package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R16/§5.3 — testes FOCAIS da política de estágio (JVM, sem aparelho).
 *
 * Cobrem as dez exigências do prompt: CPU default, GPU qualificada, SoC diferente,
 * runtime ausente, segmento faltando, modelo fora da qualificação, recusa sem
 * consentimento, fallback COM consentimento, rótulo de hardware efetivo e chave
 * de cache distinta por modo.
 */
class WhisperOrtStagePolicyTest {

    private fun qualificacao(
        modelo: String = WhisperOrtStagePolicy.MODELO_GPU_QUALIFICADO,
        soc: String = "SM8550",
        segmentos: Map<String, String> = SEGMENTOS_OK,
    ) = WhisperOrtStagePolicy.QualificacaoOrtGpu(
        modeloId = modelo,
        socAlvo = soc,
        runtimeVersao = "1.29.0",
        qnnPluginVersao = "2.7.0",
        qnnSdkVersao = "2.51.0",
        segmentosEsperados = segmentos.size,
        sha256Segmentos = segmentos,
    )

    private fun ambiente(
        soc: String = "SM8550",
        runtime: Boolean = true,
        plugin: Boolean = true,
        gpu: Boolean = true,
    ) = WhisperOrtStagePolicy.AmbienteOrt(
        socAtual = soc,
        runtimeDesacopladoDisponivel = runtime,
        pluginQnnDisponivel = plugin,
        backendGpuDisponivel = gpu,
    )

    private fun decidir(
        modo: WhisperOrtStagePolicy.ModoExecucaoOrt = WhisperOrtStagePolicy.ModoExecucaoOrt.HIBRIDO_QNN_GPU_ENC_CPU_DEC,
        modelo: String = WhisperOrtStagePolicy.MODELO_GPU_QUALIFICADO,
        threads: Int = 6,
        qual: WhisperOrtStagePolicy.QualificacaoOrtGpu? = qualificacao(),
        amb: WhisperOrtStagePolicy.AmbienteOrt = ambiente(),
        presentes: Set<String> = SEGMENTOS_OK.keys,
    ) = WhisperOrtStagePolicy.decidir(modo, modelo, threads, qual, amb, presentes)

    // 1
    @Test
    fun cpuIntegralEhPermitidoComoDefault() {
        val d = decidir(modo = WhisperOrtStagePolicy.ModoExecucaoOrt.CPU_INTEGRAL)
        assertTrue(d is WhisperOrtStagePolicy.DecisaoEstagioOrt.Permitido)
        val p = d as WhisperOrtStagePolicy.DecisaoEstagioOrt.Permitido
        assertEquals(WhisperOrtStagePolicy.ModoExecucaoOrt.CPU_INTEGRAL, p.modoEfetivo)
        assertEquals("CPU (6 threads)", p.rotuloHardware)
        assertTrue("chave CPU não deve citar segmentos", p.chaveCacheSessao.contains("seg"))
    }

    // 2
    @Test
    fun hibridoQualificadoEhPermitido() {
        val p = decidir() as WhisperOrtStagePolicy.DecisaoEstagioOrt.Permitido
        assertEquals(WhisperOrtStagePolicy.ModoExecucaoOrt.HIBRIDO_QNN_GPU_ENC_CPU_DEC, p.modoEfetivo)
        assertEquals(WhisperOrtStagePolicy.ROTULO_HIBRIDO, p.rotuloHardware)
    }

    // 3
    @Test
    fun socDiferenteRecusaHibrido() {
        val d = decidir(amb = ambiente(soc = "SM8450"))
        assertTrue(d is WhisperOrtStagePolicy.DecisaoEstagioOrt.Recusado)
        assertEquals(
            "RUNTIME_QNN_INDISPONIVEL",
            (d as WhisperOrtStagePolicy.DecisaoEstagioOrt.Recusado).motivoCodigo,
        )
    }

    // 4
    @Test
    fun runtimeAusenteRecusaHibrido() {
        val d = decidir(amb = ambiente(runtime = false))
        assertEquals(
            "RUNTIME_QNN_INDISPONIVEL",
            (d as WhisperOrtStagePolicy.DecisaoEstagioOrt.Recusado).motivoCodigo,
        )
    }

    // 5
    @Test
    fun segmentoFaltandoRecusaHibrido() {
        val presentes = SEGMENTOS_OK.keys - "r10_grupo8_15.onnx"
        val d = decidir(presentes = presentes)
        val r = d as WhisperOrtStagePolicy.DecisaoEstagioOrt.Recusado
        assertEquals("PACOTE_SEGMENTADO_INCOMPLETO", r.motivoCodigo)
        assertTrue("a mensagem deve nomear o segmento", r.mensagemUsuario.contains("r10_grupo8_15.onnx"))
    }

