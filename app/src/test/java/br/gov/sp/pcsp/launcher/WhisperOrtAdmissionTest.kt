package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Admissão single-flight e terminal único da rota M4. */
class WhisperOrtAdmissionTest {

    private val hash = "a".repeat(64)

    private fun admitir(a: WhisperOrtAdmission, id: String = "j1") =
        a.admit(jobId = id, attempt = 1L, engine = "ORT", provider = "CPU", inputSha256 = hash)

    @Test
    fun `segunda admissao durante execucao e recusada`() {
        val a = WhisperOrtAdmission()
        assertTrue(admitir(a) is WhisperOrtAdmission.Admitted.Ok)
        val segunda = admitir(a, "j2")
        assertTrue(segunda is WhisperOrtAdmission.Admitted.Refused)
        assertTrue((segunda as WhisperOrtAdmission.Admitted.Refused).reason.contains("j1"))
        assertTrue(a.isBusy())
    }

    @Test
    fun `terminal congela identidade e libera a proxima admissao`() {
        val a = WhisperOrtAdmission()
        val job = (admitir(a) as WhisperOrtAdmission.Admitted.Ok).job
        val t = a.complete(job, "SUCCESS", "ola mundo", 1234, partial = false)!!
        assertEquals(job.jobId, t.jobId)
        assertEquals(job.generation, t.generation)
        assertEquals(hash, t.inputSha256)
        assertEquals("SUCCESS", t.outcome)
        assertEquals(9, t.textBytes)
        assertFalse(t.partial)
        assertFalse(a.isBusy())
        // Terminal repetido não sobrescreve.
        assertNull(a.complete(job, "ERROR", "x", 1, partial = true))
        assertEquals(1, a.terminals().size)
        assertTrue(admitir(a, "j3") is WhisperOrtAdmission.Admitted.Ok)
    }

    @Test
    fun `callback de job antigo nao sobrescreve o corrente`() {
        val a = WhisperOrtAdmission()
        val velho = (admitir(a, "velho") as WhisperOrtAdmission.Admitted.Ok).job
        a.complete(velho, "SUCCESS", "v", 10, partial = false)
        val novo = (admitir(a, "novo") as WhisperOrtAdmission.Admitted.Ok).job
        assertNull(a.complete(velho, "ERROR", "tarde", 1, partial = false))
        assertTrue(a.isBusy())
        assertEquals(1, a.terminals().size)
        assertNotNull(a.complete(novo, "SUCCESS", "n", 20, partial = false))
    }

    @Test
    fun `cancelamento antes da admissao nao consome geracao`() {
        val a = WhisperOrtAdmission()
        val r = a.admit("c1", 1L, "ORT", "CPU", hash, isCancelled = true)
        assertTrue(r is WhisperOrtAdmission.Admitted.Refused)
        assertEquals(0L, a.generation())
        assertFalse(a.isBusy())
    }

    @Test
    fun `hash de entrada invalido e recusado`() {
        val a = WhisperOrtAdmission()
        val r = a.admit("x", 1L, "ORT", "CPU", "nao-e-hash")
        assertTrue(r is WhisperOrtAdmission.Admitted.Refused)
    }

    @Test
    fun `abandonar so pelo proprio job`() {
        val a = WhisperOrtAdmission()
        val job = (admitir(a) as WhisperOrtAdmission.Admitted.Ok).job
        assertFalse(a.abandon(job.copy(jobId = "outro")))
        assertTrue(a.isBusy())
        assertTrue(a.abandon(job))
        assertFalse(a.isBusy())
    }

    @Test
    fun `cancelar no meio produz terminal CANCELLED parcial e libera readmissao`() {
        val a = WhisperOrtAdmission()
        val job = (admitir(a) as WhisperOrtAdmission.Admitted.Ok).job
        assertTrue(a.requestCancel(job))
        assertTrue(a.isCancelRequested(job))
        val t = a.complete(job, "CANCELLED", "texto parcial", 500, partial = false)!!
        assertEquals("CANCELLED", t.outcome)
        // Cancelado é parcial por definição, mesmo com partial=false no chamador.
        assertTrue(t.partial)
        assertEquals(13, t.textBytes)
        assertFalse(a.isBusy())
        // Sucessor é admitido e o pedido antigo não contamina.
        val proximo = (admitir(a, "depois") as WhisperOrtAdmission.Admitted.Ok).job
        assertFalse(a.isCancelRequested(proximo))
        assertNotNull(a.complete(proximo, "SUCCESS", "ok", 100, partial = false))
    }

    @Test
    fun `pedido de cancelamento atrasado nao marca a tentativa nova`() {
        val a = WhisperOrtAdmission()
        val velho = (admitir(a, "velho") as WhisperOrtAdmission.Admitted.Ok).job
        a.complete(velho, "SUCCESS", "v", 10, partial = false)
        val novo = (admitir(a, "novo") as WhisperOrtAdmission.Admitted.Ok).job
        assertFalse(a.requestCancel(velho))
        assertFalse(a.isCancelRequested(novo))
        assertTrue(a.isBusy())
    }
}
