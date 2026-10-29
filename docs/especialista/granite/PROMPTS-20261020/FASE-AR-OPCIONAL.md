# Ordem FAR — Investigação autoregressiva opcional, modelo separado

Revisão 20261020.1. Não integra a sequência obrigatória F00–F12. Executar somente se um parecer do especialista abrir explicitamente esta mudança de arquitetura. O Smart NAR não precisa desta fase.

## Objetivo

Se o objetivo passar a exigir encoder/prefill NPU e decode token a token GPU literal, avaliar um modelo Granite Speech autoregressivo adequado ao português como família separada. Não tornar o editor NAR causal nem chamar essa modificação de equivalência.

## Tarefas obrigatórias

1. **FAR.1 — Seleção e custo.** Verificar na fonte oficial modelo/revisão/licença/idioma, arquitetura, tokenizer/processador e requisitos de memória. Comparar escopo e custo com a necessidade; não baixar variantes gigantes sem orçamento.
2. **FAR.2 — CPU independente.** Reproduzir áudio público PT-BR em referência fonte e controle CPU local, com decoder/beam/greedy/configuração fixados. Registrar qualidade e tempos encoder/prefill/primeiro token/decode/total.
3. **FAR.3 — Contrato AR.** Definir estados do encoder, eventual cross-attention, KV próprios do texto, posições/máscaras causais, tokenizer, EOS e geração. Não copiar shapes/layouts/token-ponte de Hy-MT2 sem verificar a arquitetura escolhida.
4. **FAR.4 — Backends mínimos.** Provar encoder/prefill HTP e primeiro passo de decoder OpenCL/Vulkan em micro/blocos e pesos reais antes do modelo completo. Medir cobertura/fallback/precisão, usando contratos/negativos independentes.
5. **FAR.5 — Ponte correta.** Captura de estados na mesma tentativa, tamanhos/dtypes/layout/escalas/posições e ownership/sync. A–B–A/anti-replay, erro/empty/EOS e um único handoff. Provar que não há duplicação do primeiro token nem reutilização indevida de estado.
6. **FAR.6 — Continuidade.** Primeiro token e pelo menos oito passos de decode com teacher forcing contra referência independente, depois geração livre e qualidade de texto. Atenção cruzada ou KV de cada camada devem pertencer ao mesmo áudio.
7. **FAR.7 — Piloto útil.** Comparar CPU/GPU/NPU possíveis e Smart com o mesmo modelo/configuração. Incluir carga, encoder, prefill, ponte, decode e total, memória/thermal e trechos longos segmentados com contrato próprio.
8. **FAR.8 — Decisão de arquitetura.** Mostrar benefício/custo de qualidade/latência/manutenção em relação ao NAR, sem misturar tok/s ou nomes de perfil. Recomendar nova campanha detalhada ou encerrar a hipótese. Não trocar o padrão do produto nesta investigação.

## Aceitação e entrega

Cumprir CONTRATO-EXECUTOR-20261020.md e testes/gates aplicáveis. Entregar RELATORIO-FASE-AR, report.json com phase_id=FAR, coverage.csv, revisão de modelo/processador, contratos/ponte/golden/mutantes, métricas e conclusão com limites.

O especialista decidirá se abre fases AR adicionais. Mesmo um piloto AR bem-sucedido não aprova automaticamente a migração do produto nem muda os vereditos NAR.
