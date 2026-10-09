package br.gov.sp.pcsp.launcher

import org.junit.Assert.*
import org.junit.Test

class SmartJoinTimingTest {
    @Test fun recoveryKeepsTheOriginalValidationRequirements() {
        val recovery = SmartJoinTiming.ResumeValidation(3369.075756, 100995, 89.0 / 3.0, .135, 1, 48000,
            listOf(2300.186644, 2305.242824, 3369.072736), 10235190787L)
        assertEquals(100995, recovery.frames)
        assertEquals(10235190787L, recovery.stageBytes)
        assertEquals(2, recovery.boundaries.dropLast(1).size)
    }

    @Test fun incompleteOrCorruptRecoveryIsRejected() {
        val recovery = SmartJoinTiming.ResumeValidation(6.0, 150, 25.0, .04, 1, 48000, listOf(3.0, 6.0), 1234)
        assertThrows(IllegalArgumentException::class.java) { recovery.copy(stageBytes = 0) }
        assertThrows(IllegalArgumentException::class.java) { recovery.copy(frames = 0) }
        assertThrows(IllegalArgumentException::class.java) { recovery.copy(boundaries = listOf(4.0, 3.0)) }
        assertThrows(IllegalArgumentException::class.java) { recovery.copy(boundaries = listOf(Double.NaN)) }
        assertThrows(IllegalArgumentException::class.java) { recovery.copy(boundaries = listOf(7.0)) }
        assertThrows(IllegalArgumentException::class.java) { recovery.copy(sourceGap = -1.0) }
    }
    @Test fun compactProbePreservesDiscardFlagsAndStreamClock() {
        val probe = """
            packet|flags=KD_|duration_time=0.04|dts_time=1.88|pts_time=1.96
            packet|pts_time=2.0|dts_time=1.92|duration_time=0.04|flags=K__
            packet|pts_time=2.04|dts_time=1.96|duration_time=0.04|flags=___
            stream|codec_type=video|r_frame_rate=25/1|start_time=2.0|duration=0.08|tag:handler_name=Ignore
            format|start_time=1.5|duration=99
        """.trimIndent()
        val parsed = SmartJoinTiming.readCompactProbe(probe.reader())
        assertEquals(3, parsed.packets.size)
        assertEquals(2, parsed.visiblePackets.size)
        assertEquals(listOf(0.0), parsed.keys)
        assertEquals(0.5, parsed.seekOffset, 1e-9)
        assertEquals(0.08, parsed.duration, 1e-9)
    }

    @Test fun compactProbeUsesPacketsWhenStreamDurationIsUnavailable() {
        val probe = "packet|pts_time=0|dts_time=0|duration_time=N/A|flags=K__\n" +
            "stream|codec_type=video|start_time=N/A|duration=N/A|r_frame_rate=25/1\nformat|start_time=N/A\n"
        val parsed = SmartJoinTiming.readCompactProbe(probe.reader())
        assertEquals(.04, parsed.duration, 1e-9)
        assertEquals(0.0, parsed.packets.single().duration, 0.0)
        assertThrows(IllegalStateException::class.java) { SmartJoinTiming.readCompactProbe("".reader()) }
    }

    @Test fun compactProbeReadsLongVideoFromIncrementalReader() {
        val count = 100995
        val reader = object : java.io.Reader() {
            var index = 0
            var pending = ""
            var offset = 0
            override fun read(buffer: CharArray, start: Int, length: Int): Int {
                if (length == 0) return 0
                if (offset == pending.length) {
                    if (index > count) return -1
                    pending = if (index == count) "stream|codec_type=video|r_frame_rate=1/1|start_time=0|duration=$count\nformat|start_time=0\n"
                        else "packet|pts_time=$index|dts_time=$index|duration_time=1|flags=${if (index % 60 == 0) "K__" else "___"}\n"
                    index++
                    offset = 0
                }
                val available = minOf(length, pending.length - offset)
                pending.toCharArray(buffer, start, offset, offset + available)
                offset += available
                return available
            }
            override fun close() = Unit
        }
        val parsed = reader.use(SmartJoinTiming::readCompactProbe)
        assertEquals(count, parsed.visiblePackets.size)
        assertEquals(count.toDouble(), parsed.duration, 0.0)
        assertEquals(1.0, parsed.maximumFrameGapSeconds, 0.0)
    }
    private fun video(times: List<Double>, rate: Double = 25.0) = SmartJoinTiming.Video(
        times.mapIndexed { i, pts -> SmartJoinTiming.Packet(pts, i / rate - 0.08, 1 / rate, i == 0) },
        0.0, 0.0, 6.0, rate.toString())

