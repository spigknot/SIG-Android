package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Vacina dos modos de corte (SmartCut / Reencode Completo / Sem Reencode).
 *
 * Regras que estes testes travam (as mesmas do SIG Windows):
 * - as três opções, nessa ordem e com esses rótulos, com SmartCut como PADRÃO;
 * - o "?" explica os três modos;
 * - a seleção de área força o Reencode Completo, com motivo (copiar streams
 *   não recorta pixels e o miolo copiado do SmartCut não pode ser recortado);
 * - só o modo de cópia não usa encoder de vídeo.
 */
class FfmpegCutModesTest {

    @Test
    fun rotulosEOrdem() {
        assertEquals(
            listOf("SmartCut", "Reencode Completo", "Sem Reencode"),
            FfmpegCutModes.MODES
        )
        assertEquals(FfmpegCutModes.SMART, FfmpegCutModes.DEFAULT)
        assertEquals("SmartCut", FfmpegCutModes.MODES.first())
    }

    @Test
    fun ajudaDoSeletorExplicaOsTresModos() {
        assertTrue(FfmpegCutModes.HELP.contains("SmartCut"))
        assertTrue(FfmpegCutModes.HELP.contains("EXPERIMENTAL"))
        assertTrue(FfmpegCutModes.HELP.contains("lento e preciso"))
        assertTrue(FfmpegCutModes.HELP.contains("rápido e menos preciso"))
    }

    @Test
    fun apenasSemReencodeEModoDeCopia() {
        assertFalse(FfmpegCutModes.isCopy(FfmpegCutModes.SMART))
        assertFalse(FfmpegCutModes.isCopy(FfmpegCutModes.REENCODE))
        assertTrue(FfmpegCutModes.isCopy(FfmpegCutModes.COPY))
    }

    @Test
    fun soOCopiaNaoUsaEncoderDeVideo() {
        assertTrue(FfmpegCutModes.usesVideoEncoder(FfmpegCutModes.SMART))
        assertTrue(FfmpegCutModes.usesVideoEncoder(FfmpegCutModes.REENCODE))
        assertFalse(FfmpegCutModes.usesVideoEncoder(FfmpegCutModes.COPY))
    }

    @Test
    fun semSelecaoOModoEscolhidoEOMesmo() {
        FfmpegCutModes.MODES.forEach { mode ->
            val plan = FfmpegCutModes.videoPlan(mode, hasCrop = false)
            assertEquals(mode, plan.mode)
            assertNull(plan.reason)
        }
    }

    @Test
    fun selecaoDeAreaForcaReencodeCompletoNaCopia() {
        val plan = FfmpegCutModes.videoPlan(FfmpegCutModes.COPY, hasCrop = true)
        assertEquals(FfmpegCutModes.REENCODE, plan.mode)
        assertTrue(plan.reason!!.contains("seleção de área"))
        assertTrue(plan.reason!!.contains("Reencode Completo"))
    }

    @Test
    fun selecaoDeAreaForcaReencodeCompletoNoSmartCut() {
        val plan = FfmpegCutModes.videoPlan(FfmpegCutModes.SMART, hasCrop = true)
        assertEquals(FfmpegCutModes.REENCODE, plan.mode)
        assertTrue(plan.reason!!.contains("seleção de área"))
    }

    @Test
    fun reencodeCompletoComSelecaoContinuaReencode() {
        val plan = FfmpegCutModes.videoPlan(FfmpegCutModes.REENCODE, hasCrop = true)
        assertEquals(FfmpegCutModes.REENCODE, plan.mode)
        assertNull(plan.reason)
    }

    @Test
    fun smartCutCaiQuandoNaoDaParaCopiarOMiolo() {
        assertEquals(
            "Codec vp9 não permite cópia híbrida.",
            FfmpegCutModes.smartCutFallbackReason("vp9", hasInternalKeyframes = true, edgeEncoderAvailable = true)
        )
        assertTrue(
            FfmpegCutModes.smartCutFallbackReason("hevc", hasInternalKeyframes = true, edgeEncoderAvailable = false)!!
                .contains("mesmo codec")
        )
        assertTrue(
            FfmpegCutModes.smartCutFallbackReason("h264", hasInternalKeyframes = false, edgeEncoderAvailable = true)!!
                .contains("keyframes internos")
        )
        assertNull(
            FfmpegCutModes.smartCutFallbackReason("h264", hasInternalKeyframes = true, edgeEncoderAvailable = true)
        )
    }
}
