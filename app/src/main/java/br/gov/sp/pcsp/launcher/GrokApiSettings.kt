package br.gov.sp.pcsp.launcher

import android.content.Context

/** Preferências privadas das integrações de API e dos modelos internos. */
object GrokApiSettings {

    const val TRANSCRIPTION_NAME = "Grok STT"
    const val TEXT_NAME = "grok-4.5"
    const val DEEPSEEK_TEXT_NAME = "deepseek-v4-pro"
    const val IA_PROXY_NAME = "IA-Proxy (grok-4.5)"
    const val IA_PROXY_DEEPSEEK_NAME = "IA-Proxy (deepseek-v4-pro)"

    private const val PREFERENCES = "model_api_settings"
    private const val KEY_XAI_API = "xai_api_key"
    private const val KEY_DEEPSEEK_API = "deepseek_api_key"
    private const val KEY_TRANSCRIPTION = "transcription_model"
    private const val KEY_TEXT = "text_model"
    private const val KEY_TEXT_PROVIDER = "text_proxy_provider"
    private const val KEY_TEXT_REASONING = "text_reasoning"
    private const val KEY_GROK_CHUNK_MS = "grok_chunk_ms"

    fun apiKey(): String = xaiApiKey()

    fun xaiApiKey(): String = preferences().getString(KEY_XAI_API, "").orEmpty().trim()

    fun setApiKey(value: String) = setXaiApiKey(value)

    fun setXaiApiKey(value: String) {
        preferences().edit().putString(KEY_XAI_API, value.trim()).apply()
    }

    fun deepseekApiKey(): String = preferences().getString(KEY_DEEPSEEK_API, "").orEmpty().trim()

    fun setDeepseekApiKey(value: String) {
        preferences().edit().putString(KEY_DEEPSEEK_API, value.trim()).apply()
    }

    fun isPlausibleXaiKey(value: String = xaiApiKey()): Boolean {
        val key = value.trim()
        return key.length == 84 && key.startsWith("xai-", ignoreCase = true)
    }

    fun isPlausibleDeepseekKey(value: String = deepseekApiKey()): Boolean {
        val key = value.trim()
        return key.length == 35 && key.startsWith("sk-")
    }

    fun hasApiKey(): Boolean = isPlausibleXaiKey()

    fun selectedTranscription(): String = preferences().getString(
        KEY_TRANSCRIPTION,
        TranscriptionModelStore.AVARE_NAME
    ).orEmpty()

    fun selectTranscription(name: String) {
        preferences().edit().putString(KEY_TRANSCRIPTION, name).apply()
    }

    fun isTranscriptionSelected(): Boolean = selectedTranscription() == TRANSCRIPTION_NAME

    fun selectTranscription(selected: Boolean) {
        selectTranscription(if (selected) TRANSCRIPTION_NAME else TranscriptionModelStore.AVARE_NAME)
    }

    fun selectedText(): String {
        val stored = preferences().getString(KEY_TEXT, IA_PROXY_NAME).orEmpty()
        return if (stored == "IA-Proxy") IA_PROXY_NAME else stored
    }

    fun selectText(name: String) {
        preferences().edit().putString(KEY_TEXT, name).apply()
    }

    fun isTextSelected(): Boolean = selectedText() == TEXT_NAME

    fun selectText(selected: Boolean) {
        selectText(if (selected) TEXT_NAME else IA_PROXY_NAME)
    }

    fun textProvider(): String = preferences().getString(KEY_TEXT_PROVIDER, "grok")
        .orEmpty().lowercase().takeIf { it == "grok" || it == "deepseek" } ?: "grok"

    fun setTextProvider(value: String) {
        preferences().edit().putString(KEY_TEXT_PROVIDER, value.lowercase()).apply()
    }

    fun textReasoning(): String = preferences().getString(KEY_TEXT_REASONING, "low")
        .orEmpty().lowercase()

    fun setTextReasoning(value: String) {
        preferences().edit().putString(KEY_TEXT_REASONING, value.lowercase()).apply()
    }

    fun grokChunkMillis(): Int = preferences().getInt(KEY_GROK_CHUNK_MS, 100).coerceIn(20, 2000)

    fun setGrokChunkMillis(value: Int) {
        preferences().edit().putInt(KEY_GROK_CHUNK_MS, value.coerceIn(20, 2000)).apply()
    }

    private fun preferences() = SigApplication.appInstance.getSharedPreferences(
        PREFERENCES,
        Context.MODE_PRIVATE
    )
}
