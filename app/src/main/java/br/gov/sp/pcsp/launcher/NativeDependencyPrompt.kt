package br.gov.sp.pcsp.launcher

import android.app.Activity
import android.util.Log
import androidx.appcompat.app.AlertDialog

/**
 * Componentes nativos: aviso persistente + download manual.
 *
 * Download, verificação de SHA-256 e instalação são do [NativeDependencyManager].
 *
 * ⚠️ Correção de projeto (rodada 6): a versão anterior REEXIBIA o modal a cada
 * `onResume` enquanto o pacote faltasse. Isso fechava o caminho de volta, mas
 * impunha o modal repetido — o usuário que tocou "Agora não" voltava a vê-lo a
 * cada ida e volta ao app.
 *
 * O modelo agora:
 *  - o modal é MODAL e acontece uma vez, no `onCreate`;
 *  - a ausência do pacote fica visível de forma PERSISTENTE e não bloqueante,
 *    numa faixa discreta na tela principal, com ação de retry;
 *  - "Agora não" fecha o modal e a faixa permanece;
 *  - abrir uma ferramenta que depende do pacote explica o que falta e oferece
 *    a ação, sem interromper as demais;
 *  - `onResume` apenas ATUALIZA o estado da faixa. Não insiste no modal.
 *
 * ⚠️ O pacote é UM `.zip`: o plano mostra o próprio ZIP como arquivo único (43,8 MB
 * no arm64), e não as 18 libs de dentro — listá-las mentiria sobre o que está
 * sendo baixado. O texto de antes ("aproximadamente 40 MB") era o número
 * congelado do pacote v1 e foi medido como 41% menor que o real.
 */
object NativeDependencyPrompt {
    /**
     * TAG para o logcat. O sintoma deste bug era silencioso: o diálogo não
     * aparecia e o app continuava funcionando sem as libs nativas, sem
     * mensagem nenhuma. Cada guarda suprimida fica registrada aqui.
     */
    private const val TAG = "SigNativeDeps"

    /**
     * Atalho para tentar de novo o download a partir de QUALQUER Activity do
     * app, sem depender do ciclo de vida do MainActivity.
     *
     * BUG (27/09, achado no item 9 da rodada 4): [showIfNeeded] só era chamado
     * no fim do `onCreate` do MainActivity e saía cedo demais. Se o app
     * fechasse, fosse morto pelo sistema ou o usuário simplesmente
     * dispensasse o diálogo, não havia segunda chance: o `activateIfInstalled`
     * continuava falso e o diálogo nunca mais aparecia. O usuário ficava sem
     * componentes nativos e sem caminho para baixá-los pela tela principal.
     *
     * Agrava quando o `COMPONENT_VERSION` muda: quem tinha o pacote v8
     * instalado passa a exigir v9, o marker deixa de bater e o app entra
     * nesse estado sem nenhuma ação do usuário.
     *
     * Esta função é idempotente e pode ser chamada de qualquer Activity.
     */
    fun showIfNeeded(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) {
            // Não é mais uma condição de SUPRESSÃO silenciosa: registra e sai.
            Log.i(TAG, "showIfNeeded: Activity finishing/destroyed, adiado")
            return
        }
        if (sDialogAberto) {
            Log.i(TAG, "showIfNeeded: já há diálogo em aberto, ignorando")
            return
        }
        val instalado = NativeDependencyManager.activateIfInstalled(activity)
        Log.i(TAG, "showIfNeeded: activateIfInstalled=$instalado versao=${NativeDependencyManager.COMPONENT_VERSION}")
        sPendente = !instalado
        // A faixa acompanha o estado mesmo quando não há modal (já instalado).
        sFaixa?.invoke(!instalado)
        if (instalado) return

        val plano = NativeDependencyManager.downloadPlan()
        if (plano.arquivos.isEmpty() && plano.totalFallbackBytes <= 0L) {
            // Sem plano não há o que baixar (ABI não suportada?): registra em vez
            // de mostrar um diálogo vazio que não conduz a nada.
            Log.w(TAG, "showIfNeeded: plano vazio, nada a baixar")
            return
        }

