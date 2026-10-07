# Auditoria do SmartJoin Android — 07/10/2026

**Registro da versão anterior à correção.** Os defeitos descritos abaixo foram tratados posteriormente; os resultados atuais, o ganho de velocidade e os limites da prova estão no [relatório de correção](smartjoin-fix-2026-10-07.md).

Há defeitos reproduzidos na duração e na integridade das emendas. Alguns resultados incorretos são rejeitados pelo app; outros passam pelo validador. Também existe um caminho que recodifica todo o vídeo apesar de o SmartJoin anunciar preservação por cópia.

Esta tarefa foi uma auditoria. Não alterei `FfmpegJoinVideosActivity.kt`, `SmartJoinPlanner.kt` nem o app instalado. Alterações preexistentes e alterações concorrentes em outros módulos foram preservadas.

## Localização e prova executada

- Orquestração e comandos: `app/src/main/java/br/gov/sp/pcsp/launcher/FfmpegJoinVideosActivity.kt`, `executeSmartJoin`, linha 1118; geradores de corpo/emenda/concat a partir da linha 1691.
- Regra de planejamento: `app/src/main/java/br/gov/sp/pcsp/launcher/SmartJoinPlanner.kt`.
- Dispositivo: **OnePlus Ace 2 Pro, PJA110, serial 1164a04, Android 13**. Todo comando ADB no aparelho usou `adb -s 1164a04`. O OnePlus 15 não foi alvo de comandos.
- Runtime real do app: **FFmpeg n6.0**, componente nativo `11-arm64-v8a`, já disponível no Ace.
- Um harness Java/Dex carregou um APK de debug do projeto separadamente, via `app_process` com o UID do app, e chamou por reflexão os métodos reais de sondagem, planejamento, geração de comandos e validação. Os comandos produzidos foram executados pelo FFmpegKit real do Android. Não houve instalação de APK, limpeza de dados, leitura de mídias pessoais nem interação com a janela.
- A orquestração headless reproduz a ordem corpo → emenda → corpo e as decisões dos métodos privados. O botão e a tela completa não foram exercitados. “Aceito” abaixo significa que **o método real `validateSmartJoinDuration` aceitou a saída**, condição usada pelo fluxo para concluir a junção.
- FFprobe/FFmpeg 8.0.1 no Windows inspecionaram as saídas recuperadas do aparelho: streams, pacotes, duração, contagem, cores, tons e decodificação completa. Houve também uma prova de decodificação no runtime Android.
- Os testes existentes focais passaram: **11 `SmartJoinPlannerTest` e 48 `FfmpegMediaPoliciesTest`**, total 59. A suíte contratual do harness e `assembleDebug` também passaram. Esses resultados não comprovam a integridade das mídias produzidas.

Foram executados **30 cenários de SmartJoin**, além de provas direcionadas do validador, da concatenação direta e do decoder. Dos 30, **15 passaram pelo validador**, **14 foram rejeitados por duração** e **um foi recusado pelo planejador por transição excessiva**. Aprovação do validador não equivale a saída correta.

O APK usado pelo harness tinha SHA-256 `ceccd01851d48fe265ef04cbf57c655ae2b69c56428204ae647dbd579224affb`. A identidade, o commit e os hashes dos fontes estão em `build/smartjoin_audit/evidence.json`. Esse APK de teste não substituiu o APK instalado no telefone.

## 1. Limites dos corpos e relógios das peças alteram duração e contagem — alta prioridade

Local: `buildSmartJoinBodyArguments`, linha 1691; limite `-t` na linha 1726; `buildSmartJoinTsArguments`, linha 1990; `buildSmartJoinConcatArguments`, linha 2022.

O seek de entrada está correto, mas a cópia continua limitada somente por `-t`. Nos arquivos H.264 com B-frames e GOP de dois segundos, os corpos de 0–4 s e 2–4 s incluem pacotes além da janela lógica. As peças também são deslocadas por `-avoid_negative_ts make_zero` e acumulam o atraso AAC por segmento. O manifesto concat não usa `SmartJoinPiece.durationSeconds`: só escreve nomes de arquivos, deixando o demuxer inferir os deslocamentos.

Reprodução com três clipes de 6 s, 25 fps, vermelho/verde/azul e tons diferentes:

| Opção | Esperado | Medido | Validador do app |
| --- | --- | --- | --- |
| Fade in/out, 0,5 s, CPU | 18 s / 450 quadros | 18,458667 s / 454 quadros | Rejeita |
| Demais dez efeitos, 0,5 s, CPU | 17 s / 425 quadros | 17,478667 s / 430 quadros | Rejeita todos |
| Fade in/out, 0,5 s, h264_mediacodec | 18 s / 450 quadros | 18,458667 s / 454 quadros | Rejeita |
| Dois clipes, Fade in/out, 0,5 s | 12 s / 300 quadros | 12,245333 s / 302 quadros | **Aceita** |
| Sem áudio, três clipes, Fade in/out | 18 s / 450 quadros | 18,32 s / 454 quadros | **Aceita** |
| GOP esparso intermediário, 6 + 3 + 6 s | 15 s / 375 quadros | 15,296 s / 377 quadros | **Aceita** |
| Misturar H.264 e HEVC | 18 s / 450 quadros | 18,328667 s / 452 quadros | **Aceita** |

