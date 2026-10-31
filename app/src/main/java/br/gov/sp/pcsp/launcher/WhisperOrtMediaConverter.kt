package br.gov.sp.pcsp.launcher

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Conversão de mídia para o formato que o frontend ORT exige (PCM 16 kHz mono).
 *
 * Posse do recurso físico (§2.4): o pedido de cancelamento e o resultado lógico
 * NÃO equivalem ao recurso encerrado. Quando o término real não é observado
 * (prazo estourado ou cancelamento sem callback), a função devolve
 * [Resultado.SessaoAtiva] — e NUNCA apaga o destino (pode estar em escrita) nem
 * decide pela reserva. Quem observa o término real é o dono do job, através de
 * [Resultado.SessaoAtiva.esperarFim]; o callback tardio, inclusive após timeout,
 * finaliza e limpa uma única vez.
 *
 * O [Motor] é injetável para prova HOST com callbacks controlados; a produção usa
 * [MotorFfmpeg]. O cancelamento é SEMPRE da sessão deste job, nunca o global.
 */
object WhisperOrtMediaConverter {

    private const val TIMEOUT_CONVERSAO_MS = 60_000L
    private const val ESPERA_CANCELAMENTO_MS = 5_000L

    /** Sessão viva de conversão (recurso físico ainda não encerrado). */
    interface Sessao {
        val id: Long
    }

    /** Término observado da sessão. */
    data class Final(
        val ok: Boolean,
        val cancelada: Boolean,
        val logsCauda: String,
    )

    /** Motor de conversão: produção ([MotorFfmpeg]) ou controlado (testes). */
    interface Motor {
        fun iniciar(argumentos: Array<String>, aoTerminar: (Final) -> Unit): Sessao
        fun cancelar(sessao: Sessao)
    }

    /** Motor real, sobre o FFmpegKit. */
    object MotorFfmpeg : Motor {
        private data class Viva(override val id: Long) : Sessao

        override fun iniciar(argumentos: Array<String>, aoTerminar: (Final) -> Unit): Sessao {
            // A API devolve a sessão antes de concluir: guardar o id aqui (e não
            // só no callback) deixa o cancelamento alcançar a sessão ativa.
            var vista: com.arthenica.ffmpegkit.FFmpegSession? = null
            val iniciada = FFmpegKit.executeWithArgumentsAsync(
                argumentos,
                { fim ->
                    vista = fim
                    aoTerminar(
                        Final(
                            ok = ReturnCode.isSuccess(fim.returnCode),
                            cancelada = ReturnCode.isCancel(fim.returnCode),
                            logsCauda = fim.allLogsAsString.orEmpty().lines()
                                .map { it.trim() }
                                .filter { it.isNotEmpty() }
                                .takeLast(2)
                                .joinToString(" "),
                        ),
                    )
                },
                { },
                { },
            )
            return Viva((vista ?: iniciada).sessionId)
        }

        override fun cancelar(sessao: Sessao) {
            runCatching { FFmpegKit.cancel(sessao.id) }
        }
    }

    /** Nome do arquivo convertido, derivado do original (mesma pasta). */
    fun wavDeSaida(origem: File): File = File(origem.parentFile, origem.nameWithoutExtension + "-ort16k.wav")

    /** Desfecho tipado da conversão. */
    sealed class Resultado {
        data class Converteu(val arquivo: File) : Resultado()
        data class Cancelada(val motivo: String = "cancelado durante a conversao") : Resultado()
        data class Falhou(val motivo: String) : Resultado()

        /**
         * O prazo estourou ou o cancelamento não teve callback, MAS A SESSÃO PODE
         * ESTAR ATIVA. Não autoriza apagar o arquivo nem liberar a reserva:
         * [esperarFim] bloqueia até o término real e o dono finaliza e limpa uma
         * única vez quando ele chegar.
         */
        data class SessaoAtiva(
            val motivo: String,
            val sessaoId: Long,
            val destino: File,
            val esperarFim: () -> Final?,
        ) : Resultado()
    }

