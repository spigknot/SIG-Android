package br.gov.sp.pcsp.launcher

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Verificação silenciosa de atualização do APK no GitHub.
 *
 * Ao abrir o app (MainActivity), consulta a última release de
 * `spigknot/SIG-Android`. Se houver versão nova, mostra um diálogo com
 * "Atualizar" / "Agora não". Sem conexão, sem release ou sem versão nova:
 * nada acontece.
 *
 * Numeração igual ao SIG Windows: `YYYYMMDD_NNN` (comparação normalizada
 * ignora `_`/`-`, então tags antigas como `20260817-002` continuam válidas).
 */
object AppUpdateChecker {

    /** Versão deste APK — atualizar junto com a release (formato YYYYMMDD_NNN). */
    const val APP_VERSION = "20260823_001"

    private const val RELEASES_URL = "https://api.github.com/repos/spigknot/SIG-Android/releases/latest"
    private const val APK_ASSET_NAME = "sig.apk"

    fun check(activity: Activity) {
        Thread {
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(8, TimeUnit.SECONDS)
                    .readTimeout(8, TimeUnit.SECONDS)
                    .build()
                val request = Request.Builder()
                    .url(RELEASES_URL)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "SIG-Android")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val payload = response.body?.string() ?: return@use
                    val json = JSONObject(payload)
                    val tag = json.optString("tag_name", "")
                    if (normalize(tag).isEmpty() || normalize(tag) <= normalize(APP_VERSION)) return@use
                    val assets = json.optJSONArray("assets") ?: return@use
                    var apkUrl: String? = null
                    var apkSize = 0L
                    for (index in 0 until assets.length()) {
                        val asset = assets.optJSONObject(index) ?: continue
                        if (asset.optString("name") == APK_ASSET_NAME) {
                            apkUrl = asset.optString("browser_download_url")
                            apkSize = asset.optLong("size")
                            break
                        }
                    }
                    val url = apkUrl ?: return@use
                    activity.runOnUiThread { showUpdateDialog(activity, tag, url, apkSize) }
                }
            } catch (_: Exception) {
                // Sem conexão ou erro transitório: silencioso.
            }
        }.start()
    }

    private fun normalize(version: String): String =
        version.replace("-", "").replace("_", "").trim()

    private fun showUpdateDialog(activity: Activity, tag: String, apkUrl: String, apkSize: Long) {
        if (activity.isFinishing) return
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (20 * resources.displayMetrics.density).roundToInt()
            setPadding(padding, 0, padding, 0)
        }
        val status = TextView(activity).apply {
            text = "Uma nova versão do SIG está disponível (${tag}).\n\nBaixar e instalar agora?"
            setTextColor(Color.WHITE)
            textSize = 15f
        }
        val progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 1000
            visibility = android.view.View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (18 * resources.displayMetrics.density).roundToInt()
            }
        }
        content.addView(status)
        content.addView(progress)

        val dialog = AlertDialog.Builder(activity)
            .setTitle("Atualização disponível")
            .setView(content)
            .setNegativeButton("Agora não", null)
            .setPositiveButton("Atualizar", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                dialog.setCancelable(false)
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = false
                progress.visibility = android.view.View.VISIBLE
                status.text = "Baixando atualização..."
                Thread {
                    try {
                        val file = downloadApk(activity, apkUrl) { downloaded, total ->
                            activity.runOnUiThread {
                                if (total > 0L) {
                                    progress.isIndeterminate = false
                                    progress.progress = ((downloaded.coerceAtMost(total) * 1000L) / total).toInt()
                                } else {
                                    progress.isIndeterminate = true
                                }
                            }
                        }
                        activity.runOnUiThread {
                            status.text = "Download concluído. Instalando..."
                            dialog.dismiss()
                            installApk(activity, file)
                        }
                    } catch (error: Exception) {
                        activity.runOnUiThread {
                            status.text = "Não foi possível baixar a atualização:\n${error.message ?: error.javaClass.simpleName}"
                            dialog.setCancelable(true)
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply {
                                text = "Tentar novamente"
                                isEnabled = true
                                setOnClickListener { dialog.dismiss(); showUpdateDialog(activity, tag, apkUrl, apkSize) }
                            }
                            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = true
                        }
                    }
                }.start()
            }
        }
        dialog.show()
    }

    private fun downloadApk(activity: Activity, url: String, onProgress: (Long, Long) -> Unit): File {
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder().url(url).header("User-Agent", "SIG-Android").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
            val body = response.body ?: throw IllegalStateException("Resposta vazia")
            val file = File(activity.cacheDir, "sig-update.apk")
            val total = body.contentLength()
            var downloaded = 0L
            val input = body.byteStream()
            val output = file.outputStream()
            try {
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                    downloaded += read
                    onProgress(downloaded, total)
                }
                output.flush()
            } finally {
                input.close()
                output.close()
            }
            return file
        }
    }

    private fun installApk(activity: Activity, file: File) {
        val uri: Uri = FileProvider.getUriForFile(
            activity,
            "${activity.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        activity.startActivity(intent)
    }
}
