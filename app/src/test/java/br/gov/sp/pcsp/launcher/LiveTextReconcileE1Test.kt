package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R7/fase E (E1) — REPRODUCERS da reconciliação de texto ao vivo
 * (`LiveTextCommitter`), escritos ANTES do patch de produção (E2).
 *
 * Cada caso documenta entrada→saída ERRADA do estado atual→saída ESPERADA com
 * os ÍNDICES DE AMOSTRAS das janelas (16 kHz, "dur" = 4000 amostras = 0,25 s
 * por palavra; único = [u0,u1), contexto = [ws,u0) com ws = u0 - ctx).
 *
 * A expectativa é uma linha do tempo INDEPENDENTE do cálculo posicional da
 * implementação: o que o áudio contém (e o que o modelo DECODIFICOU em alguma
 * janela) não pode sumir por estimativa; repetições legítimas não podem ser
 * fundidas nem duplicadas; sem alinhamento provado nada é cortado em silêncio.
 *
 * Defeitos atuais cobertos (KDoc vs código do LiveTextCommitter):
 *  (i)   `minOverlapWords` sem uso;
 *  (ii)  busca pega o MAIOR casamento (KDoc pede o menor) e ignora a cauda
 *        provisória como referência de sobreposição;
 *  (iii) sem casamento, corta por POSIÇÃO estimada ("posicional:k");
 *  (iv)  limpa a cauda provisória anterior presumida coberta — se a janela
 *        nova OMITE esse trecho, a palavra se perde em silêncio;
 *  (v)   a Activity não propaga tempos e o onSegment é vazio (coberto em E2).
 */
class LiveTextReconcileE1Test {

    private val dur = 4000 // amostras por palavra @16 kHz (0,25 s)

    private fun countOf(text: String, w: String): Int =
        text.split(" ").count { it == w }

    private fun assertContainsAll(out: String, vararg words: String) {
        for (w in words) {
            assertTrue("palavra '$w' ausente em: '$out'", out.split(" ").contains(w))
        }
    }

    // ================================================================ E1-1

    /**
     * E1-1 — palavra legítima só na cauda PROVISÓRIA antiga; a janela nova
     * OMITE apesar de cobrir o áudio [0,6dur)=[0,24000).
     *
     * Áudio/decodificação:
     *  w0: único [0,16000) ctx=0, texto "alfa beta gama delta" → commit parcial
     *      "alfa beta"; cauda provisória "gama delta".
     *  w1: único [8000,24000) ctx=8000 (ws=0), texto "alfa beta eco" — o modelo
     *      re-decodificou o contexto mas NÃO re-emitiu "gama delta" (áudio
     *      [8000,16000) coberto) e ouviu "eco" em [16000,24000).
     * ERRADO (atual): "alfa beta eco" — gama/delta somem (clean-phase presume
     * coberto e descarta; sem casamento, k=2 pela cauda comprometida).
     * ESPERADO: "alfa beta gama delta eco" (nada do áudio decodificado some;
     * a ausência na hipótese nova não pode virar corte silencioso).
     */
    @Test
    fun `E1-1 palavra legitima so na cauda provisoria nao pode sumir`() {
        val c = LiveTextCommitter()
        c.onWindow(0, "alfa beta gama delta", 0, 4L * dur, 0)
        c.onWindow(1, "alfa beta eco", 2L * dur, 6L * dur, 2 * dur)
        c.finalize()
        assertEquals("alfa beta gama delta eco", c.committedText())
    }

    // ================================================================ E1-2

    /**
     * E1-2 — contexto reconhecido com palavras DIFERENTES + único novo
     * correto: o início do único não pode ser cortado por contagem ESTIMADA.
     *
     * Áudio: a[0,4000) b[4000,8000) c[8000,12000) d[12000,16000) e[16000,20000)
     *  w0: único [0,20000) ctx=0, texto "a b c d e" → commit "a b c", cauda "d e".
     *  w1: único [12000,24000) ctx=12000 (ws=0), texto "x d e f" — "x" é a
     *      leitura divergente do contexto (a,b,c), "d","e" corretos e "f" novo.
     * ERRADO (atual): "a b c f" — sem casamento (x no lugar de a), corta
     * k=3 posicional e come "d","e" (palavras do ÚNICO, não do contexto).
     * ESPERADO: contém a,b,c,d,e,f; d/e/f exatamente uma vez (x pode permanecer
     * como hipótese retida — perda nunca).
     */
    @Test
    fun `E1-2 contexto divergente nao pode cortar o inicio do unico por estimativa`() {
        val c = LiveTextCommitter()
        c.onWindow(0, "a b c d e", 0, 5L * dur, 0)
        c.onWindow(1, "x d e f", 3L * dur, 6L * dur, 3 * dur)
        c.finalize()
        val out = c.committedText()
        assertContainsAll(out, "a", "b", "c", "d", "e", "f")
        assertEquals("d uma vez (out='$out')", 1, countOf(out, "d"))
        assertEquals("e uma vez (out='$out')", 1, countOf(out, "e"))
        assertEquals("f uma vez (out='$out')", 1, countOf(out, "f"))
    }

