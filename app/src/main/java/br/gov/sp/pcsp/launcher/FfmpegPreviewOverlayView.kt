package br.gov.sp.pcsp.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot

/** Sobreposição do player FFmpeg: desenha a seleção de área e trata os toques.
 *
 * Port do palco de prévia do SIG Windows, com os gestos do toque:
 * - 1 dedo arrasta o quadro (pan) quando ele está ampliado;
 * - 2 dedos dão zoom (pinça) mantendo sob os dedos o mesmo ponto da imagem;
 * - toque longo inicia o DESENHO da seleção (o quadro amarelo fino);
 * - com uma seleção existente: arrastar por dentro move, pelas alças/cantos
 *   redimensiona, e um toque longo parado abre o menu ("Desfazer seleção").
 *
 * A seleção é guardada em FRAÇÕES do quadro (como no Windows), então ela
 * acompanha zoom, arrasto e redimensionamento da tela. Não abre Activity nem
 * FFMpeg: só desenha e devolve valores. */
class FfmpegPreviewOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private enum class Mode { NONE, PAN, DRAW, MOVE, RESIZE, PINCH }

    var selection: FfmpegPreviewSelection.Selection? = null
        private set

    /** Avisa quando o enquadramento (zoom/deslocamento) muda. */
    var onViewportChanged: (() -> Unit)? = null

    /** Avisa quando a seleção muda (inclusive ao ser apagada). */
    var onSelectionChanged: ((FfmpegPreviewSelection.Selection?) -> Unit)? = null

    /** Avisa quando o desenho TERMINA (soltar o dedo) — para o aviso ao usuário. */
    var onSelectionCommitted: ((FfmpegPreviewSelection.Selection?) -> Unit)? = null

    /** Pedido do menu da seleção (toque longo parado sobre ela). */
    var onSelectionMenuRequested: (() -> Unit)? = null

    private var zoom = FfmpegPreviewSelection.ZOOM_MIN
    private var offsetX = 0.0
    private var offsetY = 0.0

    private var videoWidth = 0
    private var videoHeight = 0

    private val density = resources.displayMetrics.density
    private val tolerance = FfmpegPreviewSelection.SELECTION_HANDLE * density
    private val minimumPixels = FfmpegPreviewSelection.SELECTION_MIN_SIZE * density

    private val outlinePaint = Paint().apply {
        style = Paint.Style.STROKE
        color = FfmpegPreviewSelection.SELECTION_OUTLINE_COLOR
        strokeWidth = (FfmpegPreviewSelection.SELECTION_OUTLINE_WIDTH * density).toFloat()
        isAntiAlias = true
    }
    private val handlePaint = Paint().apply {
        style = Paint.Style.FILL
        color = FfmpegPreviewSelection.SELECTION_OUTLINE_COLOR
        isAntiAlias = true
    }
    private val handleRadiusPx = (FfmpegPreviewSelection.SELECTION_HANDLE_SIZE * density / 2.0).toFloat()

    private var mode = Mode.NONE
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var movedEnough = false
    private var pinchDistance = 1f
    private var activeHandle: String? = null
    private var dragStartSelection: FfmpegPreviewSelection.Selection? = null
    private var drawStart: Pair<Double, Double>? = null

    private val longPress = Runnable {
        if (mode == Mode.PAN || mode == Mode.MOVE) {
            if (mode == Mode.MOVE) {
                onSelectionMenuRequested?.invoke()
            } else {
                mode = Mode.DRAW
                drawStart = fractionAt(downX.toDouble(), downY.toDouble())
            }
        }
    }

    /** Tamanho natural (ou exibido) da mídia — define o recorte em pixels.
     * `resetSelection = false` quando o mesmo vídeo só mudou de orientação. */
    fun setMediaSize(width: Int, height: Int, resetSelection: Boolean = true) {
        videoWidth = width
        videoHeight = height
        if (resetSelection) reset() else invalidate()
    }

    fun viewportZoom(): Double = zoom

    fun viewportOffsetX(): Double = offsetX

    fun viewportOffsetY(): Double = offsetY

    fun canPan(): Boolean {
        val (drawnWidth, drawnHeight, _) = drawnSize()
        return drawnWidth > width || drawnHeight > height
    }

    /** Volta ao enquadramento inicial e apaga a seleção (nova mídia). */
    fun reset() {
        zoom = FfmpegPreviewSelection.ZOOM_MIN
        offsetX = 0.0
        offsetY = 0.0
        mode = Mode.NONE
        dragStartSelection = null
        drawStart = null
        selection = null
        invalidate()
        onViewportChanged?.invoke()
        onSelectionChanged?.invoke(null)
    }

    fun clearSelection() {
        if (selection == null) return
        selection = null
        invalidate()
        onSelectionChanged?.invoke(null)
    }

    /** Recorte da seleção em pixels do vídeo; `null` quando não há seleção. */
    fun selectionCropPixels(): IntArray? =
        selection?.let { FfmpegPreviewSelection.cropPixels(it, videoWidth, videoHeight) }

    /** Reexpressa a seleção quando os filtros de giro mudam (Girar). */
    fun reexpressSelection(oldFilters: String, newFilters: String) {
        val current = selection ?: return
        selection = FfmpegPreviewSelection.betweenFilters(current, oldFilters, newFilters)
        invalidate()
        onSelectionChanged?.invoke(selection)
    }

    private fun drawnSize(): Triple<Int, Int, Double> =
        FfmpegPreviewSelection.drawnSize(width.coerceAtLeast(2), height.coerceAtLeast(2), zoom)

    private fun origin(): Pair<Int, Int> {
        val (drawnWidth, drawnHeight, _) = drawnSize()
        return FfmpegPreviewSelection.viewRect(
            width.coerceAtLeast(2), height.coerceAtLeast(2), drawnWidth, drawnHeight, offsetX, offsetY
        )
    }

    private fun selectionViewRect(): List<Double>? {
        val current = selection ?: return null
        val (drawnWidth, drawnHeight, _) = drawnSize()
        val (originX, originY) = origin()
        return current.toView(drawnWidth, drawnHeight, originX.toDouble(), originY.toDouble())
    }

    private fun fractionAt(x: Double, y: Double): Pair<Double, Double> {
        val (drawnWidth, drawnHeight, _) = drawnSize()
        val (originX, originY) = origin()
        return FfmpegPreviewSelection.fractionFromView(x, y, drawnWidth, drawnHeight, originX.toDouble(), originY.toDouble())
    }

    private fun minimumFractionX(): Double = FfmpegPreviewSelection.minimumFraction(minimumPixels, drawnSize().first)

    private fun minimumFractionY(): Double = FfmpegPreviewSelection.minimumFraction(minimumPixels, drawnSize().second)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val rect = selectionViewRect() ?: return
        canvas.drawRect(
            rect[0].toFloat(), rect[1].toFloat(), rect[2].toFloat(), rect[3].toFloat(), outlinePaint
        )
        // Alças dos quatro cantos (referência para redimensionar no toque).
        val corners = listOf(
            rect[0] to rect[1], rect[2] to rect[1], rect[0] to rect[3], rect[2] to rect[3]
        )
        corners.forEach { (x, y) ->
            canvas.drawCircle(x.toFloat(), y.toFloat(), handleRadiusPx, handlePaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (videoWidth <= 0 || videoHeight <= 0) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // O gesto que começa DENTRO do player é do player: sem isso o
                // ScrollView da tela rouba o arrasto vertical (o quadro não anda)
                // e o toque longo é cancelado antes de virar desenho da seleção.
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = event.x
                downY = event.y
                lastX = event.x
                lastY = event.y
                movedEnough = false
                val rect = selectionViewRect()
                val handle = rect?.let {
                    FfmpegPreviewSelection.handleAt(it, event.x.toDouble(), event.y.toDouble(), tolerance)
                }
                activeHandle = handle
                dragStartSelection = selection
                mode = when (handle) {
                    null -> Mode.PAN
                    "move" -> Mode.MOVE
                    else -> Mode.RESIZE
                }
                if (handle != "move") {
                    postDelayed(longPress, LONG_PRESS_MILLIS)
                } else {
                    postDelayed(longPress, LONG_PRESS_MILLIS)
                }
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                removeCallbacks(longPress)
                if (event.pointerCount >= 2) {
                    mode = Mode.PINCH
                    pinchDistance = pointerDistance(event)
                    val (focalX, focalY) = pointerCenter(event)
                    lastX = focalX
                    lastY = focalY
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2) {
                    handlePinch(event)
                    return true
                }
                val dx = event.x - lastX
                val dy = event.y - lastY
                if (!movedEnough && hypot((event.x - downX).toDouble(), (event.y - downY).toDouble()) >
                    FfmpegPreviewSelection.SELECTION_DRAG_THRESHOLD * density
                ) {
                    movedEnough = true
                    removeCallbacks(longPress)
                }
                when (mode) {
                    Mode.DRAW -> handleDraw(event)
                    Mode.MOVE -> handleMove(event, dragStartSelection)
                    Mode.RESIZE -> handleResize(event)
                    Mode.PINCH -> Unit
                    Mode.PAN -> if (movedEnough) handlePan(dx, dy)
                    Mode.NONE -> Unit
                }
                lastX = event.x
                lastY = event.y
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                parent?.requestDisallowInterceptTouchEvent(false)
                if (mode == Mode.DRAW) {
                    val start = drawStart
                    val end = fractionAt(event.x.toDouble(), event.y.toDouble())
                    if (start != null && event.actionMasked == MotionEvent.ACTION_UP) {
                        val built = FfmpegPreviewSelection.fromDrag(
                            start.first, start.second, end.first, end.second,
                            minimumFractionX(), minimumFractionY()
                        )
                        selection = built
                        invalidate()
                        onSelectionChanged?.invoke(built)
                        onSelectionCommitted?.invoke(built)
                    } else {
                        selection = null
                        invalidate()
                        onSelectionChanged?.invoke(null)
                        onSelectionCommitted?.invoke(null)
                    }
                }
                mode = Mode.NONE
                activeHandle = null
                drawStart = null
                dragStartSelection = null
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun handleDraw(event: MotionEvent) {
        val start = drawStart ?: return
        val end = fractionAt(event.x.toDouble(), event.y.toDouble())
        selection = FfmpegPreviewSelection.fromDrag(
            start.first, start.second, end.first, end.second,
            minimumFractionX(), minimumFractionY()
        )
        invalidate()
        onSelectionChanged?.invoke(selection)
    }

    private fun handleMove(event: MotionEvent, base: FfmpegPreviewSelection.Selection?) {
        val start = base ?: return
        val (drawnWidth, drawnHeight, _) = drawnSize()
        val deltaX = (event.x - downX) / drawnWidth.coerceAtLeast(1).toDouble()
        val deltaY = (event.y - downY) / drawnHeight.coerceAtLeast(1).toDouble()
        selection = FfmpegPreviewSelection.moved(start, deltaX, deltaY)
        invalidate()
        onSelectionChanged?.invoke(selection)
    }

    private fun handleResize(event: MotionEvent) {
        val handle = activeHandle ?: return
        val base = dragStartSelection ?: return
        val (fractionX, fractionY) = fractionAt(event.x.toDouble(), event.y.toDouble())
        selection = FfmpegPreviewSelection.resized(
            base, handle, fractionX, fractionY, minimumFractionX(), minimumFractionY()
        )
        invalidate()
        onSelectionChanged?.invoke(selection)
    }

    private fun handlePan(dx: Float, dy: Float) {
        val (drawnWidth, drawnHeight, _) = drawnSize()
        offsetX = FfmpegPreviewSelection.clampedOffset(width, drawnWidth, offsetX + dx)
        offsetY = FfmpegPreviewSelection.clampedOffset(height, drawnHeight, offsetY + dy)
        invalidate()
        onViewportChanged?.invoke()
    }

    private fun handlePinch(event: MotionEvent) {
        val distance = pointerDistance(event)
        if (pinchDistance <= 1f || distance <= 1f) {
            pinchDistance = distance.coerceAtLeast(1f)
            return
        }
        val (focalX, focalY) = pointerCenter(event)
        val (drawnWidth, drawnHeight, _) = drawnSize()
        val requested = zoom * (distance / pinchDistance)
        val (zoomedWidth, zoomedHeight, effective) = FfmpegPreviewSelection.drawnSize(width, height, requested)
        if (zoomedWidth == drawnWidth && zoomedHeight == drawnHeight) {
            pinchDistance = distance
            return
        }
        val (offsetNewX, offsetNewY) = FfmpegPreviewSelection.zoomOffsets(
            width, height, drawnWidth, drawnHeight, offsetX, offsetY,
            zoomedWidth, zoomedHeight, focalX.toDouble(), focalY.toDouble()
        )
        zoom = effective
        offsetX = FfmpegPreviewSelection.clampedOffset(width, zoomedWidth, offsetNewX)
        offsetY = FfmpegPreviewSelection.clampedOffset(height, zoomedHeight, offsetNewY)
        pinchDistance = distance
        invalidate()
        onViewportChanged?.invoke()
    }

    private fun pointerDistance(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 1f
        val dx = event.getX(1) - event.getX(0)
        val dy = event.getY(1) - event.getY(0)
        return hypot(dx.toDouble(), dy.toDouble()).toFloat().coerceAtLeast(1f)
    }

    private fun pointerCenter(event: MotionEvent): Pair<Float, Float> {
        if (event.pointerCount < 2) return event.x to event.y
        return (event.getX(0) + event.getX(1)) / 2f to (event.getY(0) + event.getY(1)) / 2f
    }

    /** Frações ↔ pixels são estáveis; o desenho acompanha o enquadramento. */
    fun hasSelection(): Boolean = selection != null

    companion object {
        private const val LONG_PRESS_MILLIS = 450L
    }
}
