package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regras puras da lista de keywords do STT (normalização, limites, recusa e
 *  o parâmetro EXATO de cada provedor). */
class SttKeywordsTest {

    private val terms = listOf("placa", "abordagem")

    @Test
    fun normalize_trimsDropsEmptyAndRepeatsKeepingOrder() {
        val normalized = SttKeywords.normalize(listOf("  placa ", "", "PLACA", "abordagem", "   "))

        assertEquals(listOf("placa", "abordagem"), normalized)
    }

    @Test
    fun normalize_capsAtTheTotalLimit() {
        val many = (1..SttKeywords.MAX_KEYWORDS + 20).map { "termo$it" }

        assertEquals(SttKeywords.MAX_KEYWORDS, SttKeywords.normalize(many).size)
    }

    @Test
    fun rejectionReason_describesEachProblem() {
        assertEquals(
            "Digite a palavra antes de adicionar.",
            SttKeywords.rejectionReason("   ", emptyList()),
        )
        assertEquals(
            "Use no máximo ${SttKeywords.MAX_KEYWORD_LENGTH} caracteres.",
            SttKeywords.rejectionReason("x".repeat(SttKeywords.MAX_KEYWORD_LENGTH + 1), emptyList()),
        )
        assertEquals(
            "O limite de ${SttKeywords.MAX_KEYWORDS} keywords foi atingido.",
            SttKeywords.rejectionReason(
                "placa",
                (1..SttKeywords.MAX_KEYWORDS).map { "termo$it" },
            ),
        )
        assertEquals("\"placa\" já está na lista.", SttKeywords.rejectionReason("PLACA", terms))
        assertNull(SttKeywords.rejectionReason("abordagem policial", terms))
    }

    @Test
    fun supportsKeywords_coversEveryApiProviderAndNotTheLocalServer() {
        listOf("deepgram", "grok", "elevenlabs", "assemblyai", "metamuse", "alibaba").forEach {
            assertTrue(it, SttKeywords.supportsKeywords(it))
        }
        assertFalse(SttKeywords.supportsKeywords("servidor"))
        assertFalse(SttKeywords.supportsKeywords(""))
    }

    @Test
    fun queryParams_repeatsTheParameterForDeepgramGrokAndElevenLabs() {
        assertEquals(
            listOf("keyterm" to "placa", "keyterm" to "abordagem"),
            SttKeywords.queryParams("deepgram", terms),
        )
        assertEquals(
            listOf("keyterm" to "placa", "keyterm" to "abordagem"),
            SttKeywords.queryParams("grok", terms),
        )
        assertEquals(
            listOf("keyterms" to "placa", "keyterms" to "abordagem"),
            SttKeywords.queryParams("elevenlabs", terms),
        )
    }

    @Test
    fun queryParams_assemblyaiUsesASingleJsonArrayParameter() {
        assertEquals(
            listOf("keyterms_prompt" to "[\"placa\",\"abordagem\"]"),
            SttKeywords.queryParams("assemblyai", terms),
        )
    }

    @Test
    fun queryParams_isEmptyForBodyProvidersAndEmptyLists() {
        assertTrue(SttKeywords.queryParams("metamuse", terms).isEmpty())
        assertTrue(SttKeywords.queryParams("alibaba", terms).isEmpty())
        assertTrue(SttKeywords.queryParams("servidor", terms).isEmpty())
        assertTrue(SttKeywords.queryParams("deepgram", emptyList()).isEmpty())
        assertTrue(SttKeywords.queryParams("deepgram", listOf("   ")).isEmpty())
    }

    @Test
    fun museKeywords_andAlibabaVocabulary_omitWhenEmpty() {
        assertEquals(terms, SttKeywords.museKeywords(terms))
        assertTrue(SttKeywords.museKeywords(listOf("  ")).isEmpty())
        assertEquals(
            mapOf("placa" to SttKeywords.ALIBABA_WEIGHT, "abordagem" to SttKeywords.ALIBABA_WEIGHT),
            SttKeywords.alibabaVocabulary(terms),
        )
        assertTrue(SttKeywords.alibabaVocabulary(emptyList()).isEmpty())
    }

    @Test
    fun assemblyaiPrompt_sharesTheSameNormalization() {
        assertEquals(listOf("placa"), SttKeywords.assemblyaiPrompt(listOf(" placa ", "PLACA")))
    }

    // ---- Ajuste da lista ao provedor/modo (limites DOCUMENTADOS) ----

    @Test
    fun fitForProvider_grokKeepsAtMost100TermsOf50Chars() {
        val many = (1..120).map { "termo$it" }
        val long = "x".repeat(51)

        // A normalização já corta em MAX_KEYWORDS; o limite do xAI é o mesmo.
        assertEquals(SttKeywords.MAX_KEYWORDS, SttKeywords.fitForProvider("grok", many).size)
        assertTrue(SttKeywords.fitForProvider("grok", listOf(long, "ok")).none { it == long })
    }

    @Test
    fun fitForProvider_deepgramRespectsThe500TokenBudget() {
        // Cada termo de 50 caracteres custa 13 tokens: o orçamento corta a lista.
        val heavy = (1..40).map { "t".repeat(48) + "%02d".format(it) }
        val fitted = SttKeywords.fitForProvider("deepgram", heavy)

        val tokens = fitted.sumOf { (it.length + 3) / 4 }
        assertTrue("tokens=$tokens", tokens <= 500)
        assertTrue(fitted.size in 1 until heavy.size)
        // Termos curtos normais continuam passando inteiros.
        assertEquals(listOf("placa", "abordagem"), SttKeywords.fitForProvider("deepgram", terms))
    }

    @Test
    fun fitForProvider_elevenlabsIsStricterLiveThanRest() {
        val longTerm = "y".repeat(25)

        // REST aceita até 50 caracteres; o Realtime documenta ~20 por termo.
        assertEquals(
            listOf(longTerm),
            SttKeywords.fitForProvider("elevenlabs", listOf(longTerm), isLive = false),
        )
        assertTrue(SttKeywords.fitForProvider("elevenlabs", listOf(longTerm), isLive = true).isEmpty())

        val many = (1..60).map { "t$it" }
        assertEquals(50, SttKeywords.fitForProvider("elevenlabs", many, isLive = true).size)
        assertEquals(60, SttKeywords.fitForProvider("elevenlabs", many, isLive = false).size)
    }

    @Test
    fun fitForProvider_assemblyaiLiveCapsTermsAndSyncCapsTotalCharacters() {
        val many = (1..120).map { "t$it" }
        assertEquals(100, SttKeywords.fitForProvider("assemblyai", many, isLive = true).size)

        val longTerms = (1..50).map { "a".repeat(48) + "%02d".format(it) }
        val syncTerms = SttKeywords.fitForProvider("assemblyai", longTerms, isLive = false)
        val chars = syncTerms.sumOf { it.length }
        assertTrue("chars=$chars", chars <= 2048)
        assertTrue(syncTerms.size < longTerms.size)
    }

    @Test
    fun fitForProvider_leavesOtherProvidersUntouched() {
        assertEquals(terms, SttKeywords.fitForProvider("metamuse", terms))
        assertEquals(terms, SttKeywords.fitForProvider("alibaba", terms))
    }

    @Test
    fun encodeDecode_roundTripsTheList() {
        val stored = SttKeywords.encode(terms)

        assertEquals(terms, SttKeywords.decode(stored))
        assertEquals(emptyList<String>(), SttKeywords.decode(""))
        assertEquals(emptyList<String>(), SttKeywords.decode("não é json"))
    }
}
