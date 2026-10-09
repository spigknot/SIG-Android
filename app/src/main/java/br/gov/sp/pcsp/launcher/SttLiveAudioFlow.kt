package br.gov.sp.pcsp.launcher

/** Regras de envio PCM ao vivo, sem rede, relógio ou microfone próprios.
 *
 * O Muse exige ingresso em tempo real: uma nova sessão recebe áudio atual,
 * sem rajada do buffer anterior, e a pausa mantém o ingresso com silêncio.
 * O chamador fornece o tempo monotônico e executa a espera fora dos locks.
 */
class SttLiveAudioFlow(
    private val isMuse: Boolean,
    private val pcmBytesPerSecond: Int,
) {
    init {
        require(pcmBytesPerSecond > 0)
    }

    val replaysAudio: Boolean get() = !isMuse
    private var nextAudioAtMillis = 0L

    data class Recovery(val replayAudio: ByteArray, val discardedAudioBytes: Long)

    /** Frames curtos evitam lacunas de ingresso mesmo com a opção de 2s. */
    fun chunkMillis(configuredMillis: Int): Int =
        if (isMuse) configuredMillis.coerceIn(20, 100) else configuredMillis

    /** A pausa do Muse envia PCM zerado; os demais mantêm a política anterior. */
    fun outgoingAudio(pcm: ByteArray, length: Int, paused: Boolean): ByteArray? = when {
        !paused -> pcm
        isMuse -> ByteArray(length)
        else -> null
    }

    /** O intervalo sem conexão do Muse é perdido e deve ser informado na UI. */
    fun recoverAudio(bufferedAudio: ByteArray, disconnectedAudioBytes: Long): Recovery =
        if (isMuse) Recovery(ByteArray(0), disconnectedAudioBytes.coerceAtLeast(0L))
        else Recovery(bufferedAudio, 0L)

    fun resetPacing(nowMillis: Long) {
        nextAudioAtMillis = nowMillis
    }

    /** Impede que leituras já acumuladas no AudioRecord sejam enviadas em rajada.
     * Após um atraso, a cadência recomeça no tempo atual, sem tentar compensá-lo.
     */
    fun delayBeforeSendMillis(byteCount: Int, nowMillis: Long): Long {
        if (!isMuse || byteCount <= 0) return 0L
        val sendAtMillis = maxOf(nextAudioAtMillis, nowMillis)
        val durationMillis = (byteCount * 1000L + pcmBytesPerSecond - 1) / pcmBytesPerSecond
        nextAudioAtMillis = sendAtMillis + durationMillis
        return sendAtMillis - nowMillis
    }
}
