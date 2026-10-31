package br.gov.sp.pcsp.launcher

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Frontend de áudio do Whisper para o motor ORT (ORT1-C01/C03/C04/C06/C07).
 *
 * Converte PCM já em 16 kHz mono (float, faixa -1..1) na matriz log-mel
 * `[128][3000]` que o encoder ONNX consome, exatamente como o
 * `WhisperFeatureExtractor`:
 *
 * 1. pad/truncate para 30 s = 480000 amostras com valor 0.0 (padding `right`);
 * 2. STFT `n_fft=400`, `hop=160`, janela Hann **periódica**, `center=True`
 *    (padding `reflect` de 200 amostras em cada ponta);
 * 3. potência `|X|^2`, descartando a ÚLTIMA coluna (3001 -> 3000);
 * 4. `mel = filtros @ potência` ([WhisperOrtMelFilters.slaney]);
 * 5. `log10(clamp(mel, 1e-10))`, depois `max(log, max_global - 8.0)` e
 *    `(log + 4.0) / 4.0`.
 *
 * A aritmética interna é `Double` (como a referência numpy/torch em float64) e
 * só o resultado é convertido para `Float`, para que a divergência contra o
 * golden meça o contrato e não o arredondamento de acumulação.
 *
 * **Custo declarado:** a DFT é direta (201 bins x 400 amostras por frame), não
 * uma FFT. É a escolha de correção-primeiro desta rodada: exata por construção e
 * independente de radix. O tempo é medido em H01; trocar por FFT/Bluestein só
 * com causa e contraprova (H03/H06), nunca antes da primeira transcrição real.
 *
 * Seam puro: sem Android, sem I/O, sem alocação estática grande (as tabelas de
 * seno/cosseno são derivadas no construtor, ~1,3 MB, e reutilizadas).
 */
