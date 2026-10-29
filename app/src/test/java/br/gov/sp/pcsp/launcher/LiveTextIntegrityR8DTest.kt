package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R8/fase D — INTEGRIDADE: o texto retido/reparado TEM de ser promovido ao
 * texto final (defeito R8-D: w01 ficava invisível; f05a/f03 perdiam a cauda
 * promovida no EOF; d01-inject não exibia a frase re-decodificada).
 *
 * Cobre o contrato do committer (dreno terminal ordenado, resolução por reparo)
 * E a POLÍTICA de publicação até a UI ([LiveTextPublication]) usada pela
 * WhisperActivity no fim de janela, no passe de reparo e no terminal.
 */
class LiveTextIntegrityR8DTest {

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

    /**
     * Fluxo de publicação da Activity (recorte das 3 chamadas: fim de janela,
     * passe de reparo, terminal) — o MESMO caminho do produto, sem Android.
     */
    private class UiFlow {
        var visible: String? = null
        private fun step(source: String, display: String, terminalNormal: Boolean = false) {
            val t = LiveTextPublication.textToPublish(source, display, visible, terminalNormal)
            if (t != null) visible = t
        }

        fun commit(c: LiveTextCommitter) = step(LiveTextPublication.SOURCE_COMMIT, c.displayText())
        fun repair(c: LiveTextCommitter) = step(LiveTextPublication.SOURCE_REPAIR, c.displayText())
        fun terminal(c: LiveTextCommitter, cancelled: Boolean) {
            if (cancelled) {
                step(LiveTextPublication.SOURCE_CANCEL_DROP, c.displayText())
            } else {
                step(LiveTextPublication.SOURCE_TERMINAL, c.displayText(), terminalNormal = true)
            }
        }
    }

    @Test
    fun `R8D-I1 w01 palavra unica retida+reparada chega ao TEXTO FINAL visivel`() {
        // Geometria EXATA do run w01 do device: 1ª janela única [8640,34560),
        // contexto 8640; o segmento da palavra cruza a fronteira → retida.
        val c = LiveTextCommitter()
        val ui = UiFlow()
        val win = pcmSamples(34560)
        val segs = listOf(LiveSegmentTime("Confirmado.", 0L, 2160L))
        val info = c.onWindow(
            0, "Confirmado.", 8640L, 34560L, 8640,
            wordTimes = null, segments = segs, generation = 1L, windowPcm = win,
        )
        assertEquals("palavra retida (fronteira)", 1, info!!.heldWords)
        assertEquals(0, c.committedWordCount())
        ui.commit(c)
        // RED do defeito: com só o commit, NADA foi publicado (widget vazio).
        assertNull("pré-reparo nada visível (defeito R8-D reproduzido)", ui.visible)

        val req = c.takeRepairRequest()
        assertTrue("região retida pede reparo", req != null)
        assertTrue("reparo reproduz a palavra", c.completeRepair(req!!.regionId, "Confirmado."))
        ui.commit(c)
        ui.repair(c)
        assertEquals("Confirmado.", ui.visible)

        c.finalize()
        ui.terminal(c, cancelled = false)
        assertEquals("TEXTO FINAL visível", "Confirmado.", ui.visible)
        assertEquals(1, c.committedWordCount())
        assertEquals(0, c.heldWordCount())
    }

    @Test
    fun `R8D-I2 reparo insere frase nova e ela aparece no terminal normal`() {
        // Padrão d01-inject: janela divergente vira região retida; o reparo
        // re-decodifica e ACRESCENTA palavras que a hipótese não tinha.
        val c = LiveTextCommitter()
        val ui = UiFlow()
        c.onWindow(0, "alfa", 0L, 8_000L, 0)
        val win = pcmSamples(16_000)
        c.onWindow(1, "zulu xadrez", 8_000L, 16_000L, 8_000, windowPcm = win)
        ui.commit(c)
        val req = c.takeRepairRequest()!!
        assertTrue(c.completeRepair(req.regionId, "zulu xadrez charlie delta echo"))
        ui.repair(c)
        assertTrue("frase re-decodificada visível antes do terminal", ui.visible!!.contains("delta echo"))
        c.finalize()
        ui.terminal(c, cancelled = false)
        val out = c.committedText()
        assertTrue("nada some (out='$out')", out.contains("zulu") && out.contains("xadrez"))
        assertTrue("frase nova no TEXTO FINAL (out='$out')", ui.visible!!.contains("charlie delta echo"))
        assertEquals(0, c.heldWordCount())
    }

