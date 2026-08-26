# Ferramenta Granite (STT local) — Design

> Estado: **aprovado** (26/08) — decisões: F32 puro; CPU + GPU(NNAPI) + NPU(NNAPI) com
> fallback; hospedagem no R2 sig-android. Em implementação.
> Data: 2026-08-26. Escopo: SIG Android (`D:\Projetos\SIG`).
> Não toca em nenhuma ferramenta existente (FFmpeg, Whisper, Remote STT, Calculadora, NPU).

## 1. Objetivo

Adicionar na tela **Ferramentas** um botão **"Granite"** (padrão dos demais cards, mas com
ícone/traços **amarelos** em vez de brancos) que abre uma ferramenta de transcrição local
similar ao Whisper: selecionar áudio(s) → escolher modelo (por enquanto só 1) → se não
baixado, tela perguntando se quer baixar → download automático → transcrever com **CPU** ou
**GPU** → salvar TXT/HTML.

Modelo: **Granite Speech 5.0 TurboCTC** da IBM (`ibm-granite/granite-speech-5.0-470m-turboctc`),
Apache 2.0, 470M parâmetros, conformer encoder + CTC, decodificação não-autorregressiva
(greedy) — rápido o bastante para edge. A IBM publicou um pipeline ONNX completo no Space
`granite-speech-streaming-webgpu` (front-end + encoder + decodificador + pós-processador de
pontuação), que é a referência de implementação.

## 2. Modelo — o que é e o que NÃO é

| | |
|---|---|
| Arquitetura | 16 blocos conformer (hidden 1024, 8 heads, block attention 128) + head CTC |
| Saída | 16.384 unidades BPE, greedy non-autoregressive |
| Idiomas | **SOMENTE inglês** (treinado com Librispeech/MLS/CommonVoice/... EN) |
| Pré-processamento | log-mel 80 + deltas, 16 kHz, stacking 2× → 320 dim @ 12,5 fps |
| Pesos oficiais | `model.safetensors` **946 MB (BF16)** — sem quantização |
| Licença | Apache 2.0 |

⚠️ **Idioma**: o modelo é EN-only. Testes com áudio em português vão produzir texto em
inglês/errado. É uma ferramenta **de testes** (pedido do usuário); o Granite 4.1 (multilíngue)
entra depois. O pós-processador de pontuação também é EN.

## 3. Pipeline de inferência (portado do Space oficial da IBM)

O Space `granite-speech-streaming-webgpu` roda exatamente este modelo com ONNX Runtime e foi
verificado pela IBM "bit-for-bit" contra o Python. O pipeline tem 5 estágios:

```
WAV 16k mono (Float32)
  → 1. AGC  (automatic gain control, moving-RMS → target 0.12, maxGain 20, janela 150 ms)
  → 2. Front-end (porta exata do CtcConformerProcessor._frontend):
        - STFT: n_fft 512, win_length 400 (janela de stft_window.bin), hop 160,
          center=True com reflect pad (256)
        - Mel: mel_filters.bin (80×257, htk), power
        - log10 com floor relativo ao pico global (floor_db 8.0) → /4 + 1
        - Deltas (win 3): (x[t+1] − x[t−1]) / 2 com borda replicate
        - Stacking 2×: (nFrames/2, 320)
  → 3. ONNX encoder (input_features [1,T,320] float32 + padding_mask [1,T])
        - T é múltiplo de pad_multiple 512 (janelas de 10,24 s); mask 1=pad, 0=real
        - Saída: ids (argmax embutido no grafo) + top_logprob [1, T/4]
  → 4. CTC collapse: remove repetidos (contra o id bruto anterior, blanks incluídos),
        depois blank (0), depois ids < 1 → tokens de conteúdo
  → 5. Tokenizer ByteLevel GPT-2 (vocab.json, 16.384): cada piece é byte-stand-in;
        concatena bytes e decodifica UTF-8 UMA vez no final
  → 6. Pós-processador punct_cap_seg_en.onnx (209 MB): pontuação + capitalização (EN)
        com pcs_vocab.json (Unigram greedy, BOS/EOS/UNK)
```

