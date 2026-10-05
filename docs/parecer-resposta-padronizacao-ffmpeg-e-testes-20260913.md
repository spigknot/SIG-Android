# Parecer sobre a resposta à proposta de padronização — decisões revisadas e roteiro de testes

> **Direção atualizada em 14/09/2026:** o [plano de decisões e ação](/D:/Projetos/SIG/docs/plano-decisao-final-midia-windows-android-20260914.md) incorpora o documento consolidado mais recente do desenvolvimento e prevalece nas prioridades. Ele encerra as dúvidas de contagem/quarto bipe e trata o crescimento temporal de áudio informado em T07 como problema a corrigir, com áudio contínuo como arquitetura-alvo. Esta revisão 2 permanece como histórico da Rodada A.

**Data:** 13/09/2026  
**Revisão 2:** incorpora a Rodada A enviada pelo desenvolvimento (T01, T02, T03, T04, T05, T06 e T08).  
**Projetos:** SIG Android e SIG Windows  
**Finalidade:** orientar a próxima avaliação técnica e as primeiras correções, sem transformar toda a proposta anterior em obrigação de implementação imediata.  
**Entrega:** atualização documental. Os testes foram executados pelo desenvolvimento e seus resultados foram fornecidos ao assistente. Nesta revisão, conferi o relatório e os trechos de código pertinentes; não executei novamente a bateria nem alterei os aplicativos.  
**Prioridade atual do usuário:** testar nos dois aplicativos; explicitar mudanças por plataforma, mantendo sua implementação para uma decisão posterior aos resultados.

**Leitura prioritária desta revisão:** seções 1–4 (conclusões atualizadas), 12 (próxima rodada), 16 (mudanças por aplicativo) e 18 (análise das evidências e complementos necessários). As descrições T01–T20 continuam como procedimentos reutilizáveis; os testes já realizados passam a servir de regressão.

## 1. Minha avaliação honesta

**A Rodada A confirmou boa parte dos riscos e resolveu duas divergências importantes do debate: a mudança para AAC corrige o grande prelúdio do corte preciso no experimento, e o SmartCut preserva integralmente o miolo do caso avaliado.** A recomendação de começar por correções pequenas ganha sustentação.

Eu retiro a classificação de “mera hipótese” para a causa principal do excesso no corte preciso Android e para a perda de faixas no seu remux: ambas foram reproduzidas segundo o relatório recebido e são coerentes com o código reconferido. A variante AAC é uma correção candidata validada experimentalmente para esse cenário; ainda não é uma correção integrada e aprovada no aplicativo.

Também passo a tratar como defeitos reproduzidos a imposição do perfil da primeira faixa às demais no Windows, a divergência espacial do crop e a diferença de efeito sob o nome “Linear” no Smart Insert Windows. A afirmação de que dimensões pares tornam o crop correto foi refutada pelo teste da coordenada ímpar. A distinção entre áudio contínuo e áudio codificado por trechos permanece válida.

**Minha recomendação atual: corrigir política de áudio e remux no Android; corrigir perfis multifaixa e semântica do Smart Insert no Windows; corrigir o contrato espacial do crop nos dois. Manter SmartCut como padrão experimental, com aprovação de conteúdo restrita à classe testada.** Não há motivo demonstrado para reescrever seu núcleo ou implantar áudio contínuo imediatamente.

Este parecer complementa o [relatório técnico anterior](/D:/Projetos/SIG/docs/decisao-padronizacao-ffmpeg-android-windows-20260913.md) e incorpora a [Rodada A recebida](/D:/Projetos/SIG/docs/rodada-a-resultados-20260913.md). Em caso de conflito de diagnóstico/prioridade, prevalece esta revisão. As evidências brutas foram informadas como guardadas pelo desenvolvimento, mas seus arquivos não foram reanalisados nesta revisão.

## 2. O que eu manteria, revisaria ou rejeitaria na resposta

| Afirmação recebida | Minha posição | Consequência prática |
|---|---|---|
| O programa completo é grande demais para começar | Concordo com reduzir a primeira entrega | Não exigir esquema compartilhado ou corpus amplo antes de corrigir |
| Tudo isso vira meses | Não há levantamento suficiente para estimar | Definir pequenas entregas, sem prazo inventado |
| Áudio no corte preciso e remux devem vir primeiro | Concordo, acompanhados de referência e testes | Reproduzir e corrigir sem refatoração geral |
| AAC explícito resolve os 3,62 s | Confirmado no experimento quanto ao grande prelúdio; variante termina em 3,23 s | Integrar a política e repetir pelo app; verificar quarto bipe, último quadro e resíduo temporal |
| São os únicos dois achados com dano real comprovado | Refutado como descrição atual | T04/T05/T08 também reproduziram problemas |
| Áudio contínuo já existe no Windows porque ele reencoda os trechos | Tecnicamente incorreto | Reencodar por trecho é diferente de uma passagem contínua |
| Manter SmartCut como padrão experimental | Agora sustentado por 50/50 quadros do miolo e plano 15+50+15 | Manter na classe testada; complementar apresentação temporal e outras classes |
| Preset é uma decisão de benchmark simples | Concordo para uma escolha inicial | Medir no aparelho; ampliar só se necessário |
| Avisar/bloquear recodificação HDR/10-bit é suficiente no início | Concordo | Preservar cópia compatível; não exigir suporte completo agora |
| Crop par em yuv420p não muda o resultado | Refutado por T05: y=87 produziu a linha 86 | Alinhar seleção e coordenadas exibidas, ou validar rota exata |
| Finalidade de Inserir é decisão de produto | Concordo | Separar finalidade de inconsistência dos efeitos |
| Semântica de Inserir pode ficar toda para o fim | Concordo apenas quanto a recursos novos | Rótulos que descrevem efeitos diferentes merecem correção antecipada |

## 3. Quatro conclusões técnicas após a Rodada A

### 3.1 AAC corrige o defeito principal no experimento; falta aceitação integrada

A Rodada A relata vídeo começando na fonte em 1,4 s, primeiro PTS de vídeo na saída em 0,442 s e áudio copiado incluindo conteúdo anterior ao intervalo. A variante que mudou somente o áudio para AAC reduziu a duração de 3,62 s para 3,23 s, com 80 quadros e bipes deslocados para próximo das posições esperadas.

Isso sustenta a política AAC como correção do prelúdio no modo preciso. O modo Sem Reencode apresentou outro comportamento: aproximadamente 90 quadros e início visual perto de 1,0 s. Não se deve converter seu áudio compulsoriamente para corrigir um modo cujo objetivo é cópia; deve-se mostrar seu intervalo efetivo.

**Próxima prova necessária:** repetir a correção pelo aplicativo após implementação autorizada, preservando todas as faixas e seus perfis. A tabela AAC lista três bipes, embora a seleção contenha quatro posições esperadas; esclarecer se foi abreviação ou ausência do último. A duração residual e a identidade do último quadro também devem constar da aceitação. Esses complementos não anulam o diagnóstico principal.

### 3.2 Processar áudio por trecho não é uma passagem contínua

O Windows monta AAC em cada trecho do SmartCut. Uma passagem contínua selecionaria todo o áudio do intervalo e o codificaria uma vez, sem acompanhar as reinicializações dos segmentos de vídeo.

Concordo em testar a proposta primeiro no Windows por conveniência de instrumentação. Não proponho introduzi-la antes de provar necessidade ou benefício. Ela continua sendo melhoria futura, não requisito para aplicar a política de áudio no corte preciso Android.

O alvo também não deve ser copiar qualquer comportamento Windows: ambos devem respeitar o resultado escolhido. Uma diferença de 3,29 s contra 3,20 s no Windows merece explicação, mesmo sendo menor que a do Android.

### 3.3 O crop anunciado em y=87 efetivamente começou em y=86

O código Android arredonda largura e altura, mas pode conservar coordenadas ímpares. O caso discutido usa `crop=322:162:100:87`: o `87` continua relevante.

