# RELATÓRIO FASE 00 — Rebaseline e reserva de escopo

**Ordem:** `PROMPTS-20261029/FASE-00-REBASELINE.md`, revisão **20261029.1**
**Tentativa:** F00-20261104-01
**Início:** 2026-11-04T14:52-03:00 · **Fim:** 2026-11-04T15:26-03:00 (America/Sao_Paulo)
**Executor:** agente Hermes (executor) — sem autonomia para aprovar rota
**Estado proposto:** `concluida_para_revisao`
**Aprovação:** pendente de parecer do especialista

## 1. Objetivo recebido e resultado concreto

Fixar a situação real da campanha sem benchmark, sem correção de fonte e sem substituir artefato.
O inventário foi concluído: repositório em `3709d3a` (4 commits após o snapshot v3), **6 de 44**
arquivos do snapshot divergentes — nenhum fonte Granite de produção alterado —, pacote nativo
real **v15**, ORT **1.29.0** (AAR e artefato nativo), QAIRT v1 **não instalado** no aparelho de
teste, pacote NAR **instalado com integridade verificada** (6/6 hashes) mas **sem a variante
padrão fp16**, e um segundo aparelho (PJA110) **ocupado pela campanha Whisper**. Nenhuma rota
possui prova de execução; o próprio manifesto publicado declara `backend_aprovado = null` e
`phone_validation = false`.

## 2. Base e preservação

| Item | Valor |
|---|---|
| Branch | `main` |
| HEAD inicial = final | `3709d3a4b841f881bacf7bbc454c3ef2a8762f29` |
| HEAD do snapshot v3 | `254b4a2b1e671f36c429259396eaf662b55ac4be` |
| Diff próprio | **nenhum** (nenhuma fonte rastreada escrita nesta fase) |
| Alterações de terceiros preservadas | 10 arquivos modificados no working tree (Whisper/ORT + `MODULE-MAP.md`), 1098 não rastreados |
| Pasta das evidências | `docs/especialista/granite/` (arquivos `F00-*`, conforme instrução do usuário) |
| Versionamento dos entregáveis | **atenção:** `.gitignore:55` ignora `*.csv`; os três CSVs desta fase existem em disco e **não entrariam em commit** sem exceção explícita (G-13) |

Commits que avançaram o HEAD desde o snapshot, todos de outra campanha:

```
b9feb6d Detecta modelo Granite NAR no servidor
aac84d0 Servidor STT local: requisicoes por motor (Granite/Nemotron) e por aba
c2b79ca Versao 20261031_001: Whisper ORT e atualizacoes STT
3709d3a Versao 20261101_001: ORT CPU e Whisper por tentativa
```

## 3. Cobertura da ordem

| Tarefa | Prioridade | Estado | Evidência verificável | Limitação e impacto |
|---|---|---|---|---|
| F00.01 Fotografia do repositório | P0 | feita | `F00-evidencias.txt` E01/E02; `F00-inventory.json` → `repository` | HEAD 4 commits à frente do snapshot; nada revertido |
| F00.02 Modelos e caches | P0 | feita | E05; `F00-inventory.json` → `models` | Variante fp16 ausente; Turbo incompleto |
| F00.03 Runtimes | P0 | feita | E03; `F00-runtime-matrix.csv` | Identidade carregada em runtime não observada |
| F00.04 Capacidades e escopo | P0 | feita | `F00-capability-map.csv` | Comprovação funcional é F02+; nada aprovado |
| F00.05 Dispositivo e recursos | P0 | feita | E04; `F00-inventory.json` → `devices` | PJA110 ocupado; OnePlus 15 com 28% de bateria |
| F00.06 Contratos e lacunas | P0 | feita | E06; `F00-lacunas.md` | Sem prova de partição/ACK; testes não executados |
| F00.07 Histórico delimitado | P1 | feita | Seção 8 deste relatório | Histórico não reconstruído (deliberado) |
| F00.08 Base proposta | P0 | feita | Seção 9; `F00-inventory.json` | Proposta; aprovação é do especialista |

