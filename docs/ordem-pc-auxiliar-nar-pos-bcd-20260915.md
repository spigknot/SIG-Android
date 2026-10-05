# Continuação após B/C/D — corrigir conclusões e medir opções reais

Execute esta ordem como complemento dos planos anteriores, sem reiniciar tudo. Objetivo: oferecer opções PT-BR com diferenças demonstradas de precisão, tempo e memória. Não precisamos preencher cinco nomes. Nesta rodada, priorize: corrigir o gate de qualidade, fazer a leitura REAL do contexto N2 e reduzir a transferência de logits para Java. Depois medir CTC e sobreviventes. Este documento não aprova nenhum perfil para produção.

## 0. Limites e preparação

- Trabalhe na worktree própria; preserve alterações de terceiros. Leia o AGENTS.md aplicável. Não desabilite hooks, não faça push, não publique release e não altere preferências de produção permanentemente.
- Não acesse E:. Use o lab reconstruído em D:. Não apague evidências anteriores nem regenere em lote artefatos que já existem.
- Áudios, transcrições e tensores pessoais ficam locais. Não enviá-los ao R2. Artefatos experimentais sem dados pessoais podem ir para prefixo novo, com hashes e manifesto; nunca sobrescrever produção.
- Um único controlador de ADB por vez. Se houver outra tarefa usando o telefone, não force-stop, reinstale ou troque pacote concorrente. Combine exclusividade antes. Sem telefone, avance com regras, testes, exportação piloto e reavaliação das saídas existentes; testes de hardware ficam explicitamente pendentes.
- Identifique HEAD, diff próprio, APK por SHA completo, ORT, QAIRT efetivamente carregado, SoC, opções por estágio e hashes dos modelos/external data. SHA abreviado em prosa não substitui identidade completa no ledger.
- Fixe um run_id e diretório exclusivos por execução. Não reutilize logs globais entre ensaios. Modelos são imutáveis enquanto houver sessões abertas. Uma troca invalida sessões; feche-as antes.

## 1. Correção obrigatória: os deltas contra REF estão invertidos

O cérebro leu `quality/metrics-holdout-ptbr-normv2.jsonl` e `quality/metrics-ref-float.jsonl`, em `D:\SIG-granite-nar-lab-rebuild\nar-next-20260914-0950`. Nos 24 itens REF encontrou uma linha válida por perfil/item, referências literais, versões e denominadores iguais nas 48 comparações:

| Perfil | Erros de palavras / referência | WER micro | Erros de caracteres / referência | CER micro |
|---|---:|---:|---:|---:|
| REF | 26 / 255 | 10,1961% | 64 / 1242 | 5,1530% |
| EQ | 40 / 255 | 15,6863% | 84 / 1242 | 6,7633% |
| N1 | 35 / 255 | 13,7255% | 83 / 1242 | 6,6828% |

Convenção obrigatória: delta = candidato MENOS referência; positivo significa MAIS erro.

- EQ − REF: **+5,4902 pp WER; +1,6103 pp CER**.
- N1 − REF: **+3,5294 pp WER; +1,5298 pp CER**.
- N1 − EQ: −1,9608 pp WER; −0,0805 pp CER.

Esses números refutam o sinal e o veredito publicados em `gate-vs-ref.json`. A conferência acima é aritmética e de referências, NÃO substitui a auditoria de hashes dos áudios/builds. Não repetir inferências só para corrigir o cálculo.

Tarefas:

1. Preserve o relatório antigo. Localize o código/comando que produziu o JSON e a inversão. Se foi escrito manualmente, declare isso e substitua a geração por script reproduzível.
2. Gere tabela por item, erros absolutos, denominadores, interseção tripla REF/EQ/N1, hashes de áudio/referência normalizada, normalizador e run_ids. Recuse hashes ausentes/ambíguos no gate; mantenha esses dados como descritivos.
3. Use o mesmo conjunto triplo para a identidade delta(N1,REF) = delta(N1,EQ) + delta(EQ,REF), com tolerância de arredondamento explicitada. Pares sobre conjuntos diferentes devem aparecer separados.
4. Acrescente testes: candidato pior dá delta positivo; melhor dá negativo; igual dá zero; a identidade tripla fecha; conjunto divergente não é apresentado como triplo; ordem das linhas não muda o resultado. Gere o veredito dos valores sem inserção manual.
5. Sob os limites anteriores, EQ e N1 NÃO passam o gate Equilibrado de +1 pp contra REF. N1 também excede o limite de WER de +3 pp da classe rápida/leve neste conjunto. Não relaxe esses limites retroativamente. Preserve os candidatos para pesquisa; não os declare aprovados.
6. A ausência da sonda prova diferença de APK, não que toda a diferença seja somente log. Compare commits/fontes/artefatos dos builds. Se o impacto não puder ser isolado, marque evidência histórica de atribuição incompleta. Refaça apenas a bateria necessária à decisão com um único APK identificado.
7. Reconcilie os números 40 tentadas, 39 válidas únicas e 48 linhas: tabela de arquivos originais, segmentos, configurações elegíveis, tentativas, sucessos únicos, retries, recusas e capturas inválidas. Denominador de disponibilidade não é denominador de qualidade.

