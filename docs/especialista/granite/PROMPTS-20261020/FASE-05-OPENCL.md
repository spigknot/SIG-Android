# Ordem F05 — Granite NAR em OpenCL

Revisão 20261020.1, condicionada à referência/contratos F02 e ao parecer de shapes/janelas aplicável. Provar a API OpenCL em estágios reais do NAR, antes de integrar UI. QNN GPU antigo é controle histórico distinto, não substitui esta rota.

## Leituras e seleção inicial

Ler contrato comum, decisões D09/D10/D11, relatório F02 e mapa de runtime F00. Começar por MNN com revisão fixada, se a disponibilidade confirmada o permitir. Alternativa de editor ggml exige conversão NAR própria; suporte ao texto Granite AR no llama.cpp não é prova do editor NAR.

Ler fonte/documentação oficial da revisão escolhida e registrar flags. Não converter todos os pesos nem instalar APK no início. Orçamento de piloto: até um dia de trabalho por candidato até relatório; abrir somente um candidato reserva após bloqueio localizado.

## Tarefas obrigatórias

1. **F05.1 — Capacidades reais.** No alvo reservado, identificar driver/OpenCL versão, extensões, FP16, limites de buffers/workgroup/alocação, ABI e UID. Verificar carregamento da biblioteca pelo mesmo contexto que o app usará. Biblioteca presente e clGetDeviceIDs bem-sucedido ainda não provam execução do modelo.
2. **F05.2 — Micrografo.** Executar MatMul/normalização/softmax/reshape relevantes com shapes pequenos e entradas determinísticas não triviais, comparando a referência independente. Registrar dispatch OpenCL e ausência de fallback neural. Contemplar dimensões não múltiplas do tile, valores pequenos/grandes e não finitos rejeitados conforme contrato.
3. **F05.3 — Bloco de atenção real.** Extrair um bloco do editor com pesos reais, GQA, RoPE e máscara bidirecional/padding. Testar S curto e um S representativo, positions e escalas do modelo, incluindo attention_multiplier. Não aceitar default de escala do kernel sem comparação. Comparar saída e intermediários necessários contra PyTorch/ORT CPU.
4. **F05.4 — Negativos semânticos.** Forçar máscara causal, escala padrão incompatível, ordenação errada de cabeças/strides, posição deslocada e padding válido indevido. O checker deve rejeitar cada um pelo erro esperado. Se todos passam, o teste não discrimina a semântica NAR.
5. **F05.5 — Estágios em ordem de utilidade.** Validar primeiro editor completo, então encoder/projector ou outra ordem justificada pelo perfil medido. Converter incrementalmente e produzir manifesto de pesos/grafos/external_data; registrar operadores convertidos, fundidos, omitidos e fallback. Não substituir editor por CTC apenas para mostrar texto.
6. **F05.6 — Precisão e quantização.** Começar com a precisão de controle viável; FP16/BF16/FP32 são distintos. Weight-only/INT8/INT4 ficam como candidatos separados; calibrar ativações com tensores encadeados reais, fora do conjunto reservado. Não aplicar quantização e mudar runtime/atenção ao mesmo tempo sem controles.
7. **F05.7 — Pipeline e hardware observado.** Integrar em harness de desenvolvimento por estágio, com requested/observed plan. Provar enc/proj/editor em OpenCL para chamar rota OpenCL integral. Se apenas editor rodar em GPU, nomear CPU→OpenCL e preservar sua utilidade para F08, sem declarar a meta integral atingida.
8. **F05.8 — Shapes e sessões.** Validar buckets curtos e um representativo dentro do limite aprovado, criação/reuso de sessão, cache de kernels/contexto e mudança de bucket sem estado stale. 60 s só entra se seus grafos forem aprovados nesta combinação; manter segmentação menor como controle.
9. **F05.9 — Piloto de produto.** Rodar áudio PT-BR curto e multi-chunk público no UID normal/probe representativo, com golden e qualidade piloto. Medir carga, compile, cópias, kernels, estágios e total, memória e thermal. Registrar cancelamento/release, repetição A–B–A de áudios e erro de GPU.
10. **F05.10 — Decidir o próximo investimento.** Entregar gargalo dominante, cobertura de hardware, custo de conversão/manutenção e hipótese de ganho. Se um op bloquear, fornecer grafo mínimo e mensagem/RC. Não iniciar um segundo runtime inteiro sem esse diagnóstico; propor o menor patch/kernel alternativo.

## Aceitação

- Bloco real bidirecional/GQA correto e negativos rejeitados.
- Evidência de OpenCL efetivo por estágio; fallback explícito. Nenhuma aprovação por rótulo.
- Saída PT-BR e qualidade piloto compatíveis com a categoria, sem vazamento entre áudios.
- Tempos incluem cópias/sessões e toda a rota. Piloto não substitui F10.
- Build/testes do runtime e JNI; se Android tocado, os três gates e mapa/KDoc. Pacote nativo novo permanece experimental até F12.

## Entrega

RELATORIO-FASE-05/report.json/coverage.csv, revisões/build flags, patches separados, micrografo e bloco mínimos, referências/tolerâncias/mutantes, manifests, partição/profiling e métricas/qualidade. Classificar cada estágio como funcional, numericamente validado, sem prova ou bloqueado.

Devolver ao especialista para escolher a F06 ou retrabalho pontual. Não habilitar OpenCL no produto final nesta fase.
