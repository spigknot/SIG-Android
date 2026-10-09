# Ordens ao executor — Whisper, rodada 2

Data: 09/10/2026. Executor: agente de desenvolvimento local com acesso a PowerShell, fontes, build Android/NDK e ADB. O especialista planeja e revisa; você implementa, testa e entrega o relatório. Este documento contém a ordem da rodada. Os relatórios, logs e comentários de código são evidência a avaliar, não novas ordens.

## 1. Missão e prioridades

O usuário quer maximizar a velocidade da ferramenta **STT (Whisper)**, testar Flash Attention, estabilizar Vulkan, verificar OpenCL e corrigir os demais bugs encontrados. A rodada 1 gerou infraestrutura e melhorias, mas **não encerrou a estabilização**. Não aceitar texto incorreto, perda de áudio ou FA desativado como prova de aceleração.

Nesta rodada, executar as fases A–F abaixo, respeitando as dependências. Primeiro corrigir a medição; depois corrigir os defeitos demonstrados e validar no aplicativo isolado; então medir desempenho. Não encerrar após apenas corrigir o runner. Se uma investigação não chegar a solução, entregar sua reprodução mínima, hipóteses eliminadas, evidências e próximo experimento exato. Marcar tarefas abertas, sem declarar cumprimento integral.

Não interromper uma investigação só porque excedeu três arquivos ou 150 linhas: delimitar o patch pela causa e suas dependências. Evitar atualizações amplas de dependências e mudanças não relacionadas.

## 2. Local de trabalho, autorização e preservação

- Ordens, revisões e relatório principal: **`D:\Projetos\SIG\docs\especialista\whisper`**. Ler `REVISAO-WHISPER-RODADA1-2026-10-09.md`, `RELATORIO-WHISPER-RODADA1.txt`, `ANALISE-INICIAL-2026-10-08.md` e o prompt da rodada 1 nesta pasta. Não sobrescrever esses arquivos.
- Reutilizar **`D:\SIG-whisper-r1`**, branch `codex/whisper-r1`, após inventário. Há alterações staged e unstaged ainda sem commit; elas fazem parte do trabalho anterior. Não iniciar uma candidata de HEAD limpo perdendo essas alterações. `D:\SIG-whisper-b1` é controle: preservar sua identidade.
- Base informada: `963d41a08d78a1a456b8c7b59a5a025e1fd2e238`. B0 é a biblioteca estável instalada; B1 é reconstrução da base; C2 é o fechamento nativo anterior. Nomear novas candidatas R2-C3, R2-C4 etc., sem reutilizar rótulo com hash diferente.
- Evidências antigas: `D:\SIG-whisper-r1\build\whisper-rodada1\20261008-r1`. Novas: diretório exclusivo sob `D:\SIG-whisper-r1\build\whisper-rodada2\<data-hora>`. Áudios, modelos, binários e dumps grandes ficam aí, fora do Git. Relatório contém caminhos absolutos e hashes.
- Ler `AGENTS.md`, `MODULE-MAP.md` e `native-dependencies\README.md` do checkout. Preservar os trabalhos de outros agentes. Não resetar, limpar, cherry-pickar ou editar áreas não relacionadas para fazer os gates passarem.
- Alterações autorizadas: WhisperActivity/Native e seams focais; `whisper_jni.cpp`, WAV, árvore **`app\src\main\cpp\whisper.cpp\ggml`**, testes, runner, configuração estritamente necessária de build/ensaios e MODULE-MAP. A árvore GGML do llama é distinta e fica fora do escopo.
- Não editar `NativeDependencyManager.kt` para apontar pacote publicado para candidata; não mudar versão/URL/hash público, não assinar, publicar, fazer push nem promover APK/ZIP. Testes de build e instalação **debug isolada** estão autorizados. Aprovação de release ficará para outra etapa.

### Dispositivo obrigatório

PJA110 autorizado continuamente em **`100.114.88.45:5555`**. Usar `C:\adb\adb.exe` e serial explícito:

```powershell
& C:\adb\adb.exe connect 100.114.88.45:5555
& C:\adb\adb.exe -s 100.114.88.45:5555 get-state
& C:\adb\adb.exe -s 100.114.88.45:5555 shell getprop ro.product.model
```

