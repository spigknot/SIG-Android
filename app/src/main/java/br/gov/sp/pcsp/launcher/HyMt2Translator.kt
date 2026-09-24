package br.gov.sp.pcsp.launcher

import org.json.JSONArray
import org.json.JSONObject

/** Tradução de texto com o modelo Hy-MT2 (Tencent, 1.25 bit, llama.cpp).
 *
 * Regra pura da ferramenta Texto: monta o prompt de instrução oficial do
 * model card do Hy-MT2 (idioma alvo em nome COMPLETO em inglês, prompt em
 * inglês) e lê a resposta do servidor llama.cpp no formato OpenAI-compatible.
 * Não contém UI nem HTTP.
 */
object HyMt2Translator {

    /** Modelo servido (rótulo enviado no payload; o llama.cpp ecoa o nome). */
    const val MODEL_ID = "hy-mt2-1.8b"

    /** Idioma alvo padrão da ferramenta (nome completo em inglês, exigência do model card). */
    const val DEFAULT_TARGET_LANGUAGE = "Portuguese"

    /** `max_tokens` recomendado pelo model card para o 1.8B/7B. */
    const val MAX_TOKENS = 4096

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

    /** Payload POSTado em `/v1/chat/completions` (só mensagem de usuário; o modelo
     *  não tem system_prompt padrão). Amostragem fica no servidor, como no model card. */
    fun buildRequestPayload(
        sourceText: String,
        targetLanguage: String = DEFAULT_TARGET_LANGUAGE,
        model: String = MODEL_ID
    ): String = JSONObject()
        .put("model", model)
        .put("stream", false)
        .put("max_tokens", MAX_TOKENS)
        .put(
            "messages",
            JSONArray().put(
                JSONObject().put("role", "user").put("content", buildUserPrompt(sourceText, targetLanguage))
            )
        )
        .toString()

    /** Lê `choices[0].message.content` da resposta e devolve a tradução aparada;
     *  lança `IllegalStateException` com motivo real quando a resposta está vazia. */
    fun parseTranslation(body: String): String {
        val root = JSONObject(body)
        val message = root.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            ?: throw IllegalStateException("A resposta não contém choices[0].message.")
        val content = (message.opt("content") as? String)?.trim()
        if (content.isNullOrBlank()) throw IllegalStateException("O servidor devolveu uma tradução vazia.")
        return content
    }
}
