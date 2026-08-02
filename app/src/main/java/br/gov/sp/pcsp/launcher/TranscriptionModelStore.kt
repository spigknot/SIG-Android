package br.gov.sp.pcsp.launcher

import org.json.JSONObject

object TranscriptionModelStore {
    const val AVARE_NAME = "avare"
    const val SERVER_NAME = "servidor"

    data class Config(
        val name: String,
        val url: String,
        val parameters: JSONObject,
        val selected: Boolean = false,
        val isGrokApi: Boolean = false
    ) {
        val modelName: String get() = parameters.optString("model").ifBlank { "modelo não informado" }
    }

    fun ensureDefaults() = Unit

    fun readConfigs(): List<Config> {
        val available = mutableListOf(
            Config(AVARE_NAME, "http://avare:8100", JSONObject().put("model", "granite-speech-4.1-2b-plus-ar")),
            Config(SERVER_NAME, "http://servidor:8100", JSONObject().put("model", "granite-speech-4.1-2b-nar"))
        )
        if (GrokApiSettings.isPlausibleXaiKey()) {
            available += Config(
                GrokApiSettings.TRANSCRIPTION_NAME,
                "https://api.x.ai/v1/stt",
                JSONObject().put("model", "grok-2-audio"),
                isGrokApi = true
            )
        }
        val selected = GrokApiSettings.selectedTranscription()
        return available.map { it.copy(selected = it.name == selected) }
            .let { list -> if (list.none { it.selected }) list.mapIndexed { index, item -> item.copy(selected = index == 0) } else list }
    }

    fun selectedConfig(): Config = readConfigs().first { it.selected }

    fun select(name: String): Boolean {
        if (readConfigs().none { it.name == name }) return false
        GrokApiSettings.selectTranscription(name)
        return true
    }

    fun addConfig(name: String, url: String, modelName: String) = false
    fun removeConfig(name: String) = false
}
