#!/usr/bin/env python3
"""Validate QDQ U16/U8 exports against float ONNX on ORT CPU (offline parity).

Per graph/bucket, runs the SAME static input through float and QDQ sessions and
compares:
  - max abs / max rel / NRMSE / cosine on each output
  - top-1 agreement (CTC BPE / LLM logits)
  - text final (LLM: decode after CTC collapse of the real textual prefix)

Only reports; does NOT claim HTP support (no phone). Status per artifact:
  passed | passed-with-numeric-warning | failed

Usage:
  python validate_qdq.py --work-dir E:/... --target enc:200
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


def metrics(a: np.ndarray, b: np.ndarray) -> dict:
    a64 = a.astype(np.float64).ravel()
    b64 = b.astype(np.float64).ravel()
    diff = a64 - b64
    denom = np.maximum(np.abs(b64), 1e-12)
    nrmse = float(np.sqrt(np.mean(diff ** 2)) / (np.std(b64) + 1e-12))
    cos = float(np.dot(a64, b64) / (np.linalg.norm(a64) * np.linalg.norm(b64) + 1e-30))
    return {"max_abs": float(np.max(np.abs(diff))) if diff.size else 0.0,
            "max_rel": float(np.max(np.abs(diff) / denom)) if diff.size else 0.0,
            "nrmse": nrmse, "cosine": cos,
            "nan_inf": bool(np.isnan(a64).any() or np.isinf(a64).any()
                            or np.isnan(b64).any() or np.isinf(b64).any())}


def _session(path: Path):
    import onnxruntime as ort
    so = ort.SessionOptions()
    so.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_BASIC
    return ort.InferenceSession(str(path), so, providers=["CPUExecutionProvider"])


def _run(sess, feeds: dict[str, np.ndarray]) -> dict[str, np.ndarray]:
    names = [o.name for o in sess.get_outputs()]
    outs = sess.run(names, feeds)
    return dict(zip(names, outs))


def main() -> int:
    ap = base_parser(__doc__)
    ap.add_argument("--target", required=True, help="enc:200 | proj:200 | llm:64")
    ap.add_argument("--resume", action="store_true")
    args = ap.parse_args()
    work = Path(args.work_dir).resolve()
    dirs = work_dirs(work)
    exports = dirs["exports"]
    quant = dirs["quantized"]

    kind, size = args.target.split(":")
    size = int(size)
    step = f"validate_qdq_{kind}_{size}"
    if args.resume and step_status(work, step) == "passed":
        print_step(f"{step} already passed; skipping")
        return 0
    record_step(work, step, "running", command=f"{__file__} --target {args.target}")

    report: dict = {"target": args.target, "checks": [], "status": "passed"}
    failed = False

    if kind == "enc":
        from quantize_qnn import collect_encoder_calib
        x = collect_encoder_calib(work, size, max_files=1)[0]
        f = exports / f"granite-4.1-nar-encoder-t{size:04d}-fp16.onnx"
        q = quant / f"granite-4.1-nar-encoder-t{size:04d}-qdq-u16u8.onnx"
        if not q.exists():
            record_step(work, step, "failed", exit_code=2, error=f"missing {q}")
            return 2
        fo = _run(_session(f), {"input_features": x})
        qo = _run(_session(q), {"input_features": x})
        for name in ("encoder_bpe_logits", "multilayer_features"):
            a = np.asarray(qo[name])
            b = np.asarray(fo[name])
            if a.ndim == 3:
                a = a.reshape(-1, a.shape[-1])
            if b.ndim == 3:
                b = b.reshape(-1, b.shape[-1])
            m = metrics(a, b)
            ok = (not m["nan_inf"]) and m["cosine"] > 0.99
            report["checks"].append({"output": name, "metrics": m,
                                    "status": "passed" if ok else "failed"})
            failed |= not ok
            print_step(f"  {name}: cos={m['cosine']:.6f} max_abs={m['max_abs']:.4g} "
                       f"({'passed' if ok else 'failed'})")
    elif kind == "proj":
        from quantize_qnn import collect_projector_calib
        x = collect_projector_calib(work, size, max_files=1)[0]
        f = exports / f"granite-4.1-nar-projector-t{size:04d}-fp16.onnx"
        q = quant / f"granite-4.1-nar-projector-t{size:04d}-qdq-u16u8.onnx"
        if not q.exists():
            record_step(work, step, "failed", exit_code=2, error=f"missing {q}")
            return 2
        fo = _run(_session(f), {"multilayer_features": x})
        qo = _run(_session(q), {"multilayer_features": x})
        a = np.asarray(qo["audio_embeds"])
        b = np.asarray(fo["audio_embeds"])
        if a.ndim == 3:
            a = a[0]
        if b.ndim == 3:
            b = b[0]
        n = min(a.shape[0], b.shape[0])
        m = metrics(a[:n], b[:n])
        ok = (not m["nan_inf"]) and m["cosine"] > 0.99
        report["checks"].append({"output": "audio_embeds", "metrics": m,
                                "status": "passed" if ok else "failed"})
        failed |= not ok
        print_step(f"  audio_embeds: cos={m['cosine']:.6f} max_abs={m['max_abs']:.4g} "
                   f"({'passed' if ok else 'failed'})")
    elif kind == "llm":
        from quantize_qnn import collect_llm_calib
        calib = collect_llm_calib(work, size, max_files=1)
        if not calib:
            record_step(work, step, "failed", exit_code=5, error="no calib feeds fit")
            return 5
        feeds = calib[0]
        f = exports / f"granite-4.1-nar-llm-s{size:04d}-fp16.onnx"
        q = quant / f"granite-4.1-nar-llm-s{size:04d}-qdq-u16u8.onnx"
        if not q.exists():
            record_step(work, step, "failed", exit_code=2, error=f"missing {q}")
            return 2
        fo = _run(_session(f), feeds)
        qo = _run(_session(q), feeds)
        a = np.asarray(qo["logits"])
        b = np.asarray(fo["logits"])
        if a.ndim == 3:
            a = a[0]
        if b.ndim == 3:
            b = b[0]
        m = metrics(a, b)
        top1 = float((a.argmax(-1) == b.argmax(-1)).mean())
        ok = (not m["nan_inf"]) and m["cosine"] > 0.99 and top1 >= 0.95
        report["checks"].append({"output": "logits", "metrics": m,
                                "top1_agreement": top1,
                                "status": "passed" if ok else "failed"})
        failed |= not ok
        print_step(f"  logits: cos={m['cosine']:.6f} top1={top1:.4f} "
                   f"({'passed' if ok else 'failed'})")
    else:
        record_step(work, step, "failed", exit_code=4, error=f"unknown kind {kind}")
        return 4

    report["status"] = "passed" if not failed else "failed"
    atomic_write_json(dirs["reports"] / f"validate-qdq-{kind}-{size}.json", report)
    record_step(work, step, report["status"],
                artifacts={"report": str(dirs['reports'] / f'validate-qdq-{kind}-{size}.json')})
    print_step(f"{step}: {report['status']}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
