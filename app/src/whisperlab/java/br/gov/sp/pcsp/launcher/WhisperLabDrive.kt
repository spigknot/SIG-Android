package br.gov.sp.pcsp.launcher

import android.app.Activity
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * R4/C3 — DRIVE DE LABORATÓRIO da WhisperActivity REAL (existente SÓ no
 * flavor `whisperlab`; não há nada disto no APK de produção).
 *
 * A Activity real é aberta por Intent explícito, do MESMO pacote (o
 * `android:exported=false` do manifest de produção continua valendo; caller
 * interno do app pode iniciar). O drive NÃO recria os botões nem o algoritmo:
 * ele só aciona os MESMOS listeners do produto (performClick nos ids reais) e
 * lê o estado dos MESMOS widgets. As injeções (fonte PCM, atrasos, falhas)
 * chegam à Activity pelos seams [WhisperLabSeams] consultados nos pontos fixos
 * do produto.
 *
 * Eventos: JSONL append em [logFile]; cada linha {ts, wall, run, scenario,
 * inst, event, ...kv}. Heartbeat de 100 ms na main thread com distribuição por
 * faixas de atraso + máximo, ativo da criação ao finish de cada instância.
 */
object WhisperLabDrive {

    const val TAG = "WhisperLab"

    @Volatile var active = false
    @Volatile var runId = ""
    @Volatile var scenario = ""
    @Volatile var logFile: File? = null

    // ---- fontes injetadas (PCM cru mono 16 kHz s16le) ----
    @Volatile var livePcmPath: String? = null
    @Volatile var livePcmSpeed = 0.0
    @Volatile var livePcmFailAfterReads = 0
    @Volatile var livePcmReadBytes = 3200
    @Volatile var recordPcmPath: String? = null
    @Volatile var recordPcmSpeed = 0.0

    // ---- comportamento do produto ----
    @Volatile var modelLoadDelayMs = 0L
    @Volatile var chunkDelayMs = 0L
    @Volatile var listenerFaultChunk = -1
    @Volatile var spoolLimitBytes = -1L
    @Volatile var injectChunkIndex = -1
    @Volatile var injectChunkText = ""
    @Volatile var injectChunkStatus = ""
    // ---- R6/D2: estratégia de janela do caminho ao vivo (ms; -1 = default) ----
    @Volatile var liveHopMs = -1L
    @Volatile var liveContextMs = -1L
    // ---- R7/E3: estratégia do caminho ao vivo ("fixed"/"vad"; "" = default) ----
    @Volatile var liveStrategyKind = ""
    @Volatile var livePadMs = -1L
    @Volatile var liveMaxSegmentMs = -1L
    @Volatile var liveSilenceCutMs = -1L
    /** R8/D (candidata 1) — teto da 1ª janela em ms (0/negativo = desligado). */
    @Volatile var liveFirstSegmentMs = -1L
    /** R8/D (candidata 1) — corte de silêncio da 1ª janela (0/negativo = desligado). */
    @Volatile var liveFirstSilenceCutMs = -1L
    /** R8/D (candidata 2) — publicar onSegment incremental como parcial provisória. */
    @Volatile var liveIncrementalPartial = false
    /** R8/D — pausa de scheduler injetada na fonte: em [livePauseAtMs] de áudio
     * (ms), uma única espera de [livePauseForMs]. */
    @Volatile var livePauseAtMs = -1L
    @Volatile var livePauseForMs = 0L
    // ---- R6/D3: fixture da rota de ARQUIVO (nome servido pelo provider do lab) ----
    @Volatile var mediaFixtureName: String? = null
    /** R6/D3: ms após onActivityReady para clicar "transcrever" (arquivo). */
    @Volatile var fileTranscribeClickMs = -1L
    /** R14/A2 — quando true, o export do ensaio EXIGE payload do job (nunca
     *  aceita widget legado como resultado). Falta de payload = erro diagnóstico. */
    @Volatile var requirePayloadExport = false
    /** R6/D3: ms após o clique de transcrever para clicar voltar (cancela). */
    @Volatile var fileCancelAfterTranscribeMs = -1L
    /** R6/D3: 2º job usa o botão transcrever (rota de arquivo) em vez do live. */
    @Volatile var fileSecondJob = false

    // ---- preparação de campos REAIS da UI avançada (aplicados no install,
    //      como se o usuário tivesse digitado/marcado; null = não tocar) ----
    @Volatile var beamToSet: Int? = null
    @Volatile var bestOfToSet: Int? = null
    @Volatile var vadToSet: Boolean? = null
    /**
     * R11/D2 — SEAM DE THREADS (bancada): 0 = nenhum (a política do nativo
     * decide, como em produção). Valor > 0 = o PRÓXIMO job roda com esse número
     * de threads (`lab_override` no log nativo), one-shot: consumido pelo job e
     * nunca reaplicado. Existe para o par controlado t4 × política no MESMO
     * caminho/lib/params.
     */
    @Volatile var threadsToSet: Int = 0
    /** R14/D2-D3 — audio_ctx experimental do PRÓXIMO job (0 = default). */
    @Volatile var audioCtxToSet: Int = 0
    /** R25/REQ — política/captura da TENTATIVA, com o mesmo valor que o Intent
     *  do lab declara (uma fonte só). `null`/0 = DEFAULT explícito.
     *  - audioCtxWindowToSet: amostras de PCM real (0 = contexto completo);
     *  - submitMbToSet: 0 = DEFAULT; >0 = override;
     *  - serialToSet/kToSet: -1 = DEFAULT, 0/1 e >=0 = valor pedido;
     *  - dump*: espelho EXATO do que o config() do lab aplicou. */
    @Volatile var audioCtxWindowToSet: Long = 0L
    @Volatile var submitMbToSet: Int = 0
    @Volatile var serialToSet: Int = -1
    @Volatile var kToSet: Int = -1
    @Volatile var dumpKindsToSet: String = ""
    @Volatile var dumpDirToSet: String = ""
    @Volatile var dumpTagToSet: String = ""
    @Volatile var dumpFirstToSet: Int = 0
    @Volatile var dumpStepToSet: Int = 0
    @Volatile var dumpMaxToSet: Int = 0
    @Volatile var dumpStepsToSet: Int = 1
    /** R14/E4 — ESTADO DA TELA observado pelo lab, com clock MONOTÔNICO.
     * É o que permite provar "READ/ENQUEUE depois do OFF" (a revisão apontou:
     * "o gate conta reads e qualquer OFF, sem correlacionar seus intervalos"). */
    @Volatile var screenState: String = "UNKNOWN"
    @Volatile var screenStateAt: Long = 0L
    @Volatile var screenOffAt: Long = 0L
    @Volatile var screenOnAt: Long = 0L
    /** R14/E1 — nome da fixture de MODELO a importar pelo fluxo SAF real. */
    @Volatile var modelImportFixtureName: String? = null
    /** R14/E1 — ms após o clique em "Selecionar arquivo de modelo…" para
     *  entregar o Uri ao produto (a importação real do lab acontece quando o
     *  provider entrega o arquivo). */
    @Volatile var modelImportClickAfterMs: Long = -1L
    /** R18/A2 — SHA-256 que a SELEÇÃO CORRENTE precisa ter antes de qualquer
     *  inferência. null = não gatear (cenários que não comparam modelo). */
    @Volatile var waitForImportSha: String? = null
    /** R18/A2 — budget para a seleção válida aparecer; estourar = INCOMPLETE
     *  sem inferência (nunca medir o modelo errado). */
    @Volatile var importSelectBudgetMs: Long = 30_000L

    /** R18/A2 — lê o próprio JSONL e diz se um evento (e um trecho) já saiu.
     *  Serve para não duplicar fallback de importação e para o gate da seleção. */

    /**
     * R19/A4 — ULTIMA linha de um evento, e so a partir de `desdeMono`.
     * `arquivoTemEvento` aceitava QUALQUER linha historica do arquivo: uma
     * selecao antiga com o SHA certo liberava o gate mesmo sem selecao corrente
     * (o defeito que a revisao da R18 apontou).
     */
    private fun ultimaLinhaEvento(evento: String, desdeMono: Long = 0L): String? {
        val f = logFile ?: return null
        if (!f.isFile) return null
        return runCatching {
            var achado: String? = null
            f.forEachLine { l ->
                if (l.contains("\"event\":\"$evento")) {
                    val mono = runCatching {
                        org.json.JSONObject(l).optLong("mono", -1L)
                    }.getOrDefault(-1L)
                    if (mono < 0L || mono >= desdeMono) achado = l
                }
            }
            achado
        }.getOrNull()
    }

    private fun arquivoTemEvento(evento: String, contem: String = ""): Boolean {
        val f = logFile ?: return false
        if (!f.isFile) return false
        return runCatching {
            f.useLines { seq ->
                seq.any { l -> l.contains("\"event\":\"$evento") && (contem.isEmpty() || l.contains(contem)) }
            }
        }.getOrDefault(false)
    }

    /**
     * R11/D4 — liga/desliga o FOREGROUND SERVICE do job nesta instância de lab
     * (default `true` = comportamento de produção). `false` roda o braço de
     * controle "sem dono de lifecycle" no MESMO binário.
     */
    @Volatile var jobFgsEnabled: Boolean = true
    /**
     * R9/G3 — rotulo do MODELO a selecionar no MENU REAL antes do 1o passo da
     * sequencia FA (ex.: "v3Turbo"). null = nao tocar no menu de modelo. A
     * sequencia NAO comeca enquanto o botao nao mostrar o rotulo pedido.
     */
    @Volatile var modelLabelToSet: String? = null

    /** R21/Q1 — arquivo de modelo a selecionar direto (seam lab-only). */
    @Volatile var modelFileToSet: String? = null

    // R22/A1 — identidade do produtor imutável e da configuração da célula.
    @Volatile var producerId: String = ""
    @Volatile var configId: String = ""

    /** R19/A4 — backend (0 CPU, 1 Vulkan, 2 OpenCL) e FA forcados; null = produto. */
    /** R20/A2 — geração do job em curso (identidade do request/terminal). */
    @Volatile var currentGen: Long = 0L
    @Volatile var forcedBackendKind: Int? = null
    @Volatile var forcedFlashAttention: Boolean? = null

    // ---- segundo job na MESMA instância (reuse/cache-hit), depois do 1º
    //      veredito ("Ao vivo "): -1 = desligado ----
    @Volatile var secondJobStartAfterFirstVerdictMs = -1L
    @Volatile var secondJobStopAfterStartMs = -1L

    // ---- roteiro relativo ao onActivityReady (ms; <0 = desligado) ----
    @Volatile var clickLiveStartMs = -1L
    @Volatile var clickLiveStopMs = -1L
    @Volatile var clickRecordStartMs = -1L
    @Volatile var clickRecordStopMs = -1L
    @Volatile var clickBackMs = -1L
    @Volatile var forceFinishMs = -1L
    @Volatile var dumpEveryMs = 1000L
    /**
     * R9/B1: `main_stall` sem o thread-dump do processo inteiro (o campo
     * `threads` chega a ~2,5 kB por snapshot). Mantém late_ms/estado/pilha
     * da main (top 12) — suficiente para contar e localizar a espera, sem
     * produzir dump gigante por frame nas pernas de controle da matriz.
     */
    @Volatile var stallLite = false
    /** Encerra (com dump final) quando o status COMEÇAR com este prefixo. */
    @Volatile var finishOnStatusPrefix: String? = null
    /** Encerra (com dump final) quando o status CONTIVER este texto. */
    @Volatile var finishOnStatusContains: String? = null
    /** Teto absoluto: encerra de qualquer forma (dump final). */
    @Volatile var hardFinishMs = -1L
    /** STOP do job ao vivo após o status virar "Ouvindo..." (robusto ao tempo
     * de load). Uma vez por fase "Ouvindo" (2º job re-arma após veredito). */
    @Volatile var stopAfterOuvindoMs = -1L

    // ---- R8/A2+A3: sequência de UI REAL (controles do painel avançado) ----
    // ex.: "vulkan_on,silencio,cpu_on,vulkan_off,opencl_on,cancel_progress,recovery"
    @Volatile var faSeqPlan: String? = null
    /** PCM de FALA para os passos live (o passo "silencio" gera zeros próprio). */
    @Volatile var faSeqSpeechPcm: String? = null
    @Volatile var faSeqZerosMs: Long = 3000L
    /** Teto por passo (ms) — evita travar a bateria se um job não fechar. */
    @Volatile var faSeqStepCapMs: Long = 150_000L
    /** R8/A2: carimbo do último `live_terminal` do PROCESSO (job do passo). */
    @Volatile var lastLiveTerminalAt = 0L
    @Volatile var lastLiveTerminalJob = -1L
    /** R8/D (fecho) — CORRIDA export×terminal-publish: carimbos do último
     * `live_terminal` (instância/job/cancelamento) e do último `clock_ui_publish`
     * com source=terminal_final (a PUBLICAÇÃO do texto final no widget). O export
     * do fulltext só sai depois desta publicação da MESMA instância/job. */
    @Volatile var lastLiveTerminalInst = -1
    @Volatile var lastLiveTerminalJobId = -1L
    @Volatile var lastLiveTerminalCancelled = false
    @Volatile var lastTerminalFinalAt = 0L
    @Volatile var lastTerminalFinalInst = -1
    @Volatile var lastTerminalFinalJobId = -1L
    /** R8/D (fecho): espera máxima (ms) pelo publish do terminal antes do export. */
    @Volatile var terminalFinalWaitMs = 15_000L

