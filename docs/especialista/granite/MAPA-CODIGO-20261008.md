# Granite 4.1 NAR — mapa auditado do código e riscos de integração

Data de referência: 08/10/2026. Auditoria de leitura; somente este documento foi criado. Não foram executados build, testes, ADB, download, exportação, alteração de pacote ou acesso ao disco E:. A existência de código/testes abaixo não é uma afirmação de que esses testes passaram neste snapshot ou de que o APK instalado contém esse código.

## 1. Identidade e limites da inspeção

| Campo | Checkout principal | Worktree experimental |
|---|---|---|
| Diretório | `D:\Projetos\SIG` | `D:\SIG-perfis-20260914-123811` |
| Branch observada | `main` | `codex/granite-perfis-20260914-123811` |
| HEAD completo | `21439199ed28086139c2bbbe64ce4e4e18de77d9` | `0036f149fd8dd7cb94b24fcdf6eef686a6749a07` |
| Ancestral comum | `7a6115039652328cb995d03a3ec9284f9b255696` | mesmo ancestral |
| Commits exclusivos em relação ao outro HEAD | 80 | 17 |
| Alterações alheias observadas | `FfmpegJoinVideosActivity.kt`, `SmartJoinPlanner.kt`, `SmartJoinPlannerTest.kt`, `SmartJoinTimingTest.kt`; documentos de especialistas não rastreados | `hs_err_pid52044.log`, `replay_pid52044.log`, ambos não rastreados |

As instruções `AGENTS.md` dos dois diretórios foram lidas. Não foram encontrados `AGENTS.md` adicionais nas árvores `app`, `scripts` ou `tools` do checkout principal. O inventário de produção é controlado por `MODULE-MAP.md` e pela exigência de KDoc antes da primeira declaração. O gate aplica staged snapshot quando pedido, `git diff --check`, consistência do mapa e, com `-RunAndroidGates`, unitários, lint e assemble (`scripts/validate-agent-harness.ps1:97`, `:111`, `:120`).

O APK, o UID, o processo e os bytes nativos do telefone não foram inspecionados por esta auditoria. Sua identidade continua pendente de prova própria. O estado do lab e a validade dos ensaios históricos são objetos de auditoria separada; comentários de código com números históricos são identificados como comentários, não como medição repetida aqui.

Nas tabelas, **M** significa arquivo relativo a `D:\Projetos\SIG` e **X** significa relativo a `D:\SIG-perfis-20260914-123811`. Todos os números de linha são de um dos HEADs acima e devem ser reconfirmados após qualquer edição.

## 2. Ownership e divergências que impedem cópia integral

| Arquivo | Diferença M→X | Consequência |
|---|---:|---|
| `app/src/main/java/br/gov/sp/pcsp/launcher/GraniteNarEngine.kt` | +655 / −201 | contém melhorias experimentais e código de download/UI dependente de snapshot antigo; integrar por seams e hunks |
| `app/src/main/java/br/gov/sp/pcsp/launcher/GraniteActivity.kt` | +87 / −120 | X perde os diálogos atuais `DownloadPlanDialog` e a apresentação acumulada por arquivo |
| `app/src/main/java/br/gov/sp/pcsp/launcher/GraniteEngine.kt` | +4 / −78 | motor TurboCTC e tipos compartilhados; não substituir para trazer suporte NAR |
| `app/src/main/java/br/gov/sp/pcsp/launcher/NativeDependencyManager.kt` | +9 / −162 | M usa contrato nativo v11; X usa v3; uma substituição causaria regressão do pacote do app |
| `app/src/main/java/br/gov/sp/pcsp/launcher/QairtDependencyManager.kt` | +8 / −52 | X adiciona helpers de identidade, mas não contém toda a evolução posterior de M |
| `app/build.gradle` | +2 / −8 | preservar configuração de build atual ao portar código |
| `app/src/main/AndroidManifest.xml` | 0 / −10 | X remove registros de `TextoActivity` e `PromptsSettingsActivity`; não portar esse diff |
| `MODULE-MAP.md` | +9 / −17 | mapa de X é anterior a outras adições de M; adicionar apenas linhas dos novos seams |

