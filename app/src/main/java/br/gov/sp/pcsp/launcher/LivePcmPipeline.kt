package br.gov.sp.pcsp.launcher

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * R3/C1 — coordenador PURO (sem Android) do caminho ao vivo:
 * leitor (thread de captura) -> SPOOL TEMPORÁRIO SEQUENCIAL (um arquivo por
 * chunk; índice leve em RAM) -> worker de inferência, em ordem.
 *
 * Por que spool em vez de "descartar o mais antigo": a política antiga perdia
 * PCM ACEITO quando o backlog passava da fila. Agora TODO aceito é preservado
 * em disco (spoolDir) até ser processado; o leitor NUNCA espera a inferência —
 * se a inferência atrasa, o dado fica no spool, não some. Os limites são
 * explícitos e CONTADOS: [maxSpoolBytes] (armazenamento; ao estourar/IO falhar
 * o pipeline para de aceitar, marca a falha e QUANTIFICA o perdido em
 * [LivePcmCounters.lostSamples]).
 *
 * Contrato de contabilidade (amostras PCM16; nada some sem contador):
 * - `producedSamples` = amostras apresentadas a [feed] enquanto aceitando.
 * - `capturedSamples` ("aceitos") = amostras aceitas (RAM+spool) — produzido =
 *   capturado + perdido, sempre.
 * - `processedSamples` = capturado que o worker terminou (subdividido em
 *   ok/silêncio/falha; chunk com exceção do worker é FALHA de tentativa, não
 *   áudio transcrito).
 * - `cancelledSamples` = aceito descartado por [cancel] (pendente no spool/RAM).
 * - `lostSamples` = produzido que NÃO entrou no spool (falha de
 *   armazenamento/limite) — quantificado, nunca silencioso.
 * - Terminal: produzido == capturado + perdido e capturado == processado + cancelado.
 *
 * Contrato da fonte ([LivePcmSource.read]): >0 bytes lidos; 0 = nada agora
 * (limitado por [maxConsecutiveZeroReads] — sem loop infinito); <0 = fim/erro
 * (o leitor encerra e o pipeline drena e FINALIZA sozinho). Leitura ímpar: o
 * prefixo par É processado e o byte ímpar é contado
 * ([LivePcmCounters.oddBytesDropped]) — nunca ignorado em silêncio.
 *
 * Listener: os callbacks são entregues DENTRO da seção crítica em relação a
 * [cancel] (teste-e-chamada atômico) e exceções do listener NÃO derrubam o
 * worker ([LivePcmCounters.listenerErrors]); o terminal é garantido mesmo com
 * listener/fonte/worker lançando.
 */
