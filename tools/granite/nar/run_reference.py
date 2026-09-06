#!/usr/bin/env python3
"""Reference PyTorch inference over the official IBM WAV; golden tensors + text.

Wraps the three sub-graphs exactly as the Android engine does (front-end log-mel
+ stack2, encoder->CTC, projector /12, interleave slots, bidirectional LLM) so
the ONNX exports have a byte-level reference.

Usage:
  python run_reference.py --work-dir E:/SIG-granite-nar-lab/<id> [--resume]
"""
from __future__ import annotations

import json
import sys
import time
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).parent))
from common import (atomic_write_json, base_parser, print_step, record_step,  # noqa: E402
                    sha256_bytes, sha256_file, step_status, work_dirs)

BLANK = 100257
VOCAB = 100352
HIDDEN = 2048
EMBED_MULT = 12.0
MIN_EDIT = 8
ENCODE_LAYERS = [4, 8, 12, -1]
FRONTEND_N_FFT, FRONTEND_HOP, FRONTEND_MELS, FRONTEND_STACK = 512, 160, 80, 2


def save_npy(path: Path, arr: np.ndarray) -> dict:
    np.save(path, arr)
    return {"path": str(path), "shape": list(arr.shape), "dtype": str(arr.dtype),
            "bytes": path.stat().st_size, "sha256": sha256_file(path)}


def ctc_collapse(logits: np.ndarray) -> list[int]:
    ids = logits.argmax(axis=-1).tolist()
    out, prev = [], -1
    for t in ids:
        if t != prev and t != BLANK:
            out.append(t)
        prev = t
    return out


def build_slots(ctc_tokens: list[int]) -> list[int]:
    n = len(ctc_tokens)
    total = max(2 * n + 1, MIN_EDIT)
    slots = [BLANK] * total
    for i, t in enumerate(ctc_tokens):
        slots[2 * i + 1] = t
    return slots


