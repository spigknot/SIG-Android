# Resposta executável ao relatório V2 — continuar perfis NAR

Leia este adendo e continue docs/prompt-pc-auxiliar-granite-perfis-20260914.md. As cinco perguntas estão respondidas abaixo. Não repetir pedidos de autorização já resolvidos por esses documentos. Não alterar produção/defaults, publicar APK ou fazer push nesta rodada.

## 1. CER está autorizado, junto de WER e estabilidade

SIM: CER é critério oficial de avaliação dos perfis, inclusive int8/NPU. NÃO é critério único. Manter WER, avaliação por idioma, erros relevantes, repetições e taxa de execução. Igualdade normalizada serve a testes de equivalência de implementação e como diagnóstico entre backends; não é veto universal para um perfil deliberadamente menos preciso.

O prompt anterior já definiu os limites. Ficam reafirmados como limites de CANDIDATURA experimental, não aprovação automática de release:

| Classe | Delta CER global | Delta WER global | Delta WER por idioma |
|---|---|---|---|
| Equilibrado | <=1,0 ponto percentual | <=1,0 pp | <=1,5 pp |
| Rápido/leve | <=2,0 pp | <=3,0 pp | <=5,0 pp |

Calcular contra a referência float e informar também contra EQ CPU U8/int8b. Para N1 ser equivalente ao perfil EQ, aplicar a faixa Equilibrado também contra EQ; para oferecer perda deliberada no perfil Rápido, aplicar a faixa Rápido também contra EQ. Comparar sempre os mesmos áudios. Exemplo: CER CPU=4,0% e NPU=5,5% significa +1,5 pp, não +1,5% relativo. Não trocar denominadores entre hipóteses.

Perfil Rápido/leve precisa de benefício: >=20% de ganho mediano end-to-end sobre EQ em comparação térmica controlada OU >=30% de redução de pico de RAM ou download total necessário. Se só diminuir download/RAM, nome provisório Leve; não anunciar Rápido. Um ganho do encoder isolado não prova ganho do pipeline.

Repetição “aprenderender” entra como erro lexical e ocorrência de repetição artificial. Pode coexistir com candidatura experimental dentro dos limites agregados, mas deve ser apresentada para revisão antes de release. Não usar n-gramas como substituto de WER/CER: repetição de morfema dentro de palavra pode escapar de n-gramas de palavras. Registrar eventos por áudio, posição, trecho, diferença contra referência e revisão; repetição genuína da fala não é artefato. Não criar regex para esconder erro.

Q4 não está globalmente reprovado por duas repetições de UMA amostra. Reclassificar como known-regression/holdout-pending e executar a avaliação autorizada. Int2b continua fora do escopo por colapso já relatado. Rascunho CTC mantém caráter exploratório, com qualidade medida e decisão posterior.

## 2. Falhas de execução não são perda de precisão aceitável

Tratar rodadas sem inferência/terminal como defeito a investigar antes de promover qualquer perfil. Não atribuir ainda ao HTP: dois start podem indicar duplo lançamento, Activity reiniciada, PID novo ou mistura de captura. Timeout pode ser carga lenta, crash ou protocolo. Determinar por evidência.

Criar ledger de TODAS as tentativas com run_id, serial, áudio/hash, APK/hash, artefatos efetivos, backend esperado/observado, PID, process instance, Activity instance, horários, status de execução e qualidade. Publicar denominadores separados:
- tentativas totais e execuções completas;
- capturas válidas e inferências com oracle;
- falhas de infraestrutura, protocolo, sessão e execução;
- áudios únicos, repetições e idiomas;
- WER/CER condicional às saídas capturadas e taxa de sucesso operacional.

Nunca melhorar taxa removendo uma falha e contando apenas seu retry. Nunca atribuir WER 1 a captura ausente. Ela é qualidade desconhecida e falha operacional/captura no denominador apropriado.

Há inconsistência a resolver no V2: tabela lista quatro inválidas, resumo diz três inválidas em doze. Na cópia local lida pelo cérebro, reverificacao.jsonl contém doze linhas: nove rc=0 e três rc=4. Esclarecer se a rodada BACKEND incorreto foi anterior/externa à bateria. Não apagar evidência para forçar a conta. A ordem cpu,npu,npu,cpu é ABBA, não ABAB; derivar a ordem de timestamps, não da ordem de linhas no relatório.

Depois da correção, exigir um smoke operacional de pelo menos vinte tentativas consecutivas planejadas por configuração sobrevivente, distribuídas entre aberturas com processo novo e inferências com sessão quente, em pelo menos dois áudios/t200-t400. Exigir zero falha não explicada/zero SSR nessa prova antes de candidatura. Isso é gate de engenharia, não prova estatística de confiabilidade. Uma falha exige diagnóstico; não repetir baterias até obter vinte sucessos e ocultar falhas anteriores.

Pode continuar preparando holdout, métricas e instrumentos enquanto investiga. Resultado parcial de qualidade é útil, mas não aprova perfil instável.

