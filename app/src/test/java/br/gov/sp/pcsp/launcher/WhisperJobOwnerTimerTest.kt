package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R15/B2 — o timer do "owner gone" NÃO pode finalizar o dono NOVO.
 *
 * Contraexemplo da revisão (compilado contra os métodos reais do Service):
 *   agenda destroy do owner1; troca para owner2; dispara o timer antigo.
 *   Ele chamava stopJob(2, OWNER_GONE_TIMEOUT) — ou seja, PARAVA O JOB ERRADO.
 *
 * Aqui testamos a REGRA, não a classe completa (o Service precisa de Android):
 * a decisão de "este timer ainda vale?" é uma função pura, testável no host.
 */
class WhisperJobOwnerTimerTest {

    /**
     * Réplica do mecanismo do Service: o timer carrega o owner para o qual foi
     * agendado e só age se o owner ATUAL ainda for esse.
     */
    private class OwnerGoneTimer {
        var ownerAtual = 0L
        var agendadoPara = 0L
        var token = 0L
        val terminados = mutableListOf<Long>()

        fun onOwnerGone(generation: Long) {
            if (generation != ownerAtual) return
            token += 1
            agendadoPara = generation
        }

        fun onTerminal(generation: Long) {
            if (generation != ownerAtual) return
            ownerAtual = 0L
            token += 1
            agendadoPara = 0L
        }

        /** Dispara o timer: devolve true se ele ATUOU sobre o job. */
        fun disparaTimer(): Boolean {
            val alvo = agendadoPara
            if (alvo <= 0L) return false
            if (alvo != ownerAtual) return false   // dono mudou: timer é do job VELHO
            terminados += alvo
            return true
        }
    }

    @Test
    fun `timer antigo do owner1 NAO finaliza o owner2`() {
        val t = OwnerGoneTimer()
        t.ownerAtual = 1L
        t.onOwnerGone(1L)              // a dona do owner1 foi destruída
        t.ownerAtual = 2L              // o owner2 inicia um job novo
        val agiu = t.disparaTimer()     // o timer antigo dispara
        assertFalse("o timer do owner1 não pode agir sobre o owner2", agiu)
        assertTrue("nenhum job foi finalizado", t.terminados.isEmpty())
    }

    @Test
    fun `timer do owner1 ainda vale se o owner NAO mudou`() {
        val t = OwnerGoneTimer()
        t.ownerAtual = 1L
        t.onOwnerGone(1L)
        assertTrue("com o mesmo dono, o timer deve agir", t.disparaTimer())
        assertEquals(listOf(1L), t.terminados)
    }

    @Test
    fun `terminal do owner1 invalida o timer pendente`() {
        val t = OwnerGoneTimer()
        t.ownerAtual = 1L
        t.onOwnerGone(1L)
        t.onTerminal(1L)               // o job terminou: o timer não vale mais
        assertFalse("timer de job terminado não pode agir", t.disparaTimer())
        assertTrue(t.terminados.isEmpty())
    }

    @Test
    fun `ownerGone de owner que nao e o dono atual e ignorado`() {
        val t = OwnerGoneTimer()
        t.ownerAtual = 2L
        t.onOwnerGone(1L)              // stale: o owner1 já não é o dono
        t.ownerAtual = 3L
        assertFalse("stale não pode agendar", t.disparaTimer())
    }

    @Test
    fun `novo onOwnerGone rearma para o novo dono`() {
        val t = OwnerGoneTimer()
        t.ownerAtual = 1L
        t.onOwnerGone(1L)
        t.ownerAtual = 2L
        t.onOwnerGone(2L)              // o owner2 também foi destruído
        assertTrue("agora o timer vale para o owner2", t.disparaTimer())
        assertEquals(listOf(2L), t.terminados)
    }
}
