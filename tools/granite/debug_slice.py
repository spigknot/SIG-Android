"""Debug: por que os Slice reais não foram convertidos?"""
import onnx, numpy as np
from onnx import numpy_helper

m = onnx.load("D:/Projetos/SIG/tools/granite/package/granite-5.0-turboctc-fp16-ext.onnx")
const_vals = {}
for init in m.graph.initializer:
    const_vals[init.name] = numpy_helper.to_array(init)
for n in m.graph.node:
    if n.op_type == "Constant":
        for a in n.attribute:
            if a.name == "value":
                const_vals[n.output[0]] = numpy_helper.to_array(a.t)

mi = onnx.shape_inference.infer_shapes(m)
shapes = {}
for v in mi.graph.value_info:
    dims = [d.dim_value if d.HasField("dim_value") else None for d in v.type.tensor_type.shape.dim]
    shapes[v.name] = dims

# pega um slice real típico
for n in m.graph.node:
    if n.name == "/model/encoder/layers.0/Slice":
        print("node:", n.name)
        print("inputs:", list(n.input))
        print("data shape:", shapes.get(n.input[0]))
        starts = const_vals.get(n.input[1]); ends = const_vals.get(n.input[2])
        axes = const_vals.get(n.input[3]) if len(n.input) > 3 else None
        steps = const_vals.get(n.input[4]) if len(n.input) > 4 else None
        print("starts:", starts.tolist() if starts is not None else None)
        print("ends:", ends.tolist() if ends is not None else None)
        print("axes:", axes.tolist() if axes is not None else None)
        print("steps:", steps.tolist() if steps is not None else None)
        break
