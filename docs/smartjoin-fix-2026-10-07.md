# SmartJoin Android: correções e prova funcional — 07/10/2026

Os defeitos reproduzidos na [auditoria inicial](smartjoin-audit-2026-10-07.md) foram corrigidos no código-fonte do projeto. O SmartJoin continua híbrido: copia os corpos compatíveis e recodifica somente emendas e corpos incompatíveis. Não existe retry que substitua um plano inviável por recodificação integral.

## Resultado e velocidade

No **Ace 2 Pro, serial 1164a04**, com o **FFmpeg n6.0 do pacote nativo 11-arm64-v8a**, três vídeos sintéticos em movimento de 60 s, 1280×720, 25 fps, com Fade in/out de 0,5 s:

| Medida | SmartJoin | Recodificação completa |
|---|---:|---:|
| Tempo do processamento no aparelho | 10,891 s | 20,828 s |
| Quadros copiados | 4.300 | 0 |
| Quadros recodificados | 200 | 4.500 |
| Quadros finais | 4.500 | 4.500 |
| Duração do vídeo final | 180 s | 180 s |

O SmartJoin preservou **95,56% dos quadros por stream copy** e foi **1,91× mais rápido nessa medição**, incluindo sua análise e validação. Ambos usaram `h264_mediacodec` e qualidade Alta. O comparador integral usou o gerador de filtros e os argumentos de encoder do caminho normal do app; o harness montou esses argumentos sem abrir a tela. O tempo não inclui envio dos arquivos por ADB nem inspeção posterior no Windows. Não é uma garantia universal de desempenho; em vídeos curtos, as margens até os keyframes ocupam uma proporção maior do conteúdo.

## O que mudou

- **Cortes e emendas:** análise dos PTS/DTS dos pacotes, limites por contagem de quadros, durações explícitas no manifesto e arredondamento acumulado das emendas. Removido `make_zero` do caminho híbrido; o atraso de decodificação é alinhado sem alterar PTS.
- **GOP aberto de HEVC:** o corpo de saída termina antes das imagens leading do CRA seguinte. O corpo de entrada descarta leading com PTS negativo. No FFmpeg 6 Android, `-frames:v` conta depois desse descarte, sem somar os leading ao limite. As emendas terminam com keyframe solicitado; `hev1` preserva os parâmetros em banda, também ao remuxar para MOV.
- **Áudio:** nenhum AAC é gerado nos corpos/emendas. O áudio das fontes é montado e codificado uma única vez no passo final, com fades/acrossfade, seleção ou preservação das faixas e preenchimento com silêncio (`apad` + `atrim`).
- **Transição zero:** quando vídeo, áudio e seleção permitem, há um único comando de cópia de vídeo e áudio. Nenhum encoder de vídeo é exigido. Quando apenas áudio precisa de tratamento, o vídeo continua em copy.
- **Plano sem ganho:** o guard exige corpo copiável com duração real; uma flag `copyVideo` em corpo vazio não autoriza recodificar tudo.
- **Geometria e opções:** a escala considera DAR e SAR do destino. O limite de transição respeita os clipes intermediários. Valores não finitos são recusados no planejamento; efeitos menores que meio quadro são normalizados para zero usando o framerate do perfil dominante.
- **Aceitação e cancelamento:** duração de cabeçalho sozinha não basta. A saída precisa ter a quantidade prevista de quadros, PTS contínuos, DTS ordenados e áudio com inventário/duração corretos. Pequenas janelas nas emendas são decodificadas para conferir quadros e referências. A publicação ocorre depois dessas verificações. O worker limpa seu diretório temporário e a saída parcial em falha/cancelamento.

## Prova funcional

Foram aprovados **41 cenários válidos de SmartJoin**, contando a prova de 180 s. Dois planos deliberadamente inviáveis foram recusados: transições sobrepostas e três clipes de GOP esparso sem nenhum corpo útil para copiar. Não houve fallback para recodificação integral.

