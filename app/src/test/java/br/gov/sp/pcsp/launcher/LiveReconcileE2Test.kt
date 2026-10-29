package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R7/fase E (E2) — testes do MECANISMO de reconciliação por INTERVALO DE ÁUDIO:
 * evidência de tempo (segmentos reais do JNI), hipóteses RETIDAS com dreno
 * terminal tipado, limites/`forcedClosures`, fence de geração e uso de
 * `minOverlapWords`. Contratos novos pinados depois do patch E2.
 */
class LiveReconcileE2Test {

    private val dur = 4000 // amostras por palavra @16 kHz

    private fun countOf(text: String, w: String): Int = text.split(" ").count { it == w }

    // ================================================================ segmentos

    /**
     * Segmentos REAIS (texto+ms da janela): a parte comprovadamente no contexto
     * não é duplicada; a nova entra. w0 único [0,8000) ctx=0 ("a b" — cauda);
     * w1 único [8000,16000) ctx=8000, texto "b c" com b terminando em 490 ms
     * (≤ 500 ms de contexto) e c em 740 ms (fora do contexto).
     */
    @Test
    fun `segmentos reais - contexto comprovado nao duplica e novo entra`() {
        val c = LiveTextCommitter()
        c.onWindow(0, "a b", 0, 2L * dur, 0)
        val info = c.onWindow(
            1, "b c", 2L * dur, 4L * dur, 2 * dur,
            segments = listOf(
                LiveSegmentTime("b", 200, 490),
                LiveSegmentTime("c", 510, 740),
            ),
        )
        assertNotNull(info)
        assertTrue("corte por evidência (alignment=${info!!.alignment})", info.alignment.startsWith("segmentos:1"))
        c.finalize()
        val out = c.committedText()
        assertEquals("b uma vez (out='$out')", 1, countOf(out, "b"))
        assertEquals("c uma vez (out='$out')", 1, countOf(out, "c"))
        assertEquals("a uma vez (out='$out')", 1, countOf(out, "a"))
    }

    /**
     * Segmento CRUZANDO a fronteira (t0 < contexto < t1): a palavra não é
     * cortada (é começo do único) e nada dela se perde.
     */
    @Test
    fun `segmento cruzando a fronteira nao e cortado`() {
        val c = LiveTextCommitter()
        c.onWindow(0, "x", 0, 2L * dur, 0)
        val info = c.onWindow(
            1, "b c", 2L * dur, 4L * dur, 2 * dur,
            segments = listOf(
                LiveSegmentTime("b", 100, 560), // cruza 500 ms
                LiveSegmentTime("c", 600, 740),
            ),
        )
        assertNotNull(info)
        assertEquals("corte para na 1ª palavra que cruza", "segmentos:0(evidencia)+retido:1", info!!.alignment)
        c.finalize()
        val out = c.committedText()
        for (w in listOf("x", "b", "c")) {
            assertEquals("$w uma vez (out='$out')", 1, countOf(out, w))
        }
    }

    /**
     * Segmentos INVÁLIDOS (texto concatenado divergente / fora da janela) NÃO
     * são usados: caem para a estimativa, com contador, e nada se perde.
     */
    @Test
    fun `segmentos invalidos caem para estimativa com contador`() {
        val c = LiveTextCommitter()
        c.onWindow(0, "a b", 0, 2L * dur, 0)
        // (a) concatenação divergente
        val info = c.onWindow(
            1, "b c", 2L * dur, 4L * dur, 2 * dur,
            segments = listOf(LiveSegmentTime("OUTRO", 200, 490), LiveSegmentTime("TEXTO", 510, 740)),
        )
        assertNotNull(info)
        assertTrue("não pode usar segmentos inválidos (${info!!.alignment})", !info.alignment.startsWith("segmentos:"))
        // (b) fim além da janela (tempo absurdo)
        c.onWindow(
            2, "d", 4L * dur, 6L * dur, 2 * dur,
            segments = listOf(LiveSegmentTime("d", 10, 999_999)),
        )
        assertEquals("duas fontes de tempo inválidas contadas", 2, c.invalidTimeWindowCount())
        c.finalize()
        val out = c.committedText()
        for (w in listOf("a", "b", "c", "d")) {
            assertTrue("$w preservado (out='$out')", out.split(" ").contains(w))
        }
    }

    // ================================================================ retidas

    /**
     * Janela SEM prova não publica nem descarta: retém a hipótese, preserva a
     * cauda; `finalize` drena UMA vez (com dedup) e o alinhamento é TIPADO.
     */
    @Test
    fun `retida - nao publica na hora e drena uma vez no terminal`() {
        val c = LiveTextCommitter()
        c.onWindow(0, "alfa beta gama", 0, 3L * dur, 0)
        // texto que não casa com NADA da referência (nem exato nem tolerante):
        // não pode cortar nem descartar a cauda; vira hipótese RETIDA.
        val info = c.onWindow(1, "que confuso texto", dur.toLong(), 5L * dur, dur)
        assertNotNull(info)
        assertEquals("uma hipótese retida", 1, info!!.heldHypotheses)
        assertEquals("3 palavras retidas", 3, info.heldWords)
        assertEquals("retidas não publicadas", "alfa", c.committedText())
        assertEquals("cauda preservada (revisável)", "beta gama", c.provisionalText())
        assertEquals(3, c.heldWordCount())
        assertTrue("alinhamento tipado (${info.alignment})", info.alignment.startsWith("retido:"))
        val fin = c.finalize()
        assertTrue("terminal tipado (${fin.alignment})", fin.alignment.startsWith("finalize+retidas:1"))
        val out = c.committedText()
        for (w in listOf("alfa", "beta", "gama", "que", "confuso", "texto")) {
            assertTrue("$w presente após drain (out='$out')", out.split(" ").contains(w))
        }
        assertEquals("2º finalize não muda", out, c.committedText())
        assertEquals(0, c.heldHypothesisCount())
    }

