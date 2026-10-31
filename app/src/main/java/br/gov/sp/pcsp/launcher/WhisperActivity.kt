package br.gov.sp.pcsp.launcher

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.DocumentsContract
import android.system.Os
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.ImageButton
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.PopupWindow
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToLong
import kotlin.math.sqrt

/** UI e orquestracao da transcricao local com Whisper.
 *
 * A Activity cuida de tela, selecao de arquivo e relatorio; o nativo fica em
 * WhisperNative. Nenhuma regra de transcricao mora aqui.
 */

class WhisperActivity : AppCompatActivity(), JobCancelBridge.UiSink {

    private lateinit var whisperScroll: ScrollView
    private lateinit var selectedFileView: TextView
    private lateinit var buttonModel: TextView
    private lateinit var buttonTranscribe: ImageButton
    private var buttonTranscribeOrt: ImageButton? = null
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var logToggle: TextView
    private lateinit var logText: TextView
    private lateinit var terminalText: TextView

    /**
     * R9/B2: publicadores coalescidos do terminal e do log global. O histórico
     * inteiro é reconstruído/medido no máximo uma vez por janela de publicação
     * (`MainTextCoalescer`), em vez de a cada linha anexada — o único snapshot de
     * main_stall fora do renderer na R8-H é justamente medida/layout de texto
     * grande na main (`Paint.nGetCharArrayBounds`, 423 ms). A escrita continua
     * na main; nada de View/setText em worker.
     */
    private val terminalPublisher: MainTextCoalescer by lazy {
        MainTextCoalescer(
            minIntervalMs = MainTextCoalescer.DEFAULT_MIN_INTERVAL_MS,
            now = { SystemClock.uptimeMillis() },
            postDelayed = { delay, action -> terminalText.postDelayed(action, delay) },
        ) { text ->
            terminalText.text = text
            // O scroll depende do layout, calculado no próximo traversal.
            terminalText.post { scrollTerminalToBottom() }
        }
    }

    private val globalLogPublisher: MainTextCoalescer by lazy {
        MainTextCoalescer(
            minIntervalMs = MainTextCoalescer.DEFAULT_MIN_INTERVAL_MS,
            now = { SystemClock.uptimeMillis() },
            postDelayed = { delay, action -> logText.postDelayed(action, delay) },
        ) { text -> logText.text = text }
    }
    private lateinit var buttonLanguage: TextView
    private lateinit var buttonBackend: TextView
    private lateinit var outputFileName: TextView
    private lateinit var outputActions: View
    private lateinit var buttonOutputExport: ImageButton
    private lateinit var buttonOutputFolder: ImageButton
    private lateinit var advancedToggle: TextView
    private lateinit var advancedPanel: LinearLayout
    /**
     * URI escolhida no seletor interno (snapshot da seleção).
     *
     * `transcribeSelectedMedia` consultava só a URI do intent; quando o arquivo
     * vinha do picker, a rota recebia A enquanto a tela mostrava B, ou caía no
     * CPP por "faltar URI no intent". Este campo congela a escolha real.
     */
    private var uriEscolhida: android.net.Uri? = null

    /**
     * Consentimento "usar whisper.cpp NESTE arquivo".
     *
     * Ligado apenas pelo diálogo de fallback e consumido no despacho seguinte. Não
     * mexe na preferência geral: o próximo arquivo volta ao default (ORT).
     */
    private var cppParaEstaEntrada: android.net.Uri? = null

    /** Job ORT cujo resultado esta tela está exibindo (§4: identidade do observador). */
    private var jobOrtNaTela: WhisperOrtAdmission.Job? = null
    private var ortObserverGeneration = 0L
    private var ortDownload: WhisperOrtDownloadOwner.Attempt? = null
    private var ortDownloadDialog: android.app.AlertDialog? = null

    /**
     * Último despacho efetivo da ferramenta (`ORT:<uri>`, `CPP:<uri>` ou `NENHUM`).
     *
     * Costura de teste autorizada: registra QUAL motor atendeu QUAL entrada, para
     * provar seleção, checkbox e consentimento sem dirigir o seletor do sistema.
     */
    var ultimoDespacho: String = "NENHUM"
        private set

    /** Diálogo de fallback em exibição (costura de teste: permite clicar os botões). */
    var dialogoFallbackEmExibicao: android.app.AlertDialog? = null
        private set

    private lateinit var checkboxVad: CheckBox
    private lateinit var checkboxFlashAttention: CheckBox
    private lateinit var checkboxWordTimestamps: CheckBox
    private lateinit var inputBeamSize: TextView
    private lateinit var inputBestOf: TextView
    private lateinit var resetAdvanced: TextView

    // Controles de gravação de microfone (branco) e transcrição ao vivo (vermelho)
    private lateinit var buttonRecordingAction: ImageButton
    private lateinit var buttonLiveMicStop: ImageButton
    private lateinit var recordingPanel: View
    private lateinit var recordingTimer: TextView
    private lateinit var liveTranscriptContainer: View
    private lateinit var liveTranscriptText: EditText
    private lateinit var liveTranscriptClipboardActions: View
    private lateinit var buttonClearTranscript: View
    private lateinit var buttonShareLiveTranscript: ImageButton
    private lateinit var buttonCopyLiveTranscript: ImageButton
    private lateinit var buttonPasteTranscript: ImageButton

    private val selectedItems = mutableListOf<MediaItem>()
    private var selectedModel: WhisperModel? = null
    private var selectedLanguage = WhisperLanguage.PT
    private var selectedBackend = WhisperBackend.CPU
    private var lastSession: OutputSession? = null
    private lateinit var buttonSelectOutputFolder: ImageButton
    private lateinit var arrowInputOutput: View
    private lateinit var buttonSaveToFolder: ImageButton
    private var preSelectedOutputDirUri: Uri? = null
    private var finalOutputDirUri: Uri? = null
    private var tempSessionDir: File? = null
    private var hasSaved = false
    private var sourcePopup: PopupWindow? = null
    private var isBusy = false
    private var isTranscribing = false
    @Volatile private var cancelRequested = false
    private val timerHandler = Handler(Looper.getMainLooper())
    private var transcriptionStartedAt = 0L
    private var currentTranscriptionStatus = ""
    private var currentTranscriptionProgress = 0
    private var currentEstimatedMs: Long? = null
    private var currentTotalAudioSeconds = 0.0
    private var currentEstimatedEfficiency: Double? = null

    // Estados de gravação
    @Volatile private var whiteRecordingActive = false
    private var whiteRecordingThread: Thread? = null
    private var whiteRecordingPcmFile: File? = null
    private var whiteRecordingWavFile: File? = null
    private var whiteRecordingAudioRecord: AudioRecord? = null
    private var whiteRecordingStartedAt = 0L

    @Volatile private var liveMicActive = false
    private var liveMicThread: Thread? = null
    private var liveMicAudioRecord: AudioRecord? = null
    /** R3/C1: pipeline leitor->spool->worker do caminho ao vivo (cancel em
     * teardown). R4/C1: resultado TIPADO por chunk (nada de String? ambíguo). */
    private var liveMicPipeline: LivePcmPipeline<LiveChunkResult>? = null
    /** R13/F2-fix: geração do job AO VIVO (capturada no início do laço) — o
     * stop encaminha o cancelamento para ELA, nunca para a geração global lida
     * tarde demais (um sucessor não pode ser atingido). */
    @Volatile private var liveMicGeneration = 0L
    // R56/B01-B07 (adendo8 §1-2) — ACK TERMINAL INDEPENDENTE DA ACTIVITY.
    // Guarda a geração já confirmada para o terminal do live: o sink do worker
    // e o caminho de UI podem chamar; só o primeiro emite, e só da própria
    // geração (nunca libera dono alheio).
    @Volatile private var liveAckGenEmitido = 0L
    /** Sink terminal DESTE job (identidade imutável capturada na admissão). */
    @Volatile private var terminalSinkAtual: WhisperJobTerminalSink? = null
    /** R13/F2-fix: pedido ÚNICO de parada do job ao vivo (cancelar a janela em
     * voo por-dono + descartar o pendente). Recriado a cada job. */
    @Volatile private var liveStopRequest: LiveStopRequest? = null
    /** R4/C1: snapshot imutável das configurações do job ao vivo (capturado no
     * INÍCIO; encaminhado ao JNI a cada chunk). */
    @Volatile private var liveMicSettings: LiveJobSettings? = null
    /** R4/C1/C2: contadores por desfecho e retrato terminal do job ao vivo. */
    @Volatile private var liveChunkTally: LiveChunkTally? = null
    @Volatile private var liveTerminalSnapshot: LiveTerminalSummary? = null
    private var liveTerminalLines: StringBuilder? = null
    @Volatile private var liveReaderReason: String = ""
    /** R4/C2: o pipeline terminou por cancel (teardown) — entra no retrato. */
    @Volatile private var liveWasCancelled = false
    /** R3/C1: teto VISÍVEL antes de anunciar "worker ainda ativo" no stop (inj.)
     * — a espera continua e nada é liberado até o worker terminal. */
    @Volatile private var liveMicDrainTimeoutMs: Long = LIVE_MIC_DRAIN_TIMEOUT_MS
    private var liveMicStartedAt = 0L
    // R6/D2 — commit/janela do caminho ao vivo (por job; nada atravessa sessão).
    @Volatile private var liveTextCommitter: LiveTextCommitter? = null
    @Volatile private var liveFirstSpeechMs: Long = -1L
    @Volatile private var liveFirstPartialMs: Long = -1L
    /** R8/D — 1º texto INCREMENTAL publicado (candidata 2); -1 = nunca. */
    @Volatile private var liveFirstIncrementalMs: Long = -1L
    /** R8/D — o overlay incremental já apareceu NESTE job (cancel derruba). */
    @Volatile private var liveIncrementalShown: Boolean = false
    /** R8/D (integridade) — último texto publicado na UI pelo caminho ao vivo
     * (commit/repair/terminal/incremental); base da promoção sem setText cego. */
    @Volatile private var liveLastPublishedText: String? = null
    @Volatile private var liveDecodeWallMsTotal: Long = 0L
    @Volatile private var liveDecodeWindowsCount: Int = 0
    /** R13/F1 — `attempt_id` do log de reparo (por job; NÃO é política de
     * reparo: o committer continua decidindo quantas tentativas existem). */
    private val liveRepairAttemptIds = LiveTelemetry.AttemptIds()
    @Volatile private var liveWindowDesc: String = ""
    /** R7/A1: identidade do PRODUTOR do job ao vivo (run/act/job/seq/mono) —
     * carimbada em todo evento; NUNCA deduzida do arquivo do laboratório. */
    private val activityInstanceId = LiveEventIdentity.nextActivityInstanceId()

    /**
     * R14/B2 — token de cancelamento DESTA Activity. Registrado no serviço e
     * removido só ele mesmo no destroy: a instância vizinha não perde o seu.
     */
    /**
     * R14/C2 — registra a ROTA FA efetiva (contadores do backend) no artefato do
     * lab. Em produção `liveEvent` é inerte; a chamada é barata e não muda o job.
     */
    private fun logFaRoute(tag: String) {
        runCatching {
            val st = WhisperNative.faRouteStatus().replace("\"", "'")
            liveEvent("fa_route", currentSessionGeneration,
                      fields = "\"tag\":\"$tag\",\"status\":\"$st\"")
            // R14/B4 — MEDIÇÃO do trabalho em voo (submits/nós/bytes do grafo).
            runCatching {
                val b4 = WhisperNative.b4InflightState().replace("\"", "'")
                liveEvent("b4_inflight", currentSessionGeneration,
                          fields = "\"tag\":\"$tag\",\"state\":\"$b4\"")
            }
        }
    }

    /**
     * R15/B2 — o forwarder devolve ACK: `true` só quando a sessão DESTA geração
     * aceitou o pedido. Um no-op (geração desconhecida/já terminada) devolve
     * `false` e o serviço encerra localmente em vez de esperar para sempre.
     */
    /**
     * R15/B3 — o forwarder agora é de PROCESSO (`JobCancelBridge`): ele não
     * captura esta Activity e por isso NÃO precisa ser desregistrado no
     * destroy. O serviço continua tendo quem aceite o cancelamento enquanto o
     * worker existir, e a UI só é avisada se houver Activity viva.
     */
    private val jobCancelForwarder: (Long) -> Boolean = JobCancelBridge.forwarder

    /**
     * Chamado pela ponte quando um cancelamento foi ACEITO. Seguro depois do
     * destroy (não mexe em views): só marca o pedido e atualiza status na UI
     * viva, pela geração.
     */
    override fun onJobCancelForwarded(generation: Long) {
        cancelRequested = true
        if (isFinishing || isDestroyed) return
        runOnUiThread {
            whisperSession.runIfCurrent(generation) {
                setTranscriptionStatus("Cancelando transcrição...")
            }
        }
    }
    private val liveStamp = LiveEventStamp(activityInstanceId)

    private val modelLoadLock = Any()
    private val modelImportSelection = ModelImportSupport.SelectionGate()
    @Volatile private var currentlyLoadedModelKey: String? = null

    /** R3/B2: cache do modelo carregado com dono/sessão + existência do contexto
     * nativo (owned) — substitui o antigo fast-path só-por-chave. */
    private val loadedModelCache = ModelLoadCache(
        contextOwner = { WhisperNative.contextOwnerSafe() },
        ownedNative = { whisperSession.ownershipAvailable() },
    )
    private var pendingAudioAction: (() -> Unit)? = null

    // W01/W02: ciclo de vida de sessão fora da UI thread.
    private val whisperSession = WhisperSessions.shared
    @Volatile private var currentSessionGeneration = 0L
    // W03: encerramento da transcrição ao vivo sem join na UI thread.
    private var liveMicStopping = false
    private var liveMicStopStartedAt = 0L

    private val transcriptionTimer = object : Runnable {
        override fun run() {
            if (!isTranscribing || transcriptionStartedAt <= 0L) return
            refreshTranscriptionStatus()
            timerHandler.postDelayed(this, 1000L)
        }
    }

    private val recordingTicker = object : Runnable {
        override fun run() {
            if (!whiteRecordingActive && !liveMicActive) return
            val startedAt = if (whiteRecordingActive) whiteRecordingStartedAt else liveMicStartedAt
            if (startedAt > 0L) {
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                val mins = (elapsed / 1000) / 60
                val secs = (elapsed / 1000) % 60
                val millis = elapsed % 1000
                recordingTimer.text = String.format(Locale.US, "%02d:%02d.%03d", mins, secs, millis)
            }
            timerHandler.postDelayed(this, 50)
        }
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        keepContentInsideSystemBars()
        setContentView(R.layout.activity_whisper)
        // Reentrega do que ficou sem confirmação (Activity recriada/após erro).
        runCatching { recuperarEntregasPendentes() }
        // ORT1/M4 (a): botao proprio da rota ORT (arquivo/CPU), ao lado do
        // existente. Nao substitui o botao de transcricao nem o default do app.
        // "usar whisper.cpp": desliga o ORT (que e o padrao) e explica no "!".
        val cbCpp = findViewById<android.widget.CheckBox?>(R.id.checkbox_usar_cpp)
        cbCpp?.isChecked = !WhisperOrtRoute.isEnabled(this)
        cbCpp?.setOnCheckedChangeListener { _, marcado ->
            WhisperOrtRoute.setEnabled(this, !marcado)
            // Reflete a escolha imediatamente (habilitação e botão), sem esperar
            // por outra interação.
            runCatching { updateTranscribeEnabled() }
        }
        findViewById<android.view.View?>(R.id.help_usar_cpp)?.setOnClickListener {
            android.app.AlertDialog.Builder(this)
                .setTitle("usar whisper.cpp")
                .setMessage(
                    "Desmarcado (padrao): a transcricao de arquivo roda em ONNX Runtime na CPU.\n\n" +
                        "Marcado: volta ao whisper.cpp (CPU/Vulkan), que continua disponivel como " +
                        "alternativa e como compatibilidade.\n\n" +
                        "O ORT usa Turbo INT8, português e busca greedy; os controles de VAD, " +
                        "beam e timestamps do whisper.cpp não se aplicam. Áudio longo usa janelas.\n\n" +
                        "Se o ORT nao puder atender o arquivo (formato ou pacote ausente), o app PERGUNTA antes de usar o whisper.cpp - nunca troca " +
                        "de motor em silencio.",
                )
                .setPositiveButton("Entendi", null)
                .show()
        }
        // Controle de threads ORT (pedido do usuário): default 4, máximo 8.
        // Faixa 1–8, persistida; a rota lê via `threadsEfetivos` a cada despacho.
        val inputOrtThreads = findViewById<android.widget.TextView?>(R.id.input_ort_threads)
        inputOrtThreads?.text = WhisperOrtRoute.threadsEfetivos(this).toString()
        fun ajustarThreads(delta: Int) {
            val atual = inputOrtThreads?.text?.toString()?.toIntOrNull()
                ?: WhisperOrtRoute.threadsEfetivos(this)
            val novo = (atual + delta).coerceIn(WhisperOrtRoute.THREADS_MIN, WhisperOrtRoute.THREADS_MAX)
            WhisperOrtRoute.setThreads(this, novo)
            inputOrtThreads?.text = novo.toString()
        }
        findViewById<android.view.View?>(R.id.btn_ort_threads_dec)?.setOnClickListener { ajustarThreads(-1) }
        findViewById<android.view.View?>(R.id.btn_ort_threads_inc)?.setOnClickListener { ajustarThreads(1) }
        findViewById<android.view.View?>(R.id.button_transcribe_ort)?.setOnClickListener {
            // NÃO altera a preferência: gravar setEnabled(true) aqui deixava a
            // checkbox "usar whisper.cpp" marcada enquanto a preferência ia para
            // o lado contrário.
            val intent = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(android.content.Intent.CATEGORY_OPENABLE)
                type = "audio/*"
            }
            startActivityForResult(intent, REQUEST_PICK_MEDIA_ORT)
        }

        whisperScroll = findViewById(R.id.whisper_scroll)
        selectedFileView = findViewById(R.id.selected_file)
        buttonModel = findViewById(R.id.button_model)
        buttonTranscribe = findViewById(R.id.button_transcribe)
        // Herdou `gone` do botão original (que só aparece com mídia escolhida):
        // sem isto o controle existia no layout mas nunca era visto — o teste
        // instrumentado do §7 reprovou exatamente isso.
        buttonTranscribeOrt = findViewById(R.id.button_transcribe_ort)
        progress = findViewById(R.id.progress)
        status = findViewById(R.id.status)
        logToggle = findViewById(R.id.log_toggle)
        logText = findViewById(R.id.log_text)
        terminalText = findViewById(R.id.terminal_text)
        buttonLanguage = findViewById(R.id.button_language)
        buttonBackend = findViewById(R.id.button_backend)
        outputFileName = findViewById(R.id.output_file_name)
        outputActions = findViewById(R.id.output_actions)
        buttonOutputExport = findViewById(R.id.button_output_export)
        buttonOutputFolder = findViewById(R.id.button_output_folder)
        buttonSelectOutputFolder = findViewById(R.id.button_select_output_folder)
        arrowInputOutput = findViewById(R.id.arrow_input_output)
        buttonSaveToFolder = findViewById(R.id.button_save_to_folder)
        buttonSelectOutputFolder.visibility = View.GONE
        arrowInputOutput.visibility = View.GONE

        buttonRecordingAction = findViewById(R.id.button_recording_action)
        buttonLiveMicStop = findViewById(R.id.button_live_mic_stop)
        recordingPanel = findViewById(R.id.recording_panel)
        recordingTimer = findViewById(R.id.recording_timer)
        liveTranscriptContainer = findViewById(R.id.live_transcript_container)
        liveTranscriptText = findViewById(R.id.live_transcript_text)
        liveTranscriptClipboardActions = findViewById(R.id.live_transcript_clipboard_actions)
        buttonClearTranscript = findViewById(R.id.button_clear_transcript)
        buttonShareLiveTranscript = findViewById(R.id.button_share_live_transcript)
        buttonCopyLiveTranscript = findViewById(R.id.button_copy_live_transcript)
        buttonPasteTranscript = findViewById(R.id.button_paste_transcript)

        advancedToggle = findViewById(R.id.advanced_toggle)
        advancedPanel = findViewById(R.id.advanced_panel)
        checkboxVad = findViewById(R.id.checkbox_vad)
        checkboxFlashAttention = findViewById(R.id.checkbox_flash_attention)
        checkboxWordTimestamps = findViewById(R.id.checkbox_word_timestamps)
        inputBeamSize = findViewById(R.id.input_beam_size)
        inputBestOf = findViewById(R.id.input_best_of)
        resetAdvanced = findViewById(R.id.reset_advanced)

        findViewById<View>(R.id.btn_beam_size_dec).setOnClickListener {
            val current = inputBeamSize.text.toString().toIntOrNull() ?: DEFAULT_BEAM_SIZE
            inputBeamSize.text = (current - 1).coerceIn(1, 16).toString()
        }
        findViewById<View>(R.id.btn_beam_size_inc).setOnClickListener {
            val current = inputBeamSize.text.toString().toIntOrNull() ?: DEFAULT_BEAM_SIZE
            inputBeamSize.text = (current + 1).coerceIn(1, 16).toString()
        }
        findViewById<View>(R.id.btn_best_of_dec).setOnClickListener {
            val current = inputBestOf.text.toString().toIntOrNull() ?: DEFAULT_BEST_OF
            inputBestOf.text = (current - 1).coerceIn(1, 16).toString()
        }
        findViewById<View>(R.id.btn_best_of_inc).setOnClickListener {
            val current = inputBestOf.text.toString().toIntOrNull() ?: DEFAULT_BEST_OF
            inputBestOf.text = (current + 1).coerceIn(1, 16).toString()
        }

