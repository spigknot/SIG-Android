package br.gov.sp.pcsp.launcher

/** Endpoints oficiais embutidos no aplicativo. */
object ServiceEndpoints {
    const val GRANITE_HOST = "servidor"
    const val GRANITE_STT_PORT = 8100
    const val GRANITE_STT_REST = "http://servidor:8100"

    const val GROK_STT_REST = "https://api.x.ai/v1/stt"
    const val GROK_STT_WEBSOCKET = "wss://api.x.ai/v1/stt"

    const val DEEPGRAM_STT_REST = "https://api.deepgram.com/v1/listen"
    const val DEEPGRAM_STT_WEBSOCKET = "wss://api.deepgram.com/v1/listen"

    const val ASSEMBLYAI_STT_REST = "https://sync.assemblyai.com/transcribe"
    const val ASSEMBLYAI_STT_WEBSOCKET = "wss://streaming.assemblyai.com/v3/ws"

    const val ELEVENLABS_STT_REST = "https://api.elevenlabs.io/v1/speech-to-text"
    const val ELEVENLABS_STT_WEBSOCKET = "wss://api.elevenlabs.io/v1/speech-to-text/realtime"

    const val IA_PROXY = "http://servidor:8500"
    const val SERVER_GEMMA = "http://servidor:8400/v1/chat/completions"
    const val XAI_RESPONSES = "https://api.x.ai/v1/responses"
    const val DEEPSEEK_CHAT_COMPLETIONS = "https://api.deepseek.com/chat/completions"
}
