package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contratos dos tempos de uma rodada de transcrição.
 *
 * O ponto destes testes é separar o que o relatório antigo misturava: a JANELA
 * de rede (que tem o upload dentro e por isso não é tempo de servidor), o
 * ENVIO medido no socket e o PROCESSAMENTO reportado pelo servidor — que pode
 * até ultrapassar a janela quando o servidor tem fila (medido em campo com
 * outro cliente dividindo a GPU). Se um destes quebrar, ou o relatório voltou
 * a somar coisas diferentes, ou a conta ganhou um caso novo que precisa de
 * decisão explícita.
 */
class TranscriptionRunTimingsTest {

    /** Números do teste de campo no emulador (16/09): 1 arquivo de 46:19. */
    private fun timings(
        totalMs: Long = 99_400,
        firstRequestMs: Long? = 10_600,
        preparationMs: Long = 10_400,
        preparedFiles: Int = 1,
        uploadMs: Long? = 32_500,
        serverWindowMs: Long = 88_800,
        serverProcessingMs: Long? = 130_600,
        serverProcessingFiles: Int = 1,
        serverAudioMs: Long? = 2_779_072
    ) = TranscriptionRunTimings(
        totalMs = totalMs,
        firstRequestMs = firstRequestMs,
        preparationMs = preparationMs,
        preparedFiles = preparedFiles,
        uploadMs = uploadMs,
        serverWindowMs = serverWindowMs,
        serverProcessingMs = serverProcessingMs,
        serverProcessingFiles = serverProcessingFiles,
        serverAudioMs = serverAudioMs
    )

    @Test
    fun envio_eMedido_naoDerivadoDaJanela() {
        // O servidor reportou 130,6s (com fila) numa janela de 88,8s: subtrair
        // daria "rede negativa". O envio medido continua sendo o do socket.
        val linhas = timings().reportLines(2779.072)
        assertTrue(linhas.any { it == "- envio pela rede (upload dos arquivos): 32.5s" })
        assertFalse(linhas.any { it.contains("0.0s") })
    }

    @Test
    fun semMedicaoDeEnvio_aLinhaNaoAparece() {
        val linhas = timings(uploadMs = null).reportLines(2779.072)
        assertFalse(linhas.any { it.contains("envio pela rede") })
    }

    @Test
    fun semTemposDoServidor_naoInventaEficienciaDeServidor() {
        val semServidor = timings(serverProcessingMs = null, serverProcessingFiles = 0, serverAudioMs = null)
        assertNull(semServidor.serverEfficiency(2779.072))
        val linhas = semServidor.reportLines(2779.072)
        assertTrue(linhas.any { it.contains("processamento no servidor: não informado") })
        assertFalse(linhas.any { it.startsWith("Eficiência no servidor") })
        assertTrue(linhas.any { it.startsWith("Eficiência da janela de envio+servidor") })
    }

    @Test
    fun eficienciaGeral_usaOTotalDoCliqueAoFim() {
        assertEquals(27.96, timings().generalEfficiency(2779.072), 0.01)
    }

    @Test
    fun eficienciaNoServidor_usaOTempoReportadoPorEle() {
        assertEquals(21.27, timings().serverEfficiency(2779.072)!!, 0.01)
        assertEquals(31.29, timings().windowEfficiency(2779.072), 0.01)
    }

    @Test
    fun temposZerados_naoViramNaN() {
        val zerado = timings(
            totalMs = 0, serverWindowMs = 0, serverProcessingMs = 0,
            uploadMs = 0, preparationMs = 0, serverAudioMs = null
        )
        assertTrue(zerado.generalEfficiency(0.0).isFinite())
        assertTrue(zerado.windowEfficiency(0.0).isFinite())
        assertTrue(zerado.serverEfficiency(0.0)!!.isFinite())
    }

    @Test
    fun linhasDoRelatorio_mostramATotal_aPreparacao_oEnvio_aJanelaEOProcessamento() {
        val linhas = timings().reportLines(2779.072)
        assertTrue(linhas.any { it == "Tempo total (clique -> fim): 99.4s" })
        assertTrue(
            linhas.any {
                it.contains("preparação local") && it.contains("10.4s") &&
                    it.contains("1 arquivo") && it.contains("1º arquivo pronto em 10.6s")
            }
        )
        assertTrue(linhas.any { it.contains("janela de envio + servidor") && it.contains("88.8s") })
        assertTrue(
            linhas.any {
                it.contains("processamento no servidor") && it.contains("130.6s") && it.contains("1 arquivo")
            }
        )
        val eficienciaServidor = linhas.first { it.startsWith("Eficiência no servidor") }
        val valor = eficienciaServidor.substringAfter(": ").removeSuffix("x").toDouble()
        assertEquals(2779.072 / 130.6, valor, 0.01)
    }

    @Test
    fun casoDeTresArquivos_somaPreparacaoEProcessamento() {
        val tres = timings(
            totalMs = 276_600,
            firstRequestMs = 2_300,
            preparationMs = 20_400,
            preparedFiles = 3,
            uploadMs = 96_300,
            serverWindowMs = 274_300,
            serverProcessingMs = 178_800,
            serverProcessingFiles = 3,
            serverAudioMs = 8_337_210
        )
        val linhas = tres.reportLines(8337.2)
        assertTrue(linhas.any { it.contains("20.4s somando 3 arquivo(s)") })
        assertTrue(linhas.any { it.contains("envio pela rede (upload dos arquivos): 96.3s") })
        assertTrue(linhas.any { it.contains("processamento no servidor") && it.contains("178.8s") && it.contains("3 arquivo") })
        assertEquals(30.14, tres.generalEfficiency(8337.2), 0.01)
    }

    @Test
    fun acumulador_medeOEnvioDoPrimeiroEnvioAoUltimoByte() {
        val acumulador = MutableTranscriptionRunTimings()
        acumulador.recordRequestStarted(5_000)
        acumulador.recordRequestStarted(6_000)   // 2ª requisição em paralelo
        acumulador.recordUploadWritten(12_000)
        acumulador.recordUploadWritten(9_000)    // fora de ordem: vale o MAIOR
        val snap = acumulador.snapshot(totalMs = 20_000, firstRequestMs = 4_000, serverWindowMs = 15_000)
        assertEquals(7_000L, snap.uploadMs)
    }

    @Test
    fun acumulador_semMedicaoDeEnvio_naoInventa() {
        val acumulador = MutableTranscriptionRunTimings()
        acumulador.recordRequestStarted(5_000)
        val snap = acumulador.snapshot(totalMs = 20_000, firstRequestMs = 4_000, serverWindowMs = 15_000)
        assertNull(snap.uploadMs)
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
        assertNull(snap.uploadMs)
    }
}
