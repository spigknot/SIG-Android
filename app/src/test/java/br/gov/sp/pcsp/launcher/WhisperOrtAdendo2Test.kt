package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Regressão do adendo2: EOS na primeira posição, default real do export,
 * vocabulário parcial, varredura integral de external data, variante com
 * `with_past` e catálogo restrito.
 */
class WhisperOrtAdendo2Test {

    @get:Rule
    val temp = TemporaryFolder()

    private fun tokenizer(): WhisperOrtTokenizer = WhisperOrtTokenizer.fromSubset(
        mapOf("a" to 65, "b" to 66, "\u0120" to 220, "z" to 51_865),
    )

    private fun logits(vararg pairs: Pair<Int, Float>): FloatArray {
        val out = FloatArray(51_866) { -1f }
        for ((id, value) in pairs) out[id] = value
        return out
    }

    private class Scripted(private val steps: List<FloatArray>) : WhisperOrtBackend {
        private var index = 0
        var encodeCalls = 0
        override fun encode(logMel: FloatArray): FloatArray {
            encodeCalls++
            return FloatArray(1_500 * 1_280)
        }

        override fun start(hidden: FloatArray, prompt: IntArray): FloatArray = steps[index++]
        override fun step(token: Int): FloatArray = steps[index++]
        override fun close() = Unit
    }

    // ---- EOS na primeira posição -------------------------------------------
    @Test
    fun `eos com logit maior e barrado na posicao 0 pela config`() {
        val config = WhisperOrtGenerationConfig(
            suppressTokens = emptySet(),
            beginSuppressTokens = setOf(WhisperOrtPrompt.END_OF_TEXT),
            maxLength = 448,
            eosTokenId = WhisperOrtPrompt.END_OF_TEXT,
            decoderStartTokenId = WhisperOrtPrompt.START_OF_TRANSCRIPT,
        )
        val backend = Scripted(
            listOf(
                logits(WhisperOrtPrompt.END_OF_TEXT to 9f, 65 to 5f),
                logits(WhisperOrtPrompt.END_OF_TEXT to 9f, 66 to 1f),
            ),
        )
        val result = WhisperOrtGenerator(tokenizer(), WhisperOrtGenerationPolicy(config = config))
            .generate(backend, FloatArray(128 * 3000))
        // posição 0: EOS suprimido -> escolhe 'a'; posição 1: EOS permitido -> para.
        assertEquals(listOf(65), result.tokens)
        assertEquals(WhisperOrtStopReason.EOS, result.stopReason)
        assertEquals("a", result.text)
    }

    @Test
    fun `default congelado tem as 88 supressoes e as 2 iniciais`() {
        val frozen = WhisperOrtGenerationConfig.frozenExport()
        assertEquals(88, frozen.suppressTokens.size)
        assertEquals(setOf(220, 50257), frozen.beginSuppressTokens)
        assertEquals(448, frozen.maxLength)
        assertTrue(frozen.isSuppressed(1, 5))
        assertTrue(frozen.isSuppressed(50257, 0))
        assertTrue(!frozen.isSuppressed(50257, 1))
    }

    @Test
    fun `policy default nao roda com supressoes vazias`() {
        val policy = WhisperOrtGenerationPolicy()
        assertEquals(88, policy.config.suppressTokens.size)
    }

    @Test
    fun `validateAgainst recusa eos divergente e teto pequeno`() {
        val cfg = WhisperOrtGenerationConfig.frozenExport()
        assertNull(WhisperOrtGenerationConfig.validateAgainst(cfg, 51_866, 50_257, 4))
        assertNotNull(WhisperOrtGenerationConfig.validateAgainst(cfg, 51_866, 50_258, 4))
        assertNotNull(
            WhisperOrtGenerationConfig.validateAgainst(cfg.copy(maxLength = 4), 51_866, 50_257, 4),
        )
        assertNotNull(
            WhisperOrtGenerationConfig.validateAgainst(cfg, 100, 50_257, 4),
        )
    }

    // ---- Vocabulário parcial ------------------------------------------------
    @Test
    fun `producao recusa vocabulario parcial e subset aceita`() {
        val parcial = mapOf("a" to 65, "b" to 66)
        assertThrows(IllegalArgumentException::class.java) {
            WhisperOrtTokenizer.fromVocab(parcial)
        }
        val subset = WhisperOrtTokenizer.fromSubset(parcial)
        assertEquals(WhisperOrtTokenizer.VOCAB_SIZE, subset.vocabSize)
    }

