package br.gov.sp.pcsp.launcher

import android.content.Context
import android.widget.PopupMenu
import java.io.File

/** Opções, menus e filtro PCM compartilhados pelas telas Transcrição e Whisper. */
internal object LocalVad {
    enum class Mode(
        val preferenceKey: String,
        val label: String,
        val selectorLabel: String,
        val nativeMode: Int,
        val usesSilero: Boolean = false,
    ) {
        NONE("none", "Sem VAD", "Sem VAD", 0),
        SILERO_CPU("silero_cpu", "Silero VAD (CPU)", "Silero CPU", 1, true),
        SILERO_GPU("silero_gpu", "Silero VAD (GPU)", "Silero GPU", 2, true),
        WEBRTC("webrtc", "WebRTC VAD", "WebRTC", 3);

        companion object {
            fun fromPreference(value: String?): Mode = entries.firstOrNull { it.preferenceKey == value } ?: NONE
        }
    }

    val levels = 0..3

    data class Selection(val mode: Mode = Mode.NONE, val level: Int = 1) {
        val label: String
            get() = if (mode == Mode.NONE) "VAD: Nenhum" else "VAD: ${mode.selectorLabel} ($level)"
    }

    fun addModes(popup: PopupMenu, separateLevel: Boolean, select: (Mode, Int?) -> Unit) {
        for (mode in Mode.entries) {
            if (separateLevel || mode == Mode.NONE) {
                popup.menu.add(if (separateLevel) mode.label else mode.selectorLabel)
                    .setOnMenuItemClickListener { select(mode, null); true }
            } else {
                val submenu = popup.menu.addSubMenu(mode.selectorLabel)
                for (level in levels) {
                    submenu.add(level.toString()).setOnMenuItemClickListener { select(mode, level); true }
                }
            }
        }
    }

    data class Result(val originalSamples: Long, val filteredSamples: Long, val segments: Int, val backend: String)

    fun filter(
        context: Context,
        input: File,
        output: File,
        selection: Selection,
        checkCancellation: () -> Unit = {},
    ): Result {
        val mode = selection.mode
        require(mode != Mode.NONE)
        val modelPath = if (mode.usesSilero) {
            NativeDependencyManager.sileroModelFile(context).also {
                check(it.isFile && it.length() > 100_000L) { "Modelo Silero não instalado." }
            }.absolutePath
        } else ""
        val nativeResult = WhisperNative.filterVad(
            input.absolutePath, output.absolutePath, modelPath, mode.nativeMode, selection.level,
        )
        checkCancellation()
        val fields = nativeResult.split("|")
        if (fields.firstOrNull() != "OK" || fields.size < 5) {
            throw IllegalStateException(nativeResult.removePrefix("ERRO|").ifBlank { "falha no VAD local" })
        }
        return Result(
            fields[1].toLongOrNull() ?: 0L, fields[2].toLongOrNull() ?: 0L,
            fields[3].toIntOrNull() ?: 0, fields.drop(4).joinToString("|"),
        )
    }
}
