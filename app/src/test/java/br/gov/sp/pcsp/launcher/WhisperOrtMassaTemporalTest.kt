package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Adendo 12 §3 — MASSA TEMPORAL depois de todas as máscaras.
 *
 * A referência não escolhe o maior logit isolado: compara a soma das
 * probabilidades de TODOS os timestamps elegíveis com o melhor token de TEXTO
 * elegível. Isto é o que fecha segmentos em fala corrida, onde nenhum timestamp
 * isolado vence um texto, mas o conjunto vence.
 *
 * Aqui os logits são CONTROLADOS (não roteirizados), porque o caso exige valores
 * precisos: texto alto contra vários timestamps médios.
 */
class WhisperOrtMassaTemporalTest {

    private val base = WhisperOrtSegments.timestampBegin(WhisperOrtPrompt.NO_TIMESTAMPS)

    /** Backend com logits explícitos por passo (default: muito baixo). */
    private class LogitBackend(
        private val passos: List<Map<Int, Float>>,
        private val vocabSize: Int = 51_866,
        private val logitPadrao: Float = -30f,
    ) : WhisperOrtBackend {
        private var i = 0
        override fun encode(logMel: FloatArray) = FloatArray(1_500 * 1_280)
        override fun start(hidden: FloatArray, prompt: IntArray) = logits()
        override fun step(token: Int): FloatArray {
            i++
            return logits()
        }

        override fun close() = Unit
        private fun logits(): FloatArray {
            val v = FloatArray(vocabSize) { logitPadrao }
            passos.getOrNull(i)?.forEach { (id, valor) -> v[id] = valor }
            return v
        }
    }

    private fun gerador() = WhisperOrtGenerator(
        WhisperOrtTokenizer.fromSubset(mapOf("a" to 65, "b" to 66, "\u0120" to 220)),
        WhisperOrtGenerationPolicy(
            prompt = WhisperOrtPrompt.portugueseWithTimestamps(),
            config = WhisperOrtGenerationConfig.frozenExport(),
            timestampMode = true,
        ),
    )

    @Test
    fun `soma dos timestamps vence o melhor texto individual`() {
        // A massa só compara tokens ELEGÍVEIS: no passo seguinte a um timestamp
        // isolado a regra de pares exige texto, então o teste precisa do passo em
        // que texto E timestamps estão liberados (depois de um texto).
        //   passo 0: timestamp (posição inicial)
        //   passo 1: texto 65 (a regra de pares exige texto aqui)
        //   passo 2: texto 65 logit 9,0 contra TRÊS timestamps de 8,5
        //            mass = 8,5 + ln(3) = 9,60 > 9,0  =>  TIMESTAMP vence
        val passo0 = mapOf(base to 5f)
        val passo1 = mapOf(65 to 9.0f)
        val passo2 = mapOf(
            65 to 9.0f,
            base + 50 to 8.5f,
            base + 60 to 8.5f,
            base + 70 to 8.5f,
        )
        val geracao = gerador().generate(
            LogitBackend(listOf(passo0, passo1, passo2, emptyMap())),
            FloatArray(128 * 3000),
        )
        assertTrue("passo 0 deveria ser timestamp", (geracao.tokens.getOrNull(0) ?: 0) >= base)
        assertEquals("passo 1 deveria ser o texto", 65, geracao.tokens.getOrNull(1))
        val terceiro = geracao.tokens.getOrNull(2)
        assertTrue(
            "massa temporal deveria vencer o texto 65 (veio $terceiro)",
            terceiro != null && terceiro >= base,
        )
    }

    @Test
    fun `texto com logit muito acima dos timestamps continua vencendo`() {
        // Controle: 12,0 contra três timestamps de 8,5 (mass 9,60) => TEXTO vence.
        val passo0 = mapOf(base to 5f)
        val passo1 = mapOf(
            65 to 12.0f,
            base + 50 to 8.5f,
            base + 60 to 8.5f,
            base + 70 to 8.5f,
        )
        val geracao = gerador().generate(LogitBackend(listOf(passo0, passo1, emptyMap())), FloatArray(128 * 3000))
        assertEquals("texto deve vencer quando o logit é muito maior", 65, geracao.tokens.getOrNull(1))
    }

    @Test
    fun `timestamp isolado antes de eos continua permitido`() {
        // Regra preservada: um timestamp simples antes do EOS é válido (a referência
        // mascara `< eos`, não `<= eos`).
        val passo0 = mapOf(base to 5f)
        val passo1 = mapOf(65 to 6.0f)
        val passo2 = mapOf(base + 20 to 7.0f)
        val passo3 = mapOf(WhisperOrtPrompt.END_OF_TEXT to 9.0f)
        val geracao = gerador().generate(
            LogitBackend(listOf(passo0, passo1, passo2, passo3)), FloatArray(128 * 3000),
        )
        assertEquals(
            "EOS deve encerrar depois do timestamp",
            WhisperOrtStopReason.EOS,
            geracao.stopReason,
        )
    }

    private fun terceiroPasso(candidatos: Map<Int, Float>): WhisperOrtGeneration =
        gerador().generate(
            LogitBackend(
                listOf(
                    mapOf(base to 0f),
                    mapOf(65 to 0f),
                    candidatos,
                    mapOf(WhisperOrtPrompt.END_OF_TEXT to 0f),
                ),
                logitPadrao = Float.NEGATIVE_INFINITY,
            ),
            FloatArray(128 * 3000),
        )

    @Test
    fun `massa temporal preserva a decisao com logits negativos grandes`() {
        // Subtrair a mesma constante de todos os logits nao muda probabilidades.
        // Dois timestamps 8,8 somam mais que texto9, mesmo com deslocamento -1000.
        val g = terceiroPasso(mapOf(65 to -991f, base + 50 to -991.2f, base + 60 to -991.2f))
        assertEquals(base + 50, g.tokens.getOrNull(2))
    }

    @Test
    fun `texto continua vencendo sem overflow com logits positivos grandes`() {
        // 12 > 8,5 + ln(2); deslocamento +1000 nao pode inverter essa escolha.
        val g = terceiroPasso(mapOf(65 to 1012f, base + 50 to 1008.5f, base + 60 to 1008.5f))
        assertEquals(65, g.tokens.getOrNull(2))
    }

    @Test
    fun `massa temporal tambem e comparada com eos elegivel`() {
        val g = terceiroPasso(mapOf(WhisperOrtPrompt.END_OF_TEXT to 9f, base + 50 to 8.8f, base + 60 to 8.8f))
        assertEquals("a referencia inclui EOS no melhor nao timestamp", base + 50, g.tokens.getOrNull(2))
    }

    @Test
    fun `timestamps todos mascarados nao desviam o texto`() {
        val g = terceiroPasso(mapOf(65 to 9f))
        assertEquals(65, g.tokens.getOrNull(2))
        assertEquals(WhisperOrtStopReason.EOS, g.stopReason)
    }
}
