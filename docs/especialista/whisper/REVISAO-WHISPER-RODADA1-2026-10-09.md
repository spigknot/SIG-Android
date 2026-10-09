# Revisão do especialista — Whisper, rodada 1

Data: 09/10/2026. Parecer: **rodada parcialmente concluída; candidata C2 ainda não aceita para integração ou promoção**. A infraestrutura, o corpus e os testes novos permitem avançar. Entretanto, permanecem falhas de qualidade do Vulkan, Flash Attention está desativado nas GPUs e o ciclo de vida real da ferramenta não foi validado.

Esta revisão confere o relatório contra os fontes e algumas evidências brutas acessíveis. Não executa novamente a matriz nem modifica a implementação. As próximas ordens estão em `PROMPT-WHISPER-RODADA2.md`, nesta pasta.

## Material conferido

- Relatório recebido: `D:\Projetos\SIG\docs\especialista\whisper\RELATORIO-WHISPER-RODADA1.txt`, também fornecido como anexo `Texto colado.txt`.
- Implementação: `D:\SIG-whisper-r1`, branch `codex/whisper-r1`, baseada em `963d41a08d78a1a456b8c7b59a5a025e1fd2e238`; alterações staged e unstaged, sem commit de fechamento.
- Controle reconstruído: `D:\SIG-whisper-b1`.
- Evidências: `D:\SIG-whisper-r1\build\whisper-rodada1\20261008-r1`, especialmente `runner-out\final\SUMMARY-FINAL.md` e os logs em `runner-out\final\cells`.
- C2 informada: SHA-256 `91091742f490ff2bb6bdf92e7f2791eb8a8cb84a53ffb674cefa62e30db1139c`. A identidade do binário não comprova, sozinha, a correspondência com todos os fontes atualmente modificados.

O relatório técnico `.md` em `D:\SIG-whisper-r1\docs\whisper\rodada1` antecede parte das diligências finais. Seu inventário de patch, números e classificação de achados não devem substituir as evidências finais. A próxima rodada precisa fechar um único snapshot consistente.

## Resultados que podemos aproveitar

1. O PJA110/Adreno 740 dispõe de controles sintéticos e de bibliotecas B0/B1/C1/C2 identificadas. O ensaio não instalou APK sobre o SIG do usuário.
2. O parser WAV passou a limitar chunks pelo tamanho real do arquivo e ganhou testes nativos. É uma melhoria concreta de robustez; a cobertura não permite declarar toda a validação WAV encerrada.
3. O cancelamento antes da inferência passou a funcionar nas provas sequenciais do executável. Ainda falta a prova com sessões concorrentes e ART real.
4. A aquisição de `JNIEnv` por thread e o uso de referências globais seguem a direção correta. A prova com JNI simulado não valida os contratos reais de ART, attach/detach e exceções Java.
5. O resultado estruturado evita confundir uma transcrição legítima iniciada por “Erro:” com falha, quando o status correspondente está disponível. A associação entre texto, status e sessão ainda precisa ser protegida contra concorrência.
6. Os números de CPU sugerem benefício de FA. São resultados preliminares, dependentes do caminho e dos parâmetros usados; não estabelecem ainda um ganho final no aplicativo.

## Correções de interpretação e achados da revisão

### R2-01 — O teste core de OpenCL usou Vulkan

Em `tools\whisper\whisper_bench_harness.cpp`, `core_load()` usa `gpu_device = 0` para qualquer GPU e apenas distingue CPU de GPU por `use_gpu`. Não resolve o dispositivo pelo backend solicitado, como o JNI faz em `can_initialize_gpu_backend()`.

O log `runner-out\final\cells\f3-C01-opencl-facmp-C2.attempt1.stderr.log` confirma: `whisper_backend_init_gpu: using Vulkan0 backend`, seguido de falhas de pipelines Vulkan e aborto. Portanto, **esse crash não constitui prova de FA no OpenCL**. A falha de transcrição vazia observada no caminho JNI/OpenCL é um achado separado e continua exigindo investigação própria.

O campo `flash_effective` do modo core também deriva do valor configurado. Ele não prova execução de nós FA na GPU. Todos os resultados que dependem dessas classificações precisam ser revistos.

### R2-02 — A desativação de FA em GPU é mitigação

O C2 força `cparams.flash_attn = false` sempre que o usuário pede FA em qualquer backend diferente de CPU. A mensagem de aviso é útil, mas a mudança não corrige os shaders e não entrega a aceleração solicitada nas GPUs.

Os testes Vulkan C2 rotulados FA-on continuam produzindo texto incoerente, com WER próximo de 100%, porque efetivamente executam FA-off. Exit 0 não transforma esses resultados em sucesso funcional. Classificar W11/W12 como **MITIGADO**, com causa raiz aberta, e W13 como falha de qualidade aberta nos cenários registrados.

Não há evidência suficiente para associar esse crash específico ao problema intermitente original do usuário: seu cenário exato e a configuração usada não foram fornecidos.

### R2-03 — Há um caminho concreto para o aborto Vulkan

Os logs mostram falha de `createComputePipeline` para `flash_attn_f32_f16_aligned`, seguida de aborto em `ggml-backend.cpp`: tensor `leaf_0`, buffer `Vulkan0`, operação `NONE`.

Na árvore vendorizada Whisper, `ggml-vulkan.cpp` incrementa `pipeline_failures` quando a criação de pipeline falha. `ggml_backend_vk_device_supports_op()` então retorna false para **todas** as operações. O agendador encontra um tensor já alocado na GPU cujo backend passou a recusá-lo e aborta.