Há divergência real além do NAR. A unidade de entrega segura é um patch com ownership explícito, partindo de M, que usa X como fonte de trechos e testes selecionados. Uma integração do engine inteiro, dos gestores nativos, da Activity inteira, do manifesto inteiro ou de todo o mapa não está autorizada por uma tarefa pequena de consolidação.

## 3. Contratos efetivos no código

| Etapa | Contrato observado | Evidência |
|---|---|---|
| Frontend | WAV lido em 16 kHz mono; STFT NFFT 512, hop 160, 80 mels, stack 2, dimensão 160; padding reflect implementado e normalização logmel | M `GraniteNarEngine.kt:69`, `:89`, `:121`, `:131` |
| Limite por chamada | `frames=floor(samples/(2*160))`; rejeita `frames>2000`; mensagem usa ~40 s, não 37 s | M `GraniteNarEngine.kt:83`, `:486`, `:1406`; X `:1945` |
| Entrada encoder | float32 `[1,bucket,160]`, zeros no padding, uma entrada `input_features` | M `GraniteNarEngine.kt:1441`; X `:1769` |
| Saídas encoder full | `encoder_bpe_logits` e `multilayer_features` | X `GraniteNarEngine.kt:1770` |
| CTC inicial | blank `100257`; ArgMax com empate no primeiro índice, collapse consecutivo e remoção de blank; frames válidos `ceil(realFrames/4)` | M `GraniteNarEngine.kt:147`, `:169`, `:177`, `:1461`; X `:1783` |
| Projector | `multilayer_features [1,bucket,4096]` → `audio_embeds`; consumo de `floor(realFrames/5)` vetores de dimensão 2048 | M `GraniteNarEngine.kt:1468`, `:1475`; X `:1794`, `:1801` |
| Escala | divide embeddings acústicos por `EMBEDDING_MULTIPLIER`; valor documentado 12 | M `GraniteNarEngine.kt:1487`; X `:1813` |
| Slots editor | `[blank,tok0,blank,tok1,…]`, comprimento `max(2*n+1,8)` | M `GraniteNarEngine.kt:188` |
| Embeddings texto | mmap do arquivo token-major fp16; lookup e conversão apenas dos vetores usados | M `GraniteNarEngine.kt:1298`, `:1354` |
| Entradas editor do app | `inputs_embeds [1,S,2048]`, `position_ids [1,S]`; sem `attention_mask` externo nesta chamada | M `GraniteNarEngine.kt:1500`, `:1502`; X `:1844`, `:1847` |
| Saída editor M | `logits`; collapse da região textual começando em `validAudio` | M `GraniteNarEngine.kt:1502`, `:1511` |
| Saída editor X opcional | `token_ids` int64 do grafo derivado `*-argmax.onnx`; collapse dos IDs da região textual | X `GraniteNarEngine.kt:1441`, `:1862` |
| Saída de texto | BPE byte-level → bytes → UTF-8; resultado trim | X `GraniteNarEngine.kt:1901`, `:1991` |

**Implicação arquitetural:** estas chamadas não implementam geração token a token, prefill/decode causal ou KV cache. A proposta de Smart NAR deve conservar os contratos do editor bidirecional; Smart causal literal requer outro contrato/modelo e outra validação. A documentação antiga e `tools/granite/nar/export_static.py` incluem editor estático com três entradas e `attention_mask`, enquanto o app consome editor de duas entradas e S dinâmico. Não escolher o exporter só pelo nome. Inspecionar cada grafo antes de usá-lo.

