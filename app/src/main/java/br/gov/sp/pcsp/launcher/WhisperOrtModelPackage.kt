package br.gov.sp.pcsp.launcher

import java.io.File
import java.security.MessageDigest

/**
 * Identidade, inventário e validação do pacote ORT no disco
 * (ORT1-B03/B04/B07; endurecido no adendo1 §1).
 *
 * Duas correções vieram da revisão independente:
 *
 * 1. **Conjunto obrigatório de componentes**: um manifesto com só `config.json`
 *    retornava `Ready`. Agora o pacote tem de trazer encoder, decoder de init,
 *    decoder com past (quando o export usa), tokenizer e config — cada papel é
 *    exigido explicitamente e a ausência é `MISSING_COMPONENT`.
 * 2. **External data de verdade**: a varredura por `.onnx_data` "provava"
 *    presença de nome, não de arquivo — um grafo válido apontando para
 *    `weights.bin` ausente passava. Agora o `location` é LIDO do protobuf
 *    ([externalDataLocations]), o arquivo precisa existir no diretório e estar
 *    declarado no manifesto com o papel `EXTERNAL_DATA`.
 *
 * A promoção é atômica por rename: pacote parcial nunca aparece no caminho
 * ativo e pacote em uso nunca é sobrescrito.
 *
 * Seam puro de regra: usa `java.io`/`MessageDigest`, não usa Android.
 */
object WhisperOrtModelPackage {

    /** Papel de cada arquivo dentro do pacote. */
    enum class Role {
        ENCODER,
        DECODER,
        DECODER_WITH_PAST,
        TOKENIZER,
        CONFIG,
        EXTERNAL_DATA,
    }

    /** Componente declarado do pacote, com integridade esperada. */
    data class FileEntry(
        val name: String,
        val sizeBytes: Long,
        val sha256: String,
        val role: Role,
        /** Quando este arquivo é external data, o `.onnx` a que pertence. */
        val externalDataOf: String? = null,
    )

    /**
     * Identidade completa do pacote.
     *
     * [key] é a chave de cache: engine/runtime/provider/revision/dtype/opset.
     */
    data class Identity(
        val model: String,
        val revision: String,
        val dtype: String,
        val provider: String,
        val opset: Int,
    ) {
        val key: String get() = "ort/$model/$revision/$dtype/$provider/opset$opset"
    }

    /** Por que o pacote foi recusado. Cada valor é um caminho de erro distinto. */
    enum class Error {
        /** Arquivo do manifesto não existe no diretório. */
        MISSING_FILE,

        /** Tamanho diferente do declarado (download truncado/corrompido). */
        SIZE_MISMATCH,

        /** SHA-256 diferente do declarado (conteúdo trocado). */
        SHA_MISMATCH,

        /** O `.onnx` referencia external data e o arquivo não está no pacote. */
        EXTERNAL_DATA_MISSING,

        /** Nome de arquivo com caminho relativo/absoluto: escapa do diretório. */
        PATH_UNSAFE,

        /** Manifesto vazio: nada a validar não é pacote válido. */
        EMPTY_PACKAGE,

        /** Falta um papel obrigatório (encoder/decoder/tokenizer/config). */
        MISSING_COMPONENT,
    }

    /** Resultado da validação. */
    sealed class Verdict {
        /** Pacote íntegro: pode abrir sessão. */
        data class Ready(val identity: Identity, val files: List<FileEntry>) : Verdict()

        /** Pacote recusado com motivo tipado e detalhe auditável. */
        data class Invalid(val error: Error, val detail: String) : Verdict()
    }

    /** Papéis que TODO pacote precisa ter, sem exceção. */
    private val REQUIRED_ROLES = setOf(Role.ENCODER, Role.DECODER, Role.TOKENIZER, Role.CONFIG)

    /** Papel que o export com `with_past` separado precisa declarar. */
    private val SEPARATE_PAST_ROLE = Role.DECODER_WITH_PAST

