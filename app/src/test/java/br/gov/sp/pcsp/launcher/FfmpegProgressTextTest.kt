package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class FfmpegProgressTextTest {

    @Test
    fun activeStep_showsShortEncoder() {
        assertEquals(
            " (hevc)",
            FfmpegProgressText.suffix(encoderName = "hevc_mediacodec")
        )
    }

    @Test
    fun completedStep_showsShortEncoderAndElapsedSeconds() {
        assertEquals(
            " (hevc, 32.5s)",
            FfmpegProgressText.suffix(encoderName = "hevc_mediacodec", elapsedMs = 32_500L)
        )
        assertEquals(
            " (cpu, 40.0s)",
            FfmpegProgressText.suffix(encoderName = "libx264", elapsedMs = 40_000L)
        )
        assertEquals(
            " (h264, 22.7s)",
            FfmpegProgressText.suffix(encoderName = "h264_mediacodec", elapsedMs = 22_700L)
        )
    }

    @Test
    fun audioEncoder_showsReadableShortNameAndElapsedSeconds() {
        assertEquals(
            " (mp3, 2.4s)",
            FfmpegProgressText.suffix(encoderName = "libmp3lame", elapsedMs = 2_400L)
        )
    }

    @Test
    fun nonEncodedStep_doesNotInventEncoderOrElapsedSuffix() {
        assertEquals("", FfmpegProgressText.suffix(elapsedMs = 12_300L))
    }
}
