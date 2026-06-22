package br.gov.sp.pcsp.launcher

import android.app.Activity
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
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

class FfmpegCleanAudioActivity : AppCompatActivity() {

    private lateinit var cleanScroll: ScrollView
    private lateinit var selectedFileText: TextView
    private lateinit var buttonFilterMode: TextView
    private lateinit var helpFilter: TextView
    private lateinit var buttonClean: ImageButton
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var terminalBox: ScrollView
    private lateinit var terminalText: TextView
    private lateinit var outputFileName: TextView
    private lateinit var outputActions: View
    private lateinit var buttonSelectOutputFolder: ImageButton
    private lateinit var arrowInputOutput: View
    private lateinit var buttonSaveToFolder: ImageButton
    private lateinit var buttonOutputFolder: ImageButton
    private lateinit var buttonOutputShare: ImageButton

    private var selectedUri: Uri? = null
    private var selectedName = ""
    private var selectedMode = CleanMode.BALANCED
    private var isProcessing = false
    private var currentSessionId: Long? = null
    private var preSelectedOutputDirUri: Uri? = null
    private var finalOutputDirUri: Uri? = null
    private var tempOutputFile: File? = null
    private var lastOutputUri: Uri? = null
    private var lastOutputName = ""
    private val terminalLines = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        keepContentInsideSystemBars()
        setContentView(R.layout.activity_ffmpeg_clean_audio)

