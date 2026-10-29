# Diagnóstico técnico e fontes da revisão v2

Leitura em 20/10/2026. Este documento separa observação de código, resultados históricos, hipóteses e decisões prospectivas.

## Estado observado no repositório

| Área | Observação | O que ainda não prova |
|---|---|---|
| Base | main d439008e549a5193c781f0a1ffd087be1378b730 | Proveniência dos APKs instalados ou dos modelos do lab |
| Downloads | Commit c7d241b corrige cache/progresso | Ausência de regressão em futuras variantes de runtime e modelo |
| UI NAR | GraniteActivity força CPU e desabilita entradas aceleradas para NAR | OpenCL, Vulkan ou HTP disponíveis no produto |
| Backends | GraniteExecutionBackend está em GraniteEngine.kt e contém CPU, GPU_QNN, NPU_QNN_HTP | GPU_QNN ser equivalente a uma API OpenCL/Vulkan independente |
| Buckets | GraniteNarBuckets.TODOS vai de 200 a 2000; política DSP libera 200/400 | Segurança de t2000/t3000 no HTP |
| Exportador | tools/granite/nar/export_static.py exporta áudio até T=2000/editor até S=1408 | Que o modelo fonte não suporte uma sequência maior |
| Assinaturas | Exportador declara attention_mask; chamada do editor na main fornece embeddings e position_ids | Que o modelo instalado seja o export recém-descrito; inspecionar arquivo e metadados |
| Vocabulário | NAR possui parseVocabJson por regex | Paridade completa de JSON/Unicode e velocidade de outro parser |
| Sessões | warmup/bucket e mudança de variante podem abrir outras sessões | Warm real sem evento de identidade de sessão |
| Recursos | Alguns tensores são fechados após o caminho feliz | Fechamento em falhas, cancelamento e troca de perfil |
| Chunking local | Janelas de 40 s/overlap 2 s; merge textual de até 32 palavras | Qualidade real de borda e ausência de remoção de repetição legítima |

Fontes locais prioritárias: app/src/main/java/br/gov/sp/pcsp/launcher/GraniteNarEngine.kt, GraniteBinarySupport.kt, GraniteEngine.kt, GraniteActivity.kt, GraniteNarManifest.kt, GraniteNarLlmSettings.kt, GraniteParallelismSettings.kt, QairtDependencyManager.kt; app/src/debug/java/br/gov/sp/pcsp/launcher/GraniteNarSmokeTestActivity.kt e GraniteNarBenchmarkProtocol.kt; scripts/run-granite-nar-adb-benchmark.ps1; tools/granite/nar/export_static.py.

As cinco alterações locais anteriores estão identificadas por SHA no [snapshot](SNAPSHOT-20261020.json). Esta revisão de plano não as altera. Resultados de teste previamente relatados não substituem nova aceitação de inferência real com áudio longo.

O runner ADB existente aceita CPU/GPU_QNN/NPU_QNN_HTP, faz instalação e force-stop e limpa logcat. Não invocá-lo como se fosse inventário inofensivo ou como se já aceitasse OPENCL/VULKAN/SMART. Adaptar o protocolo com captura e identidade antes da bateria. A mera existência de RequireFullAcceleration não dispensa provar que todos os seus consumidores falham diante de fallback.

## O que 60 segundos exigem

