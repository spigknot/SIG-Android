package br.gov.sp.pcsp.launcher

/**
 * Laço de geração greedy do Whisper sobre um backend injetável
 * (ORT1-D03/D04/D06, corrigido no adendo1 §1).
 *
 * Regras que a revisão independente reprovou e que agora são explícitas:
 *
 * 1. **Domínio de logits exato**: o vetor tem de ter `vocabSize` posições. Um
 *    vetor de 2 valores era aceito por `minOf` e virava texto silenciosamente —
 *    agora é erro de contrato.
 * 2. **Não finitos**: `NaN` e `+Inf` em qualquer posição são recusados ANTES de
 *    escolher. `-Inf` é permitido porque é a máscara legítima da política; `+Inf`
 *    cru do modelo é defeito e não pode virar "EOS confiante".
 * 3. **Orçamento TOTAL de posições**: `max_length` (448) inclui o prefixo. O
 *    cálculo antigo (prefixo 4 + 448 novos) chegava a 449 e pedia um passo extra
 *    depois do teto.
 * 4. **Supressões do export por posição**: `suppress_tokens` vale sempre e
 *    `begin_suppress_tokens` só na primeira posição (é o que impede nascer vazio).
 * 5. **Cancelamento antes das etapas caras**: um cancel já conhecido não roda
 *    encoder nem inicializa o decoder.
 *
 * Classificação de falha: [WhisperOrtRuntimeFault] (erro de runtime do ORT) é
 * RECUPERÁVEL; shape, não finito, config e cancelamento são definitivos e nunca
 * viram retry.
 *
 * Seam puro: nenhuma dependência de Android, nenhum tensor.
 */
private const val MAX_INITIAL_TICKS = 1_500

