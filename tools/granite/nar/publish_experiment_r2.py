#!/usr/bin/env python3
"""Publish experiment artifacts to Cloudflare R2 under the experiments prefix.

Rules (docs/prompt-operador §16):
  - bucket MUST be sig-android; public_base must match; else abort
  - multipart 64 MiB, concurrency 2, retries; never overwrite existing keys
  - Metadata.sha256/role/experiment/content-type on every object
  - data first, manifest.json LAST; HeadObject + public HEAD after each upload
  - reports/r2-upload.jsonl (no secrets)

Usage:
  python publish_experiment_r2.py --work-dir E:/... [--config D:/Projetos/SIG/release/r2_config.json]
"""
from __future__ import annotations

import json
import sys
import threading
from pathlib import Path

import boto3
from boto3.s3.transfer import TransferConfig

sys.path.insert(0, str(Path(__file__).parent))
from common import (atomic_write_json, base_parser, print_step,  # noqa: E402
                    record_step, sha256_file, work_dirs)

EXPECTED_BUCKET = "sig-android"
EXPECTED_PUBLIC = "https://pub-6476622beda24c82875cb84f11f660ea.r2.dev"
PART_SIZE = 64 * 1024 * 1024
CONCURRENCY = 2

_upload_lock = threading.Lock()
_log_lines = []


def load_config(path: Path) -> dict:
    cfg = json.loads(path.read_text(encoding="utf-8"))
    return cfg


def make_client(cfg: dict):
    url = cfg.get("endpoint_url") or cfg.get("endpoint")
    return boto3.client(
        "s3",
        endpoint_url=url,
        aws_access_key_id=cfg["access_key_id"],
        aws_secret_access_key=cfg["secret_access_key"],
        region_name=cfg.get("region", "auto"),
    )


def upload_one(client, bucket: str, key: str, path: Path, exp_id: str, role: str,
               ctype: str) -> dict:
    import urllib.request
    sha = sha256_file(path)
    size = path.stat().st_size

    # exists check: never overwrite
    try:
        head = client.head_object(Bucket=bucket, Key=key)
        remote_sha = (head.get("Metadata") or {}).get("sha256")
        if head["ContentLength"] == size and remote_sha == sha:
            return {"key": key, "status": "skip-identical", "bytes": size}
        return {"key": key, "status": "conflict",
                "error": f"existing object differs (remote {head['ContentLength']}B sha={remote_sha})"}
    except client.exceptions.NoSuchKey:
        pass
    except Exception as e:  # noqa: BLE001
        if "404" in str(e) or "Not Found" in str(e):
            pass
        else:
            return {"key": key, "status": "error", "error": repr(e)[:300]}

    config = TransferConfig(multipart_threshold=PART_SIZE, multipart_chunksize=PART_SIZE,
                            max_concurrency=CONCURRENCY, num_download_attempts=8,
                            max_io_queue=100)
    extra = {"ContentType": ctype, "Metadata": {"sha256": sha, "role": role,
                                                "experiment": exp_id}}
    client.upload_file(str(path), bucket, key, ExtraArgs=extra, Config=config)

    # verify via HeadObject
    head = client.head_object(Bucket=bucket, Key=key)
    ok_size = head["ContentLength"] == size
    ok_sha = (head.get("Metadata") or {}).get("sha256") == sha

    # public HEAD (R2 bloqueia User-Agent Python-urllib; usar UA browser)
    pub = f"{EXPECTED_PUBLIC}/{key}"
    pub_status = None
    try:
        req = urllib.request.Request(pub, method="HEAD")
        req.add_header("User-Agent", "Mozilla/5.0 (SIG-r2-uploader)")
        with urllib.request.urlopen(req, timeout=30) as r:
            pub_status = r.status
    except Exception as e:  # noqa: BLE001
        pub_status = f"error:{e!r:.120}"

    return {"key": key, "status": "uploaded" if (ok_size and ok_sha) else "verify-failed",
            "bytes": size, "sha256": sha, "head_size_ok": ok_size, "head_sha_ok": ok_sha,
            "public_head": pub_status, "public_url": pub}


