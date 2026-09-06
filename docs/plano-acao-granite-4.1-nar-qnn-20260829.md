# Plano de ação — Granite Speech 4.1 NAR em CPU, GPU e NPU

**Projeto:** SIG Android  
**Data-base:** 29/08/2026  
**Alvo primário:** OnePlus 15, SM8850, HTP v81, Android 16  
**Estado:** plano executável; fases sem aparelho já podem começar  
**Decisão:** não é um beco sem saída, mas os artefatos FP16 atuais não são uma base válida para HTP.

## 1. Veredito executivo

Há uma rota tecnicamente sólida para os três backends:

- **CPU:** exportar comprimentos estáticos menores e selecionar o menor bucket que comporte o áudio. Hoje até um áudio de poucos segundos percorre o encoder e o projector com `T=2000`; eliminar esse desperdício deve ser a primeira otimização, pois também reduz memória e acelera a criação das variantes usadas por GPU/NPU.
- **GPU Adreno:** começar com ONNX FP16 estático, sem quantização. A prioridade é remover ou dobrar as construções dinâmicas e validar cada subgrafo com fallback CPU proibido. Quantização de pesos para `uint8` só entra depois que FP16 integral funcionar.
- **NPU Hexagon/HTP:** criar modelos QDQ com shapes inteiramente estáticos, calibrados com dados reais, e então gerar contexto QNN pré-compilado específico do SM8850/HTP v81. O contexto elimina a preparação de vários minutos que tornou o teste híbrido atual inviável.

O telefone não é necessário para exportar, simplificar, quantizar, validar numericamente, organizar os artefatos nem testar a integração Android. Ele é indispensável para quatro gates: suporte integral do QNN, geração/validação final do contexto SM8850, profiling HTP/Adreno e aceitação térmica/energética.

O emulador Android pode validar registro de artefatos, download, SHA-256, escolha de buckets, erros/fallback e CPU com fixtures pequenas. Ele **não** emula a GPU Adreno, FastRPC, HTP v81 nem o compilador QNN do firmware do OnePlus.

## 2. Evidência já conhecida

### 2.1 Contrato atual do modelo

O pacote NAR atual tem três sessões ONNX:

| Grafo | Entrada atual | Saída relevante | Peso aproximado |
|---|---:|---:|---:|
| Encoder conformer | `[1,2000,160]` | BPE `[1,500,100352]`; features `[1,2000,4096]` | 1,09 GB |
| Projector Q-Former | `[1,2000,4096]` | áudio `[1,402,2048]` | 319 MB |
| LLM bidirecional | embeds `[1,S,2048]`; posições `[1,S]` | logits `[1,S,100352]` | 3,27 GB |

Além disso, `nar_embed_tokens.bin` ocupa aproximadamente 411 MB. O pacote completo chega a cerca de 4,4 GB.

O frontend produz aproximadamente 50 frames empilhados por segundo. Portanto, `T=2000` representa cerca de 40 segundos nominais, não importa se o arquivo contém apenas 2 segundos. O encoder usa atenção em blocos de 200 frames; o projector usa blocos de 15 com downsample de 5.

### 2.2 Medições e falhas já observadas

- O engine antigo ignorava o backend escolhido; a aparente execução GPU/NPU era CPU.
- Com fallback proibido, NPU recusou o encoder porque parte do grafo ficou no CPU EP.
- Em modo híbrido, o encoder QNN foi criado em aproximadamente 4,4 s, mas o projector não terminou em mais de 2 min e o processo atingiu cerca de 3,3 GB de memória nativa.
- GPU estrita recusou vários padrões dinâmicos e terminou o encoder com erro QNN 6022.
- CPU `BASIC_OPT` reduziu a inferência do mesmo WAV de 41,48 s para 36,41 s, mantendo a transcrição idêntica: ganho de 12,2%.

Esses resultados não dizem que a NPU é lenta. Eles dizem que o artefato errado foi compilado em tempo de execução, com partição híbrida e shapes inadequados.

### 2.3 Regras externas que orientam o desenho