class WhisperOrtGenerator(
    private val tokenizer: WhisperOrtTokenizer,
    private val policy: WhisperOrtGenerationPolicy = WhisperOrtGenerationPolicy(),
    private val clock: () -> Long = { System.nanoTime() },
) {

    init {
        val problem = WhisperOrtPrompt.validate(policy.prompt, tokenizer.vocabSize)
        require(problem == null) { "prefixo inválido: $problem" }
        // A config não é aplicada "quase toda": eos/start/teto/ids têm de bater
        // com o tokenizer e com o prefixo, senão o motor roda com política
        // divergente da do export.
        val incoerencia = WhisperOrtGenerationConfig.validateAgainst(
            policy.config,
            tokenizer.vocabSize,
            tokenizer.eosTokenId,
            policy.prompt.size,
        )
        require(incoerencia == null) { "config incoerente: $incoerencia" }
    }

    /**
     * Gera o texto de uma janela de 30 s já convertida para log-mel.
     *
     * [isCancelled] é consultado ANTES do encoder e entre tokens: cancelamento
     * nunca inventa texto e nunca paga compute já sabidamente indesejado.
     */
    fun generate(
        backend: WhisperOrtBackend,
        logMel: FloatArray,
        isCancelled: () -> Boolean = { false },
    ): WhisperOrtGeneration {
        val startedAt = clock()
        // 1) cancelamento ANTES de qualquer etapa cara
        if (isCancelled()) {
            return failure(WhisperOrtStopReason.CANCELLED, null, 0, startedAt, WhisperOrtFault.Cancelled)
        }
        val budget = policy.config.newTokenBudget(policy.prompt.size)
        if (budget <= 0) {
            return failure(
                WhisperOrtStopReason.ERROR, null, 0, startedAt,
                WhisperOrtFault.Config("orçamento de tokens zero (prefixo ${policy.prompt.size} >= max_length ${policy.config.maxLength})"),
            )
        }
        val tokens = ArrayList<Int>(budget)
        var firstTokenAt = 0L
        try {
            val hidden = backend.encode(logMel)
            // 2) cancelamento DEPOIS do encoder (etapa mais cara)
            if (isCancelled()) {
                return failure(WhisperOrtStopReason.CANCELLED, null, 0, startedAt, WhisperOrtFault.Cancelled)
            }
            var logits = backend.start(hidden, policy.prompt)
            var ultimoTimestamp: Int? = null
        // A referência trata `len(seq) < 2` como penúltimo = timestamp; iniciar em
        // `false` invertia o ramo na PRIMEIRA decisão (exigia timestamp onde a regra
        // exige texto) e o laço caía direto em EOS.
        var penultimoEraTimestamp = true
            var anteriorEraTimestamp = false
            val baseTs = WhisperOrtSegments.timestampBegin(policy.config.noTimestampsTokenId)
            while (true) {
                if (isCancelled()) {
                    return finish(tokens, WhisperOrtStopReason.CANCELLED, firstTokenAt, startedAt, WhisperOrtFault.Cancelled)
                }
                // 3) teto TOTAL: nenhum passo extra depois de completar o orçamento
                if (tokens.size >= budget) {
                    return finish(tokens, WhisperOrtStopReason.MAX_TOKENS, firstTokenAt, startedAt, null)
                }
                val next = choose(
                    logits,
                    position = tokens.size,
                    ultimoTimestamp = ultimoTimestamp,
                    // Derivados da PRÓPRIA sequência, como a referência faz:
                    //   last    = seq[-1] >= begin
                    //   penult  = len(seq) < 2 or seq[-2] >= begin
                    anteriorEraTimestamp = tokens.isNotEmpty() && tokens.last() >= baseTs,
                    penultimoEraTimestamp = tokens.size < 2 || tokens[tokens.size - 2] >= baseTs,
                ) ?: return finish(
                    tokens, WhisperOrtStopReason.ERROR, firstTokenAt, startedAt,
                    WhisperOrtFault.Invalid("sem candidato válido (logits suprimidos)"),
                )
                if (next == tokenizer.eosTokenId) {
                    return finish(tokens, WhisperOrtStopReason.EOS, firstTokenAt, startedAt, null)
                }
                if (tokens.isEmpty()) firstTokenAt = clock()
                tokens.add(next)
                if (policy.timestampMode) {
                    val ehTs = WhisperOrtSegments.isTimestamp(
                        next, policy.config.noTimestampsTokenId, tokenizer.vocabSize,
                    )
                    if (ehTs) ultimoTimestamp = next
                    // A regra de PARES da referência olha os DOIS últimos tokens:
                    // ts,ts -> texto; texto,ts -> timestamp (fecha o par).
                    penultimoEraTimestamp = anteriorEraTimestamp
                    anteriorEraTimestamp = ehTs
                }
                // 4) no teto, NÃO executar passo extra
                if (tokens.size >= budget) {
                    return finish(tokens, WhisperOrtStopReason.MAX_TOKENS, firstTokenAt, startedAt, null)
                }
                logits = backend.step(next)
            }
        } catch (runtime: WhisperOrtRuntimeFault) {
            // Sem classe recuperável DEMONSTRADA e segura na arquitetura de 3
            // sessões, falha de runtime é TERMINAL: não há retry genérico nem
            // reabertura de sessão. O fault preserva código e causa real.
            return finish(
                tokens, WhisperOrtStopReason.ERROR, firstTokenAt, startedAt,
                WhisperOrtFault.Runtime(runtime.describe(), runtime),
            )
        } catch (invalid: WhisperOrtInputFault) {
            return finish(
                tokens, WhisperOrtStopReason.ERROR, firstTokenAt, startedAt,
                WhisperOrtFault.Invalid(invalid.message ?: "entrada inválida"),
            )
        } catch (invalid: InvalidInput) {
            return finish(
                tokens, WhisperOrtStopReason.ERROR, firstTokenAt, startedAt,
                WhisperOrtFault.Invalid(invalid.message ?: "entrada inválida"),
            )
        } catch (failure: Throwable) {
            return finish(
                tokens, WhisperOrtStopReason.ERROR, firstTokenAt, startedAt,
                WhisperOrtFault.Invalid("${failure.javaClass.simpleName}: ${failure.message}"),
            )
        }
    }

    /**
     * Escolhe o próximo token, validando o domínio antes de comparar.
     *
     * Devolve `null` quando não há candidato elegível — o chamador transforma em
     * ERROR com motivo, nunca em texto vazio silencioso.
     */
    private fun choose(
        logits: FloatArray,
        position: Int,
        ultimoTimestamp: Int? = null,
        anteriorEraTimestamp: Boolean = false,
        penultimoEraTimestamp: Boolean = false,
    ): Int? {
        if (logits.size != tokenizer.vocabSize) {
            throw InvalidInput(
                "logits com ${logits.size} posições != vocabulário ${tokenizer.vocabSize}",
            )
        }
        // Não finito inválido: NaN e +Inf são defeito do modelo. -Inf é máscara.
        for (i in logits.indices) {
            val value = logits[i]
            if (value.isNaN() || value == Float.POSITIVE_INFINITY) {
                throw InvalidInput("logit não finito na posição $i ($value)")
            }
        }
        var best = -1
        var bestValue = Float.NEGATIVE_INFINITY
        // Massa temporal (referência): avaliada DEPOIS de todas as máscaras —
        // só entram timestamps ELEGÍVEIS — e comparada ao melhor TEXTO elegível.
        var melhorTs = -1
        var melhorTsLogit = Float.NEGATIVE_INFINITY
        var melhorTextoLogit = Float.NEGATIVE_INFINITY
        var somaExpTs = 0.0
        val primeiroTs = WhisperOrtSegments.timestampBegin(policy.config.noTimestampsTokenId)
        for (id in 0 until tokenizer.vocabSize) {
            if (policy.isSuppressed(id, position, tokenizer.eosTokenId)) continue
            if (policy.timestampMode &&
                !timestampAllowed(
                    id, position, primeiroTs, ultimoTimestamp,
                    anteriorEraTimestamp, penultimoEraTimestamp,
                )
            ) {
                continue
            }
            val value = logits[id]
            if (value > bestValue) {
                bestValue = value
                best = id
            }
            if (policy.timestampMode) {
                if (id >= primeiroTs && value != Float.NEGATIVE_INFINITY) {
                    if (value > melhorTsLogit) {
                        // Mantem a soma relativa ao maior logit ja visto. A
                        // diferenca em Double evita overflow antes do exp.
                        somaExpTs = somaExpTs * kotlin.math.exp(
                            melhorTsLogit.toDouble() - value.toDouble(),
                        ) + 1.0
                        melhorTsLogit = value
                        melhorTs = id
                    } else {
                        somaExpTs += kotlin.math.exp(value.toDouble() - melhorTsLogit.toDouble())
                    }
                } else if (id < primeiroTs && value > melhorTextoLogit) {
                    // A referencia inclui EOS entre os candidatos nao timestamp.
                    melhorTextoLogit = value
                }
            }
        }
        if (policy.timestampMode && melhorTs >= 0) {
            // Compara no mesmo referencial, sem somar um deslocamento grande
            // ao logaritmo nem depender de uma constante fixa como 100.
            val textoRelativo = melhorTextoLogit.toDouble() - melhorTsLogit.toDouble()
            if (kotlin.math.ln(somaExpTs) > textoRelativo) return melhorTs
        }
        return if (best >= 0) best else null
    }

    /**
     * Regra temporal aplicada ANTES do argmax (referencia
     * WhisperTimeStampLogitsProcessor): posicao 0 exige timestamp; timestamp nao
     * retrocede; o primeiro nao passa do limite inicial da janela; EOS nao abre
     * segmento vazio; texto e livre fora disso.
     */
    /**
     * Regra temporal aplicada ANTES do argmax, fiel a
     * `WhisperTimeStampLogitsProcessor` (referência primária congelada).
     *
     * Regras implementadas:
     * 1. `<|notimestamps|>` nunca pode ser escolhido quando o modo é temporal;
     * 2. **pares**: depois de `texto,ts` o próximo tem de ser `ts` (fecha o par);
     *    depois de `ts,ts` o próximo tem de ser TEXTO (abre conteúdo);
     * 3. **progressão**: timestamp não retrocede — e logo após fechar um par o
     *    piso é `ultimo` (repetir o mesmo instante é permitido), nos demais casos
     *    `ultimo + 1` (a referência evita reemitir `<|0.00|>`);
     * 4. **limite inicial**: enquanto nenhum timestamp foi emitido, nenhum passa
     *    de `primeiro + maxInitialTimestampIndex`, e no passo 0 só timestamp vale.
     *
     * EOS continua elegível (a referência mascara `< eos`, não `<= eos`): bloquear
     * EOS foi regra inventada antes e travava a geração.
     */
    private fun timestampAllowed(
        id: Int,
        position: Int,
        primeiroTimestamp: Int,
        ultimoTimestamp: Int?,
        ultimoEraTimestamp: Boolean,
        penultimoEraTimestamp: Boolean,
    ): Boolean {
        if (id == policy.config.noTimestampsTokenId) return false
        val isTs = id >= primeiroTimestamp
        val ehEos = id == tokenizer.eosTokenId

        if (position == 0) {
            // Força e LIMITE no MESMO passo (antes o early return liberava qualquer
            // timestamp inicial, ignorando maxInitialTimestampIndex).
            if (!isTs) return false
            return id <= primeiroTimestamp + policy.config.maxInitialTimestampIndex
        }

        if (ultimoEraTimestamp) {
            if (penultimoEraTimestamp) {
                // ts,ts: agora tem de vir TEXTO (senão os pares nunca fecham).
                if (isTs) return false
            } else {
                // texto,ts: agora tem de vir TIMESTAMP. EOS segue permitido.
                if (!isTs && !ehEos) return false
            }
        }

        if (isTs) {
            val piso = if (ultimoEraTimestamp && !penultimoEraTimestamp && ultimoTimestamp != null) {
                // Fechando o par: repetir o instante é válido (ts,ts).
                ultimoTimestamp
            } else {
                (ultimoTimestamp ?: (primeiroTimestamp - 1)) + 1
            }
            if (id < piso) return false
            if (ultimoTimestamp == null &&
                id > primeiroTimestamp + policy.config.maxInitialTimestampIndex
            ) {
                return false
            }
        }
        return true
    }

    private fun finish(
        tokens: List<Int>,
        stop: WhisperOrtStopReason,
        firstTokenAt: Long,
        startedAt: Long,
        fault: WhisperOrtFault?,
    ): WhisperOrtGeneration {
        val finishedAt = clock()
        return WhisperOrtGeneration(
            text = tokenizer.decode(tokens.toIntArray()),
            tokens = tokens.toList(),
            stopReason = stop,
            steps = tokens.size,
            firstTokenMillis = if (firstTokenAt == 0L) -1 else (firstTokenAt - startedAt) / 1_000_000,
            totalMillis = (finishedAt - startedAt) / 1_000_000,
            fault = fault,
        )
    }

    private fun failure(
        stop: WhisperOrtStopReason,
        tokens: List<Int>?,
        steps: Int,
        startedAt: Long,
        fault: WhisperOrtFault,
    ): WhisperOrtGeneration = WhisperOrtGeneration(
        text = "",
        tokens = tokens ?: emptyList(),
        stopReason = stop,
        steps = steps,
        firstTokenMillis = -1,
        totalMillis = (clock() - startedAt) / 1_000_000,
        fault = fault,
    )
}

