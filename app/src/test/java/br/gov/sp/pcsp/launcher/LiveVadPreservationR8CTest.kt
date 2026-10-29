package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList

/**
 * R8/C — RED→GREEN da PRESERVAÇÃO do VAD live: fala baixa/incerta/cauda NÃO
 * pode virar silêncio por histórico de pico nem por limiar de energia.
 *
 * Antes do patch R8/C (fontes congeladas em
 * build/whisper-rodada8/20261011-1544/fase-c/reproducer/antes-src) estes testes
 * FALHAM: o trecho com sinal fica coberto=0 e é contado como "gap". Depois:
 * todo trecho NÃO NULO entra em janela decodificada (speech/forced/tail/
 * uncertain) e o silêncio contado é SÓ zero-digital. O contrato da suíte E3
 * continua valendo: janelas + silêncio contado == aceito (nada some sem número).
 */
class LiveVadPreservationR8CTest {

    private val sr = 16_000

    private fun zeros(samples: Int): List<Short> = List(samples) { 0.toShort() }

    /** Onda alternada (mesmo formato do reproducer da auditoria). */
    private fun wave(samples: Int, amp: Int): List<Short> =
        (0 until samples).map { i -> if (i % 2 == 0) amp.toShort() else (-amp).toShort() }

    private fun toBytes(v: List<Short>): ByteArray {
        val out = ByteArray(v.size * 2)
        for (i in v.indices) {
            val s = v[i].toInt()
            out[i * 2] = (s and 0xFF).toByte()
            out[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun feedAll(seg: LiveVadSegmenter, data: ByteArray, chunkBytes: Int = 64_000): List<PlannedWindow> {
        val out = ArrayList<PlannedWindow>()
        var off = 0
        while (off < data.size) {
            val n = minOf(chunkBytes, data.size - off)
            out += seg.accept(data, off, n)
            off += n
        }
        out += seg.flush()
        return out
    }

    private fun covered(out: List<PlannedWindow>, from: Long, to: Long): Long =
        out.sumOf { w ->
            val a = maxOf(w.startSample, from)
            val b = minOf(w.endSample, to)
            if (b > a) b - a else 0L
        }

    private fun skippedBySilence(seg: LiveVadSegmenter, from: Long, to: Long): Long =
        seg.stats().silenceIntervals.sumOf { iv ->
            val a = maxOf(iv.startSample, from)
            val b = minOf(iv.endSample, to)
            if (b > a) b - a else 0L
        }

    private fun windowSamples(out: List<PlannedWindow>): Long = out.sumOf { it.endSample - it.startSample }

    // ------------------------------------------------------------------ testes

    @Test
    fun `onda baixa depois de pico entra em janela - caso do reproducer`() {
        val seg = LiveVadSegmenter(sr, padMs = 300, silenceCutMs = 3_000, maxSegmentMs = 15_000)
        val stream = toBytes(wave(32_000, 6_000) + zeros(32_000) + wave(160_000, 300) + zeros(64_000))
        val out = feedAll(seg, stream)
        val lo = 64_000L
        val hi = 224_000L
        assertEquals("faixa baixa tem de estar 100% decodificada", hi - lo, covered(out, lo, hi))
        assertEquals("silêncio contado não pode cobrir a fala baixa", 0L, skippedBySilence(seg, lo, hi))
        // contabilidade fecha e o silêncio contado é SÓ zero-digital
        assertEquals(288_000L, windowSamples(out) + seg.skippedSilenceSamples)
        assertEquals(0L, seg.pendingSamples)
        assertTrue(seg.stats().silenceIntervals.all { it.kind == "zero-digital" })
    }

    @Test
    fun `n06 aprovado isolado e apos pico - cobertura integral`() {
        val f = File("D:/SIG-whisper-r1/build/whisper-rodada7/20261010-2118/fase-e/audio/n06.pcm")
        assumeTrue("PCM aprovado n06 ausente", f.isFile)
        val voice = f.readBytes()
        val sha = MessageDigest.getInstance("SHA-256").digest(voice).joinToString("") { "%02x".format(it) }
        assertEquals("8663bd8a7aefb24f071ddc6e9d381a3cd9499e8e13c34d1adbfd839d671e2270", sha)

        // (a) isolado (controle): o áudio em nível baixo já era decodificado
        val segA = LiveVadSegmenter(sr, padMs = 300, silenceCutMs = 3_000, maxSegmentMs = 15_000)
        val outA = feedAll(segA, voice + toBytes(zeros(32_000)))
        assertEquals(212_480L, covered(outA, 0L, 212_480L))
        assertEquals(0L, skippedBySilence(segA, 0L, 212_480L))

        // (b) após 1 s de +-6000 + 1 s de zero: RED antes do patch (0 coberto)
        val segB = LiveVadSegmenter(sr, padMs = 300, silenceCutMs = 3_000, maxSegmentMs = 15_000)
        val streamB = toBytes(wave(32_000, 6_000) + zeros(32_000)) + voice + toBytes(zeros(64_000))
        val outB = feedAll(segB, streamB)
        val lo = 64_000L
        val hi = 64_000L + 212_480L
        assertEquals("n06 após pico tem de ser 100% decodificado", 212_480L, covered(outB, lo, hi))
        assertEquals(0L, skippedBySilence(segB, lo, hi))
        assertEquals(streamB.size / 2L, windowSamples(outB) + segB.skippedSilenceSamples)
    }

    @Test
    fun `ruido nao nulo abaixo do piso vira janela uncertain`() {
        val seg = LiveVadSegmenter(sr, padMs = 300, silenceCutMs = 3_000, maxSegmentMs = 15_000)
        // ruído determinístico ±8 (rms ~4,9 < piso 10): não é zero-digital e não
        // confirma fala — o contrato exige PRESERVAR e decodificar (uncertain).
        val noise = (0 until 12 * sr).map { i -> (((i * 7) % 17) - 8).toShort() }
        val out = feedAll(seg, toBytes(noise))
        assertEquals(192_000L, covered(out, 0L, 192_000L))
        assertEquals(0L, skippedBySilence(seg, 0L, 192_000L))
        assertTrue("trecho incerto tem de virar janela decodificada", out.any { it.kind == "uncertain" })
        assertEquals(0L, seg.pendingSamples)
    }

    @Test
    fun `silencio total continua atalho zero-digital`() {
        val seg = LiveVadSegmenter(sr, padMs = 300, silenceCutMs = 3_000, maxSegmentMs = 15_000)
        val out = feedAll(seg, toBytes(zeros(60 * sr)))
        assertTrue("silencio total nao pode pagar decode", out.isEmpty())
        assertEquals(960_000L, seg.skippedSilenceSamples)
        assertTrue(seg.stats().silenceIntervals.all { it.kind == "zero-digital" })
    }

    @Test
    fun `fala curta menor que 1s com flush nao some`() {
        val seg = LiveVadSegmenter(sr, padMs = 300, silenceCutMs = 3_000, maxSegmentMs = 15_000)
        val out = feedAll(seg, toBytes(wave(11_200, 2_000) + zeros(32_000)))
        assertEquals(11_200L, covered(out, 0L, 11_200L))
        assertEquals(0L, skippedBySilence(seg, 0L, 11_200L))
    }

    @Test
    fun `repeticoes legitimas continuam cobertas`() {
        val seg = LiveVadSegmenter(sr, padMs = 300, silenceCutMs = 3_000, maxSegmentMs = 15_000)
        val unit = 16_000
        val stream = toBytes(
            wave(unit, 2_000) + zeros(8_000) + wave(unit, 2_000) + zeros(8_000) + wave(unit, 2_000)
        )
        val out = feedAll(seg, stream)
        assertEquals(unit.toLong(), covered(out, 0L, unit.toLong()))
        assertEquals(unit.toLong(), covered(out, (unit + 8_000).toLong(), (2 * unit + 8_000).toLong()))
        assertEquals(unit.toLong(), covered(out, (2 * unit + 16_000).toLong(), (3 * unit + 16_000).toLong()))
    }

    @Test
    fun `contexto do pipeline e audio real precedente mesmo com lacuna`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "sig-vad-ctx-${System.nanoTime()}")
        dir.mkdirs()
        val ctx = 16_000 // 1 s > pad: o tail da janela anterior conteria FALA (caso RED do contrato)
        val seg = LiveVadSegmenter(sr, padMs = 300, silenceCutMs = 700, maxSegmentMs = 15_000)
        val chunks = CopyOnWriteArrayList<LivePcmPipeline.Chunk>()
        val pipeline = LivePcmPipeline<String?>(
            chunkSamples = 15_000,
            contextSamples = ctx,
            planner = seg,
            spoolDir = dir,
            worker = object : LivePcmPipeline.Worker<String?> {
                override fun process(chunk: LivePcmPipeline.Chunk): String? {
                    chunks.add(chunk)
                    return "t${chunk.index}"
                }
            },
        )
        pipeline.start()
        // fala (1,5 s) + lacuna de 3 s (pulada como zero-digital) + fala (1,5 s)
        val stream = toBytes(wave(24_000, 2_500) + zeros(48_000) + wave(24_000, 2_500))
        var off = 0
        while (off < stream.size) {
            val n = minOf(3_200, stream.size - off)
            assertEquals("feed recusou com espaço no spool", n, pipeline.feed(stream.copyOfRange(off, off + n), n))
            off += n
        }
        pipeline.requestStop()
        assertTrue(pipeline.awaitFinished(20_000))

        assertTrue("esperava mais de uma janela", chunks.size >= 2)
        // TODO prefixo de contexto tem de ser o áudio REAL imediatamente anterior
        for (chunk in chunks) {
            val len = chunk.contextSamplesInChunk
            val start = chunk.uniqueStartSample - len
            assertTrue("prefixo fora do fluxo (chunk ${chunk.index})", start >= 0L)
            val expected = stream.copyOfRange((start * 2).toInt(), (chunk.uniqueStartSample * 2).toInt())
            val actual = chunk.pcm.copyOfRange(0, len * 2)
            assertArrayEquals(
                "contexto do chunk ${chunk.index} não é o áudio real precedente",
                expected, actual
            )
        }
        // a janela DEPOIS da lacuna tem contexto REAL da lacuna (zeros), não a
        // cauda antiga de fala da primeira janela (comportamento RED do defeito)
        val afterGap = chunks.filter { it.uniqueStartSample >= 67_000L }
        assertTrue("esperava janela depois da lacuna", afterGap.isNotEmpty())
        for (chunk in afterGap) {
            assertTrue("contexto pós-lacuna deveria existir", chunk.contextSamplesInChunk > 0)
            assertTrue(
                "contexto pós-lacuna tem de ser a lacuna real (zeros)",
                chunk.pcm.copyOfRange(0, chunk.contextSamplesInChunk * 2).all { it == 0.toByte() }
            )
        }
        val c = pipeline.counters()
        assertTrue("contadores não fecham: ${c.summary()}", c.isBalanced())
        assertTrue("inconsistente: ${c.summary()}", c.isConsistent())
        assertEquals(c.capturedSamples, c.uniqueSamples + c.silenceSkippedSamples)
        assertNotNull(pipeline.planStats())
        pipeline.close()
    }
}
