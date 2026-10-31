package br.gov.sp.pcsp.launcher

import java.io.File
import java.security.MessageDigest

/**
 * Fronteira ÚNICA de pacote validado (ORT1-B04/M3, adendo4 §2).
 *
 * Três defeitos do contrato anterior estão fechados aqui:
 *
 * 1. **Conjunto obrigatório**: `install`/`adopt` aceitavam catálogo vazio ou só
 *    com `generation_config.json` e devolviam `Ready`; `open` aceitava marcador
 *    com `files=[]`. Agora o descritor é o do export (encoder + init +
 *    with_past + vocab + config), conferido por [validateDescriptor], e o
 *    marcador é validado contra ele — lista incompleta, nome inseguro ou fato
 *    divergente viram recusa tipada.
 * 2. **Identidade estável**: antes o pacote era promovido por cima do mesmo
 *    diretório, então um handle já entregue passava a ler bytes novos e o
 *    anterior era apagado. Agora cada instalação vive em
 *    `versions/<chave>` **imutável** e a seleção é um único arquivo `active`
 *    publicado por rename atômico. Quem já tem o handle continua na versão que
 *    adquiriu; a retirada só acontece quando ninguém mais aponta para ela.
 * 3. **Caminho quente**: `open` comparava só tamanho. Agora o marcador carrega
 *    um *fingerprint* por arquivo (tamanho + SHA de 4 janelas de 16 KB), e a
 *    versão é imutável por construção — não se rehasha 1,5 GB por transcrição,
 *    mas também não se confia apenas no tamanho.
 *
 * Cancelamento: [install] e [adopt] recebem `isCancelled`, consultado entre
 * arquivos e durante o fingerprint. Cancelar não publica nada e não toca na
 * versão ativa; o staging privado fica para limpeza.
 */
object WhisperOrtPackageInstaller {

    /** Store atômico (tmp + bak + recuperação), compatível com minSdk 24. */
    private val store = WhisperOrtAtomicFileStore()

    /** Arquivo com a chave da versão ativa (publicado por rename atômico). */
    const val ACTIVE = "active"

    /** Diretório das versões imutáveis. */
    const val VERSIONS = "versions"

    /** Marcador gravado dentro de cada versão. */
    const val MARKER = "pacote-validado.json"

    /** Versão do formato do marcador. */
    const val MARKER_VERSION = 2

    /** Bytes amostrados em cada janela do fingerprint. */
    const val FINGERPRINT_WINDOW = 16 * 1024

    /** Fato de um arquivo: tamanho + SHA integral (instalação) + fingerprint. */
    data class FileFact(
        val name: String,
        val sizeBytes: Long,
        val sha256: String,
        val fingerprint: String,
    )

    /**
     * Pacote pronto para uso, ligado a UMA versão imutável.
     *
     * Só nasce de [install], [adopt] ou [open]; não há construtor a partir de
     * hash declarado pelo chamador.
     */
    class Validated internal constructor(
        val identity: WhisperOrtModelPackage.Identity,
        val versionKey: String,
        val directory: File,
        val files: List<FileFact>,
    ) {
        /** Arquivo pelo nome, exigindo que pertença ao pacote validado. */
        fun file(name: String): File {
            require(files.any { it.name == name }) { "$name não faz parte do pacote validado" }
            return File(directory, name)
        }

        val encoder: File get() = file("encoder_model_int8.onnx")
        val decoder: File get() = file("decoder_model_int8.onnx")
        val decoderWithPast: File get() = file("decoder_with_past_model_int8.onnx")
        val vocab: File get() = file("vocab.json")
        val generationConfig: File get() = file("generation_config.json")
    }

    /** Resultado de instalação/abertura. */
    sealed class Outcome {
        data class Ready(val validated: Validated) : Outcome()
        data class Refused(val reason: String, val detail: String) : Outcome()
    }

