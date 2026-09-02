package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cobre apenas as partes puras de SharedMediaIntents. A leitura de URIs e de
 * mimetypes depende de ContentResolver e por isso fica para teste manual ou
 * instrumentado.
 */
class SharedMediaIntentsTest {

    @Test
    fun `mimetype explicito vence a extensao`() {
        assertTrue(SharedMediaIntents.isVideoMedia("gravacao.mp3", "video/mp4"))
        assertFalse(SharedMediaIntents.isAudioMedia("gravacao.mp3", "video/mp4"))
        assertTrue(SharedMediaIntents.isAudioMedia("clipe.mp4", "audio/mpeg"))
        assertFalse(SharedMediaIntents.isVideoMedia("clipe.mp4", "audio/mpeg"))
    }

    @Test
    fun `extensao decide quando o mimetype e generico`() {
        assertTrue(SharedMediaIntents.isVideoMedia("clipe.mp4", "application/octet-stream"))
        assertFalse(SharedMediaIntents.isAudioMedia("clipe.mp4", "application/octet-stream"))
        assertTrue(SharedMediaIntents.isAudioMedia("faixa.wav", "application/octet-stream"))
        assertFalse(SharedMediaIntents.isVideoMedia("faixa.wav", "application/octet-stream"))
    }

    @Test
    fun `extensao decide quando o mimetype vem vazio`() {
        assertTrue(SharedMediaIntents.isVideoMedia("audiencia.mkv", ""))
        assertTrue(SharedMediaIntents.isVideoMedia("audiencia.mov", ""))
        assertTrue(SharedMediaIntents.isAudioMedia("entrevista.m4a", ""))
        assertTrue(SharedMediaIntents.isAudioMedia("entrevista.opus", ""))
    }

    @Test
    fun `item sem tipo conhecido nao e audio nem video`() {
        assertFalse(SharedMediaIntents.isVideoMedia("sem_extensao", ""))
        assertFalse(SharedMediaIntents.isAudioMedia("sem_extensao", ""))
        assertFalse(SharedMediaIntents.isVideoMedia("nota.pdf", "application/pdf"))
        assertFalse(SharedMediaIntents.isAudioMedia("nota.pdf", "application/pdf"))
    }

    @Test
    fun `extensao e comparada sem diferenca de caixa`() {
        assertTrue(SharedMediaIntents.isVideoMedia("CLIPE.MP4", ""))
        assertTrue(SharedMediaIntents.isAudioMedia("FAIXA.WAV", ""))
    }

    @Test
    fun `mimetypes derivados da extensao sao classificaveis`() {
        assertEquals("video/x-matroska", SharedMediaIntents.mimeFromExtension("arquivo.mkv"))
        assertEquals("audio/mpeg", SharedMediaIntents.mimeFromExtension("arquivo.mp3"))
        assertEquals("", SharedMediaIntents.mimeFromExtension("arquivo.semtipo"))
        assertTrue(SharedMediaIntents.isVideoMedia("arquivo.mkv", SharedMediaIntents.mimeFromExtension("arquivo.mkv")))
        assertTrue(SharedMediaIntents.isAudioMedia("arquivo.mp3", SharedMediaIntents.mimeFromExtension("arquivo.mp3")))
    }

    @Test
    fun `mkv e m2ts sao reconhecidos como video pelo mimetype derivado`() {
        listOf("a.mkv", "b.m2ts", "c.webm", "d.avi", "e.3gp").forEach { name ->
            val mime = SharedMediaIntents.mimeFromExtension(name)
            assertTrue("$name deveria ser video (mime=$mime)", SharedMediaIntents.isVideoMedia(name, mime))
        }
    }
}
