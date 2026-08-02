package br.gov.sp.pcsp.launcher

import android.Manifest
import android.app.Activity
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.text.Editable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.method.ScrollingMovementMethod
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class FasterWhisperServerActivity : AppCompatActivity() {

    private lateinit var serverScroll: ScrollView
    private lateinit var previewFrame: View
    private lateinit var videoPreview: TextureView
    private lateinit var audioWaveform: FfmpegWaveformView
    private lateinit var playbackControls: View
    private lateinit var timelineFrame: View
    private lateinit var timeline: FfmpegRangeSlider
    private lateinit var playbackSpeedLabel: TextView
    private lateinit var currentTime: TextView
    private lateinit var timeFields: View
    private lateinit var inputFrom: EditText
    private lateinit var inputTo: EditText
    private lateinit var prepareModeButtons: View
    private lateinit var videoPrepareWarning: TextView
    private lateinit var buttonCompactFiles: TextView
    private lateinit var buttonPrepareHelp: TextView
    private lateinit var buttonReadyFiles: TextView
    private lateinit var buttonOriginalFiles: TextView
    private lateinit var selectedFile: TextView
    private lateinit var selectedListBox: View
    private lateinit var selectedList: TextView
    private lateinit var terminalText: TextView
    private lateinit var buttonSelectOutputFolder: ImageButton
    private lateinit var arrowInputOutput: View
    private lateinit var buttonPlayPause: ImageButton
    private lateinit var buttonSpeedDown: ImageButton
    private lateinit var buttonSpeedUp: ImageButton
    private lateinit var buttonTranscribe: ImageButton
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var outputFileName: TextView
    private lateinit var outputActions: View
    private lateinit var buttonSaveToFolder: ImageButton
    private lateinit var buttonOutputExport: ImageButton
    private lateinit var buttonOutputFolder: ImageButton
    private lateinit var buttonCopyTranscript: ImageButton
    private lateinit var liveTranscriptTextView: TextView
    private lateinit var liveAiProgress: TextView
    private lateinit var livePostActions: View
    private lateinit var buttonHistory: TextView
    private lateinit var buttonPersonSelector: TextView
    private lateinit var buttonStatement: TextView
    private lateinit var buttonPasteTranscript: TextView
    private lateinit var serverGate: View
    private lateinit var sourceBar: View
    private lateinit var serverGateStatus: TextView
    private lateinit var buttonPingServer: ImageButton
    private lateinit var buttonServerSelector: TextView
    private lateinit var ipInputs: List<EditText>
    private lateinit var recordingPanel: View
    private lateinit var buttonRecordingAction: ImageButton
    private lateinit var buttonLiveMicTest: ImageButton
    private lateinit var buttonLiveMicStop: ImageButton
    private lateinit var recordingTimer: TextView
    private lateinit var buttonSaveRecording: ImageButton
    private lateinit var inputLiveInterval: EditText
    private lateinit var buttonLiveIntervalMinus: TextView
    private lateinit var buttonLiveIntervalPlus: TextView
    private lateinit var buttonLiveLanguage: TextView

    private val selectedItems = mutableListOf<MediaItem>()
    private val tempOutputFiles = mutableListOf<File>()
    private val outputItems = mutableListOf<OutputItem>()
    private val handler = Handler(Looper.getMainLooper())
    private val speedSteps = floatArrayOf(0.25f, 0.5f, 1f, 2f)
    private val client = OkHttpClient.Builder()
        .connectTimeout(0, TimeUnit.MILLISECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(0, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var previewPlayer: MediaPlayer? = null
    private var audioPlayer: MediaPlayer? = null
    private var previewSurface: Surface? = null
    private var durationMs = 1L
    private var videoWidth = 0
    private var videoHeight = 0
    private var playbackSpeed = 1f
    private var syncingFields = false
    private var isProcessing = false
    private var cancelRequested = false
    private val currentCalls = Collections.synchronizedSet(mutableSetOf<Call>())
    private var currentFfmpegSessionId: Long? = null
    private var preSelectedOutputDirUri: Uri? = null
    private var finalOutputDirUri: Uri? = null
    private var lastSession: OutputSession? = null
    private var lastTranscriptionResults: List<TranscriptionResult> = emptyList()
    private var zipFile: File? = null
    private var playWhenSeekCompletes = false
    private var selectedPrepareMode: PrepareMode? = null
    private var serverBaseUrl: String = ""
    private var serverFallbackIps: List<String> = emptyList()
    private var serverIpIndex = -1
    private var serverEntries: List<ServerEntry> = emptyList()
    private var mediaRecorder: MediaRecorder? = null
    private var recordingFile: File? = null
    @Volatile private var recordingActive = false
    @Volatile private var recordingAudioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private var recordingPcmFile: File? = null
    private var whiteMicSelection = false
    private var recordingStartedAt = 0L
    @Volatile private var liveTranscribing = false
    @Volatile private var liveFinalizing = false
    @Volatile private var livePaused = false
    @Volatile private var liveDraftIntervalMillis = DEFAULT_LIVE_DRAFT_INTERVAL_MILLIS
    private var liveThread: Thread? = null
    private var liveUploadExecutor: ExecutorService? = null
    @Volatile private var liveAudioRecord: AudioRecord? = null
    private var liveFullPcmFile: File? = null
    private val liveTerminalLines = StringBuilder()
    private val liveTranscriptText = StringBuilder()
    private val liveRequestLock = Any()
    private var liveDraftText = ""
    private var liveDraftGeneration = 0
    private var liveCurrentCall: Call? = null
    private var liveCurrentCallIsFinal = false
    private var livePausedAt = 0L
    private var livePausedAccumulatedMs = 0L
    private var selectedLiveLanguage = LiveLanguage.PT
    private var pendingAudioPermissionAction = AudioPermissionAction.NONE
    private val assistantCalls = Collections.synchronizedSet(mutableSetOf<Call>())
    private var assistantRequestGeneration = 0
    private var historyTaskState = AssistantTaskState.IDLE
    private var namesTaskState = AssistantTaskState.IDLE
    private var statementTaskState = AssistantTaskState.IDLE
    private var historyElapsedMs: Long? = null
    private var namesElapsedMs: Long? = null
    private var statementElapsedMs: Long? = null
    private var assistantNames: List<String> = emptyList()
    private var progressPhase = ProgressPhase.TRANSCRIPTION
    private var transcriptionTaskState = AssistantTaskState.IDLE
    private var refiningTaskState = AssistantTaskState.IDLE

    private val progressTicker = object : Runnable {
        override fun run() {
            val player = currentPlayer()
            if (playbackControls.visibility == View.VISIBLE && player?.isPlaying == true) {
                val end = timeline.getEndMs()
                val position = player.currentPosition.toLong().coerceIn(0L, durationMs)
                if (position >= end) {
                    pausePreview()
                    timeline.setCurrent(end)
                    audioWaveform.setCurrent(end)
                    currentTime.text = formatTime(end)
                    setPlaybackButtonPlaying(false)
                } else {
                    timeline.setCurrent(position)
                    audioWaveform.setCurrent(position)
                    currentTime.text = formatTime(position)
                }
            }
            handler.postDelayed(this, 80L)
        }
    }

    private val recordingTicker = object : Runnable {
        override fun run() {
            if (recordingActive || mediaRecorder != null || liveTranscribing) {
                val now = SystemClock.elapsedRealtime()
                val elapsed = if (liveTranscribing) {
                    val pausedNow = if (livePaused && livePausedAt > 0L) now - livePausedAt else 0L
                    now - recordingStartedAt - livePausedAccumulatedMs - pausedNow
                } else {
                    now - recordingStartedAt
                }
                recordingTimer.text = formatElapsedCompact(elapsed.coerceAtLeast(0L))
                handler.postDelayed(this, 80L)
            }
        }
    }

    private val surfaceListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
            previewSurface = Surface(surfaceTexture)
            selectedItems.firstOrNull()?.takeIf { isVideo(it.mime, it.name) }?.let { prepareVideoPreview(it.uri) }
        }

        override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
            applyPreviewTransform()
        }

        override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
            previewPlayer?.setSurface(null)
            previewSurface?.release()
            previewSurface = null
            return true
        }

        override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) = Unit
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        keepContentInsideSystemBars()
        setContentView(R.layout.activity_faster_whisper_server)

        serverScroll = findViewById(R.id.server_scroll)
        serverGate = findViewById(R.id.server_gate)
        sourceBar = findViewById(R.id.source_bar)
        serverGateStatus = findViewById(R.id.server_gate_status)
        buttonPingServer = findViewById(R.id.button_ping_server)
        ipInputs = listOf(
            findViewById(R.id.input_ip_1),
            findViewById(R.id.input_ip_2),
            findViewById(R.id.input_ip_3),
            findViewById(R.id.input_ip_4)
        )
        previewFrame = findViewById(R.id.preview_frame)
        videoPreview = findViewById(R.id.video_preview)
        audioWaveform = findViewById(R.id.audio_waveform)
        playbackControls = findViewById(R.id.playback_controls)
        timelineFrame = findViewById(R.id.timeline_frame)
        timeline = findViewById(R.id.timeline)
        playbackSpeedLabel = findViewById(R.id.playback_speed_label)
        currentTime = findViewById(R.id.current_time)
        timeFields = findViewById(R.id.time_fields)
        inputFrom = findViewById(R.id.input_from)
        inputTo = findViewById(R.id.input_to)
        prepareModeButtons = findViewById(R.id.prepare_mode_buttons)
        videoPrepareWarning = findViewById(R.id.video_prepare_warning)
        buttonCompactFiles = findViewById(R.id.button_compact_files)
        buttonPrepareHelp = findViewById(R.id.button_prepare_help)
        buttonReadyFiles = findViewById(R.id.button_ready_files)
        buttonOriginalFiles = findViewById(R.id.button_original_files)
        selectedFile = findViewById(R.id.selected_file)
        selectedListBox = findViewById(R.id.selected_list_box)
        selectedList = findViewById(R.id.selected_list)
        terminalText = findViewById(R.id.terminal_text)
        buttonSelectOutputFolder = findViewById(R.id.button_select_output_folder)
        recordingPanel = findViewById(R.id.recording_panel)
        buttonRecordingAction = findViewById(R.id.button_recording_action)
        buttonLiveMicTest = findViewById(R.id.button_live_mic_test)
        buttonLiveMicStop = findViewById(R.id.button_live_mic_stop)
        recordingTimer = findViewById(R.id.recording_timer)
        buttonSaveRecording = findViewById(R.id.button_save_recording)
        inputLiveInterval = findViewById(R.id.input_live_interval)
        buttonLiveIntervalMinus = findViewById(R.id.button_live_interval_minus)
        buttonLiveIntervalPlus = findViewById(R.id.button_live_interval_plus)
        buttonLiveLanguage = findViewById(R.id.button_live_language)
        arrowInputOutput = findViewById(R.id.arrow_input_output)
        buttonServerSelector = findViewById(R.id.button_server_selector)
        buttonPlayPause = findViewById(R.id.button_play_pause)
        buttonSpeedDown = findViewById(R.id.button_speed_down)
        buttonSpeedUp = findViewById(R.id.button_speed_up)
        buttonTranscribe = findViewById(R.id.button_transcribe)
        progress = findViewById(R.id.progress)
        status = findViewById(R.id.status)
        outputFileName = findViewById(R.id.output_file_name)
        outputActions = findViewById(R.id.output_actions)
        buttonSaveToFolder = findViewById(R.id.button_save_to_folder)
        buttonOutputExport = findViewById(R.id.button_output_export)
        buttonOutputFolder = findViewById(R.id.button_output_folder)
        buttonCopyTranscript = findViewById(R.id.button_copy_transcript)
        liveTranscriptTextView = findViewById(R.id.live_transcript_text)
        liveAiProgress = findViewById(R.id.live_ai_progress)
        livePostActions = findViewById(R.id.live_post_actions)
        buttonHistory = findViewById(R.id.button_history)
        buttonPersonSelector = findViewById(R.id.button_person_selector)
        buttonStatement = findViewById(R.id.button_statement)
        buttonPasteTranscript = findViewById(R.id.button_paste_transcript)

        videoPreview.surfaceTextureListener = surfaceListener
        terminalText.movementMethod = ScrollingMovementMethod.getInstance()
        terminalText.setOnTouchListener { view, event ->
            view.parent.requestDisallowInterceptTouchEvent(true)
            if (event.actionMasked == android.view.MotionEvent.ACTION_UP || event.actionMasked == android.view.MotionEvent.ACTION_CANCEL) {
                view.parent.requestDisallowInterceptTouchEvent(false)
            }
            false
        }
        liveTranscriptTextView.movementMethod = ScrollingMovementMethod.getInstance()
        liveTranscriptTextView.setOnTouchListener { view, event ->
            view.parent.requestDisallowInterceptTouchEvent(true)
            if (event.actionMasked == android.view.MotionEvent.ACTION_UP || event.actionMasked == android.view.MotionEvent.ACTION_CANCEL) {
                view.parent.requestDisallowInterceptTouchEvent(false)
            }
            false
        }

        val exitHandler = installCancelAndExitGuard(
            isTaskRunning = { hasRunningTaskForExit() },
            cancelTask = { cancelRunningTaskForExit() }
        )
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { exitHandler() }
        findViewById<ImageButton>(R.id.button_model_settings).setOnClickListener {
            startActivity(Intent(this, ModelSettingsActivity::class.java))
        }
        findViewById<View>(R.id.button_select_media).setOnClickListener { showSourceMenu(it) }
        buttonPingServer.setOnClickListener { testManualServerIp() }
        buttonServerSelector.visibility = View.GONE
        buttonRecordingAction.setOnClickListener {
            if (!recordingActive) startMicrophoneRecording() else stopMicrophoneRecording()
        }
        buttonLiveMicTest.setOnClickListener {
            if (liveTranscribing) toggleLiveMicPause() else startLiveMicTranscription()
        }
        buttonLiveMicStop.setOnClickListener { stopLiveMicTranscription() }
        buttonLiveLanguage.setOnClickListener { showLiveLanguageMenu() }
        buttonSaveRecording.setOnClickListener { openOutputFolderPicker(REQUEST_SAVE_RECORDING_DIR) }
        buttonSelectOutputFolder.setOnClickListener { openOutputFolderPicker(REQUEST_CHOOSE_PRE_OUTPUT_DIR) }
        buttonPlayPause.setOnClickListener { togglePlayback() }
        buttonSpeedDown.setOnClickListener { changePlaybackSpeed(-1) }
        buttonSpeedUp.setOnClickListener { changePlaybackSpeed(1) }
        buttonTranscribe.setOnClickListener {
            if (isProcessing) cancelTranscription() else startServerTranscription()
        }
        buttonSaveToFolder.setOnClickListener {
            val preUri = preSelectedOutputDirUri
            if (preUri != null) saveTempOutputsToUri(preUri) else openOutputFolderPicker(REQUEST_CHOOSE_OUTPUT_DIR)
        }
        buttonOutputExport.setOnClickListener { showTranscriptShareMenu(it) }
        buttonOutputFolder.setOnClickListener { openOutputFolder() }
        buttonCopyTranscript.setOnClickListener { copyTranscriptToClipboard() }
        buttonHistory.setOnClickListener { requestHistory() }
        buttonPersonSelector.setOnClickListener { showPersonMenu() }
        buttonStatement.setOnClickListener { requestStatement() }
        buttonPasteTranscript.setOnClickListener { pasteTranscriptFromClipboard() }
        outputFileName.setOnClickListener { openOutputFile(lastSession?.txtFile, "text/plain") }

        timeline.onRangeChanged = { startMs, endMs, fromUser, _ ->
            updateTimeFields(startMs, endMs)
            if (fromUser) {
                val current = timeline.getCurrentMs().coerceIn(startMs, endMs)
                timeline.setCurrent(current)
                seekPreview(current)
            }
        }
        timeline.onPositionChanged = { positionMs, fromUser ->
            currentTime.text = formatTime(positionMs)
            audioWaveform.setCurrent(positionMs)
            if (fromUser) seekPreview(positionMs)
        }
        inputFrom.addTextChangedListener(timeFieldWatcher { timeline.setStart(it, true) })
        inputTo.addTextChangedListener(timeFieldWatcher { timeline.setEnd(it, true) })
        buttonCompactFiles.setOnClickListener { selectPrepareMode(PrepareMode.COMPACT) }
        buttonPrepareHelp.setOnClickListener { showPrepareModeHelp() }
        buttonReadyFiles.setOnClickListener { selectPrepareMode(PrepareMode.READY) }
        buttonOriginalFiles.setOnClickListener { selectPrepareMode(PrepareMode.ORIGINAL) }
        setupLiveIntervalControls()
        updateSpeedButton()
        updateTranscribeEnabled()
        loadServersAndActivateDefault()
    }

    override fun onResume() {
        super.onResume()
        handler.post(progressTicker)
        refreshModelSummaryStatusIfIdle()
    }

    override fun onPause() {
        handler.removeCallbacks(progressTicker)
        pausePreview()
        setPlaybackButtonPlaying(false)
        super.onPause()
    }

    override fun onDestroy() {
        synchronized(currentCalls) {
            currentCalls.forEach { it.cancel() }
            currentCalls.clear()
        }
        synchronized(assistantCalls) {
            assistantCalls.forEach { it.cancel() }
            assistantCalls.clear()
        }
        stopLiveMicTranscription(generateDefinitive = false)
        releaseRecorder()
        releasePreviewPlayers()
        previewSurface?.release()
        previewSurface = null
        super.onDestroy()
    }

    private fun hasRunningTaskForExit(): Boolean {
        return isProcessing ||
            recordingActive ||
            mediaRecorder != null ||
            liveTranscribing ||
            liveFinalizing ||
            liveThread != null ||
            liveUploadExecutor != null ||
            synchronized(currentCalls) { currentCalls.isNotEmpty() } ||
            synchronized(assistantCalls) { assistantCalls.isNotEmpty() }
    }

    private fun cancelRunningTaskForExit() {
        if (isProcessing) cancelTranscription()
        stopLiveMicTranscription(generateDefinitive = false)
        releaseRecorder()
        synchronized(currentCalls) {
            currentCalls.forEach { it.cancel() }
            currentCalls.clear()
        }
        synchronized(assistantCalls) {
            assistantCalls.forEach { it.cancel() }
            assistantCalls.clear()
        }
        FFmpegKit.cancel()
    }

    private fun hideToolsUntilServer() {
        serverBaseUrl = ""
        serverFallbackIps = emptyList()
        serverIpIndex = -1
        serverGate.visibility = View.VISIBLE
        sourceBar.visibility = View.GONE
        recordingPanel.visibility = View.VISIBLE
        previewFrame.visibility = View.GONE
        audioWaveform.visibility = View.GONE
        playbackControls.visibility = View.GONE
        timelineFrame.visibility = View.GONE
        currentTime.visibility = View.GONE
        timeFields.visibility = View.GONE
        selectedFile.visibility = View.GONE
        selectedListBox.visibility = View.GONE
        prepareModeButtons.visibility = View.GONE
        buttonTranscribe.visibility = View.GONE
        progress.visibility = View.GONE
        status.visibility = View.GONE
        outputFileName.visibility = View.GONE
        outputActions.visibility = View.GONE
        liveTranscriptTextView.visibility = View.GONE
        buttonPasteTranscript.visibility = View.GONE
        liveAiProgress.visibility = View.GONE
        livePostActions.visibility = View.GONE
        terminalText.visibility = View.GONE
        serverGateStatus.text = "Procurando server.txt..."
    }

    private fun activateServer(ip: String, candidates: List<String> = listOf(ip), index: Int = 0) {
        serverFallbackIps = candidates
        serverIpIndex = index
        serverBaseUrl = "http://$ip:$SERVER_PORT"
        val serverName = serverNameForIp(ip)
        if (::buttonServerSelector.isInitialized) buttonServerSelector.text = "Servidor: $serverName"
        serverGate.visibility = View.GONE
        sourceBar.visibility = View.VISIBLE
        recordingPanel.visibility = View.VISIBLE
        buttonTranscribe.visibility = View.VISIBLE
        status.visibility = View.VISIBLE
        terminalText.visibility = View.GONE
        terminalText.text = ""
        status.text = ModelSelectionSummary.current()
        liveTranscriptTextView.visibility = View.VISIBLE
        buttonPasteTranscript.visibility = View.VISIBLE
        livePostActions.visibility = View.VISIBLE
        buttonHistory.isEnabled = true
        buttonHistory.alpha = 1f
        buttonPersonSelector.isEnabled = true
        buttonPersonSelector.alpha = 1f
        buttonStatement.isEnabled = true
        buttonStatement.alpha = 1f
        resetTranscriptionProgress()
        liveAiProgress.visibility = View.GONE
        updateTranscribeEnabled()
    }

    private fun refreshModelSummaryStatusIfIdle() {
        if (!::status.isInitialized || status.visibility != View.VISIBLE) return
        val current = status.text?.toString().orEmpty()
        if (
            current.isBlank() ||
            current.startsWith("Servidor conectado:") ||
            current.startsWith("Modelo de transcrição:")
        ) {
            status.text = ModelSelectionSummary.current()
        }
    }

    private fun loadServersAndActivateDefault() {
        serverEntries = loadServerEntries()
        val defaultIndex = serverEntries.indexOfFirst { it.ip == DEFAULT_SERVER_IP }.takeIf { it >= 0 } ?: 0
        activateServerEntry(serverEntries[defaultIndex])
    }

    private fun showServerMenu() {
        if (serverEntries.isEmpty()) serverEntries = loadServerEntries()
        PopupMenu(this, buttonServerSelector).apply {
            serverEntries.forEachIndexed { index, entry ->
                menu.add(0, index, index, entry.name)
            }
            setOnMenuItemClickListener { item ->
                serverEntries.getOrNull(item.itemId)?.let { entry ->
                    activateServerEntry(entry)
                    true
                } ?: false
            }
            show()
        }
    }

    private fun activateServerEntry(entry: ServerEntry) {
        val orderedEntries = listOf(entry) + serverEntries.filter { it.ip != entry.ip }
        activateServer(entry.ip, orderedEntries.map { it.ip }, 0)
    }

    private fun serverNameForIp(ip: String): String {
        return serverEntries.firstOrNull { it.ip == ip }?.name ?: ip
    }

    private fun loadServerEntries(): List<ServerEntry> {
        val entries = mutableListOf<ServerEntry>()
        val serverFile = File(getExternalFilesDir(null), "server.txt")
        if (serverFile.exists()) {
            try {
                serverFile.readLines(Charsets.UTF_8)
                    .mapNotNull { parseServerLine(it) }
                    .forEach { entries += it }
            } catch (_: Throwable) {
            }
        }
        entries += ServerEntry(DEFAULT_SERVER_IP, DEFAULT_SERVER_NAME)
        return entries.distinctBy { it.ip }
    }

    private fun parseServerLine(line: String): ServerEntry? {
        val clean = line.substringBefore("#").replace("\uFEFF", "").trim()
        if (clean.isBlank() || clean.startsWith("#")) return null
        val match = SERVER_LINE_IP.find(clean) ?: return null
        val ip = match.groupValues[1].takeIf { isValidIpv4(it) } ?: return null
        val name = clean
            .removeRange(match.range)
            .replace(Regex("""^[\s,;|:\-]+|[\s,;|:\-]+$"""), "")
            .trim()
            .ifBlank { ip }
        return ServerEntry(ip, name)
    }

    private fun resolveServerOnEnter() {
        Thread {
            val serverFile = File(getExternalFilesDir(null), "server.txt")
            if (!serverFile.exists()) {
                runOnUiThread { serverGateStatus.text = "" }
                return@Thread
            }

            val candidates = readServerIps(serverFile)
            if (candidates.isEmpty()) {
                runOnUiThread { serverGateStatus.text = "" }
                return@Thread
            }

            candidates.forEachIndexed { index, ip ->
                runOnUiThread { serverGateStatus.text = "Testando ${index + 1}/${candidates.size}: $ip" }
                if (pingIp(ip)) {
                    runOnUiThread { activateServer(ip, candidates, index) }
                    return@Thread
                }
            }
            runOnUiThread { serverGateStatus.text = "" }
        }.start()
    }

    private fun testManualServerIp() {
        val ip = manualIpOrNull()
        if (ip == null) {
            serverGateStatus.text = "IP inválido."
            return
        }
        buttonPingServer.isEnabled = false
        serverGateStatus.text = "Testando $ip..."
        Thread {
            val ok = pingIp(ip)
            runOnUiThread {
                buttonPingServer.isEnabled = true
                if (ok) {
                    activateServer(ip)
                } else {
                    serverGateStatus.text = "$ip não respondeu."
                }
            }
        }.start()
    }

    private fun manualIpOrNull(): String? {
        val octets = ipInputs.map { input ->
            input.text.toString().toIntOrNull()?.takeIf { it in 0..255 } ?: return null
        }
        return octets.joinToString(".")
    }

    private fun readServerIps(file: File): List<String> {
        return try {
            file.readLines(Charsets.UTF_8)
                .mapNotNull { parseServerLine(it)?.ip }
                .distinct()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun sanitizeIpLine(line: String): String? {
        return parseServerLine(line)?.ip
    }

    private fun isValidIpv4(value: String): Boolean {
        val parts = value.split(".")
        return parts.size == 4 && parts.all { part ->
            part.isNotBlank() && part.all { it.isDigit() } && part.toIntOrNull()?.let { it in 0..255 } == true
        }
    }

    private fun pingIp(ip: String): Boolean {
        try {
            val process = ProcessBuilder("/system/bin/ping", "-c", "1", "-W", "1", ip)
                .redirectErrorStream(true)
                .start()
            val finished = process.waitFor(1600, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroy()
                return false
            }
            if (process.exitValue() == 0) return true
        } catch (_: Throwable) {
        }
        return try {
            InetAddress.getByName(ip).isReachable(1200)
        } catch (_: Throwable) {
            false
        }
    }

    @Deprecated("Deprecated Android callback kept for this legacy XML activity.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK) return

        when (requestCode) {
            REQUEST_PICK_MEDIA -> handlePickedMedia(data)
            REQUEST_PICK_FOLDER -> data?.data?.let { handlePickedFolder(it, data.flags) }
            REQUEST_CHOOSE_PRE_OUTPUT_DIR -> data?.data?.let {
                takeTreePermission(it)
                preSelectedOutputDirUri = it
                buttonSelectOutputFolder.setBackgroundResource(R.drawable.ffmpeg_outline_green_button_bg)
                Toast.makeText(this, "Pasta de saída selecionada.", Toast.LENGTH_SHORT).show()
            }
            REQUEST_CHOOSE_OUTPUT_DIR -> data?.data?.let {
                takeTreePermission(it)
                saveTempOutputsToUri(it)
            }
            REQUEST_SAVE_RECORDING_DIR -> data?.data?.let {
                takeTreePermission(it)
                saveRecordedAudioToUri(it)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO_PERMISSION) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                when (pendingAudioPermissionAction) {
                    AudioPermissionAction.RECORD_FILE -> startMicrophoneRecording()
                    AudioPermissionAction.LIVE_TEST -> startLiveMicTranscription()
                    AudioPermissionAction.NONE -> Unit
                }
            } else {
                Toast.makeText(this, "Permissão de microfone negada.", Toast.LENGTH_SHORT).show()
            }
            pendingAudioPermissionAction = AudioPermissionAction.NONE
        }
    }

    private fun showSourceMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add("Selecionar arquivos")
            menu.add("Selecionar pasta")
            setOnMenuItemClickListener { item ->
                when (item.title.toString()) {
                    "Selecionar arquivos" -> openMediaPicker()
                    "Selecionar pasta" -> openFolderPicker()
                }
                true
            }
            show()
        }
    }

    private fun showRecordingPanel() {
        if (isProcessing) return
        recordingPanel.visibility = View.VISIBLE
        buttonSaveRecording.visibility = if (recordingFile?.exists() == true && mediaRecorder == null) View.VISIBLE else View.GONE
        recordingTimer.text = "00:00.000"
        serverScroll.post { serverScroll.smoothScrollTo(0, recordingPanel.top) }
    }

    private fun setupLiveIntervalControls() {
        refreshLiveIntervalInput()
        buttonLiveIntervalMinus.setOnClickListener {
            setLiveDraftIntervalMillis(liveDraftIntervalMillis - 1000)
        }
        buttonLiveIntervalPlus.setOnClickListener {
            setLiveDraftIntervalMillis(liveDraftIntervalMillis + 1000)
        }
        inputLiveInterval.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                parseLiveIntervalMillis(s?.toString().orEmpty())?.let { liveDraftIntervalMillis = it }
            }
        })
        inputLiveInterval.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) refreshLiveIntervalInput()
        }
    }

    private fun setLiveDraftIntervalMillis(value: Int) {
        val rounded = (((value + 50) / 100) * 100).coerceIn(
            MIN_LIVE_DRAFT_INTERVAL_MILLIS,
            MAX_LIVE_DRAFT_INTERVAL_MILLIS
        )
        liveDraftIntervalMillis = rounded
        refreshLiveIntervalInput()
    }

    private fun refreshLiveIntervalInput() {
        val value = String.format(Locale.US, "%.1f", liveDraftIntervalMillis / 1000.0)
        if (inputLiveInterval.text.toString() == value) return
        inputLiveInterval.setText(value)
        inputLiveInterval.setSelection(inputLiveInterval.text.length)
    }

    private fun parseLiveIntervalMillis(raw: String): Int? {
        val value = raw
            .replace(',', '.')
            .trim()
            .toDoubleOrNull()
            ?.takeIf { it in 0.1..10.0 }
            ?: return null
        return ((value * 10).toInt() * 100).coerceIn(
            MIN_LIVE_DRAFT_INTERVAL_MILLIS,
            MAX_LIVE_DRAFT_INTERVAL_MILLIS
        )
    }

    private fun startMicrophoneRecording() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingAudioPermissionAction = AudioPermissionAction.RECORD_FILE
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQUEST_RECORD_AUDIO_PERMISSION
            )
            return
        }
        releaseRecorder()
        releasePreviewPlayers()
        clearOutputResult()
        selectedItems.clear()
        whiteMicSelection = false
        selectedPrepareMode = PrepareMode.READY
        updatePrepareModeButtons()
        showSinglePreview(null)
        prepareWhiteRecordingUi()
        recordingPanel.visibility = View.VISIBLE
        buttonSaveRecording.visibility = View.GONE
        recordingTimer.text = "00:00.000"
        val stamp = System.currentTimeMillis()
        recordingFile = File(cacheDir, "faster_whisper_gravacao_$stamp.wav")
        recordingPcmFile = File(cacheDir, "faster_whisper_gravacao_$stamp.pcm")
        recordingActive = true
        recordingStartedAt = SystemClock.elapsedRealtime()
        buttonRecordingAction.setImageResource(R.drawable.ic_ffmpeg_cancel_red)
        buttonRecordingAction.setBackgroundResource(R.drawable.ffmpeg_outline_red_button_bg)
        buttonRecordingAction.contentDescription = "Parar gravação"
        status.text = ""
        handler.post(recordingTicker)
        recordingThread = Thread { recordWhiteMicrophonePcm() }.also { it.start() }
    }

    private fun stopMicrophoneRecording() {
        recordingActive = false
        runCatching { recordingAudioRecord?.stop() }
        recordingThread?.join(3_000)
        recordingThread = null
        recordingAudioRecord = null
        val file = recordingFile
        val pcmFile = recordingPcmFile
        if (file != null && pcmFile?.exists() == true && pcmFile.length() > 0L) {
            writeWavFile(file, pcmFile, WHITE_RECORDING_SAMPLE_RATE)
        }
        pcmFile?.delete()
        recordingPcmFile = null
        resetRecordingButton()
        if (file == null || !file.exists() || file.length() == 0L) {
            transcriptionTaskState = AssistantTaskState.ERROR
            renderLiveProgress()
            status.text = "Gravação vazia."
            return
        }
        buttonSaveRecording.visibility = View.GONE
        val item = MediaItem(Uri.fromFile(file), file.name, "audio/wav", readDurationFromFile(file))
        applySelection(listOf(item))
        whiteMicSelection = true
        selectedPrepareMode = PrepareMode.READY
        transcriptionTaskState = AssistantTaskState.DONE
        renderLiveProgress()
        updateTranscribeEnabled()
        recordingPanel.visibility = View.GONE
        recordingTimer.text = formatElapsedCompact(item.durationMs)
        status.text = ""
    }

    private fun resetRecordingButton() {
        handler.removeCallbacks(recordingTicker)
        buttonRecordingAction.setImageResource(R.drawable.ic_mic_outline)
        buttonRecordingAction.setBackgroundResource(R.drawable.ffmpeg_outline_button_bg)
        buttonRecordingAction.contentDescription = "Iniciar gravação"
    }

    private fun releaseRecorder() {
        handler.removeCallbacks(recordingTicker)
        recordingActive = false
        runCatching { recordingAudioRecord?.stop() }
        recordingAudioRecord?.release()
        recordingAudioRecord = null
        recordingThread?.interrupt()
        recordingThread = null
        recordingPcmFile?.delete()
        recordingPcmFile = null
        mediaRecorder?.runCatching { release() }
        mediaRecorder = null
    }

    private fun recordWhiteMicrophonePcm() {
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBuffer = AudioRecord.getMinBufferSize(WHITE_RECORDING_SAMPLE_RATE, channelConfig, encoding)
        if (minBuffer <= 0) {
            runOnUiThread {
                recordingActive = false
                status.text = "Microfone indisponível."
                resetRecordingButton()
            }
            return
        }
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            WHITE_RECORDING_SAMPLE_RATE,
            channelConfig,
            encoding,
            minBuffer * 2
        )
        recordingAudioRecord = recorder
        try {
            val pcmFile = recordingPcmFile ?: return
            FileOutputStream(pcmFile).use { output ->
                val buffer = ByteArray(minBuffer)
                recorder.startRecording()
                while (recordingActive) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) output.write(buffer, 0, read)
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "WAV recording failed", e)
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
            if (recordingAudioRecord == recorder) recordingAudioRecord = null
        }
    }

    private fun saveRecordedAudioToUri(treeUri: Uri) {
        val file = recordingFile?.takeIf { it.exists() } ?: run {
            Toast.makeText(this, "Nenhuma gravação para salvar.", Toast.LENGTH_SHORT).show()
            return
        }
        val dir = DocumentFile.fromTreeUri(this, treeUri) ?: return
        try {
            copyFileToDocument(file, dir, "audio/wav")
            Toast.makeText(this, "Áudio salvo.", Toast.LENGTH_SHORT).show()
        } catch (e: Throwable) {
            Toast.makeText(this, "Não consegui salvar o áudio.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startLiveMicTranscription() {
        if (serverBaseUrl.isBlank()) {
            status.text = "Informe e teste o IP do servidor."
            return
        }
        if (isProcessing) return
        if (liveFinalizing) {
            status.text = "Aguarde a transcrição definitiva terminar."
            return
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingAudioPermissionAction = AudioPermissionAction.LIVE_TEST
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQUEST_RECORD_AUDIO_PERMISSION
            )
            return
        }
        releaseRecorder()
        stopMicrophonePreview()
        clearOutputResult()
        liveFullPcmFile?.delete()
        liveFullPcmFile = File(cacheDir, "live_full_${System.currentTimeMillis()}.pcm")
        liveTranscribing = true
        liveFinalizing = false
        livePaused = false
        livePausedAt = 0L
        livePausedAccumulatedMs = 0L
        liveUploadExecutor = Executors.newFixedThreadPool(LIVE_UPLOAD_WORKERS)
        synchronized(liveTerminalLines) { liveTerminalLines.clear() }
        synchronized(liveTranscriptText) {
            liveTranscriptText.clear()
            liveDraftText = ""
        }
        synchronized(liveRequestLock) {
            liveDraftGeneration = 0
            liveCurrentCall = null
            liveCurrentCallIsFinal = false
        }
        refreshLiveLanguageButton()
        prepareLiveTranscriptUi()
        transcriptionTaskState = AssistantTaskState.RUNNING
        refiningTaskState = AssistantTaskState.IDLE
        renderLiveProgress()
        status.visibility = View.VISIBLE
        status.text = "Ouvindo e transcrevendo ao vivo..."
        recordingPanel.visibility = View.VISIBLE
        recordingStartedAt = SystemClock.elapsedRealtime()
        recordingTimer.text = "00:00.000"
        handler.removeCallbacks(recordingTicker)
        handler.post(recordingTicker)
        buttonLiveMicTest.setImageResource(R.drawable.ic_live_pause_yellow)
        buttonLiveMicTest.setBackgroundResource(R.drawable.ffmpeg_outline_button_bg)
        buttonLiveMicTest.alpha = 1f
        buttonLiveMicTest.contentDescription = "Pausar transcrição ao vivo"
        buttonLiveMicStop.visibility = View.VISIBLE
        runOnUiThread { updateLiveTerminalText() }
        liveThread = Thread { runLiveMicLoop() }.also { it.start() }
    }

    private fun toggleLiveMicPause() {
        if (!liveTranscribing) return
        val now = SystemClock.elapsedRealtime()
        livePaused = !livePaused
        if (livePaused) {
            livePausedAt = now
            buttonLiveMicTest.alpha = 0.55f
            buttonLiveMicTest.contentDescription = "Retomar transcrição ao vivo"
            status.text = "Transcrição ao vivo pausada."
        } else {
            if (livePausedAt > 0L) {
                livePausedAccumulatedMs += now - livePausedAt
            }
            livePausedAt = 0L
            buttonLiveMicTest.alpha = 1f
            buttonLiveMicTest.contentDescription = "Pausar transcrição ao vivo"
            status.text = "Ouvindo e transcrevendo ao vivo..."
        }
    }

    private fun stopLiveMicTranscription(generateDefinitive: Boolean = true) {
        if (!liveTranscribing && liveThread == null && liveUploadExecutor == null) return
        liveTranscribing = false
        livePaused = false
        livePausedAt = 0L
        val recordingThread = liveThread
        recordingThread?.interrupt()
        liveThread = null
        try {
            liveAudioRecord?.stop()
        } catch (_: Throwable) {
        }
        liveUploadExecutor?.shutdownNow()
        liveUploadExecutor = null
        synchronized(liveRequestLock) {
            liveDraftGeneration += 1
            liveCurrentCall?.cancel()
            liveCurrentCall = null
            liveCurrentCallIsFinal = false
        }
        synchronized(currentCalls) {
            currentCalls.forEach { it.cancel() }
            currentCalls.clear()
        }
        handler.removeCallbacks(recordingTicker)
        buttonLiveMicTest.setImageResource(R.drawable.ic_mic_outline_red)
        buttonLiveMicTest.setBackgroundResource(R.drawable.ffmpeg_outline_button_bg)
        buttonLiveMicTest.alpha = 1f
        buttonLiveMicTest.contentDescription = "Transcrição ao vivo"
        buttonLiveMicStop.visibility = View.GONE
        if (generateDefinitive) {
            progressPhase = ProgressPhase.TRANSCRIPTION
            transcriptionTaskState = AssistantTaskState.DONE
            refiningTaskState = AssistantTaskState.RUNNING
            liveAiProgress.visibility = View.VISIBLE
            renderLiveProgress()
        }
        if (::status.isInitialized && status.visibility == View.VISIBLE) {
            status.text = if (generateDefinitive) {
                "Enviando áudio integral para a transcrição definitiva..."
            } else {
                "Transcrição ao vivo encerrada."
            }
        }
        if (generateDefinitive) {
            liveFinalizing = true
            finishDefinitiveLiveTranscript(recordingThread, liveFullPcmFile)
        } else {
            liveFinalizing = false
            liveFullPcmFile?.delete()
            liveFullPcmFile = null
        }
    }

    private fun stopMicrophonePreview() {
        if (mediaRecorder != null) {
            try {
                mediaRecorder?.stop()
            } catch (_: Throwable) {
            }
            releaseRecorder()
            resetRecordingButton()
        }
    }

    private fun runLiveMicLoop() {
        val sampleRate = 16000
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelConfig, encoding)
        if (minBuffer <= 0) {
            runOnUiThread { status.text = "Microfone indisponível." }
            stopLiveMicTranscription()
            return
        }
        val recordBufferSize = maxOf(minBuffer * 2, sampleRate)
        val bytesPerSecond = pcmBytesForMillis(sampleRate, 1000)
        val finalChunkBytes = pcmBytesForMillis(sampleRate, LIVE_FINAL_CHUNK_MILLIS)
        val readBuffer = ByteArray(minBuffer.coerceAtMost(bytesPerSecond))
        val windowPcm = ByteArrayOutputStream(finalChunkBytes + bytesPerSecond)
        var recorder: AudioRecord? = null
        var fullPcmOutput: FileOutputStream? = null
        var windowIndex = 1
        var lastSentDraftMillis = 0
        try {
            fullPcmOutput = liveFullPcmFile?.let { FileOutputStream(it) }
            recorder = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, channelConfig, encoding, recordBufferSize)
            liveAudioRecord = recorder
            recorder.startRecording()
            while (liveTranscribing) {
                val read = recorder.read(readBuffer, 0, readBuffer.size)
                if (read <= 0) continue
                if (livePaused) continue
                fullPcmOutput?.write(readBuffer, 0, read)
                windowPcm.write(readBuffer, 0, read)

                while (windowPcm.size() >= finalChunkBytes) {
                    val allPcm = windowPcm.toByteArray()
                    val finalPcm = allPcm.copyOfRange(0, finalChunkBytes)
                    submitLiveMicSnapshot(finalPcm, windowIndex, LIVE_FINAL_CHUNK_MILLIS, isFinal = true)
                    val leftover = allPcm.copyOfRange(finalChunkBytes, allPcm.size)
                    windowPcm.reset()
                    windowPcm.write(leftover)
                    windowIndex++
                    lastSentDraftMillis = 0
                }

                val draftInterval = liveDraftIntervalMillis.coerceIn(
                    MIN_LIVE_DRAFT_INTERVAL_MILLIS,
                    MAX_LIVE_DRAFT_INTERVAL_MILLIS
                )
                val draftWindowMillis = ((windowPcm.size().toLong() * 1000L) / (sampleRate * 2L))
                    .coerceAtMost((LIVE_FINAL_CHUNK_MILLIS - draftInterval).toLong())
                val currentDraftMillis = ((draftWindowMillis / draftInterval) * draftInterval).toInt()
                if (currentDraftMillis > lastSentDraftMillis) {
                    lastSentDraftMillis = currentDraftMillis
                    submitLiveMicSnapshot(
                        windowPcm.toByteArray(),
                        windowIndex,
                        currentDraftMillis,
                        isFinal = false
                    )
                }
            }
        } catch (e: Throwable) {
            if (liveTranscribing) {
                Log.e(TAG, "Live mic failed", e)
                runOnUiThread {
                    status.text = "Erro no microfone ao vivo."
                }
            }
        } finally {
            try {
                fullPcmOutput?.flush()
                fullPcmOutput?.close()
            } catch (_: Throwable) {
            }
            try {
                recorder?.stop()
            } catch (_: Throwable) {
            }
            recorder?.release()
            liveAudioRecord = null
        }
    }

    private fun submitLiveMicSnapshot(pcm: ByteArray, windowIndex: Int, secondsInWindow: Int, isFinal: Boolean) {
        if (pcm.size < 1024) return
        val generation = synchronized(liveRequestLock) {
            if (isFinal) {
                liveDraftGeneration
            } else {
                liveDraftGeneration += 1
                if (!liveCurrentCallIsFinal) liveCurrentCall?.cancel()
                liveDraftGeneration
            }
        }
        liveUploadExecutor?.submit {
            sendLiveMicSnapshot(pcm, windowIndex, secondsInWindow, isFinal, generation)
        }
    }

    private fun sendLiveMicSnapshot(
        pcm: ByteArray,
        windowIndex: Int,
        secondsInWindow: Int,
        isFinal: Boolean,
        generation: Int
    ) {
        val wavFile = File(cacheDir, "live_mic_${System.currentTimeMillis()}_${windowIndex}_${secondsInWindow}.wav")
        try {
            if (!isFinal && generation != synchronized(liveRequestLock) { liveDraftGeneration }) return
            writeWavFile(wavFile, pcm, 16000)
            val upload = UploadFile(wavFile, "audio/wav", "microfone ao vivo")
            val text = sendLiveChunkToServer(upload, isFinal)
            if (text.isNotBlank()) {
                updateLiveTranscriptWindow(text, isFinal, generation)
                runOnUiThread { updateLiveTerminalText() }
            }
        } catch (e: Throwable) {
            val obsolete = !isFinal && generation != synchronized(liveRequestLock) { liveDraftGeneration }
            if (liveTranscribing && !obsolete) {
                runOnUiThread { status.text = e.message ?: "Falha na transcrição ao vivo." }
            }
        } finally {
            wavFile.delete()
        }
    }

    private fun sendLiveChunkToServer(uploadFile: UploadFile, isFinal: Boolean): String {
        var lastError: Throwable? = null
        while (true) {
            val baseUrl = serverBaseUrl
            try {
                return sendLiveChunkToServerOnce(uploadFile, baseUrl, isFinal)
            } catch (e: ServerUnavailableException) {
                lastError = e
                if (activateNextServerAfterFailure(StringBuilder(), baseUrl) == null) break
            }
        }
        throw lastError ?: ServerUnavailableException()
    }

    private fun sendLiveChunkToServerOnce(uploadFile: UploadFile, baseUrl: String, isFinal: Boolean): String {
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("stream", "true")
            .addFormDataPart("file", uploadFile.file.name, uploadFile.file.asRequestBody(uploadFile.mime.toMediaType()))
            .build()
        val request = Request.Builder()
            .url("$baseUrl/v1/audio/transcriptions")
            .post(requestBody)
            .build()
        val call = client.newCall(request)
        currentCalls.add(call)
        synchronized(liveRequestLock) {
            liveCurrentCall = call
            liveCurrentCallIsFinal = isFinal
        }
        val textBuilder = StringBuilder()
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    if (response.code == 500) throw ServerUnavailableException()
                    throw IllegalStateException("servidor respondeu ${response.code}")
                }
                val body = response.body ?: return ""
                val source = body.source()
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    val delta = extractTextDelta(line)
                    if (delta.isNotBlank()) textBuilder.append(delta)
                }
            }
        } finally {
            currentCalls.remove(call)
            synchronized(liveRequestLock) {
                if (liveCurrentCall == call) {
                    liveCurrentCall = null
                    liveCurrentCallIsFinal = false
                }
            }
        }
        return textBuilder.toString()
    }

    private fun showLiveLanguageMenu() {
        PopupMenu(this, buttonLiveLanguage).apply {
            LiveLanguage.values().forEach { language -> menu.add(language.label) }
            setOnMenuItemClickListener { item ->
                selectedLiveLanguage = LiveLanguage.values().first { it.label == item.title.toString() }
                refreshLiveLanguageButton()
                true
            }
            show()
        }
    }

    private fun refreshLiveLanguageButton() {
        if (::buttonLiveLanguage.isInitialized) {
            buttonLiveLanguage.text = "Idioma: ${selectedLiveLanguage.shortLabel}"
        }
    }

    private fun updateLiveTranscriptWindow(text: String, isFinal: Boolean, generation: Int) {
        val clean = text.trim()
        if (clean.isBlank()) return
        synchronized(liveTranscriptText) {
            if (!isFinal && generation != synchronized(liveRequestLock) { liveDraftGeneration }) return
            if (isFinal) {
                if (liveTranscriptText.isNotEmpty() && !liveTranscriptText.endsWith("\n")) liveTranscriptText.append('\n')
                liveTranscriptText.append(clean).append('\n')
                liveDraftText = ""
            } else {
                liveDraftText = clean
            }
            rebuildLiveTranscriptDisplayLocked()
        }
    }

    private fun rebuildLiveTranscriptDisplayLocked() {
        synchronized(liveTerminalLines) {
            liveTerminalLines.clear()
            val committed = liveTranscriptText.toString().trim()
            if (committed.isNotBlank()) {
                liveTerminalLines.append(TRANSCRIPTION_START).append(committed).append(TRANSCRIPTION_END).append('\n')
            }
            val draft = liveDraftText.trim()
            if (draft.isNotBlank()) {
                liveTerminalLines.append(TRANSCRIPTION_START).append(draft).append(TRANSCRIPTION_END).append('\n')
            }
        }
    }

    private fun pcmBytesForMillis(sampleRate: Int, millis: Int): Int {
        return sampleRate * 2 * millis / 1000
    }

    private fun writeWavFile(file: File, pcm: ByteArray, sampleRate: Int) {
        FileOutputStream(file).use { output ->
            val byteRate = sampleRate * 2
            output.write("RIFF".toByteArray(Charsets.US_ASCII))
            writeIntLe(output, 36 + pcm.size)
            output.write("WAVE".toByteArray(Charsets.US_ASCII))
            output.write("fmt ".toByteArray(Charsets.US_ASCII))
            writeIntLe(output, 16)
            writeShortLe(output, 1)
            writeShortLe(output, 1)
            writeIntLe(output, sampleRate)
            writeIntLe(output, byteRate)
            writeShortLe(output, 2)
            writeShortLe(output, 16)
            output.write("data".toByteArray(Charsets.US_ASCII))
            writeIntLe(output, pcm.size)
            output.write(pcm)
        }
    }

    private fun writeWavFile(file: File, pcmFile: File, sampleRate: Int) {
        val pcmSize = pcmFile.length().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        FileOutputStream(file).use { output ->
            val byteRate = sampleRate * 2
            output.write("RIFF".toByteArray(Charsets.US_ASCII))
            writeIntLe(output, 36 + pcmSize)
            output.write("WAVE".toByteArray(Charsets.US_ASCII))
            output.write("fmt ".toByteArray(Charsets.US_ASCII))
            writeIntLe(output, 16)
            writeShortLe(output, 1)
            writeShortLe(output, 1)
            writeIntLe(output, sampleRate)
            writeIntLe(output, byteRate)
            writeShortLe(output, 2)
            writeShortLe(output, 16)
            output.write("data".toByteArray(Charsets.US_ASCII))
            writeIntLe(output, pcmSize)
            pcmFile.inputStream().use { input -> input.copyTo(output) }
        }
    }

    private fun writeIntLe(output: FileOutputStream, value: Int) {
        output.write(byteArrayOf(
            (value and 0xff).toByte(),
            ((value shr 8) and 0xff).toByte(),
            ((value shr 16) and 0xff).toByte(),
            ((value shr 24) and 0xff).toByte()
        ))
    }

    private fun writeShortLe(output: FileOutputStream, value: Int) {
        output.write(byteArrayOf(
            (value and 0xff).toByte(),
            ((value shr 8) and 0xff).toByte()
        ))
    }

    private fun finishDefinitiveLiveTranscript(recordingThread: Thread?, pcmFile: File?) {
        Thread {
            try {
                recordingThread?.join(5_000)
                if (pcmFile == null || !pcmFile.exists() || pcmFile.length() < 1024) {
                    runOnUiThread { status.text = "Nenhum áudio foi gravado." }
                    finishLiveTranscriptOutput(null)
                    return@Thread
                }
                val wavFile = File(cacheDir, "live_definitive_${System.currentTimeMillis()}.wav")
                try {
                    writeWavFile(wavFile, pcmFile, 16000)
                    val definitiveText = sendLiveChunkToServer(
                        UploadFile(wavFile, "audio/wav", "transcricao_ao_vivo_integral.wav"),
                        isFinal = true
                    ).trim()
                    if (definitiveText.isBlank()) {
                        throw IllegalStateException("A transcrição definitiva voltou vazia.")
                    }
                    synchronized(liveTranscriptText) {
                        liveTranscriptText.clear()
                        liveTranscriptText.append(definitiveText)
                        liveDraftText = ""
                        rebuildLiveTranscriptDisplayLocked()
                    }
                    runOnUiThread {
                        updateLiveTerminalText()
                        refiningTaskState = AssistantTaskState.DONE
                        renderLiveProgress()
                    }
                    finishLiveTranscriptOutput(null)
                } finally {
                    wavFile.delete()
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Definitive live transcription failed", e)
                runOnUiThread {
                    refiningTaskState = AssistantTaskState.ERROR
                    renderLiveProgress()
                    status.text = e.message ?: "Não consegui gerar a transcrição definitiva."
                }
                finishLiveTranscriptOutput(null)
            } finally {
                pcmFile?.delete()
                if (liveFullPcmFile == pcmFile) liveFullPcmFile = null
                liveFinalizing = false
            }
        }.start()
    }

    private fun finishLiveTranscriptOutput(executor: ExecutorService?) {
        Thread {
            executor?.awaitTermination(12, TimeUnit.SECONDS)
            val text = synchronized(liveTranscriptText) {
                buildString {
                    val committed = liveTranscriptText.toString().trim()
                    val draft = liveDraftText.trim()
                    if (committed.isNotBlank()) append(committed)
                    if (draft.isNotBlank()) {
                        if (isNotEmpty()) append('\n')
                        append(draft)
                    }
                }.trim()
            }
            runOnUiThread {
                if (text.isBlank()) {
                    status.text = "Transcrição ao vivo encerrada."
                    return@runOnUiThread
                }
                try {
                    val sessionDir = createSessionDir()
                    val txtFile = File(sessionDir, "transcricao_ao_vivo.txt")
                    val htmlFile = File(sessionDir, "transcricao_ao_vivo.html")
                    val logFile = File(sessionDir, "log.txt")
                    val terminalFile = File(sessionDir, "terminal.txt")
                    txtFile.writeText(text + "\n", Charsets.UTF_8)
                    htmlFile.writeText(buildLiveHtml(text), Charsets.UTF_8)
                    logFile.writeText("", Charsets.UTF_8)
                    terminalFile.writeText(text + "\n", Charsets.UTF_8)
                    File(sessionDir, "Transcricoes").mkdirs()
                    lastSession = OutputSession(sessionDir, txtFile, htmlFile, logFile, terminalFile)
                    outputFileName.visibility = View.GONE
                    outputActions.visibility = View.VISIBLE
                    buttonOutputFolder.visibility = View.GONE
                    liveTranscriptTextView.setMinLines(0)
                    livePostActions.visibility = View.VISIBLE
                    renderLiveProgress()
                    liveAiProgress.visibility = View.VISIBLE
                    status.text = ""
                    serverScroll.post { serverScroll.smoothScrollTo(0, livePostActions.bottom) }
                } catch (e: Throwable) {
                    status.text = "Não consegui gerar o arquivo de transcrição."
                }
            }
        }.start()
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
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_PICK_FOLDER)
    }

    private fun openOutputFolderPicker(requestCode: Int) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, requestCode)
    }

    private fun handlePickedMedia(data: Intent?) {
        val nextItems = mutableListOf<MediaItem>()
        val clipData = data?.clipData
        if (clipData != null) {
            for (index in 0 until clipData.itemCount) {
                val uri = clipData.getItemAt(index).uri
                takeReadPermission(uri, data.flags)
                addMediaItem(uri, nextItems)
            }
        } else {
            data?.data?.let { uri ->
                takeReadPermission(uri, data.flags)
                addMediaItem(uri, nextItems)
            }
        }
        applySelection(nextItems)
    }

    private fun handlePickedFolder(treeUri: Uri, flags: Int) {
        takeTreePermission(treeUri)
        val folder = DocumentFile.fromTreeUri(this, treeUri)
        val nextItems = mutableListOf<MediaItem>()
        folder?.listFiles()?.forEach { file ->
            if (file.isFile) {
                val name = file.name ?: "midia_${nextItems.size + 1}"
                val mime = file.type ?: guessMime(name)
                if (isSupportedMedia(mime, name)) {
                    nextItems += MediaItem(file.uri, name, mime, readDuration(file.uri))
                }
            }
        }
        applySelection(nextItems)
        if (nextItems.isEmpty()) status.text = "Não encontrei áudio ou vídeo nessa pasta."
    }

    private fun addMediaItem(uri: Uri, target: MutableList<MediaItem>) {
        val name = queryDisplayName(uri) ?: "midia_${target.size + 1}"
        val mime = contentResolver.getType(uri) ?: guessMime(name)
        if (isSupportedMedia(mime, name)) {
            target += MediaItem(uri, name, mime, readDuration(uri))
        }
    }

    private fun applySelection(items: List<MediaItem>) {
        releasePreviewPlayers()
        selectedItems.clear()
        selectedItems.addAll(items)
        if (!recordingActive) whiteMicSelection = false
        selectedPrepareMode = if (items.isNotEmpty()) PrepareMode.READY else null
        updatePrepareModeButtons()
        clearOutputResult()
        status.text = ""
        terminalText.text = "$ faster-whisper-server --${items.size} arquivo(s) selecionado(s)"
        buttonSelectOutputFolder.visibility = if (items.isNotEmpty()) View.VISIBLE else View.GONE
        arrowInputOutput.visibility = if (items.isNotEmpty()) View.VISIBLE else View.GONE
        buttonSelectOutputFolder.setBackgroundResource(
            if (preSelectedOutputDirUri != null) R.drawable.ffmpeg_outline_green_button_bg else R.drawable.ffmpeg_outline_button_bg
        )

        if (items.isEmpty()) {
            selectedFile.visibility = View.GONE
            selectedListBox.visibility = View.GONE
            showSinglePreview(null)
        } else if (items.size == 1) {
            selectedFile.text = "1 arquivo selecionado"
            selectedFile.visibility = View.VISIBLE
            selectedListBox.visibility = View.GONE
            showSinglePreview(items.first())
        } else {
            selectedFile.text = "${items.size} arquivos selecionados"
            selectedFile.visibility = View.VISIBLE
            selectedList.text = ""
            selectedListBox.visibility = View.GONE
            showSinglePreview(null)
        }
        updateTranscribeEnabled()
    }

    private fun showPrepareModeHelp() {
        AlertDialog.Builder(this)
            .setTitle("Formato de envio")
            .setMessage(
                "Enviar compactado é ideal para conexões limitadas, pois consome menos dados.\n\n" +
                    "Enviar pronto exige mais da conexão, mas ajuda na velocidade de processamento do servidor.\n\n" +
                    "Enviar como está não consome nenhum processamento do telefone e é ideal para telefones antigos."
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun selectPrepareMode(mode: PrepareMode) {
        selectedPrepareMode = mode
        updatePrepareModeButtons()
        updateTranscribeEnabled()
    }

    private fun updatePrepareModeButtons() {
        prepareModeButtons.visibility = if (selectedItems.isNotEmpty()) View.VISIBLE else View.GONE
        videoPrepareWarning.visibility = View.GONE

        val selected = selectedPrepareMode
        buttonCompactFiles.setBackgroundResource(
            if (selected == PrepareMode.COMPACT) R.drawable.ffmpeg_outline_green_button_bg else R.drawable.ffmpeg_outline_button_bg
        )
        buttonReadyFiles.setBackgroundResource(
            if (selected == PrepareMode.READY) R.drawable.ffmpeg_outline_green_button_bg else R.drawable.ffmpeg_outline_button_bg
        )
        buttonOriginalFiles.setBackgroundResource(
            if (selected == PrepareMode.ORIGINAL) R.drawable.ffmpeg_outline_green_button_bg else R.drawable.ffmpeg_outline_button_bg
        )
    }

    private fun showSinglePreview(item: MediaItem?) {
        val visible = item != null
        playbackControls.visibility = if (visible) View.VISIBLE else View.GONE
        timelineFrame.visibility = if (visible) View.VISIBLE else View.GONE
        currentTime.visibility = if (visible) View.VISIBLE else View.GONE
        timeFields.visibility = if (visible) View.VISIBLE else View.GONE
        audioWaveform.visibility = View.GONE
        previewFrame.visibility = View.GONE
        videoPreview.visibility = View.GONE
        if (item == null) return

        durationMs = item.durationMs.coerceAtLeast(1L)
        timeline.isEnabled = true
        timeline.setRange(durationMs, 0L, durationMs)
        timeline.setCurrent(0L)
        updateTimeFields(0L, durationMs)
        currentTime.text = formatTime(0L)
        playbackSpeed = 1f
        updateSpeedButton()

        if (isVideo(item.mime, item.name)) {
            previewFrame.visibility = View.VISIBLE
            videoPreview.visibility = View.VISIBLE
            if (videoPreview.isAvailable) {
                previewSurface = Surface(videoPreview.surfaceTexture)
                prepareVideoPreview(item.uri)
            }
        } else {
            audioWaveform.visibility = View.VISIBLE
            audioWaveform.configure(item.name, durationMs)
            audioWaveform.setRange(0L, durationMs)
            prepareAudioPreview(item.uri)
        }
    }

    private fun prepareVideoPreview(uri: Uri) {
        val surface = previewSurface ?: return
        releasePreviewPlayer()
        previewPlayer = MediaPlayer().apply {
            setDataSource(this@FasterWhisperServerActivity, uri)
            setSurface(surface)
            setOnPreparedListener { player ->
                this@FasterWhisperServerActivity.durationMs = player.duration.toLong().coerceAtLeast(durationMs)
                this@FasterWhisperServerActivity.videoWidth = player.videoWidth
                this@FasterWhisperServerActivity.videoHeight = player.videoHeight
                timeline.setRange(durationMs, timeline.getStartMs(), timeline.getEndMs().coerceAtMost(durationMs))
                applyPreviewTransform()
                seekPreview(0L)
            }
            setOnCompletionListener {
                setPlaybackButtonPlaying(false)
                timeline.setCurrent(timeline.getEndMs())
            }
            prepareAsync()
        }
    }

    private fun prepareAudioPreview(uri: Uri) {
        releaseAudioPlayer()
        audioPlayer = MediaPlayer().apply {
            setDataSource(this@FasterWhisperServerActivity, uri)
            setOnPreparedListener { player ->
                durationMs = player.duration.toLong().coerceAtLeast(durationMs)
                timeline.setRange(durationMs, timeline.getStartMs(), timeline.getEndMs().coerceAtMost(durationMs))
                audioWaveform.configure(selectedItems.firstOrNull()?.name ?: "áudio", durationMs)
                audioWaveform.setRange(timeline.getStartMs(), timeline.getEndMs())
            }
            setOnCompletionListener {
                setPlaybackButtonPlaying(false)
                timeline.setCurrent(timeline.getEndMs())
            }
            prepareAsync()
        }
    }

    private fun startServerTranscription() {
        val whiteRecording = whiteMicSelection
        if (whiteRecording) {
            progressPhase = ProgressPhase.WHITE_TRANSCRIPTION
            transcriptionTaskState = AssistantTaskState.RUNNING
            liveAiProgress.visibility = View.VISIBLE
            liveTranscriptTextView.visibility = View.VISIBLE
            buttonPasteTranscript.visibility = View.VISIBLE
            liveTranscriptTextView.text = ""
            livePostActions.visibility = View.GONE
            outputActions.visibility = View.GONE
            renderLiveProgress()
        } else {
            hideLiveTranscriptUi()
        }
        val items = selectedItems.toList()
        if (items.isEmpty()) return
        if (serverBaseUrl.isBlank()) {
            status.text = "Informe e teste o IP do servidor."
            return
        }
        val prepareMode = selectedPrepareMode ?: run {
            status.text = "Escolha uma forma de envio."
            updateTranscribeEnabled()
            return
        }

        clearOutputResult()
        setProcessing(true)
        val startedAt = SystemClock.elapsedRealtime()
        val terminalLines = newTerminalSession("$ faster-whisper-server --server $serverBaseUrl --stream true")
        val logLines = StringBuilder()
        val results = mutableListOf<TranscriptionResult>()
        val serverStartedAt = AtomicLong(0L)
        val serverFinishedAt = AtomicLong(0L)

        Thread {
            var sessionDir: File? = null
            try {
                sessionDir = createSessionDir()
                val transcriptionDir = File(sessionDir, "Transcricoes").apply { mkdirs() }
                val tempDir = File(cacheDir, "faster_whisper_server_temp_${System.currentTimeMillis()}").apply { mkdirs() }
                appendLog(logLines, "Servidor: $serverBaseUrl")
                appendLog(logLines, "Arquivos: ${items.size}")
                appendLog(logLines, "Preparo: ${prepareMode.label}")
                val cores = Runtime.getRuntime().availableProcessors()
                val parallelism = conversionParallelism()
                appendTerminal(terminalLines, "CPU: $cores núcleo(s); preparação paralela: $parallelism conversão(ões) por vez")
                val uploadParallelism = uploadParallelism(items.size)
                appendTerminal(terminalLines, "envio em esteira: até $uploadParallelism requisição(ões) simultânea(s)")
                appendLog(logLines, "Núcleos detectados: $cores")
                appendLog(logLines, "Conversões paralelas: $parallelism")
                appendLog(logLines, "Requisições paralelas: $uploadParallelism")
                runOnUiThread { updateTerminalText(terminalLines) }

                val conversionExecutor = Executors.newFixedThreadPool(parallelism)
                val prepareCompletion = ExecutorCompletionService<PreparedUpload>(conversionExecutor)
                val uploadExecutor = Executors.newFixedThreadPool(uploadParallelism)
                val uploadCompletion = ExecutorCompletionService<TranscriptionResult>(uploadExecutor)
                items.forEachIndexed { index, item ->
                    prepareCompletion.submit(prepareUploadTask(items, prepareMode, tempDir, terminalLines, index, item))
                }

                var totalSentSeconds = 0.0
                var submittedUploads = 0
                try {
                    repeat(items.size) {
                        ensureNotCancelled()
                        val prepared = prepareCompletion.take().get()
                        ensureNotCancelled()
                        totalSentSeconds += prepared.durationMs / 1000.0
                        uploadCompletion.submit(uploadTask(prepared, items.size, transcriptionDir, terminalLines, serverStartedAt, serverFinishedAt))
                        submittedUploads++
                    }

                    repeat(submittedUploads) {
                        ensureNotCancelled()
                        results += uploadCompletion.take().get()
                    }
                } finally {
                    conversionExecutor.shutdownNow()
                    uploadExecutor.shutdownNow()
                }

                val orderedResults = results.sortedBy { it.index }
                val finalText = buildTranscriptionsText(orderedResults)
                tempDir.deleteRecursively()
                val elapsedMs = SystemClock.elapsedRealtime() - startedAt
                val serverElapsedMs = calculateServerElapsedMs(serverStartedAt, serverFinishedAt)
                val txtFile = File(sessionDir, "transcricoes.txt")
                val htmlFile = File(sessionDir, "transcricoes.html")
                val logFile = File(sessionDir, "log.txt")
                val terminalFile = File(sessionDir, "terminal.txt")
                txtFile.writeText(finalText, Charsets.UTF_8)
                htmlFile.writeText(buildHtml(orderedResults), Charsets.UTF_8)
                val report = buildReport(items.size, totalSentSeconds, elapsedMs, serverElapsedMs, prepareMode)
                appendLog(logLines, report)
                logFile.writeText(logLines.toString(), Charsets.UTF_8)
                terminalFile.writeText(snapshotText(terminalLines), Charsets.UTF_8)
                appendTerminal(terminalLines, "")
                appendTerminal(terminalLines, report)

                runOnUiThread {
                    lastSession = OutputSession(sessionDir, txtFile, htmlFile, logFile, terminalFile)
                    lastTranscriptionResults = orderedResults
                    status.text = report
                    outputFileName.visibility = View.GONE
                    outputActions.visibility = View.VISIBLE
                    buttonOutputFolder.visibility = View.GONE
                    if (whiteRecording) {
                        val transcript = orderedResults.firstOrNull()?.text.orEmpty().trim()
                        replaceLiveTranscript(transcript)
                        transcriptionTaskState = AssistantTaskState.DONE
                        renderLiveProgress()
                        livePostActions.visibility = View.VISIBLE
                        terminalText.visibility = View.GONE
                    } else {
                        val transcriptDisplay = buildTranscriptDisplayText(orderedResults)
                        liveTranscriptTextView.text = transcriptDisplay
                        liveTranscriptTextView.setMinLines(0)
                        liveTranscriptTextView.visibility = View.VISIBLE
                        buttonPasteTranscript.visibility = View.VISIBLE
                        livePostActions.visibility = View.VISIBLE
                        updateTerminalText(terminalLines)
                    }
                    setProcessing(false)
                    serverScroll.post { serverScroll.fullScroll(View.FOCUS_DOWN) }
                }
            } catch (e: CancellationException) {
                val elapsedMs = SystemClock.elapsedRealtime() - startedAt
                appendTerminal(terminalLines, "CANCELADO: transcrição cancelada pelo usuário")
                appendTerminal(terminalLines, "Tempo decorrido: ${formatElapsedCompact(elapsedMs)}")
                sessionDir?.let { writeFailureFiles(it, terminalLines, logLines, "Cancelado após ${formatElapsedCompact(elapsedMs)}") }
                runOnUiThread {
                    if (whiteRecording) {
                        transcriptionTaskState = AssistantTaskState.ERROR
                        renderLiveProgress()
                    }
                    status.text = "Transcrição cancelada."
                    setProcessing(false)
                    updateTerminalText(terminalLines)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Server transcription failed", e)
                val elapsedMs = SystemClock.elapsedRealtime() - startedAt
                val message = e.message ?: "falha inesperada"
                appendTerminal(terminalLines, "ERROR: $message")
                appendTerminal(terminalLines, "Tempo decorrido: ${formatElapsedCompact(elapsedMs)}")
                sessionDir?.let { writeFailureFiles(it, terminalLines, logLines, "Erro: $message") }
                runOnUiThread {
                    if (whiteRecording) {
                        transcriptionTaskState = AssistantTaskState.ERROR
                        renderLiveProgress()
                    }
                    status.text = "Erro: $message\nTempo decorrido: ${formatElapsedCompact(elapsedMs)}"
                    setProcessing(false)
                    updateTerminalText(terminalLines)
                }
            }
        }.start()
    }

    private fun prepareUploadTask(
        items: List<MediaItem>,
        mode: PrepareMode,
        tempDir: File,
        terminalLines: StringBuilder,
        index: Int,
        item: MediaItem
    ): Callable<PreparedUpload> {
        return Callable {
            ensureNotCancelled()
            val number = index + 1
            appendTerminal(terminalLines, "")
            appendTerminal(terminalLines, "prepare input[$number/${items.size}]: ${item.name}")
            runOnUiThread {
                status.text = "Preparando $number/${items.size}: ${item.name}"
                updateTerminalText(terminalLines)
            }

            val inputFile = copyUriToCache(item.uri, item.name)
            val originalAudioInfo = describeAudioFile(inputFile)
            val startMs = if (items.size == 1) timeline.getStartMs() else 0L
            val endMs = if (items.size == 1) timeline.getEndMs() else item.durationMs.coerceAtLeast(1L)
            val durationToSend = (endMs - startMs).coerceAtLeast(1L)
            val uploadFile = prepareUploadFile(
                mode = mode,
                inputFile = inputFile,
                tempDir = tempDir,
                index = number,
                item = item,
                startMs = startMs,
                durationMs = durationToSend,
                terminalLines = terminalLines
            )
            val sentAudioInfo = describeAudioFile(uploadFile.file)
            appendTerminal(terminalLines, "prepare done[$number/${items.size}]: ${item.name}")
            runOnUiThread { updateTerminalText(terminalLines) }
            PreparedUpload(number, item, uploadFile, durationToSend, originalAudioInfo, sentAudioInfo)
        }
    }

    private fun conversionParallelism(): Int {
        return ConversionParallelismSettings.selected(this)
    }

    private fun uploadParallelism(itemCount: Int): Int {
        return itemCount.coerceAtMost(SERVER_WORKERS).coerceAtLeast(1)
    }

    private fun uploadTask(
        prepared: PreparedUpload,
        itemCount: Int,
        transcriptionDir: File,
        terminalLines: StringBuilder,
        serverStartedAt: AtomicLong,
        serverFinishedAt: AtomicLong
    ): Callable<TranscriptionResult> {
        return Callable {
            ensureNotCancelled()
            val item = prepared.item
            val number = prepared.index
            appendTerminal(terminalLines, "upload liberado: ${item.name}")
            appendTerminalAudioInfo(terminalLines, "original: ${prepared.originalAudioInfo}")
            appendTerminalAudioInfo(terminalLines, "enviado: ${prepared.sentAudioInfo}")
            runOnUiThread {
                status.text = "Enviando arquivo pronto $number/$itemCount: ${item.name}"
                updateTerminalText(terminalLines)
            }

            val text = sendToServer(prepared.uploadFile, item.name, number, itemCount, terminalLines, serverStartedAt, serverFinishedAt)
            if (text.isBlank()) {
                appendTerminal(terminalLines, "transcrição vazia")
                throw IllegalStateException("transcrição vazia em ${item.name}")
            }
            val individual = uniqueFile(transcriptionDir, "${safeBaseName(item.name)}.txt")
            individual.writeText(text.trim() + "\n", Charsets.UTF_8)
            runOnUiThread { updateTerminalText(terminalLines) }
            TranscriptionResult(number, item.name, text.trim())
        }
    }

    private fun prepareUploadFile(
        mode: PrepareMode,
        inputFile: File,
        tempDir: File,
        index: Int,
        item: MediaItem,
        startMs: Long,
        durationMs: Long,
        terminalLines: StringBuilder
    ): UploadFile {
        return when (mode) {
            PrepareMode.COMPACT -> prepareCompactUpload(inputFile, tempDir, index, item, startMs, durationMs, terminalLines)
            PrepareMode.ORIGINAL -> prepareOriginalUpload(inputFile, tempDir, index, item, startMs, durationMs, terminalLines)
            PrepareMode.READY -> {
                val fullFile = startMs <= 0L && durationMs >= item.durationMs - 10L
                if (fullFile && isAlreadyReadyWav(inputFile, item.name, terminalLines)) {
                    appendTerminal(terminalLines, "[${item.name}] conversão ignorada: WAV já está pronto para envio")
                    return UploadFile(inputFile, "audio/wav", "wav original 16 kHz mono PCM s16le")
                }
                val wavFile = File(tempDir, "${index}_${safeBaseName(item.name)}.wav")
                convertToReadyWav(inputFile, wavFile, item.name, startMs, durationMs, terminalLines)
                UploadFile(wavFile, "audio/wav", "wav 16 kHz mono PCM s16le")
            }
        }
    }

    private fun prepareOriginalUpload(
        inputFile: File,
        tempDir: File,
        index: Int,
        item: MediaItem,
        startMs: Long,
        durationMs: Long,
        terminalLines: StringBuilder
    ): UploadFile {
        val fullFile = startMs <= 0L && durationMs >= item.durationMs - 10L
        val probe = probeAudioFile(inputFile)

        if (fullFile && !probe.hasVideo && !isVideo(item.mime, item.name)) {
            appendTerminal(terminalLines, "[${item.name}] envio direto: áudio original sem conversão")
            return UploadFile(inputFile, contentMimeForUpload(item), "arquivo original")
        }

        val ext = "m4a"
        val extractedFile = File(tempDir, "${index}_${safeBaseName(item.name)}_extracted.$ext")

        appendTerminal(terminalLines, "[${item.name}] extraindo áudio original (m4a)...")
        val arguments = mutableListOf("-y")
        if (startMs > 0L) arguments.addAll(listOf("-ss", formatSeconds(startMs)))
        arguments.addAll(listOf("-i", inputFile.absolutePath))
        if (!fullFile) arguments.addAll(listOf("-t", formatSeconds(durationMs)))
        
        if (probe.codec.contains("aac") || probe.codec.contains("mp4a") || probe.codec.contains("alac")) {
            arguments.addAll(listOf("-vn", "-c:a", "copy"))
        } else {
            arguments.addAll(listOf("-vn", "-c:a", "aac", "-b:a", "256k"))
        }
        arguments.add(extractedFile.absolutePath)

        val session = FFmpegKit.executeWithArguments(arguments.toTypedArray())
        if (!ReturnCode.isSuccess(session.returnCode)) {
            appendTerminal(terminalLines, "[${item.name}] falha ao extrair áudio, usando conversão de segurança (FLAC)...")
            val flacFile = File(tempDir, "${index}_${safeBaseName(item.name)}_extracted.flac")
            val fallbackArgs = mutableListOf("-y")
            if (startMs > 0L) fallbackArgs.addAll(listOf("-ss", formatSeconds(startMs)))
            fallbackArgs.addAll(listOf("-i", inputFile.absolutePath))
            if (!fullFile) fallbackArgs.addAll(listOf("-t", formatSeconds(durationMs)))
            fallbackArgs.addAll(listOf("-vn", "-c:a", "flac", flacFile.absolutePath))
            
            val fallbackSession = FFmpegKit.executeWithArguments(fallbackArgs.toTypedArray())
            if (!ReturnCode.isSuccess(fallbackSession.returnCode)) {
                val tail = fallbackSession.allLogsAsString.orEmpty().takeLast(1000).replace("\n", "  ")
                throw IllegalStateException("falha ao extrair áudio FLAC. $tail")
            }
            return UploadFile(flacFile, "audio/flac", "áudio extraído (FLAC)")
        }

        return UploadFile(extractedFile, "audio/mp4", "áudio extraído original")
    }

    private fun prepareCompactUpload(
        inputFile: File,
        tempDir: File,
        index: Int,
        item: MediaItem,
        startMs: Long,
        durationMs: Long,
        terminalLines: StringBuilder
    ): UploadFile {
        val fullFile = startMs <= 0L && durationMs >= item.durationMs - 10L
        if (fullFile && isAlreadyCompact(inputFile, item.name, terminalLines)) {
            val mime = "audio/ogg"
            appendTerminal(terminalLines, "[${item.name}] conversão ignorada: arquivo já está compacto")
            return UploadFile(inputFile, mime, "arquivo compacto original")
        }
        val oggFile = File(tempDir, "${index}_${safeBaseName(item.name)}.ogg")
        convertToCompactOgg(inputFile, oggFile, item.name, startMs, durationMs, terminalLines)
        return UploadFile(oggFile, "audio/ogg", "ogg opus 16 kHz mono 32k")
    }

    private fun contentMimeForUpload(item: MediaItem): String {
        if (item.mime.isNotBlank() && item.mime != "application/octet-stream") return item.mime
        return when {
            isVideo("", item.name) -> "video/mp4"
            item.name.lowercase(Locale.ROOT).endsWith(".mp3") -> "audio/mpeg"
            item.name.lowercase(Locale.ROOT).endsWith(".wav") -> "audio/wav"
            item.name.lowercase(Locale.ROOT).endsWith(".ogg") -> "audio/ogg"
            item.name.lowercase(Locale.ROOT).endsWith(".opus") -> "audio/opus"
            item.name.lowercase(Locale.ROOT).endsWith(".m4a") -> "audio/mp4"
            else -> "application/octet-stream"
        }
    }

    private fun isAlreadyCompact(inputFile: File, originalName: String, terminalLines: StringBuilder): Boolean {
        val lower = originalName.lowercase(Locale.ROOT)
        if (!lower.endsWith(".ogg")) return false
        appendTerminal(terminalLines, "[${originalName}] testando formato compacto")
        val session = FFmpegKit.executeWithArguments(arrayOf("-hide_banner", "-i", inputFile.absolutePath))
        val logs = session.allLogsAsString.orEmpty().lowercase(Locale.ROOT)
        val isAudioLineCompatible = logs.contains("audio:") &&
            logs.contains("16000 hz") &&
            logs.contains("mono") &&
            (logs.contains("32 kb/s") || logs.contains("32kb/s") || logs.contains("bitrate: 32 kb/s"))
        return isAudioLineCompatible
    }

    private fun isAlreadyReadyWav(inputFile: File, originalName: String, terminalLines: StringBuilder): Boolean {
        if (!originalName.lowercase(Locale.ROOT).endsWith(".wav")) return false
        appendTerminal(terminalLines, "[${originalName}] analisando metadados para envio pronto")
        val probe = probeAudioFile(inputFile)
        appendTerminal(terminalLines, "[${originalName}] metadados: ${metadataSummary(probe)}")
        return !probe.hasVideo &&
            probe.codec == "pcm_s16le" &&
            probe.sampleRateHz == 16000 &&
            probe.channelCount == 1
    }

    private fun metadataSummary(probe: AudioProbe): String {
        return listOf(
            "codec=${probe.codec.ifBlank { "?" }}",
            "hz=${probe.sampleRate.ifBlank { "?" }}",
            "canais=${probe.channels.ifBlank { "?" }}",
            "bitrate=${probe.bitrate.ifBlank { "?" }}",
            "video=${if (probe.hasVideo) "sim" else "não"}"
        ).joinToString(", ")
    }

    private fun describeAudioFile(file: File): String {
        val info = probeAudioFile(file)
        return listOf(
            ".${file.extension.lowercase(Locale.ROOT).ifBlank { "sem extensão" }}",
            info.sampleRate.ifBlank { "hz ?" },
            info.channels.ifBlank { "canal ?" },
            info.bitrate.ifBlank { "bitrate ?" }
        ).joinToString(", ")
    }

    private fun probeAudioFile(file: File): AudioProbe {
        return try {
            val session = FFmpegKit.executeWithArguments(arrayOf("-hide_banner", "-i", file.absolutePath))
            val logs = session.allLogsAsString.orEmpty()
            val audioLine = logs.lines().firstOrNull { it.contains("Audio:", ignoreCase = true) }.orEmpty()
            val codec = Regex("""Audio:\s*([^,\s]+)""", RegexOption.IGNORE_CASE)
                .find(audioLine)
                ?.groupValues
                ?.getOrNull(1)
                ?.lowercase(Locale.ROOT)
                .orEmpty()
            val sampleRateHz = Regex("""(\d+)\s*Hz""", RegexOption.IGNORE_CASE)
                .find(audioLine)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
            val channelCount = when {
                audioLine.contains("mono", ignoreCase = true) -> 1
                audioLine.contains("stereo", ignoreCase = true) -> 2
                else -> Regex("""(\d+)\s*channels""", RegexOption.IGNORE_CASE)
                    .find(audioLine)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
            }
            val durationSeconds = parseDurationSeconds(logs)
            val parsedBitrateKbps = Regex("""(\d+(?:\.\d+)?)\s*kb/s""", RegexOption.IGNORE_CASE)
                .find(audioLine.ifBlank { logs })
                ?.groupValues
                ?.getOrNull(1)
                ?.toDoubleOrNull()
            val bitrateKbps = parsedBitrateKbps
                ?: durationSeconds?.takeIf { it > 0.0 }?.let { (file.length() * 8.0) / it / 1000.0 }
            AudioProbe(
                codec = codec,
                sampleRate = sampleRateHz?.let { "${it}hz" }.orEmpty(),
                channels = when (channelCount) {
                    1 -> "mono"
                    2 -> "stereo"
                    null -> ""
                    else -> "${channelCount}ch"
                },
                bitrate = bitrateKbps?.let { formatKbps(it) }.orEmpty(),
                sampleRateHz = sampleRateHz,
                channelCount = channelCount,
                bitrateKbps = bitrateKbps,
                hasVideo = logs.lines().any { it.contains("Video:", ignoreCase = true) }
            )
        } catch (_: Throwable) {
            AudioProbe("", "", "", "", null, null, null, false)
        }
    }

    private fun parseDurationSeconds(logs: String): Double? {
        val match = Regex("""Duration:\s*(\d+):(\d+):(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE).find(logs)
            ?: return null
        val hours = match.groupValues.getOrNull(1)?.toDoubleOrNull() ?: return null
        val minutes = match.groupValues.getOrNull(2)?.toDoubleOrNull() ?: return null
        val seconds = match.groupValues.getOrNull(3)?.toDoubleOrNull() ?: return null
        return hours * 3600.0 + minutes * 60.0 + seconds
    }

    private fun formatKbps(kbps: Double): String {
        val rounded = Math.round(kbps).toLong()
        return if (rounded >= 1000L) {
            String.format(Locale.US, "%.1f mb/s", kbps / 1000.0)
        } else {
            "${rounded}k"
        }
    }

    private fun convertToCompactOgg(
        inputFile: File,
        outputFile: File,
        originalName: String,
        startMs: Long,
        durationMs: Long,
        terminalLines: StringBuilder
    ) {
        val arguments = mutableListOf("-y")
        if (startMs > 0L) arguments.addAll(listOf("-ss", formatSeconds(startMs)))
        arguments.addAll(listOf("-i", inputFile.absolutePath))
        arguments.addAll(
            listOf(
                "-t", formatSeconds(durationMs),
                "-vn",
                "-map", "0:a:0",
                "-ac", "1",
                "-ar", "16000",
                "-c:a", "libopus",
                "-b:a", "32k",
                "-f", "ogg",
                outputFile.absolutePath
            )
        )
        appendTerminal(terminalLines, "# original: $originalName")
        appendTerminal(terminalLines, "ffmpeg ${arguments.joinToString(" ")}")
        runOnUiThread { updateTerminalText(terminalLines) }
        val session = executeFfmpegWithTerminal(arguments.toTypedArray(), terminalLines)
        if (!ReturnCode.isSuccess(session.returnCode) || !outputFile.exists() || outputFile.length() == 0L) {
            val tail = session.allLogsAsString.orEmpty().lines().takeLast(3).joinToString(" ")
            throw IllegalStateException("falha ao gerar áudio .ogg. ${tail.take(160)}")
        }
    }

    private fun convertToReadyWav(
        inputFile: File,
        outputFile: File,
        originalName: String,
        startMs: Long,
        durationMs: Long,
        terminalLines: StringBuilder
    ) {
        val arguments = mutableListOf("-y")
        if (startMs > 0L) arguments.addAll(listOf("-ss", formatSeconds(startMs)))
        arguments.addAll(listOf("-i", inputFile.absolutePath))
        arguments.addAll(
            listOf(
                "-t", formatSeconds(durationMs),
                "-vn",
                "-map", "0:a:0",
                "-ac", "1",
                "-ar", "16000",
                "-c:a", "pcm_s16le",
                "-f", "wav",
                outputFile.absolutePath
            )
        )
        appendTerminal(terminalLines, "# original: $originalName")
        appendTerminal(terminalLines, "ffmpeg ${arguments.joinToString(" ")}")
        runOnUiThread { updateTerminalText(terminalLines) }
        val session = executeFfmpegWithTerminal(arguments.toTypedArray(), terminalLines)
        if (!ReturnCode.isSuccess(session.returnCode) || !outputFile.exists() || outputFile.length() == 0L) {
            val tail = session.allLogsAsString.orEmpty().lines().takeLast(3).joinToString(" ")
            throw IllegalStateException("falha ao gerar áudio .wav. ${tail.take(160)}")
        }
    }

    private fun executeFfmpegWithTerminal(arguments: Array<String>, terminalLines: StringBuilder): FFmpegSession {
        val latch = CountDownLatch(1)
        val sessionRef = AtomicReference<FFmpegSession>()
        val session = FFmpegKit.executeWithArgumentsAsync(
            arguments,
            { completed ->
                sessionRef.set(completed)
                latch.countDown()
            },
            { log ->
                val text = log.message.orEmpty().trimEnd()
                if (text.isNotBlank()) {
                    appendTerminal(terminalLines, text)
                    runOnUiThread { updateTerminalText(terminalLines) }
                }
            },
            { statistics ->
                appendTerminal(terminalLines, "ffmpeg stats: time=${statistics.time} size=${statistics.size} speed=${statistics.speed}")
                runOnUiThread { updateTerminalText(terminalLines) }
            }
        )
        currentFfmpegSessionId = session.sessionId
        latch.await()
        currentFfmpegSessionId = null
        ensureNotCancelled()
        return sessionRef.get() ?: session
    }

    private fun sendToServer(
        uploadFile: UploadFile,
        originalName: String,
        itemIndex: Int,
        itemCount: Int,
        terminalLines: StringBuilder,
        serverStartedAt: AtomicLong,
        serverFinishedAt: AtomicLong
    ): String {
        var lastError: Throwable? = null
        while (true) {
            val baseUrl = serverBaseUrl
            try {
                return sendToServerOnce(uploadFile, originalName, itemIndex, itemCount, terminalLines, baseUrl, serverStartedAt, serverFinishedAt)
            } catch (e: ServerUnavailableException) {
                lastError = e
                val nextUrl = activateNextServerAfterFailure(terminalLines, baseUrl)
                if (nextUrl == null) break
                appendTerminal(terminalLines, "tentando próximo servidor: $nextUrl")
                runOnUiThread {
                    status.text = "Tentando próximo servidor..."
                    updateTerminalText(terminalLines)
                }
            }
        }
        throw lastError ?: ServerUnavailableException()
    }

    private fun sendToServerOnce(
        uploadFile: UploadFile,
        originalName: String,
        itemIndex: Int,
        itemCount: Int,
        terminalLines: StringBuilder,
        baseUrl: String,
        serverStartedAt: AtomicLong,
        serverFinishedAt: AtomicLong
    ): String {
        appendTerminal(terminalLines, "upload[$itemIndex/$itemCount]: ${uploadFile.file.name} (${uploadFile.file.length() / 1024} KB, ${uploadFile.label})")
        runOnUiThread {
            status.text = "Enviando $itemIndex/$itemCount: $originalName"
            updateTerminalText(terminalLines)
        }

        val textBuilder = StringBuilder()
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("stream", "true")
            .addFormDataPart("file", uploadFile.file.name, uploadFile.file.asRequestBody(uploadFile.mime.toMediaType()))
            .build()
        val request = Request.Builder()
            .url("$baseUrl/v1/audio/transcriptions")
            .post(requestBody)
            .build()

        val call = client.newCall(request)
        currentCalls.add(call)
        try {
            serverStartedAt.compareAndSet(0L, SystemClock.elapsedRealtime())
            call.execute().use { response ->
            appendTerminal(terminalLines, "http ${response.code} ${response.message}")
            if (!response.isSuccessful) {
                if (response.code == 500) {
                    throw ServerUnavailableException()
                }
                val body = response.body?.string().orEmpty().take(240)
                throw IllegalStateException("servidor respondeu ${response.code}. $body")
            }
            val body = response.body ?: throw IllegalStateException("resposta sem corpo")
            val source = body.source()
            while (!source.exhausted()) {
                ensureNotCancelled()
                val line = source.readUtf8Line() ?: break
                if (line.isBlank()) continue
                val delta = extractTextDelta(line)
                if (delta.isNotBlank()) {
                    appendTerminalTranscription(terminalLines, delta)
                    textBuilder.append(delta)
                } else if (!isServerEnvelopeLine(line)) {
                    appendTerminal(terminalLines, line)
                }
                runOnUiThread {
                    status.text = "Transcrevendo $itemIndex/$itemCount: $originalName"
                    updateTerminalText(terminalLines)
                }
            }
            }
        } finally {
            markServerFinished(serverFinishedAt)
            currentCalls.remove(call)
        }
        return textBuilder.toString()
    }

    private fun activateNextServerAfterFailure(terminalLines: StringBuilder, failedBaseUrl: String): String? {
        synchronized(this) {
            if (serverBaseUrl.isNotBlank() && serverBaseUrl != failedBaseUrl) {
                return serverBaseUrl
            }
            val nextIndex = serverIpIndex + 1
            if (nextIndex !in serverFallbackIps.indices) return null
            for (index in nextIndex until serverFallbackIps.size) {
                val ip = serverFallbackIps[index]
                appendTerminal(terminalLines, "servidor indisponível; testando fallback ${index + 1}/${serverFallbackIps.size}: ${serverNameForIp(ip)} ($ip)")
                if (pingIp(ip)) {
                    serverIpIndex = index
                    serverBaseUrl = "http://$ip:$SERVER_PORT"
                    runOnUiThread {
                        val serverName = serverNameForIp(ip)
                        buttonServerSelector.text = "Servidor: $serverName"
                        status.text = ModelSelectionSummary.current()
                    }
                    return serverBaseUrl
                }
            }
            return null
        }
    }

    private fun extractTextDelta(rawLine: String): String {
        var line = rawLine.trim()
        if (line.startsWith("event:") || line.startsWith("id:") || line.startsWith("retry:")) return ""
        if (line.startsWith("data:")) line = line.removePrefix("data:").trim()
        if (line == "[DONE]") return ""
        if (line.isBlank()) return ""
        return if (line.startsWith("{")) {
            try {
                val json = JSONObject(line)
                json.optString("text")
                    .ifBlank { json.optString("delta") }
                    .ifBlank {
                        val choices = json.optJSONArray("choices")
                        val choice = choices?.optJSONObject(0)
                        val delta = choice?.optJSONObject("delta")
                        delta?.optString("content").orEmpty().ifBlank { choice?.optString("text").orEmpty() }
                    }
                    .ifBlank {
                        val segments = json.optJSONArray("segments") ?: return@ifBlank ""
                        buildString {
                            for (i in 0 until segments.length()) {
                                append(segments.optJSONObject(i)?.optString("text").orEmpty())
                            }
                        }
                    }
            } catch (_: Throwable) {
                ""
            }
        } else {
            line
        }
    }

    private fun isServerEnvelopeLine(rawLine: String): Boolean {
        val line = rawLine.trim()
        return line.startsWith("data:") ||
            line.startsWith("event:") ||
            line.startsWith("id:") ||
            line.startsWith("retry:") ||
            line == "[DONE]"
    }

    private fun cancelTranscription() {
        cancelRequested = true
        status.text = "Cancelando..."
        synchronized(currentCalls) {
            currentCalls.forEach { it.cancel() }
        }
        FFmpegKit.cancel()
    }

    private fun setProcessing(processing: Boolean) {
        isProcessing = processing
        progress.visibility = if (processing) View.VISIBLE else View.GONE
        buttonTranscribe.isEnabled = true
        buttonTranscribe.isClickable = true
        buttonTranscribe.isFocusable = true
        buttonTranscribe.alpha = 1f
        if (processing) {
            cancelRequested = false
            buttonTranscribe.setImageResource(R.drawable.ic_ffmpeg_cancel_red)
            buttonTranscribe.setBackgroundResource(R.drawable.ffmpeg_outline_red_button_bg)
            buttonTranscribe.contentDescription = "Cancelar"
        } else {
            currentCalls.clear()
            currentFfmpegSessionId = null
            buttonTranscribe.setImageResource(R.drawable.ic_whisper_transcribe)
            buttonTranscribe.setBackgroundResource(R.drawable.ffmpeg_outline_green_button_bg)
            buttonTranscribe.contentDescription = "Transcrever no servidor"
            updateTranscribeEnabled()
        }
    }

    private fun updateTranscribeEnabled() {
        if (isProcessing) return
        val enabled = selectedItems.isNotEmpty() && selectedPrepareMode != null
        buttonTranscribe.alpha = if (enabled) 1f else 0.45f
        buttonTranscribe.isClickable = enabled
        buttonTranscribe.isFocusable = enabled
    }

    private fun togglePlayback() {
        val player = currentPlayer() ?: return
        if (player.isPlaying) {
            pausePreview()
            setPlaybackButtonPlaying(false)
            return
        }
        val start = timeline.getStartMs()
        val end = timeline.getEndMs()
        val current = player.currentPosition.toLong()
        val playFrom = if (current < start || current >= end) start else current
        if (current != playFrom) {
            playWhenSeekCompletes = true
            seekPreview(playFrom)
            handler.postDelayed({
                if (playWhenSeekCompletes) {
                    playWhenSeekCompletes = false
                    startPreview()
                }
            }, 120L)
        } else {
            startPreview()
        }
        setPlaybackButtonPlaying(currentPlayer()?.isPlaying == true)
        syncPlaybackButtonSoon()
    }

    private fun startPreview() {
        applyPlaybackSpeed()
        currentPlayer()?.start()
        setPlaybackButtonPlaying(currentPlayer()?.isPlaying == true)
        syncPlaybackButtonSoon()
    }

    private fun pausePreview() {
        previewPlayer?.pause()
        audioPlayer?.pause()
    }

    private fun currentPlayer(): MediaPlayer? {
        return if (videoPreview.visibility == View.VISIBLE) previewPlayer else audioPlayer
    }

    private fun setPlaybackButtonPlaying(isPlaying: Boolean) {
        buttonPlayPause.setImageResource(if (isPlaying) R.drawable.ic_ffmpeg_pause else R.drawable.ic_ffmpeg_play)
        buttonPlayPause.contentDescription = if (isPlaying) "Pausar" else "Reproduzir"
    }

    private fun syncPlaybackButtonSoon() {
        handler.postDelayed({
            setPlaybackButtonPlaying(currentPlayer()?.isPlaying == true)
        }, 180L)
    }

    private fun seekPreview(positionMs: Long) {
        val safePosition = positionMs.coerceIn(0L, durationMs).coerceAtMost(Int.MAX_VALUE.toLong())
        timeline.setCurrent(safePosition)
        audioWaveform.setCurrent(safePosition)
        currentTime.text = formatTime(safePosition)
        val player = currentPlayer() ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                player.seekTo(safePosition, MediaPlayer.SEEK_CLOSEST)
            } else {
                player.seekTo(safePosition.toInt())
            }
        } catch (e: Exception) {
            Log.w(TAG, "seek failed", e)
        }
    }

    private fun changePlaybackSpeed(direction: Int) {
        val currentIndex = speedSteps.indexOfFirst { kotlin.math.abs(it - playbackSpeed) < 0.01f }
        val safeIndex = if (currentIndex >= 0) currentIndex else speedSteps.indexOfFirst { it == 1f }
        playbackSpeed = speedSteps[(safeIndex + direction).coerceIn(0, speedSteps.lastIndex)]
        updateSpeedButton()
        applyPlaybackSpeed()
    }

    private fun applyPlaybackSpeed() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            playbackSpeed = 1f
            updateSpeedButton()
            return
        }
        val player = currentPlayer() ?: return
        try {
            player.playbackParams = player.playbackParams.setSpeed(playbackSpeed)
        } catch (e: Exception) {
            Log.w(TAG, "Could not change playback speed", e)
            playbackSpeed = 1f
            updateSpeedButton()
        }
    }

    private fun updateSpeedButton() {
        val label = when (playbackSpeed) {
            0.25f -> "0,25x"
            0.5f -> "0,5x"
            1f -> "1x"
            2f -> "2x"
            else -> String.format(Locale("pt", "BR"), "%.2fx", playbackSpeed)
        }
        playbackSpeedLabel.text = label
        buttonSpeedDown.alpha = if (playbackSpeed <= speedSteps.first()) 0.35f else 1f
        buttonSpeedUp.alpha = if (playbackSpeed >= speedSteps.last()) 0.35f else 1f
    }

    private fun applyPreviewTransform() {
        if (videoPreview.width == 0 || videoPreview.height == 0 || videoWidth <= 0 || videoHeight <= 0) return
        val frameWidth = videoPreview.width.toFloat()
        val frameHeight = videoPreview.height.toFloat()
        val centerX = frameWidth / 2f
        val centerY = frameHeight / 2f
        val fitScale = minOf(frameWidth / videoWidth.toFloat(), frameHeight / videoHeight.toFloat())
        val fittedWidth = videoWidth * fitScale
        val fittedHeight = videoHeight * fitScale
        val matrix = Matrix()
        matrix.postScale(fittedWidth / frameWidth, fittedHeight / frameHeight, centerX, centerY)
        videoPreview.setTransform(matrix)
        videoPreview.invalidate()
    }

    private fun timeFieldWatcher(update: (Long) -> Unit): TextWatcher {
        return object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (syncingFields) return
                val value = parseTime(s?.toString().orEmpty()) ?: return
                update(value.coerceIn(0L, durationMs))
            }
        }
    }

    private fun updateTimeFields(startMs: Long, endMs: Long) {
        syncingFields = true
        inputFrom.setText(formatTime(startMs))
        inputTo.setText(formatTime(endMs))
        audioWaveform.setRange(startMs, endMs)
        syncingFields = false
    }

    private fun saveTempOutputsToUri(treeUri: Uri) {
        val session = lastSession ?: return
        val root = DocumentFile.fromTreeUri(this, treeUri)
        if (root == null || !root.isDirectory) {
            Toast.makeText(this, "Pasta inválida.", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val sessionDoc = root.createDirectory(session.dir.name) ?: root
            copyFileToDocument(session.txtFile, sessionDoc, "text/plain")
            copyFileToDocument(session.htmlFile, sessionDoc, "text/html")
            copyFileToDocument(session.logFile, sessionDoc, "text/plain")
            copyFileToDocument(session.terminalFile, sessionDoc, "text/plain")
            val transcriptionsDoc = sessionDoc.createDirectory("Transcricoes") ?: sessionDoc
            File(session.dir, "Transcricoes").listFiles()?.forEach { file ->
                copyFileToDocument(file, transcriptionsDoc, "text/plain")
            }
            finalOutputDirUri = sessionDoc.uri
            buttonOutputFolder.visibility = View.VISIBLE
            status.text = "Arquivos salvos em ${sessionDoc.name ?: "pasta selecionada"}"
        } catch (e: Throwable) {
            Log.e(TAG, "Save failed", e)
            Toast.makeText(this, "Não consegui salvar os arquivos.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyFileToDocument(file: File, dir: DocumentFile, mime: String) {
        val existingName = file.name
        val doc = dir.createFile(mime, existingName) ?: throw IllegalStateException("não consegui criar $existingName")
        contentResolver.openOutputStream(doc.uri)?.use { output ->
            file.inputStream().use { input -> input.copyTo(output) }
        } ?: throw IllegalStateException("não consegui escrever $existingName")
    }

    private fun showExportMenu(anchor: View) {
        val session = lastSession ?: return
        PopupMenu(this, anchor).apply {
            menu.add("txt")
            menu.add("html")
            setOnMenuItemClickListener { item ->
                when (item.title.toString()) {
                    "txt" -> shareFile(session.txtFile, "text/plain")
                    "html" -> shareFile(session.htmlFile, "text/html")
                }
                true
            }
            show()
        }
    }

    private fun showTranscriptShareMenu(anchor: View) {
        val session = lastSession
        PopupMenu(this, anchor).apply {
            menu.add("txt")
            menu.add("html")
            setOnMenuItemClickListener { item ->
                when (item.title.toString()) {
                    "txt" -> shareTranscriptAsTextOrFile()
                    "html" -> {
                        if (session != null && session.htmlFile.exists()) {
                            shareFile(session.htmlFile, "text/html")
                        } else {
                            Toast.makeText(this@FasterWhisperServerActivity, "Ainda não há HTML para compartilhar.", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                true
            }
            show()
        }
    }

    private fun openOutputFile(file: File?, mime: String) {
        if (file == null || !file.exists()) return
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "Não encontrei app para abrir o arquivo.", Toast.LENGTH_SHORT).show()
        }
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
            try {
                startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
            } catch (_: Exception) {
                Toast.makeText(this, "Não consegui abrir a pasta.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun shareFile(file: File, mime: String) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Compartilhar"))
    }

    private fun shareOutputText() {
        val text = currentShareableTranscriptText()
        if (text.isBlank()) {
            Toast.makeText(this, "Ainda não há texto para compartilhar.", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, "Compartilhar transcrição"))
    }

    private fun shareTranscriptAsTextOrFile() {
        val session = lastSession
        if (lastTranscriptionResults.size > 1 && session?.txtFile?.exists() == true) {
            shareFile(session.txtFile, "text/plain")
            return
        }
        shareOutputText()
    }

    private fun copyTranscriptToClipboard() {
        val text = liveTranscriptTextView.text?.toString()?.trim().orEmpty()
            .ifBlank { currentShareableTranscriptText() }
        if (text.isBlank()) {
            Toast.makeText(this, "Ainda não há texto para copiar.", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Transcrição", text))
        Toast.makeText(this, "Texto copiado.", Toast.LENGTH_SHORT).show()
    }

    private fun pasteTranscriptFromClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip ?: return
        if (clip.itemCount == 0) return
        val pasted = clip.getItemAt(0).coerceToText(this)?.toString()?.trim().orEmpty()
        if (pasted.isBlank()) return
        if (liveTranscriptTextView.text?.toString()?.isBlank() != false) {
            replaceLiveTranscript(pasted)
            return
        }
        AlertDialog.Builder(this)
            .setMessage("Deseja sobrescrever?")
            .setPositiveButton("Sim") { _, _ -> replaceLiveTranscript(pasted) }
            .setNegativeButton("Não", null)
            .show()
    }

    private fun prepareLiveTranscriptUi() {
        synchronized(assistantCalls) {
            assistantCalls.forEach { it.cancel() }
            assistantCalls.clear()
        }
        assistantRequestGeneration += 1
        assistantNames = emptyList()
        historyTaskState = AssistantTaskState.IDLE
        namesTaskState = AssistantTaskState.IDLE
        statementTaskState = AssistantTaskState.IDLE
        historyElapsedMs = null
        namesElapsedMs = null
        statementElapsedMs = null
        progressPhase = ProgressPhase.TRANSCRIPTION
        transcriptionTaskState = AssistantTaskState.IDLE
        refiningTaskState = AssistantTaskState.IDLE
        buttonPersonSelector.text = ""
        buttonHistory.isEnabled = true
        buttonHistory.alpha = 1f
        liveAiProgress.visibility = View.VISIBLE
        renderLiveProgress()
        livePostActions.visibility = View.GONE
        liveTranscriptTextView.text = ""
        liveTranscriptTextView.setMinLines(10)
        liveTranscriptTextView.visibility = View.VISIBLE
        buttonPasteTranscript.visibility = View.VISIBLE
        outputFileName.visibility = View.GONE
        outputActions.visibility = View.GONE
        terminalText.visibility = View.GONE
    }

    private fun prepareWhiteRecordingUi() {
        synchronized(assistantCalls) {
            assistantCalls.forEach { it.cancel() }
            assistantCalls.clear()
        }
        assistantRequestGeneration += 1
        assistantNames = emptyList()
        buttonPersonSelector.text = ""
        historyTaskState = AssistantTaskState.IDLE
        namesTaskState = AssistantTaskState.IDLE
        statementTaskState = AssistantTaskState.IDLE
        historyElapsedMs = null
        namesElapsedMs = null
        statementElapsedMs = null
        progressPhase = ProgressPhase.WHITE_RECORDING
        transcriptionTaskState = AssistantTaskState.RUNNING
        refiningTaskState = AssistantTaskState.IDLE
        liveTranscriptTextView.text = ""
        liveTranscriptTextView.setMinLines(10)
        liveTranscriptTextView.visibility = View.VISIBLE
        buttonPasteTranscript.visibility = View.VISIBLE
        livePostActions.visibility = View.GONE
        liveAiProgress.visibility = View.VISIBLE
        outputFileName.visibility = View.GONE
        outputActions.visibility = View.GONE
        terminalText.visibility = View.GONE
        renderLiveProgress()
    }

    private fun hideLiveTranscriptUi() {
        liveTranscriptTextView.visibility = View.GONE
        buttonPasteTranscript.visibility = View.GONE
        liveAiProgress.visibility = View.GONE
        livePostActions.visibility = View.GONE
    }

    private fun requestHistory() {
        val transcript = liveTranscriptTextView.text?.toString()?.trim().orEmpty()
        if (transcript.isBlank()) {
            Toast.makeText(this, "Ainda não há transcrição para processar.", Toast.LENGTH_SHORT).show()
            return
        }
        synchronized(assistantCalls) {
            assistantCalls.forEach { it.cancel() }
            assistantCalls.clear()
        }
        val generation = ++assistantRequestGeneration
        val requestStartedAt = SystemClock.elapsedRealtime()
        progressPhase = ProgressPhase.ASSISTANT
        historyTaskState = AssistantTaskState.RUNNING
        namesTaskState = AssistantTaskState.RUNNING
        statementTaskState = AssistantTaskState.IDLE
        historyElapsedMs = null
        namesElapsedMs = null
        statementElapsedMs = null
        assistantNames = emptyList()
        buttonPersonSelector.text = ""
        buttonHistory.isEnabled = false
        buttonHistory.alpha = 0.55f
        liveAiProgress.visibility = View.VISIBLE
        renderLiveProgress()
        val extractionMethod = PartsExtractionSettings.selectedMethod(this)
        val nameDatabase = if (extractionMethod == PartsExtractionSettings.Method.NAME_DATABASE) {
            NameDatabaseStore.load(this)
        } else {
            emptySet()
        }

        val calls = TranscriptAssistantClient.requestHistoryAndNames(
            client = client,
            serverConfig = ModelServerStore.selectedConfig(),
            partsServerConfig = PartsExtractionSettings.selectedConfig(this),
            transcript = transcript,
            historyPrompt = PromptTemplateStore.historyPrompt(),
            partsPrompt = PromptTemplateStore.partsPrompt(),
            extractionMethod = extractionMethod,
            nameDatabase = nameDatabase,
            onHistory = { result ->
                runOnUiThread {
                    if (generation != assistantRequestGeneration) return@runOnUiThread
                    result.fold(
                        onSuccess = { history ->
                            historyElapsedMs = SystemClock.elapsedRealtime() - requestStartedAt
                            historyTaskState = AssistantTaskState.DONE
                            replaceLiveTranscript(history)
                        },
                        onFailure = {
                            historyElapsedMs = SystemClock.elapsedRealtime() - requestStartedAt
                            historyTaskState = AssistantTaskState.ERROR
                            Toast.makeText(
                                this,
                                it.message ?: "Não consegui gerar o histórico.",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    )
                    finishAssistantTaskIfReady()
                }
            },
            onNames = { result, extractionElapsedMs ->
                runOnUiThread {
                    if (generation != assistantRequestGeneration) return@runOnUiThread
                    result.fold(
                        onSuccess = { names ->
                            namesElapsedMs = extractionElapsedMs
                            namesTaskState = AssistantTaskState.DONE
                            assistantNames = names
                            buttonPersonSelector.text = names.firstOrNull().orEmpty()
                        },
                        onFailure = {
                            namesElapsedMs = extractionElapsedMs
                            namesTaskState = AssistantTaskState.ERROR
                            Toast.makeText(
                                this,
                                it.message ?: "Não consegui identificar os envolvidos.",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    )
                    finishAssistantTaskIfReady()
                }
            }
        )
        synchronized(assistantCalls) { assistantCalls.addAll(calls) }
    }

    private fun requestStatement() {
        val material = liveTranscriptTextView.text?.toString()?.trim().orEmpty()
        if (material.isBlank()) {
            Toast.makeText(this, "Ainda não há texto para redigir a oitiva.", Toast.LENGTH_SHORT).show()
            return
        }
        synchronized(assistantCalls) {
            assistantCalls.forEach { it.cancel() }
            assistantCalls.clear()
        }
        val generation = ++assistantRequestGeneration
        val requestStartedAt = SystemClock.elapsedRealtime()
        val selectedName = buttonPersonSelector.text?.toString()?.trim()?.takeIf { it.isNotBlank() }
        progressPhase = ProgressPhase.STATEMENT
        statementTaskState = AssistantTaskState.RUNNING
        statementElapsedMs = null
        buttonStatement.isEnabled = false
        buttonStatement.alpha = 0.55f
        liveAiProgress.visibility = View.VISIBLE
        renderLiveProgress()

        val call = TranscriptAssistantClient.requestStatement(
            client = client,
            serverConfig = ModelServerStore.selectedConfig(),
            material = material,
            statementPrompt = PromptTemplateStore.statementPrompt(selectedName)
        ) { result ->
            runOnUiThread {
                if (generation != assistantRequestGeneration) return@runOnUiThread
                result.fold(
                    onSuccess = { statement ->
                        statementElapsedMs = SystemClock.elapsedRealtime() - requestStartedAt
                        statementTaskState = AssistantTaskState.DONE
                        replaceLiveTranscript(statement)
                    },
                    onFailure = {
                        statementElapsedMs = SystemClock.elapsedRealtime() - requestStartedAt
                        statementTaskState = AssistantTaskState.ERROR
                        Toast.makeText(
                            this,
                            it.message ?: "Não consegui redigir a oitiva.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                )
                renderLiveProgress()
                buttonStatement.isEnabled = true
                buttonStatement.alpha = 1f
                synchronized(assistantCalls) { assistantCalls.clear() }
            }
        }
        synchronized(assistantCalls) { assistantCalls.add(call) }
    }

    private fun replaceLiveTranscript(text: String) {
        val clean = text.trim()
        liveTranscriptTextView.text = clean
        liveTranscriptTextView.setMinLines(0)
        synchronized(liveTranscriptText) {
            liveTranscriptText.clear()
            liveTranscriptText.append(clean)
            liveDraftText = ""
            rebuildLiveTranscriptDisplayLocked()
        }
        lastSession?.let { session ->
            runCatching {
                session.txtFile.writeText(clean + "\n", Charsets.UTF_8)
                session.htmlFile.writeText(buildLiveHtml(clean), Charsets.UTF_8)
                session.terminalFile.writeText(clean + "\n", Charsets.UTF_8)
            }
        }
    }

    private fun finishAssistantTaskIfReady() {
        renderLiveProgress()
        if (historyTaskState != AssistantTaskState.RUNNING && namesTaskState != AssistantTaskState.RUNNING) {
            buttonHistory.isEnabled = true
            buttonHistory.alpha = 1f
            synchronized(assistantCalls) { assistantCalls.clear() }
        }
    }

    private fun resetTranscriptionProgress() {
        progressPhase = ProgressPhase.TRANSCRIPTION
        transcriptionTaskState = AssistantTaskState.IDLE
        refiningTaskState = AssistantTaskState.IDLE
        renderLiveProgress()
    }

    private fun renderLiveProgress() {
        val entries = when (progressPhase) {
            ProgressPhase.WHITE_RECORDING -> listOf(
                Triple("Gravando", transcriptionTaskState, null)
            )
            ProgressPhase.WHITE_TRANSCRIPTION -> listOf(
                Triple("Transcrevendo", transcriptionTaskState, null)
            )
            ProgressPhase.TRANSCRIPTION -> listOf(
                Triple("Transcrevendo...", transcriptionTaskState, null),
                Triple("Refinando", refiningTaskState, null)
            )
            ProgressPhase.ASSISTANT -> listOf(
                Triple("Redigindo histórico", historyTaskState, historyElapsedMs),
                Triple("Identificando partes", namesTaskState, namesElapsedMs)
            )
            ProgressPhase.STATEMENT -> listOf(
                Triple("Redigindo oitiva", statementTaskState, statementElapsedMs)
            )
        }
        val text = entries.joinToString("\n") { (label, state, elapsedMs) ->
            when (state) {
                AssistantTaskState.DONE -> "100% $label${formatRequestElapsed(elapsedMs)}"
                AssistantTaskState.ERROR -> "ERRO $label"
                else -> "0% $label"
            }
        }
        val spannable = SpannableString(text)
        var start = 0
        entries.forEachIndexed { index, (_, state, _) ->
            val end = if (index == entries.lastIndex) text.length else text.indexOf('\n', start)
            val color = when (state) {
                AssistantTaskState.IDLE -> Color.rgb(145, 145, 145)
                AssistantTaskState.RUNNING -> Color.WHITE
                AssistantTaskState.DONE -> Color.rgb(94, 240, 142)
                AssistantTaskState.ERROR -> Color.rgb(255, 92, 92)
            }
            spannable.setSpan(ForegroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            start = end + 1
        }
        liveAiProgress.text = spannable
    }

    private fun formatRequestElapsed(elapsedMs: Long?): String {
        if (elapsedMs == null) return ""
        return String.format(Locale.US, " (%.1fs)", elapsedMs / 1000.0)
    }

    private fun showPersonMenu() {
        if (assistantNames.isEmpty()) return
        PopupMenu(this, buttonPersonSelector).apply {
            assistantNames.forEach { menu.add(it) }
            setOnMenuItemClickListener {
                buttonPersonSelector.text = it.title
                true
            }
            show()
        }
    }

    private fun clearOutputResult() {
        outputItems.clear()
        zipFile = null
        tempOutputFiles.clear()
        lastSession = null
        lastTranscriptionResults = emptyList()
        finalOutputDirUri = null
        outputFileName.visibility = View.GONE
        outputActions.visibility = View.GONE
        buttonOutputFolder.visibility = View.GONE
    }

    private fun writeFailureFiles(sessionDir: File, terminalLines: StringBuilder, logLines: StringBuilder, message: String) {
        try {
            File(sessionDir, "terminal.txt").writeText(snapshotText(terminalLines), Charsets.UTF_8)
            File(sessionDir, "log.txt").writeText(logLines.toString() + "\n$message\n", Charsets.UTF_8)
        } catch (_: Throwable) {
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

    private fun newTerminalSession(header: String): StringBuilder {
        val existing = terminalText.text?.toString()?.trimEnd().orEmpty()
        return StringBuilder().apply {
            if (existing.isNotBlank()) {
                append(existing).append("\n\n")
            }
            append("============================================================\n")
            append(header).append('\n')
            append("============================================================\n")
        }
    }

    private fun markServerFinished(serverFinishedAt: AtomicLong) {
        val now = SystemClock.elapsedRealtime()
        while (true) {
            val previous = serverFinishedAt.get()
            if (now <= previous || serverFinishedAt.compareAndSet(previous, now)) return
        }
    }

    private fun calculateServerElapsedMs(serverStartedAt: AtomicLong, serverFinishedAt: AtomicLong): Long {
        val started = serverStartedAt.get()
        val finished = serverFinishedAt.get()
        return if (started > 0L && finished >= started) finished - started else 0L
    }

    private fun appendTerminalTranscription(builder: StringBuilder, text: String) {
        appendTerminal(builder, "$TRANSCRIPTION_START$text$TRANSCRIPTION_END")
    }

    private fun appendTerminalAudioInfo(builder: StringBuilder, text: String) {
        appendTerminal(builder, "$AUDIO_INFO_START$text$AUDIO_INFO_END")
    }

    private fun updateTerminalText(builder: StringBuilder) {
        val rawText = synchronized(builder) { builder.toString() }
        terminalText.text = renderTerminalText(rawText.ifBlank { "$ faster-whisper-server --aguardando arquivo" })
        terminalText.post {
            val layout = terminalText.layout ?: return@post
            val scrollAmount = layout.getLineTop(terminalText.lineCount) - terminalText.height + terminalText.totalPaddingTop + terminalText.totalPaddingBottom
            terminalText.scrollTo(0, scrollAmount.coerceAtLeast(0))
        }
    }

    private fun updateLiveTerminalText() {
        val text = synchronized(liveTranscriptText) {
            buildString {
                val committed = liveTranscriptText.toString().trim()
                val draft = liveDraftText.trim()
                if (committed.isNotBlank()) append(committed)
                if (draft.isNotBlank()) {
                    if (isNotEmpty()) append('\n')
                    append(draft)
                }
            }.trim()
        }
        liveTranscriptTextView.visibility = View.VISIBLE
        buttonPasteTranscript.visibility = View.VISIBLE
        liveTranscriptTextView.text = text
        liveTranscriptTextView.post {
            val layout = liveTranscriptTextView.layout ?: return@post
            val scrollAmount = layout.getLineTop(liveTranscriptTextView.lineCount) -
                liveTranscriptTextView.height +
                liveTranscriptTextView.totalPaddingTop +
                liveTranscriptTextView.totalPaddingBottom
            liveTranscriptTextView.scrollTo(0, scrollAmount.coerceAtLeast(0))
        }
    }

    private fun currentShareableTranscriptText(): String {
        if (::liveTranscriptTextView.isInitialized && liveTranscriptTextView.visibility == View.VISIBLE) {
            liveTranscriptTextView.text?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        }
        val liveText = synchronized(liveTranscriptText) {
            buildString {
                val committed = liveTranscriptText.toString().trim()
                val draft = liveDraftText.trim()
                if (committed.isNotBlank()) append(committed)
                if (draft.isNotBlank()) {
                    if (isNotEmpty()) append('\n')
                    append(draft)
                }
            }.trim()
        }
        if (liveText.isNotBlank()) return liveText
        return lastSession?.txtFile
            ?.takeIf { it.exists() }
            ?.readText(Charsets.UTF_8)
            ?.trim()
            .orEmpty()
    }

    private fun renderTerminalText(rawText: String): SpannableString {
        val clean = StringBuilder()
        val ranges = mutableListOf<Pair<IntRange, Int>>()
        var index = 0
        while (index < rawText.length) {
            val transcriptionStart = rawText.indexOf(TRANSCRIPTION_START, index)
            val audioInfoStart = rawText.indexOf(AUDIO_INFO_START, index)
            val nextStart = listOf(transcriptionStart, audioInfoStart).filter { it >= 0 }.minOrNull() ?: -1
            if (nextStart < 0) {
                clean.append(rawText.substring(index))
                break
            }
            clean.append(rawText.substring(index, nextStart))
            val textStart = clean.length
            val isAudioInfo = nextStart == audioInfoStart
            val startMarker = if (isAudioInfo) AUDIO_INFO_START else TRANSCRIPTION_START
            val endMarker = if (isAudioInfo) AUDIO_INFO_END else TRANSCRIPTION_END
            val color = if (isAudioInfo) Color.rgb(255, 216, 86) else Color.rgb(88, 255, 150)
            val contentStart = nextStart + startMarker.length
            val end = rawText.indexOf(endMarker, contentStart)
            if (end < 0) {
                clean.append(rawText.substring(contentStart))
                ranges += (textStart until clean.length) to color
                break
            }
            clean.append(rawText.substring(contentStart, end))
            ranges += (textStart until clean.length) to color
            index = end + endMarker.length
        }
        val spannable = SpannableString(clean.toString())
        ranges.forEach { (range, color) ->
            if (!range.isEmpty()) {
                spannable.setSpan(
                    ForegroundColorSpan(color),
                    range.first,
                    range.last + 1,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        return spannable
    }

    private fun snapshotText(builder: StringBuilder): String {
        return synchronized(builder) {
            builder.toString()
                .replace(TRANSCRIPTION_START, "")
                .replace(TRANSCRIPTION_END, "")
                .replace(AUDIO_INFO_START, "")
                .replace(AUDIO_INFO_END, "")
        }
    }

    private fun appendLog(builder: StringBuilder, line: String) {
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        synchronized(builder) { builder.append("[$stamp] ").append(line).append('\n') }
    }

    private fun appendTranscriptionHeader(builder: StringBuilder, fileName: String) {
        synchronized(builder) {
            if (builder.isNotEmpty() && !builder.endsWith("\n")) builder.append('\n')
            builder.append(fileName).append("\n\n")
        }
    }

    private fun appendTranscriptionSeparator(builder: StringBuilder) {
        synchronized(builder) {
            if (!builder.endsWith("\n")) builder.append('\n')
            builder.append("-------------------------------\n")
        }
    }

    private fun buildReport(
        fileCount: Int,
        totalAudioSeconds: Double,
        elapsedMs: Long,
        serverElapsedMs: Long,
        mode: PrepareMode
    ): String {
        val elapsedSeconds = (elapsedMs / 1000.0).coerceAtLeast(0.001)
        val serverSeconds = (serverElapsedMs / 1000.0).coerceAtLeast(0.001)
        val generalEfficiency = totalAudioSeconds / elapsedSeconds
        val serverEfficiency = totalAudioSeconds / serverSeconds
        return listOf(
            "Servidor: $serverBaseUrl",
            "Formato enviado: ${mode.reportLabel}",
            "Arquivos: $fileCount",
            "Total de áudio enviado: ${formatSeconds(totalAudioSeconds)}s",
            "Tempo total: ${formatSeconds(elapsedSeconds)}s",
            "Tempo no servidor: ${formatSeconds(serverSeconds)}s",
            "Eficiência geral: ${String.format(Locale.US, "%.2fx", generalEfficiency)}",
            "Eficiência do servidor: ${String.format(Locale.US, "%.2fx", serverEfficiency)}"
        ).joinToString("\n")
    }

    private fun buildHtml(results: List<TranscriptionResult>): String {
        val rows = results.joinToString("\n") { result ->
            "<tr><td>${escapeHtml(result.fileName)}</td><td>${escapeHtml(result.text).replace("\n", "<br>")}</td></tr>"
        }
        return """
            <!doctype html>
            <html lang="pt-BR">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>Transcrições</title>
              <style>
                body { font-family: sans-serif; margin: 24px; color: #111; }
                table { border-collapse: collapse; width: 100%; }
                th, td { border: 1px solid #bbb; padding: 8px; vertical-align: top; }
                th { background: #eee; text-align: left; }
              </style>
            </head>
            <body>
              <h1>Transcrições</h1>
              <table>
                <thead><tr><th>Arquivo</th><th>Transcrição</th></tr></thead>
                <tbody>
                $rows
                </tbody>
              </table>
            </body>
            </html>
        """.trimIndent()
    }

    private fun buildLiveHtml(text: String): String {
        return """
            <!doctype html>
            <html lang="pt-BR">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>Transcrição ao vivo</title>
              <style>
                body { font-family: sans-serif; margin: 24px; color: #111; background: #f6f6f6; }
                h1 { margin: 0 0 16px; font-size: 22px; }
                .box {
                  border: 1px solid #bbb;
                  background: #fff;
                  padding: 16px;
                  line-height: 1.45;
                  white-space: pre-wrap;
                  overflow-wrap: anywhere;
                }
              </style>
            </head>
            <body>
              <h1>Transcrição ao vivo</h1>
              <div class="box">${escapeHtml(text)}</div>
            </body>
            </html>
        """.trimIndent()
    }

    private fun buildTranscriptionsText(results: List<TranscriptionResult>): String {
        val builder = StringBuilder()
        results.forEach { result ->
            appendTranscriptionHeader(builder, result.fileName)
            builder.append(result.text.trim()).append('\n')
            appendTranscriptionSeparator(builder)
        }
        return builder.toString()
    }

    private fun buildTranscriptDisplayText(results: List<TranscriptionResult>): String {
        return if (results.size == 1) {
            results.firstOrNull()?.text.orEmpty().trim()
        } else {
            buildTranscriptionsText(results).trim()
        }
    }

    private fun escapeHtml(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }

    private fun createSessionDir(): File {
        val root = File(File(Environment.getExternalStorageDirectory(), "SIG"), "Faster-whisper-server").apply { mkdirs() }
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

    private fun copyUriToCache(uri: Uri, displayName: String): File {
        val extension = displayName.substringAfterLast('.', "tmp")
        val inputFile = File(cacheDir, "server_input_${System.currentTimeMillis()}.$extension")
        if (uri.scheme == "file") {
            val source = File(uri.path ?: "")
            if (source.exists()) {
                source.copyTo(inputFile, overwrite = true)
                return inputFile
            }
        }
        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(inputFile).use { output -> input.copyTo(output) }
        } ?: throw IllegalStateException("não consegui ler $displayName")
        return inputFile
    }

    private fun releasePreviewPlayers() {
        releasePreviewPlayer()
        releaseAudioPlayer()
    }

    private fun releasePreviewPlayer() {
        previewPlayer?.release()
        previewPlayer = null
    }

    private fun releaseAudioPlayer() {
        audioPlayer?.release()
        audioPlayer = null
    }

    private fun queryDisplayName(uri: Uri): String? {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index)
            }
        }
        return uri.lastPathSegment
    }

    private fun takeReadPermission(uri: Uri, flags: Int) {
        try {
            contentResolver.takePersistableUriPermission(uri, flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
        }
    }

    private fun takeTreePermission(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: SecurityException) {
        }
    }

    private fun readDuration(uri: Uri): Long {
        return try {
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(this, uri)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 1L
            }
        } catch (_: Exception) {
            1L
        }
    }

    private fun readDurationFromFile(file: File): Long {
        return try {
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(file.absolutePath)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 1L
            }
        } catch (_: Exception) {
            1L
        }
    }

    private fun parseTime(value: String): Long? {
        val parts = value.trim().split(":")
        if (parts.isEmpty() || parts.size > 3) return null
        val seconds = parts.lastOrNull()?.replace(",", ".")?.toDoubleOrNull() ?: return null
        val minutes = parts.getOrNull(parts.size - 2)?.toLongOrNull() ?: 0L
        val hours = parts.getOrNull(parts.size - 3)?.toLongOrNull() ?: 0L
        return ((hours * 3600 + minutes * 60) * 1000 + seconds * 1000).toLong()
    }

    private fun formatTime(milliseconds: Long): String {
        val safeMilliseconds = milliseconds.coerceAtLeast(0L)
        val totalSeconds = safeMilliseconds / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        val millis = safeMilliseconds % 1000
        return String.format(Locale.US, "%02d:%02d:%02d.%03d", hours, minutes, seconds, millis)
    }

    private fun formatSeconds(milliseconds: Long): String {
        return String.format(Locale.US, "%.3f", milliseconds / 1000.0)
    }

    private fun formatSeconds(seconds: Double): String {
        return String.format(Locale.US, "%.1f", seconds)
    }

    private fun formatElapsedCompact(elapsedMs: Long): String {
        val totalSeconds = elapsedMs / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        val milliseconds = elapsedMs % 1000
        return String.format(Locale.US, "%02d:%02d.%03d", minutes, seconds, milliseconds)
    }

    private fun safeBaseName(name: String): String {
        return name.substringBeforeLast('.', name).ifBlank { "transcricao" }
            .replace(Regex("""[\\/:*?"<>|]"""), "_")
    }

    private fun uniqueFile(outputDir: File, outputName: String): File {
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

    private fun isSupportedMedia(mime: String, name: String): Boolean {
        return isVideo(mime, name) || isAudio(mime, name)
    }

    private fun isVideo(mime: String, name: String): Boolean {
        if (mime.startsWith("video/")) return true
        val lower = name.lowercase(Locale.ROOT)
        return VIDEO_EXTENSIONS.any { lower.endsWith(it) }
    }

    private fun isAudio(mime: String, name: String): Boolean {
        if (mime.startsWith("audio/")) return true
        val lower = name.lowercase(Locale.ROOT)
        return AUDIO_EXTENSIONS.any { lower.endsWith(it) }
    }

    private fun guessMime(name: String): String {
        return when {
            isVideo("", name) -> "video/*"
            isAudio("", name) -> "audio/*"
            else -> "application/octet-stream"
        }
    }

    private fun ensureNotCancelled() {
        if (cancelRequested) throw CancellationException()
    }

    private class CancellationException : Exception()

    private class ServerUnavailableException : Exception("O servidor está indisponível")

    private data class MediaItem(
        val uri: Uri,
        val name: String,
        val mime: String,
        val durationMs: Long
    )

    private data class TranscriptionResult(
        val index: Int,
        val fileName: String,
        val text: String
    )

    private data class OutputSession(
        val dir: File,
        val txtFile: File,
        val htmlFile: File,
        val logFile: File,
        val terminalFile: File
    )

    private data class OutputItem(
        val uri: Uri,
        val name: String,
        val mime: String
    )

    private data class UploadFile(
        val file: File,
        val mime: String,
        val label: String
    )

    private data class ServerEntry(
        val ip: String,
        val name: String
    )

    private data class PreparedUpload(
        val index: Int,
        val item: MediaItem,
        val uploadFile: UploadFile,
        val durationMs: Long,
        val originalAudioInfo: String,
        val sentAudioInfo: String
    )

    private data class AudioProbe(
        val codec: String,
        val sampleRate: String,
        val channels: String,
        val bitrate: String,
        val sampleRateHz: Int?,
        val channelCount: Int?,
        val bitrateKbps: Double?,
        val hasVideo: Boolean
    )

    private enum class PrepareMode(
        val label: String,
        val reportLabel: String
    ) {
        COMPACT("Enviar compactado", "ogg opus 16 kHz mono 32k"),
        ORIGINAL("Enviar como está", "arquivo original, sem conversão local"),
        READY("Arquivos prontos", "wav 16 kHz mono PCM s16le")
    }

    private enum class LiveLanguage(
        val serverCode: String,
        val label: String,
        val shortLabel: String
    ) {
        PT("pt", "pt", "pt"),
        EN("en", "en", "en"),
        ES("es", "es", "es"),
        AUTO("auto", "auto", "auto")
    }

    private enum class AudioPermissionAction {
        NONE,
        RECORD_FILE,
        LIVE_TEST
    }

    private enum class AssistantTaskState {
        IDLE,
        RUNNING,
        DONE,
        ERROR
    }

    private enum class ProgressPhase {
        WHITE_RECORDING,
        WHITE_TRANSCRIPTION,
        TRANSCRIPTION,
        ASSISTANT,
        STATEMENT
    }

    companion object {
        private const val REQUEST_PICK_MEDIA = 7201
        private const val REQUEST_PICK_FOLDER = 7202
        private const val REQUEST_CHOOSE_PRE_OUTPUT_DIR = 7203
        private const val REQUEST_CHOOSE_OUTPUT_DIR = 7204
        private const val REQUEST_SAVE_RECORDING_DIR = 7205
        private const val REQUEST_RECORD_AUDIO_PERMISSION = 7206
        private const val DEFAULT_SERVER_IP = "servidor"
        private const val DEFAULT_SERVER_NAME = "Avare"
        private const val SERVER_PORT = 8000
        private const val SERVER_WORKERS = 8
        private const val LIVE_UPLOAD_WORKERS = 1
        private const val DEFAULT_LIVE_DRAFT_INTERVAL_MILLIS = 1000
        private const val MIN_LIVE_DRAFT_INTERVAL_MILLIS = 100
        private const val MAX_LIVE_DRAFT_INTERVAL_MILLIS = 10000
        private const val LIVE_FINAL_CHUNK_MILLIS = 30000
        private const val WHITE_RECORDING_SAMPLE_RATE = 16000
        private const val TAG = "FasterWhisperServer"
        private const val TRANSCRIPTION_START = "\uE000TS\uE000"
        private const val TRANSCRIPTION_END = "\uE000TE\uE000"
        private const val AUDIO_INFO_START = "\uE000AI\uE000"
        private const val AUDIO_INFO_END = "\uE000AE\uE000"
        private val SERVER_LINE_IP = Regex("""(?:https?://)?(\d{1,3}(?:\.\d{1,3}){3})(?::\d{1,5})?(?:/\S*)?""")
        private val VIDEO_EXTENSIONS = setOf(".mp4", ".mkv", ".mov", ".avi", ".webm", ".3gp", ".m4v")
        private val AUDIO_EXTENSIONS = setOf(".wav", ".mp3", ".m4a", ".aac", ".ogg", ".opus", ".flac", ".wma")
    }
}

