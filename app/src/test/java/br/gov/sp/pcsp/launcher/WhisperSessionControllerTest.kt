package br.gov.sp.pcsp.launcher

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** W01/W02: geração de sessão, release assíncrono e descarte de callbacks
 * obsoletos do WhisperSessionController, com gateway nativo falso. */
class WhisperSessionControllerTest {

    private open class FakeGateway : WhisperSessionController.NativeGateway {
        val beginSessionCount = AtomicInteger(0)
        val transcribeCount = AtomicInteger(0)
        val releaseCount = AtomicInteger(0)
        val cancelCount = AtomicInteger(0)
        val releaseThreadNames = CopyOnWriteArrayList<String>()
        val loggedMessages = CopyOnWriteArrayList<String>()

        @Volatile var transcribeResult: String = "transcrição de verdade"
        @Volatile var status: String = "ok"

        /** Se definido, releaseModel bloqueia até o teste liberar. */
        @Volatile var releaseBlock: CountDownLatch? = null
        @Volatile var releaseDone: CountDownLatch? = null
        @Volatile var releaseThrows = false
        @Volatile var cancelDuringLoad = false

        @Volatile
        var loadStarted: CountDownLatch? = null

        @Volatile
        var loadBlock: CountDownLatch? = null

        override fun loadModel(modelPath: String, backendKind: Int, flashAttention: Boolean): Boolean {
            loadStarted?.countDown()
            loadBlock?.await(5, TimeUnit.SECONDS)
            return true
        }

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
            transcribeCount.incrementAndGet()
            return transcribeResult
        }

        override fun cancelTranscription() {
            cancelCount.incrementAndGet()
            if (loadStarted != null && (loadBlock?.count ?: 0L) > 0L) cancelDuringLoad = true
        }

        override fun beginSession(): Long = beginSessionCount.incrementAndGet().toLong()

        override fun releaseModel() {
            releaseThreadNames += Thread.currentThread().name
            if (releaseThrows) throw RuntimeException("release explodiu")
            releaseBlock?.await(5, TimeUnit.SECONDS)
            releaseCount.incrementAndGet()
            releaseDone?.countDown()
        }

        override fun lastTranscribeStatus(): String = status
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

    private fun controllerWith(
        gateway: WhisperSessionController.NativeGateway,
        executor: ExecutorService = newExecutor("whisper-release-test")
    ): WhisperSessionController = WhisperSessionController(
        gateway = gateway,
        releaseExecutor = executor,
        logError = { message, _ -> (gateway as? FakeGateway)?.loggedMessages?.add(message) },
        // R2/B2: testes JVM não tocam o nativo; o prewarm de produção (class-load
        // da lib) fica desligado aqui, os testes de ownership contam as chamadas.
        nativePrewarm = { }
    )

    /** Tarefa sentinela: o executor é single-thread, então quando ela roda,
     * toda fila anterior já foi processada (ou descartada). */
    private fun drain(executor: ExecutorService, timeoutSeconds: Long = 3L): CountDownLatch {
        val done = CountDownLatch(1)
        executor.execute { done.countDown() }
        assertTrue("executor não esvaziou em ${timeoutSeconds}s", done.await(timeoutSeconds, TimeUnit.SECONDS))
        return done
    }

    private fun noopCallback() = object : WhisperNative.Callback {
        override fun onSegment(text: String, startMs: Long, endMs: Long) {}
        override fun onProgress(progress: Int) {}
        override fun onNativeLog(line: String) {}
    }

    private fun transcribe(controller: WhisperSessionController, generation: Long): String =
        controller.transcribe(generation, "a.wav", "pt", 1, 1, false, false, "", noopCallback())

    // ---------------- W01: release assíncrono ----------------

    @Test
    fun `release roda na thread do executor e nunca na thread chamadora`() {
        val gateway = FakeGateway()
        val executor = newExecutor("whisper-release-test")
        val controller = controllerWith(gateway, executor)
        val generation = controller.beginSession()
        val done = CountDownLatch(1)
        gateway.releaseDone = done

        controller.releaseAsync(generation)

        assertTrue("releaseModel não rodou em 3s", done.await(3, TimeUnit.SECONDS))
        assertEquals(1, gateway.releaseCount.get())
        assertEquals(listOf("whisper-release-test"), gateway.releaseThreadNames.toList())
        assertNotEquals(Thread.currentThread().name, gateway.releaseThreadNames.first())
    }

