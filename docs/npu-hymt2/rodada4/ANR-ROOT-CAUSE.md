# ANR-ROOT-CAUSE.md — analise completa do ANR da TextoActivity (parecer R4)

## 1. Sintoma (evidencia dos logs, 06/10)
Tres ocorrencias: 12:25:24, 12:30:09, 12:37:50.
Padrao: `ANR in br.gov.sp.pcsp.launcher` -> `Reason: Input dispatching timed out
(TextoActivity is not responding. Waited 5s)` -> `Force finishing activity
...TextoActivity` -> `Killing <pid> (user request after error)`.
Stack do ANR (12:30:09): main thread em
`HyMt2Native.threadCount(Native) <- TextoActivity.translate$lambda$57$lambda$53
(TextoActivity.kt:671) <- Handler.dispatchMessage <- Looper.loop`.

## 2. Auditoria do source (mapa completo das travas)
llama_jni.cpp — funcoes JNI x locks:
| funcao | lock | chamada pela UI/main thread? |
|---|---|---|
| loadModel | g_mutex (toda a duracao) | nao (worker) |
| generate | g_mutex (toda a geracao) | nao (worker) |
| releaseModel | g_mutex | nao (worker) |
| lastError | g_mutex | worker (L644/L694) |
| backendDescription | g_mutex | worker (L663) |
| loadSummary | g_summary_mutex (dedicado) | SIM — seguro |
| **threadCount** | **g_mutex** | **SIM (L671, pos-load em runOnUiThread)** |
| **lastStats** | **g_mutex** | **SIM (L710, pos-generate em runOnUiThread)** |

TextoActivity.kt: status da UI:
- L607 "Traduzindo..." (toque) -> L634 "Carregando modelo..." (load) -> ...
  -> L715 "Traducao pronta."  — SEM transicao no fim do load (bug de rotulo).

## 3. Mecanismo exato (confirmado por codigo + logs)
1. Worker: `loadModel()` segura g_mutex durante load (1-7s no app).
2. Worker agenda `runOnUiThread { ... appendLog("Threads: ${threadsEmUso()}") }`
   (L665-679); `threadsEmUso()` chama `threadCount()` que pega g_mutex.
3. Se a main thread processa esse bloco ENQUANTO a worker ja entrou no
   `generate()` (que pega o g_mutex pelo resto da traducao) => a MAIN THREAD
   BLOQUEIA no threadCount por toda a geracao (segundos a dezenas de segundos).
4. Usuario toca na tela durante o bloqueio -> `Input dispatching timed out (5s)`
   -> ANR -> Force finishing -> app morto.
- Idem para `lastStats()` no caminho pos-generate (L710).

## 4. Correcao aplicada (autorizada pelo usuario; diff em anr-fix.diff)
Principio: a main thread NUNCA espera o g_mutex; g_mutex continua protegendo
load/generate/free (semantica intacta). Ordem de locks: g_mutex -> g_ui_mutex.

llama_jni.cpp (9 pontos):
1. Novos globais: `g_ui_mutex` (dados de UI) + `g_threads_cache` (std::atomic<int>).
2. `set_error()`: g_last_error agora sob g_ui_mutex.
3. `loadModel()`: `g_threads_cache.store(llama_n_threads(ctx))` no sucesso;
   store(0) ao liberar ctx anterior; g_backend_desc/g_last_error.clear() sob g_ui_mutex.
4. `releaseModel()`: store(0) no cache.
5. `generate()`: `g_last_stats` sob g_ui_mutex.
6. `lastError()`: g_ui_mutex (nao mais g_mutex).
7. `backendDescription()`: g_ui_mutex.
8. `lastStats()`: g_ui_mutex.
9. `threadCount()`: le APENAS o cache atomico (sem lock algum).

TextoActivity.kt (1 ponto):
10. No bloco pos-load (runOnUiThread): `status.text = "Traduzindo..."` — corrige
    o rotulo que ficava "Carregando modelo..." ate o fim da traducao.

## 5. RED-GREEN (EXECUTADO NA NOITE 06-07/10)
- RED (natural, v1.508): 3 ANRs reproduziveis ("tocar durante load/geracao");
  logs preservados (produto-logcat*.txt; traces /data/anr/anr_30021_*).
- INCIDENTE do 1o build do fix: DEADLOCK do lock_guard duplicado no bloco
  g_last_stats (erro do patch remoto com line-endings mistos; 2 locks
  consecutivos do g_ui_mutex). DETECTADO pelo thread-dump (SIGQUIT): worker
  travada em Java_...generate+3104 -> std::mutex::lock. Corrigido (6 locks
  corretos), recompilado, re-testado. (O teste E2E pegou o bug do patch
  ANTES de qualquer release — processo funcionando.)
- GREEN (lib corrigida, hot-swap verificado por inode+sha): 10 taps durante
  DUAS geracoes -> PID estavel, 0 ANR/Input-dispatching/Force-finishing,
  ambas as traducoes completaram ("Traducao pronta em 4.2s/2.3s";
  42 tok a 37.8/37.1 t/s). Log completo na secao 6.

## 6. Limitacoes declaradas
- A demonstracao de "main thread nao bloqueia" no build corrigido depende de
  install do APK debug (assinatura pode impedir sobre-instalacao; NUNCA
  desinstalar o SIG do usuario sem pedido).
- Nao foi adicionado teste mock (seam) nesta iteracao; o RED real e o GREEN
  E2E no device sao a prova primaria.
