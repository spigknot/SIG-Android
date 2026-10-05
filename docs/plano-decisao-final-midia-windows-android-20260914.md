# Decisões e plano de ação — padronização das ferramentas de mídia SIG

**Data:** 14/09/2026. **Revisão:** especificação detalhada das correções. **Estado:** proposta de implementação; somente este relatório foi atualizado nesta revisão. Nenhuma correção de aplicativo, execução de teste, build, commit ou publicação foi realizada nesta entrega.

**Base:** documento de decisão anexado pelo usuário, que consolida Rodada A e medições complementares; leitura dos repositórios; pareceres anteriores. Os resultados numéricos de execução citados são os fornecidos pelo desenvolvimento. Não reexecutei essas medições. Valores de configuração e tolerâncias introduzidos aqui são identificados como **decisões propostas**, e não resultados de benchmark.

Este documento prevalece sobre recomendações anteriores quanto às próximas ações. O objetivo é que a mesma escolha produza conteúdo equivalente, mantendo as diferenças de backend necessárias a cada plataforma.

**Para implementar:** usar a seção 11, que especifica arquivos, funções, alterações de argumentos, comportamento de interface, falhas e regressões. As seções anteriores conservam as decisões e sua justificativa. Nomes de tipos e funções assinalados como propostos ainda não existem; os demais são pontos encontrados no código consultado. As linhas podem mudar: localizar também pelo nome do símbolo.

## 1. O que temos que fazer

**Primeiro corrigir perdas comprovadas; depois corrigir a timeline dos modos inteligentes; finalmente alinhar os perfis de ferramentas e calibrar desempenho.** Não precisamos reescrever os aplicativos nem trocar o FFmpeg para começar.

| Ordem | Mudança | Quem muda primeiro | Resultado esperado |
|---|---|---|---|
| 1 | Seleção de faixas no remux | Android | Nenhuma faixa escolhida desaparece no último passo |
| 2 | Inventário e parâmetros de áudio por faixa | Windows, depois Android | A faixa estéreo/48 kHz continua com seu perfil; o Android não importa o defeito Windows |
| 3 | Política de áudio do corte preciso | Android | AAC explícito no modo preciso, sem prelúdio de áudio anterior ao pedido |
| 4 | Crop e rótulos das transições | Android e Windows, mudanças separadas | Coordenada exibida corresponde aos pixels; nomes representam o mesmo efeito |
| 5 | Áudio contínuo dos cortes inteligentes | Prova Windows, integração Windows e Android | Preservar o miolo de vídeo aprovado e eliminar acréscimos temporais por emenda |
| 6 | Sem Reencode e SmartJoin | Definir limites comuns, testar ambos | Aproximação declarada e junções sem deriva acumulada |
| 7 | Extração, limpeza, metadados e proteção de perfis | Ambos | Mesmos perfis e conversões explícitas |
| 8 | Preset e qualidade de hardware | Benchmark Android e Windows, depois mudança conjunta de perfil | Melhor compromisso medido, sem confundir velocidade com qualidade |

**Decisões imediatas:** corrigir remux, perfis multifaixa, AAC preciso, crop e nomenclatura; manter SmartCut experimental; não aceitar crescimento de atraso nas emendas como padrão final; não acrescentar x265/HDR completo agora.

**Decisões que dependem de teste:** implementação validada do áudio contínuo, limites copiáveis Windows, compatibilidade das pontes SmartJoin, vencedor do modo Forte e preset de CPU. O teste escolhe entre alternativas definidas neste documento; não exige reabrir as decisões já apoiadas por defeitos medidos.

## 2. O que mudou com as novas medições

O documento recebido encerra as dúvidas anteriores sobre contagem e fim do áudio: informa 80 quadros no corte preciso Windows, 80 na variante AAC Android e os quatro bipes nas posições esperadas. O parser/detector anterior tinha limitações. Não recomendo refazer essas perguntas como se a resposta ainda faltasse.

Também informa T06 aprovado em aparelho físico, além de emulador: o corpo visual foi preservado na classe testada. A comparação de pixels decodificados sustenta preservação visual do miolo; não é automaticamente um hash do payload comprimido.

**A novidade decisiva é T07:** a sequência de marcadores é preservada, mas há +20 ms na cabeça, +40 ms na primeira emenda e +20 ms na segunda, chegando a +80 ms. Isso deve ser tratado como descontinuidade/crescimento temporal a corrigir no perfil preciso, mesmo ocorrendo igualmente nos dois apps. Ter todos os sons em ordem não comprova que suas posições temporais estão corretas.

O resumo não publica toda a relação áudio/vídeo por marcador. Por isso, não afirmo que o desvio relativo A/V seja exatamente 80 ms; afirmo que o crescimento temporal informado já impede adotá-lo sem análise como apenas um deslocamento constante de contêiner. A próxima prova deve medir ambos na mesma origem temporal.

A identificação de APK no documento contém SHA-256 abreviado. Antes da implementação, registrar o hash completo das referências e o estado local dos fontes. Isso é organização das evidências, não um bloqueio aos diagnósticos já obtidos.

A composição de padrões de período 8 e 9 distingue coordenadas **módulo 72**; não identifica sozinha toda uma imagem de 360 linhas. Para o deslocamento local de uma linha discutido, serve com a posição esperada conhecida. Para um verificador geral, acrescentar identidade de bloco ou padrões cujo alcance elimine a ambiguidade. Não invalida a reprovação já demonstrada do crop.

## 3. Contrato comum e tolerâncias de aceitação

### 3.1 Regras obrigatórias

- Intervalo exato definido como **[início, fim)** na timeline apresentada ao operador. Identificar a origem temporal e preservar offsets intencionais entre streams.
- Vídeo: selecionar por PTS de apresentação, não por DTS. Não duplicar quadros para tornar uma contagem aparentemente correta.
- Áudio: conservar perfil por faixa e calcular limites de amostra de forma determinística. Conversões de canais, taxa e codec devem fazer parte do plano exibido.
- Cópia de conteúdo não pode ter filtro de pixels ou de amostras oculto. Qualidade/preset ficam inativos para streams copiados.
- Alteração de codec, contêiner incompatível, canais, faixa, HDR ou resolução deve ser apresentada antes de ocorrer. Alternativa equivalente de encoder pode executar uma vez com registro, conforme política automática.
- Confirmar o arquivo realmente entregue. Um temporário correto e um mux final com perda não são sucesso.

