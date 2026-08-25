# Aceitação do STT remoto

Este roteiro fecha a fronteira de validação dos fluxos REST e WebSocket
referenciados por `AGENTS.md`. Ele é um runbook de execução, não substitui os
testes unitários nem autoriza release.

## Limites comuns

- Usar somente dispositivo, áudio curto e provedor previamente aprovados para
  teste.
- A chave deve existir apenas no armazenamento seguro do ambiente/dispositivo;
  nunca entrar em código, commit, screenshot, terminal, log ou relatório.
- Não usar áudio, conta ou endpoint de produção sem aprovação explícita.
- Em falha, preservar apenas diagnóstico redigido. Remover áudio, tokens,
  headers de autorização e corpos de resposta antes de compartilhar qualquer
  artefato.
- Não promover APK, pacote nativo ou release como consequência deste roteiro.

## Gates automatizados

Executar no PowerShell, a partir de `D:\Projetos\SIG`:

```powershell
.\gradlew.bat --quiet :app:testDebugUnitTest --tests "br.gov.sp.pcsp.launcher.AssemblyAiAsyncFlowTest"
.\gradlew.bat --quiet :app:testDebugUnitTest --tests "br.gov.sp.pcsp.launcher.SttDiarizationTest"
.\gradlew.bat --quiet :app:testDebugUnitTest --tests "br.gov.sp.pcsp.launcher.SttLanguageSettingsTest"
```

Para qualquer mudança em `RemoteSttActivity.kt`, `AssemblyAiAsyncFlow.kt`,
`SttLanguageSettings.kt` ou `SttDiarization.kt`, executar também:

```powershell
.\gradlew.bat --quiet :app:testDebugUnitTest
.\gradlew.bat --quiet :app:lintDebug
.\gradlew.bat --quiet :app:assembleDebug
```

O comando de pacote nativo só é necessário se a mudança tocar o pacote nativo;
nesse caso, seguir `native-dependencies/README.md` e executar a verificação
definida em `AGENTS.md` antes de qualquer promoção.

## Prova REST — arquivos

### Escopo

Validar as rotas `send*ApiTranscription` e a montagem multipart usada por cada
provedor. O teste deve cobrir pelo menos o provedor alterado e manter a matriz
de outros provedores sem reutilizar parâmetros indevidos.

### Procedimento

1. Selecionar um áudio curto aprovado e registrar apenas um identificador
   redigido da fixture.
2. Confirmar provedor, modo e chave de teste antes de iniciar; não registrar a
   chave.
3. Executar a transcrição de arquivo.
4. Conferir o contrato específico da rota: nome do campo binário, campos de
   idioma/diarização, header de autorização, status HTTP e transcrição não
   vazia.
5. Em sucesso, registrar provedor, modo, resultado e duração aproximada, sem
   payload ou header.
6. Em falha, salvar somente os arquivos de diagnóstico redigidos e registrar
   status, mensagem sanitizada, etapa e próximo passo.

### Critério de aceitação

- A resposta é bem-sucedida para a fixture aprovada e a transcrição não é
  vazia.
- Os campos e headers observados correspondem ao owner do provedor.
- Uma falha reproduzível identifica etapa, status e artefato sem segredo.
- O mesmo gate automatizado permanece verde depois da prova.

## Prova WebSocket — ao vivo

### Escopo

Validar os handlers `handleGrokLiveEvent` e o ciclo de conexão dos provedores
Deepgram, AssemblyAI, Scribe e Grok quando aplicável à configuração testada.

### Procedimento

1. Conceder somente as permissões necessárias no dispositivo aprovado e iniciar
   a transcrição ao vivo.
2. Confirmar conexão e captura de áudio; registrar `CONNECTING` e `CONNECTED`
   somente como estados, sem dados sensíveis.
3. Produzir áudio suficiente para observar um parcial e um resultado final.
4. Durante uma captura controlada, provocar uma desconexão reversível. O
   resultado esperado é `RECONNECTING` seguido de `RECONNECTED`, com áudio
   recente reenviado quando a rota suportar isso.
5. Se a reconexão não puder ser concluída, o resultado deve ser
   `RECONNECT_FAILED` ou `AUDIO_LOST` com diagnóstico legível, sem Activity
   travada ou espera infinita.
6. Finalizar deliberadamente a sessão e confirmar resultado final, estado
   `DONE`, desconexão e limpeza dos callbacks.

### Contrato determinístico de diagnóstico

O seam `LiveDiagnosticContext` é exercitado por
`LiveDiagnosticContextTest`. O artefato redigido de correlação deve conservar,
quando a sessão gerar diagnóstico, `live_run_id`, `provider`,
`state_sequence`, `recovery_observed` e `terminal_outcome`. Esses campos ligam
o estado observado ao resultado sem armazenar áudio, payload ou credencial.

### Critério de aceitação

- Parcial e final aparecem sem duplicação indevida.
- A finalização termina dentro do limite definido pela implementação e não
  depende de fechar a Activity à força.
- A desconexão provocada produz estado observável de reconexão ou falha
  terminal, nunca silêncio sem diagnóstico.
- O resultado final e os artefatos de falha permanecem redigidos.
- O gate automatizado completo permanece verde.

## Registro redigido

Para cada execução aprovada, manter fora do Git um registro com:

```text
data/hora:
dispositivo:
provedor e modo:
fixture redigida:
cenário:
resultado:
estados observados:
artefato de diagnóstico redigido:
próxima ação:
```

Uma execução de campo não fecha, sozinha, a eficácia longitudinal do workflow;
ela apenas fornece o resultado da prova e a evidência de aceitação daquela
revisão.
