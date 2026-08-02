package br.gov.sp.pcsp.launcher

import android.content.Context

object ConversionParallelismSettings {
    private const val PREFS = "conversion_parallelism_settings"
    private const val KEY = "parallel_conversions"
    fun options(): List<Int> = (1..(Runtime.getRuntime().availableProcessors().coerceAtLeast(1) * 4)).toList()
    fun selected(context: Context): Int {
        val values = options()
        val defaultValue = (Runtime.getRuntime().availableProcessors().coerceAtLeast(1) * 2).coerceAtMost(values.last())
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY, defaultValue)
            .coerceIn(values.first(), values.last())
    }
    fun select(context: Context, value: Int) {
        val values = options()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY, value.coerceIn(values.first(), values.last())).apply()
    }
}
