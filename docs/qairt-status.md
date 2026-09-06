# QAIRT / QNN — Status no Device (OnePlus 15)

**Data:** 29/08/2026 — **Device:** OnePlus 15 (CPH2747), Snapdragon 8 Elite Gen 5 (SM8850), platform `canoe`, Android 16 (SDK 36)

## Fase 0 — Diagnóstico (concluído ✅)

| Teste | Resultado |
|---|---|
| `adb devices` | OK — 3B15BD00FVE00000 (CPH2747) |
| SoC | SM8850 / QTI / canoe / Android 16 / SDK 36 |
| GPU (Adreno) `qnn-platform-validator --backend gpu` | ✅ Hardware Supported, Libraries Found |
| DSP (HTP) `qnn-platform-validator --backend dsp` | ✅ Hardware Supported, Libraries Found |
| **Smoke test HTP sem root** (unit test calculator, v81) | ✅ **PASSED** — skel unsigned carrega via FastRPC sem root (risco R1 eliminado no nível do validator) |
| Libs nativas v3 (QNN EP) | ✅ ativas (`no_backup/native_dependencies/3-arm64-v8a`) |
| QAIRT v1 | ✅ instalado (`no_backup/qairt/v1`, 53 MB) |
| Modelo FP16 + F32 5.0 Turbo | ✅ completos em `granite_models/` (~4,9 GB) |

## Fase 6 — Validação GPU (RESULTADO FINAL: ❌ NÃO FUNCIONA no ORT 1.29 + QNN GPU)

### Bloqueio 1: StridedSlice rejeitado (RESOLVIDO ✅)
- Sintoma: `QNN.backendValidateOpConfig() failed ... StridedSlice ... error code 3110` → `Failed to finalize QNN graph (6020)`
- Causa: QNN GPU EP rejeita `StridedSlice` mesmo com params constantes e steps=1
- Solução: **convertido todos os 404 Slice → Gather** (`tools/granite/convert_slice_to_gather.py`)
  - 400 Gather + 21 Identity (no-ops), índices int32
  - Validação numérica: **diff EXATO 0.0** vs modelo original (int64 E int32)
  - Publicado no R2 (`sig-android`): `granite-5.0-turboctc-fp16-gather.onnx` (200 OK)
- Resultado: warnings 3110 **sumiram** do logcat

### Bloqueio 2: 6020 persiste na finalização (NÃO RESOLVIDO ❌)
- Mesmo sem StridedSlice, `FinalizeGraphs` falha com **6020** silenciosamente (sem warning de op)
- Testes feitos:
  - FP16-gather (int64) → 6020
  - FP16-gather (int32) → 6020
  - **F32-gather** (isolar FP16) → **6020 também** ❌
- Conclusão: o **QNN GPU EP do ORT 1.29 não consegue compilar o modelo Granite** (4000+ nodes, atenção multi-head). Não é FP16 — é tamanho/complexidade ou limitação do backend GPU.

## Fase 6 — Validação NPU (RESULTADO FINAL: ✅ FUNCIONA sem root)

### Causa raiz real e correção

O veredito anterior confundiu uma integração incompleta com bloqueio do Android.
O próprio OnePlus publica `libcdsprpc.so` e `libadsprpc.so` em
`/vendor/etc/public.libraries.txt`. Como o app usa `targetSdk 35`, o Android 12+
só coloca essas bibliotecas nativas públicas do fabricante no namespace do app
quando elas são declaradas por `<uses-native-library>`.

Correções aplicadas:

1. `AndroidManifest.xml`: `libcdsprpc.so` e `libadsprpc.so` declaradas com
   `android:required="false"` (o app continua instalável em não-Qualcomm).
2. `QairtDependencyManager`: `System.loadLibrary("cdsprpc")` executado antes do
   stub HTP, usando a biblioteca pública do vendor em vez de uma cópia privada.
3. `ADSP_LIBRARY_PATH`: trocado `System.setProperty` por
   `android.system.Os.setenv`; FastRPC lê a variável nativa via `getenv()`.
4. Mantido `System.load(caminho completo)` para as libs QAIRT baixadas no R2.

Copiar `/vendor/lib64/libcdsprpc.so` para o diretório privado era a abordagem
errada: a cópia passava a resolver `libhidlbase.so` no namespace do app e gerava
a falha que foi interpretada como dead end.

### Prova funcional no app normal

Executada no OnePlus 15, Android 16, app UID normal, sem root, após mover para
quarentena todas as cópias privadas de `libcdsprpc`, `libadsprpc`,
`libhidlbase`, `libhidltransport` e `libhwbinder`:

| Etapa | Resultado |
|---|---|
| FastRPC público | `load: OK libcdsprpc.so (vendor public native library)` |
| Stub/arquitetura | `HTP_STUB_OK arch=v81` |
| Runtime HTP | `HTP_LIBS_OK arch=v81` |
| Compilação do Granite FP16 Gather | `ONNX session criada (NPU (QNN HTP))` |
| Inferência real | `HTP_INFERENCE_PROGRESS 100` |
| Transcrição | `the quick brown fox jumps over the lazy dog welcome to the granite speech recognition test` |

