package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartJoinPlannerTest {

    private val h264 = SmartJoinEncoderOption("h264_mediacodec", "h264")
    private val hevc = SmartJoinEncoderOption("hevc_mediacodec", "hevc")
    private val cpuH264 = SmartJoinEncoderOption("libx264", "h264")

    @Test
    fun hevcSource_selectsCompatibleHevcEncoderInsteadOfForcingFullReencode() {
        val plan = SmartJoinPlanner.plan(
            sourceCodecFamily = "hevc",
            selectedEncoder = h264,
            availableEncoders = listOf(h264, hevc, cpuH264),
            inputsCompatible = true,
            orientationMismatch = false
        )

        assertEquals(SmartJoinMode.SMART_JOIN, plan.mode)
        assertEquals(hevc, plan.encoder)
        assertEquals(listOf(hevc), plan.compatibleEncoders)
        assertTrue(plan.reason.orEmpty().contains("não é compatível"))
    }

    @Test
    fun compatibleSelectedEncoder_isPreserved() {
        val plan = SmartJoinPlanner.plan(
            sourceCodecFamily = "h264",
            selectedEncoder = h264,
            availableEncoders = listOf(h264, hevc, cpuH264),
            inputsCompatible = true,
            orientationMismatch = false
        )

        assertEquals(SmartJoinMode.SMART_JOIN, plan.mode)
        assertEquals(h264, plan.encoder)
        assertEquals(listOf(h264, cpuH264), plan.compatibleEncoders)
    }

    @Test
    fun candidates_putSelectedEncoderFirst_andExcludeOtherCodecFamilies() {
        val candidates = SmartJoinPlanner.compatibleEncoderCandidates(
            sourceCodecFamily = "h264",
            selectedEncoder = cpuH264,
            availableEncoders = listOf(h264, hevc, cpuH264, cpuH264)
        )

        assertEquals(listOf(cpuH264, h264), candidates)
    }

    @Test
    fun unsupportedSourceCodec_fallsBackExplicitly() {
        val plan = SmartJoinPlanner.plan(
            sourceCodecFamily = "vp9",
            selectedEncoder = h264,
            availableEncoders = listOf(h264, hevc, cpuH264),
            inputsCompatible = true,
            orientationMismatch = false
        )

        assertEquals(SmartJoinMode.FULL_REENCODE, plan.mode)
        assertTrue(plan.reason.orEmpty().contains("encoder compatível"))
    }

    @Test
    fun orientationMismatch_doesNotSilentlyUseUnsafeStreamCopy() {
        val plan = SmartJoinPlanner.plan(
            sourceCodecFamily = "hevc",
            selectedEncoder = hevc,
            availableEncoders = listOf(hevc),
            inputsCompatible = true,
            orientationMismatch = true
        )

        assertEquals(SmartJoinMode.FULL_REENCODE, plan.mode)
        assertTrue(plan.reason.orEmpty().contains("orientações"))
    }

    @Test
    fun incompatibleInputs_fallBackBeforeConcatCanProduceInvalidOutput() {
        val plan = SmartJoinPlanner.plan(
            sourceCodecFamily = "hevc",
            selectedEncoder = hevc,
            availableEncoders = listOf(hevc),
            inputsCompatible = false,
            orientationMismatch = false
        )

        assertEquals(SmartJoinMode.FULL_REENCODE, plan.mode)
        assertTrue(plan.reason.orEmpty().contains("incompatíveis"))
    }

    @Test
    fun sameCameraProfiles_allowSmallFrameRateDrift() {
        val base = SmartJoinMediaProfile("hevc", 1920, 1080, 29.63, 90, 48000, 2, true)
        val candidate = base.copy(fps = 29.73)

        assertTrue(SmartJoinPlanner.profilesCompatible(base, candidate))
    }

    @Test
    fun differentRotationOrCodec_isNotCompatible() {
        val base = SmartJoinMediaProfile("hevc", 1920, 1080, 29.63, 90, 48000, 2, true)

        assertFalse(SmartJoinPlanner.profilesCompatible(base, base.copy(rotationDegrees = 0)))
        assertFalse(SmartJoinPlanner.profilesCompatible(base, base.copy(codecFamily = "h264")))
    }

    @Test
    fun differentPixFmtOrSar_isNotCompatible() {
        val base = SmartJoinMediaProfile("h264", 1920, 1080, 30.0, 0, 48000, 2, true, pixFmt = "yuv420p", sar = "1:1")

        assertFalse(SmartJoinPlanner.profilesCompatible(base, base.copy(pixFmt = "yuv420p10le")))
        assertFalse(SmartJoinPlanner.profilesCompatible(base, base.copy(sar = "4:3")))
        assertTrue(SmartJoinPlanner.profilesCompatible(base, base.copy(pixFmt = "YUV420P", sar = "1:1")))
    }

    @Test
    fun modernCodecsWithoutAnnexB_forceFullReencode() {
        listOf("vp9", "av1", "mpeg4").forEach { codec ->
            val plan = SmartJoinPlanner.plan(
                sourceCodecFamily = codec,
                selectedEncoder = h264,
                availableEncoders = listOf(h264, hevc, cpuH264),
                inputsCompatible = true,
                orientationMismatch = false
            )
            assertEquals("Codec $codec should force full reencode", SmartJoinMode.FULL_REENCODE, plan.mode)
            assertTrue(plan.reason.orEmpty().contains("Não há encoder compatível"))
        }
    }
}
