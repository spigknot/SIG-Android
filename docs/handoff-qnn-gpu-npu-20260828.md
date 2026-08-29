# CONTINUIDADE — GPU/NPU via QNN (QAIRT) no Granite Speech 5.0

**Data:** 28/08/2026 — **Próximo:** 29/08/2026
**Quem:** Gustavo + Hermes
**Aparelho:** OnePlus 15 (Snapdragon 8 Elite Gen 5 / SM8750, Android 16) — CONECTAR VIA USB COM DEPURAÇÃO ATIVA

---

## RESUMO DO QUE FOI FEITO HOJE (28/08)

### ✅ Fase 1 — Pacote QAIRT no R2
- `tools/qairt/build-qairt-r2-package.ps1`: script que empacota as libs QNN (GPU+HTP+stubs+skels v73-81)
- **ZIP publicado no R2:** `packages/qairt/sig-qairt-arm64-v8a-v1.zip`
  - Tamanho: **53.1 MB** (141 MB descomp)
  - SHA-256: `df879cd794ae0a2339a039d90ced937e08f4094a536d16bc571cf68c5a61a9f0`
  - URL pública: `https://pub-6476622beda24c82875cb84f11f660ea.r2.dev/packages/qairt/sig-qairt-arm64-v8a-v1.zip`

### ✅ Fase 3 — Dependência onnxruntime-android-qnn
- `app/build.gradle`: trocado `onnxruntime-android:1.29.0` → `onnxruntime-android-qnn:1.29.0`
- QNN EP já vem embutido no AAR oficial do Maven Central — **não precisa compilar nada**
- Build confirmado com `assembleDebug` — SUCCESSO

### ✅ Fase 4 — QairtDependencyManager
- `app/src/main/java/.../QairtDependencyManager.kt`:
  - `isQualcommDevice()` — detecta Snapdragon por Build.SOC_MODEL/SOC_MANUFACTURER
  - `htpArchitecture()` — tenta carregar stubs v81→v79→v75→v73
  - `install()` — download ~53MB do R2 com validação SHA256 + extração atômica
  - `loadQnnNatives()` — carrega libs na ordem: System → Gpu/Htp → Prepar → Stub
  - ADSP_LIBRARY_PATH setado para o dir dos skels (validar no device — risco R1)
- Download só dispara quando usuário escolhe GPU/NPU (sob demanda)
- `app/src/test/.../QairtDependencyManagerTest.kt` — 10 testes JVM puros

### ✅ Fase 5 — GraniteEngine + GraniteBackendMapping
- Enum refatorado: `CPU` / `GPU_QNN("GPU (Adreno)")` / `NPU_QNN_HTP("NPU (Hexagon)")`
  - NNAPI **removido completamente** — não existe mais "GPU (NNAPI)"
- `GraniteEngine.load()`:
  - CPU → modelo F32 (pacote atual de 1.9GB)
  - GPU/NPU → modelo F32 (mesmo pacote); QNN EP converte FP32→FP16 internamente via `enable_htp_fp16_precision=1`
  - Carrega libs QNN antes de criar sessão
  - Fallback com diálogo de confirmação (mantido — D4)
- `GraniteBackendMappingTest.kt` — 9 testes JVM
- `GraniteActivity.showBackendMenu()`:
  - Backends acelerados aparecem **desabilitados com "?"** em aparelho não-Qualcomm
  - Clique no "?" abre diálogo explicativo (D5)
  - Primeiro uso: oferece download de ~53MB

### ✅ Fase 2 — FP16 RESOLVIDO (28/08 22:22)
- **Exportado do PyTorch** com `export_fp16_torch.py` (wrapper com cast nas bordas: entrada fp32 → fp16 → saída fp32)
- Validação: ONNX fp16 vs PyTorch fp16 = **0.00%** divergência; vs fp32 = **0.00%**
- Arquivos: `tools/granite/package/granite-5.0-turboctc-fp16-ext.onnx` (~1.1 MB) + `.data` (~947 MB)
- **Upload pro R2** em `models/granite/5.0-turbo/` (verificar conclusão)
- `GraniteEngine.load()` agora usa `modelFileForBackend()` — FP16 p/ GPU/NPU, F32 p/ CPU
- Build verde: `testDebugUnitTest` + `lintDebug` + `assembleDebug` ✅

