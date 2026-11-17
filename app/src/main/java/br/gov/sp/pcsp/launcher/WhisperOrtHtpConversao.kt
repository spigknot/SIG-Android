package br.gov.sp.pcsp.launcher

/**
 * R100 §2.3 — conversão de dtype do encoder W8A16 do PRODUTO.
 *
 * O wrapper EPContext é `int16_le` nos dois lados: a entrada log-mel entra
 * quantizada com a escala/offset medidos na exportação, a saída (hidden) volta
 * pelos mesmos parâmetros. As constantes são as da R97 (rota `htp-w8a16`), que
 * foram as que produziram a M3 aprovada; mudá-las muda o resultado numérico.
 *
 * `f16` fica porque o wrapper de diagnóstico da R97 é F16 e a conferência de
 * hidratação reusa a conversão — não há dependência de `android.util.Half` aqui,
 * então o teste JVM cobre arredondamento e subnormais.
 */
object WhisperOrtHtpConversao {

    const val DTYPE_I16 = "int16_le"

    const val ESCALA_ENTRADA_I16 = 4.746108970721252e-05
    const val OFFSET_ENTRADA_I16 = 31605
    const val DESLOCAMENTO_SAIDA_I16 = 31745
    const val ESCALA_SAIDA_I16 = 3.499292361084372e-04

    /** float32 -> half (16 bits), RNE, portátil. */
    fun f16(x: Float): Short {
        val bits = java.lang.Float.floatToIntBits(x)
        val sinal = (bits ushr 16) and 0x8000
        val expoente = (bits ushr 23) and 0xFF
        val mantissa = bits and 0x7FFFFF
        if (expoente == 0xFF) {
            return (sinal or 0x7C00 or (if (mantissa != 0) 0x200 else 0)).toShort()
        }
        var exp = expoente - 127 + 15
        if (exp >= 0x1F) return (sinal or 0x7C00).toShort()
        if (exp <= 0) {
            if (exp < -10) return sinal.toShort()
            val m = mantissa or 0x800000
            val deslocamento = 14 - exp
            var h = m ushr deslocamento
            val resto = m and ((1 shl deslocamento) - 1)
            val meio = 1 shl (deslocamento - 1)
            if (resto > meio || (resto == meio && (h and 1) == 1)) h += 1
            return (sinal or h).toShort()
        }
        var h = (exp shl 10) or (mantissa ushr 13)
        val resto = mantissa and 0x1FFF
        if (resto > 0x1000 || (resto == 0x1000 && (h and 1) == 1)) h += 1
        return (sinal or h).toShort()
    }

    fun deF16(bits: Short): Float {
        val v = bits.toInt() and 0xFFFF
        val sinal = if ((v and 0x8000) != 0) -1.0f else 1.0f
        val exp = (v ushr 10) and 0x1F
        val mant = v and 0x3FF
        return when (exp) {
            0 -> if (mant == 0) sinal * 0.0f else sinal * (mant * 5.960464477539063e-08f)
            0x1F -> if (mant == 0) sinal * Float.POSITIVE_INFINITY else Float.NaN
            else -> sinal * ((1 + mant / 1024.0f) * Math.pow(2.0, (exp - 15).toDouble()).toFloat())
        }
    }

    fun quantizarI16(x: Float): Short {
        val q = Math.rint(x / ESCALA_ENTRADA_I16) + OFFSET_ENTRADA_I16
        return q.coerceIn(0.0, 65535.0).toInt().toShort()
    }

    fun dequantizarI16(bits: Short): Float =
        (((bits.toInt() and 0xFFFF) - DESLOCAMENTO_SAIDA_I16) * ESCALA_SAIDA_I16).toFloat()

    /** log-mel float -> bytes little-endian do dtype do wrapper. */
    fun paraDtype(mel: FloatArray, dtype: String): ByteArray {
        val out = ByteArray(mel.size * 2)
        for (i in mel.indices) {
            val v = when (dtype) {
                DTYPE_I16 -> quantizarI16(mel[i]).toInt() and 0xFFFF
                DTYPE_F16 -> f16(mel[i]).toInt() and 0xFFFF
                else -> throw IllegalArgumentException("dtype sem conversão: $dtype")
            }
            out[2 * i] = (v and 0xFF).toByte()
            out[2 * i + 1] = ((v ushr 8) and 0xFF).toByte()
        }
        return out
    }

    /** bytes little-endian do dtype -> hidden float. */
    fun paraFloat(raw: ByteArray, dtype: String): FloatArray {
        require(raw.size % 2 == 0) { "hidden com número ímpar de bytes: ${raw.size}" }
        val out = FloatArray(raw.size / 2)
        for (i in out.indices) {
            val lo = raw[2 * i].toInt() and 0xFF
            val hi = raw[2 * i + 1].toInt() and 0xFF
            val v = (lo or (hi shl 8)).toShort()
            out[i] = when (dtype) {
                DTYPE_I16 -> dequantizarI16(v)
                DTYPE_F16 -> deF16(v)
                else -> throw IllegalArgumentException("dtype sem conversão: $dtype")
            }
        }
        return out
    }

    private const val DTYPE_F16 = "float16_le"
}
