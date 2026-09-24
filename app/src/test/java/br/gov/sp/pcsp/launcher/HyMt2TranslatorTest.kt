package br.gov.sp.pcsp.launcher

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contratos da tradução Hy-MT2: prompt oficial do model card, payload de uma
 * única mensagem de usuário (sem system_prompt) e leitura da resposta.
 *
 * O ponto destes testes é travar o formato que o modelo exige: idioma alvo em
 * nome completo em inglês, instrução de "apenas o resultado traduzido" e
 * separação em linha em branco antes do texto. Se um destes quebrar, o modelo
 * passa a devolver explicação junto com a tradução (ou inventa idioma alvo).
 */
class HyMt2TranslatorTest {

    @Test
    fun prompt_usaAInstrucaoPadraoDoModelCard_comIdiomaAlvoCompleto() {
        val prompt = HyMt2Translator.buildUserPrompt("Hello, how are you?")
        assertTrue(prompt.startsWith("Translate the following text into Portuguese."))
        assertTrue(prompt.contains("only output the translated result without any additional explanation"))
        assertFalse(prompt.contains("**"))
        assertTrue(prompt.endsWith("\n\nHello, how are you?"))
    }

    @Test
    fun prompt_aceitaOutroIdiomaAlvo() {
        val prompt = HyMt2Translator.buildUserPrompt("Bom dia", "English")
        assertTrue(prompt.startsWith("Translate the following text into English."))
        assertTrue(prompt.endsWith("\n\nBom dia"))
    }

    @Test
    fun payload_montaMensagemUnicaDeUsuario_semSystem() {
        val payload = JSONObject(HyMt2Translator.buildRequestPayload("Olá"))
        assertEquals("hy-mt2-1.8b", payload.getString("model"))
        assertEquals(false, payload.getBoolean("stream"))
        assertEquals(4096, payload.getInt("max_tokens"))
        val messages = payload.getJSONArray("messages")
        assertEquals(1, messages.length())
        assertEquals("user", messages.getJSONObject(0).getString("role"))
        assertEquals(
            HyMt2Translator.buildUserPrompt("Olá"),
            messages.getJSONObject(0).getString("content")
        )
        assertFalse(payload.has("system"))
    }

    @Test
    fun parseTranslation_leConteudoDaEscolha_aparado() {
        val body = """{"choices":[{"message":{"role":"assistant","content":"  Olá, como vai?  "}}]}"""
        assertEquals("Olá, como vai?", HyMt2Translator.parseTranslation(body))
    }

    @Test(expected = IllegalStateException::class)
    fun parseTranslation_semChoices_lancaErroComMotivo() {
        HyMt2Translator.parseTranslation("""{"object":"chat.completion"}""")
    }

    @Test(expected = IllegalStateException::class)
    fun parseTranslation_conteudoVazio_lancaErroComMotivo() {
        HyMt2Translator.parseTranslation("""{"choices":[{"message":{"content":"   "}}]}""")
    }
}