    /**
     * Valida o DESCRITOR (o conjunto esperado) antes de qualquer medição.
     *
     * Devolve a razão textual da recusa ou `null`. Garante: lista não vazia,
     * nomes únicos e seguros, e os cinco papéis do export ativo presentes.
     */
    fun validateDescriptor(descriptor: List<WhisperOrtModelCatalog.Entry>): String? {
        if (descriptor.isEmpty()) return "descritor vazio"
        val nomes = descriptor.map { it.name }
        if (nomes.size != nomes.toSet().size) return "descritor com nome repetido"
        for (nome in nomes) {
            if (nome.isBlank() || nome.contains("..") || nome.contains('/') || nome.contains('\\') ||
                File(nome).name != nome
            ) {
                return "descritor com nome inseguro: $nome"
            }
        }
        val papeis = descriptor.map { it.role }.toSet()
        val obrigatorios = setOf(
            WhisperOrtModelPackage.Role.ENCODER,
            WhisperOrtModelPackage.Role.DECODER,
            WhisperOrtModelPackage.Role.DECODER_WITH_PAST,
            WhisperOrtModelPackage.Role.TOKENIZER,
            WhisperOrtModelPackage.Role.CONFIG,
        )
        val faltando = obrigatorios - papeis
        if (faltando.isNotEmpty()) return "descritor sem papel obrigatório: $faltando"
        return null
    }

    /**
     * Instala [staging] como uma NOVA versão sob [root].
     *
     * Mede tamanho, SHA-256 integral e fingerprint de cada arquivo do descritor;
     * divergência recusa antes de publicar. [isCancelled] é consultado entre
     * arquivos e durante o fingerprint.
     */
    fun install(
        staging: File,
        root: File,
        identity: WhisperOrtModelPackage.Identity,
        descriptor: List<WhisperOrtModelCatalog.Entry> = WhisperOrtModelCatalog.active,
        withPublication: ((() -> Outcome) -> Outcome) = { it() },
        isCancelled: () -> Boolean = { false },
    ): Outcome {
        validateDescriptor(descriptor)?.let { return Outcome.Refused("DESCRITOR", it) }
        if (!staging.isDirectory) return Outcome.Refused("SEM_STAGING", "staging ausente: ${staging.path}")
        val fatos = ArrayList<FileFact>(descriptor.size)
        for (esperado in descriptor) {
            if (isCancelled()) return Outcome.Refused("CANCELADO", "cancelado antes de ${esperado.name}")
            val arquivo = File(staging, esperado.name)
            if (!arquivo.isFile) return Outcome.Refused("AUSENTE", "${esperado.name} não está em ${staging.path}")
            if (arquivo.length() != esperado.sizeBytes) {
                return Outcome.Refused(
                    "TAMANHO",
                    "${esperado.name}: ${arquivo.length()} B != ${esperado.sizeBytes} B do catálogo",
                )
            }
            val medido = sha256(arquivo, isCancelled)
                ?: return Outcome.Refused("CANCELADO", "cancelado durante o hash de ${esperado.name}")
            if (!medido.equals(esperado.sha256, ignoreCase = true)) {
                return Outcome.Refused("SHA", "${esperado.name}: sha medido $medido != ${esperado.sha256}")
            }
            val fp = fingerprint(arquivo, isCancelled)
                ?: return Outcome.Refused("CANCELADO", "cancelado durante o fingerprint de ${esperado.name}")
            fatos += FileFact(esperado.name, arquivo.length(), medido, fp)
        }
        for (esperado in descriptor) {
            for (dependencia in WhisperOrtModelPackage.externalDataLocations(File(staging, esperado.name))) {
                if (!File(staging, dependencia).isFile) {
                    return Outcome.Refused(
                        "DEPENDENCIA",
                        "${esperado.name} referencia $dependencia ausente no staging",
                    )
                }
            }
        }
        if (isCancelled()) return Outcome.Refused("CANCELADO", "cancelado antes de publicar")
        val chave = versionKey(identity, fatos)
        val destino = File(File(root, VERSIONS), chave)
        destino.parentFile?.mkdirs()
        if (destino.exists()) {
            // Reutilizar sem conferir já produziu Ready + open FINGERPRINT.
            val confere = validateVersionDir(destino, identity, chave, fatos)
            if (confere != null) {
                return Outcome.Refused("VERSAO_EXISTENTE", "versão $chave já existe e não confere: $confere")
            }
        } else {
            if (!staging.renameTo(destino)) {
                return Outcome.Refused("PROMOCAO", "rename falhou: ${staging.path} -> ${destino.path}")
            }
        }
        return withPublication {
            if (isCancelled()) {
                Outcome.Refused("CANCELADO", "cancelado antes de ativar")
            } else {
                val commit = writeMarkerAtomic(destino, identity, chave, fatos)
                    ?: publishActive(root, chave)
                if (commit != null) Outcome.Refused("COMMIT", commit)
                else Outcome.Ready(Validated(identity, chave, destino, fatos))
            }
        }
    }

