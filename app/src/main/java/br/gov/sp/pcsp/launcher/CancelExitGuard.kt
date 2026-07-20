package br.gov.sp.pcsp.launcher

import androidx.activity.addCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

fun AppCompatActivity.installCancelAndExitGuard(
    isTaskRunning: () -> Boolean,
    cancelTask: () -> Unit
): () -> Unit {
    val handler: () -> Unit = {
        if (isTaskRunning()) {
            AlertDialog.Builder(this)
                .setMessage("Cancelar e sair?")
                .setNegativeButton("Não", null)
                .setPositiveButton("Sim") { _, _ ->
                    cancelTask()
                    finish()
                }
                .show()
        } else {
            finish()
        }
    }
    onBackPressedDispatcher.addCallback(this) { handler() }
    return handler
}
