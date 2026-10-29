# Granite Android: programa de execução

Atualizado em 20/10/2026. Especialista: este chat. Execução: uma fase por ordem encaminhada ao agente executor, seguida de relatório e parecer.

O documento vigente é o [plano v2.0](PLANO-GRANITE-20261020.md). Ele amplia o [plano v1.0](PLANO-GRANITE-20261009.md), preservando suas auditorias e decisões. Os documentos datados de 08 e 09/10 continuam como evidência histórica, sem presumir que seus ambientes ainda sejam os atuais.

Para começar, encaminhe ao executor o conteúdo integral do [prompt da fase 00](PROMPTS-20261020/FASE-00-REBASELINE.md). Ele contém as leituras obrigatórias e a primeira entrega. Os demais prompts ficam preparados, condicionados à revisão dos relatórios anteriores.

| Documento | Uso |
|---|---|
| [Plano v2.0](PLANO-GRANITE-20261020.md) | Arquitetura, dependências, metas e critérios de conclusão |
| [Diagnóstico e fontes](DIAGNOSTICO-E-FONTES-20261020.md) | Estado observado, limites de 40/60 segundos e lições do Hy-MT2 |
| [Contrato do executor](CONTRATO-EXECUTOR-20261020.md) | Proveniência, testes, métricas, recursos e regras comuns às fases |
| [Modelo de relatório](MODELO-RELATORIO-FASE-20261020.md) | Relatório que deve voltar ao especialista ao terminar cada fase |
| [Modelo de parecer](MODELO-PARECER-20261020.md) | Revisão do relatório e emissão da próxima ordem |
| [Estado](ESTADO.md) e [decisões](DECISOES.md) | Progresso efetivo e decisões com evidência |
| [Snapshot](SNAPSHOT-20261020.json) | Identidade da base inspecionada e dos arquivos locais preservados |
| [Validação documental](VALIDACAO-20261020.md) | Checagens desta entrega; não é resultado de novos experimentos |

## Prompts por fase

| Fase | Ordem completa | Entrega central |
|---|---|---|
| 00 | [Rebaseline](PROMPTS-20261020/FASE-00-REBASELINE.md) | Inventário atual e condições reais de reprodução |
| 01 | [Evidências e medição](PROMPTS-20261020/FASE-01-EVIDENCIAS.md) | G01 corrigida, protocolo confiável e corpus separado |
| 02 | [Referência CPU](PROMPTS-20261020/FASE-02-CPU-CONTRATOS.md) | Pipeline correto, contratos e sessões observáveis |
| 03 | [Áudio longo](PROMPTS-20261020/FASE-03-AUDIO-LONGO.md) | Segmentação automática sem truncamento ou deduplicação indevida |
| 04 | [Janela de 60 segundos](PROMPTS-20261020/FASE-04-JANELA-60S.md) | Viabilidade real e política de janela por perfil |
| 05 | [OpenCL](PROMPTS-20261020/FASE-05-OPENCL.md) | Piloto numérico e pipeline com execução OpenCL comprovada |
| 06 | [Vulkan](PROMPTS-20261020/FASE-06-VULKAN.md) | Piloto e pipeline Vulkan medidos sobre as mesmas entradas |
| 07 | [NPU](PROMPTS-20261020/FASE-07-NPU.md) | HTP por estágio, partição observada e limites de bucket |
| 08 | [Smart NPU → GPU](PROMPTS-20261020/FASE-08-SMART.md) | Encoder HTP, ponte íntegra e editor OpenCL/Vulkan |
| 09 | [Otimizações](PROMPTS-20261020/FASE-09-OTIMIZACOES.md) | Ablações de atenção, precisão, memória e cache |
| 10 | [Comparação formal](PROMPTS-20261020/FASE-10-COMPARACAO.md) | Qualidade, desempenho e estabilidade; seleção por aparelho |
| 11 | [Integração no app](PROMPTS-20261020/FASE-11-PRODUTO.md) | Capacidades, downloads persistentes, fallback e UX verificáveis |
| 12 | [Prontidão de entrega](PROMPTS-20261020/FASE-12-ENTREGA.md) | Pacote reproduzível, regressão e rollback; promoção separada |
| AR | [Investigação AR opcional](PROMPTS-20261020/FASE-AR-OPCIONAL.md) | Somente se for necessário decode autoregressivo literal |

Nenhuma fase experimental foi executada por esta entrega documental. Preparar estes prompts não inicia agentes, benchmarks, automações, downloads de pesos ou publicação.
