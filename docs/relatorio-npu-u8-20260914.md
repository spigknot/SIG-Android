# NAR na NPU — o que destravou, medido no aparelho (13–14/09/2026)

Aparelho: OnePlus CPH2747 (SM8850 / Snapdragon 8 Elite, Hexagon v81), Android 16.
Áudio de referência: `nar-curto-7s.wav` (7,08 s → 354 frames → bucket **t0400**) e um derivado de
3,5 s (`nar-curto-3s.wav`, 175 frames → bucket **t0200**).

---

## 1. Resumo executivo

| configuração | onde | resultado |
|---|---|---|
| encoder+projector U16, LLM fp16 | **CPU** | ✅ **10.426 ms** (fp16: 45.774 ms) = **4,39×** e texto correto |
| encoder U16 + projector fp16 | NPU (aquecendo t2000) | ❌ DSP do Hexagon **crashou**; depois `QNN 6001` no projector |
| encoder U8 + projector fp16 | NPU (aquecendo t2000) | ❌ mesmo defeito, 186.220 ms |
| encoder U8 + projector U8 | NPU, aquecendo **t0400** | ❌ encoder executou **966 ms**; projector falhou (`QNN 6001`) |
| encoder U8 + projector fp16 | NPU, aquecendo **t0200** | ✅ **PASSED — inferência 2.562 ms** |
| encoder U8 **com constantes fp16** + projector U8 | NPU, aquecendo t0400 | ❌ **hipótese REFUTADA** — ver §9 |
| encoder U8 na NPU + **projector no CPU** | NPU, aquecendo **t0400** | ✅ **PASSED — inferência 4.065 ms** |

**A conclusão que importa:** a quantização **destravou a NPU** — o encoder passou de 39.028 ms
(fp16, caindo para o CPU) para **966–1.260 ms no Hexagon** (no bucket t0400). O que falta é
**espaço no DSP** para o segundo e o terceiro grafo conviverem com o encoder residente — e a
rodada de 7,1 s **já fecha com texto correto** pondo só o encoder na NPU (§10).

---

## 2. O que estava errado no diagnóstico anterior

Em 12/09 a conclusão registrada foi "a NPU perde para o CPU no fp16" (49.614 ms contra 45.774 ms).
**A medição estava certa, a conclusão estava errada**: naquele formato o encoder **não rodava na
NPU** — caía para o CPU e o tempo era o mesmo do CPU (45.046 ms ≈ 39.028 ms). O QNN aceitava a
sessão (a mensagem `Some nodes were not assigned to the preferred execution provider` era o aviso,
não o veredito) e a "medição da NPU" media o CPU. Por isso a comparação parecia um empate técnico.

---

## 3. As três causas raiz

### 3.1 O grafo QUANTIZADO do bucket maior derruba o processo do Hexagon

O `load()` do app abre sempre o bucket **T_FIXED (t2000)** na carga, para validar que encoder e
projector abrem. No QNN HTP, o grafo quantizado desse bucket derruba o DSP:

```
CDSP0:[DU]: Error 0x80000583: fastrpc_mmap_validate failed for fd -1, size 182452224
CDSP0: ################# Process on cDSP0 CRASHED!!! #################
    fa16RuntimeAllocator::deserialize
    fa16RuntimeAllocator::deserialize_pools
    fa16RuntimeAllocator::allocate_buffer      (libQnnHtpV81Skel.so)
    Bad VA: 0x0
...
[E:onnxruntime: sequential_executor.cc:671 ExecuteKernel] Non-zero status code returned while
    running QNN_4881975290327645100_8 ... Status Message: QNN graph execute error. Error code: 6001
```

Ou seja: **o `6001` que aparecia no projector era o eco de um crash do DSP que aconteceu 3 minutos
antes**, ao preparar o encoder do bucket maior. Com o encoder fp16 o mesmo t2000 abria normalmente
— porque o fp16 nunca chegava a usar o DSP.

