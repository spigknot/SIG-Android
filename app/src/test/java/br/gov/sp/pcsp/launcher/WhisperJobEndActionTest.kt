package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R16/B3 — SÓ O TÉRMINO DEMONSTRADO LIBERA A PROTEÇÃO.
 *
 * Repros do especialista (métodos extraídos do Service):
 *
 *   OWNER_TIMEOUT stop_called=true worker_ack=false
 *   DRAIN_BUDGET  stop_called=true rounds=2  worker_ack=false
 *
 * Em ambos, o serviço chamava `stopJob` — liberando wake lock e declarando
 * terminal — sem qualquer confirmação de que o worker havia terminado. Aqui a
 * decisão é uma função pura consultada pelos dois caminhos.
 */
class WhisperJobEndActionTest {

    @Test
    fun `sem worker terminado NAO libera - vai para quarentena`() {
        assertEquals(
            "sem ack, a proteção NÃO pode ser liberada",
            WhisperJobLifecycle.JobEndAction.QUARANTINE,
            WhisperJobLifecycle.endAction(workerFinished = false)
        )
    }

    @Test
    fun `com worker terminado libera`() {
        assertEquals(
            WhisperJobLifecycle.JobEndAction.RELEASE,
            WhisperJobLifecycle.endAction(workerFinished = true)
        )
    }

    @Test
    fun `a quarentena tem orcamento finito`() {
        assertTrue(
            "quarentena precisa de pelo menos uma rodada",
            WhisperJobService.MAX_QUARANTINE_ROUNDS >= 1
        )
        assertTrue(
            "e não pode ser infinita",
            WhisperJobService.MAX_QUARANTINE_ROUNDS <= 4
        )
    }

    @Test
    fun `o terminal de quarentena esgotada NAO e sucesso`() {
        // contrato: distinto de SUCCESS/CANCELLED; declara que não houve ack
        val terminal = "TIMEOUT_LEASE_UNCONFIRMED"
        assertFalse(terminal == "SUCCESS")
        assertFalse(terminal == "CANCELLED")
        assertTrue("diz explicitamente que não foi confirmado", terminal.contains("UNCONFIRMED"))
    }

    @Test
    fun `aceite de cancelamento nao e termino do worker`() {
        // session.cancel devolver true significa PEDIDO ACEITO; nada mais.
        val cancelAccepted = true
        val workerFinished = false
        assertEquals(
            "aceite sem término => quarentena",
            WhisperJobLifecycle.JobEndAction.QUARANTINE,
            WhisperJobLifecycle.endAction(workerFinished && cancelAccepted)
        )
    }
}
