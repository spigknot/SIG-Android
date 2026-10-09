# Plano de arquitetura e execução do Granite STT

Versão 1.0, emitida em 09/10/2026, horário de São Paulo. Inspeção iniciada em 08/10/2026. Responsável: especialista deste chat. Executor externo: vínculo ainda não confirmado. A primeira ordem está em [ORDEM-G01-20261009.md](ORDEM-G01-20261009.md). O quadro de execução está em [ESTADO.md](ESTADO.md).

A prioridade é entregar transcrição local confiável em português brasileiro com escolhas reais de precisão, tempo, memória e hardware. A CPU permanece como controle e caminho utilizável. OpenCL, Vulkan, HTP e Smart avançam por provas independentes de contrato, execução numérica, uso de hardware e benefício do pipeline. O primeiro trabalho aproveita os logs existentes para corrigir a interpretação da memória; em seguida, integra o parser de vocabulário e resolve sessão e bucket antes de medir aceleradores.

Este plano substitui a sequência de execução anterior quando houver conflito estratégico. Não altera retroativamente critérios nem resultados de setembro. Cada mudança de decisão registra a evidência e o alcance da conclusão. Não há inferência, exportação ou monitoramento executando em segundo plano por causa destes arquivos.

## 1 Decisões iniciais

1. Corrigir offline os quadros de memória existentes. O extrator antigo trata `end.memory` como memória após inferência antes do release, embora o evento já contenha liberação. Os streams também contêm `inference.memory_before` e `memory_after` ignorados por esse extrator. Não repetir as transcrições para recuperar dados que já existem.
2. Integrar o parser manual como mudança pequena, preservando a interpretação do vocabulário. A diferença histórica de carga é grande, mas o ganho no APK integrado deve ser medido novamente. Corrigir semântica de JSON é uma tarefa distinta de substituir a implementação sem mudar resultados.
3. Tornar a configuração uma entrada imutável da sessão. Backend, rota CTC/full, editor logits/token_ids, bucket e contexto precisam sobreviver a recargas. Corrigir a instrumentação antes de novos comparativos.
4. Integrar ArgMax com validação de contrato no consumidor real. O seam de manifesto não é usado pelo loader experimental; validação apenas por quantidade de elementos e conversão Long para Int antes da faixa não bastam.
5. Reabrir HTP com t200/t400, contexto corretamente identificado e prova de partição. Começar por CTC CPU e CTC HTP com mesma entrada e mesmo artefato, ou explicitar quando a quantização muda o candidato. Separar custo de carga e uso repetido.
6. Investigar OpenCL e Vulkan separadamente. MNN e ggml são candidatos para partes do pipeline; ncnn é reserva para encoder/projector. QNN GPU é uma rota adicional, sem comprovação de duas APIs GPU distintas no SIG.
7. Tratar Smart NAR como divisão por estágios. Investigar Smart literal de prefill NPU e decode GPU em uma alternativa autoregressiva da família Granite, com identidade, caches e avaliação próprios.
8. Não copiar o engine experimental inteiro nem fazer merge amplo. A main já tem instalador, manifesto, diálogos e pacote nativo posteriores que seriam regredidos.

## 2 Estado observado e limites da inspeção

| Superfície | Estado confirmado | O que ainda precisa de prova |
|---|---|---|
| Main | `D:\Projetos\SIG`, branch `main`, HEAD `21439199ed28086139c2bbbe64ce4e4e18de77d9` | Reconfirmar HEAD e diff antes de qualquer integração |
| Worktree NAR | `D:\SIG-perfis-20260914-123811`, branch `codex/granite-perfis-20260914-123811`, HEAD `0036f149fd8dd7cb94b24fcdf6eef686a6749a07` | Hash dos artefatos usados por cada prova; testes novos na base de outubro |
| Ancestral comum | `7a6115039652328cb995d03a3ec9284f9b255696` | Seleção de alterações por consumidor e dependência |
| Lab | `D:\SIG-granite-nar-lab-rebuild`; rodada `nar-next-20260914-0950` | Inventário de integridade dos arquivos grandes; presença não equivale a hash validado |
| APK observado no OnePlus 15 | SHA-256 `713873631aa33a67d6b9227965f4bfe03585399603724ba7e5eea053e364be45`, versionName `1.509`, versionCode `72`, PID observado `31711` | Commit/diff de origem, hashes e caminhos das bibliotecas realmente mapeadas; esse APK não foi atribuído à main |
| Build do repositório | compileSdk/targetSdk 35, minSdk 24, NDK `27.2.12479018`, Java 21 instalado | Compatibilidade do novo runtime deve ser medida em build isolado |
| ORT do build | AAR Microsoft QNN `1.29.0`; `.so` ORT excluídos do APK | A versão do AAR não estabelece a versão carregada |
| Nativos | Main `COMPONENT_VERSION=11`; experimental usa versão 3. QAIRT distribuição `PACKAGE_VERSION=1` | Versão real QAIRT, SDK, ABI e hashes efetivos no aparelho |
| UI NAR | CPU no fluxo normal; GPU/NPU indisponíveis; editor float/int8b/int4b; float padrão | Não há implementação Granite OpenCL/Vulkan direta nem perfis completos de qualidade na UI |

As alterações já presentes de `FfmpegJoinVideosActivity.kt`, `SmartJoinPlanner.kt`, `SmartJoinPlannerTest.kt` e `SmartJoinTimingTest.kt` pertencem a outra tarefa. Documentos Whisper, Smart e a entrega original também não fazem parte do patch Granite. Os dois crash logs não rastreados da worktree experimental devem permanecer. Não fazer reset, stash global, prune ou limpeza para facilitar integração.

O mapa de código e as linhas verificadas estão em [MAPA-CODIGO-20261008.md](MAPA-CODIGO-20261008.md). O histórico confrontado com eventos está em [HISTORICO-EVIDENCIAS-20261008.md](HISTORICO-EVIDENCIAS-20261008.md). A matriz de fontes, versões e pilotos está em [RUNTIMES-E-PILOTOS-20261008.md](RUNTIMES-E-PILOTOS-20261008.md). O snapshot estruturado está em [SNAPSHOT-20261009.json](SNAPSHOT-20261009.json).