### 3.2 O caminho de diagnóstico não existia

O smoke test não tinha como abrir um bucket de carga diferente do T_FIXED, e sem isso **não havia
experimento** capaz de separar "o grafo quantizado roda no HTP?" de "o HTP comporta o maior grafo?".
Adicionado: `GraniteNarEngine.load(..., warmupBucket: Int = 0)` (0 = produção, T_FIXED) e o extra
`--ei warmup_bucket N` no smoke test. Com `warmup_bucket=200` a rodada deixa de morrer e passa.

### 3.3 Com o encoder residente, o projector perde o mapeamento de pesos

| projector | buffers que o DSP não conseguiu mapear |
|---|---|
| fp16 de produção (152 MiB) | **52 MiB + 54 MiB** |
| U8 quantizado (76 MiB) | **26 MiB + 26 MiB** |

`fastrpc_mmap_validate failed for fd NNN` com fd **válido** (não é fd inválido: é recusa do DSP em
mapear). O tamanho falho acompanha o tamanho do arquivo de pesos, o que indica **pressão de
memória no DSP**, não um defeito do grafo. No t0200 (grafo menor) tudo cabe e o run passa.

---

## 4. A rodada que passou — números completos

`BACKEND=npu WARMUP_BUCKET=200 AUDIO=/sdcard/Download/nar-curto-3s.wav`

```
load : NPU_QNN_HTP em 27665 ms | sessao {'encoder t200': 11044, 'projector t200': 2382,
                                         'llm editor (Equilibrado)': 4053} ms | 30.1 C
inference: TOTAL 2562 ms
    frontend                          55 ms
    encoder                         1122 ms   <- NPU
    ctc encoder + cópia projector     48 ms
    projector                         18 ms   <- NPU
    montagem de embeddings            22 ms
    llm editor                      1280 ms   <- CPU
    ctc final + decode                17 ms
end  : status=passed rodadas=1 | total 31307 ms | 30.1 C
```

Texto (55 caracteres): `uma opção cada vez mais popular para aqueles que pretem…` — mesmo início da
referência, coerente com o corte de 3,5 s.

Comparação de encoder no MESMO aparelho: fp16 39.028 ms → U8 **1.122 ms** (**35×**); no CPU com U16,
3.148 ms → NPU **2,8×** mais rápido que o CPU.

---

## 5. Artefatos desta rodada

| artefato | conteúdo | verificação |
|---|---|---|
| `enc-qdq/*-fp16-qdq-quint8.onnx` | 6 encoders, ativações **uint8** + pesos uint8 | 6/6 gerados pelo pipeline |
| `pacote-u8/` | 6 grafos com nomes de produção + `encoder-pesos.data` compartilhado + manifest | 7 arquivos; dedup **7.042.390.872 → 1.178.040.452 B (16,7 %)**; os 6 grafos **verificados por sessão ORT + inferência** |
| `proj-qdq/…-t0400-qdq-uint8.onnx` | projector quantizado (37 `QuantizeLinear`) | cosseno **0,99977190**, max\|diff\| 0,343, finito |
| `scripts/monta_pacote_u8.py` | monta o pacote no formato que o app espera | guarda de 6 buckets + verificação ORT |
| `scripts/converte_constantes_fp16.py` | FLOAT→FLOAT16 nas constantes grandes (protege Q/DQ) | cosseno vs original, aborta se cair |

O `.data` do encoder U8 tem **699 MiB de constantes FLOAT32** — 312 MiB só de `rel_pos_emb`
dobrado. É herança do caminho de quantização (que passa por fp32); o modelo fp16 de **produção**
guarda os mesmos tensores em fp16. Converter corta ~350 MiB do *footprint* no DSP — é a hipótese
em teste para o t0400 caber.

---

## 6. Como reproduzir

