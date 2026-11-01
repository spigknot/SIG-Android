package br.gov.sp.pcsp.launcher

import org.junit.Assert.*
import org.junit.Test

/**
 * Adendo 16 §3 — seletor de threads compartilhado ORT + whisper.cpp.
 *
 * Uma chave neutra (`whisper_threads`, 1–8, default 4); a legada
 * (`whisper_ort_threads`) é herdada uma vez. ORT aplica direto por despacho;
 * o CPP recebe o mesmo valor por job via seam one-shot (sem config global).
 * O terminal ORT carrega as pedidas ([WhisperOrtAdmission.Terminal.threads]).
 */
class WhisperOrtThreadsCompartilhadasTest {

    @Test fun precedenciaNovaLegadaDefault() {
        assertEquals(6, WhisperOrtSelecao.resolverThreads(nova = 6, legada = 2, padrao = 4))
        assertEquals(2, WhisperOrtSelecao.resolverThreads(nova = null, legada = 2, padrao = 4))
        assertEquals(4, WhisperOrtSelecao.resolverThreads(nova = null, legada = null, padrao = 4))
    }

    @Test fun faixaCoagidaUmAOito() {
        assertEquals(1, WhisperOrtSelecao.resolverThreads(nova = 0, legada = null, padrao = 4))
        assertEquals(1, WhisperOrtSelecao.resolverThreads(nova = -3, legada = null, padrao = 4))
        assertEquals(8, WhisperOrtSelecao.resolverThreads(nova = 9, legada = null, padrao = 4))
        assertEquals(8, WhisperOrtSelecao.resolverThreads(nova = null, legada = 99, padrao = 4))
        assertEquals(1, WhisperOrtSelecao.resolverThreads(nova = null, legada = null, padrao = 0))
    }

    @Test fun terminalCarregaThreadsPedidas() {
        val admission = WhisperOrtAdmission()
        val job = (admission.admit("T", 1, "ORT", "CPU", "c".repeat(64)) as WhisperOrtAdmission.Admitted.Ok).job
        val terminal = admission.complete(job, "SUCCESS", "texto", 100L, false, threads = 4)
        assertNotNull(terminal)
        assertEquals(4, terminal!!.threads)
    }

    @Test fun terminalDefaultZeroSemMarcacao() {
        val admission = WhisperOrtAdmission()
        val job = (admission.admit("T", 1, "ORT", "CPU", "d".repeat(64)) as WhisperOrtAdmission.Admitted.Ok).job
        val terminal = admission.complete(job, "SUCCESS", "texto", 100L, false)
        assertNotNull(terminal)
        assertEquals(0, terminal!!.threads)
    }

    @Test fun trocaDeThreadsNaoReaproveitaSessaoQuente() {
        val config = WhisperOrtGenerationConfig(
            suppressTokens = setOf(1),
            beginSuppressTokens = setOf(2),
            maxLength = 448,
            eosTokenId = 50256,
            decoderStartTokenId = 50258,
        )
        val t4 = WhisperOrtSessionCache.keyFor(WhisperOrtModelCatalog.identity(), "v1", "test", config, 4)
        val t1 = WhisperOrtSessionCache.keyFor(WhisperOrtModelCatalog.identity(), "v1", "test", config, 1)
        assertNotEquals("threads distintas são sessões distintas", t4, t1)
    }

    @Test fun provedorDoJobDefaultELegado() {
        // Sem provedor ligado, o controller não toca no seam (lab/produto intactos).
        val controller = WhisperSessionController(
            gateway = object : WhisperSessionController.NativeGateway {
                override fun loadModel(modelPath: String, backendKind: Int, flashAttention: Boolean) = true
                override fun transcribe(
                    wavPath: String, language: String, beamSize: Int, bestOf: Int,
                    wordTimestamps: Boolean, vadFilter: Boolean, vadModelPath: String,
                    callback: WhisperNative.Callback,
                ): String = ""
                override fun cancelTranscription() = Unit
                override fun beginSession() = 0L
                override fun releaseModel() = Unit
                override fun lastTranscribeStatus() = ""
            },
        )
        assertNull("default preserva o legado (sem toque no seam)", controller.threadsForJob)
    }