### 3.1 Owners de código, artefatos e processos

| Objeto | Owner | Evidência | Efeito nesta campanha |
|---|---|---|---|
| Fontes Granite de produção (`GraniteActivity`, `GraniteEngine`, `GraniteNarEngine`, `GraniteNarManifest`, `GraniteBinarySupport`, `GraniteNarLlmSettings`, `GraniteParallelismSettings`, `LocalGraniteModel`) | **campanha Granite** (este programa) | `MODULE-MAP.md`; `AGENTS.md` | Não alterados em F00; qualquer mudança exige o gate completo do AGENTS.md |
| Pipeline live compartilhado (`LivePcmPipeline`, `LiveVadSegmenter`, `LiveTextCommitter`, `LiveTextPublication`, `LiveTerminalPolicy`, `LiveStopRequest`, `LiveJobSupport`, `LiveEventIdentity`) | **campanha Whisper** (em uso ativo) | diagnóstico v3 §1; `LiveTextCommitter` mudou em commit pós-snapshot | Reaproveitamento só com contrato e regressão (D35); não editar por conta do Granite |
| Libs nativas e pacote v15 | **fluxo de release SIG** (`NativeDependencyManager` → R2) | `COMPONENT_VERSION = 15`; ZIPs publicados e conferidos | Consumo compartilhado (Whisper, Granite, llama, FFmpeg); mudar exige verificar todos os consumidores (D33) |
| ORT Java (AAR) e ORT nativo | **campanha Whisper/ORT** (`docs/especialista/ort`) | `app/build.gradle:443`; `loadOrtRuntime` novo no manager | Granite é consumidor; não alterar versão/carregador isoladamente |
| QAIRT | **fluxo `tools/qairt` + `QairtDependencyManager`** | pacote v1, 55,7 MB | Não instalado; instalação é passo de F06/F07 com autorização |
| Modelo NAR publicado (R2) | **pipeline `tools/granite`** (export/publicação) | manifesto `granite-nar-v2` | Fonte de identidade; não republicar nesta campanha |
| `MODULE-MAP.md` | **harness** (`scripts/check-module-map.ps1`, etapa `module-map-consistency`) | `AGENTS.md` | Modificado por terceiro; não commitar junto (G-11) |
| CPH2747 (OnePlus 15) | **usuário** (alvo de testes do projeto) | instrução direta de 04/11 | Reserva proposta nesta fase |
| PJA110 | **campanha Whisper lab** | `whisperlab`/`.test` ativos | Fora desta campanha enquanto houver job Whisper |
| `docs/especialista/whisper/` | **campanha Whisper** | 649 entradas não rastreadas | Não tocar; leitura apenas |

## 4. Configurações e artefatos

### 4.1 Pacote nativo e ORT

| Item | Valor observado | Como foi verificado |
|---|---|---|
| `COMPONENT_VERSION` | **15** (era 14 no snapshot) | `NativeDependencyManager.kt:23`; diff `254b4a2..HEAD` |
| ZIP arm64 | 49.748.422 B · `b8f05f4a…b26a` | `sha256sum` local **igual** à constante do código |
| ZIP x86_64 | 55.979.740 B · `589aff93…3eab` | idem |
| Delta v14→v15 | apenas `libsig_whisper.so` (+2.512 B arm64, +2.536 B x86_64) | diff do manager |
| ORT Java | `onnxruntime-android-qnn:1.29.0` | `app/build.gradle:443` |
| ORT nativo | 23.990.744 B · símbolo `VERS_1.29.0` | `grep` no `.so` extraído do ZIP v15 |
| Exclusões do APK | `**/libonnxruntime.so`, `**/libonnxruntime4j_jni.so` | `app/build.gradle:204-206` |
| Ordem de carga | `libonnxruntime.so` → `libonnxruntime4j_jni.so` | `loadOnnxRuntimeNatives` / `loadOrtRuntime` |
| QAIRT | pacote **1**, 55.682.231 B, `df879cd7…a9f0`, 12 libs `Qnn*` (skels 73/75/79/81) | `QairtDependencyManager.kt:36-60` |
| QAIRT no CPH2747 | **não instalado** | `ls no_backup/qairt` → ausente |

