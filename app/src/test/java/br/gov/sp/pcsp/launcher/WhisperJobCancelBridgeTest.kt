package br.gov.sp.pcsp.launcher

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R15/B3 — O FORWARDER DE CANCELAMENTO SOBREVIVE À DONA.
 *
 * Defeito da R14: o forwarder era um campo da `WhisperActivity` e era
 * desregistrado no `onDestroy`. Morrendo a dona com o worker ainda drenando a
 * GPU, o serviço ficava sem ninguém para aceitar o cancelamento. Como o job
 * continuava vivo no processo, o resultado era um worker órfão: o serviço
 * declarava terminal e soltava o wake lock no meio do dreno.
 *
 * A correção separa REGISTRO (processo, permanente) de UI (referência fraca,
 * descartável). Estes testes fixam que:
 *  - anexar registra UMA vez (idempotente);
 *  - desanexar a UI NÃO desregistra o forwarder;   <- a regressão da R14
 *  - o destroy atrasado de A não apaga a referência de B.
 *
 * ADENDO 19 §1.2 — a UI é avisada com a GERAÇÃO LÓGICA traduzida (não o
 * token do serviço) e SÓ com ACK comprovado; encaminhamento alheio/atrasado
 * (sem PAR ou sem aceitação da sessão) não alimenta a UI.
 */
class WhisperJobCancelBridgeTest {

    private class SinkFalso : JobCancelBridge.UiSink {
        val recebidos = mutableListOf<Pair<Long, Boolean>>()
        override fun onJobCancelForwarded(generation: Long, aceito: Boolean) {
            recebidos.add(generation to aceito)
        }
    }

    @After
    fun limpar() {
        ReservationJobIdentity.esquecer(12345L)
        JobCancelBridge.resetForTest()
    }

    @Test
    fun `anexar registra o forwarder uma unica vez`() {
        JobCancelBridge.resetForTest()
        assertEquals("estado inicial limpo", 0, WhisperJobService.cancelForwarderCount)
        val a = SinkFalso()
        val b = SinkFalso()
        JobCancelBridge.attach(a)
        JobCancelBridge.attach(b)
        assertEquals(
            "duas Activities não podem empilhar forwarders",
            1, WhisperJobService.cancelForwarderCount
        )
    }

    @Test
    fun `regressao R14 - desanexar a UI mantem o forwarder registrado`() {
        JobCancelBridge.resetForTest()
        val sink = SinkFalso()
        JobCancelBridge.attach(sink)
        assertEquals(1, WhisperJobService.cancelForwarderCount)

        JobCancelBridge.detach(sink)

        assertEquals(
            "R15/B3: o registro é de PROCESSO — destroy da dona não pode " +
                "deixar o worker sem quem aceite o cancelamento",
            1, WhisperJobService.cancelForwarderCount
        )
    }

    @Test
    fun `destroy atrasado de A nao apaga a referencia de B`() {
        JobCancelBridge.resetForTest()
        val a = SinkFalso()
        val b = SinkFalso()
        JobCancelBridge.attach(a)
        JobCancelBridge.attach(b)
        // A morre DEPOIS de B já ter anexado: não pode desanexar B
        JobCancelBridge.detach(a)
        // (a referência de UI não é observável por API pública; provamos pelo
        // registro continuar único e vivo)
        assertEquals(1, WhisperJobService.cancelForwarderCount)
    }

    @Test
    fun `reset devolve o estado inicial`() {
        JobCancelBridge.resetForTest()
        JobCancelBridge.attach(SinkFalso())
        assertEquals(1, WhisperJobService.cancelForwarderCount)
        JobCancelBridge.resetForTest()
        assertEquals(0, WhisperJobService.cancelForwarderCount)
    }

    @Test
    fun `o forwarder e estavel entre anexos`() {
        JobCancelBridge.resetForTest()
        val f1 = JobCancelBridge.forwarder
        JobCancelBridge.attach(SinkFalso())
        val f2 = JobCancelBridge.forwarder
        assertSame("identidade estável (registro por token)", f1, f2)
    }

    // ------------------------------------------------ ADENDO 19 §1.2

    @Test
    fun `forwarder avisa a UI com a geracao LOGICA traduzida quando o cancel foi aceito`() {
        JobCancelBridge.resetForTest()
        val sink = SinkFalso()
        JobCancelBridge.attach(sink)
        // Sessão REAL no singleton do processo: jobFor(gen) existe, o
        // cancelamento é aceito mesmo sem binding resolvido (pedido
        // registrado para encaminhar ao owner quando ele nascer).
        val gen = WhisperSessions.shared.beginSession()
        ReservationJobIdentity.registrar(12345L, gen)
        val aceito = JobCancelBridge.forwarder(12345L)
        assertTrue("cancelamento registrado na sessão real", aceito)
        assertEquals(
            "UI recebe a SESSION generation (nunca o token do serviço) com ACK",
            listOf(gen to true),
            sink.recebidos,
        )
    }

    @Test
    fun `sem ACK o forwarder NAO alimenta a UI`() {
        JobCancelBridge.resetForTest()
        val sink = SinkFalso()
        JobCancelBridge.attach(sink)
        // Sink de sessão com RECUSA (cancelamento não aceito — ex.: binding
        // perdido/job desconhecido): Sem ACK, zero avisos à UI.
        ReservationJobIdentity.registrar(12345L, 77L)
        var chamadas = 0
        JobCancelBridge.sessionSink = { chamadas++; false }
        val aceito = JobCancelBridge.forwarder(12345L)
        assertEquals(false, aceito)
        assertEquals("recusa exercita o sink de sessão com vínculo válido", 1, chamadas)
        assertEquals("sem ACK, zero avisos à UI", 0, sink.recebidos.size)
    }

    @Test
    fun `token sem vinculo nunca e interpretado como geracao logica`() {
        JobCancelBridge.resetForTest()
        val sink = SinkFalso()
        JobCancelBridge.attach(sink)
        val cancelados = mutableListOf<Long>()
        // Uma sessão pode aceitar um número lógico igual ao token recebido.
        // Só o vínculo explícito autoriza atravessar os dois domínios.
        JobCancelBridge.sessionSink = { gen -> cancelados.add(gen); true }
        val aceito = JobCancelBridge.forwarder(12345L)
        assertEquals("token sem par não tem sessão autorizada", false, aceito)
        assertTrue("não encaminha um número lógico inventado", cancelados.isEmpty())
        assertTrue("token alheio não avisa a UI", sink.recebidos.isEmpty())
    }

    @Test
    fun `cancelamento atrasado apos esquecer o par nao reutiliza numero logico`() {
        JobCancelBridge.resetForTest()
        val sink = SinkFalso()
        JobCancelBridge.attach(sink)
        val cancelados = mutableListOf<Long>()
        JobCancelBridge.sessionSink = { gen -> cancelados.add(gen); true }
        ReservationJobIdentity.registrar(12345L, 12345L)
        assertTrue(JobCancelBridge.forwarder(12345L))
        ReservationJobIdentity.esquecer(12345L)
        assertEquals("terminal retirou a autorização de encaminhar", false,
            JobCancelBridge.forwarder(12345L))
        assertEquals("só encaminhou enquanto havia vínculo", listOf(12345L), cancelados)
        assertEquals("aviso atrasado não chega à UI", listOf(12345L to true), sink.recebidos)
    }
}
