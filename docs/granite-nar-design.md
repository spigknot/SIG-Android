# Granite Speech 4.1 2B NAR (ONNX FP16) — Contrato real e design da engine

> Data: 2026-08-28. Escopo: SIG Android (`D:\Projetos\SIG`).
> Estado: modelo publicado no R2 (bucket sig-android, pasta `models/`); engine em implementação.

## 1. Modelo

`ibm-granite/granite-speech-4.1-2b-nar` — arquitetura **NLE** (Non-autoregressive
LLM-based Editing), Apache 2.0, ~2,25B params. Suporta EN/ES/FR/DE/**PT**.

Três componentes (exportados como 3 sub-grafos ONNX FP16 separados):

| Componente | Params | Sub-grafo ONNX |
|---|---|---|
| CTC Encoder (conformer 16 blocos, dual head) | 440M | `granite-4.1-nar-encoder-fp16.onnx` |
| Q-Former Projector (downsample 5×) | 160M | `granite-4.1-nar-projector-fp16.onnx` (+ .data) |
| LLM Editor (granite-4.0-1b bidirecional) | 1B | `granite-4.1-nar-llm-fp16.onnx` (+ .data) |

## 2. Contrato REAL dos sub-grafos (conferido no grafo, não no README)

> ⚠️ Divergências do `README.txt` do exportador estão marcadas.

### Encoder
- **Input**: `input_features` `[1, 2000, 160]` float32. **T é FIXO = 2000**.
  - ⚠️ O README dizia ter `attention_mask [1,T] int64` — **NÃO existe** no grafo
    exportado (0 nós de máscara). O encoder foi exportado com T=2000 estático
    e SEM máscara; o engine deve padar features até 2000 com zeros e não
    informar máscara.
- **Outputs**:
  - `encoder_bpe_logits` `[1, 500, 100352]` float32 (T//4 após pool janela 4).
  - `multilayer_features` `[1, 2000, 4096]` float32 (concat camadas [4,8,12,-1]).
- fp16 initializers: 472. Opset 17.

### Projector
- **Input**: `multilayer_features` `[1, 2000, 4096]` float32 (fixo 2000).
- **Output**: `audio_embeds` `[1, 402, 2048]` float32.
  - 2000 → 402 porque o Q-Former pad até múltiplo de block_size=15 → 2010 →
    ceil(2010/5)=402. O engine usa só os primeiros `real_frames//5` vetores.
- fp16 initializers: 34 (pesos em `.onnx.data`).

### LLM Editor
- **Inputs**:
  - `inputs_embeds` `[1, S, 2048]` float32 (S dinâmico).
  - `position_ids` `[1, S]` int64.
- **Output**: `logits` `[1, S, 100352]` float32.
- fp16 initializers: 362. **`lm_head.weight` (`onnx::MatMul_11754`) `[2048, 100352]`
  está no `.onnx.data` offset 2852458496, length 411041792**.
- ⚠️ **`embed_tokens.weight` NÃO está no grafo** (é `tie_word_embeddings=True`).
  O embedding é a **transposta** do `lm_head.weight`. Geramos `nar_embed_tokens.bin`
  `[100352, 2048]` fp16 token-major a partir dele (ver §4).

### Constantes de escala (conferidas nos nós do grafo)
- `embedding_multiplier = 12.0` — o grafo LLM faz `Mul(inputs_embeds, 12)` no início.
- `logits_scaling = 8.0` — o grafo LLM faz `Div(..., 8)` no fim.
- `scale_projected_embeddings = true` — o PyTorch divide `audio_embeds / 12` ANTES de
  concatenar (fora do `self.projector`). ⚠️ O sub-grafo projector NÃO embute essa
  divisão (termina em `out_linear → Cast → audio_embeds`). **O engine deve dividir
  `audio_embeds / 12` antes de concatenar com os text_embeds**, para o `×12` do LLM
  cancelar corretamente (senão os áudio-embeds saem 12× amplificados e a transcrição
  degrada).

## 3. Pipeline de inferência (ordem exata)

```
WAV 16k mono → front-end (log-mel 80, stack 2× → 160 dim, SEM deltas/AGC)
  → pad até T=2000 (zeros)
  → ENCODER → encoder_bpe_logits [500,100352] + multilayer_features [2000,4096]
  → CTC collapse BPE: argmax → unique_consecutive → remove blank(100257)  [código: ctc_tokens]
  → PROJECTOR(multilayer_features) → audio_embeds [402,2048]
  → audio_embeds = audio_embeds[:real_frames//5] / 12   (scale + trim)
  → interleave: slots = [blank, tok0, blank, tok1, ...] (len = max(2n+1, 8))
  → text_embeds = embed_tokens[slots]   (embedding lookup)
  → inputs_embeds = concat(audio_embeds, text_embeds)  [S, 2048]
  → position_ids = [0..S-1]
  → LLM(inputs_embeds, position_ids) → logits [S, 100352]
  → text_logits = logits[valid_audio:]   (fatia do texto)
  → CTC collapse: argmax → unique_consecutive → remove blank(100257)
  → decode byte-level BPE (100352 peças, id = byte − 0x21 na faixa imprimível)
```

## 4. Arquivos no R2 (pasta `models/`)

Modelo (12 arquivos + 1 gerado por nós):

| Arquivo | Papel |
|---|---|
| `granite-4.1-nar-encoder-fp16.onnx` (1.086.629.439) | encoder |
| `granite-4.1-nar-projector-fp16.onnx` (159.568.555) + `.onnx.data` (159.535.104) | projector |
| `granite-4.1-nar-llm-fp16.onnx` (2.149.128) + `.onnx.data` (3.263.500.288) | LLM editor |
| `nar_mel_filters.bin` (82.240) | mel filters 80×257 f32 (transposta de torchaudio `mel_scale.fb`) |
| `nar_stft_window.bin` (2.048) | Hann(400) com pad 56 zeros cada lado → 512 f32 |
| `preprocessor_config.json` | config front-end |
| `vocab.json` (1.612.704) | BPE 100352, byte-level, ids 0..100351 |
| `tokenizer.json` / `tokenizer_config.json` / `special_tokens_map.json` | tokenizer (ref) |
| `config.json` | config do modelo |
| **`nar_embed_tokens.bin`** (411.041.792) ⚠️ **gerado por nós** | embedding [100352,2048] fp16 = transpose(lm_head) |

`blank_token_id = 100257 = <|end_of_text|>`, `pad = 100256`, `unk = 100269`.
`vocab.json` tem 100352 entradas (ids 0..100351, sequenciais).

## 5. Engine Android (GraniteNarEngine)

- Reusa o padrão do `GraniteEngine`: partes **puras** (front-end, collapse,
  interleave, tokenizer, embedding lookup) testáveis na JVM; só a sessão ORT
  é do dispositivo.
- **Novo front-end** (diferente do TurboCTC): sem AGC, sem deltas; STFT com
  `nar_stft_window.bin` (Hann 400 pad 512), mel via `nar_mel_filters.bin`
  (80×257), `log10(clamp(1e-10))` → `max(logmel, mx-8)/4+1` → stack 2× (160).
  - ⚠️ A janela já vem centralizada (pad 56/56). O STFT usa `center=True`
    (reflect pad de 256) como o torchaudio — igual ao front-end TurboCTC.
  - Truncamento: `l = 2*(T // 320)` frames de mel (múltiplo par de hop).
- **3 sessões ORT** (encoder fixo T=2000, projector fixo, llm dinâmico S).
- **Embedding lookup em Java/Kotlin**: ler `nar_embed_tokens.bin` (411MB) como
  `Float16` e indexar `[token][:]` — ou, se o download for pesado, transpor é
  feito uma vez no carregamento. Memória: 100352×2048×2 = 411MB em float16
  (ou 822MB em float32). Manter fp16 e converter só os vetores usados.
- **Chunking**: como o encoder é T=2000 fixo (~37,5s de áudio por janela), áudios
  longos precisam de janelamento. v1: limitar a ~37s (T≤2000) com mensagem clara;
  chunking com overlap fica para depois (mesmo problema do TurboCTC, ver §6).

## 6. Limites conhecidos (v1)

- **T fixo = 2000** (~37,5s por encoder call). Áudio > 37,5s: chunking (depois).
- **Peso total ~4,4 GB** (encoder 1GB + projector 0,3GB + llm 3,3GB + embed 0,4GB).
  Requer flagship 12GB+; CPU faz upcast FP16→FP32 (RAM ~dobra nos logits).
- OOM de áudio longo = mesmo problema do TurboCTC (front-end processa tudo antes
  do janelamento). Fica para o chunking unificado.