Os intervalos entre pacotes de vídeo chegaram a 0,12 s em fontes de 25 fps cujo intervalo normal é 0,04 s. Alterar apenas a tolerância não resolve os quadros excedentes, as lacunas ou o deslocamento do relógio. A mensagem “Vídeo truncado” também é usada quando a saída ficou **mais longa**, tornando o diagnóstico impreciso.

Correção indicada: limitar cópia pelos pacotes/quadros da janela segura; preservar PTS e controlar DTS entre peças; usar durações explícitas e arredondamento cumulativo dos quadros; montar áudio sem multiplicar o atraso AAC por peça.

## 2. HEVC com GOP aberto produz referências inválidas e passa pela validação — alta prioridade

Locais: `detectVideoKeyframes`, linha 1369; limites do planejador em `SmartJoinPlanner.kt`, linha 134; `smartJoinVideoEncoderTail`, linha 1971; preparação/concat nas linhas 1990 e 2022.

Dois clipes HEVC de 6 s com GOP aberto, B-frames e emenda de 0,5 s geraram **12,245333 s e 303 pacotes de vídeo**, contra 12 s e 300 quadros esperados. O método real do app **aceitou** a saída.

Na decodificação completa pelo FFmpeg/FFprobe 8.0.1 apareceram:

```text
Could not find ref with POC 48
Error constructing the frame RPS.
Skipping invalid undecodable NALU: 8
```

O FFprobe contou **302 quadros decodificados para 303 pacotes**. O decoder do FFmpeg 6 no Ace também entregou **302 quadros**, embora não tenha emitido esses avisos na prova com nível warning. Portanto, ausência de aviso no runtime Android não comprova que todos os pacotes foram decodificados.

O planejador conhece apenas os timestamps dos keyframes, sem a margem dos quadros anteriores ao CRA armazenados depois dele. As emendas MediaCodec usam `-bf 0`, e os corpos originais mantêm outra reordenação. O problema exige tratar GOP aberto e a continuidade do decoder; não basta o container declarar a duração correta. O uso final de `hvc1` também merece revisão quando as emendas introduzem conjuntos de parâmetros distintos.

## 3. A validação aceita uma lacuna de dois segundos — alta prioridade

Local: `validateSmartJoinDuration`, linha 1443; tolerância na linha 1457.

A validação compara metadados de duração do container e das faixas, tolerando `max(0,35 s, número de emendas × 0,12 s)`. Não conta quadros, não verifica continuidade de apresentação nem decodifica as emendas. Falhas de leitura de metadados podem deixar as comparações sem dados.

Em uma **prova de controle separada**, gerei um arquivo de 6 s removendo os 50 quadros entre 2 e 4 s e conservando os timestamps restantes. Ele tem:

- 100 quadros, contra 150 esperados a 25 fps;
- salto de PTS de **2,04 s**, que exige sustentar o quadro anterior durante a lacuna;
- vídeo declarado com 6 s e áudio com 6,016 s.

O validador real, executado no Ace, **aceitou** esse arquivo. Essa prova demonstra a fragilidade estrutural da checagem; não afirma que toda junção cria uma lacuna de dois segundos. Os casos naturais de HEVC e de quadros excedentes acima mostram falhas que já passam por ela.

## 4. Um corpo vazio permite recodificar todo o vídeo — alta prioridade

Locais: `executeSmartJoin`, linha 1161; contagem na linha 1204; a mesma checagem aparece no preview, linha 667.

A condição `plan.clips.none { it.copyVideo }` considera um corpo marcado como copiável mesmo quando sua duração é zero. A execução depois ignora esse corpo por estar abaixo de `SMART_JOIN_MIN_SEGMENT_SECONDS`.

Reprodução com três clipes de 3 s que só têm o keyframe inicial, Fade in/out de 0,5 s:

- Primeiro corpo: `copyVideo=true`, intervalo **0–0 s**.
- Segundo e terceiro corpos: recodificados.
- Duas emendas: recodificadas.
- **Nenhum comando de corpo usa `-c:v copy`. Todo o vídeo é codificado novamente.**
- O plano permite a tarefa e a contagem usada pela UI seria “1/3 corpos em stream copy”. O resultado de 9,131333 s/225 quadros é aceito.

Não é uma troca de estratégia no tratamento de erro: é uma brecha na elegibilidade que produz o mesmo efeito indesejado de recodificar tudo. Para respeitar a proposta experimental, precisa haver pelo menos um corpo copiável com duração/quadros efetivos acima do limiar.

## 5. Sem transição, o SmartJoin ainda recodifica áudio desnecessariamente — prioridade média

Local: corpos a partir da linha 1732; áudio normalizado por peça na linha 1937; entrada SmartJoin na linha 1013.

Com os três arquivos compatíveis e `Sem transição`:

