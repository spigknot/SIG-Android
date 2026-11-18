package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Method

/**
 * R109/T02 — a busca otimizada do produto contra o ORÁCULO, em 200 000 vetores.
 *
 * O oráculo é a cópia fiel do `choose` de `9e75bf6` (passada de não finitos
 * separada, `policy.isSuppressed` escalar, mesma ordem de visita, mesmas
 * operações em `Double`, mesmo `>` e mesmo `ln`). O produto agora usa máscaras
 * `BooleanArray` e a recusa de não finito na MESMA passada.
 *
 * Contrato exigido pela ordem da R109: token, `null` e exceção IDÊNTICOS em
 * 100% dos casos. A comparação é sobre o resultado OBSERVÁVEL (token, nenhum
 * candidato, ou recusa com a mensagem que localiza a posição), porque a exceção
 * de recusa é privada do arquivo do produto.
 *
 * Casos cobertos por construção do gerador de vetores: empates exatos, `-Inf`
 * em massa, tudo `-Inf` (nenhum candidato), todos os candidatos suprimidos,
 * `NaN` e `+Inf` (na posição livre e numa posição SUPRIMIDA), a posição 0 e as
 * transições da regra temporal (pares ts/ts e texto/ts, piso que não retrocede,
 * limite inicial e massa temporal).
 */
class WhisperOrtBuscaR109Test {

    /** Resultado observável de uma escolha. */
    private sealed class Escolha {
        data class Token(val id: Int) : Escolha()
        object Nenhum : Escolha()
        data class Recusa(val mensagem: String) : Escolha()
    }

    // ------------------------------------------------------------------ oráculo

    /**
     * Cópia do `choose` de `9e75bf6`, com a recusa devolvida como [Escolha.Recusa]
     * em vez de exceção — o estado observável é o mesmo (o laço transforma a
     * exceção em `ERROR` com a mensagem).
     */
    private object Oraculo {

        fun choose(
            tokenizer: WhisperOrtTokenizer,
            policy: WhisperOrtGenerationPolicy,
            logits: FloatArray,
            position: Int,
            ultimoTimestamp: Int?,
            anteriorEraTimestamp: Boolean,
            penultimoEraTimestamp: Boolean,
        ): Escolha {
            if (logits.size != tokenizer.vocabSize) {
                return Escolha.Recusa(
                    "logits com ${logits.size} posições != vocabulário ${tokenizer.vocabSize}",
                )
            }
            for (i in logits.indices) {
                val value = logits[i]
                if (value.isNaN() || value == Float.POSITIVE_INFINITY) {
                    return Escolha.Recusa("logit não finito na posição $i ($value)")
                }
            }
            var best = -1
            var bestValue = Float.NEGATIVE_INFINITY
            var melhorTs = -1
            var melhorTsLogit = Float.NEGATIVE_INFINITY
            var melhorTextoLogit = Float.NEGATIVE_INFINITY
            var somaExpTs = 0.0
            val primeiroTs = WhisperOrtSegments.timestampBegin(policy.config.noTimestampsTokenId)
            for (id in 0 until tokenizer.vocabSize) {
                if (policy.isSuppressed(id, position, tokenizer.eosTokenId)) continue
                if (policy.timestampMode &&
                    !timestampAllowed(
                        policy, id, position, primeiroTs, ultimoTimestamp,
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
                            somaExpTs = somaExpTs * kotlin.math.exp(
                                melhorTsLogit.toDouble() - value.toDouble(),
                            ) + 1.0
                            melhorTsLogit = value
                            melhorTs = id
                        } else {
                            somaExpTs += kotlin.math.exp(value.toDouble() - melhorTsLogit.toDouble())
                        }
                    } else if (id < primeiroTs && value > melhorTextoLogit) {
                        melhorTextoLogit = value
                    }
                }
            }
            if (policy.timestampMode && melhorTs >= 0) {
                val textoRelativo = melhorTextoLogit.toDouble() - melhorTsLogit.toDouble()
                if (kotlin.math.ln(somaExpTs) > textoRelativo) return Escolha.Token(melhorTs)
            }
            return if (best >= 0) Escolha.Token(best) else Escolha.Nenhum
        }