```bash
# CPU (o ganho garantido)
bash scripts/testa_pacote_u16_no_aparelho.sh pacote-u8          # mede CPU e NPU
# NPU no bucket pequeno (a rodada que passa)
BACKEND=npu WARMUP_BUCKET=200 AUDIO=/sdcard/Download/nar-curto-3s.wav \
  bash scripts/testa_pacote_u16_no_aparelho.sh pacote-u8
# NPU no bucket 400 (o alvo a destravar)
BACKEND=npu WARMUP_BUCKET=400 bash scripts/testa_pacote_u16_no_aparelho.sh pacote-u8
```

O script confere sha256 dos dois lados (local e no aparelho), faz backup dos arquivos de produção,
restaura no fim e avisa se o app morreu no meio.

---

## 7. Estado do aparelho ao fim da rodada

- `encoder-pesos.data` = **1.085.993.664 B** (produção) ✓
- `granite-4.1-nar-encoder-t0400-fp16.onnx` e `granite-4.1-nar-projector-t0400-fp16.onnx` =
  sha256 **idêntico ao backup** ✓
- APK instalado: v1.497 (com o parâmetro `warmupBucket`; produção continua em T_FIXED)

## 8. Aberto (sem maquiagem)

- **t0400 ainda não passa** na NPU; o candidato em teste é o encolhimento do `.data` (§5).
- O **passo 6 do pipeline (validação por texto) reprova por um ponto final** — é a decisão §13
  (CER × texto exato) ainda pendente; e no t0800 ele trava porque recarrega o modelo PyTorch
  inteiro (23 min), por isso `--pular-validacao`.
- O **LLM segue no CPU** (`llm_backend_cpu`): com 1,66 GB ele não cabe no DSP ao lado do encoder.
- O gate de build: `BUILD SUCCESSFUL` — testes unitários + `lintDebug` + `assembleDebug`
  (a primeira tentativa falhou no lint por *GC thrashing* do Gradle com a máquina ocupada).

---

## 9. Hipótese REFUTADA: constantes em fp16 para encolher o `.data`

**Hipótese**: o `.data` do encoder carrega 699 MiB de constantes FLOAT32 (herança do caminho de
quantização, que passa por fp32 — o modelo fp16 de produção guarda os mesmos tensores em fp16).
Converter para fp16 cortaria ~350 MiB do *footprint* e faria o t0400 caber.

**Execução**: `converte_constantes_fp16.py` → 64 tensores, **698,4 → 349,2 MiB**, `.data` de
**1.178.045.968 → 811.896.336 B (−31 %)**, com `scale`/`zero_point` de Q/DQ preservados em float32.
Validação no PC: **cosseno 1.00000000**, `max|diff| < 5e-6` — numericamente perfeito.

**Resultado no aparelho (t0400): REPROVADO, e pior que o original.**

```
load : NPU_QNN_HTP em 47097 ms | sessao {'encoder t400': 25445, 'projector t400': 6430, ...}
E/onnxruntime: qnn_model.cc:560 ExecuteGraph] NPU crashed. SSR detected.
               Caused QNN graph execute error. Error code: 1007
erro em GraniteNarEngine.kt:1116   <- o ENCODER, nao o projector
```

| | encoder U8 (fp32 nas constantes) | encoder U8 (constantes fp16) |
|---|---|---|
| preparar t400 | **13.423 ms** | 25.445 ms (**1,9× pior**) |
| executar t400 | **966 ms** ✅ | ❌ crash do DSP (`SSR`, error **1007**) |

**Mecanismo provável**: o app liga `enable_htp_fp16_precision=1`; introduzir fp16 *nos dados* muda
o caminho de layout/conversão do HTP e o DSP passa a alocar um buffer de 32 MiB (`fd -1`) que não
mapeia. O erro mudou de lado (do projector para o encoder) — sinal de que a conversão mexeu no
orçamento de memória, só que para PIOR.

**Lição**: o `.data` menor não compensou. O que importa para o DSP não é o tamanho do arquivo, e
sim os **buffers que ele materializa** — e o caminho fp32 puro é o que o HTP lida melhor. Variante
`pacote-u8-leve` **descartada**; o pacote válido segue sendo `pacote-u8`.