Arquivos do modelo (da IBM): `ctc_conformer_q4f16.onnx` (572 KB) + `.data` (**311 MB**,
Q4F16 — quantizado), `frontend_config.json`, `mel_filters.bin` (82 KB), `stft_window.bin`
(2 KB), `vocab.json` (177 KB), `pcs_vocab.json` (640 KB), `punct_cap_seg_en.onnx`
(**209 MB**). Total oficial: **~520 MB**.

### Versão "sem quantização alguma" (pedido do usuário)

O ONNX oficial é **Q4F16** (quantizado). Para atender "sem quantização", exportamos nós
mesmos o ONNX em **F32** a partir do `model.safetensors` (o repo não traz ONNX):

- Export com `torch.onnx.export` (transformers from source — a arquitetura
  `granite_speech5_ctc` só existe no main do transformers) usando as mesmas entradas/saídas
  do grafo da IBM (`input_features`, `padding_mask` → `ids`, `top_logprob`).
- Tamanho F32: **~1,88 GB** (download) e ~2,4 GB de RAM em inferência. Celulares 8 GB ok;
  aparelhos menores podem sofrer.
- Alternativas futuras (mesmo fluxo, só troca o arquivo): FP16 ~950 MB (sem quantização,
  meia precisão) ou Q4F16 oficial 311 MB (quantizado, perda pequena).

## 4. Engine no Android

- **ONNX Runtime Android** (`com.microsoft.onnxruntime:onnxruntime-android`), AAR oficial.
- Backends (decisão do usuário: CPU + GPU + NPU, "se possível"):
  - **CPU**: execution provider padrão (XNNPACK em arm64).
  - **GPU**: execution provider **NNAPI com `nnapi_device_type=GPU`** (delega para o driver
    Adreno/GPU — no Android moderno o driver é Vulkan-backed, então o usuário "tem Vulkan"
    por baixo; o onnxruntime não expõe EP Vulkan próprio).
  - **NPU**: execution provider **NNAPI com `nnapi_device_type=NPU`** (delega para o
    DSP/NPU do Qualcomm/MediaTek quando o aparelho expõe; senão a opção fica indisponível).
  - Fallback: se a sessão NNAPI falhar ao criar/rodar, cai para CPU com aviso no status.
- ⚠️ **OpenCL literal não existe no onnxruntime Android** (o OpenCL do Whisper funciona
  porque o whisper.cpp/ggml tem backend OpenCL Adreno próprio). Alternativa OpenCL literal
  exigiria outra engine (ex.: MNN/Alibaba) com risco maior e sem a referência da IBM — fora
  de escopo por decisão do usuário.
- Janelas fixas de 512 frames (10,24 s), sem overlap — mesmo formato do warmup/streaming da
  IBM (o padding do fim de janela não altera os ids dos frames reais, verificado pela IBM),
  progresso por janela, memória controlada.
- O `GraniteEngine.kt` mantém o front-end/decode/tokenizer/AGC **puros** (sem Android) →
  testáveis na JVM (`testDebugUnitTest`); só a sessão ORT é do dispositivo.

## 5. UI

### Botão na tela de Ferramentas

- Novo layout `view_tool_granite.xml` (cópia do padrão `view_tool_*.xml`: card 132 dp,
  `tool_card_bg`, ImageView 34 dp + título 16 sp bold) com:
  - Ícone novo `ic_tool_granite` (drawable vetorial de linhas/traços — padrão dos demais)
    com **`app:tint="#FFFFC928"` (amarelo)** em vez de `#FFFFFFFF`.
  - Título "Granite".
- `activity_tools.xml`: entra na **linha 3** (que hoje tem `button_transcription` + espaço
  vazio) ao lado da Transcrição.
- `ToolsActivity.kt`: `findViewById<View>(R.id.button_granite)` → `startActivity(... GraniteActivity)`.
- Nenhum outro botão/card é alterado.

### Tela da ferramenta (GraniteActivity — cópia simplificada do WhisperActivity)

