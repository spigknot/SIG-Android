# Próxima rodada — consolidar os avanços e recuperar precisão

Esta ordem responde à ENTREGA FINAL B/C/D/E + P5 e ao adendo Q4-24 de 15/09/2026. Complemente a ordem pós-B/C/D, sem repetir seus pilotos já concluídos. Foco exclusivo em PT-BR. Execute as fases abaixo, em sequência, com entregas intermediárias. Não implementar catálogo público nem promover release nesta rodada.

## 1. Decisões do cérebro

- Aceito a correção dos sinais: EQ − REF = +5,4902 pp WER; N1 − REF = +3,5294 pp. Os limites anteriores continuam valendo. EQ é um identificador experimental, não um perfil Equilibrado aprovado.
- N2 deixou de ser impedimento de leitura: os logs mostram LOAD real. Isso aprova o mecanismo no piloto, não sua integridade completa, ganho total ou uso em produção.
- ArgMax do editor é uma melhoria concreta de memória. O caso de 37,14 s sem OOM é avanço real. Não confundir redução da saída Java com eliminação dos logits nativos nem com ganho universal de velocidade.
- CTC é o candidato prioritário a Rascunho. O ganho de 2,1× corresponde ao áudio 15 segmentado dessa bateria; não é medição geral de todos os áudios e aparelhos.
- Q4 continua candidato a Compacto: 24,6% menos download é útil, mesmo sem cumprir a meta anterior de 30%. Não usar “fora do Leve” como veto automático a qualquer opção compacta. Economia de disco, RAM e qualidade são critérios independentes. RAM nesta expansão é não medida.
- Similaridade Q4×EQ neste conjunto NÃO prova equivalência geral nem qualidade próxima de REF. Calcular Q4×REF diretamente no mesmo conjunto. Pelos deltas arredondados, a diferença esperada é aproximadamente +4,71 pp WER e +1,45 pp CER; confirmar pelos erros absolutos.
- N1/N2 permanecem aceleradores experimentais, não “modo rápido com defeito conhecido”. Exigir benefício total medido para usar o nome Rápido.
- Congelar novas tentativas amplas de GPU e LLM-NPU. Os próximos ganhos mais justificáveis estão em CTC, memória, carga NPU e composição de precisões por estágio.

## 2. Limites operacionais

Use worktree própria, leia AGENTS.md, preserve alterações alheias e mantenha hooks. Nenhum push, APK de release, mudança de defaults ou sobrescrita de produção. Não acesse E:. Use o lab em D:. Um controlador de ADB de cada vez, com dispositivo e exclusividade confirmados antes de instalar ou trocar pacote.

Dados pessoais, referências e tensores derivados de gravações pessoais permanecem locais. Publicação R2 somente de artefatos experimentais sem esses dados, em prefixos novos e com contrato/hashes completos. Não quantize usando os áudios pessoais ou os conjuntos de avaliação.

Não refaça inferências para corrigir um JSON quando os eventos brutos com identidade suficiente já existem. Não limpe logcat de outra tarefa. Faça extração por run_id + pid + evento + índice, com origem e hash do stream. O mesmo arquivo de log pode conter vários processos e baterias.

## 3. Primeiro: fechar dois defeitos comprovados do avaliador

O cérebro inspecionou e exercitou `D:\SIG-granite-nar-lab-rebuild\nar-next-20260914-0950\quality\analisa_metricas.py`, sem alterar arquivos:

1. A definição efetiva de `chave_pareamento`, aproximadamente linha 114, usa `arquivo`/`audio`, não o SHA dos bytes do áudio. Existem duas definições da função. Duas linhas com `same.wav`, mesma referência e hashes de áudio diferentes formaram a MESMA chave. Portanto “zero hashes ausentes” ainda não é prova de identidade.
2. A agregação soma erros de todas as repetições, mas o delta subtrai esses totais e divide só pelo denominador do primeiro perfil. Um caso sintético com erro 1/2 em todos os runs, duas repetições EQ e uma N1, devolveu −25 pp em vez de ZERO.
3. `tabela_tripla` ainda sobrescreve a linha anterior do mesmo perfil/chave. Isso pode divergir da regra usada no agregado.

Correção limitada, antes de novos gates:

