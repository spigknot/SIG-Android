package br.gov.sp.pcsp.launcher

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import okio.ByteString.Companion.decodeBase64

class GeminiSttProtocolTest {
    @Test fun restUsesInteractionsWithFileUriAndNoStoredHistory() {
        val body = JSONObject(GeminiSttProtocol.restBody("https://example.test/audio", "audio/wav", listOf("pt-BR"), false, emptyList()))
        assertEquals("gemini-3.5-transcribe", body.getString("model"))
        assertFalse(body.getBoolean("store"))
        assertEquals("audio/wav", body.getJSONArray("input").getJSONObject(0).getString("mime_type"))
        val config = body.getJSONObject("generation_config").getJSONObject("transcription_config")
        assertEquals("pt-BR", config.getJSONArray("language_codes").getString(0))
        assertEquals("verbatim", config.getJSONObject("mode").getString("type"))
        assertFalse(config.has("custom_vocabulary"))
    }

    @Test fun restDiarizationIsInsideMode() {
        val body = JSONObject(GeminiSttProtocol.restBody("uri", "audio/wav", listOf("multi"), true, emptyList()))
        val mode = body.getJSONObject("generation_config").getJSONObject("transcription_config").getJSONObject("mode")
        assertEquals("speaker", mode.getString("diarization_mode"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun diarizationAndVocabularyCannotBeCombined() {
        GeminiSttProtocol.restBody("uri", "audio/wav", listOf("pt-BR"), true, listOf("SIG"))
    }

    @Test fun liveSetupUsesDedicatedModelAndCamelCase() {
        val setup = JSONObject(GeminiSttProtocol.setup(listOf("pt-BR", "en-US"), listOf(" SIG ", "SIG", ""))).getJSONObject("setup")
        assertEquals("models/gemini-3.5-transcribe-live", setup.getString("model"))
        assertEquals("TEXT", setup.getJSONObject("generationConfig").getJSONArray("responseModalities").getString(0))
        val config = setup.getJSONObject("inputAudioTranscription")
        assertEquals("VERBATIM", config.getString("mode"))
        assertEquals(2, config.getJSONArray("languageCodes").length())
        assertEquals("SIG", config.getJSONArray("customVocabulary").getString(0))
        assertEquals(1, config.getJSONArray("customVocabulary").length())
        assertFalse(config.has("diarizationMode"))
    }

    @Test fun vocabularyIsLimitedToOneHundredTerms() {
        val setup = JSONObject(GeminiSttProtocol.setup(listOf("multi"), (1..120).map { "termo $it" }))
        assertEquals(100, setup.getJSONObject("setup").getJSONObject("inputAudioTranscription").getJSONArray("customVocabulary").length())
    }

    @Test fun liveAudioUsesExactSliceWithoutHeadersOrNewlines() {
        val bytes = byteArrayOf(99, 1, 0, -1, -1, 99)
        val audio = JSONObject(GeminiSttProtocol.audio(bytes, 1, 4)).getJSONObject("realtimeInput").getJSONObject("audio")
        assertEquals("audio/pcm;rate=16000", audio.getString("mimeType"))
        assertArrayEquals(byteArrayOf(1, 0, -1, -1), audio.getString("data").decodeBase64()!!.toByteArray())
        assertFalse(audio.getString("data").contains('\n'))
        assertTrue(JSONObject(GeminiSttProtocol.endAudio()).getJSONObject("realtimeInput").getBoolean("audioStreamEnd"))
    }

    @Test fun liveDraftAndFinalRemainSeparate() {
        val interim = GeminiSttProtocol.liveEvent(JSONObject("""{"serverContent":{"interimInputTranscription":{"text":"rascunho"}}}"""))
        assertEquals("rascunho", interim.draft)
        assertNull(interim.final)
        val final = GeminiSttProtocol.liveEvent(JSONObject("""{"serverContent":{"inputTranscription":{"text":"final"},"turnComplete":true}}"""))
        assertEquals("final", final.final)
        assertNull(final.draft)
        assertTrue(final.complete)
        assertTrue(GeminiSttProtocol.liveEvent(JSONObject("""{"setupComplete":{}}""")).ready)
        assertTrue(GeminiSttProtocol.liveEvent(JSONObject("""{"goAway":{"timeLeft":"20s"}}""")).goAway)
        assertTrue(GeminiSttProtocol.liveEvent(JSONObject("""{"error":{"code":400}}""")).error)
    }

    @Test fun restIgnoresInputIdsAndUsage() {
        val body = JSONObject("""{"id":"ignore","usage":{"text":"ignore"},"steps":[{"type":"model_input","content":[{"type":"text","text":"ignore"}]},{"type":"model_output","content":[{"type":"text","text":"Texto correto."}]}]}""")
        assertEquals("Texto correto.", GeminiSttProtocol.restText(body, false))
    }

    @Test fun restGroupsWordsBySpeakerInOrder() {
        val body = JSONObject("""{"steps":[{"type":"model_output","content":[{"type":"text","text":"Oi. Tudo bem?","annotations":[{"type":"word_info","text":"Oi.","speaker":"spk_1"},{"type":"word_info","text":"Tudo","speaker":"spk_2"},{"type":"word_info","text":"bem?","speaker":"spk_2"}]}]}]}""")
        assertEquals("Interlocutor 1: Oi.\nInterlocutor 2: Tudo bem?", GeminiSttProtocol.restText(body, true))
    }

    @Test fun uploadAllowsOnlyGoogleHttps() {
        val url = "https://generativelanguage.googleapis.com/upload/v1beta/files?upload_id=test"
        assertEquals(url, GeminiSttProtocol.uploadUrl(url))
        listOf("http://generativelanguage.googleapis.com", "https://evil.test", "https://generativelanguage.googleapis.com.evil.test", "https://user@generativelanguage.googleapis.com", "https://generativelanguage.googleapis.com:444").forEach {
            assertTrue(runCatching { GeminiSttProtocol.uploadUrl(it) }.isFailure)
        }
        assertEquals("https://generativelanguage.googleapis.com/v1beta/files/abc_123", GeminiSttProtocol.fileUrl("files/abc_123"))
        assertTrue(runCatching { GeminiSttProtocol.fileUrl("../secret") }.isFailure)
    }

    @Test fun googleKeyValidationUsesOnlyShape() {
        assertTrue(GeminiSttProtocol.plausibleKey("AIza" + "x".repeat(35)))
        assertFalse(GeminiSttProtocol.plausibleKey(""))
        assertFalse(GeminiSttProtocol.plausibleKey("AIza" + " ".repeat(35)))
    }

    @Test fun importerRecognizesGoogleAiStudioAliases() {
        listOf("Google AI Studio", "G AI Studio", "Gemini", "Google AI Std").forEach {
            assertEquals("fake-key", ApiKeysImportParser.parse("$it fake-key").keys[ApiKeysImportParser.Service.GOOGLE_AI_STUDIO])
        }
    }

    @Test fun languageUsesBcp47AndMulti() {
        assertEquals(listOf("pt-BR"), SttLanguageSettings.geminiLanguageCodes("pt-BR", ""))
        assertEquals(listOf("multi"), SttLanguageSettings.geminiLanguageCodes("multi", ""))
        assertEquals(listOf("en-US", "pt-BR"), SttLanguageSettings.geminiLanguageCodes("custom", "en-US,pt-BR,en-US"))
        assertEquals(listOf("pt"), SttLanguageSettings.invalidCodes("gemini", listOf("pt", "pt-BR")))
        assertTrue(runCatching { SttLanguageSettings.geminiLanguageCodes("custom", "") }.isFailure)
    }

    @Test fun diarizationOnlyRestAndKeywordsSupported() {
        assertTrue(SttDiarization.supportsDiarize("gemini", false))
        assertFalse(SttDiarization.supportsDiarize("gemini", true))
        assertTrue(SttKeywords.supportsKeywords("gemini"))
    }

    @Test fun liveUsesSilenceDuringPauseAndNeverReplaysFinalizedAudio() {
        val flow = SttLiveAudioFlow(isMuse = false, pcmBytesPerSecond = 32000, isGemini = true)
        assertEquals(100, flow.chunkMillis(2000))
        assertArrayEquals(ByteArray(4), flow.outgoingAudio(byteArrayOf(1, 2, 3, 4), 4, true))
        assertFalse(flow.replaysAudio)
        val recovery = flow.recoverAudio(byteArrayOf(1, 2), 6400)
        assertTrue(recovery.replayAudio.isEmpty())
        assertEquals(6400L, recovery.discardedAudioBytes)
        flow.resetPacing(1000)
        assertEquals(0L, flow.delayBeforeSendMillis(3200, 1000))
        assertEquals(100L, flow.delayBeforeSendMillis(3200, 1000))
    }
}
