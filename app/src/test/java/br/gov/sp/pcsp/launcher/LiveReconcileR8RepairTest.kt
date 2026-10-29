package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R8/fase B — testes da RE-DECODIFICAÇÃO RESTRITA (API do seam puro): áudio
 * preservado por região retida (com tetos por região/total), pedido ÚNICO por
 * região (sem loop de retry), resolução NO LUGAR quando o reparo reproduz a
 * hipótese, fechamento TIPADO quando falha/parcial — nunca declara sucesso
 * integral de texto sem cobrir a hipótese retida.
 */
class LiveReconcileR8RepairTest {

    /** PCM sintético determinístico (s16le). */
    private fun pcmSamples(samples: Int): ByteArray {
        val out = ByteArray(samples * 2)
        for (i in 0 until samples) {
            val v = ((i * 37) % 3001) - 1500
            out[i * 2] = (v and 0xFF).toByte()
            out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    /** w0 "alfa" provisória; w1 divergente ("zulu xadrez") vira região retida. */
    private fun heldAfterDivergentWindow(c: LiveTextCommitter, withPcm: Boolean): LiveTextCommitter.CommitInfo? {
        c.onWindow(0, "alfa", 0L, 8_000L, 0)
        val win = pcmSamples(16_000) // janela = contexto 8000 + único 8000
        return c.onWindow(1, "zulu xadrez", 8_000L, 16_000L, 8_000, windowPcm = if (withPcm) win else null)
    }

    @Test
    fun `R8B-R1 regiao retida preserva o audio e o reparo resolve no lugar`() {
        val c = LiveTextCommitter()
        val info = heldAfterDivergentWindow(c, withPcm = true)
        assertEquals("retida criada", 2, info!!.heldWords)
        assertTrue("áudio preservado", c.retainedAudioBytes() > 0)
        assertEquals(1, c.pendingRepairCount())
        val req = c.takeRepairRequest()
        assertNotNull(req)
        assertEquals("recorte = áudio PRÓPRIO do chunk (spans só estimados)", 16_000, req!!.pcm.size)
        assertEquals("uma tentativa por região", null, c.takeRepairRequest())
        assertTrue("reparo reproduziu a hipótese", c.completeRepair(req.regionId, "zulu xadrez"))
        assertEquals("nada pendente", 0, c.heldWordCount())
        assertEquals("áudio liberado após o reparo", 0, c.retainedAudioBytes())
        assertTrue("visível no lugar, antes do terminal", c.displayText().contains("zulu xadrez"))
        c.finalize()
        assertEquals("alfa zulu xadrez", c.committedText())
    }

    @Test
    fun `R8B-R2 sem audio preservado o reparo nao existe mas o texto nao some`() {
        val c = LiveTextCommitter()
        val info = heldAfterDivergentWindow(c, withPcm = false)
        assertEquals(2, info!!.heldWords)
        assertNull("sem PCM preservado não há pedido", c.takeRepairRequest())
        assertEquals(0, c.pendingRepairCount())
        c.finalize()
        val out = c.committedText()
        assertTrue("nada some (out='$out')", out.contains("zulu") && out.contains("xadrez"))
    }

    @Test
    fun `R8B-R3 falha do reparo fecha tipado sem segunda tentativa`() {
        val c = LiveTextCommitter()
        heldAfterDivergentWindow(c, withPcm = true)
        val req = c.takeRepairRequest()!!
        assertTrue(c.failRepair(req.regionId, "no_speech do reparo"))
        assertEquals(1, c.repairFailedCount())
        assertNull("uma tentativa por região (sem loop de retry)", c.takeRepairRequest())
        val fin = c.finalize()
        assertEquals("fechamento não resolvido tipado", 1, fin.unresolvedRegionsClosed)
        val out = c.committedText()
        assertTrue(out.contains("zulu") && out.contains("xadrez"))
    }

    @Test
    fun `R8B-R4 reparo parcial mantem pendencia e nao declara sucesso integral`() {
        val c = LiveTextCommitter()
        heldAfterDivergentWindow(c, withPcm = true)
        val req = c.takeRepairRequest()!!
        assertTrue(c.completeRepair(req.regionId, "zulu")) // omite "xadrez"
        assertTrue("hipótese não coberta continua pendente", c.heldWordCount() >= 1)
        c.finalize()
        val out = c.committedText()
        assertTrue("nada some (out='$out')", out.contains("zulu") && out.contains("xadrez"))
    }

    @Test
    fun `R8B-R5 teto por regiao e teto total limitam o audio preservado`() {
        // teto por região = 1000 amostras: a região (16000) não é preservada
        val c = LiveTextCommitter(2, 2, 4.0, 1, 4, 240, 1_000L)
        heldAfterDivergentWindow(c, withPcm = true)
        assertEquals("sem áudio retido", 0, c.retainedAudioBytes())
        assertNull(c.takeRepairRequest())
        // teto TOTAL de bytes = 100: nada preservado
        val c2 = LiveTextCommitter(2, 2, 4.0, 1, 4, 240, 160_000L, 100L)
        heldAfterDivergentWindow(c2, withPcm = true)
        assertEquals(0, c2.retainedAudioBytes())
        assertNull(c2.takeRepairRequest())
        // PCM de tamanho errado (não bate com a janela): não preserva, não quebra
        val c3 = LiveTextCommitter()
        c3.onWindow(0, "alfa", 0L, 8_000L, 0)
        c3.onWindow(1, "zulu xadrez", 8_000L, 16_000L, 8_000, windowPcm = ByteArray(100))
        assertNull(c3.takeRepairRequest())
    }
}
