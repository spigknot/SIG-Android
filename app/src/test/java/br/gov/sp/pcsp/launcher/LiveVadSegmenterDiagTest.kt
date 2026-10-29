package br.gov.sp.pcsp.launcher

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * R7/E3 — diagnóstico do segmentador contra um PCM APROVADO real (d01): imprime
 * os intervalos decididos. Depende do arquivo de evidência local; sem ele o
 * teste é PULADO (não falha). Serve para explicar fronteiras de corte.
 */
class LiveVadSegmenterDiagTest {

    @Test
    fun `diagnostico d01 - intervalos decididos`() {
        val f = File("D:/SIG-whisper-r1/build/whisper-rodada7/20261010-2118/fase-e/audio/src/d01_aprovado.pcm")
        assumeTrue("PCM de diagnóstico ausente", f.isFile)
        val data = f.readBytes()
        val seg = LiveVadSegmenter(
            sampleRate = 16_000, padMs = 300, silenceCutMs = 1_500, maxSegmentMs = 15_000,
            record = { iv -> println("VAD %s [%d,%d) %.3fs-%.3fs".format(iv.kind, iv.startSample, iv.endSample, iv.startSample / 16000.0, iv.endSample / 16000.0)) }
        )
        var off = 0
        val out = ArrayList<PlannedWindow>()
        while (off < data.size) {
            val n = minOf(3_200, data.size - off)
            out += seg.accept(data, off, n)
            off += n
        }
        out += seg.flush()
        for (w in out) println("WIN %s [%d,%d) %.3fs-%.3fs".format(w.kind, w.startSample, w.endSample, w.startSample / 16000.0, w.endSample / 16000.0))
        println("STATS windows=${seg.stats().windows} forced=${seg.stats().forcedCuts} skipped=${seg.skippedSilenceSamples} pending=${seg.pendingSamples}")
    }
}
