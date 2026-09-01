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
    fun realEndTrimIsNeverDiscardedByTolerance() {
        assertEquals(9_900L, FfmpegMediaPolicies.requestedTrimDurationMs(0L, 9_900L))
        assertFalse(FfmpegMediaPolicies.audioSelectionCanUseStreamCopy(0L, 9_900L, 10_000L))
        assertTrue(FfmpegMediaPolicies.audioSelectionCanUseStreamCopy(0L, 10_000L, 10_000L))
        assertFalse(FfmpegMediaPolicies.audioSelectionCanUseStreamCopy(0L, 9_900L, 0L))
    }

    @Test
    fun hybridFallbackAndAudioJoinDecisionsAreExplicit() {
        assertEquals(
            "Não há keyframes internos suficientes para copiar o trecho central sem perdas.",
            FfmpegMediaPolicies.hybridCutFallbackReason("h264", "h264", hasInternalKeyframes = false)
        )
        assertEquals(
            "O encoder escolhido não corresponde ao codec da origem.",
            FfmpegMediaPolicies.hybridCutFallbackReason("hevc", "h264")
        )
        assertNull(FfmpegMediaPolicies.hybridCutFallbackReason("h264", "h264", hasInternalKeyframes = true))

        assertEquals(
            FfmpegAudioJoinPlan(requiresReencode = false, standardizeLosslessly = false),
            FfmpegMediaPolicies.audioJoinPlan(false, directCopyCompatible = true, selectedTrackReduction = false)
        )
        assertEquals(
            FfmpegAudioJoinPlan(requiresReencode = true, standardizeLosslessly = true),
            FfmpegMediaPolicies.audioJoinPlan(false, directCopyCompatible = false, selectedTrackReduction = false)
        )
        assertEquals(
            FfmpegAudioJoinPlan(requiresReencode = true, standardizeLosslessly = false),
            FfmpegMediaPolicies.audioJoinPlan(true, directCopyCompatible = true, selectedTrackReduction = false)
        )
    }

    @Test
    fun losslessNormalizationPreservesEqualMultitrackTopology() {
        val automatic = FfmpegMediaPolicies.audioJoinPlan(
            requestedReencode = false,
            directCopyCompatible = false,
            selectedTrackReduction = false
        )
        assertEquals(2, FfmpegMediaPolicies.normalizedAudioTrackCount(listOf(2, 2), automatic, false))
        assertEquals("mka", FfmpegMediaPolicies.losslessAudioStandardizationExtension(2))
        assertEquals("flac", FfmpegMediaPolicies.losslessAudioStandardizationEncoder("mka"))

        assertEquals(1, FfmpegMediaPolicies.normalizedAudioTrackCount(listOf(2, 1), automatic, true))
        assertEquals("wav", FfmpegMediaPolicies.losslessAudioStandardizationExtension(1))
        assertEquals("pcm_s24le", FfmpegMediaPolicies.losslessAudioStandardizationEncoder("wav", "pcm_s24le"))
    }

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
                "-map", "0", "-map_metadata", "0", "-map_chapters", "0",
                "-c", "copy", "-t", "1.000", "probe.mov"
            ),
            FfmpegMediaPolicies.metadataCopyPreflightArguments("in.mov", "probe.mov", 90, 90).toList()
        )
    }

    @Test
    fun audioSpecifiersSeparateOptionalMapsFromStrictFilterInputs() {
        assertEquals("0:a:0?", FfmpegMediaPolicies.audioMapSpecifier(0, 0))
        assertEquals("2:a:3", FfmpegMediaPolicies.audioMapSpecifier(2, 3, optional = false))
        assertEquals("2:a:3", FfmpegMediaPolicies.audioFilterInputSpecifier(2, 3))
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
                "-vn", "-map", "0:a:1?", "-map_metadata", "0",
                "-ar", "48000", "-ac", "2", "-c:a", "libmp3lame", "-b:a", "160k",
                "-avoid_negative_ts", "make_zero", "out.mp3"
            ),
            FfmpegMediaPolicies.extractAudioCommandArguments(
                "in.mkv", "out.mp3", "2.000", "3.000", "0:a:1?", false,
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

        val multitrack = FfmpegMediaPolicies.joinAudioCommandArguments(
            listOf("a.mkv", "b.mkv"),
            "out.mkv",
            "[0:a:0][1:a:0]concat=n=2:v=0:a=1[aout0];[0:a:1][1:a:1]concat=n=2:v=0:a=1[aout1]",
            "aac",
            48000,
            2,
            "192k",
            outputLabels = listOf("aout0", "aout1")
        ).toList()
        assertTrue(multitrack.windowed(2).contains(listOf("-map", "[aout0]")))
        assertTrue(multitrack.windowed(2).contains(listOf("-map", "[aout1]")))
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
                "-y", "-i", "in.wav", "-vn", "-map", "0:a:0?", "-af", "highpass=f=80",
                "-c:a", "pcm_s32le", "-ar", "96000", "-ac", "6",
                "-avoid_negative_ts", "make_zero", "-f", "wav", "out.wav"
            ),
            FfmpegMediaPolicies.cleanAudioCommandArguments(
                "in.wav", "out.wav", "0:a:0?", "highpass=f=80", "pcm_s32le", 96000, 6
            ).toList()
        )
    }

    @Test
    fun filterGraphFragmentsKeepArityLabelsAndOrder() {
        assertEquals(
            "[a0][a1][a2]concat=n=3:v=0:a=1[aout]",
            FfmpegMediaPolicies.audioConcatFilter(listOf("a0", "a1", "a2"))
        )
        assertEquals(
            "[v0][a0][v1][a1]concat=n=2:v=1:a=1[vout][aout]",
            FfmpegMediaPolicies.videoAudioConcatFilter(2)
        )
        assertEquals(
            "[v0][v1][v2]concat=n=3:v=1:a=0[vout]",
            FfmpegMediaPolicies.videoConcatFilter(listOf("v0", "v1", "v2"))
        )
        assertEquals(
            listOf(
                "[a0][a1]acrossfade=d=0.500:c1=tri:c2=tri[ax1]",
                "[ax1][a2]acrossfade=d=0.500:c1=tri:c2=tri[aout]"
            ),
            FfmpegMediaPolicies.audioCrossfadeChain(listOf("a0", "a1", "a2"), "0.500", "tri")
        )
    }

    @Test
    fun completeAudioJoinGraphPreservesEveryTrack() {
        val graph = FfmpegMediaPolicies.audioJoinFilterComplex(
            inputs = listOf(
                FfmpegAudioJoinFilterInput(2.0, listOf("0:a:0", "0:a:1")),
                FfmpegAudioJoinFilterInput(3.0, listOf("1:a:0", "1:a:1"))
            ),
            normalizeFilter = "aresample=48000,aformat=sample_fmts=fltp:sample_rates=48000:channel_layouts=stereo",
            outputLabels = listOf("aout0", "aout1"),
            transitionSeconds = 0.0,
            fadeInOut = false,
            crossfadeCurve = null
        )
        assertTrue(graph.contains("[0:a:0]"))
        assertTrue(graph.contains("[0:a:1]"))
        assertTrue(graph.contains("[1:a:0]"))
        assertTrue(graph.contains("[1:a:1]"))
        assertTrue(graph.contains("[a0_0][a0_1]concat=n=2:v=0:a=1[aout0]"))
        assertTrue(graph.contains("[a1_0][a1_1]concat=n=2:v=0:a=1[aout1]"))
    }

    @Test
    fun completeVideoJoinGraphCoversTransitionSilenceAndOutputs() {
        val graph = FfmpegMediaPolicies.videoJoinFilterComplex(
            inputs = listOf(
                FfmpegVideoJoinFilterInput(2.0, true, listOf("0:a:0")),
                FfmpegVideoJoinFilterInput(3.0, false, emptyList())
            ),
            videoFilter = "scale=320:240,setsar=1,fps=25,format=yuv420p",
            sampleRate = 48000,
            audioLayout = "stereo",
            outputAudioLabels = listOf("aout"),
            transitionSeconds = 0.5,
            fadeInOut = false,
            xfadeTransition = "wipeleft"
        )
        assertTrue(graph.contains("anullsrc=channel_layout=stereo:sample_rate=48000,atrim=0:3.000"))
        assertTrue(graph.contains("[v0][v1]xfade=transition=wipeleft:duration=0.500:offset=1.500[vx1]"))
        assertTrue(graph.contains("[vx1]copy[vout]"))
        assertTrue(graph.contains("[a0][a1]acrossfade=d=0.500:c1=tri:c2=tri[aout]"))
    }

    @Test
    fun completeInsertGraphHandlesMiddleAndBoundaryInsertion() {
        val normalize = "aresample=48000,aformat=sample_fmts=fltp:sample_rates=48000:channel_layouts=stereo"
        val middle = FfmpegMediaPolicies.insertAudioFilterComplex(
            "0:a:1", "1:a:0", 10.0, 2.0, 4.0, normalize, 0.5,
            fadeInOut = true, crossfadeCurve = null
        )
        assertTrue(middle.contains("[0:a:1]atrim=start=0:end=4.000"))
        assertTrue(middle.contains("[1:a:0]atrim=start=0:end=2.000"))
        assertTrue(middle.contains("[0:a:1]atrim=start=4.000:end=10.000"))
        assertTrue(middle.endsWith("[a0][a1][a2]concat=n=3:v=0:a=1[aout]"))

        val atStart = FfmpegMediaPolicies.insertAudioFilterComplex(
            "0:a:0", "1:a:0", 10.0, 2.0, 0.0, normalize, 0.5,
            fadeInOut = false, crossfadeCurve = "tri"
        )
        assertFalse(atStart.contains("atrim=start=0:end=0.000"))
        assertTrue(atStart.contains("[a1][a2]acrossfade=d=0.500:c1=tri:c2=tri[aout]"))

        val atEnd = FfmpegMediaPolicies.insertAudioFilterComplex(
            "0:a:0", "1:a:0", 10.0, 2.0, 10.0, normalize, 0.0,
            fadeInOut = false, crossfadeCurve = null
        )
        assertFalse(atEnd.contains("atrim=start=10.000:end=10.000"))
        assertTrue(atEnd.endsWith("[a0][a1]concat=n=2:v=0:a=1[aout]"))
    }

    @Test
    fun probedContainerDoesNotDependOnFilenameExtension() {
        assertEquals("matroska", FfmpegMediaPolicies.containerFamilyFromProbe("matroska,webm"))
        assertEquals("mov", FfmpegMediaPolicies.containerFamilyFromProbe("mov,mp4,m4a,3gp,3g2,mj2"))
        assertEquals("unknown", FfmpegMediaPolicies.containerFamilyFromProbe("mystery"))
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
        assertTrue(args.windowed(2).contains(listOf("-map", "0:t?")))
        assertTrue(args.windowed(2).contains(listOf("-c:t", "copy")))
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
