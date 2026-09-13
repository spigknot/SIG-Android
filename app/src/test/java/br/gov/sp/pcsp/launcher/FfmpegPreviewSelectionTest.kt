package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Vacina da geometria do player (zoom, deslocamento e seleção de área).
 *
 * Regras que estes testes travam (as mesmas do SIG Windows):
 * - o quadro é selecionado em FRAÇÕES e a seleção acompanha zoom, arrasto e
 *   redimensionamento da tela (fica exatamente sobre os mesmos pixels);
 * - as alças são os quatro cantos + os quatro lados + "move" no meio;
 * - o zoom out para no vídeo inteiro (1×) e o zoom in vai até 5×;
 * - o recorte em pixels é par e cabe dentro do quadro;
 * - girar/espelhar reexpressa a seleção (e voltar ao giro original a restaura).
 */
class FfmpegPreviewSelectionTest {

    private fun selection(left: Double, top: Double, right: Double, bottom: Double) =
        FfmpegPreviewSelection.Selection(left, top, right, bottom)

    @Test
    fun arrastoConstroiUmRetanguloNormalizado() {
        val selection = FfmpegPreviewSelection.fromDrag(0.2, 0.3, 0.8, 0.9, 0.01, 0.01)
        assertNotNull(selection)
        assertEquals(0.2, selection!!.left, 1e-9)
        assertEquals(0.3, selection.top, 1e-9)
        assertEquals(0.8, selection.right, 1e-9)
        assertEquals(0.9, selection.bottom, 1e-9)
    }

    @Test
    fun arrastoFuncionaEmQualquerDirecao() {
        val direta = FfmpegPreviewSelection.fromDrag(0.2, 0.2, 0.7, 0.6, 0.01, 0.01)
        val inversa = FfmpegPreviewSelection.fromDrag(0.7, 0.6, 0.2, 0.2, 0.01, 0.01)
        assertEquals(direta, inversa)
    }

    @Test
    fun arrastoELimitadoAoVideo() {
        val selection = FfmpegPreviewSelection.fromDrag(-0.5, -0.2, 1.8, 2.0, 0.01, 0.01)
        assertEquals(0.0, selection!!.left, 1e-9)
        assertEquals(0.0, selection.top, 1e-9)
        assertEquals(1.0, selection.right, 1e-9)
        assertEquals(1.0, selection.bottom, 1e-9)
    }

    @Test
    fun arrastoMinimoNaoViraselecao() {
        assertNull(FfmpegPreviewSelection.fromDrag(0.1, 0.1, 0.1, 0.1, 0.02, 0.02))
        assertNull(FfmpegPreviewSelection.fromDrag(0.1, 0.1, 0.11, 0.5, 0.02, 0.02))
    }

    @Test
    fun alcasDeTodosOsLadosECantos() {
        val rect = listOf(100.0, 50.0, 300.0, 200.0)
        val cases = mapOf(
            "nw" to (100.0 to 50.0), "ne" to (300.0 to 50.0),
            "sw" to (100.0 to 200.0), "se" to (300.0 to 200.0),
            "n" to (200.0 to 50.0), "s" to (200.0 to 200.0),
            "w" to (100.0 to 125.0), "e" to (300.0 to 125.0),
            "move" to (200.0 to 125.0)
        )
        cases.forEach { (expected, point) ->
            assertEquals(expected, FfmpegPreviewSelection.handleAt(rect, point.first, point.second))
        }
        assertNull(FfmpegPreviewSelection.handleAt(rect, 1000.0, 1000.0))
        assertNull(FfmpegPreviewSelection.handleAt(rect, 200.0, 400.0))
    }

    @Test
    fun redimensionaPorLadoECanto() {
        val base = selection(0.2, 0.2, 0.8, 0.8)
        val oeste = FfmpegPreviewSelection.resized(base, "w", 0.5, 0.0, 0.01, 0.01)
        assertEquals(0.5, oeste.left, 1e-9)
        assertEquals(0.8, oeste.right, 1e-9)
        val nordeste = FfmpegPreviewSelection.resized(base, "ne", 0.95, 0.05, 0.01, 0.01)
        assertEquals(0.95, nordeste.right, 1e-9)
        assertEquals(0.05, nordeste.top, 1e-9)
        assertEquals(0.2, nordeste.left, 1e-9)
        assertEquals(0.8, nordeste.bottom, 1e-9)
    }

    @Test
    fun redimensionamentoRespeitaMinimoELimites() {
        val base = selection(0.2, 0.2, 0.8, 0.8)
        // arrastar o lado oeste além do leste não inverte nem zera
        val apertado = FfmpegPreviewSelection.resized(base, "w", 0.99, 0.0, 0.05, 0.05)
        assertTrue(apertado.width <= 0.8 + 1e-9)
        assertTrue(apertado.width >= 0.05 - 1e-9)
        val fora = FfmpegPreviewSelection.resized(base, "se", 9.0, 9.0, 0.01, 0.01)
        assertTrue(fora.right <= 1.0)
        assertTrue(fora.bottom <= 1.0)
    }

