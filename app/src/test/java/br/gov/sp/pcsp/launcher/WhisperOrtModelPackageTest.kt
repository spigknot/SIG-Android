package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Prova os erros de pacote ANTES de qualquer sessão (ORT1-B04/B07/T01),
 * endurecidos no adendo1 §1: conjunto obrigatório de componentes e leitura
 * REAL do `location` de external data.
 */
class WhisperOrtModelPackageTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val identity = WhisperOrtModelPackage.Identity(
        model = "whisper-large-v3-turbo",
        revision = "360ebcde2559d60bb474678be3c1de9ef347d01a",
        dtype = "int8",
        provider = "CPU",
        opset = 14,
    )

    private fun entry(
        name: String,
        bytes: ByteArray,
        role: WhisperOrtModelPackage.Role,
        externalDataOf: String? = null,
    ): WhisperOrtModelPackage.FileEntry {
        val file = File(temp.root, name)
        if (!file.exists()) file.writeBytes(bytes)
        return WhisperOrtModelPackage.FileEntry(
            name = name,
            sizeBytes = bytes.size.toLong(),
            sha256 = WhisperOrtModelPackage.sha256(file),
            role = role,
            externalDataOf = externalDataOf,
        )
    }

    private fun onnxWithExternal(location: String): ByteArray {
        val header = byteArrayOf(0x0A, 0x08) + "location".toByteArray(Charsets.US_ASCII)
        val value = byteArrayOf(0x12, location.length.toByte()) + location.toByteArray(Charsets.US_ASCII)
        return byteArrayOf(0x08, 0x0A) + header + value + ByteArray(32) { 0x00 }
    }

    private fun onnxMentioningData(): ByteArray =
        "grafo qualquer que cita encoder_model.onnx_data no meio".toByteArray(Charsets.US_ASCII)

    private fun completePackage(): List<WhisperOrtModelPackage.FileEntry> {
        val encoder = ByteArray(64) { 1 }
        val decoder = ByteArray(32) { 2 }
        val past = ByteArray(16) { 3 }
        val vocab = """{"a":65}""".toByteArray()
        val config = """{"max_length":448}""".toByteArray()
        return listOf(
            entry("encoder_model_int8.onnx", encoder, WhisperOrtModelPackage.Role.ENCODER),
            entry("decoder_model_int8.onnx", decoder, WhisperOrtModelPackage.Role.DECODER),
            entry("decoder_with_past_model_int8.onnx", past, WhisperOrtModelPackage.Role.DECODER_WITH_PAST),
            entry("vocab.json", vocab, WhisperOrtModelPackage.Role.TOKENIZER),
            entry("generation_config.json", config, WhisperOrtModelPackage.Role.CONFIG),
        )
    }

    @Test
    fun `pacote completo e aceito com identidade preservada`() {
        val verdict = WhisperOrtModelPackage.validate(temp.root, identity, completePackage(), verifySha = true)
        assertTrue(verdict is WhisperOrtModelPackage.Verdict.Ready)
        assertEquals(identity, (verdict as WhisperOrtModelPackage.Verdict.Ready).identity)
        assertEquals(5, verdict.files.size)
    }

    @Test
    fun `pacote so com config e recusado por componente faltante`() {
        val apenasConfig = listOf(
            entry(
                "generation_config.json", """{"max_length":448}""".toByteArray(),
                WhisperOrtModelPackage.Role.CONFIG,
            ),
        )
        val verdict = WhisperOrtModelPackage.validate(temp.root, identity, apenasConfig, verifySha = false)
        assertEquals(
            WhisperOrtModelPackage.Error.MISSING_COMPONENT,
            (verdict as WhisperOrtModelPackage.Verdict.Invalid).error,
        )
    }

    @Test
    fun `pacote sem tokenizer e recusado`() {
        val semTokenizer = completePackage().filter { it.role != WhisperOrtModelPackage.Role.TOKENIZER }
        val verdict = WhisperOrtModelPackage.validate(temp.root, identity, semTokenizer, verifySha = false)
        assertEquals(
            WhisperOrtModelPackage.Error.MISSING_COMPONENT,
            (verdict as WhisperOrtModelPackage.Verdict.Invalid).error,
        )
    }

    @Test
    fun `arquivo ausente e recusado como MISSING_FILE`() {
        val entries = listOf(
            WhisperOrtModelPackage.FileEntry("nao_existe.onnx", 10, "abc", WhisperOrtModelPackage.Role.ENCODER),
            entry("vocab.json", """{"a":65}""".toByteArray(), WhisperOrtModelPackage.Role.TOKENIZER),
            entry("generation_config.json", """{}""".toByteArray(), WhisperOrtModelPackage.Role.CONFIG),
        )
        val verdict = WhisperOrtModelPackage.validate(temp.root, identity, entries, verifySha = false)
        assertEquals(
            WhisperOrtModelPackage.Error.MISSING_FILE,
            (verdict as WhisperOrtModelPackage.Verdict.Invalid).error,
        )
    }

    @Test
    fun `tamanho diferente e recusado como SIZE_MISMATCH`() {
        File(temp.root, "modelo.onnx").writeBytes(ByteArray(8))
        val entries = listOf(
            WhisperOrtModelPackage.FileEntry("modelo.onnx", 9, "abc", WhisperOrtModelPackage.Role.DECODER),
            entry("vocab.json", """{"a":65}""".toByteArray(), WhisperOrtModelPackage.Role.TOKENIZER),
            entry("generation_config.json", """{}""".toByteArray(), WhisperOrtModelPackage.Role.CONFIG),
        )
        val verdict = WhisperOrtModelPackage.validate(temp.root, identity, entries, verifySha = false)
        assertEquals(
            WhisperOrtModelPackage.Error.SIZE_MISMATCH,
            (verdict as WhisperOrtModelPackage.Verdict.Invalid).error,
        )
    }

    @Test
    fun `hash diferente e recusado como SHA_MISMATCH`() {
        File(temp.root, "modelo.onnx").writeBytes(ByteArray(8))
        val entries = listOf(
            WhisperOrtModelPackage.FileEntry("modelo.onnx", 8, "0".repeat(64), WhisperOrtModelPackage.Role.DECODER),
            entry("vocab.json", """{"a":65}""".toByteArray(), WhisperOrtModelPackage.Role.TOKENIZER),
            entry("generation_config.json", """{}""".toByteArray(), WhisperOrtModelPackage.Role.CONFIG),
        )
        val verdict = WhisperOrtModelPackage.validate(temp.root, identity, entries, verifySha = true)
        assertEquals(
            WhisperOrtModelPackage.Error.SHA_MISMATCH,
            (verdict as WhisperOrtModelPackage.Verdict.Invalid).error,
        )
    }

    @Test
    fun `external data com location real e exigido no pacote`() {
        val grafo = onnxWithExternal("weights.bin")
        File(temp.root, "encoder_model_int8.onnx").writeBytes(grafo)
        val entries = listOf(
            entry("encoder_model_int8.onnx", grafo, WhisperOrtModelPackage.Role.ENCODER),
            entry("decoder_model_int8.onnx", ByteArray(8) { 2 }, WhisperOrtModelPackage.Role.DECODER),
            entry("vocab.json", """{"a":65}""".toByteArray(), WhisperOrtModelPackage.Role.TOKENIZER),
            entry("generation_config.json", """{}""".toByteArray(), WhisperOrtModelPackage.Role.CONFIG),
        )
        val file = File(temp.root, "encoder_model_int8.onnx")
        assertEquals(listOf("weights.bin"), WhisperOrtModelPackage.externalDataLocations(file))
        val verdict = WhisperOrtModelPackage.validate(
            temp.root, identity, entries, verifySha = false, requireWithPast = false,
        )
        assertEquals(
            WhisperOrtModelPackage.Error.EXTERNAL_DATA_MISSING,
            (verdict as WhisperOrtModelPackage.Verdict.Invalid).error,
        )
    }

    @Test
    fun `mencao a onnx_data sem external data real nao e confundida`() {
        val grafo = onnxMentioningData()
        File(temp.root, "encoder_model_int8.onnx").writeBytes(grafo)
        val file = File(temp.root, "encoder_model_int8.onnx")
        assertTrue(WhisperOrtModelPackage.externalDataLocations(file).isEmpty())
        val entries = listOf(
            entry("encoder_model_int8.onnx", grafo, WhisperOrtModelPackage.Role.ENCODER),
            entry("decoder_model_int8.onnx", ByteArray(8) { 2 }, WhisperOrtModelPackage.Role.DECODER),
            entry("vocab.json", """{"a":65}""".toByteArray(), WhisperOrtModelPackage.Role.TOKENIZER),
            entry("generation_config.json", """{}""".toByteArray(), WhisperOrtModelPackage.Role.CONFIG),
        )
        val verdict = WhisperOrtModelPackage.validate(
            temp.root, identity, entries, verifySha = false, requireWithPast = false,
        )
        assertTrue(verdict is WhisperOrtModelPackage.Verdict.Ready)
    }

    @Test
    fun `external data presente e declarado fecha o pacote`() {
        val grafo = onnxWithExternal("weights.bin")
        val pesos = ByteArray(64) { 7 }
        File(temp.root, "encoder_model_int8.onnx").writeBytes(grafo)
        File(temp.root, "weights.bin").writeBytes(pesos)
        val entries = listOf(
            entry("encoder_model_int8.onnx", grafo, WhisperOrtModelPackage.Role.ENCODER),
            entry(
                "weights.bin", pesos, WhisperOrtModelPackage.Role.EXTERNAL_DATA,
                externalDataOf = "encoder_model_int8.onnx",
            ),
            entry("decoder_model_int8.onnx", ByteArray(8) { 2 }, WhisperOrtModelPackage.Role.DECODER),
            entry("vocab.json", """{"a":65}""".toByteArray(), WhisperOrtModelPackage.Role.TOKENIZER),
            entry("generation_config.json", """{}""".toByteArray(), WhisperOrtModelPackage.Role.CONFIG),
        )
        val verdict = WhisperOrtModelPackage.validate(
            temp.root, identity, entries, verifySha = true, requireWithPast = false,
        )
        assertTrue(verdict is WhisperOrtModelPackage.Verdict.Ready)
    }

    @Test
    fun `external data declarado sem dono e recusado`() {
        val pesos = ByteArray(16) { 5 }
        File(temp.root, "weights.bin").writeBytes(pesos)
        val entries = completePackage() + entry(
            "weights.bin", pesos, WhisperOrtModelPackage.Role.EXTERNAL_DATA, externalDataOf = null,
        )
        val verdict = WhisperOrtModelPackage.validate(temp.root, identity, entries, verifySha = false)
        assertEquals(
            WhisperOrtModelPackage.Error.EXTERNAL_DATA_MISSING,
            (verdict as WhisperOrtModelPackage.Verdict.Invalid).error,
        )
    }

    @Test
    fun `nome com caminho relativo e recusado como PATH_UNSAFE`() {
        val entries = listOf(
            WhisperOrtModelPackage.FileEntry("../fora.onnx", 1, "abc", WhisperOrtModelPackage.Role.ENCODER),
        )
        val verdict = WhisperOrtModelPackage.validate(temp.root, identity, entries, verifySha = false)
        assertEquals(
            WhisperOrtModelPackage.Error.PATH_UNSAFE,
            (verdict as WhisperOrtModelPackage.Verdict.Invalid).error,
        )
    }

    @Test
    fun `manifesto vazio nao e pacote valido`() {
        val verdict = WhisperOrtModelPackage.validate(temp.root, identity, emptyList(), verifySha = false)
        assertEquals(
            WhisperOrtModelPackage.Error.EMPTY_PACKAGE,
            (verdict as WhisperOrtModelPackage.Verdict.Invalid).error,
        )
    }

    @Test
    fun `promocao recusa sobrescrever pacote ativo e move quando livre`() {
        val staging = temp.newFolder("staging")
        File(staging, "modelo.onnx").writeBytes(ByteArray(4))
        val target = File(temp.root, "ativo")

        assertTrue(WhisperOrtModelPackage.publish(staging, target).isSuccess)
        assertTrue(File(target, "modelo.onnx").isFile)

        val second = temp.newFolder("staging2")
        val failure = WhisperOrtModelPackage.publish(second, target)
        assertTrue(failure.isFailure)
        assertTrue(File(target, "modelo.onnx").isFile)
    }
}
