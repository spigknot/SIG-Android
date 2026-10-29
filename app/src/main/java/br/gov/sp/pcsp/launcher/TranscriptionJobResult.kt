package br.gov.sp.pcsp.launcher

import org.json.JSONArray
import org.json.JSONObject

/**
 * R12/A2 — RESULTADO DE TRANSCRIÇÃO COM IDENTIDADE DE JOB (contrato puro).
 *
 * ## Defeito que este arquivo corrige
 *
 * Na R11 o instrumento P4 exportava o texto do WIDGET (`live_transcript_text`)
 * ao fim de cada passo. O passo 1 era CANCELADO antes do decode e o widget
 * ainda continha o texto do passo 0; o export gravou o MESMO texto (329 B,
 * sha `bf956e79…`) nos três passos e a análise publicou "WER 0,0000" em um job
 * que nunca decodificou. O produto tinha a mesma associação: `clearOutput()`
 * não limpava o widget, então um job cancelado/errado deixava na tela o texto
 * do job anterior, sem identidade, copiável como se fosse o resultado atual.
 *
 * ## Contrato
 *
 * Quem PRODUZ o texto (o caminho de transcrição) carimba a identidade e o
 * estado terminal; quem EXPORTA apenas lê o que o produtor publicou e NUNCA
 * reusa texto de outro job. Regras:
 *
 *  * todo resultado carrega [TranscriptionJobKey] (job_id/owner/input_id/
 *    attempt_id) — sem identidade completa não há resultado;
 *  * publicação é aceita SÓ para a tentativa ATIVA (callback atrasado de um
 *    job anterior é recusado — [TranscriptionJobLedger.appendSegment] e
 *    [TranscriptionJobLedger.finishAttempt] devolvem `false`/`null`);
 *  * texto de job que não decodificou é [TextOrigin.NONE] — export vazio e
 *    [TranscriptionJobResult.werEligible] falso (nunca "sucesso");
 *  * texto parcial (cancel/erro com fala já decodificada) é
 *    [TextOrigin.PARTIAL] e continua marcado como parcial no payload/export;
 *  * o corpo do texto é DADO do produtor, por bloco/entrada: nada é removido
 *    por regex de nome de arquivo ou de separador (a fala "relatorio.wav" e
 *    uma linha "---------------" continuam íntegras).
 *
 * Módulo sem Android (só `org.json`), testável na JVM. O laboratório lê o
 * MESMO ledger do processo ([TranscriptionJobResults]) em vez do widget.
 */
enum class TextOrigin {
    /** Texto veio do decode DESTE job/tentativa. */
    DECODE,

    /** Texto decodificado DESTE job, mas o job terminou sem completar. */
    PARTIAL,

    /** Nenhum texto deste job — export vazio, sem resultado. */
    NONE,
}

/** Estado terminal do job, com o rótulo usado pelo serviço/relatório. */
enum class JobTerminalState(val wire: String) {
    SUCCESS("SUCCESS"),
    CANCELLED("CANCELLED"),
    ERROR("ERROR"),
    NO_SPEECH("NO_SPEECH"),
    ;

    companion object {
        /** Rótulo → estado; desconhecido devolve `null` (nunca "sucesso"). */
        fun fromWire(value: String?): JobTerminalState? =
            entries.firstOrNull { it.wire.equals(value?.trim(), ignoreCase = true) }
    }
}

/**
 * Identidade COMPLETA de um resultado: quem produziu (run/act/job), qual dono
 * de sessão, qual entrada e qual tentativa daquela entrada.
 *
 * `owner` é a geração de sessão (dono nativo) — a MESMA usada por
 * `WhisperJobLifecycle.isOwner`. `attemptId` distingue duas passadas do mesmo
 * arquivo no mesmo job (ex.: retry/segunda execução).
 */
