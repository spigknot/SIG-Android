package br.gov.sp.pcsp.launcher

import android.app.Activity
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.Editable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.PopupWindow
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.graphics.Matrix
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class FfmpegExtractAudioActivity : AppCompatActivity() {

    private lateinit var extractScroll: ScrollView
    private lateinit var selectionSummary: TextView
    private lateinit var videoPreview: TextureView
    private lateinit var audioWaveform: FfmpegWaveformView
    private lateinit var playbackControls: View
    private lateinit var buttonSpeedDown: ImageButton
    private lateinit var buttonPlayPause: ImageButton
    private lateinit var buttonSpeedUp: ImageButton
    private lateinit var timelineFrame: View
    private lateinit var timeline: FfmpegRangeSlider
    private lateinit var playbackSpeedLabel: TextView
    private lateinit var currentTime: TextView
    private lateinit var timeFields: View
    private lateinit var inputFrom: EditText
    private lateinit var inputTo: EditText
    private lateinit var transcriptionPresets: View
    private lateinit var checkboxTranscriptionStandard: CheckBox
    private lateinit var helpTranscriptionStandard: TextView
    private lateinit var checkboxCompactStandard: CheckBox
    private lateinit var helpCompactStandard: TextView
    private lateinit var buttonOutputExtension: TextView
    private lateinit var advancedOptions: View
    private lateinit var buttonSampleRate: TextView
    private lateinit var buttonChannels: TextView
    private lateinit var buttonBitrate: TextView
    private lateinit var selectedListBox: ScrollView
    private lateinit var selectedList: TextView
    private lateinit var terminalBox: ScrollView
    private lateinit var terminalText: TextView
    private lateinit var buttonExtract: ImageButton
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var outputFileName: TextView
    private lateinit var outputActions: View
    private lateinit var buttonOutputFolder: ImageButton
    private lateinit var buttonOutputShare: ImageButton

    private val selectedVideos = mutableListOf<SelectedVideo>()
    private val outputItems = mutableListOf<OutputItem>()
    private val terminalLines = StringBuilder()
    private var selectedOutputFolder: DocumentFile? = null
    private var zipFile: File? = null
    private var sourcePopup: PopupWindow? = null
    private val handler = Handler(Looper.getMainLooper())
    private var previewPlayer: MediaPlayer? = null
    private var durationMs = 0L
    private var syncingFields = false
    private var playWhenSeekCompletes = false
    private var playbackSpeed = 1f
    private var lastSeekTime = 0L
    private var pendingSeekPos = -1L
    private val pendingSeekDebounce = Runnable {
        if (pendingSeekPos != -1L) {
            performActualSeek(pendingSeekPos, forPlaybackStart = false)
            pendingSeekPos = -1L
        }
    }

    private var previewSurface: Surface? = null
    private var videoWidth = 0
    private var videoHeight = 0

    private val surfaceListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
            previewSurface = Surface(surfaceTexture)
            val selected = selectedVideos.singleOrNull()
            if (selected != null && isVideo(selected.mime, selected.name)) {
                preparePreview(selected.uri)
            }
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
    private var outputPreset = AudioPreset.NONE
    private var outputExtension = AudioExtension.WAV
    private var sampleRate = 16000
    private var channels = 1
    private var bitrate = "128k"
    private var refreshingOutputSettings = false
    private var isProcessing = false
    @Volatile private var currentSessionId: Long? = null
 
    private lateinit var buttonSelectOutputFolder: ImageButton
    private lateinit var arrowInputOutput: View
    private lateinit var buttonSaveToFolder: ImageButton
    private var preSelectedOutputDirUri: Uri? = null
    private var finalOutputDirUri: Uri? = null
    private val tempOutputFiles = mutableListOf<File>()
    private var hasSaved = false

    private val speedSteps = floatArrayOf(0.25f, 0.5f, 1f, 2f, 4f)

    private val progressTicker = object : Runnable {
        override fun run() {
            val player = previewPlayer
            if ((videoPreview.visibility == View.VISIBLE || audioWaveform.visibility == View.VISIBLE) && player?.isPlaying == true) {
                val position = player.currentPosition.toLong()
                val startMs = timeline.getStartMs()
                val endMs = timeline.getEndMs()
                if (position < startMs) {
                    player.pause()
                    playWhenSeekCompletes = true
                    seekPreview(startMs, forPlaybackStart = true)
                } else if (position >= endMs) {
                    player.pause()
                    playWhenSeekCompletes = false
                    seekPreview(endMs)
                    currentTime.text = formatTime(endMs)
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        keepContentInsideSystemBars()
        setContentView(R.layout.activity_ffmpeg_extract_audio)

        extractScroll = findViewById(R.id.extract_scroll)
        selectionSummary = findViewById(R.id.selection_summary)
        videoPreview = findViewById(R.id.video_preview)
        audioWaveform = findViewById(R.id.audio_waveform)
        videoPreview.surfaceTextureListener = surfaceListener
        playbackControls = findViewById(R.id.playback_controls)
        buttonSpeedDown = findViewById(R.id.button_speed_down)
        buttonPlayPause = findViewById(R.id.button_play_pause)
        buttonSpeedUp = findViewById(R.id.button_speed_up)
        timelineFrame = findViewById(R.id.timeline_frame)
        timeline = findViewById(R.id.timeline)
        playbackSpeedLabel = findViewById(R.id.playback_speed_label)
        currentTime = findViewById(R.id.current_time)
        timeFields = findViewById(R.id.time_fields)
        inputFrom = findViewById(R.id.input_from)
        inputTo = findViewById(R.id.input_to)
        transcriptionPresets = findViewById(R.id.transcription_presets)
        checkboxTranscriptionStandard = findViewById(R.id.checkbox_transcription_standard)
        helpTranscriptionStandard = findViewById(R.id.help_transcription_standard)
        checkboxCompactStandard = findViewById(R.id.checkbox_compact_standard)
        helpCompactStandard = findViewById(R.id.help_compact_standard)
        buttonOutputExtension = findViewById(R.id.button_output_extension)
        advancedOptions = findViewById(R.id.advanced_options)
        buttonSampleRate = findViewById(R.id.button_sample_rate)
        buttonChannels = findViewById(R.id.button_channels)
        buttonBitrate = findViewById(R.id.button_bitrate)
        selectedListBox = findViewById(R.id.selected_list_box)
        selectedList = findViewById(R.id.selected_list)
        terminalBox = findViewById(R.id.extract_terminal_box)
        terminalText = findViewById(R.id.extract_terminal_text)
        buttonExtract = findViewById(R.id.button_extract)
        progress = findViewById(R.id.progress)
        status = findViewById(R.id.status)
        outputFileName = findViewById(R.id.output_file_name)
        outputActions = findViewById(R.id.output_actions)
        buttonOutputFolder = findViewById(R.id.button_output_folder)
        buttonOutputShare = findViewById(R.id.button_output_share)
        buttonSelectOutputFolder = findViewById(R.id.button_select_output_folder)
        arrowInputOutput = findViewById(R.id.arrow_input_output)
        buttonSaveToFolder = findViewById(R.id.button_save_to_folder)
        buttonSelectOutputFolder.visibility = View.GONE
        arrowInputOutput.visibility = View.GONE
 
        val exitHandler = installCancelAndExitGuard(
            isTaskRunning = { isProcessing },
            cancelTask = { cancelExtraction() }
        )
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { exitHandler() }
        findViewById<View>(R.id.button_select_source).setOnClickListener { showSourceMenu(it) }
        buttonSpeedDown.setOnClickListener { changePlaybackSpeed(-1) }
        buttonPlayPause.setOnClickListener { togglePreviewPlayback() }
        buttonSpeedUp.setOnClickListener { changePlaybackSpeed(1) }
        buttonExtract.setOnClickListener {
            if (isProcessing) cancelExtraction() else extractSelectedAudio()
        }
        checkboxTranscriptionStandard.setOnCheckedChangeListener { _, checked ->
            if (!refreshingOutputSettings) setTranscriptionStandard(checked)
        }
        checkboxCompactStandard.setOnCheckedChangeListener { _, checked ->
            if (!refreshingOutputSettings) setCompactStandard(checked)
        }
        helpTranscriptionStandard.setOnClickListener { showTranscriptionStandardHelp() }
        helpCompactStandard.setOnClickListener { showCompactStandardHelp() }
        buttonOutputExtension.setOnClickListener { showExtensionMenu() }
        buttonSampleRate.setOnClickListener { showSampleRateMenu() }
        buttonChannels.setOnClickListener { showChannelsMenu() }
        buttonBitrate.setOnClickListener { showBitrateMenu() }
        outputFileName.setOnClickListener {
            if (outputItems.size == 1) openOutputFile(outputItems.first()) else openOutputFolder()
        }
        buttonOutputFolder.setOnClickListener { openOutputFolder() }
        buttonOutputShare.setOnClickListener { shareOutputs() }
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

        setExtractEnabled(false)
        timeline.onRangeChanged = { startMs, endMs, fromUser, thumb ->
            audioWaveform.setRange(startMs, endMs)
            if (fromUser) {
                updateTimeFields(startMs, endMs)
                val target = if (thumb == FfmpegRangeSlider.Thumb.END) endMs else startMs
                currentTime.text = formatTime(target)
                audioWaveform.setCurrent(target)
                seekPreview(target)
            }
        }
        timeline.onPositionChanged = { positionMs, fromUser ->
            currentTime.text = formatTime(positionMs)
            audioWaveform.setCurrent(positionMs)
            if (fromUser) seekPreview(positionMs, updateTimeline = false)
        }
        inputFrom.addTextChangedListener(timeFieldWatcher { value ->
            timeline.setStart(value.coerceIn(0L, timeline.getEndMs()))
        })
        inputTo.addTextChangedListener(timeFieldWatcher { value ->
            timeline.setEnd(value.coerceIn(timeline.getStartMs(), durationMs))
        })
        handleIncomingShareIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingShareIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        handler.post(progressTicker)
    }

    override fun onPause() {
        handler.removeCallbacks(progressTicker)
        val player = previewPlayer
        if (player?.isPlaying == true) {
            player.pause()
            playWhenSeekCompletes = false
            setPlaybackButtonPlaying(false)
        }
        super.onPause()
    }

    override fun onDestroy() {
        releasePreviewPlayer()
        previewSurface?.release()
        previewSurface = null
        super.onDestroy()
    }

    private fun preparePreview(uri: Uri) {
        releasePreviewPlayer()
        playbackSpeed = 1f
        updateSpeedButton()
        status.text = ""
        previewPlayer = MediaPlayer().apply {
            setDataSource(this@FfmpegExtractAudioActivity, uri)
            previewSurface?.takeIf { videoPreview.visibility == View.VISIBLE }?.let { setSurface(it) }
            setOnErrorListener { _, what, extra ->
                Log.w(TAG, "previewPlayer error: what=$what, extra=$extra")
                true // suppress error dialog
            }
            setOnPreparedListener { player ->
                durationMs = player.duration.toLong().coerceAtLeast(1L)
                this@FfmpegExtractAudioActivity.videoWidth = player.videoWidth
                this@FfmpegExtractAudioActivity.videoHeight = player.videoHeight
                timeline.isEnabled = true
                timeline.setRange(durationMs, 0L, durationMs)
                timeline.setCurrent(0L)
                currentTime.text = formatTime(0L)
                updateTimeFields(0L, durationMs)
                applyPreviewTransform()
                audioWaveform.configure(selectedVideos.firstOrNull()?.name ?: "áudio", durationMs)
                audioWaveform.setRange(0L, durationMs)
                audioWaveform.setCurrent(0L)
                seekPreview(0L)
            }
            setOnCompletionListener {
                timeline.setCurrent(durationMs)
                audioWaveform.setCurrent(durationMs)
                currentTime.text = formatTime(durationMs)
                setPlaybackButtonPlaying(false)
            }
            prepareAsync()
        }
    }

    private fun releasePreviewPlayer() {
        previewPlayer?.release()
        previewPlayer = null
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
            setOnClickListener {
                onClick()
            }
        }
    }

    @Deprecated("Deprecated Android callback kept for this legacy XML activity.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK) return
 
        when (requestCode) {
            REQUEST_PICK_VIDEOS -> data?.let { loadPickedVideos(it) }
            REQUEST_PICK_FOLDER -> data?.let { loadPickedFolder(it) }
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

    private fun openMediaPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/*", "audio/*"))
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_PICK_VIDEOS)
    }

    private fun openFolderPicker() {
        val downloadsUri = DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents",
            "primary:${Environment.DIRECTORY_DOWNLOADS}"
        )
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            putExtra(DocumentsContract.EXTRA_INITIAL_URI, downloadsUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_PICK_FOLDER)
    }

    private fun handleIncomingShareIntent(intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return
        var skippedSilentVideos = 0
        val videos = sharedUrisFrom(intent).mapNotNull { uri ->
            tryTakeReadPermission(uri, intent.flags)
            val name = queryDisplayName(uri) ?: "midia"
            val mime = contentResolver.getType(uri).orEmpty().ifBlank { mimeFromName(name) }
            if (!isSupportedMedia(mime, name)) return@mapNotNull null
            if (!hasAudioTrack(uri)) {
                skippedSilentVideos++
                return@mapNotNull null
            }
            SelectedVideo(uri, name, mime)
        }
        selectedOutputFolder = null
        if (videos.isEmpty()) {
            clearSelection(if (skippedSilentVideos > 0) "O vídeo selecionado não possui trilha de áudio." else "Compartilhe um arquivo de áudio ou vídeo.")
            return
        }
        selectedVideos.clear()
        selectedVideos.addAll(videos.distinctBy { it.uri })
        showSelection()
        status.text = if (skippedSilentVideos > 0) {
            "$skippedSilentVideos vídeo(s) sem áudio ignorado(s)."
        } else {
            "Arquivo recebido pelo compartilhamento."
        }
    }

    private fun hasAudioTrack(uri: Uri): Boolean {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(applicationContext, uri, null)
            (0 until extractor.trackCount).any { index ->
                val trackMime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME).orEmpty()
                trackMime.startsWith("audio/")
            }
        } catch (_: Throwable) {
            true
        } finally {
            try { extractor.release() } catch (_: Throwable) {}
        }
    }

    @Suppress("DEPRECATION")
    private fun sharedUrisFrom(intent: Intent): List<Uri> {
        val uris = mutableListOf<Uri>()
        intent.clipData?.let { clip ->
            for (index in 0 until clip.itemCount) {
                clip.getItemAt(index).uri?.let { uris += it }
            }
        }
        if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris += it }
        } else {
            intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris += it }
        }
        intent.data?.let { uris += it }
        return uris.distinct()
    }

    private fun tryTakeReadPermission(uri: Uri, flags: Int) {
        try {
            contentResolver.takePersistableUriPermission(uri, flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Throwable) {
        }
    }

    private fun loadPickedVideos(data: Intent) {
        val uris = mutableListOf<Uri>()
        data.clipData?.let { clip ->
            for (index in 0 until clip.itemCount) {
                uris.add(clip.getItemAt(index).uri)
            }
        }
        data.data?.let { uris.add(it) }

        var skippedSilentVideos = 0
        val videos = uris.distinct().mapNotNull { uri ->
            val mime = contentResolver.getType(uri).orEmpty()
            val name = queryDisplayName(uri) ?: "midia"
            if (!isSupportedMedia(mime, name)) return@mapNotNull null
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
            }
            if (!hasAudioTrack(uri)) {
                skippedSilentVideos++
                return@mapNotNull null
            }
            SelectedVideo(uri, name, mime.ifBlank { mimeFromName(name) })
        }

        selectedOutputFolder = null
        if (videos.isEmpty()) {
            clearSelection(if (skippedSilentVideos > 0) "Os vídeos selecionados não possuem trilha de áudio." else "Escolha arquivos de áudio ou vídeo.")
            return
        }

        selectedVideos.clear()
        selectedVideos.addAll(videos)
        showSelection()
        if (skippedSilentVideos > 0) {
            status.text = "$skippedSilentVideos vídeo(s) sem trilha de áudio ignorado(s)."
        }
    }

    private fun loadPickedFolder(data: Intent) {
        val treeUri = data.data ?: return
        try {
            val canRead = data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0
            val canWrite = data.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0
            when {
                canRead && canWrite -> contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                canRead -> contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                canWrite -> contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
        } catch (_: SecurityException) {
        }

        val folder = DocumentFile.fromTreeUri(this, treeUri)
        if (folder == null || !folder.isDirectory) {
            clearSelection("Não consegui abrir essa pasta.")
            return
        }

        var skippedSilentVideos = 0
        val videos = folder.listFiles()
            .filter { it.isFile }
            .mapNotNull { file ->
                val name = file.name ?: "midia"
                val mime = file.type.orEmpty().ifBlank { mimeFromName(name) }
                if (!isSupportedMedia(mime, name)) return@mapNotNull null
                if (!hasAudioTrack(file.uri)) {
                    skippedSilentVideos++
                    return@mapNotNull null
                }
                SelectedVideo(file.uri, name, mime)
            }

        selectedOutputFolder = null
        if (videos.isEmpty()) {
            clearSelection(if (skippedSilentVideos > 0) "Os vídeos da pasta não possuem trilha de áudio." else "A pasta escolhida não tem áudio ou vídeo reconhecido.")
            return
        }

        selectedVideos.clear()
        selectedVideos.addAll(videos)
        showSelection()
        if (skippedSilentVideos > 0) {
            status.text = "$skippedSilentVideos vídeo(s) sem trilha de áudio ignorado(s)."
        }
    }

    private fun showSelection() {
        clearOutputResult()
        setExtractEnabled(true)
        val count = selectedVideos.size
        selectionSummary.visibility = View.VISIBLE
        selectionSummary.text = if (count == 1) {
            selectedVideos.first().name
        } else {
            "$count arquivos selecionados"
        }
        showOutputSettings(true)

        buttonSelectOutputFolder.visibility = View.VISIBLE
        arrowInputOutput.visibility = View.VISIBLE
        if (preSelectedOutputDirUri != null) {
            buttonSelectOutputFolder.setBackgroundResource(R.drawable.ffmpeg_outline_green_button_bg)
        } else {
            buttonSelectOutputFolder.setBackgroundResource(R.drawable.ffmpeg_outline_button_bg)
        }

        if (count == 1) {
            val selected = selectedVideos.first()
            selectedListBox.visibility = View.GONE
            val previewVideo = isVideo(selected.mime, selected.name)
            videoPreview.visibility = if (previewVideo) View.VISIBLE else View.GONE
            audioWaveform.visibility = if (previewVideo) View.GONE else View.VISIBLE
            showSingleMediaControls(true, showTimeFields = true)
            durationMs = 0L
            playbackSpeed = 1f
            updateSpeedButton()
            setPlaybackButtonPlaying(false)
            timeline.isEnabled = false
            timeline.setRange(1L, 0L, 1L)
            timeline.setCurrent(0L)
            currentTime.text = formatTime(0L)
            if (previewVideo && videoPreview.isAvailable) {
                previewSurface = Surface(videoPreview.surfaceTexture)
                preparePreview(selected.uri)
            } else if (!previewVideo) {
                preparePreview(selected.uri)
            }
        } else {
            releasePreviewPlayer()
            videoPreview.visibility = View.GONE
            audioWaveform.visibility = View.GONE
            showSingleMediaControls(false)
            selectedListBox.visibility = View.VISIBLE
            selectedList.text = buildSelectedListText()
        }

        status.text = ""
    }

    private fun buildSelectedListText(): String {
        val visible = selectedVideos.take(10).mapIndexed { index, video ->
            "${index + 1}. ${video.name}"
        }
        val hiddenCount = selectedVideos.size - visible.size
        return buildString {
            append(visible.joinToString("\n"))
            if (hiddenCount > 0) {
                append("\n+")
                append(hiddenCount)
                append(" arquivos")
            }
        }
    }

    private fun extractSelectedAudio() {
        if (selectedVideos.isEmpty()) return
        if (!hasSigStorageAccess()) {
            requestSigStorageAccess()
            status.text = "Libere o acesso a todos os arquivos para salvar na pasta SIG."
            return
        }
        val trimSingleMedia = selectedVideos.size == 1
        val startMs = if (trimSingleMedia) parseTime(inputFrom.text.toString()) else 0L
        val endMs = if (trimSingleMedia) parseTime(inputTo.text.toString()) else null
        if (trimSingleMedia && (startMs == null || endMs == null || endMs <= startMs)) {
            status.text = "Confira os tempos de início e fim."
            return
        }

        val jobVideos = selectedVideos.toList()
        val processingStartMs = SystemClock.elapsedRealtime()
        val jobSettings = currentAudioSettings()
        val jobOutputMime = jobSettings.extension.mime
        clearOutputResult()
        clearTerminal()
        setProcessing(true)

        Thread {
            val results = mutableListOf<OutputItem>()
            val failures = mutableListOf<String>()
            val usedNames = mutableSetOf<String>()
            val taskList = jobVideos.mapIndexed { index, _ ->
                if (jobVideos.size > 1) "Extraindo áudio ${index + 1}/${jobVideos.size}" else "Extraindo áudio"
            }
            val tracker = FfmpegTaskTracker(status, taskList)
            var totalDurationMs = 0L
            var cancelled = false

            for ((index, video) in jobVideos.withIndex()) {
                if (Thread.interrupted()) {
                    cancelled = true
                    tracker.fail("Cancelado")
                    break
                }

                try {
                    val inputFile = copyUriToCache(video.uri, video.name)
                    appendTerminalAudioInfo("original: ${describeAudioFile(inputFile)}")
                    val outputName = buildOutputName(video.name, usedNames, jobSettings.extension)
                    val tempOutput = File(cacheDir, "audio_${System.currentTimeMillis()}_$outputName")
                    val trimStartMs = if (trimSingleMedia) startMs ?: 0L else 0L
                    val trimEndMs = if (trimSingleMedia) endMs else null
                    val expectedDuration = trimEndMs?.let { it - trimStartMs } ?: readDuration(video.uri)
                    totalDurationMs += expectedDuration
                    tracker.setTaskEncoder(index, audioEncoderForExtension(jobSettings.extension))
                    tracker.startTask(index)
                    val session = executeFfmpegWithProgress(
                        buildFfmpegArguments(inputFile, tempOutput, trimStartMs, trimEndMs, jobSettings),
                        expectedDuration,
                        tracker
                    )

                    if (ReturnCode.isCancel(session.returnCode)) {
                        tracker.fail("Operação cancelada.")
                        cancelled = true
                        break
                    }

                    if (!ReturnCode.isSuccess(session.returnCode) || !tempOutput.exists() || tempOutput.length() == 0L) {
                        val logTail = session.allLogsAsString.orEmpty().lines().takeLast(2).joinToString(" ")
                        failures.add("${video.name}: ${logTail.ifBlank { "sem áudio extraído" }}")
                        tracker.fail("Falhou em ${video.name}")
                        continue
                    }
                    tracker.completeCurrentTask()
                    appendTerminalAudioInfo("gerado: ${describeAudioFile(tempOutput)}")
                    tempOutputFiles.add(tempOutput)
                    results.add(OutputItem(Uri.fromFile(tempOutput), outputName, jobOutputMime))
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to extract audio from ${video.name}", e)
                    failures.add("${video.name}: ${e.message ?: "falha inesperada"}")
                }
            }

            runOnUiThread {
                setProcessing(false)
                if (cancelled) {
                    // Já atualizado pelo tracker
                } else if (results.isNotEmpty()) {
                    hasSaved = false
                    outputItems.clear()
                    outputItems.addAll(results)
 
                    outputFileName.text = if (results.size == 1) results.first().name else "${results.size} arquivos de áudio"
                    outputFileName.visibility = View.VISIBLE
 
                    outputActions.visibility = View.VISIBLE
                    buttonSelectOutputFolder.visibility = View.VISIBLE
                    arrowInputOutput.visibility = View.VISIBLE
                    buttonSaveToFolder.visibility = View.VISIBLE
                    buttonOutputFolder.visibility = View.GONE
                    buttonOutputShare.visibility = View.GONE
                    
                    val elapsedMs = SystemClock.elapsedRealtime() - processingStartMs
                    val elapsedSeconds = (elapsedMs / 1000.0).coerceAtLeast(0.001)
                    val mediaSeconds = totalDurationMs / 1000.0
                    val efficiency = String.format(Locale.US, "%.2fx", mediaSeconds / elapsedSeconds)
                    tracker.success("Tempo de processamento: ${formatTime(elapsedMs)}\nMídia processada: ${formatTime(totalDurationMs)}\nEficiência: $efficiency")
                } else {
                    tracker.fail("Não consegui extrair áudio.\n${failures.take(3).joinToString("\n")}".trim())
                }
            }
        }.start()
    }

    private fun buildFfmpegArguments(
        inputFile: File,
        outputFile: File,
        startMs: Long,
        endMs: Long?,
        settings: AudioSettings = currentAudioSettings()
    ): Array<String> {
        val args = mutableListOf("-y")
        if (endMs != null) {
            args.addAll(listOf("-ss", formatSeconds(startMs)))
        }
        args.addAll(listOf("-i", inputFile.absolutePath))
        if (endMs != null) {
            args.addAll(listOf("-t", formatSeconds(endMs - startMs)))
        }
        args.addAll(
            listOf(
                "-vn",
                "-map", "0:a:0",
                "-ar", settings.sampleRate.toString(),
                "-ac", settings.channels.toString()
            )
        )
        when (settings.extension) {
            AudioExtension.WAV -> {
                args.addAll(listOf("-c:a", "pcm_s16le", "-f", "wav"))
            }
            AudioExtension.MP3 -> {
                args.addAll(listOf("-c:a", "libmp3lame", "-b:a", settings.bitrate, "-minrate", settings.bitrate, "-maxrate", settings.bitrate))
            }
            AudioExtension.M4A -> {
                args.addAll(listOf("-c:a", "aac", "-b:a", settings.bitrate, "-movflags", "+faststart"))
            }
            AudioExtension.AAC -> {
                args.addAll(listOf("-c:a", "aac", "-b:a", settings.bitrate))
            }
            AudioExtension.OGG -> {
                args.addAll(listOf("-c:a", "libvorbis", "-b:a", settings.bitrate))
            }
            AudioExtension.OPUS -> {
                args.addAll(listOf(
                    "-c:a", "libopus",
                    "-application", "voip",
                    "-b:a", settings.bitrate,
                    "-vbr", "off"
                ))
            }
            AudioExtension.FLAC -> {
                args.addAll(listOf("-c:a", "flac"))
            }
        }
        args.add(outputFile.absolutePath)
        return args.toTypedArray()
    }

    private fun saveTempOutputsToUri(treeUri: Uri) {
        val destDir = DocumentFile.fromTreeUri(this, treeUri)
        if (destDir == null || !destDir.isDirectory) {
            status.text = "Erro: pasta de destino inválida."
            return
        }
 
        var savedCount = 0
        val savedItems = mutableListOf<OutputItem>()
 
        for (tempFile in tempOutputFiles) {
            if (!tempFile.exists()) continue
            val outputName = tempFile.name.substringAfter('_')
            val fileMime = mimeForOutputFile(tempFile)
            try {
                val document = destDir.createFile(fileMime, outputName)
                if (document != null) {
                    contentResolver.openOutputStream(document.uri)?.use { output ->
                        tempFile.inputStream().use { input ->
                            input.copyTo(output)
                        }
                    }
                    savedCount++
                    savedItems.add(OutputItem(document.uri, outputName, fileMime))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save file $outputName to selected folder", e)
            }
        }
 
        if (savedCount > 0) {
            hasSaved = true
            finalOutputDirUri = treeUri
            outputItems.clear()
            outputItems.addAll(savedItems)
 
            val folderName = destDir.name ?: "Pasta selecionada"
            status.text = "Arquivo(s) salvo(s) na pasta \"$folderName\""
 
            buttonSaveToFolder.visibility = View.GONE
            buttonSelectOutputFolder.visibility = View.GONE
            arrowInputOutput.visibility = View.GONE
            buttonOutputFolder.visibility = View.VISIBLE
            buttonOutputShare.visibility = View.VISIBLE
        } else {
            status.text = "Erro ao salvar os arquivos na pasta selecionada."
        }
    }

    private fun hasSigStorageAccess(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
    }

    private fun requestSigStorageAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val appSettings = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:$packageName")
        )
        val allFilesSettings = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        try {
            startActivity(appSettings)
        } catch (_: ActivityNotFoundException) {
            try {
                startActivity(allFilesSettings)
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(this, "Não consegui abrir a permissão de arquivos.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun copyUriToCache(uri: Uri, displayName: String): File {
        val extension = displayName.substringAfterLast('.', "tmp")
        val inputFile = File(cacheDir, "extract_input_${System.currentTimeMillis()}.$extension")
        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(inputFile).use { output -> input.copyTo(output) }
        }
        return inputFile
    }

    private fun buildOutputName(
        name: String,
        usedNames: MutableSet<String>,
        audioExt: AudioExtension = currentAudioSettings().extension
    ): String {
        val rawBase = name.substringBeforeLast('.', name).ifBlank { "audio" }
        val base = rawBase.replace(Regex("""[\\/:*?"<>|]"""), "_")
        val extension = audioExt.ext
        var candidate = "$base.$extension"
        var suffix = 2
        while (!usedNames.add(candidate.lowercase(Locale.ROOT))) {
            candidate = "${base}_$suffix.$extension"
            suffix++
        }
        return candidate
    }

    private fun setTranscriptionStandard(enabled: Boolean) {
        outputPreset = if (enabled) AudioPreset.LOCAL else AudioPreset.NONE
        if (enabled) {
            outputExtension = AudioExtension.WAV
            sampleRate = 16000
            channels = 1
            bitrate = "256k"
        }
        refreshOutputSettingsUi()
    }

    private fun setCompactStandard(enabled: Boolean) {
        outputPreset = if (enabled) AudioPreset.COMPACT else AudioPreset.NONE
        if (enabled) {
            outputExtension = AudioExtension.OGG
            sampleRate = 16000
            channels = 1
            bitrate = "32k"
        }
        refreshOutputSettingsUi()
    }

    private fun currentAudioSettings(): AudioSettings {
        return when (outputPreset) {
            AudioPreset.LOCAL -> AudioSettings(AudioExtension.WAV, 16000, 1, "256k")
            AudioPreset.COMPACT -> AudioSettings(AudioExtension.OGG, 16000, 1, "32k")
            AudioPreset.NONE -> AudioSettings(outputExtension, sampleRate, channels, bitrate)
        }
    }

    private fun audioEncoderForExtension(extension: AudioExtension): String {
        return when (extension) {
            AudioExtension.WAV -> "pcm_s16le"
            AudioExtension.MP3 -> "libmp3lame"
            AudioExtension.M4A, AudioExtension.AAC -> "aac"
            AudioExtension.OGG -> "libvorbis"
            AudioExtension.OPUS -> "libopus"
            AudioExtension.FLAC -> "flac"
        }
    }

    private fun currentOutputMime(): String {
        return currentAudioSettings().extension.mime
    }

    private fun mimeForOutputFile(file: File): String {
        val ext = file.extension.lowercase(Locale.ROOT)
        return AudioExtension.values().firstOrNull { it.ext == ext }?.mime
            ?: currentOutputMime()
    }

    private fun showOutputSettings(visible: Boolean) {
        val visibility = if (visible) View.VISIBLE else View.GONE
        transcriptionPresets.visibility = visibility
        buttonOutputExtension.visibility = visibility
        advancedOptions.visibility = visibility
        refreshOutputSettingsUi()
    }

    private fun refreshOutputSettingsUi() {
        val settings = currentAudioSettings()
        refreshingOutputSettings = true
        try {
            if (checkboxTranscriptionStandard.isChecked != (outputPreset == AudioPreset.LOCAL)) {
                checkboxTranscriptionStandard.isChecked = outputPreset == AudioPreset.LOCAL
            }
            if (checkboxCompactStandard.isChecked != (outputPreset == AudioPreset.COMPACT)) {
                checkboxCompactStandard.isChecked = outputPreset == AudioPreset.COMPACT
            }
        } finally {
            refreshingOutputSettings = false
        }
        buttonOutputExtension.text = ".${settings.extension.ext}"
        val customEnabled = outputPreset == AudioPreset.NONE
        buttonOutputExtension.isEnabled = customEnabled
        buttonOutputExtension.alpha = if (customEnabled) 1f else 0.45f
        buttonSampleRate.text = "${settings.sampleRate} Hz"
        buttonChannels.text = if (settings.channels == 1) "mono" else "estéreo"
        buttonBitrate.text = if (settings.extension.supportsBitrate) settings.bitrate else settings.extension.fixedBitrateLabel
        listOf(buttonSampleRate, buttonChannels).forEach { button ->
            button.isEnabled = customEnabled
            button.alpha = if (customEnabled) 1f else 0.45f
        }
        val bitrateEnabled = customEnabled && settings.extension.supportsBitrate
        buttonBitrate.isEnabled = bitrateEnabled
        buttonBitrate.alpha = if (bitrateEnabled) 1f else 0.45f
    }

    private fun showExtensionMenu() {
        if (outputPreset != AudioPreset.NONE) return
        PopupMenu(this, buttonOutputExtension).apply {
            val extensions = listOf(
                AudioExtension.WAV,
                AudioExtension.OGG,
                AudioExtension.M4A,
                AudioExtension.MP3
            )
            extensions.forEach { menu.add(it.ext) }
            setOnMenuItemClickListener { item ->
                outputExtension = extensions.first { it.ext == item.title.toString() }
                refreshOutputSettingsUi()
                true
            }
            show()
        }
    }

    private fun showSampleRateMenu() {
        if (outputPreset != AudioPreset.NONE) return
        PopupMenu(this, buttonSampleRate).apply {
            listOf(8000, 16000, 22050, 44100, 48000).forEach { menu.add(it.toString()) }
            setOnMenuItemClickListener { item ->
                sampleRate = item.title.toString().toInt()
                refreshOutputSettingsUi()
                true
            }
            show()
        }
    }

    private fun showChannelsMenu() {
        if (outputPreset != AudioPreset.NONE) return
        PopupMenu(this, buttonChannels).apply {
            menu.add("mono")
            menu.add("estéreo")
            setOnMenuItemClickListener { item ->
                channels = if (item.title.toString() == "mono") 1 else 2
                refreshOutputSettingsUi()
                true
            }
            show()
        }
    }

    private fun showBitrateMenu() {
        if (outputPreset != AudioPreset.NONE) return
        if (!outputExtension.supportsBitrate) {
            Toast.makeText(this, "Este formato não usa bitrate configurável.", Toast.LENGTH_SHORT).show()
            return
        }
        PopupMenu(this, buttonBitrate).apply {
            listOf("24k", "32k", "48k", "64k", "96k", "128k", "192k", "256k").forEach { menu.add(it) }
            setOnMenuItemClickListener { item ->
                bitrate = item.title.toString()
                refreshOutputSettingsUi()
                true
            }
            show()
        }
    }

    private fun showTranscriptionStandardHelp() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setMessage("O padrão para transcrição gera um WAV 16-bit PCM, 16000 Hz e mono. É o formato pronto para transcrição por IA local ou remota quando você quer máxima compatibilidade e menos chance de erro de leitura.")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showCompactStandardHelp() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setMessage("O padrão compacto gera um OGG mono, 16000 Hz e 32k. Ele é indicado quando você quer enviar o áudio pela rede usando menos dados, mantendo um formato leve para transcrição remota.")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showSingleMediaControls(visible: Boolean, showTimeFields: Boolean = visible) {
        val visibility = if (visible) View.VISIBLE else View.GONE
        playbackControls.visibility = visibility
        timelineFrame.visibility = visibility
        currentTime.visibility = visibility
        timeFields.visibility = if (showTimeFields) View.VISIBLE else View.GONE
    }

    private fun togglePreviewPlayback() {
        if (videoPreview.visibility != View.VISIBLE && audioWaveform.visibility != View.VISIBLE) return
        val player = previewPlayer ?: return
        if (player.isPlaying) {
            player.pause()
            playWhenSeekCompletes = false
            setPlaybackButtonPlaying(false)
            return
        }

        val startMs = timeline.getStartMs()
        val endMs = timeline.getEndMs()
        val currentMs = player.currentPosition.toLong()
        val playFromMs = if (currentMs < startMs || currentMs >= endMs) startMs else currentMs
        if (currentMs < startMs || currentMs >= endMs) {
            playWhenSeekCompletes = true
            seekPreview(playFromMs, forPlaybackStart = true)
            setPlaybackButtonPlaying(true)
            syncPlaybackButtonSoon()
            return
        }

        timeline.setCurrent(playFromMs)
        startPreview()
    }

    private fun startPreview() {
        applyPlaybackSpeed()
        previewPlayer?.start()
        setPlaybackButtonPlaying(previewPlayer?.isPlaying == true)
        syncPlaybackButtonSoon()
    }

    private fun seekPreview(positionMs: Long, updateTimeline: Boolean = true, forPlaybackStart: Boolean = false) {
        if (videoPreview.visibility != View.VISIBLE && audioWaveform.visibility != View.VISIBLE) return
        val safePosition = positionMs.coerceIn(0L, durationMs).coerceAtMost(Int.MAX_VALUE.toLong())
        
        if (updateTimeline) {
            timeline.setCurrent(safePosition)
        }
        audioWaveform.setCurrent(safePosition)
        currentTime.text = formatTime(safePosition)

        if (forPlaybackStart) {
            performActualSeek(safePosition, forPlaybackStart = true)
        } else {
            val now = SystemClock.elapsedRealtime()
            if (now - lastSeekTime >= 150L) {
                lastSeekTime = now
                performActualSeek(safePosition, forPlaybackStart = false)
                pendingSeekPos = -1L
            } else {
                pendingSeekPos = safePosition
                handler.removeCallbacks(pendingSeekDebounce)
                handler.postDelayed(pendingSeekDebounce, 100L)
            }
        }
    }

    private fun performActualSeek(safePosition: Long, forPlaybackStart: Boolean) {
        val player = previewPlayer ?: return
        try {
            if (forPlaybackStart && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                player.seekTo(safePosition, MediaPlayer.SEEK_NEXT_SYNC)
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    player.seekTo(safePosition, MediaPlayer.SEEK_CLOSEST)
                } else {
                    player.seekTo(safePosition.toInt())
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error performing actual seek", e)
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
        val player = previewPlayer ?: return
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
        buttonSpeedDown.contentDescription = "Desacelerar, velocidade atual $label"
        buttonSpeedUp.contentDescription = "Acelerar, velocidade atual $label"
    }

    private fun setPlaybackButtonPlaying(isPlaying: Boolean) {
        buttonPlayPause.setImageResource(if (isPlaying) R.drawable.ic_ffmpeg_pause else R.drawable.ic_ffmpeg_play)
        buttonPlayPause.contentDescription = if (isPlaying) "Pausar" else "Reproduzir"
    }

    private fun syncPlaybackButtonSoon() {
        handler.postDelayed({
            setPlaybackButtonPlaying((videoPreview.visibility == View.VISIBLE || audioWaveform.visibility == View.VISIBLE) && previewPlayer?.isPlaying == true)
        }, 180L)
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

    private fun updateTimeFields(startMs: Long, endMs: Long) {
        syncingFields = true
        inputFrom.setText(formatTime(startMs))
        inputTo.setText(formatTime(endMs))
        syncingFields = false
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

    private fun openOutputFile(item: OutputItem) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(item.uri, item.mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "Não encontrei um app para abrir ${item.name}.", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.w(TAG, "Could not open output file", e)
            Toast.makeText(this, "Não consegui abrir o arquivo.", Toast.LENGTH_SHORT).show()
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
            val dlIntent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)
            try {
                startActivity(dlIntent)
            } catch (_: Exception) {
                Toast.makeText(this, "Não consegui abrir a pasta.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun shareOutputs() {
        if (outputItems.isEmpty()) return
        if (outputItems.size == 1) {
            shareSingleOutput(outputItems.first())
        } else {
            shareZip()
        }
    }

    private fun shareSingleOutput(item: OutputItem) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = item.mime
            putExtra(Intent.EXTRA_STREAM, item.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Compartilhar áudio"))
    }

    private fun shareZip() {
        try {
            val zip = File(cacheDir, "audios_extraidos_${System.currentTimeMillis()}.zip")
            ZipOutputStream(zip.outputStream()).use { zipOutput ->
                zipOutput.setLevel(Deflater.BEST_SPEED)
                outputItems.forEach { item ->
                    zipOutput.putNextEntry(ZipEntry(item.name))
                    contentResolver.openInputStream(item.uri)?.use { input -> input.copyTo(zipOutput) }
                    zipOutput.closeEntry()
                }
            }
            zipFile = zip
            val zipUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", zip)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, zipUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Compartilhar ZIP"))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create share zip", e)
            Toast.makeText(this, "Não consegui gerar o ZIP.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setProcessing(processing: Boolean) {
        isProcessing = processing
        progress.visibility = if (processing) View.VISIBLE else View.GONE
        buttonExtract.isEnabled = true
        buttonExtract.isClickable = true
        buttonExtract.isFocusable = true
        buttonExtract.alpha = 1f
        buttonExtract.visibility = View.VISIBLE
        if (processing) {
            status.text = ""
            clearOutputResult()
            buttonExtract.setImageResource(R.drawable.ic_ffmpeg_cancel_red)
            buttonExtract.setBackgroundResource(R.drawable.ffmpeg_outline_red_button_bg)
            buttonExtract.contentDescription = "Cancelar"
        } else {
            currentSessionId = null
            buttonExtract.setImageResource(R.drawable.ic_ffmpeg_extract_audio)
            buttonExtract.setBackgroundResource(R.drawable.ffmpeg_outline_green_button_bg)
            buttonExtract.contentDescription = "Extrair áudio"
            setExtractEnabled(selectedVideos.isNotEmpty())
        }
        checkboxTranscriptionStandard.isEnabled = !processing
        checkboxCompactStandard.isEnabled = !processing
        buttonOutputExtension.isEnabled = !processing
        buttonSampleRate.isEnabled = !processing
        buttonChannels.isEnabled = !processing
        buttonBitrate.isEnabled = !processing
        buttonSelectOutputFolder.isEnabled = !processing
        findViewById<View>(R.id.button_select_video).isEnabled = !processing
        buttonPlayPause.isEnabled = !processing
        buttonSpeedDown.isEnabled = !processing
        buttonSpeedUp.isEnabled = !processing
        inputFrom.isEnabled = !processing
        inputTo.isEnabled = !processing
    }

    private fun cancelExtraction() {
        status.text = "Cancelando..."
        currentSessionId?.let { FFmpegKit.cancel(it) } ?: FFmpegKit.cancel()
    }

    private fun executeFfmpegWithProgress(
        arguments: Array<String>,
        expectedDurationMs: Long,
        tracker: FfmpegTaskTracker
    ): FFmpegSession {
        val latch = CountDownLatch(1)
        val sessionRef = AtomicReference<FFmpegSession>()
        val safeDuration = expectedDurationMs.coerceAtLeast(1L)
        val startedAt = SystemClock.elapsedRealtime()
        val session = FFmpegKit.executeWithArgumentsAsync(
            arguments,
            { session ->
                sessionRef.set(session)
                latch.countDown()
            },
            { },
            { statistics ->
                val percent = ((statistics.time / safeDuration.toDouble()) * 100.0)
                    .toInt()
                    .coerceIn(0, 99)
                tracker.setProgress(percent)
            }
        )
        currentSessionId = session.sessionId
        latch.await()
        currentSessionId = null
        return sessionRef.get() ?: session
    }

    private fun clearTerminal() {
        synchronized(terminalLines) { terminalLines.clear() }
        terminalBox.visibility = View.GONE
        terminalText.text = ""
    }

    private fun appendTerminalAudioInfo(line: String) {
        synchronized(terminalLines) {
            terminalLines.append(AUDIO_INFO_START).append(line).append(AUDIO_INFO_END).append('\n')
        }
        runOnUiThread { updateTerminalText() }
    }

    private fun updateTerminalText() {
        val rawText = synchronized(terminalLines) { terminalLines.toString() }
        terminalBox.visibility = if (rawText.isBlank()) View.GONE else View.VISIBLE
        terminalText.text = renderTerminalText(rawText)
        terminalText.post {
            val layout = terminalText.layout ?: return@post
            val scrollAmount = layout.getLineTop(terminalText.lineCount) - terminalText.height + terminalText.totalPaddingTop + terminalText.totalPaddingBottom
            terminalText.scrollTo(0, scrollAmount.coerceAtLeast(0))
        }
    }

    private fun renderTerminalText(rawText: String): SpannableString {
        val clean = StringBuilder()
        val ranges = mutableListOf<IntRange>()
        var index = 0
        while (index < rawText.length) {
            val start = rawText.indexOf(AUDIO_INFO_START, index)
            if (start < 0) {
                clean.append(rawText.substring(index))
                break
            }
            clean.append(rawText.substring(index, start))
            val textStart = clean.length
            val contentStart = start + AUDIO_INFO_START.length
            val end = rawText.indexOf(AUDIO_INFO_END, contentStart)
            if (end < 0) {
                clean.append(rawText.substring(contentStart))
                ranges += textStart until clean.length
                break
            }
            clean.append(rawText.substring(contentStart, end))
            ranges += textStart until clean.length
            index = end + AUDIO_INFO_END.length
        }
        val spannable = SpannableString(clean.toString())
        ranges.forEach { range ->
            if (!range.isEmpty()) {
                spannable.setSpan(
                    ForegroundColorSpan(Color.rgb(255, 216, 86)),
                    range.first,
                    range.last + 1,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        return spannable
    }

    private fun describeAudioFile(file: File): String {
        val info = probeAudioFile(file)
        val sampleRate = if (
            file.extension.equals(AudioExtension.OPUS.ext, ignoreCase = true) &&
            info.sampleRate == "48000hz"
        ) {
            "48000hz (Opus)"
        } else {
            info.sampleRate.ifBlank { "hz ?" }
        }
        return listOf(
            ".${file.extension.lowercase(Locale.ROOT).ifBlank { "sem extensão" }}",
            sampleRate,
            info.channels.ifBlank { "canal ?" },
            info.bitrate.ifBlank { "bitrate ?" }
        ).joinToString(", ")
    }

    private fun probeAudioFile(file: File): AudioProbe {
        return try {
            val session = FFmpegKit.executeWithArguments(arrayOf("-hide_banner", "-i", file.absolutePath))
            val logs = session.allLogsAsString.orEmpty()
            val audioLine = logs.lines().firstOrNull { it.contains("Audio:", ignoreCase = true) }.orEmpty()
            val sampleRate = Regex("""(\d+)\s*Hz""", RegexOption.IGNORE_CASE)
                .find(audioLine)
                ?.groupValues
                ?.getOrNull(1)
                ?.let { "${it}hz" }
                .orEmpty()
            val channels = when {
                audioLine.contains("mono", ignoreCase = true) -> "mono"
                audioLine.contains("stereo", ignoreCase = true) -> "stereo"
                else -> Regex("""(\d+)\s*channels""", RegexOption.IGNORE_CASE)
                    .find(audioLine)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.let { "${it}ch" }
                    .orEmpty()
            }
            val bitrate = Regex("""(\d+(?:\.\d+)?)\s*kb/s""", RegexOption.IGNORE_CASE)
                .find(logs)
                ?.groupValues
                ?.getOrNull(1)
                ?.let { "${it.removeSuffix(".0")}k" }
                ?: estimateBitrate(file)
            AudioProbe(sampleRate, channels, bitrate)
        } catch (_: Throwable) {
            AudioProbe("", "", "")
        }
    }

    private fun estimateBitrate(file: File): String {
        val durationMs = readDuration(file)
        if (durationMs <= 0L) return ""
        val kbps = ((file.length().toDouble() * 8.0) / durationMs.toDouble()).toInt().coerceAtLeast(1)
        return "${kbps}k"
    }

    private fun readDuration(uri: Uri): Long {
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            retriever.setDataSource(this, uri)
            retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?: 1L
        } catch (e: Exception) {
            1L
        } finally {
            retriever.release()
        }
    }

    private fun readDuration(file: File): Long {
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?: 1L
        } catch (e: Exception) {
            1L
        } finally {
            retriever.release()
        }
    }

    private fun setExtractEnabled(enabled: Boolean) {
        if (isProcessing) return
        buttonExtract.visibility = if (enabled) View.VISIBLE else View.GONE
        buttonExtract.alpha = if (enabled) 1f else 0.45f
        buttonExtract.isClickable = enabled
        buttonExtract.isFocusable = enabled
    }

    private fun clearSelection(message: String) {
        selectedVideos.clear()
        selectedOutputFolder = null
        selectionSummary.text = ""
        selectionSummary.visibility = View.GONE
        selectedListBox.visibility = View.GONE
        showOutputSettings(false)
        releasePreviewPlayer()
        videoPreview.visibility = View.GONE
        buttonSelectOutputFolder.visibility = View.GONE
        arrowInputOutput.visibility = View.GONE
        clearOutputResult()
        clearTerminal()
        setExtractEnabled(false)
        status.text = message
    }

    private fun clearOutputResult() {
        outputItems.clear()
        zipFile = null
        tempOutputFiles.clear()
        hasSaved = false
        outputFileName.visibility = View.GONE
        outputActions.visibility = View.GONE
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

    private fun isVideo(mime: String, name: String): Boolean {
        if (mime.startsWith("video/")) return true
        val lowerName = name.lowercase(Locale.ROOT)
        return VIDEO_EXTENSIONS.any { lowerName.endsWith(it) }
    }

    private fun isAudio(mime: String, name: String): Boolean {
        if (mime.startsWith("audio/")) return true
        val lowerName = name.lowercase(Locale.ROOT)
        return AUDIO_EXTENSIONS.any { lowerName.endsWith(it) }
    }

    private fun isSupportedMedia(mime: String, name: String): Boolean {
        return isVideo(mime, name) || isAudio(mime, name)
    }

    private fun mimeFromName(name: String): String {
        return if (isAudio("", name)) "audio/*" else "video/*"
    }

    companion object {
        private const val REQUEST_PICK_VIDEOS = 5101
        private const val REQUEST_PICK_FOLDER = 5102
        private const val REQUEST_CHOOSE_OUTPUT_DIR = 5103
        private const val REQUEST_CHOOSE_PRE_OUTPUT_DIR = 5104
        private const val OUTPUT_FOLDER_NAME = "SIG"
        private const val TAG = "FfmpegExtractAudio"
        private const val AUDIO_INFO_START = "\uE000AI\uE000"
        private const val AUDIO_INFO_END = "\uE000AE\uE000"
        private val VIDEO_EXTENSIONS = setOf(".mp4", ".mkv", ".mov", ".avi", ".webm", ".3gp", ".m4v")
        private val AUDIO_EXTENSIONS = setOf(".wav", ".mp3", ".m4a", ".aac", ".ogg", ".opus", ".flac", ".wma", ".amr")
    }

    private data class SelectedVideo(
        val uri: Uri,
        val name: String,
        val mime: String
    )

    private data class OutputItem(
        val uri: Uri,
        val name: String,
        val mime: String
    )

    private data class AudioSettings(
        val extension: AudioExtension,
        val sampleRate: Int,
        val channels: Int,
        val bitrate: String
    )

    private data class SaveResult(
        val uri: Uri?,
        val error: String?
    )

    private data class AudioProbe(
        val sampleRate: String,
        val channels: String,
        val bitrate: String
    )

    private enum class AudioPreset {
        NONE,
        LOCAL,
        COMPACT
    }

    private enum class AudioExtension(
        val ext: String,
        val mime: String,
        val supportsBitrate: Boolean,
        val fixedBitrateLabel: String = ""
    ) {
        WAV("wav", "audio/wav", false, "PCM"),
        M4A("m4a", "audio/mp4", true),
        MP3("mp3", "audio/mpeg", true),
        AAC("aac", "audio/aac", true),
        OGG("ogg", "audio/ogg", true),
        OPUS("opus", "audio/opus", true),
        FLAC("flac", "audio/flac", false, "FLAC")
    }
}
