package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Prova o laço greedy e o prefixo de geração (ORT1-D02/D04/D06) sem sessão
 * ONNX: o backend é roteirizado, então cada caso isola uma regra de parada.
 */
class WhisperOrtGenerationTest {

    private fun tokenizer(): WhisperOrtTokenizer = WhisperOrtTokenizer.fromSubset(
        mapOf(
            "a" to 65,
            "b" to 66,
            "\u0120" to 220,
            "z" to 51_865,
        ),
    )

    private fun generator(
        maxNewTokens: Int = 444,
        suppressFrom: Int = WhisperOrtPrompt.START_OF_TRANSCRIPT,
    ) = WhisperOrtGenerator(
        tokenizer(),
        WhisperOrtGenerationPolicy(
            config = WhisperOrtGenerationConfig(
                suppressTokens = emptySet(),
                beginSuppressTokens = emptySet(),
                maxLength = 4 + maxNewTokens,
                eosTokenId = WhisperOrtPrompt.END_OF_TEXT,
                decoderStartTokenId = WhisperOrtPrompt.START_OF_TRANSCRIPT,
            ),
            suppressFrom = suppressFrom,
        ),
    )

    @Test
    fun `eos encerra e o texto nao contem token especial`() {
        val backend = WhisperOrtFakeBackend(listOf(listOf(65, 220, 66, WhisperOrtPrompt.END_OF_TEXT)))
        val result = generator().generate(backend, FloatArray(128 * 3000))
        assertEquals("a b", result.text)
        assertEquals(WhisperOrtStopReason.EOS, result.stopReason)
        assertTrue(result.complete)
        assertEquals(3, result.tokens.size)
        assertEquals(3, result.steps)
        assertNull(result.error)
    }

    @Test
    fun `teto de tokens vira PARCIAL e nao sucesso integral`() {
        val backend = WhisperOrtFakeBackend(listOf(listOf(65, 65, 65, 65, 65)))
        val result = generator(maxNewTokens = 3).generate(backend, FloatArray(128 * 3000))
        assertEquals(WhisperOrtStopReason.MAX_TOKENS, result.stopReason)
        assertFalse(result.complete)
        assertEquals(3, result.tokens.size)
        assertEquals("aaa", result.text)
    }

    @Test
    fun `cancelamento entre tokens preserva o que ja foi gerado`() {
        // O laço consulta o cancelamento DUAS vezes antes do 1o token (antes do
        // encoder e depois dele); o cancelamento "entre tokens" é o 3o check.
        var checks = 0
        val backend = WhisperOrtFakeBackend(listOf(listOf(65, 66, 65)))
        val result = generator().generate(backend, FloatArray(128 * 3000)) {
            checks++
            checks > 3
        }
        assertEquals(WhisperOrtStopReason.CANCELLED, result.stopReason)
        assertFalse(result.complete)
        assertEquals(1, result.tokens.size)
        assertEquals("a", result.text)
    }

    @Test
    fun `token de controle com logit maior e suprimido`() {
        val backend = WhisperOrtFakeBackend(
            listOf(listOf(65, WhisperOrtPrompt.END_OF_TEXT)),
            controlSpike = WhisperOrtPrompt.NO_TIMESTAMPS,
        )
        val result = generator().generate(backend, FloatArray(128 * 3000))
        assertEquals("a", result.text)
        assertEquals(listOf(65), result.tokens)
        assertEquals(WhisperOrtStopReason.EOS, result.stopReason)
    }

    @Test
    fun `logits invalidos produzem erro explicito e nenhum texto`() {
        val backend = object : WhisperOrtBackend {
            override fun encode(logMel: FloatArray) = FloatArray(1_500 * 1_280)
            override fun start(hidden: FloatArray, prompt: IntArray) = FloatArray(51_866) { Float.NaN }
            override fun step(token: Int) = FloatArray(51_866) { Float.NaN }
            override fun close() = Unit
        }
        val result = generator().generate(backend, FloatArray(128 * 3000))
        assertEquals(WhisperOrtStopReason.ERROR, result.stopReason)
        assertEquals("", result.text)
        assertNotNull(result.error)
    }

    @Test
    fun `falha de runtime do backend e terminal, com motivo e sem texto`() {
        // Falha de SESSÃO é classificada como recuperável; quem decide repetir é
        // o dono da transcrição, não o laço.
        val backend = WhisperOrtFakeBackend(listOf(listOf(65, 50257)), failEncodeTimes = Int.MAX_VALUE)
        val result = generator().generate(backend, FloatArray(128 * 3000))
        assertEquals(WhisperOrtStopReason.ERROR, result.stopReason)
        assertTrue(result.fault is WhisperOrtFault.Runtime)
        assertTrue(result.error!!.contains("falha simulada"))
        assertEquals("", result.text)
    }

    @Test
    fun `prefixo padrao e portugues com tarefa e sem timestamps`() {
        val prompt = WhisperOrtPrompt.portuguese()
        assertEquals(4, prompt.size)
        assertEquals(WhisperOrtPrompt.START_OF_TRANSCRIPT, prompt[0])
        assertEquals(50_267, prompt[1])
        assertEquals(WhisperOrtPrompt.TRANSCRIBE, prompt[2])
        assertEquals(WhisperOrtPrompt.NO_TIMESTAMPS, prompt[3])
        assertNull(WhisperOrtPrompt.validate(prompt, 51_866))
    }

    @Test
    fun `prefixo sem idioma e recusado antes de gastar decoder`() {
        val missingLanguage = intArrayOf(WhisperOrtPrompt.START_OF_TRANSCRIPT, 50360, 50364, 50364)
        assertNotNull(WhisperOrtPrompt.validate(missingLanguage, 51_866))
        val onlySot = intArrayOf(WhisperOrtPrompt.START_OF_TRANSCRIPT)
        assertNotNull(WhisperOrtPrompt.validate(onlySot, 51_866))
        val outOfRange = intArrayOf(WhisperOrtPrompt.START_OF_TRANSCRIPT, 50_267, 50_360, 99_999)
        assertNotNull(WhisperOrtPrompt.validate(outOfRange, 51_866))
        assertThrows(IllegalArgumentException::class.java) {
            WhisperOrtGenerator(tokenizer(), WhisperOrtGenerationPolicy(prompt = onlySot))
        }
    }

    @Test
    fun `config recusa teto que nao cabe o prefixo`() {
        // max_length é TOTAL: com prefixo de 4, um total de 4 nao deixa gerar nada.
        assertThrows(IllegalArgumentException::class.java) {
            WhisperOrtGenerator(
                tokenizer(),
                WhisperOrtGenerationPolicy(
                    config = WhisperOrtGenerationConfig(
                        suppressTokens = emptySet(),
                        beginSuppressTokens = emptySet(),
                        maxLength = 4,
                        eosTokenId = WhisperOrtPrompt.END_OF_TEXT,
                        decoderStartTokenId = WhisperOrtPrompt.START_OF_TRANSCRIPT,
                    ),
                ),
            )
        }
    }
}