Reconectar se necessário. USB `1164a04` é o mesmo telefone, não um segundo dispositivo. Não selecionar implicitamente outro aparelho. Usar corpus sintético/controlado e PCM injetado; não captar áudio pessoal incidental. Limitar escrita a `/data/local/tmp/whisper-bench-r2` e ao pacote exclusivo de teste. Modelos existentes podem ser lidos/copiados sem modificar os originais. Não matar, desinstalar, substituir ou limpar dados do SIG do usuário; não usar `adb kill-server`, reinicialização ou mudança global de configuração para resolver um ensaio. Verificar ocupação do telefone e evitar disputá-lo com outros trabalhos.

## 3. Correções obrigatórias da interpretação anterior

Incorporar essas conclusões ao seu novo relatório; conferir as linhas no snapshot, pois podem mudar:

1. `core_load()` do harness deixou `gpu_device=0` para qualquer GPU. O log `runner-out\final\cells\f3-C01-opencl-facmp-C2.attempt1.stderr.log` diz **using Vulkan0 backend**. O crash dessa célula não prova falha OpenCL. O caso JNI/OpenCL que retornou vazio é independente.
2. C2 força FA-off em todas as GPUs. W11/W12 estão mitigados, não corrigidos pela causa raiz. C2 Vulkan FA-on com WER próximo de 100% continua FAIL de qualidade, mesmo com exit 0.
3. A cadeia observada no Vulkan é: criação de pipeline FA falha → `pipeline_failures` cresce → `supports_op` recusa tudo, inclusive `NONE` → agendador aborta com tensor já residente em `Vulkan0`. Investigar essa cadeia e a corrupção FA-off separadamente.
4. O controlador verifica geração fora das operações nativas e não serializa o load direto da Activity com release/transcribe/status. Há riscos de release/cancel/callback de sessão antiga afetarem a nova.
5. Stop-live ainda chama “finalizada” após timeout com worker vivo. A leitura de AudioRecord ainda divide thread com a inferência. Restaurar **W04 = captura e inferência na mesma thread**; não redefinir IDs antigos.
6. B0/C1 têm evidência de mojibake cp437; C02 não foi recuperado integralmente. W14 e as conclusões de regressão baseadas nesses números são inconclusivos até recolher controles brutos.
7. JNI simulado não prova ART, thread attachment, UTF real nem UI. C09 como WAV não prova live. Não usar essas substituições como aceite final.

## Fase A — congelar a origem e consertar a prova

### A1. Inventário e snapshots

Antes de editar, registrar branch, HEAD, status, diffs staged/unstaged e arquivos adicionados. Gerar snapshot recuperável **com conteúdo** de todos os arquivos da rodada anterior; lista de untracked sozinha não basta. Preservar também os arquivos ignorados necessários aos ensaios. Manifesto com hash e tamanho de fontes relevantes, harness, libsig_whisper, libomp, modelo, áudio e ferramentas de build.

O patch citado no `.md` antigo não contém o fechamento inteiro. Para o novo snapshot, incluir staged, unstaged e arquivos novos do escopo. Não usar `git add .` no checkout compartilhado. Identificar explicitamente quais gates correspondem a quais hashes/fontes.

### A2. Backend e parâmetros efetivos

Corrigir `--mode core` para enumerar os dispositivos GGML e selecionar o backend pedido pelo registro/nome/tipo, usando o mesmo significado de índice GPU que a versão local do Whisper espera. Conferir `can_initialize_gpu_backend()` e `whisper_backend_init_gpu()`: não presumir que índice global de dispositivo é índice de GPU, nem que Vulkan/OpenCL sempre têm a mesma ordem.

Se o backend solicitado não existir, falhar explicitamente ou registrar fallback permitido com razão; nunca rotular Vulkan como OpenCL. Instrumentar seleção e inicialização do contexto e verificar nos logs o backend real. Acrescentar teste do resolvedor com ordens de enumeração trocadas, CPU entre dispositivos, GPU ausente e múltiplas GPUs. Fazer smoke CPU/Vulkan/OpenCL no PJA110, em processo novo por perfil.

Registrar separado: backend solicitado, backend de pesos/contexto, backend que executa cada classe de operação, FA solicitado, FA configurado e contagem de nós FA realmente executados por backend. Um bool de `cparams` não prova execução. Se não houver medição, escrever UNKNOWN, sem inferir “GPU FA”.

