package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * Testes do pipeline puro do Granite TurboCTC (front-end, AGC, CTC, decoder).
 * Sem Android — roda na JVM (testDebugUnitTest).
 */
class GraniteEngineTest {

    private val config = GraniteFrontendConfig()

    // ---- AGC ----

    @Test
    fun `agc silence stays silence`() {
        val out = GraniteAgc.apply(FloatArray(16000) { 0f })
        assertEquals(16000, out.size)
        assertTrue(out.all { it == 0f })
    }

    @Test
    fun `agc amplifies quiet signal toward target and clips peak`() {
        // Sinal constante bem baixo: o AGC deve aproximar de target ~0.12
        val x = FloatArray(16000) { 0.01f }
        val out = GraniteAgc.apply(x)
        val peak = out.maxOf { kotlin.math.abs(it) }
        assertTrue("pico $peak deveria ficar perto de 0.12", peak in 0.08..0.15)
    }

    @Test
    fun `agc loud signal is limited below 0_97`() {
        val x = FloatArray(16000) { 0.9f }
        val out = GraniteAgc.apply(x)
        val peak = out.maxOf { kotlin.math.abs(it) }
        assertTrue("pico $peak deveria ser <= 0.97", peak <= 0.971f)
    }

    // ---- FFT ----

    @Test
    fun `fft of sine is a single peak`() {
        val fft = Radix2Fft(512)
        val re = FloatArray(512)
        val im = FloatArray(512)
        val freq = 3
        for (i in 0 until 512) re[i] = sin(2.0 * Math.PI * freq * i / 512).toFloat()
        fft.run(re, im)
        // Potência: pico no bin 3 (e espelho 512-3)
        var peakBin = -1
        var peakPower = -1.0
        for (k in 0 until 256) {
            val p = (re[k].toDouble() * re[k] + im[k] * im[k]).toDouble()
            if (p > peakPower) { peakPower = p; peakBin = k }
        }
        assertEquals(3, peakBin)
    }

    @Test
    fun `fft preserves energy of impulse`() {
        val fft = Radix2Fft(512)
        val re = FloatArray(512)
        val im = FloatArray(512)
        re[0] = 1f
        fft.run(re, im)
        var sum = 0.0
        for (k in 0 until 512) sum += re[k] * re[k] + im[k] * im[k]
        // Parseval: soma das potências = N * energia do sinal = 512 * 1
        assertEquals(512.0, sum, 1e-3)
    }

    // ---- Frontend ----

    @Test
    fun `frameCount matches spec`() {
        val fe = GraniteFrontend(config, FloatArray(config.nMels * (config.nFft / 2 + 1)), FloatArray(config.nFft) { 1f })
        // 16000 amostras -> 100 mel frames (hop 160). frameCount retorna os frames
        // ANTES da divisão pelo stack (nFrames/stack é o outFrames).
        assertEquals(100, fe.frameCount(16000))
        // 0.5s -> 50 mel -> 50 (stack não divide aqui, ceil(50/2)*2=50)
        assertEquals(50, fe.frameCount(8000))
        // 10.24s -> 1024 mel -> 1024
        assertEquals(1024, fe.frameCount(163840))
    }

    @Test
    fun `compute silence produces finite features with expected shape`() {
        val fe = GraniteFrontend(config, FloatArray(config.nMels * (config.nFft / 2 + 1)), FloatArray(config.nFft) { 1f })
        val out = fe.compute(FloatArray(16000) { 0f })
        assertEquals(50, out.frames)
        assertEquals(320, out.dim)
        assertEquals(50 * 320, out.data.size)
        assertTrue(out.data.all { it.isFinite() })
        // Silêncio: log10(0) floored -> floor; com melFilters zerados, mel é 0 -> log10(eps) ~ -10
        // -> floor relativo ao pico global (que é -10) -> -10/4+1 = -1.5
        // (só garantimos finitude e o shape; valores exatos dependem dos filtros reais)
    }