data class TranscriptionJobKey(
    val runId: String,
    val actId: Int,
    val jobId: Long,
    val owner: Long,
    val inputId: String,
    val attemptId: Long,
) {
    /** Identidade utilizável: todos os campos preenchidos. */
    val isComplete: Boolean
        get() = runId.isNotBlank() && actId > 0 && jobId > 0L && owner > 0L &&
            inputId.isNotBlank() && attemptId > 0L

    /** Forma curta para log/UI: `act<A>.job<J>.gen<G>.att<N>`. */
    val shortForm: String
        get() = "act$actId.job$jobId.gen$owner.att$attemptId"

    fun toJson(): JSONObject = JSONObject()
        .put("run_id", runId)
        .put("act_id", actId)
        .put("job_id", jobId)
        .put("owner", owner)
        .put("input_id", inputId)
        .put("attempt_id", attemptId)
}

/** Bloco de texto de UMA entrada do job (dado estruturado, não widget). */
data class TranscriptionTextBlock(
    val inputId: String,
    val text: String,
    val origin: TextOrigin,
    /**
     * R14/E2 — TEMPOS do segmento em ms, relativos ao início do áudio.
     *
     * O JNI já entregava `t0/t1` ao callback (`onSegment(text, startMs, endMs)`) e
     * eles eram **descartados** — era o que impedia o replay fiel (a revisão da R13
     * apontou exatamente isto: "falta captura curta real com segmentos/tempos").
     * `null` = o produtor não informou (não é zero).
     */
    val t0Ms: Long? = null,
    val t1Ms: Long? = null,
    /**
     * R15/A3 — tentativa à qual o bloco pertence (pertinência EXPLÍCITA).
     * Sem isto, a deduplicação comparava o job inteiro com a tentativa
     * corrente e duplicava o segundo arquivo do lote.
     */
    val attemptId: Long = 0L,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("input_id", inputId)
        .put("origin", origin.name.lowercase())
        .put("t0_ms", t0Ms ?: JSONObject.NULL)
        .put("t1_ms", t1Ms ?: JSONObject.NULL)
        .put("text", text)
}

/**
 * Resultado publicado de um job: identidade + terminal + origem do texto.
 *
 * [audit] devolve as incoerências entre resultado, status nativo, código de
 * retorno e export; lista vazia = tudo concorda. Um resultado incoerente não
 * é promovido a "sucesso" nem usado para WER.
 */
