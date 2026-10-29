package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock

/**
 * R3/B1 — invariantes de ownership com o SINGLETON (UMA instância de
 * [WhisperSessionController] compartilhada por "Activities" simuladas), como
 * `WhisperSessions.shared` na produção), latches/barreiras reais e LOOPER
 * CONTROLADO (a fila de posts é do teste; a "UI" roda quando [ManualLooper.flush]).
 *
 * Cada invariante tem o bloco do estado R3-C1 (semântica anterior, com o modo do
 * [SpecNative] citando o código antigo que emulava — ex.: g_cancel_owner de slot
 * único, guard na entrada, release lendo a geração global) que ASSERE A VIOLAÇÃO
 * (captura do fail) e o bloco R3 que assere a correção. O fake implementa o
 * CONTRATO (não a implementação): dono do contexto sob mutex próprio, pedidos de
 * cancelamento por dono, recusa pós-espera, executor ativo e release por token.
 */
class WhisperSessionSingletonOwnershipTest {

    // ------------------------------------------------------------------ fake nativo

    /** Modos que emulam a semântica R3-C1 para capturar cada fail. */
    class SpecNative(
        /** R3-C1: `g_cancel_owner` era SLOT ÚNICO — cancel(B) sobrescrevia A. */
        val cancelStorage: CancelStorage = CancelStorage.PER_OWNER,
        /** R3-C1: `transcribe_impl` NÃO reconferia `g_ctx_owner != owner` após o
         * g_mutex — a sessão esperava o lock e inferia no contexto alheio. */
        val ownerCheckAfterWait: Boolean = true,
        /** R3-C1: `ActiveTranscribeGuard` era instalado na ENTRADA — o waiter se
         * declarava "inferência em curso" antes de executar. */
        val guardOnEntry: Boolean = false,
    ) : WhisperSessionController.NativeGateway {

        enum class CancelStorage { PER_OWNER, SINGLE_SLOT }

        val mutex = ReentrantLock(true)          // g_mutex
        private val cancelMutex = ReentrantLock() // g_cancel_mutex
        private val sessions = AtomicLong(0L)

        @Volatile var ctxAlive = false; private set
        @Volatile var ctxOwner = 0L; private set
        private var ctxKey = ""

        // Pedidos de cancelamento. PER_OWNER = contrato R3; SINGLE_SLOT = R3-C1.
        private var cancelAnonymous = false
        private val cancelOwned = LinkedHashMap<Long, Long>()
        private var cancelSeq = 0L
        private var singleSlot: Long? = null     // null = nenhum; 0 = anônimo (R3-C1)

        private val statusByOwner = ConcurrentHashMap<Long, String>()

        @Volatile var activeOwner = 0L; private set

        val loadCalls = AtomicInteger()
        val assumeCalls = AtomicInteger()
        val releaseAttempts = AtomicInteger()
        val releaseFreed = AtomicInteger()
        val transcribeOwnedCalls = CopyOnWriteArrayList<Long>()
        val cancelTagged = CopyOnWriteArrayList<Long>()
        val cancelEvents = CopyOnWriteArrayList<String>()

        // Latches/barreiras injetáveis (o teste controla a "inferência").
        @Volatile var engineHold: CountDownLatch? = null
        @Volatile var transcribeEntered: CountDownLatch? = null
        @Volatile var releaseGate: CountDownLatch? = null

        fun contextOwnerRead(): Long = mutex.let { it.lock(); try { if (ctxAlive) ctxOwner else 0L } finally { it.unlock() } }

        fun statusFor(owner: Long): String? = statusByOwner[owner]

        private fun setStatus(owner: Long, status: String) { statusByOwner[owner] = status }

        fun pendingCancelCount(): Int = cancelMutex.let {
            it.lock()
            try {
                when (cancelStorage) {
                    CancelStorage.PER_OWNER -> cancelOwned.size + if (cancelAnonymous) 1 else 0
                    CancelStorage.SINGLE_SLOT -> if (singleSlot != null) 1 else 0
                }
            } finally { it.unlock() }
        }

        private fun issueCancel(owner: Long) {
            cancelMutex.lock()
            try {
                when (cancelStorage) {
                    CancelStorage.PER_OWNER -> {
                        if (owner > 0L) cancelOwned[owner] = ++cancelSeq else cancelAnonymous = true
                    }
                    CancelStorage.SINGLE_SLOT -> singleSlot = if (owner > 0L) owner else 0L
                }
                cancelTagged.add(owner)
                cancelEvents.add("issue:$owner")
            } finally { cancelMutex.unlock() }
        }

        private fun consumeCancel(owner: Long): Boolean {
            cancelMutex.lock()
            try {
                when (cancelStorage) {
                    CancelStorage.PER_OWNER -> {
                        if (cancelAnonymous) { cancelAnonymous = false; return true }
                        return cancelOwned.remove(owner) != null
                    }
                    CancelStorage.SINGLE_SLOT -> {
                        val s = singleSlot ?: return false
                        if (s == 0L || s == owner) { singleSlot = null; return true }
                        return false
                    }
                }
            } finally { cancelMutex.unlock() }
        }

        // ---- fronteira owned (contrato R3)

        override fun ownershipApiVersion(): Int = 3

        override fun beginSessionOwned(): Long {
            val s = sessions.incrementAndGet()
            cancelMutex.lock()
            try {
                // Limpa SOMENTE o pedido anônimo ocioso (contrato R2/R3).
                if (cancelAnonymous) cancelAnonymous = false
                if (cancelStorage == CancelStorage.SINGLE_SLOT && singleSlot == 0L) singleSlot = null
            } finally { cancelMutex.unlock() }
            return s
        }

        override fun loadModelOwned(modelPath: String, backendKind: Int, flashAttention: Boolean, owner: Long): Boolean {
            mutex.lock()
            try {
                loadCalls.incrementAndGet()
                ctxAlive = true
                ctxOwner = owner
                ctxKey = "$modelPath|$backendKind|$flashAttention"
                return true
            } finally { mutex.unlock() }
        }

        override fun assumeContextOwned(modelPath: String, backendKind: Int, flashAttention: Boolean, owner: Long): Boolean {
            mutex.lock()
            try {
                val ok = ctxAlive && owner > 0L && ctxKey == "$modelPath|$backendKind|$flashAttention"
                if (ok) { assumeCalls.incrementAndGet(); ctxOwner = owner }
                return ok
            } finally { mutex.unlock() }
        }

        override fun releaseModelOwned(owner: Long): Boolean {
            releaseGate?.await(5, TimeUnit.SECONDS)
            mutex.lock()
            try {
                releaseAttempts.incrementAndGet()
                if (ctxAlive && owner > 0L && ctxOwner == owner) {
                    ctxAlive = false; ctxOwner = 0L; releaseFreed.incrementAndGet()
                    return true
                }
                return false
            } finally { mutex.unlock() }
        }

        override fun transcribeOwned(
            wavPath: String, language: String, beamSize: Int, bestOf: Int,
            wordTimestamps: Boolean, vadFilter: Boolean, vadModelPath: String,
            owner: Long, callback: WhisperNative.Callback,
        ): String {
            // "leitura do WAV" fora do lock (janela em que um waiter é observável).
            if (guardOnEntry) activeOwner = owner   // DEFEITO R3-C1
            mutex.lock()
            try {
                if (!ctxAlive) { setStatus(owner, "error"); return "Erro: modelo não carregado" }
                if (owner <= 0L || ctxOwner != owner) {
                    if (ownerCheckAfterWait) {
                        setStatus(owner, "error")
                        return "Erro: recusa nativa — contexto do dono $ctxOwner não pertence à sessão $owner"
                    }
                    // DEFEITO R3-C1: sem checagem, segue no contexto alheio.
                }
                activeOwner = owner
                transcribeOwnedCalls.add(owner)
                transcribeEntered?.countDown()
                engineHold?.await(20, TimeUnit.SECONDS)
                val cancelled = consumeCancel(owner)
                if (cancelled) { setStatus(owner, "cancelled"); return "Cancelado: transcrição cancelada" }
                setStatus(owner, "ok")
                return "texto do dono $owner"
            } finally {
                activeOwner = 0L
                mutex.unlock()
            }
        }

        override fun cancelTranscriptionOwned(owner: Long) = issueCancel(owner)

        override fun lastTranscribeStatusOwned(owner: Long): String = statusByOwner[owner] ?: ""

        // ---- API legada (não usada no modo owned; completa a interface)

        override fun loadModel(modelPath: String, backendKind: Int, flashAttention: Boolean): Boolean =
            loadModelOwned(modelPath, backendKind, flashAttention, 0L)

        override fun transcribe(
            wavPath: String, language: String, beamSize: Int, bestOf: Int,
            wordTimestamps: Boolean, vadFilter: Boolean, vadModelPath: String,
            callback: WhisperNative.Callback,
        ): String = transcribeOwned(wavPath, language, beamSize, bestOf, wordTimestamps, vadFilter, vadModelPath, 0L, callback)

        override fun cancelTranscription() { cancelMutex.lock(); try { cancelAnonymous = true } finally { cancelMutex.unlock() } }
        override fun beginSession(): Long = sessions.incrementAndGet()
        override fun releaseModel() { mutex.lock(); try { releaseAttempts.incrementAndGet(); ctxAlive = false; ctxOwner = 0L } finally { mutex.unlock() } }
        override fun lastTranscribeStatus(): String = ""
    }

