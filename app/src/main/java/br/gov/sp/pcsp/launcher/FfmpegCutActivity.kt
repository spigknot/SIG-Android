package br.gov.sp.pcsp.launcher

import android.app.Activity
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.Editable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.appcompat.app.AlertDialog
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

class FfmpegCutActivity : AppCompatActivity() {

    private lateinit var selectedFile: TextView
    private lateinit var cutScroll: ScrollView
    private lateinit var previewFrame: View
    private lateinit var videoPreview: TextureView
    private lateinit var timeline: FfmpegRangeSlider
    private lateinit var audioWaveform: FfmpegWaveformView
    private lateinit var currentTime: TextView
    private lateinit var inputFrom: EditText
    private lateinit var inputTo: EditText
    private lateinit var buttonFromPrev: ImageButton
    private lateinit var buttonFromNext: ImageButton
    private lateinit var buttonToPrev: ImageButton
    private lateinit var buttonToNext: ImageButton
    private lateinit var timeFields: View
    private lateinit var playbackControls: View
    private lateinit var buttonSpeedDown: ImageButton
    private lateinit var buttonPlayPause: ImageButton
    private lateinit var buttonSpeedUp: ImageButton
    private lateinit var playbackSpeedLabel: TextView
    private lateinit var buttonVideoEncoder: TextView
    private lateinit var helpVideoEncoder: TextView
    private lateinit var buttonVideoQuality: TextView
    private lateinit var helpVideoQuality: TextView
    private lateinit var buttonCut: ImageButton
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var outputFileName: TextView
    private lateinit var outputActions: View
    private lateinit var buttonOutputFolder: ImageButton
    private lateinit var buttonOutputShare: ImageButton
    private lateinit var buttonSelectOutputFolder: ImageButton
    private lateinit var arrowInputOutput: View
    private lateinit var buttonSaveToFolder: ImageButton
    private var preSelectedOutputDirUri: Uri? = null
    private var finalOutputDirUri: Uri? = null
    private val tempOutputFiles = mutableListOf<File>()
    private var hasSaved = false
    @Volatile private var isSaving = false

    private val handler = Handler(Looper.getMainLooper())
    private var selectedUri: Uri? = null
    private var selectedName: String = ""
    private var selectedMime: String = ""
    private var lastOutputUri: Uri? = null
    private var lastOutputMime: String = ""
    private var lastOutputName: String = ""
    private var durationMs: Long = 0L
    private var syncingFields = false
    private var previewPlayer: MediaPlayer? = null
    private var audioPlayer: MediaPlayer? = null
    private var playWhenSeekCompletes = false
    private var hasPreviewPlaybackStarted = false
    private var playbackSpeed = 1f
    private var isProcessing = false
    private var availableVideoEncoders: List<FfmpegVideoEncoder> = emptyList()
    private var selectedVideoEncoder: FfmpegVideoEncoder? = null
    private var selectedVideoQuality = FfmpegVideoQuality.default
    private var selectedAudioQuality = FfmpegAudioQuality.default
    @Volatile private var currentSessionId: Long? = null
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
            selectedUri?.let { preparePreview(it) }
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

    private val speedSteps = floatArrayOf(0.25f, 0.5f, 1f, 2f, 4f)

