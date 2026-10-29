package br.gov.sp.pcsp.launcher

import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * R7/E3 — ESTRATÉGIA do caminho AO VIVO (como o áudio é fatiado antes do
 * decode). Só o pipeline live consulta: modelo/backend/FA/VAD-do-modelo NÃO
 * mudam por aqui. Três estratégias concretas (medidas na fase E):
 *
 *  (1) [Fixed] 2 s — CONTROLE fixo (blocos sem contexto);
 *  (2) [Fixed] janela+contexto (R6 4+4 é o controle; valores ajustáveis);
 *  (3) [VadSegment] — VAD segmentando INTERVALOS de fala com padding e
 *      overlap contextual explícito, duração máxima, flush de silêncio/EOF.
 */
sealed class LiveStrategy {
    abstract fun describe(): String

    /** (1)/(2) — blocos fixos de [uniqueMs]; [contextMs] > 0 repete o tail
     * anterior como contexto de decode (o único continua exclusivo). */
    data class Fixed(val uniqueMs: Long, val contextMs: Long) : LiveStrategy() {
        override fun describe(): String = "janela: unico=${uniqueMs}ms contexto=${contextMs}ms"
    }

    /** (3) — VAD por energia adaptativa segmentando intervalos de fala.
     *
     * Contratos (fase E + R8/C):
     *  - DECISÃO DE SILÊNCIO = só ZERO-DIGITAL (todas as amostras 0). R8/C:
     *    trecho NÃO NULO abaixo do limiar é INCERTO e preservado — absorvido
     *    como pré-roll/pad pela janela de fala seguinte ou emitido como janela
     *    "uncertain" agrupada (≤ 4 s) para decode; nunca descartado nem contado
     *    como silêncio;
     *  - fala fraca é decisão do VAD (limiar = max([absoluteRmsFloor],
     *    [relativePeakFraction] × pico), com pico de memória LIMITADA a 2 s —
     *    R8/C: um pico antigo não suprime fala baixa depois);
     *  - PADDING [padMs] antes/depois de cada trecho de fala; o OVERLAP
     *    contextual é ÁUDIO REAL precedente do pipeline (contextSamples);
     *  - duração MÁXIMA [maxSegmentMs]: corte em fronteira de frame, sem
     *    concatenar indefinidamente (o corte reabre imediatamente);
     *  - flush: silêncio sustentado ([silenceCutMs]) fecha o intervalo e EOF/stop
     *    emite a cauda (nunca perde cauda com sinal).
     */
    data class VadSegment(
        val contextMs: Long,
        val padMs: Long,
        val silenceCutMs: Long,
        val maxSegmentMs: Long,
        val relativePeakFraction: Double = DEFAULT_RELATIVE_PEAK,
        val absoluteRmsFloor: Double = DEFAULT_ABS_RMS_FLOOR,
        /** R8/D — teto da PRIMEIRA janela (ms de fala; 0 = mesmo teto das
         * demais). Fechamento ADAPTATIVO INICIAL: a 1ª janela curta entrega o
         * 1º texto cedo; daí em diante valem as janelas longas estáveis. */
        val firstSegmentMs: Long = 0L,
        /** R8/D — corte de silêncio da PRIMEIRA janela (ms; 0 = o mesmo
         * [silenceCutMs]). Só a 1ª janela aceita fechar num silêncio curto —
         * fragmentação depois do 1º texto continua evitada (3 s). */
        val firstSilenceCutMs: Long = 0L,
    ) : LiveStrategy() {
        override fun describe(): String = String.format(
            Locale.US,
            "janela: vad segmento pad=%dms corte=%dms max=%dms contexto=%dms limiar=max(%.1f,%.2f×pico)" +
                (if (firstSegmentMs > 0L || firstSilenceCutMs > 0L) " 1a_janela=max=%dms/corte=%dms" else ""),
            padMs, silenceCutMs, maxSegmentMs, contextMs, absoluteRmsFloor, relativePeakFraction,
            if (firstSegmentMs > 0L) firstSegmentMs else maxSegmentMs,
            if (firstSilenceCutMs > 0L) firstSilenceCutMs else silenceCutMs
        )
    }

