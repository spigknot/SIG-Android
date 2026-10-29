# Ordem F08 — Smart NPU → OpenCL e NPU → Vulkan

Revisão 20261020.1, condicionada a encoder HTP real validado em F07 e editor válido em F05 e/ou F06. Investigar as duas variantes; a falta de um editor GPU deve ser registrada como dependência, sem simulá-lo. Não há migração KV ou token-ponte no NAR.

## Entradas e arquitetura

Ler contrato comum, contratos CPU, relatórios dos backends e lessons do dossiê. Pipeline de interesse: frontend CPU → encoder HTP → CTC/slots e projector com localização explícita → editor OpenCL/Vulkan → CTC/texto. CPU para controle/CTC não é fallback neural, desde que previsto.

Usar objetos de configuração/sessão por estágio, imutáveis durante uma operação. O backend global atual não pode acidentalmente mandar o editor para HTP ou o encoder para CPU porque outro estágio mudou.

## Tarefas obrigatórias

1. **F08.1 — Definir a fronteira.** Comparar pelo menos: encoder HTP/projector CPU/editor GPU; encoder+projector HTP/editor GPU; encoder HTP/projector+editor GPU, quando viáveis. Se alguma não existir, declarar ausência e motivo. Definir o que cruza: multilayer_features, audio_embeds, IDs/logits CTC e metadados; medir seus tamanhos reais.
2. **F08.2 — Contrato de ponte.** Versionar graph/model/config/run/chunk IDs, generation, shape/dtype/layout/strides, endianness, escalas/zero points, comprimentos válidos, posições e blank/vocab. Incluir ownership, lifetime, fence/event e regra de liberação. Entrada incompatível falha antes do editor.
3. **F08.3 — Captura na mesma tentativa.** Para um áudio público, salvar saída real HTP antes da ponte e entrada real GPU após import/conversão, com hashes/tamanhos e comparação contra transformação independente. Cópia sem conversão exige bytes iguais; dequant/layout exige referência e tolerância. Não comparar arquivos capturados em runs diferentes.
4. **F08.4 — Continuidade de semântica.** CTC válido e interleave devem corresponder àquele áudio e comprimento. Verificar /embedding_multiplier e outras escalas exatamente uma vez, posição do início do texto e máscara bidirecional. Não recalcular encoder em CPU escondido para produzir a entrada “correta” da GPU.
5. **F08.5 — A–B–A e anti-replay.** Usar áudios A/B distintos, comparando cada resultado/tensor ao seu controle. A segunda execução de A deve reutilizar apenas pesos/sessões compatíveis e produzir A dentro da tolerância, nunca B ou estado antigo. Trocar run/chunk/generation, truncar bytes, mudar escala/layout/posição e injetar saída antiga: o checker real deve rejeitar.
6. **F08.6 — Coexistência dos runtimes.** Validar símbolos/bibliotecas/allocators/threads, memória de pesos/contextos simultâneos, JNI, handles e release. Medir o custo de possuir HTP e GPU no mesmo processo. Não resolver colisão trocando bibliotecas de produção de outra ferramenta nem carregar cópias sem identidade.
7. **F08.7 — Execução serial correta.** Primeiro um chunk com sincronização explícita; depois múltiplos chunks, cancelamento e erro em cada lado. Confirmar que nada lê buffer já fechado, que um erro não reutiliza uma ponte parcial e que resultado parcial tem status correto. Pipelining é candidato posterior, não pré-requisito.
8. **F08.8 — Medir as três colocações.** Tempo de encoder, CTC, projector, export/dequant/cópia/import/sync, editor e total. Comparar memória nativa/GPU/DSP e bytes transferidos. Exportar features de dezenas de MiB ou logits de centenas de MiB pode apagar o ganho: não omitir esses custos.
9. **F08.9 — Comparar com rotas puras.** Para os mesmos pesos/cortes/categoria, piloto CPU, GPU correspondente e NPU integral se existir. Comparar operação completa fria/quente, qualidade PT-BR e comportamento sustentado. Medir também uma configuração de produto viável quando cada backend exigir janelas diferentes, com fatores declarados.
10. **F08.10 — Decidir as variantes.** SmartOC e SmartVK recebem veredito separado: funcional com qualidade piloto, benefício candidato, funcional sem ganho, bloqueado ou não comprovado. Escolher colocação do projector por medição e custo de manutenção, não por intuição. Registrar limites de bucket/janela e a melhor próxima otimização.

## Aceitação

- Encoder HTP e editor na API GPU indicada são comprovados na mesma tentativa.
- Ponte sem replay, corrupção, conversão implícita ou dupla aplicação de escala; negativos efetivos.
- Resultado/qualidade correspondem ao áudio; sequência de chunks/cancelamento não contamina sessão.
- Custos completos e coexistência de memória medidos. Funcionar não basta para declarar melhor que NPU/GPU.
- Unitários/contratos/JNI/nativos pertinentes e gates Android; artefatos ainda experimentais.

## Entrega

RELATORIO-FASE-08/report.json/coverage.csv, especificação da ponte e implementação/consumidor, manifests das capturas, referências/mutantes, profiling de ambos os backends, métricas das colocações e qualidade, reproducer mínimo e patch.

Não importar “8 passos autoregressivos” do Hy-MT2 como teste NAR. Aqui o editor é uma passagem; se a necessidade for AR, pedir ao especialista a ordem opcional correspondente. Encerrar para revisão da F09.
