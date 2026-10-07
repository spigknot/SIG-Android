package br.gov.sp.pcsp.launcher

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Planejador puro do SmartJoin.
 *
 * Os corpos elegiveis para stream copy sempre comecam e terminam em keyframes.
 * As lacunas entre o corte logico e esses keyframes viram margens da emenda
 * recodificada. Um clipe incompatível (ou com GOP grande demais) e recodificado
 * isoladamente, sem impedir que os demais continuem em stream copy.
 */
internal object SmartJoinPlanner {

    private const val EPSILON_SECONDS = 0.002
    private const val MAX_FPS_DELTA = 0.01
    private const val FIRST_KEYFRAME_TOLERANCE_SECONDS = 0.500
    private val supportedCodecs = setOf("h264", "hevc")
    private val supportedPixelFormats = setOf("yuv420p")

    data class VideoProfile(
        val codecFamily: String,
        val width: Int,
        val height: Int,
        val fps: Double,
        val rotationDegrees: Int,
        val pixelFormat: String?,
        val sampleAspectRatio: String?,
        val codecProfile: String?
    )

    data class Source(
        val durationSeconds: Double,
        val profile: VideoProfile,
        val keyframesSeconds: List<Double>,
        val safeCopyEnds: Map<Double, Double> = emptyMap()
    )

    data class ClipPlan(
        val index: Int,
        val copyVideo: Boolean,
        val bodyStartSeconds: Double,
        val bodyEndSeconds: Double,
        val incompatibilityReason: String? = null
    ) {
        val bodyDurationSeconds: Double
            get() = (bodyEndSeconds - bodyStartSeconds).coerceAtLeast(0.0)
    }

    data class JunctionPlan(
        val index: Int,
        val outgoingBridgeStartSeconds: Double,
        val outgoingTransitionStartSeconds: Double,
        val outgoingDurationSeconds: Double,
        val incomingTransitionEndSeconds: Double,
        val incomingBridgeEndSeconds: Double
    )

    data class Plan(
        val targetIndex: Int,
        val targetProfile: VideoProfile,
        val transitionSeconds: Double,
        val fadeInOut: Boolean,
        val clips: List<ClipPlan>,
        val junctions: List<JunctionPlan>,
        val ineligibilityReason: String? = null
    ) {
        val canSmartJoin: Boolean
            get() = ineligibilityReason == null

        val hasUsefulCopy: Boolean
            get() = clips.any { it.copyVideo && it.bodyDurationSeconds > EPSILON_SECONDS }

        fun expectedDurationSeconds(sourceDurationSeconds: List<Double>): Double {
            val total = sourceDurationSeconds.sum()
            return if (fadeInOut) total else total - transitionSeconds * junctions.size
        }
    }

