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
                    "Usa aceleração de hardware, costuma ser mais rápido, consome menos CPU e tem ampla compatibilidade. É a escolha ideal quando estiver disponível.\n\n" +
                    "hevc_mediacodec\n" +
                    "Também usa hardware e pode gerar arquivos menores com qualidade semelhante. A compatibilidade com aparelhos e players antigos é menor e alguns fluxos rápidos podem exigir reencode completo.\n\n" +
                    "libx264 (último recurso)\n" +
                    "Encoder H.264 por CPU, muito compatível e previsível, mas normalmente bem mais lento e com maior consumo de bateria. Só aparece quando estiver incluído na biblioteca FFmpeg.\n\n" +
                    "mpeg4 (CPU)\n" +
                    "Fallback por CPU usado quando esta compilação do FFmpeg não possui libx264. É mais lento e comprime pior que H.264, mas permite processar sem depender dos MediaCodec."
            )
            .setPositiveButton("OK", null)
            .show()
    }
}
