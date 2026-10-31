package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regressão dos defeitos que a revisão independente reproduziu nos seams
 * (adendo1 §1). Cada teste corresponde a uma linha da tabela da revisão.
 */
class WhisperOrtGenerationContractTest {

    private fun tokenizer(): WhisperOrtTokenizer = WhisperOrtTokenizer.fromSubset(
        mapOf("a" to 65, "b" to 66, "\u0120" to 220, "z" to 51_865),
    )

    private fun config(
        maxLength: Int = 448,
        suppress: Set<Int> = emptySet(),
        beginSuppress: Set<Int> = emptySet(),
    ) = WhisperOrtGenerationConfig(
        suppressTokens = suppress,
        beginSuppressTokens = beginSuppress,
        maxLength = maxLength,
        eosTokenId = WhisperOrtPrompt.END_OF_TEXT,
        decoderStartTokenId = WhisperOrtPrompt.START_OF_TRANSCRIPT,
    )

    private fun generator(
        maxLength: Int = 448,
        suppress: Set<Int> = emptySet(),
        beginSuppress: Set<Int> = emptySet(),
    ) = WhisperOrtGenerator(
        tokenizer(),
        WhisperOrtGenerationPolicy(
            config = config(maxLength, suppress, beginSuppress),
        ),
    )

    /** Backend que devolve exatamente os logits informados, em ordem. */
    private class RawBackend(
        private val steps: List<FloatArray>,
        private val failAt: Int = -1,
        private val failKind: String = "runtime",
    ) : WhisperOrtBackend {
        var encodeCalls = 0
            private set
        var stepCalls = 0
            private set
        private var index = 0

        override fun encode(logMel: FloatArray): FloatArray {
            encodeCalls++
            if (failAt == 0) throwFault()
            return FloatArray(1_500 * 1_280)
        }

        override fun start(hidden: FloatArray, prompt: IntArray): FloatArray = next()

        override fun step(token: Int): FloatArray {
            stepCalls++
            return next()
        }

        private fun next(): FloatArray {
            if (index == failAt) throwFault()
            return steps.getOrElse(index++) { steps.last() }
        }

        private fun throwFault() {
            when (failKind) {
                "runtime" -> throw WhisperOrtRuntimeFault("ORT_FAIL", "falha simulada de sessao")
                else -> throw IllegalStateException("falha simulada de contrato")
            }
        }

        override fun close() = Unit

        override fun reopen(): Boolean = true
    }

    private fun logits(vararg pairs: Pair<Int, Float>, size: Int = 51_866): FloatArray {
        val out = FloatArray(size) { -1f }
        for ((id, value) in pairs) out[id] = value
        return out
    }

    // 1. Não finitos -----------------------------------------------------------
    @Test
    fun `NaN nos logits e erro definitivo e nao vira texto`() {
        val backend = RawBackend(listOf(logits(65 to 5f, 66 to 4f).also { it[200] = Float.NaN }))
        val result = generator().generate(backend, FloatArray(128 * 3000))
        assertEquals(WhisperOrtStopReason.ERROR, result.stopReason)
        assertTrue(result.fault is WhisperOrtFault.Invalid)
        assertTrue(result.error!!.contains("não finito"))
        assertEquals("", result.text)
    }

    @Test
    fun `EOS com mais infinito nao vira completo`() {
        val backend = RawBackend(
            listOf(logits(WhisperOrtPrompt.END_OF_TEXT to Float.POSITIVE_INFINITY)),
        )
        val result = generator().generate(backend, FloatArray(128 * 3000))
        assertEquals(WhisperOrtStopReason.ERROR, result.stopReason)
        assertFalse(result.complete)
        assertEquals("", result.text)
    }

    @Test
    fun `menos infinito e mascara legitima e nao e recusado`() {
        // Tudo -Inf: não há candidato, mas o motivo é "sem candidato", não
        // "logit inválido" — a máscara aplicada pela política é válida.
        val allMask = FloatArray(51_866) { Float.NEGATIVE_INFINITY }
        val backend = RawBackend(listOf(allMask))
        val result = generator().generate(backend, FloatArray(128 * 3000))
        assertEquals(WhisperOrtStopReason.ERROR, result.stopReason)
        assertTrue(result.error!!.contains("sem candidato"))
    }

    // 2. Domínio de logits -----------------------------------------------------
    @Test
    fun `vetor de logits menor que o vocabulario e recusado`() {
        val backend = RawBackend(listOf(floatArrayOf(-1f, 5f)))
        val result = generator().generate(backend, FloatArray(128 * 3000))
        assertEquals(WhisperOrtStopReason.ERROR, result.stopReason)
        assertTrue(result.error!!.contains("posições"))
        assertEquals("", result.text)
    }

    // 3. Orçamento TOTAL de posições -------------------------------------------
    @Test
    fun `max_length e total e nao executa passo extra no teto`() {
        // 448 total - 4 de prefixo = 444 novos.
        val backend = RawBackend(List(600) { logits(65 to 5f) })
        val result = generator(maxLength = 448).generate(backend, FloatArray(128 * 3000))
        assertEquals(WhisperOrtStopReason.MAX_TOKENS, result.stopReason)
        assertEquals(444, result.tokens.size)
        assertEquals("tokens", 444, result.steps)
        // Nenhum passo além do teto: 443 steps para 444 tokens.
        assertEquals(443, backend.stepCalls)
    }

