package br.gov.sp.pcsp.launcher

import java.util.Locale

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

    fun audioStreamSpecifier(inputIndex: Int, audioTrackIndex: Int): String =
        "$inputIndex:a:${audioTrackIndex.coerceAtLeast(0)}"

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
