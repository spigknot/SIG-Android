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

/** Timeline used by the join preview. Each clip occupies its own proportional segment. */
class FfmpegJoinPlaybackTimelineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class Segment(val name: String, val durationMs: Long, val isAudio: Boolean)

    var onSeek: ((Long) -> Unit)? = null

    private val audioPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(94, 218, 242)
        strokeWidth = dp(2f)
        strokeCap = Paint.Cap.ROUND
    }
    private val videoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(150, 170, 255)
        strokeWidth = dp(2f)
        strokeCap = Paint.Cap.ROUND
    }
    private val separatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 255, 255, 255)
        strokeWidth = dp(1f)
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 194, 74)
        strokeWidth = dp(2.5f)
        strokeCap = Paint.Cap.ROUND
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(90, 94, 218, 242)
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val rect = RectF()
    private val segments = mutableListOf<Segment>()
    private var currentMs = 0L

    fun setSegments(items: List<Segment>) {
        segments.clear()
        segments.addAll(items.map { it.copy(durationMs = it.durationMs.coerceAtLeast(1L)) })
        currentMs = currentMs.coerceIn(0L, totalDurationMs())
        isEnabled = segments.isNotEmpty()
        invalidate()
    }

    fun setCurrent(positionMs: Long) {
        currentMs = positionMs.coerceIn(0L, totalDurationMs())
        invalidate()
    }

    fun totalDurationMs(): Long = segments.sumOf { it.durationMs }.coerceAtLeast(1L)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (segments.isEmpty()) return

        val left = paddingLeft + dp(8f)
        val right = width - paddingRight - dp(8f)
        val top = paddingTop + dp(8f)
        val bottom = height - paddingBottom - dp(8f)
        val total = totalDurationMs().toFloat()
        rect.set(left, top, right, bottom)
        canvas.drawRoundRect(rect, dp(7f), dp(7f), borderPaint)

        var cursor = left
        segments.forEachIndexed { index, segment ->
            val segmentWidth = (right - left) * segment.durationMs / total
            val segmentRight = if (index == segments.lastIndex) right else cursor + segmentWidth
            drawWave(canvas, cursor, segmentRight, top, bottom, segment.name, if (segment.isAudio) audioPaint else videoPaint)
            if (index < segments.lastIndex) canvas.drawLine(segmentRight, top, segmentRight, bottom, separatorPaint)
            cursor = segmentRight
        }

        val markerX = left + (right - left) * currentMs / total
        canvas.drawLine(markerX, top - dp(2f), markerX, bottom + dp(2f), markerPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || segments.isEmpty()) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent.requestDisallowInterceptTouchEvent(true)
                val left = paddingLeft + dp(8f)
                val right = width - paddingRight - dp(8f)
                val fraction = ((event.x - left) / max(1f, right - left)).coerceIn(0f, 1f)
                currentMs = (totalDurationMs() * fraction).toLong()
                invalidate()
                onSeek?.invoke(currentMs)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent.requestDisallowInterceptTouchEvent(false)
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun drawWave(canvas: Canvas, left: Float, right: Float, top: Float, bottom: Float, seedText: String, paint: Paint) {
        if (right - left < dp(2f)) return
        val center = (top + bottom) / 2f
        val count = max(2, ((right - left) / dp(5f)).toInt())
        val gap = (right - left) / count
        var seed = seedText.fold(0x2A1F3C) { acc, char -> acc * 31 + char.code }
        repeat(count) { index ->
            seed = seed * 1103515245 + 12345
            val random = abs(seed % 1000) / 1000f
            val envelope = 0.45f + 0.55f * abs(kotlin.math.sin((index + 1) * 0.43f))
            val amplitude = (bottom - top) * (0.12f + random * 0.34f) * envelope
            val x = left + gap * (index + 0.5f)
            canvas.drawLine(x, center - amplitude, x, center + amplitude, paint)
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
