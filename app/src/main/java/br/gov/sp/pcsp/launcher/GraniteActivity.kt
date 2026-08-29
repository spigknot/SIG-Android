package br.gov.sp.pcsp.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.method.ScrollingMovementMethod
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToLong

/**
 * STT local com Granite Speech 5.0 TurboCTC e Granite 4.1 NAR (ONNX).
 *
 * UI idêntica à ferramenta Transcrição (layout activity_granite.xml, clone da
 * activity_remote_stt_transcription.xml) + barra de modelo/chip (5.0 Turbo/4.1 NAR,
 * CPU/GPU/NPU). O motor é on-device (ONNX Runtime + QNN) via GraniteEngine/
 * GraniteNarEngine.
 */
class GraniteActivity : AppCompatActivity() {

    // ---- views (IDs idênticos ao layout da Transcrição) ----
    private lateinit var serverScroll: ScrollView
    private var previewFrame: View? = null
    private var videoPreview: TextureView? = null
    private var audioWaveform: FfmpegWaveformView? = null
    private var playbackControls: View? = null
    private var timelineFrame: View? = null
    private var timeline: FfmpegRangeSlider? = null
    private var playbackSpeedLabel: TextView? = null
    private var currentTime: TextView? = null
    private var timeFields: View? = null
    private var inputFrom: EditText? = null
    private var inputTo: EditText? = null
    private var prepareModeButtons: View? = null
    private var vadModeRow: View? = null
    private var buttonVadMode: TextView? = null
    private var buttonVadLevel: TextView? = null
    private var batchOptionsRow: View? = null
    private var checkboxOnlyConvert: CheckBox? = null
    private var checkboxOnlyVad: CheckBox? = null
    private var checkboxSendZip: CheckBox? = null
    private var buttonZipLevel: TextView? = null
    private var videoPrepareWarning: TextView? = null
    private var buttonCompactFiles: TextView? = null
    private var buttonPrepareHelp: TextView? = null
    private var buttonReadyFiles: TextView? = null
    private var buttonOriginalFiles: TextView? = null
    private var selectedFile: TextView? = null
    private var selectedListBox: View? = null
    private var selectedList: TextView? = null
    private var buttonSelectOutputFolder: ImageButton? = null
    private var arrowInputOutput: View? = null
    private var buttonPlayPause: ImageButton? = null
    private var buttonSpeedDown: ImageButton? = null
    private var buttonSpeedUp: ImageButton? = null
    private var buttonTranscribe: ImageButton? = null
    private var progress: ProgressBar? = null
    private lateinit var status: TextView
    private var outputFileName: TextView? = null
    private var outputActions: View? = null
    private var buttonSaveToFolder: ImageButton? = null
    private var buttonOutputExport: ImageButton? = null
    private var buttonOutputFolder: ImageButton? = null
    private var buttonCopyTranscript: ImageButton? = null
    private lateinit var liveTranscriptTextView: EditText
    private var liveAiProgress: TextView? = null
    private var liveTranscriptClipboardActions: View? = null
    private var buttonRecoverTranscript: ImageButton? = null
    private var buttonClearTranscript: TextView? = null
    private var buttonShareLiveTranscript: ImageButton? = null
    private var buttonCopyLiveTranscript: ImageButton? = null
    private var buttonPasteTranscript: View? = null
    private var batchProgressBox: View? = null
    private var batchProgressRows: LinearLayout? = null
    private val batchRowCells = mutableListOf<Triple<TextView, TextView, TextView>>()
    private var batchSnapshotKey = ""
    private var batchSessionFinished = false

    // ---- modelo/chip (Granite) ----
    private lateinit var graniteModelRow: View
    private lateinit var buttonModel: TextView
    private lateinit var buttonBackend: TextView

    // ---- estado ----
    private val selectedItems = mutableListOf<MediaItem>()
    private val tempOutputFiles = mutableListOf<File>()
    private val outputItems = mutableListOf<OutputItem>()
    private val handler = Handler(Looper.getMainLooper())
    private val speedSteps = floatArrayOf(0.25f, 0.5f, 1f, 2f, 4f)

    private var previewPlayer: MediaPlayer? = null
    private var audioPlayer: MediaPlayer? = null
    private var previewSurface: Surface? = null
    private var durationMs = 1L
    private var videoWidth = 0
    private var videoHeight = 0
    private var playbackSpeed = 1f
    private var syncingFields = false
    private var isProcessing = false
    @Volatile private var cancelRequested = false
    private var currentFfmpegSessionId: Long? = null
    private var preSelectedOutputDirUri: Uri? = null
    private var finalOutputDirUri: Uri? = null
    private var lastSession: OutputSession? = null
    private var lastReceivedTranscription: String = ""
    private var timestampPlainTranscript: String = ""
    private var timestampedTranscript: String = ""
    private var zipFile: File? = null
    private var playWhenSeekCompletes = false
    private var selectedPrepareMode: PrepareMode? = null
    private var selectedVadMode: VadMode = VadMode.NONE
    private var selectedVadLevel: Int = 1
    private var selectedZipLevel: Int = 1
    private var isTranscribing = false
    private var selectedBackend = GraniteExecutionBackend.CPU
    private var selectedModel = MODEL_TURBO
    private var tempSessionDir: File? = null
    private val timerHandler = Handler(Looper.getMainLooper())
    private var transcriptionStartedAt = 0L
    private var currentTranscriptionStatus = ""
    private var currentTranscriptionProgress = 0

    private val progressTicker = object : Runnable {
        override fun run() {
            val player = currentPlayer()
            if (playbackControls?.visibility == View.VISIBLE && player?.isPlaying == true) {
                val end = timeline?.getEndMs() ?: return
                val position = player.currentPosition.toLong().coerceIn(0L, durationMs)
                if (position >= end) {
                    pausePreview()
                    timeline?.setCurrent(end)
                    audioWaveform?.setCurrent(end)
                    currentTime?.text = formatTime(end)
                    setPlaybackButtonPlaying(false)
                } else {
                    timeline?.setCurrent(position)
                    audioWaveform?.setCurrent(position)
                    currentTime?.text = formatTime(position)
                }
            }
            handler.postDelayed(this, 80L)
        }
    }

    private val transcriptionTimer = object : Runnable {
        override fun run() {
            if (!isTranscribing || transcriptionStartedAt <= 0L) return
            refreshTranscriptionStatus()
            timerHandler.postDelayed(this, 1000L)
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

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        keepContentInsideSystemBars()
        setContentView(R.layout.activity_granite)

        serverScroll = findViewById(R.id.server_scroll)
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
        vadModeRow = findViewById(R.id.vad_mode_row)
        buttonVadMode = findViewById(R.id.button_vad_mode)
        buttonVadLevel = findViewById(R.id.button_vad_level)
        batchOptionsRow = findViewById(R.id.batch_options_row)
        checkboxOnlyConvert = findViewById(R.id.checkbox_only_convert)
        checkboxOnlyVad = findViewById(R.id.checkbox_only_vad)
        checkboxSendZip = findViewById(R.id.checkbox_send_zip)
        buttonZipLevel = findViewById(R.id.button_zip_level)
        videoPrepareWarning = findViewById(R.id.video_prepare_warning)
        buttonCompactFiles = findViewById(R.id.button_compact_files)
        buttonPrepareHelp = findViewById(R.id.button_prepare_help)
        buttonReadyFiles = findViewById(R.id.button_ready_files)
        buttonOriginalFiles = findViewById(R.id.button_original_files)
        selectedFile = findViewById(R.id.selected_file)
        selectedListBox = findViewById(R.id.selected_list_box)
        selectedList = findViewById(R.id.selected_list)
        batchProgressBox = findViewById(R.id.batch_progress_box)
        batchProgressRows = findViewById(R.id.batch_progress_rows)
        buttonSelectOutputFolder = findViewById(R.id.button_select_output_folder)
        arrowInputOutput = findViewById(R.id.arrow_input_output)
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
        liveTranscriptClipboardActions = findViewById(R.id.live_transcript_clipboard_actions)
        buttonRecoverTranscript = findViewById(R.id.button_recover_transcript)
        buttonClearTranscript = findViewById(R.id.button_clear_transcript)
        buttonShareLiveTranscript = findViewById(R.id.button_share_live_transcript)
        buttonCopyLiveTranscript = findViewById(R.id.button_copy_live_transcript)
        buttonPasteTranscript = findViewById(R.id.button_paste_transcript)

        // modelo/chip (Granite)
        graniteModelRow = findViewById(R.id.granite_model_row)
        buttonModel = findViewById(R.id.button_model)
        buttonBackend = findViewById(R.id.button_backend)
        buttonModel.text = "5.0 Turbo"
        buttonBackend.text = "CPU"

        videoPreview?.surfaceTextureListener = surfaceListener
        liveTranscriptTextView.movementMethod = ScrollingMovementMethod.getInstance()
        liveTranscriptTextView.setOnTouchListener { view, event ->
            view.parent.requestDisallowInterceptTouchEvent(true)
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                view.parent.requestDisallowInterceptTouchEvent(false)
            }
            if (event.actionMasked == MotionEvent.ACTION_UP) view.performClick()
            false
        }

        val exitHandler = installCancelAndExitGuard(
            isTaskRunning = { isTranscribing || isProcessing },
            cancelTask = { cancelRunningTaskForExit() }
        )
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { exitHandler() }
        findViewById<View>(R.id.button_select_media).setOnClickListener { showSourceMenu(it) }
        buttonModel.setOnClickListener { showModelMenu() }
        buttonBackend.setOnClickListener { showBackendMenu() }
        buttonTranscribe?.setOnClickListener {
            if (isTranscribing || isProcessing) cancelTranscription() else startGraniteTranscription()
        }
        buttonVadMode?.setOnClickListener { showVadModeMenu() }
        buttonVadLevel?.setOnClickListener { showVadLevelMenu() }
        buttonCompactFiles?.setOnClickListener { selectPrepareMode(PrepareMode.COMPACT) }
        buttonReadyFiles?.setOnClickListener { selectPrepareMode(PrepareMode.READY) }
        buttonOriginalFiles?.setOnClickListener { selectPrepareMode(PrepareMode.ORIGINAL) }
        buttonPrepareHelp?.setOnClickListener { showPrepareModeHelp() }
        checkboxOnlyConvert?.setOnCheckedChangeListener { _, _ -> updatePrepareModeButtons() }
        checkboxOnlyVad?.setOnCheckedChangeListener { _, _ -> updatePrepareModeButtons() }
        checkboxSendZip?.setOnCheckedChangeListener { _, checked ->
            updateBatchOptionVisibility()
            updateTranscribeEnabled()
        }
        buttonZipLevel?.setOnClickListener { showZipLevelMenu() }
        buttonSelectOutputFolder?.setOnClickListener { openOutputFolderPicker(REQUEST_CHOOSE_PRE_OUTPUT_DIR) }
        buttonPlayPause?.setOnClickListener { togglePlayback() }
        buttonSpeedDown?.setOnClickListener { changePlaybackSpeed(-1) }
        buttonSpeedUp?.setOnClickListener { changePlaybackSpeed(1) }
        buttonSaveToFolder?.setOnClickListener {
            val preUri = preSelectedOutputDirUri
            if (preUri != null) saveTempOutputsToUri(preUri) else openOutputFolderPicker(REQUEST_CHOOSE_OUTPUT_DIR)
        }
        buttonOutputExport?.setOnClickListener { showTranscriptShareMenu(it) }
        buttonOutputFolder?.setOnClickListener { openOutputFile(lastSession?.txtFile, "text/plain") }
        buttonCopyTranscript?.setOnClickListener { copyTranscriptToClipboard() }
        buttonRecoverTranscript?.setOnClickListener { recoverLastTranscription() }
        buttonClearTranscript?.setOnClickListener { clearTextWithConfirmation(liveTranscriptTextView, "Transcrição") }
        buttonShareLiveTranscript?.setOnClickListener { shareEditorText(liveTranscriptTextView, "Transcrição") }
        buttonCopyLiveTranscript?.setOnClickListener { copyTranscriptToClipboard() }
        buttonPasteTranscript?.setOnClickListener { pasteTranscriptFromClipboard() }
        outputFileName?.setOnClickListener { openOutputFile(lastSession?.txtFile, "text/plain") }

        timeline?.let { tl ->
            tl.onRangeChanged = { startMs, endMs, fromUser, _ ->
                updateTimeFields(startMs, endMs)
                if (fromUser) {
                    val current = tl.getCurrentMs().coerceIn(startMs, endMs)
                    tl.setCurrent(current)
                    seekPreview(current)
                }
            }
            tl.onPositionChanged = { positionMs, fromUser ->
                currentTime?.text = formatTime(positionMs)
                audioWaveform?.setCurrent(positionMs)
                if (fromUser) seekPreview(positionMs)
            }
        }

        handler.post(progressTicker)
        updateTranscribeEnabled()
    }

