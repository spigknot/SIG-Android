#!/usr/bin/env python3
"""Download the immutable Granite Speech 4.1 NAR snapshot + source manifest.

Usage:
  python download_source.py --work-dir E:/SIG-granite-nar-lab/<experiment-id> [--resume]
"""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from common import (base_parser, env_for_experiment, file_entry, looks_like_lfs_pointer,  # noqa: E402
                    print_step, record_step, run_cmd, save_state, step_status, work_dirs)

REPO = "ibm-granite/granite-speech-4.1-2b-nar"
REVISION = "a1e3416e25ce29ab3852778e54fa8b3bd59c4bf2"
LICENSE = "Apache-2.0"


def main() -> int:
    ap = base_parser(__doc__)
    ap.add_argument("--resume", action="store_true")
    args = ap.parse_args()
    work = Path(args.work_dir).resolve()
    dirs = work_dirs(work)

    if args.resume and step_status(work, "download_source") == "passed":
        print_step("download_source already passed; skipping (use --force to redo)")
        return 0

    record_step(work, "download_source", "running", command=f"{__file__} --work-dir {work}")
    print_step(f"snapshot_download {REPO}@{REVISION} -> {dirs['source']}")
    env = env_for_experiment(work)

    # Bootstrap huggingface_hub into the experiment venv (idempotent).
    venv_py = dirs["venv"] / "Scripts" / "python.exe"
    rc = run_cmd([str(venv_py), "-m", "pip", "install", "--retries", "8", "--timeout", "120",
                  f"huggingface_hub"], dirs["logs"] / "pip-bootstrap.log", env=env)
    if rc != 0:
        record_step(work, "download_source", "failed", exit_code=rc,
                    artifacts={"log": str(dirs['logs'] / 'pip-bootstrap.log')},
                    error="pip install huggingface_hub failed")
        return rc

    code = (
        "import json,sys;"
        "from huggingface_hub import snapshot_download;"
        f"p=snapshot_download(repo_id='{REPO}',revision='{REVISION}',"
        f"local_dir=r'{dirs['source']}',max_workers=2);"
        "print(json.dumps({'resolved':p}))"
    )
    rc = run_cmd([str(venv_py), "-c", code], dirs["logs"] / "snapshot-download.log",
                 env=env, timeout=7200)
    if rc != 0:
        record_step(work, "download_source", "failed", exit_code=rc,
                    artifacts={"log": str(dirs['logs'] / 'snapshot-download.log')},
                    error="snapshot_download failed (see log)")
        return rc

    # Manifest + LFS-pointer scan.
    manifest = {
        "repo": REPO, "revision": REVISION, "resolved_revision": REVISION,
        "license": LICENSE, "local_dir": str(dirs["source"]), "files": [],
        "lfs_pointer_files": [], "scan": {"checked": 0, "broken_or_pointer": 0},
    }
    total = 0
    for p in sorted(dirs["source"].rglob("*")):
        if not p.is_file():
            continue
        rel = p.relative_to(dirs["source"])
        if rel.parts and rel.parts[0] == ".cache":
            continue  # huggingface_hub bookkeeping
        entry = file_entry(p, dirs["source"])
        total += entry["bytes"]
        manifest["files"].append(entry)
        manifest["scan"]["checked"] += 1
        if looks_like_lfs_pointer(p):
            manifest["lfs_pointer_files"].append(entry["path"])
            manifest["scan"]["broken_or_pointer"] += 1

    if not manifest["files"]:
        record_step(work, "download_source", "failed", exit_code=3,
                    error="source directory empty after download")
        return 3
    if manifest["scan"]["broken_or_pointer"]:
        record_step(work, "download_source", "failed", exit_code=4,
                    artifacts={"manifest": str(dirs['reports'] / 'source-manifest.json')},
                    error=f"LFS pointers found: {manifest['lfs_pointer_files'][:5]}")
        # Still persist the manifest as diagnostic evidence.
        from common import atomic_write_json
        atomic_write_json(dirs["reports"] / "source-manifest.json", manifest)
        return 4

    from common import atomic_write_json
    atomic_write_json(dirs["reports"] / "source-manifest.json", manifest)
    save_state(work, {})
    record_step(work, "download_source", "passed",
                artifacts={"manifest": str(dirs['reports'] / 'source-manifest.json'),
                           "files": len(manifest["files"]), "total_bytes": total})
    print_step(f"download_source passed: {len(manifest['files'])} files, {total} bytes")
    return 0


if __name__ == "__main__":
    sys.exit(main())
