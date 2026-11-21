package br.gov.sp.pcsp.launcher

/**
 * Resumo legível ao fim de uma transcrição (adendo R10 §5).
 *
 * Regras que este arquivo garante (e que a UI apenas exibe):
 *  - **Modelo**: o efetivamente usado no job, congelado no encerramento.
 *  - **Hardware**: rótulo da execução efetiva informado pelo motor.
 *  - **Quantidade**: fontes concluídas (um streaming = uma fonte), não janelas,
 *    segmentos ou tokens. Em lote, separa concluídos de total.
 *  - **Duração**: áudio efetivamente processado, somado sem overlaps/padding.
 *  - **Tempo**: relógio monotônico, marcos de processamento; `null` quando a rota
 *    não permite medir com fidelidade (vira "indisponível", nunca número inventado).
 *  - **Eficiência**: duração / tempo, até três casas, sem NaN/Infinity/divisão por
 *    zero; derivada, nunca acumulada de tarefas sobrepostas.
 *  - **Status**: Concluído, Parcial, Cancelado ou Erro — sempre com os dados
 *    realmente disponíveis.
 */
data class WhisperResumo(
    val modelo: String?,
    val hardware: String,
    val concluidos: Int,
    val total: Int,
    val duracaoAudioMs: Long?,
    val tempoProcessamentoMs: Long?,
    val status: WhisperResumoStatus,
    val streaming: Boolean = false,
) {

    /** Eficiência declarada, ou `null` quando falta dado confiável. */
    val eficiencia: Double?
        get() {
            val dur = duracaoAudioMs ?: return null
            val tempo = tempoProcessamentoMs ?: return null
            if (tempo <= 0L || dur <= 0L) return null
            return dur.toDouble() / tempo.toDouble()
        }

    /** Linhas exatamente na ordem do exemplo da ordem, com indisponíveis explícitos. */
    fun linhas(): List<String> {
        val qtd = if (total > concluidos) "$concluidos de $total" else concluidos.toString()
        val qtdComStreaming = if (streaming) "$qtd (Streaming)" else qtd
        return listOf(
            "Modelo: ${modelo?.takeIf { it.isNotBlank() } ?: INDISPONIVEL}",
            "Hardware: ${hardware.takeIf { it.isNotBlank() } ?: INDISPONIVEL}",
            "Áudios transcritos: $qtdComStreaming",
            "Total de áudio transcrito: ${formatarDuracao(duracaoAudioMs)}",
            "Tempo de processamento: ${formatarTempo(tempoProcessamentoMs)}",
            "Eficiência: ${formatarEficiencia(eficiencia)}",
            "Status: ${status.rotulo}",
        )
    }

    fun texto(): String = linhas().joinToString("\n")

    companion object {
        const val INDISPONIVEL = "indisponível"

        /**
         * Hardware a partir da identidade **tipada** da rota (`WhisperBackend`:
         * CPU, GPU+Vulkan, GPU+OpenCL). Rótulo desconhecido ou genérico NÃO é
         * ecoado nem promovido: vira `indisponível` (nunca "npu" por eco, nunca
         * "gpu" genérico virando Vulkan).
         */
        fun hardwareDe(motor: String?): String {
            val bruto = motor?.trim().orEmpty()
            if (bruto.isEmpty()) return INDISPONIVEL
            val m = bruto.lowercase()
            return when {
                m == "cpu" -> "CPU"
                // R14/§1.1 — o recibo ORT guarda o hardware EFETIVO do job, que pode
                // trazer detalhe ("CPU (6 threads)"). Preserva o sufixo em vez de
                // jogar tudo para "indisponível".
                m.startsWith("cpu ") -> "CPU" + bruto.substring(3)
                m == "gpu+vulkan" || m == "vulkan" || m == "gpu (vulkan)" -> "GPU (Vulkan)"
                m == "gpu+opencl" || m == "opencl" || m == "gpu (opencl)" -> "GPU (OpenCL)"
                // R16/§5 — híbrido do laboratório: encoder QNN GPU + decoder CPU.
                // Preserva o rótulo completo (identidade por estágio, não é "GPU" puro).
                m.startsWith("gpu (qnn") -> bruto
                m == "npu→cpu" -> "NPU→CPU"
                m == "htp w8a16" -> "NPU (HTP W8A16)"
                else -> INDISPONIVEL
            }
        }

        /** mm:ss para durações de áudio; acima de 1 h vira h:mm:ss. */
        fun formatarDuracao(ms: Long?): String {
            if (ms == null || ms < 0) return INDISPONIVEL
            val totalS = ms / 1000.0
            // o exemplo da ordem mostra 60 s (não 1:00): segundos até 60 s inclusive
            if (totalS <= 60.0) return formatarSegundos(totalS)
            val s = (ms + 500) / 1000
            val h = s / 3600
            val m = (s % 3600) / 60
            val seg = s % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, seg)
            else "%d:%02d".format(m, seg)
        }

        /** Tempo de processamento com uma casa até 10 s, senão segundos inteiros. */
        fun formatarTempo(ms: Long?): String {
            if (ms == null || ms < 0) return INDISPONIVEL
            val s = ms / 1000.0
            return if (s < 10.0) "%.1f s".format(java.util.Locale.US, s).replace('.', ',')
            else "${Math.round(s)} s"
        }

        private fun formatarSegundos(s: Double): String {
            val txt = if (s < 10.0) "%.1f".format(java.util.Locale.US, s).replace('.', ',')
            else "${Math.round(s)}"
            return "$txt s"
        }

        /**
         * Até três casas, separador decimal vírgula, sem NaN/Infinity.
         * Ex.: 60/20 = "3,000"; 40/30 = "1,333".
         */
        fun formatarEficiencia(v: Double?): String {
            if (v == null || v.isNaN() || v.isInfinite() || v <= 0.0) return INDISPONIVEL
            val texto = String.format(java.util.Locale.US, "%.3f", v).replace('.', ',')
            return "${texto}x"
        }
    }
}

enum class WhisperResumoStatus(val rotulo: String) {
    CONCLUIDO("Concluído"),
    PARCIAL("Parcial"),
    CANCELADO("Cancelado"),
    ERRO("Erro"),
}
