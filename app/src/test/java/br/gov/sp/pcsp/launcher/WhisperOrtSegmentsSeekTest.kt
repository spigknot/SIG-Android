package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Porte do `_retrieve_segment`: só segmento FECHADO por par de timestamps é
 * publicado, a cauda fica pendente e o seek vai para a última fronteira fechada.
 */
class WhisperOrtSegmentsSeekTest {

    private val noTs = WhisperOrtPrompt.NO_TIMESTAMPS
    private val begin = WhisperOrtSegments.timestampBegin(noTs)
    private val vocab = 51_866

    private fun ts(seg: Double): Int = begin + Math.round(seg / 0.02).toInt()
    /** Decodifica só os tokens de texto: timestamps são especiais e ficam fora. */
    private fun decode(ids: IntArray): String =
        ids.filter { it < begin }
            .joinToString(" ") { if (it == 65) "a" else if (it == 66) "b" else "c" }

    // ---- retrieve ---------------------------------------------------------
    @Test
    fun `par de timestamps fecha segmento e o seek vai para a fronteira`() {
        // [ts0 a ts2 ts2 b ts4 b] -> fecha em 2 s, cauda "b" pendente, seek 2 s.
        val tokens = listOf(ts(0.0), 65, ts(2.0), ts(2.0), 66)
        val r = WhisperOrtSegments.retrieve(tokens, noTs, vocab, 0.0, 30.0, ::decode)
        assertEquals(1, r.segments.size)
        assertEquals(0.0, r.segments[0].startSeconds, 1e-9)
        assertEquals(2.0, r.segments[0].endSeconds, 1e-9)
        assertEquals("a", r.segments[0].text)
        assertEquals("b", r.pendingText)
        assertEquals(2.0, r.seekSeconds, 1e-9)
        assertFalse(r.singleTimestampEnding)
    }

    @Test
    fun `fim simples consome a janela inteira`() {
        // [ts0 a b ts3] -> dois ultimos [texto, ts] = fim simples.
        val tokens = listOf(ts(0.0), 65, 66, ts(3.0))
        val r = WhisperOrtSegments.retrieve(tokens, noTs, vocab, 0.0, 30.0, ::decode)
        assertTrue(r.singleTimestampEnding)
        assertEquals(30.0, r.seekSeconds, 1e-9)
        assertEquals("", r.pendingText)
        assertEquals(1, r.segments.size)
        assertEquals(3.0, r.segments[0].endSeconds, 1e-9)
    }

    @Test
    fun `sem fronteira a janela inteira e um segmento`() {
        val tokens = listOf(65, 66)
        val r = WhisperOrtSegments.retrieve(tokens, noTs, vocab, 0.0, 30.0, ::decode)
        assertEquals(1, r.segments.size)
        assertEquals("a b", r.segments[0].text)
        assertEquals(30.0, r.seekSeconds, 1e-9)
    }

    @Test
    fun `pausa desigual nao perde fala nova`() {
        // Janela 0: "aguarde" fechado em 8 s.
        val j1 = listOf(ts(0.0), 65, ts(8.0), ts(8.0))
        val r1 = WhisperOrtSegments.retrieve(j1, noTs, vocab, 0.0, 30.0, ::decode)
        var (texto, fim) = WhisperOrtSegments.join("", 0.0, r1.segments)
        assertEquals("a", texto)
        // Janela 2 em 8 s: "aguarde sim" com pausa e "sim" novo em 8..9.
        val j2 = listOf(ts(0.0), 65, ts(1.0), 66, ts(2.0))
        val r2 = WhisperOrtSegments.retrieve(j2, noTs, vocab, 8.0, 22.0, ::decode)
        val (final, _) = WhisperOrtSegments.join(texto, fim, r2.segments)
        assertTrue(final.contains("a"))
        assertTrue(final.contains("b"))
    }

    @Test
    fun `palavra igual em ocorrencia distinta nao e apagada`() {
        val j1 = listOf(ts(0.0), 65, ts(1.0), ts(1.0))
        val r1 = WhisperOrtSegments.retrieve(j1, noTs, vocab, 0.0, 30.0, ::decode)
        val (t1, f1) = WhisperOrtSegments.join("", 0.0, r1.segments)
        val j2 = listOf(ts(0.0), 65, ts(1.0), ts(1.0))
        val r2 = WhisperOrtSegments.retrieve(j2, noTs, vocab, 8.0, 22.0, ::decode)
        val (t2, _) = WhisperOrtSegments.join(t1, f1, r2.segments)
        assertEquals("a a", t2)
    }

