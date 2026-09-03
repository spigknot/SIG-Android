package br.gov.sp.pcsp.launcher

import android.graphics.Color
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import java.util.WeakHashMap

/**
 * Keeps the commands available to the user in every FFmpeg tool.
 *
 * Duas funcoes:
 * - [preview]: mostra em tempo real o(s) comando(s) planejado(s) a partir das
 *   opcoes atuais (o informativo muda antes de executar).
 * - [show]: quando a execucao comeca, o primeiro comando real substitui o
 *   preview e os comandos seguintes sao anexados, formando o historico da
 *   tarefa. Cada comando mostrado pode ser marcado com [completeLastShown]:
 *   com sucesso ele fica verde; sem sucesso ele e fechado na cor padrao (o
 *   erro aparece no passo-a-passo). No paralelismo do Girar, um unico comando
 *   com "Repeticoes: Nx" e marcado verde apenas quando as N execucoes
 *   terminarem com sucesso.
 */
internal object FfmpegCommandPresenter {
    private const val COMMAND_VIEW_TAG = "sig_ffmpeg_command"
    private const val COLOR_DEFAULT = "#FFB8C7D9"
    private const val COLOR_SUCCESS = "#FF2ECC71"

    private val showingPreview = WeakHashMap<TextView, Boolean>()
    private val commandLogs = WeakHashMap<TextView, MutableList<CommandEntry>>()

    private enum class EntryState { RUNNING, DONE, FAILED }

    private data class CommandEntry(val text: String, var state: EntryState = EntryState.RUNNING)

    data class PreviewCommand(
        val arguments: Iterable<String>,
        val repetitions: Int = 1
    )

    fun preview(anchor: TextView, arguments: Iterable<String>, repetitions: Int = 1) {
        preview(anchor, listOf(PreviewCommand(arguments, repetitions)))
    }

    fun preview(anchor: TextView, commands: List<PreviewCommand>) {
        val text = formatPreview(commands)
        anchor.post {
            val commandView = commandView(anchor) ?: return@post
            commandView.text = text
            commandView.visibility = View.VISIBLE
            showingPreview[commandView] = true
            // Um novo planejamento descarta o historico anterior; o proximo
            // show() comeca um registro novo.
            commandLogs[commandView]?.clear()
        }
    }

    /**
     * Mostra um aviso no lugar do comando quando nenhum comando pode ser
     * planejado no modo atual (ex.: SmartJoin inaplicavel aos arquivos).
     * Mantem a mesma semantica do preview: o proximo show() da execucao
     * substitui o aviso, e um novo planejamento descarta o historico.
     */
    fun placeholder(anchor: TextView, text: String) {
        anchor.post {
            val commandView = commandView(anchor) ?: return@post
            commandView.text = text
            commandView.visibility = View.VISIBLE
            showingPreview[commandView] = true
            commandLogs[commandView]?.clear()
        }
    }

    fun show(anchor: TextView, arguments: Iterable<String>, repetitions: Int = 1) {
        val formatted = FfmpegMediaPolicies.formatCommand(arguments)
        val command = if (repetitions > 1) "Repetições: ${repetitions}×\n$formatted" else formatted
        anchor.post {
            val commandView = commandView(anchor) ?: return@post
            val log = commandLogs.getOrPut(commandView) { mutableListOf() }
            // O primeiro comando real da execucao substitui o preview; os
            // comandos seguintes sao anexados ao historico.
            if (showingPreview.remove(commandView) == true || log.isEmpty()) {
                log.clear()
            }
            log += CommandEntry(command)
            commandView.text = renderCommands(log)
            commandView.visibility = View.VISIBLE
        }
    }

    /**
     * Fecha o comando mostrado mais recente que ainda esta em execucao.
     * Com [succeeded] = true o texto fica verde (concluido com sucesso); com
     * false o comando e fechado na cor padrao para nao ser alvo de uma
     * marcacao futura (o erro/cancelamento aparece no passo-a-passo).
     */
    fun completeLastShown(anchor: TextView, succeeded: Boolean) {
        anchor.post {
            val commandView = commandView(anchor) ?: return@post
            val log = commandLogs[commandView] ?: return@post
            val index = log.indexOfLast { it.state == EntryState.RUNNING }
            if (index < 0) return@post
            log[index].state = if (succeeded) EntryState.DONE else EntryState.FAILED
            commandView.text = renderCommands(log)
        }
    }

    internal fun formatPreview(commands: List<PreviewCommand>): String = commands.joinToString("\n\n") { item ->
        val command = FfmpegMediaPolicies.formatCommand(item.arguments)
        if (item.repetitions > 1) "Repetições: ${item.repetitions}×\n$command" else command
    }

    private fun renderCommands(log: List<CommandEntry>): SpannableStringBuilder {
        val builder = SpannableStringBuilder()
        for ((index, entry) in log.withIndex()) {
            if (index > 0) builder.append("\n\n")
            val lineStart = builder.length
            builder.append(entry.text)
            val color = if (entry.state == EntryState.DONE) {
                Color.parseColor(COLOR_SUCCESS)
            } else {
                Color.parseColor(COLOR_DEFAULT)
            }
            builder.setSpan(ForegroundColorSpan(color), lineStart, builder.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return builder
    }

    private fun commandView(anchor: TextView): TextView? {
        val parent = anchor.parent as? ViewGroup ?: return null
        return parent.findViewWithTag<TextView>(COMMAND_VIEW_TAG) ?: TextView(anchor.context).apply {
            tag = COMMAND_VIEW_TAG
            setTextColor(Color.parseColor(COLOR_DEFAULT))
            textSize = 11f
            setTextIsSelectable(true)
            // Mesma caixa de texto usada nas telas de Ocorrência/Transcrição:
            // fundo escuro + contorno azul. A caixa cresce com o conteúdo
            // (altura wrap_content) conforme os comandos são anexados.
            setBackgroundResource(R.drawable.ffmpeg_outline_button_bg)
            val density = resources.displayMetrics.density
            val padH = (10 * density).toInt()
            val padV = (8 * density).toInt()
            setPadding(padH, padV, padH, padV)
            val params = if (parent is LinearLayout) {
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            } else {
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            if (params is ViewGroup.MarginLayoutParams) {
                params.topMargin = (6 * density).toInt()
                params.bottomMargin = (6 * density).toInt()
            }
            parent.addView(this, parent.indexOfChild(anchor) + 1, params)
        }
    }
}
