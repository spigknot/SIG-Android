package br.gov.sp.pcsp.launcher

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.TabHost
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import okhttp3.OkHttpClient
import okhttp3.Request

/** Histórico / Oitiva tabs; each owns independent System and User editors. */
class PromptsSettingsActivity : AppCompatActivity() {
    private class Section(
        val slot: PromptSlot,
        val options: LinearLayout,
        val editor: EditText,
        val save: Button,
        val delete: Button,
    ) {
        var current: PromptEntry? = null
        var savedText = ""
    }

    private val sections = linkedMapOf<PromptSlot, Section>()
    private lateinit var tabs: TabHost
    private lateinit var message: TextView
    private var importSlot: PromptSlot? = null
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        keepContentInsideSystemBars()
        setContentView(R.layout.activity_prompts_settings)
        message = findViewById(R.id.prompt_message)
        tabs = findViewById(R.id.prompt_tabs)
        tabs.setup()
        tabs.addTab(tabs.newTabSpec("history").setIndicator("Histórico").setContent(R.id.prompt_history_tab))
        tabs.addTab(tabs.newTabSpec("statement").setIndicator("Oitiva").setContent(R.id.prompt_statement_tab))
        for (index in 0 until tabs.tabWidget.childCount) {
            val title = tabs.tabWidget.getChildAt(index).findViewById<TextView>(android.R.id.title)
            title.isAllCaps = false
        }
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.button_update_prompts).setOnClickListener { updateFromR2() }
        buildSection(PromptSlot.HISTORY_SYSTEM, R.id.prompt_history_sections)
        buildSection(PromptSlot.HISTORY_USER, R.id.prompt_history_sections)
        buildSection(PromptSlot.STATEMENT_SYSTEM, R.id.prompt_statement_sections)
        buildSection(PromptSlot.STATEMENT_USER, R.id.prompt_statement_sections)
        sections.values.forEach { refresh(it) }
        tabs.currentTab = savedInstanceState?.getInt("tab") ?: 0
        importSlot = savedInstanceState?.getString("importSlot")?.let { PromptSlot.valueOf(it) }
        sections.values.forEach { section ->
            if (section.current?.isDefault == false) {
                savedInstanceState?.getString("draft_${section.slot.name}")?.let { section.editor.setText(it) }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tab", tabs.currentTab)
        outState.putString("importSlot", importSlot?.name)
        sections.values.filter { it.current?.isDefault == false }.forEach {
            outState.putString("draft_${it.slot.name}", it.editor.text.toString())
        }
    }

    private fun buildSection(slot: PromptSlot, parentId: Int) {
        val parent = findViewById<LinearLayout>(parentId)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(12))
        }
        parent.addView(panel)
        val header = layoutInflater.inflate(R.layout.view_prompt_section_header, panel, false)
        header.findViewById<TextView>(R.id.prompt_section_title).text = if (slot.isSystem) "System" else "User"
        panel.addView(header)
        val options = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(options)
        val editor = promptEditor().apply { contentDescription = editorTitle(slot) }
        val editorHeight = if (slot.isSystem) R.dimen.prompt_tab_system_editor_height else R.dimen.prompt_tab_user_editor_height
        panel.addView(editor, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            resources.getDimensionPixelSize(editorHeight)))
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        panel.addView(actions)
        val save = actionButton("Salvar")
        val import = actionButton("Importar")
        val delete = actionButton("Deletar")
        listOf(save, import, delete).forEach {
            actions.addView(it, LinearLayout.LayoutParams(0, dp(48), 1f))
        }
        val section = Section(slot, options, editor, save, delete)
        sections[slot] = section
        header.findViewById<ImageButton>(R.id.button_add_prompt).apply {
            contentDescription = "Novo ${editorTitle(slot)}"
            setOnClickListener { createPrompt(section) }
        }
        save.setOnClickListener { save(section) }
        import.setOnClickListener { openImportPicker(slot) }
        delete.setOnClickListener { confirmDelete(section) }
    }

    private fun actionButton(label: String) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 12f
        minWidth = 0
        minimumWidth = 0
        setPadding(0, 0, 0, 0)
    }

    private fun promptEditor() = EditText(this).apply {
        gravity = Gravity.TOP or Gravity.START
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.rgb(16, 20, 24))
        textSize = 12f
        setPadding(dp(8), dp(8), dp(8), dp(8))
        isVerticalScrollBarEnabled = true
    }

    private fun editorTitle(slot: PromptSlot): String = when (slot) {
        PromptSlot.HISTORY_SYSTEM -> "Histórico (system)"
        PromptSlot.HISTORY_USER -> "Histórico (user)"
        PromptSlot.STATEMENT_SYSTEM -> "Oitiva (system)"
        PromptSlot.STATEMENT_USER -> "Oitiva (user)"
    }

    /** Never reload another section's unsaved editor when one section changes. */
    private fun refresh(section: Section, loadText: Boolean = true) {
        try {
            val entries = PromptTemplateStore.entries().filter { it.slot == section.slot }
            section.options.removeAllViews()
            val controls = mutableListOf<RadioButton>()
            var row: LinearLayout? = null
            entries.forEachIndexed { index, entry ->
                if (index % 3 == 0) {
                    row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                    section.options.addView(row)
                }
                val radio = RadioButton(this).apply {
                    text = entry.optionLabel
                    contentDescription = entry.id
                    textSize = 12f
                    setTextColor(Color.WHITE)
                    buttonTintList = ColorStateList.valueOf(Color.rgb(94, 218, 242))
                    isChecked = entry.active
                    setOnClickListener {
                        // Undo Android's automatic check until the user confirms a dirty switch.
                        controls.forEach { it.isChecked = it.contentDescription.toString() == section.current?.id }
                        select(section, entry)
                    }
                }
                controls.add(radio)
                row!!.addView(radio, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            if (loadText) {
                val active = entries.firstOrNull { it.active } ?: entries.first()
                section.current = active
                section.savedText = PromptTemplateStore.readEntry(section.slot, active.id)
                section.editor.setText(section.savedText)
                // Default edits are temporary; only persistence actions are protected.
                section.editor.isEnabled = true
                section.save.isEnabled = !active.isDefault
                section.delete.isEnabled = !active.isDefault
            }
        } catch (error: Exception) {
            showError(error.message ?: "Não consegui ler os prompts.")
        }
    }

    private fun select(section: Section, entry: PromptEntry) {
        val apply = {
            val error = PromptTemplateStore.setActive(section.slot, entry.id)
            if (error != null) showError(error) else refresh(section)
        }
        if (section.current?.id != entry.id && section.editor.text.toString() != section.savedText) {
            AlertDialog.Builder(this).setTitle("Alterações não salvas")
                .setMessage("Descartar a edição e trocar de prompt?")
                .setPositiveButton("Descartar") { _, _ -> apply() }
                .setNegativeButton("Cancelar", null).show()
        } else apply()
    }

    private fun save(section: Section) {
        val entry = section.current ?: return
        if (entry.isDefault) return
        val text = section.editor.text.toString()
        val error = PromptTemplateStore.saveCustom(section.slot, entry.id, text, overwrite = true)
        if (error != null) showError(error) else {
            section.savedText = text
            showMessage("'${entry.id}' salvo.")
        }
    }

    /** A new prompt ALWAYS starts from this slot's default, not the active custom. */
    private fun createPrompt(section: Section) {
        val editor = promptEditor().apply { setText(PromptTemplateStore.readEntry(section.slot, PROMPT_DEFAULT_ID)) }
        val scroll = ScrollView(this).apply {
            addView(editor, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val dialog = AlertDialog.Builder(this).setTitle(editorTitle(section.slot)).setView(scroll)
            .setPositiveButton("SALVAR", null).setNegativeButton("Cancelar", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = EditText(this).apply {
                    hint = "Nome do prompt"
                    inputType = InputType.TYPE_CLASS_TEXT
                }
                val naming = AlertDialog.Builder(this).setTitle("Nome do prompt").setView(name)
                    .setPositiveButton("Salvar", null).setNegativeButton("Cancelar", null).create()
                naming.setOnShowListener {
                    naming.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener saveNamed@ {
                        val id = PromptTemplateStore.sanitizeId(name.text.toString())
                        if (id.isEmpty()) {
                            name.error = "Informe um nome."
                            return@saveNamed
                        }
                        val error = PromptTemplateStore.saveCustom(section.slot, id, editor.text.toString(), overwrite = false)
                        if (error != null) { name.error = error } else {
                            val selectionError = PromptTemplateStore.setActive(section.slot, id)
                            if (selectionError != null) { showError(selectionError) } else {
                                refresh(section)
                                showMessage("'$id' salvo e em uso.")
                            }
                            naming.dismiss()
                            dialog.dismiss()
                        }
                    }
                }
                naming.show()
            }
        }
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.8).toInt())
    }

    private fun confirmDelete(section: Section) {
        val entry = section.current ?: return
        if (entry.isDefault) return
        AlertDialog.Builder(this).setTitle("Deletar prompt")
            .setMessage("Deletar '${entry.id}' de ${editorTitle(section.slot)}?")
            .setPositiveButton("Deletar") { _, _ ->
                val error = PromptTemplateStore.deletePrompt(section.slot, entry.id)
                if (error != null) showError(error) else refresh(section)
            }.setNegativeButton("Cancelar", null).show()
    }

    private fun openImportPicker(slot: PromptSlot) {
        if (busy) return
        importSlot = slot
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/plain", "text/*", "application/octet-stream"))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_IMPORT_PROMPT)
    }

    @Deprecated("Legacy XML activity callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_IMPORT_PROMPT) return
        val slot = importSlot ?: return
        importSlot = null
        if (resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        readImport(slot, uri, SharedMediaIntents.displayName(this, uri, "prompt.txt"))
    }

    private fun readImport(slot: PromptSlot, uri: Uri, filename: String) {
        if (busy) return
        busy = true
        Thread {
            val result = runCatching {
                contentResolver.openInputStream(uri)?.use { input ->
                    val bytes = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (bytes.size() + count > MAX_PROMPT_BYTES) {
                            throw IllegalStateException("O prompt excede o limite de 64 KiB.")
                        }
                        bytes.write(buffer, 0, count)
                    }
                    PromptTemplateStore.decodeStrict(bytes.toByteArray())
                        ?: throw IllegalStateException("O arquivo não é UTF-8 válido.")
                } ?: throw IllegalStateException("Não consegui ler o arquivo selecionado.")
            }
            runOnUiThread {
                busy = false
                result.fold(onSuccess = { text ->
                    val inferred = PromptTemplateStore.inferSlot(filename, text)
                    if (inferred != null && inferred != slot) {
                        AlertDialog.Builder(this).setTitle("Arquivo de outra função")
                            .setMessage("Importar em ${editorTitle(slot)} mesmo assim?")
                            .setPositiveButton("Importar") { _, _ -> finishImport(slot, filename, text) }
                            .setNegativeButton("Cancelar", null).show()
                    } else finishImport(slot, filename, text)
                }, onFailure = { showError(it.message ?: "Não consegui importar.") })
            }
        }.start()
    }

    private fun finishImport(slot: PromptSlot, filename: String, text: String) {
        val error = PromptTemplateStore.importPrompt(slot, filename, text)
        if (error != null) showError(error) else {
            refresh(sections.getValue(slot))
            showMessage("Importado em ${editorTitle(slot)} e em uso.")
        }
    }

    /** Only the four allowlisted files; preserve custom text AND unsaved drafts. */
    private fun updateFromR2() {
        if (busy) return
        busy = true
        showMessage("Consultando o R2…")
        Thread {
            val result = fetchRemotePrompts()
            runOnUiThread {
                busy = false
                result.fold(onSuccess = { files ->
                    val changed = runCatching { PromptTemplateStore.defaultsChanged(files) }.getOrElse {
                        showError(it.message ?: "Não consegui comparar os padrões locais.")
                        return@fold
                    }
                    if (changed.isEmpty()) showMessage("Seus prompts já estão atualizados.") else {
                        val error = PromptTemplateStore.applyDefaults(files)
                        if (error != null) showError(error) else {
                            sections.values.filter { it.slot.file in changed && it.current?.isDefault == true }
                                .forEach { refresh(it) }
                            showMessage("Atualizado: ${changed.joinToString(", ")}")
                        }
                    }
                }, onFailure = { showError(it.message ?: "Não consegui baixar os prompts.") })
            }
        }.start()
    }

    private fun fetchRemotePrompts(): Result<Map<String, String>> = runCatching {
        val client = OkHttpClient()
        val files = mutableMapOf<String, String>()
        for (key in R2_PROMPT_KEYS) {
            val request = Request.Builder().url("$R2_PROMPTS_BASE_URL/$key")
                .header("Cache-Control", "no-cache").build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IllegalStateException("$key: HTTP ${response.code}")
                val bytes = response.body?.bytes() ?: throw IllegalStateException("$key: resposta vazia")
                files[key] = PromptTemplateStore.decodeStrict(bytes)
                    ?: throw IllegalStateException("$key: o conteúdo não é UTF-8 válido")
            }
        }
        files.toMap()
    }

    private fun showMessage(text: String) {
        message.setTextColor(Color.rgb(124, 217, 138))
        message.text = text
        message.visibility = View.VISIBLE
    }

    private fun showError(text: String) {
        message.setTextColor(Color.rgb(255, 119, 119))
        message.text = text
        message.visibility = View.VISIBLE
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object { private const val REQUEST_IMPORT_PROMPT = 7501 }
}
