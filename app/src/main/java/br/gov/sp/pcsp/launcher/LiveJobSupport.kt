package br.gov.sp.pcsp.launcher

import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/** R4/C1 — snapshot IMUTÁVEL das configurações efetivas capturadas no INÍCIO do
 * job ao vivo. TODOS os campos vão ao JNI em cada chunk (nada fixado em
 * beam=1/bestOf=1/timestamps=false/vad=false). O que não se aplica ao caminho
 * live é apresentado explicitamente em [describe]. */
data class LiveJobSettings(
    val language: String,
    val modelLabel: String,
    val backendLabel: String,
    val beamSize: Int,
    val bestOf: Int,
    val wordTimestamps: Boolean,
    val vadFilter: Boolean,
    val vadModelPath: String,
) {
    /** Parâmetros efetivos, para UI/terminal/relatório (não só log interno). */
    fun describe(): String = buildString {
        append("decode: beam=$beamSize bestOf=$bestOf timestamps=$wordTimestamps vad=$vadFilter")
        if (vadFilter) {
            append(" vadModel=")
            append(if (vadModelPath.isBlank()) "(ausente)" else vadModelPath.substringAfterLast('/'))
        }
        append(" | modelo=$modelLabel backend=$backendLabel idioma=$language")
    }
}

/** R7/E2 — segmento REAL devolvido pelo JNI (callback onSegment) com os tempos
 * da janela decodificada (ms relativos ao áudio do chunk): evidência de
 * INTERVALO para o commit de texto. Tempo de segmento NÃO é tempo exato de
 * palavra (o commit herda o intervalo do segmento, sem espalhar
 * uniformemente). Só é usado quando o chunk não passou por VAD (os tempos do
 * VAD são relativos ao áudio filtrado, não à janela). */
data class LiveSegmentTime(val text: String, val t0Ms: Long, val t1Ms: Long)

/** R4/C1 — resultado TIPADO de um chunk ao vivo (substitui o String? ambíguo em
 * que null significava silêncio E erro E cancelamento ao mesmo tempo). */
sealed class LiveChunkResult {
    /** R7/E2: [segments] = segmentos reais do JNI (vazio quando indisponíveis
     * ou inválidos para a janela); o texto segue sendo a fonte de verdade. */
    data class Success(
        val text: String,
        val segments: List<LiveSegmentTime> = emptyList(),
    ) : LiveChunkResult()

    /** Silêncio LEGÍTIMO: no_speech do nativo ou portão de energia (R7: só
     * zero-digital válido), sempre com o motivo — nunca um erro disfarçado. */
    data class NoSpeech(val reason: String) : LiveChunkResult()

    /** Falha (status error, exceção, chunkWav, status perdido) — conta em
     * failedSamples e apresenta motivo; NÃO vira silêncio. */
    data class Failed(val reason: String) : LiveChunkResult()

    data class Cancelled(val reason: String) : LiveChunkResult()
}

/** R7/A2 — Portão de energia do chunk ao vivo: contrato SÓ-ZERO-DIGITAL.
 *
 * Histórico (por que mudou): até a R6 o portão descartava como "sem fala"
 * qualquer chunk com rms < 20 E pico < 320 (R4). O KDoc da Activity dizia
 * "zero digital", mas o código descartava PCM NÃO nulo — reprovado na revisão
 * da R6: fala fraca real, onset curto cercado de silêncio e transientes abaixo
 * dos limiares eram jogados fora ANTES do modelo/VAD, por limiar de energia.
 *
 * Contrato R7/A2: o portão só pode decidir sem-fala SEM CONSULTAR O MODELO
 * quando TODAS as amostras do chunk (PCM16 VÁLIDO = nº PAR de bytes e ≥ 1
 * amostra) são ZERO. Qualquer amostra não nula — inclusive -32768, inclusive
 * um único sample de amplitude 1 — segue para o modelo/VAD, que é quem decide
 * fala. RMS/pico continuam MEDIDOS, mas só como DIAGNÓSTICO (vão no evento do
 * portão/terminal); NUNCA são critério de descarte nesta rodada.
 * Entrada INVÁLIDA (nº ímpar de bytes) NÃO vira silêncio: num fragmento
 * inválido não há como provar "tudo zero", então o chunk segue o fluxo normal.
 */
object LiveEnergyGate {
    /** Referência HISTÓRICA do contrato antigo (R4/R6) — NÃO é critério de
     * descarte; existe só para testes de regressão provarem que uma amostra
     * não nula abaixo destes valores ainda atravessa. */
    const val LEGACY_RMS_SILENCE_THRESHOLD = 20.0

