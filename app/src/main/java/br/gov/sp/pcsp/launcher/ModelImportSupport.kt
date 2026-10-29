package br.gov.sp.pcsp.launcher

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.CancellationException

/**
 * R11/fase D3 (B) — suporte à importação do MODELO EXTERNO (escolhido pelo usuário
 * via SAF) sob identidade/verificação, cópia ATÔMICA e orçamento de espaço.
 *
 * Medições da R11/D3 no PJA110 (`/storage/emulated/0` é FUSE):
 *   * leitura do modelo externo (147 MB): 630 MB/s FUSE vs 1,1 GB/s interno;
 *   * turbo 1,6 GB: leitura completa em ~0,93 s (page cache);
 *   * carga do modelo (whisper_init) FUSE vs interno: 107,2 ms vs 92,7 ms (base);
 *   * escrita (importação) no diretório externo: 2,5 GB/s (buffered).
 * Conclusões que definem este helper:
 *   * NÃO se justifica um cache interno privado (copiar 1,6 GB para /data não paga
 *     os ~15 ms de penalidade FUSE na carga) — o modelo continua onde o usuário o
 *     colocou (a regra proíbe mover/apagar o arquivo do usuário);
 *   * o que falta é o CONTRATO da importação: atômica (sem modelo parcial), com
 *     identidade persistida (sha256 + tamanho) e sem reescrever 1,6 GB quando o
 *     mesmo arquivo é reescolhido.
 *
 * Este objeto é lógica PURA (sem Android) para poder ser testado na JVM.
 */
object ModelImportSupport {

    const val PART_SUFFIX = ".part"
    const val IDENTITY_SUFFIX = ".identity"

    /** Cria `.part` exclusivo na mesma pasta/filesystem do destino para este import. */
    fun partFile(destination: File): File {
        val parent = destination.parentFile ?: throw IllegalArgumentException("destino sem pasta pai")
        check(parent.isDirectory || parent.mkdirs()) { "não foi possível criar ${parent.absolutePath}" }
        val prefix = "mdl-${destination.name.take(24)}-"
        return File.createTempFile(prefix, PART_SUFFIX, parent)
    }

    /** Sidecar com a identidade do modelo importado (`sha256 tamanho`). */
    fun identityFile(destination: File): File = File(destination.parentFile, destination.name + IDENTITY_SUFFIX)

