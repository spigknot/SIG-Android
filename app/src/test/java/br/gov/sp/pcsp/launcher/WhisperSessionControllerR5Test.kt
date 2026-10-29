package br.gov.sp.pcsp.launcher

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** R5/A2+A3 — regressões de cancel SEM id inventado, release atrasado/
 * legado e destroy durante inferência. Fakes com latches: nada de sleep
 * arbitrário; single-thread executor como em produção. */
class WhisperSessionControllerR5Test {

    /** Gateway OWNED (v3) — owners emitidos pela própria lib fake; todo método
     * conta a chamada e o token usado. */
    private open class OwnedFakeGateway : WhisperSessionController.NativeGateway {
        val calls = AtomicInteger(0)
        val beginOwnedCalls = AtomicInteger(0)
        val cancelOwnedCalls = CopyOnWriteArrayList<Long>()
        val cancelAnonymousCalls = AtomicInteger(0)
        val releaseOwnedCalls = CopyOnWriteArrayList<Long>()
        val releaseLegacyCalls = AtomicInteger(0)
        val loadOwnedCalls = CopyOnWriteArrayList<Long>()
        val transcribeOwnedCalls = CopyOnWriteArrayList<Long>()
        val transcribeLegacyCalls = AtomicInteger(0)
        val assumeOwnedCalls = CopyOnWriteArrayList<Long>()
        val lastErrorCalls = AtomicInteger(0)
        val lastLoadLogCalls = AtomicInteger(0)
        val logged = CopyOnWriteArrayList<String>()

        /** one-shot: força o próximo owner emitido por beginSessionOwned. */
        @Volatile var nextOwner: Long? = null
        @Volatile var beginOwnedFails = false
        @Volatile var cancelOwnedThrows = false
        @Volatile var loadOwnedResult = true
        @Volatile var lastErrorValue = "erro nativo sintético"
        @Volatile var lastLoadLogValue = "log de carga sintético"
        @Volatile var status = "ok"
        @Volatile var transcribeResult = "texto"

        /** Estado do contexto nativo simulado (owner do ctx carregado). */
        @Volatile var ctxOwner = 0L
        val ctxFreeCount = AtomicInteger(0)

        @Volatile var transcribeEntered: CountDownLatch? = null
        @Volatile var transcribeBlock: CountDownLatch? = null

        override fun ownershipApiVersion(): Int { calls.incrementAndGet(); return 3 }

        override fun beginSessionOwned(): Long {
            calls.incrementAndGet()
            if (beginOwnedFails) throw RuntimeException("init owned falhou")
            beginOwnedCalls.incrementAndGet()
            val forced = nextOwner
            if (forced != null) { nextOwner = null; return forced }
            return 1000L + beginOwnedCalls.get()
        }

        override fun loadModelOwned(modelPath: String, backendKind: Int, flashAttention: Boolean, owner: Long): Boolean {
            calls.incrementAndGet()
            loadOwnedCalls += owner
            return loadOwnedResult
        }

        override fun transcribeOwned(
            wavPath: String, language: String, beamSize: Int, bestOf: Int,
            wordTimestamps: Boolean, vadFilter: Boolean, vadModelPath: String, owner: Long,
            callback: WhisperNative.Callback
        ): String {
            calls.incrementAndGet()
            transcribeOwnedCalls += owner
            transcribeEntered?.countDown()
            transcribeBlock?.await(5, TimeUnit.SECONDS)
            return transcribeResult
        }

        override fun cancelTranscriptionOwned(owner: Long) {
            calls.incrementAndGet()
            if (cancelOwnedThrows) throw RuntimeException("cancel owned falhou")
            cancelOwnedCalls += owner
        }

        override fun lastTranscribeStatusOwned(owner: Long): String { calls.incrementAndGet(); return status }

        override fun assumeContextOwned(modelPath: String, backendKind: Int, flashAttention: Boolean, owner: Long): Boolean {
            calls.incrementAndGet()
            assumeOwnedCalls += owner
            ctxOwner = owner
            return true
        }

