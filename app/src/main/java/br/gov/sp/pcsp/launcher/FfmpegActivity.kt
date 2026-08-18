package br.gov.sp.pcsp.launcher

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlin.concurrent.thread

class FfmpegActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        keepContentInsideSystemBars()
        setContentView(R.layout.activity_ffmpeg)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.button_cut_media).setOnClickListener { openWithNativeDeps(FfmpegCutActivity::class.java) }
        findViewById<View>(R.id.button_extract_audio).setOnClickListener { openWithNativeDeps(FfmpegExtractAudioActivity::class.java) }
        findViewById<View>(R.id.button_rotate_video).setOnClickListener { openWithNativeDeps(FfmpegRotateVideoActivity::class.java) }
        findViewById<View>(R.id.button_join_videos).setOnClickListener { openWithNativeDeps(FfmpegJoinVideosActivity::class.java) }
        findViewById<View>(R.id.button_clean_audio).setOnClickListener { openWithNativeDeps(FfmpegCleanAudioActivity::class.java) }
        findViewById<View>(R.id.button_insert_audio).setOnClickListener { openWithNativeDeps(FfmpegInsertAudioActivity::class.java) }
    }

    /** Abre a ferramenta, baixando o pacote nativo na primeira execução
     * (o APK não embute o ffmpeg desde o F3). Depois da primeira vez, é
     * instantâneo. */
    private fun openWithNativeDeps(target: Class<*>) {
        if (NativeDependencyManager.isInstalled(this)) {
            startActivity(Intent(this, target))
            return
        }
        val progressView = layoutInflater.inflate(R.layout.dialog_native_download, null)
        val statusText = progressView.findViewById<TextView>(R.id.nativeStatusText)
        val progressBar = progressView.findViewById<ProgressBar>(R.id.nativeProgressBar)
        val dialog = AlertDialog.Builder(this)
            .setTitle("Preparando ferramentas de vídeo/áudio")
            .setMessage("Baixando os componentes nativos (primeira execução — leva alguns minutos)...")
            .setView(progressView)
            .setCancelable(false)
            .create()
        dialog.show()
        thread {
            val result = NativeDependencyManager.install(this) { progress ->
                runOnUiThread {
                    statusText.text = if (progress.total > 0) {
                        "${progress.stage} · ${progress.downloaded * 100 / progress.total}%"
                    } else {
                        progress.stage
                    }
                    progressBar.max = progress.total.toInt().coerceAtLeast(1)
                    progressBar.progress = progress.downloaded.toInt()
                }
            }
            runOnUiThread {
                dialog.dismiss()
                result.fold(
                    onSuccess = {
                        NativeDependencyManager.activateIfInstalled(this)
                        startActivity(Intent(this, target))
                    },
                    onFailure = { error ->
                        AlertDialog.Builder(this)
                            .setTitle("Falha ao baixar os componentes nativos")
                            .setMessage(error.message ?: error.javaClass.simpleName)
                            .setPositiveButton("Tentar novamente") { _, _ -> openWithNativeDeps(target) }
                            .setNegativeButton("Cancelar", null)
                            .show()
                    }
                )
            }
        }
    }
}