def main() -> int:
    ap = base_parser(__doc__)
    ap.add_argument("--resume", action="store_true")
    args = ap.parse_args()
    work = Path(args.work_dir).resolve()
    dirs = work_dirs(work)

    if args.resume and step_status(work, "run_reference") == "passed":
        print_step("run_reference already passed; skipping")
        return 0

    record_step(work, "run_reference", "running", command=f"{__file__} --work-dir {work}")
    t0 = time.time()
    import torch
    import torchaudio
    from transformers import AutoModel

    source = dirs["source"]
    wav_path = next(source.rglob("10226_10111_000000.wav"), None)
    if wav_path is None:
        record_step(work, "run_reference", "failed", exit_code=2, error="official WAV not found in source")
        return 2

    print_step("loading model (sdpa, fp32 on CPU) ...")
    load_t0 = time.time()
    model = AutoModel.from_pretrained(
        str(source), trust_remote_code=True,
        attn_implementation="sdpa", dtype=torch.float32,
    ).eval()
    load_s = time.time() - load_t0
    print_step(f"model loaded in {load_s:.1f}s")

    import soundfile as sf
    data, sr = sf.read(str(wav_path), dtype="float32", always_2d=True)
    assert sr == 16000, f"expected 16 kHz, got {sr}"
    wav = torch.from_numpy(data.mean(axis=1))  # mono

    # ---- front-end: log-mel 80 + stack 2 -> [T,160] (same normalization as engine)
    melspec = torchaudio.transforms.MelSpectrogram(
        sample_rate=16000, n_fft=FRONTEND_N_FFT, win_length=400, hop_length=FRONTEND_HOP,
        n_mels=FRONTEND_MELS, power=2.0,
    )(wav)
    logmel = torch.log10(torch.clamp(melspec, min=1e-10))
    logmel = torch.clamp(logmel, min=logmel.max() - 8.0) / 4.0 + 1.0
    features = logmel.transpose(0, 1).unfold(0, FRONTEND_STACK, FRONTEND_STACK) \
        .permute(0, 2, 1).reshape(-1, FRONTEND_MELS * FRONTEND_STACK)
    T = features.shape[0]
    print_step(f"features: {features.shape} (T={T})")

    with torch.no_grad():
        enc = model.encoder(input_features=features.unsqueeze(0),
                            attention_mask=torch.ones(1, T, dtype=torch.bool),
                            output_hidden_states=True)
        # GraniteSpeechNarEncoderOutput: .logits = BPE head flat [1, T/4, vocab],
        # .all_hidden_states = tuple incl. input projection (index 0) + layers.
        bpe_logits = enc.logits
        hidden = enc.all_hidden_states
        idxs = model.config.encoder_layer_indices
        multilayer = torch.cat([hidden[i] for i in idxs], dim=-1)
        print_step(f"encoder ok: bpe {tuple(bpe_logits.shape)}, multilayer {tuple(multilayer.shape)} "
                   f"(layer idx {idxs})")

        ctc_tokens = ctc_collapse(bpe_logits.float().numpy())  # 2D [T/4, vocab]
        proj = model.projector(multilayer)
        audio_embeds = proj if torch.is_tensor(proj) else proj[0]
        print_step(f"projector ok: {tuple(audio_embeds.shape)}")

        slots = build_slots(ctc_tokens)
        real_frames = T
        valid_audio = real_frames // 5
        audio_part = audio_embeds[0][:valid_audio] / EMBED_MULT
        text_part = model.language_model.model.embed_tokens.weight[slots]
        inputs_embeds = torch.cat([audio_part, text_part], dim=0).unsqueeze(0)
        position_ids = torch.arange(inputs_embeds.shape[1]).unsqueeze(0)
        S = inputs_embeds.shape[1]
        print_step(f"sequence: ctc={len(ctc_tokens)} valid_audio={valid_audio} slots={len(slots)} S={S}")

        llm_logits = model.language_model(inputs_embeds=inputs_embeds,
                                          position_ids=position_ids)
        llm_logits = llm_logits if torch.is_tensor(llm_logits) else llm_logits[0]
        print_step(f"llm ok: {tuple(llm_logits.shape)}")

        text_logits = llm_logits[0][valid_audio:]
        pred = ctc_collapse(text_logits.float().numpy())

    tokenizer = None
    text = ""
    try:
        from transformers import AutoTokenizer
        tokenizer = AutoTokenizer.from_pretrained(str(source), trust_remote_code=True)
        text = tokenizer.decode(pred)
    except Exception as e:  # noqa: BLE001
        print_step(f"tokenizer decode failed ({e!r}); saving token ids only")

    golden = dirs["golden"]
    artifacts = {
        "wav": {"path": str(wav_path), "bytes": wav_path.stat().st_size,
                "sha256": sha256_file(wav_path), "duration_s": round(wav.shape[0] / 16000.0, 3),
                "sample_rate": 16000},
        "features": save_npy(golden / "features.npy", features.numpy()),
        "encoder_bpe_logits": save_npy(golden / "encoder_bpe_logits_argmax_top8.npy",
                                       _argmax_topk_slice(bpe_logits)),
        "multilayer_features": save_npy(golden / "multilayer_features.npy",
                                        multilayer[0].float().numpy()),
        "audio_embeds": save_npy(golden / "audio_embeds.npy", audio_embeds[0].float().numpy()),
        "inputs_embeds": save_npy(golden / "inputs_embeds.npy", inputs_embeds[0].float().numpy()),
        "position_ids": save_npy(golden / "position_ids.npy", position_ids.numpy()),
        "llm_logits_argmax_top8": save_npy(golden / "llm_logits_argmax_top8.npy",
                                           _argmax_topk_slice(llm_logits[0])),
    }
    report = {
        "step": "run_reference",
        "generated_at": time.strftime("%Y-%m-%dT%H:%M:%S"),
        "load_seconds": round(load_s, 1),
        "total_seconds": round(time.time() - t0, 1),
        "model_revision": "a1e3416e25ce29ab3852778e54fa8b3bd59c4bf2",
        "constants": {"blank_token_id": BLANK, "vocab_size": VOCAB, "hidden": HIDDEN,
                      "embedding_multiplier": EMBED_MULT, "downsample": 5,
                      "min_edit_sequence_length": MIN_EDIT,
                      "encoder_layer_indices": ENCODE_LAYERS},
        "dimensions": {"T": int(T), "ctc_tokens": len(ctc_tokens),
                       "valid_audio": int(valid_audio), "slots": len(slots), "S": int(S)},
        "ctc_encoder_tokens": ctc_tokens,
        "ctc_final_tokens": pred,
        "reference_text": text,
        "golden": artifacts,
    }
    atomic_write_json(dirs["reports"] / "reference-report.json", report)

    # Text hash for parity checks downstream.
    atomic_text = text
    (dirs["golden"] / "reference_text.txt").write_text(atomic_text, encoding="utf-8")
    report["reference_text_sha256"] = sha256_bytes(atomic_text.encode("utf-8"))
    atomic_write_json(dirs["reports"] / "reference-report.json", report)

    del model, bpe_logits, multilayer, audio_embeds, inputs_embeds, llm_logits
    import gc
    gc.collect()

    record_step(work, "run_reference", "passed",
                artifacts={"report": str(dirs['reports'] / 'reference-report.json'),
                           "S": int(S), "T": int(T), "text": text[:120]})
    print_step(f"run_reference passed: text={text[:80]!r}")
    return 0


def _argmax_topk_slice(logits_2d, k: int = 8) -> np.ndarray:
    """[frames, 100352] -> [frames, 1+2k]: argmax id + top-k (id, value) float32."""
    import torch
    topv, topi = logits_2d.float().topk(k, dim=-1)
    am = topi[:, 0:1]
    return torch.cat([am.to(torch.float32), topi.to(torch.float32), topv], dim=-1).numpy()


if __name__ == "__main__":
    sys.exit(main())
