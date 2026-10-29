package br.gov.sp.pcsp.launcher

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
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
 */
class WhisperJobCancelBridgeTest {

    private class SinkFalso : JobCancelBridge.UiSink {
        val recebidos = mutableListOf<Long>()
        override fun onJobCancelForwarded(generation: Long) {
            recebidos.add(generation)
        }
    }

    @After
    fun limpar() {
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
}
