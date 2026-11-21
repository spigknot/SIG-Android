package br.gov.sp.pcsp.launcher

import br.gov.sp.pcsp.launcher.WhisperSelectors.Choice
import br.gov.sp.pcsp.launcher.WhisperSelectors.Hardware
import org.junit.Assert.*
import org.junit.Test

class WhisperSelectorsTest {
    @Test
    fun `ORT e o padrao e a escolha moderna precede a legada`() {
        assertTrue(WhisperSelectors.useOrt(null, null))
        assertFalse(WhisperSelectors.useOrt(null, false))
        assertTrue(WhisperSelectors.useOrt(null, true))
        assertTrue(WhisperSelectors.useOrt(false, false))
        assertFalse(WhisperSelectors.useOrt(true, true))
    }

    @Test
    fun `hardware depende do SoC modelo e versao verificada`() {
        for (hardware in listOf(Hardware.HYBRID, Hardware.NPU)) {
            assertEquals(Choice.UNSUPPORTED_SOC, WhisperSelectors.choice(hardware, false, true, 3))
            assertEquals(Choice.UNSUPPORTED_MODEL, WhisperSelectors.choice(hardware, true, false, 3))
            assertEquals(Choice.DOWNLOAD, WhisperSelectors.choice(hardware, true, true, 0))
            assertEquals(Choice.READY, WhisperSelectors.choice(hardware, true, true, 3))
        }
        assertEquals(Choice.READY, WhisperSelectors.choice(Hardware.CPU, false, false, 0))
        assertEquals(Choice.READY, WhisperSelectors.choice(Hardware.HYBRID, true, true, 2))
        assertEquals(Choice.DOWNLOAD, WhisperSelectors.choice(Hardware.NPU, true, true, 2))
    }

    @Test
    fun `hardware implicito so usa NPU com v3 turbo e SoC admitido`() {
        assertEquals(Hardware.NPU, WhisperSelectors.effective(null, true, true, 3))
        assertEquals(Hardware.CPU, WhisperSelectors.effective(null, true, true, 0))
        assertEquals(Hardware.CPU, WhisperSelectors.effective(null, true, false, 3))
        assertEquals(Hardware.CPU, WhisperSelectors.effective(null, false, true, 3))
        assertEquals(Hardware.CPU, WhisperSelectors.effective(Hardware.CPU, true, true, 3))
        assertEquals(Hardware.HYBRID, WhisperSelectors.effective(Hardware.HYBRID, true, true, 3))
        assertEquals(Hardware.CPU, WhisperSelectors.effective(Hardware.HYBRID, true, false, 3))
    }

