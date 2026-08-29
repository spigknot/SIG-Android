#!/usr/bin/env python
"""Fase 2: exporta a variante FP16 do Granite Speech 5.0 TurboCTC.
Script corrigido — a primeira tentativa com keep_io_types=True deu erro de tipo
no MatMul (/encoder/input_linear/MatMul) porque pesos float16 + entrada float32
quebram a type inference do ONNX Runtime.

CORREÇÃO: usa keep_io_types=False (modelo FULLY FP16) + Cast manual dos I/O para
float32 após load. Alternativa: reexportar do PyTorch com dtype=float16 direto —
é mais confiável que converter um ONNX existente.

STATUS: BLOQUEADO até decidir a estratégia de conversão FP16.
         O modelo F32 (1.9 GB) é usado para todos os backends; o QNN EP faz
         conversão FP16 interna via enable_htp_fp16_precision=1.
"""
import sys, time
from pathlib import Path
import numpy as np
import onnx
from onnxconverter_common import float16
import onnxruntime as ort

BASE = Path(__file__).resolve().parent
PACKAGE = BASE / "package"
OUT = BASE / "out"
F32_ONNX = PACKAGE / "granite-5.0-turboctc-f32-ext.onnx"
FP16_ONNX = OUT / "granite-5.0-turboctc-fp16-ext.onnx"


def main():
    print(f"[{time.strftime('%H:%M:%S')}] carregando F32 ONNX...", flush=True)
    t0 = time.time()
    model = onnx.load(str(F32_ONNX), load_external_data=True)
    print(f"[{time.strftime('%H:%M:%S')}] carregado em {time.time()-t0:.1f}s", flush=True)

    # ESTRATÉGIA: keep_io_types=False → modelo FULLY FP16
    # O ONNX Runtime CPU EP carrega FP16 nativamente (ort 1.29+).
    # O QNN GPU EP também suporta FP16.
    print(f"[{time.strftime('%H:%M:%S')}] convertendo para FP16 (full)...", flush=True)
    t0 = time.time()
    model_fp16 = float16.convert_float_to_float16(model, keep_io_types=False)
    print(f"[{time.strftime('%H:%M:%S')}] convertido em {time.time()-t0:.1f}s", flush=True)

    OUT.mkdir(exist_ok=True)
    onnx.save_model(
        model_fp16,
        str(FP16_ONNX),
        save_as_external_data=True,
        all_tensors_to_one_file=True,
        location="granite-5.0-turboctc-fp16-ext.onnx.data",
        size_threshold=1024,
        convert_attribute=False,
    )
    data = FP16_ONNX.with_name("granite-5.0-turboctc-fp16-ext.onnx.data")
    print(f"[{time.strftime('%H:%M:%S')}] FP16 salvo: "
          f"{FP16_ONNX.stat().st_size/1e6:.1f}MB + {data.stat().st_size/1e9:.2f}GB", flush=True)

    # Valida que o modelo carrega com CPU EP
    print(f"[{time.strftime('%H:%M:%S')}] validando carga...", flush=True)
    try:
        sess = ort.InferenceSession(str(FP16_ONNX), providers=["CPUExecutionProvider"])
        feat = np.random.randn(1, 512, 320).astype(np.float16)
        mask = np.ones((1, 512), dtype=np.int64)
        out = sess.run(["logits"], {"input_features": feat, "attention_mask": mask})[0]
        print(f"[{time.strftime('%H:%M:%S')}] OK — logits shape {out.shape}, finito={np.isfinite(out).all()}",
              flush=True)
    except Exception as e:
        print(f"FALHA: {e}", flush=True)
        sys.exit(1)

    print("RESULTADO: OK", flush=True)


if __name__ == "__main__":
    main()