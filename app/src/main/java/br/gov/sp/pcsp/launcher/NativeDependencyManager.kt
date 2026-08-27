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
    const val COMPONENT_VERSION = "2"
    private const val LIBRARY_PROPERTY = "sig.native.library.dir"
    private const val ONNX_NATIVE_PATH_PROPERTY = "onnxruntime.native.path"
    private const val ROOT_NAME = "native_dependencies"
    private const val MARKER_NAME = ".installed"
    private const val SILERO_MODEL = "ggml-silero-v6.2.0.bin"

    private val packages = mapOf(
        "arm64-v8a" to PackageSpec(
            "https://pub-6476622beda24c82875cb84f11f660ea.r2.dev/sig-android-dependencies-v2-arm64-v8a.zip",
            "402255cd51a4a31ba337bb8fe240b566b616507b3c09ae7eae98365df0cce958",
            42_633_501L
        ),
        "x86_64" to PackageSpec(
            "https://pub-6476622beda24c82875cb84f11f660ea.r2.dev/sig-android-dependencies-v2-x86_64.zip",
            "3a148ed2dea71f12e6eab8bbf2e76bd1df4fabccfc5d3e0d6001e0352788b9cf",
            45_918_603L
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
        "libsig_npu_probe.so",
        "libonnxruntime.so",
        "libonnxruntime4j_jni.so"
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
        // O ONNX Runtime (engine do Granite) carrega libonnxruntime.so e
        // libonnxruntime4j_jni.so deste diretório. O onnxruntime-java no Android
        // usa System.loadLibrary("onnxruntime4j_jni") — que busca SÓ no caminho
        // do classloader do APK (a property "onnxruntime.native.path" é ignorada
        // no Android). Por isso registramos este diretório no classloader.
        registerNativeLibraryDir(libDir)
        System.setProperty(ONNX_NATIVE_PATH_PROPERTY, libDir.absolutePath)
        debugLog(context, "activateIfInstalled: dir=$libDir")
        return true
    }

    /**
     * Registra [libDir] no caminho de busca de libs nativas do classloader da app,
     * para que System.loadLibrary() encontre as libs baixadas do R2 (o
     * onnxruntime-java só usa System.loadLibrary no Android e não lê
     * onnxruntime.native.path). É o padrão suportado para libs externas no ART.
     */
    fun registerNativeLibraryDir(libDir: File) {
        if (!libDir.isDirectory) return
        try {
            val classLoader = NativeDependencyManager::class.java.classLoader ?: return
            val pathListField = findFieldUpward(classLoader.javaClass, "pathList")
            pathListField.isAccessible = true
            val pathList = pathListField.get(classLoader) ?: return

            // API 23-25: lista de diretórios usada diretamente na busca.
            runCatching {
                val dirsField = findFieldUpward(pathList.javaClass, "nativeLibraryDirectories")
                dirsField.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                val dirs = dirsField.get(pathList) as MutableList<File>
                if (!dirs.contains(libDir)) dirs.add(0, libDir)
            }.onFailure { android.util.Log.e("SigNative", "nativeLibraryDirectories falhou: ${it.message}") }

            // API 26+: NativeLibraryElement[] construído com o construtor público
            // NativeLibraryElement(File) — evita depender do makePathElement.
            //
            // PREPEND (posição 0), NÃO append: alguns aparelhos (OnePlus 15 e outros
            // com OEM que embute onnxruntime no /system/lib64) têm libonnxruntime.so e
            // libonnxruntime4j_jni.so NO SISTEMA. O findLibrary do DexPathList percorre
            // nativeLibraryPathElements EM ORDEM e devolve a PRIMEIRA ocorrência; como
            // /system/lib64 vem do java.library.path e aparece ANTES, um append deixaria
            // a lib OEM "vencer" a busca. Aí o System.loadLibrary resolve para
            // /system/lib64/libonnxruntime4j_jni.so, que o namespace clns-9 do app NÃO
            // permite carregar -> dlopen failed -> NoClassDefFoundError: OrtEnvironment.
            runCatching {
                val elementsField = findFieldUpward(pathList.javaClass, "nativeLibraryPathElements")
                elementsField.isAccessible = true
                val elements = elementsField.get(pathList) as Array<*>
                val componentType: Class<*> = elements.javaClass.componentType!!
                // Construtor público NativeLibraryElement(File).
                val ctor = componentType.getConstructor(File::class.java)
                ctor.isAccessible = true
                val newElement = ctor.newInstance(libDir)
                // Dedup: se o primeiro elemento já aponta para o nosso dir, não re-insere
                // (o activateIfInstalled roda a cada subida/uso, e o toString() do
                // NativeLibraryElement vira `directory "<path>"`).
                val alreadyFirst = elements.isNotEmpty() &&
                    elements[0] != null &&
                    elements[0].toString().contains(libDir.absolutePath)
                if (!alreadyFirst) {
                    val newElements = java.lang.reflect.Array.newInstance(componentType, elements.size + 1)
                    System.arraycopy(elements, 0, newElements, 1, elements.size)
                    java.lang.reflect.Array.set(newElements, 0, newElement)
                    elementsField.set(pathList, newElements)
                    android.util.Log.i("SigNative", "nativeLibraryPathElements atualizado (${elements.size} -> ${elements.size + 1}, prepend)")
                } else {
                    android.util.Log.i("SigNative", "nativeLibraryPathElements já registrado no topo")
                }
            }.onFailure { android.util.Log.e("SigNative", "nativeLibraryPathElements falhou: ${it.message}") }

            android.util.Log.i("SigNative", "registerNativeLibraryDir: $libDir")
        } catch (e: Throwable) {
            android.util.Log.e("SigNative", "registerNativeLibraryDir falhou", e)
        }
    }

    private fun findFieldUpward(cls: Class<*>, name: String): java.lang.reflect.Field {
        var current: Class<*>? = cls
        while (current != null) {
            try {
                return current.getDeclaredField(name)
            } catch (_: NoSuchFieldException) {
            }
            current = current.superclass
        }
        throw NoSuchFieldException(name)
    }

    /** Pré-carrega as libs nativas do pacote NA ORDEM DE DEPENDÊNCIA antes do
     * primeiro uso do FFmpegKit.
     *
     * ATENÇÃO: o wrapper do FFmpegKit (NativeLoader) já carrega as libs sozinho
     * via sig.native.library.dir — o System.load manual do libffmpegkit.so fora
     * desse fluxo causa SIGSEGV (observado no campo). NÃO chame este método no
     * fluxo normal; fica apenas como ferramenta de diagnóstico. */
    @Suppress("unused")
    fun preloadFfmpegLibraries(context: Context) {
        val abi = supportedAbi() ?: return
        preloadFfmpegLibraries(context, File(installedRoot(context, abi), "lib"))
    }

    /** Pré-carrega as libs nativas do pacote NA ORDEM DE DEPENDÊNCIA antes do
     * primeiro uso do FFmpegKit (o wrapper as carrega via System.load; aqui
     * garantimos a ordem e logamos cada passo para diagnóstico).
     * Carrega SÓ o núcleo do ffmpeg — o sig_whisper e o npu_probe têm carga
     * própria (WhisperNative) e NÃO podem bloquear as ferramentas de vídeo. */
    private fun preloadFfmpegLibraries(context: Context, libDir: File): Boolean {
        val order = listOf(
            "libc++_shared.so",
            "libomp.so",
            "libavutil.so",
            "libswresample.so",
            "libswscale.so",
            "libavcodec.so",
            "libavformat.so",
            "libavfilter.so",
            "libavdevice.so",
            "libffmpegkit_abidetect.so",
            "libffmpegkit.so",
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
