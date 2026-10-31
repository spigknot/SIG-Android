package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Contrato M3 do adendo4: conjunto obrigatório, identidade estável por versão,
 * fingerprint no caminho quente, cancelamento e retirada segura.
 */
class WhisperOrtPackageInstallerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val identity = WhisperOrtModelPackage.Identity(
        model = "whisper-large-v3-turbo",
        revision = "360ebcde2559d60bb474678be3c1de9ef347d01a",
        dtype = "int8",
        provider = "CPU",
        opset = 14,
    )

    private fun sha(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun entry(nome: String, bytes: ByteArray) = WhisperOrtModelCatalog.Entry(
        name = nome,
        sizeBytes = bytes.size.toLong(),
        sha256 = sha(bytes),
        role = when {
            nome.contains("with_past") -> WhisperOrtModelPackage.Role.DECODER_WITH_PAST
            nome.contains("encoder") -> WhisperOrtModelPackage.Role.ENCODER
            nome.contains("decoder") -> WhisperOrtModelPackage.Role.DECODER
            nome.startsWith("vocab") -> WhisperOrtModelPackage.Role.TOKENIZER
            else -> WhisperOrtModelPackage.Role.CONFIG
        },
    )

    private val base = arrayOf(
        "encoder_model_int8.onnx" to ByteArray(64) { 1 },
        "decoder_model_int8.onnx" to ByteArray(48) { 2 },
        "decoder_with_past_model_int8.onnx" to ByteArray(32) { 3 },
        "vocab.json" to """{"a":65,"b":66}""".toByteArray(),
        "generation_config.json" to """{"max_length":448}""".toByteArray(),
    )

    private fun descriptor(vararg arquivos: Pair<String, ByteArray>) =
        arquivos.map { (nome, bytes) -> entry(nome, bytes) }

    private fun staging(vararg arquivos: Pair<String, ByteArray>): File {
        val dir = temp.newFolder("staging-${System.nanoTime()}")
        for ((nome, bytes) in arquivos) File(dir, nome).writeBytes(bytes)
        return dir
    }

    // ---- conjunto obrigatório ---------------------------------------------
    @Test
    fun `descritor vazio ou incompleto e recusado antes de medir`() {
        val vazio = WhisperOrtPackageInstaller.install(
            staging(), File(temp.root, "r1"), identity, descriptor(),
        )
        assertEquals("DESCRITOR", (vazio as WhisperOrtPackageInstaller.Outcome.Refused).reason)

        val soConfig = descriptor("generation_config.json" to """{"max_length":448}""".toByteArray())
        val recusa = WhisperOrtPackageInstaller.install(
            staging("generation_config.json" to """{"max_length":448}""".toByteArray()),
            File(temp.root, "r2"), identity, soConfig,
        )
        assertEquals("DESCRITOR", (recusa as WhisperOrtPackageInstaller.Outcome.Refused).reason)
    }

    @Test
    fun `marcador com lista incompleta e recusado`() {
        val root = File(temp.root, "r3")
        val desc = descriptor(*base)
        val ok = WhisperOrtPackageInstaller.install(staging(*base), root, identity, desc)
        assertTrue(ok is WhisperOrtPackageInstaller.Outcome.Ready)
        val chave = File(root, WhisperOrtPackageInstaller.ACTIVE).readText().trim()
        val marcador = File(File(File(root, WhisperOrtPackageInstaller.VERSIONS), chave), WhisperOrtPackageInstaller.MARKER)
        // Esvazia a lista de arquivos do marcador.
        val json = org.json.JSONObject(marcador.readText())
        json.put("files", org.json.JSONArray())
        marcador.writeText(json.toString(1))
        val aberto = WhisperOrtPackageInstaller.open(root, identity, desc)
        assertEquals("CONJUNTO", (aberto as WhisperOrtPackageInstaller.Outcome.Refused).reason)
    }

    // ---- instalação e abertura --------------------------------------------
    @Test
    fun `instala medindo sha e abre pelo ativo`() {
        val root = File(temp.root, "r4")
        val desc = descriptor(*base)
        val instalado = WhisperOrtPackageInstaller.install(staging(*base), root, identity, desc)
        assertTrue(instalado is WhisperOrtPackageInstaller.Outcome.Ready)
        val aberto = WhisperOrtPackageInstaller.open(root, identity, desc)
        assertTrue(aberto is WhisperOrtPackageInstaller.Outcome.Ready)
        val v = (aberto as WhisperOrtPackageInstaller.Outcome.Ready).validated
        assertTrue(v.encoder.isFile)
    }

    @Test
    fun `vocab com um byte trocado e mesmo tamanho e recusado por sha`() {
        val alterado = """{"a":65,"b":67}""".toByteArray()
        assertEquals(base[3].second.size, alterado.size)
        val desc = descriptor(*base)
        val dir = staging(base[0], base[1], base[2], "vocab.json" to alterado, base[4])
        val r = WhisperOrtPackageInstaller.install(dir, File(temp.root, "r5"), identity, desc)
        assertEquals("SHA", (r as WhisperOrtPackageInstaller.Outcome.Refused).reason)
    }

    @Test
    fun `conjunto incompleto no staging e recusado`() {
        val desc = descriptor(*base)
        val dir = staging(base[0], base[3])
        val r = WhisperOrtPackageInstaller.install(dir, File(temp.root, "r6"), identity, desc)
        assertEquals("AUSENTE", (r as WhisperOrtPackageInstaller.Outcome.Refused).reason)
    }

    // ---- identidade estável durante uso -----------------------------------
    @Test
    fun `handle antigo continua lendo a sua versao depois de instalar outra`() {
        val root = File(temp.root, "r7")
        val descA = descriptor(*base)
        val a = WhisperOrtPackageInstaller.install(staging(*base), root, identity, descA)
        val handleA = (a as WhisperOrtPackageInstaller.Outcome.Ready).validated
        val caminhoA = handleA.encoder.absolutePath

        // Versão B: mesmos nomes, bytes diferentes e identidade nova.
        val baseB = base.map { (nome, bytes) -> nome to ByteArray(bytes.size) { 9 } }.toTypedArray()
        val identityB = identity.copy(revision = "ffffffffffffffffffffffffffffffffffffffff")
        val b = WhisperOrtPackageInstaller.install(staging(*baseB), root, identityB, descriptor(*baseB))
        val handleB = (b as WhisperOrtPackageInstaller.Outcome.Ready).validated

        // O handle antigo NÃO mudou de caminho nem de conteúdo.
        assertEquals(caminhoA, handleA.encoder.absolutePath)
        assertTrue(handleA.versionKey != handleB.versionKey)
        assertEquals(handleA.encoder.readBytes().toList(), base[0].second.toList())
        // E a retirada respeita a versão ativa e o uso.
        assertTrue(!WhisperOrtPackageInstaller.retire(root, handleB.versionKey, emptySet()))
        assertTrue(!WhisperOrtPackageInstaller.retire(root, handleA.versionKey, setOf(handleA.versionKey)))
        assertTrue(WhisperOrtPackageInstaller.retire(root, handleA.versionKey, emptySet()))
    }

    // ---- caminho quente ----------------------------------------------------
    @Test
    fun `alteracao de mesmo tamanho dentro da janela invalida o fingerprint`() {
        val root = File(temp.root, "r8")
        val desc = descriptor(*base)
        val instalado = WhisperOrtPackageInstaller.install(staging(*base), root, identity, desc)
        val v = (instalado as WhisperOrtPackageInstaller.Outcome.Ready).validated
        assertTrue(WhisperOrtPackageInstaller.open(root, identity, desc) is WhisperOrtPackageInstaller.Outcome.Ready)

        // Troca um byte na primeira janela (amostrada), mantendo o tamanho.
        val arquivo = v.encoder
        val bytes = arquivo.readBytes()
        bytes[10] = 0x7F
        arquivo.writeBytes(bytes)
        val aberto = WhisperOrtPackageInstaller.open(root, identity, desc)
        assertEquals("FINGERPRINT", (aberto as WhisperOrtPackageInstaller.Outcome.Refused).reason)
    }

    @Test
    fun `sem ativo e sem marcador nao abre`() {
        val vazio = temp.newFolder("vazio")
        val r = WhisperOrtPackageInstaller.open(vazio, identity, descriptor(*base))
        assertEquals("SEM_ATIVO", (r as WhisperOrtPackageInstaller.Outcome.Refused).reason)
    }

    // ---- cancelamento ------------------------------------------------------
    @Test
    fun `cancelamento antes de publicar conserva a versao ativa`() {
        val root = File(temp.root, "r9")
        val desc = descriptor(*base)
        val a = WhisperOrtPackageInstaller.install(staging(*base), root, identity, desc)
        val chaveA = (a as WhisperOrtPackageInstaller.Outcome.Ready).validated.versionKey

        val baseB = base.map { (nome, bytes) -> nome to ByteArray(bytes.size) { 7 } }.toTypedArray()
        val cancelado = WhisperOrtPackageInstaller.install(
            staging(*baseB), root, identity.copy(revision = "b"), descriptor(*baseB),
        ) { true }
        assertEquals("CANCELADO", (cancelado as WhisperOrtPackageInstaller.Outcome.Refused).reason)
        assertEquals(chaveA, File(root, WhisperOrtPackageInstaller.ACTIVE).readText().trim())
    }

    // ---- adoção (laboratório) ---------------------------------------------
    @Test
    fun `adocao move para versao imutavel e publica ativo`() {
        val root = temp.newFolder("lab")
        val desc = descriptor(*base)
        for ((nome, bytes) in base) File(root, nome).writeBytes(bytes)
        val adotado = WhisperOrtPackageInstaller.adopt(root, identity, desc)
        assertTrue(adotado is WhisperOrtPackageInstaller.Outcome.Ready)
        assertTrue(File(root, WhisperOrtPackageInstaller.ACTIVE).isFile)
        // Os arquivos saíram da raiz e vivem na versão.
        assertTrue(!File(root, "vocab.json").isFile)
        assertTrue(WhisperOrtPackageInstaller.open(root, identity, desc) is WhisperOrtPackageInstaller.Outcome.Ready)
    }

    @Test
    fun `identidade diferente da ativa e recusada`() {
        val root = File(temp.root, "r10")
        val desc = descriptor(*base)
        WhisperOrtPackageInstaller.install(staging(*base), root, identity, desc)
        val outra = identity.copy(dtype = "fp32")
        val r = WhisperOrtPackageInstaller.open(root, outra, desc)
        assertEquals("IDENTIDADE", (r as WhisperOrtPackageInstaller.Outcome.Refused).reason)
    }


    // ---- adendo5: transação, SHA declarado, versão existente, confinamento ----
    @Test
    fun `retire nao escapa de versions mesmo com chave maliciosa`() {
        val root = File(temp.root, "r11")
        val desc = descriptor(*base)
        WhisperOrtPackageInstaller.install(staging(*base), root, identity, desc)
        val irma = File(root, "nao-e-versao")
        irma.mkdirs()
        File(irma, "x.txt").writeText("x")

        assertTrue(!WhisperOrtPackageInstaller.retire(root, "../nao-e-versao", emptySet()))
        assertTrue(irma.isDirectory)
        assertTrue(!WhisperOrtPackageInstaller.retire(root, "versions/../../nao-e-versao", emptySet()))
        assertTrue(irma.isDirectory)
        assertTrue(!WhisperOrtPackageInstaller.isSafeVersionKey(root, "../fora"))
        assertTrue(!WhisperOrtPackageInstaller.isSafeVersionKey(root, "ZZZZ"))
    }

    @Test
    fun `open recusa descritor que exige outro sha para o mesmo arquivo`() {
        val root = File(temp.root, "r12")
        val desc = descriptor(*base)
        WhisperOrtPackageInstaller.install(staging(*base), root, identity, desc)
        // Descritor confiável diferente: mesmo nome/tamanho, SHA de outro conteúdo.
        val outro = desc.map { e ->
            if (e.name == "vocab.json") e.copy(sha256 = "0".repeat(64)) else e
        }
        val aberto = WhisperOrtPackageInstaller.open(root, identity, outro)
        assertEquals("CONJUNTO", (aberto as WhisperOrtPackageInstaller.Outcome.Refused).reason)
    }

    @Test
    fun `install nao reutiliza versao corrompida`() {
        val root = File(temp.root, "r13")
        val desc = descriptor(*base)
        val a = WhisperOrtPackageInstaller.install(staging(*base), root, identity, desc)
        val v = (a as WhisperOrtPackageInstaller.Outcome.Ready).validated
        // Corrompe a versão já publicada (mesmo nome/tamanho, byte na janela).
        val arquivo = v.encoder
        val bytes = arquivo.readBytes()
        bytes[4] = 0x55
        arquivo.writeBytes(bytes)

        val dir = staging(*base)
        val r = WhisperOrtPackageInstaller.install(dir, root, identity, desc)
        assertEquals(
            "VERSAO_EXISTENTE",
            (r as WhisperOrtPackageInstaller.Outcome.Refused).reason,
        )
        // O staging não foi consumido e a versão ativa segue a mesma (corrompida).
        assertEquals(base[0].second.size.toLong(), File(dir, "encoder_model_int8.onnx").length())
        assertEquals(v.versionKey, File(root, WhisperOrtPackageInstaller.ACTIVE).readText().trim())
    }

    @Test
    fun `falha ao publicar ativo devolve recusa e nao reporta Ready`() {
        val root = File(temp.root, "r14")
        val desc = descriptor(*base)
        // Bloqueia o caminho de `active` com um DIRETÓRIO NÃO VAZIO: apagar falha
        // e o movimento não pode ser concluído.
        val bloqueio = File(root, WhisperOrtPackageInstaller.ACTIVE)
        bloqueio.mkdirs()
        File(bloqueio, "ocupado.txt").writeText("x")
        val r = WhisperOrtPackageInstaller.install(staging(*base), root, identity, desc)
        assertEquals("COMMIT", (r as WhisperOrtPackageInstaller.Outcome.Refused).reason)
        val aberto = WhisperOrtPackageInstaller.open(root, identity, desc)
        assertEquals(
            "SEM_ATIVO",
            (aberto as WhisperOrtPackageInstaller.Outcome.Refused).reason,
        )
    }

    // ---- tokenizer ---------------------------------------------------------
    @Test
    fun `vocab com buraco no 67 e recusado em producao`() {
        val completo = HashMap<String, Int>(50_257)
        for (id in 0 until 50_257) completo["tok$id"] = id
        completo.remove("tok67")
        completo["tok67_fake"] = 50_256
        assertEquals(50_257, completo.size)
        assertNotNull(runCatching { WhisperOrtTokenizer.fromVocab(completo) }.exceptionOrNull())
        assertNotNull(WhisperOrtTokenizer.fromSubset(completo))
    }

    @Test
    fun `vocab completo e aceito em producao`() {
        val completo = HashMap<String, Int>(50_257)
        for (id in 0 until 50_257) completo["tok$id"] = id
        val tokenizer = WhisperOrtTokenizer.fromVocab(completo)
        assertEquals(WhisperOrtTokenizer.VOCAB_SIZE, tokenizer.vocabSize)
        assertNull(WhisperOrtPrompt.validate(WhisperOrtPrompt.portuguese(), tokenizer.vocabSize))
    }
}
