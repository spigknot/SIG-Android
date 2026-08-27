package br.gov.sp.pcsp.launcher

import android.app.Activity
import android.graphics.Color
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import kotlin.math.roundToInt

object NativeDependencyPrompt {
    fun showIfNeeded(activity: Activity) {
        if (NativeDependencyManager.activateIfInstalled(activity) || activity.isFinishing) return
        val spec = NativeDependencyManager.packageSpec()
        val size = spec?.downloadBytes?.takeIf { it > 0L }?.let(::formatBytes) ?: "aproximadamente 40 MB"

        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (20 * resources.displayMetrics.density).roundToInt()
            setPadding(padding, 0, padding, 0)
        }
        val status = TextView(activity).apply {
            text = "O SIG precisa baixar seus componentes de áudio, vídeo, Whisper, NPU e transcrição local (Granite) ($size).\n\n" +
                "Isso será feito apenas uma vez. Os arquivos continuarão instalados nas próximas atualizações do APK. " +
                "Sem o download, várias ferramentas não funcionarão."
            setTextColor(Color.WHITE)
            textSize = 15f
        }
        val progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 1000
            visibility = android.view.View.GONE
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (18 * resources.displayMetrics.density).roundToInt()
            }
        }
        content.addView(status)
        content.addView(progress)

        val dialog = AlertDialog.Builder(activity)
            .setTitle("Componentes do SIG")
            .setView(content)
            .setNegativeButton("Agora não", null)
            .setPositiveButton("Baixar", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                dialog.setCancelable(false)
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = false
                progress.visibility = android.view.View.VISIBLE
                Thread {
                    val result = NativeDependencyManager.install(activity) { state ->
                        activity.runOnUiThread {
                            status.text = state.stage
                            if (state.total > 0L) {
                                progress.isIndeterminate = false
                                progress.progress = ((state.downloaded.coerceAtMost(state.total) * 1000L) / state.total).toInt()
                            } else {
                                progress.isIndeterminate = true
                            }
                        }
                    }
                    activity.runOnUiThread {
                        result.onSuccess {
                            status.text = "Componentes instalados. O SIG está pronto."
                            progress.progress = 1000
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply {
                                text = "Continuar"
                                isEnabled = true
                                setOnClickListener { dialog.dismiss() }
                            }
                        }.onFailure { error ->
                            status.text = "Não foi possível instalar os componentes:\n${error.message ?: error.javaClass.simpleName}"
                            dialog.setCancelable(true)
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply {
                                text = "Tentar novamente"
                                isEnabled = true
                                setOnClickListener { dialog.dismiss(); showIfNeeded(activity) }
                            }
                            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = true
                        }
                    }
                }.start()
            }
        }
        dialog.show()
    }

    private fun formatBytes(bytes: Long): String {
        val mib = bytes.toDouble() / (1024.0 * 1024.0)
        return String.format(java.util.Locale.US, "%.1f MB", mib)
    }
}