- Uma única função de chave: SHA-256 completo do WAV convertido efetivamente usado + hash da referência normalizada + versão explícita. Rótulo/nome não substitui hash. Recuperar hashes de manifestos históricos somente com vínculo inequívoco; caso contrário declarar legacy e não aprovar gates com esses runs.
- Isolar baterias/configurações/builds por lista de run_ids congelada. Não agregar APK, precisão ou contrato de saída diferentes como se fossem simples repetições.
- Para esta rodada de qualidade, fixar uma tentativa primária por áudio/perfil antes de rodar. Em reanálise histórica, usar a primeira tentativa designada por ordem verificável. Falhas/retries permanecem no quadro operacional; análise com recuperação por retry é secundária e explicitamente separada. Não escolher o melhor texto.
- Se houver análise de repetições, dar a cada áudio o mesmo peso de referência nos dois perfis: calcular a média dos erros desse áudio por perfil e somar sobre o mesmo denominador de referências únicas. Alternativamente usar pares de repetição completos predefinidos. Nunca subtrair totais de quantidades diferentes de tentativas.
- Tabela tripla, pares e agregados devem consumir a mesma seleção. Excluir null por métrica em ambos os lados. Mostrar resultados dos 24 pareados separados do agregado EQ dos 74 áudios; não colocar essas taxas em colunas aparentemente comparáveis.
- Testes obrigatórios: mesmo nome com bytes diferentes; bytes iguais com nomes diferentes; hash ausente; referências/normalizadores distintos; duplicata em ordem invertida; repetições 2×1 com taxas iguais produzindo delta zero; null de um lado; configuração/build divergente; identidade algébrica dos deltas no conjunto triplo.

Recalcular REF/EQ/N1/Q4 somente depois. A correção aritmética anterior permanece válida para os 24 sem duplicação conferidos; não alegar que estes novos testes, por si sós, alteraram aqueles números. O objetivo é impedir a próxima conclusão falsa.

## 4. N2: completar identidade e medir o tempo que falta

A fonte atual `GraniteNarEpContext.kt` e o trecho de geração no engine têm lacunas específicas:

- CacheId não inclui a identidade dos pesos externos do grafo nem as opções efetivas do provider, apesar do KDoc dizer que inclui.
- `wrapper_sha256` é gravado, mas não conferido em `validaParaLoad`.
- `binarios` é opcional; entradas sem nome são ignoradas e hash vazio não reprova.
- A geração encontra binários por prefixo do nome, não pelas referências reais do wrapper. Uma lista vazia/incompleta pode passar pelo validador.
- QAIRT é preenchido com `PACKAGE_VERSION`; isso pode identificar o pacote distribuído, não a versão real das bibliotecas. Registrar ambos ou manter a versão real explicitamente desconhecida, usando os hashes das bibliotecas para identidade. Não rotular versão presumida como observada.

Corrigir no escopo do contrato de cache:

1. Identidade do modelo = grafo + manifesto completo dos external data relevantes, incluindo hashes/tamanhos e referências. Acrescentar backend e mapa canônico das opções de compilação efetivas, além de ORT/QAIRT/SoC/HTP.
2. Conferir wrapper e TODOS os binários referenciados, por nomes extraídos do wrapper no empacotamento validado, não por prefixo. Para este cache externo, lista ausente/vazia ou divergente deve ser recusa. Se a extração no Android for onerosa, gerar um manifesto validado offline, vinculá-lo ao hash do wrapper e conferir o contrato antes de instalar.
3. Recusar caminho absoluto, `..`, escape canônico da pasta, item duplicado, tamanho/hash ausente ou inválido. Recusar mudança isolada dos pesos, wrapper, binário, opções ou runtime. Sidecar só fica completo após todos os artefatos terem sido escritos e validados; não deixar cache parcial elegível.
4. Não burlar integridade para melhorar benchmark. Medir hashing/validação separadamente; reaproveitar validação apenas sob contrato explícito de pacote imutável e invalidação por qualquer troca.
5. Acrescentar testes negativos de cada caso acima. Depois repetir apenas 3 LOADs do cache válido e uma recusa real controlada. Restaurar os arquivos por cópia íntegra; não destruir o cache válido.

Tempos já encontrados em `quality/n2-piloto2/n2v2.jsonl`:

- LOAD total: 15.293 / 16.174 / 16.425 ms.
- Criação do encoder: 1.746 / 1.696 / 1.536 ms.
- Nos logs, há aproximadamente 10 s entre carregar ORT JNI e anunciar QNN carregado. Isso é pista, não causa identificada.