    fun reset() {
        active = false
        // R15/B1 — sem herança entre células: prazo curto e supressão de
        // progresso valem só para o ensaio que os pediu.
        leaseTimeoutMs = 0L
        leaseIgnoreProgress = false
        expectedModelPath = null
        destroyAfterMs = -1L
        runId = ""
        scenario = ""
        logFile = null
        livePcmPath = null; livePcmSpeed = 0.0; livePcmFailAfterReads = 0; livePcmReadBytes = 3200
        recordPcmPath = null; recordPcmSpeed = 0.0
        modelLoadDelayMs = 0L; chunkDelayMs = 0L
        listenerFaultChunk = -1; spoolLimitBytes = -1L
        injectChunkIndex = -1; injectChunkText = ""; injectChunkStatus = ""
        liveHopMs = -1L; liveContextMs = -1L
        liveStrategyKind = ""; livePadMs = -1L; liveMaxSegmentMs = -1L; liveSilenceCutMs = -1L
        liveFirstSegmentMs = -1L; liveFirstSilenceCutMs = -1L; liveIncrementalPartial = false
        livePauseAtMs = -1L; livePauseForMs = 0L
        mediaFixtureName = null
        fileTranscribeClickMs = -1L; fileCancelAfterTranscribeMs = -1L; fileSecondJob = false
        requirePayloadExport = false
        clickLiveStartMs = -1L; clickLiveStopMs = -1L
        clickRecordStartMs = -1L; clickRecordStopMs = -1L
        clickBackMs = -1L; forceFinishMs = -1L
        dumpEveryMs = 1000L
        finishOnStatusPrefix = null; finishOnStatusContains = null
        hardFinishMs = -1L
        stopAfterOuvindoMs = -1L
        beamToSet = null; bestOfToSet = null; vadToSet = null
        threadsToSet = 0
        audioCtxToSet = 0
        audioCtxWindowToSet = 0L
        submitMbToSet = 0
        serialToSet = -1
        kToSet = -1
        dumpKindsToSet = ""; dumpDirToSet = ""; dumpTagToSet = ""
        dumpFirstToSet = 0; dumpStepToSet = 0; dumpMaxToSet = 0; dumpStepsToSet = 1
        screenState = "UNKNOWN"; screenStateAt = 0L; screenOffAt = 0L; screenOnAt = 0L
        modelImportFixtureName = null
        modelImportClickAfterMs = -1L
        waitForImportSha = null
        importSelectBudgetMs = 30_000L
        modelLabelToSet = null
        producerId = ""; configId = ""
        forcedBackendKind = null; forcedFlashAttention = null
        secondJobStartAfterFirstVerdictMs = -1L; secondJobStopAfterStartMs = -1L
        faSeqPlan = null; faSeqSpeechPcm = null; faSeqZerosMs = 3000L; faSeqStepCapMs = 150_000L
        lastLiveTerminalInst = -1; lastLiveTerminalJobId = -1L; lastLiveTerminalCancelled = false
        lastTerminalFinalAt = 0L; lastTerminalFinalInst = -1; lastTerminalFinalJobId = -1L
        terminalFinalWaitMs = 15_000L
        // R12/A2: run novo não vê resultado de run anterior (export por passo
        // exige terminal publicado DEPOIS do início do passo).
        stepLedgerSeq = -1L
        TranscriptionJobResults.ledger.reset()
    }

    // ------------------------------------------------------------------ log

    private val logLock = Any()

    /**
     * R14/E4 — Observa o estado da TELA e carimba a TRANSIÇÃO com clock
     * MONOTÔNICO (`elapsedRealtime`). Sem isto, "houve um OFF" não prova
     * "houve leitura DEPOIS do OFF".
     */
    private var screenWatcher: Thread? = null

    fun startScreenWatcher(ctxIn: android.content.Context) {
        if (screenWatcher != null) return
        // R15/E3 — APPLICATION context: o watcher vive enquanto o processo
        // viver e nao pode reter a Activity.
        val ctx = ctxIn.applicationContext
        // identidade do processo/run: sem isso o analisador nao consegue
        // distinguir o OFF deste run de um OFF de run antigo.
        val pid = android.os.Process.myPid()
        val boot = SystemClock.elapsedRealtime() - android.os.SystemClock.uptimeMillis()
        // R15/E3 — o run e' lido a cada evento: o watcher pode nascer antes de
        // o cenario definir o runId, e gravar "" tornaria a evidencia inutil.
        screenWatcher = Thread {
            var last = ""
            var runDaUltimaLinha = ""
            // R15/E3 — SNAPSHOT inicial: o estado no começo do job nao pode ser
            // presumido; sem ele "houve OFF" nao sabe de onde partiu.
            runCatching {
                val pm0 = ctx.getSystemService(android.content.Context.POWER_SERVICE)
                    as? android.os.PowerManager
                val inicial = if (pm0 == null) "UNKNOWN"
                    else if (pm0.isInteractive) "ON" else "OFF"
                val agora = SystemClock.elapsedRealtime()
                last = inicial
                screenState = inicial
                screenStateAt = agora
                val dir0 = File(ctx.filesDir, "art-lab")
                dir0.mkdirs()
                runDaUltimaLinha = runId
                File(dir0, "screen-state.jsonl").appendText(
                    "{\"ts\":$agora,\"state\":\"$inicial\",\"run\":\"$runDaUltimaLinha\"," +
                    "\"pid\":$pid,\"boot\":$boot,\"snapshot\":1}\n")
            }
            while (true) {
                // API pública: o PowerManager sabe se a tela está ligada.
                val pm = ctx?.getSystemService(android.content.Context.POWER_SERVICE)
                    as? android.os.PowerManager
                val st = if (pm == null) "UNKNOWN"
                    else if (pm.isInteractive) "ON" else "OFF"
                val now = SystemClock.elapsedRealtime()
                val cur = st
                // R15/E3 — run novo (o cenario acabou de definir o runId):
                // snapshot proprio, sem herdar a historia do run anterior.
                if (runId != runDaUltimaLinha && runId.isNotEmpty()) {
                    runDaUltimaLinha = runId
                    runCatching {
                        val d = File(ctx.filesDir, "art-lab")
                        d.mkdirs()
                        File(d, "screen-state.jsonl").appendText(
                            "{\"ts\":$now,\"state\":\"$cur\",\"run\":\"$runDaUltimaLinha\"," +
                            "\"pid\":$pid,\"boot\":$boot,\"snapshot\":1}\n")
                    }
                }
                if (cur != last) {
                    last = cur
                    screenState = cur
                    screenStateAt = now
                    if (cur == "OFF" || cur == "Dozing" || cur == "DOZE") {
                        screenOffAt = now
                    } else if (cur == "ON") {
                        screenOnAt = now
                    }
                    log("screen_state", "state" to cur, "mono_ms" to now)
                    // arquivo PRÓPRIO: o `log()` depende do `logFile` (que é
                    // definido depois do reset do cenário) — a transição de
                    // tela não pode se perder por isso.
                    runCatching {
                        val dir = File(ctx.filesDir, "art-lab")
                        dir.mkdirs()
                        runDaUltimaLinha = runId
                        File(dir, "screen-state.jsonl").appendText(
                            "{\"ts\":$now,\"state\":\"$cur\",\"run\":\"$runDaUltimaLinha\"," +
                            "\"pid\":$pid,\"boot\":$boot,\"snapshot\":0}\n")
                    }
                }
                try { Thread.sleep(200) } catch (_: InterruptedException) { return@Thread }
            }
        }.apply { isDaemon = true; name = "lab-screen-watcher"; start() }
    }

    fun log(event: String, vararg kv: Pair<String, Any?>) {
        // R8/A2: carimba `live_terminal` no processo — a sequência FA usa isso
        // para saber que o JOB DO PASSO terminou (EOF do PCM injetado NÃO
        // atualiza o status da UI; sem o carimbo o passo só fecharia no teto).
        if (event == "live_terminal") {
            lastLiveTerminalAt = SystemClock.elapsedRealtime()
            lastLiveTerminalInst = instanceCount.get()
            val job = kv.firstOrNull { it.first == "job" }?.second
            if (job is Number) lastLiveTerminalJob = job.toLong()
            val jid = kv.firstOrNull { it.first == "job_id" }?.second
            if (jid is Number) lastLiveTerminalJobId = jid.toLong()
            val cancelled = kv.firstOrNull { it.first == "cancelled" }?.second
            if (cancelled is Boolean) lastLiveTerminalCancelled = cancelled
        }
        // R8/D (fecho): carimba a PUBLICAÇÃO do texto final (clock_ui_publish
        // source=terminal_final) — é ESTA que destrava o export do fulltext.
        if (event == "clock_ui_publish") {
            val source = kv.firstOrNull { it.first == "source" }?.second
            if (source == "terminal_final") {
                lastTerminalFinalAt = SystemClock.elapsedRealtime()
                lastTerminalFinalInst = instanceCount.get()
                val jid = kv.firstOrNull { it.first == "job_id" }?.second
                if (jid is Number) lastTerminalFinalJobId = jid.toLong()
            }
        }
        val file = logFile ?: return
        val obj = JSONObject()
        obj.put("ts", SystemClock.elapsedRealtime())
        obj.put("wall", System.currentTimeMillis())
        obj.put("run", runId)
        obj.put("scenario", scenario)
        obj.put("inst", instanceCount.get())
        obj.put("event", event)
        for ((k, v) in kv) obj.put(k, v ?: JSONObject.NULL)
        val line = obj.toString()
        Log.i(TAG, "C3 $line".take(3500))
        try {
            synchronized(logLock) { file.appendText(line + "\n", Charsets.UTF_8) }
        } catch (t: Throwable) {
            Log.e(TAG, "drive log falhou: $t")
        }
        // R28/A5 — SNAPSHOT DE ESTADO: o runner lê UM arquivo pequeno em vez de
        // varrer 60 mil linhas de JSONL vivo (leitura que falhava/truncava).
        runCatching { atualizarSnapshot(event, kv) }
    }

    // R29/A5 — SNAPSHOT DO WORKER (contrato pequeno e ESTRITO).
    //
    // Defeitos da R28 corrigidos aqui:
    //   * `active` era ligado/desligado por NOME de evento: um terminal de
    //     geração ANTIGA apagava a geração nova (falso OCIOSO) e um evento sem
    //     identidade ligava "trabalho" sem prova;
    //   * `boot_id` vazio em caso de erro de leitura era indistinguível de
    //     "sem boot" — agora há `boot_ok` explícito;
    //   * o `.tmp` era um nome FIXO compartilhado por callbacks concorrentes, e
    //     em falha de rename o destino era APAGADO. Agora a publicação é
    //     serializada, o tmp é único por escrita e a fotografia válida anterior
    //     nunca é destruída por uma falha;
    //   * não existia `schema`: o runner não podia distinguir ausência de campo
    //     de campo com tipo errado.
    @Volatile private var ativo = false
    @Volatile private var ultimoEvento = ""
    @Volatile private var ultimoOwner = 0L
    @Volatile private var ultimoGen = 0L
    @Volatile private var ultimoJob = 0L
    @Volatile private var ultimoAttempt = 0L
    @Volatile private var terminalCasado = false
    @Volatile private var terminalDivergente = 0L
    @Volatile private var ativoDesdeMs = 0L
    private val snapLock = Any()
    private var snapSeq = 0L

