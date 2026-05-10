package br.gov.sp.pcsp.launcher

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import android.net.Uri
import android.content.ActivityNotFoundException
import androidx.browser.customtabs.CustomTabsIntent
import android.view.View
import okhttp3.*
import org.json.JSONObject
import java.io.IOException

class ToolsActivity : AppCompatActivity() {

    private val client = OkHttpClient()
    private val apiKey = "AC98-7B2E-E1DC-48A0-0F34-46VN" // sua chave de API

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tools)

        // Botão voltar discreto
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        val inputRg = findViewById<EditText>(R.id.input_rg)
        val resultRg = findViewById<TextView>(R.id.result_rg)

        val inputImei = findViewById<EditText>(R.id.input_imei)
        val resultImei = findViewById<TextView>(R.id.result_imei)
        val resultImeiModel = findViewById<TextView>(R.id.result_imei_model)

        // ----- RG: calcula DV automaticamente -----
        inputRg.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val digits = s?.toString()?.filter { it.isDigit() } ?: ""
                resultRg.text = if (digits.length < 7) {
                    "Dígito: —"
                } else {
                    if(digits.length > 8){
                        "Dígitos demais!"
                    }else{
                        "Dígito: ${computeRgDigit(digits)}"
                    }
                }
            }
        })

        // ----- IMEI: calcula DV (Luhn) automaticamente + consulta API -----
        inputImei.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val digits = s?.toString()?.replace(Regex("\\D"), "") ?: ""

                if (digits.length < 14) {
                    resultImei.text = "Dígito: —"
                    resultImeiModel.text = ""
                    return
                }

                if (digits.length > 14) {
                    resultImei.text = "Dígitos demais!"
                    resultImeiModel.text = ""
                    return
                }

                val check = computeLuhnDigit(digits.take(14))
                resultImei.text = "Dígito: $check"

                val fullImei = digits.take(14) + check.toString()
                fetchImeiInfo(fullImei, resultImeiModel)
            }
        })
    }

    // ---------- Consulta API IMEICheck ----------
    private fun fetchImeiInfo(imei: String, resultView: TextView) {
        val url =
            "https://alpha.imeicheck.com/api/free_with_key/modelBrandName?key=$apiKey&imei=$imei&format=json"

        val request = Request.Builder().url(url).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    resultView.text = "Cheque sua conexão"
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                runOnUiThread {
                    try {
                        val json = JSONObject(body ?: "{}")
                        val status = json.optString("status")

                        if (status != "succes") {
                            resultView.text = "Modelo não encontrado"
                        } else {
                            val obj = json.optJSONObject("object")
                            if (obj != null) {
                                val brand = obj.optString("brand", "—")
                                val name = obj.optString("name", "—")
                                val model = obj.optString("model", "—")

                                resultView.text = "Marca: $brand\nModelo: $model ($name)"
                            } else {
                                resultView.text = "Modelo não encontrado"
                            }
                        }
                    } catch (e: Exception) {
                        resultView.text = "Erro ao processar resposta"
                    }
                }
            }
        })
    }

    // ---------- RG (SP) ----------
    private fun computeRgDigit(number: String): String {
        val weights = intArrayOf(2, 3, 4, 5, 6, 7, 8, 9)
        var sum = 0
        number.forEachIndexed { i, ch ->
            val d = ch - '0'
            sum += d * weights[i % weights.size]
        }
        val remainder = sum % 11
        val dv = 11 - remainder
        return when (dv) {
            10 -> "X"
            11 -> "0"
            else -> dv.toString()
        }
    }

    // ---------- IMEI (Luhn) ----------
    private fun computeLuhnDigit(numberOnlyDigits: String): Int {
        val digits = numberOnlyDigits.filter { it.isDigit() }.map { it - '0' }
        var sum = 0
        val len = digits.size
        for (i in digits.indices.reversed()) {
            var d = digits[i]
            val posFromRightIfCheckAppended = (len - i) + 1
            if (posFromRightIfCheckAppended % 2 == 0) {
                d *= 2
                if (d > 9) d -= 9
            }
            sum += d
        }
        return (10 - (sum % 10)) % 10
    }
}
