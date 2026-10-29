package br.gov.sp.pcsp.launcher

import java.util.Locale

/**
 * R6/D2 + R7/E2 + R8/B — COMMIT DE TEXTO do caminho ao vivo com janela+contexto
 * (seam PURO, sem Android). Cada janela decodifica [contexto][único]; o texto da
 * região de contexto é áudio JÁ comprometido — este objeto reconcilia a parte
 * COBERTA com a região ÚNICA e mantém hipóteses REVISÁVEIS até confirmação
 * (fronteira estável) ou terminal.
 *
 * ## Contrato
 * - O texto é uma LINHA DO TEMPO de blocos (ocorrências de região). Cada palavra
 *   tem estado: `COMMITTED` (estável, nunca reescrito), `REVIEWABLE` (cauda
 *   revisável/revisada; nunca final sem [finalize]) ou `HELD` (hipótese de
 *   região NÃO resolvida — não publicada até resolver/terminal, mas SEMPRE na
 *   posição da região; fechamento terminal é TIPADO).
 * - `displayText()` = palavras visíveis (committed + reviewable) na ordem da
 *   linha do tempo; retidas ficam fora enquanto pendentes.
 *
 * ## Reconciliação (R8/B, substitui o corte por posição da R7)
 * - A referência de reconciliação é a linha do tempo visível (committed +
 *   reviewable), em ordem. O bloco da janela que cobre o contexto é classificado
 *   por ALINHAMENTO (LCS normalizado) contra as palavras cobertas:
 *   casamento → duplicação; divergência 1:1 com sobreposição de áudio PROVADA
 *   (ao menos um tempo real) → a leitura NOVA substitui a antiga SÓ se a antiga
 *   for revisável (revisão em palavras antigas, contabilizada); palavra nova
 *   entre dois casamentos com o lado esquerdo revisável → INSERT (recuperação
 *   de palavra omitida); o resto que não casa → RETIDO (conflito; nunca corte
 *   silencioso). Prova de mesmo intervalo NÃO prova igualdade textual — por
 *   isso o promotion de cauda provisória exige o alinhamento acima.
 * - Retidas resolvem ORDENADAS e VINCULADAS À REGIÃO: quando uma janela MAIS
 *   NOVA cobre a região e reproduz a hipótese (texto+ocorrência), a retida é
 *   resolvida NO LUGAR (sem mover texto para o fim); divergência com tempo real
 *   revisa; omissão mantém pendente. O dedup NUNCA é por vocabulário/contagem
 *   global da referência (palavra igual em OUTRA ocorrência não é duplicata).
 * - [maxHeldHypotheses]/[maxHeldWords] limitam o crescimento; o excedente fecha
 *   TIPADO a região mais antiga (`forcedClosures`), também no lugar.
 * - RE-DECODIFICAÇÃO RESTRITA (R8/B): quando uma região fica retida, o PCM da
 *   janela ([onWindow] `windowPcm`, limites de retenção) é preservado para UMA
 *   re-decodição restrita — [takeRepairRequest] entrega a região (chamada FORA
 *   do lock, jamais JNI/callback sob o lock), [completeRepair]/[failRepair]
 *   fecham tipado. Sem áudio preservado/decoder: estado tipado (não declara
 *   sucesso integral). Nunca re-roda o histórico inteiro nem cria loop de retry.
 *
 * ## Evidência de tempo
 * - wordTimes: contagem, CORRESPONDÊNCIA de texto palavra-a-palavra, ORDEM
 *   não-decrescente e bounds dentro da janela; inválidos → estimativa +
 *   `invalidTimeWindowCount`.
 * - segments: texto/count têm de casar com a janela, ORDEM não-decrescente e
 *   bounds; a palavra herda o intervalo do SEGMENTO (evidência de intervalo,
 *   não de palavra). Segmento que CRUZA a fronteira não é cortado.
 *
 * ## Fence de geração / threads
 * - `generation` id de sessão: a primeira janela fixa; divergente é rejeitada
 *   (staleWindowCount) e jamais cruza texto entre jobs; [reset] zera.
 * - R7/fase A: todos os métodos públicos sincronizam no MESMO lock; nenhum
 *   callback roda sob o lock (leitura de UI × worker comprovada no soak).
 */
