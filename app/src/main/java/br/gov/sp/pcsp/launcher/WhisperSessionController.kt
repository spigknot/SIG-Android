package br.gov.sp.pcsp.launcher

import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock

/** Controle de ciclo de vida das sessões de transcrição Whisper
 * (W01/W02/W09/W10; R2/B2; R4/B1 — init REALMENTE assíncrona).
 *
 * Seam puro (JVM, sem Android) para que regras de geração de sessão, init
 * assíncrono do nativo, release assíncrono, descarte de callbacks obsoletos e
 * ownership por sessão sejam testáveis sem o nativo.
 *
 * Contrato R4/B1 — identidade LÓGICA x identidade NATIVA:
 * - [beginSession] é barato e SÓ da thread chamadora: reserva uma geração
 *   lógica ÚNICA no singleton (contador único do processo; não reinicia por
 *   Activity) e ENFILEIRA um job de init no executor de trabalho. Nenhuma
 *   chamada nativa (class-init/dlopen/probe de versão/beginSessionOwned)
 *   acontece na chamadora — primeiro clique/start/cancel/onDestroy nunca
 *   pagam a carga da lib nem esperam por ela (`await`/`join` zero na UI).
 * - O job é a associação EXPLÍCITA e IMUTÁVEL entre a geração lógica (token
 *   imediato, usado por isCurrent/runIfCurrent para invalidar durante o init)
 *   e o binding nativo (owner emitido por `beginSessionOwned`, ou sessão
 *   legada). Operações longas usam o binding DO JOB e esperam por ele em
 *   thread de trabalho com teto ([BINDING_WAIT_MS]) — nunca na UI.
 * - Init resolve em READY (modo OWNED quando a lib expõe a API >= 2; LEGACY
 *   quando a lib simplesmente NÃO tem a API — compatibilidade, distinta de
 *   falha) ou FAILED (erro explícito; NÃO vira "legado" silenciosamente).
 * - Cancel/saída (invalidateOwner) DURANTE o init ficam registrados no job:
 *   quando o binding aparece, a sessão cancelada não carrega/transcreve e o
 *   owner que surgiu tarde recebe o cancel/cleanup correto por token próprio.
 * - Release usa SEMPRE o token nativo do binding do job que realmente possuía
 *   o contexto (nunca a geração global).
 * - R2/B2 (ownership v2): load/transcribe/release são serializados entre si
 *   por um coordinator lock justo, com o LEASE do job (isCurrent + validade)
 *   re-verificado DENTRO do lock; [cancel] nunca passa por esse lock.
 * - R2/B2 (pareamento): [transcribeAndStatus] devolve texto e status do MESMO
 *   job, lidos na mesma seção crítica (o par é protegido).
 */
