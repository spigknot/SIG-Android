# Ferramenta Granite (export ONNX do Granite Speech 5.0 TurboCTC)

Estes scripts exportam o modelo `ibm-granite/granite-speech-5.0-470m-turboctc`
(conformer CTC 470M, Apache 2.0, **somente inglês**) para ONNX F32 e validam o
pipeline. O app Android baixa o pacote do Cloudflare R2 e roda via ONNX Runtime.

## Ambiente

```powershell
python -m venv tools/granite/venv
tools/granite/venv\Scripts\Activate.ps1
pip install -r tools/granite/requirements.txt
pip install torch torchaudio --index-url https://download.pytorch.org/whl/cpu
pip install "git+https://github.com/huggingface/transformers.git"
```

> O modelo exige o `transformers` da branch main (arquitetura `granite_speech5_ctc`
> não está numa release estável ainda).

## Fluxo

```powershell
# 1. Baixa o modelo (946 MB safetensors) para tools/granite/models/
python -c "from huggingface_hub import snapshot_download; snapshot_download('ibm-granite/granite-speech-5.0-470m-turboctc', local_dir='tools/granite/models/granite-speech-5.0-470m-turboctc')"

# 2. Exporta ONNX F32 (embutido, ~1,9 GB) para tools/granite/out/
python tools/granite/export_onnx.py

# 3. Divide em external data (.onnx 0,9 MB + .onnx.data 1,89 GB) e valida com ORT
python tools/granite/split_external.py

# 4. Valida o pipeline completo (PyTorch vs ONNX) com um áudio de teste em inglês
python tools/granite/validate_pipeline.py
```

## Pacote para o R2

Os arquivos finais ficam em `tools/granite/package/` (copiados do `out/` após a
etapa 3): `granite-5.0-turboctc-f32-ext.onnx` + `.onnx.data` + os 6 arquivos de
apoio do Space da IBM (`frontend_config.json`, `mel_filters.bin`,
`stft_window.bin`, `vocab.json`, `pcs_vocab.json`, `punct_cap_seg_en.onnx`).
Subir os 8 na raiz do bucket `sig-android` (R2). SHAs em
`release/granite_links_download.txt`.

> `venv/`, `models/`, `out/` e `package/` são ignorados no Git (artefatos grandes).
> Não commitar pesos de modelo nem o pacote.