    @Test fun completeVideoPassesButMissingFramesWithCorrectHeaderFail() {
        val all = (0 until 150).map { it / 25.0 }
        SmartJoinTiming.validate(video(all), 6.0, 150, 25.0)
        assertThrows(IllegalStateException::class.java) {
            SmartJoinTiming.validate(video(all.filterIndexed { i, _ -> i !in 50..99 }), 6.0, 150, 25.0)
        }
    }
    @Test fun duplicatedFrameFailsEvenWithExpectedCountAndDuration() {
        val pts = (0 until 150).map { it / 25.0 }.toMutableList()
        pts[60] = pts[59]
        assertThrows(IllegalStateException::class.java) { SmartJoinTiming.validate(video(pts), 6.0, 150, 25.0) }
    }
    @Test fun cumulativeRoundingDoesNotAddFramePerBridge() {
        val frames = SmartJoinTiming.Frames(25.0)
        assertEquals(100, frames.next(4.0, 100))
        assertEquals(88, frames.next(3.5))
        assertEquals(87, frames.next(3.5))
        assertEquals(175, frames.total - 100)
    }
    @Test fun openCraLeavesLeadingFramesInOutgoingBridge() {
        val packets = listOf(
            SmartJoinTiming.Packet(0.0, -.08, .04, true),
            SmartJoinTiming.Packet(.04, -.04, .04, false),
            SmartJoinTiming.Packet(2.0, 1.88, .04, true),
            SmartJoinTiming.Packet(1.92, 1.92, .04, false),
            SmartJoinTiming.Packet(1.96, 1.96, .04, false),
            SmartJoinTiming.Packet(2.04, 2.0, .04, false))
        val video = SmartJoinTiming.Video(packets, 0.0, 0.0, 6.0, "25/1")
        assertEquals(1.92, video.safeEnds[2.0]!!, 1e-9)
        assertEquals(2, video.leading(2.0))
        assertEquals(.12, video.delay(2.0), 1e-9)
    }
    @Test fun rationalFrameRateKeepsPrecision() {
        assertEquals(30000.0 / 1001.0, SmartJoinTiming.fps("30000/1001"), 1e-10)
    }

    @Test fun durationValidationDoesNotHideLateVideoOrigin() {
        val original = video((0 until 150).map { it / 25.0 })
        val shifted = original.copy(origin = .3, packets = original.packets.map { it.copy(pts = it.pts + .3, dts = it.dts + .3) })
        assertThrows(IllegalStateException::class.java) { SmartJoinTiming.validate(shifted, 6.0, 150, 25.0) }
    }

    @Test fun copiedCameraBodyKeepsRealDurationEvenWithMoreFramesThanNominalRate() {
        val rate = 89.0 / 3.0
        val frames = SmartJoinTiming.Frames(rate)
        assertEquals(69313, frames.next(2300.186644, 69313))
        assertEquals(2300.186644, frames.lastDurationSeconds, 1e-9)
        assertEquals(150, frames.next(5.059200))
        assertEquals(150.0 / rate, frames.lastDurationSeconds, 1e-9)
        assertEquals(2300.186644 + 150.0 / rate, frames.durationSeconds, 1e-9)
        assertEquals(69463, frames.total)
    }

    @Test fun copiedSparseBodyDoesNotMakeBridgeFillItsOriginalMissingFrames() {
        val frames = SmartJoinTiming.Frames(25.0)
        assertEquals(50, frames.next(4.0, 50))
        assertEquals(4.0, frames.lastDurationSeconds, 1e-9)
        assertEquals(75, frames.next(3.0))
        assertEquals(7.0, frames.durationSeconds, 1e-9)
    }