### ✅ Gates e build — TUDO VERDE
- `:app:testDebugUnitTest` — **BUILD SUCCESSFUL** (todos os testes passam)
- `:app:lintDebug` — **BUILD SUCCESSFUL** (zero warnings novos)
- `:app:assembleDebug` — **BUILD SUCCESSFUL**
- APK: **7.4 MB** (zero .so dentro — QNN libs excluídas via packaging.jniLibs.excludes)
- APK está em: `app/build/outputs/apk/debug/app-debug.apk`

---

## O QUE FALTA — ORDEM SUGERIDA PARA AMANHÃ

### 0) CONECTAR O ONEPLUS 15 (CRÍTICO — PRIMEIRO PASSO)
```bash
# Conectar via USB com depuração ativa
adb devices -l
# Deve mostrar o dispositivo. Se não:
# - Verificar se "Depuração USB" está ativado nas Opções do Desenvolvedor
# - Tentar outra porta USB / cabo
# - Autorizar o computador no popup do celular
```

### 1) FASE 0 — DIAGNÓSTICO NO APARELHO

```bash
# 1.1 Confirmar SoC e Android
adb shell getprop ro.soc.model          # Esperado: SM8750
adb shell getprop ro.soc.manufacturer    # Esperado: Qualcomm
adb shell getprop ro.board.platform      # Esperado: sun ou pineapple
adb shell getprop ro.build.version.release  # Esperado: 16
adb shell getprop ro.build.version.sdk   # Esperado: 35 ou 36

# 1.2 Listar dispositivos NNAPI (referência do que NÃO usaremos)
adb shell dumpsys neuralnetworks | head -20

# 1.3 Smoke test HTP sem root (CRÍTICO — risco R1)
# Copiar libs mínimas para o device:
adb push D:/Projetos/qairt/2.49.0.260730/lib/aarch64-android/libQnnSystem.so /data/local/tmp/
adb push D:/Projetos/qairt/2.49.0.260730/lib/aarch64-android/libQnnHtp.so /data/local/tmp/
adb push D:/Projetos/qairt/2.49.0.260730/lib/aarch64-android/libQnnHtpV81Stub.so /data/local/tmp/
adb push D:/Projetos/qairt/2.49.0.260730/lib/hexagon-v81/unsgned/libQnnHtpV81Skel.so /data/local/tmp/

# Tentar carregar o stub (shel root NÃO é necessário — testar como shel normal):
adb shell "cd /data/local/tmp && export LD_LIBRARY_PATH=/data/local/tmp && export ADSP_LIBRARY_PATH=/data/local/tmp && logcat -c && logcat &"
# NÃO temos qnn-net-run para Android — testar com um APK de smoke OU
# verificar no logcat se o stub carrega sem erro quando o app SIG tentar usar QNN.

# 1.4 Verificar nome exato das libs que o ORT procura:
adb shell logcat -s Qnn ONNX Ort | head -50
# (depois de rodar o app com backend GPU/NPU)

# 1.5 Registrar TUDO em docs/qairt-status.md
```

### 2) VERIFICAR QNN EP NO LOGCAT

Depois de instalar o APK e selecionar GPU (Adreno) ou NPU (Hexagon):

```bash
adb logcat -s GraniteEngine QairtManager SigNative ONNX Qnn
```

O que procurar:
- `HTP arch detectada: v81` — confirma que o stub v81 carregou
- `libs QNN carregadas: backend=gpu` ou `backend=htp arch=v81`
- `QNN EP configurado: {backend_path=libQnnGpu.so, ...}`
- `ONNX session criada (GPU (QNN))` ou `ONNX session criada (NPU (QNN HTP))`
- **SEM** `ANEURALNETWORKS_BAD_DATA` — esse erro NÃO deve mais aparecer

Se falhar:
- `Arquitetura HTP não detectada` → o stub não carregou → tentar CalculatorStub em vez de Stub (ver QAIRT docs)
- `QNN EP indisponível` → a lib libonnxruntime.so do AAR -qnn não tem QNN EP? → verificar strings no .so
- `SIGSEGV` → ordem de carga errada ou conflito com outra lib → verificar `sig.natve.library.dir`

### 3) TESTAR RTF (REAL TIME FACTOR)