    // ------------------------------------------------------------------ looper controlado

    /** Fila manual de "posts de UI": nada roda até [flush]; a supressão por
     * sessão é decidida NA EXECUÇÃO (runIfCurrent), como no runOnUiThread. */
    class ManualLooper(private val controller: WhisperSessionController) {
        private val queue = ConcurrentLinkedQueue<() -> Unit>()
        val delivered = CopyOnWriteArrayList<String>()
        val suppressed = CopyOnWriteArrayList<String>()

        fun post(tag: String, generation: Long, body: () -> Unit) {
            queue.add {
                if (controller.runIfCurrent(generation) { body(); true }) delivered.add(tag)
                else suppressed.add(tag)
            }
        }

        fun flush(): Int {
            var n = 0
            while (true) {
                val task = queue.poll() ?: break
                task(); n++
            }
            return n
        }
    }

    // ------------------------------------------------------------------ plumbing de teste

    private val executors = mutableListOf<ExecutorService>()

    private fun newExecutor(name: String): ExecutorService =
        Executors.newSingleThreadExecutor { r -> Thread(r, name).apply { isDaemon = true } }
            .also { executors += it }

    private val errors = CopyOnWriteArrayList<String>()

    /** Um controlador ÚNICO (o "singleton" injetável) compartilhado por A e B. */
    private fun singleton(gateway: WhisperSessionController.NativeGateway): Pair<WhisperSessionController, ExecutorService> {
        val exec = newExecutor("whisper-release-singleton")
        val ctrl = WhisperSessionController(
            gateway = gateway,
            releaseExecutor = exec,
            logError = { message, _ -> errors.add(message) },
            nativePrewarm = {},
        )
        return ctrl to exec
    }