    @Test fun copiedVfrBodyBetweenBridgesDoesNotResetAccumulatedRounding() {
        val frames = SmartJoinTiming.Frames(25.0)
        assertEquals(88, frames.next(3.5))
        assertEquals(61, frames.next(4.0, 61))
        assertEquals(87, frames.next(3.5))
        assertEquals(11.0, frames.durationSeconds, 1e-9)
    }

    @Test fun framesRejectInvalidRateDurationOrCopiedCount() {
        assertThrows(IllegalArgumentException::class.java) { SmartJoinTiming.Frames(Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { SmartJoinTiming.Frames(0.0) }
        val frames = SmartJoinTiming.Frames(25.0)
        assertThrows(IllegalArgumentException::class.java) { frames.next(Double.POSITIVE_INFINITY) }
        assertThrows(IllegalArgumentException::class.java) { frames.next(-1.0) }
        assertThrows(IllegalArgumentException::class.java) { frames.next(1.0, 0) }
    }

    @Test fun validationUsesActualLastPacketDurationForVfr() {
        val packets = listOf(
            SmartJoinTiming.Packet(0.0, -.08, .04, true),
            SmartJoinTiming.Packet(.04, -.04, .04, false),
            SmartJoinTiming.Packet(.08, 0.0, .20, false))
        val output = SmartJoinTiming.Video(packets, 0.0, 0.0, .28, "25/1")
        SmartJoinTiming.validate(output, .28, 3, 25.0)
        assertThrows(IllegalStateException::class.java) { SmartJoinTiming.validate(output, .40, 3, 25.0) }
    }

    @Test fun sourceVfrGapIsAllowedButNewGapAndMissingPacketsStillFail() {
        val output = video(listOf(0.0, .04, .08, 1.28, 1.32)).copy(duration = 1.36)
        assertEquals(1.2, output.maximumFrameGapSeconds, 1e-9)
        assertThrows(IllegalStateException::class.java) { SmartJoinTiming.validate(output, 1.36, 5, 25.0) }
        SmartJoinTiming.validate(output, 1.36, 5, 25.0, 1.2)
        assertThrows(IllegalStateException::class.java) { SmartJoinTiming.validate(output, 1.36, 6, 25.0, 1.2) }
        assertThrows(IllegalStateException::class.java) { SmartJoinTiming.validate(output, 1.36, 5, 25.0, .2) }
    }

    @Test fun discardedAndNegativePrerollNeverCountAsVisibleFrames() {
        val packets = listOf(
            SmartJoinTiming.Packet(-.08, -.16, .04, true, true),
            SmartJoinTiming.Packet(-.04, -.12, .04, false),
            SmartJoinTiming.Packet(0.0, -.08, .04, true),
            SmartJoinTiming.Packet(.04, -.04, .04, false),
            SmartJoinTiming.Packet(.08, 0.0, .04, false, true),
            SmartJoinTiming.Packet(.12, .04, .04, false))
        val output = SmartJoinTiming.Video(packets, 0.0, 0.0, .16, "25/1")
        assertEquals(listOf(0.0, .04, .12), output.times)
        assertEquals(listOf(0.0), output.keys)
        assertEquals(3, output.count(0.0, .16))
        SmartJoinTiming.validate(output, .16, 3, 25.0)
    }

    @Test fun sourceGapNeverAllowsInvalidTimestampsOrUnorderedDecodeTimes() {
        val output = video(listOf(0.0, .04, .08)).copy(duration = .12)
        val unordered = output.copy(packets = output.packets.mapIndexed { i, p -> if (i == 2) p.copy(dts = -.04) else p })
        assertThrows(IllegalStateException::class.java) { SmartJoinTiming.validate(unordered, .12, 3, 25.0, 1.2) }
        val invalid = output.copy(packets = output.packets.mapIndexed { i, p -> if (i == 2) p.copy(pts = Double.NaN) else p })
        assertThrows(IllegalStateException::class.java) { SmartJoinTiming.validate(invalid, .12, 3, 25.0, 1.2) }
    }

    @Test fun discardedPositiveReferenceBeforeVisibleBFrameNeedsOnlyLastGopRepair() {
        val packets = listOf(
            SmartJoinTiming.Packet(0.0, -.08, .04, true),
            SmartJoinTiming.Packet(.04, -.04, .04, false),
            SmartJoinTiming.Packet(2.0, 1.88, .04, true),
            SmartJoinTiming.Packet(1.92, 1.92, .04, false),
            SmartJoinTiming.Packet(2.12, 1.96, .04, false, true),
            SmartJoinTiming.Packet(2.08, 2.0, .04, false))
        val camera = SmartJoinTiming.Video(packets, 0.0, 0.0, 2.12, "25/1")
        assertEquals(1.92, camera.tailRepairStartSeconds!!, 1e-9)
    }

    @Test fun discardedLastPacketAndNegativeCameraPrerollDoNotRequireTailEncode() {
        val packets = listOf(
            SmartJoinTiming.Packet(-.04, -.12, .04, true, true),
            SmartJoinTiming.Packet(0.0, -.08, .04, true),
            SmartJoinTiming.Packet(.04, -.04, .04, false),
            SmartJoinTiming.Packet(.08, 0.0, .04, false, true))
        val camera = SmartJoinTiming.Video(packets, 0.0, 0.0, .12, "25/1")
        assertNull(camera.tailRepairStartSeconds)
    }

    @Test fun cameraOriginIsSubtractedWhenFindingDiscardedReferenceTail() {
        val packets = listOf(
            SmartJoinTiming.Packet(.17, .09, .04, true),
            SmartJoinTiming.Packet(2.17, 2.05, .04, true),
            SmartJoinTiming.Packet(2.09, 2.09, .04, false),
            SmartJoinTiming.Packet(2.29, 2.13, .04, false, true),
            SmartJoinTiming.Packet(2.25, 2.17, .04, false))
        val camera = SmartJoinTiming.Video(packets, .17, .17, 2.12, "25/1")
        assertEquals(1.92, camera.tailRepairStartSeconds!!, 1e-9)
    }

    @Test fun decoderWindowReconstructsNinetyKhzTicksInsteadOfNominalFrameRate() {
        val output = video(listOf(1.516456, 1.550000, 2.316367), 60.0)
        val window = SmartJoinTiming.decoderWindow(output, 1.516456, 2.316456)!!

        assertEquals(listOf(136481L, 139500L, 208473L), window.presentationTicks)
        assertEquals(90000L, window.timeBaseDenominator)
        assertEquals(136481.0 / 90000 - .000002, window.seekSeconds, 1e-12)
        assertEquals(208473.0 / 90000 + .000002, window.untilSeconds, 1e-12)
        SmartJoinTiming.validateDecodedWindow(window, listOf(136481L, 139500L, 208473L))
    }

    @Test fun decoderWindowKeepsEndExclusiveAndDoesNotUseFrameCountEpsilon() {
        val output = video(listOf(0.0, .04, .08, .12))
        val window = SmartJoinTiming.decoderWindow(output, .04, .12)!!

        assertEquals(listOf(3600L, 7200L), window.presentationTicks)
        assertNull(SmartJoinTiming.decoderWindow(output, .13, .14))
    }

    @Test fun decoderWindowUsesAbsolutePtsAndInputFormatOffset() {
        val output = video(listOf(.17, .21, .25)).copy(origin = .17, seekOffset = .02)
        val window = SmartJoinTiming.decoderWindow(output, .04, .12)!!

        assertEquals(listOf(18900L, 22500L), window.presentationTicks)
        assertEquals(.06 - .000002, window.seekSeconds, 1e-12)
        assertEquals(.25 + .000002, window.untilSeconds, 1e-12)
    }

    @Test fun decodedWindowRequiresEveryExactPtsInPresentationOrder() {
        val window = SmartJoinTiming.decoderWindow(video(listOf(0.0, .04, .08)), 0.0, .12)!!

        SmartJoinTiming.validateDecodedWindow(window, listOf(0L, 3600L, 7200L))
        assertThrows(IllegalStateException::class.java) { SmartJoinTiming.validateDecodedWindow(window, listOf(0L, 3600L)) }
        assertThrows(IllegalStateException::class.java) { SmartJoinTiming.validateDecodedWindow(window, listOf(0L, 3600L, 7201L)) }
        assertThrows(IllegalStateException::class.java) { SmartJoinTiming.validateDecodedWindow(window, listOf(0L, 7200L, 3600L)) }
    }
}