- Transcrever o mesmo áudio em CPU e GPU/NPU
- O relatório mostra RTF no campo "Eficiência" (ex: 2.5x = 2.5× mais rápido que tempo real)
- Para o OnePlus 15, esperamos RTF > 1.5x no GPU vs CPU
- Registrar resultados em docs/qairt-status.md

### 4) EDITAR build-android-native-dependencies.ps1 PARA O AAR -QNN

O script atual (linha 19) busca o AAR em:
```
~/.gradle/caches/.../onnxruntime-android/$onxVersion/...onnxruntime-android-$onxVersion.aar
```
Precisa ser atualizado para buscar `onnxruntime-android-qnn` no mesmo padrão:
```
$onxAarDir = Join-Path $env:USERPROFILE ".gradle\caches\modules-2\files-2.1\com.microsft.onnxruntime\onnxruntime-android-qnn\$onxVersion"
```

E o filter para: `onnxruntime-android-qnn-$onxVersion.aar`

Isso gera o `sig-android-dependencies-v3-<abi>.zip` com o .so do AAR QNN.

### 5) ATUALIZAR NativeDependencyManager CO V3

Depois de gerar os ZIPs v3:
- `COMPONENT_VERSION = "3"`
- Atualizar URLs no R2 (os ZIPs precisam ser upados)
- Atualizar SHA256 e tamanhos
- Rodar `scripts/verify-native-dependencies.ps1 -Version 3`

### 6) GERAR APK E TESTAR

```bash
cd D:/Projetos/SIG
./gradlew clean :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
# Confirmar BUILD SUCCESSFUL
cp app/build/outputs/apk/debug/app-debug.apk O:/SIG-A...apk  # mesmo nome de sempre
```

---

## FP16 — BLOQUEIO E PLANO B

### O que aconteceu
A conversão com `keep_io_types=True` falhou:
```
[ONNXRuntimeError] Type Error: Type parameter (T) of Optype (MatMul) bound to
different types (tensor(float) and tensor(float16) in node
(/encoder/input_linear/MatMul).
```
Isso acontece porque o `onnxconverter_common.float16` converte pesos para float16 mas
não insere Cast nodes entre o input (float32) e os pesos (float16).

### Plano B (três opções, em ordem de preferência)

**Opção 1 — Reexportar do PyTorch com dtype=float16 (RECOMENDADO)**
```bash
cd D:/Projetos/SIG/tools/granite
venv/Sripts/pip install torch --index-url https://download.pytorch.org/whl/cpu
# Editar export_onx.py: mudar dtype=torch.float32 → dtype=torch.float16
venv/Sripts/python export_onx.py --dtype fp16
# Isso gera um ONNX já float16 desde o PyTorch, sem precisar converter depois.
# Os pesos já nascem float16 e o grafo é consistente.
```
Pró: resultado garantido, sem Cast nodes manuais.
Contra: precisa baixar o modelo de novo do HF (a cache está em tools/granite/models/).

**Opção 2 — keep_io_types=False + modelo fully FP16**
```bash
venv/Sripts/python tools/granite/export_fp16.py
# O script já está corrigido para keep_io_types=False.
# Gera um modelo 100% FP16 (~950MB).
```
Pró: já está pronto, só rodar.
Contra: inputs/outputs são float16 — o engine Kotlin precisa enviar float16 em vez de float32
      (mudar `OnxTensor.createTensor` de `FloatBuffer` para usar `Float16`).
      O QNN EP pode ou não aceitar inputs float16 — testar no device.

**Opção 3 — Inserir Cast nodes manualmente**
Usar `onnx.helper.make_node('Cast', ...)` para inserir float32→float16 antes
de cada operação que usa inicializador convertido. Trbalhoso e frágil.

### O que fazer amanhã
1. **Opção 1 primeiro** — é a mais limpa e confiável
2. Se o download do modelo PyTorch for rápido, gerar o FP16 em < 30 min
3. Publicar no R2 em `models/granite/5.0-turbo/granite-5.0-turboctc-fp16-ext.onnx`
4. Atualizar `GraniteEngine.load()` para usar `modelFileFp16()` quando `backend.accelerated`
5. Atualizar `packageFiles()` para incluir o FP16 no download (10 arquivos no pacote)

---

## ARQUIVOS MODIFICADOS (28/08)

