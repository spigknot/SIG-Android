package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R115 §T03 — backend HTP de ponta a ponta com fakes: zero-cópia do encoder ao
 * projetor, prompt token a token, volta para a CPU no limite artificial e na
 * falha da HTP, carga PREGUIÇOSA dos decoders CPU e logits pelo contrato.
 *
 * Nada aqui abre ORT nem QNN: as portas ([WhisperOrtHtpSessao],
 * [WhisperOrtHtpProjetorSessao], [WhisperOrtHtpDecoderSessao]) existem para o
 * teste JVM provar a REGRA antes do aparelho.
 */
class WhisperOrtHtpLoopBackendTest {

    private val cruzadas = (0 until 4).flatMap { listOf("k_cache_cross_$it", "v_cache_cross_$it") }
        .associateWith { ByteArray(4) { 0x11 } }

    private class EncoderFake(val saida: ByteArray) : WhisperOrtHtpSessao {
        var entradas = 0
            private set
        var ultimaEntrada: ByteArray? = null
            private set

        override fun rodar(entrada: ByteArray): ByteArray {
            entradas += 1
            ultimaEntrada = entrada.copyOf()
            return saida
        }

        override fun close() = Unit
    }

    private class ProjetorFake : WhisperOrtHtpProjetorSessao {
        var ultimosCodigos: ByteArray? = null
            private set
        var chamadas = 0
            private set

        override fun rodar(codigos: ByteArray): Map<String, ByteArray> {
            chamadas += 1
            ultimosCodigos = codigos.copyOf()
            return (0 until 4).flatMap { listOf("k_cache_cross_$it", "v_cache_cross_$it") }
                .associateWith { ByteArray(4) { 0x22 } }
        }

        override fun close() = Unit
    }

    /** Decoder fake: logits com um pico no índice pedido por [picos]. */
    private class DecoderFake(private val picos: (Int) -> Int = { 0 }) : WhisperOrtHtpDecoderSessao {
        val tokens = ArrayList<Int>()
        val posicoes = ArrayList<Int>()
        val cruzadasRecebidas = ArrayList<Int>()
        var falharNaPosicao: Int? = null
        var tamanhoLogits: Int = WhisperOrtHtpLoopBackend.VOCABULARIO

        override fun abrir(cruzadas: Map<String, ByteArray>) {
            cruzadasRecebidas.clear()
            cruzadasRecebidas.addAll(cruzadas.keys.sorted().map { it.length })
            tokens.clear()
            posicoes.clear()
        }

        override fun passo(token: Int, posicao: Int): ByteArray {
            if (falharNaPosicao == posicao) throw IllegalStateException("HTP recusou o passo")
            tokens.add(token)
            posicoes.add(posicao)
            val bytes = ByteArray(tamanhoLogits * 2)
            val pico = picos(token).coerceIn(0, tamanhoLogits - 1)
            val codigo = WhisperOrtHtpContrato.OFFSET_LOGIT + 1000
            bytes[2 * pico] = (codigo and 0xFF).toByte()
            bytes[2 * pico + 1] = ((codigo ushr 8) and 0xFF).toByte()
            return bytes
        }

        override fun close() = Unit
    }

    /** Backend CPU fake: registra a janela refeita. */
    private class CpuFake : WhisperOrtBackend {
        var aberturas = 0
            private set
        var promptRecebido: IntArray? = null
            private set
        var hiddenRecebido: FloatArray? = null
            private set
        val passos = ArrayList<Int>()

        override fun encode(logMel: FloatArray): FloatArray = FloatArray(0)

        override fun start(hidden: FloatArray, prompt: IntArray): FloatArray {
            aberturas += 1
            hiddenRecebido = hidden.copyOf()
            promptRecebido = prompt.copyOf()
            passos.clear()
            val logits = FloatArray(WhisperOrtHtpLoopBackend.VOCABULARIO)
            logits[7] = 1.0f
            return logits
        }

        override fun step(token: Int): FloatArray {
            passos.add(token)
            val logits = FloatArray(WhisperOrtHtpLoopBackend.VOCABULARIO)
            logits[7] = 1.0f
            return logits
        }

        override fun close() = Unit
    }

    private fun backend(
        decoder: DecoderFake = DecoderFake(),
        limite: Int = WhisperOrtHtpContrato.POSICOES_CACHE_SELF,
        cpu: CpuFake = CpuFake(),
        cargasCpu: MutableList<Int> = ArrayList(),
    ): Triple<WhisperOrtHtpLoopBackend, DecoderFake, CpuFake> {
        val loop = WhisperOrtHtpLoopBackend(
            encoder = EncoderFake(ByteArray(8) { 0x33 }),
            projetor = ProjetorFake(),
            decoder = decoder,
            decodersCpu = {
                cargasCpu.add(1)
                cpu
            },
            limiteHtp = limite,
        )
        return Triple(loop, decoder, cpu)
    }

    @Test
    fun `encoder entrega os bytes crus ao projetor sem passar por float`() {
        val projetor = ProjetorFake()
        val encoder = EncoderFake(ByteArray(6) { (0x40 + it).toByte() })
        val loop = WhisperOrtHtpLoopBackend(
            encoder = encoder,
            projetor = projetor,
            decoder = DecoderFake(),
            decodersCpu = { error("não deve tocar na CPU") },
        )
        val logMel = FloatArray(WhisperOrtFrontend.N_MELS * WhisperOrtFrontend.N_FRAMES)
        loop.encode(logMel)
        assertEquals(1, encoder.entradas)
        loop.start(FloatArray(0), intArrayOf(1))
        // O projetor recebeu EXATAMENTE os bytes da saída do encoder.
        assertNotNull(projetor.ultimosCodigos)
        assertEquals(encoder.saida.toList(), projetor.ultimosCodigos!!.toList())
        assertEquals(1, projetor.chamadas)
    }