    @Test
    fun `config menor reduz o orcamento pelo prefixo`() {
        val backend = RawBackend(List(50) { logits(65 to 5f) })
        val result = generator(maxLength = 10).generate(backend, FloatArray(128 * 3000))
        assertEquals(WhisperOrtStopReason.MAX_TOKENS, result.stopReason)
        assertEquals(6, result.tokens.size)
    }

    // 4. Supressões do export --------------------------------------------------
    @Test
    fun `suppress_tokens do export nunca e escolhido`() {
        // id 1 com logit altíssimo, mas na lista de supressão do export.
        val backend = RawBackend(
            listOf(
                logits(1 to 9f, 65 to 5f),
                logits(WhisperOrtPrompt.END_OF_TEXT to 5f),
            ),
        )
        val result = generator(suppress = setOf(1, 2, 7)).generate(backend, FloatArray(128 * 3000))
        assertEquals(listOf(65), result.tokens)
        assertEquals(WhisperOrtStopReason.EOS, result.stopReason)
    }

    @Test
    fun `begin_suppress_tokens vale so na primeira posicao`() {
        val backend = RawBackend(
            listOf(
                logits(220 to 9f, 65 to 5f),
                logits(220 to 9f, 66 to 5f),
                logits(WhisperOrtPrompt.END_OF_TEXT to 9f),
            ),
        )
        val result = generator(beginSuppress = setOf(220)).generate(backend, FloatArray(128 * 3000))
        // Na posição 0 o 220 é barrado; na 1 ele pode ser escolhido.
        assertEquals(listOf(65, 220), result.tokens)
    }

    // 5. Cancelamento antes das etapas caras -----------------------------------
    @Test
    fun `cancel conhecido nao executa encoder`() {
        val backend = RawBackend(listOf(logits(65 to 5f)))
        val result = generator().generate(backend, FloatArray(128 * 3000)) { true }
        assertEquals(WhisperOrtStopReason.CANCELLED, result.stopReason)
        assertEquals(0, backend.encodeCalls)
        assertEquals("", result.text)
    }

    // 6. Classificação de falha ------------------------------------------------
    @Test
    fun `falha de runtime e RECOVERABLE e falha de contrato e ERROR`() {
        val runtime = RawBackend(List(5) { logits(65 to 5f) }, failAt = 1, failKind = "runtime")
        val r1 = generator().generate(runtime, FloatArray(128 * 3000))
        // Sem classe recuperável demonstrada, falha de runtime é TERMINAL.
        assertEquals(WhisperOrtStopReason.ERROR, r1.stopReason)
        assertTrue(r1.fault is WhisperOrtFault.Runtime)

        val contrato = RawBackend(List(5) { logits(65 to 5f) }, failAt = 1, failKind = "contrato")
        val r2 = generator().generate(contrato, FloatArray(128 * 3000))
        assertEquals(WhisperOrtStopReason.ERROR, r2.stopReason)
        assertTrue(r2.fault is WhisperOrtFault.Invalid)
    }

    // 7. Tokenizer vazio -------------------------------------------------------
    @Test
    fun `vocabulario vazio e recusado em vez de virar texto vazio`() {
        assertThrows(IllegalArgumentException::class.java) {
            WhisperOrtTokenizer.fromSubset(emptyMap())
        }
    }

    // 8. Config real do export -------------------------------------------------
    @Test
    fun `parse le supressoes e teto total do generation_config`() {
        val json = """
            {"suppress_tokens":[1,2,7],"begin_suppress_tokens":[220,50257],
             "max_length":448,"eos_token_id":50257,"decoder_start_token_id":50258}
        """.trimIndent()
        val parsed = WhisperOrtGenerationConfig.parse(json)
        assertEquals(setOf(1, 2, 7), parsed.suppressTokens)
        assertEquals(setOf(220, 50257), parsed.beginSuppressTokens)
        assertEquals(448, parsed.maxLength)
        assertEquals(444, parsed.newTokenBudget(4))
        assertTrue(parsed.isSuppressed(1, 0))
        assertTrue(parsed.isSuppressed(220, 0))
        assertFalse(parsed.isSuppressed(220, 1))
    }

    @Test
    fun `prefixo valido continua obrigatorio`() {
        assertNull(WhisperOrtPrompt.validate(WhisperOrtPrompt.portuguese(), 51_866))
        assertThrows(IllegalArgumentException::class.java) {
            WhisperOrtGenerator(
                tokenizer(),
                WhisperOrtGenerationPolicy(prompt = intArrayOf(WhisperOrtPrompt.START_OF_TRANSCRIPT)),
            )
        }
    }

    @Test
    fun `fault preserva motivo textual`() {
        val backend = RawBackend(List(3) { logits(65 to 5f) }, failAt = 0, failKind = "runtime")
        val result = generator().generate(backend, FloatArray(128 * 3000))
        assertNotNull(result.error)
        assertTrue(result.error!!.contains("encoder") || result.error!!.contains("sessao"))
    }
}
