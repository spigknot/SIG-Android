# Granite STT — runtimes, evidências oficiais e pilotos

Documento auxiliar da entrega de 08/10/2026; pesquisa desta sessão concluída em
09/10/2026, horário de São Paulo. Ownership desta rodada: somente este arquivo.
Estado: **pesquisa e desenho de experimento**, sem build, download de pesos,
instalação Android, ADB ou promoção de artefatos nesta rodada.

A ordem operacional do plano principal prevalece: G01 extrator offline de
evidências, G02 vocabulário, G03 contratos/buckets/lifecycle e depois G04 pilotos
de runtime. As propostas abaixo são entradas de decisão para G04. Nenhuma
versão nova deve substituir o controle CPU ou as bibliotecas instaladas apenas
por ser mais recente.

## 1. Identidade das fontes e distinção de versões

As versões de release foram corroboradas em páginas oficiais e pela API GitHub
`releases/latest`, seguida de `commits/<tag>`. Os modelos foram conferidos na API
Hugging Face oficial e em seus arquivos na revisão indicada. A coluna SHA
identifica código-fonte, **não** o SHA-256 do AAR, da biblioteca instalada ou do
contexto QNN. O executor deverá registrar esses hashes separadamente.

| Componente | Release/revisão considerada | SHA Git completo | Evidência oficial |
|---|---|---|---|
| Granite Speech 4.1 2B NAR | Revisão atual conferida; última alteração 18/06/2026 | `a1e3416e25ce29ab3852778e54fa8b3bd59c4bf2` | [API IBM](https://huggingface.co/api/models/ibm-granite/granite-speech-4.1-2b-nar), [config fixo](https://huggingface.co/ibm-granite/granite-speech-4.1-2b-nar/raw/a1e3416e25ce29ab3852778e54fa8b3bd59c4bf2/config.json) |
| Granite Speech 4.1 2B AR | Alternativa de produto; última alteração 12/06/2026 | `de575db64086f84fdc79da4932d1076e965bc546` | [API IBM](https://huggingface.co/api/models/ibm-granite/granite-speech-4.1-2b), [config fixo](https://huggingface.co/ibm-granite/granite-speech-4.1-2b/raw/de575db64086f84fdc79da4932d1076e965bc546/config.json) |
| ONNX Runtime upstream | `v1.30.0`, publicado 10/09/2026 | `f2c39fe2f838cf35ce7da92824f5a5e3ee6e88a7` | [Release](https://github.com/microsoft/onnxruntime/releases/tag/v1.30.0), [commit](https://github.com/microsoft/onnxruntime/commit/f2c39fe2f838cf35ce7da92824f5a5e3ee6e88a7) |
| QNN EP plugin oficial Qualcomm | `v2.6.0`, publicado 10/09/2026 | `7332461750cb7fefc6444a674ebe34ce5c9c0d01` | [Release](https://github.com/onnxruntime/onnxruntime-qnn/releases/tag/v2.6.0), [docs do tag](https://github.com/onnxruntime/onnxruntime-qnn/blob/7332461750cb7fefc6444a674ebe34ce5c9c0d01/docs/execution_providers/QNN-ExecutionProvider.md) |
| llama.cpp | `v0.6.0`, publicado 05/10/2026 | `d81235049384534c167caea52b85a694f6103d14` | [Release](https://github.com/ggml-org/llama.cpp/releases/tag/v0.6.0), [commit](https://github.com/ggml-org/llama.cpp/commit/d81235049384534c167caea52b85a694f6103d14) |
| ggml independente | `v0.26.0`, publicado 05/10/2026 | `d7cb574130e6f01ad25b3289685489200febcd74` | [Release](https://github.com/ggml-org/ggml/releases/tag/v0.26.0), [commit](https://github.com/ggml-org/ggml/commit/d7cb574130e6f01ad25b3289685489200febcd74) |
| MNN | `3.6.1`, publicado 23/07/2026 | `d407447ed56c4121a11ccbd266dc184ca1ead0c2` | [Release](https://github.com/alibaba/MNN/releases/tag/3.6.1), [commit](https://github.com/alibaba/MNN/commit/d407447ed56c4121a11ccbd266dc184ca1ead0c2) |
| ncnn | `20260526`, publicado 26/05/2026 | `e54f7b1f88434e1d844ea0551b880a1cfb079ce1` | [Release Android/Vulkan](https://github.com/Tencent/ncnn/releases/tag/20260526), [commit](https://github.com/Tencent/ncnn/commit/e54f7b1f88434e1d844ea0551b880a1cfb079ce1) |

O ggml incorporado em llama.cpp deve ser congelado pelo SHA de **llama.cpp**.
O tag independente ggml não prova que a biblioteca embutida tenha os mesmos
bytes ou o mesmo conjunto de kernels.

O plugin QNN é uma linha de distribuição própria, distinta do QNN integrado no
ORT usado pelo SIG. Seu tag documenta Android ARM64 com
`com.qualcomm.qti:onnxruntime-android-qnn:2.6.0`, ORT Android **1.27.0** e
`com.qualcomm.qti:qnn-runtime:2.50.0`. A tabela Windows usa QAIRT **2.50.40**;
esses números não devem ser intercambiados. O
[POM Android oficial](https://repo1.maven.org/maven2/com/qualcomm/qti/onnxruntime-android-qnn/2.6.0/onnxruntime-android-qnn-2.6.0.pom)
confirma coordenada e versão. A compatibilidade geral declarada com ORT >=1.24.1
não substitui prova Android da combinação realmente empacotada. O candidato
inicial deve reproduzir a combinação Android documentada; combinar plugin 2.6
com upstream 1.30 constitui outro braço.

## 2. Contrato IBM que uma implementação precisa preservar

O NAR recebe uma hipótese CTC e a edita numa passagem bidirecional. Portanto o
Smart NAR deve dividir **estágios**; ele não possui a geração iterativa que
fundamenta o par prefill/decode autoregressivo. O código IBM constrói máscara
bidirecional e força `use_cache=False`. [Arquitetura IBM](https://huggingface.co/ibm-granite/granite-speech-4.1-2b-nar),
[implementação fixa](https://huggingface.co/ibm-granite/granite-speech-4.1-2b-nar/blob/a1e3416e25ce29ab3852778e54fa8b3bd59c4bf2/modeling_granite_speech_nar.py).

As constantes verificadas no [config NAR](https://huggingface.co/ibm-granite/granite-speech-4.1-2b-nar/raw/a1e3416e25ce29ab3852778e54fa8b3bd59c4bf2/config.json)
são `input_dim=160`, encoder 16 camadas/hidden 1024/8 heads,
`bpe_output_dim=100352`, blank 100257; editor hidden 2048/40 camadas/16 heads
e 4 KV heads, escalas embedding 12, atenção 0.0078125, residual 0.22 e logits 8.
Projector: bloco 15, downsample 5, 3 queries por janela, 2 camadas, concatenando
features das camadas 4, 8, 12 e final. Conformer, pooling posterior e Q-Former
precisam de equivalência própria; executar um LLM Granite genérico não cobre
esses componentes.

O [tokenizer fixo](https://huggingface.co/ibm-granite/granite-speech-4.1-2b-nar/resolve/a1e3416e25ce29ab3852778e54fa8b3bd59c4bf2/tokenizer.json)
foi lido: BPE com 100352 entradas, 100000 merges, 96 added tokens,
pré-tokenizador com Split+ByteLevel e decoder ByteLevel. IDs especiais
100256=`<|pad|>` e 100257=`<|end_of_text|>` foram confirmados.
O contrato deve guardar arquivo/hash e vetor de tokens, sem presumir que uma
rotina GPT-2/BPE genérica reproduza tratamento de bytes/especiais. Na inspeção
por PowerShell, usar `.get_Count()`/`.get_Values()` para dicionários de vocab:
uma peça chamada `Count`/`Values` pode ocultar a propriedade correspondente.

No modelo oficial, CTC usa ArgMax → collapse de repetições consecutivas →
remoção de blank. Features projetadas são divididas por 12 e a entrada do
editor é multiplicada por 12. Esses detalhes são confirmados no
[código IBM](https://huggingface.co/ibm-granite/granite-speech-4.1-2b-nar/blob/a1e3416e25ce29ab3852778e54fa8b3bd59c4bf2/modeling_granite_speech_nar.py).
O golden de uma conversão deve incluir IDs CTC, embeddings, offset textual,
IDs do editor e texto UTF-8, além de métricas de tensores.

`tools/granite/nar/README.md` permanece entrada local dos wrappers/buckets.
Atenção: suas entradas/saídas históricas são um ponto de partida; conferir o
grafo concreto, sua máscara e seus frames válidos antes de qualquer ensaio.

## 3. Matriz dos candidatos

Escala de custo **proposta pelo especialista**, não estimativa de benchmark:
baixo=reutilizar grafo/runtime íntegros; médio=conversão/JNI isolados;
alto=conversor específico, kernels ou novo modelo. Benefício em velocidade,
memória ou precisão permanece hipótese até medição.

| Rota | Suporte confirmado na fonte | Hipótese específica para Granite NAR | Conversão e custo | Maior risco / piloto eliminatório |
|---|---|---|---|---|
| CPU ORT atual | Pipeline existente no SIG; controle preservado pelo plano principal | Integrar ArgMax/CTC/buckets e reutilizar sessões reduz custo Android | Reaproveitar ONNX e artefatos do lab; baixo | G01–G03 verificam contrato observado; não herdar métrica do PC |
| CPU ORT upstream 1.30 | Release oficial e kernels ARM/quantização disponíveis | Editor MatMulNBits pode melhorar ou regredir | Mesmo grafo, nativos isolados; baixo/médio | R01: A/B no mesmo aparelho e artefato; sem misturar QAIRT ou editor |
| GPU QNN | EP com backend `libQnnGpu.so`; grafo fixo float candidato | Executar encoder/projector/editor preservando NAR | Reutilizar ONNX float; médio | R02: mesmo T200/S64, cobertura e dtype observados; rótulo GPU QNN, não presumir seleção direta OpenCL/Vulkan |
| OpenCL MNN | Backend móvel OpenCL e Attention com máscara/cache configuráveis | Converter estágio ou editor não causal completo | ONNX→MNN, JNI, golden; médio/alto | R03: atenção GQA 16:4 e projector cross-attention; ausência de causal sentinel e cache |
| OpenCL llama.cpp | Backend Adreno, Granite text, embeddings/logits e API não causal | Editor NAR extraído pode executar numa passagem | Extrair editor, integrar pesos/LoRA, GGUF e glue CTC; médio/alto | R04: S64 integral, escalas, logits em todas posições; converter Speech AR não cobre NAR |
| Vulkan MNN | Backend Vulkan e Attention com `kv_cache=false`/máscara explícita | Mesmo modelo convertido pode atender os dois backends GPU | Conversão compartilhada com OpenCL; médio/alto | R03 seguido de R05 no Vulkan, registrando fallback e dtype de cada op |
| Vulkan ncnn | CPU/Vulkan, pnnx e testes SDPA com GQA/máscaras | Encoder/projector ou editor via grafo decomposto | ONNX/PyTorch→pnnx→ncnn e glue; médio/alto | R05: não causal, repeat KV 16:4, RoPE/escalas; atenção INT8 específica desliga Vulkan no código |
| Vulkan ggml/llama.cpp | Backend Vulkan da árvore llama.cpp; grafo Granite text | Mesmo editor isolado do R04 pode usar Vulkan | GGUF do R04, backend distinto; médio após R04 | Equivalência e residência GPU; S inteiro, sem microbatch causal |
| NPU HTP QDQ ponte atual | Infraestrutura ORT/QNN e buckets estáticos existentes | Resgatar estágios aprovados com contexto efetivamente carregado | QDQ/calibração encadeada já disponível; baixo/médio | R06: strict por estágio, UID app normal, um bucket, cache frio/quente distintos |
| NPU plugin QNN 2.6 | Release oficial separada, Android ARM64 e novos builders | Pode remover bloqueio histórico do editor ou da partição | Runtime/registro EP isolados; médio; começar sem reexport | R02/R06 com mesmo grafo; versões Android documentadas, antes de alterar representação |
| NPU HTP float | Código ORT/testes e docs plugin atuais permitem rota float em hardware compatível | Evitar QDQ inicial em um estágio pequeno pode acelerar diagnóstico | ONNX float estático íntegro; baixo/médio | R06F: micrografo primeiro; suporte depende de SoC/SDK/ops, não do nome FP16 |
| Smart NAR por estágios | Interfaces explícitas encoder→projector→editor permitem orquestração | Encoder HTP + projector/editor GPU ou CPU pode vencer total CPU | Adaptadores tensor/layout/dtype e lifecycle; médio/alto | R07 somente após dois estágios aprovados separadamente; incluir transferências e memória residente |
| Smart AR prefill HTP/decode GPU | Modelo AR oficial e suporte GraniteSpeech AR em llama.cpp | Portar KV entre QNN e GPU com semântica idêntica pode funcionar | Modelo/contrato novos, exports prefill/decode e cache bridge; alto | R08: primeiro token após import de KV, vários passos e CPU referência; não é perfil NAR |
| ggml direto por estágio | Biblioteca de grafos/tensores e kernels reais | Grafo customizado pode executar componentes ausentes no frontend llama | Import/conversão manual, kernels e manutenção; alto | Última opção após identificar op impeditivo de MNN/ncnn/ORT; não reconstruir tudo de início |

Evidência das linhas GPU: [MNN README no tag](https://github.com/alibaba/MNN/blob/d407447ed56c4121a11ccbd266dc184ca1ead0c2/README.md),
[ncnn README no tag](https://github.com/Tencent/ncnn/blob/e54f7b1f88434e1d844ea0551b880a1cfb079ce1/README.md),
[OpenCL llama.cpp no tag](https://github.com/ggml-org/llama.cpp/blob/d81235049384534c167caea52b85a694f6103d14/docs/backend/OPENCL.md),
[árvore Vulkan llama.cpp](https://github.com/ggml-org/llama.cpp/tree/d81235049384534c167caea52b85a694f6103d14/ggml/src/ggml-vulkan).
Nenhuma dessas fontes constitui prova Android do NAR completo.

## 4. Bloqueios precisos encontrados no código

### 4.1 QNN: documentação antiga, precisão e linhas de distribuição

A [página ORT da ponte QNN](https://onnxruntime.ai/docs/execution-providers/QNN-ExecutionProvider.html)
contém uma seção que restringe HTP a modelos quantizados, enquanto lista
`enable_htp_fp16_precision`. O
[código upstream 1.30](https://github.com/microsoft/onnxruntime/blob/f2c39fe2f838cf35ce7da92824f5a5e3ee6e88a7/onnxruntime/core/providers/qnn/qnn_execution_provider.cc)
configura `QNN_PRECISION_FLOAT16`; há
[teste oficial float32 com precisão FP16](https://github.com/microsoft/onnxruntime/blob/f2c39fe2f838cf35ce7da92824f5a5e3ee6e88a7/onnxruntime/test/providers/qnn/qnn_basic_test.cc).
Logo, a afirmação universal “HTP só aceita QDQ” não deve bloquear pesquisa.

A [documentação plugin atual congelada para esta consulta](https://github.com/onnxruntime/onnxruntime-qnn/blob/643c2e0cf9fc53364be34b74c141b5984ad5a284/docs/execution_providers/QNN-ExecutionProvider.md)
esclarece: desde QAIRT 2.35, operações float usam matemática FP16 em SoCs que
suportam modelos float; a opção citada não seleciona FP32 nesses SDKs. Esse
commit de documentação é **main**, não o candidato binário v2.6.0. O tag v2.6
ainda possui texto anterior sobre a opção. Não usar `0` como prova de execução
FP32, nem extrapolar suporte float para qualquer DSP Snapdragon.

O [backend manager upstream 1.30](https://github.com/microsoft/onnxruntime/blob/f2c39fe2f838cf35ce7da92824f5a5e3ee6e88a7/onnxruntime/core/providers/qnn/builder/qnn_backend_manager.cc)
exige QAIRT >=2.39 no build. Um teste com bibliotecas arbitrárias de outro SDK
não é comparação válida. Distinguir build compatível, carregamento, atribuição
de operadores e execução numérica. BF16 é outra hipótese; não entra no primeiro
piloto só porque o código contém suporte.

### 4.2 Attention/GQA QNN não causal

No [builder Attention plugin v2.6](https://github.com/onnxruntime/onnxruntime-qnn/blob/7332461750cb7fefc6444a674ebe34ce5c9c0d01/onnxruntime/core/providers/qnn/builder/opbuilder/attention_op_builder.cc),
o caminho GQA GPU nativo exige atenção causal e ausência de máscara/softcap e
saídas extras; o caso não causal usa decomposição. O builder aceita float16/32,
rejeita máscara booleana, `nonpad_kv_seqlen` e `softmax_precision`. Para NAR,
converter máscara em bias aditivo float só com equivalência verificada. Não
trocar escala Granite 0.0078125 por `1/sqrt(head_dim)`.

Assim, “Attention suportado” não significa kernel eficiente para o editor.
O grafo existente pode continuar decomposto e ser melhor que uma fusão nova.
Qualquer mudança de opset/fusão recebe ID de artefato e controle próprio.
ArgMax é candidato útil: o
[builder plugin](https://github.com/onnxruntime/onnxruntime-qnn/blob/7332461750cb7fefc6444a674ebe34ce5c9c0d01/onnxruntime/core/providers/qnn/builder/opbuilder/argmax_argmin_op_builder.cc)
declara HTP rank máximo 4, `select_last_index=0` e adaptação interna de saída
INT64 para INT32. Isso pede prova com último eixo 100352 e ties, não uma
conclusão por nome do operador.

### 4.3 llama.cpp: editor plausível, import NAR ausente

A [API no tag](https://github.com/ggml-org/llama.cpp/blob/d81235049384534c167caea52b85a694f6103d14/include/llama.h)
expõe embeddings, logits por posição e atenção não causal. O
[conversor Granite](https://github.com/ggml-org/llama.cpp/blob/d81235049384534c167caea52b85a694f6103d14/conversion/granite.py)
registra Granite text, Speech AR e Plus, mas não `GraniteSpeechNarForASR`.
É uma rota de **editor isolado com conversor próprio**. Conferir se LoRA já
está incorporado no peso fonte antes de aplicar merge; duplicar adaptação é
erro. Preservar pesos amarrados e tokenizer integral.

O [contexto llama.cpp](https://github.com/ggml-org/llama.cpp/blob/d81235049384534c167caea52b85a694f6103d14/src/llama-context.cpp)
exige `n_ubatch >= n_tokens` quando atenção não causal. O piloto deverá usar
`n_batch >= S`, `n_ubatch >= S`, posições e máscaras corretas, limpar estado
entre exemplos e pedir saída de todas as posições textuais. Dividir S em
microbatches causais altera o editor. O custo de logits de todas as posições
e o caminho de ArgMax precisam de medição própria.

A [construção de embeddings](https://github.com/ggml-org/llama.cpp/blob/d81235049384534c167caea52b85a694f6103d14/src/llama-graph.cpp)
aplica a escala Granite a embeddings crus no caso sem deepstack. Não pré-escalar
e depois repetir a escala inadvertidamente. O
[grafo Granite](https://github.com/ggml-org/llama.cpp/blob/d81235049384534c167caea52b85a694f6103d14/src/models/granite.cpp)
contém suas escalas de residual/logits, mas isso ainda exige comparação com o
editor adaptado IBM.

### 4.4 MNN e ncnn: máscara, cache e precisão observados

O [Attention Vulkan MNN](https://github.com/alibaba/MNN/blob/d407447ed56c4121a11ccbd266dc184ca1ead0c2/source/backend/vulkan/buffer/execution/VulkanAttention.cpp)
possui caminho sem KV cache e máscara float. Máscara escalar sem shape é
sentinela causal; NAR deve usar bias não causal explícito ou ausência de máscara
conforme golden. O [Attention OpenCL MNN](https://github.com/alibaba/MNN/blob/d407447ed56c4121a11ccbd266dc184ca1ead0c2/source/backend/opencl/execution/buffer/AttentionBufExecution.cpp)
também possui gestão condicional de cache e máscara. Isso é evidência de
implementação de peças, não de exportação do modelo IBM completo.

O [pass SDPA pnnx](https://github.com/Tencent/ncnn/blob/e54f7b1f88434e1d844ea0551b880a1cfb079ce1/tools/pnnx/src/pass_ncnn/F_scaled_dot_product_attention.cpp)
tem casos não causais; o [teste ncnn](https://github.com/Tencent/ncnn/blob/e54f7b1f88434e1d844ea0551b880a1cfb079ce1/tools/pnnx/tests/ncnn/test_F_scaled_dot_product_attention.py)
inclui `enable_gqa=True` e máscaras. Contudo, a
[camada MultiHeadAttention Vulkan](https://github.com/Tencent/ncnn/blob/e54f7b1f88434e1d844ea0551b880a1cfb079ce1/src/layer/vulkan/multiheadattention_vulkan.cpp)
desativa `support_vulkan` quando `int8_scale_term` é ativo. Não oferecer editor
INT8 Vulkan antes de observar o grafo convertido e a atribuição efetiva. O
caminho decomposto pode ter comportamento diferente, a ser provado.

## 5. Memória e transferências: contas para dimensionar, não medições

Estas contas derivam dos shapes locais e usam **MiB=1048576 bytes**. Não incluem
pesos, workspace, duplicações, heap Java ou cache do driver e não representam
pico de memória.

| Tensor float32 | Exemplo pequeno | Exemplo maior | Implicação do desenho |
|---|---|---|---|
| Features `[1,T,4096]` | T200: 3.125 MiB | T2000: 31.25 MiB | Colocar projector junto do encoder pode evitar uma transferência maior |
| Encoder logits `[1,ceil(T/4),100352]` | T200: 19.140625 MiB | T2000: 191.40625 MiB | ArgMax no grafo evita materialização/export dos logits, mas kernel final pode continuar alocando intermediário |
| Audio embeds válidos `[1,T/5,2048]` para estes T | T200: 0.3125 MiB | T2000: 3.125 MiB | Handoff após projector é candidato menor; saída padded pode ter posições extras |
| Editor logits `[1,S,100352]` | S64: 24.5 MiB | S1408: 539 MiB | Saída token IDs muda tráfego; provar memória interna separadamente |
| Scores de atenção `[1,16,S,S]`, um tensor | S64: 0.25 MiB | S1408: 121 MiB | Decomposição noncausal e workspace podem inviabilizar bucket que passa no piloto |

Se o contrato retorna IDs INT64, S1408 ocupa 11 KiB na saída. Isso não autoriza
subtrair 539 MiB de um pico sem observar a execução: os logits podem existir
internamente antes do ArgMax. Não multiplicar automaticamente workspace por
40 camadas; política de buffers/reuso define a residência real.

Smart NAR deve comparar pelo menos encoder+projector no HTP/editor GPU,
encoder HTP/projector+editor GPU e acelerador/CPU por estágio. Primeiro medir
estágios em série com buffers CPU explícitos; só depois testar buffers
compartilhados se a cópia tiver peso relevante. Registrar bytes, dtype, escala,
layout, ownership e sincronização de cada fronteira. O mesmo formato de shape
não estabelece compartilhamento seguro de memória.

## 6. Ordem e contrato dos pilotos G04

### Regras comuns antes de liberar uma rodada

1. G01–G03 precisam fornecer pares íntegros, tokenizer validado e contrato/bucket
   observado. Reutilizar entradas e pesos locais por hash; somente áudio público
   no piloto inicial. Arquivo pessoal permanece local.
2. Fixar runtime/tag/NDK/ABI/opset, modelos/external data, SoC/driver e hash de
   cada biblioteca. Um controle por mudança: não mudar bucket, quantização,
   thread count e runtime simultaneamente.
3. Produzir três vetores pequenos: entrada real válida, padding/cauda, e caso
   discriminante de não causalidade. No último, modificar posição futura e
   conferir influência na posição anterior contra referência, além da máscara.
4. Executar primeiro golden host para cada conversão. Medir NRMSE/cosseno,
   max_abs/rel com regra para zeros, top1 e margens. Para transformação float,
   exigir inicialmente texto idêntico no piloto e investigar qualquer diferença;
   qualidade final pertence ao holdout PT-BR definido no plano principal.
5. Android: UID normal do APK experimental, um estágio/uma sessão por vez,
   controle CPU válido. Capturar load, compile, primeira inferência, quente,
   transfers, total pipeline, memória contínua e temperatura inicial/final.
   Thread/backend solicitado e observado entram em registros separados.
6. “Integral HTP” usa fallback CPU desabilitado no estágio em prova. “Híbrido”
   registra atribuição por nó/partição; não pode produzir etiqueta NPU integral.
   Falha de configuração/captura invalida resultado antes de comparar tempos.

### Sequência proposta e limites de orçamento

Os valores abaixo são propostas subordinadas ao
[plano principal](PLANO-GRANITE-20261009.md), não autorização de execução nem
previsão de duração. Os tetos finais precisam constar da **ordem liberada**.
Até essa liberação, prevalecem: geração inicial de contexto **10 min para
t200/t400**, abertura **LOAD 120 s** e teto de inferência calculado a partir do
controle CPU pelo protocolo do plano principal. Valores maiores precisam de
justificativa e decisão registrada **antes** de executar a tentativa. Preparação
host, compilação, LOAD e inferência têm relógios e tetos separados; um orçamento
de horas de trabalho não amplia um timeout de runtime.

| ID | Pergunta e entrada mínima | Orçamento inicial | Aprovação/saída e condição de parada |
|---|---|---|---|
| R01 CPU isolada | Mesmo grafo atual em ORT carregado conhecido versus upstream 1.30, se houver motivo após G01 | Inventário+preparo 2 h; 1 áudio público curto, depois 3; máximo 3 pares válidos de aquecimento/medida | Mesmos IDs/texto, erros dentro do gate e timings separados. Sem vantagem ou correção demonstrada: manter controle e registrar o braço, sem campanha grande |
| R02 capacidade QNN | Primeiro micrografo: MatMul/RMSNorm/GQA não causal/ArgMax, shapes do NAR; depois T200/S64 já existentes | 2 h trabalho de integração; compilação micrografo 5 min, contexto t200/t400 10 min; LOAD 120 s; no máximo 1 retry de falha ambiental corrigida | Bibliotecas/EP/ops observados, golden e retorno de IDs. Op rejeitado ou fail strict: identificar primeiro bloqueio e parar esse estágio |
| R03 MNN host/OpenCL | Export de 1 bloco editor GQA 16:4, escala Granite; janela projector15→3; depois estágio completo mínimo | 2 h conversão; 30 min por conversão float; nada de quantização em lote | Converter sem op perdido, paridade host; OpenCL Android observado. Causal sentinel, cache ativo ou fallback involuntário invalidam o braço |
| R04 editor GGUF | Pesos do editor NAR sem dupla LoRA, S64 input embeddings golden, noncausal, todas posições | 4 h teto de preparação/conversor; um artefato float antes de Q8/Q4 | Escalas/tokenizer/pesos demonstrados e golden; se o conversor exigir reconstrução extensa sem piloto de bloco, devolver decisão ao especialista |
| R05 Vulkan | Mesmo golden MNN aprovado; alternativamente pnnx/ncnn para estágio bloqueado; GGUF após R04 | 2 h por candidato; 10 min compilação de shaders pequena; 1 áudio/3 repetições | Atribuição Vulkan real e texto; máscara/GQA/INT8 verificados. Sem ganho técnico: não manter três integrações inteiras concorrentes |
| R06 HTP QDQ | Enc T200 e proj T200 íntegros; editor S64 somente depois de cobertura e gate do trio | Geração contexto t200/t400 10 min; LOAD 120 s; inferência conforme controle/protocolo raiz; 1 tentativa inicial e 1 retry justificado | Strict pelo estágio, cache realmente lido, paridade e UID app. OOM/timeout/SSR encerra braço e preserva primeira falha; sem ciclos de reboot |
| R06F HTP float | Micrografo não causal primeiro, depois proj T200 float íntegro; não começar no editor inteiro | 30 min trabalho host de capacidade; contexto t200/t400 10 min; LOAD 120 s; inferência conforme controle/protocolo raiz | SoC/QAIRT compatíveis e execução HTP observada; comparar contra mesmo float CPU e, se aprovado, QDQ. “fp16_precision=0” não rotula FP32 |
| R07 Smart NAR | Duas rotas de estágio já aprovadas, T200/S64 e mesmas entradas | 2 h glue experimental; máximo 3 combos iniciais | Ganho total incluindo cópias/load, memória e texto; começar por fronteira após projector. Sem ganho líquido: conservar resultados por estágio, não recomendar Smart |
| R08 Smart AR | Fonte AR separada, prefill pequeno e 8 passos greedy; controle AR num único runtime | Inventário/metadados 1 h; capacidade cache 2 h antes de baixar/exportar conjunto grande | KV compatível demonstrado e primeiro token/8 passos equivalentes; incompatibilidade de representação sem conversor barato suspende split e permite investigar AR GPU único |

O orçamento de R01 pressupõe controle CPU íntegro e não obriga gerar build novo
quando G01 já basta para decidir. R02 também deve testar o **plugin QNN 2.6**
em ambiente isolado, reproduzindo suas dependências Android documentadas.
ORT 1.30 e QNN plugin 2.6 são experimentos separados; aprovação de um não
aprova a combinação. Descobrir inexistência de biblioteca/compatibilidade é
resultado do inventário, não justificativa para baixar gigabytes neste turno.

Para candidatos que passarem compatibilidade, executar ABBA e BAAB com sessão
reutilizada de verdade, denominadores iguais e tempo térmico definido pelo
protocolo principal. A fase mínima de 3 execuções detecta crashes/divergências,
não sustenta recomendação estatística de desempenho. Expandir T200→T400 e
S64→S128 um passo de cada vez, somente quando o maior risco tenha sido eliminado.

Paradas globais: OOM, reset do DSP, travamento, timeout ou erro numérico não
finito encerram o braço; guardar run inicial, causa e retry. Encerrar somente
PID/run pertencentes à tarefa. Restaurar APK/configuração do proprietário e
validar hash; nunca remover modelos/datasets/worktrees para liberar espaço sem
decisão de descarte. Sem telefone aprovado, classificação máxima é host/context
ready, nunca NPU/GPU aprovado Android.

## 7. Smart AR e contrato KV que ainda falta

O [modelo AR oficial IBM](https://huggingface.co/ibm-granite/granite-speech-4.1-2b)
documenta português e uso com llama.cpp. Seu
[config nesta revisão](https://huggingface.co/ibm-granite/granite-speech-4.1-2b/raw/de575db64086f84fdc79da4932d1076e965bc546/config.json)
tem vocab **100353**, diferente do NAR 100352. Portanto IDs/tokenizer/golden
não são intercambiáveis. A implementação AR em llama.cpp confirma uma rota
candidata, não aprovação Android nem interoperabilidade de caches.

Antes de quantização/export AR extensos, congelar: pesos/adaptação, prompt e
processor, sequência de audio embeds, posições, RoPE/escala, máscara causal,
layout K/V por camada e por cabeça, offset, comprimento válido/capacidade,
quantização/scales, strides, propriedade de buffers e importação no runtime
de decode. Acordar se o cache guarda K antes ou depois de RoPE e o formato de V.

O gate R08 compara prefills no mesmo runtime de referência, exporta/importa KV
e confere o **primeiro passo de decode** antes de 8 passos. Comparar apenas
logits de prefill, shape ou texto final não localiza a incompatibilidade.
Quando ambos usam quantização, medir erro do cache separadamente do erro dos
pesos. Prefill NPU pode perder no total por load, compilação ou cópias; reportar
TTFT, decode/token, total, memória e carga isoladamente. AR tem referência de
qualidade própria e deve ser rotulado como outra opção de modelo.

## 8. Licenças e pacote redistribuído

Inventário de licenças declaradas nas fontes, não parecer jurídico. Conservar
licença/notices aplicáveis a cada pacote realmente distribuído.

| Objeto | Licença declarada | Evidência e obrigação de inventário |
|---|---|---|
| Granite NAR e Granite AR | Apache-2.0 | [Card NAR](https://huggingface.co/ibm-granite/granite-speech-4.1-2b-nar), [card AR](https://huggingface.co/ibm-granite/granite-speech-4.1-2b); manter revisão, procedência do derivado e notices |
| ORT upstream | MIT | [LICENSE fixo](https://github.com/microsoft/onnxruntime/blob/f2c39fe2f838cf35ce7da92824f5a5e3ee6e88a7/LICENSE) |
| QNN EP plugin | MIT | [LICENSE fixo](https://github.com/onnxruntime/onnxruntime-qnn/blob/7332461750cb7fefc6444a674ebe34ce5c9c0d01/LICENSE), [POM 2.6](https://repo1.maven.org/maven2/com/qualcomm/qti/onnxruntime-android-qnn/2.6.0/onnxruntime-android-qnn-2.6.0.pom) |
| Bibliotecas QNN/QAIRT | Licença Qualcomm própria, separada da MIT do EP | [POM qnn-runtime 2.50.0](https://repo1.maven.org/maven2/com/qualcomm/qti/qnn-runtime/2.50.0/qnn-runtime-2.50.0.pom) declara Qualcomm AI Hub Model License e [licença do fornecedor](https://softwarecenter.qualcomm.com/api/download/software/licenses/ai_model_hub/v1/LICENSE.pdf); registrar licença do SDK/pacote concreto antes da redistribuição |
| llama.cpp | MIT | [LICENSE fixo](https://github.com/ggml-org/llama.cpp/blob/d81235049384534c167caea52b85a694f6103d14/LICENSE) |
| ggml | MIT | [LICENSE fixo](https://github.com/ggml-org/ggml/blob/d7cb574130e6f01ad25b3289685489200febcd74/LICENSE) |
| MNN | Apache-2.0 | [LICENSE fixo](https://github.com/alibaba/MNN/blob/d407447ed56c4121a11ccbd266dc184ca1ead0c2/LICENSE.txt) |
| ncnn | BSD-3-Clause, com componentes terceiros sob termos próprios | [LICENSE fixo](https://github.com/Tencent/ncnn/blob/e54f7b1f88434e1d844ea0551b880a1cfb079ce1/LICENSE.txt) |

O plugin MIT não transforma `libQnnHtp.so`, stubs/skels e demais binários do
SDK em MIT. Driver GPU, loader OpenCL/Vulkan e eventuais kernels binários
Adreno também devem constar do inventário quando empacotados. Pilotos podem
utilizar bibliotecas já íntegras; release segue a aprovação e os verificadores
nativos do AGENTS.

Na revisão de links, releases/POMs e o LICENSE do plugin responderam HTTP 200.
O PDF de licença Qualcomm referenciado pelo POM respondeu HTTP 403 ao pedido
de metadados desta sessão. O nome da licença foi confirmado pelo POM; seu texto
e os termos do pacote efetivamente instalado não foram auditados. Obter esses
termos pela distribuição oficial/licença do SDK antes de decidir redistribuição;
essa limitação não é uma reprovação do piloto técnico local já autorizado.

## 9. Evidências de saída e decisão após G04

Diretório por tentativa, fora do conjunto de produção: manifest de entradas e
hashes, `runtime-manifest.json`, `observed-config.json`, `stage-contract.json`,
`node-assignment.json` ou profiling equivalente, golden/IDs/UTF-8, métricas
geradas, timeline de memória/temperatura, terminal e log bruto por run/pid,
ledger de tentativas e restauração. Arquivo de artefato recebe hash próprio;
tag ou extensão não prova dtype, bits, backend ou compilação.

Decidir separadamente: (1) converte; (2) executa; (3) usa o hardware solicitado;
(4) preserva a semântica/qualidade; (5) melhora algum cenário útil; (6) está pronto
para produto. Um candidato pode sobreviver como experimento de memória sem
vencer latência. Falha de compilação de um estágio não reprova todo runtime;
aprovação de um estágio não aprova o pipeline.

Recomendação desta pesquisa: consolidar evidência/CTC/ArgMax antes de migração;
usar micrografos e estágios existentes para comparar QNN ponte/plugin;
avaliar MNN como conversão que pode compartilhar OpenCL/Vulkan e llama.cpp
como alternativa de editor; manter ncnn/ggml customizado para bloqueios
identificados. Essa priorização é uma decisão de custo/risco, **não** resultado
de benchmark nem declaração de superioridade de runtime.
