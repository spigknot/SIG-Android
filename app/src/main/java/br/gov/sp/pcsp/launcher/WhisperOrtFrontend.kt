package br.gov.sp.pcsp.launcher

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Frontend de áudio do Whisper para o motor ORT (ORT1-C01/C03/C04/C06/C07;
 * FFT de 400 pontos na R109).
 *
 * Converte PCM já em 16 kHz mono (float, faixa -1..1) na matriz log-mel
 * `[nMels][3000]` que o encoder ONNX consome, exatamente como o
 * `WhisperFeatureExtractor`: 128 bins no turbo (large-v3), 80 no small
 * (extrator padrão) — a contagem vem da tabela de filtros recebida
 * (adendo 17 §1).
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
 * **Transformada.** A R109 troca a DFT direta (201 bins x 400 amostras por
 * frame) pela FFT EXATA de 400 pontos da candidata da R108: `400 = 2^4 * 5^2`,
 * Cooley-Tukey radix misto 4-4-5-5, iterativo (planos de nós pré-computados, sem
 * recursão), em `double`, **sem** zero-padding para 512 (mudaria os bins). Só as
 * [N_FREQ_BINS] saídas não redundantes são materializadas. A equivalência com a
 * DFT está fixada por teste próprio nos 210 clipes do corpus M1-C/R97: max
 * |delta| do log-mel <= 1e-5 e **zero** códigos INT16 diferentes na entrada do
 * encoder HTP, pela conversão do produto ([WhisperOrtHtpConversao.paraDtype]).
 * A DFT antiga ficou em `app/src/test` como oráculo.
 *
 * A soma dos filtros mel é esparsa (CSR dos coeficientes NÃO nulos por bin): o
 * termo `0.0 * power` é identidade exata, então o resultado é bit-idêntico ao da
 * soma densa e o custo cai junto com a FFT.
 *
 * Seam puro: sem Android, sem I/O, sem alocação estática grande — as tabelas de
 * twiddle são derivadas no construtor (399 pares, alguns KB) e reutilizadas.
 */
