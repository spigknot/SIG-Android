package br.gov.sp.pcsp.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class FfmpegRangeSlider @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class Thumb {
        START,
        END,
        CURRENT
    }

    var onRangeChanged: ((startMs: Long, endMs: Long, fromUser: Boolean, thumb: Thumb?) -> Unit)? = null
    var onPositionChanged: ((positionMs: Long, fromUser: Boolean) -> Unit)? = null

    private val inactivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66555555 }
    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF5EDAF2.toInt() }
    private val currentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFC24A.toInt()
        strokeWidth = dp(2.5f)
        strokeCap = Paint.Cap.ROUND
    }
    private val rangeMarkerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF4A4A.toInt()
        strokeWidth = dp(3f)
        strokeCap = Paint.Cap.ROUND
    }

    private val trackRect = RectF()
    private var durationMs = 1L
    private var startMs = 0L
    private var endMs = 1L
    private var currentMs = 0L
    private var activeThumb: Thumb? = null

    fun setRange(durationMs: Long, startMs: Long, endMs: Long, fromUser: Boolean = false) {
        this.durationMs = max(durationMs, 1L)
        this.startMs = startMs.coerceIn(0L, this.durationMs)
        this.endMs = endMs.coerceIn(this.startMs, this.durationMs)
        val previousCurrent = currentMs
        this.currentMs = currentMs.coerceIn(this.startMs, this.endMs)
        invalidate()
        onRangeChanged?.invoke(this.startMs, this.endMs, fromUser, activeThumb)
        if (currentMs != previousCurrent) {
            onPositionChanged?.invoke(currentMs, fromUser)
        }
    }

    fun setStart(startMs: Long, fromUser: Boolean = false) {
        setRange(durationMs, startMs.coerceAtMost(endMs), endMs, fromUser)
    }

    fun setEnd(endMs: Long, fromUser: Boolean = false) {
        setRange(durationMs, startMs, endMs.coerceAtLeast(startMs), fromUser)
    }

    fun getStartMs(): Long = startMs

    fun getEndMs(): Long = endMs

    fun setCurrent(positionMs: Long, fromUser: Boolean = false) {
        val nextPosition = positionMs.coerceIn(startMs, endMs)
        if (nextPosition == currentMs && !fromUser) return
        currentMs = nextPosition
        invalidate()
        onPositionChanged?.invoke(currentMs, fromUser)
    }

    fun getCurrentMs(): Long = currentMs

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val centerY = height / 2f
        val markerHalfHeight = dp(15f)
        val markerTouchInset = dp(10f)
        val left = paddingLeft + markerTouchInset
        val right = width - paddingRight - markerTouchInset
        val trackHeight = dp(5f)

        trackRect.set(left, centerY - trackHeight / 2f, right, centerY + trackHeight / 2f)
        canvas.drawRoundRect(trackRect, trackHeight, trackHeight, inactivePaint)

        val startX = valueToX(startMs)
        val endX = valueToX(endMs)
        trackRect.set(startX, centerY - trackHeight / 2f, endX, centerY + trackHeight / 2f)
        canvas.drawRoundRect(trackRect, trackHeight, trackHeight, activePaint)

        drawMarker(canvas, startX, centerY, markerHalfHeight, rangeMarkerPaint)
        drawMarker(canvas, endX, centerY, markerHalfHeight, rangeMarkerPaint)

        val currentX = valueToX(currentMs)
        drawMarker(canvas, currentX, centerY, markerHalfHeight, currentPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || durationMs <= 0L) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent.requestDisallowInterceptTouchEvent(true)
                activeThumb = nearestThumb(event.x)
                updateThumb(event.x, true)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                updateThumb(event.x, true)
                return true
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                updateThumb(event.x, true)
                activeThumb = null
                parent.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }

        return super.onTouchEvent(event)
    }

    private fun updateThumb(x: Float, fromUser: Boolean) {
        val value = xToValue(x)
        when (activeThumb) {
            Thumb.START -> {
                startMs = snapToCurrent(value).coerceIn(0L, endMs)
            }

            Thumb.END -> {
                endMs = snapToCurrent(value).coerceIn(startMs, durationMs)
            }

            Thumb.CURRENT -> {
                currentMs = value.coerceIn(startMs, endMs)
            }

            null -> return
        }
        invalidate()
        if (activeThumb == Thumb.CURRENT) {
            onPositionChanged?.invoke(currentMs, fromUser)
        } else {
            onRangeChanged?.invoke(startMs, endMs, fromUser, activeThumb)
        }
    }

    private fun nearestThumb(x: Float): Thumb {
        val startDistance = abs(x - valueToX(startMs))
        val endDistance = abs(x - valueToX(endMs))
        val currentDistance = abs(x - valueToX(currentMs))
        val touchRadius = dp(18f)

        if (currentDistance <= touchRadius) return Thumb.CURRENT
        if (startDistance <= touchRadius || endDistance <= touchRadius) {
            return if (startDistance <= endDistance) Thumb.START else Thumb.END
        }
        return Thumb.CURRENT
    }

    private fun snapToCurrent(value: Long): Long {
        val snapMs = min(max(250L, durationMs / 500L), 1500L)
        return if (abs(value - currentMs) <= snapMs) currentMs else value
    }

    private fun valueToX(value: Long): Float {
        val markerTouchInset = dp(10f)
        val left = paddingLeft + markerTouchInset
        val right = width - paddingRight - markerTouchInset
        val fraction = value.toFloat() / durationMs.toFloat()
        return left + (right - left) * fraction
    }

    private fun xToValue(x: Float): Long {
        val markerTouchInset = dp(10f)
        val left = paddingLeft + markerTouchInset
        val right = width - paddingRight - markerTouchInset
        val fraction = ((x - left) / max(right - left, 1f)).coerceIn(0f, 1f)
        return (durationMs * fraction).toLong()
    }

    private fun drawMarker(canvas: Canvas, x: Float, y: Float, halfHeight: Float, paint: Paint) {
        canvas.drawLine(x, y - halfHeight, x, y + halfHeight, paint)
    }

    private fun dp(value: Float): Float {
        return value * resources.displayMetrics.density
    }
}
