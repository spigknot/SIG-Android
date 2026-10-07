package br.gov.sp.pcsp.launcher

import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToLong

/** Inserção testável sem Activity: copia PCM/ALAC/FLAC e publica somente após validar. */
class SmartInsertPipeline @JvmOverloads constructor(
    private val execute: (Array<String>, Double) -> Unit,
    private val probe: (Array<String>) -> JSONObject,
    private val cancelled: () -> Unit = {},
    private val log: (String) -> Unit = {},
    private val packetProbe: ((Array<String>) -> String)? = null
) {
    data class Options(val insertion: Double, val seconds: Double, val curve: String, val smart: Boolean,
        val mainTrack: Int = 0, val insertedTrack: Int = 0)
    data class Result(val plan: SmartInsertPlanner.Render, val audio: SmartInsertPlanner.Audio,
        val partial: Boolean, val reason: String?)

    private fun info(path: File, track: Int = 0, packets: Boolean = false, interval: String? = null): JSONObject {
        cancelled()
        val args = mutableListOf("-v","error","-show_streams","-show_format","-of","json")
        if (packets) args += listOf("-select_streams","a:$track","-show_packets","-show_entries",
            "packet=pts_time,duration_time,pos,size:packet_side_data=skip_samples,discard_padding:stream=codec_type,codec_name,sample_rate,channels,channel_layout,sample_fmt,bits_per_raw_sample,start_time,duration,duration_ts,time_base:format=duration")
        if (interval != null) args += listOf("-read_intervals",interval)
        args += path.absolutePath
        return probe(args.toTypedArray()).also { cancelled() }
    }

    fun readAudio(path: File, track: Int = 0): SmartInsertPlanner.Audio {
        require(track >= 0) { "Faixa de áudio inválida." }
        val metadata = info(path)
        var audio = SmartInsertPlanner.readAudio(metadata,track)
        if (audio.codec == "opus") {
            val first = info(path,track,true,"%+0.1").getJSONArray("packets").optJSONObject(0)
            audio = audio.copy(samples=audio.samples-sideSamples(first,"skip_samples"))
        } else if (audio.codec == "mp3") {
            // LAME/Xing: versões de FFprobe diferem na duração da faixa.
            // O span dos pacotes, descontando skip/padding, é a duração audível.
            val interval = if (audio.duration < .4) null else "%+0.15,${SmartInsertPlanner.decimal(audio.duration-.15)}%"
            var packets = info(path,track,true,interval).getJSONArray("packets")
            require(packets.length() > 0) { "MP3 sem pacotes de áudio." }
            val candidate=packets.getJSONObject(packets.length()-1)
            val tail=candidate.optJSONArray("side_data_list")
            val hasEndPadding=tail!=null && (0 until tail.length()).any { tail.getJSONObject(it).has("discard_padding") }
            // Seeking pelo índice Xing pode ultrapassar o EOF e retornar apenas a
            // primeira janela. Nesse caso ler todos os timestamps, sem decodificar.
            if(interval!=null && (!hasEndPadding || candidate.getDouble("pts_time")+candidate.getDouble("duration_time") < audio.duration-.1))
                packets=info(path,track,true).getJSONArray("packets")
            val first = packets.getJSONObject(0); val last = packets.getJSONObject(packets.length()-1)
            val span = last.getDouble("pts_time")+last.getDouble("duration_time")-first.getDouble("pts_time")
            audio = audio.copy(samples=SmartInsertPlanner.sampleCount(span,audio.rate)-sideSamples(first,"skip_samples")-sideSamples(last,"discard_padding"))
        }
        require(audio.samples > 0) { "Duração do áudio inválida." }
        return audio
    }
    private fun sideSamples(packet: JSONObject?, key: String): Long {
        val array = packet?.optJSONArray("side_data_list") ?: return 0
        return (0 until array.length()).sumOf { array.getJSONObject(it).optLong(key) }
    }

    private fun packets(path: File, audio: SmartInsertPlanner.Audio): List<SmartInsertPlanner.Packet> {
        cancelled()
        val raw=packetProbe ?: return SmartInsertPlanner.packets(info(path,audio.track,true),audio.rate)
        val text=raw(arrayOf("-v","error","-select_streams","a:${audio.track}","-show_packets","-show_entries",
            "packet=pts_time,duration_time,size,pos","-of","csv=p=0",path.absolutePath))
        cancelled()
        return SmartInsertPlanner.csvPackets(text,audio.rate,audio.startTime)
    }

    fun outputAudio(source: SmartInsertPlanner.Audio, extension: String): SmartInsertPlanner.Audio {
        val codec = when (extension.lowercase(Locale.ROOT)) {
            "wav" -> source.codec.takeIf { it in SmartInsertPlanner.pcmCodecs } ?: "pcm_s16le"
            "m4a","mp4" -> source.codec.takeIf { it in setOf("alac","aac") } ?: "aac"
            "flac" -> "flac"
            "ogg" -> source.codec.takeIf { it in setOf("vorbis","opus","flac") } ?: "vorbis"
            "opus" -> "opus"
            "mp3" -> "mp3"
            "aac" -> "aac"
            "wma" -> "wmav2"
            else -> "aac"
        }
        return source.copy(codec=codec, sampleFormat=if (codec==source.codec) source.sampleFormat else "",
            bits=if (codec==source.codec) source.bits else 0)
    }

    fun continuousArguments(main: File, inserted: File, output: File, audio: SmartInsertPlanner.Audio,
        plan: SmartInsertPlanner.Render, mainTrack: Int, insertedTrack: Int): Array<String> = buildList {
        addAll(listOf("-hide_banner","-y","-i",main.absolutePath,"-i",inserted.absolutePath,
            "-filter_complex",SmartInsertPlanner.filter(plan,"0:a:$mainTrack","1:a:$insertedTrack",audio.layout),"-map","[aout]","-vn",
            "-ar",audio.rate.toString(),"-ac",audio.channels.toString()))
        addAll(audio.encoderArguments())
        addAll(listOf("-map_metadata","0","-map_metadata:s:a:0","0:s:a:$mainTrack","-map_chapters","-1"))
        if (output.extension.lowercase(Locale.ROOT) in setOf("m4a","mp4")) addAll(listOf("-movflags","+faststart"))
        add(output.absolutePath)
    }.toTypedArray()

    fun run(main: File, inserted: File, output: File, options: Options, preview: Boolean = false,
        forceContinuous: Boolean = false): Result {
        require(options.insertion.isFinite() && options.seconds.isFinite() && options.seconds >= 0) { "Ponto ou tempo de transição inválido." }
        cancelled()
        val original = readAudio(main,options.mainTrack)
        val other = readAudio(inserted,options.insertedTrack)
        val audio = if(preview)original.copy(codec="pcm_s16le",sampleFormat="",bits=16) else outputAudio(original,output.extension)
        val insertedSamples = SmartInsertPlanner.sampleCount(other.duration,audio.rate)
        val plan = SmartInsertPlanner.render(original.samples,insertedSamples,
            SmartInsertPlanner.sampleCount(options.insertion.coerceAtLeast(0.0),audio.rate),audio.rate,options.seconds,options.curve,options.smart)
        val work = File(output.parentFile,"smart_insert_${UUID.randomUUID()}")
        check(work.mkdirs()) { "Não foi possível criar a pasta temporária." }
        val staged = File(work,output.name)
        var partial = options.smart && !preview && !forceContinuous && SmartInsertPlanner.canCopy(original,output.extension)
        var reason: String? = null
        try {
            if (partial && original.codec in SmartInsertPlanner.pcmCodecs) {
                try { SmartInsertWave.inspect(main) } catch (error: IllegalArgumentException) {
                    partial = false; reason = error.message
                }
                if (partial) {
                    var middle = inserted
                    val canCopyInserted = (plan.fade==0L || plan.curve=="nofade") && runCatching { SmartInsertWave.inspect(inserted).signature == SmartInsertWave.inspect(main).signature }.getOrDefault(false)
                    if (!canCopyInserted) {
                        middle=File(work,"inserted.wav")
                        val insertedPlan=plan.copy(main=0,cut=0)
                        val filter=SmartInsertPlanner.filter(insertedPlan,"0:a:${options.mainTrack}","1:a:${options.insertedTrack}",audio.layout)
                            .replace("[1:a:${options.insertedTrack}]","[0:a:${options.insertedTrack}]")
                        val command=listOf("-hide_banner","-y","-i",inserted.absolutePath,"-filter_complex",filter,
                            "-map","[aout]","-vn","-ar",audio.rate.toString(),"-ac",audio.channels.toString()) + audio.encoderArguments() + middle.absolutePath
                        execute(command.toTypedArray(),other.duration)
                    }
                    cancelled()
                    SmartInsertWave.assemble(main,middle,staged,plan.cut,plan.total,cancelled)
                    log("Smart Insert: principal PCM em cópia por amostras; ${if(canCopyInserted) "inserido também em cópia." else "somente o inserido foi recodificado."}")
                }
            } else if (partial) {
                val splice=try {
                    SmartInsertPlanner.splice(packets(main,original),plan.main,plan.inserted,plan.cut,
                        if(audio.codec=="flac")16 else 0)
                } catch(error: IllegalArgumentException) { partial=false;reason=error.message;null }
                if (splice != null) {
                    val pieces=mutableListOf<File>(); val durations=mutableListOf<Double>()
                    fun copy(start: Long, samples: Long, packets: Int) {
                        val piece=File(work,"${pieces.size}.${output.extension}")
                        val args=listOf("-hide_banner","-y","-ss",SmartInsertPlanner.decimal(start.toDouble()/audio.rate),"-i",main.absolutePath,
                            "-ss","0","-map","0:a:${options.mainTrack}","-c:a","copy","-frames:a",packets.toString(),
                            "-bsf:a","setts=pts=PTS-STARTPTS:dts=DTS-STARTPTS","-avoid_negative_ts","disabled",piece.absolutePath)
                        execute(args.toTypedArray(),samples.toDouble()/audio.rate)
                        validate(piece,audio,samples)
                        pieces+=piece;durations+=samples.toDouble()/audio.rate
                    }
                    if (splice.left>0 && audio.codec!="flac") copy(0,splice.left,splice.prefix.size)
                    val middle=File(work,"middle.${output.extension}")
                    val bridge=SmartInsertPlanner.render(splice.right-splice.left,plan.inserted,plan.cut-splice.left,
                        audio.rate,options.seconds,options.curve,true)
                    val args=continuousArguments(main,inserted,middle,audio,bridge,options.mainTrack,options.insertedTrack).toMutableList()
                    args.addAll(args.indexOf("-i"),listOf("-ss",SmartInsertPlanner.decimal(splice.left.toDouble()/audio.rate)))
                    execute(args.toTypedArray(),bridge.total.toDouble()/audio.rate)
                    validate(middle,audio,bridge.total)
                    pieces+=middle;durations+=bridge.total.toDouble()/audio.rate
                    if (splice.right<plan.main && audio.codec!="flac") copy(splice.right,plan.main-splice.right,splice.suffix.size)
                    if (audio.codec=="flac") {
                        val middlePackets=packets(middle,audio.copy(track=0,startTime=0.0))
                        SmartInsertFlac.assemble(listOf(Pair(main,splice.prefix),Pair(middle,middlePackets),Pair(main,splice.suffix)),staged,main,plan.total,cancelled)
                    } else {
                        val manifest=File(work,"pieces.txt")
                        manifest.writeText(pieces.mapIndexed { index,file ->
                            "file '${file.absolutePath.replace("\\","/").replace("'","'\\''")}'\nduration ${SmartInsertPlanner.decimal(durations[index])}"
                        }.joinToString("\n"))
                        execute(arrayOf("-hide_banner","-y","-f","concat","-safe","0","-i",manifest.absolutePath,"-i",main.absolutePath,
                            "-map","0:a:0","-map_metadata","1","-map_metadata:s:a:0","1:s:a:${options.mainTrack}","-map_chapters","-1","-c:a","copy","-avoid_negative_ts","disabled",
                            "-movflags","+faststart",staged.absolutePath),plan.total.toDouble()/audio.rate)
                    }
                    log("Smart Insert: ${SmartInsertPlanner.decimal((plan.main-splice.right+splice.left).toDouble()/audio.rate)} s em cópia; " +
                        "inserido e ${SmartInsertPlanner.decimal((splice.right-splice.left).toDouble()/audio.rate)} s da emenda recodificados.")
                }
            }
            if (!partial) {
                if (options.smart && !preview && !forceContinuous) {
                    reason=reason ?: "emendas ${original.codec.uppercase(Locale.ROOT)} ainda sem cópia parcial confiável neste contêiner"
                    log("Smart Insert: $reason; recodificação contínua mantendo o efeito selecionado.")
                }
                execute(continuousArguments(main,inserted,staged,audio,plan,options.mainTrack,options.insertedTrack),plan.total.toDouble()/audio.rate)
            }
            validate(staged,audio,plan.total)
            cancelled()
            check(!output.exists()) { "O arquivo de saída já existe." }
            check(staged.renameTo(output)) { "Não foi possível publicar o áudio validado." }
            return Result(plan,audio,partial,reason)
        } finally { work.deleteRecursively() }
    }

    fun validate(path: File, audio: SmartInsertPlanner.Audio, samples: Long) {
        cancelled()
        check(path.isFile && path.length()>0) { "O FFmpeg não gerou áudio." }
        if (audio.codec in SmartInsertPlanner.pcmCodecs && path.extension.lowercase(Locale.ROOT)=="wav") {
            val data=SmartInsertWave.inspect(path)
            check(data.signature[1]==audio.channels && data.signature[2]==audio.rate && data.size/data.align==samples) { "Formato ou contagem de amostras WAV incorretos." }
        } else if (audio.codec=="flac" && path.extension.lowercase(Locale.ROOT)=="flac") {
            val stream=SmartInsertFlac.streamValue(SmartInsertFlac.metadata(path)[0].second)
            check((stream ushr 44).toInt()==audio.rate && ((stream ushr 41) and 7).toInt()+1==audio.channels && stream and ((1L shl 36)-1)==samples &&
                (audio.bits==0 || ((stream ushr 36) and 31).toInt()+1==audio.bits)) { "Formato ou contagem de amostras FLAC incorretos." }
        } else {
            val metadata=info(path)
            val array=metadata.getJSONArray("streams")
            check((0 until array.length()).count { array.getJSONObject(it).optString("codec_type")=="audio" }==1) { "Quantidade de faixas de áudio incorreta." }
            val actual=readAudio(path)
            val tolerance=if(audio.lossless)1L else maxOf((.002*audio.rate).roundToLong(),4096)
            check(actual.codec==audio.codec && actual.rate==audio.rate && actual.channels==audio.channels && abs(actual.samples-samples)<=tolerance) { "Codec, formato ou duração da saída incorretos." }
        }
        cancelled()
    }

    fun singlePreview(main: File, output: File, track: Int): Result {
        val source=readAudio(main,track)
        val audio=source.copy(codec="pcm_s16le",sampleFormat="",bits=16)
        val plan=SmartInsertPlanner.Render(audio.rate,audio.samples,0,0,0,"none",false)
        execute(arrayOf("-hide_banner","-y","-i",main.absolutePath,"-map","0:a:$track","-vn","-af",
            "asetpts=PTS-STARTPTS,apad,atrim=end_sample=${audio.samples},asetpts=PTS-STARTPTS",
            "-ar",audio.rate.toString(),"-ac",audio.channels.toString(),"-c:a","pcm_s16le",output.absolutePath),audio.duration)
        validate(output,audio,audio.samples)
        return Result(plan,audio,false,null)
    }
}