- A documentação oficial do QNN EP afirma que HTP requer modelo quantizado e que shapes dinâmicos devem ser fixados. Recomenda `qnn_preprocess_model`, `get_qnn_qdq_config`, calibração representativa e oferece `session.disable_cpu_ep_fallback=1`: <https://onnxruntime.ai/docs/execution-providers/QNN-ExecutionProvider.html>.
- A mesma documentação informa que a GPU QNN aceita FP32/FP16 sem quantização; FP16 tende a ser mais rápido. Também documenta cache de contexto e profiling QNN.
- `EPContext` existe justamente para evitar conversão/compilação de modelos grandes a cada sessão: <https://onnxruntime.ai/docs/execution-providers/EP-Context-Design.html>.
- O Qualcomm AI Hub confirma que contexto QNN é específico do SoC, exclusivo da NPU e pode ser embrulhado em ONNX pré-compilado: <https://workbench.aihub.qualcomm.com/docs/hub/compile_examples.html>.
- O AI Hub também suporta vincular variantes de shapes em um único contexto multigrafo com pesos compartilhados: <https://workbench.aihub.qualcomm.com/docs/hub/generated/qai_hub.submit_compile_and_link_jobs.html>.
- A fonte oficial IBM confirma `context_size=200`, `block_size=15`, downsample 5 e que o LLM bidirecional aceita `attention_mask`: <https://huggingface.co/ibm-granite/granite-speech-4.1-2b-nar>.

## 3. Princípios obrigatórios

1. **Nenhum backend por rótulo.** GPU/NPU só passam quando `session.disable_cpu_ep_fallback=1` permite criar e executar todas as sessões planejadas.
2. **Uma mudança por experimento.** Shape, reescrita de grafo, precisão, calibração, provider option e contexto não serão trocados simultaneamente.
3. **PyTorch oficial é a referência.** Cada export deve ser comparado com uma revisão IBM imutável e registrada.
4. **Calibração encadeada.** Projector recebe features reais do encoder; LLM recebe embeddings reais do projector e dos slots. Dados aleatórios não são aceitáveis.
5. **Tempo frio e quente são métricas distintas.** Download, load, compilação, primeira inferência e inferências aquecidas serão registrados separadamente.
6. **Qualidade precede velocidade.** Um artefato rápido com regressão de WER/CER ou texto instável não é candidato de release.
7. **Artefatos são imutáveis.** Nome versionado, SHA-256, manifesto, proveniência e rollback; nunca substituir silenciosamente uma chave R2 já aprovada.
8. **Sem explosão de armazenamento por acidente.** Variantes devem compartilhar external data ou contextos multigrafo quando isso for comprovadamente compatível.

## 4. Arquitetura de artefatos proposta

### 4.1 Famílias

| Família | Encoder/projector | LLM | Uso |
|---|---|---|---|
| `cpu-float` | F32 e/ou FP16 estático por `T` | dinâmico inicialmente; estático depois | baseline e fallback universal |
| `gpu-fp16` | FP16 estático por `T` | FP16 estático por bucket `S` com máscara | QNN GPU integral |
| `npu-u16u8` | QDQ U16 ativações/U8 pesos | QDQ U16/U8 por `S` | primeiro candidato de qualidade HTP |
| `npu-u8u8` | QDQ U8/U8 | QDQ U8/U8 por `S` | candidato posterior de velocidade/tamanho |
| `npu-context-sm8850-v81` | wrappers `_ctx.onnx` + `.bin` | contexto multigrafo quando possível | produção no OnePlus 15/família compatível validada |

O HTP começa em **U16 ativações + U8 pesos**, combinação usada no exemplo oficial do QNN EP e menos agressiva para qualidade. U8/U8 só avança quando U16/U8 já tiver uma linha de base aprovada.

### 4.2 Manifesto do pacote

O app não deve receber dezenas de constantes Kotlin. Um manifesto versionado deve descrever:

```json
{
  "schema": 1,
  "package_id": "granite-4.1-nar-qnn-001",
  "source": {
    "repo": "ibm-granite/granite-speech-4.1-2b-nar",
    "revision": "<commit imutável>",
    "license": "Apache-2.0"
  },
  "toolchain": {
    "onnxruntime": "1.29.0",
    "qairt": "<build exato>",
    "opset": 17
  },
  "targets": [
    {
      "backend": "npu",
      "soc": "SM8850",
      "htp_arch": "81",
      "precision": "u16u8",
      "encoder_buckets": [200, 400, 800, 1200, 1600, 2000],
      "llm_buckets": [64, 128, 256, 512, 768, 1024, 1408]
    }
  ],
  "files": [
    {"path": "...", "bytes": 0, "sha256": "...", "role": "..."}
  ]
}
```

