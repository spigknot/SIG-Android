package br.gov.sp.pcsp.launcher

import java.util.Locale

/** Formatação única das informações de encoder e duração nas etapas FFmpeg. */
object FfmpegProgressText {

    fun encoderShortName(value: String?): String? {
        val name = value?.trim().orEmpty()
        if (name.isBlank()) return null
        return when {
            name.equals("libx264", ignoreCase = true) -> "cpu"
            name.contains("hevc", ignoreCase = true) || name.contains("h265", ignoreCase = true) -> "hevc"
            name.contains("h264", ignoreCase = true) || name.contains("avc", ignoreCase = true) -> "h264"
            name.equals("libmp3lame", ignoreCase = true) -> "mp3"
            name.equals("libvorbis", ignoreCase = true) -> "vorbis"
            name.equals("libopus", ignoreCase = true) -> "opus"
            else -> name
        }
    }

    fun suffix(
        encoderName: String? = null,
        detail: String? = null,
        elapsedMs: Long? = null
    ): String {
        val values = mutableListOf<String>()
        val shortEncoder = encoderShortName(encoderName)
        shortEncoder?.let(values::add)
        detail?.trim()?.takeIf { it.isNotBlank() }?.let(values::add)
        if (shortEncoder != null) elapsedMs?.let {
            values += String.format(Locale.US, "%.1fs", it.coerceAtLeast(0L) / 1000.0)
        }
        return if (values.isEmpty()) "" else " (${values.joinToString(", ")})"
    }
}
