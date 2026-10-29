# Ordem F04 — Viabilidade de uma janela de até 60 segundos

Revisão 20261020.1, condicionada aos pareceres F02/F03. Objetivo: decidir, com evidência, se uma janela real de 60 s melhora a ferramenta em algum perfil/aparelho. Áudio maior continuará segmentado automaticamente mesmo se a hipótese 60 s for rejeitada.

## Leituras e entradas

Ler contrato comum, diagnóstico v2, código fonte NAR da revisão fixada, frontend/exportador/manifesto/loader e resultados de F02/F03. Não confundir chunk_size, encoder context_size, projector block_size, buckets T/S e duração do arquivo.

## Tarefas obrigatórias

1. **F04.1 — Derivar limites reais.** Refazer as contas de amostras→frames→pooling→projector→slots→editor a partir do código real. Com o frontend atual, 960000 amostras dão T=3000. O interleave atual admite S até 2101 no pior caso. Conferir arredondamentos, comprimentos mínimos, padding, posição máxima e contexto relativo do encoder. Registrar hipóteses e limites que ainda precisam de runtime.
2. **F04.2 — Dimensionar memória/disco.** Estimar pesos e buffers por estágio/dtype, logits/atenção, cópias, sessões simultâneas e export. Distinguir estimativa de pico medido. Reservar orçamento; não iniciar export de vários buckets só para descobrir falta de espaço. Uma janela maior não deve carregar versões duplicadas de todos os pesos na RAM.
3. **F04.3 — Rever o export com um piloto.** Exportar/validar encoder/projector T=3000 e editor que comporte a sequência necessária, dinâmica validada ou bucket suficiente. tools/granite/nar/export_static.py aceita especificação de bucket, mas checar seu código/CLI e a validade do resultado antes de usar. O skip atual por existência/tamanho >1 MB não é integridade: não reutilizar arquivo parcial só por nome/tamanho.
4. **F04.4 — Preservar semântica.** Nenhuma alteração de atenção causal/bidirecional, escala, pooling, RoPE ou truncamento para “caber”. Comparar fonte→export nos tensores de estágios e no texto. Se usar padding estático, testar sua influência com lengths/mask reais. S=1408 ou 2048 não satisfaz automaticamente o pior caso 2101.
5. **F04.5 — Excesso de editor.** Testar fala densa/CTC longo, maior S válido e S acima do suporte usando fixtures de contrato. Se o export não suporta o caso, retornar ao planejador para subdividir a janela, com cobertura e junção F03; não omitir tokens ou só trocar mensagem de erro.
6. **F04.6 — Prova CPU em degraus.** Validar host primeiro; depois no aparelho reservado CPU em um áudio curto e nos comprimentos 40, 50 e 60 s, conforme orçamento/memória. Registrar load/compile/infer/total/memória e estágio limitante. Não testar t3000 no HTP nesta fase: a antiga política de proteção permanece e a F07 fará rampa própria.
7. **F04.7 — Qualidade e fronteiras.** Comparar uma passagem de até 60 s contra a mesma entrada segmentada em janelas menores aprovadas, com referências humanas. Incluir fala contínua, ruído, silêncio, nomes/números/negação, repetição, duração 59,98/60/60,02 s e 120 s. Separar ganho de contexto, alteração de corte e efeito de hardware.
8. **F04.8 — Cancelamento e sustentação.** Testar custo de uma inferência maior, duração de cancelamento, série de janelas e release. Confirmar que o pico não excede o orçamento/limite do aparelho e que uma janela maior não torna o app inutilizável. Não aprovar só porque um run terminou.
9. **F04.9 — Matriz de decisão.** Para cada perfil CPU/grafo/precisão/aparelho testado, emitir max_audio_window_seconds, supported_T, supported_S, constraints e resultado: viável experimental, útil e candidata à confirmação, inviável nesta combinação ou sem prova. Definir janela operacional preferida separada do máximo.
10. **F04.10 — Política compatível.** Propor capacidade por artefato/perfil consumida pelo planejador. Se 60 s falhar, manter janela menor validada e áudio longo funcional; apresentar menor bloqueio para reabrir. Não elevar constante global para backends ainda não testados.

## Aceitação

- Shapes e contratos suportam 60 s reais sem truncamento, quando a rota for classificada viável.
- Referência numérica, qualidade piloto, memória/tempo e teste em aparelho identificados.
- Excesso de S/T e 60 s +1 frame são tratados automaticamente pelo planejador sem perda.
- Decisão pode ser negativa: é aceita como investigação encerrada se o bloqueio é reproduzível e não destrói áudio longo.
- Para ferramentas, testes pertinentes; para integração Android, unitários/lint/assemble e mapa/KDoc. Native ZIP não muda só por novo grafo; verificar contrato antes de gerar pacote.

## Entrega

RELATORIO-FASE-04/report.json/coverage.csv, derivação dos limites, modelos/export manifests, tensor comparisons, métricas e qualidade por janela, política proposta, patch separado e limites conhecidos.

O especialista decide o teto por perfil e atualiza os próximos prompts. Não declarar 60 s aprovado em GPU/NPU com resultado apenas CPU.
