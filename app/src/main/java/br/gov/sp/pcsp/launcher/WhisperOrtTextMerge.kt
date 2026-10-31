package br.gov.sp.pcsp.launcher

/**
 * Junção de texto entre janelas com sobreposição (ORT1-D07/G04).
 *
 * O modo anterior (janelas justapostas de 30 s) perdia a palavra cortada na
 * fronteira: medido no fixture de 64,4 s, o item "Amostra 11" do segundo ciclo
 * desaparecia. A correção é dar sobreposição ao áudio e remover a repetição no
 * TEXTO — o modelo transcreve o trecho comum duas vezes, e a segunda cópia não
 * pode ser publicada.
 *
 * A remoção é conservadora e verificável: procura o MAIOR k tal que as últimas
 * `k` palavras do acumulado sejam iguais às primeiras `k` palavras do novo
 * trecho, e só então descarta essas `k`. Se não houver casamento exato, os dois
 * textos são concatenados — nunca se apaga texto por semelhança aproximada.
 * [maxWords] limita o custo e evita casar frases inteiras repetidas por acaso.
 *
 * Seam puro: sem Android, sem I/O, sem estado.
 */
object WhisperOrtTextMerge {

    /** Teto de palavras comparadas no casamento da sobreposição. */
    const val DEFAULT_MAX_WORDS = 30

    /**
     * Junta [next] a [accumulated] removendo a sobreposição repetida.
     *
     * Devolve o texto combinado, com um único espaço entre as partes e sem
     * espaços nas pontas.
     */
    fun merge(accumulated: String, next: String, maxWords: Int = DEFAULT_MAX_WORDS): String {
        val left = accumulated.trim()
        val right = next.trim()
        if (left.isEmpty()) return right
        if (right.isEmpty()) return left
        val a = left.split(WHITESPACE)
        val b = right.split(WHITESPACE)
        val limit = minOf(maxWords, a.size, b.size)
        for (k in limit downTo 1) {
            if (a.subList(a.size - k, a.size) == b.subList(0, k)) {
                if (k == b.size) return left
                return (a + b.subList(k, b.size)).joinToString(" ")
            }
        }
        return "$left $right"
    }

    /**
     * Junta uma sequência inteira na ordem das janelas.
     *
     * Vazio não contribui; a ordem é preservada (nunca há reordenação por
     * tamanho ou por confiança).
     */
    fun mergeAll(parts: List<String>, maxWords: Int = DEFAULT_MAX_WORDS): String {
        var result = ""
        for (part in parts) result = merge(result, part, maxWords)
        return result
    }

    private val WHITESPACE = Regex("\\s+")
}
