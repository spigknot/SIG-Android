package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Prova o corte em janelas, a junção de texto e o terminal tipado
 * (ORT1-D07/F06) sem sessão ONNX: o backend é roteirizado por janela.
 */
class WhisperOrtTranscriberTest {

    private fun tokenizer(): WhisperOrtTokenizer = WhisperOrtTokenizer.fromSubset(
        mapOf("a" to 65, "b" to 66, "\u0120" to 220, "z" to 51_865),
    )

    private fun transcriber() = WhisperOrtTranscriber(tokenizer())

    @Test
    fun `silencio digital responde NO_SPEECH sem gastar encoder`() {
        val backend = WhisperOrtFakeBackend(emptyList())
        val result = transcriber().transcribe(backend, FloatArray(16_000))
        assertEquals(WhisperOrtOutcome.NO_SPEECH, result.outcome)
        assertEquals(0, backend.encodeCalls)
        assertEquals("", result.text)
    }

    @Test
    fun `pcm vazio responde NO_AUDIO`() {
        val backend = WhisperOrtFakeBackend(emptyList())
        val result = transcriber().transcribe(backend, FloatArray(0))
        assertEquals(WhisperOrtOutcome.NO_AUDIO, result.outcome)
        assertEquals(0, backend.encodeCalls)
    }

    @Test
    fun `uma janela com fala termina em SUCCESS e preserva tempos`() {
        val backend = WhisperOrtFakeBackend(listOf(listOf(65, 220, 66, WhisperOrtPrompt.END_OF_TEXT)))
        val pcm = FloatArray(16_000) { 0.1f }
        val result = transcriber().transcribe(backend, pcm)
        assertEquals(WhisperOrtOutcome.SUCCESS, result.outcome)
        assertEquals("a b", result.text)
        assertEquals(1, result.plannedWindows)
        assertEquals(1, backend.encodeCalls)
        assertTrue(result.audioMillis >= 1_000)
        assertTrue(result.totalMillis >= 0)
        assertFalse(backend.closed)
    }

    @Test
    fun `audio longo vira duas janelas e o texto e juntado na ordem`() {
        val backend = WhisperOrtFakeBackend(
            listOf(
                listOf(65, WhisperOrtPrompt.END_OF_TEXT),
                listOf(66, WhisperOrtPrompt.END_OF_TEXT),
            ),
        )
        val pcm = FloatArray(WhisperOrtFrontend.N_SAMPLES + 16_000) { 0.1f }
        val result = transcriber().transcribe(backend, pcm)
        assertEquals(2, result.plannedWindows)
        assertEquals(2, backend.encodeCalls)
        assertEquals("a b", result.text)
        // Com passo de 26 s a segunda janela comeca em 416000 e cobre ate o fim.
        assertEquals(80_000, result.windows[1].window.usableSamples)
        assertEquals(416_000, result.windows[1].window.offsetSamples)
        assertTrue(result.windows[1].window.partial)
        assertEquals(WhisperOrtOutcome.SUCCESS, result.outcome)
    }

    @Test
    fun `janela que bate o teto torna o resultado PARCIAL`() {
        val generatorPolicy = WhisperOrtGenerationPolicy(
            config = WhisperOrtGenerationConfig(
                suppressTokens = emptySet(),
                beginSuppressTokens = emptySet(),
                maxLength = 4 + 2,
                eosTokenId = WhisperOrtPrompt.END_OF_TEXT,
                decoderStartTokenId = WhisperOrtPrompt.START_OF_TRANSCRIPT,
            ),
        )
        val transcriber = WhisperOrtTranscriber(tokenizer(), generatorPolicy)
        val backend = WhisperOrtFakeBackend(listOf(listOf(65, 65, 65)))
        val result = transcriber.transcribe(backend, FloatArray(16_000) { 0.1f })
        assertEquals(WhisperOrtOutcome.PARTIAL, result.outcome)
        assertEquals(WhisperOrtStopReason.MAX_TOKENS, result.stop)
        assertEquals("aa", result.text)
    }

    @Test
    fun `falha do backend contamina o resultado com erro tipado`() {
        val backend = WhisperOrtFakeBackend(listOf(listOf(65)), failEncodeTimes = Int.MAX_VALUE)
        val result = transcriber().transcribe(backend, FloatArray(16_000) { 0.1f })
        assertEquals(WhisperOrtOutcome.ERROR, result.outcome)
        assertNotNull(result.error)
        assertEquals("", result.text)
    }

    @Test
    fun `falha do backend e terminal e nao reabre sessao`() {
        // Sem classe recuperavel demonstrada na arquitetura de 3 sessoes: erro de
        // runtime encerra a janela e nao ha retry nem reabertura.
        val backend = WhisperOrtFakeBackend(
            listOf(listOf(65, 220, 66, WhisperOrtPrompt.END_OF_TEXT)),
            failEncodeTimes = 1,
        )
        val result = transcriber().transcribe(backend, FloatArray(16_000) { 0.1f })
        assertEquals(0, backend.reopenCalls)
        assertEquals(1, backend.encodeCalls)
        assertEquals(WhisperOrtOutcome.ERROR, result.outcome)
        assertEquals("", result.text)
    }

    @Test
    fun `cancelamento antes do compute nao inventa texto`() {
        val backend = WhisperOrtFakeBackend(listOf(listOf(65, WhisperOrtPrompt.END_OF_TEXT)))
        val result = transcriber().transcribe(backend, FloatArray(16_000) { 0.1f }) { true }
        assertEquals(WhisperOrtOutcome.CANCELLED, result.outcome)
        assertEquals(0, backend.encodeCalls)
        assertEquals("", result.text)
    }
}
