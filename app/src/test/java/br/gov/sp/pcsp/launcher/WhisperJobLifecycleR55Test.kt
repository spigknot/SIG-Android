package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R55/B01-B03 — PORTA DE ADMISSÃO, IDENTIDADE DELIMITADA e TERMINAL SEM UI/SERVIÇO.
 *
 * Cada teste corresponde a um ponto da ordem que o código de produção executa
 * nos dois caminhos reais (`WhisperJobService.startJob` pré-Serviço,
 * `onStartCommand`→`beginJob`, o worker da Activity e `finishJob`):
 *
 *  * a sequência reserve7 → release7 → reserve7 tardio → reserve8 recusado;
 *  * a geração encerrada como identidade delimitada (um escalar, sem `clear`
 *    global e sem bloquear o futuro);
 *  * terminal antes de `onStart` — libera e vira estado;
 *  * duplicata, outra identidade e sucessor;
 *  * o evento do terminal é correlacionado e NUNCA um ACK inventado.
 */
class WhisperJobLifecycleR55Test {

    // ------------------------------------------------ B02: identidade da reserva

    @Test
    fun `reserve7 release7 reserve7 tardio e recusado e reserve8 entra`() {
        WorkerReservationStore.clear()

        // Dono reserva antes do primeiro passo bloqueante.
        assertEquals(WorkerReservation.Decision.RESERVED, WorkerReservationStore.reserve(7L))
        // Terminal do worker: libera e REGISTRA a geração como encerrada.
        assertTrue(WorkerReservationStore.release(7L, "SUCCESS"))

        // ACTION_START/retry atrasado da MESMA geração (chega depois do fim).
        assertEquals(WorkerReservation.Decision.REFUSED_STALE_GENERATION,
            WorkerReservationStore.reserve(7L))
        // A recusa não ocupa nada: o dono continua vazio…
        assertEquals(-1L, WorkerReservationStore.owner())
        assertFalse(WorkerReservationStore.heldBy(7L))
        // …e a geração seguinte entra normalmente.
        assertEquals(WorkerReservation.Decision.RESERVED, WorkerReservationStore.reserve(8L))
        assertEquals(8L, WorkerReservationStore.owner())
        WorkerReservationStore.clear()
    }

    @Test
    fun `identidade e um escalar - encerradas ficam para tras e as futuras entram`() {
        WorkerReservationStore.clear()
        for (gen in 4L..9L) {
            assertEquals(WorkerReservation.Decision.RESERVED, WorkerReservationStore.reserve(gen))
            assertTrue(WorkerReservationStore.release(gen, "SUCCESS"))
            assertEquals(gen, WorkerReservationStore.lastTerminalGen())
            // Nada de cemitério: só a última geração encerrada é lembrada.
            for (stale in 4L..gen) {
                assertEquals(WorkerReservation.Decision.REFUSED_STALE_GENERATION,
                    WorkerReservationStore.reserve(stale))
            }
            assertEquals(-1L, WorkerReservationStore.owner())
        }
        // A próxima ainda entra: o limite nunca bloqueia o futuro.
        assertEquals(WorkerReservation.Decision.RESERVED, WorkerReservationStore.reserve(10L))
        WorkerReservationStore.clear()
    }

    @Test
    fun `adopt nao reabre geracao encerrada`() {
        val s = WorkerReservation.State()
        s.reserve(3L)
        s.release(3L, "CANCELLED")
        assertFalse(s.adopt(3L))
        assertFalse(s.held())
        assertTrue(s.adopt(4L))
    }

    @Test
    fun `terminal de geracao futura nao e bloqueado pelo limite`() {
        WorkerReservationStore.clear()
        WorkerReservationStore.reserve(20L)
        WorkerReservationStore.release(20L, "SUCCESS")
        // Gerações futuras (inclusive com salto) entram.
        assertEquals(WorkerReservation.Decision.RESERVED, WorkerReservationStore.reserve(21L))
        assertTrue(WorkerReservationStore.release(21L, "SUCCESS"))
        assertEquals(WorkerReservation.Decision.RESERVED, WorkerReservationStore.reserve(99L))
        WorkerReservationStore.clear()
    }

    // ------------------------------------------------ B01: porta de admissão

    @Test
    fun `porta unica traduz as quatro decisoes`() {
        val reservado = WhisperJobLifecycle.admission(WorkerReservation.Decision.RESERVED)
        assertTrue(reservado.start)
        assertEquals("reservado", reservado.reason)

        val mesma = WhisperJobLifecycle.admission(WorkerReservation.Decision.ALREADY_HELD)
        assertTrue(mesma.start)

        val outra = WhisperJobLifecycle.admission(WorkerReservation.Decision.REFUSED_OTHER_OWNER)
        assertFalse(outra.start)
        assertEquals("outra_geracao_detem_a_reserva", outra.reason)

        val encerrada =
            WhisperJobLifecycle.admission(WorkerReservation.Decision.REFUSED_STALE_GENERATION)
        assertFalse(encerrada.start)
        assertEquals("geracao_ja_encerrada", encerrada.reason)
    }

