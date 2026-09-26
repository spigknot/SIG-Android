package br.gov.sp.pcsp.launcher

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import kotlin.math.roundToInt

/**
 * Diálogos de download do SIG (só UI; o download e a verificação são dos
 * gerenciadores).
 *
 * Todos abrem a MESMA estrutura, para o usuário reconhecer o padrão em qualquer
 * ferramenta:
 *
 *  1. **Confirmação**: cabeçalho (N arquivos · total) + a lista rolável de cada
 *     arquivo com o tamanho. É a lista do [DownloadSizeFormat.Plano], e o corte
 *     para caber é feito aqui (altura limitada), nunca agrupando "outros N".
 *  2. **Progresso**: a mesma lista, que vai sendo "colada" conforme o download
 *     avança (marcando o que já terminou), com a barra e a linha
 *     "Baixando: 68% (45,2 MB de 67,7 MB)" no topo.
 *
 * ⚠️ ZIP (o pacote nativo, o QAIRT e o APK de atualização) é tratado como
 * **arquivo único**: o app baixa UM .zip e extrai. Listar as libs de dentro seria
 * mentir sobre o que está sendo baixado, então esses planos trazem um item só,
 * com o tamanho do ZIP.
 */
object DownloadPlanDialog {

    /**
     * Confirmação do download: mostra o plano completo e o total.
     *
     * @param plano o que será baixado; um `Plano` sem itens vira "arquivo único"
     * @param prefacio texto ANTES do plano (explicação do que é o download)
     * @param sufixo texto DEPOIS do plano (aviso, pergunta final)
     * @param positivo rótulo do botão de ação
     * @param aoConfirmar callback do botão (o download é do chamador)
     */
    fun confirmar(
        activity: Activity,
        plano: DownloadSizeFormat.Plano,
        positivo: String = "Baixar",
        negativo: String = "Agora não",
        prefacio: String? = null,
        sufixo: String? = null,
        aoNegar: (() -> Unit)? = null,
        aoConfirmar: () -> Unit,
    ) {
        if (activity.isFinishing) return
        val corpo = corpo(activity, plano, comProgresso = false, prefacio = prefacio, sufixo = sufixo)
        val dialog = AlertDialog.Builder(activity)
            .setTitle(plano.rotulo)
            .setView(corpo.view)
            .setNegativeButton(negativo) { _, _ -> aoNegar?.invoke() }
            .setPositiveButton(positivo, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                dialog.dismiss()
                aoConfirmar()
            }
        }
        dialog.show()
    }

    /**
     * Diálogo de progresso com a lista que acumula.
     *
     * @param concluidos nomes já baixados (para marcar `✓` na lista)
     */
    fun progresso(
        activity: Activity,
        plano: DownloadSizeFormat.Plano,
        titulo: String = plano.rotulo,
        cancelavel: Boolean = false,
    ): Progresso {
        if (activity.isFinishing) return Progresso(null, null, plano)
        val corpo = corpo(activity, plano, comProgresso = true)
        val dialog = AlertDialog.Builder(activity)
            .setTitle(titulo)
            .setView(corpo.view)
            .setCancelable(cancelavel)
            // Botão "Continuar" desde o início: fica desabilitado durante o
            // download (senão o usuário perderia um pacote pela metade) e é
            // liberado por [Progresso.concluirContinuando] no fim, para ele poder
            // ler a lista e o log antes de seguir.
            .setPositiveButton("Continuar", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = false
        }
        dialog.show()
        return Progresso(dialog, corpo, plano)
    }

    /**
     * Tudo que a Activity precisa para desenhar o progresso.
     *
     * Guarda as views, não o estado: quem chama é a thread de download e quem
     * escreve na tela é a UI thread, então [atualizar] só pinta.
     */
    class Progresso(
        private val dialog: AlertDialog?,
        private val corpo: Corpo?,
        val plano: DownloadSizeFormat.Plano,
    ) {
        /**
         * Redesenha a linha de progresso e marca na lista o que já baixou.
         *
         * @param percentual 0..100, ou -1 quando o total é desconhecido
         * @param concluidos nomes dos arquivos já baixados
         */
        fun atualizar(downloaded: Long, total: Long, percentual: Int, concluidos: Set<String> = emptySet()) {
            atualizarComLinha(DownloadSizeFormat.progresso(downloaded, total, percentual), downloaded, total, percentual, concluidos)
        }

        /**
         * Igual a [atualizar], mas com a LINHA de texto escolhida pelo chamador.
         *
         * É o que permite a linha do SigUpdater "Baixando arquivo 7/25: x.onnx (12%)"
         * no lugar do agregado, sem duplicar a lógica de barra/lista/log.
         */
        fun atualizarComLinha(
            linha: String,
            downloaded: Long,
            total: Long,
            percentual: Int,
            concluidos: Set<String> = emptySet(),
        ) {
            val corpo = corpo ?: return
            corpo.status.text = linha
            corpo.bar?.let { barra ->
                if (percentual >= 0) {
                    barra.isIndeterminate = false
                    barra.max = 100
                    barra.progress = percentual.coerceIn(0, 100)
                } else {
                    barra.isIndeterminate = true
                }
            }
            // A lista acumula: o que já baixou vira `✓` e o texto é repintado.
            val itens = plano.arquivos.map { it.copy(jaBaixado = it.jaBaixado || it.nome in concluidos) }
            corpo.lista.text = DownloadSizeFormat.linhas(itens)
            // Cada arquivo CONCLUÍDO vira uma linha no log acima da lista (o "colando
            // conforme baixa"); no fim o usuário rola para cima e lê a sequência.
            for (nome in concluidos) {
                if (jaMarcados.add(nome)) {
                    val item = itens.firstOrNull { it.nome == nome } ?: plano.arquivos.firstOrNull { it.nome == nome }
                    if (item != null) {
                        log.concluido(item.nome, item.bytes)
                        corpo.log.text = log.texto()
                    }
                }
            }
        }

        private val jaMarcados = mutableSetOf<String>()
        private val log = DownloadSizeFormat.Log()

        /**
         * Mantém a etapa visível até o próximo [atualizar] (o "Validando pacote"
         * do instalador não é progresso, é status).
         */
        fun etapa(texto: String) {
            corpo?.status?.text = texto
        }

        /** Fecha o diálogo. */
        fun fechar() {
            dialog?.dismiss()
        }

        /**
         * Deixa a tela aberta no estado final para o usuário LER antes de sair.
         *
         * O pedido é explícito: "no final, o usuário pode rolar pra cima e ler tudo
         * antes de continuar". Fechar o diálogo no `atualizar(100)` apagaria a lista
         * e o log justamente quando eles ficaram interestinges. Aí o botão vira
         * "Continuar" e o toque fecha.
         */
        fun concluirContinuando(totalBaixado: Long = 0L) {
            val corpo = corpo ?: run { fechar(); return }
            log.fim(totalBaixado)
            corpo.log.text = log.texto()
            corpo.status.text = "Download concluído."
            corpo.bar?.let { it.progress = it.max }
            // A barra some: ela já cumpriu o papel e roubaria altura da lista.
            corpo.bar?.visibility = View.GONE
            val dialog = dialog ?: return
            dialog.setOnCancelListener(null)
            dialog.setOnKeyListener(null)
            val botao = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            if (botao != null) {
                botao.text = "Continuar"
                botao.isEnabled = true
                botao.setOnClickListener { dialog.dismiss() }
            }
        }
    }

    /**
     * Monta o corpo (cabeçalho + lista rolável [+ barra]) de todos os diálogos.
     *
     * A lista tem altura limitada (~40% da tela): cabe junto do botão, e o
     * usuário rola para ler tudo. A alternativa seria cortar ou agrupar, e as
     * duas escondem justamente o que o usuário pediu para ver.
     */
    private fun corpo(
        activity: Activity,
        plano: DownloadSizeFormat.Plano,
        comProgresso: Boolean,
        prefacio: String? = null,
        sufixo: String? = null,
    ): Corpo {
        val dens = activity.resources.displayMetrics.density
        val padding = (20 * dens).roundToInt()
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, 0, padding, 0)
        }

        // Prefácio/sufixo são texto normal (não a lista): o que explica/encerra o
        // diálogo não é monoespaçado nem entra no log de arquivos.
        val texto = TextView(activity).apply {
            val partes = listOfNotNull(
                prefacio?.takeIf { it.isNotBlank() },
                DownloadSizeFormat.cabecalho(plano),
                sufixo?.takeIf { it.isNotBlank() },
            )
            text = partes.joinToString("\n\n")
            setTextColor(Color.WHITE)
            textSize = 14f
        }
        content.addView(texto)

        val status = TextView(activity).apply {
            text = ""
            setTextColor(Color.WHITE)
            textSize = 14f
            visibility = if (comProgresso) View.VISIBLE else View.GONE
        }
        content.addView(status)

        val bar = if (comProgresso) {
            ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
                isIndeterminate = false
                max = 100
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (10 * dens).roundToInt() }
            }.also { content.addView(it) }
        } else {
            null
        }

        // O LOG do download: começa vazio e vai "colando" uma linha por arquivo
        // concluído. Rola junto com a lista, então no fim o usuário sobe e lê a
        // sequência inteira antes de continuar (mesmo espírito do log do SigUpdater).
        val log = TextView(activity).apply {
            text = ""
            setTextColor(Color.LTGRAY)
            textSize = 12f
            typeface = Typeface.MONOSPACE
            movementMethod = ScrollingMovementMethod()
            setHorizontallyScrolling(true)
            visibility = if (comProgresso) View.VISIBLE else View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (8 * dens).roundToInt() }
        }
        content.addView(log)

        val lista = TextView(activity).apply {
            text = DownloadSizeFormat.linhas(plano.arquivos)
            setTextColor(Color.LTGRAY)
            textSize = 13f
            // Monoespaçada: `linhas()` já alinha por espaços, e o alinhamento só
            // é exato se a fonte tiver largura fixa.
            typeface = Typeface.MONOSPACE
            // Rola dentro do próprio TextView, que é mais leve que um ScrollView
            // com o tamanho fixo da tela.
            movementMethod = ScrollingMovementMethod()
            setHorizontallyScrolling(true)
            // Plano sem itens = arquivo único: a lista não tem o que mostrar.
            visibility = if (plano.arquivos.isEmpty()) View.GONE else View.VISIBLE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (activity.resources.displayMetrics.heightPixels * 0.40f).toInt()
            ).apply { topMargin = (10 * dens).roundToInt() }
        }
        content.addView(lista)

        return Corpo(content, status, lista, bar, log)
    }

    /** As views do corpo, prontas para o [Progresso] pintar. */
    class Corpo(
        val view: LinearLayout,
        val status: TextView,
        val lista: TextView,
        val bar: ProgressBar?,
        val log: TextView,
    )
}