    private fun atualizarSnapshot(event: String, kv: Array<out Pair<String, Any?>>) {
        fun num(name: String): Long? =
            (kv.firstOrNull { it.first == name }?.second as? Number)?.toLong()

        val evOwner = num("owner")
        val evGen = num("gen")
        val evJob = num("job")
        val evAttempt = num("attempt")

        // Identidade VIGENTE (antes deste evento): um terminal só encerra a
        // geração a que pertence.
        val genVigente = ultimoGen
        val jobVigente = ultimoJob
        val attemptVigente = ultimoAttempt

        when (event) {
            "worker_reserved", "job_fgs_start" -> {
                ativo = true
                terminalCasado = false
                ativoDesdeMs = System.currentTimeMillis()
                evOwner?.let { ultimoOwner = it }
                evGen?.let { ultimoGen = it }
                evJob?.let { ultimoJob = it }
                evAttempt?.let { ultimoAttempt = it }
            }
            "job_fgs_terminal" -> {
                // Só encerra quando a identidade do terminal corresponde à
                // reserva vigente. Sem identidade, NÃO se encerra (recusa a
                // inferência por nome de evento).
                val casaGen = evGen == null || evGen == genVigente
                val casaJob = evJob == null || jobVigente == 0L || evJob == jobVigente
                val casaAttempt = evAttempt == null || attemptVigente == 0L || evAttempt == attemptVigente
                if (casaGen && casaJob && casaAttempt) {
                    ativo = false
                    terminalCasado = true
                } else {
                    terminalDivergente += 1   // terminal de geração antiga
                }
            }
            else -> { }
        }
        evOwner?.let { if (event != "job_fgs_terminal") ultimoOwner = it }
        evGen?.let { if (event != "job_fgs_terminal") ultimoGen = it }
        evJob?.let { if (event != "job_fgs_terminal") ultimoJob = it }
        evAttempt?.let { if (event != "job_fgs_terminal") ultimoAttempt = it }

        if (event in setOf("activity_ready", "cell_header", "worker_reserved", "job_fgs_start",
                           "model_loaded", "job_fgs_terminal", "drive_finish")) {
            ultimoEvento = event
        }
        val base = logFile ?: return
        val dir = base.parentFile ?: return

        // boot_id com estado EXPLÍCITO: erro de leitura não vira string vazia
        // silenciosa (o runner recusa com UNKNOWN em vez de supor ociosidade).
        var boot = ""
        var bootOk = true
        try {
            boot = java.io.File("/proc/sys/kernel/random/boot_id").readText().trim()
            if (boot.isEmpty()) bootOk = false
        } catch (t: Throwable) {
            bootOk = false
        }
        val pid = android.os.Process.myPid()
        // R55/B04 — IDENTIDADE COMPLETA do escritor: pid sozinho é reciclado
        // pelo Android. O start_ticks (campo 22 de /proc/self/stat) é o que
        // distingue "outro processo" de "mesmo número reaproveitado".
        val startTicks = lerStartTicks()

        synchronized(snapLock) {
            snapSeq += 1
            val snap = JSONObject()
            snap.put("schema", 1)
            snap.put("boot_id", boot)
            snap.put("boot_ok", bootOk)
            snap.put("pid", pid)
            snap.put("pid_start_ticks", startTicks)
            snap.put("run", runId)
            snap.put("active", ativo)
            snap.put("last_event", ultimoEvento)
            snap.put("owner", ultimoOwner)
            snap.put("gen", ultimoGen)
            snap.put("job", ultimoJob)
            snap.put("attempt", ultimoAttempt)
            snap.put("terminal_casado", terminalCasado)
            snap.put("terminal_divergente", terminalDivergente)
            snap.put("active_since_ms", ativoDesdeMs)
            snap.put("updated_ms", System.currentTimeMillis())
            snap.put("writer_seq", snapSeq)

            val dst = java.io.File(dir, "state.json")
            // R55/B04 — HANDOVER: na PRIMEIRA escrita deste processo, o snapshot
            // anterior (de outro escritor) é LIDO e sua identidade preservada
            // aqui. Sem isto a única saída para um arquivo de processo morto
            // era force-stop; e o arquivo velho podia ser lido como "instância
            // viva" (ou o contrário). O destino NUNCA é apagado antes do rename.
            if (snapSeq == 1L) {
                val anterior = lerSnapshotAnterior(dst)
                if (anterior != null) {
                    val vivo = pidVivo(anterior.pid)
                    val veredito = LabSnapshotIdentity.classify(
                        LabSnapshotIdentity.Writer(boot, pid, startTicks),
                        anterior,
                        previousPidAlive = vivo,
                    )
                    snap.put("handover_verdict", veredito.name)
                    snap.put("handover_blocks_preflight",
                        LabSnapshotIdentity.blocksPreflight(veredito))
                    snap.put("handover_from_boot_id", anterior.bootId)
                    snap.put("handover_from_pid", anterior.pid)
                    snap.put("handover_from_start_ticks", anterior.startTicks)
                    snap.put("handover_from_seq", anterior.seq)
                    snap.put("handover_from_active", anterior.active)
                    snap.put("handover_from_last_event", anterior.lastEvent)
                    Log.i(TAG, "snapshot handover: ${veredito.name} de pid=${anterior.pid} " +
                        "seq=${anterior.seq} active=${anterior.active} " +
                        "last_event=${anterior.lastEvent}")
                } else {
                    snap.put("handover_verdict", LabSnapshotIdentity.Verdict.NO_SNAPSHOT.name)
                    snap.put("handover_blocks_preflight", false)
                }
            }
            val tmp = java.io.File(dir, "state.json.$pid.$snapSeq.tmp")
            try {
                tmp.writeText(snap.toString(), Charsets.UTF_8)
                // rename atômico no mesmo diretório; NUNCA apagar o destino
                // anterior: se falhar, a fotografia válida permanece.
                if (!tmp.renameTo(dst)) {
                    throw java.io.IOException("rename state.json falhou")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "snapshot falhou: $t")
                runCatching { tmp.delete() }
            }
        }
    }

    /**
     * R55/B04 — `starttime` (campo 22) do processo atual. -1 quando ilegível:
     * desconhecido é EXPLÍCITO, nunca "0" silencioso.
     */
    private fun lerStartTicks(): Long = try {
        LabSnapshotIdentity.parseStartTicks(
            java.io.File("/proc/self/stat").readText()
        ) ?: -1L
    } catch (t: Throwable) {
        -1L
    }

    /** R55/B04 — identidade do snapshot anterior, se existir e for legível. */
    private fun lerSnapshotAnterior(f: java.io.File): LabSnapshotIdentity.Writer? = try {
        if (f.isFile) {
            val o = JSONObject(f.readText(Charsets.UTF_8))
            LabSnapshotIdentity.Writer(
                bootId = o.optString("boot_id", ""),
                pid = o.optInt("pid", -1),
                startTicks = o.optLong("pid_start_ticks", -1L),
                seq = o.optLong("writer_seq", 0L),
                active = o.optBoolean("active", false),
                lastEvent = o.optString("last_event", ""),
            )
        } else null
    } catch (t: Throwable) {
        null
    }

    /** `/proc/<pid>` existe agora? (leitura; não sinaliza nem mata nada). */
    private fun pidVivo(pid: Int): Boolean =
        pid > 0 && runCatching { java.io.File("/proc/$pid").exists() }.getOrDefault(false)

    // ------------------------------------------------------------- instâncias

    private val instanceCount = AtomicInteger(0)

    fun nextInstance(): Int = instanceCount.incrementAndGet()

    fun currentInstance(): Int = instanceCount.get()

    // R8/D (fecho) — LAZY de propósito: no JVM de teste unitário (variante
    // whisperlab) a leitura de `active` por um seam dispara o INIT desta
    // classe; com o Handler criado no init, `Looper.getMainLooper()` (não
    // mockado) lançava e o store do pipeline virava "falha de spool" — 24
    // testes de pipeline quebravam SÓ no flavor lab. No device o init segue no
    // primeiro uso, na main (install) ou na thread que postar.
    private val main: Handler by lazy { Handler(Looper.getMainLooper()) }

    private var hbLast = 0L
    private var hbMax = 0L
    private var hbTicks = 0
    private val hbHist = IntArray(6) // <10,<30,<100,<300,<1000,>=1000 ms

    // ---- R5/A1: watch de stall da MAIN (lab) ---------------------------------
    // Objetivo: LOCALIZAR a espera (não só medir o sintoma). Uma thread própria
    // observa o heartbeat da main; quando a main atrasa > [STALL_THRESHOLD_MS],
    // tira um thread-dump do PROCESSO (Thread.getAllStackTraces) enquanto ela
    // ainda está PRESA e registra o evento `main_stall` com a pilha da main e
    // das threads candidatas (Whisper*/LivePcm*/lab-*). O critério R5 separa:
    // >300 ms = investigação; >=1000 ms em período ativo = reprova
    // responsividade (a decisão é do analyze_c3_r5.py; aqui só se captura).
    @Volatile private var hbArmed = false
    @Volatile private var hbLastTick = 0L
    @Volatile private var stallWatchStarted = false
    private const val STALL_THRESHOLD_MS = 300L

    private fun hbSnapshot(): String {
        return "{\"max_ms\":$hbMax,\"ticks\":$hbTicks,\"lt10\":${hbHist[0]},\"lt30\":${hbHist[1]}," +
            "\"lt100\":${hbHist[2]},\"lt300\":${hbHist[3]},\"lt1000\":${hbHist[4]},\"ge1000\":${hbHist[5]}}"
    }

