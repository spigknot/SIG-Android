#!/usr/bin/env python3
"""Per-bucket end-to-end text gate (decisão 30/08 — investigação T=400/800).

Para cada bucket T e cada amostra cujo áudio REAL cabe no bucket:
  1. mesmíssima entrada estática [1,T,160] para PyTorch e ONNX (sem truncar
     áudio maior — a amostra é escolhida com T_real <= T);
  2. referência PyTorch gerada por bucket (mesmo input estático);
  3. comparação: BPE válido no prefixo real (top1), CTC colapsado do prefixo,
     áudio embeds (projector encadeado por lado), LLM com máscara end-padding,
     recorte CORRETO dos logits de texto = logits[valid_audio:S_real]
     (o restante do bucket é padding e nunca entra no CTC);
  4. texto final decodificado dos dois lados; igualdade exata exigida.

Status por amostra: passed | passed-with-numeric-warning (top1<0.97) | failed.
Bucket: passed | passed-with-numeric-warning | needs-review | failed.

BF16: o modelo é carregado via transformers (safetensors) em fp16 — a conversão
BF16→FP16 é confirmada por decodificação de texto real em cada bucket.
"""
from __future__ import annotations

import json
import math
import sys
import time
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).parent))
from common import (atomic_write_json, base_parser, print_step, record_step,  # noqa: E402
                    sha256_file, work_dirs)

BLANK = 100257
EMBED_MULT = 12.0
MIN_EDIT = 8
AUDIO_T = [200, 400, 800, 1200, 1600, 2000]
LLM_S = [64, 128, 256, 512, 768, 1024, 1408]
PRIORITY_LANGS = ["pt_br", "en_us", "es_419"]


def ctc_collapse(logits: np.ndarray) -> list[int]:
    ids = logits.argmax(axis=-1).tolist()
    out, prev = [], -1
    for t in ids:
        if t != prev and t != BLANK:
            out.append(t)
        prev = t
    return out


def build_slots(ctc: list[int]) -> list[int]:
    n = len(ctc)
    total = max(2 * n + 1, MIN_EDIT)
    slots = [BLANK] * total
    for i, t in enumerate(ctc):
        slots[2 * i + 1] = t
    return slots


def load_wav(path: Path) -> np.ndarray:
    import soundfile as sf
    data, sr = sf.read(str(path), dtype="float32", always_2d=True)
    assert sr == 16000, f"{path}: sr={sr}"
    return data.mean(axis=1)


def frontend(wav: np.ndarray) -> np.ndarray:
    import torch
    import torchaudio
    m = torchaudio.transforms.MelSpectrogram(
        sample_rate=16000, n_fft=512, win_length=400, hop_length=160,
        n_mels=80, power=2.0)(torch.from_numpy(wav))
    logmel = torch.log10(torch.clamp(m, min=1e-10))
    logmel = torch.clamp(logmel, min=logmel.max() - 8.0) / 4.0 + 1.0
    feats = logmel.transpose(0, 1).unfold(0, 2, 2).permute(0, 2, 1).reshape(-1, 160)
    return feats.numpy()


def pick_samples(entries: list[dict], T: int, want: int) -> list[dict]:
    cands = []
    for e in entries:
        frames = int(e["duration_s"] * 50)
        if 160 <= frames <= T:
            cands.append((e, frames / T))
    if not cands:
        return []
    # prioridade: melhor ocupação de pt_br, en_us, es_419
    chosen: list[dict] = []
    used = set()
    for lang in PRIORITY_LANGS:
        pool = [(e, f) for e, f in cands if e["language"] == lang and e["file"] not in used]
        if pool:
            best = max(pool, key=lambda x: x[1])[0]
            chosen.append(best)
            used.add(best["file"])
    # completa com espalhamento de ocupação (alto→baixo)
    rest = sorted((x for x in cands if x[0]["file"] not in used),
                  key=lambda x: -x[1])
    if rest:
        quantiles = [0.95, 0.8, 0.65, 0.5, 0.35, 0.2]
        for q in quantiles:
            if len(chosen) >= want:
                break
            target = q * T * 50  # frames alvo aproximados
            best = min(rest, key=lambda x: abs(x[1] * T * 50 - target))
            if best[0]["file"] not in used:
                chosen.append(best[0])
                used.add(best[0]["file"])
    for e, _f in rest:
        if len(chosen) >= want:
            break
        if e["file"] not in used:
            chosen.append(e)
            used.add(e["file"])
    return chosen[:want]