Entregável: relatório corrigido gerado pelo avaliador, comando exato, entradas por hash, testes e conclusão. Isso encerra esta correção do instrumento; não abra nova pesquisa de normalização.

## 2. N2: corrigir o caminho de leitura, não condenar o cache

Na fonte inspecionada em `D:\SIG-perfis-20260914-123811\app\src\main\java\br\gov\sp\pcsp\launcher\GraniteNarEngine.kt`, aproximadamente linhas 1037–1044, o ramo `debugEpContext` sempre define `ep.context_enable=1` e a sessão sempre recebe `modelo.absolutePath`, o grafo original. Portanto a segunda execução tenta GERAR novamente no mesmo destino. A mensagem `Failed to generate EP context model since the file exists` não demonstra falha ao carregar um contexto: esse caminho de carregamento ainda não foi exercitado.

A documentação oficial separa geração de inferência: [EP Context Design](https://onnxruntime.ai/docs/execution-providers/EP-Context-Design.html) e [QNN EP](https://onnxruntime.ai/docs/execution-providers/QNN-ExecutionProvider.html). Confira o contrato da versão ORT 1.29 instalada; não atualize dependências nesta rodada.

Implemente uma decisão pura, testável, com três estados explícitos:

- OFF: abrir o grafo original sem geração.
- GENERATE: abrir o grafo original com `ep.context_enable=1`, destino novo e `ep.context_embed_mode=0`. Recusar destino já ocupado; não apagar o cache antigo para fingir reabertura.
- LOAD: abrir o arquivo ONNX GERADO que contém EPContext, por caminho absoluto, com geração desabilitada (`ep.context_enable=0` ou omitida). Não voltar ao original neste modo. Para esta prova, use carga por arquivo, não por buffer em memória.

Mantenha wrapper e binário externo no arranjo referenciado pelo próprio wrapper. Não renomeie o binário sem atualizar suas referências. Valide existência, integridade, compatibilidade e identidade antes de LOAD. Cache deve ser identificado por grafo/external data, bucket, precisão, versões ORT/QAIRT, SoC/arquitetura HTP e opções relevantes. Cache incompatível gera diagnóstico explícito, não fallback escondido.

Escopo: encoder HTP t400, projector e editor CPU. Não aplicar a flag indistintamente a todo backend acelerado. O rótulo N2 não deve ser confundido com algum valor numérico universal chamado `mode=2`.

Prova mínima:

1. Testes da decisão: OFF; geração em destino novo; colisão; LOAD usa wrapper e não habilita geração; binário ausente; identidade incompatível; LLM CPU excluído.
2. Reutilizar o cache existente se íntegro e compatível. Se não, gerar uma única nova versão, preservando a anterior. Registrar hashes/tamanhos do wrapper/binário e opções.
3. Fazer três LOADs em processos novos, registrando caminho efetivo, tempo de criação, primeira inferência, texto e partições/backend. Verificar hashes/mtime antes/depois e logs de carga. Não chamar cache do sistema de arquivos de “frio” só porque o processo é novo.
4. Comparar texto/tokens ao N1 sem cache, mesmo grafo/áudio/configuração. CPU continua controle de qualidade, mas o primeiro teste do cache é N2 contra N1.
5. Só depois medir sessões quentes. Separar custo de geração, reabertura, primeira inferência e inferências seguintes.

Um binário de 980 MB tem custo de armazenamento e potencial custo de memória; seu tamanho NÃO prova que a carga ultrapassará 10 segundos. Meça. Cache também não promete melhorar inferência quente nem corrigir qualidade. Se a leitura real falhar, entregue erro novo, identidade e logs; não entre em varredura de flags.

## 3. Memória: retirar a cópia enorme de logits do caminho Java

A guarda de memória foi uma contenção útil, não a solução de eficiência. No arquivo atual já há `projOut.close()` após a leitura de audioEmbeds, embora o relatório diga que estava pendente. Confira o diff e o APK correspondente antes de repetir essa alteração. Revise a liberação também em exceções: `encOut`, `projOut`, `llmOut` e tensores de entrada precisam de ownership explícito, `use`/`finally`, sem fechar um tensor enquanto seu consumidor ainda precisa dele.

Piloto autorizado: derivar UM editor int8b já existente para devolver IDs ArgMax em vez de materializar todos os logits no Java. Não requantizar pesos nesta etapa.

1. Inspecione o contrato original de saída, dtype, eixo do vocabulário, offsets textuais e regra atual de empate. Documente-os antes de transformar.
2. Acrescente ArgMax no último eixo, sem manter essa dimensão, preservando os logits originais como entrada do ArgMax, sem casts/Softmax/alteração de pesos. Confirme opset e suporte do runtime. Use int64 inicialmente no CPU. Preserve a mesma escolha em empate do código existente; não assuma sem testar.
3. A saída de produção do derivado deve conter somente os IDs necessários ao consumidor, não logits e IDs simultaneamente. Primeiro mantenha todas as posições de sequência e aplique o mesmo recorte textual no consumidor; não combine com uma otimização da cabeça do modelo ou remoção de posições nesta etapa.
4. O decoder recebe IDs, mantém exatamente validAudio/textFrames, blank, collapse de repetições, ordem das operações e tokenização. Não introduzir correção textual por regex.
5. Faça paridade de IDs antes de comparar texto: grafos original e derivado com entradas idênticas, pesos idênticos, mesmo runtime/backend. Inclua empates sintéticos, blanks separando repetições, sequência curta e limite de bucket. Entrada não finita deve ser detectada e reportada, não usada para alegar paridade.
6. Piloto real curto: 3 áudios PT-BR, incluindo negação e repetição real. Exigir igualdade de IDs válidos e texto; em caso de falha, parar a expansão e localizar a diferença.
7. Depois tentar o caso longo que disparou a guarda, primeiro CPU em processo novo, com memória monitorada. Não retirar a guarda do caminho original nem presumir que ArgMax elimina a alocação NATIVA dos logits. O benefício esperado é reduzir a saída/cópia Java; medir heap, PSS e pico, além de latência.
8. Só se passar, derivar UM encoder CPU com IDs CTC no lugar de bpeLogits, preservando as outras saídas necessárias ao projector. Repetir paridade e medir. Encoder HTP com ArgMax é uma experiência posterior: não alterar simultaneamente grafo, cache e backend.

Não exportar todas as variantes/tamanhos antes do piloto. Novo manifesto deve distinguir contrato `logits` de contrato `token_ids`; o loader deve recusar combinações erradas ANTES de inferência. Aproveite para testar a mistura FP16/U8 que já produziu lixo silencioso. Os derivativos permanecem experimentais.

## 4. Perfis: medir qualidade e benefício separadamente

Catálogo de pesquisa, não nomes já aprovados na UI:

- REF: referência de maior precisão observada neste conjunto, sem promessa de perfeição. Float não é sinônimo universal de qualidade superior.
- EQ: candidato quantizado CPU; nome Equilibrado depende de gate corrigido. Não esconder +5,49 pp de WER atrás da média de CER.
- CTC: candidato Rascunho, sem editor. Prioridade para produzir uma opção realmente mais rápida, com perda de qualidade quantificada.
- Q4: candidato Compacto. O resultado de −27,2% no download não atingiu a meta anterior de −30%, mas não deve ser descartado por três pontos percentuais de diferença. Decisão nova: manter como candidato a economia de espaço; isso NÃO muda seu gate de qualidade nem o transforma em aprovado. Registrar bytes do pacote inteiro, instalação, RAM e falhas, não só tamanho do editor.
- N1/N2: acelerador experimental. Sem ganho end-to-end demonstrado, não chamar de Rápido. N1 levou 66,3 s contra 52,6 s de EQ no áudio longo, cerca de 26% mais tempo nesta execução; não generalizar um ensaio isolado para todo aparelho/áudio.
- GPU: piloto do encoder bloqueado com 6022; não uma impossibilidade de GPU em geral. Ativar verbose foi instrumentação, não correção de grafo.

Próxima bateria de qualidade:

1. Congele lista e configurações antes de executar. Reutilize saídas válidas de identidade comprovada. Use os 24 itens PT-BR pareados já disponíveis para comparar CTC ao EQ/REF; declare a amostra pequena e se algum item deixou de ser holdout por orientar ajustes.
2. Complete CTC CPU nos pessoais 1–14 e no 15 segmentado, mesmos cortes da comparação EQ. Não use dados pessoais para calibrar ou ajustar pesos.
3. Teste CTC HTP somente t200/t400 depois de estabilidade de N1/N2; primeiro 3 áudios, depois expanda aos elegíveis se houver benefício e operação correta. Sem editor, o balanço CPU/NPU pode mudar: medir, não presumir.
4. Q4: só ampliar aos 24 se o custo couber após as prioridades acima. Os 6 testes existentes são triagem, não aprovação nem reprovação global. Não gerar outra quantização para contornar a ausência dessa avaliação.
5. Marque referências pessoais como unverified até conferência por escuta. Entregue ao usuário apenas trechos divergentes com timestamps e texto de cada perfil. Não confundir “apreender” (palavra válida, possivelmente substituição) e “apender” (possível exclusão) com repetição morfológica confirmada. “aprenderender” é um candidato diferente à revisão.
6. Preserve WER/CER literais norm-v2. Uma coluna auxiliar pode explicar número por extenso versus dígitos, mas nunca mascarar perda de valor, negação, placa, nome ou repetição verdadeira.
7. No áudio 15, garantir cobertura de amostras e cauda. Não apagar “nos nos” por deduplicação global. Correção das junções exige alinhamento/cortes definidos, e será outro experimento, não mudança silenciosa desta bateria.

Se os derivados ArgMax forem exatamente equivalentes em tokens, não é preciso refazer todo o estudo de qualidade apenas pela troca de formato de saída. Faça regressão representativa e documente a prova de equivalência e seus limites.

## 5. Desempenho controlado e piloto GPU limitado

Após estabilidade, selecionar de antemão 4 áudios curtos (2 por bucket) e o longo segmentado. Para cada comparação A/B, executar blocos ABBA e BAAB sequenciais, registrando tentativa e estado térmico; para perfis quentes, manter sessão reutilizada e separar aquecimento das medições. Não misturar totais com/sem carga. Reportar cada amostra, mediana, amplitude e RTF; esse piloto pequeno não sustenta percentis de cauda robustos.

Comparações prioritárias: EQ original × EQ ArgMax; EQ × CTC CPU; N1 × N2 na carga; CTC CPU × CTC HTP se elegível. Q4 só reivindica velocidade se o total realmente melhorar. Identificar encode, projector, editor, cópias/decodificação e overhead fora dos estágios. Medir energia somente se houver instrumento apropriado; bateria em graus não é potência nem temperatura exata do SoC.

Repetir abertura/inferência/fechamento em 10 ciclos curtos por candidato acelerado sobrevivente, guardando primeira tentativa e retries. Se o erro external-data voltar, não aprovar operação apenas porque o retry passou. Investigar o caminho efetivo no instante da falha; não repetir downloads por palpite.

GPU é secundário nesta rodada: conferir se o piloto anterior executou encoder e projector separadamente. Se projector float t200 isolado ainda não foi testado, realizar um piloto com mesmo hash e controle CPU. Se funcionar, medir contribuição no pipeline misto. Se falhar, guardar o primeiro erro estruturado e parar. Não atribuir a fusões nas camadas 6–7 uma causa raiz sem grafo/nó reproduzível; nenhuma varredura de versões ou flags.

## 6. Aceitação e entrega

Executar testes unitários focais antes do aparelho, depois os gates Android da revisão final: `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:assembleDebug`, mais contratos de MODULE-MAP/KDoc e hooks aplicáveis. Falhas de terceiros devem ser registradas, não absorvidas em commit próprio. Após alterações no APK, provar o SHA efetivamente instalado.

Entregar nesta ordem:

1. Errata de qualidade com sinais corretos e gates recalculados; não aguardar o fim dos pilotos para retirar a aprovação incorreta.
2. N2: caminho efetivamente aberto, opções, três reaberturas, tempos e resultado, ou nova falha demonstrada na leitura real.
3. Memória/ArgMax: contratos, scripts reexecutáveis, hashes, paridade, picos e áudio longo completo ou recusa explícita.
4. Matriz dos perfis: configuração por estágio, áudio/set, qualidade, disponibilidade, carga, tempo quente, RAM, bytes e limitações. Aprovação de qualidade e benefício são colunas separadas.
5. Commits/patches próprios, comandos, testes, APK/hash, ledgers e um próximo comando inequívoco para cada pendência.

Não promover pacote ou UI nesta rodada. Não chamar EQ de aprovado, N1 de rápido, cache de inviável ou GPU de impossível sem as provas correspondentes. A entrega pode ser incremental: correção dos números e leitura real do cache já são marcos úteis, mesmo que a expansão de CTC/ArgMax ainda esteja em curso.
