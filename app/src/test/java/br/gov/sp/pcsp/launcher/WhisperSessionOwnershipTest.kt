package br.gov.sp.pcsp.launcher

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** R2/B2 — ownership por sessão no WhisperSessionController.
 *
 * Cada invariante roda em duas fases, sem sleeps nem esperas probabilísticas:
 *  - (a) emula a SEMÂNTICA LEGADA (release/cancel sem dono, status global,
 *    checagem-antes-do-nativo) e DEMONSTRA a violação;
 *  - (b) com o contrato owned (v2) + coordenador do controller, prova que a
 *    violação não acontece.
 * Toda ordenação usa CountDownLatch; o fake bloqueia em pontos exatos para o
 * interleaving ser determinístico.
 */
class WhisperSessionOwnershipTest {

    /** Emulação do nativo compartilhado (g_ctx + g_mutex + g_cancel_mutex +
     * g_status_*). `ownedApi=false` reproduz o defeito: release e cancel não
     * conhecem dono. `ownedApi=true` implementa o contrato R2/B1. */
    private class NativeEmulation(private val ownedApi: Boolean) :
        WhisperSessionController.NativeGateway {

        private val nativeLock = Object()   // "g_mutex": serializa load/transcribe/release
        private val cancelLock = Object()   // "g_cancel_mutex"
        private val statusLock = Object()   // "g_status_mutex"

        @Volatile private var ctxOwnerInternal = 0L
        @Volatile var ctxAlive = false
        @Volatile private var requested = false
        @Volatile private var cancelOwnerInternal = 0L
        @Volatile private var activeOwner = 0L
        private val statusByOwner = ConcurrentHashMap<Long, String>()

        @Volatile var globalStatus = "ok"

        /** Status que o "motor" devolve no próximo transcribe. */
        @Volatile var statusForJobs = "ok"

        /** Contagem de terminais por dono ("um único terminal por job"). */
        val terminalsByOwner = ConcurrentHashMap<Long, AtomicInteger>()

        val releaseInvocations = AtomicInteger(0)

        @Volatile var releaseSawCtxOwner = -1L

        // Pontos de bloqueio/latch (null = não bloqueia)
        @Volatile var claimStarted: CountDownLatch? = null
        @Volatile var claimHold: CountDownLatch? = null
        @Volatile var releaseAttempted: CountDownLatch? = null
        @Volatile var releaseFinished: CountDownLatch? = null
        @Volatile var transcribeStarted: CountDownLatch? = null
        @Volatile var engineFinished: CountDownLatch? = null
        @Volatile var transcribeHold: CountDownLatch? = null
        @Volatile var statusReadStarted: CountDownLatch? = null
        @Volatile var statusReadHold: CountDownLatch? = null
        private val statusReadHoldOnce = AtomicBoolean(false)

        val ctxOwner: Long get() = ctxOwnerInternal
        val cancelRequested: Boolean get() = requested
        val cancelOwner: Long get() = cancelOwnerInternal

        // ---------------- load ----------------

        override fun loadModel(modelPath: String, backendKind: Int, flashAttention: Boolean): Boolean {
            loadInternal(0L) // legado: sem dono
            return true
        }

        override fun loadModelOwned(modelPath: String, backendKind: Int, flashAttention: Boolean, owner: Long): Boolean {
            loadInternal(owner)
            return true
        }

        private fun loadInternal(owner: Long) {
            synchronized(nativeLock) {
                claimStarted?.countDown()
                claimHold?.await(5, TimeUnit.SECONDS)
                ctxOwnerInternal = owner
                ctxAlive = true
            }
        }

        // ---------------- release ----------------

        override fun releaseModel() {
            // legado: libera o g_ctx global SEM verificar dono
            releaseInvocations.incrementAndGet()
            releaseAttempted?.countDown()
            synchronized(nativeLock) {
                releaseSawCtxOwner = ctxOwnerInternal
                ctxAlive = false
                ctxOwnerInternal = 0L
            }
            releaseFinished?.countDown()
        }

        override fun releaseModelOwned(owner: Long): Boolean {
            releaseInvocations.incrementAndGet()
            releaseAttempted?.countDown()
            val freed = synchronized(nativeLock) {
                releaseSawCtxOwner = ctxOwnerInternal
                if (ctxAlive && owner != 0L && ctxOwnerInternal == owner) {
                    ctxAlive = false
                    ctxOwnerInternal = 0L
                    true
                } else {
                    false
                }
            }
            releaseFinished?.countDown()
            return freed
        }

        // ---------------- cancel ----------------

        override fun cancelTranscription() {
            // legado: anônimo (sem dono) — atinge qualquer inferência
            synchronized(cancelLock) {
                requested = true
                cancelOwnerInternal = 0L
            }
        }

        override fun cancelTranscriptionOwned(owner: Long) {
            synchronized(cancelLock) {
                requested = true
                cancelOwnerInternal = if (owner > 0L) owner else 0L
            }
        }

        private fun consumeCancelFor(owner: Long): Boolean = synchronized(cancelLock) {
            if (!requested) {
                false
            } else if (cancelOwnerInternal == 0L || cancelOwnerInternal == owner) {
                requested = false
                cancelOwnerInternal = 0L
                true
            } else {
                false
            }
        }

        // ---------------- sessão ----------------

        private var legacySessionCounter = 0L
        private var ownedSessionCounter = 0L

        /** Último owner nativo emitido por beginSessionOwned (id de sessão). */
        fun lastOwnedOwner(): Long = ownedSessionCounter

        override fun beginSession(): Long {
            // legado: limpa QUALQUER cancelamento pendente ("sem vínculo")
            synchronized(cancelLock) {
                requested = false
                cancelOwnerInternal = 0L
            }
            return ++legacySessionCounter
        }

        override fun beginSessionOwned(): Long {
            val id = ++ownedSessionCounter
            synchronized(cancelLock) {
                // limpa SOMENTE cancelamento anônimo ocioso
                if (cancelOwnerInternal == 0L && activeOwner == 0L) {
                    requested = false
                }
            }
            return id
        }

        // ---------------- transcribe ----------------

        override fun transcribe(
            wavPath: String,
            language: String,
            beamSize: Int,
            bestOf: Int,
            wordTimestamps: Boolean,
            vadFilter: Boolean,
            vadModelPath: String,
            callback: WhisperNative.Callback
        ): String = transcribeInternal(0L)

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
        ): String = transcribeInternal(owner)