Instrumentar intervalos mutuamente exclusivos: validação de pacote, carga nativa, seleção/carregamento HTP, frontend/tokenizer/embeddings, validação do cache, sessão encoder, projector, editor e restante. A soma deve fechar com tempo total, salvo tolerância pequena reportada. Fazer OFF×LOAD com o mesmo APK/artefato e processos novos, e depois reutilização da mesma sessão. Não atribuir ao cache ganho de inferência quente nem ocultar custo de integridade.

O ledger N2 inicial tem texto null; existe transcrição em `load-texto3.log`. Extrair por run_id correto e ligar cada prova ao seu run, sem preencher texto de um run com saída de outro. LOAD 3/3 e paridade de texto são evidências separadas se só uma rodada capturou texto.

## 5. Consolidar ArgMax, sem ampliar a exportação ainda

- `argmax.jsonl` preserva p11 inicial com saída `!`; o log `p11-retry.log` contém a execução correta `argmax-p11-retry`, além de eventos de outras rodadas. Acrescentar ao ledger a tentativa válida com run_id e motivo verificável da invalidade anterior. Não transformar retry em primeira tentativa nem contar qualquer `passed` como transcrição correta.
- O teste NaN do script não é prova de finitude do cálculo: saída int64 será finita mesmo quando os logits de origem não forem. Mantê-lo como caso inválido deliberado, fora da prova de equivalência. Testar política de rejeição de entrada não finita no seam apropriado e paridade com entradas finitas reais.
- Fixar teste de empate, blank entre repetições, offsets e limites de sequência. Verificar tipos/shapes do contrato token_ids antes da isenção da guarda; não confiar apenas na flag de debug.
- Conferir fechamento em exceção de tensores/resultados e rodar 10 ciclos curtos no mesmo processo, com carga/descarga e memória antes/depois. Não exigir que todo PSS volte instantaneamente ao início; investigar tendência crescente sustentada e objetos sem fechamento.
- Manter o derivado int8b ArgMax como base técnica dos próximos testes CPU. Não exportar todos os editores nem encoder ArgMax nesta rodada antes da decisão de precisão abaixo. Aplicar ArgMax a outro editor apenas quando esse editor se tornar candidato real ou quando for necessário para um teste longo.

## 6. Próxima pergunta de produto: dá para preservar precisão quantizando só o editor?

Ainda não isolamos quanto da perda EQ×REF vem do encoder U8 e quanto vem do editor. Não assumir que toda a perda é da NPU: EQ já perde para REF no CPU.

Congele configurações explícitas por estágio, com hashes, shapes, bucket, frontend e tokenizer. Os nomes abaixo são IDs de experimento:

| ID | Encoder | Projector | Editor | Backend |
|---|---|---|---|---|
| R | exato encoder de REF | exato projector de REF | exato editor de REF | CPU |
| E8 | exato encoder U8 de EQ | projector fixo | int8b ArgMax validado | CPU |
| H8 | encoder de REF | mesmo projector fixo | mesmo int8b ArgMax | CPU |
| EF | encoder U8 de EQ | mesmo projector fixo | editor de REF | CPU |

Primeiro conferir se REF/EQ realmente usam o mesmo projector e frontend. Se não, não fingir ablação de um fator: documentar diferenças e fixar os controles necessários antes. Registrar precisão REAL de cada tensor/peso, não deduzir de nomes que ainda contêm `fp16` em pacotes U8.

Não misturar arquivos informalmente dentro de pacote antigo. Criar descritores experimentais válidos para H8/EF com contratos e compatibilidade verificados; preservar o bloqueio de mistura acidental FP16/U8.

Triagem limitada:

1. Selecionar 6 itens dos 24 com maior regressão de EQ contra REF, após correção do avaliador, com desempate estável. Acrescentar 6 dos demais com semente fixa 15092026, favorecendo referências textuais distintas. Congelar a lista. Os primeiros 6 são diagnóstico dirigido, não evidência de generalização.
2. Rodar H8 e EF nesses 12; reutilizar R/E8 históricos apenas se configuração/entrada/build forem compatíveis. Se necessário, refazer os controles no mesmo APK. Nenhuma nova quantização para esta triagem: reutilizar pesos existentes.
3. Comparar texto, WER/CER e tempo por estágio. Se H8 recuperar o limite de +1 pp contra R na triagem, ampliar aos 24 e aos pessoais 1–14. O resultado continua exploratório porque esses itens já orientaram decisões.
4. Se H8 for bom em qualidade mas lento, reportar precisamente quanto. Não promovê-lo por nome. O objetivo é identificar um candidato intermediário entre REF e U8, não forçar um vencedor.
5. H4, com encoder de REF e editor int4b, só entra depois de H8 passar e se o benefício de armazenamento justificar o teste. Primeiro os mesmos 12, depois expansão condicional. ArgMax do Q4 exige paridade própria; a prova int8b não se transfere automaticamente.
6. Se H8 falhar no critério de precisão, analisar EF/R/E8 para localizar contribuição/interação. Não iniciar automaticamente nova quantização em lote. Entregar a matriz e a hipótese mais específica.

