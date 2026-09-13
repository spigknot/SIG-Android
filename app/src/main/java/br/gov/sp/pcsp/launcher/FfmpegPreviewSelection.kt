package br.gov.sp.pcsp.launcher

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Geometria do player das ferramentas FFmpeg: zoom, deslocamento e seleção de área.
 *
 * Port fiel do palco de prévia do SIG Windows (`PreviewViewport`,
 * `PreviewSelection` e as funções `preview_*`/`selection_*`), com as mesmas
 * fórmulas: zoom de 1× (vídeo inteiro) a 5×, deslocamento que nunca deixa
 * aparecer fundo, e seleção guardada em FRAÇÕES (0..1) do quadro — é o que faz
 * a seleção sobreviver ao zoom, ao arrasto e ao redimensionamento da tela.
 *
 * Diferença de propósito em relação ao Windows: sem orçamento de pixels de
 * imagem (lá o quadro é um bitmap reconstruído a cada frame; aqui é a própria
 * textura do vídeo transformada pela Matrix). Medidas em dp quando são de tela.
 *
 * Não toca Android: recebe números e devolve números. */
object FfmpegPreviewSelection {

    const val ZOOM_MIN = 1.0
    const val ZOOM_MAX = 5.0
    const val ZOOM_STEP = 1.25

    /** Tamanho mínimo da seleção (dp) e tolerância das alças (dp). */
    const val SELECTION_MIN_SIZE = 8.0
    const val SELECTION_HANDLE = 7.0
    const val SELECTION_HANDLE_SIZE = 4.0

    /** Movimento mínimo para o toque longo virar desenho (abaixo disso é toque). */
    const val SELECTION_DRAG_THRESHOLD = 4.0

    const val SELECTION_OUTLINE_COLOR = 0xFFFFD700.toInt()
    const val SELECTION_OUTLINE_WIDTH = 1.0

    /** Área escolhida pelo usuário, em FRAÇÕES (0..1) do quadro. */
    data class Selection(
        val left: Double,
        val top: Double,
        val right: Double,
        val bottom: Double
    ) {
        val width: Double get() = max(0.0, right - left)
        val height: Double get() = max(0.0, bottom - top)

        /** Retângulo na tela sobre o quadro DESENHADO (que já inclui o zoom). */
        fun toView(drawnWidth: Int, drawnHeight: Int, originX: Double, originY: Double): List<Double> {
            val escalaX = max(1, drawnWidth).toDouble()
            val escalaY = max(1, drawnHeight).toDouble()
            return listOf(
                left * escalaX + originX,
                top * escalaY + originY,
                right * escalaX + originX,
                bottom * escalaY + originY
            )
        }
    }

    fun zoomClamped(zoom: Double): Double = max(ZOOM_MIN, min(ZOOM_MAX, zoom))

    /** Tamanho desenhado do quadro (par) e zoom efetivo. */
    fun drawnSize(stageWidth: Int, stageHeight: Int, zoom: Double): Triple<Int, Int, Double> {
        val safeWidth = max(2, stageWidth)
        val safeHeight = max(2, stageHeight)
        val effective = zoomClamped(zoom)
        val width = max(2, (safeWidth * effective).roundToInt())
        val height = max(2, (safeHeight * effective).roundToInt())
        // O scale/pad do FFmpeg exige dimensões pares.
        return Triple(width - width % 2, height - height % 2, effective)
    }

    /** Deslocamento válido: nunca deixa aparecer fundo enquanto ampliado. */
    fun clampedOffset(stage: Int, drawn: Int, offset: Double): Double {
        if (drawn <= stage) return 0.0
        return min(0.0, max((stage - drawn).toDouble(), offset.roundToInt().toDouble()))
    }

    /** Canto superior esquerdo do quadro no palco (centralizado quando menor). */
    fun viewRect(
        stageWidth: Int,
        stageHeight: Int,
        drawnWidth: Int,
        drawnHeight: Int,
        offsetX: Double,
        offsetY: Double
    ): Pair<Int, Int> {
        val x = if (drawnWidth <= stageWidth) {
            (stageWidth - drawnWidth) / 2
        } else {
            clampedOffset(stageWidth, drawnWidth, offsetX).roundToInt()
        }
        val y = if (drawnHeight <= stageHeight) {
            (stageHeight - drawnHeight) / 2
        } else {
            clampedOffset(stageHeight, drawnHeight, offsetY).roundToInt()
        }
        return x to y
    }

