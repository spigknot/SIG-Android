package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun museWebSocket_hasNoQueryParamsAndBearerHeader() {
        val spec = SttRequestBuilders.museWebSocket(apiKey = "LLM|1|secret")

        assertEquals("wss://api.meta.ai/v1/asr/realtime", spec.url)
        assertEquals(
            SttRequestHeader("Authorization", "Bearer LLM|1|secret"),
            spec.header,
        )
    }

    @Test
    fun museHandshake_carriesRawKeyAndSessionConfig() {
        val json = org.json.JSONObject(
            SttRequestBuilders.museHandshake(
                apiKey = "LLM|1|secret",
                mode = "ENDPOINTING",
                audioEncoding = "PCM_16KHZ",
                languageBias = listOf("Portuguese"),
            )
        )

        // A credencial vai CRUA dentro do JSON (sem prefixo "Bearer").
        assertEquals("LLM|1|secret", json.getJSONObject("authorization").getString("accessToken"))
        assertEquals("PCM_16KHZ", json.getString("audioEncoding"))
        assertEquals("muse-voice-transcribe-1.0", json.getString("model"))
        assertEquals("ENDPOINTING", json.getString("mode"))
        assertEquals("CUMULATIVE", json.getString("partialMode"))
        assertEquals(false, json.getBoolean("emitAudioProgress"))
        assertEquals("Portuguese", json.getJSONArray("languageBias").getString(0))
    }

    @Test
    fun museHandshake_omitsLanguageBiasWhenEmpty() {
        val json = org.json.JSONObject(
            SttRequestBuilders.museHandshake(
                apiKey = "LLM|1|secret",
                mode = "DIARIZATION",
            )
        )

        assertEquals("DIARIZATION", json.getString("mode"))
        assertTrue(!json.has("languageBias"))
    }

    @Test
    fun museEndStream_sendsTypedFrame() {
        val json = org.json.JSONObject(SttRequestBuilders.museEndStream())

        assertEquals("endStream", json.getString("type"))
    }

    @Test
    fun museRest_usesBearerHeaderAndWavRequestJson() {
        val spec = SttRequestBuilders.museRest(apiKey = "LLM|1|secret")
        val requestJson = org.json.JSONObject(
            SttRequestBuilders.museRestRequestJson(
                mode = "DIARIZATION",
                languageBias = listOf("Portuguese"),
            )
        )

        assertEquals("https://api.meta.ai/v1/asr/transcribe", spec.url)
        assertEquals(
            SttRequestHeader("Authorization", "Bearer LLM|1|secret"),
            spec.headers.single(),
        )
        assertEquals("audio", spec.fileField)
        assertEquals("DIARIZATION", requestJson.getString("mode"))
        assertEquals("muse-voice-transcribe-1.0", requestJson.getString("model"))
        assertEquals("WAV", requestJson.getString("audioEncoding"))
        assertEquals("Portuguese", requestJson.getJSONArray("languageBias").getString(0))
    }

    @Test
    fun museRestBody_matchesStrictMultipartContract() {
        val wav = java.io.File.createTempFile("muse-test", ".wav")
        try {
            wav.writeBytes("RIFF....fake-wav-bytes".toByteArray(Charsets.UTF_8))
            val body = SttRequestBuilders.museRestBody(
                requestJson = SttRequestBuilders.museRestRequestJson(
                    mode = "DIARIZATION",
                    languageBias = listOf("Portuguese"),
                ),
                fileName = "gravacao.wav",
                audioFile = wav,
            )

            val buffer = okio.Buffer()
            body.writeTo(buffer)
            val wireSize = buffer.size
            val raw = buffer.readUtf8()

            // Ordem: request antes de audio; filenames e content-types exatos.
            assertTrue(raw.indexOf("name=\"request\"") in 0 until raw.indexOf("name=\"audio\""))
            assertTrue(raw.contains("Content-Type: application/json"))
            assertTrue(raw.contains("filename=\"gravacao.wav\""))
            assertTrue(raw.contains("Content-Type: audio/wav"))
            assertTrue(raw.contains("\"mode\":\"DIARIZATION\""))
            assertTrue(raw.contains("RIFF....fake-wav-bytes"))
            // O parser do Muse é estrito: nenhum header extra por parte.
            assertFalse(raw.contains("Content-Length"))
            assertTrue(raw.trimEnd().endsWith("--"))
            // Content-Length total conhecido (sem chunked).
            assertEquals(wireSize, body.contentLength())
        } finally {
            wav.delete()
        }
    }

    @Test
    fun alibabaRest_usesNativeDashScopeContract() {
        val spec = SttRequestBuilders.alibabaRest(apiKey = "sk-ws-test")

        assertEquals(
            "https://dashscope-intl.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation",
            spec.url
        )
        assertEquals("Bearer sk-ws-test", spec.headers.first { it.name == "Authorization" }.value)
        assertEquals("disable", spec.headers.first { it.name == "X-DashScope-SSE" }.value)
        assertEquals("application/json", spec.headers.first { it.name == "Content-Type" }.value)

        val json = org.json.JSONObject(
            SttRequestBuilders.alibabaRestBody(
                audioDataUri = "data:audio/wav;base64,AAA",
                languageHints = listOf("pt"),
            )
        )
        assertEquals("fun-asr-flash-2026-06-15", json.getString("model"))
        val params = json.getJSONObject("parameters")
        assertEquals("wav", params.getString("format"))
        assertEquals(16000, params.getInt("sample_rate"))
        assertEquals("pt", params.getJSONArray("language_hints").getString(0))
        val audio = json.getJSONObject("input")
            .getJSONArray("messages").getJSONObject(0)
            .getJSONArray("content").getJSONObject(0)
            .getJSONObject("input_audio").getString("data")
        assertEquals("data:audio/wav;base64,AAA", audio)
    }

    @Test
    fun alibabaRestBody_autoOmitsHints() {
        val json = org.json.JSONObject(
            SttRequestBuilders.alibabaRestBody(audioDataUri = "x", languageHints = null)
        )

        assertTrue(!json.getJSONObject("parameters").has("language_hints"))
        assertEquals("wav", json.getJSONObject("parameters").getString("format"))
    }

    @Test
    fun alibabaRunTask_shapeAndHeartbeat() {
        val json = org.json.JSONObject(
            SttRequestBuilders.alibabaRunTask(taskId = "tid-123", languageHints = listOf("pt"))
        )

        assertEquals("run-task", json.getJSONObject("header").getString("action"))
        assertEquals("tid-123", json.getJSONObject("header").getString("task_id"))
        assertEquals("duplex", json.getJSONObject("header").getString("streaming"))
        val payload = json.getJSONObject("payload")
        assertEquals("audio", payload.getString("task_group"))
        assertEquals("asr", payload.getString("task"))
        assertEquals("recognition", payload.getString("function"))
        assertEquals(
            "qwen-audio-3.0-asr-flash-streaming",
            payload.getString("model")
        )
        val params = payload.getJSONObject("parameters")
        assertEquals("pcm", params.getString("format"))
        assertEquals(16000, params.getInt("sample_rate"))
        assertEquals(true, params.getBoolean("heartbeat"))
        assertEquals("pt", params.getJSONArray("language_hints").getString(0))
    }

    @Test
    fun alibabaFinishTask_reusesTaskId() {
        val json = org.json.JSONObject(SttRequestBuilders.alibabaFinishTask("tid-123"))

        assertEquals("finish-task", json.getJSONObject("header").getString("action"))
        assertEquals("tid-123", json.getJSONObject("header").getString("task_id"))
        assertEquals("duplex", json.getJSONObject("header").getString("streaming"))
    }

    @Test
    fun alibabaSentenceText_distinguishesFinalAndPartial() {
        val final = org.json.JSONObject(
            "{\"payload\":{\"output\":{\"sentence\":{\"text\":\"Olá\",\"sentence_end\":true}}}}"
        )
        assertEquals("Olá" to true, SttRequestBuilders.alibabaSentenceText(final))

        val partial = org.json.JSONObject(
            "{\"payload\":{\"output\":{\"sentence\":{\"text\":\"Olá\"}}}}"
        )
        assertEquals("Olá" to false, SttRequestBuilders.alibabaSentenceText(partial))
        assertEquals("" to false, SttRequestBuilders.alibabaSentenceText(org.json.JSONObject("{}")))
    }

    @Test
    fun alibabaWebSocket_usesSharedSingaporeEndpoint() {
        val spec = SttRequestBuilders.alibabaWebSocket(apiKey = "sk-ws-test")

        assertEquals("wss://dashscope-intl.aliyuncs.com/api-ws/v1/inference", spec.url)
        assertEquals(
            SttRequestHeader("Authorization", "Bearer sk-ws-test"),
            spec.header,
        )
    }
}