    @Test
    fun `prompt passa token a token e a posicao comeca em zero`() {
        val (loop, decoder, _) = backend()
        loop.encode(FloatArray(0))
        loop.start(FloatArray(0), intArrayOf(11, 12, 13))
        assertEquals(listOf(11, 12, 13), decoder.tokens)
        assertEquals(listOf(0, 1, 2), decoder.posicoes)
        assertEquals(3, loop.tokensPrompt)
        assertEquals(0, loop.passosHtp)
    }

    @Test
    fun `logits sao desquantizados pelo contrato`() {
        val (loop, _, _) = backend(decoder = DecoderFake(picos = { 5 }))
        loop.encode(FloatArray(0))
        val logits = loop.start(FloatArray(0), intArrayOf(1))
        assertEquals(WhisperOrtHtpLoopBackend.VOCABULARIO, logits.size)
        assertEquals(1000 * WhisperOrtHtpContrato.ESCALA_LOGIT.toFloat(), logits[5], 1e-4f)
        assertEquals(WhisperOrtHtpContrato.codigoParaLogit(0), logits[0], 1e-4f)
    }

    @Test
    fun `limite artificial forca a volta e refaz a janela inteira na cpu`() {
        val cargas = ArrayList<Int>()
        val (loop, decoder, cpu) = backend(limite = 2, cargasCpu = cargas)
        loop.encode(FloatArray(0))
        loop.start(FloatArray(0), intArrayOf(21, 22))
        assertEquals(2, decoder.tokens.size)
        assertFalse("decoders CPU ainda não podem ter carregado", loop.decodersCpuCarregados())
        assertTrue(cargas.isEmpty())

        val logits = loop.step(23)
        assertEquals(1, loop.voltasCpu)
        assertEquals(1, cpu.aberturas)
        assertEquals("a janela é refeita com o MESMO hidden e prompt", listOf(21, 22), cpu.promptRecebido!!.toList())
        assertEquals("o parcial da HTP é descartado: nada a rejogar", emptyList<Int>(), cpu.passos.dropLast(1))
        assertEquals(listOf(23), cpu.passos)
        assertEquals(7, logits.indexOfFirst { it > 0f })
        assertTrue(loop.decodersCpuCarregados())
        assertEquals("uma única carga", 1, cargas.size)
        assertEquals("limite:2", loop.motivoVoltaCpu)
    }

    @Test
    fun `depois da volta os passos seguintes ficam na cpu sem nova carga`() {
        val cargas = ArrayList<Int>()
        val (loop, _, cpu) = backend(limite = 1, cargasCpu = cargas)
        loop.encode(FloatArray(0))
        loop.start(FloatArray(0), intArrayOf(1))
        loop.step(2)
        loop.step(3)
        assertEquals("uma volta por JANELA, não por passo", 1, loop.voltasCpu)
        assertEquals(2, loop.passosCpu)
        assertEquals(1, cargas.size)
        assertEquals(listOf(2, 3), cpu.passos)
    }

    @Test
    fun `falha da htp no meio da janela cai para a cpu e rejoga o parcial`() {
        val cargas = ArrayList<Int>()
        val decoder = DecoderFake().apply { falharNaPosicao = 3 }
        val (loop, _, cpu) = backend(decoder = decoder, cargasCpu = cargas)
        loop.encode(FloatArray(0))
        loop.start(FloatArray(0), intArrayOf(1))
        loop.step(10) // posição 1
        loop.step(11) // posição 2
        // posição 3 falha: a janela volta inteira e o parcial (10, 11) é rejogado.
        loop.step(12)
        assertEquals(1, loop.voltasCpu)
        assertTrue(loop.motivoVoltaCpu!!.startsWith("falha:3:"))
        assertEquals(listOf(10, 11, 12), cpu.passos)
        assertEquals(1, cargas.size)
    }

    @Test
    fun `logits com tamanho errado viram falha tipada da rota`() {
        val decoder = DecoderFake().apply { tamanhoLogits = 3 }
        val (loop, _, _) = backend(decoder = decoder)
        loop.encode(FloatArray(0))
        assertThrows(WhisperOrtHtpFalha::class.java) {
            loop.start(FloatArray(0), intArrayOf(1))
        }
    }

    @Test
    fun `falha do encoder vira falha tipada da rota`() {
        val loop = WhisperOrtHtpLoopBackend(
            encoder = object : WhisperOrtHtpSessao {
                override fun rodar(entrada: ByteArray): ByteArray = error("HTP fora")
                override fun close() = Unit
            },
            projetor = ProjetorFake(),
            decoder = DecoderFake(),
            decodersCpu = { error("não deve tocar na CPU") },
        )
        val falha = assertThrows(WhisperOrtHtpFalha::class.java) { loop.encode(FloatArray(0)) }
        assertTrue(falha.message!!.startsWith("htp: encoder:"))
    }

    @Test
    fun `rota e bins mel nao dependem dos decoders cpu`() {
        val (loop, _, _) = backend()
        assertEquals(WhisperOrtHtpAdmissao.ROTA_HTP, loop.rota)
        assertEquals(WhisperOrtFrontend.N_MELS, loop.melBinsDetectados)
        assertNull(loop.codigosDaJanela())
        assertFalse(loop.decodersCpuCarregados())
    }
}
