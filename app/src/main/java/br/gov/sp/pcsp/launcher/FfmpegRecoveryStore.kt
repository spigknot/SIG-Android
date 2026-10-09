package br.gov.sp.pcsp.launcher

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Checkpoints de FFmpeg: somente arquivos de etapas concluídas e intactas são reutilizados. */
class FfmpegRecoveryStore private constructor(val directory: File, val state: JSONObject) {
    val request: JSONObject get() = state.getJSONObject("request")
    private val journal get() = File(directory, "job.json")

    @Synchronized fun save() {
        val temporary = File(directory, "job.tmp")
        temporary.outputStream().use { stream -> stream.write(state.toString().toByteArray()); stream.fd.sync() }
        if(!temporary.renameTo(journal)) {
            // Windows não substitui o destino via renameTo. O backup também
            // permite recuperar a janela entre as duas renomeações.
            val backup=File(directory,"job.backup")
            if(journal.exists()) { if(backup.exists())check(backup.delete());check(journal.renameTo(backup)) }
            if(!temporary.renameTo(journal)) { backup.renameTo(journal);error("Não foi possível gravar a retomada.") }
            backup.delete()
        }
    }

    @Synchronized fun file(key: String, suffix: String): File {
        val paths = state.getJSONObject("paths")
        if (!paths.has(key)) { paths.put(key, hash(key) + suffix); save() }
        return File(directory, paths.getString(key))
    }

    @Synchronized fun input(key: String, suffix: String, copy: (File) -> Unit): File {
        val inputs = state.getJSONObject("inputs")
        // Ao retomar, as URIs apontam para as cópias privadas já preservadas.
        val existingKey = inputs.keys().asSequence().firstOrNull { name ->
            val record = inputs.getJSONObject(name)
            name == key || (key.startsWith("file:") && runCatching { File(java.net.URI(key)).canonicalFile == File(record.getString("path")).canonicalFile }.getOrDefault(false))
        }
        if (existingKey != null) {
            val record = inputs.getJSONObject(existingKey)
            val existing = File(record.getString("path"))
            check(matches(existing, record)) { "A entrada preservada mudou ou foi removida. Inicie uma nova tarefa." }
            return existing
        }
        val target = file("input:$key", suffix)
        copy(target)
        check(target.isFile && target.length() > 0) { "Não foi possível preparar o arquivo de entrada." }
        inputs.put(key, fingerprint(target)); save()
        return target
    }

    @Synchronized fun inputPath(uri: String): String? = state.getJSONObject("inputs").optJSONObject(uri)?.optString("path")
    @Synchronized fun alias(uri: String, input: File) {
        if(owns(input)) { state.getJSONObject("inputs").put(uri,fingerprint(input)); save() }
    }

    @Synchronized fun reusable(arguments: Array<String>): Boolean {
        val record = state.getJSONObject("steps").optJSONObject(hash(arguments.joinToString("\u0000"))) ?: return false
        if (record.optString("status") != "complete") return false
        val outputs = record.getJSONArray("outputs")
        return outputs.length() > 0 && (0 until outputs.length()).all { index ->
            val output = outputs.getJSONObject(index); matches(File(output.getString("path")), output)
        }
    }

    @Synchronized fun begin(arguments: Array<String>) {
        state.getJSONObject("steps").put(hash(arguments.joinToString("\u0000")), JSONObject().put("status", "running")); save()
    }

    @Synchronized fun complete(arguments: Array<String>) {
        val target = File(arguments.last())
        val outputs = if (target.name.contains('%')) {
            val expression = Regex(Regex.escape(target.name).replace(Regex("%0?\\d*d")) { "\\E[0-9]+\\Q" })
            target.parentFile?.listFiles()?.filter { expression.matches(it.name) }.orEmpty()
        } else listOf(target)
        if (outputs.isEmpty() || outputs.any { !it.isFile || it.length() <= 0 }) return
        state.getJSONObject("steps").put(hash(arguments.joinToString("\u0000")),
            JSONObject().put("status", "complete").put("outputs", JSONArray(outputs.map { fingerprint(it) }))); save()
    }

    @Synchronized fun finish(status: String) { state.put("status", status); save() }
    fun owns(file: File) = file.canonicalPath.startsWith(directory.canonicalPath + File.separator)

    companion object {
        fun publish(staged: File,output: File) {
            if(output.exists()) {
                if(staged.canonicalFile==output.canonicalFile)return
                check(output.delete()) { "Não foi possível preparar a saída preservada." }
            }
            try { android.system.Os.link(staged.absolutePath,output.absolutePath) }
            catch (_: Exception) { staged.copyTo(output,overwrite=true) }
        }
        fun create(cache: File, tool: String, request: JSONObject): FfmpegRecoveryStore {
            val directory = File(File(cache, "ffmpeg_jobs"), UUID.randomUUID().toString())
            check(directory.mkdirs()) { "Não foi possível criar a pasta de retomada." }
            return FfmpegRecoveryStore(directory, JSONObject().put("version",1).put("tool",tool).put("status","running")
                .put("request",request).put("paths",JSONObject()).put("inputs",JSONObject()).put("steps",JSONObject())).also { it.save() }
        }
        fun pending(cache: File, tool: String): FfmpegRecoveryStore? = File(cache,"ffmpeg_jobs").listFiles().orEmpty()
            .mapNotNull { load(it) }.filter { it.state.optString("tool")==tool && it.state.optString("status") in setOf("running","failed","ready") }
            .maxByOrNull { it.journal.lastModified() }
        fun load(directory: File): FfmpegRecoveryStore? = runCatching {
            val journal=File(directory,"job.json")
            val recovered=if(journal.isFile)journal else File(directory,"job.backup").takeIf { it.isFile } ?: File(directory,"job.tmp")
            val state = JSONObject(recovered.readText())
            check(state.getInt("version")==1)
            check(directory.canonicalFile.parentFile?.name=="ffmpeg_jobs")
            FfmpegRecoveryStore(directory,state)
        }.getOrNull()
        fun protected(directory: File): Boolean = load(directory)?.state?.optString("status") in setOf("running","failed","ready")
        private fun fingerprint(file: File) = JSONObject().put("path",file.absolutePath).put("size",file.length()).put("modified",file.lastModified())
        private fun matches(file: File, expected: JSONObject) = file.isFile && file.length()>0 &&
            file.length()==expected.getLong("size") && file.lastModified()==expected.getLong("modified")
        private fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
