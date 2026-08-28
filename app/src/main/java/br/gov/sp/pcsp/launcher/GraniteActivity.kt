package br.gov.sp.pcsp.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
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
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
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
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToLong

/**
 * STT local com Granite Speech 5.0 TurboCTC (ONNX).
 *
 * Fluxo copiado do WhisperActivity, simplificado: modelo único (5.0 Turbo),
 * backend CPU / GPU (NNAPI) / NPU (NNAPI), sem VAD/idioma/timestamps.
 */
class GraniteActivity : AppCompatActivity() {

    private lateinit var graniteScroll: ScrollView
    private lateinit var selectedFileView: TextView
    private lateinit var buttonModel: TextView
    private lateinit var buttonBackend: TextView
    private lateinit var buttonTranscribe: ImageButton
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var outputText: EditText

    private val selectedItems = mutableListOf<MediaItem>()
    private var selectedBackend = GraniteExecutionBackend.CPU
    private var selectedModel = MODEL_TURBO
    private var lastSession: OutputSession? = null
    private var currentTranscriptionText: String = ""
    private var tempSessionDir: File? = null
    private var sourcePopup: PopupWindow? = null
    private var isBusy = false
    private var isTranscribing = false
    @Volatile private var cancelRequested = false
    private val timerHandler = Handler(Looper.getMainLooper())
    private var transcriptionStartedAt = 0L
    private var currentTranscriptionStatus = ""
    private var currentTranscriptionProgress = 0
    private var currentTotalAudioSeconds = 0.0

    private val transcriptionTimer = object : Runnable {
        override fun run() {
            if (!isTranscribing || transcriptionStartedAt <= 0L) return
            refreshTranscriptionStatus()
            timerHandler.postDelayed(this, 1000L)
        }
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        keepContentInsideSystemBars()
        setContentView(R.layout.activity_granite)

        graniteScroll = findViewById(R.id.granite_scroll)
        selectedFileView = findViewById(R.id.selected_file)
        buttonModel = findViewById(R.id.button_model)
        buttonBackend = findViewById(R.id.button_backend)
        buttonTranscribe = findViewById(R.id.button_transcribe)
        progress = findViewById(R.id.progress)
        status = findViewById(R.id.status)
        outputText = findViewById(R.id.output_text)

        buttonBackend.text = selectedBackend.shortLabel
        buttonModel.text = modelLabel(selectedModel)

        val exitHandler = installCancelAndExitGuard(
            isTaskRunning = { isTranscribing || isBusy },
            cancelTask = { cancelRunningTaskForExit() }
        )
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { exitHandler() }
        findViewById<View>(R.id.button_select_media).setOnClickListener { showSourceMenu(it) }
        buttonTranscribe.setOnClickListener {
            if (isTranscribing) cancelTranscription() else transcribeSelectedMedia()
        }
        buttonModel.setOnClickListener { showModelMenu() }
        buttonBackend.setOnClickListener { showBackendMenu() }
        outputText.setText(currentTranscriptionText)
        updateTranscribeEnabled()
    }

