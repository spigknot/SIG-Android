package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * R3/C1 — suíte do caminho ao vivo v2 (spool + contadores exatos + término).
 * Cobre: preservação do aceito com inferência lenta (backlog maior que a fila
 * antiga), storage cheio/IO falho QUANTIFICADO, leitor que não espera, timeout
 * curto injetável com worker em latch, listener que lança, cancel
 * (antes do start, entre processamento e entrega), EOF/zero contínuo/ímpar,
 * exceções de fonte/worker, hash independente do PCM reconstruído vs fonte.
 */
class LivePcmPipelineTest {

    private fun tmpDir(): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "sig-live-test-${System.nanoTime()}")
        dir.mkdirs()
        return dir
    }

    private fun pattern(samples: Int, seed: Int = 1234): ByteArray {
        val out = ByteArray(samples * 2)
        var v = seed
        for (i in 0 until samples) {
            v = v * 1103515245 + 12345
            val s = (v ushr 8) and 0x7FFF
            out[i * 2] = (s and 0xFF).toByte()
            out[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun shaOf(md: MessageDigest): String = md.digest().joinToString("") { "%02x".format(it) }

    /** Fonte sintética: entrega o buffer em pedaços fixos; pode injetar falhas. */
    private class ScriptedSource(
        private val data: ByteArray,
        private val readSize: Int,
        private val errorAt: Int = -1,
        private val alwaysZero: Boolean = false,
        private val oddReads: Boolean = false,
    ) : LivePcmSource {
        private var offset = 0
        private var reads = 0
        @Volatile var closed = false; private set

        override fun read(buffer: ByteArray): Int {
            if (alwaysZero) return 0
            reads++
            if (errorAt >= 0 && reads == errorAt) throw IllegalStateException("falha injetada na fonte")
            if (offset >= data.size) return -1
            var n = minOf(readSize, data.size - offset)
            if (oddReads && n > 1 && n % 2 == 0) n -= 1
            if (oddReads && n == 0) n = 1
            n = minOf(n, buffer.size)
            System.arraycopy(data, offset, buffer, 0, n)
            offset += n
            return n
        }

        override fun close() { closed = true }
    }

    private fun workerOf(latch: CountDownLatch? = null, failAt: Int? = null, hash: MessageDigest? = null, seen: CopyOnWriteArrayList<Int>? = null) =
        object : LivePcmPipeline.Worker<String?> {
            override fun process(chunk: LivePcmPipeline.Chunk): String? {
                seen?.add(chunk.index)
                hash?.update(chunk.pcm)
                latch?.await(20, TimeUnit.SECONDS)
                if (failAt != null && chunk.index == failAt) throw IllegalStateException("worker falhou no chunk ${chunk.index}")
                return "txt-${chunk.index}"
            }

            override fun isSilence(result: String?): Boolean = result == null
        }

    // ================================================================ 1

    @Test
    fun `spool preserva o aceito com inferencia lenta - backlog maior que a fila antiga`() {
        val dir = tmpDir()
        val latch = CountDownLatch(1)
        val seen = CopyOnWriteArrayList<Int>()
        val pipeline = LivePcmPipeline(
            chunkSamples = 1000,
            worker = workerOf(latch = latch, seen = seen),
            spoolDir = dir,
        )
        pipeline.start()
        val chunkBytes = 2000
        val chunks = 10
        val source = pattern(1000 * chunks)
        var fed = 0
        repeat(chunks) {
            val slice = source.copyOfRange(it * chunkBytes, it * chunkBytes + chunkBytes)
            val before = System.nanoTime()
            val accepted = pipeline.feed(slice, chunkBytes)
            val dtMs = (System.nanoTime() - before) / 1_000_000
            assertTrue("feed não pode esperar a inferência (${dtMs}ms)", dtMs < 250)
            assertEquals(chunkBytes, accepted)
            fed++
        }
        assertEquals(chunks, fed)
        val running = pipeline.counters()
        assertEquals("TUDO aceito (nada de descartar o mais antigo)", 10000, running.capturedSamples)
        assertTrue(running.isConsistent())

        pipeline.requestStop()
        assertFalse("worker em latch: ainda NÃO finalizado", pipeline.awaitFinished(150))
        assertEquals(LivePcmPipeline.State.STOPPING, pipeline.state())
        assertTrue(pipeline.workerAlive())

        latch.countDown()
        assertTrue(pipeline.awaitFinished(10_000))
        assertEquals(LivePcmPipeline.State.FINISHED, pipeline.state())
        val counters = pipeline.counters()
        assertEquals(10000, counters.processedSamples)
        assertEquals(10000, counters.okSamples)
        assertEquals(0, counters.lostSamples)
        assertTrue(counters.isBalanced())
        assertEquals("os chunks foram processados NA ORDEM", (0 until chunks).toList(), seen.toList())
        dir.deleteRecursively()
    }

    // ================================================================ 2

    @Test
    fun `storage cheio injetado interrompe e quantifica o perdido`() {
        val dir = tmpDir()
        val latch = CountDownLatch(1)
        val pipeline = LivePcmPipeline(
            chunkSamples = 500,
            worker = workerOf(latch = latch),
            spoolDir = dir,
            maxSpoolBytes = 1500,
        )
        pipeline.start()
        val source = pattern(1500)
        val accepted = pipeline.feed(source, 3000)
        assertEquals("aceitou até o limite", 1000, accepted)
        assertFalse(pipeline.isAccepting())
        assertNotNull("falha de armazenamento registrada", pipeline.storageFailureMessage())
        val counters = pipeline.counters()
        assertEquals(1500, counters.producedSamples)
        assertEquals(500, counters.capturedSamples)
        assertEquals("o perdido está QUANTIFICADO, não silencioso", 1000, counters.lostSamples)
        assertTrue(counters.lossVisible())
        assertTrue(counters.isConsistent())

        latch.countDown()
        pipeline.requestStop()
        assertTrue(pipeline.awaitFinished(10_000))
        assertTrue(pipeline.counters().isBalanced())
        dir.deleteRecursively()
    }

    // ================================================================ 3

    @Test
    fun `falha de IO no spool quantifica e nao lanca`() {
        val base = File(System.getProperty("java.io.tmpdir"), "sig-live-notdir-${System.nanoTime()}")
        base.writeText("sou um arquivo, não um diretório")
        val pipeline = LivePcmPipeline(
            chunkSamples = 500,
            worker = workerOf(),
            spoolDir = base,
        )
        pipeline.start()
        val source = pattern(500)
        val accepted = pipeline.feed(source, 1000)
        assertEquals(0, accepted)
        assertNotNull(pipeline.storageFailureMessage())
        val counters = pipeline.counters()
        assertEquals(500, counters.producedSamples)
        assertEquals(0, counters.capturedSamples)
        assertEquals(500, counters.lostSamples)
        assertTrue(counters.isBalanced())
        pipeline.requestStop()
        assertTrue(pipeline.awaitFinished(10_000))
        base.delete()
    }

    // ================================================================ 4

    @Test
    fun `timeout curto injetavel com worker em latch - pendente visivel e nada liberado ate o terminal`() {
        val dir = tmpDir()
        val latch = CountDownLatch(1)
        val coordinator = LiveTranscriptionCoordinator(
            spoolDir = dir,
            chunkSamples = 500,
            processChunk = { chunk ->
                latch.await(20, TimeUnit.SECONDS)
                "txt-${chunk.index}"
            },
        )
        coordinator.start()
        assertEquals(4000, coordinator.pipeline.feed(pattern(2000), 4000))
        val outcome = coordinator.runReader(
            object : LivePcmSource {
                override fun read(buffer: ByteArray): Int = -1
                override fun close() {}
            },
            { true },
        )
        assertEquals("eof", outcome.reason)

        val pendingAt = AtomicLong(-1)
        val returned = AtomicLong(-1)
        val t = Thread {
            coordinator.stopAndAwait(stopTimeoutMs = 150, pollMs = 50, onPending = { elapsed -> pendingAt.set(elapsed) })
            returned.set(System.nanoTime())
        }
        t.start()
        Thread.sleep(500)
        assertTrue("onPending disparou (estado pendente VISÍVEL)", pendingAt.get() >= 150)
        assertFalse("NÃO finalizou com o worker em latch", coordinator.isFinished())
        assertTrue("handle mantido (worker vivo)", coordinator.pipeline.workerAlive())
        assertEquals("controles NÃO liberados (estado STOPPING)", LivePcmPipeline.State.STOPPING, coordinator.state())
        assertEquals("a espera continua (não retornou)", -1L, returned.get())

        latch.countDown()
        t.join(10_000)
        assertTrue(coordinator.isFinished())
        val counters = coordinator.counters()
        assertEquals(2000, counters.processedSamples)
        assertTrue(counters.isBalanced())
        dir.deleteRecursively()
    }

    // ================================================================ 5

    @Test
    fun `listener que lanca nao derruba o worker - terminal garantido e erros contados`() {
        val dir = tmpDir()
        val finished = CountDownLatch(1)
        val pipeline = LivePcmPipeline(
            chunkSamples = 500,
            worker = workerOf(),
            spoolDir = dir,
            listener = object : LivePcmPipeline.Listener<String?> {
                override fun onChunkProcessed(chunk: LivePcmPipeline.Chunk, result: String?, ok: Boolean) {
                    throw IllegalStateException("listener explode")
                }

                override fun onFinished(cancelled: Boolean) { finished.countDown() }
            },
        )
        pipeline.start()
        val source = pattern(1500)
        assertEquals(3000, pipeline.feed(source, 3000))
        pipeline.requestStop()
        assertTrue("terminal garantido mesmo com listener lançando", pipeline.awaitFinished(10_000))
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        val counters = pipeline.counters()
        assertEquals(1500, counters.processedSamples)
        assertTrue("exceções do listener contadas", counters.listenerErrors >= 3)
        assertTrue(counters.isBalanced())
        dir.deleteRecursively()
    }

    // ================================================================ 6

    @Test
    fun `cancel antes do start e idempotente e o worker termina`() {
        val dir = tmpDir()
        val finishedCancelled = AtomicInteger(-1)
        val pipeline = LivePcmPipeline(
            chunkSamples = 500,
            worker = workerOf(),
            spoolDir = dir,
            listener = object : LivePcmPipeline.Listener<String?> {
                override fun onChunkProcessed(chunk: LivePcmPipeline.Chunk, result: String?, ok: Boolean) {}
                override fun onFinished(cancelled: Boolean) { finishedCancelled.set(if (cancelled) 1 else 0) }
            },
        )
        pipeline.cancel()
        pipeline.cancel()   // idempotente
        pipeline.requestStop() // idempotente em cima do cancel
        pipeline.start()
        assertTrue(pipeline.awaitFinished(5_000))
        assertEquals(1, finishedCancelled.get())
        assertEquals(0L, pipeline.counters().producedSamples)
        assertTrue(pipeline.counters().isBalanced())
        pipeline.close()
        pipeline.close()   // idempotente
        dir.deleteRecursively()
    }

    /**
     * R11/G-fix — REGRESSOR DETERMINÍSTICO da corrida do terminal (o caso que
     * falhou em `cancel antes do start…` de forma intermitente: `awaitFinished`
     * voltava true com `onFinished` ainda não entregue).
     *
     * Segura o worker DENTRO do `onFinished` com um latch: enquanto o callback
     * não retorna, o terminal NÃO pode estar publicado. Sem o fix o
     * `awaitFinished` já retornava true aqui (o `finished = true` era publicado
     * antes do callback), e o retrato terminal de quem espera (counters/export do
     * `stopAndAwait`) sairia ANTES do callback publicar o último texto.
     */
    @Test
    fun `terminal so e publicado DEPOIS do onFinished - retrato terminal nao sai antes do callback`() {
        val dir = tmpDir()
        val inCallback = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val delivered = AtomicInteger(-1)
        val pipeline = LivePcmPipeline(
            chunkSamples = 500,
            worker = workerOf(),
            spoolDir = dir,
            listener = object : LivePcmPipeline.Listener<String?> {
                override fun onChunkProcessed(chunk: LivePcmPipeline.Chunk, result: String?, ok: Boolean) {}
                override fun onFinished(cancelled: Boolean) {
                    delivered.set(if (cancelled) 1 else 0)
                    inCallback.countDown()
                    releaseCallback.await(10, TimeUnit.SECONDS)   // segura o worker DENTRO do callback
                }
            },
        )
        pipeline.start()
        pipeline.requestStop()
        assertTrue("callback terminal foi alcançado", inCallback.await(5, TimeUnit.SECONDS))
        assertFalse(
            "terminal publicado ANTES do callback (corrida): o retrato terminal sairia cedo",
            pipeline.awaitFinished(300),
        )
        assertFalse("isFinished() nao pode anteceder o callback", pipeline.isFinished())
        releaseCallback.countDown()
        assertTrue("depois do callback o terminal e publicado", pipeline.awaitFinished(5_000))
        assertTrue("callback entregue", delivered.get() >= 0)
        pipeline.close()
        dir.deleteRecursively()
    }

    // ================================================================ 7

    @Test
    fun `cancel entre processamento e entrega - resultado em voo nao entregue e pendentes contados`() {
        val dir = tmpDir()
        val inProcess = CountDownLatch(1)
        val release = CountDownLatch(1)
        val delivered = CopyOnWriteArrayList<String>()
        val pipeline = LivePcmPipeline(
            chunkSamples = 500,
            worker = object : LivePcmPipeline.Worker<String?> {
                override fun process(chunk: LivePcmPipeline.Chunk): String? {
                    if (chunk.index == 0) {
                        inProcess.countDown()
                        release.await(10, TimeUnit.SECONDS)
                    }
                    return "txt-${chunk.index}"
                }
            },
            spoolDir = dir,
            listener = object : LivePcmPipeline.Listener<String?> {
                override fun onChunkProcessed(chunk: LivePcmPipeline.Chunk, result: String?, ok: Boolean) {
                    delivered.add("txt-${chunk.index}")
                }
                override fun onFinished(cancelled: Boolean) {}
            },
        )
        pipeline.start()
        val source = pattern(1500)
        assertEquals(3000, pipeline.feed(source, 3000))
        assertTrue(inProcess.await(5, TimeUnit.SECONDS))
        pipeline.cancel()          // entre o processamento (chunk 0) e a entrega
        release.countDown()
        assertTrue(pipeline.awaitFinished(10_000))
        assertTrue("nem o resultado do chunk em voo é entregue", delivered.isEmpty())
        val counters = pipeline.counters()
        assertEquals(500, counters.processedSamples)   // o chunk em voo PROCESSOU...
        assertEquals(1000, counters.cancelledSamples)  // ...os 2 pendentes foram cancelados e contados
        assertTrue(counters.isBalanced())
        dir.deleteRecursively()
    }

    // ================================================================ 8

    @Test
    fun `EOF da fonte termina o pipeline sozinho sem espera de dreno`() {
        val dir = tmpDir()
        val data = pattern(2000)
        val coordinator = LiveTranscriptionCoordinator(
            spoolDir = dir,
            chunkSamples = 500,
            processChunk = { chunk -> "txt-${chunk.index}" },
        )
        coordinator.start()
        val source = ScriptedSource(data, readSize = 2000)
        val outcome = coordinator.runReader(source, { true })
        assertEquals("eof", outcome.reason)
        assertTrue(source.closed)
        assertTrue("drenou e terminou sozinho", coordinator.pipeline.awaitFinished(10_000))
        val counters = coordinator.counters()
        assertTrue(counters.sourceEof)
        assertEquals(2000, counters.processedSamples)
        assertTrue(counters.isBalanced())
        dir.deleteRecursively()
    }

    // ================================================================ 9

    @Test
    fun `zero continuo e limitado - sem loop infinito`() {
        val dir = tmpDir()
        val coordinator = LiveTranscriptionCoordinator(
            spoolDir = dir,
            chunkSamples = 500,
            processChunk = { chunk -> "txt-${chunk.index}" },
            maxConsecutiveZeroReads = 50,
        )
        coordinator.start()
        val source = ScriptedSource(pattern(100), readSize = 66, alwaysZero = true)
        val t0 = System.nanoTime()
        val outcome = coordinator.runReader(source, { true })
        val dtMs = (System.nanoTime() - t0) / 1_000_000
        assertTrue("terminou em tempo limitado (${dtMs}ms)", dtMs < 5_000)
        assertEquals("stalled", outcome.reason)
        assertTrue(coordinator.counters().sourceStalled)
        assertNotNull(coordinator.pipeline.sourceFailureMessage())
        assertTrue(coordinator.pipeline.awaitFinished(5_000))
        dir.deleteRecursively()
    }

    // ================================================================ 10

    @Test
    fun `leitura impar - fragmento emendado entre leituras (nenhum byte perdido)`() {
        val dir = tmpDir()
        val data = pattern(1501)   // 3002 bytes
        val md = MessageDigest.getInstance("SHA-256")
        val coordinator = LiveTranscriptionCoordinator(
            spoolDir = dir,
            chunkSamples = 500,
            processChunk = { chunk -> md.update(chunk.pcm); "txt-${chunk.index}" },
        )
        coordinator.start()
        val source = object : LivePcmSource {
            private var offset = 0
            private var n = 0
            override fun read(buffer: ByteArray): Int {
                if (offset >= data.size) return -1
                n++
                var take = minOf(1001, data.size - offset)
                if (take % 2 == 0) take -= 1   // SEMPRE ímpar (1001 / 999 / ... / 1)
                System.arraycopy(data, offset, buffer, 0, take)
                offset += take
                return take
            }
            override fun close() {}
        }
        val outcome = coordinator.runReader(source, { true })
        assertEquals("eof", outcome.reason)
        // R4/C2: o byte ímpar NÃO é descartado na hora — é emendado com a
        // próxima leitura (1001+1001+999+1): 1501 amostras, zero bytes perdidos.
        assertEquals(1501L, outcome.samplesRead)
        assertEquals("nenhum byte perdido no caminho ímpar", 0L, outcome.oddBytesDropped)
        assertFalse(outcome.fragmentDropped)
        assertTrue(coordinator.pipeline.awaitFinished(10_000))
        val counters = coordinator.counters()
        assertEquals(0L, counters.oddBytesDropped)
        assertEquals("nada de ignorar bytes: hash do PCM recomposto bate", sha(data), shaOf(md))
        assertTrue(counters.isBalanced())
        dir.deleteRecursively()
    }

    // ================================================================ 10b

    @Test
    fun `R4-C2 duas leituras impares seguidas formam amostras completas - fragmento conservado`() {
        // 1001 + 1001 + 1000 bytes: o byte ímpar da 1ª leitura é CONCATENADO
        // com a 2ª (o adaptador preserva o fragmento entre leituras) — nenhum
        // byte perdido e o hash do PCM recomposto bate com a fonte.
        val dir = tmpDir()
        val data = pattern(1501)   // 3002 bytes
        val sizes = intArrayOf(1001, 1001, 1000)
        val md = MessageDigest.getInstance("SHA-256")
        val coordinator = LiveTranscriptionCoordinator(
            spoolDir = dir,
            chunkSamples = 500,
            processChunk = { chunk -> md.update(chunk.pcm); "txt-${chunk.index}" },
        )
        coordinator.start()
        val source = object : LivePcmSource {
            private var offset = 0
            private var i = 0
            override fun read(buffer: ByteArray): Int {
                if (i >= sizes.size) return -1
                val n = minOf(sizes[i], data.size - offset)
                i++
                if (n <= 0) return -1
                System.arraycopy(data, offset, buffer, 0, n)
                offset += n
                return n
            }

            override fun close() {}
        }
        val outcome = coordinator.runReader(source, { true })
        assertEquals("eof", outcome.reason)
        assertEquals("amostras completas das duas leituras ímpares", 1501L, outcome.samplesRead)
        assertEquals("nenhum byte perdido (fragmento concatenado)", 0L, outcome.oddBytesDropped)
        assertFalse("sem fragmento pendente no EOF", outcome.fragmentDropped)
        assertTrue(coordinator.pipeline.awaitFinished(10_000))
        val counters = coordinator.counters()
        assertEquals(0L, counters.oddBytesDropped)
        assertEquals("hash do PCM recomposto bate com a fonte", sha(data), shaOf(md))
        assertTrue(counters.isBalanced())
        dir.deleteRecursively()
    }

    // ================================================================ 10c

    @Test
    fun `R4-C2 fragmento impar remanescente no EOF e perda explicita`() {
        // 1001 + 1001 + 1001 bytes: sobra 1 byte no fim — aí SIM é descartado,
        // contado em oddBytesDropped e marcado como fragmento (EOF/parada).
        val dir = tmpDir()
        val data = pattern(1502).copyOf(3003)
        val sizes = intArrayOf(1001, 1001, 1001)
        val coordinator = LiveTranscriptionCoordinator(
            spoolDir = dir,
            chunkSamples = 500,
            processChunk = { chunk -> "txt-${chunk.index}" },
        )
        coordinator.start()
        val source = object : LivePcmSource {
            private var offset = 0
            private var i = 0
            override fun read(buffer: ByteArray): Int {
                if (i >= sizes.size) return -1
                val n = minOf(sizes[i], data.size - offset)
                i++
                if (n <= 0) return -1
                System.arraycopy(data, offset, buffer, 0, n)
                offset += n
                return n
            }

            override fun close() {}
        }
        val outcome = coordinator.runReader(source, { true })
        assertEquals("eof", outcome.reason)
        assertEquals(1501L, outcome.samplesRead)
        assertEquals("fragmento final de 1 byte = perda explícita", 1L, outcome.oddBytesDropped)
        assertTrue("marcado como fragmento de EOF", outcome.fragmentDropped)
        assertTrue(coordinator.pipeline.awaitFinished(10_000))
        assertEquals(1L, coordinator.counters().oddBytesDropped)
        assertTrue(coordinator.counters().isBalanced())
        dir.deleteRecursively()
    }

    // ================================================================ 11

    @Test
    fun `excecao da fonte - terminal com falha visivel`() {
        val dir = tmpDir()
        val coordinator = LiveTranscriptionCoordinator(
            spoolDir = dir,
            chunkSamples = 500,
            processChunk = { chunk -> "txt-${chunk.index}" },
        )
        coordinator.start()
        val source = ScriptedSource(pattern(4000), readSize = 1000, errorAt = 2)
        val outcome = coordinator.runReader(source, { true })
        assertEquals("source-error", outcome.reason)
        assertTrue(coordinator.pipeline.awaitFinished(5_000))
        assertNotNull(coordinator.pipeline.sourceFailureMessage())
        assertTrue(coordinator.counters().isBalanced())
        dir.deleteRecursively()
    }

    // ================================================================ 11b

    @Test
    fun `excecao da fonte com tail parcial - o aceito vira chunk final e o balanco fecha`() {
        // Regressao do gap encontrado no device (s11 sourcefail): readSize 3200
        // B (1600 amostras) com chunkSamples 500 aceita 3 chunks cheios + tail
        // de 100 amostras; o erro na leitura 2 encerra o leitor ANTES do chunk
        // fechar. O terminal por falha tem de preservar o aceito (flush do tail)
        // e fechar isBalanced().
        val dir = tmpDir()
        val coordinator = LiveTranscriptionCoordinator(
            spoolDir = dir,
            chunkSamples = 500,
            processChunk = { chunk -> "txt-${chunk.index}" },
        )
        coordinator.start()
        val source = ScriptedSource(pattern(8000), readSize = 3200, errorAt = 2)
        val outcome = coordinator.runReader(source, { true })
        assertEquals("source-error", outcome.reason)
        assertTrue(coordinator.pipeline.awaitFinished(5_000))
        val counters = coordinator.counters()
        assertNotNull(counters.sourceFailure)
        assertEquals("nada pode ficar pendente no terminal", 0L, counters.pendingSamples())
        assertEquals(1600L, counters.processedSamples)
        assertTrue("balanco terminal fechado", counters.isBalanced())
        dir.deleteRecursively()
    }

    // ================================================================ 12

    @Test
    fun `excecao do worker - tentativa falha nao e audio transcrito - contadores separam`() {
        val dir = tmpDir()
        val results = CopyOnWriteArrayList<Pair<Int, Boolean>>()
        val pipeline = LivePcmPipeline(
            chunkSamples = 500,
            worker = workerOf(failAt = 1),
            spoolDir = dir,
            listener = object : LivePcmPipeline.Listener<String?> {
                override fun onChunkProcessed(chunk: LivePcmPipeline.Chunk, result: String?, ok: Boolean) {
                    results.add(chunk.index to ok)
                }
                override fun onFinished(cancelled: Boolean) {}
            },
        )
        pipeline.start()
        val source = pattern(1500)
        assertEquals(3000, pipeline.feed(source, 3000))
        pipeline.requestStop()
        assertTrue(pipeline.awaitFinished(10_000))
        val counters = pipeline.counters()
        assertEquals(1500, counters.processedSamples)
        assertEquals(500, counters.failedSamples)
        assertEquals(1000, counters.okSamples)
        assertTrue(counters.isBalanced())
        assertEquals("o chunk falho vai marcado ok=false", false, results.first { it.first == 1 }.second)
        dir.deleteRecursively()
    }

    // ================================================================ 13

    @Test
    fun `hash independente - PCM reconstruido identico a fonte e ordem preservada`() {
        val dir = tmpDir()
        val totalSamples = 50_000
        val data = pattern(totalSamples, seed = 777)
        val md = MessageDigest.getInstance("SHA-256")
        val chunkCount = AtomicInteger(0)
        val coordinator = LiveTranscriptionCoordinator(
            spoolDir = dir,
            chunkSamples = 1000,
            processChunk = { chunk ->
                md.update(chunk.pcm)
                chunkCount.incrementAndGet()
                "txt-${chunk.index}"
            },
        )
        coordinator.start()
        val source = ScriptedSource(data, readSize = 1250)
        val outcome = coordinator.runReader(source, { true })
        assertEquals("eof", outcome.reason)
        assertTrue(coordinator.pipeline.awaitFinished(20_000))
        val counters = coordinator.counters()
        assertEquals(totalSamples.toLong(), counters.producedSamples)
        assertEquals("hash INDEPENDENTE: reconstruído == fonte (amostras e ordem)", sha(data), shaOf(md))
        assertEquals(totalSamples / 1000, chunkCount.get())
        assertTrue(counters.isBalanced())
        dir.deleteRecursively()
    }
}
