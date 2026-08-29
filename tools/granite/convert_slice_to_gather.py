"""Converte Slice (constantes, steps=1) do modelo Granite FP16 em Gather.

O QNN GPU EP do ORT 1.29 rejeita StridedSlice com error 3110 mesmo com
starts/ends/axes/steps constantes e steps=1. Gather é suportado.

Estratégia: para cada Slice com parâmetros constantes e steps=1, substitui por
Gather(axis, indices=[start..end-1]). Slice com steps != 1 ou params dinâmicos
ficam como estão.
"""
import onnx, numpy as np, sys
from onnx import numpy_helper, helper

SRC = "D:/Projetos/SIG/tools/granite/package/granite-5.0-turboctc-fp16-ext.onnx"
DST = "D:/Projetos/SIG/tools/granite/package/granite-5.0-turboctc-fp16-gather.onnx"

m = onnx.load(SRC)

# 1. Coleta de Constant nodes e initializers
const_vals = {}
for init in m.graph.initializer:
    const_vals[init.name] = numpy_helper.to_array(init)
for n in m.graph.node:
    if n.op_type == "Constant":
        for a in n.attribute:
            if a.name == "value":
                const_vals[n.output[0]] = numpy_helper.to_array(a.t)

# 2. Shape inference
mi = onnx.shape_inference.infer_shapes(m)
shapes = {}
for v in mi.graph.value_info:
    dims = [d.dim_value if d.HasField("dim_value") else None for d in v.type.tensor_type.shape.dim]
    shapes[v.name] = dims

# Quais Constant nodes existem e são usados só por Slice? Para simplificar,
# mantemos os Constant originais (não removemos) e adicionamos os novos.
new_nodes = []
converted = 0
skipped = 0

for n in m.graph.node:
    if n.op_type != "Slice":
        new_nodes.append(n)
        continue
    try:
        starts = const_vals.get(n.input[1])
        ends = const_vals.get(n.input[2])
        axes = const_vals.get(n.input[3]) if len(n.input) > 3 else None
        steps = const_vals.get(n.input[4]) if len(n.input) > 4 else None
        if starts is None or ends is None:
            skipped += 1
            new_nodes.append(n)
            continue
        steps = np.asarray(steps).reshape(-1) if steps is not None else np.ones(len(np.asarray(starts).reshape(-1)), dtype=np.int64)
        if not np.all(steps == 1):
            skipped += 1
            new_nodes.append(n)
            continue
        data = n.input[0]
        shape = shapes.get(data)
        if shape is None:
            skipped += 1
            new_nodes.append(n)
            continue

        axes_arr = np.asarray(axes).reshape(-1) if axes is not None else np.arange(len(starts), dtype=np.int64)
        # Gera os Gathers encadeados
        current = data
        ok = True
        generated = []
        for i, ax in enumerate(axes_arr):
            ax = int(ax)
            ax = ax if ax >= 0 else ax + len(shape)
            st = int(starts[i]); en = int(ends[i])
            dim = shape[ax] if ax < len(shape) else None
            if dim is None or en <= st or st < 0:
                ok = False
                break
            indices = np.arange(st, en, dtype=np.int32)
            if len(indices) == dim:
                # no-op: mantém o mesmo tensor
                continue
            idx_name = f"{n.name}_gather_idx_{i}"
            generated.append(helper.make_node(
                "Constant", inputs=[], outputs=[idx_name],
                name=f"{n.name}_gather_const_{i}",
                value=numpy_helper.from_array(indices, name=idx_name),
            ))
            out_name = n.output[0] if i == len(axes_arr) - 1 else f"{n.name}_gather_{i}"
            generated.append(helper.make_node(
                "Gather", inputs=[current, idx_name], outputs=[out_name],
                name=f"{n.name}_gather_{i}", axis=ax,
            ))
            current = out_name
        if not ok:
            skipped += 1
            new_nodes.append(n)
            continue
        if not generated:
            # virou no-op total (nenhum gather gerado) -> identidade
            new_nodes.append(helper.make_node("Identity", inputs=[data], outputs=[n.output[0]], name=f"{n.name}_identity"))
            converted += 1
        else:
            new_nodes.extend(generated)
            converted += 1
    except Exception as e:
        skipped += 1
        new_nodes.append(n)

m.graph.ClearField("node")
m.graph.node.extend(new_nodes)
onnx.checker.check_model(m)
onnx.save(m, DST)
print(f"Convertidos: {converted}, skipped: {skipped}")