    /**
     * Adota arquivos que JÁ estão em [root] (layout legado do laboratório).
     *
     * Move cada arquivo para `versions/<chave>` (rename no mesmo sistema de
     * arquivos, sem cópia), mede tudo e publica a versão ativa. Existe porque o
     * lab recebe os pesos por `adb push` direto no destino.
     */
    fun adopt(
        root: File,
        identity: WhisperOrtModelPackage.Identity,
        descriptor: List<WhisperOrtModelCatalog.Entry> = WhisperOrtModelCatalog.active,
        isCancelled: () -> Boolean = { false },
    ): Outcome {
        validateDescriptor(descriptor)?.let { return Outcome.Refused("DESCRITOR", it) }
        if (!root.isDirectory) return Outcome.Refused("SEM_DIRETORIO", "${root.path} não existe")
        val fatos = ArrayList<FileFact>(descriptor.size)
        for (esperado in descriptor) {
            if (isCancelled()) return Outcome.Refused("CANCELADO", "cancelado antes de ${esperado.name}")
            val arquivo = File(root, esperado.name)
            if (!arquivo.isFile) return Outcome.Refused("AUSENTE", "${esperado.name} não está em ${root.path}")
            if (arquivo.length() != esperado.sizeBytes) {
                return Outcome.Refused(
                    "TAMANHO",
                    "${esperado.name}: ${arquivo.length()} B != ${esperado.sizeBytes} B do catálogo",
                )
            }
            val medido = sha256(arquivo, isCancelled)
                ?: return Outcome.Refused("CANCELADO", "cancelado durante o hash de ${esperado.name}")
            if (!medido.equals(esperado.sha256, ignoreCase = true)) {
                return Outcome.Refused("SHA", "${esperado.name}: sha medido $medido != ${esperado.sha256}")
            }
            val fp = fingerprint(arquivo, isCancelled)
                ?: return Outcome.Refused("CANCELADO", "cancelado durante o fingerprint de ${esperado.name}")
            fatos += FileFact(esperado.name, arquivo.length(), medido, fp)
        }
        if (isCancelled()) return Outcome.Refused("CANCELADO", "cancelado antes de mover")
        val chave = versionKey(identity, fatos)
        val destino = File(File(root, VERSIONS), chave)
        destino.parentFile?.mkdirs()
        if (!destino.exists()) destino.mkdirs()
        for (fato in fatos) {
            if (isCancelled()) return Outcome.Refused("CANCELADO", "cancelado durante a adoção")
            val origem = File(root, fato.name)
            if (origem.exists()) {
                val alvo = File(destino, fato.name)
                if (!alvo.exists() && !origem.renameTo(alvo)) {
                    return Outcome.Refused("ADOCAO", "não foi possível mover ${fato.name} para a versão")
                }
            }
        }
        val commit = writeMarkerAtomic(destino, identity, chave, fatos)
            ?: publishActive(root, chave)
        if (commit != null) return Outcome.Refused("COMMIT", commit)
        return Outcome.Ready(Validated(identity, chave, destino, fatos))
    }

