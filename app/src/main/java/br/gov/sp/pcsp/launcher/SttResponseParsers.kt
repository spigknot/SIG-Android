package br.gov.sp.pcsp.launcher

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Parsing PURO das respostas de transcrição dos provedores STT (REST, SSE e
 * WebSocket). Nada aqui depende de Android, UI, rede ou estado da Activity:
 * entram textos/JSON, saem textos/estruturas. Isso torna o comportamento
 * verificável por testes unitários (SttResponseParsersTest).
 *
 * Cada provedor tem o seu formato e as diferenças são intencionais — não
 * unifique os parsers sem conferir o contrato de cada API.
 */
object SttResponseParsers {

    data class ParsedText(
        val name: String?,
        val text: String,
        val timestampedText: String = ""
    )

    data class TimedEntry(
        val text: String,
        val startSeconds: Double,
        val endSeconds: Double
    )

    /** Chave de correlação de um upload: índice de entrada + nome exibido + nome do arquivo enviado. */
    data class UploadKey(
        val index: Int,
        val itemName: String,
        val fileName: String
    )

    /** Resultado do cruzamento entre a lista de uploads e os itens devolvidos pelo servidor. */
    data class MatchedTranscription(
        val index: Int,
        val itemName: String,
        val text: String,
        val timestampedText: String
    )

    // ---------------------------------------------------------------- Granite

    /**
     * Correlaciona os itens parseados com os uploads enviados, na mesma ordem
     * de [RemoteSttActivity.sendBatchToServerOnce]: primeiro por nome (exibido
     * ou do arquivo, aceitando caminho com '/'), depois por posição quando a
     * quantidade restante casa com a esperada, e por fim o único upload recebe
     * o primeiro item restante.
     */
    fun matchTranscriptions(
        parsed: List<ParsedText>,
        uploads: List<UploadKey>
    ): List<MatchedTranscription> {
        val sorted = uploads.sortedBy { it.index }
        val unused = parsed.toMutableList()
        return sorted.mapIndexed { orderIndex, upload ->
            val matchedIndex = unused.indexOfFirst { candidate ->
                val key = candidate.name?.lowercase(Locale.ROOT) ?: return@indexOfFirst false
                key == upload.itemName.lowercase(Locale.ROOT) ||
                    key == upload.fileName.lowercase(Locale.ROOT) ||
                    key.substringAfterLast('/') == upload.itemName.lowercase(Locale.ROOT) ||
                    key.substringAfterLast('/') == upload.fileName.lowercase(Locale.ROOT)
            }
            val item = when {
                matchedIndex >= 0 -> unused.removeAt(matchedIndex)
                unused.size == sorted.size - orderIndex -> unused.removeAt(0)
                sorted.size == 1 && unused.isNotEmpty() -> unused.removeAt(0)
                else -> ParsedText(null, "")
            }
            MatchedTranscription(upload.index, upload.itemName, item.text.trim(), item.timestampedText)
        }
    }

    fun parseResponseItems(responseText: String): List<ParsedText> {
        val trimmed = responseText.trim()
        if (trimmed.isBlank()) return emptyList()
        return try {
            when {
                trimmed.startsWith("{") -> parsedTextsFromObject(JSONObject(trimmed), null)
                trimmed.startsWith("[") -> parsedTextsFromArray(JSONArray(trimmed), null)
                else -> listOf(ParsedText(null, trimmed))
            }
        } catch (_: Throwable) {
            listOf(ParsedText(null, trimmed))
        }.filter { it.text.isNotBlank() }
    }

