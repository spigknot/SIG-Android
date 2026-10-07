package br.gov.sp.pcsp.launcher

import org.junit.Assert.*
import org.junit.Test

class SmartJoinTimingTest {
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
}