    // Adendo 17 §2 — contrato por tentativa (seam fake + gateway fake).

    private class SeamFake : WhisperSessionController.ThreadsSeam {
        val aplicados = mutableListOf<Int>()
        var valor = 0
        var falharTudo = false
        /** Quando true, o compute "consome" (como o nativo no create params). */
        var consumirNoCompute = true
        override fun peek(): Int {
            if (falharTudo) throw UnsatisfiedLinkError("seam ausente (lib antiga)")
            return valor
        }
        override fun set(threads: Int): Int {
            if (falharTudo) throw UnsatisfiedLinkError("seam ausente (lib antiga)")
            val anterior = valor
            valor = threads
            aplicados.add(threads)
            return anterior
        }
        override fun clear() {
            valor = 0
        }
    }

    private class GatewayFake(
        val seam: SeamFake,
        var falharCompute: Boolean = false,
    ) : WhisperSessionController.NativeGateway {
        override fun loadModel(modelPath: String, backendKind: Int, flashAttention: Boolean) = true
        override fun transcribe(
            wavPath: String, language: String, beamSize: Int, bestOf: Int,
            wordTimestamps: Boolean, vadFilter: Boolean, vadModelPath: String,
            callback: WhisperNative.Callback,
        ): String {
            if (falharCompute) throw RuntimeException("compute abortado antes do consume")
            // O nativo consome o one-shot ao criar os params.
            if (seam.consumirNoCompute) seam.valor = 0
            return "ok"
        }
        override fun cancelTranscription() = Unit
        override fun beginSession() = System.nanoTime()
        override fun releaseModel() = Unit
        override fun lastTranscribeStatus() = "ok"
    }

    // Adendo 18 §1 — gateway OWNED com capability do transporte de pedido
    // (símbolo adicional de v15; calculado por [WhisperNative]).
    private class GatewayOwnedCap2(
        val seam: SeamFake,
        val capa: Int = 2,
        var falharCompute: Boolean = false,
        val semEntradaPedido: Boolean = false,
    ) : WhisperSessionController.NativeGateway {
        var ultimoPedido: String = ""
        var chamadasLegadas = 0
        override fun loadModel(modelPath: String, backendKind: Int, flashAttention: Boolean) = true
        override fun transcribe(
            wavPath: String, language: String, beamSize: Int, bestOf: Int,
            wordTimestamps: Boolean, vadFilter: Boolean, vadModelPath: String,
            callback: WhisperNative.Callback,
        ): String = "ok"
        override fun cancelTranscription() = Unit
        override fun beginSession() = System.nanoTime()
        override fun releaseModel() = Unit
        override fun lastTranscribeStatus() = "ok"

        override fun ownershipApiVersion(): Int = 2
        override fun beginSessionOwned(): Long = 777L
        override fun attemptRequestApiVersion(): Int = capa

        override fun transcribeOwnedReq(
            wavPath: String, language: String, beamSize: Int, bestOf: Int,
            wordTimestamps: Boolean, vadFilter: Boolean, vadModelPath: String,
            owner: Long, callback: WhisperNative.Callback, request: String,
        ): String {
            if (semEntradaPedido) throw UnsatisfiedLinkError("transcribeOwnedReq ausente")
            if (falharCompute) throw RuntimeException("compute abortado antes do consume")
            ultimoPedido = request
            // Espelha o nativo: só o pedido com threads preserva o one-shot.
            if (!request.contains(";threads=") && seam.consumirNoCompute) seam.valor = 0
            return "ok"
        }
        override fun transcribeOwned(
            wavPath: String, language: String, beamSize: Int, bestOf: Int,
            wordTimestamps: Boolean, vadFilter: Boolean, vadModelPath: String,
            owner: Long, callback: WhisperNative.Callback,
        ): String {
            chamadasLegadas++
            return "legacy"
        }
        override fun cancelTranscriptionOwned(owner: Long) = Unit
        override fun lastTranscribeStatusOwned(owner: Long): String = "ok"
    }

