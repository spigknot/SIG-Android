# Ordem de continuação — sair da auditoria para medir perfis

Recebido o relatório pós-auditoria de 15/09. O cérebro executou teste_regressao_avaliador.py e confirmou 18/18 passando. Aceito as correções como avanço do instrumento; isso ainda não aprova os perfis nem encerra o escopo. Continue o plano unificado dos quinze áudios e o plano de perfis. Este documento apenas fixa os próximos marcos e dois ajustes identificados no código.

## A. Fechar o instrumento sem abrir nova rodada de pesquisa

1. corpus_recalcula.py chama avalia sem selecionar normalização. O arquivo metrics-holdout-ptbr-v2.jsonl contém norma_versao=v1. Portanto v2 é versão do relatório/avaliador, NÃO a normalização nova. Preservar esse arquivo para reproduzir os resultados históricos. Acrescentar argumento explícito de normalização e gerar arquivo separado com norm-v2 no nome e no conteúdo, preservando separadores significativos. Publicar ambos, com deltas e versão explícita; não rebatizar resultados v1 como v2. Novas baterias devem fixar norm-v2 antes de executar. Não mudar o padrão global silenciosamente.
2. analisa_metricas.py::pareado_por_audio usa somente item e sobrescreve o registro anterior do mesmo perfil. Implementar pareamento pela identidade do áudio convertido, hash da referência normalizada e versão do normalizador; manter item como rótulo. Registrar separadamente build, modelo e configuração para não misturar baterias. Se hashes faltarem, recuperar dos manifests por vínculo inequívoco; senão marcar legacy-unverified e não aprovar gate com esse par.
3. Duplicatas exigem política explícita: filtrar bateria/run_ids definidos previamente, ou agregar repetições segundo regra registrada. Nunca ficar silenciosamente com a última linha. Excluir métricas null do numerador E do denominador pareado. Exigir mesma referência/denominador e versão nos dois lados. Captura válida de silêncio pode ter WER null; isso não deve diluir o delta.
4. Testes mínimos novos: mesmo item com áudio diferente; mesma gravação com referências divergentes; duplicata de perfil; ordem de linhas invertida; null de um lado; normalizadores diferentes. Resultado deve recusar mistura ou produzir invariância documentada, nunca aprovar silenciosamente.

Após isso, congelar avaliador e seguir para B. Não refazer todas as inferências para recalcular métricas: reaproveitar saídas brutas com identidade comprovada. Os números antigos permanecem descritivos com suas limitações.

## B. Primeiro marco: execução segura e provas reais do APK final

Implementar os itens já autorizados, em worktree isolada, sem push ou mistura com tarefa FFmpeg:

- Portão de perfil/backend ANTES de qualquer sessão, inclusive LLM. Não permitir que LLM-NPU conhecido como incompatível chegue ao DSP antes da política de bucket. Perfil N1 é encoder HTP + projector/editor CPU explicitamente.
- Pacote experimental imutável durante inferência e criação de sessão. Instrumentar o ENOENT e resolved path vazio no instante da falha. Guardar caminho, estado, exceção e identidade; sem retry silencioso. Encontrar se algum script de restore/download/troca interfere enquanto uma sessão existe.
- OOM: capturar stack/shape/tamanho da alocação e reproduzir o áudio longo CPU em processo novo. Corrigir o ponto de cópia/alocação demonstrado ou entregar contenção por segmentação com cobertura integral. Não declarar limite universal de 37 s nem aumentar heap por palpite.
- APK/ORT/QAIRT e modelos identificados; serial exclusivo. Provar load sem t2000, seleção t200/t400 e recusa de bucket NPU não aprovado antes de chamada DSP. Confirmar que o APK instalado é realmente o construído.

Falha intermitente não precisa ser reproduzida infinitamente: fazer uma bateria limitada conforme o gate operacional anterior, preservar tentativas e registrar not-reproduced quando aplicável. Não chamar not-reproduced de fixed. Se B bloquear apenas NPU, avançar com testes CPU seguros e instrumentos offline.

