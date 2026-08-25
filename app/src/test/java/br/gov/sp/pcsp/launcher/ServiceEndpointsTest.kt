package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class ServiceEndpointsTest {
    @Test
    fun containsOnlyTheBuiltInServiceEndpoints() {
        assertEquals("http://servidor:8100", ServiceEndpoints.GRANITE_STT_REST)
        assertEquals("https://api.x.ai/v1/stt", ServiceEndpoints.GROK_STT_REST)
        assertEquals("wss://api.x.ai/v1/stt", ServiceEndpoints.GROK_STT_WEBSOCKET)
        assertEquals("https://api.deepgram.com/v1/listen", ServiceEndpoints.DEEPGRAM_STT_REST)
        assertEquals("wss://api.deepgram.com/v1/listen", ServiceEndpoints.DEEPGRAM_STT_WEBSOCKET)
        assertEquals("https://sync.assemblyai.com/transcribe", ServiceEndpoints.ASSEMBLYAI_STT_REST)
        assertEquals("wss://streaming.assemblyai.com/v3/ws", ServiceEndpoints.ASSEMBLYAI_STT_WEBSOCKET)
        assertEquals("https://api.elevenlabs.io/v1/speech-to-text", ServiceEndpoints.ELEVENLABS_STT_REST)
        assertEquals("wss://api.elevenlabs.io/v1/speech-to-text/realtime", ServiceEndpoints.ELEVENLABS_STT_WEBSOCKET)
        assertEquals("http://servidor:8500", ServiceEndpoints.IA_PROXY)
        assertEquals("http://servidor:8400/v1/chat/completions", ServiceEndpoints.SERVER_GEMMA)
        assertEquals("https://api.x.ai/v1/responses", ServiceEndpoints.XAI_RESPONSES)
        assertEquals("https://api.deepseek.com/chat/completions", ServiceEndpoints.DEEPSEEK_CHAT_COMPLETIONS)
    }
}
