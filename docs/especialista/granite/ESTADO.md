# Estado da execução Granite STT

Atualização vigente: 29/10/2026, São Paulo. [Plano v3](PLANO-GRANITE-20261029.md). Registro anterior preservado no [estado v2](ESTADO-20261020-ARQUIVADO.md). Este quadro não inicia agentes ou tarefas.

## Estado real

- Planejamento v3 com 16 fases/128 tarefas preparado; nenhuma fase nova despachada.
- OpenCL/F05 e Smart NPU → OpenCL suspensos por decisão do usuário. Não houve remoção de biblioteca ou alteração de aplicativo nesta entrega documental.
- HEAD observado 254b4a2; chunking 40 s/2 s está commitado em a2b4d58. Sua aceitação funcional integral continua sendo trabalho F03; não é mais correto descrevê-lo como patch local pendente.
- NativeDependencyManager indica pacote 14; Gradle declara ORT Android QNN 1.29.0. Biblioteca nativa carregada e runtime efetivo serão revalidados em F00/F02.
- CPU é o caminho NAR disponível; UI acelerada continua indisponível no código inspecionado. Vulkan, NPU integral e Smart não ganharam validação por este planejamento.
- 60 s por janela ainda é hipótese F04. Áudio longo não depende dela.
- Live Granite é objetivo novo F13–F16; nomes de editor live no layout não comprovam implementação. Whisper fornece componentes candidatos a reaproveitamento.
- Nenhum benchmark, instalação, microfone, publicação, commit ou push foi executado nesta revisão.

## Fases

| Fase | Estado | Ordem |
|---|---|---|
| F00 | Pronta para despacho | [Prompt](PROMPTS-20261029/FASE-00-REBASELINE.md) |
| F01 | Condicional; não executada | [Prompt](PROMPTS-20261029/FASE-01-PROTOCOLO.md) |
| F02 | Condicional; não executada | [Prompt](PROMPTS-20261029/FASE-02-CPU-CONTRATOS.md) |
| F03 | Condicional; não executada | [Prompt](PROMPTS-20261029/FASE-03-AUDIO-LONGO.md) |
| F04 | Condicional; não executada | [Prompt](PROMPTS-20261029/FASE-04-JANELA-60S.md) |
| F06 | Condicional; não executada | [Prompt](PROMPTS-20261029/FASE-06-VULKAN.md) |
| F07 | Condicional; não executada | [Prompt](PROMPTS-20261029/FASE-07-NPU.md) |
| F08 | Condicional; não executada | [Prompt](PROMPTS-20261029/FASE-08-SMART.md) |
| F09 | Condicional; não executada | [Prompt](PROMPTS-20261029/FASE-09-OTIMIZACOES.md) |
| F10 | Condicional; não executada | [Prompt](PROMPTS-20261029/FASE-10-COMPARACAO.md) |
| F11 | Condicional; não executada | [Prompt](PROMPTS-20261029/FASE-11-PRODUTO.md) |
| F12 | Condicional; não executada | [Prompt](PROMPTS-20261029/FASE-12-ENTREGA.md) |
| F13 | Condicional; não executada | [Prompt](PROMPTS-20261029/FASE-13-LIVE-CONTRATO.md) |
| F14 | Condicional; não executada | [Prompt](PROMPTS-20261029/FASE-14-LIVE-CPU.md) |
| F15 | Condicional; não executada | [Prompt](PROMPTS-20261029/FASE-15-LIVE-ACELERADO.md) |
| F16 | Condicional; não executada | [Prompt](PROMPTS-20261029/FASE-16-LIVE-QUALIFICACAO.md) |
| F05 | Suspensa; sem ordem v3 | Histórico v2 apenas |
| FAR | Fora da campanha ativa | Modelo AR separado exigiria nova decisão |

Próxima ação: encaminhar F00 ao executor. Após cada relatório, o especialista emite parecer e ajusta a próxima ordem. Aprovação parcial de encoder HTP/editor Vulkan pode liberar F08 sem aprovar NPU integral. Resultados de arquivo não aprovam automaticamente live.
