package br.gov.sp.pcsp.launcher

/**
 * Prompt inicial do decoder Whisper e vocabulário de controle (ORT1-D02).
 *
 * O prefixo é `[<|startoftranscript|>, <|idioma|>, <|transcribe|>, <|notimestamps|>]`.
 * Os ids NÃO são inventados: vêm do `generation_config.json` /
 * `inspecao-candidato.json` do export congelado
 * (`onnx-community/whisper-large-v3-turbo` @ `360ebcde...`), confirmados por
 * teste. Um export diferente pode renumerar; por isso [portuguese] recebe os ids
 * por parâmetro no caminho do motor e o default só vale para este export.
 *
 * Auto-idioma NÃO está implementado nesta rodada: [autoDetect] não existe de
 * propósito. Chamar [WhisperOrtGenerationPolicy] com `prompt` sem idioma geraria
 * texto com o token errado — a política rejeita prefixo sem token de idioma.
 *
 * Seam puro: constantes e funções sem Android.
 */
object WhisperOrtPrompt {

    /** `<|startoftranscript|>` — abre a geração. */
    const val START_OF_TRANSCRIPT = 50_258

    /** `<|endoftext|>` — encerra a geração (também é o eos do modelo). */
    const val END_OF_TEXT = 50_257

    /** `<|translate|>` — tarefa de tradução para inglês (não usada aqui). */
    const val TRANSLATE = 50_359

    /** `<|transcribe|>` — tarefa de transcrição no idioma de origem. */
    const val TRANSCRIBE = 50_360

    /** `<|notimestamps|>` — desliga tokens de timestamp na saída. */
    const val NO_TIMESTAMPS = 50_364

    /** `<|pt|>` — português (índice 8 da lista de idiomas do Whisper). */
    const val LANGUAGE_PT = 50_267

    /** Primeiro id de token de idioma (`<|en|>`). */
    const val LANGUAGE_FIRST = 50_259

    /** Último id de token de idioma (`<|su|>`, 99 idiomas). */
    const val LANGUAGE_LAST = 50_357

    /** `<|nospeech|>` — token de "não há fala" (guard ainda não implementado). */
    const val NO_SPEECH = 50_362

    /** Prefixo desta rodada: português, transcrever, sem timestamps. */
    fun portuguese(): IntArray = intArrayOf(START_OF_TRANSCRIPT, LANGUAGE_PT, TRANSCRIBE, NO_TIMESTAMPS)

    /**
     * Prefixo do MODO TEMPORAL: sem `<|notimestamps|>`.
     *
     * Com o token ausente o modelo emite `<|t|>` a cada 0,02 s — é o que permite
     * juntar janelas por TEMPO em vez de por semelhança de texto.
     */
    fun portugueseWithTimestamps(): IntArray =
        intArrayOf(START_OF_TRANSCRIPT, LANGUAGE_PT, TRANSCRIBE)

    /** Prefixo temporal para um id de idioma já resolvido. */
    fun forLanguageWithTimestamps(languageTokenId: Int): IntArray {
        require(languageTokenId in LANGUAGE_FIRST..LANGUAGE_LAST) {
            "id de idioma fora da faixa: $languageTokenId"
        }
        return intArrayOf(START_OF_TRANSCRIPT, languageTokenId, TRANSCRIBE)
    }

    /** Prefixo para um id de idioma já resolvido. */
    fun forLanguage(languageTokenId: Int, translate: Boolean = false): IntArray {
        require(languageTokenId in LANGUAGE_FIRST..LANGUAGE_LAST) {
            "id de idioma fora da faixa: $languageTokenId"
        }
        return intArrayOf(
            START_OF_TRANSCRIPT,
            languageTokenId,
            if (translate) TRANSLATE else TRANSCRIBE,
            NO_TIMESTAMPS,
        )
    }

    /**
     * Valida um prefixo arbitrário antes de gastar decoder.
     *
     * Devolve `null` quando aceitável ou a razão textual da recusa. Regras:
     * começa em `<|startoftranscript|>`, segundo token é idioma, terceiro é
     * tarefa, quarto é `<|notimestamps|>` (este motor não produz timestamps) e
     * nenhum id desconhecido entra no grafo.
     */
    fun validate(prompt: IntArray, vocabSize: Int): String? = when {
        prompt.size < 3 -> "prefixo curto (${prompt.size} < 3): sem idioma/tarefa explícitos"
        prompt[0] != START_OF_TRANSCRIPT -> "prefixo não começa em <|startoftranscript|>"
        prompt[1] !in LANGUAGE_FIRST..LANGUAGE_LAST -> "segundo token não é idioma (${prompt[1]})"
        prompt[2] != TRANSCRIBE && prompt[2] != TRANSLATE -> "terceiro token não é tarefa (${prompt[2]})"
        // 3 tokens = modo temporal; com 4, o quarto tem de ser <|notimestamps|>.
        prompt.size > 3 && prompt[3] != NO_TIMESTAMPS ->
            "quarto token não é <|notimestamps|> (${prompt[3]})"
        prompt.any { it < 0 || it >= vocabSize } -> "prefixo tem id fora do vocabulário ($vocabSize)"
        else -> null
    }
}