    @Test
    fun moverMantemASelecaoDentroDoQuadro() {
        val base = selection(0.2, 0.2, 0.6, 0.5)
        val movida = FfmpegPreviewSelection.moved(base, 5.0, 5.0)
        assertEquals(1.0, movida.right, 1e-9)
        assertEquals(1.0, movida.bottom, 1e-9)
        assertEquals(base.width, movida.width, 1e-9)
        val voltando = FfmpegPreviewSelection.moved(base, -5.0, -5.0)
        assertEquals(0.0, voltando.left, 1e-9)
        assertEquals(0.0, voltando.top, 1e-9)
    }

    @Test
    fun retanguloNaTelaAcompanhaZoomEDeslocamento() {
        val base = selection(0.25, 0.5, 0.75, 1.0)
        assertEquals(
            listOf(200.0, 200.0, 600.0, 400.0),
            base.toView(800, 400, 0.0, 0.0)
        )
        assertEquals(
            listOf(300.0, 350.0, 1100.0, 750.0),
            base.toView(1600, 800, -100.0, -50.0)
        )
    }

    @Test
    fun viewEFracaoSaoInversas() {
        val random = Random(5)
        repeat(200) {
            val stageWidth = random.nextInt(200, 1600)
            val stageHeight = random.nextInt(150, 900)
            val zoom = FfmpegPreviewSelection.ZOOM_MIN +
                random.nextDouble() * (FfmpegPreviewSelection.ZOOM_MAX - FfmpegPreviewSelection.ZOOM_MIN)
            val (drawnWidth, drawnHeight, _) = FfmpegPreviewSelection.drawnSize(stageWidth, stageHeight, zoom)
            val offsetX = -random.nextDouble() * drawnWidth
            val offsetY = -random.nextDouble() * drawnHeight
            val (originX, originY) = FfmpegPreviewSelection.viewRect(
                stageWidth, stageHeight, drawnWidth, drawnHeight, offsetX, offsetY
            )
            val base = selection(0.3, 0.4, 0.7, 0.9)
            val rect = base.toView(drawnWidth, drawnHeight, originX.toDouble(), originY.toDouble())
            val fraction = FfmpegPreviewSelection.fractionFromView(
                rect[0], rect[1], drawnWidth, drawnHeight, originX.toDouble(), originY.toDouble()
            )
            assertEquals(base.left, fraction.first, 1e-3)
            assertEquals(base.top, fraction.second, 1e-3)
        }
    }

    @Test
    fun deslocamentoNuncaDeixaFundoAparecer() {
        // drawn <= stage: sempre centralizado (offset 0)
        assertEquals(0.0, FfmpegPreviewSelection.clampedOffset(400, 400, -50.0), 1e-9)
        // ampliado: o offset fica entre (stage - drawn) e 0
        assertEquals(-50.0, FfmpegPreviewSelection.clampedOffset(400, 500, -50.0), 1e-9)
        assertEquals(-100.0, FfmpegPreviewSelection.clampedOffset(400, 500, -900.0), 1e-9)
        assertEquals(0.0, FfmpegPreviewSelection.clampedOffset(400, 500, 900.0), 1e-9)
    }

    @Test
    fun zoomOutParaNoVideoInteiroEZoomInVaiAteCinco() {
        val (width, height, zoom) = FfmpegPreviewSelection.drawnSize(800, 450, 0.2)
        assertEquals(1.0, zoom, 1e-9)
        assertEquals(800, width)
        assertEquals(450, height)
        val (maxWidth, maxHeight, maxZoom) = FfmpegPreviewSelection.drawnSize(400, 240, 99.0)
        assertEquals(5.0, maxZoom, 1e-9)
        assertEquals(2000, maxWidth)
        assertEquals(1200, maxHeight)
    }

    @Test
    fun recorteEmPixelsEParEDentroDoQuadro() {
        val crop = FfmpegPreviewSelection.cropPixels(selection(0.25, 0.25, 0.75, 0.75), 1920, 1080)
        assertEquals(listOf(480, 270, 960, 540), crop!!.toList())
        assertEquals(0, crop[2] % 2)
        assertEquals(0, crop[3] % 2)
    }

    @Test
    fun recorteEmPixelsEForcadoParaDentroDoQuadro() {
        val random = Random(9)
        repeat(200) {
            val base = selection(
                random.nextDouble(-0.2, 0.9), random.nextDouble(-0.2, 0.9),
                random.nextDouble(0.0, 1.2), random.nextDouble(0.0, 1.2)
            )
            val crop = FfmpegPreviewSelection.cropPixels(base, 640, 360) ?: return@repeat
            val (x, y) = crop[0] to crop[1]
            assertTrue(x >= 0)
            assertTrue(y >= 0)
            assertTrue(crop[2] >= 2)
            assertTrue(crop[3] >= 2)
            assertTrue(x + crop[2] <= 640)
            assertTrue(y + crop[3] <= 360)
            assertEquals(0, crop[2] % 2)
            assertEquals(0, crop[3] % 2)
        }
    }

    @Test
    fun filtroDeRecorteDoFfmpeg() {
        assertEquals("crop=100:50:10:20", FfmpegPreviewSelection.cropFilter(intArrayOf(10, 20, 100, 50)))
    }