O manifesto final também guardará:

- hash da configuração e código de export;
- hash do manifesto de calibração;
- assinatura completa de inputs/outputs;
- escala/zero point por tensor ou hash do relatório QDQ;
- versão da biblioteca ORT Android e de cada `libQnn*.so`;
- SoC, arquitetura HTP e flags usadas na geração do contexto;
- resultados resumidos de paridade e benchmark;
- compatibilidade mínima do app e política de rollback.

### 4.3 Seleção em runtime

1. Calcular `realFrames` após o frontend.
2. Escolher o menor bucket `T >= realFrames`.
3. Executar encoder e CTC; calcular `validAudio`, slots e `S` real.
4. Escolher o menor bucket `S_bucket >= S`.
5. Preencher embeddings até `S_bucket` e fornecer máscara `[1,S_bucket]` com 1 no prefixo real e 0 no padding.
6. Colapsar logits somente no intervalo textual real.
7. Registrar `package_id`, artefatos, buckets, backend solicitado/efetivo e tempos.
8. Se não houver bucket ou artefato íntegro, oferecer fallback explícito; nunca trocar de backend silenciosamente.

O LLM oficial suporta `attention_mask`, mas a equivalência entre sequência exata e sequência preenchida precisa ser provada em PyTorch e ONNX antes de se congelar essa arquitetura.

## 5. Buckets iniciais e experimento para reduzi-los

### 5.1 Buckets de áudio propostos

| `T` | duração nominal | saída projector | observação |
|---:|---:|---:|---|
| 200 | ~4 s | 42 tokens | alinhado ao bloco do encoder |
| 400 | ~8 s | 81 tokens | alinhado ao bloco do encoder |
| 800 | ~16 s | 162 tokens | primeiro bucket médio |
| 1200 | ~24 s | 240 tokens | também alinha exatamente ao bloco 15 |
| 1600 | ~32 s | 321 tokens | áudio longo |
| 2000 | ~40 s | 402 tokens | compatibilidade com o pacote atual |

A fórmula da saída do projector é `ceil(T / 15) * 3`. Os tokens válidos continuam sendo `realFrames / 5`; o excedente do bloco é descartado.

Essa lista é deliberadamente ampla para a fase de laboratório. Antes de publicar, a telemetria do corpus de benchmark deve permitir remover buckets pouco usados. Uma opção de pacote inicial menor é `200/400/800/2000`; ela só será escolhida se o salto 16→40 s não prejudicar demasiadamente CPU, memória e HTP.

### 5.2 Buckets do LLM propostos

`S = validAudio + max(2 * ctcTokens + 1, 8)`. O pior caso teórico para `T=2000` fica perto de 1401, mas fala real tende a produzir muito menos tokens CTC.

Começar no laboratório com `64/128/256/512/768/1024/1408`. O harness ADB agora registra `ctc_tokens`, `valid_audio`, `slots` e `llm_tokens`; a primeira matriz real determinará percentis P50/P90/P95/P99 e a lista final.

### 5.3 Gates específicos de padding

- PyTorch com `S` exato versus PyTorch com `S_bucket` + máscara: logits do prefixo real dentro da tolerância.
- ONNX estático versus PyTorch estático: mesma condição.
- Texto final idêntico no conjunto dourado float.
- Máscara totalmente suportada no QNN integral.
- Nenhum logit do padding entra no CTC final.

Se a criação de máscara gerar ops QNN incompatíveis, as alternativas, nesta ordem, são:

1. reescrever a máscara como bias aditivo estático de shape fixo;
2. exportar variantes exatas para os comprimentos observados mais comuns;
3. reunir variantes em contexto QNN multigrafo com pesos compartilhados;
4. manter LLM no melhor backend comprovado e declarar pipeline híbrido, apenas se ele ainda vencer CPU e a UI disser a verdade.

## 6. Fases de execução

## Fase 0 — Congelar a referência e o ambiente

**Pode ser feita sem telefone:** sim.

### Ações

