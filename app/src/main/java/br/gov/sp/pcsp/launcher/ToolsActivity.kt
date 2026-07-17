package br.gov.sp.pcsp.launcher

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import br.gov.sp.pcsp.launcher.experimental.npu.NpuTestActivity
import java.util.Locale

class ToolsActivity : AppCompatActivity() {
    private lateinit var buttonClearCache: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        keepContentInsideSystemBars()
        setContentView(R.layout.activity_tools)

        buttonClearCache = findViewById(R.id.button_clear_cache)
        buttonClearCache.setOnClickListener { clearTemporaryCache() }
        updateCacheButton()

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.button_rg_calculator).setOnClickListener {
            startActivity(
                Intent(this, RgCalculatorActivity::class.java)
                    .putExtra(RgCalculatorActivity.EXTRA_MODE, RgCalculatorActivity.MODE_RG)
            )
        }
        findViewById<View>(R.id.button_cpf_calculator).setOnClickListener {
            startActivity(
                Intent(this, RgCalculatorActivity::class.java)
                    .putExtra(RgCalculatorActivity.EXTRA_MODE, RgCalculatorActivity.MODE_CPF)
            )
        }
        findViewById<View>(R.id.button_imei_calculator).setOnClickListener {
            startActivity(Intent(this, ImeiCalculatorActivity::class.java))
        }
        findViewById<View>(R.id.button_ffmpeg).setOnClickListener {
            startActivity(Intent(this, FfmpegActivity::class.java))
        }
        findViewById<View>(R.id.button_whisper).setOnClickListener {
            startActivity(Intent(this, WhisperActivity::class.java))
        }
        findViewById<View>(R.id.button_faster_whisper_server).setOnClickListener {
            startActivity(Intent(this, FasterWhisperServerActivity::class.java))
        }
        findViewById<View>(R.id.button_remote_stt).setOnClickListener {
            startActivity(Intent(this, RemoteSttActivity::class.java))
        }
        findViewById<View>(R.id.button_grok_tests).setOnClickListener {
            startActivity(Intent(this, GrokTestActivity::class.java))
        }
        findViewById<View>(R.id.button_npu_tests).apply {
            visibility = if (BuildConfig.ENABLE_NPU_TESTS) View.VISIBLE else View.GONE
            setOnClickListener { startActivity(Intent(this@ToolsActivity, NpuTestActivity::class.java)) }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::buttonClearCache.isInitialized) updateCacheButton()
    }

    private fun clearTemporaryCache() {
        buttonClearCache.isEnabled = false
        buttonClearCache.text = "Limpando..."
        Thread {
            val removed = AppCacheManager.clearAll(this)
            runOnUiThread {
                buttonClearCache.isEnabled = true
                updateCacheButton()
                Toast.makeText(this, "Cache limpo: ${formatBytes(removed)} removidos", Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun updateCacheButton() {
        Thread {
            val size = AppCacheManager.cacheSize(this)
            runOnUiThread {
                buttonClearCache.text = "Cache: ${formatBytes(size)}"
            }
        }.start()
    }

    private fun formatBytes(bytes: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024.0 && unit < units.lastIndex) {
            value /= 1024.0
            unit++
        }
        return if (unit == 0) {
            "${bytes}B"
        } else {
            String.format(Locale.US, "%.1f%s", value, units[unit])
        }
    }
}