class WhisperOrtFrontend(
    private val filters: Array<FloatArray> = WhisperOrtMelFilters.slaney,
) {

    init {
        require(filters.size == N_MELS) { "filtros mel: esperado $N_MELS linhas, veio ${filters.size}" }
        require(filters.all { it.size == N_FREQ_BINS }) {
            "filtros mel: esperado $N_FREQ_BINS colunas por linha"
        }
    }

    private val cosTable = DoubleArray(N_FREQ_BINS * N_FFT)
    private val sinTable = DoubleArray(N_FREQ_BINS * N_FFT)
    private val filterTable = DoubleArray(N_FREQ_BINS * N_MELS)
    private val hann = DoubleArray(N_FFT)
    private val windowBuffer = DoubleArray(N_FFT)
    private val melAccumulator = DoubleArray(N_MELS)

    init {
        for (k in 0 until N_FREQ_BINS) {
            for (n in 0 until N_FFT) {
                val angle = 2.0 * PI * k * n / N_FFT
                cosTable[k * N_FFT + n] = cos(angle)
                sinTable[k * N_FFT + n] = sin(angle)
            }
        }
        // Transposta para que o laço interno do mel seja contíguo em memória.
        for (m in 0 until N_MELS) {
            for (k in 0 until N_FREQ_BINS) {
                filterTable[k * N_MELS + m] = filters[m][k].toDouble()
            }
        }
        // Hann PERIÓDICA: 0.5 - 0.5*cos(2*pi*n/N). A simétrica (N-1) é outra
        // janela e deslocaria todos os valores — o golden pega a diferença.
        for (n in 0 until N_FFT) {
            hann[n] = 0.5 - 0.5 * cos(2.0 * PI * n / N_FFT)
        }
    }

    /**
     * Log-mel de um bloco de até 30 s.
     *
     * [pcm] é truncado em [N_SAMPLES] amostras e completado com zeros até lá: a
     * cauda curta NÃO é descartada (C06) e uma entrada maior é fatiada pelo
     * chamador em janelas de [planWindows]. Devolve `[N_MELS][N_FRAMES]`
     * row-major (índice `mel * N_FRAMES + frame`) — o mesmo layout do tensor
     * `[1, 128, 3000]` do encoder.
     */
    fun logMel(pcm: FloatArray): FloatArray {
        val audio = DoubleArray(N_SAMPLES)
        val copied = min(pcm.size, N_SAMPLES)
        for (i in 0 until copied) audio[i] = pcm[i].toDouble()
        val padded = reflectPad(audio, N_FFT / 2)
        val logSpec = DoubleArray(N_MELS * N_FRAMES)
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
                    val filterBase = k * N_MELS
                    for (m in 0 until N_MELS) {
                        melAccumulator[m] += filterTable[filterBase + m] * power
                    }
                }
            }
            for (m in 0 until N_MELS) {
                val value = log10(max(melAccumulator[m], 1e-10))
                logSpec[m * N_FRAMES + frame] = value
                if (value > globalMax) globalMax = value
            }
        }
        val floor = globalMax - 8.0
        return FloatArray(N_MELS * N_FRAMES) { index ->
            val value = max(logSpec[index], floor)
            ((value + 4.0) / 4.0).toFloat()
        }
    }

    /**
     * Planeja as janelas de 30 s de uma entrada longa (C07/D07).
     *
     * [hopSamples] é o passo entre inícios de janela. Com o default (passo =
     * janela) as janelas são justapostas — foi o modo que PERDIA a palavra
     * cortada na fronteira (medido no fixture de 64,4 s). Com passo menor há
     * sobreposição e o texto repetido é removido por [WhisperOrtTextMerge].
     *
     * Garantias: nenhuma amostra é descartada, a última janela é PARCIAL
     * (completada com zeros) e o laço sempre termina — [hopSamples] é validado
     * e há teto de janelas para uma entrada absurda.
     */
    fun planWindows(
        totalSamples: Int,
        hopSamples: Int = N_SAMPLES,
    ): List<WhisperOrtWindow> {
        require(hopSamples in 1..N_SAMPLES) { "hop fora de 1..$N_SAMPLES: $hopSamples" }
        if (totalSamples <= 0) return emptyList()
        val windows = ArrayList<WhisperOrtWindow>(totalSamples / hopSamples + 1)
        var offset = 0
        while (true) {
            val usable = min(N_SAMPLES, totalSamples - offset)
            windows += WhisperOrtWindow(offsetSamples = offset, usableSamples = usable, paddedSamples = N_SAMPLES)
            if (offset + usable >= totalSamples) break
            if (windows.size >= MAX_WINDOWS) break
            offset += hopSamples
        }
        return windows
    }

    /** Extrai a fatia `[offset, offset+usable)` já pronta para [logMel]. */
    fun slice(pcm: FloatArray, window: WhisperOrtWindow): FloatArray {
        val slice = FloatArray(N_SAMPLES)
        val available = min(window.usableSamples, max(0, pcm.size - window.offsetSamples))
        for (i in 0 until available) slice[i] = pcm[window.offsetSamples + i]
        return slice
    }

    private fun reflectPad(source: DoubleArray, pad: Int): DoubleArray {
        val out = DoubleArray(source.size + 2 * pad)
        for (i in 0 until pad) out[i] = source[pad - i]
        System.arraycopy(source, 0, out, pad, source.size)
        for (j in 0 until pad) out[pad + source.size + j] = source[source.size - 2 - j]
        return out
    }

    companion object {
        /** Taxa de amostragem exigida pelo frontend Whisper. */
        const val SAMPLE_RATE = WhisperOrtMelFilters.SAMPLE_RATE

        /** Amostras de uma janela cheia de 30 s. */
        const val N_SAMPLES = 480_000

        /** Passo da STFT (10 ms a 16 kHz). */
        const val HOP_LENGTH = 160

        /** Tamanho da FFT do frontend (400 amostras = 25 ms). */
        const val N_FFT = WhisperOrtMelFilters.N_FFT

        /** Frames log-mel por janela (3000, não 3001). */
        const val N_FRAMES = 3_000

        /** Bins mel por frame (128). */
        const val N_MELS = WhisperOrtMelFilters.N_MELS

        /** Bins de frequência por frame (201). */
        const val N_FREQ_BINS = WhisperOrtMelFilters.N_FREQ_BINS

        /** Teto de janelas por entrada (2 h de áudio); evita laço patológico. */
        const val MAX_WINDOWS = 240
    }
}

/**
 * Janela de 30 s do áudio de entrada (C06/C07).
 *
 * [usableSamples] é o que existe de verdade no arquivo a partir de
 * [offsetSamples]; [paddedSamples] é quanto o frontend entrega ao encoder depois
 * do padding com zeros. A diferença é a cauda parcial — registrada para que o
 * relatório não confunda silêncio de padding com silêncio do áudio.
 */
data class WhisperOrtWindow(
    val offsetSamples: Int,
    val usableSamples: Int,
    val paddedSamples: Int,
) {
    /** Duração real da janela em segundos (a parte que veio do arquivo). */
    val usableSeconds: Double get() = usableSamples.toDouble() / WhisperOrtFrontend.SAMPLE_RATE

    /** `true` quando a janela não enche os 30 s e recebeu padding. */
    val partial: Boolean get() = usableSamples < paddedSamples
}
