package br.gov.sp.pcsp.launcher

import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/** Keeps the exact command available to the user in every FFmpeg tool. */
internal object FfmpegCommandPresenter {
    private const val COMMAND_VIEW_TAG = "sig_ffmpeg_command"

    fun show(anchor: TextView, arguments: Iterable<String>) {
        val command = FfmpegMediaPolicies.formatCommand(arguments)
        anchor.post {
            val parent = anchor.parent as? ViewGroup ?: return@post
            val existing = parent.findViewWithTag<TextView>(COMMAND_VIEW_TAG)
            val commandView = existing ?: TextView(anchor.context).apply {
                tag = COMMAND_VIEW_TAG
                setTextColor(Color.parseColor("#FFB8C7D9"))
                textSize = 11f
                setTextIsSelectable(true)
                setPadding(anchor.paddingLeft, 8, anchor.paddingRight, 12)
                parent.addView(this, parent.indexOfChild(anchor) + 1)
            }
            commandView.text = command
            commandView.visibility = View.VISIBLE
        }
    }
}
