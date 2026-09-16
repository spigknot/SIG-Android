package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contratos dos tempos de uma rodada de transcrição.
 *
 * O ponto destes testes é separar o que o relatório antigo misturava: a
 * JANELA de rede (que tem o upload dentro e por isso não é tempo de servidor)
 * e o PROCESSAMENTO medido pelo próprio servidor. Se um destes quebrar, ou o
 * relatório voltou a somar coisas diferentes, ou a conta ganhou um caso novo
 * que precisa de decisão explícita.
 */
class TranscriptionRunTimingsTest {

    private fun timings(
        totalMs: Long = 276_600,
        firstRequestMs: Long? = 2_300,
        preparationMs: Long = 20_400,
        preparedFiles: Int = 3,
        serverWindowMs: Long = 274_300,
        serverProcessingMs: Long? = 180_300,
        serverProcessingFiles: Int = 3,
        serverAudioMs: Long? = 8_337_210
    ) = TranscriptionRunTimings(
        totalMs = totalMs,
        firstRequestMs = firstRequestMs,
        preparationMs = preparationMs,
        preparedFiles = preparedFiles,
        serverWindowMs = serverWindowMs,
        serverProcessingMs = serverProcessingMs,
        serverProcessingFiles = serverProcessingFiles,
        serverAudioMs = serverAudioMs
    )

    @Test
    fun rede_e_oQueSobraDaJanelaDepoisDoProcessamentoDoServidor() {
        assertEquals(94_000L, timings().networkMs)
    }

    @Test
    fun rede_nuncaFicaNegativa_mesmoSeOServidorReportarMaisQueAJanela() {
        // Arquivos em paralelo podem ter soma de processamento maior que a
        // janela medida no cliente; a conta não pode virar tempo negativo.
        assertEquals(0L, timings(serverWindowMs = 100_000, serverProcessingMs = 180_300).networkMs)
    }

    @Test
    fun semTemposDoServidor_naoInventaRedeNemEficienciaDeServidor() {
        val semServidor = timings(serverProcessingMs = null, serverProcessingFiles = 0)
        assertNull(semServidor.networkMs)
        assertNull(semServidor.serverEfficiency(8336.7))
    }

    @Test
    fun eficienciaGeral_usaOTotalDoCliqueAoFim() {
        assertEquals(30.14, timings().generalEfficiency(8336.7), 0.01)
    }

    @Test
    fun eficienciaDoServidor_usaOProcessamentoRealInformadoPorEle() {
        assertEquals(46.24, timings().serverEfficiency(8336.7)!!, 0.01)
        assertEquals(30.39, timings().windowEfficiency(8336.7), 0.01)
    }

    @Test
    fun temposZerados_naoViramNaN() {
        val zerado = timings(totalMs = 0, serverWindowMs = 0, serverProcessingMs = 0, serverAudioMs = null)
        assertTrue(zerado.generalEfficiency(0.0).isFinite())
        assertTrue(zerado.windowEfficiency(0.0).isFinite())
        assertTrue(zerado.serverEfficiency(0.0)!!.isFinite())
    }

    @Test
    fun linhasDoRelatorio_mostramATotal_ajanela_oProcessamentoEaRede() {
        val linhas = timings().reportLines(8336.7)
        assertTrue(linhas.any { it == "Tempo total (clique -> fim): 276.6s" })
        assertTrue(
            linhas.any {
                it.contains("preparação local") && it.contains("20.4s") &&
                    it.contains("1º arquivo pronto em 2.3s")
            }
        )
        assertTrue(linhas.any { it.contains("janela de envio + servidor") && it.contains("274.3s") })
        assertTrue(
            linhas.any {
                it.contains("processamento no servidor") && it.contains("180.3s") && it.contains("3 arquivo")
            }
        )
        assertTrue(linhas.any { it.contains("rede dentro da janela") && it.contains("94.0s") })

        val eficienciaServidor = linhas.first { it.startsWith("Eficiência do servidor") }
        val valor = eficienciaServidor.substringAfter(": ").removeSuffix("x").toDouble()
        assertEquals(8336.7 / 180.3, valor, 0.01)
    }

    @Test
    fun semTemposDoServidor_oRelatorioDizQueNaoFoiInformado() {
        val linhas = timings(serverProcessingMs = null, serverProcessingFiles = 0).reportLines(8336.7)
        assertTrue(linhas.any { it.contains("processamento no servidor: não informado") })
        assertFalse(linhas.any { it.startsWith("Eficiência do servidor") })
        assertTrue(linhas.any { it.startsWith("Eficiência da janela de envio+servidor") })
    }

    @Test
    fun acumulador_somaPreparacaoETemposDoServidorDeThreadsParalelas() {
        val acumulador = MutableTranscriptionRunTimings()
        val threads = (1..3).map {
            Thread {
                acumulador.recordPreparation(1_000)
                acumulador.recordServerTiming(SttResponseParsers.ServerTiming(59.61, 2779.07, 1))
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        val snap = acumulador.snapshot(totalMs = 276_600, firstRequestMs = 2_300, serverWindowMs = 274_300)
        assertEquals(3_000L, snap.preparationMs)
        assertEquals(3, snap.preparedFiles)
        assertEquals(178_830L, snap.serverProcessingMs)
        assertEquals(3, snap.serverProcessingFiles)
        assertEquals(8_337_210L, snap.serverAudioMs)
    }

    @Test
    fun acumulador_semRegistros_naoInventaTempos() {
        val snap = MutableTranscriptionRunTimings().snapshot(totalMs = 10, firstRequestMs = null, serverWindowMs = 0)
        assertEquals(0L, snap.preparationMs)
        assertEquals(0, snap.preparedFiles)
        assertNull(snap.serverProcessingMs)
        assertNull(snap.serverAudioMs)
        assertNull(snap.networkMs)
    }
}
