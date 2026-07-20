package br.gov.sp.pcsp.launcher

import android.content.Context

object PartsExtractionSettings {

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

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private const val PREFERENCES_NAME = "parts_extraction_settings"
    private const val KEY_METHOD = "method"
}
