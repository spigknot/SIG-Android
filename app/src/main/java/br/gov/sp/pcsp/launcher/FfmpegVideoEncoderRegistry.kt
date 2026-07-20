package br.gov.sp.pcsp.launcher

import android.content.Context
import android.media.MediaCodecList
import androidx.appcompat.app.AlertDialog
import com.arthenica.ffmpegkit.FFmpegKit

data class FfmpegVideoEncoder(
    val ffmpegName: String,
    val codecFamily: String,
    val extraArguments: List<String> = emptyList(),
    val displayName: String = ffmpegName
) {
    val arguments: List<String>
        get() = listOf("-c:v", ffmpegName) + extraArguments

    val shortName: String
        get() = when (codecFamily) {
            "h264" -> "h264"
            "hevc" -> "hevc"
            else -> "mpeg"
        }
}

object FfmpegVideoEncoderRegistry {
    @Volatile
    private var cachedEncoders: List<FfmpegVideoEncoder>? = null

    @Synchronized
    fun detect(): List<FfmpegVideoEncoder> {
        cachedEncoders?.let { return it }

        val ffmpegEncoders = try {
            FFmpegKit.executeWithArguments(arrayOf("-hide_banner", "-encoders"))
                .allLogsAsString
                .orEmpty()
        } catch (_: Throwable) {
            ""
        }
        val mediaCodecs = try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.toList()
        } catch (_: Throwable) {
            emptyList()
        }

        fun ffmpegHas(name: String): Boolean {
            return Regex("(?m)^\\s*V\\S*\\s+${Regex.escape(name)}(?:\\s|$)")
                .containsMatchIn(ffmpegEncoders)
        }

        fun androidHas(mime: String): Boolean {
            return mediaCodecs.any { codec ->
                codec.isEncoder && codec.supportedTypes.any { it.equals(mime, ignoreCase = true) }
            }
        }

        cachedEncoders = buildList {
            if (ffmpegHas("h264_mediacodec") && androidHas("video/avc")) {
                add(FfmpegVideoEncoder("h264_mediacodec", "h264"))
            }
            if (ffmpegHas("hevc_mediacodec") && androidHas("video/hevc")) {
                add(FfmpegVideoEncoder("hevc_mediacodec", "hevc"))
            }
            if (ffmpegHas("libx264")) {
                add(
                    FfmpegVideoEncoder(
                        "libx264",
                        "h264",
                        listOf("-preset", "ultrafast"),
                        "libx264 (CPU)"
                    )
                )
            } else if (ffmpegHas("mpeg4")) {
                add(FfmpegVideoEncoder("mpeg4", "mpeg4", displayName = "mpeg4 (CPU)"))
            }
        }
        return cachedEncoders.orEmpty()
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
