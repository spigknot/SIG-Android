package br.gov.sp.pcsp.launcher

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity

class CalculatorToolsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        keepContentInsideSystemBars()
        setContentView(R.layout.activity_calculator_tools)
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.button_rg_calculator).setOnClickListener {
            startActivity(Intent(this, RgCalculatorActivity::class.java)
                .putExtra(RgCalculatorActivity.EXTRA_MODE, RgCalculatorActivity.MODE_RG))
        }
        findViewById<View>(R.id.button_cpf_calculator).setOnClickListener {
            startActivity(Intent(this, RgCalculatorActivity::class.java)
                .putExtra(RgCalculatorActivity.EXTRA_MODE, RgCalculatorActivity.MODE_CPF))
        }
        findViewById<View>(R.id.button_imei_calculator).setOnClickListener {
            startActivity(Intent(this, ImeiCalculatorActivity::class.java))
        }
    }
}