    // ================================================================ E1-3

    /**
     * E1-3 — densidade: pausa longa entre as palavras faz a estimativa
     * posicional errar feio; palavra não re-decodificada NÃO pode ser
     * descartada.
     *
     * Áudio: a[0,4000) b[4000,8000) + PAUSA [8000,24000) + c[24000,28000)
     *        d[28000,32000) e[32000,36000).
     *  w0: único [0,24000) ctx=0, texto "a b" (falas em [0,8000)).
     *  w1: único [24000,36000) ctx=24000 (ws=0), texto "c d e" — o modelo NÃO
     *      re-emitiu "a b" (pausa longa confunde o contexto).
     * ERRADO (atual): "a c d e" — b cai no clean-phase (endEst-step<=ws) e é
     * perdido; o "posicional" não recupera.
     * ESPERADO: "a b c d e".
     */
    @Test
    fun `E1-3 densidade - pausa longa nao pode descartar palavra nao re-decodificada`() {
        val c = LiveTextCommitter()
        c.onWindow(0, "a b", 0, 6L * dur, 0)
        c.onWindow(1, "c d e", 6L * dur, 9L * dur, 6 * dur)
        c.finalize()
        assertEquals("a b c d e", c.committedText())
    }

    // ================================================================ E1-4a

    /**
     * E1-4a — "não não" através da fronteira com fusão na janela nova.
     *
     * Áudio: não[0,4000) não[4000,8000) x[8000,12000).
     *  w0: único [0,8000) ctx=0, texto "não não" → tudo provisório (cauda).
     *  w1: único [4000,12000) ctx=4000 (ws=0), texto "não x" — o modelo fundiu
     *      as duas repetições.
     * ERRADO (atual): "não x" (o segundo "não", decodificado em w0, é
     * descartado no clean-phase e não reaparece).
     * ESPERADO: "não não x" — repetição legítima preservada (áudio tinha as
     * duas).
     */
    @Test
    fun `E1-4a repeticao legitima nao e fundida quando a janela nova omite`() {
        val c = LiveTextCommitter()
        c.onWindow(0, "não não", 0, 2L * dur, 0)
        c.onWindow(1, "não x", dur.toLong(), 3L * dur, dur)
        c.finalize()
        assertEquals("não não x", c.committedText())
    }

    // ================================================================ E1-4b

    /**
     * E1-4b — frase repetida de propósito ATRAVÉS da fronteira.
     *
     * Áudio: de[0,4000) novo[4000,8000) de[8000,12000) novo[12000,16000) fim[16000,20000).
     *  w0: único [0,16000) ctx=0, texto "de novo de novo" → commit "de novo";
     *      cauda "de novo".
     *  w1: único [12000,24000) ctx=12000 (ws=0), texto "de novo fim" (o modelo
     *      fundiu a segunda repetição do contexto).
     * ERRADO (atual): "de novo fim" — a segunda repetição decodificada em w0
     * se perde no clean-phase.
     * ESPERADO: "de novo de novo fim".
     */
    @Test
    fun `E1-4b frase repetida de proposito nao e perdida na fronteira`() {
        val c = LiveTextCommitter()
        c.onWindow(0, "de novo de novo", 0, 4L * dur, 0)
        c.onWindow(1, "de novo fim", 3L * dur, 6L * dur, 3 * dur)
        c.finalize()
        assertEquals("de novo de novo fim", c.committedText())
    }

    // ================================================================ E1-4c

