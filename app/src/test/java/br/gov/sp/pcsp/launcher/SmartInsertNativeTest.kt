package br.gov.sp.pcsp.launcher

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Executa o pipeline de produção no JVM com FFmpeg/FFprobe locais (sem emular FFmpegKit).
 * Precisa dos binários no PATH; o gate continua portátil quando eles não estão instalados. */
class SmartInsertNativeTest {
    private lateinit var root: File
    private val commands=mutableListOf<List<String>>()
    private val messages=mutableListOf<String>()
    private var count=0
    private fun native(tool: String, args: List<String>): ByteArray {
        val stdout=File(root,"stdout-${count++}");val stderr=File(root,"stderr-${count++}")
        val process=ProcessBuilder(listOf(tool)+args).redirectOutput(stdout).redirectError(stderr).start()
        if(!process.waitFor(120,TimeUnit.SECONDS)) { process.destroyForcibly();error("Native timeout: $tool $args") }
        val errors=stderr.readText()
        check(process.exitValue()==0) { "$tool $args\n$errors" }
        check(!errors.contains("non monoton",true) && !errors.contains("non-monoton",true)) { errors }
        return stdout.readBytes().also { stdout.delete();stderr.delete() }
    }
    private fun pipeline(cancel: () -> Unit = {}, afterExecute: () -> Unit = {}) = SmartInsertPipeline(
        execute={ args,_ -> commands+=args.toList();native("ffmpeg",listOf("-v","warning")+args);afterExecute() },
        probe={ args -> JSONObject(String(native("ffprobe",args.toList()))) },cancelled=cancel,log={ messages+=it },
        packetProbe={ args -> String(native("ffprobe",args.toList())) })
    @Before fun availability() {
        assumeTrue("FFmpeg e FFprobe locais necessários",listOf("ffmpeg","ffprobe").all { tool ->
            runCatching { val p=ProcessBuilder(tool,"-version").redirectErrorStream(true).start();p.inputStream.readBytes();p.waitFor()==0 }.getOrDefault(false)
        })
    }
    private fun inDirectory(body: () -> Unit) {
        root=Files.createTempDirectory("sig-smartinsert-native-").toFile()
        var passed=false
        try { body();passed=true } finally {
            if(passed)root.deleteRecursively() else System.err.println("Native diagnostics retained at ${root.absolutePath}")
        }
    }
    private fun fixture(name: String, codec: String, rate: Int = 48000, duration: String = "8.137", frequency: Int = 997,
        channels: Int = 1, extra: List<String> = emptyList()): File = File(root,name).also {
        native("ffmpeg",listOf("-v","error","-y","-f","lavfi","-i","sine=frequency=$frequency:sample_rate=$rate:duration=$duration",
            "-ac",channels.toString(),"-c:a",codec,"-metadata","title=Smart Insert native fixture")+extra+it.absolutePath)
    }
    private fun decode(file: File, track: Int = 0, bits: Int = 16, start: Double? = null, duration: Double? = null): ByteArray {
        val codec=when(bits) { 32 -> "pcm_s32le";else -> "pcm_s16le" }
        return native("ffmpeg",listOf("-v","error","-err_detect","crccheck","-i",file.absolutePath)+
            (if(start==null)emptyList() else listOf("-ss",start.toString()))+
            (if(duration==null)emptyList() else listOf("-t",duration.toString()))+
            listOf("-map","0:a:$track","-c:a",codec,"-f",if(bits==32)"s32le" else "s16le","-"))
    }
    private fun equalReference(main: File, inserted: File, options: SmartInsertPipeline.Options, partial: Boolean,
        bits: Int = 16): SmartInsertPipeline.Result {
        val output=File(root,"out-${count++}.${main.extension}")
        val reference=File(root,"reference-${count++}.${main.extension}")
        val pipeline=pipeline()
        val result=pipeline.run(main,inserted,output,options)
        assertEquals("$main $options: $messages",partial,result.partial)
        pipeline.run(main,inserted,reference,options,forceContinuous=true)
        val expected=decode(reference,bits=bits);val actual=decode(output,bits=bits)
        assertArrayEquals("$main $options; reference=$reference; output=$output; commands=$commands",expected,actual)
        val metadata=JSONObject(String(native("ffprobe",listOf("-v","error","-show_format","-show_streams","-of","json",output.absolutePath))))
        val title=metadata.getJSONObject("format").optJSONObject("tags")?.optString("title")
            ?: metadata.getJSONArray("streams").getJSONObject(0).getJSONObject("tags").getString("title")
        assertEquals("Smart Insert native fixture",title)
        return result
    }
    @Test fun losslessMatchesContinuousEncodingAtFourTransitionTimesAndBoundaries() = inDirectory {
        val inserted=fixture("inserted.wav","pcm_s16le",44100,"1.731",233)
        for((codec,extension) in listOf("pcm_s16le" to "wav","alac" to "m4a","flac" to "flac")) {
            val main=fixture("main.$extension",codec)
            for(time in listOf(0.0,.2,.5,1.0)) {
                val result=equalReference(main,inserted,SmartInsertPipeline.Options(3.123,time,"tri",true),true)
                assertEquals(473664L,result.plan.total)
            }
            for(at in listOf(0.0,1.0/48000,.03,4096.0/48000,8.137))
                equalReference(main,inserted,SmartInsertPipeline.Options(at,.2,"tri",true),true)
        }
    }
    @Test fun lossyKeepsSmartEffectWithContinuousEncodingAndGaplessDurations() = inDirectory {
        val inserted=fixture("inserted.wav","pcm_s16le",44100,"1.731",233)
        for((codec,extension) in listOf("aac" to "m4a","libmp3lame" to "mp3","libopus" to "opus","libvorbis" to "ogg","wmav2" to "wma")) {
            val main=fixture("main-$codec.$extension",codec)
            for(time in listOf(0.0,.2,.5,1.0)) {
                val result=equalReference(main,inserted,SmartInsertPipeline.Options(3.123,time,"tri",true),false)
                assertNotNull(result.reason)
                assertEquals(result.plan.main+result.plan.inserted,result.plan.total)
                if(codec in setOf("libmp3lame","libopus"))assertEquals(473664L,result.plan.total)
            }
        }
    }
    @Test fun everyCurveZeroNoneAndFullCrossfadesHaveExpectedSampleCounts() = inDirectory {
        val main=fixture("main.wav","pcm_s16le")
        val inserted=fixture("inserted.wav","pcm_s16le",44100,"1.731",233)
        for(curve in SmartInsertPlanner.curves+setOf("fade","none")) {
            val result=equalReference(main,inserted,SmartInsertPipeline.Options(3.123,.5,curve,true),true)
            assertEquals(473664L,result.plan.total)
        }
        for(at in listOf(0.0,1.0/48000,.03,3.123,8.137))for(time in listOf(0.0,.2,.5,1.0)) {
            val options=SmartInsertPipeline.Options(at,time,"tri",false)
            val result=pipeline().run(main,inserted,File(root,"cross-${count++}.wav"),options)
            assertEquals(473664-result.plan.fade*result.plan.boundaries,result.plan.total)
        }
        commands.clear()
        equalReference(main,inserted,SmartInsertPipeline.Options(3.123,3.0,"none",true),true)
        assertFalse(commands.any { command -> command.any { it.contains("afade") || it.contains("acrossfade") } })
    }
    @Test fun nativePcmDepthsAndMatchingInsertedAudioKeepExactBytesWithoutEncoder() = inDirectory {
        for(codec in SmartInsertPlanner.pcmCodecs) {
            val main=fixture("main-$codec.wav",codec,channels=2)
            val inserted=fixture("inserted-$codec.wav",codec,48000,"1.731",233,2)
            commands.clear()
            val output=File(root,"copied-$codec.wav")
            val result=pipeline().run(main,inserted,output,SmartInsertPipeline.Options(3.123,0.0,"tri",true))
            assertTrue(result.partial);assertTrue("Matching WAV must not call an encoder",commands.isEmpty())
            val before=decode(main,bits=32);val middle=decode(inserted,bits=32)
            val cut=(result.plan.cut*2*4).toInt()
            assertArrayEquals(before.copyOfRange(0,cut)+middle+before.copyOfRange(cut,before.size),decode(output,bits=32))
            val info=pipeline().readAudio(output)
            assertEquals(codec,info.codec)
            equalReference(main,inserted,SmartInsertPipeline.Options(3.123,.2,"tri",true),true,32)
        }
        for((codec,extension) in listOf("flac" to "flac","alac" to "m4a")) {
            val extra=if(codec=="flac")listOf("-sample_fmt","s32","-bits_per_raw_sample","24") else listOf("-sample_fmt","s32p")
            val main=fixture("depth.$extension",codec,44100,channels=2,extra=extra)
            val inserted=fixture("depth-insert.wav","pcm_s24le",48000,"1.731",233,2)
            equalReference(main,inserted,SmartInsertPipeline.Options(3.123,.5,"tri",true),true,32)
        }
    }
    @Test fun selectedTracksAndPreviewUseTheActualExportFilters() = inDirectory {
        fun multi(name: String, duration: String): File = File(root,name).also {
            native("ffmpeg",listOf("-v","error","-y","-f","lavfi","-i","sine=frequency=199:sample_rate=48000:duration=$duration",
                "-f","lavfi","-i","sine=frequency=997:sample_rate=48000:duration=$duration","-map","0:a","-map","1:a",
                "-c:a","alac","-metadata","title=Smart Insert native fixture",it.absolutePath))
        }
        val main=multi("multi.m4a","8.137");val inserted=multi("insert.m4a","1.731")
        for(smart in listOf(true,false))for(time in listOf(0.0,.2,.5,1.0)) {
            val options=SmartInsertPipeline.Options(3.123,time,"tri",smart,1,1)
            equalReference(main,inserted,options,smart)
            val preview=File(root,"preview-${count++}.wav");val output=File(root,"export-${count++}.m4a")
            val pipe=pipeline();val result=pipe.run(main,inserted,preview,options,preview=true)
            pipe.run(main,inserted,output,options,forceContinuous=true)
            assertArrayEquals(decode(output),decode(preview));assertFalse(result.partial)
        }
        equalReference(main,main,SmartInsertPipeline.Options(3.123,.2,"tri",true,1,0),true)
        val only=File(root,"single.wav")
        pipeline().singlePreview(main,only,1)
        assertArrayEquals(decode(main,1),decode(only));assertFalse(decode(main,0).contentEquals(decode(only)))
    }
    @Test fun flacCompressedBodiesCrcAndRandomSeekingRemainValid() = inDirectory {
        val main=fixture("main.flac","flac");val inserted=fixture("inserted.wav","pcm_s16le",44100,"1.731",233)
        val output=File(root,"out.flac")
        val pipe=pipeline();pipe.run(main,inserted,output,SmartInsertPipeline.Options(3.123,.5,"tri",true))
        val full=decode(output)
        for(start in listOf(.1,3.1,5.7,7.8)) {
            val seeked=decode(output,start=start,duration=.11)
            val offset=(start*48000).toInt()*2
            assertArrayEquals(full.copyOfRange(offset,offset+seeked.size),seeked)
        }
        fun bodies(file: File): Set<List<Byte>> {
            val info=JSONObject(String(native("ffprobe",listOf("-v","error","-show_packets","-show_streams","-of","json",file.absolutePath))))
            val bytes=file.readBytes()
            return SmartInsertPlanner.packets(info,48000).map { packet ->
                val frame=bytes.copyOfRange(packet.offset.toInt(),packet.offset.toInt()+packet.size)
                frame.copyOfRange(SmartInsertFlac.frameHeader(frame).end,frame.size-2).toList()
            }.toSet()
        }
        val original=bodies(main);val copied=bodies(output)
        assertTrue("Most compressed bodies must remain copied",original.intersect(copied).size>=original.size-1)
    }
    @Test fun linearFadeHasIndependentGainAndDoesNotChangePrincipal() = inDirectory {
        val main=fixture("main.wav","pcm_s16le");val inserted=fixture("inserted.wav","pcm_s16le",48000,"1.731",233)
        val output=File(root,"fade.wav")
        val result=pipeline().run(main,inserted,output,SmartInsertPipeline.Options(3.123,.2,"tri",true))
        val actual=decode(output);val before=decode(main);val other=decode(inserted)
        val cut=(result.plan.cut*2).toInt()
        assertArrayEquals(before.copyOfRange(0,cut),actual.copyOfRange(0,cut))
        assertArrayEquals(before.copyOfRange(cut,before.size),actual.copyOfRange(cut+other.size,actual.size))
        val expected=ByteBuffer.wrap(other).order(ByteOrder.LITTLE_ENDIAN)
        val observed=ByteBuffer.wrap(actual).order(ByteOrder.LITTLE_ENDIAN)
        for(sample in 0 until other.size/2) {
            val gain=when {
                sample<result.plan.fade -> sample.toDouble()/result.plan.fade
                sample>=result.plan.inserted-result.plan.fade -> (result.plan.inserted-sample).toDouble()/result.plan.fade
                else -> 1.0
            }
            assertTrue("Gain at sample $sample",abs(observed.getShort(cut+sample*2)-expected.getShort(sample*2)*gain)<=2)
        }
    }
    @Test fun composedCurvesMatchModernNativeGainsForFadeAndCrossfade() = inDirectory {
        val main=fixture("main.wav","pcm_s16le")
        val inserted=fixture("inserted.wav","pcm_s16le",48000,"1.731",233)
        for(curve in listOf("quat","quatr","qsin2","hsin2"))for(smart in listOf(true,false)) {
            val plan=SmartInsertPlanner.render(390576,83088,149904,48000,.2,curve,smart)
            val pipe=pipeline()
            val audio=pipe.readAudio(main)
            val output=File(root,"composed-$curve-$smart.wav");val reference=File(root,"modern-$curve-$smart.wav")
            pipe.run(main,inserted,output,SmartInsertPipeline.Options(3.123,.2,curve,smart))
            var graph=SmartInsertPlanner.filter(plan,"0:a:0","1:a:0","mono")
            if(smart) {
                val sequence=when(curve) { "quat" -> List(4) { "tri" }; "quatr" -> List(2) { "squ" }; "qsin2" -> List(2) { "qsin" };else -> List(2) { "hsin" } }
                for((direction,start) in listOf("in" to 0L,"out" to (plan.inserted-plan.fade))) {
                    val gain=if(direction=="in")"(n-$start)/${plan.fade}" else "(${start+plan.fade}-n)/${plan.fade}"
                    val old=if(curve=="quatr")",aeval=exprs='val(ch)*pow(clip($gain,0,1),0.25)':c=same"
                        else sequence.joinToString("") { ",afade=t=$direction:ss=$start:ns=${plan.fade}:curve=$it" }
                    graph=graph.replace(old,",afade=t=$direction:ss=$start:ns=${plan.fade}:curve=$curve")
                }
            } else {
                graph=graph.replace(Regex(",(?:afade|aeval)=[^;\\[]+"),"")
                    .replace("c1=nofade:c2=nofade","c1=$curve:c2=$curve")
            }
            val args=pipe.continuousArguments(main,inserted,reference,audio,plan,0,0).toMutableList()
            args[args.indexOf("-filter_complex")+1]=graph
            native("ffmpeg",listOf("-v","error")+args)
            val expected=ByteBuffer.wrap(decode(reference)).order(ByteOrder.LITTLE_ENDIAN)
            val actual=ByteBuffer.wrap(decode(output)).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(expected.remaining(),actual.remaining())
            while(expected.hasRemaining())assertTrue("$curve smart=$smart",abs(expected.short-actual.short)<=1)
        }
    }
    @Test fun cancellationAndFailedCommandsNeverPublishPartialOutputOrRemoveExistingFile() = inDirectory {
        val inserted=fixture("inserted.wav","pcm_s16le",44100,"1.731",233)
        for((codec,extension) in listOf("pcm_s16le" to "wav","alac" to "m4a","flac" to "flac")) {
            val main=fixture("main.$extension",codec)
            for(cancelAfter in listOf(0,1)) {
                val output=File(root,"cancel-$codec-$cancelAfter.$extension")
                var calls=0
                assertThrows(InterruptedException::class.java) {
                    pipeline(cancel={ if(calls>=cancelAfter)throw InterruptedException("cancelled") },afterExecute={ calls++ })
                        .run(main,inserted,output,SmartInsertPipeline.Options(3.123,.2,"tri",true))
                }
                assertFalse(output.exists());assertTrue(root.listFiles()!!.none { it.isDirectory && it.name.startsWith("smart_insert_") })
            }
            val existing=File(root,"existing.$extension").also { it.writeText("sentinel") }
            assertThrows(IllegalStateException::class.java) { pipeline().run(main,inserted,existing,SmartInsertPipeline.Options(3.123,0.0,"none",true)) }
            assertEquals("sentinel",existing.readText())
        }
        val main=fixture("failure.wav","pcm_s16le")
        val broken=SmartInsertPipeline(execute={ _,_ -> error("encoder failed") },probe={ args -> JSONObject(String(native("ffprobe",args.toList()))) })
        val output=File(root,"failed.wav")
        assertThrows(IllegalStateException::class.java) { broken.run(main,inserted,output,SmartInsertPipeline.Options(3.123,.2,"tri",true)) }
        assertFalse(output.exists());assertTrue(root.listFiles()!!.none { it.isDirectory })
    }
}
