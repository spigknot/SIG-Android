# Continuação NAR PT-BR — consolidar H8 e medir CTC corretamente

Data: 16/09/2026. Resposta à entrega da ordem `ordem-pc-auxiliar-nar-perfis-reais-20260915.md`. Leia esta ordem integralmente. Ela atualiza as decisões conflitantes e preserva os resultados válidos da rodada anterior.

Objetivo: obter opções úteis de precisão, tempo e memória para PT-BR. As prioridades são H8 (encoder de REF + editor int8b), Rascunho CTC e Compacto Q4. Execute por marcos e entregue evidências de cada marco. A aprovação aqui é de experimentos; nenhum perfil recebe aprovação de produção.

## 1. O que foi conferido e quais conclusões precisam mudar

O cérebro leu os arquivos pelo SMB; um subagente fez uma revisão delimitada de N2/carga. Não houve execução de ADB nesta revisão.

### 1.1 H8: resultado positivo, com escopo definido

- Conferência direta dos resultados e sessões: H8 e R produziram textos BRUTOS idênticos nos 24 áudios públicos pareados. Esse resultado sustenta continuar H8 como candidato de precisão com editor menor.
- Na triagem de 12, R/H8 somam 13 erros em 125 palavras; E8/EF somam 24/125. O aumento de 11/125 = 8,8 pontos percentuais acompanha a troca do encoder nesse conjunto dirigido. Não confundir com os +5,49 pp dos 24 da comparação anterior.
- Escrever “nenhuma perda observada com int8b neste conjunto”. Não generalizar para “editor custa zero” ou “toda perda em qualquer áudio vem do encoder”.
- H8 público levou 41.683–101.786 ms de inferência nos registros conferidos. Nos 14 pessoais, 61.140–183.467 ms. Qualidade preservada e memória menor não comprovam velocidade aceitável.
- As sessões da ablação H8/E8 observadas usaram o editor `logits`. A prova anterior do derivado ArgMax continua válida em seu escopo, mas não transformar retroativamente a ablação em teste de `token_ids`.
- Encontrei 14 resultados pessoais H8 e correspondentes R; o relatório informa 13 pares de métricas. Explicitar qual item foi excluído e por quê. Não alterar a regra de métricas para forçar 14 pares.

### 1.2 CTC: a comparação desta rodada está incorreta

Em `nar-next-20260914-0950/orquestrador.py`, a configuração `ctc` não envia `CTC_ONLY=1`, enquanto `ctcnpu` envia. Os eventos originais confirmam:

| Caso | CPU observado | HTP observado |
|---|---|---|
| p2-175 | `route=full`, inferência 6572 ms | `route=ctc`, inferência 1316 ms |
| nar-curto | `route=full`, inferência 5156 ms | `route=ctc`, inferência 1306 ms |
| pt-br-1651 | `route=full`, inferência 8654 ms | `route=ctc`, inferência 1412 ms, mode=2 |
| pessoal 3 | `route=full`, inferência 7631 ms | `route=ctc`, inferência 1139 ms |

Cada execução válida contém somente uma inferência medida. O título “sessão reutilizada” não é sustentado por essas execuções. Os tempos do encoder continuam sendo observações úteis por estágio; não são prova de um comparativo CTC CPU×HTP controlado.

`duracao_s` mede o subprocesso completo: inclui verificação, transferência/backup/restauração e esperas. Não apresentá-lo como carga do modelo ou latência de uso do app. O evento `load.elapsed_ms` distingue carga; `inference.elapsed_ms` distingue inferência.

Retirar o veredito “3/4 divergências CPU×HTP em CTC” dessa bateria até comparar as duas rotas CTC. O HTP sozinho pode produzir “aprenderender” sem editor, mas isso não prova que CPU CTC produziria “aprender”. A investigação anterior dos IDs permanece evidência distinta. O replay cruzado IDs/features continua opcional para a decisão de produto, sem declarar causa exclusiva provada.

### 1.3 Os dez ciclos não executaram ArgMax

Run `ciclos-ciclos-a-453f0f00`: o primeiro `load` e os probes de recarga indicam `llm editor (Equilibrado, logits)`, grafo `granite-4.1-nar-llm-int8b-blk128.onnx`. O driver não propagou `editor_token_ids=true`.