- Escolher e registrar a revisão imutável do repositório IBM. Não exportar de `main` sem guardar o commit.
- Baixar código, configuração, tokenizer, pesos e arquivo de áudio oficial; registrar tamanho e SHA-256.
- Criar ambiente Python isolado com versões exatas de Python, PyTorch, Transformers, ONNX e ORT.
- Registrar CPU, RAM, GPU/VRAM, discos e espaço disponível da máquina de export.
- Confirmar a versão QAIRT real contida no pacote `qairt/v1`; `PACKAGE_VERSION=1` é versão do pacote SIG, não versão do SDK.
- Construir matriz ORT Android ↔ QAIRT e não presumir compatibilidade apenas porque as bibliotecas carregam.
- Reservar diretório de trabalho em disco com espaço amplo; processar um subgrafo por vez para não exceder 32 GB de RAM.

### Entregáveis

- `tools/granite/nar/requirements-lock.txt` ou lock equivalente;
- `source-manifest.json` com revisão e hashes;
- relatório de ambiente e espaço;
- matriz de compatibilidade ORT/QAIRT.

### Gate de saída

Uma reinstalação limpa consegue carregar o modelo PyTorch e repetir o áudio oficial com o texto esperado.

## Fase 1 — Exportador NAR reproduzível

**Pode ser feita sem telefone:** sim.

O arquivo `granite-nar-export-prompt.txt` não é um pipeline executável. Criar uma ferramenta própria, com comandos independentes:

```text
download -> export-float -> validate-float -> capture-calibration
         -> quantize-qnn -> validate-qdq -> package -> publish
```

### Ações

- Implementar wrappers explícitos para encoder, projector e LLM.
- Exportar primeiro uma variante pequena (`T=200`, `S=64`) para tornar o ciclo de correção rápido.
- Exportar logits em FP32 nas bordas, ainda que pesos/computação internos sejam FP16.
- Fornecer nomes estáveis para todos os nós de I/O.
- Usar external data e permitir que variantes compartilhem inicializadores idênticos.
- Salvar tensors dourados de entrada e saída em formato streaming, sem copiar logits gigantes desnecessariamente.
- Gerar relatório de operadores, shapes dinâmicos restantes e inicializadores duplicados.
- Fazer shape inference e validação ONNX.

### Reescritas candidatas, não automáticas

- dobrar `Shape`, `ConstantOfShape`, `Slice`, `Reshape` e `Pad` quando os valores forem determinados pelo bucket;
- substituir `Einsum` do encoder por `MatMul`/`Transpose` equivalente se o QNN recusar o padrão;
- exportar SDPA em decomposição que o backend aceite;
- evitar `If`, `Loop` e controle de fluxo no grafo final;
- preservar o comportamento da máscara, RoPE e atenção bidirecional.

Cada reescrita exige paridade antes/depois. Não aplicar uma transformação global ao pacote inteiro sem relatório por nó.

### Gate de saída

- ORT CPU executa cada subgrafo isolado.
- PyTorch ↔ ONNX passa nas variantes pequenas e `T=2000`.
- Texto final é idêntico no conjunto dourado.
- Nenhuma dimensão dinâmica permanece nos artefatos candidatos QNN.

## Fase 2 — Corpus dourado e calibração

**Pode ser feita sem telefone:** sim.

### Corpus funcional

Criar manifestos separados para desenvolvimento e aceitação. Usar apenas áudio público/licenciado ou sintético aprovado, nunca material operacional/produção.

Cobertura mínima:

- português, inglês, espanhol, francês e alemão;
- 1–4 s, 4–8 s, 8–16 s, 16–24 s, 24–40 s;
- voz masculina/feminina e variação de sotaque quando disponível;
- limpo, ruído moderado, fala baixa e pausas;
- silêncio, áudio quase vazio e limite exato de bucket;
- transcrições normalizadas e hashes dos arquivos.

### Calibração encadeada

1. **Encoder:** features log-mel reais, já produzidas pelo frontend exato do app.
2. **Projector:** `multilayer_features` geradas pelo encoder float de referência para os mesmos áudios.
3. **LLM:** embeddings de áudio reais + embeddings dos slots CTC reais, com distribuição por bucket `S`.

Os readers devem iterar em streaming. Não salvar indiscriminadamente `[T/4,100352]` ou `[S,100352]` para todo o corpus.

### Métricas de qualidade