Registrar **todos** os parâmetros relevantes de `whisper_full_params`: estratégia, beam, best_of, threads, temperaturas/fallback, thresholds, idioma, prompt/contexto, timestamps, VAD, padding e processors. Usar uma configuração comum nos controles core/JNI; comparar primeiro o mesmo caminho antes de comparar caminhos diferentes. Validar compatibilidade de headers/ABI usados pelo harness com cada biblioteca B0/B1/candidata. Não passar structs de revisão incompatível por dlsym.

### A3. Bytes, tempos e status

Capturar saída em arquivo no dispositivo e fazer pull binário, como no ensaio final, sem decodificar e regravar pelo console PowerShell. Preservar bytes originais e validar UTF-8 estritamente. Se texto nativo for inválido, guardar bytes em hex/base64 ao lado e classificar a falha; não usar substituição silenciosa como reparo da evidência.

Testar o runner com acentos portugueses, caracteres fora do BMP, NUL/control chars em fixtures, JSON inválido, stdout misturado com log, exit 0 + no_speech para áudio com fala, crash, timeout, transporte caído e recuperação. Tentar no máximo uma repetição de transporte por execução; crash do motor permanece registrado e não vira falha de ADB. Distinguir SIGABRT=6/exit134 de SIGPIPE=13/exit141 com o log bruto.

Corrigir classificação: `execution_status`, `semantic_status` e `task_status` distintos. No áudio com fala conhecida, vazio ou texto incoerente é FAIL semântico. No silêncio, no_speech pode ser esperado. Não calcular velocidade útil para áudio incompleto. Tempos ausentes são null, não zero; o parser deve incluir decoder individual e batch quando a lib os emitir e explicar o escopo, sem somar sobreposições indevidas.

Recolher **B0, B1 e C2** CPU FA-off em C01 e C02, 3 repetições por controle, mesmos parâmetros/caminho/hash de corpus. Preferir JNI para confronto com B0 quando a ABI core não for comprovada. Só abrir investigação de toolchain/build W14 se a diferença persistir em dados íntegros; não repetir builds inteiros por uma diferença originada no transporte. Recalcular WER/CER com normalização documentada e exemplos de diff, sem alterar o corpus para favorecer uma lib.

**Saída de A:** runner testado, seleção efetiva provada, controles íntegros e mapa das conclusões anteriores válidas/inválidas. Não iniciar matriz longa enquanto essa saída falhar.

## Fase B — ownership, cancelamento, resultado e ART

### B1. Construir testes que exponham as brechas

Usar latches/barreiras, não sleeps probabilísticos. Cada teste relevante deve falhar no C2 e passar na correção:

| Interleaving | Invariante exigida |
|---|---|
| Release A passa na verificação, espera lock; B inicia/carrega antes da liberação | A nunca libera o contexto pertencente a B |
| Inferência/load A ativo; B começa; cancelamento A atrasado chega | A termina cancelada quando solicitado; B não é cancelada e não limpa prematuramente o cancelamento de A |
| Callback A já foi postado no looper; B começa ou A é destruída antes do post rodar | Evento A não altera UI, relatório ou progresso de B, nem Activity destruída |
| Transcribe A retorna; outra execução altera status antes de A lê-lo | Texto e status entregues a A pertencem ao mesmo job |
| Activity A destruída e B recriada com outro modelo/backend | Cache e contexto pertencem ao mesmo proprietário; nenhum uso do modelo errado |
| Worker antigo falha ou termina no mesmo instante do cancelamento | Um único terminal por job, sem apagar estado da sessão nova |

### B2. Corrigir o contrato completo

Fazer load, chave de cache, inferência, leitura do resultado/status e release obedecerem ao ownership **no ponto em que operam no contexto**, após qualquer espera. Uma verificação de geração antes do mutex não basta. Preferir um coordenador compartilhado que serialize as operações longas e mantenha job/session IDs, ou handles nativos isolados se justificável. Evitar apenas trocar locks locais por outro lock na UI.

O cancelamento precisa ser rápido e direcionado ao job ativo; não pode ficar atrás da inferência na mesma fila. Nova sessão não pode apagar a intenção de cancelar uma inferência anterior ainda em execução. Nenhuma Activity antiga pode cancelar ou liberar contexto de outra. Invalidar o proprietário no teardown e conferir a validade também **ao executar** o post na UI.