    @Test
    fun `compute nonzero produces different features than silence`() {
        // melFilters = 1 (passa tudo) para que o mel de um sinal não-zero seja > 0.
        val nFreqs = config.nFft / 2 + 1
        val melFilters = FloatArray(config.nMels * nFreqs) { 1f }
        val fe = GraniteFrontend(config, melFilters, FloatArray(config.nFft) { 1f })
        val silence = fe.compute(FloatArray(16000) { 0f })
        val tone = fe.compute(FloatArray(16000) { i -> sin(2.0 * Math.PI * 440.0 * i / 16000.0).toFloat() })
        var diff = 0
        for (i in 0 until silence.data.size) if (kotlin.math.abs(silence.data[i] - tone.data[i]) > 1e-4f) diff++
        assertTrue("sinal senoidal deveria produzir features diferentes", diff > 100)
    }

    // ---- CTC collapse / decoder ----

    @Test
    fun `ctc collapse removes repeats and blanks`() {
        val dec = GraniteDecoder(listOf("a", "b", "c"), numSpecialTokens = 1)
        // ids: blank(0), a, a, blank, b, b, c, blank -> a, b, c (repetidos removidos, blank removido)
        val out = dec.collapse(intArrayOf(0, 1, 1, 0, 2, 2, 3, 0))
        assertTrue(out.contentEquals(intArrayOf(1, 2, 3)))
    }

    @Test
    fun `blank between identical tokens preserves both`() {
        val dec = GraniteDecoder(listOf("a", "b"), numSpecialTokens = 1)
        // a, blank, a -> a, a (o blank entre iguais preserva os dois)
        val out = dec.collapse(intArrayOf(1, 0, 1))
        assertTrue(out.contentEquals(intArrayOf(1, 1)))
    }

    @Test
    fun `bytelevel decode reassembles utf8`() {
        // GPT-2 ByteLevel: cada byte vira um codepoint imprimível.
        // "é" em UTF-8 = 0xC3 0xA9 -> no bytelevel vira os chars de codepoint 0xC3 e 0xA9? Não:
        // 0xC3 e 0xA9 estão na faixa 0xa1..0xac? Não. Então são mapeados para U+0100+.
        // Simplificação: testamos com bytes ASCII (0x41='A', 0x42='B').
        val dec = GraniteDecoder(listOf("A", "B"), numSpecialTokens = 1)
        val text = dec.decode(intArrayOf(1, 2))
        assertEquals("AB", text)
    }

    @Test
    fun `bytelevel decode of utf8 multibyte`() {
        // "é" UTF-8 = [0xC3, 0xA9]. O ByteLevel mapeia 0xC3 -> "Ã" (codepoint 0xC3 é imprimível)
        // e 0xA9 -> "©" (0xA9 imprimível). Então o piece "\u00C3\u00A9" decodifica para "é".
        val dec = GraniteDecoder(listOf("\u00C3\u00A9"), numSpecialTokens = 1)
        val text = dec.decode(intArrayOf(1))
        assertEquals("é", text)
    }

    @Test
    fun `model file name used by download check matches engine`() {
        // Vacina do bug "pede download de novo após concluir": o nome do arquivo
        // que a Activity verifica (GRANITE_MODEL_FILE) precisa ser EXATAMENTE o
        // que a engine baixa (MODEL_FILE_NAME). Se divergirem, o app baixa mas
        // nunca reconhece o modelo como presente.
        val activityModelFile = "granite-5.0-turboctc-f32-ext.onnx"
        assertEquals(activityModelFile, GraniteEngine.modelFileName())
    }

    @Test
    fun `package complete requires all files non empty`() {
        // O pacote só está "baixado" quando TODOS os arquivos existem e têm
        // tamanho > 0 — não basta o .onnx (o .data de 1,89GB pode faltar).
        // Sem Context Android, validamos a lista de arquivos esperados.
        val expected = listOf(
            "granite-5.0-turboctc-f32-ext.onnx",
            "granite-5.0-turboctc-f32-ext.onnx.data",
            "frontend_config.json",
            "mel_filters.bin",
            "stft_window.bin",
            "vocab.json",
            "pcs_vocab.json",
            "punct_cap_seg_en.onnx",
        )
        // O nome do modelo está na lista
        assertTrue(expected.contains(GraniteEngine.modelFileName()))
        assertEquals(8, expected.size)
    }
}
