package br.gov.sp.pcsp.launcher

import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.util.Log

/** Integra buscas precisas agrupadas ao MediaPlayer, respeitando preparo e descarte. */
internal class FfmpegPreviewSeeker(
    private val handler: Handler,
    private val onSettled: () -> Unit = {}
) {
    private val queue = FfmpegPreviewSeekQueue()
    private var player: MediaPlayer? = null
    val isReady: Boolean get() = player != null
    val isSeeking: Boolean get() = queue.isSeeking
    private val dispatch = Runnable { dispatchSeek() }

    fun attach(prepared: MediaPlayer) {
        reset()
        player = prepared
        prepared.setOnSeekCompleteListener {
            if (player === it) {
                queue.complete()
                schedule()
                if (!queue.isSeeking) onSettled()
            }
        }
    }

    fun request(positionMs: Long, immediate: Boolean = false) {
        if (player == null) return
        queue.request(positionMs, SystemClock.elapsedRealtime(), immediate)
        schedule()
    }

    fun cancelPending() {
        handler.removeCallbacks(dispatch)
        queue.cancelPending()
    }

    fun reset() {
        handler.removeCallbacks(dispatch)
        player = null
        queue.reset()
    }

    private fun schedule() {
        handler.removeCallbacks(dispatch)
        queue.delayMs(SystemClock.elapsedRealtime())?.let { delay ->
            if (delay == 0L) dispatchSeek() else handler.postDelayed(dispatch, delay)
        }
    }

    private fun dispatchSeek() {
        val current = player ?: return
        val position = queue.take(SystemClock.elapsedRealtime()) ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                current.seekTo(position, MediaPlayer.SEEK_CLOSEST)
            } else {
                current.seekTo(position.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            }
        } catch (error: IllegalStateException) {
            Log.w("FfmpegPreviewSeeker", "Player indisponível para busca", error)
            reset()
        }
    }
}
