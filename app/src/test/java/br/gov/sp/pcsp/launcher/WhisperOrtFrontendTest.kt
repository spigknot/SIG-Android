package br.gov.sp.pcsp.launcher

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/**
 * Prova o frontend ORT contra referências INDEPENDENTES (ORT1-C03/C04/C05/C06):
 * a matriz mel oficial do `openai/whisper` e o log-mel calculado em numpy
 * (`gen_frontend_golden.py`). Nenhum valor esperado é copiado da implementação.
 */
class WhisperOrtFrontendTest {

    private fun resource(name: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("ort/$name")!!.use { it.readBytes() }

    private fun floats(name: String): FloatArray {
        val bytes = resource(name)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / 4) { buffer.getFloat() }
    }

    private val samples = WhisperOrtFrontend.N_SAMPLES
    private val rate = WhisperOrtFrontend.SAMPLE_RATE

    @Test
    fun `filtros mel batem com a matriz oficial mel_128 do openai whisper`() {
        val official = floats("mel_128_f32le.bin")
        assertEquals(WhisperOrtMelFilters.N_MELS * WhisperOrtMelFilters.N_FREQ_BINS, official.size)
        val ours = WhisperOrtMelFilters.slaney
        var maxAbs = 0.0
        var worst = ""
        for (m in 0 until WhisperOrtMelFilters.N_MELS) {
            for (k in 0 until WhisperOrtMelFilters.N_FREQ_BINS) {
                val diff = abs(official[m * WhisperOrtMelFilters.N_FREQ_BINS + k] - ours[m][k]).toDouble()
                if (diff > maxAbs) {
                    maxAbs = diff
                    worst = "mel=$m bin=$k ref=${official[m * 201 + k]} nosso=${ours[m][k]}"
                }
            }
        }
        assertTrue("maxAbs=$maxAbs ($worst)", maxAbs < 1e-6)
    }

    @Test
    fun `log-mel reproduz o golden numpy em todas as amostras de frame`() {
        val meta = JSONObject(String(resource("frontend-sampled.json"), Charsets.UTF_8))
        val order = meta.getJSONArray("order")
        val frames = meta.getJSONArray("sample_frames")
        val frameCount = frames.length()
        val flat = floats("frontend-sampled.f32")
        assertEquals(order.length() * frameCount * 128, flat.size)
        val frontend = WhisperOrtFrontend()
        var worstCase = ""
        var worstAbs = 0.0
        for (caseIndex in 0 until order.length()) {
            val name = order.getString(caseIndex)
            val pcm = pcmFor(name)
            assertEquals("amostras de $name", samples, pcm.size)
            val mels = frontend.logMel(pcm)
            assertEquals(128 * WhisperOrtFrontend.N_FRAMES, mels.size)
            for (f in 0 until frameCount) {
                val frame = frames.getInt(f)
                for (m in 0 until 128) {
                    val index = ((caseIndex * frameCount + f) * 128) + m
                    val expected = flat[index]
                    val actual = mels[m * WhisperOrtFrontend.N_FRAMES + frame]
                    val diff = abs(expected - actual).toDouble()
                    if (diff > worstAbs) {
                        worstAbs = diff
                        worstCase = "$name frame=$frame mel=$m ref=$expected nosso=$actual"
                    }
                }
            }
            val stats = meta.getJSONObject("stats").getJSONObject(name)
            assertEquals("$name maxAbs", stats.getDouble("max_abs"), mels.maxOf { abs(it) }.toDouble(), 1e-4)
            assertEquals(
                "$name rms",
                stats.getDouble("rms"),
                Math.sqrt(mels.sumOf { it.toDouble() * it } / mels.size),
                1e-4,
            )
            assertTrue("$name finito", mels.all { it.isFinite() })
        }
        assertTrue("maxAbs=$worstAbs ($worstCase)", worstAbs < 1e-4)
    }

    @Test
    fun `cauda curta e janela exata de 30 s tem o mesmo inicio`() {
        val frontend = WhisperOrtFrontend()
        val oneSecond = FloatArray(samples) { index ->
            if (index < rate) (0.3 * sin(2 * PI * 220.0 * index / rate)).toFloat() else 0f
        }
        val full = FloatArray(samples) { index -> (0.3 * sin(2 * PI * 220.0 * index / rate)).toFloat() }
        val shortMel = frontend.logMel(oneSecond)
        val fullMel = frontend.logMel(full)
        // Primeiro segundo idêntico nos dois: o padding não desloca a janela.
        for (frame in 0 until 90) {
            for (m in 0 until 128) {
                assertEquals(
                    "frame $frame mel $m",
                    fullMel[m * WhisperOrtFrontend.N_FRAMES + frame],
                    shortMel[m * WhisperOrtFrontend.N_FRAMES + frame],
                    1e-6f,
                )
            }
        }
    }