`tools/granite/nar/README.md:13` descreve três entradas; `export_static.py:15`, `:87`, `:153` confirma esse exporter estático. `run_reference.py:132` chama o language model com embeddings/posições. A correspondência entre esses caminhos e o artefato concreto deve ser estabelecida por manifesto/graph inspection.

## 4. CPU, UI e produto no snapshot principal

O enum compartilhado existe em `GraniteEngine.kt:364`: CPU, `GPU_QNN` e `NPU_QNN_HTP`. Não existe enum dedicado chamado `GraniteExecutionBackend.kt`. A chamada GPU usa `libQnnGpu.so`; HTP usa `libQnnHtp.so`. Não há seleção Granite OpenCL direta ou Vulkan direta nessa rota.

Na UI, escolher NAR com acelerador selecionado força CPU (`GraniteActivity.kt:985`). O menu desabilita aceleradores para NAR (`:1024`). Isso confirma que a rota normal do NAR no produto atual é CPU, mesmo com infraestrutura QNN utilizável em debug.

As variantes oferecidas são float/fp16, int8b-blk128 e int4b-blk128; a padrão é float (`GraniteNarEngine.kt:354`, `:364`, `:376`, `:417`, `:420`). O 2-bit está registrado como reprovado e fora da UI (`:386`). Esses nomes e nomes de arquivos não provam o dtype efetivo dos nós/pesos.

O seletor apresenta fator de velocidade e qualidade dos dados históricos do editor (`GraniteActivity.kt:895`, `:896`). Os fatores 2,4× e 4,3× vêm de comentários/configuração de medições de CPU no PC, explicitamente identificadas como tal (`GraniteNarEngine.kt:294`, `:369`, `:381`). A linha “mesma qualidade” do float significa referência do modelo, não ausência de erro humano. Uma interface nova deve só exibir afirmações validadas do pipeline/aparelho/corpus aplicável.

## 5. Melhorias experimentais: pronto para portar versus condicional

“Portável” aqui significa que existe um recorte de código revisável; não significa aprovação Android neste checkout.

| Candidato | Estado de código | Próxima prova mínima e condição |
|---|---|---|
| Parser manual de vocabulário | seam isolado em X `GraniteNarVocab.kt:16`; engine o usa em X `GraniteNarEngine.kt:2027`; testes de paridade existem | portar seam+testes+mapa+chamada; comparar lista token a token com o vocabulário real de hash congelado; confirmar ganho de carga no Android |
| CTC collapse de IDs | X `GraniteNarEngine.kt:213`; testes de blanks/repetições/empates em `GraniteNarEngineTest.kt:123` | integrar como regra pura junto ao contrato ArgMax; manter ordem unique-consecutive→remove blank |
| ArgMax do editor | seleção de grafo e chave variante+contrato em X `:1424`; interpretação int64 em `:1862` | condicionado a manifesto/artefatos corretos, equivalência do ArgMax e correções de validação listadas abaixo |
| Guarda de memória de logits | seam em X `GraniteNarMemoria.kt`; chamada em engine `:1831` | útil ao contrato logits; não chamar a estimativa de pico global; incluir reservas necessárias e evidência de heap/PSS |
| CTC-only | engine evita sessões projector/editor e mmap em X `:1571`, `:1967`; debug propaga `loadLlm=!ctcOnly` (`SmokeTestActivity.kt:141`, `:200`) | perfil de produto condicionado a qualidade PT-BR; instalador ainda exige pacote completo e precisa de contrato por rota |
| Instrumentação de sessão | `GraniteNarSessionProbe.kt:49`, `:70`, `:103`; engine em X `:1227`, `:1295` | portar para diagnóstico; adicionar identidade/hash e correlacionar caminho efetivamente aberto, inclusive wrapper |
| Perfis/backend por estágio e portão pré-runtime | `GraniteNarPerfis.kt:38`, `:64`; chamada em X engine `:1597` | manter decisões experimentais explícitas; substituir restrições históricas por capacidade validada quando novos pilotos forem aprovados |
| Contexto EP OFF/GENERATE/LOAD | seam e wiring do encoder em X `GraniteNarEpContext.kt:46`, engine `:1237`, `:1372` | piloto encoder exato com cache vinculado a libs/artefatos; não é suporte completo de NPU/projector/editor |
| EXTENDED_OPT CPU | extra debug e opção em X engine `:1108` | hipótese comparativa; rodar controle BASIC com mesmo grafo/entrada, qualidade e memória; não promovida no código padrão |
| Downloader testável | X `GraniteNarDownloader.kt`; testes de transport/hash presentes | **sem consumidor em produção**: engine continua seu download próprio; integrar wiring preservando callbacks atuais e provar retomada real |
| Manifesto com contrato | campo em X `GraniteNarManifest.kt:26`; helper `contratoConfere` em `:133` | **sem consumidor de produção**: somente testes chamam helper; ligar validação à carga antes da criação da sessão |

