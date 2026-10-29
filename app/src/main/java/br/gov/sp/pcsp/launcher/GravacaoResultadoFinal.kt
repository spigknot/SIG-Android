package br.gov.sp.pcsp.launcher

/**
 * R56/adendo19 — finalização do resultado da GRAVAÇÃO, exatamente uma vez.
 *
 * O defeito delimitado no adendo19: `transcribeRecordedWav` abria o job e, nos
 * caminhos de falha ANTES do parse (carga de modelo falhou; exceção no worker),
 * apenas postava erro e retornava — o `finally` mandava ACK ERROR, mas nenhum
 * resultado terminal era publicado. O job admitido ficava sem payload próprio.
 *
 * Esta classe concentra a publicação:
 *  - guarda a identidade capturada na ADMISSÃO (`jobId`/`owner`), então uma
 *    recusa não cria resultado de outra geração;
 *  - publica no máximo uma vez por instância, e só confirma depois que o
 *    ledger aceitou (segundo terminal é ignorado pelo próprio ledger);
 *  - não toca em UI: publicar funciona com a fila de UI retida ou a Activity
 *    destruída.
 *
 * Sem dependência de Android — é exercitável em JVM, como exige o adendo.
 */
class GravacaoResultadoFinal(
    private val ledger: TranscriptionJobLedger,
    val jobId: Long,
    val owner: Long,
) {
    /** Terminal efetivamente aceito pelo ledger; `null` = ainda não publicado. */
    var publicado: JobTerminalState? = null
        private set

    /** Quantas tentativas de publicação foram feitas (diagnóstico do chamador). */
    var tentativas: Int = 0
        private set

    /** `true` somente quando ESTA chamada gravou o terminal no ledger. */
    fun publicar(terminal: JobTerminalState, nativeStatus: String): Boolean {
        if (publicado != null) return false
        tentativas += 1
        val resultado = ledger.publishJob(jobId, owner, terminal, nativeStatus)
            ?: return false
        publicado = terminal
        return resultado.terminal == terminal
    }

    /** Terminal do ACK correspondente (NoSpeech continua SUCCESS para o Service). */
    fun terminalDeAck(): String = when (publicado) {
        JobTerminalState.SUCCESS, JobTerminalState.NO_SPEECH -> "SUCCESS"
        JobTerminalState.CANCELLED -> "CANCELLED"
        JobTerminalState.ERROR, null -> "ERROR"
    }
}
