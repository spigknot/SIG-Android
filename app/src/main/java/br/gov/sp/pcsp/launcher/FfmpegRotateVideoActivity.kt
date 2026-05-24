package br.gov.sp.pcsp.launcher

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.app.DownloadManager
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

class FfmpegRotateVideoActivity : AppCompatActivity() {

    private lateinit var rotateScroll: ScrollView
    private lateinit var previewFrame: View
    private lateinit var videoPreview: TextureView
    private lateinit var controls: View
    private lateinit var timeline: FfmpegRangeSlider
    private lateinit var currentTime: TextView
    private lateinit var rotationOptions: RadioGroup
    private lateinit var metadataRotation: CheckBox
    private lateinit var flipHorizontal: CheckBox
    private lateinit var flipVertical: CheckBox
    private lateinit var useMpeg4: CheckBox
    private lateinit var buttonPlayPause: ImageButton
    private lateinit var buttonRotate: ImageButton
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var outputFileName: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var selectedUri: Uri? = null
    private var selectedName = ""
    private var durationMs = 0L
    private var previewPlayer: MediaPlayer? = null
    private var previewSurface: Surface? = null
    private var lastOutputUri: Uri? = null
    private var videoWidth = 0
    private var videoHeight = 0
    private var isProcessing = false
    private val transformOrder = mutableListOf<TransformOp>()
    @Volatile private var currentSessionId: Long? = null

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
    private var lastOutputMime = "video/mp4"
    private var lastOutputName = ""

