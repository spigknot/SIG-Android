package br.gov.sp.pcsp.launcher

import android.content.Context

object ImeiApiSettings {
    private const val PREFERENCES = "imei_api_settings"
    private const val KEY_API = "imei_check_api_key"

    fun apiKey(): String {
        val preferences = preferences()
        return if (preferences.contains(KEY_API)) {
            preferences.getString(KEY_API, "").orEmpty().trim()
        } else {
            BuildConfig.IMEICHECK_API_KEY.trim()
        }
    }

    fun setApiKey(value: String) {
        preferences().edit().putString(KEY_API, value.trim()).apply()
    }

    private fun preferences() = SigApplication.appInstance.getSharedPreferences(
        PREFERENCES,
        Context.MODE_PRIVATE
    )
}