    companion object {
        /** Fração do pico móvel que ainda conta como fala (fala fraca passa). */
        const val DEFAULT_RELATIVE_PEAK = 0.12

        /** Piso absoluto de RMS (PCM16) — abaixo disso é silêncio de fundo. */
        const val DEFAULT_ABS_RMS_FLOOR = 10.0
    }
}

/**
 * R7/E3 (+R8/C) — registro de UM intervalo decidido pelo VAD, em AMOSTRAS do
 * fluxo ORIGINAL (mapa do áudio): `kind` = "speech" (decodificado), "forced"
 * (corte por duração máxima), "tail" (cauda no flush), "uncertain" (trecho
 * NÃO-FALA com sinal, agrupado e decodificado — R8/C) e "zero-digital"
 * (silêncio NÃO decodificado = todas as amostras 0, contabilizado em
 * [LivePcmCounters.silenceSkipped]). R8/C: trecho não nulo NUNCA é registrado
 * como silêncio.
 */
data class LiveVadInterval(val startSample: Long, val endSample: Long, val kind: String)

/** R7/E3 — janela pronta para o spool: [pcm] é o trecho ÚNICO (dono exclusivo);
 * o contexto repetido é anexado pelo pipeline. [startSample]/[endSample] são
 * amostras ABSOLUTAS do fluxo original (mapa do áudio). */
class PlannedWindow(
    val pcm: ByteArray,
    val startSample: Long,
    val endSample: Long,
    val kind: String,
) {
    val sampleCount: Int get() = pcm.size / 2
}

/** R7/E3 — planejador de janelas do caminho ao vivo (troca o corte fixo por
 * política própria: VAD). Consome PCM ACEITO e devolve janelas; o que não vira
 * janela é contado (silêncio) — nada some sem número. */
interface LiveWindowPlanner {
    /** Consome [length] bytes ACEITOS e devolve janelas prontas (ordem temporal). */
    fun accept(bytes: ByteArray, offset: Int, length: Int): List<PlannedWindow>

    /** Stop/EOF: emite a cauda (com sinal) e contabiliza o resto. */
    fun flush(): List<PlannedWindow>

    /** Amostras decididas como silêncio NÃO decodificado (contadas). */
    val skippedSilenceSamples: Long

    /** Áudio aceito ainda sem destino (RAM) — vira janela, silêncio ou perda. */
    val pendingSamples: Long

    /** Cancelamento: o pendente deixa de ter destino (quem conta é o pipeline). */
    fun abandonPending()

    /** Estatística do plano para o terminal/evidência. */
    fun stats(): LivePlanStats
}

data class LivePlanStats(
    val strategy: String,
    val windows: Int,
    val speechWindows: Int,
    val forcedCuts: Int,
    val tails: Int,
    /** R8/C — intervalos de SILÊNCIO CONTADO (agora só zero-digital: trecho
     * não nulo nunca é contado como silêncio). */
    val gaps: Int,
    val skippedSilenceSamples: Long,
    val silenceIntervals: List<LiveVadInterval>,
    /** R8/C — janelas "uncertain" (trecho não-fala com sinal, decodificado). */
    val uncertainWindows: Int = 0,
    /** R8/C — amostras decodificadas dentro de janelas "uncertain". */
    val uncertainSamples: Long = 0L,
    /** R8/D — 1ª janela fechada pelo teto PRÓPRIO (fechamento adaptativo inicial). */
    val firstCuts: Int = 0,
)

