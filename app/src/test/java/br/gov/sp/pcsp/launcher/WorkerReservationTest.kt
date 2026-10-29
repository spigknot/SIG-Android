package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R19/C1 — testes de barreira da reserva do worker.
 *
 * Cada caso corresponde a uma intercalação que a ordem exige: reserva antes do
 * init, recusa de segundo dono, ACK atrasado de outra geração e fim forçado.
 */
class WorkerReservationTest {

    private fun novo() = WorkerReservation.State()

    @Test
    fun `reserva simples e idempotente`() {
        val s = novo()
        assertEquals(WorkerReservation.Decision.RESERVED, s.reserve(1L))
        assertTrue(s.heldBy(1L))
        assertEquals(WorkerReservation.Decision.ALREADY_HELD, s.reserve(1L))
    }

    @Test
    fun `segundo dono e recusado e nao rouba a reserva`() {
        val s = novo()
        s.reserve(1L)
        assertEquals(WorkerReservation.Decision.REFUSED_OTHER_OWNER, s.reserve(2L))
        assertTrue(s.heldBy(1L))
        assertFalse(s.heldBy(2L))
    }

    @Test
    fun `release de geracao errada nao libera`() {
        val s = novo()
        s.reserve(1L)
        assertFalse(s.release(2L))
        assertTrue(s.heldBy(1L))
    }

    @Test
    fun `release do dono libera e permite a proxima`() {
        val s = novo()
        s.reserve(1L)
        assertTrue(s.release(1L))
        assertFalse(s.held())
        assertEquals(WorkerReservation.Decision.RESERVED, s.reserve(2L))
    }

    @Test
    fun `adopt da mesma geracao confirma e de outra recusa`() {
        val s = novo()
        s.reserve(5L)
        assertTrue(s.adopt(5L))
        assertFalse(s.adopt(6L))
        assertTrue(s.heldBy(5L))
    }

    @Test
    fun `adopt em reserva vazia assume a geracao do servico`() {
        val s = novo()
        assertTrue(s.adopt(9L))
        assertTrue(s.heldBy(9L))
    }

    @Test
    fun `ack atrasado de A depois de B nao derruba B`() {
        val s = novo()
        s.reserve(1L)
        s.release(1L)
        s.reserve(2L)
        // ACK/terminal atrasado da geração 1 chegando agora:
        assertFalse(s.release(1L))
        assertTrue(s.heldBy(2L))
    }

    @Test
    fun `clear devolve o dono anterior e libera`() {
        val s = novo()
        s.reserve(3L)
        assertEquals(3L, s.clear())
        assertFalse(s.held())
        assertEquals(-1L, s.clear())
    }

    @Test
    fun `geracao negativa e recusada`() {
        val s = novo()
        assertEquals(WorkerReservation.Decision.REFUSED_OTHER_OWNER, s.reserve(-1L))
        assertFalse(s.held())
    }

    @Test
    fun `store compartilhado aplica a mesma politica`() {
        WorkerReservationStore.clear()
        assertEquals(WorkerReservation.Decision.RESERVED, WorkerReservationStore.reserve(7L))
        assertEquals(WorkerReservation.Decision.REFUSED_OTHER_OWNER, WorkerReservationStore.reserve(8L))
        assertTrue(WorkerReservationStore.adopt(7L))
        assertFalse(WorkerReservationStore.release(8L))
        assertTrue(WorkerReservationStore.release(7L))
        assertFalse(WorkerReservationStore.heldBy(7L))
        WorkerReservationStore.clear()
    }
}
