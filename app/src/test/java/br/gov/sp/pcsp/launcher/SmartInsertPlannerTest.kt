package br.gov.sp.pcsp.launcher

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SmartInsertPlannerTest {
    @Test fun smartFadeKeepsDurationAndUsesOnlyInsertedHalfAsLimit() {
        val plan=SmartInsertPlanner.render(480000,96000,1,48000,.5,"tri",true)
        assertEquals(576000L,plan.total);assertEquals(24000L,plan.fade);assertFalse(plan.crossfade)
        val filter=SmartInsertPlanner.filter(plan,"0:a:1","1:a:2","stereo")
        assertFalse(filter.contains("acrossfade"));assertFalse(filter.substringBefore("[a0]").contains("afade"))
    }
    @Test fun zeroSecondsAndNoneDoNotApplyAnyEffect() {
        for(plan in listOf(SmartInsertPlanner.render(480000,96000,10000,48000,0.0,"tri",true),
            SmartInsertPlanner.render(480000,96000,10000,48000,3.0,"none",true))) {
            val filter=SmartInsertPlanner.filter(plan,"0:a:0","1:a:0","mono")
            assertFalse(filter.contains("afade"));assertFalse(filter.contains("acrossfade"))
            assertEquals(576000L,plan.total)
        }
    }
    @Test fun fullCrossfadeCountsActualNeighborsIncludingOneSample() {
        for(cut in listOf(0L,1L,10000L,480000L)) {
            val plan=SmartInsertPlanner.render(480000,96000,cut,48000,.5,"tri",false)
            assertEquals(576000-plan.fade*plan.boundaries,plan.total)
            if(cut==1L)assertEquals(0L,plan.fade)
        }
    }
    @Test fun exactPacketBoundariesNeedNoPrincipalEncode() {
        val packets=(0..2).map { SmartInsertPlanner.Packet(it*4000L,4000,0,10) }
        val splice=SmartInsertPlanner.splice(packets,12000,1000,4000)
        assertEquals(splice.left,splice.right);assertEquals(1,splice.prefix.size);assertEquals(2,splice.suffix.size)
    }
    @Test fun discontinuousPacketsCannotBeCopied() {
        val packets=listOf(SmartInsertPlanner.Packet(0,4000,0,10),SmartInsertPlanner.Packet(5000,7000,0,10))
        assertThrows(IllegalArgumentException::class.java) { SmartInsertPlanner.splice(packets,12000,1000,4000) }
    }
    @Test fun flacShortFinalPacketDoesNotBecomeShortInteriorPacket() {
        val packets=listOf(SmartInsertPlanner.Packet(0,4096,0,10),SmartInsertPlanner.Packet(4096,5,0,10))
        val splice=SmartInsertPlanner.splice(packets,4101,1000,4101,16)
        assertEquals(4096L,splice.left);assertEquals(1,splice.prefix.size)
    }
    @Test fun selectedTrackDurationDoesNotUseContainerRounding() {
        val info=JSONObject("""{"streams":[{"codec_type":"audio","codec_name":"aac","sample_rate":"48000","channels":1,"duration_ts":48000,"time_base":"1/48000"},
          {"codec_type":"audio","codec_name":"alac","sample_rate":"44100","channels":2,"duration_ts":76337,"time_base":"1/44100","sample_fmt":"s32p","bits_per_raw_sample":24}],"format":{"duration":"10.0"}}""")
        val audio=SmartInsertPlanner.readAudio(info,1)
        assertEquals(76337L,audio.samples);assertEquals(44100,audio.rate);assertEquals(1,audio.track);assertEquals("alac",audio.encoder)
    }
    @Test fun supportedCopiesAreLimitedToReliableCodecAndContainerPairs() {
        fun audio(codec: String)=SmartInsertPlanner.Audio(codec,48000,1,"mono",1000,"",0,"128k")
        assertTrue(SmartInsertPlanner.canCopy(audio("pcm_s32le"),"wav"))
        assertTrue(SmartInsertPlanner.canCopy(audio("alac"),"m4a"))
        assertTrue(SmartInsertPlanner.canCopy(audio("flac"),"flac"))
        for(codec in listOf("aac","mp3","opus","vorbis","wmav2")) assertFalse(SmartInsertPlanner.canCopy(audio(codec),"m4a"))
        assertFalse(SmartInsertPlanner.canCopy(audio("flac"),"ogg"))
    }
    @Test fun outputPositionsIncludeBothCrossfadeOverlaps() {
        val plan=SmartInsertPlanner.render(480000,96000,240000,48000,.5,"tri",false)
        assertEquals(384000L-48000,plan.outputPosition(384000))
        assertEquals(384000L,plan.compositePosition(336000))
    }
    @Test fun invalidTimesAreRejected() {
        for(value in listOf(Double.NaN,Double.POSITIVE_INFINITY,-1.0))
            assertThrows(IllegalArgumentException::class.java) { SmartInsertPlanner.render(10000,1000,1000,48000,value,"tri",true) }
        assertThrows(IllegalArgumentException::class.java) { SmartInsertPlanner.readAudio(JSONObject("""{"streams":[]} """)) }
    }
    @Test fun compactPacketTablePreservesSourceClockAndOffsets() {
        val result=SmartInsertPlanner.csvPackets("0.200000,0.096000,1352,8323\n0.296000,0.096000,1351,9675\n",48000,.2)
        assertEquals(SmartInsertPlanner.Packet(0,4608,8323,1352),result.first())
        assertEquals(4608L,result.last().start)
        for(text in listOf("N/A,0.1,2,40","0,0.1,-1,40","0,0.1,2,N/A","0,0.1,2"))
            assertThrows(IllegalArgumentException::class.java) { SmartInsertPlanner.csvPackets(text,48000,0.0) }
    }
    @Test fun packetSideDataDoesNotForceContinuousReencoding() {
        val packets=SmartInsertPlanner.csvPackets("-0.037000,0.085333,1912,44,Skip Samples,1776,0,0,0\n0.048333,0.085333,1952,1956\n",48000,0.0)
        assertEquals(-1776L,packets.first().start);assertEquals(4096L,packets.first().count)
        assertEquals(packets.first().start+packets.first().count,packets.last().start)
    }
    @Test fun millisecondEofRoundingOnlyEncodesTheLastPacket() {
        val packets=(0..2).map { SmartInsertPlanner.Packet(it*4096L,4096,0,10) }
        val splice=SmartInsertPlanner.splice(packets,12288+16L,1000,4096,endTolerance=48)
        assertEquals(8192L,splice.tailStart);assertEquals(1,splice.suffix.size)
        assertThrows(IllegalArgumentException::class.java) { SmartInsertPlanner.splice(packets,12388,1000,4096,endTolerance=48) }
    }
}
