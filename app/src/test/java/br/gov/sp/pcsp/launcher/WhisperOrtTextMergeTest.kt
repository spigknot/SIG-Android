package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Prova a junção com remoção de sobreposição (ORT1-D07/G04).
 *
 * O caso real que motivou o arquivo está no teste do meio: sem a remoção, o
 * áudio sobreposto faz o texto repetir; com remoção agressiva demais, apaga
 * conteúdo legítimo. Os limites ficam explícitos aqui.
 */
class WhisperOrtTextMergeTest {

    @Test
    fun `sem sobreposicao concatena com um espaco`() {
        assertEquals("Amostra 1. Amostra 2.", WhisperOrtTextMerge.merge("Amostra 1.", "Amostra 2."))
    }

    @Test
    fun `remove exatamente a palavra repetida na fronteira`() {
        assertEquals(
            "Amostra 10. Amostra 11. Amostra 12.",
            WhisperOrtTextMerge.merge("Amostra 10. Amostra 11.", "Amostra 11. Amostra 12."),
        )
    }

    @Test
    fun `remove trecho de varias palavras repetido`() {
        assertEquals(
            "um dois tres quatro cinco",
            WhisperOrtTextMerge.merge("um dois tres", "dois tres quatro cinco"),
        )
    }

    @Test
    fun `nao apaga nada quando nao ha casamento exato`() {
        // Palavras parecidas mas diferentes NÃO são sobreposição: nada é cortado.
        assertEquals(
            "Amostra 11. Amostra 12.",
            WhisperOrtTextMerge.merge("Amostra 11.", "Amostra 12."),
        )
    }

    @Test
    fun `trecho inteiramente contido nao duplica`() {
        assertEquals("um dois tres", WhisperOrtTextMerge.merge("um dois tres", "dois tres"))
    }

    @Test
    fun `vazios nao contribuem nem quebram`() {
        assertEquals("abc", WhisperOrtTextMerge.merge("", "abc"))
        assertEquals("abc", WhisperOrtTextMerge.merge("abc", ""))
        assertEquals("", WhisperOrtTextMerge.merge("", ""))
    }

    @Test
    fun `mergeAll preserva a ordem das janelas`() {
        val partes = listOf("Amostra 1. Amostra 2.", "Amostra 2. Amostra 3.", "Amostra 3. Amostra 4.")
        assertEquals("Amostra 1. Amostra 2. Amostra 3. Amostra 4.", WhisperOrtTextMerge.mergeAll(partes))
    }

    @Test
    fun `limite de palavras evita casar frase longa repetida por acaso`() {
        val longo = "a b c d e f g h"
        // Com limite 2 não há casamento de 3 palavras, então concatena.
        assertEquals("$longo c d e f g h", WhisperOrtTextMerge.merge(longo, "c d e f g h", maxWords = 2))
        // Com limite padrão, casa as 6 palavras e não repete.
        assertEquals(longo, WhisperOrtTextMerge.merge(longo, "c d e f g h"))
    }
}