    /**
     * E1-4c — dedup de fronteira: o contexto explica SÓ a sua cópia; nada de
     * duplicar a palavra re-decodificada ("r") no texto final.
     *
     * Áudio: p[0,4000) q[4000,8000) r[8000,12000) s[12000,16000) t[16000,20000).
     *  w0: único [0,16000) ctx=0, texto "p q r s" → commit "p q"; cauda "r s".
     *  w1: único [12000,24000) ctx=4000 (ws=8000), texto "g r s t" — "g" é a
     *      leitura divergente de p/q; "r","s" re-decodificados; "t" novo.
     * ERRADO (atual): "p q r g r s t" — "r" DUPLICADO (commit no clean-phase E
     * de novo no rest, k=0/posicional).
     * ESPERADO: p,q,r,s,t cada um uma vez (g pode permanecer como hipótese).
     */
    @Test
    fun `E1-4c sobreposicao nao pode duplicar palavra re-decodificada`() {
        val c = LiveTextCommitter()
        c.onWindow(0, "p q r s", 0, 4L * dur, 0)
        c.onWindow(1, "g r s t", 3L * dur, 6L * dur, dur)
        c.finalize()
        val out = c.committedText()
        assertContainsAll(out, "p", "q", "r", "s", "t")
        assertEquals("r uma vez (out='$out')", 1, countOf(out, "r"))
        assertEquals("s uma vez (out='$out')", 1, countOf(out, "s"))
    }

    // ================================================================ E1-5

    /**
     * E1-5 — trecho repetido na região CONTEXTUAL: remover SÓ a duplicação
     * comprovada, preservando o que foi decodificado e não re-emitido.
     *
     * Áudio: a[0,4000) de[4000,8000) novo[8000,12000) de[12000,16000) novo[16000,20000) mais[20000,24000).
     *  w0: único [0,20000) ctx=0, texto "a de novo de novo" → commit "a de novo";
     *      cauda "de novo".
     *  w1: único [16000,28000) ctx=16000 (ws=0), texto "a de novo mais" — o
     *      modelo re-decodificou o contexto com UMA repetição a menos.
     * ERRADO (atual): "a de novo mais" — a segunda "de novo" (áudio real,
     * decodificada em w0) se perde.
     * ESPERADO: "a de novo de novo mais" (só a duplicação comprovada do
     * contexto é removida; o resto do que foi decodificado permanece).
     */
    @Test
    fun `E1-5 repeticao no contexto - remover so a duplicacao comprovada`() {
        val c = LiveTextCommitter()
        c.onWindow(0, "a de novo de novo", 0, 5L * dur, 0)
        c.onWindow(1, "a de novo mais", 4L * dur, 7L * dur, 4 * dur)
        c.finalize()
        assertEquals("a de novo de novo mais", c.committedText())
    }

    // ================================================================ E1-6

    /**
     * E1-6 — janela VAZIA (no_speech) e FALHA entre duas janelas com texto não
     * podem causar perda da cauda na janela seguinte.
     *
     * Áudio: a[0,4000) b[4000,8000) c[8000,12000) d[12000,16000) e[24000,28000) f[28000,32000).
     *  w0 (idx 0): único [0,16000) ctx=0, texto "a b c d" → commit "a b";
     *      cauda "c d".
     *  w1 (idx 2): janela sem fala → onWindow("") devolve null (sem mutação).
     *  w2 (idx 4): único [24000,32000) ctx=16000 (ws=8000), texto "e f" — o
     *      modelo não re-emitiu "c d".
     * ERRADO (atual): "a b c e f" — "d" perdido.
     * ESPERADO: "a b c d e f".
     */
    @Test
    fun `E1-6 janela vazia e falha entre janelas com texto nao perdem a cauda`() {
        val c = LiveTextCommitter()
        c.onWindow(0, "a b c d", 0, 4L * dur, 0)
        val after0 = c.committedText() + "|" + c.provisionalText()
        val empty = c.onWindow(2, "   ", 4L * dur, 6L * dur, 0)
        assertNull("janela vazia não commita", empty)
        assertEquals("janela vazia não muta estado", after0, c.committedText() + "|" + c.provisionalText())
        // idx 3 = falha de decodificação: o pipeline real entrega Failed e NÃO
        // chama onWindow — simulado pela ausência da chamada.
        c.onWindow(4, "e f", 6L * dur, 8L * dur, 4 * dur)
        c.finalize()
        assertEquals("a b c d e f", c.committedText())
    }

    // ================================================================ E1-7

