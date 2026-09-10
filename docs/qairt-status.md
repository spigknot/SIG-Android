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

---

# Diagnóstico dos bloqueadores de NPU do NAR (10/09/2026)

Análise feita **fora do aparelho**, contra a documentação oficial de ops do QNN EP
(`docs.onnxruntime.ai/execution-providers/QNN-ExecutionProvider.html`, mainline — a mesma
família do `onnxruntime-android 1.29.0` que o app usa) e o fork
`github.com/onnxruntime/onnxruntime-qnn`. Nada aqui é `npu-approved`: são hipóteses com
artefato pronto para teste, não resultados de device.

## 1. `Einsum` era o bloqueador real da NPU estrita (confirmado na documentação)

A distribuição de ops explica a rejeição "encoder rejeitado porque há nós atribuídos ao CPU
EP". O encoder conformer tem **16 nós `Einsum`**, todos na atenção relativa, com a equação

```
b m h c d , c r d -> b m h c r
```

e shapes reais q `[1,10,8,200,128]` / P `[200,200,128]`.

| op | ORT **mainline** (o app) | fork da Qualcomm |
|---|---|---|
| `Einsum` | ⛔ **ausente** | ✅ presente |
| `Mod` | ⛔ **ausente** | ✅ presente |

Como o app usa mainline, cada `Einsum` vira **nó de CPU EP** — exatamente a mensagem de
rejeição da NPU estrita.

**Correção implementada:** `tools/granite/nar/einsum_to_matmul.py` reescreve a identidade

```
out[m,h,c,r] = sum_d q[m,h,c,d] * P[c,r,d]
```

como `Split + Reshape + Transpose + MatMul + Concat` (todos cobertos pelo HTP), explorando o
broadcasting do `MatMul`. Medido no encoder de produção: **16/16 convertidos, 0 `Einsum`
restantes**, `onnx.checker` OK, contrato de I/O idêntico (`encoder_bpe_logits
[1,500,100352]`, `multilayer_features [1,2000,4096]`), custo de tempo neutro (174,6 s →
176,1 s). O caminho `Mul` + `ReduceSum` foi descartado: geraria intermediário de 410 M
elementos (~820 MB em fp16) por camada.

## 2. O projector NÃO tem bloqueador de op — os dois suspeitos eram falsos

Os suspeitos eram `Erf` e `Mod`, mas:

- **`Mod`** é `/projector/Mod` = `2000 % 15` sobre **dois Constants** → `Sub` → `Unsqueeze` →
  `Concat` → shape de um `Reshape`. É cálculo de forma, não aritmética.
- **`Erf`** casa exatamente o padrão de fusão documentado
  `[Div/Mul(√2)] → Erf → [Mul(0.5) →] Add(1) → Mul → [Mul(0.5)]` → `QNN_OP_GELU`
  (medido no grafo: `Div(x, 1.4140625)` → `Erf` → `Add(1.0)` → `Mul` → `Mul(0.5)`).
  Não existe `Erf` isolado em nenhuma das duas listas, o que explica a suspeita inicial.

Portanto o travamento do projector na preparação (>2 min, ~3,3 GB de heap nativo) **não é
rejeição de op** — é outra classe de problema.

## 3. Achado novo e importante: `OptLevel.NO_OPT` desliga o constant folding

`GraniteNarEngine.kt` cria as sessões aceleradas com `NO_OPT` (a intenção é boa: não criar
padrões que o backend não reconheça). O efeito colateral é que **o constant folding não roda**:
as cadeias de cálculo de forma chegam ao particionador do QNN como nós de verdade. Medido:

| grafo | `Constant` | `Shape` | `Mod` |
|---|---|---|---|
| encoder (produção) | **690** | 49 | 0 |
| encoder (convertido) | 690 | 49 | 0 |
| projector | 45 | 3 | **1** |

**Solução:** pré-dobrar o grafo offline e servir o artefato já dobrado
(`tools/granite/nar/fold_constant_subgraphs.py`, usa `optimized_model_filepath` do ORT). O app
continua em `NO_OPT` — não precisa otimizar em runtime porque o grafo já vem pronto. Medido no
projector: `Mod` → 0, `Shape` → 0, `Constant` → 0; no encoder: `Constant` 690 → 0.

⚠️ **Ressalva:** o grafo otimizado passa a declarar shapes **concretas** em vez de simbólicas.
Isso é desejável para o QNN (compilação estática), mas o artefato vale apenas para a forma
declarada — registre sempre com qual `T` ele foi gerado.

## 4. Quantização: 4-bit weight-only preserva a qualidade (medido em CPU)

A linha de quantização estava fechada com quatro paradigmas reprovados. Duas correções de
medição mudaram o quadro:

1. **Igualdade exata de texto é métrica inadequada** — é binária e reprova a amostra por um
   caractere. Medido, com artefato ONNX real, corpus de 82 amostras:

   | modelo | igualdade exata vs float | **CER vs FLEURS** |
   |---|---|---|
   | float | — | 0,0323 |
   | **4-bit `MatMulNBits` RTN blk128** | 41/82 (50%) | **0,0324** |

   Delta **+0,0001**. Análise pareada: 14 amostras melhoram, 48 empatam, 20 pioram.

2. **A referência não pode ser a saída do próprio modelo.** Medido em 5 amostras: `ctc_ids`
   idênticos 5/5, LLM fp16 == fp32 5/5, pipeline fp16 == fp32 4/5 — e **fp32 puro comete os
   mesmos erros** em palavras raras (`wifi door bell` → `wi doorbell`, `inland waterways` →
   `land`). Comparar contra a saída do modelo pune a quantização por erros que o float já tem.

**Método:** `MatMulNBitsQuantizer` com `bits=4`, `block_size=128`, simétrico, **sem
calibração. Ganho: **4,1× menor** (803 MB vs 3,26 GB) e **4,5× mais rápido**. A calibração
*piorou* o resultado: GPTQ deu CER 0,1562 contra 0,0151 do RTN puro, com corrupção de prefixo
que o RTN não apresenta.

⚠️ **Armadilha medida:** `MatMulNBitsQuantizer.__init__` só usa o parâmetro `bits` quando
`algo_config is None`. Passar `algo_config=RTNWeightOnlyQuantConfig()` (que **não tem** campo
`bits`) faz o quantizador cair no default de 4 bits e o pedido ser **silenciosamente
ignorado** — uma rodada rotulada "8 bits" saiu byte-idêntica à de 4 bits. Sempre verifique o
atributo `bits` dos nós `MatMulNBits` no grafo salvo e o tamanho do `.data`.

## 5. Próximo passo concreto

Testar no aparelho se a NPU estrita aceita o encoder sem os `Einsum` (e, em paralelo, se a
pré-dobra ajuda). O entrypoint existe e não exige mudança no engine:
`GraniteNarSmokeTestActivity` (em `app/src/debug`) aceita `backend`,
`require_full_acceleration`, `audio_path` por intent e **recusa fallback silencioso**
(`session.disable_cpu_ep_fallback=1`), então "efetivo = NPU" é prova real.

