package br.gov.sp.pcsp.launcher

/** Tradução de texto com o modelo Hy-MT2 (Tencent, 1.25 bit / Q4, llama.cpp local).
 *
 * Regra pura da ferramenta Texto: monta o prompt de instrução oficial do
 * model card do Hy-MT2 e o envolve no template de chat do modelo (tokens
 * especiais <｜hy_...｜>). A inferência acontece no aparelho via `HyMt2Native`;
 * aqui não há UI nem HTTP.
 */
object HyMt2Translator {

    /** Idioma alvo padrão da ferramenta (nome completo em inglês, exigência do model card). */
    const val DEFAULT_TARGET_LANGUAGE = "Portuguese"

    /** Um idioma de destino da ferramenta Texto: rótulo exibido x nome que entra no prompt. */
    data class TargetLanguage(val label: String, val promptName: String)

    /** Idiomas oferecidos pelo botão de idioma-alvo (ordem do menu).
     *
     * O model card exige o nome COMPLETO em inglês no prompt ("Translate the
     * following text into {promptName}"); o `label` é só o que o usuário vê. */
    val TARGET_LANGUAGES: List<TargetLanguage> = listOf(
        TargetLanguage("Português", "Portuguese"),
        TargetLanguage("Inglês", "English"),
        TargetLanguage("Espanhol", "Spanish"),
        TargetLanguage("Francês", "French"),
        TargetLanguage("Alemão", "German"),
        TargetLanguage("Italiano", "Italian"),
        TargetLanguage("Japonês", "Japanese"),
        TargetLanguage("Chinês", "Chinese"),
        TargetLanguage("Russo", "Russian"),
        TargetLanguage("Coreano", "Korean"),
        TargetLanguage("Árabe", "Arabic"),
        TargetLanguage("Hindi", "Hindi"),
        TargetLanguage("Holandês", "Dutch"),
        TargetLanguage("Turco", "Turkish"),
        TargetLanguage("Polonês", "Polish"),
        TargetLanguage("Sueco", "Swedish")
    )

    /** Converte o rótulo persistido/exibido no nome que o prompt precisa;
     *  rótulo desconhecido cai no padrão (Portuguese). */
    fun promptNameFor(label: String): String =
        TARGET_LANGUAGES.firstOrNull { it.label == label }?.promptName ?: DEFAULT_TARGET_LANGUAGE

    /** Parâmetros de amostragem recomendados para o 1.8B/7B (model card). */
    const val MAX_TOKENS = 4096
    const val TEMPERATURE = 0.7f
    const val TOP_P = 0.6f
    const val TOP_K = 20
    const val REPEAT_PENALTY = 1.05f

    /** Prompt de tradução padrão do model card (Default Translation, prompt em inglês).
     *
     * O texto de origem entra depois da instrução, separado por linha em branco
     * (mesmo formato do exemplo `transformers` oficial). O modelo detecta o
     * idioma de origem sozinho e devolve APENAS a tradução. */
    fun buildUserPrompt(
        sourceText: String,
        targetLanguage: String = DEFAULT_TARGET_LANGUAGE
    ): String = "Translate the following text into $targetLanguage. Note that you should " +
        "only output the translated result without any additional explanation:\n\n$sourceText"

    /** Envolve o prompt no template de chat oficial do modelo (tokenizer.chat_template).
     *
     * Sem system message; o template renderiza para uma única mensagem de
     * usuário com `add_generation_prompt`:
     * `<｜hy_begin▁of▁sentence｜><｜hy_User｜>{texto}<｜hy_Assistant｜>`. */
    fun wrapWithChatTemplate(userPrompt: String): String =
        "<｜hy_begin▁of▁sentence｜><｜hy_User｜>$userPrompt<｜hy_Assistant｜>"
}
