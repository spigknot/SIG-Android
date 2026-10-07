package br.gov.sp.pcsp.launcher

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SmartInsertPipelineTest {
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
