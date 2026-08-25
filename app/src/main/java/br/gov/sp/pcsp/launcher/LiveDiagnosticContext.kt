package br.gov.sp.pcsp.launcher

import java.util.UUID

/** Correlação reader-safe de uma sessão STT ao vivo.
 *
 * O identificador não contém provedor, usuário, áudio ou credencial. A
 * sequência registra somente estados de ciclo de vida para ligar um artefato
 * de diagnóstico ao resultado da mesma sessão.
 */
class LiveDiagnosticContext(
    val runId: String,
    val provider: String,
) {
    private val states = mutableListOf<String>()

    @Synchronized
    fun recordState(state: String) {
        val normalized = state.trim().uppercase()
        if (normalized.isNotBlank()) states += normalized
    }

    @Synchronized
    fun stateSequence(): List<String> = states.toList()

    /** Indica que uma tentativa de reconexão chegou a uma conexão recuperada. */
    @Synchronized
    fun recoveryObserved(): Boolean {
        var reconnecting = false
        for (state in states) {
            when (state) {
                "RECONNECTING" -> reconnecting = true
                "RECONNECTED" -> if (reconnecting) return true
            }
        }
        return false
    }

    /** Último resultado que encerra ou perde o ciclo live, quando existente. */
    @Synchronized
    fun terminalOutcome(): String? = states.lastOrNull { it in TERMINAL_STATES }

    @Synchronized
    fun correlationText(): String = buildString {
        append("live_run_id=").append(runId).append('\n')
        append("provider=").append(provider).append('\n')
        append("state_sequence=").append(states.joinToString(">"))
            .append('\n')
        append("recovery_observed=").append(recoveryObserved()).append('\n')
        append("terminal_outcome=").append(terminalOutcome() ?: "NONE").append('\n')
    }

    @Synchronized
    fun correlation(): Map<String, String> = linkedMapOf(
        "live_run_id" to runId,
        "provider" to provider,
        "state_sequence" to states.joinToString(">"),
        "recovery_observed" to recoveryObserved().toString(),
        "terminal_outcome" to (terminalOutcome() ?: "NONE"),
    )

    companion object {
        private val TERMINAL_STATES = setOf(
            "DONE",
            "FAILED",
            "RECONNECT_FAILED",
            "AUDIO_LOST",
        )

        fun create(provider: String): LiveDiagnosticContext = LiveDiagnosticContext(
            runId = "live-" + UUID.randomUUID().toString().replace("-", "").take(12),
            provider = provider,
        )
    }
}
