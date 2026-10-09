# Entrega do Granite STT ao especialista de arquitetura e aceleração

Data da entrega: 08/10/2026. Projeto SIG Android. Destino: agente especialista com acesso ao repositório, às ferramentas disponíveis no seu ambiente e a um agente executor separado. Modelo exato e ferramentas do destinatário devem ser conferidos por ele; o texto não depende de um ID de modelo ou ferramenta inventado.

Você assume a responsabilidade técnica pelo Granite STT, com prioridade no Granite Speech 4.1 2B NAR e em português brasileiro. Seu primeiro trabalho é produzir um plano de ação extremamente detalhado, apoiado na inspeção do estado real. Use esse plano como orientação permanente para delegar tarefas ao executor, revisar as entregas e conduzir a implementação até opções utilizáveis de qualidade, velocidade e hardware.

O usuário quer, se tecnicamente viável, CPU, GPU OpenCL, GPU Vulkan, NPU e Smart com prefill na NPU e decode na GPU OpenCL ou Vulkan. Esses são objetivos de pesquisa e implementação. Você tem liberdade para mudar profundamente o plano, os formatos de modelos, a divisão entre estágios e os runtimes quando houver fundamento técnico. As tentativas históricas não devem ser presumidas como as melhores soluções.

## 1 Sua responsabilidade e a responsabilidade do executor

Você é o especialista: decide arquitetura, hipóteses, prioridades, critérios de aprovação, validade das medições e integração de produto. O executor realiza tarefas delimitadas: inventário, downloads, exports, quantização, builds, instrumentação, testes, captura de evidências e patches conforme suas ordens.

Delegar ao executor faz parte do pedido do usuário. Use o mecanismo realmente disponível para esse agente, respeitando seu contrato. Se o executor for outra tarefa acessível, identifique-a antes de enviar qualquer mensagem; nunca invente seu ID nem envie instruções a uma tarefa escolhida só pelo título. Se o vínculo ainda não estiver disponível, produza a primeira ordem executável como documento e avance na inspeção independente. Não presuma que criar um subagente equivale a enviar trabalho ao executor externo.

Sua primeira entrega deve ser o plano completo, seguida da primeira ordem de execução. A existência de um plano não encerra sua responsabilidade: você deve acompanhar as entregas, conferir evidências, corrigir decisões e gerar as ordens seguintes. Peça ao usuário apenas escolhas materiais ou recursos realmente ausentes; resolva decisões técnicas rotineiras com as informações disponíveis.

Cada ordem ao executor deve conter:

1. Objetivo concreto e pergunta que o experimento resolve.
2. Host, checkout, commit e arquivos de entrada esperados, que deverão ser confirmados.
3. Escopo de arquivos que podem mudar e ownership de arquivos compartilhados.
4. Configuração por estágio e contrato efetivo a observar.
5. Comandos ou procedimentos verificáveis, com dependências e ordem.
6. Critérios de aprovação, invalidação, interrupção e restauração.
7. Artefatos e evidências obrigatórios, com nomes e localização.
8. Condições explícitas para avançar, repetir um ensaio ou devolver a decisão ao especialista.

Depois de receber uma entrega, confronte o relatório com fontes e eventos. Um relatório do executor é uma descrição do que ele acredita ter feito; o arquivo, a configuração observada e o evento do runtime estabelecem o que foi efetivamente executado. Não exigir repetição de uma prova já íntegra sem mudança relevante. Não acumular dezenas de tarefas pendentes sob o nome de entrega final.

## 2 Objetivo de produto e liberdade para revisar a estratégia

O usuário quer opções comparáveis, em espírito, a tiny/base/small/medium/turbo do Whisper: algumas mais rápidas, outras mais precisas ou menores. O interesse é PT-BR. Outros idiomas podem servir como testes de regressão, mas não devem consumir a campanha principal.

O produto deverá distinguir duas escolhas relacionadas:

- Modo de transcrição: referência de precisão, intermediário, compacto e rascunho, quando esses perfis forem demonstrados.
- Forma de execução: CPU, GPU OpenCL, GPU Vulkan, NPU e híbrido Smart, quando houver implementação, compatibilidade e benefício reais.

Os nomes anteriores Máxima, Equilibrado e Leve já aparecem na UI, mas seus números derivam de medições históricas de componentes e nem sempre do pipeline Android completo. Você deve revisar as promessas de produto à luz das evidências atuais. Uma variante do editor não equivale a uma variante completa do sistema. Aceleração também não é uma garantia de maior velocidade.

Você pode substituir ORT/QNN por outro runtime em uma rota experimental, reexportar partes do modelo, usar execução nativa, estudar kernels específicos, criar novos formatos quantizados ou estáticos e redesenhar a lógica de seleção de backend. Preserve a semântica do modelo na comparação de equivalência. Se alterar a arquitetura ou usar outro modelo, rotule a experiência e avalie-a como uma alternativa de produto, com suas próprias referências e contratos.

Ordens antigas restringiam upgrades, GPU, LLM-NPU e quantização em lote para limitar rodadas específicas. Este pedido reabre a investigação estratégica desses caminhos. A liberdade nova não autoriza ignorar falhas conhecidas: cada reabertura precisa de hipótese, diferença concreta em relação ao teste anterior, piloto pequeno e orçamento. Não há obrigação de continuar um método só porque ele já recebeu muito trabalho.

Priorize a melhor relação entre precisão, velocidade, memória, armazenamento, tempo de carga e robustez. Um perfil que ganha um eixo pode ser útil sem dominar todos os outros. Justifique a seleção dos sobreviventes com medições comparáveis, incluindo o custo de compilação, conversão e movimentação entre CPU, GPU e DSP.

## 3 Estado confirmado na branch principal em 08/10/2026

Checkout local: `D:\Projetos\SIG`.

Na inspeção desta entrega:

- Branch: `main`.
- HEAD: `21439199ed28086139c2bbbe64ce4e4e18de77d9`.
- Android: compileSdk 35, minSdk 24, targetSdk 35.
- Dependência de inferência Granite: `com.microsoft.onnxruntime:onnxruntime-android-qnn:1.29.0`.
- Os arquivos `.so` ORT são excluídos do APK pelo empacotamento e vêm do contrato de dependências nativas. A versão do AAR sozinha não prova os bytes realmente carregados no telefone.
- O Granite 4.1 NAR usa CPU no fluxo normal. GPU e NPU são desabilitadas no menu para esse modelo; ao escolhê-lo com backend acelerado selecionado, a Activity troca para CPU.
- A infraestrutura enumera CPU, GPU QNN/Adreno e NPU QNN/HTP. O backend GPU usa `libQnnGpu.so`; HTP usa `libQnnHtp.so` e componentes associados.
- Não existe nesta rota do Granite uma implementação direta nem seleção de GPU OpenCL ou Vulkan. Opções OpenCL/Vulkan de outras ferramentas do SIG não comprovam implementação Granite.
- A UI oferece variantes do editor: float/fp16, int8b-blk128 e int4b-blk128. A preferência padrão é float. O editor de 2 bits está registrado como reprovado e fica fora da UI.
- Existem buckets de encoder/projector 200, 400, 800, 1200, 1600 e 2000. A escolha atual considera os buckets instalados e usa o menor que comporte a entrada.
- A branch principal contém correções de integridade do manifesto e parte das alterações de buckets. Ela ainda não contém as melhorias experimentais completas de ArgMax, CTC, contexto EP e parser novo de vocabulário descritas adiante.

