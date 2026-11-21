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

    fun effective(
        saved: Hardware?,
        admitted: Boolean,
        turbo: Boolean,
        verifiedVersion: Int,
    ): Hardware {
        val requested = saved ?: Hardware.NPU
        return if (choice(requested, admitted, turbo, verifiedVersion) == Choice.READY) requested
        else Hardware.CPU
    }
}
