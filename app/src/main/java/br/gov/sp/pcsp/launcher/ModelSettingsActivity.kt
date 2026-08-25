package br.gov.sp.pcsp.launcher

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.appcompat.app.AppCompatActivity

class ModelSettingsActivity : AppCompatActivity() {
    private data class TextModelControls(
        val modelGroup: RadioGroup,
        val proxyControls: View,
        val proxyGroup: RadioGroup,
        val reasoningControls: View,
        val reasoningGroup: RadioGroup,
    )

    private lateinit var transcriptionGroup: RadioGroup
    private lateinit var historyControls: TextModelControls
    private lateinit var statementControls: TextModelControls
    private lateinit var partsSection: View
    private lateinit var partsMethodGroup: RadioGroup
    private lateinit var partsModelControls: View
    private lateinit var partsModelGroup: RadioGroup
    private lateinit var partsProxyModelLabel: View
    private lateinit var partsProxyModelGroup: RadioGroup
    private lateinit var partsReasoningLabel: View
    private lateinit var partsReasoningGroup: RadioGroup
    private lateinit var conversionParallelism: EditText
    private lateinit var requestParallelism: EditText
    private var populating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        keepContentInsideSystemBars()
        setContentView(R.layout.activity_model_settings)
        transcriptionGroup = findViewById(R.id.radio_transcription_models)
        historyControls = TextModelControls(
            modelGroup = findViewById(R.id.radio_history_models),
            proxyControls = findViewById(R.id.history_proxy_controls),
            proxyGroup = findViewById(R.id.radio_history_proxy_models),
            reasoningControls = findViewById(R.id.history_reasoning_controls),
            reasoningGroup = findViewById(R.id.radio_history_reasoning),
        )
        statementControls = TextModelControls(
            modelGroup = findViewById(R.id.radio_statement_models),
            proxyControls = findViewById(R.id.statement_proxy_controls),
            proxyGroup = findViewById(R.id.radio_statement_proxy_models),
            reasoningControls = findViewById(R.id.statement_reasoning_controls),
            reasoningGroup = findViewById(R.id.radio_statement_reasoning),
        )
        partsSection = findViewById(R.id.parts_extraction_section)
        partsMethodGroup = findViewById(R.id.radio_parts_extraction)
        partsModelControls = findViewById(R.id.parts_model_controls)
        partsModelGroup = findViewById(R.id.radio_parts_model)
        partsProxyModelLabel = findViewById(R.id.label_parts_proxy_model)
        partsProxyModelGroup = findViewById(R.id.radio_parts_proxy_model)
        partsReasoningLabel = findViewById(R.id.label_parts_reasoning)
        partsReasoningGroup = findViewById(R.id.radio_parts_reasoning)
        conversionParallelism = findViewById(R.id.edit_conversion_parallelism)
        requestParallelism = findViewById(R.id.edit_request_parallelism)
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.button_api_keys).setOnClickListener {
            startActivity(Intent(this, ApiKeysSettingsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        populateAll()
    }

    override fun onPause() {
        super.onPause()
        saveParallelismFields()
    }

    private fun populateAll() {
        populating = true
        populateTranscription()
        populateText(TextModelPurpose.HISTORY, historyControls)
        populateText(TextModelPurpose.STATEMENT, statementControls)
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
    }

    private fun populateText(purpose: TextModelPurpose, controls: TextModelControls) {
        controls.modelGroup.removeAllViews()
        ModelServerStore.readConfigs(purpose).forEach { config ->
            controls.modelGroup.addView(radio(modelLabel(config), config.selected) {
                ModelServerStore.select(purpose, config.name)
                refreshVisibility()
            })
        }
        populateProxyModelGroup(controls.proxyGroup)
        refreshTextControls(purpose, controls)
    }

    private fun modelLabel(config: ModelServerStore.Config): String = when {
        config.name == ModelServerStore.SERVER_GEMMA_NAME ->
            "${config.name} (${config.modelName})"
        else -> config.name
    }

    private fun populateProxyModelGroup(group: RadioGroup) {
        group.removeAllViews()
        val selected = GrokApiSettings.selectedProxyModel()
        GrokApiSettings.IA_PROXY_MODELS.forEach { model ->
            group.addView(radio(model, model == selected) {
                GrokApiSettings.selectProxyModel(model)
                refreshVisibility()
            })
        }
    }

    private fun reasoningOptions(model: String): List<Pair<String, String>> = when (model) {
        GrokApiSettings.TEXT_NAME -> listOf(
            "low" to "Low",
            "medium" to "Medium",
            "high" to "High",
            "xhigh" to "XHigh",
        )
        GrokApiSettings.DEEPSEEK_TEXT_NAME -> listOf(
            "none" to "Nenhum",
            "low" to "Low",
            "high" to "High",
            "max" to "Max",
        )
        else -> emptyList()
    }

    private fun populateReasoningGroup(
        group: RadioGroup,
        model: String,
        selected: String,
        onSelected: (String) -> Unit,
    ) {
        group.removeAllViews()
        val options = reasoningOptions(model)
        val current = selected.takeIf { saved -> options.any { it.first == saved } }
            ?: options.firstOrNull()?.first
        options.forEach { (value, label) ->
            group.addView(radio(label, value == current) { onSelected(value) })
        }
    }

    private fun refreshTextControls(purpose: TextModelPurpose, controls: TextModelControls) {
        val config = ModelServerStore.selectedConfig(purpose)
        if (config.isProxy) {
            controls.proxyControls.visibility = View.VISIBLE
            controls.reasoningControls.visibility = View.GONE
            populateProxyModelGroup(controls.proxyGroup)
            return
        }
        controls.proxyControls.visibility = View.GONE
        val options = reasoningOptions(config.modelName)
        if (options.isEmpty()) {
            controls.reasoningControls.visibility = View.GONE
            controls.reasoningGroup.removeAllViews()
            return
        }
        val current = GrokApiSettings.textReasoning()
            .takeIf { saved -> options.any { it.first == saved } }
            ?: options.first().first
        if (current != GrokApiSettings.textReasoning()) GrokApiSettings.setTextReasoning(current)
        populateReasoningGroup(controls.reasoningGroup, config.modelName, current) { value ->
            GrokApiSettings.setTextReasoning(value)
        }
        controls.reasoningControls.visibility = View.VISIBLE
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
        val configs = ModelServerStore.readConfigs()
        val selectedModel = PartsExtractionSettings.selectedModel(this)
        val resolvedModel = selectedModel.takeIf { model -> configs.any { it.name == model } }
            ?: PartsExtractionSettings.MODEL_PROXY.also {
                PartsExtractionSettings.selectModel(this, it)
            }
        configs.forEach { config ->
            partsModelGroup.addView(radio(modelLabel(config), config.name == resolvedModel) {
                PartsExtractionSettings.selectModel(this, config.name)
                refreshVisibility()
            })
        }
        populateProxyModelGroup(partsProxyModelGroup)
        refreshPartsModelControls()
    }

    private fun refreshPartsModelControls() {
        val selected = PartsExtractionSettings.selectedModel(this)
        val config = ModelServerStore.readConfigs().firstOrNull { it.name == selected }
        if (config == null || PartsExtractionSettings.selectedMethod(this) != PartsExtractionSettings.Method.AI) {
            partsProxyModelLabel.visibility = View.GONE
            partsProxyModelGroup.visibility = View.GONE
            partsReasoningLabel.visibility = View.GONE
            partsReasoningGroup.visibility = View.GONE
            return
        }
        if (config.isProxy) {
            partsProxyModelLabel.visibility = View.VISIBLE
            partsProxyModelGroup.visibility = View.VISIBLE
            partsReasoningLabel.visibility = View.GONE
            partsReasoningGroup.visibility = View.GONE
            populateProxyModelGroup(partsProxyModelGroup)
            return
        }
        partsProxyModelLabel.visibility = View.GONE
        partsProxyModelGroup.visibility = View.GONE
        val options = reasoningOptions(config.modelName)
        if (options.isEmpty()) {
            partsReasoningLabel.visibility = View.GONE
            partsReasoningGroup.visibility = View.GONE
            return
        }
        val current = PartsExtractionSettings.reasoning(this)
            .takeIf { saved -> options.any { it.first == saved } }
            ?: options.first().first
        if (current != PartsExtractionSettings.reasoning(this)) {
            PartsExtractionSettings.selectReasoning(this, current)
        }
        partsReasoningLabel.visibility = View.VISIBLE
        partsReasoningGroup.visibility = View.VISIBLE
        populateReasoningGroup(partsReasoningGroup, config.modelName, current) { value ->
            PartsExtractionSettings.selectReasoning(this, value)
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

    private fun refreshVisibility() {
        refreshTextControls(TextModelPurpose.HISTORY, historyControls)
        refreshTextControls(TextModelPurpose.STATEMENT, statementControls)
        val partsVisible = PartsExtractionSettings.selectedMethod(this) == PartsExtractionSettings.Method.AI
        partsModelControls.visibility = if (partsVisible) View.VISIBLE else View.GONE
        refreshPartsModelControls()
        disablePartsSection()
    }

    private fun disablePartsSection() {
        setEnabledRecursively(partsSection, false)
        partsSection.alpha = 0.45f
    }

    private fun setEnabledRecursively(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                setEnabledRecursively(view.getChildAt(index), enabled)
            }
        }
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