    /**
     * Limite de retenções (maxHeldHypotheses=2): a mais antiga fecha TIPADO
     * (publicada com dedup) e o contador registra; retidas ficam ≤ limite.
     */
    @Test
    fun `limite de retencoes fecha a mais antiga com tipagem`() {
        val c = LiveTextCommitter(2, 2, 4.0, 1, 2)
        c.onWindow(0, "a b c d", 0, 4L * dur, 0)
        // três janelas sem prova (textos que não casam com nada)
        c.onWindow(1, "m n", 4L * dur, 6L * dur, 2 * dur)
        c.onWindow(2, "o p", 6L * dur, 8L * dur, 4 * dur)
        val info3 = c.onWindow(3, "q r", 8L * dur, 10L * dur, 5 * dur)
        assertNotNull(info3)
        assertEquals(1, c.forcedClosureCount())
        assertTrue("retidas ≤ limite", c.heldHypothesisCount() <= 2)
        assertTrue("fechamento tipado registrado no info", info3!!.forcedClosures == 1)
        c.finalize()
        val out = c.committedText()
        assertTrue("texto do fechamento tipado publicado (out='$out')", out.contains("m") && out.contains("n"))
    }

    // ================================================================ fence/geração

    /** Janela de OUTRA geração é rejeitada (contador) e não cruza o texto. */
    @Test
    fun `fence de geracao rejeita janela de outro job`() {
        val c = LiveTextCommitter()
        c.onWindow(0, "a b c", 0, 3L * dur, 0, generation = 7L)
        val before = c.committedText()
        assertNull(
            "geração 8 não pode entrar no job 7",
            c.onWindow(1, "novo job", 3L * dur, 5L * dur, dur, generation = 8L),
        )
        assertEquals(1, c.staleWindowCount())
        assertEquals("texto não cruzou de geração", before, c.committedText())
        assertNotNull(c.onWindow(1, "d e", 3L * dur, 5L * dur, dur, generation = 7L))
        c.finalize()
        assertTrue(c.committedText().split(" ").containsAll(listOf("a", "b", "c", "d", "e")))
        // reset re-arma a cerca: geração 8 passa a valer
        c.reset()
        assertNotNull(c.onWindow(0, "z", 0, 2L * dur, 0, generation = 8L))
        c.finalize()
        assertEquals("z", c.committedText())
    }

    // ================================================================ minOverlapWords

    /**
     * `minOverlapWords` É USADO no casamento TOLERANTE: com o default (2) um
     * casamento de 3 palavras com 1 divergência é aceito ("tolerante:3"); com
     * minOverlapWords=4 o MESMO trecho é recusado (retenção) — evidência fraca
     * demais para cortar.
     */
    @Test
    fun `minOverlapWords limita o corte tolerante`() {
        // default: aceita tolerante de 3
        val c1 = LiveTextCommitter()
        c1.onWindow(0, "a b c d", 0, 4L * dur, 0)
        val i1 = c1.onWindow(1, "b c x y", 2L * dur, 4L * dur, 2 * dur)
        assertNotNull(i1)
        assertTrue("tolerante aceito com default (${i1!!.alignment})", i1.alignment.startsWith("tolerante:3"))
        // minOverlapWords=4: o mesmo casamento NÃO é aceito como corte
        val c2 = LiveTextCommitter(4, 2, 4.0, 1)
        c2.onWindow(0, "a b c d", 0, 4L * dur, 0)
        val i2 = c2.onWindow(1, "b c x y", 2L * dur, 4L * dur, 2 * dur)
        assertNotNull(i2)
        assertTrue("tolerante recusado com minOverlapWords=4 (${i2!!.alignment})", i2.alignment.startsWith("retido:"))
    }

    // ================================================================ integração com o pipeline

    /**
     * `LiveChunkResult.Success` propaga os segmentos (retrocompatível com o
     * construtor de 1 argumento) e o committer os consome via `onWindow`.
     */
    @Test
    fun `LiveChunkResult propaga segmentos para o onWindow`() {
        val r = LiveChunkResult.Success(
            "b c",
            listOf(LiveSegmentTime("b", 200, 490), LiveSegmentTime("c", 510, 740)),
        )
        assertEquals(2, r.segments.size)
        val legacy = LiveChunkResult.Success("texto")
        assertEquals(0, legacy.segments.size)
        val c = LiveTextCommitter()
        c.onWindow(0, "a b", 0, 2L * dur, 0)
        val info = c.onWindow(1, r.text, 2L * dur, 4L * dur, 2 * dur, segments = r.segments)
        assertTrue(info!!.alignment.startsWith("segmentos:1"))
        c.finalize()
        assertEquals("a b c", c.committedText())
    }
}
