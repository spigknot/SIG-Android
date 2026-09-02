package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FfmpegVideoQualityTest {

    @Test
    fun missingSourceBitrateIsEstimatedFromVideoShape() {
        assertEquals("6221k", estimateVideoBitrate(1920, 1080, 30.0, "h264"))
        assertEquals("4044k", estimateVideoBitrate(1920, 1080, 30.0, "hevc"))
        assertEquals("24883k", estimateVideoBitrate(3840, 2160, 30.0, "h264"))
        assertEquals("691k", estimateVideoBitrate(640, 360, 30.0, "h264"))
    }

    @Test
    fun scaleVideoBitrate_scalesKbpsCorrectly() {
        assertEquals("2000K", scaleVideoBitrate("2000k", 1.00))
        assertEquals("900K", scaleVideoBitrate("2000k", 0.45))
        assertEquals("1400K", scaleVideoBitrate("2000k", 0.70))
        assertEquals("2500K", scaleVideoBitrate("2000k", 1.25))
        assertEquals("3200K", scaleVideoBitrate("2000k", 1.60))
    }

    @Test
    fun scaleVideoBitrate_scalesMbpsCorrectly() {
        assertEquals("15M", scaleVideoBitrate("15M", 1.00))
        assertEquals("7M", scaleVideoBitrate("15M", 0.45))
        assertEquals("11M", scaleVideoBitrate("15M", 0.70))
        assertEquals("19M", scaleVideoBitrate("15M", 1.25))
        assertEquals("24M", scaleVideoBitrate("15M", 1.60))
    }

    @Test
    fun scaleVideoBitrate_handlesDecimalBitrates() {
        assertEquals("5M", scaleVideoBitrate("4.8M", 1.00))
        assertEquals("6M", scaleVideoBitrate("4.8M", 1.25))
    }

    @Test
    fun encodingFor_hardwareEncoder_appliesMultipliers() {
        val h264Hardware = FfmpegVideoEncoder("h264_mediacodec", "h264")
        val economic = h264Hardware.encodingFor(FfmpegVideoQuality.ECONOMIC, "10000k")
        assertEquals("4500K", economic.targetBitrate)
        assertEquals(
            listOf("-c:v", "h264_mediacodec", "-pix_fmt", "yuv420p", "-bf", "0"),
            economic.arguments
        )

        val maximum = h264Hardware.encodingFor(FfmpegVideoQuality.MAXIMUM, "10000k")
        assertEquals("16000K", maximum.targetBitrate)
        assertEquals(
            listOf("-c:v", "h264_mediacodec", "-pix_fmt", "yuv420p", "-bf", "0"),
            maximum.arguments
        )
    }

    @Test
    fun encodingFor_libx264_usesCrfWithoutTargetBitrate() {
        val cpuEncoder = FfmpegVideoEncoder("libx264", "h264")
        val economic = cpuEncoder.encodingFor(FfmpegVideoQuality.ECONOMIC, "10000k")
        assertEquals(null, economic.targetBitrate)
        assertEquals(listOf("-c:v", "libx264", "-preset", "ultrafast", "-crf", "26"), economic.arguments)

        val high = cpuEncoder.encodingFor(FfmpegVideoQuality.HIGH, "10000k")
        assertEquals(null, high.targetBitrate)
        assertEquals(listOf("-c:v", "libx264", "-preset", "ultrafast", "-crf", "20"), high.arguments)
    }

    @Test
    fun mediaCodecGopSize_usesRoundedSourceFrameRateWithSafeFallback() {
        assertEquals(24, mediaCodecGopSize(23.976))
        assertEquals(30, mediaCodecGopSize(29.97))
        assertEquals(60, mediaCodecGopSize(59.94))
        assertEquals(30, mediaCodecGopSize(null))
        assertEquals(30, mediaCodecGopSize(0.0))
    }

    @Test
    fun ffmpegFailureDetails_keepsRelevantLinesBeyondLogTail() {
        val logs = buildString {
            appendLine("MediaCodec.configure failed with status -22")
            repeat(20) { appendLine("cleanup line $it") }
            appendLine("Conversion failed!")
        }

        val details = ffmpegFailureDetails(logs)

        assertTrue(details.contains("MediaCodec.configure failed with status -22"))
        assertTrue(details.contains("Conversion failed!"))
    }
}