O parser manual reproduz os mesmos dois replaces do parser antigo (`\"` e `\\`), não implementa um parser JSON geral nem corrige escapes Unicode completos. Também preserva comportamento antigo de objeto ordenado por IDs, inclusive sem exigir continuidade dos IDs. Uma melhoria de correção do tokenizer deve ser rodada separadamente da substituição por desempenho, usando tokenizer fonte e referência dos tokens; não misturar mudanças semânticas na alegação de paridade.

## 6. Riscos concretos de contrato e memória

### 6.1 ArgMax ainda tem travas incompletas

1. O grafo derivado é escolhido pelo nome e pela existência do par ONNX/data (X engine `:1443`). `GraniteNarManifest.contratoConfere` não é chamado no loader. Assim, o helper/testes não sustentam a afirmação de validação manifesto↔grafo antes da inferência.
2. X verifica dtype int64 e quantidade total S (`:1872`, `:1876`), mas não compara o shape completo. Um shape incorreto com o mesmo número de elementos passaria. Decidir o contrato exato pelo artefato e validar esse shape antes de ler.
3. X faz `buf.get(it).toInt()` antes da validação de faixa (`:1879`, `:1881`). Um int64 fora da faixa pode sofrer wrap para um Int aparentemente válido. Validar no domínio Long antes da conversão.
4. O modo ArgMax é flag mutável de singleton. Deve ser parte de configuração imutável de sessão; não só parte da seleção feita durante load.
5. ArgMax evita a cópia Java de todos os logits, mas não prova que o grafo não materializa logits internamente nem elimina custo nativo/compute. Medir ambos os espaços de memória.

### 6.2 Comentário “FloatBuffer direto” é insuficiente

M lê `OnnxTensor.floatBuffer` no encoder e no editor (`:1452`, `:1505`) e chama isso de leitura direta em comentários. X registra a descoberta histórica de que `getFloatBuffer` copia integralmente os dados (`GraniteNarMemoria.kt:7`). Não inferir zero-copy do uso de FloatBuffer; confirmar a API/bytes carregados e instrumentar alocações. A guarda de X protege especificamente a cópia de logits do editor; o encoder CTC ainda obtém seu tensor de logits por FloatBuffer, inclusive no CTC-only (`:1981`).

### 6.3 Limites dos arquivos instalados

`bucketsInstalados` exige os dois grafos encoder/projector, sem checar hashes nessa seleção (M `:238`; X `:283`). `escolhe` devolve o maior disponível se nenhum comportar a entrada (M `:227`). Um pacote com somente t200 pode carregar, mas um áudio com realFrames>200 e ≤2000 pode chegar à cópia de features para um buffer menor, gerando falha em vez de diagnóstico de bucket insuficiente. A validação deve garantir `bucket>=realFrames` antes da alocação/cópia e definir fallback/segmentação explícitos.

