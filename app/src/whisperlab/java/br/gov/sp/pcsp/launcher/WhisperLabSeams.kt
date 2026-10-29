package br.gov.sp.pcsp.launcher

import java.io.File

/**
 * R4/C3 — SEAM DE LABORATÓRIO (variante whisperlab). Substitui o arquivo
 * inerte de `src/main/loader-prod` via source set do flavor. Consulta o
 * [WhisperLabDrive] nos MESMOS pontos fixos em que a produção consulta o
 * objeto inerte — a ligação settings→modelo→sessão→coordenador→UI da
 * WhisperActivity é a de produção; este objeto só injeta fonte/atraso/falha e
 * registra eventos. Nenhuma classe deste arquivo existe no APK normal.
 */
object WhisperLabSeams {

    fun isLab(): Boolean = WhisperLabDrive.active

    fun onActivityReady(activity: android.app.Activity) {
        WhisperLabDrive.install(activity)
    }

    /** Fonte PCM injetada no caminho AO VIVO (lab); null = AudioRecord real. */
    fun injectedLiveSource(activity: android.app.Activity): LivePcmSource? {
        if (!WhisperLabDrive.active) return null
        val path = WhisperLabDrive.livePcmPath ?: return null
        val file = File(path)
        if (!file.isFile) {
            WhisperLabDrive.log("injected_source_missing", "path" to path)
            return null
        }
        val source: LivePcmSource = if (WhisperLabDrive.livePcmFailAfterReads > 0) {
            FailingPcmSource(file, failAfterReads = WhisperLabDrive.livePcmFailAfterReads, readBytes = WhisperLabDrive.livePcmReadBytes)
        } else {
            // R8/D: pacing por deadline monotônico + evidência por leitura
            // (jitter/atraso/catch-up/re-âncora) e resumo no close.
            FilePcmSource(
                file,
                speed = WhisperLabDrive.livePcmSpeed,
                readBytes = WhisperLabDrive.livePcmReadBytes,
                pauseAtMs = WhisperLabDrive.livePauseAtMs,
                pauseForMs = WhisperLabDrive.livePauseForMs,
                onRead = { e ->
                    WhisperLabDrive.log(
                        "pcm_source_read",
                        "i" to e.index, "samples" to e.samples, "delivered" to e.deliveredSamples,
                        "target_ms" to String.format(java.util.Locale.US, "%.3f", e.targetMs),
                        "return_ms" to String.format(java.util.Locale.US, "%.3f", e.returnMs),
                        "late_ms" to String.format(java.util.Locale.US, "%.3f", e.lateMs),
                        "jitter_ms" to String.format(java.util.Locale.US, "%.3f", e.jitterMs),
                        "sleep_ms" to String.format(java.util.Locale.US, "%.3f", e.sleepMs)
                    )
                },
                onStats = { s ->
                    WhisperLabDrive.log(
                        "pcm_source_stats",
                        "reads" to s.reads, "samples" to s.samples,
                        "audio_ms" to String.format(java.util.Locale.US, "%.1f", s.audioMs),
                        "wall_ms" to String.format(java.util.Locale.US, "%.1f", s.wallMs),
                        "ratio" to String.format(java.util.Locale.US, "%.4f", s.ratio),
                        "jitter_p50" to String.format(java.util.Locale.US, "%.3f", s.jitterAbsP50Ms),
                        "jitter_p95" to String.format(java.util.Locale.US, "%.3f", s.jitterAbsP95Ms),
                        "jitter_max" to String.format(java.util.Locale.US, "%.3f", s.jitterAbsMaxMs),
                        "late_sum_ms" to String.format(java.util.Locale.US, "%.1f", s.lateSumMs),
                        "late_max_ms" to String.format(java.util.Locale.US, "%.1f", s.lateMaxMs),
                        "catchups" to s.catchUps, "reanchors" to s.reAnchors,
                        "reanchor_ms" to String.format(java.util.Locale.US, "%.1f", s.reAnchorMs),
                        "pauses" to s.pauses,
                        "pause_ms" to String.format(java.util.Locale.US, "%.1f", s.pauseInjectedMs),
                        "eof_ms" to String.format(java.util.Locale.US, "%.1f", s.eofReturnMs),
                        "eof_ahead_ms" to String.format(java.util.Locale.US, "%.1f", s.eofAheadOfPlanMs)
                    )
                }
            )
        }
        val sha = runCatching {
            when (source) {
                is FilePcmSource -> source.sha256()
                is FailingPcmSource -> source.sha256()
                else -> "?"
            }
        }.getOrDefault("?")
        WhisperLabDrive.log(
            "injected_live_source",
            "path" to path,
            "speed" to WhisperLabDrive.livePcmSpeed,
            "fail_after_reads" to WhisperLabDrive.livePcmFailAfterReads,
            "sha256" to sha
        )
        return source
    }

