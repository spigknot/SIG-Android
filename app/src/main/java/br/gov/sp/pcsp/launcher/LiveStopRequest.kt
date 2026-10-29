package br.gov.sp.pcsp.launcher

import java.util.concurrent.atomic.AtomicBoolean

/**
 * R13/F2-fix — PEDIDO ÚNICO de parada do job AO VIVO.
 *
 * Achado F2: no piloto live o clique em parar NÃO emitia pedido de cancelamento
 * nenhum — o stop só parava de capturar e esperava a janela em voo terminar; o
 * primeiro cancel saía do `onDestroy` ~29 s depois e abortava a janela
 * SEGUINTE. Com o pedido encaminhado no clique, a janela em voo observa o abort
 * na próxima fronteira segura (nó do grafo CPU — `ggml-cpu` consulta o callback
 * por nó) sem kill, sem detach e sem esperar a janela inteira.
 *
 * Duas ações, nesta ordem, ANTES de qualquer join/espera:
 *  1. [cancelNative] com a geração do job ao vivo — pedido POR-DONO (nunca
 *     anônimo): um sucessor não é atingido e o texto já publicado fica;
 *  2. [cancelPipeline] — o áudio ainda enfileirado é descartado; sem isto o
 *     próximo chunk viraria um decode novo de ~82 s depois do pedido consumido.
 *
 * Idempotente: clique duplo / stop + cancelamento da notificação encaminham um
 * pedido só. Geração desconhecida (<= 0) não vira pedido — sem owner não há
 * alvo e um pedido anônimo derrubaria outro job.
 */
internal class LiveStopRequest(
    private val cancelNative: (Long) -> Unit,
    private val cancelPipeline: () -> Unit,
    private val onForwarded: (Long) -> Unit = {},
) {
    private val forwarded = AtomicBoolean(false)

    /** true = ESTE chamado encaminhou o pedido; false = nada a fazer. */
    fun request(generation: Long): Boolean {
        if (generation <= 0L) return false
        if (!forwarded.compareAndSet(false, true)) return false
        // O pedido é tentado UMA vez (sem retry: um segundo pedido poderia
        // atingir outra janela) e uma falha do gateway não pode deixar o
        // pendente sendo decodificado.
        try {
            cancelNative(generation)
        } catch (_: Throwable) {
        }
        try {
            cancelPipeline()
        } catch (_: Throwable) {
        }
        onForwarded(generation)
        return true
    }
}
