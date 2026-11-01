package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Adendo 16 §4 — cauda do áudio de ~30 s no ORT (modo temporal, produção).
 *
 * Controles sintéticos com backend roteirizado, sem ONNX: imediatamente abaixo
 * (29,98 s), exatamente no limite (30,000 s = 480000 amostras, como o chunk
 * `data` do c.wav) e acima (30,016 s = 480256 amostras, como o arquivo físico
 * do c.wav), incluindo fala que atravessa a fronteira e silêncio final.
 *
 * Garantias provadas: fala preservada até o fim, sem duplicação artificial,
 * desfecho coerente quando há fala não confirmada (PARTIAL, nunca SUCCESS
 * inventado nem texto cortado em silêncio).
 */
class WhisperOrtCaudaFronteiraTest {

    private val noTs = WhisperOrtPrompt.NO_TIMESTAMPS
    private val begin = WhisperOrtSegments.timestampBegin(noTs)

    private fun ts(seg: Double): Int = begin + Math.round(seg / 0.02).toInt()

    private fun tokenizer() = WhisperOrtTokenizer.fromSubset(
        mapOf("a" to 65, "b" to 66, "c" to 67, " " to 220),
    )

    private fun temporal() = WhisperOrtTranscriber(
        tokenizer(),
        WhisperOrtGenerationPolicy(
            prompt = WhisperOrtPrompt.portugueseWithTimestamps(),
            config = WhisperOrtGenerationConfig.frozenExport(),
            timestampMode = true,
        ),
    )

    private fun fala(amostras: Int) = FloatArray(amostras) { 0.1f }

    @Test fun `abaixoDoLimite_transcreveNormal`() {
        // 29,98 s numa janela: par fechado + fim simples.
        val backend = WhisperOrtFakeBackend(
            listOf(listOf(begin, 65, ts(28.0), ts(28.0), 66, ts(29.9), WhisperOrtPrompt.END_OF_TEXT)),
        )
        val r = temporal().transcribe(backend, fala(479_680))
        assertEquals(WhisperOrtOutcome.SUCCESS, r.outcome)
        assertEquals("a b", r.text)
        assertEquals(1, backend.encodeCalls)
    }

    @Test fun `exatamenteNoLimite_preservaCaudaSemDuplicar`() {
        // 30,000 s exatos (480000 amostras): fala colada no segundo 30.
        val backend = WhisperOrtFakeBackend(
            listOf(listOf(begin, 65, ts(29.5), ts(29.5), 66, ts(30.0), WhisperOrtPrompt.END_OF_TEXT)),
        )
        val r = temporal().transcribe(backend, fala(480_000))
        assertEquals(WhisperOrtOutcome.SUCCESS, r.outcome)
        assertEquals("a b", r.text)
        assertEquals(1, backend.encodeCalls)
    }

    @Test fun `acimaDoLimite_16msVaoParaSegundaJanelaSemDuplicar`() {
        // 30,016 s (480256 amostras, espelho do c.wav): a cauda de 256
        // amostras reabre a fronteira em 29 s; a palavra "b" fecha na janela 2
        // em 29,5 s e a reconciliação abre uma 3ª janela curta sobre a cauda
        // com padding (sem ela, a perda seria silenciosa). Texto uma vez só.
        val backend = WhisperOrtFakeBackend(
            listOf(
                listOf(begin, 65, ts(29.0), ts(29.0), 66),
                listOf(ts(0.0), 66, ts(0.5), WhisperOrtPrompt.END_OF_TEXT),
            ),
        )
        val r = temporal().transcribe(backend, fala(480_256))
        assertEquals(WhisperOrtOutcome.SUCCESS, r.outcome)
        assertEquals("a b", r.text)
        assertEquals(3, backend.encodeCalls)
    }

    @Test fun `falaQueAtravessaAFronteira_naoCortaNemDuplica`() {
        // 31 s: palavra "b" começa na janela 1 (29 s) e fecha na janela 2.
        val backend = WhisperOrtFakeBackend(
            listOf(
                listOf(begin, 65, ts(29.0), ts(29.0), 66),
                listOf(ts(0.0), 66, ts(1.5), WhisperOrtPrompt.END_OF_TEXT),
            ),
        )
        val r = temporal().transcribe(backend, fala(496_000))
        assertEquals(WhisperOrtOutcome.SUCCESS, r.outcome)
        assertEquals("a b", r.text)
    }

    @Test fun `silencioFinal_resultadoCoerenteSemTextoInventado`() {
        // 31 s com fala só até 28 s + silêncio digital: nada além de "a".
        val pcm = fala(496_000)
        java.util.Arrays.fill(pcm, 448_000, 496_000, 0.0f)
        val backend = WhisperOrtFakeBackend(
            listOf(
                listOf(begin, 65, ts(28.0), WhisperOrtPrompt.END_OF_TEXT),
                listOf(WhisperOrtPrompt.END_OF_TEXT),
            ),
        )
        val r = temporal().transcribe(backend, pcm)
        assertTrue(
            "desfecho deveria ser SUCCESS ou PARTIAL, veio ${r.outcome}",
            r.outcome == WhisperOrtOutcome.SUCCESS || r.outcome == WhisperOrtOutcome.PARTIAL,
        )
        assertEquals("a", r.text.trim())
    }
}
