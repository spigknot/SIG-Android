package br.gov.sp.pcsp.launcher

/**
 * R17/E2 — DECISÃO DO `audio_ctx` DE BANCADA (peça pura, host-testável).
 *
 * O seam da R14 entregava o valor ao nativo a cada job e **herdava** o que
 * estivesse lá. Dois defeitos:
 *
 *  1. **vínculo**: o valor não pertencia a nenhum job — um segundo job na mesma
 *     sessão podia recebê-lo de novo (vazamento entre donos).
 *  2. **legado**: se um job anterior deixasse o override no nativo e o job
 *     atual não pedisse nada, ele era herdado em silêncio.
 *
 * A regra implementada aqui:
 *
 *  - pedido > 0 ................................ APPLY  (e o pedido é consumido
 *                                                  no ato: só este job o vê);
 *  - sem pedido e nativo > 0 .................... CLEAR  (legado RECUSADO — o
 *                                                  valor antigo é zerado);
 *  - sem pedido e nativo == 0 ................... NONE.
 *
 * Quem aplica é o chamador (o controlador de sessão), logo antes do job. A
 * peça não toca no nativo: decide.
 */
object AudioCtxSeam {

    enum class Action { NONE, APPLY, CLEAR }

    /**
     * @param value    valor a aplicar (só é significativo em [Action.APPLY])
     * @param motivo   rótulo curto para o log/diagnóstico
     */
    data class Decision(val action: Action, val value: Int, val motivo: String)

    /**
     * @param requested  valor pedido pelo seam de bancada (0 = nenhum).
     * @param nativeNow  valor que o nativo está carregando AGORA
     *                   (peek antes de decidir).
     */
    fun decide(requested: Int, nativeNow: Int): Decision = when {
        requested > 0 ->
            Decision(Action.APPLY, requested, "pedido")
        nativeNow > 0 ->
            // Não há pedido para este job: o valor em memória é de OUTRO dono.
            Decision(Action.CLEAR, 0, "legado_recusado")
        else ->
            Decision(Action.NONE, 0, "sem_pedido")
    }
}