Entregar texto/status/erro como um resultado por chamada, ou fazer um snapshot protegido no coordenador antes de permitir outro job. Se alterar JNI, preservar compatibilidade com a lib publicada: detectar capacidade/versionamento uma vez e definir fallback legado explícito. Testar APK novo com B0 e candidata; não criar caminho que depende silenciosamente de símbolo ausente. A inicialização da biblioteca e chamadas longas devem sair da thread principal.

### B3. Ensaio real isolado

Criar variante/aplicativo **debug exclusivo**, com applicationId distinto, para executar o mesmo WhisperNative, controlador e a WhisperActivity relevante em ART. O isolamento já está autorizado; não declarar UI/live SKIP por receio de reinstalar o produto. Verificar pacote, authorities/providers, armazenamento e caminhos antes do install. A variante não pode reutilizar dados, provider ou pacote do SIG instalado.

Carregar a biblioteca candidata e libomp de diretório privado do sandbox, com caminho/hash registrado. Usar seam/loader apenas de debug, excluído do release. Não falsificar NativeDependencyManager de produção nem desabilitar a verificação dos pacotes do usuário. Conferir que o APK de produto não recebeu hooks, fixtures nem lib diagnóstica.

Executar em ART: arquivo curto; fechar/reabrir durante load e inferência; cancelamento antes/durante load, espera por lock e inferência; troca de modelo/backend; recriação da Activity; callbacks enfileirados e exceção Java no callback; texto iniciado por “Erro:”/“Cancelado:”; silêncio, arquivo inválido e recuperação para job bom. No sandbox, provocar callback por thread nativa de teste para verificar attach/detach e GlobalRef com CheckJNI quando disponível. Hook deve existir somente no build de ensaio; contabilizar refs/attachments e documentar se callbacks reais usam threads estrangeiras.

Investigar a conversão **UTF-8 padrão ↔ string Java**: o fake `NewStringUTF` aceita qualquer byte, enquanto ART usa contrato Modified UTF-8. Testar acentos, emoji, NUL e texto inválido separado dos tokens brutos. Distinguir erro de motor, concatenação de tokens e conversão/serialização. Não remendar W13 apenas substituindo caracteres ilegíveis.

Medir heartbeat de UI e término real do worker. Nenhum bloqueio determinístico >500 ms em sair/cancelar/cleanup; latência de cancelamento da inferência é métrica separada. Não esconder worker vivo por timeout.

**Saída de B:** regressões antes/depois, contrato de ownership explicado e prova ART/UI no sandbox. Logs fictícios não substituem a prova.

## Fase C — recuperar Vulkan e localizar corrupção FA-off

### C1. Minimizar a recuperação após falha de pipeline

Com harness corrigido, reproduzir FA Vulkan sem a mitigação em build diagnóstico isolado, registrando backtrace simbolizado e shader/shape/driver. Usar a lib exata com símbolos. Conferir que a sequência observada realmente é a mesma: erro de pipeline → `pipeline_failures` → `supports_op=false` para `NONE` → tensor residente → aborto.

Criar regressão determinística de falha de compilação com hook somente de teste, além da reprodução real no PJA110. Investigar a política correta para operação sem cálculo em tensor residente e para fallback dos nós de cálculo. Alternativas: capacidade por pipeline/op, preflight antes da alocação, ou reconstrução explícita de contexto em backend válido. Selecionar com evidência e explicar buffers/cópias/cleanup.

Não remover assert do agendador, retornar true indiscriminadamente em `supports_op`, despachar pipeline null nem continuar com resultado parcial. Uma recusa antecipada pode ser recuperação correta, mas não é FA GPU funcionando. Fallback CPU precisa ser identificado e não contado como aceleração Vulkan.

Testar: falha antes da carga de pesos, após pesos residentes, durante seleção de operação; erro explícito/recuperação sem crash; transcrição controlada correta depois; novo load no mesmo processo; load/free repetidos. Contador/capacidade cacheados não podem contaminar outro contexto sem diagnóstico.

### C2. Localizar a primeira divergência FA-off

Reproduzir C01 em CPU, OpenCL real e Vulkan real, FA-off, mesmos parâmetros; inspecionar bytes/tokens **antes de JNI**. Se só a conversão divergir, corrigir o transporte e repetir. Se o motor já produzir tokens incoerentes, continuar investigação numérica.

