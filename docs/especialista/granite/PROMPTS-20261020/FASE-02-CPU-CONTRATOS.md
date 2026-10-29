# Ordem F02 — Referência CPU e contratos do NAR

Revisão 20261020.1, condicionada à revisão de F01. Execute em unidades pequenas: parser, contrato/sessão e otimizações de saída são patches separáveis. Entregar uma referência confiável antes de converter para outros backends. Não copiar o GraniteNarEngine inteiro do worktree experimental.

## Entradas e leituras

Ler AGENTS/MODULE-MAP, contrato comum, pareceres F00/F01, DECISOES D01–D12 e mapa de consumidores. Trabalhar com a revisão de modelo, vocab, embeddings, frontend e grafos fixados na F00. Ler código remoto IBM NAR dessa revisão antes de trust_remote_code.

## Tarefas obrigatórias

1. **F02.1 — Golden independente.** Preparar referência PyTorch da fonte revisada e ORT CPU do grafo real, inicialmente em áudio curto. Fixar dtype/atenção, normalização e sementes. Exportar somente os tensores necessários para comparar frontend, encoder, projector, embeddings, editor e IDs. Não gerar todos os buckets como primeira ação.
2. **F02.2 — Contrato executável do modelo.** Inspecionar nomes, dtypes e shapes do consumidor e dos artefatos instalados; resolver attention_mask, logits/ArgMax e versões. Validar manifesto/loader contra o grafo real e falhar antes da inferência se incompatíveis. Confirmar CTC blank, ordem do vocab, escala, posições e trechos áudio/texto. Não inferir contrato por nome de arquivo.
3. **F02.3 — Parser mínimo e correto.** Auditar parser regex atual e a alternativa histórica. Implementar/adotar seam pequeno somente se comprovar paridade e vantagem. Testar arrays e objetos piece→id realmente suportados, escapes JSON/Unicode/pares substitutos, aspas/barra/controle, tokens de byte-level, ordem, duplicatas, lacunas, IDs inválidos e JSON truncado. Nunca compactar silenciosamente IDs faltantes. Comparar os 100352 itens do vocab real ou o tamanho efetivo documentado, por índice e conteúdo.
4. **F02.4 — Medir parser sem exagero.** Separar leitura do disco, parse, preparação de mapa e carga do modelo; usar mesmo arquivo/caches definidos. Resultados históricos de duas cargas não autorizam alegar redução universal do tempo total. Reportar memória/tempo e impacto no APK integrado.
5. **F02.5 — Configuração imutável.** Criar/ajustar contrato de execução por estágio contendo modelo/grafo/variante/bucket/backend/strict/threads/precisão/máscara. Mudança incompatível abre nova sessão com identidade; estado mutável da UI não pode alterar metade da inferência. Não implementar agora todos os runtimes.
6. **F02.6 — Warm real e buckets.** Escolher bucket necessário antes de medir. Registrar sessão, PID, grafo e recompilação; provar warmup e medidas na mesma sessão. Reproduzir o caso de 175 frames que trocava sessão t400→t200 e mostrar seu tratamento. Usar menor bucket compatível sem truncar; se não couber, encaminhar ao planejador de chunks, não devolver maior bucket e perder áudio.
7. **F02.7 — Máscara e padding.** Comparar comprimentos válidos, padding, limites e editor não causal no controle fonte/ONNX/app. Testar perturbação de contexto futuro que distinga máscara causal de bidirecional. Não aprovar padding como neutro por produzir tensores de shape correto.
8. **F02.8 — Recursos e cancelamento.** Corrigir lifetime/fechamento de tensors/results/sessions/buffers em caminhos de sucesso, exceção e interrupção, inclusive falha entre encoder/projector/editor. Testar troca de variante, load/release repetidos e leitura após close quando cabível. Medir memória após inferência e após release separadamente; não chamar dez reloads de soak warm.
9. **F02.9 — ArgMax condicional.** Se aplicável à memória/latência, testar grafo de IDs contra logits do mesmo modelo/precisão e consumidor real: nomes, shape, INT64, faixa, blank, offset e empate. Mutantes de dtype/shape/offset precisam falhar. Medir total e memória interna observável; se não houver ganho ou contrato pronto, manter candidato experimental e entregar a base CPU sem forçar adoção.
10. **F02.10 — Qualidade CPU e regressão.** Rodar corpus piloto PT-BR com referência humana, incluindo nomes/números/negação/repetição/silêncio, e comparar a base final à versão CPU anterior. A normalização não pode esconder perda de palavras. Documentar limitações já existentes do modelo, separadas das mudanças deste patch.

## Aceitação

- Referência e candidato independentes, comparações por estágio e tolerâncias justificadas antes dos runs de confirmação.
- Parser real por índice sem regressão de Unicode/tokenização; erro de manifesto/contrato detectado antes de crash.
- Sessões e configuração observáveis; warm legítimo; nenhum chunk excedente truncado.
- Recursos fechados em falhas e cancelamento; consumo estabilizado em série curta real.
- IDs ArgMax exatos onde a transformação promete equivalência; alternativa rejeitada sem comprometer o controle.
- Testes focais e, para Android, testDebugUnitTest/lintDebug/assembleDebug; KDoc/MODULE-MAP dos fontes novos.

## Entrega

RELATORIO-FASE-02/report.json/coverage.csv; patches por unidade; golden/tensor manifests; contrato I/O e negativos; eventos de sessões; métricas de parse/memória/tempo; qualidade piloto e logs dos gates. Não atribuir ao novo parser um ganho de backend.

O especialista pode aceitar 02A e pedir retrabalho em 02B/02C. Devolver dependências exatas para F03/F05/F07 e aguardar a próxima ordem.
