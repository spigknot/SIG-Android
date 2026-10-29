# Estado da execução do Granite STT

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
