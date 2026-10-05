# Prompt ao PC auxiliar — decisões V2 e testes PT-BR com 15 gravações

Continue o trabalho Granite 4.1 2B NAR/QNN. Este documento reúne as respostas às cinco perguntas do relatório V2 e incorpora as gravações do usuário. Prevalece sobre instruções anteriores conflitantes de idiomas e aceitação de perfis rápidos. O objetivo é oferecer perfis com diferentes compromissos de velocidade, qualidade e memória, aproveitando CPU/NPU/GPU quando houver benefício medido.

Pasta dos áudios informada pelo usuário: **D:\15 audios**. Esperados: 1.wav até 15.wav, sendo 15.wav uma gravação de aproximadamente 59 segundos. Inspecione a pasta; nomes/extensões e duração precisam ser conferidos. Não declare que arquivos foram recebidos ou validados antes da inspeção. Se essa pasta não existir no PC auxiliar, registre bloqueio somente para os testes pessoais e continue as tarefas independentes.

## 1. Respostas definitivas às cinco perguntas

### Pergunta 1 — CER pode ser critério do caminho int8?

SIM. Usar CER junto com WER, erros relevantes, repetições e estabilidade. Igualdade normalizada é um teste de equivalência e uma medida diagnóstica, não veto universal a um perfil rápido deliberadamente menos preciso. Um ponto final editorial não reprova. “Aprenderender” é erro lexical real, deve ser contado, mas uma única amostra não encerra a avaliação de um perfil inteiro.

Não usar n-gramas como substituto de CER/WER: repetição de morfema dentro da palavra pode escapar da métrica. Preservar saídas brutas e registrar por áudio os casos de repetição artificial, separando-os de repetições realmente faladas.

### Pergunta 2 — Qual é o limite de perda aceitável para NPU/int8?

Limites de CANDIDATURA experimental, exclusivamente PT-BR, em pontos percentuais absolutos:

| Perfil | Aumento de CER | Aumento de WER |
|---|---|---|
| Equilibrado | <=1,0 pp | <=1,0 pp |
| Rápido/leve | <=2,0 pp | <=3,0 pp |

Comparar contra REF float confiável e informar contra EQ CPU U8/int8b. Ao propor substituir EQ por NPU, aplicar também os limites da classe proposta no comparativo contra EQ. Usar os mesmos áudios em cada par e reportar qualidade absoluta. Exemplo: CER 4,0%→5,5% é aumento de 1,5 pp, não 1,5% relativo.

Para justificar perfil Rápido/leve: >=20% de ganho mediano end-to-end sobre EQ em ensaio controlado OU >=30% de redução de RAM pico ou download total necessário. Se só reduzir memória/download, chamar provisoriamente Leve, não Rápido. Ganho isolado do encoder não prova ganho do pipeline.

Esses limites não autorizam release automático. Mostrar erros de números, nomes, negações, saídas vazias e repetições para revisão, mesmo se o agregado passar. Não diluir regressões pessoais graves na média pública. Q4/int4b está autorizado para avaliação com regressão conhecida; não está globalmente reprovado por 2/2 repetições do mesmo áudio. Int2b permanece fora do escopo por colapso já relatado.

### Pergunta 3 — Quais fontes para o holdout?

O usuário só precisa de PT-BR. Suspender novas baterias EN/ES/FR/DE. Usar google/fleurs, configuração pt_br, pelo menos cinquenta áudios independentes dos pools de calibração e ajuste. Auditar IDs/hashes dos pools REAIS de todos os candidatos; não decidir elegibilidade só pela fórmula sorted(glob)[::5][:16]. Preferir split test ainda não usado, registrando revisão, split, IDs, referência, licença e hash. Se faltarem áudios elegíveis, baixar mais PT-BR. Não replicar arquivos para fechar contagem.

Congelar seleção antes de escolher o vencedor. Manter estrato de pelo menos vinte falas completas que caibam em t200/t400 para comparações NPU. CPU também terá avaliação de buckets longos. Não cortar fala arbitrariamente para aumentar o estrato NPU. Registrar diversidade de falantes quando conhecida e não inventar esse número.

