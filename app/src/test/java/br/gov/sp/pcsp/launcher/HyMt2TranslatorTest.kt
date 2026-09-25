package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contratos da tradução Hy-MT2: prompt oficial do model card e template de
 * chat do modelo.
 *
 * O ponto destes testes é travar o formato que o modelo exige: idioma alvo em
 * nome completo em inglês, instrução de "apenas o resultado traduzido" e os
 * tokens especiais do template de chat. Se um destes quebrar, o modelo passa a
 * devolver explicação junto com a tradução (ou não reconhece o turno).
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
    fun template_envolveComOsTokensDeChatDoModelo() {
        val wrapped = HyMt2Translator.wrapWithChatTemplate("TEXTO")
        assertEquals(
            "<｜hy_begin▁of▁sentence｜><｜hy_User｜>TEXTO<｜hy_Assistant｜>",
            wrapped
        )
    }

    @Test
    fun idiomas_rotulosUnicosENomesCompletosEmIngles() {
        val labels = HyMt2Translator.TARGET_LANGUAGES.map { it.label }
        assertEquals(labels.size, labels.toSet().size)
        assertTrue(HyMt2Translator.TARGET_LANGUAGES.isNotEmpty())
        for (language in HyMt2Translator.TARGET_LANGUAGES) {
            assertTrue(
                "promptName deve ser nome completo em inglês: ${language.promptName}",
                language.promptName.matches(Regex("[A-Za-z]+"))
            )
        }
    }

    @Test
    fun idiomas_portuguesEhOPrimeiroEPadrao() {
        val first = HyMt2Translator.TARGET_LANGUAGES.first()
        assertEquals("Português", first.label)
        assertEquals(HyMt2Translator.DEFAULT_TARGET_LANGUAGE, first.promptName)
    }

    @Test
    fun promptNameFor_converteRotuloEcaiNoPadraoQuandoDesconhecido() {
        assertEquals("Spanish", HyMt2Translator.promptNameFor("Espanhol"))
        assertEquals("English", HyMt2Translator.promptNameFor("Inglês"))
        assertEquals(
            HyMt2Translator.DEFAULT_TARGET_LANGUAGE,
            HyMt2Translator.promptNameFor("Klingon")
        )
    }

    @Test
    fun prompt_aceitaQualquerIdiomaDaLista() {
        for (language in HyMt2Translator.TARGET_LANGUAGES) {
            val prompt = HyMt2Translator.buildUserPrompt("Bom dia", language.promptName)
            assertTrue(prompt.startsWith("Translate the following text into ${language.promptName}."))
        }
    }

    @Test
    fun amostragem_segueOModelCardPara18B() {
        assertEquals(4096, HyMt2Translator.MAX_TOKENS)
        assertEquals(0.7f, HyMt2Translator.TEMPERATURE, 0.001f)
        assertEquals(0.6f, HyMt2Translator.TOP_P, 0.001f)
        assertEquals(20, HyMt2Translator.TOP_K)
        assertEquals(1.05f, HyMt2Translator.REPEAT_PENALTY, 0.001f)
    }
}