## 3 Máquinas e capacidade de execução

O host local `GUSTAVO` tem Xeon E5-2696 v3, 18 núcleos/36 threads, cerca de 31,8 GiB de RAM e RTX 3060 Ti. Python 3.11, Java 21, Node, Git, PowerShell 7 e ADB foram encontrados. O SDK Android contém o NDK 27.2 e CMake 3.22.1. Os ambientes `venv`, `venv-gpu` e `venv-qnn` existem no lab; seus executáveis e pacotes precisam ser conferidos antes de usá-los. A existência do diretório não prova ambiente saudável.

Na inspeção, D: tinha aproximadamente 13,3 GiB livres e C: 217,7 GiB. Novos exports grandes devem usar `C:\Users\Gustavo\SIG-granite-experimentos\20261009`, com paths explícitos e manifestos. Manter no mínimo 10 GiB livres em D: e 50 GiB em C:. Cada piloto autoriza no máximo 20 GiB de novos artefatos em C: e 1 GiB de relatórios/logs em D:, até nova decisão do especialista. Estimar pesos, external data, contexto e temporários antes de começar. Não excluir modelos, datasets ou ambientes para caber no orçamento. E: está fora desta investigação.

Há dois aparelhos físicos observados em três transportes ADB. `100.108.27.64:5555` corresponde ao OnePlus CPH2747, serial físico `3B15BD00FVE00000`, SM8850, Android 16, patch de segurança `2026-07-01`. `1164a04` e `100.114.88.45:5555` informam o mesmo serial `1164a04`, PJA110. Tratar os dois transportes PJA110 como o mesmo recurso. Endereços podem mudar; confirmar serial físico antes de qualquer comando que altere o aparelho.

A conexão do CPH2747 foi confirmada somente por leitura. Não há exclusividade concedida nem lock adquirido. Outros ensaios de Smart e Whisper podem disputar APK, processo, DSP e logcat. Não instalar, force-stop, trocar pacote, capturar teste ou medir enquanto outro dono usa esse serial. A G01 é inteiramente offline e não depende dessa reserva.

O mecanismo de subagentes deste chat foi usado para auditorias delimitadas. Eles não são o executor externo mencionado na entrega. O chat `Solucionar deadend GPU NPU` foi lido e identificado como autor da entrega e responsável por um inventário atual, sem vínculo comprovado de execução. Nenhuma ordem foi enviada a esse chat. A seleção do executor depende da identificação fornecida pelo usuário ou de evidência inequívoca do vínculo.

As capacidades efetivamente usadas nesta sessão foram leitura/edição local com PowerShell/Git, pesquisa web, auditorias por subagentes, leitura de chats e criação de checkout gerenciado. A identidade exata do modelo desta sessão não foi exposta por um retorno de ferramenta e não foi inventada. O executor deve registrar sua identidade quando disponível e confirmar suas próprias ferramentas; os comandos desta ordem não dependem de um nome de modelo específico.

## 4 Contrato do pipeline que deve permanecer explícito

```text
mídia e intervalo selecionados
  -> preparação FFmpeg e WAV 16 kHz mono
  -> frontend próprio NAR e contagem efetiva de frames
  -> encoder e hipótese BPE CTC
  -> features acústicas de múltiplas camadas
  -> projector e embeddings acústicos
  -> slots CTC e embeddings de tokens
  -> editor bidirecional em uma passagem
  -> ArgMax e collapse CTC na região textual
  -> BPE byte-level e UTF-8
```

