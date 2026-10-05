# Continuação ao PC auxiliar — perfis de qualidade/velocidade Granite NAR

## Objetivo e precedência

O usuário quer opções comparáveis, em experiência de uso, a tiny/base/small/medium/turbo: poder escolher mais qualidade ou mais velocidade e aproveitar CPU, NPU e GPU. Neste projeto, quantizar o mesmo NAR 2B não cria modelos treinados de tamanhos distintos; use nomes de perfis e divulgue as diferenças medidas.

Este prompt dá continuidade à rodada nar-next-20260914-0950. Leia também docs/prompt-pc-auxiliar-granite-20260914.md, incluindo seção 10. Preserve seus contratos de integridade, rastreabilidade, corpus e isolamento; este documento prevalece nos critérios de qualidade dos perfis rápidos, nas permissões de int4b, na inclusão da GPU e nos pontos de parada. Não repetir fases já comprovadas para exatamente o mesmo código/artefato/configuração.

Está autorizada a implementação da instrumentação faltante, dos reparos de código e das variantes experimentais descritas. “Exige instrumentação” ou “não existe corpus separado” são tarefas a executar, não motivos para encerrar. Pode baixar fontes e dados licenciados, gerar artefatos e publicá-los sob experiments/<id-novo> com hashes e resultados. Não publicar APK, fazer push, sobrescrever produção ou alterar defaults de qualidade do usuário nesta rodada.

## 1. Resposta ao relatório e estado corrigido

1. D1: não restaurar o objeto v2 agora. A indisponibilidade transitória foi reproduzida como recuperada. O parser name versus caminho_relativo é um defeito real corrigido; falta terminar a aceitação de rede. P1 deve constar partial até a matriz automatizada passar. O script de restauração dry-run deixou de ser prioridade e pode constar deferred, pois não há reparo imediato a executar.
2. P0: falta identificar versão e hashes das bibliotecas QAIRT realmente carregadas no aparelho. A versão ORT Python 1.30 não é a versão do Android 1.29 nem prova suporte naquele backend. Complete isso antes de comparar contextos ou compatibilidade.
3. P2: integrar o patch e comprová-lo no APK de teste. Commit bloqueado pelo estado de terceiros não impede preparar uma cópia isolada e testar nela.
4. P3: fixtures e normalização são úteis; três amostras sobrepostas com calibração permitem triagem, mas não generalização. Finalizar os manifests do holdout é trabalho autorizado.
5. P4: terminar as rodadas válidas e preservar histórico invalidado. Sem transcrição capturada, qualidade=unknown. Só afirmar inferência concluída se o evento de término correlacionado comprovar; ausência de captura não prova sucesso nem falha da inferência.
6. O mesmo reprodutor PT passou agora e falhou antes. Isso NÃO prova que a diferença depende apenas do áudio, pois esse áudio aparentemente foi repetido. Tratar causa como unresolved: auditar hashes, input, contexto, flags, build, variantes, concorrência, captura e estado antes de invocar arredondamento do Hexagon.
7. A discrepância resumo Gradle/XML não permite concluir que a falha FFmpeg é inofensiva ou externa. Reexecutar testes em snapshot isolado e guardar uma única execução com timestamps consistentes. Não corrigir FFmpeg dentro desta tarefa.
8. P5/P6 foram autorizados e continuam pendentes. Implementar captura/replay e opções de EPContext. P7 não precisa esperar cache para comparar CPU e híbrido quente; separar ensaio quente do custo de abertura com/sem contexto.

## 2. Perfis candidatos — nomes de laboratório, não promessas

| ID | Cadeia | Objetivo |
|---|---|---|
| REF | pipeline float confiável CPU | referência numérica e baseline ASR |
| EQ | encoder U8 CPU + projector fp16 CPU + LLM int8b CPU | equilibrado e portátil, prioridade |
| N1 | encoder U8 HTP + projector fp16 CPU + LLM int8b CPU | rápido híbrido, isolar encoder |
| N2 | encoder U8 HTP + projector U8 HTP mode=2/contexto + LLM int8b CPU | reduzir projector e custo de preparação recorrente |
| Q4 | mesma cadeia de EQ, só LLM int4b CPU | leve: memória/download versus qualidade |
| G1 | encoder FP16 GPU + projector/LLM iguais ao controle CPU correspondente | piloto GPU por estágio |
| G2 | encoder escolhido e projector FP16 GPU + LLM int8b CPU | projector GPU, apenas se valer a transferência |
| CTC | encoder e decode CTC, sem projector nem LLM editor | rascunho experimental, hipótese de maior ganho |

