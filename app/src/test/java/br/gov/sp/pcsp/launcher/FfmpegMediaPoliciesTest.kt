package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FfmpegMediaPoliciesTest {
    @Test
    fun metadataModeAlwaysUsesCopyCommandRegardlessOfPreviousUiState() {
        assertTrue(FfmpegMediaPolicies.usesMetadataCopyCommand(true))
        assertFalse(FfmpegMediaPolicies.usesMetadataCopyCommand(false))
        assertEquals(
            listOf("-y", "-display_rotation:v:0", "0", "-i", "in.mkv", "-map", "0", "-c", "copy", "out.mkv"),
            FfmpegMediaPolicies.metadataRotationCopyArguments("in.mkv", "out.mkv", 90, 90).toList()
        )
        assertEquals(
            "-90",
            FfmpegMediaPolicies.metadataRotationCopyArguments("in", "out", 0, 90)[2]
        )
    }

    @Test
    fun selectedAudioTrackProducesExplicitFfmpegSpecifier() {
        assertEquals("0:a:0", FfmpegMediaPolicies.audioStreamSpecifier(0, 0))
        assertEquals("2:a:3", FfmpegMediaPolicies.audioStreamSpecifier(2, 3))
    }
    @Test
    fun metadataRotationConvertsClockwiseUiToCounterClockwiseFfmpeg() {
        assertEquals(-90, FfmpegMediaPolicies.metadataRotationAfterClockwiseRequest(0, 90))
        assertEquals(180, FfmpegMediaPolicies.metadataRotationAfterClockwiseRequest(-90, 90))
        assertEquals(0, FfmpegMediaPolicies.metadataRotationAfterClockwiseRequest(90, 90))
        assertEquals(0, FfmpegMediaPolicies.metadataRotationAfterClockwiseRequest(180, 180))
    }

    @Test
    fun arbitraryRotationRoundsToNearestRightAngle() {
        assertEquals(90, FfmpegMediaPolicies.normalizeRightAngle(46))
        assertEquals(-90, FfmpegMediaPolicies.normalizeRightAngle(271))
        assertEquals(0, FfmpegMediaPolicies.normalizeRightAngle(359))
    }

    @Test
    fun physicalFiltersMaterializeDisplayRotation() {
        assertEquals(listOf("transpose=1"), FfmpegMediaPolicies.physicalRotationFilters(-90))
        assertEquals(listOf("transpose=2"), FfmpegMediaPolicies.physicalRotationFilters(90))
        assertEquals(listOf("hflip", "vflip"), FfmpegMediaPolicies.physicalRotationFilters(180))
    }

    @Test
    fun channelParserSupportsMultichannelLayouts() {
        assertEquals(1, FfmpegMediaPolicies.parseAudioChannelCount("Audio: pcm, 16000 Hz, mono"))
        assertEquals(2, FfmpegMediaPolicies.parseAudioChannelCount("Audio: aac, 48000 Hz, stereo"))
        assertEquals(6, FfmpegMediaPolicies.parseAudioChannelCount("Audio: aac, 48000 Hz, 5.1, fltp"))
        assertEquals(8, FfmpegMediaPolicies.parseAudioChannelCount("Audio: eac3, 48000 Hz, 7.1(side)"))
        assertEquals(12, FfmpegMediaPolicies.parseAudioChannelCount("Audio: pcm, 48000 Hz, 12 channels"))
    }

    @Test
    fun videoProfileParserDoesNotTreatCodecTagAsProfile() {
        assertEquals("High", FfmpegMediaPolicies.parseKnownVideoProfile("Video: h264 (High) (avc1 / 0x31637661)"))
        assertNull(FfmpegMediaPolicies.parseKnownVideoProfile("Video: h264 (avc1 / 0x31637661)"))
    }

    @Test
    fun commandFormatterQuotesArgumentsWithSpaces() {
        assertEquals("ffmpeg -i \"a b.mp4\" -c copy", FfmpegMediaPolicies.formatCommand(listOf("-i", "a b.mp4", "-c", "copy")))
    }
}