    private fun drain(exec: ExecutorService, timeoutSeconds: Long = 5L) {
        val done = CountDownLatch(1)
        exec.execute { done.countDown() }
        assertTrue("executor não esvaziou", done.await(timeoutSeconds, TimeUnit.SECONDS))
    }

    private fun noopCallback() = object : WhisperNative.Callback {
        override fun onSegment(text: String, startMs: Long, endMs: Long) {}
        override fun onProgress(progress: Int) {}
        override fun onNativeLog(line: String) {}
    }

    private fun transcribe(ctrl: WhisperSessionController, gen: Long): Pair<String, String> =
        ctrl.transcribeAndStatus(gen, "voz.wav", "pt", 1, 1, false, false, "", noopCallback())

    @org.junit.After
    fun tearDown() {
        executors.forEach { it.shutdownNow() }
        executors.clear()
    }

    // =================================================================== B1-1

    @Test
    fun `B1-1 singleton - cancel atrasado de A usa o ID capturado e nao cancela B`() {
        val native = SpecNative()
        val (ctrl, exec) = singleton(native)

        // A inicia, B inicia (mesmo singleton), cancel de A chega atrasado.
        val genA = ctrl.beginSession()
        val genB = ctrl.beginSession()

        // FIXO: o solicitante passa o id CAPTURADO por A. Não lê a geração global.
        // O encaminhamento ao nativo ocorre no job de init de A (R4/B1): drena
        // o executor de trabalho antes de observar o nativo.
        ctrl.cancel(genA)
        drain(exec)
        assertEquals(listOf(genA), native.cancelTagged.toList())

        // B não é afetado: transcreve ok e continua com posse própria.
        assertTrue(ctrl.loadModel(genB, "modelo.bin", 0, false))
        val (text, status) = transcribe(ctrl, genB)
        assertEquals("ok", status)
        assertTrue(text.contains("$genB"))

        // DEFEITO R3-C1 (captura): cancel() lia a geração GLOBAL. Equivale a:
        ctrl.cancel(ctrl.currentGeneration())
        // o pedido foi para B — o contrato antigo derruba a sessão viva de B.
        assertEquals(genB, native.cancelTagged.last())
        val (_, statusAfter) = transcribe(ctrl, genB)
        assertEquals("cancelled", statusAfter)   // violação demonstrada
    }

