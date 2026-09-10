"""Dobra subgrafos constantes de um grafo ONNX usando o otimizador do ORT.

Por que existe: `GraniteNarEngine.kt` cria as sessoes aceleradas com `OptLevel.NO_OPT`
("o QNN EP faz a propria conversao/particao; transformacoes ORT agressivas podem criar
padroes que o backend nao reconhece"). A intencao e correta, mas o efeito colateral e que
o **constant folding nao roda**: as cadeias de calculo de forma (`Shape`/`Mod`/`Concat`/
`Reshape`) chegam ao particionador do QNN como nos de verdade. Qualquer no que o EP nao
cubra vira **no de CPU EP** — e isso quebra a atribuicao integral exigida pela NPU estrita.

Medido no encoder do NAR: **690 nos `Constant`**, 49 `Shape`. No projector: `Mod` (que so
existe no fork onnxruntime-qnn, nao no mainline que o app usa) com a forma `2000 % 15`
sobre DOIS Constants, alimentando o shape de um `Reshape`.

O ORT faz essa dobra nativamente e corretamente. `optimized_model_filepath` grava o grafo
ja otimizado, que e entao servido como artefato estatico ao app (que continua em NO_OPT:
nao precisa de otimizacao em runtime porque o grafo ja vem dobrado).

O avaliador proprio do laboratorio so alcanca cadeias 100% constantes; os `Shape` operam
sobre tensores INTERMEDIARIOS, cuja forma apenas a inferencia de shape conhece — por isso
o caminho nativo do ORT.

Uso:
    python tools/granite/nar/fold_constant_subgraphs.py --in <grafo.onnx> --out <grafo-dobrado.onnx>

AVISO: o grafo otimizado declara shapes CONCRETAS no lugar das simbolicas da origem. Isso e
desejavel para QNN (que exige compilacao estatica), mas significa que o artefato so e valido
para a forma declarada — registre qual T foi assumido.
"""
from __future__ import annotations

import argparse
import collections
import json
import sys
from pathlib import Path

import onnx
import onnxruntime as ort

sys.path.insert(0, str(Path(__file__).parent))
from common import atomic_write_json  # noqa: E402

# Ops que interessam ao diagnostico de atribuicao ao QNN EP.
OPS_MONITORADOS = (
    "Constant", "Shape", "Concat", "Reshape", "Transpose", "MatMul", "Gemm", "Einsum",
    "Mod", "Gather", "Slice", "Unsqueeze", "Squeeze", "Cast", "Split", "Pad",
    "LayerNormalization", "Softmax",
)

NIVEIS = {
    "disable": ort.GraphOptimizationLevel.ORT_DISABLE_ALL,
    "basic": ort.GraphOptimizationLevel.ORT_ENABLE_BASIC,
    "extended": ort.GraphOptimizationLevel.ORT_ENABLE_EXTENDED,
    "all": ort.GraphOptimizationLevel.ORT_ENABLE_ALL,
}


def resumo_ops(caminho) -> dict:
    """Contagem de op_types (sem carregar os dados externos)."""
    m = onnx.load(str(caminho), load_external_data=False)
    return collections.Counter(n.op_type for n in m.graph.node)


def compara(antes: collections.Counter, depois: collections.Counter) -> dict:
    """Diferenca por op, somente onde houve mudanca."""
    return {k: {"antes": antes.get(k, 0), "depois": depois.get(k, 0)}
            for k in OPS_MONITORADOS
            if antes.get(k, 0) != depois.get(k, 0)}


def otimizar(entrada, saida, nivel: str = "basic") -> Path:
    """Roda o otimizador do ORT e grava o grafo resultante em `saida`."""
    if nivel not in NIVEIS:
        raise ValueError(f"nivel invalido: {nivel} (use {sorted(NIVEIS)})")
    so = ort.SessionOptions()
    so.log_severity_level = 3
    so.graph_optimization_level = NIVEIS[nivel]
    so.optimized_model_filepath = str(saida)
    Path(saida).parent.mkdir(parents=True, exist_ok=True)
    # A sessao existe apenas para disparar a otimizacao; nao executamos inferencia.
    ort.InferenceSession(str(entrada), so, providers=["CPUExecutionProvider"])
    return Path(saida)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--in", dest="entrada", required=True)
    ap.add_argument("--out", dest="saida", required=True)
    ap.add_argument("--level", default="basic", choices=sorted(NIVEIS))
    ap.add_argument("--report", default=None)
    a = ap.parse_args()

    antes = resumo_ops(a.entrada)
    n0 = sum(antes.values())
    print(f"[fold] {Path(a.entrada).name}: {n0} nos | nivel={a.level}")
    otimizar(a.entrada, a.saida, a.level)
    depois = resumo_ops(a.saida)
    n1 = sum(depois.values())
    print(f"[fold] {n1} nos ({n1 - n0:+d}) -> {Path(a.saida).name}")
    dif = compara(antes, depois)
    for k, v in sorted(dif.items(), key=lambda x: -(x[1]["antes"] - x[1]["depois"])):
        print(f"    {k:20s} {v['antes']:6d} -> {v['depois']:6d}")

    try:
        onnx.checker.check_model(str(a.saida))
        checker = "ok"
    except Exception as e:                          # noqa: BLE001
        checker = f"falhou: {str(e)[:200]}"
    print(f"[fold] onnx.checker: {checker}")

    # Confere que o contrato de I/O foi preservado (nomes e contagem).
    ma = onnx.load(str(a.entrada), load_external_data=False)
    mb = onnx.load(str(a.saida), load_external_data=False)
    io_igual = ([i.name for i in ma.graph.input] == [i.name for i in mb.graph.input]
                and [o.name for o in ma.graph.output] == [o.name for o in mb.graph.output])
    print(f"[fold] nomes de entrada/saida preservados: {io_igual}")

    if a.report:
        atomic_write_json(a.report, {
            "entrada": a.entrada, "saida": a.saida, "nivel": a.level,
            "nos_antes": n0, "nos_depois": n1, "diferenca_por_op": dif,
            "checker": checker, "nomes_io_preservados": io_igual,
            "aviso": ("shapes declaradas passam a ser concretas: o artefato vale apenas "
                      "para a forma de entrada informada"),
        })
    return 0 if checker == "ok" and io_igual else 2


if __name__ == "__main__":
    raise SystemExit(main())
