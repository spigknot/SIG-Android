# Ordem F06 — Granite NAR em Vulkan

Revisão 20261020.1, condicionada ao parecer F02 e aos contratos de shapes aplicáveis. Reutilizar conversão e referências da F05 se corretas, mas provar a API Vulkan independentemente. Falha/ganho OpenCL não prevê automaticamente o resultado Vulkan.

## Entradas

Ler AGENTS, contrato comum, F00/F02, resultado F05 e decisão do especialista sobre runtime. Preferir o mesmo runtime/conversão quando isso preservar o modelo e permitir comparação útil. Se outro runtime for necessário, identificar os fatores diferentes e manter controle com os mesmos pesos/precisão/entradas.

## Tarefas obrigatórias

1. **F06.1 — Capacidades.** Inventariar versão Vulkan, dispositivo/driver e extensões realmente usadas: limites de storage buffers/alocação, FP16/INT8, subgroup/cooperative matrix se aplicável. Feature de build não prova feature habilitada ou kernel ativo. Não exigir extensões que o shader de controle não usa.
2. **F06.2 — Build e micrografo.** Compilar ABI/shaders com flags congeladas, executar MatMul/normalização/softmax/transferências pequenas e capturar dispatch/sincronização. Comparar referência independente e rejeitar fallback em strict. Registrar todos os buffers e conversões de layout.
3. **F06.3 — Atenção real.** Rodar o mesmo bloco de editor/pesos/entradas da F05 com bidirecional, GQA, RoPE, posições e padding. Testar S pequeno e representativo, bordas não múltiplas do tile, escala e logits. Não habilitar Flash Attention apenas por uma flag sem provar a variante de shader selecionada.
4. **F06.4 — Sincronização e negativos.** Testar barriers/fences e ownership de buffers host/GPU, perturbações de stride/layout/mask/posição/scale e resultado stale. Verificar que o erro é detectado no consumidor real. Se um teste de falta de sincronização for não determinístico, usar instrumentação determinística para o contrato e soak complementar.
5. **F06.5 — Estágios e pipeline.** Validar editor completo e demais estágios viáveis, preservando CTC/interleave/escalas. Mostrar o plano efetivo por estágio. Não chamar uma rota mista de Vulkan integral; manter editor validado disponível para F08.
6. **F06.6 — Memória e cache.** Medir upload de pesos, cópias, cache de shader/pipeline, recriação por bucket e memória nativa/GPU observável. Validar warm na mesma sessão. Cache persistente inclui identidade do driver/runtime/grafo; mudança de driver invalida somente entradas incompatíveis.
7. **F06.7 — Áudio real.** Usar exatamente os áudios/cortes públicos do piloto OpenCL/CPU, qualidade e categoria de precisão equivalentes. Incluir múltiplos chunks e A–B–A. Verificar erro de shader/device, cancelamento, release e ausência de vazamento de conteúdo.
8. **F06.8 — Medição piloto pareada.** Separar kernels, transferências, frontend, enc/proj/editor e total; medir frio/quente, memória e thermal. Alternar ordem com controle, reportar cada medida. Não produzir p95 com n pequeno nem escolher a amostra mais rápida.
9. **F06.9 — Janela máxima.** Validar os buckets de uso propostos sem remover proteção de outros backends. Se 60 s não for viável, usar o planejador para janelas menores. Nunca pad/truncar para encaixar em bucket inadequado.
10. **F06.10 — Parecer técnico proposto.** Identificar ganho/perda por gargalo, diferenças de implementação em relação ao OpenCL, bloqueios mínimos e oportunidade de fusão/atenção. Se inviável, entregar reproducer mínimo e a mudança necessária para reabrir; não criar opção de UI sem funcionalidade.

## Aceitação

- Mesma semântica NAR e prova independente de dispatch Vulkan.
- Bloco/pipeline e consumidor com layout/ownership corretos; mutantes relevantes rejeitados.
- PT-BR e qualidade piloto; custos de upload/sincronização no tempo total.
- Artefatos e testes reprodutíveis; testes nativos pertinentes e gates Android quando tocado.
- Nenhuma conclusão formal de “melhor backend” antes da F10.

## Entrega

RELATORIO-FASE-06/report.json/coverage.csv, patches, build/driver/feature manifests, tensores/negativos, profiling/partição, cache e métricas/qualidade. Quadro CPU/OpenCL/Vulkan com comparabilidade e lacunas explícitas.

Retornar ao especialista para F07 ou retrabalho. Não publicar pacote nem habilitar modo definitivo.
