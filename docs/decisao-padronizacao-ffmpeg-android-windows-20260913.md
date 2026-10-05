# Decisão técnica de padronização das ferramentas de mídia — SIG Android e SIG Windows

> **Decisões atuais (14/09/2026):** consultar o [plano final de direção e implementação proposta](/D:/Projetos/SIG/docs/plano-decisao-final-midia-windows-android-20260914.md), baseado nas medições complementares enviadas pelo desenvolvimento. Este estudo permanece como histórico técnico.

> **Atualização após a Rodada A (13/09/2026):** este documento preserva o estudo inicial. Os diagnósticos e prioridades atuais estão na [revisão 2 do parecer e roteiro de testes](/D:/Projetos/SIG/docs/parecer-resposta-padronizacao-ffmpeg-e-testes-20260913.md), que prevalece quando houver conflito. A rodada confirmou o prelúdio do corte preciso e a perda de faixa no remux Android, o defeito de perfis multifaixa e a semântica divergente do Smart Insert Windows, e o problema do crop ímpar. O SmartCut passou na preservação dos 50 quadros do miolo do caso avaliado nos dois apps; permanece recomendado como padrão experimental nessa classe. Não houve implementação nesta atualização.

**Data:** 13/09/2026  
**Natureza:** relatório de decisão e especificação proposta; não é implementação nem certificação de funcionamento.  
**Escopo:** Cortar, Extrair áudio, Girar, Juntar, Inserir áudio, Limpar áudio, preview, encoders e processamento de arquivos.  
**Critério:** previsibilidade, preservação do conteúdo, precisão temporal e robustez. Questões jurídicas não integram esta análise.

## 1. Minha recomendação

**Adotar um contrato de comportamento comum, com implementações específicas por plataforma.** O padrão deve dizer o que entra, o que sai, o que pode mudar e como comprovar o resultado. Não deve obrigar Windows e Android a executar comandos idênticos.

Eu não escolheria simplesmente “o Windows está certo” ou “o Android está certo”. Há decisões melhores em cada um e problemas potenciais compartilhados. A prioridade deve ser corrigir a definição de precisão, a preservação de faixas no arquivo final e as conversões implícitas. A troca de preset vem depois.

As decisões que recomendo são:

1. **Corte exato:** vídeo com limites definidos por apresentação dos quadros; áudio processado em uma passagem contínua, com recorte por amostras e formato de saída explícito. AAC é a saída compacta usual, mas não deve ser chamado de garantia de precisão absoluta.
2. **Corte sem recodificação:** preservar os streams selecionados e declarar os limites efetivos. Não prometer o intervalo arbitrário exato.
3. **SmartCut:** otimização condicionada à comprovação de continuidade e preservação do miolo. A bateria existente ainda não demonstra isso de maneira suficiente.
4. **Qualidade e velocidade:** controles separados. Manter inicialmente a escala CRF existente; avaliar `fast` como preset comum de equilíbrio, sem misturar essa alteração com a correção temporal.
5. **Faixas e contêiner:** mesma seleção explícita nos dois apps, validada depois de todas as etapas. No padrão, manter o vídeo principal e todas as faixas de áudio; extras presentes devem aparecer no resumo antes da execução.
6. **Encoder:** escolher primeiro a capacidade de cumprir o resultado solicitado, depois a preferência de CPU/hardware. A regra de menos de três segundos não pode causar troca de codec ou perda de profundidade de cor.
7. **HEVC no Android:** manter a limitação atual inicialmente, oferecendo preservar sem recodificar quando possível, usar hardware compatível ou converter explicitamente. Avaliar x265 separadamente, por custo técnico real.
8. **Limpar e extrair áudio:** unificar perfis com significado claro; não confundir limpeza de ruído, normalização de volume, redução de canais e redução de taxa de amostragem.
9. **Entrega:** só marcar sucesso quando o arquivo final tiver o perfil esperado. Comando concluído e arquivo não vazio são verificações insuficientes.
10. **Evolução:** implantar por etapas pequenas, com os mesmos casos de teste e a mesma especificação versionada nos dois projetos.

Não existe uma escolha de parâmetros que garanta ausência de bugs em qualquer mídia. O caminho tecnicamente mais seguro é limitar o que cada modo promete e testar essas promessas de forma objetiva.

## 2. Base da análise e limites da evidência

Foram lidos os relatórios fornecidos, as instruções dos dois repositórios e os caminhos relevantes de implementação e testes. Também foi consultada documentação oficial do FFmpeg e das capacidades de codecs do Android.

**Estado examinado:**

| Projeto | Referência local | Observação |
|---|---|---|
| Android | HEAD `a79ce038c9f81c1e179996083e4481fcc7ba220c` | A bateria histórica identifica APK `cc49b0f`, portanto não corresponde necessariamente a este HEAD |
| Windows | HEAD `b549e60b42e03d22f5b45e91af7996098b8e2b64` | `src/ffmpeg_tools_panel.py` tinha alterações locais; a análise considerou o conteúdo presente no disco |

Havia outros arquivos locais alterados ou não rastreados. Eles foram preservados. Este trabalho produziu somente este relatório: não modificou código, configuração, testes, dependências ou binários dos aplicativos.

**Não foi executada uma nova bateria de mídia, nem testes unitários, build, instalação ou prova em dispositivo.** Os números de duração e hashes mencionados abaixo vêm do anexo existente, não de uma reprodução feita nesta análise. A leitura de código identifica decisões e riscos; não demonstra sozinha a manifestação de cada falha em execução.

Uso três categorias ao longo do relatório:

| Categoria | Significado |
|---|---|
| Confirmado por leitura | O caminho ou parâmetro está presente no código local examinado |
| Registrado na bateria anterior | Resultado relatado pelo anexo; não reproduzido nesta análise |
| Recomendação ou hipótese | Decisão proposta ou explicação que ainda precisa de teste |

O inventário de evidências ao final traz arquivos e símbolos para localizar as constatações. Os nomes de funções são preferíveis a depender exclusivamente de números de linha em uma árvore de trabalho que já possui alterações.

## 3. Correções necessárias na interpretação do relatório anterior

### 3.1 O corte de 3,62 segundos não pode ficar fora da decisão

O anexo registra:

| Modo | Pedido | Windows | Android | Excesso sobre o pedido |
|---|---:|---:|---:|---|
| Reencode Completo | 3,20 s | 3,20 s | 3,62 s | Android: 420 ms, aproximadamente 13,1% |
| Sem Reencode | 3,20 s | 3,22 s | 3,62 s | Windows: 20 ms; Android: 420 ms |
| SmartCut | 3,20 s | 3,29 s | 3,30 s | Windows: 90 ms; Android: 100 ms |

Esses números são durações reportadas do arquivo. Eles não dizem, isoladamente, se houve conteúdo extra, atraso inicial, diferença de duração entre streams, padding de áudio ou apenas uma forma distinta de declarar duração no contêiner.

**Decisão:** tratar a divergência de 420 ms como bloqueadora da afirmação “corte exato equivalente” até decompor a saída por stream e por timestamps. Não atribuí-la automaticamente à cópia de um pacote AAC.

No exemplo usual de AAC-LC com 1024 amostras por quadro a 44,1 kHz, um quadro representa aproximadamente 23,22 ms. Quatrocentos e vinte milissegundos não são explicados por um único quadro desse tipo. A coincidência com um recuo de seek e um deslocamento temporal é uma hipótese a investigar, não um diagnóstico fechado.

### 3.2 Resultados próximos entre plataformas podem conter o mesmo defeito

3,29 s e 3,30 s são próximos entre si, mas ambos excedem 3,20 s. A 25 fps, cada quadro representa 40 ms; 90–100 ms equivalem a aproximadamente 2,25–2,5 períodos de quadro. Isso não deve ser arredondado conceitualmente para “dois quadros, logo aceitável”.

**Decisão:** medir cada saída contra a fonte e contra o pedido, além de compará-las entre si. A hipótese de que o excesso vem do transporte MPEG-TS precisa ser provada pelos timestamps dos segmentos e do arquivo final.

### 3.3 “Miolo bit a bit” está mais forte do que a prova apresentada

O anexo informa 39/49 quadros decodificados iguais no Windows e 38/49 no Android, com deslocamento de dois quadros. Isso comprova a igualdade dos quadros que coincidiram na comparação. Não comprova todos os quadros do miolo, nem igualdade dos pacotes comprimidos.

As divergências podem realmente estar concentradas em bordas incluídas indevidamente na janela, mas é necessário identificar exatamente quais quadros pertencem à cabeça, ao miolo e à cauda. A justificativa “são transições” não substitui essa identificação.

**Decisão:** reservar “miolo preservado” para a verificação de todos os quadros de um intervalo interno conhecido. Reservar “bitstream copiado” para uma verificação do conteúdo comprimido que considere conversões de empacotamento, como AVCC/Annex B. Hash do arquivo inteiro não serve para comparar contêineres diferentes.

### 3.4 Mesmo CRF não garante a mesma imagem entre presets

