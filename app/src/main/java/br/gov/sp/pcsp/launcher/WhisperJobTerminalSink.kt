package br.gov.sp.pcsp.launcher

/**
 * R56/B01-B07 — SINK TERMINAL por JOB, com identidade IMUTÁVEL.
 *
 * Por que existe (adendo8 §1-2, nota9, adendo checkpoint10 §1):
 *  - no C3 `r56b02c-cancel-open-1`, o job `act1.job1.gen1` publicou
 *    `live_terminal` cancelled e o Service registrou `job_owner_gone_sem_ack` e
 *    `job_stuck(terminal_sem_ack)`: o ACK estava preso ao caminho de UI
 *    (`completeLiveMicStop` → `runOnUiThread`), que é descartado quando a
 *    Activity morre;
 *  - o campo mutável `liveMicGeneration` da Activity não serve como identidade:
 *    num job novo ele pode ainda ser 0 no ponto de término, e "lembrar a última
 *    geração" não prova a cadeia A→C→A nem detecta falha de encaminhamento.
 *
 * Contrato:
 *  - a geração é capturada no start/admissão e NUNCA relida de campo mutável;
 *  - `concluir` é idempotente por INSTÂNCIA (um job, um terminal);
 *  - o emissor recebe SEMPRE a geração desta instância — é impossível liberar
 *    dono alheio por acidente;
 *  - falha do emissor fica OBSERVÁVEL (`falhaEncaminhamento`) e o job NÃO é
 *    marcado como concluído — sem ACK fabricado;
 *  - nada aqui depende de Activity viva nem de Runnable da main: quem chama é o
 *    worker, depois do término real e do dreno.
 *
 * O que este seam NÃO faz: decidir se a inferência terminou. Quem chama deve
 * comprovar o dreno (ou publicar UNKNOWN/PENDING e conservar a proteção).
 */
class WhisperJobTerminalSink(
    val geracao: Long,
    private val emitir: (Long, String) -> Boolean,
) {

    private var concluido = false

    /** Falha de encaminhamento observável (emissor falso para esta etapa). */
    var falhaEncaminhamento: Boolean = false
        private set

    /** Último desfecho aceito, para recibo/diagnóstico. */
    var desfechoConfirmado: String? = null
        private set

    /**
     * Emite o terminal DESTE job uma única vez.
     *
     * @return true se este chamada emitiu; false se já havia concluído, se a
     * geração é inválida ou se o emissor falhou (falha fica registrada).
     */
    @Synchronized
    fun concluir(desfecho: String): Boolean {
        if (concluido) return false
        if (geracao <= 0L) {
            falhaEncaminhamento = true
            return false
        }
        val ok = runCatching { emitir(geracao, desfecho) }.getOrDefault(false)
        if (!ok) {
            falhaEncaminhamento = true
            return false
        }
        desfechoConfirmado = desfecho
        concluido = true
        return true
    }

    @Synchronized
    fun concluido(): Boolean = concluido
}