    private fun controleThreadsTransporte(seam: SeamFake, gateway: GatewayOwnedCap2) =
        WhisperSessionController(
            gateway = gateway,
            threadsSeam = seam,
            logError = { _, _ -> },
            nativePrewarm = { },
        ).apply { threadsForJob = { 4 } }

    private fun callbackNulo() = object : WhisperNative.Callback {
        override fun onSegment(text: String, startMs: Long, endMs: Long) = Unit
        override fun onProgress(progress: Int) = Unit
        override fun onNativeLog(line: String) = Unit
    }

    private fun controlador(seam: SeamFake, gateway: GatewayFake, pedidas: () -> Int?) =
        WhisperSessionController(
            gateway = gateway,
            threadsSeam = seam,
            logError = { _, _ -> },
            nativePrewarm = { },
        ).apply { threadsForJob = pedidas }

    private fun transcrever(controller: WhisperSessionController): Pair<String, String> {
        val gen = controller.beginSession()
        return controller.transcribeAndStatus(
            gen, "a.wav", "pt", 1, 1, false, false, "", callbackNulo(),
        )
    }

    @Test fun leaseConsomeElimpaNoSucesso() {
        val seam = SeamFake()
        val gateway = GatewayFake(seam)
        val controller = controlador(seam, gateway) { 4 }
        val (texto, status) = transcrever(controller)
        assertEquals("ok", texto)
        assertEquals(listOf(4), seam.aplicados)
        assertEquals(0, seam.valor)
        val lease = controller.ultimoLeaseThreads
        assertNotNull(lease)
        assertEquals("seletor", lease!!.fonte)
        assertEquals(4, lease.pedidas)
        assertTrue(lease.consumida)
        assertFalse(lease.vazamentoEvitado)
        assertFalse(lease.interferencia)
        assertEquals(status, "ok")
    }

    @Test fun labPendenteNaoEClobberado() {
        val seam = SeamFake().apply { valor = 6; consumirNoCompute = false }
        val gateway = GatewayFake(seam)
        val controller = controlador(seam, gateway) { 4 }
        transcrever(controller)
        assertTrue("lab pendente nunca recebe set", seam.aplicados.isEmpty())
        assertEquals("valor do lab segue pendente", 6, seam.valor)
        val lease = controller.ultimoLeaseThreads
        assertNotNull(lease)
        assertEquals("lab", lease!!.fonte)
        assertEquals(6, lease.pedidas)
    }

    @Test fun saidaAntecipadaLimpaSemVazar() {
        val seam = SeamFake()
        val gateway = GatewayFake(seam, falharCompute = true)
        val controller = controlador(seam, gateway) { 9 }
        // O compute aborta antes do consume: a exceção sobe, mas o lease
        // fecha e limpa antes dela sair do controller.
        runCatching { transcrever(controller) }
        assertEquals("coagido a 1-8 antes de armar", listOf(8), seam.aplicados)
        assertEquals("nada pode restar para o próximo job", 0, seam.valor)
        val lease = controller.ultimoLeaseThreads
        assertNotNull(lease)
        assertEquals("seletor", lease!!.fonte)
        assertFalse(lease.consumida)
        assertTrue(lease.vazamentoEvitado)
        assertFalse(lease.interferencia)
    }

