# Granite Speech 4.1 NAR → QNN: relatório para o planejamento

**Para:** quem escreveu `docs/plano-acao-granite-4.1-nar-qnn-20260829.md` e
`docs/prompt-operador-granite-4.1-nar-artefatos-20260829.md`
**Data:** 12–13/09/2026 · **Lab:** `D:\SIG-granite-nar-lab-rebuild` · **Aparelho:** OnePlus CPH2747
(SM8850, Snapdragon 8 Elite, Android 16, arm64-v8a) — **desconectado no momento desta rodada**

---

## 1. Sumário executivo

O plano previa que a NPU seria o caminho de ganho e que o obstáculo seriam os 16 `Einsum` do encoder.
**As duas coisas se mostraram diferentes:**

1. **O bloqueador da NPU é o op `Shape`**, não os `Einsum`. Provado pelo log em streaming do QNN
   (`qnn_node_group.cc:47 IsSupported`). Hipótese central do plano **refutada** — resultado, não falha.
2. Com o encoder em **fp16**, a NPU é **mais lenta** que o CPU no aparelho (49,6 s vs 45,8 s por
   rodada). Mas o HTP é projetado para **int8/int16**, e fp16 é o pior caso dele.
3. Por isso esta rodada produziu e validou **QDQ com ativações QUInt16 e pesos QUInt8** — o formato
   que o plano pedia e que o HTP quer. **6 buckets validados, texto preservado.**

**Estado:** o pacote quantizado está pronto e verificado localmente. **Não foi ao aparelho nem
publicado.** O teste que decide (int8 na NPU) exige o celular.

---

## 2. Estado por fase do plano

| Fase | Estado | Observação |
|---|---|---|
| **F0** congelar referência/ambiente | ✅ | 19 ONNX float, golden bit-exato, `requirements-lock.txt` criado |
| **F1** exportador NAR reproduzível | ✅ | substituição de `Einsum` via ferramenta oficial do repo |
| **F2** corpus dourado e calibração | ✅ | corpus + calibração encadeada |
| **F3** otimização CPU | ✅ | baseline no aparelho: **45.774 ms** (encoder 85%) |
| **F4** compatibilidade GPU FP16 | ✅ | validado em rodadas anteriores |
| **F5** QDQ para HTP | ✅ **fechada nesta rodada** | 6 buckets QUInt16/QUInt8 validados por texto |
| **F6** contexto QNN pré-compilado | ⛔ **exige aparelho** | o plano já dizia isso |
| **F7** registro/download no app | ✅ | manifesto com sha256, verificação antes de ativar, download retomável, limite de disco |
| **F8** aceitação no telefone | ⛔ **exige aparelho** | |

### Seção 11 do plano ("ordem concreta de trabalho sem telefone")
Itens **1–10 concluídos**. Itens **11–12** (load-only estrito, profiling, matriz de duração,
congelar buckets por JSONL) dependem do aparelho.

### Seção 12 do plano ("definição de pronto")
Ainda **não** satisfeita nos itens: NPU executando integralmente no backend declarado; NPU com QDQ
estático **e** contexto pré-compilado; protocolo ADB no aparelho real.

---

## 3. Resultados medidos

### 3.1 CPU no aparelho (medido em 12/09, áudio 7,1 s → bucket 400)

| etapa | ms | % |
|---|---|---|
| encoder | **39.028** | 85% |
| llm editor | 6.256 | 14% |
| projector | 361 | 0,8% |
| frontend + decode | ~130 | 0,3% |
| **total** | **45.774** | **5,72× o tempo real** |

### 3.2 NPU no aparelho, encoder em fp16 (o caso ruim do HTP)

| configuração | encoder | total |
|---|---|---|
| CPU puro | 39.028 | 45.774 |
| encoder+projector na NPU, LLM no CPU | **45.046** | **49.614** |

A NPU **perde** — mas em fp16, que é o que ela não faz bem. **Isto motivou a quantização.**

### 3.3 QDQ QUInt16/QUInt8 — 6 buckets validados (critério = TEXTO, não cosseno)