As quinze gravações pessoais COMPLEMENTAM o corpus público. São um falante, não quinze falantes. Manter métricas separadas. Não usar essas gravações para calibrar ou quantizar; se futuramente orientarem ajustes, passam a ser regressões conhecidas, não holdout novo.

### Pergunta 4 — Pode instrumentar o erro external data / resolved path vazio?

SIM, implementar agora no caminho debug. Não concluir corrida nem excluir indisponibilidade do arquivo com base na existência conferida depois da falha.

Imediatamente antes de criar sessão ORT, registrar run_id, PID/thread, instância de sessão, modelo absoluto/canônico, diretório pai, criação por caminho ou bytes, variante e identidade do pacote. Para cada external data: location, caminho calculado, existência, tamanho, legibilidade, offsets/length e exceção completa com causas/errno disponíveis. Registrar estado posterior à falha para comparação.

Conferir hashes grandes no preflight, fora das medições temporais; não ler 1,6 GB a cada load quente. Fazer teste de LLM CPU isolado em diretório experimental imutável, sem troca de arquivos/preferências durante a bateria. Auditar sincronização de load/download/troca de variante/restore/release. Não mover/substituir dados enquanto uma sessão os cria ou usa.

Não esconder a falha com retries automáticos ou redownload. Uma tentativa diagnóstica depois da captura é permitida, preservando o erro original. Resolver com evidência e teste correspondente.

### Pergunta 5 — Falhas NPU são custo aceitável do modo rápido?

NÃO. Menor precisão é uma escolha de perfil; travar, não concluir ou perder resultado é falha operacional. Investigar protocolo/processo/sessão antes de culpar o HTP. Dois start podem indicar duplo lançamento ou reinício da Activity; ausência de terminal pode ser timeout, crash ou captura defeituosa.

Manter lock atômico por serial, run_id único, stream exclusivo, validação de backend e descarte de eventos alheios. Todo comando ADB/subprocesso precisa apontar explicitamente para o serial alvo **3B15BD00FVE00000**, após confirmar que continua sendo o aparelho aprovado. Não tocar no PJA110 ou emulador de outra tarefa.

Após a correção, executar pelo menos vinte tentativas planejadas por configuração sobrevivente, incluindo processos novos e sessões quentes, em pelo menos dois áudios/buckets. Gate operacional: zero falha não explicada e zero SSR nessa prova. Não apagar falhas anteriores nem repetir baterias até escolher uma sequência favorável. Vinte sucessos são gate de engenharia, não certificação estatística de confiabilidade.

## 2. Corrigir os denominadores e terminar o trabalho pendente

O V2 lista quatro rodadas inválidas, mas informa três falhas em doze. A cópia de reverificacao.jsonl lida pelo cérebro continha doze linhas, nove rc=0 e três rc=4. Esclarecer se o caso BACKEND incorreto pertence a outra bateria. Gerar ledger completo sem apagar registros.

Cada tentativa precisa de serial, run_id, áudio/hash, APK/hash, variantes/hashes por estágio, PID/instância, backend esperado/observado, ordem real, timestamps, status de execução e qualidade. CPU,NPU,NPU,CPU é ABBA, não ABAB; usar timestamps para reconstruir ordem.

Separar: tentativas; execuções concluídas; capturas válidas; falhas de sessão/protocolo/infraestrutura; áudios únicos; repetições. Sem texto capturado, qualidade=unknown, nunca WER=1 automático nem sucesso presumido. Publicar WER/CER condicional às saídas válidas JUNTO com taxa operacional, sem esconder falhas.

Implementar e provar P2 no APK efetivamente instalado: load não cria t2000; 175 frames→t200; 354→t400; >t400 em NPU estrita rejeitado antes do DSP. Identificar QAIRT real por versão/hashes. Rodar testes focais, suíte JVM, lintDebug e assembleDebug da revisão final conforme AGENTS.md. Falha de recursos é execução inconclusiva/infra-failed, não passed.

