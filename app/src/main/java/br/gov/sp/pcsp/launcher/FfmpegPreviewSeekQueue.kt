package br.gov.sp.pcsp.launcher

/** Retém o destino mais recente e permite apenas uma busca nativa em andamento. */
internal class FfmpegPreviewSeekQueue(private val debounceMs: Long = 120L) {
    private var pending: Long? = null
    private var dueMs = 0L
    private var active = false
    val isSeeking: Boolean get() = active || pending != null

    fun request(positionMs: Long, nowMs: Long, immediate: Boolean = false) {
        pending = positionMs.coerceAtLeast(0L)
        dueMs = nowMs + if (immediate) 0L else debounceMs
    }

    fun delayMs(nowMs: Long): Long? =
        if (active || pending == null) null else (dueMs - nowMs).coerceAtLeast(0L)

    fun take(nowMs: Long): Long? {
        if (delayMs(nowMs) != 0L) return null
        val position = pending ?: return null
        pending = null
        active = true
        return position
    }

    fun complete() { active = false }
    fun cancelPending() { pending = null }
    fun reset() { pending = null; active = false }
}
