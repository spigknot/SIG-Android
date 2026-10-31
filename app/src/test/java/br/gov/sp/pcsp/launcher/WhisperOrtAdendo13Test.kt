package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Adendo 13 — dreno físico (§3), aquisição atômica (§2.6), vínculo único (§2.2)
 * e temporal restante (§5), com callbacks controlados (sem ONNX, sem FFmpeg).
 */
class WhisperOrtAdendo13Test {

    @get:Rule
    val temp = TemporaryFolder()

    // ---- motor controlado -------------------------------------------------

    private class MotorControlado : WhisperOrtMediaConverter.Motor {
        data class Viva(override val id: Long = 77L) : WhisperOrtMediaConverter.Sessao

        var aoTerminar: ((WhisperOrtMediaConverter.Final) -> Unit)? = null
        val canceladas = mutableListOf<Long>()
        /** Quando `false`, cancelar NÃO dispara o callback (sessão segue ativa). */
        var cancelaTermina = true
        /** Quando `true`, nunca chama o callback sozinho (só via [terminarTarde]). */
        var nuncaTermina = false
        /** Quando `true`, a sessão escreve saída parcial ao iniciar. */
        var simularSaidaParcial = false

        override fun iniciar(
            argumentos: Array<String>,
            aoTerminar: (WhisperOrtMediaConverter.Final) -> Unit,
        ): WhisperOrtMediaConverter.Sessao {
            this.aoTerminar = aoTerminar
            if (simularSaidaParcial) {
                java.io.File(argumentos.last()).writeBytes(byteArrayOf(9, 9, 9))
            }
            return Viva()
        }

        override fun cancelar(sessao: WhisperOrtMediaConverter.Sessao) {
            canceladas.add(sessao.id)
            if (cancelaTermina) {
                aoTerminar?.invoke(WhisperOrtMediaConverter.Final(ok = false, cancelada = true, logsCauda = ""))
            }
        }

        fun terminarTarde(ok: Boolean) {
            aoTerminar?.invoke(WhisperOrtMediaConverter.Final(ok = ok, cancelada = false, logsCauda = ""))
        }
    }

    private fun origem(): File = File(temp.root, "in.mp3").apply { writeBytes(byteArrayOf(1, 2, 3)) }
    private fun destino(): File = File(temp.root, "out.wav")

    @Test
    fun `cancelar durante a conversao devolve Cancelada sem tocar no global`() {
        val motor = MotorControlado()
        var sondagens = 0
        val r = WhisperOrtMediaConverter.converterParaWav16kMono(
            origem(), destino(),
            isCancelada = { ++sondagens > 1 },
            motor = motor,
        )
        assertTrue("deveria ser Cancelada, veio $r", r is WhisperOrtMediaConverter.Resultado.Cancelada)
        assertEquals("só a sessão do job pode ser cancelada", listOf(77L), motor.canceladas)
        assertFalse("arquivo parcial não pode ficar", destino().exists())
    }

    @Test
    fun `prazo sem callback devolve SessaoAtiva e mantem o arquivo`() {
        val motor = MotorControlado().apply { nuncaTermina = true }
        val saidas = AtomicInteger(0)
        val r = WhisperOrtMediaConverter.converterParaWav16kMono(
            origem(), destino(), motor = motor, timeoutMs = 300L,
        )
        assertTrue("deveria ser SessaoAtiva, veio $r", r is WhisperOrtMediaConverter.Resultado.SessaoAtiva)
        val ativa = r as WhisperOrtMediaConverter.Resultado.SessaoAtiva
        assertEquals(77L, ativa.sessaoId)
        // O callback tardio finaliza: esperarFim desbloqueia e o dono limpa UMA vez.
        var fim: WhisperOrtMediaConverter.Final? = null
        val vigia = Thread {
            fim = ativa.esperarFim()
            saidas.incrementAndGet()
        }.apply { isDaemon = true; start() }
        motor.terminarTarde(ok = true)
        vigia.join(5_000)
        assertNotNull("esperarFim deveria desbloquear no callback tardio", fim)
        assertTrue(fim!!.ok)
        assertEquals(1, saidas.get())
    }