- máximo erro absoluto, erro relativo, NRMSE e similaridade de cosseno por tensor intermediário;
- concordância top-1 e top-k dos logits CTC;
- distância entre sequências CTC;
- texto exato no conjunto de smoke;
- WER e CER por idioma e global;
- contagem de NaN/Inf e saturação de quantização.

### Gate de saída

Manifestos de dados e calibração são imutáveis e reproduzem as estatísticas. O baseline PyTorch e ONNX float é aprovado antes de qualquer QDQ.

## Fase 3 — Otimização CPU primeiro

**Pode ser feita sem telefone:** parcialmente; Android real fecha o gate.

### Ações

- Implementar seleção dos buckets `T` no pipeline float.
- Comparar F32, FP16 e, se suportado pelo build, BF16 por subgrafo; não assumir que FP16 é mais rápido no CPU ARM.
- Comparar `NO_OPT`, `BASIC_OPT`, `EXTENDED_OPT` e `ALL_OPT` separadamente. O projector já mostrou que otimização agressiva pode gerar kernel Gelu FP16 incompatível.
- Testar número de threads intra/inter-op de forma controlada; evitar oversubscription entre três sessões.
- Avaliar disponibilidade e cobertura do XNNPACK no artefato Android real, sem presumir que o AAR QNN o inclui.
- Reduzir cópias restantes: especialmente o tensor `[T,4096]`, buffers de embeddings e I/O entre sessões. Avaliar buffers diretos/I/O binding apenas depois de medir a cópia.
- Reutilizar sessões durante o lote, mas separar benchmark de load e inferência.
- Definir chunking para áudio > bucket máximo, com overlap e regra de junção testada.

### Hipótese principal

Para áudio curto, reduzir `T=2000` para `T=200/400` deve valer mais do que micro-otimizações Kotlin. O objetivo inicial é pelo menos **2×** no áudio de 2–4 s; o gate mínimo de continuidade é ganho mediano de **20%** sem piorar texto ou memória.

### Gate de saída

Existe um baseline CPU por duração, com três ou mais execuções medidas, texto estável, memória e temperatura. A melhor política é registrada por subgrafo.

## Fase 4 — Compatibilidade GPU FP16

**Pode ser feita sem telefone:** preparação sim; gate final não.

### Ordem dos experimentos

1. Encoder `T=200` FP16 estático, estrito.
2. Projector `T=200` FP16 estático, estrito.
3. LLM `S=64` FP16 estático com máscara, estrito.
4. Pipeline pequeno completo.
5. Aumentar buckets isoladamente até o máximo.

Para cada falha, capturar primeiro operador/nó rejeitado e erro de finalização. Corrigir o padrão mínimo, repetir a paridade CPU e só depois avançar.

### Critérios

- criação de sessão com `backend_path=libQnnGpu.so` e fallback proibido;
- backend efetivo GPU nos três grafos;
- texto igual ao float aprovado;
- load e inferência sem crescimento de memória a cada repetição;
- inferência quente mais rápida que o melhor CPU para o mesmo bucket.

Se o grafo pequeno passa e o grande falha 6022/6020, testar limites por bucket e por bloco. Particionar o encoder só será considerado quando houver um corte natural, contrato de tensor estável e ganho suficiente para pagar as transferências.

Quantização de peso U8 com ativação float é uma otimização posterior, não uma dependência para fazer GPU funcionar.

## Fase 5 — QDQ para HTP

**Pode ser feita sem telefone:** geração e paridade CPU sim; suporte HTP não.

### Pipeline inicial

1. Executar `qnn_preprocess_model`.
2. Confirmar shape inference e ausência de dimensões dinâmicas.
3. Criar `CalibrationDataReader` específico do subgrafo/bucket.
4. Gerar configuração com `get_qnn_qdq_config`.
5. Quantizar em QDQ com ativações QUInt16 e pesos QUInt8.
6. Rodar o QDQ no CPU EP e comparar ao float.
7. Produzir relatório de cobertura Q/DQ, tensores excluídos e saturação.

### Estratégia de recuperação de qualidade

- excluir temporariamente I/O e cabeças de logits da quantização;
- manter LayerNorm/RMSNorm, Softmax, RoPE ou projeções sensíveis em precisão maior quando suportado;
- usar overrides por tensor/nó;
- aumentar representatividade da calibração antes de reduzir precisão;
- só então testar U8/U8 e variantes mistas.

### Gate de saída