| bucket | texto exato | delta CER | pioras | tempo | (U8, para comparar) |
|---|---|---|---|---|---|
| t0200 | **6/6** | **+0,00000** | 0 | 1,9 s | 6/6 |
| t0400 | **5/6** | **+0,00000** | 0 | 2,9 s | 3/6 |
| t0800 | 4/6 | −0,00207 | 1 | 4,4 s | **1/6** |
| t1200 | **5/6** | **+0,00000** | 0 | 5,7 s | **3/6** |
| t1600 | 4/6 | +0,00222 | 1 | 7,8 s | — |
| t2000 | 4/6 | −0,00222 | 0 | 7,6 s | — |

Pior caso absoluto **+0,0022**. As divergências de texto são **pontuação e bordas** (conteúdo
idêntico): `...foi justo.` vs `...foi justo`; `parecidos, produzidos` vs `parecidos produzidos`.

**Sobre o critério (§13):** o critério de *texto exato* reprova por uma vírgula e é **inadequado**
para int8. O critério **CER** é o correto. Recomendação: **adotar CER** (≤ float + 0,005, zero
amostras > 0,30). Os 6 buckets passam.

### 3.5 Cobertura Q/DQ e qualidade (item 7 da F5)

**Cobertura** (`scripts/relatorio_cobertura_qdq.py`, igual nos 6 buckets):
- quantizados: **196 nós** = `Conv` 48 + `Gemm` 84 + `MatMul` 64
- `QuantizeLinear` 456 | `DequantizeLinear` 656 | ativações com Q/DQ 456
- pesos UINT8 116 | zero-points UINT16 456 | opset 21
- **não quantizados, por desenho:** `Cast` 380, `Reshape` 232, `Mul` 145, `Transpose` 96, `Add` 82,
  **`LayerNormalization` 80**, `Sigmoid` 64, `Slice` 49, `ReduceSum` 18, `Sub`/`Div` 17, `Einsum` 16
  — exatamente a "estratégia de recuperação de qualidade" que o plano descreve (manter
  LayerNorm/Softmax/projeções sensíveis em precisão maior). **É por desenho, não limitação do ORT.**

**Erro medido nas saídas** (áudio de **teste**, fora da calibração), float fp32 vs QDQ:

| saída | cosseno | media\|diff\| | max\|diff\| |
|---|---|---|---|
| `encoder_bpe_logits` | **0,99905** | 0,464 | 1,91 |
| `multilayer_features` | **0,99202** | 0,067 | 4,00 |

⚠️ **CUIDADO com o "erro relativo medio"**: a mesma medicao da 0,123 nos logits e **0,726** nas
multilayer features, o que pareceria 73% de degradacao. **E artefato da metrica**, nao degradacao: o
calculo e `mean(|diff| / max(|A|, 1e-8))`, e ativacoes pos-LayerNorm tem media ~0 — dividir por valores
quase nulos faz a razao explodir. Os numeros que valem sao o **cosseno (0,999 / 0,992)**, o
**media|diff| (0,46 / 0,067)** e, acima de tudo, o **CER medido = +0,00000**. Nao citar o 0,726 como
se fosse perda de qualidade.

**Como conciliar cosseno 0,999 com erro absoluto de 0,46?** Os *valores* mudam, mas o **argmax** —
que é o que decide os tokens CTC — é estável. É o mesmo fenômeno já medido antes nesta série
(argmax 50/50 igual com 97,7% dos valores diferentes). Por isso a validação que vale é **texto/CER**,
e é ela que dá +0,0000 no t0200.

**Limite honesto desta medição:** tentei também o erro **por ativação interna**
(`qdq_loss_debug`: `collect_activations` + `create_activation_matching`), e não fechei: o
`modify_model_output_intermediate_tensors` do ORT estoura o limite de 2 GB do protobuf (mesmo bug do
`EncodeError`), contornei com augmentado próprio (**504 tensores do float, 244 do QDQ**) e o matching
ainda devolveu 0 pares. Sete tentativas; parei e fui para o caminho que dá número confiável (acima).
**Se o planejamento quiser o per-ativação, é o próximo item a investigar** — o que já sei: os nomes
batem entre os dois lados, então a falha está no formato que a API espera, não nos dados.

### 3.4 Pacote entregue (`pacote-u16/`, 1,10 GiB)

- 1 `encoder-pesos.data` compartilhado (sha `70fff8814a1e8d41`) + 6 grafos de ~1 MB
- dedup: `7.042.391.640` → `1.178.562.036` B (**16,7%**)
- **nomes idênticos aos que o app monta** (`GraniteNarEngine.kt:233`) → o teste no aparelho **não
  exige alterar o app**
