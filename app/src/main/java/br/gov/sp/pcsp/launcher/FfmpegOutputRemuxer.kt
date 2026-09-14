package br.gov.sp.pcsp.launcher

import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
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
 *
 * F1 da padronizacao: o remux leva a SELECAO do plano ate o muxer final
 * (`-map 0:v:0 -map 0:a?`) e VALIDA o inventario antes de abandonar o
 * intermediario. Sem os `-map`, o FFmpeg faz selecao automatica e faixas de
 * audio extras desaparecem silenciosamente no ultimo passo do pipeline.
 */
object FfmpegOutputRemuxer {

    data class RemuxResult(
        val file: File,
        val converted: Boolean,
        val warning: String? = null
    )

    /** Containers aceitos pelo remux -c copy para H.264/HEVC + AAC. */
    private val REMUXABLE_EXTENSIONS = setOf("mp4", "mov", "m4v", "3gp", "3g2", "avi", "mkv")

    private const val TAG = "FfmpegRemux"

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

    /**
     * Argumentos do remux. A selecao do plano e "video principal + todas as
     * faixas de audio"; legendas/anexos/dados nao entram nessa promessa (o
     * plano os trata separadamente) e, quando existirem no intermediario, o
     * chamador recebe um aviso em vez de perde-los em silencio.
     */
    fun remuxArguments(
        inputPath: String,
        outputPath: String,
        isHevc: Boolean,
        isMovContainer: Boolean
    ): Array<String> = buildList {
        addAll(listOf("-y", "-hide_banner", "-loglevel", "error"))
        addAll(listOf("-i", inputPath))
        addAll(listOf("-map", "0:v:0", "-map", "0:a?"))
        addAll(listOf("-c", "copy"))
        addAll(listOf("-map_metadata", "0"))
        // MP4 exige a tag hvc1 para HEVC (o padrao hev1 nao abre em
        // varios players/iOS). Nao aplicar a tag ao Matroska/AVI.
        if (isHevc && isMovContainer) addAll(listOf("-tag:v", "hvc1"))
        // moov no inicio (reproducao progressiva) apenas nos containers
        // da familia MOV; Matroska/AVI nao usam esse atom.
        if (isMovContainer) addAll(listOf("-movflags", "+faststart"))
        add(outputPath)
    }.toTypedArray()

    /** Motivo pelo qual o arquivo final NAO pode substituir o intermediario;
     * `null` quando o inventario do plano foi cumprido. */
    fun inventoryMismatch(
        expectedVideo: Int,
        expectedAudio: Int,
        actualVideo: Int,
        actualAudio: Int
    ): String? = when {
        actualVideo < expectedVideo -> "o arquivo final perdeu a faixa de video"
        actualAudio < expectedAudio ->
            "o arquivo final perdeu ${expectedAudio - actualAudio} faixa(s) de audio"
        else -> null
    }

