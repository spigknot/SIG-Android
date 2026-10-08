#!/usr/bin/env python3
# R14: REJ-APPLY v2 replace-aware: processa cada hunk como janela
# (ctx-before, -removidas, +adicionadas, ctx-after) com matching flexivel.
import re

def rx_linha(alvo):
    parts = alvo.strip().split()
    if not parts: return None
    return re.compile(r"^\s*" + r"\s+".join(re.escape(p) for p in parts) + r"\s*$")

def busca(lines, alvo, start=0):
    rx = rx_linha(alvo)
    if rx is None: return -1
    for i in range(start, len(lines)):
        if rx.match(lines[i]): return i
    return -1

def parse_hunks(path):
    hunks, cur = [], None
    for raw in open(path, encoding="utf-8", errors="replace").read().splitlines():
        if raw.startswith("@@"):
            cur = []
            hunks.append(cur)
            continue
        if cur is None: continue
        if raw.startswith("+++") or raw.startswith("---"): continue
        if raw.startswith("+") or raw.startswith("-") or raw.startswith(" ") or raw == "":
            cur.append(raw)
    return hunks

target = "/root/kq2/ggml/src/ggml-hexagon/ggml-hexagon.cpp"
hunks = parse_hunks("/root/kq2/ggml/src/ggml-hexagon/ggml-hexagon.cpp.rej")
print(f"hunks: {len(hunks)}")

resultados = []
for hi, h in enumerate(hunks):
    lines = open(target).read().splitlines()
    removidas = []   # indices
    insercoes = []   # (pos, [linhas])
    cursor = 0
    pend = False
    i = 0
    while i < len(h):
        raw = h[i]
        if raw.startswith(" ") or raw == "":
            txt = raw[1:] if raw.startswith(" ") else ""
            if txt.strip():
                p = busca(lines, txt, cursor)
                if p < 0:
                    pend = True; break
                cursor = p + 1
            i += 1
            continue
        # bloco de - e +
        mns, pls = [], []
        pos_prim = None
        while i < len(h) and (h[i].startswith("-") or h[i].startswith("+")):
            if h[i].startswith("-"):
                p = busca(lines, h[i][1:], cursor)
                if p < 0:
                    pend = True; break
                if pos_prim is None: pos_prim = p
                removidas.append(p)
                cursor = p + 1
            else:
                pls.append(h[i][1:])
            i += 1
        if pend: break
        if pos_prim is not None and pls:
            insercoes.append((pos_prim, pls))
        elif pos_prim is None and pls:
            insercoes.append((cursor, pls))
    if pend:
        resultados.append((hi, "PENDENTE", 0, 0))
        continue
    # aplicar: remover de tras p/ frente; inserir ajustando indices
    offs = 0
    rem = sorted(set(removidas), reverse=True)
    novas = list(lines)
    for r in rem:
        del novas[r]
    for pos, pls in sorted(insercoes):
        ajust = pos - sum(1 for r in rem if r < pos)
        novas[ajust:ajust] = pls
    open(target, "w").write("\n".join(novas) + "\n")
    resultados.append((hi, "OK", len(removidas), sum(len(p) for _, p in insercoes)))

res = {}
for hi, st, nr, ni in resultados:
    res[st] = res.get(st, 0) + 1
    print(f"hunk[{hi}]: {st} -r{nr} +i{ni}")
print("RESUMO:", res)
# sanity: marcadores nao podem existir
t = open(target).read()
print("marcadores:", t.count("<<<<"), "| tamanho:", len(t))