- QDQ U16/U8 mantém os limites de tensor e WER/CER.
- Todos os inputs/outputs conservam nomes, tipos e escalas esperados.
- O pacote registra exatamente quais nós não foram quantizados e por quê.

## Fase 6 — Contexto QNN pré-compilado

**Requer telefone ou compilador remoto autorizado compatível com SM8850:** sim.

Executar primeiro com a menor variante. Há duas rotas válidas:

### Rota A — EPContext do ORT

- carregar o QDQ com HTP e `ep.context_enable=1`;
- usar `ep.context_embed_mode=0` para manter ONNX wrapper pequeno e `.bin` separado;
- fixar `ep.context_file_path`;
- destruir a sessão de geração;
- criar nova sessão usando `_ctx.onnx` e confirmar que não recompila o grafo;
- repetir com profiling básico e depois optrace.

### Rota B — QAIRT/AI Hub

- compilar variantes estáticas para SM8850;
- vincular variantes de `T` e `S` em contexto multigrafo com pesos compartilhados;
- gerar wrappers ONNX `EPContext` por grafo;
- guardar job IDs, versões e logs no manifesto.

O AI Hub só é aceito se oferecer o alvo exato ou uma compatibilidade formalmente verificável. Perfil em dispositivo proxy não substitui o OnePlus real.

### Matriz de flags a medir

- `htp_performance_mode`: burst e opções sustentáveis;
- `htp_graph_finalization_optimization_mode`: comparar load/context e execução;
- `vtcm_mv` dentro do limite do dispositivo;
- `rpc_control_latency`;
- `enable_htp_shared_memory_allocator` quando o runtime e o aparelho suportarem;
- `profiling_level`: basic, detailed e optrace apenas em builds de laboratório.

### Gate de saída

- contexto abre integralmente com fallback proibido;
- load do contexto fica dentro da meta e não recompila;
- três execuções repetidas são estáveis;
- contexto gerado e runtime usam toolchain compatível;
- o arquivo não é tratado como universal: target SM8850/HTP v81 fica explícito.

## Fase 7 — Registro e download no app

**Pode ser feita sem telefone:** sim, exceto aceitação QNN.

### Componentes

- parser e validador do manifesto;
- registro por backend/SoC/precisão/bucket;
- download sob demanda e retomável;
- SHA-256 antes da ativação;
- staging + troca atômica;
- limite de disco e remoção de versões antigas recuperável;
- rollback para último pacote aprovado;
- mensagens claras quando falta variante ou o aparelho é incompatível.

### Política de memória

- não manter todas as sessões de todos os buckets abertas;
- cache LRU pequeno, inicialmente uma dupla encoder/projector e uma sessão LLM;
- medir custo de troca de bucket versus memória;
- para contexto compartilhado, respeitar ordem de criação/destruição exigida pelo EPContext;
- mapear pesos/embeddings em vez de copiá-los ao heap Java.

### Gate de saída

Emulador e testes JVM cobrem seleção, fallback, integridade, rollback e compatibilidade. APK debug abre e executa CPU com um pacote aprovado.

## Fase 8 — Aceitação no telefone

**Requer telefone:** sim.

O script `scripts/run-granite-nar-adb-benchmark.ps1` já prepara essa fase. Ele:

- monta e instala o APK debug;
- envia um WAV diretamente à área externa privada do app;
- força parada entre backends para separar load frio;
- executa Activity debug com fallback proibido;
- faz warmup e execuções medidas;
- coleta JSONL, logcat completo, `dumpsys meminfo`, bateria, thermal service e propriedades;
- grava tudo em `build/granite-nar-adb/<timestamp>` (ignorado pelo Git);
- omite a transcrição por padrão e guarda seu SHA-256.

Exemplo:

```powershell
& .\scripts\run-granite-nar-adb-benchmark.ps1 `
  -AudioPath D:\audios\nar-pt-04s.wav `
  -Backends CPU,GPU_QNN,NPU_QNN_HTP `
  -WarmupRuns 1 `
  -MeasuredRuns 3 `
  -RequireFullAcceleration $true
```

Para diagnóstico apenas de criação das sessões:

```powershell
& .\scripts\run-granite-nar-adb-benchmark.ps1 `
  -AudioPath D:\audios\nar-pt-04s.wav `
  -Backends NPU_QNN_HTP `
  -LoadOnly `
  -MeasuredRuns 1 `
  -TimeoutSeconds 1200
```