    @Test
    fun `cancelar sem callback nao apaga nem libera`() {
        val motor = MotorControlado().apply { cancelaTermina = false }
        destino().writeBytes(byteArrayOf(9, 9))
        val r = WhisperOrtMediaConverter.converterParaWav16kMono(
            origem(), destino(),
            isCancelada = { true },
            motor = motor,
        )
        // Pré-cancelamento imediato retorna Cancelada antes de iniciar; para cobrir o
        // caminho "cancelado sem callback", o pedido chega após o início.
        assertTrue(r is WhisperOrtMediaConverter.Resultado.Cancelada)
    }

    @Test
    fun `cancelar apos o inicio sem callback vira SessaoAtiva com arquivo preservado`() {
        // O motor simula a sessão escrevendo saída parcial (como o stub da
        // contraprova do especialista): o arquivo em escrita não pode sumir.
        val motor = MotorControlado().apply { cancelaTermina = false; simularSaidaParcial = true }
        var sondagens = 0
        val saida = destino()
        val r = WhisperOrtMediaConverter.converterParaWav16kMono(
            origem(), saida,
            isCancelada = { ++sondagens > 1 },
            motor = motor,
            timeoutMs = 30_000L,
        )
        assertTrue("sem callback, o cancelamento não encerra o recurso: $r", r is WhisperOrtMediaConverter.Resultado.SessaoAtiva)
        assertEquals(listOf(77L), motor.canceladas)
        assertEquals("saída parcial em escrita não pode ser apagada", 3L, saida.length())
    }

