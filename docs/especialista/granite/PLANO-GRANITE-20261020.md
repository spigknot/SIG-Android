# Plano Granite Android v2.0

20/10/2026, America/Sao_Paulo. Escopo: especialização, planejamento e ordens de execução para Granite STT no SIG. Esta revisão substitui a estratégia prospectiva do plano de 09/10; não reescreve seus resultados nem aprova candidatos antigos por critérios novos.

## 1. Resultado a alcançar

Entregar transcrição local em português no Android com seis rotas verificadas: CPU, OpenCL, Vulkan, NPU, Smart NPU → OpenCL e Smart NPU → Vulkan. Descobrir quais combinações oferecem melhor qualidade, latência, memória e estabilidade em cada aparelho. Uma rota só conta como disponível quando executa os estágios declarados no hardware indicado, com qualidade aceita e integração normal no aplicativo.

O usuário deve poder selecionar um arquivo com duração superior à janela do modelo e receber sua transcrição completa. A ferramenta fará a segmentação, o processamento e a junção. Investigar uma janela de até 60 segundos; aprová-la somente se o modelo, seus grafos e o aparelho a sustentarem. O tamanho total do arquivo não fica limitado a 60 segundos. Limites operacionais de espaço e recursos devem ser explícitos e nunca causar corte silencioso do áudio.

A execução acontecerá por fases. O executor recebe uma ordem, conclui suas tarefas possíveis, entrega resultados e limitações com evidências e aguarda o parecer do especialista. Os prompts seguintes são versões iniciais detalhadas; serão corrigidos conforme os relatórios. Não é uma ordem para rodar todas as fases em sequência sem revisão.

## 2. Ponto de partida e lacunas

Base inspecionada: main em d439008e549a5193c781f0a1ffd087be1378b730. Há alterações locais anteriores em dois fontes, dois testes e no documento de design implementando janelas de 40 s com sobreposição de 2 s. Elas são preservadas nesta entrega; não se presume que estejam commitadas ou aceitas em aparelho. Ver [snapshot](SNAPSHOT-20261020.json) e [diagnóstico](DIAGNOSTICO-E-FONTES-20261020.md).

O produto seleciona CPU para NAR e desabilita suas opções aceleradas na UI. Existem infraestrutura QNN, experimentos históricos, política de buckets e ferramentas de exportação, mas isso não constitui seis rotas prontas. A política atual restringe aceleradores a t200/t400 e documenta falha do DSP em t2000. Não remover essa proteção para “liberar NPU”.

O estado anterior registra inspeções G00 concluídas e G01 pronta, sem despacho. A fase 00 verificará se existe entrega posterior antes de refazer trabalho. O parser atual do NAR usa regex; o benefício histórico de outro parser só pode ser incorporado com paridade de vocabulário. A documentação do exportador e a assinatura usada pelo app divergem sobre attention_mask: inspecionar o artefato real.

O dossiê Hy-MT2 demonstra investigação útil de ponte, runtime e produto. Seu próprio desfecho ainda registra KERNEL_VALIDATED=NÃO e SMART_APROVADO=NÃO. Transferir os métodos de prova e as lições de integração; não copiar kernels, números, autorização de dispositivo ou a arquitetura do decoder.

## 3. Arquitetura que orienta os experimentos