    /** Fonte PCM injetada na GRAVAÇÃO (lab); null = AudioRecord real. */
    fun injectedRecordingSource(activity: android.app.Activity): LivePcmSource? {
        if (!WhisperLabDrive.active) return null
        val path = WhisperLabDrive.recordPcmPath ?: return null
        val file = File(path)
        if (!file.isFile) {
            WhisperLabDrive.log("injected_record_missing", "path" to path)
            return null
        }
        val source = FilePcmSource(file, speed = WhisperLabDrive.recordPcmSpeed)
        WhisperLabDrive.log(
            "injected_record_source",
            "path" to path,
            "speed" to WhisperLabDrive.recordPcmSpeed,
            "sha256" to source.sha256()
        )
        return source
    }

    fun chunkDelayMs(): Long = if (WhisperLabDrive.active) WhisperLabDrive.chunkDelayMs else 0L

    fun modelLoadDelayMs(): Long = if (WhisperLabDrive.active) WhisperLabDrive.modelLoadDelayMs else 0L

    fun overrideChunkResult(chunkIndex: Int, text: String, status: String): Pair<String, String> {
        if (!WhisperLabDrive.active) return text to status
        // R17/B3 — sonda: registra TODA chamada para distinguir "não foi
        // chamado" de "chamado com outro índice". Sem isso o B3 não fecha.
        WhisperLabDrive.log(
            "chunk_result_probe",
            "index" to chunkIndex,
            "inject_at" to WhisperLabDrive.injectChunkIndex,
            "hit" to (WhisperLabDrive.injectChunkIndex == chunkIndex)
        )
        if (WhisperLabDrive.injectChunkIndex == chunkIndex) {
            WhisperLabDrive.log(
                "chunk_result_injected",
                "index" to chunkIndex,
                "status" to WhisperLabDrive.injectChunkStatus,
                "text" to WhisperLabDrive.injectChunkText
            )
            return WhisperLabDrive.injectChunkText to WhisperLabDrive.injectChunkStatus
        }
        return text to status
    }

    fun spoolLimitBytes(): Long? {
        if (!WhisperLabDrive.active) return null
        val limit = WhisperLabDrive.spoolLimitBytes
        return if (limit > 0L) limit else null
    }

    /** R6/D2 — estratégia de janela (lab): null = default do produto. */
    data class LiveWindowSpec(val uniqueMs: Long, val contextMs: Long)

    fun liveWindowSpec(): LiveWindowSpec? {
        if (!WhisperLabDrive.active) return null
        val hop = WhisperLabDrive.liveHopMs
        val ctx = WhisperLabDrive.liveContextMs
        if (hop <= 0L && ctx < 0L) return null
        return LiveWindowSpec(
            uniqueMs = if (hop > 0L) hop else 4000L,
            contextMs = if (ctx >= 0L) ctx else 4000L
        )
    }