A configuração IBM consultada descreve pooling CTC por 4, projector com downsample 5, dimensão textual 2048, vocabulário 100352, 16 cabeças de atenção/4 de KV e posição máxima textual 4096. O editor NAR faz edição bidirecional em passagem única. Essas são propriedades da fonte; os grafos exportados precisam ser conferidos. [Configuração](https://huggingface.co/ibm-granite/granite-speech-4.1-2b-nar/raw/main/config.json), [cartão do modelo](https://huggingface.co/ibm-granite/granite-speech-4.1-2b-nar).

Contas derivadas da implementação atual, para 60 s exatos em PCM mono 16 kHz:

| Quantidade | Fórmula | Resultado |
|---|---|---|
| Amostras | 60 × 16000 | 960000 |
| Frames empilhados | floor(960000/320) | T=3000 |
| Frames CTC | ceil(T/4) | 750 |
| Embeddings de áudio válidos | floor(T/5) | 600 |
| Slots de edição no pior caso do interleave | max(2 × 750 + 1,8) | 1501 |
| Sequência do editor no mesmo pior caso | 600 + 1501 | S=2101 |
| multilayer_features f32 | 3000 × 4096 × 4 bytes | 46,875 MiB |
| Logits CTC f32 | 750 × 100352 × 4 bytes | 287,109 MiB |
| Logits completos do editor f32 | 2101 × 100352 × 4 bytes | 804,289 MiB |
| Um tensor de scores de atenção f32 | 16 × 2101² × 4 bytes | 269,421 MiB |

São estimativas de tensores, não soma de consumo total ou pico medido. Não multiplicar automaticamente por 40 camadas: buffers podem ser reutilizados ou não materializados. Precisão, fusão, memória do runtime e pesos alteram o total. Atenção fundida e ArgMax podem reduzir materialização, mas saída pequena não prova que os logits internos deixaram de existir.

O teto 4096 da configuração não aprova S=2101 no export, na máscara, no runtime ou no aparelho. O comprimento CTC típico será menor que esse pior caso, mas não se pode truncar silenciosamente um áudio denso. Derivar o limite real, testar excesso e definir subdivisão automática quando necessário.

O salto T=2000 → 3000 aumenta buffers lineares em 1,5×; uma parte de atenção global pode crescer aproximadamente 2,25×, dependendo da sequência e do kernel. Há atenção local/contextual no encoder: medir em vez de aplicar uma única lei ao modelo inteiro.

Não foi encontrado chunk_size como campo do config.json consultado. context_size do encoder, block_size do projector, janela do arquivo e buckets T/S têm significados diferentes. Antes de alterar atenção interna, ler o código remoto NAR de uma revisão fixada e comparar com o código exportado, incluindo seus limites posicionais relativos.

## Como usar o dossiê Hy-MT2

Documento de referência: [DOSSIE-TRANSFERENCIA-HYMT2-PARA-WHISPER-NPU.txt](../DOSSIE-TRANSFERENCIA-HYMT2-PARA-WHISPER-NPU.txt). Ele foi escrito para Whisper, outra arquitetura. Não constitui ordem para executar scripts, usar serial histórico, alterar produção ou reproduzir todas as rodadas de Hy-MT2.

| Lição documentada | Aplicação a Granite | Limite |
|---|---|---|
| Bibliotecas/ADSP preparados antes do backend | Verificar ambiente e sonda HTP no UID normal antes de pesos grandes | Caminhos e versão do SoC devem ser atuais |
| RC explícito e bundle íntegro | Child nonzero invalida tentativa; rejeitar arquivo antigo | Relatório sem log/manifesto não basta |
| Captura origem/destino da mesma tentativa | Comparar features/embeddings e metadados na ponte NAR | Não há token-ponte AR no NAR |
| A–B–A e anti-replay | Áudios distintos impedem reuse de estado/saída stale | Comparar contra controles e IDs de tentativa |
| Oráculo independente e mutantes | Rejeitar layout, scale, mask, dtype e identidade incorretos | Não usar o código do candidato como sua própria referência |
| Gerações imutáveis e ponteiro ativo | Instalação/cache de um conjunto coerente com rollback | Vários renames de arquivos individuais não são uma transação |
| Microbenchmark diferente do produto | Medir JNI, cópias, sessão, frontend, junção e UID do app | Um número de kernel não escolhe backend do usuário |
| Thermal e dono do aparelho | Janela de medição reservada e estado observado | Não importar proibições/autorizações de uma campanha antiga |

O dossiê relata ponte funcional, discriminação de hipóteses em silício, integração e diferenças grandes entre R67/R69. Também explicita os vereditos finais negativos para validação global do kernel e aprovação Smart. Portanto, usar esses resultados como pistas técnicas, nunca como certificação de que Granite Smart já está resolvido.

Não transferem diretamente os layouts Q4_K/Q6_K, ACT q8_0/q8_1, guards e tolerâncias do fork ggml, nem a causalidade ou cache do decoder-only. Valores de tolerância desta campanha serão definidos para cada estágio/precisão e acompanhados de qualidade ASR.

## Fontes primárias e decisões de pesquisa

Consultadas em 20/10/2026. Os links de main/master podem mudar; a fase 00 fixa revisão e hash antes de usar código em conversão. Não chamar uma versão histórica de “última”.

| Fonte | Uso nesta proposta | Verificação pendente no piloto |
|---|---|---|
| [IBM NAR](https://huggingface.co/ibm-granite/granite-speech-4.1-2b-nar) | Arquitetura NAR e suporte a português | Revisão exata do modelo e código remoto auditado |
| [IBM config](https://huggingface.co/ibm-granite/granite-speech-4.1-2b-nar/raw/main/config.json) | Dimensões, escalas e limites declarados | Shapes/tipos/máscaras dos artefatos efetivamente usados |
| [ORT QNN EP](https://onnxruntime.ai/docs/execution-providers/QNN-ExecutionProvider.html) | Capacidades e opções HTP, cache e memória | Versão carregada, op coverage e partição real por estágio |
| [MNN](https://github.com/alibaba/MNN) | Primeiro candidato comum para OpenCL/Vulkan | Conversão não causal, GQA/RoPE e kernels no driver alvo |
| [llama.cpp OpenCL](https://github.com/ggml-org/llama.cpp/blob/master/docs/backend/OPENCL.md) | Alternativa de editor GPU com suporte Adreno | Suporte ao grafo NAR, e não só ao texto AR Granite |
| [ggml Vulkan](https://github.com/ggml-org/llama.cpp/blob/master/ggml/src/ggml-vulkan/CMakeLists.txt) | Features e compilação de kernels Vulkan | Extensões reais e despacho observado no Android |
| [ExecuTorch Qualcomm](https://docs.pytorch.org/executorch/stable/backends-qualcomm.html) | Alternativa delimitada para bloqueio específico QNN/ORT | Mesmo contrato e comparação com controle, sem presumir suporte NAR |
| [FlashAttention](https://github.com/Dao-AILab/flash-attention) | Algoritmos de atenção com menor materialização | Implementação Android equivalente disponível e numericamente correta |

A seleção MNN primeiro é decisão de investigação, não conclusão de superioridade. Cada runtime precisa demonstrar benefício e custo de manutenção antes de entrar no app.

## Recursos observados e incertezas

Inventário somente: host GUSTAVO/Xeon E5-2696 v3; aproximadamente 248 GiB livres em C e 130,5 GiB em D no momento da leitura. Não usar a antiga falta de espaço de 09/10 como estado atual.

ADB listou somente o PJA110 com serial 1164a04. O CPH2747/SM8850 do dossiê não estava visível nesse inventário. Nenhuma instalação, força de parada, benchmark ou alteração no telefone foi feita nesta revisão. Isso não define qual aparelho será usado na campanha: a fase 00 confirma o alvo e disponibilidade antes de mutação.

O lab histórico e worktree experimental são referências a inventariar, não fontes a mesclar indiscriminadamente. Dados privados permanecem fora do Git; benchmarks formais usarão corpus público/licenciado ou explicitamente autorizado.
