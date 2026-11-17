package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * R102 §T01 — prova de que o timer de cancelamento não corre na thread da
 * inferência, e de que o recibo grava os três instantes sem expor texto no resumo.
 */
class WhisperNpuAceiteReceiptTest {

    @Test
    fun `timer dispara no prazo mesmo com a inferencia bloqueada`() {
        // Relógio monotônico de teste: começa em 1000 ms e avança com o tempo real.
        val inicioRelogio = System.nanoTime()
        val relogio: () -> Long = { 1_000L + (System.nanoTime() - inicioRelogio) / 1_000_000L }
        val executor = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "r102-timer-teste").apply { isDaemon = true }
        }
        val cancelador = WhisperNpuAceiteReceipt.Cancelador(relogio, executor)
        val disparo = CountDownLatch(1)
        val programado = AtomicLong(0L)
        val real = AtomicLong(0L)
        val threadDisparo = AtomicReference("")

        val inferenciaBloqueada = CountDownLatch(1)
        val ocupada = Thread({
            inferenciaBloqueada.await(10, TimeUnit.SECONDS)
        }, "inferencia-bloqueada").apply { isDaemon = true }
        ocupada.start()

        try {
            cancelador.programar(1_000L, 3_000L) { p, r ->
                programado.set(p)
                real.set(r)
                threadDisparo.set(Thread.currentThread().name)
                disparo.countDown()
            }
            assertTrue("o timer TEM de disparar com a inferência ocupada", disparo.await(8, TimeUnit.SECONDS))
            assertEquals(4_000L, programado.get())
            assertTrue(
                "o disparo real deve ser pontual (programado=4000, real=${real.get()})",
                real.get() in 4_000L..5_000L,
            )
            assertEquals("r102-timer-teste", threadDisparo.get())
            assertFalse("a thread do timer não é a da inferência", threadDisparo.get() == ocupada.name)
        } finally {
            inferenciaBloqueada.countDown()
            cancelador.fechar()
        }
    }

    @Test
    fun `recibo separa texto do resumo e calcula os tres instantes`() {
        val timers = WhisperNpuAceiteReceipt.Timers(
            programadoMs = 3_000L, realMs = 3_120L, terminalMs = 3_940L,
        )
        val dados = WhisperNpuAceiteReceipt.Dados(
            celula = "C7", rotaPedida = "htp", rotaEfetiva = "htp",
            versaoNpu = "2", versaoQairt = "1", motivoFallback = null,
            openMs = 210L, totalMs = 3_950L, soc = "SM8550",
            timers = timers, texto = "texto sensivel so do EV",
        )
        assertEquals(120L, timers.atrasoMs)
        assertEquals(820L, timers.posSinalMs)
        assertTrue("resumo NÃO pode conter texto", !dados.resumo().contains("sensivel"))
        val json = dados.json()
        assertTrue("o JSON do EV contém o texto", json.contains("texto sensivel so do EV"))
        assertTrue(json.contains("\"atraso_ms\":120"))
        assertNotNull(json)
    }
}