    fun plan(
        sources: List<Source>,
        transitionSeconds: Double,
        fadeInOut: Boolean
    ): Plan {
        require(sources.isNotEmpty()) { "SmartJoin precisa de ao menos um clipe." }
        val targetIndex = chooseTargetIndex(sources)
        val target = sources[targetIndex].profile
        val invalid = !transitionSeconds.isFinite() || transitionSeconds < 0.0 || sources.any {
            !it.durationSeconds.isFinite() || it.durationSeconds <= 0.0 ||
                !it.profile.fps.isFinite() || it.profile.fps <= 0.0 ||
                it.keyframesSeconds.any { key -> !key.isFinite() }
        }
        if (invalid) return rejectedPlan(sources, targetIndex, 0.0, fadeInOut, "Duração, framerate ou transição inválida.")
        // Efeitos menores que meio quadro não são representáveis nesse framerate.
        val safeTransition = if (transitionSeconds < 0.5 / target.fps) 0.0 else transitionSeconds

        val unsupportedReason = when {
            normalizeCodec(target.codecFamily) !in supportedCodecs ->
                "O codec ${target.codecFamily} não permite o SmartJoin seguro."
            normalizePixelFormat(target.pixelFormat) !in supportedPixelFormats ->
                "O formato de pixel ${target.pixelFormat ?: "desconhecido"} não pode ser reproduzido com segurança nas emendas."
            sources.size > 1 && sources.any { it.durationSeconds <= safeTransition + EPSILON_SECONDS } ->
                "A transição ocupa todo o clipe mais curto."
            sources.size > 2 && safeTransition > 0.0 && sources.drop(1).dropLast(1).any {
                safeTransition * 2.0 >= it.durationSeconds - EPSILON_SECONDS
            } -> "As transições de entrada e saída se sobrepõem em um clipe intermediário."
            else -> null
        }
        if (unsupportedReason != null) {
            return rejectedPlan(sources, targetIndex, safeTransition, fadeInOut, unsupportedReason)
        }

        val copyEligibility = sources.mapIndexed { index, source ->
            val reason = videoIncompatibility(target, source.profile)
            // MP4 com edit-list costuma expor o primeiro quadro de vídeo em
            // 100-200 ms, embora esse quadro já seja o primeiro IDR do stream.
            val startsWithKeyframe = source.keyframesSeconds.firstOrNull()
                ?.let { it in -EPSILON_SECONDS..FIRST_KEYFRAME_TOLERANCE_SECONDS } == true
            val eligible = reason == null && startsWithKeyframe
            Triple(index, eligible, reason ?: if (!startsWithKeyframe) "O primeiro quadro não é um keyframe utilizável." else null)
        }.toMutableList()

        fun computeClipPlan(index: Int, copyVideo: Boolean, reason: String?): ClipPlan? {
            val source = sources[index]
            val desiredStart = if (index == 0 || safeTransition <= EPSILON_SECONDS) 0.0 else safeTransition
            val desiredEnd = if (index == sources.lastIndex || safeTransition <= EPSILON_SECONDS) {
                source.durationSeconds
            } else {
                source.durationSeconds - safeTransition
            }
            if (!copyVideo) {
                return ClipPlan(index, false, desiredStart, desiredEnd, reason)
            }
            // Sem transição não há uma margem lógica na emenda: cada clipe
            // precisa contribuir desde o seu próprio início. Não aplique o
            // primeiro keyframe visível (muitas fontes MP4 o expõem ~170 ms
            // depois de zero por causa da edit-list), pois isso cortaria o
            // começo de todos os clipes que entram depois do primeiro.
            val bodyStart = if (safeTransition <= EPSILON_SECONDS || index == 0) {
                0.0
            } else {
                nextKeyframe(source.keyframesSeconds, desiredStart)
            }
            val bodyEnd = if (safeTransition <= EPSILON_SECONDS || index == sources.lastIndex) {
                source.durationSeconds
            } else {
                previousKeyframe(source.keyframesSeconds, desiredEnd)?.let { source.safeCopyEnds[it] ?: it }
            }
            if (bodyStart == null || bodyEnd == null || bodyStart > bodyEnd + EPSILON_SECONDS) return null
            return ClipPlan(index, true, bodyStart, bodyEnd)
        }

        var clipPlans = copyEligibility.map { (index, copy, reason) ->
            computeClipPlan(index, copy, reason) ?: ClipPlan(
                index = index,
                copyVideo = false,
                bodyStartSeconds = if (index == 0) 0.0 else safeTransition,
                bodyEndSeconds = if (index == sources.lastIndex) sources[index].durationSeconds else sources[index].durationSeconds - safeTransition,
                incompatibilityReason = "Os keyframes seguros se cruzam; este clipe será recodificado."
            )
        }

        // Recalcular depois de desabilitar copy em clips com GOP esparso evita
        // sobreposição entre duas emendas adjacentes.
        clipPlans = clipPlans.map { clip ->
            if (clip.copyVideo) clip else computeClipPlan(clip.index, false, clip.incompatibilityReason)!!
        }

        val junctions = if (safeTransition <= EPSILON_SECONDS) {
            emptyList()
        } else {
            (0 until sources.lastIndex).map { index ->
                val outgoing = sources[index]
                JunctionPlan(
                    index = index,
                    outgoingBridgeStartSeconds = clipPlans[index].bodyEndSeconds,
                    outgoingTransitionStartSeconds = outgoing.durationSeconds - safeTransition,
                    outgoingDurationSeconds = outgoing.durationSeconds,
                    incomingTransitionEndSeconds = safeTransition,
                    incomingBridgeEndSeconds = clipPlans[index + 1].bodyStartSeconds
                )
            }
        }

        return Plan(
            targetIndex = targetIndex,
            targetProfile = target,
            transitionSeconds = safeTransition,
            fadeInOut = fadeInOut,
            clips = clipPlans,
            junctions = junctions
        )
    }

