package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * ADENDO 27 §2 — DESTINOS controlados: o recibo final é preso à identidade da
 * tentativa. Um post/terminal atrasado de A NÃO escreve no terminal de B e não
 * depende de campo global algum.
 */
class WhisperApresentacaoRegistroTest {

    private fun chave(job: Long, attempt: Long, input: String = "c.wav") =
        TranscriptionJobKey(
            runId = "run-teste", actId = 1, jobId = job, owner = job * 2,
            inputId = input, attemptId = attempt,
        )

    private fun dir(): File = Files.createTempDirectory("ad27-reg").toFile()

    @Test
    fun destinoEDestinatarioPresosAIdentidade() {
        val registro = WhisperApresentacaoRegistro()
        var agora = 0L
        val a = chave(1, 1, "a.wav")
        val b = chave(2, 1, "b.wav")
        val obsA = registro.registrar(a) { agora }
        val obsB = registro.registrar(b) { agora }
        val fa = File(dir(), "terminal-a.txt")
        val fb = File(dir(), "terminal-b.txt")
        registro.vincularDestino(a, fa)
        registro.vincularDestino(b, fb)

        assertEquals("destino de A é o de A", fa, registro.destino(a))
        assertEquals("destino de B é o de B", fb, registro.destino(b))
        assertNotEquals("destinos distintos por identidade", registro.destino(a), registro.destino(b))

        // A e B fecham com fatos próprios.
        for (obs in listOf(obsA, obsB)) {
            obs.inicioCompute()
            obs.emitido(1)
            obs.apresentar(1, obs.identidade) { }
            obs.persistencia()
            obs.finalizacao()
            obs.terminal(liberacaoConfirmada = true)
        }
        assertTrue(registro.reservarFinal(a))
        registro.destino(a)!!.writeText(obsA.linha("final"), Charsets.UTF_8)
        assertTrue(registro.reservarFinal(b))
        registro.destino(b)!!.writeText(obsB.linha("final"), Charsets.UTF_8)

        val textoA = fa.readText(Charsets.UTF_8)
        val textoB = fb.readText(Charsets.UTF_8)
        assertTrue("A escreve a linha de A", textoA.contains("job=1"))
        assertTrue("B escreve a linha de B", textoB.contains("job=2"))
        assertFalse("terminal de B não recebe a linha de A", textoB.contains("job=1"))
        assertFalse("terminal de A não recebe a linha de B", textoA.contains("job=2"))
    }

    @Test
    fun terminalAtrasadoDeANaoAlteraBDepoisDeBPublicado() {
        val registro = WhisperApresentacaoRegistro()
        var agora = 0L
        val a = chave(1, 1, "a.wav")
        val b = chave(2, 1, "b.wav")
        val obsA = registro.registrar(a) { agora }
        val obsB = registro.registrar(b) { agora }
        val fa = File(dir(), "a.txt")
        val fb = File(dir(), "b.txt")
        registro.vincularDestino(a, fa)
        registro.vincularDestino(b, fb)

        // B fecha e publica PRIMEIRO.
        obsB.inicioCompute(); obsB.emitido(1); obsB.apresentar(1, obsB.identidade) { }
        obsB.persistencia(); obsB.finalizacao(); obsB.terminal(liberacaoConfirmada = true)
        assertTrue(registro.reservarFinal(b))
        fb.writeText(obsB.linha("final"), Charsets.UTF_8)
        val antes = fb.readText(Charsets.UTF_8)

        // A chega ATRASADO (terminal depois) e publica no destino DELE.
        obsA.inicioCompute(); obsA.emitido(1); obsA.apresentar(1, obsA.identidade) { }
        obsA.persistencia(); obsA.finalizacaoAusente(); obsA.terminal(liberacaoConfirmada = false)
        assertTrue("A publica no próprio destino mesmo depois de B", registro.reservarFinal(a))
        fa.writeText(obsA.linha("final"), Charsets.UTF_8)

        assertEquals("terminal de B intocado pelo atraso de A", antes, fb.readText(Charsets.UTF_8))
        assertTrue(fa.readText(Charsets.UTF_8).contains("finalizacao_ausente=true"))
    }

    @Test
    fun publicacaoUnicaEPorTentativa() {
        val registro = WhisperApresentacaoRegistro()
        var agora = 0L
        val a = chave(1, 1)
        val b = chave(2, 1)
        val obsA = registro.registrar(a) { agora }
        val obsB = registro.registrar(b) { agora }
        // Sem terminal/finalização ainda: ninguém reserva.
        obsA.inicioCompute(); obsA.emitido(1); obsA.apresentar(1, obsA.identidade) { }
        assertFalse("sem fatos não reserva", registro.reservarFinal(a))

        obsA.persistencia(); obsA.finalizacao(); obsA.terminal(liberacaoConfirmada = true)
        assertTrue(registro.reservarFinal(a))
        assertFalse("publicação única por tentativa", registro.reservarFinal(a))

        obsB.inicioCompute(); obsB.emitido(1); obsB.apresentar(1, obsB.identidade) { }
        obsB.persistencia(); obsB.finalizacao(); obsB.terminal(liberacaoConfirmada = true)
        assertTrue("B tem a própria reserva", registro.reservarFinal(b))
    }

    @Test
    fun reciboNuncaEhDeOutraTentativa() {
        val registro = WhisperApresentacaoRegistro()
        var agora = 0L
        val a = chave(1, 1)
        val obsA = registro.registrar(a) { agora }
        obsA.inicioCompute(); obsA.emitido(1); obsA.apresentar(1, obsA.identidade) { }
        assertEquals(1L, registro.recibo(1, 1)?.ultimoApresentado)
        assertNull("tentativa desconhecida não devolve o recibo de A", registro.recibo(1, 2))
        assertNull(registro.recibo(2, 1))
    }
}