        private fun transcribeInternal(owner: Long): String {
            synchronized(nativeLock) {
                activeOwner = owner
                try {
                    transcribeStarted?.countDown()
                    if (consumeCancelFor(owner)) {
                        return terminal("cancelled", "Cancelado: transcrição cancelada", owner)
                    }
                    engineFinished?.countDown()
                    transcribeHold?.await(5, TimeUnit.SECONDS)
                    if (consumeCancelFor(owner)) {
                        return terminal("cancelled", "Cancelado: transcrição cancelada", owner)
                    }
                    return terminal(statusForJobs, if (owner > 0L) "texto de $owner" else "texto legado", owner)
                } finally {
                    activeOwner = 0L
                }
            }
        }

        private fun terminal(status: String, text: String, owner: Long): String {
            terminalsByOwner.computeIfAbsent(owner) { AtomicInteger(0) }.incrementAndGet()
            synchronized(statusLock) {
                globalStatus = status
                statusByOwner[owner] = status
            }
            return text
        }

        // ---------------- status ----------------

        override fun lastTranscribeStatus(): String = globalStatus

        override fun lastTranscribeStatusOwned(owner: Long): String {
            val hold = statusReadHold
            if (hold != null && statusReadHoldOnce.compareAndSet(false, true)) {
                // Gancho de observação: injeta a atividade de outra sessão no
                // exato instante da leitura de status (a janela que o par
                // protegido fecha). Dispara UMA vez.
                statusReadStarted?.countDown()
                hold.await(5, TimeUnit.SECONDS)
            }
            return statusByOwner[owner] ?: ""
        }

