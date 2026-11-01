package br.gov.sp.pcsp.launcher

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ADENDO 19 §1 — ciclo integrado do serviço com IDENTIDADES DISTINTAS
 * (token de reserva × geração lógica), NO MÉTODO REAL.
 *
 * O revisor apontou em `WhisperJobService` e `ReservationJobIdentity`:
 *  - onOwnerGone aceitava a identidade traduzida SÓ no `isOwner`, mas agendava
 *    o timer e encaminhava o cancel pela geração lógica — o timer comparava
 *    lógico×token e descartava o próprio destroy como stale;
 *  - o caminho sem serviço esquecia o vínculo mesmo com a política RECUSANDO
 *    o terminal (vínculo necessário ao cancelamento preservado só enquanto o
 *    job vive).
 *
 * Estes testes exercem os métodos REAIS do serviço (companion + instância) —
 * não apenas a política pura — com identidades que NÃO coincidem numericamente.
 */
@RunWith(AndroidJUnit4::class)
class WhisperJobServiceOwnershipIdentityTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val tokensTeste = mutableListOf<Long>()

    @After
    fun repositar() {
        for (t in tokensTeste) ReservationJobIdentity.esquecer(t)
        tokensTeste.clear()
    }

    /**
     * §1.1 — onOwnerGone(logica) com dono token: o AGENDAMENTO do timer sai no
     * TOKEN do dono do serviço (a comparação do timer é token×token), não na
     * geração lógica do chamador. Observação só-leitura por
     * `ownerGoneScheduledFor()`.
     */
    @Test
    fun ownerGoneComIdentidadeDistintaAgendaNoToken() {
        WorkerReservationStore.clear()
        val genLogica = WhisperSessions.shared.beginSession()
        val token = WorkerReservation.Tokens.alocar(SystemClock.elapsedRealtime())
        tokensTeste.add(token)
        ReservationJobIdentity.registrar(token, genLogica)
        val outcome = WhisperJobService.startJobOutcome(
            ctx, token, WhisperJobLifecycle.KIND_FILE, "ad19 §1.1"
        )
        // Recusa condicional honesta: sem FGS pleno nesta janela (recusado/não
        // iniciado) o método real não existe — o cenário fica pendente no
        // XML, não reconstruído por um fake.
        assumeTrue(
            "FGS não subiu nesta janela (cenário fica pendente no XML)",
            outcome == WhisperJobService.StartOutcome.STARTED,
        )
        val donoSubiu = aguardar(condicao = { WhisperJobService.owner() == token })
        assertTrue("dono do FGS deveria ser o token", donoSubiu)

        WhisperJobService.ownerGone(genLogica)
        assertEquals(
            "agendamento do destroy sai NO TOKEN do dono (timer token×token)",
            token, WhisperJobService.ownerGoneScheduledFor(),
        )
        // Limpeza: encerra o job (workerStarted + terminal tipado) para a
        // reserva liberar; o timer pendente vira stale e é ignorado.
        encerrarJob(token)
    }

    /**
     * §1.3 — TERMINAL RECUSADO (sem serviço dono) NÃO esquece o vínculo;
     * terminal ACEITO esquece. Sempre pelo MÉTODO REAL da companion.
     */
    @Test
    fun terminalSemServicoRecusadoPreservaParEAceitoEsquece() {
        WorkerReservationStore.clear()
        val token = WorkerReservation.Tokens.alocar(SystemClock.elapsedRealtime())
        tokensTeste.add(token)
        val genLogica = 88_881L
        ReservationJobIdentity.registrar(token, genLogica)

        // RECUSADO: `heldBy(token)` é false (nunca reservou) → política não
        // libera → resultado.released=false → PAR deve sobreviver.
        WhisperJobService.finishJob(token, "CANCELLED")
        assertEquals(
            "vínculo preservado com terminal recusado (job ainda vive)",
            genLogica, ReservationJobIdentity.logicoDe(token),
        )

        // ACEITO: reserva no store + terminal provado → released=true → PAR
        // esquecido no terminal provado.
        assertEquals(
            WorkerReservation.Decision.RESERVED,
            WorkerReservationStore.reserve(token),
        )
        WhisperJobService.finishJob(token, "SUCCESS")
        assertFalse("reserva liberada", WorkerReservationStore.heldBy(token))
        assertNull("PAR esquecido no terminal provado", ReservationJobIdentity.logicoDe(token))
    }

    /**
     * §1.3 — TERMINAL DESCONHECIDO com RESERVA REALMENTE RETIDA: o terminal
     * inválido NÃO libera a reserva NEM esquece o vínculo (ambos sobrevivem —
     * um cancelamento pendente continua alcançável); o terminal VÁLIDO
     * posterior encerra AMBOS pelo ciclo normal do serviço.
     */
    @Test
    fun terminalDesconhecidoRetemVinculoEReservaEValidoPosteriorEncerraAmbos() {
        WorkerReservationStore.clear()
        val token = WorkerReservation.Tokens.alocar(SystemClock.elapsedRealtime())
        tokensTeste.add(token)
        val genLogica = 88_882L
        ReservationJobIdentity.registrar(token, genLogica)
        // Reserva de verdade — não é chamada isolada ao Store: começa como
        // real FGS dono quando o serviço está disponível nesta janela.
        val outcome = WhisperJobService.startJobOutcome(
            ctx, token, WhisperJobLifecycle.KIND_FILE, "ad20 §1 desconhecido"
        )
        // Quando não há FGS nesta janela, o estado retido é o do STORE (a
        // reserva real foi feita por startJobOutcome mesmo assim; se o FGS
        // falhar, o par e a reserva continuam reais no processo).
        assertEquals(
            "admissão do dono deveria reservar o token",
            WorkerReservationStore.heldBy(token), true,
        )
        when (outcome) {
            WhisperJobService.StartOutcome.ADMISSAO_RECUSADA ->
                org.junit.Assume.assumeFalse("janela sem FGS: cenário fica pendente no XML", true)
            else -> {}
        }
        // TERMINAL DESCONHECIDO: nome que não é ack (kind UNKNOWN).
        WhisperJobService.finishJob(token, "DEFEITO_INVALIDO")
        assertTrue(
            "reserva de verdade permanece retida com terminal desconhecido",
            WorkerReservationStore.heldBy(token),
        )
        assertEquals(
            "vínculo permanece com terminal desconhecido",
            genLogica, ReservationJobIdentity.logicoDe(token),
        )
        // TERMINAL VÁLIDO posterior: encerra vínculo E reserva pelo ciclo
        // normal (workerStarted + tipo conhecido).
        encerrarJob(token)
        assertFalse("reserva liberada pelo terminal válido", WorkerReservationStore.heldBy(token))
        assertNull("vínculo fechado pelo terminal válido", ReservationJobIdentity.logicoDe(token))
    }

    /** Encerra o job do teste: marca o worker e fecha com terminal tipado.
     * ADENDO 20 §1 — a falha de encerramento REAL FALHA O TESTE: a prova é o
     * ciclo normal liberar a reserva. A limpeza de fixture (clear) fica
     * SEPARADA, depois da prova, e não serve como resultado; não limpa
     * trabalho de outro dono (só limpa no estado de teste desta classe). */
    private fun encerrarJob(token: Long) {
        for (tentativa in 0 until 5) {
            WhisperJobService.markWorkerStarted(ctx, token)
            Thread.sleep(300)
            WhisperJobService.finishJob(token, "CANCELLED")
            if (!WorkerReservationStore.heldBy(token)) return
        }
        // PROVA antes de qualquer limpeza: se o ciclo normal não liberou, o
        // teste falha AQUI (não disfarça com clear + assertFalse).
        assertTrue(
            "encerramento real não liberou a reserva (ciclo do serviço)",
            !WorkerReservationStore.heldBy(token),
        )
        // Limpeza de fixture, separada da prova (nunca toca alheio: a classe
        // garante que o token é dela via tokensTeste).
        WorkerReservationStore.clear()
        assertFalse(WorkerReservationStore.heldBy(token))
    }

    private fun aguardar(condicao: () -> Boolean, tetoMs: Long = 5_000L): Boolean {
        val inicio = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - inicio < tetoMs) {
            if (condicao()) return true
            Thread.sleep(50)
        }
        return condicao()
    }
}
