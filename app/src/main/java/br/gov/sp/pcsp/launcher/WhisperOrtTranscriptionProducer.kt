package br.gov.sp.pcsp.launcher

import java.io.File

/**
 * Produtor COMUM de transcrição ORT (ORT1-M4, adendo6 §5).
 *
 * É a peça que faltava: até aqui o pacote validado, o cache com lease e o
 * transcriber existiam como peças soltas e o runner do laboratório fazia a
 * própria montagem. Este produtor é o único caminho:
 *
 * 1. recebe o pacote **já validado** ([WhisperOrtPackageInstaller.Validated]) e
 *    deriva a chave do cache dele (identidade + versão imutável + config +
 *    runtime + threads);
 * 2. `acquire` no [WhisperOrtSessionCache] — hit reutiliza as três sessões,
 *    miss abre e mede o custo;
 * 3. toma o **compute exclusivo** com `tryBeginCompute`: se outro job está
 *    computando na mesma sessão, devolve [Outcome.BUSY] em vez de rodar dois
 *    estados sobre o mesmo hidden/KV;
 * 4. transcreve com o `WhisperOrtTranscriber` (modo temporal ou textual conforme
 *    a política congelada no request);
 * 5. encerra o compute e libera o lease **no finally**, sem fechar a sessão (ela
 *    fica para o próximo job).
 *
 * Seam sem Android: depende só de `java.io` e das classes puras do motor.
 */
