package br.gov.sp.pcsp.launcher

/** Fluxo assíncrono da AssemblyAI — seam PURO e testável (sem UI/Activity).
 *
 * A Activity injeta o `fetchStatus` (o HTTP real) e o `isCancelled`; este
 * objeto concentra a decisão sync/async, o loop de polling e o orçamento
 * terminal (F1/F2 do Better Harness). Nenhum segredo ou corpo de áudio passa
 * por aqui.
 */
object AssemblyAiAsyncFlow {
    /** Arquivos com 2 minutos ou mais vão para o fluxo assíncrono. */
    const val ASYNC_THRESHOLD_MILLIS = 120000L

    /** Intervalo entre consultas (espelhado na mensagem de status da Activity). */
    const val POLL_INTERVAL_MILLIS = 3000L

    /** Orçamento padrão do polling: 30 minutos (termina com expiração diagnóstica). */
    const val POLL_BUDGET_MILLIS = 30 * 60 * 1000L

    /** Fatia do sleep: o cancelamento é verificado a cada fatia. */
    const val POLL_SLEEP_SLICE_MILLIS = 500L

    sealed class PollOutcome {
        data class Completed(val text: String) : PollOutcome()
        data class Failed(val message: String) : PollOutcome()
        data class Cancelled(val attempts: Int) : PollOutcome()
        data class TimedOut(val attempts: Int) : PollOutcome()
    }

    /** Decisão sync vs async: a duração REAL do áudio preparado (ms). */
    fun shouldUseAsync(durationMs: Long): Boolean = durationMs >= ASYNC_THRESHOLD_MILLIS

    /** Loop de polling puro: toda execução termina em Completed, Failed,
     *  Cancelled ou TimedOut. Exceções do fetch PROPAGAM (falha remota). */
    fun pollUntilTerminal(
        fetchStatus: () -> Triple<String, String, String>,
        isCancelled: () -> Boolean,
        budgetMillis: Long = POLL_BUDGET_MILLIS,
        sleep: (Long) -> Unit = { Thread.sleep(it) },
        onAttempt: ((Int) -> Unit)? = null,
    ): PollOutcome {
        val deadline = System.currentTimeMillis() + budgetMillis
        var attempt = 0
        while (true) {
            if (isCancelled()) return PollOutcome.Cancelled(attempt)
            attempt++
            onAttempt?.invoke(attempt)
            val (statusName, text, error) = fetchStatus()
            when (statusName) {
                "completed" -> {
                    if (text.isBlank()) {
                        return PollOutcome.Failed(
                            "A AssemblyAI retornou uma transcrição vazia (async)."
                        )
                    }
                    return PollOutcome.Completed(text)
                }
                "error" -> return PollOutcome.Failed(
                    "AssemblyAI async falhou: ${error.ifBlank { "erro desconhecido" }}"
                )
            }
            if (System.currentTimeMillis() >= deadline) {
                return PollOutcome.TimedOut(attempt)
            }
            var waited = 0L
            while (waited < POLL_INTERVAL_MILLIS) {
                if (isCancelled()) return PollOutcome.Cancelled(attempt)
                sleep(POLL_SLEEP_SLICE_MILLIS)
                waited += POLL_SLEEP_SLICE_MILLIS
            }
        }
    }

    /** Grava os artefatos de diagnóstico; NÃO engole falhas de escrita —
     *  retorna os avisos para o chamador registrar de forma observável. */
    fun writeDiagnostics(
        sessionDir: java.io.File,
        correlation: Map<String, String>,
        terminalSnapshot: String,
        logSnapshot: String,
        message: String,
    ): List<String> {
        val warnings = mutableListOf<String>()
        try {
            java.io.File(sessionDir, "terminal.txt")
                .writeText(terminalSnapshot, Charsets.UTF_8)
            java.io.File(sessionDir, "log.txt")
                .writeText(logSnapshot + "\n" + message + "\n", Charsets.UTF_8)
            if (correlation.isNotEmpty()) {
                val body = correlation.entries
                    .joinToString("\n") { "${it.key}=${it.value}" } + "\n"
                java.io.File(sessionDir, "correlation.txt")
                    .writeText(body, Charsets.UTF_8)
            }
        } catch (error: Throwable) {
            warnings += "não foi possível gravar os arquivos de diagnóstico: ${error.message}"
        }
        return warnings
    }
}
