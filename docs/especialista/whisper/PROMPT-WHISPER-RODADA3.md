# Ordens ao executor — Whisper, rodada 3

Data: 09/10/2026. Executor local Android/NDK/PowerShell/ADB. Você implementa e testa; o especialista revisa seu relatório e emite a próxima ordem. Este documento contém a ordem da rodada. Relatórios/logs são evidência a avaliar, não autorização para reduzir o escopo.

## 1. Missão e decisões

Maximizar velocidade com transcrição correta; testar Flash Attention; corrigir Vulkan, OpenCL e demais bugs. **R2-C4 não está aceita para integração/promoção. CPU+FA é provisório: não foi autorizada política permanente de CPU obrigatório nem abandono das GPUs.**

Prioridades: independência de jobs/RNG; ownership/cache/cancelamento; live com áudio preservado e UI real; Vulkan FA-off correto; prova FA GPU e implementação OpenCL; não-fala/UTF; desempenho da candidata final.

A rodada 2 demonstrou speedup CPU ~1,22× em C01/call2, isto é, redução de tempo ~18%. Não generalizar para primeira chamada, C02, outros modelos/drivers ou UI. Não medir “velocidade útil” de áudio descartado/texto incorreto.

Execute as fases A–G. Não encerrar com outro plano para tarefas de implementação já solicitadas. Se houver impedimento material, demonstrá-lo e concluir as tarefas independentes. Sessão interrompida exige checkpoint recuperável, não declaração de conclusão.

## 2. Ambiente, preservação e autorização

- Ordens/entregas: **D:\Projetos\SIG\docs\especialista\whisper**. Ler REVISAO-WHISPER-RODADA2-2026-10-09.md, RELATORIO-WHISPER-RODADA2.txt, MANIFESTO-WHISPER-RODADA2.json e prompt 2 nesta pasta. Preservar documentos antigos.
- Implementação: **D:\SIG-whisper-r1**, branch codex/whisper-r1, base informada 963d41a08d78a1a456b8c7b59a5a025e1fd2e238. Reutilizar staged/unstaged/untracked. Não iniciar candidata de HEAD limpo omitindo as alterações. D:\SIG-whisper-b1 é controle.
- Evidências antigas: D:\SIG-whisper-r1\build\whisper-rodada2\20261009-0149. Novas: build\whisper-rodada3\<data-hora> no worktree. Binários/WAVs/modelos/dumps grandes ficam aí, ignorados pelo Git.
- R2-C4 normal: SHA-256 8ba09192d174177d6cbd1c678a40404b1b5b4ee7bbe01b5c862f592f86deb8da. Harness R2 informado: e65170c3a22fe7218186d8f2c083c63ebaa53b88044e05bf2092d2c119f4c563. Conferir arquivos carregados. Novas candidatas R3-C1/R3-C2...; não reutilizar rótulo para hash diferente.
- Ler AGENTS.md, MODULE-MAP.md e native-dependencies/README.md do checkout. Alterar apenas Whisper/seams/JNI/WAV, sua árvore GGML vendorizada, testes/runner/build/lab estritamente necessários. A árvore GGML do llama é distinta.
- Outros agentes mexem em outras áreas: preservar seus arquivos/commits e ignorá-los na investigação. Não usar reset/clean/git add .; staging por allowlist. Fonte de produção nova exige KDoc e MODULE-MAP.
- Snapshot inicial e final incluem conteúdo dos arquivos novos, staged, unstaged, ferramentas e hashes. Só uma lista de untracked não recupera o trabalho.
- Sem integrar automaticamente, push, assinar, publicar ou promover APK/ZIP. Não alterar NativeDependencyManager/versões/URLs/hashes publicados para testar. Promoção nativa futura seguirá AGENTS.md e aprovação específica.
- Testes debug reversíveis no lab já autorizados. Usar br.gov.sp.pcsp.whisperlab, pacote e armazenamento isolados; conferir providers/authorities/paths antes do install. Não substituir/limpar o SIG nem usar dados pessoais.

### Dispositivo obrigatório

PJA110 disponível continuamente em **100.114.88.45:5555**. Usar C:\adb\adb.exe e serial explícito:

    & C:\adb\adb.exe connect 100.114.88.45:5555
    & C:\adb\adb.exe -s 100.114.88.45:5555 get-state
    & C:\adb\adb.exe -s 100.114.88.45:5555 shell getprop ro.product.model

