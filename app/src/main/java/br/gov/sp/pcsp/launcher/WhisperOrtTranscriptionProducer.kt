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
        data class Failed(val jobId: String, val attempt: Long, val error: String) : Outcome()
    }

    /**
     * Executa a transcrição de um pedido.
     *
     * Nunca lança por falha de motor: devolve [Outcome.Failed] com a causa. O
     * lease é sempre liberado (idempotente) e o compute sempre encerrado.
     */
    fun transcribe(pacote: WhisperOrtPackageInstaller.Validated, request: Request): Outcome {
        val chave = WhisperOrtSessionCache.keyFor(
            identity = pacote.identity,
            packageVersion = pacote.versionKey,
            runtime = runtimeVersion,
            config = request.policy.config,
            threads = request.threads,
        )
        // Preflight: cancelamento já conhecido não abre sessão (nem paga load).
        if (request.isCancelled()) {
            return Outcome.Cancelled(request.jobId, request.attempt, zeroOpen = true)
        }
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
            )) {
                is WhisperOrtSessionCache.Aquisicao.Ok -> aq.lease
                is WhisperOrtSessionCache.Aquisicao.Recusada ->
                    return Outcome.Failed(
                        request.jobId,
                        request.attempt,
                        "abertura de sessão recusada: " + aq.motivo,
                    )
            }
        } catch (failure: Throwable) {
            return Outcome.Failed(
                request.jobId,
                request.attempt,
                "acquire lançou: ${failure.javaClass.simpleName}: ${failure.message}",
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
                val transcriber = WhisperOrtTranscriber(tokenizer, request.policy)
                val transcript = transcriber.transcribe(lease.backend, request.pcm, request.isCancelled)
                Outcome.Done(
                    transcript = transcript,
                    cacheHit = hit,
                    sessionOpenedMillis = openMillis,
                    jobId = request.jobId,
                    attempt = request.attempt,
                )
            } finally {
                cache.endCompute(lease)
            }
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
            WhisperOrtPackageInstaller.open(root, identity)
    }
}