    @Test fun adapterDaJniQueRetornaValorNovoMantemPosseELimpeza() {
        val estadoNativo = SeamFake()
        // A JNI v14 devolve peek DEPOIS do set, não o valor anterior.
        val adapter = WhisperSessionController.ThreadsSeam.NativeAdapter(
            { estadoNativo.valor },
            { novo -> estadoNativo.valor = novo; novo },
        )
        val controller = WhisperSessionController(
            gateway = GatewayFake(estadoNativo, falharCompute = true),
            threadsSeam = adapter,
            nativePrewarm = { },
        ).apply { threadsForJob = { 4 } }
        runCatching { transcrever(controller) }
        val lease = controller.ultimoLeaseThreads
        assertNotNull(lease)
        assertEquals("pedido do produto não pode virar lab", "seletor", lease!!.fonte)
        assertEquals("saída antes de consume não deixa override", 0, estadoNativo.valor)
        assertTrue(lease.vazamentoEvitado)
    }

    @Test fun congelamentoPorTentativaNaoReleNoFim() {
        val seam = SeamFake()
        val gateway = GatewayFake(seam)
        val pedidos = listOf(4, 8).iterator()
        val controller = controlador(seam, gateway) { pedidos.next() }
        transcrever(controller)
        transcrever(controller)
        assertEquals(listOf(4, 8), seam.aplicados)
        assertEquals(8, controller.ultimoLeaseThreads!!.pedidas)
    }

    @Test fun sucessorSemPedidoNaoTocaSeam() {
        val seam = SeamFake()
        val gateway = GatewayFake(seam)
        var pedido: Int? = 4
        val controller = controlador(seam, gateway) { pedido }
        transcrever(controller)
        pedido = null
        transcrever(controller)
        assertEquals("segunda tentativa sem pedido não arma", listOf(4), seam.aplicados)
        assertNull(controller.ultimoLeaseThreads)
    }

    @Test fun seamIndisponivelNaoElegadoSilencioso() {
        val seam = SeamFake().apply { falharTudo = true }
        val gateway = GatewayFake(seam)
        val controller = controlador(seam, gateway) { 4 }
        val (texto, _) = transcrever(controller)
        assertEquals("compute legado segue sem override", "ok", texto)
        // Adendo 18 §1 — o pedido NÃO aplicado é REGISTRADO (solicitadas
        // presentes, sem efetiva observável); nunca mais legado silencioso.
        val lease = controller.ultimoLeaseThreads
        assertNotNull(lease)
        assertEquals(4, lease!!.pedidas)
        assertTrue(lease.pedidoNaoAplicado)
        assertFalse(lease.consumida)
    }

    @Test fun pedidoTransportadoNoFioNaoTocaOneShot() {
        // Adendo 18 §1 — controller com capability 2 e binding owned: as
        // threads congeladas viajam NA chamada (wire `threads=<n>`) e o
        // one-shot global NÃO é armado nem consumido pelo produto.
        val seam = SeamFake()
        val gateway = GatewayOwnedCap2(seam)
        val controller = controleThreadsTransporte(seam, gateway)
        val (texto, status) = transcrever(controller)
        assertEquals("ok", texto)
        assertEquals("ok", status)
        assertEquals("seam nunca armado no transporte", 0, seam.aplicados.size)
        assertEquals("one-shot global continua vazio (podia ter lab pendente)", 0, seam.valor)
        val lease = controller.ultimoLeaseThreads
        assertNotNull(lease)
        assertEquals("seletor", lease!!.fonte)
        assertEquals(4, lease.pedidas)
        assertEquals("via=pedido", "pedido", lease.via)
        assertTrue(gateway.ultimoPedido.contains("threads=4"))
    }

