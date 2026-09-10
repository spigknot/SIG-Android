package br.gov.sp.pcsp.launcher

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/** Tela de configuracoes avancadas: paralelismo e perfis de keywords do STT.
 *
 * A secao "Keywords" mantem VARIOS perfis (listas nomeadas). O seletor escolhe
 * qual perfil esta ativo (e o mesmo das telas de transcricao): a tabela abaixo
 * edita os termos DESSE perfil. O "+" verde cria um perfil (pede o nome), o "-"
 * vermelho exclui o perfil selecionado com confirmacao; tocar e segurar o
 * seletor renomeia. A persistencia e do GrokApiSettings e cada provedor monta o
 * SEU parametro na requisicao (SttKeywords).
 */

class AdvancedSettingsActivity : AppCompatActivity() {
    private lateinit var conversionParallelism: EditText
    private lateinit var requestParallelism: EditText
    private lateinit var keywordInput: EditText
    private lateinit var keywordRows: LinearLayout
    private lateinit var keywordScroll: ScrollView
    private lateinit var keywordEmpty: TextView
    private lateinit var keywordProfileSelector: TextView
    private lateinit var buttonAddKeyword: ImageButton
    private lateinit var buttonRemoveKeyword: ImageButton
    private val keywordCells = mutableListOf<Pair<TextView, TextView>>()
    private var selectedKeyword = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        keepContentInsideSystemBars()
        setContentView(R.layout.activity_advanced_settings)
        conversionParallelism = findViewById(R.id.edit_conversion_parallelism)
        requestParallelism = findViewById(R.id.edit_request_parallelism)
        keywordInput = findViewById(R.id.input_keyword)
        keywordRows = findViewById(R.id.keyword_rows)
        keywordScroll = findViewById(R.id.keyword_scroll)
        keywordEmpty = findViewById(R.id.keyword_empty)
        keywordProfileSelector = findViewById(R.id.button_keyword_profile)
        buttonAddKeyword = findViewById(R.id.button_add_keyword)
        buttonRemoveKeyword = findViewById(R.id.button_remove_keyword)
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.button_keywords_help).setOnClickListener { showKeywordsHelp() }
        keywordProfileSelector.setOnClickListener { showProfileMenu() }
        keywordProfileSelector.setOnLongClickListener {
            renameSelectedProfile()
            true
        }
        findViewById<ImageButton>(R.id.button_add_profile).setOnClickListener { createProfile() }
        findViewById<ImageButton>(R.id.button_remove_profile).setOnClickListener {
            confirmRemoveProfile()
        }
        buttonAddKeyword.setOnClickListener { addKeyword() }
        buttonRemoveKeyword.setOnClickListener { confirmRemoveKeyword() }
        configureFields()
    }

    override fun onResume() {
        super.onResume()
        populateFields()
        refreshKeywordsUi()
    }

    override fun onPause() {
        saveFields()
        super.onPause()
    }

    private fun configureFields() {
        conversionParallelism.setOnFocusChangeListener { _, focused ->
            if (!focused) saveFields()
        }
        requestParallelism.setOnFocusChangeListener { _, focused ->
            if (!focused) saveFields()
        }
    }

    private fun populateFields() {
        conversionParallelism.setText(ConversionParallelismSettings.selected(this).toString())
        requestParallelism.setText(GraniteParallelismSettings.selectedRequests(this).toString())
    }

    private fun saveFields() {
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

    // ---------------- Perfis de keywords ----------------

    private fun showKeywordsHelp() {
        AlertDialog.Builder(this)
            .setTitle("Keywords")
            .setMessage(
                SttKeywordsHelp.text(
                    provider = null,
                    isLive = false,
                    keywords = GrokApiSettings.activeSttKeywords(),
                )
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showProfileMenu() {
        val profiles = GrokApiSettings.keywordProfiles()
        val popup = PopupMenu(this, keywordProfileSelector)
        popup.menu.add(0, OFF_MENU_ID, 0, SttKeywordProfiles.OFF_LABEL)
        profiles.forEachIndexed { index, profile ->
            popup.menu.add(0, index + 1, index + 1, "${profile.name} (${profile.keywords.size})")
        }
        popup.setOnMenuItemClickListener { item ->
            val name = if (item.itemId == OFF_MENU_ID) {
                null
            } else {
                profiles.getOrNull(item.itemId - 1)?.name
            }
            GrokApiSettings.selectKeywordProfile(name)
            selectedKeyword = -1
            refreshKeywordsUi()
            true
        }
        popup.show()
    }

    private fun createProfile() {
        val profiles = GrokApiSettings.keywordProfiles()
        val input = EditText(this).apply {
            hint = "Nome do perfil"
            setText(SttKeywordProfiles.nextDefaultName(profiles))
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle("Novo perfil de keywords")
            .setView(input)
            .setPositiveButton("Criar") { _, _ ->
                val name = input.text.toString()
                val error = SttKeywordProfiles.nameError(name, profiles)
                if (error != null) {
                    Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                GrokApiSettings.setKeywordProfiles(
                    SttKeywordProfiles.withProfile(profiles, name, emptyList())
                )
                GrokApiSettings.selectKeywordProfile(name.trim())
                selectedKeyword = -1
                refreshKeywordsUi()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun renameSelectedProfile() {
        val current = GrokApiSettings.selectedKeywordProfile()
        if (current == null) {
            Toast.makeText(this, "Escolha um perfil para renomear.", Toast.LENGTH_SHORT).show()
            return
        }
        val profiles = GrokApiSettings.keywordProfiles()
        val input = EditText(this).apply {
            setText(current)
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle("Renomear perfil")
            .setView(input)
            .setPositiveButton("Renomear") { _, _ ->
                val newName = input.text.toString()
                val error = SttKeywordProfiles.nameError(newName, profiles, renaming = current)
                if (error != null) {
                    Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                GrokApiSettings.setKeywordProfiles(
                    SttKeywordProfiles.renamed(profiles, current, newName)
                )
                GrokApiSettings.selectKeywordProfile(newName.trim())
                refreshKeywordsUi()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun confirmRemoveProfile() {
        val current = GrokApiSettings.selectedKeywordProfile()
        if (current == null) {
            Toast.makeText(this, "Escolha um perfil para excluir.", Toast.LENGTH_SHORT).show()
            return
        }
        val profiles = GrokApiSettings.keywordProfiles()
        val count = SttKeywordProfiles.keywordsOf(profiles, current).size
        AlertDialog.Builder(this)
            .setMessage(
                "Excluir o perfil \"$current\" ($count palavra(s))? " +
                    "O envio de keywords ficará desligado."
            )
            .setPositiveButton("Excluir") { _, _ ->
                GrokApiSettings.setKeywordProfiles(
                    SttKeywordProfiles.withoutProfile(profiles, current)
                )
                GrokApiSettings.selectKeywordProfile(null)
                selectedKeyword = -1
                refreshKeywordsUi()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ---------------- Termos do perfil ativo ----------------

    private fun addKeyword() {
        var profiles = GrokApiSettings.keywordProfiles()
        var profileName = GrokApiSettings.selectedKeywordProfile()
        if (profileName == null) {
            // Sem perfil ativo: cria um automaticamente para não perder o termo.
            profileName = SttKeywordProfiles.nextDefaultName(profiles)
            profiles = SttKeywordProfiles.withProfile(profiles, profileName, emptyList())
            GrokApiSettings.setKeywordProfiles(profiles)
            GrokApiSettings.selectKeywordProfile(profileName)
        }
        val current = SttKeywordProfiles.keywordsOf(profiles, profileName)
        val typed = keywordInput.text.toString()
        val reason = SttKeywords.rejectionReason(typed, current)
        if (reason != null) {
            Toast.makeText(this, reason, Toast.LENGTH_SHORT).show()
            return
        }
        val term = typed.trim()
        val updated = SttKeywords.normalize(current + term)
        GrokApiSettings.setKeywordProfiles(
            SttKeywordProfiles.withProfile(profiles, profileName, updated)
        )
        keywordInput.setText("")
        selectedKeyword = updated.indexOfFirst { it.equals(term, ignoreCase = true) }
        refreshKeywordsUi()
    }

    private fun confirmRemoveKeyword() {
        val current = selectedProfileKeywords()
        if (selectedKeyword !in current.indices) {
            Toast.makeText(this, "Selecione uma palavra na lista para excluir.", Toast.LENGTH_SHORT).show()
            return
        }
        val term = current[selectedKeyword]
        AlertDialog.Builder(this)
            .setMessage("Excluir \"$term\" da lista de keywords?")
            .setPositiveButton("Excluir") { _, _ -> removeKeyword(selectedKeyword) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun removeKeyword(index: Int) {
        val profileName = GrokApiSettings.selectedKeywordProfile() ?: return
        val profiles = GrokApiSettings.keywordProfiles()
        val current = SttKeywordProfiles.keywordsOf(profiles, profileName)
        if (index !in current.indices) return
        val updated = current.toMutableList().also { it.removeAt(index) }
        GrokApiSettings.setKeywordProfiles(
            SttKeywordProfiles.withProfile(profiles, profileName, updated)
        )
        // A lista sobe: nenhuma linha fica selecionada depois da remoção.
        selectedKeyword = -1
        refreshKeywordsUi()
    }

    private fun selectedProfileKeywords(): List<String> =
        SttKeywordProfiles.keywordsOf(
            GrokApiSettings.keywordProfiles(),
            GrokApiSettings.selectedKeywordProfile(),
        )

    private fun refreshKeywordsUi() {
        val profileName = GrokApiSettings.selectedKeywordProfile()
        keywordProfileSelector.text = profileName ?: "${SttKeywordProfiles.OFF_LABEL} (desligado)"
        val semPerfil = profileName == null
        keywordInput.isEnabled = !semPerfil
        buttonAddKeyword.isEnabled = true
        buttonAddKeyword.alpha = 1f
        buttonRemoveKeyword.isEnabled = !semPerfil
        buttonRemoveKeyword.alpha = if (semPerfil) 0.45f else 1f
        val keywords = if (semPerfil) emptyList() else selectedProfileKeywords()
        if (selectedKeyword !in keywords.indices) selectedKeyword = -1
        keywordCells.clear()
        keywordRows.removeAllViews()
        keywordScroll.visibility = if (keywords.isEmpty()) View.GONE else View.VISIBLE
        keywordEmpty.visibility = View.VISIBLE
        keywordEmpty.text = when {
            semPerfil -> "Nenhum perfil ativo. Escolha um perfil acima ou crie um no \"+\" verde."
            keywords.isEmpty() -> "Nenhuma palavra neste perfil. Digite e toque no \"+\" verde."
            else -> ""
        }
        if (keywordEmpty.text.isEmpty()) keywordEmpty.visibility = View.GONE
        keywords.forEachIndexed { index, term -> keywordRows.addView(keywordRow(index, term)) }
        paintKeywordSelection()
    }

    private fun keywordRow(index: Int, term: String): LinearLayout {
        val numberView = TextView(this).apply {
            text = (index + 1).toString()
            textSize = 12f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(4), dp(5), dp(4), dp(5))
        }
        val termView = TextView(this).apply {
            text = term
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.WHITE)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setBackgroundResource(R.drawable.batch_cell_left_border)
            setPadding(dp(6), dp(5), dp(6), dp(5))
        }
        keywordCells += numberView to termView
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(
                numberView,
                LinearLayout.LayoutParams(dp(NUMBER_CELL_WIDTH_DP), ViewGroup.LayoutParams.WRAP_CONTENT)
            )
            addView(termView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            setOnClickListener { selectKeyword(index) }
        }
    }

    private fun selectKeyword(index: Int) {
        selectedKeyword = if (selectedKeyword == index) -1 else index
        paintKeywordSelection()
    }

    private fun paintKeywordSelection() {
        keywordCells.forEachIndexed { index, cells ->
            val selected = index == selectedKeyword
            val (numberView, termView) = cells
            numberView.setTextColor(if (selected) ACCENT_COLOR else Color.WHITE)
            numberView.setBackgroundColor(if (selected) SELECTION_COLOR else Color.TRANSPARENT)
            termView.setTextColor(if (selected) ACCENT_COLOR else Color.WHITE)
            termView.setTypeface(Typeface.MONOSPACE, if (selected) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt().coerceAtLeast(1)

    private companion object {
        const val OFF_MENU_ID = 0
        const val NUMBER_CELL_WIDTH_DP = 34
        val ACCENT_COLOR = Color.rgb(94, 218, 242)
        val SELECTION_COLOR = Color.argb(0x33, 94, 218, 242)
    }
}
