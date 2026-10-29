package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R6/D2 — suíte do COMMIT de texto (provisório×comprometido) sobre janelas com
 * contexto. O "decodificador" dos testes é EXATO por construção (devolve as
 * palavras realmente cobertas pela janela) — então qualquer perda/duplicação
 * de palavra é bug real, não espelho da implementação.
 */
class LiveTextCommitterTest {

    private val dur = 4000  // 0,25 s por palavra (amostras)

    /** Simula janelas [u0,u1) com contexto ws=u0-ctx; texto = palavras que
     * interceptam [ws,u1) (decodificador EXATO). Devolve janelas em ordem. */
    private fun simbatches(words: List<String>, hop: Int, ctx: Int, startOffset: Int = 0): List<Array<Any>> {
        val total = words.size * dur
        val out = ArrayList<Array<Any>>()
        var idx = 0
        var u0 = startOffset
        while (u0 < total) {
            val u1 = minOf(total, u0 + hop)
            val ws = maxOf(0, u0 - ctx)
            val covered = words.filterIndexed { i, _ ->
                val w0 = i * dur
                val w1 = (i + 1) * dur
                w1 > ws && w0 < u1
            }
            out.add(arrayOf(idx, covered.joinToString(" "), u0.toLong(), u1.toLong(), (u0 - ws)))
            u0 += hop
            idx++
        }
        return out
    }

    private fun feedAll(c: LiveTextCommitter, batches: List<Array<Any>>) {
        for (b in batches) {
            c.onWindow(b[0] as Int, b[1] as String, b[2] as Long, b[3] as Long, b[4] as Int)
        }
    }

    private fun wordsOf(n: Int, prefix: String = "p"): List<String> = (0 until n).map { "$prefix$it" }

    // ================================================================ 1

    @Test
    fun `fronteiras variadas - texto final identico sem perda nem duplicacao`() {
        val cases = listOf(
            Triple(4 * dur, 4 * dur, 0),      // janela 8 palavras, contexto 4
            Triple(4 * dur, 4 * dur, dur / 3), // fronteiras no meio de palavra
            Triple(2 * dur, 0, 0),            // modo fixo 2 s sem contexto
            Triple(3 * dur, 1 * dur, dur / 2), // contexto curto, offset artificial
            Triple(5 * dur, 2 * dur, 3 * dur / 4),
        )
        for ((hop, ctx, off) in cases) {
            val words = wordsOf(37)
            val c = LiveTextCommitter()
            val batches = simbatches(words, hop, ctx, off)
            feedAll(c, batches)
            c.finalize()
            assertEquals("hop=$hop ctx=$ctx off=$off: texto final", words.joinToString(" "), c.committedText())
            assertEquals(0, c.provisionalWordCount())
            // nunca duplica palavra adjacente idêntica
            val outWords = c.committedText().split(" ")
            for (i in 1 until outWords.size) {
                if (outWords[i] == outWords[i - 1]) {
                    assertEquals("repetição adjacente indevida em $i", "nunca", outWords[i])
                }
            }
        }
    }

    // ================================================================ 2

    @Test
    fun `palavras repetidas legitimas preservadas nas fronteiras`() {
        val words = listOf(
            "vamos", "repetir", "de", "novo", "de", "novo", "agora",
            "e", "de", "novo", "mais", "uma", "vez", "de", "novo",
        )
        for (hop in listOf(2 * dur, 3 * dur, 4 * dur, 5 * dur)) {
            for (ctx in listOf(0, dur, 2 * dur, 4 * dur)) {
                val c = LiveTextCommitter()
                feedAll(c, simbatches(words, hop, ctx))
                c.finalize()
                assertEquals("hop=$hop ctx=$ctx: repetição legítima preservada", words.joinToString(" "), c.committedText())
            }
        }
    }

    // ================================================================ 3