    @Test
    fun `releaseAsync não bloqueia a thread chamadora com release lento`() {
        val gateway = FakeGateway()
        val controller = controllerWith(gateway)
        val generation = controller.beginSession()
        val block = CountDownLatch(1)
        val done = CountDownLatch(1)
        gateway.releaseBlock = block
        gateway.releaseDone = done

        val returned = CountDownLatch(1)
        val caller = Thread { controller.releaseAsync(generation); returned.countDown() }
        caller.start()

        assertTrue("releaseAsync bloqueou a chamadora", returned.await(3, TimeUnit.SECONDS))
        assertEquals(0, gateway.releaseCount.get()) // release ainda em andamento
        block.countDown()
        assertTrue(done.await(3, TimeUnit.SECONDS))
        assertEquals(1, gateway.releaseCount.get())
    }

    @Test
    fun `release de geração antiga é descartado e não chama releaseModel`() {
        val gateway = FakeGateway()
        val executor = newExecutor("whisper-release-test")
        val controller = controllerWith(gateway, executor)
        val old = controller.beginSession()
        val current = controller.beginSession()

        controller.releaseAsync(old)
        drain(executor)
        assertEquals(0, gateway.releaseCount.get())

        controller.releaseAsync(current)
        drain(executor)
        assertEquals(1, gateway.releaseCount.get())
    }

    @Test
    fun `exceção no release é engolida e logada`() {
        val gateway = FakeGateway()
        gateway.releaseThrows = true
        val executor = newExecutor("whisper-release-test")
        val controller = controllerWith(gateway, executor)
        val generation = controller.beginSession()

        controller.releaseAsync(generation) // não deve lançar na chamadora
        drain(executor)

        assertEquals(1, gateway.loggedMessages.size)
        assertTrue(gateway.loggedMessages.first().contains("releaseModel"))
    }

    // ---------------- W02: geração de sessão ----------------

    @Test
    fun `beginSession avança a geração`() {
        val gateway = FakeGateway()
        val executor = newExecutor("whisper-release-test")
        val controller = controllerWith(gateway, executor)

        val first = controller.beginSession()
        val second = controller.beginSession()

        assertNotEquals(first, second)
        assertEquals(second, controller.currentGeneration())
        assertFalse(controller.isCurrent(first))
        assertTrue(controller.isCurrent(second))
        // R4/B1: beginSession é assíncrono — o nativo (beginSession legado, no
        // job de init) só é tocado na thread de trabalho. Drena para observar.
        drain(executor)
        assertEquals(2, gateway.beginSessionCount.get())
    }

    @Test
    fun `transcribe de geração antiga não toca o nativo e devolve cancelado`() {
        val gateway = FakeGateway()
        val controller = controllerWith(gateway)
        val old = controller.beginSession()
        controller.beginSession()

        val returned = transcribe(controller, old)

        assertEquals(WhisperSessionController.STALE_RESULT_TEXT, returned)
        assertEquals(0, gateway.transcribeCount.get())
        assertEquals(WhisperSessionController.STALE_STATUS, controller.lastTranscribeStatus(old))
    }

    @Test
    fun `transcribe de geração atual delega ao nativo e expõe o status real`() {
        val gateway = FakeGateway()
        gateway.transcribeResult = "texto do arquivo"
        gateway.status = "no_speech"
        val controller = controllerWith(gateway)
        val generation = controller.beginSession()

        assertEquals("texto do arquivo", transcribe(controller, generation))
        assertEquals(1, gateway.transcribeCount.get())
        assertEquals("no_speech", controller.lastTranscribeStatus(generation))
    }

    @Test
    fun `lastTranscribeStatus de lib antiga sem status vira string vazia`() {
        val gateway = object : FakeGateway() {
            override fun lastTranscribeStatus(): String = throw UnsatisfiedLinkError("lib antiga")
        }
        val controller = controllerWith(gateway)
        val generation = controller.beginSession()

        assertEquals("", controller.lastTranscribeStatus(generation))
        assertTrue(gateway.loggedMessages.isNotEmpty())
    }

