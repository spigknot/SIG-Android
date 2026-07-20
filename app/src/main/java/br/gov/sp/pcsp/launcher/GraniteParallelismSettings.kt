package br.gov.sp.pcsp.launcher

import android.content.Context

object GraniteParallelismSettings {
    private const val PREFERENCES_NAME = "granite_parallelism_settings"
    private const val KEY_REQUESTS = "parallel_requests"
    const val DEFAULT_REQUESTS = 2
    val OPTIONS = listOf(1, 2, 4, 8)

    fun selectedRequests(context: Context): Int {
        val stored = context
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_REQUESTS, DEFAULT_REQUESTS)
        return stored.coerceIn(1, 8)
    }

    fun select(context: Context, value: Int) {
        val normalized = value.coerceIn(1, 8)
        context
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_REQUESTS, normalized)
            .apply()
    }
}