    @Test
    fun operacoesAtomicasSaoLidasNaOrdem() {
        assertEquals(
            listOf("transpose=1", "hflip"),
            FfmpegPreviewSelection.filterAtoms("transpose=1,hflip")
        )
        assertEquals(emptyList<String>(), FfmpegPreviewSelection.filterAtoms(""))
        assertEquals(emptyList<String>(), FfmpegPreviewSelection.filterAtoms("null"))
        assertEquals(
            listOf("transpose=2", "vflip", "hflip"),
            FfmpegPreviewSelection.filterAtoms("transpose=2,vflip,hflip")
        )
    }

    @Test
    fun mapeamentoDeCadaOperacao() {
        val base = selection(0.1, 0.2, 0.4, 0.6)
        val hflip = FfmpegPreviewSelection.afterFilterAtom(base, "hflip")
        assertEquals(listOf(0.6, 0.2, 0.9, 0.6), listOf(hflip.left, hflip.top, hflip.right, hflip.bottom))
        val vflip = FfmpegPreviewSelection.afterFilterAtom(base, "vflip")
        assertEquals(listOf(0.1, 0.4, 0.4, 0.8), listOf(vflip.left, vflip.top, vflip.right, vflip.bottom))
        val clockwise = FfmpegPreviewSelection.afterFilterAtom(base, "transpose=1")
        assertEquals(listOf(0.4, 0.1, 0.8, 0.4), listOf(clockwise.left, clockwise.top, clockwise.right, clockwise.bottom))
        val counterClockwise = FfmpegPreviewSelection.afterFilterAtom(base, "transpose=2")
        assertEquals(
            listOf(0.2, 0.6, 0.6, 0.9),
            listOf(counterClockwise.left, counterClockwise.top, counterClockwise.right, counterClockwise.bottom)
        )
    }

    @Test
    fun girarEVoltarRestauraASelecao() {
        val random = Random(13)
        repeat(200) {
            val base = selection(
                random.nextDouble(0.0, 0.5), random.nextDouble(0.0, 0.5),
                random.nextDouble(0.5, 1.0), random.nextDouble(0.5, 1.0)
            )
            listOf("transpose=1", "transpose=2", "hflip", "vflip", "transpose=1,hflip").forEach { filters ->
                val going = FfmpegPreviewSelection.betweenFilters(base, "", filters)
                val back = FfmpegPreviewSelection.betweenFilters(going, filters, "")
                assertEquals(base.left, back.left, 1e-6)
                assertEquals(base.top, back.top, 1e-6)
                assertEquals(base.right, back.right, 1e-6)
                assertEquals(base.bottom, back.bottom, 1e-6)
            }
        }
    }

    @Test
    fun recorteContinuaSobreOsMesmosPixelsDepoisDeGirar() {
        val selection = selection(0.25, 0.10, 0.75, 0.30)
        val before = FfmpegPreviewSelection.cropPixels(selection, 1920, 1080)
        assertEquals(listOf(480, 108, 960, 216), before!!.toList())

        val rotated = FfmpegPreviewSelection.betweenFilters(selection, "", "transpose=1")
        // a exibição gira: a seleção passa para a faixa que sobrou na vertical
        val after = FfmpegPreviewSelection.cropPixels(rotated, 1080, 1920)
        assertEquals(listOf(756, 480, 216, 960), after!!.toList())

        // voltando a fração pelo giro inverso, cai exatamente na seleção original
        val afterFractions = selection(
            after[0] / 1080.0, after[1] / 1920.0,
            (after[0] + after[2]) / 1080.0, (after[1] + after[3]) / 1920.0
        )
        val back = FfmpegPreviewSelection.afterFilterAtom(afterFractions, "transpose=2")
        assertEquals(selection.left, back.left, 1e-3)
        assertEquals(selection.top, back.top, 1e-3)
        assertEquals(selection.right, back.right, 1e-3)
        assertEquals(selection.bottom, back.bottom, 1e-3)
    }

    @Test
    fun meiaVoltaLevaASelecaoParaOCantoOposto() {
        val base = selection(0.1, 0.2, 0.3, 0.4)
        val halfTurn = FfmpegPreviewSelection.betweenFilters(base, "", "hflip,vflip")
        assertEquals(0.7, halfTurn.left, 1e-9)
        assertEquals(0.6, halfTurn.top, 1e-9)
        assertEquals(0.9, halfTurn.right, 1e-9)
        assertEquals(0.8, halfTurn.bottom, 1e-9)
    }

    @Test
    fun fracaoMinimaVemDoTamanhoEmPixels() {
        // mínimo de 8 dp sobre um quadro desenhado de 800 dp = 1% da largura
        assertEquals(0.01, FfmpegPreviewSelection.minimumFraction(8.0, 800), 1e-9)
        // quadro minúsculo: a fração mínima cresce junto (8/4 = 2.0, que o
        // arrasto depois limita a 1.0 no clamp das frações)
        assertEquals(2.0, FfmpegPreviewSelection.minimumFraction(8.0, 4), 1e-9)
    }
}
