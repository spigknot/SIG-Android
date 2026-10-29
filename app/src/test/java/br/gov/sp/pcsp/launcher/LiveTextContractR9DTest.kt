package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R9/fase D — CONTRATO DE RESULTADO PARCIAL das regiões retidas ([LiveTextCommitter.RegionOutcome]).
 *
 * Defeito (revisão R8, achado 4): uma região retida cujo reparo FALHA/é divergente
 * era drenada no terminal, `held_words` voltava a 0 e o terminal anunciava
 * "resolvida(s) NO TERMINAL" — indistinguível de resolvida. Aqui o contrato exige
 * estados TIPADOS e distintos, e que `held_words=0` NÃO valha como resolvido.
 */
class LiveTextContractR9DTest {

    private fun pcm(samples: Int): ByteArray {
        val out = ByteArray(samples * 2)
        for (i in 0 until samples) {
            val v = ((i * 37) % 3001) - 1500
            out[i * 2] = (v and 0xFF).toByte()
            out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    /** Janela que RETÉM a hipótese (segmento cruza a fronteira), geometria do w01. */
    private fun holdWindow(c: LiveTextCommitter, idx: Int, us: Long, ue: Long, text: String, t1Ms: Long) {
        val ctx = 8640
        val winLen = (ue - us) + ctx
        c.onWindow(
            idx, text, us, ue, ctx, null,
            listOf(LiveSegmentTime(text, 0L, t1Ms)), 1L, pcm(winLen.toInt())
        )
    }

    @Test
    fun `R9D-C1 reparo ACEITO conta como resolvida por reparo e integral`() {
        val c = LiveTextCommitter()
        holdWindow(c, 0, 8640, 34560, "Confirmado.", 2160)
        val req = c.takeRepairRequest()
        assertTrue("região retida esperada", req != null)
        assertTrue(c.completeRepair(req!!.regionId, "Confirmado.", null))
        c.finalize()
        val ro = c.regionOutcome()
        assertEquals(1, ro.resolvedByRepair)
        assertEquals(0, ro.promotedUnconfirmed)
        assertTrue(ro.integral)
    }

    @Test
    fun `R9D-C2 reparo PARCIAL promove SEM confirmacao e preserva a palavra omitida`() {
        val c = LiveTextCommitter()
        holdWindow(c, 0, 8640, 34560, "Confirmado agora", 2160)
        val req = c.takeRepairRequest()
        assertTrue(req != null)
        // reparo cobre só "Confirmado" -> não cobre a hipótese -> parcial
        assertTrue(c.completeRepair(req!!.regionId, "Confirmado", null))
        c.finalize()
        val ro = c.regionOutcome()
        assertEquals(1, ro.promotedUnconfirmed)
        assertEquals(1, ro.promotedRepairPartial)
        assertFalse(ro.integral)
        assertTrue("palavra omitida NÃO pode sumir", c.displayText().contains("agora"))
    }

    @Test
    fun `R9D-C3 reparo com erro NÃO declara resolvido`() {
        val c = LiveTextCommitter()
        holdWindow(c, 0, 8640, 34560, "Confirmado.", 2160)
        val req = c.takeRepairRequest()!!
        c.failRepair(req.regionId, "erro_rede")
        c.finalize()
        val ro = c.regionOutcome()
        assertEquals(0, ro.totalResolved)
        assertEquals(1, ro.promotedUnconfirmed)
        assertEquals(1, ro.promotedRepairFailed)
        assertFalse(ro.integral)
    }

    @Test
    fun `R9D-C4 reparo sem fala e cancelado NÃO declaram resolvido`() {
        for (reason in listOf("no_speech do reparo", "cancelado")) {
            val c = LiveTextCommitter()
            holdWindow(c, 0, 8640, 34560, "Confirmado.", 2160)
            val req = c.takeRepairRequest()!!
            c.failRepair(req.regionId, reason)
            c.finalize()
            assertEquals(1, c.regionOutcome().promotedRepairFailed)
            assertFalse(c.regionOutcome().integral)
        }
    }

    @Test
    fun `R9D-C5 ultima janela EOF sem reparo promove sem confirmacao`() {
        val c = LiveTextCommitter()
        holdWindow(c, 0, 8640, 34560, "Confirmado.", 2160)
        c.finalize()
        val ro = c.regionOutcome()
        assertEquals(1, ro.promotedUnconfirmed)
        assertEquals(1, ro.promotedNoRepair)
        assertFalse(ro.integral)
        assertTrue(c.displayText().contains("Confirmado"))
    }

    @Test
    fun `R9D-C6 hipotese igual em OUTRO intervalo NAO e dedup entre ocorrencias`() {
        val c = LiveTextCommitter()
        holdWindow(c, 0, 8640, 34560, "Confirmado agora", 2160)
        holdWindow(c, 1, 208640, 234560, "Confirmado agora", 2160)
        c.finalize()
        assertEquals("as duas ocorrências sobrevivem", 2, c.regionOutcome().promotedUnconfirmed)
    }

    @Test
    fun `R9D-C7 geracao divergente e rejeitada pelo fence`() {
        val c = LiveTextCommitter()
        holdWindow(c, 0, 8640, 34560, "Confirmado.", 2160) // gen 1
        val ctx = 0
        c.onWindow(1, "Confirmado.", 40000, 60000, ctx, null, null, 2L, pcm(20000))
        assertEquals(1, c.staleWindowCount())
    }
}
