package br.gov.sp.pcsp.launcher

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
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
        private const val HISTORY_FILE = "rg_history.txt"
        private const val CPF_HISTORY_FILE = "cpf_history.txt"
        private const val HISTORY_COLLAPSED_LIMIT = 10
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
    private val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("pt", "BR"))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        keepContentInsideSystemBars()
        setContentView(R.layout.activity_rg_calculator)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        rgHistoryHeaderView = findViewById(R.id.history_rg_header)
        rgHistoryView = findViewById(R.id.history_rg)
        rgToggleHistoryView = findViewById(R.id.toggle_history_rg)
        cpfHistoryHeaderView = findViewById(R.id.history_cpf_header)
        cpfHistoryView = findViewById(R.id.history_cpf)
        cpfToggleHistoryView = findViewById(R.id.toggle_history_cpf)
        val inputRg = findViewById<EditText>(R.id.input_rg)
        val resultRg = findViewById<TextView>(R.id.result_rg)
        val inputCpf = findViewById<EditText>(R.id.input_cpf)
        val resultCpf = findViewById<TextView>(R.id.result_cpf)

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

        refreshRgHistory()
        refreshCpfHistory()

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
        refreshHistory(
            rgHistoryFile(),
            rgHistoryHeaderView,
            rgHistoryView,
            rgToggleHistoryView,
            isRgHistoryExpanded
        )
    }

    private fun refreshCpfHistory() {
        refreshHistory(
            cpfHistoryFile(),
            cpfHistoryHeaderView,
            cpfHistoryView,
            cpfToggleHistoryView,
            isCpfHistoryExpanded
        )
    }

    private fun refreshHistory(
        file: File,
        headerView: View,
        historyView: TextView,
        toggleHistoryView: TextView,
        isExpanded: Boolean
    ) {
        if (!file.exists() || file.length() == 0L) {
            headerView.visibility = View.GONE
            historyView.text = ""
            historyView.visibility = View.GONE
            toggleHistoryView.visibility = View.GONE
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