    // ---- External data depois dos 8 MB -------------------------------------
    @Test
    fun `location depois do primeiro bloco tambem e encontrada`() {
        val header = byteArrayOf(0x0A, 0x08) + "location".toByteArray(Charsets.US_ASCII)
        val nome = "pesos.bin"
        val value = byteArrayOf(0x12, nome.length.toByte()) + nome.toByteArray(Charsets.US_ASCII)
        // 9 MB de preenchimento e o par location no fim.
        val file = File(temp.root, "encoder_model_int8.onnx")
        file.outputStream().use { out ->
            out.write(ByteArray(9 * 1024 * 1024) { 0x00 })
            out.write(header)
            out.write(value)
        }
        assertEquals(listOf(nome), WhisperOrtModelPackage.externalDataLocations(file, chunkBytes = 1024 * 1024))
    }

    // ---- Variante com with_past --------------------------------------------
    private fun entry(name: String, bytes: ByteArray, role: WhisperOrtModelPackage.Role) =
        WhisperOrtModelPackage.FileEntry(name, bytes.size.toLong(), "0".repeat(64), role)

    @Test
    fun `variante de tres sessoes exige decoder_with_past`() {
        val root = temp.newFolder("pacote")
        val arquivos = mapOf(
            "encoder_model_int8.onnx" to WhisperOrtModelPackage.Role.ENCODER,
            "decoder_model_int8.onnx" to WhisperOrtModelPackage.Role.DECODER,
            "vocab.json" to WhisperOrtModelPackage.Role.TOKENIZER,
            "generation_config.json" to WhisperOrtModelPackage.Role.CONFIG,
        )
        val entries = arquivos.map { (nome, papel) ->
            File(root, nome).writeBytes(ByteArray(4))
            entry(nome, ByteArray(4), papel)
        }
        val semPast = WhisperOrtModelPackage.validate(
            root, WhisperOrtCatalogFixtures.identity, entries, verifySha = false, requireWithPast = true,
        )
        assertEquals(
            WhisperOrtModelPackage.Error.MISSING_COMPONENT,
            (semPast as WhisperOrtModelPackage.Verdict.Invalid).error,
        )
        val semExigencia = WhisperOrtModelPackage.validate(
            root, WhisperOrtCatalogFixtures.identity, entries, verifySha = false, requireWithPast = false,
        )
        assertTrue(semExigencia is WhisperOrtModelPackage.Verdict.Ready)
    }

    // ---- Catálogo restrito --------------------------------------------------
    @Test
    fun `catalogo recusa arquivo fora do export`() {
        val entries = WhisperOrtModelCatalog.active.map {
            WhisperOrtModelPackage.FileEntry(it.name, it.sizeBytes, it.sha256, it.role)
        }
        assertNull(WhisperOrtModelCatalog.validate(entries))
        val intruso = entries + WhisperOrtModelPackage.FileEntry(
            "modelo_caseiro_int8.onnx", 10, "0".repeat(64), WhisperOrtModelPackage.Role.DECODER,
        )
        assertNotNull(WhisperOrtModelCatalog.validate(intruso))
    }

    @Test
    fun `catalogo recusa sha divergente e conjunto incompleto`() {
        val entries = WhisperOrtModelCatalog.active.map {
            WhisperOrtModelPackage.FileEntry(it.name, it.sizeBytes, it.sha256, it.role)
        }
        val shaErrado = entries.mapIndexed { i, e ->
            if (i == 0) e.copy(sha256 = "1".repeat(64)) else e
        }
        assertNotNull(WhisperOrtModelCatalog.validate(shaErrado))
        assertNotNull(WhisperOrtModelCatalog.validate(entries.drop(1)))
        assertTrue(WhisperOrtModelCatalog.knows("encoder_model_int8.onnx"))
        assertEquals("MIT", WhisperOrtModelCatalog.LICENSE)
    }
}

/** Identidade fixa para os testes de pacote. */
private object WhisperOrtCatalogFixtures {
    val identity = WhisperOrtModelPackage.Identity(
        model = "whisper-large-v3-turbo",
        revision = "360ebcde2559d60bb474678be3c1de9ef347d01a",
        dtype = "int8",
        provider = "CPU",
        opset = 14,
    )
}