class TranscriptionJobResult(
    val key: TranscriptionJobKey,
    val terminal: JobTerminalState,
    val origin: TextOrigin,
    val text: String,
    val blocks: List<TranscriptionTextBlock>,
    val nativeStatus: String,
    val resultCode: Int,
    val publishedSeq: Long,
) {
    /** Texto parcial do job atual (nunca é resultado completo). */
    val partial: Boolean get() = origin == TextOrigin.PARTIAL

    /** Existe texto DESTE job (completo ou parcial). */
    val hasResult: Boolean get() = origin != TextOrigin.NONE && text.isNotBlank()

    /**
     * Elegível a métrica (WER): só job SUCCESS cujo texto veio do decode DESTE
     * job. Parcial, vazio, erro e cancelamento são SEMPRE inelegíveis.
     */
    val werEligible: Boolean
        get() = terminal == JobTerminalState.SUCCESS &&
            origin == TextOrigin.DECODE && text.isNotBlank()

    /** Terminais que NÃO decodificaram o job atual. */
    val semDecode: Boolean get() = origin == TextOrigin.NONE

    fun audit(): List<String> {
        val out = mutableListOf<String>()
        if (text.isBlank() && origin != TextOrigin.NONE) out += "origem_sem_texto"
        if (text.isNotBlank() && origin == TextOrigin.NONE) out += "texto_sem_origem"
        if (hasResult && !key.isComplete) out += "identidade_incompleta"
        when (terminal) {
            JobTerminalState.SUCCESS -> {
                if (!nativeStatus.equals("ok", ignoreCase = true)) out += "sucesso_status_nativo_$nativeStatus"
                if (resultCode != RC_SUCCESS) out += "sucesso_rc_$resultCode"
                if (origin != TextOrigin.DECODE) out += "sucesso_sem_decode"
            }
            JobTerminalState.CANCELLED -> {
                if (!nativeStatus.equals("cancelled", ignoreCase = true)) out += "cancelado_status_nativo_$nativeStatus"
                if (resultCode != RC_CANCELLED) out += "cancelado_rc_$resultCode"
                if (origin == TextOrigin.DECODE) out += "cancelado_com_texto_completo"
            }
            JobTerminalState.ERROR -> {
                if (!nativeStatus.equals("error", ignoreCase = true)) out += "erro_status_nativo_$nativeStatus"
                if (resultCode != RC_ERROR) out += "erro_rc_$resultCode"
                if (origin == TextOrigin.DECODE) out += "erro_com_texto_completo"
            }
            JobTerminalState.NO_SPEECH -> {
                if (!nativeStatus.equals("no_speech", ignoreCase = true)) out += "sem_fala_status_nativo_$nativeStatus"
                if (resultCode != RC_NO_SPEECH) out += "sem_fala_rc_$resultCode"
                if (origin != TextOrigin.NONE) out += "sem_fala_com_texto"
            }
        }
        if (werEligible && partial) out += "wer_em_parcial"
        val joined = blocks.joinToString("\n") { it.text.trim() }.trim()
        if (blocks.isNotEmpty() && joined != text.trim()) out += "blocos_diferem_do_texto"
        return out
    }

    fun toJson(): JSONObject {
        val arr = JSONArray()
        blocks.forEach { arr.put(it.toJson()) }
        return key.toJson()
            .put("schema", SCHEMA)
            .put("terminal", terminal.wire)
            .put("text_origin", origin.name.lowercase())
            .put("parcial", partial)
            .put("sem_resultado", !hasResult)
            .put("wer_eligivel", werEligible)
            .put("native_status", nativeStatus)
            .put("rc", resultCode)
            .put("chars", text.length)
            .put("bytes_utf8", text.toByteArray(Charsets.UTF_8).size)
            .put("published_seq", publishedSeq)
            .put("blocks", arr)
    }

    fun toJsonString(): String = toJson().toString()

    override fun toString(): String =
        "TranscriptionJobResult(${key.shortForm}, ${terminal.wire}, ${origin.name.lowercase()}," +
            " chars=${text.length}, rc=$resultCode, seq=$publishedSeq)"

    companion object {
        /** Esquema do payload estruturado (consumido pelo lab e pela análise). */
        const val SCHEMA = "sig-whisper-result/v1"

        const val RC_SUCCESS = 0
        const val RC_ERROR = 1
        const val RC_CANCELLED = 2
        const val RC_NO_SPEECH = 3

        /** Código de retorno coerente com cada terminal. */
        fun resultCodeOf(terminal: JobTerminalState): Int = when (terminal) {
            JobTerminalState.SUCCESS -> RC_SUCCESS
            JobTerminalState.ERROR -> RC_ERROR
            JobTerminalState.CANCELLED -> RC_CANCELLED
            JobTerminalState.NO_SPEECH -> RC_NO_SPEECH
        }
    }
}

/**
 * Export do resultado: o TEXTO que sai é o do payload, ou VAZIO quando o job
 * atual não produziu texto. Nunca herda o widget nem texto de job anterior.
 *
 * [empty] + [reason] tornam a ausência explícita (o consumidor não pode
 * interpretar vazio como "transcrição perfeita" nem como sucesso).
 */
data class TranscriptionJobExport(
    val text: String,
    val empty: Boolean,
    val partial: Boolean,
    val werEligible: Boolean,
    val reason: String,
    val result: TranscriptionJobResult?,
) {
    fun metaJson(): JSONObject {
        val meta = JSONObject()
            .put("export_schema", SCHEMA)
            .put("export_empty", empty)
            .put("parcial", partial)
            .put("wer_eligivel", werEligible)
            .put("motivo", reason)
        val r = result
        if (r == null) {
            meta.put("terminal", "NONE")
            meta.put("text_origin", TextOrigin.NONE.name.lowercase())
            meta.put("sem_resultado", true)
        } else {
            r.toJson().keys().forEach { k -> meta.put(k, r.toJson().get(k)) }
        }
        return meta
    }

    companion object {
        const val SCHEMA = "sig-whisper-export/v1"
    }
}

