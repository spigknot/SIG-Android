package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class FfmpegVideoQualityTest {

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
        assertEquals(listOf("-c:v", "h264_mediacodec"), economic.arguments)

        val maximum = h264Hardware.encodingFor(FfmpegVideoQuality.MAXIMUM, "10000k")
        assertEquals("16000K", maximum.targetBitrate)
        assertEquals(listOf("-c:v", "h264_mediacodec"), maximum.arguments)
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
}
