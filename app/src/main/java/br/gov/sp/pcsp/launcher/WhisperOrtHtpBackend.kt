package br.gov.sp.pcsp.launcher

/**
 * R100 §2.4 — BACKEND da rota HTP do produto: encoder EPContext W8A16 rodando no
 * QNN HTP e os decoders INT8 do PRODUTO em CPU (`openDecodersOnly`).
 *
 * A falha do encoder é TIPADA ([WhisperOrtHtpFalha]): o dono do pedido precisa
 * distingui-la de erro de decoder para trocar a rota e transcrever o MESMO
 * pedido na CPU. Falha de decoder NÃO vira falha de HTP — os decoders são os
 * mesmos da rota CPU, e trocar de rota não os conserta.
 *
 * Nada aqui é novo em conceito: é a rota provada na R97, sem reflexão e sem
 * sandbox, com contadores de tempo para o log de diagnóstico.
 */
class WhisperOrtHtpFalha(motivo: String, cause: Throwable? = null) :
    RuntimeException("htp: $motivo", cause)

/** Sessão EPContext já aberta (QNN HTP); injetável para prova JVM. */
internal interface WhisperOrtHtpSessao : AutoCloseable {
    /** Recebe o log-mel no dtype do wrapper e devolve o hidden no mesmo dtype. */
    fun rodar(entrada: ByteArray): ByteArray
}

internal class WhisperOrtHtpBackend(
    private val htp: WhisperOrtHtpSessao,
    private val decoders: WhisperOrtBackend,
    private val dtype: String = WhisperOrtHtpConversao.DTYPE_I16,
    private val relogio: () -> Long = { System.nanoTime() },
    private val route: String = WhisperOrtHtpAdmissao.ROTA_HTP,
) : WhisperOrtBackend {

    override val rota: String get() = route
    override val melBinsDetectados: Int get() = decoders.melBinsDetectados

    var encodes: Int = 0
        private set
    var encodeMs: Long = 0
        private set

    override fun encode(logMel: FloatArray): FloatArray {
        val inicio = relogio()
        val entrada = WhisperOrtHtpConversao.paraDtype(logMel, dtype)
        val saida = try {
            htp.rodar(entrada)
        } catch (failure: Throwable) {
            throw WhisperOrtHtpFalha("encoder: ${failure.javaClass.simpleName}: ${failure.message}", failure)
        }
        val hidden = try {
            WhisperOrtHtpConversao.paraFloat(saida, dtype)
        } catch (failure: Throwable) {
            throw WhisperOrtHtpFalha("saida: ${failure.javaClass.simpleName}: ${failure.message}", failure)
        }
        encodeMs += (relogio() - inicio) / 1_000_000
        encodes += 1
        return hidden
    }

    override fun start(hidden: FloatArray, prompt: IntArray): FloatArray =
        decoders.start(hidden, prompt)

    override fun step(token: Int): FloatArray = decoders.step(token)

    override fun reopen(): Boolean = decoders.reopen()

    override fun close() {
        runCatching { htp.close() }
        decoders.close()
    }
}