/**
 * R7/E3 (+R8/C) — SEGMENTADOR VAD do caminho ao vivo (puro Kotlin, sem modelo):
 * frames de 20 ms, limiar adaptativo `max(piso_abs, fração × pico)` com
 * histerese de onset (2 frames) e corte de silêncio sustentado. R8/C: o pico
 * tem memória LIMITADA por [peakHorizonMs] — um pico antigo sai do horizonte e
 * deixa de suprimir fala baixa (a meia-vida longa de [peakDecayPerFrame] não
 * governa mais o limiar).
 *
 * Contrato R8/C (preservação do incerto): energia serve para FRONTEIRAS e
 * AGRUPAMENTO, não como prova para descartar sinal. Só ZERO-DIGITAL (todas as
 * amostras 0) é pulado como silêncio; qualquer trecho NÃO NULO abaixo do
 * limiar é INCERTO e preservado: é absorvido (como pré-roll/pad) pela janela
 * de fala seguinte ou vira janela "uncertain" agrupada (≤ [uncertainGroupMs])
 * para decode — recuperação explícita, contada e NUNCA rotulada de silêncio.
 * O retido fica limitado (grupo + pré-roll) e o PCM incerto é mantido até a
 * decisão; captura não bloqueia por inferência (o segmentador não decodifica).
 *
 * Toda decisão tem endereço em amostras do fluxo ORIGINAL. Quem chama garante
 * nº PAR de bytes (fragmento ímpar não vira silêncio aqui: não é aceito).
 * NÃO usa microfone: recebe bytes de quem chama (AudioRecord em produção,
 * fonte injetada no lab) e não toca em áudio fora do laço [accept]/[flush].
 */
