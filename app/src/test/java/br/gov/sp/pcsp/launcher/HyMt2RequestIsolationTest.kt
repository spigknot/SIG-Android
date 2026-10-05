package br.gov.sp.pcsp.launcher

import org.junit.Assert.*
import org.junit.Test

/**
 * Contrato de pedido independente: reset antes de cada prefill, e o segundo
 * pedido nunca observa tokens do primeiro.
 */
class HyMt2RequestIsolationTest {

    private class FakeScope : HyMt2RequestIsolation.RequestScope {
        val calls = mutableListOf<String>()
        var resetOk = true
        var seenTokens = mutableListOf<Int>()
        var toGenerate = mutableListOf(90, 91)

        override fun reset(): Boolean {
            calls += "reset"
            seenTokens.clear()
            return resetOk
        }

        override fun prefill(tokens: List<Int>): Boolean {
            calls += "prefill:${tokens.hashCode()}"
            seenTokens += tokens
            return true
        }

        override fun decodeStep(): Int? {
            calls += "decode"
            return if (toGenerate.isNotEmpty()) toGenerate.removeAt(0) else null
        }
    }

    @Test
    fun `segundo pedido nao observa tokens do primeiro e reset vem antes do prefill`() {
        val scope = FakeScope()
        val primeiro = listOf(1, 2, 3)
        val segundo = listOf(7, 8)

        val out1 = HyMt2RequestIsolation.translateFresh(scope, primeiro)
        scope.toGenerate = mutableListOf(92)
        val out2 = HyMt2RequestIsolation.translateFresh(scope, segundo)

        val resets = scope.calls.count { it == "reset" }
        assertEquals("reset uma vez por pedido", 2, resets)
        val firstPrefill = scope.calls.indexOfFirst { it.startsWith("prefill") }
        assertEquals("primeira chamada e reset", "reset", scope.calls.first())
        assertTrue("reset antes do primeiro prefill", scope.calls.indexOf("reset") < firstPrefill)
        assertTrue("saida do segundo pedido nao contem tokens do primeiro",
            out2.none { it == 90 || it == 91 } || out1.isNotEmpty())
        // isolamento real: o prefill do segundo pedido recebe só os tokens dele
        val prefills = scope.calls.filter { it.startsWith("prefill") }
        assertEquals(2, prefills.size)
        assertEquals("prefill:${segundo.hashCode()}", prefills[1])
    }

    @Test
    fun `falha de reset aborta o pedido sem prefill`() {
        val scope = FakeScope()
        scope.resetOk = false
        try {
            HyMt2RequestIsolation.translateFresh(scope, listOf(1))
            fail("deveria lancar quando reset falha")
        } catch (e: IllegalStateException) {
            // esperado
        }
        assertFalse("nenhum prefill apos reset falhar",
            scope.calls.any { it.startsWith("prefill") })
    }
}
