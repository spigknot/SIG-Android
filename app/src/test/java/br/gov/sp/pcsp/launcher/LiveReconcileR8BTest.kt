package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

/**
 * R8/fase B — REPRODUCER do especialista + casos ADVERSARIAIS da reconciliação
 * live realmente revisável (B1). Escritos ANTES do patch de produção de B2.
 *
 * Contrato exercitado (prompt R8, seção B):
 *  - a hipótese provisória antiga é SUBSTITUÍDA/RECONCILIADA pela correção da
 *    janela nova quando as duas leem o MESMO áudio e divergem (caso real
 *    "porta azul"→"porta vermelha"); o texto já COMMITTED não pode ser alterado
 *    por callback tardio (fronteira de estabilidade);
 *  - dedup por TEXTO+ocorrência (áudio), nunca por vocabulário/contagem global
 *    da referência; sem tempos, estimativa uniforme não é prova para descartar
 *    palavra nenhuma;
 *  - retidas resolvem por região quando uma janela MAIS NOVA cobre o áudio, sem
 *    mover o texto para o fim; terminal fecha o que não resolveu, tipado;
 *  - ordem/tempos inválidos de segmentos e wordTimes com palavras incompatíveis
 *    NÃO podem virar corte silencioso.
 *
 * As expectativas usam os índices de amostra das janelas (16 kHz, "dur" = 4000
 * amostras), como as suítes E1/E2.
 */
class LiveReconcileR8BTest {

    private val dur = 4000 // amostras por palavra @16 kHz

    private fun countOf(text: String, w: String): Int = text.split(" ").count { it == w }

    // ================================================================ R8B-1

    /**
     * Reproducer do especialista (auditoria-rodada7): W0 [0,4 s] "porta azul"
     * (cauda provisória); W1 [0,8 s] contexto [0,4 s] "porta vermelha entrada
     * nova" com segmentos 0–4/4–8. A saída do estado atual é "porta azul
     * entrada nova" — a atualização nova divergente é engolida pela promoção
     * por kTime. ESPERADO: "porta vermelha entrada nova".
     */
    @Test
    fun `R8B-1 porta vermelha - hipotese provisoria antiga e substituida pela correcao`() {
        val c = LiveTextCommitter()
        c.onWindow(
            0, "porta azul", 0L, 64_000L, 0,
            segments = listOf(LiveSegmentTime("porta azul", 0L, 4000L)),
        )
        assertEquals("porta azul", c.displayText())
        c.onWindow(
            1, "porta vermelha entrada nova", 64_000L, 128_000L, 64_000,
            segments = listOf(
                LiveSegmentTime("porta vermelha", 0L, 4000L),
                LiveSegmentTime("entrada nova", 4000L, 8000L),
            ),
        )
        assertEquals("porta vermelha entrada nova", c.displayText())
        c.finalize()
        assertEquals("porta vermelha entrada nova", c.committedText())
    }

    // ================================================================ R8B-2

    /**
     * Fronteira de estabilidade: com tailGuardWords=0 as duas palavras de W0 já
     * estão COMMITTED. A re-leitura divergente de W1 NÃO pode alterar o
     * committed ("porta azul" permanece); a hipótese nova divergente não pode
     * sumir em silêncio — fica pendente/tipada até o terminal e é publicada
     * como hipótese (nada some), sem tocar o trecho estável.
     */
    @Test
    fun `R8B-2 fronteira de estabilidade - committed nao muda com callback tardio`() {
        val c = LiveTextCommitter(2, 0, 4.0, 1)
        c.onWindow(
            0, "porta azul", 0L, 64_000L, 0,
            segments = listOf(LiveSegmentTime("porta azul", 0L, 4000L)),
        )
        assertEquals("porta azul", c.committedText())
        c.onWindow(
            1, "porta vermelha entrada nova", 64_000L, 128_000L, 64_000,
            segments = listOf(
                LiveSegmentTime("porta vermelha", 0L, 4000L),
                LiveSegmentTime("entrada nova", 4000L, 8000L),
            ),
        )
        assertEquals("porta azul entrada nova", c.committedText())
        c.finalize()
        val out = c.committedText()
        assertEquals("azul uma vez (out='$out')", 1, countOf(out, "azul"))
        assertEquals("vermelha não pode sumir (out='$out')", 1, countOf(out, "vermelha"))
        assertEquals(
            "committed intacto sob a hipótese publicada (out='$out')",
            "porta azul entrada nova",
            out.split(" ").filter { it != "vermelha" }.joinToString(" "),
        )
    }

    // ================================================================ R8B-3