    /**
     * @param isCancelada consultado a cada 100 ms; quando `true`, a sessão DESTE
     *   job é cancelada.
     * @param onProgresso etapas legíveis para a tela.
     * @param motor produção ou controlado (prova HOST).
     * @param timeoutMs prazo do término real, em relógio MONOTÔNICO.
     */
    fun converterParaWav16kMono(
        origem: File,
        destino: File,
        isCancelada: () -> Boolean = { false },
        onProgresso: (String) -> Unit = { },
        motor: Motor = MotorFfmpeg,
        timeoutMs: Long = TIMEOUT_CONVERSAO_MS,
    ): Resultado {
        if (!origem.isFile || origem.length() == 0L) {
            return Resultado.Falhou("arquivo de entrada ausente ou vazio")
        }
        if (isCancelada()) return Resultado.Cancelada()
        destino.delete()
        val argumentos = arrayOf(
            "-y",
            "-i", origem.absolutePath,
            "-vn",
            "-ar", "16000",
            "-ac", "1",
            "-c:a", "pcm_s16le",
            "-f", "wav",
            destino.absolutePath,
        )
        onProgresso("convertendo para 16 kHz mono…")
        val fim = CountDownLatch(1)
        var final: Final? = null
        val sessao = try {
            motor.iniciar(argumentos) { f ->
                final = f
                fim.countDown()
            }
        } catch (falha: Throwable) {
            return Resultado.Falhou("falha ao iniciar o conversor: ${falha.message}")
        }

        val limiteNs = System.nanoTime() + timeoutMs * 1_000_000L
        var canceladaPorNos = false
        while (!fim.await(100, TimeUnit.MILLISECONDS)) {
            if (System.nanoTime() > limiteNs) {
                // Cancela somente esta sessão; a posse continua até o callback.
                motor.cancelar(sessao)
                return sessaoAtiva(
                    "conversao excedeu ${timeoutMs / 1_000}s sem término observado",
                    sessao,
                    destino,
                    fim,
                    { final },
                )
            }
            if (!canceladaPorNos && isCancelada()) {
                canceladaPorNos = true
                motor.cancelar(sessao)
                if (!fim.await(ESPERA_CANCELAMENTO_MS, TimeUnit.MILLISECONDS)) {
                    // Cancelamento pedido, sessão ainda ativa: não apaga, não
                    // libera — o término real decide.
                    return sessaoAtiva(
                        "cancelamento pedido; sessão ainda ativa após ${ESPERA_CANCELAMENTO_MS / 1_000}s",
                        sessao,
                        destino,
                        fim,
                        { final },
                    )
                }
                break
            }
        }
        val f = final ?: return Resultado.Falhou("conversor não devolveu término")
        if (f.cancelada) {
            destino.delete()
            return Resultado.Cancelada()
        }
        if (!f.ok) {
            destino.delete()
            return Resultado.Falhou("falha ao converter audio ${f.logsCauda.take(120)}".trim())
        }
        if (!destino.isFile || destino.length() == 0L) return Resultado.Falhou("conversao nao gerou arquivo")
        onProgresso("conversão concluída")
        return Resultado.Converteu(destino)
    }

    /** Aguarda o callback físico; interrupção da espera não autoriza liberar posse. */
    internal fun aguardarTermino(ativa: Resultado.SessaoAtiva): Final {
        var interrompida = false
        try {
            while (true) {
                try {
                    val final = ativa.esperarFim()
                    if (final != null) return final
                    // Ausência de callback é quarentena, nunca ACK ou sucesso.
                    Thread.sleep(100)
                } catch (_: InterruptedException) {
                    interrompida = true
                }
            }
        } finally {
            if (interrompida) Thread.currentThread().interrupt()
        }
    }

    private fun sessaoAtiva(
        motivo: String,
        sessao: Sessao,
        destino: File,
        fim: CountDownLatch,
        lerFinal: () -> Final?,
    ): Resultado.SessaoAtiva =
        Resultado.SessaoAtiva(motivo, sessao.id, destino) {
            fim.await()
            lerFinal()
        }
}