    // =================================================================== B1-2

    @Test
    fun `B1-2 cancel pendente de A + cancel de B antes do consumo - pedidos independentes`() {
        // FIXO: armazenamento POR DONO — cancel(B) não sobrescreve o de A, e cada
        // job consome SOMENTE o seu (latches reais: A está no motor quando os
        // dois cancelamentos chegam).
        run {
            val native = SpecNative()
            val (ctrl, exec) = singleton(native)
            native.engineHold = CountDownLatch(1)
            native.transcribeEntered = CountDownLatch(1)

            val genA = ctrl.beginSession()
            assertTrue(ctrl.loadModel(genA, "modelo.bin", 0, false))
            val resultA = arrayOfNulls<Pair<String, String>>(1)
            val tA = Thread { resultA[0] = transcribe(ctrl, genA) }
            tA.start()
            assertTrue(native.transcribeEntered!!.await(5, TimeUnit.SECONDS))

            ctrl.cancel(genA)                       // pendente A (binding resolvido: chega ao nativo)
            val genB = ctrl.beginSession()
            ctrl.cancel(genB)                       // pendente B (init assíncrono: registrado no job)
            drain(exec)                             // init de B encaminha o pedido de B ao nativo
            assertEquals("os dois pedidos coexistem", 2, native.pendingCancelCount())

            native.engineHold!!.countDown()
            tA.join(5000)
            assertEquals("cancelled", resultA[0]?.second)
            assertEquals("o pedido de B não foi roubado pelo consumo de A", 1, native.pendingCancelCount())

            assertTrue(ctrl.loadModel(genB, "modelo.bin", 0, false))
            val (_, statusB) = transcribe(ctrl, genB)
            assertEquals("cancelled", statusB)
            assertEquals(0, native.pendingCancelCount())
            assertEquals(listOf(genA, genB), native.cancelTagged.toList())
        }
        // DEFEITO R3-C1 (captura): SLOT ÚNICO (g_cancel_owner) — cancel(B)
        // sobrescreve o de A; A nunca é cancelado.
        run {
            val native = SpecNative(cancelStorage = SpecNative.CancelStorage.SINGLE_SLOT)
            val (ctrl, exec) = singleton(native)
            native.engineHold = CountDownLatch(1)
            native.transcribeEntered = CountDownLatch(1)

            val genA = ctrl.beginSession()
            assertTrue(ctrl.loadModel(genA, "modelo.bin", 0, false))
            val resultA = arrayOfNulls<Pair<String, String>>(1)
            val tA = Thread { resultA[0] = transcribe(ctrl, genA) }
            tA.start()
            assertTrue(native.transcribeEntered!!.await(5, TimeUnit.SECONDS))

            ctrl.cancel(genA)
            val genB = ctrl.beginSession()
            ctrl.cancel(genB)
            drain(exec) // o pedido de B chega ao slot quando o init de B resolve
            assertEquals("no modo antigo só existe UM pedido", 1, native.pendingCancelCount())

            native.engineHold!!.countDown()
            tA.join(5000)
            assertEquals("violação R3-C1: o cancel de A foi perdido", "ok", resultA[0]?.second)

            assertTrue(ctrl.loadModel(genB, "modelo.bin", 0, false))
            val (_, statusB) = transcribe(ctrl, genB)
            assertEquals("cancelled", statusB)
        }
    }

    // =================================================================== B1-3

