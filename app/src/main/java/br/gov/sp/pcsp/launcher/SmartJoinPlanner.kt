package br.gov.sp.pcsp.launcher

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

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
        val safeCopyEnds: Map<Double, Double> = emptyMap(),
        val tailRepairStartSeconds: Double? = null
    )

    data class ClipPlan(
        val index: Int,
        val copyVideo: Boolean,
        val bodyStartSeconds: Double,
        val bodyEndSeconds: Double,
        val incompatibilityReason: String? = null,
        val tailStartSeconds: Double? = null,
        val tailEndSeconds: Double? = null
    ) {
        val bodyDurationSeconds: Double
            get() = (bodyEndSeconds - bodyStartSeconds).coerceAtLeast(0.0)
        val tailDurationSeconds: Double
            get() = if (tailStartSeconds != null && tailEndSeconds != null) {
                (tailEndSeconds - tailStartSeconds).coerceAtLeast(0.0)
            } else 0.0
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
                it.keyframesSeconds.any { key -> !key.isFinite() } ||
                it.tailRepairStartSeconds?.let { start -> !start.isFinite() || start < 0.0 } == true
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
            // Edit-lists de câmeras podem incluir um keyframe de preroll com
            // PTS negativo, seguido pelo IDR visível em zero. O preroll não
            // torna esse IDR nem os GOPs seguintes incompatíveis.
            val startsWithKeyframe = source.keyframesSeconds.firstOrNull { it >= -EPSILON_SECONDS }
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
            val repairStart = source.tailRepairStartSeconds
            if (repairStart != null && bodyEnd >= source.durationSeconds - EPSILON_SECONDS &&
                repairStart < bodyEnd - EPSILON_SECONDS) {
                // Um quadro marcado para descarte pode ser referência de um
                // B-frame visível posterior. TS/MP4 perdem essa marca no remux;
                // decodifique só a cauda deste GOP e preserve o corpo anterior.
                val tailStart = maxOf(bodyStart, repairStart)
                return ClipPlan(index, true, bodyStart, tailStart, reason, tailStart, bodyEnd)
            }
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
            }.thenBy { candidate ->
                // As taxas diferentes não impedem copiar os corpos. No empate,
                // as emendas usam a taxa predominante da mídia compatível.
                sources.filter { source -> videoIncompatibility(sources[candidate].profile,source.profile)==null &&
                    abs(source.profile.fps-sources[candidate].profile.fps)<.02 }.sumOf { it.durationSeconds }
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
    data class Packet(val pts: Double, val dts: Double, val duration: Double, val key: Boolean, val discarded: Boolean = false)

    /** Contrato persistido após finalizar as peças; retomar nunca aprova a saída sem validá-la. */
    data class ResumeValidation(
        val expected: Double, val frames: Int, val rate: Double, val sourceGap: Double,
        val tracks: Int, val sampleRate: Int, val boundaries: List<Double>, val stageBytes: Long
    ) {
        init {
            require(expected.isFinite() && expected > 0.0 && frames > 0 && rate.isFinite() && rate > 0.0)
            require(sourceGap.isFinite() && sourceGap >= 0.0 && tracks >= 0 && sampleRate > 0 && stageBytes > 0)
            require(boundaries.all { it.isFinite() && it > 0.0 && it <= expected + 1.0 / rate })
            require(boundaries.zipWithNext().all { (a, b) -> b > a })
        }
    }

    /** Lê uma linha por pacote, sem manter JSON e milhões de objetos de log na memória. */
    fun readCompactProbe(reader: java.io.Reader): Video {
        val packets = ArrayList<Packet>()
        var origin = 0.0
        var formatOrigin = 0.0
        var duration: Double? = null
        var rate: String? = null
        reader.buffered().forEachLine { line ->
            val fields = line.split('|')
            if (fields.firstOrNull() !in setOf("packet", "stream", "format")) return@forEachLine
            val values = fields.drop(1).mapNotNull { field ->
                val separator = field.indexOf('=')
                if (separator < 0) null else field.substring(0, separator) to field.substring(separator + 1)
            }.toMap()
            when (fields[0]) {
                "packet" -> {
                    val flags = values["flags"].orEmpty()
                    packets += Packet(values.getValue("pts_time").toDouble(), values.getValue("dts_time").toDouble(),
                        values["duration_time"]?.toDoubleOrNull() ?: 0.0, 'K' in flags, 'D' in flags)
                }
                "stream" -> if (values["codec_type"] == "video") {
                    origin = values["start_time"]?.toDoubleOrNull() ?: 0.0
                    duration = values["duration"]?.toDoubleOrNull()
                    rate = values["r_frame_rate"]
                }
                "format" -> formatOrigin = values["start_time"]?.toDoubleOrNull() ?: 0.0
            }
        }
        check(packets.isNotEmpty()) { "Vídeo sem quadros analisáveis." }
        val frameRate = checkNotNull(rate) { "Vídeo sem taxa de quadros analisável." }
        check(fps(frameRate).isFinite() && fps(frameRate) > 0.0) { "Taxa de quadros inválida." }
        return Video(packets, origin, origin - formatOrigin,
            duration ?: (packets.maxOf { it.pts } - origin + 1.0 / fps(frameRate)), frameRate)
    }
    data class Video(
        val packets: List<Packet>, val origin: Double, val seekOffset: Double,
        val duration: Double, val fps: String
    ) {
        // Pacotes de preroll continuam disponíveis para analisar o GOP, mas
        // não representam imagens visíveis nem entram na contagem da saída.
        val visiblePackets = packets.filter { !it.discarded && it.pts - origin >= -0.00001 }
        val times = visiblePackets.map { it.pts - origin }.sorted()
        val keys = packets.filter { it.key && !it.discarded }.map { it.pts - origin }
        val maximumFrameGapSeconds: Double = times.zipWithNext().maxOfOrNull { (a, b) -> b - a } ?: 0.0
        val safeEnds: Map<Double, Double> = groups().associate { (key, group) ->
            key to group.minOf { it.pts - origin }
        }
        val tailRepairStartSeconds: Double? = findTailRepairStart()

        private fun findTailRepairStart(): Double? {
            val lastVisible = packets.indexOfLast {
                !it.discarded && it.pts - origin >= -0.00001 && it.pts - origin < duration - 0.00001
            }
            val discardedReference = packets.indices.firstOrNull { index ->
                index < lastVisible && packets[index].discarded && packets[index].pts - origin >= -0.00001
            } ?: return null
            val groupStart = (discardedReference downTo 0).firstOrNull { packets[it].key } ?: return 0.0
            val groupEnd = ((groupStart + 1) until packets.size).firstOrNull { packets[it].key } ?: packets.size
            return packets.subList(groupStart, groupEnd).minOf { it.pts - origin }.coerceAtLeast(0.0)
        }
        private fun groups(): List<Pair<Double, List<Packet>>> {
            val indices = packets.indices.filter { packets[it].key }
            return indices.mapIndexed { i, start ->
                (packets[start].pts - origin) to packets.subList(start, indices.getOrNull(i + 1) ?: packets.size)
            }.filter { (_, group) -> !group.first().discarded }
        }
        fun leading(start: Double): Int = groups().firstOrNull { abs(it.first - start) < 0.00001 }
            ?.second?.count { it.pts - origin < start - 0.00001 } ?: 0
        fun delay(start: Double): Double = packets.firstOrNull { it.key && !it.discarded && abs(it.pts - origin - start) < 0.00001 }
            ?.let { (it.pts - it.dts).coerceAtLeast(0.0) } ?: 0.0
        fun count(start: Double, end: Double): Int = times.count { it >= start - 0.00001 && it < end - 0.00001 }
    }
    fun fps(value: String): Double {
        val parts = value.split('/')
        return if (parts.size == 2) (parts[0].toDoubleOrNull() ?: 0.0) / (parts[1].toDoubleOrNull() ?: 0.0)
        else value.toDoubleOrNull() ?: 0.0
    }

    /** Janela de decode no relógio do MP4 híbrido, sem arredondar quadros para FPS nominal. */
    data class DecoderWindow(
        val seekSeconds: Double,
        val untilSeconds: Double,
        val presentationTicks: List<Long>,
        val timeBaseDenominator: Long
    )

    fun decoderWindow(video: Video, start: Double, end: Double, timeBaseDenominator: Long = 90000L): DecoderWindow? {
        require(start.isFinite() && end.isFinite() && start >= 0.0 && end > start && timeBaseDenominator > 0L)
        val originTicks = (video.origin * timeBaseDenominator).roundToLong()
        val startTicks = (start * timeBaseDenominator).roundToLong()
        val endTicks = (end * timeBaseDenominator).roundToLong()
        // pts_time do FFprobe tem seis decimais. Reconstruir o tick inteiro
        // evita comparar a borda de 1,516456 s com 136481/90000 arredondado.
        val selected = video.visiblePackets.map { (it.pts * timeBaseDenominator).roundToLong() }
            .filter { it - originTicks >= startTicks && it - originTicks < endTicks }
            .sorted()
        if (selected.isEmpty()) return null
        // FFmpeg interpreta -ss/-to em microssegundos; dois microssegundos
        // deixam as imagens das duas bordas dentro da janela, sem incluir o
        // tick vizinho (11,11 us no MP4 híbrido). -copyts mantém PTS absolutos.
        val margin = minOf(0.000002, 0.25 / timeBaseDenominator)
        val formatOrigin = video.origin - video.seekOffset
        return DecoderWindow(
            selected.first().toDouble() / timeBaseDenominator - formatOrigin - margin,
            selected.last().toDouble() / timeBaseDenominator + margin,
            selected,
            timeBaseDenominator
        )
    }

    fun validateDecodedWindow(window: DecoderWindow, presentationTicks: List<Long>) {
        check(presentationTicks == window.presentationTicks) {
            "Emenda não decodifica os quadros e timestamps previstos (${presentationTicks.size}/${window.presentationTicks.size})."
        }
    }

    /** Preserva o relógio VFR da cópia e arredonda cumulativamente só as emendas CFR. */
    class Frames(private val fps: Double) {
        private var logicalSeconds = 0.0
        private var scheduledSeconds = 0.0
        private var allocated = 0
        var lastDurationSeconds: Double = 0.0
            private set

        init { require(fps.isFinite() && fps > 0.0) }

        fun next(duration: Double, copied: Int? = null): Int {
            require(duration.isFinite() && duration > 0.0)
            require(copied == null || copied > 0)
            logicalSeconds += duration
            // Uma câmera pode entregar mais ou menos pacotes que duration*fps.
            // Convertê-los para count/fps deslocaria todas as emendas seguintes.
            val count = copied ?: ((logicalSeconds - scheduledSeconds) * fps).roundToInt().coerceAtLeast(1)
            lastDurationSeconds = if (copied != null) duration else count / fps
            scheduledSeconds += lastDurationSeconds
            allocated += count
            return count
        }
        val total: Int get() = allocated
        val durationSeconds: Double get() = scheduledSeconds
    }
    fun validate(video: Video, expected: Double, frames: Int, rate: Double, maxSourceGap: Double = 0.0) {
        require(expected.isFinite() && expected > 0.0 && rate.isFinite() && rate > 0.0)
        require(maxSourceGap.isFinite() && maxSourceGap >= 0.0)
        check(video.packets.all { it.pts.isFinite() && it.dts.isFinite() && it.duration.isFinite() && it.duration >= 0.0 }) {
            "Quadros com timestamps inválidos."
        }
        check(video.times.size == frames) { "Vídeo com ${video.times.size} quadros; esperado $frames." }
        check(video.times.isNotEmpty()) { "Vídeo sem quadros." }
        val tick = 1.0 / rate
        check(abs(video.origin) < tick * 1.1) { "Início do vídeo deslocado." }
        check(abs(video.times.first()) < tick * 1.1) { "Início do vídeo deslocado." }
        val end = video.visiblePackets.maxOf { it.pts - video.origin + if (it.duration > 0.0) it.duration else tick }
        check(abs(end - expected) <= tick * 1.1) {
            String.format(Locale.ROOT, "Duração do vídeo: %.6f s; esperado: %.6f s.", end, expected)
        }
        // Lacunas já presentes na gravação devem permanecer na cópia. Contagem
        // exata e DTS ordenados continuam impedindo perda/duplicação de pacotes.
        val gapLimit = maxOf(tick * 2.1, maxSourceGap + tick * 1.1)
        check(video.times.zipWithNext().all { (a, b) -> b - a > 0.000001 && b - a <= gapLimit }) {
            "Quadros ausentes, duplicados ou lacuna na linha temporal."
        }
        check(video.packets.zipWithNext().all { (a, b) -> b.dts > a.dts }) { "DTS do vídeo fora de ordem." }
    }
}