    /** Referência HISTÓRICA do contrato antigo (R4/R6) — idem acima. */
    const val LEGACY_PEAK_RESCUE_THRESHOLD = 320

    data class Energy(
        val rms: Double,
        val peak: Int,
        /** true somente quando [validPcm] e TODAS as amostras (16 bits) são 0. */
        val digitalZero: Boolean,
        /** false = fragmento inválido (nº ímpar de bytes): o contrato só-zero não decide. */
        val validPcm: Boolean,
        val sampleCount: Int,
    )

    /** Medição PURA (diagnóstico + decisão). Amostras lidas como s16le. */
    fun measure(pcm: ByteArray): Energy {
        val sampleCount = pcm.size / 2
        val validPcm = pcm.size >= 2 && (pcm.size % 2) == 0
        if (sampleCount <= 0) {
            return Energy(0.0, 0, digitalZero = false, validPcm = validPcm, sampleCount = 0)
        }
        var sumSquares = 0.0
        var peak = 0
        var allZero = true
        for (i in 0 until sampleCount) {
            val bits = (pcm[i * 2].toInt() and 0xFF) or (pcm[i * 2 + 1].toInt() shl 8)
            if (bits != 0) allZero = false
            val s = bits.toShort().toInt()
            sumSquares += s.toDouble() * s.toDouble()
            val abs = if (s < 0) -s else s
            if (abs > peak) peak = abs
        }
        return Energy(
            kotlin.math.sqrt(sumSquares / sampleCount),
            peak,
            digitalZero = allZero,
            validPcm = validPcm,
            sampleCount = sampleCount,
        )
    }

    /** Decisão do portão: verdadeiro APENAS para PCM válido integralmente zero. */
    fun isDigitalSilence(energy: Energy): Boolean = energy.validPcm && energy.digitalZero
}

/** R8/A1 — FASE observável do lifecycle do job ao vivo (seleção → preparação →
 * carga → captura → decode → teardown). O terminal SEMPRE declara até onde o
 * job chegou; um cancel durante a carga NÃO pode se apresentar como job que
 * capturou/decodificou. */
enum class LiveJobPhase(val wire: String) {
    SELECTION("selecao"),
    PREPARATION("preparacao"),
    LOAD("carga"),
    CAPTURE("captura"),
    DECODE("decode"),
    TEARDOWN("teardown"),
}

/** R8/A1 — desfecho TIPADO de um job (contrato com o analisador): cancel
 * continua cancel, erro continua erro — nunca um virando o outro. */
enum class LiveJobOutcome(val wire: String, val verdict: String) {
    SUCCESS("success", "sucesso"),
    NO_SPEECH("no_speech", "sem-fala"),
    PARTIAL("partial", "parcial"),
    CANCELLED("cancelled", "cancelado"),
    ERROR("error", "erro"),
}

/** R8/A1 — identidade IMUTÁVEL do job (run/act/job/gen), capturada no
 * [LiveEventStamp.beginJob] e usada em TODAS as saídas daquele job. */
data class LiveJobIdentity(
    val runId: String,
    val activityInstanceId: Int,
    val jobId: Long,
    val generation: Long,
) {
    /** Forma curta "act<A>.job<J>.gen<G>" — MESMO formato de
     * [LiveEventIdentity.shortForm], mas sem tocar o objeto global (que usa
     * APIs Android no inicializador e não carrega em teste JVM puro). */
    val shortForm: String
        get() = "act$activityInstanceId.job$jobId.gen$generation"
}

