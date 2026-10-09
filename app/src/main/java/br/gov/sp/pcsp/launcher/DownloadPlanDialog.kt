package br.gov.sp.pcsp.launcher

import android.app.Activity
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.shape.MaterialShapeDrawable
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
        val builder = dialogo(activity)
        val corpo = corpo(builder.context, plano, comProgresso = false, prefacio = prefacio, sufixo = sufixo)
        val dialog = builder
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
        val builder = dialogo(activity)
        val corpo = corpo(builder.context, plano, comProgresso = true)
        val dialog = builder
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
                        corpo.log.visibility = View.VISIBLE
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
            corpo.log.visibility = View.VISIBLE
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

    /** Mantém o acabamento arredondado comum a confirmação e progresso. */
    private fun dialogo(activity: Activity): MaterialAlertDialogBuilder {
        return MaterialAlertDialogBuilder(activity, R.style.ThemeOverlay_SIG_DownloadDialog).apply {
            (background as? MaterialShapeDrawable)?.let { fundo ->
                val corTexto = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurface, "DownloadPlanDialog")
                fundo.setStroke(context.resources.displayMetrics.density, ColorUtils.setAlphaComponent(corTexto, 32))
            }
        }
    }

    /**
     * Monta o corpo com altura natural: novas linhas no status, lista ou log
     * provocam uma nova medição. O corpo inteiro compartilha a rolagem quando
     * chega ao limite, mantendo título e botões fora da área rolável.
     */
    private fun corpo(
        context: Context,
        plano: DownloadSizeFormat.Plano,
        comProgresso: Boolean,
        prefacio: String? = null,
        sufixo: String? = null,
    ): Corpo {
        val dens = context.resources.displayMetrics.density
        fun dp(valor: Int) = (valor * dens).roundToInt()
        val corTexto = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurface, "DownloadPlanDialog")
        val corFundo = MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurface, "DownloadPlanDialog")
        val corDestaque = MaterialColors.getColor(context, com.google.android.material.R.attr.colorPrimary, "DownloadPlanDialog")
        val corSecundaria = ColorUtils.setAlphaComponent(corTexto, 210)
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(4), dp(24), dp(8))
        }

        // A explicação e o resumo usam fonte normal; nomes e log ficam abaixo.
        val texto = TextView(context).apply {
            val partes = listOfNotNull(
                prefacio?.takeIf { it.isNotBlank() },
                DownloadSizeFormat.cabecalho(plano),
                sufixo?.takeIf { it.isNotBlank() },
            )
            text = partes.joinToString("\n\n")
            setTextColor(corTexto)
            textSize = 14f
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        content.addView(texto)

        val status = TextView(context).apply {
            text = "Preparando download..."
            setTextColor(corDestaque)
            textSize = 14f
            setLineSpacing(dp(2).toFloat(), 1f)
            visibility = if (comProgresso) View.VISIBLE else View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) }
        }
        content.addView(status)

        val bar = if (comProgresso) {
            LinearProgressIndicator(context).apply {
                isIndeterminate = false
                max = 100
                trackThickness = dp(4)
                trackCornerRadius = dp(2)
                setIndicatorColor(corDestaque)
                trackColor = ColorUtils.setAlphaComponent(corTexto, 32)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(12) }
            }.also { content.addView(it) }
        } else {
            null
        }

        fun textoDeArquivos(): TextView = TextView(context).apply {
            setTextColor(corSecundaria)
            typeface = Typeface.MONOSPACE
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(MaterialColors.layer(corFundo, corTexto, 0.04f))
                setStroke(dp(1), ColorUtils.setAlphaComponent(corTexto, 24))
            }
            // A quebra de linha também conta na altura; o ScrollView cuida de
            // toda a rolagem, sem disputar gestos com TextViews roláveis.
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(14) }
        }

        // O histórico e a lista compartilham a rolagem para permitir a leitura
        // completa, inclusive depois de terminar o download.
        val log = textoDeArquivos().apply {
            text = ""
            textSize = 12f
            // Um log vazio não reserva altura. Ao receber a primeira linha,
            // [Progresso] o exibe e a janela cresce junto com o conteúdo.
            visibility = View.GONE
        }
        content.addView(log)

        val lista = textoDeArquivos().apply {
            text = DownloadSizeFormat.linhas(plano.arquivos)
            textSize = 13f
            // A fonte monoespaçada mantém o alinhamento dos nomes e tamanhos.
            visibility = if (plano.arquivos.isEmpty()) View.GONE else View.VISIBLE
        }
        content.addView(lista)

        val scroll = ConteudoRolavel(context).apply {
            isFillViewport = false
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        return Corpo(scroll, status, lista, bar, log)
    }

    /** Limita a altura máxima, sem impor altura mínima aos planos pequenos. */
    private class ConteudoRolavel(context: Context) : ScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            // screenHeightDp acompanha rotação e multiwindow. O espaço restante
            // do AlertDialog ainda limita o corpo em telas baixas ou fonte grande.
            val maximo = (resources.configuration.screenHeightDp * resources.displayMetrics.density * 0.60f).roundToInt()
            val limite = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) {
                maximo
            } else {
                minOf(maximo, MeasureSpec.getSize(heightMeasureSpec))
            }
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(limite, MeasureSpec.AT_MOST))
        }
    }

    /** As views do corpo, prontas para o [Progresso] pintar. */
    class Corpo(
        val view: ScrollView,
        val status: TextView,
        val lista: TextView,
        val bar: ProgressBar?,
        val log: TextView,
    )
}
