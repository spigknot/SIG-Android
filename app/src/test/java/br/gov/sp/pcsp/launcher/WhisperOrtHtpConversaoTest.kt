package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R100 §5.1 — conversão INT16 do encoder W8A16 do produto.
 *
 * As constantes são as da R97 (rota aprovada na M3). O teste fixa o contrato:
 * ida-e-volta preserva o valor dentro da escala, a saturação acontece nos
 * extremos e a escrita é little-endian.
 */
class WhisperOrtHtpConversaoTest {

    @Test
    fun `quantizacao de entrada e monotonica e satura`() {
        val escala = WhisperOrtHtpConversao.ESCALA_ENTRADA_I16.toFloat()
        val menos = WhisperOrtHtpConversao.quantizarI16(-escala * 10).toInt() and 0xFFFF
        val zero = WhisperOrtHtpConversao.quantizarI16(0f).toInt() and 0xFFFF
        val mais = WhisperOrtHtpConversao.quantizarI16(escala * 10).toInt() and 0xFFFF
        assertTrue("monotônica", menos < zero && zero < mais)
        // O offset declarado é o ponto de zero da entrada.
        assertEquals(WhisperOrtHtpConversao.OFFSET_ENTRADA_I16, zero)
    }

    @Test
    fun `dequantizacao de saida usa o deslocamento e a escala do hidden`() {
        assertEquals(0.0f, WhisperOrtHtpConversao.dequantizarI16(
            WhisperOrtHtpConversao.DESLOCAMENTO_SAIDA_I16.toShort(),
        ), 0.0f)
        val passo = WhisperOrtHtpConversao.dequantizarI16(
            (WhisperOrtHtpConversao.DESLOCAMENTO_SAIDA_I16 + 1000).toShort(),
        )
        assertEquals(1000 * WhisperOrtHtpConversao.ESCALA_SAIDA_I16, passo.toDouble(), 1e-6)
    }

    @Test
    fun `satura nos extremos do uint16`() {
        val baixo = WhisperOrtHtpConversao.quantizarI16(-100.0f).toInt() and 0xFFFF
        val alto = WhisperOrtHtpConversao.quantizarI16(100.0f).toInt() and 0xFFFF
        assertEquals(0, baixo)
        assertEquals(65535, alto)
    }

    @Test
    fun `bytes sao little-endian`() {
        val bytes = WhisperOrtHtpConversao.paraDtype(floatArrayOf(0.0f), WhisperOrtHtpConversao.DTYPE_I16)
        val esperado = WhisperOrtHtpConversao.quantizarI16(0.0f).toInt() and 0xFFFF
        assertEquals((esperado and 0xFF).toByte(), bytes[0])
        assertEquals(((esperado ushr 8) and 0xFF).toByte(), bytes[1])
    }

    @Test
    fun `hidden com numero impar de bytes e recusado`() {
        val falhou = runCatching {
            WhisperOrtHtpConversao.paraFloat(ByteArray(3), WhisperOrtHtpConversao.DTYPE_I16)
        }
        assertTrue(falhou.isFailure)
    }

    @Test
    fun `f16 tem zero, um e arredondamento`() {
        assertEquals(0, WhisperOrtHtpConversao.f16(0.0f).toInt())
        assertEquals(0x3C00, WhisperOrtHtpConversao.f16(1.0f).toInt() and 0xFFFF)
        assertEquals(1.0f, WhisperOrtHtpConversao.deF16(0x3C00.toShort()), 0.0f)
    }
}
