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

    /** Peso das hotwords do DashScope. A doc usa 1-5 como faixa normal e 50
     *  como "super hotword" (no máximo 50 delas por lista). MEDIDO no modo ao
     *  vivo: com peso 5 só um dos dois termos foi corrigido; com peso 50 os
     *  DOIS saíram certos (2 execuções cada, mesmo áudio). */
    const val ALIBABA_WEIGHT = 5
    const val ALIBABA_SUPER_WEIGHT = 50
    /** Limite de "super hotwords" (peso 50) por requisição, segundo a doc. */
    const val ALIBABA_MAX_SUPER = 50

    /** Limites DOCUMENTADOS por provedor (modo REST x ao vivo). Exceder o
     *  limite do Deepgram devolve erro ("Keyterm limit exceeded"); nos demais o
     *  termo excedente é ignorado ou a requisição é rejeitada. Por isso a lista
     *  é AJUSTADA ao provedor antes de entrar na requisição. */
    private const val DEEPGRAM_TOKEN_BUDGET = 500   // tokens somados (todos os keyterms)
    private const val GROK_MAX_TERMS = 100          // xAI: 100 termos...
    private const val GROK_MAX_TERM_LENGTH = 50     // ...de até 50 caracteres
    private const val ELEVENLABS_REST_MAX_TERMS = 1000
    private const val ELEVENLABS_LIVE_MAX_TERMS = 50
    private const val ELEVENLABS_LIVE_MAX_TERM_LENGTH = 20
    private const val ASSEMBLYAI_LIVE_MAX_TERMS = 100
    private const val ASSEMBLYAI_LIVE_MAX_TERM_LENGTH = 50
    private const val ASSEMBLYAI_SYNC_CHAR_BUDGET = 2048  // Sync REST: soma dos caracteres

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

    /** Estimativa de tokens de um termo (≈1 token a cada 4 caracteres, mínimo
     *  1) — usada para respeitar o orçamento de 500 tokens do Deepgram. */
    private fun estimateTokens(term: String): Int = (term.length + 3) / 4

    /** Ajusta a lista ao provedor/modo antes de montar o parâmetro: corta no
     *  número máximo, descarta termos acima do tamanho permitido e respeita o
     *  orçamento (tokens no Deepgram, caracteres no Sync da AssemblyAI).
     *  Nada é enviado que o provedor vá recusar; nada é inventado no lugar. */
    fun fitForProvider(
        provider: String,
        keywords: List<String>,
        isLive: Boolean = false,
    ): List<String> {
        val terms = normalize(keywords)
        return when (provider) {
            "deepgram" -> {
                var budget = DEEPGRAM_TOKEN_BUDGET
                terms.takeWhile { term ->
                    val cost = estimateTokens(term)
                    if (cost > budget) false else { budget -= cost; true }
                }
            }
            "grok" -> terms.filter { it.length <= GROK_MAX_TERM_LENGTH }.take(GROK_MAX_TERMS)
            "elevenlabs" ->
                if (isLive) {
                    terms.filter { it.length <= ELEVENLABS_LIVE_MAX_TERM_LENGTH }
                        .take(ELEVENLABS_LIVE_MAX_TERMS)
                } else {
                    terms.take(ELEVENLABS_REST_MAX_TERMS)
                }
            "assemblyai" ->
                if (isLive) {
                    terms.filter { it.length <= ASSEMBLYAI_LIVE_MAX_TERM_LENGTH }
                        .take(ASSEMBLYAI_LIVE_MAX_TERMS)
                } else {
                    var budget = ASSEMBLYAI_SYNC_CHAR_BUDGET
                    terms.takeWhile { term ->
                        if (term.length > budget) false else { budget -= term.length; true }
                    }
                }
            else -> terms
        }
    }

    /** Pares (nome, valor) que entram na query string/WS do provedor. Lista
     *  vazia = nada a anexar (Muse e Alibaba levam as keywords no JSON do
     *  corpo). O valor sai CRU — quem monta a URL faz o quote. */
    fun queryParams(
        provider: String,
        keywords: List<String>,
        isLive: Boolean = false,
    ): List<Pair<String, String>> {
        val terms = fitForProvider(provider, keywords, isLive)
        if (terms.isEmpty()) return emptyList()
        return when (provider) {
            "deepgram", "grok" -> terms.map { "keyterm" to it }
            "elevenlabs" -> terms.map { "keyterms" to it }
            // A AssemblyAI espera UM parâmetro com o array em JSON (no REST do
            // Sync o mesmo valor vai como campo de formulário; no corpo JSON do
            // async é um array de verdade).
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

    /** Alibaba (DashScope): `vocabulary` como {termo: peso} — vazio = omitir.
     *
     *  O peso útil depende do modo, e isso foi MEDIDO com áudio real:
     *  - ao vivo (qwen-audio-3.0-asr-flash-streaming): peso 50 (super hotword)
     *    fez o modelo escrever "Taguaí" (com peso 5 saía "Taguay");
     *  - arquivo (fun-asr-flash): peso 5 já corrige o nome próprio e a doc do
     *    Fun-ASR só admite 1-5, então não enviamos o peso especial nesse modo.
     */
    fun alibabaVocabulary(keywords: List<String>, isLive: Boolean): Map<String, Int> =
        normalize(keywords).mapIndexed { index, term ->
            val weight = if (isLive && index < ALIBABA_MAX_SUPER) ALIBABA_SUPER_WEIGHT else ALIBABA_WEIGHT
            term to weight
        }.toMap()
}
