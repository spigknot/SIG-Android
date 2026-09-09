package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SttAudioProbeTest {

    private val logOpus = """
        Input #0, ogg, from 'audio.ogg':
          Duration: 00:00:10.00, start: 0.000000, bitrate: 24 kb/s
          Stream #0:0: Audio: opus, 16000 Hz, mono, fltp, 24 kb/s
    """.trimIndent()

    @Test
    fun parseLogs_opusMono16k() {
        val probe = SttAudioProbe.parseLogs(logOpus, 0L)
        assertEquals("opus", probe.codec)
        assertEquals("16000hz", probe.sampleRate)
        assertEquals("mono", probe.channels)
        assertEquals("24k", probe.bitrate)
        assertEquals(16000, probe.sampleRateHz)
        assertEquals(1, probe.channelCount)
        assertEquals(24.0, probe.bitrateKbps!!, 0.0001)
        assertFalse(probe.hasVideo)
    }

    @Test
    fun parseLogs_aacStereo() {
        val probe = SttAudioProbe.parseLogs(
            "  Duration: 00:00:05.00, bitrate: 128 kb/s\n  Stream #0:0: Audio: aac (LC), 48000 Hz, stereo, fltp, 128 kb/s",
            0L
        )
        assertEquals("aac", probe.codec)
        assertEquals("stereo", probe.channels)
        assertEquals(2, probe.channelCount)
    }

    @Test
    fun parseLogs_canaisExplicitos() {
        val probe = SttAudioProbe.parseLogs("  Stream #0:1: Audio: ac3, 48000 Hz, 6 channels, fltp", 0L)
        assertEquals(6, probe.channelCount)
        assertEquals("6ch", probe.channels)
    }

    @Test
    fun parseLogs_semLinhaDeAudio_naoQuebra() {
        val probe = SttAudioProbe.parseLogs("  Stream #0:0: Video: h264, yuv420p, 1920x1080", 0L)
        assertEquals("", probe.codec)
        assertNull(probe.sampleRateHz)
        assertNull(probe.channelCount)
        assertTrue(probe.hasVideo)
    }

    @Test
    fun parseLogs_bitratePorDuracaoQuandoLogNaoTraz() {
        // 100000 bytes em 10 s = 80 kbps
        val probe = SttAudioProbe.parseLogs(
            "  Duration: 00:00:10.00, start: 0.0\n  Stream #0:0: Audio: pcm_s16le, 16000 Hz, mono",
            100_000L
        )
        assertEquals(80.0, probe.bitrateKbps!!, 0.0001)
        assertEquals("80k", probe.bitrate)
    }

    @Test
    fun parseLogs_semBitrateNemDuracao_ficaVazio() {
        val probe = SttAudioProbe.parseLogs("  Stream #0:0: Audio: pcm_s16le, 16000 Hz, mono", 0L)
        assertNull(probe.bitrateKbps)
        assertEquals("", probe.bitrate)
    }

    @Test
    fun metadataSummary_formataCamposVaziosComoInterrogacao() {
        val resumo = SttAudioProbe.metadataSummary(SttAudioProbe.AudioProbe("", "", "", "", null, null, null, false))
        assertEquals("codec=?, hz=?, canais=?, bitrate=?, video=não", resumo)
    }

    @Test
    fun metadataSummary_comVideoMarcado() {
        val resumo = SttAudioProbe.metadataSummary(
            SttAudioProbe.AudioProbe("h264", "48000hz", "stereo", "128k", 48000, 2, 128.0, true)
        )
        assertEquals("codec=h264, hz=48000hz, canais=stereo, bitrate=128k, video=sim", resumo)
    }
}
