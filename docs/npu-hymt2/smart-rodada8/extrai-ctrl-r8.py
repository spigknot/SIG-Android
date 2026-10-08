#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R8: extrai os JSONLs do CONTROLE v11 (R7) do npuprobe-r7.log — os dados
existem no stream; o files/ foi sobrescrito pelos runs seguintes. Schema
identico ao nativo; fonte declarada (extracao do log)."""
import re, json, os

LOG = r"C:/llama-npu/rodada2/logs/npuprobe-r7.log"
OUTDIR = r"D:/Projetos/SIG/docs/npu-hymt2/smart-rodada7/ctrl"

t = open(LOG, encoding="utf-8", errors="replace").read()
linhas = t.splitlines()
os.makedirs(OUTDIR, exist_ok=True)

# blocos: pids com hora entre 23:3[3-9] (controle) — pegar os SESS por pid
runs = {}
for l in linhas:
    m = re.search(r"(\d\d:\d\d:\d\d\.\d+)\s+I/NpuProbe\(\s*(\d+)\): (.*)", l)
    if not m:
        continue
    hora, pid, msg = m.groups(); msg = msg.strip()
    # CONTROLE v11 (R7): pids 3581 (puro:htp solo) e 19099 (smart oc + puro oc)
    if pid not in ("3581", "19099"):
        continue
    mm = re.match(r"SESS\[(\d+)\] (\S+) (.*)", msg)
    if not mm:
        continue
    idx, rota, resto = int(mm.group(1)), mm.group(2), mm.group(3)
    r = runs.setdefault((pid, idx), {"pid": pid, "idx": idx, "rota": rota, "hora": hora,
                                     "fonte": "ctrl-v11 (extracao do npuprobe-r7.log)",
                                     "variant": "ctrl"})
    if resto.startswith("puro: ok="):
        r["tokens"] = re.search(r"pieces=(\d+)", resto).group(1)
        r["prefill_ms"] = float(re.search(r"prefill=([\d.]+)ms", resto).group(1))
        r["gen_ms"] = float(re.search(r"gen=([\d.]+)ms", resto).group(1))
        r["wall_ms"] = float(re.search(r"wall=([\d.]+)ms", resto).group(1))
        r["ok"] = "ok=1" in resto
    elif resto.startswith("bench:") or resto.startswith("valid:"):
        r["tokens"] = re.search(r"emitidos=(\d+)", resto).group(1)
        mds = re.search(r"(?:decodes_s_pos_ponte|dec_s)=(\d+)", resto)
        if mds: r["dec_s"] = int(mds.group(1))
        r["ok"] = "ok=1" in resto
    elif resto.startswith("tempos:"):
        r["prefill_ms"] = float(re.search(r"prefill=([\d.]+)ms", resto).group(1))
        r["export_ms"] = float(re.search(r"export=([\d.]+)ms", resto).group(1))
        r["export_b"] = int(re.search(r"\((\d+)B\)", resto).group(1))
        r["import_ms"] = float(re.search(r"import=([\d.]+)ms", resto).group(1))
        r["gen_ms"] = float(re.search(r"gen=([\d.]+)ms", resto).group(1))
    elif resto.startswith("fronteiras"):
        for k, rx in [("t_prefdone", r"prefdone=(-?[\d.]+)"), ("t_tok1r", r"tok1r=(-?[\d.]+)"),
                      ("t_impdone", r"impdone=(-?[\d.]+)"), ("t_tok2r", r"tok2r=(-?[\d.]+)"),
                      ("t_fim", r"fim=(-?[\d.]+)"), ("wall_ms", r"wall=(-?[\d.]+)")]:
            mmk = re.search(rx, resto)
            if mmk: r[k] = float(mmk.group(1))
    elif resto.startswith("texto_len="):
        r["texto_len"] = int(re.search(r"texto_len=(\d+)", resto).group(1))
    elif resto.startswith("RESULTADO:"):
        r["resultado"] = resto[10:].strip()

def dump(nome, filtro):
    sel = [r for k, r in sorted(runs.items()) if filtro(r)]
    p = os.path.join(OUTDIR, nome)
    with open(p, "w", encoding="utf-8", newline="\n") as f:
        for r in sel:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    print(f"{nome}: {len(sel)} reqs")

dump("ctrl-htp-solo-v11.jsonl", lambda r: r["rota"] == "puro:htp")
dump("ctrl-smart-oc-e-puro-v11.jsonl", lambda r: r["rota"] in ("opencl", "puro:opencl"))
import hashlib
for f in ["ctrl-htp-solo-v11.jsonl", "ctrl-smart-oc-e-puro-v11.jsonl"]:
    p = os.path.join(OUTDIR, f)
    if os.path.exists(p):
        print(f, hashlib.sha256(open(p, "rb").read()).hexdigest()[:32])
