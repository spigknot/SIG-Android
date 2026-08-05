package br.gov.sp.pcsp.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max

class FfmpegInsertAudioTimelineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var onSeek: ((Long) -> Unit)? = null

    private val mainPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(94, 218, 242)
        strokeCap = Paint.Cap.ROUND
        strokeWidth = dp(2f)
    }
    private val insertedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 194, 74)
        strokeCap = Paint.Cap.ROUND
        strokeWidth = dp(2f)
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(90, 94, 218, 242)
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val divisionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 255, 255, 255)
        strokeWidth = dp(1f)
    }
    private val currentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 194, 74)
        strokeWidth = dp(2.5f)
        strokeCap = Paint.Cap.ROUND
    }
    private val rect = RectF()
    private var mainDurationMs = 1L
    private var insertedDurationMs = 0L
    private var insertionMs = 0L
    private var currentCompositeMs = 0L
    private var mainSeed = 1
    private var insertedSeed = 2

    fun configure(mainName: String, mainDurationMs: Long, insertedName: String?, insertedDurationMs: Long, insertionMs: Long) {
        this.mainDurationMs = mainDurationMs.coerceAtLeast(1L)
        this.insertedDurationMs = insertedDurationMs.coerceAtLeast(0L)
        this.insertionMs = insertionMs.coerceIn(0L, this.mainDurationMs)
        mainSeed = seedFor(mainName)
        insertedSeed = seedFor(insertedName.orEmpty())
        currentCompositeMs = currentCompositeMs.coerceIn(0L, totalDurationMs())
        invalidate()
    }

    fun setCurrent(compositePositionMs: Long) {
        currentCompositeMs = compositePositionMs.coerceIn(0L, totalDurationMs())
        invalidate()
    }

    fun totalDurationMs(): Long = (mainDurationMs + insertedDurationMs).coerceAtLeast(1L)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = paddingLeft + dp(8f)
        val right = width - paddingRight - dp(8f)
        val top = paddingTop + dp(8f)
        val bottom = height - paddingBottom - dp(8f)
        rect.set(left, top, right, bottom)
        canvas.drawRoundRect(rect, dp(7f), dp(7f), borderPaint)

        val total = totalDurationMs().toFloat()
        val firstEnd = left + (right - left) * insertionMs / total
        val insertedEnd = left + (right - left) * (insertionMs + insertedDurationMs) / total
        drawWave(canvas, left, firstEnd, top, bottom, mainSeed, mainPaint, 0)
        if (insertedDurationMs > 0L) {
            drawWave(canvas, firstEnd, insertedEnd, top, bottom, insertedSeed, insertedPaint, 1)
            canvas.drawLine(firstEnd, top, firstEnd, bottom, divisionPaint)
            canvas.drawLine(insertedEnd, top, insertedEnd, bottom, divisionPaint)
        }
        drawWave(canvas, insertedEnd, right, top, bottom, mainSeed, mainPaint, 2)

        val markerX = left + (right - left) * currentCompositeMs / total
        canvas.drawLine(markerX, top - dp(2f), markerX, bottom + dp(2f), currentPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent.requestDisallowInterceptTouchEvent(true)
                val left = paddingLeft + dp(8f)
                val right = width - paddingRight - dp(8f)
                val fraction = ((event.x - left) / max(1f, right - left)).coerceIn(0f, 1f)
                currentCompositeMs = (totalDurationMs() * fraction).toLong()
                invalidate()
                onSeek?.invoke(currentCompositeMs)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent.requestDisallowInterceptTouchEvent(false)
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun drawWave(canvas: Canvas, left: Float, right: Float, top: Float, bottom: Float, seedValue: Int, paint: Paint, offset: Int) {
        if (right - left < dp(2f)) return
        val center = (top + bottom) / 2f
        val count = max(2, ((right - left) / dp(5f)).toInt())
        val gap = (right - left) / count
        var seed = seedValue + offset * 7919
        repeat(count) { index ->
            seed = seed * 1103515245 + 12345
            val random = abs(seed % 1000) / 1000f
            val amplitude = (bottom - top) * (0.12f + random * 0.34f)
            val x = left + gap * (index + 0.5f)
            canvas.drawLine(x, center - amplitude, x, center + amplitude, paint)
        }
    }

    private fun seedFor(value: String): Int = value.fold(0x2A1F3C) { acc, char -> acc * 31 + char.code }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