> **Correção de base:** o plano/estado v3 descreve pacote **v14**; o código atual publica **v15**.
> A diferença é funcionalmente cosmética para o Granite (só o Whisper mudou), mas a identidade
> correta é v15. Nada foi republicado nem revertido.

### 4.2 Modelos

Manifesto publicado (`models/granite/4.1-nar/v2/manifest.json`, cópia em cache no aparelho com
hash do próprio texto conferido — `51318838…c166a`):

| Campo | Valor |
|---|---|
| Pacote | `granite-nar-v2`, criado em 2026-09-11 |
| Arquivos / bytes | **25** / **7.431.915.532** |
| Buckets | 200, 400, 800, 1200, 1600, 2000 |
| Variantes | fp16, int8b-blk128, int4b-blk128 |
| `validation_level` | `context-ready` |
| `phone_validation` | **false** |
| `backend_aprovado` | **null** |
| `backend_pretendido` | `cpu` |
| Limitações declaradas | sem validação Adreno/HTP/contexto QNN; nada executado em aparelho; `S` dinâmico |

No **OnePlus 15 (CPH2747)**, diretório `granite_nar_models`:

- 24 entradas, **4.166.291.039 B** (manifesto completo seriam 7,43 GB);
- instaladas as variantes `int8b-blk128` e `int4b-blk128`; preferência salva = `int8b-blk128`;
- **faltam** `granite-4.1-nar-llm-fp16.onnx` (2.149.128) e `.data` (3.263.500.288) — a variante
  **padrão** do código (`GraniteNarLlm.PADRAO = FLOAT`);
- **6/6** arquivos amostrados com SHA-256 **igual** ao manifesto (encoder t0200, projector t0200,
  mel filters, stft window, vocab, preprocessor config).

`granite_models` (5.0 Turbo) está **incompleto**: grafo de 865.408 B + `.data.download` parcial de
1.327.104 B. Cópias locais de exportação existem em `tools/granite/out|package` e
`D:/Projetos/granite-export/package` (F32 até 1,89 GB), mas não foram validadas nesta fase.

### 4.3 Aparelhos

| | **CPH2747 — OnePlus 15 (alvo)** | **PJA110 (fora)** |
|---|---|---|
| Serial | `100.108.27.64:5555` | `100.114.88.45:5555` |
| SoC / Android | SM8850 / 16 (API 36) | SM8550 / 13 |
| RAM total / disponível | 15.473.704 kB / 4.762.668 kB | 23.731.972 kB / 2.923.192 kB |
| Disco livre | 510 GB de 932 GB | 519 GB de 934 GB |
| Bateria | 28% · 31,7 °C | 100% · 21,1 °C |
| App SIG | 1.516 (79), atualizado 02/11 | instalado; **não rodando** |
| Pacote nativo | **15** | **14** |
| Modelos NAR | 4,17 GB (int8b/int4b) | **ausentes** |
| QAIRT | ausente | ausente |
| Owner | **teste do projeto (designado pelo usuário)** | **campanha Whisper lab** (`whisperlab` + `.test` ativos, 20,3/23,2 GB usados) |

Nenhuma instalação, limpeza de log, force-stop ou microfone foi executado. Leituras via
`adb shell ls/cat/getprop/df/sha256sum` e `run-as` (APK debuggable).

## 5. Capacidades: solicitado × exposto × implementado × comprovado

