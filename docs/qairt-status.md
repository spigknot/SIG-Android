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

## Fase 6 — Validação NPU (RESULTADO FINAL: ❌ BLOQUEADO pelo Android 16)

### Cadeia de bugs encontrados e corrigidos:
1. **`htpArchitecture()` usava `System.loadLibrary`** (procura no dir nativo do APK) em vez de `System.load(caminho)` do dir QAIRT → "library not found"
   - ✅ Corrigido (agora usa `System.load` com caminho completo + context)
2. **`libcdsprpc.so` não encontrado** (FastRPC do DSP): o stub HTP precisa dela, mas o app não vê `/vendor/lib64`
   - Copiada manualmente para o dir QAIRT → resolveu o erro
3. **`libhidlbase.so` não encontrado** (dependência do libcdsprpc): copiada + `registerNativeLibraryDir` → **AINDA falha**

### Veredito NPU: **namespace isolation do Android 16 bloqueia** ❌
- O app (`namespace clns-9`) **não pode carregar libs do vendor** (`/vendor/lib64`) mesmo copiadas para o app
- `libcdsprpc.so` (do vendor) depende de `libhidlbase.so` etc. (também vendor) que o namespace não expõe
- **Risco R1 do plano CONFIRMADO**: HTP em app sem root não carrega no Android 16
- O `qnn-platform-validator` funcionava porque roda como **root/shell** (sem namespace isolation)

## LIÇÕES APRENDIDAS (vacinas)
1. **QNN GPU do ORT 1.29**: não confiar para modelos grandes de atenção (testar com modelo pequeno antes)
2. **HTP em app sem root no Android 11+**: bloqueado por namespace isolation (libs vendor). O validator funciona mas o app NÃO — não prometer NPU sem root
3. **`System.load(caminho)` não adiciona o dir ao path de dependências** — usar `registerNativeLibraryDir` (NativeDependencyManager) para libs com dependências
4. **Bucket R2 do SIG Android é `sig-android`** (não `sig` do Windows) — o `r2_config.json` do repo agora tem bucket/public_base completos (UPDATE.md linha 18)

## ESTADO ATUAL DO CÓDIGO (29/08 14:15)
- `GraniteEngine.kt`: revertido ao estado limpo (GPU/NPU usam FP16-gather; sem logs temporários)
- `QairtDependencyManager.kt`: `htpArchitecture(context)` corrigido (System.load com caminho) + `registerNativeLibraryDir` no loadQnnNatives (KEEP — correto mesmo que NPU esteja bloqueado no device)
- Gates verdes: testDebugUnitTest + lintDebug + assembleDebug ✅
- Modelos: FP16-gather publicado no R2; F32-gather existe local (diagnóstico, não publicado)
- APK em `O:` NÃO re-publicado ainda (aguarda decisão do usuário)

## PRÓXIMOS PASSOS POSSÍVEIS (decisão do usuário)
1. **Aceitar CPU-only** para o Granite 5.0 (remover/desabilitar GPU/NPU com mensagem honesta)
2. **Investigar alternativa**: NNAPI (deprecado), GPU via outra rota (ex.: QNN CPU backend — não acelera), ou ORT mais novo
3. **Root no OnePlus 15** (destrava HTP) — decisão do usuário
4. Manter GPU/NPU como estão (fallback honesto com diálogo) — código já faz isso