| Caso | Resultado |
|---|---|
| Três clipes de 6 s, Fade in/out de 0,5 s | 18 s, 450 quadros, maior intervalo PTS 40 ms |
| Os dez efeitos com sobreposição de 0,5 s | 17 s, 425 quadros, maior intervalo PTS 40 ms |
| Dois clipes de 6 s, Fade in/out | 12 s, 300 quadros |
| GOP esparso no clipe intermediário de 3 s | 15 s, 375 quadros; outros corpos continuam em copy |
| H.264 e HEVC por hardware, incluindo movimento | Decodificação completa sem erros; quadros amostrados dos três corpos copiados idênticos aos originais |
| Movimento com fonte H.264 sem B-frames | 450 quadros; amostras dos corpos copiados idênticas |
| Framerate 30000/1001, efeito de 0,55 s | Quantidade/duração corretas, sem arredondar o FPS para 29,97 nos comandos |
| Falta de áudio, áudio curto, duas faixas e as cinco qualidades | Duração e inventário corretos, sem atraso AAC acumulado |
| Faixas distintas, 440 Hz e 1320 Hz | Seleção da segunda produziu apenas 1320 Hz; preservação de ambas manteve 440 e 1320 Hz |
| SAR de origem 4:3, destino 1:1 | Figura de teste passou de retângulo 45×60 para aproximadamente quadrado 45×44, com arredondamento de escala/codec |
| Arquivo adversarial de 6 s, 50 quadros ausentes e lacuna de 2,04 s | Validador recusou apesar da duração correta no cabeçalho |
| Sinal de cancelamento antes da análise, depois de corpo e depois da montagem | Pipeline interrompido; nenhuma saída publicada |

Na cópia direta de AAC, o vídeo mediu exatamente 18 s e o contêiner 18,021333 s, devido à granularidade/priming dos pacotes de áudio copiados. Não há múltiplas recodificações de AAC nesse caminho.

## Verificações, identidade e limites da prova

- **554 testes unitários, zero falhas**, mais `lintDebug` e `assembleDebug`, pelo gate central. A suíte contratual do harness passou antes dos gates. `git diff --check` e o mapa de módulos passaram.
- O harness chamou **`runSmartJoinPipeline` de produção**, usado também pelo worker da tela, em um APK separado carregado por `app_process`. Não há implementação alternativa do SmartJoin no harness.
- A matriz principal e o benchmark usaram o APK SHA-256 `b9beabfc0b7635d4b9e801b5c08cfe1142ea9487c177ba650d3ecf3f013e23ba`. Depois houve somente o ajuste do FPS dominante na interface e, por último, a atualização dos textos de ajuda; o worker permaneceu igual. A versão intermediária `ffa9f4e24201fc0d942ee0d94e91629d9e4ad3e19b1b81a79ac1dc2eeaefbb0b` passou nos gates e nas provas das faixas distintas/qualidades restantes. O APK final `ddcba83893c173b7663880b3006578270e48c14e98f00157f466b9d046041e0b` compilou e passou em smoke nativo adicional. Os textos esclarecem duração das transições e que a qualidade afeta apenas as partes recodificadas.
- Evidências completas, comandos, resultados, CRC/amostras de quadros, mídias e hashes ficam em `build/smartjoin_audit/fixed/`, ignorado pelo Git. Índice: `evidence.json`; matriz: `results.json`; opções adicionais: `extra-results.json`; benchmark: `benchmark-summary.json`; cancelamentos: `final-probes.json`; faixas distintas: `audio-selection-results.json`; gates: `harness-validation.json`.
- A decodificação completa foi conferida com FFmpeg no Windows, além das janelas verificadas pelo FFmpeg real do Android. A tela, o botão e a reprodução pelo MediaPlayer **não foram exercitados**. A tentativa adicional de obter quadros pelo `MediaMetadataRetriever` no processo headless retornou nulo também para o arquivo original de controle, ficando inconclusiva; isso não é apresentado como falha das saídas nem como aprovação do player Android.
- Não foi instalado ou publicado APK. O pacote SIG que existia na auditoria anterior já não estava instalado no Ace ao iniciar esta prova; por isso o teste usou diretório isolado em `/data/local/tmp`, com as bibliotecas do ZIP nativo cujo SHA-256 foi conferido. Nenhum comando foi direcionado ao OnePlus 15, nenhum dado pessoal foi lido e alterações concorrentes em outros módulos foram preservadas.

## Fontes e testes de regressão

- `FfmpegJoinVideosActivity.kt`: orquestração, sondagem, comandos, validação, publicação e UI.
- `SmartJoinPlanner.kt`: compatibilidade, limites, GOP aberto, alocação de quadros e invariantes temporais (`SmartJoinTiming`).
- `FfmpegOutputRemuxer.kt`: preservação de `hev1` para HEVC híbrido; comportamento dos outros pipelines preservado por parâmetro opcional.
- `SmartJoinPlannerTest`, `SmartJoinTimingTest` e `FfmpegOutputRemuxerTest`: regressões de números inválidos, copy vazio, margem do CRA, FPS dominante, arredondamento, quadros ausentes/duplicados, início deslocado e tag do remux.