| Rota | Solicitado (plano v3) | Exposto na UI/enum | Implementado no código | Comprovado nesta fase |
|---|---|---|---|---|
| CPU | sim (baseline/oráculo) | sim (padrão) | sim (NAR e Turbo) | **não** |
| Vulkan | sim (F06) | não | **não** | **não** |
| NPU/HTP | sim (F07) | sim (`NPU_QNN_HTP`), **desabilitado no NAR** | parcial: Turbo QNN HTP; NAR com política de bucket T200/T400 | **não** |
| Smart (encoder HTP → editor Vulkan) | sim (F08) | não | **não** | **não** |
| GPU_QNN (legado) | não promovido | sim (`GPU (Adreno)`) | Turbo apenas; NAR bloqueado | **não** |
| OpenCL | **suspenso (F05)** | não | **não no Granite**; loader nativo existe para whisper/llama | n/a |

Detalhes que importam para o parecer:

1. `GraniteActivity.selectModel()` força CPU quando o modelo é NAR e o backend é acelerado;
   `showBackendMenu()` desabilita as opções aceleradas com o rótulo “indisponível no NAR”.
2. `GraniteExecutionBackend` tem exatamente **três** valores (CPU, GPU_QNN, NPU_QNN_HTP). Não
   existem Vulkan, OpenCL ou Smart no enum — nenhuma rota foi “escondida” ou “removida”.
3. `GraniteNarBuckets.LIBERADOS_NO_DSP = {200, 400}` e `politicaBucket()` recusam bucket maior
   com motivo explícito (crash do DSP em t2000, medido em 14/09).
4. **OpenCL:** zero ocorrências em `Granite*.kt`, `LocalGraniteModel.kt` e `app/build.gradle`.
   O que existe é `app/src/main/cpp/android-opencl-loader.cpp`, o alvo `ggml-opencl` do whisper e
   `llama-jni`, e `<uses-native-library android:name="libOpenCL.so" required="false"/>`. Portanto
   **a suspensão já é efetiva no Granite**; a alteração mínima necessária é **nenhuma** — só
   política/documentação. Apagar o loader compartilhado seria regressão para Whisper/llama (D27).
5. `GPU_QNN` tem como consumidores apenas `GraniteEngine.kt` e `GraniteBackendMappingTest.kt`.

## 6. Contratos e riscos (F00.06)

| Contrato | Valor no código | Observação |
|---|---|---|
| Front-end | N_FFT 512, HOP 160, N_MELS 80, N_FREQS 257, STACK 2, INPUT_DIM 160, FLOOR_DB 8 | sem deltas/AGC; `T_FIXED = 2000` |
| Janela | `MAX_CHUNK_SECONDS = 2000*2*160/16000 = 40 s`; overlap 2 s; junção até 32 palavras | confirmado também dentro de `a2b4d58` |
| Buckets | 200/400/800/1200/1600/2000; escolhe o menor que caiba | CPU aceita todos; DSP só 200/400 |
| CTC | `BLANK = 100257`; colapso greedy lendo `FloatBuffer` | evita ~200 MB de logits no heap |
| Interleave | `[blank, tok0, blank, tok1, …]`, mínimo 8 | parte pura testável |
| Editor | escala de logits 8, multiplicador de embedding 12, `audio_embeds/12` | `S` dinâmico |
| Parser | `vocab.json` com 100.352 peças, byte-level | parse fora do Android |
| Sessões | encoder/projector fecham na troca de bucket; `release()` fecha as três | ~1 GB por encoder residente |
| ORT global | `OrtEnvironment.getEnvironment()` compartilhado com Whisper | risco D33 |
| Cancelamento | flag `cancelRequested` checada **entre trechos**; sem ACK | cancelar não interrompe inferência em curso |
| Testes JVM | 94 `@Test` em 5 arquivos (9/13/20/8/44) | nenhum teste instrumentado específico de Granite |