/**
 * Ledger de resultados por job — a ÚNICA fonte de texto para export.
 *
 * Thread-safe (o worker publica, a UI/lab leem). O ledger:
 *  * gera identidade (`job_id`/`attempt_id` monotônicos por processo);
 *  * aceita texto só da tentativa ATIVA (callback atrasado é recusado);
 *  * fecha o job UMA vez (segundo terminal do mesmo job é ignorado);
 *  * mantém o último resultado BOM com sua identidade ([lastGood]) — a UI
 *    pode exibi-lo sabendo de quem é, mas ele não vira resultado atual.
 */
class TranscriptionJobLedger {

    private val lock = Any()

    private var jobSeq = 0L
    private var attemptSeq = 0L
    private var publishSeq = 0L

    private var activeJobId = 0L
    private var activeOwner = 0L
    private var activeJobRunId = ""
    private var activeJobActId = 0
    private var activeKey: TranscriptionJobKey? = null
    private val activeText = StringBuilder()
    /**
     * R15/A3 — BLOCOS DA TENTATIVA ATIVA (separados dos já fechados).
     *
     * O defeito da R14: `appendSegment` inseria cada segmento direto em
     * `activeBlocks` (de TODO o job) e `finishAttempt` decidia duplicação
     * comparando a concatenação de todos os blocos do job com o texto só da
     * tentativa corrente. Em um lote A+B isso dá "A+B" != "B" ⇒ B era anexado
     * de novo (resultado: primeiro/segundo/SEGUNDO).
     *
     * Agora a perteniência é EXPLÍCITA: cada bloco sabe a qual tentativa
     * (inputId + attemptId) pertence, e o fechamento move os blocos da
     * tentativa para a lista de fechados (idempotente).
     */
    private var activeBlocks = mutableListOf<TranscriptionTextBlock>()
    /** Blocos das tentativas JÁ FECHADAS (de entradas anteriores do mesmo job). */
    private var closedBlocks = mutableListOf<TranscriptionTextBlock>()
    /** Índice do bloco agregado da tentativa corrente (para o fechamento idempotente). */
    private var activeAggregateIndex = -1

    private var terminatedJobId = 0L
    private var attemptClosed = false
    /**
     * R16/B1 — existe tentativa ABERTA (entre beginAttempt e finishAttempt)?
     *
     * `attemptClosed` sozinho nao distingue "nenhuma tentativa ainda" de
     * "tentativa aberta" (ambos sao false). Sem esta flag, abrir B por cima de
     * A limpava activeText e deixava os blocos de A em activeBlocks -> o
     * publicado saia "velho | novo | novo".
     */
    private var attemptOpen = false
    /** R16/B1 — quantas aberturas foram recusadas por tentativa aberta. */
    var rejectedAttemptOpen = 0
        private set
    private var latest: TranscriptionJobResult? = null
    private var lastGood: TranscriptionJobResult? = null

    /** Abre um job; devolve o `job_id` recém-assumido. */
    fun beginJob(runId: String, actId: Int, owner: Long): Long = synchronized(lock) {
        jobSeq += 1
        activeJobId = jobSeq
        activeOwner = owner
        activeKey = null
        activeJobRunId = runId
        activeJobActId = actId
        activeText.setLength(0)
        activeBlocks = mutableListOf()
        closedBlocks = mutableListOf()
        activeAggregateIndex = -1
        terminatedJobId = 0L
        attemptClosed = false
        attemptOpen = false
        jobSeq
    }

