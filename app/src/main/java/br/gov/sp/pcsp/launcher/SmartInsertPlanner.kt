package br.gov.sp.pcsp.launcher

import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToLong

/** Plano por amostras, formatos e filtros compartilhados pela inserção e sua prévia. */
object SmartInsertPlanner {
    val pcmCodecs = setOf("pcm_u8", "pcm_s16le", "pcm_s24le", "pcm_s32le", "pcm_f32le", "pcm_f64le")
    val curves = setOf("tri", "qsin", "esin", "hsin", "log", "ipar", "qua", "cub", "squ", "cbr", "par",
        "exp", "iqsin", "ihsin", "dese", "desi", "losi", "sinc", "isinc", "quat", "quatr", "qsin2", "hsin2", "nofade")

    data class Audio(val codec: String, val rate: Int, val channels: Int, val layout: String,
        val samples: Long, val sampleFormat: String, val bits: Int, val bitrate: String, val track: Int = 0,
        val startTime: Double = 0.0, val seekOffset: Double = 0.0) {
        val duration get() = samples.toDouble() / rate
        val encoder get() = when (codec) { "mp3" -> "libmp3lame"; "opus" -> "libopus"; "vorbis" -> "libvorbis"; else -> codec }
        val lossless get() = codec in pcmCodecs || codec in setOf("alac", "flac")
        fun encoderArguments(): List<String> = buildList {
            addAll(listOf("-c:a", encoder))
            if (!lossless) addAll(listOf("-b:a", bitrate))
            if (codec == "alac" && sampleFormat in setOf("s16p", "s32p")) addAll(listOf("-sample_fmt", sampleFormat))
            if (codec == "flac" && sampleFormat in setOf("s16", "s32")) {
                addAll(listOf("-sample_fmt", sampleFormat))
                if (bits > 0) addAll(listOf("-bits_per_raw_sample", bits.toString()))
            }
        }
    }

    data class Render(val rate: Int, val main: Long, val inserted: Long, val cut: Long, val fade: Long,
        val curve: String, val smart: Boolean) {
        val crossfade get() = fade > 0 && !smart && curve !in setOf("none", "fade")
        val boundaries get() = (if (cut > 0) 1 else 0) + (if (cut < main) 1 else 0)
        val total get() = main + inserted - if (crossfade) fade * boundaries else 0
        fun outputPosition(composite: Long): Long = if (!crossfade) composite else
            (composite - (if (cut > 0 && composite >= cut) fade else 0) -
                (if (cut < main && composite >= cut + inserted) fade else 0)).coerceAtLeast(0)
        fun compositePosition(output: Long): Long {
            if (!crossfade) return output
            var value = output
            if (cut > 0 && output >= cut - fade) value += fade
            if (cut < main && output >= cut + inserted - fade * boundaries) value += fade
            return value
        }
    }

    data class Packet(val start: Long, val count: Long, val offset: Long, val size: Int)
    data class Splice(val left: Long, val right: Long, val prefix: List<Packet>, val suffix: List<Packet>,
        val headEnd: Long = 0,val tailStart: Long = 0)

    fun decimal(value: Double): String = String.format(Locale.US, "%.9f", value)
    fun sampleCount(seconds: Double, rate: Int): Long {
        require(seconds.isFinite() && seconds >= 0 && rate > 0 && seconds * rate < Long.MAX_VALUE / 4.0) { "Duração inválida." }
        return (seconds * rate).roundToLong()
    }

