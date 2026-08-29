package br.gov.sp.pcsp.launcher

import android.content.Context
import android.os.Build
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import java.io.BufferedInputStream
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Gerencia o download, instalação e carregamento das libs nativas do Qualcomm AI
 * Runtime (QAIRT/QNN) para aceleração GPU (Adreno) e NPU (Hexagon HTP) no
 * Snapdragon.
 *
 * O pacote (~53 MB zip) é baixado sob demanda do Cloudflare R2 APENAS quando o
 * usuário escolhe GPU ou NPU num aparelho Qualcomm. Em aparelhos não-Qualcomm,
 * as opções aceleradas aparecem desabilitadas com tooltip explicativo.
 *
 * Libs carregadas (ordem obrigatória):
 *   1. libQnnSystem.so            — system-level
 *   2. libQnnGpu.so  (GPU)  OU   libQnnHtp.so + stub da arquitetura (HTP)
 *   3. libQnnHtpPrepare.so        — on-device graph preparation (HTP)
 *   4. libQnnHtpV{arch}Stub.so    — stub da arquitetura HTP
 *   5. libQnnHtpV{arch}Skel.so    — skeleton (DSP, carregado pelo stub)
 *
 * Os skels ficam em lib/ (mesmo dir); o stub usa ADSP_LIBRARY_PATH para
 * localizá-los (validar no device — risco R1 do plano).
 */
object QairtDependencyManager {
    private const val TAG = "QairtManager"
    const val PACKAGE_VERSION = "1"
    private const val ROOT_NAME = "qairt"
    private const val MARKER_NAME = ".installed"

    // URL do pacote no R2 (publicado por tools/qairt/build-qairt-r2-package.ps1)
    private const val PACKAGE_URL =
        "https://pub-6476622beda24c82875cb84f11f660ea.r2.dev/packages/qairt/sig-qairt-arm64-v8a-v1.zip"
    private const val PACKAGE_SHA256 =
        "df879cd794ae0a2339a039d90ced937e08f4094a536d16bc571cf68c5a61a9f0"
    private const val PACKAGE_BYTES = 55_682_231L

    // Libs que DEVEM existir após a extração (ordem de carregamento).
    private val requiredLibraries = listOf(
        "libQnnSystem.so",
        "libQnnGpu.so",
        "libQnnHtp.so",
        "libQnnHtpPrepare.so",
        "libQnnHtpV73Stub.so",
        "libQnnHtpV75Stub.so",
        "libQnnHtpV79Stub.so",
        "libQnnHtpV81Stub.so",
        "libQnnHtpV73Skel.so",
        "libQnnHtpV75Skel.so",
        "libQnnHtpV79Skel.so",
        "libQnnHtpV81Skel.so",
    )

    // Libs carregadas via System.load — evita reload.
    private val loadedLibs = mutableSetOf<String>()

    // Cache da arquitetura HTP detectada (null = não detectada ainda).
    @Volatile private var cachedHtpArch: String? = null

    // ---- detecção de hardware ----

    fun isQualcommDevice(): Boolean {
        val socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL ?: "" else ""
        val socMfr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MANUFACTURER ?: "" else ""
        return socMfr.equals("Qualcomm", ignoreCase = true) ||
            socModel.startsWith("SM", ignoreCase = true) ||
            socModel.startsWith("QCM", ignoreCase = true)
    }

    /**
     * Descobre a arquitetura HTP disponível tentando carregar o stub de cada
     * arquitetura (da mais recente para a mais antiga). A primeira que carregar
     * define a arch. Resultado é cacheado.
     *
     * Retorna null se nenhuma arquitetura HTP estiver disponível (ex.: aparelho
     * Qualcomm sem Hexagon, ou skels não carregáveis sem root).
     */
    fun htpArchitecture(): String? {
        cachedHtpArch?.let { return it }
        val archs = listOf("81", "79", "75", "73")
        for (arch in archs) {
            try {
                System.loadLibrary("QnnHtpV${arch}Stub")
                cachedHtpArch = arch
                return arch
            } catch (_: UnsatisfiedLinkError) {
                // Tenta a próxima
            }
        }
        return null
    }