class WhisperOrtFrontend(
    private val filters: Array<FloatArray> = WhisperOrtMelFilters.slaney,
) {

    /**
     * Bins mel DESTA instância (adendo 17 §1): 128 no turbo (large-v3),
     * 80 no small (extrator padrão). Vem da tabela recebida, nunca de
     * constante global — é o que impede o `ConvInteger` receber 128
     * canais quando o encoder espera 80.
     */
    val nMels: Int = filters.size

    init {
        require(filters.isNotEmpty()) { "tabela de filtros mel vazia" }
        require(filters.all { it.size == N_FREQ_BINS }) {
            "filtros mel: esperado $N_FREQ_BINS colunas por linha"
        }
    }

    /** `m = n / r` de cada nível da decomposição (100, 25, 5, 1). */
    private val nivelM = IntArray(4)

    /** Twiddles radix-4: níveis 0 (m=100) e 1 (m=25). */
    private val tw4Re = arrayOfNulls<DoubleArray>(2)
    private val tw4Im = arrayOfNulls<DoubleArray>(2)

    /** Twiddles radix-5: níveis 2 (m=5) e 3 (m=1). */
    private val tw5Re = arrayOfNulls<DoubleArray>(2)
    private val tw5Im = arrayOfNulls<DoubleArray>(2)

    private val hann = DoubleArray(N_FFT)
    private val frameRe = DoubleArray(N_FFT)
    private val frameIm = DoubleArray(N_FFT)

    /** Scratch achatado por profundidade: o nível `d` mora em `d * N_FFT`. */
    private val scratchRe = DoubleArray(6 * N_FFT)
    private val scratchIm = DoubleArray(6 * N_FFT)

    private val melAccumulator = DoubleArray(nMels)

    /** CSR dos coeficientes NÃO nulos: os de [k] moram em `[melStart[k], melStart[k+1])`. */
    private val melStart = IntArray(N_FREQ_BINS + 1)
    private val melIndex: IntArray
    private val melCoef: DoubleArray

    /** `outBase` de cada nó por nível (planos fixos da decomposição). */
    private val out1 = IntArray(4)
    private val out2 = IntArray(16)
    private val out3 = IntArray(80)

    init {
        // Hann PERIÓDICA: 0.5 - 0.5*cos(2*pi*n/N). A simétrica (N-1) é outra
        // janela e deslocaria todos os valores — o golden pega a diferença.
        for (n in 0 until N_FFT) {
            hann[n] = 0.5 - 0.5 * cos(2.0 * PI * n / N_FFT)
        }

        // Plano: 400 -> 4 x 100 -> 4 x 25 -> 5 x 5 -> 5 x 1.
        var n = N_FFT
        for (d in 0 until 4) {
            val r = if (d < 2) 4 else 5
            require(n % r == 0) { "decomposição inválida em n=$n r=$r" }
            val m = n / r
            nivelM[d] = m
            val passo = N_FFT / n
            if (r == 4) {
                val idx = if (d == 0) 0 else 1
                val re = DoubleArray(3 * m)
                val im = DoubleArray(3 * m)
                for (k in 0 until m) {
                    for (j in 0 until 3) {
                        val t = ((j + 1) * k * passo) % N_FFT
                        val ang = 2.0 * PI * t / N_FFT
                        re[j * m + k] = cos(ang)
                        im[j * m + k] = -sin(ang)
                    }
                }
                tw4Re[idx] = re
                tw4Im[idx] = im
            } else {
                val idx = if (d == 2) 0 else 1
                val re = DoubleArray(4 * m)
                val im = DoubleArray(4 * m)
                for (k in 0 until m) {
                    for (j in 0 until 4) {
                        val t = ((j + 1) * k * passo) % N_FFT
                        val ang = 2.0 * PI * t / N_FFT
                        re[j * m + k] = cos(ang)
                        im[j * m + k] = -sin(ang)
                    }
                }
                tw5Re[idx] = re
                tw5Im[idx] = im
            }
            n = m
        }
        require(n == 1) { "decomposição não terminou em 1 (parou em $n)" }

        // Planos de nós: no nível d o nó lê `src[no + i*passo]` e escreve em `outBase`.
        for (p in 0 until 4) out1[p] = p * nivelM[0]
        for (p in 0 until 4) {
            for (j in 0 until 4) out2[p + 4 * j] = out1[p] + j * nivelM[1]
        }
        for (q in 0 until 16) {
            for (j in 0 until 5) out3[q + 16 * j] = out2[q] + j * nivelM[2]
        }

        // CSR: só o que o filtro realmente cobre.
        var total = 0
        for (k in 0 until N_FREQ_BINS) {
            for (m in 0 until nMels) if (filters[m][k] != 0f) total++
        }
        melIndex = IntArray(total)
        melCoef = DoubleArray(total)
        var cursor = 0
        for (k in 0 until N_FREQ_BINS) {
            melStart[k] = cursor
            for (m in 0 until nMels) {
                val coef = filters[m][k]
                if (coef != 0f) {
                    melIndex[cursor] = m
                    melCoef[cursor] = coef.toDouble()
                    cursor++
                }
            }
        }
        melStart[N_FREQ_BINS] = cursor
        require(cursor == total)
    }

    /**
     * Log-mel de um bloco de até 30 s.
     *
     * [pcm] é truncado em [N_SAMPLES] amostras e completado com zeros até lá: a
     * cauda curta NÃO é descartada (C06) e uma entrada maior é fatiada pelo
     * chamador em janelas de [planWindows]. Devolve `[nMels][N_FRAMES]`
     * row-major (índice `mel * N_FRAMES + frame`) — o mesmo layout do tensor
     * `[1, nMels, 3000]` do encoder.
     */
    fun logMel(pcm: FloatArray): FloatArray {
        val audio = DoubleArray(N_SAMPLES)
        val copied = min(pcm.size, N_SAMPLES)
        for (i in 0 until copied) audio[i] = pcm[i].toDouble()
        val padded = reflectPad(audio, N_FFT / 2)
        val logSpec = DoubleArray(nMels * N_FRAMES)
        var globalMax = -Double.MAX_VALUE
        for (frame in 0 until N_FRAMES) {
            val start = frame * HOP_LENGTH
            for (n in 0 until N_FFT) {
                frameRe[n] = padded[start + n] * hann[n]
                frameIm[n] = 0.0
            }
            fft(frameRe, frameIm)
            java.util.Arrays.fill(melAccumulator, 0.0)
            for (k in 0 until N_FREQ_BINS) {
                val r = scratchRe[k]
                val i = scratchIm[k]
                val power = r * r + i * i
                if (power != 0.0) {
                    val ate = melStart[k + 1]
                    for (t in melStart[k] until ate) {
                        melAccumulator[melIndex[t]] += melCoef[t] * power
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

    /**
     * FFT de 400 pontos: entrada em [srcRe]/[srcIm], saída nos 201 bins em
     * `scratchRe[0..200]` / `scratchIm[0..200]`.
     */
    private fun fft(srcRe: DoubleArray, srcIm: DoubleArray) {
        // Nível 3: DFT_5 direta (n=5, m=1, passo 80, twiddle = 1).
        for (no in 0 until 80) {
            val s = no
            val o = 3 * N_FFT + out3[no]
            val x0r = srcRe[s]
            val x0i = srcIm[s]
            val x1r = srcRe[s + 80]
            val x1i = srcIm[s + 80]
            val x2r = srcRe[s + 160]
            val x2i = srcIm[s + 160]
            val x3r = srcRe[s + 240]
            val x3i = srcIm[s + 240]
            val x4r = srcRe[s + 320]
            val x4i = srcIm[s + 320]
            val a1r = x1r + x4r
            val a1i = x1i + x4i
            val a2r = x2r + x3r
            val a2i = x2i + x3i
            val b1r = x1r - x4r
            val b1i = x1i - x4i
            val b2r = x2r - x3r
            val b2i = x2i - x3i
            scratchRe[o] = x0r + a1r + a2r
            scratchIm[o] = x0i + a1i + a2i
            // p = 1
            val p1r = x0r + C1 * a1r + C2 * a2r
            val p1i = x0i + C1 * a1i + C2 * a2i
            val q1r = S1 * b1r + S2 * b2r
            val q1i = S1 * b1i + S2 * b2i
            scratchRe[o + 1] = p1r + q1i
            scratchIm[o + 1] = p1i - q1r
            scratchRe[o + 4] = p1r - q1i
            scratchIm[o + 4] = p1i + q1r
            // p = 2
            val p2r = x0r + C2 * a1r + C1 * a2r
            val p2i = x0i + C2 * a1i + C1 * a2i
            val q2r = S1 * b2r - S2 * b1r
            val q2i = S1 * b2i - S2 * b1i
            scratchRe[o + 2] = p2r - q2i
            scratchIm[o + 2] = p2i + q2r
            scratchRe[o + 3] = p2r + q2i
            scratchIm[o + 3] = p2i - q2r
        }

        // Nível 2: radix-5 (n=25, m=5) com twiddles W^{jk}.
        run {
            val twRe = tw5Re[0]!!
            val twIm = tw5Im[0]!!
            val m = 5
            for (no in 0 until 16) {
                val child = 3 * N_FFT + out2[no]
                val out = 2 * N_FFT + out2[no]
                for (k in 0 until m) {
                    val x0r = scratchRe[child + k]
                    val x0i = scratchIm[child + k]
                    var t1r = 0.0
                    var t1i = 0.0
                    var t2r = 0.0
                    var t2i = 0.0
                    var t3r = 0.0
                    var t3i = 0.0
                    var t4r = 0.0
                    var t4i = 0.0
                    for (j in 1 until 5) {
                        val wr = twRe[(j - 1) * m + k]
                        val wi = twIm[(j - 1) * m + k]
                        val yr = scratchRe[child + j * m + k]
                        val yi = scratchIm[child + j * m + k]
                        val ar = yr * wr - yi * wi
                        val ai = yr * wi + yi * wr
                        when (j) {
                            1 -> { t1r = ar; t1i = ai }
                            2 -> { t2r = ar; t2i = ai }
                            3 -> { t3r = ar; t3i = ai }
                            else -> { t4r = ar; t4i = ai }
                        }
                    }
                    val a1r = t1r + t4r
                    val a1i = t1i + t4i
                    val a2r = t2r + t3r
                    val a2i = t2i + t3i
                    val b1r = t1r - t4r
                    val b1i = t1i - t4i
                    val b2r = t2r - t3r
                    val b2i = t2i - t3i
                    scratchRe[out + k] = x0r + a1r + a2r
                    scratchIm[out + k] = x0i + a1i + a2i
                    val p1r = x0r + C1 * a1r + C2 * a2r
                    val p1i = x0i + C1 * a1i + C2 * a2i
                    val q1r = S1 * b1r + S2 * b2r
                    val q1i = S1 * b1i + S2 * b2i
                    scratchRe[out + 5 + k] = p1r + q1i
                    scratchIm[out + 5 + k] = p1i - q1r
                    scratchRe[out + 20 + k] = p1r - q1i
                    scratchIm[out + 20 + k] = p1i + q1r
                    val p2r = x0r + C2 * a1r + C1 * a2r
                    val p2i = x0i + C2 * a1i + C1 * a2i
                    val q2r = S1 * b2r - S2 * b1r
                    val q2i = S1 * b2i - S2 * b1i
                    scratchRe[out + 10 + k] = p2r - q2i
                    scratchIm[out + 10 + k] = p2i + q2r
                    scratchRe[out + 15 + k] = p2r + q2i
                    scratchIm[out + 15 + k] = p2i - q2r
                }
            }
        }

        // Nível 1: radix-4 (n=100, m=25).
        for (no in 0 until 4) {
            borboleta4(1 * N_FFT + out1[no], 2 * N_FFT + out1[no], 25, 100, tw4Re[1]!!, tw4Im[1]!!)
        }
        // Nível 0: radix-4 (n=400, m=100), só as 201 saídas não redundantes.
        borboleta4(0, 1 * N_FFT, 100, N_FREQ_BINS, tw4Re[0]!!, tw4Im[0]!!)
    }

    /**
     * Radix-4: `X[k+p*m] = Sum_j (W_n^{jk} Y_j[k mod m]) * (-i)^{jp}`.
     * Y0..Y3 vêm do nível seguinte, em `child + j*m`.
     */
    private fun borboleta4(
        out: Int,
        child: Int,
        m: Int,
        limite: Int,
        twRe: DoubleArray,
        twIm: DoubleArray,
    ) {
        for (k in 0 until m) {
            val y0r = scratchRe[child + k]
            val y0i = scratchIm[child + k]

            val w1r = twRe[k]
            val w1i = twIm[k]
            val y1r = scratchRe[child + m + k]
            val y1i = scratchIm[child + m + k]
            val t1r = y1r * w1r - y1i * w1i
            val t1i = y1r * w1i + y1i * w1r

            val w2r = twRe[m + k]
            val w2i = twIm[m + k]
            val y2r = scratchRe[child + 2 * m + k]
            val y2i = scratchIm[child + 2 * m + k]
            val t2r = y2r * w2r - y2i * w2i
            val t2i = y2r * w2i + y2i * w2r

            val w3r = twRe[2 * m + k]
            val w3i = twIm[2 * m + k]
            val y3r = scratchRe[child + 3 * m + k]
            val y3i = scratchIm[child + 3 * m + k]
            val t3r = y3r * w3r - y3i * w3i
            val t3i = y3r * w3i + y3i * w3r

            val ar = y0r + t2r
            val ai = y0i + t2i
            val br = y0r - t2r
            val bi = y0i - t2i
            val cr = t1r + t3r
            val ci = t1i + t3i
            val dr = t1r - t3r
            val di = t1i - t3i

            if (k < limite) {
                scratchRe[out + k] = ar + cr
                scratchIm[out + k] = ai + ci
            }
            if (k + m < limite) {
                // b - i*d  ->  (br + di, bi - dr)
                scratchRe[out + m + k] = br + di
                scratchIm[out + m + k] = bi - dr
            }
            if (k + 2 * m < limite) {
                scratchRe[out + 2 * m + k] = ar - cr
                scratchIm[out + 2 * m + k] = ai - ci
            }
            if (k + 3 * m < limite) {
                // b + i*d  ->  (br - di, bi + dr)
                scratchRe[out + 3 * m + k] = br - di
                scratchIm[out + 3 * m + k] = bi + dr
            }
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

        /** cos(2*pi/5) e cos(4*pi/5) — constantes da borboleta radix-5. */
        private val C1 = cos(2.0 * PI / 5.0)
        private val S1 = sin(2.0 * PI / 5.0)
        private val C2 = cos(4.0 * PI / 5.0)
        private val S2 = sin(4.0 * PI / 5.0)
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
