package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartJoinPlannerTest {

    private fun profile(
        codec: String = "h264",
        width: Int = 1920,
        height: Int = 1080,
        fps: Double = 30.0,
        rotation: Int = 0,
        pixelFormat: String? = "yuv420p",
        sar: String? = "1:1",
        codecProfile: String? = "High"
    ) = SmartJoinPlanner.VideoProfile(codec, width, height, fps, rotation, pixelFormat, sar, codecProfile)

    private fun source(
        duration: Double,
        profile: SmartJoinPlanner.VideoProfile = profile(),
        keyframes: List<Double> = listOf(0.0, 2.0, 4.0, 6.0, 8.0, 10.0)
    ) = SmartJoinPlanner.Source(duration, profile, keyframes)

    @Test
    fun transitionMarginsUsePreviousAndNextKeyframes() {
        val plan = SmartJoinPlanner.plan(
            sources = listOf(
                source(10.0, keyframes = listOf(0.0, 2.0, 4.0, 6.0, 8.0)),
                source(10.0, keyframes = listOf(0.0, 2.0, 4.0, 6.0, 8.0))
            ),
            transitionSeconds = 1.0,
            fadeInOut = false
        )

        assertTrue(plan.canSmartJoin)
        assertEquals(8.0, plan.clips[0].bodyEndSeconds, 0.0001)
        assertEquals(2.0, plan.clips[1].bodyStartSeconds, 0.0001)
        assertEquals(8.0, plan.junctions.single().outgoingBridgeStartSeconds, 0.0001)
        assertEquals(2.0, plan.junctions.single().incomingBridgeEndSeconds, 0.0001)
        assertEquals(19.0, plan.expectedDurationSeconds(listOf(10.0, 10.0)), 0.0001)
    }

    @Test
    fun fadeInOutPreservesTotalDuration() {
        val plan = SmartJoinPlanner.plan(
            listOf(source(10.0), source(12.0)),
            transitionSeconds = 1.0,
            fadeInOut = true
        )

        assertEquals(22.0, plan.expectedDurationSeconds(listOf(10.0, 12.0)), 0.0001)
    }

    @Test
    fun noTransitionKeepsEveryClipFromItsOwnStart() {
        val plan = SmartJoinPlanner.plan(
            listOf(
                source(10.0, keyframes = listOf(0.17, 2.17, 4.17, 6.17, 8.17)),
                source(12.0, keyframes = listOf(0.17, 2.17, 4.17, 6.17, 8.17))
            ),
            transitionSeconds = 0.0,
            fadeInOut = false
        )

        assertTrue(plan.canSmartJoin)
        assertEquals(0.0, plan.clips[0].bodyStartSeconds, 0.0001)
        assertEquals(0.0, plan.clips[1].bodyStartSeconds, 0.0001)
        assertEquals(10.0, plan.clips[0].bodyEndSeconds, 0.0001)
        assertEquals(12.0, plan.clips[1].bodyEndSeconds, 0.0001)
        assertTrue(plan.junctions.isEmpty())
    }

    @Test
    fun mp4EditListOffsetStillAllowsCopyWhenFirstIdrIsNearStart() {
        val shifted = source(10.0, keyframes = listOf(0.170, 2.170, 4.170, 6.170, 8.170))
        val plan = SmartJoinPlanner.plan(listOf(shifted, shifted), 0.5, fadeInOut = true)

        assertTrue(plan.clips.all { it.copyVideo })
    }

    @Test
    fun incompatibleClipIsReencodedWhileCompatibleClipsRemainCopied() {
        val incompatible = profile(width = 1280, height = 720)
        val plan = SmartJoinPlanner.plan(
            listOf(source(10.0), source(10.0, incompatible), source(10.0)),
            transitionSeconds = 1.0,
            fadeInOut = false
        )

        assertTrue(plan.clips[0].copyVideo)
        assertFalse(plan.clips[1].copyVideo)
        assertTrue(plan.clips[2].copyVideo)
        assertEquals(1.0, plan.clips[1].bodyStartSeconds, 0.0001)
        assertEquals(9.0, plan.clips[1].bodyEndSeconds, 0.0001)
        assertEquals("resolução diferente", plan.clips[1].incompatibilityReason)
    }

    @Test
    fun dominantDurationProfileIsSelectedToMaximizeStreamCopy() {
        val hevc = profile(codec = "hevc", codecProfile = "Main")
        val sources = listOf(
            source(5.0),
            source(20.0, hevc),
            source(15.0, hevc)
        )

        assertEquals(1, SmartJoinPlanner.chooseTargetIndex(sources))
    }

    @Test
    fun sparseKeyframesReencodeOnlyAffectedClip() {
        val plan = SmartJoinPlanner.plan(
            listOf(
                source(10.0),
                source(3.0, keyframes = listOf(0.0, 2.9)),
                source(10.0)
            ),
            transitionSeconds = 1.0,
            fadeInOut = false
        )

        assertTrue(plan.canSmartJoin)
        assertTrue(plan.clips[0].copyVideo)
        assertFalse(plan.clips[1].copyVideo)
        assertTrue(plan.clips[2].copyVideo)
        assertTrue(plan.clips[1].incompatibilityReason.orEmpty().contains("keyframes"))
    }

    @Test
    fun transitionOverlapRejectsSmartJoinWithoutFullReencode() {
        val plan = SmartJoinPlanner.plan(
            listOf(source(10.0), source(1.5), source(10.0)),
            transitionSeconds = 1.0,
            fadeInOut = false
        )

        assertFalse(plan.canSmartJoin)
        assertTrue(plan.ineligibilityReason.orEmpty().contains("sobrepõem"))
    }

    @Test
    fun unsupportedPixelFormatRejectsSmartJoin() {
        val plan = SmartJoinPlanner.plan(
            listOf(source(10.0, profile(pixelFormat = "yuv420p10le")), source(10.0, profile(pixelFormat = "yuv420p10le"))),
            transitionSeconds = 0.5,
            fadeInOut = false
        )

        assertFalse(plan.canSmartJoin)
        assertTrue(plan.ineligibilityReason.orEmpty().contains("formato de pixel"))
    }

    @Test
    fun compatibilityChecksCodecFpsRotationPixelFormatAndSar() {
        val base = profile()

        assertNull(SmartJoinPlanner.videoIncompatibility(base, base.copy(fps = 30.005)))
        assertEquals("codec diferente", SmartJoinPlanner.videoIncompatibility(base, base.copy(codecFamily = "hevc")))
        assertEquals("framerate diferente", SmartJoinPlanner.videoIncompatibility(base, base.copy(fps = 29.97)))
        assertEquals("rotação diferente", SmartJoinPlanner.videoIncompatibility(base, base.copy(rotationDegrees = 90)))
        assertEquals("formato de pixel diferente", SmartJoinPlanner.videoIncompatibility(base, base.copy(pixelFormat = "yuv422p")))
        assertEquals("SAR/DAR diferente", SmartJoinPlanner.videoIncompatibility(base, base.copy(sampleAspectRatio = "4:3")))
    }

    @Test
    fun selectedCompatibleEncoderComesFirst() {
        val candidates = SmartJoinPlanner.compatibleEncoderNames(
            codecFamily = "h264",
            selectedEncoderName = "libx264",
            encoders = listOf(
                "h264_mediacodec" to "h264",
                "hevc_mediacodec" to "hevc",
                "libx264" to "h264"
            )
        )

        assertEquals(listOf("libx264", "h264_mediacodec"), candidates)
    }
}
