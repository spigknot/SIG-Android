package br.gov.sp.pcsp.launcher

import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.util.WeakHashMap

/** Keeps the commands available to the user in every FFmpeg tool. */
internal object FfmpegCommandPresenter {
    private const val COMMAND_VIEW_TAG = "sig_ffmpeg_command"
    private val showingPreview = WeakHashMap<TextView, Boolean>()

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
        }
    }

    fun show(anchor: TextView, arguments: Iterable<String>, repetitions: Int = 1) {
        val formatted = FfmpegMediaPolicies.formatCommand(arguments)
        val command = if (repetitions > 1) "Repetições: ${repetitions}×\n$formatted" else formatted
        anchor.post {
            val commandView = commandView(anchor) ?: return@post
            val previous = commandView.text?.toString()?.trimEnd().orEmpty()
            commandView.text = if (showingPreview.remove(commandView) == true || previous.isBlank()) {
                command
            } else {
                "$previous\n\n$command"
            }
            commandView.visibility = View.VISIBLE
        }
    }

    internal fun formatPreview(commands: List<PreviewCommand>): String = commands.joinToString("\n\n") { item ->
        val command = FfmpegMediaPolicies.formatCommand(item.arguments)
        if (item.repetitions > 1) "Repetições: ${item.repetitions}×\n$command" else command
    }

    private fun commandView(anchor: TextView): TextView? {
        val parent = anchor.parent as? ViewGroup ?: return null
        return parent.findViewWithTag<TextView>(COMMAND_VIEW_TAG) ?: TextView(anchor.context).apply {
            tag = COMMAND_VIEW_TAG
            setTextColor(Color.parseColor("#FFB8C7D9"))
            textSize = 11f
            setTextIsSelectable(true)
            setPadding(anchor.paddingLeft, 8, anchor.paddingRight, 12)
            parent.addView(this, parent.indexOfChild(anchor) + 1)
        }
    }
}
