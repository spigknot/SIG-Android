package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SttRequestBuildersTest {

    @Test
    fun grokRest_hasProviderHeaderAndFileLast() {
        val spec = SttRequestBuilders.grokRest(
            apiKey = "test-key",
            language = "pt",
            diarize = true,
        )

        assertEquals("https://api.x.ai/v1/stt", spec.url)
        assertEquals(
            SttRequestHeader("Authorization", "Bearer test-key"),
            spec.headers.single(),
        )
        assertEquals(
            listOf("language", "format", "filler_words", "diarize"),
            spec.multipartFields.map { it.name },
        )
        assertEquals("file", spec.fileField)
    }

    @Test
    fun deepgramRest_repeatsKeytermsAndEncodesValues() {
        val spec = SttRequestBuilders.deepgramRest(
            apiKey = "test-key",
            language = "pt-BR",
            diarize = true,
            keyterms = listOf("placa", "ação policial"),
        )

        assertEquals(
            SttRequestHeader("Authorization", "Token test-key"),
            spec.headers.single(),
        )
        assertTrue(spec.fileField == null)
        assertTrue(spec.url.contains("model=nova-3"))
        assertTrue(spec.url.contains("language=pt-BR"))
        assertTrue(spec.url.contains("diarize_model=latest"))
        assertTrue(spec.url.contains("keyterm=placa"))
        assertTrue(spec.url.contains("keyterm=a%C3%A7%C3%A3o%20policial"))
    }

    @Test
    fun assemblyaiRest_keepsDetectionAndDiarizationFieldsSeparate() {
        val spec = SttRequestBuilders.assemblyaiRest(
            apiKey = "test-key",
            languageDetection = false,
            languageCode = "pt",
            speakerLabels = true,
            punctuate = true,
        )

        assertEquals("https://sync.assemblyai.com/transcribe", spec.url)
        assertEquals(
            listOf("Authorization", "X-AAI-Model"),
            spec.headers.map { it.name },
        )
        assertEquals(
            listOf("language_code", "speaker_labels", "punctuate"),
            spec.multipartFields.map { it.name },
        )
        assertEquals("audio", spec.fileField)
    }

    @Test
    fun elevenlabsRest_usesScribeModelAndFileField() {
        val spec = SttRequestBuilders.elevenlabsRest(
            apiKey = "test-key",
            languageCode = "pt",
            diarize = true,
        )

        assertEquals(
            SttRequestHeader("xi-api-key", "test-key"),
            spec.headers.single(),
        )
        assertEquals(
            listOf("model_id", "language_code", "diarize"),
            spec.multipartFields.map { it.name },
        )
        assertEquals("file", spec.fileField)
    }

    @Test
    fun webSockets_preserveRepeatedProviderParameters() {
        val assembly = SttRequestBuilders.assemblyaiWebSocket(
            apiKey = "test-key",
            languageCodes = listOf("pt", "en"),
            diarize = true,
        )
        val eleven = SttRequestBuilders.elevenlabsWebSocket(
            apiKey = "test-key",
            primaryLanguage = "pt",
            secondaryLanguages = listOf("en", "es"),
        )

        assertTrue(assembly.url.contains("language_codes=pt&language_codes=en"))
        assertTrue(assembly.url.contains("speaker_labels=true"))
        assertEquals(SttRequestHeader("Authorization", "test-key"), assembly.header)
        assertTrue(eleven.url.contains("language_code=pt"))
        assertTrue(eleven.url.contains("secondary_languages=en&secondary_languages=es"))
        assertEquals(SttRequestHeader("xi-api-key", "test-key"), eleven.header)
    }

    @Test
    fun grokWebSocket_preservesLiveParametersAndAuthorization() {
        val spec = SttRequestBuilders.grokWebSocket(
            apiKey = "test-key",
            language = "pt-BR",
            diarize = true,
        )

        assertTrue(spec.url.startsWith("wss://api.x.ai/v1/stt?"))
        assertTrue(spec.url.contains("sample_rate=16000"))
        assertTrue(spec.url.contains("encoding=pcm"))
        assertTrue(spec.url.contains("interim_results=true"))
        assertTrue(spec.url.contains("language=pt-BR"))
        assertTrue(spec.url.contains("endpointing=900"))
        assertTrue(spec.url.contains("diarize=true"))
        assertEquals(
            SttRequestHeader("Authorization", "Bearer test-key"),
            spec.header,
        )
    }

    @Test
    fun deepgramWebSocket_repeatsAndEncodesKeyterms() {
        val spec = SttRequestBuilders.deepgramWebSocket(
            apiKey = "test-key",
            language = "pt-BR",
            diarize = true,
            keyterms = listOf("placa", "ação policial"),
        )

        assertTrue(spec.url.startsWith("wss://api.deepgram.com/v1/listen?"))
        assertTrue(spec.url.contains("model=nova-3"))
        assertTrue(spec.url.contains("language=pt-BR"))
        assertTrue(spec.url.contains("encoding=linear16"))
        assertTrue(spec.url.contains("sample_rate=16000"))
        assertTrue(spec.url.contains("channels=1"))
        assertTrue(spec.url.contains("diarize_model=latest"))
        assertTrue(spec.url.contains("keyterm=placa"))
        assertTrue(spec.url.contains("keyterm=a%C3%A7%C3%A3o%20policial"))
        assertEquals(
            SttRequestHeader("Authorization", "Token test-key"),
            spec.header,
        )
    }

    @Test
    fun genericMultipart_preservesAcceptAndConfiguredFileField() {
        val spec = SttRequestBuilders.genericMultipart(
            url = "https://example.test/transcribe",
            fields = listOf(SttMultipartField("language", "pt")),
            fileField = "files",
        )

        assertEquals(SttRequestHeader("accept", "application/json"), spec.headers.single())
        assertEquals("language", spec.multipartFields.single().name)
        assertEquals("files", spec.fileField)
    }
}
