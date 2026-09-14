package br.gov.sp.pcsp.launcher

import java.util.Locale

/** Politicas de midia do FFmpeg: stream-copy, trim, juncao e selecao de faixas.
 *
 * Regra pura por codec/assinatura de stream (data classes de plano e probe).
 * Nao executa FFmpeg nem le arquivo: recebe o que foi sondado e devolve a
 * decisao (comando copiavel, motivo de fallback).
 */

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

internal sealed interface FfmpegTrackProbeResult {
    data class Count(val value: Int) : FfmpegTrackProbeResult
    data class Failed(val message: String) : FfmpegTrackProbeResult
}

internal data class FfmpegAudioJoinPlan(
    val requiresReencode: Boolean,
    val standardizeLosslessly: Boolean
)

internal data class FfmpegAudioJoinFilterInput(
    val durationSeconds: Double,
    val audioInputSpecifiers: List<String>
)

internal data class FfmpegVideoJoinFilterInput(
    val durationSeconds: Double,
    val hasAudio: Boolean,
    val audioInputSpecifiers: List<String>
)

internal object FfmpegMediaPolicies {
    fun usesMetadataCopyCommand(metadataOnly: Boolean): Boolean = metadataOnly

    fun requestedTrimDurationMs(startMs: Long, endMs: Long?): Long? =
        endMs?.minus(startMs)?.takeIf { it > 0L }

    fun audioSelectionCanUseStreamCopy(startMs: Long, endMs: Long?, inputDurationMs: Long): Boolean {
        if (startMs > 0L) return false
        if (endMs == null) return true
        if (inputDurationMs <= 0L) return false
        return endMs >= inputDurationMs
    }

    fun hybridCutFallbackReason(
        sourceCodec: String?,
        encoderCodec: String,
        hasInternalKeyframes: Boolean? = null
    ): String? = when {
        sourceCodec !in setOf("h264", "hevc") ->
            "Codec ${sourceCodec ?: "desconhecido"} não permite cópia híbrida."
        encoderCodec != sourceCodec ->
            "O encoder escolhido não corresponde ao codec da origem."
        hasInternalKeyframes == false ->
            "Não há keyframes internos suficientes para copiar o trecho central sem perdas."
        else -> null
    }

    fun audioJoinPlan(
        requestedReencode: Boolean,
        directCopyCompatible: Boolean,
        selectedTrackReduction: Boolean
    ): FfmpegAudioJoinPlan {
        val requiresReencode = requestedReencode || !directCopyCompatible || selectedTrackReduction
        return FfmpegAudioJoinPlan(
            requiresReencode = requiresReencode,
            standardizeLosslessly = requiresReencode && !requestedReencode
        )
    }

    fun normalizedAudioTrackCount(
        audioTrackCounts: List<Int>,
        plan: FfmpegAudioJoinPlan,
        selectedTrackReduction: Boolean
    ): Int {
        if (!plan.requiresReencode || selectedTrackReduction || audioTrackCounts.isEmpty()) return 1
        val first = audioTrackCounts.first()
        return first.takeIf { it > 1 && audioTrackCounts.all { count -> count == first } } ?: 1
    }

    fun losslessAudioStandardizationExtension(audioTrackCount: Int): String =
        if (audioTrackCount > 1) "mka" else "wav"

