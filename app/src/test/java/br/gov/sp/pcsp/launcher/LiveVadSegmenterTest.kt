package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * R7/E3 — suíte da ESTRATÉGIA (3) VAD segmentando intervalos. Foca no que
 * PODE se perder/duplicar de verdade:
 *  - partição: cada amostra aceita sai como JANELA (decodificada) ou como
 *    SILÊNCIO CONTADO com intervalo registrado — nunca sem número;
 *  - fala fraca (−30 dB) NÃO é descartada (decisão do VAD, não do portão);
 *  - duração máxima: cortes em fronteira de frame, sem sobreposição de único;
 *  - flush: cauda com sinal nunca some; digital-zero puro é contado;
 *  - integração com o pipeline: contadores fecham (capturado == processado +
 *    cancelado + silêncio contado) e o terminal bate com as janelas.
 */
class LiveVadSegmenterTest {

    private val sr = 16_000

    // ------------------------------------------------------------- geradores

    /** Fala sintética: alterna amplitude alta (RMS ~1200, pico ~2500). */
    private fun speech(samples: Int, amp: Int = 2500): List<Short> =
        (0 until samples).map { i -> if ((i / 40) % 2 == 0) amp.toShort() else (-amp).toShort() }

    /** Fala FRACA (−30 dB do sinal cheio): RMS ~79, pico ~79. */
    private fun weakSpeech(samples: Int, amp: Int = 79): List<Short> =
        (0 until samples).map { i -> if ((i / 40) % 2 == 0) amp.toShort() else (-amp).toShort() }

    private fun zeros(samples: Int): List<Short> = List(samples) { 0.toShort() }

