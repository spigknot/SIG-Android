package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * R13/F1 — INVARIÂNCIA DE COMPORTAMENTO (telemetria é ADITIVA).
 *
 * Adicionar campos de log NÃO pode mudar nenhuma decisão publicável. Estes
 * testes alimentam o MESMO roteiro de replay — geometria de janela/contexto em
 * amostras (16 amostras = 1 ms, igual ao caminho live), segmentos VÁLIDOS e
 * INVÁLIDOS (os dois ramos de evidência), regiões retidas com áudio preservado,
 * passe de reparo (sucesso e falha) e finalize — e comparam a impressão digital
 * das decisões com a capturada do código ANTES da telemetria ([EXPECTED_SWEEP],
 * [EXPECTED_REPAIR]).
 *
 * Se qualquer decisão (added/provisional/committed/alignment/retido/reparo/
 * texto publicado) mudar, a string muda e o teste falha.
 */
class LiveReplayInvarianceF1Test {

    /** 0,25 s por palavra em amostras (16 kHz). */
    private val dur = 4000

    private fun wordsOf(n: Int): List<String> = (0 until n).map { "p$it" }

    /** PCM sintético determinístico (s16le) — mesma forma da suíte R8/B. */
    private fun pcmSamples(samples: Int): ByteArray {
        val out = ByteArray(samples * 2)
        for (i in 0 until samples) {
            val v = ((i * 37) % 3001) - 1500
            out[i * 2] = (v and 0xFF).toByte()
            out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    private class Win(
        val idx: Int,
        val text: String,
        val u0: Long,
        val u1: Long,
        val ctx: Int,
        val segments: List<LiveSegmentTime>?,
    )

    /** Janelas no formato do replay: [u0,u1) único, contexto = hop anterior. */
    private fun windows(words: List<String>, hop: Int, ctx: Int): List<Win> {
        val total = words.size.toLong() * dur
        val out = ArrayList<Win>()
        var idx = 0
        var u0 = 0L
        while (u0 < total) {
            val u1 = minOf(total, u0 + hop)
            val ws = maxOf(0L, u0 - ctx)
            val covered = words.filterIndexed { i, _ ->
                val w0 = i.toLong() * dur
                val w1 = (i + 1).toLong() * dur
                w1 > ws && w0 < u1
            }
            val text = covered.joinToString(" ")
            val ctxSamples = (u0 - ws).toInt()
            val windowMs = (ctxSamples + (u1 - u0)) / 16L
            val segs = when (idx) {
                // ramo "segmentos:k(evidencia)": 1 segmento por palavra, tempos
                // coerentes com a janela.
                2 -> {
                    val per = windowMs / covered.size
                    covered.mapIndexed { i, w ->
                        LiveSegmentTime(w, i * per, minOf(windowMs, (i + 1) * per))
                    }
                }
                // segmento com t1 FORA da janela: contrato rejeita -> estimado.
                4 -> listOf(LiveSegmentTime(text, 0, windowMs + 5000))
                // segmentos com texto que não casa com o texto da janela.
                6 -> listOf(LiveSegmentTime("zzz", 0, windowMs / 2))
                else -> null
            }
            out.add(Win(idx, text, u0, u1, ctxSamples, segs))
            u0 += hop
            idx++
        }
        return out
    }

    private fun infoLine(info: LiveTextCommitter.CommitInfo?): String =
        if (info == null) "null"
        else "a=${info.committedWordsAdded} p=${info.provisionalWords} c=${info.committedWordCount} " +
            "al=${info.alignment} h=${info.heldWords} hh=${info.heldHypotheses} ch=${info.conflictsHeld} " +
            "hr=${info.heldResolved} pr=${info.pendingRepairRegions} ra=${info.repairAttempts} " +
            "ro=${info.repairCompleted} rf=${info.repairFailed} rb=${info.retainedAudioBytes}"

    @Test
    fun `decisoes publicaveis identicas ao baseline pre-telemetria - varredura`() {
        val c = LiveTextCommitter()
        val windows = windows(wordsOf(40), hop = 3 * dur, ctx = dur)
        val sb = StringBuilder()

        for (w in windows) {
            val windowLen = w.ctx + (w.u1 - w.u0).toInt()
            val pcm = ByteArray(windowLen * 2) { (it % 251).toByte() }
            val info = c.onWindow(
                w.idx, w.text, w.u0, w.u1, w.ctx,
                segments = w.segments,
                windowPcm = pcm,
            )
            sb.append("w${w.idx}:${infoLine(info)}\n")
        }

        val fin = c.finalize()
        sb.append("fin:a=${fin.committedWordsAdded} c=${fin.committedWordCount} al=${fin.alignment}\n")
        sb.append("text:${c.displayText()}\n")
        sb.append(
            "tot:c=${c.committedWordCount()} p=${c.provisionalWordCount()} h=${c.heldWordCount()} " +
                "ra=${c.repairAttemptCount()} ro=${c.repairCompletedCount()} rf=${c.repairFailedCount()} " +
                "rb=${c.retainedAudioBytes()} pending=${c.pendingRepairCount()}\n"
        )

        assertEquals(EXPECTED_SWEEP, sb.toString())
    }

    @Test
    fun `decisoes publicaveis identicas ao baseline pre-telemetria - retencao e reparo`() {
        val c = LiveTextCommitter()
        val sb = StringBuilder()
        // [0,8000) provisório; [8000,16000) divergente = região retida com PCM.
        sb.append("w0:${infoLine(c.onWindow(0, "alfa", 0L, 8_000L, 0, windowPcm = pcmSamples(8_000)))}\n")
        sb.append("w1:${infoLine(c.onWindow(1, "zulu xadrez", 8_000L, 16_000L, 8_000, windowPcm = pcmSamples(16_000)))}\n")
        val req1 = c.takeRepairRequest()
        sb.append("req1:${req1?.let { "r${it.regionId}@${it.windowIndex} s=${it.startSample} e=${it.endSample} b=${it.pcm.size} w=${it.wordCount}" }}\n")
        if (req1 != null) {
            // Reproduz a hipótese inteira: resolve NO LUGAR.
            sb.append("rep1:${c.completeRepair(req1.regionId, "zulu xadrez")}\n")
        }
        // Segunda região retida; o reparo FALHA (fechamento tipado).
        sb.append("w2:${infoLine(c.onWindow(2, "kilo lima", 16_000L, 24_000L, 8_000, windowPcm = pcmSamples(16_000)))}\n")
        val req2 = c.takeRepairRequest()
        sb.append("req2:${req2?.let { "r${it.regionId}@${it.windowIndex} s=${it.startSample} e=${it.endSample} b=${it.pcm.size} w=${it.wordCount}" }}\n")
        if (req2 != null) sb.append("rep2:${c.failRepair(req2.regionId, "no_speech do reparo")}\n")
        sb.append("sem-segunda-tentativa:${c.takeRepairRequest()}\n")
        val fin = c.finalize()
        sb.append("fin:c=${fin.committedWordCount} al=${fin.alignment} unres=${fin.unresolvedRegionsClosed}\n")
        sb.append("text:${c.displayText()}\n")
        sb.append(
            "tot:c=${c.committedWordCount()} h=${c.heldWordCount()} ra=${c.repairAttemptCount()} " +
                "ro=${c.repairCompletedCount()} rf=${c.repairFailedCount()} rb=${c.retainedAudioBytes()}\n"
        )

        assertEquals(EXPECTED_REPAIR, sb.toString())
    }

    private companion object {
        /** Capturado do worktree ANTES de qualquer campo de telemetria. */
        val EXPECTED_SWEEP = """w0:a=1 p=2 c=1 al=anexo(esp=0) h=0 hh=0 ch=0 hr=0 pr=0 ra=0 ro=0 rf=0 rb=0
w1:a=0 p=5 c=1 al=texto:1(esp=1) h=0 hh=0 ch=0 hr=0 pr=0 ra=0 ro=0 rf=0 rb=0
w2:a=0 p=8 c=1 al=segmentos:1(evidencia) h=0 hh=0 ch=0 hr=0 pr=0 ra=0 ro=0 rf=0 rb=0
w3:a=0 p=11 c=1 al=texto:1(esp=1) h=0 hh=0 ch=0 hr=0 pr=0 ra=0 ro=0 rf=0 rb=0
w4:a=0 p=14 c=1 al=texto:1(esp=1) h=0 hh=0 ch=0 hr=0 pr=0 ra=0 ro=0 rf=0 rb=0
w5:a=0 p=17 c=1 al=texto:1(esp=1) h=0 hh=0 ch=0 hr=0 pr=0 ra=0 ro=0 rf=0 rb=0
w6:a=0 p=20 c=1 al=texto:1(esp=1) h=0 hh=0 ch=0 hr=0 pr=0 ra=0 ro=0 rf=0 rb=0
w7:a=0 p=23 c=1 al=texto:1(esp=1) h=0 hh=0 ch=0 hr=0 pr=0 ra=0 ro=0 rf=0 rb=0
w8:a=0 p=26 c=1 al=texto:1(esp=1) h=0 hh=0 ch=0 hr=0 pr=0 ra=0 ro=0 rf=0 rb=0
w9:a=0 p=29 c=1 al=texto:1(esp=1) h=0 hh=0 ch=0 hr=0 pr=0 ra=0 ro=0 rf=0 rb=0
w10:a=0 p=32 c=1 al=texto:1(esp=1) h=0 hh=0 ch=0 hr=0 pr=0 ra=0 ro=0 rf=0 rb=0
w11:a=0 p=35 c=1 al=texto:1(esp=1) h=0 hh=0 ch=0 hr=0 pr=0 ra=0 ro=0 rf=0 rb=0
w12:a=0 p=38 c=1 al=texto:1(esp=1) h=0 hh=0 ch=0 hr=0 pr=0 ra=0 ro=0 rf=0 rb=0
w13:a=0 p=39 c=1 al=texto:1(esp=1) h=0 hh=0 ch=0 hr=0 pr=0 ra=0 ro=0 rf=0 rb=0
fin:a=39 c=40 al=finalize
text:p0 p1 p2 p3 p4 p5 p6 p7 p8 p9 p10 p11 p12 p13 p14 p15 p16 p17 p18 p19 p20 p21 p22 p23 p24 p25 p26 p27 p28 p29 p30 p31 p32 p33 p34 p35 p36 p37 p38 p39
tot:c=40 p=0 h=0 ra=0 ro=0 rf=0 rb=0 pending=0
"""

        /** Idem para o roteiro com retenção + reparo. */
        val EXPECTED_REPAIR = """w0:a=0 p=1 c=0 al=anexo(esp=0) h=0 hh=0 ch=0 hr=0 pr=0 ra=0 ro=0 rf=0 rb=0
w1:a=1 p=0 c=1 al=retido:2(esp=1) h=2 hh=1 ch=2 hr=0 pr=1 ra=0 ro=0 rf=0 rb=16000
req1:r1@1 s=8000 e=16000 b=16000 w=2
rep1:true
w2:a=2 p=0 c=3 al=retido:2(esp=1) h=2 hh=1 ch=2 hr=0 pr=1 ra=1 ro=1 rf=0 rb=16000
req2:r2@2 s=16000 e=24000 b=16000 w=2
rep2:true
sem-segunda-tentativa:null
fin:c=5 al=finalize+retidas:1 unres=1
text:alfa zulu xadrez kilo lima
tot:c=5 h=0 ra=2 ro=1 rf=1 rb=0
"""
    }
}
