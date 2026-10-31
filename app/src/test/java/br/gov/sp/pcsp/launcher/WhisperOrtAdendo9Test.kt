package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Adendo 9 §4 — regressões da política temporal.
 *
 * Cada caso isola um defeito que o especialista reproduziu:
 * 1. o passo 0 exigia timestamp mas ignorava `maxInitialTimestampIndex`;
 * 2. o seek usava a abertura seguinte em vez da última fronteira FECHADA;
 * 3. o laço pulava 26 s em silêncio quando o seek não avançava.
 *
 * Sem sessão ONNX: o backend é roteirizado e a junção é testada por tokens.
 */
class WhisperOrtAdendo9Test {

    private val noTs = WhisperOrtPrompt.NO_TIMESTAMPS
    private val base = WhisperOrtSegments.timestampBegin(noTs)

    private fun tokenizer(): WhisperOrtTokenizer = WhisperOrtTokenizer.fromSubset(
        mapOf("a" to 65, "b" to 66, "\u0120" to 220),
    )

    private fun geradorTemporal(maxInitial: Int) = WhisperOrtGenerator(
        tokenizer(),
        WhisperOrtGenerationPolicy(
            prompt = intArrayOf(
                WhisperOrtPrompt.START_OF_TRANSCRIPT,
                WhisperOrtPrompt.LANGUAGE_PT,
                WhisperOrtPrompt.TRANSCRIBE,
            ),
            config = WhisperOrtGenerationConfig(
                suppressTokens = emptySet(),
                beginSuppressTokens = emptySet(),
                maxLength = 4 + 444,
                eosTokenId = WhisperOrtPrompt.END_OF_TEXT,
                decoderStartTokenId = WhisperOrtPrompt.START_OF_TRANSCRIPT,
                maxInitialTimestampIndex = maxInitial,
            ),
            timestampMode = true,
        ),
    )

    @Test
    fun `passo 0 respeita o limite inicial de timestamp`() {
        val cedo = base + 10    // 0,2 s: dentro do limite de 1 s
        val tarde = base + 100  // 2,0 s: fora do limite
        // O backend oferece primeiro o token fora do limite; ele não pode vencer.
        val backend = WhisperOrtFakeBackend(listOf(listOf(tarde, cedo, WhisperOrtPrompt.END_OF_TEXT)))
        val resultado = geradorTemporal(maxInitial = 50).generate(backend, FloatArray(128 * 3000))
        val primeiro = resultado.tokens.firstOrNull()
        assertNotEquals("timestamp acima do limite inicial venceu", tarde, primeiro)
        assertTrue("primeiro token deveria ser timestamp", primeiro != null && primeiro >= base)
        assertTrue("timestamp inicial fora do limite", (primeiro ?: base) - base <= 50)
    }

    @Test
    fun `limite inicial maior aceita timestamp mais distante`() {
        val tarde = base + 100
        val backend = WhisperOrtFakeBackend(listOf(listOf(tarde, WhisperOrtPrompt.END_OF_TEXT)))
        val resultado = geradorTemporal(maxInitial = 500).generate(backend, FloatArray(128 * 3000))
        assertEquals(tarde, resultado.tokens.first())
    }

    @Test
    fun `seek usa a ultima fronteira fechada e nao a abertura seguinte`() {
        // ts0, a, ts1, ts2, b — a última fronteira FECHADA é ts1 (1 s);
        // a versão anterior devolvia ts2 (2 s) e engolia a sobreposição.
        val tokens = listOf(base, 65, base + 50, base + 100, 66)
        val retrieval = WhisperOrtSegments.retrieve(
            tokens = tokens,
            noTimestampsTokenId = noTs,
            vocabSize = 51_866,
            windowOffsetSeconds = 0.0,
            windowDurationSeconds = 30.0,
        ) { ids -> ids.joinToString(" ") { it.toString() } }
        assertEquals(1.0, retrieval.seekSeconds, 1e-6)
    }

    @Test
    fun `janela sem fronteira fechada nao avanca para o horizonte da janela`() {
        // Áudio ÚTIL de 1 s com timestamp de 20 s (padding até 30 s): o seek não
        // pode ir além do útil nem consumir áudio que não existe.
        val tokens = listOf(base, 65, base + 1000)
        val retrieval = WhisperOrtSegments.retrieve(
            tokens = tokens,
            noTimestampsTokenId = noTs,
            vocabSize = 51_866,
            windowOffsetSeconds = 0.0,
            windowDurationSeconds = 1.0,
        ) { ids -> ids.joinToString(" ") { it.toString() } }
        assertTrue(
            "seek ${retrieval.seekSeconds}s passou do audio util (1 s)",
            retrieval.seekSeconds <= 1.0,
        )
    }

    @Test
    fun `dois timestamps seguidos obrigam texto`() {
        // Referência: ts,ts -> próximo tem de ser TEXTO (senão os pares nunca
        // fecham e nenhum segmento é produzido).
        val t0 = base + 10
        val t1 = base + 20
        val backend = WhisperOrtFakeBackend(listOf(listOf(t0, t1, base + 30, WhisperOrtPrompt.END_OF_TEXT)))
        val r = geradorTemporal(maxInitial = 500).generate(backend, FloatArray(128 * 3000))
        val triplo = r.tokens.windowed(3).any { w -> w.all { it >= base } }
        assertTrue("três timestamps seguidos: ${r.tokens.filter { it >= base }}", !triplo)
    }

    @Test
    fun `timestamp nao retrocede`() {
        val tarde = base + 100
        val cedo = base + 10
        val backend = WhisperOrtFakeBackend(
            listOf(listOf(tarde, 65, cedo, WhisperOrtPrompt.END_OF_TEXT)),
        )
        val r = geradorTemporal(maxInitial = 500).generate(backend, FloatArray(128 * 3000))
        val emitidos = r.tokens.filter { it >= base }
        for (i in 1 until emitidos.size) {
            assertTrue(
                "timestamp retrocedeu: ${emitidos[i - 1]} -> ${emitidos[i]}",
                emitidos[i] >= emitidos[i - 1],
            )
        }
    }
}