OnePlus PJA110/SM8550/Adreno740, Android13/API33; USB 1164a04 é o mesmo dispositivo. Registrar driver atual e reconectar se necessário. Escritas em /data/local/tmp/whisper-bench-r3 e no pacote lab; artefatos anteriores podem ser lidos/copiados. Não usar kill-server/reboot nem mudanças globais do aparelho. Fonte sintética/injetada: não gravar áudio incidental. Um trabalho pesado por dispositivo, sem disputar benchmarks.

## 3. Retificações que devem entrar no relatório

1. enc[0] é índice da saída FINAL do encoder. Ainda falta localizar a primeira OP defeituosa.
2. fallbacks p/h são n_fail_p/n_fail_h do decoder: logprob/entropy threshold. Não medem nós migrados GPU→CPU.
3. WhisperLabActivity própria valida ART/JNI, mas não automaticamente cache/posts/botões da WhisperActivity real.
4. Testes com controllerA/controllerB separados não representam o singleton WhisperSessions.shared.
5. Trinta processos novos/call2 não cobrem drift com contexto reutilizado.
6. Sanitização UTF protege ART; não corrige o motor nem prova origem “latin-1”.
7. Prova FA GPU, port OpenCL e ART s11 continuam tarefas de execução. Planejá-las não equivale a fazê-las.

Conservar logs/documentos antigos; registrar retificações em novo documento/relatório. Estados distintos: execução, semântica, capacidade, correção e tarefa concluída.

## Fase A — F-01: RNG e independência de arquivos

### A1. Reprodução causal pequena

Preservar fase-f/f1/f1ax-live.jsonl e f1ax2-live.jsonl. Reproduzir C01 CPU FA-off/on, quatro chamadas no MESMO contexto, sem warmup oculto. Registrar call-index absoluto, job/session IDs, PCM hash antes/depois, temperaturas tentadas/escolhida, falhas logprob/entropia por chamada, tokens, parâmetros efetivos e saída bruta.

Hipótese prioritária conferida no código local:
- whisper_init_state() semeia decoders[0].rng com std::mt19937(0).
- whisper_full_with_state() re-semeia apenas j>=1.
- Default temperature_inc=0.2f permite fallback; a amostragem usa decoder.rng.

O estado do decoder principal pode avançar entre jobs. no_context=true e threads=1 não eliminam isso. Instrumentar fingerprint do RNG antes/depois e consumo efetivo; não assumir causa resolvida só por ler o código.

| Controle | Pergunta |
|---|---|
| Defaults/contexto reutilizado | Reproduz a sequência da rodada 2? |
| temperature=0 e temperature_inc=0 | Sem amostragem, o drift desaparece? É diagnóstico, não novo default |
| Reseed diagnóstico do decoder0 por job, outros parâmetros iguais | Elimina dependência da posição preservando call1? |
| Estado novo/pesos mantidos vs contexto inteiro novo | Qual estado explica o efeito e qual custo de reset? |
| A,A,A; A,B,A; depois de erro/cancelamento; processo novo | Jobs independentes influenciam o posterior? |

Começar C01 threads1/4; expandir C02 após a hipótese principal. Não começar por dumps de alinhamento de todos os buffers se RNG explica a sequência.

### A2. Corrigir o contrato

Se confirmado, definir estado de amostragem por job independente: seed estável documentada, reset uma vez no começo da transcrição; todos os decoders usados cobertos. Preservar evolução entre janelas/temperaturas do MESMO job. Não desativar fallback nem reduzir busca para forçar igualdade. Continuidade explícita de prompt/live tem contrato separado do lote independente.

Se RNG não explica, comparar mel/encoder/primeiro decode com prefixo/tokens fixos entre call1..4; verificar KV/padding/máscaras/buffers/repack/cache/logits. Localizar primeiro estágio e depois nó divergente. Registrar hipóteses eliminadas com evidência.

Não recarregar pesos por arquivo como primeira solução. Estado/contexto novo é controle/mitigação; medir custo, memória, qualidade e cleanup antes de adotar. Não selecionar transcrição pela referência de teste em produção.

