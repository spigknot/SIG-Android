package br.gov.sp.pcsp.launcher

/**
 * Orquestração de uma transcrição ORT (ORT1-D07/E01/F06, adendo8 §5).
 *
 * Dois caminhos, escolhidos pela política:
 *
 * **Temporal** (`policy.timestampMode`): a janela não avança por hop fixo — o
 * avanço vem do `seek` do [WhisperOrtSegments.retrieve], porte do
 * `_retrieve_segment`. Só segmentos FECHADOS por par de timestamps são
 * publicados; a cauda cortada fica PENDENTE e a próxima janela recomeça na última
 * fronteira fechada. Não há deduplicação lexical em nenhum ponto.
 *
 * **Textual** (modo antigo, sem timestamps): hop fixo e junção por remoção da
 * maior repetição exata — mantido como caminho legado, com as mesmas garantias
 * de terminal.
 *
 * Avanço não progressivo (seek <= offset) tem limite de tentativas e termina em
 * PARCIAL/erro com diagnóstico: nunca laço infinito nem salto silencioso.
 */
class WhisperOrtTranscriber(
    private val tokenizer: WhisperOrtTokenizer,
    private val policy: WhisperOrtGenerationPolicy = WhisperOrtGenerationPolicy(),
    private val frontend: WhisperOrtFrontend = WhisperOrtFrontend(),
    private val hopSamples: Int = DEFAULT_HOP_SAMPLES,
    private val clock: () -> Long = { System.nanoTime() },
) {

    private val generator = WhisperOrtGenerator(tokenizer, policy, clock)

    /**
     * Transcreve [pcm] (16 kHz mono float) usando [backend].
     *
     * [backend] NÃO é fechado aqui: o dono é o cache/produtor.
     */
    fun transcribe(
        backend: WhisperOrtBackend,
        pcm: FloatArray,
        isCancelled: () -> Boolean = { false },
    ): WhisperOrtTranscript {
        val startedAt = clock()
        if (pcm.isEmpty()) {
            return WhisperOrtTranscript(
                text = "", windows = emptyList(), outcome = WhisperOrtOutcome.NO_AUDIO,
                stop = WhisperOrtStopReason.ERROR, totalMillis = 0, error = "PCM vazio: nada a transcrever",
            )
        }
        if (WhisperOrtPcm.isDigitalSilence(pcm)) {
            return WhisperOrtTranscript(
                text = "", windows = emptyList(), outcome = WhisperOrtOutcome.NO_SPEECH,
                stop = WhisperOrtStopReason.EOS, totalMillis = (clock() - startedAt) / 1_000_000,
            )
        }
        return if (policy.timestampMode) temporal(backend, pcm, isCancelled, startedAt)
        else textual(backend, pcm, isCancelled, startedAt)
    }

    /** Caminho temporal: avanço por seek de fronteira fechada. */
    private fun temporal(
        backend: WhisperOrtBackend,
        pcm: FloatArray,
        isCancelled: () -> Boolean,
        startedAt: Long,
    ): WhisperOrtTranscript {
        val results = ArrayList<WhisperOrtWindowResult>(2)
        var offset = 0
        var texto = ""
        var fim = 0.0
        var featuresMillis = 0L
        var semProgresso = 0
        // Cauda que ficou sem confirmação na janela anterior: precisa reaparecer
        // na seguinte. Antes era guardada e ignorada — "a fechado, b pendente,
        // continuação sem b" terminava SUCCESS só com "a".
        var pendente = ""
        // Origem temporal da cauda: a janela seguinte precisa COBRIR essa origem
        // (reexaminar o trecho) E conter o texto. Texto igual sem cobertura não
        // confirma nada — seria publicar perda como sucesso.
        var pendenteOrigem = 0.0
        var pendentePerdido = false
        val maxJanelas = WhisperOrtFrontend.MAX_WINDOWS
        while (offset < pcm.size) {
            if (results.size >= maxJanelas) {
                return assemble(pcm, results, WhisperOrtOutcome.PARTIAL, texto, startedAt, featuresMillis,
                    "limite de janelas atingido")
            }
            if (isCancelled()) {
                return assemble(pcm, results, WhisperOrtOutcome.CANCELLED, texto, startedAt, featuresMillis, null)
            }
            val usable = minOf(WhisperOrtFrontend.N_SAMPLES, pcm.size - offset)
            val janela = WhisperOrtWindow(offset, usable, WhisperOrtFrontend.N_SAMPLES)
            val inicioFeatures = clock()
            val features = frontend.logMel(frontend.slice(pcm, janela))
            featuresMillis += (clock() - inicioFeatures) / 1_000_000
            val generation = generator.generate(backend, features, isCancelled)
            val retrieval = WhisperOrtSegments.retrieve(
                tokens = generation.tokens,
                noTimestampsTokenId = policy.config.noTimestampsTokenId,
                vocabSize = tokenizer.vocabSize,
                windowOffsetSeconds = offset.toDouble() / WhisperOrtFrontend.SAMPLE_RATE,
                windowDurationSeconds = usable.toDouble() / WhisperOrtFrontend.SAMPLE_RATE,
            ) { ids -> tokenizer.decode(ids) }
            val textoJanela = retrieval.segments.joinToString(" ") { it.text }
            if (pendente.isNotBlank()) {
                // Tolerância de 0,30 s (15 passos de timestamp): o modelo quantiza
                // em 0,02 s e pode deslocar o início; a contraprova de repetição
                // legítima (a em 0..1 s e em 28..29 s) está a 27 s de distância.
                val cobreOrigem = retrieval.segments.any { it.startSeconds <= pendenteOrigem + 0.30 }
                val contemTexto = textoJanela.contains(pendente.trim())
                if (!cobreOrigem || !contemTexto) {
                    // Sem cobertura, a janela seguinte nem reexaminou o trecho; sem o
                    // texto, ela não reconheceu a cauda. Nos dois casos, falar em
                    // SUCCESS seria publicar perda de fala como sucesso.
                    pendentePerdido = true
                }
            }
            pendente = retrieval.pendingText
            pendenteOrigem = retrieval.pendingFromSeconds
            val (novoTexto, novoFim) = WhisperOrtSegments.join(texto, fim, retrieval.segments)
            texto = novoTexto
            fim = novoFim
            results += WhisperOrtWindowResult(janela, generation, retrieval = retrieval)
            if (generation.stopReason == WhisperOrtStopReason.ERROR) {
                return assemble(pcm, results, WhisperOrtOutcome.ERROR, texto, startedAt, featuresMillis, generation.error)
            }
            if (generation.stopReason == WhisperOrtStopReason.CANCELLED) {
                return assemble(pcm, results, WhisperOrtOutcome.CANCELLED, texto, startedAt, featuresMillis, null)
            }
            // Avanço: o seek é absoluto (segundos). Nunca para trás.
            val proximo = Math.round(retrieval.seekSeconds * WhisperOrtFrontend.SAMPLE_RATE).toInt()
            if (proximo <= offset) {
                // NUNCA pular hopSamples (26 s) para "destravar": isso descartava
                // fala silenciosamente e o SUCCESS seguinte escondia a perda.
                // Repetir o mesmo trecho não ajuda (mesma entrada, mesmo resultado),
                // então encerra PARTIAL com diagnóstico e texto preservado.
                return assemble(pcm, results, WhisperOrtOutcome.PARTIAL, texto, startedAt, featuresMillis,
                    "seek não avançou (${retrieval.seekSeconds}s em offset ${offset / WhisperOrtFrontend.SAMPLE_RATE}s)")
            } else {
                semProgresso = 0
                offset = proximo
            }
        }
        if (pendentePerdido || pendente.isNotBlank()) {
            val motivo = if (pendentePerdido) {
                "cauda pendente nao reconciliada na janela seguinte"
            } else {
                "cauda final sem confirmacao (texto preservado)"
            }
            return assemble(pcm, results, WhisperOrtOutcome.PARTIAL, texto, startedAt, featuresMillis, motivo)
        }
        val parcial = results.any { it.generation.stopReason == WhisperOrtStopReason.MAX_TOKENS }
        val desfecho = when {
            results.isEmpty() -> WhisperOrtOutcome.NO_SPEECH
            parcial -> WhisperOrtOutcome.PARTIAL
            texto.isBlank() -> WhisperOrtOutcome.NO_SPEECH
            else -> WhisperOrtOutcome.SUCCESS
        }
        return assemble(pcm, results, desfecho, texto, startedAt, featuresMillis, null)
    }

    /** Caminho legado: hop fixo + junção por repetição exata. */
    private fun textual(
        backend: WhisperOrtBackend,
        pcm: FloatArray,
        isCancelled: () -> Boolean,
        startedAt: Long,
    ): WhisperOrtTranscript {
        val plan = frontend.planWindows(pcm.size, hopSamples)
        val results = ArrayList<WhisperOrtWindowResult>(plan.size)
        var featuresMillis = 0L
        for (janela in plan) {
            if (isCancelled()) {
                val parcial = WhisperOrtTextMerge.mergeAll(results.map { it.generation.text })
                return assemble(pcm, results, WhisperOrtOutcome.CANCELLED, parcial, startedAt, featuresMillis, null)
            }
            val inicioFeatures = clock()
            val features = frontend.logMel(frontend.slice(pcm, janela))
            featuresMillis += (clock() - inicioFeatures) / 1_000_000
            val generation = generator.generate(backend, features, isCancelled)
            results += WhisperOrtWindowResult(janela, generation)
            if (generation.stopReason == WhisperOrtStopReason.CANCELLED) {
                // Preserva o texto ja produzido: cancelar nao apaga fala.
                val parcial = WhisperOrtTextMerge.mergeAll(results.map { it.generation.text })
                return assemble(pcm, results, WhisperOrtOutcome.CANCELLED, parcial, startedAt, featuresMillis, null)
            }
            if (generation.stopReason == WhisperOrtStopReason.ERROR) {
                val parcial = WhisperOrtTextMerge.mergeAll(results.map { it.generation.text })
                return assemble(pcm, results, WhisperOrtOutcome.ERROR, parcial, startedAt, featuresMillis, generation.error)
            }
        }
        val texto = WhisperOrtTextMerge.mergeAll(results.map { it.generation.text })
        val desfecho = when {
            results.isEmpty() -> WhisperOrtOutcome.NO_SPEECH
            results.any { it.generation.stopReason == WhisperOrtStopReason.MAX_TOKENS } -> WhisperOrtOutcome.PARTIAL
            texto.isBlank() -> WhisperOrtOutcome.NO_SPEECH
            else -> WhisperOrtOutcome.SUCCESS
        }
        return assemble(pcm, results, desfecho, texto, startedAt, featuresMillis, null)
    }

    private fun assemble(
        pcm: FloatArray,
        results: List<WhisperOrtWindowResult>,
        outcome: WhisperOrtOutcome,
        joinedText: String,
        startedAt: Long,
        featuresMillis: Long,
        error: String?,
    ): WhisperOrtTranscript {
        // Coerência: fala produzida e montagem vazia é falha de junção, não
        // "sem fala". Preserva o texto das janelas e marca ERROR.
        var finalOutcome = outcome
        var finalError = error
        var finalText = joinedText
        if (finalText.isBlank() && results.any { it.generation.text.isNotBlank() }) {
            finalOutcome = WhisperOrtOutcome.ERROR
            finalError = "juncao devolveu texto vazio com fala produzida nas janelas"
            finalText = WhisperOrtTextMerge.mergeAll(results.map { it.generation.text })
        }
        return WhisperOrtTranscript(
            text = finalText,
            windows = results,
            outcome = finalOutcome,
            stop = results.lastOrNull()?.generation?.stopReason ?: WhisperOrtStopReason.ERROR,
            totalMillis = (clock() - startedAt) / 1_000_000,
            featuresMillis = featuresMillis,
            audioMillis = (pcm.size.toLong() * 1000) / WhisperOrtPcm.SAMPLE_RATE,
            plannedWindows = results.size,
            error = finalError,
        )
    }

    companion object {
        /** Passo default do caminho legado (26 s = 4 s de sobreposição). */
        const val DEFAULT_HOP_SAMPLES = 26 * 16_000

        /** Tentativas de avanço sem progresso antes de encerrar em PARCIAL. */
        const val MAX_SEM_PROGRESSO = 2
    }
}

