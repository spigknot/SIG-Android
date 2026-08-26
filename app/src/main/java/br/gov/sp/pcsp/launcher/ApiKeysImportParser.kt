package br.gov.sp.pcsp.launcher

import java.util.Locale

/** Parser puro do formato "nome do serviço chave", uma entrada por linha. */
internal object ApiKeysImportParser {
    enum class Service {
        XAI,
        DEEPSEEK,
        DEEPGRAM,
        ASSEMBLYAI,
        ELEVENLABS,
        IMEI_CHECK,
    }

    data class Result(
        val keys: Map<Service, String>,
        val ignoredLineNumbers: List<Int>,
        val unknownServices: List<String>,
    )

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

            val separator = line.lastIndexOf(' ')
            if (separator <= 0 || separator == line.lastIndex) {
                ignoredLineNumbers += index + 1
                return@forEachIndexed
            }

            val serviceLabel = line.substring(0, separator).trim().trimEnd(':')
            val key = line.substring(separator + 1).trim()
            val service = serviceFor(serviceLabel)
            if (service == null || key.isBlank()) {
                if (service == null && serviceLabel.isNotBlank()) unknownServices += serviceLabel
                ignoredLineNumbers += index + 1
                return@forEachIndexed
            }
            keys[service] = key
        }

        return Result(
            keys = keys,
            ignoredLineNumbers = ignoredLineNumbers,
            unknownServices = unknownServices.toList(),
        )
    }

    private fun serviceFor(label: String): Service? {
        return when (label.lowercase(Locale.US).replace(" ", "")) {
            "xai" -> Service.XAI
            "deepseek" -> Service.DEEPSEEK
            "deepgram" -> Service.DEEPGRAM
            "assemblyai" -> Service.ASSEMBLYAI
            "elevenlabs" -> Service.ELEVENLABS
            "imeicheck" -> Service.IMEI_CHECK
            else -> null
        }
    }
}
