package br.gov.sp.pcsp.launcher

import br.gov.sp.pcsp.launcher.WhisperSelectors.Choice
import br.gov.sp.pcsp.launcher.WhisperSelectors.Hardware
import org.junit.Assert.*
import org.junit.Test

class WhisperSelectorsTest {
    @Test
    fun `ORT e o padrao e a escolha moderna precede a legada`() {
        assertTrue(WhisperSelectors.useOrt(null, null))
        assertFalse(WhisperSelectors.useOrt(null, false))
        assertTrue(WhisperSelectors.useOrt(null, true))
        assertTrue(WhisperSelectors.useOrt(false, false))
        assertFalse(WhisperSelectors.useOrt(true, true))
    }

    @Test
    fun `hardware depende do SoC modelo e versao verificada`() {
        for (hardware in listOf(Hardware.HYBRID, Hardware.NPU)) {
            assertEquals(Choice.UNSUPPORTED_SOC, WhisperSelectors.choice(hardware, false, true, 3))
            assertEquals(Choice.UNSUPPORTED_MODEL, WhisperSelectors.choice(hardware, true, false, 3))
            assertEquals(Choice.DOWNLOAD, WhisperSelectors.choice(hardware, true, true, 0))
            assertEquals(Choice.READY, WhisperSelectors.choice(hardware, true, true, 3))
        }
        assertEquals(Choice.READY, WhisperSelectors.choice(Hardware.CPU, false, false, 0))
        assertEquals(Choice.READY, WhisperSelectors.choice(Hardware.HYBRID, true, true, 2))
        assertEquals(Choice.DOWNLOAD, WhisperSelectors.choice(Hardware.NPU, true, true, 2))
    }

    @Test
    fun `hardware implicito so usa NPU com v3 turbo e SoC admitido`() {
        assertEquals(Hardware.NPU, WhisperSelectors.effective(null, true, true, 3))
        assertEquals(Hardware.CPU, WhisperSelectors.effective(null, true, true, 0))
        assertEquals(Hardware.CPU, WhisperSelectors.effective(null, true, false, 3))
        assertEquals(Hardware.CPU, WhisperSelectors.effective(null, false, true, 3))
        assertEquals(Hardware.CPU, WhisperSelectors.effective(Hardware.CPU, true, true, 3))
        assertEquals(Hardware.HYBRID, WhisperSelectors.effective(Hardware.HYBRID, true, true, 3))
        assertEquals(Hardware.CPU, WhisperSelectors.effective(Hardware.HYBRID, true, false, 3))
    }

    @Test
    fun `ORT ignora o valor salvo do Flash Attention`() {
        assertFalse(WhisperSelectors.flashAttention(true, true))
        assertTrue(WhisperSelectors.flashAttention(false, true))
        assertFalse(WhisperSelectors.flashAttention(false, false))
    }

    @Test
    fun `rotulos do VAD incluem nivel apenas quando ativo`() {
        assertEquals("VAD: Nenhum", LocalVad.Selection().label)
        assertEquals("VAD: Silero CPU (2)", LocalVad.Selection(LocalVad.Mode.SILERO_CPU, 2).label)
        assertEquals("VAD: Silero GPU (1)", LocalVad.Selection(LocalVad.Mode.SILERO_GPU, 1).label)
        assertEquals("VAD: WebRTC (3)", LocalVad.Selection(LocalVad.Mode.WEBRTC, 3).label)
    }
}