    /**
     * E1-7 — tempos AUSENTES/INVÁLIDOS/tamanho divergente e palavra cruzando a
     * fronteira. Tempo inválido NUNCA pode virar corte (o caminho "tempos:"
     * atual corta por tempo sem confirmação nem validação).
     *
     * (a) RED — wordTimes inválidos (t1 negativo):
     *  w0: único [0,8000) ctx=0, texto "a b" → tudo provisório.
     *  w1: único [4000,12000) ctx=4000 (ws=0), texto "b c",
     *      wordTimes=[("b",-5,-3),("c",-2,-1)] → t1*16 <= ctx para AMBOS →
     *      k=2: o texto inteiro é engolido.
     * ERRADO (atual): "a" (b e c somem).
     * ESPERADO: "a b c" (tempo inválido → ignora o caminho de tempos e
     * reconcilia por texto: "b" confirma a cauda, "c" é novo).
     *
     * (b) controle — wordTimes com tamanho divergente cai no caminho de texto
     *     (não pode crashar nem perder).
     * (c) controle — palavra CRUZANDO a fronteira (t0 < ctx < t1) não pode ser
     *     engolida (o takeWhile já para antes dela).
     */
    @Test
    fun `E1-7 tempos invalidos nao podem virar corte silencioso`() {
        // (a) RED
        val c = LiveTextCommitter()
        c.onWindow(0, "a b", 0, 2L * dur, 0)
        c.onWindow(
            1, "b c", dur.toLong(), 3L * dur, dur,
            wordTimes = listOf(
                LiveTextCommitter.WordTime("b", -5, -3),
                LiveTextCommitter.WordTime("c", -2, -1),
            ),
        )
        c.finalize()
        assertEquals("tempos inválidos: texto não pode ser cortado", "a b c", c.committedText())

        // (b) controle: tamanho divergente → caminho de texto
        val c2 = LiveTextCommitter()
        c2.onWindow(0, "a b", 0, 2L * dur, 0)
        c2.onWindow(
            1, "b c", dur.toLong(), 3L * dur, dur,
            wordTimes = listOf(LiveTextCommitter.WordTime("x", 0, 100)),
        )
        c2.finalize()
        assertEquals("tamanho divergente: reconciliação por texto", "a b c", c2.committedText())

        // (c) controle: palavra cruzando a fronteira fica (é início do único)
        val c3 = LiveTextCommitter()
        c3.onWindow(0, "a b", 0, 2L * dur, 0)
        c3.onWindow(
            1, "b c", dur.toLong(), 3L * dur, dur,
            wordTimes = listOf(
                LiveTextCommitter.WordTime("b", 240, 490), // t0<250< t1 (cruzando)
                LiveTextCommitter.WordTime("c", 500, 740),
            ),
        )
        c3.finalize()
        assertEquals("palavra cruzando a fronteira não some", "a b c", c3.committedText())
    }

    // ================================================================ E1-8

    /**
     * E1-8 — terminal: EOF/stop drena a cauda UMA vez; cancel NÃO promove
     * provisório; destroy/reabertura não herda estado nem cruza geração.
     *
     * Controles pinados para o E2 (comportamentos já corretos que o patch não
     * pode regredir; o patch adiciona contadores tipados e a cerca de geração).
     */
    @Test
    fun `E1-8 terminal - drena uma vez, cancel nao promove, reset nao herda`() {
        // (a) drain UMA vez + idempotência + janela tardia rejeitada
        val c = LiveTextCommitter()
        c.onWindow(0, "a b c d e", 0, 5L * dur, 0)
        val before = c.committedText()
        val first = c.finalize()
        assertTrue("finalize drena a cauda", first.committedWordsAdded > 0)
        val drained = c.committedText()
        assertTrue("texto cresce ao drenar", drained.length > before.length)
        val second = c.finalize()
        assertEquals("segundo finalize não drena de novo", 0, second.committedWordsAdded)
        assertEquals("texto estável após 2º finalize", drained, c.committedText())
        assertNull(
            "janela tardia após EOF não commita",
            c.onWindow(1, "tarde demais", 5L * dur, 6L * dur, dur),
        )
        assertEquals("janela tardia não muta texto", drained, c.committedText())
        assertTrue("comitter terminado", c.isFinalized)

        // (b) cancel NÃO promove provisório (nem com finalize posterior)
        val c2 = LiveTextCommitter()
        c2.onWindow(0, "alfa beta gama delta", 0, 4L * dur, 0)
        val committedAtCancel = c2.committedText()
        c2.cancel()
        assertEquals("cauda descartada no cancel", 0, c2.provisionalWordCount())
        c2.finalize()
        assertEquals("cancel não publica provisório", committedAtCancel, c2.committedText())
        assertNull("pós-cancel não aceita janela", c2.onWindow(1, "x y", 4L * dur, 6L * dur, dur))
        assertEquals("pós-cancel texto estável", committedAtCancel, c2.committedText())

        // (c) reset: nova geração não herda o texto nem a cauda da anterior
        c2.reset()
        assertEquals("reset zera comprometido", 0, c2.committedWordCount())
        assertEquals("reset zera provisório", 0, c2.provisionalWordCount())
        c2.onWindow(0, "novo job", 0, 2L * dur, 0)
        c2.finalize()
        assertEquals("novo job publica só o próprio texto", "novo job", c2.committedText())
    }
}
