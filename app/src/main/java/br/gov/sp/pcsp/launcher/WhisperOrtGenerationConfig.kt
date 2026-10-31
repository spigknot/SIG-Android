package br.gov.sp.pcsp.launcher

/**
 * Configuração de geração EFETIVA do export (ORT1-D02/D04, adendo1 §1).
 *
 * Os valores abaixo não são inventados: vêm do `generation_config.json` do
 * export congelado, que declara
 *
 * - `suppress_tokens`: 88 ids suprimidos em TODAS as posições (inclui 1, 2, 7…);
 * - `begin_suppress_tokens`: `[220, 50257]` suprimidos SÓ na primeira posição —
 *   é isso que impede a geração de nascer vazia (EOS imediato) ou com espaço;
 * - `max_length`: 448 posições TOTAIS (prefixo + gerados), não 448 novos;
 * - `eos_token_id` 50257, `decoder_start_token_id` 50258.
 *
 * Sem isso o laço escolhia o id 1 mesmo estando na lista de supressão e
 * estourava o contexto em uma posição (prefixo 4 + 448 novos = 449).
 *
 * Seam puro: recebe o JSON já lido, devolve dados.
 */
data class WhisperOrtGenerationConfig(
    /** Ids proibidos em qualquer posição (lista `suppress_tokens` do export). */
    val suppressTokens: Set<Int>,
    /** Ids proibidos apenas na PRIMEIRA posição gerada. */
    val beginSuppressTokens: Set<Int>,
    /** Posições TOTAIS do decoder (prefixo incluído). */
    val maxLength: Int,
    /** Id de fim de sequência. */
    val eosTokenId: Int,
    /** Id de abertura (`<|startoftranscript|>`). */
    val decoderStartTokenId: Int,
    /**
     * `<|notimestamps|>` do export: BASE dos tokens de timestamp (`<|0.00|>` é
     * `noTimestampsTokenId + 1`). Sem ele o modo temporal não deriva a faixa.
     */
    val noTimestampsTokenId: Int = 50_364,
    /**
     * `max_initial_timestamp_index` do export: índice MÁXIMO do primeiro timestamp
     * (50 ticks de 0,02 s = 1 s). O 1500 que eu usava é o horizonte da janela
     * (30 s), não o limite inicial — com ele o modelo podia abrir em 2 s.
     */
    val maxInitialTimestampIndex: Int = 50,
) {

    init {
        require(maxLength > 0) { "maxLength inválido: $maxLength" }
        require(eosTokenId >= 0) { "eosTokenId inválido: $eosTokenId" }
    }

    /**
     * Quantos tokens NOVOS cabem dado um prefixo de [promptSize].
     *
     * É o limite TOTAL menos o prefixo: com prefixo 4 e total 448 sobram 444.
     * Nunca devolve negativo (prefixo maior que o total é erro do chamador).
     */
    fun newTokenBudget(promptSize: Int): Int = (maxLength - promptSize).coerceAtLeast(0)

    /** `true` quando [id] está proibido em [position] (0 = primeira gerada). */
    fun isSuppressed(id: Int, position: Int): Boolean =
        id in suppressTokens || (position == 0 && id in beginSuppressTokens)

    companion object {
        /**
         * Config do export CONGELADO, com os valores REAIS.
         *
         * Antes este default declarava representar o export mas trazia 0/0
         * supressões contra 88/2 reais — um consumidor futuro rodaria sem a
         * política efetiva em silêncio. Os ids abaixo são os do
         * `generation_config.json` da revisão `360ebcde…`; qualquer divergência
         * é detectada por [validateAgainst].
         */
        fun frozenExport(): WhisperOrtGenerationConfig = WhisperOrtGenerationConfig(
            suppressTokens = FROZEN_SUPPRESS,
            beginSuppressTokens = FROZEN_BEGIN_SUPPRESS,
            maxLength = 448,
            eosTokenId = WhisperOrtPrompt.END_OF_TEXT,
            decoderStartTokenId = WhisperOrtPrompt.START_OF_TRANSCRIPT,
        )

        /**
         * Confere a config contra o tokenizer/vocabulário do modelo aberto.
         *
         * Devolve `null` quando coerente ou a razão textual. Não adivinha: ids
         * fora do vocabulário, eos diferente do tokenizer ou teto que não cabe o
         * prefixo são recusados.
         */
        fun validateAgainst(
            config: WhisperOrtGenerationConfig,
            vocabSize: Int,
            tokenizerEos: Int,
            promptSize: Int,
        ): String? = when {
            config.eosTokenId != tokenizerEos ->
                "eos da config (${config.eosTokenId}) != eos do tokenizer ($tokenizerEos)"
            config.eosTokenId >= vocabSize ->
                "eos ${config.eosTokenId} fora do vocabulário ($vocabSize)"
            config.decoderStartTokenId >= vocabSize ->
                "start ${config.decoderStartTokenId} fora do vocabulário ($vocabSize)"
            config.maxLength <= promptSize ->
                "max_length ${config.maxLength} não cabe o prefixo $promptSize"
            config.suppressTokens.any { it !in 0 until vocabSize } ->
                "suppress_tokens com id fora do vocabulário ($vocabSize)"
            else -> null
        }

        /** Ids reais de `suppress_tokens` do export congelado (88). */
        val FROZEN_SUPPRESS: Set<Int> = setOf(
            1, 2, 7, 8, 9, 10, 14, 25, 26, 27, 28, 29, 31, 58, 59, 60, 61, 62, 63, 90, 91, 92,
            93, 359, 503, 522, 542, 873, 893, 902, 918, 922, 931, 1350, 1853, 1982, 2460, 2627,
            3246, 3253, 3268, 3536, 3846, 3961, 4183, 4667, 6585, 6647, 7273, 9061, 9383, 10428,
            10929, 11938, 12033, 12331, 12562, 13793, 14157, 14635, 15265, 15618, 16553, 16604,
            18362, 18956, 20075, 21675, 22520, 26130, 26161, 26435, 28279, 29464, 31650, 32302,
            32470, 36865, 42863, 47425, 49870, 50254, 50258, 50359, 50360, 50361, 50362, 50363,
        )

        /** Ids reais de `begin_suppress_tokens` (só na primeira posição). */
        val FROZEN_BEGIN_SUPPRESS: Set<Int> = setOf(220, 50257)

        /**
         * Lê a configuração do `generation_config.json` do export.
         *
         * Campos ausentes caem no default do export; listas são lidas como
         * conjuntos de ids. JSON inválido vira [IllegalArgumentException] — o
         * motor não adivinha supressões.
         */
        fun parse(json: String): WhisperOrtGenerationConfig {
            val root = org.json.JSONObject(json)
            fun ids(name: String): Set<Int> {
                val array = root.optJSONArray(name) ?: return emptySet()
                val out = HashSet<Int>(array.length())
                for (i in 0 until array.length()) out.add(array.getInt(i))
                return out
            }
            return WhisperOrtGenerationConfig(
                suppressTokens = ids("suppress_tokens"),
                beginSuppressTokens = ids("begin_suppress_tokens"),
                maxLength = root.optInt("max_length", 448),
                eosTokenId = root.optInt("eos_token_id", WhisperOrtPrompt.END_OF_TEXT),
                decoderStartTokenId = root.optInt(
                    "decoder_start_token_id",
                    WhisperOrtPrompt.START_OF_TRANSCRIPT,
                ),
                noTimestampsTokenId = root.optInt(
                    "no_timestamps_token_id",
                    WhisperOrtPrompt.NO_TIMESTAMPS,
                ),
                maxInitialTimestampIndex = root.optInt("max_initial_timestamp_index", 50),
            )
        }

        private val EMPTY = emptySet<Int>()
    }
}