    /** R7/E3 — ESTRATÉGIA do caminho ao vivo (lab): null = default do produto.
     * extras: strategy=fixed|vad, hopMs, ctxMs, padMs, maxMs, silMs. */
    fun liveStrategySpec(): LiveStrategy? {
        if (!WhisperLabDrive.active) return null
        val kind = WhisperLabDrive.liveStrategyKind.trim().lowercase()
        val ctx = WhisperLabDrive.liveContextMs
        return when (kind) {
            "fixed" -> LiveStrategy.Fixed(
                uniqueMs = if (WhisperLabDrive.liveHopMs > 0L) WhisperLabDrive.liveHopMs else 2000L,
                contextMs = if (ctx >= 0L) ctx else 0L
            )
            "vad" -> LiveStrategy.VadSegment(
                contextMs = if (ctx >= 0L) ctx else 2000L,
                padMs = if (WhisperLabDrive.livePadMs >= 0L) WhisperLabDrive.livePadMs else 300L,
                silenceCutMs = if (WhisperLabDrive.liveSilenceCutMs > 0L) WhisperLabDrive.liveSilenceCutMs else 700L,
                maxSegmentMs = if (WhisperLabDrive.liveMaxSegmentMs > 0L) WhisperLabDrive.liveMaxSegmentMs else 12000L,
                // R8/D (candidata 1): fechamento adaptativo da 1ª janela.
                firstSegmentMs = if (WhisperLabDrive.liveFirstSegmentMs > 0L) WhisperLabDrive.liveFirstSegmentMs else 0L,
                firstSilenceCutMs = if (WhisperLabDrive.liveFirstSilenceCutMs > 0L) WhisperLabDrive.liveFirstSilenceCutMs else 0L
            )
            "" -> if (WhisperLabDrive.liveHopMs > 0L || ctx >= 0L) {
                // Compat R6/D2: extras de janela sozinhos = estratégia fixa.
                LiveStrategy.Fixed(
                    uniqueMs = if (WhisperLabDrive.liveHopMs > 0L) WhisperLabDrive.liveHopMs else 4000L,
                    contextMs = if (ctx >= 0L) ctx else 4000L
                )
            } else null
            else -> null
        }
    }

    /** R6/D3 — Uri de fixture (lab): provider do próprio lab, jamais no normal. */
    fun injectedMediaUri(activity: android.app.Activity): android.net.Uri? {
        if (!WhisperLabDrive.active) return null
        val name = WhisperLabDrive.mediaFixtureName ?: return null
        val uri = android.net.Uri.parse("content://${WhisperLabFixtureProvider.AUTHORITY}/$name")
        WhisperLabDrive.log("injected_media_uri", "uri" to uri.toString())
        return uri
    }

    /** R8/D — clocks de chegada/enfileiramento de PCM (lab). No APK normal o
     * objeto inerte responde false e nada é emitido. BLINDADO: em teste JVM a
     * leitura do drive nunca pode derrubar o pipeline (runCatching → false). */
    fun feedClockEnabled(): Boolean = runCatching { WhisperLabDrive.active }.getOrDefault(false)

    /** R8/D (candidata 2) — publicação INCREMENTAL de onSegment do job corrente
     * como parcial PROVISÓRIA (lab). No APK normal: false. */
    fun incrementalPartialEnabled(): Boolean =
        WhisperLabDrive.active && WhisperLabDrive.liveIncrementalPartial

    fun listenerFaultFor(chunkIndex: Int): Boolean =
        WhisperLabDrive.active && WhisperLabDrive.listenerFaultChunk == chunkIndex

    /** R11/D2 — pedido de threads do SEAM DE BANCADA (one-shot, consumido pelo
     * PRÓXIMO job): 0 = nenhum (a política do nativo decide). O valor vem do
     * extra `threads` da Activity do lab e existe só para rodar os DOIS braços de
     * um par controlado no MESMO caminho/lib/params (a política não é alterada:
     * o nativo rotula a decisão como `lab_override`). */
    fun threadsOverride(): Int =
        if (WhisperLabDrive.active) WhisperLabDrive.threadsToSet else 0

    /** R14/D2-D3 — contexto de encoder experimental (0 = desligado).
     *  R17/E2 — ONE-SHOT: devolver o valor o CONSOME. O pedido pertence ao job
     *  que o lê; um segundo job na mesma sessão não o herda (vínculo ao dono). */
    fun audioCtxOverride(): Int {
        if (!WhisperLabDrive.active) return 0
        val v = WhisperLabDrive.audioCtxToSet
        if (v > 0) WhisperLabDrive.audioCtxToSet = 0
        android.util.Log.i("WhisperLabSeams", "audioCtxOverride -> $v (consumido=${v > 0})")
        return v
    }