class LivePcmPipeline<T>(
    private val chunkSamples: Int,
    private val worker: Worker<T>,
    /** Diretório-base do spool (cacheDir em produção; TMP nos testes). */
    private val spoolDir: File,
    private val maxSpoolBytes: Long = 256L * 1024 * 1024,
    /** Teto de leituras consecutivas devolvendo 0 antes de declarar estagnação. */
    val maxConsecutiveZeroReads: Int = 4000,
    private val listener: Listener<T>? = null,
    /**
     * R6/D2 (+R8/C) — CONTEXTO ANTERIOR LIMITADO (janela deslizante). 0
     * (default) = modo clássico: cada chunk é um bloco fixo não sobreposto (só
     * amostras únicas). >0 = cada chunk é UMA JANELA de decodificação
     * [contexto][único]: o prefixo é ÁUDIO REAL de até [contextSamples]
     * amostras imediatamente anteriores no fluxo aceito (com planejador,
     * inclusive lacunas puladas — nunca um tail distante rotulado de
     * adjacente), e só o trecho único tem dono exclusivo
     * ([Chunk.uniqueStartSample]/[Chunk.uniqueEndSample]).
     * A contabilidade não infla: `capturedSamples` continua contando SÓ o
     * áudio único aceito; o contexto repetido entra em
     * [LivePcmCounters.contextualSamples] e o volume de decode em
     * [LivePcmCounters.enqueuedSamples].
     */
    private val contextSamples: Int = 0,
    /**
     * R7/E3 — PLANEJADOR de janelas (VAD segmentando intervalos). null = modo
     * clássico (blocos fixos em [chunkSamples]). Com planejador, o corte é
     * decisão dele: o que não vira janela é contabilizado em
     * [LivePcmCounters.silenceSkippedSamples] (nunca descarte silencioso).
     */
    private val planner: LiveWindowPlanner? = null,
) {

    init {
        require(chunkSamples > 0) { "chunkSamples deve ser > 0" }
        require(contextSamples >= 0) { "contextSamples deve ser >= 0" }
    }

    /** Processador de chunks — roda SEMPRE na thread do worker, em ordem. */
    interface Worker<T> {
        fun process(chunk: Chunk): T

        /** Classificação do resultado: silêncio é processado, não falha. */
        fun isSilence(result: T?): Boolean = result == null

        /** R4/C1: resultado TIPADO de FALHA (erro de transcrição/IO de chunk) —
         * conta em failedSamples e NÃO é confundido com silêncio nem sucesso
         * (exceção do worker continua contando como falha também). */
        fun isFailure(result: T?): Boolean = false
    }

    interface Listener<T> {
        /** Resultado de um chunk processado (suprimido após cancel; o cancel
         * entre o processamento e a entrega é fechado pela seção crítica). */
        fun onChunkProcessed(chunk: Chunk, result: T?, ok: Boolean)

        /** Worker terminou (uma vez; depois de drenar no stop ou cancelar). */
        fun onFinished(cancelled: Boolean)
    }

    class Chunk internal constructor(
        val index: Int,
        val pcm: ByteArray,
        /** R6/D2 (+R8/C) — intervalo ÚNICO desta janela (amostras absolutas do
         * fluxo). Só este intervalo é propriedade NOVA; o prefixo
         * [0, contextSamplesInChunk) do PCM é ÁUDIO REAL imediatamente anterior
         * no fluxo ACEITO (inclusive lacunas puladas pelo planejador): pode
         * repetir janelas anteriores ou zeros contados como silêncio — nunca um
         * tail antigo de janela distante colado como se fosse adjacente. */
        val uniqueStartSample: Long = 0L,
        val uniqueEndSample: Long = 0L,
    ) {
        /** Amostras DECODIFICADAS da janela (contexto + único). */
        val sampleCount: Int get() = pcm.size / 2

        /** Amostras ÚNICAS (dono exclusivo desta janela). */
        val uniqueSampleCount: Int get() = (uniqueEndSample - uniqueStartSample).toInt()

        /** Amostras de CONTEXTO repetido no prefixo do PCM. */
        val contextSamplesInChunk: Int get() = sampleCount - uniqueSampleCount
    }

    enum class State { RUNNING, STOPPING, CANCELLED, FINISHED }

    private val lock = Object()
    private val queue = ArrayDeque<ChunkRef>()
    private val acc = ByteArray(if (planner == null) chunkSamples * 2 else 0)
    private var accSize = 0
    private var nextChunkIndex = 0
    // R6/D2 — janela deslizante: tail do fluxo ACEITO (contexto anterior das
    // próximas janelas) e contadores separados de único × contexto.
    private val history = ByteArray(contextSamples * 2)
    private var historySize = 0
    // R8/C — caminho por PLANEJADOR: anel de TODOS os bytes aceitos (inclusive
    // o que o planejador pulou) para o prefixo das janelas ser áudio REAL
    // imediatamente anterior — nunca tail antigo de janela distante colado
    // quando há lacuna. Poda: nada antes de [bufStart−contextSamples] pode virar
    // prefixo (o buffer do planejador só avança), então a RAM fica limitada a
    // contexto + pendente.
    private var plannerHistory = ByteArray(if (planner != null && contextSamples > 0) contextSamples * 2 else 0)
    private var plannerHistoryBytes = 0
    private var plannerHistoryStart = 0L
    private var plannerAcceptedSamples = 0L
    private var uniqueStoredSamples = 0L
    private var contextualStoredSamples = 0L
    /** R7/E3 — silêncio NÃO decodificado decidido pelo planejador (contado). */
    private var silenceSkippedSamples = 0L

    private var producedSamples = 0L
    private var capturedSamples = 0L
    private var enqueuedSamples = 0L
    private var processedSamples = 0L
    private var okSamples = 0L
    private var silenceSamples = 0L
    private var failedSamples = 0L
    private var cancelledSamples = 0L
    private var lostSamples = 0L
    private var lateLostSamples = 0L
    private var oddBytesDropped = 0L
    private var listenerErrors = 0
    private var storageFailure: String? = null
    private var sourceFailure: String? = null
    private var sourceEof = false
    private var sourceStalled = false
    private var accepting = true
    private var indexHighWatermark = 0
    private var spoolBytesLive = 0L
    private var spoolBytesPeak = 0L

    private var workerStarted = false
    private var workerShouldExit = false
    private var stopping = false
    private var cancelled = false
    private var finished = false
    private var lastWorkerError: Throwable? = null
    @Volatile private var workerThread: Thread? = null
    @Volatile private var runDir: File? = null

    private class ChunkRef(
        val index: Int,
        val file: File,
        /** Bytes TOTAIS da janela no spool (contexto + único). */
        val bytes: Int,
        /** Bytes do trecho ÚNICO (dono da janela). */
        val uniqueBytes: Int,
        val uniqueStartSample: Long,
    ) {
        val sampleCount: Int get() = bytes / 2
        val uniqueSampleCount: Int get() = uniqueBytes / 2
    }

    fun start() {
        synchronized(lock) {
            check(!workerStarted) { "LivePcmPipeline já iniciado" }
            workerStarted = true
        }
        ensureRunDir()
        workerThread = Thread({ workerLoop() }, "LivePcmWorker").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Alimenta o pipeline com PCM16 (bytes pares). Devolve os BYTES aceitos
     * (0 quando parou de aceitar — stop/cancel/falha de armazenamento; o leitor
     * deve encerrar em seguida).
     */
    fun feed(bytes: ByteArray, length: Int): Int {
        require(length % 2 == 0) { "PCM16 exige um número par de bytes" }
        require(length <= bytes.size) { "length além do buffer" }
        synchronized(lock) {
            if (!accepting || stopping || cancelled) return 0
            val totalSamples = (length / 2).toLong()
            producedSamples += totalSamples
            capturedSamples += totalSamples
            if (planner != null) {
                return feedPlannedLocked(bytes, 0, length, totalSamples)
            }
            var offset = 0
            while (offset < length) {
                val space = acc.size - accSize
                val take = minOf(space, length - offset)
                System.arraycopy(bytes, offset, acc, accSize, take)
                accSize += take
                offset += take
                if (accSize == acc.size) {
                    val stored = storeWindowLocked(acc, accSize, uniqueStoredSamples)
                    if (!stored) {
                        // Falha de armazenamento: para de aceitar; o chunk que
                        // falhou + o resto desta chamada viram PERDIDO contado
                        // (nunca silencioso). Devolve os bytes ACEITOS.
                        val acceptedBytes = offset - acc.size
                        val acceptedInThisCall = acceptedBytes.toLong() / 2
                        val remaining = totalSamples - acceptedInThisCall
                        capturedSamples -= remaining
                        lostSamples += remaining
                        accepting = false
                        accSize = 0
                        lock.notifyAll()
                        return acceptedBytes
                    }
                    accSize = 0
                }
            }
            lock.notifyAll()
            return length
        }
    }

    /**
     * R7/E3 — caminho por PLANEJADOR (VAD segmentando intervalos). O corte é
     * decisão do planejador; cada janela emitida vira um chunk do spool. Falha
     * de armazenamento vira PERDA CONTADA do que ainda tem sinal (janela +
     * pendente) — nunca pendência silenciosa. Devolve os bytes ACEITOS.
     */
    private fun feedPlannedLocked(bytes: ByteArray, offset: Int, length: Int, totalSamples: Long): Int {
        // R8/C: o anel de contexto recebe TODO o fluxo aceito ANTES das
        // emissões — o prefixo de cada janela tem de ser áudio real precedente
        // (inclusive o que o planejador pulou entre janelas).
        appendPlannerHistoryLocked(bytes, offset, length)
        plannerAcceptedSamples += totalSamples
        val emissions = try {
            planner!!.accept(bytes, offset, length)
        } catch (t: Throwable) {
            storageFailure = storageFailure ?: "planejador falhou: ${t.message ?: t.javaClass.simpleName}"
            capturedSamples -= totalSamples
            lostSamples += totalSamples
            accepting = false
            lock.notifyAll()
            return 0
        }
        silenceSkippedSamples = planner!!.skippedSilenceSamples
        val lost = storePlannedLocked(emissions)
        trimPlannerHistoryLocked()
        if (lost > 0) {
            capturedSamples -= lost
            lostSamples += lost
            accepting = false
            val acceptedSamples = totalSamples - lost
            lock.notifyAll()
            return (acceptedSamples.coerceAtLeast(0L) * 2L).toInt()
        }
        lock.notifyAll()
        return length
    }

    /** Grava as janelas do planejador; devolve as AMOSTRAS sem destino (spool
     * recusou) — incluindo o que ficou pendente na RAM do planejador. */
    private fun storePlannedLocked(emissions: List<PlannedWindow>): Long {
        var lost = 0L
        var failed = false
        for (w in emissions) {
            if (failed) {
                lost += w.sampleCount.toLong()
                continue
            }
            if (!storePlannedWindowLocked(w)) {
                failed = true
                lost += w.sampleCount.toLong()
            }
        }
        if (failed && planner != null) {
            lost += planner!!.pendingSamples
            planner!!.abandonPending()
        }
        return lost
    }

    /** Byte ímpar lido da fonte: contado; o prefixo par do read é processado. */
    fun noteOddBytesDropped(count: Long) {
        if (count <= 0L) return
        synchronized(lock) { oddBytesDropped += count }
    }

    fun markSourceEof() = synchronized(lock) { sourceEof = true }

    fun markSourceFailure(message: String) = synchronized(lock) {
        sourceFailure = message
        if (!stopping && !cancelled) {
            // Terminal por FALHA DA FONTE (rodada 3, gap sourcefail/device): o
            // trecho parcial ACEITO ainda em RAM tem de virar chunk final —
            // mesma regra do stop normal ([requestStop] faz esse flush; aqui o
            // estado de stop era marcado ANTES e o guard de requestStop pulava
            // o flush => amostras aceitas ficavam pendentes sem rosto no
            // terminal, isBalanced()=false). Se o spool recusar, vira PERDIDO
            // contado (flushAccLocked), nunca pendente silencioso.
            flushAccLocked()
        }
        accepting = false
        stopping = true
        workerShouldExit = true
        lock.notifyAll()
    }

    fun markSourceStalled() = synchronized(lock) { sourceStalled = true }

    /**
     * Stop NORMAL: para de aceitar, faz flush do trecho final parcial (ele
     * vira um chunk) e o worker termina quando drenar tudo. Só então [State.FINISHED].
     */
    fun requestStop() {
        synchronized(lock) {
            if (stopping || cancelled) return
            stopping = true
            accepting = false
            flushAccLocked()
            workerShouldExit = true
            lock.notifyAll()
        }
    }

    /**
     * CANCEL: invalida callbacks (nem o resultado do chunk em voo é entregue) e
     * os pendentes (fila + trecho em RAM) contam como cancelados. Idempotente;
     * pode ser chamado antes de [start] (o worker sai imediatamente).
     */
    fun cancel() {
        synchronized(lock) {
            if (cancelled) return
            cancelled = true
            stopping = true
            accepting = false
            if (planner != null) {
                // R7/E3: o plano perdeu o destino — o pendente conta como
                // CANCELADO (o silêncio já contabilizado permanece contado).
                val n = planner!!.pendingSamples
                planner!!.abandonPending()
                cancelledSamples += n
                silenceSkippedSamples = planner!!.skippedSilenceSamples
            } else if (accSize > 0) {
                val n = (accSize / 2).toLong()
                cancelledSamples += n
                accSize = 0
            }
            while (queue.isNotEmpty()) {
                val ref = queue.removeFirst()
                // R6/D2: cancel conta SOMENTE amostras únicas pendentes (o
                // contexto repetido de uma janela pendente não é áudio novo).
                cancelledSamples += ref.uniqueSampleCount
                spoolBytesLive -= ref.bytes
                try { ref.file.delete() } catch (_: Throwable) {}
            }
            workerShouldExit = true
            lock.notifyAll()
        }
    }

    /** Cancel + limpeza do spool. Idempotente, inclusive antes de [start]. */
    fun close() {
        cancel()
        try { runDir?.deleteRecursively() } catch (_: Throwable) {}
    }

    /** Espera a finalização (worker terminou). false no timeout; deadline monotônico. */
    fun awaitFinished(timeoutMs: Long): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        synchronized(lock) {
            while (!finished) {
                val remainingNs = deadline - System.nanoTime()
                if (remainingNs <= 0) return false
                try {
                    lock.wait(maxOf(1L, remainingNs / 1_000_000L))
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
            return true
        }
    }

    fun state(): State = synchronized(lock) {
        when {
            finished -> State.FINISHED
            cancelled -> State.CANCELLED
            stopping -> State.STOPPING
            else -> State.RUNNING
        }
    }

    fun isFinished(): Boolean = synchronized(lock) { finished }

    /** A thread do worker AINDA está viva? (observação do coordenador inteiro) */
    fun workerAlive(): Boolean = workerThread?.isAlive == true

    fun isAccepting(): Boolean = synchronized(lock) { accepting && !stopping && !cancelled }

    fun lastWorkerError(): Throwable? = synchronized(lock) { lastWorkerError }

    fun sourceFailureMessage(): String? = synchronized(lock) { sourceFailure }

    fun storageFailureMessage(): String? = synchronized(lock) { storageFailure }

    fun counters(): LivePcmCounters = synchronized(lock) {
        LivePcmCounters(
            producedSamples = producedSamples,
            capturedSamples = capturedSamples,
            enqueuedSamples = enqueuedSamples,
            processedSamples = processedSamples,
            okSamples = okSamples,
            silenceSamples = silenceSamples,
            failedSamples = failedSamples,
            cancelledSamples = cancelledSamples,
            lostSamples = lostSamples,
            lateLostSamples = lateLostSamples,
            oddBytesDropped = oddBytesDropped,
            listenerErrors = listenerErrors,
            expectedSamples = producedSamples,
            spoolBytesPeak = spoolBytesPeak,
            indexHighWatermark = indexHighWatermark,
            sourceEof = sourceEof,
            sourceStalled = sourceStalled,
            storageFailure = storageFailure,
            sourceFailure = sourceFailure,
            workerError = lastWorkerError?.let { it.message ?: it.javaClass.simpleName },
            uniqueSamples = uniqueStoredSamples,
            contextualSamples = contextualStoredSamples,
            silenceSkippedSamples = silenceSkippedSamples,
        )
    }

    /** R7/E3 — estatística do PLANO (VAD) para o terminal/evidência; null no
     * modo clássico (blocos fixos). */
    fun planStats(): LivePlanStats? = synchronized(lock) { planner?.stats() }

    // ------------------------------------------------------------------ internos

    private fun ensureRunDir(): File {
        runDir?.let { return it }
        synchronized(lock) {
            runDir?.let { return it }
            val dir = File(spoolDir, "live-${System.nanoTime()}")
            dir.mkdirs()
            runDir = dir
            return dir
        }
    }

    /** Grava UMA janela no spool: [contexto pendente][único em pcm[0,uniqueLen)].
     * Devolve false em falha/limite — nunca lança. O histórico é atualizado
     * SÓ em sucesso (o contexto da próxima janela é o tail do fluxo aceito). */
    private fun storeWindowLocked(pcm: ByteArray, uniqueLen: Int, startSample: Long): Boolean {
        val contextLen = if (contextSamples > 0) minOf(historySize, contextSamples * 2) else 0
        return storeWindowCoreLocked(
            prefixSrc = history,
            prefixOff = historySize - contextLen,
            contextLen = contextLen,
            pcm = pcm,
            uniqueLen = uniqueLen,
            startSample = startSample,
            updateChain = true,
        )
    }

    /**
     * R8/C — janela do caminho por PLANEJADOR: o prefixo de contexto é o ÁUDIO
     * REAL imediatamente anterior no fluxo aceito
     * ([startSample−contextSamples, startSample) do anel), encurtado só no
     * começo do fluxo/disponibilidade — NUNCA um tail de janela distante
     * colado como se fosse adjacente quando o planejador pulou uma lacuna.
     */
    private fun storePlannedWindowLocked(w: PlannedWindow): Boolean {
        var contextLen = 0
        var prefixOff = 0
        if (contextSamples > 0) {
            val endAbs = w.startSample
            val historyEnd = plannerHistoryStart + plannerHistoryBytes / 2
            val fromAbs = maxOf(plannerHistoryStart, endAbs - contextSamples)
            val toAbs = minOf(endAbs, historyEnd)
            if (toAbs > fromAbs) {
                contextLen = ((toAbs - fromAbs) * 2).toInt()
                prefixOff = ((fromAbs - plannerHistoryStart) * 2).toInt()
            }
        }
        return storeWindowCoreLocked(
            prefixSrc = plannerHistory,
            prefixOff = prefixOff,
            contextLen = contextLen,
            pcm = w.pcm,
            uniqueLen = w.pcm.size,
            startSample = w.startSample,
            updateChain = false,
        )
    }

    private fun storeWindowCoreLocked(
        prefixSrc: ByteArray,
        prefixOff: Int,
        contextLen: Int,
        pcm: ByteArray,
        uniqueLen: Int,
        startSample: Long,
        updateChain: Boolean,
    ): Boolean {
        val windowLen = contextLen + uniqueLen
        if (spoolBytesLive + windowLen > maxSpoolBytes) {
            storageFailure = storageFailure ?: "spool cheio (${spoolBytesLive + windowLen} > $maxSpoolBytes bytes)"
            return false
        }
        val dir = ensureRunDir()
        val file = File(dir, "chunk-${nextChunkIndex}.pcm")
        return try {
            FileOutputStream(file).use { out ->
                if (contextLen > 0) out.write(prefixSrc, prefixOff, contextLen)
                out.write(pcm, 0, uniqueLen)
            }
            queue.addLast(ChunkRef(nextChunkIndex, file, windowLen, uniqueLen, startSample))
            // R8/D — clock de ENFILEIRAMENTO (lab; no APK normal é no-op): a
            // chegada ao worker é medida contra este instante (backlog/fila).
            if (WhisperLabSeams.feedClockEnabled()) {
                WhisperLabSeams.event(
                    "clock_enqueue",
                    "{\"chunk\":$nextChunkIndex,\"unique_start\":$startSample,\"bytes\":$windowLen," +
                        "\"queue\":${queue.size},\"spool_bytes\":${spoolBytesLive + windowLen}}"
                )
            }
            nextChunkIndex++
            uniqueStoredSamples += uniqueLen / 2
            contextualStoredSamples += contextLen / 2
            enqueuedSamples += windowLen / 2
            if (updateChain && contextSamples > 0) {
                // histórico = tail do fluxo ACEITO (contexto+único da janela
                // recém-armazenada) — usado como contexto da próxima janela.
                val keep = minOf(contextLen + uniqueLen, contextSamples * 2)
                if (uniqueLen >= keep) {
                    System.arraycopy(pcm, uniqueLen - keep, history, 0, keep)
                } else {
                    val fromHist = keep - uniqueLen
                    System.arraycopy(history, historySize - fromHist, history, 0, fromHist)
                    System.arraycopy(pcm, 0, history, fromHist, uniqueLen)
                }
                historySize = keep
            }
            spoolBytesLive += windowLen
            if (spoolBytesLive > spoolBytesPeak) spoolBytesPeak = spoolBytesLive
            if (queue.size > indexHighWatermark) indexHighWatermark = queue.size
            lock.notifyAll()
            true
        } catch (t: Throwable) {
            storageFailure = storageFailure ?: "falha ao gravar spool: ${t.message}"
            try { file.delete() } catch (_: Throwable) {}
            false
        }
    }

    /** R8/C — anexa bytes ACEITOS ao anel de contexto (antes das emissões). */
    private fun appendPlannerHistoryLocked(bytes: ByteArray, offset: Int, length: Int) {
        if (length <= 0) return
        val need = plannerHistoryBytes + length
        if (need > plannerHistory.size) {
            var cap = if (plannerHistory.size == 0) 1 shl 16 else plannerHistory.size
            while (cap < need) cap = cap shl 1
            plannerHistory = plannerHistory.copyOf(cap)
        }
        System.arraycopy(bytes, offset, plannerHistory, plannerHistoryBytes, length)
        plannerHistoryBytes += length
    }

    /** R8/C — poda o anel: nada antes de [bufStart−contextSamples] do fluxo
     * aceito pode virar prefixo de janela futura (o buffer do planejador só
     * avança), então o excedente é solto — RAM limitada a contexto + pendente. */
    private fun trimPlannerHistoryLocked() {
        val p = planner ?: return
        if (plannerHistoryBytes <= 0) return
        val bufStartAbs = (plannerAcceptedSamples - p.pendingSamples).coerceAtLeast(0L)
        val keepFrom = (bufStartAbs - contextSamples).coerceAtLeast(0L)
        if (keepFrom > plannerHistoryStart) {
            val dropBytes = minOf(((keepFrom - plannerHistoryStart) * 2).toInt(), plannerHistoryBytes)
            if (dropBytes > 0) {
                System.arraycopy(plannerHistory, dropBytes, plannerHistory, 0, plannerHistoryBytes - dropBytes)
                plannerHistoryBytes -= dropBytes
                plannerHistoryStart += dropBytes / 2
            }
        }
    }

    private fun flushAccLocked() {
        // R7/E3: com planejador, o flush é do PLANEJADOR (cauda com sinal vira
        // janela; o resto é contabilizado) — o que não couber no spool é perda
        // CONTADA, nunca pendência silenciosa.
        if (planner != null) {
            val emissions = try {
                planner!!.flush()
            } catch (t: Throwable) {
                storageFailure = storageFailure ?: "planejador falhou no flush: ${t.message ?: t.javaClass.simpleName}"
                emptyList()
            }
            silenceSkippedSamples = planner!!.skippedSilenceSamples
            val lost = storePlannedLocked(emissions)
            if (lost > 0) {
                capturedSamples -= lost
                lostSamples += lost
            }
            return
        }
        if (accSize > 0) {
            val stored = storeWindowLocked(acc, accSize, uniqueStoredSamples)
            if (!stored) {
                val n = (accSize / 2).toLong()
                capturedSamples -= n
                lostSamples += n
            }
            accSize = 0
        }
    }

    private fun workerLoop() {
        while (true) {
            var ref: ChunkRef? = null
            synchronized(lock) {
                while (queue.isEmpty() && !workerShouldExit) {
                    try {
                        lock.wait()
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        workerShouldExit = true
                    }
                }
                if (queue.isNotEmpty()) ref = queue.removeFirst()
            }
            if (ref == null) break

            val taken = ref
            val chunk: Chunk? = try {
                val bytes = ByteArray(taken.bytes)
                RandomAccessFile(taken.file, "r").use { raf -> raf.readFully(bytes) }
                Chunk(taken.index, bytes, taken.uniqueStartSample, taken.uniqueStartSample + taken.uniqueSampleCount)
            } catch (t: Throwable) {
                synchronized(lock) {
                    lastWorkerError = t
                    // Aceito, mas o spool ficou ilegível: perda PÓS-aceite do
                    // trecho ÚNICO (o contexto repetido não é áudio novo), contada
                    // separadamente (o balanço continua fechando).
                    lateLostSamples += taken.uniqueSampleCount.toLong()
                    spoolBytesLive -= taken.bytes
                }
                try { taken.file.delete() } catch (_: Throwable) {}
                null
            }

            if (chunk != null) {
                var result: T? = null
                var ok = true
                try {
                    result = worker.process(chunk)
                } catch (t: Throwable) {
                    ok = false
                    synchronized(lock) { lastWorkerError = t }
                }
                try { taken.file.delete() } catch (_: Throwable) {}
                synchronized(lock) {
                    spoolBytesLive -= taken.bytes
                    // R6/D2: a contabilidade de PROCESSADO é por amostra ÚNICA
                    // (o contexto repetido não infla ok/silêncio/falha).
                    processedSamples += chunk.uniqueSampleCount.toLong()
                    if (!ok) {
                        failedSamples += chunk.uniqueSampleCount.toLong()
                    } else if (worker.isFailure(result)) {
                        failedSamples += chunk.uniqueSampleCount.toLong()
                    } else if (worker.isSilence(result)) {
                        silenceSamples += chunk.uniqueSampleCount.toLong()
                    } else {
                        okSamples += chunk.uniqueSampleCount.toLong()
                    }
                    // Entrega TESTE-E-CHAMADA atômica em relação a cancel();
                    // exceção do listener não pode derrubar o worker.
                    if (!cancelled && listener != null) {
                        try {
                            listener.onChunkProcessed(chunk, result, ok)
                        } catch (_: Throwable) {
                            listenerErrors++
                        }
                    }
                    lock.notifyAll()
                }
            }
        }
        val wasCancelled: Boolean
        // R11/G-fix (corrida do TERMINAL) — o callback do listener é entregue
        // ANTES de publicar `finished`. Contrato agora garantido: quem observa o
        // terminal ([awaitFinished]/[isFinished]/[state] == FINISHED) já recebeu
        // [Listener.onFinished]. Antes, `finished = true` era publicado primeiro
        // e o callback vinha depois: quem espera o terminal (o `stopAndAwait` da
        // rota de produção, e o retrato terminal de counters/export logo em
        // seguida) podia ver o pipeline "terminado" ANTES de o callback publicar
        // o último texto/reparo deferido (`WhisperActivity` terminal = finalize
        // do committer) — o retrato terminal saía cedo. O callback corre FORA do
        // lock (não há reentrância: nenhum listener do produto chama de volta
        // métodos sincronizados do pipeline).
        synchronized(lock) { wasCancelled = cancelled }
        try {
            listener?.onFinished(wasCancelled)
        } catch (_: Throwable) {
            synchronized(lock) { listenerErrors++ }
        }
        synchronized(lock) {
            finished = true
            lock.notifyAll()
        }
    }
}

/**
 * Contadores do caminho ao vivo em AMOSTRAS (PCM16). Invariantes terminais:
 * produzido == capturado + perdido; capturado == processado + cancelado;
 * processado == ok + silêncio + falha. [isBalanced] confere tudo; [summary]
 * imprime inclusive perdidos/cancelados (nunca um descarte invisível).
 */
data class LivePcmCounters(
    val producedSamples: Long,
    val capturedSamples: Long,
    val enqueuedSamples: Long,
    val processedSamples: Long,
    val okSamples: Long,
    val silenceSamples: Long,
    val failedSamples: Long,
    val cancelledSamples: Long,
    val lostSamples: Long,
    /** Aceito que ficou ilegível no spool (perda pós-aceite). */
    val lateLostSamples: Long,
    val oddBytesDropped: Long,
    val listenerErrors: Int,
    val expectedSamples: Long,
    val spoolBytesPeak: Long,
    val indexHighWatermark: Int,
    val sourceEof: Boolean,
    val sourceStalled: Boolean,
    val storageFailure: String?,
    val sourceFailure: String?,
    /** R4/C2: último erro do worker (transcrição/IO) — entra no terminal. */
    val workerError: String? = null,
    /** R6/D2 — amostras ÚNICAS já emitidas ao spool (dono exclusivo; não conta
     * contexto repetido). No terminal sem cancelamento == processado+lateLost;
     * durante o run, o que falta está pendente em RAM. */
    val uniqueSamples: Long = 0L,
    /** R6/D2 — amostras de CONTEXTO repetido emitidas (decode extra, nenhum
     * áudio novo). 0 no modo clássico. */
    val contextualSamples: Long = 0L,
    /** R7/E3 — amostras decididas como SILÊNCIO pelo planejador (VAD): não
     * decodificadas, contabilizadas e mapeadas em intervalos — nunca descarte
     * silencioso. 0 no modo clássico. */
    val silenceSkippedSamples: Long = 0L,
) {
    fun isBalanced(): Boolean =
        producedSamples == capturedSamples + lostSamples &&
            capturedSamples == processedSamples + cancelledSamples + lateLostSamples + silenceSkippedSamples &&
            processedSamples == okSamples + silenceSamples + failedSamples &&
            expectedSamples == producedSamples

    /** Invariantes que valem A QUALQUER momento (durante o run ainda pode haver
     * aceito pendente): produzido == capturado+perdido e subcontadores fecham. */
    fun isConsistent(): Boolean =
        producedSamples == capturedSamples + lostSamples &&
            processedSamples == okSamples + silenceSamples + failedSamples &&
            capturedSamples >= processedSamples + cancelledSamples + lateLostSamples + silenceSkippedSamples &&
            // R6/D2: o volume de decode é exatamente único + contexto repetido.
            (uniqueSamples == 0L || enqueuedSamples == uniqueSamples + contextualSamples)

    /** Sobra aceita ainda não resolvida (fila/em processamento/silêncio pendente). */
    fun pendingSamples(): Long =
        capturedSamples - processedSamples - cancelledSamples - lateLostSamples - silenceSkippedSamples

    fun lossVisible(): Boolean =
        lostSamples > 0 || lateLostSamples > 0 || cancelledSamples > 0 || failedSamples > 0 || sourceStalled ||
            // R4/C2: falha de fonte/storage sem contadores de perda AINDA é
            // interrupção; byte ímpar descartado é perda EXPLÍCITA.
            sourceFailure != null || storageFailure != null || oddBytesDropped > 0

    fun summary(): String =
        "produced=$producedSamples captured=$capturedSamples enqueued=$enqueuedSamples " +
            "unique=$uniqueSamples contextual=$contextualSamples " +
            "processed=$processedSamples ok=$okSamples silence=$silenceSamples failed=$failedSamples " +
            "cancelled=$cancelledSamples lost=$lostSamples lateLost=$lateLostSamples oddBytes=$oddBytesDropped " +
            "silenceSkipped=$silenceSkippedSamples " +
            "listenerErrors=$listenerErrors eof=$sourceEof stalled=$sourceStalled " +
            "spoolPeak=$spoolBytesPeak idxPeak=$indexHighWatermark" +
            (storageFailure?.let { " storageFailure=[$it]" } ?: "") +
            (sourceFailure?.let { " sourceFailure=[$it]" } ?: "")
}

/** Fonte de PCM16 mono — AudioRecord em produção; fake/arquivo nos testes. */
interface LivePcmSource {
    /** Lê até buffer.size bytes; devolve >0 lidos, 0 se nada agora, <0 em fim/erro. */
    fun read(buffer: ByteArray): Int

    /** Encerra a fonte (idempotente; nunca lança). */
    fun close()
}
