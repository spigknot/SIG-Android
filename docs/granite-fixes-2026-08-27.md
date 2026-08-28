# Granite (SIG Android) — Plano de fix (27/ago/2026)

> Estado: **diagnóstico pronto, fix ainda NÃO aplicado**. Aguardando aprovação do
> usuário antes de codar (regra "design-first" + "vacina em código+testes").
> Build atual: `O:\SIG-Android-Granite-20260826_003-fix8.apk` (8,83 MB).

## TL;DR (1 minuto)

| Sintoma | Causa raiz | Severidade | Fix |
|---|---|---|---|
| Áudio EN curto (71 KB) → "transcrição vazia" (100% processado, sem erro) | `attention_mask` **invertida**: engine passa 0=real/1=pad, mas o grafo (transformers HF) espera 1=real/0=pad. Conformer trata os frames válidos como padding → logits degenerados → argmax sempre blank → `decode([])` → "" | **Alta** (bloqueante da v1) | Trocar `mask.fill(0L, 0, windowLen)` por `mask.fill(1L, 0, windowLen)` em `GraniteEngine.transcribeFileInner` (linha 663) + **vacina de teste JVM** |
| Áudio 19 MB (PT) → OOM `Failed to allocate a 81709072 byte allocation` com footprint 256 MB | (a) Máscara invertida (acima) **amplifica**: o argmax degenerado faz o decoder produzir `outFrames/4` ids todos blank, e cada janela já é 16384 floats × 128 frames — se algum caminho copia esses buffers em heap Java em vez de usar DirectByteBuffer, dispara OOM no segundo loop. (b) `largeHeap="true"` pode estar competindo com a ORT se algum `OrtIoBinding`/arena for alocado dentro do heap do app em vez de off-heap. | **Média** (não-bloqueante com o fix acima, mas precisa verificar pós-fix) | (1) Confirmar que o fix da máscara elimina o OOM (teste rápido). (2) Se persistir: usar `OrtEnvironment` com arena off-heap explícito (`setUseArena(true)` é default, mas criar a sessão com `SessionOptions` sem `addConfigEntry("session.use_env_allocators","1")` é o que já está). (3) OOM killer do OnePlus 15 costuma aparecer com logits muito grandes em F32; FP16 resolve. |
| `audio_16` (PT, EN-only) → "transcrição vazia" | **Esperado**: modelo é EN-only. Texto sai zerado/errado em PT. | OK (limitação documentada) | Sem fix; manter mensagem "modelo em inglês" na UI. |

A causa nº 1 sozinha explica o sintoma "transcrição vazia". O OOM quase
certamente desaparece junto (porque o caminho que estoura está em torno do
loop de janelas + máscara). Se persistir, ver §4.

---

## 1. Diagnóstico completo da "transcrição vazia"

### 1.1 O que o usuário viu (screenshot)

- `Granite 5.0 Turbo` + `CPU` selecionados.
- Processamento chegou a 100%, status "Concluído", mas mensagem de erro
  `"transcrição vazia em 1787874093119_Meeting recording 11.wav"`.
- Log/terminal (não mostrado na imagem) só confirma que o `transcribeFile`
  retornou string vazia, e a `GraniteActivity.transcribeSelectedMedia`
  (linha 521) lança `IllegalStateException("transcrição vazia em ...")` quando
  `text.isBlank()`.

### 1.2 Rastreamento do código

**Arquivo**: `app/src/main/java/br/gov/sp/pcsp/launcher/GraniteEngine.kt`

```kotlin
// linhas 661-670 — transcribeFileInner
// mask: 1 = padding (frames além do real dentro da janela); ONNX espera int64.
val mask = LongArray(windowFrames) { 1L }   //  ← inicializa TUDO como 1 (= padding)
mask.fill(0L, 0, windowLen)                 //  ← preenche [0, windowLen) com 0

val inputTensor = OnnxTensor.createTensor(env(), java.nio.FloatBuffer.wrap(input), longArrayOf(1L, windowFrames.toLong(), features.dim.toLong()))
val maskTensor = OnnxTensor.createTensor(env(), java.nio.LongBuffer.wrap(mask), longArrayOf(1L, windowFrames.toLong()))
```

**Semântica resultante** (mask enviada à ORT):
- frames reais `[0, windowLen)`: `mask = 0`
- frames de padding `[windowLen, windowFrames)`: `mask = 1`

### 1.3 Convenção esperada pelo grafo

Fonte: `transformers/src/transformers/models/granite_speech5/modeling_granite_speech5.py`
(commit do Space `granite-speech-streaming-webgpu`):

