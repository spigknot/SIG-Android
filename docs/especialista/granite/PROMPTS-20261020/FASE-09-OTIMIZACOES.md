# Ordem F09 — Otimizações e ablações controladas

Revisão 20261020.1, condicionada às rotas funcionais e contratos revisados. Meta: investigar as ferramentas disponíveis e obter ganhos medidos sem sacrificar a semântica/qualidade. Não executar produto cartesiano de todos os knobs. Uma ferramenta não disponível recebe diagnóstico verificável, não resultado inventado.

## Entradas e método

Ler contrato comum, perfis/gargalos de F02–F08 e documentação oficial das revisões em uso. Congelar um baseline por rota. Criar optimization-registry.csv com ID, hipótese, suporte/requisito, estágio, controle, fator alterado, teste, custo, resultado e motivo de descarte/adiamento.

Para cada família abaixo: verificar disponibilidade real; executar o menor teste discriminante se disponível; fazer piloto completo somente se o teste e o perfil justificarem. Entregar registro de todas as famílias, inclusive as não aplicáveis. O especialista delimita lotes e orçamento.

## Tarefas obrigatórias

1. **F09.1 — Atenção.** Comparar implementação de controle/eager ou equivalente contra SDPA/atenção fundida/Flash Attention disponível no runtime. Verificar noncausal, GQA, padding, escala e precisão; inspecionar kernel realmente selecionado. O pacote CUDA/ROCm FlashAttention não roda diretamente no Adreno: não apresentar benchmark desktop como resultado Android.
2. **F09.2 — Materialização.** Medir buffers S² e logits completos. Investigar ArgMax no grafo, seleção apenas das posições textuais no lm_head quando semanticamente equivalente, reutilização de buffers e poda de outputs não consumidos. Provar que o tensor grande deixou de existir internamente, ou reportar somente redução de cópia/output. Comparar IDs/exceções reais no consumidor.
3. **F09.3 — Precisão.** Comparar FP32/FP16/BF16 somente onde suportados e quantizações W8/INT8/INT4 ou variantes reais disponíveis. Precisão por estágio, operadores excluídos, calibração encadeada, escalas e tamanho efetivo. Variação em peso/ativação exige categoria de qualidade explícita; não chamar tudo de fp16 por nome do arquivo.
4. **F09.4 — Grafo e kernels.** Fusion de MatMul/bias/ativação/norm/RoPE, constant folding, prepacking, tiling/subgroups/cooperative matrix, layout e seleção de shader. Respeitar matemática do modelo. Op não suportado precisa fallback observado ou kernel validado, não substituição aproximada silenciosa.
5. **F09.5 — CPU/threads.** Controlar intra/inter-op, oversubscription entre frontend/ORT/JNI, parallelism de carga e inferência, arena/alocador quando disponível. Começar com poucos níveis relevantes ao SoC; mais threads não implicam maior velocidade. Não alterar governor/thermal para obter ganho.
6. **F09.6 — I/O e memória.** mmap/streaming de pesos se suportado, I/O binding, buffers diretos, shared memory/zero-copy real, dequant/cópia e persistência. Registrar ownership/sync e bytes. A opção “shared allocator” ligada não prova ausência de cópia.
7. **F09.7 — Cache e sessão.** Cache de contexto HTP e pipelines/kernels GPU, load lazily e reuso por bucket. Medir primeiro uso, processo novo com cache, sessão quente e invalidação seletiva. Não confundir aquecimento/cache com ganho de kernel. A→B→A preserva pesos/downloads e contextos compatíveis.
8. **F09.8 — Buckets e chunks.** Comparar tamanhos de janela/cortes e política de máscara dentro dos limites aprovados, incluindo 60 s apenas onde validado. Fazer braço de cortes iguais e braço de melhor configuração de produto. Não atribuir ao backend uma redução causada por menos frames ou editor omitido.
9. **F09.9 — Smart e sobreposição de estágios.** Só após ponte serial correta, testar fila limitada e overlap de frontend/encoder do próximo chunk com editor do atual, se o runtime/SoC permitirem. Medir contenção CPU/GPU/DSP, thermal/memória, fences/cancelamento e ordem. In-flight limitado; não duplicar toda a sequência de tensores. Ganho que desaparece no soak não é recomendação.
10. **F09.10 — Seleção de combinações.** Primeiro uma variável por vez e micro/piloto; depois no máximo poucas interações justificadas pelos ganhadores. Manter controles não otimizado e anterior, incluir negativos/regressão numérica e qualidade piloto em cada mudança relevante. Congelar candidatos para F10 antes de abrir o conjunto reservado.

## Regras de decisão

- Cada hipótese tem métricas esperadas, tolerância e limite de recurso antes de executar.
- Ordem balanceada, eventos completos, repetição válida; tempo de kernel e tempo total separados.
- Um método pode reduzir memória e aumentar tempo: reportar Pareto e categoria, sem resumir em “mais rápido”.
- Não repetir EXTENDED/ALL_OPT que falharam historicamente sem nova hipótese concreta e controle.
- Otimização que muda texto requer investigação/categoria e revisão, mesmo se a média WER piloto parecer boa.
- Se uma técnica requer reescrever backend inteiro, entregar reproducer/proposta e pedir nova ordem ao especialista; não expandir indefinidamente a fase.

## Aceitação e entrega

Entregar RELATORIO-FASE-09/report.json/coverage.csv, registry completo, patches por técnica, todos os runs/exclusões, kernels/partições observados, numérica/mutantes, qualidade piloto e métricas de recurso/tempo total.

Tabelar CPU/OpenCL/Vulkan/NPU/SmartOC/SmartVK com configuração final proposta, ganho relativo ao próprio controle, incerteza e limite. Verificar testes/gates aplicáveis a cada patch. O especialista aprova a lista congelada da F10; não autoclassificar uma combinação como vencedora formal.