    fun chooseTargetIndex(sources: List<Source>): Int {
        require(sources.isNotEmpty())
        return sources.indices.maxWithOrNull(
            compareBy<Int> { candidate ->
                sources.indices.sumOf { index ->
                    if (videoIncompatibility(sources[candidate].profile, sources[index].profile) == null) {
                        sources[index].durationSeconds
                    } else 0.0
                }
            }.thenByDescending { it }
        ) ?: 0
    }

    fun maximumTransitionSeconds(durations: List<Double>): Double {
        if (durations.isEmpty() || durations.any { !it.isFinite() || it <= 0.0 }) return 0.0
        val edge = durations.minOrNull()!! - 0.1
        val middle = if (durations.size > 2) durations.drop(1).dropLast(1).minOrNull()!! / 2.0 - 0.1
            else Double.POSITIVE_INFINITY
        return minOf(edge, middle).coerceAtLeast(0.0)
    }

    fun videoIncompatibility(base: VideoProfile, candidate: VideoProfile): String? {
        if (normalizeCodec(base.codecFamily) != normalizeCodec(candidate.codecFamily)) return "codec diferente"
        if (base.width != candidate.width || base.height != candidate.height) return "resolução diferente"
        if (abs(base.fps - candidate.fps) > MAX_FPS_DELTA) return "framerate diferente"
        if (normalizeRotation(base.rotationDegrees) != normalizeRotation(candidate.rotationDegrees)) return "rotação diferente"
        if (normalizePixelFormat(base.pixelFormat) != normalizePixelFormat(candidate.pixelFormat)) return "formato de pixel diferente"
        if (normalizeSar(base.sampleAspectRatio) != normalizeSar(candidate.sampleAspectRatio)) return "SAR/DAR diferente"
        val baseProfile = normalizeOptional(base.codecProfile)
        val candidateProfile = normalizeOptional(candidate.codecProfile)
        if (baseProfile != null && candidateProfile != null && baseProfile != candidateProfile) return "perfil do codec diferente"
        return null
    }

    fun compatibleEncoderNames(
        codecFamily: String,
        selectedEncoderName: String?,
        encoders: List<Pair<String, String>>
    ): List<String> {
        val codec = normalizeCodec(codecFamily)
        return encoders
            .filter { normalizeCodec(it.second) == codec }
            .map { it.first }
            .distinct()
            .sortedBy { if (it == selectedEncoderName) 0 else 1 }
    }

    private fun rejectedPlan(
        sources: List<Source>,
        targetIndex: Int,
        transitionSeconds: Double,
        fadeInOut: Boolean,
        reason: String
    ): Plan = Plan(
        targetIndex = targetIndex,
        targetProfile = sources[targetIndex].profile,
        transitionSeconds = transitionSeconds,
        fadeInOut = fadeInOut,
        clips = sources.indices.map { index ->
            ClipPlan(index, false, 0.0, sources[index].durationSeconds, reason)
        },
        junctions = emptyList(),
        ineligibilityReason = reason
    )

    private fun previousKeyframe(keyframes: List<Double>, requested: Double): Double? =
        keyframes.asSequence().filter { it <= requested + EPSILON_SECONDS }.maxOrNull()

    private fun nextKeyframe(keyframes: List<Double>, requested: Double): Double? =
        keyframes.asSequence().filter { it + EPSILON_SECONDS >= requested }.minOrNull()

