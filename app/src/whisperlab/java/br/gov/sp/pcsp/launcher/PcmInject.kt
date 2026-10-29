package br.gov.sp.pcsp.launcher

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * R8/D — UMA leitura da fonte com o pacing REAL observado (lab).
 *
 *  - [targetMs] = DEADLINE da leitura (deadline monotônico acumulado por
 *    amostras entregues/speed, ms desde a 1ª leitura);
 *  - [returnMs] = instante real do retorno da leitura;
 *  - [lateMs]   = atraso ANTES de dormir (return > target => a leitura "perdeu"
 *    o deadline e entra em recuperação);
 *  - [jitterMs] = erro de retorno vs deadline depois do ajuste (≈0 quando o
 *    relógio está em dia; positivo = overhead do scheduler não compensado).
 */
data class PcmReadEvent(
    val index: Int,
    val samples: Long,
    val deliveredSamples: Long,
    val targetMs: Double,
    val returnMs: Double,
    val lateMs: Double,
    val jitterMs: Double,
    val sleepMs: Double,
)

/** R8/D — resumo de pacing da fonte (jitter/atraso/recuperação), emitido no close. */
data class PcmSourceStats(
    val reads: Int,
    val samples: Long,
    val audioMs: Double,
    val wallMs: Double,
    /** wall/áudio: 1.0 = tempo real exato; < 1 = mais rápido que o real. */
    val ratio: Double,
    val jitterAbsP50Ms: Double,
    val jitterAbsP95Ms: Double,
    val jitterAbsMaxMs: Double,
    val lateSumMs: Double,
    val lateMaxMs: Double,
    /** Leituras que perderam o deadline e recuperaram sem re-âncora. */
    val catchUps: Int,
    /** Re-âncoras do relógio por atraso > [recoverLagMs] (excedente reportado). */
    val reAnchors: Int,
    val reAnchorMs: Double,
    val pauses: Int,
    val pauseInjectedMs: Double,
    val eofReturnMs: Double,
    /** EOF vs plano CORRENTE do relógio (já com as re-âncoras): > 0 = EOF
     * ADIANTADO do plano; < 0 = atrasado. */
    val eofAheadOfPlanMs: Double,
)

/**
 * s11 (R3/C2) + R8/D — fonte de PCM16 INJETADA para o laboratório. Lê um
 * arquivo `.pcm` cru (mono 16 kHz s16le) com ritmo controlado:
 *  - [speed] = 1.0 => tempo real (100 ms de áudio por 100 ms de relógio);
 *  - [speed] > 1.0 => mais rápido (gera backlog além da fila antiga);
 *  - [speed] = 0.0 => rajada (sem espera).
 *
 * R8/D — CADÊNCIA POR DEADLINE MONOTÔNICO: a espera de cada leitura é
 * `t0 + amostras_entregues/(16000×speed)` (clock MONOTÔNICO, System.nanoTime),
 * NUNCA o sleep relativo por leitura da versão anterior (que pagava
 * read+copy+agenda por cima do áudio e entregava ≈0,99× real, acumulando
 * atraso de ~0,6 s/min no sustentado — ver R7/E4). O overhead real de cada
 * leitura é absorvido pelo deadline absoluto da seguinte; atrasos grandes
 * (pausa de scheduler/app freeze) são recuperados em modo catch-up, com o
 * excedente acima de [recoverLagMs] RE-ANCORADO e REPORTADO (nunca vira
 * rajada enorme de áudio nem some silenciosamente).
 *
 * [onRead]/[onStats] são o canal de evidência (JSONL/CSV do lab); nenhuma
 * settings global é consultada. SÓ existe no flavor de laboratório —
 * produção usa AudioRecord.
 */
