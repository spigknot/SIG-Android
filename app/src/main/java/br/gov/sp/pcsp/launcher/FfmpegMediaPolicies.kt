package br.gov.sp.pcsp.launcher

import java.util.Locale

internal data class FfmpegStreamCopySignature(
    val containerFamily: String,
    val ffmpegDescriptor: String,
    val mime: String,
    val profile: Int?,
    val level: Int?,
    val sampleRate: Int?,
    val channels: Int?,
    val channelMask: Int?,
    val pcmEncoding: Int?,
    val width: Int?,
    val height: Int?,
    val frameRate: Double?,
    val colorStandard: Int?,
    val colorTransfer: Int?,
    val colorRange: Int?,
    val codecTag: String?,
    val sampleFormat: String?,
    val channelLayout: String?,
    val timeBase: String?,
    val csd0: Int?,
    val csd1: Int?,
    val csd2: Int?
)

internal object FfmpegMediaPolicies {
    fun usesMetadataCopyCommand(metadataOnly: Boolean): Boolean = metadataOnly

    fun metadataRotationCopyArguments(
        inputPath: String,
        outputPath: String,
        currentCounterClockwise: Int,
        requestedClockwise: Int
    ): Array<String> = arrayOf(
        "-y",
        "-display_rotation:v:0",
        metadataRotationAfterClockwiseRequest(currentCounterClockwise, requestedClockwise).toString(),
        "-i", inputPath,
        "-map", "0",
        "-c", "copy",
        outputPath
    )

    fun metadataCopyPreflightArguments(
        inputPath: String,
        outputPath: String,
        currentCounterClockwise: Int,
        requestedClockwise: Int
    ): Array<String> = arrayOf(
        "-y", "-hide_banner", "-loglevel", "error",
        "-display_rotation:v:0",
        metadataRotationAfterClockwiseRequest(currentCounterClockwise, requestedClockwise).toString(),
        "-i", inputPath,
        "-map", "0", "-c", "copy", "-t", "0.001",
        outputPath
    )

    fun audioStreamSpecifier(inputIndex: Int, audioTrackIndex: Int): String =
        "$inputIndex:a:${audioTrackIndex.coerceAtLeast(0)}"

    fun cutAudioEncoderArguments(extension: String, bitrate: String, pcmEncoder: String): List<String> =
        when (extension.lowercase(Locale.ROOT)) {
            "wav" -> listOf("-c:a", pcmEncoder)
            "flac" -> listOf("-c:a", "flac")
            "mp3" -> listOf("-c:a", "libmp3lame", "-b:a", bitrate)
            "ogg" -> listOf("-c:a", "libvorbis", "-b:a", bitrate)
            "opus" -> listOf("-c:a", "libopus", "-b:a", bitrate, "-vbr", "on", "-application", "audio")
            "m4a", "aac" -> listOf("-c:a", "aac", "-b:a", bitrate)
            else -> listOf("-c:a", "aac", "-b:a", bitrate)
        }

    fun cutAudioCommandArguments(
        inputPath: String,
        outputPath: String,
        start: String,
        duration: String,
        encoderArguments: List<String>,
        audioMap: String = "0:a?"
    ): Array<String> = buildList {
        addAll(listOf("-y", "-ss", start, "-i", inputPath, "-t", duration))
        addAll(listOf("-map", audioMap, "-map_metadata", "0", "-map_chapters", "0", "-vn"))
        addAll(encoderArguments)
        addAll(listOf("-avoid_negative_ts", "make_zero", outputPath))
    }.toTypedArray()

    fun extractAudioEncoderArguments(extension: String, bitrate: String, pcmEncoder: String): List<String> =
        when (extension.lowercase(Locale.ROOT)) {
            "wav" -> listOf("-c:a", pcmEncoder, "-f", "wav")
            "mp3" -> listOf("-c:a", "libmp3lame", "-b:a", bitrate)
            "m4a" -> listOf("-c:a", "aac", "-b:a", bitrate, "-movflags", "+faststart")
            "aac" -> listOf("-c:a", "aac", "-b:a", bitrate)
            "ogg" -> listOf("-c:a", "libvorbis", "-b:a", bitrate)
            "opus" -> listOf("-c:a", "libopus", "-application", "audio", "-b:a", bitrate, "-vbr", "on")
            "flac" -> listOf("-c:a", "flac")
            else -> listOf("-c:a", "aac", "-b:a", bitrate)
        }