/** R8/A1 — TERMINAL universal de um job iniciado. */
data class LiveJobTerminal(
    val identity: LiveJobIdentity,
    val outcome: LiveJobOutcome,
    val reason: String,
    val phase: LiveJobPhase,
    val durationMs: Long,
    val decodeWindows: Int,
    val decodeWallMs: Long,
    val summary: LiveTerminalSummary?,
) {
    val verdict: String get() = outcome.verdict
    val cancelled: Boolean get() = outcome == LiveJobOutcome.CANCELLED
    /** false = o pipeline NUNCA existiu neste job (cancel/erro na carga). */
    val pipelineExisted: Boolean get() = summary != null

    /** Campos JSON do evento `live_terminal` (sem chaves externas). Quando o
     * pipeline não existiu, os contadores são ZERO — nunca herdados/inventados
     * (os do sucessor NÃO entram aqui: a identidade e os números são do job). */
    fun jsonFields(extra: String = ""): String {
        val s = summary
        return StringBuilder(640).apply {
            append("\"outcome\":\"").append(outcome.wire).append('"')
            append(",\"verdict\":\"").append(verdict).append('"')
            append(",\"cancelled\":").append(cancelled)
            append(",\"pipeline\":").append(s != null)
            append(",\"phase\":\"").append(phase.wire).append('"')
            append(",\"reason\":\"").append(escapeJson(reason)).append('"')
            append(",\"identity\":\"").append(identity.shortForm).append('"')
            append(",\"duration_ms\":").append(durationMs)
            append(",\"decode_windows\":").append(decodeWindows)
            append(",\"decode_wall_ms\":").append(decodeWallMs)
            if (s != null) {
                append(",\"ok_samples\":").append(s.okSamples)
                append(",\"silence_samples\":").append(s.silenceSamples)
                append(",\"failed_samples\":").append(s.failedSamples)
                append(",\"cancelled_samples\":").append(s.cancelledSamples)
                append(",\"lost_samples\":").append(s.lostSamples)
                append(",\"late_lost_samples\":").append(s.lateLostSamples)
                append(",\"odd_bytes\":").append(s.oddBytesDropped)
                append(",\"success_chunks\":").append(s.successChunks)
                append(",\"failed_chunks\":").append(s.failedChunks)
                append(",\"no_speech_chunks\":").append(s.noSpeechChunks)
                append(",\"zero_digital_skipped\":").append(s.digitalZeroSkippedChunks)
                append(",\"silence_skipped_samples\":").append(s.silenceSkippedSamples)
                append(",\"unique_samples\":").append(s.uniqueSamples)
                append(",\"contextual_samples\":").append(s.contextualSamples)
                append(",\"source_failure\":\"").append(escapeJson(s.sourceFailure ?: "")).append('"')
                append(",\"storage_failure\":\"").append(escapeJson(s.storageFailure ?: "")).append('"')
                append(",\"reader_reason\":\"").append(escapeJson(s.readerReason)).append('"')
                append(",\"worker_error\":\"").append(escapeJson(s.lastError ?: "")).append('"')
                append(",\"listener_errors\":").append(s.listenerErrors)
                append(",\"effective\":\"").append(escapeJson(s.effective)).append('"')
            } else {
                append(",\"ok_samples\":0,\"silence_samples\":0,\"failed_samples\":0")
                append(",\"cancelled_samples\":0,\"lost_samples\":0,\"late_lost_samples\":0")
                append(",\"odd_bytes\":0,\"success_chunks\":0,\"failed_chunks\":0")
                append(",\"no_speech_chunks\":0,\"zero_digital_skipped\":0")
                append(",\"silence_skipped_samples\":0")
                append(",\"unique_samples\":0,\"contextual_samples\":0")
                append(",\"source_failure\":\"\",\"storage_failure\":\"\"")
                append(",\"reader_reason\":\"\",\"worker_error\":\"\"")
                append(",\"listener_errors\":0,\"effective\":\"\"")
            }
            if (extra.isNotBlank()) {
                append(',').append(extra.removePrefix("{").removeSuffix("}"))
            }
        }.toString()
    }

    companion object {
        internal fun escapeJson(v: String): String =
            v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ")
    }
}

/** R8/A1 — EMISSOR TERMINAL ÚNICO E IDEMPOTENTE de um job.
 *
 * Contrato:
 *  - exatamente UM `live_terminal` por job iniciado (o primeiro emit ganha;
 *    qualquer emissão posterior devolve null e NÃO escreve nada);
 *  - identidade IMMUTABLE ([identity] capturada no início do job): um job
 *    sucessor começando NÃO altera o carimbo nem os números deste terminal;
 *  - resultado TIPADO ([LiveJobOutcome]) + fase + motivo; pipeline ausente ⇒
 *    contadores ZERO (nunca inventados nem tomados do sucessor);
 *  - pode ser chamado de QUALQUER thread (worker/leitor/main).
 */
