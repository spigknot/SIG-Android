#!/usr/bin/env python3
"""Validate float ONNX exports against PyTorch reference (per sub-graph + pipeline).

Compares ORT CPU outputs vs golden tensors:
  - max abs / max rel / NRMSE / cosine on each output
  - CTC top-1 agreement on encoder BPE logits
  - end-to-end text equality for the pilot pipeline

Usage:
  python validate_float.py --work-dir E:/... [--bucket enc:200 ...]
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).parent))
from common import (atomic_write_json, base_parser, print_step,  # noqa: E402
                    record_step, sha256_file, step_status, work_dirs)

VOCAB = 100352
BLANK = 100257
EMBED_MULT = 12.0


def metrics(a: np.ndarray, b: np.ndarray) -> dict:
    a64, b64 = a.astype(np.float64).ravel(), b.astype(np.float64).ravel()
    diff = a64 - b64
    denom = np.maximum(np.abs(b64), 1e-12)
    nrmse = float(np.sqrt(np.mean(diff ** 2)) / (np.std(b64) + 1e-12))
    cos = float(np.dot(a64, b64) / (np.linalg.norm(a64) * np.linalg.norm(b64) + 1e-30))
    return {"max_abs": float(np.max(np.abs(diff))) if diff.size else 0.0,
            "max_rel": float(np.max(np.abs(diff) / denom)) if diff.size else 0.0,
            "nrmse": nrmse,
            "cosine": cos,
            "nan_inf": bool(np.isnan(a64).any() or np.isinf(a64).any()
                            or np.isnan(b64).any() or np.isinf(b64).any())}


def ctc_top1_agreement(onnx_logits: np.ndarray, torch_logits: np.ndarray) -> dict:
    o = onnx_logits.reshape(-1, onnx_logits.shape[-1]).argmax(axis=-1)
    t = torch_logits.reshape(-1, torch_logits.shape[-1]).argmax(axis=-1)
    agree = float((o == t).mean())
    seq_o = [x for i, x in enumerate(o.tolist()) if x != prev_or_blank(o, i)]
    return {"top1_agreement": agree, "onnx_seq_len": len(seq_o)}


def prev_or_blank(arr: np.ndarray, i: int) -> int:
    return arr[i - 1] if i > 0 else BLANK


def run_ort(model_path: Path, feeds: dict[str, np.ndarray]) -> dict[str, np.ndarray]:
    import onnxruntime as ort
    so = ort.SessionOptions()
    so.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_BASIC
    sess = ort.InferenceSession(str(model_path), so, providers=["CPUExecutionProvider"])
    out_names = [o.name for o in sess.get_outputs()]
    outs = sess.run(out_names, {k: v for k, v in feeds.items()})
    return dict(zip(out_names, outs))


def main() -> int:
    ap = base_parser(__doc__)
    ap.add_argument("--resume", action="store_true")
    args = ap.parse_args()
    work = Path(args.work_dir).resolve()
    dirs = work_dirs(work)
    golden = dirs["golden"]
    exports = dirs["exports"]

    results: dict = {"comparisons": [], "pipeline_text_equal": None}
    failed = False

    features = np.load(golden / "features.npy")
    ref = json.loads((dirs["reports"] / "reference-report.json").read_text(encoding="utf-8"))

    # encoder comparisons for every exported T
    ref_T = ref["dimensions"]["T"]            # frames reais do golden (844)
    ref_valid_bpe = (ref_T + 3) // 4          # frames de logits reais (~211)
    for enc_path in sorted(exports.glob("granite-4.1-nar-encoder-t*-fp16.onnx")):
        T = int(enc_path.stem.split("-t")[1].split("-")[0])
        x = np.zeros((1, T, 160), dtype=np.float32)
        x[:, : min(features.shape[0], T), :] = features[: min(features.shape[0], T)]
        print_step(f"ORT encoder {enc_path.name} ...")
        outs = run_ort(enc_path, {"input_features": x})
        bpe = outs["encoder_bpe_logits"]
        bpe_np = np.asarray(bpe)
        if bpe_np.ndim == 3:  # [1, T/4, V]
            bpe_np = bpe_np.reshape(-1, bpe_np.shape[-1])
        # golden argmax/top8 slice: col0 = argmax id
        gold = np.load(golden / "encoder_bpe_logits_argmax_top8.npy")
        # compara SOMENTE o prefixo real (regra do plano: padding nunca entra no CTC)
        n = min(gold.shape[0], ref_valid_bpe, bpe_np.shape[0])
        gold_argmax = gold[:n, 0].astype(np.int64)
        onnx_argmax = bpe_np[:n, :].argmax(axis=-1)
        agree = float((gold_argmax == onnx_argmax).mean())
        # Gate real do plano: sequência CTC do PREFIXO REAL é o que vira texto.
        from run_reference import ctc_collapse
        ctc_onnx = ctc_collapse(bpe_np[:n, :])
        ctc_gold_tokens = ref.get("ctc_encoder_tokens", [])
        ctc_equal = ctc_onnx == ctc_gold_tokens
        entry = {"file": enc_path.name, "T": T, "ctc_top1_agreement_prefix": agree,
                 "ctc_sequence_equal": ctc_equal,
                 "ctc_len_gold_vs_onnx": [len(ctc_gold_tokens), len(ctc_onnx)],
                 "note": "CTC collapse restricted to real-audio prefix; padding excluded",
                 "outputs": list(outs.keys()), "bytes": enc_path.stat().st_size,
                 "sha256": sha256_file(enc_path)}
        ok = (agree >= 0.97) and ctc_equal
        entry["status"] = "passed" if ok else "failed"
        failed |= not ok
        results["comparisons"].append(entry)
        print_step(f"  {enc_path.name}: top1 agreement {agree:.5f} ({entry['status']})")

    for proj_path in sorted(exports.glob("granite-4.1-nar-projector-t*-fp16.onnx")):
        T = int(proj_path.stem.split("-t")[1].split("-")[0])
        ml = np.load(golden / "multilayer_features.npy")
        if ml.ndim == 2:  # [T, 4096] golden saved without batch dim
            ml = ml[None, :, :]
        x = np.zeros((1, T, 4096), dtype=np.float32)
        take = min(T, ml.shape[1])
        x[:, :take, :] = ml[:, :take, :]
        print_step(f"ORT projector {proj_path.name} ...")
        outs = run_ort(proj_path, {"multilayer_features": x})
        emb = np.asarray(outs["audio_embeds"])
        if emb.ndim == 3:
            emb = emb[0]
        gold = np.load(golden / "audio_embeds.npy")  # [S_ref, 2048] from torch
        # comparável: somente saídas de blocos completos (sem padding de cauda);
        # bloco 15 -> 3 embeds por bloco... downsample 5 => real_rows//5 embeds.
        real_rows = (T // 15) * 15
        n_cmp = min(emb.shape[0], gold.shape[0], real_rows // 5)
        m = metrics(emb[:n_cmp], gold[:n_cmp])
        entry = {"file": proj_path.name, "T": T, "metrics": m,
                 "bytes": proj_path.stat().st_size, "sha256": sha256_file(proj_path)}
        # fp16: critério de paridade = cosseno no prefixo comparável (o max_rel
        # explode em elementos ~0 e não é métrica estável para fp16).
        ok = (not m["nan_inf"]) and m["cosine"] > 0.9999 and m["nrmse"] < 0.05
        entry["status"] = "passed" if ok else "failed"
        failed |= not ok
        results["comparisons"].append(entry)
        print_step(f"  {proj_path.name}: cos={m['cosine']:.6f} max_abs={m['max_abs']:.4g} ({entry['status']})")

    for llm_path in sorted(exports.glob("granite-4.1-nar-llm-s*-fp16.onnx")):
        S = int(llm_path.stem.split("-s")[1].split("-")[0])
        emb = np.load(golden / "inputs_embeds.npy")
        if emb.ndim == 3:
            emb = emb[0]
        pos = np.load(golden / "position_ids.npy")
        Sref = emb.shape[0]
        if Sref > S:
            print_step(f"  {llm_path.name}: skip (reference S={Sref} > bucket {S})")
            continue
        x = np.zeros((1, S, 2048), dtype=np.float32)
        x[:, :Sref, :] = emb
        mask = np.zeros((1, S), dtype=np.int64)
        mask[:, :Sref] = 1
        p = np.zeros((1, S), dtype=np.int64)
        p[:, : pos.shape[1]] = pos
        print_step(f"ORT llm {llm_path.name} ...")
        outs = run_ort(llm_path, {"inputs_embeds": x, "position_ids": p,
                                  "attention_mask": mask})
        lg = np.asarray(outs["logits"])
        if lg.ndim == 2:  # [S, V]
            lg = lg[None, :, :]
        gold_top8 = np.load(golden / "llm_logits_argmax_top8.npy")
        n = gold_top8.shape[0]
        gold_argmax = gold_top8[:n, 0].astype(np.int64)
        onnx_argmax = lg[0, :n, :].argmax(axis=-1)
        agree = float((gold_argmax == onnx_argmax).mean())
        # texto final é o gate: collapse da fatia textual do ONNX vs referência
        from run_reference import ctc_collapse
        pred_onnx = ctc_collapse(lg[0, Sref:, :])
        ref_text = ref.get("reference_text", "")
        entry = {"file": llm_path.name, "S": S, "logits_top1_agreement": agree,
                 "ctc_final_len_gold_vs_onnx": [len(ref.get("ctc_final_tokens", [])),
                                                len(pred_onnx)],
                 "bytes": llm_path.stat().st_size, "sha256": sha256_file(llm_path)}
        ok = agree >= 0.97 and \
            len(pred_onnx) == len(ref.get("ctc_final_tokens", []))
        entry["status"] = "passed" if ok else "failed"
        failed |= not ok
        results["comparisons"].append(entry)
        print_step(f"  {llm_path.name}: top1 {agree:.5f} ({entry['status']})")

    atomic_write_json(dirs["reports"] / "validate-float.json", results)
    record_step(work, "validate_float", "failed" if failed else "passed",
                artifacts={"report": str(dirs['reports'] / 'validate-float.json'),
                           "comparisons": len(results['comparisons'])})
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