    /** Deslocamentos que mantêm sob o dedo/cursor o mesmo ponto da imagem. */
    fun zoomOffsets(
        stageWidth: Int,
        stageHeight: Int,
        drawnWidth: Int,
        drawnHeight: Int,
        offsetX: Double,
        offsetY: Double,
        zoomedWidth: Int,
        zoomedHeight: Int,
        focalX: Double,
        focalY: Double
    ): Pair<Double, Double> {
        val (originX, originY) = viewRect(stageWidth, stageHeight, drawnWidth, drawnHeight, offsetX, offsetY)
        val ratioX = (focalX - originX) / max(1, drawnWidth).toDouble()
        val ratioY = (focalY - originY) / max(1, drawnHeight).toDouble()
        return (focalX - ratioX * zoomedWidth) to (focalY - ratioY * zoomedHeight)
    }

    /** Fração (0..1) do quadro para um ponto da tela, limitada ao vídeo. */
    fun fractionFromView(
        x: Double,
        y: Double,
        drawnWidth: Int,
        drawnHeight: Int,
        originX: Double,
        originY: Double
    ): Pair<Double, Double> {
        val escalaX = max(1, drawnWidth).toDouble()
        val escalaY = max(1, drawnHeight).toDouble()
        val fractionX = (x - originX) / escalaX
        val fractionY = (y - originY) / escalaY
        return min(1.0, max(0.0, fractionX)) to min(1.0, max(0.0, fractionY))
    }

    fun minimumFraction(pixels: Double, drawn: Int): Double = pixels / max(1, drawn).toDouble()

    /** Seleção a partir de dois cantos (frações) — `null` quando é pequena demais. */
    fun fromDrag(
        startX: Double,
        startY: Double,
        endX: Double,
        endY: Double,
        minimumFractionX: Double,
        minimumFractionY: Double
    ): Selection? {
        var left = min(startX, endX)
        var right = max(startX, endX)
        var top = min(startY, endY)
        var bottom = max(startY, endY)
        left = min(1.0, max(0.0, left))
        top = min(1.0, max(0.0, top))
        right = min(1.0, max(0.0, right))
        bottom = min(1.0, max(0.0, bottom))
        if (right - left < minimumFractionX || bottom - top < minimumFractionY) return null
        return Selection(left, top, right, bottom)
    }

    /** Alça sob o toque (coords de tela): n/s/e/w/cantos, "move" ou `null`. */
    fun handleAt(
        rect: List<Double>,
        x: Double,
        y: Double,
        tolerance: Double = SELECTION_HANDLE
    ): String? {
        val (left, top, right, bottom) = rect
        val noX = Pair(abs(x - left) <= tolerance, abs(x - right) <= tolerance)
        val noY = Pair(abs(y - top) <= tolerance, abs(y - bottom) <= tolerance)
        val dentroX = x >= left - tolerance && x <= right + tolerance
        val dentroY = y >= top - tolerance && y <= bottom + tolerance
        if (!(dentroX && dentroY)) return null
        if (noX.first && noY.first) return "nw"
        if (noX.second && noY.first) return "ne"
        if (noX.first && noY.second) return "sw"
        if (noX.second && noY.second) return "se"
        if (noY.first) return "n"
        if (noY.second) return "s"
        if (noX.first) return "w"
        if (noX.second) return "e"
        if (x >= left && x <= right && y >= top && y <= bottom) return "move"
        return null
    }

