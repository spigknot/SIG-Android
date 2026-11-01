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
            FFmpegKit.cancel(sessao.id)
        }
    }

    /** Notifica progresso sem deixar falha de observador virar prova de término. */
    private fun progressoSeguro(onProgresso: (String) -> Unit, etapa: String) {
        runCatching { onProgresso(etapa) }
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
         * [esperarFim] aguarda o término real em fatias e o dono finaliza e limpa
         * uma única vez quando ele chegar.
         *
         * Carrega [sessao] e [motor] para o dono poder encaminhar um NOVO pedido
         * de cancelamento ao recurso físico (nunca o cancelamento global).
         */
        data class SessaoAtiva(
            val motivo: String,
            val sessaoId: Long,
            val destino: File,
            val sessao: Sessao,
            val motor: Motor,
            val esperarFim: (timeoutMs: Long) -> Final?,
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
        progressoSeguro(onProgresso, "convertendo para 16 kHz mono…")
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
        try {
            while (!fim.await(100, TimeUnit.MILLISECONDS)) {
                if (System.nanoTime() > limiteNs) {
                    // Cancela somente esta sessão; a posse continua até o callback.
                    // Falha do cancel é best-effort: a sessão pode seguir ativa.
                    runCatching { motor.cancelar(sessao) }
                    return sessaoAtiva(
                        "conversao excedeu ${timeoutMs / 1_000}s sem término observado",
                        sessao,
                        motor,
                        destino,
                        fim,
                        { final },
                    )
                }
                if (!canceladaPorNos && runCatching { isCancelada() }.getOrDefault(false)) {
                    canceladaPorNos = true
                    runCatching { motor.cancelar(sessao) }
                    if (!fim.await(ESPERA_CANCELAMENTO_MS, TimeUnit.MILLISECONDS)) {
                        // Cancelamento pedido, sessão ainda ativa: não apaga, não
                        // libera — o término real decide.
                        return sessaoAtiva(
                            "cancelamento pedido; sessão ainda ativa após ${ESPERA_CANCELAMENTO_MS / 1_000}s",
                            sessao,
                            motor,
                            destino,
                            fim,
                            { final },
                        )
                    }
                    break
                }
            }
        } catch (_: InterruptedException) {
            // Interrupção da espera NÃO libera a posse: o recurso físico pode
            // seguir ativo sem callback. Devolve SessaoAtiva para o dono drenar
            // até o término real; o cancel é best-effort.
            Thread.currentThread().interrupt()
            runCatching { motor.cancelar(sessao) }
            return sessaoAtiva(
                "espera interrompida; sessão ainda ativa sem término observado",
                sessao,
                motor,
                destino,
                fim,
                { final },
            )
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
        progressoSeguro(onProgresso, "conversão concluída")
        return Resultado.Converteu(destino)
    }

    /** Aguarda o callback físico; interrupção da espera não autoriza liberar posse. */
    internal fun aguardarTermino(ativa: Resultado.SessaoAtiva): Final {
        var interrompida = false
        try {
            while (true) {
                try {
                    val final = ativa.esperarFim(Long.MAX_VALUE)
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

    /**
     * Drena a conversão até o fim físico, encaminhando cancelamentos tardios.
     *
     * Diferença para [aguardarTermino]: um cancel pedido DEPOIS do diagnóstico
     * chega à SESSÃO dona ([SessaoAtiva.motor]/[SessaoAtiva.sessao]), nunca ao
     * cancelamento global — e a espera continua, porque pedido de cancelamento
     * não equivale a recurso encerrado. Só o término observado encerra.
     * Relógio monotônico; falha/interrupção da espera mantém a posse.
     */
    fun drenar(
        ativa: Resultado.SessaoAtiva,
        isCancelada: () -> Boolean = { false },
        onProgresso: (String) -> Unit = { },
    ): Final {
        var cancelEnviado = false
        var interrompida = false
        try {
            while (true) {
                if (!cancelEnviado && runCatching { isCancelada() }.getOrDefault(false)) {
                    // Só marca como enviado quando o cancel CHEGOU ao recurso.
                    // Se a chamada lançar, a próxima volta tenta de novo — sem
                    // presumir posse liberada nem recurso encerrado.
                    val enviado = runCatching { ativa.motor.cancelar(ativa.sessao) }.isSuccess
                    if (enviado) {
                        cancelEnviado = true
                        progressoSeguro(onProgresso, "conversão cancelada; aguardando término físico…")
                    } else {
                        // Cancel pedido mas não entregue + espera que falha sem
                        // intervalo consumiria CPU em laço: pausa breve antes
                        // da próxima tentativa (posse e cancel preservados).
                        try {
                            Thread.sleep(100)
                        } catch (_: InterruptedException) {
                            interrompida = true
                        }
                        // Mesmo se cancelar falhar, o callback físico pode já
                        // ter chegado: a espera abaixo precisa observá-lo.
                    }
                }
                val saida = runCatching { ativa.esperarFim(500) }
                val falha = saida.exceptionOrNull()
                if (falha != null) {
                    if (falha is InterruptedException) interrompida = true
                    // Falha da espera NÃO é término: mantém a posse e evita
                    // laço quente quando a falha é persistente e imediata.
                    try {
                        Thread.sleep(100)
                    } catch (_: InterruptedException) {
                        interrompida = true
                    }
                    continue
                }
                val fim = saida.getOrNull()
                if (fim != null) return fim
            }
        } finally {
            if (interrompida) Thread.currentThread().interrupt()
        }
    }

    private fun sessaoAtiva(
        motivo: String,
        sessao: Sessao,
        motor: Motor,
        destino: File,
        fim: CountDownLatch,
        lerFinal: () -> Final?,
    ): Resultado.SessaoAtiva =
        Resultado.SessaoAtiva(motivo, sessao.id, destino, sessao, motor) { timeoutMs ->
            if (fim.await(timeoutMs, TimeUnit.MILLISECONDS)) lerFinal() else null
        }
}
