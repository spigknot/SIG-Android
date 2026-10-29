# Ordem F03 — Transcrição automática de áudio longo

Revisão 20261020.1, condicionada ao parecer F02. Meta desta fase: arquivo maior que a janela deve ser processado por completo, com memória limitada e fronteiras corretas. Partir do teto atual de até 40 s e validar o caminho real por arquivo; a avaliação de 60 s vem na F04. Não tornar áudio longo dependente de NPU/GPU.

## Entradas e escopo

Ler AGENTS, contrato comum, parecer F02 e diff local preservado de GraniteBinarySupport.kt, GraniteNarEngine.kt, seus testes e docs/granite-nar-design.md. O patch existente já cria janelas 40 s/overlap 2 s e merge textual até 32 palavras. Auditá-lo e integrar somente o que passar; não reaplicar cegamente nem descartá-lo.

Manter regras de planejamento/junção fora de Activities. Audio decoding/conversão, seleção da janela, execução e resultado precisam ter contratos separados. Se tocar RemoteSttActivity, cumprir integralmente seus gates e critérios.

## Tarefas obrigatórias

1. **F03.1 — Definir o contrato do arquivo.** Obter amostras/duração pelo áudio preparado e validar PCM mono 16 kHz. Não confiar só em metadata da URI. Detectar arquivo vazio, truncado, RIFF com chunks extras, data ímpar, sample rate/canais incorretos e overflow de contagem. Preservar os caminhos de conversão já aceitos e registrar limitações de formatos/tamanhos.
2. **F03.2 — Planejar cortes.** Implementar seam puro com intervalos inteiros [start,end), contexto, propriedade do trecho e limite por perfil. Toda amostra pertence a um trecho; contexto pode sobrepor. Separar tamanho máximo validado, alvo de corte, overlap e tolerância de busca de silêncio. A duração inclui o contexto: não enviar 40 s + overlap extra para um grafo de 40 s.
3. **F03.3 — Avaliar fronteira acústica.** Comparar pelo menos corte fixo e corte em silêncio/VAD disponível, mantendo limite rígido e sem exigir internet. Silêncio não é prova de fim de palavra; testar fala contínua/ruído. Não adicionar uma dependência pesada sem ganho demonstrado e licença.
4. **F03.4 — Resolver junção sem apagar fala.** Revisar a deduplicação atual sobre texto acumulado. Fazer junção local da fronteira e guardar proveniência por chunk. Tokens iguais podem ser repetição legítima. Se optar por overlap, demonstrar alinhamento suficiente; não inventar timestamps a partir de índice do editor. Em fronteira ambígua, aplicar estratégia conservadora validada, por exemplo retranscrever os intervalos proprietários sem overlap, em vez de simplesmente remover texto por igualdade. Registrar quando houve resolução/fallback e seu custo.
5. **F03.5 — Executar em fluxo.** Ler somente janela/contexto necessário com seeks seguros e contagem Long. Manter no máximo os buffers/tensores necessários e sessões compatíveis. Liberar recursos entre chunks; não guardar todos os PCM, features ou logits. Estabelecer limite de temporários e comportamento de espaço insuficiente antes de iniciar.
6. **F03.6 — Progresso e resultado.** Progresso monotônico por áudio único coberto, com etapa/chunk atual e término somente após junção/gravação. Não somar overlap como conteúdo novo. Resultado inclui contagem de chunks, backend efetivo e cobertura. Erro no chunk intermediário não publica transcrição completa; preservar parcial identificado com ação clara.
7. **F03.7 — Cancelamento e retomada.** Interromper entre etapas quando o runtime permitir, impedir próximo chunk e medir prazo de liberação. Gravar checkpoint apenas de chunks concluídos com SHA de áudio/cortes/modelo/configuração. Retomar somente checkpoints compatíveis e íntegros; mudança de modelo/janela não reutiliza texto antigo. Testar fechar/abrir tela, erro, cancelamento e troca de qualidade sem misturar runs.
8. **F03.8 — Casos determinísticos.** Testar 0/1 amostra, menos de um frame, 40 s ±1 amostra/±1 frame, 60 s, 80 s, múltiplos exatos, último trecho curto, contagens grandes sem alocar áudio gigante, overlap inválido e erro de arquivo. Provar cobertura e ausência de loop/índice negativo com variações aleatórias de limites. Fixtures sintéticas provam planejamento, não WER.
9. **F03.9 — Casos reais de borda PT-BR.** Áudios públicos/controlados com frase atravessando corte, “não”, números, nome próprio, “sim, sim”, trechos repetidos de propósito, silêncio e fala sem pausa. Testar fronteira com diferença de caixa/pontuação e transcrições divergentes entre overlaps. Referências humanas distinguem repetição verdadeira de duplicata introduzida pelo algoritmo.
10. **F03.10 — Série longa e regressão.** No aparelho reservado, transcrever arquivos reais de pelo menos 2 min, 10 min e um teste sustentado próximo de 30 min dentro do orçamento. Reportar o maior tamanho/duração efetivamente testado; não afirmar “ilimitado”. Medir tempo por chunk, pico/memória após cada chunk, último trecho, cancelamento intermediário e CPU curto anterior. Se o aparelho faltar, entregar patch/testes e marcar aceitação real pendente.

## Aceitação

- Nenhum truncamento silencioso; cobertura [0,N) verificável; falhas e parcial explícitos.
- Testes de palavras/repetições de fronteira não mostram perda/duplicação atribuível à junção.
- Memória de trabalho de PCM/features/tensores não cresce com a duração total; crescimento de texto/checkpoint separado e justificado.
- Nenhum backend recebe T/S maior que seu contrato. Caso de editor S excedido precisa subdivisão segura, não corte de tokens.
- Progresso correto, cancelamento observado, checkpoint não aceita replay/incompatibilidade.
- Testes focais e testDebugUnitTest/lintDebug/assembleDebug; KDoc/MODULE-MAP se houver novo fonte. Prova real é necessária para aprovar produto, além de unitários.

## Entrega

RELATORIO-FASE-03, report.json/coverage.csv, patch por seam, plano de cortes por SHA, referências e saídas públicas, decisões de junção e todos os casos negativos, métricas longas/memória/cancelamento, logs/gates e limitações.

Explicar em linguagem de produto o que ocorre ao selecionar um arquivo de 5 minutos e o que ocorre se o terceiro chunk falhar. Devolver ao especialista para decidir a F04; não elevar a janela nesta fase.