    fun remuxToOriginalContainer(
        inputFile: File,
        originalExtension: String,
        onCommand: ((Array<String>) -> Unit)? = null
    ): RemuxResult {
        val extension = originalExtension.lowercase(Locale.ROOT)
        if (extension.isEmpty() || extension !in REMUXABLE_EXTENSIONS) {
            return RemuxResult(inputFile, false)
        }
        if (!inputFile.exists() || inputFile.length() <= 0L) return RemuxResult(inputFile, false)

        // O intermediario ja esta no container desejado. Alem de ser
        // desnecessario, remuxar para o mesmo caminho apagaria a propria
        // entrada antes de o FFmpeg conseguir le-la.
        if (inputFile.extension.equals(extension, ignoreCase = true)) {
            return RemuxResult(inputFile, false, extrasWarning(inputFile))
        }

        val output = File(inputFile.parentFile, "${inputFile.nameWithoutExtension}.$extension")
        output.delete()

        val isHevc = detectHevc(inputFile)
        val isMovContainer = extension in setOf("mp4", "mov", "m4v", "3gp", "3g2")
        val args = remuxArguments(inputFile.absolutePath, output.absolutePath, isHevc, isMovContainer)

        onCommand?.invoke(args)
        val session = FFmpegKit.executeWithArguments(args)
        if (!ReturnCode.isSuccess(session.returnCode) || !output.exists() || output.length() <= 0L) {
            output.delete()
            return RemuxResult(inputFile, false)
        }

        // F1: validar o inventario ANTES de abandonar o intermediario. Um
        // arquivo final sem as faixas do plano nao pode ser tratado como
        // concluido (o intermediario e recuperavel e fica preservado).
        val entrada = trackInventory(inputFile)
        val saida = trackInventory(output)
        val motivo = if (entrada != null && saida != null) {
            inventoryMismatch(entrada.videos, entrada.audios, saida.videos, saida.audios)
        } else {
            null
        }
        if (motivo != null) {
            Log.w(TAG, "remux rejeitado: $motivo")
            output.delete()
            return RemuxResult(
                inputFile,
                false,
                "A conversão para $extension foi descartada ($motivo); o arquivo intermediário foi mantido."
            )
        }

        val aviso = extrasWarning(inputFile)
        inputFile.delete()
        return RemuxResult(output, true, aviso)
    }

    fun originalVideoExtension(fileName: String): String =
        fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)

    /** Detecta HEVC (H.265) no arquivo via MediaExtractor, para decidir o
     * -tag:v hvc1 no remux para MP4/MOV. */
    fun detectHevc(file: File): Boolean = detectHevcTrack(file)

    /** Inventario do arquivo: quantos videos, audios e streams de outra natureza. */
    private data class TrackInventory(
        val videos: Int,
        val audios: Int,
        val extras: Int
    )

    private fun trackInventory(file: File): TrackInventory? = countTracks(file)?.let { contagem ->
        TrackInventory(
            videos = contagem.first,
            audios = contagem.second,
            extras = contagem.third
        )
    }

    private fun countTracks(file: File): Triple<Int, Int, Int>? {
        // Em teste unitario (JVM) as classes de midia do Android sao stubs que
        // lancam em QUALQUER chamada, inclusive release(): nada aqui pode
        // escapar do catch.
        var extractor: MediaExtractor? = null
        return try {
            extractor = MediaExtractor()
            extractor.setDataSource(file.absolutePath)
            var videos = 0
            var audios = 0
            var extras = 0
            for (index in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(index)
                    .getString(MediaFormat.KEY_MIME).orEmpty()
                when {
                    mime.startsWith("video/") -> videos++
                    mime.startsWith("audio/") -> audios++
                    else -> extras++
                }
            }
            Triple(videos, audios, extras)
        } catch (_: Throwable) {
            null
        } finally {
            try {
                extractor?.release()
            } catch (_: Throwable) {
                // stub de teste: nao ha recurso nativo para liberar
            }
        }
    }

    private fun detectHevcTrack(file: File): Boolean {
        var extractor: MediaExtractor? = null
        return try {
            extractor = MediaExtractor()
            extractor.setDataSource(file.absolutePath)
            (0 until extractor.trackCount).any { index ->
                extractor.getTrackFormat(index)
                    .getString(MediaFormat.KEY_MIME)
                    ?.startsWith("video/hevc", ignoreCase = true) == true
            }
        } catch (_: Throwable) {
            false
        } finally {
            try {
                extractor?.release()
            } catch (_: Throwable) {
                // stub de teste: nao ha recurso nativo para liberar
            }
        }
    }

    /** Aviso quando o intermediario tem streams que o plano nao leva ao final
     * (legendas, dados, anexos): nada e descartado em silencio. */
    private fun extrasWarning(file: File): String? {
        val inventario = trackInventory(file) ?: return null
        return if (inventario.extras > 0) {
            "Legendas, dados ou anexos do intermediário não entram no arquivo final (o plano preserva vídeo e áudios)."
        } else {
            null
        }
    }
}