    override fun onDestroy() {
        try {
            releasePreviewPlayers()
        } catch (_: Throwable) {
        }
        try {
            GraniteEngine.release()
        } catch (_: Throwable) {
        }
        try {
            GraniteNarEngine.release()
        } catch (_: Throwable) {
        }
        super.onDestroy()
    }

    @Deprecated("Deprecated Android callback kept for this legacy XML activity.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK) return
        when (requestCode) {
            REQUEST_PICK_MEDIA -> handlePickedMedia(data)
            REQUEST_PICK_FOLDER -> handlePickedFolder(data)
            REQUEST_CHOOSE_PRE_OUTPUT_DIR -> data?.data?.let {
                preSelectedOutputDirUri = it
                try {
                    contentResolver.takePersistableUriPermission(
                        it,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (_: SecurityException) {
                }
                buttonSelectOutputFolder?.setBackgroundResource(R.drawable.ffmpeg_outline_green_button_bg)
            }
            REQUEST_CHOOSE_OUTPUT_DIR -> data?.data?.let { saveTempOutputsToUri(it) }
        }
    }

    // ---- seleção de mídia (idêntico à Transcrição) ----

    private fun showSourceMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add("Selecionar arquivo(s)")
            menu.add("Selecionar pasta")
            setOnMenuItemClickListener { item ->
                when (item.title.toString()) {
                    "Selecionar arquivo(s)" -> openMediaPicker()
                    "Selecionar pasta" -> openFolderPicker()
                }
                true
            }
            show()
        }
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

    private fun handlePickedFolder(data: Intent?) {
        val treeUri = data?.data ?: return
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
        val nextItems = mutableListOf<MediaItem>()
        folder.listFiles()
            .filter { it.isFile && isSupportedMedia(it.type.orEmpty(), it.name.orEmpty()) }
            .sortedBy { it.name.orEmpty().lowercase(Locale.US) }
            .forEach { file ->
                val uri = file.uri
                val name = file.name ?: "midia_${nextItems.size + 1}"
                val mime = file.type ?: guessMime(name)
                nextItems += MediaItem(uri, name, mime, readDuration(uri))
            }
        if (nextItems.isEmpty()) {
            Toast.makeText(this, "A pasta escolhida não tem áudio ou vídeo reconhecido.", Toast.LENGTH_SHORT).show()
            return
        }
        applySelection(nextItems)
    }

    private fun applySelection(items: List<MediaItem>) {
        releasePreviewPlayers()
        selectedItems.clear()
        selectedItems.addAll(items)
        selectedPrepareMode = if (items.isNotEmpty()) PrepareMode.READY else null
        updatePrepareModeButtons()
        clearOutputResult()
        status.text = ""
        buttonSelectOutputFolder?.visibility = if (items.isNotEmpty()) View.VISIBLE else View.GONE
        arrowInputOutput?.visibility = if (items.isNotEmpty()) View.VISIBLE else View.GONE
        buttonSelectOutputFolder?.setBackgroundResource(
            if (preSelectedOutputDirUri != null) R.drawable.ffmpeg_outline_green_button_bg else R.drawable.ffmpeg_outline_button_bg
        )

        if (items.isEmpty()) {
            selectedFile?.visibility = View.GONE
            selectedListBox?.visibility = View.GONE
            showSinglePreview(null)
        } else if (items.size == 1) {
            selectedFile?.text = "1 arquivo selecionado"
            selectedFile?.visibility = View.VISIBLE
            selectedListBox?.visibility = View.GONE
            showSinglePreview(items.first())
        } else {
            selectedFile?.text = "${items.size} arquivos selecionados"
            selectedFile?.visibility = View.VISIBLE
            selectedList?.text = ""
            selectedListBox?.visibility = View.GONE
            showSinglePreview(null)
        }
        updateTranscribeEnabled()
    }

    private fun addMediaItem(uri: Uri, target: MutableList<MediaItem>) {
        val name = queryDisplayName(uri) ?: "midia_${target.size + 1}"
        val mime = contentResolver.getType(uri) ?: guessMime(name)
        if (isSupportedMedia(mime, name)) {
            target += MediaItem(uri, name, mime, readDuration(uri))
        }
    }

    private fun takeReadPermission(uri: Uri, flags: Int) {
        try {
            contentResolver.takePersistableUriPermission(uri, flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
        }
    }

    // ---- preview (waveform, play/pause, timeline, velocidade) ----

    private fun showSinglePreview(item: MediaItem?) {
        val visible = item != null
        playbackControls?.visibility = if (visible) View.VISIBLE else View.GONE
        timelineFrame?.visibility = if (visible) View.VISIBLE else View.GONE
        currentTime?.visibility = if (visible) View.VISIBLE else View.GONE
        timeFields?.visibility = if (visible) View.VISIBLE else View.GONE
        audioWaveform?.visibility = View.GONE
        previewFrame?.visibility = View.GONE
        videoPreview?.visibility = View.GONE
        if (item == null) return

        durationMs = item.durationMs.coerceAtLeast(1L)
        timeline?.isEnabled = true
        timeline?.setRange(durationMs, 0L, durationMs)
        timeline?.setCurrent(0L)
        updateTimeFields(0L, durationMs)
        currentTime?.text = formatTime(0L)
        playbackSpeed = 1f
        updateSpeedButton()

        if (isVideo(item.mime, item.name)) {
            previewFrame?.visibility = View.VISIBLE
            videoPreview?.visibility = View.VISIBLE
            if (videoPreview?.isAvailable == true) {
                videoPreview?.surfaceTexture?.let { st -> previewSurface = Surface(st) }
                prepareVideoPreview(item.uri)
            }
        } else {
            audioWaveform?.visibility = View.VISIBLE
            audioWaveform?.configure(item.name, durationMs)
            audioWaveform?.setRange(0L, durationMs)
            prepareAudioPreview(item.uri)
        }
    }

    private fun prepareVideoPreview(uri: Uri) {
        val surface = previewSurface ?: return
        releasePreviewPlayer()
        previewPlayer = MediaPlayer().apply {
            setDataSource(this@GraniteActivity, uri)
            setSurface(surface)
            setOnPreparedListener { player ->
                this@GraniteActivity.durationMs = player.duration.toLong().coerceAtLeast(durationMs)
                this@GraniteActivity.videoWidth = player.videoWidth
                this@GraniteActivity.videoHeight = player.videoHeight
                timeline?.setRange(durationMs, timeline?.getStartMs() ?: 0L, timeline?.getEndMs()?.coerceAtMost(durationMs) ?: durationMs)
                applyPreviewTransform()
                seekPreview(0L)
            }
            setOnCompletionListener {
                setPlaybackButtonPlaying(false)
                timeline?.setCurrent(timeline?.getEndMs() ?: 0L)
            }
            prepareAsync()
        }
    }

    private fun prepareAudioPreview(uri: Uri) {
        releaseAudioPlayer()
        audioPlayer = MediaPlayer().apply {
            setDataSource(this@GraniteActivity, uri)
            setOnPreparedListener { player ->
                durationMs = player.duration.toLong().coerceAtLeast(durationMs)
                timeline?.setRange(durationMs, timeline?.getStartMs() ?: 0L, timeline?.getEndMs()?.coerceAtMost(durationMs) ?: durationMs)
                audioWaveform?.configure(selectedItems.firstOrNull()?.name ?: "áudio", durationMs)
                audioWaveform?.setRange(timeline?.getStartMs() ?: 0L, timeline?.getEndMs() ?: durationMs)
            }
            setOnCompletionListener {
                setPlaybackButtonPlaying(false)
                timeline?.setCurrent(timeline?.getEndMs() ?: 0L)
            }
            prepareAsync()
        }
    }

    private fun togglePlayback() {
        val player = currentPlayer() ?: return
        if (player.isPlaying) {
            pausePreview()
            setPlaybackButtonPlaying(false)
            return
        }
        val tl = timeline ?: return
        val start = tl.getStartMs()
        val end = tl.getEndMs() ?: return
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

    private fun pausePreview() {
        previewPlayer?.pause()
        audioPlayer?.pause()
    }

    private fun currentPlayer(): MediaPlayer? {
        return if (videoPreview?.visibility == View.VISIBLE) previewPlayer else audioPlayer
    }

    private fun setPlaybackButtonPlaying(isPlaying: Boolean) {
        buttonPlayPause?.setImageResource(if (isPlaying) R.drawable.ic_ffmpeg_pause else R.drawable.ic_ffmpeg_play)
        buttonPlayPause?.contentDescription = if (isPlaying) "Pausar" else "Reproduzir"
    }

    private fun syncPlaybackButtonSoon() {
        handler.postDelayed({
            setPlaybackButtonPlaying(currentPlayer()?.isPlaying == true)
        }, 180L)
    }

    private fun startPreview() {
        applyPlaybackSpeed()
        currentPlayer()?.start()
        setPlaybackButtonPlaying(currentPlayer()?.isPlaying == true)
        syncPlaybackButtonSoon()
    }

    private fun seekPreview(positionMs: Long) {
        val safePosition = positionMs.coerceIn(0L, durationMs).coerceAtMost(Int.MAX_VALUE.toLong())
        timeline?.setCurrent(safePosition)
        audioWaveform?.setCurrent(safePosition)
        currentTime?.text = formatTime(safePosition)
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
        playbackSpeedLabel?.text = label
        buttonSpeedDown?.alpha = if (playbackSpeed <= speedSteps.first()) 0.35f else 1f
        buttonSpeedUp?.alpha = if (playbackSpeed >= speedSteps.last()) 0.35f else 1f
    }

    private fun applyPreviewTransform() {
        if (videoPreview?.width == 0 || videoPreview?.height == 0 || videoWidth <= 0 || videoHeight <= 0) return
        val frameWidth = videoPreview?.width?.toFloat() ?: 0f
        val frameHeight = videoPreview?.height?.toFloat() ?: 0f
        val centerX = frameWidth / 2f
        val centerY = frameHeight / 2f
        val fitScale = minOf(frameWidth / videoWidth.toFloat(), frameHeight / videoHeight.toFloat())
        val fittedWidth = videoWidth * fitScale
        val fittedHeight = videoHeight * fitScale
        val matrix = Matrix()
        matrix.postScale(fittedWidth / frameWidth, fittedHeight / frameHeight, centerX, centerY)
        videoPreview?.setTransform(matrix)
        videoPreview?.invalidate()
    }

    private fun updateTimeFields(startMs: Long, endMs: Long) {
        syncingFields = true
        inputFrom?.setText(formatTime(startMs))
        inputTo?.setText(formatTime(endMs))
        audioWaveform?.setRange(startMs, endMs)
        syncingFields = false
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

    // ---- prepare mode / VAD / zip ----

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
        prepareModeButtons?.visibility = if (selectedItems.isNotEmpty()) View.VISIBLE else View.GONE
        vadModeRow?.visibility = if (selectedItems.isNotEmpty()) View.VISIBLE else View.GONE
        updateBatchOptionVisibility()
        videoPrepareWarning?.visibility = View.GONE

        val selected = selectedPrepareMode
        buttonCompactFiles?.setBackgroundResource(
            if (selected == PrepareMode.COMPACT) R.drawable.ffmpeg_outline_green_button_bg else R.drawable.ffmpeg_outline_button_bg
        )
        buttonReadyFiles?.setBackgroundResource(
            if (selected == PrepareMode.READY) R.drawable.ffmpeg_outline_green_button_bg else R.drawable.ffmpeg_outline_button_bg
        )
        buttonOriginalFiles?.setBackgroundResource(
            if (selected == PrepareMode.ORIGINAL) R.drawable.ffmpeg_outline_green_button_bg else R.drawable.ffmpeg_outline_button_bg
        )
        buttonOriginalFiles?.isEnabled = checkboxOnlyConvert?.isChecked != true
        buttonOriginalFiles?.alpha = if (buttonOriginalFiles?.isEnabled == true) 1f else 0.38f
    }

    private fun updateBatchOptionVisibility() {
        if (batchOptionsRow == null) return
        val hasFiles = selectedItems.isNotEmpty()
        val zipAllowed = selectedItems.size > 1
        batchOptionsRow?.visibility = if (hasFiles) View.VISIBLE else View.GONE
        checkboxSendZip?.visibility = if (zipAllowed) View.VISIBLE else View.GONE
        if (!zipAllowed && checkboxSendZip?.isChecked == true) checkboxSendZip?.isChecked = false
        buttonZipLevel?.visibility = if (zipAllowed && checkboxSendZip?.isChecked == true) View.VISIBLE else View.GONE
        checkboxOnlyVad?.isEnabled = selectedVadMode != VadMode.NONE
        checkboxOnlyVad?.alpha = if (checkboxOnlyVad?.isEnabled == true) 1f else 0.38f
        if (checkboxOnlyVad?.isEnabled != true && checkboxOnlyVad?.isChecked == true) checkboxOnlyVad?.isChecked = false
    }

    private fun showVadModeMenu() {
        PopupMenu(this, buttonVadMode).apply {
            VadMode.entries.forEach { mode ->
                menu.add(mode.label)
            }
            setOnMenuItemClickListener { item ->
                val mode = VadMode.entries.firstOrNull { it.label == item.title.toString() } ?: VadMode.NONE
                selectedVadMode = mode
                buttonVadMode?.text = mode.label
                updateBatchOptionVisibility()
                updateTranscribeEnabled()
                true
            }
            show()
        }
    }

    private fun showVadLevelMenu() {
        PopupMenu(this, buttonVadLevel).apply {
            (1..4).forEach { level ->
                menu.add("Nível: $level")
            }
            setOnMenuItemClickListener { item ->
                val level = item.title.toString().removePrefix("Nível: ").toIntOrNull() ?: 1
                selectedVadLevel = level
                buttonVadLevel?.text = "Nível: $level"
                true
            }
            show()
        }
    }

    private fun showZipLevelMenu() {
        PopupMenu(this, buttonZipLevel).apply {
            (0..9).forEach { level ->
                menu.add("Nível ZIP: $level")
            }
            setOnMenuItemClickListener { item ->
                val level = item.title.toString().removePrefix("Nível ZIP: ").toIntOrNull() ?: 1
                selectedZipLevel = level
                buttonZipLevel?.text = "Nível ZIP: $level"
                true
            }
            show()
        }
    }

    // ---- menus modelo/chip (Granite) ----

    private fun showModelMenu() {
        PopupMenu(this, buttonModel).apply {
            // Mesmo padrão do Whisper: "✓ " quando o modelo já está baixado
            // (packageComplete). Resolver por itemId, NUNCA por item.title —
            // o "✓ " prefixado quebraria o match por label.
            val turboComplete = GraniteEngine.packageComplete(this@GraniteActivity)
            val narComplete = GraniteNarEngine.packageComplete(this@GraniteActivity)
            menu.add(0, 1, 0, if (turboComplete) "✓ Granite 5.0 Turbo (en)" else "Granite 5.0 Turbo (en)")
            menu.add(0, 2, 0, if (narComplete) "✓ Granite 4.1 NAR" else "Granite 4.1 NAR")
            setOnMenuItemClickListener { item ->
                val model = when (item.itemId) {
                    1 -> MODEL_TURBO
                    2 -> MODEL_NAR
                    else -> return@setOnMenuItemClickListener true
                }
                val complete = when (model) {
                    MODEL_NAR -> GraniteNarEngine.packageComplete(this@GraniteActivity)
                    else -> GraniteEngine.packageComplete(this@GraniteActivity)
                }
                if (complete) {
                    selectModel(model)
                } else {
                    confirmModelDownload(model)
                }
                true
            }
            show()
        }
    }

    private fun selectModel(model: String) {
        selectedModel = model
        buttonModel.text = if (model == MODEL_NAR) "4.1 NAR" else "5.0 Turbo"
        status.text = "${modelLabel(model)} pronto."
        updateTranscribeEnabled()
    }

    private fun confirmModelDownload(model: String) {
        val size = when (model) {
            MODEL_NAR -> formatBytes(GraniteNarEngine.packageDownloadBytes(this))
            else -> formatBytes(GraniteEngine.packageDownloadBytes(this))
        }
        AlertDialog.Builder(this)
            .setTitle("Baixar modelo")
            .setMessage(
                "Baixar ${modelLabel(model)} ($size)?\n\n" +
                    "O Granite 4.1 NAR é multilíngue (EN/ES/FR/DE/PT); o 5.0 Turbo é só inglês."
            )
            .setNegativeButton("Não", null)
            .setPositiveButton("Sim") { _, _ ->
                downloadPackage(model, onSuccess = { selectModel(model) })
            }
            .show()
    }

    private fun showBackendMenu() {
        val isQcom = QairtDependencyManager.isQualcommDevice()
        PopupMenu(this, buttonBackend).apply {
            GraniteExecutionBackend.entries.forEach { backend ->
                val item = menu.add(backend.label)
                if (backend.accelerated && !isQcom) {
                    item.isEnabled = false
                    item.title = "${backend.label} ?"
                }
            }
            setOnMenuItemClickListener { item ->
                val clickedLabel = item.title.toString().replace(" ?", "")
                val backend = GraniteExecutionBackend.entries.first { it.label == clickedLabel }
                if (backend.accelerated && !isQcom) {
                    AlertDialog.Builder(this@GraniteActivity)
                        .setTitle("Aceleração Qualcomm indisponível")
                        .setMessage(
                            "GPU (Adreno) e NPU (Hexagon) exigem um processador Qualcomm " +
                                "Snapdragon com suporte ao QAIRT (AI Engine Direct)." +
                                "Este aparelho não tem processador Qualcomm compatível."
                        )
                        .setPositiveButton("OK", null)
                        .show()
                    return@setOnMenuItemClickListener true
                }
                if (backend.accelerated && !QairtDependencyManager.isInstalled(this@GraniteActivity)) {
                    showQairtDownloadDialog(backend)
                    return@setOnMenuItemClickListener true
                }
                selectedBackend = backend
                buttonBackend.text = backend.shortLabel
                true
            }
            show()
        }
    }

    private fun showQairtDownloadDialog(backend: GraniteExecutionBackend) {
        val sizeMb = QairtDependencyManager.downloadSize() / 1_048_576L
        AlertDialog.Builder(this)
            .setTitle("Componentes de aceleração Qualcomm")
            .setMessage(
                "O backend ${backend.label} precisa das bibliotecas do " +
                    "Qualcomm AI Runtime (QAIRT)." +
                    "Download: ~$sizeMb MB (uma única vez)."
            )
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Baixar") { _, _ ->
                downloadQairtPackage(backend)
            }
            .show()
    }

    private fun downloadQairtPackage(backend: GraniteExecutionBackend) {
        val progressView = layoutInflater.inflate(R.layout.dialog_model_download, null)
        val statusText = progressView.findViewById<TextView>(R.id.modelDownloadStatusText)
        val progressBar = progressView.findViewById<ProgressBar>(R.id.modelDownloadProgressBar)
        val dialog = AlertDialog.Builder(this)
            .setTitle("Baixando QAIRT")
            .setView(progressView)
            .setCancelable(false)
            .create()
        dialog.show()
        setProcessing(true)
        Thread {
            val result = QairtDependencyManager.install(this) { progress ->
                runOnUiThread {
                    val pct = if (progress.total > 0L) (progress.downloaded * 100L / progress.total).toInt() else -1
                    if (pct >= 0) {
                        progressBar.progress = pct.coerceIn(0, 100)
                        val dlMb = progress.downloaded / 1_048_576L
                        val totMb = progress.total / 1_048_576L
                        statusText.text = "$pct% ($dlMb MB de $totMb MB)"
                    } else {
                        statusText.text = progress.stage
                    }
                }
            }
            runOnUiThread {
                dialog.dismiss()
                setProcessing(false)
                result.fold(
                    onSuccess = {
                        // Instalação confirmada: só agora marca o chip como selecionado.
                        selectedBackend = backend
                        buttonBackend.text = backend.shortLabel
                        status.text = "${backend.label} pronto."
                    },
                    onFailure = { error ->
                        // O Result<Unit> do install captura falhas internas (rename,
                        // SHA, pacote incompleto). NÃO seleciona o backend e mostra o erro.
                        val message = error.message ?: "Erro desconhecido ao baixar o pacote QAIRT."
                        status.text = "Falha ao instalar QAIRT: $message"
                        AlertDialog.Builder(this)
                            .setTitle("Falha no download")
                            .setMessage(message)
                            .setPositiveButton("OK", null)
                            .show()
                    }
                )
            }
        }.start()
    }

    private fun downloadPackage(model: String, onSuccess: () -> Unit) {
        val progressView = layoutInflater.inflate(R.layout.dialog_model_download, null)
        val statusText = progressView.findViewById<TextView>(R.id.modelDownloadStatusText)
        val progressBar = progressView.findViewById<ProgressBar>(R.id.modelDownloadProgressBar)
        val isNar = model == MODEL_NAR
        val dialog = AlertDialog.Builder(this)
            .setTitle("Baixando modelo ${modelLabel(model)}")
            .setView(progressView)
            .setCancelable(false)
            .create()
        dialog.show()
        setProcessing(true)
        Thread {
            try {
                val totalBytes = if (isNar) GraniteNarEngine.packageDownloadBytes(this) else GraniteEngine.packageDownloadBytes(this)
                val download: (Context, (Int, Long) -> Unit) -> Unit =
                    if (isNar) { c, cb -> GraniteNarEngine.downloadPackage(c, cb) }
                    else { c, cb -> GraniteEngine.downloadPackage(c, cb) }
                download(
                    this,
                    { percent, mb ->
                        runOnUiThread {
                            val totalMb = totalBytes / 1048576L
                            if (percent >= 0) {
                                progressBar.progress = percent.coerceIn(0, 100)
                                statusText.text = "$percent% ($mb MB de $totalMb MB)"
                            } else {
                                statusText.text = "Baixando... $mb MB"
                            }
                        }
                    }
                )
                runOnUiThread {
                    dialog.dismiss()
                    setProcessing(false)
                    status.text = "Modelo pronto."
                    onSuccess()
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Granite download failed", e)
                runOnUiThread {
                    dialog.dismiss()
                    setProcessing(false)
                    status.text = "Erro ao baixar modelo: ${e.message ?: "falha inesperada"}"
                }
            }
        }.start()
    }

    // ---- transcrição (motor on-device) ----

    private fun startGraniteTranscription() {
        val items = selectedItems.toList()
        if (items.isEmpty()) return
        if (!NativeDependencyManager.isInstalled(this)) {
            NativeDependencyPrompt.showIfNeeded(this)
            return
        }
        val modelComplete = when (selectedModel) {
            MODEL_NAR -> GraniteNarEngine.packageComplete(this)
            else -> GraniteEngine.packageComplete(this)
        }
        if (!modelComplete) {
            confirmModelDownload(selectedModel)
            return
        }
        val onlyConvert = checkboxOnlyConvert?.isChecked ?: false
        val onlyVad = checkboxOnlyVad?.isChecked ?: false
        val sendZip = checkboxSendZip?.isChecked == true && items.size > 1
        if (onlyVad && selectedVadMode == VadMode.NONE) {
            status.text = "Escolha um VAD antes de usar Apenas VAD."
            return
        }
        val prepareMode = selectedPrepareMode ?: run {
            status.text = "Escolha uma forma de envio."
            updateTranscribeEnabled()
            return
        }

        val snapshotTimelineStartMs = if (items.size == 1) timeline?.getStartMs() ?: 0L else 0L
        val snapshotTimelineEndMs = if (items.size == 1) timeline?.getEndMs() ?: items.firstOrNull()?.durationMs?.coerceAtLeast(1L) ?: 0L else 0L
        // Total de áudio processado (para o relatório de eficiência, como na tela Transcrição).
        val totalAudioMs: Long = if (items.size == 1 && snapshotTimelineEndMs > snapshotTimelineStartMs) {
            snapshotTimelineEndMs - snapshotTimelineStartMs
        } else {
            items.sumOf { it.durationMs.coerceAtLeast(1L) }
        }
        val snapshotVadMode = selectedVadMode
        val snapshotVadLevel = selectedVadLevel
        val snapshotPrepareMode = prepareMode
        val backend = selectedBackend

        clearOutputResult()
        setProcessing(true)
        isTranscribing = true
        cancelRequested = false
        startTranscriptionTimer()
        val startedAt = SystemClock.elapsedRealtime()
        val terminalLines = StringBuilder()
        val logLines = StringBuilder()
        val results = mutableListOf<TranscriptionResult>()
        val vadStats = VadRunStats()
        val modelLoadMs = AtomicReference(0L)

        Thread {
            var sessionDir: File? = null
            var tempDir: File? = null
            try {
                sessionDir = createSessionDir()
                val transcriptionDir = File(sessionDir, "Transcricoes").apply { mkdirs() }
                tempDir = File(cacheDir, "granite_wavs_${System.currentTimeMillis()}").apply { mkdirs() }
                appendTerminal(terminalLines, "$ granite --model ${modelLabel(selectedModel)} --input ${items.size} arquivo(s)")
                appendTerminal(terminalLines, "output: ${sessionDir.absolutePath}")
                appendTerminal(terminalLines, "backend solicitado: ${backend.reportLabel}")
                appendLog(logLines, "Sessão: ${sessionDir.name}")
                appendLog(logLines, "Modelo: ${modelLabel(selectedModel)}")
                appendLog(logLines, "Backend solicitado: ${backend.reportLabel}")

                val parallelism = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)
                val conversionExecutor = Executors.newFixedThreadPool(parallelism)
                val prepareCompletion = ExecutorCompletionService<PreparedUpload>(conversionExecutor)
                val preparedUploads = mutableListOf<PreparedUpload>()
                try {
                    items.forEachIndexed { index, item ->
                        prepareCompletion.submit(
                            prepareUploadTask(
                                items, snapshotPrepareMode, tempDir, terminalLines, index, item, vadStats,
                                applyVad = !onlyConvert && snapshotVadMode != VadMode.NONE,
                                useTimeline = true,
                                timelineStartMs = snapshotTimelineStartMs,
                                timelineEndMs = snapshotTimelineEndMs,
                                vadMode = snapshotVadMode,
                                vadLevel = snapshotVadLevel
                            )
                        )
                    }
                    repeat(items.size) {
                        ensureNotCancelled()
                        val prepared = prepareCompletion.take().get()
                        ensureNotCancelled()
                        preparedUploads += prepared
                    }
                } finally {
                    conversionExecutor.shutdownNow()
                }

                if (onlyConvert || onlyVad) {
                    finishPreparedOnly(sessionDir, preparedUploads.sortedBy { it.index }, onlyVad, terminalLines, logLines, startedAt)
                    tempDir?.deleteRecursively()
                    runOnUiThread {
                        isTranscribing = false
                        stopTranscriptionTimer()
                        setProcessing(false)
                        updateTranscribeEnabled()
                    }
                    return@Thread
                }

                // Carrega o modelo UMA vez (depois de preparar os arquivos).
                runOnUiThread { setTranscriptionStatus("Carregando modelo (pode levar 1-2 min na primeira vez)...") }
                val modelStartedAt = SystemClock.elapsedRealtime()
                val loaded = if (selectedModel == MODEL_NAR) {
                    GraniteNarEngine.load(context = this, onLog = { line -> appendTerminal(terminalLines, line) })
                } else {
                    GraniteEngine.load(
                        context = this,
                        backend = backend,
                        onLog = { line -> appendTerminal(terminalLines, line) },
                        onFallbackPrompt = { reason ->
                            val latch = CountDownLatch(1)
                            val decision = AtomicReference(false)
                            runOnUiThread {
                                AlertDialog.Builder(this)
                                    .setTitle("Acelerador indisponível")
                                    .setMessage(
                                        "O ${backend.label} falhou ao carregar o modelo:\n\n$reason\n\n" +
                                            "Deseja continuar com CPU? (mais lento, mas funciona em qualquer aparelho)"
                                    )
                                    .setPositiveButton("Sim, usar CPU") { _, _ ->
                                        decision.set(true)
                                        latch.countDown()
                                    }
                                    .setNegativeButton("Não, cancelar") { _, _ ->
                                        decision.set(false)
                                        latch.countDown()
                                    }
                                    .setOnCancelListener {
                                        decision.set(false)
                                        latch.countDown()
                                    }
                                    .show()
                            }
                            latch.await()
                            decision.get()
                        }
                    )
                }
                modelLoadMs.set(SystemClock.elapsedRealtime() - modelStartedAt)
                checkNotCancelled()
                if (!loaded) {
                    val engineError = if (selectedModel == MODEL_NAR) {
                        GraniteNarEngine.lastError().ifBlank { "não consegui carregar o modelo Granite 4.1 NAR" }
                    } else {
                        GraniteEngine.lastError().ifBlank { "não consegui carregar o modelo com ${backend.reportLabel}" }
                    }
                    throw IllegalStateException(engineError)
                }
                val effectiveBackend = if (selectedModel == MODEL_NAR) {
                    GraniteExecutionBackend.CPU
                } else {
                    GraniteEngine.loadedBackend() ?: backend
                }
                appendTerminal(terminalLines, "backend efetivo: ${effectiveBackend.reportLabel}")
                appendLog(logLines, "Backend efetivo: ${effectiveBackend.reportLabel}")

                // Transcreve cada arquivo preparado (on-device).
                preparedUploads.sortedBy { it.index }.forEachIndexed { idx, prepared ->
                    checkNotCancelled()
                    val number = prepared.index
                    val item = prepared.item
                    updateBatchProgressLine(number, "Transcrevendo")
                    appendTerminal(terminalLines, "transcrevendo ${number}/${items.size}: ${item.name}")
                    runOnUiThread {
                        setTranscriptionStatus("Transcrevendo ${number}/${items.size}: ${item.name}")
                    }
                    val text = if (selectedModel == MODEL_NAR) {
                        GraniteNarEngine.transcribeFile(
                            wavFile = prepared.uploadFile.file,
                            onProgress = { percent ->
                                runOnUiThread {
                                    setTranscriptionStatus("Transcrevendo ${number}/${items.size}: ${item.name}... $percent%", percent)
                                }
                            }
                        )
                    } else {
                        GraniteEngine.transcribeFile(
                            wavFile = prepared.uploadFile.file,
                            onProgress = { percent ->
                                runOnUiThread {
                                    setTranscriptionStatus("Transcrevendo ${number}/${items.size}: ${item.name}... $percent%", percent)
                                }
                            }
                        )
                    }
                    checkNotCancelled()
                    if (text.isBlank()) throw IllegalStateException("transcrição vazia em ${item.name}")
                    val individual = uniqueFile(transcriptionDir, "${safeBaseName(item.name)}.txt")
                    individual.writeText(text.trim() + "\n", Charsets.UTF_8)
                    results += TranscriptionResult(number, item.name, text.trim())
                    updateBatchProgressLine(number, "OK")
                }

                releaseModel()
                tempDir?.deleteRecursively()
                val elapsedMs = SystemClock.elapsedRealtime() - startedAt
                val orderedResults = results.sortedBy { it.index }
                val finalText = buildTranscriptDisplayText(orderedResults)
                val txtFile = File(sessionDir, "transcricoes.txt")
                val htmlFile = File(sessionDir, "transcricoes.html")
                val logFile = File(sessionDir, "log.txt")
                val terminalFile = File(sessionDir, "terminal.txt")
                txtFile.writeText(finalText, Charsets.UTF_8)
                htmlFile.writeText(buildHtml(orderedResults), Charsets.UTF_8)
                val report = buildGraniteReport(effectiveBackend, items.size, totalAudioMs, elapsedMs, modelLoadMs.get(), selectedModel)
                appendLog(logLines, report)
                logFile.writeText(logLines.toString(), Charsets.UTF_8)
                terminalFile.writeText(snapshotText(terminalLines), Charsets.UTF_8)

                runOnUiThread {
                    lastSession = OutputSession(sessionDir, txtFile, htmlFile, logFile, terminalFile)
                    tempSessionDir = sessionDir
                    isTranscribing = false
                    cancelRequested = false
                    stopTranscriptionTimer()
                    setProcessing(false)
                    // Exibe o relatório de estatísticas (mesmo padrão da tela Transcrição),
                    // com a linha de conclusão no topo.
                    status.text = "Transcrição concluída com sucesso!\n\n$report"
                    if (orderedResults.size <= 1) {
                        val transcriptDisplay = buildTranscriptDisplayText(orderedResults)
                        storeReceivedTranscription(transcriptDisplay, transcriptDisplay)
                        liveTranscriptTextView.visibility = View.VISIBLE
                    } else {
                        liveTranscriptTextView.visibility = View.GONE
                        refreshBatchProgressUi()
                    }
                    outputFileName?.visibility = View.GONE
                    outputActions?.visibility = View.VISIBLE
                    buttonOutputFolder?.visibility = View.GONE
                    serverScroll.post { serverScroll.fullScroll(View.FOCUS_DOWN) }
                    updateTranscribeEnabled()
                }
            } catch (e: CancellationException) {
                Log.i(TAG, "Granite transcription cancelled", e)
                try { releaseModel() } catch (_: Throwable) {}
                tempDir?.deleteRecursively()
                runOnUiThread {
                    isTranscribing = false
                    cancelRequested = false
                    stopTranscriptionTimer()
                    setProcessing(false)
                    status.text = "Transcrição cancelada pelo usuário."
                    updateTranscribeEnabled()
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Granite transcription failed", e)
                try { releaseModel() } catch (_: Throwable) {}
                tempDir?.deleteRecursively()
                val errorMessage = e.message ?: "falha inesperada"
                val detail = if (selectedModel == MODEL_NAR) {
                    GraniteNarEngine.lastError().ifBlank { errorMessage }
                } else {
                    GraniteEngine.lastError().ifBlank { errorMessage }
                }
                appendLog(logLines, "Erro: $detail")
                appendTerminal(terminalLines, "ERROR: $detail")
                try {
                    sessionDir?.let { dir ->
                        File(dir, "log.txt").writeText(logLines.toString(), Charsets.UTF_8)
                        File(dir, "terminal.txt").writeText(snapshotText(terminalLines), Charsets.UTF_8)
                    }
                } catch (_: Throwable) {}
                runOnUiThread {
                    isTranscribing = false
                    cancelRequested = false
                    stopTranscriptionTimer()
                    setProcessing(false)
                    status.text = "Erro: $errorMessage"
                    updateTranscribeEnabled()
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
        item: MediaItem,
        vadStats: VadRunStats,
        applyVad: Boolean = true,
        useTimeline: Boolean = true,
        timelineStartMs: Long = 0L,
        timelineEndMs: Long = 0L,
        vadMode: VadMode = selectedVadMode,
        vadLevel: Int = selectedVadLevel
    ): Callable<PreparedUpload> {
        return Callable {
            ensureNotCancelled()
            val number = index + 1
            updateBatchProgressLine(number, if (applyVad) "Aplicando VAD" else "Convertendo")
            appendTerminal(terminalLines, "")
            appendTerminal(terminalLines, "prepare input[$number/${items.size}]: ${item.name}")
            runOnUiThread {
                status.text = "Preparando $number/${items.size}: ${item.name}"
            }

            val inputFile = copyUriToCache(item.uri, item.name)
            val originalAudioInfo = describeAudioFile(inputFile)
            val startMs = if (items.size == 1 && useTimeline) timelineStartMs else 0L
            val endMs = if (items.size == 1 && useTimeline) {
                if (timelineEndMs > 0L) timelineEndMs else item.durationMs.coerceAtLeast(1L)
            } else {
                item.durationMs.coerceAtLeast(1L)
            }
            val durationToSend = (endMs - startMs).coerceAtLeast(1L)
            val preparedUploadFile = prepareUploadFile(
                mode = mode,
                inputFile = inputFile,
                tempDir = tempDir,
                index = number,
                item = item,
                startMs = startMs,
                durationMs = durationToSend,
                terminalLines = terminalLines
            )
            val uploadFile = if (applyVad) {
                applySelectedVad(
                    preparedUploadFile = preparedUploadFile,
                    sourceFile = inputFile,
                    tempDir = tempDir,
                    index = number,
                    item = item,
                    startMs = startMs,
                    durationMs = durationToSend,
                    terminalLines = terminalLines,
                    vadStats = vadStats,
                    vadMode = vadMode,
                    vadLevel = vadLevel
                )
            } else {
                preparedUploadFile
            }
            val sentAudioInfo = describeAudioFile(uploadFile.file)
            appendTerminal(terminalLines, "prepare done[$number/${items.size}]: ${item.name}")
            PreparedUpload(number, item, uploadFile, durationToSend, originalAudioInfo, sentAudioInfo)
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

    private fun isAlreadyCompact(inputFile: File, originalName: String, terminalLines: StringBuilder): Boolean {
        val lower = originalName.lowercase(Locale.ROOT)
        if (!lower.endsWith(".ogg")) return false
        appendTerminal(terminalLines, "[${originalName}] analisando metadados para envio compactado")
        val probe = probeAudioFile(inputFile)
        appendTerminal(terminalLines, "[${originalName}] metadados: ${metadataSummary(probe)}")
        return !probe.hasVideo &&
            probe.codec == "opus" &&
            probe.sampleRateHz == 16000 &&
            probe.channelCount == 1 &&
            probe.bitrateKbps?.let { it <= 40.0 } == true
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
        val session = executeFfmpegWithTerminal(arguments.toTypedArray(), terminalLines)
        if (!ReturnCode.isSuccess(session.returnCode) || !outputFile.exists() || outputFile.length() == 0L) {
            val tail = session.allLogsAsString.orEmpty().lines().takeLast(3).joinToString(" ")
            throw IllegalStateException("falha ao gerar áudio .wav. ${tail.take(160)}")
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
        val session = executeFfmpegWithTerminal(arguments.toTypedArray(), terminalLines)
        if (!ReturnCode.isSuccess(session.returnCode) || !outputFile.exists() || outputFile.length() == 0L) {
            val tail = session.allLogsAsString.orEmpty().lines().takeLast(3).joinToString(" ")
            throw IllegalStateException("falha ao gerar áudio .ogg. ${tail.take(160)}")
        }
    }

    private fun applySelectedVad(
        preparedUploadFile: UploadFile,
        sourceFile: File,
        tempDir: File,
        index: Int,
        item: MediaItem,
        startMs: Long,
        durationMs: Long,
        terminalLines: StringBuilder,
        vadStats: VadRunStats,
        vadMode: VadMode = selectedVadMode,
        vadLevel: Int = selectedVadLevel
    ): UploadFile {
        val mode = vadMode
        if (mode == VadMode.NONE) return preparedUploadFile

        ensureNotCancelled()
        val startedAt = SystemClock.elapsedRealtime()
        runOnUiThread { status.text = "Filtrando voz com VAD..." }
        appendTerminal(terminalLines, "[${item.name}] VAD selecionado: ${mode.label}")
        appendTerminal(terminalLines, "[${item.name}] Agressividade VAD: $vadLevel")

        val readyWav = if (preparedUploadFile.mime == "audio/wav" &&
            preparedUploadFile.file.name.lowercase(Locale.ROOT).endsWith(".wav") &&
            isAlreadyReadyWav(preparedUploadFile.file, preparedUploadFile.file.name, terminalLines)
        ) {
            preparedUploadFile.file
        } else {
            File(tempDir, "${index}_${safeBaseName(item.name)}_vad_input.wav").also { wav ->
                convertToReadyWav(sourceFile, wav, item.name, startMs, durationMs, terminalLines)
            }
        }
        val filteredWav = File(tempDir, "${index}_${safeBaseName(item.name)}_vad.wav")
        val modelPath = if (mode.usesSilero) ensureBundledSileroVadModel().absolutePath else ""
        val nativeResult = WhisperNative.filterVad(
            readyWav.absolutePath,
            filteredWav.absolutePath,
            modelPath,
            mode.nativeMode,
            vadLevel
        )
        ensureNotCancelled()
        val fields = nativeResult.split("|")
        if (fields.firstOrNull() != "OK" || fields.size < 5) {
            throw IllegalStateException(nativeResult.removePrefix("ERRO|").ifBlank { "falha no VAD local" })
        }
        val originalSamples = fields[1].toLongOrNull() ?: 0L
        val filteredSamples = fields[2].toLongOrNull() ?: 0L
        val segments = fields[3].toIntOrNull() ?: 0
        val backend = fields.drop(4).joinToString("|")
        val reducedSamples = (originalSamples - filteredSamples).coerceAtLeast(0L)
        val reductionPercent = if (originalSamples > 0L) reducedSamples * 100.0 / originalSamples else 0.0
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        vadStats.record(readyWav.length(), filteredWav.length(), elapsed)
        appendTerminal(
            terminalLines,
            "[${item.name}] VAD concluído: $segments segmentos; reduziu ${String.format(Locale.US, "%.2f", reducedSamples / 16000.0)}s " +
                "(${String.format(Locale.US, "%.1f", reductionPercent)}%); backend=$backend; tempo=${formatElapsedCompact(elapsed)}; " +
                "tamanho: ${readyWav.length()} bytes -> ${filteredWav.length()} bytes"
        )
        return UploadFile(filteredWav, "audio/wav", "WAV 16 kHz mono PCM s16le filtrado por ${mode.label}")
    }

    private fun finishPreparedOnly(
        sessionDir: File,
        preparedUploads: List<PreparedUpload>,
        onlyVad: Boolean,
        terminalLines: StringBuilder,
        logLines: StringBuilder,
        startedAt: Long
    ) {
        val outputDir = File(sessionDir, if (onlyVad) "VAD" else "Convertidos").apply { mkdirs() }
        preparedUploads.forEachIndexed { index, prepared ->
            ensureNotCancelled()
            val suffix = prepared.uploadFile.file.extension.takeIf { it.isNotBlank() }?.let { ".$it" } ?: ".wav"
            val marker = if (onlyVad) "_vad" else "_convertido"
            val target = uniqueFile(outputDir, "${safeBaseName(prepared.item.name)}$marker$suffix")
            prepared.uploadFile.file.copyTo(target, overwrite = true)
            appendTerminal(terminalLines, "salvo ${index + 1}/${preparedUploads.size}: ${target.name}")
        }
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        val summary = "${if (onlyVad) "VAD" else "Conversão"} concluído: ${preparedUploads.size} arquivo(s) em ${formatElapsedCompact(elapsed)}"
        appendTerminal(terminalLines, summary)
        appendLog(logLines, summary)
        val txtFile = File(sessionDir, "resultado.txt").apply { writeText(summary + "\n", Charsets.UTF_8) }
        val htmlFile = File(sessionDir, "resultado.html").apply {
            writeText("<meta charset=\"utf-8\"><p>${escapeHtml(summary)}</p>", Charsets.UTF_8)
        }
        val logFile = File(sessionDir, "log.txt").apply { writeText(logLines.toString(), Charsets.UTF_8) }
        val terminalFile = File(sessionDir, "terminal.txt").apply { writeText(snapshotText(terminalLines), Charsets.UTF_8) }
        runOnUiThread {
            lastSession = OutputSession(sessionDir, txtFile, htmlFile, logFile, terminalFile)
            status.text = summary
            outputActions?.visibility = View.VISIBLE
            buttonOutputFolder?.visibility = View.GONE
            setProcessing(false)
        }
    }

    // ---- share / save / clipboard ----

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
            listOf("Convertidos", "VAD").forEach { folderName ->
                val localDir = File(session.dir, folderName)
                if (localDir.isDirectory) {
                    val outputDoc = sessionDoc.createDirectory(folderName) ?: sessionDoc
                    localDir.listFiles()?.forEach { file ->
                        copyFileToDocument(file, outputDoc, contentResolver.getType(Uri.fromFile(file)) ?: "application/octet-stream")
                    }
                }
            }
            finalOutputDirUri = sessionDoc.uri
            buttonOutputFolder?.visibility = View.VISIBLE
            status.text = "Arquivos salvos em ${sessionDoc.name ?: "pasta selecionada"}"
        } catch (e: Throwable) {
            Log.e(TAG, "Save failed", e)
            Toast.makeText(this, "Não consegui salvar os arquivos.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyFileToDocument(file: File, dir: DocumentFile, mime: String) {
        copyFileToDocumentAs(file, dir, file.name, mime)
    }

    private fun copyFileToDocumentAs(file: File, dir: DocumentFile, targetName: String, mime: String) {
        val doc = dir.createFile(mime, targetName) ?: throw IllegalStateException("não consegui criar $targetName")
        contentResolver.openOutputStream(doc.uri)?.use { output ->
            file.inputStream().use { input -> input.copyTo(output) }
        } ?: throw IllegalStateException("não consegui escrever $targetName")
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
                            Toast.makeText(this@GraniteActivity, "Ainda não há HTML para compartilhar.", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                true
            }
            show()
        }
    }

    private fun shareTranscriptAsTextOrFile() {
        val session = lastSession
        if (session?.txtFile?.exists() == true) {
            shareFile(session.txtFile, "text/plain")
            return
        }
        shareOutputText()
    }

    private fun shareOutputText() {
        val text = currentShareableTranscriptText()
        if (text.isBlank()) {
            Toast.makeText(this, "Ainda não há texto para compartilhar.", Toast.LENGTH_SHORT).show()
            return
        }
        shareTextAsFile(text, "transcricao")
    }

    private fun shareFile(file: File, mime: String) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Compartilhar"))
    }

    private fun openOutputFile(file: File?, mime: String?) {
        if (file == null || !file.exists()) return
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime ?: contentResolver.getType(uri) ?: "text/plain")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "Não encontrei app para abrir o arquivo.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyTranscriptToClipboard() {
        val text = liveTranscriptTextView.text?.toString()?.trim().orEmpty()
            .ifBlank { currentShareableTranscriptText() }
        copyTextToClipboard(text, "Transcrição")
    }

    private fun copyTextToClipboard(text: String, label: String) {
        if (text.isBlank()) {
            Toast.makeText(this, "Ainda não há texto para copiar.", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, "Texto copiado.", Toast.LENGTH_SHORT).show()
    }

    private fun shareEditorText(target: EditText, label: String) {
        val text = target.text?.toString()?.trim().orEmpty()
        if (text.isBlank()) {
            Toast.makeText(this, "Ainda não há texto para compartilhar.", Toast.LENGTH_SHORT).show()
            return
        }
        shareTextAsFile(text, label)
    }

    private fun shareTextAsFile(text: String, label: String) {
        val safeLabel = label.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .ifBlank { "texto" }
        val file = File(cacheDir, "sig_${safeLabel}_${System.currentTimeMillis()}.txt")
        try {
            file.writeText(text.trimEnd() + "\n", Charsets.UTF_8)
        } catch (e: Throwable) {
            Log.e(TAG, "Could not prepare text file for sharing", e)
            Toast.makeText(this, "Não consegui preparar o arquivo de texto.", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, file.name)
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Compartilhar transcrição Granite"))
    }

    private fun clearTextWithConfirmation(target: EditText, label: String) {
        val clear = {
            target.setText("")
            if (target === liveTranscriptTextView) {
                timestampPlainTranscript = ""
                timestampedTranscript = ""
            }
        }
        if (target.text?.toString()?.trim().isNullOrBlank()) {
            clear()
            return
        }
        AlertDialog.Builder(this)
            .setMessage("Deseja sobrescrever?")
            .setPositiveButton("Sim") { _, _ -> clear() }
            .setNegativeButton("Não", null)
            .show()
    }

    private fun storeReceivedTranscription(text: String, timestampedText: String = "") {
        val clean = text.trim()
        lastReceivedTranscription = if (timestampedText.isNotBlank()) timestampedText.trim() else clean
        timestampPlainTranscript = clean
        timestampedTranscript = timestampedText.trim()
        // Caixa e botões de clipboard só aparecem com UM arquivo (como na Transcrição).
        val singleFile = selectedItems.size <= 1
        liveTranscriptTextView.setText(clean)
        // Altura FIXA: nunca alternar minLines (0/5) — isso redimensiona a caixa.
        liveTranscriptTextView.setMinLines(5)
        liveTranscriptTextView.visibility = if (singleFile) View.VISIBLE else View.GONE
        liveTranscriptClipboardActions?.visibility = if (singleFile) View.VISIBLE else View.GONE
    }

    private fun currentShareableTranscriptText(): String {
        if (liveTranscriptTextView.visibility == View.VISIBLE) {
            liveTranscriptTextView.text?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return lastSession?.txtFile
            ?.takeIf { it.exists() }
            ?.readText(Charsets.UTF_8)
            ?.trim()
            .orEmpty()
    }

    private fun recoverLastTranscription() {
        if (lastReceivedTranscription.isBlank()) return
        val restore = { storeReceivedTranscription(lastReceivedTranscription) }
        if (liveTranscriptTextView.text?.toString()?.trim().isNullOrBlank()) {
            restore()
            return
        }
        AlertDialog.Builder(this)
            .setMessage("Deseja sobrescrever?")
            .setPositiveButton("Sim") { _, _ -> restore() }
            .setNegativeButton("Não", null)
            .show()
    }

    private fun pasteTranscriptFromClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip ?: return
        if (clip.itemCount == 0) return
        val pasted = clip.getItemAt(0).coerceToText(this)?.toString()?.trim().orEmpty()
        if (pasted.isBlank()) return
        val apply = {
            storeReceivedTranscription(pasted)
        }
        if (liveTranscriptTextView.text?.toString()?.isBlank() != false) {
            apply()
        } else {
            AlertDialog.Builder(this)
                .setMessage("Deseja sobrescrever?")
                .setPositiveButton("Sim") { _, _ -> apply() }
                .setNegativeButton("Não", null)
                .show()
        }
    }

    // ---- batch progress (tabela 3 colunas) ----

    private fun refreshBatchProgressUi() {
        val key = selectedItems.joinToString("|") { it.name }
        if (key != batchSnapshotKey) {
            batchSnapshotKey = key
            batchSessionFinished = false
        }
        if (selectedItems.size < 2 || batchSessionFinished) {
            batchProgressBox?.visibility = View.GONE
            if (selectedItems.size <= 1) liveTranscriptTextView.visibility = View.VISIBLE
            if (selectedItems.size <= 1) liveTranscriptClipboardActions?.visibility = View.VISIBLE
            batchRowCells.clear()
            batchProgressRows?.removeAllViews()
            return
        }
        batchProgressBox?.visibility = View.VISIBLE
        liveTranscriptTextView.visibility = View.GONE
        // Múltiplos arquivos: esconde também os botões de clipboard (só valem p/ 1 arquivo).
        liveTranscriptClipboardActions?.visibility = View.GONE
        if (batchRowCells.size != selectedItems.size) {
            batchRowCells.clear()
            batchProgressRows?.removeAllViews()
            val measurePaint = Paint().apply {
                textSize = 12f * resources.displayMetrics.scaledDensity
                typeface = Typeface.MONOSPACE
            }
            val cellPadding = dp(4)
            val cellPaddingEnd = dp(6)
            val sizeCellWidth = (measurePaint.measureText("9999.9 mb") * 1.2f).toInt() + cellPadding + cellPaddingEnd
            val stateCellWidth = (measurePaint.measureText("Aplicando VAD") * 1.2f).toInt() + cellPadding + cellPaddingEnd
            selectedItems.forEach { item ->
                val nameView = TextView(this).apply {
                    text = item.name
                    textSize = 12f
                    typeface = Typeface.MONOSPACE
                    setTextColor(Color.WHITE)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.MIDDLE
                    setPadding(cellPadding, dp(5), cellPadding, dp(5))
                }
                val sizeView = TextView(this).apply {
                    text = resolveMediaSize(item)
                    textSize = 12f
                    typeface = Typeface.MONOSPACE
                    setTextColor(Color.WHITE)
                    gravity = Gravity.END
                    maxLines = 1
                    setBackgroundResource(R.drawable.batch_cell_left_border)
                    setPadding(cellPadding, dp(5), cellPaddingEnd, dp(5))
                }
                val stateView = TextView(this).apply {
                    text = "Aguardando"
                    textSize = 12f
                    typeface = Typeface.MONOSPACE
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    maxLines = 1
                    setBackgroundResource(R.drawable.batch_cell_left_border)
                    setPadding(cellPadding, dp(5), cellPadding, dp(5))
                }
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(nameView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    addView(sizeView, LinearLayout.LayoutParams(sizeCellWidth, LinearLayout.LayoutParams.WRAP_CONTENT))
                    addView(stateView, LinearLayout.LayoutParams(stateCellWidth, LinearLayout.LayoutParams.WRAP_CONTENT))
                }
                batchProgressRows?.addView(row)
                batchRowCells += Triple(nameView, sizeView, stateView)
            }
        }
    }

    private fun updateBatchProgressLine(number: Int, state: String) {
        val index = number - 1
        runOnUiThread {
            val cells = batchRowCells.getOrNull(index) ?: return@runOnUiThread
            cells.third.text = state
            if (state == "OK") {
                val green = Color.rgb(94, 240, 142)
                cells.first.setTextColor(green)
                cells.second.setTextColor(green)
                cells.third.setTextColor(green)
            }
        }
    }

    private fun resolveMediaSize(item: MediaItem): String {
        val size = try {
            val file = item.uri.path?.let { File(it) }
            if (file != null && file.exists()) {
                file.length()
            } else {
                contentResolver.query(
                    item.uri, arrayOf(OpenableColumns.SIZE), null, null, null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getLong(0) else -1L
                } ?: -1L
            }
        } catch (e: Throwable) {
            -1L
        }
        return if (size >= 0L) formatMediaSize(size) else "?"
    }

    private fun clearOutputResult() {
        outputItems.clear()
        zipFile = null
        tempOutputFiles.clear()
        lastSession = null
        finalOutputDirUri = null
        outputFileName?.visibility = View.GONE
        outputActions?.visibility = View.GONE
        buttonOutputFolder?.visibility = View.GONE
    }

    // ---- estado / progresso / cancelamento ----

    private fun setProcessing(processing: Boolean) {
        isProcessing = processing
        progress?.visibility = if (processing) View.VISIBLE else View.GONE
        if (processing) {
            cancelRequested = false
            buttonTranscribe?.setImageResource(R.drawable.ic_ffmpeg_cancel_red)
            buttonTranscribe?.setBackgroundResource(R.drawable.ffmpeg_outline_red_button_bg)
            buttonTranscribe?.contentDescription = "Cancelar"
        } else {
            currentFfmpegSessionId = null
            buttonTranscribe?.setImageResource(R.drawable.ic_whisper_transcribe)
            buttonTranscribe?.setBackgroundResource(R.drawable.ffmpeg_outline_green_button_bg)
            buttonTranscribe?.contentDescription = "Transcrever"
            updateTranscribeEnabled()
        }
        val notProcessing = !processing
        timeline?.isEnabled = notProcessing
        buttonVadMode?.isEnabled = notProcessing
        buttonVadLevel?.isEnabled = notProcessing
        checkboxOnlyConvert?.isEnabled = notProcessing
        checkboxOnlyVad?.isEnabled = notProcessing
        checkboxSendZip?.isEnabled = notProcessing
        buttonZipLevel?.isEnabled = notProcessing
        buttonCompactFiles?.isEnabled = notProcessing
        buttonReadyFiles?.isEnabled = notProcessing
        buttonOriginalFiles?.isEnabled = notProcessing
        buttonSelectOutputFolder?.isEnabled = notProcessing
        findViewById<View>(R.id.button_select_media)?.isEnabled = notProcessing
        buttonPlayPause?.isEnabled = notProcessing
        buttonSpeedDown?.isEnabled = notProcessing
        buttonSpeedUp?.isEnabled = notProcessing
        inputFrom?.isEnabled = notProcessing
        inputTo?.isEnabled = notProcessing
    }

    private fun updateTranscribeEnabled() {
        if (isProcessing) return
        val enabled = selectedItems.isNotEmpty() &&
            selectedItems.all { isSupportedMedia(it.mime, it.name) } &&
            selectedPrepareMode != null &&
            (checkboxOnlyVad?.isChecked != true || selectedVadMode != VadMode.NONE)
        buttonTranscribe?.visibility = if (selectedItems.isNotEmpty() && selectedItems.all { isSupportedMedia(it.mime, it.name) }) {
            View.VISIBLE
        } else {
            View.GONE
        }
        buttonTranscribe?.alpha = if (enabled) 1f else 0.45f
        buttonTranscribe?.isClickable = enabled
        buttonTranscribe?.isFocusable = enabled
        refreshBatchProgressUi()
    }

    private fun startTranscriptionTimer() {
        transcriptionStartedAt = SystemClock.elapsedRealtime()
        currentTranscriptionProgress = 0
        currentTranscriptionStatus = "Preparando..."
        timerHandler.removeCallbacks(transcriptionTimer)
        timerHandler.post(transcriptionTimer)
    }

    private fun stopTranscriptionTimer() {
        timerHandler.removeCallbacks(transcriptionTimer)
        transcriptionStartedAt = 0L
    }

    private fun refreshTranscriptionStatus() {
        val elapsed = SystemClock.elapsedRealtime() - transcriptionStartedAt
        val text = "$currentTranscriptionStatus (${formatElapsedCompact(elapsed)})"
        status.text = text
    }

    private fun setTranscriptionStatus(text: String, progressValue: Int? = null) {
        currentTranscriptionStatus = text
        status.text = text
        if (progressValue != null) {
            currentTranscriptionProgress = progressValue
            progress?.visibility = View.VISIBLE
            progress?.progress = progressValue
        }
    }

    private fun cancelTranscription() {
        cancelRequested = true
        status.text = "Cancelando..."
    }

    private fun cancelRunningTaskForExit() {
        cancelRequested = true
    }

    private fun checkNotCancelled() {
        if (cancelRequested) throw CancellationException()
    }

    private fun ensureNotCancelled() {
        if (cancelRequested) throw CancellationException()
    }

    private fun releaseModel() {
        if (selectedModel == MODEL_NAR) GraniteNarEngine.release() else GraniteEngine.release()
    }

    // ---- helpers (portados da Transcrição) ----

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

    private fun formatElapsedCompact(elapsedMs: Long): String {
        val totalSeconds = elapsedMs / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        val milliseconds = elapsedMs % 1000
        return String.format(Locale.US, "%02d:%02d.%03d", minutes, seconds, milliseconds)
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

    private fun isSupportedMedia(mime: String, name: String): Boolean {
        return isVideo(mime, name) || isAudio(mime, name)
    }

    private fun guessMime(name: String): String {
        return when {
            isVideo("", name) -> "video/*"
            isAudio("", name) -> "audio/*"
            else -> "application/octet-stream"
        }
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

    private fun queryDisplayName(uri: Uri): String? {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index)
            }
        }
        return uri.lastPathSegment
    }

    private fun readDuration(uri: Uri): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(this, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 1L
        } catch (_: Exception) {
            1L
        } finally {
            retriever.release()
        }
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

    private fun describeAudioFile(file: File): String {
        val info = probeAudioFile(file)
        return listOf(
            ".${file.extension.lowercase(Locale.ROOT).ifBlank { "sem extensão" }}",
            info.codec.ifBlank { "codec ?" },
            info.sampleRate.ifBlank { "hz ?" },
            info.channels.ifBlank { "canal ?" },
            info.bitrate.ifBlank { "bitrate ?" }
        ).joinToString(", ")
    }

    private fun probeDurationMs(file: File): Long? {
        return try {
            val session = FFmpegKit.executeWithArguments(
                arrayOf("-hide_banner", "-i", file.absolutePath)
            )
            parseDurationSeconds(session.allLogsAsString.orEmpty())
                ?.takeIf { it > 0.0 }
                ?.let { (it * 1000.0).toLong() }
        } catch (_: Throwable) {
            null
        }
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

    private fun formatKbps(value: Double): String {
        return if (value < 10.0) {
            String.format(Locale.US, "%.1fk", value)
        } else {
            "${value.toInt()}k"
        }
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
                }
            },
            { statistics ->
                appendTerminal(terminalLines, "ffmpeg stats: time=${statistics.time} size=${statistics.size} speed=${statistics.speed}")
            }
        )
        currentFfmpegSessionId = session.sessionId
        latch.await()
        currentFfmpegSessionId = null
        ensureNotCancelled()
        return sessionRef.get() ?: session
    }

    private fun ensureBundledSileroVadModel(): File {
        return NativeDependencyManager.sileroModelFile(this).also {
            check(it.isFile && it.length() > 100_000L) { "Modelo Silero não instalado." }
        }
    }

    private fun buildTranscriptDisplayText(results: List<TranscriptionResult>): String {
        return if (results.size == 1) {
            results.firstOrNull()?.text.orEmpty().trim()
        } else {
            buildTranscriptionsText(results).trim()
        }
    }

    private fun buildTranscriptionsText(results: List<TranscriptionResult>): String {
        val builder = StringBuilder()
        results.forEach { result ->
            builder.append(result.text.trim()).append('\n')
            builder.append('\n')
        }
        return builder.toString()
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

    private fun escapeHtml(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }

    private fun buildGraniteReport(backend: GraniteExecutionBackend, fileCount: Int, totalAudioMs: Long, elapsedMs: Long, modelLoadMs: Long, model: String): String {
        val elapsedSeconds = (elapsedMs / 1000.0).coerceAtLeast(0.001)
        val audioSeconds = (totalAudioMs / 1000.0).coerceAtLeast(0.001)
        val transcribeMs = (elapsedMs - modelLoadMs).coerceAtLeast(1L)
        // Velocidade relativa calculada sobre o TEMPO TOTAL (carga + inferência),
        // como pedido pelo usuário.
        val efficiency = audioSeconds / elapsedSeconds
        val modelLabel = modelLabel(model)
        return listOf(
            "Modelo: $modelLabel",
            "Backend: ${backend.reportLabel}",
            "Arquivos: $fileCount",
            "Total de áudio: ${formatSeconds(totalAudioMs)}s",
            "Tempo de carga do modelo: ${formatElapsedCompact(modelLoadMs)}",
            "Tempo de inferência: ${formatSeconds(transcribeMs)}s",
            "Tempo total: ${String.format(Locale.US, "%.1f", elapsedSeconds)}s",
            "Velocidade (x tempo real): ${String.format(Locale.US, "%.2fx", efficiency)}"
        ).joinToString("\n")
    }

    private fun createSessionDir(): File {
        val publicRoot = File(
            File(Environment.getExternalStorageDirectory(), SIG_OUTPUT_FOLDER),
            GRANITE_OUTPUT_FOLDER
        )
        val appSpecificRoot = File(
            getExternalFilesDir(null) ?: filesDir,
            GRANITE_OUTPUT_FOLDER
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
        val root = candidates.firstOrNull { SttOutputStorage.ensureDirectory(it) }
            ?: throw IllegalStateException("não consegui preparar o armazenamento da sessão")
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

    private fun hasPublicStorageAccess(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
            Environment.isExternalStorageManager()
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

    private fun safeBaseName(name: String): String {
        return name.substringBeforeLast('.', name).ifBlank { "transcricao" }
            .replace(Regex("""[\\/:*?"<>|]"""), "_")
    }

    private fun formatMediaSize(bytes: Long): String {
        val units = arrayOf("b", "kb", "mb", "gb")
        var value = bytes.coerceAtLeast(0L).toDouble()
        var unit = 0
        while (value >= 1024.0 && unit < units.lastIndex) {
            value /= 1024.0
            unit++
        }
        return String.format(Locale.US, "%.1f %s", value, units[unit])
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
        return String.format(Locale.US, "%.2f MB", kb / 1024.0)
    }

    private fun humanFileSize(bytes: Long): String {
        return formatBytes(bytes)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt().coerceAtLeast(1)

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

    private fun appendLog(builder: StringBuilder, line: String) {
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        synchronized(builder) { builder.append("[$stamp] ").append(line).append('\n') }
    }

    private fun snapshotText(builder: StringBuilder): String {
        return synchronized(builder) { builder.toString() }
    }

    private fun modelLabel(model: String): String = when (model) {
        MODEL_NAR -> MODEL_NAR_LABEL
        else -> MODEL_TURBO_LABEL
    }

    companion object {
        private const val TAG = "GraniteActivity"
        private const val REQUEST_PICK_MEDIA = 1001
        private const val REQUEST_PICK_FOLDER = 1002
        private const val REQUEST_CHOOSE_PRE_OUTPUT_DIR = 1003
        private const val REQUEST_CHOOSE_OUTPUT_DIR = 1004
        private const val MODEL_TURBO = "turbo"
        private const val MODEL_NAR = "nar"
        private const val MODEL_TURBO_LABEL = "Granite 5.0 Turbo"
        private const val MODEL_NAR_LABEL = "Granite 4.1 NAR"
        private const val SIG_OUTPUT_FOLDER = "SIG"
        private const val GRANITE_OUTPUT_FOLDER = "Granite"
        private val VIDEO_EXTENSIONS = setOf(".mp4", ".mkv", ".mov", ".avi", ".webm", ".3gp", ".m4v")
        private val AUDIO_EXTENSIONS = setOf(".wav", ".mp3", ".m4a", ".aac", ".ogg", ".opus", ".flac", ".wma")
    }
}

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

private class VadRunStats {
    private var files = 0
    private var beforeBytes = 0L
    private var afterBytes = 0L
    private var elapsedMs = 0L

    @Synchronized
    fun record(before: Long, after: Long, elapsed: Long) {
        files++
        beforeBytes += before
        afterBytes += after
        elapsedMs += elapsed
    }

    @Synchronized
    fun snapshot(): VadRunSnapshot? {
        return if (files == 0) null else VadRunSnapshot(files, beforeBytes, afterBytes, elapsedMs)
    }
}

private data class VadRunSnapshot(
    val files: Int,
    val beforeBytes: Long,
    val afterBytes: Long,
    val elapsedMs: Long
)

private enum class PrepareMode(
    val label: String,
    val reportLabel: String
) {
    COMPACT("Enviar compactado", "ogg opus 16 kHz mono 32k"),
    ORIGINAL("Enviar como está", "arquivo original, sem conversão local"),
    READY("Arquivos prontos", "wav 16 kHz mono PCM s16le")
}

private enum class VadMode(
    val preferenceKey: String,
    val label: String,
    val nativeMode: Int,
    val usesSilero: Boolean
) {
    NONE("none", "Sem VAD", 0, false),
    SILERO_CPU("silero_cpu", "Silero VAD (CPU)", 1, true),
    SILERO_GPU("silero_gpu", "Silero VAD (GPU)", 2, true),
    WEBRTC("webrtc", "WebRTC VAD", 3, false);

    companion object {
        fun fromPreference(value: String?): VadMode {
            return entries.firstOrNull { it.preferenceKey == value } ?: NONE
        }
    }
}

private class CancellationException : Exception()
