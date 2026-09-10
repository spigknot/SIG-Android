#!/usr/bin/env python3
"""Build the FLEURS calibration corpus (pt_br, en_us, es_419, fr_fr, de_de).

Pilot: >=5 audio/lang (manifest pilot=true); target: 20/lang, deterministic by
seed, stratified by duration bucket. Each entry: dataset/revision, license,
subset, split, id, language, duration, reference text, sha256, T bucket.

Usage:
  python capture_calibration.py corpus --work-dir E:/... [--per-language 20]
"""
from __future__ import annotations

import json
import random
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from common import (atomic_write_json, base_parser, env_for_experiment,  # noqa: E402
                    print_step, record_step, run_cmd, sha256_file, step_status, work_dirs)

LANGS = ["pt_br", "en_us", "es_419", "fr_fr", "de_de"]
SEED = 20260829


def duration_bucket_s(d: float) -> str:
    if d <= 4:
        return "1-4s"
    if d <= 8:
        return "4-8s"
    if d <= 16:
        return "8-16s"
    if d <= 24:
        return "16-24s"
    return "24-40s"


def t_bucket_for(frames: int) -> int:
    for t in (200, 400, 800, 1200, 1600, 2000):
        if frames <= t:
            return t
    return 2000


def lang_seed(lang: str, base: int = 20260829) -> int:
    """Semente ESTAVEL por idioma.

    NAO usar `hash(lang)`: o hash de string do Python e randomizado por processo
    (PYTHONHASHSEED), entao o "corpus deterministico por seed" mudava a cada run —
    duas execucoes com a mesma semente compartilharam so 27 de 82 amostras.
    """
    import hashlib

    digest = hashlib.sha256(lang.encode("utf-8")).hexdigest()
    return base + int(digest[:8], 16) % 1000


def dedup_entries(entries: list[dict]) -> tuple[list[dict], list[str]]:
    """Remove linhas duplicadas por caminho de arquivo, mantendo a PRIMEIRA.

    Regra do plano: dedup por caminho de arquivo. Necessario porque `id` do FLEURS
    se repete entre takes: sem isto o manifest declara mais amostras do que existem
    em disco (observado: 82 linhas / 79 arquivos, uma linha por take perdido).
    """
    seen: set[str] = set()
    unique: list[dict] = []
    dups: list[str] = []
    for e in entries:
        key = e["file"]
        if key in seen:
            dups.append(key)
            continue
        seen.add(key)
        unique.append(e)
    return unique, dups


