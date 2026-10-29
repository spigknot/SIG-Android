package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R56/adendo20 — política de disponibilidade: OpenCL pausado, CPU/Vulkan ativos.
 *
 * Garante que a pausa é REVERSÍVEL e que ninguém reindexa os valores nativos:
 * CPU=0 e Vulkan=1 continuam valendo, OpenCL=2 continua existindo e apenas
 * deixa de ser oferecido/aceito.
 */
class WhisperBackendDisponibilidadeTest {

    @Test
    fun `CPU e Vulkan disponiveis, OpenCL recusado com motivo tipado`() {
        assertTrue(WhisperBackendDisponibilidade.disponivel(0))
        assertTrue(WhisperBackendDisponibilidade.disponivel(1))
        assertFalse(WhisperBackendDisponibilidade.disponivel(2))
        assertEquals(
            WhisperBackendDisponibilidade.MOTIVO_OPENCL,
            WhisperBackendDisponibilidade.motivoIndisponivel(2)
        )
        assertNull(WhisperBackendDisponibilidade.motivoIndisponivel(1))
    }

    @Test
    fun `numeracao nativa nao e reindexada`() {
        // o valor 2 continua sendo OpenCL: a pausa não reescreve o enum
        assertEquals(listOf(0, 1), WhisperBackendDisponibilidade.ativos)
        assertNotNull(WhisperBackendDisponibilidade.motivoIndisponivel(2))
    }

    @Test
    fun `preferencia antiga 2 resolve para backend disponivel`() {
        assertEquals(0, WhisperBackendDisponibilidade.resolver(2))
        assertEquals(1, WhisperBackendDisponibilidade.resolver(1))
        assertEquals(0, WhisperBackendDisponibilidade.resolver(0))
    }

    @Test
    fun `backend desconhecido tambem e recusado`() {
        assertFalse(WhisperBackendDisponibilidade.disponivel(7))
        assertEquals(0, WhisperBackendDisponibilidade.resolver(7))
    }
}
