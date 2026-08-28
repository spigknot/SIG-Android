#!/usr/bin/env python
"""Valida o pipeline completo do Granite 5.0 TurboCTC num WAV de teste."""
import sys
from pathlib import Path
import numpy as np
import soundfile as sf
import torch
from transformers import AutoProcessor, AutoModelForCTC
import onnxruntime as ort

BASE = Path(__file__).resolve().parent
MODEL_DIR = BASE / "models" / "granite-speech-5.0-470m-turboctc"
ONNX = BASE / "out" / "granite-5.0-turboctc-f32-ext.onnx"
WAV = BASE / "test_en_16k.wav"

audio, sr = sf.read(str(WAV), dtype="float32")
if sr != 16000:
    print("ERRO: sample rate != 16000"); sys.exit(1)

print(f"áudio: {len(audio)/sr:.2f}s, {sr}Hz", flush=True)

# 1) Pipeline PyTorch (referência)
model = AutoModelForCTC.from_pretrained(str(MODEL_DIR), dtype=torch.float32).eval()
processor = AutoProcessor.from_pretrained(str(MODEL_DIR))
inputs = processor(audio, sampling_rate=16000, return_tensors="pt")
feat = inputs["input_features"]
T = feat.shape[1]
pad = (512 - (T % 512)) % 512
if pad:
    feat = torch.nn.functional.pad(feat, (0, 0, 0, pad))
mask = torch.ones(1, T, dtype=torch.long)
if pad:
    mask = torch.nn.functional.pad(mask, (0, pad))
with torch.no_grad():
    logits = model(input_features=feat, attention_mask=mask).logits
ids = logits.argmax(dim=-1).squeeze(0).tolist()
# remove frames de padding (mask==0)
real = (mask[0] == 1).sum().item() // 4
ids = ids[:real]
# CTC collapse
prev = -1
collapsed = []
for i in ids:
    if i != prev and i != 0:
        collapsed.append(i)
    prev = i
text_pt = processor.batch_decode([collapsed], skip_special_tokens=True)[0].strip()
print(f"[PyTorch] {text_pt}", flush=True)

# 2) Pipeline ONNX (mesmas features)
sess = ort.InferenceSession(str(ONNX), providers=["CPUExecutionProvider"])
out = sess.run(["logits"], {"input_features": feat.numpy(), "attention_mask": mask.numpy().astype(np.int64)})[0]
ids2 = out.argmax(axis=-1)[0].tolist()[:real]
prev = -1
collapsed2 = []
for i in ids2:
    if i != prev and i != 0:
        collapsed2.append(i)
    prev = i
text_onnx = processor.batch_decode([collapsed2], skip_special_tokens=True)[0].strip()
print(f"[ONNX]     {text_onnx}", flush=True)

ok = text_pt == text_onnx
print("IGUAIS:", ok, flush=True)
sys.exit(0 if ok else 1)