Obter comparação controlada de mel/encoder/saída do encoder e logits do decoder com **entradas e tokens fixos**, em build diagnóstico. Localizar a primeira camada/operação divergente e minimizar em teste de operador GGML com shapes, strides, types, offsets e buffers do Whisper. Priorizar IM2COL/conv, matmul, softmax, casts, cópias CPU↔GPU, KV e views segundo a divergência observada, sem escolher o kernel por suposição.

Testar no operador mínimo e depois C01: V0 versus fusion-off, graph-opt-off, FP16 permitido e preferência de memória removida. V5/FP16 não foi coberto na matriz final anterior. Cada perfil usa processo novo e ambiente antes da primeira enumeração; “0” não desliga variável lida por presença. Alterar um eixo por comparação e registrar ambiente efetivo. Não repetir a matriz grande de perfis já falhos sem nova hipótese sustentada.

Corrigir a causa localizada com regressão do operador e transcrição. Verificar também efeitos na build x86_64/CPU. Proteções específicas de driver precisam usar identificação comprovada; não generalizar automaticamente uma falha Adreno 740 para todas as GPUs.

**Saída de C:** correção da recuperação demonstrada e correção da corrupção, ou primeiro ponto de divergência com reproducer mínimo e próximos testes precisos. FA-off incorreto impede aceitar Vulkan.

## Fase D — Flash Attention e OpenCL reais

### D1. Prova numérica adequada

Usar testes de GGML existentes quando compatíveis, ou acrescentar caso focal para atenção com entradas fixas e seed documentada. Comparar FA com referência não fundida (QK, escala/máscara, softmax, V), mantendo precisão e semântica correspondentes. Cobrir shapes reais do tiny/base: dimensões 64 e demais encontradas, encoder longo/padding, decoder q pequeno, self/cross, cache e layouts/strides usados. Incluir shapes suportados e recusados por backend; não depender só de um tensor artificial contíguo.

Conservar os **tensores completos** ou arquivo verificável com todos os elementos e diff. Medir NaN/Inf, max/mean de erro absoluto e relativo, tolerância e número de elementos. Fixar tolerâncias antes da candidata, calibradas pela precisão e por uma referência de maior precisão quando viável; não afrouxar após falhar sem justificar matematicamente e conservar a falha. Somas, head/tail e argmax isolados são diagnóstico parcial, não equivalência completa. Comparações de logits de decodificações livres com tokens diferentes são inconclusivas.

Registrar em qual backend os nós FA rodaram. Recusa e fallback são resultados válidos de capacidade, mas não prova de FA GPU. Executar CPU, Vulkan e OpenCL selecionados corretamente. A prova numérica é separada da prova de WER e da medição de velocidade.

### D2. OpenCL: vazio com fala e compilação

Reproduzir o caso **JNI/OpenCL** de fala conhecida que retornou vazio com FA-on, em build diagnóstico. Conferir backend efetivo, parâmetros, código de retorno, logits, segmentos, erros OpenCL e duração realmente processada. Diferenciar no_speech legítimo de grafo incompleto ou falha de operação. Testar FA-off como controle e obter o primeiro ponto divergente.

A auditoria anterior cita upstream `d1be6fde` para compilação lazy e guardas Adreno. Verificar o **SHA completo e fonte primária**, os shapes/tipos/driver que a guarda realmente cobre e suas dependências antes do backport. Não apresentar esse port OpenCL como correção de shader Vulkan. Não inventar que toda a família E031 foi corrigida por uma guarda cujo comentário trata outro compiler/shape.

Se a inicialização compila FA que não será usado, avaliar compilação lazy e tratamento explícito de falha. Uma falha de compilação não pode terminar o processo; uma guarda tardia após o compilador já ter falhado também não resolve. Portar somente o necessário e testar init FA-off, FA-on suportado, FA-on recusado, compilação falha injetada, libera/recarrega e coexistência dos backends. Kernels binários Adreno são opção separada, exigindo compatibilidade demonstrada.

### D3. Substituir a mitigação por capacidade observável

A guarda global da C2 é temporária. Se houver caminho FA correto e estável, habilitá-lo nas condições comprovadas. Se o driver não suportar uma operação, recusar/fazer fallback com motivo preciso e configuração efetiva visível, preservando a transcrição correta. A UI deve explicar quando a opção solicitada não foi aplicada; log oculto sozinho não basta para o usuário entender o resultado.

Todo bypass de proteção, perfil por ambiente ou injeção de falha usado nas provas deve ficar restrito à build diagnóstica. A candidata de produto conserva validação de capacidade. Não habilitar automaticamente FA GPU apenas porque deixou de abortar.

