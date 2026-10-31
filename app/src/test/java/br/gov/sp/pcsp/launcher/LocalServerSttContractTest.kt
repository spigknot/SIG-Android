package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contrato do servidor STT local: motor anunciado em `/health`, rotas/campos
 * das duas abas e formatação da resposta detalhada do Nemotron.
 *
 * Os payloads abaixo são recortes de respostas REAIS de
 * `http://servidor:8100/v1/audio/transcriptions` com `response_format=verbose_json`
 * (o servidor rotula `speaker` a partir de 1 e devolve `words` mesmo quando só
 * a diarização foi pedida).
 */
class LocalServerSttContractTest {

    private val nemotronHealth = """{"model": "Nemotron 3.5 Transcribe · 30B A4B · Q2_0_M", "model_family": "salm"}"""
    private val graniteHealth = """{"model": "granite-speech-4.1-2b-nar"}"""

    // ------------------------------------------------------------ detecção

    @Test
    fun engineFor_readsModelAnnouncedByHealth() {
        assertEquals(
            LocalServerEngine.NEMOTRON,
            LocalGraniteModel.engineFor(LocalGraniteModel.parseId(nemotronHealth)),
        )
        assertEquals(
            LocalServerEngine.GRANITE,
            LocalGraniteModel.engineFor(LocalGraniteModel.parseId(graniteHealth)),
        )
    }

    @Test
    fun engineFor_unknownOrBlankFallsBackToGranite() {
        assertEquals(LocalServerEngine.GRANITE, LocalGraniteModel.engineFor(""))
        assertEquals(LocalServerEngine.GRANITE, LocalGraniteModel.engineFor("   "))
        assertEquals(LocalServerEngine.GRANITE, LocalGraniteModel.engineFor("modelo-desconhecido"))
        assertTrue(LocalGraniteModel.isNemotron("NEMOTRON"))
    }

    // ------------------------------------------------ aba Transcrição

    @Test
    fun transcription_graniteSendsOnlyFiles() {
        val spec = SttRequestBuilders.localServerTranscription(LocalServerEngine.GRANITE, "pt")

        assertEquals("http://servidor:8100/transcribe", spec.url)
        assertEquals("files", spec.fileField)
        assertTrue("Granite não envia campo de formulário nenhum", spec.multipartFields.isEmpty())
        assertEquals(SttRequestHeader("accept", "application/json"), spec.headers.single())
    }

    @Test
    fun transcription_nemotronSendsLanguage() {
        val spec = SttRequestBuilders.localServerTranscription(LocalServerEngine.NEMOTRON, "pt")

        assertEquals("http://servidor:8100/transcribe", spec.url)
        assertEquals("files", spec.fileField)
        assertEquals(listOf(SttMultipartField("language", "pt")), spec.multipartFields)
    }

    @Test
    fun transcription_nemotronOmitsLanguageOnAutomaticDetection() {
        val spec = SttRequestBuilders.localServerTranscription(LocalServerEngine.NEMOTRON, null)

        assertTrue(spec.multipartFields.isEmpty())
    }

    @Test
    fun neitherRouteEverSendsTheModelField() {
        val granite = SttRequestBuilders.localServerTranscription(LocalServerEngine.GRANITE, "pt")
        val nemotron = SttRequestBuilders.localServerOccurrence(
            LocalServerEngine.NEMOTRON, "pt", diarize = true, timestamps = true,
        )
        assertTrue(granite.multipartFields.none { it.name == "model" })
        assertTrue(nemotron.multipartFields.none { it.name == "model" })
    }

    // ------------------------------------------------ aba Ocorrência

    @Test
    fun occurrence_graniteKeepsBatchContractWithoutAdvancedFields() {
        val spec = SttRequestBuilders.localServerOccurrence(
            LocalServerEngine.GRANITE, "pt", diarize = true, timestamps = true,
        )

        assertEquals("http://servidor:8100/transcribe", spec.url)
        assertEquals("files", spec.fileField)
        assertTrue(spec.multipartFields.isEmpty())
    }

    @Test
    fun occurrence_nemotronUsesSingularFileAndVerboseJsonForDiarizationAndTimestamps() {
        val spec = SttRequestBuilders.localServerOccurrence(
            LocalServerEngine.NEMOTRON, "pt", diarize = true, timestamps = true,
        )

        assertEquals("http://servidor:8100/v1/audio/transcriptions", spec.url)
        assertEquals("file", spec.fileField)
        assertEquals(
            listOf(
                SttMultipartField("response_format", "verbose_json"),
                SttMultipartField("diarization", "true"),
                SttMultipartField("timestamp_granularities[]", "word"),
                SttMultipartField("language", "pt"),
            ),
            spec.multipartFields,
        )
    }

    @Test
    fun occurrence_nemotronDiarizationOnlyAsksVerboseJsonAndDiarization() {
        val spec = SttRequestBuilders.localServerOccurrence(
            LocalServerEngine.NEMOTRON, null, diarize = true, timestamps = false,
        )

        assertEquals(
            listOf(
                SttMultipartField("response_format", "verbose_json"),
                SttMultipartField("diarization", "true"),
            ),
            spec.multipartFields,
        )
    }

    @Test
    fun occurrence_nemotronTimestampsOnlyAsksVerboseJsonAndGranularities() {
        val spec = SttRequestBuilders.localServerOccurrence(
            LocalServerEngine.NEMOTRON, null, diarize = false, timestamps = true,
        )

        assertEquals(
            listOf(
                SttMultipartField("response_format", "verbose_json"),
                SttMultipartField("timestamp_granularities[]", "word"),
            ),
            spec.multipartFields,
        )
    }

