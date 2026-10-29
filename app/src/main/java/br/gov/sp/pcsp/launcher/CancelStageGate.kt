package br.gov.sp.pcsp.launcher

/**
 * R16/D2 — porta de cancelamento por estágio REAL observado no terminal.
 *
 * Função pura (testável no host): dado o texto do terminal e um limiar,
 * decide se o estágio de cancelamento foi atingido e qual rótulo registrar.
 *
 * - `min = 0` (legado R10/B3): qualquer "progress " ou
 *   "calling whisper_full_parallel" arma o cancel.
 * - `min > 0` (R16/D2 meio/fim): exige "progress N%" com N >= min.
 *
 * Retorna `null` quando o estágio ainda não foi observado.
 */
object CancelStageGate {
    private val PROGRESS_RE = Regex("""progress (\d+)%""")

    /** Maior progresso N observado em "progress N%", ou null se nenhum. */
    fun maxProgress(terminalText: String): Int? =
        PROGRESS_RE.findAll(terminalText)
            .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
            .maxOrNull()

    fun stageFor(terminalText: String, min: Int): String? {
        if (min > 0) {
            val prog = maxProgress(terminalText) ?: return null
            return if (prog >= min) "progress_ge_$min" else null
        }
        return when {
            terminalText.contains("progress ") -> "progress"
            terminalText.contains("calling whisper_full_parallel") -> "calling_full"
            else -> null
        }
    }
}
