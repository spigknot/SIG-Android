package br.gov.sp.pcsp.launcher

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Verificação silenciosa de atualização do APK no GitHub.
 *
 * A API pública check(Activity) mantém o fluxo original da MainActivity. A
 * consulta e a decisão de atualização ficam separadas em funções internas
 * para que HTTP, JSON, versão e download tenham aceitação determinística.
 */
object AppUpdateChecker {

    /** Versão deste APK — atualizar junto com a release (formato YYYYMMDD_NNN). */
    const val APP_VERSION = "20260925_002"

    private const val RELEASES_URL = "https://api.github.com/repos/spigknot/SIG-Android/releases/latest"
    private const val APK_ASSET_NAME = "sig.apk"
    private const val LOG_TAG = "AppUpdateChecker"

    sealed class ReleaseCheckResult {
        data class NoUpdate(val tag: String, val reason: String) : ReleaseCheckResult()
        data class Available(val tag: String, val apkUrl: String, val apkSize: Long) : ReleaseCheckResult()
        data class Failure(val code: String) : ReleaseCheckResult()
    }

    fun check(activity: Activity) {
        Thread {
            val startedAt = System.nanoTime()
            val result = checkRelease(createReleaseClient())
            emitDiagnostic(result, elapsedMillis(startedAt))
            if (result is ReleaseCheckResult.Available) {
                activity.runOnUiThread {
                    showUpdateDialog(activity, result.tag, result.apkUrl, result.apkSize)
                }
            }
        }.start()
    }