class LiveJobTerminalEmitter(
    val identity: LiveJobIdentity,
    private val emitEvent: (name: String, identity: LiveJobIdentity, fields: String) -> Unit,
    private val listener: ((LiveJobTerminal) -> Unit)? = null,
) {
    private val emittedOnce = java.util.concurrent.atomic.AtomicBoolean(false)

    @Volatile
    var terminal: LiveJobTerminal? = null
        private set

    val emitted: Boolean get() = emittedOnce.get()

    /**
     * Emite o terminal do job (idempotente). [summary] só é aceito do pipeline
     * DESTE job; quando null, [decodeWindows]/[decodeWallMs] são forçados a 0.
     */
    fun emit(
        outcome: LiveJobOutcome,
        reason: String,
        phase: LiveJobPhase,
        durationMs: Long,
        decodeWindows: Int,
        decodeWallMs: Long,
        summary: LiveTerminalSummary?,
        extraFields: String = "",
    ): LiveJobTerminal? {
        if (!emittedOnce.compareAndSet(false, true)) return null
        val t = LiveJobTerminal(
            identity = identity,
            outcome = outcome,
            reason = reason,
            phase = phase,
            durationMs = durationMs,
            decodeWindows = if (summary == null) 0 else decodeWindows,
            decodeWallMs = if (summary == null) 0L else decodeWallMs,
            summary = summary,
        )
        terminal = t
        emitEvent("live_terminal", identity, t.jsonFields(extraFields))
        listener?.invoke(t)
        return t
    }

    companion object {
        /** Classificação a partir do retrato do pipeline (só quando ele existiu). */
        fun classify(summary: LiveTerminalSummary): LiveJobOutcome = when {
            summary.cancelled -> LiveJobOutcome.CANCELLED
            summary.verdict == "sucesso" -> LiveJobOutcome.SUCCESS
            summary.verdict == "parcial" -> LiveJobOutcome.PARTIAL
            else -> LiveJobOutcome.NO_SPEECH
        }
    }
}

/** Contadores por desfecho de chunk do job ao vivo (thread do worker). */
class LiveChunkTally {
    private val success = AtomicInteger()
    private val noSpeech = AtomicInteger()
    private val failed = AtomicInteger()
    private val cancelled = AtomicInteger()
    /** R7/A2: chunks pulados pelo portão = SOMENTE zero-digital válido. */
    private val digitalZeroSkipped = AtomicInteger()

    fun count(result: LiveChunkResult) {
        when (result) {
            is LiveChunkResult.Success -> success.incrementAndGet()
            is LiveChunkResult.NoSpeech -> {
                noSpeech.incrementAndGet()
                if (result.reason.startsWith("zero-digital")) digitalZeroSkipped.incrementAndGet()
            }
            is LiveChunkResult.Failed -> failed.incrementAndGet()
            is LiveChunkResult.Cancelled -> cancelled.incrementAndGet()
        }
    }

    fun successChunks(): Int = success.get()
    fun noSpeechChunks(): Int = noSpeech.get()
    fun failedChunks(): Int = failed.get()
    fun cancelledChunks(): Int = cancelled.get()
    fun digitalZeroSkippedChunks(): Int = digitalZeroSkipped.get()
}

/** R4/C2 — retrato TERMINAL do job ao vivo, montado ANTES de destruir o
 * pipeline. Diferencia sucesso / não-fala / parcial / erro / cancelado com
 * duração, amostras falhas/perdidas/canceladas, byte ímpar e MOTIVO. */
