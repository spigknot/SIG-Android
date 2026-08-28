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
import android.provider.OpenableColumns
import android.provider.Settings
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
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

class WhisperActivity : AppCompatActivity() {

    private lateinit var whisperScroll: ScrollView
    private lateinit var selectedFileView: TextView
    private lateinit var buttonModel: TextView
    private lateinit var buttonTranscribe: ImageButton
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var logToggle: TextView
    private lateinit var logText: TextView
    private lateinit var terminalText: TextView
    private lateinit var buttonLanguage: TextView
    private lateinit var buttonBackend: TextView
    private lateinit var outputFileName: TextView
    private lateinit var outputActions: View
    private lateinit var buttonOutputExport: ImageButton
    private lateinit var buttonOutputFolder: ImageButton
    private lateinit var advancedToggle: TextView
    private lateinit var advancedPanel: LinearLayout
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
    private var liveMicStartedAt = 0L

    private val modelLoadLock = Any()
    @Volatile private var currentlyLoadedModelKey: String? = null
    private var pendingAudioAction: (() -> Unit)? = null

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

        whisperScroll = findViewById(R.id.whisper_scroll)
        selectedFileView = findViewById(R.id.selected_file)
        buttonModel = findViewById(R.id.button_model)
        buttonTranscribe = findViewById(R.id.button_transcribe)
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
            selectedModel = available.firstOrNull { it.file.exists() } ?: available.first()
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
    }

    override fun onDestroy() {
        if (whiteRecordingActive) {
            whiteRecordingActive = false
            try { whiteRecordingAudioRecord?.stop() } catch (_: Throwable) {}
            try { whiteRecordingAudioRecord?.release() } catch (_: Throwable) {}
        }
        if (liveMicActive) {
            liveMicActive = false
            try { liveMicAudioRecord?.stop() } catch (_: Throwable) {}
            try { liveMicAudioRecord?.release() } catch (_: Throwable) {}
        }
        try {
            WhisperNative.releaseModel()
        } catch (_: Throwable) {
        }
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
        if (resultCode != Activity.RESULT_OK) return

        when (requestCode) {
            REQUEST_PICK_MEDIA -> data?.let { loadPickedMedia(it) }
            REQUEST_PICK_FOLDER -> data?.let { loadPickedFolder(it) }
            REQUEST_PICK_MODEL -> data?.let { loadPickedModel(it) }
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

    private fun loadPickedMedia(data: Intent) {
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
        val name = queryDisplayName(uri) ?: "audio_${selectedItems.size + 1}"
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

    private fun openModelPicker() {
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
        val name = queryDisplayName(uri)?.takeIf { it.lowercase(Locale.US).endsWith(".bin") }
            ?: "modelo_${System.currentTimeMillis()}.bin"
        val destination = File(modelsDir(), name)
        Thread {
            try {
                modelsDir().mkdirs()
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(destination).use { output -> input.copyTo(output) }
                } ?: throw IllegalStateException("não consegui abrir o modelo selecionado")
                val model = WhisperModel(name.removeSuffix(".bin"), name, destination, null)
                runOnUiThread { selectModel(model) }
            } catch (e: Throwable) {
                Log.e(TAG, "Could not import model", e)
                runOnUiThread { status.text = "Erro ao importar modelo: ${e.message ?: "falha inesperada"}" }
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
                val display = if (model.file.exists()) "✓ ${model.label}" else model.label
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
                if (model.file.exists()) {
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
        selectedModel = model
        buttonModel.text = model.label
        status.text = "${model.label} pronto."
        updateTranscribeEnabled()
    }

    private fun confirmModelDownload(model: WhisperModel) {
        val url = model.downloadUrl ?: return
        AlertDialog.Builder(this)
            .setTitle("Baixar modelo")
            .setMessage("Ainda não temos o modelo ${model.label} no aparelho. Baixar agora?")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Baixar") { _, _ ->
                downloadModelWithProgress(
                    label = model.label,
                    url = url,
                    destination = model.file
                ) { selectModel(model) }
            }
            .show()
    }

    private fun showBackendMenu() {
        PopupMenu(this, buttonBackend).apply {
            WhisperBackend.values()
                .forEach { backend -> menu.add(backend.label) }
            setOnMenuItemClickListener { item ->
                val backend = WhisperBackend.values().first { it.label == item.title.toString() }
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

    private fun transcribeSelectedMedia() {
        val model = selectedModel ?: return
        val items = selectedItems.toList()
        val backend = selectedBackend
        val settings = readAdvancedSettings()
        if (items.isEmpty()) return
        if (!modelFile(model).exists()) {
            model.downloadUrl?.let {
                confirmModelDownload(model)
                return
            }
            status.text = "Escolha um modelo existente."
            updateTranscribeEnabled()
            return
        }
        if (settings.vadFilter && !vadModelFile().exists()) {
            confirmVadDownload { transcribeSelectedMedia() }
            return
        }
        if (backend == WhisperBackend.VULKAN && !supportsVulkanCompute()) {
            status.text = "Este aparelho não anuncia suporte a Vulkan Compute."
            return
        }


        clearOutput()
        cancelRequested = false
        isTranscribing = true
        startTranscriptionTimer()
        setBusy(true)
        val startedAt = SystemClock.elapsedRealtime()
        val liveText = StringBuilder()
        val logLines = StringBuilder()
        val terminalLines = StringBuilder()
        val results = mutableListOf<TranscriptionResult>()

        Thread {
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
                appendLog(logLines, "Sessão: ${sessionDir.name}")
                appendLog(logLines, "Pasta de saída: ${sessionDir.absolutePath}")
                appendLog(logLines, "Modelo: ${model.label}")
                appendLog(logLines, "Backend: ${backend.reportLabel}")
                appendLog(logLines, "Idioma: ${selectedLanguage.label}")
                appendLog(logLines, "Decode: ${settings.describe()}")
                appendLog(logLines, WhisperNative.buildInfo())
                appendLog(logLines, "CPU: ${cpuName()}")

                runOnUiThread {
                    logToggle.visibility = View.VISIBLE
                    updateGlobalLogText()
                    updateTerminalText(terminalLines)
                }

                appendLog(logLines, "Convertendo arquivos para WAV temporário...")
                val convertedItems = prepareWavFiles(items, tempWavDir, terminalLines, logLines)
                checkNotCancelled()
                val totalAudioSeconds = convertedItems.sumOf { it.durationSeconds }
                currentTotalAudioSeconds = totalAudioSeconds

                appendTerminal(terminalLines, "")
                appendTerminal(terminalLines, "loading model: ${modelFile(model).absolutePath}")
                appendLog(logLines, "Carregando modelo...")
                runOnUiThread {
                    setTranscriptionStatus("Carregando modelo...")
                    updateGlobalLogText()
                }
                val modelStartedAt = SystemClock.elapsedRealtime()
                val loaded = synchronized(modelLoadLock) { ensureModelLoaded(model, backend, settings.flashAttention) }
                modelLoadMs = SystemClock.elapsedRealtime() - modelStartedAt
                checkNotCancelled()
                if (!loaded) {
                    val nativeError = WhisperNative.lastError().ifBlank {
                        "não consegui carregar o modelo com ${backend.reportLabel}"
                    }
                    throw IllegalStateException(nativeError)
                }
                appendTerminal(terminalLines, "model loaded in ${formatElapsedCompact(modelLoadMs)}")
                WhisperNative.lastLoadLog().trimEnd()
                    .lineSequence()
                    .filter { it.isNotBlank() }
                    .forEach { appendTerminal(terminalLines, it) }
                appendTerminal(terminalLines, "native backends:\n${WhisperNative.backendInfo()}")
                appendLog(logLines, "Modelo carregado em ${formatElapsedCompact(modelLoadMs)}")
                WhisperNative.lastLoadLog().trimEnd().takeIf { it.isNotBlank() }?.let {
                    appendLog(logLines, "Log de carregamento do modelo:\n$it")
                }
                updateGlobalLogText()
                appendLog(logLines, "Transcrevendo...")
                updateGlobalLogText()

                convertedItems.forEachIndexed { index, converted ->
                    checkNotCancelled()
                    val item = converted.item
                    val fileNumber = index + 1
                    appendTranscriptionHeader(liveText, item.name)
                    val fileText = StringBuilder()
                    appendTerminal(terminalLines, "whisper.cpp: begin ${item.name}")
                    runOnUiThread {
                        updateTerminalText(terminalLines)
                    }

                    appendLog(logLines, "Transcrevendo $fileNumber/${convertedItems.size}: ${item.name}")
                    runOnUiThread {
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
                                    updateTerminalText(terminalLines)
                                }
                            }
                        }
                        return object : WhisperNative.Callback {
                            override fun onSegment(text: String, startMs: Long, endMs: Long) {
                                val segment = text.trim()
                                if (segment.isBlank()) return
                                synchronized(liveText) {
                                    fileText.append(segment).append('\n')
                                    liveText.append(segment).append('\n')
                                }
                                runOnUiThread {
                                    if (liveTranscriptContainer.visibility != View.VISIBLE) {
                                        liveTranscriptContainer.visibility = View.VISIBLE
                                        liveTranscriptClipboardActions.visibility = View.VISIBLE
                                    }
                                    val cur = liveTranscriptText.text.toString()
                                    val sep = if (cur.isEmpty() || cur.endsWith("\n") || cur.endsWith(" ")) "" else " "
                                    liveTranscriptText.append(sep + segment)
                                    liveTranscriptText.setSelection(liveTranscriptText.text.length)
                                }
                                appendTerminal(terminalLines, "${formatTime(startMs)} --> ${formatTime(endMs)}  $segment")
                                updateTerminalThrottled()
                            }

                            override fun onProgress(progress: Int) {
                                val safeProgress = progress.coerceIn(0, 100)
                                appendTerminal(terminalLines, "whisper.cpp: progress $safeProgress%")
                                runOnUiThread {
                                    setTranscriptionStatus(
                                        "Transcrevendo $fileNumber/${convertedItems.size}: ${item.name} ($languageLabel)... $safeProgress%",
                                        safeProgress
                                    )
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
                    runOnUiThread { updateTerminalText(terminalLines) }
                    val transcribeStartedAt = SystemClock.elapsedRealtime()
                    val returnedText = WhisperNative.transcribe(
                        converted.wavFile.absolutePath,
                        selectedLanguage.code,
                        settings.beamSize,
                        settings.bestOf,
                        settings.wordTimestamps,
                        settings.vadFilter,
                        if (settings.vadFilter) vadModelFile().absolutePath else "",
                        makeCallback(selectedLanguage.code)
                    ).trim()
                    appendTerminal(terminalLines, "whisper.cpp: whisper_full_parallel returned in ${formatElapsedCompact(SystemClock.elapsedRealtime() - transcribeStartedAt)}")
                    runOnUiThread { updateTerminalText(terminalLines) }
                    if (returnedText.startsWith("Cancelado:")) throw CancellationException(returnedText.removePrefix("Cancelado:").trim())
                    if (returnedText.startsWith("Erro:")) throw IllegalStateException(returnedText.removePrefix("Erro:").trim())
                    checkNotCancelled()
                    if (fileText.isBlank() && returnedText.isNotBlank()) {
                        synchronized(liveText) {
                            fileText.append(returnedText).append('\n')
                            liveText.append(returnedText).append('\n')
                        }
                        appendTerminal(terminalLines, returnedText)
                    }
                    if (fileText.isBlank()) throw IllegalStateException("transcrição vazia em ${item.name}")

                    appendTranscriptionSeparator(liveText)
                    appendTerminal(terminalLines, "whisper.cpp: end ${item.name}")
                    val text = fileText.toString().trim()
                    val individual = uniqueOutputFile(perFileDir, "${safeBaseName(item.name)}.txt")
                    individual.writeText(text, Charsets.UTF_8)
                    results += TranscriptionResult(item.name, text, individual, converted.durationSeconds)

                    appendLog(logLines, "Concluído: ${item.name}")
                    runOnUiThread {
                        updateGlobalLogText()
                        updateTerminalText(terminalLines)
                    }
                }

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
                htmlFile.writeText(buildHtml(results), Charsets.UTF_8)

                val report = buildReport(model, backend, items.size, totalAudioSeconds, elapsedMs, modelLoadMs)
                appendLog(logLines, report)
                sessionLogFile.writeText(logLines.toString(), Charsets.UTF_8)
                sessionTerminalFile.writeText(snapshotText(terminalLines), Charsets.UTF_8)
                appendGlobalLog(logLines.toString())

                val outputSession = OutputSession(sessionDir, txtFile, htmlFile, sessionLogFile)
                runOnUiThread {
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
            } catch (e: CancellationException) {
                Log.i(TAG, "Transcription cancelled", e)
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
                appendLog(logLines, cancelReport)
                sessionDir?.let {
                    try {
                        File(it, "log.txt").writeText(logLines.toString(), Charsets.UTF_8)
                        File(it, "terminal.txt").writeText(snapshotText(terminalLines), Charsets.UTF_8)
                        appendGlobalLog(logLines.toString())
                    } catch (_: Throwable) {
                    }
                }
                runOnUiThread {
                    isTranscribing = false
                    cancelRequested = false
                    stopTranscriptionTimer()
                    setBusy(false)
                    status.text = cancelReport
                    updateGlobalLogText()
                    updateTerminalText(terminalLines)
                    logToggle.visibility = View.VISIBLE
                    updateTranscribeEnabled()
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Transcription failed", e)
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
                appendLog(logLines, errorReport)
                sessionDir?.let {
                    try {
                        File(it, "log.txt").writeText(logLines.toString(), Charsets.UTF_8)
                        File(it, "terminal.txt").writeText(snapshotText(terminalLines), Charsets.UTF_8)
                        appendGlobalLog(logLines.toString())
                    } catch (_: Throwable) {
                    }
                }
                runOnUiThread {
                    isTranscribing = false
                    cancelRequested = false
                    stopTranscriptionTimer()
                    setBusy(false)
                    status.text = errorReport
                    updateGlobalLogText()
                    updateTerminalText(terminalLines)
                    logToggle.visibility = View.VISIBLE
                    updateTranscribeEnabled()
                }
            }
        }.start()
    }

    private fun convertToWav(inputFile: File, wavFile: File, originalName: String, terminalLines: StringBuilder) {
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
        runOnUiThread { updateTerminalText(terminalLines) }
        val convertSession = executeFfmpegWithTerminal(arguments, terminalLines)
        if (!ReturnCode.isSuccess(convertSession.returnCode) || !wavFile.exists() || wavFile.length() == 0L) {
            val logTail = convertSession.allLogsAsString.orEmpty().lines().takeLast(2).joinToString(" ")
            throw IllegalStateException("falha ao converter áudio. ${logTail.take(90)}")
        }
    }

    private fun executeFfmpegWithTerminal(arguments: Array<String>, terminalLines: StringBuilder): FFmpegSession {
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
                runOnUiThread { updateTerminalText(terminalLines) }
            },
            { statistics ->
                appendTerminal(terminalLines, "ffmpeg stats: time=${statistics.time} size=${statistics.size} bitrate=${statistics.bitrate} speed=${statistics.speed}")
                runOnUiThread { updateTerminalText(terminalLines) }
            }
        )
        latch.await()
        return sessionRef.get()
    }

    private fun prepareWavFiles(
        items: List<MediaItem>,
        tempWavDir: File,
        terminalLines: StringBuilder,
        logLines: StringBuilder
    ): List<ConvertedMediaItem> {
        val copiedInputs = items.mapIndexed { index, item ->
            val fileNumber = index + 1
            appendTerminal(terminalLines, "")
            appendTerminal(terminalLines, "prepare input[$fileNumber/${items.size}]: ${item.name}")
            appendLog(logLines, "Preparando $fileNumber/${items.size}: ${item.name}")
            runOnUiThread {
                status.text = "Preparando $fileNumber/${items.size}: ${item.name}"
                updateGlobalLogText()
                updateTerminalText(terminalLines)
            }

            val copyStartedAt = SystemClock.elapsedRealtime()
            val inputFile = copyUriToCache(item.uri, item.name)
            val wavFile = File(tempWavDir, "${index + 1}_${safeBaseName(item.name)}.wav")
            val inputWavInfo = readWavInfo(inputFile)
            appendTerminal(terminalLines, "copy input done in ${formatElapsedCompact(SystemClock.elapsedRealtime() - copyStartedAt)}")
            runOnUiThread { updateTerminalText(terminalLines) }
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
        appendLog(logLines, "Conversões FFmpeg necessárias: $needsFfmpeg; paralelismo: $parallelism")
        runOnUiThread {
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
                    prepareSingleWav(prepared, terminalLines, logLines)
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
        logLines: StringBuilder
    ): ConvertedMediaItem {
        val prepareStartedAt = SystemClock.elapsedRealtime()
        try {
            if (prepared.wavInfo?.isWhisperReady == true) {
                appendTerminal(terminalLines, "[${prepared.item.name}] ffmpeg skipped: input already WAV PCM s16le mono 16000 Hz")
                prepared.inputFile.copyTo(prepared.wavFile, overwrite = true)
                appendLog(logLines, "FFmpeg dispensado: ${prepared.item.name} já está em WAV 16 kHz mono.")
            } else {
                convertToWav(prepared.inputFile, prepared.wavFile, prepared.item.name, terminalLines)
            }
            appendTerminal(terminalLines, "[${prepared.item.name}] wav preparation done in ${formatElapsedCompact(SystemClock.elapsedRealtime() - prepareStartedAt)}")
            val durationStartedAt = SystemClock.elapsedRealtime()
            val duration = wavDurationSeconds(prepared.wavFile)
            appendTerminal(terminalLines, "[${prepared.item.name}] wav duration read in ${formatElapsedCompact(SystemClock.elapsedRealtime() - durationStartedAt)} (${formatSeconds(duration)})")
            appendLog(logLines, "WAV temporário pronto: ${prepared.item.name}")
            runOnUiThread {
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

    private fun appendLog(builder: StringBuilder, line: String) {
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        synchronized(builder) {
            builder.append("[$stamp] ").append(line).append('\n')
        }
    }

    private fun updateGlobalLogText() {
        if (logText.visibility == View.VISIBLE) {
            logText.text = readGlobalLog()
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
        synchronized(builder) {
            terminalText.text = builder.toString().ifBlank { "$ whisper --aguardando arquivo" }
        }
        terminalText.post {
            val layout = terminalText.layout ?: return@post
            val scrollAmount = layout.getLineTop(terminalText.lineCount) - terminalText.height + terminalText.totalPaddingTop + terminalText.totalPaddingBottom
            terminalText.scrollTo(0, scrollAmount.coerceAtLeast(0))
        }
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

    private fun safeBaseName(name: String): String {
        return name.substringBeforeLast('.', name).ifBlank { "transcricao" }
            .replace(Regex("""[\\/:*?"<>|]"""), "_")
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
        return listOf(
            WhisperModel(
                "tiny",
                "ggml-tiny.bin",
                File(dir, "ggml-tiny.bin"),
                "$MODEL_BASE_URL/ggml-tiny.bin"
            ),
            WhisperModel(
                "base",
                "ggml-base.bin",
                File(dir, "ggml-base.bin"),
                "$MODEL_BASE_URL/ggml-base.bin"
            ),
            WhisperModel(
                "small",
                "ggml-small.bin",
                File(dir, "ggml-small.bin"),
                "$MODEL_BASE_URL/ggml-small.bin"
            ),
            WhisperModel(
                "v3Turbo",
                "ggml-large-v3-turbo.bin",
                File(dir, "ggml-large-v3-turbo.bin"),
                "$MODEL_BASE_URL/ggml-large-v3-turbo.bin"
            )
        )
    }

    private fun vadModelFile(): File {
        val bundled = NativeDependencyManager.sileroModelFile(this)
        return if (bundled.isFile) bundled else File(File(modelsDir(), "silero"), VAD_MODEL_NAME)
    }

    private fun confirmVadDownload(onReady: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle("Baixar VAD")
            .setMessage("O VAD filter usa o modelo Silero. Baixar agora?")
            .setNegativeButton("Não") { _, _ ->
                checkboxVad.isChecked = false
                status.text = "VAD desativado. Toque em transcrever novamente."
            }
            .setPositiveButton("Sim") { _, _ ->
                downloadFile(
                    label = "VAD Silero",
                    url = VAD_MODEL_URL,
                    destination = vadModelFile(),
                    onSuccess = onReady
                )
            }
            .show()
    }

    private fun downloadFile(label: String, url: String, destination: File, onSuccess: () -> Unit) {
        Thread {
            try {
                destination.parentFile?.mkdirs()
                val temp = File(destination.parentFile, "${destination.name}.download")
                URL(url).openConnection().apply {
                    connectTimeout = 15000
                    readTimeout = 30000
                }.let { connection ->
                    val total = connection.contentLengthLong
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
                                    val progressText = if (total > 0L) {
                                        val percent = (copied * 100L / total).coerceIn(0L, 100L)
                                        "$label: baixando $percent%"
                                    } else {
                                        "$label: baixando ${copied / 1048576L} MB"
                                    }
                                    runOnUiThread { status.text = progressText }
                                }
                            }
                        }
                    }
                }
                if (destination.exists()) destination.delete()
                if (!temp.renameTo(destination)) {
                    temp.copyTo(destination, overwrite = true)
                    temp.delete()
                }
                runOnUiThread {
                    status.text = "$label pronto."
                    onSuccess()
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Download failed", e)
                runOnUiThread { status.text = "Erro ao baixar $label: ${e.message ?: "falha inesperada"}" }
            }
        }.start()
    }

    private fun downloadModelWithProgress(label: String, url: String, destination: File, onSuccess: () -> Unit) {
        val progressView = layoutInflater.inflate(R.layout.dialog_model_download, null)
        val statusText = progressView.findViewById<TextView>(R.id.modelDownloadStatusText)
        val progressBar = progressView.findViewById<ProgressBar>(R.id.modelDownloadProgressBar)
        val dialog = AlertDialog.Builder(this)
            .setTitle("Baixando modelo $label")
            .setView(progressView)
            .setCancelable(false)
            .create()
        dialog.show()
        Thread {
            try {
                destination.parentFile?.mkdirs()
                val temp = File(destination.parentFile, "${destination.name}.download")
                URL(url).openConnection().apply {
                    connectTimeout = 15000
                    readTimeout = 30000
                }.let { connection ->
                    val total = connection.contentLengthLong
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
                                    val mb = copied / 1048576L
                                    runOnUiThread {
                                        if (percent >= 0L) {
                                            progressBar.progress = percent.toInt()
                                            statusText.text = "$percent% ($mb MB de ${total / 1048576L} MB)"
                                        } else {
                                            statusText.text = "Baixando... $mb MB"
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (destination.exists()) destination.delete()
                if (!temp.renameTo(destination)) {
                    temp.copyTo(destination, overwrite = true)
                    temp.delete()
                }
                runOnUiThread {
                    dialog.dismiss()
                    status.text = "$label pronto."
                    onSuccess()
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Download failed", e)
                runOnUiThread {
                    dialog.dismiss()
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
    }

    private fun updateTranscribeEnabled() {
        val hasMedia = selectedItems.isNotEmpty()
        buttonTranscribe.visibility = if (hasMedia) View.VISIBLE else View.GONE
        val enabled = !isBusy && hasMedia && selectedModel?.let { modelFile(it).exists() } == true && !whiteRecordingActive && !liveMicActive
        buttonTranscribe.alpha = if (enabled) 1f else 0.45f
        buttonTranscribe.isClickable = enabled
        buttonTranscribe.isFocusable = enabled
    }

    private fun setBusy(busy: Boolean) {
        isBusy = busy
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
        if (!isTranscribing || cancelRequested) return
        cancelRequested = true
        setTranscriptionStatus("Cancelando transcrição...")
        try {
            WhisperNative.cancelTranscription()
        } catch (_: Throwable) {
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
        try {
            WhisperNative.cancelTranscription()
        } catch (_: Throwable) {
        }
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

    private fun ensureModelLoaded(model: WhisperModel, backend: WhisperBackend, flashAttention: Boolean): Boolean {
        val key = "${model.file.absolutePath}|${backend.nativeKind}|$flashAttention"
        if (currentlyLoadedModelKey == key) return true
        if (currentlyLoadedModelKey != null) {
            try { WhisperNative.releaseModel() } catch (_: Throwable) {}
            currentlyLoadedModelKey = null
        }
        val ok = WhisperNative.loadModel(modelFile(model).absolutePath, backend.nativeKind, flashAttention)
        if (ok) {
            currentlyLoadedModelKey = key
        }
        return ok
    }

    private fun releaseLoadedModel() {
        try { WhisperNative.releaseModel() } catch (_: Throwable) {}
        currentlyLoadedModelKey = null
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

    private fun startWhiteRecording() {
        if (liveMicActive || isBusy || isTranscribing) return
        checkAudioPermission {
            val model = selectedModel ?: run {
                status.text = "Selecione um modelo."
                return@checkAudioPermission
            }
            if (!modelFile(model).exists()) {
                confirmModelDownload(model)
                return@checkAudioPermission
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
            status.text = "Gravando áudio do microfone..."

            val stamp = System.currentTimeMillis()
            whiteRecordingPcmFile = File(cacheDir, "whisper_mic_$stamp.pcm")
            whiteRecordingWavFile = File(cacheDir, "whisper_mic_$stamp.wav")

            whiteRecordingThread = Thread {
                recordWhitePcm()
            }.also { it.start() }
        }
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

    private fun recordWhitePcm() {
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

        liveTranscriptContainer.visibility = View.VISIBLE
        liveTranscriptClipboardActions.visibility = View.VISIBLE
        status.text = "Carregando modelo e transcrevendo..."
        progress.visibility = View.VISIBLE

        Thread {
            try {
                val modelLoaded = synchronized(modelLoadLock) {
                    ensureModelLoaded(model, backend, settings.flashAttention)
                }
                if (!modelLoaded) {
                    val err = WhisperNative.lastError().ifBlank { "Falha ao carregar modelo." }
                    runOnUiThread {
                        status.text = "Erro: $err"
                        progress.visibility = View.GONE
                    }
                    return@Thread
                }

                val cb = object : WhisperNative.Callback {
                    override fun onSegment(text: String, startMs: Long, endMs: Long) {
                        val segment = text.trim()
                        if (segment.isNotBlank()) {
                            runOnUiThread {
                                val cur = liveTranscriptText.text.toString()
                                val sep = if (cur.isEmpty() || cur.endsWith("\n") || cur.endsWith(" ")) "" else " "
                                liveTranscriptText.append(sep + segment)
                                liveTranscriptText.setSelection(liveTranscriptText.text.length)
                            }
                        }
                    }
                    override fun onProgress(progress: Int) {
                        runOnUiThread {
                            status.text = "Transcrevendo gravação... $progress%"
                        }
                    }
                    override fun onNativeLog(line: String) {}
                }

                val returnedText = synchronized(modelLoadLock) {
                    WhisperNative.transcribe(
                        wavFile.absolutePath,
                        selectedLanguage.code,
                        settings.beamSize,
                        settings.bestOf,
                        settings.wordTimestamps,
                        settings.vadFilter,
                        if (settings.vadFilter) vadModelFile().absolutePath else "",
                        cb
                    ).trim()
                }

                runOnUiThread {
                    progress.visibility = View.GONE
                    if (returnedText.startsWith("Cancelado:")) {
                        status.text = "Transcrição cancelada."
                    } else if (returnedText.startsWith("Erro:")) {
                        status.text = returnedText
                    } else {
                        status.text = "Transcrição concluída com sucesso!"
                        if (liveTranscriptText.text.isEmpty() && returnedText.isNotBlank()) {
                            liveTranscriptText.setText(returnedText)
                        }
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Error transcribing recorded wav", e)
                runOnUiThread {
                    progress.visibility = View.GONE
                    status.text = "Erro: ${e.message}"
                }
            } finally {
                wavFile.delete()
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
            if (!modelFile(model).exists()) {
                confirmModelDownload(model)
                return@checkAudioPermission
            }

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
            status.text = "Carregando modelo para tempo real..."
            timerHandler.post(recordingTicker)

            val backend = selectedBackend
            val settings = readAdvancedSettings()

            liveMicThread = Thread {
                runLiveMicLoop(model, backend, settings)
            }.also { it.start() }
        }
    }

    private fun stopLiveMicTranscription() {
        if (!liveMicActive) return
        liveMicActive = false
        timerHandler.removeCallbacks(recordingTicker)

        Thread {
            try {
                liveMicThread?.join(3000)
            } catch (_: Throwable) {}
            liveMicThread = null

            runOnUiThread {
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

                status.text = "Transcrição ao vivo finalizada."
            }
        }.start()
    }

    private fun runLiveMicLoop(model: WhisperModel, backend: WhisperBackend, settings: WhisperAdvancedSettings) {
        val sampleRate = 16000
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelConfig, encoding)
        if (minBuffer <= 0) {
            runOnUiThread {
                status.text = "Microfone indisponível."
                stopLiveMicTranscription()
            }
            return
        }

        val modelLoaded = synchronized(modelLoadLock) {
            ensureModelLoaded(model, backend, settings.flashAttention)
        }
        if (!modelLoaded) {
            runOnUiThread {
                val err = WhisperNative.lastError().ifBlank { "Falha ao carregar modelo para tempo real." }
                status.text = "Erro no modelo: $err"
                stopLiveMicTranscription()
            }
            return
        }

        runOnUiThread {
            status.text = "Ouvindo e transcrevendo ao vivo..."
        }

        var recorder: AudioRecord? = null
        val chunkIntervalMillis = 2000L
        val chunkBytes = (sampleRate * 2 * chunkIntervalMillis / 1000).toInt()
        val readBuffer = ByteArray(minBuffer.coerceAtLeast(4096))
        val audioWindow = ByteArrayOutputStream(chunkBytes * 2)
        var chunkIndex = 0

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            runOnUiThread {
                status.text = "Permissão do microfone não concedida."
                stopLiveMicTranscription()
            }
            return
        }

        try {
            val recordBufferSize = maxOf(minBuffer * 2, sampleRate * 2)
            recorder = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, channelConfig, encoding, recordBufferSize)
            liveMicAudioRecord = recorder
            recorder.startRecording()

            while (liveMicActive) {
                val read = recorder.read(readBuffer, 0, readBuffer.size)
                if (read <= 0) continue
                audioWindow.write(readBuffer, 0, read)

                if (audioWindow.size() >= chunkBytes) {
                    val pcmBytes = audioWindow.toByteArray()
                    audioWindow.reset()
                    processLiveChunk(pcmBytes, chunkIndex++, selectedLanguage.code)
                }
            }

            // Processar áudio restante ao parar
            if (audioWindow.size() >= (sampleRate * 2 * 0.5)) {
                val remainingPcm = audioWindow.toByteArray()
                audioWindow.reset()
                processLiveChunk(remainingPcm, chunkIndex++, selectedLanguage.code)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error in live mic loop", e)
            if (liveMicActive) {
                runOnUiThread {
                    status.text = "Erro na gravação ao vivo: ${e.message}"
                }
            }
        } finally {
            try { recorder?.stop() } catch (_: Throwable) {}
            recorder?.release()
            liveMicAudioRecord = null
        }
    }

    private fun processLiveChunk(pcm: ByteArray, index: Int, langCode: String) {
        var sumSquares = 0.0
        val numSamples = pcm.size / 2
        for (i in 0 until numSamples) {
            val sample = (pcm[i * 2].toInt() and 0xFF) or (pcm[i * 2 + 1].toInt() shl 8)
            val sampleShort = sample.toShort()
            sumSquares += sampleShort * sampleShort
        }
        val rms = sqrt(sumSquares / numSamples)
        if (rms < 250) {
            return
        }

        val chunkWav = File(cacheDir, "live_chunk_${System.currentTimeMillis()}_$index.wav")
        try {
            writeWavFile(chunkWav, pcm, 16000)
            val dummyCallback = object : WhisperNative.Callback {
                override fun onSegment(text: String, startMs: Long, endMs: Long) {}
                override fun onProgress(progress: Int) {}
                override fun onNativeLog(line: String) {}
            }

            val text = synchronized(modelLoadLock) {
                WhisperNative.transcribe(
                    chunkWav.absolutePath,
                    langCode,
                    1,
                    1,
                    false,
                    false,
                    "",
                    dummyCallback
                ).trim()
            }

            if (text.isNotBlank() && !text.startsWith("Cancelado:") && !text.startsWith("Erro:")) {
                runOnUiThread {
                    val current = liveTranscriptText.text.toString()
                    val separator = if (current.isEmpty() || current.endsWith("\n") || current.endsWith(" ")) "" else " "
                    liveTranscriptText.append(separator + text)
                    liveTranscriptText.setSelection(liveTranscriptText.text.length)
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to transcribe live chunk $index", e)
        } finally {
            chunkWav.delete()
        }
    }

    private fun checkNotCancelled() {
        if (cancelRequested) throw CancellationException("transcrição cancelada")
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
        private const val REQUEST_PICK_MEDIA = 6101
        private const val REQUEST_PICK_FOLDER = 6102
        private const val REQUEST_PICK_MODEL = 6103
        private const val REQUEST_CHOOSE_OUTPUT_DIR = 6104
        private const val REQUEST_CHOOSE_PRE_OUTPUT_DIR = 6105
        private const val REQUEST_RECORD_AUDIO_PERMISSION = 6106
        private const val SIG_OUTPUT_FOLDER = "SIG"
        private const val WHISPER_OUTPUT_FOLDER = "Whisper"
        private const val GLOBAL_LOG_NAME = "whisper_log.txt"
        private const val VAD_MODEL_NAME = "ggml-silero-v6.2.0.bin"
        private const val VAD_MODEL_URL = "https://huggingface.co/ggml-org/whisper-vad/resolve/main/ggml-silero-v6.2.0.bin"
        private const val MODEL_BASE_URL = "https://pub-6476622beda24c82875cb84f11f660ea.r2.dev/models/whisper"
        private const val DEFAULT_BEAM_SIZE = 5
        private const val DEFAULT_BEST_OF = 5
        private const val TAG = "WhisperActivity"
    }

    private data class WhisperModel(
        val label: String,
        val fileName: String,
        val file: File,
        val downloadUrl: String?
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