    private fun parsedTextsFromObject(json: JSONObject, fallbackName: String?): List<ParsedText> {
        val name = listOf("filename", "file", "name", "path")
            .firstNotNullOfOrNull { key -> json.optString(key).takeIf { it.isNotBlank() } }
            ?: fallbackName
        val directText = listOf("text", "transcription", "transcript", "result", "output")
            .firstNotNullOfOrNull { key -> json.optString(key).takeIf { it.isNotBlank() } }
        if (directText != null) {
            return listOf(ParsedText(name, directText, extractTimestampedText(json)))
        }

        listOf("results", "files", "items", "data", "transcriptions", "segments").forEach { key ->
            if (!json.has(key) || json.isNull(key)) return@forEach
            val value = json.get(key)
            val nested = parsedTextsFromAny(value, name)
            if (nested.isNotEmpty()) {
                if (key == "segments") {
                    return listOf(
                        ParsedText(
                            name,
                            nested.joinToString("") { it.text },
                            nested.mapNotNull { it.timestampedText.takeIf(String::isNotBlank) }.joinToString("\n")
                        )
                    )
                }
                return nested
            }
        }

        val mapped = mutableListOf<ParsedText>()
        json.keys().forEach { key ->
            val value = json.opt(key)
            if (value != null && value != JSONObject.NULL) {
                mapped += parsedTextsFromAny(value, key)
            }
        }
        return mapped
    }

    private fun parsedTextsFromArray(array: JSONArray, fallbackName: String?): List<ParsedText> {
        val result = mutableListOf<ParsedText>()
        for (i in 0 until array.length()) {
            val value = array.opt(i)
            if (value != null && value != JSONObject.NULL) {
                result += parsedTextsFromAny(value, fallbackName)
            }
        }
        return result
    }

    private fun parsedTextsFromAny(value: Any, fallbackName: String?): List<ParsedText> {
        return when (value) {
            is JSONObject -> parsedTextsFromObject(value, fallbackName)
            is JSONArray -> parsedTextsFromArray(value, fallbackName)
            is String -> listOf(ParsedText(fallbackName, value))
            else -> emptyList()
        }
    }

    private fun extractTimestampedText(json: JSONObject): String {
        val segments = json.optJSONArray("segments")
        if (segments != null) {
            return timedEntriesFromArray(segments, groupWords = false)
        }
        val words = json.optJSONArray("words")
        if (words != null) {
            return timedEntriesFromArray(words, groupWords = true)
        }
        val direct = timedEntryFromObject(json)
        return direct?.let { formatTimedEntry(it) }.orEmpty()
    }

    private fun timedEntriesFromArray(array: JSONArray, groupWords: Boolean): String {
        val entries = buildList {
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.let(::timedEntryFromObject)?.let(::add)
            }
        }
        if (entries.isEmpty()) return ""
        if (!groupWords) return entries.joinToString("\n", transform = ::formatTimedEntry)

