package br.gov.sp.pcsp.launcher.experimental.npu

import br.gov.sp.pcsp.launcher.NativeDependencyManager
import br.gov.sp.pcsp.launcher.SigApplication

internal object NpuNativeProbe {
    private val loadError: Throwable? = runCatching {
        NativeDependencyManager.loadLibrary(SigApplication.appInstance, "sig_npu_probe")
    }.exceptionOrNull()

    private external fun diagnose(): String

    fun diagnosticReport(): String {
        return loadError?.let {
            "Ponte nativa NPU indisponível: ${it.message ?: it.javaClass.simpleName}\n" +
                "Backend HTP inicializado: não\nExecução confirmada no HTP: não"
        } ?: runCatching { diagnose() }.getOrElse {
            "Falha controlada no diagnóstico NPU: ${it.message ?: it.javaClass.simpleName}\n" +
                "Backend HTP inicializado: não\nExecução confirmada no HTP: não"
        }
    }
}
