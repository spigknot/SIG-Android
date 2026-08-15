package br.gov.sp.pcsp.launcher

import android.content.Context

/** Preferências privadas das integrações de API e dos modelos internos. */
object GrokApiSettings {

    const val TRANSCRIPTION_NAME = "Grok STT"
    const val DEEPGRAM_TRANSCRIPTION_NAME = "Deepgram Nova 3"
    const val TEXT_NAME = "grok-4.6"
    const val GROK_NON_REASONING_TEXT_NAME = "grok-4.20-0309-non-reasoning"
    const val DEEPSEEK_TEXT_NAME = "deepseek-v4-flash"
    const val IA_PROXY_NAME = "IA-Proxy (grok-4.6)"
    const val IA_PROXY_DEEPSEEK_NAME = "IA-Proxy (deepseek-v4-flash)"

    private const val PREFERENCES = "model_api_settings"
    private const val KEY_XAI_API = "xai_api_key"
    private const val KEY_DEEPSEEK_API = "deepseek_api_key"
    private const val KEY_DEEPGRAM_API = "deepgram_api_key"
    private const val KEY_DEEPGRAM_KEYTERMS = "deepgram_keyterms"
    private const val KEY_TRANSCRIPTION = "transcription_model"
    private const val KEY_TEXT = "text_model"
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

    fun deepgramApiKey(): String = preferences().getString(KEY_DEEPGRAM_API, "").orEmpty().trim()

    fun setDeepgramApiKey(value: String) {
        preferences().edit().putString(KEY_DEEPGRAM_API, value.trim()).apply()
    }

    fun isPlausibleDeepgramKey(value: String = deepgramApiKey()): Boolean {
        val key = value.trim()
        return key.length == 40 && key.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
    }

    fun hasDeepgramApiKey(): Boolean = isPlausibleDeepgramKey()

    fun deepgramKeyterms(): String = preferences().getString(KEY_DEEPGRAM_KEYTERMS, "").orEmpty().trim()

    fun setDeepgramKeyterms(value: String) {
        preferences().edit().putString(KEY_DEEPGRAM_KEYTERMS, value.trim()).apply()
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
        return when (stored) {
            "IA-Proxy" -> IA_PROXY_NAME
            "grok-4.20-non-reasoning" -> GROK_NON_REASONING_TEXT_NAME
            "deepseek-v4-pro" -> DEEPSEEK_TEXT_NAME
            "IA-Proxy (deepseek-v4-pro)" -> IA_PROXY_DEEPSEEK_NAME
            else -> stored
        }.also { migrated ->
            if (migrated != stored) selectText(migrated)
        }
    }

    fun selectText(name: String) {
        preferences().edit().putString(KEY_TEXT, name).apply()
    }

    fun isTextSelected(): Boolean = selectedText() == TEXT_NAME

    fun selectText(selected: Boolean) {
        selectText(if (selected) TEXT_NAME else IA_PROXY_NAME)
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