/**
 * Falha TIPADA da geração.
 *
 * A distinção importa: só [Runtime] pode ser repetida (o motor reabre a sessão);
 * [Invalid] (shape, não finito, contrato), [Config] e [Cancelled] nunca justificam
 * reabrir nada.
 */
sealed class WhisperOrtFault(val message: String) {
    /**
     * Falha de runtime do ORT (sessão/execução).
     *
     * Hoje é TERMINAL: não há classe recuperável demonstrada, então não existe
     * retry. O tipo permanece distinto para diagnóstico (não é entrada inválida)
     * e carrega a causa original.
     */
    class Runtime(message: String, val cause: Throwable? = null) : WhisperOrtFault(message)

    /** Entrada/modelo inválidos (shape, não finito, contrato) — definitivo. */
    class Invalid(message: String) : WhisperOrtFault(message)

    /** Configuração inconsistente — definitivo. */
    class Config(message: String) : WhisperOrtFault(message)

    /** Cancelamento observado — não é erro. */
    object Cancelled : WhisperOrtFault("cancelado")
}

/**
 * Erro de runtime do ONNX Runtime, propagado pelo backend.
 *
 * Existe como tipo próprio para que o laço NÃO confunda falha recuperável de
 * sessão com entrada inválida — a revisão reprovou reabertura cega para
 * qualquer ERROR.
 */