Provas de código importantes:

- `D:\Projetos\SIG\app\src\main\java\br\gov\sp\pcsp\launcher\GraniteActivity.kt`: `selectModel`, `showBackendMenu`, `showNarVarianteDialog`, `startGraniteTranscription` e a chamada de `GraniteNarEngine.transcribeFile`.
- `D:\Projetos\SIG\app\src\main\java\br\gov\sp\pcsp\launcher\GraniteNarEngine.kt`: frontend, CTC, variantes, manifesto, buckets, carga, sessões ORT e inferência.
- O enum `GraniteExecutionBackend` está em `GraniteEngine.kt`, não em um arquivo separado chamado `GraniteExecutionBackend.kt`.

Reconfirme esse snapshot ao iniciar: a branch pode ter avançado. O APK instalado no telefone precisa de identidade própria; não deduzi-lo de `main` ou de versionName.

Na inspeção havia mudanças de outra tarefa em `FfmpegJoinVideosActivity.kt`, `SmartJoinPlanner.kt` e testes associados, além de documentos de especialistas. Preserve-as. Não trate a árvore inteira como sua entrega nem limpe arquivos sem estabelecer ownership.

## 4 O que a ferramenta Granite STT faz no SIG

Granite é uma ferramenta local de reconhecimento de fala, acessível pelo hub Ferramentas. A tela `GraniteActivity` atende dois motores:

1. Granite Speech 5.0 TurboCTC, motor separado, com foco histórico em inglês.
2. Granite Speech 4.1 2B NAR, multilíngue com português e prioridade desta entrega.

No fluxo atual, o usuário seleciona arquivos ou pasta, pode visualizar o áudio e escolher um intervalo, seleciona modelo e, no NAR, variante do editor. A Activity prepara a mídia usando a infraestrutura FFmpeg e converte a entrada efetiva para WAV 16 kHz mono compatível com o motor. A tela também possui preparação de áudio e VAD; o papel exato dessas opções e de eventual compactação deve ser conferido no código atual antes de usar essas etapas como parte de um benchmark.

O processamento suporta lotes, progresso, cancelamento, mensagens de carga, logs e gravação de transcrições individuais. A ferramenta gera TXT e HTML agregados, com informações de execução, duração e backend. A Activity calcula tempo de carga do modelo e tempo do processamento, mas esses campos devem ser auditados quando a definição do pipeline mudar.

O motor é on-device. Downloads do R2 servem para instalar modelos e dependências; não constituem inferência remota. A ferramenta Transcrição remota, em `RemoteSttActivity.kt`, é outro fluxo do app e um hotspot de risco. Não confundir os dois só porque ambos têm referências a Granite.

O desenho de interface desejado mantém essas capacidades e torna as opções de qualidade/hardware compreensíveis. Backend indisponível deve ter motivo útil. Fallback para CPU deve ser declarado. Uma transcrição feita na CPU após rejeição do acelerador não pode ser relatada como execução GPU/NPU.

Áudios longos exigem uma política real de segmentação, cobertura, recomposição e memória. A gravação pessoal de 59 segundos foi processada nos ensaios por segmentos; isso não prova que o fluxo principal já segmenta automaticamente qualquer arquivo longo. Inspecione o caminho de produção antes de prometer essa capacidade.

## 5 Arquitetura real do Granite 4.1 NAR

