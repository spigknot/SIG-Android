package br.gov.sp.pcsp.launcher

import android.media.MediaExtractor
import android.media.MediaFormat
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.util.Locale

/**
 * Remuxa um arquivo intermediario do pipeline para o container original do
 * arquivo de entrada (mp4, mov, m4v, 3gp, avi, mkv...) usando stream copy.
 * Nenhum stream e reencodado; a qualidade e preservada byte a byte.
 *
 * O intermediario pode ser MKV (CPU) ou MP4 (MediaCodec). O MP4 e necessario
 * para que os pacotes AVCC/HVCC produzidos pelo MediaCodec sejam finalizados
 * de forma confiavel antes de qualquer conversao para o container final.
 * Se a conversao falhar, o intermediario e mantido como fallback.
 */
object FfmpegOutputRemuxer {

    data class RemuxResult(val file: File, val converted: Boolean)

    /** Containers aceitos pelo remux -c copy para H.264/HEVC + AAC. */
    private val REMUXABLE_EXTENSIONS = setOf("mp4", "mov", "m4v", "3gp", "3g2", "avi", "mkv")

    /**
     * MediaCodec deve ser finalizado em MP4; os demais caminhos conservam a
     * extensao de trabalho escolhida pelo pipeline.
     */
    fun intermediateVideoExtension(
        outputExtension: String,
        encoderName: String?,
        reencode: Boolean
    ): String {
        return if (reencode && encoderName?.endsWith("_mediacodec", ignoreCase = true) == true) {
            "mp4"
        } else {
            outputExtension.lowercase(Locale.ROOT)
        }
    }

    fun remuxToOriginalContainer(inputFile: File, originalExtension: String): RemuxResult {
        val extension = originalExtension.lowercase(Locale.ROOT)
        if (extension.isEmpty() || extension !in REMUXABLE_EXTENSIONS) {
            return RemuxResult(inputFile, false)
        }
        if (!inputFile.exists() || inputFile.length() <= 0L) return RemuxResult(inputFile, false)

        // O intermediario ja esta no container desejado. Alem de ser
        // desnecessario, remuxar para o mesmo caminho apagaria a propria
        // entrada antes de o FFmpeg conseguir le-la.
        if (inputFile.extension.equals(extension, ignoreCase = true)) {
            return RemuxResult(inputFile, false)
        }

        val output = File(inputFile.parentFile, "${inputFile.nameWithoutExtension}.$extension")
        output.delete()

        val isHevc = detectHevc(inputFile)
        val isMovContainer = extension in setOf("mp4", "mov", "m4v", "3gp", "3g2")
        val args = buildList {
            add("-y")
            add("-hide_banner")
            add("-loglevel")
            add("error")
            add("-i")
            add(inputFile.absolutePath)
            add("-c")
            add("copy")
            // MP4 exige a tag hvc1 para HEVC (o padrao hev1 nao abre em
            // varios players/iOS). Nao aplicar a tag ao Matroska/AVI.
            if (isHevc && isMovContainer) {
                add("-tag:v")
                add("hvc1")
            }
            // moov no inicio (reproducao progressiva) apenas nos containers
            // da familia MOV; Matroska/AVI nao usam esse atom.
            if (isMovContainer) {
                add("-movflags")
                add("+faststart")
            }
            add(output.absolutePath)
        }.toTypedArray()

        val session = FFmpegKit.executeWithArguments(args)
        return if (ReturnCode.isSuccess(session.returnCode) && output.exists() && output.length() > 0L) {
            inputFile.delete()
            RemuxResult(output, true)
        } else {
            output.delete()
            RemuxResult(inputFile, false)
        }
    }

    fun originalVideoExtension(fileName: String): String =
        fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)

    /** Detecta HEVC (H.265) no arquivo via MediaExtractor, para decidir o
     * -tag:v hvc1 no remux para MP4/MOV. */
    fun detectHevc(file: File): Boolean {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            (0 until extractor.trackCount).any { index ->
                extractor.getTrackFormat(index)
                    .getString(MediaFormat.KEY_MIME)
                    ?.startsWith("video/hevc", ignoreCase = true) == true
            }
        } catch (_: Throwable) {
            false
        } finally {
            extractor.release()
        }
    }
}
