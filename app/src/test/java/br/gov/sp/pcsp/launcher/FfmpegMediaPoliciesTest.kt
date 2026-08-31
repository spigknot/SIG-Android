package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FfmpegMediaPoliciesTest {
    private fun streamSignature(
        container: String = "mov",
        descriptor: String = "video:h264 (high) (avc1), yuv420p, 1920x1080, 30 fps, 15360 tbn",
        channelMask: Int? = null
    ) = FfmpegStreamCopySignature(
        containerFamily = container,
        ffmpegDescriptor = descriptor,
        mime = "video/avc",
        profile = 8,
        level = 256,
        sampleRate = null,
        channels = null,
        channelMask = channelMask,
        pcmEncoding = null,
        width = 1920,
        height = 1080,
        frameRate = 30.0,
        colorStandard = 1,
        colorTransfer = 3,
        colorRange = 2,
        codecTag = "avc1",
        sampleFormat = null,
        channelLayout = null,
        timeBase = "1/15360",
        csd0 = 123,
        csd1 = 456,
        csd2 = null
    )

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
        assertEquals(
            listOf(
                "-y", "-hide_banner", "-loglevel", "error", "-display_rotation:v:0", "0", "-i", "in.mov",
                "-map", "0", "-c", "copy", "-t", "0.001", "probe.mov"
            ),
            FfmpegMediaPolicies.metadataCopyPreflightArguments("in.mov", "probe.mov", 90, 90).toList()
        )
    }

    @Test
    fun selectedAudioTrackProducesExplicitFfmpegSpecifier() {
        assertEquals("0:a:0", FfmpegMediaPolicies.audioStreamSpecifier(0, 0))
        assertEquals("2:a:3", FfmpegMediaPolicies.audioStreamSpecifier(2, 3))
    }

    @Test
    fun cutAudioArgumentsPreservePcmDepthAndDeclareCommonContainers() {
        assertEquals(
            listOf("-c:a", "pcm_s24le"),
            FfmpegMediaPolicies.cutAudioEncoderArguments("wav", "192k", "pcm_s24le")
        )
        assertEquals(
            listOf("-c:a", "aac", "-b:a", "192k"),
            FfmpegMediaPolicies.cutAudioEncoderArguments("m4a", "192k", "pcm_s16le")
        )
        assertEquals(
            listOf(
                "-y", "-ss", "1.250", "-i", "in.wav", "-t", "2.500",
                "-map", "0:a?", "-map_metadata", "0", "-map_chapters", "0", "-vn",
                "-c:a", "pcm_s24le", "-avoid_negative_ts", "make_zero", "out.wav"
            ),
            FfmpegMediaPolicies.cutAudioCommandArguments(
                "in.wav", "out.wav", "1.250", "2.500", listOf("-c:a", "pcm_s24le")
            ).toList()
        )
    }

    @Test
    fun extractionUsesVbrCapableMp3AndAudioOpusProfile() {
        val mp3 = FfmpegMediaPolicies.extractAudioEncoderArguments("mp3", "160k", "pcm_s16le")
        assertEquals(listOf("-c:a", "libmp3lame", "-b:a", "160k"), mp3)
        assertFalse("-minrate" in mp3)
        val opus = FfmpegMediaPolicies.extractAudioEncoderArguments("opus", "96k", "pcm_s16le")
        assertTrue(opus.windowed(2).contains(listOf("-application", "audio")))
        assertTrue(opus.windowed(2).contains(listOf("-vbr", "on")))
        assertEquals(
            listOf(
                "-y", "-ss", "2.000", "-i", "in.mkv", "-t", "3.000",
                "-vn", "-map", "0:a:1", "-map_metadata", "0",
                "-ar", "48000", "-ac", "2", "-c:a", "libmp3lame", "-b:a", "160k",
                "-avoid_negative_ts", "make_zero", "out.mp3"
            ),
            FfmpegMediaPolicies.extractAudioCommandArguments(
                "in.mkv", "out.mp3", "2.000", "3.000", "0:a:1", false,
                48000, 2, mp3
            ).toList()
        )
    }

    @Test
    fun joinCommandsCoverDirectCopyAndFilteredAudio() {
        assertEquals(
            listOf(
                "-y", "-fflags", "+genpts", "-f", "concat", "-safe", "0", "-i", "list.txt",
                "-map", "0", "-map_metadata", "0", "-map_chapters", "0", "-c", "copy",
                "-avoid_negative_ts", "make_zero", "out.mkv"
            ),
            FfmpegMediaPolicies.directConcatCommandArguments("list.txt", "out.mkv").toList()
        )
        val filtered = FfmpegMediaPolicies.joinAudioCommandArguments(
            listOf("a.wav", "b.wav"), "out.wav", "[0:a][1:a]concat=n=2:v=0:a=1[aout]",
            "pcm_s24le", 48000, 2, null
        ).toList()
        assertTrue(filtered.windowed(2).contains(listOf("-filter_complex", "[0:a][1:a]concat=n=2:v=0:a=1[aout]")))
        assertTrue(filtered.windowed(2).contains(listOf("-c:a", "pcm_s24le")))
        assertFalse("-b:a" in filtered)
    }

    @Test
    fun insertCommandMapsFilteredOutputAndKeepsContainerOptions() {
        val args = FfmpegMediaPolicies.insertAudioCommandArguments(
            "main.m4a", "insert.m4a", "out.m4a", "[0:a][1:a]concat=n=2:v=0:a=1[aout]",
            "aac", 44100, 2, "192k", true
        ).toList()
        assertEquals(listOf("-y", "-i", "main.m4a", "-i", "insert.m4a"), args.take(5))
        assertTrue(args.windowed(2).contains(listOf("-map", "[aout]")))
        assertTrue(args.windowed(2).contains(listOf("-b:a", "192k")))
        assertTrue(args.windowed(2).contains(listOf("-movflags", "+faststart")))
    }

    @Test
    fun cleanCommandPreservesRequestedPcmProfile() {
        assertEquals(
            listOf(
                "-y", "-i", "in.wav", "-vn", "-map", "0:a:0", "-af", "highpass=f=80",
                "-c:a", "pcm_s32le", "-ar", "96000", "-ac", "6",
                "-avoid_negative_ts", "make_zero", "-f", "wav", "out.wav"
            ),
            FfmpegMediaPolicies.cleanAudioCommandArguments(
                "in.wav", "out.wav", "0:a:0", "highpass=f=80", "pcm_s32le", 96000, 6
            ).toList()
        )
    }

    @Test
    fun directConcatRequiresExactContainerAndFfmpegStreamContract() {
        val base = streamSignature()
        assertTrue(FfmpegMediaPolicies.directConcatSignaturesCompatible(listOf(listOf(base), listOf(base))))
        assertFalse(
            FfmpegMediaPolicies.directConcatSignaturesCompatible(
                listOf(listOf(base), listOf(base.copy(containerFamily = "matroska")))
            )
        )
        assertFalse(
            FfmpegMediaPolicies.directConcatSignaturesCompatible(
                listOf(listOf(base.copy(containerFamily = "unknown")), listOf(base.copy(containerFamily = "unknown")))
            )
        )
        assertFalse(
            FfmpegMediaPolicies.directConcatSignaturesCompatible(
                listOf(listOf(base), listOf(base.copy(ffmpegDescriptor = base.ffmpegDescriptor.replace("15360 tbn", "90000 tbn"))))
            )
        )
        assertFalse(FfmpegMediaPolicies.directConcatSignaturesCompatible(listOf(listOf(base), null)))
    }

    @Test
    fun hybridBodyKeepsMicrosecondPrecisionAndSeeksBeforeInput() {
        val args = FfmpegMediaPolicies.hybridCopyBodyArguments("in.mp4", "out.mkv", 8_333_333L, 9_999_999L).toList()
        assertEquals("8.333333", args[2])
        assertTrue(args.indexOf("-ss") < args.indexOf("-i"))
        assertEquals("1.666666", args[args.indexOf("-t") + 1])
        assertTrue(args.windowed(2).contains(listOf("-c", "copy")))
    }

    @Test
    fun audioNormalizationResetsPtsBeforeFade() {
        val filter = FfmpegMediaPolicies.normalizedAudioFilter(
            "1:a:2",
            "aresample=48000",
            listOf("afade=t=in:st=0:d=0.5"),
            "a1"
        )
        assertTrue(filter.indexOf("asetpts=PTS-STARTPTS") < filter.indexOf("afade="))
        assertTrue(filter.startsWith("[1:a:2]"))
        assertTrue(filter.endsWith("[a1]"))
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