class LiveTextCommitter @JvmOverloads constructor(
    /** Mínimo de palavras de sobreposição para aceitar corte por casamento
     * TOLERANTE (1 divergência). Casamento EXATO de 1 palavra é aceito (a
     * região casada é evidência exata); o tolerante exige
     * `k >= max(3, minOverlapWords)`. */
    private val minOverlapWords: Int = 2,
    /** Palavras da cauda mantidas provisórias até a próxima janela confirmar. */
    private val tailGuardWords: Int = 2,
    /** Teto de palavras/segundo (estimativa p/ contar contexto esperado). */
    private val maxWordsPerSecond: Double = 4.0,
    /** Margem (± palavras) da busca do ponto de corte (diagnóstico). */
    private val overlapSlackWords: Int = 1,
    /** Máximo de hipóteses RETIDAS antes do fechamento tipado da mais antiga. */
    private val maxHeldHypotheses: Int = 4,
    /** Máximo de palavras retidas antes do fechamento tipado da mais antiga. */
    private val maxHeldWords: Int = 240,
    /** R8/B: teto de amostras do áudio preservado por região (re-decode). */
    private val maxRepairRegionSamples: Long = 160_000L,
    /** R8/B: teto TOTAL de bytes de áudio preservado para reparo. */
    private val maxRetainedAudioBytes: Long = 3L * 1024 * 1024,
    /**
     * R9/D-fix: contexto LIMITADO (amostras) que pode ser incluído no áudio do
     * reparo ALÉM do intervalo próprio da região — mas SEMPRE dentro do áudio
     * PRÓPRIO do chunk (o intervalo único). NUNCA se estende para o contexto
     * `[ws, uniqueStart)` já comprometido por janelas anteriores: re-decodificar
     * esse áudio alheio era o que devolvia texto de OUTRA ocorrência (ex. região
     * que cruzava a fronteira c09→d01 e reparava "A equipe de cã."). 250 ms @16k.
     */
    private val repairContextPadSamples: Long = 4_000L,
    /**
     * R9/D-fix: fração MÍNIMA da hipótese retida que a leitura do reparo tem de
     * reproduzir (ANCORAGEM) para ser aceita. Abaixo disso o reparo é REJEITADO
     * (nunca adotado): a hipótese do modelo permanece provisória e a região sai
     * como resultado PARCIAL no terminal.
     */
    private val minRepairAnchorRatio: Double = 0.5,
    /** R9/D-fix: nº MÍNIMO de palavras da hipótese reproduzidas pelo reparo. */
    private val minRepairAnchorWords: Int = 1,
) {

    data class WordTime(val word: String, val t0Ms: Long, val t1Ms: Long)

    data class CommitInfo(
        val windowIndex: Int,
        val committedWordsAdded: Int,
        val provisionalWords: Int,
        val committedWordCount: Int,
        val alignment: String,
        /** R7/E2: hipóteses retidas após esta janela. */
        val heldHypotheses: Int = 0,
        /** R7/E2: palavras retidas (não comprometidas, não descartadas). */
        val heldWords: Int = 0,
        /** R7/E2: fechamentos tipados acumulados (limite de retenção). */
        val forcedClosures: Int = 0,
        /** R8/B: palavras ANTIGAS revisadas (texto substituído) nesta janela. */
        val revisedWords: Int = 0,
        /** R8/B: palavras novas retidas por conflito com texto estável. */
        val conflictsHeld: Int = 0,
        /** R8/B: regiões retidas RESOLVIDAS por esta janela (no lugar). */
        val heldResolved: Int = 0,
        /** R8/B: fechamento não resolvido no terminal (regiões). */
        val unresolvedRegionsClosed: Int = 0,
        /** R8/B: regiões retidas aguardando/elegíveis a reparo. */
        val pendingRepairRegions: Int = 0,
        /** R8/B: reparos pedidos/ok/falhos acumulados. */
        val repairAttempts: Int = 0,
        val repairCompleted: Int = 0,
        val repairFailed: Int = 0,
        /** R8/B: bytes de áudio preservado retidos AGORA. */
        val retainedAudioBytes: Int = 0,
    )

    /** R8/B: pedido de re-decodição RESTRITA de uma região retida. O PCM é a
     * cópia preservada da janela original (recortada à região). */
    class RepairRequest(
        val regionId: Long,
        val windowIndex: Int,
        val startSample: Long,
        val endSample: Long,
        val pcm: ByteArray,
        val heldText: String,
        val wordCount: Int,
    )

    private class W(
        var word: String,
        /** KIND_EST = posição só estimada; KIND_TIME/KIND_SEG = intervalo real. */
        val kind: Int,
        val a0: Long,
        val a1: Long,
        val endEst: Long,
        val step: Long,
        var state: Int,
        var windowIndex: Int,
        /** R10/E2: áudio da JANELA/REGIÃO de origem — proveniência da ocorrência.
         *  O slot estimado é posição relativa à geometria do próprio bloco (duas
         *  janelas com densidades diferentes não são comparáveis por slot), então
         *  a identidade estimada ancora no ÁUDIO de origem, nunca no histórico. */
        var winA0: Long = 0L,
        var winA1: Long = 0L,
        /** R11/B2: início do áudio ÚNICO da janela de origem (winA0 + ctxSamples).
         *  Marca a fronteira CONTEXTO (re-decodificável por janela vizinha) vs
         *  TRECHO ÚNICO (conteúdo novo). Uma palavra ESTIMADA é a MESMA ocorrência
         *  de outra (entre janelas) sse sua posição estimada cai no CONTEXTO da
         *  própria janela — i.e., no áudio re-decodificado — e NÃO no trecho único.
         *  Permite distinguir re-decodificação (mesma ocorrência) de repetição no
         *  trecho único (nova ocorrência) sem confiar no span uniforme (que desloca
         *  com o nº de palavras da janela). */
        var winU0: Long = 0L,
    ) {
        var blk: Blk? = null
    }

    /** Bloco = ocorrência de região (um trecho contínuo de texto do job). */
    private class Blk(
        val windowIndex: Int,
        val words: MutableList<W>,
    ) {
        var regionId: Long = -1L
        /** Áudio preservado da região (só p/ retidas) e recorte no PCM. */
        var pcm: ByteArray? = null
        var regionStart: Long = 0L
        var regionEnd: Long = 0L
        /** REPAIR_NONE/REQUESTED/COMPLETED/FAILED/NO_AUDIO. */
        var repairState: Int = REPAIR_NONE
        /** R9/D-fix: true quando o áudio preservado foi RESTRITO ao áudio PRÓPRIO
         * do chunk (a região cruzava a fronteira); nesse caso a leitura do reparo
         * passa pelo GATE DE ANCORAGEM. */
        var audioRestricted: Boolean = false
        /** R9/D2: estado FINAL tipado da região (ver [RegionOutcome]). */
        var outcome: Int = OUTCOME_NONE
        /** R9/D2: palavras promovidas sem confirmação (só p/ PROMOTED_UNCONFIRMED). */
        var outcomeWords: Int = 0
    }

    /**
     * R9/D2 — CONTRATO DE RESULTADO PARCIAL das REGIÕES RETIDAS. Cada região
     * retida termina em UM estado TIPADO, contabilizado UMA vez (nunca só
     * "held_words=0", que confunde drenado com resolvido):
     *  - [resolvedConfirmed]: hipótese reproduzida por janela mais nova (no lugar);
     *  - [resolvedByRepair]: re-decode restrito cobriu TODA a hipótese (aceito);
     *  - [promotedUnconfirmed]: DRENADA no terminal/fechamento por limite SEM
     *    confirmação — o texto entra no display mas NÃO está verificado. Esta
     *    ainda é sub-classificada pelo MOTIVO (contadores `promotedRepairPartial`/
     *    `promotedRepairFailed`/`promotedAudioMissing`/`promotedNoRepair`), que
     *    NÃO são estados próprios: reparo parcial/falho cuja região segue sem
     *    confirmação continua sendo "promovida sem confirmação".
     * `integral` só é verdadeiro quando NENHUMA região foi promovida sem
     * confirmação. Terminal do PIPELINE com sucesso NÃO implica `integral`.
     */
    data class RegionOutcome(
        val resolvedConfirmed: Int = 0,
        val resolvedByRepair: Int = 0,
        val promotedUnconfirmed: Int = 0,
        val promotedUnconfirmedWords: Int = 0,
        val promotedRepairPartial: Int = 0,
        val promotedRepairFailed: Int = 0,
        val promotedAudioMissing: Int = 0,
        val promotedNoRepair: Int = 0,
        /** R10/E1: regiões cujo reparo trouxe evidência de tempo INVÁLIDA
         *  (rejeitado tipado; hipótese/PCM preservados). Nunca "tempo real
         *  confirmado" e nunca clampado em silêncio. */
        val promotedRepairInvalidEvidence: Int = 0,
    ) {
        val totalResolved: Int get() = resolvedConfirmed + resolvedByRepair
        val uncertain: Int get() = promotedUnconfirmed
        val integral: Boolean get() = promotedUnconfirmed == 0
        fun summary(): String =
            "resolvidas=$totalResolved(conf=$resolvedConfirmed,repair=$resolvedByRepair)" +
                " promovidas_sem_confirmacao=$promotedUnconfirmed(palavras=$promotedUnconfirmedWords;" +
                " reparo_parcial=$promotedRepairPartial, reparo_falho=$promotedRepairFailed," +
                " reparo_tempo_invalido=$promotedRepairInvalidEvidence," +
                " audio_ausente=$promotedAudioMissing, sem_reparo=$promotedNoRepair)"
    }

    private val lock = Any()
    private val timeline = ArrayList<Blk>()
    private var finalized = false
    private var cancelled = false
    private var currentGeneration = GENERATION_UNSPECIFIED
    private var staleWindows = 0
    private var forcedClosures = 0
    private var invalidTimeWindows = 0
    private var revisedTotal = 0
    private var repairedTotal = 0
    private var repairFailedTotal = 0
    /** R10/E1: reparos recusados por evidência de tempo inválida (tipado). */
    private var repairInvalidEvidenceTotal = 0
    private var repairAttemptsTotal = 0
    private var nextRegionId = 1L

    fun reset() = synchronized(lock) {
        timeline.clear()
        finalized = false
        cancelled = false
        currentGeneration = GENERATION_UNSPECIFIED
        staleWindows = 0
        forcedClosures = 0
        invalidTimeWindows = 0
        revisedTotal = 0
        repairedTotal = 0
        repairFailedTotal = 0
        repairInvalidEvidenceTotal = 0
        repairAttemptsTotal = 0
        nextRegionId = 1L
    }

    /**
     * R9/D2: resultado TIPADO por região, derivado do estado FINAL de cada bloco
     * (determinístico, sem contadores que possam dessincronizar). Cada região
     * conta em EXATAMENTE um grupo: resolvida (confirmada/por reparo) ou
     * PROMOVIDA SEM CONFIRMAÇÃO — esta ainda sub-classificada pelo motivo
     * (reparo parcial/falho/áudio ausente/sem reparo). `integral=false` quando
     * QUALQUER região foi promovida sem confirmação.
     */
    fun regionOutcome(): RegionOutcome = synchronized(lock) {
        var rc = 0; var rr = 0; var pu = 0; var pw = 0
        var pPartial = 0; var pFailed = 0; var pAudio = 0; var pNone = 0; var pInvalid = 0
        for (b in timeline) {
            when (b.outcome) {
                OUTCOME_RESOLVED_CONFIRMED -> rc++
                OUTCOME_RESOLVED_BY_REPAIR -> rr++
                OUTCOME_PROMOTED_UNCONFIRMED -> {
                    pu++
                    pw += b.outcomeWords
                    when (b.repairState) {
                        REPAIR_COMPLETED -> pPartial++
                        REPAIR_FAILED -> pFailed++
                        REPAIR_INVALID_EVIDENCE -> pInvalid++
                        REPAIR_NO_AUDIO -> pAudio++
                        else -> pNone++
                    }
                }
            }
        }
        RegionOutcome(rc, rr, pu, pw, pPartial, pFailed, pAudio, pNone, pInvalid)
    }

    /** R9/D2: grava o estado FINAL de uma região (o bloco permanece na timeline). */
    private fun markOutcomeLocked(b: Blk, outcome: Int, promotedWords: Int = 0) {
        b.outcome = outcome
        b.outcomeWords = if (outcome == OUTCOME_PROMOTED_UNCONFIRMED) promotedWords else 0
    }

    val isFinalized: Boolean get() = synchronized(lock) { finalized }

    fun committedText(): String = synchronized(lock) { wordsTextLocked(STATE_COMMITTED) }

    fun provisionalText(): String = synchronized(lock) { wordsTextLocked(STATE_REVIEWABLE) }

    fun committedWordCount(): Int = synchronized(lock) { stateCountLocked(STATE_COMMITTED) }

    fun provisionalWordCount(): Int = synchronized(lock) { stateCountLocked(STATE_REVIEWABLE) }

    /** R7/E2: regiões não resolvidas aguardando terminal (não publicadas). */
    fun heldHypothesisCount(): Int = synchronized(lock) { heldBlocksLocked().size }

    /** R7/E2: palavras retidas em hipóteses não resolvidas. */
    fun heldWordCount(): Int = synchronized(lock) { stateCountLocked(STATE_HELD) }

    /** R7/E2: fechamentos tipados (limite de retenção excedido). */
    fun forcedClosureCount(): Int = synchronized(lock) { forcedClosures }

    /** R7/E2: janelas rejeitadas por geração divergente (fence). */
    fun staleWindowCount(): Int = synchronized(lock) { staleWindows }

    /** R7/E2: fontes de tempo presentes mas inválidas (caíram p/ estimativa). */
    fun invalidTimeWindowCount(): Int = synchronized(lock) { invalidTimeWindows }

    /** R8/B: total de palavras antigas revisadas (acumulado). */
    fun revisedWordCount(): Int = synchronized(lock) { revisedTotal }

    /** R8/B: regiões retidas com reparo aguardando/elegível (chamada única). */
    fun pendingRepairCount(): Int = synchronized(lock) {
        heldBlocksLocked().count { it.repairState == REPAIR_NONE || it.repairState == REPAIR_REQUESTED }
    }

    /** R8/B: bytes de áudio preservado retidos agora (limite de memória). */
    fun retainedAudioBytes(): Int = synchronized(lock) { retainedBytesLocked() }

    /** R8/B: reparos pedidos/ok/falhos (acumulado). */
    fun repairAttemptCount(): Int = synchronized(lock) { repairAttemptsTotal }

    fun repairCompletedCount(): Int = synchronized(lock) { repairedTotal }

    fun repairFailedCount(): Int = synchronized(lock) { repairFailedTotal }

    /** R10/E1: reparos recusados por evidência de tempo inválida. */
    fun repairInvalidEvidenceCount(): Int = synchronized(lock) { repairInvalidEvidenceTotal }

    /** Texto para publicar AGORA no terminal (estável + cauda; retidas ficam
     * fora até resolverem/terminal — nunca publicadas como certas). */
    fun displayText(): String = synchronized(lock) {
        val out = StringBuilder()
        for (b in timeline) {
            for (w in b.words) {
                if (w.state == STATE_HELD) continue
                if (out.isNotEmpty()) out.append(' ')
                out.append(w.word)
            }
        }
        out.toString()
    }

    /**
     * Terminal NORMAL (stop/EOF/fim de fonte): drena a cauda revisável e as
     * regiões retidas UMA vez (resolução ORDENADA, no lugar). Idempotente.
     */
    fun finalize(): CommitInfo = synchronized(lock) {
        if (finalized) return CommitInfo(-1, 0, 0, stateCountLocked(STATE_COMMITTED), "finalize")
        finalized = true
        val added = stateCountLocked(STATE_REVIEWABLE)
        promoteReviewableLocked()
        var regions = 0
        var unresolved = 0
        for (b in heldBlocksLocked()) {
            val hadPending = b.words.any { it.state == STATE_HELD }
            val kept = drainHeldOrderedLocked(b)
            if (kept > 0) {
                unresolved++
                // R9/D2: promovida ao texto SEM confirmação de janela/reparo.
                markOutcomeLocked(b, OUTCOME_PROMOTED_UNCONFIRMED, kept)
            } else if (hadPending) {
                // R9/D2: todas as palavras pendentes casaram com o visível =
                // resolução confirmada (NO LUGAR); não é promoção cega.
                markOutcomeLocked(b, OUTCOME_RESOLVED_CONFIRMED)
            }
            regions++
        }
        val align = if (regions == 0) "finalize" else "finalize+retidas:$regions"
        return CommitInfo(
            -1, added, 0, stateCountLocked(STATE_COMMITTED), align,
            heldHypotheses = 0, heldWords = 0, forcedClosures = forcedClosures,
            unresolvedRegionsClosed = unresolved,
            repairAttempts = repairAttemptsTotal, repairCompleted = repairedTotal,
            repairFailed = repairFailedTotal, retainedAudioBytes = retainedBytesLocked(),
        )
    }

    /**
     * CANCEL: nada mais é commitado; cauda revisável e hipóteses retidas NÃO
     * são publicadas (evita lixo como final). O comprometido permanece.
     */
    fun cancel() = synchronized(lock) {
        cancelled = true
        val it = timeline.iterator()
        while (it.hasNext()) {
            val b = it.next()
            b.words.removeAll { w -> w.state != STATE_COMMITTED }
            b.pcm = null
            if (b.words.isEmpty()) it.remove()
        }
    }

    /**
     * Resultado de uma janela. [text] = transcrição da janela COMPLETA
     * (contexto+único). Retorna null quando nada foi processado (texto vazio,
     * cancelado/terminal, geração divergente). Nunca perde texto em silêncio:
     * o que não pôde ser reconciliado fica RETIDO e sai (deduplicado por
     * ocorrência) no terminal.
     *
     * @param wordTimes tempos por palavra (contagem == nº de palavras, texto
     *   correspondente, ordem e bounds válidos) — caminho exato quando válidos.
     * @param segments segmentos REAIS do JNI (texto+ms na janela), em ordem —
     *   a palavra herda o intervalo do segmento (evidência de intervalo).
     * @param generation id da sessão do job (R7/A1); [GENERATION_UNSPECIFIED] =
     *   não especificado (compatibilidade).
     * @param windowPcm R8/B: PCM (s16le) da janela [ws, u1) — usado SÓ para
     *   preservar o áudio de uma região que ficar retida (re-decode restrito).
     */
    @JvmOverloads
    fun onWindow(
        windowIndex: Int,
        text: String,
        uniqueStartSample: Long,
        uniqueEndSample: Long,
        contextSamplesInChunk: Int,
        wordTimes: List<WordTime>? = null,
        segments: List<LiveSegmentTime>? = null,
        generation: Long = GENERATION_UNSPECIFIED,
        windowPcm: ByteArray? = null,
    ): CommitInfo? = synchronized(lock) {
        // R16/C1 — CAPTURA na fronteira, antes de qualquer mutação.
        if (CommitCapture.ativa) {
            CommitCapture.write(mapOf(
                "ev" to "window_in",
                "gen" to generation,
                "idx" to windowIndex,
                "t0" to uniqueStartSample,
                "t1" to uniqueEndSample,
                "ctx" to contextSamplesInChunk,
                "texto" to text,
                "wordTimes" to CommitCapture.wordTimesJson(wordTimes),
                "segments" to CommitCapture.segmentsJson(segments),
                "pcmBytes" to (windowPcm?.size ?: -1),
                "pcmSha256" to (windowPcm?.let { CommitCapture.sha256(it) } ?: "")
            ))
        }
        if (cancelled || finalized) return null
        if (generation != GENERATION_UNSPECIFIED) {
            if (currentGeneration != GENERATION_UNSPECIFIED && generation != currentGeneration) {
                staleWindows++
                return null
            }
            currentGeneration = generation
        }
        val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return null

        val uniqueLen = (uniqueEndSample - uniqueStartSample).coerceAtLeast(0L)
        val ctxSamples = contextSamplesInChunk.coerceAtLeast(0).toLong()
        val uniqueStart = uniqueStartSample
        val ws = uniqueStartSample - ctxSamples
        val windowLen = ctxSamples + uniqueLen
        val windowMs = windowLen / 16L

        // ---------------------------------------------------------------- evidência
        val placed = ArrayList<W>(words.size)
        var evidence = false
        var usedTimes = false
        if (wordTimes != null) {
            val ok = wordTimes.size == words.size &&
                wordTimes.indices.all { norm(wordTimes[it].word) == norm(words[it]) } &&
                wordTimes.all { it.t0Ms >= 0 && it.t1Ms >= it.t0Ms && it.t1Ms <= windowMs + TIME_SLACK_MS } &&
                wordTimes.zipWithNext().all { (a, b) -> a.t0Ms <= b.t0Ms }
            if (ok) {
                usedTimes = true
                evidence = true
                for (i in words.indices) {
                    val t = wordTimes[i]
                    val a0 = ws + t.t0Ms * 16L
                    val a1 = ws + t.t1Ms * 16L
                    placed.add(
                        W(words[i], KIND_TIME, a0, a1, a1, (a1 - a0).coerceAtLeast(1L), STATE_REVIEWABLE, windowIndex)
                    )
                }
            } else {
                invalidTimeWindows++
            }
        }
        if (!usedTimes && segments != null) {
            val segWords = segments.flatMap { splitWords(it.text) }
            val ok = segments.isNotEmpty() &&
                segWords.size == words.size &&
                segWords == words &&
                segments.all { it.t0Ms >= 0 && it.t1Ms > it.t0Ms && it.t1Ms <= windowMs + TIME_SLACK_MS } &&
                segments.zipWithNext().all { (a, b) -> a.t0Ms <= b.t0Ms && a.t1Ms <= b.t1Ms }
            if (ok) {
                evidence = true
                var i = 0
                for (s in segments) {
                    val sw = splitWords(s.text)
                    val a0 = ws + s.t0Ms * 16L
                    val a1 = ws + s.t1Ms * 16L
                    for (k in sw.indices) {
                        placed.add(W(words[i], KIND_SEG, a0, a1, a1, (a1 - a0).coerceAtLeast(1L), STATE_REVIEWABLE, windowIndex))
                        i++
                    }
                }
            } else {
                invalidTimeWindows++
            }
        }
        if (placed.isEmpty()) {
            val mWords = maxOf(1, words.size)
            val step = windowLen / mWords
            for (i in words.indices) {
                placed.add(
                    W(words[i], KIND_EST, 0L, 0L, ws + windowLen * (i + 1) / mWords, step, STATE_REVIEWABLE, windowIndex)
                )
            }
        }
        for (w in placed) {
            w.windowIndex = windowIndex
            w.winA0 = ws
            w.winA1 = ws + windowLen
            // R11/B2: fronteira CONTEXTO (re-decodificável) vs
            // TRECHO ÚNICO (conteúdo novo) da janela de origem.
            w.winU0 = uniqueStart
        }

        // ------------------------------------------------- R8/B: resolução ordenada de retidas cobertas
        // (ANTES da referência: as palavras resolvidas passam a valer como
        // referência desta janela, no lugar delas)
        var heldResolvedNow = 0
        if (placed.isNotEmpty()) {
            heldResolvedNow = resolveHeldCoveredByWindowLocked(placed, ws, ws + windowLen)
        }

        // ------------------------------------------------- referência (visível, em ordem)
        val ref = visibleWordsLocked()
        val refNorm = ref.map { norm(it.word) }
        val decoNorm = placed.map { norm(it.word) }

        val expected = ref.count { it.endEst > ws }
        val estCap = maxOf(0, Math.ceil(ctxSamples / 16000.0 * maxWordsPerSecond).toInt() + 1)

        // ---------------------------------------------------------------- reconciliação
        var added = 0
        var revisedNow = 0
        var conflictsNow = 0
        val alignment: String
        if (evidence) {
            // Corte pelo ÁUDIO (evidência de tempo): a parte comprovadamente
            // coberta é a duplicação; a palavra que cruza a fronteira fica (é
            // começo do único).
            val firstNew = placed.indexOfFirst { it.a1 > uniqueStart }
            val kTime = if (firstNew >= 0) firstNew else placed.size
            // R7/E2: com tempos de SEGMENTO a palavra não tem tempo exato — o
            // segmento que cruza a fronteira explica a duplicação só por TEXTO.
            val kStartUnique = placed.indexOfFirst { it.a0 >= uniqueStart }
            val kMaxText = if (kStartUnique >= 0) kStartUnique else placed.size
            var kText = -1
            var kTextHead = 0
            var i = minOf(kMaxText, refNorm.size, decoNorm.size)
            while (i >= 1) {
                if (decoNorm.take(i) == refNorm.takeLast(i)) {
                    kText = i
                    break
                }
                i--
            }
            if (kText < 0) {
                var j = 1
                while (j <= 2 && j < kMaxText) {
                    var m = minOf(kMaxText - j, refNorm.size, decoNorm.size - j)
                    while (m >= 1) {
                        var eq = true
                        for (x in 0 until m) {
                            if (decoNorm[j + x] != refNorm[refNorm.size - m + x]) {
                                eq = false
                                break
                            }
                        }
                        if (eq) break
                        m--
                    }
                    if (m >= 1) {
                        kText = j + m
                        kTextHead = j
                        break
                    }
                    j++
                }
            }
            if (kText < 0) {
                val floor = maxOf(3, minOverlapWords)
                var j = minOf(kMaxText, refNorm.size, decoNorm.size)
                while (j >= floor) {
                    var diff = 0
                    for (x in 0 until j) {
                        if (decoNorm[x] != refNorm[refNorm.size - j + x]) diff++
                    }
                    if (diff <= 1) {
                        kText = j
                        break
                    }
                    j--
                }
            }
            val k = maxOf(kTime, kText.coerceAtLeast(0)).coerceAtMost(placed.size)
            // Sem referência não existe duplicação possível: nada é consumido.
            val kEffective = if (ref.isEmpty()) 0 else k
            // R8/B: o bloco consumido é CLASSIFICADO por alinhamento — divergência
            // com áudio provado revisa a palavra provisória antiga (não a engole);
            // o que não casa vira inserção ou retenção tipada.
            val provBefore = stateCountLocked(STATE_REVIEWABLE)
            if (kEffective > 0) {
                val res = classifyConsumedLocked(
                    ArrayList(placed.subList(0, kEffective)), ref, windowIndex,
                )
                revisedNow += res.revised
                conflictsNow += res.held
                if (res.held > 0) {
                    holdRegionForWordsLocked(
                        windowIndex, res.heldWordsSnapshot, windowPcm, ws, uniqueStartSample, uniqueEndSample,
                    )
                }
            }
            if (kEffective >= provBefore && provBefore > 0) {
                added += promoteReviewableLocked()
            }
            // Dedup de fronteira (R7/E2 preservado): palavra do início do "resto"
            // com tempo REAL que re-decodifica (áudio sobreposto) a MESMA palavra
            // da cauda pendente REMOVE a cópia antiga — a nova entra no lugar
            // (texto uma vez só).
            if (stateCountLocked(STATE_REVIEWABLE) > 0) {
                var ri = kEffective
                while (ri < placed.size && stateCountLocked(STATE_REVIEWABLE) > 0) {
                    val w = placed[ri]
                    val tail = lastReviewableLocked() ?: break
                    if (norm(w.word) != norm(tail.word)) break
                    val tailStart = tail.endEst - tail.step
                    if (!(w.a0 < tail.endEst && w.a1 > tailStart)) break
                    tail.blk?.words?.remove(tail)
                    ri++
                }
            }
            // Cabeça que TOCA o contexto (segmento cruzando a fronteira) SEM
            // prova: NÃO cortar por posição — vira hipótese RETIDA (revisável;
            // resolvida por janela mais nova ou terminal, no lugar).
            var heldPrefix = 0
            if (k == 0 && kMaxText > 0) {
                heldPrefix = kMaxText
                holdRegionLocked(windowIndex, ArrayList(placed.subList(0, heldPrefix)), windowPcm, ws, uniqueStartSample, uniqueEndSample)
                conflictsNow += heldPrefix
            } else if (kTextHead > 0 && kTime == 0 && kText == k) {
                heldPrefix = kTextHead
                holdRegionLocked(windowIndex, ArrayList(placed.subList(0, heldPrefix)), windowPcm, ws, uniqueStartSample, uniqueEndSample)
                conflictsNow += heldPrefix
            }
            val restStart = maxOf(kEffective, heldPrefix).coerceAtMost(placed.size)
            val restBlk = Blk(windowIndex, ArrayList())
            for (i2 in restStart until placed.size) {
                val w = placed[i2]
                w.blk = restBlk
                restBlk.words.add(w)
            }
            var restAdded = 0
            if (stateCountLocked(STATE_REVIEWABLE) == 0) {
                val commitCount = (restBlk.words.size - tailGuardWords).coerceAtLeast(0)
                for (i2 in restBlk.words.indices) {
                    if (i2 < commitCount) {
                        restBlk.words[i2].state = STATE_COMMITTED
                        restAdded++
                    } else {
                        restBlk.words[i2].state = STATE_REVIEWABLE
                    }
                }
            } else {
                for (w in restBlk.words) w.state = STATE_REVIEWABLE
            }
            if (restBlk.words.isNotEmpty()) timeline.add(restBlk)
            added += restAdded
            alignment = (if (usedTimes) "tempos:$k" else "segmentos:$k") + "(evidencia)" +
                (if (heldPrefix > 0) "+retido:$heldPrefix" else "")
        } else {
            // Sem tempos: casamento normalizado contra a referência visível,
            // teto = capacidade estimada do contexto.
            val kMax = minOf(decoNorm.size, refNorm.size, estCap)
            var found = -1
            var tolerantCut = false
            var i = kMax
            while (i >= 1) {
                if (decoNorm.take(i) == refNorm.takeLast(i)) {
                    found = i
                    break
                }
                i--
            }
            if (found < 0) {
                val floor = maxOf(3, minOverlapWords)
                var j = kMax
                while (j >= floor) {
                    var diff = 0
                    for (x in 0 until j) {
                        if (decoNorm[x] != refNorm[refNorm.size - j + x]) diff++
                    }
                    if (diff <= 1) {
                        found = j
                        tolerantCut = true
                        break
                    }
                    j--
                }
            }
            if (found >= 0) {
                // R8/B: mesma classificação por alinhamento do ramo com evidência.
                val provBefore = stateCountLocked(STATE_REVIEWABLE)
                val res = classifyConsumedLocked(
                    ArrayList(placed.subList(0, found)), ref, windowIndex,
                )
                revisedNow += res.revised
                conflictsNow += res.held
                if (res.held > 0) {
                    holdRegionForWordsLocked(
                        windowIndex, res.heldWordsSnapshot, windowPcm, ws, uniqueStartSample, uniqueEndSample,
                    )
                }
                if (found >= provBefore && provBefore > 0) {
                    added += promoteReviewableLocked()
                }
                val restStart = found
                val restBlk = Blk(windowIndex, ArrayList())
                for (i2 in restStart until placed.size) {
                    val w = placed[i2]
                    w.blk = restBlk
                    restBlk.words.add(w)
                }
                var restAdded = 0
                if (stateCountLocked(STATE_REVIEWABLE) == 0) {
                    val commitCount = (restBlk.words.size - tailGuardWords).coerceAtLeast(0)
                    for (i2 in restBlk.words.indices) {
                        if (i2 < commitCount) {
                            restBlk.words[i2].state = STATE_COMMITTED
                            restAdded++
                        } else {
                            restBlk.words[i2].state = STATE_REVIEWABLE
                        }
                    }
                } else {
                    for (w in restBlk.words) w.state = STATE_REVIEWABLE
                }
                if (restBlk.words.isNotEmpty()) timeline.add(restBlk)
                added += restAdded
                alignment =
                    if (tolerantCut) "tolerante:$found(esp=$expected)" else "texto:$found(esp=$expected)"
            } else {
                // Sem casamento: NADA é cortado por posição/estimativa.
                // 1) só o prefixo IRREVOGÁVEL da cauda (nenhuma janela futura
                //    volta a cobrir o áudio: ws já passou do fim estimado).
                while (true) {
                    val head = firstReviewableLocked() ?: break
                    if (head.endEst - head.step <= ws) {
                        head.state = STATE_COMMITTED
                        added++
                    } else {
                        break
                    }
                }
                val hasOverlap = ctxSamples > 0L && ref.any { it.endEst > ws }
                // R8/B: palavras com tempo REAL integralmente no ÚNICO são
                // conteúdo novo comprovado — não são retidas só por a janela
                // ter contexto (o áudio delas não é re-decode de nada antigo).
                val allUniqueProven = placed.isNotEmpty() &&
                    placed.all { it.kind != KIND_EST && it.a0 >= uniqueStart }
                if (!hasOverlap || allUniqueProven) {
                    // Nada deste áudio é re-decodificado por janelas anteriores
                    // (ou tudo é único comprovado): anexa.
                    val commitCount = (placed.size - tailGuardWords).coerceAtLeast(0)
                    val restBlk = Blk(windowIndex, ArrayList())
                    for (i2 in placed.indices) {
                        val w = placed[i2]
                        if (i2 < commitCount) {
                            w.state = STATE_COMMITTED
                            added++
                        }
                        w.blk = restBlk
                        restBlk.words.add(w)
                    }
                    timeline.add(restBlk)
                    alignment = "anexo(esp=$expected)"
                } else {
                    // Parte coberta NÃO reconciliada: retém hipótese revisável e
                    // preserva a cauda; nada é cortado nem descartado.
                    holdRegionLocked(windowIndex, placed, windowPcm, ws, uniqueStartSample, uniqueEndSample)
                    conflictsNow += placed.size
                    alignment = "retido:${placed.size}(esp=$expected)"
                }
            }
        }
        return CommitInfo(
            windowIndex, added, stateCountLocked(STATE_REVIEWABLE), stateCountLocked(STATE_COMMITTED), alignment,
            heldHypotheses = heldBlocksLocked().size, heldWords = stateCountLocked(STATE_HELD),
            forcedClosures = forcedClosures,
            revisedWords = revisedNow, conflictsHeld = conflictsNow, heldResolved = heldResolvedNow,
            pendingRepairRegions = pendingRepairCountLocked(),
            repairAttempts = repairAttemptsTotal, repairCompleted = repairedTotal,
            repairFailed = repairFailedTotal, retainedAudioBytes = retainedBytesLocked(),
        )
    }

    // ================================================================ R8/B: reparo

    /**
     * R8/B: próximo pedido de re-decodição restrita (região retida com áudio
     * preservado e ainda não pedida). Chamar FORA do lock do committer (o
     * chamador decodifica fora); no máximo UMA tentativa por região — sem loop.
     */
    fun takeRepairRequest(): RepairRequest? = synchronized(lock) {
        for (b in timeline) {
            if (b.repairState != REPAIR_NONE) continue
            val words = b.words.filter { it.state == STATE_HELD }
            if (words.isEmpty()) continue
            val pcm = b.pcm ?: continue
            b.repairState = REPAIR_REQUESTED
            repairAttemptsTotal++
            // R16/C1 — captura do reparo na fronteira (pedido).
            if (CommitCapture.ativa) {
                CommitCapture.write(mapOf(
                    "ev" to "repair",
                    "gen" to currentGeneration,
                    "idx" to b.windowIndex,
                    "t1" to b.regionStart,
                    "t2" to b.regionEnd,
                    "ok" to false,
                    "fase" to "pedido",
                    "regiao" to b.regionId
                ))
            }
            return RepairRequest(
                b.regionId, b.windowIndex, b.regionStart, b.regionEnd, pcm,
                words.joinToString(" ") { it.word }, words.size,
            )
        }
        null
    }

    /**
     * R8/B + R9/D-fix: resultado da re-decodição restrita. Mescla por alinhamento
     * com a hipótese retida: palavra reproduzida confirma; palavra nova da região
     * entra; omissão NÃO descarta nada. Resolução completa vira texto visível
     * NO LUGAR; parcial mantém retida (fechamento tipado no terminal) — nunca
     * declara sucesso integral sem cobrir a hipótese.
     *
     * R9/D-fix — GATE DE ANCORAGEM: se a leitura do reparo NÃO reproduz uma
     * fração mínima ([minRepairAnchorRatio]/[minRepairAnchorWords]) da hipótese
     * retida (divergiu — tipicamente porque antes se re-decodificava áudio
     * alheio), o reparo é REJEITADO: NADA do texto do reparo é adotado, a
     * hipótese do modelo permanece provisória (HELD) e a região sai no terminal
     * como PROMOVIDA SEM CONFIRMAÇÃO (resultado PARCIAL). Nunca adota lixo.
     *
     * R10/E1 — EVIDÊNCIA DE TEMPO: quando [segments] é informado e NÃO-vaio, ele
     * tem de cumprir o contrato da região (mesma tokenização do texto, t0 >= 0,
     * t1 > t0, AMBOS dentro do áudio preservado com [TIME_SLACK_MS], início E fim
     * ordenados e sem overflow). Evidência INVÁLIDA → REJEIÇÃO TIPADA
     * (`REPAIR_INVALID_EVIDENCE`, contada em
     * [RegionOutcome.promotedRepairInvalidEvidence]): não se clampa em silêncio o
     * segmento absurdo, o reparo não é adotado e a hipótese/PCM da região
     * permanecem (o terminal fecha como promovida sem confirmação) — evidência
     * inválida NUNCA vira "tempo real confirmado". [segments] ausente/vazio é
     * "sem evidência de tempo": a colocação usa estimativa (como antes) e a
     * confirmação continua ancorada no PCM PRÓPRIO da região (mesma ocorrência).
     */
    fun completeRepair(regionId: Long, text: String, segments: List<LiveSegmentTime>? = null): Boolean =
        synchronized(lock) {
            val b = timeline.firstOrNull { it.regionId == regionId && it.repairState == REPAIR_REQUESTED }
                ?: return false
            val regionWords = b.words.filter { it.state == STATE_HELD }
            if (regionWords.isEmpty()) return false
            val repairWords = splitWords(text)
            if (repairWords.isEmpty()) {
                b.repairState = REPAIR_FAILED
                repairFailedTotal++
                return false
            }
            val windowLen = (b.regionEnd - b.regionStart).coerceAtLeast(1L)
            val m = repairWords.size.toLong()
            val built = ArrayList<W>(repairWords.size)
            // R10/E1 — CONTRATO DE EVIDÊNCIA DE TEMPO DA REGIÃO (mesmo contrato do
            // seam da janela em [onWindow]): o texto do reparo tem de ser a MESMA
            // tokenização dos segmentos informados, os segmentos não podem ser
            // vazios, t0 >= 0, t1 > t0, t1 DENTRO do áudio preservado da região
            // ([regionSamples] + [TIME_SLACK_MS], o mesmo slack do seam) e início
            // E fim ORDENADOS. A conversão ms→amostras é validada ANTES de
            // multiplicar (nada de overflow) e a origem é [regionStart] (o PCM do
            // reparo é o recorte da região).
            val regionSamples = windowLen
            val segEvidence = segments != null && segments.isNotEmpty()
            val segValid = segEvidence &&
                segments!!.flatMap { splitWords(it.text) } == repairWords &&
                segments!!.all { segWithinRegion(it.t0Ms, it.t1Ms, regionSamples) } &&
                segments!!.zipWithNext().all { (x, y) -> x.t0Ms <= y.t0Ms && x.t1Ms <= y.t1Ms }
            if (segEvidence && !segValid) {
                // R10/E1 — EVIDÊNCIA INVÁLIDA (fim fora do áudio da região, fim <=
                // início, negativo, fora à esquerda/direita, finais fora de ordem,
                // texto divergente do informado, overflow): REJEIÇÃO TIPADA. NÃO se
                // clampa o segmento absurdo nem se adota o reparo; a hipótese e o
                // PCM da região permanecem (o terminal fecha como promovida sem
                // confirmação). Evidência inválida NUNCA vira "tempo real
                // confirmado" e o reparo NÃO é re-tentado (uma tentativa/região).
                b.repairState = REPAIR_INVALID_EVIDENCE
                repairInvalidEvidenceTotal++
                return false
            }
            if (segValid) {
                for (s in segments!!) {
                    val sw = splitWords(s.text)
                    val s0 = msToSamples(s.t0Ms) ?: return false // inalcançável: validado acima
                    val s1 = msToSamples(s.t1Ms) ?: return false
                    val a0 = b.regionStart + s0
                    val a1 = b.regionStart + s1
                    for (w in sw) built.add(W(w, KIND_SEG, a0, a1, a1, (a1 - a0).coerceAtLeast(1L), STATE_HELD, b.windowIndex))
                }
            } else {
                // R11/B2 — ESTIMATIVA ANCORADA NO FRAME DAS PALAVRAS
                // RETIDAS (âncora local da MESMA ocorrência). O reparo
                // re-decodifica o áudio PRÓPRIO da região, que é a
                // MESMA ocorrência das palavras retidas — mas o
                // estimate uniforme do reparo é relativo ao intervalo
                // da região (regionStart..regionEnd), enquanto o das
                // retidas é relativo à JANELA (winA0..winA1). São
                // frames DIFERENTES: sem este ajuste os spans NÃO se
                // sobrepõem e occCompatible() recusa o casamento da
                // MESMA ocorrência (o reparo não resolvia a região:
                // R8B-R1/R4 quebravam). Ancoramos cada palavra do
                // reparo no intervalo ESTIMADO da palavra retida da
                // mesma posição (o slot i da hipótese), preservando o
                // frame da hipótese — a posição relativa dentro da
                // ocorrência é a âncora, não o frame absoluto.
                val slotWords = regionWords.sortedBy { span0(it) }
                for (i in repairWords.indices) {
                    val anchor = slotWords.getOrNull(i)
                    val (a0, a1) = if (anchor != null) {
                        val s0 = span0(anchor)
                        val s1 = span1(anchor)
                        // estimativa uniforme dentro do slot da hipótese
                        val len = (s1 - s0).coerceAtLeast(1L)
                        val sub = len / m.coerceAtLeast(1L)
                        s0 to (s0 + sub * (i + 1)).coerceAtLeast(s0 + 1)
                    } else {
                        val w0 = b.regionStart + windowLen * i / m
                        val w1 = b.regionStart + windowLen * (i + 1) / m
                        w0 to w1.coerceAtLeast(w0 + 1)
                    }
                    built.add(
                        W(
                            repairWords[i], KIND_EST, 0L, 0L, a1,
                            (a1 - a0).coerceAtLeast(1L), STATE_HELD, b.windowIndex,
                        )
                    )
                }
            }
            for (w in built) {
                // R10/E2: proveniência do reparo = áudio PRÓPRIO da região.
                w.winA0 = b.regionStart
                w.winA1 = b.regionEnd
            }
            // R9/D-fix — GATE DE ANCORAGEM (só para regiões cujo áudio foi
            // RESTRITO ao áudio próprio do chunk: a leitura de um recorte pode
            // divergir; quantas palavras da HIPÓTESE ela reproduz?).
            val unmatchedHypothesis = mergeAlignedLocked(regionWords, built)
            val matchedAnchor = regionWords.size - unmatchedHypothesis.size
            val requiredAnchor = maxOf(
                minRepairAnchorWords,
                Math.ceil(regionWords.size * minRepairAnchorRatio).toInt(),
            )
            if (b.audioRestricted && regionWords.size >= 2 && matchedAnchor < requiredAnchor) {
                // DIVERGENTE: não ancora na região. REJEITA — não adota lixo; a
                // hipótese do modelo fica provisória (HELD) e a região sai como
                // resultado PARCIAL no terminal (estado tipado REPAIR_FAILED).
                b.repairState = REPAIR_FAILED
                repairFailedTotal++
                return false
            }
            // mescla: hipótese retida × leitura do reparo — casamento confirma
            // (uma cópia, na ordem do alinhamento); o reparo acrescenta o que a
            // hipótese não tinha; omissão da hipótese é preservada (nada some).
            val merged = mergeRepairLocked(regionWords, built)
            for (w in merged) w.blk = b
            b.words.clear()
            b.words.addAll(merged)
            b.pcm = null
            repairedTotal++
            b.repairState = REPAIR_COMPLETED
            if (unmatchedHypothesis.isEmpty()) {
                // R9/D2: reparo ACEITO — cobriu toda a hipótese (resolvida por reparo).
                markOutcomeLocked(b, OUTCOME_RESOLVED_BY_REPAIR)
            }
            // Não-cobridor (parcial) NÃO é estado próprio: a região segue retida e,
            // se drenada no terminal, sai como PROMOVIDA SEM CONFIRMAÇÃO com o
            // motivo reparo_parcial (derivado de repairState==REPAIR_COMPLETED).
            if (unmatchedHypothesis.isEmpty()) {
                // resolução completa: toda a hipótese foi reproduzida pelo
                // reparo — publica NO LUGAR, sem duplicar o que o restante do
                // job já mostrou para o mesmo áudio (dedup por ocorrência).
                val publish = dedupAgainstVisibleLocked(merged, b)
                val publishSet = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<W, Boolean>())
                publishSet.addAll(publish)
                val keep = ArrayList<W>(publish.size)
                for (w in merged) {
                    if (publishSet.contains(w)) {
                        w.state = STATE_REVIEWABLE
                        keep.add(w)
                    }
                }
                b.words.clear()
                b.words.addAll(keep)
            }
            // R16/C1 — captura do reparo na fronteira (resultado aceito).
            if (CommitCapture.ativa) {
                CommitCapture.write(mapOf(
                    "ev" to "repair",
                    "gen" to currentGeneration,
                    "idx" to b.windowIndex,
                    "t1" to b.regionStart,
                    "t2" to b.regionEnd,
                    "ok" to true,
                    "fase" to "resultado",
                    "regiao" to b.regionId
                ))
            }
            true
        }

    /** R8/B: re-decode falhou/indisponível — fechamento TIPADO da região. */
    fun failRepair(regionId: Long, reason: String): Boolean = synchronized(lock) {
        val b = timeline.firstOrNull { it.regionId == regionId && it.repairState == REPAIR_REQUESTED }
            ?: return false
        b.repairState = REPAIR_FAILED
        repairFailedTotal++
        // R16/C1 — captura do reparo na fronteira (falha tipada).
        if (CommitCapture.ativa) {
            CommitCapture.write(mapOf(
                "ev" to "repair",
                "gen" to currentGeneration,
                "idx" to b.windowIndex,
                "t1" to b.regionStart,
                "t2" to b.regionEnd,
                "ok" to false,
                "fase" to "falha",
                "regiao" to b.regionId,
                "causa" to reason
            ))
        }
        true
    }

    // ================================================================ helpers

    private fun visibleWordsLocked(): ArrayList<W> {
        val out = ArrayList<W>()
        for (b in timeline) for (w in b.words) if (w.state != STATE_HELD) out.add(w)
        return out
    }

    private fun heldBlocksLocked(): ArrayList<Blk> {
        val out = ArrayList<Blk>()
        for (b in timeline) if (b.words.any { it.state == STATE_HELD }) out.add(b)
        return out
    }

    private fun stateCountLocked(state: Int): Int {
        var n = 0
        for (b in timeline) for (w in b.words) if (w.state == state) n++
        return n
    }

    private fun wordsTextLocked(state: Int): String {
        val out = StringBuilder()
        for (b in timeline) {
            for (w in b.words) {
                if (w.state != state) continue
                if (out.isNotEmpty()) out.append(' ')
                out.append(w.word)
            }
        }
        return out.toString()
    }

    private fun firstReviewableLocked(): W? {
        for (b in timeline) for (w in b.words) if (w.state == STATE_REVIEWABLE) return w
        return null
    }

    private fun lastReviewableLocked(): W? {
        for (bi in timeline.indices.reversed()) {
            val b = timeline[bi]
            for (wi in b.words.indices.reversed()) {
                val w = b.words[wi]
                if (w.state == STATE_REVIEWABLE) return w
            }
        }
        return null
    }

    private fun promoteReviewableLocked(): Int {
        var n = 0
        for (b in timeline) for (w in b.words) if (w.state == STATE_REVIEWABLE) {
            w.state = STATE_COMMITTED
            n++
        }
        return n
    }

    private fun pendingRepairCountLocked(): Int =
        heldBlocksLocked().count { it.repairState == REPAIR_NONE || it.repairState == REPAIR_REQUESTED }

    private fun retainedBytesLocked(): Int {
        var n = 0
        for (b in timeline) n += (b.pcm?.size ?: 0)
        return n
    }

    /**
     * R8/B: classifica o bloco CONSUMIDO (corte por áudio/texto) por
     * alinhamento LCS com as palavras cobertas da referência:
     *  - casamento → duplicação comprovada (o texto antigo permanece);
     *  - divergência emparelhada com áudio provado (≥1 tempo real + overlap) e
     *    antiga revisável → SUBSTITUI o texto antigo (revisão contabilizada);
     *    antiga committed → RETÉM (fronteira de estabilidade);
     *  - divergente sem par, entre dois casamentos com lado esquerdo revisável
     *    → INSERE no bloco (palavra recuperada);
     *  - resto → RETIDO (nada some em silêncio).
     */
    private class ClassifyResult(val revised: Int, val held: Int, val heldWordsSnapshot: ArrayList<W>)

    private fun classifyConsumedLocked(consumed: List<W>, refCovered: List<W>, windowIndex: Int): ClassifyResult {
        val n = consumed.size
        val m = refCovered.size
        if (n == 0 || m == 0) return ClassifyResult(0, 0, ArrayList())
        val cn = consumed.map { norm(it.word) }
        val rn = refCovered.map { norm(it.word) }
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                dp[i][j] = if (cn[i] == rn[j] && occCompatible(consumed[i], refCovered[j])) {
                    dp[i + 1][j + 1] + 1
                } else {
                    maxOf(dp[i + 1][j], dp[i][j + 1])
                }
            }
        }
        val matchC = IntArray(n) { -1 } // consumed idx → refCovered idx
        val matchR = IntArray(m) { -1 } // refCovered idx → consumed idx
        var i = 0
        var j = 0
        while (i < n && j < m) {
            if (cn[i] == rn[j] && occCompatible(consumed[i], refCovered[j]) && dp[i][j] == dp[i + 1][j + 1] + 1) {
                matchC[i] = j
                matchR[j] = i
                i++
                j++
            } else if (dp[i + 1][j] >= dp[i][j + 1]) {
                i++
            } else {
                j++
            }
        }
        fun slotBounds(ci: Int): Pair<Int, Int> {
            var left = -1
            var right = m
            var sj = 0
            while (sj < m) {
                if (matchR[sj] >= 0) {
                    if (matchR[sj] < ci) left = sj else {
                        right = sj
                        break
                    }
                }
                sj++
            }
            return left to right
        }
        var revised = 0
        val heldWords = ArrayList<W>()
        for (ci in 0 until n) {
            if (matchC[ci] >= 0) continue
            val cw = consumed[ci]
            val (leftRef, rightRef) = slotBounds(ci)
            val slotRef = ArrayList<Int>()
            for (rj in leftRef + 1 until rightRef) if (matchR[rj] < 0) slotRef.add(rj)
            var ord = 0
            for (ck in 0 until ci) {
                if (matchC[ck] >= 0) continue
                val (cl, cr) = slotBounds(ck)
                if (cl == leftRef && cr == rightRef) ord++
            }
            val target = slotRef.getOrNull(ord)
            if (target != null) {
                val rw = refCovered[target]
                if (occCompatibleReal(cw, rw)) {
                    if (rw.state == STATE_REVIEWABLE) {
                        rw.word = cw.word
                        revised++
                        revisedTotal++
                        continue
                    } else {
                        cw.state = STATE_HELD
                        heldWords.add(cw)
                        continue
                    }
                }
            }
            // inserção: entre dois casamentos, com o lado esquerdo revisável
            if (leftRef >= 0 && rightRef < m) {
                val leftW = refCovered[leftRef]
                if (leftW.state == STATE_REVIEWABLE) {
                    val blk = leftW.blk
                    if (blk != null) {
                        val idx = blk.words.indexOf(leftW)
                        if (idx >= 0) {
                            cw.state = STATE_REVIEWABLE
                            cw.blk = blk
                            cw.windowIndex = windowIndex
                            blk.words.add(idx + 1, cw)
                            continue
                        }
                    }
                }
            }
            cw.state = STATE_HELD
            heldWords.add(cw)
        }
        return ClassifyResult(revised, heldWords.size, heldWords)
    }

    /**
     * R8/B: resolução ORDENADA de retidas cobertas por esta janela. Uma região
     * só resolve quando TODAS as suas palavras pendentes são reproduzidas
     * (mesma ocorrência). Nesse caso as palavras consumidas da janela saem do
     * fluxo novo (dedup por ocorrência) e a hipótese vira visível NO LUGAR.
     * Parcial não consome nada (a próxima janela/terminal resolve).
     */
    private fun resolveHeldCoveredByWindowLocked(placed: ArrayList<W>, ws: Long, u1: Long): Int {
        var resolved = 0
        val consumed = BooleanArray(placed.size)
        for (b in ArrayList(heldBlocksLocked())) {
            val heldWords = b.words.filter { it.state == STATE_HELD }
            if (heldWords.isEmpty()) continue
            var rStart = Long.MAX_VALUE
            var rEnd = Long.MIN_VALUE
            for (w in heldWords) {
                rStart = minOf(rStart, span0(w))
                rEnd = maxOf(rEnd, w.endEst)
            }
            if (!(rStart < u1 && rEnd > ws)) continue
            val matchedPlaced = ArrayList<Int>()
            var pi = 0
            var okAll = true
            for (hw in heldWords) {
                var found = -1
                var j = pi
                while (j < placed.size) {
                    if (!consumed[j] && norm(placed[j].word) == norm(hw.word) && occCompatible(hw, placed[j])) {
                        found = j
                        break
                    }
                    j++
                }
                if (found >= 0) {
                    matchedPlaced.add(found)
                    pi = found + 1
                } else {
                    okAll = false
                    break
                }
            }
            if (!okAll) continue
            for (idx in matchedPlaced) consumed[idx] = true
            // publica NO LUGAR sem duplicar o que outras janelas já mostraram
            // para o mesmo áudio (dedup por ocorrência)
            val publishable = dedupAgainstVisibleLocked(heldWords, b)
            val publishSet = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<W, Boolean>())
            publishSet.addAll(publishable)
            val keep = ArrayList<W>(b.words.size)
            for (w in b.words) {
                if (w.state == STATE_HELD) {
                    if (publishSet.contains(w)) {
                        w.state = STATE_COMMITTED
                        keep.add(w)
                    }
                } else {
                    keep.add(w)
                }
            }
            b.words.clear()
            b.words.addAll(keep)
            b.pcm = null
            resolved++
            // R9/D2: região resolvida por confirmação de janela mais nova.
            markOutcomeLocked(b, OUTCOME_RESOLVED_CONFIRMED)
        }
        if (resolved > 0) {
            val keep = ArrayList<W>(placed.size)
            for (idx in placed.indices) if (!consumed[idx]) keep.add(placed[idx])
            placed.clear()
            placed.addAll(keep)
        }
        return resolved
    }

    /** R8/B: retenção das palavras retidas pela classificação (conflitos). */
    private fun holdRegionForWordsLocked(
        windowIndex: Int,
        words: ArrayList<W>,
        windowOpt: ByteArray?,
        ws: Long,
        uniqueStart: Long,
        uniqueEnd: Long,
    ) {
        if (words.isEmpty()) return
        val blk = Blk(windowIndex, ArrayList(words))
        blk.regionId = nextRegionId++
        for (w in words) {
            w.state = STATE_HELD
            w.blk = blk
        }
        attachRegionLocked(blk, words, windowOpt, ws, uniqueStart, uniqueEnd)
        timeline.add(blk)
        enforceHeldLimitsLocked()
    }

    /**
     * Retenção (R8/B): hipótese de região não resolvida com áudio PRESERVADO
     * (limites por região e total) e limite de retenções com fechamento tipado
     * da mais antiga (também ordenado, no lugar).
     */
    private fun holdRegionLocked(
        windowIndex: Int,
        words: List<W>,
        windowOpt: ByteArray?,
        ws: Long,
        uniqueStart: Long,
        uniqueEnd: Long,
    ) {
        val blk = Blk(windowIndex, ArrayList(words))
        blk.regionId = nextRegionId++
        for (w in words) {
            w.state = STATE_HELD
            w.blk = blk
        }
        attachRegionLocked(blk, words, windowOpt, ws, uniqueStart, uniqueEnd)
        timeline.add(blk)
        enforceHeldLimitsLocked()
    }

    /**
     * R8/B + R9/D-fix — INTERVALO e ÁUDIO da região retida (re-decode restrito).
     *
     * O intervalo é o das palavras PRÓPRIAS da região (tempos reais; sem tempo
     * real NÃO se usa a janela inteira) RESTRITO ao áudio PRÓPRIO do chunk
     * `[uniqueStart, uniqueEnd)`: o contexto `[ws, uniqueStart)` é áudio JÁ
     * comprometido por janelas anteriores — re-decodificá-lo devolvia texto de
     * OUTRA ocorrência (o defeito da região 24: `"Amozze. Amoztra doze. A equipe
     * de cã."`). É permitido um contexto LIMITADO ([repairContextPadSamples]) mas
     * sempre DENTRO do áudio próprio. Intervalo próprio vazio (região só no
     * contexto alheio) → `REPAIR_NO_AUDIO`: NENHUMA re-decodição é pedida.
     */
    private fun attachRegionLocked(
        blk: Blk,
        words: List<W>,
        windowOpt: ByteArray?,
        ws: Long,
        uniqueStart: Long,
        uniqueEnd: Long,
    ) {
        val winLen = (uniqueEnd - ws).coerceAtLeast(1L)
        // R9/D-fix — o intervalo PRÓPRIO da região usa só os tempos REAIS (as
        // palavras ESTIMADAS não localizam áudio): uma estimativa não pode puxar
        // o início da região para o começo do contexto. Sem tempo real, o
        // intervalo próprio é o áudio próprio do chunk (nunca a janela inteira).
        var rStart = Long.MAX_VALUE
        var rEnd = Long.MIN_VALUE
        var anyReal = false
        for (w in words) {
            if (w.kind == KIND_EST) continue
            anyReal = true
            rStart = minOf(rStart, w.a0)
            rEnd = maxOf(rEnd, w.endEst)
        }
        if (!anyReal || rStart == Long.MAX_VALUE) {
            rStart = uniqueStart
            rEnd = uniqueEnd
        }
        rEnd = rEnd.coerceAtLeast(rStart)
        if (rEnd > uniqueStart) {
            // A região CRUZA a fronteira: o áudio PRÓPRIO é o do chunk
            // [uniqueStart, uniqueEnd); o contexto [ws, uniqueStart) é áudio JÁ
            // comprometido por janelas anteriores — re-decodificá-lo devolvia
            // texto de OUTRA ocorrência (defeito da região 24). Restringe e
            // marca RESTRITO (a leitura do reparo passa pelo gate de ancoragem).
            rStart = rStart.coerceIn(uniqueStart, uniqueEnd)
            rEnd = rEnd.coerceIn(uniqueStart, uniqueEnd)
            blk.audioRestricted = true
            if (rEnd > rStart) {
                // contexto LIMITADO documentado — só DENTRO do áudio próprio.
                rStart = (rStart - repairContextPadSamples).coerceAtLeast(uniqueStart)
                rEnd = (rEnd + repairContextPadSamples).coerceAtMost(uniqueEnd)
            }
        }
        // Região TOTALMENTE no contexto alheio (não cruza) ou intervalo vazio:
        // NÃO há áudio próprio a que restringir — mantém o áudio PRÓPRIO da região
        // (sem restrição; documentado: aqui o gate de ancoragem NÃO se aplica).
        if (rEnd <= rStart) {
            blk.regionStart = rStart
            blk.regionEnd = rEnd
            markAudioMissingLocked(blk)
            return
        }
        blk.regionStart = rStart
        blk.regionEnd = rEnd
        // áudio preservado (re-decode restrito): cópia RECORTADA da região própria
        if (windowOpt != null && windowOpt.size >= 2) {
            val winSamples = windowOpt.size / 2L
            if (winSamples >= winLen) {
                val samples = rEnd - rStart
                val bytes = samples * 2
                if (samples in 1..maxRepairRegionSamples &&
                    retainedBytesLocked() + bytes <= maxRetainedAudioBytes
                ) {
                    val off = ((rStart - ws) * 2).toInt()
                    blk.pcm = windowOpt.copyOfRange(off, off + bytes.toInt())
                } else {
                    markAudioMissingLocked(blk)
                }
            } else {
                markAudioMissingLocked(blk)
            }
        } else {
            markAudioMissingLocked(blk)
        }
    }

    /** R9/D2: áudio NÃO preservado (limite por região/total explícito) → estado
     * tipado distinto (não confundir com "resolvido" nem com "aguardando reparo").
     * A região pode AINDA resolver por janela; só conta como audio_ausente se
     * terminar promovida sem confirmação. */
    private fun markAudioMissingLocked(b: Blk) {
        b.repairState = REPAIR_NO_AUDIO
    }

    private fun enforceHeldLimitsLocked() {
        var closings = 0
        while (true) {
            val blocks = heldBlocksLocked()
            if (blocks.size <= maxHeldHypotheses && stateCountLocked(STATE_HELD) <= maxHeldWords) break
            val oldest = blocks.firstOrNull() ?: break
            val kept = drainHeldOrderedLocked(oldest)
            if (kept > 0) {
                // R9/D2: fechamento por LIMITE de retenção = promovida sem
                // confirmação (estado distinto de resolvida).
                markOutcomeLocked(oldest, OUTCOME_PROMOTED_UNCONFIRMED, kept)
            }
            forcedClosures++
            closings++
            if (closings > 64) break // segurança: nunca laço infinito
        }
    }

    /**
     * R8/B: dreno ORDENADO de UMA região retida (terminal ou fechamento tipado):
     * palavras reproduzidas pela referência visível (mesma ocorrência: texto +
     * áudio compatível) são duplicatas; o resto é preservado NO LUGAR. Sem
     * vocabulário/contagem global: palavra igual em OUTRA ocorrência fica.
     * Devolve quantas palavras pendentes permaneceram (0 = resolvida).
     */
    private fun drainHeldOrderedLocked(b: Blk): Int {
        val visible = visibleWordsLocked().filter { it.blk !== b }
        val pending = b.words.filter { it.state == STATE_HELD }
        if (pending.isEmpty()) return 0
        val keep = mergeAlignedLocked(pending, visible)
        val keepSet = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<W, Boolean>())
        keepSet.addAll(keep)
        val newWords = ArrayList<W>(b.words.size)
        var kept = 0
        val pendingSet = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<W, Boolean>())
        pendingSet.addAll(pending)
        for (w in b.words) {
            if (pendingSet.contains(w)) {
                if (keepSet.contains(w)) {
                    w.state = STATE_COMMITTED
                    w.blk = b
                    newWords.add(w)
                    kept++
                }
                // casada = duplicata comprovada: sai do texto
            } else {
                newWords.add(w)
            }
        }
        b.words.clear()
        b.words.addAll(newWords)
        b.pcm = null
        return kept
    }

    /** R8/B: remove de [words] as que reproduzem (texto+ocorrência) o que já
     * está VISÍVEL fora do próprio bloco — duplicatas comprovadas; o resto fica. */
    private fun dedupAgainstVisibleLocked(words: List<W>, own: Blk): ArrayList<W> {
        if (words.isEmpty()) return ArrayList()
        val visible = visibleWordsLocked().filter { it.blk !== own }
        return mergeAlignedLocked(words, visible)
    }

    /**
     * R8/B: mescla ORDENADA de hipótese retida [a] com a leitura do reparo [b]:
     * casamento → UMA cópia (a palavra da hipótese, que mantém a geometria);
     * palavra só da hipótese → permanece (omissão não descarta); palavra só do
     * reparo → entra na posição do alinhamento.
     */
    private fun mergeRepairLocked(a: List<W>, b: List<W>): ArrayList<W> {
        val n = a.size
        val m = b.size
        val out = ArrayList<W>(maxOf(n, m))
        val an = a.map { norm(it.word) }
        val bn = b.map { norm(it.word) }
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                dp[i][j] = if (an[i] == bn[j] && occCompatible(a[i], b[j])) {
                    dp[i + 1][j + 1] + 1
                } else {
                    maxOf(dp[i + 1][j], dp[i][j + 1])
                }
            }
        }
        var i = 0
        var j = 0
        while (i < n && j < m) {
            if (an[i] == bn[j] && occCompatible(a[i], b[j]) && dp[i][j] == dp[i + 1][j + 1] + 1) {
                out.add(a[i])
                i++
                j++
            } else if (dp[i + 1][j] >= dp[i][j + 1]) {
                out.add(a[i])
                i++
            } else {
                out.add(b[j])
                j++
            }
        }
        while (i < n) {
            out.add(a[i])
            i++
        }
        while (j < m) {
            out.add(b[j])
            j++
        }
        return out
    }

    /**
     * Mescla alinhada de [a] (hipótese) com [bVisible] (texto já visível):
     * casamentos removem a palavra de [a] (duplicata); o resto de [a] permanece
     * na ordem original. Devolve a lista filtrada de [a].
     */
    private fun mergeAlignedLocked(a: List<W>, bVisible: List<W>): ArrayList<W> {
        val n = a.size
        val m = bVisible.size
        val out = ArrayList<W>(n)
        if (n == 0) return out
        val an = a.map { norm(it.word) }
        val bn = bVisible.map { norm(it.word) }
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                dp[i][j] = if (an[i] == bn[j] && occCompatible(a[i], bVisible[j])) {
                    dp[i + 1][j + 1] + 1
                } else {
                    maxOf(dp[i + 1][j], dp[i][j + 1])
                }
            }
        }
        val matched = BooleanArray(n)
        var i = 0
        var j = 0
        while (i < n && j < m) {
            if (an[i] == bn[j] && occCompatible(a[i], bVisible[j]) && dp[i][j] == dp[i + 1][j + 1] + 1) {
                matched[i] = true
                i++
                j++
            } else if (dp[i + 1][j] >= dp[i][j + 1]) {
                i++
            } else {
                j++
            }
        }
        for (k in 0 until n) {
            if (!matched[k]) out.add(a[k])
        }
        return out
    }

    private fun span0(w: W): Long = if (w.kind == KIND_EST) (w.endEst - w.step).coerceAtLeast(0L) else w.a0

    private fun span1(w: W): Long = if (w.kind == KIND_EST) w.endEst else w.a1

    /** R10/E1: ms→amostras com origem no áudio da região, validado ANTES de
     *  multiplicar (evidência absurda nunca estoura `Long`). */
    private fun msToSamples(ms: Long): Long? {
        if (ms < 0L || ms > Long.MAX_VALUE / 16L) return null
        return ms * 16L
    }

    /**
     * R10/E1 — contrato de UM segmento de reparo contra o áudio PRESERVADO da
     * região `[0, regionSamples)`: início e fim válidos, ordenados (fim > início),
     * os dois DENTRO do áudio com o MESMO slack do resto do seam ([TIME_SLACK_MS])
     * e sem overflow na conversão. Fora disso a evidência é INVÁLIDA (nunca
     * clampada em silêncio).
     */
    private fun segWithinRegion(t0Ms: Long, t1Ms: Long, regionSamples: Long): Boolean {
        if (t0Ms < 0L || t1Ms <= t0Ms) return false
        val s0 = msToSamples(t0Ms) ?: return false
        val s1 = msToSamples(t1Ms) ?: return false
        if (s0 > regionSamples) return false
        return s1 <= regionSamples + TIME_SLACK_MS * 16L
    }

    /**
     * R10/E2 — COMPATIBILIDADE DE OCORRÊNCIA (defeito E2: bastava UM lado
     * `KIND_EST` para o casamento virar "qualquer palavra igual no histórico").
     *
     *  - evidência REAL dos dois lados → só a MESMA ocorrência se os intervalos de
     *    áudio se sobrepõem (intervalos disjuntos = ocorrências diferentes);
     *  - evidência ESTIMADA (qualquer lado) → o slot é posição relativa à geometria
     *    do próprio bloco (não comparável entre janelas com densidades diferentes),
     *    então a identidade ancora na PROVENIÊNCIA: mesma janela/região de origem
     *    (áudio idêntico por construção — inclui o reparo, que re-decodifica o
     *    áudio PRÓPRIO da região) ou janelas cujo ÁUDIO realmente se sobrepõe
     *    (o contexto da seguinte é o áudio da anterior). Uma fala repetida em áudio
     *    NÃO sobreposto é outra ocorrência e fica preservada.
     * Sem compatibilidade a palavra NÃO é dedupada: permanece na posição (retida/
     * revisável) e a ambiguidade perde-se por último, nunca em silêncio.
     */
    private fun occCompatible(x: W, y: W): Boolean {
        if (x.kind != KIND_EST && y.kind != KIND_EST) return spanOverlaps(x, y)
        // R11/B2 — IDENTIDADE DE OCORRÊNCIA PARA ESTIMATIVA (KIND_EST).
        //
        // Defeito R10 (casos 1/3/9/15): a identidade estimada
        // ancora em PROVENIÂNCIA GEOMÉTRICA — windowIndex igual
        // ⇒ identidade automática, e entre janelas o teste
        // inclusivo winA0<=winA1. Isso prova apenas GEOMETRIA
        // de janela (o áudio das janelas se sobrepõe), NÃO que
        // as duas palavras sejam a MESMA ocorrência:
        //  - mesma janela "alfa bravo alfa": DUAS ocorrências de
        //    "alfa" em SLOTS ESTIMADOS DISJUNTOS; a identidade por
        //    windowIndex casava as duas e o dreno APAGAVA a 2ª;
        //  - fronteira adjacente (gap 0/1): o áudio ÚNICO da
        //    janela seguinte NÃO se sobrepõe ao da anterior, mas a
        //    estimativa uniforme infla o extento da cabeça para o
        //    contexto, casando uma OCORRÊNCIA NOVA com a cauda.
        // A estimativa NÃO prova associação sozinha (R11/B2):
        // exigimos PROVENIÂNCIA + ÂNCORA LOCAL ADEQUADA.
        //  - MESMA janela: o estimate uniforme está num frame
        //    consistente dentro da janela, então a âncora local
        //    (span estimado) distingue ocorrências DISTINTAS em
        //    slots disjuntos (dois "alfa" em [0,step) e
        //    [2*step,3*step)); spans disjuntos ⇒ OUTRA ocorrência
        //    ⇒ preservada. (O reparo re-decodifica a MESMA região
        //    da janela; seu estimate é ancorado no frame das
        //    palavras retidas — ver completeRepair — então o
        //    span SOBREPOE e o casamento é a MESMA ocorrência.)
        //  - JANELAS DIFERENTES: o estimate uniforme DESLOCA com
        //    o nº de palavras (span não confiável entre janelas),
        //    então a âncora é o ÁUDIO COMPARTILHADO (região de
        //    contexto = interseção do áudio das duas janelas).
        //    Uma palavra é a MESMA ocorrência sse seu span
        //    estimado cai na região compartilhada (é áudio re-
        //    decodificado, não conteúdo único). Janelas adjacentes
        //    (fronteira no mesmo sample) NÃO compartilham áudio
        //    (interseção vazia) ⇒ ocorrências diferentes (casos
        //    R10 1/9/15); uma repetição no trecho ÚNICO de janela
        //    com contexto também fica fora da região compartilhada
        //    ⇒ NOVA ocorrência (contrato: fala repetida no trecho
        //    único é nova ocorrência).
        // Sem compatibilidade a palavra NÃO é dedupada: permanece
        // retida/revisável e a ambiguidade perde-se por último,
        // nunca em silêncio.
        if (x.windowIndex == y.windowIndex) return spanOverlaps(x, y)
        // JANELAS DIFERENTES (EST envolvido): o estimate uniforme
        // DESLOCA com o nº de palavras da janela (span não é
        // comparável entre janelas), então a âncora é o ÁUDIO
        // RE-DECODIFICADO em comum: a região de CONTEXTO =
        // interseção do áudio das duas janelas [max(winA0),
        // min(winA1)). Uma palavra é a MESMA ocorrência sse o
        // seu span ESTIMADO cai nessa região compartilhada (é
        // áudio re-decodificado pela outra janela, NÃO conteúdo
        // único).
        //
        // R11/B2-fix1 (E1-4c) — a GRID ESTIMADA da janela mais
        // nova é uniforme sobre a JANELA inteira, então o mesmo
        // áudio pode ser estimado deslocado de até UM slot em
        // relação à janela anterior (contagens/larguras
        // diferentes ⇒ steps diferentes). O teste de pertinência
        // ESTRITO (`span0 < shared1 && shared0 < span1`) exigia o
        // span INTEIRO dentro da região compartilhada e por isso
        // REJEITAVA a MESMA ocorrência quando a estimativa
        // encostava na fronteira: em E1-4c `s` da janela anterior
        // tem span [12000,16000) (o fim da região compartilhada),
        // enquanto a MESMA `s` da janela nova é estimada em
        // [16000,20000) — o span começa EXATAMENTE em shared1.
        // Resultado: a `s` do "resto" da janela nova não era
        // reconhecida como duplicata e era PUBLICADA de novo
        // (out='p q r s g s t'). A pertinência é portanto
        // INCLUSIVA na fronteira (`shared0 <= span <= shared1`),
        // mas SÓ quando as duas janelas realmente compartilham
        // áudio re-decodificado (`shared1 > shared0`): com
        // interseção VAZIA (janelas adjacentes/afastadas, casos
        // R10 1/3/9/15) nada é casado por aqui — a fronteira
        // contígua é tratada pela regra seguinte.
        val shared0 = maxOf(x.winA0, y.winA0)
        val shared1 = minOf(x.winA1, y.winA1)
        if (shared1 > shared0) {
            val xTouchesShared = span0(x) <= shared1 && shared0 <= span1(x)
            val yTouchesShared = span0(y) <= shared1 && shared0 <= span1(y)
            if (xTouchesShared && yTouchesShared) return true
        }
        // Fronteira contígua re-decodificada: os áudios se TOCAM
        // (shared0 == shared1) e a palavra da janela ANTERIOR
        // termina na fronteira (span1 == shared1) enquanto a da
        // SEGUINTE começa no seu CONTEXTO (span0 == shared0 E
        // span0 < winU0 da seguinte ⇒ re-decodificada).
        val xTail = span1(x) == shared1 && span0(x) <= shared1 && shared0 <= span1(x)
        val yHead = span0(y) == shared0 && span0(y) < y.winU0
        val yTail = span1(y) == shared1 && span0(y) <= shared1 && shared0 <= span1(y)
        val xHead = span0(x) == shared0 && span0(x) < x.winU0
        return (xTail && yHead) || (yTail && xHead)
    }

    /** Sobreposição dos intervalos de áudio REAIS. */
    private fun spanOverlaps(x: W, y: W): Boolean =
        span0(x) < span1(y) && span0(y) < span1(x)

    /** Compatibilidade p/ REVISÃO/INSERÇÃO: exige sobreposição de áudio real. */
    private fun occCompatibleReal(x: W, y: W): Boolean =
        (x.kind != KIND_EST || y.kind != KIND_EST) &&
            span0(x) < span1(y) && span0(y) < span1(x)

    companion object {
        /** R7/E2: geração não especificada (compatibilidade com chamadas antigas). */
        const val GENERATION_UNSPECIFIED = -1L

        private const val KIND_EST = 0
        private const val KIND_TIME = 1
        private const val KIND_SEG = 2

        private const val STATE_COMMITTED = 0
        private const val STATE_REVIEWABLE = 1
        private const val STATE_HELD = 2

        private const val REPAIR_NONE = 0
        private const val REPAIR_REQUESTED = 1
        private const val REPAIR_COMPLETED = 2
        private const val REPAIR_FAILED = 3
        private const val REPAIR_NO_AUDIO = 4
        /** R10/E1: reparo recusado por evidência de tempo inválida (tipado). */
        private const val REPAIR_INVALID_EVIDENCE = 5

        /** R9/D2: estados FINAIS tipados de região (distintos, nunca "resolvido" mudo). */
        private const val OUTCOME_NONE = 0
        private const val OUTCOME_RESOLVED_CONFIRMED = 1
        private const val OUTCOME_RESOLVED_BY_REPAIR = 2
        private const val OUTCOME_PROMOTED_UNCONFIRMED = 3

        /** Tolerância de arredondamento dos tempos do JNI (ms). */
        private const val TIME_SLACK_MS = 100L

        private fun splitWords(text: String): List<String> =
            text.split(Regex("\\s+")).filter { it.isNotBlank() }

        /** Normalização SÓ para comparação de sobreposição (nunca para saída). */
        fun norm(word: String): String =
            word.lowercase(Locale.ROOT).trim { it <= ' ' || it in ".,;:!?\"'()[]{}<>«»…-" }
    }
}
