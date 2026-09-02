package br.gov.sp.pcsp.launcher

import android.content.Context
import androidx.appcompat.app.AlertDialog
import kotlin.math.roundToLong

enum class FfmpegVideoQuality(val label: String) {
    MAXIMUM("Máxima"),
    VERY_HIGH("Muito alta"),
    HIGH("Alta"),
    MEDIUM("Média"),
    ECONOMIC("Econômica");

    companion object {
        val default = HIGH
    }

    val menuLabel: String
        get() = if (this == HIGH) "$label (Recomendado)" else label
}

enum class FfmpegAudioQuality(val label: String, val bitrate: String) {
    MAXIMUM("Máxima", "320k"),
    HIGH("Alta", "256k"),
    MEDIUM("Média", "192k"),
    ECONOMIC("Econômica", "128k");

    companion object {
        val default = HIGH
    }

    val menuLabel: String
        get() = if (this == HIGH) "$label (Recomendado)" else label
}

data class FfmpegVideoEncoding(
    val arguments: List<String>,
    val targetBitrate: String?
)

fun FfmpegVideoEncoder.encodingFor(
    quality: FfmpegVideoQuality,
    sourceBitrate: String
): FfmpegVideoEncoding {
    if (ffmpegName == "libx264") {
        val crf = when (quality) {
            FfmpegVideoQuality.MAXIMUM -> 16
            FfmpegVideoQuality.VERY_HIGH -> 18
            FfmpegVideoQuality.HIGH -> 20
            FfmpegVideoQuality.MEDIUM -> 23
            FfmpegVideoQuality.ECONOMIC -> 26
        }
        return FfmpegVideoEncoding(
            arguments = listOf("-c:v", ffmpegName, "-preset", "ultrafast", "-crf", crf.toString()),
            targetBitrate = null
        )
    }

    val multiplier = when (codecFamily) {
        "hevc" -> when (quality) {
            FfmpegVideoQuality.MAXIMUM -> 1.25
            FfmpegVideoQuality.VERY_HIGH -> 1.05
            FfmpegVideoQuality.HIGH -> 0.80
            FfmpegVideoQuality.MEDIUM -> 0.55
            FfmpegVideoQuality.ECONOMIC -> 0.35
        }
        else -> when (quality) {
            FfmpegVideoQuality.MAXIMUM -> 1.60
            FfmpegVideoQuality.VERY_HIGH -> 1.25
            FfmpegVideoQuality.HIGH -> 1.00
            FfmpegVideoQuality.MEDIUM -> 0.70
            FfmpegVideoQuality.ECONOMIC -> 0.45
        }
    }
    return FfmpegVideoEncoding(
        // MediaCodec varia bastante entre fabricantes. Declarar o formato de
        // entrada e desabilitar B-frames evita que o wrapper tente negociar
        // uma configuração implícita que muitos encoders Android rejeitam no
        // configure(). O GOP continua sendo escolhido pelo chamador, que
        // conhece a taxa de quadros da mídia.
        arguments = listOf(
            "-c:v", ffmpegName,
            "-pix_fmt", "yuv420p",
            "-bf", "0"
        ),
        targetBitrate = scaleVideoBitrate(sourceBitrate, multiplier)
    )
}

fun mediaCodecGopSize(frameRate: Double?): Int =
    frameRate
        ?.takeIf { it in 1.0..240.0 }
        ?.roundToLong()
        ?.toInt()
        ?: 30

fun ffmpegFailureDetails(logs: String, fallback: String = "O FFmpeg não concluiu o processamento."): String {
    val lines = logs.lines().map { it.trim() }.filter { it.isNotBlank() }
    val important = lines.filter { line ->
        line.contains("error", ignoreCase = true) ||
            line.contains("failed", ignoreCase = true) ||
            line.contains("invalid", ignoreCase = true) ||
            line.contains("not supported", ignoreCase = true) ||
            line.contains("exception", ignoreCase = true)
    }
    return (important.takeLast(8) + lines.takeLast(10))
        .distinct()
        .joinToString("\n")
        .take(1_500)
        .ifBlank { fallback }
}

fun scaleVideoBitrate(bitrate: String, multiplier: Double): String {
    val match = Regex("""(\d+(?:\.\d+)?)\s*([kKmM])""").find(bitrate.trim())
        ?: return bitrate
    val value = match.groupValues[1].toDoubleOrNull() ?: return bitrate
    val unit = match.groupValues[2].uppercase()
    val scaled = (value * multiplier).roundToLong().coerceAtLeast(1L)
    return "$scaled$unit"
}

fun estimateVideoBitrate(
    width: Int?,
    height: Int?,
    frameRate: Double?,
    codecFamily: String?
): String {
    val safeWidth = width?.takeIf { it > 0 } ?: 1920
    val safeHeight = height?.takeIf { it > 0 } ?: 1080
    val safeFrameRate = frameRate?.takeIf { it in 1.0..240.0 } ?: 30.0
    val bitsPerPixel = if (codecFamily == "hevc") 0.065 else 0.10
    val kbps = (safeWidth.toDouble() * safeHeight * safeFrameRate * bitsPerPixel / 1000.0)
        .roundToLong()
        .coerceIn(350L, 80_000L)
    return "${kbps}k"
}

fun FfmpegVideoQuality.showHelp(context: Context) {
    AlertDialog.Builder(context)
        .setTitle("Qualidade do vídeo")
        .setMessage(
            "Máxima\nPrioriza a imagem; arquivos maiores e processamento potencialmente mais lento.\n\n" +
                "Muito alta\nQualidade elevada com menor uso de espaço que Máxima.\n\n" +
                "Alta (Recomendado)\nMantém como referência o bitrate do vídeo original para H.264 por hardware. É o melhor equilíbrio para a maioria dos casos.\n\n" +
                "Média\nReduz espaço e pode acelerar o processamento, com perda visual moderada.\n\n" +
                "Econômica\nPrioriza arquivo menor; indicada quando tamanho importa mais que a fidelidade.\n\n" +
                "No encoder CPU (libx264), os níveis usam CRF 16, 18, 20, 23 e 26. Nos encoders por hardware, eles ajustam o bitrate alvo conforme o codec."
        )
        .setPositiveButton("OK", null)
        .show()
}

fun FfmpegAudioQuality.showHelp(context: Context) {
    AlertDialog.Builder(context)
        .setTitle("Qualidade do áudio")
        .setMessage(
            "Máxima: 320 kb/s.\n\n" +
                "Alta (Recomendado): 256 kb/s.\n\n" +
                "Média: 192 kb/s.\n\n" +
                "Econômica: 128 kb/s.\n\n" +
                "WAV e FLAC continuam sem perdas e não usam esse bitrate. A escolha só é aplicada quando um corte real exige recodificação."
        )
        .setPositiveButton("OK", null)
        .show()
}