    fun normalizeCodec(value: String): String = when (value.trim().lowercase(Locale.ROOT)) {
        "avc", "h.264", "h264" -> "h264"
        "hevc", "h.265", "h265" -> "hevc"
        else -> value.trim().lowercase(Locale.ROOT)
    }

    private fun normalizeRotation(value: Int): Int = ((value % 360) + 360) % 360

    private fun normalizePixelFormat(value: String?): String =
        value?.trim()?.lowercase(Locale.ROOT).orEmpty()

    private fun normalizeSar(value: String?): String = value?.trim()?.lowercase(Locale.ROOT).orEmpty().ifBlank { "1:1" }

    private fun normalizeOptional(value: String?): String? =
        value?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
}

/** Regras de quadros, GOP aberto e continuidade da linha temporal do SmartJoin. */
internal object SmartJoinTiming {
    data class Packet(val pts: Double, val dts: Double, val duration: Double, val key: Boolean)
    data class Video(
        val packets: List<Packet>, val origin: Double, val seekOffset: Double,
        val duration: Double, val fps: String
    ) {
        val times = packets.map { it.pts - origin }.sorted()
        val keys = packets.filter { it.key }.map { it.pts - origin }
        val safeEnds: Map<Double, Double> = groups().associate { (key, group) ->
            key to group.minOf { it.pts - origin }
        }
        private fun groups(): List<Pair<Double, List<Packet>>> {
            val indices = packets.indices.filter { packets[it].key }
            return indices.mapIndexed { i, start ->
                (packets[start].pts - origin) to packets.subList(start, indices.getOrNull(i + 1) ?: packets.size)
            }
        }
        fun leading(start: Double): Int = groups().firstOrNull { abs(it.first - start) < 0.00001 }
            ?.second?.count { it.pts - origin < start - 0.00001 } ?: 0
        fun delay(start: Double): Double = packets.firstOrNull { it.key && abs(it.pts - origin - start) < 0.00001 }
            ?.let { (it.pts - it.dts).coerceAtLeast(0.0) } ?: 0.0
        fun count(start: Double, end: Double): Int = times.count { it >= start - 0.00001 && it < end - 0.00001 }
    }
    fun fps(value: String): Double {
        val parts = value.split('/')
        return if (parts.size == 2) (parts[0].toDoubleOrNull() ?: 0.0) / (parts[1].toDoubleOrNull() ?: 0.0)
        else value.toDoubleOrNull() ?: 0.0
    }
    /** Arredondamento acumulado impede que cada emenda acrescente meio quadro. */
    class Frames(private val fps: Double) {
        private var seconds = 0.0
        private var allocated = 0
        fun next(duration: Double, copied: Int? = null): Int {
            seconds += duration
            val count = copied ?: ((seconds * fps).roundToInt() - allocated)
            allocated += count
            return count
        }
        val total: Int get() = allocated
    }
    fun validate(video: Video, expected: Double, frames: Int, rate: Double) {
        require(expected.isFinite() && expected > 0.0 && rate.isFinite() && rate > 0.0)
        check(video.times.size == frames) { "Vídeo com ${video.times.size} quadros; esperado $frames." }
        check(video.times.isNotEmpty()) { "Vídeo sem quadros." }
        val tick = 1.0 / rate
        check(abs(video.origin) < tick * 1.1) { "Início do vídeo deslocado." }
        check(abs(video.times.first()) < tick * 1.1) { "Início do vídeo deslocado." }
        check(abs(video.times.last() + tick - expected) <= tick * 1.1) { "Duração do vídeo diferente da solicitada." }
        check(video.times.zipWithNext().all { (a, b) -> b - a > 0.000001 && b - a <= tick * 1.5 }) {
            "Quadros ausentes, duplicados ou lacuna na linha temporal."
        }
        check(video.packets.zipWithNext().all { (a, b) -> b.dts > a.dts }) { "DTS do vídeo fora de ordem." }
    }
}
