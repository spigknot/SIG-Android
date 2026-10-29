# Ordem F07 — NPU Qualcomm HTP por estágio

Revisão 20261020.1, condicionada ao parecer F02, modelo/artefatos identificados e reserva do dispositivo. Meta: encoder HTP útil para Smart e investigação honesta da rota neural integral NPU. CPU host de WAV/CTC/montagem não invalida o rótulo neural integral; fallback de encoder/projector/editor invalida.

## Leituras e proteção existente

Ler AGENTS, contrato comum, F00/F01/F02, política GraniteNarBuckets, QairtDependencyManager, NativeDependencyManager e resultados históricos auditados. A proteção atual libera t200/t400 e documenta crash de DSP em t2000. Não removê-la globalmente. Não interpretar FastRPC/root/Android16 por hipótese já refutada sem conferir a combinação real.

Primeira linha é a combinação ORT/QNN congelada. Upstream ORT, plugin Qualcomm, QAIRT e arquivos do telefone têm compatibilidade a provar. Não atualizar todos simultaneamente.

## Tarefas obrigatórias

1. **F07.1 — Bring-up no UID normal.** Conferir serial/SoC/HTP arch/ABI/Android/driver, acesso FastRPC e runtime. Preparar LD/ADSP paths e carregar as bibliotecas apropriadas antes de inicializar o backend, pelo mecanismo correto do app. Registrar caminhos e SHA das .so/.skel efetivamente carregadas. Não alterar estado global do telefone nem exigir root.
2. **F07.2 — Sonda antes de pesos.** Criar sessão HTP e micrografo pequeno com MatMul/ops necessários, comparando CPU independente. Capturar RC e diagnóstico do erro real. “Provider registrado” não é êxito de execução. Strict falha diante de fallback.
3. **F07.3 — Precisão suportada.** Consultar operadores/tipos da versão concreta e testar. FP16, W8A8/W8A16 ou outro caminho dependem de SoC/SDK/ops; não prometer suporte pelo nome HTP. Congelar escalas/zero points/layout e calibrar com entradas reais encadeadas quando usar QDQ.
4. **F07.4 — Encoder pequeno.** Validar t200, depois t400, com pesos reais, atenção/convoluções/CTC e multilayer_features comparados ao controle. Obter partições/cobertura e buffers. Separar custo de compilação/contexto, transferência, inferência e CTC. Medir áudio real com pelo menos mais de uma entrada.
5. **F07.5 — Projector e editor.** Fazer micrografo/bloco real de cada um e depois estágio completo quando houver suporte e orçamento. Para editor, preservar atenção não causal, GQA, RoPE/escalas e I/O; não importar kernel de decoder causal. Quantização requer tolerâncias e qualidade de categoria própria.
6. **F07.6 — Atribuição integral.** Construir tabela enc/proj/editor com backend efetivo e ops/partições/cópias. Se só encoder estiver no HTP, entregar “encoder HTP validado, NPU integral não alcançada” e o recurso para F08. Não somar sucesso parcial para marcar todos os estágios.
7. **F07.7 — Rampa de buckets isolada.** Depois dos menores passarem, investigar 800/1200/1600/2000 e eventualmente 3000 somente com hipótese e orçamento aprovados pelo especialista. Cada salto é probe isolado com timeout/captura e checks de saúde DSP; autorização de um bucket não autoriza o próximo automaticamente. T3000 exige contratos F04 e não é requisito para encoder HTP útil em chunks menores.
8. **F07.8 — Crash e falhas.** Se ocorrer SSR/hang/Bad VA/OOM, preservar log/ambiente, parar a bateria e entregar reproducer mínimo. Não afrouxar strict ou usar CPU silencioso para prosseguir. Recuperação do recurso deve respeitar seu dono; não fazer loop de reboot.
9. **F07.9 — Context cache e reuso.** Validar chave por SoC/runtime/driver/grafo/dtype/bucket/I/O, cold compile e warm com mesma sessão, mudança de perfil A–B–A e contexto incompatível. Shared memory/I/O binding só após prova de ownership/sincronização; não supor zero-copy por uma opção ligada.
10. **F07.10 — Piloto real e próxima alternativa.** Áudios PT-BR curtos/multi-chunk, qualidade piloto, carga/compile/transferência/total/memória/thermal e cancelamento/release. Se ORT bloquear por contrato de frontend/partição específico, propor um spike QNN direto ou ExecuTorch com o mesmo micrografo, como ordem separada; não portar o app inteiro nesta fase.

## Aceitação

- HTP executa pelo menos o encoder real para habilitar investigação Smart; NPU integral requer prova adicional de todos os estágios neurais.
- Bibliotecas/partições e tipos observados, negativos e modo estrito efetivos.
- Proteções de buckets conservadas até prova específica; contexto inválido não reaproveitado.
- Qualidade piloto e custos completos, sem confundir compile com inferência nem cold com warm.
- Testes focais, nativos pertinentes e gates Android quando tocado. Mudança de pacote nativo exige build/verify conforme AGENTS antes de promoção.

## Entrega

RELATORIO-FASE-07/report.json/coverage.csv, runtime/loaded-libs manifests, sonda e micrografos, QDQ/calibration manifest, referências/tolerâncias/negativos, partições, saúde DSP, métricas e limites por bucket.

Propor: abrir F08 com encoder aprovado; retrabalhar bloco específico; ou registrar indisponibilidade nesta combinação. A falta de NPU integral não invalida automaticamente Smart; a falta de encoder HTP real impede chamá-lo de Smart NPU.
