package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R44/E02 + R55/B01 — reserva que termina antes de o SERVIÇO existir.
 *
 * O defeito original: `WhisperJobService.finishJob` fazia `instance ?: return`.
 * A reserva é feita pelo dono ANTES do startService; se o serviço nunca era
 * instanciado (destroy/cancel/erro de carga nessa janela), o ACK real do worker
 * era descartado e `WorkerReservationStore.release` — chamado só de dentro de
 * `stopJob` — nunca rodava. A reserva ficava presa na geração e a próxima
 * recebia REFUSED_OTHER_OWNER.
 *
 * R55/B01: a decisão do caminho sem Serviço deixou de ser um gate paralelo
 * (`releaseWithoutService`) e passou a ser
 * [WhisperJobLifecycle.applyTerminalWithoutService] — a MESMA função que
 * `WhisperJobService.finishJob` executa quando não há Serviço dono da geração.
 * Estes testes chamam essa função real, com o store real, e não uma
 * reimplementação do teste.
 */
class JobReservationSemServicoTest {

    private fun storeHeld(gen: Long): Boolean = WorkerReservationStore.heldBy(gen)
    private fun storeRelease(gen: Long, terminal: String): Boolean =
        WorkerReservationStore.release(gen, terminal)

    private fun terminal(gen: Long, t: String) =
        WhisperJobLifecycle.applyTerminalWithoutService(gen, t, ::storeHeld, ::storeRelease)

    // ---------------------------------------------------------------- decisão --

    @Test
    fun `terminal de worker sem servico libera e vira estado`() {
        WorkerReservationStore.clear()
        assertEquals(WorkerReservation.Decision.RESERVED, WorkerReservationStore.reserve(7L))

        val r = terminal(7L, "SUCCESS")

        assertEquals(WhisperJobLifecycle.TerminalKind.WORKER_ACK, r.kind)
        assertTrue(r.released)
        assertTrue(r.recorded)
        assertEquals("job_fgs_terminal", r.event)
        // Estado, não log: a geração e o terminal ficam registrados no store.
        assertEquals(7L, WorkerReservationStore.lastTerminalGen())
        assertEquals("SUCCESS", WorkerReservationStore.lastTerminal())
        assertFalse(WorkerReservationStore.heldBy(7L))
        WorkerReservationStore.clear()
    }

    @Test
    fun `terminal sem reserva nao inventa estado`() {
        WorkerReservationStore.clear()
        val r = terminal(8L, "SUCCESS")
        assertFalse(r.released)
        assertFalse(r.recorded)
        // ACK não provado nunca sai com o nome do evento de terminal.
        assertEquals("job_terminal_recusado", r.event)
        assertEquals(-1L, WorkerReservationStore.lastTerminalGen())
        WorkerReservationStore.clear()
    }

    @Test
    fun `terminal desconhecido nao libera nem encerra`() {
        WorkerReservationStore.clear()
        WorkerReservationStore.reserve(9L)

        val r = terminal(9L, "QUALQUER_COISA")

        assertEquals(WhisperJobLifecycle.TerminalKind.UNKNOWN, r.kind)
        assertFalse(r.released)
        assertEquals("job_terminal_recusado", r.event)
        assertTrue(WorkerReservationStore.heldBy(9L))
        WorkerReservationStore.clear()
    }

    // ------------------------------------------------------------ store real --

    @Test
    fun `reserva sobrevive sem servico e a proxima geracao entra apos o ACK`() {
        WorkerReservationStore.clear()
        assertEquals(WorkerReservation.Decision.RESERVED, WorkerReservationStore.reserve(7L))

        // Serviço nunca existiu: finishJob cai no ramo sem instância.
        assertTrue(terminal(7L, "SUCCESS").released)

        // A reserva da mesma geração foi liberada e a próxima consegue entrar.
        assertFalse(WorkerReservationStore.heldBy(7L))
        assertEquals(WorkerReservation.Decision.RESERVED, WorkerReservationStore.reserve(8L))
        assertEquals(8L, WorkerReservationStore.owner())
        WorkerReservationStore.clear()
    }

    @Test
    fun `ACK de outra geracao nao libera a reserva do dono`() {
        WorkerReservationStore.clear()
        assertEquals(WorkerReservation.Decision.RESERVED, WorkerReservationStore.reserve(7L))

        // Chega um terminal da geração 8: não é o dono, não libera.
        val alheio = terminal(8L, "SUCCESS")
        assertFalse(alheio.released)
        assertFalse(WorkerReservationStore.release(8L))
        assertEquals(7L, WorkerReservationStore.owner())

        // E a geração 8 não consegue entrar enquanto a 7 segura.
        assertEquals(WorkerReservation.Decision.REFUSED_OTHER_OWNER,
            WorkerReservationStore.reserve(8L))
        WorkerReservationStore.clear()
    }

    @Test
    fun `terminal duplicado da mesma geracao e idempotente`() {
        WorkerReservationStore.clear()
        WorkerReservationStore.reserve(9L)
        assertTrue(terminal(9L, "ERROR").released)
        // Segundo ACK: já não há reserva desta geração; nada muda.
        val segundo = terminal(9L, "ERROR")
        assertFalse(segundo.released)
        assertFalse(segundo.recorded)
        assertFalse(WorkerReservationStore.heldBy(9L))
        assertEquals(-1L, WorkerReservationStore.owner())
        // O registro continua sendo o do primeiro terminal (não é reescrito).
        assertEquals(9L, WorkerReservationStore.lastTerminalGen())
        assertEquals("ERROR", WorkerReservationStore.lastTerminal())
        WorkerReservationStore.clear()
    }

    @Test
    fun `sucessor nao e liberado pelo terminal antigo`() {
        WorkerReservationStore.clear()
        WorkerReservationStore.reserve(10L)
        assertTrue(terminal(10L, "SUCCESS").released)
        WorkerReservationStore.reserve(11L)
        // Terminal atrasado da 10 não pode derrubar a 11.
        assertFalse(terminal(10L, "SUCCESS").released)
        assertEquals(11L, WorkerReservationStore.owner())
        WorkerReservationStore.clear()
    }
}
