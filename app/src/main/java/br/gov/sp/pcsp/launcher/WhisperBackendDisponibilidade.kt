package br.gov.sp.pcsp.launcher

/** Native kinds for Whisper.cpp. Model and device safety checks remain in the native loader. */
object WhisperBackendDisponibilidade {
    val ativos: List<Int> = listOf(0, 2, 1)

    fun motivoIndisponivel(nativeKind: Int): String? =
        if (nativeKind in ativos) null else "backend desconhecido"

    fun disponivel(nativeKind: Int): Boolean = motivoIndisponivel(nativeKind) == null

    /** Resolve uma preferência antiga para um backend efetivamente disponível. */
    fun resolver(nativeKind: Int): Int = if (disponivel(nativeKind)) nativeKind else 0
}
