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
        val isGrokApi: Boolean = false,
        val isDeepgramApi: Boolean = false,
        val isAssemblyaiApi: Boolean = false,
        val isElevenlabsApi: Boolean = false
    ) {
        val modelName: String get() = parameters.optString("model").ifBlank { "modelo não informado" }
    }

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
        if (GrokApiSettings.hasDeepgramApiKey()) {
            available += Config(
                GrokApiSettings.DEEPGRAM_TRANSCRIPTION_NAME,
                "https://api.deepgram.com/v1/listen",
                JSONObject().put("model", "nova-3"),
                isDeepgramApi = true
            )
        }
        if (GrokApiSettings.hasAssemblyaiApiKey()) {
            available += Config(
                GrokApiSettings.ASSEMBLYAI_TRANSCRIPTION_NAME,
                "https://sync.assemblyai.com/transcribe",
                JSONObject().put("model", "universal-3-5-pro"),
                isAssemblyaiApi = true
            )
        }
        if (GrokApiSettings.hasElevenlabsApiKey()) {
            available += Config(
                GrokApiSettings.ELEVENLABS_TRANSCRIPTION_NAME,
                "https://api.elevenlabs.io/v1/speech-to-text",
                JSONObject().put("model", "scribe_v2"),
                isElevenlabsApi = true
            )
        }
        val selected = GrokApiSettings.selectedTranscription()
        val resolved = if (available.any { it.name == selected }) selected else available.first().name
        if (resolved != selected) GrokApiSettings.selectTranscription(resolved)
        return available.map { it.copy(selected = it.name == resolved) }
    }

    fun selectedConfig(): Config = readConfigs().first { it.selected }

    fun select(name: String): Boolean {
        if (readConfigs().none { it.name == name }) return false
        GrokApiSettings.selectTranscription(name)
        return true
    }

}
