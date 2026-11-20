package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R115 §T03 — ordem da REALIMENTAÇÃO DIRETA e do FECHAMENTO dos resultados.
 *
 * A R114 mediu 0,7 ms por janela no modo direto contra 36,6 ms no modo cópia.
 * O que pode quebrar sem o aparelho é a ORDEM: primeiro passo com zeros, passos
 * seguintes lendo do resultado anterior, e o anterior só fechado DEPOIS do run.
 * Aqui `T = S = String` prova exatamente isso.
 */
class WhisperOrtFeedbackDiretoTest {

    private val abertos = ArrayList<String>()
    private val fechados = ArrayList<String>()

    private fun novo(): WhisperOrtFeedbackDireto<String, String> =
        WhisperOrtFeedbackDireto(
            saidasParaEntrada = { resultado ->
                // Um resultado "r1" produz as entradas r1:out0 e r1:out1.
                mapOf("in0" to "$resultado:out0", "in1" to "$resultado:out1")
            },
            fechar = { resultado ->
                abertos.remove(resultado)
                fechados.add(resultado)
            },
        )

    @Test
    fun `primeiro passo usa zeros e nao tem origem para fechar`() {
        val feedback = novo()
        val (entradas, origem) = feedback.entradas()
        assertTrue(entradas.isEmpty())
        assertNull(origem)
        assertEquals(WhisperOrtFeedbackDireto.DIRETO, feedback.modo)
        assertNull(feedback.erro)
    }

    @Test
    fun `resultado anterior entra como entrada e so fecha depois do run`() {
        val feedback = novo()
        val (_, primeiraOrigem) = feedback.entradas()
        feedback.concluir("r1", primeiraOrigem)
        abertos.add("r1")
        assertTrue("r1 continua aberto depois do passo 1", abertos.contains("r1"))

        val (entradas, origem) = feedback.entradas()
        assertEquals(mapOf("in0" to "r1:out0", "in1" to "r1:out1"), entradas)
        assertEquals("r1", origem)
        // O run acontece AQUI e só então o resultado anterior é fechado.
        assertTrue("origem ainda aberta durante o run", abertos.contains("r1"))
        feedback.concluir("r2", origem)
        abertos.add("r2")
        assertEquals(listOf("r1"), fechados)
        assertTrue(abertos.contains("r2"))
    }

    @Test
    fun `limpar fecha o resultado pendente`() {
        val feedback = novo()
        feedback.concluir("r1", null)
        abertos.add("r1")
        feedback.limpar()
        assertEquals(listOf("r1"), fechados)
        assertFalse(feedback.temAnterior)
    }

    @Test
    fun `recusa registra o erro e mantem a origem para a copia do retry`() {
        val feedback = novo()
        feedback.concluir("r1", null)
        abertos.add("r1")
        feedback.recusar("api recusou")
        assertEquals(WhisperOrtFeedbackDireto.COPIA, feedback.modo)
        assertEquals("api recusou", feedback.erro)
        assertTrue("a origem precisa continuar viva para o retry", abertos.contains("r1"))
        // Uma segunda recusa não apaga a primeira mensagem.
        feedback.recusar("outra")
        assertEquals("api recusou", feedback.erro)
        // No retry em modo cópia o resultado anterior ainda é a fonte.
        assertEquals("r1", feedback.entradas().second)
        feedback.limpar()
        assertEquals(listOf("r1"), fechados)
    }

    @Test
    fun `modo copia nao volta para direto`() {
        val feedback = novo()
        feedback.recusar("sem suporte")
        assertEquals(WhisperOrtFeedbackDireto.COPIA, feedback.modo)
        feedback.concluir("r1", null)
        assertEquals(WhisperOrtFeedbackDireto.COPIA, feedback.modo)
        assertNotNull(feedback.erro)
    }
}