    // 6
    @Test
    fun modeloForaDaQualificacaoRecusaHibrido() {
        val d = decidir(modelo = "small")
        assertEquals(
            "MODELO_NAO_QUALIFICADO_GPU",
            (d as WhisperOrtStagePolicy.DecisaoEstagioOrt.Recusado).motivoCodigo,
        )
    }

    // 7
    @Test
    fun recusaSemConsentimentoNaoCaiParaCpu() {
        val recusa = decidir(amb = ambiente(gpu = false))
        val depois = WhisperOrtStagePolicy.resolverFallback(recusa, consentimentoExplicitoUsuario = false)
        assertTrue("sem consentimento a recusa tem de permanecer", depois is WhisperOrtStagePolicy.DecisaoEstagioOrt.Recusado)
        assertEquals(recusa, depois)
    }

    // 8
    @Test
    fun recusaComConsentimentoCaiParaCpuRotulada() {
        val recusa = decidir(amb = ambiente(gpu = false))
        val depois = WhisperOrtStagePolicy.resolverFallback(recusa, consentimentoExplicitoUsuario = true)
        val p = depois as WhisperOrtStagePolicy.DecisaoEstagioOrt.Permitido
        assertEquals(WhisperOrtStagePolicy.ModoExecucaoOrt.CPU_INTEGRAL, p.modoEfetivo)
        assertEquals("CPU (6 threads)", p.rotuloHardware)
    }

    // 9
    @Test
    fun rotuloDeHardwareEhPreservadoPeloResumo() {
        val p = decidir() as WhisperOrtStagePolicy.DecisaoEstagioOrt.Permitido
        assertEquals(
            WhisperOrtStagePolicy.ROTULO_HIBRIDO,
            WhisperResumo.hardwareDe(p.rotuloHardware),
        )
        assertEquals("CPU (6 threads)", WhisperResumo.hardwareDe("CPU (6 threads)"))
    }

    // 10
    @Test
    fun chaveDeCacheDistingueModoEModelo() {
        val cpu = (decidir(modo = WhisperOrtStagePolicy.ModoExecucaoOrt.CPU_INTEGRAL)
            as WhisperOrtStagePolicy.DecisaoEstagioOrt.Permitido).chaveCacheSessao
        val gpu = (decidir() as WhisperOrtStagePolicy.DecisaoEstagioOrt.Permitido).chaveCacheSessao
        assertNotEquals("modos diferentes não podem compartilhar chave", cpu, gpu)
        val outroModelo = (decidir(
            modo = WhisperOrtStagePolicy.ModoExecucaoOrt.CPU_INTEGRAL, modelo = "small",
        ) as WhisperOrtStagePolicy.DecisaoEstagioOrt.Permitido).chaveCacheSessao
        assertNotEquals(cpu, outroModelo)
        val outrosThreads = (decidir(
            modo = WhisperOrtStagePolicy.ModoExecucaoOrt.CPU_INTEGRAL, threads = 4,
        ) as WhisperOrtStagePolicy.DecisaoEstagioOrt.Permitido).chaveCacheSessao
        assertNotEquals(cpu, outrosThreads)
    }


    // 11 (R21/§3.1)
    @Test
    fun todosOsModelosNaoTurboSaoRecusadosNoHibrido() {
        for (modelo in listOf("tiny", "base", "small")) {
            val r = decidir(modelo = modelo) as WhisperOrtStagePolicy.DecisaoEstagioOrt.Recusado
            assertEquals("MODELO_NAO_QUALIFICADO_GPU", r.motivoCodigo)
            assertTrue("o usuário precisa poder consentir ($modelo)", r.permiteFallbackConsentidoCpu)
            // ... mas nunca cai para CPU sem consentimento:
            val semConsentimento = WhisperOrtStagePolicy.resolverFallback(r, consentimentoExplicitoUsuario = false)
            assertTrue("fallback silencioso para $modelo", semConsentimento is WhisperOrtStagePolicy.DecisaoEstagioOrt.Recusado)
        }
    }

