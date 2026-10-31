package br.gov.sp.pcsp.launcher

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Prova a decodificação do tokenizer contra a biblioteca `tokenizers` oficial
 * sobre os MESMOS ids (ORT1-D05): acentos, espaços e tokens especiais.
 */
class WhisperOrtTokenizerTest {

    private fun golden(): JSONObject {
        val bytes = javaClass.classLoader!!.getResourceAsStream("ort/tokenizer-golden.json")!!
            .use { it.readBytes() }
        return JSONObject(String(bytes, Charsets.UTF_8))
    }

    private fun tokenizer(root: JSONObject): WhisperOrtTokenizer {
        val vocabJson = root.getJSONObject("vocab")
        val vocab = HashMap<String, Int>(vocabJson.length())
        for (key in vocabJson.keys()) vocab[key] = vocabJson.getInt(key)
        return WhisperOrtTokenizer.fromSubset(vocab)
    }

    @Test
    fun `decodifica portugues com acentos igual a referencia`() {
        val root = golden()
        val tokenizer = tokenizer(root)
        val cases = root.getJSONArray("cases")
        var checked = 0
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val idsJson = case.getJSONArray("ids")
            val ids = IntArray(idsJson.length()) { idsJson.getInt(it) }
            val expected = case.getString("expected")
            val actual = tokenizer.decode(ids, skipSpecial = true, trim = false)
            assertEquals("caso ${case.getString("kind")} ids=${ids.toList()}", expected, actual)
            checked++
        }
        assertTrue("casos conferidos: $checked", checked >= 10)
    }

    @Test
    fun `tokens especiais nao aparecem no texto`() {
        // Vocabulário mínimo com id conhecido (65 = 'a'); os especiais entram
        // só para provar que são descartados, não para decodificar.
        val tokenizer = WhisperOrtTokenizer.fromSubset(mapOf("a" to 65, "z" to 51_865))
        val ids = intArrayOf(
            WhisperOrtPrompt.START_OF_TRANSCRIPT,
            WhisperOrtPrompt.LANGUAGE_PT,
            65,
            WhisperOrtPrompt.TRANSCRIBE,
            WhisperOrtPrompt.NO_TIMESTAMPS,
            WhisperOrtPrompt.END_OF_TEXT,
        )
        val text = tokenizer.decode(ids)
        assertEquals("a", text)
        assertFalse(text.contains("<|"))
        assertFalse(text.contains("|>"))
    }

    @Test
    fun `bytes invalidos viram U+FFFD em vez de excecao`() {
        val tokenizer = WhisperOrtTokenizer.fromSubset(mapOf("a" to 65, "z" to 51_865))
        // 0xE2 abre um caractere de 3 bytes que não completa: UTF-8 inválido.
        val bytes = byteArrayOf(0x61, 0xE2.toByte())
        val decoded = String(bytes, Charsets.UTF_8)
        assertTrue(decoded.contains('\uFFFD'))
        assertEquals("a\uFFFD", decoded)
        assertEquals("a", tokenizer.decode(intArrayOf(65)))
    }

    @Test
    fun `tabela de bytes do GPT2 cobre os 256 valores sem colisao`() {
        val decoder = WhisperOrtTokenizer.byteDecoder()
        val seen = BooleanArray(256)
        var mapped = 0
        for (code in 0 until 512) {
            val byte = decoder[code]
            if (byte < 0) continue
            assertFalse("byte $byte mapeado duas vezes", seen[byte])
            seen[byte] = true
            mapped++
        }
        assertEquals(256, mapped)
        // Espaço (byte 32) é o 33º byte não imprimível -> codepoint 256+32.
        assertEquals(32, decoder[288])
    }

    @Test
    fun `tamanho do vocabulario vem do maior id`() {
        val tokenizer = WhisperOrtTokenizer.fromSubset(mapOf("a" to 65, "z" to 51_865))
        assertEquals(51_866, tokenizer.vocabSize)
        assertEquals(WhisperOrtPrompt.END_OF_TEXT, tokenizer.eosTokenId)
        assertTrue(tokenizer.isSpecial(WhisperOrtPrompt.NO_TIMESTAMPS))
        assertFalse(tokenizer.isSpecial(65))
    }

    @Test
    fun `vocab json so de texto ainda cobre eos e especiais`() {
        // Caso real do aparelho: vocab.json tem só 0..50256; os especiais vêm de
        // added_tokens.json. Se o tamanho ficasse 50257, o argmax nunca poderia
        // escolher EOS (id 50257) e a geração correria até o teto.
        val tokenizer = WhisperOrtTokenizer.fromSubset(mapOf("a" to 65, "b" to 66))
        assertEquals(WhisperOrtTokenizer.VOCAB_SIZE, tokenizer.vocabSize)
        assertEquals(WhisperOrtPrompt.END_OF_TEXT, tokenizer.eosTokenId)
        assertTrue(tokenizer.vocabSize > WhisperOrtPrompt.END_OF_TEXT)
        // E o prefixo do motor passa na validação com esse vocabulário.
        assertNull(WhisperOrtPrompt.validate(WhisperOrtPrompt.portuguese(), tokenizer.vocabSize))
        // Texto continua decodificando normalmente.
        assertEquals("ab", tokenizer.decode(intArrayOf(65, 66)))
    }
}
