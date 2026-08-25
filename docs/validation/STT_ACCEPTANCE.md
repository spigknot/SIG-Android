# Aceitação do STT remoto

Este roteiro fecha a fronteira de validação dos fluxos REST e WebSocket
referenciados por AGENTS.md. Ele é um runbook de execução, não substitui os
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

A matriz única de comandos está em docs/agents/validation.md. Execute os
testes focais antes da prova e o gate completo quando a mudança tocar o
hotspot. No PowerShell, a porta curta `& .\scripts\validate-agent-harness.ps1
-Quiet` verifica o contrato do agente e o diff; use a opção `-RunAndroidGates`
para incluir os três gates Android. No Git Bash, invoque o host PowerShell
explicitamente conforme a matriz; não execute o `.ps1` diretamente.

O comando de pacote nativo só é necessário se a mudança tocar o pacote nativo;
nesse caso, siga docs/agents/release.md e a matriz canônica antes de qualquer
promoção.

## Preparação determinística do teste de campo

A prova usa a variante `debug` e o package `br.gov.sp.pcsp.launcher`. O serial
do dispositivo deve ser escolhido explicitamente; não use o primeiro aparelho
retornado por `adb devices` quando houver mais de um conectado.

No PowerShell, a partir da raiz do repositório:

```powershell
$root = (git rev-parse --show-toplevel)
$serial = "<SERIAL_DO_DISPOSITIVO_APROVADO>"
$package = "br.gov.sp.pcsp.launcher"
$apk = Join-Path $root "app\build\outputs\apk\debug\app-debug.apk"

adb devices -l
adb -s $serial get-state
if (-not (Test-Path -LiteralPath $apk -PathType Leaf)) { throw "APK debug ausente: $apk" }
adb -s $serial install -r $apk
adb -s $serial shell am force-stop $package
adb -s $serial shell monkey -p $package 1
```

Durante uma execução, capturar somente os tags de diagnóstico do app, com
timestamp. Em um segundo terminal, antes de iniciar a rota STT:

```powershell
$evidenceRoot = Join-Path $env:TEMP ("sig-stt-" + (Get-Date -Format "yyyyMMdd-HHmmss"))
New-Item -ItemType Directory -Force -Path $evidenceRoot | Out-Null
adb -s $serial logcat -c
adb -s $serial logcat -v threadtime -s GraniteSpeech:I SigNative:I '*:S' > (Join-Path $evidenceRoot "logcat-filtered.txt")
```

Interrompa a captura com `Ctrl+C` depois de finalizar a sessão. Não colete
`*:V`, dumps completos, payloads HTTP, áudio ou o arquivo de transcrição.

## Coleta e correlação redigida

As sessões criadas pelo app ficam em `/sdcard/SIG/Granite Speech/`. Para trazer
somente a correlação da sessão mais recente e manter os dados fora do Git:

```powershell
$sessionPath = (& adb -s $serial shell 'ls -td "/sdcard/SIG/Granite Speech/"* 2>/dev/null | head -n 1' | Out-String).Trim()
if ([string]::IsNullOrWhiteSpace($sessionPath)) { throw "pasta de sessão STT não encontrada" }
adb -s $serial pull "$sessionPath/correlation.txt" (Join-Path $evidenceRoot "correlation.raw.txt")

$allowedCorrelation = '^(live_run_id|run_id|transcript_id|provider|state_sequence|recovery_observed|terminal_outcome)='
Get-Content -LiteralPath (Join-Path $evidenceRoot "correlation.raw.txt") |
    Where-Object { $_ -match $allowedCorrelation } |
    Set-Content -LiteralPath (Join-Path $evidenceRoot "correlation.txt") -Encoding utf8
Remove-Item -LiteralPath (Join-Path $evidenceRoot "correlation.raw.txt") -Force
```

O `live_run_id` liga a sequência de estados ao resultado da mesma sessão. Para
WebSocket, o registro mínimo de aceitação é `state_sequence`,
`recovery_observed` e `terminal_outcome`; para REST/AssemblyAI, preserve
`run_id`, `transcript_id` e `provider` quando existirem. Antes de compartilhar
qualquer arquivo, revisar localmente e remover chaves, headers, payloads,
transcrição e áudio. O `logcat-filtered.txt` serve apenas como evidência dos
estados e erros do app; não substitui `correlation.txt`.

