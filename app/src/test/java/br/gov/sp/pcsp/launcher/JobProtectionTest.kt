package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R17/B1 — [JobProtection]: quem pode liberar e quem pode entrar.
 *
 * O teste executa o CONTROLADOR (não compara string de terminal). O caso
 * central é o defeito da R16: quarentena esgotada sem ack liberava lock/owner.
 */
class JobProtectionTest {

    @Test
    fun `quarentena esgotada SEM ack vira contencao e nao libera`() {
        val d = JobProtection.quarantineExhausted(
            workerFinished = false, workerStarted = true)
        assertEquals(JobProtection.State.STUCK, d.state)
        assertFalse("lock/owner NÃO podem ser liberados", d.release)
        assertFalse("admissão de job novo bloqueada", d.admitNew)
    }

    @Test
    fun `quarentena esgotada COM ack libera`() {
        val d = JobProtection.quarantineExhausted(
            workerFinished = true, workerStarted = true)
        assertEquals(JobProtection.State.RELEASED, d.state)
        assertTrue(d.release)
        assertTrue(d.admitNew)
    }

    @Test
    fun `quarentena esgotada sem worker iniciado libera`() {
        val d = JobProtection.quarantineExhausted(
            workerFinished = false, workerStarted = false)
        assertTrue("nada a proteger", d.release)
    }

    @Test
    fun `sem forwarder com worker iniciado e contencao`() {
        val d = JobProtection.noForwarder(workerStarted = true)
        assertEquals(JobProtection.State.STUCK, d.state)
        assertFalse(d.release)
    }

    @Test
    fun `sem forwarder e nenhum worker iniciado libera`() {
        val d = JobProtection.noForwarder(workerStarted = false)
        assertEquals(JobProtection.State.RELEASED, d.state)
        assertTrue(d.release)
    }

    @Test
    fun `admissao recusada com worker em voo`() {
        val d = JobProtection.admitNew(
            ownerAtivo = true, workerStarted = true, workerFinished = false)
        assertFalse("não pode substituir o dono", d.admitNew)
        assertFalse("não pode liberar o lock do anterior", d.release)
    }

    @Test
    fun `admissao permitida quando nenhum worker iniciou`() {
        val d = JobProtection.admitNew(
            ownerAtivo = true, workerStarted = false, workerFinished = false)
        assertTrue(d.admitNew)
        assertTrue("não há computação a proteger", d.release)
    }

    @Test
    fun `admissao permitida apos ack do worker anterior`() {
        val d = JobProtection.admitNew(
            ownerAtivo = true, workerStarted = true, workerFinished = true)
        assertTrue(d.admitNew)
        assertTrue(d.release)
    }

    @Test
    fun `admissao livre sem dono ativo`() {
        val d = JobProtection.admitNew(
            ownerAtivo = false, workerStarted = false, workerFinished = false)
        assertTrue(d.admitNew)
    }

    @Test
    fun `aceite de cancelamento nao e termino`() {
        val d = JobProtection.step(
            JobProtection.State.CANCELLING, JobProtection.Event.CANCEL_ACCEPTED,
            workerFinished = false)
        assertEquals(JobProtection.State.CANCELLING, d.state)
        assertFalse("aceite não libera", d.release)
    }

    @Test
    fun `STOP da notificacao pede cancel e nao libera`() {
        val d = JobProtection.step(
            JobProtection.State.RUNNING, JobProtection.Event.STOP_REQUESTED,
            workerFinished = false)
        assertEquals(JobProtection.State.CANCELLING, d.state)
        assertFalse(d.release)
    }

    @Test
    fun `somente ack do worker libera no caminho generico`() {
        val d = JobProtection.step(
            JobProtection.State.CANCELLING, JobProtection.Event.WORKER_FINISHED,
            workerFinished = true)
        assertEquals(JobProtection.State.RELEASED, d.state)
        assertTrue(d.release)
    }

    @Test
    fun `matriz de canRelease`() {
        assertTrue(JobProtection.canRelease(true, false, true))    // ack explícito
        assertTrue(JobProtection.canRelease(false, true, true))    // ack registrado
        assertTrue(JobProtection.canRelease(false, false, false))  // nada a proteger
        assertFalse(JobProtection.canRelease(false, false, true))  // worker vivo
    }
}
