package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
    fun uniqueOutputNameInsertsSuffixBeforeExtension() {
        assertEquals("audio.wav", FfmpegMediaPolicies.uniqueOutputName("audio.wav") { false })
        val existing = mutableSetOf("audio.wav")
        assertEquals("audio (1).wav", FfmpegMediaPolicies.uniqueOutputName("audio.wav") { it in existing })
        existing += "audio (1).wav"
        assertEquals("audio (2).wav", FfmpegMediaPolicies.uniqueOutputName("audio.wav") { it in existing })
    }

    @Test
    fun uniqueOutputNameHandlesNameWithoutExtension() {
        assertEquals("audio (1)", FfmpegMediaPolicies.uniqueOutputName("audio") { it == "audio" })
        assertEquals("meu.video.2026 (1).mp4", FfmpegMediaPolicies.uniqueOutputName("meu.video.2026.mp4") { it == "meu.video.2026.mp4" })
    }

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
        val ssIndex = args.indexOf("-ss")
        assertTrue(ssIndex < args.indexOf("-i"))
        assertEquals("8.333333", args[ssIndex + 1])
        assertEquals("1.666666", args[args.indexOf("-t") + 1])
        assertTrue(args.windowed(2).contains(listOf("-c:v", "copy")))
        // F5: o trecho é SÓ VÍDEO (o áudio do SmartCut vem de uma passagem única
        // sobre a fonte, no mux final). Legenda/dados também não entram: não
        // existem em MPEG-TS e derrubariam a conversão do trecho.
        assertTrue(args.windowed(2).contains(listOf("-map", "0:v:0")))
        assertFalse(args.windowed(2).contains(listOf("-map", "0:a?")))
        assertTrue(args.contains("-an"))
        assertFalse(args.windowed(2).contains(listOf("-map", "0")))
        assertTrue(args.windowed(2).contains(listOf("-f", "mpegts")))
        // Os parâmetros do codec viajam no início de cada trecho.
        assertTrue(args.windowed(2).contains(listOf("-mpegts_flags", "+resend_headers+initial_discontinuity")))
        assertTrue(args.windowed(2).contains(listOf("-muxdelay", "0")))
        assertTrue(args.windowed(2).contains(listOf("-muxpreload", "0")))
    }

    @Test
    fun smartCutSegmentLevaOBitstreamFilterDoCodec() {
        val h264 = FfmpegMediaPolicies.hybridSegmentArguments(
            "in.mp4", "out.ts", 1_400_000L, 0.6, "h264", reencode = true, hasAudio = true,
            videoArguments = listOf("-c:v", "h264_mediacodec"), audioArguments = listOf("-c:a", "aac", "-b:a", "128k")
        ).toList()
        assertTrue(h264.windowed(2).contains(listOf("-bsf:v", "h264_mp4toannexb")))
        assertEquals("h264_mp4toannexb", FfmpegMediaPolicies.tsBitstreamFilter("h264"))
        assertEquals("hevc_mp4toannexb", FfmpegMediaPolicies.tsBitstreamFilter("hevc"))
        assertNull(FfmpegMediaPolicies.tsBitstreamFilter("vp9"))
        // A borda reencodada espelha o fps do miolo copiado.
        val withFps = FfmpegMediaPolicies.hybridSegmentArguments(
            "in.mp4", "out.ts", 0L, 0.5, "hevc", reencode = true, hasAudio = false,
            videoArguments = listOf("-c:v", "hevc_mediacodec"), frameRate = 30000.0 / 1001.0
        ).toList()
        assertTrue(withFps.windowed(2).contains(listOf("-bsf:v", "hevc_mp4toannexb")))
        assertEquals("29.970030", withFps[withFps.indexOf("-r") + 1])
        assertTrue(withFps.contains("-an"))
    }

    @Test
    fun smartCutConcatColaOvideoETrazOaudioContinuoDaFonte() {
        // F5: o mux final cola o vídeo dos trechos (entrada 1) e puxa o áudio da
        // FONTE (entrada 0, com seek no início pedido) — uma passagem só, sem
        // emenda de áudio (antes o áudio vinha dos trechos e acumulava atraso).
        val args = FfmpegMediaPolicies.hybridConcatArguments(
            listPath = "lista.txt", outputPath = "saida.mkv", rotationDegrees = 0,
            hasAudio = true, sourcePath = "fonte.mp4", startUs = 1_400_000L, hevc = true,
            audioArguments = listOf("-c:a:0", "aac", "-b:a:0", "96k")
        ).toList()
        assertTrue(args.windowed(2).contains(listOf("-c:v", "copy")))
        // o áudio vem da fonte, no início pedido
        assertEquals("1.400000", args[args.indexOf("-ss") + 1])
        assertEquals("fonte.mp4", args[args.indexOf("-ss") + 3])
        assertTrue(args.windowed(2).contains(listOf("-map", "1:v:0")))
        assertTrue(args.windowed(2).contains(listOf("-map", "0:a?")))
        assertTrue(args.windowed(2).contains(listOf("-c:a:0", "aac")))
        assertFalse(args.contains("-bsf:a"))
        assertTrue(args.windowed(2).contains(listOf("-tag:v", "hvc1")))
        assertTrue(args.windowed(2).contains(listOf("-max_interleave_delta", "0")))
        // A rotação devolvida no mux final e o inventário do concat
        assertTrue(args.windowed(2).contains(listOf("-display_rotation:v:0", "0")))
        assertTrue(args.windowed(2).contains(listOf("-f", "concat")))
        // o vídeo vem da ENTRADA 1 (o concat); a 0 é a fonte, só para o áudio
        assertFalse(args.windowed(2).contains(listOf("-map", "0:v:0")))
    }

    @Test
    fun modoSemReencodeCopiaStreamsComSeekAntesDoInput() {
        val args = FfmpegMediaPolicies.cutCopyCommandArguments(
            "in.mp4", "out.mkv", "1.400", "3.200", listOf("-display_rotation:v:0", "0")
        ).toList()
        assertTrue(args.indexOf("-ss") < args.indexOf("-i"))
        assertTrue(args.windowed(2).contains(listOf("-c", "copy")))
        assertTrue(args.windowed(2).contains(listOf("-avoid_negative_ts", "make_zero")))
        assertTrue(args.windowed(2).contains(listOf("-display_rotation:v:0", "0")))
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
        assertEquals(
            "ffmpeg -filter_complex \"a b\" -c copy",
            FfmpegMediaPolicies.formatCommand(listOf("-filter_complex", "a b", "-c", "copy"))
        )
    }

    @Test
    fun commandFormatterHidesDirectoriesAndKeepsExtensions() {
        assertEquals(
            "ffmpeg -y -i input.mp4 -map 0:v -i input2.wav -c copy output.mkv",
            FfmpegMediaPolicies.formatCommand(
                listOf(
                    "-y",
                    "-i", "/data/user/0/br.gov.sp.pcsp.launcher/cache/source video.MP4",
                    "-map", "0:v",
                    "-i", "C:\\temp\\audio.wav",
                    "-c", "copy",
                    "/data/user/0/br.gov.sp.pcsp.launcher/cache/result final.MKV"
                )
            )
        )
    }

    @Test
    fun commandFormatterNumbersMultipleInputsInOrder() {
        assertEquals(
            "ffmpeg -y -i input.mp4 -i input2.mkv -i input3.mov -map 0:v output.mp4",
            FfmpegMediaPolicies.formatCommand(
                listOf(
                    "-y",
                    "-i", "/cache/first.MP4",
                    "-i", "/cache/second.MKV",
                    "-i", "C:\\temp\\third.MOV",
                    "-map", "0:v",
                    "/cache/joined.MP4"
                )
            )
        )
    }

    @Test
    fun commandFormatterNumbersMultipleOutputsInOrder() {
        assertEquals(
            "ffmpeg -i input.mp4 -map 0:v output.mp4 -map 0:a output2.m4a",
            FfmpegMediaPolicies.formatCommand(
                listOf(
                    "-i", "/cache/source.mp4",
                    "-map", "0:v", "/cache/video.mp4",
                    "-map", "0:a", "/cache/audio.m4a"
                )
            )
        )
    }

    @Test
    fun commandFormatterDoesNotInventOutputForProbeWithOnlyInput() {
        assertEquals(
            "ffmpeg -hide_banner -i input.mp4",
            FfmpegMediaPolicies.formatCommand(
                listOf("-hide_banner", "-i", "/cache/probe.mp4")
            )
        )
    }

    @Test
    fun commandPreviewSeparatesCommandsWithBlankLine() {
        assertEquals(
            "ffmpeg -i input.mp4 output.mkv\n\nffmpeg -i input.wav output.m4a",
            FfmpegCommandPresenter.formatPreview(
                listOf(
                    FfmpegCommandPresenter.PreviewCommand(listOf("-i", "/cache/a.mp4", "/cache/a.mkv")),
                    FfmpegCommandPresenter.PreviewCommand(listOf("-i", "/cache/b.wav", "/cache/b.m4a"))
                )
            )
        )
    }

    @Test
    fun commandPreviewShowsParallelRepetitionOnlyOnce() {
        assertEquals(
            "Repetições: 6×\nffmpeg -i input.mkv -vf transpose=1 output.mp4",
            FfmpegCommandPresenter.formatPreview(
                listOf(
                    FfmpegCommandPresenter.PreviewCommand(
                        listOf("-i", "/cache/part.mkv", "-vf", "transpose=1", "/cache/rotated.mp4"),
                        repetitions = 6
                    )
                )
            )
        )
    }

    @Test
    fun preciseAudioReencodesEachTrackWithItsOwnProfile() {
        // T01/T02: copiar o audio no corte preciso deixava 0,44 s de som anterior
        // ao inicio pedido; reencodar por faixa resolve sem impor o perfil da
        // primeira faixa as demais.
        val tracks = listOf(
            FfmpegMediaPolicies.AudioTrackProfile(index = 0, bitrate = "64k", sampleRate = 44100, channels = 1),
            FfmpegMediaPolicies.AudioTrackProfile(index = 1, bitrate = "128k", sampleRate = 48000, channels = 2)
        )

        assertEquals(
            listOf(
                "-c:a:0", "aac", "-b:a:0", "64k", "-ar:a:0", "44100", "-ac:a:0", "1",
                "-c:a:1", "aac", "-b:a:1", "128k", "-ar:a:1", "48000", "-ac:a:1", "2"
            ),
            FfmpegMediaPolicies.preciseAudioTrackArguments(tracks)
        )
    }

    @Test
    fun preciseAudioWithoutInventoryStillReencodesInAac() {
        assertEquals(
            listOf("-c:a", "aac"),
            FfmpegMediaPolicies.preciseAudioTrackArguments(emptyList())
        )
    }

    @Test
    fun preciseAudioOmitsUnknownFieldsAndDefaultsBitrate() {
        assertEquals(
            listOf("-c:a:0", "aac", "-b:a:0", "128k"),
            FfmpegMediaPolicies.preciseAudioTrackArguments(
                listOf(FfmpegMediaPolicies.AudioTrackProfile(0, null, null, null))
            )
        )
    }

    @Test
    fun audioTracksSummaryDescribesEachTrack() {
        assertEquals(
            "AAC por faixa (2 faixas: 64k 44.1 kHz mono • 128k 48.0 kHz estéreo)",
            FfmpegMediaPolicies.audioTracksSummary(
                listOf(
                    FfmpegMediaPolicies.AudioTrackProfile(0, "64k", 44100, 1),
                    FfmpegMediaPolicies.AudioTrackProfile(1, "128k", 48000, 2)
                )
            )
        )
        assertEquals("AAC (faixa unica)", FfmpegMediaPolicies.audioTracksSummary(emptyList()))
    }

    @Test
    fun copyIntervalMessageDeclaraOsLimitesEfetivos() {
        // F6/T01: copiar streams nao corta em qualquer ponto — o FFmpeg recua ao
        // keyframe. O modo promete copia fiel; o intervalo efetivo tem que aparecer.
        assertEquals(
            "Sem Reencode: intervalo efetivo 1.000–4.600 s (3.600 s); pedido " +
                "1.400–4.600 s (3.200 s) — o início recua 0.400 s até o keyframe anterior.",
            FfmpegMediaPolicies.copyIntervalMessage(1400L, 4600L, 1000L)
        )
    }

    @Test
    fun copyIntervalMessageQuandoOInicioJaEUmKeyframe() {
        assertEquals(
            "Sem Reencode: intervalo efetivo 2.000–4.600 s (2.600 s) — igual ao pedido.",
            FfmpegMediaPolicies.copyIntervalMessage(2000L, 4600L, 2000L)
        )
    }

    @Test
    fun colorDepthWarningAcusaFonteComMaisDe8Bits() {
        // F10: reencodar 10/12 bits para yuv420p reduz a profundidade de cor, e
        // sem aviso isso acontece em silencio (o Sem Reencode preserva).
        assertNull(FfmpegMediaPolicies.colorDepthWarning("yuv420p"))
        assertNull(FfmpegMediaPolicies.colorDepthWarning("yuv422p"))
        assertNull(FfmpegMediaPolicies.colorDepthWarning(""))
        assertNull(FfmpegMediaPolicies.colorDepthWarning(null))

        for (formato in listOf("yuv420p10le", "p010le", "gbrp12le", "yuv420p16le")) {
            val aviso = FfmpegMediaPolicies.colorDepthWarning(formato)
            assertNotNull("esperava aviso para $formato", aviso)
            assertTrue("o aviso cita a profundidade e o formato de saida", aviso!!.contains("8 bits"))
            assertTrue(aviso.contains(formato))
        }
    }

    @Test
    fun cleanFiltersSaoOsMesmosDoWindows() {
        // F9: as MESMAS tecnologias nos dois apps. Antes o "forte" era anlmdn no
        // Android e afftdn agressivo no Windows — mesmo rótulo, efeitos diferentes.
        assertEquals("afftdn=nf=-25", FfmpegMediaPolicies.CLEAN_FILTER_BALANCED)
        assertEquals("afftdn=nr=18:nf=-35:tn=1", FfmpegMediaPolicies.CLEAN_FILTER_STRONG)
    }

    @Test
    fun smartInsertMontaAsPecasComOCorpoCopiado() {
        // F-smart: o corpo do principal vai COPIADO (é o que preserva o áudio);
        // só o inserido é reencodado, com o fade das curvas escolhidas.
        val esquerda = FfmpegMediaPolicies.insertSmartLeftArguments("principal.wav", "000.wav", 5.0).toList()
        assertTrue(esquerda.windowed(2).contains(listOf("-c", "copy")))
        assertEquals("5.000000", esquerda[esquerda.indexOf("-t") + 1])

        val direita = FfmpegMediaPolicies.insertSmartRightArguments("principal.wav", "002.wav", 5.0).toList()
        assertEquals("5.000000", direita[direita.indexOf("-ss") + 1])
        assertTrue(direita.windowed(2).contains(listOf("-c", "copy")))

        val meio = FfmpegMediaPolicies
            .insertSmartMiddleArguments("inserido.wav", "001.wav", 2.0, 48000, 2, "pcm_s16le", null, 0.2, "tri")
            .toList()
        assertTrue(meio.windowed(2).contains(listOf("-c:a", "pcm_s16le")))
        val af = meio[meio.indexOf("-af") + 1]
        assertTrue("fade de entrada no começo do inserido", af.contains("afade=t=in:st=0:d=0.200000:curve=tri"))
        assertTrue("fade de saída em (duração - fade)", af.contains("afade=t=out:st=1.800000:d=0.200000:curve=tri"))

        val concat = FfmpegMediaPolicies.insertSmartConcatArguments("lista.txt", "saida.wav").toList()
        assertTrue(concat.windowed(2).contains(listOf("-f", "concat")))
        // as peças já estão no formato final: o concat COPIA (sem segunda geração)
        assertTrue(concat.windowed(2).contains(listOf("-c:a", "copy")))
    }

    @Test
    fun smartInsertReencodaOInseridoNoCodecDoPrincipal() {
        // Fonte m4a/AAC: o trecho inserido nasce em AAC (não em PCM) para o concat
        // poder copiar; o bitrate do perfil é aplicado.
        val meio = FfmpegMediaPolicies
            .insertSmartMiddleArguments("inserido.m4a", "001.m4a", 2.0, 48000, 2, "aac", "128k", 0.2, "tri")
            .toList()
        assertTrue(meio.windowed(2).contains(listOf("-c:a", "aac")))
        assertTrue(meio.windowed(2).contains(listOf("-b:a", "128k")))
        // codec sem bitrate configurável (FLAC) não recebe -b:a
        val flac = FfmpegMediaPolicies
            .insertSmartMiddleArguments("x.flac", "001.flac", 2.0, 48000, 2, "flac", "128k", 0.0, null)
            .toList()
        assertFalse(flac.contains("-b:a"))
    }

    @Test
    fun smartInsertRecusaCodecQueOAppNaoSabeReencodar() {
        // Fora de PCM não há como copiar o corpo para uma saída WAV — o app cai
        // no modo preciso (com aviso), como o próprio Smart Insert do Windows.
        assertTrue(FfmpegMediaPolicies.insertSmartCanPreserveCodec("pcm_s16le"))
        assertTrue(FfmpegMediaPolicies.insertSmartCanPreserveCodec("PCM_F32LE"))
        // O Android entrega o subtipo do MIME (audio/raw) para PCM.
        assertTrue(FfmpegMediaPolicies.insertSmartCanPreserveCodec("raw"))
        assertTrue(FfmpegMediaPolicies.insertSmartCanPreserveCodec("WAV"))
        // "Disponível para tudo": os comprimidos que o app sabe reencodar também
        // preservam o corpo copiado (a saída usa o contêiner da fonte).
        assertTrue(FfmpegMediaPolicies.insertSmartCanPreserveCodec("aac"))
        assertTrue(FfmpegMediaPolicies.insertSmartCanPreserveCodec("mp3"))
        // Medido no aparelho: um m4a chega como "mp4a-latm" (MIME do Android).
        assertTrue(FfmpegMediaPolicies.insertSmartCanPreserveCodec("mp4a-latm"))
        assertTrue(FfmpegMediaPolicies.insertSmartCanPreserveCodec("mp4a"))
        assertTrue(FfmpegMediaPolicies.insertSmartCanPreserveCodec("opus"))
        assertTrue(FfmpegMediaPolicies.insertSmartCanPreserveCodec("vorbis"))
        assertTrue(FfmpegMediaPolicies.insertSmartCanPreserveCodec("flac"))
        assertFalse(FfmpegMediaPolicies.insertSmartCanPreserveCodec("ac3"))
        assertFalse(FfmpegMediaPolicies.insertSmartCanPreserveCodec(null))
    }

    @Test
    fun variableRateWarningAcusaFonteComTaxaVariavel() {
        // T12: no banner, "18.71 fps, 25 tbr" = taxa variável (a média difere do
        // nominal). Reencodar fixa a taxa — e não pode ser silencioso.
        val aviso = FfmpegMediaPolicies.variableRateWarning("18.71", "25")
        assertNotNull(aviso)
        assertTrue(aviso!!.contains("variável"))

        assertNull(FfmpegMediaPolicies.variableRateWarning("25", "25"))
        assertNull(FfmpegMediaPolicies.variableRateWarning("29.97", "29.97"))
        assertNull(FfmpegMediaPolicies.variableRateWarning("29.6", "30"))  // dentro de 2%
        assertNull(FfmpegMediaPolicies.variableRateWarning("", "25"))
        assertNull(FfmpegMediaPolicies.variableRateWarning(null, null))
    }

    @Test
    fun audioOffsetWarningComparaComOInicioDoConteiner() {
        // T12: PTS inicial deslocado tem os DOIS streams juntos (não é offset de
        // A/V); o offset que importa é o áudio em relação ao início do contêiner.
        assertNull(FfmpegMediaPolicies.audioOffsetWarning(4.976, 4.976))
        assertNotNull(FfmpegMediaPolicies.audioOffsetWarning(0.176, 0.0))
        assertTrue(FfmpegMediaPolicies.audioOffsetWarning(0.176, 0.0)!!.contains("176 ms"))
        assertNull(FfmpegMediaPolicies.audioOffsetWarning(0.021, 0.0))
        assertNull(FfmpegMediaPolicies.audioOffsetWarning(null, 0.0))
    }
}