**Aceite A:** 20 chamadas no mesmo contexto por FA-off/on em C01; A,B,A com C02 e erro/cancel intercalado; parâmetros/seeds iguais produzem jobs independentes da ordem. Conferir tokens/WER e primeira chamada. Caso exista variação numérica residual, demonstrar origem/tolerância/impacto.

## Fase B — ownership real, cache e cancelamentos

### B1. Regressões com UM controlador

Usar singleton compartilhado nas provas principais, latches/barreiras e looper controlado. Testes relevantes falham em R2-C4 e passam na correção. Fakes não devem apenas repetir a implementação.

| Interleaving | Invariante |
|---|---|
| A inicia, B inicia, cancel A atrasado no singleton | A transmite ID capturado; não cancela B |
| Cancel A pendente, cancel B chega antes de A consumir | Pedidos independentes não se sobrescrevem |
| A termina, B usa MESMO modelo/backend/FA na MESMA Activity | Cache válido, ownership atualizado; B funciona e libera corretamente |
| Release A enfileirado, B reaproveita cache antes da execução | A não libera B; chave não aponta para contexto destruído |
| Contexto B; chamada direta transcribeOwned(A) entra após esperar mutex | Recusa nativa, sem inferir no contexto de B |
| Destroy A sem abrir B; resultado/callback já postado executa | Nenhum efeito em Activity destruída; owner invalidado |
| A ativo; chamada B ainda lê WAV/espera lock | Owner ativo identifica executor, não waiter |
| Primeiro uso com loader/probe lento | Inicialização assíncrona, sem carga/probe longa na UI |

### B2. Completar o contrato

cancel recebe identificador capturado do solicitante; não consulta geração global para adivinhá-lo. Avaliar jobId separado de sessionId para lote/live. Armazenamento de pedidos por owner/job ou equivalente não pode perder cancel A quando chega B. Cobrir pedido antigo de job concluído e cancel antes de load/inferência.

Invalidar owner no teardown antes de cleanup. Release usa token próprio do contexto e não afeta sucessor. Conferir validade no momento de executar todos os posts: segmento/progresso, erro de load, finally e resultado terminal. Não corrigir só callback intermediário.

Cache/contexto precisam fonte de verdade compartilhada. Reutilizar pesos é permitido, mas claim/transferência ocorre atomicamente no ponto protegido com handle/chave real. O retorno rápido de ensureModelLoaded() não pode ignorar owner ou existência do contexto. Load usa geração capturada do job, não campo mutável lido por worker antigo.

Após qualquer espera por g_mutex, a fronteira nativa confere owner/handle/contexto. Owned que apenas associa status não isola a inferência. Marcar owner ativo somente quando representa a execução que será abortada; cleanup garantido. Texto/status/erro têm resultado ou snapshot por job.

Inicialização lib/probe tem estado ready/failure assíncrono. Agendar prewarm e imediatamente chamar lazy na UI ainda deixa corrida. Testar candidata normal e B0 legado no sandbox; capacidades explícitas e fallback documentado. Não fingir garantias owned no B0.

**Aceite B:** provas JVM+ART desses casos, múltiplos jobs na mesma Activity/modelo, dois cancelamentos pendentes, sem cache falso/leak/evento antigo ou bloqueio de UI. Explicar garantias reais do legado.

## Fase C — live, integridade e tela real

### C1. Término e áudio preservado

Em R2, timeout de awaitFinished faz o leitor terminar e zerar pipeline; stop monitora leitor, não LivePcmWorker. Corrigir observando o worker/coordenador inteiro. Manter handle/cleanup até terminal. Timeout gera estado pendente/falha visível; não finalizado nem controles liberados para concorrência indevida. Testar timeout curto injetável com worker em latch, sem aguardar 60s em unitário.

A fila descarta o mais antigo. Priorizar preservar PCM aceito em spool temporário sequencial, com fila/índice limitado e limites de armazenamento. Leitor não espera inferência; worker processa em ordem. Fonte/armazenamento falho deve interromper ou marcar parcial, quantificando o trecho perdido. Não aumentar RAM indefinidamente.

Se mantiver descarte como modo explícito, ele não pode ser padrão silencioso nem receber “integridade corrigida”; UI/relatório mostram perda e intervalo. Contabilização em Log.i não basta.

