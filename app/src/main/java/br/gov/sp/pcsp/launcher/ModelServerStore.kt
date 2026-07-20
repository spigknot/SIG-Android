package br.gov.sp.pcsp.launcher

import android.os.Environment
import org.json.JSONObject
import java.io.File

object ModelServerStore {

    data class Config(
        val name: String,
        val url: String,
        val parameters: JSONObject,
        val selected: Boolean = false,
        val isGrokApi: Boolean = false
    ) {
        val modelName: String
            get() = if (isGrokApi || name.contains("grok", ignoreCase = true)) {
                "grok-4.5"
            } else {
                parameters.optString("model").ifBlank { "modelo não informado" }
            }
    }

    @Synchronized
    fun ensureDefaults() {
        runCatching {
            val dir = serverDirectory().apply { mkdirs() }
            val file = textModelsFile()
            val legacyFile = File(dir, LEGACY_FILE_NAME)

            if (!file.exists() && legacyFile.exists()) {
                legacyFile.copyTo(file, overwrite = false)
            }
            if (!file.exists()) {
                file.writeText(DEFAULT_CONTENT.trimEnd() + "\n", Charsets.UTF_8)
            }
            if (file.exists() && legacyFile.exists()) {
                legacyFile.delete()
            }

            var current = file.readText(Charsets.UTF_8)
            current = current.replace(
                "\"model\": \"llama3.1-8b-abliterated\"",
                "\"model\": \"mannix/llama3.1-8b-abliterated\""
            )
            current = migrateDefaultServerUrls(current)
            current = migrateTaguaiGrok(current)
            val normalized = ensureSelectedLine(current)
            if (normalized != file.readText(Charsets.UTF_8)) {
                writeAtomically(file, normalized)
            }
        }
    }

    fun defaultConfig(): Config = selectedConfig()

    fun selectedConfig(): Config {
        ensureDefaults()
        val configs = readConfigs()
        return configs.firstOrNull { it.selected } ?: configs.firstOrNull() ?: FALLBACK_CONFIG
    }

    fun readConfigs(): List<Config> {
        ensureDefaults()
        val stored = runCatching {
            textModelsFile()
                .readLines(Charsets.UTF_8)
                .mapNotNull(::parseLine)
        }.getOrDefault(emptyList())
        val grokSelected = GrokApiSettings.isTextSelected()
        return stored.map { it.copy(selected = it.selected && !grokSelected) } + grokApiConfig(grokSelected)
    }

    @Synchronized
    fun select(name: String): Boolean {
        ensureDefaults()
        if (name == GrokApiSettings.TEXT_NAME) {
            if (!GrokApiSettings.hasApiKey()) return false
            val file = textModelsFile()
            val cleared = file.readLines(Charsets.UTF_8)
                .joinToString("\n") { stripSelectionMarker(it) }
            writeAtomically(file, cleared.trimEnd() + "\n")
            GrokApiSettings.selectText(true)
            return true
        }
        val file = textModelsFile()
        val lines = file.readLines(Charsets.UTF_8)
        var found = false
        val updated = lines.map { rawLine ->
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
            GrokApiSettings.selectText(false)
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
        ) return false

        ensureDefaults()
        if (readConfigs().any { it.name.equals(cleanName, ignoreCase = true) }) return false
        val line = listOf(
            cleanName,
            cleanUrl,
            "\"model\": ${JSONObject.quote(cleanModel)}"
        ).joinToString("\t\t")
        val file = textModelsFile()
        val updated = file.readText(Charsets.UTF_8).trimEnd()
            .let { current -> if (current.isBlank()) line else "$current\n$line" }
        writeAtomically(file, ensureSelectedLine(updated))
        return true
    }

    @Synchronized
    fun removeConfig(name: String): Boolean {
        if (name == GrokApiSettings.TEXT_NAME) return false
        ensureDefaults()
        val file = textModelsFile()
        var removed = false
        val updated = file.readLines(Charsets.UTF_8).filter { rawLine ->
            val matches = parseLine(rawLine)?.name?.equals(name, ignoreCase = true) == true
            if (matches) removed = true
            !matches
        }
        if (removed) writeAtomically(file, ensureSelectedLine(updated.joinToString("\n")))
        return removed
    }

    fun textModelsFile(): File = File(serverDirectory(), FILE_NAME)

    fun serverDirectory(): File {
        return File(File(Environment.getExternalStorageDirectory(), "SIG"), "Servidores")
    }

    private fun parseLine(rawLine: String): Config? {
        val trimmed = rawLine.trim()
        if (trimmed.isBlank() || trimmed.startsWith("#")) return null
        val selected = trimmed.startsWith("*")
        val line = if (selected) trimmed.removePrefix("*").trimStart() else trimmed
        val fields = line.split(FIELD_SEPARATOR).map(String::trim)
        if (fields.size < 3) return null

        val name = fields[0]
        val url = fields[1]
        if (name.isBlank() || url.isBlank()) return null

        val parameterFragments = fields.drop(2).filter(String::isNotBlank)
        if (parameterFragments.isEmpty()) return null
        val parameters = runCatching {
            JSONObject("{${parameterFragments.joinToString(",")}}")
        }.getOrNull() ?: return null
        if (!parameters.has("model")) return null
        return Config(name, url, parameters, selected)
    }

