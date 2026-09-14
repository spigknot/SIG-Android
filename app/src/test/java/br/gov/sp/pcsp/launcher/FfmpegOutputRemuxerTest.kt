package br.gov.sp.pcsp.launcher

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun remuxArgumentsLevaAselecaoDoPlanoAteOMuxerFinal() {
        val args = FfmpegOutputRemuxer
            .remuxArguments("/tmp/entrada.mkv", "/tmp/saida.mp4", false, true)
            .toList()

        // F1: sem os -map o FFmpeg faz selecao automatica e faixas de audio
        // extras desaparecem no ultimo passo do pipeline.
        assertEquals(2, args.count { it == "-map" })
        assertTrue(args.containsAll(listOf("-map", "0:v:0", "-map", "0:a?")))
        assertTrue(args.containsAll(listOf("-c", "copy")))
        assertEquals("/tmp/saida.mp4", args.last())
    }

    @Test
    fun remuxArgumentsAplicaTagEfaststartSoNoContainerCerto() {
        val hevcParaMp4 = FfmpegOutputRemuxer
            .remuxArguments("/tmp/entrada.mkv", "/tmp/saida.mp4", true, true)
            .toList()
        assertTrue(hevcParaMp4.containsAll(listOf("-tag:v", "hvc1")))
        assertTrue(hevcParaMp4.containsAll(listOf("-movflags", "+faststart")))

        val hevcParaMkv = FfmpegOutputRemuxer
            .remuxArguments("/tmp/entrada.mp4", "/tmp/saida.mkv", true, false)
            .toList()
        assertFalse(hevcParaMkv.contains("-tag:v"))
        assertFalse(hevcParaMkv.contains("-movflags"))

        val h264ParaMp4 = FfmpegOutputRemuxer
            .remuxArguments("/tmp/entrada.mkv", "/tmp/saida.mp4", false, true)
            .toList()
        assertFalse(h264ParaMp4.contains("-tag:v"))
        assertTrue(h264ParaMp4.contains("-movflags"))
    }

    @Test
    fun inventoryMismatchAcusaPerdaDeFaixasDoPlano() {
        assertNull(FfmpegOutputRemuxer.inventoryMismatch(1, 2, 1, 2))
        // Ganhar faixa nao e o defeito medido (o plano pediu o minimo).
        assertNull(FfmpegOutputRemuxer.inventoryMismatch(1, 1, 1, 2))
        assertEquals(
            "o arquivo final perdeu 1 faixa(s) de audio",
            FfmpegOutputRemuxer.inventoryMismatch(1, 2, 1, 1)
        )
        assertEquals(
            "o arquivo final perdeu a faixa de video",
            FfmpegOutputRemuxer.inventoryMismatch(1, 2, 0, 2)
        )
    }
}
