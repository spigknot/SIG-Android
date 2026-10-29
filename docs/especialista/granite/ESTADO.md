# Estado da execução do Granite STT

Atualização vigente: 20/10/2026, São Paulo. Plano vigente: [v2.0](PLANO-GRANITE-20261020.md). Este quadro registra progresso real; não inicia agentes nem tarefas em segundo plano. Registro anterior preservado em [arquivo histórico](ESTADO-20261009-ARQUIVADO.md).

| Trabalho | Estado real | Próxima ação e dependência | Evidência |
|---|---|---|---|
| Auditorias G00 de 08/09 de outubro | Históricas, preservadas | Revalidar identidades antes de editar/medir | Mapa, histórico e runtimes de 08/10 |
| Revisão do plano com dossiê, áudio longo e seis rotas | Entrega documental preparada | Encaminhar F00 ao executor | [Índice](README.md), [diagnóstico](DIAGNOSTICO-E-FONTES-20261020.md) |
| F00 Rebaseline | Prompt pronto; não despachado | Executor lê fontes e devolve inventário | [F00](PROMPTS-20261020/FASE-00-REBASELINE.md) |
| F01 Evidências | Condicional | Parecer F00; absorve G01 se ainda pendente | [F01](PROMPTS-20261020/FASE-01-EVIDENCIAS.md) |
| F02 CPU/contratos | Condicional | Parecer F01; integra G02/G03 por unidade | [F02](PROMPTS-20261020/FASE-02-CPU-CONTRATOS.md) |
| F03 Áudio longo | Patch local anterior existe; aceitação real pendente | Auditar após contratos, preservar o diff atual | [F03](PROMPTS-20261020/FASE-03-AUDIO-LONGO.md), snapshot |
| F04 Janela 60 s | Hipótese; nenhum novo export/run nesta revisão | Shapes, memória e qualidade F02/F03 | [F04](PROMPTS-20261020/FASE-04-JANELA-60S.md) |
| F05/F06 GPU | Pilotos planejados; não executados nesta revisão | Referência, runtime e dispositivo | [OpenCL](PROMPTS-20261020/FASE-05-OPENCL.md), [Vulkan](PROMPTS-20261020/FASE-06-VULKAN.md) |
| F07 HTP/NPU | Piloto planejado; guard de bucket permanece | Dispositivo reservado, libs e contrato | [F07](PROMPTS-20261020/FASE-07-NPU.md) |
| F08 Smart | Hipótese NAR por estágios | Encoder HTP e editor GPU comprovados | [F08](PROMPTS-20261020/FASE-08-SMART.md) |
| F09/F10 Otimizar/comparar | Condicionais | Rotas funcionais e corpus separado | [F09](PROMPTS-20261020/FASE-09-OTIMIZACOES.md), [F10](PROMPTS-20261020/FASE-10-COMPARACAO.md) |
| F11/F12 Produto/entrega | Condicionais | Seleção de perfis e revisão | [F11](PROMPTS-20261020/FASE-11-PRODUTO.md), [F12](PROMPTS-20261020/FASE-12-ENTREGA.md) |
| FAR | Opcional; não aberta | Somente decisão explícita de investigar outro modelo AR | [FAR](PROMPTS-20261020/FASE-AR-OPCIONAL.md) |

Executor externo não está vinculado nesta entrega; nenhuma mensagem foi enviada a outro chat. O primeiro prompt pode ser encaminhado manualmente. Cada fase termina com relatório ao especialista, que emitirá parecer e a ordem seguinte revisada.

ADB em inventário de leitura listou somente PJA110/1164a04. A disponibilidade atual do CPH2747/SM8850 não foi demonstrada. Nenhum dispositivo foi reservado ou alterado, e não houve instalação/force-stop/benchmark.

As cinco alterações locais anteriores de chunks de 40 s foram preservadas por SHA. Arquivos e dossiês de outras campanhas ficaram fora desta edição. Ver [validação desta entrega](VALIDACAO-20261020.md). Nenhuma rota acelerada ou janela 60 s recebeu nova aprovação por este plano.

## Registro anterior de 09/10 — histórico, não estado atual

Atualizado em 09/10/2026, São Paulo. Plano v1.0. Este quadro descreve trabalho real; não inicia execução em segundo plano.

| Tarefa | Responsável | Estado | Dependência ou bloqueio | Próxima ação | Evidência |
|---|---|---|---|---|---|
| G00 Inspeção main e experimental | Especialista e subagente de código | Concluída | Snapshot de 08/10; reconfirmar ao editar | Integrar por unidade, preservando main | [Mapa](MAPA-CODIGO-20261008.md) |
| G00 Auditoria de setembro | Especialista e subagente de evidências | Concluída | Recomputação offline, sem novos runs | Substituir interpretação do extrator | [Histórico](HISTORICO-EVIDENCIAS-20261008.md) |
| G00 Pesquisa de runtimes | Especialista e subagente de pesquisa | Concluída | Fontes oficiais; compatibilidade NAR Android ainda experimental | Preparar G04 após contratos | [Runtimes](RUNTIMES-E-PILOTOS-20261008.md) |
| Plano e primeira ordem | Especialista | Entrega documental validada | Versionamento em checkout isolado | Receber identificação ou entrega do executor | [Plano](PLANO-GRANITE-20261009.md), [ordem](ORDEM-G01-20261009.md), [validação](VALIDACAO-20261009.md) |
| G01 Extrator offline | Executor externo ainda não vinculado | Pronta, não despachada | Usuário indica chat executor ou encaminha manualmente | Executar ordem e devolver patch/manifestos | [Ordem G01](ORDEM-G01-20261009.md) |
| G02 Parser de vocabulário | Executor a definir | Planejada | Revisão de G01; checkout limpo | Emitir ordem pequena, sem portar engine inteiro | Plano F1 |
| G03 Contratos, sessão e bucket | Executor a definir | Planejada | Parser e instrumentação definida | Tornar configuração imutável e provar sessão real | Plano F1/F2 |
| G04 Pilotos de hardware | Especialista decide, executor mede | Condicional | Contratos e reserva do telefone; versão nativa efetiva | Selecionar micrografo e controle por API | Plano F3 e matriz de runtimes |

Executor externo: não identificado. O chat `Solucionar deadend GPU NPU` foi lido; preparou o handoff e estava fazendo inventário de arquivos. Nenhuma mensagem foi enviada a ele. Os três subagentes fizeram auditorias neste chat e não representam vínculo com o executor externo.

Telefone: CPH2747/SM8850 acessível por ADB na leitura; serial físico conferido. Nenhum lock, exclusividade, instalação, force-stop ou benchmark foi realizado. APK SHA observado está no snapshot; proveniência e libs efetivas pendentes.

Arquivos de outras tarefas e todos os históricos foram preservados. G01 não requer telefone, modelos novos, R2 ou aprovação de release. O documento da ordem permite encaminhamento manual imediato.
