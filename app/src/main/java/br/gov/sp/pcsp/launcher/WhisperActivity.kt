package br.gov.sp.pcsp.launcher

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
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
    private val transcriptionTimer = object : Runnable {
        override fun run() {
            if (!isTranscribing || transcriptionStartedAt <= 0L) return
            refreshTranscriptionStatus()
            timerHandler.postDelayed(this, 1000L)
        }
    }

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
            false
        }
        logText.setOnTouchListener { view, event ->
            view.parent.requestDisallowInterceptTouchEvent(true)
            if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                view.parent.requestDisallowInterceptTouchEvent(false)
            }
            false
        }
        buttonLanguage.text = selectedLanguage.shortLabel
        buttonBackend.text = selectedBackend.shortLabel
        logToggle.visibility = View.VISIBLE
        val exitHandler = installCancelAndExitGuard(
            isTaskRunning = { isTranscribing || isBusy },
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
        try {
            WhisperNative.releaseModel()
        } catch (_: Throwable) {
        }
        super.onDestroy()
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
        val flags = data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
        try {
            contentResolver.takePersistableUriPermission(treeUri, flags)
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
        PopupMenu(this, buttonModel).apply {
            officialModels().forEach { model -> menu.add(model.label) }
            menu.add("Outro...")
            setOnMenuItemClickListener { item ->
                val label = item.title.toString()
                if (label == "Outro...") {
                    openModelPicker()
                    return@setOnMenuItemClickListener true
                }
                val model = officialModels().first { it.label == label }
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
            .setMessage("Baixar ${model.label} para ${modelsDir().absolutePath}?")
            .setNegativeButton("Não", null)
            .setPositiveButton("Sim") { _, _ ->
                downloadFile(
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
                appendTerminal(terminalLines, "backend: ${backend.reportLabel}")
                appendTerminal(terminalLines, "decode: ${settings.describe()}")
                appendTerminal(terminalLines, WhisperNative.buildInfo())
                appendLog(logLines, "Sessão: ${sessionDir.name}")
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
                    updateTerminalText(terminalLines)
                }
                val modelStartedAt = SystemClock.elapsedRealtime()
                val loaded = WhisperNative.loadModel(modelFile(model).absolutePath, backend.nativeKind, settings.flashAttention)
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
                appendLog(logLines, "Native backends:\n${WhisperNative.backendInfo()}")
                runOnUiThread { updateTerminalText(terminalLines) }

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

                WhisperNative.releaseModel()
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
                    
                    status.text = "Transcrição concluída com sucesso! Clique no disquete para salvar."
                    updateGlobalLogText()
                    updateTerminalText(terminalLines)
                    whisperScroll.post { whisperScroll.smoothScrollTo(0, outputActions.bottom) }
                    updateTranscribeEnabled()
                }
            } catch (e: CancellationException) {
                Log.i(TAG, "Transcription cancelled", e)
                try {
                    WhisperNative.releaseModel()
                } catch (_: Throwable) {
                }
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
                try {
                    WhisperNative.releaseModel()
                } catch (_: Throwable) {
                }
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

    private fun toggleLog() {
        if (logText.visibility == View.VISIBLE) {
            logText.visibility = View.GONE
        } else {
            logText.text = readGlobalLog()
            logText.visibility = View.VISIBLE
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
        val root = File(File(Environment.getExternalStorageDirectory(), SIG_OUTPUT_FOLDER), WHISPER_OUTPUT_FOLDER).apply { mkdirs() }
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
        val file = globalLogFile()
        val body = if (file.exists() && file.length() > 0L) {
            file.readText(Charsets.UTF_8)
        } else {
            "Sem log geral ainda."
        }
        val sessionCount = "SESSÃO WHISPER -".toRegex().findAll(body).count()
        return "LOG GLOBAL - SIG/Whisper/$GLOBAL_LOG_NAME\nSessões registradas: $sessionCount\nMais recentes primeiro; role para ver sessões anteriores.\n\n$body"
    }

    private fun globalLogFile(): File {
        val root = File(File(Environment.getExternalStorageDirectory(), SIG_OUTPUT_FOLDER), WHISPER_OUTPUT_FOLDER)
        if (!root.exists()) root.mkdirs()
        return File(root, GLOBAL_LOG_NAME)
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
                "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin"
            ),
            WhisperModel(
                "base",
                "ggml-base.bin",
                File(dir, "ggml-base.bin"),
                "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin"
            ),
            WhisperModel(
                "small",
                "ggml-small.bin",
                File(dir, "ggml-small.bin"),
                "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.bin"
            ),
            WhisperModel(
                "large-v3-turbo",
                "ggml-large-v3-turbo.bin",
                File(dir, "ggml-large-v3-turbo.bin"),
                "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo.bin"
            )
        )
    }

    private fun vadModelFile(): File {
        return File(File(modelsDir(), "silero"), VAD_MODEL_NAME)
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
        val enabled = !isBusy && selectedItems.isNotEmpty() && selectedModel?.let { modelFile(it).exists() } == true
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
        private const val SIG_OUTPUT_FOLDER = "SIG"
        private const val WHISPER_OUTPUT_FOLDER = "Whisper"
        private const val GLOBAL_LOG_NAME = "whisper_log.txt"
        private const val VAD_MODEL_NAME = "ggml-silero-v6.2.0.bin"
        private const val VAD_MODEL_URL = "https://huggingface.co/ggml-org/whisper-vad/resolve/main/ggml-silero-v6.2.0.bin"
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
