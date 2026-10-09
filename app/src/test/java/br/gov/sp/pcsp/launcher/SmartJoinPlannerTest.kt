package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartJoinPlannerTest {
    @Test fun nominalFrameRateDifferenceDoesNotForceBodyEncode() {
        val result=SmartJoinPlanner.plan(listOf(source(12.0,profile(fps=25.0)),source(12.0,profile(fps=30.0))),.5,false)
        assertTrue(result.clips.all { it.copyVideo })
    }


    @Test fun cameraNegativePrerollDoesNotHideVisibleKeyframe() {
        val cameraClip = source(12.0, keyframes = listOf(-.033756, 0.0, 2.0, 4.0, 6.0, 8.0, 10.0))
        for (seconds in listOf(0.0, .2, .5, 1.0)) {
            for (fadeInOut in listOf(false, true)) {
                val result = SmartJoinPlanner.plan(listOf(source(10.0), cameraClip), seconds, fadeInOut)
                assertTrue(result.clips.all { it.copyVideo })
                if (seconds == 0.0) assertTrue(result.junctions.isEmpty())
            }
        }
    }

    @Test fun negativePrerollWithoutVisibleInitialKeyframeStillRequiresEncode() {
        val cameraClip = source(12.0, keyframes = listOf(-.033756, 3.0, 6.0, 9.0))
        val result = SmartJoinPlanner.plan(listOf(source(10.0), cameraClip), .5, false)
        assertFalse(result.clips[1].copyVideo)
        assertTrue(result.clips[1].incompatibilityReason.orEmpty().contains("keyframe"))
    }

    @Test fun zeroTransitionRepairsOnlyCameraLastGopWhenDiscardedFrameIsStillAReference() {
        val camera = source(2304.1279, profile(codec = "hevc", fps = 89.0 / 3.0, codecProfile = "Main"),
            listOf(0.0, 2300.186644, 2301.203122, 2302.219600, 2303.236056))
            .copy(tailRepairStartSeconds = 2303.236056)
        val next = source(1067.947856, camera.profile, listOf(0.0, 2.059044, 4.117944))
        val plan = SmartJoinPlanner.plan(listOf(camera, next), 0.0, false)

        assertTrue(plan.canSmartJoin && plan.hasUsefulCopy)
        assertTrue(plan.clips.all { it.copyVideo })
        assertTrue(plan.junctions.isEmpty())
        val first = plan.clips.first()
        assertEquals(2303.236056, first.bodyEndSeconds, 1e-9)
        assertEquals(2303.236056, first.tailStartSeconds!!, 1e-9)
        assertEquals(2304.1279, first.tailEndSeconds!!, 1e-9)
        assertEquals(.891844, first.tailDurationSeconds, 1e-9)
        assertEquals(3372.075756, plan.expectedDurationSeconds(listOf(camera.durationSeconds, next.durationSeconds)), 1e-9)
        assertEquals(camera.durationSeconds, first.bodyDurationSeconds + first.tailDurationSeconds, 1e-9)
        assertNull(plan.clips.last().tailStartSeconds)
    }

    @Test fun transitionAlreadyCoveringDiscardedCameraTailDoesNotEncodeAnExtraTail() {
        val camera = source(2304.1279, keyframes = listOf(0.0, 2300.186644, 2301.203122, 2302.219600, 2303.236056))
            .copy(tailRepairStartSeconds = 2303.236056)
        val plan = SmartJoinPlanner.plan(listOf(camera, source(1067.947856)), 3.0, false)

        assertTrue(plan.clips.all { it.copyVideo })
        assertEquals(2300.186644, plan.clips.first().bodyEndSeconds, 1e-9)
        assertNull(plan.clips.first().tailStartSeconds)
        assertEquals(0.0, plan.clips.first().tailDurationSeconds, 0.0)
    }

    @Test fun incomingBridgeMayCoverCopyBodyBeforeARequiredTail() {
        val camera = source(4.0, keyframes = listOf(0.0, 2.0, 3.0))
            .copy(tailRepairStartSeconds = 1.92)
        val plan = SmartJoinPlanner.plan(listOf(source(6.0), camera), 1.0, false)
        val clip = plan.clips.last()

        assertTrue(plan.hasUsefulCopy)
        assertTrue(clip.copyVideo)
        assertEquals(2.0, clip.bodyStartSeconds, 0.0)
        assertEquals(0.0, clip.bodyDurationSeconds, 0.0)
        assertEquals(2.0, clip.tailStartSeconds!!, 0.0)
        assertEquals(2.0, clip.tailDurationSeconds, 0.0)
        assertEquals(2.0, plan.junctions.single().incomingBridgeEndSeconds, 0.0)
    }

    @Test fun sourceWithoutDiscardedReferenceKeepsEntireBodyCopied() {
        val plan = SmartJoinPlanner.plan(listOf(source(6.0), source(6.0)), 0.0, false)
        assertTrue(plan.clips.all { it.copyVideo && it.tailDurationSeconds == 0.0 })
        assertTrue(plan.clips.all { it.bodyDurationSeconds == 6.0 })
    }

    @Test fun encodedTailUsesCfrRoundingWithoutStretchingCopiedCameraBody() {
        val rate = 89.0 / 3.0
        val scheduler = SmartJoinTiming.Frames(rate)
        assertEquals(69496, scheduler.next(2303.236056, 69496))
        assertEquals(26, scheduler.next(2304.1279 - 2303.236056))
        assertEquals(31654, scheduler.next(1067.947856, 31654))
        assertEquals(101176, scheduler.total)
        assertEquals(2303.236056 + 26.0 / rate + 1067.947856, scheduler.durationSeconds, 1e-9)
    }

    @Test fun invalidNumbersAreRejected() {
        for (seconds in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0)) {
            assertFalse(SmartJoinPlanner.plan(listOf(source(6.0), source(6.0)), seconds, true).canSmartJoin)
        }
        assertFalse(SmartJoinPlanner.plan(listOf(source(Double.NaN)), 0.0, true).canSmartJoin)
    }

    @Test fun emptyCopyDoesNotPermitFullReencode() {
        val plan = SmartJoinPlanner.plan(List(3) { source(3.0, keyframes = listOf(0.0)) }, .5, true)
        assertFalse(plan.hasUsefulCopy)
    }

    @Test fun subFrameTransitionIsExplicitlyNormalizedToZero() {
        val plan = SmartJoinPlanner.plan(listOf(source(6.0), source(6.0)), .0015, false)
        assertEquals(0.0, plan.transitionSeconds, 0.0)
        assertTrue(plan.junctions.isEmpty())
        assertEquals(12.0, plan.expectedDurationSeconds(listOf(6.0, 6.0)), 0.0)
    }

    @Test fun outgoingBodyStopsBeforeCraLeadingPictures() {
        val src = source(6.0, keyframes = listOf(0.0, 2.0, 4.0)).copy(safeCopyEnds = mapOf(4.0 to 3.92))
        val plan = SmartJoinPlanner.plan(listOf(src, src), .5, true)
        assertEquals(3.92, plan.clips[0].bodyEndSeconds, 1e-9)
        assertEquals(3.92, plan.junctions[0].outgoingBridgeStartSeconds, 1e-9)
    }

    @Test fun uiTransitionLimitCannotOverlapMiddleClip() {
        assertEquals(5.9, SmartJoinPlanner.maximumTransitionSeconds(listOf(6.0, 6.0)), 1e-9)
        val maximum = SmartJoinPlanner.maximumTransitionSeconds(listOf(6.0, 6.0, 6.0))
        assertEquals(2.9, maximum, 1e-9)
        assertTrue(SmartJoinPlanner.plan(List(3) { source(6.0) }, maximum, true).canSmartJoin)
    }

    @Test fun subFrameThresholdUsesDominantProfileRatherThanFirstClip() {
        val sources = listOf(source(6.0, profile(fps = 25.0)), source(6.0, profile(fps = 60.0)), source(6.0, profile(fps = 60.0)))
        val plan = SmartJoinPlanner.plan(sources, .015, true)
        assertEquals(60.0, plan.targetProfile.fps, 0.0)
        assertEquals(.015, plan.transitionSeconds, 0.0)
    }

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
    fun compatibilityChecksCodecRotationPixelFormatAndSar() {
        val base = profile()

        assertNull(SmartJoinPlanner.videoIncompatibility(base, base.copy(fps = 30.005)))
        assertEquals("codec diferente", SmartJoinPlanner.videoIncompatibility(base, base.copy(codecFamily = "hevc")))
        assertNull(SmartJoinPlanner.videoIncompatibility(base, base.copy(fps = 29.97)))
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