P5 captura/replay e P6 EPContext continuam autorizados. “Exige instrumentação” é tarefa a implementar. Em worktree compartilhada suja, usar snapshot/worktree isolada com apenas patch próprio, hooks normais e sem push; não misturar FFmpeg nem descartar mudanças de outra tarefa.

## 3. Receber e preparar as gravações

1. Inspecionar D:\15 audios e ordenar pelo número: 1,2,...15, não ordem lexicográfica. Guardar manifest com caminho, hash, bytes, codec/container reais, canais, sample rate e duração. Extensão renomeada não converte arquivo. Preservar originais.
2. Converter cópias para WAV PCM mono 16 kHz, registrando ferramenta/comando/versão e hashes antes/depois. Não aplicar denoise, aceleração, remoção de silêncio ou correção de voz na primeira rodada.
3. Salvar os textos abaixo como expected_script. Ouvir e preparar verified_reference antes de examinar saídas dos candidatos; registrar divergências reais do roteiro, hesitações, omissões e repetições. O roteiro não é ground truth automático. Se não conseguir conferir a fala, marcar referência não verificada e métricas provisórias; não usar o candidato como árbitro de si mesmo.
4. Guardar voz, transcrições e tensores pessoais somente em disco saudável/local. Não publicar esses dados no R2 sob a autorização de publicar modelos. Podem subir relatórios agregados sanitizados, scripts e artefatos de modelo autorizados.

### Correspondência dos textos esperados

**1.wav:** Uma opção para o ano sabático é viajar e aprender.

**2.wav:** Eu quero aprender, compreender e depois empreender.

**3.wav:** O motorista não confirmou a entrega do pacote.

**4.wav:** O motorista confirmou a entrega do pacote.

**5.wav:** João e Jéssica encontraram Luís em São José do Rio Preto.

**6.wav:** A reunião será no dia quatorze de setembro, às quinze horas e trinta minutos.

**7.wav:** O valor informado foi de mil duzentos e trinta e quatro reais e cinquenta centavos.

**8.wav:** Foram quinze unidades, não cinquenta, e duas caixas ficaram no depósito.

**9.wav:** A placa fictícia é bê, cê, dê, um, éfe, dois, três.

**10.wav:** O endereço fictício é Rua das Acácias, número cento e vinte e sete, bloco bê, apartamento quarenta e dois.

**11.wav:** Eu vi, eu vi o carro parar. Quer dizer, ouvi o barulho e só depois olhei pela janela.

**12.wav:** O pacote pesava um vírgula cinco quilo. A distância era de quinze quilômetros, e o trajeto levou cinquenta minutos.

**13.wav:** Na segunda-feira, João chegou à oficina por volta das oito e meia. Ele disse que o veículo não apresentava falhas no dia anterior. Depois da avaliação, pediu que nenhuma peça fosse substituída sem autorização.

**14.wav:** Durante a conversa, a testemunha afirmou que não viu quem abriu a porta. Ela ouviu dois barulhos, esperou alguns segundos e chamou a vizinha. Mais tarde, esclareceu que o carro era cinza, não preto, e que não conseguiu ler a placa.

**15.wav — duração informada, ainda a medir: 59 segundos:**

Quando os ponteiros do relógio começam a girar mais rápido do que a sua capacidade de processar o ar que entra nos pulmões, cada fração de segundo deixa de ser uma medida abstrata de tempo e passa a ser uma barreira física que você precisa romper a qualquer custo. Você passa os olhos por cada frase sem hesitar, tropeçando nas sílabas mentais enquanto a mente tenta manter o ritmo acelerado das palavras, ignorando vírgulas, atropelando pausas e torcendo para que a compreensão não se perca no meio do turbilhão de informações que passam diante da sua visão como postes vistos da janela de um trem em alta velocidade. Não dá tempo de parar para contemplar metáforas, muito menos para respirar com calma entre uma oração e outra; o objetivo aqui é unicamente vencer o cronômetro, engolindo termos, sintetizando ideias em microssegundos e empurrando a narrativa para frente

