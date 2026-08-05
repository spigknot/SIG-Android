package br.gov.sp.pcsp.launcher

import org.json.JSONObject

object ModelServerStore {

    data class Config(
        val name: String,
        val url: String,
        val parameters: JSONObject,
        val selected: Boolean = false,
        val isGrokApi: Boolean = false,
        val isDeepseekApi: Boolean = false,
        val isProxy: Boolean = false,
        val provider: String = "",
        val fallbackUrl: String = ""
    ) {
        val modelName: String get() = parameters.optString("model").ifBlank { "modelo não informado" }
    }

    fun defaultConfig() = selectedConfig()

    fun readConfigs(): List<Config> {
        val available = mutableListOf(proxyConfig("grok"), proxyConfig("deepseek"))
        if (GrokApiSettings.isPlausibleXaiKey()) available += directGrok()
        if (GrokApiSettings.isPlausibleDeepseekKey()) available += directDeepseek()
        val selected = GrokApiSettings.selectedText()
        val resolved = if (available.any { it.name == selected }) selected else available.first().name
        if (resolved != selected) GrokApiSettings.selectText(resolved)
        return available.map { it.copy(selected = it.name == resolved) }
    }

    fun selectedConfig(): Config = readConfigs().first { it.selected }

    fun select(name: String): Boolean {
        if (readConfigs().none { it.name == name }) return false
        GrokApiSettings.selectText(name)
        return true
    }

    fun configForParts(name: String, proxyProvider: String): Config = when (name) {
        GrokApiSettings.TEXT_NAME -> directGrok()
        GrokApiSettings.DEEPSEEK_TEXT_NAME -> directDeepseek()
        PartsExtractionSettings.MODEL_PROXY_DEEPSEEK -> proxyConfig("deepseek", parts = true)
        else -> proxyConfig(proxyProvider, parts = true)
    }

    private fun proxyConfig(provider: String = "grok", parts: Boolean = false): Config {
        val normalized = if (provider == "deepseek") "deepseek" else "grok"
        return Config(
            if (normalized == "deepseek") GrokApiSettings.IA_PROXY_DEEPSEEK_NAME else GrokApiSettings.IA_PROXY_NAME,
            "http://servidor:8500",
            parameters(normalized, if (parts) defaultReasoning(normalized) else GrokApiSettings.textReasoning()),
            isProxy = true,
            provider = normalized,
            fallbackUrl = "http://avare:8500"
        )
    }

    private fun directGrok() = Config(
        GrokApiSettings.TEXT_NAME,
        "https://api.x.ai/v1/responses",
        parameters("grok", GrokApiSettings.textReasoning()),
        isGrokApi = true,
        provider = "grok"
    )

    private fun directDeepseek() = Config(
        GrokApiSettings.DEEPSEEK_TEXT_NAME,
        "https://api.deepseek.com/chat/completions",
        parameters("deepseek", GrokApiSettings.textReasoning()),
        isDeepseekApi = true,
        provider = "deepseek"
    )

    private fun parameters(provider: String, reasoning: String): JSONObject = if (provider == "deepseek") {
        JSONObject().put("model", "deepseek-v4-flash").put("temperature", 0.0)
            .put("max_tokens", 10000).put("reasoning_effort", reasoning.takeIf { it == "high" } ?: "none")
    } else {
        JSONObject().put("model", "grok-4.5").put("temperature", 0.0)
            .put("max_output_tokens", 10000).put("reasoning", JSONObject().put("effort", reasoning.takeIf { it == "high" } ?: "low"))
    }

    private fun defaultReasoning(provider: String) = if (provider == "deepseek") "none" else "low"
}