    // ---- instalação ----

    fun isInstalled(context: Context): Boolean {
        val root = installedRoot(context)
        if (File(root, MARKER_NAME).readTextOrNull()?.trim() != PACKAGE_VERSION) return false
        val libDir = File(root, "lib")
        return requiredLibraries.all { File(libDir, it).isFile && File(libDir, it).length() > 0L }
    }

    fun packageDir(context: Context): File = installedRoot(context)

    fun downloadSize(): Long = PACKAGE_BYTES

    data class Progress(val downloaded: Long, val total: Long, val stage: String)

    fun install(context: Context, onProgress: (Progress) -> Unit): Result<Unit> = runCatching {
        check(isQualcommDevice()) { "Este aparelho não tem processador Qualcomm." }
        check(PACKAGE_SHA256.length == 64) { "SHA-256 do pacote não configurado." }

        val root = File(context.noBackupFilesDir, ROOT_NAME).apply { mkdirs() }
        val archive = File(context.cacheDir, "qairt-v$PACKAGE_VERSION.zip.download")

        download(archive, onProgress)
        onProgress(Progress(archive.length(), archive.length(), "Validando"))
        check(sha256(archive).equals(PACKAGE_SHA256, ignoreCase = true)) {
            "SHA-256 do pacote QAIRT não confere."
        }

        val staging = File(root, ".staging-${System.nanoTime()}")
        staging.deleteRecursively()
        staging.mkdirs()
        try {
            onProgress(Progress(archive.length(), archive.length(), "Instalando"))
            extractZip(archive, staging)
            val libDir = File(staging, "lib")
            val missing = requiredLibraries.filterNot { File(libDir, it).isFile }
            check(missing.isEmpty()) {
                "Pacote QAIRT incompleto: faltam ${missing.size} lib(s): ${missing.joinToString(", ")}"
            }
            File(staging, MARKER_NAME).writeText(PACKAGE_VERSION)

            val destination = installedRoot(context)
            val previous = File(root, ".previous")
            previous.deleteRecursively()
            if (destination.exists() && !destination.renameTo(previous)) {
                destination.deleteRecursively()
            }
            // renameTo pode falhar para diretórios em alguns devices/ROMs; usa cópia
            // recursiva como fallback para garantir que a instalação sempre ative.
            val activated = staging.renameTo(destination) || runCatching {
                staging.copyRecursively(destination, overwrite = true)
                staging.deleteRecursively()
                destination.exists()
            }.getOrDefault(false)
            check(activated) { "Falha ao ativar componentes QAIRT." }
            // Garantia pós-ativação: o isInstalled() depende do marker + libs.
            check(isInstalled(context)) {
                "Instalação QAIRT não ficou ativa (verifique o armazenamento interno)."
            }
            previous.deleteRecursively()
        } finally {
            staging.deleteRecursively()
            archive.delete()
        }
    }

