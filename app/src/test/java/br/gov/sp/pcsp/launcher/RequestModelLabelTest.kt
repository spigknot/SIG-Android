package br.gov.sp.pcsp.launcher

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class RequestModelLabelTest {
    @Test
    fun formatsDirectGrokModel() {
        val config = ModelServerStore.Config(
            name = GrokApiSettings.TEXT_NAME,
            url = "https://example.test",
            parameters = JSONObject().put("model", GrokApiSettings.TEXT_NAME),
        )

        assertEquals("Grok-4.6", RequestModelLabel.from(config))
    }

    @Test
    fun formatsProxyWithUnderlyingModel() {
        val config = ModelServerStore.Config(
            name = GrokApiSettings.IA_PROXY_NAME,
            url = "http://servidor:8500",
            parameters = JSONObject().put("model", GrokApiSettings.TEXT_NAME),
            isProxy = true,
        )

        assertEquals("IA-Proxy/Grok-4.6", RequestModelLabel.from(config))
    }

    @Test
    fun formatsServerAndDeepseekModels() {
        val server = ModelServerStore.Config(
            name = ModelServerStore.SERVER_GEMMA_NAME,
            url = "http://servidor:8400/v1/chat/completions",
            parameters = JSONObject().put("model", ModelServerStore.SERVER_GEMMA_MODEL),
            provider = "servidor",
        )
        val deepseek = ModelServerStore.Config(
            name = GrokApiSettings.DEEPSEEK_TEXT_NAME,
            url = "https://example.test",
            parameters = JSONObject().put("model", GrokApiSettings.DEEPSEEK_TEXT_NAME),
        )

        assertEquals("servidor", RequestModelLabel.from(server))
        assertEquals("deepseek-v4-flash", RequestModelLabel.from(deepseek))
    }
}
