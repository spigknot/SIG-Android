#!/usr/bin/env python3
"""Create the experiment venv, install pinned deps, write pip-freeze + env report.

Reference versions: Python 3.11, torch 2.9.1, torchaudio 2.9.1, transformers>=5.5.3,
onnx/onnxscript current, onnxruntime 1.29.0. CPU wheels preferred over CUDA
(8 GB VRAM; reliability over CUDA OOM).
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from common import (atomic_write_json, base_parser, env_for_experiment,  # noqa: E402
                    now_iso, print_step, record_step, run_cmd, step_status, work_dirs)

TORCH_INDEX = "https://download.pytorch.org/whl/cpu"
PIN = [
    "torch==2.9.1", "torchaudio==2.9.1",
    "transformers>=5.5.3,<6",
    "onnx", "onnxscript",
    "onnxruntime==1.29.0",
    "accelerate", "numpy", "soundfile", "datasets", "boto3", "psutil",
    "huggingface_hub", "safetensors",
]


def main() -> int:
    ap = base_parser(__doc__)
    ap.add_argument("--resume", action="store_true")
    args = ap.parse_args()
    work = Path(args.work_dir).resolve()
    dirs = work_dirs(work)
    env = env_for_experiment(work)
    venv_py = dirs["venv"] / "Scripts" / "python.exe"

    if args.resume and step_status(work, "python_env") == "passed":
        print_step("python_env already passed; skipping")
        return 0

    record_step(work, "python_env", "running", command=f"{__file__} --work-dir {work}")

    # torch/torchaudio from the CPU index first (avoid CUDA wheels ~2.5 GB).
    rc = run_cmd([str(venv_py), "-m", "pip", "install", "--retries", "8", "--timeout", "180",
                  "--index-url", TORCH_INDEX, "torch==2.9.1", "torchaudio==2.9.1"],
                 dirs["logs"] / "pip-torch.log", env=env, timeout=7200)
    if rc != 0:
        record_step(work, "python_env", "failed", exit_code=rc,
                    artifacts={"log": str(dirs['logs'] / 'pip-torch.log')},
                    error="pip install torch (cpu) failed")
        return rc

    rc = run_cmd([str(venv_py), "-m", "pip", "install", "--retries", "8", "--timeout", "180"] + PIN,
                 dirs["logs"] / "pip-rest.log", env=env, timeout=7200)
    if rc != 0:
        record_step(work, "python_env", "failed", exit_code=rc,
                    artifacts={"log": str(dirs['logs'] / 'pip-rest.log')},
                    error="pip install deps failed")
        return rc

    freeze_rc = run_cmd([str(venv_py), "-m", "pip", "freeze"],
                        dirs["logs"] / "pip-freeze.log", env=env)
    freeze_text = (dirs["logs"] / "pip-freeze.log").read_text(encoding="utf-8", errors="replace")
    (dirs["reports"] / "pip-freeze.txt").write_text(freeze_text, encoding="utf-8")

    probe = r"""
import json, platform, sys
import psutil
out = {"python": sys.version, "platform": platform.platform()}
try:
    import torch; out["torch"] = torch.__version__; out["cuda_available"] = torch.cuda.is_available()
    out["cuda_version"] = torch.version.cuda
except Exception as e:
    out["torch_error"] = repr(e)
for mod in ("transformers", "onnx", "onnxruntime", "datasets", "soundfile"):
    try:
        m = __import__(mod); out[mod] = getattr(m, "__version__", "?")
    except Exception as e:
        out[mod + "_error"] = repr(e)
out["ram_total_gb"] = round(psutil.virtual_memory().total / 2**30, 1)
out["ram_available_gb"] = round(psutil.virtual_memory().available / 2**30, 1)
print(json.dumps(out))
"""
    rc = run_cmd([str(venv_py), "-c", probe], dirs["logs"] / "env-probe.log", env=env)
    env_report = {"generated_at": now_iso(), "work_dir": str(work), "pins": PIN}
    if rc == 0:
        last = [l for l in (dirs["logs"] / "env-probe.log").read_text(
            encoding="utf-8", errors="replace").splitlines() if l.strip().startswith("{")]
        if last:
            env_report.update(json.loads(last[-1]))
    atomic_write_json(dirs["reports"] / "environment.json", env_report)

    if freeze_rc != 0 or rc != 0:
        record_step(work, "python_env", "failed", exit_code=rc or freeze_rc,
                    artifacts={"environment": str(dirs['reports'] / 'environment.json')},
                    error="env probe failed")
        return rc or freeze_rc

    record_step(work, "python_env", "passed",
                artifacts={"environment": str(dirs['reports'] / 'environment.json'),
                           "pip_freeze": str(dirs['reports'] / 'pip-freeze.txt')})
    print_step(f"python_env passed: torch={env_report.get('torch')} "
               f"transformers={env_report.get('transformers')} ort={env_report.get('onnxruntime')}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