`packageComplete` exige todos os arquivos/todos os buckets e só testa existência/comprimento positivo (M `:753`; X `:782`); `load` admite um único bucket completo. Há duas noções diferentes de pacote utilizável. Download novo usa manifesto estrito e hash, mas os checks de prontidão/carga não constituem prova de integridade observada de todos os arquivos nessa execução. Capturar manifesto e hashes na campanha; para uso normal, projetar recibo de instalação validado e política de invalidação sem rehash de gigabytes a cada run.

CTC-only ainda passa por `arquivosComuns`, com pesos projector, editor ONNX/data e embeddings (X `:719`, `:1532`), e por bucket completo encoder+projector (`:1540`). É uma economia de execução, ainda não economia completa de armazenamento/download.

## 7. Buckets, sessões, reutilização e cancelamento

| Tema | Fato no código | Ação proposta |
|---|---|---|
| Buckets | 200,400,800,1200,1600,2000; menor instalado que comporta quando há um | manter bucket observado e validar faixa antes de run |
| Aceleração | somente t200/t400 passam a política histórica (M `:250`; X `:295`) | não promover t800+ por nome/shape; reabrir só com hipótese e piloto pequeno |
| Load acelerado | default de `warmupBucket` é 2000 (M `:1258`, X `:1501`); debug envia 0 por padrão | mudar contrato explicitamente para 0/bucket planejado; testar caminho de chamadores sem extra |
| Load CPU | chama createSessions sem repassar warmupBucket (M `:1336`; X `:1651`) | carga CPU preaquece só editor; criar simetria para separar criação encoder/projector da inferência |
| Reinício | toda chamada load faz release (M `:1267`; X `:1522`) | medir load/unload separadamente; não alegar reutilização entre cargas |
| Mesmo batch | uma carga e transcrição sequencial dos arquivos; release no fim (M Activity `:1349`, `:1383`, `:1420`) | benchmark de batch pode reutilizar mesmo bucket, mas troca de bucket recria sessão |
| Encoder/projector | chave de reutilização é somente bucket (M `:1158`, `:1177`; X `:1366`, `:1392`) | chave futura inclui artefato/hash/backend/opções/contexto/strictness; provar reuse com session_id |
| Editor | M chave somente variante; X variante+contrato (M `:1209`; X `:1427`) | acrescentar identidade/backend/opções efetivas e configuração imutável |
| Número de sessões | troca de bucket fecha a anterior; evita seis encoders residentes | manter orçamento explícito; cache LRU só após medição que justifique memória |
| Cancelamento | Activity marca flag, checa antes/depois de operações; engine não recebe cancel token/RunOptions | planejar cancelamento por etapa e término ORT compatível; não prometer interrupção imediata |
| Destruição | Activity chama release incondicional no onDestroy (M Activity `:369`; X `:368`) enquanto trabalho ocorre em Thread | auditar ownership do worker/sessões para impedir close concorrente com run |

Em `transcribeFile`, a política de bucket pode forçar CPU e logar essa decisão (X `:1753`), mas `lastLoadedBackend` continua o rótulo da carga. O editor usa `sessBackend` e flags, não necessariamente `backendDaVez` (`:1760`). O relatório precisa observar cada estágio de cada áudio, inclusive fallback, em vez de derivar hardware de um único enum global.

O paralelismo de `GraniteActivity` é utilizado na preparação de mídia. Não foram encontrados ajustes explícitos de threads intra/inter-op, execution mode, RunOptions ou término ORT no engine NAR. Um teste de paralelismo deve inspecionar as opções efetivamente aplicadas e não confundir quantidade de conversões FFmpeg com threads de inferência.

## 8. Segmentação, VAD e definição de tempo

M `GraniteActivity.kt:1502` prepara cada arquivo/intervalo; `:1546` aplica VAD quando selecionado; `:1776` interpreta estatísticas que incluem quantidade de segmentos do filtro. A saída é **um WAV filtrado**, devolvido em `:1794`. Essa estatística não implementa transcrição/recomposição de vários chunks NAR.