    /** sha256 em streaming (nunca carrega o arquivo inteiro na memória). */
    fun sha256(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read > 0) md.update(buffer, 0, read)
        }
        return hex(md.digest())
    }

    /**
     * Copia [input] -> [output] devolvendo o sha256 do conteúdo. A digestão sai
     * "de graça" junto da cópia: nenhuma leitura extra do arquivo de origem.
     */
    fun copyAndDigest(input: InputStream, output: OutputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            md.update(buffer, 0, read)
            output.write(buffer, 0, read)
        }
        output.flush()
        return hex(md.digest())
    }

    fun identityLine(sha256: String, size: Long): String = "$sha256 $size"

    /** Lê a identidade persistida (null se ausente/malformada). */
    fun readIdentity(file: File): Pair<String, Long>? {
        if (!file.isFile) return null
        val text = runCatching { file.readText().trim() }.getOrNull() ?: return null
        val parts = text.split(" ")
        if (parts.size != 2) return null
        val digest = parts[0]
        if (digest.length != 64 || !digest.all { it in "0123456789abcdef" }) return null
        val size = parts[1].toLongOrNull() ?: return null
        if (size <= 0L) return null
        return digest to size
    }

    /** Identidade do sidecar coincide com a origem; sozinho, isso NÃO valida o destino. */
    private fun identityMatchesSource(destIdentity: Pair<String, Long>?, sourceSha256: String, sourceSize: Long): Boolean =
        destIdentity != null && sourceSize > 0L &&
            destIdentity.first == sourceSha256 && destIdentity.second == sourceSize

    /**
     * Só pula a cópia quando destino, sidecar e origem concordam por conteúdo.
     * Sidecar órfão, destino truncado/ilegível ou bytes substituídos não autorizam skip.
     */
    fun canSkipImport(
        destination: File,
        destIdentity: Pair<String, Long>?,
        sourceSha256: String,
        sourceSize: Long,
    ): Boolean {
        if (destIdentity == null || !destination.isFile || !destination.canRead()) return false
        if (destination.length() != sourceSize || !identityMatchesSource(destIdentity, sourceSha256, sourceSize)) return false
        val actualSha = runCatching { destination.inputStream().use(::sha256) }.getOrNull() ?: return false
        return actualSha == destIdentity.first
    }

    class SelectionGate {
        private val lock = Any()
        private var generation = 0L

        fun beginImport(): Long = synchronized(lock) { ++generation }

        fun invalidate(): Long = synchronized(lock) { ++generation }

        fun isCurrent(token: Long): Boolean = synchronized(lock) { generation == token }

        fun runIfCurrent(token: Long, action: () -> Unit): Boolean = synchronized(lock) {
            if (generation != token) return@synchronized false
            action()
            true
        }

        fun selectIfCurrent(token: Long, action: () -> Unit): Boolean = synchronized(lock) {
            if (generation != token) return@synchronized false
            ++generation
            action()
            true
        }
    }

    enum class ImportDecision { REUSE, COPY, INSUFFICIENT_SPACE }

    /**
     * Decide reuse before querying available space. This keeps a verified existing
     * model usable even when there is not enough free space for another full copy.
     */
    fun importDecision(
        destination: File,
        destIdentity: Pair<String, Long>?,
        sourceSha256: String?,
        sourceSize: Long,
        usableBytes: () -> Long,
    ): ImportDecision {
        if (sourceSha256 != null && canSkipImport(destination, destIdentity, sourceSha256, sourceSize)) {
            return ImportDecision.REUSE
        }
        if (sourceSize <= 0L) return ImportDecision.COPY
        return if (lacksSpace(usableBytes(), sourceSize)) ImportDecision.INSUFFICIENT_SPACE else ImportDecision.COPY
    }

    /**
     * Publica bytes completos e sidecar por rename atômico no mesmo diretório.
     * Se o move do payload falhar, o destino/sidecar anteriores permanecem intactos.
     * A falha do segundo move deixa sidecar antigo detectavelmente stale; o próximo
     * import verifica o conteúdo e recupera em vez de confiar no metadata.
     */
    fun publishAtomically(
        part: File,
        destination: File,
        identity: Pair<String, Long>,
        commitIfCurrent: (() -> Unit) -> Boolean = { action -> action(); true },
        atomicMove: (File, File) -> Unit,
    ) {
        require(part.isFile && part.canRead()) { "temporário de modelo ausente ou ilegível" }
        require(identity.first.length == 64 && identity.second == part.length()) { "identidade não corresponde ao temporário" }
        val parent = destination.parentFile ?: throw IllegalArgumentException("destino sem pasta pai")
        require(part.canonicalFile.parentFile == parent.canonicalFile) { "promoção deve permanecer no mesmo diretório" }
        val sidecar = identityFile(destination)
        val sidecarPart = partFile(sidecar)
        try {
            FileOutputStream(sidecarPart).use { output ->
                output.write(identityLine(identity.first, identity.second).toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            val committed = commitIfCurrent {
                atomicMove(part, destination)
                atomicMove(sidecarPart, sidecar)
            }
            if (!committed) throw CancellationException("importação substituída por seleção mais recente")
        } finally {
            sidecarPart.delete()
        }
    }

    /** O destino comporta a cópia? (só decide quando o tamanho da origem é conhecido) */
    fun lacksSpace(usableBytes: Long, sourceSize: Long): Boolean =
        sourceSize > 0L && usableBytes in 0 until sourceSize

    /**
     * Identidade do arquivo de modelo usada na chave do cache de contexto.
     * O hash do conteúdo é calculado na fronteira de carga do modelo (não em
     * cada janela live): tamanho/mtime não detectam substituição mantendo metadados.
     */
    fun cacheIdentity(file: File): String {
        require(file.isFile && file.canRead()) { "arquivo de modelo ausente ou ilegível: ${file.absolutePath}" }
        val size = file.length()
        val digest = file.inputStream().use(::sha256)
        return "$digest:$size"
    }

    private fun hex(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xff
            out.append("0123456789abcdef"[v ushr 4])
            out.append("0123456789abcdef"[v and 0x0f])
        }
        return out.toString()
    }
}