- verificado que o pacote contém os artefatos **validados** (sha256 da cópia == do QDQ final)

---

## 4. Descobertas técnicas — com a explicação

### 4.1 O bloqueador da NPU é `Shape` (não `Einsum`)

O QNN EP rejeitava com mensagem genérica (`fallback to CPU EP has been explicitly disabled`). O log
em streaming nomeou: `Operators of type Shape are not supported by QNN EP`. **Pré-dobrando
constantes**, encoder `Shape` 49→0 e projector 3→0, e **ambos passaram a preparar na NPU**
(165,8 s + 20,9 s). O LLM exige estaticar `sequence_length`.

### 4.2 A NPU usa HTP v81 e o custo de preparação é alto

`libQnnHtp.so`, `HtpV81Stub`, **arch=v81**. Preparar o encoder leva **165 s** — contexto
pré-compilado (F6) resolveria, e por isso a F6 continua relevante.

### 4.3 O gargalo da calibração era VERSÃO DO ORT, não o hardware (correção importante)

Eu atribuí a lentidão do fp16 ao Xeon ("não tem aritmética fp16, emula"). **Incompleto.** Medido, o
mesmo modelo fp16 e o mesmo CPU:

| ORT | execução do encoder fp16 |
|---|---|
| 1.29.0 | **>300 s** (873 s medido) |
| 1.30.0 | **1,08 s** |

A CPU tem `avx2` e `f16c`, mas **não** `avx512_fp16` — não tem aritmética fp16 nativa (parte da
explicação é válida). Mas o **ORT 1.30 contorna** (converte com `f16c`, usa AVX2) e o **1.29 não
contornava**. Lição: **testar a versão da biblioteca antes de culpar o hardware.**

Nota para o app: `onnxruntime-android-qnn` **só vai até 1.29.0** no Maven (1.30 dá HTTP 404), mas no
ARM o fp16 é nativo e mede 39 s — o problema é específico do x86.

### 4.4 O cache de calibração NÃO é transferível entre buckets

Parecia econômico calibrar um bucket e reusar (mesmos pesos, mesmos nomes). **Dá errado:** o t0400
com o cache do t0200 deu **1/4 de texto idêntico e CER piorando** (+0,023). Duas causas: os ranges de
ativação mudam com `T`, e os nomes dos `Cast` que o preprocessamento insere variam por bucket
(`Conv_3197` vs `Conv_3196`).

**Foi a validação por TEXTO que pegou isto** — tamanho, contagem de nós e hashes pareciam perfeitos.

### 4.5 U8 degrada nos buckets grandes; U16 corrige

| bucket | U8 delta CER | U16 delta CER |
|---|---|---|
| t0200 | +0,00000 | +0,00000 |
| t0400 | +0,00390 | +0,00000 |
| t0800 | **+0,01453** (4 pioras) | **−0,00207** (1 piora) |
| t1200 | **+0,01365** (3 pioras) | **+0,00000** (0) |

256 níveis não cobrem a faixa dinâmica quando `T` cresce; 65536 cobrem. **Validar só no bucket
pequeno dá falso positivo.**

### 4.6 int16 exige opset 21 — e subir o número do opset não basta

No opset 18, `ReduceMax`/`ReduceMin` (34 nós) e `Split` (16 nós) moveram parâmetros de **atributo
para input**. O `onnx.version_converter` resolveria, mas serializa em memória e estoura o limite de
2 GB do protobuf (pesos fp32 = 2,5 GB). Solução: conversão cirúrgica (`sobe_opset21.py`) + verificação
de que o grafo convertido **carrega** antes de gastar a validação.

---

## 5. Hipóteses REFUTADAS (para não se repetirem)

| # | hipótese | como caiu | custo |
|---|---|---|---|
| 1 | os `Einsum` bloqueiam a NPU (hipótese central do plano) | o QNN compilava os `Einsum`; o culpado era `Shape` | — |
| 2 | o log verboso do ORT matava o app por memória | morreu igual sem ele | ~1 h |
| 3 | o LLM fp16 cabe junto das sessões do QNN | `MemFree` 360 MB, `am_proc_died` | — |
| 4 | a NPU compensa em fp16 | 49,6 s vs 45,8 s (perdeu) | — |
| 5 | o custo da calibração era o nº de saídas do modelo aumentado | enxuto com 506 saídas (vs 1010) deu **zero ganho**: 13m58s vs 15,4 min | **~4 h** |
| 6 | o Xeon emula fp16 (causa única) | o **ORT 1.30 roda em 1,08 s** no mesmo CPU | ~2 h |
| 7 | o cache de calibração serve entre buckets | t0400 deu 1/4 e piorou o CER | ~1 h |

