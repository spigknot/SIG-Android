package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * R6/D2 — suíte do modo JANELA do caminho ao vivo (contexto anterior
 * limitado). Foca no que PODE se perder/duplicar de verdade:
 * - partição ÚNICA exata do fluxo (cada amostra pertence a exatamente uma
 *   janela; contexto é repetição do MESMO áudio, nunca amostra nova);
 * - janela parcial final (cauda) drenada no stop/EOF;
 * - cancel conta SÓ unique pendente;
 * - falha de fonte/storage/listener com janela;
 * - integração pipeline+committer: texto final idêntico ao decodificador
 *   exato (= não perde nem duplica palavras em nenhuma fronteira).
 */
class LiveWindowPipelineTest {

    private fun tmpDir(): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "sig-live-win-test-${System.nanoTime()}")
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

    private class Seen(
        val index: Int,
        val uniqueStart: Long,
        val uniqueEnd: Long,
        val contextSamples: Int,
        val pcm: ByteArray,
    )

    private fun recordingWorker(
        latch: CountDownLatch? = null,
        seen: CopyOnWriteArrayList<Seen>,
        silenceAbove: Long? = null,
    ) = object : LivePcmPipeline.Worker<String?> {
        override fun process(chunk: LivePcmPipeline.Chunk): String? {
            seen.add(Seen(chunk.index, chunk.uniqueStartSample, chunk.uniqueEndSample, chunk.contextSamplesInChunk, chunk.pcm.copyOf()))
            latch?.await(20, TimeUnit.SECONDS)
            if (silenceAbove != null && chunk.uniqueStartSample >= silenceAbove) return null
            return "txt-${chunk.index}"
        }

        override fun isSilence(result: String?): Boolean = result == null
    }

    private fun feedSliced(pipeline: LivePcmPipeline<String?>, data: ByteArray, sliceBytes: Int) {
        var off = 0
        while (off < data.size) {
            var n = minOf(sliceBytes, data.size - off)
            if (n % 2 != 0) n -= 1
            if (n <= 0) break
            val slice = data.copyOfRange(off, off + n)
            val t0 = System.nanoTime()
            val accepted = pipeline.feed(slice, n)
            val dtMs = (System.nanoTime() - t0) / 1_000_000
            assertTrue("feed não pode esperar o worker (${dtMs}ms)", dtMs < 250)
            assertEquals(n, accepted)
            off += n
        }
    }

    private fun assertPartitionAndPcm(data: ByteArray, chunks: List<Seen>, contextSamples: Int) {
        var next = 0L
        for (c in chunks) {
            assertEquals("ordem/cobertura contínua", next, c.uniqueStart)
            assertTrue("janela com trecho único", c.uniqueEnd > c.uniqueStart)
            next = c.uniqueEnd
            // trecho único = bytes exatos da fonte
            val u0 = c.uniqueStart.toInt()
            val uLen = (c.uniqueEnd - c.uniqueStart).toInt()
            for (i in 0 until uLen * 2) {
                assertEquals("byte único difere da fonte (pos $i da janela ${c.index})", data[u0 * 2 + i], c.pcm[c.contextSamples * 2 + i])
            }
            // contexto = bytes exatos da fonte imediatamente anteriores
            if (c.contextSamples > 0) {
                val cs = u0 - c.contextSamples
                assertTrue("contexto existe no fluxo", cs >= 0)
                for (i in 0 until c.contextSamples * 2) {
                    assertEquals("byte de contexto difere da fonte", data[cs * 2 + i], c.pcm[i])
                }
                assertTrue("contexto <= teto", c.contextSamples <= contextSamples)
            }
        }
        assertEquals("o fluxo inteiro tem dono", data.size / 2L, next)
    }

    // ================================================================ 1

    @Test
    fun `janela com contexto - particao unica exata sem perda nem duplicacao`() {
        val hop = 640
        val ctx = 480
        val total = hop * 7 + 333
        val data = pattern(total)
        val seen = CopyOnWriteArrayList<Seen>()
        val pipeline = LivePcmPipeline(
            chunkSamples = hop,
            worker = recordingWorker(seen = seen),
            spoolDir = tmpDir(),
            contextSamples = ctx,
        )
        pipeline.start()
        feedSliced(pipeline, data, sliceBytes = 2048)
        pipeline.requestStop()
        assertTrue(pipeline.awaitFinished(10_000))
        val counters = pipeline.counters()
        assertEquals(total.toLong(), counters.capturedSamples)
        assertEquals(total.toLong(), counters.uniqueSamples)
        assertEquals(total.toLong(), counters.processedSamples)
        assertEquals(total.toLong(), counters.okSamples)
        assertEquals(0L, counters.lostSamples)
        assertEquals(0L, counters.cancelledSamples)
        assertTrue(counters.isBalanced())
        assertTrue(counters.isConsistent())
        assertEquals("decode = único + contexto", counters.enqueuedSamples, counters.uniqueSamples + counters.contextualSamples)
        assertTrue("houve contexto repetido (janela deslizante)", counters.contextualSamples > 0)
        assertPartitionAndPcm(data, seen, ctx)
        // primeira janela não tem contexto completo; as seguintes têm até o teto
        assertTrue(seen.size >= 8)
        assertTrue(seen.drop(1).all { it.contextSamples == ctx })
    }

    // ================================================================ 2

    @Test
    fun `cauda parcial final - drenada com contexto e cobertura fecha`() {
        val hop = 500
        val ctx = 300
        val total = hop * 2 + 7
        val data = pattern(total, seed = 777)
        val seen = CopyOnWriteArrayList<Seen>()
        val pipeline = LivePcmPipeline(chunkSamples = hop, worker = recordingWorker(seen = seen), spoolDir = tmpDir(), contextSamples = ctx)
        pipeline.start()
        pipeline.feed(data, data.size)
        pipeline.requestStop()
        assertTrue(pipeline.awaitFinished(10_000))
        val counters = pipeline.counters()
        assertTrue(counters.isBalanced())
        assertEquals(total.toLong(), counters.capturedSamples)
        assertEquals(7, seen.last().uniqueEnd - seen.last().uniqueStart)
        assertEquals(ctx, seen.last().contextSamples)
        assertPartitionAndPcm(data, seen, ctx)
    }

    // ================================================================ 3

    @Test
    fun `cancel em janela - so intervalo unico pendente e contado`() {
        val hop = 400
        val ctx = 300
        val latch = CountDownLatch(1)
        val seen = CopyOnWriteArrayList<Seen>()
        val pipeline = LivePcmPipeline(chunkSamples = hop, worker = recordingWorker(latch = latch, seen = seen), spoolDir = tmpDir(), contextSamples = ctx)
        pipeline.start()
        val total = hop * 5
        val data = pattern(total)
        pipeline.feed(data, data.size)
        // espera o worker pegar a 1ª janela e travar no latch
        val deadline = System.currentTimeMillis() + 5000
        while (seen.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(1, seen.size)
        pipeline.cancel()
        latch.countDown()
        assertTrue(pipeline.awaitFinished(10_000))
        val counters = pipeline.counters()
        assertEquals("cancelado = capturado - processado (SÓ unique)", total - hop.toLong(), counters.cancelledSamples)
        assertEquals(hop.toLong(), counters.processedSamples)
        assertEquals("cancelado + processado == capturado", total.toLong(), counters.cancelledSamples + counters.processedSamples)
        assertEquals(total.toLong(), counters.capturedSamples)
        assertTrue("balanço fecha mesmo no cancel", counters.isBalanced())
        // o contexto repetido NUNCA entra em cancelled/processed
        assertTrue(counters.contextualSamples > 0)
        assertEquals(total.toLong(), counters.uniqueSamples)
    }

    // ================================================================ 4

    @Test
    fun `falha de fonte com janela - o aceito vira janela final e o balanco fecha`() {
        val hop = 300
        val ctx = 200
        val seen = CopyOnWriteArrayList<Seen>()
        val pipeline = LivePcmPipeline(chunkSamples = hop, worker = recordingWorker(seen = seen), spoolDir = tmpDir(), contextSamples = ctx)
        pipeline.start()
        val chunks = 3
        val data = pattern(hop * chunks + 140)
        pipeline.feed(data, data.size)
        pipeline.markSourceFailure("falha injetada de teste")
        assertTrue(pipeline.awaitFinished(10_000))
        val counters = pipeline.counters()
        assertNotNull(counters.sourceFailure)
        assertTrue(counters.isBalanced())
        assertEquals(data.size / 2L, counters.capturedSamples)
        assertPartitionAndPcm(data, seen, ctx)
    }

    // ================================================================ 5

    @Test
    fun `spool limitado em janela - quantifica o perdido sem inflar contexto`() {
        val hop = 500
        val ctx = 400
        val latch = CountDownLatch(1)
        val seen = CopyOnWriteArrayList<Seen>()
        val windowBytes = (hop + ctx) * 2
        val pipeline = LivePcmPipeline(
            chunkSamples = hop,
            worker = recordingWorker(latch = latch, seen = seen),
            spoolDir = tmpDir(),
            maxSpoolBytes = (windowBytes * 2).toLong(),
            contextSamples = ctx,
        )
        pipeline.start()
        val total = hop * 10
        val data = pattern(total)
        var off = 0
        var acceptedTotal = 0
        while (off < data.size) {
            val n = minOf(hop * 2 * 3, data.size - off)
            val slice = data.copyOfRange(off, off + n)
            val accepted = pipeline.feed(slice, n)
            acceptedTotal += accepted
            off += n
            if (accepted == 0) break
        }
        latch.countDown()
        pipeline.requestStop()
        assertTrue(pipeline.awaitFinished(10_000))
        val counters = pipeline.counters()
        assertNotNull("storage cheio tem rosto", counters.storageFailure)
        assertEquals("produzido = aceito + perdido", counters.producedSamples, counters.capturedSamples + counters.lostSamples)
        assertTrue("aceito vira capturado", acceptedTotal.toLong() / 2 == counters.capturedSamples)
        assertTrue("produzido > 0 e nada alem do alimentado", counters.producedSamples in 1..total.toLong())
        assertTrue(counters.isBalanced())
    }

    // ================================================================ 6

    @Test
    fun `listener que lanca em janela - terminal garantido e erro contado`() {
        val hop = 300
        val ctx = 200
        val finished = CountDownLatch(1)
        val pipeline = LivePcmPipeline(
            chunkSamples = hop,
            worker = object : LivePcmPipeline.Worker<String?> {
                override fun process(chunk: LivePcmPipeline.Chunk): String? = "txt"
                override fun isSilence(result: String?): Boolean = false
            },
            spoolDir = tmpDir(),
            contextSamples = ctx,
            listener = object : LivePcmPipeline.Listener<String?> {
                override fun onChunkProcessed(chunk: LivePcmPipeline.Chunk, result: String?, ok: Boolean) {
                    throw RuntimeException("listener quebrado de propósito")
                }

                override fun onFinished(cancelled: Boolean) { finished.countDown() }
            },
        )
        pipeline.start()
        val data = pattern(hop * 3)
        pipeline.feed(data, data.size)
        pipeline.requestStop()
        assertTrue("terminal garantido mesmo com listener lançando", pipeline.awaitFinished(10_000))
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        assertTrue(pipeline.counters().listenerErrors >= 2)
        assertTrue(pipeline.counters().isBalanced())
    }

    // ================================================================ 7

    @Test
    fun `fixado 2s sem contexto - comportamento classico preservado (regressao)`() {
        val hop = 400
        val total = hop * 3
        val data = pattern(total)
        val seen = CopyOnWriteArrayList<Seen>()
        val pipeline = LivePcmPipeline(chunkSamples = hop, worker = recordingWorker(seen = seen), spoolDir = tmpDir())
        pipeline.start()
        feedSliced(pipeline, data, sliceBytes = 512)
        pipeline.requestStop()
        assertTrue(pipeline.awaitFinished(10_000))
        val counters = pipeline.counters()
        assertEquals(0L, counters.contextualSamples)
        assertEquals(counters.capturedSamples, counters.uniqueSamples)
        assertEquals(counters.capturedSamples, counters.enqueuedSamples)
        assertTrue(counters.isBalanced())
        assertTrue(seen.all { it.contextSamples == 0 })
        assertPartitionAndPcm(data, seen, 0)
    }

    // ================================================================ 8 (integração)

    @Test
    fun `pipeline janela + committer - texto final igual ao decodificador exato`() {
        // verdade: 40 palavras de 0,4 s (6400 amostras); "decoder" devolve a
        // sequência exata das palavras cobertas pela janela [ws, ue]
        val wordSamples = 6400
        val nWords = 40
        val total = wordSamples * nWords
        val words = (0 until nWords).map { "w%02d".format(it) }
        val data = pattern(total, seed = 4242)
        val hop = wordSamples * 4        // 4 palavras por janela (1,6 s)
        val ctx = wordSamples * 3        // contexto 1,2 s
        val committer = LiveTextCommitter()
        val seen = CopyOnWriteArrayList<Seen>()
        val textOfWindow = { ws: Long, ue: Long ->
            val first = (ws / wordSamples).toInt().coerceAtLeast(0)
            val last = ((ue - 1) / wordSamples).toInt()
            words.subList(first, (last + 1).coerceAtMost(words.size)).joinToString(" ")
        }
        val pipeline = LivePcmPipeline(
            chunkSamples = hop,
            worker = object : LivePcmPipeline.Worker<String?> {
                override fun process(chunk: LivePcmPipeline.Chunk): String? {
                    seen.add(Seen(chunk.index, chunk.uniqueStartSample, chunk.uniqueEndSample, chunk.contextSamplesInChunk, chunk.pcm.copyOf()))
                    val ws = chunk.uniqueStartSample - chunk.contextSamplesInChunk
                    return textOfWindow(ws, chunk.uniqueEndSample)
                }

                override fun isSilence(result: String?): Boolean = false
            },
            spoolDir = tmpDir(),
            contextSamples = ctx,
            listener = object : LivePcmPipeline.Listener<String?> {
                var last = ""
                override fun onChunkProcessed(chunk: LivePcmPipeline.Chunk, result: String?, ok: Boolean) {
                    if (result != null && result.isNotBlank()) {
                        val info = committer.onWindow(chunk.index, result, chunk.uniqueStartSample, chunk.uniqueEndSample, chunk.contextSamplesInChunk)
                        if (info != null) last = info.alignment
                    }
                }

                override fun onFinished(cancelled: Boolean) { if (!cancelled) committer.finalize() else committer.cancel() }
            },
        )
        pipeline.start()
        feedSliced(pipeline, data, sliceBytes = 3000)
        pipeline.requestStop()
        assertTrue(pipeline.awaitFinished(10_000))
        val counters = pipeline.counters()
        assertTrue(counters.isBalanced())
        assertPartitionAndPcm(data, seen, ctx)
        assertEquals("texto final == verdade (sem perda/duplicação de palavra)", words.joinToString(" "), committer.committedText())
        assertEquals(nWords, committer.committedWordCount())
        assertEquals(0, committer.provisionalWordCount())
    }
}
