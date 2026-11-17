package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regras do resumo de transcrição (adendo R10 §5).
 *
 * Cobre os números pedidos explicitamente (60/20 = 3 e 40/30 ≈ 1,333), os casos
 * degenerados (zero, desconhecido, divisão por zero) e o lote misto.
 */
class WhisperResumoTest {

    private fun resumo(
        modelo: String? = "Turbo v3",
        hardware: String = "CPU",
        concluidos: Int = 3,
        total: Int = 3,
        duracao: Long? = 60_000,
        tempo: Long? = 20_000,
        status: WhisperResumoStatus = WhisperResumoStatus.CONCLUIDO,
        streaming: Boolean = false,
    ) = WhisperResumo(modelo, hardware, concluidos, total, duracao, tempo, status, streaming)

    @Test
    fun exemploDeArquivosBateComOEsperado() {
        val linhas = resumo().linhas()
        assertEquals("Modelo: Turbo v3", linhas[0])
        assertEquals("Hardware: CPU", linhas[1])
        assertEquals("Áudios transcritos: 3", linhas[2])
        assertEquals("Total de áudio transcrito: 60 s", linhas[3])
        assertEquals("Tempo de processamento: 20 s", linhas[4])
        assertEquals("Eficiência: 3,000x", linhas[5])
        assertEquals("Status: Concluído", linhas[6])
    }

    @Test
    fun exemploDeStreamingBateComOEsperado() {
        val linhas = resumo(concluidos = 1, total = 1, duracao = 40_000, tempo = 30_000, streaming = true).linhas()
        assertEquals("Áudios transcritos: 1 (Streaming)", linhas[2])
        assertEquals("Total de áudio transcrito: 40 s", linhas[3])
        assertEquals("Tempo de processamento: 30 s", linhas[4])
        assertEquals("Eficiência: 1,333x", linhas[5])
    }

    @Test
    fun eficienciaNuncaDividePorZeroNemProduzInfinito() {
        assertNull(resumo(tempo = 0).eficiencia)
        assertEquals(WhisperResumo.INDISPONIVEL, WhisperResumo.formatarEficiencia(resumo(tempo = 0).eficiencia))
        assertNull(resumo(duracao = 0).eficiencia)
        assertNull(resumo(duracao = null).eficiencia)
        assertNull(resumo(tempo = null).eficiencia)
        assertEquals(WhisperResumo.INDISPONIVEL, WhisperResumo.formatarEficiencia(Double.NaN))
        assertEquals(WhisperResumo.INDISPONIVEL, WhisperResumo.formatarEficiencia(Double.POSITIVE_INFINITY))
    }

    @Test
    fun dadoDesconhecidoViraIndisponivelSemInventarNumero() {
        val linhas = resumo(modelo = null, duracao = null, tempo = null, hardware = "").linhas()
        assertEquals("Modelo: indisponível", linhas[0])
        assertEquals("Hardware: indisponível", linhas[1])
        assertEquals("Total de áudio transcrito: indisponível", linhas[3])
        assertEquals("Tempo de processamento: indisponível", linhas[4])
        assertEquals("Eficiência: indisponível", linhas[5])
    }

    @Test
    fun loteMistoSeparaConcluidosDeTotal() {
        val linhas = resumo(concluidos = 2, total = 3, duracao = 40_000, tempo = 10_000,
            status = WhisperResumoStatus.PARCIAL).linhas()
        assertEquals("Áudios transcritos: 2 de 3", linhas[2])
        assertEquals("Status: Parcial", linhas[6])
        assertEquals("Eficiência: 4,000x", linhas[5])
    }

    @Test
    fun erroEcancelamentoMostramStatusCorreto() {
        assertEquals("Status: Erro", resumo(status = WhisperResumoStatus.ERRO).linhas()[6])
        assertEquals("Status: Cancelado", resumo(status = WhisperResumoStatus.CANCELADO).linhas()[6])
    }

    @Test
    fun hardwareVemDoMotorEfetivoEDesconhecidoNaoViraPalpite() {
        assertEquals("CPU", WhisperResumo.hardwareDe("CPU"))
        // R14/§1.1 — hardware efetivo com detalhe preserva o sufixo.
        assertEquals("CPU (6 threads)", WhisperResumo.hardwareDe("CPU (6 threads)"))
        assertEquals("GPU (Vulkan)", WhisperResumo.hardwareDe("GPU+Vulkan"))
        assertEquals("GPU (OpenCL)", WhisperResumo.hardwareDe("GPU+OpenCL"))
        assertEquals(WhisperResumo.INDISPONIVEL, WhisperResumo.hardwareDe(null))
        // R11 §3 — desconhecido NÃO é ecoado, e "gpu" genérico NÃO vira Vulkan
        assertEquals(WhisperResumo.INDISPONIVEL, WhisperResumo.hardwareDe("npu"))
        assertEquals(WhisperResumo.INDISPONIVEL, WhisperResumo.hardwareDe("gpu"))
    }

    @Test
    fun duracaoLongaUsaMinutosEHoras() {
        assertEquals("1:05", WhisperResumo.formatarDuracao(65_000))
        assertEquals("1:00:05", WhisperResumo.formatarDuracao(3_605_000))
        assertTrue(WhisperResumo.formatarDuracao(5_500).endsWith(" s"))
    }

    @Test
    fun eficienciaTemTresCasasComVirgula() {
        assertEquals("3,000x", WhisperResumo.formatarEficiencia(3.0))
        assertEquals("1,333x", WhisperResumo.formatarEficiencia(4.0 / 3.0))
        assertEquals("0,500x", WhisperResumo.formatarEficiencia(0.5))
    }
}
