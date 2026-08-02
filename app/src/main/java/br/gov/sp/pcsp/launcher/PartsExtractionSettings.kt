package br.gov.sp.pcsp.launcher

import android.content.Context

object PartsExtractionSettings {

    const val MODEL_PROXY = "IA-Proxy (grok-4.5)"
    const val MODEL_PROXY_DEEPSEEK = "IA-Proxy (deepseek-v4-pro)"
    const val MODEL_GROK = "grok-4.5"
    const val MODEL_DEEPSEEK = "deepseek-v4-pro"

    enum class Method(val storedValue: String) {
        UPPERCASE("uppercase"),
        NAME_DATABASE("name_database"),
        AI("ai")
    }

    fun selectedMethod(context: Context): Method {
        val value = preferences(context).getString(KEY_METHOD, Method.UPPERCASE.storedValue)
        return Method.entries.firstOrNull { it.storedValue == value } ?: Method.UPPERCASE
    }

    fun select(context: Context, method: Method) {
        preferences(context)
            .edit()
            .putString(KEY_METHOD, method.storedValue)
            .apply()
    }

    fun selectedModel(context: Context): String = preferences(context)
        .getString(KEY_MODEL, MODEL_PROXY).orEmpty()

    fun selectModel(context: Context, model: String) {
        preferences(context).edit().putString(KEY_MODEL, model).apply()
    }

    fun selectedConfig(context: Context): ModelServerStore.Config =
        ModelServerStore.configForParts(
            selectedModel(context),
            if (selectedModel(context) == MODEL_PROXY_DEEPSEEK) "deepseek" else "grok"
        )

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private const val PREFERENCES_NAME = "parts_extraction_settings"
    private const val KEY_METHOD = "method"
    private const val KEY_MODEL = "ai_model"
}