data class LiveTerminalSummary(
    val cancelled: Boolean,
    val durationMs: Long,
    val capturedSamples: Long,
    val processedSamples: Long,
    val okSamples: Long,
    val silenceSamples: Long,
    val failedSamples: Long,
    val cancelledSamples: Long,
    val lostSamples: Long,
    val lateLostSamples: Long,
    val oddBytesDropped: Long,
    val sourceFailure: String?,
    val storageFailure: String?,
    val sourceStalled: Boolean,
    val readerReason: String,
    /** R6/D2: amostras únicas/contexto do caminho em janela (0 no modo 2 s). */
    val uniqueSamples: Long = 0L,
    val contextualSamples: Long = 0L,
    val successChunks: Int,
    val noSpeechChunks: Int,
    val failedChunks: Int,
    val cancelledChunks: Int,
    /** R7/A2: pulados pelo portão = somente zero-digital (PCM válido todo 0). */
    val digitalZeroSkippedChunks: Int,
    val lastError: String?,
    val effective: String,
    /** R4/C2: entregas ao listener que lançaram (perda EXPLÍCITA de entrega). */
    val listenerErrors: Int,
    /** R7/A1: identidade do PRODUTOR do job (act/job/gen) — viaja até a UI e o
     * terminal para correlação de eventos; vazia quando não há job. */
    val identity: String = "",
    /** R7/E3 — silêncio decidido pelo VAD do planejador (não decodificado,
     * contabilizado; 0 no modo clássico). */
    val silenceSkippedSamples: Long = 0L,
    /** R7/E3 — resumo do PLANO (VAD) quando houver: "janelas=… forçados=…". */
    val planSummary: String = "",
) {
    val interrupted: Boolean
        get() = sourceFailure != null || storageFailure != null || sourceStalled ||
            readerReason == "source-error" || readerReason == "stalled" || readerReason == "pipeline-rejected"

    val hasLoss: Boolean
        get() = failedSamples > 0 || lostSamples > 0 || lateLostSamples > 0 || oddBytesDropped > 0

    /** Veredito terminal (a UI NUNCA sobrescreve isto com "finalizada"). */
    val verdict: String
        get() = when {
            cancelled -> "cancelado"
            interrupted || failedChunks > 0 || hasLoss || listenerErrors > 0 -> "parcial"
            okSamples > 0 -> "sucesso"
            else -> "sem-fala"
        }

    /** Linha de status/terminal com números e motivo (não só log interno). */
    fun statusLine(): String = buildString {
        append("Ao vivo ")
        append(String.format(Locale.US, "%.1fs", durationMs / 1000.0))
        append(" — $verdict")
        append(": trechos=${successChunks} falhas=${failedChunks} semFala=${noSpeechChunks} cancelados=${cancelledChunks} zeroDigitalIgnorados=${digitalZeroSkippedChunks}")
        append(" | amostras ok=$okSamples silencio=$silenceSamples falha=$failedSamples canceladas=$cancelledSamples perdidas=$lostSamples+$lateLostSamples")
        append(" | janela unico=$uniqueSamples contexto=$contextualSamples")
        if (silenceSkippedSamples > 0) append(" silencioVadIgnorado=$silenceSkippedSamples")
        if (planSummary.isNotBlank()) append(" | $planSummary")
        if (oddBytesDropped > 0) append(" byteImparDescartado=$oddBytesDropped")
        when {
            cancelled -> append(" (teardown: backlog descartado explicitamente)")
            sourceFailure != null -> append(" — FONTE FALHOU: $sourceFailure")
            storageFailure != null -> append(" — ARMAZENAMENTO FALHOU: $storageFailure")
            sourceStalled -> append(" — fonte estagnada")
            readerReason == "eof" -> append(" — fim da fonte (EOF)")
            readerReason == "pipeline-rejected" -> append(" — pipeline recusou leitura")
        }
        if (lastError != null) append(" — ultimo erro: $lastError")
        if (listenerErrors > 0) append(" listenerErrors=$listenerErrors")
        if (effective.isNotBlank()) append(" | $effective")
        // R7/A1: identidade do produtor no fim da linha — consumidores antigos
        // (prefixo/contains) continuam valendo; a correlação NÃO depende do
        // nome do arquivo do laboratório.
        if (identity.isNotBlank()) append(" | id=$identity")
    }

    companion object {
        fun of(
            cancelled: Boolean,
            durationMs: Long,
            counters: LivePcmCounters,
            readerReason: String,
            tally: LiveChunkTally,
            lastError: String?,
            effective: String,
            identity: String = "",
            silenceSkippedSamples: Long = 0L,
            planSummary: String = "",
        ): LiveTerminalSummary = LiveTerminalSummary(
            cancelled = cancelled,
            durationMs = durationMs,
            capturedSamples = counters.capturedSamples,
            processedSamples = counters.processedSamples,
            okSamples = counters.okSamples,
            silenceSamples = counters.silenceSamples,
            failedSamples = counters.failedSamples,
            cancelledSamples = counters.cancelledSamples,
            lostSamples = counters.lostSamples,
            lateLostSamples = counters.lateLostSamples,
            oddBytesDropped = counters.oddBytesDropped,
            sourceFailure = counters.sourceFailure,
            storageFailure = counters.storageFailure,
            sourceStalled = counters.sourceStalled,
            readerReason = readerReason,
            successChunks = tally.successChunks(),
            noSpeechChunks = tally.noSpeechChunks(),
            failedChunks = tally.failedChunks(),
            cancelledChunks = tally.cancelledChunks(),
            digitalZeroSkippedChunks = tally.digitalZeroSkippedChunks(),
            lastError = lastError,
            effective = effective,
            listenerErrors = counters.listenerErrors,
            uniqueSamples = counters.uniqueSamples,
            contextualSamples = counters.contextualSamples,
            identity = identity,
            silenceSkippedSamples = silenceSkippedSamples,
            planSummary = planSummary,
        )
    }
}
