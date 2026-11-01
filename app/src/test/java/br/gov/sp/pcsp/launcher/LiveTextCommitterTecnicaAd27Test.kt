package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADENDO 27 §1 — REGRESSÃO da região retida espúria na fronteira de janelas.
 *
 * Fatos do aparelho (live d01, janela VAD): a janela 0 transcreveu
 * "a equipe de campo concluiu a inspeção" (5 comprometidas + 2 provisórias) e a
 * janela 1 repetiu o MESMO trecho no início
 * ("A equipe de campo concluiu a inspeção técnica ..."), mas as 2 palavras
 * provisórias foram RETIDAS como conflito (`conflicts_held=2`).
 *
 * Causa: com palavras ESTIMADAS (sem word_times do motor), `occCompatible`
 * compara spans de grids DIFERENTES (cada janela tem o seu step uniforme) e
 * recusa a MESMA ocorrência; a região retida fica sem áudio próprio (ou com
 * áudio que começa DEPOIS dela) e o reparo nunca reproduz as palavras → o
 * fechamento promove sem confirmação e o live termina ERROR.
 *
 * Regra correta: palavra PROVISÓRIA da cauda (STATE_REVIEWABLE) confirmada
 * TEXTUALMENTE pela janela seguinte, no trecho de sobreposição, é CONFIRMAÇÃO —
 * a grade estimada não pode vetar a mesma ocorrência.
 */
class LiveTextCommitterTecnicaAd27Test {

    private fun pcm(samples: Long): ByteArray = ByteArray((samples * 2).toInt())

    private fun committer() = LiveTextCommitter()

    @Test
    fun caudaProvisoriaConfirmadaPelaJanelaSeguinteNaoFicaRetida() {
        val c = committer()
        // Janela 0: 7 palavras, contexto 0, grid estimado sobre [0, 32960).
        c.onWindow(
            windowIndex = 0,
            text = "a equipe de campo concluiu a inspeção",
            uniqueStartSample = 0,
            uniqueEndSample = 32_960,
            contextSamplesInChunk = 0,
            windowPcm = pcm(32_960),
        )
        // Janela 1: repete o trecho no INÍCIO (sobreposição textual) e segue.
        // Contexto 2 s (32000 amostras); único [32960, 198080).
        val info = c.onWindow(
            windowIndex = 1,
            text = "A equipe de campo concluiu a inspeção técnica",
            uniqueStartSample = 32_960,
            uniqueEndSample = 198_080,
            contextSamplesInChunk = 32_000,
            windowPcm = pcm(198_080 - 960),
        )
        println("ALIGN=" + info?.alignment + " held=" + info?.heldHypotheses +
            " heldWords=" + c.heldWordCount() + " conflicts=" + info?.conflictsHeld +
            " display=[" + c.displayText() + "] provisional=[" + c.provisionalText() + "]")
        val visible = c.displayText()
        assertEquals(
            "texto visível não pode duplicar a cauda confirmada",
            "a equipe de campo concluiu a inspeção técnica",
            visible.trim().lowercase(),
        )
        assertEquals(
            "não pode sobrar hipótese retida para uma cauda CONFIRMADA pela janela",
            0,
            info?.heldHypotheses ?: -1,
        )
        assertEquals(0, c.pendingRepairCount())
    }

    @Test
    fun caudaProvisoriaAusenteNaJanelaSeguinteContinuaRetida() {
        val c = committer()
        c.onWindow(
            windowIndex = 0,
            text = "alfa bravo charlie delta echo",
            uniqueStartSample = 0,
            uniqueEndSample = 16_000,
            contextSamplesInChunk = 0,
            windowPcm = pcm(16_000),
        )
        // A janela 1 NÃO repete a cauda (texto diferente): a hipótese tem de ser
        // preservada (nada some em silêncio).
        val info = c.onWindow(
            windowIndex = 1,
            text = "foxtrot golf hotel india",
            uniqueStartSample = 16_000,
            uniqueEndSample = 48_000,
            contextSamplesInChunk = 8_000,
            windowPcm = pcm(48_000 - 8_000),
        )
        assertTrue(
            "cauda não confirmada precisa permanecer retida",
            (info?.heldHypotheses ?: 0) >= 1,
        )
    }

    @Test
    fun reparoDeRegiaoSemTempoRealRetemOPcmInteiroDaJanela() {
        val c = committer()
        // Janela > 10 s (o teto antigo de 160k amostras rejeitava e marcava
        // REPAIR_NO_AUDIO — o defeito do live).
        val c0 = c.onWindow(
            windowIndex = 0,
            text = "a equipe de campo concluiu",
            uniqueStartSample = 0,
            uniqueEndSample = 32_960,
            contextSamplesInChunk = 0,
            windowPcm = pcm(32_960),
        )
        // Sem sobreposição textual: força uma região retida mesmo depois da
        // correção de deduplicação, para o teste realmente exercer o teto.
        val janela = ByteArray(((198_080L - 960L) * 2).toInt()) { (it % 251).toByte() }
        val c1 = c.onWindow(
            windowIndex = 1,
            text = "foxtrot golf hotel india juliet kilo lima mike",
            uniqueStartSample = 32_960,
            uniqueEndSample = 198_080,
            contextSamplesInChunk = 32_000,
            windowPcm = janela,
        )
        assertEquals("o cenário precisa gerar a região que verifica o teto", 1, c1?.heldHypotheses)
        assertEquals("região retida precisa estar pendente de reparo", 1, c.pendingRepairCount())
        val req = c.takeRepairRequest()
        assertNotNull("a região de 10,32 s precisa conservar PCM para reparo", req)
        assertEquals(32_960L, req!!.startSample)
        assertEquals(198_080L, req.endSample)
        assertArrayEquals("reparo usa somente o áudio próprio", janela.copyOfRange(64_000, janela.size), req.pcm)
        c.finalize()
        val r = c.regionOutcome()
        assertEquals(
            "nenhuma região pode ser promovida com audio_ausente por teto de amostras",
            0,
            r.promotedAudioMissing,
        )
        assertEquals(0, c0?.heldHypotheses ?: 0)
    }
}
