package br.gov.sp.pcsp.launcher

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class ApiKeysSettingsActivity : AppCompatActivity() {
    private lateinit var xaiKey: EditText
    private lateinit var deepseekKey: EditText
    private lateinit var deepgramKey: EditText
    private lateinit var assemblyaiKey: EditText
    private lateinit var elevenlabsKey: EditText
    private lateinit var imeiCheckKey: EditText
    private var importPickerOpen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        keepContentInsideSystemBars()
        setContentView(R.layout.activity_api_keys_settings)
        xaiKey = findViewById(R.id.edit_xai_key)
        deepseekKey = findViewById(R.id.edit_deepseek_key)
        deepgramKey = findViewById(R.id.edit_deepgram_key)
        assemblyaiKey = findViewById(R.id.edit_assemblyai_key)
        elevenlabsKey = findViewById(R.id.edit_elevenlabs_key)
        imeiCheckKey = findViewById(R.id.edit_imei_check_key)
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.button_import_api_keys).setOnClickListener { openImportPicker() }
        findViewById<Button>(R.id.button_save_api_keys).setOnClickListener { saveKeys() }
    }

    @Deprecated("Deprecated Android callback kept for this legacy XML activity.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_IMPORT_API_KEYS) return
        importPickerOpen = false
        if (resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        try {
            if (data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0) {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (_: SecurityException) {
            // Um provedor pode conceder somente uma permissão de leitura temporária.
        }
        importKeysFromUri(uri)
    }

    override fun onResume() {
        super.onResume()
        if (importPickerOpen) return
        xaiKey.setText(GrokApiSettings.xaiApiKey())
        deepseekKey.setText(GrokApiSettings.deepseekApiKey())
        deepgramKey.setText(GrokApiSettings.deepgramApiKey())
        assemblyaiKey.setText(GrokApiSettings.assemblyaiApiKey())
        elevenlabsKey.setText(GrokApiSettings.elevenlabsApiKey())
        imeiCheckKey.setText(ImeiApiSettings.apiKey())
    }

    private fun saveKeys() {
        GrokApiSettings.setXaiApiKey(xaiKey.text.toString())
        GrokApiSettings.setDeepseekApiKey(deepseekKey.text.toString())
        GrokApiSettings.setDeepgramApiKey(deepgramKey.text.toString())
        GrokApiSettings.setAssemblyaiApiKey(assemblyaiKey.text.toString())
        GrokApiSettings.setElevenlabsApiKey(elevenlabsKey.text.toString())
        ImeiApiSettings.setApiKey(imeiCheckKey.text.toString())
        val xaiStatus = keyStatus(GrokApiSettings.xaiApiKey(), GrokApiSettings::isPlausibleXaiKey)
        val deepseekStatus = keyStatus(GrokApiSettings.deepseekApiKey(), GrokApiSettings::isPlausibleDeepseekKey)
        val deepgramStatus = keyStatus(GrokApiSettings.deepgramApiKey(), GrokApiSettings::isPlausibleDeepgramKey)
        Toast.makeText(
            this,
            "Chaves salvas. xAI: $xaiStatus; Deepseek: $deepseekStatus; Deepgram: $deepgramStatus.",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun openImportPicker() {
        importPickerOpen = true
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/plain", "text/*", "application/octet-stream"))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_IMPORT_API_KEYS)
    }

    private fun importKeysFromUri(uri: android.net.Uri) {
        Thread {
            try {
                val content = contentResolver.openInputStream(uri)
                    ?.bufferedReader(Charsets.UTF_8)
                    ?.use { it.readText() }
                    ?: throw IllegalStateException("não consegui ler o arquivo selecionado")
                val result = ApiKeysImportParser.parse(content)
                runOnUiThread { applyImportedKeys(result) }
            } catch (e: Throwable) {
                Log.e(TAG, "Could not import API keys file", e)
                runOnUiThread {
                    Toast.makeText(this, "Não consegui importar o arquivo de API KEYS.", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun applyImportedKeys(result: ApiKeysImportParser.Result) {
        result.keys[ApiKeysImportParser.Service.XAI]?.let { value -> xaiKey.setText(value) }
        result.keys[ApiKeysImportParser.Service.DEEPSEEK]?.let { value -> deepseekKey.setText(value) }
        result.keys[ApiKeysImportParser.Service.DEEPGRAM]?.let { value -> deepgramKey.setText(value) }
        result.keys[ApiKeysImportParser.Service.ASSEMBLYAI]?.let { value -> assemblyaiKey.setText(value) }
        result.keys[ApiKeysImportParser.Service.ELEVENLABS]?.let { value -> elevenlabsKey.setText(value) }
        result.keys[ApiKeysImportParser.Service.IMEI_CHECK]?.let { value -> imeiCheckKey.setText(value) }

        if (result.keys.isEmpty()) {
            Toast.makeText(this, "Nenhum serviço reconhecido no arquivo.", Toast.LENGTH_LONG).show()
            return
        }

        val ignored = result.ignoredLineNumbers.size
        val suffix = if (ignored == 0) "" else " $ignored linha(s) ignorada(s)."
        Toast.makeText(
            this,
            "${result.keys.size} chave(s) importada(s). Confira e toque em Salvar chaves.$suffix",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun keyStatus(value: String, validator: (String) -> Boolean): String = when {
        value.isBlank() -> "não informada"
        validator(value) -> "válida"
        else -> "inválida"
    }

    companion object {
        private const val REQUEST_IMPORT_API_KEYS = 7401
        private const val TAG = "ApiKeysSettings"
    }
}