class LiveVadSegmenter @JvmOverloads constructor(
    sampleRate: Int = 16_000,
    private val padMs: Long = 300,
    private val silenceCutMs: Long = 700,
    private val maxSegmentMs: Long = 12_000,
    private val relativePeakFraction: Double = LiveStrategy.DEFAULT_RELATIVE_PEAK,
    private val absoluteRmsFloor: Double = LiveStrategy.DEFAULT_ABS_RMS_FLOOR,
    /** Frames consecutivos acima do limiar para declarar ONSET de fala. */
    private val onsetFrames: Int = 2,
    /** LEGADO (mantido por compatibilidade de assinatura do reproducer da
     * auditoria): a memória do pico agora é o horizonte limitado
     * [peakHorizonMs]; este valor não é mais usado no limiar. */
    private val peakDecayPerFrame: Double = 0.999,
    private val record: ((LiveVadInterval) -> Unit)? = null,
    /** R8/C — horizonte (ms) da memória do pico: o limiar relativo usa o maior
     * RMS dos últimos [peakHorizonMs] (um pico não suprime fala baixa por mais
     * que isto). */
    private val peakHorizonMs: Long = 2_000,
    /** R8/C — teto de AGRUPAMENTO de um trecho incerto (não-fala não nula)
     * antes de virar janela "uncertain": limita RAM retida e o decode. */
    private val uncertainGroupMs: Long = 4_000,
    /** R8/D (candidata 1) — teto da PRIMEIRA janela em ms de fala (0 = mesmo
     * teto das demais): fechamento ADAPTATIVO INICIAL — a 1ª protelação curta
     * entrega o 1º texto cedo; depois valem as janelas longas estáveis. */
    private val firstSegmentMs: Long = 0L,
    /** R8/D (candidata 1) — corte de silêncio da PRIMEIRA janela (0 = mesmo
     * [silenceCutMs]): só a 1ª janela fecha em silêncio curto. */
    private val firstSilenceCutMs: Long = 0L,
) : LiveWindowPlanner {

    private val sr = sampleRate
    private val frameSamples = max(1, sr / 50)
    private val padSamples = (sr * padMs / 1000).toInt()
    private val silenceCutSamples = max(frameSamples, (sr * silenceCutMs / 1000).toInt())
    private val maxSegmentSamples = max(frameSamples, (sr * maxSegmentMs / 1000).toInt())
    /** R8/D — 1ª janela: teto/corte próprios (0 = desligado). */
    private val firstSegmentSamples =
        if (firstSegmentMs > 0L) max(frameSamples, (sr * firstSegmentMs / 1000).toInt()) else 0
    private val firstSilenceCutSamples =
        if (firstSilenceCutMs > 0L) max(frameSamples, (sr * firstSilenceCutMs / 1000).toInt()) else 0
    /** Menor janela ÚNICA aceitável (1 s): fragmentos menores são absorvidos
     * pela janela seguinte em vez de pagar um decode inteiro sozinhos. */
    private val minWindowSamples = max(frameSamples, sr)
    /** Pré-roll retido no silêncio: nunca menos que o necessário para o onset. */
    private val preRollSamples = max(padSamples, (onsetFrames + 1) * frameSamples)
    /** R8/C — anel do pico (horizonte LIMITADO) e teto do agrupamento incerto. */
    private val peakHorizonFrames = max(1, (peakHorizonMs / 20).toInt())
    private val peakRing = DoubleArray(peakHorizonFrames)
    private var peakRingIdx = 0
    private var peakRingCount = 0
    private val uncertainGroupSamples = max(frameSamples, (sr * uncertainGroupMs / 1000).toInt())

    private var buf = ByteArray(0)
    private var bufBytes = 0
    private var bufStart = 0L
    private var pos = 0L
    private var frameCursor = 0L
    private var nextWindowFloor = 0L

    private var inSpeech = false
    private var coreStart = -1L
    private var lastSpeechEnd = -1L
    private var onsetRun = 0
    private var runningPeak = 0.0
    /** Fim do último frame CLARAMENTE quieto do intervalo corrente (rms bem
     * abaixo do limiar): usado como fronteira preferida do corte forçado —
     * cortar no silêncio evita partir palavra no meio. */
    private var lastQuietEnd = -1L
    /** Corridos de frames não-fala (marca candidato a fronteira de corte). */
    private var quietRun = 0
    /** Fim do corrido quieto EM ANDAMENTO (só vira fronteira quando ENCERRA). */
    private var quietRunEnd = -1L
    /** Mínimo de frames não-fala (300 ms) para um corrido valer como fronteira:
     * uma queda curta no meio da palavra não é silêncio de verdade. */
    private val minQuietFrames = max(2, 300 / max(1, 1000 / 50))

    private var windows = 0
    private var speechWindows = 0
    private var forcedCuts = 0
    private var firstCuts = 0
    private var tails = 0
    private var gaps = 0
    private var skippedSamples = 0L
    /** R8/C — janelas/amostras "uncertain" (não-fala com sinal, decodificada). */
    private var uncertainWindows = 0
    private var uncertainSamples = 0L
    private val silenceIntervals = ArrayList<LiveVadInterval>()

    /** R8/D — corte de silêncio da janela CORRENTE: a 1ª janela aceita o corte
     * curto de [firstSilenceCutMs]; depois vale o corte estável. */
    private fun effectiveSilenceCutSamples(): Int =
        if (windows == 0 && firstSilenceCutSamples > 0) minOf(firstSilenceCutSamples, silenceCutSamples)
        else silenceCutSamples

    /** R8/D — teto de duração da janela CORRENTE: a 1ª usa [firstSegmentMs]
     * (fechamento adaptativo inicial); depois vale o teto cheio. */
    private fun effectiveMaxSegmentSamples(): Int =
        if (windows == 0 && firstSegmentSamples > 0) minOf(firstSegmentSamples, maxSegmentSamples)
        else maxSegmentSamples

    override val skippedSilenceSamples: Long get() = skippedSamples

    override val pendingSamples: Long get() = pos - bufStart

    override fun accept(bytes: ByteArray, offset: Int, length: Int): List<PlannedWindow> {
        if (length <= 0) return emptyList()
        appendToBuffer(bytes, offset, length)
        pos += (length / 2).toLong()
        val out = ArrayList<PlannedWindow>(2)
        while (pos - frameCursor >= frameSamples) {
            processFrame(frameCursor, out)
            frameCursor += frameSamples
        }
        settleNonSpeech(out)
        return out
    }

    override fun flush(): List<PlannedWindow> {
        val out = ArrayList<PlannedWindow>(2)
        if (inSpeech) {
            // Cauda parcial (frame incompleto) conta como fala: nunca perde cauda.
            val coreEnd = maxOf(lastSpeechEnd, frameCursor)
            closeInterval(out, coreEnd, "tail")
        }
        if (pos > bufStart) {
            val head = maxOf(bufStart, nextWindowFloor)
            if (head < pos) {
                val nz = firstNonZeroSample(head, pos)
                if (nz < 0) {
                    // R8/C: atalho permitido — puro zero-digital.
                    commitSkip(head, pos, "zero-digital")
                } else {
                    if (nz > head) commitSkip(head, nz, "zero-digital")
                    // R8/C: QUALQUER sinal retido vira janela (nunca silêncio).
                    emit(out, nz, pos, "tail")
                }
            }
        }
        // Fim do plano: nada fica pendente na RAM.
        dropBefore(pos)
        return out
    }

    override fun abandonPending() {
        bufBytes = 0
        bufStart = pos
        frameCursor = pos
        nextWindowFloor = pos
        inSpeech = false
        coreStart = -1L
        lastSpeechEnd = -1L
        onsetRun = 0
    }

    override fun stats(): LivePlanStats = LivePlanStats(
        strategy = "vad-segment",
        windows = windows,
        speechWindows = speechWindows,
        forcedCuts = forcedCuts,
        tails = tails,
        gaps = gaps,
        skippedSilenceSamples = skippedSamples,
        silenceIntervals = ArrayList(silenceIntervals),
        uncertainWindows = uncertainWindows,
        uncertainSamples = uncertainSamples,
        firstCuts = firstCuts,
    )

    // ------------------------------------------------------------------ internos

    private fun appendToBuffer(bytes: ByteArray, offset: Int, length: Int) {
        val need = bufBytes + length
        if (need > buf.size) {
            var cap = if (buf.size == 0) 1 shl 16 else buf.size
            while (cap < need) cap = cap shl 1
            buf = buf.copyOf(cap)
        }
        System.arraycopy(bytes, offset, buf, bufBytes, length)
        bufBytes += length
    }

    private fun dropBefore(abs: Long) {
        val dropSamples = (abs - bufStart).toInt()
        if (dropSamples <= 0) return
        val dropBytes = dropSamples * 2
        System.arraycopy(buf, dropBytes, buf, 0, bufBytes - dropBytes)
        bufBytes -= dropBytes
        bufStart += dropSamples
    }

    private fun frameStats(fs: Long, fe: Long): Pair<Double, Boolean> {
        val off = ((fs - bufStart) * 2).toInt()
        var sum = 0.0
        var allZero = true
        var i = off
        val end = off + frameSamples * 2
        while (i < end) {
            val bits = (buf[i].toInt() and 0xFF) or (buf[i + 1].toInt() shl 8)
            val s = bits.toShort().toInt()
            if (s != 0) allZero = false
            sum += s.toDouble() * s.toDouble()
            i += 2
        }
        return Pair(sqrt(sum / frameSamples), allZero)
    }

    /** R8/C — primeira amostra NÃO zero em [from,to) (pares s16le); -1 = tudo
     * zero. Quem chama garante nº PAR de bytes (fragmento ímpar não vira
     * silêncio: não é aceito — o descarte de byte ímpar é contado no pipeline). */
    private fun firstNonZeroSample(from: Long, to: Long): Long {
        var i = ((from - bufStart) * 2).toInt()
        val end = ((to - bufStart) * 2).toInt()
        while (i < end) {
            if (buf[i] != 0.toByte() || buf[i + 1] != 0.toByte()) return from + (i / 2).toLong()
            i += 2
        }
        return -1L
    }

    private fun processFrame(fs: Long, out: MutableList<PlannedWindow>) {
        val fe = fs + frameSamples
        if (pos < fe) return
        val (rms, allZero) = frameStats(fs, fe)
        // R8/C — pico com memória LIMITADA: maior RMS dos últimos
        // [peakHorizonMs]; fora do horizonte um pico não suprime mais nada
        // (sem memória excessiva do pico, sem apagar fala baixa).
        peakRing[peakRingIdx] = rms
        peakRingIdx = (peakRingIdx + 1) % peakHorizonFrames
        if (peakRingCount < peakHorizonFrames) peakRingCount++
        var peakMax = 0.0
        for (i in 0 until peakRingCount) if (peakRing[i] > peakMax) peakMax = peakRing[i]
        runningPeak = peakMax
        val threshold = max(absoluteRmsFloor, relativePeakFraction * runningPeak)
        // Semáforo de fala: zero-digital NUNCA é fala; fala fraca é decisão do
        // limiar adaptativo (não do portão de bytes).
        val speech = !allZero && rms >= threshold

        if (inSpeech) {
            if (speech) {
                lastSpeechEnd = fe
            } else if (fe - lastSpeechEnd >= effectiveSilenceCutSamples()) {
                closeInterval(out, lastSpeechEnd, "speech")
                return
            }
            // Fronteira candidata: corrido de não-fala de ≥300 ms JÁ ENCERRADO
            // (o corrido em andamento não vale: cortar dentro dele não ajudaria
            // a fechar palavra, e um dip curto no meio da fala não é silêncio).
            if (!speech) {
                quietRun++
                quietRunEnd = fe
            } else {
                if (quietRun >= minQuietFrames) lastQuietEnd = quietRunEnd
                quietRun = 0
                quietRunEnd = -1L
            }
            if (inSpeech && (fe + frameSamples - coreStart) > effectiveMaxSegmentSamples()) {
                // Duração MÁXIMA: fecha ANTES de estourar o teto e REABRE na
                // mesma amostra. Fronteira PREFERIDA = último frame quieto
                // (metade final do trecho), nunca o meio de uma palavra se
                // houver silêncio disponível; a continuidade vem do contexto.
                // R8/D: a 1ª janela usa teto próprio ([firstSegmentMs]) — a
                // curta inicial entrega o 1º texto; as demais seguem o teto cheio.
                val cap = effectiveMaxSegmentSamples()
                val candidate = if (lastQuietEnd > coreStart + cap / 2) lastQuietEnd else fe
                forcedCuts++
                if (windows == 0 && firstSegmentSamples > 0) firstCuts++
                closeInterval(out, candidate, "forced")
                // Reabre SEMPRE na mesma amostra (as amostras entre o candidato e
                // o instante do corte já foram processadas: ficam na janela nova,
                // nunca somem). Se o corte caiu no silêncio, o intervalo novo
                // simplesmente começa nele e segue até a próxima decisão.
                inSpeech = true
                coreStart = candidate
                lastSpeechEnd = maxOf(lastSpeechEnd, candidate)
                onsetRun = 0
                lastQuietEnd = -1L
                quietRun = 0
                quietRunEnd = -1L
            }
        } else {
            if (speech) {
                onsetRun++
                if (onsetRun >= onsetFrames) {
                    inSpeech = true
                    coreStart = max(0L, fs - (onsetRun - 1) * frameSamples)
                    lastSpeechEnd = fe
                    lastQuietEnd = -1L
                    quietRun = 0
                }
            } else {
                onsetRun = 0
            }
        }
    }

    private fun closeInterval(out: MutableList<PlannedWindow>, coreEnd: Long, kind: String) {
        if (!inSpeech) return
        val ws = maxOf(coreStart - padSamples, nextWindowFloor)
        // Corte forçado: sem pad DEPOIS (a janela seguinte reabre na fronteira).
        val we = min(pos, if (kind == "forced") coreEnd else coreEnd + padSamples)
        if (kind == "speech" && (we - ws) < minWindowSamples) {
            // Fragmento curto (<1 s de único): NÃO paga um decode inteiro por
            // ele — o intervalo segue e a próxima janela o absorve (nada é
            // descartado; a exclusividade do único continua respeitada).
            coreStart = ws
            quietRun = 0
            quietRunEnd = -1L
            return
        }
        emit(out, ws, we, kind)
        inSpeech = false
        coreStart = -1L
        lastSpeechEnd = -1L
        onsetRun = 0
        quietRun = 0
        quietRunEnd = -1L
    }

    private fun emit(out: MutableList<PlannedWindow>, wsIn: Long, weIn: Long, kind: String) {
        var ws = maxOf(wsIn, bufStart, nextWindowFloor)
        val we = min(weIn, pos)
        if (we <= ws) return
        // R8/C — prefixo antes da janela: SÓ zeros podem virar silêncio contado;
        // sinal RETIDO é absorvido (a janela começa no primeiro sinal) — nunca
        // descartado como "gap"/"pre-roll".
        val nz = firstNonZeroSample(bufStart, ws)
        if (nz < 0) {
            if (ws > bufStart) {
                commitSkip(bufStart, ws, "zero-digital")
                dropBefore(ws)
            }
        } else {
            if (nz > bufStart) {
                commitSkip(bufStart, nz, "zero-digital")
                dropBefore(nz)
            }
            ws = nz
        }
        val pcm = ByteArray(((we - ws) * 2).toInt())
        System.arraycopy(buf, ((ws - bufStart) * 2).toInt(), pcm, 0, pcm.size)
        out.add(PlannedWindow(pcm, ws, we, kind))
        dropBefore(we)
        nextWindowFloor = we
        windows++
        speechWindows++
        if (kind == "tail") tails++
        if (kind == "uncertain") {
            uncertainWindows++
            uncertainSamples += (we - ws)
        }
        record?.invoke(LiveVadInterval(ws, we, kind))
    }

    private fun commitSkip(from: Long, to: Long, kind: String) {
        val n = to - from
        if (n <= 0) return
        skippedSamples += n
        gaps++
        silenceIntervals.add(LiveVadInterval(from, to, kind))
        record?.invoke(LiveVadInterval(from, to, kind))
    }

    /**
     * R8/C — não-fala: pula SÓ zero-digital e PRESERVA o sinal retido.
     *
     * A cauda de [preRollSamples] fica reservada para o onset (pad). O trecho
     * decidível [head, frameCursor−preRoll) que tem sinal e estoura o teto de
     * agrupamento vira janela "uncertain" (decode/recuperação explícita); o
     * resto fica retido (RAM limitada por grupo + pré-roll) até a decisão —
     * nenhuma amostra com sinal é contada como silêncio ou descartada aqui.
     */
    private fun settleNonSpeech(out: MutableList<PlannedWindow>) {
        if (inSpeech) return
        var head = maxOf(bufStart, nextWindowFloor)
        val decEnd = maxOf(head, frameCursor - preRollSamples)
        while (head < decEnd) {
            val nz = firstNonZeroSample(head, decEnd)
            if (nz < 0) {
                // Só zeros: atalho contado (zero-digital).
                commitSkip(head, decEnd, "zero-digital")
                dropBefore(decEnd)
                return
            }
            if (nz > head) {
                commitSkip(head, nz, "zero-digital")
                dropBefore(nz)
                head = nz
            }
            if (decEnd - head < uncertainGroupSamples) return
            // Trecho incerto estourou o teto: agrupa e decodifica.
            emit(out, head, head + uncertainGroupSamples, "uncertain")
            head = maxOf(bufStart, nextWindowFloor)
            if (head >= decEnd) return
        }
    }
}

