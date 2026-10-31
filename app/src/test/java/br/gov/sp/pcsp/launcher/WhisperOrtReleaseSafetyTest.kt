package br.gov.sp.pcsp.launcher

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Regressões de posse física, limite do cache e cancelamento do download na integração. */
class WhisperOrtReleaseSafetyTest {
    private fun key(threads: Int) = WhisperOrtSessionCache.Key(
        "ORT", "CPU", "test", "turbo", "rev", "config", threads, "version",
    )

    @Test fun trocaDeThreadsFechaIdleERecusaExpulsarLeaseAtivo() {
        val backends = mutableListOf<WhisperOrtFakeBackend>()
        val cache = WhisperOrtSessionCache(
            factory = { _, _, _, _ -> WhisperOrtFakeBackend(emptyList()).also { backends += it } },
            open = {}, maxEntries = 1,
        )
        val file = File("unused")
        fun acquire(t: Int) = cache.acquire(key(t), "version", file, file, file)
        val first = acquire(1)!!
        assertTrue(cache.tryBeginCompute(first))
        assertNull(acquire(2))
        assertFalse(backends.single().closed)
        cache.endCompute(first)
        assertTrue(cache.release(first))
        for (threads in 2..8) {
            val lease = acquire(threads)!!
            assertEquals(1, cache.stats().live)
            assertTrue(cache.release(lease))
        }
        assertEquals(8, backends.size)
        assertEquals(7, backends.count { it.closed })
        val last = acquire(8)!!
        assertTrue(cache.tryBeginCompute(last))
        cache.endCompute(last)
        cache.release(last)
        cache.closeAll()
    }

    @Test fun cancelamentoDoDownloadImpedeAtivacaoENaoLiberaOwnerAntesDaLimpeza() {
        val first = WhisperOrtDownloadOwner.begin()!!
        try {
            first.cancel()
            assertNull(WhisperOrtDownloadOwner.begin())
            var published = false
            assertNull(first.publish { published = true })
            assertFalse(published)
        } finally { WhisperOrtDownloadOwner.finish(first) }
        val second = WhisperOrtDownloadOwner.begin()!!
        try {
            assertNotEquals(first.id, second.id)
            WhisperOrtDownloadOwner.finish(first)
            assertNull(WhisperOrtDownloadOwner.begin())
            assertEquals("committed", second.publish { "committed" })
        } finally { WhisperOrtDownloadOwner.finish(second) }
    }

    @Test fun interrupcaoDaEsperaNaoLiberaAdmissaoAntesDoCallbackFisico() {
        val admission = WhisperOrtAdmission()
        val job = (admission.admit("A", 1, "ORT", "CPU", "a".repeat(64)) as WhisperOrtAdmission.Admitted.Ok).job
        val waiting = CountDownLatch(1)
        val callback = CountDownLatch(1)
        val done = AtomicBoolean(false)
        val active = WhisperOrtMediaConverter.Resultado.SessaoAtiva("timeout", 7, File("unused")) {
            waiting.countDown()
            callback.await()
            WhisperOrtMediaConverter.Final(false, true, "cancelled")
        }
        val worker = Thread {
            WhisperOrtMediaConverter.aguardarTermino(active)
            admission.complete(job, "CANCELLED", "", 0, false)
            done.set(true)
        }.apply { isDaemon = true; start() }
        try {
            assertTrue(waiting.await(2, TimeUnit.SECONDS))
            worker.interrupt()
            assertTrue(admission.requestCancel(job))
            assertTrue(admission.isBusy())
            assertFalse(done.get())
            assertTrue(admission.admit("B", 1, "ORT", "CPU", "b".repeat(64)) is WhisperOrtAdmission.Admitted.Refused)
        } finally {
            callback.countDown()
            worker.join(2000)
        }
        assertTrue(done.get())
        assertFalse(admission.isBusy())
        assertEquals("CANCELLED", admission.terminalOf(job)?.outcome)
    }
}