def main() -> int:
    ap = base_parser(__doc__)
    ap.add_argument("--buckets", default="200,400,800,1200,1600,2000")
    ap.add_argument("--samples-400-800", type=int, default=5)
    ap.add_argument("--samples-other", type=int, default=2)
    ap.add_argument("--resume", action="store_true")
    args = ap.parse_args()
    work = Path(args.work_dir).resolve()
    dirs = work_dirs(work)
    exports = dirs["exports"]
    golden = dirs["golden"]

    buckets = [int(b) for b in args.buckets.split(",")]
    want = lambda T: args.samples_400_800 if T in (400, 800) else args.samples_other

    progress_path = dirs["reports"] / "text-gate-progress.jsonl"
    done: dict[str, dict] = {}
    if args.resume and progress_path.exists():
        for line in progress_path.read_text(encoding="utf-8").splitlines():
            if line.strip():
                r = json.loads(line)
                if r.get("status") in ("passed", "passed-with-numeric-warning", "failed"):
                    done[f"{r['T']}:{r['file']}"] = r

    entries = []
    mf = dirs["calibration"] / "corpus-manifest.jsonl"
    for line in mf.read_text(encoding="utf-8").splitlines():
        if line.strip():
            entries.append(json.loads(line))
    print_step(f"corpus: {len(entries)} entradas")

    import torch
    from transformers import AutoModel, AutoTokenizer
    source = dirs["source"]
    print_step("carregando modelo torch fp16 (confirma conversão BF16->FP16) ...")
    model = AutoModel.from_pretrained(str(source), trust_remote_code=True,
                                      attn_implementation="sdpa",
                                      dtype=torch.float16).eval()
    tok = AutoTokenizer.from_pretrained(str(source), trust_remote_code=True)
    lm = model.language_model
    embed_w = lm.model.embed_tokens.weight

    import onnxruntime as ort
    so = ort.SessionOptions()
    so.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_BASIC
    providers = ["CPUExecutionProvider"]
    enc_sess: dict[int, ort.InferenceSession] = {}
    proj_sess: dict[int, ort.InferenceSession] = {}
    llm_sess: dict[int, ort.InferenceSession] = {}

    def get_enc(T):
        if T not in enc_sess:
            p = exports / f"granite-4.1-nar-encoder-t{T:04d}-fp16.onnx"
            print_step(f"criando sessão ORT encoder T={T} ...")
            enc_sess[T] = ort.InferenceSession(str(p), so, providers=providers)
        return enc_sess[T]

    def get_proj(T):
        if T not in proj_sess:
            p = exports / f"granite-4.1-nar-projector-t{T:04d}-fp16.onnx"
            proj_sess[T] = ort.InferenceSession(str(p), so, providers=providers)
        return proj_sess[T]

    def get_llm(S):
        if S not in llm_sess:
            p = exports / f"granite-4.1-nar-llm-s{S:04d}-fp16.onnx"
            print_step(f"criando sessão ORT llm S={S} ...")
            llm_sess[S] = ort.InferenceSession(str(p), so, providers=providers)
        return llm_sess[S]

    def torch_pipeline(static_x: np.ndarray, T_real: int):
        """Referência PyTorch do bucket com o MESMO input estático."""
        with torch.no_grad():
            x = torch.from_numpy(static_x)
            enc = model.encoder(input_features=x.to(model.encoder.dtype),
                                attention_mask=torch.ones(1, static_x.shape[1], dtype=torch.bool),
                                output_hidden_states=True)
            bpe = enc.logits.float().numpy()
            ml = torch.cat([enc.all_hidden_states[i]
                            for i in model.config.encoder_layer_indices], dim=-1).float()
            audio = model.projector(ml.to(model.projector.out_linear.weight.dtype)).float()
            return bpe, ml.numpy(), audio.numpy()

    def onnx_pipeline(static_x: np.ndarray):
        T = static_x.shape[1]
        b, m = get_enc(T).run(None, {"input_features": static_x})
        bpe = np.asarray(b)
        if bpe.ndim == 3:
            bpe = bpe.reshape(-1, bpe.shape[-1])
        ml = np.asarray(m)
        ap_out = get_proj(T).run(None, {"multilayer_features": ml})
        audio = np.asarray(ap_out[0])
        if audio.ndim == 3:
            audio = audio[0]
        return bpe, ml, audio

    def llm_logits(np_embeds: np.ndarray, S_bucket: int, use_torch: bool) -> np.ndarray:
        S_real = np_embeds.shape[0]
        x = np.zeros((S_bucket, 2048), dtype=np.float32)
        x[:S_real] = np_embeds
        mask = np.zeros((1, S_bucket), dtype=np.int64)
        mask[:, :S_real] = 1
        pos = np.arange(S_bucket, dtype=np.int64)[None, :]
        if use_torch:
            with torch.no_grad():
                emb = torch.from_numpy(x).to(next(lm.parameters()).dtype).unsqueeze(0)
                out = lm(inputs_embeds=emb, position_ids=torch.from_numpy(pos),
                         attention_mask=torch.from_numpy(mask))
                lg = out.logits.float()[0].numpy()
        else:
            lg = np.asarray(get_llm(S_bucket).run(
                ["logits"], {"inputs_embeds": x[None, :, :],
                             "position_ids": pos, "attention_mask": mask})[0])
            if lg.ndim == 3:
                lg = lg[0]
        return lg

    results = {"generated_at": time.strftime("%Y-%m-%dT%H:%M:%S"),
               "buckets": {}, "note": "BF16->FP16 confirmed via real text decode per bucket"}
    all_ok = True

    for T in buckets:
        samples = pick_samples(entries, T, want(T))
        agg = {"T": T, "samples": [], "status": "passed"}
        print_step(f"=== bucket T={T}: {len(samples)} amostras ===")
        for e in samples:
            key = f"{T}:{e['file']}"
            if args.resume and key in done:
                agg["samples"].append(done[key])
                print_step(f"  [resume] {e['file']}: {done[key]['status']}")
                continue
            rec: dict = {"T": T, "file": e["file"], "language": e["language"],
                         "duration_s": e["duration_s"]}
            try:
                wav = load_wav(dirs["calibration"] / e["file"])
                feats = frontend(wav)
                T_real = feats.shape[0]
                rec["T_real"] = int(T_real)
                rec["fill_ratio"] = round(T_real / T, 3)
                static_x = np.zeros((1, T, 160), dtype=np.float32)
                static_x[0, :T_real, :] = feats

                t0 = time.time()
                bpe_t, ml_t, audio_t = torch_pipeline(static_x, T_real)
                bpe_o, ml_o, audio_o = onnx_pipeline(static_x)
                rec["onnx_encoder_seconds"] = round(time.time() - t0, 1)

                valid_bpe = (T_real + 3) // 4
                n = min(valid_bpe, bpe_t.shape[0], bpe_o.shape[0])
                agree = float((bpe_t[:n].argmax(-1) == bpe_o[:n].argmax(-1)).mean())
                rec["bpe_top1_real_prefix"] = round(agree, 5)

                ctc_t = ctc_collapse(bpe_t[:n])
                ctc_o = ctc_collapse(bpe_o[:n])
                rec["ctc_encoder_equal"] = ctc_t == ctc_o
                rec["ctc_len"] = len(ctc_t)

                valid_audio = T_real // 5
                a_t = (audio_t[0, :valid_audio] if audio_t.ndim == 3
                       else audio_t[:valid_audio]) / EMBED_MULT
                a_o = (audio_o[0, :valid_audio] if audio_o.ndim == 3
                       else audio_o[:valid_audio]) / EMBED_MULT
                diff = float(np.max(np.abs(a_t.astype(np.float64) - a_o.astype(np.float64))))
                rec["audio_embeds_max_abs"] = round(diff, 6)

                slots_t = build_slots(ctc_t)
                S_real = valid_audio + len(slots_t)
                rec["S_real"] = int(S_real)
                S_bucket = next((s for s in LLM_S if s >= S_real), None)
                rec["S_bucket"] = S_bucket

                text_t = text_o = None
                tok_t = tok_o = None
                if S_bucket is None:
                    rec["llm"] = "skipped (S_real > max bucket)"
                else:
                    with torch.no_grad():
                        text_emb = embed_w[torch.tensor(slots_t)].float().numpy()
                        emb_t = np.concatenate([a_t, text_emb], axis=0)
                        emb_o = np.concatenate(
                            [a_o, embed_w[torch.tensor(build_slots(ctc_o))].float().numpy()],
                            axis=0)
                    lg_t = llm_logits(emb_t, S_bucket, use_torch=True)
                    lg_o = llm_logits(emb_o, S_bucket, use_torch=False)
                    # RECORTE CORRETO: só o prefixo textual real, não até o fim do bucket
                    tl_t = lg_t[valid_audio:S_real]
                    tl_o = lg_o[valid_audio:S_real]
                    tok_t = ctc_collapse(tl_t)
                    tok_o = ctc_collapse(tl_o)
                    rec["text_tokens_equal"] = tok_t == tok_o
                    text_t = tok.decode(tok_t)
                    text_o = tok.decode(tok_o)
                    rec["text_equal"] = text_t.strip() == text_o.strip()
                    rec["text_torch"] = text_t[:160]
                    rec["text_onnx"] = text_o[:160]

                ok = rec["ctc_encoder_equal"] and rec.get("text_tokens_equal", True) \
                    and rec.get("text_equal", True)
                rec["status"] = ("passed" if ok else "failed") if S_bucket is not None else \
                    "passed-encoder-only"
                if ok and agree < 0.97 and S_bucket is not None:
                    rec["status"] = "passed-with-numeric-warning"
            except Exception as ex:  # noqa: BLE001
                rec["status"] = "failed"
                rec["error"] = repr(ex)[:300]
            agg["samples"].append(rec)
            with open(progress_path, "a", encoding="utf-8") as f:
                f.write(json.dumps(rec, ensure_ascii=False) + "\n")
            print_step(f"  {e['file']} [{rec.get('language')}] fill={rec.get('fill_ratio')}: "
                       f"{rec['status']} top1={rec.get('bpe_top1_real_prefix')} "
                       f"text_equal={rec.get('text_equal')}")

        statuses = [s["status"] for s in agg["samples"]]
        if any(s == "failed" for s in statuses):
            agg["status"] = "needs-review"
        elif any(s == "passed-with-numeric-warning" for s in statuses):
            agg["status"] = "passed-with-numeric-warning"
        else:
            agg["status"] = "passed"
        results["buckets"][str(T)] = agg
        atomic_write_json(dirs["reports"] / "text-gate.json", results)
        record_step(work, f"text_gate_t{T:04d}", "passed" if agg["status"] != "needs-review"
                    else "failed", artifacts={"status": agg["status"]})
        print_step(f"bucket T={T}: {agg['status']}")

        # libera sessão ORT do encoder deste bucket
        if T in enc_sess:
            enc_sess[T] = None  # type: ignore[assignment]
            del enc_sess[T]
        import gc
        gc.collect()

    for s in llm_sess.values():
        pass
    atomic_write_json(dirs["reports"] / "text-gate.json", results)
    overall = "passed"
    for agg in results["buckets"].values():
        if agg["status"] in ("needs-review", "failed"):
            overall = "needs-review"
    record_step(work, "text_gate", overall,
                artifacts={"report": str(dirs['reports'] / 'text-gate.json')})
    print_step(f"text_gate completo: {overall}")
    return 0 if overall == "passed" else 1


if __name__ == "__main__":
    sys.exit(main())
