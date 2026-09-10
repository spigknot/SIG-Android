package br.gov.sp.pcsp.launcher

import org.json.JSONArray
import org.json.JSONObject

/** Um perfil de keywords: um NOME e a lista de termos dele. */
data class KeywordProfile(val name: String, val keywords: List<String>)

/**
 * Perfis de keywords (regras puras).
 *
 * O usuário mantém VÁRIAS listas nomeadas (ex.: "Lista 1", "Operação X") e
 * escolhe qual está ativa — o seletor das telas de transcrição mostra
 * "Keywords: Não" (desligado) ou "Keywords: <nome>". A escolha é ÚNICA para o
 * app: vale nas duas telas e para todos os modelos (o parâmetro real de cada
 * provedor é montado por SttKeywords na hora da requisição).
 *
 * A ordem dentro de cada perfil é a ordem de envio (os modelos aproveitam
 * apenas os primeiros termos — ver SttKeywordsHelp).
 */
object SttKeywordProfiles {

    /** Limite de perfis (a lista é editada à mão; o limite evita abuso). */
    const val MAX_PROFILES = 20

    /** Limite do nome do perfil. */
    const val MAX_NAME_LENGTH = 30

    /** Como o "desligado" aparece no seletor. */
    const val OFF_LABEL = "Não"

    private const val BASE_NAME = "Lista"

    /** Rótulo do seletor: "Keywords: Não" / "Keywords: Lista 1". */
    fun label(name: String?): String =
        "Keywords: " + (name?.trim()?.takeIf { it.isNotEmpty() } ?: OFF_LABEL)

    /** Nome sugerido para o próximo perfil ("Lista 1", "Lista 2", ...), sem
     *  colidir com os existentes. */
    fun nextDefaultName(existing: List<KeywordProfile>): String {
        val taken = existing.map { it.name.lowercase() }.toSet()
        var index = 1
        while ("${BASE_NAME} $index".lowercase() in taken) index++
        return "$BASE_NAME $index"
    }

    /** Motivo da recusa do nome digitado; `null` = pode usar. */
    fun nameError(name: String, existing: List<KeywordProfile>, renaming: String? = null): String? {
        val clean = name.trim()
        return when {
            clean.isEmpty() -> "Digite o nome do perfil."
            clean.length > MAX_NAME_LENGTH -> "Use no máximo $MAX_NAME_LENGTH caracteres."
            clean.equals(OFF_LABEL, ignoreCase = true) ->
                "\"$OFF_LABEL\" é o valor de desligado; escolha outro nome."
            existing.size >= MAX_PROFILES && renaming == null ->
                "O limite de $MAX_PROFILES perfis foi atingido."
            existing.any { it.name.equals(clean, ignoreCase = true) && !it.name.equals(renaming, ignoreCase = true) } ->
                "\"$clean\" já existe."
            else -> null
        }
    }

    /** Lista com o perfil criado/atualizado (ordem de criação preservada). */
    fun withProfile(
        profiles: List<KeywordProfile>,
        name: String,
        keywords: List<String>,
    ): List<KeywordProfile> {
        val clean = name.trim()
        val terms = SttKeywords.normalize(keywords)
        if (clean.isEmpty()) return profiles
        val index = profiles.indexOfFirst { it.name.equals(clean, ignoreCase = true) }
        return if (index >= 0) {
            profiles.toMutableList().also { it[index] = KeywordProfile(profiles[index].name, terms) }
        } else {
            profiles + KeywordProfile(clean, terms)
        }
    }

    /** Lista sem o perfil indicado. */
    fun withoutProfile(profiles: List<KeywordProfile>, name: String?): List<KeywordProfile> {
        val clean = name?.trim().orEmpty()
        if (clean.isEmpty()) return profiles
        return profiles.filterNot { it.name.equals(clean, ignoreCase = true) }
    }

    /** Lista com o perfil renomeado (mantém a posição e os termos). */
    fun renamed(profiles: List<KeywordProfile>, from: String?, to: String): List<KeywordProfile> {
        val old = from?.trim().orEmpty()
        val new = to.trim()
        if (old.isEmpty() || new.isEmpty()) return profiles
        return profiles.map { if (it.name.equals(old, ignoreCase = true)) it.copy(name = new) else it }
    }

    /** Nome EFETIVO do perfil selecionado: null quando ele não existe mais. */
    fun resolveSelection(profiles: List<KeywordProfile>, selected: String?): String? {
        val clean = selected?.trim().orEmpty()
        if (clean.isEmpty()) return null
        return profiles.firstOrNull { it.name.equals(clean, ignoreCase = true) }?.name
    }

    /** Termos do perfil selecionado (vazio = nada a enviar). */
    fun keywordsOf(profiles: List<KeywordProfile>, selected: String?): List<String> {
        val name = resolveSelection(profiles, selected) ?: return emptyList()
        return profiles.first { it.name == name }.keywords
    }

    /** Perfis -> JSON (o mesmo formato lido por [decode]). */
    fun encode(profiles: List<KeywordProfile>): String {
        val array = JSONArray()
        profiles.take(MAX_PROFILES).forEach { profile ->
            val clean = profile.name.trim()
            if (clean.isEmpty()) return@forEach
            array.put(
                JSONObject()
                    .put("name", clean)
                    .put("keywords", JSONArray(SttKeywords.normalize(profile.keywords)))
            )
        }
        return array.toString()
    }

    /** JSON persistido -> perfis (tolerante a lixo/formato antigo). */
    fun decode(stored: String): List<KeywordProfile> {
        if (stored.isBlank()) return emptyList()
        val array = runCatching { JSONArray(stored) }.getOrNull() ?: return emptyList()
        val profiles = mutableListOf<KeywordProfile>()
        val seen = mutableSetOf<String>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val name = item.optString("name").trim()
            if (name.isEmpty() || !seen.add(name.lowercase())) continue
            val terms = item.optJSONArray("keywords")?.let { terms ->
                (0 until terms.length()).map { terms.optString(it) }
            }.orEmpty()
            profiles += KeywordProfile(name, SttKeywords.normalize(terms))
            if (profiles.size >= MAX_PROFILES) break
        }
        return profiles
    }

    /** Migração do formato ANTIGO (lista única de termos): vira um perfil. */
    fun migratedFromSingleList(legacyKeywords: List<String>): List<KeywordProfile> {
        val terms = SttKeywords.normalize(legacyKeywords)
        return if (terms.isEmpty()) emptyList() else listOf(KeywordProfile("$BASE_NAME 1", terms))
    }
}
