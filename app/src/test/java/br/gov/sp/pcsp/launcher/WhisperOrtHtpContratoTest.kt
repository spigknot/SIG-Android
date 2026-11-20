package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R115 §T03 — contrato numérico da rota HTP de ponta a ponta.
 *
 * Prova, sem Android e sem ORT, que os números usados pelo produto são os
 * pinados nas rodadas que os mediram (R111/T01, R112/T02, R113, R114) e que a
 * regra da volta para a CPU é a do braço Q′ qualificado.
 */
class WhisperOrtHtpContratoTest {

    @Test
    fun `hidden do produto usa a escala e o offset do encoder`() {
        assertEquals(3.499292361084372e-04, WhisperOrtHtpContrato.ESCALA_OCULTO, 0.0)
        assertEquals(31745, WhisperOrtHtpContrato.OFFSET_OCULTO)
        assertEquals(WhisperOrtHtpConversao.ESCALA_SAIDA_I16, WhisperOrtHtpContrato.ESCALA_OCULTO, 0.0)
    }

    @Test
    fun `logits usam a escala do contrato R111`() {
        assertEquals(0.0007861469688191425, WhisperOrtHtpContrato.ESCALA_LOGIT, 0.0)
        assertEquals(23109, WhisperOrtHtpContrato.OFFSET_LOGIT)
        // Código do offset vale exatamente 0,0 — o "zero" do contrato.
        assertEquals(0.0f, WhisperOrtHtpContrato.codigoParaLogit(23109), 0.0f)
        assertEquals(
            WhisperOrtHtpContrato.ESCALA_LOGIT.toFloat(),
            WhisperOrtHtpContrato.codigoParaLogit(23110),
            1e-12f,
        )
    }

    @Test
    fun `mascara tem 200 posicoes alinhadas a direita`() {
        assertEquals(200, WhisperOrtHtpContrato.TAMANHO_MASCARA)
        assertEquals(65535, WhisperOrtHtpContrato.MASCARA_VALIDO)
        val mascara = WhisperOrtHtpContrato.novaMascara()
        assertEquals(400, mascara.size)
        WhisperOrtHtpContrato.marcarMascara(mascara, 0)
        // Posição 0 marca a ÚLTIMA coluna (índice 199).
        assertEquals(0xFF, mascara[199 * 2].toInt() and 0xFF)
        assertEquals(0xFF, mascara[199 * 2 + 1].toInt() and 0xFF)
        WhisperOrtHtpContrato.marcarMascara(mascara, 199)
        assertEquals(0xFF, mascara[0].toInt() and 0xFF)
        assertEquals(0xFF, mascara[1].toInt() and 0xFF)
        // As demais continuam em -100,0 (zero).
        assertEquals(0, mascara[100 * 2].toInt())
    }

    @Test
    fun `hidden para bytes e de volta preserva o codigo`() {
        val hidden = floatArrayOf(0.0f, 0.1f, -0.1f, 3.0f)
        val bytes = WhisperOrtHtpContrato.ocultoParaBytes(hidden)
        assertEquals(hidden.size * 2, bytes.size)
        for (i in hidden.indices) {
            val codigo = WhisperOrtHtpContrato.codigoEm(bytes, i)
            assertEquals(
                "código $i",
                WhisperOrtHtpContrato.codigoOculto(hidden[i]),
                codigo,
            )
        }
    }

    @Test
    fun `logits de bytes voltam ao float do contrato`() {
        val codigos = intArrayOf(23109, 23109 + 1000, 0, 65535)
        val bytes = ByteArray(codigos.size * 2)
        for (i in codigos.indices) {
            bytes[2 * i] = (codigos[i] and 0xFF).toByte()
            bytes[2 * i + 1] = ((codigos[i] ushr 8) and 0xFF).toByte()
        }
        val logits = WhisperOrtHtpContrato.logitsParaFloat(bytes)
        assertEquals(4, logits.size)
        assertEquals(0.0f, logits[0], 0.0f)
        assertEquals(1000 * WhisperOrtHtpContrato.ESCALA_LOGIT.toFloat(), logits[1], 1e-5f)
    }

    @Test
    fun `a janela cabe ate a posicao 198`() {
        assertTrue(WhisperOrtHtpContrato.cabeNaJanela(0))
        assertTrue(WhisperOrtHtpContrato.cabeNaJanela(198))
        // Posição 199 exigiria o 200º slot em um cache de 199: volta para a CPU.
        assertFalse(WhisperOrtHtpContrato.cabeNaJanela(199))
        assertFalse(WhisperOrtHtpContrato.cabeNaJanela(200))
        assertEquals(199, WhisperOrtHtpContrato.POSICOES_CACHE_SELF)
    }

    @Test
    fun `divergencias contam bytes diferentes`() {
        assertEquals(0, WhisperOrtHtpContrato.divergencias(byteArrayOf(1, 2), byteArrayOf(1, 2)))
        assertEquals(1, WhisperOrtHtpContrato.divergencias(byteArrayOf(1, 2), byteArrayOf(1, 3)))
        assertEquals(3, WhisperOrtHtpContrato.divergencias(byteArrayOf(1), byteArrayOf(1, 2, 3)))
    }
}
