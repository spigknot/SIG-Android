package br.gov.sp.pcsp.launcher

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * R109/T02 — ORÁCULO: a DFT direta que o produto usava antes desta rodada.
 *
 * Este arquivo é uma cópia fiel do `WhisperOrtFrontend` em `9e75bf6` (o mesmo
 * `logMel`, o mesmo `reflectPad`, as mesmas tabelas de seno/cosseno densas e a
 * mesma soma densa dos filtros mel). Ele existe SÓ no conjunto de testes: é
 * contra ele que a FFT agora no produto é medida.
 *
 * Critérios fixados pela ordem da R109 (os mesmos da R108, mais estritos):
 * - log-mel em float32: `max |delta| <= 1e-5` nos 210 clipes;
 * - entrada INT16 do encoder HTP, pela função `paraDtype` do PRODUTO:
 *   **zero** códigos diferentes.
 */
class WhisperOrtFrontendDftOraculo(
    private val filters: Array<FloatArray> = WhisperOrtMelFilters.slaney,
) {

    val nMels: Int = filters.size

    init {
        require(filters.isNotEmpty()) { "tabela de filtros mel vazia" }
        require(filters.all { it.size == N_FREQ_BINS }) {
            "filtros mel: esperado $N_FREQ_BINS colunas por linha"
        }
    }

    private val cosTable = DoubleArray(N_FREQ_BINS * N_FFT)
    private val sinTable = DoubleArray(N_FREQ_BINS * N_FFT)
    private val filterTable = DoubleArray(N_FREQ_BINS * nMels)
    private val hann = DoubleArray(N_FFT)
    private val windowBuffer = DoubleArray(N_FFT)
    private val melAccumulator = DoubleArray(nMels)

    init {
        for (k in 0 until N_FREQ_BINS) {
            for (n in 0 until N_FFT) {
                val angle = 2.0 * PI * k * n / N_FFT
                cosTable[k * N_FFT + n] = cos(angle)
                sinTable[k * N_FFT + n] = sin(angle)
            }
        }
        for (m in 0 until nMels) {
            for (k in 0 until N_FREQ_BINS) {
                filterTable[k * nMels + m] = filters[m][k].toDouble()
            }
        }
        for (n in 0 until N_FFT) {
            hann[n] = 0.5 - 0.5 * cos(2.0 * PI * n / N_FFT)
        }
    }

    fun logMel(pcm: FloatArray): FloatArray {
        val audio = DoubleArray(N_SAMPLES)
        val copied = min(pcm.size, N_SAMPLES)
        for (i in 0 until copied) audio[i] = pcm[i].toDouble()
        val padded = reflectPad(audio, N_FFT / 2)
        val logSpec = DoubleArray(nMels * N_FRAMES)
        var globalMax = -Double.MAX_VALUE
        for (frame in 0 until N_FRAMES) {
            val start = frame * HOP_LENGTH
            for (n in 0 until N_FFT) windowBuffer[n] = padded[start + n] * hann[n]
            java.util.Arrays.fill(melAccumulator, 0.0)
            for (k in 0 until N_FREQ_BINS) {
                var re = 0.0
                var im = 0.0
                val base = k * N_FFT
                for (n in 0 until N_FFT) {
                    val sample = windowBuffer[n]
                    re += sample * cosTable[base + n]
                    im -= sample * sinTable[base + n]
                }
                val power = re * re + im * im
                if (power != 0.0) {
                    val filterBase = k * nMels
                    for (m in 0 until nMels) {
                        melAccumulator[m] += filterTable[filterBase + m] * power
                    }
                }
            }
            for (m in 0 until nMels) {
                val value = log10(max(melAccumulator[m], 1e-10))
                logSpec[m * N_FRAMES + frame] = value
                if (value > globalMax) globalMax = value
            }
        }
        val floor = globalMax - 8.0
        return FloatArray(nMels * N_FRAMES) { index ->
            val value = max(logSpec[index], floor)
            ((value + 4.0) / 4.0).toFloat()
        }
    }

    private fun reflectPad(source: DoubleArray, pad: Int): DoubleArray {
        val out = DoubleArray(source.size + 2 * pad)
        for (i in 0 until pad) out[i] = source[pad - i]
        System.arraycopy(source, 0, out, pad, source.size)
        for (j in 0 until pad) out[pad + source.size + j] = source[source.size - 2 - j]
        return out
    }

    companion object {
        const val N_SAMPLES = WhisperOrtFrontend.N_SAMPLES
        const val HOP_LENGTH = WhisperOrtFrontend.HOP_LENGTH
        const val N_FFT = WhisperOrtFrontend.N_FFT
        const val N_FRAMES = WhisperOrtFrontend.N_FRAMES
        const val N_FREQ_BINS = WhisperOrtFrontend.N_FREQ_BINS
    }
}
