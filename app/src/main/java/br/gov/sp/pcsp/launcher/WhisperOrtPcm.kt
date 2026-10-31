package br.gov.sp.pcsp.launcher

import java.security.MessageDigest

/**
 * Contrato de PCM do motor ORT (ORT1-C01/C02/F06).
 *
 * O Whisper exige **16 kHz, mono, float na faixa -1..1**. Este arquivo é a
 * ÚNICA porta de entrada de amostras no motor e existe para que três erros não
 * passem em silêncio:
 *
 * - taxa/channels diferentes de 16 kHz/mono: a conversão pertence ao fluxo comum
 *   de mídia (FFmpeg já usado pela ferramenta). Aqui a entrada incoerente é
 *   RECUSADA com motivo, nunca reamostrada "no jeito";
 * - escala inconsistente entre int16 e float: int16 vira float dividindo por
 *   32768 (o mesmo fator da referência), não por 32767, para que onda cheia e
 *   silêncio deem exatamente -1.0/1.0 e 0.0;
 * - silêncio digital: a guarda [isDigitalSilence] permite responder
 *   `NO_SPEECH` sem gastar encoder, em vez de deixar o modelo inventar fala.
 *
 * Nada de pre-emphasis, AGC ou normalização por pico: o Granite usa AGC porque
 * o frontend dele pede; o Whisper não pede, e herdar isso mudaria as features.
 *
 * Seam puro: sem Android, sem I/O de arquivo.
 */
object WhisperOrtPcm {

    /** Taxa exigida pelo frontend Whisper. */
    const val SAMPLE_RATE = WhisperOrtFrontend.SAMPLE_RATE

    /** Fator int16 -> float (-32768 -> -1.0; 32767 -> 0.99997). */
    const val INT16_SCALE = 32_768.0f

    /** Amostras de 30 s: o bloco que o encoder consome. */
    const val WINDOW_SAMPLES = WhisperOrtFrontend.N_SAMPLES

    /**
     * Converte PCM int16 little-endian em float mono.
     *
     * [channels] precisa ser 1 — sinal estéreo chegando aqui significa que o
     * fluxo de conversão não rodou, e média de canais esconderia um erro de
     * configuração em vez de reportá-lo.
     */
    fun fromInt16Le(bytes: ByteArray, channels: Int = 1): FloatArray {
        require(channels == 1) { "PCM estéreo ($channels canais) deve ser convertido para mono antes do motor ORT" }
        require(bytes.size % 2 == 0) { "PCM int16 com ${bytes.size} bytes ímpares" }
        val out = FloatArray(bytes.size / 2)
        for (i in out.indices) {
            val low = bytes[2 * i].toInt() and 0xFF
            val high = bytes[2 * i + 1].toInt()
            val value = (high shl 8) or low
            out[i] = value.toShort() / INT16_SCALE
        }
        return out
    }

    /** Converte float mono de volta para int16 little-endian (round-trip testável). */
    fun toInt16Le(samples: FloatArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val clamped = samples[i].coerceIn(-1.0f, 1.0f)
            val value = Math.round(clamped * INT16_SCALE).toInt().coerceIn(-32_768, 32_767)
            out[2 * i] = (value and 0xFF).toByte()
            out[2 * i + 1] = ((value shr 8) and 0xFF).toByte()
        }
        return out
    }

    /** Duração em segundos de [sampleCount] amostras a 16 kHz. */
    fun durationSeconds(sampleCount: Int): Double = sampleCount.toDouble() / SAMPLE_RATE

    /** SHA-256 dos bytes de entrada, para amarrar a prova ao áudio exato. */
    fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val hex = StringBuilder(digest.size * 2)
        for (byte in digest) hex.append("%02x".format(byte))
        return hex.toString()
    }

    /** Pico absoluto (útil para distinguir silêncio digital de fala baixa). */
    fun peak(samples: FloatArray): Float {
        var max = 0.0f
        for (sample in samples) {
            val value = if (sample < 0) -sample else sample
            if (value > max) max = value
        }
        return max
    }

    /** RMS da janela; 0.0 quando a janela está vazia. */
    fun rms(samples: FloatArray): Double {
        if (samples.isEmpty()) return 0.0
        var sum = 0.0
        for (sample in samples) sum += sample.toDouble() * sample
        return Math.sqrt(sum / samples.size)
    }

    /**
     * Silêncio DIGITAL: todos os módulos abaixo de [threshold] (1e-4 = -80 dBFS).
     *
     * Serve só para não enviar zero absoluto ao modelo. Ruído de fundo real fica
     * acima do limiar e vai para o encoder normalmente — a decisão de "não há
     * fala" continua sendo do modelo, não desta função.
     */
    fun isDigitalSilence(samples: FloatArray, threshold: Float = 1e-4f): Boolean =
        samples.isNotEmpty() && peak(samples) < threshold

    /**
     * Motivo de recusa quando a taxa declarada não é 16 kHz, ou `null`.
     *
     * Chamado ANTES do frontend: reamostrar aqui produziria features plausíveis
     * a partir de áudio errado, o pior tipo de falha.
     */
    fun rejectionForSampleRate(declared: Int): String? = when {
        declared == SAMPLE_RATE -> null
        declared <= 0 -> "taxa de amostragem inválida ($declared Hz)"
        else -> "taxa ${declared} Hz não suportada: o motor ORT exige 16000 Hz mono (converter no fluxo de mídia)"
    }
}