    @Test
    fun `pausa curta e longa - janelas sem fala nao quebram o commit`() {
        val words = wordsOf(20, "x")
        val batches = simbatches(words, 4 * dur, 4 * dur).toMutableList()
        // injeta janelas vazias (silêncio) entre as janelas de fala — o
        // pipeline real entrega NoSpeech e NÃO chama onWindow; aqui simulamos
        // o mesmo efeito: janela vazia é simplesmente pulada.
        val c = LiveTextCommitter()
        var i = 0
        for (b in batches) {
            // janela "sem fala" (silêncio): o pipeline real entrega NoSpeech e
            // NÃO chama onWindow — simulado aqui pulando a chamada. A ÚLTIMA
            // janela NUNCA é pulada (não haveria janela seguinte para cobrir a
            // região — perda estrutural do teste, não do mecanismo).
            if (i % 3 == 1 && i < batches.lastIndex) {
                // pula (silêncio)
            } else {
                c.onWindow(b[0] as Int, b[1] as String, b[2] as Long, b[3] as Long, b[4] as Int)
            }
            i++
        }
        c.finalize()
        assertEquals(words.joinToString(" "), c.committedText())
    }

    // ================================================================ 4

    @Test
    fun `stop durante contexto - cauda provisoria drenada no finalize`() {
        val words = listOf("alpha", "beta", "gamma", "delta", "epsilon")
        val c = LiveTextCommitter()
        // uma única janela: commit parcial, cauda provisória
        c.onWindow(0, "alpha beta gamma delta epsilon", 0, dur * 5L, 0)
        assertTrue(c.committedWordCount() < 5)
        assertTrue(c.provisionalWordCount() > 0)
        val before = c.committedText()
        val info = c.finalize()
        assertTrue(info.committedWordsAdded > 0)
        assertEquals(words.joinToString(" "), c.committedText())
        assertTrue(before != c.committedText())
    }

    // ================================================================ 5

    @Test
    fun `cancel durante contexto - provisorio NAO e publicado`() {
        val c = LiveTextCommitter()
        c.onWindow(0, "alfa beta gama delta", 0, dur * 4L, 0)
        val committedAtCancel = c.committedText()
        c.cancel()
        c.finalize()  // não pode publicar a cauda depois do cancel
        assertEquals("só o comprometido sobrevive ao cancel", committedAtCancel, c.committedText())
        assertEquals(0, c.provisionalWordCount())
    }

    // ================================================================ 6

    @Test
    fun `desalinhamento tolerante - palavra divergente nao apaga o resto`() {
        val c = LiveTextCommitter()
        // w0: 6 palavras, commit "a b c d", provisório "e f"
        c.onWindow(0, "a b c d e f", 0, 6 * dur.toLong(), 0)
        // w1: decodifica contexto com UMA palavra divergente ("c"->"z") e
        // segue com 3 palavras novas. Nada do trecho novo pode sumir.
        val info = c.onWindow(1, "b z d e f g h i", 4 * dur.toLong(), 10 * dur.toLong(), 4 * dur)
        c.finalize()
        val out = c.committedText().split(" ")
        for (w in listOf("g", "h", "i", "e", "f")) {
            assertTrue("palavra nova preservada: $w (out=$out)", out.contains(w))
        }
        assertTrue(info == null || info.committedWordsAdded >= 3)
    }

    // ================================================================ 7

    @Test
    fun `onset no inicio exato e fim de palavra na fronteira - uma vez cada`() {
        val words = wordsOf(12, "f")
        // janelas alinhadas de forma que uma palavra TERMINE exatamente na
        // fronteira e a próxima COMECE nela
        val c = LiveTextCommitter()
        feedAll(c, simbatches(words, 3 * dur, 2 * dur))
        c.finalize()
        assertEquals(words.joinToString(" "), c.committedText())
        val out = c.committedText().split(" ")
        assertEquals("nenhuma palavra duplicada", out.size, out.toSet().size)
    }

    // ================================================================ 8

    @Test
    fun `reabertura - committer resetado nao herda estado`() {
        val c = LiveTextCommitter()
        feedAll(c, simbatches(listOf("um", "dois", "tres"), 2 * dur, 0))
        c.finalize()
        assertEquals("um dois tres", c.committedText())
        c.reset()
        assertEquals(0, c.committedWordCount())
        feedAll(c, simbatches(listOf("outro", "job"), 2 * dur, 0))
        c.finalize()
        assertEquals("outro job", c.committedText())
    }
}