A transcrição chama o motor uma vez por arquivo preparado (`:1393`). O motor lê o WAV inteiro e calcula todas as features antes de recusar frames>2000. Não há nessa rota produção um planejador automático de chunks, ledger de cobertura por amostras, overlap, seam matching ou recomposição temporal. O ensaio pessoal de 59 s por segmentos externos não prova suporte automático do produto.

Para uma implementação futura, propor seam puro de planejamento e cobertura, com `[start_sample,end_sample)` congelados, hash do WAV preparado, cauda incluída, política de overlap e evidência por segmento. CPU/GPU/NPU recebem os mesmos segmentos na comparação de backend. Não deduplicar texto globalmente, porque a repetição pode ser fala legítima. VAD/compactação exigem manifestos próprios, pois alteram entrada e duração efetivas.

M `GraniteActivity.kt:2398` calcula “tempo de inferência” como tempo total menos carga. O total começa antes da preparação e termina depois do release e da remoção temporária (`:1420`). Portanto esse campo inclui preparação/VAD/overhead e criação tardia de encoder/projector, não só inferência. “Velocidade x tempo real” usa duração original/intervalo e tempo total (`:2401`), podendo diferir da duração enviada após VAD. Conservar a estatística de experiência do usuário, mas renomear/decompor campos: preparação, validação/identidade, criação de sessão, primeira inferência, inferências quentes, recomposição/gravação e release; registrar duração selecionada e duração efetiva separadas.

## 9. Nativos e contexto EP

M `app/build.gradle:106` exclui `libonnxruntime.so` e `libonnxruntime4j_jni.so` do APK. A dependência Java declarada é `onnxruntime-android-qnn:1.29.0` (`:161`). O contrato de distribuição usa `NativeDependencyManager.COMPONENT_VERSION=11` (`:23`); isso não prova que são esses bytes que estão mapeados no telefone. X usa v3 e deve ser mantido apenas como referência histórica.

M ativa o diretório instalado e registra caminhos no classloader (`NativeDependencyManager.kt:214`, `:236`). O engine faz `System.load` por caminho completo para as duas bibliotecas ORT. QAIRT é pacote SIG v1 (`QairtDependencyManager.kt:36`), não versão Qualcomm observada.

FastRPC público é declarado no manifesto (`AndroidManifest.xml:35`, `:38`), carregado por nome antes do stub (`QairtDependencyManager.kt:343`, `:379`) e `ADSP_LIBRARY_PATH` é configurado por `Os.setenv` (`:355`). Preservar essas correções; não copiar libs de vendor para diretório privado. A remoção do `TelemetryInitializer` mantém ORT sob carregamento controlado (`AndroidManifest.xml:42`).

X EP context possui estados distintos OFF/GENERATE/LOAD. GENERATE recusa destino ocupado; LOAD valida sidecar, wrapper e binários, abre wrapper com geração desligada. Só encoder acelerado usa contexto (`GraniteNarEngine.kt:1370`); projector/editor permanecem OFF. O seam inclui hashes de grafo, dados externos, wrapper/binários, opções, SoC, HTP e pacote QAIRT. São boas bases para continuar o piloto, não prova de benefício ou de compatibilidade com outro runtime.

Limitações a resolver antes de confiar no cache em produto:

- `GraniteNarEpContext.ORT_VERSAO="1.29.0"` (`:41`) é constante vinculada ao build Java, sem hash das libs ORT carregadas. Incluir identidade de runtime efetiva.
- `installedLibraryFiles` lista bibliotecas obrigatórias **presentes** (`QairtDependencyManager.kt:143`), e engine faz hash dessa lista (`GraniteNarEngine.kt:1153`). Isso não prova quais foram carregadas/mapeadas. Registrar caminhos efetivos dos mappings e hashes correspondentes, incluindo ORT/FastRPC quando relevantes.
- `debugPrecisaoPacote` é string do intent, não inspeção de quantização do grafo (`GraniteNarEngine.kt:1193`). Conservar rótulo solicitado separado de dtype/atributos observados.
- O parser de referências externas reconhece codificação específica de protobuf, nomes ASCII com comprimento de um byte (`GraniteNarEpContext.kt:189`, `:216`). Ele recusa formatos não suportados; testes/gate devem tornar a recusa explícita. Um runtime/export novo exige inspeção apropriada, não presunção de que esse parser é universal.
- Instrumentação pré-sessão registra o modelo original antes de escolher o wrapper (`GraniteNarEngine.kt:1227`, `:1237`). Registrar adicionalmente `arquivoSessao`, modo e identidade efetivamente usados.
- Depois de `env.createSession`, etapas de selo podem lançar antes do return (`:1301`). Garantir fechamento de `created` se validação/hash/escrita do sidecar falhar.
- Hash de pesos grandes e binários é trabalho real de carga. Os logs separam identidade/validação; incluir esse custo no total e avaliar recibos de instalação imutáveis em produto, preservando rigor dos ensaios.

## 10. Protocolo debug: reutilizável, porém ainda incompleto

M tem `GraniteNarBenchmarkProtocol.VERSION=1` e eventos start/load/inference/error/end. X adiciona configurações solicitadas, rota CTC, contrato observado do editor, reload_per_run, perfil nomeado, profiling e modo de contexto. O benchmark roda em Activity debug sob UID do app; isso é caminho apropriado para smoke, sujeito à identidade própria do APK.

| Lacuna observada | Evidência | Correção necessária |
|---|---|---|
| Pattern de entrada defasado | Protocol `:16` exige `effective_frames`; M engine emite samples/frames, X `:1727` também | fixture do log real do engine e parsing estruturado do bucket/frames |
| CTC não tem coleta equivalente | X engine emite `NAR entrada (ctc)` em `:1943`, etapa em `:1978`, sem total final | eventos próprios por rota com tempos e dimensões obrigatórios |
| Backend “efetivo” de inference é solicitado | X Smoke `:306` grava parâmetro backend recebido, não observação por estágio | coletar providers/partições/runtime por sessão; invalidar se exigência de hardware não ocorreu |
| Logs internos sem run_id | X Smoke logger `:325` grava `NAR_LOG` genérico | incluir run_id/pid/session_id/índice na origem; ainda filtrar captura por processo correto |
| Sem identidade APK/grafo/áudio completa no evento | X start tem bytes áudio e versionName (`:112`, `:113`), não SHA desses arquivos | manifesto por tentativa com hashes completos e commit/diff; registrar binding no evento |
| Sem session_id estável de uso | SessionProbe numera criações; inference não vincula sessão utilizada | sessões têm ID imutável; eventos run referenciam IDs para provar reuse |
| Perfil não impõe encoder solicitado | X Smoke `:72` aplica flags projector/editor, mas não usa `plano.encoder` para resolver/validar backend | rejeitar inconsistência perfil↔backend antes do runtime |
| warmup_bucket solicitado não garante carga CPU do bucket | X load CPU `:1651` omite argumento | planejar bucket e provar sessão/shape criado antes de marcar medida quente |
| PSS só antes/depois | X Smoke `:278`, `:313`, `:314` e start/load/end | amostragem contínua com intervalo/coverage; não chamar amostras de pico |
| Repetições não alternam braços | warmup e measured são loops de um candidato | orquestração ABBA/BAAB equilibrada e ledger com primeira tentativa/retry |

`reload_per_run` em X propaga novamente `warmupBucket` e `loadLlm` (`SmokeTestActivity.kt:193`), e o contrato do editor faz parte da chave; são correções úteis das regressões históricas, mas devem ser protegidas por configuração observada. O `Collector.sessionLoadMs` também pode capturar criação tardia durante inference; esses tempos precisam ser publicados em vez de desaparecer em “inferência”.