    fun extractAudioCommandArguments(
        inputPath: String,
        outputPath: String,
        start: String?,
        duration: String?,
        audioMap: String,
        copyAudio: Boolean,
        sampleRate: Int,
        channels: Int,
        encoderArguments: List<String>
    ): Array<String> = buildList {
        add("-y")
        if (start != null) addAll(listOf("-ss", start))
        addAll(listOf("-i", inputPath))
        if (duration != null) addAll(listOf("-t", duration))
        addAll(listOf("-vn", "-map", audioMap, "-map_metadata", "0"))
        if (copyAudio) {
            addAll(listOf("-c:a", "copy"))
        } else {
            addAll(listOf("-ar", sampleRate.toString(), "-ac", channels.toString()))
            addAll(encoderArguments)
        }
        addAll(listOf("-avoid_negative_ts", "make_zero", outputPath))
    }.toTypedArray()

    fun directConcatCommandArguments(listPath: String, outputPath: String): Array<String> = arrayOf(
        "-y", "-fflags", "+genpts", "-f", "concat", "-safe", "0",
        "-i", listPath,
        "-map", "0", "-map_metadata", "0", "-map_chapters", "0",
        "-c", "copy", "-avoid_negative_ts", "make_zero", outputPath
    )

    fun joinAudioCommandArguments(
        inputPaths: List<String>,
        outputPath: String,
        filterComplex: String,
        encoder: String,
        sampleRate: Int,
        channels: Int,
        bitrate: String?
    ): Array<String> = buildList {
        add("-y")
        inputPaths.forEach { addAll(listOf("-i", it)) }
        addAll(listOf("-filter_complex", filterComplex, "-map", "[aout]", "-vn"))
        addAll(listOf("-c:a", encoder, "-ar", sampleRate.toString(), "-ac", channels.toString()))
        if (bitrate != null) addAll(listOf("-b:a", bitrate))
        addAll(listOf("-avoid_negative_ts", "make_zero", outputPath))
    }.toTypedArray()

    fun insertAudioCommandArguments(
        mainInputPath: String,
        insertedInputPath: String,
        outputPath: String,
        filterComplex: String,
        encoder: String,
        sampleRate: Int,
        channels: Int,
        bitrate: String?,
        fastStart: Boolean
    ): Array<String> = buildList {
        addAll(listOf(
            "-y", "-i", mainInputPath, "-i", insertedInputPath,
            "-filter_complex", filterComplex, "-map", "[aout]", "-vn", "-c:a", encoder
        ))
        if (bitrate != null) addAll(listOf("-b:a", bitrate))
        addAll(listOf("-ar", sampleRate.toString(), "-ac", channels.toString()))
        if (fastStart) addAll(listOf("-movflags", "+faststart"))
        addAll(listOf("-avoid_negative_ts", "make_zero", outputPath))
    }.toTypedArray()

    fun cleanAudioCommandArguments(
        inputPath: String,
        outputPath: String,
        audioMap: String,
        filter: String,
        pcmEncoder: String,
        sampleRate: Int,
        channels: Int
    ): Array<String> = arrayOf(
        "-y", "-i", inputPath, "-vn", "-map", audioMap,
        "-af", filter, "-c:a", pcmEncoder,
        "-ar", sampleRate.toString(), "-ac", channels.toString(),
        "-avoid_negative_ts", "make_zero", "-f", "wav", outputPath
    )

    fun hybridCopyBodyArguments(inputPath: String, outputPath: String, startUs: Long, endUs: Long): Array<String> {
        val safeStart = startUs.coerceAtLeast(0L)
        val safeEnd = endUs.coerceAtLeast(safeStart + 1L)
        val start = String.format(Locale.US, "%.6f", safeStart / 1_000_000.0)
        val duration = String.format(Locale.US, "%.6f", (safeEnd - safeStart) / 1_000_000.0)
        return arrayOf(
            "-y", "-ss", start, "-noautorotate", "-i", inputPath,
            "-t", duration,
            "-map", "0:v:0?", "-map", "0:a?", "-map", "0:s?", "-map", "0:d?",
            "-map_metadata", "0", "-map_chapters", "0", "-c", "copy",
            "-avoid_negative_ts", "make_zero", "-f", "matroska", outputPath
        )
    }

    fun normalizedAudioFilter(
        inputSpecifier: String,
        normalizeFilter: String,
        postTimestampFilters: List<String>,
        outputLabel: String
    ): String = buildString {
        append('[').append(inputSpecifier).append(']')
        append(normalizeFilter)
        append(",asetpts=PTS-STARTPTS")
        postTimestampFilters.forEach { append(',').append(it) }
        append('[').append(outputLabel).append(']')
    }