    @Test
    fun occurrence_nemotronWithoutBoxesSendsNoAdvancedField() {
        val spec = SttRequestBuilders.localServerOccurrence(
            LocalServerEngine.NEMOTRON, null, diarize = false, timestamps = false,
        )

        assertTrue(spec.multipartFields.isEmpty())
        assertTrue(spec.multipartFields.none { it.name == "response_format" })
    }

    // ------------------------------------- formatação da resposta detalhada

    private val nemotronVerbose = """
        {
          "duration": 3.334,
          "language": "pt",
          "task": "transcribe",
          "text": "Este é um teste em português",
          "words": [
            {"confidence": 0, "end": 0.556, "speaker": 1, "start": 0.08, "word": "Este"},
            {"confidence": 0, "end": 0.714, "speaker": 1, "start": 0.556, "word": "é"},
            {"confidence": 0, "end": 0.953, "speaker": 1, "start": 0.714, "word": "um"},
            {"confidence": 0, "end": 1.350, "speaker": 1, "start": 0.953, "word": "teste"},
            {"confidence": 0, "end": 2.461, "speaker": 1, "start": 2.061, "word": "em"},
            {"confidence": 0, "end": 3.334, "speaker": 1, "start": 2.461, "word": "português"}
          ]
        }
    """.trimIndent()

    private val twoSpeakers = """
        {
          "text": "Ola tudo bem? Sim.",
          "words": [
            {"start": 0.0, "end": 0.2, "word": "Ola", "speaker": 1},
            {"start": 0.2, "end": 0.4, "word": "tudo", "speaker": 1},
            {"start": 0.4, "end": 0.6, "word": "bem?", "speaker": 1},
            {"start": 1.0, "end": 1.2, "word": "Sim.", "speaker": 2}
          ]
        }
    """.trimIndent()

    @Test
    fun format_withoutBoxesIgnoresWords() {
        assertEquals(
            "Este é um teste em português",
            SttResponseParsers.formatLocalServerTranscript(nemotronVerbose, diarize = false, timestamps = false),
        )
    }

    @Test
    fun format_diarizationUsesServerSpeakerLabelsOneBased() {
        val text = SttResponseParsers.formatLocalServerTranscript(twoSpeakers, diarize = true, timestamps = false)

        assertEquals("Interlocutor 1: Ola tudo bem?\nInterlocutor 2: Sim.", text)
    }

    @Test
    fun format_timestampsShowsPhraseRangesOnly() {
        val text = SttResponseParsers.formatLocalServerTranscript(nemotronVerbose, diarize = false, timestamps = true)

        assertEquals("[00:00:00.080 -> 00:00:03.334] Este é um teste em português", text)
    }

    @Test
    fun format_bothBoxesShowsRangesAndSpeakers() {
        val text = SttResponseParsers.formatLocalServerTranscript(twoSpeakers, diarize = true, timestamps = true)

        assertEquals(
            "[00:00:00.000 -> 00:00:00.600] Interlocutor 1: Ola tudo bem?\n" +
                "[00:00:01.000 -> 00:00:01.200] Interlocutor 2: Sim.",
            text,
        )
    }

    @Test
    fun format_brokenJsonFallsBackToRawBody() {
        assertEquals(
            "não é json",
            SttResponseParsers.formatLocalServerTranscript("não é json", diarize = true, timestamps = true),
        )
    }

    @Test
    fun format_withoutWordsFallsBackToText() {
        val body = """{"text": "só o texto"}"""

        assertEquals(
            "só o texto",
            SttResponseParsers.formatLocalServerTranscript(body, diarize = true, timestamps = true),
        )
    }

    // --------------------------------------------- idioma aceito pelo Nemotron

    @Test
    fun nemotronLanguageParam_normalizesAndOmitsAutomatic() {
        assertEquals("pt", SttLanguageSettings.nemotronLanguageParam("pt", ""))
        assertEquals("pt", SttLanguageSettings.nemotronLanguageParam("PT", ""))
        assertEquals(null, SttLanguageSettings.nemotronLanguageParam("multi", ""))
        // Um código por requisição: em "custom" vale o primeiro válido.
        assertEquals("de", SttLanguageSettings.nemotronLanguageParam("custom", "de, en"))
        // Código fora da lista do servidor nunca é inventado.
        assertEquals(null, SttLanguageSettings.nemotronLanguageParam("custom", "xx"))
        assertEquals(null, SttLanguageSettings.nemotronLanguageParam("klingon", ""))
    }

    @Test
    fun nemotronCodesCoverTheServerList() {
        val expected = setOf(
            "ar", "bg", "bn", "cs", "da", "de", "el", "en", "es", "et", "fi", "fil", "tl",
            "fr", "he", "hi", "hr", "hu", "hy", "id", "it", "ja", "ko", "lt", "lv", "mt",
            "nb", "nn", "no", "nl", "pl", "pt", "ro", "ru", "sk", "sl", "sv", "ta", "th",
            "tr", "uk", "vi", "zh",
        )

        assertEquals(expected, SttLanguageSettings.NEMOTRON_CODES)
        assertEquals(listOf("xx"), SttLanguageSettings.invalidCodes("nemotron", listOf("pt", "xx")))
    }
}
