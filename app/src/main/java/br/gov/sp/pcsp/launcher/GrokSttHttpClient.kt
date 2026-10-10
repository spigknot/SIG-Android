package br.gov.sp.pcsp.launcher

import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** Transporte HTTP do Grok STT em lote: pacing nos headers e retentativas explícitas.
 * A Activity fornece cancelamento e métricas da rodada; a política pura decide
 * o backoff. As respostas continuam paralelas após a liberação dos headers.
 */
object GrokSttHttpClient {
    fun create(
        base: OkHttpClient,
        checkCancelled: () -> Unit,
        metrics: () -> GrokSttRequestPolicy.Metrics,
        pacer: GrokSttRequestPolicy.Pacer = GrokSttRequestPolicy.sharedPacer,
    ): OkHttpClient = base.newBuilder()
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .addNetworkInterceptor { chain ->
            val response = chain.proceed(chain.request())
            // OkHttp repete 503 + Retry-After: 0 internamente, mesmo com retry
            // de conexão desligado. Evite essa tentativa invisível: o backoff
            // explícito já aguarda pelo menos 1s e respeita o teto de 5 envios.
            if (response.code == 503 && response.header("Retry-After")?.toLongOrNull() == 0L) {
                response.newBuilder().header("Retry-After", "1").build()
            } else response
        }
        .eventListenerFactory {
            val requestMetrics = metrics()
            object : EventListener() {
                override fun requestHeadersStart(call: Call) = pacer.begin(checkCancelled)

                override fun requestHeadersEnd(call: Call, request: Request) {
                    try { requestMetrics.started() }
                    finally { pacer.end() }
                }

                override fun callFailed(call: Call, ioe: IOException) = pacer.end()
            }
        }
        .build()
}
