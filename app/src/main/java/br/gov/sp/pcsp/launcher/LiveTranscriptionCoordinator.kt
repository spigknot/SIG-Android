package br.gov.sp.pcsp.launcher

import android.media.AudioRecord
import java.io.File

/**
 * R3/C2 — coordenador ÚNICO do caminho ao vivo. É este o código que a
 * WhisperActivity REAL usa (rota de produção: AudioRecord + transcribeAndStatus)
 * e o que o laboratório exercita com fonte PCM injetada — não há segunda
 * implementação do laço leitor/stop.
 *
 * Garantias centrais (R3/C1):
 * - [runReader] implementa o contrato EXPLÍCITO da fonte: erro/EOF/zero
 *   contínuo/leitura ímpar — nada de ignorar bytes nem de loop infinito; a
 *   fonte é fechada no finally (idempotente).
 * - [stopAndAwait] drena com deadline MONOTÔNICO; se o timeout estourar, o
 *   estado pendente fica VISÍVEL via [onPending] e a espera CONTINUA até o
 *   worker terminal — handle/controles só são liberados depois disso.
 * - O spool preserva o PCM aceito; os contadores fecham (nada some sem número).
 */
class LiveTranscriptionCoordinator<T>(
    spoolDir: File,
    val chunkSamples: Int,
    private val processChunk: (LivePcmPipeline.Chunk) -> T,
    private val silenceCheck: (T?) -> Boolean = { it == null },
    /** R4/C1: classificador TIPADO de falha — só ele marca failedSamples; um
     * erro de transcrição NÃO é silêncio nem texto. */
    private val failureCheck: (T?) -> Boolean = { false },
    private val deliver: (LivePcmPipeline.Chunk, T?, Boolean) -> Unit = { _, _, _ -> },
    private val terminal: (Boolean) -> Unit = {},
    maxSpoolBytes: Long = 256L * 1024 * 1024,
    maxConsecutiveZeroReads: Int = 4000,
    readBufferBytes: Int = 8192,
    /** R6/D2: contexto anterior (janela deslizante) — repassado ao pipeline. */
    val contextSamples: Int = 0,
    /** R7/E3: planejador de janelas (VAD segmentando intervalos) — null = blocos
     * fixos de [chunkSamples]. */
    planner: LiveWindowPlanner? = null,
) {

    private val bufferBytes = readBufferBytes.coerceAtLeast(2)

    val pipeline: LivePcmPipeline<T> = LivePcmPipeline(
        chunkSamples = chunkSamples,
        worker = object : LivePcmPipeline.Worker<T> {
            override fun process(chunk: LivePcmPipeline.Chunk): T = processChunk(chunk)
            override fun isSilence(result: T?): Boolean = silenceCheck(result)
            override fun isFailure(result: T?): Boolean = failureCheck(result)
        },
        spoolDir = spoolDir,
        maxSpoolBytes = maxSpoolBytes,
        maxConsecutiveZeroReads = maxConsecutiveZeroReads,
        contextSamples = contextSamples,
        planner = planner,
        listener = object : LivePcmPipeline.Listener<T> {
            override fun onChunkProcessed(chunk: LivePcmPipeline.Chunk, result: T?, ok: Boolean) =
                deliver(chunk, result, ok)
            override fun onFinished(cancelled: Boolean) = terminal(cancelled)
        },
    )

    fun start() = pipeline.start()

    fun cancel() = pipeline.cancel()

    fun close() = pipeline.close()

    fun isFinished(): Boolean = pipeline.isFinished()

    fun state(): LivePcmPipeline.State = pipeline.state()

    fun counters(): LivePcmCounters = pipeline.counters()

    /** R7/E3 — estatística do plano (VAD) para terminal/evidência. */
    fun planStats(): LivePlanStats? = pipeline.planStats()

    /** Motivo do término do leitor — sempre explícito, nunca "acabou sem dizer". */
    data class ReaderOutcome(
        val reason: String,
        val samplesRead: Long,
        val oddBytesDropped: Long,
        /** R4/C2: o descarte de ímpar foi o FRAGMENTO final (EOF/parada), não
         * um byte solto no meio — o motivo fica registrado. */
        val fragmentDropped: Boolean = false,
    )

    /**
     * Laço leitor: lê da fonte e alimenta o spool. NÃO espera inferência
     * ([LivePcmPipeline.feed] só copia/grava; se o pipeline parou de aceitar,
     * devolve 0 e o leitor encerra dizendo o motivo). Fecha a fonte no finally.
     *
     * R4/C2: o byte ímpar NÃO é descartado na hora — ele é PRESERVADO e
     * concatenado com a próxima leitura (duas leituras ímpares seguidas formam
     * amostras completas). Só no fim (EOF/parada/falha) um fragmento pendente é
     * descartado, contado em oddBytesDropped (perda EXPLÍCITA) e marcado em
     * [ReaderOutcome.fragmentDropped].
     */
    fun runReader(source: LivePcmSource, isActive: () -> Boolean): ReaderOutcome {
        val buffer = ByteArray(bufferBytes)
        val merged = ByteArray(bufferBytes + 1)
        var zeroRun = 0
        var samplesRead = 0L
        var odd = 0L
        var carried = -1
        var fragmentDropped = false

        fun noteFragmentDropped() {
            if (carried >= 0) {
                carried = -1
                odd += 1
                fragmentDropped = true
                pipeline.noteOddBytesDropped(1)
            }
        }

        try {
            while (isActive() && pipeline.isAccepting()) {
                val read = try {
                    source.read(buffer)
                } catch (t: Throwable) {
                    noteFragmentDropped()
                    pipeline.markSourceFailure("exceção na fonte: ${t.message ?: t.javaClass.simpleName}")
                    pipeline.requestStop()
                    return ReaderOutcome("source-error", samplesRead, odd, fragmentDropped)
                }
                when {
                    read < 0 -> {
                        noteFragmentDropped()
                        pipeline.markSourceEof()
                        pipeline.requestStop()
                        return ReaderOutcome("eof", samplesRead, odd, fragmentDropped)
                    }
                    read == 0 -> {
                        zeroRun++
                        if (zeroRun == 1) Thread.yield()
                        if (zeroRun >= pipeline.maxConsecutiveZeroReads) {
                            noteFragmentDropped()
                            pipeline.markSourceStalled()
                            pipeline.markSourceFailure("fonte estagnada: $zeroRun leituras consecutivas devolvendo 0")
                            pipeline.requestStop()
                            return ReaderOutcome("stalled", samplesRead, odd, fragmentDropped)
                        }
                    }
                    else -> {
                        zeroRun = 0
                        val total: Int
                        val data: ByteArray
                        if (carried >= 0) {
                            merged[0] = carried.toByte()
                            System.arraycopy(buffer, 0, merged, 1, read)
                            total = read + 1
                            data = merged
                        } else {
                            total = read
                            data = buffer
                        }
                        samplesRead += (total / 2).toLong()
                        if (total % 2 != 0) {
                            carried = data[total - 1].toInt() and 0xFF
                        } else {
                            carried = -1
                        }
                        val even = total - (total % 2)
                        if (even > 0 && pipeline.feed(data, even) == 0) {
                            noteFragmentDropped()
                            return ReaderOutcome("pipeline-rejected", samplesRead, odd, fragmentDropped)
                        }
                        // R8/D — clock de CHEGADA REAL do PCM aceito (lab; no
                        // APK normal é no-op): chegada de cada intervalo do
                        // fixture no mesmo relógio monotônico dos demais clocks.
                        if (even > 0 && WhisperLabSeams.feedClockEnabled()) {
                            WhisperLabSeams.event(
                                "clock_feed",
                                "{\"samples\":$samplesRead,\"bytes\":$even}"
                            )
                        }
                    }
                }
            }
            noteFragmentDropped()
            return ReaderOutcome("stopped", samplesRead, odd, fragmentDropped)
        } finally {
            try { source.close() } catch (_: Throwable) {}
        }
    }

    /**
     * Stop normal + espera o worker TERMINAL (deadline monotônico). Se
     * [stopTimeoutMs] estourar, [onPending] é chamado (estado pendente VISÍVEL)
     * e a espera CONTINUA — este método não retorna antes do terminal, e nada
     * é liberado antes dele. Devolve true (terminal alcançado).
     */
    fun stopAndAwait(stopTimeoutMs: Long, pollMs: Long = 200L, onPending: ((Long) -> Unit)? = null): Boolean {
        pipeline.requestStop()
        val start = System.nanoTime()
        var warned = false
        while (!pipeline.awaitFinished(pollMs)) {
            val elapsedMs = (System.nanoTime() - start) / 1_000_000L
            if (!warned && elapsedMs >= stopTimeoutMs) {
                warned = true
                onPending?.invoke(elapsedMs)
            }
        }
        return true
    }
}

/** Adaptador AudioRecord -> [LivePcmSource] (rota de produção). */
class AudioRecordPcmSource(private val recorder: AudioRecord) : LivePcmSource {
    override fun read(buffer: ByteArray): Int = recorder.read(buffer, 0, buffer.size)

    override fun close() {
        try { recorder.stop() } catch (_: Throwable) {}
    }
}
