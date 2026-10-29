package br.gov.sp.pcsp.launcher

/**
 * R4/C3 — SEAM DE LABORATÓRIO (arquivo de PRODUÇÃO).
 *
 * Este arquivo vive em `src/main/loader-prod`, que é REMOVIDO do build quando
 * `-PwhisperLab=true` (mesmo mecanismo do WhisperLibLoader). No APK NORMAL
 * vale ESTA implementação: um objeto INERTE de propósito — nenhum método
 * injeta nada, não há estado mutável, não há escrita de eventos e não existe
 * caminho externo para acioná-lo. O APK normal também NÃO contém
 * WhisperLabActivity/PcmInject/WhisperLabDrive (só existem em src/whisperlab).
 *
 * WhisperActivity consulta este objeto em pontos fixos; no build de produto
 * todos os retornos são os defaults "sem instrumentação".
 */
object WhisperLabSeams {

    /** true somente no flavor de laboratório. */
    fun isLab(): Boolean = false

    /** Chamado no fim do onCreate: só o lab usa para iniciar um roteiro. */
    fun onActivityReady(activity: android.app.Activity) {}

    /** Fonte PCM injetada no caminho AO VIVO (lab); null = AudioRecord real. */
    fun injectedLiveSource(activity: android.app.Activity): LivePcmSource? = null

    /** Fonte PCM injetada na GRAVAÇÃO (lab); null = AudioRecord real. */
    fun injectedRecordingSource(activity: android.app.Activity): LivePcmSource? = null

    /** Atraso artificial ANTES de transcrever um chunk (lab; 0 = nenhum). */
    fun chunkDelayMs(): Long = 0L

    /** Atraso artificial ANTES do load do modelo (lab; 0 = nenhum). */
    fun modelLoadDelayMs(): Long = 0L

    /** Injeção de falha NA FUNÇÃO da Activity (lab): devolve o par
     * (texto, status) que SUBSTITUI o retorno do nativo para o chunk, de modo
     * que a classificação/terminal do produto rodem por cima. Identidade no
     * APK normal. */
    fun overrideChunkResult(chunkIndex: Int, text: String, status: String): Pair<String, String> =
        text to status

    /** Teto de spool do caminho live (lab); null = default do produto. */
    fun spoolLimitBytes(): Long? = null

    /** R6/D2 — estratégia de janela do caminho ao vivo (lab): null = default
     * do produto (janela deslizante com contexto). NUNCA ativo no APK normal. */
    data class LiveWindowSpec(val uniqueMs: Long, val contextMs: Long)

    fun liveWindowSpec(): LiveWindowSpec? = null

    /** R7/E3 — estratégia do caminho ao vivo (lab): no APK normal, null (default
     * do produto). Não existe caminho externo para acionar este objeto. */
    fun liveStrategySpec(): LiveStrategy? = null

    /** R6/D3 — Uri de fixture injetável para a rota de ARQUIVO da Activity
     * REAL (lab); null = fluxo normal do usuário. No APK normal não existe. */
    fun injectedMediaUri(activity: android.app.Activity): android.net.Uri? = null

    /** R8/D — clocks de chegada/enfileiramento de PCM: DESLIGADOS no produto. */
    fun feedClockEnabled(): Boolean = false

    /** R8/D — publicação incremental de parcial: DESLIGADA no produto. */
    fun incrementalPartialEnabled(): Boolean = false

    /** Faz o listener de entrega lançar para este chunk (lab; false = nunca). */
    fun listenerFaultFor(chunkIndex: Int): Boolean = false

    /** R11/D2 — pedido de threads do SEAM DE BANCADA (one-shot): 0 = nenhum (a
     * política do nativo decide, como em produção). No APK normal é SEMPRE 0 —
     * não existe caminho externo para acionar este objeto. */
    fun threadsOverride(): Int = 0

    /** R14/D2-D3 — contexto de encoder experimental: produção = desligado. */
    fun audioCtxOverride(): Int = 0

    /** R14/E1 — produção nunca injeta modelo. */
    fun injectedModelUri(activity: android.app.Activity): android.net.Uri? = null

    /** R11/D4 — liga o FOREGROUND SERVICE do job (dono de lifecycle). No APK
     * normal é SEMPRE `true` (comportamento de produção); o lab usa o extra
     * `jobFgs=off` para rodar o braço de controle do MESMO binário. */
    fun jobServiceEnabled(): Boolean = true

    /** R16/A2 — produção nunca declara modelo esperado (sem gate). */
    fun expectedModelPath(): String? = null

    /** R16/A2 — produção não registra divergência de ensaio. */
    fun onModelIdentityMismatch(pedido: String, selecionado: String) { }

    /** R56/adendo20 — em produção não há canal de ensaio: é no-op. */
    fun onBackendRecusado(kind: Int, motivo: String) { }

    /** R15/B1 — prazo de lock do ensaio (produção: usa o padrão). */
    fun leaseTimeoutMs(): Long? = null

    /** R15/B1 — produção nunca suprime progresso. */
    fun leaseProgressSuppressed(): Boolean = false

    /** R15/A2 — modelo pedido pelo ensaio (produção: nenhum). */
    fun requestedModelLabel(): String? = null

    /** Sink de eventos do lab (JSONL). No APK normal: no-op, sem arquivo. */
    fun event(name: String, payloadJson: String) {}
    /** R19/A4 — backend/FA forcados pela celula; null no produto. */
    fun forcedBackendKind(activity: android.app.Activity): Int? = null
    fun forcedFlashAttention(activity: android.app.Activity): Boolean? = null

    /** R20/A2 — geracao corrente para identidade do request (no-op no produto). */
    fun setCurrentGen(gen: Long) {}
    /** R22/Q1 — selecao pendente da bancada (no-op no produto). */
    fun applyPendingModelSelection(activity: android.app.Activity) {}
    /** R24/REQ — janela de audio_ctx da tentativa (produto: zero). */
    fun audioCtxWindowSamples(): Long = 0L

    /** R25/REQ — produção NUNCA manda pedido de tentativa: a entrada aditiva
     *  do nativo fica inerte e o caminho owned/legado continua o mesmo. */
    fun attemptRequest(token: String, owner: Long, gen: Long, job: Long, attempt: Long): AttemptRequest? = null
}