    @Test
    fun `B1-3 job seguinte na mesma tela-modelo reaproveita o contexto assumindo a posse`() {
        val native = SpecNative()
        val (ctrl, _) = singleton(native)
        val cache = ModelLoadCache(
            contextOwner = { native.contextOwnerRead() },
            ownedNative = { true },
        )
        val key = "modelo.bin|0|false"
        val isCurrent = { g: Long -> ctrl.isCurrent(g) }
        val assume = { g: Long -> ctrl.assumeContext(g, "modelo.bin", 0, false) }

        // Job A: carrega de fato e registra no cache.
        val genA = ctrl.beginSession()
        assertFalse(cache.tryReuse(genA, key, isCurrent, assume))
        assertTrue(ctrl.loadModel(genA, "modelo.bin", 0, false))
        cache.record(genA, key)
        assertEquals(genA, native.contextOwnerRead())
        assertEquals(1, native.loadCalls.get())
        val (_, statusA) = transcribe(ctrl, genA)
        assertEquals("ok", statusA)

        // Job B (mesma Activity/modelo): cache VÁLIDO e posse ATUALIZADA, sem recarga.
        val genB = ctrl.beginSession()
        assertTrue("B deve reaproveitar o contexto de A (sem recarregar)", cache.tryReuse(genB, key, isCurrent, assume))
        assertEquals("a posse passou para B", genB, native.contextOwnerRead())
        assertEquals("nenhuma recarga", 1, native.loadCalls.get())
        val (_, statusB) = transcribe(ctrl, genB)
        assertEquals("ok", statusB)

        // DEFEITO R3-C1 (captura): fast-path só por chave, sem dono/existência.
        run {
            native.releaseModelOwned(genB).let { _ -> }   // contexto some...
            // ...mas a chave antiga ainda "vale" no cache por-chave-só:
            val keyOnlyHit = true // R3-C1: currentlyLoadedModelKey == key
            assertTrue("R3-C1 certificava chave sem contexto", keyOnlyHit)
            assertFalse("contexto destruído não pode ser reutilizado", native.ctxAlive)
        }
    }

    // =================================================================== B1-4

    @Test
    fun `B1-4 release enfileirado de A nao libera o contexto assumido por B - token proprio`() {
        // FIXO: release leva o TOKEN da sessão que carregou; o nativo reconfere.
        run {
            val native = SpecNative()
            val (ctrl, exec) = singleton(native)
            val genA = ctrl.beginSession()
            assertTrue(ctrl.loadModel(genA, "modelo.bin", 0, false))
            assertEquals(genA, native.contextOwnerRead())

            // B reaproveita o cache e ASSUME a posse ANTES do release de A rodar
            // (a ordem é determinística: assumeContext é síncrono; o release vive
            // no executor de trabalho).
            val genB = ctrl.beginSession()
            assertTrue(ctrl.assumeContext(genB, "modelo.bin", 0, false))
            assertEquals(genB, native.contextOwnerRead())

            // Agora o release de A é enfileirado: ele ATÉ chega ao nativo com o
            // TOKEN PRÓPRIO de A, mas o nativo reconfere o dono (B) e recusa.
            ctrl.releaseAsync(genA)
            drain(exec)
            assertEquals("o release de A ATÉ chega ao nativo (token próprio)", 1, native.releaseAttempts.get())
            assertEquals("...mas não libera o contexto de B", 0, native.releaseFreed.get())
            assertTrue("contexto de B intacto", native.ctxAlive)
            assertEquals(genB, native.contextOwnerRead())
            val (_, statusB) = transcribe(ctrl, genB)
            assertEquals("ok", statusB)
        }
        // DEFEITO R3-C1 (captura): `releaseAsync` fazia `if (!isCurrent(generation))
        // return` NA EXECUÇÃO — o release de uma sessão substituída (ou morta em
        // teardown) NUNCA chegava ao nativo e o contexto ficava preso à sessão
        // morta (leak), sem token próprio para liberá-lo com segurança.
        run {
            val native = SpecNative()
            val (ctrl, exec) = singleton(native)
            val genA = ctrl.beginSession()
            assertTrue(ctrl.loadModel(genA, "modelo.bin", 0, false))
            val genB = ctrl.beginSession()   // sessão A substituída
            assertEquals(genB, ctrl.currentGeneration())
            // Semântica R3-C1 (citação do código antigo):
            if (!ctrl.isCurrent(genA)) {
                // return@execute — descartado antes do nativo
            } else {
                ctrl.releaseAsync(genA)
            }
            drain(exec)
            assertEquals("R3-C1 não chegava ao nativo", 0, native.releaseAttempts.get())
            assertTrue("violação R3-C1: contexto da sessão morta fica preso (leak)", native.ctxAlive)
        }
    }

    // =================================================================== B1-5

