# DESIGN — SMART DUAL-BACKEND (OpenCL/Vulkan) + NPU — Campanha 2026-10

Documento exigido pela **Fase Zero** das ordens consolidadas de 07/10/2026.
Autoridade: ordens do especialista + adendos do usuario (Smart separado da
NPU; semantica obrigatoria). Este design NAO autoriza, por si, commit/push,
release, upload R2, hot-swap da lib do SIG principal ou instalacao de APK
sobre ele — cada etapa externa requer aprovacao especifica (ver §9).

## 1. META E SEMANTICA (nao negociavel)

Menu final desejado (ordem): **Smart (OpenCL)**, **Smart (Vulkan)**, CPU,
GPU (OpenCL), GPU (Vulkan), **NPU**.
- **Smart (X)**: prefill no NPU/HTP → geracao na GPU **X** — handoff correto
  de estado/KV; **sem replay integral** do prompt no destino; jamais
  NPU+CPU rotulado como NPU+GPU.
- **NPU**: prefill E geracao predominantemente no HTP, com ops nao
  suportadas explicitadas no diagnostico. NAO e sinônimo de Smart.
- Nao existe "Smart generico/automatico". Nao trocar modelo/default/
  backend-destino mantendo rotulo. Vulkan segue o default atual ate
  decisao separada do usuario.
- **IDs persistidos**: nao renumerar CPU/OpenCL/Vulkan/NPU; Smart recebe
  DOIS IDs NOVOS com testes de migracao/persistencia.

## 2. ESTADO BASE (fresco em 07/10, 16h)

| Item | Estado |
|---|---|
| Repo | `main = 550ca56` (fix ANR + R2 publicado). WIP de outros agentes (smartjoin/ffmpeg) intacto — NAO movimentar. |
| Device alvo | CPH2747, USB `3B15BD00FVE00000` (identidade confirmada). PJA110/`1164a04` FORA DO ESCOPO. |
| App SIG no device | v1.508 + lib do fix ANR ativa (hot-swap R5; NAO duravel). Pacote nativo remontado com fix; modelo c4bf1015 reposto. Configs do app perdidas no incidente (usuario reconfigura). |
| Modelo produto | `c4bf1015b01fcc97b1510f58eca78604eb45758470e83f317ffbfae29dbe4559` (1.133.080.544 B, "produto": derivado do Q8 do projeto). |
| Modelo oficial HF/R2 | `dc5f44fcf1fa496ee7ad725982c0c8c553a4de00259b53af84c4b89fb0c06699` (1.133.080.448 B). **Equivalencia NAO presumida** — comparacao pendente (Fase 1). |
| R2 | Catalogo Hy-MT2 completo (1.25Bit/Q4_0/Q4_K_M/Q6_K/Q8_0) + VAD silero; validado HTTP 200 + Content-Length. Texturas: espelhamento por GitHub Actions. |
| Fork SIG | `app/src/main/cpp/llama/` (llama.cpp pinado + kernels STQ + patches `native-dependencies/patches/{vulkan-reset-v10, opencl-guard-cache-v1}`). **Ja contem `ggml/src/ggml-hexagon`** com `option(GGML_HEXAGON ... OFF)` (L269 do ggml/CMakeLists.txt) — ligar e' passo de integracao, nao porte. |
| Prova app-UID (R7) | Probe corrigido: dlopen driver OK, HTP0 registrado, sessao CDSP 42-48ms, MUL_MAT 64x64 correto. P1-P3 ✓; **P4 pendente** (modelo completo em app normal). |
| Hosts | PC: C: 155G livres; **D: apenas 26G (98%)** — builds novos preferir C: ou servidor. Servidor (tailnet): 36 cores, ~9G RAM livre, 122G disco — BUILD HOST oficial. |
| Contencao LMK | Testes pesados disparam LMK (mata Tailscale) — usar USB; nunca matar apps do usuario. |

## 3. RISCOS PRINCIPAIS (registro vivo)