    /**
     * Hipótese antiga OMITE palavra ("preto") e a janela seguinte a recupera: a
     * palavra recuperada entra na posição do áudio (entre "gato" e "subiu") e o
     * texto já decodificado de W0 não é duplicado nem perdido.
     */
    @Test
    fun `R8B-3 palavra omitida pela hipotese antiga e recuperada na janela seguinte`() {
        val c = LiveTextCommitter()
        c.onWindow(0, "gato subiu", 0L, 12_000L, 0)
        c.onWindow(
            1, "gato preto subiu teto", 12_000L, 24_000L, 12_000,
            segments = listOf(
                LiveSegmentTime("gato", 0L, 250L),
                LiveSegmentTime("preto", 250L, 500L),
                LiveSegmentTime("subiu", 500L, 750L),
                LiveSegmentTime("teto", 750L, 1000L),
            ),
        )
        assertEquals("gato preto subiu teto", c.displayText())
        c.finalize()
        assertEquals("gato preto subiu teto", c.committedText())
    }

    // ================================================================ R8B-4

    /**
     * Hipótese nova OMITE palavra antiga legítima: nada é cortado por
     * omissão/estimativa; o texto antigo é preservado e o novo entra depois.
     * (E1-1 com segmentos reais: o áudio [8000,16000) não é re-emitido.)
     */
    @Test
    fun `R8B-4 hipotese nova omite palavra antiga legitima - nada some`() {
        val c = LiveTextCommitter()
        c.onWindow(0, "alfa beta gama delta", 0L, 16_000L, 0)
        c.onWindow(
            1, "alfa beta eco", 8_000L, 24_000L, 8_000,
            segments = listOf(
                LiveSegmentTime("alfa", 0L, 250L),
                LiveSegmentTime("beta", 250L, 500L),
                LiveSegmentTime("eco", 1000L, 1250L),
            ),
        )
        c.finalize()
        assertEquals("alfa beta gama delta eco", c.committedText())
    }

    // ================================================================ R8B-5

    /**
     * Segmento CRUZANDO a fronteira (t0 < contexto < t1): não é cortado; a
     * palavra retida é drenada no terminal uma vez, sem duplicar nada.
     */
    @Test
    fun `R8B-5 segmento cruzando a fronteira nao e cortado`() {
        val c = LiveTextCommitter()
        c.onWindow(0, "x", 0L, 8_000L, 0)
        val info = c.onWindow(
            1, "b c", 8_000L, 16_000L, 8_000,
            segments = listOf(
                LiveSegmentTime("b", 100L, 560L), // cruza 500 ms
                LiveSegmentTime("c", 600L, 740L),
            ),
        )
        assertTrue(
            "corte para na 1ª palavra que cruza (${info!!.alignment})",
            info.alignment.startsWith("segmentos:0(evidencia)"),
        )
        c.finalize()
        val out = c.committedText()
        for (w in listOf("x", "b", "c")) {
            assertEquals("$w uma vez (out='$out')", 1, countOf(out, w))
        }
    }

    // ================================================================ R8B-6

    /**
     * DOIS segmentos com ORDEM inválida (tempos decrescentes na lista): a fonte
     * de tempo inteira cai para a estimativa (contador) — o estado atual usa os
     * tempos e duplica "b" ("a b b c").
     */
    @Test
    fun `R8B-6 segmentos fora de ordem caem para estimativa sem perder palavra`() {
        val c = LiveTextCommitter()
        c.onWindow(0, "a b", 0L, 8_000L, 0)
        c.onWindow(
            1, "b c", 4_000L, 12_000L, 4_000,
            segments = listOf(
                LiveSegmentTime("b", 510L, 740L),
                LiveSegmentTime("c", 200L, 490L), // ordem inválida
            ),
        )
        assertEquals("uma fonte de tempo inválida contada", 1, c.invalidTimeWindowCount())
        c.finalize()
        assertEquals("a b c", c.committedText())
        assertEquals("b uma vez", 1, countOf(c.committedText(), "b"))
    }

    // ================================================================ R8B-7

    /**
     * wordTimes com CONTAGEM ok e tempos ok, mas PALAVRAS incompatíveis: a
     * fonte é rejeitada (contador) — o estado atual a usa e engole "c"
     * (saída "a b").
     */
    @Test
    fun `R8B-7 wordTimes com palavras incompativeis caem para texto sem perder palavra`() {
        val c = LiveTextCommitter()
        c.onWindow(0, "a b", 0L, 8_000L, 0)
        c.onWindow(
            1, "b c", 4_000L, 12_000L, 4_000,
            wordTimes = listOf(
                LiveTextCommitter.WordTime("OUTRO", 0L, 100L),
                LiveTextCommitter.WordTime("X", 120L, 240L),
            ),
        )
        assertEquals("fonte de tempo incompatível contada", 1, c.invalidTimeWindowCount())
        c.finalize()
        assertEquals("a b c", c.committedText())
    }

