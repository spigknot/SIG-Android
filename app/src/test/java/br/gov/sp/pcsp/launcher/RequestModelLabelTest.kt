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

        assertEquals("grok-latest", RequestModelLabel.from(config))
    }

    @Test
    fun formatsProxyWithUnderlyingModel() {
        val config = ModelServerStore.Config(
            name = GrokApiSettings.IA_PROXY_NAME,
            url = "http://servidor:8500",
            parameters = JSONObject().put("model", GrokApiSettings.TEXT_NAME),
            isProxy = true,
        )

        assertEquals("IA-Proxy/grok-latest", RequestModelLabel.from(config))
    }

    @Test
    fun formatsServerAndDeepseekModels() {
        val server = ModelServerStore.Config(
            name = ModelServerStore.LOCAL_TEXT_SERVER_NAME,
            url = "http://servidor:8402/v1/chat/completions",
            parameters = JSONObject().put("model", ModelServerStore.localModelId()),
            provider = "servidor",
        )
        val deepseek = ModelServerStore.Config(
            name = GrokApiSettings.DEEPSEEK_TEXT_NAME,
            url = "https://example.test",
            parameters = JSONObject().put("model", GrokApiSettings.DEEPSEEK_TEXT_NAME),
        )

        assertEquals(LocalTextModel.label(ModelServerStore.localModelId()), RequestModelLabel.from(server))
        assertEquals("deepseek-flash", RequestModelLabel.from(deepseek))
    }
}