- Linha 50-55 (docstring do `attention_mask`):
  > "1 for tokens that are **not masked**, 0 for tokens that are masked."
- Linha 88 (eager_attention_forward):
  > `attn_weights = attn_weights + attention_mask` (máscara aditiva 4D)
- Linha 629 (forward do `GraniteSpeech5ForCTC`):
  > `sequences[~attention_mask] = self.config.pad_token_id` (mask=0 → pad)

**Convenção HF transformers (padrão em TODOS os conformer/bert/wav2vec2)**: `1=real, 0=pad`.

### 1.4 Por que o pipeline PC passa (e o Android falha)

| Script PC | Máscara enviada | Resultado |
|---|---|---|
| `tools/granite/export_onnx.py` linha 35 | `mask = torch.ones(1, T)` (1=real) | PyTorch forward → logits |
| `tools/granite/validate_pipeline.py` linha 31-33 | `mask = torch.ones(1, T)` + pad com zeros (0=pad) | ORT logits == PyTorch logits |
| `tools/granite/split_external.py` linha 26 | `mask = np.zeros((1, 512))` (TUDO 0=pad) | Logits finitos, mas o teste é só de sanidade — não valida acurácia, só que a ORT não estoura |

**Os scripts PC validam a ORT contra o PyTorch apenas com a máscara correta**. Nenhum
teste PC enviou a máscara invertida. O `split_external.py` aceita mask=zeros porque o
conformer, com tudo marcado como padding, simplesmente mascara todos os frames — logits
resultam finitos mas inúteis. Isso não é evidência de que a convenção é "mask=0=real".

### 1.5 Por que a transcrição sai vazia (mecanismo)

1. `mask[0..windowLen) = 0` → conformer trata esses frames como **padding**.
2. Atenção aditiva: `attn_weights + mask` adiciona `-inf` (ou um valor muito negativo
   equivalente) nas posições válidas → softmax colapsa.
3. Logits do CTC head ficam quase uniformes (ou degenerados).
4. Argmax no Kotlin (`GraniteEngine.kt:677-685`) sempre escolhe o mesmo token (em geral
   o blank, id=0).
5. `dec.collapse(allIds)` → todos os ids removidos por serem blank → `intArrayOf()`.
6. `dec.decode(intArrayOf())` → `""`.
7. `transcribeSelectedMedia` lança `IllegalStateException("transcrição vazia em ...")`.

---

## 2. Fix (vacina em código + teste)

### 2.1 Patch mínimo em `GraniteEngine.kt`

**Trecho a alterar** (linhas 660-664):

```kotlin
// ANTES (errado):
// mask: 1 = padding (frames além do real dentro da janela); ONNX espera int64.
val mask = LongArray(windowFrames) { 1L }
mask.fill(0L, 0, windowLen)

// DEPOIS (correto — convenção transformers HF: 1=real, 0=pad):
// mask: 1 = real, 0 = padding (convenção transformers HF, ver
// modeling_granite_speech5.py: sequences[~attention_mask] = pad_token_id).
val mask = LongArray(windowFrames) { 0L }   // inicializa TUDO como 0 (= pad)
mask.fill(1L, 0, windowLen)                  // preenche [0, windowLen) com 1 (= real)
```

### 2.2 Vacina — teste JVM em `GraniteEngineTest.kt`

Adicionar (substitui qualquer teste antigo que tenha a convenção trocada):

```kotlin
@Test
fun `transcription mask convention is 1=real 0=pad (transformers HF)`() {
    // Vacina do bug "transcrição vazia" no Android: o grafo ONNX do Granite
    // (exportado de transformers) usa a convenção HF padrão — mask=1 para frames
    // reais, mask=0 para padding. Inverter produz logits degenerados e argmax
    // sempre blank. Ver modeling_granite_speech5.py:50-55,88,629.
    val windowFrames = 8
    val windowLen = 5
    val mask = LongArray(windowFrames) { 0L }
    mask.fill(1L, 0, windowLen)
    // primeiros windowLen = 1 (real), restante = 0 (pad)
    for (i in 0 until windowLen) {
        assertEquals("frame $i deveria ser 1 (real)", 1L, mask[i])
    }
    for (i in windowLen until windowFrames) {
        assertEquals("frame $i deveria ser 0 (pad)", 0L, mask[i])
    }
}
```

(O teste JVM não roda a ORT, mas trava a **intenção** do código: qualquer refactor que
inverta o mask falha este teste. A validação real end-to-end fica para o teste manual no
OnePlus 15.)

### 2.3 Risco do patch