    // ================================================================ R8B-8

    /**
     * Mesma palavra no CONTEXTO e outra ocorrência legítima no ÚNICO: a do
     * contexto é deduplicada (mesma ocorrência de áudio), a do único entra —
     * "sim sim", nunca "sim" nem "sim sim sim".
     */
    @Test
    fun `R8B-8 mesma palavra no contexto vs ocorrencia legitima no unico`() {
        val c = LiveTextCommitter()
        c.onWindow(0, "sim", 0L, 4_000L, 0)
        c.onWindow(
            1, "sim sim", 4_000L, 12_000L, 4_000,
            segments = listOf(
                LiveSegmentTime("sim", 0L, 250L),   // re-decode do contexto
                LiveSegmentTime("sim", 250L, 500L), // ocorrência nova
            ),
        )
        c.finalize()
        val out = c.committedText()
        assertEquals("sim duas vezes (out='$out')", 2, countOf(out, "sim"))
    }

    // ================================================================ R8B-9

    /**
     * Números/repetições em ocorrências diferentes: W0 [0,8 s) "2 2"; W1
     * re-decodifica [0,4 s) "2", corrige [4,8 s) para "3" (divergência com
     * sobreposição de tempo provada) e recupera "2" novo em [8,12 s).
     * ESPERADO "2 3 2" — o estado atual devolve "2 2 2" (a correção nova é
     * engolida e o 3 some).
     */
    @Test
    fun `R8B-9 numeros em ocorrencias diferentes - correcao e recuperacao`() {
        val c = LiveTextCommitter()
        c.onWindow(0, "2 2", 0L, 8_000L, 0)
        c.onWindow(
            1, "2 3 2", 8_000L, 16_000L, 8_000,
            segments = listOf(
                LiveSegmentTime("2", 0L, 250L),
                LiveSegmentTime("3", 250L, 500L),
                LiveSegmentTime("2", 500L, 750L),
            ),
        )
        c.finalize()
        assertEquals("2 3 2", c.committedText())
    }

    // ================================================================ R8B-10

    /**
     * Retida antiga resolve quando uma janela MAIS NOVA cobre a região — e o
     * texto resolvido fica na POSIÇÃO da região, não no fim: "alfa beta gama
     * delta" (o estado atual duplica "beta gama").
     */
    @Test
    fun `R8B-10 retida antiga resolve apos janela mais nova sem mover para o fim`() {
        val c = LiveTextCommitter()
        c.onWindow(0, "alfa", 0L, 8_000L, 0)
        c.onWindow(1, "beta gama", 8_000L, 16_000L, 8_000)
        assertEquals("alfa", c.committedText())
        assertEquals("retida de 2 palavras", 2, c.heldWordCount())
        assertEquals("retida não é publicada na hora", "alfa", c.displayText())
        c.onWindow(
            2, "beta gama delta", 16_000L, 24_000L, 16_000,
            segments = listOf(
                LiveSegmentTime("beta", 0L, 300L),
                LiveSegmentTime("gama", 300L, 600L),
                LiveSegmentTime("delta", 1050L, 1300L),
            ),
        )
        assertEquals("alfa beta gama", c.committedText())
        assertEquals("retida resolvida (nada pendente)", 0, c.heldWordCount())
        assertEquals("alfa beta gama delta", c.displayText())
        c.finalize()
        val out = c.committedText()
        assertEquals("alfa beta gama delta", out)
        assertEquals("beta uma vez (out='$out')", 1, countOf(out, "beta"))
    }

    // ================================================================ R8B-11

    /**
     * Dedup ≠ "palavra no vocabulário da referência": "gato" re-decodificado em
     * OUTRA ocorrência do áudio ([4000,7200)) não pode ser descartado só porque
     * "gato" existe na referência — alinhamento por ocorrência/tempo manda.
     * (O estado atual descarta a segunda ocorrência.)
     */
    @Test
    fun `R8B-11 dedup por ocorrencia - nao descarta palavra por vocabulario da referencia`() {
        val c = LiveTextCommitter()
        c.onWindow(
            0, "gato bola", 0L, 8_000L, 0,
            segments = listOf(
                LiveSegmentTime("gato", 0L, 250L),
                LiveSegmentTime("bola", 250L, 500L),
            ),
        )
        c.onWindow(
            1, "gato", 8_000L, 16_000L, 8_000,
            segments = listOf(LiveSegmentTime("gato", 250L, 450L)), // ocorrência [4000,7200)
        )
        c.finalize()
        val out = c.committedText()
        assertEquals("gato duas vezes (ocorrências distintas; out='$out')", 2, countOf(out, "gato"))
        assertTrue("bola presente (out='$out')", out.split(" ").contains("bola"))
    }