    /**
     * Abre a tentativa (entrada) do job ATIVO; recusa (`null`) o ingresso
     * atrasado sem tocar no estado.
     *
     * R13/A2 — AQUISIÇÃO ATÔMICA: [beginJob] é quem abre nova posse;
     * [beginAttempt] NUNCA troca de job. A chamada só prossegue quando
     * (`jobId`, `owner`, `runId`, `actId`) coincidem com o job ativo e o job
     * não está terminado. Job substituído/terminal/desconhecido (inclusive
     * `jobId` positivo arbitrário de um job antigo) é recusado ANTES de
     * limpar [activeText], trocar [activeKey] ou encerrar a tentativa de
     * outro job. Os blocos já publicados de B ([activeBlocks]) são
     * preservados.
     */
    fun beginAttempt(jobId: Long, owner: Long, inputId: String, runId: String, actId: Int): TranscriptionJobKey? =
        synchronized(lock) {
            require(jobId > 0L && owner > 0L && inputId.isNotBlank()) { "identidade de tentativa incompleta" }
            if (jobId != activeJobId) return null
            if (owner != activeOwner) return null
            if (runId != activeJobRunId) return null
            if (actId != activeJobActId) return null
            if (terminatedJobId == jobId) return null
            if (terminatedJobId != 0L) return null
            // R16/B1 — RECUSA: nao abrir nova tentativa sobre uma ainda aberta.
            // Substituir em silencio misturava os blocos das duas tentativas.
            if (attemptOpen) {
                rejectedAttemptOpen += 1
                return null
            }
            attemptSeq += 1
            activeText.setLength(0)
            attemptClosed = false
            attemptOpen = true
            TranscriptionJobKey(
                runId = runId,
                actId = actId,
                jobId = jobId,
                owner = owner,
                inputId = inputId,
                attemptId = attemptSeq,
            ).also { activeKey = it }
        }

    /** Chave da tentativa ativa (ou `null`). */
    fun activeKey(): TranscriptionJobKey? = synchronized(lock) { activeKey }

    /**
     * Acrescenta um segmento à tentativa ATIVA. `false` = recusado (callback
     * atrasado de outro job/tentativa, tentativa já fechada, ou vazio).
     *
     * R14/E2 — `t0Ms`/`t1Ms` são os TEMPOS do segmento (ms, relativos ao início
     * do áudio). O JNI já os entregava e eram descartados; agora ficam no bloco,
     * que é o que torna o replay fiel possível.
     */
    fun appendSegment(
        key: TranscriptionJobKey,
        text: String,
        t0Ms: Long? = null,
        t1Ms: Long? = null,
    ): Boolean = synchronized(lock) {
        val active = activeKey ?: return false
        if (active != key || attemptClosed) return false
        if (terminatedJobId == key.jobId) return false
        val segment = text.trim()
        if (segment.isEmpty()) return false
        if (activeText.isNotEmpty()) activeText.append('\n')
        activeText.append(segment)
        // R14/E2 — o bloco guarda o texto COM os tempos.
        activeBlocks.add(
            TranscriptionTextBlock(
                active.inputId, segment, TextOrigin.DECODE, t0Ms, t1Ms,
                attemptId = active.attemptId,
            )
        )
        true
    }

    /** Texto acumulado da tentativa [key] (vazio se não for a ativa). */
    fun textOf(key: TranscriptionJobKey): String = synchronized(lock) {
        if (activeKey != key) "" else activeText.toString()
    }

