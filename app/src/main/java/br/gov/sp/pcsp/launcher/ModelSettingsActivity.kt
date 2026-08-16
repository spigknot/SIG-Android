package br.gov.sp.pcsp.launcher

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class ModelSettingsActivity : AppCompatActivity() {
    private lateinit var transcriptionGroup: RadioGroup
    private lateinit var textGroup: RadioGroup
    private lateinit var reasoningGroup: RadioGroup
    private lateinit var partsMethodGroup: RadioGroup
    private lateinit var partsModelGroup: RadioGroup
    private lateinit var xaiKey: EditText
    private lateinit var deepseekKey: EditText
    private lateinit var deepgramKey: EditText
    private lateinit var deepgramKeyterms: EditText
    private lateinit var assemblyaiKey: EditText
    private lateinit var elevenlabsKey: EditText
    private lateinit var imeiCheckKey: EditText
    private lateinit var chunkSpinner: Spinner
    private lateinit var conversionParallelism: EditText
    private lateinit var requestParallelism: EditText
    private var populating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        keepContentInsideSystemBars()
        setContentView(R.layout.activity_model_settings)
        transcriptionGroup = findViewById(R.id.radio_transcription_models)
        textGroup = findViewById(R.id.radio_text_models)
        reasoningGroup = findViewById(R.id.radio_text_reasoning)
        partsMethodGroup = findViewById(R.id.radio_parts_extraction)
        partsModelGroup = findViewById(R.id.radio_parts_model)
        xaiKey = findViewById(R.id.edit_xai_key)
        deepseekKey = findViewById(R.id.edit_deepseek_key)
        deepgramKey = findViewById(R.id.edit_deepgram_key)
        deepgramKeyterms = findViewById(R.id.edit_deepgram_keyterms)
        assemblyaiKey = findViewById(R.id.edit_assemblyai_key)
        elevenlabsKey = findViewById(R.id.edit_elevenlabs_key)
        imeiCheckKey = findViewById(R.id.edit_imei_check_key)
        chunkSpinner = findViewById(R.id.spinner_grok_chunk)
        conversionParallelism = findViewById(R.id.edit_conversion_parallelism)
        requestParallelism = findViewById(R.id.edit_request_parallelism)
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
        populateAll()
    }

    override fun onPause() {
        super.onPause()
        saveParallelismFields()
    }

    private fun saveKeys() {
        GrokApiSettings.setXaiApiKey(xaiKey.text.toString())
        GrokApiSettings.setDeepseekApiKey(deepseekKey.text.toString())
        GrokApiSettings.setDeepgramApiKey(deepgramKey.text.toString())
        GrokApiSettings.setDeepgramKeyterms(deepgramKeyterms.text.toString())
        GrokApiSettings.setAssemblyaiApiKey(assemblyaiKey.text.toString())
        GrokApiSettings.setElevenlabsApiKey(elevenlabsKey.text.toString())
        ImeiApiSettings.setApiKey(imeiCheckKey.text.toString())
        populateAll()
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

    private fun populateAll() {
        populating = true
        populateTranscription()
        populateText()
        populateParts()
        populateParallelism()
        populating = false
        refreshVisibility()
    }

    private fun populateTranscription() {
        transcriptionGroup.removeAllViews()
        TranscriptionModelStore.readConfigs().forEach { config ->
            val label = when {
                config.isGrokApi -> "Grok STT"
                config.isDeepgramApi -> "Deepgram Nova 3"
                config.isAssemblyaiApi -> "AssemblyAI Universal-3.5 Pro"
                config.isElevenlabsApi -> "ElevenLabs Scribe v2 Realtime"
                else -> "${config.name} (${config.modelName})"
            }
            transcriptionGroup.addView(radio(label, config.selected) {
                TranscriptionModelStore.select(config.name)
                refreshVisibility()
            })
        }
        val chunkOptions = listOf(50, 100, 200, 500, 1000)
        val selectedChunk = GrokApiSettings.grokChunkMillis().takeIf { it in chunkOptions } ?: 100
        GrokApiSettings.setGrokChunkMillis(selectedChunk)
        bindSpinner(chunkSpinner, chunkOptions, selectedChunk) {
            GrokApiSettings.setGrokChunkMillis(it)
        }
    }

    private fun populateText() {
        textGroup.removeAllViews()
        ModelServerStore.readConfigs().forEach { config ->
            val label = when {
                config.isGrokApi -> "xAI (${config.modelName})"
                config.isDeepseekApi -> "Deepseek (deepseek-v4-flash)"
                else -> config.name
            }
            textGroup.addView(radio(label, config.selected) {
                ModelServerStore.select(config.name)
                refreshVisibility()
            })
        }
        refreshReasoning()
    }

    private fun refreshReasoning() {
        val config = ModelServerStore.selectedConfig()
        val provider = config.provider
        reasoningGroup.removeAllViews()
        if (config.modelName == GrokApiSettings.GROK_NON_REASONING_TEXT_NAME) {
            reasoningGroup.visibility = View.GONE
            return
        }
        val options = if (provider == "deepseek") listOf("none" to "Nenhum", "high" to "High") else listOf("low" to "Low", "high" to "High")
        val current = GrokApiSettings.textReasoning().takeIf { saved -> options.any { it.first == saved } } ?: options.first().first
        if (current != GrokApiSettings.textReasoning()) GrokApiSettings.setTextReasoning(current)
        options.forEach { (value, label) -> reasoningGroup.addView(radio(label, value == current) { GrokApiSettings.setTextReasoning(value) }) }
    }

    private fun populateParts() {
        partsMethodGroup.removeAllViews()
        val selectedMethod = PartsExtractionSettings.selectedMethod(this)
        listOf(
            PartsExtractionSettings.Method.UPPERCASE to "Maiúsculas",
            PartsExtractionSettings.Method.NAME_DATABASE to "Base de nomes",
            PartsExtractionSettings.Method.AI to "IA"
        ).forEach { (method, label) ->
            partsMethodGroup.addView(radio(label, method == selectedMethod) {
                PartsExtractionSettings.select(this, method)
                refreshVisibility()
            })
        }
        partsModelGroup.removeAllViews()
        val models = mutableListOf(PartsExtractionSettings.MODEL_PROXY, PartsExtractionSettings.MODEL_PROXY_DEEPSEEK)
        if (GrokApiSettings.isPlausibleXaiKey()) models += PartsExtractionSettings.MODEL_GROK
        if (GrokApiSettings.isPlausibleXaiKey()) models += PartsExtractionSettings.MODEL_GROK_NON_REASONING
        if (GrokApiSettings.isPlausibleDeepseekKey()) models += PartsExtractionSettings.MODEL_DEEPSEEK
        if (PartsExtractionSettings.selectedModel(this) !in models) PartsExtractionSettings.selectModel(this, PartsExtractionSettings.MODEL_PROXY)
        models.forEach { model ->
            partsModelGroup.addView(radio(model, model == PartsExtractionSettings.selectedModel(this)) {
                PartsExtractionSettings.selectModel(this, model)
            })
        }
    }

    private fun populateParallelism() {
        conversionParallelism.setText(ConversionParallelismSettings.selected(this).toString())
        requestParallelism.setText(GraniteParallelismSettings.selectedRequests(this).toString())
        conversionParallelism.setOnFocusChangeListener { _, focused -> if (!focused) saveParallelismFields() }
        requestParallelism.setOnFocusChangeListener { _, focused -> if (!focused) saveParallelismFields() }
    }

    private fun saveParallelismFields() {
        val maxConversions = Runtime.getRuntime().availableProcessors().coerceAtLeast(1) * 4
        val conversions = conversionParallelism.text.toString().toIntOrNull()
            ?.coerceIn(1, maxConversions) ?: ConversionParallelismSettings.selected(this)
        val requests = requestParallelism.text.toString().toIntOrNull()
            ?.coerceIn(1, 32) ?: GraniteParallelismSettings.selectedRequests(this)
        ConversionParallelismSettings.select(this, conversions)
        GraniteParallelismSettings.select(this, requests)
        conversionParallelism.setText(conversions.toString())
        requestParallelism.setText(requests.toString())
    }

    private fun bindSpinner(spinner: Spinner, options: List<Int>, selected: Int, save: (Int) -> Unit) {
        val labels = options.map(Int::toString)
        spinner.adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, labels) {
            init {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                return super.getView(position, convertView, parent).also { view ->
                    (view as? TextView)?.apply {
                        setTextColor(Color.WHITE)
                        gravity = android.view.Gravity.CENTER
                    }
                }
            }
        }
        spinner.setSelection(options.indexOf(selected).coerceAtLeast(0), false)
        spinner.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                save(options[position])
            }
        })
    }

    private fun refreshVisibility() {
        findViewById<View>(R.id.layout_grok_chunk).visibility =
            if (TranscriptionModelStore.selectedConfig().isGrokApi || TranscriptionModelStore.selectedConfig().isDeepgramApi) View.VISIBLE else View.GONE
        val text = ModelServerStore.selectedConfig()
        reasoningGroup.visibility = if (text.isGrokApi || text.isDeepseekApi || text.isProxy) View.VISIBLE else View.GONE
        refreshReasoning()
        partsModelGroup.visibility = if (PartsExtractionSettings.selectedMethod(this) == PartsExtractionSettings.Method.AI) View.VISIBLE else View.GONE
    }

    private fun radio(label: String, checked: Boolean, onSelected: () -> Unit): RadioButton = RadioButton(this).apply {
        id = View.generateViewId(); text = label; textSize = 13f; isChecked = checked; setTextColor(Color.WHITE)
        buttonTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(Color.rgb(94, 218, 242), Color.GRAY))
        setPadding(dp(4), dp(5), dp(8), dp(5))
        layoutParams = RadioGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setOnClickListener { if (!populating) onSelected() }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