- SmartJoin: três corpos com vídeo em cópia, **três codificações AAC**, concat final; 18,112 s, 450 quadros.
- Referência sem SmartJoin, usando o método real `FfmpegMediaPolicies.directConcatCommandArguments`: **`-c copy` para vídeo e áudio**, 18,022 s, 450 quadros, duração da faixa de vídeo exatamente 18 s.

Há um caminho de cópia direta no app, mas marcar SmartJoin impede que ele seja usado mesmo quando não há efeito, silêncio a sintetizar, normalização ou redução de faixas. Além do custo, a recodificação adiciona perda geracional de áudio e atrasos por peça. A execução exige um encoder de vídeo compatível mesmo quando não haverá emendas de vídeo.

## 6. A normalização de SAR deforma a imagem — prioridade média

Local: `smartJoinVideoNormalizationFilter`, linha 1920.

O filtro ajusta pela proporção das dimensões codificadas e depois aplica o SAR do alvo. Quando o SAR da fonte difere do destino, isso altera a proporção de exibição.

Prova real: o clipe intermediário possui SAR **4:3** e uma figura branca codificada com **45×60 pixels**, exibida como um quadrado de **60×60**. O alvo majoritário tem SAR **1:1**. Após a normalização, a figura ficou **45×60**, com SAR 1:1: um retângulo, não o quadrado original. A medição e um quadro estão em `build/smartjoin_audit/sar_mismatch/geometry.json` e `body-frame.png`.

É necessário considerar a proporção de exibição ao calcular scale/pad, como na correção Windows.

## Outras observações e diferenças em relação ao Windows

- `maxSafeTransitionSeconds`, linha 2145, usa `clipe mais curto − 0,1 s`, adequado a uma emenda isolada. Em três clipes de 6 s, 10 s vira 5,9 s, mas o planejador rejeita porque as duas transições se sobrepõem no clipe intermediário. A restrição da UI deveria acompanhar a restrição do SmartJoin.
- O seam puro aceita `NaN` como transição e retorna um plano sem motivo de inelegibilidade, com limites NaN. A guarda posterior de corpos impede a execução desse plano, mas o diagnóstico não identifica o tempo inválido. Valores negativos são convertidos silenciosamente para zero. Prova em `planner-edge.log`.
- Transição de 0,0015 s desaparece pelo limiar de 0,002 s do planejador, sem aviso específico. Isso é menor que um quadro e não constitui o defeito principal.
- Cancelamento retorna antes de `smartJoinFailure`, que é quem apaga a saída. A limpeza dos segmentos não remove o MP4 final parcial, e o `finally` do chamador apaga apenas as entradas copiadas. **Risco identificado por leitura, sem prova de cancelamento na UI.**
- O Android já coloca `-ss` antes de `-i`, já intercala corpos e emendas e não tem a mesma dupla aplicação de trim/seek de áudio observada no Windows antigo. Não reproduziu a queda de 18 s para cerca de 8 s.
- O alvo de vídeo é deliberadamente escolhido pela duração compatível majoritária. A tela Android não oferece as opções Primeiro/Maior/Menor resolução do Windows; não há evidência do mesmo bug de ignorar esse seletor.
- A filtragem de encoders pela família de codec já impede o caso MPEG-4 como emenda H.264 identificado no Windows.

## Evidências e próximos passos técnicos

Tudo foi preservado localmente em `build/smartjoin_audit/`, ignorado pelo Git:

- `results.json`: 30 cenários, opções, comandos e resultados; `device-matrix.log` contém a matriz inicial de 28, complementada por `all-sparse.log` e `sar-probe.log`.
- `SmartJoinAudit.java`, `device_matrix.py`: chamada dos métodos reais do APK e execução no Ace.
- `extra-results.json`, `extra_probes.py`: arquivo com lacuna, decoder Android e concatenação direta.
- `transition_00/`, `two_distinct/`, `hevc/`, `all_sparse/`: saídas, peças selecionadas e logs completos.
- `sar_probe.py`, `sar_mismatch/`: reprodução da deformação de proporção.
- `unit-tests.log`, `harness-test.log`, `assemble-audit.log`: verificações realizadas; XMLs dos 59 testes em `app/build/test-results/testDebugUnitTest/`.
- `tested-source.apk`, `evidence.json`: identidade do código usado, sem instalação sobre o app.

A ordem recomendada para corrigir é: limites/relógios das peças e áudio; GOP aberto HEVC; validação de quadros e decoder; elegibilidade que exige cópia real; caminho de cópia direta sem efeito; SAR e mensagens/limites de transição. As mesmas reproduções devem virar testes de integração Android antes de considerar a correção concluída. Não basta os testes puros do planejador passarem.

Não foram certificados HDR/10-bit, VFR complexo, arquivos muito longos, legendas/anexos, todas as implementações MediaCodec nem interação manual/salvamento/remux para todos os containers.

Ao encerrar, os dois diretórios temporários exclusivos desta auditoria foram verificados e removidos do Ace. As evidências locais foram mantidas. Não foi limpa a pasta de dados/cache do app. Resultado em `build/smartjoin_audit/cleanup-device.log`.
