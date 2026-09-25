package br.gov.sp.pcsp.launcher

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.Locale

/** Ferramenta Texto: tradução de texto com o modelo Hy-MT2 (Tencent).
 *
 * Inferência no aparelho via `HyMt2Native` (libsig_llama.so do pacote nativo).
 * Modelo é escolhido num menu (1.25bit / Q4_0 / Q4_K_M) e baixado sob demanda,
 * como no Whisper; backend CPU / GPU OpenCL / GPU Vulkan (NPU fica para depois).
 * O rodapé mostra um log numerado de cada passo para acompanhamento.
 */
class TextoActivity : AppCompatActivity() {

    private lateinit var inputText: EditText
    private lateinit var outputText: EditText
    private lateinit var buttonTranslate: TextView
    private lateinit var buttonModel: TextView
    private lateinit var buttonBackend: TextView
    private lateinit var buttonLanguage: TextView
    private lateinit var status: TextView
    private lateinit var terminalText: TextView
    private lateinit var terminalScroll: ScrollView

    private var selectedModel: HyMt2Model? = null
    private var selectedBackend = HyMt2Backend.CPU
    private var selectedTarget: HyMt2Translator.TargetLanguage = HyMt2Translator.TARGET_LANGUAGES.first()
    private var loadedModelFile: File? = null
    private var loadedBackend: HyMt2Backend? = null
    private var translating = false
    private var logCounter = 0

    /** Modelos oficiais Hy-MT2 (GGUF). URLs diretas do HuggingFace; o Q4_0
     *  (produzido a partir do Q8_0) fica no R2, na base dos modelos do Whisper. */
    private data class HyMt2Model(
        val label: String,
        val fileName: String,
        val file: File,
        val downloadUrl: String
    )

    private enum class HyMt2Backend(val label: String, val shortLabel: String, val nativeKind: Int) {
        CPU("CPU", "CPU", 0),
        OPENCL("GPU (OpenCL)", "OpenCL", 1),
        VULKAN("GPU (Vulkan)", "Vulkan", 2),
        NPU("NPU", "NPU", 3);
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        keepContentInsideSystemBars()
        setContentView(R.layout.activity_texto)

        inputText = findViewById(R.id.input_text)
        outputText = findViewById(R.id.output_text)
        buttonTranslate = findViewById(R.id.button_translate)
        buttonModel = findViewById(R.id.button_model)
        buttonBackend = findViewById(R.id.button_backend)
        buttonLanguage = findViewById(R.id.button_language)
        status = findViewById(R.id.status)
        terminalText = findViewById(R.id.terminal_text)
        terminalScroll = findViewById(R.id.terminal_scroll)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        buttonTranslate.setOnClickListener { translate() }
        buttonModel.setOnClickListener { showModelMenu() }
        buttonBackend.setOnClickListener { showBackendMenu() }
        buttonLanguage.setOnClickListener { showLanguageMenu() }
        findViewById<TextView>(R.id.button_clear_translation).setOnClickListener {
            inputText.setText("")
            outputText.setText("")
            status.text = ""
        }
        findViewById<ImageButton>(R.id.button_copy_translation).setOnClickListener { copyTranslation() }

        val models = officialModels()
        selectedModel = models.first()
        buttonModel.text = selectedModel!!.label
        selectedTarget = savedTargetLanguage()
        buttonLanguage.text = selectedTarget.label
        selectedBackend = savedBackend()
        buttonBackend.text = selectedBackend.shortLabel
        appendLog("Ferramenta Texto pronta.")
        appendLog(
            "Modelo: ${selectedModel!!.label} | Backend: ${selectedBackend.shortLabel} | " +
                "Idioma alvo: ${selectedTarget.label}"
        )
        if (!selectedModel!!.file.exists()) {
            appendLog("Modelo ainda não baixado — toque em Traduzir para baixar.")
        }
    }

    override fun onDestroy() {
        Thread { HyMt2Native.releaseModel() }.start()
        super.onDestroy()
    }

