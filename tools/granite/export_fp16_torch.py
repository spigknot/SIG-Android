#!/usr/bin/env python
"""Exporta o Granite Speech 5.0 TurboCTC para ONNX FP16 (exportado do PyTorch).

POR QUE NÃO converter um ONNX F32 pronto:
  onnxconverter_common.float16 só converte inicializadores e NÃO insere Cast
  nodes — o MatMul do encoder recebe input float32 (do grafo) x peso float16 e
  o ONNX Runtime rejeita ("Type parameter (T) bound to different types").

SOLUÇÃO (aqui): exportar do PyTorch com um wrapper que faz cast nas BORDAS:
    input_features fp32 -> Cast -> fp16 ... modelo fp16 ... -> Cast -> fp32 logits
  O grafo final tem pesos/ativações fp16 (o que o QNN GPU quer) mas I/O fp32
  (o engine Kotlin continua mandando FloatBuffer e lendo floatBuffer, sem mudança).

Saídas:
  out/granite-5.0-turboctc-fp16-ext.onnx         (grafo)
  out/granite-5.0-turboctc-fp16-ext.onnx.data    (pesos, ~950 MB)
Valida: argmax do ONNX FP16 vs modelo PyTorch FP16 (tolerância 1%) e vs F32 (tolerância 2%).
Uso:
  venv\\Scripts\\python.exe export_fp16_torch.py
"""
import sys, time, shutil
from pathlib import Path

import numpy as np
import torch
import onnxruntime as ort
from transformers import AutoProcessor, AutoModelForCTC

BASE = Path(__file__).resolve().parent
MODEL_DIR = BASE / "models" / "granite-speech-5.0-470m-turboctc"
OUT_DIR = BASE / "out"
PACKAGE = BASE / "package"
PAD_MULTIPLE = 512
INPUT_DIM = 320

# Tolerâncias de divergência do argmax (FP16 vs F32 raramente muda o CTC argmax,
# mas valores ~1e-8 truncados para 1e-7 podem deslocar logits em empates).
FP16_VS_FP16_TOL = 0.01   # ONNX FP16 vs PyTorch FP16: até 1% dos frames
FP16_VS_FP32_TOL = 0.02   # ONNX FP16 vs PyTorch F32: até 2%


class Fp16ModelWrapper(torch.nn.Module):
    """Modelo FP16 com cast nas bordas: entrada fp32 -> fp16, saída fp16 -> fp32.

    O attention_mask é convertido para fp16 (0.0/1.0) para evitar long x fp16
    nos MatMul internos (o modelo faz mask * scores)."""

    def __init__(self, model: torch.nn.Module):
        super().__init__()
        self.model = model.half()

    def forward(self, input_features: torch.Tensor, attention_mask: torch.Tensor):
        out = self.model(input_features.half(), attention_mask.half())
        return out.logits.float()


def make_features(processor, audio: np.ndarray) -> dict:
    inputs = processor(audio, sampling_rate=processor.feature_extractor.sampling_rate,
                       return_tensors="pt")
    feat = inputs["input_features"]            # [1, T', 320]
    mask = inputs.get("attention_mask")
    T = feat.shape[1]
    pad = (PAD_MULTIPLE - (T % PAD_MULTIPLE)) % PAD_MULTIPLE
    if pad:
        feat = torch.nn.functional.pad(feat, (0, 0, 0, pad))
    if mask is None:
        mask = torch.ones(1, T, dtype=torch.long)
        if pad:
            mask = torch.nn.functional.pad(mask, (0, pad))
    else:
        if pad:
            mask = torch.nn.functional.pad(mask, (0, pad))
    return {"input_features": feat, "attention_mask": mask}


def argmax_ids(logits: torch.Tensor) -> list:
    return logits.argmax(dim=-1).squeeze(0).tolist()


def divergence(a: list, b: list) -> float:
    n = min(len(a), len(b))
    if n == 0:
        return 1.0
    mism = sum(1 for x, y in zip(a[:n], b[:n]) if x != y)
    return mism / n


