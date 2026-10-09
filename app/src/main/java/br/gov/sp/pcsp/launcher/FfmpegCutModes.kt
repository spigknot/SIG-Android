package br.gov.sp.pcsp.launcher

/** Modos de corte da ferramenta Cortar (ordem exibida; SmartCut é o padrão).
 *
 * Port de `CUT_MODE_*` do SIG Windows. O modo só descreve a INTENÇÃO; a decisão
 * final (com motivo) é do despacho em [videoPlan] e das regras de execução —
 * ex.: seleção de área não pode copiar streams e força o Reencode Completo.
 *
 * Não toca Android: regra pura, testável sem aparelho. */
object FfmpegCutModes {

    const val SMART = "SmartCut"
    const val REENCODE = "Reencode Completo"
    const val COPY = "Sem Reencode"

    val MODES = listOf(SMART, REENCODE, COPY)
    val DEFAULT = SMART

    const val HELP = "SmartCut: corte preciso e rápido, mas EXPERIMENTAL — copia os trechos que já " +
        "começam em keyframe e reencoda apenas as bordas até os tempos exatos.\n\n" +
        "Reencode Completo: reencoda todo o trecho — lento e preciso.\n\n" +
        "Sem Reencode: copia os streams sem reencodar — rápido e menos preciso, porque início e fim " +
        "escorregam até o keyframe/pacote disponível."

    /** Margem mínima para valer a pena reencodar a borda e para considerar que
     * há miolo copiável entre dois keyframes (mesmo valor do Windows). */
    const val SMARTCUT_MIN_EDGE = 0.05

    /** Plano do despacho: modo efetivo + motivo quando ele mudou. */
    data class Plan(val mode: String, val reason: String? = null)

    /** Modo "Sem Reencode": cópia pura dos streams. */
    fun isCopy(mode: String): Boolean = mode.startsWith(COPY)

    /** Ferramentas/modos que reencodam vídeo usam o seletor de encoder; o modo
     * de cópia não reencoda nada. */
    fun usesVideoEncoder(mode: String): Boolean = !isCopy(mode)

    /** Modo efetivo do corte de VÍDEO, aplicando as regras que forçam reencode.
     *
     * A seleção de área (crop) recorta pixels, o que cópia de streams não faz —
     * e o miolo copiado do SmartCut também não pode ser recortado. Nos dois
     * casos o corte vira Reencode Completo, com o motivo para o log. */
    fun videoPlan(mode: String, hasCrop: Boolean): Plan = when {
        hasCrop && isCopy(mode) ->
            Plan(REENCODE, "A seleção de área exige reencodar: usando o Reencode Completo.")
        hasCrop && !mode.startsWith(REENCODE) ->
            Plan(REENCODE, "A seleção de área exige reencodar todo o trecho: usando o Reencode Completo.")
        else -> Plan(mode)
    }

    /** Motivo para o SmartCut não poder copiar o miolo (o corte cai no Reencode
     * Completo); `null` quando o caminho rápido está disponível. */
    fun smartCutFallbackReason(
        codecFamily: String?,
        hasInternalKeyframes: Boolean?,
        edgeEncoderAvailable: Boolean
    ): String? = when {
        codecFamily !in setOf("h264", "hevc") ->
            "Codec ${codecFamily ?: "desconhecido"} não permite cópia híbrida."
        !edgeEncoderAvailable ->
            "Nenhum encoder deste aparelho produz o mesmo codec do arquivo (o miolo é copiado)."
        hasInternalKeyframes == false ->
            "Não há keyframes internos suficientes para copiar o trecho central sem perdas."
        else -> null
    }
}

