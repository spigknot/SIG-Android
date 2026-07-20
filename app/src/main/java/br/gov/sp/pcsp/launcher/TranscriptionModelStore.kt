package br.gov.sp.pcsp.launcher

import org.json.JSONObject
import java.io.File

object TranscriptionModelStore {

    data class Config(
        val name: String,
        val url: String,
        val parameters: JSONObject,
        val selected: Boolean = false,
        val isGrokApi: Boolean = false
    ) {
        val modelName: String
            get() = parameters.optString("model").ifBlank { "modelo não informado" }
    }

    @Synchronized
    fun ensureDefaults() {
        runCatching {
            val file = modelsFile()
            file.parentFile?.mkdirs()
            if (!file.exists()) {
                file.writeText(DEFAULT_CONTENT.trimEnd() + "\n", Charsets.UTF_8)
            }
            val current = file.readText(Charsets.UTF_8)
            val migrated = migrateDefaultServerUrls(migrateTaguaiSpeech(current))
            val normalized = ensureSelectedLine(migrated)
            if (normalized != current) writeAtomically(file, normalized)
        }
    }

    fun selectedConfig(): Config {
        ensureDefaults()
        val configs = readConfigs()
        return configs.firstOrNull { it.selected } ?: configs.firstOrNull() ?: FALLBACK_CONFIG
    }

    fun readConfigs(): List<Config> {
        ensureDefaults()
        val stored = runCatching {
            modelsFile()
                .readLines(Charsets.UTF_8)
                .mapNotNull(::parseLine)
        }.getOrDefault(emptyList())
        val grokSelected = GrokApiSettings.isTranscriptionSelected()
        return stored.map { it.copy(selected = it.selected && !grokSelected) } + grokApiConfig(grokSelected)
    }

    @Synchronized
    fun select(name: String): Boolean {
        ensureDefaults()
        if (name == GrokApiSettings.TRANSCRIPTION_NAME) {
            if (!GrokApiSettings.hasApiKey()) return false
            val file = modelsFile()
            val cleared = file.readLines(Charsets.UTF_8)
                .joinToString("\n") { stripSelectionMarker(it) }
            writeAtomically(file, cleared.trimEnd() + "\n")
            GrokApiSettings.selectTranscription(true)
            return true
        }
        val file = modelsFile()
        var found = false
        val updated = file.readLines(Charsets.UTF_8).map { rawLine ->
            val config = parseLine(rawLine)
            if (config == null) {
                rawLine
            } else {
                val cleanLine = stripSelectionMarker(rawLine)
                if (config.name == name) {
                    found = true
                    "*$cleanLine"
                } else {
                    cleanLine
                }
            }
        }
        if (found) {
            GrokApiSettings.selectTranscription(false)
            writeAtomically(file, updated.joinToString("\n").trimEnd() + "\n")
        }
        return found
    }

    @Synchronized
    fun addConfig(name: String, url: String, modelName: String): Boolean {
        val cleanName = name.trim()
        val cleanUrl = url.trim()
        val cleanModel = modelName.trim()
        if (
            cleanName.isBlank() || cleanUrl.isBlank() || cleanModel.isBlank() ||
            cleanName.any { it == '\n' || it == '\r' || it == '\t' } ||
            cleanUrl.any { it == '\n' || it == '\r' || it == '\t' } ||
            !cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")
        ) {
            return false
        }
        ensureDefaults()
        val file = modelsFile()
        if (readConfigs().any { it.name.equals(cleanName, ignoreCase = true) }) return false
        val line = listOf(
            cleanName,
            cleanUrl,
            "\"model\": ${JSONObject.quote(cleanModel)}"
        ).joinToString("\t\t")
        val updated = file.readText(Charsets.UTF_8).trimEnd()
            .let { content -> if (content.isBlank()) line else "$content\n$line" } + "\n"
        writeAtomically(file, ensureSelectedLine(updated))
        return true
    }

    @Synchronized
    fun removeConfig(name: String): Boolean {
        if (name == GrokApiSettings.TRANSCRIPTION_NAME) return false
        ensureDefaults()
        val file = modelsFile()
        var removed = false
        val updated = file.readLines(Charsets.UTF_8).filter { rawLine ->
            val matches = parseLine(rawLine)?.name?.equals(name, ignoreCase = true) == true
            if (matches) removed = true
            !matches
        }
        if (removed) writeAtomically(file, ensureSelectedLine(updated.joinToString("\n")))
        return removed
    }

