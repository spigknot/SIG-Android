# Granite Android — programa de execução

Atualizado em 29/10/2026. O [plano v3.0](PLANO-GRANITE-20261029.md) é o documento vigente: CPU, Vulkan, NPU e Smart NPU → Vulkan; arquivo longo, investigação de janela de 60 s e transcrição ao vivo. OpenCL/F05 e Smart NPU → OpenCL estão suspensos.

Há **16 fases e 128 tarefas**, com prompts completos. Executar uma fase por ordem, devolver relatório e aguardar parecer. Os prompts estão preparados; nenhuma fase desta campanha v3 foi despachada nesta entrega. Começar pelo [prompt integral F00](PROMPTS-20261029/FASE-00-REBASELINE.md).

| Documento | Uso |
|---|---|
| [Plano v3](PLANO-GRANITE-20261029.md) | Arquitetura, prioridades, dependências, metas e quatro fases live |
| [Diagnóstico e fontes](DIAGNOSTICO-E-FONTES-20261029.md) | Código atual, pesquisa primária e lições transferíveis |
| [Contrato do executor](CONTRATO-EXECUTOR-20261029.md) | Provas, métricas, qualidade, ownership, gates e limites |
| [Campanha estruturada](CAMPANHA-20261029.json) | IDs, tarefas, prioridades e dependências |
| [Modelo de relatório](MODELO-RELATORIO-FASE-20261029.md) / [schema v3](SCHEMA-RELATORIO-FASE-v3.json) | Entrega obrigatória do executor |
| [Modelo de parecer](MODELO-PARECER-20261029.md) | Decisão e próxima ordem pelo especialista |
| [Estado](ESTADO.md) / [decisões](DECISOES.md) | Progresso observado e decisões preservadas |
| [Snapshot](SNAPSHOT-20261029.json) / [fontes fixadas](FONTES-20261029.json) | Identidade dos materiais desta revisão |
| [Validação](VALIDACAO-20261029.md) / [manifesto de hashes](SHA256SUMS-PLANO-20261029.txt) | Conferência do pacote documental |

## Prompts preparados

| Fase | Assunto |
|---|---|
| [F00](PROMPTS-20261029/FASE-00-REBASELINE.md) | Rebaseline e reserva de escopo |
| [F01](PROMPTS-20261029/FASE-01-PROTOCOLO.md) | Protocolo, referências e corpus |
| [F02](PROMPTS-20261029/FASE-02-CPU-CONTRATOS.md) | CPU, referência e política de backends |
| [F03](PROMPTS-20261029/FASE-03-AUDIO-LONGO.md) | Áudio longo e junção de chunks |
| [F04](PROMPTS-20261029/FASE-04-JANELA-60S.md) | Viabilidade da janela de 60 segundos |
| [F06](PROMPTS-20261029/FASE-06-VULKAN.md) | Vulkan: runtime, conversão e piloto independente |
| [F07](PROMPTS-20261029/FASE-07-NPU.md) | HTP/NPU com escalada de buckets |
| [F08](PROMPTS-20261029/FASE-08-SMART.md) | Smart: encoder HTP → editor Vulkan |
| [F09](PROMPTS-20261029/FASE-09-OTIMIZACOES.md) | Otimizações com ablação e semântica preservada |
| [F10](PROMPTS-20261029/FASE-10-COMPARACAO.md) | Comparação formal e escolha por perfil |
| [F11](PROMPTS-20261029/FASE-11-PRODUTO.md) | Integração do produto, cache e downloads |
| [F12](PROMPTS-20261029/FASE-12-ENTREGA.md) | Candidato de entrega por arquivo |
| [F13](PROMPTS-20261029/FASE-13-LIVE-CONTRATO.md) | Ao vivo: contrato, reaproveitamento e experiência |
| [F14](PROMPTS-20261029/FASE-14-LIVE-CPU.md) | Ao vivo: captura e pipeline incremental CPU |
| [F15](PROMPTS-20261029/FASE-15-LIVE-ACELERADO.md) | Ao vivo: aceleração, fila e integração |
| [F16](PROMPTS-20261029/FASE-16-LIVE-QUALIFICACAO.md) | Ao vivo: qualificação e candidato final |

F05 não tem prompt v3; o ID permanece reservado para o histórico OpenCL. F06 é independente e contém conversão/piloto completos. F13–F16 acrescentam o modo ao vivo sem depender da janela de 60 s. A numeração preserva as fases antigas para evitar executar o prompt errado.

## Histórico

[Plano v2](PLANO-GRANITE-20261020.md) e [plano v1](PLANO-GRANITE-20261009.md) permanecem como registro, não ordens vigentes. O [índice v2 arquivado](README-20261020-ARQUIVADO.md), [estado v2 arquivado](ESTADO-20261020-ARQUIVADO.md) e [decisões v2 arquivadas](DECISOES-20261020-ARQUIVADO.md) preservam os bytes locais anteriores à atualização dos ponteiros. Os links internos dessas cópias mantêm o endereço original; usar a data para distinguir histórico de vigente.

O manifesto v2 listava README/ESTADO/DECISOES mutáveis; para recuperar seus conteúdos desta base, usar as cópias arquivadas e o snapshot v3. Os documentos datados e prompts antigos não foram editados. A entrega atual altera documentação, não o aplicativo.