    @Test
    fun `planejamento de janelas preserva a cauda e nao inventa janela vazia`() {
        val frontend = WhisperOrtFrontend()
        assertTrue(frontend.planWindows(0).isEmpty())
        val single = frontend.planWindows(samples)
        assertEquals(1, single.size)
        assertEquals(samples, single[0].usableSamples)
        assertTrue(!single[0].partial)

        val plusOne = frontend.planWindows(samples + 1)
        assertEquals(2, plusOne.size)
        assertEquals(samples, plusOne[0].usableSamples)
        assertEquals(1, plusOne[1].usableSamples)
        assertEquals(samples, plusOne[1].offsetSamples)
        assertTrue("segunda janela é parcial", plusOne[1].partial)
        assertEquals(samples + 1, plusOne.sumOf { it.usableSamples })

        val long = frontend.planWindows(64 * rate)
        assertEquals(3, long.size)
        assertEquals(64 * rate, long.sumOf { it.usableSamples })
        assertEquals(64_000, long[2].usableSamples)
        assertTrue(long[2].partial)
    }

    @Test
    fun `passo menor que a janela cria sobreposicao sem perder a cauda`() {
        val frontend = WhisperOrtFrontend()
        val total = 64 * 16_000 + 6_400
        val windows = frontend.planWindows(total, 26 * 16_000)
        assertEquals(3, windows.size)
        assertEquals(0, windows[0].offsetSamples)
        assertEquals(416_000, windows[1].offsetSamples)
        assertEquals(832_000, windows[2].offsetSamples)
        assertEquals(198_400, windows[2].usableSamples)
        assertEquals(total, windows[2].offsetSamples + windows[2].usableSamples)
        assertTrue(windows[0].usableSamples == 480_000)
        // Hop invalido e recusado em vez de gerar laco.
        assertThrows(IllegalArgumentException::class.java) { frontend.planWindows(total, 0) }
        assertThrows(IllegalArgumentException::class.java) { frontend.planWindows(total, 480_001) }
    }

    @Test
    fun `slice entrega exatamente as amostras uteis da janela`() {
        val frontend = WhisperOrtFrontend()
        val pcm = FloatArray(samples + 5) { it.toFloat() }
        val windows = frontend.planWindows(pcm.size)
        val slice = frontend.slice(pcm, windows[1])
        assertEquals(samples.toFloat(), slice[0])
        assertEquals(samples + 4f, slice[4])
        assertEquals(0.0f, slice[5])
    }

    @Test
    fun `pcm int16 e float sao coerentes e o silencio e detectado`() {
        val source = floatArrayOf(0f, 0.5f, -0.5f, 1f, -1f)
        val bytes = WhisperOrtPcm.toInt16Le(source)
        val back = WhisperOrtPcm.fromInt16Le(bytes)
        for (i in source.indices) assertEquals(source[i], back[i], 1e-4f)
        assertEquals(32767, ((bytes[6].toInt() and 0xFF) or (bytes[7].toInt() shl 8)))
        assertEquals(0.0f, WhisperOrtPcm.fromInt16Le(byteArrayOf(0, 0))[0], 0f)
        assertTrue(WhisperOrtPcm.isDigitalSilence(FloatArray(1_000)))
        assertTrue(!WhisperOrtPcm.isDigitalSilence(floatArrayOf(0f, 0.01f)))
        assertEquals(1.0, WhisperOrtPcm.durationSeconds(rate), 1e-9)
        assertEquals(1.0f, WhisperOrtPcm.peak(floatArrayOf(0.25f, -1f)), 0f)
        assertNotNull(WhisperOrtPcm.rejectionForSampleRate(44_100))
        assertNull(WhisperOrtPcm.rejectionForSampleRate(16_000))
    }

    @Test
    fun `pcm estereo e recusado em vez de virar media silenciosa`() {
        val failure = runCatching { WhisperOrtPcm.fromInt16Le(ByteArray(8), channels = 2) }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure is IllegalArgumentException)
    }

    /** Reproduz exatamente o sinal gerado em `gen_frontend_golden.py`. */
    private fun pcmFor(name: String): FloatArray = when (name) {
        "silencio" -> FloatArray(samples)
        "impulso" -> FloatArray(samples).also { it[1000] = 1f }
        "seno440" -> FloatArray(samples) { index ->
            if (index < rate) (0.5 * sin(2 * PI * 440.0 * index / rate)).toFloat() else 0f
        }
        "ruido" -> {
            var seed = 20_261_029L
            FloatArray(samples) { index ->
                if (index >= 2 * rate) {
                    0f
                } else {
                    seed = (seed * 1_103_515_245L + 12_345L) and 0x7FFFFFFFL
                    val unit = seed.toDouble() / 0x7FFFFFFF.toDouble()
                    (0.2 * (unit * 2.0 - 1.0) * exp(-(index.toDouble() / rate))).toFloat()
                }
            }
        }
        "fala_sintetica" -> FloatArray(samples) { index ->
            if (index < rate) {
                val t = index.toDouble() / rate
                val base = 0.4 * sin(2 * PI * 180 * t) + 0.2 * sin(2 * PI * 900 * t)
                (base * (0.5 + 0.5 * sin(2 * PI * 3.0 * t))).toFloat()
            } else {
                0f
            }
        }
        "trinta_s_exato" -> FloatArray(samples) { index ->
            (0.3 * sin(2 * PI * 220.0 * index / rate)).toFloat()
        }
        "curta_1s" -> FloatArray(samples) { index ->
            if (index < rate) (0.3 * sin(2 * PI * 220.0 * index / rate)).toFloat() else 0f
        }
        else -> error("caso sem sinal definido: $name")
    }
}