/** Resultado de um job de transcrição ORT, com o que o relatório precisa. */
data class WhisperOrtTranscript(
    val text: String,
    val windows: List<WhisperOrtWindowResult>,
    val outcome: WhisperOrtOutcome,
    val stop: WhisperOrtStopReason,
    val totalMillis: Long,
    val featuresMillis: Long = 0,
    val audioMillis: Long = 0,
    val plannedWindows: Int = 0,
    val error: String? = null,
) {
    /** RTF = tempo de parede / tempo de áudio. */
    val realTimeFactor: Double get() = if (audioMillis <= 0) 0.0 else totalMillis.toDouble() / audioMillis
}

/** Terminal de uma transcrição ORT. */
enum class WhisperOrtOutcome { SUCCESS, NO_SPEECH, PARTIAL, CANCELLED, ERROR, NO_AUDIO }

/**
 * Uma janela processada.
 *
 * [retrieval] guarda segmentos/seek/pendência do caminho temporal — é o trace que
 * permite reexecutar a montagem sem novo modelo (adendo8 §5.4).
 */
data class WhisperOrtWindowResult(
    val window: WhisperOrtWindow,
    val generation: WhisperOrtGeneration,
    val retrieval: WhisperOrtSegments.Retrieval? = null,
    val attempts: Int = 1,
    val firstFault: WhisperOrtFault? = null,
)