def main() -> int:
    ap = base_parser(__doc__)
    ap.add_argument("--config", default=r"D:\Projetos\SIG\release\r2_config.json")
    ap.add_argument("--only", help="comma list of local relative paths to upload (default: whole package)")
    ap.add_argument("--resume", action="store_true")
    args = ap.parse_args()
    work = Path(args.work_dir).resolve()
    dirs = work_dirs(work)
    exp_id = work.name
    prefix = f"models/granite/4.1-nar/experiments/{exp_id}/"

    record_step(work, "publish_r2", "running", command=f"{__file__} --work-dir {work}")

    cfg = load_config(Path(args.config))
    bucket = cfg.get("bucket") or cfg.get("bucket_name")
    public_base = (cfg.get("public_base") or cfg.get("public_url") or "").rstrip("/")
    if bucket != EXPECTED_BUCKET or public_base != EXPECTED_PUBLIC:
        record_step(work, "publish_r2", "failed", exit_code=2,
                    error=f"config mismatch: bucket={bucket!r} public_base={public_base!r}")
        print_step("ABORT: r2_config bucket/public_base mismatch")
        return 2

    client = make_client(cfg)
    pkg = dirs["packages"] / exp_id
    if not pkg.exists():
        record_step(work, "publish_r2", "failed", exit_code=3,
                    error=f"package missing: {pkg}")
        return 3

    # upload order: data/reports first, manifest.json LAST
    all_files = sorted(p for p in pkg.rglob("*") if p.is_file())
    manifest_files = [p for p in all_files if p.name == "manifest.json"]
    others = [p for p in all_files if p.name != "manifest.json"]

    if args.only:
        keep = set(Path(x).as_posix() for x in args.only.split(","))
        others = [p for p in others if p.relative_to(pkg).as_posix() in keep]
        manifest_files = [p for p in manifest_files if "manifest.json" in keep]

    CTYPES = {".onnx": "application/octet-stream", ".data": "application/octet-stream",
              ".json": "application/json", ".jsonl": "application/x-ndjson",
              ".txt": "text/plain"}
    results = []
    failed = False
    for p in others + manifest_files:
        rel = p.relative_to(pkg).as_posix()
        role = ("manifest" if p.suffix == ".json" else
                "qdq" if "qdq" in p.name else
                "float" if p.suffix == ".onnx" else "support")
        key = prefix + rel
        print_step(f"upload {rel} ...")
        res = upload_one(client, bucket, key, p, exp_id, role,
                         CTYPES.get(p.suffix, "application/octet-stream"))
        results.append(res)
        print_step(f"  -> {res['status']}")
        if res["status"] in ("conflict", "error", "verify-failed"):
            failed = True
        with _upload_lock:
            _log_lines.append(json.dumps(res, ensure_ascii=False))
            (dirs["reports"] / "r2-upload.jsonl").open("a", encoding="utf-8").write(
                json.dumps(res, ensure_ascii=False) + "\n")

    manifest_url = None
    for r in results:
        if r["key"].endswith("manifest.json") and r["status"] in ("uploaded", "skip-identical"):
            manifest_url = r.get("public_url") or f"{EXPECTED_PUBLIC}/{r['key']}"

    summary = {"experiment": exp_id, "prefix": prefix, "objects": len(results),
               "failed": failed, "manifest_url": manifest_url}
    atomic_write_json(dirs["reports"] / "r2-upload-summary.json", summary)
    record_step(work, "publish_r2", "failed" if failed else "passed",
                artifacts={"summary": str(dirs['reports'] / 'r2-upload-summary.json'),
                           "manifest_url": manifest_url})
    print_step(f"publish_r2 {'FAILED' if failed else 'passed'}: {summary}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