Fonte sintética tem total/hash independente. Separar produzidos, capturados, aceitos, processados com sucesso, classificados silêncio, falhos, pendentes/cancelados e perdidos. expected=captured por definição não certifica captura. Tentativa que falhou não é áudio transcrito com sucesso. Erro/EOF/zero contínuo/leitura ímpar têm contrato explícito; não ignorar bytes nem fazer loop infinito.

Callback/listener tem token e invalidação ao entregar, inclusive post já enfileirado/cancel entre check e chamada. Exceção de listener/fonte/worker garante cleanup/terminal. Deadline monotônico; stop/cancel/close idempotentes, inclusive falha antes de start.

### C2. Executar a rota do usuário no sandbox

Rodar s11 com PCM injetado na rota da WhisperActivity REAL do pacote isolado. O lab atual implementa cenários próprios: mantê-los, mas exercitar a Activity real ou extrair coordenador ÚNICO e demonstrar que Activity/lab o usam; testar ligação UI→coordenador.

Fonte/loader/hooks somente debug. Biblioteca NORMAL candidata no sandbox com SHA verificado; lib diag apenas quando necessária. Não abrir microfone para áudio incidental.

Cenários: C09 12 frases+final parcial; produção em ritmo real com inferência lenta; backlog maior que fila antiga; stop com worker ativo; cancel/sair/reabrir; fonte falha; storage cheio injetado; listener lança; job seguinte na mesma tela/modelo; arquivo e gravação controlada com PCM injetado.

Reconstruir PCM preservado/processado e comparar amostras/hash contra a fonte; conferir ordem/fronteiras e texto separadamente. UI mostra perda/sobrecarga/erro/parcial e recusa FA em arquivo/gravação/live. Testar botões, relatório/export quando houver, terminal e heartbeat, sem posts antigos.

**Aceite C:** s11 real executado; PCM aceito preservado salvo cancelamento/falha explicitamente reportada; término depois do worker, sem precisar reabrir tela a cada job.

## Fase D — Vulkan FA-off e falhas de pipeline

### D1. Localizar a primeira operação errada

C01 FA-off, CPU/Vulkan/OpenCL efetivos, configuração/seed iguais após A. Processo novo por perfil, ambiente antes de enumeração. Não chamar enc[0] de primeira OP.

Build diag captura nome/índice topológico, op, ne/nb, types, view offsets, pesos/input e backend/buffer. Sincronizar execução antes do dump, respeitar strides e conversão F16/F32. Conferir PCM/mel/pesos e ausência de mutação.

Localizar por fronteiras: conv1/conv2/positional embedding/primeiro bloco e camadas seguintes. Usar avaliação do scheduler ou hooks por nó quando necessário, com execução sincronizada. Comparar entradas iguais e saídas com tolerância por dtype, erro abs/rel/RMSE, NaN/Inf. Destilar primeiro nó em replay independente com tensores COMPLETOS e referência apropriada.

Hipóteses conforme evidência: IM2COL/conv, matmul/layout/repack, GELU/norm/softmax, atenção explícita, cast/views/cópias/coerência/padding/KV. No reproducer: V0, FP16 permitido/V5, fusion-off, graph-opt-off, host-preference retirada; um eixo por vez. V5 não pode permanecer não tentado porque outros perfis falharam.

Corrigir causa/kernel/cópia/capacidade e acrescentar regressão com shape real. Fallback seletivo pode mitigar corretamente, mas contar nós GPU e custo; fallback integral não é aceleração Vulkan. Não aprovar W13 sanitizando caracteres.

### D2. Recuperação além de FA

Revisar seleção de pipeline: R2 removeu recusa global e provou apenas flag FA. Injetar falha não-FA obrigatória, shader/module/allocation e variante FA fora da preflight. Nenhum despacho null/não compilado, assert/deadlock ou resultado parcial ok.

Manter regressão RAII compile_count; conferir permits sucesso/falha/exception/concorrência. Preflight publica veredito APÓS conclusão; desconhecido/preparação falha não significa suporte aprovado. O caso pequeno não certifica todos os shapes/modelos.

Preservar tensors residentes ao recusar/recuperar. Não remover asserts nem retornar true indiscriminadamente. Estado de falha cacheado não pode contaminar contexto seguinte sem diagnóstico.

**Aceite D:** primeira OP localizada/corrigida ou mitigação seletiva comprovada; C01/C02 corretos; falhas FA/não-FA recuperáveis com regressões. Se não resolver, entregar o operador mínimo e próxima hipótese causal, não outro dump só do encoder final.