def main():
    print(f"[{time.strftime('%H:%M:%S')}] carregando modelo (fp32)...", flush=True)
    model = AutoModelForCTC.from_pretrained(str(MODEL_DIR), dtype=torch.float32)
    model.eval()
    processor = AutoProcessor.from_pretrained(str(MODEL_DIR))
    wrapper = Fp16ModelWrapper(model)
    wrapper.eval()
    print(f"[{time.strftime('%H:%M:%S')}] modelo fp16 pronto (dtype={wrapper.model.dtype})", flush=True)

    sr = processor.feature_extractor.sampling_rate
    t = np.arange(sr * 3) / sr
    audio = (0.1 * np.sin(2 * np.pi * 440 * t)).astype(np.float32)
    feats = make_features(processor, audio)
    T = feats["input_features"].shape[1]
    print(f"[{time.strftime('%H:%M:%S')}] features T={T}", flush=True)

    # Referências: PyTorch fp32 e PyTorch fp16 (wrapper)
    with torch.no_grad():
        logits_fp32 = model(**feats).logits
        logits_fp16 = wrapper(feats["input_features"], feats["attention_mask"])
    ids_fp32 = argmax_ids(logits_fp32)
    ids_fp16 = argmax_ids(logits_fp16)
    div_16_32 = divergence(ids_fp16, ids_fp32)
    print(f"[{time.strftime('%H:%M:%S')}] PyTorch fp16 vs fp32: divergência {div_16_32*100:.2f}%", flush=True)
    if div_16_32 > FP16_VS_FP32_TOL:
        print(f"FALHA: modelo fp16 divergiu demais do fp32 ({div_16_32*100:.2f}% > {FP16_VS_FP32_TOL*100}%)", flush=True)
        sys.exit(1)

    # Export ONNX (pesos externos, opset 17, shape estático [1,512,320])
    OUT_DIR.mkdir(exist_ok=True)
    onnx_path = OUT_DIR / "granite-5.0-turboctc-fp16-ext.onnx"
    print(f"[{time.strftime('%H:%M:%S')}] exportando ONNX FP16...", flush=True)
    torch.onnx.export(
        wrapper,
        (feats["input_features"], feats["attention_mask"]),
        str(onnx_path),
        input_names=["input_features", "attention_mask"],
        output_names=["logits"],
        opset_version=17,
        do_constant_folding=True,
        external_data=True,
        dynamo=False,
    )
    data_path = onnx_path.with_suffix(".onnx.data")
    print(f"[{time.strftime('%H:%M:%S')}] ONNX salvo: {onnx_path.name} "
          f"({onnx_path.stat().st_size/1e6:.1f}MB + {data_path.stat().st_size/1e9:.2f}GB)", flush=True)

    # Valida com ONNX Runtime (CPU) — mesmas features
    print(f"[{time.strftime('%H:%M:%S')}] validando ONNX FP16 (CPU)...", flush=True)
    sess = ort.InferenceSession(str(onnx_path), providers=["CPUExecutionProvider"])
    logits_onnx = sess.run(
        ["logits"],
        {
            "input_features": feats["input_features"].numpy(),
            "attention_mask": feats["attention_mask"].numpy().astype(np.int64),
        },
    )[0]
    ids_onnx = logits_onnx.argmax(axis=-1)[0].tolist()

    div_onnx_16 = divergence(ids_onnx, ids_fp16)
    div_onnx_32 = divergence(ids_onnx, ids_fp32)
    print(f"[{time.strftime('%H:%M:%S')}] ONNX fp16 vs PyTorch fp16: {div_onnx_16*100:.2f}%", flush=True)
    print(f"[{time.strftime('%H:%M:%S')}] ONNX fp16 vs PyTorch fp32: {div_onnx_32*100:.2f}%", flush=True)
    ok = div_onnx_16 <= FP16_VS_FP16_TOL and div_onnx_32 <= FP16_VS_FP32_TOL
    print("RESULTADO:", "OK" if ok else "FALHOU", flush=True)
    if not ok:
        sys.exit(1)

    # Copia para package/ (publicação)
    print(f"[{time.strftime('%H:%M:%S')}] copiando para package/...", flush=True)
    shutil.copy2(onnx_path, PACKAGE / onnx_path.name)
    shutil.copy2(data_path, PACKAGE / data_path.name)
    print(f"[{time.strftime('%H:%M:%S')}] FP16 pronto para publicação em package/", flush=True)
    sys.exit(0)


if __name__ == "__main__":
    main()