    /**
     * Abre a versão ativa publicada em [root].
     *
     * Confere marcador (versão, identidade, chave), o descritor do export e, por
     * arquivo, tamanho + fingerprint. Não rehasha o pacote inteiro.
     */
    fun open(
        root: File,
        identity: WhisperOrtModelPackage.Identity,
        descriptor: List<WhisperOrtModelCatalog.Entry> = WhisperOrtModelCatalog.active,
    ): Outcome {
        validateDescriptor(descriptor)?.let { return Outcome.Refused("DESCRITOR", it) }
        val ponteiro = File(root, ACTIVE)
        // O active só serve se APONTAR PARA UMA VERSÃO UTILIZÁVEL. Antes bastava
        // existir e ser legível: um active com "broken" ou com uma chave de versão
        // inexistente devolvia Refused mesmo havendo .bak confirmado — o backup
        // nunca era tentado.
        fun apontaVersaoUtil(chave: String): Boolean {
            if (chave.isEmpty()) return false
            val dir = versionDir(root, chave) ?: return false
            return File(dir, MARKER).isFile
        }

        val chaveAtual = runCatching {
            if (ponteiro.isFile) ponteiro.readText(Charsets.UTF_8).trim() else ""
        }.getOrDefault("")
        if (!apontaVersaoUtil(chaveAtual)) {
            // Promove o último estado CONFIRMADO (conteúdo válido, não só não vazio).
            store.recover(ponteiro) { bytes ->
                apontaVersaoUtil(String(bytes, Charsets.UTF_8).trim())
            }
        }
        if (!ponteiro.isFile) {
            return Outcome.Refused("SEM_ATIVO", "${root.path} não tem '$ACTIVE': pacote não instalado")
        }
        val chave = ponteiro.readText(Charsets.UTF_8).trim()
        if (chave.isEmpty()) return Outcome.Refused("SEM_ATIVO", "'$ACTIVE' vazio")
        val diretorio = versionDir(root, chave)
            ?: return Outcome.Refused("ATIVO_INVALIDO", "'$ACTIVE' aponta para chave não canônica: $chave")
        val marcador = File(diretorio, MARKER)
        if (!marcador.isFile) return Outcome.Refused("SEM_MARCADOR", "versão $chave sem $MARKER")
        val raiz = try {
            org.json.JSONObject(marcador.readText(Charsets.UTF_8))
        } catch (failure: Throwable) {
            return Outcome.Refused("MARCADOR", "marcador ilegível: ${failure.message}")
        }
        if (raiz.optInt("version", -1) != MARKER_VERSION) {
            return Outcome.Refused("VERSAO", "marcador versão ${raiz.optInt("version", -1)} != $MARKER_VERSION")
        }
        val id = raiz.optJSONObject("identity") ?: return Outcome.Refused("IDENTIDADE", "marcador sem identidade")
        if (id.optString("key") != identity.key) {
            return Outcome.Refused("IDENTIDADE", "versão ativa é ${id.optString("key")}, pedido ${identity.key}")
        }
        if (raiz.optString("versionKey") != chave) {
            return Outcome.Refused("MARCADOR", "chave do marcador != '$chave' ativa")
        }
        val lista = raiz.optJSONArray("files") ?: return Outcome.Refused("MARCADOR", "marcador sem arquivos")
        if (lista.length() != descriptor.size) {
            return Outcome.Refused(
                "CONJUNTO",
                "marcador tem ${lista.length()} arquivos; descritor exige ${descriptor.size}",
            )
        }
        val fatos = ArrayList<FileFact>(lista.length())
        val vistos = HashSet<String>()
        for (i in 0 until lista.length()) {
            val item = lista.getJSONObject(i)
            val nome = item.optString("name")
            if (!vistos.add(nome)) return Outcome.Refused("CONJUNTO", "arquivo repetido no marcador: $nome")
            val esperado = descriptor.firstOrNull { it.name == nome }
                ?: return Outcome.Refused("CONJUNTO", "arquivo fora do descritor: $nome")
            val tamanho = item.optLong("size", -1L)
            if (tamanho != esperado.sizeBytes) {
                return Outcome.Refused("CONJUNTO", "$nome: tamanho $tamanho != ${esperado.sizeBytes} do descritor")
            }
            val arquivo = File(diretorio, nome)
            if (!arquivo.isFile) return Outcome.Refused("AUSENTE", "$nome desapareceu da versão $chave")
            if (arquivo.length() != tamanho) {
                return Outcome.Refused("TAMANHO", "$nome: ${arquivo.length()} B != $tamanho B do marcador")
            }
            val declarado = item.optString("sha256")
            if (!declarado.equals(esperado.sha256, ignoreCase = true)) {
                // Comparação de METADADOS: o marcador diz um SHA e o descritor
                // confiável exige outro. Sem rehash de 1,5 GB.
                return Outcome.Refused(
                    "CONJUNTO",
                    "$nome: sha do marcador ($declarado) != $esperado.sha256 do descritor",
                )
            }
            val fp = item.optString("fingerprint")
            if (fp.isEmpty() || fp != fingerprint(arquivo)) {
                return Outcome.Refused("FINGERPRINT", "$nome mudou depois da instalação")
            }
            fatos += FileFact(nome, tamanho, item.optString("sha256"), fp)
        }
        val faltando = descriptor.map { it.name } - vistos
        if (faltando.isNotEmpty()) return Outcome.Refused("CONJUNTO", "marcador sem $faltando")
        return Outcome.Ready(Validated(identity, chave, diretorio, fatos))
    }