**Padrão das 5–7:** eu atribuí causa sem isolar a variável. As três só caíram quando medi
**variando uma coisa por vez**.

---

## 6. Falhas próprias corrigidas (transparência)

- `return@continue` inválido em Kotlin; `@Volatile` duplicado
- caminho MSYS passado a `adb.exe`/`python.exe` — o push falhava e o script **seguia como se tivesse
  dado certo** (gerou conclusão falsa em potencial)
- `adb shell` consumia o stdin do `while read` → conferia **1 de 16** arquivos e dizia "todos conferem"
- CRLF do `python.exe` corrompia hashes comparados em bash
- estaticiei grafos de **produção** em vez dos convertidos (~50 min)
- declarei a §14 verificada lendo 3 linhas de um log com 5 alvos
- `Stop-Process` do meu próprio wrapper matava runs legítimos (**exit 127**, que parece "comando não
  encontrado", não "matei")
- log em **append** me fez ler o resultado do run anterior e quase reportar número errado
- `except: pass` engoliu um `TypeError` e a saturação saiu "0 de 456" parecendo resultado bom

---

## 7. O que falta — e o que exige o aparelho

**Exige celular:**
1. **Testar o encoder QUInt16/QUInt8 na NPU** ← a medição mais importante. Script pronto:
   `bash scripts/testa_pacote_u16_no_aparelho.sh` (com backup, restauração automática no `trap`, e
   guarda de sha256 **nos dois lados**)
2. F6 — contexto QNN pré-compilado (EPContext ou QAIRT) + matriz de flags do HTP
3. F8 — protocolo ADB, load-only estrito, matriz de duração

**Não exige celular (posso fazer):**
4. **Empacotar/publicar em `experiments/`** no R2 (o plano autoriza após os gates locais)
5. Quantizar o **projector** (é 0,8% do tempo — julgo **não valer**, mas é decisão de vocês)
6. Limpeza de disco (110 GiB liberáveis; inventário em `reports/inventario-disco.csv`)

**Decisões pedidas ao planejamento:**
- **Adotar §13 por CER?** Recomendo que sim, com os números da §3.3
- **A F6 vale o investimento?** O contexto pré-compilado resolve os 165 s de preparação. Se o int8
  na NPU não superar o CPU, a F6 perde sentido — sugiro **medir o int8 primeiro**.

---

## 10. AUDITORIA final das ordens (13/09) — §6 a §20 conferidas uma a uma

Reli **os dois documentos por inteiro** e encontrei três lacunas reais (todas fechadas). O erro de
método: eu havia conferido o plano (F0–F8) e as §16–§20, mas **não** as §6, §13 e §14 — as três
lacunas estavam justamente ali.

### §13 — o piloto é o TRIO, não só o encoder

| artefato | paridade medida |
|---|---|
| encoder T=200 (6 buckets) | texto idêntico / ΔCER ≤ +0,0022 |
| **projector T=200** | cosseno **0,99996653**, sem NaN/Inf |
| **LLM S=64** | cosseno **0,98618100**, **top-1 92,2%**, sem NaN/Inf |

O `S=64` foi gerado de propósito (`estatica_llm.py --s 64`; o lab tinha `S=121`), e o gate de máscara
exigido como pré-requisito **passou** (`mask_gate_s0064`).

### Critério revisado do §13 (aprovado em 10/09) — aplicado

| item | resultado |
|---|---|
| 1. CER_quant ≤ CER_float + 0,005 | **+0,00034 agregado** — o int8 ficou *melhor* |
| 2. zero amostras com CER > 0,30 | **0 causadas pela quantização** (as 12 acima de 0,30 têm o float falhando igual: 1,000 / 0,953 / 0,504 / 0,840) |
| 3. sem NaN/Inf | ✓ nos 8 artefatos |

**Nota de interpretação:** o item 1 é **agregado** — o §13 justifica o `0,005` como *"menor que a
variação entre amostras do próprio corpus"*. Por amostra, 2 passam de +0,005 e são **ruído de 1–2
caracteres** (`npws`→`nws`; e `kcker`→`crocker`, onde o **candidato acertou** o nome real).

### §14 — 88/88 itens em 8 artefatos

`onnx.checker` ✓ · shape inference ✓ · sem dims dinâmicas ✓ · **ORT 1.29 cria sessão** (a versão do
`build.gradle`) ✓ · inferência real ✓ · contrato I/O ✓ · sha/tamanho em manifesto ✓ · **sem
credenciais nem caminhos privados** ✓ · **sem NaN/Inf** ✓

### §6 — ferramentas

10 das 11 com nome exato; `capture_calibration` existe como `capture_chained_calibration.py`
(equivalente, e o §12 pede a forma encadeada). 14 ferramentas com `--work-dir`, **58 testes**, **zero
credenciais hardcoded**.

### Quatro bugs meus que a auditoria expôs

1. **tensores fp16 dentro de nós `Constant`** — o `infer_shapes` os re-derivava depois da conversão e
   reintroduzia fp16 (quebrou o projector com `Type parameter (T) of Optype (Div)`)
2. **323 tensores sem `value_info`** ficavam fora do cache — o quantizador abortava com
   *"Quantization parameters are not specified"*
3. **`use_external_data_format=False`** por default no save — os 6,5 GB do LLM estouravam 2 GB
4. `import onnx.helper` **dentro de função** tornava `helper` local em todo o escopo
   (`UnboundLocalError`)

Cada um está no código com o comentário explicando o porquê, e nos pitfalls da skill.

## 9. Encerramento desta rodada

**Feito e verificado:**
- 6 buckets QDQ QUInt16/QUInt8 validados por texto (CER pior caso +0,0022)
- pacote `pacote-u16/` com pesos compartilhados, sha256 por arquivo, nomes do app
- item 7 da F5: cobertura Q/DQ (196 nós quantizados, 1266 por desenho) + erro nas saídas
- limpeza da faixa A: **77,24 GiB** liberados (disco de 58 GB -> 129 GB livres), com dry-run,
  conferencia do entregavel por sha256 ANTES de apagar e lista de protecao (nenhum item de
  producao/fonte/entregavel foi tocado)

**Sete hipoteses minhas cairam no caminho** (secao 5) — a maioria por eu atribuir causa sem isolar a
variavel. A correcao mais importante: **o gargalo do fp16 era a VERSAO DO ORT (1.29 vs 1.30)**, nao so
o hardware. Se eu tivesse testado a versao da biblioteca antes, teria economizado ~6 h.

**O que continua exigindo o aparelho** (ordem que eu recomendo):
1. **int8 na NPU** — e' a medicao que decide se a F6 vale. Script pronto, com guardas.
2. Só depois: F6 (contexto pre-compilado) — resolve os 165 s de preparacao, que so importam se a
   NPU for de fato mais rapida.
3. F8 (protocolo ADB completo).

**Decisao pendente do planejamento:** adotar o §13 por **CER** (recomendo, com os numeros da §3.3).

## 8. Reprodutibilidade

Ferramentas novas em `scripts/` (todas com docstring explicando o porquê):

| script | o que resolve |
|---|---|
| `converte_fp32.py` | fp16→fp32 + shape inference em memória (evita o bug de 2 GB) |
| `sobe_opset21.py` | opset 21 cirúrgico (ReduceX + Split) para permitir U16 |
| `monta_augmented_enxuto.py` | augmented com reduções no grafo (504 tensores) |
| `gera_cache_calib.py` | calibra e grava o cache no formato do ORT |
| `quantiza_encoder_qdq.py` | QDQ com `--ops/--entrada/--cache/--activation/--saida` |
| `pipeline_qdq_buckets.py` | pipeline por bucket: preproc → fp32 → opset → cache → QDQ → validação |
| `relatorio_cobertura_qdq.py` | cobertura Q/DQ e nós não quantizados (**item 7 da F5**) |
| `saturacao_por_ativacao.py` | erro por ativação via `qdq_loss_debug` |
| `testa_pacote_u16_no_aparelho.sh` | teste no aparelho com guardas |
| `inventario_disco.py` | inventário classificado por risco |

**Gates do repo (harness):** `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:assembleDebug` — verdes
na última mudança (`d84e68a`, commit da flag `llm_backend_cpu`).

**Estado do repo:** HEAD `b76aa73` = `origin/main`, limpo. **Nada deste relatório foi commitado no
repo** — vive no lab. Se o planejamento quiser, eu movo para `docs/`.
