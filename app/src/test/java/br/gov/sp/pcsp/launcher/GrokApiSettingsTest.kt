package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Vacina: a chave do Muse colada com prefixo "Bearer " (ou espaços) deve
 *  normalizar para a chave crua "LLM|..." — o modelo some da lista e o
 *  handshake falha quando o prefixo vaza para a validação/autenticação. */
class GrokApiSettingsTest {

    @Test
    fun metamuse_acceptsDocumentedKeyFormat() {
        assertTrue(GrokApiSettings.isPlausibleMetamuseKey("LLM|607358788850350|nx9abcDEF123"))
    }

    @Test
    fun metamuse_stripsBearerPrefixBeforeValidating() {
        assertTrue(GrokApiSettings.isPlausibleMetamuseKey("Bearer LLM|607358788850350|nx9abcDEF123"))
        assertEquals(
            "LLM|607358788850350|nx9abcDEF123",
            GrokApiSettings.normalizeMetamuseKey("  bearer LLM|607358788850350|nx9abcDEF123  ")
        )
    }

    @Test
    fun metamuse_rejectsBlankAndMalformedKeys() {
        assertFalse(GrokApiSettings.isPlausibleMetamuseKey(""))
        assertFalse(GrokApiSettings.isPlausibleMetamuseKey("   "))
        assertFalse(GrokApiSettings.isPlausibleMetamuseKey("sk-abcdef"))
        assertFalse(GrokApiSettings.isPlausibleMetamuseKey("LLM|so-id-sem-segredo"))
        assertFalse(GrokApiSettings.isPlausibleMetamuseKey("Bearer "))
    }
}