Use controle CPU com os MESMOS hashes para cada piloto de backend. G1 usando encoder FP16 não é comparável a EQ/U8 como isolamento de backend; são comparações diferentes e ambas podem ser úteis se rotuladas. N2 exige controle com projector U8 CPU. Não rodar todas as combinações de encoder/projector/LLM/flags. Só combinar vencedores individuais depois.

Perfis finais possíveis: Fiel, Equilibrado, Rápido, Leve e Rascunho. Nem todos precisam existir. Fiel é provisório: float não tem qualidade superior garantida sem ground truth. Leve significa tamanho/memória menor, não necessariamente mais rápido. Não prometer economia de bateria sem medição de energia.

O usuário escolhe o perfil; backend é uma decisão separada (Automático, CPU, NPU, GPU), filtrada pela compatibilidade real. Um perfil híbrido é válido e deve identificar os estágios efetivos. Nesta rodada produza especificação e catálogo experimental; não habilite perfis novos em produção.

## 3. Preparar um ambiente que não contamine resultados

### 3.1 Snapshot de código e testes

Leia AGENTS.md/MODULE-MAP da cópia efetiva. Salve git status, HEAD, diffs e ownership. Crie uma worktree isolada com branch codex/granite-perfis-<timestamp> a partir do commit atual, sem tocar nos cinco arquivos FFmpeg de outra tarefa. Exportar/aplicar apenas o patch próprio P2 após verificar se já foi incorporado; não aplicar duas vezes. Incluir o teste novo não rastreado quando ele pertencer ao P2. Preservar o checkout original intacto.

Na cópia isolada, executar testes NAR focais e gates obrigatórios antes/depois das mudanças. Se uma falha já existir na base, documentar com logs dessa base; isso não autoriza dizer que toda suíte passou. Commit próprio nessa worktree com hooks normais, sem bypass/push. Se um hook exigir outros arquivos legítimos da mudança, incluí-los; se exigir misturar trabalho alheio, entregar patch e explicar o impedimento preciso.

### 3.2 Exclusão mútua e captura

Implemente um único orquestrador para o aparelho, com lock por serial adquirido atomicamente. A segunda execução deve recusar início antes de trocar modelo ou iniciar Activity. Liberar lock em finally; lock abandonado exige conferir PID/run_id antes de recuperação. Não usar ps|grep como mecanismo de sincronização.

Cada execução tem diretório e stream exclusivos: <experiment>/<audio>/<config>/<run_id>/. Nenhum arquivo u16-cpu-stream.log compartilhado. Esperar evento terminal associado ao run_id, validar contagem e status dos eventos; não esperar apenas quatro linhas de qualquer execução. Crash, timeout, captura truncada e término normal são estados diferentes. Não copiar logs globais e inferir autoria pelo horário apenas.

Teste o orquestrador sem aparelho com simuladores: dois jobs simultâneos; timeout; crash; evento de outro run; ausência de texto; JSON truncado; reinício após interrupção. Preserve rodadas contaminadas como invalid, excluídas das métricas. Reexecute explicitamente as três rodadas identificadas no relatório e qualquer outra com intervalo de sobreposição comprovado, não apenas os nomes inicialmente anotados.

### 3.3 Inventário do aparelho

Registrar serial, APK/hash, ORT nativo e QAIRT real. Use manifest de instalação, logs de carga e hashes de libQnnHtp/libQnnGpu/skel disponíveis com permissões normais. Não adivinhar no_backup/qai versus qairt nem usar root. Se versão não puder ser lida, guardar hashes e marcar version-unresolved; bloquear publicação de contextos como portáveis. Execute todos os testes de comparação no mesmo runtime; venv-gpu 1.30 fica separado.

Restaurar pacote/preferências após ensaio temporário. Preferir diretório de pacote exclusivo debug ao invés de trocar arquivos de produção. Nenhum pm clear/uninstall nem experimento concorrente no telefone.

## 4. Fechar P1/P2 antes de oferecer aceleração

Terminar testes de rede com MockWebServer e pequena abstração de transporte/armazenamento injetável. Não adicionar Robolectric só para testar algoritmo de download se é possível testar o seam puro. Cobrir a matriz P1 do prompt anterior: HTTP 500/cache, JSON/schema real, hash ausente/incorreto, arquivo existente corrompido, truncamento, Range 206, Range ignorado 200, 416, falha no terceiro arquivo, identidade de versão e ativação preservando pacote anterior.