A fonte oficial é [IBM Granite Speech 4.1 2B NAR](https://huggingface.co/ibm-granite/granite-speech-4.1-2b-nar), consultada em 08/10/2026. O modelo faz edição não autoregressiva de uma hipótese CTC, com editor bidirecional em uma passagem. Essa característica é decisiva para o Smart solicitado.

O pipeline Android exportado, documentado e implementado no projeto é:

```text
Mídia selecionada
  -> WAV 16 kHz mono
  -> frontend acústico NAR
  -> encoder Conformer e cabeça BPE CTC
  -> hipótese CTC inicial e features acústicas de múltiplas camadas
  -> projector Q-Former
  -> concatenação de audio embeddings e hipótese intercalada com blanks
  -> editor LLM bidirecional
  -> ArgMax e collapse CTC na região textual
  -> decodificação BPE byte-level para UTF-8
```

### 5.1 Frontend

O frontend NAR é próprio. Usa filtros mel e janela STFT do pacote, 80 bandas mel, empilhamento de dois frames e saída de dimensão 160. Ele não deve receber, por reaproveitamento automático, AGC/deltas do TurboCTC.

Leia as constantes e o processamento real, incluindo sample rate, hop, padding reflect, normalização e tratamento de cauda. A documentação inicial contém estimativas de duração desatualizadas. No código atual o limite vem de `frames <= T_FIXED`, com `T_FIXED=2000`, e a duração é calculada a partir do hop e do empilhamento. Não repetir um teto universal de 37 s. Os 37,14 s foram um caso de OOM, não uma propriedade universal da arquitetura.

### 5.2 Encoder

O encoder exportado recebe `input_features` float32 com forma `[1,T,160]`. O export original era T=2000 fixo; os experimentos e o pacote v2 passaram a usar buckets estáticos.

Saídas históricas relevantes:

- `encoder_bpe_logits`: aproximadamente `[1,ceil(T/4),100352]`, conforme o grafo concreto.
- `multilayer_features`: `[1,T,4096]`, combinação de camadas do encoder.

A inferência usa somente os frames válidos calculados a partir da entrada real. A exportação original não expunha `attention_mask`, embora um README antigo dissesse que sim. Shapes, máscara e comportamento de padding devem ser conferidos em cada artefato.

Os zeros de padding não são necessariamente neutros. Uma forma estática maior pode alterar atenção, texto e tempo. Mudança de bucket precisa de qualidade e equivalência no escopo aplicável, não só `onnx.checker`.

### 5.3 Projector

O projector recebe `multilayer_features` e produz embeddings acústicos de dimensão 2048. No export original T=2000, a saída tinha 402 posições por padding interno do Q-Former. O motor consome a parte correspondente à duração válida, calculada historicamente por `realFrames/5`.

A saída acústica é dividida por 12 antes de entrar no editor. O grafo do editor multiplica a entrada por 12. Mudar esse par de escala degrada a inferência sem necessariamente causar exceção.

### 5.4 Hipótese, embeddings e editor

O CTC inicial usa ArgMax, collapse de repetições consecutivas e remoção de blank na ordem correta. Blank histórico: 100257. Vocabulário: 100352 peças. Ids e símbolos especiais devem ser vinculados ao tokenizer exato do pacote.

A hipótese é intercalada com slots de inserção/blanks e convertida em embeddings. O arquivo `nar_embed_tokens.bin`, aproximadamente 411 MB, guarda os embeddings token-major fp16 gerados a partir da transposta de `lm_head` no export de origem. O motor usa mmap e converte apenas os vetores necessários.

O editor recebe `inputs_embeds [1,S,2048]` e `position_ids [1,S]`, com S ligado ao número de posições acústicas e slots da hipótese. O contrato original devolve `logits [1,S,100352]`; o derivado experimental devolve `token_ids` após ArgMax.

O decoder final recorta a região textual pelo offset acústico, preserva blank e collapse e converte tokens BPE para UTF-8. Uma saída repetida pode nascer nos IDs CTC iniciais, nas features acústicas, no editor ou na recomposição; a localização precisa ser demonstrada.

### 5.5 Consequência para prefill e decode

O editor NAR não gera token por token com o esquema causal usual. “Decodificar BPE/CTC” aqui não significa a etapa de geração autoregressiva de um LLM causal. Você deve avaliar o Smart de duas maneiras:

1. Para preservar o NAR, estudar um híbrido por estágio, como encoder acústico na NPU e projector/editor na GPU OpenCL ou Vulkan. O nome e a explicação de produto devem refletir o trabalho real.
2. Para atender literalmente prefill NPU + decode GPU, verificar se existe uma alternativa autoregressiva compatível com Granite Speech e com o produto. Tratar isso como uma rota/modelo adicional, com conversão, caches, qualidade, memória e identidade próprios. Não mudar a atenção bidirecional do NAR para causal e chamar o resultado de equivalente.

É legítimo concluir que o Smart literal não se aplica ao NAR, manter Smart por estágios e abrir uma pesquisa separada para modelo autoregressivo. Essa conclusão deve vir da arquitetura e das opções reais, sem reduzir a meta do usuário a uma troca de rótulo.

## 6 Onde estão o código experimental e as evidências

Há três locais com papéis diferentes:

| Local | Papel |
|---|---|
| `D:\Projetos\SIG` | Checkout principal do app |
| `D:\SIG-perfis-20260914-123811` | Worktree experimental do NAR |
| `D:\SIG-granite-nar-lab-rebuild` | Artefatos, ambientes, scripts, corpus e evidências |

Worktree experimental confirmada em 08/10:

- Branch `codex/granite-perfis-20260914-123811`.
- HEAD `0036f14` na inspeção.
- Arquivos não rastreados `hs_err_pid52044.log` e `replay_pid52044.log`; não apagar como parte da integração.
- Há centenas de linhas de diferenças no engine e novas classes de suporte. Não copiar o engine inteiro sobre a branch principal sem comparar divergências posteriores.

A bateria histórica principal fica em:

`D:\SIG-granite-nar-lab-rebuild\nar-next-20260914-0950`.

O relatório mais recente encontrado para esta entrega é:

`D:\SIG-granite-nar-lab-rebuild\nar-next-20260914-0950\handoff\entrega-perfis-reais\RELATORIO-FINAL-20260925.md`.

Leia também `marco-01..11`, `APENDICE-DADOS.md`, `ERRATA-ROTAS-CONTRATOS-20260916.md`, `memoria-publicos.md`, seus JSONs e os logs citados. Um `FINAL_REPORT` anterior pode conter conclusões corrigidas depois. Monte uma cronologia de substituição das conclusões, sem escolher o relatório mais otimista.

O disco E: apresentou falha e a reconstrução foi feita em D:. Não iniciar varreduras ou recuperação em E: para esta tarefa. O R2 não contém necessariamente scripts, corpora, logs, ambientes e todos os artefatos locais; inventarie antes de assumir que tudo é recuperável pelo bucket.

## 7 Histórico técnico que deve orientar a pesquisa

### 7.1 QNN e FastRPC não são um beco sem saída universal

Houve um diagnóstico inicial de que Android 16 impedia HTP sem root. Foi refutado. O aparelho expõe `libcdsprpc.so` e `libadsprpc.so` como bibliotecas públicas do vendor; o app precisava da declaração opcional `uses-native-library` no manifesto e do carregamento correto.

Correções históricas importantes:

- Declarar as bibliotecas públicas do fabricante no `AndroidManifest.xml`.
- Carregar FastRPC público antes do stub HTP, em vez de copiar bibliotecas de vendor para a pasta privada e criar resolução transitiva errada de `libhidlbase.so`.
- Usar `android.system.Os.setenv` para `ADSP_LIBRARY_PATH`; propriedade Java não substitui getenv nativo.
- Usar caminho absoluto para libs QAIRT baixadas quando a busca do APK não as encontra.
- Registrar diretório nativo e manter ordem de carregamento dos componentes.

O OnePlus 15 de testes era CPH2747, SM8850, Snapdragon 8 Elite Gen 5, Android 16, HTP v81, serial histórico `3B15BD00FVE00000`. Confirme telefone, serial, conexão e exclusividade atuais. Validator de shell e inferência em UID normal do app são provas distintas.

### 7.2 GPU QNN falhou nos pilotos existentes

TurboCTC apresentou rejeição de Slice/StridedSlice 3110. A conversão Slice para Gather removeu essa rejeição, mas continuou ocorrendo finalização 6020 em variantes FP16 e float.

No NAR, o piloto float t200 chegou à finalização QNN e falhou com 6022. Logs verbosos mostraram padrões de atenção com Reshape/Transpose, sem localizar uma causa exata num nó reproduzível. O controle CPU executou.

Esses resultados estabelecem falha dos artefatos e versões testados. Não estabelecem impossibilidade de GPU OpenCL/Vulkan, nem de todos os caminhos QNN. Verbose é instrumentação, não correção. Investigar um novo runtime ou grafo é permitido quando o especialista justificar um experimento novo.

### 7.3 NPU NAR executou partes úteis, com restrições

Encoder quantizado chegou a executar na rota HTP; projector e editor permaneceram CPU nos candidatos híbridos prioritários. Houve falhas graves quando se tentou conjunto maior/residência de pesos incompatível no DSP, incluindo QNN 6001, `Bad VA`, `setup_mempools` e mapeamento FastRPC.

O bucket t2000 acelerado foi restringido; t200/t400 são o início histórico dos pilotos. Um portão anterior à criação de sessão evita que combinações conhecidas como incompatíveis cheguem ao runtime.

O editor int4/int8 usa operadores `com.microsoft::MatMulNBits` no contrato testado. Isso não equivale a um grafo QDQ suportado integralmente pelo HTP. Quantização menor para CPU não garante quantização executável na NPU. A incompatibilidade é específica do runtime/grafo testado, não uma impossibilidade matemática de todo editor no DSP.

### 7.4 Exportações estáticas e correções de grafo

Existem scripts para exportação estática, constant folding e transformação de Einsum em MatMul. O export original tinha Einsum na atenção relativa; uma decomposição mantinha o contrato e ajudava a explorar suporte QNN. Uma alternativa baseada em multiplicação/redução foi descartada pelo intermediário enorme.

Sessões aceleradas usavam NO_OPT para evitar transformações ORT incompatíveis; isso também impede certos folds em runtime. A preparação offline de subgrafos constantes foi estudada. O especialista pode mudar essa política, medindo suporte, memória, semântica e texto.

Um nome `fp16.onnx` não estabelece dtype efetivo: no profiling de setembro foi encontrado um arquivo assim nomeado que continha centenas de QuantizeLinear/DequantizeLinear e pesos INT8/UINT8. Examine o grafo, as opções e os hashes.

## 8 Resultados históricos válidos e suas limitações

Os resultados abaixo são antecedentes para decidir novos pilotos. Eles não aprovam automaticamente um perfil ou backend para produção.

### 8.1 Definição dos candidatos

| ID histórico | Encoder | Editor | Papel |
|---|---|---|---|
| REF ou R | Artefato de referência float/fp16 | Editor de referência float/fp16 | Comparação de precisão |
| EQ ou E8 | Encoder U8/QDQ | Editor int8b-blk128 | CPU mais rápido, com perda observada |
| EF | Encoder U8/QDQ | Editor float/fp16 | Ablação do editor |
| H8 | Encoder de REF | Editor int8b-blk128 | Preservação de precisão com editor menor |
| H4 | Encoder de REF | Editor int4b-blk128 | Ablação compacta dirigida |
| Q4 | Encoder U8/QDQ | Editor int4b-blk128 | Compacto candidato |
| CTC | Somente encoder e decoder CTC | Sem projector/editor | Rascunho candidato |
| N1 | Encoder pedido em HTP | Projector/editor CPU | Aceleração híbrida experimental |
| N2 | Encoder com contexto EP gerado/carregado | Projector/editor CPU | Piloto de cache NPU |

Esses IDs dependem de descritores/artefatos concretos. Um número em uma pasta não substitui a configuração por estágio. Há runs N2 rotulados U8 que efetivamente usaram encoder de REF; use a errata e as identidades.

### 8.2 Comparação de qualidade nos 24 públicos

Na comparação norm-v2 de 24 áudios, com 255 palavras e 1242 caracteres de referência:

| Perfil | Erros de palavras | WER micro | Erros de caracteres | CER micro |
|---|---:|---:|---:|---:|
| REF | 26 | 10,1961% | 64 | 5,1530% |
| EQ | 40 | 15,6863% | 84 | 6,7633% |
| N1 | 35 | 13,7255% | 83 | 6,6828% |

Convenção de delta: candidato menos referência. Positivo significa mais erro.

- EQ − REF: +5,4902 pontos percentuais WER; +1,6103 CER.
- N1 − REF: +3,5294 WER; +1,5298 CER.
- Q4 − EQ na expansão: aproximadamente −0,78 WER; −0,16 CER.
- Q4 − REF: aproximadamente +4,71 WER; +1,45 CER.

Um relatório inicial inverteu os sinais e aprovou indevidamente os candidatos. A errata e o gerador corrigiram esse erro. Não reaproveitar o JSON manual antigo como veredito.

Critérios de rodadas anteriores: intermediário/Equilibrado com delta de WER e CER até +1 pp; classes rápidas/compactas até +3 pp WER e +2 pp CER. Esses limites eram critérios de pesquisa do projeto, não garantias universais. Você pode propor critérios melhores para o produto, explicitando a decisão prospectiva, sua motivação e o efeito nos candidatos. Preserve os resultados e o veredito sob o critério antigo; não declarar que um artefato passou um limite que foi mudado depois.

Não aprovar perda de negação, valor, placa ou nomes sensíveis apenas porque a média atende um limite. Casos críticos devem acompanhar a taxa agregada.

### 8.3 H8 recuperou precisão no conjunto observado, mas segue lento

A ablação encontrou textos brutos H8 iguais a R nos 24 públicos. Nos 12 dirigidos, R/H8 tiveram 13 erros em 125 palavras; E8/EF, 24/125. A diferença acompanhou a troca do encoder naquele conjunto.

H8 usa editor quantizado e preservou a saída de referência nesse escopo; isso é motivo forte para mantê-lo na investigação. Não generalizar para ausência de efeito do editor em qualquer áudio ou para responsabilidade exclusiva do encoder por todo erro.

Os tempos H8 históricos foram altos: aproximadamente 42–102 s nos públicos, 61–183 s nos pessoais; tabela posterior no conjunto comum de 12 públicos registrou mediana próxima de 92 s. A precisão recuperada não trouxe velocidade aceitável automaticamente. R e REF também tiveram tempos bastante diferentes entre baterias. Refaça o benchmark necessário com condições controladas antes de atribuir causalidade a essa diferença.

H4 teve +1,60 pp WER e +0,34 pp CER na triagem dirigida, não recebeu a mesma expansão de H8. Redução de bytes não é prova de precisão.

### 8.4 CTC ganhou velocidade pela redução de trabalho

CTC remove projector/editor e pode evitar seus pesos e embeddings na carga. No áudio pessoal 15 segmentado, a bateria histórica registrou WER 0,068 contra 0,034 no pipeline EQ, com RTF 0,415 contra 0,881: aproximadamente 2,1 vezes mais rápido naquela comparação. Nos 14 pessoais houve casos iguais, melhores e piores; medir micro e casos críticos, não só mediana.

Uma comparação posterior CPU×HTP estava errada porque a configuração CPU executou pipeline full e a HTP executou CTC. A errata retirou essa conclusão. Os runs corretos CTC_ONLY de setembro mostraram CPU com carga perto de 0,5 s e inferência perto de 1,8–2,7 s; HTP tinha carga de cerca de 19–22 s e tempos de encoder na ordem de 1,4–1,6 s nos casos sem a recompilação descrita abaixo.

Bug ainda registrado: áudio com 175 frames criava t400 na carga e t200 na inferência; isso incluía cerca de 16 s de criação dentro do tempo rotulado inferência. É um candidato concreto a corrigir antes de tirar conclusões de amortização.

O relatório de 25/09 manteve CTC CPU como decisão daquela rodada, com memória pós-inferência muito menor que a rota HTP. Entretanto, listou como pendentes a campanha de uma carga, um warmup e três medidas na mesma sessão, além da prova estrita de partição HTP. Esses experimentos podem mudar a decisão; o documento não fecha toda investigação NPU.

### 8.5 ArgMax resolveu um OOM específico da saída Java

Foi localizado OOM na cópia de logits `[578,100352]` pelo acesso Java ao tensor ORT: 232.013.840 bytes na alocação observada. Ler via FloatBuffer e remover uma segunda FloatArray não garantia ausência da primeira cópia realizada pela API.

O derivado do editor int8b acrescentou ArgMax no grafo e passou a devolver 578 IDs int64, preservando os mesmos pesos externos. Esse caso de saída corresponde a 4624 bytes de IDs, embora o runtime ainda possa alocar logits internamente.

Houve paridade PC em sequências de teste e comparação no aparelho; o caso longo de 37,14 s rodou single-shot sem o OOM anterior. A medição anterior do total foi 58,5 s. Os pilotos controlados curtos não estabeleceram ganho universal de latência.

A worktree avançou no contrato logits/token_ids, verificação de shapes, non-finite, isenção da guarda somente no contrato válido e fechamento em exceção. Os dez ciclos iniciais tinham executado logits por erro de driver; foram reclassificados. O relatório de 25/09 informa dez ciclos efetivos token_ids com textos iguais e sem crescimento sustentado observado. Isso não demonstra ausência universal de vazamentos.

### 8.6 Cache N2 funciona e o parser de vocabulário dominava a carga

O primeiro piloto N2 sempre abria o modelo original com geração habilitada. A reabertura tentava gerar em destino já existente. A falha de colisão não era uma falha de leitura de contexto.

A implementação experimental passou a separar OFF, GENERATE e LOAD. Geração abre o original e produz wrapper EPContext; LOAD abre o wrapper gerado com geração desligada. Houve reaberturas reais, texto comparado e evolução do contrato de identidade do cache.

Um contexto histórico tinha wrapper de cerca de 58 KB e binário de cerca de 980 MB; a geração levou cerca de 43,5 s num piloto. A abertura do encoder chegou perto de 1,7 s, mas o load total ainda levava 15–16 s em outra bateria. Não confundir abertura de sessão com inferência nem tirar tempo de carga a partir do tamanho do binário.

O profiling de carga posterior localizou o maior custo no parser Regex de `vocab.json`: 14,7–21,3 s. Mmap não era o gargalo medido. `GraniteNarVocab.kt` substituiu o parser por um parser manual com testes de semântica, reduzindo o parse para 62–217 ms e a carga full de cerca de 20,8–24,1 s para 3,1–3,3 s no cenário comparado. Essa melhoria ainda está na worktree experimental.

O contrato N2 foi ampliado para hashes do wrapper/binários, dados externos do grafo, opções efetivas, backend e QAIRT, selo de completude e referências extraídas do wrapper. O especialista deve conferir a versão atual e a correspondência dos testes; lacunas relatadas nos primeiros pilotos podem ter sido corrigidas depois.

### 8.7 Profiling e intervenção reprovada

A worktree tem suporte a `enableProfiling`/`endProfiling` do ORT e flag experimental de otimização CPU.

No grafo perfilado, que era QDQ apesar do nome fp16, os maiores custos incluíam `/encoder/out_bpe/Gemm` de cerca de 237–242 ms e a desquantização do peso da cabeça BPE de cerca de 170–202 ms, juntos aproximadamente 19–25% do run observado. É uma pista concreta sobre projeção do vocabulário e preparação de pesos estáticos.

Esse trace não deve ser atribuído ao encoder de referência sem provar que o artefato é o mesmo. Precisão de arquivo, runtime e tipo real de operador são essenciais.

A tentativa BASIC_OPT -> EXTENDED_OPT não melhorou consistentemente a velocidade e piorou texto em parte dos casos. Ficou reprovada. Não repetir ALL_OPT/EXTENDED_OPT em lote para buscar um número menor sem analisar a causa.

Pode valer um derivado cirúrgico da cabeça BPE, por exemplo preparar offline o peso já desquantizado do mesmo grafo e preservar exatamente sua interpretação numérica. Isso é hipótese de próxima intervenção, não fix aprovado. Trocar para o peso float de origem mudaria a precisão e seria outra comparação. Considerar memória, tamanho e suporte por backend antes de exportar.

## 9 Estado dos commits e integração necessária

Commits de interesse encontrados na worktree, em sequência histórica aproximada:

| Commit | Conteúdo indicado |
|---|---|
| `819f4dc` | N2 OFF/GENERATE/LOAD inicial |
| `f81c551` | Collapse sobre IDs |
| `3c7b0d6` | Loader e contrato editor ArgMax |
| `08079ec` | Guarda de memória ajustada para token_ids |
| `4f3d711` | Instrumentação dos IDs CTC por backend |
| `00a94dc` | Identidade completa do cache e decomposição de carga |
| `7d7d502` | Validação ArgMax, non-finite e fechamento em exceção |
| `431aab9` | Ciclos de recarga por medida |
| `dfc2203` | Configuração efetiva, rotas/contratos e referências do wrapper |
| `722f8f9` | Parser de vocabulário manual |
| `0036f14` | Profiling encoder e flag EXTENDED_OPT |

Existem commits anteriores de portão de perfil, CTC e GPU diagnóstico. Os SHAs curtos são pistas: confirme o objeto Git e a dependência antes de selecionar um patch. Relatórios contêm alguns SHAs digitados incorretamente.

Relatórios de setembro indicam gates com 500 testes, lint e assemble passando no HEAD experimental. Isso é histórico. A integração na main de outubro precisa de seus próprios testes, porque a main avançou em outras áreas e no pacote de instalação de buckets.

Faça um inventário de mudanças por consumidor e por comportamento: parser, manifesto, contrato de saída, cache, sessão por bucket, instrumentação, CTC, memória e UI. Decida quais são prontas para consolidação e quais permanecem flags de laboratório. Não incorporar debug global mutável como API de produto sem desenho de estado e concorrência.

Pode reaproveitar a worktree existente ou criar checkout isolado adequado. Preserve commits, alterações e evidências. Não usar reset destrutivo, prune global, exclusão de worktrees ou sobrescrita do engine como atalho para conflitos.

## 10 Artefatos, downloads, R2 e dependências nativas

Bucket correto do Android: `sig-android`. O bucket `sig` pertence a outro contexto e não deve ser inferido para esta ferramenta.

Base pública atual do pacote NAR v2 no código principal:

`https://pub-6476622beda24c82875cb84f11f660ea.r2.dev/models/granite/4.1-nar/v2`.

O pacote usa manifesto com nomes relativos, tamanho e SHA-256; o campo publicado era `caminho_relativo`. O parser antigo lia apenas `name`, deixava o mapa vazio e invalidava a promessa de conferência dos downloads. A correção suporta o contrato real, valida estrutura e diferencia manifesto válido, inválido e ausente.

Reutilização de arquivo exige integridade, não apenas tamanho maior que zero. Resumo de bytes, presença no disco e HEAD HTTP não substituem hash dos bytes. Retomada de `.download`, Range/206, servidor que ignora Range, JSON inválido, truncamento e falha parcial são cenários importantes de instalação. Preserve um pacote anterior válido até ativar a nova versão íntegra.

`release/r2_config.json` contém credenciais locais ignoradas pelo Git. Leia apenas com o mecanismo necessário ao trabalho; não mostre valores, tokens ou conteúdo desse arquivo em logs, prompts ou commits. Consulte `UPDATE.md` e os scripts existentes para bucket e publicação.

O usuário autorizou downloads de modelos, exports e quantizações necessários, além de publicação de artefatos experimentais no R2. Use prefixos versionados, hashes, fonte/licença, contratos de I/O e status experimental. Não sobrescrever modelos de produção ou promover APK/pacote nativo sem a aprovação de release exigida pelo projeto.

Histórico de pacote:

- Encoder de referência com dado externo começando pelo SHA `6b65e9fb...` em várias evidências.
- Editor float externo histórico: 3.263.500.288 bytes.
- Editor int8b externo histórico: 1.657.409.536 bytes.
- Editor int4b externo histórico: 841.617.408 bytes.
- Embeddings token-major: 411.041.792 bytes.
- Buckets compartilham dados externos; confira nomes, offsets e lengths contra o arquivo real.

Hash abreviado serve só para reconhecer a evidência. Manifestos e ativação precisam do SHA completo. A dimensão de disco total pode diferir da soma de pesos usados: o U8 histórico chegou a ocupar mais espaço no arquivo externo do que a referência. Não interpretar isso como propriedade universal da quantização.

`NativeDependencyManager.kt` administra dependências nativas instaladas sob demanda. `QairtDependencyManager.kt` administra o pacote QAIRT. A versão de distribuição `PACKAGE_VERSION=1` não é, por si só, a versão real do SDK Qualcomm carregado. Identifique hashes e versão real das libs quando possível.

Para runtime novo OpenCL/Vulkan, determine o pacote nativo, ABI, JNI, licenças, drivers, assinatura/instalação e compatibilidade antes da UI. Um `.so` baixado que carrega não prova execução numérica dos operadores do modelo.

## 11 Caminhos de implementação a comparar no novo plano

Você deve pesquisar fontes primárias e código atual e montar uma comparação objetiva dos runtimes candidatos. Não há escolha de engine imposta por esta entrega.

### 11.1 CPU

Consolidar uma referência confiável e as melhorias sem perda semântica: parser de vocab, lifecycle, abertura por bucket adequado, ArgMax no editor, carga de recursos por rota, segmentação e medidas de memória.

Investigar o grafo efetivo, precisão por estágio, conversões e operadores dominantes. Verificar benefício de threads e paralelismo sem medir várias tarefas disputando o telefone. Converter FP16 para FP32 ou vice-versa não deve ser presumido como ganho; benchmark e memória decidem.

H8 é candidato a precisão com editor menor; U8/int8 e U8/int4 são candidatos mais rápidos com perda quantificada; CTC é rascunho. A melhor configuração pode mudar ao trocar runtime ou preparação de pesos.

### 11.2 GPU OpenCL e GPU Vulkan

A meta do usuário é explícita: estudar duas rotas identificáveis. QNN GPU é uma rota existente de tentativa, mas não deve ser usada para afirmar que duas implementações OpenCL/Vulkan foram entregues. Verifique a API efetivamente usada pelo runtime e o backend configurável em cada rota.

Avalie engines que realmente executem os operadores e a semântica do NAR no Android. Pode estudar ORT, QNN, MNN, ncnn, ggml ou outras soluções pertinentes, desde que confirme suporte atual e custo de integração. Esses nomes são candidatos de pesquisa, não capacidades garantidas.

Um modelo salvo em GGUF não faz um runtime causal entender Conformer, Q-Former, atenção bidirecional, inputs_embeds e CTC. Um backend existente no tradutor Hy-MT2 ou no Whisper não constitui porta automática para o Granite. Demonstrar export/contrato, cobertura de operadores, saída e memória.

Começar por um subgrafo pequeno e representativo: frontend não deve esconder o custo da rede, e um MatMul isolado não prova o modelo completo. Evoluir de operador -> bloco -> estágio -> pipeline com controles numéricos e texto. Comparar full GPU com híbrido CPU/GPU; transfers e sincronização podem eliminar um ganho por estágio.

Registrar Android/driver, GPU, runtime, versão, precisão, layout, operadores que ficam na CPU e custo de compilação dos shaders/kernels. Vulkan e OpenCL precisam de provas separadas.

### 11.3 NPU

Há execução HTP histórica útil, mas ainda há atribuição incompleta em alguns ensaios, alto custo de carga/memória e diferenças de texto. Começar por encoder t200/t400 exato, com bucket previsto corretamente, partição verificada e contexto vinculado ao mesmo artefato.

Proibir fallback CPU na sessão em que a hipótese exige HTP integral; estágios declarados CPU podem continuar CPU. Se a sessão for híbrida, registrar nós/partições efetivos. `effective_backend=NPU_QNN_HTP` é rótulo insuficiente quando `strict=false`.

Quantização deve usar calibração encadeada com entradas reais de cada estágio e separação dos conjuntos. U8, U16 ou combinações de pesos/ativações devem ser inspecionadas, não deduzidas do nome do arquivo. Não calibrar com os mesmos exemplos usados para decidir uma aprovação final.

Antes de exportações extensas, determinar se a meta é aceleração do encoder, projector, editor ou modelo todo. Cada estágio aprovado reduz incerteza, e o custo do conjunto residente precisa ser medido.

### 11.4 Smart

Para NAR, comparar o híbrido por estágios com as mesmas entradas e precisão. Demonstrar interoperabilidade de tensores, layout, dtype, escala e propriedade de memória. Definir quem sincroniza, quando as sessões abrem e fecham, como funciona cancelamento e o que ocorre num erro do DSP.

Para Smart literal autoregressivo, a pesquisa precisa definir modelo e contratos de prefill/decode; formato do KV cache, posições, máscaras, layout, quantização, transferência, capacidade máxima e coerência numérica entre backends. Cache aparentemente compatível por shape pode ser semanticamente diferente. Compartilhar KV não é apenas copiar um buffer.

Medir primeira transcrição, uso repetido, carga/compilação, transferências e energia quando houver instrumento adequado. NPU rápida em prefill pode perder no total por custo de inicialização ou movimentação. Um caminho pode funcionar e ainda não merecer ser recomendado.

A seleção automática Smart deve partir de capacidade verificada e benefício observado, com critério de erro e fallback declarado. O desenho pode incluir perfil calibrado por aparelho ou configuração explícita do usuário; não executar uma varredura pesada invisível a cada transcrição.

## 12 Ferramentas e infraestrutura já disponíveis para reaproveitar

Fontes principais na raiz `D:\Projetos\SIG\app\src\main\java\br\gov\sp\pcsp\launcher`:

- `GraniteActivity.kt`: UI e orquestração da ferramenta.
- `GraniteEngine.kt`: TurboCTC e tipos compartilhados de backend.
- `GraniteNarEngine.kt`: NAR e regras associadas.
- `GraniteNarLlmSettings.kt`: preferência de variante do editor.
- `GraniteParallelismSettings.kt`: paralelismo.
- `GraniteBinarySupport.kt`: WAV, Unicode/BPE, floats, erros.
- `QairtDependencyManager.kt`, `NativeDependencyManager.kt`, `NativeDependencyPrompt.kt`.
- `TranscriptionReport.kt`, `MediaUriSupport.kt`, `ToolsActivity.kt`.

Fontes debug:

- `GraniteNarSmokeTestActivity.kt`.
- `GraniteNarBenchmarkProtocol.kt`.
- `QairtSmokeTestActivity.kt`.

Classes adicionais na worktree incluem `GraniteNarEpContext.kt`, `GraniteNarVocab.kt` e seams de política/memória/perfis. Inspecione a lista real e consumidores antes de integrá-las.

Scripts NAR em `D:\Projetos\SIG\tools\granite\nar`:

- `README.md`, `requirements-lock.txt`, `setup_env.py`, `download_source.py`.
- `inspect_onnx.py`, `export_static.py`, `einsum_to_matmul.py`, `fold_constant_subgraphs.py`.
- `build_calibration_corpus.py`, `capture_chained_calibration.py`, `quantize_qnn.py`.
- `run_reference.py`, `validate_float.py`, `validate_qdq.py`, `mask_gate.py`, `text_gate.py`.
- `build_experiment_manifest.py`, `build_validation_status.py`, `publish_experiment_r2.py`, `test_nar_tools.py`.

Há outros exports e scripts de GPU em `tools/granite`, e scripts locais no lab para ArgMax, reconstrução e provas Android. Reutilize o que existe e confira dependências fixadas; nomes não garantem que o script se aplica ao novo runtime.

Docs de referência no repositório:

- `docs/granite-nar-design.md`: arquitetura/export inicial; contém estado e limites antigos.
- `docs/granite-turboctc-design.md`: motor separado; escolhas antigas não são política permanente para NAR.
- `docs/qairt-status.md`: FastRPC e diagnóstico histórico, com conclusões datadas.
- `docs/plano-acao-granite-4.1-nar-qnn-20260829.md`.
- `docs/handoff-granite-nar-artefatos-20260830.md`.
- `docs/prompt-pc-auxiliar-granite-20260914.md` e o plano de perfis.
- `docs/prompt-unificado-pc-auxiliar-nar-ptbr-15-audios.md`.
- `docs/ordem-pc-auxiliar-nar-pos-bcd-20260915.md`.
- `docs/ordem-pc-auxiliar-nar-perfis-reais-20260915.md`.
- `docs/ordem-pc-auxiliar-nar-h8-ctc-20260916.md`: errata importante e última orientação encontrada antes da campanha de 25/09.

O projeto também tem pesquisas de OpenCL/Vulkan/NPU/Smart para Hy-MT2 em `docs/especialista` e código JNI associado. Consulte como experiência de infraestrutura e riscos; o experimento de tradução é distinto e pode estar em andamento. Não reutilizar a identidade, a validação numérica ou o backend aprovado de outro modelo como prova do Granite.

## 13 Instrumentação que precisa impedir resultados falsos

Várias conclusões históricas foram corrigidas por defeitos do protocolo. Seu plano deve incorporar testes de regressão para essas classes, sem reiniciar uma auditoria geral a cada entrega:

1. **Backend rotulado mas não utilizado:** engine criava CPU enquanto a UI dizia GPU/NPU.
2. **CTC solicitado, full observado:** variável `CTC_ONLY` não propagada num dos braços.
3. **ArgMax solicitado, logits observado:** recarga perdia a flag e o contrato.
4. **Bucket diferente entre load e run:** compilação contada como inferência.
5. **APK substituído por outra tarefa:** mesmo versionName, bytes e código diferentes.
6. **Nome de artefato mentindo sobre dtype:** `fp16.onnx` contendo QDQ.
7. **Bits ignorados pelo quantizador:** a combinação de `algo_config` com a API de MatMulNBits já produziu variante rotulada 8-bit com bytes de 4-bit. Inspecionar atributos dos nós e pesos reais.
8. **Contexto regenerado em vez de carregado:** colisão interpretada como cache inviável.
9. **Saída de outro run no mesmo log:** clientes adb logcat vazados e extração global.
10. **Inversão de delta:** REF menos candidato apresentado como candidato menos REF.
11. **Pareamento por nome, sem hash:** arquivos diferentes podiam formar pares.
12. **Repetições desiguais:** subtração de totais com denominador de só um braço produzia falso ganho.
13. **Memória em unidade errada:** milhares de KiB relatados como poucos MB/KB.
14. **Pico inferido de uma amostra:** start/load/end não é monitoramento contínuo.
15. **Referência humana presumida:** script esperado ou dataset não confirmado por escuta tratado como verdade absoluta.

Cada run deve ter identidade e configuração observadas: host, serial, pid, run_id, session_id, APK SHA completo, commit/diff, native libs, grafo/external data, contrato, backend por estágio, bucket, cache, opções e contagens. Extrair por run_id + pid + tipo + índice, com hash do stream.

Resultado inválido deve ter classe: configuração incorreta, captura inválida, falha de instalação, runtime, OOM, timeout, erro numérico, qualidade insuficiente ou ausência de suporte. Preservar primeira tentativa e retries no quadro operacional.

O lock precisa proteger também funções importáveis do orquestrador e a troca/restauração de pacotes. Dono é host + PID + tentativa. PID de outro host não é processo local abandonado. Finalização de captura deve verificar o processo que pertence à tarefa; nunca usar encerramento amplo de todos os clientes ADB de outras tarefas.

## 14 Corpus e avaliação PT-BR

Gravações pessoais: `D:\15 audios`, com arquivos 1 a 15 conforme o inventário existente. O 15 tem cerca de 59 s de fala rápida; os primeiros 14 cobrem fala comum e casos importantes de precisão. Não renomear ou reconverter sem manifestos e vínculo de hashes.

Referências e roteiro:

- `docs/roteiro-gravacoes-ptbr-granite.md`.
- `docs/gravacoes-usuario-ptbr-referencias.md`.
- `docs/prompt-unificado-pc-auxiliar-nar-ptbr-15-audios.md`.

As gravações incluíram negações, nomes, números, valores, datas/horas, placa, endereço e repetição legítima. Falhas observadas incluem placa BCD1F23, valores, datas e morfemas repetidos. A frase com “eu vi, eu vi” não deve ser deduplicada porque a repetição é real.

Referências pessoais permanecem provisórias até escuta verificável. Disponibilize ao usuário somente os trechos divergentes necessários, com timestamp e hipóteses, para confirmação. Não pedir nova gravação se as existentes resolvem o teste.

No lab há FLEURS e seleção de holdout PT-BR. Os 24 usados reiteradamente já orientaram escolhas; tratá-los como conjunto exploratório. Confirmação final exige conjunto PT-BR novo, fora da calibração e da seleção dos artefatos. Auditar a sobreposição: houve triagem de 8 áudios com 3 no pool de calibração, explicitamente insuficiente para generalização.

Normalizador norm-v2 preserva informação crítica de separadores e números. A versão deve ser explícita em arquivo, conteúdo e comparação. Norm-v1 e norm-v2 não podem ser misturados só porque um relatório se chama v2.

Avaliar WER/CER micro contra referência, erros absolutos, inserções/exclusões/substituições e casos críticos. Comparar candidato com REF também serve para medir efeito de transformação, mas concordar com REF não estabelece transcrição correta. A referência do modelo pode conter erro.

Em repetição suspeita, distinguir substituição, exclusão e duplicação real. “apreender” é uma palavra válida e pode ser uma substituição no contexto; “apender” pode ser exclusão. “aprenderender” é outro caso. Uma heurística sozinha não estabelece a causa nem a gravidade.

No ensaio longo, congelar cortes por amostras, conferir cobertura completa e cauda, guardar tempos dos segmentos e recomposição. CPU/GPU/NPU devem receber os mesmos cortes para isolar backend. Segmentos maiores de CPU são comparação de pipeline diferente. Não remover duplicações globais como remendo de junção.

## 15 Primeiro plano de ação que você deve produzir

Comece com inspeção suficiente para um plano concreto. Depois entregue um documento versionado no repositório, na pasta de especialista adequada, e uma primeira ordem ao executor. O plano é seu instrumento de execução: mantenha estado, decisões e evidências atualizados.

Ele precisa conter, no mínimo:

### 15.1 Mapa do estado real

- Comparação main/worktree/lab/artefatos instalados.
- Matriz de componentes e contratos por perfil.
- Lista das melhorias experimentais prontas para integração, dos experimentos ainda incertos e das afirmações corrigidas.
- Inventário de fontes, runtimes, modelos, licenças, scripts e evidências reutilizáveis.
- Disponibilidade atual do telefone, conexão, máquinas e ferramentas.

### 15.2 Matriz de alternativas arquiteturais

Para CPU, OpenCL, Vulkan, NPU e Smart, declarar caminhos candidatos, suporte confirmado versus hipótese, conversão necessária, custo estimado qualitativo, riscos de semântica, memória e teste que elimina a maior incerteza. Pesquise documentação oficial e código das versões atuais antes de fixar uma escolha.

Não declarar suporte com base em lista genérica de operadores quando shapes, dtypes, quantização ou atenção mudam o caso. Cite links diretos e registre a versão considerada. Uma nova versão de ORT/QAIRT pode justificar um novo piloto; congele-a e compare ao controle.

### 15.3 Fases e dependências

Defina fases com ordem, responsáveis, entradas, saídas, orçamento e condição de conclusão. Uma organização inicial possível é:

1. Inventário, baseline CPU e protocolo que valida configuração observada.
2. Consolidação das melhorias independentes e correção de bucket/lifecycle.
3. Pilotos de compatibilidade dos runtimes para OpenCL/Vulkan/HTP.
4. Perfil CPU intermediário/compacto/CTC com qualidade e memória.
5. Pipeline GPU e NPU, incluindo cache, carga e cobertura de operadores.
6. Smart por estágios NAR e decisão da rota Smart autoregressiva literal.
7. Benchmark completo e conjunto de confirmação PT-BR.
8. Integração da UI, instalador, seleção de capacidade, relatórios e pacote versionado.
9. Aceitação de release e documentação para usuário, com aprovação de promoção apropriada.

Você pode mudar completamente essa organização. Explique qual evidência ou custo motiva a mudança. Procure uma sequência que reduza incerteza cedo e entregue melhorias úteis enquanto as rotas mais difíceis amadurecem.

### 15.4 Definição dos testes

Para cada fase, especificar:

- Testes host de regra, contrato e equivalência numérica.
- Smoke no Android em UID normal.
- Prova por estágio e por pipeline.
- Entrada/hash/referência, configuração exata e controle.
- Critério de precisão, latência, memória e operação.
- Tempo de geração/compilação, load do app, primeira inferência e quente separados.
- Protocolo de sessão reutilizada real, ordem ABBA/BAAB ou alternativa justificada, contagem por braço e condição térmica.
- Timeouts proporcionais ao piloto, captura de falha e mecanismo de interrupção que preserve outras tarefas.
- Restauração e critério para manter um candidato experimental ou descartá-lo.

Não aplicar o mesmo orçamento a compilação inicial e inferência curta. Fixar um teto antes do piloto; travamento, OOM ou reset inesperado encerra o braço para diagnóstico. Não manter ciclos de reboot para conseguir preencher uma tabela.

### 15.5 Plano de delegação

Decompor em tarefas pequenas, identificáveis e revisáveis. Executor deve receber contratos e comandos suficientes para não improvisar uma nova arquitetura. Você recebe decisões/escalations, valida e escolhe as fases seguintes.

Tarefas independentes de leitura/export podem ser paralelas quando a máquina comportar; edições do mesmo arquivo, sessões grandes concorrentes e ADB no mesmo telefone precisam de ownership e exclusão. Priorizar economia de banda, espaço e crédito reaproveitando artefatos íntegros.

### 15.6 Critérios de produto

Definir quando uma opção entra na UI: implementação, qualidade, compatibilidade, estabilidade, benefício no cenário de uso, download/licença e fallback observável. CPU funcional deve continuar disponível. Rótulos e números devem ser do pipeline aplicável e do aparelho/cenário medido, sem transferir o fator de velocidade de PC ao telefone.

Separar “funciona”, “usa o hardware”, “é mais rápido”, “preserva precisão” e “aprovado para produto”. Cada afirmação tem sua evidência.

## 16 Regras de aceitação do repositório e permissões

Leia `D:\Projetos\SIG\AGENTS.md`, o AGENTS aplicável à worktree, `MODULE-MAP.md` e o gate. Fontes de produção precisam de ownership no mapa e KDoc conforme o contrato do projeto.

As mudanças de código devem passar unitários focais e, na revisão final do APK, os gates aplicáveis:

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:lintDebug
.\gradlew.bat :app:assembleDebug
```

Mudanças no pacote nativo exigem os verificadores próprios, incluindo o build e `scripts/verify-native-dependencies.ps1` conforme a versão a publicar. Release só de APK não exige regenerar ZIPs nativos se o contrato não mudou. Não repetir geração de gigabytes por hábito.

Respeitar `scripts/validate-agent-harness.ps1`, staged snapshot, MODULE-MAP e os hooks. Falha de hook por arquivos de terceiros precisa de isolamento e ownership, não de bypass ou commit de alterações alheias.

Alterações em `RemoteSttActivity.kt` exigem o gate completo e as provas funcionais específicas do hotspot. Essa área não é o lugar padrão para implementar o Granite local.

O usuário autorizou o trabalho técnico necessário, incluindo downloads, quantização e experimentos R2. Assinatura/publicação/promover artefatos de release continuam sujeitas ao procedimento do projeto. Preserve defaults de produto até haver decisão de integração e evidência. Não apagar datasets, modelos, worktrees ou snapshots para liberar espaço sem inventário e decisão específica de descarte.

Não expor credenciais ou áudio pessoal nos relatórios. Não mandar dados pessoais para serviços externos, bucket, pesquisas web ou calibração compartilhada. Use áudio público apropriado nos primeiros pilotos.

## 17 Contrato de evidências e manutenção do plano

Mantenha, no diretório definido no seu plano:

- Plano técnico versionado com fases e decisões.
- Estado atual com tarefa, responsável, dependências, bloqueio, próxima ação e referência da evidência.
- Ordens ao executor e relatórios por rodada.
- Manifestos de APK, nativos, modelos e configurações.
- Ledgers de tentativas, logs brutos, extrações e métricas geradas.
- Patches/commits próprios, testes e comandos de reprodução.
- Registro das hipóteses refutadas, do escopo de cada conclusão e dos métodos descartados.

Não confundir arquivos de estado com execução em segundo plano. Use os mecanismos de monitoramento/espera realmente disponíveis quando houver trabalho assíncrono autorizado. Se o usuário voltar dizendo “terminou lá, pode continuar”, leia o estado e o relatório da execução, confira a evidência e continue pelo próximo marco.

Relatório de cada marco deve responder: o que mudou, qual configuração rodou, resultado, ganho/perda, falhas e próxima decisão. Toda tabela deve trazer conjunto, denominador, versão do normalizador, unidades e significado temporal. Qualidade condicional e disponibilidade de primeira tentativa são quadros diferentes.

## 18 Referências técnicas para iniciar a pesquisa

Fontes primárias para revalidar antes de decisões dependentes de versão:

- Modelo e arquitetura: https://huggingface.co/ibm-granite/granite-speech-4.1-2b-nar
- ONNX Runtime QNN EP: https://onnxruntime.ai/docs/execution-providers/QNN-ExecutionProvider.html
- Contexto EP: https://onnxruntime.ai/docs/execution-providers/EP-Context-Design.html
- Profiling ORT: https://onnxruntime.ai/docs/performance/tune-performance/profiling-tools.html
- SessionOptions Java: https://onnxruntime.ai/docs/api/java/ai/onnxruntime/OrtSession.SessionOptions.html
- Código ggml: https://github.com/ggml-org/ggml
- Código llama.cpp: https://github.com/ggml-org/llama.cpp
- Código MNN: https://github.com/alibaba/MNN
- Código ncnn: https://github.com/Tencent/ncnn

São pontos de partida; não constituem uma declaração de que qualquer um deles executa o NAR completo ou o Smart pedido. Registre suporte real para a combinação modelo, runtime, backend e hardware que selecionar.

## 19 Sua primeira resposta e sua primeira ação

Assuma o papel de especialista agora. Inspecione o estado corrente e gere seu plano extremamente detalhado, com prioridades, alternativas e critérios de decisão. Considere o progresso recente de 25/09 e não reinicie a investigação como se nada tivesse sido feito.

Entregue o plano em documento local apropriado, informe os principais pontos ao usuário e emita a primeira ordem ao executor identificado. A primeira ordem deve resolver uma incerteza importante ou consolidar uma melhoria comprovada, com resultado revisável. Não encerrá-la numa pergunta genérica sobre querer começar.

A meta é levar o Granite STT a opções confiáveis de CPU, GPU OpenCL, GPU Vulkan, NPU e Smart onde a arquitetura permitir. Você pode mudar completamente a lógica do plano para chegar lá. O que deve permanecer é a qualidade PT-BR, a honestidade sobre hardware e resultados, a integridade dos artefatos e uma execução organizada entre especialista e executor.