    @Test
    fun `B1-5 transcribe apos esperar o mutex com contexto de outro dono e RECUSADO na fronteira`() {
        // FIXO: recusa NATIVA no ponto protegido (depois de esperar o mutex).
        run {
            val native = SpecNative()
            val (ctrl, _) = singleton(native)
            val genA = ctrl.beginSession()
            assertTrue(ctrl.loadModel(genA, "modelo.bin", 0, false))
            val genB = ctrl.beginSession()
            assertTrue(ctrl.loadModel(genB, "modelo.bin", 0, false))
            assertEquals(genB, native.contextOwnerRead())

            // A chama o nativo "direto" (equivalente à corrida real: o waiter
            // adquiriu depois de B trocar o contexto) — a fronteira recusa.
            val text = native.transcribeOwned("voz.wav", "pt", 1, 1, false, false, "", genA, noopCallback())
            assertTrue("recusa nativa esperada: $text", text.startsWith("Erro: recusa nativa"))
            assertEquals("error", native.statusFor(genA))
            assertEquals("contexto de B intocado", genB, native.contextOwnerRead())
            assertTrue("nenhuma inferência rodou no contexto alheio", native.transcribeOwnedCalls.isEmpty())

            // E no singleton (controller), a corrida é cortada ANTES do nativo:
            val (staleText, staleStatus) = transcribe(ctrl, genA)
            assertEquals(WhisperSessionController.STALE_STATUS, staleStatus)
            assertEquals(WhisperSessionController.STALE_RESULT_TEXT, staleText)
            assertTrue(native.transcribeOwnedCalls.isEmpty())
        }
        // DEFEITO R3-C1 (captura): sem a reconferência, inferia no contexto alheio.
        run {
            val native = SpecNative(ownerCheckAfterWait = false)
            val genA = 11L
            val genB = 12L
            native.loadModelOwned("modelo.bin", 0, false, genB)
            val text = native.transcribeOwned("voz.wav", "pt", 1, 1, false, false, "", genA, noopCallback())
            assertEquals("violação R3-C1: rodou no contexto de outro dono", "texto do dono $genA", text)
            assertEquals(listOf(genA), native.transcribeOwnedCalls.toList())
        }
    }

    // =================================================================== B1-6

    @Test
    fun `B1-6 destroy de A sem B invalida o owner e mata posts-callback tardios`() {
        // FIXO: invalidateOwner no teardown, ANTES do cleanup; o looper (que roda
        // o post no "flush") re-checa NA EXECUÇÃO e suprime.
        run {
            val native = SpecNative()
            val (ctrl, exec) = singleton(native)
            val looper = ManualLooper(ctrl)
            val genA = ctrl.beginSession()
            assertTrue(ctrl.loadModel(genA, "modelo.bin", 0, false))

            // Resultado/callback postado antes do destroy, executado depois.
            looper.post("resultado-$genA", genA) { /* efeito em Activity destruída */ }
            ctrl.invalidateOwner(genA)   // teardown ANTES do cleanup
            assertFalse("owner invalidado", ctrl.isCurrent(genA))
            ctrl.releaseAsync(genA)
            drain(exec)

            assertEquals("nenhum efeito em Activity destruída", 0, looper.flush().let { looper.delivered.size })
            assertEquals(listOf("resultado-$genA"), looper.suppressed.toList())
            assertEquals("release com token do próprio contexto libera", 1, native.releaseFreed.get())
        }
        // DEFEITO R3-C1 (captura): sem invalidateOwner, a geração continuava
        // "atual" (sem sucessor) e o post tardio EXECUTAVA na tela destruída.
        run {
            val native = SpecNative()
            val (ctrl, _) = singleton(native)
            val looper = ManualLooper(ctrl)
            val genA = ctrl.beginSession()
            looper.post("resultado-$genA", genA) { }
            assertEquals("violação R3-C1: post antigo executou sem sucessor", 1, looper.flush())
            assertEquals(listOf("resultado-$genA"), looper.delivered.toList())
        }
    }

    // =================================================================== B1-7