- Preservar os dez ciclos como prova do caminho int8b/logits. As dez transcrições têm o mesmo hash, e o PSS não mostra aumento monotônico nessa janela.
- Não encerram o teste de ciclos do caminho ArgMax. Esse teste ainda deve ser executado.
- Corrigir unidades: 4.142.190 KiB → aproximadamente 4045 MiB; 4.175.582 KiB → aproximadamente 4078 MiB. O relatório “4142→4175 KB” está incorreto. Distinguir KiB/MiB/GiB de bytes/MB/GB em todas as tabelas.

### 1.4 N2: reconhecer o avanço e identificar o modelo certo

- OFF/GENERATE/LOAD, hashes dos artefatos declarados, opções/QAIRT, recusa de caminho inválido e do binário adulterado avançaram. As três reaberturas são evidência válida do mecanismo no piloto.
- O sidecar de `quality/n2-piloto3` registra grafo `3c6f85fa...` e pesos `6b65e9fb...`: são os arquivos do encoder de REF. O campo `precisao=u8` foi informado pelo piloto e não descreve esses arquivos. Corrigir a identidade declarativa; não reclassificar como prova do pacote U8.
- Em `L1-load.log`, a inferência do encoder foi 38.945 ms e a total 41.367 ms. Em `U-reuse.log`, as inferências do encoder foram 89.987/87.914 ms. Os ~1,6 s citados são de abertura/parte da carga, não da inferência.
- O evento `load` desses runs registra `strict=false`. `effective_backend=NPU_QNN_HTP` sozinho não prova atribuição integral do encoder ao HTP. Conferir particionamento/fallback por sessão antes de atribuir os tempos ao hardware.
- A geração ainda descobre binários por diferença de nomes antes/depois, não pelo conjunto realmente referenciado pelo wrapper. Um arquivo preexistente sobrescrito pode faltar na lista; mesmo uma lista vazia recebe `completo=true` na geração, embora o LOAD a recuse. O contrato ainda precisa dessa correção delimitada.

## 2. Ambiente e limites de execução

No PC Gustavo:

- Projeto: `D:\Projetos\SIG` (nesta sessão acessível por `\\Gustavo\Projetos\SIG`).
- Worktree experimental: `D:\SIG-perfis-20260914-123811` (SMB: `\\Gustavo\d\SIG-perfis-20260914-123811`).
- Lab: `D:\SIG-granite-nar-lab-rebuild`; bateria: `nar-next-20260914-0950`.
- Gravações: `D:\15 audios` (SMB: `\\Gustavo\d\15 audios`).

Confirme máquina, HEAD, diff e APK usados antes de executar. Caminhos D:/C: do PC Gustavo não são os discos locais de outro PC. Git via SMB pode apontar metadados de worktree para caminhos locais do host: isso não autoriza `prune`, remoção de worktrees ou reparo global. Faça Git/build/ADB no host adequado e preserve alterações alheias.

Mantenha AGENTS.md, hooks e gates. Nenhum push, release, mudança de defaults ou publicação de dados pessoais. Os áudios pessoais e derivados ficam locais, fora da calibração. Use diretórios experimentais próprios. Não acesse E:. Não reabra GPU, LLM-NPU ou quantização em lote nesta rodada.

Um único controlador do aparelho. A função importável `roda()` hoje não adquire o lock; o lock está no `main()`. Drivers que a chamam diretamente precisam estar cobertos por uma única proteção real. Corrigir essa fronteira e testar exclusão concorrente sem tocar no aparelho. Registre host+PID+run_id: PID de outra máquina não pode ser tratado como PID local abandonado. Toda troca de artefato e restauração deve ocorrer dentro dessa exclusão.

Instale uma vez por versão do APK de teste e prepare o pacote antes das medições. Custos de instalação, hash de transferência e restauração pertencem ao protocolo operacional. Hash/validação executados pelo próprio app permanecem incluídos na latência do app.

## 3. Marco A — impedir que o teste execute outra configuração

Faça a correção mínima no driver e na instrumentação de debug antes de novas baterias:

1. Cada configuração declara explicitamente rota (`full`/`ctc`), backend por estágio, variante, contrato de saída (`logits`/`token_ids`), bucket, cache (`off`/`generate`/`load`), opções QNN e contagens. Não herdar opções acidentais do ambiente da execução anterior.
2. `ctc` deve enviar a opção CTC verdadeira; `ctcnpu` também. Para full, a opção deve ser falsa/ausente conforme o contrato do script. Cuidado: o script atual verifica se certas variáveis são não vazias; a string `0` pode ativar um booleano indevidamente. Testar os argumentos finais.
3. Os ciclos ArgMax devem enviar `editor_token_ids=true`, selecionar grafo derivado validado e confirmar o contrato efetivo. Em toda recarga, confirmar novamente a configuração.
4. Os eventos do app devem declarar a configuração efetiva e os hashes relevantes, inclusive no início e por sessão. Comparar solicitado×observado automaticamente. Um título de pasta ou comentário não conta como prova. Precisão declarada deve estar vinculada ao manifesto validado, não apenas a um extra livre.
5. Validar contagens, índices, `kind`, `route`, status de inferência e término, modelo carregado, contrato e processo. `valido` não pode depender apenas de existir terminal e uma inferência. Resultado de rota errada = execução concluída, experimento inválido, com motivo explícito.
6. Extrair eventos por run_id+pid+tipo+índice, com hash do stream. Variante não deve ser inferida por procurar qualquer substring no log inteiro.
7. Testes focais: CTC solicitado/full observado; token_ids solicitado/logits observado; contagem incompleta; terminal failed com inferência presente; ambiente residual; recarga que perde contrato; concorrência entre chamadas importadas. Depois um smoke curto de cada rota aprovada.

Entregue a errata e o teste de regressão desses dois erros. Preserve arquivos históricos; publique a reclassificação em arquivos novos, sem apagar os resultados brutos.

## 4. Marco B — concluir ciclos ArgMax e reduzir custo de carga

### 4.1 Dez ciclos reais de token_ids

Execute no mesmo processo, com carga/descarga, usando o derivado int8b ArgMax já aprovado e o áudio curto anterior. Registre contrato, grafo e dados externos em cada ciclo; texto/IDs, PSS antes e depois, heap Java/nativo, carga, inferência e liberação.

Exigir 10/10 na rota correta, paridade com a referência correspondente e ausência de crescimento sustentado de memória. Reportar qualquer falha e retry. Preservar o ensaio anterior de logits como controle, sem misturar suas linhas no novo ensaio.

H8 com ArgMax exige um descritor experimental próprio compatível com encoder de REF. Não desligar a proteção que recusa mistura de pacotes para reutilizar um manifesto U8. Reusar os pesos do editor somente após conferir identidade e criar o contrato adequado.

### 4.2 Carga

Instrumentar dentro do bloco hoje chamado frontend: tabelas acústicas, tokenizer, embeddings/mapeamento, leitura/validação e demais operações. Medir cada operação separadamente e fechar a soma. O nome “frontend” e o tamanho de 411 MB não identificam sozinhos a causa de 10–20 segundos.

A fonte atual faz leitura/parse de vocabulário, construção das tabelas/FFT e mmap nesse bloco. Não há leitura integral explícita dos 411 MB nem hashing do EMBED ali. `parseVocabJson` é um candidato à medição, por usar `Regex.findAll` para cada token; testar a hipótese antes de atribuir a demora ao mmap. Se otimizar o parser, verificar a lista inteira de tokens e IDs, incluindo escapes e Unicode, sem mudar a semântica junto com a medição de velocidade.

Com base no maior custo comprovado, implementar uma otimização limitada. Priorizar eliminar trabalho repetido e carregar somente recursos necessários à rota. Se usar cache em processo, vincular à identidade dos arquivos/configuração, invalidar corretamente e definir ciclo de vida e limite de memória. Não omitir integridade para melhorar o número.

CTC só precisa dos recursos realmente consumidos por encoder+decodificação. Verificar se sua carga ainda abre projector ou prepara embeddings/editor sem necessidade. Separar explicitamente `loadEncoderOnly` do pipeline completo, com dependências corretas e teste de não abertura dos estágios excluídos.

Medir antes/depois no mesmo aparelho, APK comparável e entrada: três processos novos e uma sessão reutilizada. Reportar texto, tempo total do app e memória. Uma melhoria de carga não é melhoria da inferência quente.

### 4.3 Fechar somente as lacunas remanescentes do cache

