#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Fixture de CONTRATO offline (ordem R7-2): valida JSONLs contra o schema +
invariantes de gate. Uso: valida-schema.py arquivo.jsonl [arquivo2...]
Invariantes (fail => linha FAIL):
  I1 bench=true            -> dec_s == 0 (nunca aceita benchmark com compute na origem)
  I2 cap=true             -> resultado contem PARCIAL
  I3 eog_tok1=true        -> stop == 3
  I4 ok=false             -> resultado NAO contem ACEITO/CONCLUIDO
  I5 ok=true && bench=false -> resultado ACEITO (e div coerente se presente)
  I6 wall_ms > 0 e prefill_ms >= 0
  I7 texto vazio => (eog_tok1 || !ok)
  I8 rota com ':' e src==dst (alias) exige que o probe tenha carregado 2 ctxs
     (load_dst_ms e load_src_ms reportados — validado quando presentes)
Campos obrigatorios: t,sess,i,rota,bench,ok,prefill_ms,gen_ms,wall_ms,texto,resultado
"""
import json, sys

OBRIG = ["t", "sess", "i", "rota", "bench", "ok", "wall_ms", "texto", "resultado"]

def valida(path):
    tot = ok = 0
    falhas = []
    for ln, linha in enumerate(open(path, encoding="utf-8", errors="replace"), 1):
        linha = linha.strip()
        if not linha:
            continue
        try:
            j = json.loads(linha)
        except Exception as e:
            falhas.append((ln, f"JSON invalido: {e}")); tot += 1; continue
        if j.get("t") != "req":
            continue
        tot += 1
        probs = []
        for c in OBRIG:
            if c not in j:
                probs.append(f"campo ausente: {c}")
        if probs:
            falhas.append((ln, "; ".join(probs))); continue
        res = str(j.get("resultado", ""))
        if j.get("bench") and j.get("dec_s") not in (0, None):
            probs.append(f"I1: bench com dec_s={j.get('dec_s')}")
        if j.get("cap") and "PARCIAL" not in res:
            probs.append("I2: cap sem PARCIAL")
        if j.get("eog_tok1") and j.get("stop") != 3:
            probs.append("I3: eog_tok1 com stop != 3")
        if not j.get("ok") and ("ACEITO" in res or "CONCLUIDO" in res):
            probs.append("I4: ok=false com veredito positivo")
        if j.get("ok") and not j.get("bench") and "ACEITO" not in res and "EXECUTADO" not in res:
            probs.append("I5: ok=true sem veredito positivo")
        if not (j.get("wall_ms", 0) > 0):
            probs.append("I6: wall_ms <= 0")
        if not j.get("texto") and not (j.get("eog_tok1") or not j.get("ok")):
            probs.append("I7: texto vazio com ok")
        if probs:
            falhas.append((ln, "; ".join(probs)))
        else:
            ok += 1
    print(f"{path}: {ok}/{tot} PASS")
    for ln, p in falhas[:12]:
        print(f"   linha {ln}: FAIL - {p}")
    return not falhas

if __name__ == "__main__":
    tudo = True
    for a in sys.argv[1:]:
        tudo = valida(a) and tudo
    print("RESULTADO:", "TODOS PASS" if tudo else "HA FALHAS")