Incluir fixture sanitizada derivada do manifesto público real com caminho_relativo; manter teste explícito de compatibilidade name e conflito entre ambas as chaves. Conflito de nome não pode selecionar silenciosamente a entrada errada. Indexar por identidade de pacote + caminho relativo, não basename. Concluir inventário dos seis grandes arquivos por hashes de fontes confiáveis/bytes baixados, separando testemunha local de verificação remota. Evitar downloads repetidos: usar cache saudável e registro de origem.

Instalar APK debug de P2 e comprovar: load não cria t2000; áudio 175 frames usa t200; 354 usa t400; >t400 solicitado em NPU experimental estrita falha ANTES da chamada DSP. Testar CPU em todos os buckets instalados. Relatar separadamente inicializado, sessão criada e inferência concluída. Backend informado por estágio precisa refletir execução e fallback. O LLM int8b no CPU é escolha explícita, não NPU completa.

## 5. Qualidade por perfil — nova regra substitui reprovação indiscriminada

### 5.1 Separar correção de implementação e perda de modelo

Transformação que promete equivalência (parser, contrato, cache, ArgMax, replay) deve preservar sua referência. Uma diferença lexical aí exige investigação.

Troca int8→int4 ou retirada do editor é mudança deliberada de qualidade: igualdade de texto NÃO é seu gate de produto. Medir WER/CER, repetições, erros relevantes e ganho real. “aprenderender” conta como erro e como repetição artificial; um caso não desqualifica automaticamente toda variante de perfil rápido. Loop, colapso em saídas vazias ou repetição disseminada continuam bloqueios de funcionamento, mesmo em modo rápido.

### 5.2 Dados e limites provisórios para candidatura

Finalize triagem de oito falas + silêncio. Baixe/selecione holdout de pelo menos 50 áudios únicos, dez por idioma PT/EN/ES/FR/DE, excluindo TODOS os itens usados para calibrar/ajustar cada candidato. Os 82 existentes podem conter holdout utilizável, mas só após confrontar IDs/hashes com pools efetivos; o código sorted(glob)[::5][:16] precisa ser auditado nos arquivos reais, não inferido apenas pela fórmula. Fixar seed, split, versão de dataset, referências e manifest antes da seleção de vencedor.

Regressões conhecidas, reprodutor e amostras usadas no replay formam conjunto dirigido separado; não contá-las como holdout fresco. Para NPU, começar pelo estrato t200/t400. Se não houver dez amostras por idioma nesse estrato, buscar mais dados ou declarar cobertura insuficiente. Não cortar fala arbitrariamente para satisfazer formato. CPU mantém avaliação adicional dos buckets longos.

Critérios a priori de shortlist, sujeitos a revisão do cérebro antes de release:

| Classe | Delta WER global vs REF | Delta WER por idioma | Delta CER global | Contrapartida mínima |
|---|---|---|---|---|
| Equilibrado/fiel | <=1,0 ponto percentual | <=1,5 pp | <=1,0 pp | benefício comprovado ou referência |
| Rápido/leve experimental | <=3,0 pp | <=5,0 pp | <=2,0 pp | >=20% mais rápido que EQ, OU >=30% menos RAM pico ou download necessário |
| Rascunho CTC exploratório | reportar, sem limite de release aprovado | reportar | reportar | alvo >=2× sobre EQ end-to-end |

Esses limites são decisões de engenharia para selecionar candidatos, não alegações de aceitabilidade universal. Relatar qualidade absoluta e deltas versus REF e EQ; não esconder que REF também erra. Usar a MESMA lista de áudios em cada comparação. Se conjunto for pequeno ou intervalo pareado inconclusivo, estado candidate-provisional.

Reportar: micro WER/CER, macro por áudio, idiomas, duração, distribuição de erro, contagem de falas vazias, repetições artificiais novas, erros em nomes/números/negações e silêncio com texto. Na triagem, qualquer colapso sistêmico pausa a variante; no holdout, qualquer caso grave exige revisão indicada no relatório, sem aprovação automática pelo WER agregado. Um perfil rápido pode ter mais erros lexicais, mas isso deve ser explícito e quantificado. Não normalizar/remover os erros que se deseja medir.

Não criar modo Leve apenas porque o arquivo é menor: medir download do PACOTE completo e RAM total, contando recursos compartilhados. 841 MB do LLM não significa reduzir pela metade o aplicativo inteiro. Não reabrir int2b que já destrói texto; Q4 existente está autorizado para avaliação, sem nova quantização inicialmente.

## 6. Resolver a aparente contradição e implementar P5

Antes da captura grande, construir comparison-ledger.json entre madrugada e rodada atual: hashes dos WAVs, frames, modelos e external data por estágio; variante; APK; opções; máscaras; decode; estado DSP; instrumentação/logs. “Mesmo nome” não prova mesmo arquivo.