    // ---- aquisição atômica (§2.6) ------------------------------------------

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
        val staging = temp.newFolder("stg13")
        base.forEach { (n, b) -> File(staging, n).writeBytes(b) }
        val id = WhisperOrtModelPackage.Identity("m", "r", "int8", "CPU", 14)
        val r = WhisperOrtPackageInstaller.install(staging, File(temp.root, "pkg13"), id, cat)
        return (r as WhisperOrtPackageInstaller.Outcome.Ready).validated
    }

    private class BackendFalso : WhisperOrtBackend {
        override fun encode(logMel: FloatArray) = FloatArray(1_500 * 1_280)
        override fun start(hidden: FloatArray, prompt: IntArray) = FloatArray(51_866) { -1f }
        override fun step(token: Int) = FloatArray(51_866) { -1f }
        override fun close() = Unit
    }

    @Test
    fun `falha de abertura carrega a causa da propria tentativa`() {
        val pkg = pacoteValido()
        val cache = WhisperOrtSessionCache(
            factory = { _, _, _, _ -> throw IllegalStateException("disco cheio") },
            open = { },
        )
        val r = cache.adquirir(pkg)
        assertTrue("deveria ser Recusada, veio $r", r is WhisperOrtSessionCache.Aquisicao.Recusada)
        val motivo = (r as WhisperOrtSessionCache.Aquisicao.Recusada).motivo
        assertTrue("causa da factory deve viajar no retorno (veio '$motivo')", motivo.contains("disco cheio"))
    }

    @Test
    fun `sucesso devolve lease com fatos sem ler global`() {
        val pkg = pacoteValido()
        val cache = WhisperOrtSessionCache(factory = { _, _, _, _ -> BackendFalso() }, open = { })
        val r = cache.adquirir(pkg)
        assertTrue(r is WhisperOrtSessionCache.Aquisicao.Ok)
        val lease = (r as WhisperOrtSessionCache.Aquisicao.Ok).lease
        assertFalse(lease.hit)
        assertTrue("miss registra o custo de abertura no lease", lease.openMillis >= 0L)
        val r2 = cache.adquirir(pkg)
        assertTrue(((r2 as WhisperOrtSessionCache.Aquisicao.Ok).lease.hit))
    }

    // ---- vínculo único (§2.2) ----------------------------------------------

    private fun hash(s: String) = sha(s.toByteArray(Charsets.UTF_8))

    @Test
    fun `segunda vinculacao e recusada e nao reescreve`() {
        val admissao = WhisperOrtAdmission()
        val job = (admissao.admit("j1", 1L, "ORT", "CPU", hash("selecao")) as WhisperOrtAdmission.Admitted.Ok).job
        assertTrue(admissao.vincularEntrada(job, hash("medido")))
        assertEquals(hash("medido"), job.inputSha256)
        assertFalse("reescrita direta tem de ser recusada", admissao.vincularEntrada(job, hash("outro")))
        assertEquals("identidade não pode mudar", hash("medido"), job.inputSha256)
    }

    // ---- temporal restante (§5) ----------------------------------------------

    private val noTs = WhisperOrtPrompt.NO_TIMESTAMPS
    private val begin = WhisperOrtSegments.timestampBegin(noTs)
    private fun ts(seg: Double): Int = begin + Math.round(seg / 0.02).toInt()
    private fun decode(ids: IntArray): String =
        ids.filter { it < begin }.joinToString(" ") { if (it == 65) "a" else if (it == 66) "b" else "c" }

    @Test
    fun `endSeconds nao passa do intervalo util`() {
        // Timestamp de 25 s numa janela de 1 s útil: o segmento não pode terminar
        // além do áudio que existe.
        val tokens = listOf(ts(0.0), 65, ts(25.0), ts(25.0))
        val r = WhisperOrtSegments.retrieve(tokens, noTs, 51_866, 0.0, 1.0, ::decode)
        for (s in r.segments) {
            assertTrue("fim ${s.endSeconds} além do útil (1 s)", s.endSeconds <= 1.0 + 1e-9)
            assertTrue("início ${s.startSeconds} além do útil", s.startSeconds <= 1.0 + 1e-9)
        }
        assertTrue(r.seekSeconds <= 1.0 + 1e-9)
    }

    @Test
    fun `cauda carrega a origem temporal do ultimo fechamento`() {
        // [ts0 a ts2 ts2 b] -> segmento "a" [0,2], cauda "b" com origem em 2 s.
        val tokens = listOf(ts(0.0), 65, ts(2.0), ts(2.0), 66)
        val r = WhisperOrtSegments.retrieve(tokens, noTs, 51_866, 0.0, 30.0, ::decode)
        assertEquals("b", r.pendingText)
        assertEquals(2.0, r.pendingFromSeconds, 1e-9)
    }

    private fun tokenizer() = WhisperOrtTokenizer.fromSubset(
        mapOf("a" to 65, "b" to 66, "\u0120" to 220),
    )

    @Test
    fun `origem nao coberta vira PARTIAL mesmo com texto igual`() {
        // Janela 1 (orçamento de 5 tokens): "a" fechado em 1 s, cauda "b" com
        // origem em 1 s. O orçamento corta a geração e dispensa depender do EOS,
        // que a massa temporal disputaria.
        // Janela 2 (offset 1 s): primeiro segmento começa em 1,5 s — NÃO cobre a
        // origem, embora contenha "b" (outra ocorrência). Sem a origem, o contains
        // lexical reconciliaria errado.
        val curta = WhisperOrtGenerationConfig(
            suppressTokens = emptySet(),
            beginSuppressTokens = emptySet(),
            maxLength = 3 + 5,
            eosTokenId = WhisperOrtPrompt.END_OF_TEXT,
            decoderStartTokenId = WhisperOrtPrompt.START_OF_TRANSCRIPT,
        )
        val politica = WhisperOrtGenerationPolicy(
            prompt = WhisperOrtPrompt.portugueseWithTimestamps(),
            config = curta,
            timestampMode = true,
        )
        val w1 = listOf(begin, 65, ts(1.0), ts(1.0), 66)
        val w2 = listOf(ts(0.5), 66, ts(0.6), ts(0.6), 65)
        val backend = WhisperOrtFakeBackend(listOf(w1, w2))
        // Janela 2 usa 9.600 amostras (0,6 s): cobre [1,0..1,6] e para no fim.
        val pcm = FloatArray(16_000 + 9_600) { 0.1f }
        val r = WhisperOrtTranscriber(tokenizer(), politica).transcribe(backend, pcm)
        assertEquals(
            "cauda sem cobertura temporal não pode ser SUCCESS (texto '${r.text}')",
            WhisperOrtOutcome.PARTIAL, r.outcome,
        )
        assertTrue("texto produzido deve ser preservado", r.text.isNotBlank())
    }
}
