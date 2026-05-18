package br.gov.sp.pcsp.launcher

import android.app.Activity
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
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
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

class FfmpegCutActivity : AppCompatActivity() {

    private lateinit var selectedFile: TextView
    private lateinit var cutScroll: ScrollView
    private lateinit var videoPreview: VideoView
    private lateinit var timeline: FfmpegRangeSlider
    private lateinit var currentTime: TextView
    private lateinit var inputFrom: EditText
    private lateinit var inputTo: EditText
    private lateinit var buttonPlayPause: ImageButton
    private lateinit var buttonCut: ImageButton
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var outputFileName: TextView
    private lateinit var outputActions: View
    private lateinit var buttonOutputFolder: ImageButton
    private lateinit var buttonOutputShare: ImageButton

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
    private var playWhenSeekCompletes = false

    private val progressTicker = object : Runnable {
        override fun run() {
            if (videoPreview.visibility == View.VISIBLE && videoPreview.isPlaying) {
                val position = videoPreview.currentPosition.toLong()
                val startMs = timeline.getStartMs()
                val endMs = timeline.getEndMs()
                if (position < startMs) {
                    videoPreview.pause()
                    playWhenSeekCompletes = true
                    seekPreview(startMs, forPlaybackStart = true)
                } else if (position >= endMs) {
                    videoPreview.pause()
                    playWhenSeekCompletes = false
                    seekPreview(endMs)
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        keepContentInsideSystemBars()
        setContentView(R.layout.activity_ffmpeg_cut)

        cutScroll = findViewById(R.id.cut_scroll)
        selectedFile = findViewById(R.id.selected_file)
        videoPreview = findViewById(R.id.video_preview)
        timeline = findViewById(R.id.timeline)
        currentTime = findViewById(R.id.current_time)
        inputFrom = findViewById(R.id.input_from)
        inputTo = findViewById(R.id.input_to)
        buttonPlayPause = findViewById(R.id.button_play_pause)
        buttonCut = findViewById(R.id.button_cut)
        progress = findViewById(R.id.progress)
        status = findViewById(R.id.status)
        outputFileName = findViewById(R.id.output_file_name)
        outputActions = findViewById(R.id.output_actions)
        buttonOutputFolder = findViewById(R.id.button_output_folder)
        buttonOutputShare = findViewById(R.id.button_output_share)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.button_select_file).setOnClickListener { openFilePicker() }
        buttonPlayPause.setOnClickListener { togglePreviewPlayback() }
        buttonCut.setOnClickListener { cutSelectedMedia() }
        outputFileName.setOnClickListener { openOutputFile() }
        buttonOutputFolder.setOnClickListener { openDownloadsFolder() }
        buttonOutputShare.setOnClickListener { shareOutputFile() }
        setCutEnabled(false)

        timeline.onRangeChanged = { startMs, endMs, fromUser, thumb ->
            if (fromUser) {
                updateTimeFields(startMs, endMs)
                val target = if (thumb == FfmpegRangeSlider.Thumb.END) endMs else startMs
                currentTime.text = formatTime(target)
                seekPreview(target)
            }
        }
        timeline.onPositionChanged = { positionMs, fromUser ->
            currentTime.text = formatTime(positionMs)
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
        if (videoPreview.isPlaying) {
            videoPreview.pause()
            playWhenSeekCompletes = false
            setPlaybackButtonPlaying(false)
        }
        super.onPause()
    }

    @Deprecated("Deprecated Android callback kept for this legacy XML activity.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PICK_MEDIA || resultCode != Activity.RESULT_OK) return

        val uri = data?.data ?: return
        val flags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        try {
            contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: SecurityException) {
        }

        loadSelectedMedia(uri)
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
        selectedMime = contentResolver.getType(uri).orEmpty()
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

        if (selectedMime.startsWith("video/")) {
            videoPreview.visibility = View.VISIBLE
            buttonPlayPause.visibility = View.VISIBLE
            setPlaybackButtonPlaying(false)
            previewPlayer = null
            playWhenSeekCompletes = false
            videoPreview.setVideoURI(uri)
            videoPreview.setOnPreparedListener { player ->
                previewPlayer = player
                player.setOnSeekCompleteListener {
                    if (playWhenSeekCompletes) {
                        playWhenSeekCompletes = false
                        videoPreview.start()
                        setPlaybackButtonPlaying(true)
                    }
                }
                durationMs = player.duration.toLong()
                timeline.setRange(durationMs, 0L, durationMs)
                timeline.setCurrent(0L)
                updateTimeFields(0L, durationMs)
            }
            videoPreview.setOnCompletionListener {
                timeline.setCurrent(durationMs)
                currentTime.text = formatTime(durationMs)
                updatePlayPauseLabel()
            }
        } else {
            buttonPlayPause.visibility = View.GONE
            videoPreview.visibility = View.GONE
            previewPlayer = null
            playWhenSeekCompletes = false
            videoPreview.stopPlayback()
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

        setProcessing(true)
        Thread {
            try {
                val inputFile = copyUriToCache(uri, selectedName)
                val outputName = buildOutputName(selectedName)
                val tempOutput = File(cacheDir, "${System.currentTimeMillis()}_$outputName")
                val arguments = buildFfmpegArguments(inputFile, tempOutput, startMs, endMs)
                val session = FFmpegKit.executeWithArguments(arguments)
                val success = ReturnCode.isSuccess(session.returnCode)
                val logs = session.allLogsAsString.orEmpty().lines().takeLast(3).joinToString("\n")

                runOnUiThread {
                    if (!success) {
                        setProcessing(false)
                        status.text = "Não foi possível cortar com FFmpeg.\n$logs".trim()
                        return@runOnUiThread
                    }

                    val saveResult = saveOutput(uri, tempOutput, outputName)
                    setProcessing(false)
                    if (saveResult.uri != null) {
                        lastOutputUri = saveResult.uri
                        lastOutputMime = selectedMime.ifBlank { "application/octet-stream" }
                        lastOutputName = outputName
                        setSuccessStatus(outputName)
                    } else {
                        status.text = "Corte criado, mas não consegui salvar: ${saveResult.error ?: "erro desconhecido"}"
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

    private fun saveOutput(originalUri: Uri, outputFile: File, outputName: String): SaveResult {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val relativePath = normalizeRelativePath(
                queryRelativePath(originalUri) ?: inferRelativePath(originalUri) ?: Environment.DIRECTORY_DOWNLOADS
            )
            val collection = when {
                relativePath.startsWith(Environment.DIRECTORY_DOWNLOADS) ->
                    MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

                selectedMime.startsWith("video/") -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                selectedMime.startsWith("audio/") -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                else -> MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, outputName)
                put(MediaStore.MediaColumns.MIME_TYPE, selectedMime.ifBlank { "application/octet-stream" })
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            return try {
                val destination = contentResolver.insert(collection, values)
                    ?: return SaveResult(null, "o Android recusou criar o arquivo")
                val outputStream = contentResolver.openOutputStream(destination)
                    ?: return SaveResult(null, "o Android não abriu o arquivo de destino")
                outputStream.use { output ->
                    outputFile.inputStream().use { input -> input.copyTo(output) }
                }
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                contentResolver.update(destination, values, null, null)
                SaveResult(destination, null)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save cut file to $relativePath", e)
                SaveResult(null, e.message)
            }
        }

        return SaveResult(null, "versão do Android sem salvamento automático configurado")
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

    private fun queryRelativePath(uri: Uri): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                    if (index >= 0) return cursor.getString(index)
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun inferRelativePath(uri: Uri): String? {
        val rawPath = uri.toString().substringAfter("raw%3A", missingDelimiterValue = "")
            .ifBlank { uri.toString().substringAfter("raw:", missingDelimiterValue = "") }
        if (rawPath.isBlank()) return null

        val decoded = Uri.decode(rawPath)
        val storagePrefix = "/storage/emulated/0/"
        if (!decoded.startsWith(storagePrefix)) return null

        val relativeFile = decoded.removePrefix(storagePrefix)
        val relativeFolder = relativeFile.substringBeforeLast("/", missingDelimiterValue = "")
        return relativeFolder.takeIf { it.isNotBlank() }
    }

    private fun normalizeRelativePath(relativePath: String): String {
        return if (relativePath.endsWith("/")) relativePath else "$relativePath/"
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

    private fun setCutEnabled(enabled: Boolean) {
        buttonCut.alpha = if (enabled) 1f else 0.45f
        buttonCut.isClickable = enabled
        buttonCut.isFocusable = enabled
    }

    private fun setProcessing(processing: Boolean) {
        progress.visibility = if (processing) View.VISIBLE else View.GONE
        setCutEnabled(!processing && selectedUri != null)
        if (processing) {
            status.movementMethod = null
            status.text = "Cortando..."
            outputFileName.visibility = View.GONE
            outputActions.visibility = View.GONE
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

    private fun updateTimeFields(startMs: Long, endMs: Long) {
        syncingFields = true
        inputFrom.setText(formatTime(startMs))
        inputTo.setText(formatTime(endMs))
        syncingFields = false
    }

    private fun seekPreview(positionMs: Long, updateTimeline: Boolean = true, forPlaybackStart: Boolean = false) {
        if (videoPreview.visibility == View.VISIBLE) {
            val safePosition = positionMs.coerceIn(0L, durationMs)
            val player = previewPlayer
            if (forPlaybackStart && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && player != null) {
                player.seekTo(safePosition, MediaPlayer.SEEK_NEXT_SYNC)
            } else {
                videoPreview.seekTo(safePosition.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            }
            if (updateTimeline) {
                timeline.setCurrent(safePosition)
            }
            currentTime.text = formatTime(safePosition)
            updatePlayPauseLabel()
        }
    }

    private fun togglePreviewPlayback() {
        if (videoPreview.visibility != View.VISIBLE) return

        if (videoPreview.isPlaying) {
            videoPreview.pause()
            playWhenSeekCompletes = false
            setPlaybackButtonPlaying(false)
        } else {
            val startMs = timeline.getStartMs()
            val endMs = timeline.getEndMs()
            val currentMs = videoPreview.currentPosition.toLong()
            val playFromMs = if (currentMs < startMs || currentMs >= endMs) startMs else currentMs
            if (currentMs < startMs || currentMs >= endMs) {
                playWhenSeekCompletes = true
                seekPreview(playFromMs, forPlaybackStart = true)
                setPlaybackButtonPlaying(true)
                if (previewPlayer == null) {
                    videoPreview.postDelayed({
                        if (playWhenSeekCompletes) {
                            playWhenSeekCompletes = false
                            videoPreview.start()
                            setPlaybackButtonPlaying(true)
                        }
                    }, 100L)
                }
                return
            }
            timeline.setCurrent(playFromMs)
            videoPreview.start()
            setPlaybackButtonPlaying(true)
        }
    }

    private fun updatePlayPauseLabel() {
        if (buttonPlayPause.visibility == View.VISIBLE) {
            setPlaybackButtonPlaying(videoPreview.isPlaying)
        }
    }

    private fun setPlaybackButtonPlaying(isPlaying: Boolean) {
        buttonPlayPause.setImageResource(if (isPlaying) R.drawable.ic_ffmpeg_pause else R.drawable.ic_ffmpeg_play)
        buttonPlayPause.contentDescription = if (isPlaying) "Pausar" else "Reproduzir"
    }

    private fun setSuccessStatus(outputName: String) {
        status.movementMethod = null
        status.text = "Arquivo salvo:"
        outputFileName.text = outputName
        outputFileName.visibility = View.VISIBLE
        outputActions.visibility = View.VISIBLE
        cutScroll.post {
            cutScroll.smoothScrollTo(0, outputActions.bottom)
        }
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
        outputFileName.visibility = View.GONE
        outputActions.visibility = View.GONE
    }

    private fun openDownloadsFolder() {
        val intents = listOf(
            Intent(DownloadManager.ACTION_VIEW_DOWNLOADS),
            Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload")
                type = "vnd.android.document/directory"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        )

        for (intent in intents) {
            try {
                startActivity(intent)
                return
            } catch (_: ActivityNotFoundException) {
            } catch (e: Exception) {
                Log.w(TAG, "Could not open Downloads with ${intent.action}", e)
            }
        }

        Toast.makeText(this, "Não consegui abrir a pasta Downloads.", Toast.LENGTH_SHORT).show()
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
        private const val TAG = "FfmpegCut"
    }

    private data class SaveResult(
        val uri: Uri?,
        val error: String?
    )
}
