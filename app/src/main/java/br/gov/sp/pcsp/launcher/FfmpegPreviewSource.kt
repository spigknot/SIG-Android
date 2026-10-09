package br.gov.sp.pcsp.launcher

import android.media.MediaPlayer
import android.os.Handler
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Abre e libera fontes da prévia fora da UI, serializando o ciclo nativo de cada tela. */
internal class FfmpegPreviewSource(private val handler: Handler) {
    private val worker = Executors.newSingleThreadExecutor()
    private val sources = ConcurrentHashMap<MediaPlayer, AtomicBoolean>()

    fun prepare(player: MediaPlayer, dataSource: () -> Unit, onError: (Exception) -> Unit) {
        val cancelled = AtomicBoolean(false)
        sources[player] = cancelled
        worker.execute {
            if (cancelled.get()) return@execute
            try {
                dataSource()
                if (!cancelled.get()) player.prepareAsync()
            } catch (error: Exception) {
                handler.post { if (!cancelled.get()) onError(error) }
            }
        }
    }

    fun release(player: MediaPlayer?) {
        if (player == null) return
        val cancelled = sources.remove(player) ?: return
        cancelled.set(true)
        worker.execute {
            player.release()
        }
    }

    fun detachSurface(player: MediaPlayer?) {
        if (player == null) return
        worker.execute { if (sources.containsKey(player)) runCatching { player.setSurface(null) } }
    }

    fun close() { worker.shutdown() }
}