        cleanScroll = findViewById(R.id.clean_scroll)
        selectedFileText = findViewById(R.id.selected_file)
        buttonFilterMode = findViewById(R.id.button_filter_mode)
        helpFilter = findViewById(R.id.help_filter)
        buttonClean = findViewById(R.id.button_clean)
        progress = findViewById(R.id.progress)
        status = findViewById(R.id.status)
        terminalBox = findViewById(R.id.terminal_box)
        terminalText = findViewById(R.id.terminal_text)
        outputFileName = findViewById(R.id.output_file_name)
        outputActions = findViewById(R.id.output_actions)
        buttonSelectOutputFolder = findViewById(R.id.button_select_output_folder)
        arrowInputOutput = findViewById(R.id.arrow_input_output)
        buttonSaveToFolder = findViewById(R.id.button_save_to_folder)
        buttonOutputFolder = findViewById(R.id.button_output_folder)
        buttonOutputShare = findViewById(R.id.button_output_share)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.button_select_audio).setOnClickListener { openAudioPicker() }
        buttonSelectOutputFolder.setOnClickListener {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQUEST_CHOOSE_PRE_OUTPUT_DIR)
        }
        buttonFilterMode.setOnClickListener { showFilterMenu() }
        helpFilter.setOnClickListener { showFilterHelp() }
        buttonClean.setOnClickListener {
            if (isProcessing) cancelCleaning() else cleanSelectedAudio()
        }
        outputFileName.setOnClickListener { openOutputFile() }
        buttonSaveToFolder.setOnClickListener {
            val preUri = preSelectedOutputDirUri
            if (preUri != null) saveTempOutputToUri(preUri) else {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQUEST_CHOOSE_OUTPUT_DIR)
            }
        }
        buttonOutputFolder.setOnClickListener { openOutputFolder() }
        buttonOutputShare.setOnClickListener { shareOutputFile() }
        refreshModeUi()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK) return
        when (requestCode) {
            REQUEST_PICK_AUDIO -> handlePickedAudio(data?.data)
            REQUEST_CHOOSE_PRE_OUTPUT_DIR -> {
                val treeUri = data?.data ?: return
                contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                preSelectedOutputDirUri = treeUri
                buttonSelectOutputFolder.setBackgroundResource(R.drawable.ffmpeg_outline_green_button_bg)
            }
            REQUEST_CHOOSE_OUTPUT_DIR -> data?.data?.let { saveTempOutputToUri(it) }
        }
    }

    override fun onDestroy() {
        currentSessionId?.let { FFmpegKit.cancel(it) }
        super.onDestroy()
    }

    private fun openAudioPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_PICK_AUDIO)
    }

    private fun handlePickedAudio(uri: Uri?) {
        uri ?: return
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Throwable) {
        }
        selectedUri = uri
        selectedName = displayName(uri).ifBlank { "audio" }
        selectedFileText.text = selectedName
        selectedFileText.visibility = View.VISIBLE
        buttonFilterMode.visibility = View.VISIBLE
        helpFilter.visibility = View.VISIBLE
        buttonSelectOutputFolder.visibility = View.VISIBLE
        arrowInputOutput.visibility = View.VISIBLE
        clearOutputResult()
        setCleanEnabled(true)
        status.text = ""
    }

    private fun cleanSelectedAudio() {
        val uri = selectedUri ?: return
        if (!hasSigStorageAccess()) {
            requestSigStorageAccess()
            status.text = "Libere o acesso a todos os arquivos para salvar na pasta SIG."
            return
        }
        clearOutputResult()
        clearTerminal()
        setProcessing(true)
        Thread {
            var inputFile: File? = null
            var outputFile: File? = null
            var keepOutput = false
            try {
                inputFile = copyUriToCache(uri, selectedName)
                val outputName = buildOutputName(selectedName)
                outputFile = File(cacheDir, "clean_${System.currentTimeMillis()}_$outputName")
                val duration = readDuration(inputFile).coerceAtLeast(1L)
                val startedAt = SystemClock.elapsedRealtime()
                val args = buildFfmpegArguments(inputFile, outputFile)
                appendTerminal("ffmpeg ${args.joinToString(" ")}")
                
                val tracker = FfmpegTaskTracker(status, listOf("Preparando áudio", "Limpando áudio"))
                tracker.completeCurrentTask()
                
                val session = executeFfmpegWithProgress(args, duration, tracker)
                val success = ReturnCode.isSuccess(session.returnCode) && outputFile.exists() && outputFile.length() > 0L
                if (success) keepOutput = true
                runOnUiThread {
                    setProcessing(false)
                    if (ReturnCode.isCancel(session.returnCode)) {
                        tracker.fail("Operação cancelada.")
                    } else if (success) {
                        val elapsedMs = SystemClock.elapsedRealtime() - startedAt
                        val elapsedSeconds = (elapsedMs / 1000.0).coerceAtLeast(0.001)
                        val mediaSeconds = duration / 1000.0
                        val efficiency = String.format(Locale.US, "%.2fx", mediaSeconds / elapsedSeconds)
                        tracker.success("Tempo de processamento: ${formatTime(elapsedMs)}\nMídia processada: ${formatTime(duration)}\nEficiência: $efficiency")
                        
                        tempOutputFile = outputFile
                        lastOutputName = outputName
                        outputFileName.text = outputName
                        outputFileName.visibility = View.VISIBLE
                        outputActions.visibility = View.VISIBLE
                        buttonSaveToFolder.visibility = View.VISIBLE
                        buttonOutputFolder.visibility = View.GONE
                        buttonOutputShare.visibility = View.GONE
                        cleanScroll.post { cleanScroll.smoothScrollTo(0, outputActions.bottom) }
                    } else {
                        val tail = session.allLogsAsString.orEmpty().lines().takeLast(3).joinToString(" ")
                        tracker.fail("Não consegui limpar o áudio. ${tail.take(160)}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clean audio", e)
                runOnUiThread {
                    setProcessing(false)
                    status.text = "Erro: ${e.message ?: "falha inesperada"}"
                }
            } finally {
                inputFile?.delete()
                if (!keepOutput) outputFile?.delete()
            }
        }.start()
    }

    private fun buildFfmpegArguments(inputFile: File, outputFile: File): Array<String> {
        return arrayOf(
            "-y",
            "-i", inputFile.absolutePath,
            "-vn",
            "-map", "0:a:0",
            "-af", selectedMode.filter,
            "-c:a", "pcm_s16le",
            "-ar", "16000",
            "-ac", "1",
            "-f", "wav",
            outputFile.absolutePath
        )
    }

    private fun saveTempOutputToUri(treeUri: Uri) {
        val source = tempOutputFile ?: return
        val destDir = DocumentFile.fromTreeUri(this, treeUri)
        if (destDir == null || !destDir.isDirectory) {
            status.text = "Erro: pasta de destino inválida."
            return
        }
        try {
            val document = destDir.createFile("audio/wav", lastOutputName)
                ?: throw IllegalStateException("não consegui criar o arquivo de destino")
            contentResolver.openOutputStream(document.uri, "w")?.use { output ->
                source.inputStream().use { input -> input.copyTo(output, 1024 * 1024) }
            } ?: throw IllegalStateException("não consegui abrir o arquivo de destino")
            lastOutputUri = document.uri
            lastOutputName = document.name ?: lastOutputName
            finalOutputDirUri = treeUri
            status.text = "Arquivo salvo na pasta \"${destDir.name ?: "selecionada"}\""
            outputFileName.text = lastOutputName
            buttonSaveToFolder.visibility = View.GONE
            buttonOutputFolder.visibility = View.VISIBLE
            buttonOutputShare.visibility = View.VISIBLE
            source.delete()
            tempOutputFile = null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save clean audio", e)
            status.text = "Erro ao salvar o áudio limpo."
        }
    }

    private fun showFilterMenu() {
        PopupMenu(this, buttonFilterMode).apply {
            CleanMode.values().forEach { menu.add(it.label) }
            setOnMenuItemClickListener { item ->
                selectedMode = CleanMode.values().first { it.label == item.title.toString() }
                refreshModeUi()
                true
            }
            show()
        }
    }

    private fun showFilterHelp() {
        AlertDialog.Builder(this)
            .setMessage(
                "Equilibrado usa afftdn, bom para chiado e ruído constante.\n\n" +
                    "Forte usa anlmdn, costuma limpar mais, mas demora mais e pode alterar um pouco a voz.\n\n" +
                    "Isso reduz ruído no áudio inteiro. Não separa vozes de música nem remove perfeitamente barulho por cima da fala."
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun executeFfmpegWithProgress(arguments: Array<String>, durationMs: Long, tracker: FfmpegTaskTracker): FFmpegSession {
        val latch = CountDownLatch(1)
        val sessionRef = AtomicReference<FFmpegSession>()
        val startedAt = SystemClock.elapsedRealtime()
        val session = FFmpegKit.executeWithArgumentsAsync(
            arguments,
            { session ->
                sessionRef.set(session)
                latch.countDown()
            },
            { log ->
                val message = log.message?.trim().orEmpty()
                if (message.isNotBlank()) appendTerminal(message)
            },
            { statistics ->
                val percent = ((statistics.time / durationMs.toDouble()) * 100.0).toInt().coerceIn(0, 99)
                tracker.setProgress(percent)
            }
        )
        currentSessionId = session.sessionId
        latch.await()
        return sessionRef.get() ?: session
    }

    private fun appendTerminal(line: String) {
        synchronized(terminalLines) {
            if (terminalLines.isNotEmpty()) terminalLines.append('\n')
            terminalLines.append(line)
        }
        runOnUiThread {
            terminalBox.visibility = View.VISIBLE
            terminalText.text = synchronized(terminalLines) { terminalLines.toString() }
            terminalBox.post { terminalBox.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun clearTerminal() {
        synchronized(terminalLines) { terminalLines.clear() }
        terminalText.text = ""
        terminalBox.visibility = View.GONE
    }

    private fun setProcessing(processing: Boolean) {
        isProcessing = processing
        progress.visibility = if (processing) View.VISIBLE else View.GONE
        buttonClean.setImageResource(if (processing) R.drawable.ic_ffmpeg_cancel_red else R.drawable.ic_ffmpeg_clean_audio)
        buttonClean.setBackgroundResource(if (processing) R.drawable.ffmpeg_outline_red_button_bg else R.drawable.ffmpeg_outline_green_button_bg)
        buttonClean.contentDescription = if (processing) "Cancelar" else "Limpar áudio"
        if (processing) {
            status.text = ""
        } else {
            currentSessionId = null
            setCleanEnabled(selectedUri != null)
        }
    }

    private fun setCleanEnabled(enabled: Boolean) {
        buttonClean.isEnabled = enabled
        buttonClean.alpha = if (enabled) 1f else 0.45f
    }

    private fun cancelCleaning() {
        status.text = "Cancelando..."
        currentSessionId?.let { FFmpegKit.cancel(it) } ?: FFmpegKit.cancel()
    }

    private fun clearOutputResult() {
        lastOutputUri = null
        lastOutputName = ""
        tempOutputFile?.delete()
        tempOutputFile = null
        outputFileName.visibility = View.GONE
        outputActions.visibility = View.GONE
    }

    private fun openOutputFile() {
        val uri = lastOutputUri ?: return
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "audio/wav")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "Não encontrei app para abrir o áudio.", Toast.LENGTH_SHORT).show()
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
        } catch (_: ActivityNotFoundException) {
            val downloadIntent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)
            try {
                startActivity(downloadIntent)
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(this, "Não consegui abrir a pasta.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun shareOutputFile() {
        val uri = lastOutputUri ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/wav"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Compartilhar áudio"))
    }

    private fun copyUriToCache(uri: Uri, displayName: String): File {
        val extension = displayName.substringAfterLast('.', "audio")
        val inputFile = File(cacheDir, "clean_input_${System.currentTimeMillis()}.$extension")
        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(inputFile).use { output -> input.copyTo(output, 1024 * 1024) }
        }
        return inputFile
    }

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index)
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "audio"
    }

    private fun buildOutputName(name: String): String {
        val base = name.substringBeforeLast('.', name).ifBlank { "audio" }
            .replace(Regex("""[\\/:*?"<>|]"""), "_")
        return "${base}_limpo.wav"
    }

    private fun readDuration(file: File): Long {
        return try {
            android.media.MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(file.absolutePath)
                retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 1L
            }
        } catch (_: Exception) {
            1L
        }
    }

    private fun formatEfficiency(processedMs: Long, startedAt: Long): String {
        val elapsed = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(1L)
        return String.format(Locale.US, "%.2fx", processedMs.toDouble() / elapsed.toDouble())
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = ms / 1000
        val milliseconds = ms % 1000
        val seconds = totalSeconds % 60
        val minutes = (totalSeconds / 60) % 60
        val hours = totalSeconds / 3600
        return String.format(Locale.US, "%02d:%02d:%02d.%03d", hours, minutes, seconds, milliseconds)
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

    private fun refreshModeUi() {
        buttonFilterMode.text = "Filtro: ${selectedMode.label}"
    }

    private enum class CleanMode(
        val label: String,
        val filter: String
    ) {
        BALANCED("equilibrado", "afftdn=nf=-25"),
        STRONG("forte", "anlmdn=s=0.00003:p=0.002:r=0.002")
    }

    companion object {
        private const val TAG = "FfmpegCleanAudio"
        private const val REQUEST_PICK_AUDIO = 9301
        private const val REQUEST_CHOOSE_OUTPUT_DIR = 9302
        private const val REQUEST_CHOOSE_PRE_OUTPUT_DIR = 9303
    }
}
