package br.gov.sp.pcsp.launcher

/**
 * R56/adendo20 — política CENTRAL de disponibilidade dos backends do whisper.cpp.
 *
 * Decisão do usuário (29/10/2026): OpenCL fica **temporariamente desativado** no
 * whisper.cpp. CPU e Vulkan continuam disponíveis. A pausa é REVERSÍVEL:
 *
 *  - o código, os kernels e o valor nativo 2 são preservados;
 *  - a numeração CPU=0 / Vulkan=1 / OpenCL=2 **não** é reindexada;
 *  - bibliotecas compartilhadas de outros motores (Granite, QNN) não são tocadas.
 *
 * Um pedido explícito do backend 2 é RECUSADO de forma tipada com
 * [MOTIVO_OPENCL] — nunca convertido silenciosamente em CPU, porque isso
 * registraria uma execução como OpenCL quando ela rodou em outro backend.
 * Para preferência/configuração antiga, [resolver] devolve uma opção disponível
 * e o chamador informa o backend efetivo.
 *
 * Sem dependência de Android: exercitável em JVM.
 */
object WhisperBackendDisponibilidade {
    /** Motivo tipado da recusa; é o texto que aparece no relatório e na UI. */
    const val MOTIVO_OPENCL = "temporariamente desativado"

    /** Valores `nativeKind` disponíveis hoje. Ordem = ordem de exibição. */
    val ativos: List<Int> = listOf(0, 1)

    /** `null` = disponível; texto = motivo tipado da indisponibilidade. */
    fun motivoIndisponivel(nativeKind: Int): String? = when (nativeKind) {
        2 -> MOTIVO_OPENCL
        in ativos -> null
        else -> "backend desconhecido"
    }

    fun disponivel(nativeKind: Int): Boolean = motivoIndisponivel(nativeKind) == null

    /** Resolve uma preferência antiga para um backend efetivamente disponível. */
    fun resolver(nativeKind: Int): Int = if (disponivel(nativeKind)) nativeKind else 0
}
