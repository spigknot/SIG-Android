package br.gov.sp.pcsp.launcher

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import org.json.JSONObject

/** Executa os argumentos de produção: mede conteúdo, relógio, áudio e cópia de GOPs reais. */
class SmartCutNativeTest {
    private lateinit var root: File
    private var counter=0
    private fun native(tool: String,args: List<String>): ByteArray {
        val output=File(root,"stdout-${counter++}");val errors=File(root,"stderr-${counter++}")
        val process=ProcessBuilder(listOf(tool)+args).redirectOutput(output).redirectError(errors).start()
        if(!process.waitFor(120,TimeUnit.SECONDS)) { process.destroyForcibly();error("Timeout: $args") }
        check(process.exitValue()==0) { "$args\n${errors.readText()}" }
        check(!errors.readText().contains("non-monoton",true)) { errors.readText() }
        return output.readBytes().also { output.delete();errors.delete() }
    }
    @Before fun available() { assumeTrue(listOf("ffmpeg","ffprobe").all { tool -> runCatching {
        val process=ProcessBuilder(tool,"-version").redirectErrorStream(true).start()
        process.inputStream.readBytes();process.waitFor()==0
    }.getOrDefault(false) }) }
    private fun directory(test: () -> Unit) {
        root=Files.createTempDirectory("sig-smartcut-native-").toFile();var passed=false
        try { test();passed=true } finally { if(passed)root.deleteRecursively() else System.err.println("SmartCut diagnostics: $root") }
    }
    private fun probe(file: File): SmartJoinTiming.Video = SmartJoinTiming.readCompactProbe(String(native("ffprobe",listOf("-v","error","-select_streams","v:0","-show_packets","-show_streams","-show_format",
        "-show_entries","packet=pts_time,dts_time,duration_time,flags:stream=codec_type,start_time,duration,r_frame_rate:format=start_time,duration","-of","compact=p=1:nk=0",file.absolutePath))).reader())
    private fun fixture(codec: String,vfr: Boolean=false): File = File(root,"source.mp4").also { file ->
        val args=mutableListOf("-v","error","-y","-f","lavfi","-i","nullsrc=size=128x72:rate=25:duration=6,geq=r='mod(N*13+25,256)':g='mod(N*37+55,256)':b='mod(N*61+75,256)'",
            "-f","lavfi","-i","sine=frequency=440:sample_rate=48000:duration=6","-c:v",if(codec=="hevc")"libx265" else "libx264","-preset","fast","-g","50","-bf","2","-pix_fmt","yuv420p")
        if(codec=="hevc")args+=listOf("-x265-params","scenecut=0:log-level=error") else args+=listOf("-sc_threshold","0")
        if(vfr)args+=listOf("-vf","setpts='PTS+floor(N/30)*0.006/TB'","-fps_mode","passthrough","-enc_time_base","1/90000")
        args+=listOf("-c:a","aac","-b:a","128k","-video_track_timescale","90000",file.absolutePath)
        native("ffmpeg",args)
    }
    private fun colors(file: File): List<List<Int>> = native("ffmpeg",listOf("-v","error","-i",file.absolutePath,"-map","0:v:0","-vf","scale=1:1","-pix_fmt","rgb24","-fps_mode","passthrough","-f","rawvideo","-")).map { it.toInt() and 255 }.chunked(3)
    private fun cut(source: File,codec: String,start: Double,end: Double) {
        val video=probe(source);val plan=SmartCutTiming.plan(video,start,end)
        assertTrue("Must keep a copied body",plan.segments.any { it.copy })
        val encoded=mutableMapOf<Int,File>();var delay=plan.segments.maxOf { it.delay }
        val encoder=listOf("-c:v",if(codec=="hevc")"libx265" else "libx264","-preset","fast")+
            if(codec=="hevc")listOf("-x265-params","log-level=error") else emptyList()
        for((index,segment) in plan.segments.withIndex())if(!segment.copy) {
            val edge=File(root,"edge-$index.mp4")
            native("ffmpeg",listOf("-v","warning")+SmartCutTiming.arguments(source.absolutePath,edge.absolutePath,segment,video,codec,encoder,delay))
            val edgeVideo=probe(edge);delay=maxOf(delay,edgeVideo.visiblePackets.first().let { it.pts-it.dts });encoded[index]=edge
        }
        val parts=plan.segments.mapIndexed { index,segment ->
            val part=File(root,"part-$index.ts")
            if(segment.copy) {
                // Packet-index path also exercises displaced/negative source clocks.
                native("ffmpeg",listOf("-v","warning")+SmartCutTiming.arguments(source.absolutePath,part.absolutePath,segment,video,codec,emptyList(),delay,true))
            } else {
                val edge=encoded.getValue(index);val own=probe(edge).visiblePackets.first().let { it.pts-it.dts }
                val bsf=FfmpegMediaPolicies.tsBitstreamFilter(codec)+",setts=pts=PTS:dts=DTS-${SmartInsertPlanner.decimal(delay-own)}/TB"
                native("ffmpeg",listOf("-v","warning","-y","-i",edge.absolutePath,"-map","0:v:0","-an","-c:v","copy","-bsf:v",bsf,"-avoid_negative_ts","disabled","-mpegts_flags","+resend_headers+initial_discontinuity","-muxdelay","0","-muxpreload","0","-f","mpegts",part.absolutePath))
            };part
        }
        val list=File(root,"parts.txt").apply { writeText(parts.mapIndexed { index,file -> "file '${file.absolutePath.replace("\\","/")}'\nduration ${SmartInsertPlanner.decimal(plan.segments[index].end-plan.segments[index].start)}" }.joinToString("\n")) }
        val output=File(root,"output.mp4")
        native("ffmpeg",listOf("-v","warning")+FfmpegMediaPolicies.hybridConcatArguments(list.absolutePath,output.absolutePath,0,true,source.absolutePath,(start*1_000_000).toLong(),codec=="hevc",listOf("-c:a","aac","-b:a","128k"),end-start,plan.firstGap))
        val actual=probe(output).visiblePackets.map { it.pts }.sorted();val expected=plan.times.map { it-start }
        assertEquals(expected.size,actual.size);expected.zip(actual).forEach { (a,b)->assertEquals(a,b,.0001) }
        val reference=colors(source);val result=colors(output)
        val expectedIndices=video.times.indices.filter { video.times[it]>=start-.000001 && video.times[it]<end-.000001 }
        val indices=result.map { color -> reference.indices.minBy { index -> color.zip(reference[index]).sumOf { (a,b)->(a-b)*(a-b) } } }
        assertEquals("Content includes every selected frame once",expectedIndices,indices)
        val info=JSONObject(String(native("ffprobe",listOf("-v","error","-select_streams","a:0","-show_streams","-of","json",output.absolutePath))))
        assertEquals(end-start,info.getJSONArray("streams").getJSONObject(0).getString("duration").toDouble(),1.0/48000)
        native("ffmpeg",listOf("-v","error","-xerror","-i",output.absolutePath,"-fps_mode","passthrough","-enc_time_base","1/90000","-f","null","-"))
    }
    @Test fun h264PreservesPreciseEdgesAndBoundsAudio() = directory { cut(fixture("h264"),"h264",1.401,4.601) }
    @Test fun hevcPreservesPreciseEdgesAndBoundsAudio() = directory { cut(fixture("hevc"),"hevc",1.401,4.601) }
    @Test fun variableFrameTimesStayVariable() = directory { cut(fixture("h264",true),"h264",1.401,4.601) }
    @Test fun negativePrerollInEditedMp4DoesNotReencodeTheWholeClip() = directory {
        val original=fixture("h264");val edited=File(root,"edited.mp4")
        native("ffmpeg",listOf("-v","error","-y","-ss","0.037","-i",original.absolutePath,"-t","5.951","-c","copy",edited.absolutePath))
        assertTrue(probe(edited).packets.any { it.discarded && it.pts<0 })
        cut(edited,"h264",1.401,4.601)
    }
}
