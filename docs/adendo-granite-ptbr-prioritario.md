# Mudança de escopo autorizada — somente PT-BR

Este adendo prevalece sobre a obrigação de avaliar cinco idiomas dos prompts anteriores. O usuário informou que só PT-BR importa. Não aguardar nova confirmação para reorganizar o corpus.

## Ações imediatas

1. Suspender novas coletas e baterias EN/ES/FR/DE. Preservar resultados existentes como histórico; eles não aprovam nem reprovam os perfis destinados a PT-BR.
2. Selecionar pelo menos cinquenta áudios PT-BR independentes de calibração/ajuste, usando google/fleurs, configuração pt_br, e split/revisão fixados. Auditar IDs/hashes contra todos os pools reais. Buscar diversidade de falantes, durações e condições quando houver metadados; não inventar quantidade de falantes se indisponível. Registrar duração total e cobertura por bucket.
3. Manter um estrato público de pelo menos vinte áudios completos elegíveis a t200/t400 para comparação NPU. Se o conjunto inicial não contiver vinte, buscar mais PT-BR elegível no dataset e registrar o conjunto ampliado. Não truncar áudio arbitrariamente. A avaliação dos buckets longos é CPU enquanto a política NPU maior não estiver validada.
4. A triagem anterior de oito falas multilíngues é substituída por oito falas PT-BR distintas + silêncio, com pelo menos duas t200 e duas t400. Reaproveitar reprodutores brasileiros existentes como regressões declaradas; não contá-los como holdout fresco. As gravações pessoais são complemento opcional, não dependência que bloqueia trabalho público.
5. Continuar imediatamente os reparos de estabilidade, prova P2 no aparelho, instrumentação do erro external data, replay e contextos. Nada disso depende de receber voz do usuário.

## Gates específicos PT-BR

Os limites permanecem em PONTOS PERCENTUAIS absolutos, agora medidos em PT-BR. Remover a coluna por idioma dos gates; não conservar um limite de +5 pp como tolerância adicional para português.

| Perfil | Delta CER PT-BR | Delta WER PT-BR |
|---|---|---|
| Equilibrado | <=1,0 pp | <=1,0 pp |
| Rápido/leve experimental | <=2,0 pp | <=3,0 pp |

Medir contra REF e informar contra EQ; aplicar ao comparativo EQ quando propondo troca de backend/perfil em relação a EQ, conforme adendo V2. Mesmos áudios e denominadores em cada par. Preservar qualidade absoluta, erros de nomes/números/negações, repetições e sucesso operacional. Zero tolerância a ocultar timeout/crash na taxa de qualidade. Esses gates selecionam candidatos, não autorizam release automático.

PT-BR pessoal é teste direcionado separado: não diluir uma falha de negação/número repetida na voz do usuário numa média de cinquenta áudios públicos. Apresentar esses casos explicitamente para a decisão de perfil. Um caso lexical isolado continua sendo evidência de trade-off, não motivo para apagar o perfil da pesquisa inteira.

Critério de vantagem continua >=20% de ganho quente end-to-end sobre EQ em ensaio controlado, OU >=30% de redução de RAM pico ou download total para perfil Leve. Reportar carga fria/contexto e limitações por bucket. NPU/GPU são meios; não priorizar rótulo de hardware em detrimento dos resultados.

## Gravações do usuário

Foi entregue docs/roteiro-gravacoes-ptbr-granite.md com quatorze falas, oito prioritárias, e fala espontânea opcional. Quando chegarem, execute suas instruções de preparação, referência literal, privacidade e métricas. Não enviar dados pessoais ao R2 sob a autorização genérica de publicar modelos.

Arquivos novos de entrega: corpus-ptbr-holdout.jsonl, corpus-ptbr-screening.jsonl, personal-recordings-status.json, metrics-ptbr-by-sample.jsonl e FINAL-PTBR.md. personal-recordings-status pode ser awaiting-user sem bloquear fases independentes. Informar quais perfis funcionam melhor em português e quais limitações foram realmente observadas.
