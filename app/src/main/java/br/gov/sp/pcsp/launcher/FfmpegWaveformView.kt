package br.gov.sp.pcsp.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.max

class FfmpegWaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val inactivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x664E7080 }
    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC5EDAF2.toInt() }
    private val rangePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x2228D7F2 }
    private val currentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFC24A.toInt()
        strokeWidth = dp(2f)
        strokeCap = Paint.Cap.ROUND
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x335EDAF2
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }

    private val bars = FloatArray(96)
    private val rect = RectF()
    private var durationMs = 1L
    private var startMs = 0L
    private var endMs = 1L
    private var currentMs = 0L

    fun configure(seedText: String, durationMs: Long) {
        this.durationMs = max(durationMs, 1L)
        this.startMs = 0L
        this.endMs = this.durationMs
        this.currentMs = 0L
        generateBars(seedText)
        visibility = VISIBLE
        invalidate()
    }

    fun setRange(startMs: Long, endMs: Long) {
        this.startMs = startMs.coerceIn(0L, durationMs)
        this.endMs = endMs.coerceIn(this.startMs, durationMs)
        currentMs = currentMs.coerceIn(this.startMs, this.endMs)
        invalidate()
    }

    fun setCurrent(positionMs: Long) {
        currentMs = positionMs.coerceIn(startMs, endMs)
        invalidate()
    }

    fun clear() {
        currentMs = 0L
        visibility = GONE
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (durationMs <= 0L || bars.isEmpty()) return

        val left = paddingLeft + dp(8f)
        val right = width - paddingRight - dp(8f)
        val top = paddingTop + dp(6f)
        val bottom = height - paddingBottom - dp(6f)
        val centerY = (top + bottom) / 2f
        val availableWidth = max(right - left, 1f)
        val gap = dp(2f)
        val barWidth = max((availableWidth - gap * (bars.size - 1)) / bars.size, dp(1f))

        rect.set(left, top, right, bottom)
        canvas.drawRoundRect(rect, dp(8f), dp(8f), borderPaint)

        val startX = valueToX(startMs, left, right)
        val endX = valueToX(endMs, left, right)
        rect.set(startX, top, endX, bottom)
        canvas.drawRoundRect(rect, dp(7f), dp(7f), rangePaint)

        bars.forEachIndexed { index, amplitude ->
            val x = left + index * (barWidth + gap)
            val barHeight = max(dp(4f), (bottom - top) * amplitude)
            val paint = if (x <= valueToX(currentMs, left, right)) activePaint else inactivePaint
            rect.set(x, centerY - barHeight / 2f, x + barWidth, centerY + barHeight / 2f)
            canvas.drawRoundRect(rect, barWidth, barWidth, paint)
        }

        val currentX = valueToX(currentMs, left, right)
        canvas.drawLine(currentX, top + dp(3f), currentX, bottom - dp(3f), currentPaint)
    }

    private fun valueToX(value: Long, left: Float, right: Float): Float {
        val fraction = value.toFloat() / durationMs.toFloat()
        return left + (right - left) * fraction.coerceIn(0f, 1f)
    }

    private fun generateBars(seedText: String) {
        var seed = seedText.fold(0x2A1F3C) { acc, char -> acc * 31 + char.code }
        for (index in bars.indices) {
            seed = seed * 1103515245 + 12345
            val raw = abs(seed % 1000) / 1000f
            val wave = if (index % 7 == 0) 0.9f else 0.35f + raw * 0.55f
            bars[index] = wave.coerceIn(0.18f, 0.95f)
        }
    }

    private fun dp(value: Float): Float {
        return value * resources.displayMetrics.density
    }
}
