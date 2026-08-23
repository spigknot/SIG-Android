package br.gov.sp.pcsp.launcher

import org.json.JSONObject

object ModelServerStore {

    const val SERVER_GEMMA_NAME = "servidor (gemma-4-26B-A4B-abliterated)"
    const val SERVER_GEMMA_MODEL = "gemma4"

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
        val available = mutableListOf(proxyConfig(), serverGemma())
        if (GrokApiSettings.isPlausibleXaiKey()) available += directGrok()
        if (GrokApiSettings.isPlausibleXaiKey()) available += directGrokNonReasoning()
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

    fun configForParts(name: String, reasoning: String): Config = when (name) {
        GrokApiSettings.IA_PROXY_NAME,
        GrokApiSettings.IA_PROXY_DEEPSEEK_NAME -> proxyConfig()
        GrokApiSettings.TEXT_NAME -> directGrok(reasoning)
        GrokApiSettings.GROK_NON_REASONING_TEXT_NAME -> directGrokNonReasoning()
        GrokApiSettings.DEEPSEEK_TEXT_NAME -> directDeepseek(reasoning)
        SERVER_GEMMA_NAME -> serverGemma()
        else -> proxyConfig()
    }

    private fun proxyConfig(): Config {
        val model = GrokApiSettings.selectedProxyModel()
        val normalized = if (model == GrokApiSettings.DEEPSEEK_TEXT_NAME) "deepseek" else "grok"
        return Config(
            GrokApiSettings.IA_PROXY_NAME,
            "http://servidor:8500",
            parameters(normalized, defaultReasoning(normalized), model),
            isProxy = true,
            provider = normalized,
        )
    }

    private fun serverGemma() = Config(
        SERVER_GEMMA_NAME,
        "http://servidor:8400/v1/chat/completions",
        JSONObject()
            .put("model", SERVER_GEMMA_MODEL)
            .put("chat_template_kwargs", JSONObject().put("enable_thinking", false))
            .put("temperature", 0.0)
            .put("seed", 1)
            .put("top_k", 1)
            .put("top_p", 1),
        provider = "servidor",
    )

    private fun directGrok(reasoning: String = GrokApiSettings.textReasoning()) = Config(
        GrokApiSettings.TEXT_NAME,
        "https://api.x.ai/v1/responses",
        parameters("grok", reasoning, GrokApiSettings.TEXT_NAME),
        isGrokApi = true,
        provider = "grok"
    )

    private fun directGrokNonReasoning() = Config(
        GrokApiSettings.GROK_NON_REASONING_TEXT_NAME,
        "https://api.x.ai/v1/responses",
        JSONObject()
            .put("model", GrokApiSettings.GROK_NON_REASONING_TEXT_NAME)
            .put("temperature", 0.0)
            .put("max_output_tokens", 10000),
        isGrokApi = true,
        provider = "grok"
    )

    private fun directDeepseek(reasoning: String = GrokApiSettings.textReasoning()) = Config(
        GrokApiSettings.DEEPSEEK_TEXT_NAME,
        "https://api.deepseek.com/chat/completions",
        parameters("deepseek", reasoning, GrokApiSettings.DEEPSEEK_TEXT_NAME),
        isDeepseekApi = true,
        provider = "deepseek"
    )

    private fun parameters(provider: String, reasoning: String, model: String): JSONObject = if (provider == "deepseek") {
        JSONObject().put("model", model).put("temperature", 0.0)
            .put("max_tokens", 10000).put("reasoning_effort", reasoning)
    } else {
        JSONObject().put("model", model).put("temperature", 0.0)
            .put("max_output_tokens", 10000)
            .apply {
                if (model != GrokApiSettings.GROK_NON_REASONING_TEXT_NAME) {
                    put("reasoning", JSONObject().put("effort", reasoning))
                }
            }
    }

    private fun defaultReasoning(provider: String) = if (provider == "deepseek") "none" else "low"
}