    fun readAudio(info: JSONObject, track: Int = 0, skipSamples: Long = 0): Audio {
        val streams = info.getJSONArray("streams")
        val audio = (0 until streams.length()).map { streams.getJSONObject(it) }
            .filter { it.optString("codec_type") == "audio" }.getOrNull(track)
            ?: throw IllegalArgumentException("A faixa de áudio selecionada não foi encontrada.")
        val rate = audio.optInt("sample_rate")
        val channels = audio.optInt("channels")
        require(rate > 0 && channels > 0) { "Formato de áudio inválido." }
        val clock = audio.optString("time_base").split('/')
        val duration = if (audio.has("duration_ts") && clock.size == 2 && clock[1].toDoubleOrNull()?.let { it > 0 } == true)
            audio.getDouble("duration_ts") * clock[0].toDouble() / clock[1].toDouble()
        else audio.optDouble("duration", info.optJSONObject("format")?.optDouble("duration", Double.NaN) ?: Double.NaN)
        val codec = audio.getString("codec_name")
        val samples = sampleCount(duration, rate) - if (codec == "opus") skipSamples else 0
        require(samples > 0) { "O áudio não contém amostras suficientes." }
        val layout = audio.optString("channel_layout").takeIf { it.isNotBlank() && it != "unknown" }
            ?: FfmpegMediaPolicies.channelLayout(channels)
        return Audio(codec, rate, channels, layout, samples, audio.optString("sample_fmt"),
            audio.optInt("bits_per_raw_sample"), "${(audio.optLong("bit_rate", 192000) / 1000).coerceAtLeast(1)}k", track,
            audio.optDouble("start_time",0.0),
            audio.optDouble("start_time",0.0) - (info.optJSONObject("format")?.optDouble("start_time",0.0) ?: 0.0))
    }

    fun render(main: Long, inserted: Long, cut: Long, rate: Int, seconds: Double, curve: String, smart: Boolean): Render {
        require(main >= 0 && inserted > 0 && rate > 0 && seconds.isFinite() && seconds >= 0) { "Ponto ou tempo de transição inválido." }
        require(curve in curves || curve in setOf("none", "fade")) { "Curva de transição inválida." }
        val at = cut.coerceIn(0, main)
        var limit = inserted / 2
        if (!smart) {
            if (at > 0) limit = minOf(limit, at / 2)
            if (at < main) limit = minOf(limit, (main - at) / 2)
        }
        val fade = if (curve == "none") 0 else minOf(sampleCount(seconds, rate), limit)
        return Render(rate, main, inserted, at, fade, curve, smart)
    }

    fun filter(plan: Render, mainInput: String, insertedInput: String, layout: String): String {
        val normalize = "asetpts=PTS-STARTPTS,aresample=${plan.rate},aformat=sample_fmts=dblp:sample_rates=${plan.rate}:channel_layouts=$layout,apad"
        fun window(input: String, start: Long, end: Long) = "[$input]$normalize,atrim=start_sample=$start:end_sample=$end,asetpts=PTS-STARTPTS"
        // Curvas adicionadas após FFmpeg 6: a composição mantém a função de
        // ganho em double e não passa opções desconhecidas ao FFmpegKit nativo.
        val composed=plan.curve in setOf("quat","quatr","qsin2","hsin2")
        fun fade(direction: String, start: Long = 0, curve: String = "fade"): String {
            if(curve=="quatr") {
                val gain=if(direction=="in")"(n-$start)/${plan.fade}" else "(${start+plan.fade}-n)/${plan.fade}"
                return ",aeval=exprs='val(ch)*pow(clip($gain,0,1),0.25)':c=same"
            }
            val native=when(curve) { "quat" -> List(4) { "tri" };
                "qsin2" -> List(2) { "qsin" }; "hsin2" -> List(2) { "hsin" };else -> listOf(curve) }
            return native.joinToString("") { ",afade=t=$direction:ss=$start:ns=${plan.fade}" + if(it=="fade")"" else ":curve=$it" }
        }
        val smooth = plan.fade > 0 && (plan.smart || plan.curve == "fade" || composed)
        val parts = mutableListOf<String>()
        val labels = mutableListOf<String>()
        if (plan.cut > 0) {
            parts += window(mainInput, 0, plan.cut) + (if (smooth && !plan.smart)
                fade("out",plan.cut-plan.fade-if(plan.crossfade)1 else 0,if(composed)plan.curve else "fade") else "") + "[a0]"
            labels += "a0"
        }
        var effect = ""
        if (smooth) {
            if (plan.smart || plan.cut > 0) effect += fade("in",curve=plan.curve)
            if (plan.smart || plan.cut < plan.main) effect += fade("out",plan.inserted-plan.fade-if(plan.crossfade)1 else 0,plan.curve)
        }
        parts += window(insertedInput,0,plan.inserted) + effect + "[a1]"
        labels += "a1"
        if (plan.cut < plan.main) {
            parts += window(mainInput,plan.cut,plan.main) + (if (smooth && !plan.smart) fade("in",curve=if(composed)plan.curve else "fade") else "") + "[a2]"
            labels += "a2"
        }
        if (plan.crossfade) {
            var previous=labels.first()
            labels.drop(1).forEachIndexed { index,next ->
                val output=if(index==labels.size-2)"aout" else "mix$index"
                val curve=if(composed)"nofade" else plan.curve
                parts += "[$previous][$next]acrossfade=ns=${plan.fade}:c1=$curve:c2=$curve[$output]"
                previous=output
            }
        }
        else parts += FfmpegMediaPolicies.audioConcatFilter(labels)
        return parts.joinToString(";")
    }

