package br.gov.sp.pcsp.launcher

/**
 * R17/A2 — orçamento ÚNICO de observação de um ensaio de bancada.
 *
 * O defeito que esta porta fecha (medido na R16): o cenário `s14 mode=ok`
 * entregava o `hardFinishMs` do Intent apenas à espera externa (`c3WaitFinal`),
 * enquanto o watcher do [WhisperLabDrive] continuava com **240 s fixos** e
 * chamava `finishWithDump("hard_cap")` ao vencer. Resultado: o braço longo do
 * Turbo F16 morria aos ~240,3 s e o relatório classificou isso como
 * "limitação do ambiente" — era orçamento duplicado no próprio ensaio.
 *
 * Regra: **um valor, uma origem, dois consumidores**. Quem agenda a observação
 * e quem espera o terminal leem o MESMO número resolvido aqui, e o valor
 * efetivo vai para o JSONL com a origem (`extra`, `default`, `clamp`), para que
 * a célula possa provar qual orçamento realmente valeu.
 *
 * Faixa: [MIN_MS, MAX_MS]. Fora da faixa, o pedido é **recusado** em favor do
 * default — nunca se aceita um teto absurdo que mascare um job travado, nem um
 * teto minúsculo que transforme todo job em `hard_cap`.
 */
object SessionBudget {

    /** Menor orçamento aceito (5 s): abaixo disso não há job real a observar. */
    const val MIN_MS: Long = 5_000L

    /** Maior orçamento aceito (60 min): acima disso a bancada está travada. */
    const val MAX_MS: Long = 3_600_000L

    /**
     * Valor resolvido e a razão de tê-lo escolhido.
     *
     * @property ms orçamento efetivo em milissegundos.
     * @property source `extra`, `default`, `default_abaixo_min` ou `clamp_max`.
     */
    data class Resolved(val ms: Long, val source: String) {
        /** Linha pronta para o JSONL do laboratório. */
        fun describe(): String = "ms=$ms source=$source"
    }

    /**
     * Resolve o orçamento a partir do pedido e do default do cenário.
     *
     * @param extraMs valor vindo do Intent; `-1` (ou negativo) = não pedido.
     * @param defaultMs default do cenário; é coagido para a faixa válida.
     */
    fun resolve(extraMs: Long, defaultMs: Long): Resolved {
        val base = defaultMs.coerceIn(MIN_MS, MAX_MS)
        if (extraMs < 0L) return Resolved(base, "default")
        if (extraMs < MIN_MS) return Resolved(base, "default_abaixo_min")
        if (extraMs > MAX_MS) return Resolved(MAX_MS, "clamp_max")
        return Resolved(extraMs, "extra")
    }
}