| # | Risco | Mitigacao/estado |
|---|---|---|
| R1 | Replay integral mascarando "Smart" | contadores/faixas de posicao por fase; criterio de aceitacao §8 |
| R2 | Estado/KV incompativel entre backends (dtype/layout/RoPE/seqIDs) | Fase 4: rastrear APIs e validar por contrato; nao presumir do HTP->CPU |
| R3 | Memoria dupla de pesos (dois contextos) — LMK/thermal | medir residente/pico; criterio de memoria segura; amortizacao |
| R4 | Handoff com ponte de logits/token ambigua | definir contrato do primeiro token; teacher forcing/logits |
| R5 | Custo de load/repack anula ganho de prefill | medir cold/warm COMPLETO; wall/TTFT |
| R6 | Reexecucao destrutiva de scripts (repetir incidente do uninstall) | wrapper fail-closed §7 + Fase 2 |
| R7 | Espaco D: 26G / builds grandes | caminho curto em C: ou servidor |
| R8 | Hash curto reutilizado como manifesto | manifesto SHA-256 full em toda entrega (§10) |
| R9 | Toolchain CI JDK21 (foojay 400) | fix opcional (setup-java 21) se autorizado |
| R10 | Mascarar NPU/CPU como Smart em fallback | proibido silencioso; erro explicito ou fallback declarado (adendo 2 §6) |

## 4. APIs REAIS RASTREADAS (fork SIG; refs de arquivo/linha)