    // 12 (R21/§3.1)
    @Test
    fun chaveDeCacheDistingueHashDeSegmentoEParticao() {
        val base = WhisperOrtStagePolicy.chaveCache(
            WhisperOrtStagePolicy.ModoExecucaoOrt.HIBRIDO_QNN_GPU_ENC_CPU_DEC,
            WhisperOrtStagePolicy.MODELO_GPU_QUALIFICADO, 6, qualificacao(), "aaa", "3gpu+3cpu",
        )
        val outraParticao = WhisperOrtStagePolicy.chaveCache(
            WhisperOrtStagePolicy.ModoExecucaoOrt.HIBRIDO_QNN_GPU_ENC_CPU_DEC,
            WhisperOrtStagePolicy.MODELO_GPU_QUALIFICADO, 6, qualificacao(), "aaa", "6gpu",
        )
        assertNotEquals("partições diferentes não podem compartilhar sessão", base, outraParticao)

        val outroHash = WhisperOrtStagePolicy.chaveCache(
            WhisperOrtStagePolicy.ModoExecucaoOrt.HIBRIDO_QNN_GPU_ENC_CPU_DEC,
            WhisperOrtStagePolicy.MODELO_GPU_QUALIFICADO, 6, qualificacao(), "bbb", "3gpu+3cpu",
        )
        assertNotEquals("trocar um hash de segmento tem de invalidar", base, outroHash)
        assertNotEquals(
            WhisperOrtStagePolicy.hashCombinado(SEGMENTOS_OK),
            WhisperOrtStagePolicy.hashCombinado(SEGMENTOS_OK + ("r10_grupo24_31.onnx" to "zz")),
        )
    }

    // 13 (R21/§3.1)
    @Test
    fun rotuloHonestoNuncaDizGpuQuandoRodaNaCpu() {
        val cpu = decidir(modo = WhisperOrtStagePolicy.ModoExecucaoOrt.CPU_INTEGRAL)
            as WhisperOrtStagePolicy.DecisaoEstagioOrt.Permitido
        assertEquals("CPU (6 threads)", WhisperResumo.hardwareDe(cpu.rotuloHardware))
        assertTrue(
            "o rótulo da CPU não pode citar GPU",
            !WhisperResumo.hardwareDe(cpu.rotuloHardware).contains("GPU"),
        )
        val gpu = decidir() as WhisperOrtStagePolicy.DecisaoEstagioOrt.Permitido
        val rotulo = WhisperResumo.hardwareDe(gpu.rotuloHardware)
        assertEquals(WhisperOrtStagePolicy.ROTULO_HIBRIDO, rotulo)
        assertTrue("o híbrido precisa declarar o decoder na CPU", rotulo.contains("CPU (dec)"))
    }

    // 14 (R21/§3.1)
    @Test
    fun recusaManifestoInconsistenteESeguimentoVazio() {
        val manifestoInvalido = decidir(
            qual = qualificacao().copy(segmentosEsperados = 0),
        ) as WhisperOrtStagePolicy.DecisaoEstagioOrt.Recusado
        assertEquals("PACOTE_SEGMENTADO_TAMANHO_INVALIDO", manifestoInvalido.motivoCodigo)

        val contagemErrada = decidir(
            qual = qualificacao().copy(segmentosEsperados = 9),
        ) as WhisperOrtStagePolicy.DecisaoEstagioOrt.Recusado
        assertEquals("PACOTE_SEGMENTADO_TAMANHO_INVALIDO", contagemErrada.motivoCodigo)

        val vazio = decisorComVazios()
        assertEquals("SEGMENTO_VAZIO", vazio.motivoCodigo)
        assertTrue("o usuário precisa saber qual arquivo está vazio", vazio.mensagemUsuario.contains("r10_camada0.onnx"))
        assertTrue(vazio.permiteFallbackConsentidoCpu)
    }

    private fun decisorComVazios(): WhisperOrtStagePolicy.DecisaoEstagioOrt.Recusado =
        WhisperOrtStagePolicy.decidir(
            modoPedido = WhisperOrtStagePolicy.ModoExecucaoOrt.HIBRIDO_QNN_GPU_ENC_CPU_DEC,
            modeloId = WhisperOrtStagePolicy.MODELO_GPU_QUALIFICADO,
            threadsCpu = 6,
            qualificacao = qualificacao(),
            ambiente = ambiente(),
            segmentosPresentes = SEGMENTOS_OK.keys,
            segmentosVazios = setOf("r10_camada0.onnx"),
        ) as WhisperOrtStagePolicy.DecisaoEstagioOrt.Recusado

    private companion object {
        val SEGMENTOS_OK: Map<String, String> = linkedMapOf(
            "r10_frente.onnx" to "a1",
            "r10_camada0.onnx" to "a2",
            "r10_grupo0_7.onnx" to "a3",
            "r10_grupo8_15.onnx" to "a4",
            "r10_grupo16_23.onnx" to "a5",
            "r10_grupo24_31.onnx" to "a6",
        )
    }
}