## 4. Matriz dos testes pessoais

Primeiro executar EQ, depois N1 apenas nos itens elegíveis. Calcular frames reais com frontend, sem inferir bucket pelo número do arquivo. Manter modelo/input/configuração congelados por par.

| Perfil | Cadeia experimental |
|---|---|
| REF | float confiável CPU, referência de implementação e qualidade |
| EQ | encoder U8 CPU + projector fp16 CPU + editor int8b CPU |
| N1 | mesmo encoder U8 HTP + mesmo projector/editor CPU |
| N2 | encoder/projector U8 HTP mode=2/contexto + editor int8b CPU |
| Q4 | EQ com apenas editor int4b CPU |
| GPU | estágio float GPU aprovado isoladamente + restantes CPU |
| CTC | encoder + decode CTC, sem projector/editor, rascunho experimental |

N2 precisa de controle CPU com projector U8; GPU precisa de controle CPU com o MESMO float. Não atribuir ao backend diferenças de pesos/precisão. Só executar perfis que já passaram estrutura/carga estável. Um perfil ainda não implementado fica pending, não failed-quality.

Para cada áudio, guardar: referência literal e normalizada; saída bruta por perfil; WER/CER com operações de edição; integridade de conteúdo; repetição artificial; frames/T/S; backend real por estágio; status operacional; tempos quando medidos. Manter essa tabela pessoal localmente.

Regras dirigidas:
- 1/2: observar morfemas repetidos e preservação da cauda.
- 3/4: comparar negação; não confundir frases parecidas durante captura.
- 5: acentos/nomes; normalização não remove acentos.
- 6–10/12: conferir valores, horário, endereço e placa, separando formato e conteúdo. “Quinze” e “15” podem ter mesmo valor; reportar equivalência sem substituir silenciosamente WER lexical. Para 9, se leitura fiel, sequência esperada BCD1F23.
- 11: preservar a repetição real “eu vi, eu vi” e a autocorreção; não contar repetição falada como defeito do modelo.
- 13/14: verificar omissões, negações, cores e final; medir bucket antes de tentar NPU.

Normalização: NFC, minúsculas e espaços consolidados; pontuação editorial pode ser ignorada, mas números, acentos, negações e repetições permanecem. Separadores de datas/valores precisam de tratamento explícito. Manter ground truth literal e métrica lexical principal; análise semântica de números é coluna adicional.

## 5. Áudio 15: teste de fala longa, sem truncamento

Medir duração/frames e limite atual do engine. Se exceder single-shot, registrar unsupported-single-shot e usar segmentação existente quando houver. Se não existir, está autorizado um harness experimental de segmentação, sem apresentar isso como recurso pronto do app.

Política inicial: segmentos sem overlap, preferindo pausas, respeitando limites de frames reais. Conservar cobertura integral, com offsets de amostras e registro de cortes dentro da fala quando não houver pausa. Não excluir palavras/silêncios internos para caber. Validar cada segmento pelo frontend antes de chamar ORT; encurtar o limite temporal planejado se o cálculo de frames exigir.

CPU pode usar buckets maiores. NPU continua restrita a t200/t400. Para isolamento de backend, usar os MESMOS segmentos e a mesma recomposição em CPU/NPU. Comparar CPU com segmentos maiores versus NPU menores é comparação de pipelines, a ser rotulada separadamente.

Não deduplicar frases por heurística livre para melhorar o texto. Se testar overlap posteriormente, justificar e preservar fronteiras, saídas e algoritmo determinístico de recomposição. Uma política inicial basta nesta rodada; não abrir pesquisa ilimitada de segmentação.

Avaliar texto recomposto INTEIRO contra referência conferida, inclusive o final “empurrando a narrativa para frente”. Reportar perda/duplicação nas fronteiras, WER/CER integral, latência total incluindo cortes/trocas/recomposição e fator de tempo real=tempo total/duração real. Separar carga fria e quente. Transcrição parcial correta não aprova 15.wav.