class WhisperOrtRuntimeFault(
    /** Código do ORT (`OrtErrorCode.name`) quando houver. */
    val code: String,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    /** Descrição estável para diagnóstico: código + mensagem. */
    fun describe(): String = "$code: ${message ?: "sem mensagem"}"
}

/**
 * Falha DEFINITIVA vinda do runtime (argumento/grafo/shape/OOM).
 *
 * Separada de [WhisperOrtRuntimeFault] porque NÃO admite nova tentativa: reabrir
 * sessão para dado inválido só gastaria tempo e esconderia o defeito.
 */
class WhisperOrtInputFault(message: String) : RuntimeException(message)

/**
 * Entrada inválida dentro do laço (shape de logits, valor não finito).
 *
 * É tipo PRIVADO de propósito: só o laço a lança, e ela nunca pode ser
 * confundida com falha de runtime — o retry não deve reabrir sessão para dado
 * inválido.
 */
private class InvalidInput(message: String) : RuntimeException(message)

/** Fronteira de inferência consumida por [WhisperOrtGenerator]. */
interface WhisperOrtBackend : AutoCloseable {
    /** Roda o encoder e devolve o hidden state achatado. */
    fun encode(logMel: FloatArray): FloatArray

    /** Abre o decoder com [prompt] e devolve os logits da última posição. */
    fun start(hidden: FloatArray, prompt: IntArray): FloatArray

