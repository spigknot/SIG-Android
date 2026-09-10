package br.gov.sp.pcsp.launcher

import org.json.JSONArray

/**
 * Keywords (termos de reforço) do STT: regras puras da lista única do app.
 *
 * A lista é a MESMA para todos os provedores, mas o parâmetro enviado não é:
 * cada modelo tem nome e formato próprios e o modo (REST vs WebSocket) também
 * importa — mesma regra do idioma e da diarização, nunca um valor universal.
 * Este objeto concentra normalização, limites e a tradução por provedor; a
 * Activity segue responsável por UI, persistência, arquivo e chamada externa.
 *
 * Tradução por provedor (confirmada na documentação oficial):
 * - Deepgram: `keyterm` (repetido, query do REST e do WS).
 * - Grok (xAI): `keyterm` (repetido; query do WS e campo do multipart REST).
 * - ElevenLabs: `keyterms` (repetido; query do WS e campo do multipart REST).
 * - AssemblyAI: `keyterms_prompt` (UM parâmetro com o array em JSON).
 * - Muse Voice: `keywords` (lista dentro do JSON da requisição).
 * - Alibaba (DashScope): `vocabulary` ({termo: peso} no JSON dos parâmetros).
 * - Servidor local (Granite NAR): não tem reforço de vocabulário.
 */
object SttKeywords {

    /** Chave da lista persistida (SharedPreferences, em JSON). */
    const val KEY_STT_KEYWORDS = "stt_keywords"

    /** Limite total da lista (todos os provedores ficam abaixo dele). */
    const val MAX_KEYWORDS = 100

    /** Limite por termo dos provedores que o declaram (xAI/ElevenLabs: 50). */
    const val MAX_KEYWORD_LENGTH = 50

    /** Peso das hotwords do DashScope (os exemplos oficiais usam 4, 5 e 50). */
    const val ALIBABA_WEIGHT = 5

    /** Provedores que aceitam algum tipo de termo de reforço. */
    private val SUPPORTED_PROVIDERS =
        setOf("deepgram", "grok", "elevenlabs", "assemblyai", "metamuse", "alibaba")

    fun supportsKeywords(provider: String): Boolean = provider in SUPPORTED_PROVIDERS

    /** Normaliza a lista: tira espaços, descarta vazios, remove repetidos
     *  (sem diferenciar maiúsculas/minúsculas) e corta no limite total. */
    fun normalize(raw: List<String>): List<String> {
        val keywords = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        raw.forEach { value ->
            val term = value.trim()
            if (term.isEmpty()) return@forEach
            val key = term.lowercase()
            if (!seen.add(key)) return@forEach
            if (keywords.size >= MAX_KEYWORDS) return@forEach
            keywords += term
        }
        return keywords
    }

    /** Motivo da recusa do termo digitado; `null` = pode ser adicionado. */
    fun rejectionReason(term: String, current: List<String>): String? {
        val clean = term.trim()
        return when {
            clean.isEmpty() -> "Digite a palavra antes de adicionar."
            clean.length > MAX_KEYWORD_LENGTH ->
                "Use no máximo $MAX_KEYWORD_LENGTH caracteres."
            current.size >= MAX_KEYWORDS ->
                "O limite de $MAX_KEYWORDS keywords foi atingido."
            current.any { it.equals(clean, ignoreCase = true) } ->
                "\"${current.first { it.equals(clean, ignoreCase = true) }}\" já está na lista."
            else -> null
        }
    }

    /** Lista -> JSON (o mesmo formato lido por [decode]). */
    fun encode(keywords: List<String>): String =
        JSONArray(normalize(keywords)).toString()

    /** JSON persistido -> lista (tolerante a lixo/valor antigo). */
    fun decode(stored: String): List<String> {
        if (stored.isBlank()) return emptyList()
        val array = runCatching { JSONArray(stored) }.getOrNull() ?: return emptyList()
        return normalize((0 until array.length()).map { array.optString(it) })
    }

    /** Pares (nome, valor) que entram na query string/WS do provedor. Lista
     *  vazia = nada a anexar (Muse e Alibaba levam as keywords no JSON do
     *  corpo). O valor sai CRU — quem monta a URL faz o quote. */
    fun queryParams(provider: String, keywords: List<String>): List<Pair<String, String>> {
        val terms = normalize(keywords)
        if (terms.isEmpty()) return emptyList()
        return when (provider) {
            "deepgram", "grok" -> terms.map { "keyterm" to it }
            "elevenlabs" -> terms.map { "keyterms" to it }
            // A AssemblyAI espera UM parâmetro com o array em JSON.
            "assemblyai" -> listOf("keyterms_prompt" to JSONArray(terms).toString())
            else -> emptyList()
        }
    }

    /** AssemblyAI: lista de `keyterms_prompt`. No multipart (Sync) o valor vai
     *  como o array em JSON ([queryParams]); no corpo JSON (async) é um array
     *  de verdade — as duas rotas compartilham esta normalização. */
    fun assemblyaiPrompt(keywords: List<String>): List<String> = normalize(keywords)

    /** Muse Voice: `keywords` (lista) — vazia = omitir o campo. */
    fun museKeywords(keywords: List<String>): List<String> = normalize(keywords)

    /** Alibaba (DashScope): `vocabulary` como {termo: peso} — vazio = omitir. */
    fun alibabaVocabulary(keywords: List<String>): Map<String, Int> =
        normalize(keywords).associateWith { ALIBABA_WEIGHT }
}