        override fun releaseModelOwned(owner: Long): Boolean {
            calls.incrementAndGet()
            releaseOwnedCalls += owner
            return if (ctxOwner == owner) { ctxOwner = 0L; ctxFreeCount.incrementAndGet(); true } else false
        }

        override fun lastError(): String { calls.incrementAndGet(); lastErrorCalls.incrementAndGet(); return lastErrorValue }

        override fun lastLoadLog(): String { calls.incrementAndGet(); lastLoadLogCalls.incrementAndGet(); return lastLoadLogValue }

        // legado (compat) — não deve ser tocado quando a lib é OWNED
        override fun loadModel(modelPath: String, backendKind: Int, flashAttention: Boolean): Boolean { calls.incrementAndGet(); return true }
        override fun transcribe(
            wavPath: String, language: String, beamSize: Int, bestOf: Int,
            wordTimestamps: Boolean, vadFilter: Boolean, vadModelPath: String, callback: WhisperNative.Callback
        ): String { calls.incrementAndGet(); transcribeLegacyCalls.incrementAndGet(); return transcribeResult }
        override fun cancelTranscription() { calls.incrementAndGet(); cancelAnonymousCalls.incrementAndGet() }
        override fun beginSession(): Long { calls.incrementAndGet(); return 0L }
        override fun releaseModel() { calls.incrementAndGet(); releaseLegacyCalls.incrementAndGet() }
        override fun lastTranscribeStatus(): String { calls.incrementAndGet(); return status }
    }

    /** Gateway LEGADO (sem a API owned): ownershipApiVersion lança. */
    private open class LegacyFakeGateway : WhisperSessionController.NativeGateway {
        val releaseCount = AtomicInteger(0)
        val cancelCount = AtomicInteger(0)
        val transcribeCount = AtomicInteger(0)

        override fun loadModel(modelPath: String, backendKind: Int, flashAttention: Boolean): Boolean = true
        override fun transcribe(
            wavPath: String, language: String, beamSize: Int, bestOf: Int,
            wordTimestamps: Boolean, vadFilter: Boolean, vadModelPath: String, callback: WhisperNative.Callback
        ): String { transcribeCount.incrementAndGet(); return "texto" }
        override fun cancelTranscription() { cancelCount.incrementAndGet() }
        override fun beginSession(): Long = 1L
        override fun releaseModel() { releaseCount.incrementAndGet() }
        override fun lastTranscribeStatus(): String = "ok"
    }

    private val executors = mutableListOf<ExecutorService>()

    @After
    fun tearDown() {
        executors.forEach { it.shutdownNow() }
        executors.clear()
    }