    @Test
    fun `wrapCallback descarta segmento progresso e log de sessão antiga`() {
        val gateway = FakeGateway()
        val controller = controllerWith(gateway)
        val events = CopyOnWriteArrayList<String>()
        val delegate = object : WhisperNative.Callback {
            override fun onSegment(text: String, startMs: Long, endMs: Long) { events += "segment:$text" }
            override fun onProgress(progress: Int) { events += "progress:$progress" }
            override fun onNativeLog(line: String) { events += "log:$line" }
        }

        val oldGeneration = controller.beginSession()
        val wrapped = controller.wrapCallback(oldGeneration, delegate)
        controller.beginSession() // nova sessão descarta a anterior

        wrapped.onSegment("olá", 0L, 10L)
        wrapped.onProgress(42)
        wrapped.onNativeLog("chunk 1/4")
        assertTrue("callback obsoleto não foi descartado: $events", events.isEmpty())

        val fresh = controller.wrapCallback(controller.currentGeneration(), delegate)
        fresh.onSegment("olá", 0L, 10L)
        fresh.onProgress(42)
        fresh.onNativeLog("chunk 1/4")
        assertEquals(listOf("segment:olá", "progress:42", "log:chunk 1/4"), events.toList())
    }

    @Test
    fun `transcribe envolve o callback e descarta eventos após nova sessão`() {
        val gateway = FakeGateway()
        val events = CopyOnWriteArrayList<String>()
        val delegate = object : WhisperNative.Callback {
            override fun onSegment(text: String, startMs: Long, endMs: Long) { events += text }
            override fun onProgress(progress: Int) {}
            override fun onNativeLog(line: String) {}
        }
        val captured = arrayOfNulls<WhisperNative.Callback>(1)
        val capturing = object : WhisperSessionController.NativeGateway by gateway {
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
                captured[0] = callback
                return gateway.transcribe(
                    wavPath, language, beamSize, bestOf, wordTimestamps, vadFilter, vadModelPath, callback
                )
            }
        }
        val controller = controllerWith(capturing)
        val generation = controller.beginSession()

        val returned = controller.transcribe(generation, "b.wav", "pt", 1, 1, false, false, "", delegate)

        assertEquals("transcrição de verdade", returned)
        assertTrue("callback não foi capturado para inspeção", captured[0] != null)

        // simula nova sessão iniciada enquanto a transcrição antiga ainda notificava
        controller.beginSession()
        captured[0]?.onSegment("tarde", 0L, 5L)

        assertTrue("evento de sessão substituída vazou: $events", events.isEmpty())
    }

    // ---------------- cancel sem bloqueio ----------------

    @Test
    fun `cancel não bloqueia mesmo com load em andamento`() {
        val gateway = FakeGateway()
        val executor = newExecutor("whisper-release-test")
        val controller = controllerWith(gateway, executor)
        val loadStarted = CountDownLatch(1)
        val loadBlock = CountDownLatch(1)
        gateway.loadStarted = loadStarted
        gateway.loadBlock = loadBlock

        // R4/B1: sessão com init resolvido (legado) — o cancel tem de chegar ao
        // nativo durante o load SEM esperar.
        val generation = controller.beginSession()
        drain(executor)

        val loader = Thread { gateway.loadModel("modelo.gguf", 0, false) }
        loader.start()
        assertTrue("load não começou em 3s", loadStarted.await(3, TimeUnit.SECONDS))

        val cancelDone = CountDownLatch(1)
        val canceller = Thread { controller.cancel(generation); cancelDone.countDown() }
        canceller.start()

        assertTrue("cancel bloqueou com load em andamento", cancelDone.await(3, TimeUnit.SECONDS))
        assertEquals(1, gateway.cancelCount.get())
        assertTrue("cancel deveria ter ocorrido durante o load", gateway.cancelDuringLoad)

        loadBlock.countDown()
        loader.join(3000)
        canceller.join(3000)
    }

    @Test
    fun `cancel com exceção nativa é engolida`() {
        val gateway = object : FakeGateway() {
            override fun cancelTranscription() = throw UnsatisfiedLinkError("lib antiga")
        }
        val executor = newExecutor("whisper-release-test")
        val controller = controllerWith(gateway, executor)
        val generation = controller.beginSession()
        drain(executor)   // init resolvido: o cancel da geração ATUAL é encaminhado

        controller.cancel(generation)

        assertEquals(listOf("cancelTranscription falhou"), gateway.loggedMessages.toList())
    }
}