`-IncludeTranscript` só deve ser usado com áudio de teste não sensível. `-RequireAllPassed` transforma qualquer backend falho em exit code de falha para gate automatizado.

### Matriz mínima por pacote candidato

| Eixo | Valores |
|---|---|
| Backend | CPU, GPU estrita, NPU estrita/contexto |
| Duração | ~2 s, ~6 s, ~12 s, ~20 s, ~30 s, ~39 s |
| Idioma | PT obrigatório; EN/ES/FR/DE no gate de qualidade |
| Estado | load frio; 1 warmup; 3–5 medidas |
| Repetição | mediana e p95; cooldown entre grupos |
| Precisão | float baseline; U16/U8; depois U8/U8 |

### Métricas obrigatórias

- load total e load por sessão;
- frontend, encoder, CTC/cópia, projector, montagem, LLM e decode;
- tempo total e real-time factor;
- PSS total, nativo, Dalvik, private dirty e pico observado;
- temperatura da bateria e thermal status antes/depois;
- backend solicitado/efetivo e modo estrito;
- `T`, `S`, CTC tokens, bucket e artefato;
- tamanho/hash da transcrição; WER/CER fora do log quando houver referência;
- profiling QNN por grafo nas rodadas de diagnóstico.

## 7. Metas de aceitação

São metas iniciais, ajustáveis após a primeira matriz; não são promessas de hardware.

### Correção

- 100% das sessões aceleradas criadas com fallback CPU proibido.
- Nenhum NaN/Inf.
- Smoke curto com texto idêntico ao float.
- Aumento absoluto de WER global ≤ 1,0 ponto percentual e nenhum idioma com regressão > 1,5 ponto; se o corpus inicial for pequeno, exigir também CER e inspeção por amostra.

### Desempenho

- CPU bucketizado: ganho mediano mínimo de 20%; alvo ≥ 2× em áudio 2–4 s.
- GPU quente: mais rápida que o melhor CPU no mesmo áudio e sem load/memória proibitivos.
- NPU contexto quente: alvo ≥ 2× sobre o melhor CPU; no mínimo deve vencer CPU de forma consistente para permanecer exposta na UI.
- Load NPU via contexto: alvo < 10 s para o conjunto necessário; comparar também primeiro uso após instalação.
- Sem crescimento monotônico relevante de PSS em cinco inferências.
- Sem thermal throttling severo no teste curto; para lote, medir modo sustentável, não apenas `burst`.

### Produto

- nenhum download duplicado de gigabytes por inicializadores idênticos;
- progresso, cancelamento e recuperação de download;
- backend e fallback explicados ao usuário;
- pacote anterior permanece recuperável até o novo ser aprovado.

## 8. Decisões guiadas pelos primeiros resultados ADB

| Resultado | Próxima ação |
|---|---|
| CPU `T` pequeno ganha muito | priorizar integração de buckets antes de quantização completa |
| GPU `T=200` passa, grande falha | localizar limite de bucket/memória; considerar mais buckets ou corte natural |
| GPU pequeno falha por op | reescrever somente o primeiro padrão incompatível e repetir paridade |
| QDQ pequeno falha em atribuição | analisar primeiro nó CPU; ajustar QDQ/reescrita antes de contexto |
| QDQ passa, contexto demora só na geração | esperado; validar load do `_ctx.onnx` |
| contexto ainda recompila | wrapper/caminho/compatibilidade incorretos; não medir inferência ainda |
| NPU quente vence, load frio perde | manter sessão/cache; medir experiência por lote e primeiro uso |
| NPU quente perde para CPU | usar profiling por grafo; identificar transferências, precisão ou bucket superdimensionado |
| LLM domina | priorizar buckets `S`, máscara e contexto com peso compartilhado |
| projector domina | revisar quantização e shape, pois o atual já mostrou preparação anormal |
| qualidade U16/U8 falha | aumentar/rebalancear calibração e usar precisão mista; não tentar U8/U8 |

## 9. Publicação no R2

O usuário autorizou download, quantização e publicação de novos artefatos no bucket `sig-android`. A autorização não elimina os gates de integridade.

### Layout recomendado

