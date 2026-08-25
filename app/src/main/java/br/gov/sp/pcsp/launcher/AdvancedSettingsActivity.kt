package br.gov.sp.pcsp.launcher

import android.os.Bundle
import android.widget.EditText
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity

class AdvancedSettingsActivity : AppCompatActivity() {
    private lateinit var conversionParallelism: EditText
    private lateinit var requestParallelism: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        keepContentInsideSystemBars()
        setContentView(R.layout.activity_advanced_settings)
        conversionParallelism = findViewById(R.id.edit_conversion_parallelism)
        requestParallelism = findViewById(R.id.edit_request_parallelism)
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        configureFields()
    }

    override fun onResume() {
        super.onResume()
        populateFields()
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
}
