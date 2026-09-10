"""Reescreve os Einsum de atencao relativa do encoder NAR em ops cobertos pelo QNN EP.

Por que existe: o encoder conformer do Granite 4.1 NAR tem 16 nos `Einsum` com a equacao

    b m h c d , c r d -> b m h c r

e o **QNN EP do ORT mainline nao implementa `Einsum`** (ele existe apenas no fork
onnxruntime-qnn). Cada um desses nos e atribuido ao CPU EP, o que produz exatamente a
rejeicao registrada em docs/qairt-status.md para a NPU estrita:

    "encoder rejeitado porque ha nos atribuidos ao CPU EP"

Como `P` nao depende de `b` nem de `m`:

    out[m,h,c,r] = sum_d q[m,h,c,d] * P[c,r,d]

reescrevemos com `Split + Reshape + Transpose + MatMul + Concat`, explorando o
broadcasting numpy-style do `MatMul` do ONNX (todos cobertos pelo HTP):

    Split(q, axis=1) [m fatias]        -> (1,1,h,c,d)
    Reshape                            -> (h,c,1,d)
    Transpose(P,[0,2,1]) + Reshape     -> (1,c,d,r)
    MatMul(q_i, P4)                    -> (h,c,1,r)    batch (h,c,1) vs (1,c,1)
    Concat(axis=0)                     -> (m*h,c,1,r)
    Reshape                            -> (1,m,h,c,r)

Caminho descartado: `Mul` com broadcast + `ReduceSum` no ultimo eixo geraria um
intermediario de 1*10*8*200*200*128 = 410 M elementos (~820 MB em fp16) por camada.

Uso:
    python tools/granite/nar/einsum_to_matmul.py --in <encoder.onnx> --out <encoder-matmul.onnx>

A verificacao que decide nao e a tolerancia numerica: e a transcricao nao mudar. A
paridade tensor a tensor fica na casa de cos 0,999997 com rel ~1e-2 — ruido de
reassociacao em fp16 (eps ~1e-3, 128 somas por cabeca), nao erro de matematica.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import numpy as np
import onnx
from onnx import helper, numpy_helper

sys.path.insert(0, str(Path(__file__).parent))
from common import atomic_write_json  # noqa: E402

# Equacao alvo, normalizada (sem espacos).
EINSUM_ALVO = "bmhcd,crd->bmhcr"


def equacao_do_no(no) -> str:
    """Extrai a equacao de um no Einsum, normalizada."""
    for a in no.attribute:
        if a.name == "equation":
            return a.s.decode().replace(" ", "")
    raise ValueError(f"{no.name}: Einsum sem atributo `equation`")


def shapes_do_grafo(modelo):
    """Shapes por tensor + shape canonico da camada 0 (fallback para as demais).

    O shape inference nao propaga o shape das camadas 1..N (bloco conformer empilhado
    identico). Como o bloco e o mesmo por construcao, o shape canonico da camada 0 vale
    para todas — usado apenas quando o inference devolve None.
    Casa por SUFIXO porque o prefixo muda entre exports (`/encoder/layers.N/...` vs
    `/layers.N/...`).
    """
    inf = onnx.shape_inference.infer_shapes(modelo, strict_mode=False)
    out = {}
    for vi in list(inf.graph.value_info) + list(inf.graph.input) + list(inf.graph.output):
        d = vi.type.tensor_type.shape.dim
        out[vi.name] = [x.dim_value if x.dim_value else None for x in d]
    canon = None
    for nome, sh in sorted(out.items()):
        if nome.endswith("/attn/Transpose_output_0") and sh and None not in sh:
            canon = sh
            break
    return out, canon


def decompor(no, shapes, canon=None):
    """Devolve (nos_novos, initializers_novos) para um Einsum `bmhcd,crd->bmhcr`."""
    eq = equacao_do_no(no)
    if eq != EINSUM_ALVO:
        raise ValueError(f"equacao inesperada: {eq!r} (esperado {EINSUM_ALVO!r})")

    q, p = no.input
    sq, sp = shapes.get(q), shapes.get(p)
    if canon and (not sq or None in sq):
        sq = canon
    if not sq or not sp or None in sq or None in sp:
        raise ValueError(f"shapes incompletos: q={sq} P={sp}")
    b, m, h, c, d = sq
    pc, pr, pd_ = sp
    if b != 1:
        raise ValueError(f"a decomposicao assume batch 1 (recebido {b})")
    if (pc, pd_) != (c, d):
        raise ValueError(f"P incompativel: {sp} vs q {sq}")

    pfx = f"{no.name}_dec"
    nos, inits = [], []

    def const(sufixo, valor):
        nome = f"{pfx}_{sufixo}"
        inits.append(numpy_helper.from_array(np.array(valor, dtype=np.int64), nome))
        return nome

    split_outs = [f"{pfx}_split{i}" for i in range(m)]
    nos.append(helper.make_node("Split", [q], split_outs, axis=1, name=f"{pfx}_Split"))

    nos.append(helper.make_node("Transpose", [p], [f"{pfx}_Pt"], perm=[0, 2, 1],
                                name=f"{pfx}_TransposeP"))
    sh_p = const("shp", [1, c, d, pr])
    nos.append(helper.make_node("Reshape", [f"{pfx}_Pt", sh_p], [f"{pfx}_P4"],
                                name=f"{pfx}_ReshapeP"))

    mm = []
    for i in range(m):
        sh_q = const(f"shq{i}", [h, c, 1, d])
        nos.append(helper.make_node("Reshape", [split_outs[i], sh_q], [f"{pfx}_q4_{i}"],
                                    name=f"{pfx}_ReshapeQ{i}"))
        nos.append(helper.make_node("MatMul", [f"{pfx}_q4_{i}", f"{pfx}_P4"],
                                    [f"{pfx}_mm{i}"], name=f"{pfx}_MatMul{i}"))
        mm.append(f"{pfx}_mm{i}")
    cat = mm[0] if m == 1 else f"{pfx}_cat"
    if m > 1:
        nos.append(helper.make_node("Concat", mm, [cat], axis=0, name=f"{pfx}_ConcatM"))

    sh_o = const("sho", [b, m, h, c, pr])
    nos.append(helper.make_node("Reshape", [cat, sh_o], [no.output[0]],
                                name=f"{pfx}_ReshapeOut"))
    return nos, inits


def decompor_modelo(modelo):
    """Substitui TODOS os Einsum alvo. Devolve (trocados, falhas)."""
    shapes, canon = shapes_do_grafo(modelo)
    novos, inits, trocados, falhas = [], [], 0, []
    for i, no in enumerate(modelo.graph.node):
        if no.op_type != "Einsum":
            novos.append(no)
            continue
        try:
            nn, ii = decompor(no, shapes, canon)
            novos.extend(nn)
            inits.extend(ii)
            trocados += 1
        except Exception as e:                      # noqa: BLE001
            falhas.append(f"{no.name}: {e}")
            novos.append(no)
    del modelo.graph.node[:]
    modelo.graph.node.extend(novos)
    modelo.graph.initializer.extend(inits)
    return trocados, falhas, canon


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--in", dest="entrada", required=True)
    ap.add_argument("--out", dest="saida", required=True)
    ap.add_argument("--report", default=None)
    a = ap.parse_args()

    modelo = onnx.load(a.entrada)
    antes = len(modelo.graph.node)
    print(f"[einsum->matmul] {Path(a.entrada).name}: {antes} nos")
    trocados, falhas, canon = decompor_modelo(modelo)
    print(f"[einsum->matmul] shape canonico: {canon}")
    onnx.checker.check_model(modelo)
    Path(a.saida).parent.mkdir(parents=True, exist_ok=True)
    onnx.save(modelo, a.saida)
    restantes = sum(1 for n in modelo.graph.node if n.op_type == "Einsum")
    print(f"[einsum->matmul] trocados={trocados} falhas={len(falhas)} "
          f"| {antes} -> {len(modelo.graph.node)} nos | Einsum restantes={restantes}")
    for f in falhas[:5]:
        print("   ", f)
    if a.report:
        atomic_write_json(a.report, {
            "entrada": a.entrada, "saida": a.saida, "einsum_trocados": trocados,
            "falhas": falhas, "nos_antes": antes,
            "nos_depois": len(modelo.graph.node), "einsum_restantes": restantes,
            "shape_canonico": canon,
        })
    return 0 if not falhas else 2


if __name__ == "__main__":
    raise SystemExit(main())
