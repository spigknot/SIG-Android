package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * R7/fase A — regressão da CORRIDA do LiveTextCommitter: a UI lia
 * displayText()/committedText() enquanto o worker mutava as listas em
 * onWindow()/finalize() — ConcurrentModificationException derrubava o app no
 * fim das passadas ao vivo (DropBox 2026-10-10 15:24/20:19/22:05/22:06; stack
 * completo em build/whisper-rodada7/.../fase-a/evidencia-race/crash-dropbox.txt:
 * main thread via Handler → displayText() → committedText()).
 *
 * Dois testes:
 *  - `martelo...`: reprodutor de CONCORRÊNCIA — medido contra o estado anterior
 *    (synchronized→run): falha de forma consistente; o formato inicial de
 *    1 leitor × janelas de 9 palavras NÃO reproduzia no JVM, por isso o martelo
 *    (2 leitores × janelas de 60 palavras em ritmo máximo);
 *  - `leitura publica espera o lock...`: invariante DETERMINÍSTICO (falha
 *    sempre no estado anterior; trava o estado corrigido).
 */
class LiveTextCommitterConcurrencyTest {

    /**
     * Martelo concorrente: 2 threads leem como a UI (displayText/committedText)
     * enquanto o worker commita janelas de ~60 palavras em ritmo máximo. Sem a
     * trava (estado anterior) o ArrayList compartilhado lança
     * ConcurrentModificationException na leitura; com a trava de R7 não lança.
     */
    @Test
    fun `martelo concorrente leitura UI vs mutacao do worker nao lanca`() {
        val c = LiveTextCommitter()
        val deadline = System.nanoTime() + 2_000_000_000L // bounded p/ o suite
        val error = AtomicReference<Throwable?>(null)
        val start = CountDownLatch(1)

        fun reader() = Thread {
            start.await()
            try {
                while (System.nanoTime() < deadline && error.get() == null) {
                    c.committedText()
                    c.displayText()
                    c.committedWordCount()
                    c.provisionalWordCount()
                    c.isFinalized
                }
            } catch (t: Throwable) {
                error.compareAndSet(null, t)
            }
        }

        val writer = Thread {
            start.await()
            var i = 0
            try {
                val text = (0 until 60).joinToString(" ") { "palavra$it" }
                while (System.nanoTime() < deadline && error.get() == null) {
                    c.onWindow(
                        windowIndex = i,
                        text = "$text $i",
                        uniqueStartSample = i * 64_000L,
                        uniqueEndSample = (i + 1) * 64_000L,
                        contextSamplesInChunk = 64_000,
                    )
                    if (i % 5 == 0) c.finalize()
                    if (i % 97 == 0) c.reset()
                    i++
                }
            } catch (t: Throwable) {
                error.compareAndSet(null, t)
            }
        }

        val threads = listOf(reader(), reader(), writer)
        threads.forEach { it.start() }
        start.countDown()
        threads.forEach { it.join(30_000) }
        val e = error.get()
        assertNull(
            "concorrência leitura×mutação não pode lançar: ${e?.javaClass?.name}: ${e?.message}",
            e,
        )
    }

    /**
     * Invariante DETERMINÍSTICO da correção (trava o "estado corrigido"): toda
     * leitura pública tem de esperar o MESMO lock que o worker usa durante a
     * mutação. Sem a trava (estado anterior) `committedText()` retorna mesmo
     * com o lock ocupado → este teste FALHA sempre; com a trava de R7, a
     * leitura espera e só retorna após a liberação.
     *
     * Usa reflexão porque o lock é interno: o contrato testável é "todos os
     * métodos públicos serializam no MESMO monitor"; renomear o lock exige
     * ajustar este teste de propósito.
     */
    @Test
    fun `leitura publica espera o lock ocupado - invariante da correcao R7`() {
        val c = LiveTextCommitter()
        val lockField = LiveTextCommitter::class.java.getDeclaredField("lock")
        lockField.isAccessible = true
        val lock = lockField.get(c)!!
        val readerReturned = CountDownLatch(1)
        val reader = Thread { c.committedText(); readerReturned.countDown() }

        synchronized(lock) {
            reader.start()
            // Enquanto o lock está ocupado, o leitor NÃO pode ter retornado.
            val deadline = System.nanoTime() + 1_500_000_000L
            while (readerReturned.count != 0L && System.nanoTime() < deadline) {
                Thread.sleep(10)
            }
            assertEquals(
                "committedText() não pode retornar enquanto o lock está ocupado (sem a trava, retorna)",
                1L,
                readerReturned.count,
            )
        }
        assertTrue(
            "committedText() deve retornar assim que o lock é liberado",
            readerReturned.await(5, TimeUnit.SECONDS),
        )
    }
}