Mesma anatomia do `activity_whisper.xml`: selecionar arquivos/pasta, botão de modelo,
botão de backend, botão transcrever, progresso, status, log, saída TXT/HTML + disquete.
Simplificações da v1:

- **Modelo**: menu com 1 opção ("Granite 5.0 Turbo"). Se o arquivo não existe → diálogo
  "Baixar modelo?" (igual ao Whisper) → download com % no status.
- **Backend**: menu CPU / GPU (NNAPI). Sem opção Vulkan.
- Sem VAD/beam/best-of/flash-attention/idioma/timestamps (não se aplicam ao CTC greedy;
  VAD fica para depois).
- Entrada: múltiplos arquivos/pasta, conversão via FFmpegKit (reuso do helper do Whisper)
  para WAV 16k mono pcm_s16le.
- Saída: `transcricoes.txt` + `transcricoes.html` + log, mesmos botões do Whisper.

## 6. Download do modelo (hospedagem)

Pedido: "o app baixa automaticamente". O pacote é: `model.onnx` (F32, 1,88 GB) +
`mel_filters.bin` + `stft_window.bin` + `frontend_config.json` + `vocab.json` +
`pcs_vocab.json` + `punct_cap_seg_en.onnx` (209 MB) ≈ **2,1 GB**.

Opções de fonte (decisão do usuário):

- **A. Cloudflare R2 sig-android (recomendado)**: infra já existente do app
  (NativeDependencyManager baixa ffmpeg/whisper/silero de lá). Um ZIP versionado
  `sig-android-granite-v1.zip`, download com progresso, sem conta nova. O usuário não vê
  diferença — o download continua automático.
- **B. Hugging Face (pedido literal)**: exige conta/token HF (não existe no ambiente);
  publicaríamos num repo próprio (ex.: `spigknot/granite-speech-5.0-470m-turboctc-onnx`).
  Mesmo comportamento de download (URL `resolve/main/...`). A IBM também permite usar o
  ONNX Q4F16 direto do Space dela (URLs públicas, sem conta) — mas é quantizado.

## 7. Fases de implementação

1. **Export/validação (PC)**: venv com torch + transformers (main) + onnxruntime; exportar
   ONNX F32 com `ids`/`top_logprob` embutidos; validar com áudio de teste (TTS EN) e
   comparar com o Q4F16 da IBM (mesma saída esperada, WER ~0 no clip de teste).
2. **Hospedagem**: zipar o pacote e publicar (R2 ou HF, conforme decisão).
3. **Engine Android**: dependência onnxruntime-android; `GraniteEngine.kt` (AGC, front-end,
   ORT, CTC collapse, tokenizer, punctuator — partes puras testáveis) + testes unitários.
4. **GraniteActivity** + `activity_granite.xml` (clonar estrutura do Whisper, simplificar).
5. **Botão Granite** (card amarelo) na tela de Ferramentas + ToolsActivity.
6. **Build/entrega**: `testDebugUnitTest` + `lintDebug` + `assembleDebug`, APK no O:\,
   commit+push na main, teste manual do usuário.

## 8. Riscos e mitigações

| Risco | Mitigação |
|---|---|
| Export F32 diverge do grafo da IBM (ex.: padding_mask) | Seguir exatamente entradas/saídas documentadas; validar vs Q4F16 com áudio real |
| RAM insuficiente no aparelho (F32 ~2,4 GB) | Janelas fixas de 10,24 s; se travar, migrar para FP16 (troca de arquivo) |
| Modelo EN-only decepciona em PT | Deixar explícito na UI/status ("modelo em inglês") e no plano; 4.1 multilíngue depois |
| Download de 2,1 GB em rede móvel | Diálogo avisa o tamanho antes; progresso por % |
| NNAPI indisponível em algum aparelho | Fallback automático para CPU se a sessão GPU falhar |
| punctuator 209 MB pesa no pacote | Opção v1.1: pontuação pode ser desligada (texto cru) |

## 9. Fora de escopo (v1)

- Granite 4.1/4.0 (multilíngue), streaming ao vivo, VAD, timestamps, tradução.
- Versão Windows da ferramenta (outro app, outra engine).
