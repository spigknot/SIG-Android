package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Adendo 12 §4 — precedência de SELEÇÃO e CONSENTIMENTO, em teste JVM.
 *
 * Estas duas regras ficavam dentro da Activity e só podiam ser conferidas por
 * clique. Extraídas para `WhisperOrtSelecao`, viram contrato verificável.
 */
class WhisperOrtSelecaoTest {

    @Test
    fun `arquivo escolhido na tela vence o intent`() {
        // Abrir A por intent e escolher B: tem de transcrever B.
        assertEquals("B", WhisperOrtSelecao.resolver("B", "A"))
    }

    @Test
    fun `sem escolha na tela usa o intent`() {
        assertEquals("A", WhisperOrtSelecao.resolver(null, "A"))
        assertEquals("A", WhisperOrtSelecao.resolver("", "A"))
    }

    @Test
    fun `sem nada nao inventa entrada`() {
        assertNull(WhisperOrtSelecao.resolver(null, null))
        assertNull(WhisperOrtSelecao.resolver("", ""))
    }

    @Test
    fun `consentimento so vale para a entrada exibida`() {
        assertTrue("aceite do fallback deve valer para a mesma entrada", WhisperOrtSelecao.forcarCpp("A", "A"))
        assertFalse("não pode forçar CPP em outro arquivo", WhisperOrtSelecao.forcarCpp("A", "B"))
        assertFalse("sem override não força", WhisperOrtSelecao.forcarCpp(null, "A"))
        assertFalse("sem entrada efetiva não força", WhisperOrtSelecao.forcarCpp("A", null))
    }
}
