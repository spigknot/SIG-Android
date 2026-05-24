package br.gov.sp.pcsp.launcher

import android.annotation.SuppressLint
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ImeiCalculatorActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SIG-IMEI"
        private const val HISTORY_FILE = "imei_history.txt"
        private const val HISTORY_COLLAPSED_LIMIT = 10
        private const val LEGACY_PREFS_NAME = "imei_recent_cache"
        private const val LEGACY_PREFS_ITEMS = "items"
    }

    private val client = OkHttpClient()
    private val apiKey = "AC98-7B2E-E1DC-48A0-0F34-46VN"
    private val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("pt", "BR"))
    private var lastProcessedImei = ""

    private lateinit var historyHeaderView: View
    private lateinit var historyView: TextView
    private lateinit var toggleHistoryView: TextView
    private lateinit var resultModel: TextView
    private var isHistoryExpanded = false
    private var isFormattingImei = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        keepContentInsideSystemBars()
        setContentView(R.layout.activity_imei_calculator)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        historyHeaderView = findViewById(R.id.history_imei_header)
        historyView = findViewById(R.id.history_imei)
        toggleHistoryView = findViewById(R.id.toggle_history_imei)
        configureHistoryBox(historyView)
        resultModel = findViewById(R.id.result_imei_model)
        val inputImeiTac = findViewById<EditText>(R.id.input_imei_tac)
        val inputImeiSn = findViewById<EditText>(R.id.input_imei_sn)
        val resultImei = findViewById<TextView>(R.id.result_imei)

        toggleHistoryView.setOnClickListener {
            isHistoryExpanded = !isHistoryExpanded
            refreshHistory()
        }
        findViewById<ImageButton>(R.id.clear_history_imei).setOnClickListener {
            confirmClearHistory()
        }

        migrateLegacyHistoryIfNeeded()
        refreshHistory()

        val imeiWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                updateImeiInputs(inputImeiTac, inputImeiSn, resultImei)
            }
        }
        inputImeiTac.addTextChangedListener(imeiWatcher)
        inputImeiSn.addTextChangedListener(imeiWatcher)
        inputImeiSn.setOnKeyListener { _, keyCode, event ->
            val shouldMoveBack = event.action == KeyEvent.ACTION_DOWN &&
                keyCode == KeyEvent.KEYCODE_DEL &&
                inputImeiSn.text.isEmpty() &&
                inputImeiTac.text.isNotEmpty()

            if (!shouldMoveBack) {
                false
            } else {
                val tacDigits = inputImeiTac.text.toString().filter { it.isDigit() }.dropLast(1)
                isFormattingImei = true
                inputImeiTac.setText(tacDigits)
                inputImeiTac.setSelection(inputImeiTac.text.length)
                isFormattingImei = false
                inputImeiTac.requestFocus()
                processImeiDigits(tacDigits, resultImei)
                true
            }
        }
    }

    private fun configureHistoryBox(historyView: TextView) {
        historyView.movementMethod = ScrollingMovementMethod.getInstance()
        historyView.setOnTouchListener { view, _ ->
            view.parent.requestDisallowInterceptTouchEvent(true)
            false
        }
    }

    private fun updateImeiInputs(
        inputImeiTac: EditText,
        inputImeiSn: EditText,
        resultImei: TextView
    ) {
        if (isFormattingImei) return

        val tacDigits = inputImeiTac.text.toString().filter { it.isDigit() }
        val snDigits = inputImeiSn.text.toString().filter { it.isDigit() }
        var newTacDigits = tacDigits.take(8)
        var newSnDigits = snDigits.take(6)
        var moveFocusToSn = false

        if (inputImeiTac.hasFocus() && tacDigits.length > 8) {
            val combinedDigits = (tacDigits + snDigits).take(14)
            newTacDigits = combinedDigits.take(8)
            newSnDigits = combinedDigits.drop(8).take(6)
            moveFocusToSn = true
        }

        if (newTacDigits != inputImeiTac.text.toString() || newSnDigits != inputImeiSn.text.toString()) {
            isFormattingImei = true
            inputImeiTac.setText(newTacDigits)
            inputImeiSn.setText(newSnDigits)
            if (moveFocusToSn) {
                inputImeiSn.requestFocus()
                inputImeiSn.setSelection(inputImeiSn.text.length)
            } else if (inputImeiTac.hasFocus()) {
                inputImeiTac.setSelection(inputImeiTac.text.length)
            } else {
                inputImeiSn.setSelection(inputImeiSn.text.length)
            }
            isFormattingImei = false
        } else if (moveFocusToSn) {
            inputImeiSn.requestFocus()
            inputImeiSn.setSelection(inputImeiSn.text.length)
        }

        processImeiDigits(newTacDigits + newSnDigits, resultImei)
    }

    private fun processImeiDigits(digits: String, resultImei: TextView) {
        when {
            digits.length < 14 -> {
                resultImei.text = "Dígito: —"
                resultModel.text = ""
                lastProcessedImei = ""
            }
            digits.length > 14 -> {
                resultImei.text = "Dígitos demais!"
                resultModel.text = ""
                lastProcessedImei = ""
            }
            else -> {
                val check = computeLuhnDigit(digits)
                val fullImei = digits + check.toString()
                resultImei.text = "Dígito: $check"

                if (fullImei == lastProcessedImei) return
                lastProcessedImei = fullImei

                val cached = findHistoryRecord(fullImei)
                if (cached != null) {
                    resultModel.text = formatModel(cached)
                    Log.i(TAG, "Modelo recuperado do histórico: ${formatModel(cached)}")
                    return
                }

                resultModel.text = "Consultando modelo..."
                fetchImeiInfo(fullImei)
            }
        }
    }

    private fun fetchImeiInfo(imei: String) {
        val url =
            "https://alpha.imeicheck.com/api/free_with_key/modelBrandName?key=$apiKey&imei=$imei&format=json"
        val request = Request.Builder().url(url).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    resultModel.text = "Cheque sua conexão"
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                runOnUiThread {
                    try {
                        val json = JSONObject(body ?: "{}")
                        if (json.optString("status") != "succes") {
                            resultModel.text = "Modelo não encontrado"
                            return@runOnUiThread
                        }

                        val obj = json.optJSONObject("object")
                        if (obj == null) {
                            resultModel.text = "Modelo não encontrado"
                            return@runOnUiThread
                        }

                        val record = JSONObject()
                            .put("time", System.currentTimeMillis())
                            .put("imei", imei)
                            .put("brand", obj.optString("brand", "—"))
                            .put("model", obj.optString("model", "—"))
                            .put("name", obj.optString("name", "—"))

                        appendHistory(record)
                        resultModel.text = formatModel(record)
                        refreshHistory()
                        Log.i(TAG, "Modelo consultado e salvo no histórico: ${formatModel(record)}")
                    } catch (e: Exception) {
                        resultModel.text = "Erro ao processar resposta"
                    }
                }
            }
        })
    }

    private fun computeLuhnDigit(numberOnlyDigits: String): Int {
        val digits = numberOnlyDigits.filter { it.isDigit() }.map { it - '0' }
        var sum = 0
        val len = digits.size
        for (i in digits.indices.reversed()) {
            var digit = digits[i]
            val posFromRightIfCheckAppended = (len - i) + 1
            if (posFromRightIfCheckAppended % 2 == 0) {
                digit *= 2
                if (digit > 9) digit -= 9
            }
            sum += digit
        }
        return (10 - (sum % 10)) % 10
    }

    private fun appendHistory(record: JSONObject) {
        historyFile().appendText(record.toString() + "\n", Charsets.UTF_8)
    }

    private fun migrateLegacyHistoryIfNeeded() {
        if (historyFile().exists() && historyFile().length() > 0L) return

        val raw = getSharedPreferences(LEGACY_PREFS_NAME, MODE_PRIVATE)
            .getString(LEGACY_PREFS_ITEMS, null)
            ?: return

        val legacyItems = try {
            JSONArray(raw)
        } catch (e: Exception) {
            return
        }

        for (i in legacyItems.length() - 1 downTo 0) {
            val item = legacyItems.optJSONObject(i) ?: continue
            val imei = item.optString("imei")
            if (imei.isBlank()) continue

            val record = JSONObject()
                .put("time", item.optLong("time", System.currentTimeMillis()))
                .put("imei", imei)
                .put("brand", item.optString("brand", "—"))
                .put("model", item.optString("model", "—"))
                .put("name", item.optString("name", "—"))
            appendHistory(record)
        }
    }

    private fun refreshHistory() {
        val records = readHistoryRecords()
        if (records.isEmpty()) {
            historyHeaderView.visibility = View.GONE
            historyView.text = ""
            historyView.visibility = View.GONE
            toggleHistoryView.visibility = View.GONE
            return
        }

        val reversed = records.asReversed()
        val visibleRecords = if (isHistoryExpanded) {
            reversed
        } else {
            reversed.take(HISTORY_COLLAPSED_LIMIT)
        }

        historyHeaderView.visibility = View.VISIBLE
        historyView.visibility = View.VISIBLE
        historyView.text = visibleRecords
            .joinToString("\n\n") { formatHistoryItem(it) }
        toggleHistoryView.visibility =
            if (reversed.size > HISTORY_COLLAPSED_LIMIT) View.VISIBLE else View.GONE
        toggleHistoryView.text = if (isHistoryExpanded) "ver menos" else "ver mais"
    }

    private fun confirmClearHistory() {
        AlertDialog.Builder(this)
            .setMessage("limpar histórico?")
            .setPositiveButton("sim") { _, _ ->
                historyFile().writeText("", Charsets.UTF_8)
                lastProcessedImei = ""
                resultModel.text = ""
                isHistoryExpanded = false
                refreshHistory()
            }
            .setNegativeButton("não", null)
            .show()
    }

    private fun findHistoryRecord(imei: String): JSONObject? {
        return readHistoryRecords()
            .asReversed()
            .firstOrNull { it.optString("imei") == imei }
    }

    private fun readHistoryRecords(): List<JSONObject> {
        val file = historyFile()
        if (!file.exists() || file.length() == 0L) return emptyList()

        return file.readLines(Charsets.UTF_8).mapNotNull { line ->
            try {
                JSONObject(line)
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun formatModel(record: JSONObject): String {
        val brand = record.optString("brand", "—")
        val model = record.optString("model", "—")
        val name = record.optString("name", "—")
        return "Marca: $brand\nModelo: $model ($name)"
    }

    private fun formatHistoryItem(record: JSONObject): String {
        return buildString {
            append(formatTime(record.optLong("time", 0L)))
            append('\n')
            append("IMEI: ")
            append(record.optString("imei", ""))
            append('\n')
            append(formatModel(record))
        }
    }

    private fun historyFile(): File = File(filesDir, HISTORY_FILE)

    private fun formatTime(time: Long): String {
        return if (time > 0L) dateFormat.format(Date(time)) else "Data indisponível"
    }
}
