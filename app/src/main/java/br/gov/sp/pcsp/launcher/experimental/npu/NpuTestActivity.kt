package br.gov.sp.pcsp.launcher.experimental.npu

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import br.gov.sp.pcsp.launcher.R
import br.gov.sp.pcsp.launcher.keepContentInsideSystemBars
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NpuTestActivity : AppCompatActivity() {
    private lateinit var diagnosticText: TextView
    private lateinit var modelStatus: TextView
    private lateinit var logText: TextView
    private lateinit var modelGroup: RadioGroup
    private lateinit var packageManager: NpuPackageManager
    private lateinit var downloader: NpuPackageDownloader
    private var models: List<NpuModelDescriptor> = emptyList()
    private var selectedModel: NpuModelDescriptor? = null
    private var report = ""
    private var downloadInProgress = false

    private val packagePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        val model = selectedModel ?: return@registerForActivityResult
        setModelActionsEnabled(false)
        modelStatus.text = "Validando e importando ${model.displayName}..."
        Thread {
            val result = runCatching { packageManager.importPackage(model, uri) }
            runOnUiThread {
                setModelActionsEnabled(true)
                modelStatus.text = result.fold(
                    onSuccess = { "${model.displayName}: pacote instalado e SHA-256 verificado." },
                    onFailure = { "Falha ao importar: ${it.message ?: it.javaClass.simpleName}" }
                )
                appendLog(modelStatus.text.toString())
                refreshModelRows()
            }
        }.start()
    }

    private val audioPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        findViewById<TextView>(R.id.npu_audio_status).text = "Selecionado: ${displayName(uri)}"
        appendLog("Áudio selecionado para teste futuro: ${displayName(uri)}")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        keepContentInsideSystemBars()
        setContentView(R.layout.activity_npu_test)

        diagnosticText = findViewById(R.id.npu_diagnostic_text)
        modelStatus = findViewById(R.id.npu_model_status)
        logText = findViewById(R.id.npu_log)
        modelGroup = findViewById(R.id.npu_model_group)
        packageManager = NpuPackageManager(this)
        downloader = NpuPackageDownloader(cacheDir)
        models = runCatching { NpuModelManifest.load(this) }.getOrElse {
            modelStatus.text = "Manifesto inválido: ${it.message}"
            emptyList()
        }

        findViewById<View>(R.id.button_npu_back).setOnClickListener { finish() }
        findViewById<Button>(R.id.button_run_npu_diagnostic).setOnClickListener { runDiagnostic() }
        findViewById<Button>(R.id.button_copy_npu_report).setOnClickListener { copyReport() }
        findViewById<Button>(R.id.button_export_npu_report).setOnClickListener { exportReport() }
        findViewById<Button>(R.id.button_import_npu_package).setOnClickListener {
            if (selectedModel == null) toast("Selecione Tiny, Base ou Turbo.")
            else packagePicker.launch(arrayOf("application/zip", "application/octet-stream"))
        }
        findViewById<Button>(R.id.button_download_npu_package).setOnClickListener {
            if (downloadInProgress) downloader.cancel() else downloadSelectedPackage()
        }
        findViewById<Button>(R.id.button_verify_npu_package).setOnClickListener { verifySelectedPackage() }
        findViewById<Button>(R.id.button_delete_npu_package).setOnClickListener { confirmDeletePackage() }
        findViewById<Button>(R.id.button_pick_npu_audio).setOnClickListener {
            audioPicker.launch(arrayOf("audio/*", "video/*"))
        }

        findViewById<Spinner>(R.id.npu_language_spinner).adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf("Idioma: automático", "Idioma: português", "Idioma: inglês", "Idioma: espanhol")
        )
        refreshModelRows()
        runDiagnostic()
    }

    private fun runDiagnostic() {
        val button = findViewById<Button>(R.id.button_run_npu_diagnostic)
        button.isEnabled = false
        diagnosticText.text = "Executando diagnóstico seguro..."
        Thread {
            val value = runCatching { NpuDiagnostics.collect(this) }.getOrElse {
                "Falha controlada no diagnóstico: ${it.message ?: it.javaClass.simpleName}"
            }
            runOnUiThread {
                report = value
                diagnosticText.text = value
                appendLog("Diagnóstico concluído. HTP não é considerado ativo sem inicialização e execução oficiais.")
                button.isEnabled = true
            }
        }.start()
    }

    private fun refreshModelRows() {
        val selectedId = selectedModel?.id
        modelGroup.removeAllViews()
        models.forEach { model ->
            val state = packageManager.state(model)
            val radio = RadioButton(this).apply {
                id = View.generateViewId()
                text = "${model.displayName} — $state\n${model.checkpoint}; ${model.melBins} Mel; ${model.encoderOutputFrames}x${model.encoderOutputSize}"
                setTextColor(getColor(android.R.color.white))
                textSize = 12f
                tag = model.id
                isChecked = model.id == selectedId
                setPadding(0, 5, 0, 5)
            }
            modelGroup.addView(radio)
        }
        modelGroup.setOnCheckedChangeListener { group, checkedId ->
            val id = group.findViewById<RadioButton>(checkedId)?.tag as? String
            selectedModel = models.firstOrNull { it.id == id }
            selectedModel?.let {
                modelStatus.text = packageDetails(it)
                findViewById<Button>(R.id.button_download_npu_package).isEnabled = it.downloadUrl != null && it.packageSha256 != null
            }
        }
    }

    private fun packageDetails(model: NpuModelDescriptor): String {
        return "${model.displayName}: ${packageManager.state(model)}. Encoder ${model.encoderRuntime}; " +
            "decoder ${model.decoderRuntime}; pacote local separado do GGML."
    }

    private fun verifySelectedPackage() {
        val model = selectedModel ?: return toast("Selecione um modelo.")
        Thread {
            val result = runCatching { packageManager.validateInstalled(model) }
            runOnUiThread {
                modelStatus.text = result.fold(
                    onSuccess = { "${model.displayName}: manifesto, compatibilidade e hashes válidos." },
                    onFailure = { "${model.displayName}: inválido — ${it.message}" }
                )
                appendLog(modelStatus.text.toString())
                refreshModelRows()
            }
        }.start()
    }

    private fun downloadSelectedPackage() {
        val model = selectedModel ?: return toast("Selecione um modelo.")
        if (model.downloadUrl == null || model.packageSha256 == null) {
            return toast("Este manifesto não possui um artefato real para download.")
        }
        val button = findViewById<Button>(R.id.button_download_npu_package)
        downloadInProgress = true
        setModelActionsEnabled(false)
        button.isEnabled = true
        button.text = "Cancelar"
        Thread {
            val result = runCatching {
                val file = downloader.download(model) { progress ->
                    val percent = if (progress.total > 0L) progress.downloaded * 100L / progress.total else -1L
                    runOnUiThread {
                        modelStatus.text = if (percent >= 0L) {
                            "${model.displayName}: $percent% — ${formatBytes(progress.bytesPerSecond)}/s" +
                                (progress.etaSeconds?.let { " — ~${it}s" } ?: "")
                        } else {
                            "${model.displayName}: ${formatBytes(progress.downloaded)} baixados"
                        }
                    }
                }
                packageManager.importPackage(model, file)
                file.delete()
            }
            runOnUiThread {
                downloadInProgress = false
                button.text = "Baixar"
                setModelActionsEnabled(true)
                modelStatus.text = result.fold(
                    onSuccess = { "${model.displayName}: download, hash e instalação concluídos." },
                    onFailure = { "Download não instalado: ${it.message ?: it.javaClass.simpleName}" }
                )
                appendLog(modelStatus.text.toString())
                refreshModelRows()
            }
        }.start()
    }

    private fun confirmDeletePackage() {
        val model = selectedModel ?: return toast("Selecione um modelo.")
        AlertDialog.Builder(this)
            .setTitle("Excluir pacote NPU")
            .setMessage("Excluir o pacote experimental ${model.displayName}? O modelo GGML normal não será alterado.")
            .setNegativeButton("Não", null)
            .setPositiveButton("Sim") { _, _ ->
                val removed = packageManager.delete(model)
                modelStatus.text = if (removed) "${model.displayName}: pacote excluído." else "Nenhum pacote para excluir."
                refreshModelRows()
            }
            .show()
    }

    private fun copyReport() {
        val value = report.ifBlank { diagnosticText.text.toString() }
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("Diagnóstico NPU", value))
        toast("Relatório copiado.")
    }

    private fun exportReport() {
        val value = report.ifBlank { diagnosticText.text.toString() }
        val dir = (getExternalFilesDir("npu_reports") ?: File(filesDir, "npu_reports")).apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "diagnostico_npu_$stamp.txt").apply { writeText(value) }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Exportar diagnóstico NPU"))
    }

    private fun setModelActionsEnabled(enabled: Boolean) {
        findViewById<Button>(R.id.button_import_npu_package).isEnabled = enabled
        findViewById<Button>(R.id.button_verify_npu_package).isEnabled = enabled
        findViewById<Button>(R.id.button_delete_npu_package).isEnabled = enabled
        if (!downloadInProgress) {
            val model = selectedModel
            findViewById<Button>(R.id.button_download_npu_package).isEnabled =
                enabled && model?.downloadUrl != null && model.packageSha256 != null
        }
    }

    private fun appendLog(line: String) {
        logText.append("\n$line")
    }

    private fun displayName(uri: Uri): String {
        var cursor: Cursor? = null
        return try {
            cursor = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            if (cursor != null && cursor.moveToFirst()) cursor.getString(0) else uri.lastPathSegment.orEmpty()
        } finally {
            cursor?.close()
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun formatBytes(bytes: Long): String {
        var value = bytes.toDouble()
        val units = arrayOf("B", "KB", "MB", "GB")
        var unit = 0
        while (value >= 1024.0 && unit < units.lastIndex) {
            value /= 1024.0
            unit++
        }
        return String.format(Locale.US, "%.1f %s", value, units[unit])
    }
}