Implementar captura debug de input_features, encoder_bpe_logits e multilayer_features com shape/dtype/endianness/hash. Gravar antes de fechar buffers ORT, sem expor tensores em logs. Reexecutar o reprodutor com EQ/N1 em ordem balanceada e lock exclusivo. Se não reproduzir, registrar not-reproduced-under-current-config; não afirmar que nunca existiu nem que foi causado apenas pelo áudio.

Executar matriz CPU/HTP cruzada das duas saídas, mantendo projector/LLM CPU fixos e reconstruindo CTC/slots/posições/máscaras. Mesmo sem reproduzir repetição, capturar margens e diferenças das saídas para referência futura. Limitar inicialmente a um reprodutor e um controle. Não transformar essa análise em busca ilimitada por um erro raro.

Caso N1 já passe qualidade e desempenho, não exigir localizar matematicamente cada arredondamento para prosseguir como candidato. Ainda preservar a regressão e a incerteza. Falhas de captura/concor­rência precisam ser corrigidas independentemente de qualidade aparente.

## 7. NPU: completar contextos e explorar precisão com evidência

Implementar P6 agora: suporte às SessionOptions ep.context_enable, ep.context_file_path, ep.context_embed_mode; caminhos separados por grafo; contexto mode=2 t400 com offload_graph_io_quantization=1 conforme o caso que passou. Não inserir SessionOptions no mapa do provider. Versionar por modelo/options/ORT/QAIRT/SoC.

Salvar wrappers e binários, fechar processo e reabrir explicitamente os wrappers três vezes. Confirmar ausência de recompilação e medir load, primeira inferência, quente, memória e texto. Timeout de compilação 600 s para o conjunto; restauração 60 s por tentativa, meta inicial <=10 s nos grafos acelerados. No primeiro SSR parar essa configuração e exigir controle de recuperação antes de outros ensaios.

Cache de modelo com erros pode ser funcional: separar context_functional e profile_quality. Testar comparação quente EQ/N1 sem aguardar o cache. EQ/N2 inclui custo de projector diferente: ter controle CPU U8 correspondente.

Se qualidade do encoder precisar melhorar, gerar/reutilizar U16/U8 e depois, apenas se localizado, overrides seletivos. Se memória/transferência dominar, fazer piloto ArgMax com saída de IDs e validar tie-breaking/frames no mesmo backend. Não testar t2000 no DSP nesta rodada. Ganho t400 não autoriza extrapolar memória ou qualidade para t2000.

## 8. GPU: investigação nova, pequena e concreta

Não repetir o erro de rotular uma sessão GPU com execução CPU. A documentação do QNN GPU admite FP16/FP32 e formatos específicos de pesos quantizados, mas isso não prova cobertura deste grafo nem da versão embarcada. Fonte: https://onnxruntime.ai/docs/execution-providers/QNN-ExecutionProvider.html#running-a-model-with-qnn-eps-gpu-backend . Confira a biblioteca instalada e use execução/profiling real; Python em PC e emulador não aprovam Adreno.

Ordem autorizada:
1. Selecionar os menores encoder e projector FLOAT estáticos fiéis já existentes (t200). Fazer sessões isoladas, um estágio por processo, GPU com fallback computacional proibido. Controle CPU do mesmo ONNX e inputs gravados. Não começar com o LLM MatMulNBits nem converter U8 para FP16 como se recuperasse pesos originais.
2. Capturar erro completo, nós rejeitados e fase (validação/finalização/execução). Um 6020/6022 genérico não comprova limitação por tamanho sozinho. No máximo um reteste após uma correção estrutural localizada e validada em CPU (por exemplo, operador não suportado identificado). Sem varrer opções aleatoriamente.
3. Se estágio isolado passar, t400 e pipeline híbrido com os demais estágios CPU; medir cópias/transferências e load. NPU+GPU simultâneos só depois de cada combinação com CPU passar e com ganho plausível; não carregar todos os grafos para experimentar coexistência sem orçamento de memória.
4. Se encoder GPU falhar e projector GPU passar, guardar esse resultado, mas só reter o perfil se o total vencer EQ. Projector mais rápido em 300 ms pode perder pela preparação/transferência.
5. LLM FP16 GPU S64: opcional após um estágio GPU real funcionar e após inventário indicar recursos suficientes; usar export fiel com máscara e controle CPU. Uma tentativa por configuração, com orçamento/timeouts explícitos e sem trocar o runtime Android silenciosamente. Se esse export não existir, gerar apenas piloto, não todos os S. QNN aceita o formato float em princípio; suporte do editor inteiro permanece hipótese.

