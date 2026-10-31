package br.gov.sp.pcsp.launcher

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Banco de filtros mel do frontend Whisper (ORT1-C03/C04).
 *
 * Contrato replicado de `librosa.filters.mel(sr=16000, n_fft=400, n_mels=128,
 * fmin=0, fmax=8000, htk=False, norm="slaney")` — a mesma matriz publicada em
 * `openai/whisper` como `mel_filters.npz::mel_128` (128 x 201). O teste
 * `WhisperOrtMelFiltersTest` compara cada coeficiente com esse arquivo oficial;
 * nenhum valor é copiado para o código, a matriz é CALCULADA e conferida contra
 * o golden binário independente.
 *
 * Escala Slaney (não HTK): linear abaixo de 1 kHz com passo `200/3` Hz e
 * logarítmica acima, com `logstep = ln(6.4)/27`. Normalização "slaney":
 * `enorm = 2 / (f[m+2] - f[m])`, que dá área unitária a cada filtro.
 *
 * Seam puro: sem Android, sem I/O, sem estado mutável depois do `lazy`.
 */
object WhisperOrtMelFilters {

    /** Taxa de amostragem do frontend Whisper, fixa em 16 kHz. */
    const val SAMPLE_RATE = 16_000

    /** Tamanho da FFT do frontend (400 amostras = 25 ms). */
    const val N_FFT = 400

    /** Número de bins mel do `whisper-large-v3-turbo` (128, não 80). */
    const val N_MELS = 128

    /** Bins não redundantes de uma FFT real de 400 pontos (0..200). */
    const val N_FREQ_BINS = N_FFT / 2 + 1

    /** Frequência mínima do banco de filtros (Hz). */
    const val FMIN_HZ = 0.0

    /** Frequência máxima do banco de filtros: Nyquist de 16 kHz (Hz). */
    const val FMAX_HZ = 8_000.0

    private const val F_SP = 200.0 / 3.0
    private const val MIN_LOG_HZ = 1_000.0
    private val MIN_LOG_MEL = MIN_LOG_HZ / F_SP
    private val LOG_STEP = ln(6.4) / 27.0

    /** Converte Hz para mel na escala Slaney (a mesma de `librosa.hz_to_mel`). */
    fun hzToMel(hz: Double): Double =
        if (hz >= MIN_LOG_HZ) MIN_LOG_MEL + ln(hz / MIN_LOG_HZ) / LOG_STEP else hz / F_SP

    /** Converte mel para Hz na escala Slaney (inversa exata de [hzToMel]). */
    fun melToHz(mel: Double): Double =
        if (mel >= MIN_LOG_MEL) MIN_LOG_HZ * exp(LOG_STEP * (mel - MIN_LOG_MEL)) else F_SP * mel

    /**
     * Matriz `[N_MELS][N_FREQ_BINS]` com os pesos triangulares normalizados.
     *
     * Calculada uma única vez por processo (`lazy`), imutável a partir daí: é
     * compartilhada entre jobs sem cópia defensiva.
     */
    val slaney: Array<FloatArray> by lazy { buildSlaney() }

    private fun buildSlaney(): Array<FloatArray> {
        val melMin = hzToMel(FMIN_HZ)
        val melMax = hzToMel(FMAX_HZ)
        val edges = DoubleArray(N_MELS + 2) { index ->
            melToHz(melMin + (melMax - melMin) * index / (N_MELS + 1))
        }
        val step = DoubleArray(N_MELS + 1) { edges[it + 1] - edges[it] }
        val binHz = DoubleArray(N_FREQ_BINS) { it * (SAMPLE_RATE / 2.0) / (N_FREQ_BINS - 1) }
        return Array(N_MELS) { band ->
            val enorm = 2.0 / (edges[band + 2] - edges[band])
            FloatArray(N_FREQ_BINS) { bin ->
                val lower = (binHz[bin] - edges[band]) / step[band]
                val upper = (edges[band + 2] - binHz[bin]) / step[band + 1]
                (max(0.0, min(lower, upper)) * enorm).toFloat()
            }
        }
    }
}