    private fun modelsDir(): File {
        return getExternalFilesDir("hymt2_models")
            ?: File(filesDir, "hymt2_models")
    }

    private fun officialModels(): List<HyMt2Model> {
        val dir = modelsDir().apply { mkdirs() }
        return listOf(
            HyMt2Model(
                "1.25bit",
                "Hy-MT2-1.8B-1.25Bit.gguf",
                File(dir, "Hy-MT2-1.8B-1.25Bit.gguf"),
                "https://huggingface.co/tencent/Hy-MT2-1.8B-1.25Bit-GGUF/resolve/main/Hy-MT2-1.8B-1.25Bit.gguf"
            ),
            HyMt2Model(
                "Q4_0",
                "Hy-MT2-1.8B-Q4_0.gguf",
                File(dir, "Hy-MT2-1.8B-Q4_0.gguf"),
                "https://pub-6476622beda24c82875cb84f11f660ea.r2.dev/models/hymt2/Hy-MT2-1.8B-Q4_0.gguf"
            ),
            HyMt2Model(
                "Q4_K_M",
                "Hy-MT2-1.8B-Q4_K_M.gguf",
                File(dir, "Hy-MT2-1.8B-Q4_K_M.gguf"),
                "https://huggingface.co/tencent/Hy-MT2-1.8B-GGUF/resolve/main/Hy-MT2-1.8B-Q4_K_M.gguf"
            )
        )
    }