## Fase E — atenção nas GPUs e OpenCL implementado

### E1. Prova numérica executável

Reutilizar referência f64/casos de tools/whisper/fa_attention_test.cpp e executar CPU, Vulkan e OpenCL REAIS. Criar target que liga aos mesmos objetos/backends da lib, ou dlopen/dlsym com ABI verificada; escolher caminho simples. Listar símbolos não entrega o teste.

Scheduler aloca/copia inputs/outputs corretamente, sincroniza, respeita views/strides e dispositivo resolvido. Comparar FA com referência de mesma semântica e não fundida quando útil. Preservar tensores completos, tolerâncias fixadas antes, erros max/mean/rel/RMSE e NaN/Inf.

Casos: encoder1500/pad1536, decoder self/cross, máscara causal, F16/F32, stride de camada, layouts suportados/recusados; incluir entradas reais da fase D. Instrumentar EXECUÇÃO de nó/kernel, não só requested/placement/supports_op.

Classificar GPU_EXECUTED, CPU_FALLBACK, UNSUPPORTED, COMPILE_FAILED, NUMERIC_FAILED. CPU_FALLBACK passing é recuperação, não FA GPU. Nunca inferir migração CPU dos p/h do decoder.

FA Vulkan com compilação falha: guardar shader/variant/SPIR-V, shape/workgroup/specializations, subgroup/shared-memory/FP16/capabilities e erro. Testar delta mínimo sustentado por driver, preservando outras GPUs. Tentar dtype/variante suportada e fallback seletivo com prova; se impossível, recusa precisa, não aceleração fictícia.

### E2. Port focal OpenCL e W12

Verificar SHA COMPLETO/referência primária/dependências do upstream whisper.cpp d1be6fde. Arquivo de referência R2 sha256 9b6ed650cdf50cf09407c9f7ea5109fa90ca87ae8e58616930c68d31d6215085. Não atualizar tudo nem aplicar guarda de outro compiler sem verificar o que cobre.

IMPLEMENTAR:
1. Compilação FA lazy por variante/primeiro uso; init FA-off não compila FA não usado.
2. Compilação FA não-fatal, build-log preservado, variante indisponível e recusa correta ANTES de agendar.
3. Guardas E17/A7X realmente existentes, adaptadas com identificação local. Não afirmar que E17 resolve E031 do PJA110 sem prova.
4. Cache por device/contexto/tipo/shape necessário, liberação de programas e recriação correta após falha/reload.

Testar init FA-off, variante suportada, recusa driver/tipo, falha injetada, mistura FA GPU/CPU, load/free/reload e alternância de backends. Não exit(1)/abort por build experimental; outros programas obrigatórios também precisam erro explícito.

Reproduzir vazio-com-fala W12 no OpenCL real e localizar primeiro nó inválido com entradas fixas. Registrar logits iniciais, no_speech_prob, temperatura/fallback, segmentos e rc. Port lazy pode melhorar init sem corrigir matemática: distinguir entregas e continuar até causa/reproducer.

Após prova, substituir guarda global OpenCL FA-off por capacidade específica quando houver caminho correto. Caso não suportado, recusa visível e aceleração não entregue nesse driver. OpenCL FA-off recebe profiling de kernels/cópias e qualidade; ~24,6s antigo não justifica abandoná-lo.

**Aceite E:** teste GPU construído/executado; port implementado/testado; W12 corrigido ou causa/op mínima localizada; capacidade e FA efetivos medidos. Não entregar só plano de port.

## Fase F — silêncio e UTF

### F1. Não-fala sem apagar fala

Inventariar modelo Silero/VAD compatível: relatório R2 contradiz presente/ausente. Se necessário, obter fonte oficial/primária e hash somente para sandbox. Avaliar VAD/probabilidades com contrato; limiar RMS isolado não garante ausência de fala.

Corpus: silêncio5/60s, tom, ruído, música sintética sem voz se disponível, fala baixa/curta, início/fim fracos, pausas, frase sobre música de fundo e conteúdo com colchetes/Erro:. Não remover por palavras/colchetes. Medir falsos positivos/negativos, amostras retidas, WER/timestamps e custo; silêncio aprovado por cortar fala é falha.

