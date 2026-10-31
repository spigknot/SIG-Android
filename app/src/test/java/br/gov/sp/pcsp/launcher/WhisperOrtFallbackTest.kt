package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Adendo 13 §4 — fallback SOMENTE por recusa de capacidade.
 *
 * BUSY (sessão ocupada, reserva tomada), dreno, cancelamento e erro interno NÃO
 * podem oferecer a troca para whisper.cpp: enquanto o recurso está ativo, trocar
 * de motor seria tomada de decisão sobre outro job.
 *
 * (Recebe só o motivo: a decisão é pura e não toca o objeto da rota, que carrega
 * Android e não inicializa em JVM.)
 */
class WhisperOrtFallbackTest {

    @Test
    fun `recusa de pacote e de midia oferece fallback`() {
        assertTrue(WhisperOrtPorta.isFallbackElegivel("pacote: SEM_ATIVO: x"))
        assertTrue(WhisperOrtPorta.isFallbackElegivel("midia: arquivo WAV truncado"))
    }

    @Test
    fun `busy dreno cancelamento e erro nao oferecem fallback`() {
        assertFalse(WhisperOrtPorta.isFallbackElegivel("sessao ocupada: x"))
        assertFalse(WhisperOrtPorta.isFallbackElegivel("reserva de job recusada: y"))
        assertFalse(WhisperOrtPorta.isFallbackElegivel("job j em execução"))
        assertFalse(WhisperOrtPorta.isFallbackElegivel("interno: z"))
        assertFalse(
            WhisperOrtPorta.isFallbackElegivel("CANCELLED"),
        )
        assertFalse(
            WhisperOrtPorta.isFallbackElegivel("SUCCESS"),
        )
    }
}