- **Baixíssimo**: mudança é puramente numérica em um buffer `LongArray` antes de criar
  o `OnnxTensor`. Nenhum efeito colateral fora do tensor de máscara.
- Se o patch estiver errado (máscara é "0=real" mesmo), o comportamento fica igual ao
  atual (vazio), e a investigação continua. **Não regride** o que já estava funcionando.
- O fix é totalmente compatível com o `validate_pipeline.py` PC (que já passa com mask
  correta = 1=real).

### 2.4 Validação pós-fix

Sequência sugerida (não vou rodar nada automaticamente — você decide quando):

1. `cd D:/Projetos/SIG && ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`
   (gate atual; com o teste novo, fica explícito que a convenção é 1=real).
2. Reinstalar APK no OnePlus 15.
3. Carregar o mesmo `a.wav` (71 KB EN) que deu "transcrição vazia".
4. Esperado agora: transcrição EN com algum texto (não perfeito, mas NÃO vazio).
5. Se OK: marcar Pendência #1 do resumo da sessão anterior como concluída.

---

## 3. Sobre o áudio de 19 MB (PT) — OOM

### 3.1 O que o usuário viu (screenshot)

- `1781383142158_Interview recording 16.mp3`, `Granite 5.0 Turbo`, `CPU`.
- Erro: `Failed to allocate a 81709072 byte allocation with 17915056 free bytes and 17MB until OOM, target footprint 268435456, growth limit 268435456`.

Footprint 256 MB = limite heap do app. O `largeHeap="true"` no manifest
(linha 128) deveria liberar mais (tipicamente 512 MB no Android moderno, às vezes mais),
mas o valor exato depende do dispositivo. Em vários OnePlus, o `largeHeap` ainda
cota em 512 MB.

### 3.2 Por que o OOM acontece

Cálculo do que está alocado em um momento de pico do pipeline:

| Buffer | Tamanho | Onde |
|---|---|---|
| WAV PCM 19 MB em `FloatArray` (após `readWav16kMono`) | 19 MB × 4 = **76 MB** | heap Java (linha 791: `val out = FloatArray(sampleCount)`) |
| `features.data` para 19 MB de áudio (~190 s) | 190 × 100 / 2 = 9500 frames × 320 dim × 4 B = **12,2 MB** | heap Java (linha 224) |
| `input` por janela (loop de janelas) | 512 × 320 × 4 = **0,65 MB** | heap Java (re-alocado a cada janela) |
| Tensor intermediário da ORT (logits 512/4 × 16384 × 4) | 128 × 16384 × 4 = **8,4 MB** | depende do provider CPU |
| `OrtSession` + pesos ORT (parte F32 em RAM) | ~400-600 MB típico | off-heap via `.data` mmap, mas ORT aloca arenas no heap do app |
| Buffers do FFmpegKit (rodando em paralelo) | 10-50 MB | heap Java |
| Outros (UI, strings, etc.) | ~20 MB | heap Java |

A alocação exata que falhou foi **81,7 MB** — bate com o `FloatArray(190000)` do WAV
(76 MB + overhead) **OU** com um buffer de logits acumulado. Como o `transcribeFileInner`
faz `outFrames` iterações com `OnnxTensor` de 8 MB cada, e o `logits.floatBuffer` é
um `FloatBuffer` que pode ser **heap-backed** em vez de direct, em algum momento a ORT
precisa de ~80 MB contíguos no heap Java.

### 3.3 Por que o fix da máscara pode resolver o OOM também

Com a máscara correta, o argmax distribui os tokens (não vai dar tudo blank), o
`dec.collapse` filtra rapidamente, e o `dec.decode` mantém buffers pequenos. Mas isso
**não é garantido** — a alocação do `readWav16kMono` continua existindo.

### 3.4 Opções se o OOM persistir pós-fix da máscara

| # | Mudança | Custo | Benefício | Recomendação |
|---|---|---|---|---|
| 1 | Processar o WAV em **chunks** (carregar só 16 s por vez, não 19 MB de uma vez) | médio — muda `readWav16kMono` para streaming | elimina o pico de 76 MB | **Recomendado** se o OOM persistir |
| 2 | Converter para **FP16** (modelo de 950 MB em vez de 1,88 GB) | re-export no PC + republicar no R2 | -50% de RAM ORT | bom, mas o usuário disse "sem quantização"; FP16 não é quantização |
| 3 | Mover o `FloatArray` do WAV para `DirectByteBuffer` | baixo — usar `RandomAccessFile` + parse on-the-fly | -76 MB no heap | bom se o fix da máscara não bastar |
| 4 | Documentar limite: áudio > 15 MB pede chunking | zero | transparência | combinado com 1 ou 2 |