A sessão levou aproximadamente 25 s para ser preparada; a inferência do áudio
curto terminou em menos de 1 s. O entrypoint reproduzível existe apenas no build
debug: `QairtSmokeTestActivity`.

## VALIDAÇÃO CORRIGIDA DO 4.1 NAR (29/08) — CPU ✅; GPU/NPU QNN ❌

A afirmação anterior de que o NAR funcionava nos três backends estava errada.
`GraniteNarEngine.load()` criava encoder, projector e LLM somente com
`SessionOptions` de CPU; a seleção GPU/NPU não chegava ao engine. A transcrição
estava correta, mas não comprovava aceleração.

O smoke test `GraniteNarSmokeTestActivity` passou então a recusar fallback CPU e
confirmar o backend efetivo. Resultado no OnePlus 15:

- NPU estrita: encoder rejeitado porque há nós atribuídos ao CPU EP.
- NPU híbrida: encoder QNN criado em 4,4 s; projector não terminou a preparação
  em mais de 2 min e o processo chegou a ~3,3 GB de heap nativo. Teste abortado.
- GPU estrita: encoder contém vários nós não suportados e `FinalizeGraphs`
  termina com erro 6022.
- CPU: caminho aprovado. `BASIC_OPT` reduziu a inferência do mesmo WAV de
  41,48 s para 36,41 s (-12,2%), preservando exatamente a transcrição.

GPU/NPU ficam desabilitadas na UI quando o modelo selecionado é o NAR. Uma rota
HTP real exige novos artefatos QDQ quantizados e formas estáticas (o LLM atual
tem comprimento `S` dinâmico), não apenas flags no app.

Fixes do NAR (OOM Java, heap limit 256 MB):
1. **mmap do `nar_embed_tokens.bin`** (411 MB): `readBytes()` → `RandomAccessFile.channel.map(READ_ONLY)` + `ByteBuffer` LITTLE_ENDIAN; `embedToken()` lê com `getShort()`. (OOM no load, linha 405.)
2. **`.get()` nos outputs nomeados do ORT**: `Result.get(String)` retorna `Optional<OnnxValue>`; o NAR usava `(encOut["x"] as OnnxTensor)` → ClassCastException. Corrigido nos 5 pontos (encoder/projector/LLM).
3. **CTC collapse direto do FloatBuffer**: `bpeLogits` do encoder é `[~500, 100352]` ≈ **200 MB**; `FloatArray(...)` estourava o heap. `GraniteNarCtc.collapseLogits(FloatBuffer,...)` lê direto, sem copiar. ⚠️ collapse ANTES do `close()` do tensor (buffer inválido depois).
4. **`android:largeHeap="true"` no `<application>`** (estava só na activity): heap Java sobe para 512 MB — foi o que destravou o NAR (precisava de ~230 MB+ no pico com as cópias restantes).

O engine agora também registra tempos separados de frontend, encoder, CTC,
projector, embeddings e LLM, e evita as cópias integrais dos logits finais.

## LIÇÕES APRENDIDAS (vacinas)
1. **QNN GPU do ORT 1.29**: não confiar para modelos grandes de atenção (testar com modelo pequeno antes)
2. **Libs públicas do vendor em targetSdk 31+**: declarar com `<uses-native-library>`; copiar libs de `/vendor` para o app quebra a resolução transitiva e não substitui a declaração
3. **Variáveis para código nativo**: `System.setProperty` não altera `getenv()`; usar `Os.setenv` para `ADSP_LIBRARY_PATH`
4. **Bucket R2 do SIG Android é `sig-android`** (não `sig` do Windows) — o `r2_config.json` do repo agora tem bucket/public_base completos (UPDATE.md linha 18)

## ESTADO ATUAL DO CÓDIGO (29/08 14:15)
- `GraniteEngine.kt`: revertido ao estado limpo (GPU/NPU usam FP16-gather; sem logs temporários)
- `QairtDependencyManager.kt`: FastRPC público + `htpArchitecture(context)` + `Os.setenv(ADSP_LIBRARY_PATH)` validados no app
- `AndroidManifest.xml`: `libcdsprpc.so`/`libadsprpc.so` opcionais
- Build debug: `QairtSmokeTestActivity` permite repetir carga, sessão e inferência via adb
- Gates verdes: testDebugUnitTest + lintDebug + assembleDebug ✅
- Modelos: FP16-gather publicado no R2; F32-gather existe local (diagnóstico, não publicado)
- APK em `O:` NÃO re-publicado ainda (aguarda decisão do usuário)

## PRÓXIMOS PASSOS POSSÍVEIS (decisão do usuário)
1. Manter NPU (QNN HTP) habilitado: sessão e inferência reais estão aprovadas sem root
2. Para GPU, testar um QNN EP mais novo ou particionar o grafo em subgrafos menores; o erro 6020 permanece específico do backend GPU atual
3. Manter o fallback GPU→CPU com confirmação e registrar no relatório o backend efetivamente usado