class FilePcmSource(
    pcmFile: File,
    private val speed: Double,
    /** Tamanho das leituras em bytes (par). */
    private val readBytes: Int = 3200,
    /** R8/D (lab): ao entregar [pauseAtMs] de ÁUDIO (ms), dorme [pauseForMs]
     * uma única vez — simula pausa de scheduler no meio da injeção. */
    private val pauseAtMs: Long = -1L,
    private val pauseForMs: Long = 0L,
    /** R8/D: atraso máximo compensado em catch-up; o excedente re-ancora o
     * relógio e fica REPORTADO em [PcmSourceStats.reAnchorMs]. 0 = desligado. */
    private val recoverLagMs: Double = 2_000.0,
    private val onRead: ((PcmReadEvent) -> Unit)? = null,
    private val onStats: ((PcmSourceStats) -> Unit)? = null,
) : LivePcmSource {

    private val data: ByteArray = pcmFile.readBytes()
    private var offset = 0

    /** Clock monotônico da 1ª leitura (ns); -1 = ainda não iniciado. */
    private var t0Ns = -1L
    private var samplesDelivered = 0L
    private var readIndex = 0
    private var pauseDone = false
    private var pauses = 0
    private var closed = false

    private var catchUps = 0
    private var reAnchors = 0
    private var reAnchorMs = 0.0
    private var lateSumMs = 0.0
    private var lateMaxMs = 0.0
    private var pauseInjectedMs = 0.0
    private var eofReturnMs = -1.0
    private val events: ArrayList<PcmReadEvent>? = if (onStats != null) ArrayList(4096) else null

    fun totalSamples(): Long = data.size.toLong() / 2

    fun sha256(): String = MessageDigest.getInstance("SHA-256").digest(data)
        .joinToString("") { "%02x".format(it) }

    fun allBytes(): ByteArray = data

    /** Deadline (ns monotônicos) para [samples] amostras entregues. */
    private fun targetNsFor(samples: Long): Long {
        if (speed <= 0.0) return t0Ns
        val audioNs = (samples.toDouble() / 16_000.0 * 1_000_000_000.0 / speed).toLong()
        return t0Ns + audioNs
    }

    override fun read(buffer: ByteArray): Int {
        if (offset >= data.size) {
            // EOF: devolve -1 na hora (o arquivo acabou). O plano de tempo no EOF
            // fica registrado: quanto o EOF chegou ANTES do deadline do áudio todo.
            if (t0Ns > 0 && speed > 0.0) {
                eofReturnMs = (System.nanoTime() - t0Ns) / 1e6
            }
            return -1
        }
        if (t0Ns < 0L) t0Ns = System.nanoTime()
        var n = minOf(readBytes, data.size - offset, buffer.size)
        if (n % 2 != 0) n -= 1
        if (n <= 0) return -1
        System.arraycopy(data, offset, buffer, 0, n)
        offset += n
        val totalSamples = samplesDelivered + n / 2

        var lateMs = 0.0
        var sleepMs = 0.0
        if (speed > 0.0) {
            var targetNs = targetNsFor(totalSamples)
            val now = System.nanoTime()
            if (now >= targetNs) {
                // Atrasado: entra em catch-up (zero espera) até re-sincronizar.
                lateMs = (now - targetNs) / 1e6
                // Só conta como recuperação quando o atraso é de verdade (>=1 ms);
                // micro-overshoot de sleep é absorvido sem virar evento.
                if (lateMs >= 1.0) catchUps++
                lateSumMs += lateMs
                if (lateMs > lateMaxMs) lateMaxMs = lateMs
                // Recuperação LIMITADA: excedente acima de recoverLagMs re-âncora
                // o relógio (reportado em reAnchorMs) — nunca uma rajada enorme.
                if (recoverLagMs > 0.0 && lateMs > recoverLagMs) {
                    val excessNs = ((lateMs - recoverLagMs) * 1e6).toLong()
                    t0Ns += excessNs
                    reAnchors++
                    reAnchorMs += lateMs - recoverLagMs
                    targetNs = targetNsFor(totalSamples)
                }
            }
            val preSleep = System.nanoTime()
            if (preSleep < targetNs) {
                val d = targetNs - preSleep
                try {
                    Thread.sleep(d / 1_000_000L, (d % 1_000_000L).toInt())
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                sleepMs = (System.nanoTime() - preSleep) / 1e6
            }
        }

        samplesDelivered = totalSamples
        val retNs = System.nanoTime()
        val targetMsFinal = if (speed > 0.0) (targetNsFor(totalSamples) - t0Ns) / 1e6 else -1.0
        val returnMs = (retNs - t0Ns) / 1e6
        val evt = PcmReadEvent(
            index = readIndex,
            samples = n / 2L,
            deliveredSamples = totalSamples,
            targetMs = targetMsFinal,
            returnMs = returnMs,
            lateMs = lateMs,
            jitterMs = if (speed > 0.0) returnMs - targetMsFinal else 0.0,
            sleepMs = sleepMs,
        )
        readIndex++
        events?.add(evt)
        onRead?.invoke(evt)

        // Pausa injetada (lab): DEPOIS de medir esta leitura — o estrago
        // aparece na PRÓXIMA (é assim que um scheduler parado afeta a fonte).
        if (pauseForMs > 0 && !pauseDone && pauseAtMs >= 0 &&
            totalSamples / 16.0 >= pauseAtMs
        ) {
            pauseDone = true
            pauses++
            pauseInjectedMs += pauseForMs
            try {
                Thread.sleep(pauseForMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        return n
    }

    override fun close() {
        if (closed) return
        closed = true
        val sink = onStats ?: return
        val audioMs = samplesDelivered / 16.0
        val wallMs = if (t0Ns > 0) (System.nanoTime() - t0Ns) / 1e6 else 0.0
        val absJitter = (events ?: emptyList()).map { kotlin.math.abs(it.jitterMs) }.sorted()
        fun pct(p: Double): Double =
            if (absJitter.isEmpty()) 0.0 else absJitter[minOf(absJitter.size - 1, (absJitter.size * p).toInt())]
        sink(
            PcmSourceStats(
                reads = readIndex,
                samples = samplesDelivered,
                audioMs = audioMs,
                wallMs = wallMs,
                ratio = if (audioMs > 0) wallMs / audioMs else 0.0,
                jitterAbsP50Ms = pct(0.50),
                jitterAbsP95Ms = pct(0.95),
                jitterAbsMaxMs = if (absJitter.isEmpty()) 0.0 else absJitter.last(),
                lateSumMs = lateSumMs,
                lateMaxMs = lateMaxMs,
                catchUps = catchUps,
                reAnchors = reAnchors,
                reAnchorMs = reAnchorMs,
                pauses = pauses,
                pauseInjectedMs = pauseInjectedMs,
                eofReturnMs = eofReturnMs,
                eofAheadOfPlanMs = if (eofReturnMs >= 0 && speed > 0.0) {
                    (targetNsFor(samplesDelivered) - t0Ns) / 1e6 - eofReturnMs
                } else 0.0,
            )
        )
    }
}

/** s11 — fonte que FALHA no meio (erro explícito após [failAfterReads] leituras). */
class FailingPcmSource(
    pcmFile: File,
    private val failAfterReads: Int,
    private val readBytes: Int = 3200,
) : LivePcmSource {

    private val data: ByteArray = pcmFile.readBytes()
    private var offset = 0
    private var reads = 0

    fun sha256(): String = MessageDigest.getInstance("SHA-256").digest(data)
        .joinToString("") { "%02x".format(it) }

    fun allBytes(): ByteArray = data

    override fun read(buffer: ByteArray): Int {
        reads++
        if (reads > failAfterReads) throw IllegalStateException("s11-fonte-falha-injetada na leitura $reads")
        if (offset >= data.size) return -1
        var n = minOf(readBytes, data.size - offset, buffer.size)
        if (n % 2 != 0) n -= 1
        if (n <= 0) return -1
        System.arraycopy(data, offset, buffer, 0, n)
        offset += n
        return n
    }

    override fun close() {}
}

/** WAV PCM16 mono para o chunk (o JNI transcreve caminho de arquivo). */
fun writePcmWav(file: File, pcm: ByteArray, sampleRate: Int = 16000) {
    FileOutputStream(file).use { out ->
        val byteRate = sampleRate * 2
        fun writeIntLE(v: Int) {
            out.write(v and 0xFF); out.write((v shr 8) and 0xFF)
            out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF)
        }
        fun writeShortLE(v: Int) {
            out.write(v and 0xFF); out.write((v shr 8) and 0xFF)
        }
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        writeIntLE(36 + pcm.size)
        out.write("WAVE".toByteArray(Charsets.US_ASCII))
        out.write("fmt ".toByteArray(Charsets.US_ASCII))
        writeIntLE(16)
        writeShortLE(1)
        writeShortLE(1)
        writeIntLE(sampleRate)
        writeIntLE(byteRate)
        writeShortLE(2)
        writeShortLE(16)
        out.write("data".toByteArray(Charsets.US_ASCII))
        writeIntLE(pcm.size)
        out.write(pcm)
    }
}
