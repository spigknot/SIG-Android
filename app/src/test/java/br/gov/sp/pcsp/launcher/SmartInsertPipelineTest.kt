package br.gov.sp.pcsp.launcher

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SmartInsertPipelineTest {
    @Test fun negativeAndShiftedOriginsKeepCopyAndSeekCoordinatesSeparate() {
        for((origin,container) in listOf(-2.0 to -2.0,5.0 to 4.5,0.0 to .2)) {
            val metadata=JSONObject("""{"streams":[{"codec_type":"audio","codec_name":"alac","sample_rate":"48000","channels":1,"duration":"6","start_time":"$origin"}],"format":{"start_time":"$container"}}""")
            val audio=SmartInsertPlanner.readAudio(metadata)
            assertEquals(origin,audio.startTime,0.000001);assertEquals(origin-container,audio.seekOffset,0.000001)
            assertTrue(SmartInsertPlanner.canCopy(audio,"m4a"))
        }
    }
    @Test fun editedFirstAndLastPacketOnlyReencodeTheSmallEdges() {
        val packets=listOf(-1000L,3000L,7000L,11000L).map { SmartInsertPlanner.Packet(it,4000,0,1) }
        for(cut in listOf(0L,5926L,12000L)) {
            val splice=SmartInsertPlanner.splice(packets,12000,4800,cut)
            assertTrue(splice.left>=0);assertTrue(splice.right<=12000)
            assertEquals((splice.prefix.size+splice.suffix.size)*4000L,
                splice.left-splice.headEnd+splice.tailStart-splice.right)
        }
    }
    private val mp3=JSONObject("""{"streams":[{"codec_type":"audio","codec_name":"mp3","sample_rate":"48000","channels":1,"duration":"8.137"}]}""")
    private val first=JSONObject("""{"pts_time":"0","duration_time":"0.024","side_data_list":[{"skip_samples":1105,"discard_padding":0}]}""")
    private val last=JSONObject("""{"pts_time":"8.160","duration_time":"0.024","side_data_list":[{"skip_samples":0,"discard_padding":1151}]}""")
    @Test fun incompleteMp3SeekReadsPacketTimestampsWithoutReencoding() {
        var packetReads=0
        val pipeline=SmartInsertPipeline(execute={ _,_ -> fail("Duration lookup must not encode") },probe={ args ->
            if("-show_packets" !in args)mp3 else {
                packetReads++
                JSONObject().put("packets",org.json.JSONArray().put(first).apply { if("-read_intervals" !in args)put(last) })
            }
        })
        assertEquals(390576L,pipeline.readAudio(File("fixture.mp3")).samples)
        assertEquals(2,packetReads)
    }
    @Test fun completeMp3BoundariesAvoidFullPacketScan() {
        var packetReads=0
        val pipeline=SmartInsertPipeline(execute={ _,_ -> fail() },probe={ args ->
            if("-show_packets" !in args)mp3 else {
                packetReads++
                JSONObject().put("packets",org.json.JSONArray().put(first).put(last))
            }
        })
        assertEquals(390576L,pipeline.readAudio(File("fixture.mp3")).samples);assertEquals(1,packetReads)
    }
    @Test fun malformedOptionsAndCancellationAreRejectedBeforeReadingFiles() {
        val pipeline=SmartInsertPipeline(execute={ _,_ -> fail() },probe={ fail("No probe expected");JSONObject() })
        for(value in listOf(Double.NaN,Double.POSITIVE_INFINITY,-1.0))
            assertThrows(IllegalArgumentException::class.java) {
                pipeline.run(File("main.wav"),File("insert.wav"),File("out.wav"),SmartInsertPipeline.Options(0.0,value,"tri",true))
            }
        val cancelled=SmartInsertPipeline(execute={ _,_ -> fail() },probe={ fail();JSONObject() },cancelled={ throw InterruptedException() })
        assertThrows(InterruptedException::class.java) {
            cancelled.run(File("main.wav"),File("insert.wav"),File("out.wav"),SmartInsertPipeline.Options(0.0,0.0,"none",true))
        }
    }
}