Instrumentação de atribuição ao hardware deve distinguir: sessão abriu; nós foram atribuídos ao provider; run executou e retornou; pipeline terminou; qualidade passou; total melhorou. `strict=false` não satisfaz uma hipótese de HTP integral. Estágios explicitamente CPU de N1 são CPU planejada, e não fallback oculto.

## 11. Testes existentes e verificação da integração

| Recorte | Testes disponíveis | Limite do que cobrem |
|---|---|---|
| Frontend/CTC/interleave/buckets/variantes | `GraniteNarEngineTest`, `GraniteNarBucketPolicyTest` | regras e parsing; não confirma artefato instalado/hardware |
| Vocabulário | X `GraniteNarVocabTest` | paridade com parser antigo em fixtures; comparar vocab real separadamente |
| Manifesto/contrato | M/X `GraniteNarManifestTest` | helper; consumer ausente do novo contrato precisa integração testável |
| Memória | X `GraniteNarMemoriaTest` | aritmética/folga; não pico real Android |
| Perfis/portão | X `GraniteNarPerfisTest` | política histórica pura, não compatibilidade de novos backends |
| Contexto EP | X `GraniteNarEpContextTest` | colisão, sidecar, hashes e paths; sem prova runtime de generate/load |
| Sessão/erro externo | X `GraniteNarSessionProbeTest` | diagnóstico de arquivos e exceções |
| Download | X `GraniteNarDownloaderTest` | seam ainda não wired no engine; não prova fluxo atual |

Antes de aceitar código, executar unitários focais adequados, suíte contratual silenciosa do harness, staged/mapa/KDoc e os gates finais do app previstos no projeto: `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:assembleDebug`. Registrar resultados/exit code do patch próprio. Não contornar hook por arquivo alheio; isolar ownership em checkout adequado. Esta rodada documental não modifica `RemoteSttActivity.kt` nem pacote nativo. Release só de APK não requer regenerar ZIPs se o contrato nativo permanecer igual; qualquer troca do runtime distribuído muda essa avaliação.

## 12. Ordem de consolidação sugerida ao especialista

Esta sequência é proposta técnica para reduzir incerteza, não execução já iniciada:

1. Congelar identidades e tratar o parser manual como primeira melhoria pequena: seam, testes, mapa e chamada preservando semântica. Validar paridade do vocab real sem transferência externa de áudio pessoal.
2. Corrigir o protocolo para observar rota, contrato, bucket, sessão, hashes e backend por estágio; fixtures devem usar logs reais atuais, inclusive CTC e fallback. A campanha seguinte depende dessa integridade.
3. Consolidar ArgMax com gate de manifesto/grafo antes da sessão, dtype/shape/Long range, equivalência de IDs/CTC e release em exceções. Benefício deve separar heap Java, PSS e memória nativa; manter caminho logits de controle.
4. Consolidar identidade imutável das sessões e carga planejada por bucket, com custo de primeira inferência e reuse real; fechar sessões em dono único e definir cancelamento.
5. Rodar CTC-only como candidato de qualidade, sem fingir que o instalador já é compacto. Decidir pacote por rota somente após qualidade/benefício.
6. Continuar pilotos HTP/contexto só para estágio/artefato exatos, preservando first attempts e atribuição estrita quando exigida. GPU OpenCL/Vulkan precisam runtime/piloto próprios.
7. Implementar segmentação com cobertura antes de prometer áudios longos no produto; qualidade e recomposição são gates independentes do backend.
8. Revisar UI/download/relatório com evidências do pipeline Android, preservando a evolução atual de M e sem importar as regressões históricas da worktree.

Conclusões locais podem ser invalidadas por avanço do HEAD. Antes de uma ordem que edite/instale, reconfirmar commit, diff, ownership, estado do executor e telefone, sem repetir provas íntegras já vinculadas ao mesmo artefato/configuração.
