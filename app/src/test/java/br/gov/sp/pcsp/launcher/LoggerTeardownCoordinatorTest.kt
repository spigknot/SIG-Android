package br.gov.sp.pcsp.launcher

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R56/F01-F02 — prova HOST do dreno com **produtor retido por latch** (adendo14 §3).
 *
 * O teste antigo cristalizava a política errada: produtor eternamente falso com
 * `dormir` vazio e fechamento por "perda". Agora o produtor é retido por
 * `CountDownLatch` real, a coordenação roda em thread de teste, o marco é
 * avançado pelo passo injetável (sem sleeps longos) e a liberação acontece no
 * `finally`.
 */
class LoggerTeardownCoordinatorTest {

    @Test
    fun `produtor retido mantem consumidor apto e so fecha apos o dreno`() {
        val liberarProdutor = CountDownLatch(1)
        val marcoVisto = CountDownLatch(1)
        val produtorTerminou = AtomicBoolean(false)
        // Lista thread-safe: produtor de teste e coordenador rodam em threads distintas.
        val persistidos = java.util.concurrent.CopyOnWriteArrayList<String>()
        val fechamentos = AtomicInteger(0)
        val writerAberto = AtomicBoolean(true)

        val coord = LoggerTeardownCoordinator(
            produtores = listOf<Pair<String, () -> Boolean>>(
                "worker" to { produtorTerminou.get() }
            ),
            publicar = { evento ->
                persistidos += "evt:$evento"
                if (evento.contains("marco_atingido")) marcoVisto.countDown()
            },
            fecharWriter = { fechamentos.incrementAndGet(); writerAberto.set(false) },
            writerVivo = { false },
            dormir = { }
        )

        // Produtor de teste: AGUARDA o marco por latch (sem busy-wait órfão),
        // publica o terminal e só então libera o predicado de término.
        val produtor = Thread {
            try {
                if (!marcoVisto.await(10, TimeUnit.SECONDS)) return@Thread
                persistidos += "live_terminal"
                produtorTerminou.set(true)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                liberarProdutor.countDown()
            }
        }
        val coordThread = Thread {
            coord.executar(marcoDiagnosticoMs = 5, passoMs = 1, joinSlots = 1)
        }
        try {
            produtor.start()
            coordThread.start()
            coordThread.join(TimeUnit.SECONDS.toMillis(15))
            assertFalse("coordenação não pode travar", coordThread.isAlive)
            assertTrue("PENDING do marco precisa ser observado",
                persistidos.any { it.contains("marco_atingido") })
            assertEquals("terminal persistido exatamente uma vez",
                1, persistidos.count { it == "live_terminal" })
            assertEquals("fechamento único, só após o dreno", 1, fechamentos.get())
            assertFalse(writerAberto.get())
        } finally {
            // cleanup REAL: libera o predicado e junta as duas threads
            produtorTerminou.set(true)
            liberarProdutor.countDown()
            produtor.join(TimeUnit.SECONDS.toMillis(5))
            coordThread.join(TimeUnit.SECONDS.toMillis(5))
        }
    }

    @Test
    fun `writer retido no fechamento vira pendencia e depois termina`() {
        val persistidos = mutableListOf<String>()
        val writerVivo = AtomicBoolean(true)
        var fechamentos = 0

        val coord = LoggerTeardownCoordinator(
            produtores = listOf<Pair<String, () -> Boolean>>("worker" to { true }),
            publicar = { persistidos += it },
            fecharWriter = { fechamentos++ },
            writerVivo = { writerVivo.get() },
            dormir = { writerVivo.set(false) },   // solta após o primeiro passo
        )
        val r = coord.executar(marcoDiagnosticoMs = 0, passoMs = 1, joinSlots = 3)

        assertTrue("produtor já terminou", r.drenado)
        assertFalse("writer terminou dentro dos slots", r.writerPendente)
        assertEquals(1, fechamentos)
    }

    @Test
    fun `writer que nunca termina fica observavel como pendente`() {
        val persistidos = mutableListOf<String>()
        val coord = LoggerTeardownCoordinator(
            produtores = listOf<Pair<String, () -> Boolean>>("worker" to { true }),
            publicar = { persistidos += it },
            fecharWriter = { },
            writerVivo = { true },
            dormir = { },
        )
        val r = coord.executar(marcoDiagnosticoMs = 0, passoMs = 1, joinSlots = 2)

        assertTrue(r.writerPendente)
        assertTrue(persistidos.any { it.startsWith("teardown_writer_pendente") })
    }

    @Test
    fun `sem produtor em voo fecha direto`() {
        val persistidos = mutableListOf<String>()
        var fechamentos = 0
        val coord = LoggerTeardownCoordinator(
            produtores = listOf<Pair<String, () -> Boolean>>(
                "worker" to { true }, "longOps" to { true }, "inference" to { true }
            ),
            publicar = { persistidos += it },
            fecharWriter = { fechamentos++ },
            writerVivo = { false },
            dormir = { },
        )
        val r = coord.executar()

        assertTrue(r.drenado)
        assertFalse(r.marcoDiagnostico)
        assertFalse(r.writerPendente)
        assertTrue("nenhum evento de pendência", persistidos.isEmpty())
        assertEquals(1, fechamentos)
    }

    @Test
    fun `falha de consulta de produtor nao conta como termino`() {
        val persistidos = mutableListOf<String>()
        var chamadas = 0
        val coord = LoggerTeardownCoordinator(
            produtores = listOf<Pair<String, () -> Boolean>>(
                "worker" to {
                    chamadas++
                    if (chamadas == 1) throw IllegalStateException("consulta falhou")
                    true
                }
            ),
            publicar = { persistidos += it },
            fecharWriter = { },
            writerVivo = { false },
            dormir = { },
        )
        val r = coord.executar(marcoDiagnosticoMs = 0, passoMs = 1, joinSlots = 1)

        assertTrue("depois de responder, drena", r.drenado)
        assertTrue("primeira leitura virou pendência",
            persistidos.any { it.contains("teardown_produtor_vivo") })
    }
}
