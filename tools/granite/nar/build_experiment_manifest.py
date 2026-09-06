#!/usr/bin/env python3
"""Build the experiment package: manifest.json + contracts + validation summaries.

Layout (docs/prompt-operador §15):
  packages/<experiment-id>/manifest.json (+ source-manifest, environment,
  contracts, validation-summary, artifacts/, diagnostics/, README.txt)

Usage:
  python build_experiment_manifest.py --work-dir E:/... [--schema 1]
"""
from __future__ import annotations

import json
import shutil
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from common import (atomic_write_json, base_parser, print_step,  # noqa: E402
                    record_step, sha256_file, sha256_bytes, step_status, work_dirs)

SCHEMA = 1


def role_for(name: str) -> str:
    if name.endswith(".onnx") and "qdq" in name:
        return "qdq"
    if name.endswith(".onnx"):
        return "float"
    if name.endswith((".json", ".jsonl")):
        return "manifest"
    if name.endswith(".txt"):
        return "diagnostic"
    return "support"


def main() -> int:
    ap = base_parser(__doc__)
    ap.add_argument("--resume", action="store_true")
    args = ap.parse_args()
    work = Path(args.work_dir).resolve()
    dirs = work_dirs(work)
    pkg = dirs["packages"] / work.name
    pkg.mkdir(parents=True, exist_ok=True)
    (pkg / "artifacts").mkdir(exist_ok=True)
    (pkg / "diagnostics").mkdir(exist_ok=True)

    record_step(work, "build_manifest", "running", command=f"{__file__} --work-dir {work}")

    # ---- gather reports
    reports = dirs["reports"]
    src_manifest = reports / "source-manifest.json"
    env_report = reports / "environment.json"
    val_float = reports / "validate-float.json"
    ref_report = reports / "reference-report.json"
    calibration_status = reports / "calibration-corpus-status.json"

    state = json.loads((dirs["state"] / "run-state.json").read_text(encoding="utf-8")) \
        if (dirs["state"] / "run-state.json").exists() else {"steps": {}}

    artifacts = []
    for f in sorted(dirs["exports"].glob("*.onnx")):
        data = f.with_name(f.name + ".data")
        rel = f"artifacts/{f.name}"
        shutil.copyfile(f, pkg / rel)
        entry = {"path": rel, "bytes": f.stat().st_size, "sha256": sha256_file(f),
                 "role": "float", "graph": ("encoder" if "-encoder-" in f.name else
                                            "projector" if "-projector-" in f.name else "llm"),
                 "bucket": (int(f.stem.split("-t")[1].split("-")[0]) if "-t" in f.stem
                            else int(f.stem.split("-s")[1].split("-")[0])),
                 "precision": "fp16-weights/f32-io", "parity": "unknown",
                 "intended_backend": "cpu-first; gpu/npu experimental"}
        artifacts.append(entry)
        if data.exists():
            rele = f"artifacts/{data.name}"
            shutil.copyfile(data, pkg / rele)
            artifacts.append({"path": rele, "bytes": data.stat().st_size,
                              "sha256": sha256_file(data), "role": "support",
                              "graph": entry["graph"], "bucket": entry["bucket"],
                              "precision": "fp16", "parity": "n/a",
                              "intended_backend": "same-as-onnx"})

    for f in sorted(dirs["quantized"].glob("*qdq*.onnx")):
        data = f.with_name(f.name + ".data")
        rel = f"artifacts/{f.name}"
        shutil.copyfile(f, pkg / rel)
        artifacts.append({"path": rel, "bytes": f.stat().st_size, "sha256": sha256_file(f),
                          "role": "qdq", "graph": "see-name", "bucket": "see-name",
                          "precision": "qdq-u16u8", "parity": "unknown",
                          "intended_backend": "npu-htp-context-ready-only"})
        if data.exists():
            shutil.copyfile(data, pkg / f"artifacts/{data.name}")
            artifacts.append({"path": f"artifacts/{data.name}", "bytes": data.stat().st_size,
                              "sha256": sha256_file(data), "role": "support",
                              "graph": "qdq-external", "bucket": "n/a",
                              "precision": "qdq", "parity": "n/a",
                              "intended_backend": "same-as-onnx"})

    # ---- parity status from validate-float report
    if val_float.exists():
        vf = json.loads(val_float.read_text(encoding="utf-8"))
        by_file = {c["file"]: c.get("status") for c in vf.get("comparisons", [])}
        for a in artifacts:
            name = Path(a["path"]).name
            if name in by_file:
                a["parity"] = by_file[name]

    # ---- support files
    if src_manifest.exists():
        shutil.copyfile(src_manifest, pkg / "source-manifest.json")
    if env_report.exists():
        shutil.copyfile(env_report, pkg / "environment.json")
    if calibration_status.exists():
        shutil.copyfile(calibration_status, pkg / "calibration-manifest.json")
    contracts = {
        "schema": SCHEMA,
        "encoder": {"input_features": [1, "T", 160], "T_static": True,
                    "T_multiple_of": 200,
                    "outputs": {"encoder_bpe_logits": [1, "T/4", 100352],
                                "multilayer_features": [1, "T", 4096]}},
        "projector": {"multilayer_features": [1, "T", 4096],
                      "audio_embeds": [1, "ceil(T/15)*3", 2048]},
        "llm": {"inputs_embeds": [1, "S_bucket", 2048],
                "position_ids": [1, "S_bucket"], "attention_mask": [1, "S_bucket"],
                "logits": [1, "S_bucket", 100352], "is_causal": False,
                "padding": "end-only; mask=1 on real prefix"},
        "constants": {"blank_token_id": 100257, "vocab_size": 100352, "hidden": 2048,
                      "embedding_multiplier": 12.0, "downsample": 5,
                      "min_edit_sequence_length": 8},
    }
    atomic_write_json(pkg / "contracts.json", contracts)

    ref_data = None
    if ref_report.exists():
        try:
            _r = json.loads(ref_report.read_text(encoding="utf-8"))
            ref_data = {k: _r[k] for k in ("dimensions", "reference_text") if k in _r}
        except Exception:  # noqa: BLE001
            ref_data = None

    validation = {
        "float": json.loads(val_float.read_text(encoding="utf-8")) if val_float.exists() else None,
        "reference": ref_data,
        "android_gates": json.loads((work / "reports" / "android-gates.json").read_text(
            encoding="utf-8")) if (work / "reports" / "android-gates.json").exists() else None,
    }
    atomic_write_json(pkg / "validation-summary.json", validation)

    # ---- main manifest
    manifest = {
        "schema": SCHEMA,
        "experiment_id": work.name,
        "created_at": time.strftime("%Y-%m-%dT%H:%M:%S"),
        "source": {
            "repo": "ibm-granite/granite-speech-4.1-2b-nar",
            "revision": "a1e3416e25ce29ab3852778e54fa8b3bd59c4bf2",
            "license": "Apache-2.0",
        },
        "toolchain": {
            "python": sys.version.split()[0],
            "onnxruntime": "1.29.0",
            "onnxruntime_android_expected": "1.29.0",
            "qairt": "unknown/unverified",
            "opset": 17,
            "exporter": "tools/granite/nar/export_static.py",
        },
        "targets": [{
            "backend": "npu", "soc": "SM8850", "htp_arch": "81",
            "precision": "u16u8",
            "encoder_buckets": [200, 400, 800, 1200, 1600, 2000],
            "llm_buckets": [64, 128, 256, 512, 768, 1024, 1408],
            "status": "context-ready-only",
        }],
        "phone_validation": False,
        "qnn_context_present": False,
        "steps_status": {k: v.get("status") for k, v in state.get("steps", {}).items()},
        "files": artifacts,
    }
    atomic_write_json(pkg / "manifest.json", manifest)

    readme = f"""Granite 4.1 NAR experimental package ({work.name})
Generated: {time.strftime('%Y-%m-%d %H:%M:%S')}

LIMITATIONS (read before using):
- NO Adreno/HTP/QNN validation was performed (phone unavailable).
- Models are 'context-ready' at best: static shapes + QDQ contracts only.
- QNN context binaries are NOT included (qnn_context_present=false).
- Backend proven in this package: ORT CPU only.
- Do not promote to production packages/ without on-device gates.

Source: ibm-granite/granite-speech-4.1-2b-nar @ a1e3416e25ce29ab3852778e54fa8b3bd59c4bf2
Pipeline: encoder/projector/LLM static buckets; contracts in contracts.json.
"""
    (pkg / "README.txt").write_text(readme, encoding="utf-8")

    record_step(work, "build_manifest", "passed",
                artifacts={"package": str(pkg), "files": len(artifacts)})
    print_step(f"package built: {pkg} ({len(artifacts)} file entries)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
