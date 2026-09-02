package br.gov.sp.pcsp.launcher

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FfmpegOutputRemuxerTest {

    @Test
    fun mediaCodecReencodeUsesMp4IntermediateOnlyWhenReencoding() {
        assertEquals(
            "mp4",
            FfmpegOutputRemuxer.intermediateVideoExtension("mkv", "h264_mediacodec", true)
        )
        assertEquals(
            "mp4",
            FfmpegOutputRemuxer.intermediateVideoExtension("mkv", "hevc_mediacodec", true)
        )
        assertEquals(
            "mkv",
            FfmpegOutputRemuxer.intermediateVideoExtension("mkv", "h264_mediacodec", false)
        )
        assertEquals(
            "mkv",
            FfmpegOutputRemuxer.intermediateVideoExtension("mkv", "libx264", true)
        )
    }

    @Test
    fun originalVideoExtensionUsesTheLastSuffix() {
        assertEquals("mp4", FfmpegOutputRemuxer.originalVideoExtension("evidencia.v2.MP4"))
        assertEquals("", FfmpegOutputRemuxer.originalVideoExtension("sem-extensao"))
    }

    @Test
    fun remuxToOriginalContainerDoesNotDeleteSameExtensionInput() {
        val input = File.createTempFile("remux-same-extension-", ".mp4")
        try {
            input.writeText("placeholder")
            val result = FfmpegOutputRemuxer.remuxToOriginalContainer(input, "MP4")

            assertFalse(result.converted)
            assertEquals(input, result.file)
            assertTrue(input.exists())
        } finally {
            input.delete()
        }
    }

    @Test
    fun remuxToOriginalContainerKeepsMissingOrUnsupportedInputAsFallback() {
        val missing = File.createTempFile("remux-missing-", ".mp4").apply { delete() }

        val missingResult = FfmpegOutputRemuxer.remuxToOriginalContainer(missing, "mkv")
        assertFalse(missingResult.converted)
        assertEquals(missing, missingResult.file)

        val input = File.createTempFile("remux-unsupported-", ".mp4")
        try {
            input.writeText("placeholder")
            val unsupportedResult = FfmpegOutputRemuxer.remuxToOriginalContainer(input, "webm")
            assertFalse(unsupportedResult.converted)
            assertEquals(input, unsupportedResult.file)
            assertTrue(input.exists())
        } finally {
            input.delete()
        }
    }
}