        val phrases = mutableListOf<TimedEntry>()
        var currentText = StringBuilder()
        var currentStart = entries.first().startSeconds
        var currentEnd = entries.first().endSeconds
        entries.forEachIndexed { index, entry ->
            if (currentText.isNotEmpty() && !entry.text.matches(Regex("""^[,.;:!?]$"""))) {
                currentText.append(' ')
            }
            currentText.append(entry.text)
            currentEnd = entry.endSeconds
            if (entry.text.matches(Regex(""".*[.!?]$""")) || index == entries.lastIndex) {
                phrases += TimedEntry(currentText.toString().trim(), currentStart, currentEnd)
                currentText = StringBuilder()
                if (index < entries.lastIndex) currentStart = entries[index + 1].startSeconds
            }
        }
        return phrases.joinToString("\n", transform = ::formatTimedEntry)
    }

    private fun timedEntryFromObject(json: JSONObject): TimedEntry? {
        val text = listOf("text", "word", "transcript")
            .firstNotNullOfOrNull { key -> json.optString(key).trim().takeIf(String::isNotBlank) }
            ?: return null
        val start = json.optFiniteDouble("start")
            ?: json.optFiniteDouble("start_time")
            ?: json.optJSONArray("timestamp")?.optDouble(0)?.takeIf(Double::isFinite)
            ?: return null
        val end = json.optFiniteDouble("end")
            ?: json.optFiniteDouble("end_time")
            ?: json.optJSONArray("timestamp")?.optDouble(1)?.takeIf(Double::isFinite)
            ?: json.optFiniteDouble("duration")?.let { start + it }
            ?: return null
        if (start < 0.0 || end < start) return null
        return TimedEntry(text, start, end)
    }

    private fun JSONObject.optFiniteDouble(key: String): Double? {
        if (!has(key) || isNull(key)) return null
        return optDouble(key, Double.NaN).takeIf(Double::isFinite)
    }

    fun formatTimedEntry(entry: TimedEntry): String {
        return "[${formatTimestamp((entry.startSeconds * 1000).toLong())} -> " +
            "${formatTimestamp((entry.endSeconds * 1000).toLong())}] ${entry.text}"
    }

    fun formatTimestamp(timeMs: Long): String {
        val safe = timeMs.coerceAtLeast(0L)
        val hours = safe / 3_600_000L
        val minutes = (safe / 60_000L) % 60L
        val seconds = (safe / 1_000L) % 60L
        val millis = safe % 1_000L
        return String.format(Locale.US, "%02d:%02d:%02d.%03d", hours, minutes, seconds, millis)
    }

    // ---------------------------------------------------------------- Alibaba

    /** Texto do REST DashScope: output.text, choices ou "" (sem fala). */
    fun formatAlibabaRestResponse(payload: JSONObject): String {
        val output = payload.optJSONObject("output") ?: return ""
        output.optString("text").trim().takeIf { it.isNotBlank() }?.let { return it }
        val choices = output.optJSONArray("choices") ?: return ""
        for (index in 0 until choices.length()) {
            val message = choices.optJSONObject(index)?.optJSONObject("message") ?: continue
            message.optString("content").trim().takeIf { it.isNotBlank() }?.let { return it }
            val parts = message.optJSONArray("content") ?: continue
            for (part in 0 until parts.length()) {
                parts.optJSONObject(part)?.optString("text")?.trim()
                    ?.takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        return ""
    }

    /** Mensagem de erro de um evento task-failed do Alibaba (header com
     *  error_code/error_message), com os mapeamentos de auth e rate limit. */
    fun alibabaWsErrorMessage(event: JSONObject): String {
        val header = event.optJSONObject("header")
        val code = header?.optString("error_code").orEmpty()
        val message = header?.optString("error_message")
            ?.ifBlank { header.optString("message") }.orEmpty().trim()
        if (code in listOf("InvalidApiKey", "Unauthorized", "Forbidden", "AccessDenied") ||
            "401" in code || "403" in code
        ) {
            return SttRequestBuilders.ALIBABA_AUTH_ERROR
        }
        if (code == "Throttling" || "429" in code || "limit" in message.lowercase()) {
            return SttRequestBuilders.ALIBABA_RATE_LIMIT_ERROR
        }
        return message.ifBlank { "erro ${code.ifBlank { "desconhecido" }} do Alibaba" }
    }

    // ------------------------------------------------------------- Diarização

    /** Texto plano: remove os rótulos "Interlocutor N:" do texto diarizado. */
    fun stripDiarizationLabels(text: String): String =
        text.lineSequence()
            .joinToString("\n") { line ->
                line.replaceFirst(Regex("^\\s*Interlocutor\\s+\\d+\\s*:\\s*"), "").trim()
            }
            .trim()

    /** Grok: agrupa as palavras diarizadas em turnos "Interlocutor N:".
     *  [diarizationEnabled] vem de checkboxLiveDiarize.isChecked na Activity. */
    fun formatGrokDiarizedTranscript(payload: JSONObject, fallback: String, diarizationEnabled: Boolean): String {
        if (!diarizationEnabled) return fallback
        val words = payload.optJSONArray("words") ?: return fallback
        val output = StringBuilder()
        var speaker: Int? = null
        for (index in 0 until words.length()) {
            val word = words.optJSONObject(index) ?: continue
            val text = word.optString("text").trim()
            if (text.isBlank()) continue
            val nextSpeaker = word.optInt("speaker", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }
            if (nextSpeaker != speaker) {
                if (output.isNotEmpty()) output.append('\n')
                speaker = nextSpeaker
                output.append("Interlocutor ").append((speaker ?: 0) + 1).append(": ")
            } else if (output.isNotEmpty() && !output.endsWith(" ") && !text.matches(Regex("^[,.;:!?]$"))) {
                output.append(' ')
            }
            output.append(text)
        }
        return output.toString().trim().ifBlank { fallback }
    }

    /** Prefixa o texto ao vivo do Muse com "Interlocutor N:" quando a
     *  diarização está marcada e já há um falante corrente (letras A, B...).
     *  Fora da diarização, devolve o texto intacto. [speakerNumbers] é o mapa
     *  persistente da Activity (metamuseSpeakerNumbers). */
    fun prefixMetamuseSpeaker(
        rawText: String,
        diarizationEnabled: Boolean,
        currentSpeaker: String?,
        speakerNumbers: MutableMap<String, Int>
    ): String {
        if (!diarizationEnabled) return rawText
        val label = currentSpeaker ?: return rawText
        val number = speakerNumbers.getOrPut(label) { speakerNumbers.size + 1 }
        if (rawText.matches(Regex("^\\s*Interlocutor\\s+\\d+\\s*:.*", RegexOption.DOT_MATCHES_ALL))) {
            return rawText
        }
        return "Interlocutor $number: $rawText"
    }

    /** Formata os turns do REST do Muse (DIARIZATION): cada turno com
     *  "speaker" (A, B, ...) vira "Interlocutor N: <texto>". */
    fun formatMetamuseDiarizedTurns(turns: JSONArray): String {
        val speakerNumbers = linkedMapOf<String, Int>()
        val output = StringBuilder()
        for (index in 0 until turns.length()) {
            val turn = turns.optJSONObject(index) ?: continue
            val text = turn.optString("transcript").trim()
            if (text.isBlank()) continue
            val speaker = turn.optString("speaker").trim()
            val line = if (speaker.isNotEmpty()) {
                val number = speakerNumbers.getOrPut(speaker) { speakerNumbers.size + 1 }
                "Interlocutor $number: $text"
            } else {
                text
            }
            if (output.isNotEmpty()) output.append('\n')
            output.append(line)
        }
        return output.toString().trim()
    }

    // ------------------------------------------------------------------- SSE

    /** Extrai o texto de uma linha SSE/NDJSON (data:, [DONE], choices.delta...). */
    fun extractTextDelta(rawLine: String): String {
        var line = rawLine.trim()
        if (line.startsWith("event:") || line.startsWith("id:") || line.startsWith("retry:")) return ""
        if (line.startsWith("data:")) line = line.removePrefix("data:").trim()
        if (line == "[DONE]") return ""
        if (line.isBlank()) return ""
        return if (line.startsWith("{")) {
            try {
                val json = JSONObject(line)
                json.optString("text")
                    .ifBlank { json.optString("delta") }
                    .ifBlank {
                        val choices = json.optJSONArray("choices")
                        val choice = choices?.optJSONObject(0)
                        val delta = choice?.optJSONObject("delta")
                        delta?.optString("content").orEmpty().ifBlank { choice?.optString("text").orEmpty() }
                    }
                    .ifBlank {
                        val segments = json.optJSONArray("segments") ?: return@ifBlank ""
                        buildString {
                            for (i in 0 until segments.length()) {
                                append(segments.optJSONObject(i)?.optString("text").orEmpty())
                            }
                        }
                    }
            } catch (_: Throwable) {
                ""
            }
        } else {
            line
        }
    }

    fun isServerEnvelopeLine(rawLine: String): Boolean {
        val line = rawLine.trim()
        return line.startsWith("data:") ||
            line.startsWith("event:") ||
            line.startsWith("id:") ||
            line.startsWith("retry:") ||
            line == "[DONE]"
    }
}