- **Export/import de estado (base do handoff)**: `llama_state_get_size` /
  `llama_state_get_data` / `llama_state_set_data`; `llama_state_seq_*`
  (get/set por sequencia) — `app/src/main/cpp/llama/include/llama.h:802+`.
  *Nao presumir compatibilidade cross-backend/versao*: validar por
  contrato na Fase 4 (o serializado e' dependente de config/arquitetura).
- **Memoria/KV**: `llama_get_memory`, `llama_memory_clear`,
  `llama_memory_seq_rm/cp/keep/add/div/pos_min/pos_max/can_shift`
  (llama.h:570..793) — ops de posicao/seqID para reset A-B-A e pontes.
- **Sincronizacao**: a confirmar `llama_synchronize` no pin (Fase 4);
  fences/readback por backend conforme registry.
- **Sampler**: cadeia atual do JNI (`llama_sampler_*` em llama_jni.cpp);
  contrato de "primeiro token" definido na Fase 4 (logits do prefill de
  origem -> sampler UMA vez -> token consumido no destino na posicao
  correta; proibido duplo accept).
- **Hexagon no fork**: `ggml/src/ggml-hexagon/` presente; build flag
  `GGML_HEXAGON` (OFF por padrao; ggml/CMakeLists.txt:269). Skel v81 como
  no pkg upstream; ADSP_LIBRARY_PATH/skel no APP exige projeto correto.
- **OpenCL loader do produto**: `android-opencl-loader.cpp` (JNI shim) —
  NAO usar cadeia vendor DT_NEEDED nos apps (licao R6/R7).
- **Patches do fork**: `vulkan-reset-v10` (reset de pedidos), 
  `opencl-guard-cache-v1` (guard+loadercache) — preservar; nao substituir
  o fork pelo upstream.
- **JNI atual**: `llama_jni.cpp` (load/generate/getters; g_mutex e
  g_ui_mutex; reset por pedido via `hymt2_begin_fresh_request`).

## 5. FASES (entrada/saida resumidas)

**F0 — Seguranca/snapshot/design** (ESTE doc). Saida: design + allowlist §7
+ wrapper §7 testado + snapshot §2.

**F1 — Identidade do modelo.** Comparar c4bf vs dc5f: GGUF metadata +
payloads por tensor; tabela de proveniencia; bloqueio de promocao se
identidade nao resolvida. Verificacao de sha dos objetos do R2 por leitura
no runner (sem banda local). Saida: tabela + baselines pinados.

**F2 — Vacina ANR e infra confiavel.** Trocar `Thread.sleep(200)` por sinal
REAL pos-aquisicao (latch); timeout de prontidao FALHA se nao medir;
cleanup em finally; mutante (getter->g_mutex) detectado; working thread
atrasada (sem falso positivo). Hook `sigTestHoldGmutex` restrito a
variante NATIVA de teste (`#ifdef`), simbolo AUSENTE em libs publicaveis
(verificado por nm); binding compativel no Kotlin; sem stub no-op.
CI JDK21: fix opcional autorizavel. Saida: vacina deterministica +
artefato de teste sem hooks no produto.

**F3 — P4 app-UID completo (modelo c4bf em app normal).** Probe separado,
sem root em runtime; HTP registro->sessao->grafo->geracao->reset no mesmo
processo; corpus curto->medio; completude/EOG/finite/nao-vazio; A-B-A no
mesmo contexto; cold/warm; timeouts/cancelamento; memoria conservadora.
Acesso ao modelo pelo probe: SAF/permissao documentada ou copia
autorizada FORA dos dados do SIG (decisao do usuario — formulario §9).
Saida: P4 aprovado por camada OU bloqueio preciso.

**F4 — Arquitetura do handoff (investigar antes de implementar).** Comparar
estrategia A (dois contextos + export/import) vs B (contexto unico com
roteamento por fase) contra as APIs §4; documentar KV dtype/layout/FA/
positions/RoPE/seqIDs/fences/ownership; contrato do primeiro token;
reprocessamento pequeno explicitado e contado; ordem sugerida:
NPU->Vulkan primeiro (default atual), depois NPU->OpenCL (ou invertido com
justificativa registrada). Saida: contrato + prototipo isolado por rota.

**F5 — Corretude Smart (rotas independentes).** Harness com identidade de
backend ANTES de medir; mutante CPU/contexto-errado aborta; provar prefill
HTP + geracao no destino (traces); token replay=0; KV pos/seqIDs; logits em
prefixos (teacher forcing) com tolerancias documentadas; A-B-A, vazio,
limite de contexto, cancelamento, falha de sessao, troca, unload; cleanup
correto; uma rota nao habilita a outra. Saida: testes RED/GREEN
permanentes + mapa de suporte.

**F6 — Desempenho end-to-end (sem grid inutil).** Mesmo peso c4bf, corpus
pinado; build candidato unico com modos; piloto curto+medio x modos
aprovados (repeticoes intercaladas); depois longo nas promissoras; medir
cold/repack, prefill, transfer/handoff, TTFT, decode, wall, memoria/temp;
timers aninhados nao somados; criterio conservador (ganho repetivel >
variabilidade; sem regressao; memoria segura; separar prompts longos de
curtos); tuning adicional = tabela separada. Saida: CSV/JSON por tentativa
+ decisao por rota.

**F7 — Integracao local no produto (apos provas).** Backend Hexagon minimo
no fork (flag + libs no pacote + skel), preservando STQ/patches; auditar
vendor DT_NEEDED/16KB page/licenses/tamanho; nao compilar llama/whisper no
mesmo projeto; nao atualizar whisper.cpp; devices nao-Qualcomm: CPU/GPU
seguem funcionando (npu ausente nao quebra dlopen); discover/session
failures recuperaveis; v81 testada != prometer outras archs. Seam puro de
capabilities/politica Smart (KDoc + MODULE-MAP); UI conserva UI; IDs
estaveis (2 novos); menu 6 entradas com motivo de indisponibilidade;
logs solicitado/efetivo/SHA/backends/transfer/fallback; fallback explicito
(nunca Smart silencioso com CPU/NPU inteira). Saida: implementacao local
testavel + gates reais.

**F8 — Aceitacao/artefatos.** testDebugUnitTest/lintDebug/assembleDebug +
build NATIVO quando fonte mudou; smokes Whisper/VAD (binario!); 2 ABIs
(x86 = NAO TESTADO EM RUNTIME); hooks de teste ausentes no pacote; nova
lib requer versao nativa nova (nunca sobrescrever v11); contratos
UPDATE.md/verify-native-dependencies; APK+lib com checagem de
versao/capabilities; separacao preparacao/instalacao/commit/publicacao.
Saida: objetos/versionamento/diff/smoke/rollback apresentados ANTES de
qualquer aprovacao.

## 6. CRITERIOS GERAIS DE PARADA

- P4/transfer falhou -> localizar causa, documentar reproducer; nao tunar
  em caminho incorreto.
- Rota exigindo refactor grande/duplicacao inviavel -> checkpoint de
  design com estimativa/alternativa (nao substituir Smart por replay).
- Memoria/temperatura/degradacao invalida controles -> parar, registrar,
  retomar so em condicao segura; nunca matar apps do usuario.
- Faltou permissao -> completar design/testes offline e pedir SO a decisao
  bloqueante (formulario §9). Nao aguardar inerte; nao executar condicional
  sem autorizacao.

## 7. ALLOWLIST E WRAPPER FAIL-CLOSED

**Serial permitido**: `3B15BD00FVE00000` (CPH2747). Qualquer outro
(INCLUSIVE `1164a04`/PJA110) = ABORTA.
**Packages**: `br.gov.sp.pcsp.npuprobe` = OK (probe). `br.gov.sp.pcsp.launcher`
(SIG) = PROTEGIDO: proibido uninstall/pm clear/hot-swap/instalacao sem
aprovacao especifica; nunca connectedDebugAndroidTest sobre ele sem
auditoria do ciclo AGP que garanta preservacao de dados.
**Paths de trabalho**: `C:/llama-npu` (evidencias), `C:/npu-probe`,
`docs/npu-hymt2/<rodada>/`, servidor tailnet `/root/...`; D: sem builds
grandes (26G).
**Wrapper** (`docs/npu-hymt2/smart/sig-adb.sh`): todo comando adb passa
`-s <serial>` explicito; valida identidade (getprop model/serial vs
esperado) ANTES de operar; bloqueia uninstall/pm clear de package
protegido; deploy com readback SHA-256 (bloqueia divergencia); fail-closed
(sem alvo explicito = aborta). Testes offline em
`docs/npu-hymt2/smart/test-sig-adb.sh`: serial ausente/errado/outro
aparelho/destino ambiguo/uninstall protegido/hash divergente devem BLOQUEAR
antes da acao.

## 8. CRITERIO DE DECISAO SMART (conservador)

Sucesso = beneficio de wall/TTFT REPETIVEL (acima da variabilidade medida)
com corretude/estabilidade preservadas e memoria segura — separando
ganho em prompts longos de perda em curtos; incluindo custos de dois
contextos (load/repack/memoria) em sessao amortizada; sem promessa
universal. Uma rota aprovada NAO habilita a outra.

## 9. AUTORIZACOES NECESSARIAS (formulario agrupado — pendente do usuario)

| # | Etapa | Alvo | Risco | Autorizacao |
|---|---|---|---|---|
| A1 | Verificacao SHA dos 4 objetos no R2 por leitura no runner (job de verificacao, extensao do workflow aprovado) | GitHub Actions/R2 | Sobrescrever objeto por engano (mitigado: somente leitura; paths inalterados) | [ ] |
| A2 | P4: copia do modelo c4bf para o app-id do probe (espaco em /data ~551G; copia fora dos dados do SIG) OU acesso SAF | CPH2747 | duplicar 1,1GB; nada altera o SIG | [ ] |
| A3 | P4: executar o probe com o modelo completo no device (USB) | CPH2747 | termico/banco curto; sem root | [ ] |
| A4 | Builds isolados no servidor (hexagon ON no fork + variante de teste com hook) | servidor tailnet | recursos do servidor | [ ] |
| A5 | Fix do CI (setup-java 21 no workflow) + commit/push desse arquivo | repo/CI | mudanca no CI | [ ] |
| A6 | Remover o hook de teste da lib PUBLICAVEL (novo pacote de teste separado); a lib do device atual (com hook) permanece como esta | repo/device | transparencia | [ ] |

## 10. ENTREGAVEIS DESTA CAMPANHA

- `docs/especialista/RELATORIO-SMART-DUAL-BACKEND-RODADA1.txt` (A-H, com
  CCE/PARCIAL/PENDENTE/BLOQUEADO por rota).
- Logs/evidencias em `docs/npu-hymt2/smart-rodada1/` com manifesto SHA-256
  completo (tamanho + caminho real); contadores/dedupe por script;
  distincao OBSERVADO/MEDIDO/DERIVADO/HIPOTESE/NAO EXECUTADO.
- Referenciar o ADENDO (Smart separado da NPU) em todo design/relatorio
  desta frente.