Vazio de motor quebrado não é NO_SPEECH legítimo. Em arquivo/gravação/live, distinguir sucesso, no_speech, error, cancelled e partial. VAD muda configuração, seu ganho não é FA.

### F2. Origem do 0xB3 CPU

Capturar IDs/bytes por token, concatenação por segmento e texto completo antes do sanitizador. Token byte-level individual pode ser fragmento UTF válido após concatenação. Testar multibyte/emoji atravessando fronteiras e se U+FFFD foi introduzido desnecessariamente.

Distinguir token incoerente, segmentação e string Java; manter bytes bin/hex/base64 com hash. Proteção JNI fica, com número de substituições e diagnóstico; não declarar origem latin-1 sem rastreamento nem aprovar lixo Vulkan.

**Aceite F:** não-fala coerente com controles de fala preservados; bytes têm causa/limite e regressão de fronteira além da proteção ART.

## Fase G — medir e fechar a candidata final

### G1. Desempenho comparável

Após correções: R2-C4 vs candidata; FA off/on C01 e C02, modelo/hash/seed/reset/decoder/idioma/VAD/timestamps iguais. Primeira chamada e warm com contexto reutilizado SEPARADOS; 5 pares AB/BA por caso elegível, período térmico comparável. Sem warmup secreto da transcrição do usuário.

Guardar pares individuais, mediana/intervalo/falhas/speedup/redução de tempo. Tempo total UI, cópia/conversão/load/encoder/decoder/inferência/cleanup separados. Decoder individual/batch mal parseado é null/motivo, não zero. RTF sobre áudio original e duração processada informada.

Amostrar RSS/PSS/memória/thermal/temperatura/bateria e guardar série. Nós GPU/CPU/FA realmente executados e fallback reason. Threads/beam/modelo/VAD/seed/descarte não são ganho FA. Validar threads4/8 com repetição antes de recomendar; controlar demais eixos. Smoke tiny/base e small/turbo disponíveis no perfil correto, sem matriz extensa antes da estabilização.

### G2. Repetição que cobre uso real

- CPU FA off/on: 20 inferências MESMO contexto C01 e A,B,A/cancel/erro; conferir call1 e últimas, tokens/hash/WER/memória.
- GPU correto: 30 curtas, pelo menos 10 no mesmo contexto; falhas semânticas aparecem como FAIL.
- 20 ciclos alternando modelo/backend/load/release no mesmo PROCESSO, inferência validada no meio. Reduzir carga de backend já incorreto com motivo; ele continua não aprovado.
- 10 cancel/retomada e 5 fechar/reabrir na Activity REAL sandbox; jobs sucessivos sem reabertura e mesmo modelo.
- s11 backlog/worker acima do timeout de teste; C03 longo pelo perfil correto, duração/amostras completas.

Não substituir por trinta processos novos/call2. Zero falhas em um PJA110 é triagem; informar denominadores, limites e próximos soak/drivers.

### G3. Gates, build e origem final

Usar FFmpeg do manifesto de referência que passou (8.0.1 gyan informado), path/version/hash registrados. Não alterar teste alheio para outro PATH. Serializar gates que disputam outputs Gradle.

No snapshot FINAL:

    .\gradlew.bat :app:testDebugUnitTest
    .\gradlew.bat :app:lintDebug
    .\gradlew.bat :app:assembleDebug
    & .\scripts\check-module-map.ps1 -Quiet
    git diff --check
    git diff --cached --check

Também Pester runner, WAV/UTF/resolver/operadores/FA, JVM lifecycle/live e ART. Se mudar harness central: scripts/tests/validate-agent-harness.tests.ps1 -Quiet. Falha ambiental é FAIL ambiental; skips explícitos.

C++ alterado exige build NATIVO e lib nova ensaiada, não assembleDebug comum. Fluxo focal/flags existentes -PbuildNativeComponents=true; -PsigWhisperDiag=true somente diagnóstico. Registrar ABI/toolchain/comandos. Compile/smoke CPU x86_64 quando viável.

Conferir ELF/NEEDED/libomp/ABI/load; não libOpenCL estática obrigatória. APK/lib NORMAL sem hooks/lab; bypass de perfil/falha/seed/dump deve ser diag ou mudança deliberada documentada de contrato, nunca vazamento acidental no release.

