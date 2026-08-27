# Separar os layouts das ferramentas Transcrição e Ocorrência (RemoteSttActivity)

> Status: PROPOSTA DE DESIGN (aguardando implementação em fases).
> Fonte da verdade: este documento. Não altera código por si só.

## 1. Motivação

As ferramentas **Transcrição** e **Ocorrência** compartilham a mesma Activity
(`RemoteSttActivity`) e o mesmo layout (`activity_remote_stt.xml`, 1368 linhas).
A separação é feita em runtime por `applyToolModeVisibility()`, que esconde
elementos de um modo no outro. Isso é frágil e confuso: cada ferramenta infla
e inicializa a tela inteira, mesmo quando boa parte dela não será usada.

Objetivo: **dois layouts completamente separados**, um para cada ferramenta,
mantendo a aparência idêntica (mesmos elementos, nas mesmas posições). Só o
que muda é o que está "por trás" (o XML e a inicialização).

## 2. Decisão de arquitetura

**Manter UMA Activity (`RemoteSttActivity`) com DOIS layouts**, escolhido em
`onCreate` pelo `EXTRA_MODE`.

Rejeitamos a alternativa de **duas Activities** (`OcorrenciaActivity` +
`TranscricaoActivity`): o arquivo tem ~6745 linhas com lógica compartilhada
maciça (gravação, transcrição ao vivo/WebSocket, assistente, servidor,
preparo de arquivos, saída). Dividir em duas Activities exigiria mover milhares
de linhas para uma classe base/delegate — risco muito maior para **zero ganho
visual**, que é o requisito central.

O `transcriptionMode` (flag já existente) continua existindo; apenas a escolha
do layout e a visibilidade deixam de ser feitas por esconder/mostrar.

## 3. Classificação das views

### 3.1 Views COMPARTILHADAS (presentes nos DOIS layouts)
Header (`btnBack`, `tool_mode_title`, `button_model_settings`), `server_gate`
(`advanced_model`, `input_ip_1..4`, `button_ping_server`, `server_gate_status`),
`source_bar` (container), `recording_panel` + `grok_diarize_row` (containers),
`checkbox_live_diarize`, `button_live_diarize_help`,
`button_live_diarize_realtime_help`, `button_live_language`,
`live_transcript_text`, `live_ai_progress`, `status`, footer `include`.

### 3.2 Views SÓ DA TRANSCRIÇÃO (removidas do layout Ocorrência)
`button_select_media`, `arrow_input_output`, `button_select_output_folder`,
`preview_frame`(+`video_preview`), `audio_waveform`, `playback_controls`,
`timeline_frame`(+`timeline`,`playback_speed_label`), `current_time`,
`time_fields`(+`input_from`,`input_to`), `selected_file`,
`video_prepare_warning`, `prepare_mode_buttons`(+4 botões), `vad_mode_row`,
`batch_options_row`(+3 checkboxes), `selected_list_box`, `button_transcribe`,
`progress`, `batch_progress_box`, `output_file_name`, `output_actions`.

### 3.3 Views SÓ DA OCORRÊNCIA (removidas do layout Transcrição)
`live_interval_controls`(+`button_live_interval_minus`,`input_live_interval`,
`button_live_interval_plus`), `button_recording_action`, `button_live_mic_test`,
`button_live_mic_stop`, `button_save_recording`, `recording_timer`,
`history_output_container`(+`history_text`), `statement_output_container`
(+`statement_text`), `live_transcript_clipboard_actions`
(+`button_history`, recuperar/limpar/compartilhar/copiar/colar),
`live_post_actions`, `history_clipboard_actions`(+`button_person_selector`,
`button_statement`, limpar/compartilhar/copiar/colar), `history_post_actions`,
`statement_clipboard_actions`, `terminal_text`.

### 3.4 Regra de preservação visual
Remover **somente as folhas/containers específicos de cada modo**; manter
containers e spacers (Views com `weight=1`) intactos. Assim a distribuição de
peso e as posições dos elementos restantes ficam idênticas ao estado atual
(que já renderiza os elementos "escondidos" com `GONE`).

## 4. RISCO nº 1 — caminho do "microfone branco" cruza os dois modos

O fluxo de gravação da Ocorrência ("microfone branco") REUTILIZA o motor de
transcrição do modo Transcrição:

- `startMicrophoneRecording()` (Ocorrência) zera `checkboxOnlyConvert`,
  `checkboxOnlyVad`, `checkboxSendZip` (views SÓ da Transcrição).
- Ao parar a gravação, chama `startServerTranscription()`, que lê essas
  checkboxes e faz `outputActions.visibility = GONE` (view SÓ da Transcrição).

Portanto, **não basta remover as views do XML**: esses pontos de código que
tocam views do outro modo precisam de guarda nula (ver §5). Se removêssemos as
views do layout Ocorrência sem guardar o código, o microfone branco daria
`NullPointerException`.

## 5. Estratégia de guarda (campos anuláveis + compilador como guia)

1. Views específicas de modo viram campos **anuláveis** (`View?`, `TextView?`
   etc.) em vez de `lateinit var`.
2. `onCreate` escolhe o layout e faz `findViewById` com helper opcional
   (`fun <T: View> bind(id: Int): T? = findViewById(id)`); views compartilhadas
   continuam com `findViewById` direto.
3. Referências espalhadas: o compilador Kotlin aponta TODA utilização de campo
   anulável. Regra por ponto:
   - Métodos **específicos de um modo** (só executam no modo que possui a view)
     → acesso direto (não-nulo garantido por construção) ou `!!` com comentário.
   - Métodos **compartilhados** (`activateServer`, `refreshGrokApiControls`,
     `startServerTranscription`, `startMicrophoneRecording`,
     `updateTranscribeEnabled`, `clearAssistantOutputViews`,
     `updateTextEditorsLock`, `restoreInMemoryDraft`, etc.) → `?.` com default
     seguro (ex.: `checkboxOnlyConvert?.isChecked ?: false`).
4. `applyToolModeVisibility()` é **simplificado**: passa a ajustar só o que
   depende de MODELO (idioma/diarização, via `refreshGrokApiControls`) e o
   título; os toggles de modo viram estruturais (view ausente = não exibida).

## 6. Fases e gates (cada fase termina com build+testes verdes)

- **Fase 1 — Layouts**: criar `activity_remote_stt_occurrence.xml` e
  `activity_remote_stt_transcription.xml` por cópia + remoção de subtrees
  (script XML idempotente). Não referencia nada ainda; o original permanece.
- **Fase 2 — Código**: `onCreate` escolhe layout; campos anuláveis; guardar
  referências (compilador dirigido). Remover `activity_remote_stt.xml` ao final.
- **Fase 3 — Limpeza**: simplificar `applyToolModeVisibility`/visibilidade
  redundante.
- **Gate obrigatório (AGENTS.md)**: `./gradlew :app:testDebugUnitTest
  :app:lintDebug :app:assembleDebug` → `BUILD SUCCESSFUL`.
- **Teste manual** pelo usuário nas DUAS ferramentas, incluindo o microfone
  branco da Ocorrência, antes de qualquer release.

## 7. Rollback

Os arquivos novos são aditivos; o layout original só é removido depois que o
código compila e testa verde. Em qualquer erro, reverter o `RemoteSttActivity.kt`
para o commit anterior restaura o comportamento atual (o layout original não
foi alterado até a Fase 2 concluir).
