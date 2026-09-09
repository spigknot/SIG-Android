package br.gov.sp.pcsp.launcher

import com.arthenica.ffmpegkit.FFmpegKit
import java.io.File
import java.util.Locale

/**
 * Sondagem de audio via FFmpeg, compartilhada por RemoteSttActivity e
 * GraniteActivity.
 *
 * `parseLogs` concentra TODA a interpretacao do texto do FFmpeg e e pura
 * (testavel sem Android); `probe` apenas executa o binario e delega.
 */
object SttAudioProbe {

    data class AudioProbe(
        val codec: String,
        val sampleRate: String,
        val channels: String,
        val bitrate: String,
        val sampleRateHz: Int?,
        val channelCount: Int?,
        val bitrateKbps: Double?,
        val hasVideo: Boolean
    )

    /** Executa `ffmpeg -i` no arquivo e interpreta os logs. Nunca lanca. */
    fun probe(file: File): AudioProbe {
        return try {
            val session = FFmpegKit.executeWithArguments(arrayOf("-hide_banner", "-i", file.absolutePath))
            parseLogs(session.allLogsAsString.orEmpty(), file.length())
        } catch (_: Throwable) {
            AudioProbe("", "", "", "", null, null, null, false)
        }
    }

    /**
     * Interpreta os logs do FFmpeg. `fileLengthBytes` entra apenas no fallback
     * de bitrate quando o log nao traz kb/s.
     */
    fun parseLogs(logs: String, fileLengthBytes: Long): AudioProbe {
        val audioLine = logs.lines().firstOrNull { it.contains("Audio:", ignoreCase = true) }.orEmpty()
        val codec = Regex("""Audio:\s*([^,\s]+)""", RegexOption.IGNORE_CASE)
            .find(audioLine)
            ?.groupValues
            ?.getOrNull(1)
            ?.lowercase(Locale.ROOT)
            .orEmpty()
        val sampleRateHz = Regex("""(\d+)\s*Hz""", RegexOption.IGNORE_CASE)
            .find(audioLine)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
        val channelCount = when {
            audioLine.contains("mono", ignoreCase = true) -> 1
            audioLine.contains("stereo", ignoreCase = true) -> 2
            else -> Regex("""(\d+)\s*channels""", RegexOption.IGNORE_CASE)
                .find(audioLine)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
        }
        val durationSeconds = TranscriptionReport.parseDurationSeconds(logs)
        val parsedBitrateKbps = Regex("""(\d+(?:\.\d+)?)\s*kb/s""", RegexOption.IGNORE_CASE)
            .find(audioLine.ifBlank { logs })
            ?.groupValues
            ?.getOrNull(1)
            ?.toDoubleOrNull()
        val bitrateKbps = parsedBitrateKbps
            ?: durationSeconds?.takeIf { it > 0.0 }?.let { (fileLengthBytes * 8.0) / it / 1000.0 }
        return AudioProbe(
            codec = codec,
            sampleRate = sampleRateHz?.let { "${it}hz" }.orEmpty(),
            channels = when (channelCount) {
                1 -> "mono"
                2 -> "stereo"
                null -> ""
                else -> "${channelCount}ch"
            },
            bitrate = bitrateKbps?.let { TranscriptionReport.formatKbps(it) }.orEmpty(),
            sampleRateHz = sampleRateHz,
            channelCount = channelCount,
            bitrateKbps = bitrateKbps,
            hasVideo = logs.lines().any { it.contains("Video:", ignoreCase = true) }
        )
    }

    /** Resumo curto para o terminal: extensao + codec/hz/canais/bitrate. */
    fun describe(file: File): String {
        val info = probe(file)
        return listOf(
            ".${file.extension.lowercase(Locale.ROOT).ifBlank { "sem extensão" }}",
            info.codec.ifBlank { "codec ?" },
            info.sampleRate.ifBlank { "hz ?" },
            info.channels.ifBlank { "canal ?" },
            info.bitrate.ifBlank { "bitrate ?" }
        ).joinToString(", ")
    }

    fun metadataSummary(probe: AudioProbe): String {
        return listOf(
            "codec=${probe.codec.ifBlank { "?" }}",
            "hz=${probe.sampleRate.ifBlank { "?" }}",
            "canais=${probe.channels.ifBlank { "?" }}",
            "bitrate=${probe.bitrate.ifBlank { "?" }}",
            "video=${if (probe.hasVideo) "sim" else "não"}"
        ).joinToString(", ")
    }

    /** WAV ja pronto para envio (pcm_s16le 16 kHz mono, sem video). */
    fun isAlreadyReadyWav(file: File, originalName: String, log: (String) -> Unit): Boolean {
        if (!originalName.lowercase(Locale.ROOT).endsWith(".wav")) return false
        log("[${originalName}] analisando metadados para envio pronto")
        val probe = probe(file)
        log("[${originalName}] metadados: ${metadataSummary(probe)}")
        return !probe.hasVideo &&
            probe.codec == "pcm_s16le" &&
            probe.sampleRateHz == 16000 &&
            probe.channelCount == 1
    }

    /** OGG/Opus ja compacto (opus 16 kHz mono, ate 40 kbps, sem video). */
    fun isAlreadyCompact(file: File, originalName: String, log: (String) -> Unit): Boolean {
        val lower = originalName.lowercase(Locale.ROOT)
        if (!lower.endsWith(".ogg")) return false
        log("[${originalName}] analisando metadados para envio compactado")
        val probe = probe(file)
        log("[${originalName}] metadados: ${metadataSummary(probe)}")
        return !probe.hasVideo &&
            probe.codec == "opus" &&
            probe.sampleRateHz == 16000 &&
            probe.channelCount == 1 &&
            probe.bitrateKbps?.let { it <= 40.0 } == true
    }
}
