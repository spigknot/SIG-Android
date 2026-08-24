# Divisão da ferramenta STT Remoto em "Transcrição" e "Ocorrência"

Design em 2026-08-16.

## Decisão

A `RemoteSttActivity` continua única e recebe um modo via intent extra
(`EXTRA_MODE` = "transcription" | "occurrence"). A UI é ajustada por
`applyToolModeVisibility()`, chamado no onCreate e no refresh dos controles.
Isso evita duplicar uma activity de ~6.000 linhas e mantém os fluxos de
transcrição/WS intactos.

## Ferramenta Transcrição (arquivos)

- Esconde: microfones (branco/amarelo/vermelho), controles de intervalo,
  caixas Histórico e Oitiva (+ botões e checkbox de timestamps), timer e
  botão de salvar gravação.
- Mantém: "+", conversão, VAD, pasta de destino, waveform/vídeo,
  "Apenas converter/VAD", seletor de idioma, diarização, informativos,
  "Executar tarefa".

## Ferramenta Ocorrência

- Esconde: "+", preview (vídeo/waveform), timeline, modos de preparo,
  VAD, opções de lote, lista de arquivos.
- Mantém: seletor de IA (à esquerda), microfones, idioma, diarização,
  três caixas de texto, informativos, "Executar tarefa".
- Novo: botão de salvar gravação (à esquerda do microfone branco, aparece
  após a gravação) e o botão amarelo de pausa também ativo na gravação
  branca (`recordingPaused`).

## Regra Granite NAR (servidor)

Sem seletor de idioma e sem diarização nas DUAS ferramentas; os demais
modelos exibem ambos (inclusive o Scribe).

## Fora de escopo

Nada além do especificado muda (fluxos de transcrição, WS, VAD etc. intactos).