    fun modelsFile(): File = File(ModelServerStore.serverDirectory(), FILE_NAME)

    private fun parseLine(rawLine: String): Config? {
        val trimmed = rawLine.trim()
        if (trimmed.isBlank() || trimmed.startsWith("#")) return null
        val selected = trimmed.startsWith("*")
        val line = if (selected) trimmed.removePrefix("*").trimStart() else trimmed
        val fields = line.split(FIELD_SEPARATOR).map(String::trim)
        if (fields.size < 3) return null

        val parameters = runCatching {
            JSONObject("{${fields.drop(2).filter(String::isNotBlank).joinToString(",")}}")
        }.getOrNull() ?: return null
        if (!parameters.has("model")) return null
        return Config(
            name = fields[0],
            url = fields[1],
            parameters = parameters,
            selected = selected
        ).takeIf { it.name.isNotBlank() && it.url.isNotBlank() }
    }

    private fun grokApiConfig(selected: Boolean): Config = Config(
        name = GrokApiSettings.TRANSCRIPTION_NAME,
        url = "https://api.x.ai/v1/stt",
        parameters = JSONObject().put("model", "Speech to Text"),
        selected = selected,
        isGrokApi = true
    )

    private fun ensureSelectedLine(content: String): String {
        val lines = content.lines().toMutableList()
        if (lines.any { parseLine(it)?.selected == true }) {
            return lines.joinToString("\n").trimEnd() + "\n"
        }
        val firstConfigIndex = lines.indexOfFirst { parseLine(it) != null }
        if (firstConfigIndex >= 0) {
            lines[firstConfigIndex] = "*${stripSelectionMarker(lines[firstConfigIndex])}"
        }
        return lines.joinToString("\n").trimEnd() + "\n"
    }

    private fun migrateTaguaiSpeech(content: String): String {
        if (content.contains(TAGUAI_MIGRATION_MARKER)) return content
        val hasTaguai = content.lines().any {
            parseLine(it)?.name.equals("Taguai-speech", ignoreCase = true)
        }
        val marker = "#$TAGUAI_MIGRATION_MARKER"
        return buildString {
            append(content.trimEnd())
            if (isNotEmpty()) append('\n')
            if (!hasTaguai) append(TAGUAI_CONTENT).append('\n')
            append(marker).append('\n')
        }
    }

    /**
     * Granite now exposes its multipart endpoint directly at the server root.
     * Only touch the built-in entries so user-defined endpoints keep their intent.
     */
    private fun migrateDefaultServerUrls(content: String): String {
        return content.lines().joinToString("\n") { rawLine ->
            val config = parseLine(rawLine) ?: return@joinToString rawLine
            if (!config.name.equals("Avare-speech", ignoreCase = true) &&
                !config.name.equals("Taguai-speech", ignoreCase = true)
            ) {
                return@joinToString rawLine
            }
            rawLine.replace(Regex("/transcribe/?(?=\\t{2}|$)"), "")
        }
    }

    private fun stripSelectionMarker(rawLine: String): String {
        val leadingWhitespace = rawLine.takeWhile(Char::isWhitespace)
        val content = rawLine.drop(leadingWhitespace.length)
        return leadingWhitespace + content.removePrefix("*").trimStart()
    }

    private fun writeAtomically(file: File, content: String) {
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(content, Charsets.UTF_8)
        if (!temporary.renameTo(file)) {
            file.writeText(content, Charsets.UTF_8)
            temporary.delete()
        }
    }

    private val FIELD_SEPARATOR = Regex("""\t{2,}""")
    private const val FILE_NAME = "modelos_de_transcricao.txt"
    private const val TAGUAI_MIGRATION_MARKER = "sig:taguai-speech-v1"

    private val FALLBACK_CONFIG = Config(
        name = "Avare-speech",
        url = "http://100.110.211.23:8100",
        parameters = JSONObject().put("model", "granite-speech-4.1-2b-nar"),
        selected = true
    )

    private val AVARE_CONTENT = listOf(
        "*Avare-speech",
        "http://100.110.211.23:8100",
        "\"model\": \"granite-speech-4.1-2b-nar\""
    ).joinToString("\t\t")

    private val TAGUAI_CONTENT = listOf(
        "Taguai-speech",
        "http://100.70.207.12:8100",
        "\"model\": \"granite-speech-4.1-2b-nar\""
    ).joinToString("\t\t")

    private val DEFAULT_CONTENT = listOf(AVARE_CONTENT, TAGUAI_CONTENT).joinToString("\n")
}
