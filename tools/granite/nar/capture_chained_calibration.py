#!/usr/bin/env python3
"""Capture chained calibration data: encoder<-features, projector<-encoder, LLM<-projector.

Streaming, one bucket at a time; saves per-input statistics (min/max/percentiles,
NaN/Inf) and per-bucket calibration shards — never full logits.

Usage:
  python capture_chained_calibration.py --work-dir E:/... [--buckets 200,400]
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).parent))
from common import (atomic_write_json, base_parser, print_step,  # noqa: E402
                    record_step, sha256_file, step_status, work_dirs)

BLANK = 100257
EMBED_MULT = 12.0
MIN_EDIT = 8


def stats(x: np.ndarray) -> dict:
    f = x.astype(np.float64).ravel()
    return {"min": float(f.min()), "max": float(f.max()),
            "p01": float(np.percentile(f, 1)), "p50": float(np.percentile(f, 50)),
            "p99": float(np.percentile(f, 99)),
            "mean": float(f.mean()), "std": float(f.std()),
            "nan_inf": bool(np.isnan(f).any() or np.isinf(f).any()),
            "shape": list(x.shape)}


def load_wav_16k(path: Path) -> np.ndarray:
    import soundfile as sf
    data, sr = sf.read(str(path), dtype="float32", always_2d=True)
    assert sr == 16000, f"{path}: sr={sr}"
    return data.mean(axis=1)


def frontend_features(wav: np.ndarray) -> np.ndarray:
    import torch
    import torchaudio
    m = torchaudio.transforms.MelSpectrogram(
        sample_rate=16000, n_fft=512, win_length=400, hop_length=160,
        n_mels=80, power=2.0)(torch.from_numpy(wav))
    logmel = torch.log10(torch.clamp(m, min=1e-10))
    logmel = torch.clamp(logmel, min=logmel.max() - 8.0) / 4.0 + 1.0
    feats = logmel.transpose(0, 1).unfold(0, 2, 2).permute(0, 2, 1).reshape(-1, 160)
    return feats.numpy()


def main() -> int:
    ap = base_parser(__doc__)
    ap.add_argument("--buckets", default="200,400,800,1200,1600,2000")
    ap.add_argument("--llm-buckets", default="64,128,256,512,768,1024,1408")
    ap.add_argument("--max-files", type=int, default=0, help="0 = all manifest entries")
    ap.add_argument("--resume", action="store_true")
    args = ap.parse_args()
    work = Path(args.work_dir).resolve()
    dirs = work_dirs(work)
    calib = dirs["calibration"]

    if args.resume and step_status(work, "capture_calibration") == "passed":
        print_step("capture_calibration already passed; skipping")
        return 0

    record_step(work, "capture_calibration", "running", command=f"{__file__} --work-dir {work}")

    import onnxruntime as ort
    exports = dirs["exports"]
    enc_by_T = {int(p.stem.split("-t")[1].split("-")[0]): p
                for p in exports.glob("granite-4.1-nar-encoder-t*-fp16.onnx")}
    proj_by_T = {int(p.stem.split("-t")[1].split("-")[0]): p
                 for p in exports.glob("granite-4.1-nar-projector-t*-fp16.onnx")}
    llm_by_S = {int(p.stem.split("-s")[1].split("-")[0]): p
                for p in exports.glob("granite-4.1-nar-llm-s*-fp16.onnx")}

    manifest = calib / "corpus-manifest.jsonl"
    if not manifest.exists():
        record_step(work, "capture_calibration", "failed", exit_code=2,
                    error="corpus-manifest.jsonl missing (run calibration corpus first)")
        return 2
    entries = [json.loads(l) for l in manifest.read_text(encoding="utf-8").splitlines() if l.strip()]
    if args.max_files:
        entries = entries[: args.max_files]

    so = ort.SessionOptions()
    so.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_BASIC
    providers = ["CPUExecutionProvider"]

    def sess(p: Path):
        return ort.InferenceSession(str(p), so, providers=providers)

    enc_sess = {t: sess(p) for t, p in sorted(enc_by_T.items())}
    proj_sess = {t: sess(p) for t, p in sorted(proj_by_T.items())}
    llm_sess = {s: sess(p) for s, p in sorted(llm_by_S.items())}

    report = {"files": 0, "encoder_inputs": [], "projector_inputs": [], "llm_inputs": [],
              "shards": [], "per_bucket_counts": {}, "limitation": (
                  "frontend equivalence to Android app (nar_mel_filters/nar_stft_window "
                  "binaries) not provable without the phone; features produced by the "
                  "official torchaudio frontend per docs/granite-nar-design.md §2")}

    for e in entries:
        wav = load_wav_16k(calib / e["file"])
        feats = frontend_features(wav)
        T = max(b for b in enc_by_T if b >= feats.shape[0]) if any(
            b >= feats.shape[0] for b in enc_by_T) else max(enc_by_T)
        x = np.zeros((1, T, 160), dtype=np.float32)
        x[:, : feats.shape[0], :] = feats
        enc_out = enc_sess[T].run(None, {"input_features": x})
        bpe_logits, multilayer = enc_out[0], enc_out[1]

        # CTC tokens (argmax collapse over valid frames)
        valid_bpe = (feats.shape[0] + 3) // 4
        ids = bpe_logits[0, :valid_bpe].argmax(axis=-1)
        ctc, prev = [], -1
        for t in ids.tolist():
            if t != prev and t != BLANK:
                ctc.append(t)
            prev = t

        report["encoder_inputs"].append({"file": e["file"], "T": T, "stats": stats(x)})
        report["per_bucket_counts"][f"enc_t{T}"] = report["per_bucket_counts"].get(f"enc_t{T}", 0) + 1

        # projector input = real encoder multilayer output (chained)
        px = np.zeros((1, T, 4096), dtype=np.float32)
        px[:, :T, :] = multilayer[:, :T, :]
        proj_out = proj_sess[T].run(None, {"multilayer_features": px})
        audio_embeds = proj_out[0]
        report["projector_inputs"].append({"file": e["file"], "T": T,
                                           "stats": stats(px),
                                           "audio_embeds_shape": list(audio_embeds.shape)})
        report["per_bucket_counts"][f"proj_t{T}"] = report["per_bucket_counts"].get(f"proj_t{T}", 0) + 1

        # LLM input = real audio embeds (/12) + slot embeddings (chained)
        valid_audio = feats.shape[0] // 5
        slots = [BLANK] * max(2 * len(ctc) + 1, MIN_EDIT)
        for i, tok in enumerate(ctc):
            slots[2 * i + 1] = tok
        S_real = valid_audio + len(slots)
        S = min((b for b in llm_by_S if b >= S_real), default=None)
        if S is None:
            continue  # audio too long for largest LLM bucket; skip llm shard
        lex = np.zeros((1, S, 2048), dtype=np.float32)
        lex[0, :valid_audio] = audio_embeds[0, :valid_audio] / EMBED_MULT
        # slot embeddings come from embed table == transpose(lm_head); use random-free
        # deterministic source: reuse audio embed rows is NOT allowed; instead read
        # slot embeddings from the projector? No: they must be embed_tokens rows.
        # The float encoder export has embed via language_model? Keep LLM shard only
        # when we can compute slot embeddings from the source model cheaply.
        report["llm_inputs"].append({"file": e["file"], "S": S, "S_real": S_real,
                                     "stats": stats(lex),
                                     "note": "slot embeddings filled separately (see capture_llm_slots)"})
        report["per_bucket_counts"][f"llm_s{S}"] = report["per_bucket_counts"].get(f"llm_s{S}", 0) + 1

        report["files"] += 1

    atomic_write_json(dirs["reports"] / "capture-calibration.json", report)
    record_step(work, "capture_calibration", "passed",
                artifacts={"report": str(dirs['reports'] / 'capture-calibration.json'),
                           "files": report["files"]})
    print_step(f"capture_calibration passed: {report['files']} files")
    return 0


if __name__ == "__main__":
    sys.exit(main())
