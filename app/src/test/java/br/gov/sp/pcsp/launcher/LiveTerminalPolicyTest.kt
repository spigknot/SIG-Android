package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R16/B2 — TERMINAL LIVE TIPADO (seis desfechos).
 *
 * A R15 usava `if (cancelled) CANCELLED else SUCCESS`: silêncio sem texto,
 * reparo falho e região pendente viravam SUCCESS, e o export tratava parcial
 * como sucesso completo.
 */
class LiveTerminalPolicyTest {

    private fun decide(
        cancelled: Boolean = false, integral: Boolean = true,
        repairFailed: Int = 0, audioMissing: Int = 0, noRepair: Int = 0,
        invalidEvidence: Int = 0, semTexto: Boolean = false,
    ) = LiveTerminalPolicy.decide(
        cancelled, integral, repairFailed, audioMissing, noRepair, invalidEvidence, semTexto
    )

    @Test
    fun `1 sucesso completo`() {
        assertEquals(JobTerminalState.SUCCESS, decide())
    }

    @Test
    fun `2 cancelamento`() {
        assertEquals(JobTerminalState.CANCELLED, decide(cancelled = true, semTexto = true))
    }

    @Test
    fun `3 no-speech sem texto NAO e sucesso`() {
        assertEquals(JobTerminalState.NO_SPEECH, decide(semTexto = true))
    }

    @Test
    fun `4 falha de reparo NAO e sucesso`() {
        assertEquals(JobTerminalState.ERROR, decide(repairFailed = 1))
    }

    @Test
    fun `5 regiao nao integral NAO e sucesso`() {
        assertEquals(JobTerminalState.ERROR, decide(integral = false))
    }

    @Test
    fun `6 audio ausente NAO e sucesso`() {
        assertEquals(JobTerminalState.ERROR, decide(audioMissing = 1))
    }

    @Test
    fun `evidencia de tempo invalida NAO e sucesso`() {
        assertEquals(JobTerminalState.ERROR, decide(invalidEvidence = 1))
    }

    @Test
    fun `sem reparo disponivel NAO e sucesso`() {
        assertEquals(JobTerminalState.ERROR, decide(noRepair = 1))
    }

    @Test
    fun `cancelamento vence pendencia (parcial preservado)`() {
        assertEquals(
            "cancel com pendência continua CANCELLED",
            JobTerminalState.CANCELLED, decide(cancelled = true, repairFailed = 2, integral = false)
        )
    }

    @Test
    fun `status nativo respeita o contrato do audit`() {
        assertEquals("cancelled", LiveTerminalPolicy.nativeStatus(JobTerminalState.CANCELLED))
        assertEquals("error", LiveTerminalPolicy.nativeStatus(JobTerminalState.ERROR))
        assertEquals("ok", LiveTerminalPolicy.nativeStatus(JobTerminalState.SUCCESS))
        assertEquals("ok", LiveTerminalPolicy.nativeStatus(JobTerminalState.NO_SPEECH))
    }

    @Test
    fun `so sucesso completo e elegivel para WER de sucesso`() {
        assertTrue(LiveTerminalPolicy.werElegivelSucesso(JobTerminalState.SUCCESS))
        assertFalse(LiveTerminalPolicy.werElegivelSucesso(JobTerminalState.ERROR))
        assertFalse(LiveTerminalPolicy.werElegivelSucesso(JobTerminalState.CANCELLED))
        assertFalse(
            "no-speech não é sucesso com texto",
            LiveTerminalPolicy.werElegivelSucesso(JobTerminalState.NO_SPEECH)
        )
    }

    // ------------------------------------------- R17/B2: fatos do pipeline

    @Test
    fun `R17 falha no ULTIMO chunk vira ERROR mesmo com janela boa`() {
        // Contraexemplo da revisão: janela boa + trecho final falho que nunca
        // chega ao committer → a R16 escolhia SUCCESS.
        val f = LiveTerminalPolicy.Facts(
            cancelled = false, integral = true, semTexto = false,
            failedSamples = 32_000L,
        )
        assertEquals(JobTerminalState.ERROR, LiveTerminalPolicy.decide(f))
        assertFalse(LiveTerminalPolicy.werElegivelSucesso(
            LiveTerminalPolicy.decide(f)))
    }

    @Test
    fun `R17 perda no spool vira ERROR`() {
        val f = LiveTerminalPolicy.Facts(lostSamples = 1_600L)
        assertEquals(JobTerminalState.ERROR, LiveTerminalPolicy.decide(f))
    }

    @Test
    fun `R17 amostras pendentes ao terminar viram ERROR`() {
        val f = LiveTerminalPolicy.Facts(pendingSamples = 3_200L)
        assertEquals(JobTerminalState.ERROR, LiveTerminalPolicy.decide(f))
    }

    @Test
    fun `R17 pendencia em cancelamento nao rouba o CANCELLED`() {
        val f = LiveTerminalPolicy.Facts(
            cancelled = true, failedSamples = 32_000L, pendingSamples = 1_600L)
        assertEquals("cancel preserva o comprometido",
            JobTerminalState.CANCELLED, LiveTerminalPolicy.decide(f))
    }

    @Test
    fun `R17 controle limpo continua SUCCESS`() {
        val f = LiveTerminalPolicy.Facts(
            cancelled = false, integral = true, semTexto = false)
        assertEquals(JobTerminalState.SUCCESS, LiveTerminalPolicy.decide(f))
        assertEquals("completo", LiveTerminalPolicy.motivo(f))
    }

    @Test
    fun `R17 silencio legitimo sem falha vira NO_SPEECH`() {
        val f = LiveTerminalPolicy.Facts(semTexto = true)
        assertEquals(JobTerminalState.NO_SPEECH, LiveTerminalPolicy.decide(f))
    }

    @Test
    fun `R17 motivo nomeia a falha do pipeline`() {
        val f = LiveTerminalPolicy.Facts(failedSamples = 10L)
        assertTrue(LiveTerminalPolicy.motivo(f).startsWith("falha_pipeline"))
    }
}
