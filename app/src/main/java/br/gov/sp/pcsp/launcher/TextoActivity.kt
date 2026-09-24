package br.gov.sp.pcsp.launcher

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** Ferramenta Texto: tradução de texto com o modelo Hy-MT2 (Tencent, 1.25 bit).
 *
 * Tela mínima (pedido do usuário): caixa de entrada, botão Traduzir e caixa de
 * saída. A chamada externa é um POST em `/v1/chat/completions` do llama.cpp
 * (endpoint em `ServiceEndpoints`); prompt e payload são do seam `HyMt2Translator`.
 */
class TextoActivity : AppCompatActivity() {

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /** Tradução de texto grande em CPU pode demorar; folga nos timeouts do OkHttp. */
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .callTimeout(180, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .build()
    }

    private lateinit var inputText: EditText
    private lateinit var outputText: EditText
    private lateinit var buttonTranslate: TextView
    private lateinit var status: TextView
    private var translating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        keepContentInsideSystemBars()
        setContentView(R.layout.activity_texto)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        inputText = findViewById(R.id.input_text)
        outputText = findViewById(R.id.output_text)
        buttonTranslate = findViewById(R.id.button_translate)
        status = findViewById(R.id.status)

        buttonTranslate.setOnClickListener { translate() }
        findViewById<TextView>(R.id.button_clear_translation).setOnClickListener {
            inputText.setText("")
            outputText.setText("")
            status.text = ""
        }
        findViewById<ImageButton>(R.id.button_copy_translation).setOnClickListener { copyTranslation() }
    }

    private fun translate() {
        if (translating) return
        val source = inputText.text.toString().trim()
        if (source.isBlank()) {
            status.text = "Digite o texto para traduzir."
            return
        }
        translating = true
        buttonTranslate.isEnabled = false
        status.text = "Traduzindo..."
        val payload = HyMt2Translator.buildRequestPayload(source)
        Thread {
            try {
                val request = Request.Builder()
                    .url(ServiceEndpoints.SERVER_HYMT2)
                    .post(payload.toRequestBody(jsonMediaType))
                    .build()
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        throw IllegalStateException("Servidor respondeu HTTP ${response.code}: ${body.take(300)}")
                    }
                    val translation = HyMt2Translator.parseTranslation(body)
                    runOnUiThread { showTranslation(translation) }
                }
            } catch (error: Exception) {
                runOnUiThread {
                    translating = false
                    buttonTranslate.isEnabled = true
                    status.text = "Erro: ${error.message ?: error.javaClass.simpleName}"
                }
            }
        }.start()
    }

    private fun showTranslation(translation: String) {
        translating = false
        buttonTranslate.isEnabled = true
        outputText.setText(translation)
        status.text = "Tradução pronta."
    }

    private fun copyTranslation() {
        val text = outputText.text.toString()
        if (text.isBlank()) {
            status.text = "Não há tradução para copiar."
            return
        }
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("tradução", text))
        Toast.makeText(this, "Tradução copiada", Toast.LENGTH_SHORT).show()
    }
}
