package br.gov.sp.pcsp.launcher

import java.io.File
import java.net.URLEncoder
import java.util.UUID
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject

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
            url = ServiceEndpoints.GROK_STT_REST,
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
            base = ServiceEndpoints.DEEPGRAM_STT_REST,
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
        url = ServiceEndpoints.ASSEMBLYAI_STT_REST,
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
        url = ServiceEndpoints.ELEVENLABS_STT_REST,
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
            base = ServiceEndpoints.GROK_STT_WEBSOCKET,
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
            base = ServiceEndpoints.DEEPGRAM_STT_WEBSOCKET,
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
            base = ServiceEndpoints.ASSEMBLYAI_STT_WEBSOCKET,
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
            base = ServiceEndpoints.ELEVENLABS_STT_WEBSOCKET,
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

    // ---------------- Muse Voice (Meta Model API) ----------------
    //
    // Diferenças para os demais provedores (cookbook oficial meta-models):
    // - WS sem query params; a credencial vai DENTRO do 1º frame JSON em
    //   authorization.accessToken como a chave CRUA (sem prefixo "Bearer").
    //   Um header Authorization no handshake é ignorado pelo servidor.
    // - O áudio do WS é PCM cru binário (16 kHz do app -> PCM_16KHZ).
    // - Fim do áudio: frame textual {"type":"endStream"}.
    // - A diarização é o `mode` (ENDPOINTING vs DIARIZATION), sem booleano.
    // - REST: multipart com parte JSON "request" (application/json) + parte
    //   "audio", header Authorization: Bearer <chave>, audioEncoding WAV.

    const val MUSE_MODEL = "muse-voice-transcribe-1.0"
    const val MUSE_AUDIO_ENCODING_LIVE_16K = "PCM_16KHZ"
    const val MUSE_AUDIO_ENCODING_LIVE_24K = "PCM_24KHZ"
    const val MUSE_AUDIO_ENCODING_REST = "WAV"

    /** URL do WS (sem parâmetros — tudo vai no handshake JSON). O header é
     *  incluído por simetria com os demais provedores, mas o servidor o
     *  ignora: a credencial válida é a do handshake. */
    fun museWebSocket(apiKey: String): SttWebSocketSpec = SttWebSocketSpec(
        url = ServiceEndpoints.MUSE_STT_WEBSOCKET,
        header = SttRequestHeader("Authorization", "Bearer $apiKey"),
    )

    /** 1º frame textual do WS: JSON de configuração da sessão. */
    fun museHandshake(
        apiKey: String,
        mode: String,
        audioEncoding: String = MUSE_AUDIO_ENCODING_LIVE_16K,
        languageBias: List<String> = emptyList(),
        keywords: List<String> = emptyList(),
    ): String = JSONObject().apply {
        put("authorization", JSONObject().put("accessToken", apiKey))
        put("audioEncoding", audioEncoding)
        put("model", MUSE_MODEL)
        put("mode", mode)
        put("partialMode", "CUMULATIVE")
        put("emitAudioProgress", false)
        if (languageBias.isNotEmpty()) {
            put("languageBias", JSONArray().apply { languageBias.forEach { put(it) } })
        }
        if (keywords.isNotEmpty()) {
            put("keywords", JSONArray().apply { keywords.forEach { put(it) } })
        }
    }.toString()

    /** Frame textual de fim de áudio do WS. */
    fun museEndStream(): String = JSONObject().put("type", "endStream").toString()

    /** Contrato REST: URL + header (a parte JSON "request" vai em
     *  museRestRequestJson, enviada como multipart ao lado do "audio"). */
    fun museRest(apiKey: String): SttRequestSpec = SttRequestSpec(
        url = ServiceEndpoints.MUSE_STT_REST,
        headers = listOf(SttRequestHeader("Authorization", "Bearer $apiKey")),
        fileField = "audio",
    )

    /** Corpo JSON da parte "request" do multipart REST. */
    fun museRestRequestJson(
        mode: String,
        languageBias: List<String> = emptyList(),
        keywords: List<String> = emptyList(),
    ): String = JSONObject().apply {
        put("mode", mode)
        put("model", MUSE_MODEL)
        put("audioEncoding", MUSE_AUDIO_ENCODING_REST)
        if (languageBias.isNotEmpty()) {
            put("languageBias", JSONArray().apply { languageBias.forEach { put(it) } })
        }
        if (keywords.isNotEmpty()) {
            put("keywords", JSONArray().apply { keywords.forEach { put(it) } })
        }
    }.toString()

    /** Corpo multipart do REST: parte JSON "request" + parte "audio", byte a
     *  byte no formato do cliente de referência (o app Windows monta o corpo
     *  na mão pelo mesmo motivo). O parser do Muse é estrito: os helpers de
     *  multipart do OkHttp injetam Content-Length por parte e o servidor
     *  responde 400 "Malformed multipart body". Aqui só existem os headers
     *  Content-Disposition/Content-Type por parte, com Content-Length total
     *  conhecido (sem chunked) e o arquivo em streaming. */
    fun museRestBody(
        requestJson: String,
        fileName: String,
        audioFile: File,
    ): RequestBody {
        val boundary = "----sigmuse-${UUID.randomUUID().toString().replace("-", "")}"
        val safeName = fileName.replace("\"", "")
        val crlf = String(byteArrayOf(13, 10), Charsets.UTF_8)
        val preamble = (
            "--$boundary$crlf" +
                "Content-Disposition: form-data; name=\"request\"$crlf" +
                "Content-Type: application/json$crlf$crlf" +
                "$requestJson$crlf" +
                "--$boundary$crlf" +
                "Content-Disposition: form-data; name=\"audio\"; filename=\"$safeName\"$crlf" +
                "Content-Type: audio/wav$crlf$crlf"
            ).toByteArray(Charsets.UTF_8)
        val ending = "$crlf--$boundary--$crlf".toByteArray(Charsets.UTF_8)
        val totalLength = preamble.size.toLong() + audioFile.length() + ending.size
        return object : RequestBody() {
            override fun contentType() = "multipart/form-data; boundary=$boundary".toMediaType()
            override fun contentLength() = totalLength
            override fun writeTo(sink: BufferedSink) {
                sink.write(preamble)
                audioFile.inputStream().use { input ->
                    val chunk = ByteArray(128 * 1024)
                    while (true) {
                        val read = input.read(chunk)
                        if (read <= 0) break
                        sink.write(chunk, 0, read)
                    }
                }
                sink.write(ending)
            }
        }
    }

    // ---------------- Alibaba Fun ASR/Qwen (DashScope nativa, Singapore) ----------------
    //
    // Roteamento interno pelo modo do app (o usuário vê um nome só):
    // - REST (arquivo) -> fun-asr-flash-2026-06-15 (POST JSON com o áudio em
    //   data URI base64, NÃO multipart, NÃO OpenAI-compatible).
    // - WS (ao vivo) -> qwen-audio-3.0-asr-flash-streaming (run-task ->
    //   task-started -> áudio binário -> result-generated -> finish-task ->
    //   task-finished). Eventos chegam em header.event.
    // - Idioma centralizado em language_hints (omitido = automático).
    // - Sem diarização em nenhum modo.

    const val ALIBABA_REST_MODEL = "fun-asr-flash-2026-06-15"
    const val ALIBABA_WS_MODEL = "qwen-audio-3.0-asr-flash-streaming"
    const val ALIBABA_AUTH_ERROR =
        "API Key do Alibaba Cloud inválida ou incompatível com a região Singapore."
    const val ALIBABA_RATE_LIMIT_ERROR =
        "Alibaba Cloud: rate limit / limite de uso excedido. Aguarde e tente novamente."

    fun alibabaRest(apiKey: String): SttRequestSpec = SttRequestSpec(
        url = ServiceEndpoints.ALIBABA_STT_REST,
        headers = listOf(
            SttRequestHeader("accept", "application/json"),
            SttRequestHeader("Content-Type", "application/json"),
            SttRequestHeader("Authorization", "Bearer $apiKey"),
            SttRequestHeader("X-DashScope-SSE", "disable"),
        ),
    )

    /** Corpo JSON do REST DashScope nativo (áudio como data URI base64). */
    fun alibabaRestBody(
        audioDataUri: String,
        languageHints: List<String>? = null,
    ): String = JSONObject().apply {
        put("model", ALIBABA_REST_MODEL)
        put(
            "input", JSONObject().put(
                "messages", JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put(
                            "content", JSONArray().put(
                                JSONObject()
                                    .put("type", "input_audio")
                                    .put(
                                        "input_audio",
                                        JSONObject().put("data", audioDataUri)
                                    )
                            )
                        )
                )
            )
        )
        put(
            "parameters", JSONObject()
                .put("format", "wav")
                .put("sample_rate", 16000)
                .apply {
                    if (!languageHints.isNullOrEmpty()) {
                        put("language_hints", JSONArray().apply { languageHints.forEach { put(it) } })
                    }
                }
        )
    }.toString()

    /** Evento run-task do WS DashScope (novo task_id por sessão). */
    fun alibabaRunTask(
        taskId: String,
        languageHints: List<String>? = null,
    ): String = JSONObject().apply {
        put(
            "header", JSONObject()
                .put("action", "run-task")
                .put("task_id", taskId)
                .put("streaming", "duplex")
        )
        put(
            "payload", JSONObject()
                .put("task_group", "audio")
                .put("task", "asr")
                .put("function", "recognition")
                .put("model", ALIBABA_WS_MODEL)
                .put(
                    "parameters", JSONObject()
                        .put("format", "pcm")
                        .put("sample_rate", 16000)
                        .put("heartbeat", true)
                        .apply {
                            if (!languageHints.isNullOrEmpty()) {
                                put("language_hints", JSONArray().apply { languageHints.forEach { put(it) } })
                            }
                        }
                )
                .put("input", JSONObject())
        )
    }.toString()

    /** Evento finish-task do WS (mesmo task_id do run-task). */
    fun alibabaFinishTask(taskId: String): String = JSONObject().apply {
        put(
            "header", JSONObject()
                .put("action", "finish-task")
                .put("task_id", taskId)
                .put("streaming", "duplex")
        )
        put("payload", JSONObject().put("input", JSONObject()))
    }.toString()

    /** (texto, é_final) de um evento result-generated: frase com
     *  sentence_end=true é segmento fechado; end_time presente também fecha;
     *  o resto é parcial. */
    fun alibabaSentenceText(event: JSONObject): Pair<String, Boolean> {
        val sentence = event.optJSONObject("payload")
            ?.optJSONObject("output")
            ?.optJSONObject("sentence") ?: return "" to false
        val text = sentence.optString("text").trim()
        if (sentence.optBoolean("sentence_end", false)) return text to true
        val closedByEndTime = sentence.has("end_time") && !sentence.isNull("end_time")
        return text to closedByEndTime
    }

    fun alibabaWebSocket(apiKey: String): SttWebSocketSpec = SttWebSocketSpec(
        url = ServiceEndpoints.ALIBABA_STT_WEBSOCKET,
        header = SttRequestHeader("Authorization", "Bearer $apiKey"),
    )

    private fun queryUrl(base: String, params: List<Pair<String, String>>): String =
        base + params.joinToString(prefix = "?", separator = "&") { (name, value) ->
            "${encode(name)}=${encode(value)}"
        }

    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
