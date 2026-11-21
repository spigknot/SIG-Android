package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WhisperBackendDisponibilidadeTest {
    @Test
    fun `opcoes preservam ids nativos e a ordem do seletor`() {
        assertEquals(listOf(0, 2, 1), WhisperBackendDisponibilidade.ativos)
        for (kind in WhisperBackendDisponibilidade.ativos) {
            assertTrue(WhisperBackendDisponibilidade.disponivel(kind))
            assertEquals(kind, WhisperBackendDisponibilidade.resolver(kind))
        }
    }

    @Test
    fun `backend desconhecido e recusado`() {
        assertFalse(WhisperBackendDisponibilidade.disponivel(7))
        assertEquals(0, WhisperBackendDisponibilidade.resolver(7))
    }
}
