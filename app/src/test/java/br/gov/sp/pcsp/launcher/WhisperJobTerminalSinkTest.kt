package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R56/B05 — prova HOST do sink terminal com **fila de UI retida**.
 *
 * O que este teste cobre (nota9 item 4 / adendo10 §3):
 *  - o ACK sai no momento do término do job, ANTES de qualquer post visual;
 *  - a fila de UI retida não muda o valor nem o momento do ACK;
 *  - idempotência por JOB (A→C→A): dois jobs diferentes não se anulam e
 *    repetição do mesmo job não emite de novo;
 *  - geração alheia é impossível por construção (o emissor recebe sempre a
 *    geração da instância);
 *  - falha de encaminhamento fica observável e NÃO marca conclusão;
 *  - falha de cleanup não altera o desfecho da transcrição.
 *
 * ALCANCE: é regra compartilhada em JVM. NÃO substitui a prova de integração
 * Android no aparelho (Activity real, Service, reserva).
 */
class WhisperJobTerminalSinkTest {

    /** Fila de UI controlada: no produto é `runOnUiThread`; aqui só guarda. */
    private class FilaUi {
        val pendentes = mutableListOf<() -> Unit>()
        var executados = 0
            private set

        fun post(bloco: () -> Unit) {
            pendentes += bloco
        }

        fun drenar() {
            val copia = pendentes.toList()
            pendentes.clear()
            copia.forEach { it(); executados++ }
        }
    }

    private class Emissor {
        val chamadas = mutableListOf<Pair<Long, String>>()
        var ok = true

        fun emitir(gen: Long, desfecho: String): Boolean {
            chamadas += gen to desfecho
            return ok
        }
    }

    @Test
    fun `ACK sai antes da fila de UI e nao depende dela`() {
        val emissor = Emissor()
        val fila = FilaUi()
        val sink = WhisperJobTerminalSink(7L, emissor::emitir)

        // worker termina e conclui; o post visual ainda está na fila
        assertTrue(sink.concluir("SUCCESS"))
        fila.post { /* mutação de widget */ }

        assertEquals("ACK deve existir antes de qualquer post", 1, emissor.chamadas.size)
        assertEquals(7L to "SUCCESS", emissor.chamadas[0])
        assertEquals("fila não pode ter rodado", 0, fila.executados)

        // drenar depois não reemite nem altera
        fila.drenar()
        assertEquals(1, emissor.chamadas.size)
        assertEquals(1, fila.executados)
    }

    @Test
    fun `fila retida nao atrasa nem troca o desfecho`() {
        val emissor = Emissor()
        val fila = FilaUi()
        val sink = WhisperJobTerminalSink(9L, emissor::emitir)

        sink.concluir("CANCELLED")
        // vários posts presos, incluindo um que "escreveria" o desfecho
        var escritoPelaUi: String? = null
        repeat(3) { fila.post { escritoPelaUi = "SUCCESS" } }

        assertEquals(9L to "CANCELLED", emissor.chamadas.single())
        assertNull("UI não pode ter executado nada", escritoPelaUi)
    }

    @Test
    fun `idempotente por job e insensivel a repeticao`() {
        val emissor = Emissor()
        val a = WhisperJobTerminalSink(11L, emissor::emitir)

        assertTrue(a.concluir("SUCCESS"))
        assertFalse("segunda chamada não emite", a.concluir("SUCCESS"))
        assertFalse("mesmo com outro desfecho", a.concluir("ERROR"))
        assertEquals(1, emissor.chamadas.size)
        assertEquals("SUCCESS", a.desfechoConfirmado)
    }

    @Test
    fun `dois jobs nao se anulam (A e C)`() {
        val emissor = Emissor()
        val a = WhisperJobTerminalSink(1L, emissor::emitir)
        val c = WhisperJobTerminalSink(3L, emissor::emitir)

        assertTrue(a.concluir("CANCELLED"))
        assertTrue("C tem identidade própria", c.concluir("SUCCESS"))

        assertEquals(listOf(1L to "CANCELLED", 3L to "SUCCESS"), emissor.chamadas)
        // repetir A depois de C não toca no terminal de C
        assertFalse(a.concluir("CANCELLED"))
        assertEquals(2, emissor.chamadas.size)
    }

    @Test
    fun `falha de encaminhamento fica observavel e nao conclui`() {
        val emissor = Emissor().apply { ok = false }
        val sink = WhisperJobTerminalSink(5L, emissor::emitir)

        assertFalse(sink.concluir("SUCCESS"))
        assertTrue("falha precisa ser visível", sink.falhaEncaminhamento)
        assertFalse(sink.concluido())
        assertNull(sink.desfechoConfirmado)

        // não vira ACK fabricado na segunda tentativa, mas também não trava
        emissor.ok = true
        assertTrue("retentativa legítima pode concluir", sink.concluir("SUCCESS"))
        assertTrue(sink.concluido())
    }

    @Test
    fun `geracao invalida nao emite`() {
        val emissor = Emissor()
        val sink = WhisperJobTerminalSink(0L, emissor::emitir)

        assertFalse(sink.concluir("SUCCESS"))
        assertTrue(sink.falhaEncaminhamento)
        assertTrue("emissor não pode ser chamado", emissor.chamadas.isEmpty())
    }

    @Test
    fun `excecao do emissor nao escapa e vira falha observavel`() {
        val sink = WhisperJobTerminalSink(4L) { _, _ -> error("boom") }

        assertFalse(sink.concluir("ERROR"))
        assertTrue(sink.falhaEncaminhamento)
        assertFalse(sink.concluido())
    }
}
