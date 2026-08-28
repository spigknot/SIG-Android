#!/usr/bin/env python
"""Divide o ONNX embutido em external data e valida com onnxruntime."""
import sys, time
from pathlib import Path
import numpy as np
import onnx
from onnx.external_data_helper import convert_model_to_external_data
import onnxruntime as ort

BASE = Path(__file__).resolve().parent
SRC = BASE / "out" / "granite-5.0-turboctc-f32.onnx"
OUT = BASE / "out" / "granite-5.0-turboctc-f32-ext.onnx"

print("carregando...", flush=True)
m = onnx.load(str(SRC), load_external_data=False)
print("convertendo para external data...", flush=True)
convert_model_to_external_data(m, all_tensors_to_one_file=True, location="granite-5.0-turboctc-f32-ext.onnx.data", size_threshold=1024)
onnx.save(m, str(OUT))
print(f"salvo: {OUT.name} ({OUT.stat().st_size/1e6:.1f}MB) + .data", flush=True)
data = OUT.with_name("granite-5.0-turboctc-f32-ext.onnx.data")
print(f"data: {data.stat().st_size/1e9:.2f}GB", flush=True)

print("validando com onnxruntime...", flush=True)
sess = ort.InferenceSession(str(OUT), providers=["CPUExecutionProvider"])
feat = np.random.randn(1, 512, 320).astype(np.float32)
mask = np.ones((1, 512), dtype=np.int64)
out = sess.run(["logits"], {"input_features": feat, "attention_mask": mask})[0]
print("logits:", out.shape, "finito:", np.isfinite(out).all(), flush=True)
# Valida contra o ONNX embutido (mesma saída esperada).
sess2 = ort.InferenceSession(str(SRC), providers=["CPUExecutionProvider"])
out2 = sess2.run(["logits"], {"input_features": feat, "attention_mask": mask})[0]
print("igual ao embutido:", np.allclose(out, out2, atol=1e-4), flush=True)
print("RESULTADO:", "OK" if np.isfinite(out).all() and np.allclose(out, out2, atol=1e-4) else "FALHOU", flush=True)
sys.exit(0 if np.isfinite(out).all() and np.allclose(out, out2, atol=1e-4) else 1)
