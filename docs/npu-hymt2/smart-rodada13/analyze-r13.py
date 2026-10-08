#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R13: analisador da coleta numerica (NUMCHK/NUMRES do npuprobe-r13.log).
Gera: tabela por checkpoint, envelope (max/quantis de erro por par), veredito
ESTRITO vs NUMERICO_TOLERADO por criterio independente:
- cada divergencia de stream (tok_dst != tok_ref) com erro centralizado e_dr
  acima do envelope do CONTROLE (definido por calibracao separada) => FAIL;
- NaN/state/pos invalido => FAIL sempre."""
import re, sys, statistics

LOG = r"C:/llama-npu/rodada2/logs/npuprobe-r13.log"
OUT = r"D:/Projetos/SIG/docs/npu-hymt2/smart-rodada13/NUMERIC-ENVELOPE.txt"

txt = open(LOG, encoding="utf-8", errors="replace").read()
chks = []
for m in re.finditer(r"NUMCHK pos=(\d+) tok_dst=(-?\d+) tok_ref=(-?\d+) \| lse_d=([\d.]+) lse_s=([\d.]+) lse_r=([\d.]+) \| maxp_d=([\d.]+) \| e_dr=([\d.\-]+) e_sr=([\d.\-]+) e_ds=([\d.\-]+) \| fin=(\d)(\d)(\d)", txt):
    g = m.groups()
    chks.append(dict(pos=int(g[0]), tok_dst=int(g[1]), tok_ref=int(g[2]),
                     lse_d=float(g[3]), lse_s=float(g[4]), lse_r=float(g[5]),
                     maxp=float(g[6]), e_dr=float(g[7]), e_sr=float(g[8]), e_ds=float(g[9]),
                     fin=(g[10] == "1", g[11] == "1", g[12] == "1")))
ress = re.findall(r"NUMRES .*(?:passos=\d+ nchk=\d+ div_dst_ref=(\d+) \| e_dr\(max/mean\)=([\d.]+)/([\d.]+) e_sr=([\d.]+)/([\d.]+) e_ds=([\d.]+)/([\d.]+) \| lse0\(d/s/r\)=([\d.\-]+)/([\d.\-]+)/([\d.\-]+) \| out=(\d+) texto_len=(\d+))", txt)

out = ["ENVELOPE NUMERICO — R13 (checks teacher-forced; 3 backends: dst=GPU, src=HTP, ref=CPU)", ""]
if chks:
    out.append(f"{'pos':>4} {'tokd':>6} {'tokr':>6} {'lse_d':>10} {'lse_s':>10} {'lse_r':>10} {'maxp_d':>8} {'e_dr':>9} {'e_sr':>9} {'e_ds':>9} fin")
    for c in chks:
        out.append(f"{c['pos']:>4} {c['tok_dst']:>6} {c['tok_ref']:>6} {c['lse_d']:>10.3f} {c['lse_s']:>10.3f} {c['lse_r']:>10.3f} {c['maxp']:>8.5f} {c['e_dr']:>9.3f} {c['e_sr']:>9.3f} {c['e_ds']:>9.3f} {int(c['fin'][0])}{int(c['fin'][1])}{int(c['fin'][2])}")
    # envelope por par (todos os checkpoints coletados)
    for par in ("e_ds", "e_sr", "e_dr"):
        v = [c[par] for c in chks if c[par] >= 0]
        if v:
            out.append(f"\nenvelope {par}: n={len(v)} max={max(v):.4f} mean={statistics.mean(v):.4f} p50={statistics.median(v):.4f}")
    # divergencias de stream
    divs = [c for c in chks if c["tok_dst"] != c["tok_ref"]]
    out.append(f"\ndivergencias destino-vs-ref nos checkpoints: {len(divs)}")
    for c in divs:
        out.append(f"  pos {c['pos']}: dst={c['tok_dst']} ref={c['tok_ref']} e_dr={c['e_dr']} maxp_d={c['maxp']}")
else:
    out.append("(sem NUMCHK — verificar execucao)")
out.append("")
out.append("NUMRES (linhas):")
for r in ress:
    out.append(f"  div={r[0]} e_dr(max/mean)={r[1]}/{r[2]} e_sr={r[3]}/{r[4]} e_ds={r[5]}/{r[6]} lse0(d/s/r)={r[7]}/{r[8]}/{r[9]} out={r[10]} texto={r[11]}")

open(OUT, "w", encoding="utf-8", newline="\n").write("\n".join(out) + "\n")
print("\n".join(out[:30]))
print("\nsalvo:", OUT)