class WhisperSessionController(
    private val gateway: NativeGateway,
    /** Executor de TRABALHO (single-thread por default): roda os jobs de init
     * (prewarm/probe/beginSessionOwned) e os releases. Nunca é a thread da UI;
     * a serialização FIFO também ordena a emissão de owners (igual à ordem das
     * gerações lógicas). Injetável em testes. */
    private val releaseExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "whisper-model-release").apply { isDaemon = true }
    },
    private val logError: (String, Throwable?) -> Unit = { _, _ -> },
    /** R4/B1: prewarm do class-load nativo, executado DENTRO do job de init no
     * executor de trabalho (nunca na UI). Injetável para testes; produção força
     * a carga da lib via [WhisperNative.systemInfo]. A exceção NÃO é engolida —
     * vira [NativeInitState.FAILED] observável (sem chamada lazy bloqueante). */
    private val nativePrewarm: () -> Unit = {
        WhisperNative.systemInfo()
    },
    /**
     * R28/A3 — provedor do pedido da tentativa. O default chama o seam de
     * laboratório EXATAMENTE como antes; a injeção existe para os testes
     * exercitarem o fluxo real do controller com pedidos não-default, sem
     * expor knob na UI de produção.
     */
    private val attemptRequestProvider: (String, Long, Long, Long, Long) -> AttemptRequest? =
        { token, owner, gen, job, attempt ->
            WhisperLabSeams.attemptRequest(token, owner, gen, job, attempt)
        },
) {

    /** Fronteira injetável para o nativo; produção delega a [WhisperNative],
     * testes usam um fake com contadores/latches. Os métodos "Owned" (R2/B1)
     * têm default que lança [UnsatisfiedLinkError] — fakes e libs antigas sem a
     * API v2 caem no modo legado automaticamente. */
    interface NativeGateway {
        fun loadModel(modelPath: String, backendKind: Int, flashAttention: Boolean): Boolean
        fun transcribe(
            wavPath: String,
            language: String,
            beamSize: Int,
            bestOf: Int,
            wordTimestamps: Boolean,
            vadFilter: Boolean,
            vadModelPath: String,
            callback: WhisperNative.Callback
        ): String

        fun cancelTranscription()
        fun beginSession(): Long
        fun releaseModel()
        fun lastTranscribeStatus(): String

        fun ownershipApiVersion(): Int =
            throw UnsatisfiedLinkError("ownershipApiVersion ausente (lib legada)")

        fun beginSessionOwned(): Long =
            throw UnsatisfiedLinkError("beginSessionOwned ausente (lib legada)")

        fun loadModelOwned(modelPath: String, backendKind: Int, flashAttention: Boolean, owner: Long): Boolean =
            throw UnsatisfiedLinkError("loadModelOwned ausente (lib legada)")

        fun releaseModelOwned(owner: Long): Boolean =
            throw UnsatisfiedLinkError("releaseModelOwned ausente (lib legada)")

        fun transcribeOwned(
            wavPath: String,
            language: String,
            beamSize: Int,
            bestOf: Int,
            wordTimestamps: Boolean,
            vadFilter: Boolean,
            vadModelPath: String,
            owner: Long,
            callback: WhisperNative.Callback
        ): String = throw UnsatisfiedLinkError("transcribeOwned ausente (lib legada)")

        /**
         * R25/REQ — transcrição com a fotografia da tentativa NA chamada.
         * Lib antiga (sem o símbolo) lança [UnsatisfiedLinkError]: o controller
         * cai no caminho owned/legado, registrando a recusa — nunca aplica
         * metade do pedido.
         */
        fun transcribeOwnedReq(
            wavPath: String,
            language: String,
            beamSize: Int,
            bestOf: Int,
            wordTimestamps: Boolean,
            vadFilter: Boolean,
            vadModelPath: String,
            owner: Long,
            callback: WhisperNative.Callback,
            request: String
        ): String = throw UnsatisfiedLinkError("transcribeOwnedReq ausente (lib legada)")

        fun cancelTranscriptionOwned(owner: Long): Unit =
            throw UnsatisfiedLinkError("cancelTranscriptionOwned ausente (lib legada)")

        fun lastTranscribeStatusOwned(owner: Long): String =
            throw UnsatisfiedLinkError("lastTranscribeStatusOwned ausente (lib legada)")

        /** R5/A1: diagnóstico NATIVO (erro/saída do load). Pode bloquear no
         * g_mutex do nativo enquanto carga/inferência longa estiver em curso:
         * chamar SEMPRE de thread de trabalho e, quando possível, DENTRO do
         * coordinator logo após a PRÓPRIA operação (associação garantida). */
        fun lastError(): String =
            throw UnsatisfiedLinkError("lastError ausente (lib legada)")

        /** R5/A1: log da última carga de modelo, mesma regra de [lastError]. */
        fun lastLoadLog(): String =
            throw UnsatisfiedLinkError("lastLoadLog ausente (lib legada)")

        /** R3/B2: transferência atômica de posse de contexto (fast-path de
         * cache). Lib sem o símbolo lança — o chamador trata como "sem hit". */
        fun assumeContextOwned(modelPath: String, backendKind: Int, flashAttention: Boolean, owner: Long): Boolean =
            throw UnsatisfiedLinkError("assumeContextOwned ausente (lib sem R3)")

        /** R8/A2: marcador `faJobBegin` no log nativo (delimita o estado FA do
         * job ATUAL). Diagnóstico best-effort: lib antiga sem o símbolo devolve
         * false SEM derrubar o job. */
        fun faJobBegin(route: String): Boolean = false
    }

    companion object {
        /** Texto devolvido por [transcribe]/[transcribeAndStatus] quando a
         * geração já foi substituída. */
        const val STALE_RESULT_TEXT = "Cancelado: sessão substituída"
        /** Status simulado para chamadas de geração antiga. */
        const val STALE_STATUS = "cancelled"
        /** Versão mínima do nativo que expõe o contrato owned (R2/B1). */
        const val OWNERSHIP_API_VERSION = 2
        /** Teto de espera do binding de um job nas operações (threads de
         * trabalho, nunca a UI). Generoso: cobre a carga da lib/modelo. */
        const val BINDING_WAIT_MS = 180_000L
        /** Teto do release esperando o binding (o release de um job que
         * carregou SEMPRE tem o binding já resolvido). */
        private const val RELEASE_BINDING_WAIT_MS = 30_000L
        /** Teto curto do getter de status (diagnóstico). */
        private const val STATUS_BINDING_WAIT_MS = 5_000L
        /** Nº máximo de jobs retidos para resolução de release por geração. */
        private const val MAX_RETAINED_JOBS = 64
    }

    /** Coordinator justo: serializa load/transcribe/release ENTRE si. [cancel]
     * não passa por aqui — precisa responder durante load/inferência. */
    private val coordinator = ReentrantLock(true)

    private val generationCounter = AtomicLong(0L)

    /** R4/B1: dono LÓGICO do processo (imediato). É o token devolvido por
     * [beginSession] e conferido por [isCurrent]/[runIfCurrent]. */
    @Volatile
    private var generation: Long = 0L

    /** R5/A3: última geração INVALIDADA sem sucessor (destroy) — política
     * explícita de release do modo legado. */
    @Volatile
    private var lastInvalidatedGeneration: Long = 0L

    /** Binding nativo resolvido assincronamente por job: geração lógica ↔
     * (owner nativo, ou sessão legada). */
    data class Binding(val nativeOwner: Long, val owned: Boolean, val nativeSessionId: Long)

    /** Job de uma sessão: associação imutável (id lógico) cujo binding nativo
     * é resolvido pelo executor de trabalho. */
    class SessionJob internal constructor(val id: Long) {
        private val readyLatch = CountDownLatch(1)
        private val cancelForwardedFlag = AtomicBoolean(false)
        private val attemptCounter = AtomicLong(0L)

        /** R25/REQ — número REAL da tentativa neste job (1, 2, 3...). Antes o
         * nativo recebia attempt=0 (wildcard) ou o literal 1 publicado pela
         * tabela lateral; agora cada chamada de transcrição é uma tentativa
         * identificada. */
        internal fun nextAttempt(): Long = attemptCounter.incrementAndGet()

        @Volatile
        var binding: Binding? = null
            private set

        @Volatile
        var initFailure: Throwable? = null
            private set

        @Volatile
        var invalidated: Boolean = false

        @Volatile
        var cancelRequested: Boolean = false
            private set

        internal fun markCancelRequested() {
            cancelRequested = true
        }

        /** Marca o cancel como CONSUMIDO por uma operação do próprio job
         * (status "cancelled" lido após um transcribe). */
        internal fun markCancelConsumed() {
            cancelRequested = false
            cancelForwardedFlag.set(false)
        }

        internal fun claimCancelForward(): Boolean = cancelForwardedFlag.compareAndSet(false, true)

        internal fun publish(binding: Binding) {
            this.binding = binding
            readyLatch.countDown()
        }

        internal fun fail(cause: Throwable) {
            this.initFailure = cause
            readyLatch.countDown()
        }

        /** Espera (com teto) o fim do init do job. true = resolvido (binding ou
         * falha), false = tempo esgotado (init ainda pendente). */
        fun awaitReady(timeoutMs: Long): Boolean {
            return try {
                readyLatch.await(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
        }
    }

    private val jobsLock = Any()
    private val jobs = LinkedHashMap<Long, SessionJob>()

    @Volatile
    private var nativeModeResolved = NativeMode.UNRESOLVED

    /** Modo nativo resolvido no init (compatibilidade x capacidade). */
    enum class NativeMode { UNRESOLVED, OWNED, LEGACY }

    /** R3/B2: estado assíncrono da inicialização da lib/probe nativos. Começa
     * PENDING; o primeiro job de init o leva a READY ou FAILED. A UI nunca paga
     * a carga — [awaitNativeInit] existe para testes/diagnóstico. */
    enum class NativeInitState { PENDING, READY, FAILED }

    @Volatile
    var nativeInitState: NativeInitState = NativeInitState.PENDING
        private set

    private val nativeInitLatch = CountDownLatch(1)

    /** Espera (com teto) o fim da inicialização assíncrona; devolve o estado. */
    fun awaitNativeInit(timeoutMs: Long): NativeInitState {
        try {
            nativeInitLatch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return nativeInitState
    }

    /** Id nativo da última sessão resolvida (diagnóstico). Em modo owned é o
     * owner emitido por `beginSessionOwned`; 0 antes de qualquer resolução. */
    @Volatile
    var lastNativeSessionId: Long = 0L
        private set

    /** Abre nova sessão: reserva a geração LÓGICA única e AGENDA o trabalho.
     * Nenhuma chamada nativa acontece nesta thread — o primeiro clique/start
     * nunca paga class-init/dlopen/probe de versão. */
    fun beginSession(): Long {
        val job: SessionJob
        val newGeneration: Long
        synchronized(jobsLock) {
            newGeneration = generationCounter.incrementAndGet()
            job = SessionJob(newGeneration)
            jobs[newGeneration] = job
            while (jobs.size > MAX_RETAINED_JOBS) {
                val oldest = jobs.keys.firstOrNull() ?: break
                if (oldest == newGeneration) break
                jobs.remove(oldest)
            }
            generation = newGeneration
        }
        enqueueJobInit(job)
        return newGeneration
    }

    private fun enqueueJobInit(job: SessionJob) {
        try {
            releaseExecutor.execute { runJobInit(job) }
        } catch (t: Throwable) {
            // Não foi possível agendar: erro EXPLÍCITO (não cai para legado).
            logError("não foi possível enfileirar o init da sessão ${job.id}", t)
            markInitOutcome(failed = true)
            job.fail(t)
        }
    }

    /** Job de init (thread de trabalho): prewarm/class-init → probe de versão →
     * criação/binding do owner nativo → publicação no job. NUNCA na main. */
    private fun runJobInit(job: SessionJob) {
        try {
            if (nativeInitState != NativeInitState.READY) {
                nativePrewarm()
            }
            val version: Int? = try {
                gateway.ownershipApiVersion()
            } catch (ule: UnsatisfiedLinkError) {
                null   // compatibilidade: lib sem a API owned (NÃO é falha)
            } catch (nsm: NoSuchMethodError) {
                null   // idem (defensivo: build de lib antiga)
            }
            val binding = if (version != null && version >= OWNERSHIP_API_VERSION) {
                nativeModeResolved = NativeMode.OWNED
                val owner = gateway.beginSessionOwned()
                // R5/A2-6: owner emitido inválido (<=0) NÃO vira binding "owned"
                // aparentemente utilizável (no nativo, <=0 é anônimo) — init
                // FALHA explícito e o job não carrega/transcreve/cancela por ele.
                if (owner <= 0L) {
                    throw IllegalStateException("beginSessionOwned devolveu owner inválido ($owner)")
                }
                Binding(nativeOwner = owner, owned = true, nativeSessionId = owner)
            } else {
                // Compatibilidade explícita: lib ANTIGA (sem API owned) é modo
                // legado — distinto de falha de carga, que cai no catch abaixo.
                nativeModeResolved = NativeMode.LEGACY
                val sessionId = gateway.beginSession()
                Binding(nativeOwner = 0L, owned = false, nativeSessionId = sessionId)
            }
            lastNativeSessionId = binding.nativeSessionId
            job.publish(binding)
            markInitOutcome(failed = false)
            // Cancel/cleanup chegados DURANTE o init: o owner que nasceu tarde
            // recebe o pedido no TOKEN PRÓPRIO (por-dono no nativo), sem que a
            // sessão cancelada carregue/transcreva nada.
            forwardPendingCancel(job, binding)
        } catch (t: Throwable) {
            // R4/B1: logError ANTES de liberar os lattices — quem observa
            // awaitNativeInit/awaitReady enxerga o motivo registrado (sem corrida).
            logError("init nativo da sessão ${job.id} falhou", t)
            job.fail(t)
            markInitOutcome(failed = true)
        }
    }

    private fun markInitOutcome(failed: Boolean) {
        if (failed) {
            if (nativeInitState != NativeInitState.READY) {
                nativeInitState = NativeInitState.FAILED
            }
        } else {
            nativeInitState = NativeInitState.READY
        }
        nativeInitLatch.countDown()
    }

    private fun forwardPendingCancel(job: SessionJob, binding: Binding) {
        if (!job.cancelRequested) return
        if (!job.claimCancelForward()) return
        try {
            if (binding.owned) {
                if (binding.nativeOwner > 0L) gateway.cancelTranscriptionOwned(binding.nativeOwner)
            } else if (isCurrent(job.id)) {
                // Legado não tem token: o pedido anônimo só é emitido se a
                // sessão ainda for a atual (um sucessor não pode ser atingido).
                gateway.cancelTranscription()
            }
        } catch (t: Throwable) {
            logError("cancel pendente do init falhou (sessão ${job.id})", t)
        }
    }

    private fun jobFor(generation: Long): SessionJob? = synchronized(jobsLock) { jobs[generation] }

    /** Espera (com teto) o binding do job; null = não resolvido no teto, init
     * falhou (logado) ou job desconhecido. Uso APENAS em thread de trabalho. */
    internal fun awaitBindingTimeLimited(job: SessionJob, timeoutMs: Long): Binding? {
        if (!job.awaitReady(timeoutMs)) {
            logError("binding da sessão ${job.id} não resolveu em ${timeoutMs}ms", null)
            return null
        }
        if (job.initFailure != null) {
            logError("init nativo da sessão ${job.id} falhou: ${job.initFailure?.message}", job.initFailure)
            return null
        }
        return job.binding
    }

    /** R4/B1: espera o binding do job (uso em testes/lab; nunca na UI). */
    fun awaitBindingReady(generation: Long, timeoutMs: Long = BINDING_WAIT_MS): Boolean {
        val job = jobFor(generation) ?: return false
        if (!job.awaitReady(timeoutMs)) return false
        return job.binding != null
    }

    /** R4/B1: owner nativo REAL do job [generation] (0 se ainda não resolvido
     * ou em modo legado). Diagnóstico/lab; não bloqueia. */
    fun bindingOwnerOf(generation: Long): Long = jobFor(generation)?.binding?.nativeOwner ?: 0L

    /** Modo nativo resolvido (OWNED/LEGACY/UNRESOLVED) sem disparar probe. */
    fun nativeMode(): NativeMode = nativeModeResolved

    fun currentGeneration(): Long = generation

    /** Modo owned disponível? NÃO dispara probe (estado já resolvido pelo job
     * de init) — a UI/cache nunca resolvem capacidade de forma lazy. */
    fun ownershipAvailable(): Boolean = nativeModeResolved == NativeMode.OWNED

    /** R3/B2 + R4/B2: fast-path de cache do modelo — transfere a posse do
     * contexto já carregado (mesma chave real conferida NO NATIVO, sob g_mutex)
     * para o job [generation], SEM recarga. O LEASE do job é conferido DENTRO
     * do coordinator (antes e depois da espera pelo binding): uma sessão
     * substituída, destruída ou cancelada não rouba o contexto de B — e um
     * pedido tardio de A é recusado SEM tocar no nativo. */
    fun assumeContext(generation: Long, modelPath: String, backendKind: Int, flashAttention: Boolean): Boolean {
        val job = jobFor(generation) ?: return false
        if (job.invalidated || !isCurrent(generation)) return false
        if (job.cancelRequested) return false
        val binding = awaitBindingTimeLimited(job, BINDING_WAIT_MS) ?: return false
        if (!binding.owned) return false
        coordinator.lock()
        try {
            // LEASE re-verificado dentro do coordinator: a geração pode ter
            // mudado (ou o job sido destruído/cancelado) DURANTE a espera.
            if (!isCurrent(generation) || job.invalidated || job.cancelRequested) return false
            return try {
                gateway.assumeContextOwned(modelPath, backendKind, flashAttention, binding.nativeOwner)
            } catch (t: Throwable) {
                false
            }
        } finally {
            coordinator.unlock()
        }
    }

    fun isCurrent(generation: Long): Boolean = generation == this.generation

    /**
     * R8/A2: log NATIVO da última carga (inclui as linhas SIG_FA_STATUS e a
     * SIG_FA_EXEC do job mais recente) para DIAGNÓSTICO/aviso — chamar de
     * thread de trabalho DEPOIS do job; nunca da main durante inferência (o
     * nativo responde sob g_mutex). Vazio quando o gateway falha.
     */
    fun loadLogForDiagnostics(): String = captureLoadLog()

    /** R8/A2: marcador `faJobBegin` DESTE job no log nativo — delimita o estado
     * FA lido depois (SIG_FA_STATUS/SIG_FA_EXEC anteriores deixam de valer:
     * nenhum job herda a execução do anterior). Best-effort: lib antiga sem o
     * símbolo devolve false sem quebrar o job. */
    fun faJobBegin(route: String): Boolean =
        runCatching { gateway.faJobBegin(route) }.getOrDefault(false)

    /** R2/B2: executa [block] somente se [generation] ainda for a atual NO
     * MOMENTO DA EXECUÇÃO. Use dentro de runOnUiThread — a checagem no
     * agendamento não basta (o looper pode rodar o post depois da nova sessão).
     * Devolve se o block rodou. */
    fun runIfCurrent(generation: Long, block: () -> Unit): Boolean {
        if (!isCurrent(generation)) return false
        block()
        return true
    }

    /** Carrega/substitui o modelo para [generation]. Espera o binding do job em
     * thread de trabalho (teto [BINDING_WAIT_MS]); init FAILED = false
     * EXPLÍCITO (não cai para "legado"). Re-verifica o lease DENTRO do lock. */
    fun loadModel(generation: Long, modelPath: String, backendKind: Int, flashAttention: Boolean): Boolean =
        loadModelDetailed(generation, modelPath, backendKind, flashAttention).ok

    /** R5/A1: resultado da carga com diagnóstico ATRELADO À MESMA OPERAÇÃO.
     * [refusal] é um motivo LOCAL do lease/estado do job (NUNCA o diagnóstico
     * global de outro job); [nativeError]/[loadLog] são capturados DENTRO do
     * coordinator, imediatamente após a tentativa nativa DESTA operação — não
     * montam string do sucessor. */
    class LoadOutcome(
        val ok: Boolean,
        val refusal: String = "",
        val nativeError: String = "",
        val loadLog: String = "",
    ) {
        companion object {
            internal fun refused(reason: String) = LoadOutcome(ok = false, refusal = reason)
        }
    }

    /** R5/A1: carga com diagnóstico associado (ver [LoadOutcome]). Chamar de
     * thread de trabalho; refusals NÃO tocam o nativo (nem gateway.lastError). */
    fun loadModelDetailed(generation: Long, modelPath: String, backendKind: Int, flashAttention: Boolean): LoadOutcome {
        val job = jobFor(generation)
            ?: return LoadOutcome.refused("sessão desconhecida (id expirado ou fora da tabela)")
        if (job.invalidated) return LoadOutcome.refused("sessão substituída/destruída")
        val binding = awaitBindingTimeLimited(job, BINDING_WAIT_MS)
            ?: return LoadOutcome.refused("init nativo da sessão falhou ou não resolveu o binding")
        coordinator.lock()
        try {
            if (!isCurrent(generation) || job.invalidated) {
                return LoadOutcome.refused("sessão substituída durante a espera pela carga")
            }
            return try {
                val ok = if (binding.owned) {
                    gateway.loadModelOwned(modelPath, backendKind, flashAttention, binding.nativeOwner)
                } else {
                    gateway.loadModel(modelPath, backendKind, flashAttention)
                }
                if (ok) {
                    LoadOutcome(true, loadLog = captureLoadLog())
                } else {
                    // Diagnóstico DA MESMA seção crítica: o coordinator ficou
                    // conosco do início ao fim desta carga — lastError/lastLoadLog
                    // pertencem a ESTA operação.
                    LoadOutcome(false, nativeError = captureLastError(), loadLog = captureLoadLog())
                }
            } catch (t: Throwable) {
                logError("loadModel falhou", t)
                LoadOutcome(false, refusal = "falha ao carregar o modelo (${t.message ?: t.javaClass.simpleName})")
            }
        } finally {
            coordinator.unlock()
        }
    }

    private fun captureLastError(): String = try {
        gateway.lastError()
    } catch (t: Throwable) {
        logError("lastError falhou", t)
        ""
    }

    private fun captureLoadLog(): String = try {
        gateway.lastLoadLog()
    } catch (t: Throwable) {
        logError("lastLoadLog falhou", t)
        ""
    }

    /** Libera o modelo fora da thread chamadora (W01). O release leva o TOKEN
     * NATIVO do binding do job (o que REALMENTE possuía o contexto) e o nativo
     * reconfere o dono sob g_mutex: libera APENAS se o contexto ainda for dele
     * (JNI_FALSE caso um sucessor já o tenha assumido) — um release enfileirado
     * de A que roda depois de B jamais libera o contexto de B. No modo legado
     * (sem tokens) mantém-se o gate por geração, pois o nativo não distingue
     * donos. */
    fun releaseAsync(generation: Long) {
        // R5/A2: captura o HANDLE do job já na chamadora — a poda da tabela
        // (retenção de 64) não pode perder a referência enquanto a tarefa
        // aguarda o coordinator; o token continua sendo o do BINDING.
        val capturedJob = jobFor(generation)
        try {
            releaseExecutor.execute {
                coordinator.lock()
                try {
                    val job = capturedJob ?: jobFor(generation)
                    if (job == null) {
                        // Geração desconhecida (fora da tabela/negativa): NUNCA
                        // chutar token — release vira no-op observável.
                        logError("releaseAsync: geração $generation desconhecida; nada a liberar", null)
                        return@execute
                    }
                    val binding = awaitBindingTimeLimited(job, RELEASE_BINDING_WAIT_MS)
                    if (binding == null) {
                        logError("releaseAsync: binding da geração $generation indisponível; nada a liberar", null)
                        return@execute
                    }
                    if (binding.owned) {
                        gateway.releaseModelOwned(binding.nativeOwner)
                    } else {
                        // R5/A3: política explícita de memória no modo legado
                        // (sem tokens): libera se a geração ainda é a dona, OU se
                        // foi a última INVALIDADA sem sucessor (destroy) — um
                        // release atrasado de A com B no comando não libera B.
                        if (!canReleaseLegacy(generation)) return@execute
                        gateway.releaseModel()
                    }
                } catch (t: Throwable) {
                    logError("releaseModel falhou", t)
                } finally {
                    coordinator.unlock()
                }
            }
        } catch (t: Throwable) {
            logError("não foi possível enfileirar releaseModel", t)
        }
    }

    /** R5/A3: política de memória do modo legado. true = esta geração pode
     * liberar o contexto único do nativo. */
    private fun canReleaseLegacy(generation: Long): Boolean {
        if (isCurrent(generation)) return true
        // invalidateOwner deixou a geração global negativa e registrou QUE
        // geração era a última quando nenhum sucessor começou: só ela libera.
        return generation == lastInvalidatedGeneration && this.generation < 0L
    }

    /** Cancela a transcrição da sessão [generation] sem segurar lock algum (pode
     * ser chamada da UI thread enquanto o modelo ainda carrega em outra thread).
     * NUNCA dispara class-init/probe: se o binding do job ainda não resolveu, o
     * pedido fica REGISTRADO no job e é encaminhado ao owner quando ele nascer.
     * R3/B1: em modo owned o id é o CAPTURADO pelo solicitante — um cancelamento
     * atrasado de A chega ao nativo vinculado a A (por-dono); B não é derrubado
     * nem o pedido de A é perdido. */
    fun cancel(generation: Long): Boolean {
        try {
            val job = jobFor(generation)
            if (job == null) {
                // R5/A2: um id DESCONHECIDO (pré-beginSession, expirado ou
                // podado) NUNCA vira owner nativo por coincidência numérica — o
                // número lógico pode bater com o owner ATIVO de outra sessão.
                // Sem job não há pedido a encaminhar: no-op DIAGNOSTICADO (nem
                // anônimo: um anônimo atrasado derrubaria o job ATUAL).
                logError("cancel: geração $generation desconhecida/expirada; no-op (sem owner inventado)", null)
                return false
            }
            job.markCancelRequested()
            // R15/B2 — o ACK é "existe sessão viva para esta geração e o
            // pedido ficou registrado nela". Init pendente conta: o job guarda
            // o pedido e o encaminha ao owner quando ele nascer.
            val binding = job.binding ?: return true
            if (!job.claimCancelForward()) return true   // já encaminhado (idempotente)
            if (binding.owned) {
                if (binding.nativeOwner > 0L) gateway.cancelTranscriptionOwned(binding.nativeOwner)
            } else if (isCurrent(generation)) {
                // R4/B2: no legado NÃO há token por dono — o pedido anônimo só
                // vai ao nativo se a sessão AINDA for a atual. Um pedido tardio
                // de A (já substituída por B) morre aqui e não derruba B
                // (mesma proteção do encaminhamento pós-init).
                gateway.cancelTranscription()
            }
            return true
        } catch (t: Throwable) {
            logError("cancelTranscription falhou", t)
            return false
        }
    }

    /** R3/B2: invalida o owner [generation] no TEARDOWN, ANTES do cleanup: a
     * partir daqui posts/callbacks tardios dela morrem no [runIfCurrent]/[isCurrent]
     * mesmo que nenhuma nova sessão tenha começado (Activity destruída). Não
     * libera contexto — o release usa o token PRÓPRIO da sessão e o nativo
     * reconfere o dono, então este método não pode apagar o contexto de B. */
    fun invalidateOwner(generation: Long) {
        jobFor(generation)?.invalidated = true
        if (this.generation == generation) {
            // R5/A3: registra quem era a dona lógica quando NENHUM sucessor
            // começou — é o dado que a política de release legado usa.
            lastInvalidatedGeneration = generation
            this.generation = -generationCounter.incrementAndGet()
        }
    }

    /** Envolve um callback descartando eventos de sessão antiga. */
    fun wrapCallback(generation: Long, delegate: WhisperNative.Callback): WhisperNative.Callback {
        return object : WhisperNative.Callback {
            override fun onSegment(text: String, startMs: Long, endMs: Long) {
                if (!isCurrent(generation)) return
                delegate.onSegment(text, startMs, endMs)
            }

            override fun onProgress(progress: Int) {
                if (!isCurrent(generation)) return
                delegate.onProgress(progress)
            }

            override fun onNativeLog(line: String) {
                if (!isCurrent(generation)) return
                delegate.onNativeLog(line)
            }
        }
    }

    /** Transcreve se [generation] ainda for a atual e devolve o par (texto,
     * status) do MESMO job: o status é lido na mesma seção crítica, então
     * nenhuma operação longa de outra sessão consegue intercalar entre as duas
     * leituras. O callback é sempre envolvido por [wrapCallback]. Init FAILED
     * devolve erro EXPLÍCITO (nunca "legado" nem silêncio). */
    fun transcribeAndStatus(
        generation: Long,
        wavPath: String,
        language: String,
        beamSize: Int,
        bestOf: Int,
        wordTimestamps: Boolean,
        vadFilter: Boolean,
        vadModelPath: String,
        callback: WhisperNative.Callback
    ): Pair<String, String> {
        val job = jobFor(generation)
        if (job == null || job.invalidated || !isCurrent(generation)) {
            return Pair(STALE_RESULT_TEXT, STALE_STATUS)
        }
        val binding = awaitBindingTimeLimited(job, BINDING_WAIT_MS)
            ?: return Pair("Erro: init nativo falhou (${job.initFailure?.message ?: "sem binding"})", "error")
        coordinator.lock()
        try {
            if (!isCurrent(generation) || job.invalidated) return Pair(STALE_RESULT_TEXT, STALE_STATUS)
            val wrapped = wrapCallback(generation, callback)
            val attempt = job.nextAttempt()
            // R25/REQ — a fotografia é construída AQUI, uma vez, com a
            // identidade real (gen/job/attempt/owner) e enviada NA chamada.
            // R26/REQ — produção também transporta a fotografia: quando o seam
            // do lab devolve null, o controller constrói os DEFAULTS explícitos
            // (contexto/janela default, sem override, captura desligada).
            val pedidoLab = attemptRequestProvider(
                "job-${job.id}-att-$attempt",
                binding.nativeOwner,
                generation,
                job.id,
                attempt
            )
            val pedido = pedidoLab ?: if (binding.owned) {
                AttemptRequest(
                    token = "job-${job.id}-att-$attempt",
                    owner = binding.nativeOwner,
                    gen = generation,
                    job = job.id,
                    attempt = attempt,
                    producer = "app",
                    config = "prod-default"
                )
            } else null
            // R26/REQ — pedido CONSTRUÍDO e inválido ENCERRA: não cai em
            // inferência sem pedido (o takeIf(null) anterior fazia isso).
            val recusaLocal = pedido?.validate()
            if (pedido != null && recusaLocal != null) {
                logError("pedido da tentativa recusado: $recusaLocal", null)
                return Pair("Erro: pedido da tentativa recusado ($recusaLocal)", "error")
            }
            val pedidoValido = pedido
            val text = if (binding.owned) {
                if (pedidoValido != null) {
                    try {
                        gateway.transcribeOwnedReq(
                            wavPath, language, beamSize, bestOf, wordTimestamps, vadFilter,
                            vadModelPath, binding.nativeOwner, wrapped, pedidoValido.toWire()
                        )
                    } catch (legacy: UnsatisfiedLinkError) {
                        // R27/REQ — lib sem a entrada aditiva: só a rota legada
                        // é aceitável quando o pedido é EQUIVALENTE aos defaults
                        // de produção. Override experimental é recusado de forma
                        // tipada, SEM inferência na configuração errada.
                        if (!pedidoValido.isProdDefault()) {
                            logError(
                                "lib sem transcribeOwnedReq e pedido com override: recusado (UNSUPPORTED)",
                                legacy
                            )
                            return Pair(
                                "Erro: biblioteca sem suporte ao pedido da tentativa (UNSUPPORTED)",
                                "error"
                            )
                        }
                        logError("transcribeOwnedReq ausente; defaults seguem pela rota legada", legacy)
                        gateway.transcribeOwned(
                            wavPath, language, beamSize, bestOf, wordTimestamps, vadFilter, vadModelPath,
                            binding.nativeOwner, wrapped
                        )
                    }
                } else {
                    gateway.transcribeOwned(
                        wavPath, language, beamSize, bestOf, wordTimestamps, vadFilter, vadModelPath,
                        binding.nativeOwner, wrapped
                    )
                }
            } else {
                gateway.transcribe(
                    wavPath, language, beamSize, bestOf, wordTimestamps, vadFilter, vadModelPath, wrapped
                )
            }
            val status = try {
                if (binding.owned) gateway.lastTranscribeStatusOwned(binding.nativeOwner)
                else gateway.lastTranscribeStatus()
            } catch (t: Throwable) {
                logError("lastTranscribeStatus falhou", t)
                ""
            }
            if (status.equals(WhisperResultParser.STATUS_CANCELLED, ignoreCase = true)) {
                job.markCancelConsumed()
            }
            return Pair(text, status)
        } finally {
            coordinator.unlock()
        }
    }

    /** Compat W02: transcreve e devolve só o texto (o par completo com o status
     * do mesmo job está em [transcribeAndStatus]). */
    fun transcribe(
        generation: Long,
        wavPath: String,
        language: String,
        beamSize: Int,
        bestOf: Int,
        wordTimestamps: Boolean,
        vadFilter: Boolean,
        vadModelPath: String,
        callback: WhisperNative.Callback
    ): String = transcribeAndStatus(
        generation, wavPath, language, beamSize, bestOf, wordTimestamps, vadFilter, vadModelPath, callback
    ).first

    /** Status estruturado da última transcrição de [generation] (W10).
     * Devolve "cancelled" se a sessão já foi substituída/destruída; "" quando o
     * job ainda não resolveu o binding (sem disparar probe; teto curto, nunca
     * na UI). */
    fun lastTranscribeStatus(generation: Long): String {
        val job = jobFor(generation)
        if (job == null || job.invalidated || generation != this.generation) return STALE_STATUS
        val binding = if (job.awaitReady(STATUS_BINDING_WAIT_MS)) job.binding else null
        if (binding == null) return ""
        return try {
            if (binding.owned) gateway.lastTranscribeStatusOwned(binding.nativeOwner) else gateway.lastTranscribeStatus()
        } catch (t: Throwable) {
            logError("lastTranscribeStatus falhou", t)
            ""
        }
    }

    /** Encerra o executor de trabalho (testes/shutdown). Não bloqueia. */
    fun shutdown() {
        releaseExecutor.shutdown()
    }
}

/** Implementação real de [WhisperSessionController.NativeGateway]: delega ao
 * [WhisperNative]. Carrega o nativo só no primeiro método chamado. */
object WhisperNativeGateway : WhisperSessionController.NativeGateway {

    /**
     * R11/D2 — SEAM DE BANCADA (one-shot) da política de threads. Aplicado no
     * ÚNICO ponto por onde passa a transcrição real (este gateway), imediatamente
     * antes do job. No APK normal [WhisperLabSeams.threadsOverride] devolve 0 e
     * NADA é chamado no nativo (o símbolo nem é tocado): comportamento de
     * produção intacto. Com valor > 0 o nativo aplica o override SÓ no próximo
     * job e rotula a decisão como `lab_override`.
     */
    /**
     * R14/D2-D3 — SEAM DE BANCADA do contexto de encoder (experimental,
     * one-shot). Produção devolve 0 e o nativo nunca é tocado.
     */
    /**
     * R24/REQ — publica a FOTOGRAFIA da tentativa antes do compute (lab only).
     *
     * O nativo consome por owner (`sig_attempt::global().take`), em vez do
     * global one-shot `g_audio_ctx_window_samples`. `submit_mb`/`k`/`serial`
     * ficam 0 aqui (= "sem override da tentativa"); quem os define no lab é o
     * `setDiagEnv` de config, que é processo-global e continua sendo o elo
     * fraco declarado.
     */
    internal fun publishAttemptOpts(owner: Long, windowSamples: Long) {
        if (!WhisperLabSeams.isLab() || owner <= 0L) return
        val r = runCatching {
            WhisperNative.publishAttemptOpts(owner, 1L, windowSamples, 0, 0, 0)
        }.getOrDefault(-1)
        android.util.Log.i("WhisperSessions",
            "attempt_opts publish owner=$owner attempt=1 window=$windowSamples retorno=$r")
    }

    internal fun applyLabAudioCtxOverride(owner: Long = 0L) {
        // R17/E2 — produção não toca no nativo: sem o flavor de bancada, sai
        // antes de qualquer peek/set (o símbolo nem é resolvido).
        if (!WhisperLabSeams.isLab()) return
        val requested = runCatching { WhisperLabSeams.audioCtxOverride() }.getOrDefault(0)
        // peek do que o nativo carrega AGORA: é o que revela um legado de outro
        // dono (a R14 herdava esse valor em silêncio).
        val nativeNow = runCatching { WhisperNative.audioCtxOverridePeek() }.getOrDefault(0)
        // R24/REQ — fotografia por tentativa publicada ANTES do transcribe.
        val winParaFoto = runCatching { WhisperLabSeams.audioCtxWindowSamples() }.getOrDefault(0L)
        publishAttemptOpts(owner, winParaFoto)
        when (val d = AudioCtxSeam.decide(requested, nativeNow)) {
            is AudioCtxSeam.Decision -> when (d.action) {
                AudioCtxSeam.Action.APPLY -> {
                    val applied = runCatching { WhisperNative.setAudioCtxOverrideFor(owner, d.value) }
                    val aceito = applied.getOrNull() ?: -1
                    if (aceito <= 0) {
                        // R20/C1 — recusa TIPADA (tabela cheia, owner invalido,
                        // contencao). Nao se anuncia como aplicado.
                        android.util.Log.w(
                            "WhisperSessions",
                            "lab_audio_ctx RECUSADO pedido=${d.value} retorno=$aceito owner=$owner " +
                                "nativo_antes=$nativeNow"
                        )
                    } else {
                        android.util.Log.i(
                            "WhisperSessions",
                            "lab_audio_ctx ${d.motivo} pedido=${d.value} aplicado=$aceito " +
                                "nativo_antes=$nativeNow"
                        )
                    }
                }
                AudioCtxSeam.Action.CLEAR -> {
                    // R20/C1 — descarte pelo DONO (nao um CLEAR global): o
                    // setter sem dono virou no-op e nao pode limpar o pedido de
                    // outra geracao.
                    val limpo = runCatching { WhisperNative.cancelAudioCtxRequest(owner) }
                    android.util.Log.i(
                        "WhisperSessions",
                        "lab_audio_ctx ${d.motivo} nativo_antes=$nativeNow descartado=$limpo owner=$owner"
                    )
                }
                AudioCtxSeam.Action.NONE -> Unit
            }
        }
    }

    private fun applyLabThreadsOverride() {
        val requested = runCatching { WhisperLabSeams.threadsOverride() }.getOrDefault(0)
        if (requested <= 0) return
        runCatching { WhisperNative.setThreadsOverride(requested) }
            .onFailure { android.util.Log.w("WhisperSessions", "seam de threads indisponivel: $it") }
    }

    override fun loadModel(modelPath: String, backendKind: Int, flashAttention: Boolean): Boolean =
        WhisperNative.loadModel(modelPath, backendKind, flashAttention)

    override fun transcribe(
        wavPath: String,
        language: String,
        beamSize: Int,
        bestOf: Int,
        wordTimestamps: Boolean,
        vadFilter: Boolean,
        vadModelPath: String,
        callback: WhisperNative.Callback
    ): String {
        applyLabThreadsOverride()
        // R18/E1 — o audio_ctx é aplicado UMA vez, pelo caminho com dono
        // (transcribeAndStatus). Chamar aqui sem dono gerava CLEAR imediato.
        return WhisperNative.transcribe(
            wavPath, language, beamSize, bestOf, wordTimestamps, vadFilter, vadModelPath, callback
        )
    }

    override fun cancelTranscription() = WhisperNative.cancelTranscription()
    override fun beginSession(): Long = WhisperNative.beginSession()
    override fun releaseModel() = WhisperNative.releaseModel()
    override fun lastTranscribeStatus(): String = WhisperNative.lastTranscribeStatus()

    // R2/B1: API owned — em lib antiga lança UnsatisfiedLinkError; o controller
    // detecta no job de init e permanece no modo legado.
    override fun ownershipApiVersion(): Int = WhisperNative.ownershipApiVersion()
    override fun beginSessionOwned(): Long = WhisperNative.beginSessionOwned()
    override fun loadModelOwned(modelPath: String, backendKind: Int, flashAttention: Boolean, owner: Long): Boolean =
        WhisperNative.loadModelOwned(modelPath, backendKind, flashAttention, owner)
    override fun releaseModelOwned(owner: Long): Boolean = WhisperNative.releaseModelOwned(owner)
    override fun transcribeOwned(
        wavPath: String,
        language: String,
        beamSize: Int,
        bestOf: Int,
        wordTimestamps: Boolean,
        vadFilter: Boolean,
        vadModelPath: String,
        owner: Long,
        callback: WhisperNative.Callback
    ): String {
        applyLabThreadsOverride()
        // R18/E1 — ÚNICO ponto de aplicação: a rota OWNED, que conhece o dono.
        // Aplicar também em transcribeAndStatus gerava CLEAR imediato (o valor
        // era consumido e a segunda chamada o via como "legado").
        applyLabAudioCtxOverride(owner)
        return WhisperNative.transcribeOwned(
            wavPath, language, beamSize, bestOf, wordTimestamps, vadFilter, vadModelPath, owner, callback
        )
    }

    /**
     * R25/REQ — rota com a fotografia na chamada. O pedido já carrega
     * contexto/janela/política/captura: NADA é reaplicado aqui (reaplicar
     * criaria duas fontes e o consumo duplo que a revisão reprovou).
     */
    override fun transcribeOwnedReq(
        wavPath: String,
        language: String,
        beamSize: Int,
        bestOf: Int,
        wordTimestamps: Boolean,
        vadFilter: Boolean,
        vadModelPath: String,
        owner: Long,
        callback: WhisperNative.Callback,
        request: String
    ): String {
        applyLabThreadsOverride()
        return WhisperNative.transcribeOwnedReq(
            wavPath, language, beamSize, bestOf, wordTimestamps, vadFilter, vadModelPath,
            owner, callback, request
        )
    }
    override fun cancelTranscriptionOwned(owner: Long) = WhisperNative.cancelTranscriptionOwned(owner)
    override fun lastTranscribeStatusOwned(owner: Long): String = WhisperNative.lastTranscribeStatusOwned(owner)
    override fun lastError(): String = WhisperNative.lastError()
    override fun lastLoadLog(): String = WhisperNative.lastLoadLog()
    override fun assumeContextOwned(modelPath: String, backendKind: Int, flashAttention: Boolean, owner: Long): Boolean =
        WhisperNative.assumeContextOwned(modelPath, backendKind, flashAttention, owner)

    // R8/A2: em lib antiga lança UnsatisfiedLinkError; o controller chama em
    // runCatching (marcador é diagnóstico best-effort).
    override fun faJobBegin(route: String): Boolean = WhisperNative.faJobBegin(route)
}

/** Singleton de produção usado pela WhisperActivity. Os testes constroem
 * instâncias próprias de [WhisperSessionController] com gateway fake. */
object WhisperSessions {
    val shared: WhisperSessionController = WhisperSessionController(
        gateway = WhisperNativeGateway,
        logError = { message, throwable -> android.util.Log.e("WhisperSessions", message, throwable) }
    )
}
