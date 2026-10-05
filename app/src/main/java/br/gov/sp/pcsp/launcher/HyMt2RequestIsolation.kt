package br.gov.sp.pcsp.launcher

/** Isolamento de pedidos Hy-MT2: cada tradução usa só os tokens do pedido atual.
 *
 * Contrato (design em `docs/hymt2-request-isolation-design.md`): todo pedido
 * novo começa com `reset()` (limpa KV/posição/histórico do pedido anterior);
 * falha de reset aborta antes de qualquer `prefill`. O JNI espelha isso com
 * `llama_memory_clear(..., true)` sob o mesmo `g_mutex`; aqui fica a ordem
 * testável sem aparelho.
 */
object HyMt2RequestIsolation {

    /** Escopo de um pedido: reset, prefill e decodificação passo a passo. */
    interface RequestScope {
        /** Limpa o estado do pedido anterior; false = contexto inutilizável. */
        fun reset(): Boolean

        /** Decodifica o prompt do pedido atual; chamado só após reset ok. */
        fun prefill(tokens: List<Int>): Boolean

        /** Próximo token gerado, ou null ao terminar. */
        fun decodeStep(): Int?
    }

    /** Executa um pedido isolado: reset -> prefill -> decodificação.
     *
     * @throws IllegalStateException se o reset ou o prefill falhar; nesse
     * caso nenhum token é decodificado e o chamador deve tratar como erro
     * (sem reaproveitar resultado antigo).
     */
    fun translateFresh(scope: RequestScope, tokens: List<Int>): List<Int> {
        check(scope.reset()) { "Contexto sem memória limpa para novo pedido." }
        check(scope.prefill(tokens)) { "Falha ao decodificar o prompt." }
        val out = mutableListOf<Int>()
        while (true) {
            val token = scope.decodeStep() ?: break
            out += token
        }
        return out
    }
}
