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

    @Test
    fun encodeDecode_roundTripsTheList() {
        val stored = SttKeywords.encode(terms)

        assertEquals(terms, SttKeywords.decode(stored))
        assertEquals(emptyList<String>(), SttKeywords.decode(""))
        assertEquals(emptyList<String>(), SttKeywords.decode("não é json"))
    }
}