## 7. Fatos, inferências e hipóteses

**Observado (com evidência):** HEAD/branch/status; hashes dos 44 arquivos do snapshot; v15 e seus
hashes; ORT 1.29.0 no AAR e no `.so`; QAIRT ausente no CPH2747; manifesto e pacote instalado com
6/6 hashes iguais; variante fp16 ausente; Turbo parcial; PJA110 com processos Whisper ativos;
94 testes JVM existentes; ausência de OpenCL no Granite.

**Inferência (declarada como tal):** (a) como o pacote instalado só tem int8b/int4b, um usuário
que nunca escolheu variante cai em fp16 e precisaria baixar 3,26 GB — inferido do código
(`PADRAO = FLOAT` + `packageComplete(variante)`); (b) a troca v14→v15 não deve alterar o
comportamento do Granite porque só `libsig_whisper.so` mudou — inferido do diff de tamanhos e do
manifesto de arquivos, **não executado**; (c) o pico de memória do NAR em CPU tende a crescer com
o upcast FP16→FP32 — inferido da arquitetura, não medido.

**Hipótese (não verificada):** que o ORT carregado no processo seja exatamente o `.so` do pacote
v15 (G-01). Que o HTP do SM8850 aceite os buckets liberados (G-02). Que o cancelamento entre
trechos seja suficiente para a experiência desejada (G-06).

**Métricas:** nenhuma métrica de inferência foi produzida nesta fase — por desenho. Os campos de
métrica do `F00-report.json` estão vazios com motivo, não com zeros artificiais.

## 8. Histórico delimitado (F00.07, P1)

| Conclusão antiga | Reutilizável? | Por quê |
|---|---|---|
| Chunking 40 s / 2 s / junção lexical | **sim, como implementação** | Está em `a2b4d58`; aceitação funcional continua sendo F03 |
| Tabela bucket 200 × 2000 (10/09) e crash t2000 no DSP (14/09) | **sim, como histórico** | Sustenta a política `LIBERADOS_NO_DSP`; sem log bruto no repositório (G-12) |
| Tabela de variantes fp16/int8/int4 (11/09) | **sim, como histórico** | Está em KDoc; não foi remedida nesta campanha |
| Correção do parser de manifesto (14/09) | **sim** | Código + teste de regressão com formato real |
| Contrato dos sub-grafos (28/08) | **sim** | Conferido contra as constantes do código em F00.06 |
| Delta de memória/tempo, “warm real”, conclusões OpenCL/Vulkan antigas, transferência Hy-MT2 | **não** | Exigem G01 (D01/D05/D09/D14); D27 já suspendeu OpenCL |

Contradições registradas: (i) `ESTADO.md` e plano v3 falam em HEAD `254b4a2` e pacote **v14** —
hoje é `3709d3a` e **v15**; (ii) o plano v3 dizia “patch de chunks pendente”, já corrigido por D29
(o patch está commitado); (iii) não há logs brutos das medições de 09–14/09 nesta pasta.

## 9. Base proposta (F00.08)

**Verificado (pode ser citado como fato desta base):** HEAD/branch/status; 38/44 hashes iguais ao
snapshot; ausência de alteração Granite no working tree; v15 + hashes dos ZIPs; ORT 1.29.0 no AAR
e no artefato; QAIRT ausente; manifesto publicado (25 arquivos, 7,43 GB, `backend_aprovado=null`);
pacote instalado 4,17 GB com 6/6 hashes iguais; variante fp16 ausente; Turbo incompleto; PJA110
ocupado pela campanha Whisper; OnePlus 15 designado como alvo de teste.

**Pendente (não pode ser citado como prova):** qualquer inferência; identidade ORT em runtime;
execução em CPU/Vulkan/HTP; partição por estágio; cancel/ACK; memória/térmica; 60 s; live.

