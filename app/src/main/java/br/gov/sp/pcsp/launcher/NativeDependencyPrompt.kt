package br.gov.sp.pcsp.launcher

import android.app.Activity
import androidx.appcompat.app.AlertDialog

/**
 * Diálogo de download do pacote nativo (somente UI).
 *
 * Download, verificação de SHA-256 e instalação são do [NativeDependencyManager].
 *
 * ⚠️ O pacote é UM `.zip`: o plano mostra o próprio ZIP como arquivo único (71 MB
 * no arm64), e não as 18 libs de dentro — listá-las mentiria sobre o que está
 * sendo baixado. O texto de antes ("aproximadamente 40 MB") era o número
 * congelado do pacote v1 e foi medido como 41% menor que o real.
 */
object NativeDependencyPrompt {
    fun showIfNeeded(activity: Activity) {
        if (NativeDependencyManager.activateIfInstalled(activity) || activity.isFinishing) return
        val plano = NativeDependencyManager.downloadPlan()
        // Diálogo com lista rolável: a lista não precisa caber inteira, o
        // usuário rola (é o mesmo padrão do log do SigUpdater no SIG Windows).
        DownloadPlanDialog.confirmar(
            activity = activity,
            plano = plano,
            negativo = "Agora não",
            positivo = "Baixar",
            prefacio = "O SIG precisa baixar seus componentes de áudio, vídeo, Whisper, NPU e transcrição local (Granite).\n\n" +
                "Isto acontece apenas uma vez — os arquivos continuam instalados nas próximas atualizações do APK.\n" +
                "Sem o download, várias ferramentas não funcionarão.",
        ) {
            install(activity, plano)
        }
    }

    /** Faz a instalação real com o diálogo de progresso (mesmo plano na lista). */
    private fun install(activity: Activity, plano: DownloadSizeFormat.Plano) {
        if (activity.isFinishing) return
        val progresso = DownloadPlanDialog.progresso(
            activity = activity,
            plano = plano,
            titulo = "Baixando componentes do SIG",
        )
        Thread {
            val result = NativeDependencyManager.install(activity) { state ->
                activity.runOnUiThread {
                    val percent = percentOf(state)
                    // ZIP = arquivo único: vira ✓ só quando o pacote chega inteiro.
                    val feitos = if (percent >= 100) plano.arquivos.map { it.nome }.toSet() else emptySet()
                    progresso.atualizar(state.downloaded, state.total, percent, feitos)
                }
            }
            activity.runOnUiThread {
                if (result.isSuccess) {
                    progresso.etapa("Componentes instalados. O SIG está pronto.")
                    // Deixa o ✓ e o total visíveis antes de o diálogo sumir.
                    progresso.fechar()
                } else {
                    val erro = result.exceptionOrNull()
                    progresso.etapa(
                        "Não foi possível instalar os componentes:\n${erro?.message ?: erro?.javaClass?.simpleName}"
                    )
                    progresso.fechar()
                    // O botão "Tentar novamente" reabre a confirmação.
                    AndroidAlert(
                        activity,
                        "Falha ao baixar os componentes",
                        erro?.message ?: erro?.javaClass?.simpleName ?: "Erro desconhecido.",
                    ) { showIfNeeded(activity) }
                }
            }
        }.start()
    }

    /**
     * Percentual 0..100 a partir do [NativeDependencyManager.Progress].
     *
     * Total desconhecido devolve -1 — a barra fica indeterminada, nunca "0%"
     * fingindo que não avançou.
     */
    private fun percentOf(state: NativeDependencyManager.Progress): Int {
        if (state.total <= 0L) return -1
        return ((state.downloaded.coerceAtMost(state.total) * 100L) / state.total).toInt()
    }

    /** Alerta simples com "Tentar novamente" / "Agora não". */
    private fun AndroidAlert(activity: Activity, titulo: String, mensagem: String?, aoTentar: () -> Unit) {
        if (activity.isFinishing) return
        AlertDialog.Builder(activity)
            .setTitle(titulo)
            .setMessage(mensagem ?: "Erro desconhecido.")
            .setNegativeButton("Agora não", null)
            .setPositiveButton("Tentar novamente") { _, _ -> aoTentar() }
            .setCancelable(false)
            .show()
    }
}