## 6. NPU, GPU e novos artefatos: continuidade autorizada

Completar captura/replay do encoder (inputs idênticos, duas saídas CPU/HTP cruzadas, projector/editor CPU fixos, reconstrução consistente de CTC/slots/máscaras). O objetivo é localizar perturbação, não exigir identidade matemática de toda variante rápida.

Gerar contexto mode=2 t400 via SessionOptions ep.context_enable/ep.context_file_path/ep.context_embed_mode, não pelo mapa do provider. Guardar wrappers/binários separados por grafo com hashes e versões. Fechar processo e reabrir wrappers sem regenerar três vezes. Meta inicial <=10 s para restaurar grafos acelerados; timeout de compilação 600 s e restauração 60 s. Primeiro SSR interrompe essa configuração. Contexto funcional e qualidade são gates separados.

GPU: piloto encoder/projector FLOAT t200 isolados, fallback computacional proibido e controle CPU idêntico. Se passar, t400 e pipeline híbrido. Se falhar, capturar nó/fase/erro e permitir uma correção localizada com validação CPU; não varrer flags nem começar por LLM MatMulNBits. Não trocar AAR/QAIRT silenciosamente para fazer passar.

CTC: implementar rota debug que não carrega projector/editor; validar tokenizer/blank contra fonte e medir qualidade integral. É rascunho exploratório. Q4 existente pode ser testado sem nova quantização. Novos encoders U16/U8, projectors e ArgMax estão autorizados quando motivados por evidência, um piloto antes de lote; não executar t2000 no DSP.

## 7. Ordem, aceitação e entrega

1. Inspecionar áudios/pastas e congelar manifests; auditar ledger/serial/lock.
2. Provar P2 no aparelho e instrumentar erro de external data/rodadas sem término.
3. Conferir referências pessoais e preparar holdout público PT-BR, enquanto resolve falhas.
4. Medir EQ/N1 nos itens compatíveis; dados parciais de qualidade podem ser coletados, mas promoção exige estabilidade.
5. Completar replay, contextos, pilotos GPU/Q4/CTC e testar sobreviventes.
6. Executar desempenho em rodada separada da captura de tensores: ABBA/BAAB, mesmo áudio/condições, cinco inferências quentes por sessão, múltiplas sessões. Incluir load/primeiro uso e dispersão; não escolher só o menor tempo.
7. Entregar resultados separados: público PT-BR; pessoal 1–14; pessoal longo 15. Não inflar contagem por repetições ou segmentos do mesmo áudio.

Arquivos finais: audio-inventory.json, verified-references.jsonl, attempts-ledger.jsonl, metrics-public-ptbr.jsonl, metrics-personal-ptbr.jsonl, long-audio-15-report.md, profiles-candidates.json e FINAL-REPORT.md. Use reference_status=unverified se necessário e não invente métricas aprovadas. Ausência de recurso é pending/blocked com motivo; falha medida é failed.

Recomendar poucos perfis não dominados por outros em qualidade/latência/memória/download. Informar se Fiel é realmente melhor em PT-BR, se Rápido é de fato mais rápido e quais backends/buckets estão comprovados. Não mudar defaults do usuário nem publicar produto nesta rodada.

Restaurar estado do aparelho após mudanças temporárias e conferir hashes/preferências. Preservar originais/artefatos/evidências; não acessar E:. Commits próprios com hooks normais, sem push. Publicar apenas modelos/diagnósticos sanitizados autorizados em prefixos experimentais novos. Os textos e áudios pessoais ficam locais.

As cinco decisões estão encerradas: executar o trabalho pendente sem voltar a pedir autorização para CER, holdout PT-BR, instrumentação ou testes pessoais. Pedir esclarecimento somente se houver dado indispensável ausente ou ação fora deste escopo, continuando as fases independentes.
