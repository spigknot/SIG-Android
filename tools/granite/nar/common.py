"""Shared helpers for Granite 4.1 NAR experiment tools.

Every tool accepts --work-dir (absolute), is resumable (hash/idempotence),
writes JSON reports, uses streaming SHA-256 and .partial + atomic rename.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
import time
from pathlib import Path

CHUNK = 4 * 1024 * 1024


def sha256_file(path: os.PathLike | str) -> str:
    """Streaming SHA-256; never reads the whole file into memory."""
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while True:
            b = f.read(CHUNK)
            if not b:
                break
            h.update(b)
    return h.hexdigest()


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def atomic_write_bytes(path: os.PathLike | str, data: bytes) -> None:
    p = Path(path)
    p.parent.mkdir(parents=True, exist_ok=True)
    tmp = p.with_name(p.name + ".partial")
    with open(tmp, "wb") as f:
        f.write(data)
        f.flush()
        os.fsync(f.fileno())
    os.replace(tmp, p)


def atomic_write_json(path: os.PathLike | str, obj) -> None:
    atomic_write_bytes(path, (json.dumps(obj, indent=2, ensure_ascii=False) + "\n").encode("utf-8"))


def atomic_write_text(path: os.PathLike | str, text: str) -> None:
    atomic_write_bytes(path, text.encode("utf-8"))


def read_json(path: os.PathLike | str):
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def now_iso() -> str:
    return time.strftime("%Y-%m-%dT%H:%M:%S%z")


# ---------------------------------------------------------------- state ----

def state_path(work_dir: Path) -> Path:
    return work_dir / "state" / "run-state.json"


def load_state(work_dir: Path) -> dict:
    p = state_path(work_dir)
    if p.exists():
        try:
            return read_json(p)
        except Exception:
            pass
    return {"steps": {}}


def save_state(work_dir: Path, state: dict) -> None:
    state["updated_at"] = now_iso()
    atomic_write_json(state_path(work_dir), state)


def record_step(work_dir: Path, step: str, status: str, *, command: str = "",
                exit_code: int | None = None, artifacts: dict | None = None,
                error: str = "", started_at: str = "") -> dict:
    state = load_state(work_dir)
    steps = state.setdefault("steps", {})
    prev = steps.get(step, {})
    steps[step] = {
        "status": status,  # pending/running/passed/failed/skipped
        "started_at": prev.get("started_at", started_at or now_iso()),
        "finished_at": now_iso(),
        "command": command or prev.get("command", ""),
        "exit_code": exit_code,
        "artifacts": artifacts or prev.get("artifacts", {}),
        "error": error,
    }
    save_state(work_dir, state)
    return steps[step]


def step_status(work_dir: Path, step: str) -> str:
    return load_state(work_dir).get("steps", {}).get(step, {}).get("status", "pending")


# ------------------------------------------------------------- CLI base ----

def base_parser(description: str) -> argparse.ArgumentParser:
    ap = argparse.ArgumentParser(description=description)
    ap.add_argument("--work-dir", required=True,
                    help="Absolute experiment directory (E:/SIG-granite-nar-lab/<id>)")
    return ap


def work_dirs(work_dir: Path) -> dict:
    d = {name: (work_dir / name) for name in
         ("source", "cache", "venv", "exports", "golden", "calibration",
          "quantized", "packages", "logs", "reports", "state")}
    for p in d.values():
        p.mkdir(parents=True, exist_ok=True)
    return d


def env_for_experiment(work_dir: Path) -> dict:
    """HF/pip caches pinned INSIDE the experiment (override, não setdefault:
    um HF_HOME herdado do ambiente vazaría cache para fora do experimento)."""
    env = dict(os.environ)
    exp = str(work_dir.resolve())
    env["HF_HOME"] = os.path.join(exp, "cache", "hf")
    env["HF_HUB_CACHE"] = os.path.join(exp, "cache", "hf", "hub")
    env["TRANSFORMERS_CACHE"] = os.path.join(exp, "cache", "hf", "hub")
    env["PIP_CACHE_DIR"] = os.path.join(exp, "cache", "pip")
    env.setdefault("TMPDIR", os.path.join(exp, "cache", "tmp"))
    env["TORCH_HOME"] = os.path.join(exp, "cache", "torch")
    return env


# ------------------------------------------------------------ artifacts ----

def file_entry(path: Path, base: Path | None = None) -> dict:
    entry = {
        "path": str(path.relative_to(base)) if base else str(path),
        "bytes": path.stat().st_size,
        "sha256": sha256_file(path),
    }
    return entry


def looks_like_lfs_pointer(path: Path) -> bool:
    try:
        with open(path, "rb") as f:
            head = f.read(400)
    except OSError:
        return False
    return head.startswith(b"version https://git-lfs.github.com/spec/v1")


def run_cmd(cmd: list[str], log_path: Path, env: dict | None = None,
            cwd: Path | None = None, timeout: int = 3600) -> int:
    """Run a subprocess, tee stdout/stderr to log_path; return exit code."""
    log_path.parent.mkdir(parents=True, exist_ok=True)
    with open(log_path, "ab") as log:
        log.write(f"\n==== {now_iso()} $ {' '.join(cmd)}\n".encode())
        log.flush()
        proc = subprocess.run(cmd, stdout=log, stderr=subprocess.STDOUT,
                              env=env, cwd=str(cwd) if cwd else None, timeout=timeout)
    return proc.returncode


def print_step(msg: str) -> None:
    print(f"[nar] {time.strftime('%H:%M:%S')} {msg}", flush=True)
