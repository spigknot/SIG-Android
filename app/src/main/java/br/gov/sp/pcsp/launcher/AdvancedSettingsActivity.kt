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
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/** Tela de configuracoes avancadas: paralelismo e keywords do STT.
 *
 * A secao "Keywords" edita a lista UNICA de termos de reforco: o "+" verde
 * acrescenta a palavra digitada e a tabela de duas colunas (ordinal e termo) e
 * remontada logo abaixo; tocar numa linha seleciona e o "-" vermelho exclui
 * com confirmacao, renumerando a lista. A persistencia e do GrokApiSettings e
 * cada provedor monta o SEU parametro na requisicao (SttKeywords).
 */

class AdvancedSettingsActivity : AppCompatActivity() {
    private lateinit var conversionParallelism: EditText
    private lateinit var requestParallelism: EditText
    private lateinit var keywordInput: EditText
    private lateinit var keywordRows: LinearLayout
    private lateinit var keywordScroll: ScrollView
    private lateinit var keywordEmpty: TextView
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
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.button_add_keyword).setOnClickListener { addKeyword() }
        findViewById<ImageButton>(R.id.button_remove_keyword).setOnClickListener {
            confirmRemoveKeyword()
        }
        configureFields()
    }

    override fun onResume() {
        super.onResume()
        populateFields()
        refreshKeywordTable()
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

    // ---------------- Keywords (termos de reforço do STT) ----------------

    private fun addKeyword() {
        val current = GrokApiSettings.sttKeywords()
        val typed = keywordInput.text.toString()
        val reason = SttKeywords.rejectionReason(typed, current)
        if (reason != null) {
            Toast.makeText(this, reason, Toast.LENGTH_SHORT).show()
            return
        }
        val term = typed.trim()
        val updated = SttKeywords.normalize(current + term)
        GrokApiSettings.setSttKeywords(updated)
        keywordInput.setText("")
        selectedKeyword = updated.indexOfFirst { it.equals(term, ignoreCase = true) }
        refreshKeywordTable()
    }

    private fun confirmRemoveKeyword() {
        val current = GrokApiSettings.sttKeywords()
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
        val current = GrokApiSettings.sttKeywords()
        if (index !in current.indices) return
        val updated = current.toMutableList().also { it.removeAt(index) }
        GrokApiSettings.setSttKeywords(updated)
        // A lista sobe: nenhuma linha fica selecionada depois da remoção.
        selectedKeyword = -1
        refreshKeywordTable()
    }

    private fun refreshKeywordTable() {
        val keywords = GrokApiSettings.sttKeywords()
        if (selectedKeyword !in keywords.indices) selectedKeyword = -1
        keywordCells.clear()
        keywordRows.removeAllViews()
        keywordScroll.visibility = if (keywords.isEmpty()) View.GONE else View.VISIBLE
        keywordEmpty.visibility = if (keywords.isEmpty()) View.VISIBLE else View.GONE
        keywords.forEachIndexed { index, term ->
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
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(
                    numberView,
                    LinearLayout.LayoutParams(dp(NUMBER_CELL_WIDTH_DP), ViewGroup.LayoutParams.WRAP_CONTENT)
                )
                addView(
                    termView,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                )
                setOnClickListener { selectKeyword(index) }
            }
            keywordRows.addView(row)
            keywordCells += numberView to termView
        }
        paintKeywordSelection()
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
        const val NUMBER_CELL_WIDTH_DP = 34
        val ACCENT_COLOR = Color.rgb(94, 218, 242)
        val SELECTION_COLOR = Color.argb(0x33, 94, 218, 242)
    }
}