    /** Fecha a tentativa e publica o bloco dela no job. `null` = recusado. */
    fun finishAttempt(
        key: TranscriptionJobKey,
        terminal: JobTerminalState,
        nativeStatus: String,
        resultCode: Int = TranscriptionJobResult.resultCodeOf(terminal),
    ): TranscriptionJobResult? = synchronized(lock) {
        val active = activeKey ?: return null
        if (active != key || attemptClosed) return null
        if (terminatedJobId == key.jobId) return null
        val text = activeText.toString().trim()
        val origin = when {
            text.isEmpty() -> TextOrigin.NONE
            terminal == JobTerminalState.SUCCESS -> TextOrigin.DECODE
            else -> TextOrigin.PARTIAL
        }
        // R15/A3 — o agregado da tentativa é anexado UMA VEZ e só quando os
        // blocos da tentativa NÃO o cobrem (caso sem segmentos — aí ele é a
        // única informação). A comparação é entre o agregado DESTA TENTATIVA e
        // o texto DESTA TENTATIVA — nunca entre o job inteiro e a tentativa
        // (era isso que duplicava o 2º arquivo do lote).
        val segmentosDaTentativa = activeBlocks.joinToString("\n") { it.text }.trim()
        if (text.isNotEmpty() && segmentosDaTentativa != text) {
            activeBlocks += TranscriptionTextBlock(
                key.inputId, text, origin, attemptId = key.attemptId
            )
        }
        activeAggregateIndex = if (activeBlocks.isEmpty()) -1 else activeBlocks.size - 1
        // Fecha a tentativa: os blocos dela passam a ser "de entradas anteriores".
        closedBlocks += activeBlocks
        val blocosDaTentativa = activeBlocks.toList()
        activeBlocks = mutableListOf()
        activeText.setLength(0)
        attemptClosed = true
        attemptOpen = false
        publishSeq += 1
        TranscriptionJobResult(
            key, terminal, origin, text,
            blocosDaTentativa,
            nativeStatus, resultCode, publishSeq,
        )
    }

    /**
     * Fecha o JOB (lote inteiro): texto = blocos publicados + parcial da
     * tentativa ativa. Sem texto ⇒ [TextOrigin.NONE] (export vazio, sem WER).
     * Segundo terminal do MESMO job é ignorado (`null`).
     */
    fun publishJob(
        jobId: Long,
        owner: Long,
        terminal: JobTerminalState,
        nativeStatus: String,
        resultCode: Int = TranscriptionJobResult.resultCodeOf(terminal),
    ): TranscriptionJobResult? = synchronized(lock) {
        if (jobId <= 0L || owner <= 0L) return null
        if (terminatedJobId == jobId) return null
        if (jobId != activeJobId || owner != activeOwner) return null
        // R15/A3 — o job é composta pelos blocos das tentativas JÁ FECHADAS
        // (entradas anteriores) + os da tentativa ATIVA. A pertencimento é
        // explícita, então não há mais comparação de texto global.
        val blocks = closedBlocks.toMutableList()
        val pending = activeText.toString().trim()
        // R15/A3 — a tentativa ATIVA entra com a SUA própria lista de blocos
        // (já anotados por `appendSegment`). O agregado da tentativa foi
        // anexado UMA VEZ no `finishAttempt` (índice guardado) — aqui NÃO se
        // anexa de novo, e a comparação nunca é entre o job inteiro e a
        // tentativa (era isso que duplicava o 2º arquivo do lote).
        blocks += activeBlocks
        val text = blocks.joinToString("\n") { it.text }.trim()
        val origin = when {
            text.isEmpty() -> TextOrigin.NONE
            terminal == JobTerminalState.SUCCESS -> TextOrigin.DECODE
            else -> TextOrigin.PARTIAL
        }
        val key = activeKey?.copy(jobId = jobId, owner = owner, attemptId = attemptSeq.coerceAtLeast(1L))
            ?: TranscriptionJobKey(
                runId = activeJobRunId,
                actId = activeJobActId,
                jobId = jobId,
                owner = owner,
                inputId = blocks.firstOrNull()?.inputId.orEmpty(),
                attemptId = attemptSeq.coerceAtLeast(1L),
            )
        activeText.setLength(0)
        activeKey = null
        attemptClosed = true
        attemptOpen = false
        terminatedJobId = jobId
        publishSeq += 1
        val result = TranscriptionJobResult(key, terminal, origin, text, blocks, nativeStatus, resultCode, publishSeq)
        latest = result
        if (result.werEligible) lastGood = result
        result
    }