Snapshot inclui HEAD/staged/unstaged/novos e conteúdo/hashes/allowlist. Artefatos e gates correspondem aos fontes finais. Commits locais separados no worktree são permitidos; sem push/integrar automaticamente. Preservar intermediários de prova antes/depois.

## 4. Regras de execução e aceite

Não é necessária nova autorização para corrigir/testar este escopo. Impedimento material exige comando/erro e tarefas independentes continuam. Driver sem FA pode ser resultado válido, mas requer ensaio executado/recusa segura; “não tentado por orçamento” não fecha objetivo.

Bug: trigger, causa ou hipótese rotulada, antes/depois, patch/teste e risco. Não parar porque mudou mais que três arquivos/150 linhas; limitar escopo pela causa/dependências. Persistir sem repetir cargas falhas sem hipótese nova.

Separar execução/semântica/capacidade/correção/tarefa. Estados: HIPOTESE, REPRODUZIDO, CORRIGIDO_COM_PROVA, MITIGADO, INCONCLUSIVO, ABERTO. Fases/linhas: PASS/FAIL/NAO_EXECUTADO com denominador/motivo. Preservar IDs W01–W15/R2A/F e relacionar R3-01–R3-08 sem redefini-los.

Qualidade: sem NaN/Inf, lixo, perda/duplicação sistemática, timestamps inválidos ou efeito de job antigo. Controle íntegro, parâmetros/seeds iguais. Piora >1 ponto percentual absoluto WER ou >1 palavra em C01 exige investigação/diff; não mudar referência. Perda contabilizada não é PASS integridade; erro de chunk não é silêncio.

UI: nenhuma operação longa/probe/load/cleanup na main; sair/cancelar/heartbeat sem bloqueio determinístico >500ms. request→worker-done separado. Não inventar interruptibilidade GPU: UI mantém término pendente e ownership seguro.

## 5. Relatório e ponto de retorno

Salvar **D:\Projetos\SIG\docs\especialista\whisper\RELATORIO-WHISPER-RODADA3.txt**, UTF-8, detalhado/autossuficiente. Auxiliares: MATRIZ-WHISPER-RODADA3.csv, RESUMO-METRICAS-WHISPER-RODADA3.md, MANIFESTO-WHISPER-RODADA3.json. Evidências grandes em build/whisper-rodada3; caminhos ABSOLUTOS/hashes no relatório.

Conteúdo obrigatório:

1. Resultado: bugs corrigidos/mitigados/abertos, FA CPU/GPU real/velocidade, Vulkan correto ou não, melhor perfil válido/prontidão.
2. Origem: base/branch/commits/diff completo, hashes fonte/lib/APK/harness/modelo/corpus, toolchain/flags/dispositivo/driver/comandos.
3. Retificações: encoder-final vs primeira OP, p/h vs scheduler, alcance lab/soak, RNG e bytes.
4. Fases A–G por tarefa, status/denominador/evidência/motivo; plano não implementado = NAO_EXECUTADO.
5. F-01: call-index, RNG/temperaturas, hipóteses eliminadas, causa/patch/reset/custo/qualidade.
6. Sessões/UI/live: singleton/cache/ownership/cancel duplo, invalidation, amostras/hash/perdas/erros e worker terminal.
7. GPU: primeira OP/replay/tensores/tolerâncias/backend, patch, falhas FA/não-FA, recovery/preflight/cache, port OpenCL e SHA/dependências.
8. FA: casos numéricos e GPU_EXECUTED/CPU_FALLBACK/UNSUPPORTED etc., erros/tolerâncias/capacidade/WER.
9. Não-fala/UTF: falso positivo/negativo, VAD/modelo/hash, fronteiras/tokens/bytes/substituições/riscos.
10. Dados individuais/pares/speedup/redução/primeira chamada/warm, total UI/memória/thermal/qualidade/falhas.
11. Incidentes/gates/exit/logs/skips e snapshot correspondente; preservar falhas antes da correção.
12. Dispositivo/SIG preservado/pacote/diretório sandbox/remanescentes/reprodução/rollback restrito a caminhos verificados.
13. Até cinco próximos experimentos: evidência/hipótese/delta/teste/sucesso-parada; destacar objetivo central sem tentativa.

Retornar caminhos absolutos do relatório/snapshot/patch/evidências e resumo curto. **Parar após entregar esta rodada**: especialista analisa e emite rodada 4. Não mudar política de backend, integrar ou publicar por conta própria.
