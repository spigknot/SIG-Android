package br.gov.sp.pcsp.launcher

import android.content.Context

/** Private settings for the direct xAI integration; these values never enter the editable server files. */
object GrokApiSettings {

    const val TRANSCRIPTION_NAME = "Grok (API)"
    const val TEXT_NAME = "Grok (API)"

    private const val PREFERENCES = "grok_api_settings"
    private const val KEY_API = "api_key"
    private const val KEY_TRANSCRIPTION_SELECTED = "transcription_selected"
    private const val KEY_TEXT_SELECTED = "text_selected"

    fun apiKey(): String = preferences().getString(KEY_API, "").orEmpty().trim()

    fun setApiKey(value: String) {
        preferences().edit().putString(KEY_API, value.trim()).apply()
    }

    fun hasApiKey(): Boolean = apiKey().isNotBlank()

    fun isTranscriptionSelected(): Boolean = preferences().getBoolean(KEY_TRANSCRIPTION_SELECTED, false)

    fun selectTranscription(selected: Boolean) {
        preferences().edit().putBoolean(KEY_TRANSCRIPTION_SELECTED, selected).apply()
    }

    fun isTextSelected(): Boolean = preferences().getBoolean(KEY_TEXT_SELECTED, false)

    fun selectText(selected: Boolean) {
        preferences().edit().putBoolean(KEY_TEXT_SELECTED, selected).apply()
    }

    private fun preferences() = SigApplication.appInstance.getSharedPreferences(
        PREFERENCES,
        Context.MODE_PRIVATE
    )
}
