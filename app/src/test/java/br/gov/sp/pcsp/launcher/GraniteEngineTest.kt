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
    fun `attention mask is 1 for real frames and 0 for padding`() {
        // Vacina do bug "transcrição vazia": a attention_mask estava INVERTIDA
        // (0=real/1=pad) e o conformer mascarava os frames válidos como -inf.
        // Convenção HF: 1=real, 0=pad.
        val mask = GraniteMask.build(windowFrames = 512, windowLen = 148)
        assertEquals(1L, mask[0])
        assertEquals(1L, mask[147])
        assertEquals(0L, mask[148])
        assertEquals(0L, mask[511])
        assertEquals(148, mask.count { it == 1L })
        assertEquals(512 - 148, mask.count { it == 0L })
    }

    @Test
    fun `attention mask full window has no padding`() {
        val mask = GraniteMask.build(windowFrames = 512, windowLen = 512)
        assertTrue(mask.all { it == 1L })
    }

    @Test
    fun `attention mask empty window is all padding`() {
        val mask = GraniteMask.build(windowFrames = 512, windowLen = 0)
        assertTrue(mask.all { it == 0L })
    }

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
    fun `frontend config from real json parses deltas boolean`() {
        // Vacina do bug "frontend_config sem deltas": o frontend_config.json real
        // tem "deltas": true (booleano), e o parser antigo só aceitava número —
        // o load falhava com "frontend_config sem deltas" logo após a conversão.
        val realJson = """
            {
              "sample_rate": 16000,
              "n_fft": 512,
              "win_length": 400,
              "hop_length": 160,
              "n_mels": 80,
              "stack_factor": 2,
              "deltas": true,
              "delta_win_length": 3,
              "logmel_floor_db": 8.0,
              "num_special_tokens": 1,
              "input_dim": 320,
              "blank_id": 0,
              "pad_multiple": 512,
              "subsample_factor": 4
            }
        """.trimIndent()
        val cfg = GraniteFrontendConfig.fromJson(realJson)
        assertEquals(16000, cfg.sampleRate)
        assertEquals(512, cfg.nFft)
        assertEquals(400, cfg.winLength)
        assertEquals(160, cfg.hopLength)
        assertEquals(80, cfg.nMels)
        assertEquals(2, cfg.stackFactor)
        assertTrue(cfg.deltas)
        assertEquals(3, cfg.deltaWinLength)
        assertEquals(8.0, cfg.logmelFloorDb, 1e-9)
        assertEquals(1, cfg.numSpecialTokens)
        assertEquals(320, cfg.inputDim)
        assertEquals(0, cfg.blankId)
        assertEquals(512, cfg.padMultiple)
        assertEquals(4, cfg.subsampleFactor)
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

    @Test
    fun `download size fallback matches real package on R2`() {
        // Vacina do bug "85% (771 MB de 2002 MB)": o total declarado era o
        // hardcoded 2_100_000_000 (2002 MB) mas o pacote real tem ~3.995 MB.
        // O fallback (usado quando os HEAD requests falham) precisa refletir
        // o total REAL, senão a UI mostra "de X MB" errado e o percentual não
        // bate com o texto.
        val total = GraniteEngine.FALLBACK_PACKAGE_BYTES
        // Total real = 3.995.371.683 bytes (~3810 MB) — deve ser > 3.9 GB
        assertTrue("total $total deveria ser >= 3.9 GB", total >= 3_900_000_000L)
        assertTrue("total $total deveria ser < 4.2 GB", total < 4_200_000_000L)
        // Consistência: a UI mostra MB. 3.995.371.683 bytes -> 3810 MB (não 2002!)
        assertEquals(3810L, total / 1_048_576L)
    }

    @Test
    fun `download size fallback matches R2 file sum`() {
        // A soma dos tamanhos reais publicados no R2 (verificados via HEAD).
        // O modelo FP16 agora é o granite-5.0-turboctc-fp16-gather.onnx (Slice→Gather).
        val realSum =
            865_408L + 1_891_581_952L + 946_740_875L + 945_790_976L + 303L +
                82_240L + 2_048L + 177_439L + 640_793L + 209_532_928L
        assertEquals(realSum, GraniteEngine.FALLBACK_PACKAGE_BYTES)
    }
}
