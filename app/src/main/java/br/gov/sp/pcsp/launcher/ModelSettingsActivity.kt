package br.gov.sp.pcsp.launcher

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class ModelSettingsActivity : AppCompatActivity() {

    private lateinit var transcriptionGroup: RadioGroup
    private lateinit var textGroup: RadioGroup
    private lateinit var partsExtractionGroup: RadioGroup
    private lateinit var graniteParallelismGroup: RadioGroup
    private lateinit var transcriptionEmpty: TextView
    private lateinit var textEmpty: TextView
    private var populating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        keepContentInsideSystemBars()
        setContentView(R.layout.activity_model_settings)

        transcriptionGroup = findViewById(R.id.radio_transcription_models)
        textGroup = findViewById(R.id.radio_text_models)
        partsExtractionGroup = findViewById(R.id.radio_parts_extraction)
        graniteParallelismGroup = findViewById(R.id.radio_granite_parallelism)
        transcriptionEmpty = findViewById(R.id.transcription_models_empty)
        textEmpty = findViewById(R.id.text_models_empty)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.button_add_transcription_server).setOnClickListener {
            showAddTranscriptionServerDialog()
        }
        findViewById<ImageButton>(R.id.button_remove_transcription_server).setOnClickListener {
            removeSelectedTranscriptionServer()
        }
        findViewById<ImageButton>(R.id.button_add_text_model).setOnClickListener {
            showAddTextModelDialog()
        }
        findViewById<ImageButton>(R.id.button_remove_text_model).setOnClickListener {
            removeSelectedTextModel()
        }
        findViewById<ImageButton>(R.id.button_add_part_name).setOnClickListener {
            showEditPartNameDialog(add = true)
        }
        findViewById<ImageButton>(R.id.button_remove_part_name).setOnClickListener {
            showEditPartNameDialog(add = false)
        }
    }

    override fun onResume() {
        super.onResume()
        loadModelOptions()
    }

    private fun loadModelOptions() {
        TranscriptionModelStore.ensureDefaults()
        ModelServerStore.ensureDefaults()
        populateTranscriptionModels(TranscriptionModelStore.readConfigs())
        populateTextModels(ModelServerStore.readConfigs())
        populatePartsExtractionMethods()
        populateGraniteParallelism()
    }

    private fun populateTranscriptionModels(configs: List<TranscriptionModelStore.Config>) {
        populating = true
        transcriptionGroup.removeAllViews()
        transcriptionEmpty.visibility = if (configs.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        configs.forEach { config ->
            val button = createRadioButton("${config.name} (${config.modelName})")
            button.tag = config.name
            transcriptionGroup.addView(button)
            if (config.selected) button.isChecked = true
            button.setOnClickListener {
                if (populating) return@setOnClickListener
                if (config.isGrokApi) {
                    showGrokApiKeyDialog("transcrição") {
                        TranscriptionModelStore.select(config.name)
                        loadModelOptions()
                    }
                } else if (!TranscriptionModelStore.select(config.name)) {
                    Toast.makeText(this, "Não consegui salvar o modelo de transcrição.", Toast.LENGTH_LONG).show()
                }
            }
        }
        populating = false
    }

    private fun showAddTranscriptionServerDialog() {
        val density = resources.displayMetrics.density
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * density).toInt(), (8 * density).toInt(), (24 * density).toInt(), (4 * density).toInt())
        }
        val name = createServerField("Ex.: Taguai-speech", InputType.TYPE_CLASS_TEXT)
        val url = createServerField("http://100.70.207.12:8100", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val model = createServerField("Ex.: granite-speech-4.1-2b-nar", InputType.TYPE_CLASS_TEXT)
        container.addView(createServerLabel("Nome do Servidor:"))
        container.addView(name)
        container.addView(createServerLabel("URL:"))
        container.addView(url)
        container.addView(createServerLabel("Modelo:"))
        container.addView(model)

        AlertDialog.Builder(this)
            .setTitle("Adicionar servidor de transcrição")
            .setView(container)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Adicionar") { _, _ ->
                val added = TranscriptionModelStore.addConfig(
                    name.text.toString(),
                    url.text.toString(),
                    model.text.toString()
                )
                if (added) {
                    loadModelOptions()
                    Toast.makeText(this, "Servidor adicionado.", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Confira os campos. O nome deve ser único e a URL deve começar com http:// ou https://.", Toast.LENGTH_LONG).show()
                }
            }
            .show()
    }

    private fun createServerLabel(text: String): TextView {
        val density = resources.displayMetrics.density
        return TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(Color.DKGRAY)
            setPadding(0, (10 * density).toInt(), 0, 0)
        }
    }

    private fun createServerField(hint: String, inputType: Int): EditText {
        return EditText(this).apply {
            this.hint = hint
            this.inputType = inputType
            setSingleLine(true)
            setTextColor(Color.BLACK)
            setHintTextColor(Color.GRAY)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
    }

    private fun removeSelectedTranscriptionServer() {
        val selected = transcriptionGroup.findViewById<RadioButton>(transcriptionGroup.checkedRadioButtonId)
        val name = selected?.tag as? String
        if (name == null) {
            Toast.makeText(this, "Selecione um servidor para remover.", Toast.LENGTH_SHORT).show()
            return
        }
        if (name == GrokApiSettings.TRANSCRIPTION_NAME) {
            Toast.makeText(this, "Grok (API) é uma opção fixa do aplicativo.", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setMessage("Remover o servidor $name?")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Remover") { _, _ ->
                if (TranscriptionModelStore.removeConfig(name)) {
                    loadModelOptions()
                    Toast.makeText(this, "Servidor removido.", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Não consegui remover o servidor.", Toast.LENGTH_LONG).show()
                }
            }
            .show()
    }

    private fun showAddTextModelDialog() {
        val density = resources.displayMetrics.density
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * density).toInt(), (8 * density).toInt(), (24 * density).toInt(), (4 * density).toInt())
        }
        val name = createServerField("Ex.: Taguai-grok", InputType.TYPE_CLASS_TEXT)
        val url = createServerField("http://100.70.207.12:8500", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val model = createServerField("Ex.: grok-4.3", InputType.TYPE_CLASS_TEXT)
        container.addView(createServerLabel("Nome do Servidor:"))
        container.addView(name)
        container.addView(createServerLabel("URL:"))
        container.addView(url)
        container.addView(createServerLabel("Modelo:"))
        container.addView(model)

        AlertDialog.Builder(this)
            .setTitle("Adicionar modelo de texto")
            .setView(container)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Adicionar") { _, _ ->
                if (ModelServerStore.addConfig(name.text.toString(), url.text.toString(), model.text.toString())) {
                    loadModelOptions()
                    Toast.makeText(this, "Modelo de texto adicionado.", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Confira os campos. O nome deve ser único e a URL deve começar com http:// ou https://.", Toast.LENGTH_LONG).show()
                }
            }
            .show()
    }

    private fun removeSelectedTextModel() {
        val selected = textGroup.findViewById<RadioButton>(textGroup.checkedRadioButtonId)
        val name = selected?.tag as? String
        if (name == null) {
            Toast.makeText(this, "Selecione um modelo de texto para remover.", Toast.LENGTH_SHORT).show()
            return
        }
        if (name == GrokApiSettings.TEXT_NAME) {
            Toast.makeText(this, "Grok (API) é uma opção fixa do aplicativo.", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setMessage("Remover o modelo de texto $name?")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Remover") { _, _ ->
                if (ModelServerStore.removeConfig(name)) {
                    loadModelOptions()
                    Toast.makeText(this, "Modelo de texto removido.", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Não consegui remover o modelo de texto.", Toast.LENGTH_LONG).show()
                }
            }
            .show()
    }

    private fun showEditPartNameDialog(add: Boolean) {
        val input = createServerField(
            if (add) "Ex.: PÂMELA" else "Nome a remover",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        )
        AlertDialog.Builder(this)
            .setTitle(if (add) "Adicionar nome à base" else "Remover nome da base")
            .setMessage("A base é usada quando a opção Base de nomes está selecionada.")
            .setView(input)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton(if (add) "Adicionar" else "Remover") { _, _ ->
                val changed = if (add) {
                    NameDatabaseStore.addName(this, input.text.toString())
                } else {
                    NameDatabaseStore.removeName(this, input.text.toString())
                }
                val message = when {
                    changed && add -> "Nome adicionado à base."
                    changed -> "Nome removido da base."
                    add -> "Não consegui adicionar. O nome pode já existir."
                    else -> "Nome não encontrado na base."
                }
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
            .show()
    }

    private fun populateTextModels(configs: List<ModelServerStore.Config>) {
        populating = true
        textGroup.removeAllViews()
        textEmpty.visibility = if (configs.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        configs.forEach { config ->
            val button = createRadioButton("${config.name} (${config.modelName})")
            button.tag = config.name
            textGroup.addView(button)
            if (config.selected) button.isChecked = true
            button.setOnClickListener {
                if (populating) return@setOnClickListener
                if (config.isGrokApi) {
                    showGrokApiKeyDialog("texto") {
                        ModelServerStore.select(config.name)
                        loadModelOptions()
                    }
                } else if (!ModelServerStore.select(config.name)) {
                    Toast.makeText(this, "Não consegui salvar o modelo de texto.", Toast.LENGTH_LONG).show()
                }
            }
        }
        populating = false
    }

    private fun showGrokApiKeyDialog(kind: String, onSaved: () -> Unit) {
        val input = createServerField(
            "Insira a chave API",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        )
        input.setText(GrokApiSettings.apiKey())
        input.setSelection(input.text.length)
        AlertDialog.Builder(this)
            .setTitle("Grok (API) para $kind")
            .setView(input)
            .setNegativeButton("Cancelar") { _, _ -> loadModelOptions() }
            .setPositiveButton("Usar") { _, _ ->
                val key = input.text.toString().trim()
                if (key.isBlank()) {
                    Toast.makeText(this, "Insira a chave API para selecionar Grok (API).", Toast.LENGTH_LONG).show()
                    loadModelOptions()
                } else {
                    GrokApiSettings.setApiKey(key)
                    onSaved()
                }
            }
            .show()
    }

    private fun populatePartsExtractionMethods() {
        populating = true
        partsExtractionGroup.removeAllViews()
        val selected = PartsExtractionSettings.selectedMethod(this)
        listOf(
            PartsExtractionSettings.Method.UPPERCASE to "Palavras em maiúsculas",
            PartsExtractionSettings.Method.NAME_DATABASE to "Base de nomes",
            PartsExtractionSettings.Method.AI to "IA"
        ).forEach { (method, label) ->
            val button = createRadioButton(label)
            partsExtractionGroup.addView(button)
            button.isChecked = method == selected
            button.setOnClickListener {
                if (!populating) PartsExtractionSettings.select(this, method)
            }
        }
        populating = false
    }

    private fun populateGraniteParallelism() {
        populating = true
        graniteParallelismGroup.removeAllViews()
        val selected = GraniteParallelismSettings.selectedRequests(this)
        GraniteParallelismSettings.OPTIONS.forEach { value ->
            val label = if (value == 1) {
                "1 requisição por vez"
            } else {
                "$value requisições em paralelo"
            }
            val button = createRadioButton(label)
            graniteParallelismGroup.addView(button)
            button.isChecked = value == selected
            button.setOnClickListener {
                if (!populating) GraniteParallelismSettings.select(this, value)
            }
        }
        populating = false
    }

    private fun createRadioButton(label: String): RadioButton {
        val density = resources.displayMetrics.density
        return RadioButton(this).apply {
            id = android.view.View.generateViewId()
            text = label
            textSize = 13f
            setTextColor(Color.WHITE)
            buttonTintList = ColorStateList(
                arrayOf(
                    intArrayOf(android.R.attr.state_checked),
                    intArrayOf()
                ),
                intArrayOf(Color.rgb(94, 218, 242), Color.rgb(150, 150, 150))
            )
            setPadding((4 * density).toInt(), (7 * density).toInt(), (4 * density).toInt(), (7 * density).toInt())
            layoutParams = RadioGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
    }
}
