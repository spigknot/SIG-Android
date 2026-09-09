package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaTypeRulesTest {

    @Test
    fun isVideo_porMimeEporExtensao() {
        assertTrue(MediaTypeRules.isVideo("video/mp4", "qualquer.bin"))
        assertTrue(MediaTypeRules.isVideo("", "filme.MP4"))
        assertTrue(MediaTypeRules.isVideo("", "clipe.mkv"))
        assertTrue(MediaTypeRules.isVideo("", "v.m4v"))
        assertFalse(MediaTypeRules.isVideo("", "audio.mp3"))
        assertFalse(MediaTypeRules.isVideo("application/octet-stream", "nota.txt"))
    }

    @Test
    fun isAudio_porMimeEporExtensao() {
        assertTrue(MediaTypeRules.isAudio("audio/ogg", "x.bin"))
        assertTrue(MediaTypeRules.isAudio("", "gravacao.WAV"))
        assertTrue(MediaTypeRules.isAudio("", "voz.opus"))
        assertFalse(MediaTypeRules.isAudio("", "video.mp4"))
    }

    @Test
    fun isAudio_naoAceitaAmr() {
        // Divergencia DELIBERADA: FfmpegExtractAudioActivity tem copia propria
        // com ".amr"; este seam (Granite/RemoteStt) nao aceita .amr.
        assertFalse(MediaTypeRules.isAudio("", "ligacao.amr"))
    }

    @Test
    fun isSupportedMedia_videoOuAudio() {
        assertTrue(MediaTypeRules.isSupportedMedia("", "a.mp3"))
        assertTrue(MediaTypeRules.isSupportedMedia("video/webm", "a.bin"))
        assertFalse(MediaTypeRules.isSupportedMedia("", "doc.pdf"))
    }

    @Test
    fun guessMime_porExtensao() {
        assertEquals("video/*", MediaTypeRules.guessMime("filme.mp4"))
        assertEquals("audio/*", MediaTypeRules.guessMime("trilha.flac"))
        assertEquals("application/octet-stream", MediaTypeRules.guessMime("arquivo.pdf"))
    }

    @Test
    fun contentMimeForUpload_preservaMimeExplicito() {
        assertEquals("audio/mpeg", MediaTypeRules.contentMimeForUpload("audio/mpeg", "a.mp3"))
        assertEquals("video/webm", MediaTypeRules.contentMimeForUpload("video/webm", "a.webm"))
    }

    @Test
    fun contentMimeForUpload_infereQuandoGenerico() {
        assertEquals("video/mp4", MediaTypeRules.contentMimeForUpload("application/octet-stream", "a.mp4"))
        assertEquals("audio/mpeg", MediaTypeRules.contentMimeForUpload("", "a.mp3"))
        assertEquals("audio/wav", MediaTypeRules.contentMimeForUpload("", "a.wav"))
        assertEquals("audio/ogg", MediaTypeRules.contentMimeForUpload("", "a.ogg"))
        assertEquals("audio/opus", MediaTypeRules.contentMimeForUpload("", "a.opus"))
        assertEquals("audio/mp4", MediaTypeRules.contentMimeForUpload("", "a.m4a"))
    }

    @Test
    fun contentMimeForUpload_semCorrespondenciaViraOctetStream() {
        // ".aac" nao esta no when: comportamento atual preservado.
        assertEquals("application/octet-stream", MediaTypeRules.contentMimeForUpload("", "a.aac"))
        assertEquals("application/octet-stream", MediaTypeRules.contentMimeForUpload("", "a.txt"))
    }
}