    /**
     * R123/T01 — matriz COMPLETA do hardware efetivo: escolha salva
     * (`null`, `CPU`, `HYBRID`, `NPU`) × SoC admitido × turbo × versão
     * verificada (0, 2, 3). A tabela é a regra escrita à mão, independente da
     * implementação: sem escolha salva vale a melhor rota PRONTA
     * (`NPU` → `NPU→CPU` → `CPU`); com escolha salva, a escolha se pronta,
     * senão `CPU`.
     */
    @Test
    fun `matriz completa do hardware efetivo`() {
        val esperado = mapOf(
            // escolha salva = null: melhor rota pronta
            Triple(null, true, true) to mapOf(0 to Hardware.CPU, 2 to Hardware.HYBRID, 3 to Hardware.NPU),
            Triple(null, true, false) to mapOf(0 to Hardware.CPU, 2 to Hardware.CPU, 3 to Hardware.CPU),
            Triple(null, false, true) to mapOf(0 to Hardware.CPU, 2 to Hardware.CPU, 3 to Hardware.CPU),
            Triple(null, false, false) to mapOf(0 to Hardware.CPU, 2 to Hardware.CPU, 3 to Hardware.CPU),
            // escolha salva = CPU: sempre pronta
            Triple(Hardware.CPU, true, true) to mapOf(0 to Hardware.CPU, 2 to Hardware.CPU, 3 to Hardware.CPU),
            Triple(Hardware.CPU, true, false) to mapOf(0 to Hardware.CPU, 2 to Hardware.CPU, 3 to Hardware.CPU),
            Triple(Hardware.CPU, false, true) to mapOf(0 to Hardware.CPU, 2 to Hardware.CPU, 3 to Hardware.CPU),
            Triple(Hardware.CPU, false, false) to mapOf(0 to Hardware.CPU, 2 to Hardware.CPU, 3 to Hardware.CPU),
            // escolha salva = HYBRID: pronta com v2 ou v3, turbo e SoC admitido
            Triple(Hardware.HYBRID, true, true) to mapOf(0 to Hardware.CPU, 2 to Hardware.HYBRID, 3 to Hardware.HYBRID),
            Triple(Hardware.HYBRID, true, false) to mapOf(0 to Hardware.CPU, 2 to Hardware.CPU, 3 to Hardware.CPU),
            Triple(Hardware.HYBRID, false, true) to mapOf(0 to Hardware.CPU, 2 to Hardware.CPU, 3 to Hardware.CPU),
            Triple(Hardware.HYBRID, false, false) to mapOf(0 to Hardware.CPU, 2 to Hardware.CPU, 3 to Hardware.CPU),
            // escolha salva = NPU: só pronta com v3
            Triple(Hardware.NPU, true, true) to mapOf(0 to Hardware.CPU, 2 to Hardware.CPU, 3 to Hardware.NPU),
            Triple(Hardware.NPU, true, false) to mapOf(0 to Hardware.CPU, 2 to Hardware.CPU, 3 to Hardware.CPU),
            Triple(Hardware.NPU, false, true) to mapOf(0 to Hardware.CPU, 2 to Hardware.CPU, 3 to Hardware.CPU),
            Triple(Hardware.NPU, false, false) to mapOf(0 to Hardware.CPU, 2 to Hardware.CPU, 3 to Hardware.CPU),
        )
        var casos = 0
        for ((entrada, porVersao) in esperado) {
            val (salvo, admitido, turbo) = entrada
            for ((versao, hardware) in porVersao) {
                assertEquals(
                    "salvo=$salvo admitido=$admitido turbo=$turbo versao=$versao",
                    hardware,
                    WhisperSelectors.effective(salvo, admitido, turbo, versao),
                )
                casos++
            }
        }
        assertEquals("a matriz tem de cobrir 4 x 2 x 2 x 3", 48, casos)
    }

    /** R123/T01 — o defeito da R122 §3: o v2 sem escolha salva NÃO pode cair em CPU. */
    @Test
    fun `pacote v2 sem escolha salva mantem a NPU hibrida`() {
        assertEquals(Hardware.HYBRID, WhisperSelectors.effective(null, true, true, 2))
        assertEquals(Hardware.NPU, WhisperSelectors.effective(null, true, true, 3))
        assertEquals(Hardware.CPU, WhisperSelectors.effective(null, true, true, 0))
    }

    /**
     * R124/T02 — VACINA da regressão da revisão R123 §3: o hardware é decidido
     * pelo modelo que o JOB roda, não pelo modelo do BOTÃO.
     *
     * O caso real do PJA110: botão em um modelo do whisper.cpp, turbo aceito
     * pela oferta de modelo sem pacote ORT e o pacote v3 instalado. Na 81 isso
     * rodava na NPU; na candidata ia para a CPU porque o "turbo" era calculado
     * pelo modelo do botão.
     */
    @Test
    fun `hardware pelo modelo efetivo do job e nao pelo modelo do botao`() {
        // Botão em modelo SEM pacote ORT (o catálogo só atende turbo/small/tiny/base).
        assertEquals(null, WhisperOrtModelCatalog.modeloParaPedido("ggml-medium"))
        val turboDaOferta = WhisperSelectors.modeloEfetivoDoBotao("ggml-medium", WhisperOrtModelCatalog::modeloParaPedido)
        assertEquals(WhisperOrtModelCatalog.MODEL, turboDaOferta)
        // v3 instalado e SoC admitido: NPU, como na 81.
        assertEquals(Hardware.NPU, WhisperSelectors.effectiveParaModelo(null, true, turboDaOferta, 3))

        // O MESMO botão sem a oferta aceita continua em CPU: a NPU só atende o
        // turbo, e o `tiny` não tem encoder W8A16 (tem export ORT e vai para a CPU).
        assertEquals(WhisperOrtModelCatalog.MODEL_TINY, WhisperOrtModelCatalog.modeloParaPedido("tiny"))
        assertEquals(Hardware.CPU, WhisperSelectors.effectiveParaModelo(null, true, WhisperOrtModelCatalog.MODEL_TINY, 3))
    }