`atrim` e `trim` selecionam conteúdo; ajustes de timestamps são etapas distintas. A documentação deixa claro que os filtros de trim não redefinem timestamps por si. A montagem proposta deve considerar essa separação e não zerar cada stream independentemente quando existe offset legítimo. [FFmpeg: filtros temporais](https://ffmpeg.org/ffmpeg-filters.html#atrim).

### 3.2 Critérios propostos para os testes

Os limites abaixo são **metas de engenharia deste plano**, não supostos resultados já medidos. Se uma ferramenta de medição não tiver resolução para avaliá-los, registrar inconclusivo e melhorar a medição, sem alargar a tolerância após ver o resultado.

| Código | Critério de aceite |
|---|---|
| QV | Zero quadros omitidos/duplicados. C1 [1,4;4,6): 80 quadros, índices 35–114 da fonte zero-based, em ordem. Corpo SmartCut: 50/50 pixels decodificados iguais com decoder/perfil de análise comum |
| QA | Em referência PCM sem transformação temporal intencional: seleção/soma de amostras conforme arredondamento definido, diferença máxima de uma amostra por limite. Em áudio com perdas: marcadores completos e erro de localização de até 5 ms em relação à referência apresentada, depois de considerar padding efetivamente excluído pelo player; nenhum salto acumulado acima disso nas emendas |
| QS | Offset A/V da fonte preservado na saída; para os fixtures sincronizados, aplicar QV/QA na mesma origem. Não usar uma tolerância de um quadro para aceitar deriva conhecida em áudio |
| QD | A duração declarada pode diferir por granularidade do mux/codec, mas a diferença precisa ser explicada pelos PTS/padding e não pode produzir conteúdo extra, pausa inicial ou dessincronia. Não há tolerância genérica de 500 ms |
| QF | Inventário selecionado completo, codec/perfil/canais/taxa/ordem/disposição conforme plano; nenhum stream substituído por duplicata de outro. Faixas/canais conferidos também pelo conteúdo |
| QC | Coordenadas anunciadas iguais à região salva: zero pixel de discrepância na referência espacial, antes de avaliar diferenças de compressão. Orientação e proporção também correspondem ao plano |
| QU | Mesmo nome de opção significa mesma operação. Controle ativo tem efeito prometido; conversão material aparece no plano; cancelamento/falha nunca termina como sucesso |

Para C1b, verificar os quatro bipes; para C1c, os marcadores esperados segundo seus PTS, inclusive o último próximo da saída. A soma de amostras é critério natural em PCM; não comparar o número bruto decodificado de qualquer AAC como se não houvesse representação de padding.

Se QA de 5 ms não for atingido por uma saída comprimida, primeiro distinguir erro de detector, offset constante de apresentação e saltos por emenda. O objetivo final não é aprovar o crescimento +20/+40/+20 informado; é eliminá-lo. A política precisa ser revista explicitamente se surgir uma limitação real inevitável de um player-alvo.

## 4. Documento de decisão por tema

### D01 — Corte preciso e áudio: Android se aproxima da política Windows, sem herdar o perfil global

**Decisão de produto:** padrão **“Áudio com corte preciso (AAC)”**. Alternativa **“Preservar áudio comprimido — limites aproximados”**. Ajuda: “O modo preciso recodifica o áudio. A cópia evita nova compressão, mas pode incluir conteúdo fora dos limites exatos.” Vídeo e áudio devem ter sua precisão indicada separadamente quando o operador escolher a alternativa de cópia.

**Decisão técnica:** aplicar AAC por faixa selecionada no Android; manter a escolha existente no Windows. Usar a rota temporal validada por T02 como ponto de partida, sem mudar simultaneamente preset, posição de seek e muxer. Só acrescentar outro tratamento temporal se a aceitação pelo app exigir.

**Parâmetros iniciais comuns, decisão proposta:** preservar taxa e layout de cada faixa quando suportados. Para reduzir o escopo da primeira correção, bitrate de origem por faixa quando a origem já é AAC e a informação é válida. Quando não houver referência AAC utilizável, alvo inicial de 128 kb/s mono ou 256 kb/s estéreo. Esses valores são parâmetros propostos de produto, não garantia de fidelidade. Em outros layouts sem perfil validado, oferecer perfil compatível explicitamente ou saída sem nova compressão com perdas, sem downmix automático. Não aplicar o bitrate da primeira faixa às demais.

Fonte com codec diferente de AAC gera conversão explícita. Codec/canais não suportados devem levar a alternativa ou recusa, não ajuste silencioso. O perfil avançado sem nova perda pode usar PCM/FLAC em destino compatível, sem promover esse recurso a bloqueio da correção AAC.

**Aceite:** QV/QA/QS/QD/QF; C1b e C3b no arquivo final. **Risco:** médio; corrigir D1 e manter remux defeituoso ainda perde áudio, por isso D02/D03 são pré-requisitos. **Fora do escopo:** transcrição e contratos de provedores.

### D02 — Faixas e remux: corrigir Android; preservar o caminho Windows aprovado

**Padrão de produto:** “Vídeo principal e todas as faixas de áudio”. Extras detectados aparecem no resumo; “Preservar extras compatíveis” é escolha separada. Outros streams de vídeo não ficam disfarçados dentro da promessa “todos”.

**Técnica:** carregar o inventário escolhido até o remux. Em C3, `-map 0` resolveu o caso; na implementação geral, emitir os maps da seleção validada, com tratamento conhecido para o contêiner final. `-c copy` define como codificar os streams selecionados, não a seleção de todos eles. [FFmpeg: seleção de streams](https://ffmpeg.org/ffmpeg.html#Stream-selection).

Validar existência, legibilidade, inventário e perfil antes de remover o temporário recuperável. Se um extra selecionado não couber no destino, oferecer contêiner alternativo ou cancelar aquela escolha; não descartar em silêncio.

**Quem muda:** helper de remux Android e seus chamadores; Windows mantém C3 aprovado, recebendo apenas ajustes de UI/seleção onde houver diferença de contrato. **Aceite:** QF/QU, dois áudios distinguíveis e destino incompatível. **Risco:** médio — map indiscriminado pode introduzir falhas de mux. **Fora do escopo:** forçar MKV para todo arquivo.

### D03 — Perfil individual de áudio: corrigir Windows e construir corretamente no Android

**Técnica:** o probe deve devolver uma lista de faixas, não um único perfil reutilizado para todas. Montar opções de saída por índice da faixa mapeada; não confundir índice absoluto de entrada com índice de áudio na saída. Taxa/layout/bitrate individuais, metadados e disposição preservados quando válidos.

**UI:** “Faixa 1: mono, 44,1 kHz; faixa 2: estéreo, 48 kHz”, com conversões abaixo de cada uma. Normalização comum só quando escolhida; padrão preserva perfis.

**Quem muda:** primeiro o corte preciso Windows, onde T04 reprovou; Android usa a mesma definição em seu novo caminho AAC. Revisar reutilizações do perfil único em Girar/SmartCut/SmartJoin por testes focais, sem refatorar todos os fluxos de uma vez.

**Aceite:** C3b mantém A mono/44,1 kHz e B estéreo/48 kHz com sinais distintos em L/R; QF. **Risco:** médio — troca de índices ou layout nominal incorreto. **Fora:** misturar/deduplicar faixas por heurística.

### D04 — Crop: ambos adotam seleção ajustada e visível

**Escolha final para a primeira versão:** alinhar a seleção à grade de pixels exigida pelo caminho e mostrar o retângulo real **antes da confirmação**. Não ativar `exact=1` globalmente agora.

Para a rota yuv420p testada, x/y e dimensões devem obedecer ao alinhamento de dois pixels. Proposta determinística: alinhar x/y para baixo, manter dimensões pares dentro do quadro, recomputar o retângulo e atualizar números/preview. O usuário escolhe sobre a seleção efetiva; o resultado não promete ser estritamente interno ao retângulo anterior ao ajuste. Exemplo: y=87 passa a ser exibido como y=86; x=101 passa a x=100. Se esse comportamento não atender ao uso, a alternativa é um modo de coordenada exata, e não ajuste escondido depois de confirmar.

Aplicar a transformação da seleção no espaço visual correto, considerando rotação e proporção, antes de resolver sua representação no encoder. Em formato diferente, consultar seu alinhamento; não presumir que toda mídia tenha grade 2×2.

**UI:** “Seleção ajustada: 322 × 162 em (100, 86)”. **Aceite:** QC, x/y ímpares, rotação e bordas do quadro nos dois apps. **Risco:** baixo/médio por transformação de coordenadas. **Fora:** `exact=1` universal e novo suporte HDR.

### D05 — Smart Insert: renomear já; crossfade sempre significa sobreposição

**Finalidade decidida:** Inserir áudio intercala áudio em áudio; geração de vídeo/dublagem permanece fora do escopo, conforme o documento recebido.

**Decisão de produto:** manter a otimização atual Windows com o nome **“Fade somente no áudio inserido”**, com a curva apresentada separadamente como “Linear”. Reservar **“Crossfade linear”** para o caminho integral de sobreposição, existente nos dois. Quando o usuário escolher crossfade, selecionar/solicitar modo integral explicando a recodificação; não transformar sua escolha em fade simples.

**Defaults:** “Sem transição”. Ao ativar crossfade, duração inicial proposta 0,2 s, editável; não alterar presets salvos para aplicar efeitos novos. Fade não reduz duração; crossfade subtrai cada sobreposição efetiva. C5: 12 s sem sobreposição, 11,6 s com duas de 0,2 s; entrada no início/fim tem uma fronteira. Curva `tri` para Linear.

**Quem muda:** Windows precisa corrigir nomes e estado dos controles; Android alinha nomes/duração/ajuda do integral. Não portar Smart Insert aproximado ao Android agora. **Aceite:** QU/QA; investigar 12,03 s Windows separadamente dos 400 ms editoriais, conferindo codec de saída e amostras reais. **Risco:** baixo no nome, médio no encaminhamento/preview. **Fora:** crossfade comprimido por emendas em todos os codecs.

### D06 — SmartCut: manter o vídeo híbrido; eliminar crescimento temporal do áudio

**Decisão técnica:** adotar áudio contínuo por faixa como arquitetura-alvo do perfil preciso, validado primeiro no Windows e depois no Android. O vídeo continua cabeça/corpo/cauda; o áudio vem do intervalo completo da fonte, é recortado e codificado uma vez, e entra no mux final pela mesma origem temporal do vídeo.

**Não aceitar/documentar +80 ms como padrão final de precisão.** O que pode ser documentado é um deslocamento comum de contêiner que não altera apresentação útil. Acréscimo em emendas exige correção. Não adicionar fades nas emendas internas para esconder saltos; não cortar o final para “caber” na duração.

**UI/default:** manter “SmartCut (experimental)” como padrão nas classes já validadas, com descrição “Vídeo: bordas recodificadas e miolo copiado; áudio preciso: recodificado uma vez” somente depois da integração. Até lá, não exibir a promessa nova. Se a nova rota reprovar, restringir o perfil preciso dessa classe ao integral, com motivo; não declarar a rota antiga corrigida.

**Aceite:** QV com 50/50 no miolo; QA/QS em todos os marcadores C1c; nenhum novo degrau de atraso por junção; C2 sem áudio funciona. **Risco:** médio/alto, exige prova A/B e fallback seguro. **Fora:** reescrever o planner de vídeo aprovado ou prometer VFR/HDR não testados.

### D07 — Sem Reencode: mesmo contrato de aproximação, sem conversão disfarçada

**Decisão de produto:** modo “Sem recodificar — limites aproximados”. Mostrar intervalo pedido e intervalo efetivo, inclusive conteúdo adicional. A cópia estrita nunca muda para recodificação sem que o plano o explique e a escolha de precisão seja alterada explicitamente.

**Alvo técnico:** para clipes com pontos de acesso independentes conhecidos, usar uma política comum de abranger a seleção: começo no ponto seguro anterior ou igual ao início e fim no próximo limite seguro suficiente para conter o fim. Preservar estrutura de decodificação e reportar apresentação efetiva, incluindo eventual material adicional; fontes sem essa garantia oferecem modo preciso. O teste Windows das pontas decide qual rota precisa mudar para esse contrato.

Em áudio puro, declarar limites de pacote do codec; no perfil exato, usar processamento de amostras. Não usar o número de quadros como único critério para afirmar o início visível.

**Aceite:** QF e identidade das pontas consistente com o plano, tolerância zero entre limites anunciados e realmente apresentados na granularidade disponível. Não impor 3,20 s a uma seleção explicitamente expandida. **Risco:** médio em seek/edits. **Fora:** precisão arbitrária com stream copy.

### D08 — SmartJoin e transições: conservar corpos, compartilhar a timeline

**Defaults propostos:** sem transição; perfil de destino indicado pelo primeiro clipe na ordem do usuário, com escolha avançada de outra referência; encaixe com bordas quando necessário, sem crop/upscale/estiramento oculto. A política de usar outro clipe para reduzir recodificação pode continuar como opção “Automático”, mostrando a referência.

Transição “Dissolver” opcional, duração inicial proposta 0,2 s; crossfade de áudio linear quando associado a essa sobreposição. Fade para preto/silêncio sem sobreposição deve ter nome próprio e conservar duração. Não reduzir uma transição sem atualizar preview e duração efetiva.

**Técnica:** preservar planners de corpos/pontes, reforçar regras apenas onde reprovarem. Janela recodificada pode ser maior que efeito; efeito continua na janela escolhida. Áudio com transições deve formar uma timeline contínua por faixa. Avaliar crescimento de atraso por número de junções antes de habilitar sequências longas como aprovadas.

**Aceite:** QV/QA/QS/QF; duas entradas de 10 s e uma sobreposição de 0,2 s resultam em 19,8 s úteis; clipe central curto recebe ajuste/rejeição explícita; dados de compatibilidade ausentes não equivalem a confirmação. **Risco:** alto nas pontes, médio na UI. **Fora:** suporte irrestrito a GOP aberto/VFR/perfis desconhecidos.

### D09 — Extrair: Windows ganha cópia explícita; ambos usam os mesmos perfis

**Default decidido:** “Original — copiar faixa”. Escolher faixa marcada como padrão quando válida, senão a primeira; mostrar a escolhida e permitir mudar. Não misturar áudios.

| Perfil comum | Parâmetros/efeito |
|---|---|
| Original | Cópia da faixa integral em contêiner compatível; qualidade, taxa e canais não editáveis nesse modo |
| Converter | Formato, taxa, canais e qualidade explícitos; seleção temporal exata se houver corte |
| Transcrição local | WAV PCM 16-bit, 16 kHz, mono; conversão declarada, sem alterar provedores STT |
| Compacto | OGG/Vorbis, 16 kHz, mono, 32 kb/s, preservando o perfil já descrito nos projetos; validar consumidores antes de ampliar uso |

Se selecionar um trecho no perfil Original, oferecer corte aproximado por cópia ou mudança explícita para Converter/preciso. Não recodificar só porque a extensão de destino é a mesma.

Lote: sem áudio → item ignorado com motivo; início além da duração → item ignorado; fim além da duração → ajuste ao fim informado por item. Não confundir conclusão parcial com todos concluídos.

**Quem muda:** Windows implementa seleção/cópia equivalente; Android torna controles e cópia coerentes com o perfil comum. **Aceite:** QF/QU/QA conforme modo. **Risco:** médio — copy elegível pode ignorar bitrate escolhido se os modos não forem separados. **Fora:** unificação automática de extensões como `.amr` em todas as ferramentas.

### D10 — Limpar: nomes honestos, perfil comum e algoritmo escolhido por audição

**Default:** Equilibrado `afftdn=nf=-25`; saída “Preservar taxa e canais”. Alternativa “Preparar para transcrição” com PCM 16-bit/16 kHz/mono. A limpeza altera amostras; remover promessa de “preservação sem alterações” se houver. Não acrescentar normalização de volume para tornar verdadeira uma ajuda antiga: corrigir a ajuda para “Reduz ruído; não normaliza automaticamente o volume”.

Para saída de escuta/processamento, manter a precisão PCM suportada da origem quando conhecida e adequada; não forçar 16 bits em origem de maior precisão. Se o perfil for desconhecido, informar a saída escolhida, sem afirmar preservação de uma profundidade não sondada.

**Forte:** o candidato comum é `afftdn=nr=18:nf=-35:tn=1` do Windows. Não o declarar superior antes do teste. Comparar com `anlmdn=s=0.00003:p=0.002:r=0.002` Android em audição com níveis de comparação controlados. Se o candidato preservar inteligibilidade e apresentar resultado adequado no aparelho, adotá-lo em ambos. Se houver vantagens distintas por material, manter algoritmos com nomes próprios nos dois e retirar o rótulo único ambíguo “Forte”.

Essa é uma decisão técnica dependente da audição; default Equilibrado e retirada da promessa de normalização já podem ser decididos. **Aceite:** QU/QF; sem perda de sílabas relevantes nem artefatos novos inaceitáveis no corpus de fala; parecer auditivo com trechos e motivo. **Risco:** médio, piora de inteligibilidade. **Fora:** normalização automática, modelo neural novo e promessa de melhorar toda transcrição.

### D11 — Preset e encoder: qualidade independente da velocidade

**Default de qualidade preservado inicialmente:** Alta, CRF 20 para libx264; manter escala 16/18/20/23/26 existente. Nenhum desses níveis é lossless. **Preset comum candidato decidido:** `fast`, controlado por “Velocidade: equilibrada”, separado de Qualidade. Opções avançadas propostas `veryfast` (rápida) e `medium` (maior esforço). `ultrafast` fica opção extrema explícita, não padrão pretendido.

O benchmark aprova ou rejeita o candidato antes da alteração conjunta. Se `fast` falhar no aparelho em tempo/recursos ou qualidade, não empurrar uma mudança global: comparar `veryfast` e fechar outro candidato comum; conservar perfis anteriores identificados até essa conclusão. Não invento nesta entrega um limite de minutos sem medir a carga usada pelo operador.

Hardware conserva os controles nativos. Calibrar objetivo por codec/backend, não igualar números de CQ e CRF. Não considerar fator HEVC 0,80 automaticamente equivalente a 1,00 em outro backend. Nova tabela de bitrate depende de prova de detalhe visual.

Regra de encoder já descrita como comum: hardware preferido, CPU para trecho curto **se equivalente**, uma repetição equivalente por tarefa. Manter. Capacidade depende de dimensões/taxa/perfil, não só do nome listado; Android oferece consultas específicas para essas combinações. [Android VideoCapabilities](https://developer.android.com/reference/android/media/MediaCodecInfo.VideoCapabilities).

**Aceite:** invariantes funcionais iguais; qualidade avaliada em detalhes, memória e tempo sustentado observados; melhorias de tamanho comparadas com áudio/duração iguais. **Risco:** médio na calibração; baixo em alterar uma string depois de aprovado. **Fora:** encoder idêntico entre plataformas, x265 Android e atualização conjunta de FFmpeg.

### D12 — Metadados, HDR, rotação e entrega

**HDR/10-bit:** bloquear recodificação que reduza o perfil silenciosamente; explicar “Este caminho não preserva HDR/10 bits” e oferecer cópia compatível ou conversão deliberada apenas se essa conversão já tiver implementação validada. Não adicionar tone mapping improvisado. Informação desconhecida pede investigação/alternativa, não classificação automática como SDR conhecido.

**Rotação:** +90 na UI tem a mesma direção visual nos dois; interpretação da matriz de origem, giro/espelho e crop têm uma ordem única de referência. Modo pixels entrega orientação resolvida sem giro duplo; modo metadados preserva pixels e informa limitações de players/contêiner.

**Capítulos:** em corte, intersectar cada capítulo com o intervalo efetivo, subtrair origem e eliminar vazios. Em concat/crossfade, recalcular pela timeline ou omitir com indicação se não houver transformação implementada. Nunca anunciar capítulos preservados se apenas copiados com tempos antigos.

**Timecode:** distinguir referência da fonte da posição no derivado. Preservar como referência descritiva quando não existir transformação válida, sem fabricar um timecode contínuo falso. Metadados técnicos da saída devem ser recalculados; nomes/idiomas válidos podem ser preservados.

**Entrega:** temporário exclusivo; confirmar retorno, arquivo legível, inventário/perfil e propriedades temporais acessíveis; só então concluir e abandonar temporários recuperáveis. Decodificação integral e métricas pesadas ficam na aceitação e no diagnóstico; em execução comum, validação estrutural e de pontas/propriedades, com ampliação em rotas de risco. Não prometer que uma checagem leve detecta toda corrupção.

**Aceite:** QF/QC/QU/QD; todos os campos suportados refletem a operação. **Risco:** médio, especialmente API de documentos Android e rotação. **Fora:** preservação universal de todos os extras em qualquer destino.

## 5. Plano de ação por fases, dependências e reversão

Cada fase é uma unidade revisável. Dentro de uma fase, mudanças Windows e Android continuam separadas por execução e evidência. Os gates GA/GW são definidos na seção 6 e aplicam-se à plataforma alterada. A prova comparativa usa os dois apps, mesmo quando só um mudou.

### F0 — Congelar a referência de teste e as decisões

**Atual:** corpus e artefatos existem, alguns identificadores publicados estão abreviados. **Alvo:** manifesto local com SHA completo, build/configuração, entrada, saída, encoder real e critérios Q. Não reconstruir o corpus sem necessidade.

**Arquivos:** documentação de validação e fixtures já existentes; não modificar fontes. **Testes:** confirmar que medidores contam 1:1, não perdem último PTS/bipe e resolvem posição espacial suficiente. **Gate:** coerência documental e de manifestos. **Reversão:** nenhuma mudança funcional; conservar os resultados de referência sem sobrescrevê-los.

### F1 — Corrigir exclusivamente remux Android

**Dependência:** F0 e seleção acordada em D02. **Atual:** intermediário correto, remux perde faixa. **Alvo:** maps explícitos da seleção e validação final.

**Arquivos prováveis:** `FfmpegOutputRemuxer.kt`, `FfmpegMediaPolicies.kt`, chamadores de Cut/Rotate/Join necessários para passar seleção; `FfmpegOutputRemuxerTest.kt` e testes de políticas.

**Regressões que nascem junto:** C3 com faixas audivelmente distintas, destino incompatível, entrada e saída com mesma extensão, cancelamento/falha antes de concluir. **Gates:** GA + artefato Android; Windows C3 como comparação. **Risco/mitigação:** mapeamento de extra incompatível → planejar destino antes; apagar intermediário cedo → validar antes de descartar.

**Reversão:** retirar somente a mudança candidata se falhar; manter o perfil multifaixa indisponível ou recusado na versão de teste, em vez de apresentar novamente perda silenciosa como corrigida. Não fazer reset amplo em alterações do usuário.

### F2-W — Corrigir perfis por faixa Windows

**Dependência:** F0; não depende de esperar F1 para escrever/testar a regra. **Atual:** `MediaProfile` único aplicado globalmente. **Alvo:** inventário e parâmetros próprios no corte preciso.

**Arquivos:** `src/ffmpeg_tools_panel.py` (`_probe_media`, `_cut_video_precise`, `_audio*` envolvidos), módulo pequeno de regra só se necessário, `tests/test_ffmpeg_tools_logic.py`, testes de corte.

**Regressões:** C3b, ordem de faixas invertida, faixa escolhida não primeira, sinais distintos L/R. **Gates:** GW e saída real do executável reconstruído. **Risco:** índices de saída errados → testar ordem invertida. **Reversão:** reverter só o trecho novo e bloquear explicitamente o cenário multifaixa que reprova; conservar fonte e pacote de referência completos.

### F2-A — Preparar inventário Android e depois habilitar AAC preciso

**Dependência:** D03 compartilhado, F1 aprovado; usar C3b de F2-W como referência de comportamento, sem copiar implementação global antiga.

**Mudança A:** inventário por faixa e construção de argumentos; sem mudar preset/seek. **Mudança B:** conectar política AAC/cópia à UI do corte de vídeo e seus previews. Testar A antes de B.

**Arquivos:** `FfmpegMediaPolicies.kt`, `FfmpegCutActivity.kt`, layout de corte e testes correspondentes. **Regressões:** C1b quatro bipes, C1 80 quadros, C2 sem áudio, C3b perfis e duas faixas finais; controle de qualidade inativo em cópia. **Gates:** GA para a candidata integrada; artefato no emulador e aparelho de referência.

**Risco:** AAC corrige tempo mas remove faixa ou reduz estéreo → caso combinado obrigatório. **Reversão:** caminho preciso não aprovado oferece modo integral conhecido como válido para o perfil ou recusa explicada; não trocar silenciosamente para cópia de áudio mantendo o nome preciso.

### F3 — Corrigir contrato espacial nos dois apps

**Dependência:** regra D04 fechada em documentação; implementação por plataforma, primeiro Android onde há prova UI→saída, depois Windows.

**Arquivos:** Android `FfmpegPreviewSelection.kt`, `FfmpegCutActivity.kt`, `FfmpegRotateVideoActivity.kt` e testes; Windows `selection_crop_pixels`, preview/confirmadores no painel, `tests/test_ffmpeg_area_selection.py`.

**Regressões:** x/y ímpares, bordas e rotação 90/180/270; confirmar que a seleção visual muda antes de Executar. **Gates:** GA/GW respectivos. **Risco:** aplicar alinhamento em espaço não rotacionado → padrão com identidade visual nas duas orientações. **Reversão:** desabilitar seleções não suportadas com motivo; preservar os caminhos pares aprovados.

### F4 — Corrigir semântica de Inserir e ajuda de Limpar

**Ordem:** Windows rótulos/encaminhamento, Android vocabulário equivalente; a correção de texto de limpeza não muda seu filtro.

**Arquivos:** painel Windows e seus testes/`scripts/ui_smoke.py`; Android `FfmpegInsertAudioActivity.kt`, `FfmpegCleanAudioActivity.kt`, recursos e testes.

**Regressões:** C5, sem transição, fade somente no inserido, crossfade no meio/início/fim; preview/duração e seleção integral explícita. Investigar separadamente 12,03 s. **Gates:** GA/GW. **Risco:** preferências salvas selecionarem outro efeito → preservar intenção histórica com rótulo claro e mostrar o efeito efetivo. **Reversão:** restaurar o modo sem efeito e conservar transições ambíguas desabilitadas na candidata, sem trocar semântica silenciosamente.

### F5-W — Prova de áudio contínuo no SmartCut Windows

**Dependência:** F2-W e perfil de áudio decidido. **Atual:** áudio codificado em três trechos. **Alvo:** vídeo híbrido inalterado, áudio integral do intervalo em uma única passagem.

**Arquivos:** `_cut_video_smartcut`, `_smartcut_segment_arguments`, concat/mux final e testes de corte no painel. **Regressões:** C1/C1b/C1c/C2/C3b; limites de keyframe e sem corpo; comparação A/B com vídeo de referência.

**Gates:** GW e QV/QA/QS na saída real. **Risco:** perder offset A/V ao zerar streams ou limitar áudio pelo tempo do TS → calcular fonte/destino comuns, conservar offsets intencionais; não usar `-shortest` como conserto automático. **Reversão:** desativar nova rota por classe; não apagar a evidência de T07 nem declarar a antiga conforme. Integral preciso é a alternativa oferecida quando necessário.

### F5-A — Aplicar a mesma timeline no Android

**Dependência:** comportamento aprovado em F5-W e F2-A. **Arquivos:** `FfmpegCutActivity.kt`, builders híbridos de `FfmpegMediaPolicies.kt`, remux e testes.

**Regressões:** mesmos fixtures e marcadores, mais aparelho físico/MediaCodec e ausência de software HEVC equivalente. **Gates:** GA e mídia real. **Risco:** muxer Android diferente reintroduzir deslocamento → comparar PTS por etapa e arquivo salvo; manter intermediários exigidos pelo backend.

**Reversão:** encaminhamento explícito para modo preciso validado, sem misturar cabeças de um codec e corpo de outro. A diferença temporária entre candidatas deve ser registrada; não publicar uma como padronização concluída antes da prova cruzada.

### F6 — Limites de cópia e proteção de perfis

**Dependência:** teste adicional N1 e proteção N8/N9 da seção 7. **Ordem:** fechar pontas Windows, estabelecer limites de referência e implementar ajuste necessário Windows/Android; proteger HDR/10-bit por backend antes de ampliar recodificação.

**Arquivos:** corte/media policies/encoders nas duas plataformas; `FfmpegVideoEncoderRegistry.kt` para capacidades Android; painel/`src/video_encoders.py` conforme ownership atual.

**Regressões:** intervalos aproximados iguais ao anunciado, codec preservado, HEVC curto, sondagem desconhecida, HDR recusado/conversão explicitamente escolhida. **Gates:** GA/GW. **Risco:** heurística de hardware mudar codec → primeiro resolver perfil, depois encoder. **Reversão:** recusar a classe não comprovada sem afetar cópia integral compatível.

### F7 — SmartJoin em duas etapas

**Dependência:** F5 fornece estratégia de áudio e testes; N3/N4 dizem quais defeitos existem realmente em Join.

**Etapa 1:** referência/nomes/timeline de UI, sem tocar em emendas aprovadas. **Etapa 2:** corrigir áudio contínuo e pontes que reprovaram, primeiro prova Windows, depois Android.

**Arquivos:** `src/smart_join_planner.py`, caminhos `_smart_join_*` do painel; Android `SmartJoinPlanner.kt`, `FfmpegJoinVideosActivity.kt`, `FfmpegMediaPolicies.kt`; testes dos planners e de artefato.

**Regressões:** duas fontes, centro curto, áudio ausente, várias faixas, sequência longa, perfil de referência diferente, transição sem corpo copiável. **Gates:** GA/GW. **Risco:** sobreposição de pontes e acúmulo temporal → plano verificável antes de renderizar; nenhuma região usada duas vezes indevidamente. **Reversão:** modo integral explícito para a classe reprovada, preservando modos sem transição aprovados.

### F8 — Extração comum

**Dependência:** F2 inventário, N5. **Ordem:** regra comum dos perfis; implementar cópia/seleção Windows; alinhar perfil/UI Android.

**Arquivos:** `_extract_worker`/controles/testes no Windows; Android `FfmpegExtractAudioActivity.kt` e policies/testes. **Regressões:** original compatível, conversão solicitada, faixa não primeira, lote parcial, corte exato/aproximado distintos. **Gates:** GA/GW.

**Risco:** ignorar escolha do operador porque a cópia é possível → perfil Original distinto de Converter. **Reversão:** manter perfil Converter explícito e retirar apenas a otimização de cópia defeituosa; não recodificar mantendo rótulo Original.

### F9 — Limpeza comum

**Dependência:** audição N6. **Mudanças separadas:** primeiro perfis de saída; depois algoritmo Forte/nome próprio escolhido. **Arquivos:** `_clean_worker`/UI/tests Windows; Android `FfmpegCleanAudioActivity.kt`/policies/layout/testes.

**Regressões:** taxa/canais/profundidade, perfil transcrição, audição documentada, ausência de normalização automática. **Gates:** GA/GW. **Risco:** remover fala fraca → não escolher por tamanho ou volume aparente; comparar escuta em níveis equivalentes. **Reversão:** Equilibrado mantém disponibilidade, Forte não aprovado fica como opção histórica identificada ou indisponível, sem troca oculta do filtro.

### F10 — Metadados/rotação e preset

São duas entregas independentes: **F10-M** após N10 (metadados/rotação), **F10-P** após N7 (preset). Não alterar ambas no mesmo diagnóstico.

**Arquivos M:** policies, remux, Rotate/Cut/Join Android; rotas equivalentes Windows. **Arquivos P:** Android `FfmpegVideoQuality.kt`; Windows `_video_args` e encoders quando necessários.

**Regressões M:** capítulos recortados, extras selecionados, orientação sem giro duplo, destino incompatível. **Regressões P:** qualidade/tempo/tamanho controlados, demais perfis sem regressão. **Gates:** GA/GW.

**Riscos:** metadados aparentemente preservados mas falsos; preset mais lento prejudica operação sustentada. **Mitigação:** sondagem e apresentação real; benchmark sustentado. **Reversão:** omissão explícita de metadado não transformável; restaurar perfil de velocidade anterior identificado. Nunca recuperar desempenho reduzindo codec/canais silenciosamente.

## 6. Gates de implementação e build — sem publicação

### GA — Android

Para cada candidata funcional, executar os três gates solicitados no documento do usuário, além dos casos de mídia da fase:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain
```

O gate central em `scripts/validate-agent-harness.ps1` também verifica o mapa de módulos e chama os gates Android; usar a rota canônica conforme o contexto de implementação, evitando repetições sem mudança. Novos fontes de produção precisam de MODULE-MAP e KDoc. Não burlar hooks. Mudanças no harness exigem sua suíte própria; mudanças só em mídia não exigem modificar o harness.

Executar testes de lógica antes da prova real. Prova final usa o APK recém-construído, sua identificação e o arquivo salvo no app. Emulador para regras/software e telefone para MediaCodec/desempenho. Nenhum pacote nativo novo é necessário neste plano inicial.

### GW — Windows

**Correção de nomenclatura do pedido:** o texto anexado chama a suíte de “pytest”, mas o gate canônico lido em `scripts/release.py` usa **unittest discovery**, e também há efeitos de UI testados no mesmo processo. Manter o runner oficial; não trocar de framework para satisfazer o rótulo. Pytest opcional não substitui o gate.

O [runbook UPDATE.md](</D:/Projetos/SIG Windows/UPDATE.md>) e o [contrato de validação](</D:/Projetos/SIG Windows/docs/agents/validation-output.md>) são as fontes operacionais. Modelos de comandos abaixo são apenas para implementação futura autorizada, no diretório Windows, com o Python de build definido no runbook:

```powershell
$sigBuildPython = 'C:\Users\Gustavo\AppData\Local\Programs\Python\Python311\python.exe'
& $sigBuildPython scripts/release.py syntax --quiet
& $sigBuildPython scripts/check_prompt_context.py --quiet
& $sigBuildPython scripts/release.py tests --quiet
& $sigBuildPython scripts/release.py ui-smoke --quiet
& $sigBuildPython scripts/build_dev.py --quiet
& $sigBuildPython scripts/release.py preflight --quiet
```

Cada comando só começa após sucesso do anterior. O preflight inclui suíte, validação, updater-v2-test e UI smoke; a repetição após rebuild é justificada pela checagem do estado/pacote e da sequência integrada. Na iteração local, testes focais antecedem a candidata; evitar rebuild a cada ajuste de uma linha antes da revisão da fase.

`ui-smoke` chama a implementação de `scripts/ui_smoke.py`; não há necessidade de inventar um segundo smoke alternativo. O build de desenvolvimento recompõe `dist/sig.exe` em layout onedir com o ambiente aprovado; testar esse executável no caso de mídia da fase. Não editar `dist/` manualmente como fonte.

Não executar `release`, sync, upload, commit, assinatura de distribuição ou publicação como parte deste plano. Rebuild local futuro deve preservar o pacote anterior completo fora do alvo de build, respeitar processos que usam o alvo e não mexer na instalação de uso do operador. Uma falha de ambiente/gate é registrada como tal; não “corrigir” updater/metadados de release fora do escopo só para deixar verde.

### Evidência comum por fase

Entrada/hash, candidato/build, app/backend, operação, parâmetros, saída/hash, Q aplicáveis, resultados antes/depois e motivo de cada conversão. Comparação de comando isolada ajuda no diagnóstico; aprovação exige artefato do app, incluindo a última gravação/remux.

## 7. Testes restantes que desbloqueiam decisões

T01–T08 já reportados não precisam ser repetidos sem propósito. Tornam-se regressões da fase que altera sua rota. Os testes N abaixo são a prova adicional necessária; não geram automaticamente autorização de alterar o aplicativo.

**Procedimento comum:** abrir a ferramenta no app real, selecionar as entradas indicadas, configurar a opção, registrar o plano/preview, executar e salvar; analisar a saída salva e reproduzi-la no player-alvo. Usar builds atuais para diagnóstico e candidatas para regressão. Para Windows e Android, registrar resultados separados; modo ausente é “não aplicável”, nunca aprovação por suposição.

### N1 — Pontas de Sem Reencode no Windows e política comum

**Objetivo:** decidir quais limites precisam mudar em D07. **Entrada:** C1/C1b; cortes em keyframe e entre keyframes.

**Procedimento:** nos dois apps, escolher Sem Reencode, [1,4;4,6), guardar confirmação e arquivo; identificar o primeiro/último conteúdo visual e sonoro. Depois repetir um intervalo que começa/termina exatamente em pontos seguros.

**Medir:** PTS, identidade de pontas, conteúdo extra, inventário. **Aprovar:** limites efetivos da política D07 aparecem no app e correspondem à saída; codec copiado. **Reprovar:** início oculto distinto do anunciado, perda da seleção pretendida ou recodificação silenciosa. **Inconclusivo:** tabela sem identidade de conteúdo, apenas duração. **Desbloqueia:** F6, não F1–F4.

### N2 — SmartCut de fronteira e áudio contínuo

**Objetivo:** validar D06 além do intervalo favorável. **Entrada:** C1/C1b/C1c/C2 e equivalente HEVC confirmado por probe.

**Procedimento:** no app, testar [1;4), [1,4;1,8) sem corpo copiável e [1,4;4,6); com e sem áudio; CPU e hardware disponíveis. Na candidata contínua, mesma seleção e mesmo perfil da referência.

**Medir:** QV/QA/QS; encoder efetivo das bordas; fallback. **Aprovar:** quadros e marcadores previstos, nenhum degrau temporal extra, fallback informado se não houver corpo/encoder equivalente. **Reprovar:** ausência de áudio impede C2; mix de codecs; atraso por emenda; descarte de pontas. **Inconclusivo:** variante só em comando de outro ambiente. **Desbloqueia:** F5-W/F5-A por classe de mídia.

### N3 — SmartJoin com transição e centro curto

**Entrada:** dois clipes controlados de 10 s e um terceiro curto; IDs visuais e sons distinguíveis, ausência de áudio em uma variante.

**Procedimento:** no app, primeiro sem transição; depois Dissolver 0,2 s; depois sequência cujo clipe central não comporte as duas transições. Registrar ajuste/rejeição antes de executar. Repetir em integral como referência sem exigir hash visual idêntico nas regiões recodificadas.

**Medir:** efeito de 0,2 s restrito à janela, soma de durações, corpos e pontes, áudio ausente preenchido somente conforme plano. **Aprovar:** 19,8 s úteis para dois clipes de 10 s com uma sobreposição; QA/QS e nenhuma sobreposição técnica involuntária. **Reprovar:** ampliar efeito porque GOP é longo, usar quadros duas vezes ou truncar centro. **Inconclusivo:** só avaliar o último frame. **Desbloqueia:** D08/F7.

### N4 — Crescimento de atraso em sequências longas

**Entrada nova proposta:** 2, 5 e 20 clipes derivados do mesmo corpus, com IDs/grade C1c; essas quantidades são desenho de teste, não resultados existentes.

**Procedimento:** juntar pelo app em SmartJoin, sem transição e depois com sobreposição conhecida; comparar com timeline de referência que considera todas as transições. Repetir nos dois ambientes, evitando confundir tempo esperado de crossfade com erro.

**Medir:** posição de cada marcador antes/depois de cada emenda e diferença áudio/vídeo; regressão do erro com número de junções. **Aprovar:** QA/QS sem crescimento além do limite definido; mesmos marcadores previstos. **Reprovar:** atraso cresce mesmo com duração final aparentemente plausível. **Inconclusivo:** só duração total ou correlação global. **Desbloqueia:** prioridade da correção de áudio Join e aceitação de sequências longas.

### N5 — Extrair e lotes

**Entrada:** C3b, arquivo de áudio compatível com saída original, C2 sem áudio e arquivo menor que intervalo.

**Procedimento:** selecionar faixa B no app e exportar Original; repetir Converter e Transcrição local; executar lote contendo item sem áudio e curto. Comparar comando real e arquivo entregue, incluindo caso em que o operador muda bitrate explicitamente no perfil Converter.

**Aprovar:** Original preserva conteúdo; Converter obedece ao perfil; B continua sendo a faixa B; itens ajustados/ignorados discriminados; QF/QU e QA quando exato. **Reprovar:** bitrate/mono selecionado ignorado sem explicação ou recodificação escondida sob Original. **Inconclusivo:** verificar só extensão. **Desbloqueia:** F8.

### N6 — Audição dos dois modos Forte

**Entrada nova:** passagens aprovadas de fala fraca, ruído constante, teclado/transientes e fala sobreposta; usar também controles estéreo. Não usar material de produção sem autorização específica.

**Procedimento:** gerar pelos apps Equilibrado e Forte atuais, com a mesma taxa/canais de saída para isolar algoritmo; comparar também proposta de algoritmo comum em candidata. Apresentar ordem alternada sem identificar algoritmo, igualando apenas o nível de reprodução para julgamento; conservar arquivos sem essa compensação como evidência.

**Medir:** inteligibilidade de trechos/sílabas anotados previamente, ruído musical/pombeamento, tempo e resposta da UI no aparelho. **Aprovar candidato:** não perder trechos inteligíveis da referência e obter redução de ruído útil sem artefato considerado inaceitável, com julgamento documentado. **Reprovar:** eliminar voz/partes relevantes, ainda que ruído total diminua. **Inconclusivo:** ouvintes discordam ou corpus não contém a dificuldade alegada; usar nomes distintos até decidir. **Desbloqueia:** Forte comum de D10; não bloqueia correção de ajuda/default Equilibrado.

### N7 — Preset comum

**Entrada nova controlada:** trechos com texto/detalhes, movimento, baixa luz e uma duração sustentada representativa do uso. **Procedimento:** pelo app/candidata, fixar qualidade Alta CRF 20, áudio, resolução e FPS; alternar `medium`, `fast`, `veryfast`, `ultrafast`, com condição inicial comparável; testar CPU real no aparelho e no PC. Não usar caminho hardware para medir preset libx264.

**Medir:** tempo, tamanho, falhas, uso de memória observável, estabilidade térmica e detalhes visuais em regiões pré-selecionadas. **Aprovar `fast`:** nenhum detalhe de referência relevante piora de forma inaceitável, execução sustentada estável e tempo operacional aprovado pelos responsáveis. **Reprovar:** travamento/recursos ou qualidade inadequada. **Inconclusivo:** áudio/duração distintos, só clipe muito curto ou ausência de limite operacional aceito. **Desbloqueia:** F10-P; se inconclusivo, mantém presets atuais identificados sem mexer na qualidade.

### N8 — HEVC curto e fallback

**Entrada:** HEVC com corpo/bordas curtos e longa duração em outra variante. **Procedimento:** escolher hardware, CPU e automático no app conforme disponibilidade; observar a decisão <3 s; provocar falha controlada do backend em ambiente de teste, separada de erro de entrada/armazenamento.

**Aprovar:** CPU só quando equivalente; HEVC hardware continua elegível se não houver x265; uma repetição equivalente por tarefa; codec/perfil real conforme anunciado. **Reprovar:** H.264 oculto, loop ou erro de arquivo classificado como GPU. **Inconclusivo:** teste apenas em emulador para alegação de MediaCodec físico. **Desbloqueia:** proteção D11/F6 e classes HEVC em F5.

### N9 — VFR, taxa fracionária, offset A/V, HDR/10-bit

**Entradas novas:** VFR confirmado, 30000/1001, offset A/V intencional conhecido, SDR 10-bit e HDR com metadados. **Procedimento:** no app executar corte preciso, SmartCut e cópia conforme elegibilidade; registrar plano de conversão/recusa. Repetir após a proteção de perfis.

**Aprovar:** QS preserva offset de origem; cadência/perfil não mudam silenciosamente; rota incapaz recusa/explica sem bloquear cópia compatível. **Reprovar:** converter VFR para CFR ou 10-bit/HDR para 8-bit/SDR sob promessa de preservação. **Inconclusivo:** perfil de entrada não confirmado ou só tags verificadas sem pixels/conteúdo temporal. **Desbloqueia:** classes elegíveis e proteção D12; não obriga suporte completo HDR.

### N10 — Capítulos, extras e rotação

**Entrada nova:** vídeo com matriz 90°, capítulos antes/dentro/depois do corte, legenda, anexo, faixa extra; destino compatível e incompatível.

**Procedimento:** no app cortar, girar por pixels/metadados e juntar; escolher extras; reproduzir e sondar o arquivo salvo. Testar crop após giro com padrão espacial conhecido.

**Aprovar:** capítulos/tempos verdadeiros, QF/QC, orientação sem giro duplo; destino incompatível gera decisão explícita. **Reprovar:** título/rotação antiga contradiz imagem, perda silenciosa ou capítulo fora da timeline sem indicação. **Inconclusivo:** só comando/mapa, sem artefato final. **Desbloqueia:** F10-M.

### N11 — Entrega/cancelamento e inventário integrado

**Entrada:** C3b e arquivo descartável mais longo. **Procedimento:** no app cancelar durante encode e remux; testar nome já existente, destino sem permissão/quota controlada e nova execução depois da falha. Não ocupar todo o disco principal para simular espaço esgotado.

**Aprovar:** originais intactos, nenhum arquivo incompleto oferecido, inventário correto em sucesso, limpeza/repetição funcionam e QU. **Reprovar:** apagar arquivo válido, perder temporário recuperável antes da validação ou repetir encoder em falha de armazenamento. **Inconclusivo:** apenas mock de retorno de comando. **Desbloqueia:** entrega de F1/F2-A e qualquer mudança de temporários posterior.

## 8. O que decido não mudar

| Diferença | Decisão e motivo |
|---|---|
| NVENC/QSV/AMF versus MediaCodec | Manter: a plataforma determina a API física. Padronizar capacidade/resultado/fallback |
| Ausência de libx265 Android | Manter inicialmente: não resolve faixas/crop/timeline e amplia pacote/ABIs/recursos. Reavaliar só por requisito explícito |
| FFmpeg 8.0.1 Windows versus pacote Android informado como 6.1.1 | Não atualizar juntos nesta entrega. Testar o contrato com cada binário identificado e verificar capacidades; o nome do pacote não substitui a identificação do build real |
| MP4/MKV intermediários | Manter quando necessários ao backend. A seleção e os timestamps finais é que precisam cumprir o contrato |
| Tempos e tamanho de saída | Não exigir igualdade. Exigir mesma seleção/qualidade-alvo, sem esconder conversão ou redução de informação |
| Paralelismo e número de threads | Manter específicos e conservadores; otimização futura separada de correção de mídia |
| Núcleo de cópia de vídeo SmartCut aprovado | Preservar. A mudança de áudio não deve reencodar o miolo nem trocar seu codec |
| Smart Insert ausente no Android | Não portar agora. Igualdade do significado de operações comuns vem primeiro; capacidade adicional Windows fica identificada |
| STT, ocorrência e envio a provedores | Fora do escopo. Nenhuma chave, upload real ou mudança de contrato de transcrição |
| Distribuição/updater e nativos | Não alterar nem publicar; rebuild de desenvolvimento futuro segue gates, não significa release |
| `.amr` e outras exceções documentadas | Não unificar extensões por busca/substituição. Testar ferramenta/codec antes de ampliar aceitação |

## 9. Reversão, riscos transversais e critério de conclusão

Antes da implementação, preservar fontes locais do usuário e artefatos de referência completos, sem sobrescrever evidências. Preferir patches pequenos por fase e ponto de retorno identificado. A reversão é seletiva da candidata; nunca `reset --hard` ou limpeza global de workspace.

Uma versão antiga pode continuar sendo referência de comparação mesmo contendo defeito conhecido. Ela não deve ser reapresentada como conforme depois de retirar uma correção. Se a candidata falhar, o cenário inseguro permanece explicitamente limitado enquanto é corrigido.

Os riscos principais são: corrigir tempo e perder faixa no mux; corrigir faixa e impor o perfil de outra; eliminar duração extra cortando conteúdo; mascarar salto com fade; desabilitar HDR e bloquear indevidamente cópia; trocar preset junto com algoritmo e perder atribuição da regressão. A ordem F1→F2→F3/F4→F5 e os casos combinados evitam essas confusões.

Considerar uma fase pronta quando: gates reais passaram, saídas do app cumprem Q aplicáveis, comparação antes/depois está registrada, nenhuma conversão relevante ficou oculta e o escopo de aprovação está identificado. Não prometer ausência universal de bugs; registrar explicitamente classes não testadas.

**Próximo trabalho recomendado após autorização:** F0, F1 e F2, com correções de áudio/remux por faixa e regressões combinadas. Em seguida F3/F4, depois a prova de áudio contínuo F5. Audição Forte e benchmark podem ser preparados sem bloquear as primeiras correções.

## 10. Referências e rastreabilidade

- [Documento de decisão fornecido pelo usuário](</C:/Users/Gustavo/.codex/attachments/9030be93-bfd0-43fe-8d37-9c627721b5fc/pasted-text.txt>): fonte dos resultados consolidados, inclusive T07 e contagens corrigidas.
- [Rodada A anterior](/D:/Projetos/SIG/docs/rodada-a-resultados-20260913.md): primeira medição; detalhes corrigidos pelo documento mais recente prevalecem.
- [Parecer anterior, revisão 2](/D:/Projetos/SIG/docs/parecer-resposta-padronizacao-ffmpeg-e-testes-20260913.md): histórico de hipóteses/testes; dúvidas sobre quarto bipe e 79–80 foram respondidas no documento novo.
- [AGENTS Android](/D:/Projetos/SIG/AGENTS.md), [gate central Android](/D:/Projetos/SIG/scripts/validate-agent-harness.ps1), [AGENTS Windows](</D:/Projetos/SIG Windows/AGENTS.md>), [runbook Windows](</D:/Projetos/SIG Windows/UPDATE.md>).
- [Painel Windows](</D:/Projetos/SIG Windows/src/ffmpeg_tools_panel.py>), [runner de validação Windows](</D:/Projetos/SIG Windows/scripts/release.py>), [build de desenvolvimento](</D:/Projetos/SIG Windows/scripts/build_dev.py>).

As referências oficiais de FFmpeg e Android foram consultadas para orientar o desenho; não servem como prova de que uma candidata já passou nos testes. Esta entrega contém decisões e plano, sem execução de mudanças nos aplicativos.