        private fun timestampAllowed(
            policy: WhisperOrtGenerationPolicy,
            id: Int,
            position: Int,
            primeiroTimestamp: Int,
            ultimoTimestamp: Int?,
            ultimoEraTimestamp: Boolean,
            penultimoEraTimestamp: Boolean,
        ): Boolean {
            if (id == policy.config.noTimestampsTokenId) return false
            val isTs = id >= primeiroTimestamp
            val ehEos = id == policy.config.eosTokenId

            if (position == 0) {
                if (!isTs) return false
                return id <= primeiroTimestamp + policy.config.maxInitialTimestampIndex
            }

            if (ultimoEraTimestamp) {
                if (penultimoEraTimestamp) {
                    if (isTs) return false
                } else {
                    if (!isTs && !ehEos) return false
                }
            }

            if (isTs) {
                val piso = if (ultimoEraTimestamp && !penultimoEraTimestamp && ultimoTimestamp != null) {
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
    }

    // ------------------------------------------------------------- infraestrutura

    private val tokenizer: WhisperOrtTokenizer = WhisperOrtTokenizer.fromSubset(
        mapOf(
            "a" to 65,
            "\u0120" to 220,
            "z" to 51_865,
        ),
    )

    private val vocabSize: Int get() = tokenizer.vocabSize

    private fun politica(timestampMode: Boolean): WhisperOrtGenerationPolicy =
        WhisperOrtGenerationPolicy(
            prompt = if (timestampMode) WhisperOrtPrompt.portugueseWithTimestamps()
            else WhisperOrtPrompt.portuguese(),
            config = WhisperOrtGenerationConfig.frozenExport(),
            timestampMode = timestampMode,
        )

    /** `choose` privado do produto, por reflexão (custo fora do caminho quente). */
    private fun chooseDoProduto(): Method =
        WhisperOrtGenerator::class.java.declaredMethods
            .first { it.name == "choose" }
            .also { it.isAccessible = true }

    private fun escolherNoProduto(
        metodo: Method,
        gerador: WhisperOrtGenerator,
        logits: FloatArray,
        position: Int,
        ultimoTimestamp: Int?,
        anteriorEraTimestamp: Boolean,
        penultimoEraTimestamp: Boolean,
    ): Escolha = try {
        val token = metodo.invoke(
            gerador, logits, position, ultimoTimestamp, anteriorEraTimestamp, penultimoEraTimestamp,
        ) as Int?
        if (token == null) Escolha.Nenhum else Escolha.Token(token)
    } catch (e: java.lang.reflect.InvocationTargetException) {
        val causa = e.targetException
        assertEquals("tipo da recusa", "InvalidInput", causa.javaClass.simpleName)
        Escolha.Recusa(causa.message ?: "")
    }

    /** LCG determinístico: mesmo vetor em toda execução, sem depender de `Random`. */
    private class Gerador(seed: Long, private val vocab: Int, private val primeiroTs: Int) {
        private var estado = seed

        private fun proximoLong(): Long {
            estado = estado * 6364136223846793005L + 1442695040888963407L
            return estado
        }

        fun indice(limite: Int): Int = ((proximoLong() ushr 33).toInt() and 0x7FFFFFFF) % limite

        fun valor(): Float = ((proximoLong() ushr 40).toInt() - 8_388_608) / 524_288f

        /**
         * Preenche [alvo] com o caso `forma`. Devolve o índice que recebeu
         * `NaN`/`+Inf`, ou `-1`.
         */
        fun preencher(alvo: FloatArray, forma: Int): Int {
            val n = vocab
            when (forma) {
                0 -> java.util.Arrays.fill(alvo, valor())                     // empate exato
                1, 2 -> for (i in 0 until n) alvo[i] = valor()                 // aleatório
                3 -> for (i in 0 until n) alvo[i] = if (indice(10) < 3) Float.NEGATIVE_INFINITY else valor()
                4 -> {                                                         // quase tudo -Inf
                    java.util.Arrays.fill(alvo, Float.NEGATIVE_INFINITY)
                    repeat(5) { alvo[indice(n)] = valor() }
                }
                5 -> java.util.Arrays.fill(alvo, Float.NEGATIVE_INFINITY)      // nada elegível
                6, 7 -> {                                                      // empate no topo
                    java.util.Arrays.fill(alvo, -20f)
                    val topo = 1f + indice(3)
                    repeat(4) { alvo[indice(n)] = topo }
                }
                8 -> {                                                         // massa temporal forte
                    java.util.Arrays.fill(alvo, -20f)
                    val largura = 1 + indice(200)
                    for (i in 0 until largura) {
                        val id = primeiroTs + indice(vocab - primeiroTs)
                        if (id < n) alvo[id] = 8f + indice(4)
                    }
                }
                9 -> {                                                         // texto forte, ts fraco
                    java.util.Arrays.fill(alvo, -20f)
                    repeat(6) { alvo[indice(primeiroTs)] = 6f + indice(5) }
                    repeat(50) { alvo[primeiroTs + indice(n - primeiroTs)] = -8f }
                }
                10 -> {                                                        // NaN numa posição qualquer
                    for (i in 0 until n) alvo[i] = valor()
                    return indice(n)
                }
                11 -> {                                                        // +Inf numa posição qualquer
                    for (i in 0 until n) alvo[i] = valor()
                    return indice(n)
                }
                12 -> {                                                        // NaN numa posição SUPRIMIDA
                    for (i in 0 until n) alvo[i] = valor()
                    val base = WhisperOrtPrompt.START_OF_TRANSCRIPT
                    return base + indice(WhisperOrtPrompt.NO_TIMESTAMPS - base)
                }
                else -> {                                                      // misto com -Inf em massa
                    for (i in 0 until n) {
                        alvo[i] = if (indice(4) == 0) Float.NEGATIVE_INFINITY else valor()
                    }
                }
            }
            return -1
        }
    }

    // ------------------------------------------------------------------- testes

    @Test
    fun `busca do produto e identica ao oraculo em 200 mil vetores nos dois modos`() {
        val metodo = chooseDoProduto()
        val primeiroTs = WhisperOrtSegments.timestampBegin(
            WhisperOrtGenerationConfig.frozenExport().noTimestampsTokenId,
        )
        val porModo = 100_000
        val gerador = Gerador(20_261_118L, vocabSize, primeiroTs)
        val alvo = FloatArray(vocabSize)
        var total = 0L
        var tokens = 0L
        var nulos = 0L
        var recusas = 0L
        val inicioNs = System.nanoTime()

        for (modo in 0..1) {
            val timestampMode = modo == 1
            val policy = politica(timestampMode)
            val instancia = WhisperOrtGenerator(tokenizer, policy)
            for (iteracao in 0 until porModo) {
                val forma = gerador.indice(14)
                val veneno = gerador.preencher(alvo, forma)
                if (veneno >= 0) {
                    alvo[veneno] = if (forma == 11) Float.POSITIVE_INFINITY else Float.NaN
                }
                val position = when (iteracao % 7) {
                    0 -> 0
                    1 -> 1
                    2 -> 2
                    3 -> 3
                    4 -> 10
                    5 -> 440
                    else -> 51_866 - 1
                }
                val ultimoTimestamp: Int? = when (iteracao % 6) {
                    0 -> null
                    1 -> primeiroTs
                    2 -> primeiroTs + 5
                    3 -> primeiroTs + 100
                    4 -> primeiroTs + 1_490
                    else -> primeiroTs + 1_500
                }
                val anterior = iteracao % 2 == 0
                val penultimo = iteracao % 3 == 0

                val esperado = Oraculo.choose(
                    tokenizer, policy, alvo, position, ultimoTimestamp, anterior, penultimo,
                )
                val obtido = escolherNoProduto(
                    metodo, instancia, alvo, position, ultimoTimestamp, anterior, penultimo,
                )
                if (esperado != obtido) {
                    throw AssertionError(
                        "divergência: modo=$timestampMode iteração=$iteracao forma=$forma " +
                            "position=$position ultimoTs=$ultimoTimestamp anterior=$anterior " +
                            "penultimo=$penultimo esperado=$esperado obtido=$obtido",
                    )
                }
                when (obtido) {
                    is Escolha.Token -> tokens++
                    is Escolha.Nenhum -> nulos++
                    is Escolha.Recusa -> recusas++
                }
                total++
                if ((iteracao + 1) % 20_000 == 0) {
                    println("R109-T02: modo=${if (timestampMode) "temporal" else "sem-ts"} ${iteracao + 1}/$porModo")
                }
            }
        }

        val segundos = (System.nanoTime() - inicioNs) / 1e9
        println(
            "R109-T02: $total vetores, tokens=$tokens nenhum=$nulos recusas=$recusas em ${"%.1f".format(segundos)}s",
        )
        assertEquals("vetores comparados", 200_000L, total)
        // As três saídas têm de aparecer: um teste que só devolve token não
        // exercitou nem a recusa nem o "nenhum candidato".
        assertTrue("nenhuma recusa exercitada", recusas > 0)
        assertTrue("nenhum caso sem candidato exercitado", nulos > 0)
    }

    @Test
    fun `mascaras de supressao concordam com isSuppressed em todo id e posicao`() {
        for (timestampMode in listOf(false, true)) {
            val policy = politica(timestampMode)
            val instancia = WhisperOrtGenerator(tokenizer, policy)
            val base = campo(instancia, "suprimidoBase")
            val primeira = campo(instancia, "suprimidoPrimeira")
            assertEquals(vocabSize, base.size)
            assertEquals(vocabSize, primeira.size)
            for (id in 0 until vocabSize) {
                assertEquals(
                    "modo=$timestampMode id=$id pos=0",
                    policy.isSuppressed(id, 0, tokenizer.eosTokenId),
                    primeira[id],
                )
                for (position in intArrayOf(1, 2, 7, 444)) {
                    assertEquals(
                        "modo=$timestampMode id=$id pos=$position",
                        policy.isSuppressed(id, position, tokenizer.eosTokenId),
                        base[id],
                    )
                }
            }
            // A posição 0 só difere pela `begin_suppress_tokens`.
            var diferencas = 0
            for (id in 0 until vocabSize) if (base[id] != primeira[id]) diferencas++
            assertTrue(
                "begin_suppress_tokens nao acrescentou nada (modo=$timestampMode)",
                diferencas > 0,
            )
        }
    }

    @Test
    fun `eos e os timestamps continuam elegiveis onde a regra permite`() {
        val policy = politica(false)
        assertTrue(
            "EOS nao pode ser suprimido fora da posicao 0",
            !policy.isSuppressed(tokenizer.eosTokenId, 1, tokenizer.eosTokenId),
        )
        assertTrue(
            "EOS e suprimido na posicao 0 por begin_suppress_tokens",
            policy.isSuppressed(tokenizer.eosTokenId, 0, tokenizer.eosTokenId),
        )
        val temporal = politica(true)
        val primeiroTs = WhisperOrtSegments.timestampBegin(temporal.config.noTimestampsTokenId)
        assertTrue(
            "timestamp tem de ser elegivel no modo temporal",
            !temporal.isSuppressed(primeiroTs, 3, tokenizer.eosTokenId),
        )
        assertTrue(
            "no_timestamps continua proibido no modo temporal",
            temporal.isSuppressed(primeiroTs - 1, 3, tokenizer.eosTokenId),
        )
    }

    private fun campo(instancia: WhisperOrtGenerator, nome: String): BooleanArray {
        val campo = WhisperOrtGenerator::class.java.getDeclaredField(nome)
        campo.isAccessible = true
        return campo.get(instancia) as BooleanArray
    }
}