    private val progressTicker = object : Runnable {
        override fun run() {
            val player = previewPlayer
            if (videoPreview.visibility == View.VISIBLE && player?.isPlaying == true) {
                val position = player.currentPosition.toLong().coerceIn(0L, durationMs)
                timeline.setCurrent(position)
                currentTime.text = formatTime(position)
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
        rotationOptions = findViewById(R.id.rotation_options)
        metadataRotation = findViewById(R.id.check_metadata_rotation)
        flipHorizontal = findViewById(R.id.check_flip_horizontal)
        flipVertical = findViewById(R.id.check_flip_vertical)
        useMpeg4 = findViewById(R.id.check_use_mpeg4)
        buttonPlayPause = findViewById(R.id.button_play_pause)
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
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.button_select_video).setOnClickListener { openVideoPicker() }
        buttonPlayPause.setOnClickListener { togglePlayback() }
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
            val preUri = preSelectedOutputDirUri
            if (preUri != null) {
                saveTempOutputsToUri(preUri)
            } else {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                startActivityForResult(intent, REQUEST_CHOOSE_OUTPUT_DIR)
            }
        }
        findViewById<TextView>(R.id.help_metadata_rotation).setOnClickListener {
            showHelp("Giro quase instantâneo, sem reencodar o vídeo. Nem todo player respeita.")
        }
        findViewById<TextView>(R.id.help_use_mpeg4).setOnClickListener {
            showHelp("Tende a ser mais rápido, mas com menor qualidade e arquivo de saída maior")
        }

        timeline.setRangeMarkersVisible(false)
        timeline.onPositionChanged = { positionMs, fromUser ->
            currentTime.text = formatTime(positionMs)
            if (fromUser) seekPreview(positionMs)
        }
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
        useMpeg4.setOnCheckedChangeListener { _, _ -> updateMetadataModeState() }
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
                    contentResolver.takePersistableUriPermission(uri, data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
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
        currentTime.text = formatTime(0L)

        buttonSelectOutputFolder.visibility = View.VISIBLE
        arrowInputOutput.visibility = View.VISIBLE
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
        val hasHorizontalFlip = !metadataOnly && flipHorizontal.isChecked
        val hasVerticalFlip = !metadataOnly && flipVertical.isChecked
        if (degrees == 0 && !hasHorizontalFlip && !hasVerticalFlip) {
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
            try {
                val inputFile = copyUriToCache(uri, selectedName)
                val outputName = buildOutputName(selectedName)
                val tempOutput = File(cacheDir, "rotate_${System.currentTimeMillis()}_$outputName")
                val usedMpeg4 = !metadataOnly && useMpeg4.isChecked
                val session = executeFfmpegWithProgress(
                    buildFfmpegArguments(inputFile, tempOutput, degrees, useMpeg4Encoder = usedMpeg4, metadataOnly = metadataOnly)
                )

                runOnUiThread {
                    setProcessing(false)
                    if (ReturnCode.isCancel(session.returnCode)) {
                        status.text = "Operação cancelada."
                        return@runOnUiThread
                    }
                    if (!ReturnCode.isSuccess(session.returnCode) || !tempOutput.exists() || tempOutput.length() == 0L) {
                        val logTail = session.allLogsAsString.orEmpty().lines().takeLast(2).joinToString(" ")
                        status.text = "Não consegui girar o vídeo. ${logTail.take(90)}"
                        return@runOnUiThread
                    }
                    
                    tempOutputFiles.clear()
                    tempOutputFiles.add(tempOutput)
                    lastOutputName = outputName

                    val encoderInfo = when {
                        metadataOnly -> "\nModo: metadados"
                        usedMpeg4 -> "\nEncoder: mpeg4"
                        else -> "\nEncoder: h264_mediacodec"
                    }
                    status.text = "Giro concluído com sucesso! Clique no disquete para salvar.${encoderInfo}"
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
            }
        }.start()
    }

    private fun executeFfmpegWithProgress(arguments: Array<String>): FFmpegSession {
        val latch = CountDownLatch(1)
        val sessionRef = AtomicReference<FFmpegSession>()
        val safeDuration = durationMs.coerceAtLeast(1L)
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
                        status.text = "Girando vídeo... $percent%"
                    }
                }
            }
        )
        currentSessionId = session.sessionId
        latch.await()
        currentSessionId = null
        return sessionRef.get() ?: session
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

    private fun buildFfmpegArguments(
        inputFile: File,
        outputFile: File,
        degrees: Int,
        useMpeg4Encoder: Boolean,
        metadataOnly: Boolean
    ): Array<String> {
        if (metadataOnly) {
            val metadataDegrees = normalizeMetadataDegrees(-degrees)
            return arrayOf(
                "-y",
                "-i", inputFile.absolutePath,
                "-map", "0",
                "-c", "copy",
                "-metadata:s:v:0", "rotate=$metadataDegrees",
                outputFile.absolutePath
            )
        }

        val filters = buildOrderedFilters()

        val args = mutableListOf("-y", "-i", inputFile.absolutePath)
        if (filters.isNotEmpty()) {
            args.addAll(listOf("-vf", filters))
            if (useMpeg4Encoder) {
                args.addAll(listOf("-c:v", "mpeg4", "-q:v", "4"))
            } else {
                args.addAll(listOf("-c:v", "h264_mediacodec", "-b:v", "5M"))
            }
            args.addAll(listOf("-c:a", "copy"))
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
                        180 -> filters.addAll(listOf("transpose=1", "transpose=1"))
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
            useMpeg4.isChecked = false
            transformOrder.remove(TransformOp.HFLIP)
            transformOrder.remove(TransformOp.VFLIP)
        }
        flipHorizontal.isEnabled = !metadataOnly
        flipVertical.isEnabled = !metadataOnly
        useMpeg4.isEnabled = !metadataOnly
        val alpha = if (metadataOnly) 0.42f else 1f
        flipHorizontal.alpha = alpha
        flipVertical.alpha = alpha
        useMpeg4.alpha = alpha
    }

    private fun normalizeMetadataDegrees(degrees: Int): Int {
        return ((degrees % 360) + 360) % 360
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
            status.text = "Girando vídeo... 0%"
        } else {
            currentSessionId = null
            buttonRotate.setImageResource(R.drawable.ic_ffmpeg_rotate_video)
            buttonRotate.setBackgroundResource(R.drawable.ffmpeg_outline_green_button_bg)
            buttonRotate.contentDescription = "Girar vídeo"
            setRotateEnabled(selectedUri != null)
        }
    }

    private fun cancelRotation() {
        status.text = "Cancelando..."
        currentSessionId?.let { FFmpegKit.cancel(it) } ?: FFmpegKit.cancel()
    }

    private fun clearOutputResult() {
        lastOutputUri = null
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

        var savedCount = 0
        var lastSavedUri: Uri? = null
        var lastSavedName = ""

        for (tempFile in tempOutputFiles) {
            if (!tempFile.exists()) continue
            val outputName = tempFile.name.substringAfter('_')
            try {
                val document = destDir.createFile("video/mp4", outputName)
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
            lastOutputName = lastSavedName

            val folderName = destDir.name ?: "Pasta selecionada"
            status.text = "Arquivo(s) salvo(s) na pasta \"$folderName\""
            outputFileName.text = lastSavedName
            outputFileName.visibility = View.VISIBLE

            buttonSaveToFolder.visibility = View.GONE
            buttonOutputFolder.visibility = View.VISIBLE
            buttonOutputShare.visibility = View.VISIBLE

            rotateScroll.post { rotateScroll.smoothScrollTo(0, outputFileName.bottom) }
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

    private fun togglePlayback() {
        val player = previewPlayer ?: return
        if (player.isPlaying) {
            player.pause()
            setPlaybackButtonPlaying(false)
            return
        }
        player.start()
        setPlaybackButtonPlaying(true)
    }

    private fun setPlaybackButtonPlaying(isPlaying: Boolean) {
        buttonPlayPause.setImageResource(if (isPlaying) R.drawable.ic_ffmpeg_pause else R.drawable.ic_ffmpeg_play)
        buttonPlayPause.contentDescription = if (isPlaying) "Pausar" else "Reproduzir"
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

    companion object {
        private const val REQUEST_PICK_VIDEO = 4301
        private const val REQUEST_CHOOSE_OUTPUT_DIR = 4302
        private const val REQUEST_CHOOSE_PRE_OUTPUT_DIR = 4303
        private const val SIG_OUTPUT_FOLDER = "SIG"
        private const val TAG = "FfmpegRotateVideo"
    }

    private enum class TransformOp {
        ROTATE,
        HFLIP,
        VFLIP
    }

    private data class SaveResult(val uri: Uri?, val error: String?)
}
