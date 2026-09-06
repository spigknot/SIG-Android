package br.gov.sp.pcsp.launcher

import java.security.MessageDigest
import java.util.Locale

/** Contrato puro dos eventos emitidos pelo benchmark ADB do Granite 4.1 NAR. */
internal object GraniteNarBenchmarkProtocol {
    const val VERSION = 1
    const val TAG = "GraniteNarBench"
    const val PREFIX = "NAR_BENCH_JSON "

    private val stagePattern = Regex("^NAR etapa (.+): (\\d+)ms$")
    private val totalPattern = Regex("^NAR inferência total: (\\d+)ms$")
    private val sessionPattern = Regex("^ONNX (.+) criado \\(.+\\) em (\\d+)ms$")
    private val inputPattern = Regex(
        "^NAR entrada: samples=(\\d+) frames=(\\d+) effective_frames=(\\d+)$",
    )
    private val sequencePattern = Regex(
        "^NAR sequência: ctc_tokens=(\\d+) valid_audio=(\\d+) slots=(\\d+) llm_tokens=(\\d+)$",
    )

    fun safeRunId(raw: String?): String {
        val normalized = raw.orEmpty().trim().replace(Regex("[^A-Za-z0-9._-]"), "_")
        return normalized.take(96).ifEmpty { "nar-${System.currentTimeMillis()}" }
    }

    fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
    }

    fun json(fields: Map<String, Any?>): String = buildString {
        append('{')
        fields.entries.forEachIndexed { index, entry ->
            if (index > 0) append(',')
            appendQuoted(entry.key)
            append(':')
            appendJsonValue(entry.value)
        }
        append('}')
    }

    private fun StringBuilder.appendJsonValue(value: Any?) {
        when (value) {
            null -> append("null")
            is Boolean, is Byte, is Short, is Int, is Long -> append(value.toString())
            is Float -> if (value.isFinite()) append(value) else append("null")
            is Double -> if (value.isFinite()) append(value) else append("null")
            is Number -> append(value.toString())
            is String -> appendQuoted(value)
            is Map<*, *> -> {
                append('{')
                value.entries.forEachIndexed { index, entry ->
                    if (index > 0) append(',')
                    appendQuoted(entry.key.toString())
                    append(':')
                    appendJsonValue(entry.value)
                }
                append('}')
            }
            is Iterable<*> -> {
                append('[')
                value.forEachIndexed { index, item ->
                    if (index > 0) append(',')
                    appendJsonValue(item)
                }
                append(']')
            }
            else -> appendQuoted(value.toString())
        }
    }

    private fun StringBuilder.appendQuoted(value: String) {
        append('"')
        for (character in value) {
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) {
                    append("\\u%04x".format(Locale.ROOT, character.code))
                } else {
                    append(character)
                }
            }
        }
        append('"')
    }

    /** Converte os logs estáveis do engine em métricas sem acoplá-lo ao código debug. */
    class Collector {
        val stageMs = linkedMapOf<String, Long>()
        val sessionLoadMs = linkedMapOf<String, Long>()
        val dimensions = linkedMapOf<String, Long>()
        var engineTotalMs: Long? = null
            private set

        fun accept(line: String) {
            stagePattern.matchEntire(line)?.let {
                stageMs[it.groupValues[1]] = it.groupValues[2].toLong()
                return
            }
            totalPattern.matchEntire(line)?.let {
                engineTotalMs = it.groupValues[1].toLong()
                return
            }
            sessionPattern.matchEntire(line)?.let {
                sessionLoadMs[it.groupValues[1]] = it.groupValues[2].toLong()
                return
            }
            inputPattern.matchEntire(line)?.let {
                dimensions["samples"] = it.groupValues[1].toLong()
                dimensions["frames"] = it.groupValues[2].toLong()
                dimensions["effective_frames"] = it.groupValues[3].toLong()
                return
            }
            sequencePattern.matchEntire(line)?.let {
                dimensions["ctc_tokens"] = it.groupValues[1].toLong()
                dimensions["valid_audio"] = it.groupValues[2].toLong()
                dimensions["slots"] = it.groupValues[3].toLong()
                dimensions["llm_tokens"] = it.groupValues[4].toLong()
            }
        }
    }
}