    /** R124/T02 — mesmo botão sem pacote ORT com oferta aceita e pacote v2: `NPU→CPU`, nunca `CPU`. */
    @Test
    fun `modelo efetivo turbo com pacote v2 fica na rota hibrida`() {
        val turboDaOferta = WhisperSelectors.modeloEfetivoDoBotao("ggml-medium", WhisperOrtModelCatalog::modeloParaPedido)
        assertEquals(Hardware.HYBRID, WhisperSelectors.effectiveParaModelo(null, true, turboDaOferta, 2))
    }

    /** R124/T02 — o `small` tem pacote ORT próprio e roda na CPU (nunca NPU). */
    @Test
    fun `modelo small com pacote ORT fica na CPU`() {
        val small = WhisperSelectors.modeloEfetivoDoBotao("small", WhisperOrtModelCatalog::modeloParaPedido)
        assertEquals(WhisperOrtModelCatalog.MODEL_SMALL, small)
        assertEquals(Hardware.CPU, WhisperSelectors.effectiveParaModelo(null, true, small, 3))
    }

    /** R124/T02 — a escolha salva de CPU vence o turbo do job. */
    @Test
    fun `escolha salva de CPU vence o turbo do job`() {
        val turboDaOferta = WhisperSelectors.modeloEfetivoDoBotao("ggml-medium", WhisperOrtModelCatalog::modeloParaPedido)
        assertEquals(Hardware.CPU, WhisperSelectors.effectiveParaModelo(Hardware.CPU, true, turboDaOferta, 3))
    }

    /**
     * R124/T02 — o pedido sem modelo resolvível (nenhum pacote ORT no botão)
     * cai no turbo, que é a única rota que a oferta consentida deixa rodar.
     */
    @Test
    fun `pedido sem pacote ORT resolve para o turbo pela oferta`() {
        val semPacote: (String?) -> String? = { null }
        assertEquals(WhisperSelectors.MODELO_TURBO, WhisperSelectors.modeloEfetivoDoBotao("qualquer", semPacote))
        // A constante pura tem de acompanhar o catálogo do produto.
        assertEquals(WhisperOrtModelCatalog.MODEL, WhisperSelectors.MODELO_TURBO)
    }

    @Test
    fun `ORT ignora o valor salvo do Flash Attention`() {
        assertFalse(WhisperSelectors.flashAttention(true, true))
        assertTrue(WhisperSelectors.flashAttention(false, true))
        assertFalse(WhisperSelectors.flashAttention(false, false))
    }

    @Test
    fun `rotulos do VAD incluem nivel apenas quando ativo`() {
        assertEquals("VAD: Nenhum", LocalVad.Selection().label)
        assertEquals("VAD: Silero CPU (2)", LocalVad.Selection(LocalVad.Mode.SILERO_CPU, 2).label)
        assertEquals("VAD: Silero GPU (1)", LocalVad.Selection(LocalVad.Mode.SILERO_GPU, 1).label)
        assertEquals("VAD: WebRTC (3)", LocalVad.Selection(LocalVad.Mode.WEBRTC, 3).label)
    }
}
