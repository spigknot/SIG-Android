package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Vacina: a chave do Muse colada com prefixo "Bearer " (ou espaços) deve
 *  normalizar para a chave crua — o modelo some da lista e o handshake falha
 *  quando o prefixo vaza para a validação/autenticação. A verificação é
 *  única: 48 caracteres após a normalização. */
class GrokApiSettingsTest {

    private val valid48 = ("abc123!@#".repeat(6)).take(48)

    @Test
    fun metamuse_accepts48CharKey() {
        assertEquals(48, valid48.length)
        assertTrue(GrokApiSettings.isPlausibleMetamuseKey(valid48))
    }

    @Test
    fun metamuse_stripsBearerPrefixBeforeValidating() {
        assertTrue(GrokApiSettings.isPlausibleMetamuseKey("Bearer $valid48"))
        assertEquals(
            valid48,
            GrokApiSettings.normalizeMetamuseKey("  bearer $valid48  ")
        )
    }

    @Test
    fun metamuse_rejectsBlankAndWrongLengthKeys() {
        assertFalse(GrokApiSettings.isPlausibleMetamuseKey(""))
        assertFalse(GrokApiSettings.isPlausibleMetamuseKey("   "))
        assertFalse(GrokApiSettings.isPlausibleMetamuseKey("short"))
        assertFalse(GrokApiSettings.isPlausibleMetamuseKey(valid48 + "X"))
        assertFalse(GrokApiSettings.isPlausibleMetamuseKey("Bearer "))
    }

    /** Vacina: seleções salvas com nomes ANTIGOS migram sozinhas — sem a
     *  migração o ModelServerStore.readConfigs descarta o valor salvo e cai
     *  no primeiro da lista (gemma), trocando o modelo escolhido em silêncio. */
    @Test
    fun textSelection_migratesLegacyModelNames() {
        assertEquals("grok-latest", GrokApiSettings.normalizeTextSelection("grok-4.6"))
        assertEquals(
            ModelServerStore.LOCAL_TEXT_SERVER_NAME,
            GrokApiSettings.normalizeTextSelection(ModelServerStore.LEGACY_LOCAL_TEXT_NAME)
        )
        assertEquals(
            ModelServerStore.LOCAL_TEXT_SERVER_NAME,
            GrokApiSettings.normalizeTextSelection("servidor (qwen-2.5-3B-Instruct-Abliterated)")
        )
        for (old in listOf("servidor (qwen2.5)", "qwen2.5-7b")) {
            assertEquals(ModelServerStore.LOCAL_TEXT_SERVER_NAME, GrokApiSettings.normalizeTextSelection(old))
            val config = ModelServerStore.configForParts(old, "none")
            assertEquals("http://servidor:8402/v1/chat/completions", config.url)
            assertEquals(ModelServerStore.localModelId().ifBlank { "modelo não informado" }, config.modelName)
        }
        // O 4.20 (não-reasoning) permanece igual; nomes válidos não mudam.
        assertEquals(
            GrokApiSettings.GROK_NON_REASONING_TEXT_NAME,
            GrokApiSettings.normalizeTextSelection(GrokApiSettings.GROK_NON_REASONING_TEXT_NAME)
        )
        assertEquals(
            GrokApiSettings.IA_PROXY_NAME,
            GrokApiSettings.normalizeTextSelection(GrokApiSettings.IA_PROXY_NAME)
        )
    }
}
