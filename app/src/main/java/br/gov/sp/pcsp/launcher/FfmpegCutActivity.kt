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
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.graphics.Matrix
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
    private lateinit var timeFields: View
    private lateinit var playbackControls: View
    private lateinit var buttonSpeedDown: ImageButton
    private lateinit var buttonPlayPause: ImageButton
    private lateinit var buttonSpeedUp: ImageButton
    private lateinit var playbackSpeedLabel: TextView
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
        timeFields = findViewById(R.id.time_fields)
        playbackControls = findViewById(R.id.playback_controls)
        buttonSpeedDown = findViewById(R.id.button_speed_down)
        buttonPlayPause = findViewById(R.id.button_play_pause)
        buttonSpeedUp = findViewById(R.id.button_speed_up)
        playbackSpeedLabel = findViewById(R.id.playback_speed_label)
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

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.button_select_file).setOnClickListener { openFilePicker() }
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
            val preUri = preSelectedOutputDirUri
            if (preUri != null) {
                saveTempOutputsToUri(preUri)
            } else {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                startActivityForResult(intent, REQUEST_CHOOSE_OUTPUT_DIR)
            }
        }
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
                val flags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                try {
                    contentResolver.takePersistableUriPermission(uri, flags)
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

    private fun loadSelectedMedia(uri: Uri) {
        selectedUri = uri
        selectedName = queryDisplayName(uri) ?: "arquivo"
        var mime = contentResolver.getType(uri).orEmpty()
        if (mime.isEmpty()) {
            val extension = selectedName.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
            mime = when (extension) {
                "mp4", "mkv", "mov", "avi", "webm", "3gp", "m4v" -> "video/$extension"
                "mp3", "wav", "m4a", "aac", "ogg", "opus", "flac" -> "audio/$extension"
                else -> "video/mp4"
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
            setPreviewFrameHeight(450)
            videoPreview.visibility = View.VISIBLE
            audioWaveform.clear()
            playbackSpeedLabel.visibility = View.VISIBLE
            playWhenSeekCompletes = false
            if (videoPreview.isAvailable) {
                previewSurface = Surface(videoPreview.surfaceTexture)
                preparePreview(uri)
            }
        } else if (selectedMime.startsWith("audio/")) {
            setPreviewFrameHeight(88)
            videoPreview.visibility = View.GONE
            playbackSpeedLabel.visibility = View.VISIBLE
            releasePreviewPlayer()
            playWhenSeekCompletes = false
            prepareAudioPreview(uri)
        } else {
            showEditingControls(false)
            playbackControls.visibility = View.GONE
            audioWaveform.clear()
            playbackSpeedLabel.visibility = View.GONE
            videoPreview.visibility = View.GONE
            releasePreviewPlayer()
            playWhenSeekCompletes = false
        }
    }

    private fun cutSelectedMedia() {
        val uri = selectedUri ?: return
        val startMs = parseTime(inputFrom.text.toString())
        val endMs = parseTime(inputTo.text.toString())

        if (startMs == null || endMs == null || endMs <= startMs) {
            status.text = "Confira os tempos de início e fim."
            return
        }

        clearOutputResult()
        setProcessing(true)
        Thread {
            try {
                val inputFile = copyUriToCache(uri, selectedName)
                val outputName = buildOutputName(selectedName)
                val tempOutput = File(cacheDir, "${System.currentTimeMillis()}_$outputName")
                val arguments = buildFfmpegArguments(inputFile, tempOutput, startMs, endMs)
                val session = executeFfmpegWithProgress(arguments, endMs - startMs)
                val success = ReturnCode.isSuccess(session.returnCode)
                val logs = session.allLogsAsString.orEmpty().lines().takeLast(3).joinToString("\n")
                runOnUiThread {
                    if (ReturnCode.isCancel(session.returnCode)) {
                        setProcessing(false)
                        status.text = "Operação cancelada."
                        return@runOnUiThread
                    }
                    if (!success) {
                        setProcessing(false)
                        status.text = "Não foi possível cortar com FFmpeg.\n$logs".trim()
                        return@runOnUiThread
                    }
 
                    setProcessing(false)
                    tempOutputFiles.clear()
                    tempOutputFiles.add(tempOutput)
                    hasSaved = false
 
                    status.text = "Processamento concluído com sucesso! Clique no disquete para salvar."
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
            }
        }.start()
    }

    private fun copyUriToCache(uri: Uri, displayName: String): File {
        val extension = displayName.substringAfterLast('.', "tmp")
        val inputFile = File(cacheDir, "ffmpeg_input_${System.currentTimeMillis()}.$extension")
        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(inputFile).use { output -> input.copyTo(output) }
        }
        return inputFile
    }

    private fun buildFfmpegArguments(inputFile: File, outputFile: File, startMs: Long, endMs: Long): Array<String> {
        val duration = (endMs - startMs) / 1000.0
        return arrayOf(
            "-y",
            "-ss", formatSeconds(startMs),
            "-i", inputFile.absolutePath,
            "-t", String.format(Locale.US, "%.3f", duration),
            "-map", "0",
            "-c", "copy",
            "-avoid_negative_ts", "make_zero",
            outputFile.absolutePath
        )
    }

    private fun saveTempOutputsToUri(treeUri: Uri) {
        val destDir = DocumentFile.fromTreeUri(this, treeUri)
        if (destDir == null || !destDir.isDirectory) {
            status.text = "Erro: pasta de destino inválida."
            return
        }
 
        var savedCount = 0
        var lastSavedUri: Uri? = null
        var lastSavedName = ""
 
        for (tempFile in tempOutputFiles) {
            if (!tempFile.exists()) continue
            val outputName = tempFile.name.substringAfter('_')
            try {
                val document = destDir.createFile(selectedMime.ifBlank { "application/octet-stream" }, outputName)
                if (document != null) {
                    contentResolver.openOutputStream(document.uri)?.use { output ->
                        tempFile.inputStream().use { input ->
                            input.copyTo(output)
                        }
                    }
                    savedCount++
                    lastSavedUri = document.uri
                    lastSavedName = outputName
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save file $outputName to selected folder", e)
            }
        }
 
        if (savedCount > 0) {
            hasSaved = true
            finalOutputDirUri = treeUri
            lastOutputUri = lastSavedUri
            lastOutputMime = selectedMime
            lastOutputName = lastSavedName
 
            val folderName = destDir.name ?: "Pasta selecionada"
            status.text = "Arquivo(s) salvo(s) na pasta \"$folderName\""
 
            buttonSaveToFolder.visibility = View.GONE
            buttonOutputFolder.visibility = View.VISIBLE
            buttonOutputShare.visibility = View.VISIBLE
        } else {
            status.text = "Erro ao salvar os arquivos na pasta selecionada."
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

    private fun buildOutputName(name: String): String {
        val dotIndex = name.lastIndexOf('.')
        if (dotIndex <= 0) return "${name}_cortado"
        return "${name.substring(0, dotIndex)}_cortado${name.substring(dotIndex)}"
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
            status.text = "Cortando... 0%"
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

    private fun executeFfmpegWithProgress(arguments: Array<String>, expectedDurationMs: Long): FFmpegSession {
        val latch = CountDownLatch(1)
        val sessionRef = AtomicReference<FFmpegSession>()
        val safeDuration = expectedDurationMs.coerceAtLeast(1L)
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
                runOnUiThread {
                    if (progress.visibility == View.VISIBLE) {
                        status.text = "Cortando... $percent%"
                    }
                }
            }
        )
        currentSessionId = session.sessionId
        latch.await()
        currentSessionId = null
        return sessionRef.get() ?: session
    }

    private fun showEditingControls(visible: Boolean) {
        val visibility = if (visible) View.VISIBLE else View.GONE
        timeline.visibility = visibility
        currentTime.visibility = visibility
        timeFields.visibility = visibility
        buttonCut.visibility = visibility
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
            if (videoPreview.visibility != View.VISIBLE) {
                playWhenSeekCompletes = false
                startPreview()
                setPlaybackButtonPlaying(true)
            } else if (previewPlayer == null) {
                videoPreview.postDelayed({
                    if (playWhenSeekCompletes) {
                        playWhenSeekCompletes = false
                        startPreview()
                        setPlaybackButtonPlaying(true)
                    }
                }, 100L)
            }
            return
        }

        timeline.setCurrent(playFromMs)
        audioWaveform.setCurrent(playFromMs)
        startPreview()
        setPlaybackButtonPlaying(true)
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
        if (isPreviewPlaying()) {
            applyPlaybackSpeed()
        }
    }

    private fun applyPlaybackSpeed() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            if (playbackSpeed != 1f) {
                playbackSpeed = 1f
                updateSpeedButton()
                Toast.makeText(this, "Este Android não permite mudar a velocidade.", Toast.LENGTH_SHORT).show()
            }
            return
        }
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
            4f -> "4x"
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

    companion object {
        private const val REQUEST_PICK_MEDIA = 4101
        private const val REQUEST_CHOOSE_OUTPUT_DIR = 4102
        private const val REQUEST_CHOOSE_PRE_OUTPUT_DIR = 4103
        private const val SIG_OUTPUT_FOLDER = "SIG"
        private const val TAG = "FfmpegCut"
    }

    private data class SaveResult(
        val uri: Uri?,
        val error: String?
    )
}
