#!/usr/bin/env python3
"""Gate de máscara do LLM (docs/prompt-operador §10): 3 valores reais de S < bucket.

Para cada S_test em {40, 48, 56} (menores que o bucket 64):
  1. PyTorch com S exato (referência);
  2. PyTorch com padding até 64 + attention_mask;
  3. ONNX S=64 com o mesmo padding/máscara.
Compara logits APENAS no prefixo real e exige: máscara preserva o prefixo
(torch exato vs torch padded: cos > 0.9999) e ONNX vs torch-padded igual.

Usage:
  python mask_gate.py --work-dir E:/... [--s-tests 40,48,56] [--bucket 64]
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).parent))
from common import (atomic_write_json, base_parser, print_step,  # noqa: E402
                    record_step, sha256_file, work_dirs)


def cos(a: np.ndarray, b: np.ndarray) -> float:
    a = a.astype(np.float64).ravel()
    b = b.astype(np.float64).ravel()
    return float(np.dot(a, b) / (np.linalg.norm(a) * np.linalg.norm(b) + 1e-30))


def max_abs(a: np.ndarray, b: np.ndarray) -> float:
    return float(np.max(np.abs(a.astype(np.float64) - b.astype(np.float64))))


def main() -> int:
    ap = base_parser(__doc__)
    ap.add_argument("--s-tests", default="40,48,56")
    ap.add_argument("--bucket", type=int, default=64)
    ap.add_argument("--resume", action="store_true")
    args = ap.parse_args()
    work = Path(args.work_dir).resolve()
    dirs = work_dirs(work)
    golden = dirs["golden"]
    exports = dirs["exports"]

    step = f"mask_gate_s{args.bucket:04d}"
    if args.resume and (dirs["state"] / "run-state.json").exists():
        try:
            st = json.loads((dirs["state"] / "run-state.json").read_text(encoding="utf-8"))
            if st.get("steps", {}).get(step, {}).get("status") == "passed":
                print_step(f"{step} already passed; skipping")
                return 0
        except Exception:  # noqa: BLE001
            pass
    record_step(work, step, "running", command=f"{__file__} --bucket {args.bucket}")

    s_tests = [int(s) for s in args.s_tests.split(",")]
    bucket = args.bucket
    results = {"bucket": bucket, "s_tests": [], "all_passed": True}

    # LLM ONNX wrapper path
    import onnxruntime as ort
    llm_path = exports / f"granite-4.1-nar-llm-s{bucket:04d}-fp16.onnx"
    if not llm_path.exists():
        record_step(work, step, "failed", exit_code=2, error=f"missing {llm_path}")
        return 2
    so = ort.SessionOptions()
    so.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_BASIC
    sess = ort.InferenceSession(str(llm_path), so, providers=["CPUExecutionProvider"])

    import torch
    from transformers import AutoModel
    source = dirs["source"]
    print_step("loading torch model fp16 (shared across tests) ...")
    model = AutoModel.from_pretrained(str(source), trust_remote_code=True,
                                      attn_implementation="sdpa",
                                      dtype=torch.float16).eval()
    lm = model.language_model
    lm_dtype = lm.lm_head.weight.dtype

    # Golden sequence from reference run (audio embeds + slot embeddings)
    audio_emb = np.load(golden / "audio_embeds.npy")          # [valid_audio, 2048]
    inputs_embeds = np.load(golden / "inputs_embeds.npy")     # [S_ref, 2048] incl. slots
    if inputs_embeds.ndim == 3:
        inputs_embeds = inputs_embeds[0]
    S_ref = inputs_embeds.shape[0]
    valid_audio = audio_emb.shape[0]
    text_len = S_ref - valid_audio

    def torch_logits_exact(x_f32: np.ndarray) -> np.ndarray:
        """Run the LLM with exact sequence length S (bidirectional, no mask)."""
        with torch.no_grad():
            emb = torch.from_numpy(x_f32).to(lm_dtype).unsqueeze(0)
            pos = torch.arange(emb.shape[1]).unsqueeze(0)
            out = lm(inputs_embeds=emb, position_ids=pos)
            return out.logits.float()[0].numpy()

    def torch_logits_padded(x_f32: np.ndarray, mask: np.ndarray) -> np.ndarray:
        with torch.no_grad():
            emb = torch.from_numpy(x_f32).to(lm_dtype).unsqueeze(0)
            pos = torch.arange(emb.shape[1]).unsqueeze(0)
            m = torch.from_numpy(mask).unsqueeze(0)  # [1, S] para o modelo
            out = lm(inputs_embeds=emb, position_ids=pos, attention_mask=m)
            return out.logits.float()[0].numpy()

    for S_test in s_tests:
        if S_test >= bucket:
            print_step(f"skip S={S_test} (>= bucket {bucket})")
            continue
        if S_test > S_ref:
            print_step(f"skip S={S_test} (> reference S={S_ref})")
            continue
        print_step(f"mask gate S={S_test} ...")

        # exact run: first S_test rows of the golden sequence
        x_exact = inputs_embeds[:S_test]
        ref_exact = torch_logits_exact(x_exact)

        # padded run: bucket rows, mask=1 on prefix
        x_pad = np.zeros((bucket, 2048), dtype=np.float32)
        x_pad[:S_test] = inputs_embeds[:S_test]
        mask = np.zeros((bucket,), dtype=np.int64)
        mask[:S_test] = 1
        ref_pad = torch_logits_padded(x_pad, mask)

        # gate 1: mask preserves prefix in torch
        g1_cos = cos(ref_pad[:S_test], ref_exact)
        g1_max = max_abs(ref_pad[:S_test], ref_exact)

        # gate 2: ONNX padded vs torch padded
        sess_out = sess.run(["logits"], {
            "inputs_embeds": x_pad[None, :, :],
            "position_ids": np.arange(bucket, dtype=np.int64)[None, :],
            "attention_mask": mask[None, :],
        })
        onnx_logits = np.asarray(sess_out[0])
        if onnx_logits.ndim == 3:
            onnx_logits = onnx_logits[0]
        g2_cos = cos(onnx_logits[:S_test], ref_pad[:S_test])
        g2_max = max_abs(onnx_logits[:S_test], ref_pad[:S_test])

        # top-1 agreement on prefix
        agree = float((ref_exact[:S_test].argmax(-1) ==
                       onnx_logits[:S_test].argmax(-1)).mean())

        ok = g1_cos > 0.9999 and g2_cos > 0.999 and agree >= 0.97
        results["s_tests"].append({
            "S": S_test, "bucket": bucket,
            "torch_mask_prefix_cos": g1_cos, "torch_mask_prefix_max_abs": g1_max,
            "onnx_vs_torch_prefix_cos": g2_cos, "onnx_vs_torch_prefix_max_abs": g2_max,
            "top1_agreement": agree, "status": "passed" if ok else "failed"})
        results["all_passed"] &= ok
        print_step(f"  S={S_test}: g1_cos={g1_cos:.6f} g2_cos={g2_cos:.6f} "
                   f"top1={agree:.4f} ({'passed' if ok else 'failed'})")

    del model, lm
    import gc
    gc.collect()

    atomic_write_json(dirs["reports"] / f"mask-gate-s{bucket:04d}.json", results)
    record_step(work, step, "passed" if results["all_passed"] else "failed",
                artifacts={"report": str(dirs['reports'] / f'mask-gate-s{bucket:04d}.json'),
                           "tests": len(results["s_tests"])})
    return 0 if results["all_passed"] else 1


if __name__ == "__main__":
    sys.exit(main())