    @Test fun pedidoLabSemThreadsNaoDeclaraTransporteDoSeletor() {
        val seam = SeamFake().apply { valor = 6 }
        val gateway = GatewayOwnedCap2(seam)
        val controller = WhisperSessionController(
            gateway = gateway,
            threadsSeam = seam,
            nativePrewarm = { },
            attemptRequestProvider = { token, owner, gen, job, attempt ->
                AttemptRequest(token, owner, gen, job, attempt, producer = "lab")
            },
        ).apply { threadsForJob = { 4 } }
        val (texto, _) = transcrever(controller)
        assertEquals("ok", texto)
        assertFalse(gateway.ultimoPedido.contains(";threads="))
        assertEquals("seam do lab foi consumido pelo compute", 0, seam.valor)
        assertTrue("seletor não sobrescreve o lab", seam.aplicados.isEmpty())
        val lease = controller.ultimoLeaseThreads!!
        assertEquals("sem threads no fio, não houve transporte", "seam", lease.via)
        assertEquals("lab", lease.fonte)
        assertEquals(6, lease.pedidas)
    }

    @Test fun entradaPedidoAusenteNaoDescartaThreadsTransportadas() {
        val seam = SeamFake()
        val gateway = GatewayOwnedCap2(seam, semEntradaPedido = true)
        val controller = controleThreadsTransporte(seam, gateway)
        val (texto, status) = transcrever(controller)
        assertEquals("error", status)
        assertTrue(texto.contains("UNSUPPORTED"))
        assertEquals("pedido com threads não equivale aos defaults", 0, gateway.chamadasLegadas)
        assertTrue("transporte não arma o global", seam.aplicados.isEmpty())
    }

    @Test fun labPendentePreservadoNoTransporte() {
        // Adendo 18 §1 + §1 "mesmo valor" (ABA): bancada armou 6 no one-shot;
        // o produto pede 4 via pedido: o compute do produto aplica 4 e o
        // valor 6 da bancada NÃO é consumido nem limpo por ele.
        val seam = SeamFake().apply { valor = 6; consumirNoCompute = false }
        val gateway = GatewayOwnedCap2(seam)
        val controller = controleThreadsTransporte(seam, gateway)
        val (texto, _) = transcrever(controller)
        assertEquals("ok", texto)
        assertEquals("bancada preservada", 6, seam.valor)
        assertTrue("produto nunca arma o seam", seam.aplicados.isEmpty())
        val lease = controller.ultimoLeaseThreads
        assertEquals("pedidas do produto", 4, lease!!.pedidas)
        assertEquals("via do produto", "pedido", lease.via)
        assertFalse("produto não declara consumo pelo peek alheio", lease.interferencia)
        assertTrue("fio leva o 4 do produto", gateway.ultimoPedido.contains("threads=4"))
    }

    @Test fun capZeroMantemContratoAdendo17() {
        // Lib v14 sem o símbolo de capability: sem chave `threads=` no fio e
        // com o lease do seam (adendo 17) intacto.
        val seam = SeamFake()
        val gateway = GatewayOwnedCap2(seam, capa = 0)
        val controller = controleThreadsTransporte(seam, gateway)
        val (texto, _) = transcrever(controller)
        assertEquals("ok", texto)
        assertEquals("rota seam como no adendo 17", listOf(4), seam.aplicados)
        assertFalse("fio não pode ter a chave", gateway.ultimoPedido.contains("threads="))
    }

    @Test fun saidaAntecipadaComTransporteNaoVazaOneShot() {
        // Compute aborta antes do retorno: o one-shot NÃO foi usado (nunca
        // armado) e o valor do produto não vaza nem para o seam nem em outro
        // lugar; a contabilização fica no lease via=pedido.
        val seam = SeamFake().apply { consumirNoCompute = false }
        val gateway = GatewayOwnedCap2(seam, falharCompute = true)
        val controller = controleThreadsTransporte(seam, gateway)
        runCatching { transcrever(controller) }
        assertEquals(0, seam.valor)
        assertTrue(seam.aplicados.isEmpty())
        val lease = controller.ultimoLeaseThreads
        assertNotNull(lease)
        assertEquals("pedido", lease!!.via)
        assertFalse(lease.consumida)
    }
}
