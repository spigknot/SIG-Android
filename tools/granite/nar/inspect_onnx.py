#!/usr/bin/env python3
"""Inspect exported ONNX: checker, shape report, op counts, dynamic dims, external data.

Usage:
  python inspect_onnx.py --work-dir E:/SIG-granite-nar-lab/<id> [--file <name>] [--all]
"""
from __future__ import annotations

import json
import sys
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from common import (atomic_write_json, base_parser, print_step,  # noqa: E402
                    record_step, sha256_file, work_dirs)

try:
    import onnx
except ImportError:
    onnx = None


def inspect_file(path: Path) -> dict:
    rep: dict = {"file": path.name, "bytes": path.stat().st_size,
                 "sha256": sha256_file(path), "exists": path.exists()}
    data = path.with_name(path.name + ".data")
    rep["external_data"] = {"file": data.name, "exists": data.exists(),
                            "bytes": data.stat().st_size if data.exists() else 0,
                            "sha256": sha256_file(data) if data.exists() else None}
    if onnx is None:
        rep["error"] = "onnx not importable"
        return rep
    try:
        model = onnx.load(str(path), load_external_data=False)
        checker_ok = True
        try:
            # checker with external data: needs the data present; run a light check
            onnx.checker.check_model(model, full_check=False)
        except Exception as e:  # noqa: BLE001
            checker_ok = False
            rep["checker_error"] = repr(e)[:500]
        rep["checker"] = checker_ok
        g = model.graph
        io = lambda vs: [{"name": v.name,
                          "dtype": v.type.tensor_type.elem_type,
                          "dims": [d.dim_value if d.HasField("dim_value") else d.dim_param
                                   for d in v.type.tensor_type.shape.dim]} for v in vs]
        rep["inputs"] = io(g.input)
        rep["outputs"] = io(g.output)
        ops = Counter(n.op_type for n in g.node)
        rep["op_counts"] = dict(ops.most_common())
        rep["op_total"] = sum(ops.values())
        rep["initializers"] = len(g.initializer)
        dyn = []
        for v in list(g.input) + list(g.output):
            for d in v.type.tensor_type.shape.dim:
                if not d.HasField("dim_value"):
                    dyn.append({"tensor": v.name, "dim": d.dim_param})
        rep["dynamic_dims"] = dyn
        rep["has_dynamic"] = bool(dyn)
        rep["opset"] = model.opset_import[0].version if model.opset_import else None
    except Exception as e:  # noqa: BLE001
        rep["error"] = repr(e)[:500]
    return rep


def main() -> int:
    ap = base_parser(__doc__)
    ap.add_argument("--file")
    ap.add_argument("--all", action="store_true")
    args = ap.parse_args()
    work = Path(args.work_dir).resolve()
    dirs = work_dirs(work)
    exports = dirs["exports"]

    if args.file:
        targets = [exports / args.file]
    elif args.all:
        targets = sorted(exports.glob("*.onnx"))
    else:
        ap.error("use --file <name> or --all")

    reports = []
    failed = False
    for p in targets:
        print_step(f"inspecting {p.name} ...")
        rep = inspect_file(p)
        reports.append(rep)
        if rep.get("error") or not rep.get("checker", False) or rep.get("has_dynamic"):
            failed = True
        status = "ok" if (rep.get("checker") and not rep.get("has_dynamic")
                          and not rep.get("error")) else "problem"
        print_step(f"  {p.name}: {status} ops={rep.get('op_total')} "
                   f"dynamic={rep.get('has_dynamic')} ext={rep.get('external_data', {}).get('exists')}")

    atomic_write_json(dirs["reports"] / "inspect-onnx.json", {"files": reports})
    record_step(work, "inspect_onnx", "failed" if failed else "passed",
                artifacts={"report": str(dirs['reports'] / 'inspect-onnx.json'),
                           "files": len(reports)})
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