    fun normalizeRightAngle(degrees: Int): Int {
        val normalized = ((degrees % 360) + 360) % 360
        return when (normalized) {
            0 -> 0
            90 -> 90
            180 -> 180
            270 -> -90
            else -> {
                val rounded = ((normalized + 45) / 90 * 90) % 360
                when (rounded) {
                    90 -> 90
                    180 -> 180
                    270 -> -90
                    else -> 0
                }
            }
        }
    }

    /** FFmpeg display rotation is counter-clockwise; the SIG UI is clockwise. */
    fun metadataRotationAfterClockwiseRequest(currentCounterClockwise: Int, requestedClockwise: Int): Int =
        normalizeRightAngle(currentCounterClockwise - requestedClockwise)

    /** Materializes an existing FFmpeg counter-clockwise display rotation into pixels. */
    fun physicalRotationFilters(counterClockwiseDegrees: Int): List<String> =
        when (normalizeRightAngle(counterClockwiseDegrees)) {
            -90 -> listOf("transpose=1")
            90 -> listOf("transpose=2")
            180 -> listOf("hflip", "vflip")
            else -> emptyList()
        }

    fun parseAudioChannelCount(audioLine: String, fallback: Int = 2): Int {
        val text = audioLine.lowercase(Locale.ROOT)
        Regex("""\b(\d+)\s*channels?\b""").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let {
            if (it > 0) return it
        }
        return when {
            Regex("""\b7\.1(?:\([^)]*\))?\b""").containsMatchIn(text) -> 8
            Regex("""\b6\.1(?:\([^)]*\))?\b""").containsMatchIn(text) -> 7
            Regex("""\b5\.1(?:\([^)]*\))?\b""").containsMatchIn(text) -> 6
            Regex("""\b4\.1(?:\([^)]*\))?\b""").containsMatchIn(text) -> 5
            Regex("""\bquad\b|\b4\.0\b""").containsMatchIn(text) -> 4
            Regex("""\b2\.1\b""").containsMatchIn(text) -> 3
            "stereo" in text -> 2
            "mono" in text -> 1
            else -> fallback.coerceAtLeast(1)
        }
    }

    fun channelLayout(channels: Int): String = when (channels) {
        1 -> "mono"
        2 -> "stereo"
        3 -> "2.1"
        4 -> "quad"
        5 -> "4.1"
        6 -> "5.1"
        7 -> "6.1"
        8 -> "7.1"
        else -> "stereo"
    }

    fun parseKnownVideoProfile(videoLine: String): String? {
        val profile = Regex(
            """\b(Constrained Baseline|Baseline|Main|Extended|High 4:4:4 Predictive|High 4:2:2|High 10|High)\b""",
            RegexOption.IGNORE_CASE
        ).find(videoLine)?.groupValues?.getOrNull(1) ?: return null
        return profile.lowercase(Locale.ROOT)
            .split(' ')
            .joinToString(" ") { token -> token.replaceFirstChar { it.titlecase(Locale.ROOT) } }
    }

    fun videoMimeForName(name: String): String = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "mov" -> "video/quicktime"
        "avi" -> "video/x-msvideo"
        else -> "video/mp4"
    }

    fun safeContainerExtension(name: String): String =
        name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            .takeIf { it in setOf("mp4", "m4v", "mov", "mkv", "webm", "avi") }
            ?: "mkv"

    fun containerFamily(name: String): String = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "mp4", "m4v", "mov" -> "mov"
        "mkv" -> "matroska"
        "webm" -> "webm"
        "avi" -> "avi"
        "wav" -> "wav"
        "m4a" -> "mov"
        "aac" -> "adts"
        "mp3" -> "mp3"
        "flac" -> "flac"
        "ogg", "opus" -> "ogg"
        else -> "unknown"
    }

    fun directConcatSignaturesCompatible(signatures: List<List<FfmpegStreamCopySignature>?>): Boolean {
        if (signatures.size < 2 || signatures.any { it.isNullOrEmpty() }) return false
        if (signatures.filterNotNull().flatten().any {
                it.containerFamily == "unknown" || it.ffmpegDescriptor.isBlank() || it.mime.isBlank()
            }
        ) return false
        val first = signatures.first()
        return signatures.drop(1).all { it == first }
    }

    fun formatCommand(arguments: Iterable<String>): String = buildString {
        append("ffmpeg")
        arguments.forEach { argument ->
            append(' ')
            if (argument.isNotEmpty() && argument.none(Char::isWhitespace) && '"' !in argument) {
                append(argument)
            } else {
                append('"').append(argument.replace("\\", "\\\\").replace("\"", "\\\"")).append('"')
            }
        }
    }
}