    @Test
    fun `nenhuma decisao de recusa e aceita pela porta`() {
        for (d in WorkerReservation.Decision.values()) {
            val esperado = d == WorkerReservation.Decision.RESERVED ||
                d == WorkerReservation.Decision.ALREADY_HELD
            assertEquals("decisao $d", esperado, WhisperJobLifecycle.admission(d).start)
        }
    }

    // ------------------------------------------- B01/B03: terminal e evidência

    @Test
    fun `terminal sem servico so libera com terminal provado`() {
        // Equivalência com a regra do caminho COM Serviço (JobProtection.canRelease).
        assertTrue(WhisperJobLifecycle.releasesReservedOnProvenTerminal(
            WhisperJobLifecycle.TerminalKind.WORKER_ACK, heldByGeneration = true))
        assertTrue(JobProtection.canRelease(
            fromWorker = true, workerFinished = true,
            workerStarted = false, reserved = true))
        // Sem a reserva desta geração, nada a liberar.
        assertFalse(WhisperJobLifecycle.releasesReservedOnProvenTerminal(
            WhisperJobLifecycle.TerminalKind.WORKER_ACK, heldByGeneration = false))
        // Nome desconhecido não é ACK.
        assertFalse(WhisperJobLifecycle.releasesReservedOnProvenTerminal(
            WhisperJobLifecycle.TerminalKind.UNKNOWN, heldByGeneration = true))
    }

    @Test
    fun `classificacao dos terminais usados pelo produto`() {
        assertEquals(WhisperJobLifecycle.TerminalKind.WORKER_ACK,
            WhisperJobLifecycle.terminalKind("SUCCESS"))
        assertEquals(WhisperJobLifecycle.TerminalKind.WORKER_ACK,
            WhisperJobLifecycle.terminalKind("CANCELLED"))
        assertEquals(WhisperJobLifecycle.TerminalKind.WORKER_ACK,
            WhisperJobLifecycle.terminalKind("ERROR"))
        assertEquals(WhisperJobLifecycle.TerminalKind.OWNER_DECLARED,
            WhisperJobLifecycle.terminalKind("LIVE_TERMINAL"))
        assertEquals(WhisperJobLifecycle.TerminalKind.UNKNOWN,
            WhisperJobLifecycle.terminalKind("OWNER_GONE_TIMEOUT"))
    }

    @Test
    fun `evento do terminal carrega a geracao e a origem`() {
        val json = WhisperJobLifecycle.terminalEventJson(
            generation = 42L, terminal = "SUCCESS", owner = 42L,
            released = true, origem = "sem_servico"
        )
        assertTrue(json.contains("\"gen\":42"))
        assertTrue(json.contains("\"terminal\":\"SUCCESS\""))
        assertTrue(json.contains("\"released\":true"))
        assertTrue(json.contains("\"origem\":\"sem_servico\""))
    }

    @Test
    fun `terminal recusado nunca publica o nome do evento de terminal`() {
        WorkerReservationStore.clear()
        WorkerReservationStore.reserve(31L)
        // 32 não é dono: o resultado é recusa, com evento próprio.
        val r = WhisperJobLifecycle.applyTerminalWithoutService(
            32L, "SUCCESS",
            { gen -> WorkerReservationStore.heldBy(gen) },
            { gen, term -> WorkerReservationStore.release(gen, term) },
        )
        assertNotEquals("job_fgs_terminal", r.event)
        assertEquals("job_terminal_recusado", r.event)
        assertEquals(31L, WorkerReservationStore.owner())
        WorkerReservationStore.clear()
    }

    @Test
    fun `ACK antes de onStart libera a reserva e o servico tardio nao a reabre`() {
        WorkerReservationStore.clear()

        // 1. startJob (pré-Serviço) reserva a geração.
        assertTrue(WhisperJobLifecycle.admission(WorkerReservationStore.reserve(5L)).start)

        // 2. O worker termina ANTES de o Serviço existir (finishJob sem instância).
        val r = WhisperJobLifecycle.applyTerminalWithoutService(
            5L, "SUCCESS",
            { gen -> WorkerReservationStore.heldBy(gen) },
            { gen, term -> WorkerReservationStore.release(gen, term) },
        )
        assertTrue(r.released)
        assertEquals(5L, WorkerReservationStore.lastTerminalGen())

        // 3. O ACTION_START atrasado chega: o Serviço NÃO assume um dono morto.
        val porta = WhisperJobLifecycle.admission(WorkerReservationStore.reserve(5L))
        assertFalse(porta.start)
        assertEquals("geracao_ja_encerrada", porta.reason)

        // 4. E o próximo job entra sem intervenção manual (nada de force-stop).
        assertTrue(WhisperJobLifecycle.admission(WorkerReservationStore.reserve(6L)).start)
        WorkerReservationStore.clear()
    }
}
