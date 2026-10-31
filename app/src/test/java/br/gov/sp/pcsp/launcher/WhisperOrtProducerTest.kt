package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Prova o produtor comum: cache reutilizado, compute exclusivo e desfechos. */
class WhisperOrtProducerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun sha(b: ByteArray) = java.security.MessageDigest.getInstance("SHA-256")
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

    private class Fake(private val onEncode: () -> Unit = {}) : WhisperOrtBackend {
        override fun encode(logMel: FloatArray): FloatArray {
            onEncode()
            return FloatArray(1_500 * 1_280)
        }
        override fun start(hidden: FloatArray, prompt: IntArray) = FloatArray(51_866) { -1f }
        override fun step(token: Int) = FloatArray(51_866) { -1f }
        override fun close() = Unit
    }

    private fun tokenizer() = WhisperOrtTokenizer.fromSubset(mapOf("a" to 65, "z" to 51_865))

    private fun producer(cache: WhisperOrtSessionCache) =
        WhisperOrtTranscriptionProducer(cache, tokenizer())

    private fun request(id: String) = WhisperOrtTranscriptionProducer.Request(
        jobId = id,
        attempt = 1L,
        pcm = FloatArray(16_000) { 0.1f },
        policy = WhisperOrtGenerationPolicy(),
    )

    @Test
    fun `segundo job reaproveita a sessao e reporta cache hit`() {
        val cache = WhisperOrtSessionCache(factory = { _, _, _, _ -> Fake() }, open = { })
        val p = producer(cache)
        val pkg = pacoteValido()
        val a = p.transcribe(pkg, request("j1")) as WhisperOrtTranscriptionProducer.Outcome.Done
        val b = p.transcribe(pkg, request("j2")) as WhisperOrtTranscriptionProducer.Outcome.Done
        assertFalse(a.cacheHit)
        assertTrue(b.cacheHit)
        assertEquals("j2", b.jobId)
        assertEquals(1, cache.stats().opens)
    }

    @Test
    fun `compute ocupado devolve BUSY em vez de rodar em paralelo`() {
        val cache = WhisperOrtSessionCache(factory = { _, _, _, _ -> Fake() }, open = { })
        val pkg = pacoteValido()
        val chave = WhisperOrtSessionCache.keyFor(pkg.identity, pkg.versionKey, WhisperOrtTranscriptionProducer.DEFAULT_RUNTIME, WhisperOrtGenerationPolicy().config, WhisperOrtSession.DEFAULT_THREADS)
        val lease = cache.acquire(chave, pkg.versionKey, pkg.encoder, pkg.decoder, pkg.decoderWithPast)!!
        assertTrue(cache.tryBeginCompute(lease))
        val recusa = producer(cache).transcribe(pkg, request("j3"))
        assertTrue(recusa is WhisperOrtTranscriptionProducer.Outcome.Busy)
        cache.endCompute(lease)
        cache.release(lease)
    }

    @Test
    fun `custo de abertura pertence ao job mesmo se outra sessao abrir durante o compute`() {
        val pkg = pacoteValido()
        var nanos = 0L
        var aberturas = 0
        lateinit var cache: WhisperOrtSessionCache
        cache = WhisperOrtSessionCache(
            factory = { _, _, _, threads ->
                Fake {
                    if (threads == WhisperOrtSession.DEFAULT_THREADS) {
                        val outra = (cache.adquirir(pkg, threads = threads + 1)
                            as WhisperOrtSessionCache.Aquisicao.Ok).lease
                        cache.release(outra)
                    }
                }
            },
            open = { nanos += if (++aberturas == 1) 10_000_000L else 40_000_000L },
            clock = { nanos },
        )

        val resultado = producer(cache).transcribe(pkg, request("job-A")) as
            WhisperOrtTranscriptionProducer.Outcome.Done

        assertEquals("a ultima abertura global foi de outra sessao", 40L, cache.lastOpenMillis())
        assertEquals("job A preserva o seu proprio custo de abertura", 10L, resultado.sessionOpenedMillis)
        assertEquals(0, cache.stats().inUse)
        assertEquals(0, cache.stats().computing)
        assertTrue(cache.closeAll())
    }
}
