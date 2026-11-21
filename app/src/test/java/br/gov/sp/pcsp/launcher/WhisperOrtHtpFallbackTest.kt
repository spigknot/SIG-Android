package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/**
 * R100 §5.1 — troca de rota no produtor, em JVM.
 *
 * Prova os dois gatilhos de fallback que a ordem exige (abrir e rodar) e o
 * caminho felizardão: o desfecho é sempre um resultado da CPU **sem exceção**, e
 * o pedido transcrito é o MESMO (mesmo job/attempt/PCM).
 */
class WhisperOrtHtpFallbackTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(b).joinToString("") { "%02x".format(it) }

    private fun pacoteValido(): WhisperOrtPackageInstaller.Validated {
        val base = arrayOf(
            "encoder_model_int8.onnx" to ByteArray(32) { 1 },
            "decoder_model_int8.onnx" to ByteArray(24) { 2 },
            "decoder_with_past_model_int8.onnx" to ByteArray(16) { 3 },
            "vocab.json" to """{"a":65}""".toByteArray(),
            "generation_config.json" to """{"max_length":448}""".toByteArray(),
        )
        val cat = base.map { (n, b) ->
            WhisperOrtModelCatalog.Entry(n, b.size.toLong(), sha(b), when {
                n.contains("with_past") -> WhisperOrtModelPackage.Role.DECODER_WITH_PAST
                n.contains("encoder") -> WhisperOrtModelPackage.Role.ENCODER
                n.contains("decoder") -> WhisperOrtModelPackage.Role.DECODER
                n.startsWith("vocab") -> WhisperOrtModelPackage.Role.TOKENIZER
                else -> WhisperOrtModelPackage.Role.CONFIG
            })
        }
        val staging = temp.newFolder("stg")
        base.forEach { (n, b) -> File(staging, n).writeBytes(b) }
        val id = WhisperOrtModelPackage.Identity("m", "r", "int8", "CPU", 14)
        val r = WhisperOrtPackageInstaller.install(staging, File(temp.root, "pkg"), id, cat)
        return (r as WhisperOrtPackageInstaller.Outcome.Ready).validated
    }

    private open class Fake(private val rotaInterna: String) : WhisperOrtBackend {
        override fun encode(logMel: FloatArray) = FloatArray(1_500 * 1_280)
        override fun start(hidden: FloatArray, prompt: IntArray) = FloatArray(51_866) { -1f }
        override fun step(token: Int) = FloatArray(51_866) { -1f }
        override fun close() = Unit
        override val rota: String get() = rotaInterna
    }

    /** Encoder HTP que falha no run (contexto/wrapper recusado pelo QNN). */
    private class HtpQuebrado : Fake("htp") {
        override fun encode(logMel: FloatArray): FloatArray =
            throw WhisperOrtHtpFalha("encoder: OrtException: falha simulada")
    }

    private fun tokenizer() = WhisperOrtTokenizer.fromSubset(mapOf("a" to 65, "z" to 51_865))

    private fun request(id: String) = WhisperOrtTranscriptionProducer.Request(
        jobId = id,
        attempt = 7L,
        pcm = FloatArray(16_000) { 0.1f },
        policy = WhisperOrtGenerationPolicy(),
        rota = WhisperOrtHtpAdmissao.ROTA_HTP,
    )

    @Test
    fun `falha ao RODAR no HTP transcreve o mesmo pedido na CPU`() {
        val cache = WhisperOrtSessionCache(
            abrirPorRota = { rota, _, _, _, _ ->
                if (rota == WhisperOrtHtpAdmissao.ROTA_HTP) HtpQuebrado() else Fake("cpu")
            },
            open = { },
        )
        val falhas = ArrayList<String>()
        val producer = WhisperOrtTranscriptionProducer(
            cache = cache,
            tokenizer = tokenizer(),
            aoFalharHtp = { falhas.add(it) },
        )
        val desfecho = producer.transcribe(pacoteValido(), request("job-htp-run"))

        val done = desfecho as WhisperOrtTranscriptionProducer.Outcome.Done
        assertEquals(WhisperOrtHtpAdmissao.ROTA_CPU, done.rotaEfetiva)
        assertEquals("job-htp-run", done.jobId)
        assertEquals(7L, done.attempt)
        assertEquals("a falha tem de ser contada UMA vez", 1, falhas.size)
        assertTrue(falhas[0].contains("falha simulada"))
    }

    @Test
    fun `falha ao ABRIR o HTP devolve backend CPU sem excecao`() {
        val cache = WhisperOrtSessionCache(
            abrirPorRota = { rota, _, _, _, _ ->
                if (rota == WhisperOrtHtpAdmissao.ROTA_HTP) {
                    throw WhisperOrtHtpRota.Recusa("PACOTE_AUSENTE")
                }
                Fake("cpu")
            },
            open = { },
        )
        val falhas = ArrayList<String>()
        val producer = WhisperOrtTranscriptionProducer(
            cache = cache,
            tokenizer = tokenizer(),
            aoFalharHtp = { falhas.add(it) },
        )
        val desfecho = producer.transcribe(pacoteValido(), request("job-htp-abrir"))
        val done = desfecho as WhisperOrtTranscriptionProducer.Outcome.Done
        assertEquals(WhisperOrtHtpAdmissao.ROTA_CPU, done.rotaEfetiva)
        assertEquals(1, falhas.size)
        assertTrue(falhas[0].contains("PACOTE_AUSENTE"))
    }

    @Test
    fun `HTP que roda inteiro reporta a rota htp e nao chama fallback`() {
        val cache = WhisperOrtSessionCache(
            abrirPorRota = { rota, _, _, _, _ -> Fake(rota) },
            open = { },
        )
        var falhou = 0
        var sucesso = 0
        val producer = WhisperOrtTranscriptionProducer(
            cache = cache,
            tokenizer = tokenizer(),
            aoFalharHtp = { falhou += 1 },
            aoSucessoHtp = { sucesso += 1 },
        )
        val desfecho = producer.transcribe(pacoteValido(), request("job-htp-ok"))
        val done = desfecho as WhisperOrtTranscriptionProducer.Outcome.Done
        assertEquals(WhisperOrtHtpAdmissao.ROTA_HTP, done.rotaEfetiva)
        assertEquals(0, falhou)
        assertEquals(1, sucesso)
    }

    @Test
    fun `rota hibrida abre com as threads capturadas no job`() {
        val aberturas = mutableListOf<Pair<String, Int>>()
        val cache = WhisperOrtSessionCache(
            abrirPorRota = { rota, _, _, _, threads ->
                aberturas.add(rota to threads)
                Fake(rota)
            },
            open = { },
        )
        val rota = WhisperOrtHtpAdmissao.ROTA_HTP_CPU
        val producer = WhisperOrtTranscriptionProducer(cache = cache, tokenizer = tokenizer())
        val pacote = pacoteValido()
        for (threads in listOf(1, 4, 8)) {
            val pedido = request("hibrido-$threads").copy(rota = rota, threads = threads)
            val resultado = producer.transcribe(pacote, pedido) as WhisperOrtTranscriptionProducer.Outcome.Done
            assertEquals(rota, resultado.rotaEfetiva)
        }
        assertEquals(listOf(rota to 1, rota to 4, rota to 8), aberturas)
    }

    @Test
    fun `chaves de cache de rotas diferentes nao colidem`() {
        val pkg = pacoteValido()
        val cpu = WhisperOrtSessionCache.keyFor(
            pkg.identity, pkg.versionKey, "rt", WhisperOrtGenerationPolicy().config, 4,
            rota = WhisperOrtHtpAdmissao.ROTA_CPU,
        )
        val htp = WhisperOrtSessionCache.keyFor(
            pkg.identity, pkg.versionKey, "rt", WhisperOrtGenerationPolicy().config, 4,
            rota = WhisperOrtHtpAdmissao.ROTA_HTP,
        )
        assertNotSame(cpu, htp)
        assertTrue(cpu.asString() != htp.asString())
    }
}