## Prova REST — arquivos

### Escopo

Validar as rotas send*ApiTranscription e a montagem multipart usada por cada
provedor. O teste deve cobrir pelo menos o provedor alterado e manter a matriz
de outros provedores sem reutilizar parâmetros indevidos.

### Procedimento

1. Selecionar um áudio curto aprovado e registrar apenas um identificador
   redigido da fixture.
2. Confirmar provedor, modo e chave de teste antes de iniciar; não registrar
   a chave.
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

Validar os handlers handleGrokLiveEvent e o ciclo de conexão dos provedores
Deepgram, AssemblyAI, Scribe e Grok quando aplicável à configuração testada.

### Fixture e limites determinísticos

- Usar uma fixture aprovada de 16 kHz, mono, PCM, com duração entre 10 e 30
  segundos e pelo menos um segmento de fala de 1 segundo. Registrar somente
  `fixture_id`, `duration_ms` e `speech_segment_ms`.
- Solicitar a desconexão depois de `CONNECTED` e do primeiro parcial, em um
  ponto registrado da fixture. O teste deve usar o mesmo ponto em repetições.
- Para Grok, a política vigente é no máximo 7 tentativas, atraso inicial de
  500 ms, teto de 7.000 ms e replay de no máximo 8 segundos. Esses valores
  correspondem a `GROK_MAX_RECONNECT_ATTEMPTS`,
  `GROK_RECONNECT_BASE_MILLIS`, `GROK_RECONNECT_MAX_MILLIS` e
  `GROK_REPLAY_BUFFER_SECONDS`.
- Para Deepgram, a finalização de segurança tem 3.000 ms no caminho rápido e
  10.000 ms no caminho de backup, conforme `DEEPGRAM_FINISH_FAST_MILLIS` e
  `DEEPGRAM_FINISH_TIMEOUT_MILLIS`. Outros provedores precisam expor um limite
  equivalente antes de uma prova ser aprovada.
- Registrar `state_sequence`, `recovery_observed`, `terminal_outcome` e
  `elapsed_ms`; não aceitar "áudio suficiente", "áudio recente" ou "limite
  definido" sem os valores acima.

### Procedimento

1. Conceder somente as permissões necessárias no dispositivo aprovado e iniciar
   a transcrição ao vivo.
2. Confirmar conexão e captura de áudio; registrar CONNECTING e CONNECTED
   somente como estados, sem dados sensíveis.
3. Reproduzir a fixture até o ponto registrado e confirmar pelo menos um
   parcial e um resultado final.
4. No ponto registrado, provocar a desconexão reversível. O resultado esperado
   é `RECONNECTING` seguido de `RECONNECTED` dentro da política do provedor, com
   o replay limitado registrado quando a rota suportar isso.
5. Se a reconexão não puder ser concluída, o resultado deve ser
   RECONNECT_FAILED ou AUDIO_LOST com diagnóstico legível, sem Activity
   travada ou espera infinita.
6. Finalizar deliberadamente a sessão e confirmar resultado final, estado DONE,
   desconexão e limpeza dos callbacks.

### Contrato determinístico de diagnóstico

O seam LiveDiagnosticContext é exercitado por LiveDiagnosticContextTest. O
artefato redigido de correlação deve conservar, quando a sessão gerar
diagnóstico, live_run_id, provider, state_sequence, recovery_observed e
terminal_outcome. Esses campos ligam o estado observado ao resultado sem
armazenar áudio, payload ou credencial.

### Critério de aceitação

- Parcial e final aparecem sem duplicação indevida.
- A finalização termina dentro do limite explícito do provedor e não depende de
  fechar a Activity à força.
- A desconexão provocada produz estado observável de reconexão ou falha
  terminal, nunca silêncio sem diagnóstico.
- O resultado final e os artefatos de falha permanecem redigidos.
- O gate automatizado completo permanece verde.

## Registro redigido

Para cada execução aprovada, manter fora do Git um registro com:

data/hora:
dispositivo:
provedor e modo:
fixture redigida:
cenário:
resultado:
estados observados:
artefato de diagnóstico redigido:
próxima ação:

Uma execução de campo não fecha, sozinha, a eficácia longitudinal do workflow;
ela apenas fornece o resultado da prova e a evidência de aceitação daquela
revisão.
