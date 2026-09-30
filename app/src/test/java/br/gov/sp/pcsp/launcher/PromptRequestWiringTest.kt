package br.gov.sp.pcsp.launcher

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Vacina de ligação: o prompt marcado na tela Configurações > PROMPTS é o que
 * sai na rede nas duas requisições da ferramenta Ocorrência.
 *
 * Cobre as três pontes da cadeia: `setActive` (o que a tela faz quando o
 * usuário marca uma opção) → `render*` (a montagem com os marcadores) →
 * `TranscriptAssistantClient` (o payload HTTP). Reprova se qualquer ponte
 * deixar de ler o prompt ativo.
 */
class PromptRequestWiringTest {

    private val temp: File = Files.createTempDirectory("sig-prompt-wiring").toFile()

    private val assets = mapOf(
        "historico_system.txt" to "PADRAO HISTORICO SYSTEM",
        "historico_user.txt" to "PADRAO HISTORICO USER >> $HISTORY_TRANSCRIPT_MARKER",
        "oitiva_system.txt" to "PADRAO OITIVA SYSTEM",
        "oitiva_user.txt" to "PADRAO OITIVA USER >> $STATEMENT_HISTORY_MARKER >> $STATEMENT_NAME_MARKER",
    )

    private fun core(): PromptStoreCore = PromptStoreCore(temp) { assets[it] }.apply { ensureLayout() }

    private fun config(server: MockWebServer, path: String): ModelServerStore.Config =
        ModelServerStore.Config(
            name = "test",
            url = server.url(path).toString(),
            parameters = JSONObject(),
            provider = "test",
        )

    private fun mockedBody(): String = """{"choices":[{"message":{"content":"ok"}}]}"""

    @After
    fun cleanup() {
        temp.deleteRecursively()
    }

    @Test
    fun promptEscolhidoNoMenuVaiNoRequestDeHistorico() {
        val store = core()
        assertNull(store.saveCustom(PromptSlot.HISTORY_SYSTEM, "hist_sys_custom", "SISTEMA HISTORICO CUSTOM", overwrite = false))
        assertNull(store.saveCustom(PromptSlot.HISTORY_USER, "hist_user_custom", "USER CUSTOM >> $HISTORY_TRANSCRIPT_MARKER", overwrite = false))
        // É exatamente o que a tela faz quando o usuário marca uma opção.
        assertNull(store.setActive(PromptSlot.HISTORY_SYSTEM, "hist_sys_custom"))
        assertNull(store.setActive(PromptSlot.HISTORY_USER, "hist_user_custom"))

        val system = store.renderHistorySystem()
        val user = store.renderHistoryUser("TRANSCRICAO REAL")
        assertEquals("SISTEMA HISTORICO CUSTOM", system)
        assertEquals("USER CUSTOM >> TRANSCRICAO REAL", user)

        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(mockedBody()))
            val done = CountDownLatch(1)
            TranscriptAssistantClient.requestHistory(
                client = OkHttpClient(),
                serverConfig = config(server, "/history"),
                transcript = "TRANSCRICAO REAL",
                historySystemPrompt = system,
                historyUserPrompt = user,
            ) { done.countDown() }
            assertTrue(done.await(5, TimeUnit.SECONDS))

            val input = JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("input")
            assertEquals("system", input.getJSONObject(0).optString("role"))
            assertEquals("SISTEMA HISTORICO CUSTOM", input.getJSONObject(0).optString("content"))
            assertEquals("user", input.getJSONObject(1).optString("role"))
            val sent = input.getJSONObject(1).optString("content")
            assertEquals("USER CUSTOM >> TRANSCRICAO REAL", sent)
            assertFalse("o marcador não pode viajar cru na requisição", sent.contains(HISTORY_TRANSCRIPT_MARKER))
        }
    }

    @Test
    fun promptEscolhidoNoMenuVaiNoRequestDeOitiva() {
        val store = core()
        assertNull(store.saveCustom(PromptSlot.STATEMENT_SYSTEM, "oitiva_sys_custom", "SISTEMA OITIVA CUSTOM", overwrite = false))
        assertNull(
            store.saveCustom(
                PromptSlot.STATEMENT_USER,
                "oitiva_user_custom",
                "OITIVA CUSTOM >> $STATEMENT_HISTORY_MARKER >> $STATEMENT_NAME_MARKER",
                overwrite = false,
            ),
        )
        assertNull(store.setActive(PromptSlot.STATEMENT_SYSTEM, "oitiva_sys_custom"))
        assertNull(store.setActive(PromptSlot.STATEMENT_USER, "oitiva_user_custom"))

        val material = "HISTORICO DA PARTE"
        val system = store.renderStatementSystem()
        val user = store.renderStatementUser("JOÃO", material)
        assertEquals("SISTEMA OITIVA CUSTOM", system)
        assertEquals("OITIVA CUSTOM >> $material >> JOÃO", user)

        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(mockedBody()))
            val done = CountDownLatch(1)
            TranscriptAssistantClient.requestStatement(
                client = OkHttpClient(),
                serverConfig = config(server, "/statement"),
                material = material,
                statementSystemPrompt = system,
                statementUserPrompt = user,
            ) { done.countDown() }
            assertTrue(done.await(5, TimeUnit.SECONDS))

            val input = JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("input")
            assertEquals("system", input.getJSONObject(0).optString("role"))
            assertEquals("SISTEMA OITIVA CUSTOM", input.getJSONObject(0).optString("content"))
            assertEquals("user", input.getJSONObject(1).optString("role"))
            val sent = input.getJSONObject(1).optString("content")
            assertEquals("OITIVA CUSTOM >> $material >> JOÃO", sent)
            assertFalse(sent.contains(STATEMENT_HISTORY_MARKER))
            assertFalse(sent.contains(STATEMENT_NAME_MARKER))
        }
    }

    @Test
    fun trocarASelecaoNaTelaMudaORequestEDesligarVoltaOPadrao() {
        val store = core()

        // Instalação nova, sem escolha: o que sai é o padrão (assets).
        assertEquals("PADRAO HISTORICO SYSTEM", store.renderHistorySystem())
        assertEquals("PADRAO HISTORICO USER >> AUDIO", store.renderHistoryUser("AUDIO"))

        assertNull(store.saveCustom(PromptSlot.HISTORY_SYSTEM, "so_para_testar", "CUSTOM SYSTEM", overwrite = false))
        assertNull(store.setActive(PromptSlot.HISTORY_SYSTEM, "so_para_testar"))
        assertEquals("CUSTOM SYSTEM", store.renderHistorySystem())

        // Voltar para `Padrão` na tela muda imediatamente o que a requisição envia.
        assertNull(store.setActive(PromptSlot.HISTORY_SYSTEM, PROMPT_DEFAULT_ID))
        assertEquals("PADRAO HISTORICO SYSTEM", store.renderHistorySystem())
        assertEquals("PADRAO HISTORICO USER >> AUDIO", store.renderHistoryUser("AUDIO"))
    }
}