    fun losslessAudioStandardizationEncoder(outputExtension: String, pcmEncoder: String = "pcm_s16le"): String =
        if (outputExtension.equals("mka", ignoreCase = true)) "flac" else pcmEncoder

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
        "-map", "0", "-map_metadata", "0", "-map_chapters", "0",
        "-c", "copy", "-t", "1.000",
        outputPath
    )

    fun audioMapSpecifier(inputIndex: Int, audioTrackIndex: Int, optional: Boolean = true): String =
        "$inputIndex:a:${audioTrackIndex.coerceAtLeast(0)}${if (optional) "?" else ""}"

    fun audioFilterInputSpecifier(inputIndex: Int, audioTrackIndex: Int): String =
        "$inputIndex:a:${audioTrackIndex.coerceAtLeast(0)}"

    /** Perfil de UMA faixa de audio da entrada (uma por stream). */
    data class AudioTrackProfile(
        val index: Int,
        val bitrate: String?,
        val sampleRate: Int?,
        val channels: Int?
    )

    /**
     * Audio do corte preciso: reencoda CADA faixa em AAC com o perfil da
     * propria faixa.
     *
     * Medido (T01/T02): copiar o audio no corte preciso deixa o pacote AAC que
     * cruza o inicio comandar o som — o intervalo pedido [1,4; 4,6] saiu com
     * 3,62 s e 0,44 s de audio anterior ao corte. Reencodando, o inicio e exato
     * em todas as faixas (mesma politica do SIG Windows).
     *
     * Os especificadores :N valem para a SAIDA: a selecao mapeia todas as
     * faixas de audio na ordem da entrada, entao a faixa N da saida e a faixa N
     * da entrada (indice ABSOLUTO no container nao e o mesmo que indice de
     * audio).
     */
    fun preciseAudioTrackArguments(tracks: List<AudioTrackProfile>): List<String> = buildList {
        if (tracks.isEmpty()) {
            addAll(listOf("-c:a", "aac"))
            return@buildList
        }
        tracks.forEach { track ->
            add("-c:a:${track.index}")
            add("aac")
            add("-b:a:${track.index}")
            add(track.bitrate ?: "128k")
            // Especificador QUALIFICADO (a:N): o nu (-ar:0) casa por indice
            // GLOBAL de stream e o stream 0 e o video — medido: a faixa A saia
            // com a taxa da B (48000/2 em vez de 44100/1).
            track.sampleRate?.takeIf { it > 0 }?.let {
                add("-ar:a:${track.index}")
                add(it.toString())
            }
            track.channels?.takeIf { it > 0 }?.let {
                add("-ac:a:${track.index}")
                add(it.toString())
            }
        }
    }

    /** Resumo para a lista de tarefas: "AAC por faixa (2 faixas: 64k 44,1 kHz mono • 128k 48 kHz estéreo)". */
    fun audioTracksSummary(tracks: List<AudioTrackProfile>): String {
        if (tracks.isEmpty()) return "AAC (faixa unica)"
        val detalhes = tracks.joinToString(" • ") { track ->
            val khz = track.sampleRate?.let { String.format(Locale.US, "%.1f", it / 1000.0) + " kHz" }
                ?: "taxa original"
            val canais = when (track.channels) {
                1 -> "mono"
                2 -> "estéreo"
                null -> "canais originais"
                else -> "${track.channels} canais"
            }
            "${track.bitrate ?: "128k"} $khz $canais"
        }
        return "AAC por faixa (${tracks.size} faixas: $detalhes)"
    }

    /**
     * Filtros do Limpar áudio. F9: as MESMAS tecnologias nos dois apps —
     * "equilibrado" e "forte" são afftdn (antes o Android usava anlmdn no forte,
     * enquanto o Windows usava afftdn agressivo: o mesmo rótulo, efeitos diferentes).
     */
    const val CLEAN_FILTER_BALANCED = "afftdn=nf=-25"
    const val CLEAN_FILTER_STRONG = "afftdn=nr=18:nf=-35:tn=1"

    /**
     * Aviso quando a fonte tem mais de 8 bits por componente.
     *
     * Reencodar 10/12 bits (yuv420p10le, p010le, …) para yuv420p reduz a
     * profundidade de cor — e sem aviso isso acontece em SILÊNCIO. O padrão
     * desta rodada é avisar (o Sem Reencode preserva o original).
     */
    fun colorDepthWarning(pixelFormat: String?): String? {
        val formato = pixelFormat?.lowercase(Locale.ROOT).orEmpty()
        if (formato.isBlank()) return null
        val marcadores = listOf("10le", "10be", "12le", "12be", "16le", "16be",
            "p010", "p016", "y210", "y410", "x2rgb10", "rgb48", "rgba64")
        if (marcadores.none { it in formato }) return null
        return "Fonte em $pixelFormat: o reencode grava em 8 bits (yuv420p) e a " +
            "profundidade de cor será reduzida — o modo Sem Reencode preserva o original."
    }

    /**
     * Texto do intervalo PEDIDO x EFETIVO do modo Sem Reencode.
     *
     * Copiar streams não corta em qualquer ponto: o FFmpeg recua até o keyframe
     * disponível. O modo promete cópia fiel, não o intervalo arbitrário exato —
     * então o efetivo tem que aparecer (mesma política do SIG Windows).
     */
    fun copyIntervalMessage(
        requestedStartMs: Long,
        requestedEndMs: Long,
        effectiveStartMs: Long
    ): String {
        fun segundos(valor: Long) = String.format(Locale.US, "%.3f", valor / 1000.0)
        val pedido = (requestedEndMs - requestedStartMs).coerceAtLeast(0L) / 1000.0
        val efetivo = (requestedEndMs - effectiveStartMs).coerceAtLeast(0L) / 1000.0
        val recuo = (requestedStartMs - effectiveStartMs) / 1000.0
        if (recuo <= 0.001) {
            return "Sem Reencode: intervalo efetivo ${segundos(effectiveStartMs)}–" +
                "${segundos(requestedEndMs)} s (${String.format(Locale.US, "%.3f", efetivo)} s) — igual ao pedido."
        }
        return "Sem Reencode: intervalo efetivo ${segundos(effectiveStartMs)}–${segundos(requestedEndMs)} s " +
            "(${String.format(Locale.US, "%.3f", efetivo)} s); pedido ${segundos(requestedStartMs)}–" +
            "${segundos(requestedEndMs)} s (${String.format(Locale.US, "%.3f", pedido)} s) — " +
            "o início recua ${String.format(Locale.US, "%.3f", recuo)} s até o keyframe anterior."
    }

    fun cutMappedCopyArguments(): List<String> = listOf(
        "-map", "0:v:0?",
        "-map", "0:a?",
        "-map", "0:s?",
        "-map", "0:d?",
        "-map", "0:t?",
        "-map_metadata", "0",
        "-map_chapters", "0",
        "-c", "copy",
        "-c:t", "copy"
    )

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
        bitrate: String?,
        outputLabels: List<String> = listOf("aout")
    ): Array<String> = buildList {
        require(outputLabels.isNotEmpty()) { "At least one filtered audio output is required" }
        add("-y")
        inputPaths.forEach { addAll(listOf("-i", it)) }
        addAll(listOf("-filter_complex", filterComplex))
        outputLabels.forEach { addAll(listOf("-map", "[$it]")) }
        add("-vn")
        addAll(listOf("-c:a", encoder, "-ar", sampleRate.toString(), "-ac", channels.toString()))
        if (bitrate != null) addAll(listOf("-b:a", bitrate))
        addAll(listOf("-avoid_negative_ts", "make_zero", outputPath))
    }.toTypedArray()

    // ---------------------------------------------------------------------
    // Smart Insert (portado do SIG Windows, ferramenta experimental).
    //
    // Copia o corpo do áudio principal e reencoda SÓ o trecho inserido: o
    // caminho de cópia não corta em qualquer ponto (o ponto fica aproximado ao
    // frame/pacote do codec) — é o preço da preservação do corpo. Quando o codec
    // da fonte não pode ser preservado na saída, o chamador cai no modo preciso.
    // ---------------------------------------------------------------------

    /** Trecho do principal antes da inserção, COPIADO sem reencodar. */
    fun insertSmartLeftArguments(
        mainPath: String,
        outputPath: String,
        insertionSeconds: Double
    ): Array<String> = arrayOf(
        "-y", "-ss", "0", "-i", mainPath,
        "-t", String.format(Locale.US, "%.6f", insertionSeconds.coerceAtLeast(0.0)),
        "-map", "0:a:0", "-c", "copy", "-avoid_negative_ts", "make_zero", outputPath
    )

    /** Trecho do principal depois da inserção, COPIADO sem reencodar. */
    fun insertSmartRightArguments(
        mainPath: String,
        outputPath: String,
        insertionSeconds: Double
    ): Array<String> = arrayOf(
        "-y", "-ss", String.format(Locale.US, "%.6f", insertionSeconds.coerceAtLeast(0.0)),
        "-i", mainPath, "-map", "0:a:0", "-c", "copy", outputPath
    )

    /**
     * Áudio inserido: reencodado no formato do principal e com as curvas de
     * fade escolhidas (as curvas do Smart Insert suavizam SÓ o trecho inserido,
     * como no SIG Windows).
     */
    fun insertSmartMiddleArguments(
        insertedPath: String,
        outputPath: String,
        insertedDurationSeconds: Double,
        sampleRate: Int,
        channels: Int,
        fadeSeconds: Double,
        fadeCurve: String?
    ): Array<String> = buildList {
        addAll(listOf("-y", "-i", insertedPath, "-map", "0:a:0"))
        addAll(listOf("-ar", sampleRate.toString(), "-ac", channels.toString()))
        // Como no Windows: o fade efetivo não passa de metade do inserido e o
        // fade-out começa em (duração - fade).
        val efetivo = minOf(fadeSeconds.coerceAtLeast(0.0), insertedDurationSeconds.coerceAtLeast(0.0) / 2)
        if (efetivo > 0.0) {
            val curva = fadeCurve?.takeIf { it.isNotBlank() && it != "fade" }?.let { ":curve=$it" }.orEmpty()
            val d = String.format(Locale.US, "%.6f", efetivo)
            val st = String.format(Locale.US, "%.6f", (insertedDurationSeconds - efetivo).coerceAtLeast(0.0))
            addAll(listOf("-af", "afade=t=in:st=0:d=$d$curva,afade=t=out:st=$st:d=$d$curva"))
        }
        addAll(listOf("-c:a", "pcm_s16le", "-f", "wav", outputPath))
    }.toTypedArray()

    /** Concat final do Smart Insert (peças já no mesmo formato). */
    fun insertSmartConcatArguments(
        listPath: String,
        outputPath: String,
        sampleRate: Int,
        channels: Int
    ): Array<String> = arrayOf(
        "-y", "-f", "concat", "-safe", "0", "-i", listPath,
        "-map", "0:a:0", "-c:a", "pcm_s16le",
        "-ar", sampleRate.toString(), "-ac", channels.toString(),
        "-avoid_negative_ts", "make_zero", outputPath
    )

    /**
     * O Smart Insert só preserva o corpo quando o codec cabe na saída WAV/PCM.
     *
     * O Android reporta PCM como `raw` (subtipo do MIME `audio/raw`) e o ffmpeg
     * chama de `pcm_*` — as duas famílias valem.
     */
    fun insertSmartCanPreserveCodec(sourceCodec: String?): Boolean {
        val codec = sourceCodec?.lowercase(Locale.ROOT)?.trim().orEmpty()
        if (codec.isEmpty()) return false
        return codec.startsWith("pcm_") || codec in setOf("raw", "wav", "x-wav", "lpcm")
    }

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

    /** Filtro de bitstream que leva o vídeo do MP4/MOV para MPEG-TS (concat do
     * SmartCut); `null` quando o codec não tem par TS (VP9/AV1 etc.). */
    fun tsBitstreamFilter(codecFamily: String?): String? = when (codecFamily?.lowercase(Locale.ROOT)) {
        "h264", "avc", "avc1" -> "h264_mp4toannexb"
        "hevc", "h265", "hvc1", "hev1" -> "hevc_mp4toannexb"
        else -> null
    }

    /**
     * Modo "Sem Reencode": cópia fiel dos streams, com os limites escorregando
     * até o keyframe/pacote disponível. O seek fica ANTES do input (o seek de
     * saída faria o FFmpeg recuar um GOP inteiro).
     */
    fun cutCopyCommandArguments(
        inputPath: String,
        outputPath: String,
        start: String,
        duration: String,
        rotationArguments: List<String>
    ): Array<String> = buildList {
        addAll(listOf("-y", "-noautorotate"))
        addAll(rotationArguments)
        addAll(listOf("-ss", start, "-i", inputPath, "-t", duration))
        addAll(cutMappedCopyArguments())
        addAll(listOf("-avoid_negative_ts", "make_zero", outputPath))
    }.toTypedArray()

    /**
     * Um trecho do SmartCut em MPEG-TS, pronto para o concat.
     *
     * As bordas reencodadas espelham o trecho COPIADO (mesmo codec, mesmo
     * pix_fmt, mesmo fps e bitrate de referência) porque é o miolo intocado que
     * dita o padrão do arquivo final. O vídeo copiado leva o bitstream filter
     * (mp4toannexb) e o `mpegts_flags` reenvia os parâmetros no início de cada
     * trecho — as duas correções medidas no Windows (miolo recuando um GOP e
     * emenda sem os parâmetros do codec).
     */
    fun hybridSegmentArguments(
        inputPath: String,
        outputPath: String,
        startUs: Long,
        durationSeconds: Double,
        codecFamily: String?,
        reencode: Boolean,
        hasAudio: Boolean,
        videoArguments: List<String> = emptyList(),
        audioArguments: List<String> = emptyList(),
        frameRate: Double? = null
    ): Array<String> {
        val safeStart = startUs.coerceAtLeast(0L)
        val start = String.format(Locale.US, "%.6f", safeStart / 1_000_000.0)
        val duration = String.format(Locale.US, "%.6f", durationSeconds.coerceAtLeast(0.0))
        return buildList {
            addAll(listOf("-y", "-noautorotate", "-display_rotation:v:0", "0"))
            addAll(listOf("-ss", start, "-i", inputPath, "-t", duration))
            add("-map")
            add("0:v:0")
            // F5: o trecho do SmartCut é SÓ VÍDEO. O áudio saía por trecho e cada
            // segmento começava numa fronteira de pacote AAC, acumulando ~20 ms
            // por emenda (medido: +60 ms num corte de duas emendas). Agora o áudio
            // vem de uma passagem única sobre a fonte, no mux final.
            // (hasAudio/audioArguments ficam na assinatura por compatibilidade.)
            add("-an")
            if (reencode) {
                addAll(videoArguments)
                frameRate?.takeIf { it in 1.0..240.0 }?.let {
                    addAll(listOf("-r", String.format(Locale.US, "%.6f", it)))
                }
            } else {
                addAll(listOf("-c:v", "copy"))
            }
            tsBitstreamFilter(codecFamily)?.let {
                addAll(listOf("-bsf:v", it))
            }
            addAll(
                listOf(
                    "-avoid_negative_ts", "make_zero",
                    "-mpegts_flags", "+resend_headers+initial_discontinuity",
                    "-muxdelay", "0", "-muxpreload", "0",
                    "-f", "mpegts", outputPath
                )
            )
        }.toTypedArray()
    }

    /**
     * Argumentos do mux final do SmartCut (F5): vídeo concatenado (entrada 1)
     * + áudio CONTÍNUO da fonte (entrada 0, com seek no início pedido).
     *
     * Antes o áudio também vinha dos trechos, e cada um começava numa fronteira
     * de pacote AAC: o atraso acumulava ~20 ms por emenda. Numa passagem única
     * não existe emenda de áudio — sobra só o priming do encoder (offset fixo).
     */
    fun hybridConcatArguments(
        listPath: String,
        outputPath: String,
        rotationDegrees: Int,
        hasAudio: Boolean,
        sourcePath: String,
        startUs: Long,
        hevc: Boolean,
        audioArguments: List<String> = emptyList()
    ): Array<String> = buildList {
        addAll(listOf("-y", "-fflags", "+genpts"))
        val inicio = String.format(Locale.US, "%.6f", startUs.coerceAtLeast(0L) / 1_000_000.0)
        addAll(listOf("-ss", inicio, "-i", sourcePath))
        addAll(listOf("-display_rotation:v:0", rotationDegrees.toString(), "-f", "concat", "-safe", "0", "-i", listPath))
        addAll(listOf("-map", "1:v:0"))
        if (hasAudio) addAll(listOf("-map", "0:a?"))
        addAll(listOf("-c:v", "copy"))
        if (!hasAudio) {
            add("-an")
        } else if (audioArguments.isEmpty()) {
            addAll(listOf("-c:a", "aac"))
        } else {
            addAll(audioArguments)
        }
        if (hevc) addAll(listOf("-tag:v", "hvc1"))
        addAll(listOf("-avoid_negative_ts", "make_zero", "-max_interleave_delta", "0"))
        addAll(listOf("-map_metadata", "0", "-map_chapters", "-1", outputPath))
    }.toTypedArray()

    fun hybridCopyBodyArguments(inputPath: String, outputPath: String, startUs: Long, endUs: Long): Array<String> {
        val safeStart = startUs.coerceAtLeast(0L)
        val safeEnd = endUs.coerceAtLeast(safeStart + 1L)
        return hybridSegmentArguments(
            inputPath = inputPath,
            outputPath = outputPath,
            startUs = safeStart,
            durationSeconds = (safeEnd - safeStart) / 1_000_000.0,
            codecFamily = null,
            reencode = false,
            hasAudio = true
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

    fun audioJoinFilterComplex(
        inputs: List<FfmpegAudioJoinFilterInput>,
        normalizeFilter: String,
        outputLabels: List<String>,
        transitionSeconds: Double,
        fadeInOut: Boolean,
        crossfadeCurve: String?
    ): String {
        require(inputs.isNotEmpty()) { "At least one audio input is required" }
        require(outputLabels.isNotEmpty()) { "At least one audio output is required" }
        require(inputs.all { it.audioInputSpecifiers.size == outputLabels.size }) {
            "Every input must provide one audio stream per output"
        }
        val transition = transitionSeconds.coerceAtLeast(0.0)
        val transitionText = filterDecimal(transition)
        val parts = mutableListOf<String>()
        outputLabels.indices.forEach { track ->
            val preparedLabels = mutableListOf<String>()
            inputs.forEachIndexed { index, input ->
                val fades = mutableListOf<String>()
                if (fadeInOut && transition > 0.0) {
                    if (index > 0) fades += "afade=t=in:st=0:d=$transitionText"
                    if (index < inputs.lastIndex) {
                        val fadeStart = (input.durationSeconds - transition).coerceAtLeast(0.0)
                        fades += "afade=t=out:st=${filterDecimal(fadeStart)}:d=$transitionText"
                    }
                }
                val prepared = preparedAudioLabel(index, track, outputLabels.size)
                parts += normalizedAudioFilter(
                    input.audioInputSpecifiers[track], normalizeFilter, fades, prepared
                )
                preparedLabels += prepared
            }
            val outputLabel = outputLabels[track]
            if (!fadeInOut && transition > 0.0 && crossfadeCurve != null) {
                parts += audioCrossfadeChain(
                    preparedLabels,
                    transitionText,
                    crossfadeCurve,
                    outputLabel,
                    intermediatePrefix = "ax${track}_"
                )
            } else {
                parts += audioConcatFilter(preparedLabels, outputLabel)
            }
        }
        return parts.joinToString(";")
    }

    fun videoJoinFilterComplex(
        inputs: List<FfmpegVideoJoinFilterInput>,
        videoFilter: String,
        sampleRate: Int,
        audioLayout: String,
        outputAudioLabels: List<String>,
        transitionSeconds: Double,
        fadeInOut: Boolean,
        xfadeTransition: String,
        audioCrossfadeCurve: String = "tri"
    ): String {
        require(inputs.isNotEmpty()) { "At least one video input is required" }
        require(outputAudioLabels.isNotEmpty()) { "At least one audio output is required" }
        require(inputs.filter { it.hasAudio }.all { it.audioInputSpecifiers.size == outputAudioLabels.size }) {
            "Every audio-bearing input must provide one stream per output"
        }
        val transition = transitionSeconds.coerceAtLeast(0.0)
        val transitionText = filterDecimal(transition)
        val parts = mutableListOf<String>()
        inputs.forEachIndexed { index, input ->
            val clipSeconds = input.durationSeconds.coerceAtLeast(0.001)
            val fadeDuration = if (fadeInOut && transition > 0.0) {
                transition.coerceAtMost((clipSeconds / 2.0).coerceAtLeast(0.1))
            } else 0.0
            val videoFades = mutableListOf<String>()
            if (fadeDuration > 0.0) {
                if (index > 0) videoFades += "fade=t=in:st=0:d=${filterDecimal(fadeDuration)}"
                if (index < inputs.lastIndex) {
                    videoFades += "fade=t=out:st=${filterDecimal((clipSeconds - fadeDuration).coerceAtLeast(0.0))}:d=${filterDecimal(fadeDuration)}"
                }
            }
            parts += buildString {
                append('[').append(index).append(":v]").append(videoFilter)
                if (fadeInOut) append(",setpts=PTS-STARTPTS")
                videoFades.forEach { append(',').append(it) }
                append("[v").append(index).append(']')
            }
            outputAudioLabels.indices.forEach { track ->
                val prepared = preparedAudioLabel(index, track, outputAudioLabels.size)
                val audioFades = mutableListOf<String>()
                if (fadeDuration > 0.0) {
                    if (index > 0) audioFades += "afade=t=in:st=0:d=${filterDecimal(fadeDuration)}"
                    if (index < inputs.lastIndex) {
                        audioFades += "afade=t=out:st=${filterDecimal((clipSeconds - fadeDuration).coerceAtLeast(0.0))}:d=${filterDecimal(fadeDuration)}"
                    }
                }
                parts += if (input.hasAudio) {
                    buildString {
                        append('[').append(input.audioInputSpecifiers[track]).append(']')
                        append("aresample=").append(sampleRate).append(',')
                        append("aformat=sample_fmts=fltp:sample_rates=").append(sampleRate)
                        append(":channel_layouts=").append(audioLayout)
                        if (fadeInOut) append(",asetpts=PTS-STARTPTS")
                        audioFades.forEach { append(',').append(it) }
                        append('[').append(prepared).append(']')
                    }
                } else {
                    buildString {
                        append("anullsrc=channel_layout=").append(audioLayout)
                        append(":sample_rate=").append(sampleRate)
                        append(",atrim=0:").append(filterDecimal(clipSeconds)).append(",asetpts=N/SR/TB")
                        audioFades.forEach { append(',').append(it) }
                        append('[').append(prepared).append(']')
                    }
                }
            }
        }

        if (!fadeInOut && transition > 0.0 && inputs.size > 1) {
            var lastVideo = "v0"
            var accumulatedSeconds = inputs.first().durationSeconds
            inputs.indices.drop(1).forEach { index ->
                val output = "vx$index"
                val offset = (accumulatedSeconds - transition).coerceAtLeast(0.0)
                parts += "[$lastVideo][v$index]xfade=transition=$xfadeTransition:duration=$transitionText:offset=${filterDecimal(offset)}[$output]"
                lastVideo = output
                accumulatedSeconds += inputs[index].durationSeconds - transition
            }
            parts += "[$lastVideo]copy[vout]"
            outputAudioLabels.indices.forEach { track ->
                parts += audioCrossfadeChain(
                    inputs.indices.map { preparedAudioLabel(it, track, outputAudioLabels.size) },
                    transitionText,
                    audioCrossfadeCurve,
                    outputAudioLabels[track],
                    intermediatePrefix = "vx_audio_${track}_"
                )
            }
        } else {
            parts += videoConcatFilter(inputs.indices.map { "v$it" })
            outputAudioLabels.indices.forEach { track ->
                parts += audioConcatFilter(
                    inputs.indices.map { preparedAudioLabel(it, track, outputAudioLabels.size) },
                    outputAudioLabels[track]
                )
            }
        }
        return parts.joinToString(";")
    }

    fun insertAudioFilterComplex(
        mainInputSpecifier: String,
        insertedInputSpecifier: String,
        mainDurationSeconds: Double,
        insertedDurationSeconds: Double,
        insertionSeconds: Double,
        normalizeFilter: String,
        requestedTransitionSeconds: Double,
        fadeInOut: Boolean,
        crossfadeCurve: String?
    ): String {
        val mainEnd = mainDurationSeconds.coerceAtLeast(0.001)
        val insertedEnd = insertedDurationSeconds.coerceAtLeast(0.001)
        val at = insertionSeconds.coerceIn(0.0, mainEnd)
        val neighboringDurations = mutableListOf(insertedEnd)
        if (at > 0.0) neighboringDurations += at
        if (mainEnd - at > 0.0) neighboringDurations += mainEnd - at
        val effectiveFade = requestedTransitionSeconds.coerceAtLeast(0.0)
            .coerceAtMost((neighboringDurations.minOrNull() ?: 0.0) / 2.0)
        val hasLeft = at > 0.0
        val hasRight = mainEnd - at > 0.0
        val useFade = fadeInOut && effectiveFade > 0.0
        val useCrossfade = crossfadeCurve != null && !useFade && effectiveFade > 0.0
        val fadeText = filterDecimal(effectiveFade)
        val filters = mutableListOf<String>()
        val labels = mutableListOf<String>()
        if (hasLeft) {
            val fade = if (useFade) ",afade=t=out:st=${filterDecimal((at - effectiveFade).coerceAtLeast(0.0))}:d=$fadeText" else ""
            filters += "[$mainInputSpecifier]atrim=start=0:end=${filterDecimal(at)},$normalizeFilter,asetpts=PTS-STARTPTS$fade[a0]"
            labels += "a0"
        }
        val insertedFades = buildString {
            if (useFade && hasLeft) append(",afade=t=in:st=0:d=$fadeText")
            if (useFade && hasRight) append(",afade=t=out:st=${filterDecimal((insertedEnd - effectiveFade).coerceAtLeast(0.0))}:d=$fadeText")
        }
        filters += "[$insertedInputSpecifier]atrim=start=0:end=${filterDecimal(insertedEnd)},$normalizeFilter,asetpts=PTS-STARTPTS$insertedFades[a1]"
        labels += "a1"
        if (hasRight) {
            val fade = if (useFade) ",afade=t=in:st=0:d=$fadeText" else ""
            filters += "[$mainInputSpecifier]atrim=start=${filterDecimal(at)}:end=${filterDecimal(mainEnd)},$normalizeFilter,asetpts=PTS-STARTPTS$fade[a2]"
            labels += "a2"
        }
        if (useCrossfade && labels.size > 1) {
            filters += audioCrossfadeChain(labels, fadeText, checkNotNull(crossfadeCurve))
        } else {
            filters += audioConcatFilter(labels)
        }
        return filters.joinToString(";")
    }

    fun audioConcatFilter(inputLabels: List<String>, outputLabel: String = "aout"): String {
        require(inputLabels.isNotEmpty()) { "At least one audio input is required" }
        return inputLabels.joinToString("") { "[$it]" } +
            "concat=n=${inputLabels.size}:v=0:a=1[$outputLabel]"
    }

    fun videoAudioConcatFilter(inputCount: Int, outputVideo: String = "vout", outputAudio: String = "aout"): String {
        require(inputCount > 0) { "At least one input is required" }
        val inputs = (0 until inputCount).joinToString("") { "[v$it][a$it]" }
        return "${inputs}concat=n=$inputCount:v=1:a=1[$outputVideo][$outputAudio]"
    }

    fun videoConcatFilter(inputLabels: List<String>, outputLabel: String = "vout"): String {
        require(inputLabels.isNotEmpty()) { "At least one video input is required" }
        return inputLabels.joinToString("") { "[$it]" } +
            "concat=n=${inputLabels.size}:v=1:a=0[$outputLabel]"
    }

    fun audioCrossfadeChain(
        inputLabels: List<String>,
        duration: String,
        curve: String,
        outputLabel: String = "aout",
        intermediatePrefix: String = "ax"
    ): List<String> {
        require(inputLabels.isNotEmpty()) { "At least one audio input is required" }
        if (inputLabels.size == 1) return listOf("[${inputLabels.first()}]anull[$outputLabel]")
        val filters = mutableListOf<String>()
        var previous = inputLabels.first()
        inputLabels.drop(1).forEachIndexed { zeroBasedIndex, input ->
            val index = zeroBasedIndex + 1
            val output = if (index == inputLabels.lastIndex) outputLabel else "$intermediatePrefix$index"
            filters += "[$previous][$input]acrossfade=d=$duration:c1=$curve:c2=$curve[$output]"
            previous = output
        }
        return filters
    }

    private fun preparedAudioLabel(inputIndex: Int, trackIndex: Int, trackCount: Int): String =
        if (trackCount == 1) "a$inputIndex" else "a${trackIndex}_$inputIndex"

    private fun filterDecimal(value: Double): String = String.format(Locale.US, "%.3f", value)

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

    /** Gera um nome de arquivo sem colisao, inserindo o sufixo numerico ANTES
     * da extensao (ex.: "audio (1).wav"), seguindo a convencao do Android.
     * [nameExists] decide se um candidato ja esta em uso (ex.: consulta o
     * DocumentFile da pasta de destino). */
    fun uniqueOutputName(desiredName: String, nameExists: (String) -> Boolean): String {
        if (!nameExists(desiredName)) return desiredName
        val dot = desiredName.lastIndexOf('.')
        val base = if (dot > 0) desiredName.substring(0, dot) else desiredName
        val extension = if (dot > 0) desiredName.substring(dot) else ""
        var suffix = 0
        while (true) {
            suffix++
            val candidate = "$base ($suffix)$extension"
            if (!nameExists(candidate)) return candidate
        }
    }

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

    fun containerFamilyFromProbe(formatNames: String): String {
        val formats = formatNames.lowercase(Locale.ROOT).split(',').map(String::trim)
        return when {
            formats.any { it in setOf("matroska", "matroska,webm") } -> "matroska"
            "webm" in formats -> "webm"
            formats.any { it in setOf("mov", "mp4", "m4a", "3gp", "3g2", "mj2") } -> "mov"
            "avi" in formats -> "avi"
            "wav" in formats -> "wav"
            "mp3" in formats -> "mp3"
            "flac" in formats -> "flac"
            formats.any { it in setOf("ogg", "opus") } -> "ogg"
            formats.any { it in setOf("aac", "adts") } -> "adts"
            else -> "unknown"
        }
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

    /**
     * Formata o comando exibido no log e na tela sem vazar o caminho interno
     * do cache. Os argumentos reais usados pelo FFmpeg não são alterados;
     * apenas a representação textual troca os valores de -i por
     * input.ext/input2.ext/... e os arquivos de saída por
     * output.ext/output2.ext/....
     */
    fun formatCommand(arguments: Iterable<String>): String {
        val values = arguments.toList()
        val inputIndexes = values.indices
            .filter { index -> index > 0 && values[index - 1] == "-i" }
        val inputIndexSet = inputIndexes.toSet()
        val outputIndexes = findOutputIndexes(values, inputIndexSet)
        val inputOrdinalByIndex = inputIndexes.withIndex().associate { (ordinal, index) -> index to ordinal }
        val outputOrdinalByIndex = outputIndexes.withIndex().associate { (ordinal, index) -> index to ordinal }
        val displayValues = values.mapIndexed { index, argument ->
            when {
                index in inputOrdinalByIndex ->
                    compactCommandFileName(argument, "input", inputOrdinalByIndex.getValue(index))
                index in outputOrdinalByIndex ->
                    compactCommandFileName(argument, "output", outputOrdinalByIndex.getValue(index))
                else -> argument
            }
        }

        return buildString {
            append("ffmpeg")
            displayValues.forEach { argument ->
                append(' ')
                if (argument.isNotEmpty() && argument.none(Char::isWhitespace) && '"' !in argument) {
                    append(argument)
                } else {
                    append('"').append(argument.replace("\\", "\\\\").replace("\"", "\\\"")).append('"')
                }
            }
        }
    }

    private fun findOutputIndexes(values: List<String>, inputIndexes: Set<Int>): List<Int> {
        // Os seis builders FFmpeg do app colocam as saídas depois de todas as
        // entradas. Só consideramos argumentos com aparência de arquivo e
        // ignoramos valores imediatamente posteriores a uma opção (por
        // exemplo, o texto de -filter_complex), evitando renomear parâmetros.
        val lastInputIndex = inputIndexes.maxOrNull() ?: -1
        return values.indices.filter { index ->
            index > lastInputIndex &&
                index !in inputIndexes &&
                looksLikeFileArgument(values[index]) &&
                (index == 0 || !values[index - 1].startsWith("-"))
        }
    }

    private fun compactCommandFileName(argument: String, role: String, ordinal: Int): String {
        val displayRole = if (ordinal == 0) role else role + (ordinal + 1)
        val leaf = argument
            .trim()
            .trim('"')
            .replace('\\', '/')
            .substringAfterLast('/')
        val extension = leaf.substringAfterLast('.', "")
            .takeIf { value ->
                value.isNotBlank() && value.length <= 12 && value.all(Char::isLetterOrDigit)
            }
            ?.lowercase(Locale.ROOT)
        return extension?.let { "$displayRole.$it" } ?: displayRole
    }

    private fun looksLikeFileArgument(argument: String): Boolean {
        if (argument.isBlank() || argument.startsWith("-")) return false
        val leaf = argument.trim().trim('"').replace('\\', '/').substringAfterLast('/')
        return '/' in argument || '\\' in argument ||
            leaf.substringAfterLast('.', "").let { extension ->
                extension.isNotBlank() &&
                    extension.length <= 12 &&
                    extension.all(Char::isLetterOrDigit) &&
                    extension.any(Char::isLetter)
            }
    }
}