```text
models/granite/4.1-nar/experiments/<experiment-id>/...
models/granite/4.1-nar/packages/<package-id>/manifest.json
models/granite/4.1-nar/packages/<package-id>/graphs/...
models/granite/4.1-nar/packages/<package-id>/contexts/sm8850-v81/...
```

Artefatos não aprovados ficam em `experiments/` e não entram no manifesto consumido pelo app. Promoção é cópia para uma chave imutável de `packages/`, seguida de verificação HTTP, tamanho e SHA-256.

### Gate de publicação

- export reproduzível e hashes locais;
- paridade float ou QDQ aprovada;
- manifesto validado por schema;
- upload multipart retomável para arquivos grandes;
- HEAD/GET do objeto publicado;
- SHA-256 revalidado após download de amostra;
- nenhuma credencial em logs, docs ou commit;
- contexto marcado com SoC/HTP/toolchain;
- atualização do app somente depois de o pacote existir e ser recuperável.

## 10. Riscos e mitigação

| Risco | Impacto | Mitigação |
|---|---|---|
| 32 GB de RAM insuficientes para export/quantização monolítica | OOM ou paginação extrema | subgrafo por processo, external data, streaming, diretório em disco amplo; usar máquina 64/96 GB se necessário |
| VRAM local insuficiente | export lento/OOM CUDA | export por subgrafo, CPU offload; GPU local não é gate QNN |
| logits de vocabulário consomem centenas de MB | heap/disco/OOM | leitura streaming, argmax/top-k no buffer, não persistir corpus inteiro de logits |
| padding altera LLM bidirecional | texto incorreto | `attention_mask` e três gates de equivalência; fallback para variantes exatas |
| máscara não suportada pelo QNN | partição CPU | bias reescrito ou multigrafo por shapes; teste pequeno primeiro |
| quantização degrada idioma menos representado | WER desigual | calibração balanceada e métricas por idioma; precisão mista |
| contexto incompatível com runtime/firmware | falha no aparelho | manifesto completo e matriz ORT/QAIRT/SoC; manter QDQ fonte para regeneração |
| muitos buckets duplicam 4,4 GB | custo e download inviáveis | external data compartilhada, deduplicação por hash, contexto multigrafo e redução por percentis |
| `burst` produz benchmark irreal | throttling em uso longo | medir modo frio/quente e modo sustentável com cooldown/temperatura |
| GPU QNN mantém erro 6022 | atraso | trabalhar por subgrafo/bucket; GPU não bloqueia CPU/NPU |
| artefato experimental chega à UI | regressão | namespace `experiments`, manifesto imutável, feature flag e rollback |

## 11. Ordem concreta de trabalho sem telefone

1. Fechar e validar o harness ADB desta mudança.
2. Congelar revisão IBM e toolchain.
3. Transformar o prompt antigo em exportador NAR executável.
4. Exportar `T=200` e `S=64` float e provar paridade.
5. Implementar máscara e provar `S` exato versus bucket.
6. Exportar os buckets float e medir CPU no PC/emulador onde fizer sentido.
7. Montar corpus/manifesto e capturar calibração encadeada.
8. Gerar QDQ U16/U8 pequeno por subgrafo e validar no CPU.
9. Criar manifesto e empacotador; publicar apenas em `experiments/` após os gates locais.
10. Implementar registro/seleção/download no app atrás de feature flag debug.
11. Quando o telefone voltar, executar primeiro load-only estrito, depois pipeline pequeno, profiling e matriz de duração.
12. Usar os JSONL coletados para congelar buckets, escolher flags e decidir GPU/NPU/CPU por cenário.

## 12. Definição de pronto

O trabalho só estará concluído quando:

- CPU usa buckets e supera o baseline atual sem regressão;
- GPU e NPU, se apresentadas na UI, executam integralmente no backend declarado;
- NPU usa QDQ estático e contexto pré-compilado compatível;
- qualidade passa por idioma e duração;
- load, inferência, memória, temperatura e repetibilidade estão documentados;
- artefatos têm hashes, proveniência, manifesto, R2 imutável e rollback;
- testes JVM, `lintDebug` e `assembleDebug` passam;
- o aparelho real passa o protocolo ADB e o fluxo normal da UI.

Até esses gates, CPU continua sendo o backend honesto do pacote NAR atual, e GPU/NPU permanecem experimentais/desabilitadas para ele.