    /**
     * R25/REQ — FOTOGRAFIA DA TENTATIVA, lida UMA vez do estado do lab.
     *
     * Defeito corrigido: `audioCtxWindowSamples()` chamava `audioCtxOverride()`
     * (getter CONSUMPTIVO) e devolvia o valor de contexto como se fosse janela
     * de amostras — o segundo consumo zerava a janela e o modo era ambíguo.
     * Agora cada campo tem sua leitura única e a fotografia leva os dois.
     *
     * Devolve null fora do lab (produção) ou sem owner: o produto não manda
     * pedido nenhum.
     */
    fun attemptRequest(
        token: String,
        owner: Long,
        gen: Long,
        job: Long,
        attempt: Long
    ): AttemptRequest? {
        if (!WhisperLabDrive.active || owner <= 0L) return null

        val ctx = WhisperLabDrive.audioCtxToSet
        if (ctx > 0) WhisperLabDrive.audioCtxToSet = 0
        val win = WhisperLabDrive.audioCtxWindowToSet
        if (win > 0L) WhisperLabDrive.audioCtxWindowToSet = 0L
        val submit = WhisperLabDrive.submitMbToSet.takeIf { it > 0 }
        val serial = WhisperLabDrive.serialToSet.takeIf { it >= 0 }
        val k = WhisperLabDrive.kToSet.takeIf { it >= 0 }
        val kinds = WhisperLabDrive.dumpKindsToSet
        val capture = if (kinds.isNotBlank() && WhisperLabDrive.dumpDirToSet.isNotBlank()) {
            AttemptRequest.Capture(
                kinds = kinds,
                dir = WhisperLabDrive.dumpDirToSet,
                tag = WhisperLabDrive.dumpTagToSet,
                first = WhisperLabDrive.dumpFirstToSet,
                step = WhisperLabDrive.dumpStepToSet,
                max = WhisperLabDrive.dumpMaxToSet,
                steps = WhisperLabDrive.dumpStepsToSet
            )
        } else null

        val pedido = AttemptRequest(
            token = token,
            owner = owner,
            gen = gen,
            job = job,
            attempt = attempt,
            ctxMode = if (ctx > 0) AttemptRequest.CtxMode.LITERAL else AttemptRequest.CtxMode.DEFAULT,
            ctxLiteral = ctx,
            winMode = if (win > 0L) AttemptRequest.WinMode.WINDOW else AttemptRequest.WinMode.DEFAULT,
            winSamples = win,
            submitMb = submit,
            serial = serial,
            k = k,
            capture = capture,
            producer = WhisperLabDrive.producerId,
            config = WhisperLabDrive.configId
        )
        android.util.Log.i("WhisperLabSeams",
            "attemptRequest token=$token owner=$owner gen=$gen job=$job attempt=$attempt " +
                "ctx=${pedido.ctxMode}/$ctx win=${pedido.winMode}/$win submit=$submit " +
                "serial=$serial k=$k cap=${capture != null} producer=${pedido.producer}")
        return pedido
    }

    /** R11/D4 — braço de controle do job (lab): `jobFgs=off` desliga o
     * foreground service e o job volta ao comportamento antigo (Thread solta da
     * Activity). Fora do lab: SEMPRE `true` (produção). */
    /**
     * R14/E1 — MODELO importado pelo fluxo REAL (SAF): o lab entrega o Uri do
     * provider de fixtures à MESMA callback de `REQUEST_PICK_MODEL` da
     * WhisperActivity (a mesma que o picker real usaria).
     */
    fun injectedModelUri(activity: android.app.Activity): android.net.Uri? {
        val active = WhisperLabDrive.active
        val name = WhisperLabDrive.modelImportFixtureName
        android.util.Log.i("WhisperLabSeams", "injectedModelUri: active=$active name=$name")
        if (!active) return null
        if (name == null) return null
        // NÃO abrir o arquivo aqui: em modelos grandes (574 MB) isso trava a
        // main thread (ANR). A validação é do PRÓDUTO, na importação.
        return android.net.Uri.parse("content://${WhisperLabFixtureProvider.AUTHORITY}/$name")
    }