    private fun newExecutor(name: String): ExecutorService =
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, name) }
            .also { executors += it }

    private fun controller(
        gateway: WhisperSessionController.NativeGateway,
        logged: MutableList<String>? = null,
        executor: ExecutorService = newExecutor("whisper-r5-test")
    ): WhisperSessionController = WhisperSessionController(
        gateway = gateway,
        releaseExecutor = executor,
        logError = { message, _ -> logged?.add(message) },
        nativePrewarm = { }
    )

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

    private fun transcribe(controller: WhisperSessionController, generation: Long): String =
        controller.transcribe(generation, "a.wav", "pt", 1, 1, false, false, "", noopCallback())

    // ------------------------------------------------------------------
    // R5/A2-1: id lógico != id nativo — cancel usa SÓ o nativeOwner do binding.
    // ------------------------------------------------------------------

    @Test
    fun `a2-1 cancel usa o nativeOwner do binding e nunca a geracao logica`() {
        val gateway = OwnedFakeGateway()
        val executor = newExecutor("whisper-r5-test")
        val controller = controller(gateway, executor = executor)
        gateway.nextOwner = 901L
        val generation = controller.beginSession()
        drain(executor)
        assertEquals(901L, controller.bindingOwnerOf(generation))
        assertTrue(generation != 901L)

        controller.cancel(generation)

        assertEquals(listOf(901L), gateway.cancelOwnedCalls.toList())
        assertEquals(0, gateway.cancelAnonymousCalls.get())
    }

    // ------------------------------------------------------------------
    // R5/A2-2: id desconhecido cujo NÚMERO coincide com o nativeOwner ativo
    // de B não pode tocar B (sem id inventado).
    // ------------------------------------------------------------------

    @Test
    fun `a2-2 cancel de id desconhecido nao toca owner ativo de B mesmo com numero igual`() {
        val gateway = OwnedFakeGateway()
        val executor = newExecutor("whisper-r5-test")
        val controller = controller(gateway, executor = executor)
        // 1..99: gerações com owners 1001..1099; a 100ª (B) reemite o owner 2 —
        // o MESMO número que o id lógico 2 (podado pela retenção de 64).
        repeat(99) { controller.beginSession() }
        drain(executor)
        gateway.nextOwner = 2L
        val genB = controller.beginSession()
        drain(executor)
        assertEquals(2L, controller.bindingOwnerOf(genB))
        assertFalse(controller.isCurrent(2L))

        controller.cancel(2L)          // id expirado/podado: NÃO pode virar token

        assertEquals("cancel de id desconhecido tocou owner ativo de B", emptyList<Long>(), gateway.cancelOwnedCalls.toList())
        assertEquals(0, gateway.cancelAnonymousCalls.get())
        // B segue intacto e cancelável pelo PRÓPRIO binding:
        controller.cancel(genB)
        assertEquals(listOf(2L), gateway.cancelOwnedCalls.toList())
    }

    // ------------------------------------------------------------------
    // R5/A2-3: >64 gerações intercalando invalidateOwner — A sai da retenção;
    // cancel antigo não atinge sucessor.
    // ------------------------------------------------------------------

    @Test
    fun `a2-3 mais de 64 geracoes - cancel antigo apos poda nao atinge sucessor`() {
        val gateway = OwnedFakeGateway()
        val executor = newExecutor("whisper-r5-test")
        val controller = controller(gateway, executor = executor)
        val genA = controller.beginSession()
        drain(executor)
        assertEquals(1001L, controller.bindingOwnerOf(genA))
        controller.cancel(genA)        // retido: encaminha pelo token real de A
        assertEquals(listOf(1001L), gateway.cancelOwnedCalls.toList())

        var last = genA
        for (i in 2..80) {
            last = controller.beginSession()
            if (i % 5 == 0) controller.invalidateOwner(last)
        }
        drain(executor)
        assertTrue("genA ainda retido (retenção não podou)", controller.bindingOwnerOf(genA) == 0L)
        assertFalse(controller.isCurrent(genA))

        gateway.cancelOwnedCalls.clear()
        controller.cancel(genA)        // podado: nada de owner inventado

        assertEquals("cancel antigo atingiu outro job", emptyList<Long>(), gateway.cancelOwnedCalls.toList())
        assertEquals(0, gateway.cancelAnonymousCalls.get())
        val successorOwner = controller.bindingOwnerOf(last)
        assertTrue(successorOwner > 0L)
        controller.cancel(last)
        assertEquals(listOf(successorOwner), gateway.cancelOwnedCalls.toList())
    }

    // ------------------------------------------------------------------
    // R5/A2-4: release/cancel atrasados depois de B assumir (mesma chave e
    // chaves diferentes) — nunca liberam/derrubam o contexto de B.
    // ------------------------------------------------------------------

    @Test
    fun `a2-4 release atrasado nao libera ctx de B (mesma chave)`() {
        val gateway = OwnedFakeGateway()
        val executor = newExecutor("whisper-r5-test")
        val controller = controller(gateway, executor = executor)
        val genA = controller.beginSession()
        drain(executor)
        val ownerA = controller.bindingOwnerOf(genA)
        gateway.ctxOwner = ownerA                     // A carregou o contexto

        val genB = controller.beginSession()
        drain(executor)
        val ownerB = controller.bindingOwnerOf(genB)
        assertTrue(ownerB > 0L && ownerB != ownerA)
        assertTrue(controller.assumeContext(genB, "m.bin", 0, false))   // B assume a MESMA chave
        assertEquals(ownerB, gateway.ctxOwner)

        controller.invalidateOwner(genA)
        controller.releaseAsync(genA)
        drain(executor)

        assertEquals(listOf(ownerA), gateway.releaseOwnedCalls.toList())
        assertEquals("release atrasado liberou o contexto de B", ownerB, gateway.ctxOwner)
        assertEquals(0, gateway.ctxFreeCount.get())
        assertEquals("texto", transcribe(controller, genB))             // B segue transcrevendo
        assertEquals(listOf(ownerB), gateway.transcribeOwnedCalls.toList())
    }

    @Test
    fun `a2-4b release atrasado nao libera ctx de B (chaves diferentes)`() {
        val gateway = OwnedFakeGateway()
        val executor = newExecutor("whisper-r5-test")
        val controller = controller(gateway, executor = executor)
        val genA = controller.beginSession()
        drain(executor)
        val ownerA = controller.bindingOwnerOf(genA)
        gateway.ctxOwner = ownerA

        val genB = controller.beginSession()
        drain(executor)
        val ownerB = controller.bindingOwnerOf(genB)
        gateway.ctxOwner = ownerB                     // B carregou a PRÓPRIA chave

        controller.invalidateOwner(genA)
        controller.releaseAsync(genA)
        drain(executor)

        assertEquals(ownerB, gateway.ctxOwner)
        assertEquals(0, gateway.ctxFreeCount.get())
        assertEquals(1, gateway.releaseOwnedCalls.count { it == ownerA })
    }

    @Test
    fun `a2-4c cancel atrasado apos B assumir so atinge o token de A`() {
        val gateway = OwnedFakeGateway()
        val executor = newExecutor("whisper-r5-test")
        val controller = controller(gateway, executor = executor)
        val genA = controller.beginSession()
        drain(executor)
        val ownerA = controller.bindingOwnerOf(genA)
        val genB = controller.beginSession()
        drain(executor)
        val ownerB = controller.bindingOwnerOf(genB)

        controller.invalidateOwner(genA)
        controller.cancel(genA)                       // destroy tardio de A

        assertEquals(listOf(ownerA), gateway.cancelOwnedCalls.toList())
        assertEquals(listOf(ownerB), listOf(controller.bindingOwnerOf(genB)))
        assertTrue(gateway.cancelOwnedCalls.none { it == ownerB })
    }

    // ------------------------------------------------------------------
    // R5/A2-5: cancel antes de qualquer init (gen=0 / id desconhecido) NÃO
    // carrega classe/lib nem chama o gateway na chamadora.
    // ------------------------------------------------------------------

    @Test
    fun `a2-5 cancel antes de qualquer init nao toca o gateway`() {
        val gateway = OwnedFakeGateway()
        val controller = controller(gateway)

        controller.cancel(0L)
        controller.cancel(1234L)
        controller.cancel(-7L)

        assertEquals("cancel pré-init tocou o gateway", 0, gateway.calls.get())
        assertTrue(gateway.cancelOwnedCalls.isEmpty())
        assertEquals(0, gateway.cancelAnonymousCalls.get())
    }

    // ------------------------------------------------------------------
    // R5/A2-6: falha de init / owner inválido não vira binding owned
    // aparentemente utilizável.
    // ------------------------------------------------------------------

    @Test
    fun `a2-6a falha de init nao vira binding utilizavel`() {
        val gateway = OwnedFakeGateway()
        gateway.beginOwnedFails = true
        val logged = CopyOnWriteArrayList<String>()
        val executor = newExecutor("whisper-r5-test")
        val controller = controller(gateway, logged, executor)
        val generation = controller.beginSession()
        drain(executor)

        assertEquals(0L, controller.bindingOwnerOf(generation))
        controller.cancel(generation)
        assertTrue(gateway.cancelOwnedCalls.isEmpty())
        assertEquals(0, gateway.cancelAnonymousCalls.get())
        val text = transcribe(controller, generation)
        assertTrue("transcribe deveria reportar init falho, veio: $text", text.startsWith("Erro:"))
        assertTrue(gateway.transcribeOwnedCalls.isEmpty())
        assertTrue(gateway.transcribeLegacyCalls.get() == 0)
    }

    @Test
    fun `a2-6b owner invalido (0) nao vira binding owned utilizavel`() {
        val gateway = OwnedFakeGateway()
        gateway.nextOwner = 0L
        val executor = newExecutor("whisper-r5-test")
        val controller = controller(gateway, executor = executor)
        val generation = controller.beginSession()
        drain(executor)

        // transcribe NÃO pode usar owner 0 (no nativo, <=0 = cancel anônimo e
        // carga/transcrição inválida — binding "owned" falso não é utilizável).
        val text = transcribe(controller, generation)
        assertTrue("transcribe deveria recusar owner inválido, veio: $text", text.startsWith("Erro:"))
        assertTrue("transcribeOwned(0) vazou", gateway.transcribeOwnedCalls.none { it <= 0L })
        controller.cancel(generation)
        assertTrue("cancelTranscriptionOwned(0) vazou", gateway.cancelOwnedCalls.none { it <= 0L })
        assertFalse(controller.assumeContext(generation, "m.bin", 0, false))
        assertTrue("assumeContextOwned(0) vazou", gateway.assumeOwnedCalls.none { it <= 0L })
    }

    // ------------------------------------------------------------------
    // R5/A1: loadModelDetailed — diagnóstico DA MESMA operação (nunca do
    // sucessor) e recusas locais sem tocar o nativo.
    // ------------------------------------------------------------------

    @Test
    fun `a1-1 recusa stale nao toca lastError e devolve motivo proprio`() {
        val gateway = OwnedFakeGateway()
        val executor = newExecutor("whisper-r5-test")
        val controller = controller(gateway, executor = executor)
        val genA = controller.beginSession()
        drain(executor)
        val genB = controller.beginSession()          // A deixa de ser a atual
        drain(executor)
        gateway.calls.set(0); gateway.lastErrorCalls.set(0)

        val outcome = controller.loadModelDetailed(genA, "m.bin", 0, false)

        assertFalse(outcome.ok)
        assertTrue("recusa sem motivo", outcome.refusal.isNotBlank())
        assertEquals("", outcome.nativeError)
        assertEquals("recusa stale tocou lastError global", 0, gateway.lastErrorCalls.get())
        assertTrue(genB > genA)
    }

    @Test
    fun `a1-2 falha nativa devolve erro e log capturados na mesma secao`() {
        val gateway = OwnedFakeGateway()
        gateway.loadOwnedResult = false
        val executor = newExecutor("whisper-r5-test")
        val controller = controller(gateway, executor = executor)
        val generation = controller.beginSession()
        drain(executor)
        gateway.calls.set(0); gateway.lastErrorCalls.set(0); gateway.lastLoadLogCalls.set(0)

        val outcome = controller.loadModelDetailed(generation, "m.bin", 0, false)

        assertFalse(outcome.ok)
        assertEquals("erro nativo sintético", outcome.nativeError)
        assertEquals("log de carga sintético", outcome.loadLog)
        assertEquals("", outcome.refusal)
        assertEquals(1, gateway.lastErrorCalls.get())
        assertEquals(1, gateway.lastLoadLogCalls.get())
    }

    @Test
    fun `a1-3 carga ok devolve o log da propria operacao`() {
        val gateway = OwnedFakeGateway()
        val executor = newExecutor("whisper-r5-test")
        val controller = controller(gateway, executor = executor)
        val generation = controller.beginSession()
        drain(executor)

        val outcome = controller.loadModelDetailed(generation, "m.bin", 0, false)

        assertTrue(outcome.ok)
        assertEquals("log de carga sintético", outcome.loadLog)
        assertEquals("", outcome.nativeError)
        assertEquals("", outcome.refusal)
    }

    // ------------------------------------------------------------------
    // R5/A3-1: destroy durante inferência lenta — cancel chega ao owner de A
    // sem bloquear a chamadora e sem tocar B.
    // ------------------------------------------------------------------

    @Test
    fun `a3-1 destroy durante inferencia - cancel chega a A e nao toca B`() {
        val gateway = OwnedFakeGateway()
        val executor = newExecutor("whisper-r5-test")
        val controller = controller(gateway, executor = executor)
        val genA = controller.beginSession()
        drain(executor)
        val ownerA = controller.bindingOwnerOf(genA)
        val entered = CountDownLatch(1)
        val block = CountDownLatch(1)
        gateway.transcribeEntered = entered
        gateway.transcribeBlock = block

        val worker = Thread { transcribe(controller, genA) }
        worker.start()
        assertTrue("inferência de A não começou", entered.await(3, TimeUnit.SECONDS))

        val genB = controller.beginSession()
        drain(executor)
        val ownerB = controller.bindingOwnerOf(genB)

        val done = CountDownLatch(1)
        val canceller = Thread { controller.cancel(genA); done.countDown() }
        canceller.start()
        assertTrue("cancel bloqueou com inferência em andamento", done.await(3, TimeUnit.SECONDS))
        assertEquals(listOf(ownerA), gateway.cancelOwnedCalls.toList())
        assertTrue("cancel de A tocou B", gateway.cancelOwnedCalls.none { it == ownerB })

        block.countDown()
        worker.join(5000)
        assertEquals("texto", transcribe(controller, genB))
        assertEquals(listOf(ownerB), gateway.transcribeOwnedCalls.filter { it == ownerB })
    }

    // ------------------------------------------------------------------
    // R5/A3-2: legado — release após invalidate SEM sucessor DEVE acontecer.
    // ------------------------------------------------------------------

    @Test
    fun `a3-2 legado release apos invalidate sem sucessor acontece`() {
        val gateway = LegacyFakeGateway()
        val executor = newExecutor("whisper-r5-test")
        val controller = controller(gateway, executor = executor)
        val generation = controller.beginSession()
        drain(executor)

        controller.invalidateOwner(generation)
        controller.releaseAsync(generation)
        drain(executor)

        assertEquals("release legado pós-invalidate foi engolido", 1, gateway.releaseCount.get())
    }

    @Test
    fun `a3-2b legado release antigo apos B assumir nao libera B`() {
        val gateway = LegacyFakeGateway()
        val executor = newExecutor("whisper-r5-test")
        val controller = controller(gateway, executor = executor)
        val genA = controller.beginSession()
        drain(executor)
        val genB = controller.beginSession()
        drain(executor)

        controller.invalidateOwner(genA)
        controller.releaseAsync(genA)
        drain(executor)

        assertEquals("release de A atingiu a sessão de B", 0, gateway.releaseCount.get())
        controller.releaseAsync(genB)
        drain(executor)
        assertEquals(1, gateway.releaseCount.get())
    }

    // ------------------------------------------------------------------
    // R5/A2-longa: release enfileirado NÃO perde o handle quando a tabela de
    // jobs poda a geração enquanto a tarefa aguarda o coordinator.
    // ------------------------------------------------------------------

    @Test
    fun `a2-longa release enfileirado sobrevive a poda da tabela de jobs`() {
        val gateway = OwnedFakeGateway()
        val executor = newExecutor("whisper-r5-test")
        val controller = controller(gateway, executor = executor)
        val genA = controller.beginSession()
        drain(executor)
        val ownerA = controller.bindingOwnerOf(genA)
        gateway.ctxOwner = ownerA

        // Segura o executor (e o release enfileirado) enquanto a tabela poda A.
        val blocker = CountDownLatch(1)
        executor.execute { blocker.await(10, TimeUnit.SECONDS) }
        controller.releaseAsync(genA)
        repeat(70) { controller.beginSession() }   // poda genA da retenção de 64
        blocker.countDown()
        drain(executor)

        assertEquals("release perdeu o handle podado", listOf(ownerA), gateway.releaseOwnedCalls.toList())
        assertEquals("contexto de A não foi liberado", 1, gateway.ctxFreeCount.get())
    }
}