Se nenhum piloto GPU passar, fechar GPU como blocked-on-current-runtime com casos mínimos/logs, não impossibilidade geral. Upgrade do runtime/AAR/pacote nativo é proposta de próxima fase; não trocar nessa rodada e confundir comparação.

## 9. Rascunho CTC: hipótese de maior mudança de velocidade

O NAR já produz CTC do encoder antes do projector/editor. Está autorizada uma rota debug que decodifica essa saída e termina, evitando carregar projector e LLM. Isso é saída intermediária do modelo, ainda sem validação como ASR independente.

Primeiro identificar exatamente vocab/tokenizer/blank do encoder na revisão fonte. Não assumir que o decoder textual do editor serve diretamente. Confirmar decode com a implementação de referência e tokens capturados. Sem regex para consertar repetições.

Testar CTC CPU e CTC HTP t200/t400 no mesmo encoder U8. Garantir que load da rota CTC não cria projector/LLM nem mapeia embeddings do editor; registrar isso. Para a rota debug, validar apenas arquivos realmente necessários, preservando o contrato do perfil completo.

Avaliar oito áudios e silêncio. Se saída for incoerente sistematicamente, parar e publicar diagnóstico. Se inteligível, medir no holdout, comparando contra ground truth e EQ, com tempos de frontend+encoder+decode e memória total. Não chamar perfil aprovado nem “turbo” antes dos resultados.

Se passar como candidato de pesquisa, pode suportar no futuro “rascunho imediato, refinar depois”. Nesta rodada somente demonstrar as duas saídas e seus tempos, sem substituir texto final silenciosamente. Refinamento deve reutilizar o áudio original ou features com contrato correto, não alimentar texto CTC em um modelo esperando áudio.

## 10. Escolher poucos perfis úteis e entregar trabalho completo

Medir no mesmo holdout/estrato e condições térmicas, ABBA/BAAB com processos exclusivos. Usar cinco inferências quentes por sessão e múltiplas sessões; carga/primeiro uso separados. Identificar frames e buckets em cada linha. Não usar tempos de qualidade com coleta de tensores para anunciar velocidade.

Construir tabela Pareto: qualidade absoluta, delta versus EQ, latência fria/quente, pico de RAM observado, download inicial e incremental, hardware compatível, limites de duração/bucket. Descartar configuração dominada (pior qualidade e custo sem outra vantagem demonstrada). Se diferença for incerta, preservar como inconclusive; não selecionar por uma medição mínima.

Entregar catálogo experimental de perfis com IDs estáveis e nomes provisórios; backend permitido por estágio, hashes, buckets, fallback explícito, incompatibilidades e versão da evidência. Nomes e estimativas da UI não podem usar benchmarks x86 como promessa Android. Perfil não altera idioma/duração suportados silenciosamente.

Saída final obrigatória:
- status corrigido de P0–P9; nenhum passed enquanto faltar critério obrigatório;
- testes P1/P2 e APK debug efetivamente usado no telefone;
- ledger da divergência antiga, captura/replay implementados e resultado;
- contexto mode=2 gerado/reaberto ou falha concreta reproduzível;
- resultados CPU/NPU e pilotos GPU por estágio;
- Q4 e CTC avaliados ou motivo técnico real de interrupção;
- holdout, métricas por amostra, limites e recomendações de 2–4 perfis úteis;
- commits/patchs próprios, testes completos, hashes, comandos reais e aparelho restaurado.

Ordem de execução: isolamento/lock → testes P1/P2 → terminar triagem válida + preparar holdout → captura/replay → contextos NPU → GPU piloto → Q4/CTC → avaliação ampliada dos sobreviventes → catálogo/relatório. Preparar holdout pode avançar enquanto não há aparelho; não executar dois ensaios simultâneos nele. Sem aparelho, implementar instrumentos e testes offline, deixando fase hardware explicitamente pendente.

Se o commit estiver bloqueado, não encerrar pesquisa por isso: usar worktree limpa conforme seção 3. Se instrumentação ou corpus faltar, implementá-los. Se hardware, acesso ou capacidade física impedir execução, registrar evidência e continuar partes independentes. Não encerrar em outro relatório que apenas repita “P5/P6 exigem instrumentação” sem tentativa de implementá-la.

Os perfis rápidos têm agora permissão para perder alguma precisão medida. Isso não elimina gates de integridade, estabilidade, controle de execução nem transparência dos resultados. A decisão de release continua posterior à revisão das evidências pelo usuário/cérebro.