## 3. Fontes do holdout — executar, não aguardar escolha

Use a mesma família FLEURS já adotada no projeto: google/fleurs, configurações pt_br, en_us, es_419, fr_fr, de_de. Alvo mínimo: cinquenta áudios independentes, dez por idioma. Começar auditando arquivos e pools efetivos de calibração/export/ajustes; o corpus existente pode conter amostras ainda elegíveis, mas estar fora de dezesseis itens de uma calibração não basta se outros experimentos também as usaram para ajuste.

Se faltarem itens, baixar amostras do split test, após verificar que não foram usadas anteriormente; registrar revisão exata do dataset, split, ID, licença, referência e hash de áudio. Se o test já estiver contaminado, escolher itens realmente não usados e registrar a seleção. Não chamar de holdout as regressões já analisadas. Freeze do manifesto antes de escolher a variante vencedora, seed fixa e deduplicação por ID e áudio.

Para comparar NPU t200/t400, construir estrato de fala completa que caiba nesses buckets sem truncamento artificial. Se cinquenta elegíveis não estiverem disponíveis, baixar mais dados do mesmo dataset antes de reduzir cobertura. Registrar o viés de duração: esse ensaio não valida NPU em áudio longo. Avaliação CPU dos buckets longos permanece separada. Emulador e segundo telefone não substituem o aparelho-alvo para aprovação de desempenho.

## 4. Instrumentar resolved path vazio — autorizado

Implementar em debug e testar agora. A mensagem significa que a resolução falhou ou não produziu caminho utilizável; não prova corrida nem exclui arquivo ausente/inacessível naquele instante. Existência observada depois não comprova disponibilidade no momento do erro.

Antes de env.createSession, registrar run_id, PID/thread, instância de sessão, modelo absoluto e canônico, diretório pai, modo de criação (path/bytes), identidade da variante e do pacote. Para cada external data: location exata, caminho resolvido, existência, tamanho, legibilidade, offset/length e exceção completa com cadeia de causas. Preservar mensagens de sistema/errno disponíveis. Não registrar conteúdo de pesos nem credenciais.

Conferir o hash de modelo/dados no preflight, fora do ensaio temporal. Não calcular 1,6 GB de SHA em cada load. Durante load, registrar metadados e tentativa mínima de abertura/leitura; no erro, coletar novo estado e comparar. Validar que o grafo foi carregado por caminho absoluto estável e que location é relativo a seu diretório; não consertar por renomear pesos até confirmar o contrato.

Auditar download, troca de variante, restore e fechamento de sessões: nenhum desses deve mover/apagar/substituir arquivos enquanto sessões estão sendo criadas/usadas. Testar primeiro LLM CPU isolado em diretório experimental imutável, com modelo e dados verificados e sem troca de preferências durante a bateria. Se falhar, isso enfraquece hipótese de materialização concorrente; investigar log/armazenamento/runtime. Se só falhar durante troca, adicionar sincronização sustentada por evidência e teste da corrida.

Não aplicar retry silencioso, suprimir a exceção ou redownload automático como “correção”. Registrar falha original e, só após coleta, permitir uma tentativa diagnóstica controlada. Instrumentação não deve tornar inferência normal dependente de hashes completos a cada chamada.

## 5. Próximas ações, em ordem

1. Corrigir ledger/contagens; confirmar serial em TODOS os subprocessos e restauração; manter lock por serial e stream/run_id exclusivos. Serial alvo conforme relatório: 3B15BD00FVE00000. Não tocar no PJA110 ou no emulador da outra tarefa.
2. Completar comprovação on-device do APK P2: load não abre t2000, 175 frames→t200, 354→t400, >t400 NPU estrita rejeitado antes do DSP. Registrar hash/commit do APK para não testar inadvertidamente versão antiga.
3. Instrumentar falhas de sessão/captura conforme seções 2/4; identificar QAIRT real. Gates de teste que abortaram por recursos são inconclusivos/infra-failed, não passed nem necessariamente “não executados”: registrar até onde executaram. Rodar lintDebug/assembleDebug além da suíte JVM final quando faltarem.
4. Preparar holdout enquanto não há aparelho; finalizar triagem de oito falas+silêncio. Três áudios de reverificação mais um reprodutor não completam o plano original.
5. Implementar e executar captura/replay P5 e contexto mode=2 P6 conforme prompt de perfis. Comparação quente EQ/N1 pode avançar sem cache quando execução estiver estável; comparação de primeiro uso inclui preparação/restauração real.
6. GPU por estágio, Q4 e CTC conforme os pilotos já autorizados; avaliar sobreviventes no holdout e entregar catálogo Pareto. Não trocar AAR/QAIRT durante comparação nem reabrir LLM-NPU sem hipótese nova.

Conclusão desta decisão: as cinco dúvidas estão resolvidas. Avançar com os instrumentos e provas pendentes, não encerrar por falta de autorização para CER, corpus ou captura. Guardar evidências e novos artefatos em disco saudável e R2 experimental; a publicação de produto continua aguardando revisão.