**Saída de D:** capacidade por backend/driver/shape, prova numérica, WER e contagem real de nós FA. Dizer claramente se conseguimos FA na GPU ou somente recusa segura/fallback.

## Fase E — áudio ao vivo, finalização e robustez WAV

### E1. Captura desacoplada da inferência

Criar seam de fonte PCM e fila/coordenador de live que preserve o fluxo: uma thread drena a fonte continuamente, outra processa a inferência. Fonte sintética injetada deve atravessar o mesmo caminho de chunks, fila, cancelamento e agregação da ferramenta real; um benchmark de WAV isolado não satisfaz essa fase.

Contabilizar amostras produzidas, capturadas, enfileiradas, processadas, silêncio descartado e perdas. A soma deve fechar exatamente; todo descarte deve ter categoria explícita. Testar C09 e fonte em tempo real com inferência mais lenta que a produção, fila cheia e parada no meio do chunk. Não bloquear a captura aguardando inferência nem deixar fila sem limite consumir RAM indefinidamente. Escolher armazenamento temporário/fila limitada com política explícita; qualquer sobrecarga deve aparecer na UI/resultado, sem omissão silenciosa. Não prometer tempo real quando RTF>1.

Preservar ordem e posse dos buffers. Fechamento normal para de capturar, drena o que já foi aceito, processa o último trecho e só anuncia “finalizada” após o worker terminar. Cancelar/abandonar é estado distinto e deve invalidar callbacks. Se houver timeout, manter indicação de término pendente/falha; não apagar a referência ao worker vivo nem permitir concorrência indevida.

Testar últimas amostras de chunk curto, 12 frases numeradas sem perda/duplicação sistemática, stop normal, cancel, sair da tela e reiniciar. Concatenar PCM processado e comparar hash com a entrada que deveria ser preservada; conferir qualidade por trechos e fronteiras separadamente. Não exigir transcrição perfeita do modelo para provar contagem de áudio, nem usar contagem perfeita para declarar qualidade textual aprovada.

### E2. Completar validação WAV e resultado

Definir o contrato RIFF/PCM16 mono16k e acrescentar testes antes/depois para: tamanho RIFF inconsistente; cabeçalho final de chunk de 1–7 bytes; chunk/padding truncado; data PCM16 de tamanho ímpar; fmt/block_align/byte_rate incompatíveis; duplicação de fmt conflitante; overflow e múltiplos data válidos. Distinguir padding de chunk ímpar de byte incompleto de amostra PCM16. Não descartar silenciosamente um byte de áudio. Preservar fmt estendido válido e chunks desconhecidos permitidos. Rodar testes nativos e ASan/UBSan no host quando a toolchain permitir, registrando limitações reais.

Testar na UI isolada entrada WAV válida e conversão estéreo48k/AAC44.1 pelo caminho real de preparação, erro de conversão, silêncio e recuperação para arquivo bom. Erro/cancel/no_speech/sucesso devem ser coerentes nos três modos: arquivos, gravação e live.

**Saída de E:** prova de live com PCM injetado, worker/estado terminal corretos, integridade de amostras, regressões WAV e conversão real.

## Fase F — desempenho, repetição e fechamento

### F1. Comparações úteis

Somente configurações verdes em qualidade/estabilidade podem ser candidatas de velocidade. Comparar CPU FA-off/on primeiro; comparar GPU FA-off/on quando o backend e os nós efetivos forem provados. Manter modelo/quantização, beam, best_of, threads, VAD, idioma e timestamps iguais dentro de cada par.

Em C01/C02: 1 warmup identificado, depois **5 pares medidos** off/on alternando AB/BA e ordem dos backends. Separar cold process, load e warm inference. Registrar temperatura/estado térmico, carga/bateria, memória observada e alterações no código entre rodadas. Não descartar run lento/crash/timeout dos denominadores. Não prometer p95 com 3–5 amostras.

Métricas mínimas por run: IDs/hashes, modo/driver, parâmetros e perfis efetivos, backend/nós FA, duração original e processada, conversão/load/encoder/decoder/inferência/cleanup/total de UI, memória amostrada, cancel-request→worker-done, status execução/semântico, WER/CER/diffs, caminho dos logs. Métrica não medida = null e motivo. RTF usa duração original; redução de áudio por VAD não é ganho puro de FA.

