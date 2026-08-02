package br.gov.sp.pcsp.launcher

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class ImeiSettingsActivity : AppCompatActivity() {
    private lateinit var apiKeyInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        keepContentInsideSystemBars()
        setContentView(R.layout.activity_imei_settings)

        apiKeyInput = findViewById(R.id.edit_imei_api_key)
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.button_save_imei_api_key).setOnClickListener {
            ImeiApiSettings.setApiKey(apiKeyInput.text.toString())
            Toast.makeText(this, "API KEY salva.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        apiKeyInput.setText(ImeiApiSettings.apiKey())
        apiKeyInput.setSelection(apiKeyInput.text.length)
    }
}
