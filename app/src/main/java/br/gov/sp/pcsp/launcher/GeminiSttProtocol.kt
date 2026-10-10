package br.gov.sp.pcsp.launcher

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import okio.ByteString.Companion.toByteString

/** Contratos puros do Gemini Transcribe: Files, Interactions e Live, sem rede ou UI. */
internal object GeminiSttProtocol {
    const val REST_MODEL = "gemini-3.5-transcribe"
    const val LIVE_MODEL = "gemini-3.5-transcribe-live"
    const val BASE = "https://generativelanguage.googleapis.com"
    const val REST_URL = "$BASE/v1beta/interactions"
    const val FILES_URL = "$BASE/upload/v1beta/files"
    const val LIVE_URL = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
    const val FINISH_TIMEOUT_MS = 20_000L
    const val ROTATE_AFTER_MS = 540_000L

    fun plausibleKey(value: String): Boolean = value.trim().let {
        it.length == 39 && it.startsWith("AIza") && it.all { c -> c.isLetterOrDigit() || c == '-' || c == '_' }
    }

    fun uploadUrl(value: String): String {
        val uri = runCatching { URI(value) }.getOrElse { throw IllegalArgumentException("URL de upload inválida do Google.") }
        require(uri.scheme == "https" && uri.host == "generativelanguage.googleapis.com" &&
            uri.userInfo == null && (uri.port == -1 || uri.port == 443)) { "URL de upload inválida do Google." }
        return value
    }

    fun fileUrl(name: String): String {
        require(Regex("files/[a-zA-Z0-9_-]+").matches(name)) { "Identificador de arquivo inválido do Google." }
        return "$BASE/v1beta/$name"
    }

    private fun vocabulary(values: List<String>): List<String> =
        values.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(100)

    fun restBody(uri: String, mime: String, languages: List<String>, diarize: Boolean, keywords: List<String>): String {
        require(!diarize || vocabulary(keywords).isEmpty()) {
            "Gemini: desligue Keywords para usar diarização."
        }
        val mode = JSONObject().put("type", "verbatim")
        if (diarize) mode.put("diarization_mode", "speaker")
        val config = JSONObject().put("language_codes", JSONArray(languages)).put("mode", mode)
        vocabulary(keywords).takeIf { it.isNotEmpty() }?.let { config.put("custom_vocabulary", JSONArray(it)) }
        return JSONObject().put("model", REST_MODEL)
            .put("input", JSONArray().put(JSONObject().put("type", "audio").put("uri", uri).put("mime_type", mime)))
            .put("generation_config", JSONObject().put("transcription_config", config))
            .put("store", false).toString()
    }

    fun setup(languages: List<String>, keywords: List<String>): String {
        val config = JSONObject().put("languageCodes", JSONArray(languages)).put("mode", "VERBATIM")
        vocabulary(keywords).takeIf { it.isNotEmpty() }?.let { config.put("customVocabulary", JSONArray(it)) }
        return JSONObject().put("setup", JSONObject().put("model", "models/$LIVE_MODEL")
            .put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("TEXT")))
            .put("inputAudioTranscription", config)).toString()
    }

    fun audio(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size): String =
        JSONObject().put("realtimeInput", JSONObject().put("audio", JSONObject()
            .put("data", bytes.toByteString(offset, length).base64())
            .put("mimeType", "audio/pcm;rate=16000"))).toString()

    fun endAudio(): String = "{\"realtimeInput\":{\"audioStreamEnd\":true}}"

    data class LiveEvent(val ready: Boolean, val draft: String?, val final: String?, val complete: Boolean, val goAway: Boolean, val error: Boolean)

    fun liveEvent(payload: JSONObject): LiveEvent {
        val content = payload.optJSONObject("serverContent")
        return LiveEvent(payload.has("setupComplete"),
            content?.optJSONObject("interimInputTranscription")?.optString("text"),
            content?.optJSONObject("inputTranscription")?.optString("text"),
            content?.optBoolean("generationComplete") == true || content?.optBoolean("turnComplete") == true,
            payload.has("goAway"), payload.has("error"))
    }

    fun restText(payload: JSONObject, diarize: Boolean): String {
        val texts = mutableListOf<String>()
        val speakers = linkedMapOf<String, Int>()
        val steps = payload.optJSONArray("steps") ?: JSONArray()
        for (i in 0 until steps.length()) {
            val step = steps.optJSONObject(i) ?: continue
            if (step.optString("type") != "model_output") continue
            val content = step.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val part = content.optJSONObject(j) ?: continue
                if (part.optString("type") != "text") continue
                val annotations = part.optJSONArray("annotations") ?: JSONArray()
                val turns = mutableListOf<Pair<String, StringBuilder>>()
                if (diarize) for (k in 0 until annotations.length()) {
                    val word = annotations.optJSONObject(k) ?: continue
                    if (word.optString("type") != "word_info") continue
                    val text = word.optString("text").trim()
                    val speaker = word.optString("speaker")
                    if (text.isEmpty()) continue
                    if (turns.lastOrNull()?.first != speaker) turns += speaker to StringBuilder()
                    turns.last().second.apply { if (isNotEmpty()) append(' '); append(text) }
                }
                texts += if (turns.isEmpty()) part.optString("text").trim() else turns.joinToString("\n") {
                    val number = speakers.getOrPut(it.first) { speakers.size + 1 }
                    "Interlocutor $number: ${it.second}"
                }
            }
        }
        return texts.filter { it.isNotBlank() }.joinToString("\n")
    }
}