        findViewById<View>(R.id.help_beam_size).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Beam size (Tamanho de busca)")
                .setMessage("Determina quantos caminhos de palavras o Whisper avalia simultaneamente ao transcrever.\n\n" +
                    "• Vantagem: Valores maiores podem melhorar a precisão em falas rápidas ou com ruído.\n" +
                    "• Desvantagem: Deixa a transcrição mais lenta e consome mais bateria/processamento.\n\n" +
                    "O padrão recomendado é 5.")
                .setPositiveButton("Fechar", null)
                .show()
        }
        findViewById<View>(R.id.help_best_of).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Best of (Melhor de)")
                .setMessage("Número de transcrições candidatas geradas internamente para que o modelo escolha a melhor.\n\n" +
                    "• Vantagem: Valores maiores ajudam a evitar repetições e alucinações de texto.\n" +
                    "• Desvantagem: Aumenta consideravelmente o tempo de processamento.\n\n" +
                    "O padrão recomendado é 5.")
                .setPositiveButton("Fechar", null)
                .show()
        }

        logText.text = readGlobalLog()

        logText.movementMethod = ScrollingMovementMethod.getInstance()
        terminalText.movementMethod = ScrollingMovementMethod.getInstance()
        terminalText.setOnTouchListener { view, event ->
            view.parent.requestDisallowInterceptTouchEvent(true)
            if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                view.parent.requestDisallowInterceptTouchEvent(false)
            }
            if (event.action == MotionEvent.ACTION_UP) view.performClick()
            false
        }
        logText.setOnTouchListener { view, event ->
            view.parent.requestDisallowInterceptTouchEvent(true)
            if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                view.parent.requestDisallowInterceptTouchEvent(false)
            }
            if (event.action == MotionEvent.ACTION_UP) view.performClick()
            false
        }
        buttonRecordingAction.setOnClickListener {
            if (whiteRecordingActive) stopWhiteRecording() else startWhiteRecording()
        }
        buttonLiveMicStop.setOnClickListener {
            if (liveMicActive) stopLiveMicTranscription() else startLiveMicTranscription()
        }
        buttonClearTranscript.setOnClickListener {
            liveTranscriptText.setText("")
        }
        buttonCopyLiveTranscript.setOnClickListener {
            copyTranscriptToClipboard()
        }
        buttonShareLiveTranscript.setOnClickListener {
            shareTranscript()
        }
        buttonPasteTranscript.setOnClickListener {
            pasteTranscript()
        }

        if (selectedModel == null) {
            val available = officialModels()
            // R15/A2 — o modelo pode ser pedido pelo ENSAIO (o lab sabe qual
            // modelo a célula deve usar; sem isso o produto escolhe o primeiro
            // que existe — o BASE — e a medição é do modelo errado).
            // A identificação é conferida no evento `model_loaded` (key + sha).
            val pedidoLabel = WhisperLabSeams.requestedModelLabel()
                ?: intent?.getStringExtra("labModelLabel")
            selectedModel = if (!pedidoLabel.isNullOrBlank()) {
                available.firstOrNull { it.label == pedidoLabel }
            } else null
            selectedModel = selectedModel
                ?: available.firstOrNull { DownloadFileStorage.isComplete(it.file, it.bytes) }
                ?: available.first()
            buttonModel.text = selectedModel?.label ?: "Modelo"
        }
        buttonModel.visibility = View.VISIBLE
        buttonLanguage.visibility = View.VISIBLE
        buttonBackend.visibility = View.VISIBLE

        buttonLanguage.text = selectedLanguage.shortLabel
        buttonBackend.text = selectedBackend.shortLabel
        logToggle.visibility = View.VISIBLE
        val exitHandler = installCancelAndExitGuard(
            isTaskRunning = { isTranscribing || isBusy || whiteRecordingActive || liveMicActive },
            cancelTask = { cancelRunningTaskForExit() }
        )
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { exitHandler() }
        findViewById<View>(R.id.button_select_media).setOnClickListener { showSourceMenu(it) }
        buttonTranscribe.setOnClickListener {
            if (isTranscribing) cancelTranscription() else transcribeSelectedMedia()
        }
        outputFileName.setOnClickListener { openOutputFile(lastSession?.txtFile, "text/plain") }
        buttonOutputExport.setOnClickListener { showExportMenu() }
        buttonOutputFolder.setOnClickListener { openOutputFolder() }
        buttonSelectOutputFolder.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            startActivityForResult(intent, REQUEST_CHOOSE_PRE_OUTPUT_DIR)
        }
        buttonSaveToFolder.setOnClickListener {
            val preUri = preSelectedOutputDirUri
            if (preUri != null) {
                saveTempOutputsToUri(preUri)
            } else {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                startActivityForResult(intent, REQUEST_CHOOSE_OUTPUT_DIR)
            }
        }
        // logToggle remains visible and static; no click listener needed
        buttonModel.setOnClickListener { showModelMenu() }
        buttonLanguage.setOnClickListener { showLanguageMenu() }
        buttonBackend.setOnClickListener { showBackendMenu() }
        advancedToggle.setOnClickListener { toggleAdvancedSettings() }
        resetAdvanced.setOnClickListener { resetAdvancedSettings() }
        resetAdvancedSettings()

        updateTranscribeEnabled()

        // R11/D4 — pedido de POST_NOTIFICATIONS: SÓ em produção (no lab o diálogo
        // do sistema roubaria o foco do roteiro; a célula mede o FGS, não a
        // permissão). O serviço roda igual sem ela: o sistema só não exibe a
        // notificação.
        if (!WhisperLabSeams.isLab()) requestJobNotificationPermission()

        // R11/D4 — o job passa a ter DONO DE LIFECYCLE (foreground service +
        // wake lock com teto). O botão "Cancelar" da notificação chega à sessão
        // desta Activity pelo mesmo caminho do botão na tela.
        WhisperJobService.forceEnabled = WhisperLabSeams.jobServiceEnabled()
        // R15/B3 — registro de PROCESSO (idempotente); sobrevive ao destroy.
        JobCancelBridge.attach(this)

        // R4/C3: gancho de laboratório no FIM do onCreate (produção: no-op —
        // o objeto de produção é inerte e nenhum roteiro externo existe).
        WhisperLabSeams.onActivityReady(this)

        // R6/D3: rota de ARQUIVO injetável (SÓ flavor lab; produção = null) —
        // entrega à MESMA callback do picker a Uri do provider de fixture do
        // lab (sondagem→conversão→load→JNI→relatório idênticos ao produto).
        WhisperLabSeams.injectedMediaUri(this)?.let { uri ->
            val data = Intent().setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            onActivityResult(REQUEST_PICK_MEDIA, Activity.RESULT_OK, data)
            // Lab: a escolha de arquivo reseta o modelo (fluxo real do usuário);
            // re-seleciona o primeiro modelo existente para o clique real de
            // transcrever exercitar a rota completa. Produção nunca entra aqui.
            // R15/A2 — mas a re-seleção NÃO pode trocar o modelo pedido pelo
            // ensaio: aqui ela caía no BASE e anulava a escolha do turbo.
            val pedidoLabel = WhisperLabSeams.requestedModelLabel()
                ?: intent?.getStringExtra("labModelLabel")
            if (selectedModel == null) {
                val available = officialModels()
                selectedModel = if (!pedidoLabel.isNullOrBlank()) {
                    available.firstOrNull { it.label == pedidoLabel }
                } else null
                selectedModel = selectedModel
                    ?: available.firstOrNull { DownloadFileStorage.isComplete(it.file, it.bytes) }
                    ?: available.firstOrNull()
                buttonModel.text = selectedModel?.label ?: "Modelo"
            }
            updateTranscribeEnabled()
        }
    }

    override fun onDestroy() {
        ortObserverGeneration++
        ortDownload?.cancel()
        ortDownload = null
        ortDownloadDialog?.dismiss()
        ortDownloadDialog = null
        dialogoFallbackEmExibicao?.dismiss()
        dialogoFallbackEmExibicao = null
        modelImportSelection.invalidate()
        // R3/B2: INVALIDA o owner ANTES do cleanup — posts/callbacks tardios
        // deste ciclo de vida morrem no runIfCurrent mesmo sem sucessor (a
        // Activity destruída não recebe evento antigo).
        val dyingGeneration = currentSessionGeneration
        whisperSession.invalidateOwner(dyingGeneration)
        // R4/C3: destroy visível no lab (produção: no-op).
        // R55/B04 — ORIGEM do destroy explícita: `finishing` (usuário/back),
        // `changing_config` (rotação/tema: o dono morre mas o processo fica) e
        // `destroyed`. Sem estes três, "destroy" era um nome só para causas
        // diferentes e a medição de UI não sabia o que estava medindo.
        liveEvent(
            "activity_destroy", dyingGeneration,
            fields = "\"finishing\":$isFinishing," +
                "\"changing_config\":$isChangingConfigurations," +
                "\"destroyed\":$isDestroyed," +
                "\"live_worker\":${liveMicPipeline?.isFinished() == false}"
        )
        // R9/B2: nada de texto final preso na janela de coalescência — o dump
        // final do lab lê o widget do terminal.
        terminalPublisher.flush()
        globalLogPublisher.flush()
        // R5/A3: CANCELAMENTO EFETIVO da sessão que está em inferência/carga —
        // encaminhado ao BINDING do job (por token; inclui init pendente, que
        // registra o pedido e o encaminha quando o owner nasce). Nunca atinge a
        // sucessora; nada de await/join/free na main (cancel é não-bloqueante).
        whisperSession.cancel(dyingGeneration)
        liveEvent(
            "destroy_cancel_forwarded",
            dyingGeneration,
            fields = "\"owner\":${whisperSession.bindingOwnerOf(dyingGeneration)}"
        )
        if (whiteRecordingActive) {
            whiteRecordingActive = false
            try { whiteRecordingAudioRecord?.stop() } catch (_: Throwable) {}
            try { whiteRecordingAudioRecord?.release() } catch (_: Throwable) {}
            whiteRecordingAudioRecord = null
        }
        if (liveMicActive) {
            liveMicActive = false
            try { liveMicAudioRecord?.stop() } catch (_: Throwable) {}
            try { liveMicAudioRecord?.release() } catch (_: Throwable) {}
            // R5/A3: desregistra o handle já liberado — o laço ao vivo confere a
            // identidade no finally e não libera o mesmo AudioRecord duas vezes.
            liveMicAudioRecord = null
        }
        // E1/R2: cancelar é estado distinto do stop — invalida callbacks do
        // caminho ao vivo e descarta pendentes contados; a thread do worker
        // termina sozinha depois do chunk em voo.
        liveMicPipeline?.cancel()
        liveMicPipeline = null
        // R11/D4 — o dono do job morreu: libera lock/notificação AQUI (nunca
        // deixa wake lock órfão se o sistema destruir a Activity do job).
        // R14/B2: destroy da dona NÃO é terminal — o serviço pede o cancel e
        // mantém FGS/wake lock até o worker reconhecer (prazo curto de segurança).
        WhisperJobService.ownerGone(dyingGeneration)
        // R15/B3 — NÃO desregistra: o forwarder é de processo e o cancelamento
        // precisa continuar aceito enquanto o worker drenar. Só a UI é solta.
        JobCancelBridge.detach(this)
        // W01: release assíncrono — nada de esperar liberação do modelo na UI
        // thread. O release leva o TOKEN da sessão que carregou o contexto.
        whisperSession.releaseAsync(dyingGeneration)
        loadedModelCache.clear()
        currentlyLoadedModelKey = null
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                pendingAudioAction?.invoke()
                pendingAudioAction = null
            } else {
                status.text = "Permissão do microfone necessária para gravação."
                Toast.makeText(this, "Permissão do microfone negada.", Toast.LENGTH_SHORT).show()
                pendingAudioAction = null
            }
        }
    }

    @Deprecated("Deprecated Android callback kept for this legacy XML activity.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // ORT1/M4 (a): botao PROPRIO da rota ORT. O botao existente e o
        // default do app continuam intocados.
        if (requestCode == REQUEST_PICK_MEDIA_ORT && resultCode == Activity.RESULT_OK) {
            val ortUri = data?.data
            if (ortUri != null) {
                uriEscolhida = ortUri
                // Honra o "forçar whisper.cpp": o botão de caminho alternativo não
                // pode burlar a escolha feita na caixa.
                if (WhisperOrtRoute.isEnabled(this)) {
                    despacharOrt(ortUri, data)
                } else {
                    ultimoDespacho = "CPP:$ortUri"
                    loadPickedMedia(data)
                    transcribeSelectedMedia()
                }
                return
            }
        }
        if (resultCode != Activity.RESULT_OK) return

        when (requestCode) {
            REQUEST_PICK_MEDIA -> data?.let { loadPickedMedia(it) }
            REQUEST_PICK_FOLDER -> data?.let { loadPickedFolder(it) }
            REQUEST_PICK_MODEL -> {
                // R14/E1 — no lab, o Uri do MODELO é entregue pela MESMA callback
                // que o picker real usaria (o caminho SAF do produto, sem
                // exportar o serviço nem contornar a importação).
                val modelUri = WhisperLabSeams.injectedModelUri(this)
                if (modelUri != null) {
                    loadPickedModel(Intent().setData(modelUri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                } else {
                    data?.let { loadPickedModel(it) }
                }
            }
            REQUEST_CHOOSE_PRE_OUTPUT_DIR -> {
                val treeUri = data?.data ?: return
                try {
                    val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    contentResolver.takePersistableUriPermission(treeUri, takeFlags)
                } catch (_: SecurityException) {}
                preSelectedOutputDirUri = treeUri
                val doc = DocumentFile.fromTreeUri(this, treeUri)
                Toast.makeText(this, "Pasta de saída: ${doc?.name ?: "Selecionada"}", Toast.LENGTH_SHORT).show()
                buttonSelectOutputFolder.setBackgroundResource(R.drawable.ffmpeg_outline_green_button_bg)
            }
            REQUEST_CHOOSE_OUTPUT_DIR -> {
                val treeUri = data?.data ?: return
                try {
                    val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    contentResolver.takePersistableUriPermission(treeUri, takeFlags)
                } catch (_: SecurityException) {}
                saveTempOutputsToUri(treeUri)
            }
        }
    }

    private fun showSourceMenu(anchor: View) {
        val density = resources.displayMetrics.density
        val menuView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.ffmpeg_popup_menu_bg)
            addView(sourceMenuItem("Selecionar arquivos") {
                sourcePopup?.dismiss()
                openMediaPicker()
            })
            addView(sourceMenuItem("Selecionar pasta") {
                sourcePopup?.dismiss()
                openFolderPicker()
            })
        }
        sourcePopup = PopupWindow(
            menuView,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            isOutsideTouchable = true
            elevation = 0f
            setBackgroundDrawable(getDrawable(android.R.color.transparent))
            showAsDropDown(anchor, 0, (4 * density).toInt(), Gravity.START)
        }
    }

    private fun sourceMenuItem(label: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            gravity = Gravity.CENTER_VERTICAL
            minHeight = (48 * resources.displayMetrics.density).toInt()
            setPadding(
                (14 * resources.displayMetrics.density).toInt(),
                0,
                (14 * resources.displayMetrics.density).toInt(),
                0
            )
            background = getDrawable(android.R.color.transparent)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    /**
     * Fallback de compatibilidade: PERGUNTA antes de usar o whisper.cpp.
     *
     * ORT e o padrao; se ele recusar/falhar (formato, canais, duracao, pacote),
     * o usuario decide. Nunca troca de motor em silencio.
     */
    private fun confirmarFallbackCpp(motivo: String, data: Intent?) {
        if (isFinishing || isDestroyed || data == null) {
            WhisperOrtRoute.avisar(this, "ORT indisponivel: $motivo")
            return
        }
        // Aceitar cria o pedido CPP DESTA entrada com override explícito (a
        // preferência geral continua ORT); recusar não mexe em nada. BUSY, dreno e
        // cancelamento nunca chegam aqui (ver `isFallbackElegivel`).
        dialogoFallbackEmExibicao = android.app.AlertDialog.Builder(this)
            .setTitle("Nao foi possivel usar o ORT")
            .setMessage("$motivo\n\nUsar o whisper.cpp para este arquivo?")
            .setPositiveButton("Usar whisper.cpp") { dialogo, _ ->
                val alvo = data.data ?: uriEscolhida
                cppParaEstaEntrada = alvo
                loadPickedMedia(data)
                transcribeSelectedMedia()
                dialogo.dismiss()
                dialogoFallbackEmExibicao = null
            }
            .setNegativeButton("Cancelar") { dialogo, _ ->
                dialogo.dismiss()
                dialogoFallbackEmExibicao = null
            }
            .setOnCancelListener { dialogoFallbackEmExibicao = null }
            .show()
    }

    private fun loadPickedMedia(data: Intent) {
        // Congela a escolha real: o despacho ORT e o CPP passam a ver o MESMO
        // arquivo, em vez de "A por intent, B na tela".
        uriEscolhida = data.data ?: data.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
        selectedItems.clear()
        data.clipData?.let { clip ->
            for (index in 0 until clip.itemCount) {
                addSelectedUri(clip.getItemAt(index).uri, data.flags)
            }
        } ?: data.data?.let { addSelectedUri(it, data.flags) }

        if (selectedItems.isEmpty()) return

        resetModelSelection()
        selectedFileView.text = selectedSummary()
        selectedFileView.visibility = View.VISIBLE
        buttonSelectOutputFolder.visibility = View.VISIBLE
        arrowInputOutput.visibility = View.VISIBLE
        if (preSelectedOutputDirUri != null) {
            buttonSelectOutputFolder.setBackgroundResource(R.drawable.ffmpeg_outline_green_button_bg)
        } else {
            buttonSelectOutputFolder.setBackgroundResource(R.drawable.ffmpeg_outline_button_bg)
        }
        buttonModel.visibility = View.VISIBLE
        buttonLanguage.visibility = View.VISIBLE
        buttonBackend.visibility = View.VISIBLE
        buttonTranscribe.visibility = View.VISIBLE
        advancedToggle.visibility = View.VISIBLE
        status.text = "Escolha um modelo."
        clearOutput()
        updateTranscribeEnabled()
        // R22/Q1 — ORDEM CORRETA: o picker acabou de zerar a seleção
        // (resetModelSelection). A seleção pedida pela bancada entra AGORA, na
        // main thread, e não antes da mídia chegar.
        runCatching { WhisperLabSeams.applyPendingModelSelection(this) }
    }

    private fun loadPickedFolder(data: Intent) {
        val treeUri = data.data ?: return
        try {
            if (data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0) {
                contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (_: SecurityException) {
        }

        val folder = DocumentFile.fromTreeUri(this, treeUri)
        if (folder == null || !folder.isDirectory) {
            Toast.makeText(this, "Não consegui abrir essa pasta.", Toast.LENGTH_SHORT).show()
            return
        }

        selectedItems.clear()
        folder.listFiles()
            .filter { it.isFile && isAudioOrVideo(it.type.orEmpty(), it.name.orEmpty()) }
            .sortedBy { it.name.orEmpty().lowercase(Locale.US) }
            .forEach { file ->
                selectedItems += MediaItem(file.uri, file.name ?: "midia_${selectedItems.size + 1}")
            }

        if (selectedItems.isEmpty()) {
            Toast.makeText(this, "A pasta escolhida não tem áudio ou vídeo reconhecido.", Toast.LENGTH_SHORT).show()
            return
        }

        resetModelSelection()
        selectedFileView.text = selectedSummary()
        selectedFileView.visibility = View.VISIBLE
        buttonSelectOutputFolder.visibility = View.VISIBLE
        arrowInputOutput.visibility = View.VISIBLE
        if (preSelectedOutputDirUri != null) {
            buttonSelectOutputFolder.setBackgroundResource(R.drawable.ffmpeg_outline_green_button_bg)
        } else {
            buttonSelectOutputFolder.setBackgroundResource(R.drawable.ffmpeg_outline_button_bg)
        }
        buttonModel.visibility = View.VISIBLE
        buttonLanguage.visibility = View.VISIBLE
        buttonBackend.visibility = View.VISIBLE
        buttonTranscribe.visibility = View.VISIBLE
        advancedToggle.visibility = View.VISIBLE
        status.text = "Escolha um modelo."
        clearOutput()
        updateTranscribeEnabled()
    }

    private fun addSelectedUri(uri: Uri, flags: Int) {
        try {
            contentResolver.takePersistableUriPermission(uri, flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
        }
        val name = MediaUriSupport.queryDisplayName(contentResolver, uri) ?: "audio_${selectedItems.size + 1}"
        selectedItems += MediaItem(uri, name)
    }

    private fun selectedSummary(): String {
        return if (selectedItems.size == 1) {
            selectedItems.first().name
        } else {
            "${selectedItems.size} arquivos: ${selectedItems.take(3).joinToString(", ") { it.name }}"
        }
    }

    private fun resetModelSelection() {
        modelImportSelection.invalidate()
        selectedModel = null
        buttonModel.text = "Modelo"
    }

    private fun openMediaPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/*", "video/*"))
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_PICK_MEDIA)
    }

    private fun openFolderPicker() {
        val downloadsUri = DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents",
            "primary:${Environment.DIRECTORY_DOWNLOADS}"
        )
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            putExtra(DocumentsContract.EXTRA_INITIAL_URI, downloadsUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_PICK_FOLDER)
    }

    /**
     * R14/E1 — Importação de modelo pelo fluxo REAL, disparada pelo lab.
     * Usa a MESMA função do produto ([loadPickedModel]): mesma leitura de
     * tamanho, mesmo hash, mesma cópia atômica, mesmo sidecar de identidade.
     * O shell não consegue navegar o DocumentsUI do SAF, então o lab entrega o
     * Uri — o resto do caminho é o do produto, sem atalho.
     */
    fun labImportModel(uri: android.net.Uri) {
        liveEvent("model_import_injected", currentSessionGeneration,
            fields = "\"uri\":\"$uri\"")
        loadPickedModel(Intent().setData(uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    private fun openModelPicker() {
        // R14/E1 — no lab, o Uri do modelo é entregue SEM abrir o SAF (o shell
        // não consegue navegar o DocumentsUI): a MESMA rota de importação do
        // produto é usada (`loadPickedModel`), com a mesma callback.
        val injected = runCatching { WhisperLabSeams.injectedModelUri(this) }
            .onFailure { Log.w(TAG, "seam de modelo indisponivel: $it") }
            .getOrNull()
        Log.i(TAG, "openModelPicker: injected=$injected")
        if (injected != null) {
            liveEvent("model_import_injected", currentSessionGeneration,
                fields = "\"uri\":\"$injected\"")
            loadPickedModel(Intent().setData(injected)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            return
        }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/octet-stream", "application/x-binary", "*/*"))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_PICK_MODEL)
    }

    private fun loadPickedModel(data: Intent) {
        val uri = data.data ?: return
        val name = MediaUriSupport.queryDisplayName(contentResolver, uri)?.takeIf { it.lowercase(Locale.US).endsWith(".bin") }
            ?: "modelo_${System.currentTimeMillis()}.bin"
        val destination = File(modelsDir(), name)
        val importToken = modelImportSelection.beginImport()
        Thread {
            try {
                if (!modelImportSelection.isCurrent(importToken)) throw CancellationException("importação substituída")
                modelsDir().mkdirs()
                val sourceSize = contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
                val identitySidecar = ModelImportSupport.identityFile(destination)
                val existing = ModelImportSupport.readIdentity(identitySidecar)
                // R18/A2 — sha do arquivo PUBLICADO (COPY calcula; REUSE lê o sidecar).
                var publicadoSha: String? = null
                val sourceSha = if (destination.isFile && existing != null && sourceSize > 0L && existing.second == sourceSize) {
                    contentResolver.openInputStream(uri)?.use { ModelImportSupport.sha256(it) }
                } else {
                    null
                }
                var freeBytes = -1L
                val decision = ModelImportSupport.importDecision(
                    destination,
                    existing,
                    sourceSha,
                    sourceSize,
                ) {
                    freeBytes = destination.parentFile?.usableSpace ?: -1L
                    freeBytes
                }
                when (decision) {
                    ModelImportSupport.ImportDecision.REUSE -> Unit
                    ModelImportSupport.ImportDecision.INSUFFICIENT_SPACE -> {
                        throw IOException("espaço insuficiente para importar o modelo: precisa " +
                            "${sourceSize / (1024 * 1024)} MB, livre ${freeBytes / (1024 * 1024)} MB")
                    }
                    ModelImportSupport.ImportDecision.COPY -> {
                    val tmp = ModelImportSupport.partFile(destination)
                    try {
                        val sha = contentResolver.openInputStream(uri)?.use { input ->
                            FileOutputStream(tmp).use { output ->
                                val digest = ModelImportSupport.copyAndDigest(input, output)
                                output.fd.sync()
                                digest
                            }
                        } ?: throw IllegalStateException("não consegui abrir o modelo selecionado")
                        if (sourceSize > 0L && tmp.length() != sourceSize) {
                            throw IOException("tamanho copiado difere da origem: ${tmp.length()} != $sourceSize")
                        }
                        ModelImportSupport.publishAtomically(
                            part = tmp,
                            destination = destination,
                            identity = sha to tmp.length(),
                            commitIfCurrent = { action -> modelImportSelection.runIfCurrent(importToken, action) },
                            atomicMove = { source, target ->
                                synchronized(modelLoadLock) { Os.rename(source.absolutePath, target.absolutePath) }
                            },
                        )
                        publicadoSha = sha
                    } catch (e: Throwable) {
                        tmp.delete()
                        throw e
                    }
                }
                }
                if (publicadoSha == null) publicadoSha = existing?.first
                val model = WhisperModel(name.removeSuffix(".bin"), name, destination, null)
                runOnUiThread {
                    // R18/A2 — PUBLICAR não é SELECIONAR. Só emite "selecionado"
                    // quando o callback corrente aceitou; stale/recusa vira
                    // evento próprio, com o motivo.
                    val aplicada = modelImportSelection.selectIfCurrent(importToken) { applyModelSelection(model) }
                    val campos = "\"name\":\"$name\",\"bytes\":${destination.length()}," +
                        "\"sha256\":\"${publicadoSha ?: ""}\",\"applied\":$aplicada,\"token\":$importToken"
                    if (aplicada) {
                        liveEvent("model_import_selected", currentSessionGeneration, fields = campos)
                        // compatibilidade com o consumo antigo do lab
                        liveEvent("model_imported", currentSessionGeneration,
                            fields = "\"name\":\"$name\",\"bytes\":${destination.length()}," +
                                "\"sha256\":\"${publicadoSha ?: ""}\"")
                    } else {
                        Log.i(TAG, "Ignoring stale model import selection: $name")
                        liveEvent("model_import_stale", currentSessionGeneration,
                            fields = campos + ",\"motivo\":\"callback_obsoleto\"")
                    }
                }
            } catch (_: CancellationException) {
                Log.i(TAG, "Model import superseded: $name")
            } catch (e: Throwable) {
                Log.e(TAG, "Could not import model", e)
                // R14/E1 — o erro da importação precisa ser OBSERVÁVEL pelo
                // ensaio (o logcat some na rotação).
                liveEvent("model_import_failed", currentSessionGeneration,
                    fields = "\"error\":\"${e.javaClass.simpleName}: ${e.message}\",\"name\":\"$name\"")
                runOnUiThread {
                    if (modelImportSelection.isCurrent(importToken)) {
                        status.text = "Erro ao importar modelo: ${e.message ?: "falha inesperada"}"
                    }
                }
            }
        }.start()
    }

    private fun isAudioOrVideo(mime: String, name: String): Boolean {
        val lowerName = name.lowercase(Locale.US)
        return mime.startsWith("audio/") ||
            mime.startsWith("video/") ||
            lowerName.endsWith(".mp3") ||
            lowerName.endsWith(".wav") ||
            lowerName.endsWith(".m4a") ||
            lowerName.endsWith(".aac") ||
            lowerName.endsWith(".ogg") ||
            lowerName.endsWith(".flac") ||
            lowerName.endsWith(".mp4") ||
            lowerName.endsWith(".mkv") ||
            lowerName.endsWith(".mov") ||
            lowerName.endsWith(".avi") ||
            lowerName.endsWith(".webm")
    }

    private fun supportsVulkanCompute(): Boolean {
        return packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_COMPUTE)
    }

    private fun showLanguageMenu() {
        PopupMenu(this, buttonLanguage).apply {
            WhisperLanguage.values().forEach { language -> menu.add(language.label) }
            setOnMenuItemClickListener { item ->
                selectedLanguage = WhisperLanguage.values().first { it.label == item.title.toString() }
                buttonLanguage.text = selectedLanguage.shortLabel
                true
            }
            show()
        }
    }

    private fun showModelMenu() {
        val models = officialModels()
        PopupMenu(this, buttonModel).apply {
            models.forEachIndexed { index, model ->
                val display = if (DownloadFileStorage.isComplete(model.file, model.bytes)) "✓ ${model.label}" else model.label
                menu.add(0, index + 1, 0, display)
            }
            menu.add(0, models.size + 1, 0, "Selecionar arquivo de modelo...")
            setOnMenuItemClickListener { item ->
                val itemId = item.itemId
                if (itemId == models.size + 1) {
                    openModelPicker()
                    return@setOnMenuItemClickListener true
                }
                val model = models.getOrNull(itemId - 1) ?: return@setOnMenuItemClickListener true
                if (DownloadFileStorage.isComplete(model.file, model.bytes)) {
                    selectModel(model)
                } else {
                    confirmModelDownload(model)
                }
                true
            }
            show()
        }
    }

    private fun selectModel(model: WhisperModel) {
        modelImportSelection.invalidate()
        applyModelSelection(model)
    }

    private fun applyModelSelection(model: WhisperModel) {
        selectedModel = model
        buttonModel.text = model.label
        status.text = "${model.label} pronto."
        updateTranscribeEnabled()
    }

    private fun confirmModelDownload(model: WhisperModel) {
        val url = model.downloadUrl ?: return
        // O diálogo ANTES não dizia tamanho nenhum — e o v3Turbo são 1,6 GB.
        DownloadPlanDialog.confirmar(
            activity = this,
            plano = DownloadSizeFormat.Plano(
                rotulo = "Modelo Whisper ${model.label}",
                arquivos = listOf(
                    DownloadSizeFormat.Arquivo(model.fileName, model.bytes, jaBaixado = DownloadFileStorage.isComplete(model.file, model.bytes))
                ),
            ),
            negativo = "Cancelar",
            positivo = "Baixar",
            prefacio = "Ainda não temos o modelo ${model.label} no aparelho.",
            sufixo = "Baixar agora?",
        ) {
            downloadModelWithProgress(
                    label = model.label,
                    url = url,
                    destination = model.file
                ) { selectModel(model) }
        }
    }

    private fun showBackendMenu() {
        PopupMenu(this, buttonBackend).apply {
            // R56/adendo20 — política central: OpenCL (nativeKind 2) fica
            // temporariamente desativado; o menu oferece só o que está ativo.
            // O enum não é reindexado: reativar é mudar a política, não a UI.
            val visiveis = WhisperBackend.values().filter {
                WhisperBackendDisponibilidade.disponivel(it.nativeKind)
            }
            visiveis.forEach { backend -> menu.add(backend.label) }
            setOnMenuItemClickListener { item ->
                val backend = visiveis.first { it.label == item.title.toString() }
                if (backend == WhisperBackend.VULKAN && !supportsVulkanCompute()) {
                    status.text = "Este aparelho não anuncia suporte a Vulkan Compute."
                    return@setOnMenuItemClickListener true
                }
                selectedBackend = backend
                buttonBackend.text = selectedBackend.shortLabel
                true
            }
            show()
        }
    }

    /**
     * Publica o desfecho da rota ORT na ferramenta.
     *
     * Texto COMPLETO, não um toast de 200 caracteres. CANCELLED, PARTIAL e ERROR
     * aparecem com o rótulo real: um resultado parcial nunca é apresentado como
     * sucesso integral.
     */
    private fun publicarResultadoOrt(resultado: WhisperOrtRoute.Result) {
        // §4 — OBSERVADOR: resultado de uma tela que já morreu não pode escrever em
        // view morta nem confirmar entrega. O recibo fica pendente para a próxima
        // Activity recuperar (recuperarEntregasPendentes).
        if (isFinishing || isDestroyed) return
        val rotulo = when (resultado) {
            is WhisperOrtRoute.Result.Done -> resultado.outcome
            is WhisperOrtRoute.Result.Cancelled -> if (resultado.partial) "CANCELLED (parcial)" else "CANCELLED"
            is WhisperOrtRoute.Result.Refused -> "RECUSADO"
            is WhisperOrtRoute.Result.Failed -> "ERRO"
        }
        val texto = when (resultado) {
            is WhisperOrtRoute.Result.Done -> resultado.text
            is WhisperOrtRoute.Result.Cancelled -> resultado.text
            else -> ""
        }
        val detalhe = when (resultado) {
            is WhisperOrtRoute.Result.Refused -> resultado.reason
            is WhisperOrtRoute.Result.Failed -> resultado.reason
            else -> null
        }
        // O grupo começa GONE no layout: preencher o TextView sem exibir o container
        // não mostra nada ao usuário (defeito apontado no adendo 10).
        findViewById<android.view.View?>(R.id.live_transcript_container)?.visibility = View.VISIBLE
        findViewById<android.view.View?>(R.id.live_transcript_clipboard_actions)?.visibility = View.VISIBLE
        findViewById<android.widget.TextView?>(R.id.live_transcript_text)?.text = texto
        val resumo = "ORT/CPU [$rotulo] ${texto.length} caracteres"
        WhisperOrtRoute.avisar(this, detalhe?.let { "$resumo - $it" } ?: resumo)
        // Recibo da PRÓPRIA tentativa (não o global): o global confirmava o job mais
        // recente e deixava o exibido pendente. Terminais sem texto (NO_SPEECH,
        // cancel antes do 1º token, erro) também são entrega válida. A identidade
        // (recibo deste job) já foi conferida pelo chamador, ANTES de qualquer
        // escrita — aqui só se entrega e confirma.
        val recibo = resultado.recibo
        if (recibo == null) {
            // Recusa na porta não publica terminal: nada a persistir, nada a avisar
            // além do motivo já exibido. Done/Cancelled sem recibo é falha de
            // entrega e fica observável.
            if (resultado is WhisperOrtRoute.Result.Done || resultado is WhisperOrtRoute.Result.Cancelled) {
                WhisperOrtRoute.avisar(this, "resultado NAO persistido (entrega falhou)")
            }
        } else {
            WhisperOrtRoute.confirmarEntrega(this, recibo)
        }
    }

    /**
     * Fluxo gerenciado do pacote ORT (§4): quando o pacote não está pronto, oferece
     * baixar (com tamanho, progresso e cancelamento), usar o whisper.cpp só para
     * este arquivo, ou cancelar. Nada é transcrito sem pacote válido.
     */
    private fun oferecerDownloadModeloOrt(uri: android.net.Uri) {
        if (isFinishing || isDestroyed) return
        val totalMb = WhisperOrtModelCatalog.active.sumOf { it.sizeBytes } / 1_048_576L
        android.app.AlertDialog.Builder(this)
            .setTitle("Modelo ORT não instalado")
            .setMessage(
                "A transcrição por ONNX Runtime precisa do pacote de modelo " +
                    "(${totalMb} MB, Hugging Face, revisão congelada). " +
                    "O que fazer com este arquivo?",
            )
            .setPositiveButton("Baixar modelo") { dialogo, _ ->
                dialogo.dismiss()
                baixarModeloOrt(uri)
            }
            .setNeutralButton("Usar whisper.cpp") { dialogo, _ ->
                dialogo.dismiss()
                cppParaEstaEntrada = uri
                loadPickedMedia(Intent().setData(uri))
                transcribeSelectedMedia()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /**
     * Baixa o pacote com progresso e cancelamento, valida e ativa atomicamente.
     *
     * A versão atual continua utilizável se cancelar/falhar; o pacote em uso fica
     * protegido (staging à parte, ativação pelo `install` existente). Ao concluir,
     * despacha o arquivo que originou o pedido.
     */
    private fun baixarModeloOrt(uri: android.net.Uri) {
        val attempt = WhisperOrtDownloadOwner.begin()
        if (attempt == null) {
            status.text = "Um download ORT ainda está em andamento; aguarde o encerramento."
            return
        }
        ortDownload = attempt
        val generation = ++ortObserverGeneration
        val dialogo = android.app.AlertDialog.Builder(this)
            .setTitle("Baixando modelo ORT")
            .setMessage("preparando…")
            .setNegativeButton("Cancelar") { _, _ -> attempt.cancel() }
            .setCancelable(false).show()
        ortDownloadDialog = dialogo
        val app = applicationContext
        val observer = java.lang.ref.WeakReference(this)
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        // O worker só retém app, token e referência fraca. Tela destruída cancela
        // a tentativa; o owner global permanece ocupado até fechar rede/staging.
        fun enviar(action: (WhisperActivity) -> Unit) {
            handler.post {
                val tela = observer.get()
                if (tela != null && !tela.isDestroyed && !tela.isFinishing &&
                    tela.ortObserverGeneration == generation && tela.ortDownload === attempt
                ) action(tela)
            }
        }
        Thread({
            val staging = File(app.filesDir, "ort-staging-${attempt.id}")
            try {
                val root = WhisperOrtPackageFlow.raiz(app)
                val baixado = WhisperOrtModelDownload.baixar(
                    WhisperOrtModelCatalog.active, staging,
                    isCancelada = attempt::isCancelled,
                    onProgresso = { p ->
                        enviar { tela ->
                            tela.ortDownloadDialog?.setMessage(
                                "${p.arquivoAtual}: ${p.baixadosTotal / 1_048_576} de ${p.total / 1_048_576} MB",
                            )
                        }
                    },
                )
                val instalado = if (baixado is WhisperOrtModelDownload.Resultado.Pronto && !attempt.isCancelled()) {
                    WhisperOrtPackageInstaller.install(
                        baixado.staging, root, WhisperOrtModelCatalog.identity(),
                        WhisperOrtModelCatalog.active, isCancelled = attempt::isCancelled,
                        withPublication = { action ->
                            attempt.publish(action) ?: WhisperOrtPackageInstaller.Outcome.Refused("CANCELADO", "cancelado antes de ativar")
                        },
                    )
                } else null
                enviar { tela ->
                    tela.ortDownloadDialog?.dismiss()
                    tela.ortDownloadDialog = null
                    tela.ortDownload = null
                    when {
                        attempt.isCancelled() || baixado is WhisperOrtModelDownload.Resultado.Cancelado ->
                            tela.status.text = "Download ORT cancelado."
                        instalado is WhisperOrtPackageInstaller.Outcome.Ready -> {
                            tela.status.text = "Modelo ORT pronto."
                            if (tela.uriEscolhida == uri) tela.despacharOrt(uri, null)
                        }
                        else -> tela.status.text = when {
                            baixado is WhisperOrtModelDownload.Resultado.Falhou -> "Download ORT: ${baixado.motivo}"
                            instalado is WhisperOrtPackageInstaller.Outcome.Refused -> "Pacote ORT: ${instalado.detail}"
                            else -> "Download ORT não concluído."
                        }
                    }
                }
            } catch (failure: Exception) {
                enviar { tela ->
                    tela.ortDownloadDialog?.dismiss()
                    tela.ortDownloadDialog = null
                    tela.ortDownload = null
                    tela.status.text = "Download ORT: ${failure.message}"
                }
            } finally {
                staging.deleteRecursively()
                WhisperOrtDownloadOwner.finish(attempt)
            }
        }, "whisper-ort-download").apply { isDaemon = true }.start()
    }

    /** Despacho único da rota ORT (picker normal, botão ORT e intent). */
    private fun despacharOrt(uri: android.net.Uri, data: Intent?) {
        if (jobOrtNaTela != null || isBusy) {
            status.text = "Aguarde o encerramento do trabalho atual."
            return
        }
        // O caminho do botão normal não tem Intent: sintetiza um com a URI congelada
        // para o consentimento de fallback valer em TODOS os caminhos.
        val contexto = data ?: Intent().setData(uri)
        // §4: se o pacote não está pronto, o usuário sabe ANTES — a rota ainda
        // recusa com motivo tipado e o diálogo de fallback continua valendo.
        val estadoPacote = runCatching { WhisperOrtPackageFlow.estado(this) }.getOrNull()
        if (estadoPacote != null && estadoPacote !is WhisperOrtPackageFlow.Estado.Pronto) {
            WhisperOrtRoute.avisar(this, WhisperOrtPackageFlow.explicar(estadoPacote))
            oferecerDownloadModeloOrt(uri)
            return
        }
        // A identidade do evento é capturada AQUI (thread chamadora), antes de
        // qualquer escrita em view, progresso, confirmação ou diálogo. Só o
        // resultado COM o recibo deste job passa; callback antigo não escreve,
        // não confirma e não oferece fallback sobre outro pedido.
        val generation = ++ortObserverGeneration
        var meuJob: WhisperOrtAdmission.Job? = null
        meuJob = WhisperOrtRoute.transcribe(
            this,
            uri,
            // Progresso do preparo (cópia/conversão) no status: a tela deixa de
            // ficar parada sem sinal de vida durante a conversão.
            onProgresso = { etapa -> runOnUiThread {
                if (!isDestroyed && !isFinishing && ortObserverGeneration == generation && jobOrtNaTela == meuJob) {
                    status.text = "ORT: $etapa"
                }
            } },
            // Threads do controle da ferramenta (default 4, máx 8, pedido do usuário).
            threads = WhisperOrtRoute.threadsEfetivos(this),
            onResult = { resultado ->
            if (isDestroyed || isFinishing || ortObserverGeneration != generation) return@transcribe
            if (meuJob == null) {
                // Recusa ANTES de enfileirar: sem job e sem recibo, só informa.
                // Não apaga a identidade do job que já está rodando.
                if (resultado is WhisperOrtRoute.Result.Refused || resultado is WhisperOrtRoute.Result.Failed) {
                    publicarResultadoOrt(resultado)
                }
                return@transcribe
            }
            val dono = meuJob
            val recibo = resultado.recibo
            if (dono == null || jobOrtNaTela != dono ||
                (recibo != null && (recibo.jobId != dono.jobId || recibo.generation != dono.generation))) {
                return@transcribe
            }
            isTranscribing = false
            cancelRequested = false
            setBusy(false)
            jobOrtNaTela = null
            publicarResultadoOrt(resultado)
            // Fallback SOMENTE quando elegível (recusa por capacidade): BUSY,
            // dreno e cancelamento não oferecem troca de motor.
            if (resultado is WhisperOrtRoute.Result.Refused &&
                WhisperOrtRoute.isFallbackElegivel(resultado.reason)
            ) {
                confirmarFallbackCpp(resultado.reason, contexto)
            }
            },
        )
        // Recusa na porta NÃO apaga a identidade do que já está rodando.
        if (meuJob != null) {
            jobOrtNaTela = meuJob
            cancelRequested = false
            isTranscribing = true
            setBusy(true)
            buttonTranscribe.visibility = View.VISIBLE
            ultimoDespacho = "ORT:$uri"
        }
    }

    /**
     * Reentrega o resultado que ficou pendente.
     *
     * Se a Activity morreu antes de confirmar, o terminal está no sink sem ACK.
     * Aqui ele é exibido e SÓ ENTÃO confirmado — a recuperação não depende do
     * callback antigo nem de a tela ter sobrevivido.
     */
    private fun recuperarEntregasPendentes() {
        val fila = runCatching { WhisperOrtRoute.pendentes(this) }.getOrDefault(emptyList())
            .sortedByDescending { it.publicadoEmMillis }
        if (fila.isEmpty()) return
        // Exibe o mais recente e confirma SÓ ele. Antes, um terminal sem texto
        // retornava sem confirmar e BLOQUEAVA os anteriores para sempre.
        val pendente = fila.first()
        val json = runCatching {
            org.json.JSONObject(java.io.File(pendente.caminho).readText(Charsets.UTF_8))
        }.getOrNull() ?: return
        val texto = json.optString("texto", "")
        val outcome = json.optString("outcome", "?")
        findViewById<android.view.View?>(R.id.live_transcript_container)?.visibility = View.VISIBLE
        findViewById<android.widget.TextView?>(R.id.live_transcript_text)?.text =
            if (texto.isBlank()) "[$outcome] sem texto (fala ausente, cancelamento ou erro)" else texto
        WhisperOrtRoute.confirmarEntrega(this, pendente)
    }

    private fun transcribeSelectedMedia() {
        // ORT1/M4 - rota opt-in (arquivo/CPU/uma janela). Default continua CPP;
        // falha ORT NAO cai para CPP silenciosamente.
        // Override por arquivo vence o default: consentimento do usuário no fallback.
        val forcadoCpp = WhisperOrtSelecao.forcarCpp(
            cppParaEstaEntrada?.toString(),
            uriEscolhida?.toString(),
        )
        cppParaEstaEntrada = null
        if (WhisperOrtRoute.isEnabled(this) && !forcadoCpp) {
            if (selectedItems.size > 1) {
                confirmarFallbackCpp("ORT recebe um arquivo por vez. Para este lote, use whisper.cpp.",
                    Intent().setData(uriEscolhida))
                return
            }
            // Snapshot da seleção: o arquivo escolhido no picker vence o intent.
            val ortUri = WhisperOrtSelecao.resolver(
                uriEscolhida?.toString(),
                WhisperOrtRoute.resolveUri(this)?.toString(),
            )?.let { android.net.Uri.parse(it) }
            if (ortUri != null) {
                despacharOrt(ortUri, null)
                return
            }
        } else if (forcadoCpp) {
            // Pedido CPP efetivo desta entrada (override do consentimento): registra
            // a identidade para prova e segue o fluxo CPP da ferramenta.
            ultimoDespacho = "CPP:${uriEscolhida}"
        }
        val model = selectedModel ?: return
        // R16/A2 — GATE DE IDENTIDADE (pedido x selecionado).
        //
        // A R15 rodou uma célula "Q5" em que o produto selecionou BASE e nada
        // falhou: o relatório saiu como PASS. Aqui a divergência é terminal.
        val esperado = WhisperLabSeams.expectedModelPath()
        if (esperado != null) {
            val selecionado = modelFile(model).absolutePath
            // R16/A2 — compara pelo NOME: o ensaio pode apontar para outro
            // diretório com o MESMO arquivo (files/whisperlab-model vs
            // files/whisper_models), e comparar path literal gerava falso
            // positivo, bloqueando célula legítima.
            if (File(selecionado).name != File(esperado).name) {
                status.text = "Modelo divergente do pedido — célula inválida."
                runCatching { WhisperLabSeams.onModelIdentityMismatch(esperado, selecionado) }
                Log.w(TAG, "model_identity_mismatch pedido=$esperado selecionado=$selecionado")
                updateTranscribeEnabled()
                return
            }
            Log.i(TAG, "model_identity_ok path=$selecionado")
        }
        val items = selectedItems.toList()
        // R19/A4 — backend/FA da CELULA tem precedencia sobre o default do
        // produto; sem isso o job rodava CPU mesmo pedindo Vulkan.
        val forcedKind = WhisperLabSeams.forcedBackendKind(this)
        val forcedFa = WhisperLabSeams.forcedFlashAttention(this)
        Log.i(TAG, "lab_forced_backend kind=$forcedKind fa=$forcedFa default=${selectedBackend.nativeKind}")
        // R56/adendo20 — pedido EXPLÍCITO de backend pausado é RECUSADO de forma
        // tipada antes de carregar/executar. Converter em CPU silenciosamente
        // faria o ensaio registrar "OpenCL" tendo rodado em outro backend.
        if (forcedKind != null) {
            val motivoRecusa = WhisperBackendDisponibilidade.motivoIndisponivel(forcedKind)
            if (motivoRecusa != null) {
                WhisperLabSeams.onBackendRecusado(forcedKind, motivoRecusa)
                Log.w(TAG, "backend_recusado kind=$forcedKind motivo=$motivoRecusa")
                status.text = "Backend $forcedKind recusado: $motivoRecusa."
                updateTranscribeEnabled()
                return
            }
        }
        val backend = forcedKind
            ?.let { k -> WhisperBackend.values().firstOrNull { it.nativeKind == k } }
            ?: WhisperBackend.values()
                .firstOrNull { it.nativeKind == WhisperBackendDisponibilidade.resolver(selectedBackend.nativeKind) }
            ?: WhisperBackend.CPU
        val settings = readAdvancedSettings().let { st ->
            WhisperLabSeams.forcedFlashAttention(this)?.let { fa -> st.copy(flashAttention = fa) } ?: st
        }
        if (items.isEmpty()) return
        if (!DownloadFileStorage.isComplete(modelFile(model), model.bytes)) {
            model.downloadUrl?.let {
                confirmModelDownload(model)
                return
            }
            status.text = "Escolha um modelo existente."
            updateTranscribeEnabled()
            return
        }
        if (settings.vadFilter && !DownloadFileStorage.isComplete(vadModelFile(), VAD_MODEL_BYTES)) {
            confirmVadDownload { transcribeSelectedMedia() }
            return
        }
        if (backend == WhisperBackend.VULKAN && !supportsVulkanCompute()) {
            status.text = "Este aparelho não anuncia suporte a Vulkan Compute."
            return
        }


        clearOutput()
        cancelRequested = false
        // W02: nova sessão descarta cancelamentos e callbacks da anterior.
        currentSessionGeneration = whisperSession.beginSession()
        val sessionGeneration = currentSessionGeneration
        // R12/A2: identidade do job no PRODUTOR — o resultado publicado leva
        // job_id/owner/input_id/attempt_id; export e relatório leem daqui
        // (nunca do widget, que pode estar vazio ou ser de outro job).
        val jobId = TranscriptionJobResults.ledger.beginJob(
            LiveEventIdentity.runId, activityInstanceId, sessionGeneration
        )
        isTranscribing = true
        startTranscriptionTimer()
        setBusy(true)
        // R11/D4 — execução INICIADA PELO USUÁRIO e OBSERVÁVEL: o job de arquivo
        // ganha dono de lifecycle (FGS `dataSync` = processamento LOCAL de
        // arquivo + wake lock com teto). Com a tela apagada o processo deixa de
        // ser candidato ao congelador: o job vai a terminal em vez de congelar
        // em "Carregando…".
        val admissaoArquivo = WhisperJobService.startJobOutcome(
            this, sessionGeneration, WhisperJobLifecycle.KIND_FILE, "Preparando arquivos…",
            // R15/B1 — prazo curto só quando o ENSAIO pede (produção = padrão).
            timeoutMs = WhisperLabSeams.leaseTimeoutMs()
                ?: WhisperJobService.DEFAULT_LOCK_TIMEOUT_MS
        )
        // R56/B02 — admissão recusada NÃO pode seguir: a geração não é dona, o
        // FGS não subiu e o terminal seria recusado depois. Antes, o fluxo
        // continuava e deixava a reserva em quarentena.
        if (admissaoArquivo == WhisperJobService.StartOutcome.ADMISSAO_RECUSADA) {
            isTranscribing = false
            stopTranscriptionTimer()
            setBusy(false)
            status.text = "Job recusado: outra geração é dona da reserva."
            return
        }
        val startedAt = SystemClock.elapsedRealtime()
        val liveText = StringBuilder()
        val logLines = StringBuilder()
        val terminalLines = StringBuilder()
        val results = mutableListOf<TranscriptionResult>()

        Thread {
            // R8/A2: marcador `faJobBegin` DESTE job no log nativo — o estado FA
            // lido para cada arquivo só vale para linhas DEPOIS deste ponto.
            whisperSession.faJobBegin("file")
            var sessionDir: File? = null
            var tempWavDir: File? = null
            var modelLoadMs = 0L
            try {
                sessionDir = createSessionDir()
                val perFileDir = File(sessionDir, "Transcricoes").apply { mkdirs() }
                tempWavDir = File(cacheDir, "whisper_wavs_${System.currentTimeMillis()}").apply { mkdirs() }
                appendTerminal(terminalLines, "$ whisper --model ${model.fileName} --language ${selectedLanguage.code} --input ${items.size} arquivo(s)")
                appendTerminal(terminalLines, "output: ${sessionDir.absolutePath}")
                appendTerminal(terminalLines, "backend: ${backend.reportLabel}")
                appendTerminal(terminalLines, "decode: ${settings.describe()}")
                appendTerminal(terminalLines, WhisperNative.buildInfo())
                TranscriptionReport.appendLog(logLines, "Sessão: ${sessionDir.name}")
                TranscriptionReport.appendLog(logLines, "Pasta de saída: ${sessionDir.absolutePath}")
                TranscriptionReport.appendLog(logLines, "Modelo: ${model.label}")
                TranscriptionReport.appendLog(logLines, "Backend: ${backend.reportLabel}")
                TranscriptionReport.appendLog(logLines, "Idioma: ${selectedLanguage.label}")
                TranscriptionReport.appendLog(logLines, "Decode: ${settings.describe()}")
                TranscriptionReport.appendLog(logLines, WhisperNative.buildInfo())
                TranscriptionReport.appendLog(logLines, "CPU: ${cpuName()}")

                postIfCurrent(sessionGeneration) {
                    logToggle.visibility = View.VISIBLE
                    updateGlobalLogText()
                    updateTerminalText(terminalLines)
                }

                TranscriptionReport.appendLog(logLines, "Convertendo arquivos para WAV temporário...")
                val convertedItems = prepareWavFiles(items, tempWavDir, terminalLines, logLines, sessionGeneration)
                checkNotCancelled()
                val totalAudioSeconds = convertedItems.sumOf { it.durationSeconds }
                currentTotalAudioSeconds = totalAudioSeconds

                appendTerminal(terminalLines, "")
                appendTerminal(terminalLines, "loading model: ${modelFile(model).absolutePath}")
                TranscriptionReport.appendLog(logLines, "Carregando modelo...")
                postIfCurrent(sessionGeneration) {
                    setTranscriptionStatus("Carregando modelo...")
                    updateGlobalLogText()
                }
                val modelStartedAt = SystemClock.elapsedRealtime()
                // R19/C1 — RESERVA antes do primeiro passo bloqueante. Se outra
                // geração detém o worker, este job NÃO inicia compute. Antes
                // disto a marca só chegava por startService, depois da carga.
                // R55/B01 — a leitura da decisão é a MESMA do Serviço e do
                // startJob (WhisperJobLifecycle.admission). Antes, o `else`
                // aceitava qualquer decisão nova: uma geração JÁ ENCERRADA
                // (REFUSED_STALE_GENERATION) passava e o job computava sem
                // reserva, disputando o worker com a geração viva.
                val porta = WhisperJobLifecycle.admission(
                    WorkerReservationStore.reserve(sessionGeneration)
                )
                if (!porta.start) {
                    liveEvent(
                        "worker_reserve_refused", sessionGeneration,
                        fields = "\"motivo\":\"${porta.reason}\"," +
                            "\"dono\":${WorkerReservationStore.owner()}"
                    )
                    throw IllegalStateException(
                        "worker indisponível (${porta.reason}; dono=${WorkerReservationStore.owner()}) — job não iniciado"
                    )
                }
                liveEvent("worker_reserved", sessionGeneration)
                runCatching { WhisperLabSeams.setCurrentGen(sessionGeneration) }
                val loaded = synchronized(modelLoadLock) { ensureModelLoaded(model, backend, settings.flashAttention, sessionGeneration) }
                modelLoadMs = SystemClock.elapsedRealtime() - modelStartedAt
                checkNotCancelled()
                if (!loaded.ok) {
                    // R5/A1: diagnóstico CAPTURADO na própria carga (worker) — a
                    // UI/relatório nunca releem lastError global.
                    throw IllegalStateException(loaded.failureText)
                }
                // R19/A4 — GATE DE ABORTO: o backend EFETIVO tem de ser o
                // pedido. Antes disto a chave de cache registrava o PEDIDO

                // ("|1|true|") e um fallback para CPU passava despercebido — a
                // coorte da R18 media CPU achando que era Vulkan.
                run {
                    // R20/A2 — DUAS POLÍTICAS, um só evento:
                    //  * LAB: identidade e coerência são REQUISITOS. Getter
                    //    indisponível/desconhecido não passa; backend ou FA
                    //    divergentes invalidam a célula antes do compute.
                    //  * PRODUTO: fallback tipado continua permitido e é
                    //    registrado — jamais vira "célula inválida".
                    val getterOk = runCatching { WhisperNative.effectiveBackendKind() }.isSuccess
                    val efetivo = runCatching { WhisperNative.effectiveBackendKind() }.getOrDefault(-1)
                    val faGetterOk = runCatching { WhisperNative.effectiveFlashGraph() }.isSuccess
                    val faEfetiva = runCatching { WhisperNative.effectiveFlashGraph() }.getOrNull()
                    val lab = WhisperLabSeams.isLab()
                    liveEvent(
                        "backend_gate", sessionGeneration,
                        fields = "\"pedido_backend\":${backend.nativeKind}," +
                            "\"efetivo_backend\":$efetivo," +
                            "\"pedido_fa\":${settings.flashAttention}," +
                            "\"efetivo_fa\":${faEfetiva ?: -1}," +
                            "\"getter_ok\":$getterOk," +
                            "\"fa_getter_ok\":$faGetterOk," +
                            "\"lab\":$lab"
                    )
                    if (lab) {
                        if (!getterOk || efetivo < 0) {
                            throw IllegalStateException(
                                "backend efetivo desconhecido — célula inválida"
                            )
                        }
                        if (efetivo != backend.nativeKind) {
                            throw IllegalStateException(
                                "backend divergente: pedido=${backend.reportLabel} efetivo=$efetivo — célula inválida"
                            )
                        }
                        if (settings.flashAttention) {
                            if (!faGetterOk || faEfetiva == null) {
                                throw IllegalStateException(
                                    "FA efetiva desconhecida — célula inválida"
                                )
                            }
                            if (!faEfetiva) {
                                throw IllegalStateException(
                                    "FA pedida não executada (grafo sem FA) — célula inválida"
                                )
                            }
                        }
                    } else if (efetivo >= 0 && efetivo != backend.nativeKind) {
                        // Produto: fallback suportado, registrado — não aborta.
                        liveEvent(
                            "backend_fallback_produto", sessionGeneration,
                            fields = "\"pedido_backend\":${backend.nativeKind}," +
                                "\"efetivo_backend\":$efetivo"
                        )
                    }
                }
                appendTerminal(terminalLines, "model loaded in ${formatElapsedCompact(modelLoadMs)}")
                loaded.loadLog.trimEnd()
                    .lineSequence()
                    .filter { it.isNotBlank() }
                    .forEach { appendTerminal(terminalLines, it) }
                // D3/R2: explica o FA efetivo (aplicado ou não + motivo) — a
                // decisão de capacidade é do JNI; aqui só se reporta.
                FlashAttentionNotice.message(settings.flashAttention, backend.nativeKind, loaded.loadLog)?.let {
                    appendTerminal(terminalLines, it)
                    TranscriptionReport.appendLog(logLines, it)
                }
                // R11/E (E1): a recusa de PERFIL OpenCL (quando houver) chega
                // ESTRUTURADA do motor (`SIG_OCL_GUARD`): pedido/efetivo/motivo
                // no terminal e no relatório. A decisão — e a troca para o
                // backend seguro — é do JNI; aqui só se reporta.
                OpenClProfileNotice.message(backend.nativeKind, loaded.loadLog)?.let {
                    appendTerminal(terminalLines, it)
                    TranscriptionReport.appendLog(logLines, it)
                }
                appendTerminal(terminalLines, "native backends:\n${WhisperNative.backendInfo()}")
                TranscriptionReport.appendLog(logLines, "Modelo carregado em ${formatElapsedCompact(modelLoadMs)}")
                loaded.loadLog.trimEnd().takeIf { it.isNotBlank() }?.let {
                    TranscriptionReport.appendLog(logLines, "Log de carregamento do modelo:\n$it")
                }
                updateGlobalLogText()
                TranscriptionReport.appendLog(logLines, "Transcrevendo...")
                updateGlobalLogText()

                convertedItems.forEachIndexed { index, converted ->
                    checkNotCancelled()
                    val item = converted.item
                    val fileNumber = index + 1
                    // R12/A2: tentativa (entrada) DESTE job — segmentos de um
                    // callback atrasado de outro job/tentativa são recusados.
                    // R13/A2: beginAttempt é aquisição atômica — job
                    // substituído/terminal/desconhecido devolve null ANTES de
                    // tocar no estado; a Activity trata a recusa antes do
                    // decode ou de qualquer escrita.
                    val attemptKey = TranscriptionJobResults.ledger.beginAttempt(
                        jobId, sessionGeneration, item.name, LiveEventIdentity.runId, activityInstanceId
                    )
                    if (attemptKey == null) {
                        appendTerminal(terminalLines, "whisper.cpp: stale attempt recusado em ${item.name} (job substituído)")
                        TranscriptionReport.appendLog(logLines, "Tentativa tardia recusada em ${item.name}: job substituído")
                        postIfCurrent(sessionGeneration) {
                            updateTerminalText(terminalLines)
                            updateGlobalLogText()
                        }
                        return@forEachIndexed
                    }
                    TranscriptionReport.appendTranscriptionHeader(liveText, item.name)
                    val fileText = StringBuilder()
                    appendTerminal(terminalLines, "whisper.cpp: begin ${item.name}")
                    postIfCurrent(sessionGeneration) {
                        updateTerminalText(terminalLines)
                    }

                    TranscriptionReport.appendLog(logLines, "Transcrevendo $fileNumber/${convertedItems.size}: ${item.name}")
                    postIfCurrent(sessionGeneration) {
                        setTranscriptionStatus("Transcrevendo $fileNumber/${convertedItems.size}: ${item.name}")
                        updateGlobalLogText()
                    }

                    fun makeCallback(languageLabel: String): WhisperNative.Callback {
                        val lastTerminalUpdateMs = longArrayOf(0L)
                        fun updateTerminalThrottled(force: Boolean = false) {
                            val now = SystemClock.elapsedRealtime()
                            if (force || now - lastTerminalUpdateMs[0] >= 250L) {
                                lastTerminalUpdateMs[0] = now
                                runOnUiThread {
                                    // R2/B2: re-checagem na EXECUÇÃO do post — uma nova
                                    // sessão pode ter começado depois do agendamento.
                                    whisperSession.runIfCurrent(sessionGeneration) {
                                        updateTerminalText(terminalLines)
                                    }
                                }
                            }
                        }
                        return object : WhisperNative.Callback {
                            override fun onSegment(text: String, startMs: Long, endMs: Long) {
                                val segment = text.trim()
                                if (segment.isBlank()) return
                                // R12/A2: o ledger do PRODUTOR recebe o segmento
                                // com a identidade desta tentativa. Segmento de um
                                // job/tentativa antiga é recusado (false) e NÃO
                                // entra no resultado atual.
                                TranscriptionJobResults.ledger.appendSegment(
                                    attemptKey, segment, startMs, endMs)
                                synchronized(liveText) {
                                    fileText.append(segment).append('\n')
                                    liveText.append(segment).append('\n')
                                }
                                runOnUiThread {
                                    whisperSession.runIfCurrent(sessionGeneration) {
                                        if (liveTranscriptContainer.visibility != View.VISIBLE) {
                                            liveTranscriptContainer.visibility = View.VISIBLE
                                            liveTranscriptClipboardActions.visibility = View.VISIBLE
                                        }
                                        val cur = liveTranscriptText.text.toString()
                                        val sep = if (cur.isEmpty() || cur.endsWith("\n") || cur.endsWith(" ")) "" else " "
                                        liveTranscriptText.append(sep + segment)
                                        liveTranscriptText.setSelection(liveTranscriptText.text.length)
                                    }
                                }
                                appendTerminal(terminalLines, "${formatTime(startMs)} --> ${formatTime(endMs)}  $segment")
                                updateTerminalThrottled()
                            }

                            override fun onProgress(progress: Int) {
                                val safeProgress = progress.coerceIn(0, 100)
                                appendTerminal(terminalLines, "whisper.cpp: progress $safeProgress%")
                                runOnUiThread {
                                    whisperSession.runIfCurrent(sessionGeneration) {
                                        setTranscriptionStatus(
                                            "Transcrevendo $fileNumber/${convertedItems.size}: ${item.name} ($languageLabel)... $safeProgress%",
                                            safeProgress
                                        )
                                    }
                                }
                                updateTerminalThrottled()
                            }

                            override fun onNativeLog(line: String) {
                                line.trimEnd().lineSequence()
                                    .filter { it.isNotBlank() }
                                    .forEach { appendTerminal(terminalLines, it) }
                                updateTerminalThrottled(force = true)
                            }
                        }
                    }

                    appendTerminal(terminalLines, "whisper.cpp: language=${selectedLanguage.code}")
                    appendTerminal(terminalLines, "whisper.cpp: build_info")
                    appendTerminal(terminalLines, WhisperNative.buildInfo())
                    appendTerminal(terminalLines, "system_info: ${WhisperNative.systemInfo()}")
                    appendTerminal(terminalLines, "whisper.cpp: calling whisper_full_parallel")
                    postIfCurrent(sessionGeneration) { updateTerminalText(terminalLines) }
                    val transcribeStartedAt = SystemClock.elapsedRealtime()
                    // R18/C1 — início real do worker (caminho de lote/import).
                    runCatching { WhisperJobService.markWorkerStarted(this@WhisperActivity, sessionGeneration) }
                    // R2/B2: texto+status do MESMO job numa única chamada (par
                    // protegido); o callback é envolvido pelo controller.
                    val (returnedRaw, transcribeStatus) = whisperSession.transcribeAndStatus(
                        sessionGeneration,
                        converted.wavFile.absolutePath,
                        selectedLanguage.code,
                        settings.beamSize,
                        settings.bestOf,
                        settings.wordTimestamps,
                        settings.vadFilter,
                        if (settings.vadFilter) vadModelFile().absolutePath else "",
                        makeCallback(selectedLanguage.code)
                    )
                    val returnedText = returnedRaw.trim()
                    appendTerminal(terminalLines, "whisper.cpp: whisper_full_parallel returned in ${formatElapsedCompact(SystemClock.elapsedRealtime() - transcribeStartedAt)}")
                    postIfCurrent(sessionGeneration) { updateTerminalText(terminalLines) }
                    val parsed = WhisperResultParser.parse(returnedText, transcribeStatus)
                    // R8/A2: o aviso de FA ATUALIZA com o estado ESTRUTURADO do
                    // MESMO job (SIG_FA_EXEC deste transcribe) — distingue CARGA
                    // (SIG_FA_STATUS) de EXECUÇÃO e não herda do job anterior.
                    if (settings.flashAttention) {
                        val faNow = runCatching { whisperSession.loadLogForDiagnostics() }.getOrDefault("")
                        FlashAttentionNotice.message(true, backend.nativeKind, faNow)?.let { faMsg ->
                            appendTerminal(terminalLines, "[FA exec] $faMsg")
                            TranscriptionReport.appendLog(logLines, "[FA exec] $faMsg")
                        }
                        FlashAttentionNotice.executionSummary(true, backend.nativeKind, faNow)?.let { execLine ->
                            appendTerminal(terminalLines, "[FA estado] $execLine")
                            TranscriptionReport.appendLog(logLines, "[FA estado] $execLine")
                        }
                        postIfCurrent(sessionGeneration) { updateTerminalText(terminalLines) }
                    }
                    when (parsed) {
                        is WhisperTranscriptResult.Cancelled -> throw CancellationException(parsed.message)
                        is WhisperTranscriptResult.Error -> throw IllegalStateException(parsed.message)
                        is WhisperTranscriptResult.NoSpeech -> {
                            // W10: sem fala não é erro genérico nem sucesso silencioso: pula o
                            // arquivo com a causa explícita no terminal e no log.
                            TranscriptionJobResults.ledger.finishAttempt(
                                attemptKey, JobTerminalState.NO_SPEECH, transcribeStatus
                            )
                            appendTerminal(terminalLines, "sem fala detectada em ${item.name}")
                            TranscriptionReport.appendLog(logLines, "Sem fala detectada em ${item.name}")
                            postIfCurrent(sessionGeneration) {
                                updateGlobalLogText()
                                updateTerminalText(terminalLines)
                            }
                            return@forEachIndexed
                        }
                        is WhisperTranscriptResult.Success -> {
                            checkNotCancelled()
                            if (fileText.isBlank() && parsed.text.isNotBlank()) {
                                TranscriptionJobResults.ledger.appendSegment(attemptKey, parsed.text)
                                synchronized(liveText) {
                                    fileText.append(parsed.text).append('\n')
                                    liveText.append(parsed.text).append('\n')
                                }
                                appendTerminal(terminalLines, parsed.text)
                            }
                            if (fileText.isBlank()) throw IllegalStateException("transcrição vazia em ${item.name}")
                            // R12/A2: fecha a tentativa no MESMO terminal em que o
                            // texto foi aceito (SUCCESS + status nativo "ok").
                            TranscriptionJobResults.ledger.finishAttempt(
                                attemptKey, JobTerminalState.SUCCESS, transcribeStatus
                            )
                        }
                    }

                    TranscriptionReport.appendTranscriptionSeparator(liveText)
                    appendTerminal(terminalLines, "whisper.cpp: end ${item.name}")
                    val text = fileText.toString().trim()
                    val individual = uniqueOutputFile(perFileDir, "${TranscriptionReport.safeBaseName(item.name)}.txt")
                    individual.writeText(text, Charsets.UTF_8)
                    results += TranscriptionResult(item.name, text, individual, converted.durationSeconds)

                    TranscriptionReport.appendLog(logLines, "Concluído: ${item.name}")
                    postIfCurrent(sessionGeneration) {
                        updateGlobalLogText()
                        updateTerminalText(terminalLines)
                    }
                }

                // R12/A2: TERMINAL do job publicado pelo PRODUTOR, com
                // identidade e origem do texto. `job.json` viaja junto do
                // export do produto (transcricoes.txt/html) — quem consome
                // sabe de QUAL job é o texto e se ele é elegível a métrica.
                val jobResult = TranscriptionJobResults.ledger.publishJob(
                    jobId, sessionGeneration, JobTerminalState.SUCCESS, "ok"
                )
                // R14/C2 — rota FA efetiva DESTE job no artefato.
                logFaRoute("file")
                releaseLoadedModel()
                appendTerminal(terminalLines, "model released")
                tempWavDir?.deleteRecursively()
                appendTerminal(terminalLines, "temporary wav files removed")
                val elapsedMs = SystemClock.elapsedRealtime() - startedAt
                val txtFile = File(sessionDir, "transcricoes.txt")
                val htmlFile = File(sessionDir, "transcricoes.html")
                val sessionLogFile = File(sessionDir, "log.txt")
                val sessionTerminalFile = File(sessionDir, "terminal.txt")
                txtFile.writeText(liveText.toString(), Charsets.UTF_8)
                htmlFile.writeText(TranscriptionReport.buildHtml(results.map { TranscriptionReport.Row(it.fileName, it.text) }), Charsets.UTF_8)
                jobResult?.let {
                    File(sessionDir, "job.json").writeText(it.toJsonString(), Charsets.UTF_8)
                    TranscriptionReport.appendLog(
                        logLines,
                        "Resultado: job=${it.key.jobId} attempt=${it.key.attemptId} terminal=${it.terminal.wire} " +
                            "origem=${it.origin.name.lowercase()} wer_eligivel=${it.werEligible}"
                    )
                }

                val report = buildReport(model, backend, items.size, totalAudioSeconds, elapsedMs, modelLoadMs)
                TranscriptionReport.appendLog(logLines, report)
                sessionLogFile.writeText(logLines.toString(), Charsets.UTF_8)
                sessionTerminalFile.writeText(snapshotText(terminalLines), Charsets.UTF_8)
                appendGlobalLog(logLines.toString())

                val outputSession = OutputSession(sessionDir, txtFile, htmlFile, sessionLogFile)
                runOnUiThread {
                    whisperSession.runIfCurrent(sessionGeneration) {
                    lastSession = outputSession
                    tempSessionDir = sessionDir
                    isTranscribing = false
                    cancelRequested = false
                    stopTranscriptionTimer()
                    setBusy(false)
                    
                    outputFileName.visibility = View.GONE
                    
                    outputActions.visibility = View.VISIBLE
                    buttonSaveToFolder.visibility = View.VISIBLE
                    buttonOutputExport.visibility = View.GONE
                    buttonOutputFolder.visibility = View.GONE

                    val fullText = liveText.toString().trim()
                    if (fullText.isNotBlank()) {
                        liveTranscriptText.setText(fullText)
                        liveTranscriptContainer.visibility = View.VISIBLE
                        liveTranscriptClipboardActions.visibility = View.VISIBLE
                    }
                    
                    status.text = "Transcrição concluída com sucesso! Clique no disquete para salvar."
                    updateGlobalLogText()
                    updateTerminalText(terminalLines)
                    whisperScroll.post { whisperScroll.smoothScrollTo(0, outputActions.bottom) }
                    updateTranscribeEnabled()
                    }
                }
                // R11/D4 — TERMINAL do job de arquivo: libera lock/notificação.
                WhisperJobService.finishJob(sessionGeneration, "SUCCESS")
            } catch (e: CancellationException) {
                Log.i(TAG, "Transcription cancelled", e)
                // R12/A2: terminal do PRODUTOR. Sem decode deste job o texto é
                // NONE (export vazio/sem resultado); com fala já decodificada é
                // PARTIAL e continua marcado como parcial — nunca vira o texto
                // do job anterior nem "sucesso" para WER.
                val cancelResult = TranscriptionJobResults.ledger.publishJob(
                    jobId, sessionGeneration, JobTerminalState.CANCELLED, "cancelled"
                )
                val cancelExport = TranscriptionJobResults.ledger.exportOf(cancelResult)
                releaseLoadedModel()
                tempWavDir?.deleteRecursively()
                val elapsedMs = SystemClock.elapsedRealtime() - startedAt
                val cancelReport = buildErrorReport(
                    title = "Transcrição cancelada pelo usuário.",
                    elapsedMs = elapsedMs,
                    modelLoadMs = modelLoadMs,
                    progressValue = currentTranscriptionProgress
                )
                appendTerminal(terminalLines, "CANCELADO: transcrição cancelada pelo usuário")
                appendTerminal(terminalLines, cancelReport)
                TranscriptionReport.appendLog(logLines, cancelReport)
                sessionDir?.let {
                    try {
                        File(it, "log.txt").writeText(logLines.toString(), Charsets.UTF_8)
                        File(it, "terminal.txt").writeText(snapshotText(terminalLines), Charsets.UTF_8)
                        cancelResult?.let { r ->
                            File(it, "job.json").writeText(r.toJsonString(), Charsets.UTF_8)
                        }
                        appendGlobalLog(logLines.toString())
                    } catch (_: Throwable) {
                    }
                }
                runOnUiThread {
                    whisperSession.runIfCurrent(sessionGeneration) {
                    isTranscribing = false
                    cancelRequested = false
                    stopTranscriptionTimer()
                    setBusy(false)
                    // R12/A2: a UI só mostra o que é DESTE job. Com parcial, o
                    // painel exibe o parcial com a identidade do job; sem
                    // parcial, o painel fica vazio (nada herdado do job anterior).
                    if (!cancelExport.empty) {
                        liveTranscriptText.setText(cancelExport.text)
                        liveTranscriptContainer.visibility = View.VISIBLE
                        liveTranscriptClipboardActions.visibility = View.VISIBLE
                        status.text = cancelReport + "\nPARCIAL do job " +
                            "${cancelResult?.key?.shortForm} — sem WER"
                    } else {
                        liveTranscriptText.setText("")
                        liveTranscriptContainer.visibility = View.GONE
                        liveTranscriptClipboardActions.visibility = View.GONE
                        status.text = cancelReport + "\nSEM resultado do job atual (nada decodificado)"
                    }
                    updateGlobalLogText()
                    updateTerminalText(terminalLines)
                    logToggle.visibility = View.VISIBLE
                    updateTranscribeEnabled()
                    }
                }
                // R11/D4 — TERMINAL tipado (cancel): libera lock/notificação.
                WhisperJobService.finishJob(sessionGeneration, "CANCELLED")
            } catch (e: Throwable) {
                Log.e(TAG, "Transcription failed", e)
                val errorResult = TranscriptionJobResults.ledger.publishJob(
                    jobId, sessionGeneration, JobTerminalState.ERROR, "error"
                )
                val errorExport = TranscriptionJobResults.ledger.exportOf(errorResult)
                releaseLoadedModel()
                tempWavDir?.deleteRecursively()
                val elapsedMs = SystemClock.elapsedRealtime() - startedAt
                val errorMessage = e.message ?: "falha inesperada"
                val errorReport = buildErrorReport(
                    title = "Erro: $errorMessage",
                    elapsedMs = elapsedMs,
                    modelLoadMs = modelLoadMs,
                    progressValue = currentTranscriptionProgress
                )
                appendTerminal(terminalLines, "ERROR: $errorMessage")
                appendTerminal(terminalLines, errorReport)
                TranscriptionReport.appendLog(logLines, errorReport)
                sessionDir?.let {
                    try {
                        File(it, "log.txt").writeText(logLines.toString(), Charsets.UTF_8)
                        File(it, "terminal.txt").writeText(snapshotText(terminalLines), Charsets.UTF_8)
                        errorResult?.let { r ->
                            File(it, "job.json").writeText(r.toJsonString(), Charsets.UTF_8)
                        }
                        appendGlobalLog(logLines.toString())
                    } catch (_: Throwable) {
                    }
                }
                runOnUiThread {
                    whisperSession.runIfCurrent(sessionGeneration) {
                    isTranscribing = false
                    cancelRequested = false
                    stopTranscriptionTimer()
                    setBusy(false)
                    // R12/A2: erro também não herda texto: só o parcial DESTE
                    // job (marcado) ou painel vazio.
                    if (!errorExport.empty) {
                        liveTranscriptText.setText(errorExport.text)
                        liveTranscriptContainer.visibility = View.VISIBLE
                        liveTranscriptClipboardActions.visibility = View.VISIBLE
                        status.text = errorReport + "\nPARCIAL do job " +
                            "${errorResult?.key?.shortForm} — sem WER"
                    } else {
                        liveTranscriptText.setText("")
                        liveTranscriptContainer.visibility = View.GONE
                        liveTranscriptClipboardActions.visibility = View.GONE
                        status.text = errorReport + "\nSEM resultado do job atual (nada decodificado)"
                    }
                    updateGlobalLogText()
                    updateTerminalText(terminalLines)
                    logToggle.visibility = View.VISIBLE
                    updateTranscribeEnabled()
                    }
                }
                // R11/D4 — TERMINAL tipado (erro): libera lock/notificação.
                WhisperJobService.finishJob(sessionGeneration, "ERROR")
            }
        }.start()
    }

    private fun convertToWav(inputFile: File, wavFile: File, originalName: String, terminalLines: StringBuilder, sessionGeneration: Long) {
        val arguments = arrayOf(
            "-y",
            "-i", inputFile.absolutePath,
            "-vn",
            "-ar", "16000",
            "-ac", "1",
            "-c:a", "pcm_s16le",
            "-f", "wav",
            wavFile.absolutePath
        )
        appendTerminal(terminalLines, "# original: $originalName")
        appendTerminal(terminalLines, "ffmpeg ${arguments.joinToString(" ")}")
        postIfCurrent(sessionGeneration) { updateTerminalText(terminalLines) }
        val convertSession = executeFfmpegWithTerminal(arguments, terminalLines, sessionGeneration)
        if (!ReturnCode.isSuccess(convertSession.returnCode) || !wavFile.exists() || wavFile.length() == 0L) {
            val logTail = convertSession.allLogsAsString.orEmpty().lines().takeLast(2).joinToString(" ")
            throw IllegalStateException("falha ao converter áudio. ${logTail.take(90)}")
        }
    }

    private fun executeFfmpegWithTerminal(arguments: Array<String>, terminalLines: StringBuilder, sessionGeneration: Long): FFmpegSession {
        val latch = CountDownLatch(1)
        val sessionRef = AtomicReference<FFmpegSession>()
        FFmpegKit.executeWithArgumentsAsync(
            arguments,
            { session ->
                sessionRef.set(session)
                latch.countDown()
            },
            { log ->
                appendTerminal(terminalLines, log.message.orEmpty().trimEnd())
                postIfCurrent(sessionGeneration) { updateTerminalText(terminalLines) }
            },
            { statistics ->
                appendTerminal(terminalLines, "ffmpeg stats: time=${statistics.time} size=${statistics.size} bitrate=${statistics.bitrate} speed=${statistics.speed}")
                postIfCurrent(sessionGeneration) { updateTerminalText(terminalLines) }
            }
        )
        latch.await()
        return sessionRef.get()
    }

    private fun prepareWavFiles(
        items: List<MediaItem>,
        tempWavDir: File,
        terminalLines: StringBuilder,
        logLines: StringBuilder,
        sessionGeneration: Long
    ): List<ConvertedMediaItem> {
        val copiedInputs = items.mapIndexed { index, item ->
            val fileNumber = index + 1
            appendTerminal(terminalLines, "")
            appendTerminal(terminalLines, "prepare input[$fileNumber/${items.size}]: ${item.name}")
            TranscriptionReport.appendLog(logLines, "Preparando $fileNumber/${items.size}: ${item.name}")
            postIfCurrent(sessionGeneration) {
                status.text = "Preparando $fileNumber/${items.size}: ${item.name}"
                updateGlobalLogText()
                updateTerminalText(terminalLines)
            }

            val copyStartedAt = SystemClock.elapsedRealtime()
            val inputFile = copyUriToCache(item.uri, item.name)
            val wavFile = File(tempWavDir, "${index + 1}_${TranscriptionReport.safeBaseName(item.name)}.wav")
            val inputWavInfo = readWavInfo(inputFile)
            appendTerminal(terminalLines, "copy input done in ${formatElapsedCompact(SystemClock.elapsedRealtime() - copyStartedAt)}")
            postIfCurrent(sessionGeneration) { updateTerminalText(terminalLines) }
            PreparedInput(index, item, inputFile, wavFile, inputWavInfo)
        }

        val needsFfmpeg = copiedInputs.count { it.wavInfo?.isWhisperReady != true }
        val parallelism = if (needsFfmpeg > 1) {
            (Runtime.getRuntime().availableProcessors() - 1).coerceAtLeast(1).coerceAtMost(needsFfmpeg)
        } else {
            1
        }
        appendTerminal(
            terminalLines,
            if (needsFfmpeg > 1) {
                "ffmpeg parallel conversions: $needsFfmpeg arquivo(s), $parallelism worker(s)"
            } else {
                "ffmpeg parallel conversions: not needed"
            }
        )
        TranscriptionReport.appendLog(logLines, "Conversões FFmpeg necessárias: $needsFfmpeg; paralelismo: $parallelism")
        postIfCurrent(sessionGeneration) {
            status.text = if (needsFfmpeg > 1) {
                "Convertendo $needsFfmpeg arquivos em paralelo..."
            } else {
                "Preparando áudio..."
            }
            updateGlobalLogText()
            updateTerminalText(terminalLines)
        }

        val executor = Executors.newFixedThreadPool(parallelism)
        return try {
            val futures = copiedInputs.map { prepared ->
                executor.submit<ConvertedMediaItem> {
                    prepareSingleWav(prepared, terminalLines, logLines, sessionGeneration)
                }
            }
            futures.map { it.get() }.sortedBy { it.index }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun prepareSingleWav(
        prepared: PreparedInput,
        terminalLines: StringBuilder,
        logLines: StringBuilder,
        sessionGeneration: Long
    ): ConvertedMediaItem {
        val prepareStartedAt = SystemClock.elapsedRealtime()
        try {
            if (prepared.wavInfo?.isWhisperReady == true) {
                appendTerminal(terminalLines, "[${prepared.item.name}] ffmpeg skipped: input already WAV PCM s16le mono 16000 Hz")
                prepared.inputFile.copyTo(prepared.wavFile, overwrite = true)
                TranscriptionReport.appendLog(logLines, "FFmpeg dispensado: ${prepared.item.name} já está em WAV 16 kHz mono.")
            } else {
                convertToWav(prepared.inputFile, prepared.wavFile, prepared.item.name, terminalLines, sessionGeneration)
            }
            appendTerminal(terminalLines, "[${prepared.item.name}] wav preparation done in ${formatElapsedCompact(SystemClock.elapsedRealtime() - prepareStartedAt)}")
            val durationStartedAt = SystemClock.elapsedRealtime()
            val duration = wavDurationSeconds(prepared.wavFile)
            appendTerminal(terminalLines, "[${prepared.item.name}] wav duration read in ${formatElapsedCompact(SystemClock.elapsedRealtime() - durationStartedAt)} (${formatSeconds(duration)})")
            TranscriptionReport.appendLog(logLines, "WAV temporário pronto: ${prepared.item.name}")
            postIfCurrent(sessionGeneration) {
                updateGlobalLogText()
                updateTerminalText(terminalLines)
            }
            return ConvertedMediaItem(prepared.index, prepared.item, prepared.wavFile, duration)
        } finally {
            prepared.inputFile.delete()
        }
    }

    private fun copyUriToCache(uri: Uri, displayName: String): File {
        val extension = displayName.substringAfterLast('.', "tmp").ifBlank { "tmp" }
        val inputFile = File(cacheDir, "whisper_input_${System.currentTimeMillis()}.$extension")
        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(inputFile).use { output -> input.copyTo(output) }
        } ?: throw IllegalStateException("não consegui abrir o arquivo selecionado")
        return inputFile
    }

    private fun updateGlobalLogText() {
        if (logText.visibility == View.VISIBLE) {
            // R9/B2: leitura do arquivo e layout do texto inteiro passam a
            // ocorrer na publicação coalescida, não a cada chamada.
            globalLogPublisher.request { readGlobalLog() }
        }
    }

    private fun appendTerminal(builder: StringBuilder, line: String) {
        synchronized(builder) {
            builder.append(line).append('\n')
            val lines = builder.lines()
            if (lines.size > 1200) {
                builder.clear()
                builder.append(lines.takeLast(1000).joinToString("\n")).append('\n')
            }
        }
    }

    private fun snapshotText(builder: StringBuilder): String {
        return synchronized(builder) { builder.toString() }
    }

    private fun updateTerminalText(builder: StringBuilder) {
        // R9/B2: o histórico inteiro é reconstruído/medido no máximo uma vez por
        // janela de publicação (antes: a cada linha anexada).
        terminalPublisher.request {
            snapshotText(builder).ifBlank { "$ whisper --aguardando arquivo" }
        }
    }

    /** R9/B2: rolagem do terminal para o fim após a publicação coalescida. */
    private fun scrollTerminalToBottom() {
        val layout = terminalText.layout ?: return
        val scrollAmount = layout.getLineTop(terminalText.lineCount) - terminalText.height +
            terminalText.totalPaddingTop + terminalText.totalPaddingBottom
        terminalText.scrollTo(0, scrollAmount.coerceAtLeast(0))
    }

    private fun toggleAdvancedSettings() {
        advancedPanel.visibility = if (advancedPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    private fun resetAdvancedSettings() {
        checkboxVad.isChecked = true
        checkboxFlashAttention.isChecked = false
        checkboxWordTimestamps.isChecked = false
        inputBeamSize.setText(DEFAULT_BEAM_SIZE.toString())
        inputBestOf.setText(DEFAULT_BEST_OF.toString())
    }

    private fun setAdvancedEnabled(enabled: Boolean) {
        checkboxVad.isEnabled = enabled
        checkboxFlashAttention.isEnabled = enabled
        checkboxWordTimestamps.isEnabled = enabled
        inputBeamSize.isEnabled = enabled
        inputBestOf.isEnabled = enabled
        findViewById<View>(R.id.btn_beam_size_dec).isEnabled = enabled
        findViewById<View>(R.id.btn_beam_size_inc).isEnabled = enabled
        findViewById<View>(R.id.btn_best_of_dec).isEnabled = enabled
        findViewById<View>(R.id.btn_best_of_inc).isEnabled = enabled
        resetAdvanced.isEnabled = enabled
        advancedPanel.alpha = if (enabled) 1f else 0.55f
    }

    private fun readAdvancedSettings(): WhisperAdvancedSettings {
        val beam = inputBeamSize.text.toString().toIntOrNull()?.coerceIn(1, 16) ?: DEFAULT_BEAM_SIZE
        val bestOf = inputBestOf.text.toString().toIntOrNull()?.coerceIn(1, 16) ?: DEFAULT_BEST_OF
        inputBeamSize.setText(beam.toString())
        inputBestOf.setText(bestOf.toString())
        return WhisperAdvancedSettings(
            vadFilter = checkboxVad.isChecked,
            flashAttention = checkboxFlashAttention.isChecked,
            wordTimestamps = checkboxWordTimestamps.isChecked,
            beamSize = beam,
            bestOf = bestOf
        )
    }

    private fun createSessionDir(): File {
        val root = whisperOutputRoot()
        val stamp = SimpleDateFormat("yyyy-MM-dd 'as' HH'h'mm", Locale.US).format(Date())
        var candidate = File(root, stamp)
        var suffix = 2
        while (candidate.exists()) {
            candidate = File(root, "${stamp}_$suffix")
            suffix++
        }
        if (!candidate.mkdirs()) throw IllegalStateException("não consegui criar a pasta da sessão")
        return candidate
    }

    private fun whisperOutputRoot(): File {
        val publicRoot = File(
            File(Environment.getExternalStorageDirectory(), SIG_OUTPUT_FOLDER),
            WHISPER_OUTPUT_FOLDER
        )
        val appSpecificRoot = File(
            getExternalFilesDir(null) ?: filesDir,
            WHISPER_OUTPUT_FOLDER
        )
        val preferredRoot = SttOutputStorage.chooseRoot(
            publicRoot = publicRoot,
            appSpecificRoot = appSpecificRoot,
            publicStorageAvailable = hasPublicStorageAccess()
        )
        val candidates = if (preferredRoot == publicRoot) {
            listOf(publicRoot, appSpecificRoot)
        } else {
            listOf(appSpecificRoot)
        }
        return candidates.firstOrNull { SttOutputStorage.ensureDirectory(it) }
            ?: throw IllegalStateException("não consegui preparar o armazenamento da sessão")
    }

    private fun hasPublicStorageAccess(): Boolean {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> Environment.isExternalStorageManager()
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ->
                checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            else -> true
        }
    }

    private fun appendGlobalLog(text: String) {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val file = globalLogFile()
        val previous = if (file.exists()) file.readText(Charsets.UTF_8) else ""
        val currentSession =
            "============================================================\n" +
                "SESSÃO WHISPER - $stamp\n" +
                "============================================================\n" +
                text.trimEnd() +
                "\n\n"
        file.writeText(currentSession + previous.trimStart(), Charsets.UTF_8)
    }

    private fun readGlobalLog(): String {
        val file = runCatching { globalLogFile() }.getOrNull()
        val body = runCatching {
            if (file != null && file.exists() && file.length() > 0L) {
                file.readText(Charsets.UTF_8)
            } else {
                "Sem log geral ainda."
            }
        }.getOrElse { error ->
            Log.w(TAG, "Não foi possível ler o log global do Whisper", error)
            "Sem log geral ainda."
        }
        val sessionCount = "SESSÃO WHISPER -".toRegex().findAll(body).count()
        val location = file?.absolutePath ?: "SIG/Whisper/$GLOBAL_LOG_NAME"
        return "LOG GLOBAL - $location\nSessões registradas: $sessionCount\nMais recentes primeiro; role para ver sessões anteriores.\n\n$body"
    }

    private fun globalLogFile(): File {
        return File(whisperOutputRoot(), GLOBAL_LOG_NAME)
    }

    private fun buildReport(
        model: WhisperModel,
        backend: WhisperBackend,
        fileCount: Int,
        totalAudioSeconds: Double,
        elapsedMs: Long,
        modelLoadMs: Long
    ): String {
        val elapsedSeconds = (elapsedMs / 1000.0).coerceAtLeast(0.001)
        val efficiency = totalAudioSeconds / elapsedSeconds
        return listOf(
            "Modelo: ${model.label} (${backend.reportLabel})",
            "Arquivos: $fileCount",
            "Total de áudio processado: ${formatSeconds(totalAudioSeconds)}",
            "Tempo de carregamento do modelo: ${formatElapsedCompact(modelLoadMs)}",
            "Tempo de processamento: ${formatSeconds(elapsedSeconds)}",
            "Eficiência: ${String.format(Locale.US, "%.2fx", efficiency)}"
        ).joinToString("\n")
    }

    private fun buildErrorReport(
        title: String,
        elapsedMs: Long,
        modelLoadMs: Long,
        progressValue: Int
    ): String {
        val lines = mutableListOf(
            title,
            "Tempo decorrido: ${formatElapsedCompact(elapsedMs)}",
            "Progresso: ${progressValue.coerceIn(0, 100)}%"
        )
        if (modelLoadMs > 0L) {
            lines += "Tempo de carregamento do modelo: ${formatElapsedCompact(modelLoadMs)}"
        }
        return lines.joinToString("\n")
    }

    private fun wavDurationSeconds(wavFile: File): Double {
        return readWavInfo(wavFile)?.durationSeconds ?: 0.0
    }

    private fun readWavInfo(wavFile: File): WavInfo? {
        RandomAccessFile(wavFile, "r").use { file ->
            val riff = ByteArray(4)
            file.readFully(riff)
            file.skipBytes(4)
            val wave = ByteArray(4)
            file.readFully(wave)
            if (String(riff) != "RIFF" || String(wave) != "WAVE") return null
            var audioFormat = 0
            var channels = 0
            var sampleRate = 0
            var bitsPerSample = 0
            var dataSize = 0
            while (file.filePointer < file.length()) {
                val chunk = ByteArray(4)
                file.readFully(chunk)
                val size = file.readLittleInt()
                when (String(chunk)) {
                    "fmt " -> {
                        audioFormat = file.readLittleShort()
                        channels = file.readLittleShort()
                        sampleRate = file.readLittleInt()
                        file.skipBytes(6)
                        bitsPerSample = file.readLittleShort()
                        val remaining = size - 16
                        if (remaining > 0) file.skipBytes(remaining)
                    }
                    "data" -> {
                        dataSize = size
                        file.seek(file.filePointer + size + (size % 2))
                    }
                    else -> file.seek(file.filePointer + size + (size % 2))
                }
            }
            val bytesPerSecond = sampleRate.toDouble() * channels.toDouble() * (bitsPerSample.toDouble() / 8.0)
            val duration = if (bytesPerSecond > 0.0) dataSize.toDouble() / bytesPerSecond else 0.0
            return WavInfo(audioFormat, channels, sampleRate, bitsPerSample, dataSize, duration)
        }
    }

    private fun RandomAccessFile.readLittleShort(): Int {
        val b1 = read()
        val b2 = read()
        if (b2 < 0) return 0
        return (b1 and 0xff) or ((b2 and 0xff) shl 8)
    }

    private fun RandomAccessFile.readLittleInt(): Int {
        val b1 = read()
        val b2 = read()
        val b3 = read()
        val b4 = read()
        if (b4 < 0) return 0
        return (b1 and 0xff) or ((b2 and 0xff) shl 8) or ((b3 and 0xff) shl 16) or ((b4 and 0xff) shl 24)
    }

    private fun uniqueOutputFile(outputDir: File, outputName: String): File {
        val base = outputName.substringBeforeLast('.', outputName)
        val extension = outputName.substringAfterLast('.', "")
        var candidate = File(outputDir, outputName)
        var suffix = 2
        while (candidate.exists()) {
            candidate = File(outputDir, "${base}_$suffix.$extension")
            suffix++
        }
        return candidate
    }

    private fun modelFile(model: WhisperModel): File {
        return model.file
    }

    private fun modelsDir(): File {
        return getExternalFilesDir("whisper_models")
            ?: File(filesDir, "whisper_models")
    }

    private fun officialModels(): List<WhisperModel> {
        val dir = modelsDir().apply { mkdirs() }
        // Tamanhos medidos no R2 em 25/09/2026 (HEAD): o v3Turbo são 1,6 GB e o
        // diálogo precisa dizer isso antes de o usuário aceitar.
        return listOf(
            WhisperModel(
                "tiny",
                "ggml-tiny.bin",
                File(dir, "ggml-tiny.bin"),
                "$MODEL_BASE_URL/ggml-tiny.bin",
                77_691_713L
            ),
            WhisperModel(
                "base",
                "ggml-base.bin",
                File(dir, "ggml-base.bin"),
                "$MODEL_BASE_URL/ggml-base.bin",
                147_951_465L
            ),
            WhisperModel(
                "small",
                "ggml-small.bin",
                File(dir, "ggml-small.bin"),
                "$MODEL_BASE_URL/ggml-small.bin",
                487_601_967L
            ),
            WhisperModel(
                "v3Turbo",
                "ggml-large-v3-turbo.bin",
                File(dir, "ggml-large-v3-turbo.bin"),
                "$MODEL_BASE_URL/ggml-large-v3-turbo.bin",
                1_624_555_275L
            )
        )
    }

    private fun vadModelFile(): File {
        val bundled = NativeDependencyManager.sileroModelFile(this)
        return if (bundled.isFile) bundled else File(File(modelsDir(), "silero"), VAD_MODEL_NAME)
    }

    private fun confirmVadDownload(onReady: () -> Unit) {
        // O VAD é pequeno (885 KB) e o diálogo não dizia nada — sem o número o
        // usuário não sabe se vale a pena aceitar.
        val destino = vadModelFile()
        DownloadPlanDialog.confirmar(
            activity = this,
            plano = DownloadSizeFormat.Plano(
                rotulo = "VAD Silero",
                arquivos = listOf(
                    DownloadSizeFormat.Arquivo(VAD_MODEL_NAME, VAD_MODEL_BYTES, jaBaixado = DownloadFileStorage.isComplete(destino, VAD_MODEL_BYTES))
                ),
            ),
            negativo = "Não",
            positivo = "Sim",
            prefacio = "O VAD filter usa o modelo Silero.",
            sufixo = "Baixar agora?",
            aoNegar = {
                checkboxVad.isChecked = false
                status.text = "VAD desativado. Toque em transcrever novamente."
            },
            aoConfirmar = {
                downloadFile(
                    label = "VAD Silero",
                    urls = listOf(VAD_MODEL_URL, VAD_MODEL_URL_ALTERNATIVA),
                    destination = destino,
                    onSuccess = onReady
                )
            },
        )
    }

    /** Baixa [urls] em ordem (primaria = R2; alternativa = HuggingFace) para
     *  [destination]. Usado pelo VAD Silero: se a fonte primaria estiver
     *  indisponivel, a alternativa assume sem intervencao do usuario. */
    private fun downloadFile(label: String, urls: List<String>, destination: File, onSuccess: () -> Unit) {
        if (DownloadFileStorage.isComplete(destination, VAD_MODEL_BYTES)) { onSuccess(); return }
        val plano = DownloadSizeFormat.Plano(label, listOf(DownloadSizeFormat.Arquivo(destination.name, VAD_MODEL_BYTES)))
        val progresso = DownloadPlanDialog.progresso(this, plano, titulo = "Baixando $label")
        Thread {
            var ultimaFalha: Throwable? = null
            var sucesso = false
            for (url in urls) {
                try {
                    baixarUmaFonte(url, destination) { downloaded, total ->
                        runOnUiThread { progresso.atualizarPacote(downloaded, total, "Baixando $label", true) }
                    }
                    sucesso = true
                    break
                } catch (e: Throwable) {
                    ultimaFalha = e
                    Log.w(TAG, "Fonte indisponivel ($url): ${e.message ?: "falha"}")
                }
            }
            if (sucesso) {
                runOnUiThread {
                    progresso.concluirContinuando(destination.length())
                    status.text = "$label pronto."
                    onSuccess()
                }
            } else {
                val erro = ultimaFalha
                Log.e(TAG, "Download failed", erro)
                runOnUiThread {
                    progresso.fechar()
                    status.text = "Erro ao baixar $label: ${erro?.message ?: "falha inesperada"}"
                }
            }
        }.start()
    }

    /** Baixa UMA fonte para [destination] (via temporario .download). Lanca
     *  excecao em falha — o chamador decide se tenta a proxima fonte. */
    private fun baixarUmaFonte(url: String, destination: File, onProgress: (Long, Long) -> Unit) {
        destination.parentFile?.mkdirs()
        val temp = File(destination.parentFile, "${destination.name}.download")
        URL(url).openConnection().apply {
            connectTimeout = 15000
            readTimeout = 30000
        }.let { connection ->
            val total = connection.contentLengthLong.takeIf { it > 0L } ?: VAD_MODEL_BYTES
            connection.getInputStream().use { input ->
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var copied = 0L
                    var lastUi = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastUi > 500L) {
                            lastUi = now
                            onProgress(copied, total)
                        }
                    }
                }
            }
        }
        DownloadFileStorage.activate(temp, destination, VAD_MODEL_BYTES)
    }

    /**
     * Tamanho conhecido do modelo, para a lista do diálogo.
     *
     * Usa a tabela do catálogo ([WhisperModel.bytes], medida no R2) e cai no
     * arquivo local quando o modelo já está no aparelho. Sem nenhum dos dois
     * (modelo escolhido pelo seletor, nunca baixado) devolve 0 — a lista mostra
     * o item sem tamanho em vez de inventar um número.
     */
    private fun bytesConhecidos(url: String, destination: File): Long {
        officialModels().firstOrNull { it.downloadUrl == url }?.bytes?.takeIf { it > 0L }?.let { return it }
        return destination.takeIf { it.isFile }?.length() ?: 0L
    }

    private fun downloadModelWithProgress(label: String, url: String, destination: File, onSuccess: () -> Unit) {
        val expectedBytes = bytesConhecidos(url, destination)
        if (DownloadFileStorage.isComplete(destination, expectedBytes)) { onSuccess(); return }
        val plano = DownloadSizeFormat.Plano(
            rotulo = "Modelo Whisper $label",
            arquivos = listOf(
                DownloadSizeFormat.Arquivo(destination.name, expectedBytes)
            ),
        )
        val progresso = DownloadPlanDialog.progresso(this, plano, titulo = "Baixando modelo $label")
        Thread {
            try {
                destination.parentFile?.mkdirs()
                val temp = File(destination.parentFile, "${destination.name}.download")
                // Declarado fora do `let` para o bloco de sucesso ter o total.
                var total = 0L
                URL(url).openConnection().apply {
                    connectTimeout = 15000
                    readTimeout = 30000
                }.let { connection ->
                    total = connection.contentLengthLong.takeIf { it > 0L } ?: expectedBytes
                    connection.getInputStream().use { input ->
                        FileOutputStream(temp).use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var copied = 0L
                            var lastUi = 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                copied += read
                                val now = SystemClock.elapsedRealtime()
                                if (now - lastUi > 300L) {
                                    lastUi = now
                                    val percent = if (total > 0L) (copied * 100L / total).coerceIn(0L, 100L) else -1L
                                    val downloaded = copied
                                    runOnUiThread {
                                        progresso.atualizar(downloaded, total, percent.toInt())
                                    }
                                }
                            }
                        }
                    }
                }
                DownloadFileStorage.activate(temp, destination, expectedBytes.takeIf { it > 0L } ?: total)
                runOnUiThread {
                    progresso.atualizar(
                        if (total > 0L) total else destination.length(),
                        if (total > 0L) total else destination.length(),
                        100,
                        plano.arquivos.map { it.nome }.toSet(),
                    )
                    progresso.concluirContinuando(if (total > 0L) total else destination.length())
                    status.text = "$label pronto."
                    onSuccess()
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Download failed", e)
                runOnUiThread {
                    progresso.fechar()
                    status.text = "Erro ao baixar $label: ${e.message ?: "falha inesperada"}"
                }
            }
        }.start()
    }

    private fun openOutputFile(file: File?, mimeType: String) {
        if (file == null) return
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "Não encontrei um app para abrir o arquivo.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showExportMenu() {
        val session = lastSession ?: return
        PopupMenu(this, buttonOutputExport).apply {
            menu.add("txt")
            menu.add("html")
            setOnMenuItemClickListener { item ->
                when (item.title.toString()) {
                    "txt" -> shareFile(session.txtFile, "text/plain", "Compartilhar TXT")
                    "html" -> shareFile(session.htmlFile, "text/html", "Compartilhar HTML")
                }
                true
            }
            show()
        }
    }

    private fun shareFile(file: File, mimeType: String, title: String) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, title))
    }

    private fun saveTempOutputsToUri(treeUri: Uri) {
        val destDir = DocumentFile.fromTreeUri(this, treeUri)
        if (destDir == null || !destDir.isDirectory) {
            status.text = "Erro: pasta de destino inválida."
            return
        }

        val sessionDir = tempSessionDir ?: return
        val sessionName = sessionDir.name

        try {
            val sessionDestDir = destDir.createDirectory(sessionName)
            if (sessionDestDir == null) {
                status.text = "Erro ao criar pasta da sessão."
                return
            }

            var savedCount = 0

            // Copy main files
            val txtFile = File(sessionDir, "transcricoes.txt")
            val htmlFile = File(sessionDir, "transcricoes.html")
            val logFile = File(sessionDir, "log.txt")
            val terminalFile = File(sessionDir, "terminal.txt")

            val destTxt = copyFileToDocument(txtFile, "text/plain", sessionDestDir, "transcricoes.txt")
            if (destTxt != null) savedCount++
            val destHtml = copyFileToDocument(htmlFile, "text/html", sessionDestDir, "transcricoes.html")
            if (destHtml != null) savedCount++
            copyFileToDocument(logFile, "text/plain", sessionDestDir, "log.txt")
            copyFileToDocument(terminalFile, "text/plain", sessionDestDir, "terminal.txt")
            // R12/A2: o payload estruturado (job/attempt/terminal/origem/WER
            // elegível) viaja com o export — o texto exportado tem identidade.
            val jobFile = File(sessionDir, "job.json")
            copyFileToDocument(jobFile, "application/json", sessionDestDir, "job.json")

            // Copy Transcricoes subfolder
            val perFileDir = File(sessionDir, "Transcricoes")
            if (perFileDir.exists() && perFileDir.isDirectory) {
                val perFileDestDir = sessionDestDir.createDirectory("Transcricoes")
                if (perFileDestDir != null) {
                    perFileDir.listFiles()?.forEach { file ->
                        if (file.isFile) {
                            copyFileToDocument(file, "text/plain", perFileDestDir, file.name)
                        }
                    }
                }
            }

            if (savedCount > 0) {
                hasSaved = true
                finalOutputDirUri = treeUri

                val folderName = destDir.name ?: "Pasta selecionada"
                status.text = "Arquivo(s) salvo(s) na pasta \"$folderName\""
                
                outputFileName.text = txtFile.name
                outputFileName.visibility = View.VISIBLE

                buttonSaveToFolder.visibility = View.GONE
                buttonOutputFolder.visibility = View.VISIBLE
                buttonOutputExport.visibility = View.VISIBLE

                whisperScroll.post { whisperScroll.smoothScrollTo(0, outputActions.bottom) }
            } else {
                status.text = "Erro ao salvar os arquivos na pasta selecionada."
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save session to SAF", e)
            status.text = "Erro ao salvar: ${e.message}"
        }
    }

    private fun copyFileToDocument(sourceFile: File, mimeType: String, destParent: DocumentFile, destName: String): DocumentFile? {
        if (!sourceFile.exists()) return null
        try {
            val document = destParent.createFile(mimeType, destName)
            if (document != null) {
                contentResolver.openOutputStream(document.uri)?.use { output ->
                    sourceFile.inputStream().use { input ->
                        input.copyTo(output)
                    }
                }
                return document
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy file ${sourceFile.name} to SAF document", e)
        }
        return null
    }

    private fun openOutputFolder() {
        val uri = finalOutputDirUri ?: return
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "vnd.android.document/directory")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            val dlIntent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)
            try {
                startActivity(dlIntent)
            } catch (_: Exception) {
                Toast.makeText(this, "Não consegui abrir a pasta.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun clearOutput() {
        lastSession = null
        tempSessionDir = null
        hasSaved = false
        outputFileName.visibility = View.GONE
        outputActions.visibility = View.GONE
        logText.text = readGlobalLog()
        logToggle.visibility = View.VISIBLE
        terminalText.text = "$ whisper --aguardando arquivo"
        // R12/A2: o painel de transcrição é do JOB ATUAL. Sem esta limpeza um
        // job cancelado/errado deixava na tela o texto do job anterior, sem
        // identidade, copiável/exportável como se fosse o resultado deste job
        // (foi o que o instrumento P4 da R11 exportou).
        liveTranscriptText.setText("")
        liveTranscriptContainer.visibility = View.GONE
        liveTranscriptClipboardActions.visibility = View.GONE
    }

    private fun updateTranscribeEnabled() {
        val hasMedia = selectedItems.isNotEmpty()
        buttonTranscribe.visibility = if (hasMedia) View.VISIBLE else View.GONE
        // Com ORT o motor da rota não usa GGML: exigir o modelo do CPP deixava o
        // botão desabilitado mesmo havendo pacote ORT válido e entrada escolhida.
        val temModeloCpp = selectedModel?.let { DownloadFileStorage.isComplete(modelFile(it), it.bytes) } == true
        val temMotorOrt = WhisperOrtRoute.isEnabled(this)
        val enabled = !isBusy && hasMedia && (temModeloCpp || temMotorOrt) && !whiteRecordingActive && !liveMicActive
        buttonTranscribe.alpha = if (enabled) 1f else 0.45f
        buttonTranscribe.isClickable = enabled
        buttonTranscribe.isFocusable = enabled
        // O botão ORT abre o próprio seletor, então não depende de mídia prévia:
        // aparece sempre que a rota está ligada e respeita o mesmo estado de ocupado.
        buttonTranscribeOrt?.let { botao ->
            botao.visibility = if (WhisperOrtRoute.isEnabled(this)) View.VISIBLE else View.GONE
            val podeClicar = !isBusy && !whiteRecordingActive && !liveMicActive
            botao.alpha = if (podeClicar) 1f else 0.45f
            botao.isClickable = podeClicar
            botao.isFocusable = podeClicar
        }
    }

    private fun setBusy(busy: Boolean) {
        isBusy = busy
        buttonTranscribeOrt?.isEnabled = !busy
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        buttonModel.isEnabled = !busy
        buttonModel.alpha = if (busy) 0.45f else 1f
        buttonLanguage.isEnabled = !busy
        buttonLanguage.alpha = if (busy) 0.45f else 1f
        buttonBackend.isEnabled = !busy
        buttonBackend.alpha = if (busy) 0.45f else 1f
        advancedToggle.isEnabled = !busy
        advancedToggle.alpha = if (busy) 0.45f else 1f
        setAdvancedEnabled(!busy)
        buttonRecordingAction.isEnabled = !busy && !liveMicActive
        buttonRecordingAction.alpha = if (busy || liveMicActive) 0.45f else 1f
        buttonLiveMicStop.isEnabled = !busy && !whiteRecordingActive
        buttonLiveMicStop.alpha = if (busy || whiteRecordingActive) 0.45f else 1f
        if (busy && isTranscribing) {
            buttonTranscribe.setImageResource(R.drawable.ic_ffmpeg_cancel_red)
            buttonTranscribe.setBackgroundResource(R.drawable.ffmpeg_outline_red_button_bg)
            buttonTranscribe.contentDescription = "Cancelar"
            buttonTranscribe.alpha = 1f
            buttonTranscribe.isClickable = true
            buttonTranscribe.isFocusable = true
        } else if (busy) {
            buttonTranscribe.alpha = 0.45f
            buttonTranscribe.isClickable = false
            buttonTranscribe.isFocusable = false
        } else {
            buttonTranscribe.setImageResource(R.drawable.ic_whisper_transcribe)
            buttonTranscribe.setBackgroundResource(R.drawable.ffmpeg_outline_green_button_bg)
            buttonTranscribe.contentDescription = "Transcrever"
            updateTranscribeEnabled()
        }
    }

    private fun startTranscriptionTimer() {
        transcriptionStartedAt = SystemClock.elapsedRealtime()
        currentTranscriptionStatus = ""
        currentTranscriptionProgress = 0
        currentEstimatedMs = null
        currentEstimatedEfficiency = null
        currentTotalAudioSeconds = 0.0
        timerHandler.removeCallbacks(transcriptionTimer)
        timerHandler.post(transcriptionTimer)
    }

    private fun stopTranscriptionTimer() {
        timerHandler.removeCallbacks(transcriptionTimer)
        transcriptionStartedAt = 0L
        currentTranscriptionStatus = ""
        currentTranscriptionProgress = 0
        currentEstimatedMs = null
        currentEstimatedEfficiency = null
        currentTotalAudioSeconds = 0.0
    }

    private fun setTranscriptionStatus(message: String, progressPercent: Int? = null) {
        currentTranscriptionStatus = message
        // R11/D4 — o status vivo do job vira o texto da notificação do FGS
        // (dono conferido lá dentro; nenhuma notificação de sessão antiga).
        WhisperJobService.updateStatus(this, currentSessionGeneration, message)
        progressPercent?.let {
            currentTranscriptionProgress = it.coerceIn(0, 100)
            currentEstimatedMs = if (currentTranscriptionProgress > 0 && transcriptionStartedAt > 0L) {
                val elapsed = SystemClock.elapsedRealtime() - transcriptionStartedAt
                val estimatedTotal = ((elapsed / currentTranscriptionProgress.toDouble()) * 100.0).roundToLong()
                currentEstimatedEfficiency = if (elapsed > 0L && currentTotalAudioSeconds > 0.0) {
                    val processedSeconds = currentTotalAudioSeconds * currentTranscriptionProgress / 100.0
                    processedSeconds / (elapsed / 1000.0)
                } else {
                    null
                }
                (estimatedTotal - elapsed).coerceAtLeast(0L)
            } else {
                currentEstimatedEfficiency = null
                null
            }
        }
        refreshTranscriptionStatus()
    }

    private fun refreshTranscriptionStatus() {
        if (!isTranscribing || transcriptionStartedAt <= 0L) return
        val elapsed = SystemClock.elapsedRealtime() - transcriptionStartedAt
        val base = currentTranscriptionStatus.ifBlank { "Transcrevendo..." }
        val estimatedLine = currentEstimatedMs?.let {
            "\nTempo restante: +/- ${formatTimer(it)}"
        } ?: run {
            ""
        }
        val efficiencyLine = currentEstimatedEfficiency?.let {
            "\nEficiência estimada: ${String.format(Locale.US, "%.2fx", it)}"
        }.orEmpty()
        status.text = "$base\nTempo percorrido: ${formatTimer(elapsed)}$estimatedLine$efficiencyLine"
    }

    private fun formatTimer(elapsedMs: Long): String {
        val totalSeconds = (elapsedMs / 1000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    private fun cancelTranscription() {
        if (jobOrtNaTela != null) {
            if (WhisperOrtRoute.cancelar(jobOrtNaTela)) status.text = "Cancelando ORT; aguardando término…"
            return
        }
        if (!isTranscribing || cancelRequested) return
        cancelRequested = true
        setTranscriptionStatus("Cancelando transcrição...")
        // R3/B1: id CAPTURADO pelo solicitante — cancelar não lê a geração global.
        whisperSession.cancel(currentSessionGeneration)
        // R16/D2 — retrato do trabalho em voo no instante do ACEITE do cancel
        // (última submissão/nós/bytes do grafo): alimenta a cadeia
        // pedido→aceite→…→worker_end→terminal→release. Inerte em produção.
        logFaRoute("cancel_pedido")
    }

    /**
     * R16/C3 — troca a mídia selecionada no meio da sequência do lab (a mesma
     * via de `loadPickedMedia` do picker; produção nunca chama). A troca limpa
     * o modelo (comportamento do produto), então o rótulo pedido é reposto.
     */
    /**
     * R21/Q1 — SEAM DE BANCADA: seleciona um modelo por ARQUIVO, sem passar pelo
     * menu do catálogo. Necessário para o Turbo Q5, que não está em
     * `officialModels()` — o menu não tem item e a seleção caía no BASE.
     * Só existe no flavor de laboratório; `false` quando fora dele.
     */
    fun labSelectModelFile(fileName: String): Boolean {
        if (!WhisperLabSeams.isLab()) return false
        val f = File(modelsDir(), fileName)
        if (!f.isFile) return false
        selectedModel = WhisperModel(
            label = fileName,
            fileName = fileName,
            file = f,
            downloadUrl = null,
            bytes = f.length()
        )
        buttonModel.text = fileName
        return true
    }

    fun labReselectFixture(fixtureName: String, modelLabel: String?) {
        if (!WhisperLabSeams.isLab()) return
        val uri = android.net.Uri.parse("content://br.gov.sp.pcsp.whisperlab.fixtures/$fixtureName")
        val data = Intent().setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        loadPickedMedia(data)
        if (selectedModel == null && !modelLabel.isNullOrBlank()) {
            selectedModel = officialModels().firstOrNull { it.label == modelLabel }
                ?: officialModels().firstOrNull { it.file.exists() }
            buttonModel.text = selectedModel?.label ?: "Modelo"
        }
    }

    private fun cancelRunningTaskForExit() {
        cancelRequested = true
        if (whiteRecordingActive) {
            whiteRecordingActive = false
            try { whiteRecordingAudioRecord?.stop() } catch (_: Throwable) {}
        }
        if (liveMicActive) {
            liveMicActive = false
            try { liveMicAudioRecord?.stop() } catch (_: Throwable) {}
        }
        if (isTranscribing) {
            cancelTranscription()
        } else {
            FFmpegKit.cancel()
            setBusy(false)
        }
        // R3/B1: id capturado (não a geração global de outra sessão).
        whisperSession.cancel(currentSessionGeneration)
    }

    /** R11/D4 — a notificação do FGS é o canal de OBSERVAÇÃO do job: pede
     * POST_NOTIFICATIONS (API 33+) sem bloquear o job; o serviço roda mesmo sem
     * a permissão (o sistema só não mostra a notificação). */
    private fun requestJobNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            REQUEST_POST_NOTIFICATIONS_PERMISSION
        )
    }

    private fun checkAudioPermission(onGranted: () -> Unit) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            onGranted()
        } else {
            pendingAudioAction = onGranted
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQUEST_RECORD_AUDIO_PERMISSION
            )
        }
    }

    private fun copyTranscriptToClipboard() {
        val text = liveTranscriptText.text.toString().trim()
        if (text.isNotEmpty()) {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Transcrição Whisper", text))
            Toast.makeText(this, "Transcrição copiada!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Nenhum texto para copiar.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareTranscript() {
        val text = liveTranscriptText.text.toString().trim()
        if (text.isNotEmpty()) {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
            startActivity(Intent.createChooser(intent, "Compartilhar transcrição"))
        } else {
            Toast.makeText(this, "Nenhum texto para compartilhar.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun pasteTranscript() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
        if (!clip.isNullOrBlank()) {
            val cur = liveTranscriptText.text.toString()
            val sep = if (cur.isEmpty() || cur.endsWith(" ") || cur.endsWith("\n")) "" else " "
            liveTranscriptText.append(sep + clip)
        }
    }

    /** R3/B2: executa [block] na UI thread SOMENTE na execução (não basta o
     * agendamento) e apenas se a sessão do JOB [sessionGeneration] ainda for a
     * atual; sessionGeneration < 0 = sem checagem (chamadas de setup). */
    private fun postIfCurrent(sessionGeneration: Long, block: () -> Unit) {
        runOnUiThread {
            if (sessionGeneration < 0L) {
                block()
            } else {
                whisperSession.runIfCurrent(sessionGeneration) { block() }
            }
        }
    }

    /** R4/C1: atualiza o terminal se a geração do job [generation] ainda for a
     * atual (post re-checado na execução). */
    private fun updateTerminalTextForGeneration(generation: Long, builder: StringBuilder) {
        postIfCurrent(generation) { updateTerminalText(builder) }
    }

    /** R7/A1: emite um evento do caminho ao vivo com a identidade CARIMBADA
     * pelo produtor (run_id/act_id/job_id/gen/seq/mono[/window_index]) e os
     * campos específicos em [fields] (JSON sem chaves externas). */
    /** R8/B: escape JSON mínimo para texto de janela/reparo no JSONL do lab. */
    private fun jsonEscape(v: String): String =
        v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ")

    private fun liveEvent(name: String, generation: Long, windowIndex: Int = -1, fields: String = "") {
        val header = liveStamp.header(generation, windowIndex)
        // Normaliza os campos: aceita com ou sem as chaves externas; NUNCA
        // deixa o corpo sem o fechamento (payload inválido quebra o parser do
        // consumidor — defeito real detectável pelo analisador).
        val extras = fields.removePrefix("{").removeSuffix("}")
        val body = if (extras.isBlank()) "{$header}" else "{$header,$extras}"
        WhisperLabSeams.event(name, body)
    }

    /** R4/C2: acrescenta UMA linha ao terminal do job ao vivo e reflete na UI
     * somente se a geração [generation] ainda for a atual. Chamado da thread do
     * worker do pipeline — o builder é compartilhado com a UI e sincronizado. */
    private fun appendLiveTerminal(generation: Long, line: String) {
        val builder = liveTerminalLines ?: return
        synchronized(builder) { appendTerminal(builder, line) }
        updateTerminalTextForGeneration(generation, builder)
    }

    /** R8/D (integridade) — PROMOÇÃO do texto do committer ATÉ a UI: publica
     * agora (main; fence de geração/destroy; política em [LiveTextPublication]).
     * Fatia única usada pelo fim de janela (commit), pelo passe de reparo e
     * pelo terminal — sem ela, o texto resolvido/reparado/drenado ficava só nos
     * contadores e nunca no texto final visível (defeito R8-D w01/f05a/f03). */
    private fun publishLiveTextNow(
        gen: Long,
        source: String,
        windowIndex: Int = -1,
        terminalNormal: Boolean = false,
    ) {
        if (isDestroyed || !whisperSession.isCurrent(gen)) return
        val committer = liveTextCommitter ?: return
        val text = LiveTextPublication.textToPublish(
            source = source,
            display = committer.displayText(),
            lastPublished = liveLastPublishedText,
            terminalNormal = terminalNormal,
        ) ?: return
        liveTranscriptText.setText(text)
        liveTranscriptText.setSelection(liveTranscriptText.text.length)
        liveLastPublishedText = text
        liveEvent(
            "clock_ui_publish",
            gen,
            windowIndex,
            fields = "\"source\":\"$source\",\"chars\":${text.length}"
        )
    }

    /** R5/A1: resultado da carga com diagnóstico ATRELADO À OPERAÇÃO (mesma
     * seção crítica do controller). A UI NUNCA lê lastError/lastLoadLog globais
     * depois de um load — o texto vem da MESMA carga (ou é recusa local). */
    private class EnsureLoadResult(val ok: Boolean, val failureText: String, val loadLog: String)

    private fun ensureModelLoaded(model: WhisperModel, backend: WhisperBackend, flashAttention: Boolean, sessionGeneration: Long): EnsureLoadResult {
        // R12/F1: a chave inclui SHA-256 + tamanho dos bytes validados, não só
        // tamanho/mtime. O hash é calculado uma vez na fronteira de carga/job
        // (nunca por janela live), e trocar conteúdo no mesmo caminho força miss.
        val key = "${model.file.absolutePath}|${backend.nativeKind}|$flashAttention|" +
            ModelImportSupport.cacheIdentity(model.file)
        // R3/B2: fast-path só com chave + contexto EXISTENTE + transferência
        // atômica de posse (nativo reconfere a chave real sob g_mutex, sem
        // recarga). Chave apontando para contexto destruído vira miss.
        val hit = loadedModelCache.tryReuse(
            sessionGeneration,
            key,
            { whisperSession.isCurrent(it) },
            { generation -> whisperSession.assumeContext(generation, modelFile(model).absolutePath, backend.nativeKind, flashAttention) },
        )
        if (hit) {
            // R4/C3: fast-path (cache-hit) visível no lab; produção: no-op.
            liveEvent("model_cache_hit", sessionGeneration)
            return EnsureLoadResult(ok = true, failureText = "", loadLog = "")
        }
        // R2/B2 + R5/A1: load via controller (coordenado e atrelado à sessão do
        // JOB) devolvendo o diagnóstico DA MESMA operação — nada de ler o
        // diagnóstico global do sucessor.
        val outcome = whisperSession.loadModelDetailed(
            sessionGeneration,
            modelFile(model).absolutePath,
            backend.nativeKind,
            flashAttention
        )
        if (outcome.ok) {
            loadedModelCache.record(sessionGeneration, key)
            liveEvent("model_loaded", sessionGeneration, fields = "\"key\":\"${key.replace("\"", "'")}\"")
        } else {
            loadedModelCache.clear()
            liveEvent(
                "model_load_failed",
                sessionGeneration,
                fields = "\"refusal\":\"${outcome.refusal.replace("\"", "'")}\"," +
                    "\"native_error\":\"${outcome.nativeError.replace("\"", "'")}\""
            )
        }
        currentlyLoadedModelKey = if (outcome.ok) key else null
        val failure = outcome.refusal.ifBlank {
            outcome.nativeError.ifBlank { "não consegui carregar o modelo com ${backend.reportLabel}" }
        }
        return EnsureLoadResult(outcome.ok, if (outcome.ok) "" else failure, outcome.loadLog)
    }

    private fun releaseLoadedModel() {
        // R2/B2: release assíncrono atrelado à sessão que CARREGOU o contexto
        // (token do próprio dono); o nativo reconfere sob g_mutex — um release
        // atrasado de outra sessão vira no-op e JAMAIS libera o contexto de B.
        whisperSession.releaseAsync(loadedModelCache.loadedGeneration)
        loadedModelCache.clear()
        currentlyLoadedModelKey = null
    }

    private fun writeWavFile(file: File, pcm: ByteArray, sampleRate: Int) {
        FileOutputStream(file).use { output ->
            val byteRate = sampleRate * 2
            output.write("RIFF".toByteArray(Charsets.US_ASCII))
            LittleEndianIo.writeIntLe(output, 36 + pcm.size)
            output.write("WAVE".toByteArray(Charsets.US_ASCII))
            output.write("fmt ".toByteArray(Charsets.US_ASCII))
            LittleEndianIo.writeIntLe(output, 16)
            LittleEndianIo.writeShortLe(output, 1)
            LittleEndianIo.writeShortLe(output, 1)
            LittleEndianIo.writeIntLe(output, sampleRate)
            LittleEndianIo.writeIntLe(output, byteRate)
            LittleEndianIo.writeShortLe(output, 2)
            LittleEndianIo.writeShortLe(output, 16)
            output.write("data".toByteArray(Charsets.US_ASCII))
            LittleEndianIo.writeIntLe(output, pcm.size)
            output.write(pcm)
        }
    }

    private fun writeWavFile(file: File, pcmFile: File, sampleRate: Int) {
        val pcmSize = pcmFile.length().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        FileOutputStream(file).use { output ->
            val byteRate = sampleRate * 2
            output.write("RIFF".toByteArray(Charsets.US_ASCII))
            LittleEndianIo.writeIntLe(output, 36 + pcmSize)
            output.write("WAVE".toByteArray(Charsets.US_ASCII))
            output.write("fmt ".toByteArray(Charsets.US_ASCII))
            LittleEndianIo.writeIntLe(output, 16)
            LittleEndianIo.writeShortLe(output, 1)
            LittleEndianIo.writeShortLe(output, 1)
            LittleEndianIo.writeIntLe(output, sampleRate)
            LittleEndianIo.writeIntLe(output, byteRate)
            LittleEndianIo.writeShortLe(output, 2)
            LittleEndianIo.writeShortLe(output, 16)
            output.write("data".toByteArray(Charsets.US_ASCII))
            LittleEndianIo.writeIntLe(output, pcmSize)
            pcmFile.inputStream().use { input -> input.copyTo(output) }
        }
    }

    private fun startWhiteRecording() {
        if (liveMicActive || isBusy || isTranscribing) return
        // R4/C3: no laboratório a fonte de gravação pode ser injetada (arquivo
        // PCM); o caminho de permissão só é pulado quando HÁ injeção — em
        // produção o seam devolve null e o AudioRecord segue obrigatório.
        val injectedRecording = WhisperLabSeams.injectedRecordingSource(this)
        if (injectedRecording != null) {
            beginWhiteRecording(injectedRecording)
        } else {
            checkAudioPermission { beginWhiteRecording(null) }
        }
    }

    private fun beginWhiteRecording(injectedRecording: LivePcmSource?) {
        val model = selectedModel ?: run {
            status.text = "Selecione um modelo."
            return
        }
        if (!DownloadFileStorage.isComplete(modelFile(model), model.bytes)) {
            confirmModelDownload(model)
            return
        }

        whiteRecordingActive = true
        whiteRecordingStartedAt = SystemClock.elapsedRealtime()
        buttonRecordingAction.setImageResource(R.drawable.ic_ffmpeg_cancel_red)
        buttonRecordingAction.setBackgroundResource(R.drawable.ffmpeg_outline_red_button_bg)
        buttonRecordingAction.contentDescription = "Parar gravação"

        buttonLiveMicStop.isEnabled = false
        buttonLiveMicStop.alpha = 0.4f
        findViewById<View>(R.id.button_select_media).isEnabled = false
        buttonTranscribe.isEnabled = false
        buttonModel.isEnabled = false
        buttonBackend.isEnabled = false

        recordingPanel.visibility = View.VISIBLE
        recordingTimer.text = "00:00.000"
        timerHandler.post(recordingTicker)
        status.text = if (injectedRecording == null) {
            "Gravando áudio do microfone..."
        } else {
            "Gravando (fonte de laboratório injetada)..."
        }

        val stamp = System.currentTimeMillis()
        whiteRecordingPcmFile = File(cacheDir, "whisper_mic_$stamp.pcm")
        whiteRecordingWavFile = File(cacheDir, "whisper_mic_$stamp.wav")

        whiteRecordingThread = Thread {
            recordWhitePcm(injectedRecording)
        }.also { it.start() }
    }

    private fun stopWhiteRecording() {
        if (!whiteRecordingActive) return
        whiteRecordingActive = false
        timerHandler.removeCallbacks(recordingTicker)

        buttonRecordingAction.setImageResource(R.drawable.ic_mic_outline)
        buttonRecordingAction.setBackgroundResource(R.drawable.ffmpeg_outline_button_bg)
        buttonRecordingAction.contentDescription = "Gravar e transcrever"
        recordingPanel.visibility = View.GONE

        buttonLiveMicStop.isEnabled = true
        buttonLiveMicStop.alpha = 1.0f
        findViewById<View>(R.id.button_select_media).isEnabled = true
        buttonModel.isEnabled = true
        buttonBackend.isEnabled = true
        updateTranscribeEnabled()

        val pcm = whiteRecordingPcmFile
        val wav = whiteRecordingWavFile

        Thread {
            try {
                whiteRecordingThread?.join(3000)
            } catch (_: Throwable) {}
            whiteRecordingThread = null

            if (pcm != null && pcm.exists() && pcm.length() > 0 && wav != null) {
                writeWavFile(wav, pcm, 16000)
                pcm.delete()
                whiteRecordingPcmFile = null

                runOnUiThread {
                    transcribeRecordedWav(wav)
                }
            } else {
                runOnUiThread {
                    status.text = "Gravação vazia ou cancelada."
                }
                pcm?.delete()
                wav?.delete()
            }
        }.start()
    }

    /** R4/C3: laço leitura→PCM do produto alimentado pela fonte injetada do
     * laboratório (em produção o seam devolve null e este caminho não roda).
     * EOF da fonte encerra o laço; o WAV é fechado e o stop segue o fluxo
     * normal (junção → transcribeRecordedWav). Sem microfone incidental. */
    private fun recordWhitePcmFromSource(source: LivePcmSource) {
        try {
            val pcmFile = whiteRecordingPcmFile ?: return
            FileOutputStream(pcmFile).use { output ->
                val buffer = ByteArray(8192)
                try {
                    while (whiteRecordingActive) {
                        val read = source.read(buffer)
                        if (read > 0) {
                            output.write(buffer, 0, read)
                        } else if (read < 0) {
                            break
                        }
                    }
                } finally {
                    output.flush()
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Injected PCM recording failed", e)
        } finally {
            runCatching { source.close() }
        }
    }

    private fun recordWhitePcm(injectedRecording: LivePcmSource?) {
        // R4/C3: fonte injetada do laboratório — mesmo laço leitura→PCM.
        if (injectedRecording != null) {
            recordWhitePcmFromSource(injectedRecording)
            return
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            runOnUiThread {
                whiteRecordingActive = false
                status.text = "Permissão do microfone removida."
                buttonRecordingAction.setImageResource(R.drawable.ic_mic_outline)
                buttonRecordingAction.setBackgroundResource(R.drawable.ffmpeg_outline_button_bg)
            }
            return
        }
        val sampleRate = 16000
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelConfig, encoding)
        if (minBuffer <= 0) {
            runOnUiThread {
                whiteRecordingActive = false
                status.text = "Microfone indisponível."
                buttonRecordingAction.setImageResource(R.drawable.ic_mic_outline)
                buttonRecordingAction.setBackgroundResource(R.drawable.ffmpeg_outline_button_bg)
            }
            return
        }
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            encoding,
            minBuffer * 2
        )
        whiteRecordingAudioRecord = recorder
        try {
            val pcmFile = whiteRecordingPcmFile ?: return
            FileOutputStream(pcmFile).use { output ->
                val buffer = ByteArray(minBuffer)
                recorder.startRecording()
                try {
                    while (whiteRecordingActive) {
                        val read = recorder.read(buffer, 0, buffer.size)
                        if (read > 0) output.write(buffer, 0, read)
                    }
                } finally {
                    runCatching { recorder.stop() }
                    output.flush()
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "White microphone recording failed", e)
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
            if (whiteRecordingAudioRecord == recorder) whiteRecordingAudioRecord = null
        }
    }

    private fun transcribeRecordedWav(wavFile: File) {
        val model = selectedModel ?: return
        val backend = selectedBackend
        val settings = readAdvancedSettings()
        // W02: nova sessão descarta cancelamentos e callbacks da anterior.
        currentSessionGeneration = whisperSession.beginSession()
        val sessionGeneration = currentSessionGeneration
        // R11/D4 — a transcrição da gravação é um job de ARQUIVO de verdade:
        // mesmo dono de lifecycle do caminho de arquivo (FGS `dataSync`).
        val admissaoGravacao = WhisperJobService.startJobOutcome(
            this, sessionGeneration, WhisperJobLifecycle.KIND_FILE, "Carregando modelo e transcrevendo…"
        )
        if (admissaoGravacao == WhisperJobService.StartOutcome.ADMISSAO_RECUSADA) {
            status.text = "Job recusado: outra geração é dona da reserva."
            progress.visibility = View.GONE
            return
        }
        // R56/adendo18 — IDENTIDADE DO JOB NO PRODUTOR. A gravação publicava só
        // o ACK; o texto saía pelo widget e o export virava
        // `widget_sem_identidade` (job_id=0/owner=0, terminal NONE). Aqui o job
        // admitido ganha identidade imutável e o resultado passa a ser
        // publicado no MESMO ledger da rota de arquivo.
        val jobIdGravacao = TranscriptionJobResults.ledger.beginJob(
            LiveEventIdentity.runId, activityInstanceId, sessionGeneration
        )
        liveTranscriptContainer.visibility = View.VISIBLE
        liveTranscriptClipboardActions.visibility = View.VISIBLE
        status.text = "Carregando modelo e transcrevendo..."
        progress.visibility = View.VISIBLE

        Thread {
            // R56/B03 — DESFECHO TIPADO do job de gravação. `RECORDED_TERMINAL`
            // não existe no contrato de `TerminalKind` (cai em UNKNOWN) e o dono
            // nunca recebia ACK. Vai como o desfecho real, igual às demais
            // rotas: SUCCESS/CANCELLED/ERROR.
            var desfechoGravacao = "ERROR"
            // R56/adendo19 — tentativa e finalizador criados ANTES da etapa que
            // pode falhar. Todo desfecho de job ADMITIDO converge para uma única
            // publicação; nenhum `return`/`catch` deixa o job sem resultado.
            val chaveGravacao = TranscriptionJobResults.ledger.beginAttempt(
                jobIdGravacao, sessionGeneration, wavFile.name,
                LiveEventIdentity.runId, activityInstanceId
            )
            val segmentosGravacao = StringBuilder()
            val finalizador = GravacaoResultadoFinal(
                TranscriptionJobResults.ledger, jobIdGravacao, sessionGeneration
            )
            try {
                val modelLoaded = synchronized(modelLoadLock) {
                    ensureModelLoaded(model, backend, settings.flashAttention, sessionGeneration)
                }
                if (!modelLoaded.ok) {
                    // R56/adendo19 — falha PRÉ-PARSE também publica o resultado
                    // do próprio job (antes só havia ACK ERROR, sem payload).
                    finalizador.publicar(JobTerminalState.ERROR, "model_load_failed")
                    // R5/A1: texto IMUTÁVEL capturado no worker; o post confere o
                    // lease NA EXECUÇÃO — sessão substituída/destruída não muda a
                    // UI nem consulta diagnóstico global de outra sessão.
                    val err = modelLoaded.failureText.ifBlank { "Falha ao carregar modelo." }
                    runOnUiThread {
                        if (isDestroyed) return@runOnUiThread
                        whisperSession.runIfCurrent(sessionGeneration) {
                            status.text = "Erro: $err"
                            progress.visibility = View.GONE
                        }
                    }
                    return@Thread
                }

                val cb = object : WhisperNative.Callback {
                    override fun onSegment(text: String, startMs: Long, endMs: Long) {
                        val segment = text.trim()
                        if (segment.isNotBlank()) {
                            // R56/adendo18 — o segmento entra no ledger DESTE
                            // job (por chave), independente da fila de UI.
                            synchronized(segmentosGravacao) {
                                if (segmentosGravacao.isNotEmpty()) segmentosGravacao.append('\n')
                                segmentosGravacao.append(segment)
                            }
                            chaveGravacao?.let { chave ->
                                runCatching {
                                    TranscriptionJobResults.ledger.appendSegment(
                                        chave, segment, startMs, endMs
                                    )
                                }
                            }
                            runOnUiThread {
                                // R2/B2: re-checagem na EXECUÇÃO do post.
                                whisperSession.runIfCurrent(sessionGeneration) {
                                    val cur = liveTranscriptText.text.toString()
                                    val sep = if (cur.isEmpty() || cur.endsWith("\n") || cur.endsWith(" ")) "" else " "
                                    liveTranscriptText.append(sep + segment)
                                    liveTranscriptText.setSelection(liveTranscriptText.text.length)
                                }
                            }
                        }
                    }
                    override fun onProgress(progress: Int) {
                        runOnUiThread {
                            whisperSession.runIfCurrent(sessionGeneration) {
                                status.text = "Transcrevendo gravação... $progress%"
                            }
                        }
                    }
                    override fun onNativeLog(line: String) {}
                }

                // R18/C1 — INÍCIO REAL do worker ANTES do trabalho nativo. O
                // serviço passa a saber que existe worker por esta ação, não
                // pelo primeiro status publicado.
                runCatching { WhisperJobService.markWorkerStarted(this@WhisperActivity, sessionGeneration) }

                // R2/B2: par texto+status do mesmo job, numa única chamada.
                val (returnedRaw, transcribeStatus) = synchronized(modelLoadLock) {
                    whisperSession.transcribeAndStatus(
                        sessionGeneration,
                        wavFile.absolutePath,
                        selectedLanguage.code,
                        settings.beamSize,
                        settings.bestOf,
                        settings.wordTimestamps,
                        settings.vadFilter,
                        if (settings.vadFilter) vadModelFile().absolutePath else "",
                        cb
                    )
                }
                val returnedText = returnedRaw.trim()

                val parsed = WhisperResultParser.parse(returnedText, transcribeStatus)
                // R56/B03 (checkpoint9) — DESFECHO CALCULADO UMA VEZ NO WORKER,
                // ANTES de enfileirar qualquer atualização visual. Antes, quem
                // atribuía era o Runnable da main, e o `finally` da própria
                // thread lia o valor: o ACK dependia do escalonamento da UI
                // (podia sair ERROR com a fila retida e SUCCESS depois).
                // A UI recebe só um retrato imutável para exibir.
                // R56/adendo18 — RESULTADO PUBLICADO NO PRODUTOR. O ACK sozinho
                // não é resultado: o export precisa de job/owner/input/attempt e
                // de terminal próprio. Publica ANTES de qualquer post de UI, para
                // que fila retida ou Activity destruída não suprimam o payload.
                val terminalGravacao = when (parsed) {
                    is WhisperTranscriptResult.Success -> JobTerminalState.SUCCESS
                    is WhisperTranscriptResult.NoSpeech -> JobTerminalState.NO_SPEECH
                    is WhisperTranscriptResult.Cancelled -> JobTerminalState.CANCELLED
                    else -> JobTerminalState.ERROR
                }
                // ACK e publicação saem da MESMA fonte (um só mapeamento).
                desfechoGravacao = when (terminalGravacao) {
                    JobTerminalState.SUCCESS, JobTerminalState.NO_SPEECH -> "SUCCESS"
                    JobTerminalState.CANCELLED -> "CANCELLED"
                    JobTerminalState.ERROR -> "ERROR"
                }
                // Fallback: se o callback não entregou nenhum segmento, o texto
                // do PRÓPRIO resultado entra UMA vez. Nunca texto de widget.
                val textoDoJob = synchronized(segmentosGravacao) { segmentosGravacao.toString() }
                if (chaveGravacao != null && textoDoJob.isBlank()) {
                    val textoFinal = (parsed as? WhisperTranscriptResult.Success)?.text?.trim().orEmpty()
                    if (textoFinal.isNotEmpty()) {
                        runCatching {
                            TranscriptionJobResults.ledger.appendSegment(chaveGravacao, textoFinal)
                        }
                    }
                }
                runCatching { finalizador.publicar(terminalGravacao, transcribeStatus) }
                    .onFailure { Log.w(TAG, "falha ao publicar resultado da gravacao: $it") }
                runOnUiThread {
                    // Post visual: só exibe, nunca decide o terminal. Se a
                    // Activity morreu ou a geração mudou, o retrato é
                    // descartado — o ACK do worker segue valendo.
                    if (isDestroyed) return@runOnUiThread
                    whisperSession.runIfCurrent(sessionGeneration) {
                        progress.visibility = View.GONE
                        when (parsed) {
                            is WhisperTranscriptResult.Cancelled ->
                                status.text = "Transcrição cancelada."
                            is WhisperTranscriptResult.Error ->
                                status.text = "Erro: ${parsed.message}"
                            is WhisperTranscriptResult.NoSpeech ->
                                status.text = "Sem fala detectada."
                            is WhisperTranscriptResult.Success -> {
                                status.text = "Transcrição concluída com sucesso!"
                                if (liveTranscriptText.text.isEmpty() && parsed.text.isNotBlank()) {
                                    liveTranscriptText.setText(parsed.text)
                                }
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Error transcribing recorded wav", e)
                // R56/adendo19 — exceção no worker TAMBÉM publica o resultado do
                // próprio job; sem isto o job admitido ficava só com ACK.
                runCatching {
                    finalizador.publicar(JobTerminalState.ERROR, e.message ?: "exception")
                }.onFailure { Log.w(TAG, "falha ao publicar erro da gravacao: $it") }
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    whisperSession.runIfCurrent(sessionGeneration) {
                        progress.visibility = View.GONE
                        status.text = "Erro: ${e.message}"
                    }
                }
            } finally {
                // R56/adendo19 — CONVERGÊNCIA: se nenhum caminho publicou (ex.:
                // retorno antecipado novo, erro fora do try), o resultado sai
                // aqui UMA vez. O ACK é derivado do que foi realmente aceito.
                if (finalizador.publicado == null) {
                    runCatching {
                        finalizador.publicar(JobTerminalState.ERROR, "sem_desfecho")
                    }.onFailure { Log.w(TAG, "falha ao publicar desfecho final: $it") }
                }
                desfechoGravacao = finalizador.terminalDeAck()
                // R56/B03 — cleanup NÃO pode pular o sink terminal: se
                // `delete()` falhar, o ACK do worker ainda tem de sair.
                runCatching { wavFile.delete() }
                    .onFailure { Log.w(TAG, "falha ao apagar wav temporário: $it") }
                // R11/D4 — TERMINAL do job de transcrição da gravação.
                // R56/B03 — desfecho TIPADO (o literal antigo caía em UNKNOWN e
                // o dono ficava sem ACK).
                WhisperJobService.finishJob(sessionGeneration, desfechoGravacao)
            }
        }.start()
    }

    private fun startLiveMicTranscription() {
        if (whiteRecordingActive || isBusy || isTranscribing) return
        checkAudioPermission {
            val model = selectedModel ?: run {
                status.text = "Selecione um modelo."
                return@checkAudioPermission
            }
            if (!DownloadFileStorage.isComplete(modelFile(model), model.bytes)) {
                confirmModelDownload(model)
                return@checkAudioPermission
            }
            // R4/C1: VAD pedido exige o modelo na MESMA rota do arquivo (download
            // explícito); falta = config/erro explícito, nunca silêncio.
            val settings = readAdvancedSettings()
            if (settings.vadFilter && !vadModelFile().exists()) {
                status.text = "VAD ativo, mas o modelo Silero não está baixado — baixe para transcrever."
                confirmVadDownload { startLiveMicTranscription() }
                return@checkAudioPermission
            }

            // R56/B02 — a admissão é feita depois da preparação e, em caso de
            // recusa, a preparação é DESFEITA localmente (idempotente, sem
            // tocar no dono antigo).
            liveMicActive = true
            liveMicStartedAt = SystemClock.elapsedRealtime()
            buttonLiveMicStop.setImageResource(R.drawable.ic_ffmpeg_cancel_red)
            buttonLiveMicStop.setBackgroundResource(R.drawable.ffmpeg_outline_red_button_bg)
            buttonLiveMicStop.contentDescription = "Parar transcrição ao vivo"

            buttonRecordingAction.isEnabled = false
            buttonRecordingAction.alpha = 0.4f
            findViewById<View>(R.id.button_select_media).isEnabled = false
            buttonTranscribe.isEnabled = false
            buttonModel.isEnabled = false
            buttonBackend.isEnabled = false

            recordingPanel.visibility = View.VISIBLE
            recordingTimer.text = "00:00.000"
            liveTranscriptContainer.visibility = View.VISIBLE
            liveTranscriptClipboardActions.visibility = View.VISIBLE
            timerHandler.post(recordingTicker)

            val backend = selectedBackend
            // R4/C1: SNAPSHOT dos settings/idioma/modelo/backend no INÍCIO do job;
            // este retrato (e nada além dele) alimenta o caminho live.
            val liveSettings = LiveJobSettings(
                language = selectedLanguage.code,
                modelLabel = model.label,
                backendLabel = backend.reportLabel,
                beamSize = settings.beamSize,
                bestOf = settings.bestOf,
                wordTimestamps = settings.wordTimestamps,
                vadFilter = settings.vadFilter,
                vadModelPath = if (settings.vadFilter) vadModelFile().absolutePath else "",
            )
            liveMicSettings = liveSettings
            liveChunkTally = LiveChunkTally()
            liveTerminalSnapshot = null
            liveReaderReason = ""
            liveWasCancelled = false
            val terminalLines = StringBuilder()
            liveTerminalLines = terminalLines
            appendTerminal(terminalLines, "$ whisper-live --model ${model.fileName} --language ${liveSettings.language}")
            appendTerminal(terminalLines, "backend: ${backend.reportLabel}")
            appendTerminal(terminalLines, "parametros efetivos (live): ${liveSettings.describe()}")
            status.text = "Carregando modelo para tempo real... (${liveSettings.describe()})"
            // W02: nova sessão descarta cancelamentos e callbacks da anterior.
            currentSessionGeneration = whisperSession.beginSession()
            val liveGeneration = currentSessionGeneration
            updateTerminalTextForGeneration(liveGeneration, terminalLines)
            // R11/D4 — execução iniciada pelo usuário e observável: FGS tipo
            // `microphone` (RECORD_AUDIO já autorizado por checkAudioPermission),
            // com wake lock e notificação de status/cancelar.
            val admissaoLive = WhisperJobService.startJobOutcome(
                this, liveGeneration, WhisperJobLifecycle.KIND_LIVE, "Preparando transcrição ao vivo…"
            )
            if (admissaoLive == WhisperJobService.StartOutcome.ADMISSAO_RECUSADA) {
                // Tentativa NAO admitida: desfaz a preparação local e nao abre
                // áudio nem worker. Nada do dono antigo é cancelado aqui.
                desfazPreparacaoLive()
                status.text = "Job recusado: outra geração é dona da reserva."
            } else {
                // R56/B01-B07 — sink com a geração IMUTÁVEL deste job, criado
                // antes de qualquer trabalho que possa terminar cedo. A
                // INSTÂNCIA viaja com o job (nota11 §2): nenhuma saída de A
                // pode encontrar o sink de C por lookup em campo mutável.
                val sinkDoJob = WhisperJobTerminalSink(liveGeneration) { g, d ->
                    runCatching {
                        WhisperJobService.finishJob(g, d)
                        true
                    }.getOrDefault(false)
                }
                terminalSinkAtual = sinkDoJob
                liveMicThread = Thread {
                    runLiveMicLoop(
                        model, backend, settings, liveSettings, liveGeneration, sinkDoJob
                    )
                }.also { it.start() }
            }
        }
    }

    /**
     * R56/B02 — desfaz a PREPARAÇÃO local de uma tentativa live **não admitida**.
     *
     * Idempotente e estritamente local: não chama stop/cancel do job, não toca
     * reserva nem dono de outra geração — só devolve a tela ao repouso para que
     * o usuário possa tentar de novo. Ordem importa: `liveMicActive=false`
     * ANTES de `setBusy(false)`, porque `setBusy` deriva o estado dos botões de
     * `!busy && !liveMicActive`.
     */
    private fun desfazPreparacaoLive() {
        liveMicActive = false
        liveMicStartedAt = 0L
        timerHandler.removeCallbacks(recordingTicker)
        recordingTimer.text = "00:00.000"
        recordingPanel.visibility = View.GONE
        liveTranscriptContainer.visibility = View.GONE
        liveTranscriptClipboardActions.visibility = View.GONE
        buttonLiveMicStop.setImageResource(R.drawable.ic_mic_outline_red)
        buttonLiveMicStop.contentDescription = "Transcrição ao vivo"
        setBusy(false)
        buttonRecordingAction.isEnabled = true
        buttonRecordingAction.alpha = 1f
        buttonTranscribe.isEnabled = true
        findViewById<View>(R.id.button_select_media).isEnabled = true
    }

    private fun stopLiveMicTranscription() {
        if (!liveMicActive) return
        liveMicActive = false
        timerHandler.removeCallbacks(recordingTicker)

        // R13/F2-fix (achado F2: pedido→terminal 29.044 ms): parar NÃO é só
        // "parar de capturar". O pedido de cancelamento é encaminhado AGORA, ao
        // owner do job EM VOO, antes de qualquer join: a janela de ~82 s
        // observa o abort na próxima fronteira segura (nó do grafo CPU) em vez
        // de terminar inteira. O pendente é descartado, senão o próximo chunk
        // viraria um decode novo com o pedido já consumido. Nada é revogado: o
        // texto já publicado permanece e o cancel é por-dono (nunca anônimo).
        liveStopRequest?.request(liveMicGeneration)

        val thread = liveMicThread
        if (thread == null || !thread.isAlive) {
            completeLiveMicStop()
            return
        }
        if (liveMicStopping) return
        liveMicStopping = true
        liveMicStopStartedAt = SystemClock.elapsedRealtime()
        status.text = LIVE_MIC_STOPPING_LABEL

        // W03: o join acontece em thread própria, nunca na UI thread. A UI só é
        // reabilitada quando a thread de live morrer (ou após o teto de 30s).
        Thread {
            try {
                thread.join(3000)
            } catch (_: Throwable) {}
            timerHandler.post { evaluateLiveMicStop() }
        }.start()
    }

    private fun evaluateLiveMicStop() {
        if (isDestroyed) {
            liveMicStopping = false
            return
        }
        val thread = liveMicThread
        // R3/C1: observar o coordenador INTEIRO — a thread do leitor pode morrer
        // antes do worker; "finalizada" (e liberar controles) SÓ com o worker
        // terminal, nunca só porque a thread acabou.
        val pipeline = liveMicPipeline
        val workerBusy = pipeline != null && !pipeline.isFinished()
        val threadAlive = thread != null && thread.isAlive
        if (!threadAlive && !workerBusy) {
            completeLiveMicStop()
            return
        }
        val elapsedMs = SystemClock.elapsedRealtime() - liveMicStopStartedAt
        if (elapsedMs >= LIVE_MIC_STOP_TIMEOUT_MS || workerBusy) {
            // R2/B2: com worker vivo NÃO se anuncia "finalizada" nem se libera a
            // UI — mantém a referência ao worker, troca a mensagem e continua
            // re-checando até ele morrer (só então completeLiveMicStop roda).
            Log.w(TAG, "Thread/worker da transcrição ao vivo ainda vivos após ${elapsedMs}ms; aguardando o worker terminar.")
            status.text = LIVE_MIC_STOPPING_WORKER_LABEL
            timerHandler.postDelayed({ evaluateLiveMicStop() }, LIVE_MIC_STOP_POLL_MS)
            return
        }
        status.text = LIVE_MIC_STOPPING_LABEL
        timerHandler.postDelayed({ evaluateLiveMicStop() }, LIVE_MIC_STOP_POLL_MS)
    }

    /**
     * R56/B01-B07 (adendo8 §1-2) — SINK TERMINAL do job ao vivo, independente da
     * Activity viva.
     *
     * O caminho antigo só chamava `finishJob(...)` dentro de
     * `completeLiveMicStop()`, alcançado por `runOnUiThread`: com a Activity
     * destruída o post é descartado e o dono fica sem ACK
     * (`job_owner_gone_sem_ack` → `job_stuck`). Este sink é chamado pelo próprio
     * worker, DEPOIS do término real, e é idempotente por geração — repetição
     * não libera nem reescreve geração alheia, e pedido de cancelamento não
     * serve como prova de fim.
     */
    private fun ackTerminalLive(geracao: Long, desfecho: String) {
        if (geracao <= 0L) return
        // Caminho preferencial: o sink do JOB (idempotente por identidade).
        val sink = terminalSinkAtual
        if (sink != null && sink.geracao == geracao) {
            sink.concluir(desfecho)
            return
        }
        // Fallback (gravação/rotas sem sink): dedupe pela última geração.
        synchronized(this) {
            if (liveAckGenEmitido == geracao) return
            liveAckGenEmitido = geracao
        }
        WhisperJobService.finishJob(geracao, desfecho)
    }

    private fun completeLiveMicStop() {
        liveMicStopping = false
        liveMicThread = null
        // R13/F2-fix: o pedido é do job que terminou — o próximo job cria o seu.
        liveStopRequest = null
        // R55/B01 — o terminal pertence à geração DESTE laço, capturada ANTES
        // de zerar o campo. Antes daqui o código zerava `liveMicGeneration` e
        // chamava `finishJob(currentSessionGeneration, ...)`: se um job novo
        // tinha começado na janela, o terminal ao vivo caía na geração do
        // SUCESSOR (terminal de outra identidade).
        val genDesteStop = liveMicGeneration
        liveMicGeneration = 0L
        // R11/D4 — TERMINAL do job ao vivo (stop do usuário, cancel pela
        // notificação ou erro do worker): libera lock/notificação.
        if (genDesteStop > 0L) {
            ackTerminalLive(genDesteStop, "LIVE_TERMINAL")
        }
        buttonLiveMicStop.setImageResource(R.drawable.ic_mic_outline_red)
        buttonLiveMicStop.setBackgroundResource(R.drawable.ffmpeg_outline_button_bg)
        buttonLiveMicStop.contentDescription = "Transcrição ao vivo"
        recordingPanel.visibility = View.GONE

        buttonRecordingAction.isEnabled = true
        buttonRecordingAction.alpha = 1.0f
        findViewById<View>(R.id.button_select_media).isEnabled = true
        buttonModel.isEnabled = true
        buttonBackend.isEnabled = true
        updateTranscribeEnabled()

        // R4/C2: NUNCA sobrescrever erro/parcial/cancelado com "finalizada"
        // genérica — o retrato terminal (preservado ANTES do pipeline sumir)
        // manda na UI: veredito + duração + amostras falhas/perdidas/canceladas
        // + motivo + parâmetros efetivos.
        val snapshot = liveTerminalSnapshot
        if (snapshot != null) {
            status.text = snapshot.statusLine()
            liveTerminalLines?.let { builder ->
                appendTerminal(builder, snapshot.statusLine())
                updateTerminalText(builder)
            }
            liveEvent("live_stop_ui", liveStamp.currentJobGeneration(), fields = "\"status\":\"${snapshot.statusLine().replace("\"", "'")}\"")
        } else {
            status.text = "Transcrição ao vivo finalizada."
        }
    }

    /**
     * R56/B01-B07 — WRAPPER de lifetime/termino do job ao vivo.
     *
     * Cobre por construção os caminhos que faltavam (adendo10 §1, lacuna "b"):
     * fim normal, exceção do corpo e TODOS os retornos do pipeline, sem
     * depender de `stopLiveMicTranscription()`/UI. O ACK é emitido no worker,
     * com a geração da ADMISSÃO, e o sink o torna idempotente — os retornos
     * precoces que já chamam `ackTerminalLive` não duplicam nada.
     */
    private fun runLiveMicLoop(
        model: WhisperModel,
        backend: WhisperBackend,
        settings: WhisperAdvancedSettings,
        liveSettings: LiveJobSettings,
        geracaoJob: Long,
        sinkDoJob: WhisperJobTerminalSink,
    ) {
        // R56/B01-B07 — o ACK só sai com término COMPROVADO e pela INSTÂNCIA do
        // próprio job. Exceção no corpo pode acontecer com o pipeline em voo;
        // nesse caso publicar PENDING e conservar a proteção é o honesto —
        // fabricar ACK para "fechar o teste" é o que a ordem proíbe.
        var corpoConcluiu = false
        try {
            runLiveMicLoopCorpo(
                model, backend, settings, liveSettings, geracaoJob, sinkDoJob
            )
            corpoConcluiu = true
        } catch (t: Throwable) {
            Log.e(TAG, "worker live terminou com exceção sem dreno provado", t)
        } finally {
            if (corpoConcluiu) {
                // Sem lookup em `terminalSinkAtual`: se C já substituiu o campo,
                // A continua concluindo o SEU sink.
                sinkDoJob.concluir("LIVE_TERMINAL")
            } else if (geracaoJob > 0L) {
                runCatching {
                    liveEvent(
                        "live_terminal_pendente", geracaoJob,
                        fields = "\"why\":\"excecao_sem_dreno_provado\""
                    )
                }
                Log.w(
                    TAG,
                    "job $geracaoJob: exceção sem dreno comprovado — ACK NÃO emitido; " +
                        "proteção conservada para reconciliação"
                )
            }
        }
    }

    private fun runLiveMicLoopCorpo(
        model: WhisperModel,
        backend: WhisperBackend,
        settings: WhisperAdvancedSettings,
        liveSettings: LiveJobSettings,
        // R56/B01-B07 — geração e SINK capturados na ADMISSÃO, disponíveis
        // ANTES de qualquer retorno precoce (inclusive checagem de buffer).
        geracaoJob: Long,
        sinkDoJob: WhisperJobTerminalSink,
    ) {
        val sampleRate = 16000
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        // R4/C3: fonte injetada SÓ no flavor de lab (produção devolve null e o
        // caminho é AudioRecord puro). Nada de algoritmo próprio do lab: é o
        // MESMO laço leitor->spool->worker do produto.
        val injectedSource = WhisperLabSeams.injectedLiveSource(this)
        val minBuffer = if (injectedSource != null) 8192 else AudioRecord.getMinBufferSize(sampleRate, channelConfig, encoding)
        if (minBuffer <= 0) {
            runOnUiThread {
                status.text = "Microfone indisponível."
                stopLiveMicTranscription()
            }
            // Sink do worker: ACK mesmo se a Activity morrer antes do post.
            // Pela INSTÂNCIA do job — sem lookup em campo mutável.
            sinkDoJob.concluir("LIVE_TERMINAL")
            return
        }

        // R3/B2: geração capturada no início do loop ao vivo (job do live);
        // o load usa a geração do JOB, não a global lida tarde demais.
        // R56/B01-B03 (nota11 §1) — a geração vem da ADMISSÃO (`geracaoJob`),
        // nunca de `currentSessionGeneration`: reler a global aqui fazia carga,
        // liveStamp e ACKs internos dependerem do valor vivo, enquanto o
        // `finally` já usava a geração da admissão. A geração atual só pode ser
        // consultada para COMPARAR validade (`isCurrent`), jamais para escolher
        // o dono deste job.
        val sessionGeneration = geracaoJob
        // R13/F2-fix: o stop do usuário cancela O JOB DESTE laço. A geração e o
        // pedido ficam ligados ao job (não à geração global) e são limpos no
        // terminal; o pedido é idempotente (clique duplo/notificação).
        liveMicGeneration = sessionGeneration
        liveStopRequest = LiveStopRequest(
            cancelNative = { gen -> whisperSession.cancel(gen) },
            cancelPipeline = { liveMicPipeline?.cancel() },
            onForwarded = { gen ->
                liveEvent(
                    "live_stop_cancel_forwarded",
                    gen,
                    fields = "\"owner\":${whisperSession.bindingOwnerOf(gen)}"
                )
            },
        )
        // R7/A1: abre o job NO PRODUTOR (job_id novo por job — parar e recomeçar
        // na MESMA Activity não reusa id) e registra o início com identidade.
        val liveJobId = liveStamp.beginJob(sessionGeneration)
        // R8/A1: identidade IMUTÁVEL do job (run/act/job/gen) — usada em TODAS
        // as saídas deste job; um sucessor NÃO muda este carimbo.
        val jobIdentity = liveStamp.identitySnapshot(sessionGeneration)
        val jobStartedAt = SystemClock.elapsedRealtime()
        var jobPhase = LiveJobPhase.PREPARATION
        var jobFailure: String? = null
        // R8/A1: EMISSOR TERMINAL ÚNICO — qualquer saída do job (falha, cancel,
        // sucesso, exceção) passa por aqui; exatamente UM live_terminal por job.
        val terminalEmitter = LiveJobTerminalEmitter(
            identity = jobIdentity,
            emitEvent = { name, identity, fields -> emitLiveEventWithIdentity(name, identity, fields) },
        )
        liveEvent(
            "live_job_start",
            sessionGeneration,
            fields = "\"job_id_local\":$liveJobId,\"sample_rate\":$sampleRate"
        )
        // R8/A2: marcador `faJobBegin` no log nativo — delimita o estado FA do
        // job DO AO VIVO (leitura posterior nunca herda o job anterior).
        whisperSession.faJobBegin("live")
        try {
        // R4/C3: atraso injetável ANTES do load (somente lab; 0 em produção) —
        // alimenta os cenários de cancel/sair DURANTE o load.
        jobPhase = LiveJobPhase.LOAD
        val loadDelay = WhisperLabSeams.modelLoadDelayMs()
        if (loadDelay > 0) {
            try { Thread.sleep(loadDelay) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
        val modelLoaded = synchronized(modelLoadLock) {
            ensureModelLoaded(model, backend, settings.flashAttention, sessionGeneration)
        }
        if (!modelLoaded.ok) {
            // R5/A1: diagnóstico capturado no WORKER e atrelado à operação; o
            // post abaixo só entrega DADOS IMUTÁVEIS e re-checa o lease/destroy
            // NA EXECUÇÃO — nada de lastError na main (g_mutex) e nada de
            // montar string de erro do sucessor.
            val reason = modelLoaded.failureText.ifBlank { "Falha ao carregar modelo para tempo real." }
            // R8/A1: se a carga foi recusada PORQUE o job foi cancelado/encerrado
            // (destroy, novo job, stop), o desfecho é CANCEL — erro continua erro,
            // cancel continua cancel.
            val supersededOnRefusal = when {
                isDestroyed -> "destroy da Activity durante a carga"
                !whisperSession.isCurrent(sessionGeneration) -> "cancel/destroy (lease invalidado) durante a carga"
                liveStamp.currentJobId() != liveJobId -> "novo job iniciado nesta Activity durante a carga"
                !liveMicActive -> "cancel/stop durante a carga"
                else -> null
            }
            jobFailure = if (supersededOnRefusal != null) null else "carga recusada: $reason"
            liveEvent(
                "live_load_refused",
                sessionGeneration,
                fields = "\"current_gen\":${whisperSession.currentGeneration()}," +
                    "\"still_current\":${whisperSession.isCurrent(sessionGeneration)}," +
                    "\"current_activity\":${!isDestroyed}," +
                    "\"superseded\":\"${(supersededOnRefusal ?: "").replace("\"", "'")}\"," +
                    "\"reason\":\"${reason.replace("\"", "'")}\""
            )
            // R8/A1: TERMINAL do job MESMO com o pipeline jamais criado —
            // contadores ZERO (nunca inventados nem do sucessor).
            terminalEmitter.emit(
                outcome = if (supersededOnRefusal != null) LiveJobOutcome.CANCELLED else LiveJobOutcome.ERROR,
                reason = if (supersededOnRefusal != null) "cancel durante a carga: $supersededOnRefusal"
                         else "carga recusada: $reason",
                phase = LiveJobPhase.LOAD,
                durationMs = SystemClock.elapsedRealtime() - jobStartedAt,
                decodeWindows = 0,
                decodeWallMs = 0L,
                summary = null,
            )
            runOnUiThread {
                if (isDestroyed) {
                    liveEvent("stale_post_dropped", sessionGeneration, fields = "\"kind\":\"live_load_refused\",\"why\":\"destroyed\"")
                    return@runOnUiThread
                }
                val ran = whisperSession.runIfCurrent(sessionGeneration) {
                    status.text = "Erro no modelo: $reason"
                    stopLiveMicTranscription()
                }
                if (!ran) {
                    liveEvent("stale_post_dropped", sessionGeneration, fields = "\"kind\":\"live_load_refused\",\"why\":\"stale\"")
                }
            }
            // Sink do worker: a carga falhou/foi recusada, mas o job terminou —
            // o ACK não pode depender do post de UI.
            sinkDoJob.concluir("LIVE_TERMINAL")
            return
        }
        liveEvent(
            "model_ready",
            sessionGeneration,
            fields = "\"owner\":${whisperSession.bindingOwnerOf(sessionGeneration)},\"delay_ms\":$loadDelay"
        )

        // R8/A1: re-checagem PÓS-CARGA em CADA saída assíncrona — a carga pode
        // terminar DEPOIS de um cancel/destroy/novo job. Cancel durante a carga
        // SUPRIME o início da captura, NÃO publica sucesso e NÃO libera contexto
        // de dono novo (nada de release aqui: o lease do sucessor é intocado).
        // Sem promessa de preempção do driver: a carga nativa conclui por si; o
        // que se garante é o cleanup seguro + a VISIBILIDADE do resultado do job.
        jobPhase = LiveJobPhase.CAPTURE
        val superseded = when {
            isDestroyed -> "destroy da Activity durante a carga"
            !whisperSession.isCurrent(sessionGeneration) -> "cancel/destroy (lease invalidado) durante a carga"
            liveStamp.currentJobId() != liveJobId -> "novo job iniciado nesta Activity durante a carga"
            !liveMicActive -> "cancel/stop durante a carga"
            else -> null
        }
        if (superseded != null) {
            jobFailure = null
            liveEvent(
                "live_superseded_after_load",
                sessionGeneration,
                fields = "\"why\":\"${superseded.replace("\"", "'")}\"," +
                    "\"still_current\":${whisperSession.isCurrent(sessionGeneration)}," +
                    "\"current_job\":${liveStamp.currentJobId()},\"job\":$liveJobId"
            )
            terminalEmitter.emit(
                outcome = LiveJobOutcome.CANCELLED,
                reason = "cancel durante a carga: $superseded",
                phase = LiveJobPhase.LOAD,
                durationMs = SystemClock.elapsedRealtime() - jobStartedAt,
                decodeWindows = 0,
                decodeWallMs = 0L,
                summary = null,
            )
            return
        }

        // D3/R2: registra o FA efetivo (aplicado ou não + motivo) do caminho
        // ao vivo — a decisão de capacidade é do JNI; aqui só se reporta.
        // R5/A1: usa o log CAPTURADO na própria carga (worker), não o global.
        FlashAttentionNotice.message(settings.flashAttention, backend.nativeKind, modelLoaded.loadLog)?.let { faMessage ->
            Log.i(TAG, faMessage)
        }

        runOnUiThread {
            status.text = "Ouvindo e transcrevendo ao vivo... (${liveSettings.describe()})"
        }

        var recorder: AudioRecord? = null
        // R5/A3: handle LOCAL do job — o onDestroy pode zerar o campo
        // compartilhado liveMicPipeline; o terminal/cleanup do loop NUNCA pode
        // ser pulado por isso.
        var pipelineLocal: LivePcmPipeline<LiveChunkResult>? = null
        // R6/D2: ESTRATÉGIA do caminho ao vivo — janela deslizante com contexto
        // anterior limitado (default do produto; números = hipóteses iniciais
        // medidas na fase D). O lab pode fixar o baseline 2 s (diagnóstico) ou
        // outra janela via seam; nada disso existe no APK normal.
        // R7/E3: ESTRATÉGIA do caminho ao vivo — (1)/(2) blocos fixos (2 s do
        // controle, 4+4 do R6) e (3) VAD segmentando intervalos com padding e
        // overlap contextual. O lab pode fixar qualquer uma via seam; o APK
        // normal usa SEMPRE [DEFAULT_LIVE_STRATEGY] (nada externo aciona isto).
        val strategy = WhisperLabSeams.liveStrategySpec()
            ?: WhisperLabSeams.liveWindowSpec()?.let { LiveStrategy.Fixed(it.uniqueMs, it.contextMs) }
            ?: DEFAULT_LIVE_STRATEGY
        val contextMs = when (strategy) {
            is LiveStrategy.Fixed -> strategy.contextMs
            is LiveStrategy.VadSegment -> strategy.contextMs
        }
        val chunkSamples = when (strategy) {
            is LiveStrategy.Fixed -> (sampleRate * strategy.uniqueMs / 1000).toInt()
            // No VAD a janela é o TRECHO DE FALA (≤ máximo); o chunk nominal é
            // o teto — o corte real é decisão do planejador.
            is LiveStrategy.VadSegment -> (sampleRate * strategy.maxSegmentMs / 1000).toInt()
        }.coerceAtLeast(1)
        val contextSamples = (sampleRate * contextMs / 1000).toInt()
        liveWindowDesc = strategy.describe()
        liveEvent(
            "live_window_spec",
            sessionGeneration,
            fields = "\"strategy\":\"${strategy::class.simpleName?.lowercase() ?: "?"}\"," +
                "\"unique_ms\":$chunkSamples,\"context_ms\":$contextMs," +
                "\"chunk_samples\":$chunkSamples,\"context_samples\":$contextSamples," +
                "\"strategy_desc\":\"${strategy.describe()}\""
        )
        // R6/D2: commit de texto do job (provisório×comprometido) — instância
        // NOVA por job; cancel não publica provisório; stop/EOF drenam a cauda.
        val committer = LiveTextCommitter()
        liveTextCommitter = committer
        liveFirstSpeechMs = -1L
        liveFirstPartialMs = -1L
        liveFirstIncrementalMs = -1L
        liveIncrementalShown = false
        liveLastPublishedText = null
        liveDecodeWallMsTotal = 0L
        liveDecodeWindowsCount = 0
        liveRepairAttemptIds.reset()

        try {
            val source: LivePcmSource
            if (injectedSource != null) {
                source = injectedSource
            } else {
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    jobFailure = "permissão do microfone não concedida (fase=captura)"
                    runOnUiThread {
                        status.text = "Permissão do microfone não concedida."
                        stopLiveMicTranscription()
                    }
                    sinkDoJob.concluir("LIVE_TERMINAL")
                    return
                }
                val recordBufferSize = maxOf(minBuffer * 2, sampleRate * 2)
                recorder = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, channelConfig, encoding, recordBufferSize)
                liveMicAudioRecord = recorder
                recorder.startRecording()
                source = AudioRecordPcmSource(recorder)
            }

            // R3/C1: coordenador ÚNICO (mesma rota do laboratório): leitor ->
            // SPOOL temporário -> worker. Nada de descartar o mais antigo: o
            // aceito é preservado; perdas só por falha/limite, QUANTIFICADAS.
            // R4/C1: resultado TIPADO por chunk — silêncio NUNCA é erro; erro
            // NUNCA é silêncio; falha conta em failedSamples e o job CONTINUA
            // (o trecho já aceito é conservado).
            // R7/E3: (3) VAD segmentando intervalos — o planejador decide o
            // corte; cada intervalo decidido vira EVENTO (mapa do áudio em
            // amostras originais) e o que não é decodificado é contado.
            val planner: LiveWindowPlanner? = when (strategy) {
                is LiveStrategy.VadSegment -> LiveVadSegmenter(
                    sampleRate = sampleRate,
                    padMs = strategy.padMs,
                    silenceCutMs = strategy.silenceCutMs,
                    maxSegmentMs = strategy.maxSegmentMs,
                    relativePeakFraction = strategy.relativePeakFraction,
                    absoluteRmsFloor = strategy.absoluteRmsFloor,
                    // R8/D (candidata 1): 1ª janela curta/adaptativa (0 = desligado).
                    firstSegmentMs = strategy.firstSegmentMs,
                    firstSilenceCutMs = strategy.firstSilenceCutMs,
                    record = { iv ->
                        liveEvent(
                            "live_vad_interval",
                            sessionGeneration,
                            fields = "\"start_sample\":${iv.startSample},\"end_sample\":${iv.endSample}," +
                                "\"samples\":${iv.endSample - iv.startSample},\"kind\":\"${iv.kind}\""
                        )
                    }
                )
                is LiveStrategy.Fixed -> null
            }
            // R14/A2 — IDENTIDADE DO PRODUTOR NO LIVE: o job ao vivo passa a
            // publicar no MESMO ledger do job de arquivo, com job/owner/
            // tentativa/terminal. Antes disto o live só existia no widget
            // (widget_sem_identidade, wer_eligivel=false) e nenhum ensaio
            // conseguia provar pertencimento do resultado.
            val liveJobId = TranscriptionJobResults.ledger.beginJob(
                LiveEventIdentity.runId, activityInstanceId, sessionGeneration
            )
            val liveAttemptKey = TranscriptionJobResults.ledger.beginAttempt(
                liveJobId, sessionGeneration, "live-pcm", LiveEventIdentity.runId, activityInstanceId
            )
            val coordinator = LiveTranscriptionCoordinator<LiveChunkResult>(
                spoolDir = cacheDir,
                chunkSamples = chunkSamples,
                contextSamples = contextSamples,
                planner = planner,
                readBufferBytes = minBuffer.coerceAtLeast(4096),
                processChunk = { chunk -> transcribeLiveChunk(chunk, liveSettings, sessionGeneration) },
                silenceCheck = { it is LiveChunkResult.NoSpeech },
                failureCheck = { it is LiveChunkResult.Failed },
                maxSpoolBytes = WhisperLabSeams.spoolLimitBytes() ?: DEFAULT_LIVE_SPOOL_MAX_BYTES,
                deliver = { chunk, result, ok ->
                    // R4/C3: falha de listener injetada (lab) atravessa o MESMO
                    // listener do produto — o pipeline tem de sobreviver e ainda
                    // garantir terminal/cleanup.
                    if (WhisperLabSeams.listenerFaultFor(chunk.index)) {
                        throw RuntimeException("lab: falha de listener injetada (chunk ${chunk.index})")
                    }
                    if (ok && result != null) {
                        liveChunkTally?.count(result)
                        when (result) {
                            is LiveChunkResult.Success -> {
                                // R6/D2: commit por alinhamento (contexto×único);
                                // provisório é revisável, comprometido não.
                                val info = committer.onWindow(
                                    chunk.index, result.text,
                                    chunk.uniqueStartSample, chunk.uniqueEndSample,
                                    chunk.contextSamplesInChunk,
                                    segments = result.segments,
                                    generation = sessionGeneration,
                                    // R8/B: áudio da janela p/ preservar região retida
                                    // (re-decode restrito; cópia limitada no seam).
                                    windowPcm = chunk.pcm
                                )
                                if (info != null) {
                                    // R13/F1 — TELEMETRIA ADITIVA do replay: os
                                    // segmentos REAIS (texto+ms) que alimentaram
                                    // o commit, o sha256 do PCM entregue e o
                                    // word_times explícito. Só no lab; nenhum
                                    // destes campos é lido pelo pipeline.
                                    val replayFields = if (WhisperLabSeams.isLab()) {
                                        LiveTelemetry.join(
                                            LiveTelemetry.segmentsField(result.segments),
                                            LiveTelemetry.wordTimesField(null),
                                            LiveTelemetry.pcmField(chunk.pcm),
                                        )
                                    } else ""
                                    liveEvent(
                                        "live_commit",
                                        sessionGeneration,
                                        chunk.index,
                                        fields = "\"added\":${info.committedWordsAdded}," +
                                            "\"provisional\":${info.provisionalWords},\"committed_words\":${info.committedWordCount}," +
                                            "\"alignment\":\"${info.alignment}\"" +
                                            (if (replayFields.isEmpty()) "" else ",$replayFields")
                                    )
                                    // R8/B: contabilidade por região (revisões, retidas,
                                    // resoluções, reparo) — o analisador acompanha sem
                                    // tocar no lock do committer.
                                    liveEvent(
                                        "live_commit_b",
                                        sessionGeneration,
                                        chunk.index,
                                        fields = "\"revised\":${info.revisedWords},\"held\":${info.heldWords}," +
                                            "\"conflicts_held\":${info.conflictsHeld},\"held_resolved\":${info.heldResolved}," +
                                            "\"held_hypotheses\":${info.heldHypotheses},\"pending_repair\":${info.pendingRepairRegions}," +
                                            "\"repair_attempts\":${info.repairAttempts},\"repair_ok\":${info.repairCompleted}," +
                                            "\"repair_fail\":${info.repairFailed},\"retained_bytes\":${info.retainedAudioBytes}"
                                    )
                                }
                                val elapsed = SystemClock.elapsedRealtime() - liveMicStartedAt
                                if (liveFirstPartialMs < 0L) {
                                    val display = committer.displayText()
                                    if (display.isNotEmpty()) {
                                        liveFirstPartialMs = elapsed
                                        val sinceSpeech = if (liveFirstSpeechMs >= 0L) elapsed - liveFirstSpeechMs else -1L
                                        liveEvent(
                                            "live_first_partial",
                                            sessionGeneration,
                                            chunk.index,
                                            fields = "\"ms_job\":$elapsed,\"ms_desde_1a_fala\":$sinceSpeech," +
                                                "\"words\":${committer.committedWordCount() + committer.provisionalWordCount()}"
                                        )
                                    }
                                }
                                runOnUiThread {
                                    // R2/B2: re-checagem na EXECUÇÃO do post (o chunk pode ter
                                    // terminado depois de uma nova sessão começar).
                                    whisperSession.runIfCurrent(sessionGeneration) {
                                        // R8/D — clock de PUBLICAÇÃO na UI (executou na main).
                                        publishLiveTextNow(sessionGeneration, LiveTextPublication.SOURCE_COMMIT, chunk.index)
                                    }
                                }
                                // R8/B (R8/D-fix): a re-decodificação RESTRITA saiu
                                // do deliver — o deliver roda SOB o lock do pipeline
                                // (LivePcmPipeline.workerLoop) e o reparo de ~5,5 s
                                // aqui DENTRO estancava o feed do leitor (medido no
                                // d3pre: 4 stalls de 5,4–5,7 s, déficit 13,8 s).
                                // Agora o passe roda no início de transcribeLiveChunk
                                // e no terminal — FORA do lock, mesma thread do
                                // worker (decodes serializados, captura não bloqueia).
                            }
                            is LiveChunkResult.Failed -> {
                                // Erro de chunk NÃO encerra o job: trecho aceito
                                // conservado; motivo visível no terminal/status.
                                appendLiveTerminal(sessionGeneration, "chunk ${chunk.index}: ERRO — ${result.reason}")
                                postIfCurrent(sessionGeneration) { status.text = "Falha em trecho ao vivo: ${result.reason}" }
                            }
                            is LiveChunkResult.Cancelled ->
                                appendLiveTerminal(sessionGeneration, "chunk ${chunk.index}: cancelado (${result.reason})")
                            is LiveChunkResult.NoSpeech -> {}
                        }
                    } else if (!ok) {
                        appendLiveTerminal(sessionGeneration, "chunk ${chunk.index}: EXCEÇÃO no worker (contada em falha)")
                    }
                },
                terminal = { cancelled ->
                    liveWasCancelled = cancelled
                    val c = liveMicPipeline?.counters()
                    // R8/D (integridade): o reparo DEFERIDO do ÚLTIMO chunk roda
                    // AQUI (terminal = pós-loop do worker, FORA do lock) ANTES do
                    // finalize — o texto reparado entra no TEXTO FINAL; cancel
                    // não repara (o cancel() descarta o retido).
                    if (!cancelled) {
                        val repairCommitter = liveTextCommitter
                        if (repairCommitter != null && repairCommitter.pendingRepairCount() > 0) {
                            runLiveRepairPass(repairCommitter, liveSettings, sessionGeneration)
                        }
                    }
                    // R6/D2: stop/EOF DRENAM a cauda provisória; cancel NÃO a
                    // publica (o comprometido permanece).
                    val finInfo = if (cancelled) {
                        committer.cancel()
                        null
                    } else {
                        committer.finalize()
                    }
                    // R9/D2: CONTRATO de resultado PARCIAL por região (estados ≠).
                    val regOut = committer.regionOutcome()
                    // R14/A2 — PUBLICAÇÃO NO LEDGER com identidade do produtor:
                    // terminal + texto realmente comprometido. Cancel mantém no
                    // máximo a parcial válida DESTE job (nunca o texto do widget
                    // nem o resultado de um job anterior). Recusa (null) é
                    // silenciosa: significa que outro job tomou a posse.
                    if (liveAttemptKey != null) {
                        val committedNow = committer.committedText()
                        if (committedNow.isNotBlank()) {
                            runCatching {
                                TranscriptionJobResults.ledger.appendSegment(liveAttemptKey, committedNow)
                            }
                        }
                        // R16/B2 — TERMINAL TIPADO a partir dos fatos do committer.
                        // Nao cancelado NAO significa sucesso: no-speech, reparo
                        // falho e regiao pendente tem estados proprios.
                        val temPendencia = !regOut.integral ||
                            regOut.promotedRepairFailed > 0 ||
                            regOut.promotedAudioMissing > 0 ||
                            regOut.promotedNoRepair > 0 ||
                            regOut.promotedRepairInvalidEvidence > 0
                        val factsLive = LiveTerminalPolicy.Facts(
                            cancelled = cancelled,
                            integral = regOut.integral,
                            repairFailed = regOut.promotedRepairFailed,
                            audioMissing = regOut.promotedAudioMissing,
                            noRepair = regOut.promotedNoRepair,
                            invalidEvidence = regOut.promotedRepairInvalidEvidence,
                            semTexto = committedNow.isBlank(),
                            // R17/B2 — fatos do PIPELINE que o committer não vê:
                            // chunk falho, perda de spool e amostras pendentes.
                            failedSamples = c?.failedSamples ?: 0L,
                            lostSamples = c?.lostSamples ?: 0L,
                            pendingSamples = c?.pendingSamples() ?: 0L,
                        )
                        val terminalLive = LiveTerminalPolicy.decide(factsLive)
                        val statusLive = LiveTerminalPolicy.nativeStatus(terminalLive)
                        liveEvent(
                            "live_terminal_tipado",
                            sessionGeneration,
                            fields = "\"terminal\":\"${terminalLive.wire}\"," +
                                "\"pendencia\":$temPendencia," +
                                "\"reparo_falho\":${regOut.promotedRepairFailed}," +
                                "\"integral\":${regOut.integral}," +
                                "\"texto_vazio\":${committedNow.isBlank()}," +
                                // R17/B2 — fatos do pipeline no MESMO evento
                                // que decide o terminal.
                                "\"falha_pipeline\":${factsLive.falhaPipeline}," +
                                "\"failed_samples\":${factsLive.failedSamples}," +
                                "\"lost_samples\":${factsLive.lostSamples}," +
                                "\"pending_samples\":${factsLive.pendingSamples}," +
                                "\"motivo\":\"${LiveTerminalPolicy.motivo(factsLive)}\""
                        )
                        // R18/D1 — FECHO DA CAPTURA na fronteira do terminal
                        // REAL. Antes disto ninguém chamava `fechar()`: toda
                        // captura ficava sem `final`/`capture_end` e o leitor
                        // (corretamente) a classificava INCOMPLETE.
                        runCatching {
                            CommitCapture.fechar(
                                motivo = LiveTerminalPolicy.motivo(factsLive),
                                terminal = terminalLive.wire,
                                texto = committedNow
                            )
                        }.onFailure { Log.w(TAG, "capture_fechar_falhou: $it") }
                        val pub = runCatching {
                            TranscriptionJobResults.ledger.publishJob(
                                liveJobId,
                                sessionGeneration,
                                terminalLive,
                                // R15/A4 — o `nativeStatus` tem de satisfazer o
                                // CONTRATO do audit ("ok"/"cancelled"); a R14
                                // mandava "live-success"/"live-cancelled", o que
                                // fazia o export marcar o sucesso como
                                // INCOERENTE (motivo=incoerente, wer_eligivel=false).
                                // A ROTA (live) fica como metadado separado, no
                                // evento e no log — não no status nativo.
                                nativeStatus = statusLive,
                            )
                        }.getOrNull()
                        liveEvent(
                            "live_ledger_publish",
                            sessionGeneration,
                            fields = "\"job_id\":$liveJobId,\"publicado\":${pub != null}," +
                                "\"rota\":\"live\"," +
                                // R17/B2 — o evento publica o terminal TIPADO,
                                // não o `cancelled ? CANCELLED : SUCCESS` antigo.
                                "\"terminal\":\"${terminalLive.wire}\"," +
                                "\"status_nativo\":\"$statusLive\"," +
                                "\"motivo\":\"${LiveTerminalPolicy.motivo(factsLive)}\"," +
                                "\"audit_vazio\":${pub?.audit()?.isEmpty() ?: false}," +
                                "\"wer_eligivel\":${pub?.werEligible ?: false}," +
                                "\"chars\":${committedNow.length}"
                        )
                    }
                    logFaRoute("live")
                    val genNow = sessionGeneration
                    // R8/D: se o overlay INCREMENTAL apareceu, cancel o derruba
                    // (a parcial provisória nunca sobrevive a um cancelamento).
                    if (cancelled && liveIncrementalShown) {
                        runOnUiThread {
                            if (!isDestroyed && whisperSession.isCurrent(genNow)) {
                                publishLiveTextNow(
                                    genNow,
                                    LiveTextPublication.SOURCE_CANCEL_DROP
                                )
                            }
                            liveIncrementalShown = false
                        }
                    }
                    // R8/D (integridade): TERMINAL NORMAL publica o texto FINAL do
                    // committer — promoção do que resolveu/reparou/drenou DEPOIS
                    // da última janela. Sem isto, w01 (palavra única), f05a/f03
                    // (cauda pós-fala) e d01-inject (frase re-decodificada)
                    // terminavam invisíveis no texto final.
                    if (!cancelled) {
                        runOnUiThread {
                            publishLiveTextNow(
                                genNow,
                                LiveTextPublication.SOURCE_TERMINAL,
                                terminalNormal = true,
                            )
                        }
                    }
                    liveEvent(
                        "live_text_final",
                        genNow,
                        fields = "\"cancelled\":$cancelled,\"committed_words\":${committer.committedWordCount()}," +
                            "\"committed_len\":${committer.committedText().length}," +
                            "\"display_len\":${committer.displayText().length}," +
                            "\"held_words\":${committer.heldWordCount()},\"held_hypotheses\":${committer.heldHypothesisCount()}," +
                            "\"pending_repair\":${committer.pendingRepairCount()}," +
                            "\"unresolved_regions\":${finInfo?.unresolvedRegionsClosed ?: -1}," +
                            // R9/D2: CONTRATO de resultado PARCIAL por região (estados ≠).
                            "\"regions_resolved_confirmed\":${regOut.resolvedConfirmed}," +
                            "\"regions_resolved_by_repair\":${regOut.resolvedByRepair}," +
                            "\"regions_promoted_unconfirmed\":${regOut.promotedUnconfirmed}," +
                            "\"regions_promoted_unconfirmed_words\":${regOut.promotedUnconfirmedWords}," +
                            "\"regions_promoted_repair_partial\":${regOut.promotedRepairPartial}," +
                            "\"regions_promoted_repair_failed\":${regOut.promotedRepairFailed}," +
                            // R10/E1: reparo recusado por evidência de tempo inválida (tipado).
                            "\"regions_promoted_repair_invalid_evidence\":${regOut.promotedRepairInvalidEvidence}," +
                            "\"regions_promoted_audio_missing\":${regOut.promotedAudioMissing}," +
                            "\"regions_promoted_no_repair\":${regOut.promotedNoRepair}," +
                            "\"text_integral\":${regOut.integral}," +
                            "\"pre_first_ms\":$liveFirstPartialMs,\"fs_ms\":$liveFirstSpeechMs," +
                            "\"pre_inc_first_ms\":$liveFirstIncrementalMs," +
                            "\"decode_wall_ms\":$liveDecodeWallMsTotal,\"decode_windows\":${liveDecodeWindowsCount}," +
                            "\"repair_invalid_evidence\":${committer.repairInvalidEvidenceCount()}," +
                            // R10/E3: contabilidade PCM do job (o mesmo resumo do Log.i) e
                            // PCM preservado para reparo — export integral por job.
                            "\"retained_audio_bytes\":${committer.retainedAudioBytes()}," +
                            "\"pcm_counters\":\"${jsonEscape(c?.summary() ?: "")}\""
                    )
                    // R9/D2: terminal de PIPELINE com sucesso ≠ texto integralmente
                    // resolvido. Estado parcial é TIPADO e VISÍVEL (nunca mudo).
                    if (!regOut.integral || (finInfo != null && finInfo.unresolvedRegionsClosed > 0)) {
                        appendLiveTerminal(
                            genNow,
                            "texto: RESULTADO PARCIAL — ${regOut.summary()}" +
                                (if (finInfo != null && finInfo.unresolvedRegionsClosed > 0)
                                    " (${finInfo.unresolvedRegionsClosed} região(ões) promovida(s) no terminal sem confirmação)"
                                else "")
                        )
                    }
                    Log.i(
                        TAG,
                        "live pcm finalizado (cancelled=$cancelled) counters=${c?.summary()} balanced=${c?.isBalanced()}"
                    )
                },
            )
            pipelineLocal = coordinator.pipeline
            liveMicPipeline = pipelineLocal
            coordinator.start()
            // R8/D — clock de CAPTURA: instante em que o leitor COMEÇA a puxar
            // áudio (depois da carga do modelo, antes da 1ª leitura).
            liveEvent(
                "clock_capture_start",
                sessionGeneration,
                fields = "\"strategy\":\"${strategy::class.simpleName?.lowercase() ?: "?"}\"," +
                    "\"run_ms\":${SystemClock.elapsedRealtime() - jobStartedAt}"
            )

            val outcome = coordinator.runReader(source, { liveMicActive })
            liveReaderReason = outcome.reason
            // R8/D — clock de EOF do leitor (fim da fonte) + tamanho lido.
            liveEvent(
                "clock_reader_eof",
                sessionGeneration,
                fields = "\"reason\":\"${outcome.reason}\",\"samples_read\":${outcome.samplesRead}," +
                    "\"odd_bytes\":${outcome.oddBytesDropped},\"fragment_dropped\":${outcome.fragmentDropped}"
            )
            appendLiveTerminal(
                sessionGeneration,
                "leitor terminou: reason=${outcome.reason} amostrasLidas=${outcome.samplesRead} " +
                    "fragmentoFinal=${outcome.fragmentDropped} oddBytes=${outcome.oddBytesDropped}"
            )
            Log.i(
                TAG,
                "live pcm leitor terminou: reason=${outcome.reason} samplesRead=${outcome.samplesRead} oddBytes=${outcome.oddBytesDropped}"
            )

            // R3/C1: stop com estado pendente VISÍVEL; a espera CONTINUA até o
            // worker TERMINAL — handle e controles não são liberados antes.
            coordinator.stopAndAwait(
                stopTimeoutMs = liveMicDrainTimeoutMs,
                onPending = { elapsed ->
                    Log.w(TAG, "live pcm: worker ainda ativo após ${elapsed}ms; aguardando o worker terminar (handle mantido).")
                    runOnUiThread {
                        if (!isDestroyed && liveMicStopping) status.text = LIVE_MIC_STOPPING_WORKER_LABEL
                    }
                },
            )
            val counters = coordinator.counters()
            Log.i(TAG, "live pcm counters: ${counters.summary()} balanced=${counters.isBalanced()}")
            if (counters.lossVisible() || !counters.isBalanced()) {
                Log.w(TAG, "live pcm: perdas visíveis no relatório — ${counters.summary()}")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error in live mic loop", e)
            // R8/A1: exceção do job vira FAILURE TIPADA (nunca terminal ausente).
            if (jobFailure == null) {
                jobFailure = "exceção no pipeline: ${e.javaClass.simpleName}: ${e.message ?: "sem mensagem"}"
            }
            if (liveMicActive) {
                runOnUiThread {
                    status.text = "Erro na gravação ao vivo: ${e.message}"
                }
            }
        } finally {
            // R3/C1: o handle/fonte só somem com o worker TERMINAL; no caminho
            // de exceção ainda drenamos com stop (contado) antes de liberar.
            // R5/A3: o handle vem do LOCAL do job (não do campo, que o
            // onDestroy pode ter zerado).
            val pipelineNow = pipelineLocal
            if (pipelineNow != null && !pipelineNow.isFinished()) {
                pipelineNow.requestStop()
                var waitedMs = 0L
                while (!pipelineNow.awaitFinished(1000)) {
                    waitedMs += 1000
                    Log.w(TAG, "live pcm: worker ainda ativo no teardown (${waitedMs}ms); aguardando.")
                }
            }
            // R4/C2: RETRATO TERMINAL preservado ANTES de apagar o pipeline —
            // é ele que a UI/relatório usam (nada de "finalizada" genérica).
            val countersNow = pipelineNow?.counters()
            var terminalSummary: LiveTerminalSummary? = null
            var terminalExtra = ""
            if (countersNow != null) {
                // R7/E3: retrato do PLANO (VAD) — o corte é evidência e vai ao
                // terminal (janelas, cortes forçados, silêncio contabilizado).
                val planStats = pipelineNow.planStats()
                val planSummary = if (planStats != null) {
                    "plano: janelas=${planStats.windows} cortesForcados=${planStats.forcedCuts} " +
                        "caudas=${planStats.tails} lacunas=${planStats.gaps} " +
                        "incertas=${planStats.uncertainWindows}(${planStats.uncertainSamples}amostras) " +
                        "silencioIgnorado=${planStats.skippedSilenceSamples}amostras"
                } else ""
                val snapshot = LiveTerminalSummary.of(
                    cancelled = liveWasCancelled,
                    durationMs = SystemClock.elapsedRealtime() - liveMicStartedAt,
                    counters = countersNow,
                    readerReason = liveReaderReason,
                    tally = liveChunkTally ?: LiveChunkTally(),
                    lastError = countersNow.workerError,
                    effective = listOfNotNull(
                        liveMicSettings?.describe().takeIf { !it.isNullOrBlank() },
                        liveWindowDesc.takeIf { it.isNotBlank() }
                    ).joinToString(" | "),
                    identity = jobIdentity.shortForm,
                    silenceSkippedSamples = countersNow.silenceSkippedSamples,
                    planSummary = planSummary,
                )
                liveTerminalSnapshot = snapshot
                terminalSummary = snapshot
                // Campos extras (compat R7): SÓ do pipeline DESTE job.
                terminalExtra = (planStats?.let {
                    "\"vad_windows\":${it.windows},\"vad_forced_cuts\":${it.forcedCuts}," +
                        "\"vad_tails\":${it.tails},\"vad_gaps\":${it.gaps}," +
                        "\"vad_uncertain_windows\":${it.uncertainWindows}," +
                        "\"vad_uncertain_samples\":${it.uncertainSamples}," +
                        "\"vad_silence_intervals\":${it.silenceIntervals.size},"
                } ?: "") +
                    "\"idx_peak\":${countersNow.indexHighWatermark},\"spool_peak\":${countersNow.spoolBytesPeak}," +
                    "\"pending_samples\":${countersNow.pendingSamples()}," +
                    "\"first_partial_ms\":$liveFirstPartialMs,\"first_speech_ms\":$liveFirstSpeechMs"
            }
            // R8/A1: TERMINAL ÚNICO do job — emitido para TODOS os desfechos,
            // inclusive quando o pipeline NUNCA existiu (cancel/erro na carga):
            // nesse caso os contadores são ZERO (nunca inventados nem tomados
            // do sucessor). Cancel continua cancel; erro continua erro.
            val terminalOutcome = when {
                liveWasCancelled -> LiveJobOutcome.CANCELLED
                jobFailure != null -> LiveJobOutcome.ERROR
                terminalSummary != null -> LiveJobTerminalEmitter.classify(terminalSummary!!)
                else -> LiveJobOutcome.CANCELLED
            }
            val terminalReason = when {
                liveWasCancelled -> "cancel/stop do job (fase=${jobPhase.wire})"
                jobFailure != null -> jobFailure!!
                terminalSummary != null -> terminalSummary!!.statusLine()
                else -> "job encerrado antes do pipeline (fase=${jobPhase.wire})"
            }
            terminalEmitter.emit(
                outcome = terminalOutcome,
                reason = terminalReason,
                phase = LiveJobPhase.TEARDOWN,
                durationMs = SystemClock.elapsedRealtime() - jobStartedAt,
                decodeWindows = liveDecodeWindowsCount,
                decodeWallMs = liveDecodeWallMsTotal,
                summary = terminalSummary,
                extraFields = terminalExtra,
            )
            // R8/A2: o aviso de FA passa a refletir a EXECUÇÃO do MESMO job
            // (delta real da SIG_FA_EXEC) — após o terminal, com identidade já
            // carimbada; nenhum contador do sucessor entra aqui.
            if (settings.flashAttention) {
                val faNow = runCatching { whisperSession.loadLogForDiagnostics() }.getOrDefault("")
                FlashAttentionNotice.executionSummary(true, backend.nativeKind, faNow)?.let { execLine ->
                    appendLiveTerminal(sessionGeneration, "[FA estado] $execLine")
                }
            }
            // R4/C2: cleanup do spool AGORA (worker terminal) e handle liberado.
            try { pipelineNow?.close() } catch (_: Throwable) {}
            liveMicPipeline = null
            // R5/A3: AudioRecord encerrado EXATAMENTE uma vez — se o onDestroy
            // já parou/liberou (e desregistrou do campo), o laço não libera de
            // novo; liberar duas vezes quem ainda é dono.
            if (recorder != null && liveMicAudioRecord === recorder) {
                try { recorder.stop() } catch (_: Throwable) {}
                try { recorder.release() } catch (_: Throwable) {}
                liveMicAudioRecord = null
            }
        }
        } catch (t: Throwable) {
            // R8/A1: exceção ANTES/DEPOIS do pipeline (ex.: falha de setup antes
            // do try interno) NUNCA deixa o job sem terminal — emite ERROR e
            // segue (é a thread do worker do job; a UI reflete via status).
            Log.e(TAG, "Error in live mic job (outer)", t)
            if (jobFailure == null) {
                jobFailure = "exceção no job: ${t.javaClass.simpleName}: ${t.message ?: "sem mensagem"}"
            }
            terminalEmitter.emit(
                outcome = LiveJobOutcome.ERROR,
                reason = jobFailure!!,
                phase = jobPhase,
                durationMs = SystemClock.elapsedRealtime() - jobStartedAt,
                decodeWindows = 0,
                decodeWallMs = 0L,
                summary = null,
            )
        }
    }

    /** R8/A1: emite um evento do caminho ao vivo com a identidade IMMUTABLE do
     * job (run/act/job/gen do snapshot) — saídas tardias de um job NÃO são
     * carimbadas com a identidade do sucessor. seq/mono continuam do carimbo. */
    private fun emitLiveEventWithIdentity(name: String, identity: LiveJobIdentity, fields: String) {
        val header = liveStamp.headerFor(identity)
        val extras = fields.removePrefix("{").removeSuffix("}")
        val body = if (extras.isBlank()) "{$header}" else "{$header,$extras}"
        WhisperLabSeams.event(name, body)
    }

    /** R4/C1 (+R7/A2): inferência de UM chunk do job ao vivo — roda na thread do
     * worker do pipeline. Resultado TIPADO: Success(texto), NoSpeech (silêncio
     * legítimo do nativo OU portão de energia só-zero-digital), Failed (status
     * error/perdido, exceção, chunkWav — conta em failedSamples) e Cancelled.
     * TODOS os settings do snapshot vão ao JNI — nada fixado em beam=1/bestOf=1/
     * timestamps=false/vad=false. */
    private fun transcribeLiveChunk(
        chunk: LivePcmPipeline.Chunk,
        liveSettings: LiveJobSettings,
        sessionGeneration: Long,
    ): LiveChunkResult {
        // R8/D (integridade/cadência): REPARO DEFERIDO — o passe de reparo do
        // chunk ANTERIOR roda AQUI (thread do worker, FORA do lock do pipeline).
        // O deliver roda sob o lock; o reparo de ~5,5 s lá dentro estancava o
        // feed do leitor (d3pre: 4 stalls de 5,4–5,7 s → déficit 13,8 s). Aqui
        // fica serializado com os decodes (mesma thread) — nunca concorrente.
        val repairCommitter = liveTextCommitter
        if (repairCommitter != null && repairCommitter.pendingRepairCount() > 0) {
            runLiveRepairPass(repairCommitter, liveSettings, sessionGeneration)
            postIfCurrent(sessionGeneration) {
                publishLiveTextNow(sessionGeneration, LiveTextPublication.SOURCE_REPAIR)
            }
        }
        val pcm = chunk.pcm
        if (pcm.size < 2) return LiveChunkResult.NoSpeech("chunk vazio (0 amostras)")
        // R6/D2: medição por janela (clock monotônico) — inclui o portão.
        val t0 = SystemClock.elapsedRealtime()
        // R8/D — clock de INÍCIO do worker para o chunk (chegada real ao decode):
        // contra o clock_enqueue dá a espera de fila/backlog; contra o
        // clock_worker_end (o próprio live_window) dá o decode.
        liveEvent(
            "clock_worker_start",
            sessionGeneration,
            chunk.index,
            fields = "\"unique_start\":${chunk.uniqueStartSample},\"unique_end\":${chunk.uniqueEndSample}," +
                "\"t_start_mono\":$t0"
        )
        // R7/A2: portão SÓ-ZERO-DIGITAL — decide sem-fala apenas quando o PCM é
        // VÁLIDO (nº par de bytes, ≥1 amostra) e TODAS as amostras são zero.
        // RMS/pico são MEDIDOS como diagnóstico (evento), NUNCA critério de
        // descarte: fala fraca, onset curto e qualquer amostra não nula
        // (inclusive -32768) seguem para o modelo/VAD. Fragmento inválido
        // (nº ímpar de bytes) NÃO vira silêncio.
        val energy = LiveEnergyGate.measure(pcm)
        if (LiveEnergyGate.isDigitalSilence(energy)) {
            liveEvent(
                "live_window",
                sessionGeneration,
                chunk.index,
                fields = "\"unique_start\":${chunk.uniqueStartSample}," +
                    "\"unique_end\":${chunk.uniqueEndSample},\"context_samples\":${chunk.contextSamplesInChunk}," +
                    "\"decode_samples\":${chunk.sampleCount},\"wall_ms\":0,\"kind\":\"gate\"," +
                    "\"gate_reason\":\"zero-digital\",\"pcm_valid\":${energy.validPcm}," +
                    "\"rms\":${String.format(Locale.US, "%.2f", energy.rms)},\"peak\":${energy.peak},\"text_len\":0}"
            )
            return LiveChunkResult.NoSpeech(
                String.format(Locale.US, "zero-digital (%d amostras todas 0; pcm valido=%b)", energy.sampleCount, energy.validPcm)
            )
        }
        if (liveFirstSpeechMs < 0L) {
            // R7/A1: marcador honesto = 1ª JANELA ENERGÉTICA ENTREGUE ao worker
            // (passou o portão só-zero, está indo para o modelo) — NÃO é o
            // início da fala capturada nem latência da 1ª fala. Emitimos o nome
            // legado (live_first_speech) e o nome correto (first_window_ready);
            // o onset real de fala vem do fixture/PCM, não daqui.
            liveFirstSpeechMs = SystemClock.elapsedRealtime() - liveMicStartedAt
            val markerFields = "\"ms_job\":$liveFirstSpeechMs,\"unique_start\":${chunk.uniqueStartSample}"
            liveEvent("live_first_speech", sessionGeneration, chunk.index, fields = markerFields)
            liveEvent("first_window_ready", sessionGeneration, chunk.index, fields = markerFields)
        }

        val chunkWav = File(cacheDir, "live_chunk_${System.currentTimeMillis()}_${chunk.index}.wav")
        try {
            // R4/C3: atraso injetável (lab) para worker comprovadamente lento.
            val delayMs = WhisperLabSeams.chunkDelayMs()
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            writeWavFile(chunkWav, pcm, 16000)
            // R7/E2: segmentos REAIS do JNI (texto+ms) sobem para o commit como
            // evidência de INTERVALO. Só quando os tempos são relativos À JANELA
            // (sem VAD: com filtro os tempos são do áudio filtrado, não do
            // chunk) — senão o commit cai no caminho estimado.
            val chunkSegments = ArrayList<LiveSegmentTime>()
            val captureSegments = !liveSettings.vadFilter
            // R8/D (candidata 2): publicação INCREMENTAL — o texto de cada
            // onSegment VÁLIDO do job corrente vai à UI como parcial PROVISÓRIA,
            // aproveitando o MESMO decode (nenhuma inferência nova, nenhum
            // reload). O commit continua sendo do committer (reconciliação B):
            // no fim da janela o texto é substituído pelo display oficial e o
            // incremental morre; cancel descarta o overlay.
            val incEnabled = WhisperLabSeams.incrementalPartialEnabled()
            val incSegments = ArrayList<String>()
            val incOnce = java.util.concurrent.atomic.AtomicBoolean(false)
            val chunkCallback = object : WhisperNative.Callback {
                override fun onSegment(text: String, startMs: Long, endMs: Long) {
                    if (!captureSegments) return
                    val t = text.trim()
                    if (t.isEmpty() || startMs < 0 || endMs < startMs) return
                    chunkSegments.add(LiveSegmentTime(t, startMs, endMs))
                    if (!incEnabled) return
                    incSegments.add(t)
                    val incText = incSegments.joinToString(" ")
                    val cbGeneration = sessionGeneration
                    val cbChunk = chunk.index
                    runOnUiThread {
                        if (!isDestroyed && whisperSession.isCurrent(cbGeneration)) {
                            val base = liveTextCommitter?.displayText().orEmpty()
                            val merged = (if (base.isBlank()) incText else "$base $incText").trim()
                            if (merged.isNotEmpty()) {
                                liveTranscriptText.setText(merged)
                                liveTranscriptText.setSelection(liveTranscriptText.text.length)
                                liveLastPublishedText = merged
                                liveIncrementalShown = true
                                val isFirst = incOnce.compareAndSet(false, true)
                                if (liveFirstIncrementalMs < 0L) {
                                    liveFirstIncrementalMs = SystemClock.elapsedRealtime() - liveMicStartedAt
                                }
                                val sinceSpeech = if (liveFirstSpeechMs >= 0L) {
                                    SystemClock.elapsedRealtime() - liveMicStartedAt - liveFirstSpeechMs
                                } else -1L
                                liveEvent(
                                    "clock_ui_publish",
                                    cbGeneration,
                                    cbChunk,
                                    fields = "\"source\":\"incremental\",\"chars\":${merged.length}," +
                                        "\"first\":$isFirst,\"ms_job\":${SystemClock.elapsedRealtime() - liveMicStartedAt}," +
                                        "\"ms_desde_1a_fala\":$sinceSpeech"
                                )
                                if (isFirst) {
                                    liveEvent(
                                        "live_first_incremental_partial",
                                        cbGeneration,
                                        cbChunk,
                                        fields = "\"ms_job\":${liveFirstIncrementalMs}," +
                                            "\"ms_desde_1a_fala\":$sinceSpeech," +
                                            "\"ms_desde_capture_start\":${SystemClock.elapsedRealtime() - liveMicStartedAt}"
                                    )
                                }
                            }
                        }
                    }
                }

                override fun onProgress(progress: Int) {}

                override fun onNativeLog(line: String) {}
            }

            // R2/B2: par texto+status do mesmo job, numa única chamada.
            val (rawText, rawStatus) = synchronized(modelLoadLock) {
                // R18/C1 — início real do worker (caminho ao vivo).
                runCatching { WhisperJobService.markWorkerStarted(this@WhisperActivity, sessionGeneration) }
                whisperSession.transcribeAndStatus(
                    sessionGeneration,
                    chunkWav.absolutePath,
                    liveSettings.language,
                    liveSettings.beamSize,
                    liveSettings.bestOf,
                    liveSettings.wordTimestamps,
                    liveSettings.vadFilter,
                    liveSettings.vadModelPath,
                    chunkCallback
                )
            }
            // R4/C3: substituição injetada (lab) atravessa o MESMO parse e a
            // MESMA classificação tipada do produto — nunca vira null/silêncio.
            val (chunkText, chunkStatus) = WhisperLabSeams.overrideChunkResult(chunk.index, rawText, rawStatus)
            val outcome = when (val parsed = WhisperResultParser.parse(chunkText, chunkStatus)) {
                is WhisperTranscriptResult.Success ->
                    if (parsed.text.isBlank()) LiveChunkResult.NoSpeech("texto vazio com status ok")
                    else LiveChunkResult.Success(parsed.text, chunkSegments)
                WhisperTranscriptResult.NoSpeech -> LiveChunkResult.NoSpeech("no_speech do modelo")
                is WhisperTranscriptResult.Cancelled -> LiveChunkResult.Cancelled(parsed.message)
                is WhisperTranscriptResult.Error -> LiveChunkResult.Failed(parsed.message)
            }
            // R6/D2: medição por janela — wall real de inferência e desfecho.
            val wallMs = SystemClock.elapsedRealtime() - t0
            liveDecodeWallMsTotal += wallMs
            liveDecodeWindowsCount += 1
            val kind = when (outcome) {
                is LiveChunkResult.Success -> "ok"
                is LiveChunkResult.NoSpeech -> "silence"
                is LiveChunkResult.Failed -> "fail"
                is LiveChunkResult.Cancelled -> "cancel"
            }
            val textLen = (outcome as? LiveChunkResult.Success)?.text?.length ?: 0
            // R8/B: o TEXTO da janela vai ao JSONL do lab (artefato privado do
            // flavor; a produção usa o objeto inerte e nunca grava) — insumo da
            // auditoria de reconciliação por janela.
            val textField = (outcome as? LiveChunkResult.Success)?.text?.let { jsonEscape(it) } ?: ""
            liveEvent(
                "live_window",
                sessionGeneration,
                chunk.index,
                fields = "\"unique_start\":${chunk.uniqueStartSample}," +
                    "\"unique_end\":${chunk.uniqueEndSample},\"context_samples\":${chunk.contextSamplesInChunk}," +
                    "\"decode_samples\":${chunk.sampleCount},\"wall_ms\":$wallMs,\"kind\":\"$kind\",\"text_len\":$textLen," +
                    "\"text\":\"$textField\"," +
                    "\"t_start_mono\":$t0,\"t_end_mono\":${t0 + wallMs}"
            )
            return outcome
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to transcribe live chunk ${chunk.index}", e)
            return LiveChunkResult.Failed("excecao ao transcrever chunk: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            chunkWav.delete()
        }
    }

    /**
     * R8/B — PASSE de reparo restrito do committer: pega regiões retidas com
     * áudio preservado e re-decodifica SÓ o PCM recortado da região (nunca o
     * histórico inteiro), no máximo [LIVE_REPAIR_MAX_PER_WINDOW] por janela e
     * UMA tentativa por região. Roda na thread do worker (nunca sob o lock do
     * committer e nunca na main); cancel/geração velha fecham a região tipada.
     */
    private fun runLiveRepairPass(
        committer: LiveTextCommitter,
        liveSettings: LiveJobSettings,
        sessionGeneration: Long,
    ) {
        var passes = 0
        while (passes < LIVE_REPAIR_MAX_PER_WINDOW) {
            val req = committer.takeRepairRequest() ?: break
            passes++
            // R13/F1 — identidade do log: o MESMO regionId do committer + um
            // attempt_id por job, para casar pedido/resposta no replay. Não é
            // entrada de decisão: nada além do log lê este número.
            val attemptId = liveRepairAttemptIds.next()
            if (cancelRequested || !whisperSession.isCurrent(sessionGeneration)) {
                committer.failRepair(req.regionId, "cancel/geracao")
                break
            }
            val t0 = SystemClock.elapsedRealtime()
            val outcome = transcribeLiveRepairPcm(req, liveSettings, sessionGeneration)
            val wallMs = SystemClock.elapsedRealtime() - t0
            val ok = when (outcome) {
                is LiveChunkResult.Success -> committer.completeRepair(req.regionId, outcome.text, outcome.segments)
                is LiveChunkResult.NoSpeech -> committer.failRepair(req.regionId, "no_speech do reparo")
                is LiveChunkResult.Cancelled -> committer.failRepair(req.regionId, "cancelado")
                is LiveChunkResult.Failed -> committer.failRepair(req.regionId, "falha do reparo")
            }
            val repairTextField = (outcome as? LiveChunkResult.Success)?.text?.let { jsonEscape(it) } ?: ""
            val repairIdFields =
                if (WhisperLabSeams.isLab()) LiveTelemetry.repairFields(req.regionId, attemptId) + "," else ""
            liveEvent(
                "live_repair",
                sessionGeneration,
                req.windowIndex,
                fields = repairIdFields +
                    "\"region\":${req.regionId},\"start\":${req.startSample},\"end\":${req.endSample}," +
                    "\"bytes\":${req.pcm.size},\"words\":${req.wordCount},\"wall_ms\":$wallMs,\"ok\":$ok," +
                    "\"text\":\"$repairTextField\""
            )
        }
    }

    /** R8/B — re-decode do PCM PRESERVADO de uma região (mesma fiação JNI do
     * chunk: par texto+status, mesmo parse tipado e mesmos settings do job). */
    private fun transcribeLiveRepairPcm(
        req: LiveTextCommitter.RepairRequest,
        liveSettings: LiveJobSettings,
        sessionGeneration: Long,
    ): LiveChunkResult {
        val pcm = req.pcm
        if (pcm.size < 2) return LiveChunkResult.NoSpeech("reparo vazio (0 amostras)")
        val repairWav = File(cacheDir, "live_repair_${req.regionId}_${System.currentTimeMillis()}.wav")
        return try {
            writeWavFile(repairWav, pcm, 16000)
            val chunkSegments = ArrayList<LiveSegmentTime>()
            val captureSegments = !liveSettings.vadFilter
            val callback = object : WhisperNative.Callback {
                override fun onSegment(text: String, startMs: Long, endMs: Long) {
                    if (!captureSegments) return
                    val t = text.trim()
                    if (t.isEmpty() || startMs < 0 || endMs < startMs) return
                    chunkSegments.add(LiveSegmentTime(t, startMs, endMs))
                }

                override fun onProgress(progress: Int) {}

                override fun onNativeLog(line: String) {}
            }
            val (rawText, rawStatus) = synchronized(modelLoadLock) {
                // R18/C1 — início real do worker (caminho ao vivo).
                runCatching { WhisperJobService.markWorkerStarted(this@WhisperActivity, sessionGeneration) }
                whisperSession.transcribeAndStatus(
                    sessionGeneration,
                    repairWav.absolutePath,
                    liveSettings.language,
                    liveSettings.beamSize,
                    liveSettings.bestOf,
                    liveSettings.wordTimestamps,
                    liveSettings.vadFilter,
                    liveSettings.vadModelPath,
                    callback
                )
            }
            when (val parsed = WhisperResultParser.parse(rawText, rawStatus)) {
                is WhisperTranscriptResult.Success ->
                    if (parsed.text.isBlank()) LiveChunkResult.NoSpeech("texto vazio no reparo")
                    else LiveChunkResult.Success(parsed.text, chunkSegments)
                WhisperTranscriptResult.NoSpeech -> LiveChunkResult.NoSpeech("no_speech do reparo")
                is WhisperTranscriptResult.Cancelled -> LiveChunkResult.Cancelled(parsed.message)
                is WhisperTranscriptResult.Error -> LiveChunkResult.Failed(parsed.message)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Falha no reparo da região ${req.regionId}", e)
            LiveChunkResult.Failed("excecao no reparo: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            repairWav.delete()
        }
    }

    private fun checkNotCancelled() {
        if (cancelRequested) throw CancellationException("transcrição cancelada")
    }

    private fun formatSeconds(seconds: Double): String {
        val rounded = seconds.roundToLong()
        return "${rounded}s"
    }

    private fun formatElapsedCompact(elapsedMs: Long): String {
        return String.format(Locale.US, "%.3fs", elapsedMs / 1000.0)
    }

    private fun formatTime(milliseconds: Long): String {
        val safeMilliseconds = milliseconds.coerceAtLeast(0L)
        val totalSeconds = safeMilliseconds / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        val millis = safeMilliseconds % 1000
        return String.format(Locale.US, "[%02d:%02d:%02d.%03d]", hours, minutes, seconds, millis)
    }

    private fun cpuName(): String {
        return try {
            val lines = File("/proc/cpuinfo").readLines()
            val preferred = lines.firstOrNull { it.startsWith("Hardware", true) }
                ?: lines.firstOrNull { it.startsWith("model name", true) }
                ?: lines.firstOrNull { it.startsWith("Processor", true) }
            preferred?.substringAfter(':')?.trim().orEmpty().ifBlank { Build.HARDWARE }
        } catch (_: Throwable) {
            Build.HARDWARE
        }
    }

    companion object {
        private const val LIVE_MIC_STOPPING_LABEL = "Encerrando transcrição ao vivo..."
        /** R2/B2: estado honesto quando o teto de espera estoura com o worker
         * ainda vivo (a UI só é reabilitada quando ele realmente morre). */
        private const val LIVE_MIC_STOPPING_WORKER_LABEL = "Encerrando... (worker ainda ativo)"
        private const val LIVE_MIC_STOP_POLL_MS = 500L
        private const val LIVE_MIC_STOP_TIMEOUT_MS = 30_000L
        /** R3/C1: teto do dreno visível; a espera real vai até o worker terminal. */
        private const val LIVE_MIC_DRAIN_TIMEOUT_MS = 60_000L
        /** R4/C1: teto do spool do caminho ao vivo (o lab injeta um menor). */
        private const val DEFAULT_LIVE_SPOOL_MAX_BYTES = 256L * 1024L * 1024L
        /** R8/B: teto de reparos restritos POR JANELA (cada região tem 1 tentativa). */
        private const val LIVE_REPAIR_MAX_PER_WINDOW = 2
        /** R6/D2: janela default do caminho ao vivo — único 4 s + contexto 4 s
         * (hipóteses iniciais medidas na fase D; o lab pode trocar/fixar 2 s). */
        private const val DEFAULT_LIVE_HOP_MS = 4_000L
        private const val DEFAULT_LIVE_CONTEXT_MS = 4_000L

        /** R7/E3 — ESTRATÉGIA DEFAULT do caminho ao vivo no APK NORMAL.
         *
         * ANTES (herdado da R6/D2, NÃO aprovado na fase E): bloco fixo de 4 s
         * com contexto de 4 s (WER medido 46,5% em d01 com perda de 18 palavras
         * por deleção; RTF decode 2,05).
         * DEPOIS (vencedor medido na fase E, 3 estratégias × d01/n06/n07novo):
         * VAD segmentando intervalos de fala — melhor WER nas três entradas
         * (média 7,4% vs 17,6% do controle 2 s e 21,7% do 4+4), zero perda de
         * texto, e o MENOR custo de decode (RTF 0,95–1,33 contra 3,33–3,48 do
         * 2 s e 2,05–2,14 do 4+4). Nenhum outro default (modelo/backend/FA/VAD
         * do modelo) muda por causa disto. */
        private val DEFAULT_LIVE_STRATEGY: LiveStrategy = LiveStrategy.VadSegment(
            contextMs = 2_000,
            padMs = 300,
            silenceCutMs = 3_000,
            maxSegmentMs = 15_000,
            // R9/D3 (candidata C1 — promovida do LAB ao PRODUTO): a 1ª janela é
            // CURTA/adaptativa (teto 2 s de fala, corte de silêncio 600 ms) para
            // entregar o 1º texto cedo; da 2ª em diante valem as janelas longas
            // (15 s/3 s) — NÃO se reduz o teto geral a 4 s (isso reintroduz o
            // atraso sustentado e a fragmentação medidos na R8). Medido na R8-D
            // (mesmos fixtures base): 1ª parcial ~8,1–8,9 s vs ~17,2–19,5 s do
            // cap (>2× mais cedo), WER igual/sub-1 pp, RTF 1,02–1,16.
            firstSegmentMs = 2_000,
            firstSilenceCutMs = 600,
        )
        private const val REQUEST_PICK_MEDIA = 6101

        /** Seletor do botao ORT (rota opt-in arquivo/CPU). */
        private const val REQUEST_PICK_MEDIA_ORT = 6107
        private const val REQUEST_PICK_FOLDER = 6102
        private const val REQUEST_PICK_MODEL = 6103
        private const val REQUEST_CHOOSE_OUTPUT_DIR = 6104
        private const val REQUEST_CHOOSE_PRE_OUTPUT_DIR = 6105
        private const val REQUEST_RECORD_AUDIO_PERMISSION = 6106
        /** R11/D4 — pedido de POST_NOTIFICATIONS (observabilidade do job). */
        private const val REQUEST_POST_NOTIFICATIONS_PERMISSION = 6107
        private const val SIG_OUTPUT_FOLDER = "SIG"
        private const val WHISPER_OUTPUT_FOLDER = "Whisper"
        private const val GLOBAL_LOG_NAME = "whisper_log.txt"
        private const val VAD_MODEL_NAME = "ggml-silero-v6.2.0.bin"
        /** Primario: espelho no R2 (mesma pasta dos modelos do Whisper).
         *  Alternativa: HuggingFace (origem) — usada se o R2 falhar. */
        private const val VAD_MODEL_URL = "https://pub-6476622beda24c82875cb84f11f660ea.r2.dev/models/whisper/ggml-silero-v6.2.0.bin"
        private const val VAD_MODEL_URL_ALTERNATIVA = "https://huggingface.co/ggml-org/whisper-vad/resolve/main/ggml-silero-v6.2.0.bin"
        /** Tamanho real do VAD publicado (HEAD no HuggingFace, 25/09/2026). */
        private const val VAD_MODEL_BYTES = 885_098L
        private const val MODEL_BASE_URL = "https://pub-6476622beda24c82875cb84f11f660ea.r2.dev/models/whisper"
        private const val DEFAULT_BEAM_SIZE = 5
        private const val DEFAULT_BEST_OF = 5
        private const val TAG = "WhisperActivity"
    }

    /**
     * Um modelo Whisper do menu.
     *
     * @param bytes tamanho real do arquivo publicado (medido no R2 em
     *   25/09/2026). Vem no mesmo data class para o menu e o diálogo compartilharem
     *   a verdade: um `bytes` divergente da URL mostraria o número errado. 0 = o
     *   modelo foi escolhido pelo seletor de arquivos (não tem tamanho conhecido).
     */
    private data class WhisperModel(
        val label: String,
        val fileName: String,
        val file: File,
        val downloadUrl: String?,
        val bytes: Long = 0L
    )

    private data class MediaItem(
        val uri: Uri,
        val name: String
    )

    private data class PreparedInput(
        val index: Int,
        val item: MediaItem,
        val inputFile: File,
        val wavFile: File,
        val wavInfo: WavInfo?
    )

    private data class ConvertedMediaItem(
        val index: Int,
        val item: MediaItem,
        val wavFile: File,
        val durationSeconds: Double
    )

    private data class TranscriptionResult(
        val fileName: String,
        val text: String,
        val file: File,
        val durationSeconds: Double
    )

    private data class OutputSession(
        val dir: File,
        val txtFile: File,
        val htmlFile: File,
        val logFile: File
    )

    private data class WhisperAdvancedSettings(
        val vadFilter: Boolean,
        val flashAttention: Boolean,
        val wordTimestamps: Boolean,
        val beamSize: Int,
        val bestOf: Int
    ) {
        fun describe(): String {
            return "beam_search=$beamSize; best_of=$bestOf; flash_attn=${if (flashAttention) "on" else "off"}; " +
                "word_timestamps=${if (wordTimestamps) "on" else "off"}; vad=${if (vadFilter) "on" else "off"}"
        }
    }

    private data class WavInfo(
        val audioFormat: Int,
        val channels: Int,
        val sampleRate: Int,
        val bitsPerSample: Int,
        val dataSize: Int,
        val durationSeconds: Double
    ) {
        val isWhisperReady: Boolean
            get() = audioFormat == 1 && channels == 1 && sampleRate == 16000 && bitsPerSample == 16 && dataSize > 0
    }

    private enum class WhisperBackend(
        val label: String,
        val shortLabel: String,
        val reportLabel: String,
        val nativeKind: Int
    ) {
        CPU("CPU", "CPU", "CPU", 0),
        VULKAN("GPU (Vulkan)", "Vulkan", "GPU+Vulkan", 1),
        OPENCL("GPU (OpenCL)", "OpenCL", "GPU+OpenCL", 2);
    }

    private enum class WhisperLanguage(
        val code: String,
        val label: String,
        val shortLabel: String
    ) {
        AUTO("auto", "detectar idioma", "auto"),
        PT("pt", "português", "pt"),
        EN("en", "inglês", "en"),
        ES("es", "espanhol", "es");
    }
}