class WhisperOrtTranscriptionProducer(
    private val cache: WhisperOrtSessionCache,
    private val tokenizer: WhisperOrtTokenizer,
    private val runtimeVersion: String = DEFAULT_RUNTIME,
    private val clock: () -> Long = { System.nanoTime() },
    /** Chamado quando a rota HTP falhou: desliga a rota e conta a falha. */
    private val aoFalharHtp: (String) -> Unit = { },
    /** Chamado quando uma transcrição INTEIRA terminou na rota HTP. */
    private val aoSucessoHtp: () -> Unit = { },
) {

    /**
     * Pedido congelado na admissão.
     *
     * [jobId]/[attempt] viajam no resultado: um ACK não pode ser confundido com o
     * de outra tentativa.
     */
    data class Request(
        val jobId: String,
        val attempt: Long,
        val pcm: FloatArray,
        val policy: WhisperOrtGenerationPolicy,
        val threads: Int = WhisperOrtSession.DEFAULT_THREADS,
        val isCancelled: () -> Boolean = { false },
        /** Rota pedida na admissão (`htp`/`cpu`) — a fábrica do cache decide o backend. */
        val rota: String = WhisperOrtHtpAdmissao.ROTA_CPU,
    )

    /** Desfecho do produtor. Nenhum valor implica sucesso por omissão. */
    sealed class Outcome {
        /** Transcrição concluída (parcial ou integral, conforme [transcript]). */
        data class Done(
            val transcript: WhisperOrtTranscript,
            val cacheHit: Boolean,
            val sessionOpenedMillis: Long,
            val jobId: String,
            val attempt: Long,
            /** Rota que REALMENTE produziu o texto (`htp`/`cpu`). */
            val rotaEfetiva: String = WhisperOrtHtpAdmissao.ROTA_CPU,
        ) : Outcome()

        /** Sessão ocupada por outro compute: recusa-se a rodar em paralelo. */
        data class Busy(val jobId: String, val attempt: Long, val reason: String) : Outcome()

        /**
         * Cancelamento conhecido antes/durante a carga.
         *
         * Distinto de [Failed]: cancelar não é defeito, e o consumidor precisa
         * dele para emitir terminal próprio sem marcar erro.
         */
        data class Cancelled(val jobId: String, val attempt: Long, val zeroOpen: Boolean) : Outcome()

        /** Falha de pacote/sessão antes de qualquer token. */
        data class Failed(
            val jobId: String,
            val attempt: Long,
            val error: String,
            /** `true` quando a causa é da ROTA HTP (então cabe a CPU). */
            val origemHtp: Boolean = false,
        ) : Outcome()
    }

    /**
     * Executa a transcrição de um pedido.
     *
     * Rota HTP (R100 §2.4): qualquer falha da metade HTP — abrir, criar a sessão
     * ou rodar — devolve [Outcome.Failed] com `origemHtp`, e o pedido é
     * transcrito de novo **na CPU, do início**. Nunca lança por falha de motor:
     * o lease é sempre liberado e o compute sempre encerrado.
     */
    fun transcribe(pacote: WhisperOrtPackageInstaller.Validated, request: Request): Outcome {
        // Preflight: cancelamento já conhecido não abre sessão (nem paga load).
        if (request.isCancelled()) {
            return Outcome.Cancelled(request.jobId, request.attempt, zeroOpen = true)
        }
        if (!WhisperOrtHtpAdmissao.isHtp(request.rota)) {
            return executar(pacote, request, WhisperOrtHtpAdmissao.ROTA_CPU)
        }
        val primeira = executar(pacote, request, request.rota)
        if (primeira !is Outcome.Failed || !primeira.origemHtp) return primeira
        // Fallback do MESMO pedido: o lease HTP já saiu no finally de
        // `executar`; desliga a rota (contando a falha) e reabre na CPU.
        aoFalharHtp(primeira.error)
        if (request.isCancelled()) {
            return Outcome.Cancelled(request.jobId, request.attempt, zeroOpen = false)
        }
        return executar(pacote, request, WhisperOrtHtpAdmissao.ROTA_CPU)
    }

    /** Uma passada completa do pedido em UMA rota. */
    private fun executar(
        pacote: WhisperOrtPackageInstaller.Validated,
        request: Request,
        rota: String,
    ): Outcome {
        val chave = WhisperOrtSessionCache.keyFor(
            identity = pacote.identity,
            packageVersion = pacote.versionKey,
            runtime = runtimeVersion,
            config = request.policy.config,
            threads = request.threads,
            rota = rota,
        )
        // Aquisição ATÔMICA vinculada ao pacote: a chave usa o runtime e a config
        // EFETIVOS deste pedido, e a causa da recusa viaja no retorno — sem ler
        // global fora da operação protegida (havia corrida entre `acquire` e a
        // leitura de `lastRefusal`).
        val lease = try {
            when (val aq = cache.adquirir(
                pacote,
                threads = request.threads,
                runtime = runtimeVersion,
                config = request.policy.config,
                rota = rota,
            )) {
                is WhisperOrtSessionCache.Aquisicao.Ok -> aq.lease
                is WhisperOrtSessionCache.Aquisicao.Recusada ->
                    return Outcome.Failed(
                        request.jobId,
                        request.attempt,
                        "abertura de sessão recusada: " + aq.motivo,
                        origemHtp = WhisperOrtHtpAdmissao.isHtp(rota),
                    )
            }
        } catch (failure: Throwable) {
            return Outcome.Failed(
                request.jobId,
                request.attempt,
                "acquire lançou: ${failure.javaClass.simpleName}: ${failure.message}",
                origemHtp = WhisperOrtHtpAdmissao.isHtp(rota),
            )
        }
        // Fato da PRÓPRIA tentativa: não depende mais do contador global, que
        // misturava jobs concorrentes.
        val hit = lease.hit
        val openMillis = lease.openMillis
        try {
            if (!cache.tryBeginCompute(lease)) {
                return Outcome.Busy(request.jobId, request.attempt, "sessão já em compute (${lease.key.asString()})")
            }
            // TODO o trecho após a tomada de compute — construção do transcriber,
            // validação de prefixo/config, relógio e callbacks — fica sob o mesmo
            // finally. Antes a construção estava FORA e uma exceção ali deixava
            // computing=1/inUse=1 presos: o próximo pedido válido recebia Busy e
            // closeAll recusava.
            return try {
                // Adendo 17 §1 — frontend com a tabela do MODELO do pacote
                // (80 bins no small, 128 no turbo): com 128 fixo o encoder
                // do small falhava no ConvInteger (C: 128, kernel: 80).
                val transcriber = WhisperOrtTranscriber(
                    tokenizer,
                    request.policy,
                    WhisperOrtFrontend(WhisperOrtModelCatalog.filtrosMelOuDefault(pacote.identity.model)),
                )
                val transcript = transcriber.transcribe(lease.backend, request.pcm, request.isCancelled)
                val efetiva = lease.backend.rota
                if (WhisperOrtHtpAdmissao.isHtp(efetiva)) aoSucessoHtp()
                Outcome.Done(
                    transcript = transcript,
                    cacheHit = hit,
                    sessionOpenedMillis = openMillis,
                    jobId = request.jobId,
                    attempt = request.attempt,
                    rotaEfetiva = efetiva,
                )
            } finally {
                cache.endCompute(lease)
            }
        } catch (htp: WhisperOrtHtpFalha) {
            // Falha do encoder HTP: só é fallback se o backend era mesmo HTP.
            return Outcome.Failed(
                request.jobId,
                request.attempt,
                "htp: ${htp.message}",
                origemHtp = WhisperOrtHtpAdmissao.isHtp(lease.backend.rota),
            )
        } catch (failure: Throwable) {
            return Outcome.Failed(
                request.jobId,
                request.attempt,
                "${failure.javaClass.simpleName}: ${failure.message}",
            )
        } finally {
            cache.release(lease)
        }
    }

    /** Retrato do cache para o relatório do job. */
    fun cacheStats(): WhisperOrtSessionCache.Stats = cache.stats()

    companion object {
        /** Runtime default declarado no relatório quando o chamador não informa. */
        const val DEFAULT_RUNTIME = "onnxruntime-android-qnn:1.29.0"

        /**
         * Chave de pacote a partir de um diretório já validado.
         *
         * Existe para o chamador que só tem o caminho: abre pelo marcador e
         * devolve o handle validado, sem jamais montar hash por conta própria.
         */
        fun packageAt(
            root: File,
            identity: WhisperOrtModelPackage.Identity,
        ): WhisperOrtPackageInstaller.Outcome =
            WhisperOrtPackageInstaller.open(
                root, identity,
                WhisperOrtModelCatalog.conjuntoPara(identity.model),
            )
    }
}
