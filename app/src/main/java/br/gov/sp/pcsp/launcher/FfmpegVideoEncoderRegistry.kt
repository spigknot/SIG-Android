package br.gov.sp.pcsp.launcher

import android.content.Context
import android.os.Build
import android.media.MediaCodecList
import androidx.appcompat.app.AlertDialog

data class FfmpegVideoEncoder(
    val ffmpegName: String,
    val codecFamily: String,
    val displayName: String = ffmpegName
) {
    val shortName: String
        get() = when {
            ffmpegName == "libx264" -> "cpu"
            else -> when (codecFamily) {
            "h264" -> "h264"
            "hevc" -> "hevc"
            else -> "mpeg"
            }
        }
}

object FfmpegVideoEncoderRegistry {
    @Volatile
    private var cachedEncoders: List<FfmpegVideoEncoder>? = null

    @Synchronized
    fun detect(): List<FfmpegVideoEncoder> {
        cachedEncoders?.let { return it }

        val mediaCodecs = try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.toList()
        } catch (_: Throwable) {
            emptyList()
        }

        fun androidHas(mime: String): Boolean {
            return mediaCodecs.any { codec ->
                codec.isEncoder && codec.supportedTypes.any { it.equals(mime, ignoreCase = true) }
            }
        }

        cachedEncoders = buildList {
            if (androidHas("video/avc")) {
                add(FfmpegVideoEncoder("h264_mediacodec", "h264"))
            }
            if (androidHas("video/hevc")) {
                add(FfmpegVideoEncoder("hevc_mediacodec", "hevc"))
            }
            add(
                FfmpegVideoEncoder(
                    "libx264",
                    "h264",
                    "libx264 (CPU)"
                )
            )
        }
        return cachedEncoders.orEmpty()
    }

    fun advertisedMaxInstances(encoder: FfmpegVideoEncoder): Int? {
        if (!encoder.ffmpegName.endsWith("_mediacodec")) return null
        val mime = when (encoder.codecFamily) {
            "h264" -> "video/avc"
            "hevc" -> "video/hevc"
            else -> return null
        }
        return try {
            val candidates = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { codec ->
                    codec.isEncoder && codec.supportedTypes.any { it.equals(mime, ignoreCase = true) }
                }
                .filter { codec ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        codec.isHardwareAccelerated
                    } else {
                        codec.name.contains("qti", ignoreCase = true) ||
                            codec.name.contains("qcom", ignoreCase = true) ||
                            codec.name.contains("omx", ignoreCase = true)
                    }
                }
            candidates.mapNotNull { codec ->
                runCatching { codec.getCapabilitiesForType(mime).maxSupportedInstances }
                    .getOrNull()
                    ?.takeIf { it > 0 }
            }.minOrNull()
        } catch (_: Throwable) {
            null
        }
    }

    fun showHelp(context: Context) {
        AlertDialog.Builder(context)
            .setTitle("Encoders de vídeo")
            .setMessage(
                "h264_mediacodec (recomendado)\n" +
                    "Usa aceleração de hardware do aparelho, sendo mais rápido, econômico no uso de bateria e amplamente compatível. É a escolha padrão quando disponível.\n\n" +
                    "hevc_mediacodec\n" +
                    "Também utiliza aceleração por hardware (H.265), gerando arquivos menores com qualidade visual equivalente. A compatibilidade com players antigos pode ser menor.\n\n" +
                    "libx264 (CPU)\n" +
                    "Encoder H.264 por software via CPU. Garante alta previsibilidade e qualidade consistente, porém com maior tempo de processamento e consumo de bateria."
            )
            .setPositiveButton("OK", null)
            .show()
    }
}