Depois do melhor perfil correto, avaliar threads 2/4/6/8 e beam 1/3/5 em C02, uma variável por vez, com parâmetros efetivos e tradeoff de qualidade. Não alterar defaults apenas para melhorar o tempo. Se já houver evidência forte de gargalo, priorizar os candidatos previstos em vez de varrer combinações redundantes. Se small/turbo estiverem disponíveis, fazer smoke de capacidade/memória e um caso de qualidade no melhor perfil; benchmark amplo de modelos/VAD fica para rodada posterior quando necessário.

### F2. Estabilidade proporcional

Executar 30 transcrições curtas no melhor perfil GPU correto, registrando falhas/30; alternar load/free e backends em 20 ciclos no mesmo processo do sandbox; incluir 10 ciclos de cancelamento/retomada e 5 de fechar/reabrir a tela. Em CPU, manter controles de cancelamento/lifecycle para detectar regressão compartilhada. C03 longo ao menos uma vez por backend elegível, com duração processada completa e memória antes/depois. Reduzir apenas uma classe que já falhou repetidamente e explicar o bloqueio; não anunciar estabilidade do backend que ficou excluído.

Zero falhas nessa triagem não comprova ausência de intermitência. Informar que todos os ensaios usam um PJA110/driver e propor soak/diversidade posteriores. Não disparar benchmark de horas em uma candidata já comprovadamente incorreta.

### F3. Gates e identidade final

Preparar o ambiente da falha unitária anterior (`SmartInsertNativeTest`/dependência FFmpeg no host) usando a configuração existente ou executável de teste aprovado pelo projeto. Não alterar código alheio, excluir teste nem transformar o skip em pass. Se o gate continuar falhando, comprovar na base e manter status FAIL ambiental explícito; a rodada não pode dizer “todos os gates passaram”.