    /** R14/E1 — o lab pede a importação REAL do modelo na Activity. */
    fun requestModelImport(activity: android.app.Activity) {
        if (!WhisperLabDrive.active) return
        val name = WhisperLabDrive.modelImportFixtureName ?: return
        val uri = android.net.Uri.parse(
            "content://${WhisperLabFixtureProvider.AUTHORITY}/$name")
        val a = activity as? br.gov.sp.pcsp.launcher.WhisperActivity ?: return
        a.labImportModel(uri)
    }

    /** R16/A2 — caminho do modelo PEDIDO (null = sem gate). */
    fun expectedModelPath(): String? =
        if (WhisperLabDrive.active) WhisperLabDrive.expectedModelPath else null

    /** R16/A2 — divergência registrada no ensaio (terminal, sem inferência). */
    fun onModelIdentityMismatch(pedido: String, selecionado: String) {
        WhisperLabDrive.log(
            "model_identity_mismatch", "pedido" to pedido, "selecionado" to selecionado
        )
    }

    /** R56/adendo20 — pedido de backend pausado (OpenCL) recusado de forma tipada. */
    fun onBackendRecusado(kind: Int, motivo: String) {
        WhisperLabDrive.log("backend_recusado", "kind" to kind, "motivo" to motivo)
    }

    /** R15/B1 — prazo curto do wake lock pedido pelo ensaio (null = padrão). */
    fun leaseTimeoutMs(): Long? =
        if (WhisperLabDrive.active && WhisperLabDrive.leaseTimeoutMs > 0L) {
            WhisperLabDrive.leaseTimeoutMs
        } else null

    /** R15/B1 — injeta "sem progresso" para provar o braço de cancelamento. */
    fun leaseProgressSuppressed(): Boolean =
        WhisperLabDrive.active && WhisperLabDrive.leaseIgnoreProgress

    /** R15/A2 — o modelo pedido pelo ensaio (rótulo do menu do produto). */
    fun requestedModelLabel(): String? =
        if (WhisperLabDrive.active) WhisperLabDrive.modelLabelToSet else null

    fun jobServiceEnabled(): Boolean =
        if (WhisperLabDrive.active) WhisperLabDrive.jobFgsEnabled else true

    fun event(name: String, payloadJson: String) {
        if (!runCatching { WhisperLabDrive.active }.getOrDefault(false)) return
        // O payload já vem em JSON; registra como objeto aninhado quando der.
        try {
            val obj = org.json.JSONObject(payloadJson)
            val kv = ArrayList<Pair<String, Any?>>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                kv.add(k to obj.get(k))
            }
            WhisperLabDrive.log(name, *kv.toTypedArray())
        } catch (_: Throwable) {
            WhisperLabDrive.log(name, "payload" to payloadJson.take(600))
        }
    }
    /** R19/A4 — backend/FA forcados pela celula (null = comportamento do produto). */
    fun forcedBackendKind(activity: android.app.Activity): Int? =
        if (WhisperLabDrive.active) WhisperLabDrive.forcedBackendKind else null

    fun forcedFlashAttention(activity: android.app.Activity): Boolean? =
        if (WhisperLabDrive.active) WhisperLabDrive.forcedFlashAttention else null

    /** R20/A2 — identidade do request no artefato da bancada. */
    fun setCurrentGen(gen: Long) {
        if (WhisperLabDrive.active) WhisperLabDrive.currentGen = gen
    }
    /** R22/Q1 — aplica a selecao por arquivo depois da midia, na main thread. */
    fun applyPendingModelSelection(activity: android.app.Activity) {
        if (WhisperLabDrive.active) WhisperLabDrive.applyPendingModelFile(activity)
    }
    /** R24/REQ — janela de audio_ctx da tentativa (bancada).
     *  R25/REQ — lê o campo PRÓPRIO da janela, uma vez; não consome mais o
     *  valor de contexto (era o consumo duplo que zerava a janela). */
    private fun audioCtxWindowSamplesOnce(): Long {
        if (!WhisperLabDrive.active) return 0L
        val v = WhisperLabDrive.audioCtxWindowToSet
        if (v > 0L) WhisperLabDrive.audioCtxWindowToSet = 0L
        return v
    }

    fun audioCtxWindowSamples(): Long = audioCtxWindowSamplesOnce()
}
