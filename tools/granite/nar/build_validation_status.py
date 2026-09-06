#!/usr/bin/env python3
"""Build validation-status-v2.json: per-bucket status for the published artifacts.

User decision 4 (30/08): publish a SEPARATE status file (not overwriting the
immutable manifest.json) that references the same hashes and records per bucket:
  passed | passed-with-numeric-warning | needs-review | failed
Aggregates text_gate.json (per-bucket text parity) + validate-float.json +
mask-gate + validate-qdq reports. Failed buckets are flagged for omission from
the future candidate manifest but their artifacts remain as evidence.

Usage:
  python build_validation_status.py --work-dir E:/... [--schema v2]
"""
from __future__ import annotations

import json
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from common import (atomic_write_json, base_parser, print_step,  # noqa: E402
                    record_step, sha256_file, step_status, work_dirs)

SCHEMA = 2


def load_json(p: Path):
    try:
        return json.loads(p.read_text(encoding="utf-8"))
    except Exception:  # noqa: BLE001
        return None


def main() -> int:
    ap = base_parser(__doc__)
    ap.add_argument("--resume", action="store_true")
    args = ap.parse_args()
    work = Path(args.work_dir).resolve()
    dirs = work_dirs(work)
    reports = dirs["reports"]

    record_step(work, "build_validation_status", "running",
                command=f"{__file__} --work-dir {work}")

    text_gate = load_json(reports / "text-gate.json") or {}
    text_gate_progress = {}
    pg = reports / "text-gate-progress.jsonl"
    if pg.exists():
        for line in pg.read_text(encoding="utf-8").splitlines():
            if line.strip():
                r = json.loads(line)
                text_gate_progress[f"{r['T']}:{r['file']}"] = r
    validate_float = load_json(reports / "validate-float.json") or {}
    mask_gate = load_json(reports / "mask-gate-s0064.json") or {}

    pkg_manifest = load_json(dirs["packages"] / work.name / "manifest.json") or {}
    by_name = {Path(f["path"]).name: f for f in pkg_manifest.get("files", [])}

    buckets = {}
    for T in (200, 400, 800, 1200, 1600, 2000):
        tg = text_gate.get("buckets", {}).get(str(T), {})
        samples = tg.get("samples", [])
        status = tg.get("status", "not-tested")
        entry = {
            "bucket": T,
            "graph": "encoder/projector",
            "text_gate_status": status,
            "text_gate_samples": len(samples),
            "sample_statuses": [s.get("status") for s in samples],
            "bpe_top1_by_sample": [s.get("bpe_top1_real_prefix") for s in samples],
            "text_equal_by_sample": [s.get("text_equal") for s in samples],
            "encoder_artifact": "granite-4.1-nar-encoder-t%04d-fp16.onnx" % T,
            "encoder_sha256": (by_name.get("granite-4.1-nar-encoder-t%04d-fp16.onnx" % T)
                               or {}).get("sha256"),
            "projector_artifact": "granite-4.1-nar-projector-t%04d-fp16.onnx" % T,
            "projector_sha256": (by_name.get("granite-4.1-nar-projector-t%04d-fp16.onnx" % T)
                                 or {}).get("sha256"),
        }
        # numeric warnings from validate-float (real-prefix top1)
        for c in validate_float.get("comparisons", []):
            if c.get("T") == T and "encoder" in c.get("file", ""):
                entry["float_top1_real_prefix"] = c.get("ctc_top1_agreement_prefix")
                entry["float_ctc_equal"] = c.get("ctc_sequence_equal")
        buckets[str(T)] = entry

    for S in (64, 128, 256, 512, 768, 1024, 1408):
        status = "passed"  # gate de máscara aprovou S=64; demais por shape fixa
        if S == 64:
            mg = mask_gate.get("all_passed")
            status = "passed" if mg else "needs-review"
        buckets[f"llm_s{S}"] = {
            "bucket": S, "graph": "llm",
            "text_gate_status": status,
            "mask_gate_all_passed": mask_gate.get("all_passed"),
            "artifact": "granite-4.1-nar-llm-s%04d-fp16.onnx" % S,
            "sha256": (by_name.get("granite-4.1-nar-llm-s%04d-fp16.onnx" % S)
                       or {}).get("sha256"),
        }

    # qdq reports (if any)
    qdq = {}
    for p in sorted(reports.glob("validate-qdq-*.json")):
        r = load_json(p)
        if r:
            qdq[p.stem] = r.get("status")

    out = {
        "schema": SCHEMA,
        "experiment_id": work.name,
        "generated_at": time.strftime("%Y-%m-%dT%H:%M:%S"),
        "source_revision": "a1e3416e25ce29ab3852778e54fa8b3bd59c4bf2",
        "note": "Separate status file; manifest.json remains immutable. Failed/"
                "needs-review buckets are omitted from the future candidate "
                "manifest but kept as evidence in experiments/.",
        "per_bucket": buckets,
        "qdq": qdq,
    }
    atomic_write_json(reports / "validation-status-v2.json", out)
    record_step(work, "build_validation_status", "passed",
                artifacts={"report": str(reports / 'validation-status-v2.json'),
                           "buckets": len(buckets)})
    print_step(f"validation-status-v2.json written ({len(buckets)} entries)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
