package br.gov.sp.pcsp.launcher

/**
 * R16/B2 — POLÍTICA DE TERMINAL LIVE (pura, testável).
 *
 * Antes: `if (cancelled) CANCELLED else SUCCESS`. Todo desfecho não-cancelado
 * virava sucesso — inclusive silêncio sem texto, falha de reparo e região
 * pendente. O export então tratava parcial como sucesso completo.
 *
 * A decisão usa os FATOS do committer (`RegionOutcome`), nesta ordem:
 *   1. cancelado .................. CANCELLED (parcial válido preservado)
 *   2. qualquer pendência ......... ERROR (não é sucesso completo)
 *   3. nenhum texto comprometido .. NO_SPEECH (silêncio legítimo)
 *   4. caso normal ................ SUCCESS
 */
object LiveTerminalPolicy {

    /**
     * R17/B2 — snapshot terminal IMUTÁVEL com todos os fatos que decidem o
     * desfecho: cancelamento, regiões/reparos do committer **e** o que o
     * pipeline viu (chunk falho, perda no spool, amostras pendentes).
     *
     * O committer não enxerga `LiveChunkResult.Failed` (o adaptador conta em
     * `failedSamples` e não chama `onWindow`) — por isso esses fatos vêm de
     * fora e passam a pesar na decisão.
     */
    data class Facts(
        val cancelled: Boolean = false,
        val integral: Boolean = true,
        val repairFailed: Int = 0,
        val audioMissing: Int = 0,
        val noRepair: Int = 0,
        val invalidEvidence: Int = 0,
        val semTexto: Boolean = false,
        val failedSamples: Long = 0L,
        val lostSamples: Long = 0L,
        val pendingSamples: Long = 0L,
    ) {
        /** Falha do pipeline que o committer NÃO vê. */
        val falhaPipeline: Boolean
            get() = failedSamples > 0L || lostSamples > 0L ||
                (!cancelled && pendingSamples > 0L)

        /** Pendência de região/reparo no committer. */
        val pendenciaRegiao: Boolean
            get() = !integral || repairFailed > 0 || audioMissing > 0 ||
                noRepair > 0 || invalidEvidence > 0
    }

    fun decide(f: Facts): JobTerminalState {
        if (f.cancelled) return JobTerminalState.CANCELLED
        if (f.falhaPipeline) return JobTerminalState.ERROR
        if (f.pendenciaRegiao) return JobTerminalState.ERROR
        if (f.semTexto) return JobTerminalState.NO_SPEECH
        return JobTerminalState.SUCCESS
    }

    fun decide(
        cancelled: Boolean,
        integral: Boolean,
        repairFailed: Int,
        audioMissing: Int,
        noRepair: Int,
        invalidEvidence: Int,
        semTexto: Boolean,
    ): JobTerminalState = decide(
        Facts(
            cancelled = cancelled,
            integral = integral,
            repairFailed = repairFailed,
            audioMissing = audioMissing,
            noRepair = noRepair,
            invalidEvidence = invalidEvidence,
            semTexto = semTexto,
        )
    )

    /** Status nativo aceito pelo audit ("ok"/"cancelled"/"error"). */
    fun nativeStatus(terminal: JobTerminalState): String = when (terminal) {
        JobTerminalState.CANCELLED -> "cancelled"
        JobTerminalState.ERROR -> "error"
        else -> "ok"
    }

    /** Só sucesso completo é elegível para WER de sucesso. */
    fun werElegivelSucesso(terminal: JobTerminalState): Boolean =
        terminal == JobTerminalState.SUCCESS

    /** R17/B2 — rótulo curto para evento/telemetria (não decide nada). */
    fun motivo(f: Facts): String = when {
        f.cancelled -> "cancelado"
        f.falhaPipeline -> "falha_pipeline(failed=${f.failedSamples}," +
            "lost=${f.lostSamples},pending=${f.pendingSamples})"
        f.pendenciaRegiao -> "regiao_pendente"
        f.semTexto -> "sem_texto"
        else -> "completo"
    }
}