    /** Último resultado publicado (qualquer terminal). */
    fun latest(): TranscriptionJobResult? = synchronized(lock) { latest }

    /**
     * Resultado publicado DEPOIS da fronteira [seq] — regra do INSTRUMENTO por
     * passo: só um terminal novo (posterior ao `fa_step_start`) pertence ao
     * passo; o terminal anterior, por mais recente que seja, não é exportado.
     * `null` = o passo não publicou resultado próprio (export vazio).
     */
    fun latestAfter(seq: Long): TranscriptionJobResult? = synchronized(lock) {
        if (seq < 0L) return null
        val current = latest ?: return null
        if (current.publishedSeq <= seq) null else current
    }

    /** Último resultado BOM (SUCCESS + decode), com a identidade dele. */
    fun lastGood(): TranscriptionJobResult? = synchronized(lock) { lastGood }

    /** Contador de publicações — o lab usa para exigir um terminal NOVO. */
    fun currentSeq(): Long = synchronized(lock) { publishSeq }

    /** Export do último resultado publicado. */
    fun export(): TranscriptionJobExport = TranscriptionJobExportComposer.compose(latest())

    /** Export de um resultado específico (ou vazio explícito se `null`). */
    fun exportOf(result: TranscriptionJobResult?): TranscriptionJobExport =
        TranscriptionJobExportComposer.compose(result)

    /** Limpa o estado (fim de sessão do lab / testes). */
    fun reset() = synchronized(lock) {
        activeJobId = 0L
        activeOwner = 0L
        activeKey = null
        activeText.setLength(0)
        activeBlocks = mutableListOf()
        closedBlocks = mutableListOf()
        activeAggregateIndex = -1
        terminatedJobId = 0L
        latest = null
        lastGood = null
    }
}

/**
 * Compõe o export a partir do payload — sem widget, sem herança.
 *
 * Regra dura: sem resultado do job ATUAL o export é VAZIO e explicado; com
 * texto parcial o export é o parcial, marcado como parcial e inelegível a WER.
 */
object TranscriptionJobExportComposer {

    fun compose(result: TranscriptionJobResult?): TranscriptionJobExport {
        if (result == null) {
            return TranscriptionJobExport(
                text = "",
                empty = true,
                partial = false,
                werEligible = false,
                reason = "sem_payload",
                result = null,
            )
        }
        if (result.origin == TextOrigin.NONE || result.text.isBlank()) {
            return TranscriptionJobExport(
                text = "",
                empty = true,
                partial = false,
                werEligible = false,
                reason = when (result.terminal) {
                    JobTerminalState.CANCELLED -> "cancelado_sem_decode"
                    JobTerminalState.ERROR -> "erro_sem_decode"
                    JobTerminalState.NO_SPEECH -> "sem_fala"
                    JobTerminalState.SUCCESS -> "sucesso_sem_texto"
                },
                result = result,
            )
        }
        if (result.origin == TextOrigin.PARTIAL) {
            return TranscriptionJobExport(
                text = result.text,
                empty = false,
                partial = true,
                werEligible = false,
                reason = if (result.terminal == JobTerminalState.ERROR) "parcial_com_erro" else "parcial_cancelado",
                result = result,
            )
        }
        val violations = result.audit()
        val eligible = result.werEligible && violations.isEmpty()
        return TranscriptionJobExport(
            text = result.text,
            empty = false,
            partial = false,
            werEligible = eligible,
            reason = when {
                violations.isNotEmpty() -> "incoerente"
                eligible -> "completo"
                else -> "terminal_nao_sucesso"
            },
            result = result,
        )
    }
}

/**
 * Ledger do PROCESSO — produtor (Activity) e consumidores (lab/relatório)
 * compartilham a mesma instância. Nenhum hook de laboratório é necessário:
 * o texto publicado aqui é o do caminho de produção.
 */
object TranscriptionJobResults {
    val ledger = TranscriptionJobLedger()
}