    /**
     * Valida [entries] contra [root].
     *
     * [verifySha] é `true` no aceite do pacote (uma vez) e pode ser `false` no
     * caminho quente, onde o marcador de instalação já cobriu integridade. Mesmo
     * com `false`, presença, tamanho e conjunto obrigatório são SEMPRE conferidos.
     */
    fun validate(
        root: File,
        identity: Identity,
        entries: List<FileEntry>,
        verifySha: Boolean,
        requireWithPast: Boolean = true,
    ): Verdict {
        if (entries.isEmpty()) return Verdict.Invalid(Error.EMPTY_PACKAGE, "manifesto sem arquivos")
        for (entry in entries) {
            if (!isSafeName(entry.name)) {
                return Verdict.Invalid(Error.PATH_UNSAFE, "nome não permitido: ${entry.name}")
            }
            val file = File(root, entry.name)
            if (!file.isFile) {
                return Verdict.Invalid(Error.MISSING_FILE, "${entry.name} ausente em ${root.path}")
            }
            if (file.length() != entry.sizeBytes) {
                return Verdict.Invalid(
                    Error.SIZE_MISMATCH,
                    "${entry.name}: ${file.length()} B != ${entry.sizeBytes} B declarado",
                )
            }
            if (verifySha) {
                val measured = sha256(file)
                if (!measured.equals(entry.sha256, ignoreCase = true)) {
                    return Verdict.Invalid(Error.SHA_MISMATCH, "${entry.name}: $measured != ${entry.sha256}")
                }
            }
        }
        // Só depois de cada arquivo estar íntegro o conjunto de papéis é exigido:
        // um arquivo corrompido precisa ser reportado como corrompido, não como
        // "componente faltante".
        val obrigatorios = if (requireWithPast) REQUIRED_ROLES + SEPARATE_PAST_ROLE else REQUIRED_ROLES
        val missingRole = obrigatorios.firstOrNull { role -> entries.none { it.role == role } }
        if (missingRole != null) {
            return Verdict.Invalid(
                Error.MISSING_COMPONENT,
                "pacote sem componente obrigatório $missingRole (tem ${entries.map { it.role }.distinct()})",
            )
        }
        val declared = entries.map { it.name }.toSet()
        for (entry in entries.filter { it.role == Role.ENCODER || it.role == Role.DECODER || it.role == SEPARATE_PAST_ROLE }) {
            // Lê o `location` REAL do external data, não um nome parecido.
            for (location in externalDataLocations(File(root, entry.name))) {
                if (location !in declared || !File(root, location).isFile) {
                    return Verdict.Invalid(
                        Error.EXTERNAL_DATA_MISSING,
                        "${entry.name} referencia external data '$location' que não está no pacote",
                    )
                }
                val companion = entries.first { it.name == location }
                if (companion.role != Role.EXTERNAL_DATA) {
                    return Verdict.Invalid(
                        Error.EXTERNAL_DATA_MISSING,
                        "$location existe mas não está declarado com papel EXTERNAL_DATA",
                    )
                }
            }
        }
        // Declarado mas não referenciado: registra como inválido o inverso também,
        // para que um pacote não carregue peso órfão sem que ninguém perceba.
        for (entry in entries.filter { it.role == Role.EXTERNAL_DATA }) {
            val owner = entry.externalDataOf
                ?: return Verdict.Invalid(
                    Error.EXTERNAL_DATA_MISSING,
                    "${entry.name} declara EXTERNAL_DATA sem dizer de qual .onnx",
                )
            if (owner !in declared) {
                return Verdict.Invalid(
                    Error.EXTERNAL_DATA_MISSING,
                    "${entry.name} aponta para '$owner' que não está no manifesto",
                )
            }
        }
        return Verdict.Ready(identity, entries)
    }

    /**
     * Nomes de external data referenciados por [file], lidos do protobuf.
     *
     * Cada entrada `external_data` é um StringStringEntryProto: chave
     * `location` (tag 0x0A, tamanho 8) seguida do valor (tag 0x12, tamanho N).
     * O scan procura exatamente esse par — um arquivo que apenas MENCIONE
     * `.onnx_data` em outro campo não é confundido com uma referência real.
     *
     * Limite declarado: procura no cabeçalho ([scanBytes]); o grafo do export
     * traz os inicializadores no início. Não é um parser ONNX completo — é a
     * verificação suficiente para um catálogo inspecionado, como a revisão pediu.
     */
    fun externalDataLocations(file: File, chunkBytes: Int = 8 * 1024 * 1024): List<String> {
        if (!file.isFile || file.length() == 0L) return emptyList()
        // tag 0x0A (key) + tamanho 8 + "location"
        val header = byteArrayOf(0x0A, 0x08) + "location".toByteArray(Charsets.US_ASCII)
        val out = ArrayList<String>(2)
        // Varredura do ARQUIVO INTEIRO: um `location` depois dos primeiros 8 MB
        // passava despercebido e o pacote era aceito sem os pesos.
        file.inputStream().use { input ->
            var read = 0
            var carry = 0
            val buffer = ByteArray(chunkBytes + header.size + 2)
            while (true) {
                if (carry > 0) System.arraycopy(buffer, read - carry, buffer, 0, carry)
                val filled = carry + input.read(buffer, carry, buffer.size - carry).coerceAtLeast(0)
                if (filled <= carry) break
                read = filled
                var i = 0
                while (i <= read - header.size - 2) {
                    var matches = true
                    for (j in header.indices) {
                        if (buffer[i + j] != header[j]) {
                            matches = false
                            break
                        }
                    }
                    if (!matches) {
                        i++
                        continue
                    }
                    val tag = buffer[i + header.size].toInt() and 0xFF
                    if (tag != 0x12) {
                        i++
                        continue
                    }
                    var length = 0
                    var shift = 0
                    var cursor = i + header.size + 1
                    while (cursor < read) {
                        val byte = buffer[cursor].toInt() and 0xFF
                        length = length or ((byte and 0x7F) shl shift)
                        cursor++
                        if (byte and 0x80 == 0) break
                        shift += 7
                        if (shift > 21) break
                    }
                    if (length > 0 && cursor + length <= read) {
                        out.add(String(buffer, cursor, length, Charsets.UTF_8))
                    }
                    i = cursor + length
                }
                carry = minOf(header.size + 2, read)
            }
        }
        return out.distinct()
    }

    /**
     * Move o pacote validado de [staging] para [target] atomicamente.
     *
     * Recusa (em vez de mesclar) quando [target] já existe: o pacote ativo pode
     * estar em uso por uma sessão. O chamador decide desativar antes.
     */
    fun publish(staging: File, target: File): Result<Unit> {
        if (!staging.isDirectory) return Result.failure(IllegalArgumentException("staging ausente: ${staging.path}"))
        if (target.exists()) return Result.failure(IllegalStateException("pacote ativo já existe: ${target.path}"))
        target.parentFile?.mkdirs()
        return if (staging.renameTo(target)) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("rename falhou: ${staging.path} -> ${target.path}"))
        }
    }

    /** SHA-256 em streaming (não carrega o arquivo inteiro na memória). */
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Nome simples, sem separador, sem `..`, sem absoluto. */
    private fun isSafeName(name: String): Boolean =
        name.isNotEmpty() && !name.contains("..") && !name.contains('/') && !name.contains('\\') &&
            !name.startsWith(".") && File(name).name == name
}
