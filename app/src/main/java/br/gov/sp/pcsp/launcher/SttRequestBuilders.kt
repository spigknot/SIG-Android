package br.gov.sp.pcsp.launcher

import java.net.URLEncoder

/**
 * Contratos puros das requisições STT.
 *
 * A Activity continua responsável por credenciais, arquivos, UI e execução
 * HTTP. Este objeto concentra somente URL, headers e campos, para que a
 * matriz provedor/modo possa ser verificada sem rede nem chave real.
 */
data class SttRequestHeader(val name: String, val value: String)

data class SttMultipartField(val name: String, val value: String)

data class SttRequestSpec(
    val url: String,
    val headers: List<SttRequestHeader> = emptyList(),
    val multipartFields: List<SttMultipartField> = emptyList(),
    val fileField: String? = null,
)

data class SttWebSocketSpec(
    val url: String,
    val header: SttRequestHeader,
)

object SttRequestBuilders {

    fun genericMultipart(
        url: String,
        fields: List<SttMultipartField>,
        fileField: String,
        accept: String = "application/json",
    ): SttRequestSpec = SttRequestSpec(
        url = url,
        headers = listOf(SttRequestHeader("accept", accept)),
        multipartFields = fields,
        fileField = fileField,
    )

    fun grokRest(apiKey: String, language: String?, diarize: Boolean): SttRequestSpec =
        SttRequestSpec(
            url = "https://api.x.ai/v1/stt",
            headers = listOf(SttRequestHeader("Authorization", "Bearer $apiKey")),
            multipartFields = buildList {
                language?.let { add(SttMultipartField("language", it)) }
                add(SttMultipartField("format", "true"))
                add(SttMultipartField("filler_words", "false"))
                if (diarize) add(SttMultipartField("diarize", "true"))
            },
            fileField = "file",
        )

    fun deepgramRest(
        apiKey: String,
        language: String,
        diarize: Boolean,
        keyterms: List<String>,
    ): SttRequestSpec = SttRequestSpec(
        url = queryUrl(
            base = "https://api.deepgram.com/v1/listen",
            params = buildList {
                add("model" to "nova-3")
                add("language" to language)
                add("smart_format" to "true")
                add("punctuate" to "true")
                if (diarize) add("diarize_model" to "latest")
                keyterms.map(String::trim)
                    .filter(String::isNotBlank)
                    .forEach { add("keyterm" to it) }
            },
        ),
        headers = listOf(SttRequestHeader("Authorization", "Token $apiKey")),
    )

    fun assemblyaiRest(
        apiKey: String,
        languageDetection: Boolean,
        languageCode: String?,
        speakerLabels: Boolean,
        punctuate: Boolean,
    ): SttRequestSpec = SttRequestSpec(
        url = "https://sync.assemblyai.com/transcribe",
        headers = listOf(
            SttRequestHeader("Authorization", apiKey),
            SttRequestHeader("X-AAI-Model", "u3-sync-pro"),
        ),
        multipartFields = buildList {
            if (languageDetection) add(SttMultipartField("language_detection", "true"))
            languageCode?.let { add(SttMultipartField("language_code", it)) }
            if (speakerLabels) add(SttMultipartField("speaker_labels", "true"))
            if (punctuate) add(SttMultipartField("punctuate", "true"))
        },
        fileField = "audio",
    )

    fun elevenlabsRest(
        apiKey: String,
        languageCode: String?,
        diarize: Boolean,
    ): SttRequestSpec = SttRequestSpec(
        url = "https://api.elevenlabs.io/v1/speech-to-text",
        headers = listOf(SttRequestHeader("xi-api-key", apiKey)),
        multipartFields = buildList {
            add(SttMultipartField("model_id", "scribe_v2"))
            languageCode?.let { add(SttMultipartField("language_code", it)) }
            if (diarize) add(SttMultipartField("diarize", "true"))
        },
        fileField = "file",
    )

    fun grokWebSocket(
        apiKey: String,
        language: String?,
        diarize: Boolean,
    ): SttWebSocketSpec = SttWebSocketSpec(
        url = queryUrl(
            base = "wss://api.x.ai/v1/stt",
            params = buildList {
                add("sample_rate" to "16000")
                add("encoding" to "pcm")
                add("interim_results" to "true")
                language?.let { add("language" to it) }
                add("format" to "true")
                add("smart_turn" to "0.65")
                add("endpointing" to "900")
                add("filler_words" to "false")
                if (diarize) add("diarize" to "true")
            },
        ),
        header = SttRequestHeader("Authorization", "Bearer $apiKey"),
    )

    fun deepgramWebSocket(
        apiKey: String,
        language: String,
        diarize: Boolean,
        keyterms: List<String>,
    ): SttWebSocketSpec = SttWebSocketSpec(
        url = queryUrl(
            base = "wss://api.deepgram.com/v1/listen",
            params = buildList {
                add("model" to "nova-3")
                add("language" to language)
                add("smart_format" to "true")
                add("punctuate" to "true")
                add("encoding" to "linear16")
                add("sample_rate" to "16000")
                add("channels" to "1")
                add("interim_results" to "true")
                add("endpointing" to "900")
                if (diarize) add("diarize_model" to "latest")
                keyterms.map(String::trim)
                    .filter(String::isNotBlank)
                    .forEach { add("keyterm" to it) }
            },
        ),
        header = SttRequestHeader("Authorization", "Token $apiKey"),
    )

    fun assemblyaiWebSocket(
        apiKey: String,
        languageCodes: List<String>,
        diarize: Boolean,
    ): SttWebSocketSpec = SttWebSocketSpec(
        url = queryUrl(
            base = "wss://streaming.assemblyai.com/v3/ws",
            params = buildList {
                add("speech_model" to "universal-3-5-pro")
                add("encoding" to "pcm_s16le")
                add("sample_rate" to "16000")
                add("continuous_partials" to "true")
                languageCodes.forEach { add("language_codes" to it) }
                if (diarize) add("speaker_labels" to "true")
            },
        ),
        header = SttRequestHeader("Authorization", apiKey),
    )

    fun elevenlabsWebSocket(
        apiKey: String,
        primaryLanguage: String?,
        secondaryLanguages: List<String>,
    ): SttWebSocketSpec = SttWebSocketSpec(
        url = queryUrl(
            base = "wss://api.elevenlabs.io/v1/speech-to-text/realtime",
            params = buildList {
                add("model_id" to "scribe_v2_realtime")
                add("audio_format" to "pcm_16000")
                primaryLanguage?.let { add("language_code" to it) }
                secondaryLanguages.forEach { add("secondary_languages" to it) }
                add("commit_strategy" to "vad")
                add("vad_silence_threshold_secs" to "1.0")
                add("include_timestamps" to "true")
            },
        ),
        header = SttRequestHeader("xi-api-key", apiKey),
    )

    private fun queryUrl(base: String, params: List<Pair<String, String>>): String =
        base + params.joinToString(prefix = "?", separator = "&") { (name, value) ->
            "${encode(name)}=${encode(value)}"
        }

    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