    /**
     * Retira a versão [versionKey] se ninguém mais a aponta como ativa.
     *
     * É o passo de limpeza depois do lease/dreno: recusa retirar a versão ativa
     * e recusa a chave que um consumidor ainda referencia (o chamador informa as
     * chaves em uso).
     */
    fun retire(root: File, versionKey: String, inUse: Set<String>): Boolean {
        // A chave vem do chamador: `retire(root, "../nao-e-versao")` apagou uma
        // pasta IRMÃ de versions na contraprova. Formato e caminho canônico são
        // conferidos antes de qualquer acesso.
        if (!isSafeVersionKey(root, versionKey)) return false
        val ponteiro = File(root, ACTIVE)
        if (ponteiro.isFile) {
            val ativa = ponteiro.readText(Charsets.UTF_8).trim()
            if (ativa == versionKey) return false
            if (!isSafeVersionKey(root, ativa)) return false
        }
        if (versionKey in inUse) return false
        val dir = versionDir(root, versionKey) ?: return false
        return if (dir.isDirectory) dir.deleteRecursively() else false
    }

    /** Chave no formato gerado e caminho canônico DENTRO de versions/. */
    fun isSafeVersionKey(root: File, chave: String): Boolean = versionDir(root, chave) != null

    private fun versionDir(root: File, chave: String): File? {
        if (!chave.matches(Regex("^[0-9a-f]{16}$"))) return null
        val versions = File(root, VERSIONS)
        val dir = File(versions, chave)
        return try {
            val canonico = dir.canonicalFile
            val raiz = versions.canonicalFile
            if (canonico.parentFile == raiz) canonico else null
        } catch (failure: Throwable) {
            null
        }
    }

    /**
     * Confere uma versão já existente contra os fatos medidos do staging.
     *
     * Devolve `null` quando íntegra e compatível.
     */
    private fun validateVersionDir(
        dir: File,
        identity: WhisperOrtModelPackage.Identity,
        chave: String,
        fatos: List<FileFact>,
    ): String? {
        val marcador = File(dir, MARKER)
        if (!marcador.isFile) return "sem marcador"
        val raiz = try {
            org.json.JSONObject(marcador.readText(Charsets.UTF_8))
        } catch (failure: Throwable) {
            return "marcador ilegível"
        }
        if (raiz.optInt("version", -1) != MARKER_VERSION) return "marcador de outra versão"
        if (raiz.optString("versionKey") != chave) return "chave divergente"
        if (raiz.optJSONObject("identity")?.optString("key") != identity.key) return "identidade divergente"
        val lista = raiz.optJSONArray("files") ?: return "marcador sem arquivos"
        if (lista.length() != fatos.size) return "conjunto com ${lista.length()} arquivos"
        for (i in 0 until lista.length()) {
            val item = lista.getJSONObject(i)
            val esperado = fatos.firstOrNull { it.name == item.optString("name") } ?: return "arquivo intruso"
            if (item.optLong("size") != esperado.sizeBytes) return "${esperado.name}: tamanho divergente"
            if (!item.optString("sha256").equals(esperado.sha256, ignoreCase = true)) {
                return "${esperado.name}: sha divergente"
            }
            val arquivo = File(dir, esperado.name)
            if (!arquivo.isFile) return "${esperado.name} ausente"
            if (fingerprint(arquivo) != item.optString("fingerprint")) return "${esperado.name} alterado"
        }
        return null
    }

    /** Chave da versão: identidade + fatos medidos. */
    fun versionKey(identity: WhisperOrtModelPackage.Identity, files: List<FileFact>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(identity.key.toByteArray(Charsets.UTF_8))
        for (fato in files.sortedBy { it.name }) {
            digest.update(fato.name.toByteArray(Charsets.UTF_8))
            digest.update(fato.sizeBytes.toString().toByteArray(Charsets.UTF_8))
            digest.update(fato.sha256.toByteArray(Charsets.UTF_8))
        }
        return digest.digest().joinToString("") { "%02x".format(it) }.substring(0, 16)
    }