O alvo principal é Granite Speech 4.1 2B NAR. Seu pipeline é frontend CPU → encoder → CTC inicial → projector → montagem de embeddings/slots → editor bidirecional em uma passagem → CTC final/texto. O cartão oficial descreve edição não autoregressiva da hipótese CTC. [Modelo IBM](https://huggingface.co/ibm-granite/granite-speech-4.1-2b-nar).

Neste plano, “decode GPU” no Smart NAR significa executar o editor em GPU. Não existe o loop de geração token a token e de migração de KV do Hy-MT2. A ponte transfere tensores e metadados entre estágios. Para um decoder autoregressivo literal seria necessário outro modelo e a [fase AR opcional](PROMPTS-20261020/FASE-AR-OPCIONAL.md), com contrato e avaliação próprios.

| Rota solicitada | Plano dos estágios neurais a provar | Observação obrigatória |
|---|---|---|
| CPU | Encoder, projector e editor em CPU | Controle sempre disponível |
| OpenCL | Estágios neurais em OpenCL quando suportados | Estágio CPU restante torna a rota mista; informar qual |
| Vulkan | Estágios neurais em Vulkan quando suportados | Compilar shaders não prova despacho |
| NPU | Encoder, projector e editor em HTP | “NPU integral” exige todos os estágios neurais; host pré/pós-processamento é esperado |
| Smart NPU → OpenCL | Encoder HTP; editor OpenCL | Medir projector em HTP/CPU/GPU e declarar escolha |
| Smart NPU → Vulkan | Encoder HTP; editor Vulkan | Mesma ponte sem presumir desempenho igual ao OpenCL |

Cada tentativa registra requested_route, observed_stage_plan e fallback_reason. Aceleração parcial pode ser útil e ser mantida experimentalmente com nome correto; não satisfaz silenciosamente a rota integral. Usar um contrato de execução por estágio, com precisão e bucket, evitando multiplicar enums ou transportar regras para Activities.

Granite TurboCTC é outra família já presente. Preservar seu funcionamento, idioma e downloads. Não substituí-lo ou usá-lo como resultado NAR só porque o nome é Granite.

## 4. Estratégia de runtimes

Preservar ORT CPU como controle e ORT/QNN como primeira linha de HTP. Congelar versões efetivamente carregadas, não apenas a dependência Gradle. Upstream ORT, plugin QNN, QAIRT e bibliotecas do fabricante não são intercambiáveis por nome.

Para GPU, iniciar um piloto MNN por compartilhar conversão e suportar OpenCL/Vulkan; suporte real ao bloco NAR continua hipótese. Se falhar por operador ou semântica, localizar o menor bloqueio e avaliar uma alternativa delimitada, como editor em ggml, antes de outro runtime completo. ncnn ou ExecuTorch são reservas justificadas por bloqueio concreto, não quatro integrações simultâneas. A existência de backend de texto Granite em llama.cpp não implementa automaticamente o NAR. [MNN oficial](https://github.com/alibaba/MNN), [OpenCL do llama.cpp](https://github.com/ggml-org/llama.cpp/blob/master/docs/backend/OPENCL.md), [ExecuTorch Qualcomm](https://docs.pytorch.org/executorch/stable/backends-qualcomm.html).

Falha histórica em QNN GPU não encerra OpenCL ou Vulkan independentes. Em HTP, verificar tipos, operadores e partições na combinação escolhida antes de prometer FP16 ou INT8. Modelos quantizados exigem calibração encadeada com tensores reais do estágio anterior. [QNN EP](https://onnxruntime.ai/docs/execution-providers/QNN-ExecutionProvider.html).

FlashAttention entra como hipótese de otimização. O pacote Dao-AILab tem requisitos CUDA/ROCm; não é instalação direta para a GPU Adreno. Investigar implementações de atenção fundida, SDPA e kernels equivalentes disponíveis no backend Android, com máscara bidirecional, GQA e escalas corretas. Resultados CUDA de desktop servem para referência numérica ou exploração, não como desempenho Android. [FlashAttention oficial](https://github.com/Dao-AILab/flash-attention).

## 5. Janela de 60 segundos e áudio longo

Separar quatro conceitos: duração do chunk de arquivo, limite T do grafo de áudio, limite S do editor e contexto interno de atenção do modelo. Eles não são um único chunk_size. A configuração oficial tem encoder context_size=200 e limite posicional textual 4096; isso não transforma a janela do app em 200 ou 4096 segundos. [Configuração IBM](https://huggingface.co/ibm-granite/granite-speech-4.1-2b-nar/raw/main/config.json).

No frontend atual, 16 kHz e empilhamento de 2 hops de 160 amostras geram T=floor(N/320). Portanto, 40 s correspondem a T=2000 e 60 s a T=3000. O exportador existente termina em T=2000 e seus buckets de editor em S=1408. A fase 04 precisa rever ambos, máscaras, posições, pooling, shapes e memória. Não basta alterar MAX_CHUNK_SECONDS ou criar um arquivo com nome t3000.

Uma estimativa conservadora pelo interleave atual, para T=3000, admite 750 saídas CTC, até 1501 slots e 600 embeddings de áudio, total S=2101. Essa é uma conta de dimensionamento a confirmar com os contratos, não uma medição de consumo nem uma previsão de quantidade normal de palavras. Não truncar S para fazê-lo caber. Ver contas e ressalvas no diagnóstico.

A fase 03 primeiro audita a solução local de 40 s e torna o áudio longo confiável. A fase 04 decide se 60 s tem utilidade. Menor janela pode ganhar em velocidade, memória e qualidade de borda; a comparação medirá isso. Cada perfil terá uma janela máxima validada e uma política de segmentação. Um acelerador limitado a buckets menores pode processar um arquivo longo em mais chunks, com comparação que considere esse custo.

Cobertura usa intervalos de amostras [início,fim), com propriedade definida de cada trecho. Cortes em silêncio são candidatos, não garantia. Na sobreposição, deduplicar palavras sem alinhamento pode apagar repetições legítimas. Proibir deduplicação global; validar junção de fronteira em português, inclusive palavras repetidas. Quando não houver alinhamento confiável, usar estratégia conservadora demonstrada, inclusive cortes sem sobreposição se necessário, e explicitar o compromisso. Nenhum algoritmo textual prova cobertura acústica por si só.

Cancelamento, falha no chunk, progresso, retomada e resultado parcial devem ter contrato. Não carregar todo o áudio ou manter todos os tensores de todos os chunks na memória. Sessões compatíveis podem ser reutilizadas; embeddings dependentes de áudio não.

## 6. Fases, dependências e entregas

| Fase | Pré-requisitos para a ordem | Entrega e critério de passagem |
|---|---|---|
| 00 Rebaseline | Nenhum | Fontes, pesos, ferramentas e dispositivo identificados; incertezas separadas |
| 01 Evidências | 00 revisada | Corrigir G01 sem novos benchmarks desnecessários; protocolo passa negativos |
| 02 CPU/contratos | 01 revisada | Referência numérica, vocabulário, máscaras, sessões e recursos confiáveis |
| 03 Áudio longo | 02; patch local auditado | Arquivos maiores processados por completo e testes reais de fronteira |
| 04 60 s | 02/03 | Aprovar por perfil ou rejeitar fundamentadamente, preservando áudio longo |
| 05 OpenCL | 02; shapes aprovados aplicáveis | Micrografo, bloco real, estágio e pipeline; execução GPU comprovada |
| 06 Vulkan | 02; conversão 05 se aproveitável | Prova independente da API e dos mesmos contratos |
| 07 NPU | 02; reserva do dispositivo | Bring-up HTP, partição por estágio e rampa de buckets segura |
| 08 Smart | 07 encoder + editor 05 e/ou 06 válidos | Ponte íntegra, custo total e qualidade, duas variantes avaliadas |
| 09 Otimizações | Rotas funcionais relevantes | Ablações com hipótese, controle, ganho total e qualidade |
| 10 Comparação | 03 + resultados 04–09 | Bateria congelada, corpus reservado, Pareto e recomendações |
| 11 Produto | 10 e perfis selecionados | UX, cache/download, fallback e ciclo de vida no UID normal |
| 12 Entrega | 11 revisada | Regressão, artefatos íntegros, rollback e prontidão de promoção |
| AR Opcional | Parecer que abra mudança de arquitetura | Modelo AR separado, sem alterar o contrato NAR |

As fases 05/06/07 são logicamente independentes depois dos contratos; o despacho inicial é sequencial para permitir aprendizagem e evitar disputa de RAM/DSP/edições. Reordenação por disponibilidade do aparelho é permitida pelo especialista com registro. Áudio longo não precisa aguardar NPU.

Cada fase pode ser dividida pelo especialista em unidades menores, por exemplo 02A parser, 02B contratos e 02C ArgMax. Uma unidade aprovada não torna a fase inteira aprovada. Não acumular meses de alterações sem revisão intermediária.

## 7. Critérios de correção, qualidade e utilidade

Aplicar o [contrato comum](CONTRATO-EXECUTOR-20261020.md). Seus números são metas prospectivas da campanha, a confirmar antes da coleta formal; não são promessas do fabricante.

O controle numérico é independente do candidato. Comparar frontend, encoder, projector, montagem de embeddings, editor e tokens. Verificar padding real, máscara não causal, RoPE, GQA, escalas, blank, strides e dtype. Testes devem rejeitar erros relevantes, não somente aprovar a saída feliz. IDs ArgMax usam o contrato real, inclusive INT64 e desempate, antes de conversão para Int.

Qualidade vem de referência humana, com normalização publicada e corpus de calibração, exploração e confirmação separados. A referência CPU mede regressão entre implementações, mas não é verdade humana. Medir WER e CER por amostra e agregados; estudar inserções/deleções na fronteira, nomes, números, negação e repetições. Não declarar aptidão para uso probatório a partir destes testes.

Proposta de gates: perfil equivalente aceita até +0,5 ponto percentual em WER e CER contra a CPU validada; perfil intermediário quantizado até +1 pp em ambos; perfil compacto até +3 pp WER/+2 pp CER com rótulo e decisão explícita. Nenhum aceita erro crítico novo atribuível ao candidato no conjunto dirigido. Congelar definições e cortes antes da bateria, com intervalos de confiança por amostra/falante. Se o corpus não sustentar o gate, registrar resultado inconclusivo.

Para justificar trocar a configuração recomendada, procurar pelo menos 15% de ganho mediano no tempo total ou 20% de redução de memória com latência aceitável para a categoria. Usar resultados pareados e incerteza; ganho só de kernel não basta. Um modo funcional sem ganho pode permanecer experimental com explicação, especialmente se o usuário quer comparar todas as rotas.

## 8. Protocolo de medição

Registrar separadamente instalação/download, criação de sessão, compilação/contexto, frontend, cada estágio, CTC/montagem, cópias/ponte, inferência total, junção, gravação e wall time da operação. RTF=tempo de processamento/duração do áudio; RTFx é a inversa. Informar exatamente o intervalo medido. Não comparar tok/s NAR com tok/s de decoder AR.

CPU e aceleradores recebem o mesmo áudio, corte, pesos ou transformação identificada, política de threads e qualidade alvo. São necessários dois comparativos: mesmo corte para isolar backend e melhor configuração de produto por rota para medir utilidade. Não atribuir ao hardware ganho provocado por usar chunks menores, modelo mais compacto ou omitir o editor.

Medir cold de processo/cache de contexto definido, warm na mesma sessão observada e uso sustentado. Mínimo exploratório de 5 pares válidos por candidato final; a bateria formal define repetição e potência antes. Alternar ordem com semente e blocos ABBA/randomizados. p95 só é usado como estimativa com amostragem suficiente, no mínimo 20 medidas; com n=5 mostrar valores, mediana e dispersão, sem falsa precisão.

Memória: before_load, after_load, before_infer, after_infer e after_release. Distinguir RSS/PSS/heap Java/nativo/GPU/DSP e amostragem de pico. O evento end após release não é after_infer. Temperatura, bateria, alimentação e throttling acompanham a tentativa. Energia só em joules quando houver instrumento/contador adequado e método válido; porcentagem de bateria é proxy identificado.

## 9. Reutilização, orçamento e bloqueios

Reutilizar artefatos compatíveis e seus caches íntegros. Identificar contexto por modelo/grafo, versão real do runtime, ABI, SoC/HTP, driver, precisão, bucket, máscara e contrato I/O. Não invalidar todo cache ao trocar qualidade ou backend. Caches não podem misturar saídas de áudio nem esconder recompilação no warm.

Primeiro piloto por runtime: ambiente/operador → bloco real → estágio. Orçamento inicial de um dia de trabalho por candidato até parecer, até dois candidatos ativos por rodada, até 20 GiB de novos artefatos em C e 1 GiB de logs em D por rodada. Preservar margem mínima de 50 GiB em C e 10 GiB em D; estimar o tamanho antes de exportar vários buckets. O especialista pode ajustar limites por necessidade concreta. Não apagar históricos para caber.

Um processo de modelo grande por vez neste host até medição de RAM permitir outro. Dono único por serial/DSP e por arquivo em edição. Falha repetida sem mudança de hipótese encerra o piloto com diagnóstico. Após SSR/crash do DSP, parar a bateria e registrar estado; não iniciar loop de reboot ou misturar resultados pré/pós-falha.

Ausência de aparelho ou ferramenta não torna matemática e testes offline impossíveis. Entregar a parte independente e apontar exatamente qual afirmação espera recurso. Não relatar CPU fallback como sucesso NPU para preencher a matriz.

## 10. Integração, downloads e entrega

A UI só habilita capacidades demonstradas no aparelho/runtime/pacote atual e explica as indisponíveis. Se houver fallback, mostrar backend efetivo e motivo; modo estrito de teste falha em vez de trocar silenciosamente. Auto escolhe entre perfis qualificados por aparelho, sem autotune pesado invisível.

Downloads precisam continuar persistentes em todos os componentes afetados: modelos/qualidades, encoder/projector/editor, frontend/vocab/embeddings, runtime QAIRT e bibliotecas GPU/nativas. O trabalho prévio c7d241b é base a preservar. Validar A → B → A sem baixar A novamente, cache compartilhado por hash, retomada, corrupção, espaço insuficiente, atualização e rollback. Exibir arquivo realmente ativo e progresso coerente em bytes/fases. Nem arquivo incompleto nem nome semelhante satisfazem cache.

Isolar gerações e publicar um ponteiro ativo atomicamente após validar o conjunto inteiro. Trocar perfil não remove o anterior. Não alterar Whisper, provedores remotos ou outras ferramentas para conseguir resultado Granite; testar componentes compartilhados tocados.

Novos fontes obedecem KDoc e MODULE-MAP. Alterações Android passam unitários, lint e assemble na base integrada. O build debug não gera nativos. Quando houver mudança de contrato nativo, validar os pacotes com os scripts do AGENTS; release só de APK reutiliza os ZIPs existentes. Assinatura/publicação/promoção dependem da aprovação explícita exigida pelo AGENTS e só serão propostas com artefatos já preparados e revisáveis.

## 11. Revisão de relatórios e conclusão

O executor entrega o [relatório padronizado](MODELO-RELATORIO-FASE-20261020.md), manifesto e reprodução. O especialista confere hashes, código, consumidores, contagens, RC, partições e métricas; distingue prova funcional, numérica, qualidade e performance. Emite [parecer](MODELO-PARECER-20261020.md) com uma decisão por unidade: aceitar, manter experimental, retrabalhar, indisponível nesta combinação ou aguardar recurso.

O programa só termina quando áudio longo e CPU estiverem integrados; as seis rotas tiverem resultado explícito; as rotas oferecidas tiverem qualidade/estabilidade comprovadas no app; a melhor combinação por cenário estiver sustentada por medição; e a entrega tiver reprodução e rollback. Se uma API não suportar o modelo após pilotos delimitados, o relatório deve dizer que a meta dessa rota não foi alcançada e qual mudança permitiria reabrir. Não declarar todas as seis prontas quando só existem botões.

Primeira ordem: [fase 00](PROMPTS-20261020/FASE-00-REBASELINE.md). Não há executor externo vinculado nem experimento em andamento por esta entrega.
