package br.gov.sp.pcsp.launcher.experimental.npu

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class NpuPackageDownloader(cacheDir: File) {
    data class Progress(
        val downloaded: Long,
        val total: Long,
        val bytesPerSecond: Long,
        val etaSeconds: Long?
    )

    private val downloadDir = File(cacheDir, "npu_downloads").apply { mkdirs() }
    private val cancelled = AtomicBoolean(false)
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    fun cancel() {
        cancelled.set(true)
    }

    fun download(model: NpuModelDescriptor, onProgress: (Progress) -> Unit): File {
        val url = requireNotNull(model.downloadUrl) { "URL não configurada no manifesto" }
        val expectedHash = requireNotNull(model.packageSha256) { "SHA-256 do pacote não configurado" }.lowercase()
        val part = File(downloadDir, "${model.id}.zip.part")
        val complete = File(downloadDir, "${model.id}.zip")
        cancelled.set(false)

        var offset = part.takeIf { it.isFile }?.length() ?: 0L
        val request = Request.Builder().url(url).apply {
            if (offset > 0L) header("Range", "bytes=$offset-")
        }.build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code}" }
            val append = offset > 0L && response.code == 206
            if (!append) {
                offset = 0L
                part.delete()
            }
            val responseLength = response.body?.contentLength()?.takeIf { it >= 0L } ?: -1L
            val total = model.packageSize ?: if (responseLength >= 0L) offset + responseLength else -1L
            check(model.packageSize == null || total == model.packageSize) { "tamanho remoto incompatível" }
            val startedAt = System.nanoTime()
            var downloaded = offset
            response.body?.byteStream().use { input ->
                requireNotNull(input) { "resposta sem conteúdo" }
                FileOutputStream(part, append).use { output ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        check(!cancelled.get()) { "download cancelado" }
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        val elapsed = ((System.nanoTime() - startedAt) / 1_000_000_000.0).coerceAtLeast(0.001)
                        val speed = ((downloaded - offset) / elapsed).toLong()
                        val eta = if (total > 0L && speed > 0L) (total - downloaded).coerceAtLeast(0L) / speed else null
                        onProgress(Progress(downloaded, total, speed, eta))
                    }
                    output.fd.sync()
                }
            }
        }
        check(model.packageSize == null || part.length() == model.packageSize) { "download incompleto" }
        check(sha256(part) == expectedHash) { "SHA-256 do pacote incorreto" }
        complete.delete()
        check(part.renameTo(complete)) { "não consegui concluir o arquivo baixado" }
        return complete
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
