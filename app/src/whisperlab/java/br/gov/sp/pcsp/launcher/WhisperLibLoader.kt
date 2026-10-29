package br.gov.sp.pcsp.launcher

import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * VARIANTE DE LABORATÓRIO (flavor `whisperlab`, somente debug).
 *
 * Substitui o seam [WhisperLibLoader] de produção via source set do flavor.
 * NÃO usa o NativeDependencyManager nem os caminhos/dados do pacote SIG: as
 * bibliotecas candidatas são carregadas de um diretório PRIVADO do sandbox do
 * lab (`filesDir/whisperlab-libs`), para onde foram empurradas por `run-as`.
 *
 * Para cada arquivo carregado registra caminho, tamanho, sha256 medido no
 * device e o sha256 esperado (arquivo `filesDir/art-lab/expected-sha256.json`
 * escrito pelo host) — em logcat (tag WhisperLab) e em
 * `filesDir/art-lab/lib-load.json`. Nenhum comportamento de produção é
 * alterado: este arquivo só existe na variante de lab.
 */
object WhisperLibLoader {
    const val TAG = "WhisperLab"
    const val LIBS_DIR = "whisperlab-libs"

    @Volatile
    private var reported = false

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun expectedSha(): Map<String, String> {
        val file = File(SigApplication.appInstance.filesDir, "art-lab/expected-sha256.json")
        if (!file.isFile) return emptyMap()
        return try {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            val out = HashMap<String, String>()
            val keys = root.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                out[key] = root.getString(key)
            }
            out
        } catch (t: Throwable) {
            Log.e(TAG, "expected-sha256.json ilegivel: $t")
            emptyMap()
        }
    }

    private fun report(entry: JSONObject) {
        try {
            val file = File(SigApplication.appInstance.filesDir, "art-lab/lib-load.json")
            file.parentFile?.mkdirs()
            file.appendText(entry.toString() + "\n", Charsets.UTF_8)
        } catch (t: Throwable) {
            Log.e(TAG, "falha ao gravar lib-load.json: $t")
        }
        Log.i(TAG, entry.toString())
    }

    fun load(name: String) {
        val context = SigApplication.appInstance
        val dir = File(context.filesDir, LIBS_DIR)
        val expected = expectedSha()
        val order = when (name) {
            "sig_whisper" -> listOf("libc++_shared.so", "libomp.so", "libsig_whisper.so")
            else -> listOf("lib$name.so")
        }
        if (!reported) {
            reported = true
            val ctx = JSONObject()
            ctx.put("event", "lib_load_start")
            ctx.put("dir", dir.absolutePath)
            ctx.put("dir_exists", dir.isDirectory)
            ctx.put("requested", name)
            report(ctx)
        }
        for (fileName in order) {
            val file = File(dir, fileName)
            val entry = JSONObject()
            entry.put("event", "lib_load")
            entry.put("file", fileName)
            entry.put("path", file.absolutePath)
            entry.put("exists", file.isFile)
            if (!file.isFile) {
                entry.put("result", "absent")
                report(entry)
                continue
            }
            val measured = sha256(file)
            val want = expected[fileName]
            entry.put("size", file.length())
            entry.put("sha256_measured", measured)
            entry.put("sha256_expected", want ?: JSONObject.NULL)
            entry.put(
                "sha_match",
                when {
                    want == null -> "no_expected"
                    want.equals(measured, ignoreCase = true) -> "match"
                    else -> "MISMATCH"
                }
            )
            val t0 = SystemClock.elapsedRealtime()
            try {
                System.load(file.absolutePath)
                entry.put("result", "loaded")
            } catch (t: Throwable) {
                entry.put("result", "load_failed")
                entry.put("error", t.toString())
                entry.put("elapsed_ms", SystemClock.elapsedRealtime() - t0)
                report(entry)
                throw t
            }
            entry.put("elapsed_ms", SystemClock.elapsedRealtime() - t0)
            report(entry)
        }
    }
}