**Política de reserva proposta:** CPH2747 é o **único** alvo de teste desta campanha, por decisão
do usuário (`100.108.27.64:5555`). PJA110 fica **reservado à campanha Whisper** enquanto houver
`whisperlab` ativo; nenhum job Granite deve ser disparado nele. Nenhum uso concorrente do mesmo
aparelho entre as campanhas. Bateria do alvo em 28% no inventário — carregar antes de qualquer
corrida longa.

**Política fixada:** **F05 (OpenCL) está suspensa e não é dependência de F06.** Smart NPU→OpenCL
também suspenso. Nenhuma rota nova recebe aprovação por este inventário.

**Entradas para F01:** (1) este inventário e o mapa de lacunas; (2) revisão fixada do model card
`a1e3416e…` e do código `modeling_granite_speech_nar.py`; (3) corpus FLEURS já usado (82 amostras,
5 idiomas) + conjunto pt-BR; (4) métricas prospectivas WER/CER/S/D/I e limites de atribuição;
(5) escopo do G01 (só a alegação que depender de número antigo); (6) configuração de controle
proposta: CPU, variante int8b (instalada) ou fp16 (requer download), bucket menor que caiba.

## 10. Conclusão proposta e próxima decisão

**Artefatos que podem ser liberados:** este relatório, `F00-inventory.json`,
`F00-runtime-matrix.csv`, `F00-capability-map.csv`, `F00-coverage.csv`, `F00-lacunas.md`,
`F00-evidencias.txt` e `F00-SHA256.txt` — uso: base documental de F01 e conferência de identidade.
**Nada aqui aprova backend, modelo, APK ou publicação.** Os CSVs estão em disco, mas são
ignorados pelo Git (`.gitignore:55`); versioná-los exige decisão explícita (G-13).

**Rotas não demonstradas:** CPU (nenhuma inferência), Vulkan (inexistente), HTP (QAIRT ausente),
Smart (inexistente). 60 s e live permanecem não avaliados.

**Riscos concretos:** G-01 (lib carregada), G-03 (default fp16 ausente → 3,26 GB), G-05 (ORT global
com Whisper), G-06 (cancel sem ACK), G-08 (memória/thermal sem guarda).

**Reproducer mínimo:** `adb -s 100.108.27.64:5555 shell run-as br.gov.sp.pcsp.launcher cat
shared_prefs/granite_nar_llm_settings.xml` + `ls -la .../granite_nar_models` → mostra int8b
selecionado e ausência do par fp16.

**Recomendação:** liberar **F01 (protocolo/referências/corpus)**, mantendo F05 suspensa e
F06 independente. Antes de F02, decidir explicitamente: (a) baixar a variante fp16 (3,26 GB) ou
adotar int8b como controle; (b) instalar QAIRT v1 no alvo; (c) carregar a bateria do CPH2747.
Não executei nenhuma dessas ações.

## 11. Comandos e verificações

Executados (somente leitura): `git rev-parse/status/log/show/diff/cat-file`, `sha256sum`,
`unzip -l/-p`, `grep`, `sed`, `find`, `ls`, `stat`, `df`, `python` (scripts locais de hash/parse),
`adb devices/shell` (`getprop`, `df`, `dumpsys battery`, `pm path`, `pidof`, `ps`, `ls`, `cat`,
`sha256sum`, `du`, `run-as`), `adb exec-out cat`.

**Não executados, com motivo:** `gradlew test/lint/assemble` (F00 não pede execução; seriam provas
de F02+); qualquer download/instalação; force-stop; limpeza de log; microfone; benchmark; commit;
push; assinatura.

## 12. Anexos

`F00-report.json` (schema v3) · `F00-coverage.csv` · `F00-inventory.json` ·
`F00-runtime-matrix.csv` · `F00-capability-map.csv` · `F00-lacunas.md` · `F00-evidencias.txt` ·
`F00-SHA256.txt`.

Fim da ordem. **F01 não foi iniciada.**
