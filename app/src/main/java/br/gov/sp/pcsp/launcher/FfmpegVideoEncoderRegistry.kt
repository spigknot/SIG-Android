package br.gov.sp.pcsp.launcher

import android.content.Context
import android.media.MediaCodecList
import android.os.Build
import androidx.appcompat.app.AlertDialog
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.util.Locale

/** Registro dos encoders de video do aparelho e da ajuda do seletor.
 *
 * Descoberta: `MediaCodecList` diz quais encoders de H.264/HEVC existem; cada um
 * vira uma opcao de HARDWARE com o NOME do MediaCodec (ex.:
 * `c2.qti.hevc.encoder`), que o Avancado pode forcar via `-codec_name`. O
 * software (`libx264`) entra como caminho CPU. Antes de listar, cada opcao de
 * hardware passa por uma SONDAGEM REAL (encode de 1 quadro): e o que impede o
 * Avancado de oferecer um encoder que so falharia na hora de rodar.
 *
 * Nao executa o corte: monta catalogo, sonda e traduz para o tipo de execucao. */
data class FfmpegVideoEncoder(
    val ffmpegName: String,
    val codecFamily: String,
    val displayName: String = ffmpegName,
    val codecName: String? = null
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

    private val MIME_FOR_CODEC = mapOf("h264" to "video/avc", "hevc" to "video/hevc")
    private val CODEC_FOR_MIME = mapOf("video/avc" to "h264", "video/hevc" to "hevc")

    @Volatile
    private var cachedCatalog: List<FfmpegVideoEncoders.Option>? = null
    @Volatile
    private var cachedProbed: List<FfmpegVideoEncoders.Option>? = null

    /** Catalogo do aparelho: encoders de hardware (por nome) + CPU (libx264). */
    @Synchronized
    fun detect(): List<FfmpegVideoEncoders.Option> {
        cachedCatalog?.let { return it }
        val catalog = buildList {
            MIME_FOR_CODEC.forEach { (codec, mime) ->
                hardwareEncoderNames(mime).forEach { name ->
                    add(
                        FfmpegVideoEncoders.Option(
                            key = name,
                            label = friendlyLabel(name),
                            path = FfmpegVideoEncoders.PATH_HARDWARE,
                            codec = codec,
                            encoder = if (codec == "hevc") "hevc_mediacodec" else "h264_mediacodec",
                            priority = vendorPriority(name),
                            codecName = name,
                            shortLabel = vendorName(name)
                        )
                    )
                }
            }
            // Software: so H.264 — este build nao traz libx265 (verificado no
            // libavcodec.so do pacote nativo).
            add(
                FfmpegVideoEncoders.Option(
                    key = "cpu",
                    label = "CPU (libx264)",
                    path = FfmpegVideoEncoders.PATH_CPU,
                    codec = "h264",
                    encoder = "libx264",
                    priority = 100
                )
            )
        }
        cachedCatalog = catalog
        return catalog
    }

    /** Catalogo depois da sondagem real (cache por processo; roda fora da UI). */
    @Synchronized
    fun probed(): List<FfmpegVideoEncoders.Option> {
        cachedProbed?.let { return it }
        val probed = probe(detect())
        cachedProbed = probed
        return probed
    }

    /** Sondagem: 1 quadro de verdade com cada opcao de hardware.
     *
     * Falha CONCLUINTE (encoder nao funciona) remove a opcao; falha
     * INCONCLUSIVA (sem lavfi, sem ffmpeg, erro de ambiente) mantem a opcao para
     * nao esvaziar o Avancado por causa do ambiente de teste. */
    fun probe(options: List<FfmpegVideoEncoders.Option>): List<FfmpegVideoEncoders.Option> {
        val hardware = options.filter { it.path == FfmpegVideoEncoders.PATH_HARDWARE }
        if (hardware.isEmpty()) return options
        val failed = hardware.filter { runProbe(it) == ProbeResult.FAILED }.map { it.key }.toSet()
        if (failed.isEmpty()) return options
        return options.filterNot { it.path == FfmpegVideoEncoders.PATH_HARDWARE && it.key in failed }
    }

    private enum class ProbeResult { OK, FAILED, INCONCLUSIVE }

    private fun runProbe(option: FfmpegVideoEncoders.Option): ProbeResult {
        val args = buildList {
            addAll(listOf("-hide_banner", "-loglevel", "error"))
            addAll(listOf("-f", "lavfi", "-i", "color=c=black:s=320x240:d=0.1"))
            addAll(listOf("-frames:v", "1", "-an", "-c:v", option.encoder))
            addAll(FfmpegVideoEncoders.codecNameArguments(option))
            addAll(listOf("-f", "null", "-"))
        }
        return try {
            val session = FFmpegKit.executeWithArguments(args.toTypedArray())
            if (ReturnCode.isSuccess(session.returnCode)) return ProbeResult.OK
            val logs = session.allLogsAsString.orEmpty().lowercase(Locale.ROOT)
            val environmentIssue = listOf("lavfi", "unknown input format", "no such filter", "filter not found", "unable to find a suitable output format")
                .any { logs.contains(it) }
            if (environmentIssue) ProbeResult.INCONCLUSIVE else ProbeResult.FAILED
        } catch (_: Throwable) {
            ProbeResult.INCONCLUSIVE
        }
    }

    /** Traduz a opcao escolhida para o encoder da execucao. `forceName` liga o
     * `-codec_name` (so quando o usuario forcou um encoder no Avancado). */
    fun toEncoder(option: FfmpegVideoEncoders.Option, forceName: Boolean): FfmpegVideoEncoder =
        FfmpegVideoEncoder(
            ffmpegName = option.encoder,
            codecFamily = option.codec,
            displayName = option.label,
            codecName = option.codecName.takeIf { forceName }
        )

    /** Nomes de encoders de HARDWARE do aparelho para um MIME, em ordem estavel. */
    fun hardwareEncoderNames(mime: String): List<String> {
        val codecs = try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.toList()
        } catch (_: Throwable) {
            emptyList()
        }
        return codecs
            .filter { codec -> codec.isEncoder && codec.supportedTypes.any { it.equals(mime, ignoreCase = true) } }
            .filter { codec -> isHardware(codec.name) }
            .map { it.name }
            .distinct()
            .sortedWith(compareBy({ vendorPriority(it) }, { it }))
    }

    private fun isHardware(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        // MediaCodec "de software" do AOSP: nao e hardware (o caminho CPU do app
        // e o libx264, nao o encoder de software do sistema).
        if (lower.contains("google") || lower.contains("android") || lower.contains("sw.") || lower.contains("ffmpeg")) {
            return false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val infos = try {
                MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            } catch (_: Throwable) {
                emptyArray()
            }
            val info = infos.firstOrNull { it.name == name }
            if (info != null) return info.isHardwareAccelerated
        }
        return lower.contains("qti") || lower.contains("qcom") || lower.contains("omx") ||
            lower.contains("exynos") || lower.contains("mtk") || lower.contains("mediatek") ||
            lower.contains("kirin") || lower.contains("intel") || lower.contains("nvidia")
    }

    /** Prioridade do encoder de hardware (menor = preferido), como o Windows
     * prefere NVENC > QSV > AMF. */
    private fun vendorPriority(name: String): Int {
        val lower = name.lowercase(Locale.ROOT)
        return when {
            lower.contains("qti") || lower.contains("qcom") || lower.contains("c2.qti") -> 10
            lower.contains("exynos") || lower.contains("mtk") || lower.contains("mediatek") ||
                lower.contains("kirin") || lower.contains("intel") || lower.contains("nvidia") -> 20
            else -> 40
        }
    }

    private fun vendorName(name: String): String {
        val lower = name.lowercase(Locale.ROOT)
        return when {
            lower.contains("qti") || lower.contains("qcom") -> "Qualcomm"
            lower.contains("exynos") -> "Exynos"
            lower.contains("mtk") || lower.contains("mediatek") -> "MediaTek"
            lower.contains("kirin") || lower.contains("huawei") -> "Kirin"
            lower.contains("intel") -> "Intel"
            lower.contains("nvidia") -> "NVIDIA"
            else -> "Hardware"
        }
    }

    /** Nome completo (menu de seleção): "Qualcomm (c2.qti.avc.encoder)". */
    private fun friendlyLabel(name: String): String = "${vendorName(name)} ($name)"

    fun advertisedMaxInstances(encoder: FfmpegVideoEncoder): Int? {
        val mime = MIME_FOR_CODEC[encoder.codecFamily] ?: return null
        val codecName = encoder.codecName
        return try {
            val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            infos
                .filter { codec -> codec.isEncoder && codec.supportedTypes.any { it.equals(mime, ignoreCase = true) } }
                .filter { codec -> codecName == null || codec.name == codecName }
                .mapNotNull { codec ->
                    runCatching { codec.getCapabilitiesForType(mime).maxSupportedInstances }.getOrNull()
                        ?.takeIf { it > 0 }
                }
                .minOrNull()
        } catch (_: Throwable) {
            null
        }
    }

    fun showHelp(context: Context) {
        AlertDialog.Builder(context)
            .setTitle("Encoders de vídeo")
            .setMessage(
                "GPU\nAceleração do próprio aparelho (MediaCodec). É a escolha padrão: " +
                    "processa mais rápido e gasta menos bateria.\n\n" +
                    "CPU\nEncoder por software (libx264, H.264). Mais lento e mais previsível.\n\n" +
                    "Avançado\nAparece só no modo GPU e lista os encoders de hardware que " +
                    "passaram na sondagem real deste aparelho (um encode de 1 quadro cada). " +
                    "Escolher um deles FORÇA aquele encoder específico; em \"Automático\" o app " +
                    "escolhe o melhor disponível.\n\n" +
                    "Em trecho curto (abaixo de 3 s) o modo Automático usa a CPU: a inicialização " +
                    "do hardware não compensa nesse tamanho.\n\n" +
                    "Se um encoder de hardware falhar na hora de rodar, a tarefa é repetida na CPU " +
                    "somente naquela vez — a sua escolha não muda — e o motivo fica registrado."
            )
            .setPositiveButton("OK", null)
            .show()
    }
}
