package br.gov.sp.pcsp.launcher

import android.content.Context
import android.os.Build
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream

object NativeDependencyManager {
    const val COMPONENT_VERSION = "1"
    private const val LIBRARY_PROPERTY = "sig.native.library.dir"
    private const val ROOT_NAME = "native_dependencies"
    private const val MARKER_NAME = ".installed"
    private const val SILERO_MODEL = "ggml-silero-v6.2.0.bin"

    private val packages = mapOf(
        "arm64-v8a" to PackageSpec(
            "https://drive.usercontent.google.com/download?id=1Ni3EpuvgX-KGOjSDkVR4iAtF7ZL-ueM2&export=download&confirm=t",
            "b777ae0dfe90ae73fe91a2c72286a75d36d49a40bcf14fb49de3aed05f8f2533",
            41_353_214L
        ),
        "x86_64" to PackageSpec(
            "https://drive.usercontent.google.com/download?id=1arpCKqFr-sUbpmjty9ZLHTeVHjUGQehC&export=download&confirm=t",
            "0d8b5b948b7b6a50a3dd3e3533374d837a0ec78d937d28addcee7ff2eaf9fb8f",
            43_186_016L
        )
    )

    private val requiredLibraries = listOf(
        "libc++_shared.so",
        "libavutil.so",
        "libswscale.so",
        "libswresample.so",
        "libavcodec.so",
        "libavformat.so",
        "libavfilter.so",
        "libavdevice.so",
        "libffmpegkit_abidetect.so",
        "libffmpegkit.so",
        "libomp.so",
        "libsig_whisper.so",
        "libsig_npu_probe.so"
    )
    private val loadedLibraries = ConcurrentHashMap.newKeySet<String>()

    data class PackageSpec(val url: String, val sha256: String, val downloadBytes: Long)
    data class Progress(val downloaded: Long, val total: Long, val stage: String)

    fun supportedAbi(): String? = Build.SUPPORTED_ABIS.firstOrNull { packages.containsKey(it) }

    fun packageSpec(): PackageSpec? = supportedAbi()?.let(packages::get)

    fun isInstalled(context: Context): Boolean {
        val abi = supportedAbi() ?: return false
        val root = installedRoot(context, abi)
        if (File(root, MARKER_NAME).readTextOrNull()?.trim() != COMPONENT_VERSION) return false
        val libDir = File(root, "lib")
        if (requiredLibraries.any { !File(libDir, it).isFile || File(libDir, it).length() == 0L }) return false
        return sileroModelFile(context).let { it.isFile && it.length() > 100_000L }
    }

    fun activateIfInstalled(context: Context): Boolean {
        if (!isInstalled(context)) return false
        val abi = supportedAbi() ?: return false
        val libDir = File(installedRoot(context, abi), "lib")
        System.setProperty(LIBRARY_PROPERTY, libDir.absolutePath)
        val loaded = preloadFfmpegLibraries(context, libDir)
        debugLog(context, "activateIfInstalled: dir=$libDir preload=$loaded")
        return loaded
    }

    /** Pré-carrega as libs nativas do pacote NA ORDEM DE DEPENDÊNCIA antes do
     * primeiro uso do FFmpegKit (o wrapper as carrega via System.load; aqui
     * garantimos a ordem e logamos cada passo para diagnóstico). */
    private fun preloadFfmpegLibraries(context: Context, libDir: File): Boolean {
        val order = listOf(
            "libc++_shared.so",
            "libomp.so",
            "libavutil.so",
            "libswresample.so",
            "libswscale.so",
            "libavformat.so",
            "libavcodec.so",
            "libavfilter.so",
            "libavdevice.so",
            "libffmpegkit_abidetect.so",
            "libffmpegkit.so",
            "libsig_whisper.so",
            "libsig_npu_probe.so",
        )
        var ok = true
        for (name in order) {
            val lib = File(libDir, name)
            if (!lib.isFile) {
                debugLog(context, "preload: FALTA $name")
                ok = false
                continue
            }
            try {
                System.load(lib.absolutePath)
                debugLog(context, "preload: OK $name")
            } catch (error: Throwable) {
                debugLog(context, "preload: ERRO $name -> ${error.javaClass.simpleName}: ${error.message}")
                ok = false
            }
        }
        return ok
    }

    private fun debugLog(context: Context, message: String) {
        android.util.Log.i("SigNative", message)
        try {
            val file = File(context.filesDir, "native_debug.txt")
            file.appendText("${System.currentTimeMillis()} $message\n")
        } catch (_: Throwable) {
        }
    }

