#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R5: extrai do npuprobe-r5.log os SESS[i] e gera JSONL com schema completo.
run_id = <pid>.<idx> (pid NAO e' id unico: idx do request na sessao decide).
Campos: build info, prompt_sha (do log), corpus (len), mode, variant,
lifecycle (load_dst/load_src; 0=warm), phase timestamps interno (ms desde
request), wall, tokens/stop/rc, export/import bytes, memoria/sensores (do
bateria-r5.txt por bloco), texto integral nao truncado (do log)."""
import re, json, os

LOG = r"C:/llama-npu/rodada2/logs/npuprobe-r5.log"
OUT = r"D:/Projetos/SIG/docs/npu-hymt2/smart-rodada5/WARM-R5.jsonl"

t = open(LOG, encoding="utf-8", errors="replace").read()
linhas = t.splitlines()

runs = {}   # (pid, idx) -> dict
for l in linhas:
    m = re.search(r"(\d\d:\d\d:\d\d\.\d+)\s+I/NpuProbe\(\s*(\d+)\): (.*)", l)
    if not m:
        continue
    hora, pid, msg = m.groups(); msg = msg.strip()
    mm = re.match(r"SESS\[(\d+)\] (\S+) (.*)", msg)
    if not mm:
        continue
    idx, rota, resto = int(mm.group(1)), mm.group(2), mm.group(3)
    key = (pid, idx)
    r = runs.setdefault(key, {"pid": pid, "idx": idx, "rota": rota, "hora": hora,
                              "variant": "puro" if rota.startswith("puro:") else ("bench" if "@bench" in rota else "valid")})
    if resto.startswith("puro: ok="):
        r["tokens"] = re.search(r"pieces=(\d+)", resto).group(1)
        r["prefill_ms"] = float(re.search(r"prefill=([\d.]+)ms", resto).group(1))
        r["gen_ms"] = float(re.search(r"gen=([\d.]+)ms", resto).group(1))
        r["wall_ms"] = float(re.search(r"wall=([\d.]+)ms", resto).group(1))
        r["rc"] = 0 if "ok=1" in resto else 1
    elif resto.startswith("bench:") or resto.startswith("valid:"):
        r["tokens"] = re.search(r"emitidos=(\d+)", resto).group(1)
        r["consumidos"] = re.search(r"consumidos=(\d+)", resto).group(1)
        r["decodes_origem_pos_ponte"] = re.search(r"decodes_s_pos_ponte=(\d+)", resto).group(1)
    elif resto.startswith("tempos:"):
        r["prefill_ms"] = float(re.search(r"prefill=([\d.]+)ms", resto).group(1))
        r["export_ms"] = float(re.search(r"export=([\d.]+)ms", resto).group(1))
        r["export_bytes"] = int(re.search(r"\((\d+)B\)", resto).group(1))
        r["import_ms"] = float(re.search(r"import=([\d.]+)ms", resto).group(1))
        r["gen_ms"] = float(re.search(r"gen=([\d.]+)ms", resto).group(1))
    elif resto.startswith("fronteiras"):
        pats = {"prefill_done": r"prefill_done=(-?[\d.]+)", "tok1_ready": r"tok1_ready=(-?[\d.]+)",
                "import_done": r"import_done=(-?[\d.]+)", "tok2_ready": r"tok2_ready=(-?[\d.]+)",
                "fim": r"fim=(-?[\d.]+)", "wall_ms": r"wall=(-?[\d.]+)"}
        for k, rx in pats.items():
            mmk = re.search(rx, resto)
            if mmk: r[k] = float(mmk.group(1))
    elif resto.startswith("texto:"):
        r["texto"] = resto[6:].strip()
    elif resto.startswith("lifecycle:"):
        r["load_dst_ms"] = float(re.search(r"load_dst=([\d.]+)", resto).group(1)) if "load_dst" in resto else None
        r["load_src_ms"] = float(re.search(r"load_src=([\d.]+)", resto).group(1)) if "load_src" in resto else None
        r["load_ms"] = float(re.search(r"load=([\d.]+)", resto).group(1)) if "lifecycle: load=" in resto else None
        r["lifecycle"] = "cold" if any(v and v > 0 for v in [r.get("load_dst_ms"), r.get("load_src_ms"), r.get("load_ms")]) else "warm"
    elif resto.startswith("RESULTADO:"):
        r["resultado"] = resto[10:].strip()

# sensores/memoria por bloco do bateria-r5.txt (associacao por ordem de sessao)
sens = []
br = open(r"C:/llama-npu/rodada2/logs/bateria-r5.txt", encoding="utf-8", errors="replace").read()
for bloco in re.split(r"=== \[([^\]]+)\]", br)[1:]:
    pass
blocos = re.split(r"=== \[([^\]]+)\][^\n]*\n", br)
i = 1
while i + 1 < len(blocos):
    nome, corpo = blocos[i], blocos[i+1]
    mem0 = re.search(r"mem0: (.*?) \| temp_z0_antes=(\d+)", corpo)
    mem1 = re.search(r"mem1: (.*?) \| temp_z0_depois=(\d+)", corpo)
    sens.append({"sessao": nome,
                 "temp_z0_antes": mem0.group(2) if mem0 else None,
                 "temp_z0_depois": mem1.group(2) if mem1 else None,
                 "mem_antes": (mem0.group(1).strip() if mem0 else None),
                 "mem_depois": (mem1.group(1).strip() if mem1 else None)})
    i += 2

os.makedirs(os.path.dirname(OUT), exist_ok=True)
with open(OUT, "w", encoding="utf-8", newline="\n") as f:
    f.write(json.dumps({"meta": {"build": "probe v14c (R5 session mode)",
                                 "libs": "build v12 SIG (sha manifesto rodada4)",
                                 "prompt_curto_sha16": "a5de647b1ff84e74",
                                 "device": "CPH2747 / Android 16 / SDK 36 (getprop)",
                                 "lifecycle_note": "cold=load do backend nesta sessao; warm=contexto vivo"},
                         "sessoes_sensores": sens}, ensure_ascii=False) + "\n")
    for key in sorted(runs.keys(), key=lambda k: (k[0], k[1])):
        r = runs[key]
        r["run_id"] = f"{r['pid']}.{r['idx']}"
        f.write(json.dumps(r, ensure_ascii=False) + "\n")
print(f"JSONL: {OUT} ({len(runs)} requests)")
for r in sorted(runs.values(), key=lambda x: (x['pid'], x['idx']))[:24]:
    print(f"  {r['run_id']:>10} {r['rota']:14s} {r.get('variant','?'):5s} wall={r.get('wall_ms')}ms load={r.get('load_dst_ms')}/{r.get('load_src_ms') or r.get('load_ms')} lifecycle={r.get('lifecycle')}")
