# Ordem F01 — Evidências históricas e protocolo de medição

Revisão 20261020.1, condicionada ao parecer de F00. Execute somente após receber esta fase do especialista. Entregar um extrator correto e um protocolo que não produza falsos sucessos. Não coletar novamente 96 runs nem iniciar matriz de hardware para corrigir uma tabela.

## Leituras e entradas

Ler AGENTS, CONTRATO-EXECUTOR-20261020.md, relatório/parecer F00 e ORDEM-G01-20261009.md integral. Inputs são os paths/manifestos confirmados em F00, não caminhos históricos assumidos. Consultar HISTORICO-EVIDENCIAS-20261008.md e o debug runner/protocol atuais.

## Tarefas obrigatórias

1. **F01.1 — Fechar G01 offline.** Se não houver entrega já aceita, implementar o extrator auditável previsto em tools/granite/nar/audit_benchmark_events.py e teste focal correspondente, usando biblioteca padrão e import sem efeitos. Se já existir, testar e corrigir somente lacunas. Manter CLI/padrões exatos da ordem G01 ou documentar uma migração deliberada antes do uso.
2. **F01.2 — Corrigir a semântica dos eventos.** Extrair memória de before/after da mesma tentativa; end ocorre após release. Preservar eventos originais e emitir exclusões por motivo. Distinguir ausência de campo, ausência de captura e valor zero. Não fabricar ID estrito para legado incompleto.
3. **F01.3 — Recalcular quadros antigos.** Reconstruir os 12 inputs públicos × 8 perfis quando presentes, por identidades disponíveis. Recalcular deltas pareados H8/Q4/N1 sem valores literais de aprovação. A ordem anterior documenta a mudança de sinal de N1 e números de diagnóstico; eles são controles históricos, não dados para preencher lacunas.
4. **F01.4 — Sessão e tempo históricos.** Verificar os dez ciclos ArgMax e se recarregam; distinguir warm real de reload e mudança t400→t200 dentro da inferência. Interpretar corretamente os eventos de parser antes/depois. Emitir errata que diga precisamente o que os registros permitem concluir e o que não permitem.
5. **F01.5 — Testar o extrator.** Fixtures pequenas herméticas cobrem ordem/duplicação/ausência de eventos, run_id cruzado, valores inválidos, unidades, release, legado, JSON truncado e exclusões. Reexecutar duas vezes sobre os mesmos inputs e comparar outputs determinísticos, excluindo metadados temporais explicitamente definidos. Verificar manifesto nos dois sentidos.
6. **F01.6 — Especificar eventos futuros.** Definir schema versionado e configuration_id/run_id/stage_id/session_id/chunk_id. Tempos monotônicos, marcações de memória distintas, duração/amostras, backend pedido/observado, strict/fallback, criação/cache/compile, propriedade dos tensores e erros com RC. A configuração fica registrada antes da execução.
7. **F01.7 — Adequar a captura mínima.** Inspecionar GraniteNarBenchmarkProtocol.kt, GraniteNarSmokeTestActivity.kt e scripts/run-granite-nar-adb-benchmark.ps1. Corrigir somente a instrumentação necessária em unidade revisável: sessão/bucket observáveis, child RC propagado, timeout terminal, logs preservados antes de limpeza e resultado atômico por tentativa. Não adicionar execução GPU fictícia ao enum para preencher saída. Se houver Android alterado, executar os três gates.
8. **F01.8 — Provar que falhas são detectadas.** Mutantes de wrapper child exit não zero com arquivo antigo presente, evento final faltante, replay de run anterior, identidade de APK/modelo errada, fallback em strict, contador não finito e manifesto com extra/arquivo ausente devem ser rejeitados pelo checker real. Exibir gate/RC de cada rejeição.
9. **F01.9 — Preparar corpus e métricas.** Confirmar áudio público/licenciado, referência humana e partições separadas. Implementar/reusar normalização versionada WER/CER e cálculo de I/D/S com casos verificáveis independentes. Separar fixtures de engenharia do conjunto reservado; documentar aquisição necessária para F10.
10. **F01.10 — Pilotar o protocolo com baixo custo.** Preferir fixtures e um processo sintético para integridade da captura. Uma inferência CPU curta só se necessária, aparelho reservado e autorizada no parecer; não é bateria comparativa. Não exigir telefone para concluir a correção histórica.

## Aceitação

- Métricas antigas regeneráveis dos bytes, errata sem reclassificar legado como evidência estrita.
- Novos eventos distinguem end/after_infer/after_release e warm/reload; negativos realmente falham.
- Código da extração não depende de Android, ADB, modelo grande ou estado da máquina para seus testes.
- Nenhuma alteração de estratégia/precisão/operadores do NAR nesta fase.
- Testes focais da ferramenta e suíte pertinente de tools/granite/nar/test_nar_tools.py. Para alterações Android: testDebugUnitTest, lintDebug e assembleDebug. Para alteração do harness: suíte -Quiet e gate correspondente.

## Entrega

RELATORIO-FASE-01, report.json/coverage.csv, patch, manifests de entrada/saída, errata G01, tabelas corrigidas e script que as regenera, schemas de eventos, fixtures/mutantes, corpus-manifest e comandos/RC. Dizer separadamente “G01 corrigida”, “protocolo testado” e “captura real feita/não feita”.

Retornar ao especialista para revisão da F02. Não usar a tabela histórica para declarar uma rota acelerada aprovada.