Essa sequência está sustentada por código e log. Ainda é necessário reproduzir em um teste mínimo e definir uma recuperação que preserve tensores residentes e impeça despacho de pipelines inválidos. Simplesmente remover a proteção ou o assert não é correção. A corrupção FA-off pode ter outra causa e deve ser investigada separadamente.

### R2-04 — Ownership e callbacks ainda possuem brechas

`WhisperSessionController.releaseAsync()` testa a geração antes de entrar em `releaseModel()`. O nativo pode esperar `g_mutex`; nesse intervalo, outra geração pode carregar um contexto. A verificação externa não protege a operação realizada depois da espera.

Além disso, `loadModel()` continua sendo chamado diretamente pela Activity, com lock e chave de modelo locais à instância; o singleton não serializa conjuntamente load/transcribe/status/release. `cancel()` não recebe proprietário. O `beginSession()` nativo limpa um cancelamento global sem vinculá-lo à sessão em execução.

`wrapCallback()` verifica a geração antes de o delegado postar no main looper. A geração pode mudar antes da execução desse post. `onDestroy()` enfileira release, mas não invalida o proprietário no controlador. O par texto/status é obtido em chamadas separadas. Os testes atuais cobrem geração já obsoleta na entrada, não esses interleavings.

São riscos demonstráveis pelo desenho atual; a próxima rodada deve construir provas determinísticas com latches e ART antes de marcar W02/W08/W09/W10 como concluídos.

### R2-05 — Live ainda não está corrigido

`runLiveMicLoop()` continua chamando `processLiveChunk()` sincronicamente na thread que lê `AudioRecord`. Durante a inferência ela deixa de drenar a captura. C09 transcrito como arquivo não avalia esse problema.

`evaluateLiveMicStop()` também abandona a referência a um worker vivo ao atingir o teto de espera e chama `completeLiveMicStop()`, que anuncia “Transcrição ao vivo finalizada” e reabilita os controles. Portanto, a correção W03 permanece incompleta.

O W04 original era a captura e a inferência na mesma thread. O relatório o redefiniu como ausência de instrumentação de UI; restaurar o significado original e abrir ID novo para qualquer achado adicional.

### R2-06 — A comparação de qualidade da base precisa ser refeita

O próprio `SUMMARY-FINAL.md` informa mojibake cp437 nos resultados B0/C1 e que o reparo não recupera C02 integralmente. Não é possível usar seu WER inflado como prova de ausência de regressão ou melhora da C2.

Antes de investigar divergência de build W14, recolher novamente B0/B1/C2 como bytes, no mesmo caminho, com todos os parâmetros efetivos iguais. Separar mudanças reais de palavras, normalização de números e corrupção de transporte.

A comparação numérica disponível é parcial: resumos de logits e argmax em decodificação livre não equivalem a comparar todos os elementos de atenção com entradas fixas. C02 nem manteve alinhamento de tokens. Isso não satisfaz a prova numérica solicitada.

### R2-07 — WAV e protocolo exigem testes adicionais

O leitor não usa `riff_size` para limitar o contêiner; um cabeçalho parcial de chunk pode ser tratado como fim normal; dados PCM16 de tamanho ímpar perdem um byte, além do padding RIFF. `byte_rate` e `block_align` são lidos sem validação. Definir os contratos e acrescentar regressões antes de declarar W07 completamente fechado.

O `no_speech` pode representar silêncio legítimo, mas também a falha OpenCL que devolveu texto vazio para fala conhecida. O runner precisa classificar esse segundo caso como falha semântica. Em produção, um resultado vazio isolado não permite diagnosticar automaticamente o backend: manter observabilidade e evitar afirmar sucesso de um job com erro.

### R2-08 — Gates e fechamento documental

- O gate unitário não passou: o relatório registra uma falha ambiental também reproduzida na base. Isso distingue regressão de ambiente, mas não torna o gate verde. Preparar a dependência de teste no ambiente e repetir, sem desabilitar o teste.
- O texto final “35 células ... exit 0 geral” contradiz o sumário, que contém exit 134. Separar sucesso de execução, de qualidade e de conclusão das tarefas.
- “Fatal signal 13 (SIGABRT)” é inconsistente: SIGABRT é 6; 13 é SIGPIPE. Corrigir a atribuição pelo log bruto, sem editar a evidência original.
- O patch antigo de quatro arquivos não representa o conjunto atual: existem fontes, testes, scripts e documentos adicionados ao índice, além das modificações unstaged. Fechar um snapshot completo e verificável.

## Prioridade da rodada 2

1. Tornar o runner confiável e recolher controles comparáveis.
2. Reproduzir as brechas de sessões, corrigir ownership e validar em ART/UI isolados.
3. Corrigir a recuperação do Vulkan e localizar a primeira divergência numérica FA-off; investigar FA/OpenCL no backend realmente selecionado.
4. Separar captura e inferência ao vivo, com contagem exata de amostras e finalização verdadeira.
5. Medir desempenho somente nas configurações corretas e estáveis, incluindo FA CPU e FA GPU quando realmente executado.

Não abandonar a investigação do Vulkan por ele ter perdido para CPU no harness. Não executar uma matriz extensa de modelos, VAD e parâmetros enquanto os controles de medição e a correção principal não estiverem resolvidos. Small/turbo, ASan e diversidade de drivers continuam no backlog, com ensaios proporcionais após a triagem principal.
