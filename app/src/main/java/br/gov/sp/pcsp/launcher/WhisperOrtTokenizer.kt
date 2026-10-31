package br.gov.sp.pcsp.launcher

import java.io.ByteArrayOutputStream

/**
 * Tokenizer byte-level BPE do Whisper para o motor ORT (ORT1-D05).
 *
 * O `vocab.json` do export mapeia a *string* de cada token para o id; essa
 * string usa a tabela byte->unicode do GPT-2 (bytes não imprimíveis viram
 * codepoints 256..511). Decodificar é o caminho inverso: codepoint -> byte,
 * concatenar TODOS os bytes e só então interpretar como UTF-8. Fazer isso por
 * token (ou por `String` intermediária) corrompe acentos e caracteres
 * multi-byte que atravessam a fronteira de dois tokens — é exatamente o defeito
 * que o teste de português cobre.
 *
 * Tokens especiais (`id >= 50257`: eos, sot, idiomas, tarefa, timestamps) são
 * descartados por padrão; nenhum `<|...|>` deve aparecer no texto do usuário.
 *
 * Seam puro: sem Android, sem I/O de rede. O JSON é passado já lido.
 */
class WhisperOrtTokenizer private constructor(
    private val bytesByToken: Array<ByteArray?>,
    private val specialIds: Set<Int>,
    private val eosId: Int,
) {

    /** Quantidade de tokens que o vocabulário conhece (id máximo + 1). */
    val vocabSize: Int get() = bytesByToken.size

    /** Id de fim de sequência deste export. */
    val eosTokenId: Int get() = eosId

    /** `true` quando [id] é um token especial/timestamp (não é texto). */
    fun isSpecial(id: Int): Boolean = id in specialIds

    /**
     * Bytes crus de [ids] na ordem recebida, sem interpretar como texto.
     *
     * Útil para comparar com a referência (Python) byte a byte e para provar
     * que a perda não está na decodificação UTF-8.
     */
    fun decodeBytes(ids: IntArray, skipSpecial: Boolean = true): ByteArray {
        val out = ByteArrayOutputStream(ids.size * 4)
        for (id in ids) {
            if (skipSpecial && isSpecial(id)) continue
            val bytes = bytesByToken.getOrNull(id) ?: continue
            out.write(bytes, 0, bytes.size)
        }
        return out.toByteArray()
    }

    /**
     * Texto de [ids].
     *
     * [skipSpecial] remove tokens especiais; [trim] aplica o `strip()` que a
     * referência faz no fim do pipeline. Bytes inválidos viram U+FFFD em vez de
     * exceção: um token truncado no fim não pode derrubar a transcrição inteira,
     * mas o texto preserva o sinal de que algo ficou incompleto.
     */
    fun decode(ids: IntArray, skipSpecial: Boolean = true, trim: Boolean = true): String {
        val text = String(decodeBytes(ids, skipSpecial), Charsets.UTF_8)
        return if (trim) text.trim() else text
    }

    companion object {
        /** Id de `</s>`/`<|endoftext|>`; primeiro token tratado como especial. */
        const val EOS_TOKEN_ID = 50_257

        /** Primeiro token especial do vocabulário multilíngue (eos). */
        const val FIRST_SPECIAL_ID = 50_257

        /** Tamanho do vocabulário do `whisper-large-v3-turbo`. */
        const val VOCAB_SIZE = 51_866

        /** Cobertura mínima de tokens de texto para aceitar um vocabulário real. */
        const val MIN_PRODUCTION_TOKENS = 50_000

        /**
         * Constrói o tokenizer a partir do `vocab.json` decodificado.
         *
         * ATENÇÃO ao tamanho: o `vocab.json` do Whisper tem só os 50.257 tokens
         * de TEXTO; eos/idioma/tarefa/timestamps vivem em `added_tokens.json`
         * (50257..51865). O tamanho do vocabulário é a UNIÃO dos dois — usar só
         * o `vocab.json` daria 50.257 e o argmax nunca escolheria EOS (ele fica
         * fora do limite), fazendo a geração correr até o teto. Por isso o
         * default é `max(maior id de texto, maior especial) + 1`.
         *
         * [specialIds] deve vir da configuração do export; o default é
         * `[50257, 51866)` — eos + 99 idiomas + tarefa + timestamps + no-speech.
         */
        /**
         * Caminho de PRODUÇÃO: exige o vocabulário completo do export.
         *
         * Um mapa parcial (A/B) antes era aceito e dimensionava 51866, fazendo
         * `decode(idNormal)` devolver vazio em silêncio. Agora cobertura
         * insuficiente é pacote inválido. Fixtures sintéticas usam [fromSubset],
         * que é explicitamente o caminho de teste.
         */
        fun fromVocab(
            vocab: Map<String, Int>,
            specialIds: Set<Int> = (FIRST_SPECIAL_ID until VOCAB_SIZE).toSet(),
            eosId: Int = EOS_TOKEN_ID,
        ): WhisperOrtTokenizer {
            // Contar entradas não basta: um mapa com 50257 chaves e um BURACO no
            // id 67 tem o mesmo tamanho e fazia decode(67) sumir em silêncio.
            // A produção exige cobertura EXATA de 0..FIRST_SPECIAL_ID-1, o que
            // também garante unicidade dos ids.
            val ids = vocab.values.sorted()
            require(ids.size >= MIN_PRODUCTION_TOKENS) {
                "vocab.json parcial: ${ids.size} tokens < $MIN_PRODUCTION_TOKENS exigidos em produção"
            }
            val esperado = (0 until FIRST_SPECIAL_ID).toList()
            require(ids == esperado) {
                val faltando = esperado.toSet() - ids.toSet()
                val duplicados = ids.size - ids.toSet().size
                "vocab.json não cobre 0..${FIRST_SPECIAL_ID - 1} sem buracos " +
                    "(faltando=${faltando.take(5)}, duplicados=$duplicados)"
            }
            return fromSubset(vocab, specialIds, eosId)
        }

        /**
         * Caminho de TESTE/fixture: aceita vocabulário reduzido de propósito.
         *
         * Continua recusando vazio e EOS fora da faixa; só não exige cobertura.
         */
        fun fromSubset(
            vocab: Map<String, Int>,
            specialIds: Set<Int> = (FIRST_SPECIAL_ID until VOCAB_SIZE).toSet(),
            eosId: Int = EOS_TOKEN_ID,
        ): WhisperOrtTokenizer {
            // Vocabulário vazio NÃO pode virar um tokenizer de 51866 posições com
            // texto sempre vazio: isso transformaria erro de pacote em NO_SPEECH.
            require(vocab.isNotEmpty()) { "vocab.json vazio: pacote inválido" }
            val maxText = (vocab.values.maxOrNull() ?: -1) + 1
            require(maxText > 1) { "vocab.json sem tokens de texto: ${vocab.size} entradas" }
            require(eosId < VOCAB_SIZE) { "eos $eosId fora do vocabulário do export ($VOCAB_SIZE)" }
            val maxSpecial = (specialIds.maxOrNull() ?: -1) + 1
            val size = maxOf(maxText, maxSpecial, eosId + 1)
            val table = arrayOfNulls<ByteArray>(size)
            val decoder = byteDecoder()
            for ((token, id) in vocab) {
                if (id < 0 || id >= size) continue
                table[id] = encodeToken(token, decoder)
            }
            return WhisperOrtTokenizer(table, specialIds, eosId)
        }

        /** Converte a string de um token nos bytes originais (tabela GPT-2). */
        private fun encodeToken(token: String, decoder: IntArray): ByteArray {
            val out = ByteArrayOutputStream(token.length)
            for (ch in token) {
                val code = ch.code
                val byte = if (code < decoder.size) decoder[code] else -1
                if (byte >= 0) {
                    out.write(byte)
                } else {
                    // Codepoint fora da tabela: não existe no vocabulário
                    // oficial. Preservar os bytes UTF-8 é melhor que descartar.
                    val fallback = ch.toString().toByteArray(Charsets.UTF_8)
                    out.write(fallback, 0, fallback.size)
                }
            }
            return out.toByteArray()
        }

        /**
         * Tabela inversa codepoint -> byte do GPT-2 (`bytes_to_unicode`).
         *
         * Bytes imprimíveis (33..126, 161..172, 174..255) mapeiam para si
         * mesmos; os demais viram codepoints 256..511 na ordem crescente de
         * byte. Entradas -1 não existem no alfabeto.
         */
        internal fun byteDecoder(): IntArray {
            val printable = ArrayList<Int>(256)
            for (b in 33..126) printable.add(b)
            for (b in 161..172) printable.add(b)
            for (b in 174..255) printable.add(b)
            val decoder = IntArray(512) { -1 }
            for (b in printable) decoder[b] = b
            var extra = 0
            for (b in 0 until 256) {
                if (b in printable) continue
                decoder[256 + extra] = b
                extra++
            }
            return decoder
        }
    }
}