        override fun ownershipApiVersion(): Int {
            if (!ownedApi) throw UnsatisfiedLinkError("ownershipApiVersion ausente (lib legada)")
            return 2
        }
    }

    private val executors = mutableListOf<ExecutorService>()

    @After
    fun tearDown() {
        executors.forEach { it.shutdownNow() }
        executors.clear()
    }

    private fun newExecutor(name: String): ExecutorService =
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, name) }.also { executors += it }

    private fun controllerWith(
        gateway: WhisperSessionController.NativeGateway,
        executor: ExecutorService = newExecutor("whisper-owned-test"),
        prewarm: () -> Unit = { }
    ): WhisperSessionController = WhisperSessionController(
        gateway = gateway,
        releaseExecutor = executor,
        logError = { _, _ -> },
        nativePrewarm = prewarm
    )

    /** Tarefa sentinela: o executor single-thread garante que a fila anterior
     * foi processada quando ela roda. */
    private fun drain(executor: ExecutorService, timeoutSeconds: Long = 5L) {
        val done = CountDownLatch(1)
        executor.execute { done.countDown() }
        assertTrue("executor não esvaziou em ${timeoutSeconds}s", done.await(timeoutSeconds, TimeUnit.SECONDS))
    }

    private fun noopCallback() = object : WhisperNative.Callback {
        override fun onSegment(text: String, startMs: Long, endMs: Long) {}
        override fun onProgress(progress: Int) {}
        override fun onNativeLog(line: String) {}
    }

    private fun transcribePair(controller: WhisperSessionController, generation: Long): Pair<String, String> =
        controller.transcribeAndStatus(
            generation, "a.wav", "pt", 1, 1, false, false, "", noopCallback()
        )

    // =========================================================================
    // 1) Release A espera (latch) enquanto B inicia/carrega; B NÃO pode ter o
    //    contexto liberado.
    // =========================================================================

    @Test
    fun `invariante 1 - release atrasado de A nunca libera o contexto de B`() {
        // (a) LEGADO: release sem dono libera o g_ctx que B reivindicou (violação).
        run {
            val native = NativeEmulation(ownedApi = false)
            val controllerA = controllerWith(native)
            val genA = controllerA.beginSession()
            assertTrue(controllerA.loadModel(genA, "a.gguf", 0, false))
            assertTrue(native.ctxAlive)

            // B carrega outro modelo direto no nativo (outra superfície do mesmo
            // g_ctx) e segura o "g_mutex" durante o load.
            val claimStarted = CountDownLatch(1)
            val claimHold = CountDownLatch(1)
            native.claimStarted = claimStarted
            native.claimHold = claimHold
            val loaderB = Thread { native.loadModel("b.gguf", 0, false) }
            loaderB.start()
            assertTrue(claimStarted.await(3, TimeUnit.SECONDS))

            // Release de A passa a checagem de geração e espera o "g_mutex".
            val releaseAttempted = CountDownLatch(1)
            val releaseFinished = CountDownLatch(1)
            native.releaseAttempted = releaseAttempted
            native.releaseFinished = releaseFinished
            controllerA.releaseAsync(genA)
            assertTrue(releaseAttempted.await(3, TimeUnit.SECONDS))

            claimHold.countDown() // B termina de carregar e solta o g_mutex
            loaderB.join(3000)
            assertTrue(releaseFinished.await(3, TimeUnit.SECONDS))

            assertFalse("legado viola: release atrasado de A liberou o contexto de B", native.ctxAlive)
        }

        // (b) OWNED: o release de A espera o mesmo latch, observa o contexto de B
        // e recusa liberar (owner mismatch).
        run {
            val native = NativeEmulation(ownedApi = true)
            val controllerA = controllerWith(native)
            val controllerB = controllerWith(native)
            val genA = controllerA.beginSession()
            assertTrue(controllerA.loadModel(genA, "a.gguf", 0, false))
            assertEquals(genA, native.ctxOwner)

            val claimStarted = CountDownLatch(1)
            val claimHold = CountDownLatch(1)
            native.claimStarted = claimStarted
            native.claimHold = claimHold
            val genB = controllerB.beginSession()
            val loaderB = Thread { controllerB.loadModel(genB, "b.gguf", 0, false) }
            loaderB.start()
            assertTrue(claimStarted.await(3, TimeUnit.SECONDS))

            val releaseAttempted = CountDownLatch(1)
            val releaseFinished = CountDownLatch(1)
            native.releaseAttempted = releaseAttempted
            native.releaseFinished = releaseFinished
            controllerA.releaseAsync(genA)
            assertTrue(releaseAttempted.await(3, TimeUnit.SECONDS))

            claimHold.countDown()
            loaderB.join(3000)
            val ownerB = native.ctxOwner   // dono NATIVO emitido para a 2ª sessão (B)
            assertTrue(releaseFinished.await(3, TimeUnit.SECONDS))

            assertTrue("owned: B não pode perder o contexto por um release de A", native.ctxAlive)
            assertEquals("o contexto continua sendo o de B", ownerB, native.ctxOwner)
            assertEquals("o release de A observou o contexto de B e recusou", ownerB, native.releaseSawCtxOwner)
        }
    }

    // =========================================================================
    // 2) Cancelamento atrasado de A chega durante B: A consome o seu, B não é
    //    cancelado e o pedido de A não é limpo prematuramente.
    // =========================================================================

    @Test
    fun `invariante 2 - cancelamento atrasado de A não cancela B nem é limpo cedo`() {
        // (a) LEGADO: cancel() sem dono derruba a sessão que estiver rodando (B).
        run {
            val native = NativeEmulation(ownedApi = false)
            val controllerA = controllerWith(native)
            val execB = newExecutor("owned-test-b")
            val controllerB = controllerWith(native, execB)
            val genA = controllerA.beginSession()
            assertEquals("texto legado", transcribePair(controllerA, genA).first)

            val genB = controllerB.beginSession()
            // B já resolveu o binding nativo ANTES do cancelamento atrasado de A
            // (determinismo: sem isso, o beginSession de B poderia limpar o
            // cancelamento anônimo depois de A emiti-lo — corrida de init).
            drain(execB)
            controllerA.cancel(genA) // cancelamento atrasado de A (id capturado por A)

            val pairB = transcribePair(controllerB, genB)
            assertEquals("legado viola: o cancelamento de A derrubou a transcrição de B", "cancelled", pairB.second)
            assertTrue(pairB.first.startsWith("Cancelado"))
        }

        // (b) OWNED: cancelamento com dono; a fronteira de sessão de B não apaga
        // o pedido de A, que só o próprio A consome; B segue intacto.
        run {
            val native = NativeEmulation(ownedApi = true)
            val controllerA = controllerWith(native)
            val controllerB = controllerWith(native)
            val genA = controllerA.beginSession()

            val transcribeStarted = CountDownLatch(1)
            val engineFinished = CountDownLatch(1)
            val transcribeHold = CountDownLatch(1)
            native.transcribeStarted = transcribeStarted
            native.engineFinished = engineFinished
            native.transcribeHold = transcribeHold
            val pairA = arrayOfNulls<Pair<String, String>>(1)
            val workerA = Thread { pairA[0] = transcribePair(controllerA, genA) }
            workerA.start()
            assertTrue(transcribeStarted.await(3, TimeUnit.SECONDS))
            assertTrue(engineFinished.await(3, TimeUnit.SECONDS))

            controllerA.cancel(genA) // chega atrasado: depois do motor, antes da barreira final
            assertEquals(genA, native.cancelOwner)

            val genB = controllerB.beginSession()
            assertTrue("fronteira de sessão de B não pode apagar o cancelamento de A", native.cancelRequested)
            assertEquals(genA, native.cancelOwner)

            transcribeHold.countDown()
            workerA.join(3000)
            assertEquals("cancelled", pairA[0]?.second)
            assertFalse("o cancelamento de A foi consumido pelo próprio A", native.cancelRequested)
            assertEquals(1, native.terminalsByOwner[genA]?.get() ?: 0)

            val pairB = transcribePair(controllerB, genB)
            val ownerB = native.lastOwnedOwner()
            assertEquals("texto de $ownerB", pairB.first)
            assertEquals("ok", pairB.second)
        }
    }

    // =========================================================================
    // 3) Callback/post de A agendado; B inicia antes do post rodar → o gate
    //    runIfCurrent impede o efeito na UI de B.
    // =========================================================================

    @Test
    fun `invariante 3 - post agendado de A não afeta a UI depois de B iniciar`() {
        // (a) PADRÃO LEGADO: o filtro roda no AGENDAMENTO (wrapCallback no
        // evento); o post já aprovado executa depois e aplica o efeito com B ativo.
        run {
            val native = NativeEmulation(ownedApi = false)
            val controller = controllerWith(native)
            val genA = controller.beginSession()
            assertTrue("pré-condição: A é a sessão corrente no agendamento", controller.isCurrent(genA))

            val uiEffects = AtomicInteger(0)
            val allowPost = CountDownLatch(1)
            val poster = Thread {
                allowPost.await(5, TimeUnit.SECONDS)
                uiEffects.incrementAndGet() // post sem re-checagem (padrão antigo)
            }
            poster.start()
            controller.beginSession() // B inicia antes de o post rodar
            allowPost.countDown()
            poster.join(3000)
            assertEquals("legado deixa o post obsoleto afetar a UI", 1, uiEffects.get())
        }

        // (b) CORRIGIDO: runIfCurrent re-checa na EXECUÇÃO e bloqueia o post obsoleto.
        run {
            val native = NativeEmulation(ownedApi = true)
            val controller = controllerWith(native)
            val genA = controller.beginSession()

            val uiEffects = AtomicInteger(0)
            val allowPost = CountDownLatch(1)
            val poster = Thread {
                allowPost.await(5, TimeUnit.SECONDS)
                controller.runIfCurrent(genA) { uiEffects.incrementAndGet() }
            }
            poster.start()
            controller.beginSession() // B inicia antes de o post rodar
            allowPost.countDown()
            poster.join(3000)
            assertEquals("post obsoleto não pode afetar a UI", 0, uiEffects.get())

            // o gate não é um bloqueio cego: a sessão corrente ainda executa
            assertTrue(controller.runIfCurrent(controller.currentGeneration()) { uiEffects.incrementAndGet() })
            assertEquals(1, uiEffects.get())
        }
    }

    // =========================================================================
    // 4) Texto e status entregues a A pertencem ao MESMO job (par protegido).
    // =========================================================================

    @Test
    fun `invariante 4 - texto e status entregues a A pertencem ao mesmo job`() {
        // (a) LEGADO: duas chamadas separadas — A recebe o status GLOBAL que B
        // acabou de alterar (par desalinhado).
        run {
            val native = NativeEmulation(ownedApi = false)
            val controllerA = controllerWith(native)
            val controllerB = controllerWith(native)
            val genA = controllerA.beginSession()
            native.statusForJobs = "ok"
            val textA = controllerA.transcribe(genA, "a.wav", "pt", 1, 1, false, false, "", noopCallback())

            val genB = controllerB.beginSession()
            native.statusForJobs = "no_speech"
            transcribePair(controllerB, genB)

            val statusForA = controllerA.lastTranscribeStatus(genA) // leitura separada, sem dono
            assertEquals("texto legado", textA)
            assertEquals("legado viola: status do job de B entregue junto do texto de A", "no_speech", statusForA)
        }

        // (b) OWNED: B roda inteiro (altera o status global) DURANTE a leitura do
        // status de A; ainda assim o par de A continua sendo o de A.
        run {
            val native = NativeEmulation(ownedApi = true)
            val controllerA = controllerWith(native)
            val controllerB = controllerWith(native)
            val genA = controllerA.beginSession()
            native.statusForJobs = "ok"
            val statusReadStarted = CountDownLatch(1)
            val statusReadHold = CountDownLatch(1)
            native.statusReadStarted = statusReadStarted
            native.statusReadHold = statusReadHold

            val pairA = arrayOfNulls<Pair<String, String>>(1)
            val workerA = Thread { pairA[0] = transcribePair(controllerA, genA) }
            workerA.start()
            assertTrue("A já produziu o texto e está lendo o status", statusReadStarted.await(3, TimeUnit.SECONDS))

            val genB = controllerB.beginSession()
            native.statusForJobs = "no_speech"
            val pairB = transcribePair(controllerB, genB)
            assertEquals("no_speech", pairB.second)
            assertEquals("no_speech", native.globalStatus)

            statusReadHold.countDown()
            workerA.join(3000)
            assertEquals("texto de $genA", pairA[0]?.first)
            assertEquals("o status do par de A continua sendo o de A", "ok", pairA[0]?.second)
            assertNotEquals(native.globalStatus, pairA[0]?.second)
        }
    }

    // =========================================================================
    // 5) Duas 'Activities' com controller compartilhado: B reivindica outro
    //    modelo; o release de A vira inofensivo (geração + owner mismatch).
    // =========================================================================

    @Test
    fun `invariante 5 - release de A é inofensivo quando B reivindica outro modelo`() {
        // (a) LEGADO: release de A (preso no g_mutex) libera o contexto de B.
        run {
            val native = NativeEmulation(ownedApi = false)
            val shared = controllerWith(native)
            val genA = shared.beginSession()
            assertTrue(shared.loadModel(genA, "a.gguf", 0, false))

            val claimStarted = CountDownLatch(1)
            val claimHold = CountDownLatch(1)
            native.claimStarted = claimStarted
            native.claimHold = claimHold
            val loaderB = Thread { native.loadModel("b.gguf", 0, false) }
            loaderB.start()
            assertTrue(claimStarted.await(3, TimeUnit.SECONDS))

            val releaseAttempted = CountDownLatch(1)
            val releaseFinished = CountDownLatch(1)
            native.releaseAttempted = releaseAttempted
            native.releaseFinished = releaseFinished
            shared.releaseAsync(genA)
            assertTrue(releaseAttempted.await(3, TimeUnit.SECONDS))

            claimHold.countDown()
            loaderB.join(3000)
            assertTrue(releaseFinished.await(3, TimeUnit.SECONDS))
            assertFalse("legado viola: release de A liberou o modelo reivindicado por B", native.ctxAlive)
        }

        // (b) OWNED: com o controller compartilhado, (b1) o release de A chega ao
        // nativo com o TOKEN PRÓPRIO (R3/B2) e vira no-op no dono errado; (b2)
        // mesmo forçado, o dono errado não libera nada.
        run {
            val native = NativeEmulation(ownedApi = true)
            val executor = newExecutor("owned-shared-release")
            val shared = controllerWith(native, executor)
            val genA = shared.beginSession()
            assertTrue(shared.loadModel(genA, "a.gguf", 0, false))
            assertEquals(genA, native.ctxOwner)

            // (b1) release de A fica preso na fila do executor; B inicia e
            // reivindica outro modelo; ao rodar, o nativo reconfere o dono e
            // NÃO libera o contexto de B (o gate global não decide mais).
            val releaseGate = CountDownLatch(1)
            executor.execute { releaseGate.await(5, TimeUnit.SECONDS) }
            shared.releaseAsync(genA)
            val genB = shared.beginSession()
            assertTrue(shared.loadModel(genB, "b.gguf", 0, false))
            assertEquals(genB, native.ctxOwner)
            releaseGate.countDown()
            drain(executor)
            assertEquals("R3/B2: release de A chega ao nativo com o token próprio", 1, native.releaseInvocations.get())
            assertEquals("...e o dono errado não libera nada", genB, native.ctxOwner)
            assertTrue(native.ctxAlive)
            assertTrue(native.ctxAlive)
            assertEquals(genB, native.ctxOwner)

            // (b2) forçando o release de A ao nativo: owner mismatch => no-op.
            assertFalse(native.releaseModelOwned(genA))
            assertTrue(native.ctxAlive)
            assertEquals(genB, native.ctxOwner)

            // e o release da própria B continua funcionando
            shared.releaseAsync(genB)
            drain(executor)
            assertFalse(native.ctxAlive)
        }
    }

    // =========================================================================
    // 6) Worker antigo falha/termina no exato instante do cancelamento: um único
    //    terminal por job, sem apagar o estado da sessão nova.
    // =========================================================================

    @Test
    fun `invariante 6 - worker antigo termina no instante do cancelamento sem corromper a sessão nova`() {
        // (a) LEGADO: o cancelamento atrasado de A (anônimo) derruba a sessão de B.
        run {
            val native = NativeEmulation(ownedApi = false)
            val controllerA = controllerWith(native)
            val execB6 = newExecutor("owned-test6-b")
            val controllerB = controllerWith(native, execB6)
            val genA = controllerA.beginSession()
            assertEquals("ok", transcribePair(controllerA, genA).second)

            val genB = controllerB.beginSession()
            // Determinismo (R4): B resolve o binding nativo ANTES do cancel de A.
            drain(execB6)
            controllerA.cancel(genA) // chega "no exato instante" com o id capturado
            val pairB = transcribePair(controllerB, genB)
            assertEquals("legado viola: a sessão nova terminou cancelada pelo pedido de A", "cancelled", pairB.second)
        }

        // (b) OWNED
        run {
            val native = NativeEmulation(ownedApi = true)
            val controllerA = controllerWith(native)
            val controllerB = controllerWith(native)

            // Variante 1: cancel(A) chega no exato instante em que o motor termina —
            // A consome o SEU cancelamento e termina UMA única vez.
            val genA1 = controllerA.beginSession()
            val transcribeStarted = CountDownLatch(1)
            val engineFinished = CountDownLatch(1)
            val transcribeHold = CountDownLatch(1)
            native.transcribeStarted = transcribeStarted
            native.engineFinished = engineFinished
            native.transcribeHold = transcribeHold
            val pairA1 = arrayOfNulls<Pair<String, String>>(1)
            val workerA = Thread { pairA1[0] = transcribePair(controllerA, genA1) }
            workerA.start()
            assertTrue(transcribeStarted.await(3, TimeUnit.SECONDS))
            assertTrue(engineFinished.await(3, TimeUnit.SECONDS))
            controllerA.cancel(genA1)
            transcribeHold.countDown()
            workerA.join(3000)
            assertEquals("cancelled", pairA1[0]?.second)
            assertEquals("um único terminal para o job de A", 1, native.terminalsByOwner[genA1]?.get() ?: 0)
            assertFalse("o cancelamento de A foi consumido por A", native.cancelRequested)

            // Variante 2: A termina antes (ok) e o cancelamento de A chega depois,
            // com B rodando; B não é cancelado, o pedido de A não é apagado pela
            // fronteira de sessão de B e o próprio cancelamento de B continua valendo.
            val genA2 = controllerA.beginSession()
            native.statusForJobs = "ok"
            assertEquals("ok", transcribePair(controllerA, genA2).second)
            assertEquals(1, native.terminalsByOwner[genA2]?.get() ?: 0)

            val genB = controllerB.beginSession()
            controllerA.cancel(genA2) // atrasado: a sessão de A já terminou
            assertEquals(genA2, native.cancelOwner)
            assertTrue(native.cancelRequested)

            val pairB = transcribePair(controllerB, genB)
            val ownerB = native.lastOwnedOwner()
            assertEquals("texto de $ownerB", pairB.first)
            assertEquals("B não pode terminar cancelado por um pedido de A", "ok", pairB.second)
            assertEquals(1, native.terminalsByOwner[ownerB]?.get() ?: 0)
            assertTrue("o cancelamento de A não é consumido pelo job de B", native.cancelRequested)
            assertEquals(genA2, native.cancelOwner)

            controllerB.cancel(genB) // agora o pedido é DE B
            val pairB2 = transcribePair(controllerB, genB)
            assertEquals("cancelled", pairB2.second)
            assertEquals("cada job tem exatamente um terminal", 2, native.terminalsByOwner[ownerB]?.get() ?: 0)
            assertFalse(native.cancelRequested)
        }
    }

    // =========================================================================
    // Extra: prewarm nativo (class-load fora da UI) no primeiro beginSession.
    // =========================================================================

    @Test
    fun `prewarm nativo roda uma vez no primeiro beginSession e fora da thread chamadora`() {
        val native = NativeEmulation(ownedApi = true)
        val executor = newExecutor("whisper-owned-prewarm")
        val prewarmRuns = AtomicInteger(0)
        val prewarmDone = CountDownLatch(1)
        val prewarmThread = arrayOfNulls<String>(1)
        val controller = controllerWith(native, executor) {
            prewarmRuns.incrementAndGet()
            prewarmThread[0] = Thread.currentThread().name
            prewarmDone.countDown()
        }

        val genA = controller.beginSession()
        assertTrue("prewarm não rodou em 3s", prewarmDone.await(3, TimeUnit.SECONDS))
        assertEquals(1, prewarmRuns.get())
        assertEquals("whisper-owned-prewarm", prewarmThread[0])
        assertTrue(genA > 0L)

        controller.beginSession() // segunda sessão NÃO repete o prewarm
        drain(executor)
        assertEquals(1, prewarmRuns.get())
    }
}