    private fun download(dest: File, onProgress: (Progress) -> Unit) {
        dest.parentFile?.mkdirs()
        val conn = (URL(PACKAGE_URL).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 300_000
            setRequestProperty("User-Agent", "SIG-Android/${BuildConfig.VERSION_NAME}")
        }
        try {
            check(conn.responseCode in 200..299) { "Download QAIRT retornou HTTP ${conn.responseCode}." }
            val total = conn.contentLengthLong.takeIf { it > 0L } ?: PACKAGE_BYTES
            BufferedInputStream(conn.inputStream).use { input ->
                FileOutputStream(dest).use { out ->
                    val buf = ByteArray(256 * 1024)
                    var downloaded = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        downloaded += n
                        onProgress(Progress(downloaded, total, "Baixando QAIRT"))
                    }
                    out.fd.sync()
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun extractZip(archive: File, dest: File) {
        val canonical = dest.canonicalFile
        var extracted = 0
        ZipInputStream(BufferedInputStream(FileInputStream(archive))).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                // ⚠️ Pacotes gerados no Windows (.NET ZipFile.CreateFromDirectory) gravam
                // os nomes com '\' (0x5c) em vez de '/'. No Android/Linux, '\' NÃO é
                // separador: File(dest, "lib\libX.so") criaria um arquivo com '\' no
                // nome na raiz e a pasta lib/ nunca existiria -> "Pacote incompleto".
                val normalizedName = normalizeZipEntryName(entry.name)
                val out = File(dest, normalizedName).canonicalFile
                check(out.path.startsWith(canonical.path + File.separator)) { "Entrada ZIP inválida." }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { zip.copyTo(it, 256 * 1024) }
                    out.setReadable(true, true)
                }
                extracted++
                android.util.Log.i(TAG, "extraiu: $normalizedName -> ${out.absolutePath} (dir=${entry.isDirectory})")
                zip.closeEntry()
            }
        }
        android.util.Log.i(TAG, "extração concluída: $extracted entrada(s) em ${dest.absolutePath}")
        val lib = File(dest, "lib")
        if (lib.isDirectory) {
            android.util.Log.i(TAG, "lib/ contém: ${lib.listFiles()?.map { it.name }?.joinToString(", ") ?: "(vazio)"}")
        } else {
            android.util.Log.w(TAG, "lib/ NÃO existe em ${dest.absolutePath}")
        }
    }

    /**
     * Normaliza o nome de uma entrada ZIP para usar separador '/'.
     * Pacotes gerados no Windows (.NET) gravam '\' (0x5c); no Android isso
     * quebraria a extração (File não trata '\' como separador no Linux).
     */
    internal fun normalizeZipEntryName(name: String): String = name.replace('\\', '/')

    // ---- carregamento de libs nativas ----

    /**
     * Carrega as libs QNN na ordem correta para o backend escolhido.
     * @param backend "gpu" ou "htp" (NPU)
     * @param htpArch arquitetura HTP (ex.: "81"), se backend="htp"
     */
    fun loadQnnNatives(context: Context, backend: String, htpArch: String? = null) {
        val libDir = File(installedRoot(context), "lib")
        check(libDir.isDirectory) { "QAIRT não instalado em ${libDir.absolutePath}" }

        // Ordem: System primeiro (dependência de todos), depois backend, depois Prepare + stub
        val loadOrder = mutableListOf("libQnnSystem.so")

        when (backend) {
            "gpu" -> loadOrder.add("libQnnGpu.so")
            "htp" -> {
                checkNotNull(htpArch) { "Arquitetura HTP não detectada." }
                loadOrder.add("libQnnHtp.so")
                loadOrder.add("libQnnHtpPrepare.so")
                loadOrder.add("libQnnHtpV${htpArch}Stub.so")
                // Skel é carregado pelo stub via FastRPC — não precisa System.load
                // Mas o stub precisa encontrá-lo. ADSP_LIBRARY_PATH é setado abaixo.
            }
            else -> error("Backend QNN desconhecido: $backend")
        }

        // ADSP_LIBRARY_PATH: o stub procura o skel neste diretório.
        // Risco: em app sem root, o DSP pode não conseguir carregar o skel unsigned.
        // Validar no device (Fase 0/6).
        System.setProperty("ADSP_LIBRARY_PATH", libDir.absolutePath)

        for (lib in loadOrder) {
            if (loadedLibs.contains(lib)) continue
            val path = File(libDir, lib)
            check(path.isFile) { "lib QAIRT ausente: ${lib}" }
            try {
                System.load(path.absolutePath)
                loadedLibs.add(lib)
                android.util.Log.i(TAG, "load: OK $lib")
            } catch (e: UnsatisfiedLinkError) {
                if (e.message?.contains("already loaded", ignoreCase = true) != true) {
                    android.util.Log.e(TAG, "load: ERRO $lib -> ${e.message}")
                    throw e
                }
                loadedLibs.add(lib)
            }
        }
    }

    // ---- utilitários ----

    private fun installedRoot(context: Context): File =
        File(File(context.noBackupFilesDir, ROOT_NAME), "v$PACKAGE_VERSION")

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(1024 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun File.readTextOrNull(): String? = runCatching { readText() }.getOrNull()
}