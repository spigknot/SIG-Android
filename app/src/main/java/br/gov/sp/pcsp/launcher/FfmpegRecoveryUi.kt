package br.gov.sp.pcsp.launcher

import android.app.Activity
import android.app.AlertDialog
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import org.json.JSONObject
import java.io.File

/** Integra a retomada à tela sem modificar as opções escolhidas para a tarefa original. */
class FfmpegRecoveryUi(private val activity: Activity, private val tool: String,
    private val snapshot: () -> JSONObject, private val restore: (JSONObject) -> Unit, private val restart: () -> Unit) {
    @Volatile var job: FfmpegRecoveryStore? = null
        private set
    private var resuming = false
    @Volatile private var active = false
    @Volatile private var cancellationRequested = false
    val isCancelled get() = cancellationRequested

    fun offer(): Boolean {
        val pending = FfmpegRecoveryStore.pending(activity.cacheDir,tool) ?: return false
        AlertDialog.Builder(activity).setTitle("Retomar processamento")
            .setMessage("Há uma tarefa preservada. As etapas concluídas serão reutilizadas; somente a etapa interrompida será refeita.")
            .setNegativeButton("Agora não",null).setPositiveButton("Retomar") { _,_ ->
                try {
                    job=pending;resuming=true
                    restore(pending.request)
                    restoreWidgets(pending.request.getJSONObject("widgets"))
                    restart()
                    if(resuming) { resuming=false;job=null }
                } catch(error: Exception) {
                    resuming=false;job=null
                    AlertDialog.Builder(activity).setTitle("Retomada indisponível").setMessage(error.message).setPositiveButton("OK",null).show()
                }
            }.show()
        return true
    }

    fun begin() {
        cancellationRequested=false
        if (resuming) { resuming=false;active=true;job?.finish("running");return }
        if(job?.state?.optString("status")=="ready")job?.finish("superseded")
        val request=snapshot().put("widgets",widgets())
        job=FfmpegRecoveryStore.create(activity.cacheDir,tool,request)
        active=true
    }
    fun cancel() { cancellationRequested=true }
    fun checkCancellation() { if(cancellationRequested || Thread.currentThread().isInterrupted)throw InterruptedException("Operação cancelada.") }
    fun finish(success: Boolean, cancelled: Boolean=false) { job?.finish(if(cancelled || cancellationRequested) "cancelled" else if(success) "ready" else "failed");active=false }
    fun saved() { job?.finish("saved") }
    fun alias(uri: Uri,input: File) { job?.alias(uri.toString(),input) }
    fun uri(original: String): Uri = Uri.parse(job?.inputPath(original)?.let { Uri.fromFile(File(it)).toString() } ?: original)
    fun file(key: String,suffix: String, fallback: () -> File): File = (if(active)job?.file(key,suffix) else null) ?: fallback()
    fun work(key: String,fallback: () -> File): File = file("work:$key","",fallback).apply { mkdirs() }
    fun delete(file: File?) { if(file!=null && job?.owns(file)!=true) { if(file.isDirectory)file.deleteRecursively() else file.delete() } }
    fun input(uri: Uri,name: String,copy: (File) -> Unit): File? = if(active)job?.input(uri.toString(), "."+name.substringAfterLast('.',"bin"),copy) else null
    fun reuse(arguments: Array<String>): FFmpegSession? { if(active)checkCancellation();return if(active && job?.reusable(arguments)==true) FFmpegKit.executeWithArguments(arrayOf("-version")) else null }
    fun starting(arguments: Array<String>) { if(active)job?.begin(arguments) }
    fun completed(arguments: Array<String>, session: FFmpegSession) { if(active)checkCancellation();if(active && ReturnCode.isSuccess(session.returnCode))job?.complete(arguments) }

    private fun visit(view: View, action: (View) -> Unit) {
        action(view)
        if(view is ViewGroup)for(index in 0 until view.childCount)visit(view.getChildAt(index),action)
    }
    private fun widgets(): JSONObject = JSONObject().also { result -> visit(activity.window.decorView) { view ->
        val name=runCatching { activity.resources.getResourceEntryName(view.id) }.getOrNull()
        if(name!=null)when(view) {
            is EditText -> result.put(name,JSONObject().put("text",view.text.toString()))
            is CheckBox -> result.put(name,JSONObject().put("checked",view.isChecked))
        }
    } }
    private fun restoreWidgets(values: JSONObject) { visit(activity.window.decorView) { view ->
        val name=runCatching { activity.resources.getResourceEntryName(view.id) }.getOrNull()
        val record=name?.let { values.optJSONObject(it) }
        if(record!=null)when(view) { is EditText -> view.setText(record.getString("text")); is CheckBox -> view.isChecked=record.getBoolean("checked") }
    } }
    companion object {
        fun encoder(value: FfmpegVideoEncoder?): JSONObject? = value?.let {
            JSONObject().put("ffmpeg",it.ffmpegName).put("family",it.codecFamily).put("display",it.displayName).put("codec",it.codecName)
        }
        fun encoder(value: JSONObject?): FfmpegVideoEncoder? = value?.let {
            FfmpegVideoEncoder(it.getString("ffmpeg"),it.getString("family"),it.getString("display"),it.optString("codec").takeIf { name -> name.isNotBlank() })
        }
        fun selection(value: FfmpegPreviewSelection.Selection?): JSONObject? = value?.let {
            JSONObject().put("left",it.left).put("top",it.top).put("right",it.right).put("bottom",it.bottom)
        }
        fun selection(value: JSONObject?): FfmpegPreviewSelection.Selection? = value?.let {
            FfmpegPreviewSelection.Selection(it.getDouble("left"),it.getDouble("top"),it.getDouble("right"),it.getDouble("bottom"))
        }
    }
}
