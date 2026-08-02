package br.gov.sp.pcsp.launcher

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Matrix
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.SpannableString
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.app.DownloadManager
import androidx.appcompat.app.AlertDialog
import android.widget.PopupMenu
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class FfmpegRotateVideoActivity : AppCompatActivity() {

    private lateinit var rotateScroll: ScrollView
    private lateinit var previewFrame: View
    private lateinit var videoPreview: TextureView
    private lateinit var controls: View
    private lateinit var timeline: FfmpegRangeSlider
    private lateinit var currentTime: TextView
    private lateinit var inputFrom: EditText
    private lateinit var inputTo: EditText
    private lateinit var buttonFromPrev: ImageButton
    private lateinit var buttonFromNext: ImageButton
    private lateinit var buttonToPrev: ImageButton
    private lateinit var buttonToNext: ImageButton
    private lateinit var rotationOptions: RadioGroup
    private lateinit var metadataRotation: CheckBox
    private lateinit var flipHorizontal: CheckBox
    private lateinit var flipVertical: CheckBox
    private lateinit var parallelKeyframes: CheckBox
    private lateinit var inputParallelSegments: EditText
    private lateinit var parallelSegmentsContainer: View
    private lateinit var buttonPlayPause: ImageButton
    private lateinit var buttonSpeedDown: ImageButton
    private lateinit var buttonSpeedUp: ImageButton
    private lateinit var playbackSpeedLabel: TextView
    private lateinit var buttonVideoEncoder: TextView
    private lateinit var buttonVideoQuality: TextView
    private lateinit var buttonRotate: ImageButton
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var outputFileName: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var selectedUri: Uri? = null
    private var selectedName = ""
    private var durationMs = 0L
    private var syncingTimeFields = false
    private var previewPlayer: MediaPlayer? = null
    private var previewSurface: Surface? = null
    private var lastOutputUri: Uri? = null
    private var videoWidth = 0
    private var videoHeight = 0
    private var isProcessing = false
    @Volatile private var parallelProcessing = false
    private var playbackSpeed = 1f
    private val transformOrder = mutableListOf<TransformOp>()
    @Volatile private var currentSessionId: Long? = null
    private val speedSteps = floatArrayOf(0.25f, 0.5f, 1f, 2f)

    private lateinit var buttonOutputFolder: ImageButton
    private lateinit var buttonOutputShare: ImageButton
    private lateinit var buttonSelectOutputFolder: ImageButton
    private lateinit var arrowInputOutput: View
    private lateinit var buttonSaveToFolder: ImageButton
    private lateinit var outputActions: View
    private var preSelectedOutputDirUri: Uri? = null
    private var finalOutputDirUri: Uri? = null
    private val tempOutputFiles = mutableListOf<File>()
    private var hasSaved = false
    @Volatile private var isSaving = false
    private var lastOutputMime = "video/mp4"
    private var lastOutputName = ""

    private var selectedCodec: FfmpegVideoEncoder? = null
    private var selectedVideoQuality = FfmpegVideoQuality.default
    private var availableCodecs: List<FfmpegVideoEncoder> = emptyList()

    private val progressTicker = object : Runnable {
        override fun run() {
            val player = previewPlayer
            if (videoPreview.visibility == View.VISIBLE && player?.isPlaying == true) {
                val position = player.currentPosition.toLong().coerceIn(0L, durationMs)
                val startMs = timeline.getStartMs()
                val endMs = timeline.getEndMs()
                if (position < startMs) {
                    seekPreview(startMs)
                } else if (position >= endMs) {
                    player.pause()
                    seekPreview(endMs)
                    timeline.setCurrent(endMs)
                    currentTime.text = formatTime(endMs)
                    setPlaybackButtonPlaying(false)
                } else {
                    timeline.setCurrent(position)
                    currentTime.text = formatTime(position)
                }
            }
            handler.postDelayed(this, 80L)
        }
    }

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        keepContentInsideSystemBars()
        setContentView(R.layout.activity_ffmpeg_rotate_video)

        rotateScroll = findViewById(R.id.rotate_scroll)
        previewFrame = findViewById(R.id.preview_frame)
        videoPreview = findViewById(R.id.video_preview)
        controls = findViewById(R.id.rotate_controls)
        timeline = findViewById(R.id.timeline)
        currentTime = findViewById(R.id.current_time)
        inputFrom = findViewById(R.id.input_from)
        inputTo = findViewById(R.id.input_to)
        buttonFromPrev = findViewById(R.id.button_from_prev)
        buttonFromNext = findViewById(R.id.button_from_next)
        buttonToPrev = findViewById(R.id.button_to_prev)
        buttonToNext = findViewById(R.id.button_to_next)
        rotationOptions = findViewById(R.id.rotation_options)
        metadataRotation = findViewById(R.id.check_metadata_rotation)
        flipHorizontal = findViewById(R.id.check_flip_horizontal)
        flipVertical = findViewById(R.id.check_flip_vertical)
        parallelKeyframes = findViewById(R.id.check_parallel_keyframes)
        inputParallelSegments = findViewById(R.id.input_parallel_segments)
        parallelSegmentsContainer = findViewById(R.id.parallel_segments_container)
        buttonPlayPause = findViewById(R.id.button_play_pause)
        buttonSpeedDown = findViewById(R.id.button_speed_down)
        buttonSpeedUp = findViewById(R.id.button_speed_up)
        playbackSpeedLabel = findViewById(R.id.playback_speed_label)
        buttonVideoEncoder = findViewById(R.id.button_video_encoder)
        buttonVideoQuality = findViewById(R.id.button_video_quality)
        buttonRotate = findViewById(R.id.button_rotate)
        progress = findViewById(R.id.progress)
        status = findViewById(R.id.status)
        outputFileName = findViewById(R.id.output_file_name)
        buttonOutputFolder = findViewById(R.id.button_output_folder)
        buttonOutputShare = findViewById(R.id.button_output_share)
        buttonSelectOutputFolder = findViewById(R.id.button_select_output_folder)
        arrowInputOutput = findViewById(R.id.arrow_input_output)
        buttonSaveToFolder = findViewById(R.id.button_save_to_folder)
        outputActions = findViewById(R.id.output_actions)
        buttonSelectOutputFolder.visibility = View.GONE
        arrowInputOutput.visibility = View.GONE

        videoPreview.surfaceTextureListener = surfaceListener
        val exitHandler = installCancelAndExitGuard(
            isTaskRunning = { isProcessing || parallelProcessing || isSaving },
            cancelTask = { if (isProcessing || parallelProcessing) cancelRotation() }
        )
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { exitHandler() }
        findViewById<View>(R.id.button_select_video).setOnClickListener { openVideoPicker() }
        buttonVideoEncoder.setOnClickListener { showVideoEncoderMenu() }
        findViewById<TextView>(R.id.help_video_encoder).setOnClickListener {
            FfmpegVideoEncoderRegistry.showHelp(this)
        }
        buttonVideoQuality.setOnClickListener { showVideoQualityMenu() }
        findViewById<TextView>(R.id.help_video_quality).setOnClickListener { selectedVideoQuality.showHelp(this) }
        buttonPlayPause.setOnClickListener { togglePlayback() }
        buttonSpeedDown.setOnClickListener { changePlaybackSpeed(-1) }
        buttonSpeedUp.setOnClickListener { changePlaybackSpeed(1) }
        buttonRotate.setOnClickListener {
            if (isProcessing) cancelRotation() else rotateSelectedVideo()
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
        
        detectAvailableCodecs()

        
        findViewById<TextView>(R.id.help_metadata_rotation).setOnClickListener {
            showHelp("Giro quase instantâneo, sem reencodar o vídeo. Nem todo player respeita.")
        }
        findViewById<TextView>(R.id.help_parallel_keyframes).setOnClickListener {
            showHelp("Modo experimental: o app corta o vídeo em trechos próximos de 1 minuto usando keyframes, gira os trechos em paralelo e junta tudo no final. Pode acelerar vídeos grandes em alguns aparelhos, mas também pode não ajudar quando o encoder já estiver no limite.")
        }
        findViewById<TextView>(R.id.help_parallel_segments).setOnClickListener {
            showParallelSegmentsHelp()
        }

        timeline.setRangeMarkersVisible(true)
        timeline.onRangeChanged = { startMs, endMs, fromUser, thumb ->
            updateTimeFields(startMs, endMs)
            if (fromUser) {
                val target = if (thumb == FfmpegRangeSlider.Thumb.END) endMs else startMs
                currentTime.text = formatTime(target)
                seekPreview(target)
            }
        }
        timeline.onPositionChanged = { positionMs, fromUser ->
            currentTime.text = formatTime(positionMs)
            if (fromUser) seekPreview(positionMs)
        }
        inputFrom.addTextChangedListener(timeFieldWatcher { timeline.setStart(it) })
        inputTo.addTextChangedListener(timeFieldWatcher { timeline.setEnd(it) })
        buttonFromPrev.setOnClickListener { adjustTimelineBound(true, -1) }
        buttonFromNext.setOnClickListener { adjustTimelineBound(true, 1) }
        buttonToPrev.setOnClickListener { adjustTimelineBound(false, -1) }
        buttonToNext.setOnClickListener { adjustTimelineBound(false, 1) }
        rotationOptions.setOnCheckedChangeListener { _, _ ->
            recordRotationSelection()
            applyPreviewTransform()
        }
        metadataRotation.setOnCheckedChangeListener { _, _ ->
            updateMetadataModeState()
            applyPreviewTransform()
        }
        flipHorizontal.setOnCheckedChangeListener { _, checked ->
            recordToggleSelection(TransformOp.HFLIP, checked)
            applyPreviewTransform()
        }
        flipVertical.setOnCheckedChangeListener { _, checked ->
            recordToggleSelection(TransformOp.VFLIP, checked)
            applyPreviewTransform()
        }
        parallelKeyframes.setOnCheckedChangeListener { _, _ -> updateMetadataModeState() }
        metadataRotation.isChecked = true
        updateMetadataModeState()
    }

    override fun onResume() {
        super.onResume()
        handler.post(progressTicker)
    }

    override fun onPause() {
        handler.removeCallbacks(progressTicker)
        if (previewPlayer?.isPlaying == true) {
            previewPlayer?.pause()
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

    @Deprecated("Deprecated Android callback kept for this legacy XML activity.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK) return

        when (requestCode) {
            REQUEST_PICK_VIDEO -> {
                val uri = data?.data ?: return
                try {
                    if (data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0) {
                        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                } catch (_: SecurityException) {
                }
                loadSelectedVideo(uri)
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

    private fun openVideoPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_PICK_VIDEO)
    }

    private fun loadSelectedVideo(uri: Uri) {
        selectedUri = uri
        selectedName = queryDisplayName(uri) ?: "video.mp4"
        status.text = ""
        clearOutputResult()
        controls.visibility = View.VISIBLE
        videoPreview.visibility = View.VISIBLE
        setRotateEnabled(true)
        setPlaybackButtonPlaying(false)
        timeline.isEnabled = false
        timeline.setRange(1L, 0L, 1L)
        timeline.setCurrent(0L)
        updateTimeFields(0L, 1L)
        currentTime.text = formatTime(0L)

        buttonSelectOutputFolder.visibility = View.VISIBLE
        arrowInputOutput.visibility = View.VISIBLE
        playbackSpeed = 1f
        updateSpeedButton()
        if (preSelectedOutputDirUri != null) {
            buttonSelectOutputFolder.setBackgroundResource(R.drawable.ffmpeg_outline_green_button_bg)
        } else {
            buttonSelectOutputFolder.setBackgroundResource(R.drawable.ffmpeg_outline_button_bg)
        }

        if (previewSurface != null) {
            preparePreview(uri)
        }
    }

    private fun preparePreview(uri: Uri) {
        val surface = previewSurface ?: return
        releasePreviewPlayer()
        previewPlayer = MediaPlayer().apply {
            setDataSource(this@FfmpegRotateVideoActivity, uri)
            setSurface(surface)
            setOnPreparedListener { player ->
                durationMs = player.duration.toLong().coerceAtLeast(1L)
                this@FfmpegRotateVideoActivity.videoWidth = player.videoWidth
                this@FfmpegRotateVideoActivity.videoHeight = player.videoHeight
                timeline.isEnabled = true
                timeline.setRange(durationMs, 0L, durationMs)
                timeline.setCurrent(0L)
                updateTimeFields(0L, durationMs)
                currentTime.text = formatTime(0L)
                applyPreviewTransform()
                seekPreview(0L)
            }
            setOnCompletionListener {
                setPlaybackButtonPlaying(false)
                timeline.setCurrent(durationMs)
                currentTime.text = formatTime(durationMs)
            }
            prepareAsync()
        }
    }

    private fun releasePreviewPlayer() {
        previewPlayer?.release()
        previewPlayer = null
    }

    private fun rotateSelectedVideo() {
        val uri = selectedUri ?: return

        val degrees = readDegrees()
        val metadataOnly = metadataRotation.isChecked
        val startMs = timeline.getStartMs()
        val endMs = timeline.getEndMs()
        if (endMs <= startMs) {
            status.text = "Confira os tempos de início e fim."
            return
        }
        val hasTrim = startMs > 0L || endMs < durationMs
        if ((!metadataOnly || hasTrim) && selectedCodec == null) {
            status.text = "Nenhum encoder de vídeo compatível está disponível."
            return
        }
        val hasHorizontalFlip = !metadataOnly && flipHorizontal.isChecked
        val hasVerticalFlip = !metadataOnly && flipVertical.isChecked
        if (!metadataOnly && degrees == 0 && !hasHorizontalFlip && !hasVerticalFlip) {
            AlertDialog.Builder(this)
                .setMessage("Você não pediu nenhuma mudança. Tem certeza?")
                .setPositiveButton("Sim") { _, _ -> executeRotation(uri, degrees, metadataOnly) }
                .setNegativeButton("Não", null)
                .show()
            return
        }

        if (!metadataOnly && degrees == 180 && flipHorizontal.isChecked && flipVertical.isChecked) {
            AlertDialog.Builder(this)
                .setMessage("Ao girar 180° e inverter horizontal e verticalmente, o vídeo ficará igual ao original. Tem certeza?")
                .setPositiveButton("Sim") { _, _ -> executeRotation(uri, degrees, metadataOnly) }
                .setNegativeButton("Não", null)
                .show()
            return
        }

        executeRotation(uri, degrees, metadataOnly)
    }

    private fun executeRotation(uri: Uri, degrees: Int, metadataOnly: Boolean) {
        val processingStartMs = SystemClock.elapsedRealtime()
        clearOutputResult()
        setProcessing(true)
        Thread {
            var inputFile: File? = null
            var tempOutput: File? = null
            var keepOutput = false
            try {
                val currentInputFile = copyUriToCache(uri, selectedName)
                inputFile = currentInputFile
                val outputName = buildOutputName(selectedName)
                val currentTempOutput = File(cacheDir, "rotate_${System.currentTimeMillis()}_$outputName")
                tempOutput = currentTempOutput
                val hasTrim = timeline.getStartMs() > 0L || timeline.getEndMs() < durationMs
                val canUseParallel = !hasTrim && !metadataOnly && parallelKeyframes.isChecked && buildOrderedFilters().isNotBlank()
                
                val tracker: FfmpegTaskTracker
                if (canUseParallel) {
                    tracker = FfmpegTaskTracker(status, listOf("Dividindo vídeo em trechos"))
                } else {
                    val taskLabel = when {
                        hasTrim && metadataOnly -> "Cortando e aplicando metadados"
                        hasTrim -> "Girando e cortando vídeo"
                        metadataOnly -> "Aplicando metadados"
                        else -> "Girando vídeo"
                    }
                    tracker = FfmpegTaskTracker(status, listOf("Preparando rotação", taskLabel))
                    tracker.completeCurrentTask()
                }

                val encoder = selectedCodec ?: FfmpegVideoEncoder("h264_mediacodec", "h264")
                val result = if (canUseParallel) {
                    executeParallelKeyframeRotation(currentInputFile, currentTempOutput, encoder, tracker)
                } else {
                    val session = executeFfmpegWithProgress(
                        buildFfmpegArguments(currentInputFile, currentTempOutput, degrees, encoder = encoder, metadataOnly = metadataOnly,
                            startMs = timeline.getStartMs(), endMs = timeline.getEndMs()),
                        tracker
                    )
                    RotationExecutionResult(
                        success = ReturnCode.isSuccess(session.returnCode) &&
                            currentTempOutput.exists() &&
                            currentTempOutput.length() > 0L,
                        cancelled = ReturnCode.isCancel(session.returnCode),
                        failureMessage = session.allLogsAsString.orEmpty().lines().takeLast(2).joinToString(" ").take(90),
                        encoderInfo = when {
                            metadataOnly -> "\nModo: metadados"
                            else -> ""
                        }
                    )
                }
                val success = result.success
                if (success) keepOutput = true

                runOnUiThread {
                    setProcessing(false)
                    if (result.cancelled) {
                        tracker.fail("Operação cancelada.")
                        return@runOnUiThread
                    }
                    if (!success) {
                        tracker.fail("Não consegui girar o vídeo. ${result.failureMessage}")
                        return@runOnUiThread
                    }
                    
                    val elapsedMs = SystemClock.elapsedRealtime() - processingStartMs
                    val elapsedSeconds = (elapsedMs / 1000.0).coerceAtLeast(0.001)
                    val processedDurationMs = (timeline.getEndMs() - timeline.getStartMs()).coerceAtLeast(1L)
                    val mediaSeconds = processedDurationMs / 1000.0
                    val efficiency = String.format(Locale.US, "%.2fx", mediaSeconds / elapsedSeconds)
                    tracker.success("Tempo de processamento: ${formatTime(elapsedMs)}\nMídia processada: ${formatTime(processedDurationMs)}\nEficiência: $efficiency${result.encoderInfo}")
                    
                    tempOutputFiles.clear()
                    tempOutputFiles.add(currentTempOutput)
                    lastOutputName = outputName

                    lastOutputName = outputName

                    outputActions.visibility = View.VISIBLE
                    buttonSaveToFolder.visibility = View.VISIBLE
                    buttonOutputFolder.visibility = View.GONE
                    buttonOutputShare.visibility = View.GONE

                    rotateScroll.post { rotateScroll.smoothScrollTo(0, outputActions.bottom) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to rotate video", e)
                runOnUiThread {
                    setProcessing(false)
                    status.text = "Erro: ${e.message ?: "falha inesperada"}"
                }
            } finally {
                inputFile?.delete()
                if (!keepOutput) tempOutput?.delete()
            }
        }.start()
    }

    private fun executeFfmpegWithProgress(arguments: Array<String>, tracker: FfmpegTaskTracker): FFmpegSession {
        val latch = CountDownLatch(1)
        val sessionRef = AtomicReference<FFmpegSession>()
        val safeDuration = durationMs.coerceAtLeast(1L)
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
                val elapsedMs = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(1L)
                val relativeSpeed = statistics.time.toDouble() / elapsedMs.toDouble()
                tracker.setProgress(percent, String.format(Locale.US, "%.1fx", relativeSpeed))
            }
        )
        currentSessionId = session.sessionId
        latch.await()
        currentSessionId = null
        return sessionRef.get() ?: session
    }

    private fun executeFfmpegWithIndexedProgress(
        arguments: Array<String>,
        tracker: FfmpegTaskTracker,
        taskIndex: Int,
        expectedDurationMs: Long
    ): FFmpegSession {
        val latch = CountDownLatch(1)
        val sessionRef = AtomicReference<FFmpegSession>()
        val safeDuration = expectedDurationMs.coerceAtLeast(1L)
        val startedAt = SystemClock.elapsedRealtime()
        val session = FFmpegKit.executeWithArgumentsAsync(
            arguments,
            { completed ->
                sessionRef.set(completed)
                latch.countDown()
            },
            { },
            { statistics ->
                val percent = ((statistics.time / safeDuration.toDouble()) * 100.0).toInt().coerceIn(0, 99)
                val elapsedMs = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(1L)
                tracker.setTaskProgress(taskIndex, percent, String.format(Locale.US, "%.1fx", statistics.time / elapsedMs))
            }
        )
        currentSessionId = session.sessionId
        latch.await()
        currentSessionId = null
        return sessionRef.get() ?: session
    }

    private fun executeParallelKeyframeRotation(
        inputFile: File,
        outputFile: File,
        encoder: FfmpegVideoEncoder,
        tracker: FfmpegTaskTracker
    ): RotationExecutionResult {
        parallelProcessing = true
        val workDir = File(cacheDir, "rotate_parallel_${System.currentTimeMillis()}").apply { mkdirs() }
        val segmentDir = File(workDir, "segments").apply { mkdirs() }
        val rotatedDir = File(workDir, "rotated").apply { mkdirs() }
        val videoBitrate = detectVideoBitrate(inputFile)
        val videoGopSize = recommendedGopSize(inputFile)
        var previousSessionHistorySize: Int? = null
        try {
            val defaultWorkerCount = Runtime.getRuntime().availableProcessors().minus(1).coerceAtLeast(1)
            val requestedSegmentCount = inputParallelSegments.text.toString().trim().toIntOrNull()
                ?.takeIf { it > 0 }
            val targetSegmentCount = requestedSegmentCount ?: defaultWorkerCount
            
            val keyframesMs = extractKeyframesSync(inputFile).filter { it > 100L && it < durationMs - 100L }
            val splitPoints = mutableListOf<Double>()
            
            if (keyframesMs.size < targetSegmentCount - 1) {
                splitPoints.addAll(keyframesMs.map { it / 1000.0 })
            } else {
                val targetSegmentDurationMs = durationMs / targetSegmentCount.toDouble()
                val usedKeyframes = mutableSetOf<Long>()
                for (i in 1 until targetSegmentCount) {
                    val targetMs = targetSegmentDurationMs * i
                    val bestKeyframe = keyframesMs.filter { it !in usedKeyframes }.minByOrNull { kotlin.math.abs(it - targetMs) }
                    if (bestKeyframe != null) {
                        usedKeyframes.add(bestKeyframe)
                        splitPoints.add(bestKeyframe / 1000.0)
                    }
                }
            }

            val splitTaskIndex = 0
            val segmentPattern = File(segmentDir, "part_%05d.mp4").absolutePath
            val splitSession = if (splitPoints.isNotEmpty()) {
                val times = splitPoints.sorted().joinToString(",") { String.format(Locale.US, "%.3f", it) }
                executeFfmpegWithIndexedProgress(
                    arrayOf(
                        "-y",
                        "-i", inputFile.absolutePath,
                        "-map", "0",
                        "-c", "copy",
                        "-f", "segment",
                        "-segment_times", times,
                        "-reset_timestamps", "1",
                        "-segment_format", "mp4",
                        "-avoid_negative_ts", "make_zero",
                        segmentPattern
                    ),
                    tracker,
                    splitTaskIndex,
                    durationMs
                )
            } else {
                executeFfmpegWithIndexedProgress(
                    arrayOf(
                        "-y",
                        "-i", inputFile.absolutePath,
                        "-map", "0",
                        "-c", "copy",
                        "-f", "segment",
                        "-segment_time", String.format(Locale.US, "%.1f", (durationMs / 1000.0) / targetSegmentCount.toDouble()),
                        "-reset_timestamps", "1",
                        "-segment_format", "mp4",
                        "-avoid_negative_ts", "make_zero",
                        segmentPattern
                    ),
                    tracker,
                    splitTaskIndex,
                    durationMs
                )
            }
            if (ReturnCode.isCancel(splitSession.returnCode)) {
                return RotationExecutionResult(false, true, "", "")
            }
            if (!ReturnCode.isSuccess(splitSession.returnCode)) {
                return RotationExecutionResult(
                    false,
                    false,
                    splitSession.allLogsAsString.orEmpty().lines().takeLast(2).joinToString(" ").take(120),
                    encoder.ffmpegName
                )
            }
            // O segmentador pode terminar sem emitir estatistica de 100%.
            // O retorno bem-sucedido e a referencia definitiva para fechar esta etapa visual.
            tracker.completeTask(splitTaskIndex)

            val segments = segmentDir.listFiles { file -> file.extension.equals("mp4", ignoreCase = true) }
                ?.sortedBy { it.name }
                .orEmpty()
            if (segments.isEmpty()) {
                return RotationExecutionResult(false, false, "Nenhum trecho foi gerado.", encoder.ffmpegName)
            }

            val segmentTasks = segments.mapIndexed { i, _ -> "Girando trecho ${i + 1}" }
            tracker.appendTasks(segmentTasks)
            tracker.appendTasks(listOf("Juntando vídeo final"))
            val segmentTaskOffset = splitTaskIndex + 1
            val finalJoinTaskIndex = segmentTaskOffset + segments.size
            
            val filters = buildOrderedFilters()
            // Um valor manual controla tanto a divisao quanto o total de trechos codificados juntos.
            // Sem valor manual, preservamos o padrao seguro de usar todos os nucleos menos um.
            val workerCount = minOf(requestedSegmentCount ?: defaultWorkerCount, segments.size).coerceAtLeast(1)
            // A AAR mantem apenas 10 sessoes no historico por padrao. Acima disso,
            // sessoes em curso podem perder log/estatisticas embora continuem codificando.
            previousSessionHistorySize = FFmpegKitConfig.getSessionHistorySize()
            FFmpegKitConfig.setSessionHistorySize((workerCount + 8).coerceAtMost(999))
            // Separa o coordenador das sessoes FFmpeg. O executor dedicado e passado
            // diretamente ao FFmpegKit para nao cair no limite da fila async padrao.
            val executor = Executors.newFixedThreadPool(workerCount)
            val ffmpegSessionExecutor = Executors.newFixedThreadPool(workerCount)
            val cancelCount = AtomicInteger(0)
            val failures = Collections.synchronizedList(mutableListOf<String>())
            val activeSegmentSpeeds = ConcurrentHashMap<Int, Double>()
            val startedAt = SystemClock.elapsedRealtime()

            val futures = segments.mapIndexed { index, segment ->
                val chunkOutput = File(rotatedDir, "rotated_${index.toString().padStart(5, '0')}.mp4")
                executor.submit {
                    try {
                        // Algumas sessoes nao emitem estatisticas antes de concluir, sobretudo
                        // com muitos encodes MediaCodec. Marque-as como ativas desde o disparo.
                        tracker.setTaskProgress(segmentTaskOffset + index, 0)
                        val durationMs = detectDurationMs(segment).coerceAtLeast(1000L)
                        val segmentStartedAt = SystemClock.elapsedRealtime()
                        val latch = CountDownLatch(1)
                        val reportedProgressMs = AtomicLong(0L)
                        var finalSession: FFmpegSession? = null

                        fun publishSegmentProgress(processedMs: Long) {
                            val safeProcessedMs = processedMs.coerceIn(0L, durationMs)
                            while (true) {
                                val previous = reportedProgressMs.get()
                                if (safeProcessedMs < previous) return
                                if (reportedProgressMs.compareAndSet(previous, safeProcessedMs)) break
                            }
                            val percent = ((safeProcessedMs / durationMs.toDouble()) * 100.0).toInt().coerceIn(0, 99)
                            val elapsedMs = (SystemClock.elapsedRealtime() - segmentStartedAt).coerceAtLeast(1L)
                            val relativeSpeed = safeProcessedMs.toDouble() / elapsedMs.toDouble()
                            activeSegmentSpeeds[index] = relativeSpeed
                            tracker.setTaskProgress(
                                segmentTaskOffset + index,
                                percent,
                                String.format(Locale.US, "%.1fx", relativeSpeed)
                            )
                            val combinedSpeed = activeSegmentSpeeds.values.sum()
                            tracker.setLiveStatus(
                                "Velocidade real estimada: ${String.format(Locale.US, "%.1fx", combinedSpeed)}"
                            )
                        }

                        FFmpegKit.executeWithArgumentsAsync(
                            buildSegmentRotationArguments(segment, chunkOutput, filters, encoder, videoBitrate, videoGopSize).toTypedArray(),
                            { s ->
                                finalSession = s
                                latch.countDown()
                            },
                            { log ->
                                parseFfmpegProgressTimeMs(log.message)?.let(::publishSegmentProgress)
                            },
                            { statistics ->
                                publishSegmentProgress(statistics.time.toLong())
                            },
                            ffmpegSessionExecutor
                        )
                        latch.await()
                        
                        val session = finalSession!!
                        if (ReturnCode.isCancel(session.returnCode)) {
                            cancelCount.incrementAndGet()
                        } else if (ReturnCode.isSuccess(session.returnCode) && chunkOutput.exists() && chunkOutput.length() > 0L) {
                            tracker.completeTask(segmentTaskOffset + index)
                        } else {
                            val logs = session.allLogsAsString.orEmpty()
                            val diagnosticPath = saveParallelFailureDiagnostic(index + 1, logs)
                            failures += buildFfmpegFailureSummary(logs, diagnosticPath)
                        }
                        activeSegmentSpeeds.remove(index)
                        val combinedSpeed = activeSegmentSpeeds.values.sum()
                        if (combinedSpeed > 0.0) {
                            tracker.setLiveStatus(
                                "Velocidade real estimada: ${String.format(Locale.US, "%.1fx", combinedSpeed)}"
                            )
                        }
                    } catch (e: Throwable) {
                        activeSegmentSpeeds.remove(index)
                        failures += (e.message ?: "falha ao girar trecho")
                    }
                }
            }

            futures.forEach { it.get() }
            executor.shutdown()
            ffmpegSessionExecutor.shutdown()
            tracker.clearLiveStatus()

            if (cancelCount.get() > 0) {
                return RotationExecutionResult(false, true, "", encoder.ffmpegName)
            }
            if (failures.isNotEmpty()) {
                return RotationExecutionResult(false, false, failures.firstOrNull().orEmpty(), encoder.ffmpegName)
            }

            val rotatedSegments = rotatedDir.listFiles { file -> file.extension.equals("mp4", ignoreCase = true) }
                ?.sortedBy { it.name }
                .orEmpty()
            if (rotatedSegments.size != segments.size) {
                return RotationExecutionResult(false, false, "Nem todos os trechos foram processados.", encoder.ffmpegName)
            }

            val listFile = File(workDir, "rotate_list.txt")
            listFile.writeText(
                rotatedSegments.joinToString("\n") { "file '${it.absolutePath.replace("\\", "/")}'" },
                Charsets.UTF_8
            )
            val concatSession = executeFfmpegWithIndexedProgress(
                arrayOf(
                    "-y",
                    "-f", "concat",
                    "-safe", "0",
                    "-i", listFile.absolutePath,
                    "-c", "copy",
                    "-map_metadata", "-1",
                    "-metadata:s:v:0", "rotate=0",
                    "-movflags", "+faststart",
                    outputFile.absolutePath
                ),
                tracker,
                finalJoinTaskIndex,
                durationMs
            )
            val concatSuccess = ReturnCode.isSuccess(concatSession.returnCode) && outputFile.exists() && outputFile.length() > 0L
            if (concatSuccess) tracker.completeTask(finalJoinTaskIndex)
            return RotationExecutionResult(
                success = concatSuccess,
                cancelled = ReturnCode.isCancel(concatSession.returnCode),
                failureMessage = concatSession.allLogsAsString.orEmpty().lines().takeLast(2).joinToString(" ").take(90),
                encoderInfo = "\nEncoder: ${encoder.ffmpegName}\nParalelo: ${segments.size} trechos ($workerCount threads)"
            )
        } catch (e: Throwable) {
            return RotationExecutionResult(false, false, e.message.orEmpty(), encoder.ffmpegName)
        } finally {
            previousSessionHistorySize?.let { FFmpegKitConfig.setSessionHistorySize(it) }
            parallelProcessing = false
            workDir.deleteRecursively()
        }
    }

    private fun detectDurationMs(file: File): Long {
        return try {
            val retriever = android.media.MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)
            val dur = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            retriever.release()
            dur
        } catch (_: Exception) {
            0L
        }
    }

    private fun recommendedGopSize(file: File): Int {
        return try {
            val extractor = android.media.MediaExtractor()
            try {
                extractor.setDataSource(file.absolutePath)
                val videoFormat = (0 until extractor.trackCount)
                    .map { extractor.getTrackFormat(it) }
                    .firstOrNull { it.getString(android.media.MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                val frameRate = videoFormat
                    ?.takeIf { it.containsKey(android.media.MediaFormat.KEY_FRAME_RATE) }
                    ?.getInteger(android.media.MediaFormat.KEY_FRAME_RATE)
                    ?.coerceIn(1, 120)
                    ?: 30
                (frameRate * 2).coerceAtLeast(frameRate)
            } finally {
                extractor.release()
            }
        } catch (_: Throwable) {
            60
        }
    }

    private fun extractKeyframesSync(inputFile: File): List<Long> {
        val extractor = android.media.MediaExtractor()
        val keyframes = mutableListOf<Long>()
        try {
            extractor.setDataSource(inputFile.absolutePath)
            var videoTrackIndex = -1
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(android.media.MediaFormat.KEY_MIME)
                if (mime?.startsWith("video/") == true) {
                    videoTrackIndex = i
                    break
                }
            }
            if (videoTrackIndex >= 0) {
                extractor.selectTrack(videoTrackIndex)
                while (true) {
                    val sampleTime = extractor.sampleTime
                    if (sampleTime < 0) break
                    if ((extractor.sampleFlags and android.media.MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                        keyframes.add(sampleTime / 1000L)
                    }
                    extractor.advance()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting keyframes", e)
        } finally {
            extractor.release()
        }
        return keyframes
    }

    private fun buildSegmentRotationArguments(
        inputFile: File,
        outputFile: File,
        filters: String,
        encoder: FfmpegVideoEncoder,
        videoBitrate: String,
        gopSize: Int
    ): List<String> {
        return mutableListOf(
            "-y",
            "-stats_period", "0.1",
            "-progress", "pipe:1",
            "-nostats",
            // Muitos trechos em paralelo podem abrir dezenas de threads auxiliares
            // de decodificacao/filtro. O MediaCodec continua no hardware.
            "-threads", "1",
            "-filter_threads", "1",
            "-i", inputFile.absolutePath,
            "-map", "0:v:0",
            "-map", "0:a?",
            "-vf", filters
        ).apply {
            addAll(videoEncodingArguments(encoder, videoBitrate))
            if (encoder.ffmpegName.endsWith("_mediacodec")) {
                addAll(listOf("-g", gopSize.toString()))
            }
            addAll(
                listOf(
                    "-c:a", "copy",
                    "-map_metadata", "-1",
                    "-metadata:s:v:0", "rotate=0",
                    "-movflags", "+faststart",
                    outputFile.absolutePath
                )
            )
        }
    }

    private fun parseFfmpegProgressTimeMs(text: String): Long? {
        val match = PROGRESS_OUT_TIME_REGEX.findAll(text).lastOrNull() ?: return null
        val parts = match.groupValues[1].split(":")
        if (parts.size != 3) return null
        val hours = parts[0].toLongOrNull() ?: return null
        val minutes = parts[1].toLongOrNull() ?: return null
        val secondsAndFraction = parts[2].split(".", limit = 2)
        val seconds = secondsAndFraction[0].toLongOrNull() ?: return null
        val millis = secondsAndFraction.getOrNull(1)
            ?.take(3)
            ?.padEnd(3, '0')
            ?.toLongOrNull()
            ?: 0L
        return (((hours * 60 + minutes) * 60 + seconds) * 1000) + millis
    }

    private fun buildFfmpegFailureSummary(logs: String, diagnosticPath: String?): String {
        val relevant = logs.lineSequence()
            .filter {
                it.contains("error", ignoreCase = true) ||
                    it.contains("failed", ignoreCase = true) ||
                    it.contains("resource", ignoreCase = true) ||
                    it.contains("mediacodec", ignoreCase = true)
            }
            .toList()
            .takeLast(3)
            .joinToString(" ")
            .take(220)
        val summary = relevant.ifBlank { logs.lines().takeLast(4).joinToString(" ").take(220) }
        return if (diagnosticPath != null) "$summary Diagnóstico: $diagnosticPath" else summary
    }

    private fun saveParallelFailureDiagnostic(segmentNumber: Int, logs: String): String? {
        if (logs.isBlank()) return null
        return try {
            val root = if (hasSigStorageAccess()) {
                File(sigOutputDir(), "diagnosticos")
            } else {
                File(getExternalFilesDir(null), "diagnosticos")
            }.apply { mkdirs() }
            val timestamp = SimpleDateFormat("HHmmss_SSS", Locale.US).format(Date())
            val file = File(root, "giro_paralelo_trecho_${segmentNumber}_$timestamp.txt")
            file.writeText(logs, Charsets.UTF_8)
            file.absolutePath
        } catch (e: Throwable) {
            Log.w(TAG, "Could not save parallel rotation diagnostic", e)
            null
        }
    }

    private fun bufferSizeFor(bitrate: String): String {
        val match = Regex("""(\d+)""").find(bitrate) ?: return "30M"
        val value = match.groupValues[1].toIntOrNull() ?: return "30M"
        return if (bitrate.endsWith("M", ignoreCase = true)) {
            "${(value * 2).coerceAtLeast(2)}M"
        } else {
            "${(value * 2).coerceAtLeast(2)}k"
        }
    }

    private fun formatProgressStatus(task: String, percent: Int, processedMs: Double, startedAtMs: Long): String {
        val elapsedSeconds = ((SystemClock.elapsedRealtime() - startedAtMs) / 1000.0).coerceAtLeast(0.001)
        val processedSeconds = (processedMs.coerceAtLeast(0.0) / 1000.0)
        val efficiency = processedSeconds / elapsedSeconds
        return "$task... $percent% | ${String.format(Locale.US, "%.2fx", efficiency)}"
    }

    private fun recordRotationSelection() {
        if (readDegrees() == 0) {
            transformOrder.remove(TransformOp.ROTATE)
        } else {
            transformOrder.remove(TransformOp.ROTATE)
            transformOrder.add(TransformOp.ROTATE)
        }
    }

    private fun recordToggleSelection(operation: TransformOp, checked: Boolean) {
        if (checked) {
            if (!transformOrder.contains(operation)) transformOrder.add(operation)
        } else {
            transformOrder.remove(operation)
        }
    }

    private fun detectAvailableCodecs() {
        availableCodecs = FfmpegVideoEncoderRegistry.detect()
        selectedCodec = availableCodecs.firstOrNull()
        updateVideoEncoderButton()
    }

    private fun showVideoEncoderMenu() {
        if (availableCodecs.isEmpty() || isProcessing) return
        PopupMenu(this, buttonVideoEncoder).apply {
            availableCodecs.forEach { menu.add(it.displayName) }
            setOnMenuItemClickListener { item ->
                selectedCodec = availableCodecs.firstOrNull { it.displayName == item.title.toString() }
                updateVideoEncoderButton()
                true
            }
            show()
        }
    }

    private fun updateVideoEncoderButton() {
        val encoder = selectedCodec
        buttonVideoEncoder.text = if (encoder == null) "Encoder indisponível" else encoder.shortName
        val enabled = encoder != null && !metadataRotation.isChecked && !isProcessing
        buttonVideoEncoder.isEnabled = enabled
        buttonVideoEncoder.alpha = if (enabled) 1f else 0.42f
        buttonVideoQuality.text = selectedVideoQuality.label
        buttonVideoQuality.isEnabled = enabled
        buttonVideoQuality.alpha = if (enabled) 1f else 0.42f
    }

    private fun showParallelSegmentsHelp() {
        val baseMessage = "Define quantos trechos serão criados e quantos o app tentará processar ao mesmo tempo. Valores altos podem consumir muita memória, aquecer o aparelho, atingir o limite do encoder ou travar a tarefa. Use apenas para testes."
        val advertisedLimit = selectedCodec?.let(FfmpegVideoEncoderRegistry::advertisedMaxInstances)
        val safeLimit = advertisedLimit?.minus(1)?.coerceAtLeast(1)
        if (metadataRotation.isChecked || !parallelKeyframes.isChecked || safeLimit == null) {
            showHelp(baseMessage)
            return
        }

        val warning = "\n\nO limite de seu dispositivo parece ser $safeLimit processos simultâneos."
        val message = SpannableString(baseMessage + warning).apply {
            setSpan(
                ForegroundColorSpan(Color.rgb(255, 90, 90)),
                baseMessage.length,
                length,
                SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        AlertDialog.Builder(this)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showVideoQualityMenu() {
        if (selectedCodec == null || isProcessing) return
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

    private fun videoEncodingArguments(encoder: FfmpegVideoEncoder, sourceBitrate: String): List<String> {
        val settings = encoder.encodingFor(selectedVideoQuality, sourceBitrate)
        return settings.arguments + settings.targetBitrate.orEmpty().takeIf { it.isNotBlank() }
            ?.let { listOf("-b:v", it) }
            .orEmpty()
    }



    private fun buildFfmpegArguments(
        inputFile: File,
        outputFile: File,
        degrees: Int,
        encoder: FfmpegVideoEncoder,
        metadataOnly: Boolean,
        startMs: Long = 0L,
        endMs: Long = durationMs
    ): Array<String> {
        val hasTrim = startMs > 0L || endMs < durationMs
        val trimDurationMs = (endMs - startMs).coerceAtLeast(1L)
        if (metadataOnly) {
            val currentMetadataDegrees = detectCurrentMetadataRotation(inputFile)
            val metadataDegrees = normalizeMetadataDegrees(currentMetadataDegrees - degrees)
            val args = mutableListOf("-y", "-i", inputFile.absolutePath)
            if (hasTrim) args.addAll(listOf("-ss", formatFfmpegTime(startMs), "-t", formatFfmpegTime(trimDurationMs)))
            args.addAll(listOf("-map", "0"))
            if (hasTrim) {
                args.addAll(videoEncodingArguments(encoder, detectVideoBitrate(inputFile)))
                args.addAll(listOf("-c:a", "aac"))
            } else {
                args.addAll(listOf("-c", "copy"))
            }
            args.addAll(listOf("-metadata:s:v:0", "rotate=$metadataDegrees", outputFile.absolutePath))
            return args.toTypedArray()
        }

        val filters = buildOrderedFilters()
        val videoBitrate = detectVideoBitrate(inputFile)

        val args = mutableListOf("-y", "-i", inputFile.absolutePath)
        if (hasTrim) args.addAll(listOf("-ss", formatFfmpegTime(startMs), "-t", formatFfmpegTime(trimDurationMs)))
        if (filters.isNotEmpty()) {
            args.addAll(listOf("-vf", filters))
            args.addAll(videoEncodingArguments(encoder, videoBitrate))
            args.addAll(
                listOf(
                    "-c:a", if (hasTrim) "aac" else "copy",
                    "-map_metadata", "-1",
                    "-metadata:s:v:0", "rotate=0"
                )
            )
        } else {
            args.addAll(listOf("-c", "copy"))
        }
        args.add(outputFile.absolutePath)
        return args.toTypedArray()
    }

    private fun applyPreviewTransform() {
        keepPreviewFrameFilled()
        if (videoPreview.width == 0 || videoPreview.height == 0 || videoWidth <= 0 || videoHeight <= 0) return
        val frameWidth = videoPreview.width.toFloat()
        val frameHeight = videoPreview.height.toFloat()
        val centerX = frameWidth / 2f
        val centerY = frameHeight / 2f
        val rotated = transformOrder.contains(TransformOp.ROTATE) && (readDegrees() == 90 || readDegrees() == -90)
        val boundingWidth = if (rotated) videoHeight else videoWidth
        val boundingHeight = if (rotated) videoWidth else videoHeight
        val fitScale = minOf(frameWidth / boundingWidth.toFloat(), frameHeight / boundingHeight.toFloat())
        val fittedWidth = videoWidth * fitScale
        val fittedHeight = videoHeight * fitScale
        val matrix = Matrix()
        matrix.postScale(fittedWidth / frameWidth, fittedHeight / frameHeight, centerX, centerY)
        for (operation in transformOrder) {
            when (operation) {
                TransformOp.ROTATE -> {
                    val degrees = readDegrees()
                    if (degrees != 0) matrix.postRotate(degrees.toFloat(), centerX, centerY)
                }
                TransformOp.HFLIP -> {
                    if (!metadataRotation.isChecked && flipHorizontal.isChecked) {
                        matrix.postScale(-1f, 1f, centerX, centerY)
                    }
                }
                TransformOp.VFLIP -> {
                    if (!metadataRotation.isChecked && flipVertical.isChecked) {
                        matrix.postScale(1f, -1f, centerX, centerY)
                    }
                }
            }
        }
        videoPreview.setTransform(matrix)
        videoPreview.invalidate()
    }

    private fun buildOrderedFilters(): String {
        val filters = mutableListOf<String>()
        for (operation in transformOrder) {
            when (operation) {
                TransformOp.ROTATE -> {
                    when (readDegrees()) {
                        -90 -> filters.add("transpose=2")
                        90 -> filters.add("transpose=1")
                        180 -> filters.addAll(listOf("hflip", "vflip"))
                    }
                }
                TransformOp.HFLIP -> {
                    if (flipHorizontal.isChecked) filters.add("hflip")
                }
                TransformOp.VFLIP -> {
                    if (flipVertical.isChecked) filters.add("vflip")
                }
            }
        }
        return filters.joinToString(",")
    }

    private fun detectVideoBitrate(inputFile: File): String {
        return try {
            val session = FFmpegKit.executeWithArguments(arrayOf("-hide_banner", "-i", inputFile.absolutePath))
            val logs = session.allLogsAsString.orEmpty()
            parseBitrateFromText(logs.lines().firstOrNull { it.contains("Video:", ignoreCase = true) }.orEmpty())
                ?: parseBitrateFromText(logs)
                ?: FALLBACK_VIDEO_BITRATE
        } catch (e: Throwable) {
            Log.w(TAG, "Could not detect video bitrate", e)
            FALLBACK_VIDEO_BITRATE
        }
    }

    private fun parseBitrateFromText(text: String): String? {
        val match = Regex("""(\d+(?:\.\d+)?)\s*kb/s""", RegexOption.IGNORE_CASE).find(text) ?: return null
        val kbps = match.groupValues[1].toDoubleOrNull()?.takeIf { it > 0.0 } ?: return null
        return "${kbps.toInt().coerceAtLeast(1)}k"
    }

    private fun keepPreviewFrameFilled() {
        val params = videoPreview.layoutParams as android.widget.FrameLayout.LayoutParams
        if (params.width != android.widget.FrameLayout.LayoutParams.MATCH_PARENT ||
            params.height != android.widget.FrameLayout.LayoutParams.MATCH_PARENT
        ) {
            params.width = android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            params.height = android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            params.gravity = android.view.Gravity.CENTER
            videoPreview.layoutParams = params
        }
    }

    private fun updateMetadataModeState() {
        val metadataOnly = metadataRotation.isChecked
        if (metadataOnly) {
            flipHorizontal.isChecked = false
            flipVertical.isChecked = false
            transformOrder.remove(TransformOp.HFLIP)
            transformOrder.remove(TransformOp.VFLIP)
        }
        flipHorizontal.isEnabled = !metadataOnly
        flipVertical.isEnabled = !metadataOnly
        parallelKeyframes.isEnabled = !metadataOnly
        val alpha = if (metadataOnly) 0.42f else 1f
        flipHorizontal.alpha = alpha
        flipVertical.alpha = alpha
        parallelKeyframes.alpha = alpha
        parallelSegmentsContainer.visibility = if (!metadataOnly && parallelKeyframes.isChecked) View.VISIBLE else View.GONE
        inputParallelSegments.isEnabled = !metadataOnly && parallelKeyframes.isChecked
        inputParallelSegments.alpha = if (inputParallelSegments.isEnabled) 1f else 0.42f
        updateVideoEncoderButton()

    }

    private fun normalizeMetadataDegrees(degrees: Int): Int {
        val normalized = ((degrees % 360) + 360) % 360
        return when (normalized) {
            90 -> 90
            180 -> 180
            270 -> -90
            else -> 0
        }
    }

    private fun detectCurrentMetadataRotation(inputFile: File): Int {
        return try {
            val session = FFmpegKit.executeWithArguments(arrayOf("-hide_banner", "-i", inputFile.absolutePath))
            val logs = session.allLogsAsString.orEmpty()
            parseDisplayRotation(logs) ?: 0
        } catch (e: Throwable) {
            Log.w(TAG, "Could not detect current metadata rotation", e)
            0
        }
    }

    private fun parseDisplayRotation(logs: String): Int? {
        val displayMatrixRotation = Regex("""rotation of\s+(-?\d+(?:\.\d+)?)\s+degrees""", RegexOption.IGNORE_CASE)
            .find(logs)
            ?.groupValues
            ?.getOrNull(1)
            ?.toDoubleOrNull()
            ?.let { kotlin.math.round(it).toInt() }
        if (displayMatrixRotation != null) {
            return normalizeMetadataDegrees(displayMatrixRotation)
        }
        return Regex("""rotate\s*:\s*(-?\d+)""", RegexOption.IGNORE_CASE)
            .find(logs)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.let { normalizeMetadataDegrees(it) }
    }

    private fun readDegrees(): Int {
        return when (rotationOptions.checkedRadioButtonId) {
            R.id.rotate_minus_90 -> -90
            R.id.rotate_0 -> 0
            R.id.rotate_90 -> 90
            R.id.rotate_180 -> 180
            else -> 0
        }
    }

    private fun setRotateEnabled(enabled: Boolean) {
        if (isProcessing) return
        buttonRotate.alpha = if (enabled) 1f else 0.45f
        buttonRotate.isClickable = enabled
        buttonRotate.isFocusable = enabled
    }

    private fun showHelp(message: String) {
        AlertDialog.Builder(this)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun setProcessing(processing: Boolean) {
        isProcessing = processing
        progress.visibility = if (processing) View.VISIBLE else View.GONE
        buttonRotate.isEnabled = true
        buttonRotate.isClickable = true
        buttonRotate.isFocusable = true
        buttonRotate.alpha = 1f
        if (processing) {
            buttonRotate.setImageResource(R.drawable.ic_ffmpeg_cancel_red)
            buttonRotate.setBackgroundResource(R.drawable.ffmpeg_outline_red_button_bg)
            buttonRotate.contentDescription = "Cancelar"
            status.text = ""
        } else {
            currentSessionId = null
            buttonRotate.setImageResource(R.drawable.ic_ffmpeg_rotate_video)
            buttonRotate.setBackgroundResource(R.drawable.ffmpeg_outline_green_button_bg)
            buttonRotate.contentDescription = "Girar vídeo"
            setRotateEnabled(selectedUri != null)
        }
        updateVideoEncoderButton()
    }

    private fun cancelRotation() {
        status.text = "Cancelando..."
        if (parallelProcessing) {
            FFmpegKit.cancel()
        } else {
            currentSessionId?.let { FFmpegKit.cancel(it) } ?: FFmpegKit.cancel()
        }
    }

    private fun clearOutputResult() {
        lastOutputUri = null
        tempOutputFiles.forEach { it.delete() }
        tempOutputFiles.clear()
        hasSaved = false
        outputFileName.text = ""
        outputFileName.visibility = View.GONE
        outputActions.visibility = View.GONE
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
                    document = destDir.createFile("video/mp4", outputName)
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
                    lastOutputName = lastSavedName

                    val folderName = destDir.name ?: "Pasta selecionada"
                    status.text = "Arquivo(s) salvo(s) na pasta \"$folderName\""
                    outputFileName.text = lastSavedName
                    outputFileName.visibility = View.VISIBLE

                    buttonSaveToFolder.visibility = View.GONE
                    buttonOutputFolder.visibility = View.VISIBLE
                    buttonOutputShare.visibility = View.VISIBLE
                    filesToSave.forEach { it.delete() }
                    tempOutputFiles.removeAll(filesToSave.toSet())

                    rotateScroll.post { rotateScroll.smoothScrollTo(0, outputFileName.bottom) }
                } else {
                    status.text = "Erro ao salvar. Arquivo parcial removido. ${failure?.message.orEmpty()}".trim()
                }
            }
        }.start()
    }

    private fun pendingOutputName(tempFile: File): String {
        if (tempOutputFiles.size == 1 && lastOutputName.isNotBlank()) return lastOutputName
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

    private fun shareOutputFile() {
        val uri = lastOutputUri ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
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

    private fun formatElapsedTime(elapsedMs: Long): String {
        val totalSeconds = elapsedMs / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        val milliseconds = elapsedMs % 1000
        return String.format(Locale.US, "%02d:%02d.%03d", minutes, seconds, milliseconds)
    }

    private fun formatFfmpegTime(valueMs: Long): String =
        String.format(Locale.US, "%.3f", valueMs.coerceAtLeast(0L) / 1000.0)

    private fun parseTime(value: String): Long? {
        val parts = value.trim().split(":")
        if (parts.isEmpty() || parts.size > 3) return null
        val seconds = parts.lastOrNull()?.replace(",", ".")?.toDoubleOrNull() ?: return null
        val minutes = parts.getOrNull(parts.size - 2)?.toLongOrNull() ?: 0L
        val hours = parts.getOrNull(parts.size - 3)?.toLongOrNull() ?: 0L
        return ((hours * 3600 + minutes * 60) * 1000 + seconds * 1000).toLong()
    }

    private fun updateTimeFields(startMs: Long, endMs: Long) {
        syncingTimeFields = true
        inputFrom.setText(formatTime(startMs))
        inputTo.setText(formatTime(endMs))
        syncingTimeFields = false
    }

    private fun timeFieldWatcher(update: (Long) -> Unit): TextWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) {
            if (syncingTimeFields) return
            val value = parseTime(s?.toString().orEmpty()) ?: return
            update(value.coerceIn(0L, durationMs))
        }
    }

    private fun adjustTimelineBound(isStart: Boolean, direction: Int) {
        val currentMs = if (isStart) timeline.getStartMs() else timeline.getEndMs()
        val seconds = currentMs / 1000.0
        val nextSeconds = if (direction > 0) kotlin.math.floor(seconds + 1.001)
            else kotlin.math.ceil(seconds - 1.001)
        val nextMs = (nextSeconds * 1000.0).toLong().coerceIn(0L, durationMs)
        if (isStart) {
            timeline.setStart(nextMs.coerceAtMost(timeline.getEndMs()), true)
        } else {
            timeline.setEnd(nextMs.coerceAtLeast(timeline.getStartMs()), true)
        }
    }

    private fun togglePlayback() {
        val player = previewPlayer ?: return
        if (player.isPlaying) {
            player.pause()
            setPlaybackButtonPlaying(false)
            return
        }
        val startMs = timeline.getStartMs()
        val endMs = timeline.getEndMs()
        if (player.currentPosition.toLong() !in startMs until endMs) {
            seekPreview(startMs)
        }
        player.start()
        applyPlaybackSpeed()
        setPlaybackButtonPlaying(player.isPlaying)
        syncPlaybackButtonSoon()
    }

    private fun setPlaybackButtonPlaying(isPlaying: Boolean) {
        buttonPlayPause.setImageResource(if (isPlaying) R.drawable.ic_ffmpeg_pause else R.drawable.ic_ffmpeg_play)
        buttonPlayPause.contentDescription = if (isPlaying) "Pausar" else "Reproduzir"
    }

    private fun syncPlaybackButtonSoon() {
        handler.postDelayed({
            setPlaybackButtonPlaying(previewPlayer?.isPlaying == true)
        }, 180L)
    }

    private fun seekPreview(positionMs: Long) {
        val player = previewPlayer ?: return
        val safePosition = positionMs.coerceIn(0L, durationMs).coerceAtMost(Int.MAX_VALUE.toLong())
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
        applyPlaybackSpeed()
        updateSpeedButton()
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
    }

    private fun copyUriToCache(uri: Uri, displayName: String): File {
        val extension = displayName.substringAfterLast('.', "mp4")
        val inputFile = File(cacheDir, "rotate_input_${System.currentTimeMillis()}.$extension")
        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(inputFile).use { output -> input.copyTo(output) }
        }
        return inputFile
    }

    private fun saveOutput(outputFile: File, outputName: String): SaveResult {
        return try {
            val outputDir = sigOutputDir()
            if (!outputDir.exists() && !outputDir.mkdirs()) {
                return SaveResult(null, "não consegui criar a pasta SIG")
            }
            val destination = uniqueOutputFile(outputDir, outputName)
            outputFile.inputStream().use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            }
            SaveResult(FileProvider.getUriForFile(this, "$packageName.fileprovider", destination), null)
        } catch (e: Exception) {
            SaveResult(null, e.message)
        }
    }

    private fun buildOutputName(name: String): String {
        val base = name.substringBeforeLast('.', name).ifBlank { "video" }
            .replace(Regex("""[\\/:*?"<>|]"""), "_")
        return "${base}_girado.mp4"
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

    private fun sigOutputDir(): File {
        return File(File(Environment.getExternalStorageDirectory(), SIG_OUTPUT_FOLDER), currentOutputDateFolder())
    }

    private fun currentOutputDateFolder(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
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
        try {
            startActivity(appSettings)
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }

    private fun openOutputFile() {
        val uri = lastOutputUri ?: return
        try {
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "video/mp4")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "Não encontrei um app para abrir o vídeo.", Toast.LENGTH_SHORT).show()
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

    private fun formatTime(ms: Long): String {
        val totalSeconds = ms / 1000
        val milliseconds = ms % 1000
        val seconds = totalSeconds % 60
        val minutes = (totalSeconds / 60) % 60
        val hours = totalSeconds / 3600
        return String.format(Locale.US, "%02d:%02d:%02d.%03d", hours, minutes, seconds, milliseconds)
    }

    private data class RotationExecutionResult(
        val success: Boolean,
        val cancelled: Boolean,
        val failureMessage: String,
        val encoderInfo: String
    )

    companion object {
        private const val REQUEST_PICK_VIDEO = 4301
        private const val REQUEST_CHOOSE_OUTPUT_DIR = 4302
        private const val REQUEST_CHOOSE_PRE_OUTPUT_DIR = 4303
        private const val SIG_OUTPUT_FOLDER = "SIG"
        private const val FALLBACK_VIDEO_BITRATE = "15M"
        private const val TAG = "FfmpegRotateVideo"
        private val PROGRESS_OUT_TIME_REGEX = Regex("out_time=([0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]+)?)")
    }

    private enum class TransformOp {
        ROTATE,
        HFLIP,
        VFLIP
    }

    private data class SaveResult(val uri: Uri?, val error: String?)
}