        sDialogAberto = true
        DownloadPlanDialog.confirmar(
            activity = activity,
            plano = plano,
            negativo = "Agora não",
            positivo = "Baixar",
            prefacio = "O SIG precisa baixar seus componentes de áudio, vídeo, Whisper, NPU e transcrição local (Granite).\n\n" +
                "Isto acontece apenas uma vez — os arquivos continuam instalados nas próximas atualizações do APK.\n\n" +
                "Sem o download, várias ferramentas não funcionarão. Se preferir depois, o aviso " +
                "permanece na tela principal.",
        ) {
            sDialogAberto = false
            install(activity, plano)
        }
    }

    /**
     * Sincroniza a disponibilidade do pacote. É o que o `onResume` chama.
     *
     * NÃO abre o modal. Este é o ponto que muda em relação à versão anterior
     * (que se chamava [retryOnResume] e reabria o diálogo a cada volta): agora
     * a volta ao app apenas sincroniza a faixa persistente, sem interromper.
     *
     * Uma única transferência/instalação ativa por vez ([sInstalando]).
     */
    fun atualizarEstado(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        val instalado = NativeDependencyManager.activateIfInstalled(activity)
        sPendente = !instalado
        Log.i(TAG, "atualizarEstado: pendente=${!instalado} versao=${NativeDependencyManager.COMPONENT_VERSION}")
        sFaixa?.invoke(!instalado)
    }

    /** Verdadeiro enquanto o pacote nativo não estiver instalado. */
    fun pending(activity: Activity): Boolean {
        atualizarEstado(activity)
        return sPendente
    }

    /** Registra quem desenha a faixa. Passar `null` desregistra. */
    fun setFaixaListener(listener: ((Boolean) -> Unit)?) {
        sFaixa = listener
        listener?.invoke(sPendente)
    }

    /**
     * Retry a partir de qualquer ponto: faixa persistente, erro anterior, ou
     * ferramenta que depende do componente.
     *
     * Não abre modal de confirmação — o usuário já pediu. Vai direto ao
     * diálogo de progresso, que é cancelável e mostra o estado real.
     */
    fun retryNow(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (sInstalando) {
            Log.i(TAG, "retryNow: já há instalação em curso, ignorando")
            return
        }
        val plano = NativeDependencyManager.downloadPlan()
        if (plano.arquivos.isEmpty() && plano.totalFallbackBytes <= 0L) {
            alertaSimples(activity, "Componentes indisponíveis",
                "Não há pacote de componentes para a arquitetura deste aparelho.") {}
            return
        }
        install(activity, plano)
    }

    /**
     * Explica a dependência sem interromper as demais funções: chamado por uma
     * Activity que usa componentes nativos.
     *
     * O modal aqui é justificado — a função pedida não pode funcionar — mas
     * continua sendo a única ocorrência por tentativa, e "Agora não" não marca
     * o usuário como pendente para sempre: a faixa continua disponível.
     */
    fun explicarFalta(activity: Activity, aoTentar: () -> Unit) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (sInstalando) return
        if (NativeDependencyManager.activateIfInstalled(activity)) {
            sPendente = false
            sFaixa?.invoke(false)
            aoTentar()
            return
        }
        sPendente = true
        sFaixa?.invoke(true)
        if (sDialogAberto) return
        sDialogAberto = true
        alertaSimples(
            activity,
            "Esta ferramenta precisa dos componentes do SIG",
            "Os componentes de áudio, vídeo e transcrição local ainda não foram baixados " +
                "neste aparelho.\n\nO aviso também fica na tela principal, e você pode baixar quando quiser.",
        ) {
            sDialogAberto = false
            retryNow(activity)
        }
    }

    /**
     * Fecha/limpa a trava quando o diálogo é dispensado pelo usuário.
     *
     * "Agora não" NÃO marca nada como resolvido: a pendência continua e a faixa
     * permanece. A trava existe só para não empilhar dois modais.
     */
    fun onDialogDismissed() {
        sDialogAberto = false
    }

    /**
     * Verdadeiro enquanto o pacote nativo não estiver instalado.
     *
     * É o estado que a faixa persistente reflete. `pending()` atualiza antes
     * de devolver, para uma Activity que acabou de abrir não ler um valor
     * velho depois de um bump de `COMPONENT_VERSION`.
     */
    @Volatile
    private var sPendente = false

    /** Trava de reentrância: sem ela, dois `showIfNeeded` empilham diálogos. */
    private var sDialogAberto = false

    /** Uma única transferência/instalação ativa por vez. */
    @Volatile
    private var sInstalando = false

    /** Callback da Activity que desenha a faixa persistente. */
    @Volatile
    private var sFaixa: ((Boolean) -> Unit)? = null

    /** Faz a instalação real com o diálogo de progresso (mesmo plano na lista). */
    private fun install(activity: Activity, plano: DownloadSizeFormat.Plano) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (sInstalando) {
            Log.i(TAG, "install: já em curso, ignorando pedido duplicado")
            return
        }
        sInstalando = true
        val progresso = DownloadPlanDialog.progresso(
            activity = activity,
            plano = plano,
            titulo = "Baixando componentes do SIG",
        )
        Thread {
            val result = NativeDependencyManager.install(activity) { state ->
                activity.runOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                    val percent = percentOf(state)
                    // ZIP = arquivo único: vira ✓ só quando o pacote chega inteiro.
                    val feitos = if (percent >= 100) plano.arquivos.map { it.nome }.toSet() else emptySet()
                    progresso.atualizar(state.downloaded, state.total, percent, feitos)
                }
            }
            activity.runOnUiThread {
                sInstalando = false
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                if (result.isSuccess) {
                    progresso.etapa("Componentes instalados. O SIG está pronto.")
                    // Deixa o ✓ e o total visíveis antes do diálogo sumir.
                    progresso.fechar()
                    sPendente = false
                    sFaixa?.invoke(false)
                } else {
                    val erro = result.exceptionOrNull()
                    val msg = erro?.message ?: erro?.javaClass?.simpleName ?: "Erro desconhecido."
                    progresso.etapa("Não foi possível instalar os componentes:\n$msg")
                    progresso.fechar()
                    // Falha mantém a pendência e a faixa; o retry fica à mão.
                    sPendente = true
                    sFaixa?.invoke(true)
                    alertaSimples(
                        activity,
                        "Falha ao baixar os componentes",
                        "$msg\n\nO aviso na tela principal continua disponível para tentar de novo.",
                    ) { retryNow(activity) }
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

    /** Alerta com "Tentar novamente" / "Agora não", respeitando o descarte. */
    private fun alertaSimples(
        activity: Activity,
        titulo: String,
        mensagem: String?,
        aoTentar: () -> Unit,
    ) {
        if (activity.isFinishing || activity.isDestroyed) return
        AlertDialog.Builder(activity)
            .setTitle(titulo)
            .setMessage(mensagem ?: "Erro desconhecido.")
            .setNegativeButton("Agora não") { _, _ -> sDialogAberto = false }
            .setPositiveButton("Tentar novamente") { _, _ -> aoTentar() }
            .setOnCancelListener { sDialogAberto = false }
            .show()
    }

    /**
     * Descrição curta do estado, para o log e para a faixa.
     *
     * A faixa em si é `res/layout/view_sig_native_deps_banner.xml` — a UI
     * fica no XML, o estado e a ação ficam aqui. Não duplicar a faixa em
     * código: as duas cópias divergiriam em texto e cor.
     */
    fun textoFaixa(): String =
        "Componentes do SIG não baixados — várias ferramentas não vão funcionar."
}
