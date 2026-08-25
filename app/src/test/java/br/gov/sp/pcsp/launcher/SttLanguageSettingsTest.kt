package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testes puros das regras que alimentam os campos REST e as URLs WebSocket.
 * Os overloads testados não acessam SharedPreferences nem credenciais.
 */
class SttLanguageSettingsTest {

    @Test
    fun parseCodes_trimsAndSplitsCommaAndNewline() {
        assertEquals(listOf("pt", "en", "es"), SttLanguageSettings.parseCodes(" pt, en\nes "))
    }

    @Test
    fun deepgram_customModeUsesOnlyCustomValue() {
        assertEquals("pt-BR", SttLanguageSettings.deepgramLanguageParam("custom", " pt-BR "))
        assertEquals("pt-BR", SttLanguageSettings.deepgramLanguageParam("pt-BR", "en"))
    }

    @Test
    fun assemblyai_restSingleCustomUsesLanguageCode() {
        assertEquals(
            false to "pt",
            SttLanguageSettings.assemblyaiRestLanguage("custom", " pt ")
        )
    }

    @Test
    fun assemblyai_restMultiOmitsLanguageCodeAndEnablesDetection() {
        assertEquals(
            true to null,
            SttLanguageSettings.assemblyaiRestLanguage("custom", "pt, en")
        )
        assertEquals(
            true to null,
            SttLanguageSettings.assemblyaiRestLanguage("multi", "pt")
        )
    }

    @Test
    fun assemblyai_webSocketPreservesRepeatedLanguageCodes() {
        assertEquals(
            listOf("pt", "en"),
            SttLanguageSettings.assemblyaiWsLanguageCodes("custom", "pt, en")
        )
        assertTrue(SttLanguageSettings.assemblyaiWsLanguageCodes("multi", "pt").isEmpty())
    }

    @Test
    fun elevenlabs_restOnlyUsesOneCustomCode() {
        assertEquals("pt", SttLanguageSettings.elevenlabsRestLanguageCode("custom", "pt"))
        assertNull(SttLanguageSettings.elevenlabsRestLanguageCode("custom", "pt, en"))
        assertNull(SttLanguageSettings.elevenlabsRestLanguageCode("multi", "pt"))
    }

    @Test
    fun elevenlabs_webSocketSplitsPrimaryAndSecondaryCodes() {
        assertEquals(
            "pt" to listOf("en", "es"),
            SttLanguageSettings.elevenlabsWsLanguage("custom", "pt, en, es")
        )
        assertEquals(null to emptyList<String>(), SttLanguageSettings.elevenlabsWsLanguage("multi", "pt"))
    }

    @Test
    fun grok_omitsMultiAndMultiCodeCustomValues() {
        assertEquals("pt", SttLanguageSettings.grokLanguageParam("custom", "pt"))
        assertNull(SttLanguageSettings.grokLanguageParam("custom", "pt, en"))
        assertNull(SttLanguageSettings.grokLanguageParam("multi", "pt"))
        assertNull(SttLanguageSettings.grokLanguageParam("", "pt"))
    }

    @Test
    fun invalidCodes_areScopedToTheSelectedProvider() {
        assertEquals(listOf("xx"), SttLanguageSettings.invalidCodes("assemblyai", listOf("pt", "xx")))
        assertTrue(SttLanguageSettings.invalidCodes("unknown", listOf("xx")).isEmpty())
        assertFalse(SttLanguageSettings.isValidAssemblyai("pt-BR"))
        assertTrue(SttLanguageSettings.isValidDeepgram("pt-BR"))
    }
}