- Conferir o conjunto de binários realmente referenciados pelo wrapper antes de selar. Snapshot de nomes novos não é prova de completude. É aceitável validar o wrapper offline com parser ONNX e ligar o manifesto ao hash do wrapper, seguindo o contrato já previsto; o LOAD deve conferir esse vínculo.
- Recusar geração incompleta antes de publicar `completo=true`. Testar binário preexistente sobrescrito, referência omitida e lista vazia. Usar destino experimental novo e preservar o cache válido anterior.
- A extração atual de external data por varredura de bytes só reconhece nomes curtos em um formato restrito. Usar parser confiável ou descritor validado offline; formatos não suportados devem produzir recusa explícita, nunca identidade parcial silenciosa. O nome atual `encoder-pesos.data` está coberto; não alegar que o piloto atual já estava sem hash dos pesos.
- Corrigir o cronômetro de geração que soma `hashingIdMs` a um intervalo que já o contém. Em LOAD, separar construção/validação da identidade, hash dos binários e abertura ORT sem intervalos sobrepostos. A soma total fechada não resolve erro em subtempos aninhados.
- Não repetir toda a bateria N2 para essa correção: testes focais e um LOAD válido/uma recusa controlada no contrato corrigido bastam antes do próximo piloto específico.

## 5. Marco C — fazer H8 utilizável

O encoder de REF domina a inferência CPU. Não iniciar outra quantização ampla: primeiro localizar o custo com profiling de um único grafo/bucket e um áudio público curto.

1. Fixar t400, arquivo, opções ORT, threads, estado térmico e configurações R/H8/E8. Registrar as formas reais dos tensores. O rótulo fp16 no nome do arquivo não substitui inspeção dos tipos e operadores.
2. Gerar trace de operadores do encoder CPU, identificando maiores custos, conversões e alocações. Instrumentar inclusive a distinção entre criar sessão e `run`. A API de profiling deve ser a disponível no AAR efetivamente embarcado.
3. As medições de aceitação devem rodar sem profiling. A ablação em blocos com reboots, travas e variação 40–100 s não é um benchmark controlado de velocidade.
4. Selecionar no máximo duas intervenções justificadas pelo trace (por exemplo, preparação repetida ou configuração específica de execução). Alterar uma variável por vez. Não habilitar globalmente ALL_OPT nem atualizar ORT/QAIRT: há histórico de kernel incompatível.
5. Se o trace indicar que a precisão/tipo do grafo bloqueia o ganho, inventariar primeiro a variante float/U16 já existente e sua reprovação anterior. Um novo artefato exige hipótese explícita e piloto único t400, acompanhado de qualidade, memória e compatibilidade. Não repetir a tentativa anterior sem diferença concreta.
6. Para cada intervenção aprovada no smoke, rodar quatro áudios públicos congelados, incluindo casos de erro e controle limpo, em ordem alternada ABBA/BAAB; depois os 24 somente se houver ganho consistente e estabilidade. Mesma carga de trabalho e contagem por braço. Se não houver ganho, entregar trace e conclusão específica e continuar o marco D.

H8 é candidato a qualidade com menor editor; velocidade continua uma pergunta aberta. H4 não avança nesta rodada: os +1,6 pp da triagem não comprovam qualidade de REF. Sua economia de pesos permanece registrada para uma decisão futura de Compacto.

## 6. Marco D — CTC CPU×HTP correto, depois decisão de expansão

Reusar os quatro áudios anteriores, preservando hashes. Os dois primeiros têm conteúdo relacionado; quatro arquivos não devem ser descritos como quatro casos independentes de linguagem.

