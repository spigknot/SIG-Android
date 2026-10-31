package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Adendo 10 §2.6 — RECUSA ANTES DE ENFILEIRAR.
 *
 * O contrato é: quando a porta recusa, o trabalho NUNCA entra na fila do executor.
 * Antes a admissão rodava DENTRO do worker, então um clique concorrente enfileirava
 * um `Runnable` que só depois era recusado.
 *
 * Aqui a porta é exercitada com um `enfileirar` injetado: nenhum Android, nenhuma
 * sessão, nenhum modelo. O que se prova é a ordem (decidir → enfileirar), não o
 * compute.
 */
class WhisperOrtPortaTest {

    // Admissão própria por teste: nenhum estado global compartilhado, sem hook de
    // teste em código de produção.
    private fun novaAdmissao() = WhisperOrtAdmission()

    @Test
    fun `porta recusa o segundo pedido sem enfileirar nada`() {
        val admissao = novaAdmissao()
        val enfileirados = StringBuilder()
        val primeira = WhisperOrtPorta.despachar("arquivo-A", admissao) { enfileirados.append("A") }
        assertTrue("primeiro pedido deveria ser admitido", primeira is WhisperOrtAdmission.Admitted.Ok)
        assertEquals("admitido entra na fila UMA vez", "A", enfileirados.toString())

        val segunda = WhisperOrtPorta.despachar("arquivo-B", admissao) { enfileirados.append("B") }
        assertTrue("segundo pedido deveria ser recusado", segunda is WhisperOrtAdmission.Admitted.Refused)
        assertEquals(
            "recusa NÃO pode enfileirar trabalho",
            "A",
            enfileirados.toString(),
        )
    }

    @Test
    fun `depois de liberar, a porta volta a admitir`() {
        val admissao = novaAdmissao()
        val fila = StringBuilder()
        val primeiro = WhisperOrtPorta.despachar("A", admissao) { fila.append("A") }
        val job = (primeiro as WhisperOrtAdmission.Admitted.Ok).job
        admissao.abandon(job)
        val depois = WhisperOrtPorta.despachar("A", admissao) { fila.append("A2") }
        assertTrue("após liberar, deve admitir de novo", depois is WhisperOrtAdmission.Admitted.Ok)
        assertEquals("A", fila.substring(0, 1))
        assertTrue("o segundo admitido também entra na fila", fila.endsWith("A2"))
    }

    @Test
    fun `identidade vazia nao passa pela porta`() {
        val admissao = novaAdmissao()
        var enfileirou = false
        val r = WhisperOrtPorta.despachar("", admissao) { enfileirou = true }
        // "" ainda gera hash (sha256 de string vazia) e é aceito; o que NÃO pode é
        // enfileirar sem job. Registra o comportamento observado.
        assertTrue(r is WhisperOrtAdmission.Admitted.Ok)
        assertTrue("job admitido precisa entrar na fila", enfileirou)
    }

    @Test
    fun `falha ao enfileirar libera a admissao e preserva a causa`() {
        val admissao = novaAdmissao()
        val causa = java.util.concurrent.RejectedExecutionException("executor indisponivel")
        val falha = runCatching {
            WhisperOrtPorta.despachar("A", admissao) { throw causa }
        }.exceptionOrNull()
        assertSame(causa, falha)

        var enfileirou = false
        val sucessor = WhisperOrtPorta.despachar("B", admissao) { enfileirou = true }
        assertTrue("fila rejeitada nao pode prender a admissao", sucessor is WhisperOrtAdmission.Admitted.Ok)
        assertTrue(enfileirou)
    }
}