    internal fun checkRelease(
        client: OkHttpClient,
        endpoint: String = RELEASES_URL,
        installedVersion: String = APP_VERSION
    ): ReleaseCheckResult {
        return try {
            val request = Request.Builder()
                .url(endpoint)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "SIG-Android")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@use ReleaseCheckResult.Failure("http_" + response.code)
                }
                val payload = response.body?.string()
                    ?: return@use ReleaseCheckResult.Failure("empty_body")
                parseRelease(payload, installedVersion)
            }
        } catch (_: Exception) {
            ReleaseCheckResult.Failure("network_error")
        }
    }

    internal fun parseRelease(payload: String, installedVersion: String = APP_VERSION): ReleaseCheckResult {
        return try {
            val json = JSONObject(payload)
            val tag = json.optString("tag_name", "").trim()
            val normalizedTag = normalize(tag)
            val normalizedInstalled = normalize(installedVersion)
            if (normalizedTag.isEmpty()) {
                return ReleaseCheckResult.Failure("missing_tag")
            }
            if (normalizedInstalled.isEmpty()) {
                return ReleaseCheckResult.Failure("invalid_installed_version")
            }
            if (normalizedTag <= normalizedInstalled) {
                return ReleaseCheckResult.NoUpdate(tag, "not_newer")
            }

            val assets = json.optJSONArray("assets")
                ?: return ReleaseCheckResult.Failure("assets_missing")
            var apkUrl: String? = null
            var apkSize = 0L
            for (index in 0 until assets.length()) {
                val asset = assets.optJSONObject(index) ?: continue
                if (asset.optString("name") == APK_ASSET_NAME) {
                    apkUrl = asset.optString("browser_download_url", "").trim()
                    apkSize = asset.optLong("size")
                    break
                }
            }
            if (apkUrl.isNullOrEmpty()) {
                return ReleaseCheckResult.Failure("asset_missing")
            }
            ReleaseCheckResult.Available(tag, apkUrl, apkSize)
        } catch (_: Exception) {
            ReleaseCheckResult.Failure("invalid_json")
        }
    }

    internal fun downloadApkToFile(
        client: OkHttpClient,
        url: String,
        destination: File,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): File {
        val parent = destination.parentFile
            ?: throw IllegalArgumentException("download_destination_missing")
        if (!parent.exists() && !parent.mkdirs()) {
            throw IllegalStateException("download_destination_unavailable")
        }
        val partial = File(parent, destination.name + ".part")
        partial.delete()
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "SIG-Android")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("download_http_" + response.code)
                }
                val body = response.body ?: throw IllegalStateException("download_empty_body")
                val total = body.contentLength()
                var downloaded = 0L
                body.byteStream().use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            onProgress(downloaded, total)
                        }
                        output.flush()
                    }
                }
            }
            if (destination.exists() && !destination.delete()) {
                throw IllegalStateException("download_destination_replace_failed")
            }
            if (!partial.renameTo(destination)) {
                throw IllegalStateException("download_move_failed")
            }
            return destination
        } catch (error: Exception) {
            partial.delete()
            throw error
        }
    }

    private fun createReleaseClient(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()

    private fun createDownloadClient(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

    private fun emitDiagnostic(result: ReleaseCheckResult, elapsedMillis: Long) {
        val outcome = when (result) {
            is ReleaseCheckResult.NoUpdate -> "no_update_" + result.reason
            is ReleaseCheckResult.Available -> "update_available"
            is ReleaseCheckResult.Failure -> "failure_" + result.code
        }
        val level = if (result is ReleaseCheckResult.Failure) Log.WARN else Log.INFO
        Log.println(level, LOG_TAG, "release_check outcome=" + outcome + " elapsed_ms=" + elapsedMillis)
    }

    private fun elapsedMillis(startedAt: Long): Long =
        (System.nanoTime() - startedAt) / 1_000_000L

    private fun normalize(version: String): String =
        version.replace("-", "").replace("_", "").trim()

    private fun showUpdateDialog(activity: Activity, tag: String, apkUrl: String, apkSize: Long) {
        if (activity.isFinishing) return
        // A API do GitHub JÁ entrega `apkSize` (o campo `size` do asset) e ele era
        // ignorado: o usuário era convidado a baixar um APK sem saber quanto. O
        // plano traz o próprio `.apk` como arquivo único (é o que o app baixa).
        val plano = DownloadSizeFormat.Plano(
            rotulo = "Atualização do SIG",
            arquivos = listOf(DownloadSizeFormat.Arquivo(APK_ASSET_NAME, apkSize)),
        )
        DownloadPlanDialog.confirmar(
            activity = activity,
            plano = plano,
            negativo = "Agora não",
            positivo = "Atualizar",
            prefacio = "Uma nova versão do SIG está disponível ($tag).",
            sufixo = "Baixar e instalar agora?",
        ) {
            baixarEInstalar(activity, tag, apkUrl, plano)
        }
    }

    /** Baixa o APK com a lista que acumula e abre o instalador do sistema. */
    private fun baixarEInstalar(
        activity: Activity,
        tag: String,
        apkUrl: String,
        plano: DownloadSizeFormat.Plano,
    ) {
        if (activity.isFinishing) return
        val progresso = DownloadPlanDialog.progresso(
            activity = activity,
            plano = plano,
            titulo = "Atualizando o SIG",
        )
        Thread {
            try {
                val file = downloadApk(activity, apkUrl) { downloaded, total ->
                    activity.runOnUiThread {
                        // Total desconhecido (sem Content-Length) não vira "de 0 B":
                        // a barra fica indeterminada e o texto mostra só o baixado.
                        val percent = if (total > 0L) {
                            ((downloaded.coerceAtMost(total) * 100L) / total).toInt()
                        } else {
                            -1
                        }
                        val feitos = if (percent >= 100) plano.arquivos.map { it.nome }.toSet() else emptySet()
                        progresso.atualizar(downloaded, total, percent, feitos)
                    }
                }
                activity.runOnUiThread {
                    progresso.atualizar(file.length(), file.length(), 100, plano.arquivos.map { it.nome }.toSet())
                    progresso.fechar()
                    installApk(activity, file)
                }
            } catch (error: Exception) {
                activity.runOnUiThread {
                    progresso.etapa(
                        "Não foi possível baixar a atualização:\n${error.message ?: error.javaClass.simpleName}"
                    )
                    progresso.fechar()
                    showUpdateDialog(activity, tag, apkUrl, plano.totalBytes)
                }
            }
        }.start()
    }

    private fun downloadApk(activity: Activity, url: String, onProgress: (Long, Long) -> Unit): File =
        downloadApkToFile(
            client = createDownloadClient(),
            url = url,
            destination = File(activity.cacheDir, "sig-update.apk"),
            onProgress = onProgress
        )

    private fun installApk(activity: Activity, file: File) {
        val uri: Uri = FileProvider.getUriForFile(
            activity,
            activity.packageName + ".fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        activity.startActivity(intent)
    }
}