**Recomendação primária**: aplicar primeiro só o fix da máscara (§2) e revalidar o OOM.
Se o OOM persistir, **chunking** é a mudança mais limpa (não muda acurácia, não muda
formato do modelo).

---

## 4. Sobre o áudio PT de 16 kHz (`audio_16`)

- O modelo é **EN-only** (Librispeech/MLS/CommonVoice EN). Treinado só em inglês.
- Com áudio PT, o conformer pode produzir logits baixos para qualquer token, e o argmax
  acaba escolhendo blank ou um token aleatório que vira `""` depois do `decode`.
- Isso é **comportamento esperado**, documentado no design (`docs/granite-turboctc-design.md:33-35`).
- **Não tem fix**; é uma limitação do modelo. O `Granite 4.1` multilíngue entra depois.

A UI atual da `GraniteActivity` não avisa que o modelo é EN-only. **Sugestão menor**:
adicionar uma linha `TextView` estática abaixo do `button_model` (ex.: "Modelo em
inglês apenas") para o usuário não ficar confuso. **Não-bloqueante** — fazer depois se
você quiser.

---

## 5. Resumo das ações (próximos passos)

| # | Ação | Bloqueante? | Risco |
|---|---|---|---|
| 1 | Patch de 2 linhas em `GraniteEngine.kt` (linha 663) — inverter máscara | **sim** | mínimo |
| 2 | Adicionar teste JVM `transcription mask convention is 1=real 0=pad` em `GraniteEngineTest.kt` | sim (junto com 1) | nenhum |
| 3 | Gate: `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` | sim (junto) | nenhum (rebuild já é rotina) |
| 4 | (se OOM persistir) chunking em `readWav16kMono` | não — depende de revalidação | baixo |
| 5 | (futuro) aviso na UI sobre EN-only | não | nenhum |
| 6 | (futuro) FP16 ou Q4F16 no R2 | não | baixo — republicação do modelo |

**Ação 1+2+3 juntas** destrava o teste do OnePlus 15 (Pendência #1 do resumo
anterior). Se o OOM persistir, **Ação 4** (chunking) é o próximo passo natural.

Aguardando seu OK (ou steer) para aplicar 1+2+3. Se preferir incluir 4
preventivamente, me avise — é +20 linhas no `readWav16kMono` e não mexe no
modelo nem no R2.

---

## Apêndice A — referências verificadas

- `transformers/src/transformers/models/granite_speech5/modeling_granite_speech5.py`
  linhas 50-55 (docstring), 88 (atenção aditiva), 629 (mask=0 → pad).
- HF Space `ibm-granite/granite-speech-streaming-webgpu` (Space de referência da IBM;
  não consegui ler o JS direto, mas a doc do `modeling` é a fonte canônica).
- PC: `tools/granite/validate_pipeline.py` (linhas 31-33, 52) confirmam mask=1=real
  produz o mesmo texto que PyTorch.
- PC: `tools/granite/split_external.py` linha 26 (mask=zeros) **NÃO** é evidência da
  convenção — só valida finitude dos logits.

## Apêndice B — por que não é o decoder ByteLevel

Confirmei (mentalmente + com o teste JVM `bytelevel decode of utf8 multibyte` que
passa) que a tabela `byteLevelCharToByte` está correta: bytes imprimíveis 0x21-0x7E,
0xA1-0xAC, 0xAE-0xFF ficam em seus próprios codepoints; os 68 bytes de controle vão
para 0x100-0x143 em ordem. O teste passa com "é" → "é". Se o argmax saísse com IDs
válidos (≠0), o decoder montaria o texto. Como o argmax está sempre dando 0 (blank),
o decoder é inocente.

## Apêndice C — pendências restantes (do resumo anterior)

- [x] **Pendência 1 (validação fix8 no OnePlus 15)**: este fix (máscara) é a peça
      que faltava para o pipeline funcionar de verdade. Sem ele, o teste do fix8
      também daria "transcrição vazia" porque o fix8 só resolve o `System.loadLibrary`.
- [ ] Pendência 2 (áudio EN TTS no emulador/dispositivo) — mesma coisa, depende do fix.
- [ ] Pendência 3 (punctuator `punct_cap_seg_en.onnx`): continua pendente, não integrado.
      Após o fix da máscara, o texto sai sem pontuação. Integrar depois.
- [ ] commit/push/versão: continua **NÃO** sendo feito (regra da sessão anterior).
