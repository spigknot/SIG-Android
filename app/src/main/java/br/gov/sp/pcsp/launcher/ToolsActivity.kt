package br.gov.sp.pcsp.launcher

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity

class ToolsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        keepContentInsideSystemBars()
        setContentView(R.layout.activity_tools)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.button_rg_calculator).setOnClickListener {
            startActivity(Intent(this, RgCalculatorActivity::class.java))
        }
        findViewById<View>(R.id.button_imei_calculator).setOnClickListener {
            startActivity(Intent(this, ImeiCalculatorActivity::class.java))
        }
        findViewById<View>(R.id.button_ffmpeg).setOnClickListener {
            startActivity(Intent(this, FfmpegActivity::class.java))
        }
    }
}
