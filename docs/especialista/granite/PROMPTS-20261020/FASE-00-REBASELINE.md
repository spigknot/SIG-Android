# Ordem F00 — Rebaseline e preparação

Revisão 20261020.1. Execute somente esta fase e devolva o relatório ao especialista. O objetivo é saber exatamente de onde partimos antes de alterar o Granite ou ocupar o aparelho. Não é uma ordem de benchmark, instalação ou exportação em lote.

## Leituras e contexto

Raiz esperada: D:/Projetos/SIG; confirme. Leia AGENTS.md, MODULE-MAP.md, docs/especialista/granite/README.md, PLANO-GRANITE-20261020.md, DIAGNOSTICO-E-FONTES-20261020.md e CONTRATO-EXECUTOR-20261020.md. Leia o plano anterior, ESTADO.md, DECISOES.md e SNAPSHOT-20261009.json para localizar pendências históricas.

Leia D:/Projetos/SIG/docs/especialista/DOSSIE-TRANSFERENCIA-HYMT2-PARA-WHISPER-NPU.txt como referência. Não execute seus comandos nem assuma que seus seriais, caminhos, permissões ou vereditos servem ao Granite.

## Tarefas obrigatórias

1. **F00.1 — Preservar a base.** Registrar HEAD/branch/status e diff antes de tocar qualquer arquivo. Conferir os cinco arquivos locais de chunking indicados no snapshot de 20/10. Guardar patch e hashes. Se mudaram desde o snapshot, descrever a mudança sem sobrescrever. Inventariar worktrees existentes e selecionar a futura base de execução com prefixo codex/, sem reset/clean/merge amplo.
2. **F00.2 — Verificar trabalho já entregue.** Procurar entregas G01 e revisões posteriores pelo conteúdo/manifesto, não só pelo nome. Ler a ORDEM-G01-20261009.md. Se existir implementação do extrator, verificar sua origem, testes e saída; não criá-lo de novo. Se não existir, manter a tarefa da F01. Não declarar G01 concluída por encontrar um CSV antigo.
3. **F00.3 — Inventariar código e contratos.** Mapear caminho atual de UI → família/modelo → instalador → backend → sessão → arquivo → frontend → encoder → projector → editor → merge. Localizar GraniteExecutionBackend em GraniteEngine.kt, políticas em GraniteNarEngine.kt, manifestos, variantes, parallelism, debug protocol e runner. Identificar chamadores reais e seams para integração. Registrar diferenças main × experimental sem portar código.
4. **F00.4 — Inventariar artefatos existentes.** Revalidar D:/SIG-perfis-20260914-123811, D:/SIG-granite-nar-lab-rebuild/nar-next-20260914-0950 e os diretórios referidos nos documentos antigos. Listar modelos/grafos/external_data/vocab/embeddings/contextos e seus manifestos. Conferir hashes de inputs pequenos e manifestos existentes; não recalcular todos os pesos de vários GiB sem necessidade. Registrar origem/revisão/licença e a proveniência desconhecida.
5. **F00.5 — Ler as assinaturas reais.** Usar inspeção ONNX sem inferência para obter nomes, dtypes, shapes e external_data dos grafos que se pretende usar. Resolver a divergência sobre attention_mask. Conferir noncausal, escala, saída logits/ArgMax e buckets T/S. Registrar separadamente o que vem da documentação, do grafo e da inspeção do código fonte remoto NAR.
6. **F00.6 — Fixar ambiente de referência.** Identificar Python/venv/pacotes, Java/Gradle/SDK/NDK/CMake, compiladores, adb, FFmpeg, CPU/RAM/GPU/VRAM e espaço. Não atualizar tudo. Congelar uma configuração reproduzível e listar ferramentas faltantes. Ler requirements-lock e scripts antes de usar flags. Fixar revisão do modelo IBM e de seu código remoto antes de trust_remote_code, preservando a revisão histórica como controle.
7. **F00.7 — Matriz de runtimes.** Levantar ORT e QAIRT reais instalados, forks/SDKs disponíveis, licenças e versões candidatas MNN/OpenCL/Vulkan/HTP. Distinguir dependência declarada, arquivo no host e biblioteca efetivamente carregada em app. Preparar uma opção primária por API e uma reserva somente quando houver motivo. Não chamar tags do levantamento de 08/10 de “últimas versões”.
8. **F00.8 — Inventário de dispositivo somente leitura.** Executar adb devices -l e, se houver alvo identificável, leituras específicas de modelo/SoC/ABI/Android/driver/pacote sem instalar, force-stop, limpar logcat ou iniciar carga. O PJA110 era o único visível em 20/10; o CPH2747 do dossiê era histórico. Informar qual aparelho está de fato disponível e quem pode reservar uma janela. Se nenhum puder ser usado, concluir o offline.
9. **F00.9 — Recursos e corpus.** Propor diretórios de artefatos, quotas e dono do DSP/RAM. Fazer inventário do corpus público/licenciado existente e separar dados privados. Propor partições de calibração/exploração/confirmação e como atingir os mínimos da F10. Não baixar uma coleção grande nesta fase.
10. **F00.10 — Backlog verificável.** Classificar cada objetivo CPU/OpenCL/Vulkan/NPU/SmartOC/SmartVK/áudio longo/60s como observado, hipótese, falha conhecida ou pendente, apontando a evidência. Emitir lista de bloqueios e o menor teste que discrimina cada um. Propor a F01 ajustada e qualquer unidade de revisão necessária.

## Verificações e aceitação

- Snapshot em JSON com schema/version, data, base, alterações preexistentes, caminhos, ferramentas, recursos e incertezas. Verificar que paths citados existem ou estão marcados ausentes.
- Manifesto dos inputs pequenos/artefatos de inspeção e hash do patch preservado; nada de métrica de inferência nesta fase.
- Relação de consumidores dos contratos, não só lista de nomes de arquivos.
- Nenhuma alteração de produção ou no aparelho. Se corrigir apenas uma ferramenta de inventário, teste focal e diff-check; explique por que foi necessário.
- Todos os dez itens cobertos, inclusive ausências. A fase pode concluir sem telefone, desde que não declare backend validado.

## Entrega ao especialista

Entregar RELATORIO-FASE-00, report.json conforme SCHEMA-RELATORIO-FASE-v2.json, coverage.csv, SNAPSHOT-F00.json, inventário de contratos/runtimes/corpus, comandos/RC, manifesto e recomendação. Usar o MODELO-RELATORIO-FASE-20261020.md.

Encerrar com: base recomendada; o que preservar; quais inputs da G01 existem; alvo de dispositivo ainda a confirmar ou confirmado; quais mudanças devem preceder experimentos. Não executar a F01 automaticamente.