1. Primeiro executar CPU CTC genuíno nesses quatro, com o mesmo grafo U8, frontend, bucket, valid frames, máscara/padding e decode do HTP. Registrar IDs anteriores e posteriores ao collapse quando necessário. Não comparar saída do editor CPU com saída CTC HTP.
2. Para cada backend, uma carga, um warmup e três inferências medidas no mesmo processo/sessão. Registrar session_id e ausência de recarga entre as medidas. Alternar a ordem dos backends por áudio.
3. t400 HTP começa com a configuração mode=2 que já executou. Registrar o nome exato da opção, mapa efetivo e identidade do grafo. Não repetir o mode=1 que travou por 26 minutos. Na sessão acelerada do encoder, proibir fallback CPU e provar a atribuição dos nós/subgrafos pelo runtime; sessões intencionalmente CPU de outras etapas não devem ser confundidas com fallback. Se a exigência estrita falhar, classificar o braço como híbrido/impedido e registrar a partição observada.
4. Congelar orçamento de tempo antes de executar: inferência curta sem progresso por 120 s encerra a tentativa; primeira geração/compilação pode ter orçamento separado, no máximo 10 min por piloto. Capturar diagnóstico e encerrar apenas o processo da tentativa autorizada. Reboot inesperado, OOM ou travamento encerra aquele braço até investigação; não gerar um ciclo de reboots para completar a tabela.
5. A prova anterior N2 não autoriza reutilizar contexto de outro encoder/precisão. Se a carga impedir decisão e o HTP for estável e útil na inferência, gerar um único contexto mode=2 para o U8 t400 exato; seguir o contrato de cache validado. Medir a geração separadamente e fazer três LOADs, incluindo texto.
6. Reportar por áudio: carga do app, primeira inferência, mediana quente, texto e WER/CER contra referência, IDs divergentes, memória e falhas. Informar custo do protocolo externo separadamente.
7. Medir a utilidade total. O ponto de amortização, quando aplicável, é `(carga_HTP - carga_CPU) / (inferência_CPU - inferência_HTP)` para o mesmo trabalho e sessão reutilizada. Se o denominador for não positivo, não há amortização por velocidade nessa medição.
8. Expandir qualidade somente se houver estabilidade e vantagem no cenário de uso declarado. Divergência lexical isolada não define sozinha a utilidade de Rascunho; reportar perdas de negação, números, nomes e repetições além de WER/CER. Usar os limites já definidos para o perfil aplicável, sem relaxamento retroativo.

O replay cruzado de features/IDs pode permanecer adiado se essa comparação bastar para a decisão. Descrever precisamente o alcance da conclusão causal.

## 7. Compacto, métricas e encerramento

Q4 segue candidato a Compacto. Não repetir a bateria completa de qualidade já válida. A redução de download é útil, mas “menor PSS de todos” requer comparação com os candidatos pertinentes, incluindo H4 se essa afirmação for feita, e com mesma carga de trabalho.

A tabela atual mistura 38 execuções de R/H8 (públicos+pessoais), 12 de E8/EF e 24 de Q4. Recalcular primeiro memória nos áudios públicos em comum e separar carga, pós-inferência e pico. PSS após inferência não é pico de RAM; uma amostra sem crescimento visível não prova ausência geral de vazamento. Não somar/diminuir medianas de corpora distintos como efeito causal de quantização.

O U8 ocupar mais bytes externos não prova que quantização sempre aumenta o pacote. Registrar pesos referenciados pelo grafo versus arquivo empacotado antes de considerar uma repacotação. Nenhuma repacotação é necessária para concluir esta rodada.

Entregas por marco, nesta ordem:

1. Errata das rotas/contratos/unidades e testes que impedem repetição do erro.
2. Dez ciclos efetivos token_ids e decomposição/otimização limitada de carga.
3. Profiling de H8, piloto de otimização e qualidade/tempo/memória resultantes.
4. CTC CPU×HTP correto e decisão sobre contexto/expansão.
5. Tabela de candidatos com conjunto, tentativa, falha, precisão, memória, download e latência do mesmo cenário.

Não reabrir uma auditoria geral do avaliador. Ajustar somente seleção/atribuição necessárias para estes marcos. Manter os 24 como conjunto exploratório; aprovação final de generalização exigirá confirmação PT-BR nova, fora de calibração e das decisões desta rodada. Gravações pessoais têm avaliação separada e referências explicitamente verificadas ou provisórias.

Para alterações de código, executar unitários focais antes do aparelho e, ao encerrar o APK, `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:assembleDebug`, além de MODULE-MAP/KDoc e hooks aplicáveis. Identificar cada APK por SHA-256 completo, commit e diff restante: não atribuir a um único APK medições feitas em outro build. Gates do host e provas de dispositivo são evidências distintas.

Referências técnicas para instrumentação, conferidas em 16/09/2026: [profiling do ONNX Runtime](https://onnxruntime.ai/docs/performance/tune-performance/profiling-tools.html) e [SessionOptions Java](https://onnxruntime.ai/docs/api/java/ai/onnxruntime/OrtSession.SessionOptions.html). O profiling informa latência por operador; conferir disponibilidade no runtime embarcado e medir desempenho final sem o custo da instrumentação.