    /** Novo retângulo ao arrastar um lado/canto (respeita mínimo e limites). */
    fun resized(
        selection: Selection,
        handle: String,
        fractionX: Double,
        fractionY: Double,
        minimumFractionX: Double,
        minimumFractionY: Double
    ): Selection {
        val safeX = min(1.0, max(0.0, fractionX))
        val safeY = min(1.0, max(0.0, fractionY))
        var left = selection.left
        var top = selection.top
        var right = selection.right
        var bottom = selection.bottom
        if ("w" in handle) left = min(safeX, right - minimumFractionX)
        if ("e" in handle) right = max(safeX, left + minimumFractionX)
        if ("n" in handle) top = min(safeY, bottom - minimumFractionY)
        if ("s" in handle) bottom = max(safeY, top + minimumFractionY)
        return Selection(
            min(1.0, max(0.0, left)),
            min(1.0, max(0.0, top)),
            min(1.0, max(0.0, right)),
            min(1.0, max(0.0, bottom))
        )
    }

    /** Move a seleção sem deixá-la sair do quadro. */
    fun moved(selection: Selection, deltaX: Double, deltaY: Double): Selection {
        val largura = selection.width
        val altura = selection.height
        val left = min(max(0.0, selection.left + deltaX), 1.0 - largura)
        val top = min(max(0.0, selection.top + deltaY), 1.0 - altura)
        return Selection(left, top, left + largura, top + altura)
    }

    /** (x, y, largura, altura) em PIXELS do vídeo, par e dentro do quadro. */
    fun cropPixels(selection: Selection, videoWidth: Int, videoHeight: Int): IntArray? {
        if (videoWidth <= 0 || videoHeight <= 0) return null
        if (selection.width <= 0.0 || selection.height <= 0.0) return null
        var x0 = (selection.left * videoWidth).roundToInt()
        var y0 = (selection.top * videoHeight).roundToInt()
        var x1 = (selection.right * videoWidth).roundToInt()
        var y1 = (selection.bottom * videoHeight).roundToInt()
        x0 = min(max(0, x0), max(0, videoWidth - 2))
        y0 = min(max(0, y0), max(0, videoHeight - 2))
        x1 = min(max(x0 + 2, x1), videoWidth)
        y1 = min(max(y0 + 2, y1), videoHeight)
        val largura = max(2, (x1 - x0) - (x1 - x0) % 2)
        val altura = max(2, (y1 - y0) - (y1 - y0) % 2)
        return intArrayOf(x0, y0, largura, altura)
    }

    /** Filtro de recorte do FFmpeg para a seleção. */
    fun cropFilter(crop: IntArray): String {
        require(crop.size >= 4) { "crop precisa de x, y, largura e altura" }
        return "crop=${crop[2]}:${crop[3]}:${crop[0]}:${crop[1]}"
    }

    /** Operações atômicas de giro/espelhamento na ordem em que são aplicadas. */
    fun filterAtoms(filters: String): List<String> =
        filters.split(',').map(String::trim).filter { it in ATOMS }

    private val ATOMS = setOf("transpose=1", "transpose=2", "hflip", "vflip")

    /** Seleção reexpressa depois de UMA operação (em frações do quadro resultante). */
    fun afterFilterAtom(selection: Selection, atom: String): Selection = when (atom) {
        "hflip" -> Selection(1.0 - selection.right, selection.top, 1.0 - selection.left, selection.bottom)
        "vflip" -> Selection(selection.left, 1.0 - selection.bottom, selection.right, 1.0 - selection.top)
        "transpose=1" -> Selection(1.0 - selection.bottom, selection.left, 1.0 - selection.top, selection.right)
        "transpose=2" -> Selection(selection.top, 1.0 - selection.right, selection.bottom, 1.0 - selection.left)
        else -> selection
    }

    /** Reexpressa a seleção quando o giro muda: ela continua sobre os MESMOS pixels.
     *
     * Desfaz o giro antigo (ordem inversa, operações invertidas) e aplica o novo. */
    fun betweenFilters(selection: Selection, oldFilters: String, newFilters: String): Selection {
        val inverted = mapOf(
            "hflip" to "hflip",
            "vflip" to "vflip",
            "transpose=1" to "transpose=2",
            "transpose=2" to "transpose=1"
        )
        var current = selection
        filterAtoms(oldFilters).reversed().forEach { atom ->
            inverted[atom]?.let { current = afterFilterAtom(current, it) }
        }
        filterAtoms(newFilters).forEach { atom ->
            current = afterFilterAtom(current, atom)
        }
        return current
    }
}