def corpus(args) -> int:
    work = Path(args.work_dir).resolve()
    dirs = work_dirs(work)
    calib = dirs["calibration"]
    manifest_path = calib / "corpus-manifest.jsonl"
    if args.resume and step_status(work, "calibration_corpus") == "passed" and not args.force:
        print_step("calibration_corpus already passed; skipping")
        return 0

    record_step(work, "calibration_corpus", "running",
                command=f"{__file__} corpus --per-language {args.per_language}")
    env = env_for_experiment(work)
    venv_py = dirs["venv"] / "Scripts" / "python.exe"

    code = r'''
import json, os, sys, io
import numpy as np

work, per_lang = sys.argv[1], int(sys.argv[2])
out_path = sys.argv[3]
seeds = json.loads(sys.argv[4])
from datasets import load_dataset, Audio
import soundfile as sf

LANGS = ["pt_br", "en_us", "es_419", "fr_fr", "de_de"]
rows = []
used_files = set()
collisions = []
with open(out_path, "w", encoding="utf-8") as out:
    for lang in LANGS:
        ds = load_dataset("google/fleurs", lang, split="validation", streaming=False)
        # decode=False: raw WAV bytes; decodificamos com soundfile (sem torchcodec)
        ds = ds.cast_column("audio", Audio(decode=False))
        rng = np.random.RandomState(seeds[lang])
        idx_order = rng.permutation(len(ds))
        picked = 0
        seen_buckets = {}
        for i in idx_order:
            if picked >= per_lang:
                break
            row = ds[int(i)]
            raw = row["audio"]["bytes"]
            data, sr = sf.read(io.BytesIO(raw), dtype="float32")
            if data.ndim > 1:
                data = data.mean(axis=1)
            dur = len(data) / sr
            if dur < 1.0 or dur > 40.0:
                continue
            b = ("1-4s" if dur <= 4 else "4-8s" if dur <= 8 else "8-16s"
                 if dur <= 16 else "16-24s" if dur <= 24 else "24-40s")
            if seen_buckets.get(b, 0) >= max(2, per_lang // 5):
                continue
            seen_buckets[b] = seen_buckets.get(b, 0) + 1
            fid = row["id"]
            fname = f"{lang}_{fid}.wav"
            # FLEURS repete `id` entre takes diferentes (mesmo texto, audio distinto):
            # sem desambiguar, o segundo take SOBRESCREVE o primeiro no disco e o
            # manifest fica com duas linhas apontando para um arquivo so.
            if fname in used_files:
                k = 2
                while f"{lang}_{fid}_{k}.wav" in used_files:
                    k += 1
                collisions.append(fname)
                fname = f"{lang}_{fid}_{k}.wav"
            used_files.add(fname)
            fpath = os.path.join(work, fname)
            sf.write(fpath, data, sr, subtype="PCM_16")
            rows.append(1)
            out.write(json.dumps({
                "dataset": "google/fleurs", "subset": lang, "split": "validation",
                "id": str(fid), "language": lang, "duration_s": round(dur, 3),
                "sample_rate": sr, "reference_text": row["transcription"],
                "file": fname,
                "norm_rule": "transcription as published by FLEURS (lowercase, no punctuation beyond published)",
                "pilot": per_lang < 20,
            }, ensure_ascii=False) + "\n")
            picked += 1
        print(f"{lang}: picked {picked}", flush=True)
print("TOTAL", len(rows))
if collisions:
    print("COLISOES", len(collisions), collisions)
'''
    manifest_tmp = calib / "corpus-manifest.jsonl.partial"
    seeds = {lang: lang_seed(lang, SEED) for lang in LANGS}
    rc = run_cmd([str(venv_py), "-c", code, str(calib), str(args.per_language),
                  str(manifest_tmp), json.dumps(seeds)], dirs["logs"] / "fleurs-download.log",
                 env=env, timeout=7200)
    if rc != 0:
        record_step(work, "calibration_corpus", "failed", exit_code=rc,
                    artifacts={"log": str(dirs['logs'] / 'fleurs-download.log')},
                    error="FLEURS download failed")
        return rc

    # finalize: hash files, add T bucket + sha256
    import shutil
    entries = []
    with open(manifest_tmp, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            e = json.loads(line)
            p = calib / e["file"]
            e["sha256"] = sha256_file(p)
            e["bytes"] = p.stat().st_size
            sr = e["sample_rate"]
            frames = int(e["duration_s"] * sr / 160 * 2)  # mel frames / stack approx
            e["T_bucket"] = t_bucket_for(max(frames, int(e["duration_s"] * 62.5)))
            entries.append(e)
    entries, dup_paths = dedup_entries(entries)
    if dup_paths:
        print_step(f"AVISO: {len(dup_paths)} entradas duplicadas descartadas: {dup_paths[:5]}")
    on_disk = {p.name for p in calib.glob("*.wav")}
    declared = {e["file"] for e in entries}
    if declared != on_disk:
        print_step(f"AVISO: manifest x disco divergem — so no manifest: "
                   f"{sorted(declared - on_disk)[:5]}; so no disco: {sorted(on_disk - declared)[:5]}")
    with open(manifest_path.with_suffix(".tmp"), "w", encoding="utf-8") as f:
        for e in entries:
            f.write(json.dumps(e, ensure_ascii=False) + "\n")
    manifest_path.with_suffix(".tmp").replace(manifest_path)
    manifest_tmp.unlink(missing_ok=True)

    per_lang = {}
    for e in entries:
        per_lang[e["language"]] = per_lang.get(e["language"], 0) + 1
    pilot = args.per_language < 20
    atomic_write_json(dirs["reports"] / "calibration-corpus-status.json", {
        "entries": len(entries), "per_language": per_lang, "pilot": pilot,
        "seed": SEED, "manifest": str(manifest_path)})
    record_step(work, "calibration_corpus", "passed",
                artifacts={"manifest": str(manifest_path), "entries": len(entries),
                           "per_language": per_lang, "pilot": pilot})
    print_step(f"calibration corpus: {len(entries)} entries {per_lang}")
    return 0


def main() -> int:
    ap = base_parser(__doc__)
    sub = ap.add_subparsers(dest="cmd", required=True)
    c = sub.add_parser("corpus")
    c.add_argument("--per-language", type=int, default=20)
    c.add_argument("--resume", action="store_true")
    c.add_argument("--force", action="store_true")
    args = ap.parse_args()
    if args.cmd == "corpus":
        return corpus(args)
    return 2


if __name__ == "__main__":
    sys.exit(main())