**Próximo candidato** (não testado): reduzir o MAIOR buffer de ativação do encoder —
`encoder_bpe_logits` é `(T/4, 100352)` fp32 = **40 MB no t0400 e 200 MB no t2000** (o crash do
t2000 tentava alocar exatamente 174 MiB, valor compatível com esse tensor). Emiti-lo em fp16 e
converter na fronteira (host) cortaria esse buffer pela metade sem tocar nos pesos.

---

## 10. A configuração que FECHA: encoder na NPU, projector no CPU

O projector no Hexagon é ótimo (18 ms contra ~512 ms no CPU), mas é ele que não mapeia os pesos
quando o encoder está residente. Como o encoder é o ganho grande (**1.064 ms contra 3.148 ms**), a
troca vale: com o projector no CPU a rodada de **7,1 s (t0400) PASSA com texto correto**.

Ligado por `debugProjectorBackendCpu` — diagnóstico, espelho do `debugLlmBackendCpu` que já existia;
default `false`, **produção não muda**.

```
load : NPU_QNN_HTP em 28144 ms | sessao {'encoder t400': 13728, 'projector t400': 21,
                                         'llm editor (Equilibrado)': 3942} ms | 29.2 C
inference: TOTAL 4065 ms
    frontend                          93 ms
    encoder                         1064 ms   <- NPU
    ctc encoder + cópia projector     76 ms
    projector                        512 ms   <- CPU
    montagem de embeddings            39 ms
    llm editor                      2251 ms   <- CPU
    ctc final + decode                30 ms
end  : status=passed rodadas=1 | total 32894 ms | 29.2 C
```

Texto (102 caracteres): `uma opção cada vez mais popular para aqueles que pretendem ter o ano
sabático e viajar e aprenderender` — com um artefato de repetição no fim, coerente com a qualidade
do **U8** (no dump de texto do pipeline ele já ficava abaixo do U16: 3/6 contra 5/6 no t0400).

**Comparação honesta (mesmo áudio de 7,1 s, bucket t0400):**

| configuração | encoder | projector | llm | total |
|---|---|---|---|---|
| fp16, tudo CPU — LLM fp16 (12/09) | 39.028 | 361 | 6.256 | **45.774 ms** |
| U16, tudo CPU — LLM fp16 (14/09) | 3.148 | 540 | 6.446 | **10.426 ms** |
| U8, **tudo CPU** — LLM `int8b` | 2.001 | 430 | 1.922 | **4.579 ms** |
| U8, **encoder na NPU** — LLM `int8b` | **1.064** | 512 | 2.251 | **4.065 ms** |

**Como se reparte o ganho total (45.774 → 4.065 ms = 11,3×):**

- **quantização fp16→U8 (no CPU)**: encoder 39.028 → 2.001 ms — o grosso do ganho, **e é a mesma
  vitória que já estava medida no CPU**;
- **variante do LLM** (fp16 → `int8b-blk128`): 6.256 → 1.922 ms — decisão independente da NPU;
- **NPU no encoder**: 2.001 → 1.064 ms, ou seja **937 ms a menos (1,9× no encoder)** e 514 ms no
  total do pipeline (4.579 → 4.065 ms, **1,13×**).

⚠️ É essa a leitura correta: a NPU **acelera o encoder de verdade** (1,9×), mas no pipeline de 7 s
ela vale ~11% porque o LLM editor — que roda no CPU por falta de espaço no DSP — domina o tempo.

🔴 **RESSALVA MEDIDA (segunda amostra, mesmo config, 3 min depois):** `TOTAL 5.491 ms`
(encoder 1.201, projector 577, **llm 3.329 ms**). O LLM varia de **1,9 s a 4,2 s** entre rodadas
enquanto a **bateria vai de 29,2 °C a 32,8 °C** — ele é sensível à temperatura e domina o total, e
como as configurações foram testadas em sequência, **os totais NÃO são comparáveis entre si sem
cuidado**. O que é estável e comparável:

