package br.gov.sp.pcsp.launcher

import android.app.Activity
import android.content.Intent
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.MotionEvent
import android.widget.ImageButton
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Regressões reais de busca, layout e ciclo nativo; usa apenas vídeo sintético no cache. */
@RunWith(AndroidJUnit4::class)
class FfmpegPreviewPerformanceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun fixture(): File = File(context.cacheDir, "ffmpeg-preview-audit.mp4").also {
        check(it.isFile) { "Copie o vídeo sintético para cache/ffmpeg-preview-audit.mp4 antes da instrumentação." }
    }

    private fun launch(type: Class<out Activity>, multiple: Boolean = false): Activity {
        val file = fixture()
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(context, type).setAction(Intent.ACTION_SEND).setType("video/mp4")
            .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (multiple) {
            val second = File(context.cacheDir, "ffmpeg-preview-audit-2.mp4")
            file.copyTo(second, overwrite = true)
            intent.action = Intent.ACTION_SEND_MULTIPLE
            intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(uri,
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", second)))
        }
        return instrumentation.startActivitySync(intent)
    }

    private fun ui(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun field(activity: Activity, name: String): Any? =
        activity.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(activity)
    private fun invoke(activity: Activity, name: String, position: Long? = null) {
        if (position == null) activity.javaClass.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity)
        else activity.javaClass.getDeclaredMethod(name, Long::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, position)
    }
    private fun await(message: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            var ready = false
            ui { ready = predicate() }
            if (ready) return
            Thread.sleep(40L)
        }
        fail(message)
    }

    @Test fun corteBuscaDestinoFinalERetoma() = verifySingle(FfmpegCutActivity::class.java, "togglePreviewPlayback")
    @Test fun extrairBuscaDestinoFinalERetoma() = verifySingle(FfmpegExtractAudioActivity::class.java, "togglePreviewPlayback")
    @Test fun girarBuscaDestinoFinalSemRefazerLayout() = verifySingle(FfmpegRotateVideoActivity::class.java, "togglePlayback")

    private fun verifySingle(type: Class<out Activity>, toggle: String) {
        val activity = launch(type)
        try {
            await("Player não ficou pronto") { (field(activity, "previewSeeker") as FfmpegPreviewSeeker).isReady }
            ui {
                val timeline = activity.findViewById<FfmpegRangeSlider>(R.id.timeline)
                val started = SystemClock.elapsedRealtime()
                repeat(300) { timeline.setCurrent(2000L + it * 10L, fromUser = true) }
                timeline.setCurrent(4700L, fromUser = true)
                val elapsed = SystemClock.elapsedRealtime() - started
                Log.i("FfmpegPreviewAudit", "${type.simpleName}: 301 pedidos em ${elapsed}ms")
                assertTrue("Arraste bloqueou UI por ${elapsed}ms", elapsed < 1000L)
            }
            await("Busca não chegou a 4,7s") {
                val seeker = field(activity, "previewSeeker") as FfmpegPreviewSeeker
                seeker.isReady && !seeker.isSeeking &&
                    kotlin.math.abs((field(activity, "previewPlayer") as MediaPlayer).currentPosition - 4700) < 250
            }
            if (type == FfmpegRotateVideoActivity::class.java) {
                instrumentation.waitForIdleSync()
                ui {
                    val frame = activity.findViewById<View>(R.id.preview_frame)
                    val overlay = field(activity, "previewOverlay") as FfmpegPreviewOverlayView
                    assertFalse(frame.isLayoutRequested)
                    repeat(100) { overlay.onViewportChanged?.invoke() }
                    assertFalse("Zoom pediu novo layout sem mudar altura", frame.isLayoutRequested)
                }
            }
            ui { invoke(activity, toggle) }
            await("Play não retomou após busca") { (field(activity, "previewPlayer") as MediaPlayer).currentPosition > 5050 }
            ui {
                invoke(activity, toggle)
                activity.findViewById<ImageButton>(R.id.button_speed_up).performClick()
                assertFalse("Velocidade iniciou player pausado", (field(activity, "previewPlayer") as MediaPlayer).isPlaying)
            }
        } finally { ui { activity.finish() } }
    }

    @Test fun juntarAgrupaBuscasEntreClipesEMantemMiniaturasPequenas() {
        val activity = launch(FfmpegJoinVideosActivity::class.java, multiple = true)
        try {
            await("Clipes não carregaram") { (field(activity, "clips") as List<*>).size == 2 }
            ui {
                for (clip in field(activity, "clips") as List<*>) {
                    val thumbnail = clip!!.javaClass.getDeclaredField("thumbnail").apply { isAccessible = true }.get(clip) as android.graphics.Bitmap
                    assertTrue(thumbnail.width <= 320 && thumbnail.height <= 180)
                }
                val started = SystemClock.elapsedRealtime()
                repeat(300) { invoke(activity, "seekJoinPlayback", if (it % 2 == 0) 1000L else 16000L) }
                val elapsed = SystemClock.elapsedRealtime() - started
                Log.i("FfmpegPreviewAudit", "Juntar: 300 pedidos entre clipes em ${elapsed}ms")
                assertTrue(elapsed < 1000L)
            }
            await("Juntar não buscou o segundo clipe") {
                field(activity, "joinPreviewClipIndex") == 1 && field(activity, "joinPreviewPrepared") == true &&
                    !(field(activity, "joinSeeker") as FfmpegPreviewSeeker).isSeeking &&
                    kotlin.math.abs((field(activity, "joinPreviewPlayer") as MediaPlayer).currentPosition - 4000) < 250
            }
            ui { invoke(activity, "toggleJoinPlayback") }
            await("Juntar não retomou") { (field(activity, "joinPreviewPlayer") as MediaPlayer).currentPosition > 4400 }
        } finally { ui { activity.finish() }; File(context.cacheDir, "ffmpeg-preview-audit-2.mp4").delete() }
    }

    @Test fun leituraBloqueadaNaoBloqueiaDescarteNaUi() {
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val errorCalled = AtomicBoolean(false)
        lateinit var source: FfmpegPreviewSource
        lateinit var player: MediaPlayer
        ui {
            source = FfmpegPreviewSource(Handler(Looper.getMainLooper()))
            player = MediaPlayer()
            source.prepare(player, { entered.countDown(); check(resume.await(10, TimeUnit.SECONDS)); error("fonte descartada") }) {
                errorCalled.set(true)
            }
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            ui {
                val started = SystemClock.elapsedRealtime()
                source.release(player)
                source.close()
                assertTrue("Descarte esperou leitura nativa", SystemClock.elapsedRealtime() - started < 100L)
            }
        } finally { resume.countDown() }
        instrumentation.waitForIdleSync()
        assertFalse(errorCalled.get())
    }

    @Test fun inserirAudioBuscaERetomaSemGerarOutraPrevia() {
        val audio = File(context.cacheDir, "ffmpeg-preview-audit.wav")
        check(audio.isFile)
        val activity = instrumentation.startActivitySync(Intent(context, FfmpegInsertAudioActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", audio)
            ui {
                activity.javaClass.getDeclaredMethod("loadAudio", android.net.Uri::class.java,
                    Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType).apply { isAccessible = true }
                    .invoke(activity, uri, true, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            await("Áudio não carregou") { field(activity, "mainAudio") != null }
            ui { invoke(activity, "togglePlayback") }
            await("Prévia não ficou pronta") { field(activity, "filteredPreviewReady") == true }
            lateinit var original: MediaPlayer
            ui {
                original = field(activity, "filteredPreviewPlayer") as MediaPlayer
                invoke(activity, "togglePlayback")
                val started = SystemClock.elapsedRealtime()
                repeat(300) { invoke(activity, "seekComposite", 2000L + it * 10L) }
                invoke(activity, "seekComposite", 4700L)
                val elapsed = SystemClock.elapsedRealtime() - started
                Log.i("FfmpegPreviewAudit", "Inserir: 301 pedidos em ${elapsed}ms")
                assertTrue(elapsed < 1000L)
            }
            await("Inserir não chegou ao destino") {
                !(field(activity, "previewSeeker") as FfmpegPreviewSeeker).isSeeking &&
                    kotlin.math.abs(original.currentPosition - 4700) < 250
            }
            ui { invoke(activity, "togglePlayback") }
            await("Inserir não retomou") { original.currentPosition > 5050 }
            ui { assertSame(original, field(activity, "filteredPreviewPlayer")) }
        } finally { ui { activity.finish() } }
    }

    @Test fun resultadoDaJuncaoBuscaERetoma() {
        val activity = launch(FfmpegJoinVideosActivity::class.java)
        try {
            await("Clipe não carregou") { (field(activity, "clips") as List<*>).size == 1 }
            ui {
                activity.javaClass.getDeclaredMethod("showJoinedPreview", File::class.java)
                    .apply { isAccessible = true }.invoke(activity, fixture())
            }
            await("Resultado não ficou pronto") { (field(activity, "resultSeeker") as FfmpegPreviewSeeker).isReady }
            ui {
                val timeline = field(activity, "resultTimeline") as FfmpegRangeSlider
                repeat(300) { timeline.setCurrent(2000L + it * 10L, fromUser = true) }
                timeline.setCurrent(4700L, fromUser = true)
            }
            await("Resultado não buscou destino") {
                !(field(activity, "resultSeeker") as FfmpegPreviewSeeker).isSeeking &&
                    kotlin.math.abs((field(activity, "resultPreviewPlayer") as MediaPlayer).currentPosition - 4700) < 250
            }
            ui { invoke(activity, "toggleResultPlayback") }
            await("Resultado não retomou") { (field(activity, "resultPreviewPlayer") as MediaPlayer).currentPosition > 5050 }
        } finally { ui { activity.finish() } }
    }

    @Test fun giroEPincaMantemVideoEmReproducao() {
        val activity = launch(FfmpegRotateVideoActivity::class.java)
        try {
            await("Vídeo não ficou pronto") { (field(activity, "previewSeeker") as FfmpegPreviewSeeker).isReady }
            ui { invoke(activity, "togglePlayback") }
            await("Vídeo não iniciou") { (field(activity, "previewPlayer") as MediaPlayer).isPlaying }
            ui { activity.findViewById<View>(R.id.rotate_90).performClick() }
            instrumentation.waitForIdleSync()
            var initial = 0
            val down = SystemClock.uptimeMillis()
            ui {
                initial = (field(activity, "previewPlayer") as MediaPlayer).currentPosition
                pinch(activity, down, MotionEvent.ACTION_DOWN, 40f)
                pinch(activity, down, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 40f)
            }
            repeat(25) { step ->
                ui { pinch(activity, down, MotionEvent.ACTION_MOVE, 40f + step * 3f) }
                Thread.sleep(20L)
            }
            ui {
                pinch(activity, down, MotionEvent.ACTION_UP, 112f)
                val overlay = field(activity, "previewOverlay") as FfmpegPreviewOverlayView
                assertTrue("Pinça não ampliou", overlay.viewportZoom() > 1.2)
                val player = field(activity, "previewPlayer") as MediaPlayer
                assertTrue(player.isPlaying)
                assertTrue("Vídeo parou durante giro/pinça", player.currentPosition - initial > 300)
            }
        } finally { ui { activity.finish() } }
    }

    private fun pinch(activity: Activity, down: Long, action: Int, distance: Float) {
        val overlay = field(activity, "previewOverlay") as FfmpegPreviewOverlayView
        val points = Array(2) { index -> MotionEvent.PointerProperties().apply {
            id = index; toolType = MotionEvent.TOOL_TYPE_FINGER
        } }
        val coords = Array(2) { index -> MotionEvent.PointerCoords().apply {
            x = overlay.width / 2f + if (index == 0) -distance else distance
            y = overlay.height / 2f; pressure = 1f; size = 1f
        } }
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, 2, points, coords,
            0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
        try { overlay.onTouchEvent(event) } finally { event.recycle() }
    }
}