    override fun onDestroy() {
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
            REQUEST_PICK_MEDIA -> data?.let { loadPickedMedia(it) }
            REQUEST_PICK_FOLDER -> data?.let { loadPickedFolder(it) }
        }
    }

    // ---- seleção de mídia ----

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
        selectedFileView.text = selectedSummary()
        selectedFileView.visibility = View.VISIBLE
        buttonModel.visibility = View.VISIBLE
        buttonBackend.visibility = View.VISIBLE
        buttonTranscribe.visibility = View.VISIBLE
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
        selectedFileView.text = selectedSummary()
        selectedFileView.visibility = View.VISIBLE
        buttonModel.visibility = View.VISIBLE
        buttonBackend.visibility = View.VISIBLE
        buttonTranscribe.visibility = View.VISIBLE
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

    // ---- menus ----

    private fun showModelMenu() {
        PopupMenu(this, buttonModel).apply {
            menu.add(MODEL_TURBO_LABEL)
            menu.add(MODEL_NAR_LABEL)
            setOnMenuItemClickListener { item ->
                val label = item.title.toString()
                val model = when (label) {
                    MODEL_NAR_LABEL -> MODEL_NAR
                    else -> MODEL_TURBO
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
        buttonModel.text = modelLabel(model)
        status.text = "${modelLabel(model)} pronto."
        updateTranscribeEnabled()
    }

    private fun confirmModelDownload(model: String) {
        val size = when (model) {
            MODEL_NAR -> formatBytes(GraniteNarEngine.packageDownloadBytes())
            else -> formatBytes(GraniteEngine.packageDownloadBytes())
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
        PopupMenu(this, buttonBackend).apply {
            GraniteExecutionBackend.entries
                .forEach { backend -> menu.add(backend.label) }
            setOnMenuItemClickListener { item ->
                val backend = GraniteExecutionBackend.entries.first { it.label == item.title.toString() }
                selectedBackend = backend
                buttonBackend.text = backend.shortLabel
                true
            }
            show()
        }
    }

    // ---- download do pacote ----

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
        setBusy(true)
        Thread {
            try {
                val totalBytes = if (isNar) GraniteNarEngine.packageDownloadBytes() else GraniteEngine.packageDownloadBytes()
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
                    setBusy(false)
                    status.text = "Modelo pronto."
                    onSuccess()
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Granite download failed", e)
                runOnUiThread {
                    dialog.dismiss()
                    setBusy(false)
                    status.text = "Erro ao baixar modelo: ${e.message ?: "falha inesperada"}"
                }
            }
        }.start()
    }

    // ---- transcrição ----

    private fun transcribeSelectedMedia() {
        val items = selectedItems.toList()
        val backend = selectedBackend
        if (items.isEmpty()) return
        // As libs nativas do ONNX Runtime vêm do pacote R2 (1ª execução). Sem ele,
        // oferece o download antes de tentar carregar o modelo.
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
                tempWavDir = File(cacheDir, "granite_wavs_${System.currentTimeMillis()}").apply { mkdirs() }
                appendTerminal(terminalLines, "$ granite --model ${modelLabel(selectedModel)} --input ${items.size} arquivo(s)")
                appendTerminal(terminalLines, "output: ${sessionDir.absolutePath}")
                appendTerminal(terminalLines, "backend: ${backend.reportLabel}")
                appendLog(logLines, "Sessão: ${sessionDir.name}")
                appendLog(logLines, "Modelo: ${modelLabel(selectedModel)}")
                appendLog(logLines, "Backend: ${backend.reportLabel}")
                // UI atualizada apenas em status + progresso; logs vão para o arquivo.

                appendLog(logLines, "Convertendo arquivos para WAV temporário...")
                val convertedItems = prepareWavFiles(items, tempWavDir, terminalLines, logLines)
                checkNotCancelled()
                val totalAudioSeconds = convertedItems.sumOf { it.durationSeconds }
                currentTotalAudioSeconds = totalAudioSeconds

                appendLog(logLines, "Carregando modelo...")
                runOnUiThread { setTranscriptionStatus("Carregando modelo (pode levar 1-2 min na primeira vez)...") }
                val modelStartedAt = SystemClock.elapsedRealtime()
                val loaded = if (selectedModel == MODEL_NAR) {
                    GraniteNarEngine.load(
                        context = this,
                        onLog = { line -> appendTerminal(terminalLines, line) },
                    )
                } else {
                    GraniteEngine.load(
                        context = this,
                        backend = backend,
                        onLog = { line -> appendTerminal(terminalLines, line) },
                        onFallbackPrompt = { reason ->
                            // Pergunta ao usuário (na UI thread) se quer cair para CPU.
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
                modelLoadMs = SystemClock.elapsedRealtime() - modelStartedAt
                checkNotCancelled()
                if (!loaded) {
                    val engineError = if (selectedModel == MODEL_NAR) {
                        GraniteNarEngine.lastError().ifBlank { "não consegui carregar o modelo Granite 4.1 NAR" }
                    } else {
                        GraniteEngine.lastError().ifBlank { "não consegui carregar o modelo com ${backend.reportLabel}" }
                    }
                    throw IllegalStateException(engineError)
                }
                appendLog(logLines, "Modelo carregado em ${formatElapsedCompact(modelLoadMs)}")

                convertedItems.forEachIndexed { index, converted ->
                    checkNotCancelled()
                    val item = converted.item
                    val fileNumber = index + 1
                    appendLog(logLines, "Transcrevendo $fileNumber/${convertedItems.size}: ${item.name}")
                    runOnUiThread {
                        setTranscriptionStatus("Transcrevendo $fileNumber/${convertedItems.size}: ${item.name}")
                    }
                    appendTranscriptionHeader(liveText, item.name)
                    val fileText = StringBuilder()
                    val text = if (selectedModel == MODEL_NAR) {
                        GraniteNarEngine.transcribeFile(
                            wavFile = converted.wavFile,
                            onProgress = { percent ->
                                runOnUiThread {
                                    setTranscriptionStatus(
                                        "Transcrevendo $fileNumber/${convertedItems.size}: ${item.name}... $percent%",
                                        percent
                                    )
                                }
                            }
                        )
                    } else {
                        GraniteEngine.transcribeFile(
                            wavFile = converted.wavFile,
                            onProgress = { percent ->
                                runOnUiThread {
                                    setTranscriptionStatus(
                                        "Transcrevendo $fileNumber/${convertedItems.size}: ${item.name}... $percent%",
                                        percent
                                    )
                                }
                            }
                        )
                    }
                    checkNotCancelled()
                    if (text.isBlank()) throw IllegalStateException("transcrição vazia em ${item.name}")
                    fileText.append(text).append('\n')
                    synchronized(liveText) {
                        liveText.append(text).append('\n')
                    }
                    appendTranscriptionSeparator(liveText)
                    appendTerminal(terminalLines, text)
                    val individual = uniqueOutputFile(perFileDir, "${safeBaseName(item.name)}.txt")
                    individual.writeText(text, Charsets.UTF_8)
                    results += TranscriptionResult(item.name, text, individual, converted.durationSeconds)
                    appendLog(logLines, "Concluído: ${item.name}")
                }

                releaseModel()
                tempWavDir?.deleteRecursively()
                val elapsedMs = SystemClock.elapsedRealtime() - startedAt
                val txtFile = File(sessionDir, "transcricoes.txt")
                val htmlFile = File(sessionDir, "transcricoes.html")
                val sessionLogFile = File(sessionDir, "log.txt")
                val sessionTerminalFile = File(sessionDir, "terminal.txt")
                txtFile.writeText(liveText.toString(), Charsets.UTF_8)
                htmlFile.writeText(buildHtml(results), Charsets.UTF_8)
                val report = buildReport(backend, items.size, totalAudioSeconds, elapsedMs, modelLoadMs)
                appendLog(logLines, report)
                sessionLogFile.writeText(logLines.toString(), Charsets.UTF_8)
                sessionTerminalFile.writeText(snapshotText(terminalLines), Charsets.UTF_8)

                val outputSession = OutputSession(sessionDir, txtFile, htmlFile, sessionLogFile)
                runOnUiThread {
                    lastSession = outputSession
                    tempSessionDir = sessionDir
                    isTranscribing = false
                    cancelRequested = false
                    stopTranscriptionTimer()
                    setBusy(false)
                    outputText.visibility = View.VISIBLE
                    status.text = "Transcrição concluída com sucesso!"
                    updateOutputText()
                    graniteScroll.post { graniteScroll.smoothScrollTo(0, outputText.bottom) }
                    updateTranscribeEnabled()
                }
            } catch (e: CancellationException) {
                Log.i(TAG, "Granite transcription cancelled", e)
                try { releaseModel() } catch (_: Throwable) {}
                tempWavDir?.deleteRecursively()
                runOnUiThread {
                    isTranscribing = false
                    cancelRequested = false
                    stopTranscriptionTimer()
                    setBusy(false)
                    status.text = "Transcrição cancelada pelo usuário."
                    updateTranscribeEnabled()
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Granite transcription failed", e)
                try { releaseModel() } catch (_: Throwable) {}
                tempWavDir?.deleteRecursively()
                val errorMessage = e.message ?: "falha inesperada"
                val detail = if (selectedModel == MODEL_NAR) {
                    GraniteNarEngine.lastError().ifBlank { errorMessage }
                } else {
                    GraniteEngine.lastError().ifBlank { errorMessage }
                }
                appendLog(logLines, "Erro: $detail")
                appendTerminal(terminalLines, "ERROR: $detail")
                // Grava o log mesmo em falha, para diagnóstico.
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
                    setBusy(false)
                    status.text = "Erro: $errorMessage"
                    updateOutputText()
                    updateTranscribeEnabled()
                }
            }
        }.start()
    }

    private fun convertToWav(inputFile: File, wavFile: File, originalName: String, terminalLines: StringBuilder) {
        val arguments = arrayOf(
            "-y", "-i", inputFile.absolutePath,
            "-vn", "-ar", "16000", "-ac", "1", "-c:a", "pcm_s16le", "-f", "wav",
            wavFile.absolutePath
        )
        appendTerminal(terminalLines, "# original: $originalName")
        appendTerminal(terminalLines, "ffmpeg ${arguments.joinToString(" ")}")
        val convertSession = executeFfmpegWithTerminal(arguments, terminalLines)
        if (!ReturnCode.isSuccess(convertSession.returnCode) || !wavFile.exists() || wavFile.length() == 0L) {
            val logTail = convertSession.allLogsAsString.orEmpty().lines().takeLast(2).joinToString(" ")
            throw IllegalStateException("conversão falhou: $logTail")
        }
    }

    private fun executeFfmpegWithTerminal(arguments: Array<String>, terminalLines: StringBuilder): FFmpegSession {
        val session = FFmpegKit.execute(arguments.joinToString(" "))
        appendTerminal(terminalLines, session.allLogsAsString.orEmpty().trimEnd())
        return session
    }

    private fun prepareWavFiles(
        items: List<MediaItem>,
        tempWavDir: File,
        terminalLines: StringBuilder,
        logLines: StringBuilder
    ): List<ConvertedMediaItem> {
        val prepared = mutableListOf<ConvertedMediaItem>()
        items.forEachIndexed { index, item ->
            checkNotCancelled()
            runOnUiThread {
                setTranscriptionStatus("Convertendo ${index + 1}/${items.size}: ${item.name}")
            }
            appendLog(logLines, "Convertendo ${index + 1}/${items.size}: ${item.name}")
            val inputFile = copyUriToCache(item.uri, "input_${index}_${System.currentTimeMillis()}")
            val wavFile = File(tempWavDir, "audio_${index}.wav")
            convertToWav(inputFile, wavFile, item.name, terminalLines)
            val wavInfo = readWavInfo(wavFile)
            prepared += ConvertedMediaItem(index, item, wavFile, wavInfo.durationSeconds)
        }
        return prepared
    }

    private fun copyUriToCache(uri: Uri, name: String): File {
        val originalName = queryDisplayName(uri)
        val ext = originalName?.substringAfterLast('.', "")?.takeIf { it.isNotBlank() && it.length <= 5 }
        val dest = File(cacheDir, if (ext != null) "$name.$ext" else name)
        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(dest).use { output -> input.copyTo(output) }
        } ?: throw IllegalStateException("não consegui abrir $uri")
        return dest
    }

    private fun readWavInfo(file: File): WavInfo {
        RandomAccessFile(file, "r").use { raf ->
            // RIFF header
            val riff = ByteArray(4)
            raf.readFully(riff)
            if (String(riff) != "RIFF") throw IllegalStateException("WAV inválido")
            raf.skipBytes(4) // size
            val wave = ByteArray(4)
            raf.readFully(wave)
            if (String(wave) != "WAVE") throw IllegalStateException("WAV sem WAVE")
            // fmt chunk
            raf.skipBytes(4) // "fmt "
            val fmtSize = leInt(raf)
            val audioFormat = leShort(raf)
            val channels = leShort(raf)
            val sampleRate = leInt(raf)
            raf.skipBytes(6) // byteRate, blockAlign
            val bitsPerSample = leShort(raf)
            raf.skipBytes((fmtSize - 16).coerceAtLeast(0))
            // data chunk
            var dataSize = 0L
            while (raf.filePointer < raf.length()) {
                val id = ByteArray(4)
                raf.readFully(id)
                val size = leInt(raf)
                if (String(id) == "data") { dataSize = size.toLong(); break }
                raf.skipBytes(size.coerceAtLeast(0))
            }
            val durationSeconds = if (sampleRate > 0) dataSize.toDouble() / (sampleRate * channels * (bitsPerSample / 8)) else 0.0
            return WavInfo(audioFormat, channels, sampleRate, bitsPerSample, dataSize, durationSeconds)
        }
    }

    private fun leShort(raf: RandomAccessFile): Int {
        val b = ByteArray(2)
        raf.readFully(b)
        return (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8)
    }

    private fun leInt(raf: RandomAccessFile): Int {
        val b = ByteArray(4)
        raf.readFully(b)
        return (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8) or
            ((b[2].toInt() and 0xFF) shl 16) or ((b[3].toInt() and 0xFF) shl 24)
    }

    // ---- saída ----

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

    private fun checkNotCancelled() {
        if (cancelRequested) throw CancellationException("cancelado pelo usuário")
    }

    private fun cancelTranscription() {
        cancelRequested = true
        status.text = "Cancelando..."
    }

    private fun cancelRunningTaskForExit() {
        cancelRequested = true
    }

    private fun setBusy(busy: Boolean) {
        isBusy = busy
        updateTranscribeEnabled()
    }

    private fun updateTranscribeEnabled() {
        val ready = !isBusy && !isTranscribing && selectedItems.isNotEmpty()
        buttonTranscribe.isEnabled = ready
        buttonTranscribe.alpha = if (ready) 1f else 0.45f
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
        status.visibility = View.VISIBLE
        if (progressValue != null) {
            currentTranscriptionProgress = progressValue
            progress.visibility = View.VISIBLE
            progress.progress = progressValue
        }
        status.text = text
    }

    private fun clearOutput() {
        lastSession = null
        tempSessionDir = null
        outputText.visibility = View.GONE
        progress.visibility = View.GONE
        progress.progress = 0
    }

    private fun appendLog(logLines: StringBuilder, line: String) {
        logLines.append(line).append('\n')
    }

    private fun appendTerminal(terminalLines: StringBuilder, text: String) {
        terminalLines.append(text).append('\n')
    }

    private fun appendTranscriptionHeader(liveText: StringBuilder, name: String) {
        liveText.append("### ").append(name).append('\n')
    }

    private fun appendTranscriptionSeparator(liveText: StringBuilder) {
        liveText.append('\n')
    }

    private fun updateOutputText() {
        outputText.setText(currentTranscriptionText)
        outputText.visibility = View.VISIBLE
    }

    private fun snapshotText(sb: StringBuilder): String {
        val s = sb.toString()
        return s.takeLast(20000)
    }

    private fun createSessionDir(): File {
        val root = graniteOutputRoot()
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

    private fun graniteOutputRoot(): File {
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

    private fun uniqueOutputFile(outputDir: File, outputName: String): File {
        var candidate = File(outputDir, outputName)
        var suffix = 2
        while (candidate.exists()) {
            candidate = File(outputDir, "${outputName.removeSuffix(".txt")}_$suffix.txt")
            suffix++
        }
        return candidate
    }

    private fun safeBaseName(name: String): String {
        val cleaned = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return cleaned.ifBlank { "arquivo" }.removeSuffix(".txt")
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun formatBytes(bytes: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024.0 && unit < units.lastIndex) {
            value /= 1024.0
            unit++
        }
        return if (unit == 0) "${bytes}B" else String.format(Locale.US, "%.1f%s", value, units[unit])
    }

    private fun formatElapsedCompact(ms: Long): String {
        val totalSec = ms / 1000.0
        return if (totalSec < 60) String.format(Locale.US, "%.1fs", totalSec)
        else String.format(Locale.US, "%dm%02ds", (totalSec / 60).toInt(), (totalSec % 60).toInt())
    }

    private fun buildReport(
        backend: GraniteExecutionBackend,
        fileCount: Int,
        totalAudioSeconds: Double,
        elapsedMs: Long,
        modelLoadMs: Long
    ): String {
        val elapsedSeconds = (elapsedMs / 1000.0).coerceAtLeast(0.001)
        val efficiency = totalAudioSeconds / elapsedSeconds
        return listOf(
            "Modelo: Granite 5.0 Turbo (${backend.reportLabel})",
            "Arquivos: $fileCount",
            "Total de áudio processado: ${formatSeconds(totalAudioSeconds)}",
            "Tempo de carregamento do modelo: ${formatElapsedCompact(modelLoadMs)}",
            "Tempo de processamento: ${formatSeconds(elapsedSeconds)}",
            "Eficiência: ${String.format(Locale.US, "%.2fx", efficiency)}"
        ).joinToString("\n")
    }

    private fun formatSeconds(seconds: Double): String {
        return if (seconds < 60) String.format(Locale.US, "%.1fs", seconds)
        else String.format(Locale.US, "%dm%02ds", (seconds / 60).toInt(), (seconds % 60).toInt())
    }

    private fun buildHtml(results: List<TranscriptionResult>): String {
        val rows = results.joinToString("\n") { result ->
            """<div class="item"><h3>${escapeHtml(result.fileName)}</h3><pre>${escapeHtml(result.text)}</pre></div>"""
        }
        return """<!DOCTYPE html>
<html lang="pt-BR"><head><meta charset="utf-8"><title>Transcrições Granite</title>
<style>body{font-family:sans-serif;background:#0d1117;color:#e6edf3;padding:20px}
h1{color:#FFFFC928}.item{margin-bottom:24px}h3{color:#FFFFC928;margin-bottom:4px}
pre{white-space:pre-wrap;background:#161b22;padding:12px;border-radius:8px}</style></head>
<body><h1>Transcrições Granite</h1>$rows</body></html>"""
    }

    private fun escapeHtml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    companion object {
        private const val TAG = "GraniteActivity"
        private const val REQUEST_PICK_MEDIA = 1001
        private const val REQUEST_PICK_FOLDER = 1002
        private const val SIG_OUTPUT_FOLDER = "SIG"
        private const val GRANITE_OUTPUT_FOLDER = "Granite"
        private const val MODEL_TURBO = "turbo"
        private const val MODEL_NAR = "nar"
        private const val MODEL_TURBO_LABEL = "Granite 5.0 Turbo"
        private const val MODEL_NAR_LABEL = "Granite 4.1 NAR"
    }

    private fun modelLabel(model: String): String = when (model) {
        MODEL_NAR -> MODEL_NAR_LABEL
        else -> MODEL_TURBO_LABEL
    }

    private fun releaseModel() {
        if (selectedModel == MODEL_NAR) GraniteNarEngine.release() else GraniteEngine.release()
    }
}

private data class MediaItem(
    val uri: Uri,
    val name: String
)

private data class ConvertedMediaItem(
    val index: Int,
    val item: MediaItem,
    val wavFile: File,
    val durationSeconds: Double
)

private data class WavInfo(
    val audioFormat: Int,
    val channels: Int,
    val sampleRate: Int,
    val bitsPerSample: Int,
    val dataSize: Long,
    val durationSeconds: Double
)

private data class OutputSession(
    val dir: File,
    val txtFile: File,
    val htmlFile: File,
    val logFile: File
)

private data class TranscriptionResult(
    val fileName: String,
    val text: String,
    val file: File,
    val durationSeconds: Double
)
