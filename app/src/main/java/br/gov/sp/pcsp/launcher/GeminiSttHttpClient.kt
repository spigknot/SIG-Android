package br.gov.sp.pcsp.launcher

import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** Pacing nos headers da inferência Gemini; Files/limpeza não consomem a cota do modelo. */
object GeminiSttHttpClient {
    fun create(base: OkHttpClient, policy: GeminiSttRequestPolicy, checkCancelled: () -> Unit,
               inferenceUrl: String = GeminiSttProtocol.REST_URL): OkHttpClient =
        base.newBuilder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
            .addNetworkInterceptor { chain ->
                val response = chain.proceed(chain.request())
                if (response.code == 503 && response.header("Retry-After")?.toLongOrNull() == 0L) {
                    response.newBuilder().header("Retry-After", "1").build()
                } else response
            }.eventListenerFactory { call ->
                val inference = call.request().url.toString() == inferenceUrl && call.request().method == "POST"
                object : EventListener() {
                    override fun requestHeadersStart(call: Call) { if (inference) policy.begin(checkCancelled) }
                    override fun requestHeadersEnd(call: Call, request: Request) { if (inference) policy.end() }
                    override fun callFailed(call: Call, ioe: IOException) { if (inference) policy.end() }
                }
            }.build()
}