O código confirma `medium` no Windows e `ultrafast` no Android. A documentação distingue `preset` e `crf` como opções próprias do encoder; os controles de outros encoders também têm significados específicos. [Documentação oficial dos encoders](https://www.ffmpeg.org/ffmpeg-codecs.html#libx264_002c-libx264rgb).

Eu retiraria a afirmação categórica “arquivo menor com a mesma qualidade”. A troca muda decisões de codificação. É razoável esperar diferenças de eficiência, tempo e tamanho, mas é necessário medir a fidelidade no material usado pelo SIG. Os tamanhos de saídas com pipelines e durações diferentes não isolam o efeito do preset.

### 3.5 `make_zero`, rotação e capítulos não são cosméticos

`avoid_negative_ts=make_zero` desloca timestamps; não é um filtro de recorte. O concat demuxer também depende da compatibilidade dos streams e das durações utilizadas para posicionar o próximo arquivo. [Documentação oficial de formatos](https://ffmpeg.org/ffmpeg-formats.html).

**Decisão:** tratar timestamps como parte do contrato funcional. Tratar orientação como conteúdo visual e capítulos/timecodes como informações temporais que precisam continuar corretas após a edição.

### 3.6 O Android não usa MKV universalmente por causa do hardware

`FfmpegOutputRemuxer.intermediateVideoExtension` seleciona MP4 quando há recodificação com encoder `*_mediacodec`. O próprio arquivo explica que isso permite finalizar os pacotes AVCC/HVCC do hardware antes de converter o contêiner. Outros caminhos trabalham com MKV.

**Decisão:** documentar o intermediário por rota, encoder e muxer. Não uniformizar todos os caminhos em MKV nem retirar todos os remuxes com base na descrição resumida anterior.

### 3.7 “Todos os streams” ainda não é uma descrição exata do Android

`cutMappedCopyArguments` mapeia `0:v:0?`, todas as faixas de áudio e streams de legenda, dados e anexos. Isso mantém somente o primeiro stream de vídeo, não todos os vídeos possíveis. Além disso, o remux final examinado não possui `-map` explícito.

Sem seleção manual, o FFmpeg utiliza seleção automática; `-c copy` define o tratamento do stream selecionado, não seleciona todos os streams. [Seleção de streams no FFmpeg](https://ffmpeg.org/ffmpeg.html#Stream-selection).

**Decisão:** substituir a promessa genérica por um inventário verificável do que será mantido e do que chegou ao arquivo entregue.

## 4. Contrato comum: o que “padronizado” deve significar

### 4.1 Igualdades obrigatórias

Para a mesma entrada, a mesma operação e o mesmo perfil, os dois aplicativos devem concordar em:

| Dimensão | Regra proposta |
|---|---|
| Intervalo | Mesma convenção de início/fim e mesmo arredondamento para quadros/amostras |
| Conteúdo | Mesma seleção e ordem das faixas |
| Geometria | Mesma orientação visível, área recortada e proporção |
| Temporalidade | Mesmos quadros selecionados e mesma relação de sincronismo A/V |
| Perfil de saída | Mesmo objetivo de codec, resolução, cor, canais e amostragem |
| Limitação | Mesma decisão lógica quando não é possível cumprir a solicitação |
| Feedback | Mesma explicação de cópia, recodificação, descarte e conversão |
| Sucesso | Mesmos requisitos mínimos de validação do resultado |

### 4.2 Diferenças permitidas

Permitir encoder físico, número de threads, transporte intermediário, APIs de armazenamento e tempo de processamento diferentes. Permitir pequenas diferenças de tamanho e de imagem recodificada dentro de critérios de qualidade aprovados. Não exigir identidade binária entre NVENC e MediaCodec.

Não permitir que “hardware disponível” justifique reduzir silenciosamente de 10 para 8 bits, remover HDR, converter HEVC para AVC, mudar estéreo para mono, descartar uma faixa ou deslocar a timeline.

### 4.3 Prioridade das decisões

Ordem proposta para o planejador:

1. Validar entrada e intenção da operação.
2. Definir streams, intervalo e transformações.
3. Determinar o que pode ser copiado e o que exige processamento.
4. Resolver codec, profundidade de cor, canais e contêiner.
5. Escolher um encoder capaz de cumprir esse plano.
6. Aplicar preferência de velocidade e limite de recursos.
7. Mostrar as alterações materiais.
8. Executar, validar e publicar a saída local.

A preferência CPU/hardware entra depois da definição do resultado. Isso evita que uma otimização de desempenho decida inadvertidamente o formato do arquivo.

## 5. D1 — Áudio no corte: decisão recomendada

### 5.1 Separar precisão de fidelidade

Recomendo três políticas, com a primeira como padrão no corte exato de vídeo:

| Política | Comportamento | Quando usar |
|---|---|---|
| **Áudio com corte exato — AAC** | Decodifica, seleciona o intervalo, codifica uma vez em AAC; verifica apresentação final | Derivado compacto para uso cotidiano |
| **Preservar áudio comprimido** | Copia os pacotes elegíveis; informa aproximação temporal | Prioridade de evitar nova compressão |
| **Áudio sem nova compressão com perdas** | Recorta PCM e entrega PCM/FLAC em contêiner compatível | Quando a fidelidade do áudio processado importa mais que tamanho/compatibilidade |

“Sem nova compressão com perdas” não significa recuperar o áudio anterior à compressão da origem. Também não autoriza reduzir amostragem, canais ou profundidade sem declarar.

Eu mudaria o nome “Precisão máxima (AAC)”. A precisão vem do tratamento da timeline e da apresentação das amostras; AAC é uma decisão de formato. Padding, atraso do encoder e comportamento do muxer/player precisam ser considerados na validação.

### 5.2 Parâmetros de áudio não devem vir indiscriminadamente da primeira faixa

No Windows, o caminho preciso mapeia todas as faixas de áudio, mas aplica globalmente `media.audio_bitrate`, `media.audio_rate` e `media.audio_channels`. No Android, os trechos híbridos usam AAC e um bitrate de referência. Esses caminhos não constituem uma política explícita por faixa.

**Risco:** duas faixas com configurações distintas podem receber o perfil de uma delas. A existência do risco está na estrutura do comando; a ocorrência deve ser verificada com um arquivo multifaixa.

**Decisão:** criar um plano por faixa. Preservar taxa de amostragem e layout quando suportados. Qualquer redução de canais deve aparecer na escolha do operador. Preservar idioma, título, ordem e disposição padrão quando ainda forem válidos.

### 5.3 Bitrate: proposta concreta, ainda sujeita à calibração

Para AAC-LC em um perfil comum de alta qualidade, proponho inicialmente **128 kb/s mono e 256 kb/s estéreo**. Para layouts maiores, definir tabela própria somente após testes de suporte e audição; não multiplicar cegamente uma regra de estéreo.

Esses valores são candidatos de produto, não limites derivados de um ensaio desta análise. O operador pode escolher um perfil compacto. Não trataria “bitrate igual ao original” como garantia de fidelidade: reencodar AAC em AAC no mesmo bitrate adiciona uma geração de perdas, e bitrates de codecs distintos não têm equivalência direta.

### 5.4 SmartCut: áudio contínuo

Minha arquitetura preferida é processar **o áudio completo do intervalo em uma única passagem**, independentemente da segmentação híbrida do vídeo. Depois, juntar esse áudio ao vídeo já concatenado na mesma base temporal.

Isso reduz o número de reinicializações do encoder de áudio e concentra o controle de padding e sincronismo. Não recomendo apenas acrescentar AAC às bordas ou jogar `-t` em mais etapas e considerar o assunto encerrado.

É uma mudança de risco **médio/alto**, porque envolve concatenação e timestamps. Precisa de comparação com a saída atual antes da substituição.

## 6. D2 — Qualidade, preset e desempenho

### 6.1 Não vincular preset ao nível de qualidade

Eu não adotaria a opção “Máxima → medium; Econômica → ultrafast”. Ela mistura dois eixos: fidelidade e esforço de compressão. Um usuário pode querer alta qualidade rapidamente, ou um arquivo econômico com compressão mais eficiente.

**Decisão:** manter “Qualidade” na interface principal e colocar “Velocidade de processamento” no avançado.

| Controle | Proposta inicial |
|---|---|
| Qualidade H.264 por CPU | Manter CRF 16/18/20/23/26 enquanto se corrige o contrato funcional |
| Padrão de qualidade | Alta, CRF 20, com aviso de que recodificação continua sendo com perdas |
| Velocidade CPU equilibrada | Candidato comum `fast`, condicionado a benchmark |
| Velocidade CPU rápida | `veryfast` |
| Maior esforço de compressão | `medium` |
| `ultrafast` | Opção de velocidade extrema, não padrão geral |

`fast` é uma proposta razoável para testar, não uma conclusão experimental sobre o celular disponível. Até esse ensaio, eu manteria os presets existentes e informaria a diferença. Isso permite corrigir primeiro as falhas relevantes sem tornar impossível identificar sua causa.

### 6.2 Hardware não tem paridade garantida pelo bitrate

Há uma divergência adicional confirmada: no Android, `HIGH` aplica fator **0,80 para HEVC** e **1,00 para H.264**. No Windows, `_video_args` usa a tabela geral de fatores, com **1,00 para Alta**, e controles próprios de NVENC/QSV/AMF.

Portanto, a afirmação de que o bitrate de referência já está alinhado exige revisão. Além disso, mesmo fator nominal não significa mesma qualidade visual entre encoders.

**Decisão:** calibrar perfis por família de codec e backend. Enquanto não houver medição, não aprovar redução automática para 80% como sinônimo de “Alta” em material HEVC. A qualidade deve ser avaliada especialmente em texto pequeno, rostos, placas, cenas escuras, ruído e movimento.

Para conversão HEVC → H.264, não reutilizar indiscriminadamente o bitrate de origem. Para crop, evitar escalar bitrate apenas pela proporção da área: o detalhe útil pode estar concentrado na região preservada.

### 6.3 Benchmark que decide o preset

Usar os mesmos trechos SDR: fala estática, movimento, baixa luz, câmera com ruído e imagem com letras pequenas. Executar CPU com `medium`, `fast`, `veryfast` e o `ultrafast` atual, fixando o restante do pipeline.

Medir tempo, tamanho, consumo de memória, estabilidade e temperatura no Android, além de qualidade visual com referência. Avaliar CPU e hardware separadamente. Não comparar tamanho sem controlar duração, áudio e contêiner.

Aprovar o preset comum somente se a amostra mais difícil preservar os detalhes relevantes e o tempo sustentado couber no uso esperado. Métricas objetivas ajudam a encontrar regressões, mas não substituem inspeção de detalhes que podem ocupar poucos pixels.

## 7. D3 — Streams, metadados e remux final

### 7.1 Seleção comum recomendada

Padrão: **vídeo principal e todas as faixas de áudio**. Quando houver extras, mostrar um resumo de legendas, outros vídeos, anexos, capítulos e dados detectados. Oferecer “Preservar conteúdo adicional compatível” e seleção avançada por faixa.

Não recomendo descartar extras silenciosamente, nem prometer “preservar tudo” sem verificar compatibilidade. A opção deve especificar se inclui outros streams de vídeo, capa, legenda forçada e anexos.

O perfil “Compatibilidade MP4” pode excluir conteúdo incompatível, mas essa exclusão deve constar do plano antes da execução. O perfil de preservação deve preferir outro contêiner ou interromper com motivo se a preservação solicitada não puder ser cumprida.

### 7.2 Problema adicional confirmado: seleção perdida no remux

`FfmpegOutputRemuxer.remuxToOriginalContainer` monta `-i`, `-c copy`, tag HEVC quando aplicável, `faststart` e saída. Não acrescenta `-map` explícito. O arquivo de trabalho pode conter várias faixas, mas isso não garante que todas sejam selecionadas na entrega.

**Prioridade alta:** passar a seleção do plano até o último muxer. Não basta substituir automaticamente por `-map 0`: isso pode fazer falhar destinos que não aceitam todos os tipos presentes. O remux precisa saber o conjunto exigido e validar esse conjunto.

A função atualmente considera sucesso retorno bem-sucedido, existência e tamanho maior que zero, e então remove o intermediário. Recomendo validar inventário, perfil e temporalidade antes de abandonar esse intermediário. A validação pode ocorrer fora dessa função, mas precisa ser requisito do fluxo final.

### 7.3 Capítulos e timecodes

Para um corte exato, propor a transformação dos capítulos:

`novo_inicio = max(inicio_capitulo, inicio_corte) - inicio_corte`

`novo_fim = min(fim_capitulo, fim_corte) - inicio_corte`

Manter somente intervalos não vazios. Em corte aproximado, usar o início efetivo realmente adotado. Em junções e inserções, recalcular pelos segmentos e pelas transições. Não copiar índices temporais como se a timeline não tivesse sido editada.

Timecode de origem e posição no derivado têm significados diferentes. Preservar a referência original em registro separado quando não houver uma representação interna correta para a timeline transformada.

### 7.4 Metadados técnicos versus descritivos

Título, idioma e identificação de faixa podem continuar úteis. Duração, rotação, resolução, encoder e índices temporais podem ficar incorretos depois da edição. Preservar apenas o que permanece verdadeiro e recalcular o que descreve a saída.

Não considerar sucesso a cópia textual de uma tag quando os pixels ou a timeline já mudaram.

## 8. D4 — SmartCut, contêiner e precisão temporal

### 8.1 Manter a liberdade de intermediários

Recomendo preservar a arquitetura específica de cada backend enquanto se mede o resultado. MP4 intermediário para MediaCodec e MKV em outros caminhos podem continuar existindo. A exigência comum é que a última saída cumpra o plano.

**Não recomendo acrescentar `-t` ao intermediário como correção presumida.** Primeiro identificar se o excesso está no vídeo, áudio, deslocamento inicial ou informação de duração. Limitar a saída pode esconder conteúdo duplicado no início e cortar conteúdo correto no fim.

### 8.2 Definir os limites em termos verificáveis

Proposta de intervalo: **[início, fim)**, início incluído e fim excluído. O tempo digitado se refere à timeline apresentada no player, com uma origem definida; não diretamente a um timestamp bruto arbitrário do arquivo.

Para vídeo, selecionar quadros por PTS de apresentação, não pela ordem de decodificação. Quando o limite estiver entre dois quadros, adotar a mesma regra explícita nos dois apps: primeiro PTS elegível maior ou igual ao início; último PTS menor que o fim. Mostrar o intervalo efetivo selecionado. Não prometer um quadro novo em uma posição temporal que a fonte não possui.

Para áudio, converter limites em índices de amostra com aritmética definida, por exemplo teto para o índice inicial e final de um intervalo sem inclusão de amostras anteriores. Manter as taxas como inteiros e bases temporais como razões; evitar encadear arredondamentos de `Double` e milissegundos.

Quando o início útil do áudio difere do vídeo na fonte, preservar o deslocamento relativo. Normalizar cada stream isoladamente para começar em zero pode eliminar um atraso legítimo e alterar o sincronismo.

O seek anterior ao input busca um ponto acessível; a documentação diferencia o descarte do trecho anterior na recodificação e sua preservação em stream copy. Essa é uma razão para não tratar `-ss` como uma garantia universal de corte exato. [Opções temporais do FFmpeg](https://ffmpeg.org/ffmpeg.html#Main-options).

### 8.3 Critérios mínimos para SmartCut

Permitir somente quando houver regiões de acesso aleatório adequadas, codec compatível e uma estratégia validada de emenda. Um quadro sinalizado como keyframe não deve ser a única prova de independência para qualquer origem, especialmente com GOP aberto e configurações variáveis.

O perfil das bordas deve respeitar o miolo: dimensões, pixel format, profundidade, SAR, cor e características do codec necessárias à decodificação. Sem isso, compartilhar o nome H.264 ou HEVC não basta.

Se o codec ou a geometria das bordas precisar mudar, abandonar o plano híbrido e oferecer recodificação integral. Não concatenar bordas H.264 com miolo HEVC.

Para VFR, não promover o `-r` médio da origem a regra universal. Se o caminho híbrido só estiver validado para CFR, classificá-lo assim e oferecer alternativa para VFR.

### 8.4 Padrão de corte durante a migração

Até concluir as provas de borda, recomendo **Reencode Completo para o perfil que promete precisão** e SmartCut como opção de preservação parcial com estado claramente informado. Depois de aprovado por classe de mídia, SmartCut pode tornar-se automático nessas classes.

Isso não significa declarar todos os SmartCuts atuais defeituosos. Significa que as evidências apresentadas ainda não justificam uma promessa geral de precisão.

## 9. D5 — Encoders, HEVC e fallback

### 9.1 Decisão sobre x265 no Android

Eu não adicionaria x265 na primeira etapa. É uma nova dependência nativa e amplia a matriz de ABI, memória, desempenho, temperatura e formatos de pixel. Não resolve sozinho emendas, mapeamento ou timestamps.

Também não bloquearia todo arquivo HEVC. Um stream HEVC pode ser copiado ou receber alteração somente de metadados sem encoder HEVC, quando a operação e o contêiner permitem. A ausência de encoder importa quando é necessário recodificar.

Se preservar HEVC com recodificação em qualquer aparelho for um requisito obrigatório, então incluir um encoder de software passa a ser uma decisão justificável. Fazer um estudo isolado: versões/ABIs suportadas, SDR/HDR/10-bit, uso sustentado de CPU, memória máxima e duração aceitável. Só depois alterar pacote e contrato de distribuição.

### 9.2 Regra comum de fallback

| Situação | Resposta proposta |
|---|---|
| Hardware falha; CPU equivalente suporta o perfil | Repetir uma vez, registrar o motivo e mostrar encoder efetivo |
| CPU só suporta outro codec ou profundidade | Oferecer conversão explicitamente; não classificar como repetição equivalente |
| Falta espaço, permissão ou entrada corrompida | Não repetir como falha de encoder |
| Usuário escolheu encoder estrito no avançado | Respeitar o modo estrito; informar indisponibilidade |
| SmartCut exige bordas incompatíveis | Replanejar como recodificação integral, sujeito à política escolhida |
| Conversão não autorizada | Encerrar sem saída final apresentada como sucesso |

Um aviso permanente de que “hardware pode cair para CPU” é suficiente para uma repetição que preserve o perfil solicitado. Não é necessário interromper toda tarefa equivalente. Mudanças de conteúdo ou formato devem aparecer antes de serem executadas.

### 9.3 Sondagem de um quadro é apenas o primeiro nível

O Android expõe consultas para dimensões, alinhamento e combinação tamanho/taxa de quadros; isso permite uma verificação mais específica que listar o nome do codec. [VideoCapabilities](https://developer.android.com/reference/android/media/MediaCodecInfo.VideoCapabilities).

Proposta: catálogo detectado → consulta do perfil → pequeno encode com configuração real da tarefa → execução acompanhada → validação da saída. O pequeno encode deve finalizar o muxer e permitir decodificação de retorno.

Cachear por codec concreto, versão de app/FFmpeg, sistema e classe de configuração. Invalidar após atualização relevante ou falha real. Não interpretar “nome aparece na lista” como “suporta esta mídia”.

### 9.4 Trechos menores que três segundos

Manter a regra somente como heurística de custo quando a CPU cumprir o mesmo perfil. HEVC sem CPU equivalente deve continuar elegível ao hardware mesmo em trecho curto. O contrato deve prevalecer sobre o limiar.

A igualdade de saída GPU/CPU na bateria anterior não prova equivalência entre encoders: as bordas foram executadas na CPU nos dois casos.

## 10. D6 — Rotação, crop, cor e timestamps

### 10.1 Orientação como uma transformação única

Definir um espaço comum de seleção: imagem tal como aparece ao usuário, já considerando orientação e SAR. A ordem proposta é: interpretar orientação de origem → aplicar giro/espelhamento solicitados → aplicar a seleção no espaço visual correspondente.

No modo pixels, entregar orientação visual resolvida e matriz de exibição coerente, sem reaplicar o giro no player. No modo metadados, preservar pixels e alterar somente a transformação de exibição suportada.

Os comandos podem usar autorotate ou transformação manual, mas o planejador precisa ter um único responsável pela orientação. Não misturar os dois sem uma regra explícita.

O Android atual também contém seleção de área no fluxo de Girar. Portanto, a descrição “crop apenas no Windows” está desatualizada em relação ao código local. A equivalência funcional ainda depende da prova visual.

### 10.2 Caso concreto: crop com `y=87`

Os dois relatórios mostram `crop=322:162:100:87`. A documentação informa que `crop` sem `exact=1` pode arredondar coordenadas/dimensões em vídeo subamostrado. [Filtro crop](https://ffmpeg.org/ffmpeg-filters.html#crop).

Logo, ter a mesma string de filtro e resolução final não demonstra que a linha inicial é exatamente 87. É uma lacuna compartilhada da prova, não uma divergência comprovada entre plataformas.

**Decisão inicial mais conservadora:** alinhar a seleção à grade exigida pelo formato/encoder e atualizar o retângulo na tela antes da execução. Exibir as coordenadas efetivas. Se for requisito recortar exatamente em coordenada ímpar, habilitar um caminho específico com `exact=1` e validar crominância e aceitação do encoder.

Não fazer arredondamento oculto depois da confirmação do usuário. Não usar a resolução de saída como único teste: gerar imagem de referência com linhas/colunas identificadas e conferir os pixels de fronteira.

### 10.3 HDR, 10 bits e níveis de cor

O caminho MediaCodec de qualidade examinado força `yuv420p` e `-bf 0`. Isso merece uma decisão explícita quando a origem é 10-bit/HDR. Não é uma prova de preservação de toda a informação da fonte.

**Proposta:** classificar inicialmente HDR/10-bit fora do perfil comum SDR de recodificação. Permitir cópia quando aplicável. Oferecer recodificação que preserve o perfil somente se o backend tiver suporte validado; caso contrário, explicar a limitação ou oferecer conversão SDR deliberada.

Registrar matriz, primárias, transferência, faixa de níveis e profundidade quando disponíveis. Evitar conversão silenciosa limitado/completo ou mudança de SAR. O mesmo codec com outra profundidade não é preservação equivalente.

### 10.4 Alterações que devem permanecer específicas

`-vf null` pode continuar diferente quando não muda o resultado. `faststart` deve ser aplicado apenas onde faz sentido. A tag `hvc1` não é um requisito universal de todo arquivo HEVC; deve integrar o perfil de compatibilidade do contêiner e do bitstream, sem ser usada como substituto da validação.

`make_zero`, B-frames, `-r`, matriz de rotação e capítulos não entram nessa categoria de detalhes dispensáveis.

## 11. Divergências adicionais — Extrair áudio

### 11.1 Windows converte; Android pode copiar

Em `_extract_worker`, o Windows mapeia a primeira faixa e aplica taxa, canais e encoder de saída. O Android possui `canCopyAudioWithoutConversion`, que permite cópia em extração integral quando extensão/codec, taxa e canais são compatíveis.

**Consequência:** o mesmo pedido aparente pode gerar nova compressão no Windows e cópia no Android. A verificação de cópia no Android também precisa deixar claro que o bitrate escolhido não será aplicado quando não houver conversão.

**Decisão:** distinguir na interface:

| Ação | Resultado |
|---|---|
| Extrair faixa original | Cópia quando viável, sem controles de bitrate fictícios |
| Converter áudio | Aplica formato, amostragem, canais e qualidade |
| Extrair trecho exato | Aplica a política temporal por amostras; cópia aproximada é opção separada |

Como padrão de “Extrair”, prefiro extração da faixa original. Os presets de transcrição e compacto passam a selecionar conversão explicitamente.

### 11.2 Faixa de áudio

O Windows usa `0:a:0?` no caminho examinado. O Android possui seleção de faixa. Padronizar seleção visível quando houver múltiplas faixas, com identificação por idioma/título/layout. Não misturar faixas automaticamente.

Como valor inicial, usar faixa marcada como padrão quando válida, senão a primeira. Exibir a faixa escolhida. A política deve ser a mesma no player e na exportação.

### 11.3 Perfis comuns propostos

| Perfil | Especificação |
|---|---|
| Original | Codec, amostragem e canais preservados; contêiner compatível |
| Processamento sem nova perda | PCM/FLAC; sem redução automática de taxa ou canais |
| Transcrição local | WAV PCM 16-bit, mono, 16 kHz, com conversões indicadas |
| Compacto para envio | Manter inicialmente OGG/Vorbis mono 16 kHz/32 kb/s, validando os consumidores reais |
| Personalizado | Formato e parâmetros explicitamente selecionados |

WAV 16 kHz mono é um perfil de interoperabilidade proposto para o fluxo local, não um ótimo universal de todos os serviços de transcrição. Não alterar contratos de provedores STT durante esta padronização de ferramentas.

### 11.4 Lotes, durações e `.amr`

O Windows ajusta o fim do recorte à duração de cada item do lote e informa no log. Proponho a mesma política nos dois: início além do arquivo → item ignorado com motivo; fim além do arquivo → ajuste explícito no resumo; item sem áudio → ignorado. Lote parcial não deve aparecer como “todos concluídos”.

A aceitação de `.amr` é uma divergência deliberada documentada no Android, restrita à extração. Não ampliar por uma simples unificação de extensões. A capacidade real do demuxer/decoder e o objetivo da ferramenta devem decidir a aceitação. Registrar essa mudança como decisão separada se for desejada.

## 12. Divergências adicionais — Limpar áudio

### 12.1 O modo forte é outro algoritmo

Confirmado no código:

| Modo | Windows | Android |
|---|---|---|
| Equilibrado | `afftdn=nf=-25` | `afftdn=nf=-25` |
| Forte | `afftdn=nr=18:nf=-35:tn=1` | `anlmdn=s=0.00003:p=0.002:r=0.002` |

Isso é uma divergência funcional mais relevante do que `-vf null`. Não classificaria os dois “fortes” como equivalentes sem teste auditivo e de desempenho.

**Minha escolha para a primeira versão comum:** manter Equilibrado exatamente como está e usar o perfil forte de `afftdn` do Windows como candidato compartilhado, sujeito a audição. A vantagem é reduzir variação de algoritmos e facilitar a comparação. Não afirmo que seja superior ao `anlmdn`; este pode permanecer como opção avançada com nome próprio se os testes mostrarem benefício.

Não aumentar a intensidade de limpeza automaticamente porque um resultado de transcrição foi ruim. Ruído, consoantes fracas e voz distante podem ocupar regiões semelhantes; uma limpeza mais agressiva pode remover informação útil.

### 12.2 Saída padrão diferente

O Windows utiliza por padrão “Transcrição (mono, 16 kHz)” e `pcm_s16le`. O Android passa o perfil detectado da origem, inclusive uma escolha de encoder PCM. Há, portanto, diferenças de taxa, canais e potencial profundidade da saída.

**Decisão:** criar os mesmos dois perfis:

- **Escuta/processamento:** preservar taxa e canais; manter representação com precisão adequada ao processamento, sem forçar 16 bits para toda origem.
- **Transcrição local:** converter deliberadamente para PCM 16-bit/16 kHz/mono.

Prefiro Escuta/processamento como padrão da ferramenta genérica “Limpar áudio”. Um atalho “Preparar para transcrição” pode selecionar o segundo perfil. Não usar o rótulo “preservar qualidade” para um filtro que altera amostras.

### 12.3 Normalização de volume não está demonstrada

Os comandos examinados de limpeza usam os filtros de redução de ruído e não incluem um estágio explícito de normalização de loudness. Assim, a descrição anterior “reduzir ruído e normalizar” não representa integralmente esses caminhos.

**Decisão:** deixar normalização desativada no perfil inicial. Se incluída depois, oferecer controle separado, com alvo, limite de pico e medição antes/depois. Não aproveitar esta padronização para adicionar processamento de volume sem uma decisão própria.

### 12.4 Critério de aceitação

Ouvir as mesmas passagens com volume de comparação controlado: voz distante, fala sobreposta, teclado, ventilador, trânsito e silêncio. Procurar ruído musical, bombeamento e perda de sílabas. Usar transcrição como evidência complementar; texto igual não prova áudio igual, e texto diferente pode ter outra causa.

## 13. Divergências adicionais — Inserir áudio

### 13.1 Corrigir a definição da ferramenta

Os caminhos examinados nos dois projetos produzem saída de áudio, com `-vn`, e intercalam principal antes do ponto → áudio inserido → restante do principal. Não demonstram a operação descrita inicialmente de inserir uma segunda trilha em um vídeo.

**Decisão:** padronizar primeiro o comportamento existente como “Intercalar áudio”. Se o nome “Inserir áudio” for mantido, a ajuda deve explicar a sequência. Adicionar/dublar/misturar uma faixa em vídeo seria outra operação, com regras próprias de duração e vídeo; não deve surgir como efeito colateral desta padronização.

### 13.2 Modos disponíveis diferentes

O Windows expõe reencode completo, Smart Insert e cópia. O fluxo de execução examinado no Android chama `buildFullReencodeArguments`. Logo, não há paridade demonstrada dos modos de preservação parcial e cópia nessa ferramenta.

**Decisão inicial:** tornar o modo preciso de recodificação o contrato comum. Manter os outros modos Windows como capacidades adicionais explicitamente identificadas até existir implementação e prova equivalentes. Não reduzir o Windows só para esconder a diferença.

### 13.3 Transições e duração

Contrato proposto:

| Transição | Duração esperada |
|---|---|
| Nenhuma | duração principal + inserido |
| Fade sem sobreposição | mesma soma; altera somente envelope próximo da junção |
| Crossfade | soma menos as sobreposições efetivamente aplicadas |

Com principal de 10 s, inserido de 2 s, inserção no meio e duas sobreposições de 0,2 s, a saída deve durar 11,6 s. Se inserir no início ou no fim, só existe uma fronteira e a saída é 11,8 s. Esse exemplo deve ser um caso comum de teste.

A curva da transição deve ser explícita e igual. Não trocar curva linear por outra sob o mesmo nome. O ajuste para trechos curtos deve aparecer no preview e no plano de duração.

### 13.4 Limiares e preview

Há um detalhe de borda: o builder completo Windows considera lado esquerdo/direito com limiar de 0,001 s; o seam Android usa comparações contra zero. Pode haver diferenças em inserções muito próximas do começo/fim.

**Decisão:** representar posições por amostras no planejamento de áudio, evitando tolerâncias distintas dispersas. O preview deve usar a timeline resultante da mesma operação, inclusive a contração por crossfade. Sequenciar players pela duração original não basta para provar que o ouvido antecipa a saída final.

## 14. Juntar vídeos e SmartJoin

Os planejadores puros têm verificações semelhantes para codec, dimensões, fps, orientação, pixel format, SAR e perfil. Isso é uma boa base de padronização, mas não constitui uma prova completa de compatibilidade do arquivo final. Os caminhos de concat direto também possuem verificações próprias, que precisam ser reconciliadas com a mesma política.

### 14.1 Perfil de destino

Recomendo tornar o primeiro clipe da lista a referência padrão de resolução, orientação e perfil quando for necessária normalização, com resumo antes da execução. Se a lógica atual escolher outra base por otimização, preservar essa opção como “Automático” visível, informando o clipe de referência.

Não mudar a resolução de saída porque o algoritmo escolheu outro clipe sem que isso apareça na tela. Não fazer upscale automático de todos os clipes só porque um deles tem resolução maior.

### 14.2 Compatibilidade de cópia

Requerer inventário e ordem compatíveis das faixas selecionadas; verificar características de codec e parâmetros de decodificação, tempo, cor e acesso aleatório. Dados ausentes devem resultar em “compatibilidade não comprovada”, e não automaticamente em aprovação.

A política pode começar conservadora e relaxar casos específicos após testes. Isso pode levar a mais recodificação no início, mas reduz o risco de gerar arquivos que só funcionam em um player tolerante.

### 14.3 Áudio ausente e multifaixa

Se algum clipe não tiver áudio, criar silêncio somente quando o perfil da junção exigir continuidade de áudio, preservando exatamente sua duração. Se todos não tiverem áudio, não criar uma faixa vazia sem pedido.

Quando houver múltiplas faixas, usar correspondência definida por seleção e função; não unir automaticamente “faixa 2” de arquivos com idiomas ou usos diferentes. Perguntar no plano apenas quando houver ambiguidade material.

### 14.4 Transições

Sem transição, duração esperada é a soma das durações efetivas. Com sobreposição, subtrair a duração de cada transição. O vídeo e o áudio devem consumir as mesmas janelas temporais.

SmartJoin pode copiar corpos e recodificar pontes, mas cada junção precisa de verificação de imagem e som. Aprovar um clipe único ou apenas o último frame da saída não valida as pontes intermediárias.

## 15. Interface e execução: regras comuns que evitam regressões

### 15.1 Resumo antes de executar

Mostrar um plano curto, derivado da mesma estrutura usada para gerar o comando:

> Corte exato: 00:01,400 a 00:04,600. Vídeo H.264 recodificado; 2 faixas de áudio em AAC. Saída MP4, 640×360. Uma legenda ficará de fora do perfil de compatibilidade. Processamento automático, com CPU disponível como alternativa equivalente.

O exemplo é uma proposta de apresentação. Não precisa expor dezenas de parâmetros de FFmpeg para o operador. O comando detalhado continua disponível para diagnóstico.

Controles sem efeito devem ficar desabilitados ou apresentar “não se aplica”. Cópia não pode manter um seletor de qualidade que sugere alterar o resultado. Crop não pode coexistir visualmente com promessa de copiar os mesmos pixels.

### 15.2 Cancelamento e falhas

Usar estados comuns: planejado, processando, validando, concluído, concluído parcialmente, cancelado e falhou. Não marcar sucesso antes de concluir mux, validação e gravação no destino.

Gravar em temporário exclusivo. Em armazenamento local convencional, finalizar com substituição/rename seguro quando aplicável. No Android com provedor de documentos, respeitar as limitações da API e impedir que um arquivo incompleto apareça como resultado válido.

Manter originais intactos. Resolver colisões de nomes de forma previsível. Cancelamento deve impedir a publicação final e encerrar os processos da tarefa. A falta de espaço deve ser diagnosticada como tal, sem tentativa desnecessária em outro encoder.

### 15.3 Recursos de cada plataforma

Limitar concorrência de hardware por capacidade observada, não somente pelo número anunciado de instâncias. Testar execução longa no Android: o melhor tempo de um trecho frio pode não representar desempenho sustentado.

Na primeira versão comum, preferir execução serial de cada arquivo e concorrência conservadora. Paralelizar segmentos somente quando sua recomposição temporal estiver validada. Otimização não deve mudar o resultado sem alterar o contrato.

### 15.4 Relatório de operação

Registrar versão do contrato, FFmpeg/build, entrada identificada, operação, intervalos solicitados/efetivos, streams de entrada/saída, encoder planejado/real, transformações e motivo de fallback. Separar duração do contêiner, duração apresentada de vídeo e duração útil de áudio.

Isso permite explicar diferenças técnicas sem exigir que o usuário interprete logs. Não é proposta de ampliar a ferramenta para uma plataforma documental; é um resumo da própria operação.

## 16. Arquitetura recomendada para manter a padronização

Não recomendo compartilhar uma string de comando gigante entre Python e Kotlin. Recomendo compartilhar **especificação e casos de conformidade**.

### 16.1 Plano intermediário comum

Definir um esquema versionado, por exemplo com:

| Campo | Função |
|---|---|
| `contract_version` | Identificar a regra aplicada |
| `operation` | Cortar, girar, extrair, juntar, intercalar, limpar |
| `input_profiles` | Streams, codecs, tempo, geometria e cor |
| `requested_range` / `effective_range` | Distinguir pedido e seleção materializável |
| `stream_actions` | Copiar, recodificar, transformar ou omitir por faixa |
| `audio_policy` | Exato AAC, cópia ou sem nova perda |
| `video_profile` | Codec, qualidade, geometria, fps e cor |
| `container_policy` | Destino e alternativa permitida |
| `fallback_policy` | Mudanças autorizadas e modo estrito |
| `expected_output` | Inventário e invariantes para validação |

Esse é um desenho proposto, não um arquivo ou código criado nesta tarefa. Python e Kotlin devem produzir decisões equivalentes para os mesmos fixtures, mesmo quando seus comandos forem diferentes.

### 16.2 Ownership nos projetos

No Android, começar pelos seams já existentes: `FfmpegMediaPolicies`, `FfmpegVideoQuality`, `FfmpegVideoEncoders`, `FfmpegPreviewSelection` e `SmartJoinPlanner`. As Activities devem consumir o plano e controlar UI/execução.

No Windows, aproveitar `smart_join_planner.py`, `video_encoders.py` e os testes existentes; extrair apenas a regra necessária do painel quando a implementação for autorizada. Não fazer uma refatoração geral do painel para conseguir corrigir uma política de áudio.

### 16.3 Versão do FFmpeg

Registrar versões reais e opções disponíveis. A documentação on-line consultada não substitui a ajuda do binário embarcado. Não exigir atualização simultânea para a versão mais nova; exigir que as duas versões passem a mesma suíte do contrato.

Ao atualizar FFmpeg, repetir especialmente seek, mux, display matrix, codecs de hardware, filtros de áudio e BSFs. Versões iguais ajudam a reduzir variáveis, mas não igualam MediaCodec e encoders do Windows.

## 17. Matriz de aceitação proposta

Todos os testes abaixo são **trabalho futuro recomendado**, não resultados executados nesta análise.

### 17.1 Corpus mínimo controlado

| Grupo | Entradas necessárias | Problema coberto |
|---|---|---|
| Básico | H.264/AAC e HEVC/AAC, CFR, GOP conhecido | Reproduzir a bateria atual |
| Dependências | B-frames, GOP longo/aberto, início fora de keyframe | Emendas e seek |
| Tempo | VFR, 30000/1001, início não zero, offset A/V | Arredondamento e sincronismo |
| Áudio | AAC, MP3, Opus, PCM, FLAC; mono, estéreo, multifaixa | Cópia, delay, seleção e conversão |
| Geometria | Rotações 0/90/180/270, SAR não unitário, crop ímpar | Orientação e coordenadas |
| Cor | SDR, 10-bit, HDR, faixas limitada/completa | Conversões silenciosas |
| Extras | Legendas, capítulos, anexos, segundo vídeo, capa | Preservação final |
| Robustez | Arquivo sem áudio, truncado, metadados ausentes | Diagnóstico e validação |
| Escala | Clipe de poucos quadros, arquivo longo, alta resolução | Recursos e acumulação de erro |
| Operação | Cancelamento, destino sem espaço, nome repetido, URI | Entrega e recuperação |

Não é necessário testar o produto cartesiano inteiro. Cobrir os cruzamentos de maior risco: HEVC+10-bit+hardware; crop+rotação; multifaixa+remux; VFR+SmartCut; AAC+junções repetidas.

### 17.2 Medidas e limites

| Aspecto | Critério proposto |
|---|---|
| Cópia do miolo | 100% dos quadros internos esperados iguais na decodificação de referência |
| Corte de vídeo exato | Nenhum quadro anterior ao início ou com PTS a partir do fim, segundo a regra adotada |
| Áudio PCM exato | Índices e contagem de amostras correspondentes à regra; erro máximo de quantização definido em uma amostra |
| AAC/Opus | Medir apresentação útil após delay/padding; não usar só duração do contêiner |
| Sincronismo | Não introduzir novo offset persistente nem deriva; medir marcadores no início, meio e fim |
| Streams | Inventário final corresponde ao plano, inclusive idioma/ordem/disposição |
| Crop | Pixels de borda correspondem às coordenadas efetivas anunciadas |
| Orientação | Imagem correta nos players-alvo, sem giro duplo |
| Concat | Sem quadros duplicados/ausentes nas junções, nem silêncio não previsto |
| Falha/cancelamento | Nenhuma saída incompleta oferecida como concluída |

Uma tolerância prática inicial de até um período de quadro pode ser usada como alerta de sincronismo em testes visuais, mas não como licença para inserir atraso. Para áudio, usar marcadores e correlação com precisão maior; se houver offset esperado da origem, comparar a sua preservação. Duração do contêiner pode ter granularidade própria, desde que o conteúdo apresentado esteja correto e a diferença seja explicada.

Para a fonte CFR de 25 fps com PTS em múltiplos de 40 ms, o intervalo 1,4–4,6 s contém 80 PTS elegíveis. Esse caso permite uma verificação mais forte que “aproximadamente 3,2 s”.

### 17.3 Como medir

Sondar formato, streams, pacotes e quadros; guardar PTS, DTS, durações e metadados relevantes. Decodificar com o mesmo decoder de referência para comparação de pixels, separando a prova do bitstream da prova do player.

Usar vídeo sintético com número do quadro e áudio com marcadores conhecidos. No SmartCut, identificar explicitamente o intervalo copiado e alinhar por identidade/PTS, sem aceitar um deslocamento ajustado manualmente como prova suficiente.

Para trechos recodificados, verificar qualidade visual e ausência de corrupção. Para áudio com perdas, usar correlação temporal e audição; hash PCM idêntico não é critério viável após recodificação com perdas.

### 17.4 Testes existentes e lacunas

O Android já possui testes de políticas, qualidade, encoders, remux, preview/crop, modos de corte e SmartJoin. O Windows possui testes do painel, modos de corte, seleção de área e SmartJoin. Eles são bons pontos de entrada.

Adicionar fixtures comuns de decisão e testes reais de saída. Um teste que só confirma a presença de `-c:a aac` não prova corte exato; um teste que só confere `-map 0` não prova que o arquivo final preservou todas as faixas.

Na implementação futura, executar verificações adequadas a cada mudança. No Android, respeitar os gates locais; qualquer alteração no hotspot STT exige os gates e a aceitação definidos em `AGENTS.md`. Esta proposta não exige mexer nesse hotspot. Mudança nativa tem seus verificadores próprios e deve ser uma entrega separada.

## 18. Ordem de implementação recomendada

| Etapa | Conteúdo | Motivo | Condição de conclusão |
|---|---|---|---|
| 0 | Especificar contrato, registrar versões e reproduzir 3,62 s | Criar referência confiável | Causa temporal identificada por stream |
| 1 | Plano de streams e remux final explícito | Evitar perda silenciosa | Multifaixa/extras verificados na saída final |
| 2 | Corte exato e áudio contínuo | Resolver promessa central | Intervalo, amostras e sincronismo aprovados |
| 3 | SmartCut/SmartJoin por classe de mídia | Conservar cópia onde comprovada | Miolo e todas as emendas aprovados |
| 4 | Perfis de extração, limpeza e inserção | Eliminar diferenças funcionais adicionais | Mesmo plano e comportamento nos dois apps |
| 5 | Preset comum e calibração de hardware | Melhorar custo sem esconder regressão | Benchmark sustentado e qualidade aprovados |
| 6 | Recursos opcionais, como x265 Android | Ampliar cobertura com base em necessidade | Estudo técnico e aceitação nativa completos |

Não estimaria “um dia” ou “uma semana” sem reproduzir a divergência temporal. O risco principal está em integração e formatos reais, não na quantidade de linhas de um parâmetro.

Cada etapa deve ter alterações pequenas, relatório de validação e possibilidade de voltar à versão anterior do contrato. Evitar mudar AAC, preset, contêiner, crop e fallback no mesmo conjunto de alterações: uma regressão ficaria difícil de atribuir.

## 19. Quadro final de decisão

| Tema | Decisão que eu tomaria | Relação com a proposta anterior | Risco de implementação |
|---|---|---|---|
| D1 áudio preciso | AAC explícito, recorte por amostras e uma passagem; cópia e PCM/FLAC como alternativas | Concordo com política comum; rejeito AAC como garantia isolada | Médio/alto |
| D2 preset | Separar velocidade de qualidade; testar `fast`; manter CRF inicialmente | Discordo do preset vinculado à qualidade | Baixo no comando, médio na validação |
| D3 streams | Vídeo principal + todos os áudios; extras visíveis; seleção até o último mux | Concordo com seleção comum; acrescento validação final | Médio |
| D4 intermediário | Manter diferenças justificadas; investigar timestamps antes de acrescentar `-t` | Concordo em evitar reescrita; discordo de encerrar como sem impacto | Alto no caminho híbrido |
| D5 HEVC | Não adicionar x265 inicialmente; não bloquear cópia HEVC; conversão explícita | Concordo parcialmente, com regras mais precisas | Baixo sem novo pacote |
| D6 detalhes | Rotação, tempo, capítulos e cor são contrato funcional | Discordo de classificá-los genericamente como ruído | Médio/alto |
| Extrair | Original por cópia; conversão/preset explícitos; seleção de faixa | Divergência adicional | Médio |
| Limpar | Equilibrado comum, forte a calibrar, saída e normalização separadas | Divergência adicional | Médio |
| Inserir | Intercalar áudio como contrato atual; duração de transições comum | Corrige definição e evidencia diferença de modos | Médio |
| SmartJoin | Compatibilidade conservadora, base visível, áudio e transições definidos | Amplia cobertura ainda pendente | Alto |
| HDR/10-bit | Preservar ou explicar limitação; nunca reduzir silenciosamente | Lacuna adicional | Alto se ampliar suporte |
| Entrega | Validar arquivo final antes de sucesso | Requisito transversal adicional | Médio |

**A escolha ideal para o SIG é uma padronização por resultado verificável.** Eu aprovaria já as regras de transparência, inventário de faixas e perfis comuns. Condicionaria a promoção do SmartCut, a mudança global de preset e a ampliação de HEVC/HDR às provas específicas acima. A evidência atual não permite declarar equivalência completa ou ausência de bugs.

## 20. Inventário de evidências consultadas

### Android

- [Bateria comparativa anterior](/D:/Projetos/SIG/docs/bateria-comparativa-android-windows-20260913.md): comandos, durações, contagem de quadros iguais e cobertura declarada.
- [Relatório anterior de divergências](/D:/Projetos/SIG/docs/relatorio-divergencias-android-windows-ffmpeg-20260913.md): proposta submetida à revisão.
- [FfmpegCutActivity.kt](/D:/Projetos/SIG/app/src/main/java/br/gov/sp/pcsp/launcher/FfmpegCutActivity.kt): construção de corte, uso de `audioQuality`, `preciseAudioSegmentArguments` e remux final.
- [FfmpegMediaPolicies.kt](/D:/Projetos/SIG/app/src/main/java/br/gov/sp/pcsp/launcher/FfmpegMediaPolicies.kt): `cutMappedCopyArguments`, `hybridConcatArguments`, `extractAudioCommandArguments`, `cleanAudioCommandArguments`, `insertAudioFilterComplex`, assinaturas de concat.
- [FfmpegOutputRemuxer.kt](/D:/Projetos/SIG/app/src/main/java/br/gov/sp/pcsp/launcher/FfmpegOutputRemuxer.kt): `intermediateVideoExtension` e `remuxToOriginalContainer`, seleção automática no último remux e condição de sucesso.
- [FfmpegVideoQuality.kt](/D:/Projetos/SIG/app/src/main/java/br/gov/sp/pcsp/launcher/FfmpegVideoQuality.kt): preset `ultrafast`, CRFs, fatores de bitrate, `yuv420p` e ausência de B-frames no perfil hardware.
- [FfmpegVideoEncoders.kt](/D:/Projetos/SIG/app/src/main/java/br/gov/sp/pcsp/launcher/FfmpegVideoEncoders.kt) e [FfmpegVideoEncoderRegistry.kt](/D:/Projetos/SIG/app/src/main/java/br/gov/sp/pcsp/launcher/FfmpegVideoEncoderRegistry.kt): limiar de três segundos, catálogo e ausência declarada de x265 no pacote.
- [FfmpegExtractAudioActivity.kt](/D:/Projetos/SIG/app/src/main/java/br/gov/sp/pcsp/launcher/FfmpegExtractAudioActivity.kt): cópia condicional, seleção de faixa e presets.
- [FfmpegCleanAudioActivity.kt](/D:/Projetos/SIG/app/src/main/java/br/gov/sp/pcsp/launcher/FfmpegCleanAudioActivity.kt): `CleanMode`, perfil de áudio e builder.
- [FfmpegInsertAudioActivity.kt](/D:/Projetos/SIG/app/src/main/java/br/gov/sp/pcsp/launcher/FfmpegInsertAudioActivity.kt): execução com builder completo, seleção de faixa e timeline composta.
- [FfmpegRotateVideoActivity.kt](/D:/Projetos/SIG/app/src/main/java/br/gov/sp/pcsp/launcher/FfmpegRotateVideoActivity.kt): seleção de área, metadados, intermediários e caminhos de recodificação.
- [FfmpegPreviewSelection.kt](/D:/Projetos/SIG/app/src/main/java/br/gov/sp/pcsp/launcher/FfmpegPreviewSelection.kt): arredondamento de dimensões e construção do filtro crop.
- [SmartJoinPlanner.kt](/D:/Projetos/SIG/app/src/main/java/br/gov/sp/pcsp/launcher/SmartJoinPlanner.kt): `videoIncompatibility` e estrutura do plano.
- [Testes de políticas](/D:/Projetos/SIG/app/src/test/java/br/gov/sp/pcsp/launcher/FfmpegMediaPoliciesTest.kt): cobertura existente de builders e filtros; não executados nesta tarefa.

### Windows

- [ffmpeg_tools_panel.py](</D:/Projetos/SIG Windows/src/ffmpeg_tools_panel.py>): `_cut_worker`, `_cut_video_precise`, `_smartcut_segment_arguments`, `_video_args`, `_extract_worker`, `_clean_worker`, `_insert_worker`, `_insert_full_reencode_arguments`, `_rotate_worker`, `_validate_video_copy_compatibility` e caminhos SmartJoin.
- [smart_join_planner.py](</D:/Projetos/SIG Windows/src/smart_join_planner.py>): `video_incompatibility`, seleção de alvo e planejamento de pontes/corpos.
- [Testes do painel](</D:/Projetos/SIG Windows/tests/test_ffmpeg_tools_logic.py>): cobertura existente de comandos e regras; não executados nesta tarefa.

### Referências técnicas oficiais

- As referências do FFmpeg e Android estão vinculadas junto às afirmações que fundamentam. Foram consultadas em 13/09/2026. A aplicação das opções deve ser confirmada nos binários efetivamente distribuídos; a documentação atual não foi usada como prova de que todas as opções existem em todas as versões embarcadas.

## 21. Aprofundamento — como eu melhoraria SmartCut, SmartJoin e Smart Insert

Esta seção incorpora o pedido adicional de opinar sobre os modos inteligentes e suas transições. As propostas são de engenharia; não foram implementadas. A leitura adicional do Smart Insert Windows revelou uma diferença semântica concreta descrita em 21.5.

### 21.1 O significado de “inteligente” deve ser o mesmo nos três

Minha definição seria: **realizar a edição pedida preservando as regiões que podem permanecer intactas, sem alterar a semântica da edição para aumentar a porcentagem copiada**.

Hoje há risco de o rótulo “Smart” misturar três coisas distintas: menor processamento, aproximação temporal e efeitos de transição diferentes. Eu separaria essas dimensões:

| Dimensão | Escolha visível |
|---|---|
| Resultado editorial | Cortar, juntar, intercalar; com ou sem transição |
| Precisão | Exata ou limitada aos pontos copiáveis |
| Estratégia | Automática inteligente, recodificação integral ou cópia estrita |

Exemplo: selecionar “Crossfade linear de 200 ms” deve significar a mesma sobreposição no modo inteligente e no modo integral. O inteligente pode recodificar uma janela maior para construir a emenda, mas não pode substituir a sobreposição por um fade no arquivo inserido.

Eu mostraria a economia como consequência: “Vídeo: 82% do tempo copiado; áudio: recodificado uma vez”. Não escreveria “82% sem perdas” sem esclarecer a qual stream se refere. Percentual temporal também não é percentual de bytes nem tempo economizado.

### 21.2 SmartCut: plano de segmentos verificável

Recomendo que o planejador produza uma lista de regiões com origem e destino, antes de montar comandos:

| Região | Origem | Destino | Tratamento |
|---|---|---|---|
| Cabeça | início solicitado até ponto de acesso seguro | início da saída | Recodificar |
| Corpo | entre pontos de acesso seguros | imediatamente após a cabeça | Copiar |
| Cauda | fim do corpo até fim solicitado | imediatamente após o corpo | Recodificar |
| Áudio | todo o intervalo selecionado de cada faixa | timeline final comum | Copiar conforme política ou processar continuamente |

Invariantes propostas para cada plano:

1. A união das regiões de vídeo representa exatamente os quadros selecionados.
2. Nenhum quadro aparece em duas regiões ou fica sem região.
3. A ordem de apresentação é preservada.
4. O corpo só começa onde sua decodificação independente foi considerada válida.
5. O início do destino da região seguinte é calculado pelo plano temporal, não inferido cegamente do campo de duração do temporário anterior.
6. A seleção de áudio usa a mesma origem temporal da seleção de vídeo.

**Melhoria de desempenho:** se todo o intervalo já for copiável e não houver filtro, eliminar bordas desnecessárias. Se não houver corpo útil ou sua economia estimada for insignificante, escolher recodificação integral antes de criar vários arquivos temporários. Não fixaria “corpo mínimo de X segundos” sem medir: o custo depende de resolução, codec, armazenamento e hardware.

**Melhoria de qualidade:** não aplicar fade, suavização, interpolação ou crossfade nas emendas internas do SmartCut. Cabeça, corpo e cauda representam uma única sequência contínua; uma emenda correta não precisa de efeito artístico. Se houver salto, investigar seleção, referência de decodificação, cor ou timestamps. Um fade pode ocultar o sintoma e alterar o conteúdo.

**Melhoria de diagnóstico:** mostrar, no modo avançado, os intervalos realmente copiados e recodificados. Se a saída falhar na validação de emenda, oferecer reprocessamento integral e registrar a rejeição do plano inteligente. Não entregar a saída híbrida como “concluída com aviso” quando há corrupção.

### 21.3 SmartJoin: transição visual e janela de recodificação são coisas diferentes

Uma transição visual de 200 ms pode exigir recodificar uma região maior para alcançar limites seguros de cópia. Essa região técnica maior não deve aumentar a duração do efeito nem deslocar o ponto em que ele acontece.

Exemplo conceitual: clipe A e clipe B com 10 s cada, dissolve de 0,2 s. A saída esperada é 19,8 s. Se a ponte precisar começar em A=9,0 s e terminar em B=1,0 s, sua janela contém dois segundos de material de origem, mas a sobreposição continua sendo de 0,2 s. O restante da ponte deve ser uma reprodução normal de A ou B, sem efeito estendido.

O planejador atual já distingue vários limites da ponte e possui proteção contra transições que ocupam clipes curtos ou se sobrepõem em clipes intermediários. Eu preservaria essa estrutura e ampliaria os testes, em vez de reescrever o algoritmo inteiro.

**Melhorias recomendadas:**

- Representar duração por junção, permitindo que um ajuste de clipe curto não reduza inadvertidamente todas as outras transições.
- Recalcular o plano completo quando duas janelas técnicas de recodificação se encontrarem. Não executar duas pontes que disputam os mesmos quadros do clipe central.
- Se for preciso recodificar um clipe central inteiro, mantê-lo como uma região única do plano, com suas duas transições consistentes.
- Mostrar a referência escolhida e a quantidade de material normalizado. Trocar de clipe-base deve ser uma escolha visível.
- Manter o áudio em uma timeline contínua por faixa sempre que houver edição de volume, sobreposição ou normalização de formatos.
- Separar “normalização técnica do vídeo” de “efeito visual”. Ajuste de geometria não deve incluir de forma implícita crop, zoom ou mudança de cadência.

Para clipes com proporções diferentes, minha escolha padrão seria **encaixar com bordas**, sem esticar nem cortar conteúdo. Crop para preencher seria uma opção com preview. Para orientações diferentes, normalizar a apresentação antes de calcular encaixe e transição.

### 21.4 Transições: escolhas concretas que eu adotaria

| Operação/perfil | Padrão recomendado | Justificativa |
|---|---|---|
| SmartCut | Nenhum efeito | É um recorte de uma sequência contínua |
| Juntar preservando conteúdo | Corte seco | Mantém os trechos visíveis sem mistura ou escurecimento |
| Juntar para apresentação | Dissolve opcional, com duração explícita | Oferece continuidade visual quando desejada |
| Intercalar áudio preciso | Sem transição | Não atenua fala nem altera duração sem pedido |
| Atenuar estalo na edição de áudio | Microfade opcional, separado de crossfade | Trata uma descontinuidade local sem fingir que é cópia intacta |

Como valores iniciais de interface para **opções ativadas pelo usuário**, proponho 200 ms para dissolve e 5 ms para microfade. São pontos de partida para avaliação, não configurações universais nem resultados desta análise. Nenhum desses efeitos ficaria ativo por padrão no perfil de preservação.

Um microfade sem sobreposição mantém a duração total, mas altera amostras próximas ao corte. Aplicá-lo somente no inserido não garante eliminar uma descontinuidade do principal. Evitar prometer “sem estalo” antes de comparar a junção real.

Para crossfade, eu começaria com curva linear explícita nos dois aplicativos, mantendo curvas alternativas no avançado. Uma curva escolhida para manter potência de sinais pouco correlacionados pode aumentar picos em sinais correlacionados. Não existe uma curva universalmente superior para fala, música e gravações muito semelhantes.

**Política de pico proposta:** medir a região de transição. Se a mistura ultrapassar o limite do perfil, oferecer redução de ganho ou outra curva, indicando a alteração. Não inserir limiter ou normalização global silenciosamente para esconder saturação.

Nas transições visuais, validar principalmente quadros na entrada, meio e saída do efeito; em áudio, ouvir o entorno com nível de comparação consistente. Um dissolve não deve mudar o nível de cor fora da janela da transição.

### 21.5 Nova divergência confirmada: Smart Insert Windows muda o significado da transição

Na leitura adicional de `_insert_smart_worker`, o Windows copia as partes esquerda e direita do áudio principal e recodifica o áudio inserido. Quando há transição, aplica `afade` de entrada e saída **somente no inserido**, inclusive quando o nome selecionado representa uma curva como Linear.

Já `_insert_full_reencode_arguments`, com curvas diferentes de `none`/`fade`, usa `acrossfade` entre as partes. O seam completo do Android também possui caminho de crossfade.

| Escolha aparente | Smart Insert Windows | Reencode Completo |
|---|---|---|
| Curva Linear com duração positiva | Fade no começo/fim do inserido; concat sem sobreposição | Sobreposição entre vizinhos com `acrossfade` |
| Duração lógica | Soma das partes | Soma menos as sobreposições |
| Região alterada pelo efeito | Inserido | Regiões de encontro dos dois lados |

**Minha avaliação:** esta é uma divergência prioritária de semântica dentro do próprio Windows. O comportamento diferente está confirmado nos builders; os artefatos de saída ainda precisam de uma prova de execução.

**Decisão:** não usar o mesmo rótulo para os dois resultados. Como correção de produto mais simples, renomear o comportamento existente para “Fade apenas no áudio inserido”, e deixar “Crossfade” disponível somente em caminhos que realmente o executem. Como evolução, permitir um plano inteligente de emendas que preserve a mesma operação do modo integral.

### 21.6 Smart Insert: estratégia ideal por tipo de áudio

Eu não trataria áudio comprimido como se tivesse exatamente as mesmas propriedades de montagem do vídeo. O plano deve considerar as unidades copiáveis e a apresentação decodificada de cada formato.

| Tipo de entrada/objetivo | Estratégia que eu priorizaria |
|---|---|
| PCM/FLAC e ponto exato | Processar a sequência em PCM e exportar sem nova perda; validar amostras |
| Áudio comprimido, sem transição, preservação estrita | Copiar somente quando a compatibilidade e os limites apresentados forem comprovados; informar aproximação |
| Áudio comprimido com crossfade ou ponto exato arbitrário | Uma decodificação/edição contínua e uma codificação final |
| Arquivo muito longo, economia realmente necessária | Estudar emendas comprimidas por codec, com suite própria antes de habilitar |

Decodificar FLAC e codificar novamente em FLAC não cria perda com perdas se não houver alteração de amostras/formato no caminho. Logo, a necessidade de copiar pacotes nesse caso pode ser menor do que no áudio com compressão destrutiva. Ainda assim, validar o resultado PCM em uma rota sem filtros intencionais.

Na prática, para a primeira padronização, eu daria prioridade à confiabilidade do modo preciso de áudio contínuo. A possível economia de copiar partes de um áudio deve ser medida antes de aceitar uma arquitetura mais frágil para todos os formatos.

O Smart Insert atual reencoda o inserido para o codec da origem mesmo em situações em que ele talvez já seja compatível. Uma melhoria futura é evitar essa recodificação quando não houver efeito, conversão nem incompatibilidade e quando a concatenação tiver sido validada. Essa otimização vem depois da correção do significado de transição.

### 21.7 Preview do resultado e timeline única

Minha preferência seria um modelo único de timeline usado pelo preview, pelo planejador e pelo cálculo de duração. A timeline deve mapear um instante da saída para a origem correspondente, inclusive quando duas origens contribuem durante a sobreposição.

No Android há offsets de preview de junção calculados pela soma das durações de origem, além de um caminho separado de preview do resultado. Isso exige conferir claramente qual preview está em uso antes de atribuir paridade com a saída. A leitura identifica um ponto de atenção; não demonstra que todo preview atual está incorreto.

**Proposta de experiência:**

- Preview rápido de seleção: navega nas fontes e se identifica como tal.
- Preview da transição: renderiza somente a janela escolhida usando o plano real.
- Preview final: reproduz o arquivo entregue.

Cachear a janela por identidade dos arquivos, posições, perfil e parâmetros de efeito. Alteração de qualquer parâmetro invalida o cache. Não usar um efeito do player como prova visual de um filtro diferente do FFmpeg.

Se uma transição for reduzida para caber em um clipe, atualizar o comprimento da timeline imediatamente. O operador não deve descobrir a nova duração só depois de salvar.

### 21.8 Testes novos que realmente diferenciam uma emenda boa de uma ruim

| Caso | Verificação decisiva |
|---|---|
| SmartCut com corte antes/depois de keyframe | Quadros selecionados e região copiada corretos, sem deslocamento ajustado manualmente |
| SmartCut sem corpo interno | Escolha integral correta, sem temporários ou emendas artificiais |
| SmartJoin A=10 s, B=10 s, dissolve=0,2 s | 19,8 s de timeline útil; efeito limitado à janela definida |
| SmartJoin com três clipes e centro curto | Ajuste/rejeição explícito; nenhuma região processada duas vezes |
| Junção repetida de muitos clipes | Nenhuma deriva temporal cumulativa nem crescimento de silêncios não planejados |
| Smart Insert e integral com a mesma curva | Mesmo tipo de efeito e mesma duração lógica |
| Inserção no início/no fim/no meio | Uma ou duas fronteiras de transição conforme o caso |
| Inserção perto de uma amostra-limite | Mesmo arredondamento Python/Kotlin |
| Áudio com sinais semelhantes nos dois lados | Picos da mistura medidos; ausência de saturação introduzida |
| Emenda com perfil de cor distinto | Rejeição ou normalização visível; nada de mudança silenciosa |
| Falha de hardware durante uma ponte | Nenhuma concatenação com ponte incompleta; recuperação equivalente ou interrupção clara |

Adicionar também testes de propriedades do planejador: duração não negativa, limites dentro da origem, ausência de sobreposição involuntária e soma das regiões coerente com as transições. Esses testes são mais úteis que duplicar literalmente o código de montagem de comandos.

### 21.9 Sequência específica para melhorar os modos inteligentes

1. Corrigir nomes e semântica das transições, principalmente Smart Insert.
2. Especificar timeline, regiões e duração esperada em testes comuns.
3. Reproduzir e explicar os excessos temporais já registrados.
4. Validar o áudio contínuo como referência dos modos exatos.
5. Validar cada emenda de vídeo e o miolo copiado.
6. Habilitar otimizações de cópia somente nas classes aprovadas.
7. Medir desempenho e economia; retirar otimizações que adicionem complexidade sem benefício relevante.

Eu preservaria o investimento atual nos planejadores SmartJoin: as estruturas de corpo, ponte e proteção de clipes curtos são úteis. Concentraria o esforço em **um único significado de operação, uma timeline comprovável e validação do arquivo entregue**. O objetivo não é copiar a maior porcentagem possível a qualquer custo; é executar a edição escolhida com a menor alteração necessária.

**Entrega desta tarefa:** somente o presente relatório, sem alteração funcional nos dois aplicativos e sem reprodução nova das medições históricas.