    @Test
    fun `R8D-I3 cauda retida sem reparo e promovida no terminal e tipada`() {
        // Padrão f03/f05a: região retida SEM áudio reparável; o terminal drena
        // por ocorrência (promove o que é novo) e CONTA o fechamento tipado.
        val c = LiveTextCommitter()
        val ui = UiFlow()
        c.onWindow(0, "alfa bravo", 0L, 8_000L, 0)
        c.onWindow(1, "charlie delta echo", 8_000L, 16_000L, 8_000) // sem PCM
        ui.commit(c)
        assertEquals(0, c.pendingRepairCount())
        val fin = c.finalize()
        assertEquals("fechamento tipado contado", 1, fin.unresolvedRegionsClosed)
        assertEquals("sem retido residual silencioso", 0, c.heldWordCount())
        ui.terminal(c, cancelled = false)
        val out = c.committedText()
        assertTrue("cauda promovida (out='$out')", out.contains("charlie") && out.contains("echo"))
        assertTrue("TEXTO FINAL com a cauda", ui.visible!!.contains("charlie delta echo"))
    }

    @Test
    fun `R8D-I4 cancel nao promove texto novo e derruba o overlay`() {
        val c = LiveTextCommitter()
        // 3 palavras: só a 1ª fica comprometida (cauda revisável = 2)
        c.onWindow(0, "alfa bravo charlie", 0L, 8_000L, 0)
        c.onWindow(1, "zulu xadrez", 8_000L, 16_000L, 8_000) // retida (sem PCM)
        assertEquals(1, c.committedWordCount())
        c.cancel()
        // cancel-drop publica o COMPROMETIDO (derruba overlay, mesmo vazio);
        // o retido/revisável do cancel NÃO vira texto final.
        assertEquals("alfa", LiveTextPublication.textToPublish(
            LiveTextPublication.SOURCE_CANCEL_DROP, c.displayText(), "alfa bravo zulu xadrez"))
        assertNull("terminal cancelado não publica", LiveTextPublication.textToPublish(
            LiveTextPublication.SOURCE_TERMINAL, c.displayText(), "alfa", terminalNormal = false))
        val out = c.committedText()
        assertTrue("comprometido preservado", out.contains("alfa"))
        assertTrue("retido do cancel NÃO promovido (out='$out')", !out.contains("zulu"))
        assertTrue("revisável do cancel NÃO promovido", !out.contains("bravo"))
    }

    @Test
    fun `R8D-I5 politica de publicacao minima`() {
        // vazio nunca vai à UI (exceto cancel-drop, que limpa)
        assertNull(LiveTextPublication.textToPublish(LiveTextPublication.SOURCE_COMMIT, "", null))
        assertNull(LiveTextPublication.textToPublish(LiveTextPublication.SOURCE_TERMINAL, "", null, terminalNormal = true))
        assertEquals("", LiveTextPublication.textToPublish(LiveTextPublication.SOURCE_CANCEL_DROP, "", "texto velho"))
        // commit republica o oficial; repair só quando mudou; terminal publica sempre
        assertEquals("abc", LiveTextPublication.textToPublish(LiveTextPublication.SOURCE_COMMIT, "abc", "abc"))
        assertNull(LiveTextPublication.textToPublish(LiveTextPublication.SOURCE_REPAIR, "abc", "abc"))
        assertEquals("abd", LiveTextPublication.textToPublish(LiveTextPublication.SOURCE_REPAIR, "abd", "abc"))
        assertEquals("abc", LiveTextPublication.textToPublish(LiveTextPublication.SOURCE_TERMINAL, "abc", "abc", terminalNormal = true))
    }
}