    private val progressTicker = object : Runnable {
        override fun run() {
            if (playbackControls.visibility == View.VISIBLE && isPreviewPlaying()) {
                val position = currentPreviewPosition()
                val startMs = timeline.getStartMs()
                val endMs = timeline.getEndMs()
                if (position < startMs) {
                    pausePreview()
                    playWhenSeekCompletes = true
                    seekPreview(startMs, forPlaybackStart = true)
                } else if (position >= endMs) {
                    pausePreview()
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
        setContentView(R.layout.activity_ffmpeg_cut)

        cutScroll = findViewById(R.id.cut_scroll)
        selectedFile = findViewById(R.id.selected_file)
        previewFrame = findViewById(R.id.preview_frame)
        videoPreview = findViewById(R.id.video_preview)
        videoPreview.surfaceTextureListener = surfaceListener
        timeline = findViewById(R.id.timeline)
        audioWaveform = findViewById(R.id.audio_waveform)
        currentTime = findViewById(R.id.current_time)
        inputFrom = findViewById(R.id.input_from)
        inputTo = findViewById(R.id.input_to)
        buttonFromPrev = findViewById(R.id.button_from_prev)
        buttonFromNext = findViewById(R.id.button_from_next)
        buttonToPrev = findViewById(R.id.button_to_prev)
        buttonToNext = findViewById(R.id.button_to_next)
        timeFields = findViewById(R.id.time_fields)
        playbackControls = findViewById(R.id.playback_controls)
        buttonSpeedDown = findViewById(R.id.button_speed_down)
        buttonPlayPause = findViewById(R.id.button_play_pause)
        buttonSpeedUp = findViewById(R.id.button_speed_up)
        playbackSpeedLabel = findViewById(R.id.playback_speed_label)
        buttonVideoEncoder = findViewById(R.id.button_video_encoder)
        helpVideoEncoder = findViewById(R.id.help_video_encoder)
        buttonVideoQuality = findViewById(R.id.button_video_quality)
        helpVideoQuality = findViewById(R.id.help_video_quality)
        buttonCut = findViewById(R.id.button_cut)
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
            isTaskRunning = { isProcessing || isSaving },
            cancelTask = { if (isProcessing) cancelCut() }
        )
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { exitHandler() }
        findViewById<View>(R.id.button_select_file).setOnClickListener { openFilePicker() }
        buttonVideoEncoder.setOnClickListener { showVideoEncoderMenu() }
        helpVideoEncoder.setOnClickListener { FfmpegVideoEncoderRegistry.showHelp(this) }
        buttonVideoQuality.setOnClickListener { showVideoQualityMenu() }
        helpVideoQuality.setOnClickListener {
            if (selectedMime.startsWith("audio/")) selectedAudioQuality.showHelp(this)
            else selectedVideoQuality.showHelp(this)
        }
        buttonSpeedDown.setOnClickListener { changePlaybackSpeed(-1) }
        buttonPlayPause.setOnClickListener { togglePreviewPlayback() }
        buttonSpeedUp.setOnClickListener { changePlaybackSpeed(1) }
        buttonCut.setOnClickListener {
            if (isProcessing) cancelCut() else cutSelectedMedia()
        }
        outputFileName.setOnClickListener { openOutputFile() }
        buttonOutputFolder.setOnClickListener { openOutputFolder() }
        buttonOutputShare.setOnClickListener { shareOutputFile() }
        buttonSelectOutputFolder.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            startActivityForResult(intent, REQUEST_CHOOSE_PRE_OUTPUT_DIR)
        }
        buttonSaveToFolder.setOnClickListener {
            if (isSaving) return@setOnClickListener
            val preUri = preSelectedOutputDirUri
            if (preUri != null) {
                saveTempOutputsToUri(preUri)
            } else {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                startActivityForResult(intent, REQUEST_CHOOSE_OUTPUT_DIR)
            }
        }
        
        buttonFromPrev.setOnClickListener { adjustTimelineBound(true, -1) }
        buttonFromNext.setOnClickListener { adjustTimelineBound(true, 1) }
        buttonToPrev.setOnClickListener { adjustTimelineBound(false, -1) }
        buttonToNext.setOnClickListener { adjustTimelineBound(false, 1) }

        detectVideoEncoders()
        setCutEnabled(false)

        timeline.onRangeChanged = { startMs, endMs, fromUser, thumb ->
            if (fromUser) {
                updateTimeFields(startMs, endMs)
                audioWaveform.setRange(startMs, endMs)
                val target = if (thumb == FfmpegRangeSlider.Thumb.END) endMs else startMs
                currentTime.text = formatTime(target)
                seekPreview(target)
            }
        }
        timeline.onPositionChanged = { positionMs, fromUser ->
            currentTime.text = formatTime(positionMs)
            audioWaveform.setCurrent(positionMs)
            if (fromUser) {
                seekPreview(positionMs, updateTimeline = false)
            }
        }
        inputFrom.addTextChangedListener(timeFieldWatcher { value -> timeline.setStart(value) })
        inputTo.addTextChangedListener(timeFieldWatcher { value -> timeline.setEnd(value) })

        buttonFromPrev.setOnClickListener { stepTime(isStart = true, forward = false) }
        buttonFromNext.setOnClickListener { stepTime(isStart = true, forward = true) }
        buttonToPrev.setOnClickListener { stepTime(isStart = false, forward = false) }
        buttonToNext.setOnClickListener { stepTime(isStart = false, forward = true) }
        
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
        if (isPreviewPlaying()) {
            pausePreview()
            playWhenSeekCompletes = false
            setPlaybackButtonPlaying(false)
        }
        super.onPause()
    }

    override fun onDestroy() {
        releasePreviewPlayer()
        releaseAudioPlayer()
        previewSurface?.release()
        previewSurface = null
        super.onDestroy()
    }

    @Deprecated("Deprecated Android callback kept for this legacy XML activity.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK) return
 
        when (requestCode) {
            REQUEST_PICK_MEDIA -> {
                val uri = data?.data ?: return
                try {
                    val canRead = data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0
                    val canWrite = data.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0
                    when {
                        canRead && canWrite -> contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        )
                        canRead -> contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        canWrite -> contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    }
                } catch (_: SecurityException) {}
                loadSelectedMedia(uri)
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

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/*", "video/*"))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_PICK_MEDIA)
    }

    private fun handleIncomingShareIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val uri = sharedUrisFrom(intent).firstOrNull() ?: return
        tryTakeReadPermission(uri, intent.flags)
        loadSelectedMedia(uri)
        status.text = "Arquivo recebido pelo compartilhamento."
    }

    @Suppress("DEPRECATION")
    private fun sharedUrisFrom(intent: Intent): List<Uri> {
        val uris = mutableListOf<Uri>()
        intent.clipData?.let { clip ->
            for (index in 0 until clip.itemCount) {
                clip.getItemAt(index).uri?.let { uris += it }
            }
        }
        intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris += it }
        intent.data?.let { uris += it }
        return uris.distinct()
    }

    private fun tryTakeReadPermission(uri: Uri, flags: Int) {
        try {
            contentResolver.takePersistableUriPermission(uri, flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Throwable) {
        }
    }

    private fun loadSelectedMedia(uri: Uri) {
        selectedUri = uri
        selectedName = queryDisplayName(uri) ?: "arquivo"
        var mime = detectMediaMime(uri)
        if (mime.isEmpty()) {
            val extension = selectedName.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
            mime = when (extension) {
                "mp4", "mkv", "mov", "avi", "webm", "3gp", "m4v" -> "video/$extension"
                "mp3", "wav", "m4a", "aac", "ogg", "opus", "flac" -> "audio/$extension"
                else -> "application/octet-stream"
            }
        }
        selectedMime = mime
        durationMs = readDuration(uri)

        selectedFile.text = selectedName
        timeline.isEnabled = durationMs > 0L
        timeline.setRange(durationMs, 0L, durationMs)
        timeline.setCurrent(0L)
        currentTime.text = formatTime(0L)
        updateTimeFields(0L, durationMs)
        status.text = ""
        status.movementMethod = null
        clearOutputResult()
        setCutEnabled(true)

        releaseAudioPlayer()
        playbackSpeed = 1f
        hasPreviewPlaybackStarted = false
        updateSpeedButton()
        buttonSelectOutputFolder.visibility = View.VISIBLE
        arrowInputOutput.visibility = View.VISIBLE
        if (preSelectedOutputDirUri != null) {
            buttonSelectOutputFolder.setBackgroundResource(R.drawable.ffmpeg_outline_green_button_bg)
        } else {
            buttonSelectOutputFolder.setBackgroundResource(R.drawable.ffmpeg_outline_button_bg)
        }
        playbackControls.visibility = View.VISIBLE
        setPlaybackButtonPlaying(false)
        showEditingControls(true)

        if (selectedMime.startsWith("video/")) {
            buttonVideoEncoder.visibility = View.VISIBLE
            setPreviewFrameHeight(450)
            videoPreview.visibility = View.VISIBLE
            audioWaveform.configure(selectedName, durationMs)
            audioWaveform.setRange(0L, durationMs)
            playbackSpeedLabel.visibility = View.VISIBLE
            playWhenSeekCompletes = false
            if (videoPreview.isAvailable) {
                previewSurface = Surface(videoPreview.surfaceTexture)
                preparePreview(uri)
            }
        } else if (selectedMime.startsWith("audio/")) {
            buttonVideoEncoder.visibility = View.GONE
            setPreviewFrameHeight(88)
            videoPreview.visibility = View.GONE
            playbackSpeedLabel.visibility = View.VISIBLE
            releasePreviewPlayer()
            playWhenSeekCompletes = false
            prepareAudioPreview(uri)
        } else {
            buttonVideoEncoder.visibility = View.GONE
            showEditingControls(false)
            playbackControls.visibility = View.GONE
            audioWaveform.clear()
            playbackSpeedLabel.visibility = View.GONE
            videoPreview.visibility = View.GONE
            releasePreviewPlayer()
            playWhenSeekCompletes = false
        }
        
    }

    private fun detectMediaMime(uri: Uri): String {
        val extractor = android.media.MediaExtractor()
        return try {
            extractor.setDataSource(this, uri, null)
            val trackMimes = (0 until extractor.trackCount).mapNotNull { index ->
                extractor.getTrackFormat(index).getString(android.media.MediaFormat.KEY_MIME)
            }
            trackMimes.firstOrNull { it.startsWith("video/") }
                ?: trackMimes.firstOrNull { it.startsWith("audio/") }
                ?: contentResolver.getType(uri).orEmpty()
        } catch (_: Throwable) {
            contentResolver.getType(uri).orEmpty()
        } finally {
            extractor.release()
        }
    }

    private fun cutSelectedMedia(fullReencodeConfirmed: Boolean = false) {
        val uri = selectedUri ?: return
        if (selectedMime.startsWith("video/")) {
            when (val probe = videoTrackProbe(uri)) {
                is FfmpegTrackProbeResult.Failed -> {
                    status.text = "Não foi possível analisar as faixas de vídeo: ${probe.message}"
                    return
                }
                is FfmpegTrackProbeResult.Count -> if (probe.value != 1) {
                    status.text = if (probe.value == 0) "O arquivo não possui vídeo."
                    else "O arquivo possui ${probe.value} faixas de vídeo. O corte foi bloqueado para não descartar conteúdo."
                    return
                }
            }
        }
        var startMs = parseTime(inputFrom.text.toString())
        var endMs = parseTime(inputTo.text.toString())

        if (startMs == null || endMs == null || endMs <= startMs) {
            status.text = "Confira os tempos de início e fim."
            return
        }
        val jobMime = selectedMime
        val jobEncoder = selectedVideoEncoder
        val jobQuality = selectedVideoQuality
        val jobAudioQuality = selectedAudioQuality
        val jobStartMs = startMs
        val jobEndMs = endMs

        if (jobMime.startsWith("video/") && jobEncoder == null) {
            status.text = "Nenhum encoder de vídeo compatível está disponível."
            return
        }
        if (jobMime.startsWith("video/") && !fullReencodeConfirmed && jobEncoder != null) {
            val sourceCodec = detectVideoCodecFamily(uri)
            if (sourceCodec !in setOf("h264", "hevc")) {
                AlertDialog.Builder(this)
                    .setTitle("Este codec exige recodificação completa")
                    .setMessage("A origem usa ${sourceCodec?.uppercase(Locale.ROOT) ?: "um codec não identificado"}. O corte será preciso, mas não poderá copiar o trecho central e levará mais tempo.")
                    .setPositiveButton("Continuar") { _, _ -> cutSelectedMedia(true) }
                    .setNegativeButton("Cancelar", null)
                    .show()
                return
            }
            if (jobEncoder.codecFamily != sourceCodec) {
                val compatible = availableVideoEncoders.firstOrNull { it.codecFamily == sourceCodec }
                val dialog = AlertDialog.Builder(this)
                    .setTitle("O encoder escolhido exige recodificação completa")
                    .setMessage("A origem usa ${sourceCodec.orEmpty().uppercase(Locale.ROOT)}, mas o encoder selecionado é ${jobEncoder.shortName}. Você pode manter sua escolha ou usar um encoder compatível para copiar o trecho central sem perdas.")
                    .setPositiveButton("Manter ${jobEncoder.shortName}") { _, _ -> cutSelectedMedia(true) }
                    .setNegativeButton("Cancelar", null)
                if (compatible != null) {
                    dialog.setNeutralButton("Usar ${compatible.shortName}") { _, _ ->
                        selectedVideoEncoder = compatible
                        updateVideoEncoderButton()
                        cutSelectedMedia(false)
                    }
                }
                dialog.show()
                return
            }
        }

        val producedMime = currentOutputMime()
        clearOutputResult()
        setProcessing(true)
        val processingStartMs = SystemClock.elapsedRealtime()
        Thread {
            var inputFile: File? = null
            var tempOutput: File? = null
            var keepOutput = false
            try {
                val currentInputFile = copyUriToCache(uri, selectedName)
                inputFile = currentInputFile
                val outputName = buildOutputName(selectedName)
                val currentTempOutput = File(cacheDir, "${System.currentTimeMillis()}_$outputName")
                tempOutput = currentTempOutput
                val tracker = FfmpegTaskTracker(status, listOf("Preparando arquivo"))
                tracker.completeCurrentTask()
                val execution = if (jobMime.startsWith("video/")) {
                    executeHybridVideoCut(
                        currentInputFile,
                        currentTempOutput,
                        jobStartMs,
                        jobEndMs,
                        tracker,
                        jobEncoder!!,
                        jobQuality,
                        allowFullReencode = fullReencodeConfirmed
                    )
                } else {
                    val audioWillCopy = FfmpegMediaPolicies.audioSelectionCanUseStreamCopy(
                        jobStartMs,
                        jobEndMs,
                        readDuration(currentInputFile)
                    )
                    val audioTask = if (audioWillCopy) "Copiando áudio selecionado sem recodificar"
                    else "Recodificando áudio selecionado em ${jobAudioQuality.label.lowercase(Locale.ROOT)}"
                    tracker.appendTasks(listOf("Preparando intervalo de áudio", audioTask))
                    tracker.completeCurrentTask()
                    if (!audioWillCopy) tracker.setTaskEncoder(1, preciseAudioEncoderName(selectedName, jobAudioQuality))
                    tracker.startCurrentTask()
                    val session = executeFfmpegWithProgress(
                        buildPreciseFfmpegArguments(
                            currentInputFile,
                            currentTempOutput,
                            jobStartMs,
                            jobEndMs,
                            jobMime,
                            jobEncoder,
                            jobQuality,
                            jobAudioQuality
                        ),
                        jobEndMs - jobStartMs,
                        tracker
                    )
                    if (ReturnCode.isSuccess(session.returnCode)) tracker.completeCurrentTask()
                    CutExecutionResult(
                        success = ReturnCode.isSuccess(session.returnCode),
                        cancelled = ReturnCode.isCancel(session.returnCode),
                        failureMessage = session.allLogsAsString.orEmpty().lines().takeLast(3).joinToString("\n")
                    )
                }
                val success = execution.success
                if (success && currentTempOutput.exists() && currentTempOutput.length() > 0L) {
                    keepOutput = true
                }
                runOnUiThread {
                    execution.reencodeRequiredReason?.let { reason ->
                        setProcessing(false)
                        showFullReencodeConfirmation(reason)
                        return@runOnUiThread
                    }
                    if (execution.cancelled) {
                        setProcessing(false)
                        tracker.fail("Operação cancelada.")
                        return@runOnUiThread
                    }
                    if (!success) {
                        setProcessing(false)
                        tracker.fail("Falha no FFmpeg:\n${execution.failureMessage}")
                        return@runOnUiThread
                    }
 
                    val durationMs = endMs - startMs
                    val elapsedMs = SystemClock.elapsedRealtime() - processingStartMs
                    val elapsedSeconds = (elapsedMs / 1000.0).coerceAtLeast(0.001)
                    val mediaSeconds = durationMs / 1000.0
                    val efficiency = String.format(Locale.US, "%.2fx", mediaSeconds / elapsedSeconds)
                    tracker.success("Tempo de processamento: ${formatTime(elapsedMs)}\nMídia processada: ${formatTime(durationMs)}\nEficiência: $efficiency")
                    setProcessing(false)
                    tempOutputFiles.clear()
                    tempOutputFiles.add(currentTempOutput)
                    hasSaved = false
                    lastOutputMime = producedMime
 
                    outputFileName.text = outputName
                    outputFileName.visibility = View.VISIBLE
 
                    outputActions.visibility = View.VISIBLE
                    buttonSaveToFolder.visibility = View.VISIBLE
                    buttonOutputFolder.visibility = View.GONE
                    buttonOutputShare.visibility = View.GONE
 
                    cutScroll.post {
                        cutScroll.smoothScrollTo(0, outputActions.bottom)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setProcessing(false)
                    status.text = "Erro ao cortar: ${e.message ?: "falha inesperada"}"
                }
            } finally {
                inputFile?.delete()
                if (!keepOutput) tempOutput?.delete()
            }
        }.start()
    }

    private fun videoTrackProbe(uri: Uri): FfmpegTrackProbeResult {
        val extractor = android.media.MediaExtractor()
        return try {
            extractor.setDataSource(this, uri, null)
            FfmpegTrackProbeResult.Count((0 until extractor.trackCount).count { index ->
                extractor.getTrackFormat(index).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("video/") == true
            })
        } catch (error: Throwable) {
            FfmpegTrackProbeResult.Failed(error.message ?: "falha do MediaExtractor")
        } finally {
            extractor.release()
        }
    }

    private fun showFullReencodeConfirmation(reason: String) {
        AlertDialog.Builder(this)
            .setTitle("O corte exige recodificação completa")
            .setMessage("$reason\n\nO corte continuará preciso, mas levará mais tempo e poderá alterar a qualidade. Deseja continuar?")
            .setPositiveButton("Recodificar e continuar") { _, _ -> cutSelectedMedia(fullReencodeConfirmed = true) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun copyUriToCache(uri: Uri, displayName: String): File {
        val extension = displayName.substringAfterLast('.', "tmp")
        val inputFile = File(cacheDir, "ffmpeg_input_${System.currentTimeMillis()}.$extension")
        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(inputFile).use { output -> input.copyTo(output) }
        }
        return inputFile
    }

    private fun buildPreciseFfmpegArguments(
        inputFile: File,
        outputFile: File,
        startMs: Long,
        endMs: Long,
        mime: String = selectedMime,
        encoder: FfmpegVideoEncoder? = selectedVideoEncoder,
        quality: FfmpegVideoQuality = selectedVideoQuality,
        audioQuality: FfmpegAudioQuality = selectedAudioQuality
    ): Array<String> {
        val duration = (endMs - startMs) / 1000.0
        val durationText = String.format(Locale.US, "%.3f", duration)
        if (!mime.startsWith("video/")) {
            val inputDurationMs = readDuration(inputFile)
            val canCopySelection = FfmpegMediaPolicies.audioSelectionCanUseStreamCopy(startMs, endMs, inputDurationMs)
            val encoderArguments = if (canCopySelection) {
                listOf("-c:a", "copy")
            } else {
                preciseAudioEncoderArguments(selectedName, audioQuality.bitrate, inputFile)
            }
            return FfmpegMediaPolicies.cutAudioCommandArguments(
                inputPath = inputFile.absolutePath,
                outputPath = outputFile.absolutePath,
                start = formatSeconds(startMs),
                duration = durationText,
                encoderArguments = encoderArguments
            )
        }

        val rotationDegrees = detectMetadataRotation(inputFile)
        val args = mutableListOf("-y")
        args += "-noautorotate"
        args.addAll(rotationInputArguments(rotationDegrees))
        args += listOf(
            "-ss", formatSeconds(startMs),
            "-i", inputFile.absolutePath,
            "-t", durationText
        )
        val streamBitrates = detectStreamBitrates(inputFile)
        val enc = encoder ?: error("Encoder de vídeo indisponível")
        args.addAll(FfmpegMediaPolicies.cutMappedCopyArguments())
        args.addAll(videoEncodingArguments(enc, streamBitrates.videoBitrateForEncoding(), quality))
        args.addAll(listOf("-avoid_negative_ts", "make_zero"))
        args.add(outputFile.absolutePath)
        return args.toTypedArray()
    }

    private fun executeHybridVideoCut(
        inputFile: File,
        outputFile: File,
        startMs: Long,
        endMs: Long,
        tracker: FfmpegTaskTracker,
        encoder: FfmpegVideoEncoder? = selectedVideoEncoder,
        quality: FfmpegVideoQuality = selectedVideoQuality,
        allowFullReencode: Boolean = false
    ): CutExecutionResult {
        val actualEncoder = encoder ?: return CutExecutionResult(false, false, "Encoder de vídeo indisponível")
        tracker.appendTasks(listOf("Analisando codec e orientação"))
        val sourceCodec = detectVideoCodecFamily(inputFile)
        val rotationDegrees = detectMetadataRotation(inputFile)
        tracker.completeCurrentTask()
        FfmpegMediaPolicies.hybridCutFallbackReason(sourceCodec, actualEncoder.codecFamily)?.let { reason ->
            if (!allowFullReencode) {
                return CutExecutionResult(
                    success = false,
                    cancelled = false,
                    failureMessage = "",
                    reencodeRequiredReason = reason
                )
            }
            tracker.appendTasks(listOf("Caminho rápido indisponível: ${reason.removeSuffix(".").lowercase(Locale.ROOT)}"))
            tracker.completeCurrentTask()
            return executeFullPrecisionFallback(inputFile, outputFile, startMs, endMs, rotationDegrees, tracker, actualEncoder, quality)
        }

        tracker.appendTasks(listOf("Localizando keyframes no intervalo"))
        val keyframes = extractKeyframesFromFile(inputFile)
        val startUs = startMs * 1000L
        val endUs = endMs * 1000L
        val startKeyframe = keyframes.firstOrNull { it >= startUs }
        val endKeyframe = keyframes.lastOrNull { it <= endUs }
        tracker.completeCurrentTask()
        val hasInternalKeyframes = startKeyframe != null && endKeyframe != null && startKeyframe < endKeyframe
        FfmpegMediaPolicies.hybridCutFallbackReason(
            sourceCodec,
            actualEncoder.codecFamily,
            hasInternalKeyframes
        )?.let { reason ->
            if (!allowFullReencode) {
                return CutExecutionResult(
                    success = false,
                    cancelled = false,
                    failureMessage = "",
                    reencodeRequiredReason = reason
                )
            }
            tracker.appendTasks(listOf("Caminho rápido indisponível: ${reason.removeSuffix(".").lowercase(Locale.ROOT)}"))
            tracker.completeCurrentTask()
            return executeFullPrecisionFallback(inputFile, outputFile, startMs, endMs, rotationDegrees, tracker, actualEncoder, quality)
        }
        val internalStartKeyframe = checkNotNull(startKeyframe)
        val internalEndKeyframe = checkNotNull(endKeyframe)

        val bitrates = detectStreamBitrates(inputFile)
        val workDir = File(cacheDir, "cut_hybrid_${System.currentTimeMillis()}").apply { mkdirs() }
        val pieces = mutableListOf<File>()
        try {
            val tasks = mutableListOf<String>()
            if (internalStartKeyframe > startUs) tasks += "Recodificando borda inicial"
            tasks += "Copiando vídeo do trecho central sem reencodar"
            if (internalEndKeyframe < endUs) tasks += "Recodificando borda final"
            tasks += "Juntando trechos e preservando orientação"
            val taskOffset = tracker.taskCount()
            tracker.appendTasks(tasks)
            tasks.forEachIndexed { index, task ->
                if (task.startsWith("Recodificando")) {
                    tracker.setTaskEncoder(taskOffset + index, actualEncoder.shortName)
                }
            }

            fun runPiece(arguments: Array<String>, expectedMs: Long, output: File): CutExecutionResult? {
                var lastFailure = ""
                repeat(HYBRID_CUT_MAX_ATTEMPTS) { attempt ->
                    if (attempt > 0) {
                        output.delete()
                        Thread.sleep(180L)
                    }
                    tracker.startCurrentTask()
                    val session = executeFfmpegWithProgress(arguments, expectedMs, tracker)
                    if (ReturnCode.isCancel(session.returnCode)) {
                        return CutExecutionResult(false, true, "")
                    }
                    if (ReturnCode.isSuccess(session.returnCode) && output.exists() && output.length() > 0L) {
                        tracker.completeCurrentTask()
                        pieces += output
                        return null
                    }
                    lastFailure = session.allLogsAsString.orEmpty().lines().takeLast(3).joinToString("\n")
                }
                return CutExecutionResult(false, false, lastFailure)
            }

            if (internalStartKeyframe > startUs) {
                val startPiece = File(workDir, "start.mkv")
                runPiece(
                    buildHybridEdgeArguments(inputFile, startPiece, startUs, internalStartKeyframe, bitrates, encoder = actualEncoder, quality = quality),
                    (internalStartKeyframe - startUs) / 1000L,
                    startPiece
                )?.let { return it }
            }

            val bodyPiece = File(workDir, "body.mkv")
            runPiece(
                buildHybridBodyArguments(inputFile, bodyPiece, internalStartKeyframe, internalEndKeyframe),
                (internalEndKeyframe - internalStartKeyframe) / 1000L,
                bodyPiece
            )?.let { return it }

            if (internalEndKeyframe < endUs) {
                val endPiece = File(workDir, "end.mkv")
                runPiece(
                    buildHybridEdgeArguments(inputFile, endPiece, internalEndKeyframe, endUs, bitrates, encoder = actualEncoder, quality = quality),
                    (endUs - internalEndKeyframe) / 1000L,
                    endPiece
                )?.let { return it }
            }

            val concatList = File(workDir, "parts.txt")
            concatList.writeText(pieces.joinToString("\n") { "file '${it.absolutePath.replace("\\", "/")}'" }, Charsets.UTF_8)
            val concatArguments = mutableListOf(
                "-y", "-fflags", "+genpts", "-f", "concat", "-safe", "0"
            )
            concatArguments += listOf(
                "-display_rotation:v:0", rotationDegrees.toString(), "-i", concatList.absolutePath,
                "-map", "0", "-map_metadata", "0", "-map_chapters", "0", "-c", "copy",
                "-avoid_negative_ts", "make_zero"
            )
            concatArguments += outputFile.absolutePath
            val concatSession = executeFfmpegWithProgress(
                concatArguments.toTypedArray(),
                endMs - startMs,
                tracker
            )
            if (ReturnCode.isSuccess(concatSession.returnCode) && outputFile.exists() && outputFile.length() > 0L) {
                tracker.completeCurrentTask()
                return CutExecutionResult(true, false, "")
            }
            return CutExecutionResult(
                false,
                ReturnCode.isCancel(concatSession.returnCode),
                concatSession.allLogsAsString.orEmpty().lines().takeLast(3).joinToString("\n")
            )
        } finally {
            workDir.deleteRecursively()
        }
    }

    private fun executeFullPrecisionFallback(
        inputFile: File,
        outputFile: File,
        startMs: Long,
        endMs: Long,
        rotationDegrees: Int,
        tracker: FfmpegTaskTracker,
        encoder: FfmpegVideoEncoder = selectedVideoEncoder ?: error("Encoder de vídeo indisponível"),
        quality: FfmpegVideoQuality = selectedVideoQuality
    ): CutExecutionResult {
        val encoderName = encoder.shortName
        val orientationTask = if (rotationDegrees == 0) {
            "Mantendo orientação original"
        } else {
            "Preservando rotação de ${rotationDegrees}° nos metadados"
        }
        tracker.appendTasks(
            listOf(
                "Preparando intervalo sem aplicar a rotação",
                "Recodificando somente o intervalo solicitado",
                orientationTask
            )
        )
        tracker.setTaskEncoder(tracker.taskCount() - 2, encoderName)
        tracker.completeCurrentTask()
        tracker.startCurrentTask()
        val session = executeFfmpegWithProgress(
            buildPreciseFfmpegArguments(inputFile, outputFile, startMs, endMs, selectedMime, encoder, quality),
            endMs - startMs,
            tracker
        )
        if (ReturnCode.isSuccess(session.returnCode)) {
            tracker.completeCurrentTask()
            tracker.completeCurrentTask()
        }
        return CutExecutionResult(
            success = ReturnCode.isSuccess(session.returnCode),
            cancelled = ReturnCode.isCancel(session.returnCode),
            failureMessage = session.allLogsAsString.orEmpty().lines().takeLast(3).joinToString("\n")
        )
    }

    private fun buildHybridEdgeArguments(
        inputFile: File,
        outputFile: File,
        startUs: Long,
        endUs: Long,
        bitrates: StreamBitrates,
        encoder: FfmpegVideoEncoder = selectedVideoEncoder ?: error("Encoder de vídeo indisponível"),
        quality: FfmpegVideoQuality = selectedVideoQuality
    ): Array<String> {
        val duration = (endUs - startUs) / 1_000_000.0
        val args = mutableListOf("-y", "-ss", formatMicroseconds(startUs), "-noautorotate", "-i", inputFile.absolutePath)
        args += listOf("-t", String.format(Locale.US, "%.6f", duration))
        args += FfmpegMediaPolicies.cutMappedCopyArguments()
        args += videoEncodingArguments(encoder, bitrates.videoBitrateForEncoding(), quality)
        args += listOf(
            "-avoid_negative_ts", "make_zero", "-f", "matroska", outputFile.absolutePath
        )
        return args.toTypedArray()
    }

    private fun buildHybridBodyArguments(
        inputFile: File,
        outputFile: File,
        startUs: Long,
        endUs: Long
    ): Array<String> {
        return FfmpegMediaPolicies.hybridCopyBodyArguments(inputFile.absolutePath, outputFile.absolutePath, startUs, endUs)
    }

    private fun extractKeyframesFromFile(inputFile: File): List<Long> {
        val extractor = android.media.MediaExtractor()
        val keyframes = mutableListOf<Long>()
        try {
            extractor.setDataSource(inputFile.absolutePath)
            val videoTrack = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: return emptyList()
            extractor.selectTrack(videoTrack)
            while (true) {
                val sampleTime = extractor.sampleTime
                if (sampleTime < 0L) break
                if ((extractor.sampleFlags and android.media.MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                    keyframes += sampleTime
                }
                extractor.advance()
            }
        } finally {
            extractor.release()
        }
        return keyframes.distinct().sorted()
    }

    private fun formatMicroseconds(valueUs: Long): String =
        String.format(Locale.US, "%.6f", valueUs / 1_000_000.0)

    private fun detectVideoCodecFamily(inputFile: File): String? {
        val extractor = android.media.MediaExtractor()
        return try {
            extractor.setDataSource(inputFile.absolutePath)
            val mime = (0 until extractor.trackCount).asSequence().mapNotNull { index ->
                extractor.getTrackFormat(index).getString(android.media.MediaFormat.KEY_MIME)
            }.firstOrNull { it.startsWith("video/") }
            when (mime) {
                "video/avc" -> "h264"
                "video/hevc" -> "hevc"
                else -> mime?.substringAfter('/')
            }
        } catch (_: Throwable) {
            null
        } finally {
            extractor.release()
        }
    }

    private fun detectVideoCodecFamily(uri: Uri): String? {
        val extractor = android.media.MediaExtractor()
        return try {
            extractor.setDataSource(this, uri, null)
            val mime = (0 until extractor.trackCount).asSequence().mapNotNull { index ->
                extractor.getTrackFormat(index).getString(android.media.MediaFormat.KEY_MIME)
            }.firstOrNull { it.startsWith("video/") }
            when (mime) {
                "video/avc" -> "h264"
                "video/hevc" -> "hevc"
                else -> mime?.substringAfter('/')
            }
        } catch (_: Throwable) {
            null
        } finally {
            extractor.release()
        }
    }

    private fun detectMetadataRotation(inputFile: File): Int {
        return try {
            val session = FFmpegKit.executeWithArguments(arrayOf("-hide_banner", "-i", inputFile.absolutePath))
            val logs = session.allLogsAsString.orEmpty()
            val displayMatrix = Regex(
                """rotation of\s+(-?\d+(?:\.\d+)?)\s+degrees""",
                RegexOption.IGNORE_CASE
            ).find(logs)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
            val rotateTag = Regex("""rotate\s*:\s*(-?\d+)""", RegexOption.IGNORE_CASE)
                .find(logs)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
            normalizeRotationDegrees(displayMatrix ?: rotateTag ?: 0.0)
        } catch (e: Throwable) {
            Log.w(TAG, "Could not detect rotation metadata", e)
            0
        }
    }

    private fun normalizeRotationDegrees(value: Double): Int {
        var normalized = Math.round(value).toInt() % 360
        if (normalized > 180) normalized -= 360
        if (normalized <= -180) normalized += 360
        return normalized
    }

    private fun rotationInputArguments(rotationDegrees: Int): List<String> {
        return if (rotationDegrees == 0) emptyList()
        else listOf("-display_rotation:v:0", rotationDegrees.toString())
    }

    private fun preciseAudioEncoderArguments(name: String, bitrate: String, inputFile: File? = null): List<String> {
        return FfmpegMediaPolicies.cutAudioEncoderArguments(
            name.substringAfterLast('.', ""),
            bitrate,
            inputFile?.let(::detectPcmEncoder) ?: "pcm_s16le"
        )
    }

    private fun detectPcmEncoder(inputFile: File): String {
        val extractor = android.media.MediaExtractor()
        return try {
            extractor.setDataSource(inputFile.absolutePath)
            val format = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }.firstOrNull {
                it.getString(android.media.MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return "pcm_s16le"
            when (runCatching { format.getInteger(android.media.MediaFormat.KEY_PCM_ENCODING) }.getOrNull()) {
                android.media.AudioFormat.ENCODING_PCM_8BIT -> "pcm_u8"
                android.media.AudioFormat.ENCODING_PCM_FLOAT -> "pcm_f32le"
                android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED -> "pcm_s24le"
                android.media.AudioFormat.ENCODING_PCM_32BIT -> "pcm_s32le"
                else -> "pcm_s16le"
            }
        } catch (_: Throwable) {
            "pcm_s16le"
        } finally {
            extractor.release()
        }
    }

    private fun preciseAudioEncoderName(name: String, quality: FfmpegAudioQuality = selectedAudioQuality): String {
        return preciseAudioEncoderArguments(name, quality.bitrate).getOrNull(1) ?: "áudio"
    }

    private fun detectStreamBitrates(inputFile: File): StreamBitrates {
        val extractor = android.media.MediaExtractor()
        return try {
            extractor.setDataSource(inputFile.absolutePath)
            var video: String? = null
            var audio: String? = null
            var width: Int? = null
            var height: Int? = null
            var frameRate: Double? = null
            var codecFamily: String? = null
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(android.media.MediaFormat.KEY_MIME).orEmpty()
                val bitrate = runCatching { format.getInteger(android.media.MediaFormat.KEY_BIT_RATE) }.getOrNull()
                    ?.takeIf { it > 0 }?.let { "${(it / 1000).coerceAtLeast(1)}k" }
                if (mime.startsWith("video/") && width == null) {
                    video = bitrate
                    width = runCatching { format.getInteger(android.media.MediaFormat.KEY_WIDTH) }.getOrNull()
                    height = runCatching { format.getInteger(android.media.MediaFormat.KEY_HEIGHT) }.getOrNull()
                    frameRate = runCatching { format.getFloat(android.media.MediaFormat.KEY_FRAME_RATE).toDouble() }.getOrNull()
                        ?: runCatching { format.getInteger(android.media.MediaFormat.KEY_FRAME_RATE).toDouble() }.getOrNull()
                    codecFamily = when (mime) {
                        "video/hevc" -> "hevc"
                        "video/avc" -> "h264"
                        else -> mime.substringAfter('/', "h264")
                    }
                }
                if (mime.startsWith("audio/") && audio == null) audio = bitrate
            }
            StreamBitrates(
                video = video,
                audio = audio,
                width = width,
                height = height,
                frameRate = frameRate,
                codecFamily = codecFamily
            )
        } catch (e: Throwable) {
            Log.w(TAG, "Could not detect stream bitrates", e)
            StreamBitrates()
        } finally {
            extractor.release()
        }
    }

    private fun saveTempOutputsToUri(treeUri: Uri) {
        val destDir = DocumentFile.fromTreeUri(this, treeUri)
        if (destDir == null || !destDir.isDirectory) {
            status.text = "Erro: pasta de destino inválida."
            return
        }

        val filesToSave = tempOutputFiles.filter { it.exists() && it.length() > 0L }
        if (filesToSave.isEmpty()) {
            status.text = "Não encontrei o arquivo processado para salvar."
            return
        }

        isSaving = true
        buttonSaveToFolder.isEnabled = false
        buttonSaveToFolder.alpha = 0.45f
        status.text = "Salvando arquivo grande... 0%"

        Thread {
            var savedCount = 0
            var lastSavedUri: Uri? = null
            var lastSavedName = ""
            var failure: Throwable? = null

            for (tempFile in filesToSave) {
                val outputName = pendingOutputName(tempFile)
                var document: DocumentFile? = null
                try {
                    document = destDir.createFile(lastOutputMime.ifBlank { currentOutputMime() }, outputName)
                        ?: throw IllegalStateException("não consegui criar o arquivo de destino")
                    val copied = copyLargeFileToDocument(tempFile, document, outputName)
                    val expected = tempFile.length()
                    if (copied != expected) {
                        throw IllegalStateException("cópia incompleta: ${formatBytes(copied)} de ${formatBytes(expected)}")
                    }
                    savedCount++
                    lastSavedUri = document.uri
                    lastSavedName = document.name ?: outputName
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to save file $outputName to selected folder", e)
                    try {
                        document?.delete()
                    } catch (_: Throwable) {
                    }
                    failure = e
                    break
                }
            }

            runOnUiThread {
                isSaving = false
                buttonSaveToFolder.isEnabled = true
                buttonSaveToFolder.alpha = 1f
                if (failure == null && savedCount == filesToSave.size) {
                    hasSaved = true
                    finalOutputDirUri = treeUri
                    lastOutputUri = lastSavedUri
                    lastOutputMime = lastOutputMime.ifBlank { currentOutputMime() }
                    lastOutputName = lastSavedName

                    val folderName = destDir.name ?: "Pasta selecionada"
                    status.text = "Arquivo(s) salvo(s) na pasta \"$folderName\""

                    buttonSaveToFolder.visibility = View.GONE
                    buttonOutputFolder.visibility = View.VISIBLE
                    buttonOutputShare.visibility = View.VISIBLE
                    filesToSave.forEach { it.delete() }
                    tempOutputFiles.removeAll(filesToSave.toSet())
                } else {
                    status.text = "Erro ao salvar. Arquivo parcial removido. ${failure?.message.orEmpty()}".trim()
                }
            }
        }.start()
    }

    private fun pendingOutputName(tempFile: File): String {
        val visibleName = outputFileName.text?.toString()?.trim().orEmpty()
        if (tempOutputFiles.size == 1 && visibleName.isNotBlank()) return visibleName
        return tempFile.name.substringAfter('_')
    }

    private fun copyLargeFileToDocument(source: File, document: DocumentFile, outputName: String): Long {
        val total = source.length().coerceAtLeast(1L)
        var copied = 0L
        var lastUiUpdate = 0L
        val buffer = ByteArray(1024 * 1024)
        val output = contentResolver.openOutputStream(document.uri, "w")
            ?: throw IllegalStateException("não consegui abrir o arquivo de destino")
        output.use { out ->
            source.inputStream().use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    copied += read.toLong()
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastUiUpdate >= 500L || copied == total) {
                        lastUiUpdate = now
                        val percent = ((copied * 100L) / total).coerceIn(0L, 100L)
                        runOnUiThread {
                            status.text = "Salvando \"$outputName\"... $percent% (${formatBytes(copied)} / ${formatBytes(total)})"
                        }
                    }
                }
            }
            out.flush()
            if (out is FileOutputStream) {
                try {
                    out.fd.sync()
                } catch (_: Throwable) {
                }
            }
        }
        return copied
    }

    private fun formatBytes(bytes: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB")
        var value = bytes.toDouble()
        var unitIndex = 0
        while (value >= 1024.0 && unitIndex < units.lastIndex) {
            value /= 1024.0
            unitIndex++
        }
        return if (unitIndex == 0) {
            "${bytes} B"
        } else {
            String.format(Locale.US, "%.1f %s", value, units[unitIndex])
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
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(this, uri)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            retriever.release()
            duration ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    private fun readDuration(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Throwable) {
            0L
        } finally {
            retriever.release()
        }
    }

    private fun buildOutputName(name: String): String {
        val dotIndex = name.lastIndexOf('.')
        if (dotIndex <= 0) return if (selectedMime.startsWith("video/")) "${name}_cortado.mkv" else "${name}_cortado"
        val extension = if (selectedMime.startsWith("video/")) ".mkv" else name.substring(dotIndex)
        return "${name.substring(0, dotIndex)}_cortado$extension"
    }

    private fun currentOutputMime(): String {
        return if (selectedMime.startsWith("video/")) {
            "video/x-matroska"
        } else {
            selectedMime.ifBlank { "application/octet-stream" }
        }
    }

    private fun setProcessing(processing: Boolean) {
        isProcessing = processing
        progress.visibility = if (processing) View.VISIBLE else View.GONE
        buttonCut.isEnabled = true
        buttonCut.isClickable = true
        buttonCut.isFocusable = true
        buttonCut.alpha = 1f
        if (processing) {
            status.movementMethod = null
            status.text = ""
            clearOutputResult()
            buttonCut.setImageResource(R.drawable.ic_ffmpeg_cancel_red)
            buttonCut.setBackgroundResource(R.drawable.ffmpeg_outline_red_button_bg)
            buttonCut.contentDescription = "Cancelar"
        } else {
            currentSessionId = null
            buttonCut.setImageResource(R.drawable.ic_ffmpeg_scissors)
            buttonCut.setBackgroundResource(R.drawable.ffmpeg_outline_green_button_bg)
            buttonCut.contentDescription = "Cortar"
            setCutEnabled(selectedUri != null)
        }
        buttonVideoEncoder.isEnabled = !processing
        buttonVideoQuality.isEnabled = !processing
        timeline.isEnabled = !processing
        buttonPlayPause.isEnabled = !processing
        buttonSpeedDown.isEnabled = !processing
        buttonSpeedUp.isEnabled = !processing
        inputFrom.isEnabled = !processing
        inputTo.isEnabled = !processing
        buttonFromPrev.isEnabled = !processing
        buttonFromNext.isEnabled = !processing
        buttonToPrev.isEnabled = !processing
        buttonToNext.isEnabled = !processing
        buttonSelectOutputFolder.isEnabled = !processing
        findViewById<View>(R.id.button_select_file).isEnabled = !processing
        updateVideoEncoderButton()
    }

    private fun setCutEnabled(enabled: Boolean) {
        if (isProcessing) return
        buttonCut.alpha = if (enabled) 1f else 0.45f
        buttonCut.isClickable = enabled
        buttonCut.isFocusable = enabled
    }

    private fun cancelCut() {
        status.text = "Cancelando..."
        currentSessionId?.let { FFmpegKit.cancel(it) } ?: FFmpegKit.cancel()
    }

    private fun executeFfmpegWithProgress(arguments: Array<String>, expectedDurationMs: Long, tracker: FfmpegTaskTracker): FFmpegSession {
        FfmpegCommandPresenter.show(status, arguments.asIterable())
        Log.i(TAG, "FFmpeg: ${FfmpegMediaPolicies.formatCommand(arguments.asIterable())}")
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

    private fun showEditingControls(visible: Boolean) {
        val visibility = if (visible) View.VISIBLE else View.GONE
        val isVideo = selectedMime.startsWith("video/")
        val isAudio = selectedMime.startsWith("audio/")
        timeline.visibility = visibility
        currentTime.visibility = visibility
        timeFields.visibility = visibility
        buttonCut.visibility = visibility
        buttonVideoEncoder.visibility = if (visible && isVideo) View.VISIBLE else View.GONE
        helpVideoEncoder.visibility = buttonVideoEncoder.visibility
        findViewById<View>(R.id.label_video_encoder).visibility = buttonVideoEncoder.visibility
        val qualityVisibility = if (visible && (isVideo || isAudio)) View.VISIBLE else View.GONE
        buttonVideoQuality.visibility = qualityVisibility
        helpVideoQuality.visibility = qualityVisibility
        findViewById<TextView>(R.id.label_video_quality).apply {
            this.visibility = qualityVisibility
            text = if (isAudio) "Qualidade do áudio" else "Qualidade do vídeo"
        }
        updateVideoEncoderButton()
    }

    private fun detectVideoEncoders() {
        availableVideoEncoders = FfmpegVideoEncoderRegistry.detect()
        selectedVideoEncoder = availableVideoEncoders.firstOrNull()
        updateVideoEncoderButton()
    }

    private fun showVideoEncoderMenu() {
        if (availableVideoEncoders.isEmpty() || isProcessing) return
        PopupMenu(this, buttonVideoEncoder).apply {
            availableVideoEncoders.forEach { menu.add(it.displayName) }
            setOnMenuItemClickListener { item ->
                selectedVideoEncoder = availableVideoEncoders.firstOrNull { it.displayName == item.title.toString() }
                updateVideoEncoderButton()
                true
            }
            show()
        }
    }

    private fun updateVideoEncoderButton() {
        val encoder = selectedVideoEncoder
        buttonVideoEncoder.text = if (encoder == null) "Encoder indisponível" else encoder.shortName
        buttonVideoEncoder.isEnabled = encoder != null && !isProcessing
        buttonVideoEncoder.alpha = if (buttonVideoEncoder.isEnabled) 1f else 0.42f
        val audioMode = selectedMime.startsWith("audio/")
        buttonVideoQuality.text = if (audioMode) selectedAudioQuality.label else selectedVideoQuality.label
        buttonVideoQuality.isEnabled = !isProcessing && (audioMode || encoder != null)
        buttonVideoQuality.alpha = if (buttonVideoQuality.isEnabled) 1f else 0.42f
    }

    private fun showVideoQualityMenu() {
        if (isProcessing) return
        if (selectedMime.startsWith("audio/")) {
            PopupMenu(this, buttonVideoQuality).apply {
                FfmpegAudioQuality.entries.forEach { menu.add(it.menuLabel) }
                setOnMenuItemClickListener { item ->
                    selectedAudioQuality = FfmpegAudioQuality.entries.first { it.menuLabel == item.title.toString() }
                    updateVideoEncoderButton()
                    true
                }
                show()
            }
            return
        }
        if (selectedVideoEncoder == null) return
        PopupMenu(this, buttonVideoQuality).apply {
            FfmpegVideoQuality.entries.forEach { menu.add(it.menuLabel) }
            setOnMenuItemClickListener { item ->
                selectedVideoQuality = FfmpegVideoQuality.entries.first { it.menuLabel == item.title.toString() }
                updateVideoEncoderButton()
                true
            }
            show()
        }
    }

    private fun videoEncodingArguments(
        encoder: FfmpegVideoEncoder,
        sourceBitrate: String,
        quality: FfmpegVideoQuality = selectedVideoQuality
    ): List<String> {
        val settings = encoder.encodingFor(quality, sourceBitrate)
        return settings.arguments + settings.targetBitrate.orEmpty().takeIf { it.isNotBlank() }
            ?.let { listOf("-b:v", it) }
            .orEmpty()
    }

    private fun setPreviewFrameHeight(heightDp: Int) {
        val params = previewFrame.layoutParams
        params.height = (heightDp * resources.displayMetrics.density).toInt()
        previewFrame.layoutParams = params
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

    private fun seekPreview(positionMs: Long, updateTimeline: Boolean = true, forPlaybackStart: Boolean = false) {
        if (playbackControls.visibility != View.VISIBLE) return

        val safePosition = positionMs.coerceIn(0L, durationMs)
        
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
        updatePlayPauseLabel()
    }

    private fun performActualSeek(safePosition: Long, forPlaybackStart: Boolean) {
        val player = previewPlayer
        try {
            if (videoPreview.visibility == View.VISIBLE) {
                if (forPlaybackStart && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && player != null) {
                    player.seekTo(safePosition, MediaPlayer.SEEK_NEXT_SYNC)
                } else {
                    seekMediaPlayer(player, safePosition)
                }
            } else {
                seekMediaPlayer(audioPlayer, safePosition)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error performing actual seek", e)
        }
    }

    private fun togglePreviewPlayback() {
        if (playbackControls.visibility != View.VISIBLE) return

        if (isPreviewPlaying()) {
            pausePreview()
            playWhenSeekCompletes = false
            setPlaybackButtonPlaying(false)
            return
        }

        val startMs = timeline.getStartMs()
        val endMs = timeline.getEndMs()
        val currentMs = currentPreviewPosition()
        val playFromMs = if (currentMs < startMs || currentMs >= endMs) startMs else currentMs
        if (currentMs < startMs || currentMs >= endMs) {
            playWhenSeekCompletes = true
            seekPreview(playFromMs, forPlaybackStart = true)
            setPlaybackButtonPlaying(true)
            syncPlaybackButtonSoon()
            if (videoPreview.visibility != View.VISIBLE) {
                playWhenSeekCompletes = false
                startPreview()
                setPlaybackButtonPlaying(isPreviewPlaying())
                syncPlaybackButtonSoon()
            } else if (previewPlayer == null) {
                videoPreview.postDelayed({
                    if (playWhenSeekCompletes) {
                        playWhenSeekCompletes = false
                        startPreview()
                        setPlaybackButtonPlaying(isPreviewPlaying())
                        syncPlaybackButtonSoon()
                    }
                }, 100L)
            }
            return
        }

        timeline.setCurrent(playFromMs)
        audioWaveform.setCurrent(playFromMs)
        startPreview()
        setPlaybackButtonPlaying(isPreviewPlaying())
        syncPlaybackButtonSoon()
    }

    private fun updatePlayPauseLabel() {
        if (playbackControls.visibility == View.VISIBLE) {
            setPlaybackButtonPlaying(isPreviewPlaying())
        }
    }

    private fun setPlaybackButtonPlaying(isPlaying: Boolean) {
        buttonPlayPause.setImageResource(if (isPlaying) R.drawable.ic_ffmpeg_pause else R.drawable.ic_ffmpeg_play)
        buttonPlayPause.contentDescription = if (isPlaying) "Pausar" else "Reproduzir"
    }

    private fun syncPlaybackButtonSoon() {
        handler.postDelayed({
            setPlaybackButtonPlaying(isPreviewPlaying())
        }, 180L)
    }

    private fun prepareAudioPreview(uri: Uri) {
        releaseAudioPlayer()
        status.text = "Carregando áudio..."
        audioPlayer = MediaPlayer().apply {
            setDataSource(this@FfmpegCutActivity, uri)
            setOnPreparedListener { player ->
                previewPlayer = player
                durationMs = player.duration.toLong().coerceAtLeast(durationMs)
                timeline.setRange(durationMs, 0L, durationMs)
                timeline.setCurrent(0L)
                audioWaveform.configure(selectedName, durationMs)
                audioWaveform.setRange(0L, durationMs)
                updateTimeFields(0L, durationMs)
                status.text = ""
            }
            setOnCompletionListener {
                val completedPosition = if (hasPreviewPlaybackStarted) durationMs else 0L
                hasPreviewPlaybackStarted = false
                timeline.setCurrent(completedPosition)
                audioWaveform.setCurrent(completedPosition)
                currentTime.text = formatTime(completedPosition)
                setPlaybackButtonPlaying(false)
            }
            prepareAsync()
        }
    }

    private fun preparePreview(uri: Uri) {
        val surface = previewSurface ?: return
        releasePreviewPlayer()
        playbackSpeed = 1f
        updateSpeedButton()
        status.text = ""
        previewPlayer = MediaPlayer().apply {
            setDataSource(this@FfmpegCutActivity, uri)
            setSurface(surface)
            setOnPreparedListener { player ->
                durationMs = player.duration.toLong().coerceAtLeast(1L)
                this@FfmpegCutActivity.videoWidth = player.videoWidth
                this@FfmpegCutActivity.videoHeight = player.videoHeight
                timeline.isEnabled = true
                timeline.setRange(durationMs, 0L, durationMs)
                timeline.setCurrent(0L)
                currentTime.text = formatTime(0L)
                updateTimeFields(0L, durationMs)
                applyPreviewTransform()
                seekPreview(0L)
            }
            setOnCompletionListener {
                val completedPosition = if (hasPreviewPlaybackStarted) durationMs else 0L
                hasPreviewPlaybackStarted = false
                timeline.setCurrent(completedPosition)
                currentTime.text = formatTime(completedPosition)
                setPlaybackButtonPlaying(false)
            }
            prepareAsync()
        }
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

    private fun releasePreviewPlayer() {
        previewPlayer?.release()
        previewPlayer = null
    }

    private fun releaseAudioPlayer() {
        audioPlayer?.release()
        audioPlayer = null
    }

    private fun isPreviewPlaying(): Boolean {
        return if (videoPreview.visibility == View.VISIBLE) {
            previewPlayer?.isPlaying == true
        } else {
            audioPlayer?.isPlaying == true
        }
    }

    private fun currentPreviewPosition(): Long {
        return if (videoPreview.visibility == View.VISIBLE) {
            previewPlayer?.currentPosition?.toLong() ?: timeline.getCurrentMs()
        } else {
            audioPlayer?.currentPosition?.toLong() ?: timeline.getCurrentMs()
        }
    }

    private fun startPreview() {
        applyPlaybackSpeed()
        if (videoPreview.visibility == View.VISIBLE) {
            previewPlayer?.start()
        } else {
            val player = audioPlayer
            if (player == null) {
                Toast.makeText(this, "O áudio ainda está carregando.", Toast.LENGTH_SHORT).show()
                setPlaybackButtonPlaying(false)
                return
            }
            player.start()
        }
        hasPreviewPlaybackStarted = true
    }

    private fun pausePreview() {
        if (videoPreview.visibility == View.VISIBLE) {
            previewPlayer?.pause()
        } else {
            audioPlayer?.pause()
        }
    }

    private fun seekMediaPlayer(player: MediaPlayer?, positionMs: Long) {
        if (player == null) return
        val safePosition = positionMs.coerceAtMost(Int.MAX_VALUE.toLong())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            player.seekTo(safePosition, MediaPlayer.SEEK_CLOSEST)
        } else {
            player.seekTo(safePosition.toInt())
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
        val player = previewPlayer ?: audioPlayer ?: return
        try {
            player.playbackParams = player.playbackParams.setSpeed(playbackSpeed)
        } catch (e: Exception) {
            Log.w(TAG, "Could not change playback speed", e)
            playbackSpeed = 1f
            updateSpeedButton()
            Toast.makeText(this, "Não consegui mudar a velocidade neste arquivo.", Toast.LENGTH_SHORT).show()
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

    private fun openOutputFile() {
        val uri = lastOutputUri ?: return
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, lastOutputMime.ifBlank { "application/octet-stream" })
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "Não encontrei um app para abrir $lastOutputName.", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.w(TAG, "Could not open output file", e)
            Toast.makeText(this, "Não consegui abrir o arquivo.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareOutputFile() {
        val uri = lastOutputUri ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = lastOutputMime.ifBlank { "application/octet-stream" }
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(intent, "Compartilhar arquivo"))
        } catch (e: Exception) {
            Log.w(TAG, "Could not share output file", e)
            Toast.makeText(this, "Não consegui compartilhar o arquivo.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun clearOutputResult() {
        lastOutputUri = null
        lastOutputMime = ""
        lastOutputName = ""
        tempOutputFiles.forEach { it.delete() }
        tempOutputFiles.clear()
        hasSaved = false
        outputFileName.visibility = View.GONE
        outputActions.visibility = View.GONE
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

    private fun stepTime(isStart: Boolean, forward: Boolean) {
        val currentMs = if (isStart) timeline.getStartMs() else timeline.getEndMs()
        val seconds = currentMs / 1000.0
        val nextSeconds = if (forward) {
            kotlin.math.floor(seconds + 1.0)
        } else {
            kotlin.math.ceil(seconds - 1.0)
        }
        val nextMs = (nextSeconds * 1000).toLong()
        
        if (isStart) {
            val clamped = nextMs.coerceIn(0L, timeline.getEndMs() - 100L)
            timeline.setStart(clamped, fromUser = true)
        } else {
            val clamped = nextMs.coerceIn(timeline.getStartMs() + 100L, durationMs)
            timeline.setEnd(clamped, fromUser = true)
        }
    }

    private fun adjustTimelineBound(isStart: Boolean, direction: Int) {
        val currentMs = if (isStart) timeline.getStartMs() else timeline.getEndMs()
        val currentSeconds = currentMs / 1000.0
        val targetSeconds = if (direction > 0) {
            kotlin.math.floor(currentSeconds + 1.001)
        } else {
            kotlin.math.ceil(currentSeconds - 1.001)
        }
        val newMs = (targetSeconds * 1000.0).toLong().coerceIn(0L, durationMs)
        
        if (isStart) {
            timeline.setStart(newMs.coerceAtMost(timeline.getEndMs()), true)
        } else {
            timeline.setEnd(newMs.coerceAtLeast(timeline.getStartMs()), true)
        }
    }

    companion object {
        private const val REQUEST_PICK_MEDIA = 4101
        private const val REQUEST_CHOOSE_OUTPUT_DIR = 4102
        private const val REQUEST_CHOOSE_PRE_OUTPUT_DIR = 4103
        private const val HYBRID_CUT_MAX_ATTEMPTS = 3
        private const val TAG = "FfmpegCut"
    }

    private data class StreamBitrates(
        val video: String? = null,
        val audio: String? = null,
        val width: Int? = null,
        val height: Int? = null,
        val frameRate: Double? = null,
        val codecFamily: String? = null
    ) {
        fun videoBitrateForEncoding(): String = video ?: estimateVideoBitrate(width, height, frameRate, codecFamily)
    }

    private data class CutExecutionResult(
        val success: Boolean,
        val cancelled: Boolean,
        val failureMessage: String,
        val reencodeRequiredReason: String? = null
    )

    private data class SaveResult(
        val uri: Uri?,
        val error: String?
    )
}
