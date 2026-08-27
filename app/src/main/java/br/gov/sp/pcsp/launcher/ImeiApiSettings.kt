package br.gov.sp.pcsp.launcher

import android.content.Context

object ImeiApiSettings {
    private const val PREFERENCES = "imei_api_settings"
    private const val KEY_API = "imei_check_api_key"

    fun apiKey(): String {
        return ApiKeyStore.get(preferences(), KEY_API)
    }

    fun setApiKey(value: String) {
        ApiKeyStore.put(preferences(), KEY_API, value)
    }

    private fun preferences() = SigApplication.appInstance.getSharedPreferences(
        PREFERENCES,
        Context.MODE_PRIVATE
    )
}
