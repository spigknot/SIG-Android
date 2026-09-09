package br.gov.sp.pcsp.launcher

import java.util.Locale

/**
 * Regras de tipo de midia (MIME + extensao) compartilhadas por GraniteActivity
 * e RemoteSttActivity.
 *
 * IMPORTANTE: FfmpegExtractAudioActivity tem copia propria com ".amr" a mais em
 * AUDIO_EXTENSIONS; nao foi unificada de proposito (mudaria o comportamento da
 * extracao de audio de arquivos .amr).
 */
internal object MediaTypeRules {

    private val VIDEO_EXTENSIONS = setOf(".mp4", ".mkv", ".mov", ".avi", ".webm", ".3gp", ".m4v")

    private val AUDIO_EXTENSIONS = setOf(".wav", ".mp3", ".m4a", ".aac", ".ogg", ".opus", ".flac", ".wma")

    fun isVideo(mime: String, name: String): Boolean {
        if (mime.startsWith("video/")) return true
        val lower = name.lowercase(Locale.ROOT)
        return VIDEO_EXTENSIONS.any { lower.endsWith(it) }
    }

    fun isAudio(mime: String, name: String): Boolean {
        if (mime.startsWith("audio/")) return true
        val lower = name.lowercase(Locale.ROOT)
        return AUDIO_EXTENSIONS.any { lower.endsWith(it) }
    }

    fun isSupportedMedia(mime: String, name: String): Boolean {
        return isVideo(mime, name) || isAudio(mime, name)
    }

    fun guessMime(name: String): String {
        return when {
            isVideo("", name) -> "video/*"
            isAudio("", name) -> "audio/*"
            else -> "application/octet-stream"
        }
    }

    fun contentMimeForUpload(mime: String, name: String): String {
        if (mime.isNotBlank() && mime != "application/octet-stream") return mime
        return when {
            isVideo("", name) -> "video/mp4"
            name.lowercase(Locale.ROOT).endsWith(".mp3") -> "audio/mpeg"
            name.lowercase(Locale.ROOT).endsWith(".wav") -> "audio/wav"
            name.lowercase(Locale.ROOT).endsWith(".ogg") -> "audio/ogg"
            name.lowercase(Locale.ROOT).endsWith(".opus") -> "audio/opus"
            name.lowercase(Locale.ROOT).endsWith(".m4a") -> "audio/mp4"
            else -> "application/octet-stream"
        }
    }
}