Critérios anteriores de qualidade continuam iguais. Não tratar ausência de diferença significativa numa amostra pequena como prova de equivalência. Fazer a seleção de candidatos antes de preparar um conjunto de confirmação PT-BR novo, sem reutilização da calibração nem escolha orientada pelo resultado. Os 24 atuais não são uma prova final de generalização.

## 7. CTC e NPU: um próximo experimento útil, não varredura

Manter CTC CPU como candidato Rascunho. Completar o quadro micro por corpus e os casos críticos dos pessoais; não usar mediana isolada como taxa geral. A gravação longa é um arquivo, seus 8 segmentos não são 8 falantes independentes.

Após N2 íntegro e estável, testar CTC HTP t200/t400: 4 áudios congelados, dois por bucket, incluindo um caso de divergência e um controle limpo. CPU e HTP devem usar exatamente o mesmo grafo, entradas e segmentação. Primeiro medir sessão reutilizada; medir carga separadamente. Expandir só se houver vantagem e execução estável. CTC remove o editor: essa mudança pode alterar o balanço total CPU/NPU, mas não assegura aceleração.

O P5 atual localiza divergência nos IDs CTC, mas não prova sozinho que eles são a ÚNICA causa do texto final: multilayer_features também vem do encoder. Se for necessário resolver a repetição para avançar NPU, faça um replay cruzado local para UM caso:

- Salvar a mesma entrada do encoder, IDs CTC e multilayer_features de CPU e HTP, com shapes/dtypes/hashes. Logs de IDs já colapsados não mostram necessariamente o frame original da divergência.
- Fixar projector/editor CPU. Executar quatro combinações: IDs CPU + features CPU; IDs HTP + features CPU; IDs CPU + features HTP; IDs HTP + features HTP. Reconstruir interleave, slots e posições a partir dos IDs de cada combinação, sem reutilizar shapes incorretos.
- Isso separa a contribuição dos IDs da contribuição das features e sua interação. Não “corrigir” a cauda por regex ou trocar seletivamente um token em produção.
- Se houver motivo para ampliar precisão do encoder, inventariar primeiro o U16 existente e sua evidência. Só executar novo piloto t400 CPU/HTP quando a hipótese e a diferença para a tentativa anterior estiverem explícitas. Nenhuma reexportação de todos os buckets, nenhuma calibração com o caso que se quer consertar, nenhum upgrade ORT/QAIRT nesta rodada.

## 8. Entrega objetiva

Entregar por marcos, sem aguardar tudo para reportar:

1. Avaliador: testes reproduzindo os dois defeitos e passando depois; tabela corrigida por mesmo conjunto, com hashes e seleção de runs.
2. N2: identidade completa e validação negativa; três LOADs; decomposição do tempo e comparação OFF×LOAD. Resolver flakiness external-data antes de aprovar uso acelerado.
3. ArgMax: ledger reconciliado, equivalência no escopo testado e memória nos ciclos. Não repetir exportações já válidas.
4. Ablação R/E8/H8/EF: tabela por áudio e estágio, benefício/precisão e decisão de expansão. H4 somente se cumprir a condição acima.
5. CTC CPU/HTP: qualidade, tempo total com e sem carga, memória e disponibilidade. P5 cruzado apenas se necessário à decisão NPU.

Suíte JVM focal antes do aparelho; gates finais `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:assembleDebug` e regras MODULE-MAP/KDoc/hooks aplicáveis. Registrar testes efetivamente executados e APK completo usado. Suíte JVM verde não substitui lint/build nem prova de dispositivo.

Catálogo final deve separar modo de qualidade (referência de precisão, candidato intermediário, compacto, rascunho) de backend (CPU, HTP experimental, GPU indisponível no piloto). Hardware só aparece como opção vantajosa após medição. Não chamar EQ de único não-dominado sem comparar precisão, tempo e memória nos mesmos conjuntos; REF e CTC têm vantagens em eixos diferentes.

Nenhum perfil foi aprovado para produção por este documento. O objetivo da rodada é obter escolhas reais e confiáveis, não preencher rótulos nem reabrir toda a investigação desde o início.