    private fun showModelMenu() {
        val models = officialModels()
        PopupMenu(this, buttonModel).apply {
            models.forEachIndexed { index, model ->
                val display = if (model.file.exists()) "✓ ${model.label}" else model.label
                menu.add(0, index + 1, 0, display)
            }
            setOnMenuItemClickListener { item ->
                val model = models.getOrNull(item.itemId - 1) ?: return@setOnMenuItemClickListener true
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

    private fun selectModel(model: HyMt2Model) {
        selectedModel = model
        buttonModel.text = model.label
        appendLog("Modelo selecionado: ${model.label}")
    }

    private fun confirmModelDownload(model: HyMt2Model) {
        AlertDialog.Builder(this)
            .setTitle("Baixar modelo")
            .setMessage("Ainda não temos o modelo ${model.label} no aparelho. Baixar agora?")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Baixar") { _, _ ->
                downloadModelWithProgress(model) { selectModel(model) }
            }
            .show()
    }

    private fun downloadModelWithProgress(model: HyMt2Model, onSuccess: () -> Unit) {
        val progressView = layoutInflater.inflate(R.layout.dialog_model_download, null)
        val statusText = progressView.findViewById<TextView>(R.id.modelDownloadStatusText)
        val progressBar = progressView.findViewById<ProgressBar>(R.id.modelDownloadProgressBar)
        val dialog = AlertDialog.Builder(this)
            .setTitle("Baixando modelo ${model.label}")
            .setView(progressView)
            .setCancelable(false)
            .create()
        dialog.show()
        appendLog("Baixando modelo ${model.label}...")
        Thread {
            try {
                model.file.parentFile?.mkdirs()
                val temp = File(model.file.parentFile, "${model.fileName}.download")
                URL(model.downloadUrl).openConnection().apply {
                    connectTimeout = 20000
                    readTimeout = 120000
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
                if (model.file.exists()) model.file.delete()
                if (!temp.renameTo(model.file)) {
                    temp.copyTo(model.file, overwrite = true)
                    temp.delete()
                }
                runOnUiThread {
                    dialog.dismiss()
                    appendLog("Modelo ${model.label} baixado (${model.file.length() / 1048576L} MB).")
                    onSuccess()
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Download failed", e)
                runOnUiThread {
                    dialog.dismiss()
                    appendLog("ERRO ao baixar ${model.label}: ${e.message ?: "falha inesperada"}")
                    status.text = "Erro ao baixar ${model.label}: ${e.message ?: "falha inesperada"}"
                }
            }
        }.start()
    }

    private fun showBackendMenu() {
        PopupMenu(this, buttonBackend).apply {
            HyMt2Backend.values().forEach { backend -> menu.add(backend.label) }
            setOnMenuItemClickListener { item ->
                val backend = HyMt2Backend.values().first { it.label == item.title.toString() }
                if (backend == HyMt2Backend.NPU) {
                    appendLog("Backend NPU: ainda não implementado (usando ${selectedBackend.shortLabel}).")
                    status.text = "Backend NPU disponível em breve — usando ${selectedBackend.shortLabel}."
                    return@setOnMenuItemClickListener true
                }
                if (backend != selectedBackend) {
                    selectedBackend = backend
                    buttonBackend.text = backend.shortLabel
                    // O modelo precisa recarregar no backend novo na próxima tradução.
                    loadedModelFile = null
                    loadedBackend = null
                    getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit().putString(PREF_BACKEND, backend.name).apply()
                    appendLog("Backend selecionado: ${backend.shortLabel} (modelo será recarregado)")
                }
                true
            }
            show()
        }
    }

    /** Backend persistido; valor desconhecido volta para CPU. */
    private fun savedBackend(): HyMt2Backend {
        val name = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_BACKEND, null)
        return HyMt2Backend.values().firstOrNull { it.name == name } ?: HyMt2Backend.CPU
    }

    /** Backend efetivo para um modelo.
     *
     * O 1.25bit usa tensores STQ1_0, cujo kernel existe SÓ para CPU neste build:
     * na GPU o OpenCL cai para CPU com cópias (mais lento) e o Vulkan pode
     * devolver resultado incorreto (aceitou tensores que não sabe decodificar —
     * visto em campo: saída alucinada, 25/09). Nunca mandamos STQ para GPU;
     * os modelos Q4_0/Q4_K_M são os que aceleram de verdade. */
    private fun effectiveBackend(model: HyMt2Model): HyMt2Backend =
        if (model.fileName.contains("1.25Bit")) HyMt2Backend.CPU else selectedBackend

    private fun showLanguageMenu() {
        PopupMenu(this, buttonLanguage).apply {
            HyMt2Translator.TARGET_LANGUAGES.forEach { language -> menu.add(language.label) }
            setOnMenuItemClickListener { item ->
                val language = HyMt2Translator.TARGET_LANGUAGES.firstOrNull {
                    it.label == item.title.toString()
                } ?: return@setOnMenuItemClickListener true
                selectTargetLanguage(language)
                true
            }
            show()
        }
    }

    private fun selectTargetLanguage(language: HyMt2Translator.TargetLanguage) {
        selectedTarget = language
        buttonLanguage.text = language.label
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(PREF_TARGET_LANGUAGE, language.label).apply()
        appendLog("Idioma alvo: ${language.label} (${language.promptName})")
    }

    /** Idioma-alvo persistido; rótulo desconhecido volta para o padrão (Português). */
    private fun savedTargetLanguage(): HyMt2Translator.TargetLanguage {
        val label = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_TARGET_LANGUAGE, null)
        return HyMt2Translator.TARGET_LANGUAGES.firstOrNull { it.label == label }
            ?: HyMt2Translator.TARGET_LANGUAGES.first()
    }

    private fun translate() {
        if (translating) return
        val source = inputText.text.toString().trim()
        if (source.isBlank()) {
            status.text = "Digite o texto para traduzir."
            return
        }
        val model = selectedModel ?: return
        translating = true
        buttonTranslate.isEnabled = false
        status.text = "Traduzindo..."
        val startedAt = SystemClock.elapsedRealtime()
        val backendVez = effectiveBackend(model)
        appendLog("Traduzindo com ${model.label} (${backendVez.shortLabel}) para ${selectedTarget.label}...")
        if (backendVez != selectedBackend) {
            appendLog(
                "1.25bit não tem kernel de GPU — executando em CPU " +
                    "(escolha Q4_0/Q4_K_M para usar ${selectedBackend.shortLabel})."
            )
        }

        Thread {
            try {
                if (!model.file.exists()) {
                    runOnUiThread {
                        translating = false
                        buttonTranslate.isEnabled = true
                        status.text = "Modelo ausente."
                        appendLog("Modelo ${model.label} não está no aparelho.")
                        confirmModelDownload(model)
                    }
                    return@Thread
                }
                // Recarrega o modelo quando o arquivo OU o backend efetivo mudam.
                if (loadedModelFile != model.file || loadedBackend != backendVez) {
                    runOnUiThread {
                        appendLog("Carregando modelo ${model.label} (${backendVez.shortLabel})...")
                        status.text = "Carregando modelo..."
                    }
                    val loadStart = SystemClock.elapsedRealtime()
                    val ok = HyMt2Native.loadModel(
                        model.file.absolutePath,
                        backendVez.nativeKind,
                        0,
                        8192
                    )
                    if (!ok) {
                        val error = HyMt2Native.lastError()
                        runOnUiThread {
                            translating = false
                            buttonTranslate.isEnabled = true
                            appendLog("ERRO ao carregar o modelo: $error")
                            status.text = "Erro ao carregar o modelo (ver log)."
                        }
                        return@Thread
                    }
                    loadedModelFile = model.file
                    loadedBackend = backendVez
                    val backendInUse = HyMt2Native.backendDescription()
                    runOnUiThread {
                        appendLog(
                            "Modelo carregado em " +
                                "%.1fs.".format(Locale.US, (SystemClock.elapsedRealtime() - loadStart) / 1000.0)
                        )
                        appendLog("Backend em uso: $backendInUse")
                    }
                }

                val prompt = HyMt2Translator.wrapWithChatTemplate(
                    HyMt2Translator.buildUserPrompt(source, selectedTarget.promptName)
                )
                val translation = HyMt2Native.generate(
                    prompt,
                    HyMt2Translator.MAX_TOKENS,
                    HyMt2Translator.TEMPERATURE,
                    HyMt2Translator.TOP_P,
                    HyMt2Translator.TOP_K,
                    HyMt2Translator.REPEAT_PENALTY
                )
                if (translation == null) {
                    val error = HyMt2Native.lastError()
                    runOnUiThread {
                        translating = false
                        buttonTranslate.isEnabled = true
                        appendLog("ERRO na geração: $error")
                        status.text = "Erro na tradução."
                    }
                    return@Thread
                }
                runOnUiThread {
                    translating = false
                    buttonTranslate.isEnabled = true
                    outputText.setText(translation)
                    val seconds = (SystemClock.elapsedRealtime() - startedAt) / 1000.0
                    appendLog(
                        "Tradução pronta em %.1fs.".format(Locale.US, seconds)
                    )
                    status.text = "Tradução pronta."
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Translation failed", e)
                runOnUiThread {
                    translating = false
                    buttonTranslate.isEnabled = true
                    appendLog("ERRO inesperado: ${e.message ?: e.javaClass.simpleName}")
                    status.text = "Erro na tradução."
                }
            }
        }.start()
    }

    private fun copyTranslation() {
        val text = outputText.text.toString()
        if (text.isBlank()) {
            status.text = "Não há tradução para copiar."
            return
        }
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("tradução", text))
        Toast.makeText(this, "Tradução copiada", Toast.LENGTH_SHORT).show()
        appendLog("Tradução copiada para a área de transferência.")
    }

    /** Acrescenta uma linha numerada ao log do rodapé (padrão de logs do usuário). */
    private fun appendLog(line: String) {
        logCounter++
        terminalText.append(String.format(Locale.US, "%2d. %s\n", logCounter, line))
        terminalScroll.post { terminalScroll.fullScroll(View.FOCUS_DOWN) }
    }

    companion object {
        private const val TAG = "TextoActivity"
        private const val PREFS_NAME = "texto_settings"
        private const val PREF_TARGET_LANGUAGE = "target_language"
        private const val PREF_BACKEND = "backend"
    }
}
