package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.json.JSONObject
import org.junit.Test
import kotlin.math.sin

/**
 * Testes do pipeline puro do Granite 4.1 NAR (front-end, fp16, CTC, interleave).
 * Sem Android — roda na JVM (testDebugUnitTest).
 */
class GraniteNarEngineTest {

    @Test
    fun `benchmark protocol emits valid escaped json`() {
        val encoded = GraniteNarBenchmarkProtocol.json(
            linkedMapOf(
                "event" to "inference",
                "text" to "linha 1\n\"ação\"",
                "stage_ms" to linkedMapOf("encoder" to 123L),
                "ok" to true,
            ),
        )

        val parsed = JSONObject(encoded)
        assertEquals("linha 1\n\"ação\"", parsed.getString("text"))
        assertEquals(123L, parsed.getJSONObject("stage_ms").getLong("encoder"))
        assertTrue(parsed.getBoolean("ok"))
    }

    @Test
    fun `benchmark collector parses stages dimensions and session loads`() {
        val collector = GraniteNarBenchmarkProtocol.Collector()
        collector.accept("ONNX encoder criado (CPU) em 4321ms")
        collector.accept("NAR entrada: samples=16000 frames=50 effective_frames=50")
        collector.accept("NAR etapa encoder: 987ms")
        collector.accept("NAR sequência: ctc_tokens=7 valid_audio=10 slots=15 llm_tokens=25")
        collector.accept("NAR inferência total: 1234ms")

        assertEquals(4321L, collector.sessionLoadMs["encoder"])
        assertEquals(987L, collector.stageMs["encoder"])
        assertEquals(50L, collector.dimensions["effective_frames"])
        assertEquals(25L, collector.dimensions["llm_tokens"])
        assertEquals(1234L, collector.engineTotalMs)
    }

    @Test
    fun `benchmark run id is safe and transcript hash is stable`() {
        assertEquals("cpu_pt_01", GraniteNarBenchmarkProtocol.safeRunId("cpu pt/01"))
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            GraniteNarBenchmarkProtocol.sha256("abc"),
        )
    }

    // ---- HalfFloat ----

    @Test
    fun `halffloat converts 1_0`() {
        // 1.0 em fp16 = 0x3C00
        assertEquals(1.0f, HalfFloat.toFloat(0x3C00), 0f)
    }

    @Test
    fun `halffloat converts 0 and negative`() {
        assertEquals(0.0f, HalfFloat.toFloat(0), 0f)
        assertEquals(-1.0f, HalfFloat.toFloat(0xBC00.toShort()), 0f)
    }

    @Test
    fun `halffloat converts subnormal`() {
        // menor subnormal positivo em fp16 = 0x0001 = 2^-24
        assertEquals(5.9604645e-8f, HalfFloat.toFloat(0x0001), 1e-12f)
    }

    // ---- CTC collapse ----

    @Test
    fun `ctc collapse removes repeats and blank`() {
        // vocab 3: [0, 1, 2]; blank = 100257 (fora do vocab pequeno p/ teste, usa BLANK real)
        val vocab = 100352
        val frames = 6
        // logits com picos em ids: 100257(blank), 5, 5, 100257, 6, 100257
        val logits = FloatArray(frames * vocab)
        val ids = longArrayOf(100257L, 5L, 5L, 100257L, 6L, 100257L)
        for (t in 0 until frames) logits[t * vocab + ids[t].toInt()] = 10f
        val out = GraniteNarCtc.collapseLogits(logits, frames, vocab)
        assertTrue(out.contentEquals(intArrayOf(5, 6)))
    }

    @Test
    fun `ctc collapse blank between identical preserves both`() {
        val vocab = 100352
        val frames = 3
        val logits = FloatArray(frames * vocab)
        val ids = longArrayOf(7L, 100257L, 7L)
        for (t in 0 until frames) logits[t * vocab + ids[t].toInt()] = 10f
        val out = GraniteNarCtc.collapseLogits(logits, frames, vocab)
        assertTrue(out.contentEquals(intArrayOf(7, 7)))
    }

    @Test
    fun `ctc collapse reads frame slice without copying logits`() {
        val vocab = 100352
        val logits = FloatArray(4 * vocab)
        val ids = intArrayOf(11, 12, 13, 14)
        for (frame in ids.indices) logits[frame * vocab + ids[frame]] = 10f

        val out = GraniteNarCtc.collapseLogits(
            java.nio.FloatBuffer.wrap(logits),
            frames = 2,
            vocab = vocab,
            frameOffset = 1,
        )

        assertTrue(out.contentEquals(intArrayOf(12, 13)))
    }

    // ---- Interleave ----

    @Test
    fun `interleave inserts blank slots between tokens`() {
        val out = GraniteNarInterleave.buildSlots(intArrayOf(5, 6, 7), blank = 100257)
        // total = max(2*3+1, min_edit_sequence_length=8) = 8 -> blank extra no fim
        assertTrue(out.contentEquals(intArrayOf(100257, 5, 100257, 6, 100257, 7, 100257, 100257)))
    }

    @Test
    fun `interleave enforces min edit sequence length`() {
        val out = GraniteNarInterleave.buildSlots(intArrayOf(5), blank = 100257, minEditSequenceLength = 8)
        assertEquals(8, out.size)
        assertEquals(5, out[1])
        assertTrue(out.all { it == 100257 || it == 5 })
    }

    @Test
    fun `interleave empty ctc produces all blanks`() {
        val out = GraniteNarInterleave.buildSlots(intArrayOf(), blank = 100257)
        assertEquals(8, out.size) // max(1, 8)
        assertTrue(out.all { it == 100257 })
    }

    // ---- Frontend ----

    @Test
    fun `frontend outFrames matches 2x stacking of mel`() {
        val fe = GraniteNarFrontend(FloatArray(80 * 257), FloatArray(512) { 1f })
        // 16000 samples -> 2*(16000//320) = 100 mel frames -> 50 out frames
        assertEquals(50, fe.outFrames(16000))
        assertEquals(100, fe.melFrameCount(16000))
    }

    @Test
    fun `frontend compute produces 160-dim finite features`() {
        val fe = GraniteNarFrontend(FloatArray(80 * 257) { 1f }, FloatArray(512) { 1f })
        val out = fe.compute(FloatArray(16000) { i ->
            sin(2.0 * Math.PI * 440.0 * i / 16000.0).toFloat()
        })
        assertEquals(50, out.frames)
        assertEquals(160, out.dim)
        assertEquals(50 * 160, out.data.size)
        assertTrue(out.data.all { it.isFinite() })
    }

    @Test
    fun `frontend silence is finite`() {
        val fe = GraniteNarFrontend(FloatArray(80 * 257), FloatArray(512) { 1f })
        val out = fe.compute(FloatArray(16000) { 0f })
        assertTrue(out.data.all { it.isFinite() })
    }
}