/** R7/E3 — planejador de blocos FIXOS (estratégias 1/2) na MESMA interface do
 * VAD: bloco de [chunkSamples]; contexto repetido continua sendo anexado pelo
 * pipeline. Mantém a semântica exata do modo clássico. */
class FixedWindowPlanner(private val chunkSamples: Int) : LiveWindowPlanner {
    private var acc = ByteArray(chunkSamples * 2)
    private var accSize = 0
    private var emitted = 0L

    override val skippedSilenceSamples: Long = 0L

    override val pendingSamples: Long get() = (accSize / 2).toLong()

    override fun accept(bytes: ByteArray, offset: Int, length: Int): List<PlannedWindow> {
        val out = ArrayList<PlannedWindow>(1)
        var off = offset
        val end = offset + length
        while (off < end) {
            val take = min(acc.size - accSize, end - off)
            System.arraycopy(bytes, off, acc, accSize, take)
            accSize += take
            off += take
            if (accSize == acc.size) {
                val pcm = acc.copyOf(accSize)
                out.add(PlannedWindow(pcm, emitted, emitted + chunkSamples, "fixed"))
                emitted += chunkSamples
                accSize = 0
            }
        }
        return out
    }

    override fun flush(): List<PlannedWindow> {
        if (accSize <= 0) return emptyList()
        val samples = accSize / 2
        val pcm = acc.copyOf(accSize)
        accSize = 0
        val w = PlannedWindow(pcm, emitted, emitted + samples, "fixed")
        emitted += samples
        return listOf(w)
    }

    override fun abandonPending() { accSize = 0 }

    override fun stats(): LivePlanStats = LivePlanStats(
        strategy = "fixed",
        windows = 0,
        speechWindows = 0,
        forcedCuts = 0,
        tails = 0,
        gaps = 0,
        skippedSilenceSamples = 0L,
        silenceIntervals = emptyList(),
    )
}