    private fun toBytes(v: List<Short>): ByteArray {
        val out = ByteArray(v.size * 2)
        for (i in v.indices) {
            val s = v[i].toInt()
            out[i * 2] = (s and 0xFF).toByte()
            out[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun sumWindows(w: List<PlannedWindow>): Long =
        w.sumOf { (it.endSample - it.startSample) }

    // ------------------------------------------------------------- testes do VAD

    @Test
    fun `contabilidade fecha - nada some sem numero`() {
        val seg = LiveVadSegmenter(sr, padMs = 300, silenceCutMs = 700, maxSegmentMs = 12_000)
        val stream = toBytes(zeros(16_000) + speech(32_000) + zeros(32_000) + weakSpeech(16_000) + zeros(8_000))
        val total = (stream.size / 2).toLong()
        val out = ArrayList<PlannedWindow>()
        var off = 0
        while (off < stream.size) {
            val n = minOf(3_200, stream.size - off)
            out += seg.accept(stream, off, n)
            off += n
        }
        out += seg.flush()

        assertEquals(0L, seg.pendingSamples)
        assertEquals(total, sumWindows(out) + seg.skippedSilenceSamples)

        // janelas não se sobrepõem e estão em ordem
        var lastEnd = -1L
        for (w in out) {
            assertTrue("janela fora de ordem/sobreposta: ${w.startSample} < $lastEnd", w.startSample >= lastEnd)
            lastEnd = w.endSample
        }
        // silêncio longo contado com intervalo registrado
        val st = seg.stats()
        assertTrue("deveria contar silêncio (skipped=${st.skippedSilenceSamples})", st.skippedSilenceSamples > 16_000)
        assertTrue(st.silenceIntervals.isNotEmpty())
        assertTrue(st.silenceIntervals.all { it.endSample > it.startSample })
    }

    @Test
    fun `fala fraca -30db nunca e descartada`() {
        val seg = LiveVadSegmenter(sr, padMs = 300, silenceCutMs = 700, maxSegmentMs = 12_000)
        val weakStart = 16_000L
        val weakSamples = 24_000
        val stream = toBytes(zeros(16_000) + weakSpeech(weakSamples) + zeros(16_000))
        val out = ArrayList<PlannedWindow>()
        var off = 0
        while (off < stream.size) {
            val n = minOf(3_200, stream.size - off)
            out += seg.accept(stream, off, n)
            off += n
        }
        out += seg.flush()

        // o trecho de fala fraca tem de estar COBERTO por janelas decodificadas
        val weakEnd = weakStart + weakSamples
        val covered = out.sumOf { w ->
            val a = maxOf(w.startSample, weakStart)
            val b = minOf(w.endSample, weakEnd)
            if (b > a) b - a else 0L
        }
        assertEquals("fala fraca não pode ser ignorada", weakSamples.toLong(), covered)
        // e nenhum intervalo de silêncio pode cobrir a fala fraca
        for (iv in seg.stats().silenceIntervals) {
            assertFalse(
                "intervalo de silêncio (${iv.startSample},${iv.endSample}) invade a fala fraca",
                iv.startSample < weakEnd && iv.endSample > weakStart
            )
        }
    }

    @Test
    fun `duracao maxima corta sem sobrepor e sem perder amostra`() {
        val maxMs = 1_000L
        val seg = LiveVadSegmenter(sr, padMs = 300, silenceCutMs = 700, maxSegmentMs = maxMs)
        val totalSamples = 40_000 // 2,5 s de fala contínua
        val stream = toBytes(speech(totalSamples))
        val out = ArrayList<PlannedWindow>()
        var off = 0
        while (off < stream.size) {
            val n = minOf(2_000, stream.size - off)
            out += seg.accept(stream, off, n)
            off += n
        }
        out += seg.flush()

        val maxWindow = (maxMs * sr / 1000) + 300L * sr / 1000
        assertEquals(0L, seg.pendingSamples)
        assertEquals(totalSamples.toLong(), sumWindows(out) + seg.skippedSilenceSamples)
        assertTrue("deveria haver cortes forçados (janelas=${out.size})", seg.stats().forcedCuts >= 2)
        for (w in out) assertTrue("janela acima do máximo+pad: ${w.endSample - w.startSample}", w.endSample - w.startSample <= maxWindow)
        assertTrue("fala contínua não pode ter silêncio contado", seg.skippedSilenceSamples == 0L)
        // sem sobreposição de único (nenhuma amostra decodificada duas vezes)
        var lastEnd = -1L
        for (w in out) {
            assertTrue("sobreposição de único em ${w.startSample} < $lastEnd", w.startSample >= lastEnd)
            lastEnd = w.endSample
        }
    }

    @Test
    fun `flush preserva cauda e conta zero-digital`() {
        val seg = LiveVadSegmenter(sr, padMs = 300, silenceCutMs = 700, maxSegmentMs = 12_000)
        val speechLen = 20_000
        val tailZeros = 8_000
        val stream = toBytes(speech(speechLen) + zeros(tailZeros))
        val out = ArrayList<PlannedWindow>()
        var off = 0
        while (off < stream.size) {
            val n = minOf(1_600, stream.size - off)
            out += seg.accept(stream, off, n)
            off += n
        }
        out += seg.flush()

        assertEquals(0L, seg.pendingSamples)
        assertEquals((speechLen + tailZeros).toLong(), sumWindows(out) + seg.skippedSilenceSamples)
        // a cauda de FALA está coberta
        val covered = out.sumOf { w -> maxOf(0L, minOf(w.endSample, speechLen.toLong()) - maxOf(w.startSample, 0L)) }
        assertEquals(speechLen.toLong(), covered)
    }

    @Test
    fun `digital zero no inicio e contado como intervalo`() {
        val seg = LiveVadSegmenter(sr, padMs = 300, silenceCutMs = 700, maxSegmentMs = 12_000)
        val stream = toBytes(zeros(24_000) + speech(16_000))
        val out = ArrayList<PlannedWindow>()
        var off = 0
        while (off < stream.size) {
            val n = minOf(4_000, stream.size - off)
            out += seg.accept(stream, off, n)
            off += n
        }
        out += seg.flush()
        val first = out.first()
        // o padding antes da fala é preservado (não pode começar exatamente na fala)
        assertTrue("janela deveria começar no padding: ${first.startSample}", first.startSample <= 24_000 - 0)
        assertTrue("janela deveria começar antes do padding máximo: ${first.startSample}", first.startSample >= 24_000 - 300 * 16)
        assertTrue(seg.skippedSilenceSamples >= 24_000 - 300 * 16)
    }

    @Test
    fun `corte forcado prefere silencio em vez do meio da palavra`() {
        // fala contínua com um silêncio curto (400 ms) em 5,0-5,4 s; max = 6 s
        // → o corte forçado deve cair no SILÊNCIO (5,4 s), não no teto de 6,0 s.
        val seg = LiveVadSegmenter(sr, padMs = 100, silenceCutMs = 5_000, maxSegmentMs = 6_000)
        val stream = toBytes(speech(5 * sr) + zeros(400 * sr / 1000) + speech(12 * sr))
        val out = ArrayList<PlannedWindow>()
        var off = 0
        while (off < stream.size) {
            val n = minOf(3_200, stream.size - off)
            out += seg.accept(stream, off, n)
            off += n
        }
        out += seg.flush()
        val forced = out.filter { it.kind == "forced" }
        assertTrue("esperava corte forçado", forced.isNotEmpty())
        val cut = forced.first().endSample
        assertTrue("corte forçado longe do silêncio: $cut (esperado ~5,4 s)", kotlin.math.abs(cut - 5_400 * 16) <= 2 * 320)
        assertEquals(0L, seg.pendingSamples)
    }

    @Test
    fun `fixed planner mantem blocos e cauda`() {
        val p = FixedWindowPlanner(1_000)
        val data = ByteArray(2_500 * 2)
        val out = ArrayList<PlannedWindow>()
        out += p.accept(data, 0, 2_500 * 2)
        out += p.flush()
        assertEquals(3, out.size)
        assertEquals(0L, out[0].startSample)
        assertEquals(1_000L, out[0].endSample)
        assertEquals(1_000L, out[1].startSample)
        assertEquals(2_000L, out[1].endSample)
        assertEquals(2_000L, out[2].startSample)
        assertEquals(2_500L, out[2].endSample)
        assertEquals(2_500L, out.sumOf { (it.endSample - it.startSample) })
    }

    // ------------------------------------------------- integração com o pipeline

    @Test
    fun `pipeline com planner fecha contadores e terminal bate com janelas`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "sig-vad-pipe-${System.nanoTime()}")
        dir.mkdirs()
        val seg = LiveVadSegmenter(sr, padMs = 300, silenceCutMs = 700, maxSegmentMs = 6_000)
        val seen = CopyOnWriteArrayList<LongArray>()
        val pipeline = LivePcmPipeline<String?>(
            chunkSamples = 6_000,
            contextSamples = 3_200,
            planner = seg,
            spoolDir = dir,
            worker = object : LivePcmPipeline.Worker<String?> {
                override fun process(chunk: LivePcmPipeline.Chunk): String? {
                    seen.add(longArrayOf(chunk.uniqueStartSample, chunk.uniqueEndSample))
                    return "t${chunk.index}"
                }
            },
        )
        pipeline.start()
        val stream = toBytes(zeros(16_000) + speech(32_000) + zeros(24_000) + weakSpeech(16_000) + zeros(8_000))
        var off = 0
        while (off < stream.size) {
            val n = minOf(3_200, stream.size - off)
            assertEquals("feed recusou com espaço no spool", n, pipeline.feed(stream.copyOfRange(off, off + n), n))
            off += n
        }
        pipeline.requestStop()
        assertTrue(pipeline.awaitFinished(20_000))

        val c = pipeline.counters()
        assertTrue("contadores não fecham: ${c.summary()}", c.isBalanced())
        assertTrue("inconsistente: ${c.summary()}", c.isConsistent())
        assertEquals(0L, c.pendingSamples())
        val plan = pipeline.planStats()
        assertNotNull(plan)
        assertEquals(seen.size.toLong(), (plan!!.windows).toLong())
        // terminal == soma das janelas (não infla contexto no único)
        assertEquals(c.uniqueSamples, seen.sumOf { it[1] - it[0] })
        // nada de amostra sem rosto: único decodificado + silêncio contado == aceito
        assertEquals(c.capturedSamples, c.uniqueSamples + c.silenceSkippedSamples)
        pipeline.close()
    }

    @Test
    fun `pipeline com planner - cancel conta pendente e mantem silencio contado`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "sig-vad-cancel-${System.nanoTime()}")
        dir.mkdirs()
        val seg = LiveVadSegmenter(sr, padMs = 300, silenceCutMs = 700, maxSegmentMs = 30_000)
        val pipeline = LivePcmPipeline<String?>(
            chunkSamples = 30_000,
            contextSamples = 0,
            planner = seg,
            spoolDir = dir,
            worker = object : LivePcmPipeline.Worker<String?> {
                override fun process(chunk: LivePcmPipeline.Chunk): String? = "x"
            },
        )
        pipeline.start()
        val stream = toBytes(zeros(16_000) + speech(16_000))
        pipeline.feed(stream, stream.size)
        pipeline.cancel()
        assertTrue(pipeline.awaitFinished(20_000))
        val c = pipeline.counters()
        assertTrue("cancel não fecha: ${c.summary()}", c.isConsistent())
        assertEquals(0L, c.pendingSamples())
    }

    @Test
    fun `primeira janela curta por teto proprio depois janelas longas`() {
        // R8/D (candidata 1): fala contínua de 40 s, corte de silêncio longo —
        // sem teto próprio o 1º texto sairia perto do teto cheio (12 s). Com
        // firstSegmentMs=4 s a 1ª janela fecha em ~4,3 s (teto+pad); da 2ª em
        // diante vale o teto cheio (janelas longas estáveis).
        val seg = LiveVadSegmenter(
            sr, padMs = 300, silenceCutMs = 5_000, maxSegmentMs = 12_000, firstSegmentMs = 4_000
        )
        val stream = toBytes(speech(40 * sr))
        val out = ArrayList<PlannedWindow>()
        var off = 0
        while (off < stream.size) {
            val n = minOf(3_200, stream.size - off)
            out += seg.accept(stream, off, n)
            off += n
        }
        out += seg.flush()
        assertTrue("esperava >=2 janelas, veio ${out.size}", out.size >= 2)
        val firstMs = (out[0].endSample - out[0].startSample) * 1000.0 / sr
        assertEquals("forced", out[0].kind)
        assertTrue("1a janela fora do esperado: ${firstMs}ms", firstMs in 4_000.0..4_800.0)
        val secondMs = (out[1].endSample - out[1].startSample) * 1000.0 / sr
        assertTrue("2a janela devia usar o teto cheio: ${secondMs}ms", secondMs in 11_800.0..12_800.0)
        val plan = seg.stats()
        assertEquals(1, plan.firstCuts)
        assertEquals((stream.size / 2).toLong(), sumWindows(out) + seg.skippedSilenceSamples)
    }

    @Test
    fun `primeira janela fecha em silencio curto - depois corte estavel`() {
        // R8/D (candidata 1): fala 3 s + pausa 1 s + fala 10 s. Com
        // firstSilenceCutMs=700 a 1ª janela fecha logo na pausa; a 2ª IGNORA a
        // pausa de 1 s (corte estável de 3 s) — sem fragmentação repetida.
        val seg = LiveVadSegmenter(
            sr, padMs = 300, silenceCutMs = 3_000, maxSegmentMs = 12_000, firstSilenceCutMs = 700
        )
        val stream = toBytes(speech(3 * sr) + zeros(sr) + speech(10 * sr) + zeros(4 * sr))
        val out = ArrayList<PlannedWindow>()
        var off = 0
        while (off < stream.size) {
            val n = minOf(3_200, stream.size - off)
            out += seg.accept(stream, off, n)
            off += n
        }
        out += seg.flush()
        assertTrue("esperava >=2 janelas, veio ${out.size}", out.size >= 2)
        val firstMs = (out[0].endSample - out[0].startSample) * 1000.0 / sr
        assertEquals("speech", out[0].kind)
        assertTrue("1a janela fora do esperado: ${firstMs}ms", firstMs in 3_000.0..4_000.0)
        assertEquals(0, seg.stats().firstCuts)
        val secondMs = (out[1].endSample - out[1].startSample) * 1000.0 / sr
        assertTrue("2a janela fragmentou de novo: ${secondMs}ms", secondMs > 9_500.0)
        assertEquals((stream.size / 2).toLong(), sumWindows(out) + seg.skippedSilenceSamples)
    }

    @Test
    fun `R9D producao - primeira janela 2s e corte inicial 600ms depois janelas longas`() {
        // R9/D3: ESTES são os valores do DEFAULT_LIVE_STRATEGY (produto):
        // firstSegmentMs=2000, firstSilenceCutMs=600, max=15s, silencio=3s.
        // Fala contínua: a 1ª janela fecha no TETO próprio (~2,3 s), a 2ª usa o
        // teto cheio (>14 s) — a cadência longa não é reduzida em produção.
        val seg = LiveVadSegmenter(
            sr, padMs = 300, silenceCutMs = 3_000, maxSegmentMs = 15_000,
            firstSegmentMs = 2_000, firstSilenceCutMs = 600,
        )
        val stream = toBytes(speech(40 * sr))
        val out = ArrayList<PlannedWindow>()
        var off = 0
        while (off < stream.size) {
            val n = minOf(3_200, stream.size - off)
            out += seg.accept(stream, off, n)
            off += n
        }
        out += seg.flush()
        assertTrue("esperava >=2 janelas, veio ${out.size}", out.size >= 2)
        val firstMs = (out[0].endSample - out[0].startSample) * 1000.0 / sr
        assertTrue("1a janela da producao fora de ~2-2,8 s: ${firstMs}ms", firstMs in 2_000.0..2_800.0)
        assertEquals(1, seg.stats().firstCuts)
        val secondMs = (out[1].endSample - out[1].startSample) * 1000.0 / sr
        assertTrue("2a janela devia usar o teto cheio de 15 s: ${secondMs}ms", secondMs > 14_000.0)
    }

    @Test
    fun `R9D producao - primeira janela fecha em silencio curto de 600ms depois corte 3s`() {
        val seg = LiveVadSegmenter(
            sr, padMs = 300, silenceCutMs = 3_000, maxSegmentMs = 15_000,
            firstSegmentMs = 2_000, firstSilenceCutMs = 600,
        )
        val stream = toBytes(speech(1 * sr) + zeros(sr) + speech(20 * sr) + zeros(5 * sr))
        val out = ArrayList<PlannedWindow>()
        var off = 0
        while (off < stream.size) {
            val n = minOf(3_200, stream.size - off)
            out += seg.accept(stream, off, n)
            off += n
        }
        out += seg.flush()
        assertTrue("esperava >=2 janelas, veio ${out.size}", out.size >= 2)
        val firstMs = (out[0].endSample - out[0].startSample) * 1000.0 / sr
        assertTrue("1a janela devia fechar no silencio curto (~1-2,5 s): ${firstMs}ms", firstMs in 1_000.0..2_500.0)
        val secondMs = (out[1].endSample - out[1].startSample) * 1000.0 / sr
        assertTrue("2a janela nao pode fragmentar (corte 3 s ignorou a pausa curta): ${secondMs}ms", secondMs > 15_000.0)
    }
}