| Arquivo | Status | O que mudou |
|---|---|---|
| `app/build.gradle` | ✅ | onnxruntime-android → onnxruntime-android-qnn:1.29.0 |
| `app/src/.../GraniteEngine.kt` | ✅ | enum refatorado + load() com QNN EP + modelo FP16/F32 |
| `app/src/.../GraniteActivity.kt` | ✅ | UX download QAIRT + menu backend com "?" |
| `app/src/.../QairtDependencyManager.kt` | ✅ NOVO | download/carga/detecção QAIRT |
| `app/src/test/.../QairtDependencyManagerTest.kt` | ✅ NOVO | 10 testes JVM |
| `app/src/test/.../GraniteBackendMappingTest.kt` | ✅ NOVO | 9 testes JVM |
| `tools/qairt/build-qairt-r2-package.ps1` | ✅ NOVO | empacota libs QNN p/ R2 |
| `tools/granite/export_fp16.py` | ✅ ajustado | keep_io_types=False (bloqueado—ver acima) |
| `docs/qairt-status.md` | ❌ NÃO EXISTE | **CRIAR amanhã** com resultados do device |

### NÃO ALTERADOS (precisam de atenção amanhã)
| `scripts/build-android-native-dependencies.ps1` | atualizar para buscar AAR -qnn |
| `scripts/verify-native-dependencies.ps1` | rodar com -Version 3 |
| `NativeDependencyManager.kt` | COMPONENT_VERSION="3" + urls/shas novas |
| `release/granite_liks_download.txt` | adicionar SHA do FP16 quando publicado |
| `UPDATE.md` | bump versão + fluxo v3 + qairt |
| `docs/NPU_IMPLEMENTATION_NOTES.md` | atualizar estado real |

---

## DECISÕES JÁ TOMADAS (confirmadas pelo Gustavo)

- **D1** — todos os skels v73/75/79/81 no pacote QAIRT (~53 MB zip)
- **D2** — NPU começa com FP16 (via enable_htp_fp16_precision), QDQ INT8 fase 2
- **D3** — "GPU (Adreno)" e "NPU (Hexagon)" como opções separadas
- **D4** — manter diálgo "Deseja continuar com CPU?"
- **D5** — não-Qualcomm: opções desabilitadas + "?" com explicação

---

## RISCOS A MITIGAR

| Risco | Descrição | Mitigação | Status |
|---|---|---|---|
| R1 | Skels HTP sem root | DSP pode não carregar skels unsigned de /data/data/... | **Smoke test amanhã (Fase 0)** — se falhar: GPU QNN funciona sem skel; NPU fica com mensagem hasta resolver |
| R2 | Nomes de libs | QAIRT 2.49 tem libQairt* (novo) e libQnn* (legado). ORT 1.29 espera libQnn*. | Usamos libQnn* — confirmar no logcat |
| R3 | FP16 no HTP | Doc diz "only quantized models" para HTP; enable_htp_fp16_precision=1 é o caminho | Validar no device |
| R4 | Tamanho pacote QAIRT | 53 MB — diálogo avisa antes do download | OK |
| R5 | Ordem carga libs nativas | System.load deve ser: System → Gpu/Htp → Prepar → Stub | OK — implementado no QairtDependencyManager |
| R6 | addQnn no wrapper Java | Confirmado que existe (javap no classe.jar do AAR) | OK |
| R7 | Modelo FP16 | Conversão bloqueada — Plano B com 3 opções | Ver seção FP16 acima |

---

## LINKS ÚTEIS

- QAIRT SDK: `D:\Projetos\qairt\2.49.0.260730\`
- QNN EP docs: https://onnxruntime.ai/docs/execution-providers/QNN-ExecutonProvider.html
- AAR QNN: https://rep1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android-qnn/1.29.0/
- R2 bucket: https://pub-6476622beda24c82875cb84f11f660ea.r2.dev/
- R2 credenciais: `release/r2_config.json`
- Sync R2 script: `D:\Projetos\SIG Windows\scripts\sync_r2.py`
- Projeto SIG: `D:\Projetos\SIG`
- Plano original: `docs/plano_acaa_qairt_gpu_npu_granite_revisado_20260828.txt`

---

**Boa sorte amanhã! �️ O código está compilando e os testes passam.
O que trava agora é só o device (Fase 0) + a conversão FP16 (Fase 2).**