## C. Segundo marco: entregar as transcrições pessoais

Os quinze arquivos estão em D:\15 audios; referências e protocolo em docs/prompt-unificado-pc-auxiliar-nar-ptbr-15-audios.md. Já foram inventariados/convertidos. Reutilizar cópias verificadas.

Executar EQ em 1–14 e N1 somente nos elegíveis t200/t400, quando B permitir. Refazer apenas runs de identidade inválida ou afetados por correção. Referência expected_script ainda não conferida por escuta não impede rodar: métricas provisórias claramente marcadas, saídas brutas preservadas e lista pontual de diferenças para o usuário confirmar. Não escrever apenas “referências por ouvir” como motivo para deixar todas as transcrições pendentes.

Executar 15 inteiro via segmentos já definidos, conferir cobertura e cauda, recompor e medir. CPU/NPU com os mesmos segmentos para isolamento de backend; segmentos maiores CPU são outra comparação de pipeline. Não contar segmentação preparada como transcrição concluída. Nenhum áudio pessoal/transcrição/tensor pessoal no R2.

Entrega C: tabela local por arquivo com saída EQ/N1, WER/CER/versionamento/referência, conteúdo crítico, backend, falhas e duração total. Número de arquivos únicos e segmentos separados.

## D. Terceiro marco: pilotos que criam opções de produto

Executar um piloto de cada caminho, de forma sequencial e limitada:

1. CTC: encoder + decoder correto, sem carregar projector/editor. Comparar qualidade e latência integral com EQ nos mesmos áudios. Perfil Rascunho é hipótese de maior redução de trabalho; se incoerente, rejeitar com saídas e referência.
2. Q4: mesmo encoder/projector de EQ, só editor int4b. Medir qualidade PT-BR, RAM e download do pacote inteiro. Regressão conhecida não substitui avaliação de taxa. Se só ganha memória, candidato Leve.
3. N2: contexto mode=2 t400 gerado por SessionOptions, três reaberturas sem recompilação e texto comparado ao mesmo grafo sem cache. Medir load frio/primeira/quente, sem prometer que cache reduz erro numérico ou memória de execução.
4. GPU: encoder/projector float t200 isolados com controle CPU do mesmo hash, backend real comprovado; t400 só se passar. Primeiro erro estruturado deve trazer nó/fase/log. No máximo uma correção localizada antes de devolver impedimento. Não repetir flags arbitrariamente.

P5 replay continua autorizado para uma amostra dirigida, com duas saídas CPU/HTP cruzadas e downstream fixo. Ele deve ajudar uma hipótese concreta, não atrasar todos os outros pilotos.

Nenhum upgrade de AAR/QAIRT nesta rodada sem novo plano explícito. Nenhum LLM-NPU/t2000 DSP liberado. Contexto diagnóstico de qualidade ruim não é candidato de produto.

## E. Fechamento da rodada

Depois de A/B/C/D, ampliar REF e avaliar sobreviventes no holdout PT-BR usando pares corretos e taxas micro. Manter critérios já fixados de Equilibrado/Rápido/Leve e revisão de casos graves. Não atribuir ganho quente com base em rodadas de qualidade aquecidas de forma diferente.

O próximo relatório deve trazer os resultados de A, B, C e dos pilotos D, não apenas a lista de coisas ainda não iniciadas. Se faltar recurso real, indicar comando tentado, erro e fase bloqueada, seguindo partes independentes. Atualizações intermediárias são bem-vindas e não precisam ser chamadas de relatório final.

Entregar commits/patchs próprios, testes relevantes e gates Android da revisão final, APK/hash efetivamente usado, ledgers, métricas e catálogo provisório: Equilibrado, Leve, Rascunho e opções aceleradas que demonstrarem benefício. Manter produção e preferências restauradas; não publicar release. O objetivo é fechar alternativas reais para o usuário, não preencher cinco nomes sem evidência.