| medida | fp16 | U16/U8 no CPU | U8 na NPU |
|---|---|---|---|
| encoder (t0400) | 39.028 ms | 2.001–3.148 ms | **966–1.401 ms** ✅ |
| projector | 361 ms (CPU) | 430–540 ms (CPU) | **18–53 ms** ✅ |
| preparação do encoder | 160.551 ms | ~0,3 s | 13,4–20,1 s |

O que **não** muda com tanque quente: a NPU acelera encoder e projector com folga. O que decide o
total é o LLM no CPU — e esse é o próximo alvo real, não o resto.

Reprodução:

```bash
# a configuração que fecha (encoder na NPU, projector e LLM no CPU)
BACKEND=npu WARMUP_BUCKET=400 PROJ_CPU=1 bash scripts/testa_pacote_u16_no_aparelho.sh pacote-u8
# a base honesta de comparação (tudo CPU, mesma variante de LLM)
BACKEND=cpu WARMUP_BUCKET=400 bash scripts/testa_pacote_u16_no_aparelho.sh pacote-u8
```

---

## 11. Varredura das opções do QNN EP: existe um botão que faz tudo caber

Como o projector falhava com fp16 **e** com U8, restava a hipótese de orçamento de memória do DSP.
Em vez de rebuildar (3,5 min) por palpite, foi adicionado um injetor: `debugQnnOptions` no engine
(default `null`, produção intacta) + extra `qnn_opts` no smoke test + `QNN_OPTS` no script. Com ele,
**uma sessão de push varre vários combos** (`scripts/varre_qnn_opts.sh`, ~1,5 min por combo).

| combo (t0400, encoder E projector na NPU) | veredito | preparação |
|---|---|---|
| baseline — `htp_graph_finalization_optimization_mode=1` (o de produção) | ❌ 6001 | ~31 s |
| `offload_graph_io_quantization=0` | ❌ 6001 | 31 s |
| `enable_htp_fp16_precision=0` | ❌ 6001 | 31 s |
| **`htp_graph_finalization_optimization_mode=2`** | ✅ **PASSOU** | 111–151 s |
| `offload_graph_io_quantization=0; htp_graph_finalization_optimization_mode=2` | ❌ 6001 | 48 s |
| `htp_graph_finalization_optimization_mode=3` | ✅ passou | **~253 s** |

**A variável que decide é uma só.** Mexer numa segunda opção junto **quebra** o que o mode=2 tinha
resolvido — é o argumento mais forte para variar um fator por vez.

**O que isso prova:** o fracasso do projector no t0400 **é orçamento de memória do DSP**, e existe
modo de finalização que faz encoder e projector conviverem. Detalhe do mode=2 medido no log:
`projector t400 criado ... em 61583 ms` (contra 6,5 s no mode=1) — ele gasta preparação para
economizar memória.

**Por que não vira produção:** o custo. Preparação de **111–253 s** (contra 28 s da configuração que
fecha) e — efeito colateral medido — o **LLM no CPU passou a levar ~3,4 s** (contra 2,25 s),
consistente nas três rodadas de preparação longa (o load enorme esquenta o aparelho). Total:
5.045–5.711 ms contra **4.065 ms** da configuração com o projector no CPU.

Reprodução da varredura:

```bash
cd /d/SIG-granite-nar-lab-rebuild
bash scripts/varre_qnn_opts.sh                                          # lista padrão
bash scripts/varre_qnn_opts.sh "htp_graph_finalization_optimization_mode=2"
```

Resumo em `reports/varredura-qnn-opts.jsonl` (+ `-run1.jsonl` da primeira varredura) e o stream de
cada combo em `logs/varre-<combo>-stream.log` — **o stream do script de teste tem nome fixo por
backend e é sobrescrito**; sem copiar por combo, o texto transcrito e a evidência crua se perdem
(erro que cometi na primeira varredura).