O modelo oficial edita uma hipótese CTC com contexto bidirecional em uma passagem. A IBM publica suporte a português e licença Apache 2.0. Isso fundamenta a separação entre Smart por estágios NAR e pesquisa autoregressiva. Os contratos Android a seguir vêm dos wrappers e do código local; precisam ser conferidos nos grafos efetivos, pois os READMEs antigos divergem sobre máscara. [Modelo oficial](https://huggingface.co/ibm-granite/granite-speech-4.1-2b-nar).

| Estágio | Entrada e regra | Saída e validação obrigatória |
|---|---|---|
| WAV | PCM s16le 16 kHz mono efetivo; amostras identificadas por hash; intervalos em amostras | Validar audioFormat/bits por rota de preparação, cobertura e duração; nenhum AGC/delta TurboCTC reaproveitado implicitamente |
| Frontend | 80 mel, stack 2, dimensão 160, hop 160; STFT/janela/mel do pacote | `[1,T,160]` f32; reflect/padding/cauda/normalização comparados com referência |
| Encoder | Bucket T em 200/400/800/1200/1600/2000; entrada real e padding distinguíveis | Logits BPE e multilayer features; nomes, shape, dtype, frames válidos e non-finite |
| CTC inicial | Blank 100257 e vocab 100352 vinculados ao tokenizer | ArgMax, collapse de consecutivos e remoção de blank nessa ordem; guardar IDs antes de decode |
| Projector | Features de camadas exatas do export | Embeddings 2048; posições válidas separadas do padding interno; conferir relação com T |
| Escala acústica | Engine divide por 12; editor exportado multiplica por 12 | A escala é parte do contrato, inclusive em runtime novo |
| Embeddings de tokens | Arquivo token-major fp16 com mmap | 2048 valores por token; layout, tamanho, hash e ownership da leitura |
| Editor | `inputs_embeds [1,S,2048]`, `position_ids [1,S]`; máscara somente se presente no grafo | `logits [1,S,100352]` ou IDs int64 em contrato próprio; sem atenção causal introduzida |
| Saída | Offset acústico e região textual definidos por entrada real | Shape exato, faixa Long antes de Int, comprimento, blanks, repetição legítima e UTF-8 |

Os pesos de referência e QDQ devem ter descritores diferentes. O nome `fp16.onnx` não prova precisão. Inspecionar inicializadores, Q/DQ, MatMulNBits, bits, block_size, offsets e external data. Um manifesto da fonte histórica usa a string `>256MB` no campo SHA de arquivo grande; isso não serve como identidade criptográfica. Antes de exportar a partir desse arquivo, calcular seu hash completo ou validar um manifesto posterior íntegro contra os bytes.

O frontend limita a entrada por `frames <= 2000`. A aproximação pelo hop e stack é cerca de 40 s, com bordas dependentes do processamento real. O caso de OOM de 37,14 s não fixa um teto universal. A Activity atual entrega o WAV inteiro ao engine; não há segmentação automática comprovada. O teste de 59 s segmentado em laboratório não aprova esse fluxo de produto.

A fronteira WAV também precisa de uma trava real. A UI permite rotas ORIGINAL/COMPACT, e o reader atual valida sample rate/canais, mas interpreta pares de bytes como PCM16 sem conferir audioFormat/bits de forma suficiente. O contrato desejado é PCM s16le 16 kHz mono; a preparação não deve ser presumida universalmente correta. F1/F2 deve testar essa fronteira por rota e rejeitar ou converter formatos incompatíveis antes do frontend.

## 5 Perfis e validade das afirmações históricas

| Candidato | Encoder | Projector | Editor | Papel atual |
|---|---|---|---|---|
| REF | Referência, grafo/dados exatos | Referência | float/fp16 | Controle de transformação e referência de precisão do modelo; não verdade humana |
| H8 | Mesmo de REF | Congelado e identificado | int8b-blk128 | Candidato de precisão com editor menor |
| H4 | Mesmo de REF | Congelado e identificado | int4b-blk128 | Ablação compacta, ainda incompleta |
| EQ/E8 | U8/QDQ observado | Congelado e identificado | int8b-blk128 | Mais rápido em rodadas anteriores, perda de qualidade observada |
| EF | U8/QDQ observado | Congelado e identificado | float/fp16 | Ablação para localizar efeito de encoder/editor |
| Q4 | U8/QDQ observado | Congelado e identificado | int4b-blk128 | Candidato compacto; não aprovado sob os limites antigos |
| CTC | Encoder definido explicitamente | Não executa | Não executa | Candidato Rascunho; instalador atual ainda exige recursos do full |
| N1/N2 | Encoder solicitado HTP/contexto | CPU nos pilotos prioritários | CPU | Híbrido experimental; rótulo não prova partição integral |

Nenhuma linha pode ser ativada com descriptor incompleto. Precisão do projector, frontend, tokenizer, buckets e normalizador devem constar, mesmo quando não mudam entre braços.

Nos 24 públicos exploratórios, norm-v2, denominadores 255 palavras e 1242 caracteres, os valores corrigidos informados são REF 26 erros de palavra/64 de caractere, EQ 40/84 e N1 35/83. EQ piora WER em +5,4902 pp e N1 em +3,5294 pp contra REF. O sinal é sempre candidato menos controle. H8 preservou textos brutos de R nos 24 públicos observados, mas com tempos altos. Q4 não fica aprovado por economizar bytes. O conjunto já orientou decisões e não é confirmação independente.

As provas de ArgMax do editor reduziram a saída Java no caso de 578 posições de cerca de 232 MB de logits para 4624 bytes de IDs. Isso evita aquela cópia, sem provar que o runtime elimina a alocação interna de logits ou que toda inferência será mais rápida. Dez ciclos efetivos token_ids existem e devem ser lidos com seus eventos, distintos dos dez ciclos antigos que executaram logits por erro de configuração.

O parser Regex teve custo histórico de 14,7–21,3 s; o parser manual reduziu fortemente a carga no experimento. N2 demonstrou GENERATE e LOAD de contexto, mas alguns registros tinham grafo de REF e rótulo U8, `strict=false` e tempos de abertura confundidos com inferência. BASIC para EXTENDED_OPT foi reprovado por inconsistência de tempo e texto. GPU QNN 6022 e falhas DSP 6001/Bad VA são limitações dos pilotos registrados, não teoremas de impossibilidade dos backends.

A auditoria atual de memória corrige uma parte da própria entrega de 08/10: amostras por inferência existem em streams de ciclos, e `end` já pode representar estado após release. A correção precisa de gerador reproduzível, sem substituir os arquivos históricos. Valores e índices concretos estão no apêndice de evidências. Não usar o quadro antigo para dimensionar residência de pesos.

## 6 Alternativas arquiteturais e seleção dos primeiros pilotos

Versões e URLs estão congeladas no apêndice de runtimes. A dependência declarada do build permanece Microsoft ORT QNN 1.29; os bytes carregados no aparelho ainda não foram identificados. O upstream ORT 1.30 e o plugin Qualcomm QNN recente são alternativas isoladas; cada combinação deve fixar o ORT base, o EP, QAIRT, Java/JNI e Android usados. Não misturar num mesmo eixo upgrade de runtime, novo grafo e nova quantização.

| Rota | Candidato inicial | Confirmação existente | Incerteza principal e primeiro teste | Custo e risco |
|---|---|---|---|---|
| CPU | ORT atual com parser, contratos e sessão corrigidos | Pipeline Android funcional | Mesmo vocab e IDs; carga isolada e warm real sem criação de sessão dentro do run | Baixo para parser; médio para lifecycle/segmentação |
| CPU alternativa | Pesos constantes preparados offline no grafo QDQ efetivo | Profiling aponta cabeça BPE e dequantização | Mesmo peso interpretado numericamente; t200 e texto antes de ampliar | Médio; memória/storage podem aumentar; não trocar pelo float de origem disfarçadamente |
| GPU OpenCL | MNN para bloco e estágio; ggml para editor isolado | Runtime dispõe de backend OpenCL, sem prova do NAR completo | Atenção noncausal, RoPE/norm/LoRA, layouts e saída por posição em bloco real | Médio/alto; converter MatMulNBits e external data; risco de fallback e memória S² |
| GPU Vulkan | MNN ou ggml para mesmo bloco; ncnn para partes acústicas | Backend Vulkan disponível, sem porta NAR comprovada | Mesma entrada e peso do piloto OpenCL, comando identifica Vulkan e device | Médio/alto; shaders, layouts e sincronização; evidência separada de OpenCL |
| GPU QNN | Ponte atual versus plugin atual em piloto | Piloto antigo falhou na finalização | Bloco noncausal representativo, depois encoder t200; logs de partição e sem fallback oculto | Médio; kernel GQA causal otimizado não cobre o editor NAR |
| NPU | Encoder QDQ t200/t400 em ORT/QNN HTP | Execuções úteis e contextos históricos | Partição integral, mesmo bucket no load/run, LOAD autêntico e memória antes/depois | Médio; custo de sessão/contexto e mapeamento DSP |
| NPU alternativa | Plugin QNN atual e possibilidade fp16 documentada | Capacidade no código/documentação do plugin, ainda sem prova local | Um bloco/encoder pequeno com matriz de versões congelada | Médio; coexistência JNI/SDK, disponibilidade de bibliotecas e kernels |
| Smart NAR | Encoder HTP, projector CPU/GPU, editor OpenCL ou Vulkan | Partes HTP/CPU históricas | Contrato de tensor entre runtimes, custo de transferência e ganho total | Alto; residency simultânea pode perder para etapas sequenciais |
| Smart literal | Granite Speech autoregressivo com encoder/projector e LLM causal próprios | Modelo oficial alternativo e suporte parcial de runtimes | Export correto, prefill NPU, decode GPU, KV compartilhado semanticamente | Alto; modelo/qualidade/cache próprios; somente após piloto de contratos |

Para OpenCL/Vulkan, priorizar editor isolado quando preservar o checkpoint/LoRA for viável, porque a aceleração de um LLM bidirecional é uma parte útil mesmo com acústica CPU. Fazer piloto MNN de conversão de um bloco com pesos reais em paralelo à inspeção ggml de editor. Escolher um sobrevivente por API depois do bloco; não exportar o trio inteiro para quatro runtimes antes de saber se o bloco funciona. ncnn fica como segunda opção para encoder/projector se a conversão MNN falhar por motivo concreto.

ggml/llama.cpp terem GPU e opção de atenção não causal não estabelece suporte a GraniteSpeechNarForASR. A extração do editor, adaptação LoRA, inputs_embeds, posição e logits/IDs precisa de implementação própria e equivalência. O converter de Speech autoregressivo é evidência da rota adicional, não do NAR. O plugin QNN possui diferenças entre Attention causal otimizado e decomposição noncausal; testar o caminho que o grafo realmente usa.

A pesquisa HTP fp16 deve observar o comportamento de versões e hardware exatos: a documentação atual contém afirmações de quantização e opções fp16 que precisam ser lidas junto ao código. Não concluir suporte local apenas pela chave `enable_htp_fp16_precision`. A prova exige sessão, partição, saída e métricas. [QNN EP](https://onnxruntime.ai/docs/execution-providers/QNN-ExecutionProvider.html), [código e releases do plugin Qualcomm](https://github.com/onnxruntime/onnxruntime-qnn).

## 7 Fases com dependências e orçamento

Os tetos abaixo são prospectivos, não duração prometida. O especialista autoriza a passagem de fase pelo resultado revisável. Cada rodada ativa tem uma pergunta principal e até dois braços. Trabalho que excede o teto devolve diagnóstico e estimativa antes de ampliar.

| Fase | Responsável e entradas | Entrega verificável | Orçamento inicial | Condição de conclusão |
|---|---|---|---|---|
| F0 Estado e correção de evidências | Especialista + executor offline; streams e scripts existentes | G01: extrator corrigido, manifestos de entrada, quadros de memória/tempo e errata | 4 h de host, até 1 GiB; zero telefone/modelos novos | Associação run/pid/índice íntegra; fases de memória corretas; lacunas classificadas |
| F1 Parser e fronteiras de contrato | Executor em checkout de integração; main e seams selecionados | Parser + paridade; saída ArgMax validada no consumidor; descriptors de rota/bucket | Parser 1 dia; ArgMax separado 1 dia; sem novos pesos por padrão | Unitários e gates atuais; configuração observada corresponde à solicitada |
| F2 Sessões e áudio longo | Executor + revisão de arquitetura | Bucket previsto pelo frontend, sessões reutilizadas reais, segmentação/recomposição controladas | 2 dias; 2 públicos curtos e 1 longo público | Sem load inesperado dentro do run quente; cobertura completa e cancelamento/release |
| F3 Compatibilidade de hardware | Executors com ownership por runtime; artefatos t200 e blocos | OpenCL/Vulkan/HTP: operador representativo → bloco → estágio | Até 1 dia por piloto/runtime; 20 GiB; um estágio grande residente por host/aparelho | Saída numérica/texto e atribuição; falha localizada ou candidato sobrevivente |
| F4 Perfis CPU úteis | Executor; REF/H8/H4/EQ/Q4/CTC existentes | Qualidade exploratória e pareamento operacional corrigidos | Até 2 dias; 12 públicos iniciais; expansão só dos sobreviventes | Fronteira de precisão/tempo/memória; CTC com recursos realmente dispensados |
| F5 Pipeline GPU e NPU | Executor; sobreviventes F3 e lifecycle F2 | Full ou híbrido por estágio; cache, memória e cold/warm por API | 2 dias por candidato; piloto antes de 24 casos | Sem fallback oculto, cancelamento estável, carga e transferências incluídas |
| F6 Smart e alternativa AR | Especialista decide; executor mede ponte | Smart NAR por estágios; parecer e piloto AR separados | Smart NAR 2 dias; AR 1 dia de inspeção + piloto separado | Ganho total ou utilidade demonstrada; KV/atenção corretos na rota literal |
| F7 Confirmação PT BR | Executor + usuário apenas nos trechos pessoais ambíguos | Corpus novo congelado, WER/CER micro, casos críticos e disponibilidade | ≥50 públicos novos, ≥3000 palavras e ≥30 min conjuntamente; piloto menor antes | Sem overlap por áudio/hash e sem usar confirmação para calibrar/selecionar |
| F8 Integração de produto | Executor; perfis/hardware aprovados | UI, instalador, capacidade, fallback, TXT/HTML e manual | 2 dias; gates + smoke de uso | Rótulo corresponde ao pipeline, downloads/licenças íntegros, CPU disponível |
| F9 Aceitação de release | Especialista revisa; usuário aprova promoção | APK e, somente se mudaram, pacotes nativos verificados | Uma rodada de gates na versão final | Aprovação explícita para assinatura/publicação; restauração documentada |

F3 pode começar a leitura/conversão host pequena em paralelo a F1. Benchmark no telefone depende de F0/F2 e exclusividade. F4 não depende de sucesso GPU; deve entregar valor CPU enquanto F3 amadurece. F6 não exige NPU no modelo inteiro: o orçamento considera estágios e transferências. Reabrir export em lote exige aprovação técnica do especialista baseada no piloto, não aprovação adicional do usuário para trabalho já autorizado.

## 8 Integração de código e ownership

O executor inicia um checkout limpo derivado da main confirmada. Pode reutilizar um checkout apropriado ou criar um novo com prefixo `codex/`. A worktree de setembro permanece fonte e referência. Comparar alterações contra o ancestral e contra a main atual. Não selecionar commits apenas pelo título; validar SHA completo e dependências.

| Unidade revisável | Arquivos previstos | Regra de integração |
|---|---|---|
| Parser | `GraniteNarVocab.kt`, testes, pequena chamada em `GraniteNarEngine.kt`, `MODULE-MAP.md` | Igualdade integral do vocab real, testes de escapes/ordem/IDs; preservar behavior antigo nesta unidade |
| ArgMax | Seam de contrato/decoder e loader, manifesto, testes/debug | Consumidor exige nome/dtype/shape e hash; faixa em Long; logits continuam controlados; close em exceção |
| Lifecycle/bucket | Engine, seam de plano de sessão, debug/protocolo e testes | Imutabilidade de configuração; warmup predito por frames, inclusive CPU; eventos de criação identificados |
| CTC | Plano de recursos/instalador, engine e UI somente quando aprovado | Não exigir pesos de editor/projector/mmap na rota que não os usa; download por rota não deriva só de enum |
| EP context | `GraniteNarEpContext.kt` e consumidor, testes | OFF/GENERATE/LOAD com identity completa, sidecar atomicamente completo; wrapper→binários resolvidos e verificados |
| Segmentação | Seam de plano de cortes e recomposição, Activity e engine | Cobertura por amostras, limite obtido do frontend, memória e cancelamento; sem deduplicação textual global |
| Hardware | JNI/runtime experimental e pacote isolado | ABI/licenças/driver/partição; nenhuma mudança nativa compartilhada sem ownership reservado |

Nenhuma unidade transfere constantes globais debug mutáveis para API de produto. Preferir uma configuração imutável e uma identidade de sessão que inclua perfil, rota, hashes, backend por estágio, buckets, threads, opções ORT e contexto. A sessão pode ser reutilizada quando essa chave permanece igual; troca de chave exige fechamento explícito e evento. Compartilhamento entre transcrições não deve criar estado cruzado, inferência concorrente insegura ou cache sem limite.

Novos fontes Kotlin de produção exigem KDoc antes da primeira declaração e linha no `MODULE-MAP.md`. Seguir a área de ownership definida no mapa. `GraniteActivity` mantém UI, preparação e chamadas; parsing, contratos, cortes e política de capacidade ficam em seams claros. `RemoteSttActivity.kt` permanece fora do escopo local; qualquer necessidade real de editá-la abre unidade específica com suas provas funcionais.

## 9 Instrumentação e identidade obrigatórias

Antes de interpretar uma métrica, vincular `host + serial físico + transport + pid + run_id + session_id + event + measured_index`. O log bruto tem hash; a extração traz arquivo e número da linha. Quando um campo não existia no protocolo histórico, registrar ausência e limitar a conclusão; não fabricar identidade retrospectiva.

Cada configuração deve trazer APK SHA completo e proveniência commit/diff, versão do esquema, hashes dos grafos e de todos external data, contrato de I/O, tokenizer/frontend, escala, rota solicitada/observada, editor solicitado/observado, bucket previsto/criado/usado, backend solicitado e efetivo por estágio, nós/partições que ficam em CPU, fallback, ORT/EP/QAIRT e paths das bibliotecas carregadas. Hash do pacote QAIRT instalado não equivale a hash dos bytes efetivamente mapeados.

O `GraniteNarBenchmarkProtocol` precisa reconhecer as mensagens de entrada atuais, a rota CTC, o total e as sessões por bucket. O Collector existente espera `effective_frames` numa mensagem que o engine atual não emite. Uma dimensão ausente é falha de instrumentação; não completar a partir de um texto de configuração sem declarar a fonte.

Tempo deve distinguir preparação da mídia, frontend, parse/read de vocab, mmap/embeddings, sessão encoder/projector/editor, compilação/contexto, primeira inferência, inferência quente, ArgMax/collapse/decode, transferências/sincronização, gravação de relatório e release. Guardar relógio monotônico em ms para etapas e wall externo para experiência de uso. O tempo de subprocesso do orquestrador não é carga do app.

Medir PSS/RSS/Java heap/native heap separadamente, com origem e unidade. Os campos Android históricos com sufixo `kb` representam KiB nesse instrumento; converter para MiB dividindo por 1024 e bytes para MiB por 1048576. Memória de `start`, `load`, `inference.before`, `inference.after` e `end.after_release` ocupa colunas distintas. Máximo de amostras é `máximo observado`, nunca pico contínuo.

Nos novos runs, amostrar memória a cada 250 ms durante load e inferência, quando o custo da coleta não alterar o resultado de modo relevante. Registrar perda de amostras e medir um controle com/sem coletor curto. GPU/DSP têm parcelas que PSS pode não capturar; relatar instrumento disponível sem inventar total do acelerador. Não declarar energia ou eficiência energética sem ferramenta e método apropriados.

## 10 Protocolo de benchmark

### Controle e sessão

Para compatibilidade, usar o mesmo tensor capturado por hash em todos os backends, antes de comparar áudio end-to-end. Para comparação de produto, usar o mesmo WAV/intervalo/cortes, normalizador, referência, runtime congelado e configuração de threads; a diferença deliberada deve constar no nome do braço e no descriptor.

O primeiro ensaio por par usa dois áudios públicos curtos: um que caiba em t200 e outro em t400, confirmados por frames reais. Uma carga, um warmup e três inferências medidas na MESMA sessão por braço. Testar de forma explícita que session_id e objetos permanecem os mesmos e não surgem eventos de criação dentro das medidas quentes. Run que recria sessão é frio ou inválido para essa afirmação, não pode ser contado como warm.

Para ordem térmica, usar blocos ABBA/BAAB alternados, onde cada bloco contém a carga e a série na sessão daquele braço. Não manter dois modelos enormes residentes apenas para intercalar A/B. Congelar o calendário antes do ensaio, conservar contagens iguais e reportar cada execução. Mediana por áudio evita dar mais peso a casos com mais repetições; depois apresentar mediana/dispersão e delta pareado entre áudios. Não subtrair somas com denominadores diferentes.

Separar geração de contexto (cold compile), abertura sem contexto, abertura LOAD e inferência. Não chamar cache quente apenas porque um arquivo existia. Registrar se a execução abriu o wrapper, hashes dos binários e se gerou novos arquivos. A documentação distingue o modelo original e o wrapper EPContext; o comportamento concreto deve ser verificado. [EP Context](https://onnxruntime.ai/docs/execution-providers/EP-Context-Design.html).

### Temperatura e operação

Mesmo aparelho e modo de energia, aplicações concorrentes ausentes, estado de bateria/carregamento congelado. Registrar temperatura de bateria e sensores disponíveis, throttling, temperatura inicial/final e uma faixa definida pelo controle. Regra inicial: não comparar braços cuja temperatura inicial difira mais de 2 °C ou que tenham estados distintos de throttling. Se não houver sensor comparável, registrar essa limitação e usar cooldown estável definido a partir do controle.

O controle CPU inicial determina o teto do piloto de inferência. Para áudio curto, teto máximo de 180 s ou 3 vezes a duração do controle acrescida de 30 s, o maior dos dois, limitado a 10 min; se o controle já exceder 180 s, justificar antes de começar o braço. Geração inicial de contexto tem teto separado de 10 min para t200/t400; abertura LOAD de 120 s; export de bloco de 30 min; conversão de estágio de 2 h; novo build nativo de 2 h. Essas são travas operacionais, não métricas de aprovação.

Timeout, OOM, ANR, SSR, reset inesperado ou `Bad VA` encerra o braço para diagnóstico. Permitir uma repetição somente depois de identificar uma causa corrigível e registrar a diferença. Não reboootar em série para preencher uma tabela. Preservar a primeira tentativa e registrar `configuração incorreta`, `captura inválida`, `instalação`, `runtime`, `OOM`, `timeout`, `numérico`, `qualidade` ou `sem suporte` como classe.

### Lock e restauração

Lock deve abranger serial físico, troca de APK/modelo/contexto, funções importáveis e captura. Dono contém host, PID local, ID da tentativa, timestamp e lease renovável. PID de outro host não autoriza concluir abandono. Um lock existente exige verificar dono/lease; nenhuma rotina remove lock só por idade ou por falha de `pidof` no host errado.

Antes de alterar o aparelho, guardar APK anterior e SHA, descriptor/manifesto de modelos ativos e procedimento de restauração sem copiar dados pessoais. Usar staging de modelos íntegros e ativação transacional. Encerrar somente a captura/processo que pertence à tentativa. Nada de matar todos os clientes ADB, limpar logcat global de outra tarefa ou force-stop fora da reserva. Confirmar restauração e liberação de lock ao terminar, inclusive em exceção.

## 11 Testes de contrato e equivalência

| Classe | Entrada/controlador | Critério inicial | Como falha altera a decisão |
|---|---|---|---|
| Parser | Vocab real de SHA conhecido + fixtures de ordem/escapes/duplicatas | Lista integral igual ao parser anterior; IDs e peças idênticos | Corrigir port; mudança de semântica exige ordem separada |
| CTC/ArgMax | Fixtures com repetição, blank entre tokens iguais, empate, shape errado, out-of-range int64 e non-finite | Ordem correta e IDs exatos contra referência; rejeição de contrato incorreto | Não ativar token_ids nem isentar guarda de memória |
| Buckets | Frames 199/200/201/399/400/401 e limites/cauda do frontend | Bucket previsto=criado=usado; recurso válido, nenhum t400→t200 oculto | Resolver lifecycle antes do próximo benchmark |
| Contexto | Wrapper/binários válidos, adulterados, ausentes, incompletos, path externo e opções mudadas | LOAD só aceita identidade completa; geração não reaparece em LOAD | Regerar em namespace novo ou marcar incompatível; preservar contexto anterior |
| Conversão semântica | Tensor e pesos reais do bloco; runtime CPU congelado | Sem causalização, mesmo padding/posição/escala; comparação de tensor e IDs | Localizar primeiro bloco divergente; sem export full em lote |
| Runtime float32 | Mesmo grafo/peso sem transformação de precisão | NRMSE ≤1e-3 e cosseno ≥0,9999 no piloto, sem non-finite; IDs/texto finais revisados | Limites são prospectivos; não relaxar depois de olhar o resultado |
| Runtime float16 | Mesmo candidato e precisão pretendida | NRMSE ≤5e-3 e cosseno ≥0,999 no piloto; descrever max_abs/rel e margem top1 | Caso limítrofe volta ao especialista antes de expansão |
| Quantização | Candidato quantizado versus seu controle definido | Métricas numéricas são diagnóstico; qualidade PT-BR e casos críticos decidem | Não exigir igualdade com float nem chamar concordância com REF de acerto humano |
| Lifecycle | Falha de load/run, cancelamento, mudança de bucket/perfil, sessões repetidas | Fechamento em todos caminhos; estado não vaza; warm conserva configuração | Não abrir hardware na UI até operação estável |
| Segmentação | Áudio longo público e cortes congelados por amostra | Cobertura completa, cauda incluída, ordem e boundary auditáveis | Sem remover repetições globalmente para melhorar texto |

NRMSE tem denominador RMS do controle com epsilon explícito; cosseno com vetor zero usa regra própria. Guardar max_abs, erro relativo com piso, percentis e distribuição da margem entre top1/top2. Para ArgMax aplicado ao mesmo grafo, IDs devem ser exatos, inclusive regra de empate; para kernels de outro runtime, diferenças precisam localizar região/impacto. Os limiares numéricos iniciais são decisão de piloto, não prova universal de precisão de transcrição.

Smokes Android rodam no UID normal do aplicativo. Um validator shell serve como diagnóstico adicional e não substitui a sessão do app. Se o teste exige HTP integral, desabilitar fallback CPU nessa sessão e guardar evidência de partição. Uma sessão mista é legítima quando declarada, mas deve listar subgrafos e custos reais. `effective_backend` sozinho não é uma prova de hardware.

## 12 Qualidade PT BR e promoção de perfis

Manter três conjuntos separados: calibração encadeada, exploração/seleção e confirmação nova. Auditar por hash de áudio original e efetivo, origem e quando possível falante/duplicatas. Os 24 públicos antigos e triagens que se sobrepõem à calibração são exploração. Um holdout usado repetidamente para escolher candidatos deixa de confirmar generalização.

A confirmação prospectiva busca pelo menos 50 áudios públicos novos, 3000 palavras e 30 min, sem calibrar nem escolher artefato olhando seus resultados. Se não houver corpus com referência/licença adequada para esse tamanho, entregar a disponibilidade e escolher um mínimo novo antes da coleta; não completar com pessoais ou itens usados no passado. Medir micro WER/CER a partir de erros absolutos e denominadores, com I/D/S, duração, ruído, velocidade e categoria. Guardar versão e SHA do conteúdo do normalizador norm-v2.

As 15 gravações pessoais existentes permanecem locais e suas referências provisórias. Usar apenas para casos dirigidos após escuta verificável. Mostrar ao usuário trechos divergentes com timestamps e hipóteses quando a decisão depender deles; não pedir nova gravação nem publicar áudio/transcrição pessoal em relatório ou bucket. Negação, nomes, números, valores, datas/horas, placa, endereço e repetição real têm quadro próprio.

Critérios prospectivos de entrada no produto, preservando o veredito histórico:

| Opção | Qualidade contra controle e referência humana | Utilidade mínima inicial |
|---|---|---|
| Referência de precisão | REF auditado, ou candidato sem perda observada na confirmação e sem regressão crítica | Operação estável e limite de memória declarado; não prometer ser perfeito |
| Intermediário | ΔWER e ΔCER ≤+1 pp; sem nova falha crítica confirmada atribuível ao candidato | ≥15% menos latência aplicável OU ≥20% menos memória/storage com tempo aceitável |
| Compacto | ΔWER ≤+3 pp e ΔCER ≤+2 pp; limitações explicitadas e casos críticos revisados | ≥25% menos download/storage OU ≥20% menos memória; tamanho real do pacote instalado |
| Rascunho CTC | Perda mostrada por conjunto/categoria; não passa silenciosamente como precisão | ≥1,5× velocidade quente OU carga/memória substancialmente menor; recursos do editor dispensados |
| Backend acelerado | Mesmo perfil/precisão ou candidato distinto claramente identificado | Ganho ≥15% na métrica pertinente, ou benefício de memória/latência repetida com break-even conhecido |

Um erro crítico novo não gera uma regra de zero erro do modelo: comparar com controle e referência humana, analisar pares e registrar atribuição. Perfil que cumpre média mas introduz perda de negação/valor/placa confirmada não é promovido automaticamente. Incerteza estatística e dispersão devem acompanhar ganho pequeno; resultados sem tamanho suficiente ficam experimentais.

Publicar tempos cold e warm por aparelho/cenário. Para carga mais cara e run mais rápido, calcular break-even `ceil((load_candidato-load_controle)/(infer_controle-infer_candidato))` somente se o denominador for positivo e o protocolo for pareado; mostrar os dados usados. Não recomendar NPU para um uso isolado com base no ganho de encoder quente.

## 13 Áudio longo e Smart

Segmentação começa como plano de cortes imutável por amostras do WAV. Um modo de cobertura determinística sem VAD fornece controle; VAD e overlap são variantes de pipeline, com custo e efeito de qualidade separados. Escolher duração pelo limite de frames verificado, memória e benchmark, não pelo antigo caso de OOM. Testar bordas de silêncio, corte em palavra, fala rápida, repetição legítima e cauda curta.

O plano guarda `[start_sample,end_sample)` de cada segmento, regiões de overlap, dono de cada intervalo, hash e texto/timing individual. Backend CPU/GPU/HTP recebe o mesmo plano. Recombinar apenas fronteiras sobrepostas com regra limitada e verificável; o NAR atual não fornece automaticamente alinhamento palavra/tempo suficiente para justificar deduplicação arbitrária. Se faltar evidência para reconciliação segura, preferir cortes sem overlap controlados e registrar a limitação antes de sofisticar a política.

Smart NAR tem descriptor por estágio: encoder, projector, editor e pós-processamento. Definir dtype/layout/escala, ownership de buffers, cópia versus memória compartilhada, sincronização, timeout e cancelamento. Medir bytes e tempo de cada transferência, sessões residentes e memória combinada. Uma implementação sequencial que fecha recursos pode vencer um pipeline simultâneo que estoura DSP/RAM; escolher por evidência.

Smart automático seleciona apenas caminhos previamente verificados para o aparelho/runtime/pacote e cenário. Capacidade ausente produz motivo legível. Falha do acelerador registra etapa e troca para CPU de forma observável, quando tecnicamente recuperável; não chamar o resultado final de GPU/NPU sem explicar estágios e fallback. Nunca fazer varredura pesada invisível em toda transcrição.

A alternativa AR recebe outro model_id. O contrato inclui prefill, decode de um token, atenção causal, posições, cache K/V por camada, dtype/layout/quantização, escalas, RoPE, tokens especiais, máxima sequência e transferência. Verificar prefill CPU versus NPU, primeiro decode GPU a partir do cache NPU e passos subsequentes contra controle; medir conversão/cópia do cache. Shape igual não significa semântica igual. Não aplicar máscara causal ao editor NAR para fazê-lo caber no runtime.

## 14 Delegação e revisão de entregas

Só uma ordem executável fica ativa por recurso compartilhado. As próximas fases são dependências, não dezenas de tarefas pendentes enviadas ao executor. Cada ordem fixa pergunta, host/checkout/commit, entradas/hash, arquivos autorizados, configuração por estágio, comandos/interfaces, orçamento, critérios de interrupção e restauração, evidências e próxima decisão.

A primeira ordem G01 é independente do telefone e serve tanto para executor externo quanto para encaminhamento manual. Ela pede implementação de extrator reproduzível em arquivos novos e reprocessamento dos streams existentes, sem alterar histórico, APK ou modelos. G02 será emitida depois da revisão de G01: parser de vocab com paridade integral e patch mínimo. G03 fecha bucket/sessão/protocolo e guardas ArgMax. G04 escolhe o primeiro piloto de hardware com base no inventário e nas versões realmente carregáveis.

Leituras e blocos host podem rodar em paralelo com arquivos e recursos distintos. Edições no mesmo engine, modelos grandes na RAM/VRAM, DSP e ADB do mesmo serial exigem dono único. Downloads usam cache íntegro/retomada e hashes; não duplicar pesos nem recompilar contexto válido por hábito. A memória e o espaço disponíveis limitam concorrência, mesmo quando existem agentes livres.

Ao receber relatório, o especialista confere manifestos contra bytes, eventos contra configuração, contagens contra extração e tabelas contra fórmulas. Reexecutar somente a prova afetada por mudança material, defeito de captura ou ausência de evidência. Um relatório favorável não substitui partição ou contrato observado. Cada marco termina com decisão `aceitar unidade`, `manter experimental`, `corrigir e repetir caso afetado` ou `descartar método`, com causa e próxima ordem.

## 15 Arquivos e esquema de evidências

Documentação versionada fica em `docs/especialista/granite`: plano, estado, decisões, ordens, pareceres e resumos redigidos. Logs brutos, APK, nativos, modelos e métricas pessoais ficam fora do Git, em diretório da rodada. Artefatos públicos experimentais podem usar prefixo R2 versionado somente depois de manifesto/checker/paridade e de verificar as permissões do projeto. Nunca sobrescrever produção ou publicar dados pessoais.

```text
docs/especialista/granite/
  PLANO-GRANITE-20261009.md
  ESTADO.md
  DECISOES.md
  SNAPSHOT-20261009.json
  ORDEM-G01-20261009.md
  MAPA-CODIGO-20261008.md
  HISTORICO-EVIDENCIAS-20261008.md
  RUNTIMES-E-PILOTOS-20261008.md

C:/Users/Gustavo/SIG-granite-experimentos/20261009/G01/
  input-manifest.json
  attempts.jsonl
  events.jsonl
  exclusions.jsonl
  memory-by-event.jsonl
  timing-by-event.jsonl
  summary-public.json
  ERRATA-G01.md
  tests.txt
  reproduction.txt
  patch.diff
  delivery-manifest.json
```

Todo manifesto usa SHA-256 completo, tamanho em bytes, origem/revisão/licença, nome relativo, I/O e status. CSV/JSON têm unidade e esquema explícitos. Para runs futuros, acrescentar `apk-manifest.json`, `native-manifest.json`, `model-manifest.json`, `configuration.json`, log bruto e `partition.json`. Ausência de identidade bloqueia apenas a afirmação que dela depende; uma prova offline de paridade do parser não exige identidade de APK.

`ESTADO.md` registra tarefa, dono, dependência, status, bloqueio, próxima ação e evidência. `DECISOES.md` registra hipóteses refutadas e diferença necessária para reabertura. O plano aumenta versão quando muda estratégia, orçamento ou aceitação; correções factuais têm errata e referência. Arquivos de estado não criam execução futura nem substituem wait/monitoramento real.

## 16 Gates e release

Ler AGENTS do checkout, `MODULE-MAP.md` e scripts antes de cada unidade. Para Kotlin/Android, executar unitários focais e depois os três gates na base final integrada:

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:lintDebug
.\gradlew.bat :app:assembleDebug
```

Para regras/ferramentas Python, usar testes focais de contrato e os testes existentes pertinentes. A G01 não altera APK e não precisa repetir benchmarks Android. Para o harness e commits, respeitar a suíte silenciosa e o snapshot:

```powershell
& .\scripts\tests\validate-agent-harness.tests.ps1 -Quiet
& .\scripts\validate-agent-harness.ps1 -Quiet -Json
```

O hook instalado chama o gate com `-Staged -RunAndroidGates -Quiet`. O verificador staged rejeita alterações tracked fora do índice, mesmo que pertençam a terceiros. Por isso esta entrega documental é versionada em checkout isolado e copiada para a pasta local do pedido. Não incluir arquivos de terceiros no índice nem contornar hook. Se houver falha de base, registrar causa e isolar o patch.

Release só de APK confirma ausência de mudança no contrato de `NativeDependencyManager.kt` e reutiliza ZIPs publicados. Runtime novo ou mudança de pacote nativo exige build/verify dos pacotes correspondentes e aceitação conjunta com APK. QAIRT e ORT devem ser compatíveis como conjunto real. Assinatura, publicação e promoção de release aguardam aprovação explícita do usuário conforme AGENTS; não são necessárias para executar G01 ou os experimentos já autorizados.

## 17 Conclusão operacional desta versão

F0 tem inspeção concluída e uma correção de evidências identificada. G01 está preparada para o executor; seu despacho depende apenas do vínculo do executor, ainda não confirmado. Nenhum perfil novo, backend ou release foi aprovado nesta versão. O próximo marco útil é um extrator auditável com quadros corrigidos dos registros existentes, seguido do parser e do fechamento de sessão/bucket. O trabalho de GPU e NPU passa a medir a configuração real do pipeline e pode revisar a arquitetura quando os pilotos indicarem um caminho melhor.