    private fun grokApiConfig(selected: Boolean): Config = Config(
        name = GrokApiSettings.TEXT_NAME,
        url = "https://api.x.ai/v1/responses",
        parameters = JSONObject()
            .put("model", "grok-4.5")
            .put("temperature", 0.0)
            .put("max_output_tokens", 5000)
            .put("reasoning", JSONObject().put("effort", "low")),
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

    /** Built-in Grok proxies now accept requests directly at their root URL. */
    private fun migrateDefaultServerUrls(content: String): String {
        return content.lines().joinToString("\n") { rawLine ->
            val config = parseLine(rawLine) ?: return@joinToString rawLine
            if (!config.name.equals("Avare-grok", ignoreCase = true) &&
                !config.name.equals("Taguai-grok", ignoreCase = true)
            ) {
                return@joinToString rawLine
            }
            rawLine.replace(Regex("/process/?(?=\\t{2}|$)"), "")
        }
    }

    private fun migrateTaguaiGrok(content: String): String {
        if (content.contains(TAGUAI_GROK_MIGRATION_MARKER)) return content
        val hasTaguai = content.lines().any {
            parseLine(it)?.name.equals("Taguai-grok", ignoreCase = true)
        }
        return buildString {
            append(content.trimEnd())
            if (isNotEmpty()) append('\n')
            if (!hasTaguai) append(TAGUAI_GROK_CONTENT).append('\n')
            append("#").append(TAGUAI_GROK_MIGRATION_MARKER).append('\n')
        }
    }

    private fun stripSelectionMarker(rawLine: String): String {
        val leadingWhitespace = rawLine.takeWhile(Char::isWhitespace)
        val content = rawLine.drop(leadingWhitespace.length)
        return leadingWhitespace + content.removePrefix("*").trimStart()
    }

    private fun writeAtomically(file: File, content: String) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(content, Charsets.UTF_8)
        if (!temporary.renameTo(file)) {
            file.writeText(content, Charsets.UTF_8)
            temporary.delete()
        }
    }

    private val FIELD_SEPARATOR = Regex("""\t{2,}""")

    private const val FILE_NAME = "modelos_de_texto.txt"
    private const val LEGACY_FILE_NAME = "modelos.txt"
    private const val TAGUAI_GROK_MIGRATION_MARKER = "sig:taguai-grok-v1"

    private val FALLBACK_CONFIG = Config(
        name = "Avare-grok",
        url = "http://100.110.211.23:8500",
        parameters = JSONObject()
            .put("model", "grok-4.3")
            .put("temperature", 0.0)
            .put("max_tokens", 5000)
            .put("reasoning", JSONObject().put("effort", "none")),
        selected = true
    )

    private val DEFAULT_CONTENT = listOf(
        listOf(
            "*Avare-grok",
            "http://100.110.211.23:8500",
            "\"model\": \"grok-4.3\"",
            "\"temperature\": 0.0",
            "\"max_tokens\": 5000",
            "\"reasoning\": {\"effort\": \"none\"}"
        ).joinToString("\t\t"),
        listOf(
            "Taguai-grok",
            "http://100.70.207.12:8500",
            "\"model\": \"grok-4.3\"",
            "\"temperature\": 0.0",
            "\"max_tokens\": 5000",
            "\"reasoning\": {\"effort\": \"none\"}"
        ).joinToString("\t\t"),
        listOf(
            "Avare-llama",
            "http://100.110.211.23:8400/api/generate",
            "\"model\": \"mannix/llama3.1-8b-abliterated\"",
            "\"temperature\": 0.0",
            "\"max_tokens\": 5000",
            "\"reasoning\": {\"effort\": \"none\"}",
            "\"seed\": 1"
        ).joinToString("\t\t"),
        listOf(
            "Avare-gemma3",
            "http://100.110.211.23:8400/api/generate",
            "\"model\": \"gemma3:12B\"",
            "\"temperature\": 0.0",
            "\"max_tokens\": 5000",
            "\"reasoning\": {\"effort\": \"none\"}",
            "\"seed\": 1"
        ).joinToString("\t\t")
    ).joinToString("\n")

    private val TAGUAI_GROK_CONTENT = listOf(
        "Taguai-grok",
        "http://100.70.207.12:8500",
        "\"model\": \"grok-4.3\"",
        "\"temperature\": 0.0",
        "\"max_tokens\": 5000",
        "\"reasoning\": {\"effort\": \"none\"}"
    ).joinToString("\t\t")
}
