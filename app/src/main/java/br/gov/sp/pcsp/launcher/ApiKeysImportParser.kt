package br.gov.sp.pcsp.launcher

import java.util.Locale

/** Parser puro do formato "serviço chave", uma entrada por linha.
 *
 * O identificador é a PRIMEIRA palavra da linha (rótulos de várias palavras,
 * como "Meta Muse Voice" ou "Imei Check", também são aceitos) e o restante da
 * linha é a chave. As linhas podem vir em QUALQUER ordem: o serviço é
 * resolvido pelo rótulo, nunca pela posição da linha. Rótulos são comparados
 * sem diferenciar maiúsculas/minúsculas e ignorando espaços e separadores.
 */

internal object ApiKeysImportParser {
    enum class Service {
        XAI,
        DEEPSEEK,
        DEEPGRAM,
        ASSEMBLYAI,
        ELEVENLABS,
        METAMUSE,
        ALIBABA,
        IMEI_CHECK,
    }

    data class Result(
        val keys: Map<Service, String>,
        val ignoredLineNumbers: List<Int>,
        val unknownServices: List<String>,
    )

    /** Rótulos aceitos, já normalizados (minúsculos, sem espaços/separadores). */
    private val SERVICE_ALIASES: Map<String, Service> = mapOf(
        "xai" to Service.XAI,
        "grok" to Service.XAI,
        "xaigrok" to Service.XAI,
        "deepseek" to Service.DEEPSEEK,
        "deepgram" to Service.DEEPGRAM,
        "assemblyai" to Service.ASSEMBLYAI,
        "assembly" to Service.ASSEMBLYAI,
        "elevenlabs" to Service.ELEVENLABS,
        "eleven" to Service.ELEVENLABS,
        "muse" to Service.METAMUSE,
        "meta" to Service.METAMUSE,
        "metamuse" to Service.METAMUSE,
        "musevoice" to Service.METAMUSE,
        "metamusevoice" to Service.METAMUSE,
        "alibaba" to Service.ALIBABA,
        "alibabacloud" to Service.ALIBABA,
        "alibabacloudapikey" to Service.ALIBABA,
        "alibabafunasr/qwen" to Service.ALIBABA,
        "alibabafunasrqwen" to Service.ALIBABA,
        "dashscope" to Service.ALIBABA,
        "funasr" to Service.ALIBABA,
        "qwen" to Service.ALIBABA,
        "imei" to Service.IMEI_CHECK,
        "imeicheck" to Service.IMEI_CHECK,
    )

    /** Maior rótulo possível em palavras ("Alibaba Cloud API Key" = 4). */
    private const val MAX_LABEL_WORDS = 5

    fun parse(content: String): Result {
        val keys = linkedMapOf<Service, String>()
        val ignoredLineNumbers = mutableListOf<Int>()
        val unknownServices = linkedSetOf<String>()

        content.lineSequence().forEachIndexed { index, rawLine ->
            val line = rawLine
                .removePrefix("\uFEFF")
                .trim()
                .replace(Regex("\\s+"), " ")
            if (line.isBlank()) return@forEachIndexed

            val tokens = splitLine(line)
            val maxWords = minOf(MAX_LABEL_WORDS, tokens.size - 1)
            for (words in maxWords downTo 1) {
                val service = serviceFor(tokens.take(words).joinToString(" ")) ?: continue
                val key = tokens.drop(words).joinToString(" ").trim()
                if (key.isBlank()) {
                    ignoredLineNumbers += index + 1
                } else {
                    keys[service] = key
                }
                return@forEachIndexed
            }

            // Nenhum rótulo conhecido: reporta o rótulo (nunca a chave).
            if (tokens.size >= 2) unknownServices += tokens.dropLast(1).joinToString(" ")
            ignoredLineNumbers += index + 1
        }

        return Result(
            keys = keys,
            ignoredLineNumbers = ignoredLineNumbers,
            unknownServices = unknownServices.toList(),
        )
    }

    /** Divide a linha em tokens; aceita "Serviço=chave" / "Serviço: chave". */
    private fun splitLine(line: String): List<String> {
        val tokens = line.split(' ').filter { it.isNotBlank() }
        if (tokens.size != 1) return tokens
        val separator = line.indexOfFirst { it == '=' || it == ':' }
        if (separator <= 0 || separator == line.lastIndex) return tokens
        return listOf(line.substring(0, separator).trim(), line.substring(separator + 1).trim())
            .filter { it.isNotBlank() }
    }

    private fun serviceFor(label: String): Service? = SERVICE_ALIASES[normalizeLabel(label)]

    private fun normalizeLabel(label: String): String = label
        .lowercase(Locale.US)
        .filter { !it.isWhitespace() && it != ':' && it != '-' && it != '_' }
}
