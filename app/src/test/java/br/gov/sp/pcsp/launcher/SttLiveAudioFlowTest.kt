package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SttLiveAudioFlowTest {
    private fun muse() = SttLiveAudioFlow(isMuse = true, pcmBytesPerSecond = 32_000)

    @Test
    fun muse_reconexaoDescartaBufferDeOitoSegundosEInformaIntervaloPerdido() {
        val flow = muse()
        val recovery = flow.recoverAudio(ByteArray(8 * 32_000) { 1 }, 3 * 32_000L)

        assertFalse(flow.replaysAudio)
        assertEquals(0, recovery.replayAudio.size)
        assertEquals(96_000L, recovery.discardedAudioBytes)
        assertEquals(0L, flow.recoverAudio(ByteArray(0), 0L).discardedAudioBytes)
    }

    @Test
    fun muse_pausaEnviaSilencioSemAlterarAudioCapturado() {
        val flow = muse()
        val microphone = byteArrayOf(1, 2, 3, 4, 5, 6)

        assertArrayEquals(ByteArray(4), flow.outgoingAudio(microphone, 4, paused = true))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), microphone)
        assertSame(microphone, flow.outgoingAudio(microphone, 4, paused = false))
    }

    @Test
    fun muse_leiturasAcumuladasMantemCadenciaEmVezDeRajada() {
        val flow = muse()
        flow.resetPacing(1_000L)
        var now = 1_000L
        val sendTimes = (0 until 80).map {
            now += flow.delayBeforeSendMillis(3_200, now)
            now
        }

        assertEquals(1_000L, sendTimes.first())
        assertEquals(8_900L, sendTimes.last())
        assertTrue(sendTimes.zipWithNext().all { (first, second) -> second - first == 100L })
    }

    @Test
    fun muse_atrasoOuNovaSessaoNaoProvocaCompensacaoEmRajada() {
        val flow = muse()
        flow.resetPacing(1_000L)
        assertEquals(0L, flow.delayBeforeSendMillis(3_200, 1_000L))
        assertEquals(0L, flow.delayBeforeSendMillis(3_200, 5_000L))
        assertEquals(100L, flow.delayBeforeSendMillis(3_200, 5_000L))

        flow.resetPacing(6_000L)
        assertEquals(0L, flow.delayBeforeSendMillis(640, 6_000L))
        assertEquals(20L, flow.delayBeforeSendMillis(640, 6_000L))
    }

    @Test
    fun muse_framesLongosConfiguradosNaoAtrasamIngresso() {
        val flow = muse()
        assertEquals(20, flow.chunkMillis(20))
        assertEquals(80, flow.chunkMillis(80))
        assertEquals(100, flow.chunkMillis(2_000))
    }

    @Test
    fun demaisProvedoresPreservamReplayPausaETamanhoConfigurado() {
        val flow = SttLiveAudioFlow(isMuse = false, pcmBytesPerSecond = 32_000)
        val pcm = byteArrayOf(1, 2)
        val recovery = flow.recoverAudio(pcm, 32_000L)

        assertTrue(flow.replaysAudio)
        assertSame(pcm, recovery.replayAudio)
        assertEquals(0L, recovery.discardedAudioBytes)
        assertNull(flow.outgoingAudio(pcm, pcm.size, paused = true))
        assertEquals(2_000, flow.chunkMillis(2_000))
        assertEquals(0L, flow.delayBeforeSendMillis(3_200, 1_000L))
        assertEquals(0L, flow.delayBeforeSendMillis(3_200, 1_000L))
    }
}