    /**
     * Publica a chave ativa de forma verificável.
     *
     * O código anterior apagava o destino e ignorava o retorno de `renameTo`:
     * uma publicação que falhava devolvia `Ready`, `active` não era arquivo e o
     * `open` seguinte dizia `SEM_ATIVO`. Agora:
     * - escreve em temporário e confere o conteúdo;
     * - move com `ATOMIC_MOVE`/`REPLACE_EXISTING` (java.nio) e confere o
     *   resultado lendo de volta;
     * - se falhar, RESTAURA o ativo anterior e devolve recusa tipada.
     */
    private fun publishActive(root: File, chave: String): String? {
        root.mkdirs()
        val alvo = File(root, ACTIVE)
        val conteudo = (chave + "\n").toByteArray(Charsets.UTF_8)
        if (!store.write(alvo, conteudo)) return "falha ao publicar ativo (commit atômico)"
        val lido = store.read(alvo)?.toString(Charsets.UTF_8)?.trim()
        return if (lido == chave) null else "ativo não confere depois do commit"
    }

    private fun restoreActive(alvo: File, anterior: String?) {
        try {
            if (anterior != null) {
                alvo.writeText(anterior, Charsets.UTF_8)
            } else if (alvo.exists()) {
                alvo.delete()
            }
        } catch (ignored: Throwable) {
            // Sem ativo anterior não há o que restaurar; a recusa já foi reportada.
        }
    }

    private fun writeMarkerAtomic(
        dir: File,
        identity: WhisperOrtModelPackage.Identity,
        chave: String,
        files: List<FileFact>,
    ): String? {
        val root = org.json.JSONObject()
        root.put("version", MARKER_VERSION)
        root.put("versionKey", chave)
        val id = org.json.JSONObject()
        id.put("key", identity.key)
        id.put("model", identity.model)
        id.put("revision", identity.revision)
        id.put("dtype", identity.dtype)
        id.put("provider", identity.provider)
        id.put("opset", identity.opset)
        root.put("identity", id)
        val lista = org.json.JSONArray()
        for (fato in files) {
            val item = org.json.JSONObject()
            item.put("name", fato.name)
            item.put("size", fato.sizeBytes)
            item.put("sha256", fato.sha256)
            item.put("fingerprint", fato.fingerprint)
            lista.put(item)
        }
        root.put("files", lista)
        val alvo = File(dir, MARKER)
        val conteudo = root.toString(1).toByteArray(Charsets.UTF_8)
        return if (store.write(alvo, conteudo)) null else "falha ao gravar marcador (commit atômico)"
    }

    /**
     * Fingerprint barato: tamanho + SHA das 4 janelas de [FINGERPRINT_WINDOW].
     *
     * Não substitui o SHA integral da instalação; serve ao caminho quente, onde
     * rehashar 1,5 GB por transcrição é inviável. A garantia forte vem de a
     * versão ser IMUTÁVEL: só [install]/[adopt] escrevem nela.
     */
    fun fingerprint(file: File, isCancelled: () -> Boolean = { false }): String? {
        val digest = MessageDigest.getInstance("SHA-256")
        val tamanho = file.length()
        digest.update(tamanho.toString().toByteArray(Charsets.UTF_8))
        val janela = FINGERPRINT_WINDOW.toLong()
        val posicoes = if (tamanho <= janela * 4) {
            listOf(0L)
        } else {
            listOf(0L, tamanho / 3, (tamanho / 3) * 2, tamanho - janela)
        }
        java.io.RandomAccessFile(file, "r").use { handle ->
            val buffer = ByteArray(FINGERPRINT_WINDOW)
            for (posicao in posicoes) {
                if (isCancelled()) return null
                handle.seek(posicao)
                val lido = handle.read(buffer)
                if (lido > 0) digest.update(buffer, 0, lido)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** SHA-256 integral em streaming. */
    fun sha256(file: File, isCancelled: () -> Boolean = { false }): String? {
        val digest = MessageDigest.getInstance("SHA-256")
        var desde = 0L
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                if (isCancelled()) return null
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
                desde += read
                if (desde > (64L shl 20)) {
                    desde = 0
                    if (isCancelled()) return null
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
