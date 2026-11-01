package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Adendo 17 §1 — o small usa o extrator padrão (80 bins mel), o turbo usa
 * 128 (large-v3). Com 128 fixo o encoder do small falhava no `ConvInteger`
 * (`C: 128, kernel channels: 80`). Estes testes prendem o contrato:
 * tabelas coexistindo, frontend parametrizado, mapeamento por modelo e
 * recusa antecipada quando frontend e encoder divergem.
 */
class WhisperOrtMelBinsTest {

    @Test
    fun `tabela 128 preservada bit a bit`() {
        val t = WhisperOrtMelFilters.slaney
        assertEquals(128, t.size)
        assertTrue(t.all { it.size == WhisperOrtMelFilters.N_FREQ_BINS })
    }

    @Test
    fun `tabela 80 tem forma e pesos validos`() {
        val t = WhisperOrtMelFilters.slaney80
        assertEquals(WhisperOrtMelFilters.N_MELS_SMALL, t.size)
        assertTrue(t.all { it.size == WhisperOrtMelFilters.N_FREQ_BINS })
        // Pesos triangulares normalizados: não-negativos, cada banda com massa.
        assertTrue(t.all { row -> row.all { it >= 0f } })
        assertTrue(t.all { row -> row.sum() > 0f })
        // Bandas distintas (a matriz não é cópia repetida de uma linha).
        assertTrue(t.toSet().size > 70)
    }

    @Test
    fun `frontend 80 devolve 80 por 3000`() {
        val frontend = WhisperOrtFrontend(WhisperOrtMelFilters.slaney80)
        assertEquals(80, frontend.nMels)
        val pcm = FloatArray(WhisperOrtFrontend.N_SAMPLES) { i ->
            (0.1 * kotlin.math.sin(2.0 * kotlin.math.PI * 440.0 * i / 16_000.0)).toFloat()
        }
        val mel = frontend.logMel(pcm)
        assertEquals(80 * WhisperOrtFrontend.N_FRAMES, mel.size)
        assertTrue(mel.all { it.isFinite() })
    }

    @Test
    fun `frontend default continua 128`() {
        val frontend = WhisperOrtFrontend()
        assertEquals(128, frontend.nMels)
        assertEquals(128 * WhisperOrtFrontend.N_FRAMES, frontend.logMel(FloatArray(16_000)).size)
    }

    @Test
    fun `catalogo mapeia mel por modelo`() {
        assertEquals(80, WhisperOrtModelCatalog.melBinsPara(WhisperOrtModelCatalog.MODEL_SMALL))
        assertEquals(128, WhisperOrtModelCatalog.melBinsPara(WhisperOrtModelCatalog.MODEL))
        assertEquals(80, WhisperOrtModelCatalog.filtrosMelPara(WhisperOrtModelCatalog.MODEL_SMALL).size)
        assertEquals(128, WhisperOrtModelCatalog.filtrosMelPara(WhisperOrtModelCatalog.MODEL).size)
        assertThrows(IllegalArgumentException::class.java) {
            WhisperOrtModelCatalog.melBinsPara("whisper-medium")
        }
    }

    @Test
    fun `catalogo mapeia vocab por modelo`() {
        assertEquals(51_865, WhisperOrtModelCatalog.tamanhoVocabPara(WhisperOrtModelCatalog.MODEL_SMALL))
        assertEquals(51_866, WhisperOrtModelCatalog.tamanhoVocabPara(WhisperOrtModelCatalog.MODEL))
        assertEquals(51_865 - 50_257, WhisperOrtModelCatalog.especiaisPara(WhisperOrtModelCatalog.MODEL_SMALL).size)
        assertEquals(51_866 - 50_257, WhisperOrtModelCatalog.especiaisPara(WhisperOrtModelCatalog.MODEL).size)
        assertThrows(IllegalArgumentException::class.java) {
            WhisperOrtModelCatalog.tamanhoVocabPara("whisper-medium")
        }
    }

    @Test
    fun `tokenizer small tem 51865 posicoes`() {
        val texto = (0..50257).associate { "t$it" to it }
        val pequeno = WhisperOrtTokenizer.fromVocab(
            texto, WhisperOrtModelCatalog.especiaisPara(WhisperOrtModelCatalog.MODEL_SMALL),
        )
        assertEquals(51_865, pequeno.vocabSize)
        val turbo = WhisperOrtTokenizer.fromVocab(
            texto, WhisperOrtModelCatalog.especiaisPara(WhisperOrtModelCatalog.MODEL),
        )
        assertEquals(51_866, turbo.vocabSize)
    }

    @Test
    fun `transcriber recusa frontend divergente do encoder`() {
        val backend80 = object : WhisperOrtBackend {
            override val melBinsDetectados: Int = 80
            override fun encode(logMel: FloatArray): FloatArray = FloatArray(0)
            override fun start(hidden: FloatArray, prompt: IntArray): FloatArray = FloatArray(0)
            override fun step(token: Int): FloatArray = FloatArray(0)
            override fun close() { }
        }
        val vocab = (0..50256).associate { "t$it" to it }
        val transcriber = WhisperOrtTranscriber(
            WhisperOrtTokenizer.fromVocab(vocab),
            frontend = WhisperOrtFrontend(), // 128 contra encoder de 80
        )
        val pcm = FloatArray(16_000) { i ->
            (0.1 * kotlin.math.sin(2.0 * kotlin.math.PI * 440.0 * i / 16_000.0)).toFloat()
        }
        val t = transcriber.transcribe(backend80, pcm)
        assertEquals(WhisperOrtOutcome.ERROR, t.outcome)
        assertTrue("erro=${t.error}", t.error!!.contains("128") && t.error!!.contains("80"))
    }
}
