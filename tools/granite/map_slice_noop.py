"""Mapeia todos os Slice no-op do modelo Granite (fatiam a dim inteira)."""
import onnx, numpy as np, sys
from onnx import numpy_helper

model_path = "D:/Projetos/SIG/tools/granite/package/granite-5.0-turboctc-fp16-ext.onnx"
m = onnx.load(model_path)
consts = {}
for n in m.graph.node:
    if n.op_type == "Constant":
        for a in n.attribute:
            if a.name == "value":
                consts[n.output[0]] = numpy_helper.to_array(a.t)

# shape inference
mi = onnx.shape_inference.infer_shapes(m)
shapes = {}
for v in mi.graph.value_info:
    dims = [d.dim_value if d.HasField("dim_value") else None for d in v.type.tensor_type.shape.dim]
    shapes[v.name] = dims

noop = []
other = []
for n in m.graph.node:
    if n.op_type == "Slice":
        try:
            data = n.input[0]
            starts = consts.get(n.input[1])
            ends = consts.get(n.input[2])
            axes = consts.get(n.input[3]) if len(n.input) > 3 else None
            steps = consts.get(n.input[4]) if len(n.input) > 4 else None
            if starts is None or ends is None:
                other.append((n.name, "dynamic-params"))
                continue
            # resolve shape
            shape = shapes.get(data)
            if shape is None:
                other.append((n.name, "no-shape"))
                continue
            is_noop = True
            for i, ax in enumerate(axes.tolist() if axes is not None else range(len(starts))):
                ax = ax if ax >= 0 else ax + len(shape)
                dim = shape[ax] if ax < len(shape) else None
                st = int(starts[i]); en = int(ends[i])
                stp = int(steps[i]) if steps is not None else 1
                if dim is not None and not (st == 0 and en >= dim and stp == 1):
                    is_noop = False
            if is_noop:
                noop.append(n.name)
            else:
                other.append((n.name, f"slice-real ax={axes.tolist() if axes is not None else None} st={starts.tolist()} en={ends.tolist()} shape={shape}"))
        except Exception as e:
            other.append((n.name, f"err {e}"))

print(f"TOTAL Slice nodes: {len([n for n in m.graph.node if n.op_type=='Slice'])}")
print(f"NO-OP (removíveis): {len(noop)}")
print(f"OUTROS (reais/dinâmicos): {len(other)}")
print("\n--- OUTROS (primeiros 30) ---")
for name, desc in other[:30]:
    print(f"  {name}: {desc}")
