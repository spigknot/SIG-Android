#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""break-even.py — C.5: calcula o ponto de equilíbrio HTP vs CPU (e OCL).

Modelo linear simples (exclui load/copies — hipotese, nao medido):
  t_backend(Np, Ng) = Np / pp + Ng / tg
Break-even HTP vs CPU:  t_htp = t_cpu  ->  Np/Ng = (1/tg_cpu - 1/tg_htp) / (1/pp_htp - 1/pp_cpu)

Uso: python break-even.py   (usa os valores do dict abaixo — atualizar com o bench)
"""
import json, sys

# Valores (Q4_0 upstream, r=3) — atualizados apos C.2 quando disponivel
V = {
    "cpu": {"pp": 372.77, "tg": 48.03},
    "htp": {"pp": 1082.29, "tg": 37.92},
    "ocl": {"pp": 355.03, "tg": 21.69},
}

def breakeven(a, b):
    """ratio Np/Ng onde a == b; None se nunca cruza."""
    num = 1.0/b["tg"] - 1.0/a["tg"]
    den = 1.0/a["pp"] - 1.0/b["pp"]
    if abs(den) < 1e-12: return None
    r = num/den
    return r if r > 0 else None

def t(V, Np, Ng):
    return Np/V["pp"] + Ng/V["tg"]

print("=== modelo linear: t = Np/pp + Ng/tg (segundos) ===")
for nome, v in V.items():
    print(f"  {nome}: {v['pp']:.1f} pp | {v['tg']:.1f} tg t/s")

print("\n=== break-even (Np/Ng) — acima disso o 1o backend vence ===")
pares = [("htp","cpu"), ("htp","ocl"), ("cpu","ocl")]
for a, b in pares:
    r = breakeven(V[a], V[b])
    if r is None:
        print(f"  {a} vs {b}: sem cruzamento positivo")
    else:
        print(f"  {a} vs {b}: Np/Ng = {r:.2f} ({a} vence SOH com >{r:.1f} tokens de entrada por token de saida)")

print("\n=== cenarios de traducao (Np->Ng tipicos) ===")
casos = [
    ("frase curta (30/25)", 30, 25),
    ("paragrafo (200/180)", 200, 180),
    ("texto longo (1000/900)", 1000, 900),
]
print(f"{'caso':28s} {'CPU s':>8s} {'HTP s':>8s} {'OCL s':>8s}  vencedor")
for nome, Np, Ng in casos:
    ts = {k: t(v, Np, Ng) for k, v in V.items()}
    win = min(ts, key=ts.get)
    print(f"{nome:28s} {ts['cpu']:8.2f} {ts['htp']:8.2f} {ts['ocl']:8.2f}  {win}")

# nota de limite
print("\nLIMITES: exclui load/copies/transferencias; linearidade NAO validada acima de pp4096.")
print("Hibrido NAO incluido (prefill HTP + decode CPU teria numeros proprios se provado viavel).")
