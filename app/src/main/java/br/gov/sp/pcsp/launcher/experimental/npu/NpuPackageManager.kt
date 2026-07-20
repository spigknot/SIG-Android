package br.gov.sp.pcsp.launcher.experimental.npu

import android.content.Context
import android.net.Uri
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

internal class NpuPackageManager(private val context: Context) {
    private val root: File = (context.getExternalFilesDir("npu_models")
        ?: File(context.filesDir, "npu_models")).apply { mkdirs() }

    fun packageDir(model: NpuModelDescriptor): File = File(root, model.id)

    fun state(model: NpuModelDescriptor): String {
        val dir = packageDir(model)
        if (!dir.isDirectory) return "Ausente"
        return runCatching {
            validateInstalled(model)
            "Instalado"
        }.getOrElse { "Inválido: ${it.message}" }
    }

    fun delete(model: NpuModelDescriptor): Boolean = packageDir(model).deleteRecursively()

    fun validateInstalled(model: NpuModelDescriptor) {
        val dir = packageDir(model)
        val packageJson = File(dir, PACKAGE_MANIFEST)
        require(packageJson.isFile) { "$PACKAGE_MANIFEST ausente" }
        validatePackageManifest(model, dir, JSONObject(packageJson.readText()))
    }

    fun importPackage(model: NpuModelDescriptor, uri: Uri) {
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "não consegui abrir o pacote" }
            installZip(model, input)
        }
    }

    fun importPackage(model: NpuModelDescriptor, file: File) {
        FileInputStream(file).use { installZip(model, it) }
    }

    private fun installZip(model: NpuModelDescriptor, input: InputStream) {
        val staging = File(root, ".${model.id}.importing")
        staging.deleteRecursively()
        staging.mkdirs()
        try {
            ZipInputStream(input.buffered()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val destination = File(staging, entry.name).canonicalFile
                        require(destination.path.startsWith(staging.canonicalPath + File.separator)) {
                            "entrada ZIP insegura: ${entry.name}"
                        }
                        if (entry.isDirectory) {
                            destination.mkdirs()
                        } else {
                            destination.parentFile?.mkdirs()
                            FileOutputStream(destination).use { zip.copyTo(it) }
                        }
                        zip.closeEntry()
                    }
            }
            val packageJson = File(staging, PACKAGE_MANIFEST)
            require(packageJson.isFile) { "$PACKAGE_MANIFEST ausente no ZIP" }
            validatePackageManifest(model, staging, JSONObject(packageJson.readText()))
            val destination = packageDir(model)
            destination.deleteRecursively()
            require(staging.renameTo(destination)) { "não consegui instalar o pacote atomically" }
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun validatePackageManifest(model: NpuModelDescriptor, dir: File, json: JSONObject) {
        require(json.optInt("schemaVersion", -1) == 1) { "schema incompatível" }
        require(json.optString("modelId") == model.id) { "pacote pertence a outro modelo" }
        require(json.optString("checkpoint") == model.checkpoint) { "checkpoint incompatível" }
        require(json.optString("variant") == model.variant) { "variante incompatível" }
        require(json.optString("encoderRuntime") == "qnn-htp") { "runtime do encoder não é QNN HTP" }
        require(json.optString("decoderRuntime") == "whisper-vulkan") { "decoder não é Vulkan" }
        require(json.optString("qnnSdkVersion").isNotBlank()) { "versão QAIRT/QNN ausente" }
        require(json.optInt("melBins") == model.melBins) { "número de Mel bins incompatível" }
        require(json.optInt("encoderOutputFrames") == model.encoderOutputFrames) { "frames de saída incompatíveis" }
        require(json.optInt("encoderOutputSize") == model.encoderOutputSize) { "embedding incompatível" }

        val socIds = json.optJSONArray("supportedSocIds") ?: error("SoCs compatíveis ausentes")
        val htpArchitectures = json.optJSONArray("supportedHtpArchitectures") ?: error("arquiteturas HTP ausentes")
        require(socIds.length() > 0) { "pacote não declara SoC compatível" }
        require(htpArchitectures.length() > 0) { "pacote não declara arquitetura HTP compatível" }
        if (Build.VERSION.SDK_INT >= 31 && Build.SOC_MODEL.isNotBlank()) {
            val currentSoc = Build.SOC_MODEL.lowercase()
            val compatible = (0 until socIds.length()).any { index ->
                val declared = socIds.getString(index).lowercase()
                currentSoc.contains(declared) || declared.contains(currentSoc)
            }
            require(compatible) { "pacote não declara compatibilidade com ${Build.SOC_MODEL}" }
        }

        val files = json.optJSONArray("files") ?: error("lista de arquivos ausente")
        require(files.length() > 0) { "pacote sem artefatos" }
        for (index in 0 until files.length()) {
            val item = files.getJSONObject(index)
            val name = item.getString("name")
            val expectedHash = item.getString("sha256").lowercase()
            require(expectedHash.matches(Regex("[0-9a-f]{64}"))) { "SHA-256 inválido para $name" }
            val file = File(dir, name).canonicalFile
            require(file.path.startsWith(dir.canonicalPath + File.separator) && file.isFile) { "$name ausente" }
            require(sha256(file) == expectedHash) { "hash incorreto para $name" }
        }
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

    companion object {
        const val PACKAGE_MANIFEST = "package.json"
    }
}