    fun packets(info: JSONObject, rate: Int): List<Packet> {
        val array = info.getJSONArray("packets")
        val streams = info.getJSONArray("streams")
        val origin = streams.getJSONObject(0).optDouble("start_time",0.0)
        return (0 until array.length()).map {
            val packet = array.getJSONObject(it)
            val start = ((packet.getDouble("pts_time")-origin)*rate).roundToLong()
            Packet(start,sampleCount(packet.getDouble("duration_time"),rate),packet.getLong("pos"),packet.getInt("size"))
        }
    }

    fun csvPackets(text: String, rate: Int, origin: Double): List<Packet> = text.lineSequence()
        .filter { it.isNotBlank() }.map { line ->
            val values=line.trim().split(',')
            // FFprobe acrescenta Skip Samples/discard padding depois destes
            // quatro campos quando o MP4 tem edit list/preroll.
            require(values.size>=4) { "Tabela de pacotes inválida." }
            val start=values[0].toDoubleOrNull();val duration=values[1].toDoubleOrNull()
            val size=values[2].toIntOrNull();val offset=values[3].toLongOrNull()
            require(start!=null && start.isFinite() && duration!=null && size!=null && size>0 && offset!=null && offset>=0) { "Pacote de áudio inválido." }
            Packet(((start-origin)*rate).roundToLong(),sampleCount(duration,rate),offset,size)
        }.toList()

    fun splice(packets: List<Packet>, main: Long, inserted: Long, cut: Long, minimumBridge: Int = 0, endTolerance: Long = 0): Splice {
        // O relógio da edit list MOV pode arredondar o EOF para milissegundos.
        // Só essa fração final é reconstruída com o mesmo apad do encode contínuo.
        require(endTolerance>=0)
        require(packets.isNotEmpty() && packets.first().start <= 0L && packets.last().let { it.start+it.count } >= main-endTolerance &&
            packets.all { it.count > 0 } && packets.zipWithNext().all { (a,b) -> a.start+a.count == b.start }) {
            "Os pacotes não formam uma sequência contínua de amostras."
        }
        val at = cut.coerceIn(0,main)
        var head=packets.firstOrNull { it.start<0 && it.start+it.count>0 }?.let { it.start+it.count } ?: 0
        var tail=packets.lastOrNull { it.start<main && it.start+it.count>main }?.start
            ?: packets.last().takeIf { it.start+it.count<main }?.start ?: main
        head=minOf(main,head);tail=maxOf(0,tail)
        val borders = (listOf(0L,main)+packets.map { it.start }.filter { it in 1 until main }).distinct().sorted()
        var left = borders.last { it <= at }
        var right = borders.first { it >= at }
        if(at<head)right=maxOf(right,head)
        if(at>tail)left=minOf(left,tail)
        if (minimumBridge > 0) {
            if (left == main && main-packets.last().start < minimumBridge) left = packets.last().start
            if (right-left+inserted < minimumBridge) {
                if (right < main) right = borders.first { it > right }
                else if (left > 0) left = borders.last { it < left }
            }
        }
        head=minOf(head,left);tail=maxOf(tail,right)
        return Splice(left,right,packets.filter { it.start>=head && it.start<left && it.start+it.count<=main },
            packets.filter { it.start>=right && it.start+it.count<=tail },head,tail)
    }

    fun canCopy(audio: Audio, extension: String): Boolean = when (extension.lowercase(Locale.ROOT)) {
        "wav" -> audio.codec in pcmCodecs
        "m4a" -> audio.codec == "alac"
        "flac" -> audio.codec == "flac"
        else -> false
    }
}