    /** R5/A1: thread única (processo) que vigia a main e tira o thread-dump
     * ENQUANTO ela está presa. Evento `main_stall` = espera localizada. */
    private fun startStallWatch() {
        if (stallWatchStarted) return
        stallWatchStarted = true
        val t = Thread {
            var reportedAt = 0L
            while (true) {
                try {
                    Thread.sleep(200)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                if (!active || !hbArmed) { reportedAt = 0L; continue }
                val last = hbLastTick
                if (last == 0L) continue
                val now = SystemClock.uptimeMillis()
                val late = now - last - 100L
                if (late >= STALL_THRESHOLD_MS) {
                    // Enquanto a main está presa, re-amostra a cada ~1s.
                    if (reportedAt == 0L || now - reportedAt >= 1000L) {
                        reportedAt = now
                        logStall(late)
                    }
                } else if (late < STALL_THRESHOLD_MS / 2) {
                    reportedAt = 0L
                }
            }
        }
        t.isDaemon = true
        t.name = "lab-stall-watch"
        t.start()
    }

    private fun logStall(lateMs: Long) {
        val stacks: Map<Thread, Array<StackTraceElement>> = try {
            Thread.getAllStackTraces()
        } catch (_: Throwable) {
            emptyMap()
        }
        val mainEntry = stacks.entries.firstOrNull { it.key.name == "main" }
        val mainStack = mainEntry?.value?.take(40)?.joinToString(" | ") { it.toString() } ?: "?"
        val mainState = mainEntry?.key?.state?.toString() ?: "?"
        val others = StringBuilder()
        if (stallLite) {
            // R9/B1: perna de controle enxuta (sem dump de todas as threads).
            log(
                "main_stall",
                "late_ms" to lateMs,
                "main_state" to mainState,
                "main_stack" to (mainEntry?.value?.take(12)?.joinToString(" | ") { it.toString() } ?: "?"),
                "lite" to true
            )
            return
        }
        for (entry in stacks.entries) {
            if (entry.key.name == "main") continue
            val st = entry.value
            val txt = st.joinToString(" | ") { it.toString() }
            val relevant = txt.contains("Whisper") || txt.contains("LivePcm") ||
                txt.contains("Thread-") || txt.contains("lab-")
            if (!relevant) continue
            if (others.length > 2200) { others.append(" ...(truncado)"); break }
            others.append(" [").append(entry.key.name).append(' ').append(entry.key.state).append("] ")
            others.append(st.take(14).joinToString(" | ") { it.toString() })
        }
        log(
            "main_stall",
            "late_ms" to lateMs,
            "main_state" to mainState,
            "main_stack" to mainStack,
            "threads" to others.toString()
        )
    }

    /** R28/A5 — configuração EFETIVA da célula (não um apelido fixo). */
    private fun configEfetiva(): String {
        val k = System.getenv("SIG_VK_INFLIGHT_K") ?: "0"
        val tl = System.getenv("SIG_VK_LEDGER_TL") ?: "0"
        val ser = System.getenv("SIG_VK_SERIAL_BATCH") ?: "0"
        return "cfg-b$forcedBackendKind-fa${if (forcedFlashAttention == true) 1 else 0}-k$k-tl$tl-ser$ser"
    }

    private fun memSnapshot(): String {
        return "{\"java_used_kb\":${(Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024}," +
            "\"native_alloc_kb\":${Debug.getNativeHeapAllocatedSize() / 1024}}"
    }

    /** Instala o roteiro na Activity REAL, no fim do onCreate dela. */
    fun install(activity: Activity) {
        if (!active) return
        val inst = nextInstance()
        resetCounters()
        log("activity_ready", "inst" to inst, "pid" to android.os.Process.myPid())
        // R21/A2 — CABECALHO imutavel da celula: producer/config/boot/pid. O
        // coletor exige estes campos para aceitar qualquer amostra.
        run {
            // R28/A5 — IDENTIDADE REAL do produtor: digest da biblioteca
            // instalada (não um producer.json antigo de outra rodada).
            val libFile = java.io.File(activity.filesDir, "whisperlab-libs/libsig_whisper.so")
            val libSha = runCatching {
                val md = java.security.MessageDigest.getInstance("SHA-256")
                libFile.inputStream().use { ins ->
                    val buf = ByteArray(1 shl 20)
                    while (true) {
                        val n = ins.read(buf)
                        if (n <= 0) break
                        md.update(buf, 0, n)
                    }
                }
                md.digest().joinToString("") { "%02x".format(it) }
            }.getOrDefault("")
            val libBytes = runCatching { libFile.length() }.getOrDefault(0L)
            val prod = "{\"producer_id\":\"$libSha\",\"bytes\":$libBytes," +
                "\"source\":\"libsig_whisper.so instalada\"}"
            val boot = runCatching {
                java.io.File("/proc/sys/kernel/random/boot_id").readText().trim()
            }.getOrDefault("")
            val pid = android.os.Process.myPid()
            val instAtual = inst
            // R22/A1 — cabecalho carrega o PRODUTOR e a CONFIGURACAO que
            // executam, alem da identidade do token (owner/gen/attempt).
            log("cell_header", "producer_json" to prod, "boot_id" to boot,
                "pid" to pid, "inst" to instAtual, "run" to runId,
                "producer" to (if (libSha.isNotEmpty()) libSha.take(16) else producerId),
                "config_id" to configEfetiva(),
                "owner" to instAtual, "gen" to currentGen, "attempt" to 1,
                "backend_pedido" to forcedBackendKind, "fa_pedida" to forcedFlashAttention,
                "stage" to "encoder")
        }
        startStallWatch()

        val ref = WeakReference(activity)

        // R16/B4 — DESTROY REAL: finish() na main thread, com disparo registrado.
        // `input keyevent 4` nao prova onDestroy; isto sim.
        val destruirEm = destroyAfterMs
        if (destruirEm >= 0L) {
            activity.runOnUiThread {
                activity.window.decorView.postDelayed({
                    log("lab_destroy_triggered", "delay_ms" to destruirEm,
                        "inst" to inst)
                    activity.finish()
                }, destruirEm)
            }
        }

        // R15/A2 — PORTÃO DE MODELO na Activity REAL: o botão de modelo
        // pertence ao produto, então é aqui que o modelo pedido tem de ser
        // selecionado. Sem isto, o produto carrega o primeiro modelo que
        // existe (BASE) e a célula mede o modelo errado.
        // R22/Q1 — a seleção por arquivo NÃO acontece mais aqui: ela é aplicada
        // depois que a mídia entra (loadPickedMedia → resetModelSelection),
        // na main thread, via applyPendingModelFile.
        val wantModel = modelLabelToSet
        if (wantModel != null) {
            Thread {
                try {
                    applyModelToRealActivity(activity, wantModel, 60_000L)
                } catch (t: Throwable) {
                    log("model_gate_error", "label" to wantModel, "error" to t.toString())
                }
            }.apply { isDaemon = true; start() }
        }

        // Heartbeat contínuo (main thread) — mede atraso de 100 ms e fica
        // ativo durante TODA a operação (não só no começo). R5: NÃO re-agenda
        // depois do destroy (sem callbacks periódicos de Activity morta).
        val hb = object : Runnable {
            override fun run() {
                val a = ref.get() ?: return
                if (a.isDestroyed) { hbArmed = false; return }
                val now = SystemClock.uptimeMillis()
                if (hbLast != 0L) {
                    hbTicks++
                    val late = (now - hbLast - 100L).coerceAtLeast(0L)
                    if (late > hbMax) hbMax = late
                    val b = when {
                        late < 10L -> 0
                        late < 30L -> 1
                        late < 100L -> 2
                        late < 300L -> 3
                        late < 1000L -> 4
                        else -> 5
                    }
                    hbHist[b]++
                }
                hbLast = now
                hbLastTick = now
                hbArmed = true
                main.postDelayed(this, 100L)
            }
        }
        hbArmed = true
        hbLastTick = SystemClock.uptimeMillis()
        main.postDelayed(hb, 100L)

        if (dumpEveryMs > 0) {
            val dump = object : Runnable {
                override fun run() {
                    val a = ref.get() ?: return
                    if (a.isDestroyed) return
                    dumpUi(a, "ui_periodic")
                    main.postDelayed(this, dumpEveryMs)
                }
            }
            main.postDelayed(dump, dumpEveryMs)
        }

        val finished = AtomicBoolean(false)
        // R17/A2 — relógio do install, visível ao finishWithDump (declarado
        // antes dele). É a base do "tempo decorrido" no pedido de finish.
        val installAt = SystemClock.elapsedRealtime()

        fun finishWithDump(why: String) {
            val a = ref.get() ?: return
            if (!finished.compareAndSet(false, true)) return
            // R17/A2 — PEDIDO de finish registrado ANTES de qualquer efeito:
            // motivo, instância, dono, orçamento efetivo e tempo decorrido.
            // É o que permite provar, no bruto, que `hard_cap` não dispara aos
            // 240 s quando o orçamento pedido é maior.
            log(
                "drive_finish_request",
                "why" to why,
                "inst" to inst,
                "owner" to runId,
                "hard_finish_ms" to hardFinishMs,
                "elapsed_ms" to (SystemClock.elapsedRealtime() - installAt)
            )
            // R8/A3: artefato COMPLETO antes do fim — agora SÓ depois do publish
            // do terminal (corrida export×terminal-publish fechada).
            exportFullTextFinal(ref, "ui_final") {
                val aa = ref.get() ?: return@exportFullTextFinal
                dumpUi(aa, "ui_final", "why" to why)
                log("drive_finish", "why" to why, "inst" to inst, "hb" to hbSnapshot())
                try { aa.finish() } catch (t: Throwable) { Log.e(TAG, "finish falhou: $t") }
            }
        }

        // Campos reais da UI avançada: o produto lê readAdvancedSettings()
        // DESTES widgets — o lab não inventa um segundo caminho de settings.
        beamToSet?.let { v ->
            runCatching {
                activity.findViewById<TextView>(R.id.input_beam_size).text = v.toString()
                log("prep_beam", "value" to v)
            }.onFailure { log("prep_beam_fail", "error" to it.toString()) }
        }
        bestOfToSet?.let { v ->
            runCatching {
                activity.findViewById<TextView>(R.id.input_best_of).text = v.toString()
                log("prep_bestof", "value" to v)
            }.onFailure { log("prep_bestof_fail", "error" to it.toString()) }
        }
        vadToSet?.let { v ->
            runCatching {
                activity.findViewById<CheckBox>(R.id.checkbox_vad).isChecked = v
                log("prep_vad", "checked" to v)
            }
        }

        // STOP por fase "Ouvindo": robusto ao tempo de load (o clique fixo podia
        // chegar antes do fim da carga e o job terminava com 0 trechos).
        if (stopAfterOuvindoMs >= 0L) {
            var armed = true
            val stopWatch = object : Runnable {
                override fun run() {
                    val a = ref.get() ?: return
                    if (a.isDestroyed) return
                    val statusText = runCatching { a.findViewById<TextView>(R.id.status)?.text?.toString().orEmpty() }.getOrDefault("")
                    val ouvindo = statusText.startsWith("Ouvindo")
                    val ended = statusText.startsWith("Ao vivo ") || statusText.startsWith("Encerrando") ||
                        statusText.startsWith("Erro")
                    if (ouvindo && armed) {
                        armed = false
                        log("stop_scheduled", "after_ms" to stopAfterOuvindoMs, "inst" to inst)
                        main.postDelayed({ click(ref, R.id.button_live_mic_stop, "click_live_stop_ouvindo") }, stopAfterOuvindoMs)
                    } else if (ended && !armed) {
                        armed = true   // re-arma para o próximo job da mesma instância
                    }
                    main.postDelayed(this, 300L)
                }
            }
            main.postDelayed(stopWatch, 300L)
        }

        if (finishOnStatusPrefix != null || finishOnStatusContains != null || hardFinishMs >= 0L ||
            secondJobStartAfterFirstVerdictMs >= 0L
        ) {
            val startedAt = SystemClock.elapsedRealtime()
            var phase = 0
            val watch = object : Runnable {
                override fun run() {
                    val a = ref.get() ?: return
                    if (a.isDestroyed) return
                    val statusText = runCatching { a.findViewById<TextView>(R.id.status)?.text?.toString().orEmpty() }.getOrDefault("")
                    val prefix = finishOnStatusPrefix
                    val contains = finishOnStatusContains
                    val atVerdict = (prefix != null && statusText.startsWith(prefix)) ||
                        (contains != null && statusText.contains(contains))
                    val secondJob = secondJobStartAfterFirstVerdictMs >= 0L
                    when {
                        phase == 0 && atVerdict && secondJob -> {
                            phase = 1
                            log("first_verdict", "status" to statusText.take(200), "inst" to inst)
                            main.postDelayed({
                                // R14/A2: a fronteira do ledger vale para TODO
                                // início de job, não só para a sequência FA.
                                markStepBoundary()
                                // R6/D3: rota de ARQUIVO usa o botão REAL transcrever.
                                if (fileSecondJob) click(ref, R.id.button_transcribe, "click_transcribe_2")
                                else click(ref, R.id.button_live_mic_stop, "click_live_start_2")
                                val stopIn = secondJobStopAfterStartMs
                                if (stopIn >= 0L && !fileSecondJob) {
                                    main.postDelayed({ click(ref, R.id.button_live_mic_stop, "click_live_stop_2") }, stopIn)
                                }
                            }, secondJobStartAfterFirstVerdictMs)
                            main.postDelayed(this, 500L)
                        }
                        phase == 0 && atVerdict -> finishWithDump("status_verdict")
                        phase == 1 && !atVerdict -> {
                            phase = 2
                            main.postDelayed(this, 500L)
                        }
                        phase == 2 && atVerdict -> finishWithDump("second_verdict")
                        hardFinishMs >= 0L && SystemClock.elapsedRealtime() - startedAt >= hardFinishMs -> finishWithDump("hard_cap")
                        else -> main.postDelayed(this, 500L)
                    }
                }
            }
            main.postDelayed(watch, 500L)
        }
        if (forceFinishMs >= 0L) {
            main.postDelayed({ finishWithDump("force_finish") }, forceFinishMs)
        }

        if (clickLiveStartMs >= 0L) {
            main.postDelayed({ markStepBoundary(); click(ref, R.id.button_live_mic_stop, "click_live_start") }, clickLiveStartMs)
        }
        if (clickLiveStopMs >= 0L) {
            main.postDelayed({ click(ref, R.id.button_live_mic_stop, "click_live_stop") }, clickLiveStopMs)
        }
        if (clickRecordStartMs >= 0L) {
            main.postDelayed({ markStepBoundary(); click(ref, R.id.button_recording_action, "click_record_start") }, clickRecordStartMs)
        }
        if (clickRecordStopMs >= 0L) {
            main.postDelayed({ click(ref, R.id.button_recording_action, "click_record_stop") }, clickRecordStopMs)
        }
        if (fileTranscribeClickMs >= 0L) {
            // R6/D3: rota de ARQUIVO — o MESMO botão do produto.
            // R14/A2: fronteira do ledger ANTES da entrada do job.
            // R18/A2 — GATE DE SELEÇÃO: quando o cenário exige um modelo
            // específico, não clicar antes de a seleção CORRENTE ter o SHA
            // esperado. Antes disto o s15 clicava em 4 s independentemente do
            // que tinha sido importado — e o job rodou BASE duas vezes.
            val shaEsperado = waitForImportSha
            if (shaEsperado == null) {
                main.postDelayed({ markStepBoundary(); click(ref, R.id.button_transcribe, "click_transcribe") }, fileTranscribeClickMs)
            } else {
                val limite = android.os.SystemClock.elapsedRealtime() + importSelectBudgetMs
                // R19/A4 — TOKEN da tentativa: a selecao so vale se ocorreu
                // DEPOIS deste instante. Evento de importacao anterior nao
                // libera o gate.
                val tokenMono = android.os.SystemClock.elapsedRealtime()
                log("import_gate_token", "mono" to tokenMono, "sha" to shaEsperado.take(16))
                val tentar: Runnable = object : Runnable {
                    override fun run() {
                        val linha = ultimaLinhaEvento("model_import_selected", tokenMono)
                        val ok = linha != null && linha.contains(shaEsperado)
                        when {
                            ok -> {
                                log("import_gate_ok", "sha" to shaEsperado.take(16))
                                markStepBoundary()
                                click(ref, R.id.button_transcribe, "click_transcribe")
                            }
                            android.os.SystemClock.elapsedRealtime() >= limite -> {
                                // Sem seleção válida: NÃO inicia inferência.
                                log("import_gate_timeout", "sha" to shaEsperado.take(16),
                                    "budget_ms" to importSelectBudgetMs)
                                finishWithDump("import_select_timeout")
                            }
                            else -> main.postDelayed(this, 250L)
                        }
                    }
                }
                main.postDelayed(tentar, fileTranscribeClickMs)
            }
        }
        if (fileCancelAfterTranscribeMs >= 0L) {
            // R6/D3: cancelar = clicar o MESMO botão (toggle Start/Cancel do produto).
            main.postDelayed({ click(ref, R.id.button_transcribe, "click_transcribe_cancel") }, fileTranscribeClickMs + fileCancelAfterTranscribeMs)
        }
        if (clickBackMs >= 0L) {
            main.postDelayed({ click(ref, R.id.btnBack, "click_back") }, clickBackMs)
        }
        // R8/A2+A3: bateria de UI REAL (controles do painel avançado) — só no lab.
        if (faSeqPlan != null) {
            main.postDelayed({
                startFaSeq(ref, onDone = { why -> finishWithDump(why) })
            }, 900L)
        }
        // R14/E1 — IMPORTAÇÃO DE MODELO pelo fluxo REAL: abre o menu do botão
        // de modelo e clica em "Selecionar arquivo de modelo…" — o produto
        // abre o SAF e a Activity recebe o Uri pela MESMA callback.
        if (modelImportClickAfterMs >= 0L) {
            main.postDelayed({
                click(ref, R.id.button_model, "click_model_menu")
                main.postDelayed({
                    val ok = clickDialogItemByText("Selecionar arquivo de modelo...", "click_model_pick")
                    log("model_import_pick", "performed" to ok)
                    // R14/E1: o item do menu pode não acionar o listener do
                    // PopupMenu via performClick; o lab então pede a IMPORTAÇÃO
                    // REAL (mesma função do produto) com o Uri da fixture.
                    if (ok) {
                        main.postDelayed({
                            // R18/A2 — fallback SÓ se o clique não iniciou
                            // importação. Antes, a chamada extra disparava uma
                            // SEGUNDA importação no mesmo bruto.
                            val jaIniciou = arquivoTemEvento("model_import_injected") ||
                                arquivoTemEvento("model_import_selected")
                            if (jaIniciou) {
                                log("model_import_fallback_skipped", "motivo" to "clique_ja_iniciou")
                            } else {
                                runCatching { WhisperLabSeams.requestModelImport(ref.get() ?: return@postDelayed) }
                            }
                        }, 400L)
                    }
                }, 600L)
            }, modelImportClickAfterMs)
        }
    }

    private fun resetCounters() {
        hbLast = 0L; hbMax = 0L; hbTicks = 0
        for (i in hbHist.indices) hbHist[i] = 0
    }

    private fun click(ref: WeakReference<Activity>, id: Int, label: String) {
        val a = ref.get() ?: return
        val v: View? = a.findViewById(id)
        val enabled = v?.isEnabled == true
        val inst = instanceCount.get()
        // R19/A2 — ENTRADA DO PEDIDO antes da acao. O evento anterior era
        // gravado DEPOIS de performClick e media o retorno do clique (a causa
        // do -6 ms: o worker podia terminar antes do log).
        val t0 = SystemClock.elapsedRealtimeNanos() / 1000L
        log(label, "phase" to "request_enter", "inst" to inst, "id" to id,
            "enabled" to enabled, "t_us" to t0,
            "gen" to currentGen, "attempt" to 1,
            // R20/A2 — NAO chamar o nativo aqui: `contextOwner()` disputa o
            // mutex do contexto e, durante o job, bloqueia a thread de UI
            // (213 main_stall medidos). O dono vem do terminal.
            "owner" to 0L)
        val ok = if (v != null) runCatching { v.performClick() }.getOrDefault(false) else false
        val t1 = SystemClock.elapsedRealtimeNanos() / 1000L
        // Resultado da acao em evento PROPRIO: nao se confunde com a entrada.
        log(label + "_action_result", "phase" to "request_action_result", "inst" to inst,
            "id" to id, "performed" to ok, "dt_us" to (t1 - t0))
    }

    /**
     * R16/B4 — dispara o MESMO PendingIntent de cancelar que o serviço postou
     * na notificação (o objeto que o sistema entregaria ao toque com tela OFF).
     * Retorna true só quando o intent existia e foi enviado. Não fabrica
     * cancel: sem notificação/ação postada, retorna false (célula inválida).
     */
    private fun firePostedCancelIntent(a: Activity): Boolean {
        return try {
            val nm = a.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            val ativas = try { nm.activeNotifications } catch (e: Exception) { null }
            log(
                "pendingintent_ativas",
                "n" to (ativas?.size ?: -1),
                "ids" to (ativas?.joinToString(",") { "${it.id}:${it.notification.actions?.size ?: 0}" } ?: "?").take(300)
            )
            val sbn = ativas?.firstOrNull { it.id == WhisperJobService.NOTIFICATION_ID }
                ?: return false
            val acao = sbn.notification.actions?.firstOrNull {
                it.title?.toString()?.contains("Cancelar") == true
            } ?: return false
            acao.actionIntent.send()
            true
        } catch (e: Exception) {
            log("pendingintent_fire_falhou", "erro" to e.toString().take(200))
            false
        }
    }

    private fun dumpUi(activity: Activity, event: String, vararg kv: Pair<String, Any?>) {
        fun text(id: Int): String = runCatching {
            activity.findViewById<TextView>(id)?.text?.toString().orEmpty()
        }.getOrDefault("")
        val status = text(R.id.status)
        val transcript = text(R.id.live_transcript_text)
        val terminal = text(R.id.terminal_text)
        // Views genéricas de propósito: os widgets são AppCompat* (ImageButton/
        // Button) — um cast para android.widget.Button quebraria em runtime.
        fun enabled(id: Int): Boolean = runCatching { activity.findViewById<View>(id)?.isEnabled == true }.getOrDefault(false)
        val vad = runCatching { activity.findViewById<CheckBox>(R.id.checkbox_vad)?.isChecked == true }.getOrDefault(false)
        val liveBtn = activity.findViewById<View>(R.id.button_live_mic_stop)
        val recordingPanel = activity.findViewById<View>(R.id.recording_panel)
        val back = activity.findViewById<View>(R.id.btnBack)
        log(
            event,
            "status" to status.take(500),
            "transcript_len" to transcript.length,
            "transcript_tail" to transcript.takeLast(160),
            // R6/D2/D3: texto completo do job (pequeno) para WER por estratégia
            "transcript_text" to transcript.take(900),
            "terminal_len" to terminal.length,
            "terminal_tail" to terminal.takeLast(220),
            "live_btn_enabled" to enabled(R.id.button_live_mic_stop),
            "live_btn_desc" to (liveBtn?.contentDescription?.toString() ?: ""),
            "rec_btn_enabled" to enabled(R.id.button_recording_action),
            "transcribe_enabled" to enabled(R.id.button_transcribe),
            "model_btn_enabled" to enabled(R.id.button_model),
            "vad_checked" to vad,
            "back_visible" to (back?.visibility == View.VISIBLE),
            "recording_panel_visible" to (recordingPanel?.visibility == View.VISIBLE),
            "hb" to hbSnapshot(),
            "mem" to memSnapshot(),
            *kv
        )
    }

    /** Chamado pelo teardown (onDestroy) da Activity real. */
    fun onActivityDestroyed() {
        if (!active) return
        hbArmed = false
        log("activity_destroyed", "inst" to instanceCount.get(), "hb" to hbSnapshot(), "mem" to memSnapshot())
    }

    // ------------------------------------------- R8/A3: TEXTO FINAL COMPLETO

    fun artDir(): File {
        val base = logFile?.parentFile ?: File(".")
        val dir = File(base, "fulltext")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun sha256(bytes: ByteArray): String {
        val dig = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(dig.size * 2)
        for (b in dig) sb.append(String.format("%02x", b))
        return sb.toString()
    }

    private fun uiText(activity: Activity, id: Int): String = runCatching {
        activity.findViewById<TextView>(id)?.text?.toString().orEmpty()
    }.getOrDefault("")

    /** R12/A2 — seq do ledger no INÍCIO do passo: o export só aceita um
     * terminal publicado DEPOIS disto (o job do passo), nunca o anterior. */
    private var stepLedgerSeq = -1L

    /** Marca a fronteira do passo (chamado no `fa_step_start` de cada rota). */
    private fun markStepBoundary() {
        stepLedgerSeq = TranscriptionJobResults.ledger.currentSeq()
    }

    /** Resultado do job DESTE passo (ou `null` se o passo não publicou terminal). */
    private fun currentStepPayload(): TranscriptionJobResult? =
        TranscriptionJobResults.ledger.latestAfter(stepLedgerSeq)

    /**
     * R12/A2 — EXPORTA O RESULTADO DO JOB ATUAL (payload ESTRUTURADO do
     * produtor), NUNCA o texto do widget.
     *
     * Defeito da R11: este método lia `R.id.live_transcript_text`; no passo
     * CANCELADO antes do decode o widget ainda tinha o texto do passo anterior
     * e os três passos saíram com o MESMO arquivo (329 B, sha `bf956e79…`),
     * gerando "WER 0,0000" num job que nunca decodificou.
     *
     * Agora o texto vem de [TranscriptionJobResults] com job_id/owner/input_id/
     * attempt_id, terminal e origem. Sem terminal NOVO do passo ⇒ export VAZIO
     * (`sem_resultado=true`) e `fulltext_export_sem_resultado` registra o
     * motivo; parcial sai marcado (`parcial=true`, `wer_eligivel=false`).
     * `widget_chars`/`widget_inherited` documentam a divergência sem que o
     * widget entre no texto. Produção não tem nada disto (flavor whisperlab).
     */
    fun exportFullText(activity: Activity, tag: String, allowLegacyWidget: Boolean = false) {
        try {
            val widget = uiText(activity, R.id.live_transcript_text)
            val terminal = uiText(activity, R.id.terminal_text)
            val status = uiText(activity, R.id.status)
            val payload = currentStepPayload()
            val strict = TranscriptionJobResults.ledger.exportOf(payload)
            // R14/A2 — modo ESTRITO do ensaio: sem payload do job, o export
            // FALHA com diagnóstico explícito. Nunca converte widget legado em
            // resultado elegível (era o furo do A5 da R13).
            val missingRequired = requirePayloadExport && payload == null
            // Rota ao vivo ainda não publica no ledger (o coordenador ao vivo
            // tem identidade própria): sem payload, o export ao vivo só pode
            // sair do widget e é marcado como LEGADO — nunca elegível a WER.
            val legacy = !missingRequired && payload == null && allowLegacyWidget && widget.isNotBlank()
            val export = if (missingRequired) {
                TranscriptionJobExport(
                    text = "",
                    empty = true,
                    partial = false,
                    werEligible = false,
                    reason = "payload_ausente_obrigatorio",
                    result = null,
                )
            } else if (legacy) {
                TranscriptionJobExport(
                    text = widget,
                    empty = false,
                    partial = false,
                    werEligible = false,
                    reason = "widget_sem_identidade",
                    result = null,
                )
            } else {
                strict
            }
            val dir = artDir()
            val tBytes = export.text.toByteArray(Charsets.UTF_8)
            val termBytes = terminal.toByteArray(Charsets.UTF_8)
            val tFile = File(dir, "$runId-$tag-transcript.txt")
            val termFile = File(dir, "$runId-$tag-terminal.txt")
            val jobFile = File(dir, "$runId-$tag-job.json")
            tFile.writeBytes(tBytes)
            termFile.writeBytes(termBytes)

            val meta = export.metaJson()
            if (legacy) {
                // Widget legado: não é resultado do job; o meta não pode dizer
                // "sem_resultado" (há texto) nem "payload".
                meta.put("sem_resultado", false)
                meta.put("text_origin", "widget_sem_identidade")
                meta.put("motivo", "widget_sem_identidade")
                meta.put("wer_eligivel", false)
            }
            meta.put("ts", SystemClock.elapsedRealtime())
            meta.put("run", runId)
            meta.put("scenario", scenario)
            meta.put("inst", instanceCount.get())
            meta.put("tag", tag)
            meta.put("transcript_path", tFile.absolutePath)
            meta.put("transcript_sha256", sha256(tBytes))
            meta.put("transcript_bytes", tBytes.size)
            meta.put("transcript_chars", export.text.length)
            meta.put("terminal_path", termFile.absolutePath)
            meta.put("terminal_sha256", sha256(termBytes))
            meta.put("terminal_bytes", termBytes.size)
            meta.put("status", status.take(300))
            // O widget NUNCA compõe o texto; fica registrado só como observação
            // (prova de que o export não o herdou).
            meta.put("widget_chars", widget.length)
            meta.put("widget_inherited", export.empty && widget.isNotBlank())
            meta.put("widget_diverges", widget.trim() != export.text.trim())
            meta.put("origem_export", if (missingRequired) "erro_instrumentacao" else if (legacy) "widget_legado" else "payload")
            if (missingRequired) {
                meta.put("erro_instrumentacao", "payload_ausente_obrigatorio")
                meta.put("step_ledger_seq", stepLedgerSeq)
                meta.put("ledger_seq_atual", TranscriptionJobResults.ledger.currentSeq())
            }
            jobFile.writeText(meta.toString(), Charsets.UTF_8)

            val metaFile = File(dir, "fulltext.jsonl")
            synchronized(logLock) { metaFile.appendText(meta.toString() + "\n", Charsets.UTF_8) }
            log(
                "fulltext_export",
                "tag" to tag,
                "path" to tFile.absolutePath,
                "job_path" to jobFile.absolutePath,
                "sha256" to sha256(tBytes),
                "bytes" to tBytes.size,
                "chars" to export.text.length,
                "terminal_chars" to terminal.length,
                "job_id" to (payload?.key?.jobId ?: 0L),
                "owner" to (payload?.key?.owner ?: 0L),
                "input_id" to (payload?.key?.inputId ?: ""),
                "attempt_id" to (payload?.key?.attemptId ?: 0L),
                "terminal" to (payload?.terminal?.wire ?: "NONE"),
                "text_origin" to (payload?.origin?.name?.lowercase() ?: "none"),
                "parcial" to export.partial,
                "wer_eligivel" to export.werEligible,
                "sem_resultado" to export.empty,
                "motivo" to export.reason,
                "widget_chars" to widget.length,
                "widget_inherited" to (export.empty && widget.isNotBlank()),
                "origem_export" to (if (missingRequired) "erro_instrumentacao" else if (legacy) "widget_legado" else "payload")
            )
            if (missingRequired) {
                // R14/A2: erro de instrumentação explícito — o ensaio exigia
                // payload do job e ele não existe. Nunca vira PASS por widget.
                log(
                    "fulltext_export_erro_instrumentacao",
                    "tag" to tag,
                    "motivo" to "payload_ausente_obrigatorio",
                    "step_ledger_seq" to stepLedgerSeq,
                    "ledger_seq" to TranscriptionJobResults.ledger.currentSeq(),
                    "widget_chars" to widget.length
                )
            } else if (payload == null) {
                log(
                    "fulltext_export_sem_resultado",
                    "tag" to tag,
                    "motivo" to export.reason,
                    "widget_chars" to widget.length
                )
            }
        } catch (t: Throwable) {
            log("fulltext_export_fail", "tag" to tag, "error" to t.toString())
        }
    }

    // ------------------------------ R8/D (fecho): terminal × export do fulltext

    /** R8/D (fecho) — há publicação de terminal ADIADA? O dreno/reparo deferido
     * do último chunk promove o texto FINAL no widget DEPOIS do veredito de
     * status (d3pre: export 1044 < display 1239). Verdadeiro somente quando o
     * job desta instância terminou em terminal NORMAL (cancel/erro NÃO publicam
     * terminal) e o `clock_ui_publish` source=terminal_final da MESMA
     * instância/job ainda não saiu. */
    private fun terminalPublishPending(instNow: Int): Boolean {
        if (lastLiveTerminalAt <= 0L || lastLiveTerminalInst != instNow) return false
        if (lastLiveTerminalCancelled) return false
        return !(lastTerminalFinalAt > 0L &&
            lastTerminalFinalInst == instNow &&
            lastTerminalFinalJobId == lastLiveTerminalJobId)
    }

    /** R8/D (fecho): exporta o fulltext DEPOIS do publish do terminal.
     * Espera LIMITADA ([terminalFinalWaitMs]) e não bloqueante (poll na main)
     * para o texto final chegar ao widget antes de capturá-lo; timeout →
     * exporta assim mesmo e `fulltext_export_wait` registra
     * saw_terminal_final=false. `after` só roda após o export. */
    private fun exportFullTextFinal(
        activityRef: WeakReference<Activity>,
        tag: String,
        after: (() -> Unit)? = null,
    ) {
        val t0 = SystemClock.elapsedRealtime()
        val instNow = currentInstance()
        val wanted = terminalPublishPending(instNow)
        var armed = false
        val poll = object : Runnable {
            override fun run() {
                if (armed) return
                val now = SystemClock.elapsedRealtime()
                if (wanted && terminalPublishPending(instNow) && now - t0 < terminalFinalWaitMs) {
                    main.postDelayed(this, 200L)
                    return
                }
                armed = true
                val a = activityRef.get() ?: return
                if (wanted) {
                    log(
                        "fulltext_export_wait", "tag" to tag, "inst" to instNow,
                        "job_id" to lastLiveTerminalJobId,
                        "saw_terminal_final" to !terminalPublishPending(instNow),
                        "waited_ms" to (now - t0)
                    )
                }
                exportFullText(a, tag, allowLegacyWidget = true)
                after?.invoke()
            }
        }
        main.postDelayed(poll, 0L)
    }

    // --------------------------- R8/A2+A3: SEQUÊNCIA DE UI REAL (controles)

    private data class FaSeqStep(
        val name: String,
        val backend: String,
        val faOn: Boolean,
        val route: String,
        val pcm: String,
        val cancelOnStage: Boolean,
        // R16/D2 — limiar de progresso para o cancel (0 = legado: qualquer
        // estágio "progress "/calling_full). >0 exige "progress N%" com N>=min.
        val cancelProgressMin: Int = 0,
        // R9/A2 bateria: injeção de parcial CONFLITANTE num chunk (força
        // retenção/repasse de reparo no MESMO job ao vivo). -1 = sem injeção.
        val injectChunk: Int = -1,
        val injectText: String = "",
        // R9/A2 bateria: janela FIXA 4s/4s (como o d2int do R8/D) — o gatilho de
        // reparo do committer exige sobreposição grande (contexto = janela).
        val fixedWindow4s: Boolean = false,
        // R16/B4 — dispara o MESMO PendingIntent de cancelar da notificação
        // quando o monitor de tela registrar OFF após o início do passo (dono sem
        // UI utilizável). Não é sleep: o gatilho é o evento de tela do monitor.
        val firePendingIntentOnScreenOff: Boolean = false,
        // R16/C3 — fixture de mídia própria do passo ("" = a do run). Permite
        // a sequência de 6 fases (ok/cancel/erro/ok/ok) na mesma Activity.
        val mediaName: String = "",
        // R16/E3.2 — audio_ctx próprio do passo (-1 = o default do run).
        // Permite pares default×derivado intercalados na mesma sequência.
        val audioCtx: Int = -1,
    ) {
        /** Rótulo CURTO mostrado no botão (o menu usa o completo). */
        val short: String
            get() = when {
                backend.startsWith("GPU (Vulkan)") -> "Vulkan"
                backend.startsWith("GPU (OpenCL)") -> "OpenCL"
                else -> "CPU"
            }
    }

    private fun faSeqStepFor(name: String): FaSeqStep? = when (name) {
        "vulkan_on" -> FaSeqStep(name, "GPU (Vulkan)", true, "live", "speech", false)
        "silencio" -> FaSeqStep(name, "GPU (Vulkan)", true, "live", "zeros", false)
        "cpu_on" -> FaSeqStep(name, "CPU", true, "live", "speech", false)
        "vulkan_off" -> FaSeqStep(name, "GPU (Vulkan)", false, "live", "speech", false)
        "opencl_on" -> FaSeqStep(name, "GPU (OpenCL)", true, "live", "speech", false)
        "cancel_progress" -> FaSeqStep(name, "GPU (Vulkan)", true, "file", "speech", true)
        "recovery" -> FaSeqStep(name, "GPU (Vulkan)", true, "file", "speech", false)
        // R9/A2 — bateria de device: arquivo por backend + reparo ao vivo.
        "cpu_file" -> FaSeqStep(name, "CPU", true, "file", "speech", false)
        // R10/B3 — cancel durante a INFERENCIA no CPU e job de recuperacao no MESMO
        // processo (o cancelamento arma quando o estagio REAL aparece no terminal nativo).
        "cpu_cancel" -> FaSeqStep(name, "CPU", true, "file", "speech", true)
        "vulkan_cancel_mid" -> FaSeqStep(name, "GPU (Vulkan)", true, "file", "speech", true, cancelProgressMin = 40)
        "vulkan_cancel_end" -> FaSeqStep(name, "GPU (Vulkan)", true, "file", "speech", true, cancelProgressMin = 80)
        // R16/B4 — prova do MESMO PendingIntent de cancelar da notificação,
        // com tela OFF (dono sem UI utilizável). O gatilho é o monitor de
        // tela (screenOffAt posterior ao início do passo), não sleep.
        "vulkan_pendingintent_off" -> FaSeqStep(name, "GPU (Vulkan)", true, "file", "speech", false, firePendingIntentOnScreenOff = true)
        // R16/E3.1 — par FAoff × FAauto no arquivo (mesmo backend Vulkan).
        "vulkan_file_off" -> FaSeqStep(name, "GPU (Vulkan)", false, "file", "speech", false)
        // R16/E3.2 — derivação habilitada no passo (1 = deriva do PCM real;
        // literal >1 = força (legado R14, trunca se menor que a entrada).
        "cpu_file_ctx" -> FaSeqStep(name, "CPU", true, "file", "speech", false, audioCtx = 1)
        // R16/C3 — erro controlado com fixture inválida real (decode falha de
        // verdade na seam do worker; identificada como DIAG pelo nome).
        "vulkan_file_error" -> FaSeqStep(name, "GPU (Vulkan)", true, "file", "speech", false, mediaName = "fixture_invalido.wav")
        "vulkan_file" -> FaSeqStep(name, "GPU (Vulkan)", true, "file", "speech", false)
        // R9/G3 — rota de ARQUIVO com o backend GPU (OpenCL) na UI REAL
        // (opencl_file: FA desejado = false, como o passo f1g do harness;
        //  opencl_file_fa: FA desejado = true para registrar o aviso
        //  requested/applied/executed do MESMO job).
        "opencl_file" -> FaSeqStep(name, "GPU (OpenCL)", false, "file", "speech", false)
        "opencl_file_fa" -> FaSeqStep(name, "GPU (OpenCL)", true, "file", "speech", false)
        "live_repair" -> FaSeqStep(name, "CPU", true, "live", "speech", false, injectChunk = 1, injectText = "zulu xadrez")
        "live_repair2" -> FaSeqStep(name, "CPU", true, "live", "speech", false, injectChunk = 2, injectText = "zulu", fixedWindow4s = true)
        "live_fixed" -> FaSeqStep(name, "CPU", true, "live", "speech", false, fixedWindow4s = true)
        else -> null
    }

    private fun isIdleStatus(s: String): Boolean =
        s.startsWith("Transcrição concluída") || s.startsWith("Ao vivo ") ||
            s.startsWith("Transcrição cancelada") || s.startsWith("Transcrição ao vivo finalizada") ||
            s.startsWith("Erro") || s.startsWith("Cancelado")

    private fun isBusyStatus(s: String): Boolean =
        s.startsWith("Carregando") || s.startsWith("Ouvindo") || s.startsWith("Transcrevendo") ||
            s.startsWith("Preparando") || s.startsWith("Cancelando") || s.startsWith("Encerrando")

    private fun faKindOf(backend: String): Int = when {
        backend.startsWith("GPU (Vulkan)") -> FlashAttentionNotice.KIND_VULKAN
        backend.startsWith("GPU (OpenCL)") -> FlashAttentionNotice.KIND_OPENCL
        else -> FlashAttentionNotice.KIND_CPU
    }

    /** Estado FA do MESMO job: lido do nativo APÓS o terminal do paso. */
    /**
     * R17/D2 — PERFIL POR SUBMISSÃO/FENCE do job encerrado.
     *
     * O diag nativo publica submits/nós/quantum/fences; até a R16 isso só era
     * lido no INSTANTE do cancel (`b4_antes`) e o "depois" não existia. Aqui o
     * estado final do job vai para o JSONL: o delta (o que estava em voo durante
     * o cancel) sai do par antes/depois com [SubmissionFenceProfile.delta].
     */
    private fun logB4Profile(idx: Int) {
        val bruto = runCatching { WhisperNative.b4InflightState() }.getOrDefault("")
        // R17/D2 — o b4 mede o VULKAN; o produto roda no OPENCL. O contador
        // do OpenCL vai no MESMO evento para o quantum ter denominador.
        val cl = runCatching { WhisperNative.clSubmitState() }.getOrDefault("")
        val e = SubmissionFenceProfile.parse(bruto)
        if (e == null) {
            log("submission_fence_profile", "step" to idx, "estado" to "indisponivel",
                "cl_estado" to cl.take(120))
            return
        }
        log(
            "submission_fence_profile",
            "step" to idx,
            "state" to e.state,
            "owner" to e.owner,
            "submits" to e.submits,
            "nodes" to e.nodes,
            "nodes_total" to e.nodesTotal,
            "quantum" to e.quantumMedio,
            "max_nodes_per_submit" to e.maxNodesPerSubmit,
            "fences" to e.fenceGeneration,
            "dispatches" to e.dispatchCount,
            "lote_max_ms" to e.loteMaxMs,
            "cl_estado" to cl.take(120)
        )
    }

    private fun faJobState(idx: Int, step: FaSeqStep) {
        val kind = faKindOf(step.backend)
        // R12/A2: o estado do RESULTADO do job (texto/origem/terminal) sai no
        // MESMO evento do estado FA — resultado, terminal nativo e export
        // descrevem o MESMO job (job_id/attempt_id).
        val payload = currentStepPayload()
        val payloadExport = TranscriptionJobResults.ledger.exportOf(payload)
        val logText = runCatching { WhisperNative.lastLoadLog() }.getOrDefault("")
        val st = runCatching { FlashAttentionNotice.parseStatus(logText, kind, true) }.getOrNull()
        // R8/A2: recorte por `faJobBegin` — SÓ linhas do job ATUAL valem. As
        // linhas cruas (log inteiro) vão JUNTO: provam que existia um estado
        // anterior para herdar e que ele NÃO foi usado quando o job não teve
        // linha própria (silêncio/FA-off sem transcribe).
        val scoped = runCatching { FlashAttentionNotice.scopeToCurrentJob(logText) }.getOrDefault(logText)
        fun lastLine(text: String, prefix: String): String =
            text.lineSequence().map { it.trim() }
                .filter { it.startsWith("$prefix ") }
                .lastOrNull() ?: ""
        val execLine = lastLine(scoped, "SIG_FA_EXEC")
        val statusLine = lastLine(scoped, "SIG_FA_STATUS")
        val rawExec = lastLine(logText, "SIG_FA_EXEC")
        val rawStatus = lastLine(logText, "SIG_FA_STATUS")
        val markerSeen = logText.lineSequence().any {
            it.trim().startsWith(FlashAttentionNotice.JOB_BEGIN_PREFIX)
        }
        val msg = runCatching { FlashAttentionNotice.message(true, kind, logText) }.getOrNull()
        val summary = runCatching { FlashAttentionNotice.executionSummary(true, kind, logText) }.getOrNull()
        log(
            "fa_job_state",
            "step" to idx,
            "name" to step.name,
            "backend_req" to step.backend,
            "parse_ok" to (st != null),
            "exec" to (st?.exec?.name ?: ""),
            "family" to (st?.family ?: ""),
            // R16/D2 — família efetiva da execução (reconciliação).
            "exec_family" to (st?.execFamily ?: "n/a"),
            "variant" to (st?.variant ?: ""),
            "graph_fa" to (st?.graphFa ?: false),
            "job_gpu_r6" to (st?.jobGpuR6 ?: 0L),
            "job_gpu_generic" to (st?.jobGpuGeneric ?: 0L),
            "job_cpu" to (st?.jobCpu ?: 0L),
            "cpu_source" to (st?.cpuExecSource ?: ""),
            "job_refusals" to (st?.jobRefusals ?: 0L),
            "job_begin_seen" to markerSeen,
            "scoped_missing" to (execLine.isEmpty() && statusLine.isEmpty()),
            "notice_message" to (msg ?: "").take(400),
            "exec_summary" to (summary ?: ""),
            "status_line" to statusLine.take(700),
            "exec_line" to execLine.take(700),
            "raw_status_line" to rawStatus.take(700),
            "raw_exec_line" to rawExec.take(700),
            "payload_job_id" to (payload?.key?.jobId ?: 0L),
            "payload_owner" to (payload?.key?.owner ?: 0L),
            "payload_input_id" to (payload?.key?.inputId ?: ""),
            "payload_attempt_id" to (payload?.key?.attemptId ?: 0L),
            "payload_terminal" to (payload?.terminal?.wire ?: "NONE"),
            "payload_text_origin" to (payload?.origin?.name?.lowercase() ?: "none"),
            "payload_parcial" to payloadExport.partial,
            "payload_wer_eligivel" to payloadExport.werEligible,
            "payload_sem_resultado" to payloadExport.empty
        )
    }

    /** Clique em item de MENU (AlertDialog) pelo texto — janela do diálogo é
     * achada via WindowManagerGlobal.mRoots (Mesmo processo; sem root).
     * [bySuffix]=true compara por SUFIXO (o menu de modelo mostra "✓ v3Turbo"). */
    /**
     * R15/A2 — aplica o MODELO pedido na Activity REAL (a do produto).
     *
     * O botão de modelo (`R.id.button_model`) pertence à WhisperActivity, não
     * ao lab: por isso o portão tem de agir na Activity real — o mesmo clique de
     * menu do roteiro r8fa, mas aqui com o rótulo pedido.
     */
    /**
     * R22/Q1 — aplica a seleção por ARQUIVO depois que a mídia está pronta.
     * Chamado na MAIN thread (fim de loadPickedMedia), imediatamente após o
     * resetModelSelection que zerava a seleção.
     */
    fun applyPendingModelFile(a: android.app.Activity): Boolean {
        val want = modelFileToSet ?: return false
        val ok = runCatching {
            (a as? WhisperActivity)?.labSelectModelFile(want) ?: false
        }.getOrDefault(false)
        log("model_gate_file", "file" to want, "ok" to ok, "phase" to "pos_midia")
        return ok
    }

    fun applyModelToRealActivity(a: android.app.Activity, want: String, timeoutMs: Long = 60_000L): Boolean {
        val t0 = android.os.SystemClock.elapsedRealtime()
        var tried = false
        while (android.os.SystemClock.elapsedRealtime() - t0 < timeoutMs) {
            val cur = try {
                a.findViewById<android.widget.TextView>(R.id.button_model)?.text?.toString() ?: ""
            } catch (t: Throwable) { "" }
            if (cur == want) {
                log("model_gate_ok", "label" to want,
                    "elapsed_ms" to (android.os.SystemClock.elapsedRealtime() - t0))
                return true
            }
            if (!tried) {
                runCatching {
                    val b = a.findViewById<android.view.View>(R.id.button_model)
                    b?.performClick()
                    b?.postDelayed({ runCatching { clickDialogItemByText(want, "model_gate_item", bySuffix = true) } }, 700L)
                }
                tried = true
            }
            Thread.sleep(500)
        }
        log("model_gate_timeout", "label" to want)
        return false
    }

    /**
     * R15/B1 — prazo CURTO do wake lock para a prova física do monitor.
     *
     * O prazo de produção é 45 min: esperar o vencimento real inviabiliza a
     * bancada. Aqui o ensaio pede um prazo curto (>= MIN_LOCK_TIMEOUT_MS) e o
     * serviço registra nos eventos quando decide renovar/cancelar e se o lock
     * estava FISICAMENTE em mãos naquele instante.
     */
    /** R16/B4 — dispara finish() na main thread apos N ms (-1 = nao). */
    @Volatile var destroyAfterMs: Long = -1L

    /** R16/A2 — caminho do modelo PEDIDO pelo ensaio (null = sem gate). */
    @Volatile var expectedModelPath: String? = null

    @Volatile var leaseTimeoutMs: Long = 0L

    /**
     * R15/B1 — suprime a marca de progresso (injeção de FALHA).
     *
     * Sem progresso a renovação é negada por contrato: é o caminho que leva ao
     * cancelamento + lock de dreno. Usado só para provar esse braço na bancada.
     */
    @Volatile var leaseIgnoreProgress: Boolean = false

    /** R15/A2 — público: o portão de modelo do s14 precisa do MESMO clique de menu. */
    fun clickDialogItemByText(match: String, label: String, bySuffix: Boolean = false): Boolean {
        fun hit(t: CharSequence?): Boolean {
            val s = t?.toString()?.trim() ?: return false
            return if (bySuffix) s.endsWith(match) else s == match
        }
        try {
            val wmg = Class.forName("android.view.WindowManagerGlobal")
            val inst = wmg.getMethod("getInstance").invoke(null)
            val f = wmg.getDeclaredField("mRoots").apply { isAccessible = true }
            val roots = f.get(inst) as? List<*> ?: return false
            for (rootObj in roots) {
                if (rootObj == null) continue
                val decor = runCatching {
                    rootObj.javaClass.getMethod("getView").invoke(rootObj) as? View
                }.getOrNull() ?: continue
                val texts = ArrayList<TextView>()
                collectTexts(decor, texts)
                val target = texts.firstOrNull { hit(it.text) } ?: continue
                var v: View? = target
                while (v != null) {
                    if (v.isClickable && v.isEnabled) {
                        val ok = runCatching { v.performClick() }.getOrDefault(false)
                        log(label, "match" to match, "performed" to ok, "via" to v.javaClass.name)
                        if (ok) return true
                    }
                    v = v.parent as? View
                }
                // ListView do AlertDialog: performItemClick da linha
                var lv: View? = target
                while (lv != null && lv !is android.widget.ListView) lv = lv.parent as? View
                if (lv is android.widget.ListView) {
                    for (pos in 0 until lv.childCount) {
                        val child = lv.getChildAt(pos)
                        val out = ArrayList<TextView>()
                        collectTexts(child, out)
                        if (out.any { hit(it.text) }) {
                            val ok = runCatching { lv.performItemClick(child, pos, lv.adapter.getItemId(pos)) }.getOrDefault(false)
                            log(label, "match" to match, "performed" to ok, "via" to "ListView#item")
                            return ok
                        }
                    }
                }
            }
            log(label + "_miss", "match" to match)
        } catch (t: Throwable) {
            log("dialog_click_fail", "match" to match, "error" to t.toString())
        }
        return false
    }

    private fun collectTexts(root: View, out: MutableList<TextView>) {
        if (root is TextView) out.add(root)
        if (root is android.view.ViewGroup) {
            for (i in 0 until root.childCount) collectTexts(root.getChildAt(i), out)
        }
    }

    /**
     * R8/A2+A3 — BATERIA de UI REAL: usa os CONTROLES REAIS do painel avançado
     * (expandir/checkbox FA/menu de backend) e o MESMO botão de iniciar, job a
     * job, provando settings UI → snapshot → JNI → grafo → execução → mensagem.
     * Cada job exporta o texto completo e o estado FA do MESMO job.
     */
    private fun startFaSeq(ref: WeakReference<Activity>, onDone: (String) -> Unit) {
        val plan = faSeqPlan ?: return
        val steps = plan.split(",").mapNotNull { faSeqStepFor(it.trim()) }
        if (steps.isEmpty()) {
            log("fa_seq_empty", "plan" to plan)
            return
        }
        val zerosFile = File(artDir(), "zeros-$faSeqZerosMs.pcm")
        runCatching {
            // R8/A2: faSeqZerosMs é em MILISSEGUNDOS — bytes = ms * 16
            // amostras/ms * 2 bytes (o *16000*2 anterior gerava 1000x de zeros:
            // 3000 "ms" viravam 50 min de silêncio e o passo nunca fechava).
            val want = (faSeqZerosMs * 32L).toInt()
            if (!zerosFile.exists() || zerosFile.length() != want.toLong()) {
                zerosFile.writeBytes(ByteArray(want))
            }
        }.onFailure { log("fa_seq_zeros_fail", "error" to it.toString()) }
        log(
            "fa_seq_plan",
            "plan" to plan,
            "steps" to steps.joinToString(",") { it.name },
            "speech_pcm" to (faSeqSpeechPcm ?: "?"),
            "zeros" to zerosFile.absolutePath
        )
        var idx = 0
        var phase = 0
        var phaseAt = SystemClock.elapsedRealtime()
        // R16/E3.2 — default de audio_ctx do run (extra); passos com audioCtx
        // >= 0 sobrepõem por passo, demais herdam este.
        val runAudioCtxDefault = audioCtxToSet
        // R9/G3 — o MENU DE MODELO real tem de estar aplicado ANTES do 1o passo.
        var modelApplied = modelLabelToSet == null
        var modelIssued = false
        var modelIssuedAt = 0L
        // R16/C3b — mídia corrente da sequência: passo com mediaName troca;
        // passo sem mediaName restaura a do run (sem isto, o passo seguinte
        // ao erro herdaria a fixture inválida e falharia junto).
        var mediaNow = mediaFixtureName ?: ""
        val runMedia0 = mediaNow
        var modelGateAt = SystemClock.elapsedRealtime()
        var cancelArmed = false
        var seenBusy = false
        var statusBefore = ""
        // R8/A2: desarme do estado BUSY de job ANTERIOR (pre-sync) e fecho do
        // job DO PASSO (drain) antes de medir — sem isso o clique de iniciar do
        // passo seguinte é consumido como STOP e o passo mediria o job anterior.
        var presyncArmed = false
        var drainArmed = false
        // R8/D (fecho): trava do export adiado da fase 3 (um export por passo).
        var exportArmed = false
        val tick = object : Runnable {
            override fun run() {
                val a = ref.get() ?: return
                if (a.isDestroyed) return
                if (idx >= steps.size) {
                    log("fa_seq_done", "steps" to steps.size)
                    onDone("fa_seq_done")
                    return
                }
                val step = steps[idx]
                val statusText = uiText(a, R.id.status)
                val now = SystemClock.elapsedRealtime()
                // R9/G3 — portao do MODELO: so comeca o roteiro quando o botao
                // real de modelo mostrar o rotulo pedido.
                if (!modelApplied) {
                    val want = modelLabelToSet ?: "?"
                    val cur = uiText(a, R.id.button_model)
                    if (cur == want) {
                        modelApplied = true
                        log("fa_seq_model_ok", "label" to want, "elapsed_ms" to (now - modelGateAt))
                    } else {
                        if (!modelIssued || now - modelIssuedAt > 2500L) {
                            click(ref, R.id.button_model, "fa_seq_model_click")
                            main.postDelayed({ clickDialogItemByText(want, "fa_seq_model_item", bySuffix = true) }, 500L)
                            modelIssued = true
                            modelIssuedAt = now
                            log("fa_seq_model_try", "want" to want, "cur" to cur, "attempt_at_ms" to (now - modelGateAt))
                        }
                        if (now - modelGateAt > 40_000L) {
                            log("fa_seq_model_fail", "want" to want, "cur" to uiText(a, R.id.button_model))
                            onDone("fa_seq_model_fail")
                            return
                        }
                        main.postDelayed(this, 250L)
                        return
                    }
                }
                when (phase) {
                    0 -> {
                        val panel = a.findViewById<View>(R.id.advanced_panel)
                        if (panel == null || panel.visibility != View.VISIBLE) {
                            click(ref, R.id.advanced_toggle, "fa_seq_expand")
                        }
                        val cb = a.findViewById<CheckBox>(R.id.checkbox_flash_attention)
                        if (cb != null && cb.isChecked != step.faOn) {
                            click(ref, R.id.checkbox_flash_attention, "fa_seq_fa_click")
                        }
                        val curBtn = uiText(a, R.id.button_backend)
                        if (curBtn != step.short) {
                            click(ref, R.id.button_backend, "fa_seq_backend_click")
                            main.postDelayed({ clickDialogItemByText(step.backend, "fa_seq_backend_item") }, 450L)
                        }
                        // R16/C3 — mídia própria do passo (sequência de 6 fases na
                        // mesma Activity): troca via produto antes do início.
                        // Passo sem mediaName restaura a do run (C3b).
                        val wantMedia = when {
                            step.mediaName.isNotEmpty() -> step.mediaName
                            mediaNow != runMedia0 && runMedia0.isNotEmpty() -> runMedia0
                            else -> ""
                        }
                        if (wantMedia.isNotEmpty() && wantMedia != mediaNow) {
                            (a as? WhisperActivity)?.labReselectFixture(wantMedia, modelLabelToSet)
                            mediaNow = wantMedia
                            log("fa_step_media", "step" to idx, "media" to wantMedia)
                        }
                        // R16/E3.2 — audio_ctx por passo. 1 = DERIVAÇÃO do PCM
                        // real (setAudioCtxWindow como habilitador; o nativo
                        // usa samples.size()); >1 = literal legado (trunca se
                        // menor que a entrada — risco E2 demonstrado no d01).
                        if (step.audioCtx == 1) {
                            runCatching { WhisperNative.setAudioCtxWindow(1L) }
                            audioCtxToSet = 0
                            if (step.route == "file") {
                                log("fa_step_audio_ctx", "step" to idx, "modo" to "derivacao")
                            }
                        } else {
                            audioCtxToSet = if (step.audioCtx >= 0) step.audioCtx else runAudioCtxDefault
                            if (step.route == "file") {
                                log("fa_step_audio_ctx", "step" to idx, "audio_ctx" to audioCtxToSet)
                            }
                        }
                        val pcm = if (step.pcm == "zeros") zerosFile.absolutePath else faSeqSpeechPcm
                        if (step.route == "live" && pcm != null) livePcmPath = pcm
                        // R9/A2 — injeção de parcial conflitante por passo
                        // (rearmada a cada passo; passos sem injeção resetam).
                        injectChunkIndex = step.injectChunk
                        injectChunkText = step.injectText
                        injectChunkStatus = if (step.injectChunk >= 0) "ok" else ""
                        // R9/A2 — janela fixa 4s/4s só no passo de reparo.
                        if (step.fixedWindow4s) {
                            liveStrategyKind = "fixed"
                            liveHopMs = 4000L
                            liveContextMs = 4000L
                        } else {
                            liveStrategyKind = ""
                            liveHopMs = -1L
                            liveContextMs = -1L
                        }
                        log(
                            "fa_step_prepare",
                            "step" to idx,
                            "name" to step.name,
                            "fa_desejado" to step.faOn,
                            "fa_checked" to (cb?.isChecked == true),
                            "backend_btn" to curBtn,
                            "pcm" to (pcm ?: "AudioRecord"),
                            "status" to statusText.take(160)
                        )
                        statusBefore = statusText
                        seenBusy = false
                        phase = 1
                        phaseAt = now + 900L
                    }
                    1 -> {
                        // R8/A2: um job ao vivo que TERMINOU pelo EOF do PCM
                        // injetado deixa a UI em "Ouvindo..." (o stop só vem do
                        // usuário). Se ainda houver job ativo, o drive clica o
                        // MESMO botão (stop) e espera o veredito ANTES de
                        // iniciar o job DESTE passo.
                        if (step.route == "live" && isBusyStatus(statusText)) {
                            if (!presyncArmed) {
                                presyncArmed = true
                                log("fa_step_presync", "step" to idx)
                                click(ref, R.id.button_live_mic_stop, "fa_step_presync_stop")
                            }
                            if (now - phaseAt > faSeqStepCapMs) {
                                log("fa_step_presync_cap", "step" to idx)
                                phase = 3
                                phaseAt = now
                            }
                        } else if (now >= phaseAt) {
                            if (step.route == "live") {
                                click(ref, R.id.button_live_mic_stop, "fa_start_live")
                            } else {
                                click(ref, R.id.button_transcribe, "fa_start_file")
                            }
                            // R12/A2: fronteira do passo — só um terminal
                            // publicado DEPOIS daqui pertence a este passo.
                            markStepBoundary()
                            log(
                                "fa_step_start",
                                "step" to idx,
                                "name" to step.name,
                                "route" to step.route,
                                "backend_btn" to uiText(a, R.id.button_backend),
                                "fa_checked" to (a.findViewById<CheckBox>(R.id.checkbox_flash_attention)?.isChecked == true),
                                // R17/D2 — CONTAGEM NO INÍCIO DO JOB: sem este
                                // par, o delta de submissões (e o quantum por
                                // janela) só existia em passo com cancel.
                                "b4_inicio" to runCatching { WhisperNative.b4InflightState() }
                                    .getOrDefault("").take(160)
                            )
                            cancelArmed = false
                            presyncArmed = false
                            drainArmed = false
                            seenBusy = false
                            statusBefore = statusText
                            phase = 2
                            phaseAt = now
                        }
                    }
                    2 -> {
                        val terminalText = uiText(a, R.id.terminal_text)
                        if (isBusyStatus(statusText)) seenBusy = true
                        // R16/D2b — ociosidade apurada ANTES do gatilho. Exige job
                        // OCUPADO AGORA (`isBusyStatus` no status atual), não
                        // só "viu busy um dia": clique tardio pós-terminal em
                        // UI que ainda não atualizou INICIAVA job fantasma.
                        val idleNow = isIdleStatus(statusText)
                        if (step.cancelOnStage && !cancelArmed && isBusyStatus(statusText)) {
                            // R16/D2 — limiar por progresso REAL via porta pura
                            // e testada [CancelStageGate] (meio/fim); =0 legado.
                            val prog: Int? = CancelStageGate.maxProgress(terminalText)
                            val stage = CancelStageGate.stageFor(terminalText, step.cancelProgressMin)
                            if (stage != null) {
                                cancelArmed = true
                                log(
                                    "fa_step_cancel_stage_observed",
                                    "step" to idx,
                                    "stage" to stage,
                                    "progress" to (prog ?: -1),
                                    "status" to statusText.take(160)
                                )
                                click(ref, R.id.button_transcribe, "fa_cancel_click")
                            }
                        }
                        // R16/B4 — PendingIntent REAL da notificação com tela OFF.
                        // Gatilho: o monitor de tela registrou OFF DEPOIS do
                        // início do passo (dono sem UI utilizável) — não sleep.
                        // Dispara o MESMO objeto que o sistema entregaria.
                        if (step.firePendingIntentOnScreenOff && !cancelArmed && seenBusy && !idleNow) {
                            val offDepoisDoInicio = screenOffAt > phaseAt
                            if (offDepoisDoInicio) {
                                cancelArmed = true
                                val pm = a.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
                                val interativa = pm.isInteractive
                                val b4antes = runCatching { WhisperNative.b4InflightState() }.getOrDefault("b4_state=unavailable")
                                val faAntes = runCatching { WhisperNative.faRouteStatus().replace("\"", "'").take(300) }.getOrDefault("fa_status=unavailable")
                                val disparado = firePostedCancelIntent(a)
                                log(
                                    "pendingintent_real_disparado",
                                    "step" to idx,
                                    "screen_off_at" to screenOffAt,
                                    "is_interactive" to interativa,
                                    "disparado" to disparado,
                                    "b4_antes" to b4antes.take(220),
                                    "fa_antes" to faAntes,
                                    "status" to statusText.take(160)
                                )
                            }
                        }
                        // R8/A2: o job AO VIVO terminou quando o `live_terminal`
                        // DESTE passo foi emitido (EOF do PCM injetado não mexe
                        // no status da UI) — não espera o teto do passo.
                        val changed = statusText != statusBefore && statusText.isNotBlank()
                        val jobEnded = step.route == "live" && lastLiveTerminalAt >= phaseAt
                        val finished = (idleNow && (seenBusy || (changed && now - phaseAt > 3000L)) && now - phaseAt > 1200L) ||
                            jobEnded
                        val capHit = now - phaseAt > faSeqStepCapMs
                        if (finished || capHit) {
                            // R18/A3 — `fa_step_cap` NÃO é fim de job. Antes, o
                            // cap disparava export + perfil + estado do passo
                            // como se houvesse ACK — e o "fim do chunk" medido
                            // na R17 era isso. Sem término observado, o passo
                            // fica INCOMPLETE e o ensaio encerra sem exportar.
                            if (capHit && !jobEnded) {
                                log("fa_step_cap", "step" to idx, "status" to statusText.take(160))
                                log("fa_step_incomplete", "step" to idx, "motivo" to "cap_sem_ack",
                                    "cap_ms" to faSeqStepCapMs)
                                onDone("step_cap_incomplete")
                                return
                            }
                            if (step.route == "live") {
                                // normaliza o fim (drain) ANTES de exportar/medir
                                phase = 3
                                phaseAt = now
                            } else {
                                exportFullText(a, "fa_step$idx-${step.name}")
                                logB4Profile(idx)
                                faJobState(idx, step)
                                idx += 1
                                phase = 0
                            }
                        }
                    }
                    3 -> {
                        // R8/A2: fecha o job ao vivo (clique no MESMO botão) e
                        // espera o veredito na UI ANTES de exportar/medir o passo.
                        val busy = isBusyStatus(statusText)
                        if (busy && !drainArmed) {
                            drainArmed = true
                            log("fa_step_drain", "step" to idx)
                            click(ref, R.id.button_live_mic_stop, "fa_step_drain_stop")
                        }
                        if ((!busy || now - phaseAt > 20_000L) && !exportArmed) {
                            // R8/D (fecho): export do passo só DEPOIS do publish
                            // do terminal (mesma corrida do ui_final).
                            exportArmed = true
                            exportFullTextFinal(ref, "fa_step$idx-${step.name}") {
                                logB4Profile(idx)
                                faJobState(idx, step)
                                idx += 1
                                drainArmed = false
                                exportArmed = false
                                phase = 0
                            }
                        }
                    }
                }
                main.postDelayed(this, 250L)
            }
        }
        main.postDelayed(tick, 400L)
    }
}
