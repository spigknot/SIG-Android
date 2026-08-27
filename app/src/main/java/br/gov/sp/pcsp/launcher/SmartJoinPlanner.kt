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
    val sar: String? = null,
    val videoProfile: String? = null,
    val audioCodec: String? = null
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

    private val supportedSmartJoinCodecs = setOf("h264", "hevc")

    fun plan(
        sourceCodecFamily: String,
        selectedEncoder: SmartJoinEncoderOption?,
        availableEncoders: List<SmartJoinEncoderOption>,
        inputsCompatible: Boolean,
        orientationMismatch: Boolean
    ): SmartJoinPlan {
        val normalizedSource = normalizeCodecFamily(sourceCodecFamily)
        if (normalizedSource !in supportedSmartJoinCodecs) {
            return SmartJoinPlan(
                mode = SmartJoinMode.FULL_REENCODE,
                encoder = selectedEncoder,
                reason = "Não há encoder compatível para stream copy de $sourceCodecFamily em Smart Join."
            )
        }

        if (!inputsCompatible) {
            return SmartJoinPlan(
                mode = SmartJoinMode.FULL_REENCODE,
                encoder = selectedEncoder,
                reason = "Os arquivos de entrada possuem resoluções, taxas de quadros ou perfis incompatíveis para concatenação direta."
            )
        }

        if (orientationMismatch) {
            return SmartJoinPlan(
                mode = SmartJoinMode.FULL_REENCODE,
                encoder = selectedEncoder,
                reason = "Os vídeos possuem orientações diferentes; a união direta geraria cortes ou faixas pretas incorretas."
            )
        }

        val candidates = compatibleEncoderCandidates(normalizedSource, selectedEncoder, availableEncoders)
        if (candidates.isEmpty()) {
            return SmartJoinPlan(
                mode = SmartJoinMode.FULL_REENCODE,
                encoder = selectedEncoder,
                reason = "Nenhum encoder da família $normalizedSource está disponível neste dispositivo."
            )
        }

        val preferred = candidates.first()
        val reason = if (selectedEncoder != null && normalizeCodecFamily(selectedEncoder.codecFamily) != normalizedSource) {
            "O encoder selecionado (${selectedEncoder.ffmpegName}) não é compatível com o codec dos vídeos ($sourceCodecFamily). Usando ${preferred.ffmpegName}."
        } else {
            null
        }

        return SmartJoinPlan(
            mode = SmartJoinMode.SMART_JOIN,
            encoder = preferred,
            reason = reason,
            compatibleEncoders = candidates
        )
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
        if (!base.videoProfile.isNullOrBlank() && !candidate.videoProfile.isNullOrBlank() &&
            !base.videoProfile.equals(candidate.videoProfile, ignoreCase = true)) {
            return false
        }
        if (base.hasAudio != candidate.hasAudio) return false
        if (base.hasAudio) {
            if (base.audioSampleRate != candidate.audioSampleRate ||
                base.audioChannels != candidate.audioChannels
            ) {
                return false
            }
            if (!base.audioCodec.isNullOrBlank() && !candidate.audioCodec.isNullOrBlank() &&
                !normalizeAudioCodec(base.audioCodec).equals(normalizeAudioCodec(candidate.audioCodec), ignoreCase = true)) {
                return false
            }
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

    fun normalizeAudioCodec(value: String): String {
        val trimmed = value.trim().lowercase()
        return when {
            trimmed.contains("aac") -> "aac"
            trimmed.contains("mp3") -> "mp3"
            trimmed.contains("opus") -> "opus"
            trimmed.contains("vorbis") -> "vorbis"
            trimmed.contains("flac") -> "flac"
            trimmed.contains("alac") -> "alac"
            trimmed.contains("pcm") -> "pcm"
            else -> trimmed
        }
    }
}
