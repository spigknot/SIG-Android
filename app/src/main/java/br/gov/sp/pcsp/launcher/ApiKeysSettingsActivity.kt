package br.gov.sp.pcsp.launcher

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class ApiKeysSettingsActivity : AppCompatActivity() {
    private lateinit var xaiKey: EditText
    private lateinit var deepseekKey: EditText
    private lateinit var deepgramKey: EditText
    private lateinit var deepgramKeyterms: EditText
    private lateinit var assemblyaiKey: EditText
    private lateinit var elevenlabsKey: EditText
    private lateinit var imeiCheckKey: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        keepContentInsideSystemBars()
        setContentView(R.layout.activity_api_keys_settings)
        xaiKey = findViewById(R.id.edit_xai_key)
        deepseekKey = findViewById(R.id.edit_deepseek_key)
        deepgramKey = findViewById(R.id.edit_deepgram_key)
        deepgramKeyterms = findViewById(R.id.edit_deepgram_keyterms)
        assemblyaiKey = findViewById(R.id.edit_assemblyai_key)
        elevenlabsKey = findViewById(R.id.edit_elevenlabs_key)
        imeiCheckKey = findViewById(R.id.edit_imei_check_key)
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.button_save_api_keys).setOnClickListener { saveKeys() }
    }

    override fun onResume() {
        super.onResume()
        xaiKey.setText(GrokApiSettings.xaiApiKey())
        deepseekKey.setText(GrokApiSettings.deepseekApiKey())
        deepgramKey.setText(GrokApiSettings.deepgramApiKey())
        deepgramKeyterms.setText(GrokApiSettings.deepgramKeyterms())
        assemblyaiKey.setText(GrokApiSettings.assemblyaiApiKey())
        elevenlabsKey.setText(GrokApiSettings.elevenlabsApiKey())
        imeiCheckKey.setText(ImeiApiSettings.apiKey())
    }

    private fun saveKeys() {
        GrokApiSettings.setXaiApiKey(xaiKey.text.toString())
        GrokApiSettings.setDeepseekApiKey(deepseekKey.text.toString())
        GrokApiSettings.setDeepgramApiKey(deepgramKey.text.toString())
        GrokApiSettings.setDeepgramKeyterms(deepgramKeyterms.text.toString())
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

    private fun keyStatus(value: String, validator: (String) -> Boolean): String = when {
        value.isBlank() -> "não informada"
        validator(value) -> "válida"
        else -> "inválida"
    }
}
