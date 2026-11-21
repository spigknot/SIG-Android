package br.gov.sp.pcsp.launcher

/** Preferências e disponibilidade dos seletores de motor e hardware do Whisper. */
internal object WhisperSelectors {
    enum class Hardware(val label: String, val route: String) {
        CPU("CPU", "cpu"),
        HYBRID("NPU→CPU", "htp-cpu"),
        NPU("NPU", "htp");

        companion object {
            fun fromPreference(value: String?) = entries.firstOrNull { it.name == value }
        }
    }

    enum class Choice(val reason: String? = null) {
        READY,
        DOWNLOAD,
        UNSUPPORTED_SOC("não disponível neste aparelho"),
        UNSUPPORTED_MODEL("só no large-v3-turbo"),
    }

    fun useOrt(cpp: Boolean?, legacyOrt: Boolean?): Boolean = cpp?.not() ?: legacyOrt ?: true

    fun flashAttention(useOrt: Boolean, saved: Boolean): Boolean = !useOrt && saved

    fun choice(
        hardware: Hardware,
        admitted: Boolean,
        turbo: Boolean,
        verifiedVersion: Int,
    ): Choice = when {
        hardware == Hardware.CPU -> Choice.READY
        !admitted -> Choice.UNSUPPORTED_SOC
        !turbo -> Choice.UNSUPPORTED_MODEL
        verifiedVersion >= if (hardware == Hardware.NPU) 3 else 2 -> Choice.READY
        else -> Choice.DOWNLOAD
    }

    /**
     * Hardware EFETIVO da tela.
     *
     * R123/T01 — sem escolha salva, o padrão é a melhor rota PRONTA, na ordem
     * `NPU` (v3) → `NPU→CPU` (v2 ou v3) → `CPU`. Antes o padrão era `NPU` e o
     * pacote v2 caía em `CPU`: a 81 usa o encoder na HTP com o v2, e a 82
     * tiraria a NPU de quem ainda está no v2, sem aviso (revisão R122 §3).
     *
     * Com escolha salva, a escolha vale se estiver pronta; senão, `CPU` — como
     * antes, para não surpreender quem escolheu.
     */
    fun effective(
        saved: Hardware?,
        admitted: Boolean,
        turbo: Boolean,
        verifiedVersion: Int,
    ): Hardware {
        if (saved != null) {
            return if (choice(saved, admitted, turbo, verifiedVersion) == Choice.READY) saved
            else Hardware.CPU
        }
        return PADRAO_SEM_ESCOLHA.firstOrNull {
            choice(it, admitted, turbo, verifiedVersion) == Choice.READY
        } ?: Hardware.CPU
    }

    /** Ordem do padrão sem escolha salva: a melhor rota pronta primeiro. */
    private val PADRAO_SEM_ESCOLHA = listOf(Hardware.NPU, Hardware.HYBRID, Hardware.CPU)
}
