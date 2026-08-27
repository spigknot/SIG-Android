package br.gov.sp.pcsp.launcher

import kotlin.math.abs

/**
 * Decisões puras do Smart Join.
 *
 * O Smart Join só pode copiar os corpos dos vídeos quando todos os trechos
 * compartilham um contrato de mídia compatível. O encoder usado nos pequenos
 * trechos de transição precisa pertencer à mesma família do vídeo copiado;
 * misturar corpos HEVC com transições H.264, por exemplo, invalida o concat.
 */
data class SmartJoinEncoderOption(
    val ffmpegName: String,
    val codecFamily: String
)

data class SmartJoinMediaProfile(
    val codecFamily: String,
    val width: Int,
    val height: Int,
    val fps: Double,
    val rotationDegrees: Int,
    val audioSampleRate: Int,
    val audioChannels: Int,
    val hasAudio: Boolean,
    val pixFmt: String? = null,
    val sar: String? = null
)

enum class SmartJoinMode {
    SMART_JOIN,
    FULL_REENCODE
}

data class SmartJoinPlan(
    val mode: SmartJoinMode,
    val encoder: SmartJoinEncoderOption?,
    val reason: String? = null,
    val compatibleEncoders: List<SmartJoinEncoderOption> = emptyList()
)

object SmartJoinPlanner {

    private const val MAX_FPS_DELTA = 0.25

    fun plan(
        sourceCodecFamily: String,
        selectedEncoder: SmartJoinEncoderOption?,
        availableEncoders: List<SmartJoinEncoderOption>,
        inputsCompatible: Boolean,
        orientationMismatch: Boolean
    ): SmartJoinPlan {
        if (orientationMismatch) {
            return SmartJoinPlan(
                mode = SmartJoinMode.FULL_REENCODE,
                encoder = selectedEncoder,
                reason = "As orientações dos vídeos são diferentes."
            )
        }
        if (!inputsCompatible) {
            return SmartJoinPlan(
                mode = SmartJoinMode.FULL_REENCODE,
                encoder = selectedEncoder,
                reason = "Os arquivos têm contratos de mídia incompatíveis."
            )
        }

        val sourceCodec = normalizeCodecFamily(sourceCodecFamily)
        if (sourceCodec !in setOf("h264", "hevc")) {
            return SmartJoinPlan(
                mode = SmartJoinMode.FULL_REENCODE,
                encoder = selectedEncoder,
                reason = "Não há encoder compatível com ${sourceCodecFamily.uppercase()} para Smart Join."
            )
        }

        val compatibleEncoders = compatibleEncoderCandidates(sourceCodec, selectedEncoder, availableEncoders)
        val compatibleEncoder = compatibleEncoders.firstOrNull()
        val selectedEncoderReason = selectedEncoder
            ?.takeIf { normalizeCodecFamily(it.codecFamily) != sourceCodec }
            ?.let { "O encoder selecionado (${it.ffmpegName}) não é compatível com o codec ${sourceCodec.uppercase()}." }

        return if (compatibleEncoder != null) {
            SmartJoinPlan(
                mode = SmartJoinMode.SMART_JOIN,
                encoder = compatibleEncoder,
                reason = selectedEncoderReason,
                compatibleEncoders = compatibleEncoders
            )
        } else {
            SmartJoinPlan(
                mode = SmartJoinMode.FULL_REENCODE,
                encoder = selectedEncoder,
                reason = "Não há encoder compatível com ${sourceCodecFamily.uppercase()}."
            )
        }
    }

    /**
     * Ordena os encoders que podem preservar os corpos copiados pelo Smart Join.
     * O escolhido pelo usuário vem primeiro quando pertence à mesma família do
     * codec de origem; os demais ficam disponíveis para preflight/fallback.
     */
    fun compatibleEncoderCandidates(
        sourceCodecFamily: String,
        selectedEncoder: SmartJoinEncoderOption?,
        availableEncoders: List<SmartJoinEncoderOption>
    ): List<SmartJoinEncoderOption> {
        val sourceCodec = normalizeCodecFamily(sourceCodecFamily)
        return availableEncoders
            .filter { normalizeCodecFamily(it.codecFamily) == sourceCodec }
            .distinctBy { it.ffmpegName }
            .sortedWith(
                compareBy<SmartJoinEncoderOption> {
                    if (it.ffmpegName == selectedEncoder?.ffmpegName) 0 else 1
                }
            )
    }

    fun profilesCompatible(
        base: SmartJoinMediaProfile,
        candidate: SmartJoinMediaProfile
    ): Boolean {
        if (normalizeCodecFamily(base.codecFamily) != normalizeCodecFamily(candidate.codecFamily)) return false
        if (base.width != candidate.width || base.height != candidate.height) return false
        if (normalizeRotation(base.rotationDegrees) != normalizeRotation(candidate.rotationDegrees)) return false
        if (abs(base.fps - candidate.fps) > MAX_FPS_DELTA) return false
        if (!base.pixFmt.isNullOrBlank() && !candidate.pixFmt.isNullOrBlank() &&
            !base.pixFmt.equals(candidate.pixFmt, ignoreCase = true)) {
            return false
        }
        if (!base.sar.isNullOrBlank() && !candidate.sar.isNullOrBlank() &&
            !base.sar.equals(candidate.sar, ignoreCase = true)) {
            return false
        }
        if (base.hasAudio != candidate.hasAudio) return false
        if (base.hasAudio && (
                base.audioSampleRate != candidate.audioSampleRate ||
                    base.audioChannels != candidate.audioChannels
                )
        ) {
            return false
        }
        return true
    }

    fun normalizeCodecFamily(value: String): String {
        return when (value.trim().lowercase()) {
            "avc", "h.264", "h264" -> "h264"
            "hevc", "h.265", "h265" -> "hevc"
            "vp9", "vp09" -> "vp9"
            "av1", "av01" -> "av1"
            "mpeg4", "mp4v", "mp4v-es" -> "mpeg4"
            else -> value.trim().lowercase()
        }
    }

    fun normalizeRotation(value: Int): Int {
        return ((value % 360) + 360) % 360
    }
}
