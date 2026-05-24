package br.gov.sp.pcsp.launcher

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RgCalculatorActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MODE = "calculator_mode"
        const val MODE_RG = "rg"
        const val MODE_CPF = "cpf"

        private const val MODE_BOTH = "both"
        private const val HISTORY_FILE = "rg_history.txt"
        private const val CPF_HISTORY_FILE = "cpf_history.txt"
        private const val HISTORY_COLLAPSED_LIMIT = 10
        private const val MAX_POSSIBILITIES = 5000
        private const val MAX_SEARCH_SPACE = 1_000_000L
    }

    private var lastSavedRg = ""
    private var lastSavedCpf = ""
    private var pendingRgHistorySave: Runnable? = null
    private var pendingCpfHistorySave: Runnable? = null
    private val historyHandler = Handler(Looper.getMainLooper())
    private lateinit var rgHistoryHeaderView: View
    private lateinit var rgHistoryView: TextView
    private lateinit var rgToggleHistoryView: TextView
    private lateinit var cpfHistoryHeaderView: View
    private lateinit var cpfHistoryView: TextView
    private lateinit var cpfToggleHistoryView: TextView
    private var isRgHistoryExpanded = false
    private var isCpfHistoryExpanded = false
    private var isFormattingRg = false
    private var isFormattingCpf = false
    private var calculatorMode = MODE_BOTH
    private val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("pt", "BR"))

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        keepContentInsideSystemBars()
        setContentView(R.layout.activity_rg_calculator)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        calculatorMode = intent.getStringExtra(EXTRA_MODE)
            ?.takeIf { it == MODE_RG || it == MODE_CPF }
            ?: MODE_BOTH

        rgHistoryHeaderView = findViewById(R.id.history_rg_header)
        rgHistoryView = findViewById(R.id.history_rg)
        rgToggleHistoryView = findViewById(R.id.toggle_history_rg)
        cpfHistoryHeaderView = findViewById(R.id.history_cpf_header)
        cpfHistoryView = findViewById(R.id.history_cpf)
        cpfToggleHistoryView = findViewById(R.id.toggle_history_cpf)
        configureHistoryBox(rgHistoryView)
        configureHistoryBox(cpfHistoryView)
        val titleView = findViewById<TextView>(R.id.calculator_title)
        val labelRg = findViewById<TextView>(R.id.label_rg)
        val labelCpf = findViewById<TextView>(R.id.label_cpf)
        val inputRg = findViewById<EditText>(R.id.input_rg)
        val resultRg = findViewById<TextView>(R.id.result_rg)
        val rgPossibilitiesPanel = findViewById<View>(R.id.rg_possibilities_panel)
        val inputRgPattern = findViewById<EditText>(R.id.input_rg_pattern)
        val resultRgPossibilities = findViewById<TextView>(R.id.result_rg_possibilities)
        val inputCpf = findViewById<EditText>(R.id.input_cpf)
        val resultCpf = findViewById<TextView>(R.id.result_cpf)
        val cpfPossibilitiesPanel = findViewById<View>(R.id.cpf_possibilities_panel)
        val inputCpfPattern = findViewById<EditText>(R.id.input_cpf_pattern)
        val resultCpfPossibilities = findViewById<TextView>(R.id.result_cpf_possibilities)
        configureHistoryBox(resultRgPossibilities)
        configureHistoryBox(resultCpfPossibilities)
        titleView.text = when (calculatorMode) {
            MODE_RG -> "Calculadora de RG"
            MODE_CPF -> "Calculadora de CPF"
            else -> "Calculadora de Dígitos"
        }
        setVisibleIfNeeded(listOf(labelRg, inputRg, resultRg, rgPossibilitiesPanel), showsRg())
        setVisibleIfNeeded(listOf(labelCpf, inputCpf, resultCpf, cpfPossibilitiesPanel), showsCpf())

        rgToggleHistoryView.setOnClickListener {
            isRgHistoryExpanded = !isRgHistoryExpanded
            refreshRgHistory()
        }
        findViewById<ImageButton>(R.id.clear_history_rg).setOnClickListener {
            confirmClearRgHistory()
        }
        cpfToggleHistoryView.setOnClickListener {
            isCpfHistoryExpanded = !isCpfHistoryExpanded
            refreshCpfHistory()
        }
        findViewById<ImageButton>(R.id.clear_history_cpf).setOnClickListener {
            confirmClearCpfHistory()
        }
        findViewById<TextView>(R.id.button_find_rg_possibilities).setOnClickListener {
            showRgPossibilities(inputRgPattern.text?.toString().orEmpty(), resultRgPossibilities)
        }
        findViewById<TextView>(R.id.button_find_cpf_possibilities).setOnClickListener {
            showCpfPossibilities(inputCpfPattern.text?.toString().orEmpty(), resultCpfPossibilities)
        }

        refreshRgHistory()
        refreshCpfHistory()

        when {
            showsRg() -> inputRg.requestFocus()
            showsCpf() -> inputCpf.requestFocus()
        }

        inputRg.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                if (isFormattingRg) return
                val digits = s?.toString()?.filter { it.isDigit() } ?: ""
                val limitedDigits = digits.take(8)
                val formatted = formatRg(limitedDigits)
                if (s?.toString() != formatted) {
                    isFormattingRg = true
                    inputRg.setText(formatted)
                    inputRg.setSelection(formatted.length)
                    isFormattingRg = false
                }

                when {
                    limitedDigits.length < 8 -> {
                        cancelPendingRgHistorySave()
                        resultRg.text = "Dígito: —"
                    }
                    else -> {
                        val digit = computeRgDigit(limitedDigits)
                        resultRg.text = "Dígito: $digit"
                        scheduleRgHistorySave(limitedDigits, digit)
                    }
                }
            }
        })

        inputCpf.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                if (isFormattingCpf) return
                val digits = s?.toString()?.filter { it.isDigit() } ?: ""
                val limitedDigits = digits.take(9)
                val formatted = formatCpf(limitedDigits)
                if (s?.toString() != formatted) {
                    isFormattingCpf = true
                    inputCpf.setText(formatted)
                    inputCpf.setSelection(formatted.length)
                    isFormattingCpf = false
                }

                when {
                    limitedDigits.length < 9 -> {
                        cancelPendingCpfHistorySave()
                        resultCpf.text = "Dígitos: —"
                    }
                    else -> {
                        val checkDigits = computeCpfDigits(limitedDigits)
                        resultCpf.text = "Dígitos: $checkDigits"
                        scheduleCpfHistorySave(limitedDigits, checkDigits)
                    }
                }
            }
        })
    }

    private fun configureHistoryBox(historyView: TextView) {
        historyView.movementMethod = ScrollingMovementMethod.getInstance()
        historyView.setOnTouchListener { view, _ ->
            view.parent.requestDisallowInterceptTouchEvent(true)
            false
        }
    }

    private fun setVisibleIfNeeded(views: List<View>, isVisible: Boolean) {
        val visibility = if (isVisible) View.VISIBLE else View.GONE
        views.forEach { it.visibility = visibility }
    }

    override fun onDestroy() {
        cancelPendingRgHistorySave()
        cancelPendingCpfHistorySave()
        super.onDestroy()
    }

    private fun computeRgDigit(number: String): String {
        val weights = intArrayOf(2, 3, 4, 5, 6, 7, 8, 9)
        var sum = 0
        number.forEachIndexed { i, ch ->
            val digit = ch - '0'
            sum += digit * weights[i % weights.size]
        }
        val remainder = sum % 11
        val checkDigit = 11 - remainder
        return when (checkDigit) {
            10 -> "X"
            11 -> "0"
            else -> checkDigit.toString()
        }
    }

    private fun computeCpfDigits(baseNineDigits: String): String {
        val first = computeCpfDigit(baseNineDigits, 10)
        val second = computeCpfDigit(baseNineDigits + first, 11)
        return "$first$second"
    }

    private fun computeCpfDigit(number: String, initialWeight: Int): Int {
        val sum = number.mapIndexed { index, ch ->
            (ch - '0') * (initialWeight - index)
        }.sum()
        val remainder = sum % 11
        return if (remainder < 2) 0 else 11 - remainder
    }

    private fun showRgPossibilities(rawPattern: String, resultView: TextView) {
        val pattern = normalizeRgPattern(rawPattern)
        if (pattern.isEmpty()) {
            showPossibilitiesResult(resultView, "Informe pelo menos um dígito conhecido.")
            return
        }

        val basePattern = pattern.take(8)
        val checkPattern = pattern.getOrNull(8)
        if (basePattern.any { it != '?' && !it.isDigit() }) {
            showPossibilitiesResult(resultView, "Use X apenas na posição do dígito verificador.")
            return
        }
        val unknownBaseCount = basePattern.count { it == '?' }
        val searchSpace = pow10(unknownBaseCount)
        if (searchSpace > MAX_SEARCH_SPACE) {
            showPossibilitiesResult(
                resultView,
                "Muitas combinações possíveis. Informe mais posições conhecidas."
            )
            return
        }

        val matches = mutableListOf<String>()
        generateCombinations(basePattern) { base ->
            val digit = computeRgDigit(base)
            if (checkPattern == null || checkPattern == '?' || checkPattern.toString() == digit) {
                matches += "${formatRg(base)}-$digit"
            }
            matches.size < MAX_POSSIBILITIES
        }
        showPossibilitiesResult(resultView, formatPossibilities(matches))
    }

    private fun showCpfPossibilities(rawPattern: String, resultView: TextView) {
        val pattern = normalizeCpfPattern(rawPattern)
        if (pattern.isEmpty()) {
            showPossibilitiesResult(resultView, "Informe pelo menos um dígito conhecido.")
            return
        }

        val basePattern = pattern.take(9)
        val checkPattern = pattern.drop(9)
        val unknownBaseCount = basePattern.count { it == '?' }
        val searchSpace = pow10(unknownBaseCount)
        if (searchSpace > MAX_SEARCH_SPACE) {
            showPossibilitiesResult(
                resultView,
                "Muitas combinações possíveis. Informe mais posições conhecidas."
            )
            return
        }

        val matches = mutableListOf<String>()
        generateCombinations(basePattern) { base ->
            val digits = computeCpfDigits(base)
            val matchesCheckDigits = checkPattern.withIndex().all { (index, ch) ->
                ch == '?' || ch == digits[index]
            }
            if (matchesCheckDigits) {
                matches += "${formatCpf(base)}-$digits"
            }
            matches.size < MAX_POSSIBILITIES
        }
        showPossibilitiesResult(resultView, formatPossibilities(matches))
    }

    private fun normalizeRgPattern(rawPattern: String): String {
        val known = rawPattern.uppercase(Locale.ROOT)
            .mapNotNull { ch ->
                when {
                    ch.isDigit() -> ch
                    ch == 'X' -> ch
                    ch == '?' || ch == '_' || ch == '*' -> '?'
                    else -> null
                }
            }
            .take(9)
            .joinToString("")
        if (known.isEmpty()) return ""
        return known.padEnd(9, '?')
    }

    private fun normalizeCpfPattern(rawPattern: String): String {
        val known = rawPattern
            .mapNotNull { ch ->
                when {
                    ch.isDigit() -> ch
                    ch == '?' || ch == '_' || ch == '*' -> '?'
                    else -> null
                }
            }
            .take(11)
            .joinToString("")
        if (known.isEmpty()) return ""
        return known.padEnd(11, '?')
    }

    private fun generateCombinations(pattern: String, onCandidate: (String) -> Boolean) {
        val chars = pattern.toCharArray()

        fun fill(index: Int): Boolean {
            if (index == chars.size) {
                return onCandidate(String(chars))
            }
            if (chars[index] != '?') return fill(index + 1)
            for (digit in '0'..'9') {
                chars[index] = digit
                if (!fill(index + 1)) {
                    chars[index] = '?'
                    return false
                }
            }
            chars[index] = '?'
            return true
        }

        fill(0)
    }

    private fun pow10(exponent: Int): Long {
        var value = 1L
        repeat(exponent) { value *= 10L }
        return value
    }

    private fun formatPossibilities(matches: List<String>): String {
        if (matches.isEmpty()) return "Nenhuma possibilidade encontrada."
        val suffix = if (matches.size >= MAX_POSSIBILITIES) {
            "\n\nMostrando as primeiras $MAX_POSSIBILITIES possibilidades."
        } else {
            ""
        }
        return "${matches.size} possibilidade(s):\n\n${matches.joinToString("\n")}$suffix"
    }

    private fun showPossibilitiesResult(resultView: TextView, text: String) {
        resultView.text = text
        resultView.visibility = View.VISIBLE
    }

    private fun formatRg(digits: String): String {
        return buildString {
            digits.forEachIndexed { index, ch ->
                if (index == 2 || index == 5) append('.')
                append(ch)
            }
        }
    }

    private fun formatCpf(digits: String): String {
        return buildString {
            digits.forEachIndexed { index, ch ->
                if (index == 3 || index == 6) append('.')
                append(ch)
            }
        }
    }

    private fun appendRgHistory(number: String, digit: String) {
        val line = "${formatTime(System.currentTimeMillis())} | RG: ${formatRg(number)} | Dígito: $digit\n"
        rgHistoryFile().appendText(line, Charsets.UTF_8)
    }

    private fun appendCpfHistory(number: String, digits: String) {
        val line = "${formatTime(System.currentTimeMillis())} | CPF: ${formatCpf(number)}-$digits | Dígitos: $digits\n"
        cpfHistoryFile().appendText(line, Charsets.UTF_8)
    }

    private fun scheduleRgHistorySave(number: String, digit: String) {
        cancelPendingRgHistorySave()
        pendingRgHistorySave = Runnable {
            if (number != lastSavedRg) {
                lastSavedRg = number
                appendRgHistory(number, digit)
                refreshRgHistory()
            }
        }
        historyHandler.postDelayed(pendingRgHistorySave!!, 700L)
    }

    private fun scheduleCpfHistorySave(number: String, digits: String) {
        cancelPendingCpfHistorySave()
        pendingCpfHistorySave = Runnable {
            if (number != lastSavedCpf) {
                lastSavedCpf = number
                appendCpfHistory(number, digits)
                refreshCpfHistory()
            }
        }
        historyHandler.postDelayed(pendingCpfHistorySave!!, 700L)
    }

    private fun cancelPendingRgHistorySave() {
        pendingRgHistorySave?.let { historyHandler.removeCallbacks(it) }
        pendingRgHistorySave = null
    }

    private fun cancelPendingCpfHistorySave() {
        pendingCpfHistorySave?.let { historyHandler.removeCallbacks(it) }
        pendingCpfHistorySave = null
    }

    private fun refreshRgHistory() {
        if (!showsRg()) {
            hideHistory(rgHistoryHeaderView, rgHistoryView, rgToggleHistoryView)
            return
        }
        refreshHistory(
            rgHistoryFile(),
            rgHistoryHeaderView,
            rgHistoryView,
            rgToggleHistoryView,
            isRgHistoryExpanded
        )
    }

    private fun refreshCpfHistory() {
        if (!showsCpf()) {
            hideHistory(cpfHistoryHeaderView, cpfHistoryView, cpfToggleHistoryView)
            return
        }
        refreshHistory(
            cpfHistoryFile(),
            cpfHistoryHeaderView,
            cpfHistoryView,
            cpfToggleHistoryView,
            isCpfHistoryExpanded
        )
    }

    private fun showsRg(): Boolean = calculatorMode != MODE_CPF

    private fun showsCpf(): Boolean = calculatorMode != MODE_RG

    private fun hideHistory(headerView: View, historyView: TextView, toggleHistoryView: TextView) {
        headerView.visibility = View.GONE
        historyView.text = ""
        historyView.visibility = View.GONE
        toggleHistoryView.visibility = View.GONE
    }

    private fun refreshHistory(
        file: File,
        headerView: View,
        historyView: TextView,
        toggleHistoryView: TextView,
        isExpanded: Boolean
    ) {
        if (!file.exists() || file.length() == 0L) {
            hideHistory(headerView, historyView, toggleHistoryView)
            return
        }

        val records = file.readLines(Charsets.UTF_8).asReversed()
        val visibleRecords = if (isExpanded) {
            records
        } else {
            records.take(HISTORY_COLLAPSED_LIMIT)
        }

        headerView.visibility = View.VISIBLE
        historyView.visibility = View.VISIBLE
        historyView.text = visibleRecords.joinToString("\n")
        toggleHistoryView.visibility =
            if (records.size > HISTORY_COLLAPSED_LIMIT) View.VISIBLE else View.GONE
        toggleHistoryView.text = if (isExpanded) "ver menos" else "ver mais"
    }

    private fun confirmClearRgHistory() {
        AlertDialog.Builder(this)
            .setMessage("limpar histórico?")
            .setPositiveButton("sim") { _, _ ->
                rgHistoryFile().writeText("", Charsets.UTF_8)
                lastSavedRg = ""
                isRgHistoryExpanded = false
                refreshRgHistory()
            }
            .setNegativeButton("não", null)
            .show()
    }

    private fun confirmClearCpfHistory() {
        AlertDialog.Builder(this)
            .setMessage("limpar histórico?")
            .setPositiveButton("sim") { _, _ ->
                cpfHistoryFile().writeText("", Charsets.UTF_8)
                lastSavedCpf = ""
                isCpfHistoryExpanded = false
                refreshCpfHistory()
            }
            .setNegativeButton("não", null)
            .show()
    }

    private fun rgHistoryFile(): File = File(filesDir, HISTORY_FILE)

    private fun cpfHistoryFile(): File = File(filesDir, CPF_HISTORY_FILE)

    private fun formatTime(time: Long): String = dateFormat.format(Date(time))
}