    /** Consome [token] no cache corrente e devolve os logits do próximo. */
    fun step(token: Int): FloatArray

    /** Libera sessões/tensores do backend. */
    override fun close()

    /**
     * Reabre as sessões depois de uma falha de runtime.
     *
     * O default é `false`: um backend estático não finge recuperação. Só é
     * chamado pelo dono da transcrição, e só para falha classificada como
     * recuperável e com cancelamento não observado.
     */
    fun reopen(): Boolean = false
}

/**
 * Parâmetros efetivos do laço greedy.
 *
 * O default carrega a configuração do export congelado (supressões reais e
 * `max_length` 448 total). [suppressFrom] continua sendo a faixa de controle
 * (idioma/tarefa/timestamp) que este motor não produz.
 */
data class WhisperOrtGenerationPolicy(
    /** Prefixo obrigatório: idioma + tarefa + `<|notimestamps|>` explícitos. */
    val prompt: IntArray = WhisperOrtPrompt.portuguese(),
    /** Configuração efetiva do export (supressões e teto total). */
    val config: WhisperOrtGenerationConfig = WhisperOrtGenerationConfig.frozenExport(),
    /** A partir deste id, o token é de controle e não pode ser escolhido. */
    val suppressFrom: Int = WhisperOrtPrompt.START_OF_TRANSCRIPT,
    /**
     * Modo TEMPORAL: libera os tokens `<|t|>` (>= noTimestamps+1) para que a
     * junção entre janelas seja por TEMPO. O prefixo correspondente tem 3 tokens
     * (sem `<|notimestamps|>`).
     */
    val timestampMode: Boolean = false,
) {
    init {
        require(suppressFrom in 0..51866) { "suppressFrom inválido: $suppressFrom" }
    }

    /**
     * `true` quando [id] não pode ser escolhido na posição [position].
     *
     * A CONFIG é consultada primeiro: ela é quem diz que 50257 está proibido na
     * posição 0 (`begin_suppress_tokens`). A isenção do EOS vale só para a faixa
     * de controle — antes ela vinha antes da config e o EOS ganhava a primeira
     * posição, devolvendo texto vazio apesar de `config_suppressed=true`.
     */
    fun isSuppressed(id: Int, position: Int, eosTokenId: Int): Boolean {
        if (config.isSuppressed(id, position)) return true
        if (id == eosTokenId) return false
        if (timestampMode) {
            // Controle segue proibido (idioma/tarefa/no_timestamps); timestamp é
            // permitido a partir de <|0.00|>.
            val primeiroTimestamp = WhisperOrtSegments.timestampBegin(config.noTimestampsTokenId)
            if (id >= primeiroTimestamp) return false
            return id >= suppressFrom
        }
        return id >= suppressFrom
    }
}

/** Como a geração terminou. Só [EOS] significa transcrição integral. */
enum class WhisperOrtStopReason {
    /** O modelo emitiu eos: fala terminou naturalmente. */
    EOS,

    /** Bateu o teto de posições: texto é PARCIAL, nunca sucesso integral. */
    MAX_TOKENS,

    /** Cancelamento observado: parcial, terminal do job preservado. */
    CANCELLED,

    /** Falha de runtime do ORT: única condição que admite nova tentativa. */
    RECOVERABLE,

    /** Falha definitiva (shape, não finito, config, contrato): sem retry. */
    ERROR,
}

/** Resultado de uma janela de geração, com o que é auditável em relatório. */
data class WhisperOrtGeneration(
    val text: String,
    val tokens: List<Int>,
    val stopReason: WhisperOrtStopReason,
    val steps: Int,
    val firstTokenMillis: Long,
    val totalMillis: Long,
    val fault: WhisperOrtFault? = null,
) {
    /** `true` apenas quando a janela terminou em EOS. */
    val complete: Boolean get() = stopReason == WhisperOrtStopReason.EOS

    /** Motivo textual da falha, ou `null` quando não houve. */
    val error: String? get() = fault?.message
}