Executar, no snapshot final:

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:lintDebug
.\gradlew.bat :app:assembleDebug
& .\scripts\check-module-map.ps1 -Quiet
git diff --check
git diff --cached --check
```

Rodar também testes focais Pester, nativos/operadores e instrumentados. Fonte nova de produção precisa KDoc e MODULE-MAP; não esquecer arquivos novos fora do índice na verificação. Se mudar harness central, executar `scripts\tests\validate-agent-harness.tests.ps1 -Quiet`.

Cada correção C++ exige build nativo e ensaio da lib resultante. `assembleDebug` comum não compila nativos. Usar o fluxo local existente de `-PbuildNativeComponents=true` e a seleção de ABI de teste quando adequada, ou target nativo Whisper focal; registrar comandos/toolchain/flags. Conferir ELF/ABI/NEEDED, libomp e carregamento no sandbox, evitando dependência estática obrigatória de libOpenCL onde o loader atual é dinâmico. Fazer compile/smoke CPU x86_64 quando viável; isso não valida o driver GPU Android.

Conferir a build de produto para ausência de hooks e mudanças de contrato público. Não gerar/republicar todos os pacotes nativos nem executar uma promoção como parte desta rodada. Quando futura release nativa for autorizada, a verificação de pacotes e promoção conjunta APK/ZIP seguirá AGENTS.md.

Fechar patch completo do escopo, fontes/hashes finais, diffs staged/unstaged e manifesto de novos arquivos com conteúdo recuperável. Se fizer commits locais no checkout isolado, separar infraestrutura e correções, sem push; registrar também o delta ainda não commitado. Não integrar na branch principal automaticamente.

## 4. Critérios de aceite

| Área | Aceite exigido |
|---|---|
| Medição | Backend/FA efetivos demonstrados; bytes íntegros; parâmetros comparáveis; status semântico separado |
| Sessões | Nenhum release/cancel/evento antigo afeta sessão nova; resultado por job; provas determinísticas + ART |
| Vulkan | Recuperação de falha sem abort/dado parcial; transcrição FA-off correta; causa/patch/testes demonstrados |
| OpenCL | Seleção real; fala não desaparece silenciosamente; compilação/init/recuperação testados |
| FA | Prova numérica com entrada fixa e contagem real de execução; fallback identificado; aceleração medida separada |
| Live | Captura drena durante inferência; contabilidade de amostras fecha; término corresponde ao worker |
| Qualidade | Sem NaN/Inf, perda/duplicação sistemática, texto incoerente ou timestamps inválidos introduzidos |
| Código | Gates com exit real e snapshot final; testes que expõem os bugs e demonstram as correções |

Qualidade: usar o controle íntegro do mesmo modelo/configuração. Piora superior a 1 ponto percentual absoluto de WER ou uma palavra adicional em C01 exige investigação; não resolver piora alterando a referência. Esse limite é triagem, não certificado geral. Guardar diffs mesmo abaixo do limite. Silêncio e áudio com fala têm resultados esperados diferentes.

Velocidade: reportar mediana/intervalo/número de falhas e pares individuais. Ganho que se confunde com variação é inconclusivo. Uma proteção pode ser mais lenta e útil, mas não satisfaz sozinha a meta de aceleração. Não atribuir a FA ganhos vindos de threads, busca, VAD ou modelo menor.

Usar estados de achado: HIPOTESE, REPRODUZIDO, CORRIGIDO_COM_PROVA, MITIGADO, INCONCLUSIVO e ABERTO. `NAO_EXECUTADO` não significa concluído. Preservar W01–W15 e relacionar R2-01–R2-08 da revisão, abrindo novos IDs sem redefinir os existentes. O caso core/OpenCL mal selecionado e o bypass diagnóstico de FA não devem inflar a contagem de bugs independentes do produto.

## 5. Relatório obrigatório e ponto de retorno

Entregar **`D:\Projetos\SIG\docs\especialista\whisper\RELATORIO-WHISPER-RODADA2.txt`**, UTF-8, detalhado e autossuficiente. Auxiliares pequenos: `MATRIZ-WHISPER-RODADA2.csv`, `RESUMO-METRICAS-WHISPER-RODADA2.md` e `MANIFESTO-WHISPER-RODADA2.json` na mesma pasta. Logs grandes continuam no build ignorado, com caminhos e hashes nesses documentos.

O relatório deve permitir ao especialista decidir a próxima rodada sem reconstruir suas mensagens:

1. Resultado executivo: bugs corrigidos/mitigados/abertos, se FA realmente acelerou cada backend, se Vulkan produziu texto correto e qual perfil válido foi mais rápido. Informar se a candidata ainda não pode ser integrada.
2. Origem e snapshot: branch/base/commits/diff completo, bibliotecas e APK carregados, hashes de fontes/modelo/áudio, aparelho/driver e toolchain/flags/comandos exatos.
3. Retificação da rodada 1: quais conclusões mudaram por seleção errada, encoding, FA forçado off, gates ou cobertura de UI/live. Conservar evidências antigas.
4. Fases A–F: cada tarefa com PASS/FAIL/NAO_EXECUTADO, denominador, motivo e evidência. Não apenas checklist marcado.
5. Achados: causa, trigger mínimo, frequência, estágio, arquivos/linhas atuais, teste que falhava antes e passou depois, patch e riscos restantes.
6. ART/UI/live: pacote isolado, cenários, contagem de amostras, timeline de worker/estado/UI, callbacks/referências/threads e prova de recuperação. Separar simulação de execução real.
7. GPU/FA: operação/shapes, tensores/erros/tolerâncias, backend efetivo, nós executados, logs/shaders/backtrace e origem verificada de cada backport.
8. Desempenho/qualidade: dados individuais, controles íntegros, cold/warm, parâmetros efetivos, mediana/variação/falhas, WER/CER e diffs. Mostrar runs falhos e limitações das amostras.
9. Incidentes: cada crash, timeout, erro de teste/build, falha de transporte e mudança de hipótese; extratos relevantes e caminhos brutos. Evitar duplicar centenas de linhas sem explicar o que demonstram.
10. Gates: comandos, exit-codes, logs, snapshot a que correspondem e falhas/skips. “Ambiental” não muda exit para pass.
11. Estado do dispositivo: comprovar SIG do usuário preservado, pacote/diretório sandbox utilizados, remanescentes e reprodução/rollback restritos aos caminhos de teste; não fazer limpeza recursiva de caminhos não verificados.
12. Pendências: até cinco próximos experimentos prioritários, cada um com hipótese, evidência atual, mudança exata, teste e condição de sucesso/parada. Distinguir impossibilidade material de tarefa ainda não tentada.

No encerramento, devolver os caminhos absolutos do relatório, snapshot/patch e evidências, com resumo curto. **Parar ao entregar esta rodada**: o especialista revisará os resultados e emitirá a próxima ordem. Não iniciar rodada 3, integrar ou publicar por conta própria.
