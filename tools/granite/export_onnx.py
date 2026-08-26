#!/usr/bin/env python
"""Exporta o Granite Speech 5.0 TurboCTC para ONNX (F32, logits puros).

Saídas:
  granite-5.0-turboctc-f32.onnx         (grafo)
  granite-5.0-turboctc-f32.onnx.data    (pesos externos, ~1.9GB)
  (arquivos de config do front-end são copiados do Space da IBM)

Valida: roda o ONNX com as MESMAS features do PyTorch e compara o argmax.
"""
import sys, time, json, io
from pathlib import Path

import numpy as np
import torch
import onnxruntime as ort
from transformers import AutoProcessor, AutoModelForCTC

MODEL_DIR = Path(__file__).parent / "models" / "granite-speech-5.0-470m-turboctc"
OUT_DIR = Path(__file__).parent / "out"
PAD_MULTIPLE = 512
INPUT_DIM = 320


def make_features(processor, audio: np.ndarray) -> dict:
    """Features no formato do processor (com padding para múltiplo de pad_multiple)."""
    inputs = processor(audio, sampling_rate=processor.feature_extractor.sampling_rate,
                       return_tensors="pt")
    feat = inputs["input_features"]            # [1, T', 320]
    mask = inputs.get("attention_mask")        # pode não vir
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


def main():
    print(f"[{time.strftime('%H:%M:%S')}] carregando modelo...", flush=True)
    model = AutoModelForCTC.from_pretrained(str(MODEL_DIR), dtype=torch.float32)
    model.eval()
    processor = AutoProcessor.from_pretrained(str(MODEL_DIR))
    print(f"[{time.strftime('%H:%M:%S')}] modelo {model.dtype} ok", flush=True)

    # Áudio de teste (seno 440Hz 3s — só para validar shapes; a comparação de
    # argmax vale para qualquer entrada).
    sr = processor.feature_extractor.sampling_rate
    t = np.arange(sr * 3) / sr
    audio = (0.1 * np.sin(2 * np.pi * 440 * t)).astype(np.float32)
    feats = make_features(processor, audio)
    T = feats["input_features"].shape[1]
    print(f"[{time.strftime('%H:%M:%S')}] features T={T}", flush=True)

    # Forward PyTorch (greedy argmax) — referência.
    with torch.no_grad():
        logits_pt = model(**feats).logits                     # [1, T/4, 16384]
    ids_pt = logits_pt.argmax(dim=-1).squeeze(0).tolist()     # [T/4]
    print(f"[{time.strftime('%H:%M:%S')}] logits_pt {tuple(logits_pt.shape)}", flush=True)

    # Export ONNX (pesos externos, F32, opset 17).
    # Shape ESTÁTICO [1, 512, 320] (pad_multiple): o block attention do conformer
    # exige T múltiplo de 512; o engine Android sempre usa janelas fixas de 512
    # frames (10,24s) — igual ao warmup do Space da IBM. Sem dynamic_axes, a
    # reshape simbólica (s37//128) que quebra o dynamo não aparece.
    OUT_DIR.mkdir(exist_ok=True)
    onnx_path = OUT_DIR / "granite-5.0-turboctc-f32.onnx"
    torch.onnx.export(
        model,
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

    # Validar com onnxruntime (CPU).
    sess = ort.InferenceSession(str(onnx_path), providers=["CPUExecutionProvider"])
    logits_onnx = sess.run(
        ["logits"],
        {
            "input_features": feats["input_features"].numpy(),
            "attention_mask": feats["attention_mask"].numpy().astype(np.float32),
        },
    )[0]
    ids_onnx = logits_onnx.argmax(axis=-1)[0].tolist()
    n = min(len(ids_pt), len(ids_onnx))
    mism = sum(1 for a, b in zip(ids_pt[:n], ids_onnx[:n]) if a != b)
    print(f"[{time.strftime('%H:%M:%S')}] validação: {n} frames, {mism} divergências")
    ok = mism == 0 and len(ids_pt) == len(ids_onnx)
    print("RESULTADO:", "OK" if ok else "FALHOU")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
