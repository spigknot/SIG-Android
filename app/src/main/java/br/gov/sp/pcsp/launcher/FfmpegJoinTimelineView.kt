package br.gov.sp.pcsp.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextUtils
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max

class FfmpegJoinTimelineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class Clip(
        val id: Long,
        val title: String,
        val durationMs: Long,
        val thumbnail: Bitmap?
    )

    var onOrderChanged: ((List<Long>) -> Unit)? = null

    private val clips = mutableListOf<Clip>()
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(42, 0, 0, 0) }
    private val segmentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(94, 10, 20, 24) }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(94, 218, 242)
        style = Paint.Style.STROKE
        strokeWidth = dp(1.2f)
    }
    private val activeBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(120, 255, 174)
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }
    private val snapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 190, 70)
        strokeWidth = dp(1.4f)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(11f)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 255, 255, 255)
        textSize = dp(10f)
        typeface = android.graphics.Typeface.MONOSPACE
    }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private var itemWidth = dp(122f)
    private val itemGap = dp(7f)
    private val topPadding = dp(10f)
    private val sidePadding = dp(12f)
    private var draggingIndex = -1

    fun setClips(items: List<Clip>) {
        clips.clear()
        clips.addAll(items)
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (clips.isNotEmpty()) {
            val screenWidth = resources.displayMetrics.widthPixels
            val availableWidth = screenWidth - dp(40f)
            val totalGap = max(0, clips.size - 1) * itemGap + (sidePadding * 2)
            itemWidth = max(dp(16f), (availableWidth - totalGap) / clips.size.toFloat())
        } else {
            itemWidth = dp(122f)
        }
        val desiredWidth = (sidePadding * 2 + clips.size * itemWidth + max(0, clips.size - 1) * itemGap).toInt()
        val desiredHeight = dp(128f).toInt()
        setMeasuredDimension(resolveSize(max(desiredWidth, suggestedMinimumWidth), widthMeasureSpec), resolveSize(desiredHeight, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val radius = dp(6f)
        val full = RectF(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(full, radius, radius, bgPaint)

        if (clips.isEmpty()) {
            return
        }

        clips.forEachIndexed { index, clip ->
            val left = sidePadding + index * (itemWidth + itemGap)
            val top = topPadding
            val rect = RectF(left, top, left + itemWidth, height - topPadding)
            canvas.drawRoundRect(rect, radius, radius, segmentPaint)
            drawThumbnail(canvas, clip.thumbnail, rect)
            canvas.drawRoundRect(rect, radius, radius, if (index == draggingIndex) activeBorderPaint else borderPaint)
            canvas.drawLine(rect.left, rect.bottom + dp(3f), rect.right, rect.bottom + dp(3f), snapPaint)

            val title = TextUtils.ellipsize(clip.title, android.text.TextPaint(textPaint), rect.width() - dp(14f), TextUtils.TruncateAt.MIDDLE)
            canvas.drawText(title.toString(), rect.left + dp(7f), rect.bottom - dp(22f), textPaint)
            canvas.drawText(formatDuration(clip.durationMs), rect.left + dp(7f), rect.bottom - dp(7f), timePaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (clips.isEmpty()) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                draggingIndex = indexAt(event.x)
                if (draggingIndex >= 0) {
                    parent.requestDisallowInterceptTouchEvent(true)
                    invalidate()
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (draggingIndex >= 0) {
                    val targetIndex = indexAt(event.x).coerceIn(0, clips.lastIndex)
                    if (targetIndex != draggingIndex) {
                        val clip = clips.removeAt(draggingIndex)
                        clips.add(targetIndex, clip)
                        draggingIndex = targetIndex
                        onOrderChanged?.invoke(clips.map { it.id })
                        requestLayout()
                        invalidate()
                    }
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (draggingIndex >= 0) {
                    draggingIndex = -1
                    parent.requestDisallowInterceptTouchEvent(false)
                    invalidate()
                    return true
                }
            }
        }
        return true
    }

    private fun drawThumbnail(canvas: Canvas, bitmap: Bitmap?, rect: RectF) {
        if (bitmap == null) return
        val target = RectF(rect.left + dp(5f), rect.top + dp(5f), rect.right - dp(5f), rect.bottom - dp(34f))
        val scale = max(target.width() / bitmap.width.toFloat(), target.height() / bitmap.height.toFloat())
        val width = bitmap.width * scale
        val height = bitmap.height * scale
        val srcLeft = ((width - target.width()) / 2f / scale).coerceAtLeast(0f)
        val srcTop = ((height - target.height()) / 2f / scale).coerceAtLeast(0f)
        val srcRight = (bitmap.width - srcLeft).coerceAtMost(bitmap.width.toFloat())
        val srcBottom = (bitmap.height - srcTop).coerceAtMost(bitmap.height.toFloat())
        val src = android.graphics.Rect(srcLeft.toInt(), srcTop.toInt(), srcRight.toInt(), srcBottom.toInt())
        canvas.drawBitmap(bitmap, src, target, bitmapPaint)
    }

    private fun indexAt(x: Float): Int {
        return (((x - sidePadding) / (itemWidth + itemGap)).toInt()).coerceIn(0, clips.lastIndex)
    }

    private fun formatDuration(durationMs: Long): String {
        val seconds = (durationMs / 1000).coerceAtLeast(0)
        val minutes = seconds / 60
        val rest = seconds % 60
        return "%02d:%02d".format(minutes, rest)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