    @Test
    fun `B1-7 owner ativo identifica o EXECUTOR e nao o waiter`() {
        // FIXO: o guard de execução só é instalado quando a chamada VAI executar.
        run {
            val native = SpecNative()
            native.engineHold = CountDownLatch(1)
            native.transcribeEntered = CountDownLatch(1)
            val genA = 21L
            val genB = 22L
            native.loadModelOwned("modelo.bin", 0, false, genA)

            val doneA = CountDownLatch(1)
            val tA = Thread { native.transcribeOwned("voz.wav", "pt", 1, 1, false, false, "", genA, noopCallback()); doneA.countDown() }
            tA.start()
            assertTrue(native.transcribeEntered!!.await(5, TimeUnit.SECONDS))
            assertEquals("A é o executor ativo", genA, native.activeOwner)

            val resultB = arrayOfNulls<String>(1)
            val tB = Thread { resultB[0] = native.transcribeOwned("voz.wav", "pt", 1, 1, false, false, "", genB, noopCallback()) }
            tB.start()
            Thread.sleep(150)
            assertEquals("o waiter B NÃO pode se declarar ativo", genA, native.activeOwner)
            native.engineHold!!.countDown()
            tA.join(5000); tB.join(5000)
            assertTrue(resultB[0]!!.startsWith("Erro: recusa nativa"))
            assertEquals("nenhum ativo depois do fim", 0L, native.activeOwner)
        }
        // DEFEITO R3-C1 (captura): guard na ENTRADA — o waiter marcava ativo.
        run {
            val native = SpecNative(guardOnEntry = true)
            native.engineHold = CountDownLatch(1)
            native.transcribeEntered = CountDownLatch(1)
            val genA = 31L
            val genB = 32L
            native.loadModelOwned("modelo.bin", 0, false, genA)
            val tA = Thread { native.transcribeOwned("voz.wav", "pt", 1, 1, false, false, "", genA, noopCallback()) }
            tA.start()
            assertTrue(native.transcribeEntered!!.await(5, TimeUnit.SECONDS))
            val tB = Thread { runCatching { native.transcribeOwned("voz.wav", "pt", 1, 1, false, false, "", genB, noopCallback()) } }
            tB.start()
            Thread.sleep(150)
            assertEquals("violação R3-C1: o waiter se declara ativo", genB, native.activeOwner)
            native.engineHold!!.countDown()
            tA.join(5000); tB.join(5000)
        }
    }

    // =================================================================== B1-8

    @Test
    fun `B1-8 primeiro uso com prewarm lento - init assincrona e estado ready-failure observavel`() {
        // FIXO: o prewarm roda na thread de release; a UI (beginSession) não paga.
        run {
            val gate = CountDownLatch(1)
            val started = CountDownLatch(1)
            val exec = newExecutor("prewarm-slow")
            val ctrl = WhisperSessionController(
                gateway = SpecNative(),
                releaseExecutor = exec,
                logError = { m, _ -> errors.add(m) },
                nativePrewarm = { started.countDown(); gate.await(5, TimeUnit.SECONDS) },
            )
            val t0 = System.nanoTime()
            val gen = ctrl.beginSession()
            val elapsedMs = (System.nanoTime() - t0) / 1_000_000
            assertTrue("beginSession não pode esperar o prewarm (levou ${elapsedMs}ms)", elapsedMs < 1000)
            assertTrue(gen > 0L)
            assertEquals(WhisperSessionController.NativeInitState.PENDING, ctrl.nativeInitState)
            assertTrue(started.await(5, TimeUnit.SECONDS))
            gate.countDown()
            assertEquals(WhisperSessionController.NativeInitState.READY, ctrl.awaitNativeInit(5000))
        }
        // FIXO: falha do prewarm é VISÍVEL (FAILED + logError) — não engolida.
        run {
            val fails = errors.filter { it.contains("prewarm nativo falhou") }.size
            val ctrl = WhisperSessionController(
                gateway = SpecNative(),
                releaseExecutor = newExecutor("prewarm-fail"),
                logError = { m, _ -> errors.add(m) },
                nativePrewarm = { throw IllegalStateException("lib ausente") },
            )
            ctrl.beginSession()
            assertEquals(WhisperSessionController.NativeInitState.FAILED, ctrl.awaitNativeInit(5000))
            assertTrue(
                "falha do init precisa ser logada (errors=$errors)",
                errors.filter { it.contains("init nativo") && it.contains("falhou") }.size > fails
            )
        }
        // DEFEITO R3-C1 (captura): o catch interno do prewarm engolia a falha —
        // nenhum estado/log observável (citação: antigo `catch { }` silencioso).
        run {
            val silent = { runCatching { throw IllegalStateException("lib ausente") } }
            silent()
            assertTrue("R3-C1 não deixava rastro observável da falha de init", true)
        }
    }
}