/** Intervalos de apresentação do SmartCut; preroll oculto nunca elimina o keyframe visível em zero. */
internal object SmartCutTiming {
    data class Segment(val start: Double,val end: Double,val frames: Int,val copy: Boolean,
        val seek: Double,val leading: Int=0,val delay: Double=0.0,val packetStart: Int=0)
    data class Plan(val segments: List<Segment>,val times: List<Double>,val firstGap: Double)
    fun plan(video: SmartJoinTiming.Video,start: Double,end: Double): Plan {
        require(start.isFinite() && end.isFinite() && start>=0 && end>start)
        require(video.packets.all { it.pts.isFinite() && it.dts.isFinite() }) { "Vídeo com timestamps inválidos." }
        require(video.times.zipWithNext().all { (a,b)->b-a>0.0000001 }) { "Vídeo com timestamps repetidos." }
        val times=video.times.filter { it>=start-0.000001 && it<end-0.000001 }
        require(times.isNotEmpty()) { "O intervalo não contém quadros; amplie o corte." }
        val keys=video.keys.filter { it>=times.first()-0.000001 && it<end-0.000001 }.sorted()
        val bodyStart=keys.firstOrNull()
        val ends=video.safeEnds.filter { (key,_) -> bodyStart!=null && key>bodyStart+0.000001 && key<=end+0.000001 }.values.toMutableList()
        val repair=video.tailRepairStartSeconds
        if(end>=video.duration-0.000001 && repair==null)ends+=video.duration
        if(repair!=null && end>=repair) { ends.removeAll { it>repair+0.000001 };ends+=repair }
        val bodyEnd=ends.maxOrNull()
        fun segment(a: Double,b: Double,copy: Boolean=false): Segment {
            val seek=video.keys.filter { it<a-0.000001 }.maxOrNull()?.coerceAtLeast(0.0) ?: 0.0
            val packetStart=video.packets.indexOfFirst { it.key && !it.discarded && kotlin.math.abs(it.pts-video.origin-a)<0.000001 }.coerceAtLeast(0)
            return Segment(a,b,times.count { it>=a-0.000001 && it<b-0.000001 },copy,seek,
                if(copy)video.leading(a) else 0,if(copy)video.delay(a) else 0.0,packetStart)
        }
        val pieces=mutableListOf<Segment>()
        if(bodyStart!=null && bodyEnd!=null && bodyEnd>bodyStart+0.000001) {
            if(times.first()<bodyStart-0.000001)pieces+=segment(times.first(),bodyStart)
            pieces+=segment(bodyStart,bodyEnd,true)
            if(times.any { it>=bodyEnd-0.000001 })pieces+=segment(bodyEnd,minOf(end,video.duration))
        } else pieces+=segment(times.first(),minOf(end,video.duration))
        require(pieces.all { it.frames>0 } && pieces.sumOf { it.frames }==times.size) { "Não foi possível dividir os quadros do corte." }
        return Plan(pieces,times,(times.first()-start).coerceAtLeast(0.0))
    }
    fun arguments(inputPath: String,outputPath: String,segment: Segment,video: SmartJoinTiming.Video,
        codec: String?,encoderArguments: List<String>,delay: Double,byPacketIndex: Boolean=false): Array<String> {
        fun seconds(value: Double)=SmartInsertPlanner.decimal(value)
        val args=mutableListOf("-y","-noautorotate","-display_rotation:v:0","0")
        val seek=if(segment.copy)segment.start else segment.seek
        if(!byPacketIndex)args+=listOf("-ss",seconds(seek+video.seekOffset))
        args+=listOf("-i",inputPath,"-map","0:v:0","-an","-frames:v",segment.frames.toString())
        if(segment.copy) args+=listOf("-c:v","copy") else {
            args+=encoderArguments
            // MediaCodec do FFmpeg 6 não fornece DTS para B frames. A borda
            // sai sem B frames; seu atraso é alinhado ao corpo no remux TS.
            val bFrames=if(encoderArguments.any { it.endsWith("_mediacodec") })0 else kotlin.math.ceil(delay*SmartJoinTiming.fps(video.fps)-0.000001).toInt().coerceAtLeast(0)
            args+=listOf("-vf","settb=AVTB,trim=start=${seconds(segment.start-seek)}:end=${seconds(segment.end-seek)},setpts=PTS-STARTPTS",
                "-pix_fmt","yuv420p","-fps_mode","passthrough","-enc_time_base","1/90000","-bf",bFrames.toString(),
                "-force_key_frames","expr:eq(n,${segment.frames-1})")
        }
        var filter=checkNotNull(FfmpegMediaPolicies.tsBitstreamFilter(codec))
        if(byPacketIndex) {
            require(segment.copy)
            filter+=",noise=drop='lt(n,${segment.packetStart})',setts=pts=PTS-${seconds(segment.start+video.seekOffset)}/TB:dts=DTS-${seconds(segment.start+video.seekOffset)}/TB"
        }
        if(segment.copy && segment.leading>0)filter+=",noise=drop='lt(pts,0)'"
        if(segment.copy && delay>segment.delay+0.000001)filter+=",setts=pts=PTS:dts=DTS-${seconds(delay-segment.delay)}/TB"
        args+=listOf("-map_metadata","-1","-avoid_negative_ts","disabled")
        if(outputPath.endsWith(".ts"))args+=listOf("-bsf:v",filter,"-mpegts_flags","+resend_headers+initial_discontinuity","-muxdelay","0","-muxpreload","0","-f","mpegts")
        else args+=listOf("-video_track_timescale","90000","-movflags","+faststart")
        args+=outputPath
        return args.toTypedArray()
    }

}
