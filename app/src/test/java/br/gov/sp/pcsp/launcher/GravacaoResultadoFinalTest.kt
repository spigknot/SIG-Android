package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R56/adendo19 — focal COMPORTAMENTAL do finalizador da gravação.
 *
 * Não conta strings nem lê a fonte: exercita o ledger real e verifica que
 *  - o caminho de ERRO pré-parse publica resultado com job/owner próprios;
 *  - o terminal sai EXATAMENTE uma vez, mesmo com chamadas repetidas;
 *  - outra geração não herda nem sobrescreve o resultado;
 *  - a publicação não depende de UI (nada de Looper/Handler aqui).
 */
class GravacaoResultadoFinalTest {

    private fun ledgerNovo(): TranscriptionJobLedger = TranscriptionJobLedger()

    private fun abreJob(ledger: TranscriptionJobLedger, gen: Long): Long {
        val jobId = ledger.beginJob("run-teste", 1, gen)
        ledger.beginAttempt(jobId, gen, "gravacao.wav", "run-teste", 1)
        return jobId
    }

    @Test
    fun `erro de carga publica resultado ERROR do proprio job`() {
        val ledger = ledgerNovo()
        val jobId = abreJob(ledger, 11L)
        val fin = GravacaoResultadoFinal(ledger, jobId, 11L)

        // o worker NÃO produziu segmento: o caminho de falha de carga
        assertTrue(fin.publicar(JobTerminalState.ERROR, "model_load_failed"))

        val r = ledger.latest()
        assertNotNull("resultado não publicado", r)
        assertEquals(JobTerminalState.ERROR, r!!.terminal)
        assertEquals(jobId, r.key.jobId)
        assertEquals(11L, r.key.owner)
        assertEquals("ERROR", fin.terminalDeAck())
    }

    @Test
    fun `terminal e publicado uma unica vez`() {
        val ledger = ledgerNovo()
        val jobId = abreJob(ledger, 12L)
        val fin = GravacaoResultadoFinal(ledger, jobId, 12L)

        assertTrue(fin.publicar(JobTerminalState.SUCCESS, "ok"))
        val seqAntes = ledger.currentSeq()
        val primeiro = ledger.latest()

        // segunda tentativa (finally/catch correndo depois) não republica
        assertFalse(fin.publicar(JobTerminalState.ERROR, "catch_tardio"))
        assertEquals(1, fin.tentativas)
        assertEquals(JobTerminalState.SUCCESS, ledger.latest()!!.terminal)
        assertEquals("seq de publicação não pode avançar", seqAntes, ledger.currentSeq())
        assertSame(primeiro, ledger.latest())
    }

    @Test
    fun `owner de outra geracao nao publica`() {
        val ledger = ledgerNovo()
        val jobId = abreJob(ledger, 13L)
        // finalizador construído com owner errado: o ledger recusa
        val intruso = GravacaoResultadoFinal(ledger, jobId, 999L)
        assertFalse(intruso.publicar(JobTerminalState.SUCCESS, "ok"))
        assertNull("não pode marcar publicado", intruso.publicado)
        // o job original continua sem terminal
        assertNull(ledger.latest())
    }

    @Test
    fun `NoSpeech vira SUCCESS no ACK`() {
        val ledger = ledgerNovo()
        val jobId = abreJob(ledger, 14L)
        val fin = GravacaoResultadoFinal(ledger, jobId, 14L)
        assertTrue(fin.publicar(JobTerminalState.NO_SPEECH, "sem_fala"))
        assertEquals(JobTerminalState.NO_SPEECH, ledger.latest()!!.terminal)
        assertEquals("SUCCESS", fin.terminalDeAck())
    }

    @Test
    fun `job seguinte nao herda resultado do anterior`() {
        val ledger = ledgerNovo()
        val j1 = abreJob(ledger, 15L)
        val f1 = GravacaoResultadoFinal(ledger, j1, 15L)
        assertTrue(f1.publicar(JobTerminalState.SUCCESS, "ok"))

        val j2 = abreJob(ledger, 16L)
        val f2 = GravacaoResultadoFinal(ledger, j2, 16L)
        assertTrue(f2.publicar(JobTerminalState.ERROR, "falhou"))

        val r = ledger.latest()!!
        assertEquals(j2, r.key.jobId)
        assertEquals(16L, r.key.owner)
        assertEquals(JobTerminalState.ERROR, r.terminal)
        // o finalizador antigo não republica sobre o novo
        assertFalse(f1.publicar(JobTerminalState.CANCELLED, "tardio"))
    }
}
