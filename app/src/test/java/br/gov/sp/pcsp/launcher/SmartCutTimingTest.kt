package br.gov.sp.pcsp.launcher

import org.junit.Assert.*
import org.junit.Test

class SmartCutTimingTest {
    private fun video(origin: Double=0.0,hiddenReference: Boolean=false): SmartJoinTiming.Video {
        val packets=(0 until 150).map { index -> SmartJoinTiming.Packet(origin+index/25.0,origin+index/25.0-.08,.04,index%50==0) }.toMutableList()
        packets.add(0,SmartJoinTiming.Packet(origin-.04,origin-.12,.04,true,true))
        if(hiddenReference)packets.add(packets.lastIndex,SmartJoinTiming.Packet(origin+6,origin+5.84,.04,false,true))
        return SmartJoinTiming.Video(packets,origin,-.04,6.0,"25/1")
    }
    @Test fun negativeDiscardedPrerollKeepsVisibleZeroKeyframeAndWholeBody() {
        for(origin in listOf(-2.0,0.0,5.0)) {
            val plan=SmartCutTiming.plan(video(origin),0.0,6.0)
            assertEquals(150,plan.times.size);assertEquals(1,plan.segments.size);assertTrue(plan.segments[0].copy)
            assertEquals(1,plan.segments[0].packetStart)
        }
    }
    @Test fun hiddenFinalReferenceOnlyRepairsItsGop() {
        val plan=SmartCutTiming.plan(video(hiddenReference=true),0.0,6.0)
        assertEquals(listOf(true,false),plan.segments.map { it.copy })
        assertEquals(4.0,plan.segments[0].end,0.000001);assertEquals(150,plan.segments.sumOf { it.frames })
    }
    @Test fun preciseEdgesAndAudioDurationAreBounded() {
        val plan=SmartCutTiming.plan(video(),1.401,4.601)
        assertEquals(listOf(false,true,false),plan.segments.map { it.copy })
        assertEquals(.039,plan.firstGap,0.000001)
        val args=FfmpegMediaPolicies.hybridConcatArguments("list.txt","out.mp4",0,true,"input.mp4",1_401_000,false,
            durationSeconds=3.2,firstGap=plan.firstGap).toList()
        assertEquals(3.2,args[args.indexOf("-t")+1].toDouble(),0.000001);assertTrue(args[args.indexOf("-af")+1].contains("atrim=duration=3.2"))
        assertEquals("disabled",args[args.indexOf("-avoid_negative_ts")+1])
    }
    @Test fun inaccurateMp4SeekCanCopyByPacketIndexWithoutReencoding() {
        val source=video(5.0)
        val body=SmartCutTiming.plan(source,1.401,4.601).segments.first { it.copy }
        val args=SmartCutTiming.arguments("source.mp4","body.ts",body,source,"h264",emptyList(),.08,true).toList()
        assertFalse(args.contains("-ss"));assertEquals("copy",args[args.indexOf("-c:v")+1])
        assertTrue(args[args.indexOf("-bsf:v")+1].contains("lt(n,${body.packetStart})"))
        assertTrue(args[args.indexOf("-bsf:v")+1].contains("PTS-1.960000000/TB"))
    }
    @Test fun mediaCodecEdgesAvoidUnsupportedBFrames() {
        val source=video();val edge=SmartCutTiming.plan(source,1.401,4.601).segments.first()
        val args=SmartCutTiming.arguments("source.mp4","edge.mp4",edge,source,"hevc",listOf("-c:v","hevc_mediacodec"),.08).toList()
        assertEquals("0",args[args.indexOf("-bf")+1]);assertEquals("hevc_mediacodec",args[args.indexOf("-c:v")+1])
    }
}