    @Test
    fun `seek nunca volta para tras`() {
        val j = listOf(ts(0.0), 65, ts(2.0), ts(2.0), 66)
        val r = WhisperOrtSegments.retrieve(j, noTs, vocab, 10.0, 30.0, ::decode)
        assertTrue(r.seekSeconds >= 10.0)
    }

    // ---- transcriber no modo temporal -------------------------------------
    private fun tokenizer() = WhisperOrtTokenizer.fromSubset(
        mapOf("a" to 65, "b" to 66, "\u0120" to 220, "z" to 51_865),
    )

    @Test
    fun `transcriber temporal junta por tempo`() {
        val backend = // Sequência compatível com a referência (WhisperTimeStampLogitsProcessor):
        // ts, texto, ts, ts, texto, ts, ts -> os pares fecham como no export real
        // (trace do PJA110: pares em 5,08/6,38 s e 11,02/12,38 s). O roteiro
        // anterior (ts, texto, ts, ts, texto, ts, EOS) violava a regra de pares:
        // exigia texto onde a referência exige timestamp.
        WhisperOrtFakeBackend(
            listOf(
                listOf(
                    begin, 65, ts(2.0), ts(2.0), 66, ts(4.0), ts(4.0),
                    WhisperOrtPrompt.END_OF_TEXT,
                ),
            ),
        )
        val politica = WhisperOrtGenerationPolicy(
            prompt = WhisperOrtPrompt.portugueseWithTimestamps(),
            config = WhisperOrtGenerationConfig.frozenExport(),
            timestampMode = true,
        )
        val r = WhisperOrtTranscriber(tokenizer(), politica).transcribe(backend, FloatArray(16_000) { 0.1f })
        assertTrue(r.text.isNotBlank())
        assertEquals(WhisperOrtOutcome.SUCCESS, r.outcome)
    }

    // ---- cache (preservado do arquivo anterior) ---------------------------
    private class Fake(val thread: Int) : WhisperOrtBackend {
        var closed = false
        override fun encode(logMel: FloatArray) = FloatArray(1)
        override fun start(hidden: FloatArray, prompt: IntArray) = FloatArray(1)
        override fun step(token: Int) = FloatArray(1)
        override fun close() { closed = true }
    }

    private fun chave(threads: Int = 4, config: String = "cfg1", pacote: String = "v1") =
        WhisperOrtSessionCache.Key("ORT", "CPU", "1.29.0", "m", "r", config, threads, pacote)

    private fun cache() = WhisperOrtSessionCache(factory = { _, _, _, t -> Fake(t) }, open = { })

    @Test
    fun `segundo job reutiliza a sessao e conta hit`() {
        val c = cache()
        val a = c.acquire(chave(), "v1", File("e"), File("d"), File("p"))!!
        assertTrue(c.release(a))
        val b = c.acquire(chave(), "v1", File("e"), File("d"), File("p"))!!
        assertSame(a.backend, b.backend)
        assertEquals(1, c.stats().opens)
        assertEquals(1, c.stats().hits)
    }

    @Test
    fun `compute exclusivo e release recusado durante run`() {
        val c = cache()
        val a = c.acquire(chave(), "v1", File("e"), File("d"), File("p"))!!
        val b = c.acquire(chave(), "v1", File("e"), File("d"), File("p"))!!
        assertTrue(c.tryBeginCompute(a))
        assertFalse(c.tryBeginCompute(b))
        assertFalse(c.release(a))
        c.endCompute(a)
        assertTrue(c.release(a))
    }

    @Test
    fun `chave de outro pacote nao devolve a sessao alheia`() {
        val c = cache()
        c.acquire(chave(pacote = "v1"), "v1", File("e"), File("d"), File("p"))!!
        assertNull(c.acquire(chave(pacote = "v1"), "v2", File("e2"), File("d2"), File("p2")))
    }
}
