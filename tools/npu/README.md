# Ferramentas de pacotes NPU

Estes scripts preparam a infraestrutura de desenvolvimento. Eles não tornam o HTP funcional sem o QAIRT SDK autorizado, um alvo Snapdragon explícito e validação no aparelho.

## Ambiente

```powershell
python -m venv .venv
.venv\Scripts\Activate.ps1
pip install -r tools/npu/requirements.txt
$env:QNN_SDK_ROOT = "C:\Qualcomm\QAIRT\<versao>"
```

## Fluxo

```powershell
python tools/npu/export_whisper_encoder.py --model tiny --output build/npu/tiny/encoder.onnx
python tools/npu/compile_qnn_encoder.py --onnx build/npu/tiny/encoder.onnx --output build/npu/tiny/qnn --target aarch64-android
python tools/npu/build_model_package.py --model tiny --artifacts build/npu/tiny/qnn --output build/npu/whisper-tiny-qnn.zip
python tools/npu/verify_package.py build/npu/whisper-tiny-qnn.zip
```

Antes de instalar no app, informe no `package.json` a versão real do QAIRT, SoC/HTP compatíveis, layout e dtype efetivamente produzidos. O primeiro protótipo deve ser o Tiny.

Os binários QAIRT/QNN e artefatos compilados não devem ser commitados sem autorização de redistribuição.