A documentação de `crop` informa que `exact`, desativado por padrão, controla arredondamentos de dimensões e coordenadas em vídeos subamostrados. [Documentação oficial do filtro](https://ffmpeg.org/ffmpeg-filters.html#crop).

A Rodada A mediu luma 192 no filtro atual e 224 com `exact=1`, correspondentes à distinção entre as linhas 86 e 87 no padrão local. O arquivo entregue pelo Android confirmou a divergência entre a confirmação e os pixels. O quadro-resumo também reprova Windows; o procedimento detalhado descreve explicitamente o filtro compartilhado e a execução no app Android. Convém vincular a evidência da saída Windows para fechar a rastreabilidade do teste nessa plataforma.

Minha escolha inicial continua sendo alinhar seleção e coordenadas exibidas à grade efetiva nos dois apps. A variante `exact=1` agora tem evidência favorável no filtro e pode ser a alternativa se preservar a coordenada ímpar for requisito, após validação de crominância e encoders relevantes.

### 3.4 Perda de faixa no remux Android foi reproduzida

A fonte C3 e o intermediário têm vídeo e duas faixas de áudio; a saída Android entregue tem apenas vídeo e um áudio. O experimento com `-map 0` preservou os três streams. O Windows passou nesse inventário específico. Isso transforma o risco anterior em perda localizada e reproduzida segundo a Rodada A.

A correção Android deve preservar a seleção até o último mux e validar a saída. Para C3, o mapeamento de todos os streams resolve o inventário; isso não habilita preservação indiscriminada de anexos/dados em qualquer contêiner. T04 revelou separadamente a perda de perfil por faixa no Windows, mesmo quando o inventário continua completo.

## 4. Decisões revisadas que eu tomaria agora

### 4.1 Primeira entrega pequena

Eu limitaria a primeira entrega a:

1. Usar os artefatos da Rodada A como referência de regressão e vincular comandos/versões às saídas.
2. Aplicar a política de áudio no corte preciso, sem mudar preset ou estratégia SmartCut junto.
3. Fazer a seleção pretendida chegar ao remux final e conferir a saída.
4. Corrigir controles sem efeito e descrições de transição materialmente diferentes.
5. Detectar perfis HDR/10-bit que a rota de recodificação não consegue preservar e explicar a limitação.

Essas mudanças não dependem de um esquema JSON comum ou uma reestruturação do painel Windows. Algumas podem ser pequenas no código; seu risco só será conhecido depois dos testes focais.

### 4.2 SmartCut continua inicialmente como padrão experimental

Revi minha recomendação anterior de rebaixá-lo preventivamente. T06 agora sustenta a preservação do miolo no caso H.264/CFR avaliado nos dois apps: 50/50 quadros, 80 no total e nenhuma omissão/repetição de emenda relatada. O rótulo experimental foi confirmado na ajuda dos dois projetos; isso não significa necessariamente uma advertência permanente em toda tela.

Se houver reprovação reproduzível, restringir a classe afetada ou mudar seu encaminhamento para reencode. Não desativar todas as classes sem necessidade, nem manter uma classe defeituosa apenas porque o comando termina com sucesso.

Fallback por erro de execução e validação de conteúdo são proteções diferentes. Uma emenda pode produzir um arquivo decodificável e ainda conter quadros repetidos ou um deslocamento temporal.

### 4.3 Inserir áudio: separar duas decisões

**Finalidade:** a implementação examinada intercala áudio e produz áudio. Se o produto deve gerar vídeo com uma faixa adicional, isso requer uma decisão funcional específica, não uma correção automática neste trabalho.

**Semântica atual:** o Smart Insert Windows aplica fades apenas no inserido, enquanto o modo integral pode fazer crossfade entre partes. É possível corrigir os nomes e a ajuda sem decidir agora se a ferramenta também deve produzir vídeo.

### 4.4 Arquitetura e recursos avançados ficam condicionados à necessidade

Áudio contínuo, plano compartilhado, matriz extensa, SmartJoin mais conservador e x265 Android continuam como opções de evolução. Tornam-se necessários conforme os testes revelem falhas, divergências ou manutenção repetida que justifiquem o investimento.

## 5. Como executar os testes sem criar um projeto de testes interminável

### 5.1 Três níveis de prova

| Nível | O que verifica | Limite |
|---|---|---|
| Regra/comando | Política escolhida, flags, fallback, cálculo de duração | Não comprova a mídia produzida |
| Artefato real | Quadros, amostras, timestamps e faixas | Não comprova sozinho a interface/preview |
| Aplicativo e player | Seleção, mensagens, reprodução e entrega final | Inspeção visual isolada pode não perceber perdas pequenas |

Usar o menor nível suficiente para cada afirmação. Para o caso dos 3,62 s, presença de AAC no comando é insuficiente: é necessário artefato real. Para desabilitar um controle sem efeito, não é necessário um corpus inteiro de HDR e GOP aberto.

### 5.2 Uma referência antes de cada correção

Guardar entrada, hash, parâmetros, comandos reais, saída atual e análise. Depois aplicar uma mudança isolada e repetir. Sem a referência, fica difícil afirmar se a alteração corrigiu algo ou mudou o resultado por outra razão.

O teste principal deve passar pelo pipeline do aplicativo. Uma reprodução manual do comando é útil para isolar causa, mas não substitui verificar que o app utiliza os mesmos parâmetros e entrega o mesmo arquivo.

Para Android, analisar o arquivo realmente salvo ao usuário, além de temporários relevantes. Para Windows, registrar que a árvore local pode conter modificações além do HEAD. Não usar somente o nome da versão como identificação completa da execução.

### 5.3 Corpus pequeno inicial

| Identificador | Entrada controlada proposta | Uso |
|---|---|---|
| C1 | H.264/AAC, 6 s, 640×360, 25 fps, GOP 1 s | Reprodução do corte 1,4–4,6 s |
| C2 | Variante sem áudio de C1 | Isolar contribuição do áudio |
| C3 | Vídeo com duas faixas AAC distinguíveis, idioma/título diferentes | Remux, seleção e perfil por faixa |
| C4 | Quadro com padrão diferente em cada linha/coluna, yuv420p | Crop e orientação |
| C5 | Áudio PCM com marcadores; principal 10 s e inserido 2 s | Inserção, duração e transições |
| C6 | Três vídeos numerados, com marcadores de som | SmartJoin e acúmulo temporal |

Validar o perfil realmente gerado. Solicitar GOP de 1 s ao encoder não substitui sondar os pontos resultantes. Na fonte temporal, usar marcadores de áudio reconhecíveis por correlação, não apenas um impulso que possa se espalhar após compressão.

HEVC, VFR, HDR e arquivos longos entram na expansão dirigida abaixo. Um caso sem suporte no aparelho deve ser registrado como não executado ou como teste de rejeição, não como aprovado.

## 6. Roteiro prioritário — testes que resolvem as discordâncias

**Abrangência:** todos os testes T01–T20 devem ter uma avaliação no SIG Windows e outra no SIG Android quando a operação existir em ambos. Ausência de um modo no Android, como o caminho equivalente ao Smart Insert Windows examinado, deve ser registrada como diferença de capacidade, sem inventar execução ou aprovação. O detalhamento de plataforma e hardware está na seção 15.

### T01 — Reproduzir os 3,62 s e localizar o excesso

**Estado após Rodada A:** defeito do corte preciso Android reproduzido; excesso localizado como prelúdio com áudio copiado anterior ao corte. Windows foi declarado aprovado, mas a contagem “79–80” requer discriminação por arquivo. Procedimento abaixo permanece para regressão e complemento de bordas.

**Prioridade:** imediata, antes de alterar o corte.

**Pergunta:** o excesso é conteúdo, deslocamento inicial, duração de áudio, duração de vídeo ou declaração do contêiner?

**Procedimento:** executar C1, intervalo 1,4–4,6 s, em Reencode Completo, Sem Reencode e SmartCut nos dois apps. Guardar saída final e, quando disponíveis sem alteração funcional do app, temporários de concat/remux. Repetir a análise com C2, sem áudio.

**Medir:** duração declarada; primeiro e último PTS apresentado; duração do último quadro; contagem/identidade de quadros; começo e fim úteis de áudio; offset A/V. Não usar DTS como ordem de apresentação do vídeo.

**Resultado útil:** um quadro de diferenças que explique os 420 ms e os 90–100 ms do SmartCut. Em corte exato, conteúdo fora do intervalo é reprovação. Em cópia, registrar a aproximação real e verificar se a interface a descreve.

**Não concluir:** “3,62 s = 420 ms de áudio extra” sem identificar esse áudio. Se o caso não reproduzir, registrar versão, comandos e diferenças de ambiente; não inventar reprodução do resultado histórico.

### T02 — AAC isolado resolve o problema?

**Estado após Rodada A:** variante AAC resolveu o grande prelúdio no cenário informado (3,23 s e 80 quadros). Falta integrar/retestar pelo app e esclarecer o quarto bipe esperado e o resíduo temporal. O relatório não identifica explicitamente o binário/ambiente em que cada variante de comando foi executada.

**Prioridade:** imediata, associado à correção de D1.

**Procedimento:** partir do comando real do corte preciso Android. Criar variante experimental com áudio AAC explícito, mantendo vídeo, preset, seek, timestamps e contêiner iguais. Não mudar simultaneamente bitrate de vídeo ou mux intermediário. Depois de uma eventual correção autorizada, repetir pelo aplicativo.

**Medir:** os mesmos itens de T01, inclusive identidade dos quadros nas duas pontas e localização dos marcadores de áudio.

**Aprovação da hipótese:** AAC elimina a divergência e o conteúdo selecionado permanece correto. Repetir em 44,1 kHz e 48 kHz e em mais de um intervalo antes de generalizar.

**Interpretação:** se melhorar parcialmente, há mais de uma contribuição. Se somente `format.duration` melhorar, ainda falta prova de apresentação. Se desaparecer conteúdo útil, a mudança não resolveu corretamente o problema.

**Não concluir:** aprovação de C1 certifica todos os codecs, múltiplas faixas ou o SmartCut.

### T03 — O remux final perde faixas?

**Estado após Rodada A:** reprovação Android confirmada pelo desenvolvimento, com desaparecimento de uma das duas faixas de áudio no remux. Windows aprovado em C3. `-map 0` corrigiu o caso isolado; falta integração e teste negativo de destino incompatível.

**Prioridade:** imediata.

**Procedimento:** usar C3, com conteúdo audível distinto em cada faixa. Executar uma rota que realmente faça remux para outro contêiner; testar só o caso de mesma extensão pode não exercitar a função. Comparar inventário da entrada, do temporário e da saída entregue.

**Medir:** quantidade de streams por tipo, codec, idioma, título, disposição padrão e conteúdo de cada faixa. A ordem numérica pode mudar; estabelecer a correspondência por identidade/conteúdo.

**Aprovação:** toda faixa selecionada está presente no arquivo final, com conteúdo e identificação coerentes. Faixa omitida deliberadamente deve estar prevista na política.

**Teste negativo:** incluir um extra que o destino não aceite. Esperar alternativa ou recusa clara conforme a política; não descarte silencioso nem saída parcial apresentada como completa.

**Conclusão permitida:** identificar exatamente se há perda no remux e verificar sua correção. Não atribuir ao remux uma perda ocorrida em uma etapa anterior.

### T04 — Parâmetros de uma faixa estão sendo aplicados às outras?

**Estado após Rodada A:** Windows reprovado: segunda faixa estéreo/48 kHz virou mono/44,1 kHz. No Android, o caminho de cópia mantém perfis no intermediário, mas T03 impede aprovar a entrega multifaixa. Não há evidência de que Android tenha o mesmo defeito de imposição de perfil; seu futuro caminho AAC precisa prevenir isso.

**Prioridade:** junto com áudio/remux multifaixa.

**Entrada:** vídeo com uma faixa mono/44,1 kHz e outra estéreo/48 kHz, com canais distinguíveis.

**Procedimento:** corte preciso com recodificação de áudio. Sondar cada saída e ouvir canais de teste.

**Aprovação:** parâmetros finais correspondem à política anunciada por faixa. Se a política autoriza normalização comum, ela deve estar explícita; se promete preservar perfis, não pode converter tudo ao perfil da primeira faixa.

**Não concluir:** manter duas faixas significa manter seus canais, idioma ou conteúdo corretamente.

### T05 — O crop `322:162:100:87` começa realmente na linha 87?

**Estado após Rodada A:** filtro compartilhado reprovado; saída do app Android também comprovou linha 86 apesar da confirmação 87. Windows consta como reprovado no resumo; vincular sua saída real na evidência detalhada. Variante `exact=1` passou na distinção espacial isolada.

**Prioridade:** focal, de baixo custo.

**Procedimento:** aplicar ao C4 o filtro usado pelos aplicativos. Comparar a saída do filtro antes da compressão com duas referências de coordenada: linha 87 e linha 86. Testar separadamente uma variante `exact=1`, como experimento. Depois repetir a seleção pelo app, verificando a coordenada exibida.

**Cuidado:** o padrão deve diferenciar linhas vizinhas; uma grade com grandes áreas uniformes pode esconder o deslocamento. No teste isolado, usar saída sem perdas ou raw para não confundir compressão com seleção espacial.

**Aprovação:** a região salva corresponde às coordenadas efetivas anunciadas. Se houver alinhamento à grade, o retângulo e os números devem refletir o ajuste.

**Não concluir:** largura/altura corretas comprovam x/y corretos; nem que `exact=1` funciona em todos os encoders porque funcionou no filtro isolado.

### T06 — O miolo do SmartCut está integralmente preservado?

**Estado após Rodada A:** aprovado para os pixels decodificados dos 50 quadros do miolo e para o plano 15+50+15, segundo os resultados enviados, em ambos os apps. Preservação binária do payload comprimido, áudio e apresentação inicial não são certificados por essa contagem. Não é necessário repetir do zero antes de implementar correções em outras rotas.

**Prioridade:** primeira prova de conteúdo do modo inteligente.

**Procedimento:** usar C1 com quadros identificáveis. Obter do plano/comandos os limites efetivamente copiados. Relacionar cada quadro interno esperado da fonte com seu quadro correspondente na saída, usando um decoder de referência comum e sem redimensionamento ou conversão arbitrária de cor.

**Aprovação:** 100% dos quadros pertencentes ao corpo copiável têm pixels iguais à referência, em ordem, sem duplicações nem omissões. O deslocamento temporal absoluto deve ser explicado pela origem adotada, sem mudar o alinhamento até os hashes coincidirem.

**Bordas:** avaliar separadamente todos os quadros na passagem cabeça/corpo e corpo/cauda. Não excluir genericamente “dez quadros de transição”; identificar cada região pelo plano.

**Não concluir:** hashes de quadros iguais comprovam identidade do arquivo ou do bitstream comprimido. Para essa afirmação adicional, analisar payload comprimido com normalização de empacotamento definida.

### T07 — Áudio segmentado introduz silêncio, repetição ou deslocamento?

**Estado após Rodada A:** não foram apresentados resultados específicos deste teste. O lead de SmartCut foi relatado, mas a posição relativa dos marcadores de áudio/vídeo ainda precisa ser discriminada. Áudio contínuo permanece opção condicionada a essa medição.

**Prioridade:** antes de decidir implementar áudio contínuo.

**Procedimento:** usar uma fonte com marcadores sonoros antes, dentro e depois das emendas. Gerar SmartCut atual. Comparar com uma referência que recorta o mesmo áudio continuamente. Se possível, testar uma variante contínua mantendo o vídeo híbrido idêntico.

**Medir:** correlação temporal por janela, duração útil, silêncios não previstos, repetição e relação com os marcadores visuais. Fazer teste curto e uma sequência com várias junções para observar acúmulo.

**Aprovação do atual:** nenhuma mudança de tempo ou descontinuidade não planejada. A variante contínua só merece prioridade se trouxer benefício demonstrável ou simplificar uma correção necessária.

**Não concluir:** hash PCM diferente após AAC demonstra bug; recodificação com perdas normalmente impede igualdade literal. Procurar diferença temporal e artefatos, não exigir amostras idênticas entre dois encodes AAC.

### T08 — “Linear” significa a mesma transição em Smart Insert e integral?

**Estado após Rodada A:** divergência Windows confirmada por artefatos: integral 11,60 s com crossfade; Smart Insert 12,03 s com fades só no inserido. Android não possui o Smart Insert examinado; seu builder integral é coerente com crossfade, mas a tabela não apresenta duração medida de sua saída. Os 30 ms além da soma de 12 s no Smart Insert são uma questão temporal separada dos 400 ms de diferença editorial.

**Prioridade:** primeira rodada de semântica de UI.

**Entrada:** C5, principal 10 s, inserido 2 s, ponto 5 s, transição 0,2 s. Usar sons distinguíveis e medir previamente as amostras de entrada.

**Procedimento:** executar Smart Insert Windows, integral Windows e integral Android. Comparar com “Nenhuma” e “Fade in/out”. Guardar comandos, áudio e envelope nas duas junções.

**Referências de produto propostas:** concat simples = 12 s; fade sem sobreposição = 12 s; crossfade de 0,2 s em duas fronteiras = 11,6 s. Em início/fim, uma única fronteira gera 11,8 s para esse crossfade.

**Aprovação:** mesmo rótulo promete e produz o mesmo tipo de efeito. É aceitável manter efeitos distintos desde que os nomes expliquem isso. A comparação atual deve confirmar ou refutar a divergência identificada nos builders.

**Não concluir:** saída decodifica e não estala, logo a transição é equivalente. Duração e região alterada fazem parte da equivalência.

## 7. Expansão dirigida — executar conforme a mudança afetar a rota

### T09 — SmartCut nos limites e sem corpo copiável

**Entrada/procedimento:** testar início/fim em keyframes; entre keyframes; antes do primeiro keyframe interno; perto do fim; trecho curto sem dois limites internos úteis; vídeo sem áudio. Repetir H.264 e HEVC quando houver suporte real.

**Verificar:** não criar região vazia, não duplicar quadro de fronteira, não falhar por áudio ausente; recodificação integral quando o plano híbrido não for válido. Motivo exibido deve corresponder ao plano executado.

**Aprovação:** cada seleção obedece à mesma convenção temporal. O uso de fallback não é reprovação se estiver correto e explicado.

### T10 — SmartJoin com transição: efeito e janela técnica separados

**Entrada:** dois clipes de 10 s, 25 fps, com identidade visível e áudio distinto; dissolve de 0,2 s.

**Verificar:** timeline útil de 19,8 s; mistura restrita ao intervalo da transição; regiões maiores recodificadas para alcançar keyframes não ampliam o efeito. Comparar SmartJoin e integral pelo conteúdo temporal e pelo efeito, não por hash dos pixels recodificados.

**Aprovação:** nenhuma parte falta ou aparece duas vezes fora da sobreposição intencional; áudio acompanha a mesma contração temporal do vídeo.

### T11 — SmartJoin com clipe central curto e várias junções

**Entrada:** três clipes, sendo o do meio menor que a soma das transições pretendidas; depois uma sequência de 20 clipes controlados.

**Verificar:** ajuste ou rejeição explícito no caso curto, sem duas pontes consumirem material incompatível. Na sequência longa, localizar marcadores em cada emenda e no final; comparar com a soma das durações efetivas menos as sobreposições.

**Aprovação:** não acumular atraso, silêncio ou quadros repetidos em cada junção. Não aceitar um erro pequeno por clipe se ele cresce de forma sistemática.

### T12 — VFR, taxa fracionária, início não zero e offset A/V

**Entrada:** VFR; CFR 30000/1001; fonte com primeiro PTS não zero; fonte com áudio começando deliberadamente 200 ms depois do vídeo. Confirmar que os arquivos possuem essas características.

**Verificar:** mesma regra temporal nos apps; não transformar VFR em CFR silenciosamente; preservar o offset intencional, em vez de fazer cada stream começar em zero por conta própria.

**Aprovação:** conteúdo e sincronismo correspondem à seleção. Uma rota que recusa VFR de forma clara pode ser aceitável na primeira versão; aprovação de CFR não habilita VFR automaticamente.

### T13 — HDR/10-bit: detectar e evitar degradação silenciosa

**Entrada:** SDR 8-bit, SDR 10-bit e HDR 10-bit com metadados conhecidos.

**Verificar:** só alertar quando a operação/rota não consegue preservar o perfil. Cópia compatível não deve ser bloqueada por falta de encoder HDR. Na recodificação não suportada, explicar a limitação ou oferecer conversão explicitamente.

**Aprovação inicial:** nenhuma saída anunciada como preservada vira 8-bit/SDR silenciosamente. Resultado de probe desconhecido não deve ser tratado automaticamente como SDR 8-bit conhecido.

**Não exigir agora:** implementar tone mapping ou encoder HDR para passar esse teste de proteção.

### T14 — HEVC curto e fallback de encoder

**Entrada:** HEVC com trecho/borda menor que 3 s; testar dispositivo com HEVC hardware e ausência de software equivalente.

**Verificar:** regra de trecho curto não escolhe H.264 silenciosamente. Em falha de hardware, apenas uma tentativa equivalente quando disponível; motivo e encoder real registrados. Simular falha de recurso/arquivo separadamente de falha do encoder em ambiente de teste.

**Aprovação:** nenhuma ponte AVC com corpo HEVC, nenhum loop de repetição, nenhuma falta de espaço apresentada como problema de GPU. Alteração de codec requer política explícita.

### T15 — Controles de áudio e preview correspondem à saída

**Procedimento:** alternar cópia/recodificação, qualidade de áudio, crop e transições. Comparar estado da tela, plano/comando e saída. Em áudio com perdas, distinguir bitrate alvo de bitrate efetivamente medido; não exigir igualdade numérica exata.

**Aprovação:** controle ativo produz a mudança prometida ou explica por que não se aplica. Na transição, preview e timeline refletem a duração efetiva, inclusive ajustes para clipe curto.

**Limite:** o preview rápido de fontes pode ser diferente de um preview renderizado, desde que isso esteja claro e não prometa o efeito final.

### T16 — Extrair áudio: cópia, conversão e faixa selecionada

**Entrada:** áudio compatível com saída, vídeo multifaixa e lote com item sem áudio e item menor que o intervalo.

**Verificar:** extração original evita nova compressão quando prometida; conversão aplica o perfil; faixa selecionada é a ouvida/exportada; itens ajustados ou ignorados ficam identificados.

**Aprovação:** Windows e Android produzem a mesma operação lógica. Não avaliar somente extensão; arquivo `.m4a` pode representar caminhos diferentes.

### T17 — Limpar áudio: algoritmo, formato e inteligibilidade

**Entrada:** voz com ruído estacionário, voz distante, sons transitórios e estéreo com conteúdo distinto por canal.

**Procedimento:** comparar Equilibrado e Forte atuais, registrando filtros e formato de saída. Avaliar variantes de unificação somente depois dessa referência. Fazer audição sem deixar uma saída parecer melhor apenas por estar mais alta.

**Aprovação:** mesmo nome corresponde ao algoritmo/perfil decidido; não remover canais ou taxa silenciosamente; sem perda inaceitável de sílabas ou artefatos. Caso um teste existente use “benchmarked” no nome, localizar a evidência do benchmark antes de tratá-lo como superioridade comprovada.

**Limite:** métricas e transcrição automática são auxiliares; não substituem audição. Este teste não exige padronizar o Forte antes das correções iniciais de corte/remux.

### T18 — Preset no aparelho: decisão inicial pequena

**Entrada:** três trechos iguais em todas as variantes: imagem com detalhe/texto, movimento e baixa luz. Fixar resolução, fps, áudio, contêiner, CRF e duração.

**Procedimento:** medir `medium`, `fast`, `veryfast` e o `ultrafast` atual no caminho CPU. Repetir em ordem alternada para reduzir o efeito de aquecimento; fazer um ensaio sustentado com o candidato escolhido. Registrar dispositivo e estado térmico quando disponível.

**Medir:** tempo, tamanho, falhas, memória observável e preservação de detalhes. Escolher o preset inicial por desempenho/qualidade no equipamento-alvo; ampliar a matriz somente quando houver outras classes relevantes de aparelho.

**Aprovação:** benefício demonstrado sem regressão visual relevante. Não usar o mesmo CRF como prova de pixels iguais e não comparar uma saída de 3,2 s com outra de 3,62 s como benchmark de compressão.

### T19 — Cancelamento e falha na entrega

**Procedimento:** cancelar durante encode e remux em arquivos descartáveis; testar destino sem permissão ou quota controlada, nome repetido e falha de gravação. Não encher indiscriminadamente o disco de trabalho para simular falta de espaço.

**Aprovação:** original preservado; nenhum arquivo incompleto anunciado como concluído; processo termina; repetição posterior funciona; nomes de saídas não sobrescrevem conteúdo sem política apropriada. Em lote, estado parcial deve ser distinto de sucesso total.

**Prioridade:** obrigatório se a correção mexer na gestão de temporários ou no momento em que o remux é considerado concluído.

### T20 — Capítulos, orientação e extras após edição

**Entrada:** vídeo rotacionado com capítulos antes/dentro/depois do corte, legenda e anexo; testar um destino que aceite a seleção e outro que não aceite.

**Verificar:** orientação sem giro duplo, seleção de extras coerente, capítulos dentro da nova timeline ou omissão explicitamente prevista. Conferir em player-alvo e por probe.

**Aprovação:** informações preservadas continuam verdadeiras. Não usar `-map_chapters 0` como sinônimo de capítulos corretos depois do corte.

## 8. Medições: como evitar um teste que aprova o defeito

### 8.1 Duração de arquivo não é duração de conteúdo útil

Guardar separadamente:

- Duração declarada pelo contêiner.
- Primeiro e último instante de apresentação de vídeo, incluindo duração do último quadro quando conhecida.
- Faixa temporal útil de cada áudio, considerando delay/padding e marcadores de conteúdo.
- Intervalo solicitado e intervalo efetivamente materializável pela regra escolhida.

Campo ausente no probe é desconhecido, não zero. Uma regra global de “tolerar 500 ms” esconderia justamente o problema dos 3,62 s.

### 8.2 Referências numéricas úteis

Na fonte CFR com PTS em múltiplos de 40 ms, o intervalo [1,4; 4,6) contém **80 quadros**. Isso depende do perfil da fonte efetivamente confirmado.

Em PCM de 44,1 kHz, 3,2 s representam **141.120 amostras por canal**; em 48 kHz, **153.600**. Usar essa contagem em referência PCM com início e limites conhecidos. Não aplicá-la cegamente ao número bruto de amostras decodificadas de qualquer AAC sem analisar apresentação e padding.

Para intervalos que não caem exatamente em amostras/quadros, registrar a regra de arredondamento adotada e aplicá-la nos dois lados. Não escolher tolerâncias diferentes depois de observar as saídas.

### 8.3 Hashes: três perguntas diferentes

| Hash/comparação | Responde | Não responde |
|---|---|---|
| SHA-256 do arquivo | É exatamente o mesmo arquivo? | Se dois contêineres diferentes preservam o mesmo vídeo |
| Hash de pixels decodificados por quadro | Esses quadros têm os mesmos pixels na referência? | Se os pacotes foram copiados literalmente |
| Comparação de payload comprimido normalizado | O conteúdo comprimido copiado foi preservado segundo a normalização? | Se timestamps/player apresentam a timeline corretamente |

No vídeo copiado, comparar pixels sem conversão para um formato de menor profundidade que possa ocultar diferenças. Nas regiões recodificadas, não exigir igualdade literal à fonte: avaliar fidelidade e conteúdo temporal.

## 9. Comandos auxiliares para quem executar a bateria

São **modelos de diagnóstico, não comandos executados nesta tarefa nem correções prontas de pipeline**. Substituir `entrada.mp4`, `saida.mp4` e nomes de evidência por caminhos reais. Usar os binários das versões em avaliação para gerar saídas e um conjunto de análise identificado para comparar artefatos. Confirmar opções na ajuda do binário embarcado.

O ffprobe permite exportar streams, capítulos, pacotes e quadros em formato estruturado. Essas informações apoiam inventário e análise temporal. [Documentação oficial do ffprobe](https://ffmpeg.org/ffprobe.html).

### 9.1 Identificação e inventário

```powershell
ffmpeg -version
ffmpeg -buildconf
ffprobe -version
Get-FileHash -LiteralPath 'entrada.mp4' -Algorithm SHA256
ffprobe -v error -show_format -show_streams -show_chapters -of json 'saida.mp4' > 'saida-inventario.json'
```

Executar o inventário para entrada, intermediário e saída final quando o objetivo for localizar perda de faixa. Registrar também app/commit, alterações locais relevantes e dispositivo/encoder real.

### 9.2 Pacotes e quadros

```powershell
ffprobe -v error -show_packets -of json 'saida.mp4' > 'saida-pacotes.json'
ffprobe -v error -select_streams v:0 -show_frames -of json 'saida.mp4' > 'saida-video-quadros.json'
ffprobe -v error -select_streams a:0 -show_frames -of json 'saida.mp4' > 'saida-audio0-quadros.json'
```

Repetir a análise de áudio para cada faixa relevante. Em arquivos longos, limitar a análise às janelas pertinentes com método documentado, conferindo que o seek da ferramenta de análise não descartou justamente a borda investigada. As primeiras provas devem usar arquivos curtos para permitir análise integral.

### 9.3 Decodificação e hashes de vídeo

```powershell
ffmpeg -v error -xerror -i 'saida.mp4' -map '0:v?' -map '0:a?' -f null -
ffmpeg -v error -i 'saida.mp4' -map 0:v:0 -an -c:v rawvideo -fps_mode passthrough -f framehash -hash sha256 'saida-video.framehash'
```

O primeiro comando verifica a decodificação dos streams de áudio/vídeo selecionados; não valida capítulos, anexos ou semântica. O segundo gera hashes de quadros decodificados. O muxer framehash é documentado pelo FFmpeg. [Formatos de hash](https://ffmpeg.org/ffmpeg-formats.html#framehash).

Antes de comparar, conferir formato de pixel, profundidade e correspondência dos quadros. Diferenças de timestamps nas linhas de framehash não significam necessariamente diferenças de pixels; comparar os campos apropriados e avaliar temporalidade separadamente. Se a versão não aceitar `-fps_mode`, adaptar conforme sua ajuda, garantindo que a análise não duplique ou descarte quadros.

### 9.4 PCM para correlação de áudio

```powershell
ffmpeg -v error -i 'saida.mp4' -map 0:a:0 -vn -c:a pcm_f32le -f f32le 'saida-audio0.f32'
```

Esse arquivo é apenas material de análise; não define a saída do produto. Guardar taxa e canais do áudio exportado, pois PCM bruto não carrega cabeçalho. Não forçar mono ou 16 kHz no diagnóstico de preservação: isso pode apagar a divergência investigada. Se for necessária reamostragem para uma métrica, documentá-la e conservar a medida original.

Não usar correlação global sozinha: comparar janelas antes/depois das emendas para detectar um trecho deslocado dentro de uma duração total aparentemente correta.

## 10. Onde aproveitar testes existentes

Não começar criando uma suíte paralela que repete tudo. Usar os testes existentes para regras e acrescentar casos que observem saídas reais nas rotas alteradas.

| Área | Android | Windows |
|---|---|---|
| Corte e fallback | `FfmpegCutModesTest`, `FfmpegMediaPoliciesTest` | `test_ffmpeg_cut_modes.py` |
| Remux | `FfmpegOutputRemuxerTest` | Casos pertinentes do painel e novo caso de artefato, se necessário |
| Perfis e encoders | `FfmpegVideoQualityTest`, `FfmpegVideoEncodersTest` | `test_ffmpeg_tools_logic.py` e cobertura de encoders |
| Crop/preview | `FfmpegPreviewSelectionTest` | `test_ffmpeg_area_selection.py` |
| Junções | `SmartJoinPlannerTest` | `test_smart_join_planner.py` |
| Inserção/áudio | `FfmpegMediaPoliciesTest` | `test_ffmpeg_tools_logic.py` |

A existência de um teste pelo nome não foi tratada como aprovação nesta tarefa. Não executei essas suítes. Quando houver implementação, seguir os gates locais aplicáveis; para caminhos Android no hotspot STT, observar as exigências específicas de `AGENTS.md`. A primeira entrega proposta não depende de alterar esse hotspot ou pacotes nativos.

## 11. Como registrar resultados para responder às perguntas certas

Modelo recomendado por caso:

```text
Teste: Txx
Pergunta técnica:
Versão/estado do app e FFmpeg:
Dispositivo e encoder realmente usados:
Entrada e SHA-256:
Perfil confirmado da entrada:
Parâmetros da operação:
Saída esperada e regra/tolerância definida antes da execução:
Comandos reais e arquivos intermediários pertinentes:
Resultado antes da alteração:
Alteração isolada avaliada, se houver:
Resultado depois da alteração:
Inventário/PTS/quadros/amostras relevantes:
Estado: aprovado / reprovado / inconclusivo / não executado
Conclusão permitida:
O que permanece sem prova:
Local das evidências:
```

**Aprovado** significa que o critério declarado foi medido e satisfeito. **Inconclusivo** significa que faltou uma medida ou que a referência não permite decidir. Não converter ausência de erro no log em aprovação do resultado.

Ao comparar os aplicativos, medir cada um contra a fonte e o pedido, além de compará-los entre si. Dois resultados iguais podem compartilhar o mesmo erro.

## 12. Ordem prática sugerida ao responsável pela verificação

### Rodada A — reproduzir e decidir as primeiras correções

**Rodada recebida e analisada.** T01/T02/T03/T04/T05/T06/T08 forneceram evidência suficiente para definir as primeiras correções. Não exigir a repetição integral dessa rodada antes de autorizar uma implementação localizada.

Primeiro recuperar as evidências já guardadas para fechar quatro detalhes: contagem por saída, quarto bipe da variante AAC, PTS relativos do SmartCut e formato/duração do Smart Insert. A seção 18 transforma esses pontos em verificações curtas. Repetir apenas os casos afetados por cada correção candidata.

### Rodada B — sustentar o SmartCut como padrão experimental

T06 está aprovado na classe testada. Complementar T07 e os casos de fronteira de T09; usar T12/T14 para classes adicionais. A emulação não certifica MediaCodec de aparelho físico. Se uma classe reprovar, corrigir ou restringir aquela classe, mantendo registradas as aprovadas.

### Rodada C — padronizar o restante conforme demanda

Executar T10/T11 ao mexer em SmartJoin; T15 ao ajustar UI/preview; T16/T17 ao unificar extração e limpeza. T18 pode ser um experimento independente pequeno, sem bloquear as correções funcionais. T19 acompanha mudanças de entrega/temporários; T20 acompanha preservação de extras/rotação.

T13 serve para uma proteção inicial de conversão e não obriga a implementar HDR. x265 permanece fora da primeira entrega, salvo requisito explícito de preservar HEVC com recodificação por software no Android.

## 13. Minha posição final para encaminhar ao autor da resposta

Os testes reforçam sua proposta de começar pequeno e manter SmartCut como padrão experimental. A hipótese AAC agora tem sustentação experimental, e minha exigência anterior de prova foi atendida quanto ao grande prelúdio. T06 também supera a prova anterior de 38/49 e 39/49: há agora um miolo identificado com 50/50 quadros iguais.

O crop ímpar efetivamente falhou, e o Windows também requer correções concretas em perfis multifaixa e Smart Insert. As primeiras mudanças não se limitam, portanto, ao Android. Áudio contínuo continua distinto de codificação por trechos e não se tornou necessário apenas porque apareceu lead no contêiner.

**As correções principais já podem ser planejadas a partir dos resultados.** Os complementos pendentes refinam a aceitação e evitam afirmar “precisão completa” com contagens aproximadas ou bipes omitidos da tabela. Não justificam paralisar as correções nem reabrir toda a pesquisa.

## 14. Referências locais

- [Relatório detalhado original](/D:/Projetos/SIG/docs/decisao-padronizacao-ffmpeg-android-windows-20260913.md).
- [Bateria histórica de comparação](/D:/Projetos/SIG/docs/bateria-comparativa-android-windows-20260913.md).
- [Resultados da Rodada A enviados pelo desenvolvimento](/D:/Projetos/SIG/docs/rodada-a-resultados-20260913.md).
- [Modos de corte Android](/D:/Projetos/SIG/app/src/main/java/br/gov/sp/pcsp/launcher/FfmpegCutModes.kt): padrão SmartCut e ajuda experimental.
- [Seleção espacial Android](/D:/Projetos/SIG/app/src/main/java/br/gov/sp/pcsp/launcher/FfmpegPreviewSelection.kt): `cropPixels` e `cropFilter`.
- [Remux Android](/D:/Projetos/SIG/app/src/main/java/br/gov/sp/pcsp/launcher/FfmpegOutputRemuxer.kt): seleção e resultado do remux final.
- [Corte Android](/D:/Projetos/SIG/app/src/main/java/br/gov/sp/pcsp/launcher/FfmpegCutActivity.kt): política de áudio e áudio por trecho.
- [Painel Windows](</D:/Projetos/SIG Windows/src/ffmpeg_tools_panel.py>): `_smartcut_segment_arguments`, `_insert_smart_worker` e `_insert_full_reencode_arguments`.

As referências on-line estão junto às afirmações técnicas pertinentes. Os comandos são modelos para uma futura execução controlada.

## 15. Execução explícita nos dois aplicativos

### 15.1 Cada teste tem dois resultados, não uma aprovação genérica

Para cada Txx aplicável, criar registros `Txx-Windows` e `Txx-Android`. Acrescentar o backend quando necessário: CPU, NVENC, QSV, AMF ou MediaCodec concreto. Não é preciso possuir todos os fabricantes para começar; registrar exatamente quais foram exercitados.

| Etapa | SIG Windows | SIG Android |
|---|---|---|
| Gerar referências sintéticas | Pode gerar o corpus no computador | Recebe cópias das mesmas entradas, com identidade conferida |
| Exercitar produto | Executar o pipeline real Windows | Executar o pipeline real Android, incluindo salvamento final |
| CPU | Testar o encoder de software efetivamente disponível | Testar o encoder de software embarcado efetivamente disponível |
| Hardware | Usar o backend disponível na máquina | Usar aparelho físico e codec concreto disponível |
| Analisar saídas | Probe, hashes, correlação e inspeção | Pode transferir a saída final ao Windows e usar o mesmo analisador |
| Validar reprodução | Player do app e player-alvo Windows | Player do app e player-alvo Android |
| Validar recursos/cancelamento | Processo, destino e sistema de arquivos Windows | Ciclo de vida, armazenamento e comportamento no aparelho |

**Rodar no Windows o comando que o Android exibiu não é executar o teste Android.** Isso é uma reprodução auxiliar que muda binário, backend e ambiente. É útil para isolar causas, mas a aprovação Android precisa incluir o app e seu artefato final.

O emulador ajuda em UI, regras e caminhos de software disponíveis. Não comprova MediaCodec do telefone nem comportamento térmico, desempenho sustentado ou armazenamento de todos os aparelhos físicos.

### 15.2 Distribuição dos testes por plataforma

| Testes | Execução Windows | Execução Android | Comparação entre apps |
|---|---|---|---|
| T01/T02 — duração e política AAC | Reproduzir os modos e a política atual; usar como referência adicional | Reproduzir e avaliar variante AAC no modo preciso | Conteúdo temporal contra a fonte e entre as saídas |
| T03/T04 — inventário e perfis | Conferir cada saída final, mesmo sem o helper Android | Exercitar remux real e conferir temporário/final | Mesmas faixas e perfis conforme a seleção |
| T05 — crop | Seleção real pelo app; diagnóstico isolado do filtro | Seleção real pelo app; conferir coordenadas exibidas | Mesma região visual, além da mesma dimensão |
| T06/T07/T09 — SmartCut | Pipeline atual CPU e hardware quando disponíveis | Pipeline atual CPU e hardware quando disponíveis | Mesmo intervalo e nenhuma falha de emenda; pixels copiados contra a fonte |
| T08 — Inserir | Cópia/Smart Insert/integral disponíveis | Modo integral examinado; documentar modos ausentes | Comparar modos equivalentes e explicitar diferenças de capacidade |
| T10/T11 — SmartJoin | Junções e transições reais | Junções e transições reais | Duração, conteúdo e efeito equivalentes |
| T12 — tempo especial | VFR/fracionário/offset real no pipeline | Mesmas entradas no pipeline Android | Preservação temporal ou rejeição explícita |
| T13/T14 — HDR/HEVC/fallback | Provar capacidade ou limitação do backend Windows | Provar capacidade ou limitação do backend Android | Mesma política de preservação; capacidades podem diferir |
| T15 — UI/preview | Exercitar controles e preview Windows | Exercitar controles e preview Android | Mesmo significado das escolhas |
| T16/T17 — extrair/limpar | Medir comportamento atual Windows | Medir comportamento atual Android | Mesma operação pretendida; divergências atuais registradas |
| T18 — preset | Benchmark CPU Windows | Benchmark CPU no aparelho | Mesmo perfil candidato, tempos não precisam coincidir |
| T19 — falha/cancelamento | Casos seguros no ambiente Windows | Casos seguros no ambiente Android | Mesmo estado final, originais intactos, nenhuma falsa conclusão |
| T20 — extras/rotação | Artefato e reprodução Windows | Artefato e reprodução Android | Conteúdo preservado e interpretação visual coerente |

No T02, não há necessidade de modificar o Windows apenas para tornar o experimento simétrico. Sua política AAC existente pode servir de referência, e uma variante isolada de cópia pode ajudar no diagnóstico. Comparação justa significa controlar as variáveis relevantes, não forçar alterações sem finalidade.

### 15.3 Pacote mínimo de resultados para a primeira revisão

**Primeira devolutiva recebida.** A tabela abaixo registra resultados relatados pelo desenvolvimento; nesta revisão não reexecutei os comandos nem inspecionei os artefatos brutos.

| Teste/caso | Windows observado | Android observado | Resultado esperado | Evidências | Conclusão provisória |
|---|---|---|---|---|---|
| T01, corte preciso | 3,20 s; contagem 79–80 | 3,62 s; vídeo começa em PTS 0,442 e áudio inclui conteúdo anterior | Conteúdo do intervalo conforme regra | Rodada A, T01 | Corrigir Android; discriminar contagem Windows |
| T02, variante AAC | Política existente como referência | Comando variante: 3,23 s e 80 quadros | Eliminar prelúdio sem omitir conteúdo | Rodada A, T02 | Candidata sustentada; completar fim do áudio e integração |
| T03, remux | 1 vídeo + 2 áudios | 1 vídeo + 1 áudio | Faixas selecionadas preservadas | Rodada A, T03 | Corrigir remux Android |
| T04, perfis distintos | Faixa B vira mono/44,1 kHz | Cópia preserva perfis; entrega perde faixa | Perfil próprio por faixa | Rodada A, T04 | Corrigir Windows; prevenir regressão AAC Android |
| T05, origem y=87 | Resumo reprova; filtro entrega linha 86 | Confirmação 87 e saída real 86 | Região anunciada na UI | Rodada A, T05 | Corrigir contrato espacial nos dois |
| T06, miolo SmartCut | 50/50 iguais, 80 totais | 50/50 iguais, 80 totais | Corpo e emendas sem omissão/repetição | Rodada A, T06 | Sustenta modo na classe; áudio/lead separados |
| T08, Linear 0,2 s | Integral 11,60 s; Smart Insert 12,03 s | Builder integral usa crossfade; sem duração publicada | Mesmo rótulo representa mesmo efeito | Rodada A, T08 | Corrigir semântica Windows; completar medida Android |

Para a próxima devolutiva, enviar os complementos da seção 18 e as regressões das mudanças que forem implementadas com autorização.

## 16. O que recomendo mudar em cada aplicativo para convergir

**Esta seção é uma proposta explícita de mudanças, não autorização de implementação. Os testes são a prioridade atual.** Cada linha diferencia uma inconsistência já identificada de uma decisão que depende de medição ou escolha de produto.

### 16.1 Primeiras mudanças propostas

| Tema e padrão pretendido | O que mudar no SIG Windows | O que mudar no SIG Android | Testes que devem orientar/acompanhar |
|---|---|---|---|
| Corte preciso com política de áudio efetiva | Manter AAC/cópia; completar a evidência de 79–80 quadros e corrigir seleção se houver quadro ausente | Implementar política AAC precisa sustentada por T02, com seleção e perfil por faixa; conservar cópia como opção aproximada explícita | T01/T02/T04/T15 e R1/R2 |
| Preservar a seleção de faixas até a entrega | Manter rota aprovada em C3; ampliar somente conforme mudança/risco | Corrigir perda reproduzida: mapeamento explícito da seleção no remux e validação do inventário entregue | T03 e R3 |
| Parâmetros de áudio por faixa | Corrigir defeito reproduzido: substituir aplicação global do primeiro perfil por inventário e parâmetros próprios de cada faixa | Preservar perfis ao implementar AAC; não copiar a abordagem global defeituosa do Windows | T04 e R3 |
| Mesma semântica de transição | Renomear o efeito atual do Smart Insert para indicar fade somente no inserido, ou implementar crossfade real quando essa for a escolha. Não manter nomes indistinguíveis para operações diferentes | Alinhar nomes de fade/crossfade ao efeito real do modo integral; não adicionar Smart Insert apenas para igualar a lista de opções | T08/T15 |
| Crop anuncia coordenadas efetivas | Conferir x/y além de largura/altura; ajustar o retângulo e o resumo quando houver alinhamento | Ajustar `cropPixels`/UI para representar o alinhamento de x/y, se essa for a política escolhida; não corrigir só dimensões | T05 |
| HDR/10-bit sem conversão oculta | Detectar perfil e capacidade do backend efetivo; impedir saída anunciada como preservada quando houver redução | Detectar perfil antes da rota que força formato incompatível; bloquear essa recodificação ou oferecer conversão explícita | T13 |
| Sucesso só após resultado válido | Acrescentar verificações focais onde os testes mostrarem lacunas de entrega | Reforçar condição de sucesso no fluxo de remux/entrega, preservando temporário até a validação necessária | T03/T19 |

Para crop, o alvo inicial que prefiro é alinhar visivelmente à grade suportada. Se o produto exigir coordenada ímpar exata, então a mudança deve ser feita nos dois caminhos com `exact=1` e validação de filtro/encoder. Não misturar políticas silenciosamente.

### 16.2 Modos inteligentes: mudanças condicionadas aos testes

| Tema | SIG Windows | SIG Android | Critério para implementar |
|---|---|---|---|
| SmartCut como padrão | Manter experimental, com T06 aprovado para H.264/CFR testado | Manter experimental, com T06 aprovado no emulador/classe testada | Complementar T07/T09 e R4; não tratar emulação como prova MediaCodec |
| Lead temporal nas emendas | Preservar núcleo de cópia aprovado; medir se lead é apenas deslocamento comum ou atraso percebido | Mesma decisão; sem ajuste cego de `-t` | R4 decide se basta documentar ou se é necessária correção de apresentação |
| Áudio contínuo | Prototipar uma passagem como comparação controlada, se houver problema/benefício | Implementar o mesmo resultado depois de validada a estratégia; não precisa usar o mesmo contêiner de trabalho | T07 demonstra benefício ou necessidade |
| SmartJoin e pontes | Preservar planejador existente; corrigir sobreposições, duração ou perfil que reprovarem | Fazer as mesmas regras de plano convergirem; manter execução específica do backend | T10/T11/T12 |
| Referência de perfil na junção | Mostrar clipe/perfil escolhido e permitir decisão previsível | Mesmo resumo e mesma política de referência | Perfil selecionado deve concordar antes do encode |
| Smart Insert equivalente | Decidir se conserva o modo aproximado atual com nome claro ou cria emendas semanticamente equivalentes | Só adicionar modo inteligente quando houver requisito e estratégia validada; manter integral com semântica comum | T08 e benefício medido de preservar partes |
| Preview de transição | Usar duração/efeito do plano efetivo | Usar duração/efeito do plano efetivo; distinguir navegação das fontes de preview do resultado | T15 |

**Padronizar o significado não exige implementar imediatamente todos os modos em ambos.** Na primeira etapa, um modo disponível apenas no Windows pode continuar existindo, desde que seja identificado como capacidade adicional. Se a exigência posterior for também igualdade completa de funcionalidades, portar os modos ausentes passa a ser uma entrega explícita.

### 16.3 Extração, limpeza, desempenho e suporte de formatos

| Tema e decisão proposta | SIG Windows | SIG Android | Dependência |
|---|---|---|---|
| Extrair original vs converter | Adicionar/usar caminho explícito de cópia para extração original e seleção de faixa equivalente | Tornar cópia automática e controles coerentes com a intenção; impedir bitrate fictício em cópia | T16 |
| Perfis de extração | Mesmo conjunto de nomes e parâmetros para original, conversão, transcrição e compacto | Alinhar aos mesmos perfis, conservando seleção de faixa | T16 e definição dos perfis |
| Limpeza Equilibrada | Preservar algoritmo atual, conferir perfil de saída | Preservar algoritmo atual, alinhar perfil de saída | T17 |
| Limpeza Forte | Manter como referência candidata, sem declarar superioridade só pelo nome do teste | Avaliar trocar `anlmdn` pelo perfil `afftdn` comum ou expor os dois algoritmos por nomes distintos | T17 decide; não trocar algoritmo antes da audição |
| Saída de limpeza | Oferecer claramente preservar taxa/canais vs preparar para transcrição | Oferecer os mesmos perfis, sem conversão oculta | T17/T15; padrão genérico proposto: preservar taxa/canais |
| Normalização de volume | Não acrescentar silenciosamente; corrigir ajuda se prometer etapa inexistente | Mesma regra | Decisão separada de produto, não necessária à primeira entrega |
| Preset CPU | Trocar `medium` pelo candidato comum somente após medição; manter qualidade separada | Trocar `ultrafast` pelo candidato comum somente após medição | T18; candidato inicial a testar: `fast` |
| Qualidade por hardware | Calibrar para objetivo comum, preservando controles próprios do backend | Revisar fatores, inclusive HEVC, conforme qualidade medida | Teste dirigido de detalhe visual; bitrate nominal não basta |
| HEVC e trecho curto | Preservar codec no fallback e respeitar perfil | Evitar que regra de 3 s imponha H.264 quando HEVC hardware é a alternativa válida | T14 |
| x265 Android | Nenhuma mudança necessária por este motivo | Não incluir inicialmente; só estudar se houver exigência de recodificação HEVC sem hardware | Requisito específico e estudo técnico futuro |
| Contêineres intermediários | Manter os que a rota exige e passam os testes | Manter MP4/MKV conforme a rota exige e passa os testes | Resultado final conforme seleção, T01/T03 |
| Capítulos/legendas/anexos | Recalcular ou omitir explicitamente conforme operação; verificar seleção final | Mesma política; não copiar capítulos temporalmente inválidos | T20 |

### 16.4 O que significa “padronizar tudo” nesta proposta

O objetivo final é que a mesma escolha de operação, perfil, faixa e transição tenha o mesmo significado e produza conteúdo equivalente nos dois aplicativos. Isso inclui seleção de tempo, crop, orientação, áudio, extras, mensagens de conversão e tratamento de falhas.

Não há recomendação de forçar igualdade de encoder físico, velocidade, tamanho exato do arquivo ou pixels de toda região recodificada. Também não proponho alterar o Windows em uma rota já correta apenas para torná-la internamente igual ao Android, ou manter no Android uma limitação silenciosa para imitar o Windows.

Os testes dirão se a correção cabe só em um aplicativo, precisa ocorrer nos dois ou exige uma decisão de produto antes da implementação. Uma divergência de código não implica automaticamente duas correções.

## 17. Como os resultados futuros devem revisar este parecer

Os resultados podem ser enviados por rodada; não é necessário esperar os vinte testes. Para cada caso, trazer o resumo da seção 11 e, quando possível, comandos reais, probes e arquivos sintéticos de entrada/saída.

Na revisão, cada recomendação deve receber um estado:

| Estado após os testes | Ação no relatório |
|---|---|
| Hipótese confirmada | Converter em diagnóstico, indicar plataforma/rota e correção necessária |
| Hipótese refutada | Retirar ou reescrever a recomendação correspondente |
| Correção candidata aprovada | Registrar quais casos passaram e os limites da cobertura |
| Resultado inconclusivo | Pedir apenas a medida adicional necessária para decidir |
| Divergência intencional aceitável | Documentar o significado e a mensagem ao operador |
| Nova falha | Acrescentar caso de regressão e ajustar prioridade |

A Rodada A já produziu essa revisão: AAC e remux deixaram de ser hipóteses, crop foi reprovado, SmartCut ganhou aprovação do miolo no caso avaliado e Windows recebeu correções próprias por T04/T08. O mesmo critério deve continuar nas próximas rodadas: incorporar resultados favoráveis e desfavoráveis, delimitando o alcance de cada prova.

## 18. Análise da Rodada A e plano atualizado de verificação

### 18.1 Origem das evidências e última conferência de código

Fonte de execução: desenvolvimento do SIG, conforme [Rodada A](/D:/Projetos/SIG/docs/rodada-a-resultados-20260913.md), Android em emulador Pixel_9 e Windows no PC. O autor informa que nenhuma implementação foi alterada e que variantes foram avaliadas por comandos e artefatos. Esta revisão aceita esses resultados como evidência fornecida, sem apresentá-los como experimentos executados pelo assistente.

Na última leitura local, Android estava no HEAD `6714260de9f303bbc322512699f37713f3f9215a`; Windows no HEAD `b549e60b42e03d22f5b45e91af7996098b8e2b64`, com alterações locais no painel e arquivos de teste/UI. Esses são estados da conferência, não identificação presumida dos builds usados na Rodada A. O relatório de execução não relaciona hashes de APK/binários/saídas às linhas de resultado.

Conferi novamente:

- Android: o corte preciso ainda recebe argumentos de cópia geral e sobrescreve vídeo, sem política AAC equivalente no caminho examinado; o remux não contém `-map`; o crop mantém x/y potencialmente ímpares.
- Windows: `_cut_video_precise` mapeia todas as faixas de áudio, mas aplica taxa/canais/bitrate globalmente; `_insert_smart_worker` aplica `afade` somente no inserido; o caminho integral usa `acrossfade` para curvas correspondentes; o filtro crop também não ativa `exact=1`.

As observações de código corroboram os mecanismos descritos. Não houve correção funcional nesta revisão; o anexo de resultados foi preservado como recebido.

### 18.2 AAC: diagnóstico aceito, precisão final ainda precisa de fechamento

**Aceito o resultado principal de T02:** trocar o áudio copiado por AAC removeu o grande prelúdio no cenário avaliado. Não mantenho a objeção anterior de ausência de experimento causal para essa mudança.

Há três detalhes a esclarecer para chamar a saída de integralmente precisa:

1. Os bipes da fonte em 1,5/2,5/3,5/4,5 s pertencem ao corte [1,4;4,6). Sem deslocamento residual, suas posições são 0,1/1,1/2,1/3,1 s. A tabela AAC informa somente 0,12/1,12/2,12. O quarto deveria aparecer perto de 3,12 s sob o mesmo deslocamento. A omissão da linha pode ser abreviação; se for ausência real, a cauda do áudio precisa de correção.
2. A diferença entre início da fonte 1,0 e pedido 1,4 é 400 ms; o primeiro PTS de vídeo reportado é 442 ms. Não tratar os 42 ms adicionais como conteúdo anterior comprovado sem analisar arredondamento/padding/origem. Isso refina a quantificação, sem negar o prelúdio defeituoso.
3. A variante termina em 3,23 s, não 3,20 s. Os aproximadamente 20 ms de deslocamento dos marcadores e os 30 ms de duração excedente são medidas distintas. Podem ser compatíveis com delay/padding, mas a aceitação precisa dizer como vídeo e áudio são apresentados, não simplesmente classificá-los como “normais”.

O teste sem `make_zero` é útil e afasta a remoção isolada dessa flag como solução do caso. Duração igual sozinha não demonstra que todos os PTS ficaram iguais, nem que outras políticas do muxer não normalizaram timestamps. Não recomendo perseguir essa flag como causa principal agora, tampouco declará-la irrelevante em qualquer rota.

Mover `-ss` para depois da entrada produziu uma duração favorável na variante, mas não foram publicados marcadores/identidades equivalentes. Prefiro a política AAC já isolada como primeira candidata; não trocar simultaneamente posição de seek e codec de áudio.

### 18.3 “79–80” não encerra precisão quadro a quadro

O relatório declara Windows preciso como exato e fornece contagem de 79–80; Android atual tem 79, enquanto a variante AAC tem 80. Se essas quantidades foram medidas por métodos distintos, registrar qual é a contagem de quadros efetivamente decodificados de cada arquivo.

Para a fonte com os PTS descritos, esperamos os índices 35 a 114, inclusive, totalizando 80 quadros (indexação da fonte iniciada em zero). Verificar identidade do primeiro e último, ordem e ausência de duplicação. O fato de um encode ter 80 quadros é forte, mas não prova sozinho que não duplicou um quadro para compensar outro ausente.

Não estou declarando um novo defeito Windows a partir do intervalo “79–80”. A documentação da contagem precisa ser fechada antes de certificar precisão integral. T06, ao contrário, traz plano e correspondência mais específicos e sustenta sua aprovação de conteúdo.

### 18.4 SmartCut: miolo aprovado; lead deve ser avaliado separadamente

T06 resolveu a limitação da prova anterior: agora há 50 quadros internos identificados, com correspondência constante, dentro de um plano 15+50+15. Aceito a aprovação dos pixels decodificados do miolo e da continuidade de quadros relatada no caso.

Não uso “bitstream integralmente idêntico” como sinônimo: a evidência informada compara quadros decodificados. Isso já é uma prova útil de preservação visual do corpo, sem exigir uma análise adicional de payload comprimido para avançar nas correções atuais.

A causa do excesso foi atribuída pelo desenvolvimento ao lead do primeiro TS. A 25 fps, dois períodos de quadro somam 80 ms; 90/100 ms incluem aproximadamente mais 10/20 ms ou arredondamento. O relatório não publica uma tabela de PTS de todos os streams que decomponha essa diferença.

**Decisão:** manter o modo e seu núcleo de cópia. Se o lead for apenas um deslocamento comum e os players-alvo apresentarem conteúdo imediatamente, documentar a duração do contêiner versus duração útil pode bastar. Se houver imagem parada, silêncio, atraso relativo A/V ou conteúdo extra perceptível, a apresentação precisa ser corrigida. Ter o mesmo comportamento nos dois aplicativos não torna um atraso percebido automaticamente aceitável.

Não aplicar `-t` indiscriminadamente: o miolo está aprovado e a seleção não deve perder quadros para fazer a duração declarada parecer correta. T07 decide se há justificativa para priorizar áudio contínuo.

### 18.5 Sem Reencode ainda precisa de uma regra comum de aproximação

Windows entregou aproximadamente 80 quadros/3,22 s e Android 90 quadros/3,62 s, com início perto de 1,0 s no Android. Aproximação pode fazer parte do modo, mas essa diferença mostra que o comportamento temporal dos aplicativos ainda não está completamente padronizado.

Minha escolha é um contrato comum que **mostre início/fim efetivos e extensão do material adicional antes de concluir a operação**. Para uma política de preservar o intervalo solicitado ampliando até pontos copiáveis, os dois devem adotar os mesmos limites lógicos quando a estrutura da origem permitir. Confirmar primeiro o conteúdo inicial/final Windows, não publicado nesse caso, antes de prescrever qual limite deve mudar.

Não reprovar o Android apenas por não recodificar o trecho entre 1,0 e 1,4; não aprovar equivalência Windows/Android apenas porque os dois modos dizem “aproximado”. Se a pessoa quiser o intervalo exato, encaminhar para o modo preciso.

### 18.6 T03/T04: duas perdas diferentes, correções diferentes

**Android perde uma faixa na entrega. Windows mantém as faixas, mas altera indevidamente o perfil da segunda.** Essa distinção deve constar do diagnóstico e dos testes de regressão.

No Android, implementar AAC com `-ar/-ac` globais copiados do Windows poderia corrigir T01 e introduzir o defeito de T04. A primeira correção precisa usar o inventário por faixa. No Windows, corrigir os parâmetros por faixa no corte preciso; conferir os caminhos que reutilizam o primeiro perfil, como as bordas SmartCut, antes de afirmar que toda a família de ferramentas foi corrigida.

Depois, executar C3b com ambas as faixas no arquivo **final**, e validar conteúdo distinto nos dois canais da faixa estéreo. Contar canais e faixas sem conferir o sinal pode deixar passar um estéreo apenas nominal ou uma faixa duplicada.

### 18.7 Crop: defeito suficiente para agir, corpus pode ficar mais forte

T05 refutou o argumento de que dimensões pares dispensam atenção às coordenadas. A distinção luma 192/224 e o contexto do filtro sustentam o arredondamento entre 86 e 87.

O padrão `(linha%8)*32` se repete a cada oito linhas; portanto, não identifica de forma única toda linha da imagem. Ele distingue bem 86 de 87, mas não descarta sozinho um deslocamento de oito linhas. Para a regressão futura, acrescentar identificação de blocos ou um padrão bidimensional que preserve a distinção de linhas/colunas e evite essa ambiguidade. Não é necessário invalidar a conclusão atual por isso.

Verificar também x ímpar, crop após giro e a mesma operação no app Windows. Para `exact=1`, conferir os planos de cor e o encoder real, pois a prova atual descreve principalmente a primeira linha de luma.

### 18.8 Smart Insert: corrigir nome já; investigar 30 ms separadamente

Aceito a divergência reproduzida no Windows. A diferença observada de 430 ms pode ser decomposta em **400 ms esperados pela ausência de duas sobreposições de 200 ms, mais 30 ms de resíduo**. Não atribuir os 430 ms inteiros ao significado de “Linear”.

C5 é descrito como PCM; o relatório não publica o codec/contêiner da saída de Smart Insert. Se a saída também for PCM, “atraso normal de AAC” não explica o excedente. Verificar duração real em amostras das entradas, cortes das partes e concat. O ponto de partida para um concat sem sobreposição é a soma real das amostras, não o arredondamento textual “10 s + 2 s”.

Minha primeira mudança Windows seria identificar o efeito existente como “Fade no áudio inserido — curva linear”, reservando “Crossfade linear” para a sobreposição real. Se a intenção do produto exigir o mesmo efeito nos modos, implementar isso depois com os mesmos critérios de duração. Android deve manter a semântica de crossfade do integral e fornecer uma saída medida de 11,60 s no mesmo caso antes de declarar sua aprovação funcional completa.

### 18.9 Complementos curtos e regressões — próxima rodada recomendada

Os primeiros complementos podem ser obtidos dos artefatos já guardados. Não é necessário gerar novo corpus para todos eles.

| ID | Pergunta/ação | Windows | Android | Evidência e critério |
|---|---|---|---|---|
| R1 — cauda AAC | Localizar os quatro bipes; medir primeiro/último áudio útil e offset A/V | Usar corte preciso como referência medida | Usar variante AAC e, após correção, saída real do app | Quatro marcadores esperados 0,1/1,1/2,1/3,1 mais deslocamento declarado; último presente, nenhuma cauda indevidamente removida |
| R2 — quadros precisos | Publicar contagem única e identidade das pontas por arquivo | Esclarecer 79–80 | Esclarecer 79 atual versus 80 AAC | Para a fonte confirmada, 80 quadros de índices 35–114, sem duplicação/omissão |
| R3 — AAC + remux + multifaixa | Integrar os problemas de T01/T03/T04 no mesmo cenário | Após correção, A mono/44,1 e B estéreo/48 sem imposição global | Após correções, entregar ambas em AAC com perfil individual | Inventário final completo, perfis corretos, sinal distinto nos canais; testar também destino incompatível |
| R4 — lead SmartCut | Separar deslocamento de contêiner de atraso percebido | PTS de cabeça, corpo, concat e final; marcadores no player | Mesmas medidas no pipeline Android | Nenhuma dessincronia/pausa extra; 80 quadros permanecem. Se deslocamento comum apenas, registrar claramente |
| R5 — crop final | Vincular saídas dos dois apps e verificar política escolhida | Confirmar artefato Windows com seleção/diálogo | Repetir após ajuste da seleção ou rota exata | Coordenadas mostradas iguais à região salva; x/y ímpares e giro; crominância quando aplicável |
| R6 — inserção | Medir integral Android e localizar 30 ms do Smart Insert Windows | Contagem de amostras e codecs por parte; rótulo novo coerente | Artefato integral no caso 10+2 com duas transições | Crossfade 11,6 s de referência; concat soma real das amostras; qualquer resíduo explicado |
| R7 — cópia aproximada | Publicar fonte inicial/final e início efetivo de cada saída | Identidade das pontas ausentes da tabela atual | Confirmar 90 quadros e recuo relatado | Mesma política de limites e UI coerente, sem prometer precisão arbitrária |
| R8 — ambiente/escopo | Relacionar resultados aos builds/encoders | Binário FFmpeg, HEAD e alterações locais | Hash do APK, FFmpeg, emulador/codec; contexto de execução de T02 | Uma identificação por arquivo de resultado; teste no aparelho físico para qualquer alegação MediaCodec |

**Ordem prática:** obter R1/R2/R4/R6 dos arquivos existentes; preparar as correções por plataforma; executar R3/R5 e os testes de UI/entrega afetados nas candidatas. Fazer R7 para fechar o contrato de Sem Reencode e R8 para consolidar rastreabilidade e expansão de hardware. O núcleo SmartCut não precisa ser reescrito para cumprir essa rodada.

### 18.10 Prioridade final de mudanças por plataforma

| Prioridade | SIG Android | SIG Windows |
|---|---|---|
| Correção funcional inicial | Política AAC no corte preciso **por faixa**, mais mapeamento/validação no remux | Perfil individual de áudio no corte preciso; evitar aplicar a primeira faixa globalmente |
| Correção de correspondência UI/saída | Crop com seleção/coordenadas efetivas; controles de áudio coerentes | Crop equivalente; nomes distintos para fade só no inserido e crossfade |
| Fechamento de aceitação | Quarto bipe, contagem/identidade de quadros, regressão multifaixa integrada | Contagem precisa, resíduo de Smart Insert e regressão multifaixa |
| Modos inteligentes | Manter SmartCut experimental na classe testada; medir lead e áudio | Mesma decisão; não alterar miolo aprovado sem motivo |
| Próxima padronização | Limites efetivos de Sem Reencode, reprodução no aparelho, perfis das outras ferramentas | Mesmos limites lógicos, medição de perfis e funcionalidades equivalentes |

**Posição atual:** há evidência suficiente para recomendar correções pequenas e específicas nos dois aplicativos. Há também evidência favorável suficiente para preservar o investimento no SmartCut. Os complementos pedidos tratam de precisão da aceitação e integração; não reabrem como hipótese a perda de faixa, o prelúdio principal, o crop ímpar ou a divergência de efeito já reproduzidos.

**Esta revisão incorpora testes executados e relatados pelo desenvolvimento; não os reexecuta, não certifica classes não testadas e não altera o comportamento dos aplicativos.**