    // ================================================================ R8B-12

    /**
     * Terminal entre hipóteses: janela vazia (zero/no_speech) não muta; EOF
     * drena uma vez e é idempotente; cancel não publica cauda; reset não herda.
     */
    @Test
    fun `R8B-12 terminal - vazio no meio, EOF uma vez, cancel e reset`() {
        val c = LiveTextCommitter()
        c.onWindow(0, "a b c d", 0L, 16_000L, 0)
        val snap = c.committedText() + "|" + c.provisionalText()
        assertNull("janela vazia não commita", c.onWindow(1, "   ", 16_000L, 24_000L, 4_000))
        assertEquals("janela vazia não muta estado", snap, c.committedText() + "|" + c.provisionalText())
        c.finalize()
        assertEquals("a b c d", c.committedText())
        assertEquals("segundo finalize não drena", 0, c.finalize().committedWordsAdded)

        val c2 = LiveTextCommitter()
        c2.onWindow(0, "alfa beta gama delta", 0L, 16_000L, 0)
        val at = c2.committedText()
        c2.cancel()
        c2.finalize()
        assertEquals("cancel não publica cauda", at, c2.committedText())

        c2.reset()
        assertEquals(0, c2.committedWordCount())
        c2.onWindow(0, "novo", 0L, 4_000L, 0)
        c2.finalize()
        assertEquals("novo", c2.committedText())
    }

    // ================================================================ R8B-13

    /**
     * Limite de retenções fecha a mais antiga TIPADO (publicada, contador) e a
     * cerca de geração segue rejeitando janela de outro job.
     */
    @Test
    fun `R8B-13 limite de retencoes e generation stale`() {
        val c = LiveTextCommitter(2, 2, 4.0, 1, 2, 240)
        c.onWindow(0, "a b c d", 0L, 4L * dur, 0)
        c.onWindow(1, "m n", 4L * dur, 6L * dur, 2 * dur)
        c.onWindow(2, "o p", 6L * dur, 8L * dur, 4 * dur)
        val i3 = c.onWindow(3, "q r", 8L * dur, 10L * dur, 5 * dur)
        assertTrue("fechamento tipado", c.forcedClosureCount() >= 1)
        assertTrue("retidas ≤ limite", c.heldHypothesisCount() <= 2)
        c.finalize()
        val out = c.committedText()
        assertTrue("fechamento publicado (out='$out')", out.split(" ").contains("m"))

        val g = LiveTextCommitter()
        g.onWindow(0, "a b c", 0L, 12_000L, 0, generation = 7L)
        assertNull(
            "geração 8 não entra no job 7",
            g.onWindow(1, "outro job", 12_000L, 20_000L, 4_000, generation = 8L),
        )
        assertEquals(1, g.staleWindowCount())
        g.finalize()
        assertTrue("texto do job 7 intacto", g.committedText().startsWith("a b c"))
    }

    // ================================================================ R8B-14

    /**
     * Leitores de UI concorrentes enquanto o worker REVISA região (reprodutor
     * repetido em ritmo máximo com reset/finalize): nenhuma leitura pode lançar.
     */
    @Test
    fun `R8B-14 leitores concorrentes durante revisao de regiao`() {
        val c = LiveTextCommitter()
        val deadline = System.nanoTime() + 1_500_000_000L
        val error = AtomicReference<Throwable?>(null)
        val start = CountDownLatch(1)
        val segs0 = listOf(LiveSegmentTime("porta azul", 0L, 4000L))
        val segs1 = listOf(
            LiveSegmentTime("porta vermelha", 0L, 4000L),
            LiveSegmentTime("entrada nova", 4000L, 8000L),
        )

        fun reader() = Thread {
            start.await()
            try {
                while (System.nanoTime() < deadline && error.get() == null) {
                    c.displayText()
                    c.committedText()
                    c.provisionalText()
                    c.committedWordCount()
                    c.provisionalWordCount()
                    c.heldWordCount()
                    c.forcedClosureCount()
                }
            } catch (t: Throwable) {
                error.compareAndSet(null, t)
            }
        }

        val writer = Thread {
            start.await()
            var i = 0L
            try {
                while (System.nanoTime() < deadline && error.get() == null) {
                    c.reset()
                    c.onWindow(0, "porta azul", 0L, 64_000L, 0, segments = segs0, generation = i)
                    c.onWindow(
                        1, "porta vermelha entrada nova", 64_000L, 128_000L, 64_000,
                        segments = segs1, generation = i,
                    )
                    c.finalize()
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
            "concorrência leitura×revisão não pode lançar: ${e?.javaClass?.name}: ${e?.message}",
            e,
        )
    }
}