    fun sileroModelFile(context: Context): File {
        val abi = supportedAbi().orEmpty()
        return File(File(installedRoot(context, abi), "models"), SILERO_MODEL)
    }

    @Synchronized
    fun loadLibrary(context: Context, name: String) {
        check(activateIfInstalled(context)) { "Dependências do SIG não instaladas." }
        if (loadedLibraries.contains(name)) return
        val libDir = File(installedRoot(context, supportedAbi()!!), "lib")
        fun load(dependency: String) {
            if (!loadedLibraries.add(dependency)) return
            try {
                System.load(File(libDir, System.mapLibraryName(dependency)).absolutePath)
            } catch (error: Throwable) {
                loadedLibraries.remove(dependency)
                throw error
            }
        }
        when (name) {
            "sig_whisper" -> {
                load("c++_shared")
                load("omp")
            }
        }
        load(name)
    }

    fun install(context: Context, onProgress: (Progress) -> Unit): Result<Unit> = runCatching {
        val abi = supportedAbi() ?: error("Arquitetura não suportada: ${Build.SUPPORTED_ABIS.joinToString()}")
        val spec = packages.getValue(abi)
        check(spec.url.startsWith("https://") && spec.sha256.length == 64) {
            "Pacote de dependências ainda não publicado para $abi."
        }

        val root = File(context.noBackupFilesDir, ROOT_NAME).apply { mkdirs() }
        val archive = File(context.cacheDir, "sig-dependencies-$COMPONENT_VERSION-$abi.zip.download")
        download(spec, archive, onProgress)
        onProgress(Progress(archive.length(), archive.length(), "Validando pacote"))
        check(sha256(archive).equals(spec.sha256, ignoreCase = true)) {
            "A assinatura SHA-256 do pacote não confere."
        }

        val staging = File(root, ".staging-$COMPONENT_VERSION-$abi-${System.nanoTime()}")
        staging.deleteRecursively()
        staging.mkdirs()
        try {
            onProgress(Progress(archive.length(), archive.length(), "Instalando componentes"))
            extractSecurely(archive, staging)
            val libDir = File(staging, "lib")
            check(requiredLibraries.all { File(libDir, it).isFile }) { "Pacote incompleto." }
            check(File(File(staging, "models"), SILERO_MODEL).isFile) { "Modelo Silero ausente." }
            File(staging, MARKER_NAME).writeText(COMPONENT_VERSION, Charsets.UTF_8)

            val destination = installedRoot(context, abi)
            val previous = File(root, ".previous-$abi")
            previous.deleteRecursively()
            if (destination.exists() && !destination.renameTo(previous)) destination.deleteRecursively()
            check(staging.renameTo(destination)) { "Não foi possível ativar os componentes." }
            previous.deleteRecursively()
        } finally {
            staging.deleteRecursively()
            archive.delete()
        }
        check(activateIfInstalled(context)) { "Os componentes foram extraídos, mas não puderam ser ativados." }
    }

    private fun download(spec: PackageSpec, destination: File, onProgress: (Progress) -> Unit) {
        destination.parentFile?.mkdirs()
        val connection = (URL(spec.url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 120_000
            setRequestProperty("User-Agent", "SIG-Android/${BuildConfig.VERSION_NAME}")
        }
        try {
            check(connection.responseCode in 200..299) { "Download retornou HTTP ${connection.responseCode}." }
            val total = connection.contentLengthLong.takeIf { it > 0L } ?: spec.downloadBytes
            BufferedInputStream(connection.inputStream).use { input ->
                FileOutputStream(destination, false).use { output ->
                    val buffer = ByteArray(1024 * 256)
                    var downloaded = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        onProgress(Progress(downloaded, total, "Baixando componentes"))
                    }
                    output.fd.sync()
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun extractSecurely(archive: File, destination: File) {
        val canonicalRoot = destination.canonicalFile
        ZipInputStream(BufferedInputStream(FileInputStream(archive))).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val output = File(destination, entry.name).canonicalFile
                check(output.path.startsWith(canonicalRoot.path + File.separator)) { "Entrada ZIP inválida." }
                if (entry.isDirectory) {
                    output.mkdirs()
                } else {
                    output.parentFile?.mkdirs()
                    FileOutputStream(output).use { zip.copyTo(it, 1024 * 256) }
                    output.setReadable(true, true)
                }
                zip.closeEntry()
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun installedRoot(context: Context, abi: String): File =
        File(File(context.noBackupFilesDir, ROOT_NAME), "$COMPONENT_VERSION-$abi")

    private fun File.readTextOrNull(): String? = runCatching { readText(Charsets.UTF_8) }.getOrNull()
}
