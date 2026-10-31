package br.gov.sp.pcsp.launcher

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject

/** Modelo e motor anunciados pelo servidor STT local em `/health`.
 *
 * Existe UM servidor em `http://servidor:8100`, e o painel dele alterna o
 * motor entre Granite (NAR, resposta em lote) e Nemotron (rota compatível com
 * `/v1/audio/transcriptions`) sem reiniciar o aplicativo. O `/health` é a única
 * fonte de verdade: [parseId] lê o campo `model`, [fetch]/[fetchBlocking]
 * consultam o endpoint e [engineFor] classifica o identificador já lido.
 *
 * Nada aqui escolhe motor por parâmetro de requisição: o campo multipart
 * `model` NÃO troca o motor, então o app nunca o envia para "selecionar" um
 * modelo interno.
 */
internal object LocalGraniteModel {

    /** Lê o identificador anunciado. Lança se o servidor não anunciar nenhum. */
    fun parseId(json: String): String {
        val modelId = JSONObject(json).opt("model") as? String
        return modelId?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw IllegalStateException("O servidor não anunciou nenhum modelo em /health.")
    }

    /** Motor do servidor a partir do modelo JÁ detectado. Nome desconhecido ou
     *  vazio cai em [LocalServerEngine.GRANITE], o comportamento histórico do
     *  app (só `files` em `/transcribe`, sem idioma/diarização/tempos). */
    fun engineFor(modelId: String): LocalServerEngine {
        // `lowercase()` do Kotlin é independente de locale (Locale.ROOT).
        val normalized = modelId.trim().lowercase()
        return if (NEMOTRON_MARKER in normalized) LocalServerEngine.NEMOTRON else LocalServerEngine.GRANITE
    }

    /** O servidor local está rodando o Nemotron? */
    fun isNemotron(modelId: String): Boolean = engineFor(modelId) == LocalServerEngine.NEMOTRON

    /** Consulta o `/health` e devolve o identificador já normalizado.
     *
     * Reutilizada pela tela de modelos e pelas telas de transcrição — é a ÚNICA
     * rotina de descoberta; não crie outra chamada nem outro endpoint. */
    fun fetch(client: OkHttpClient, callback: (Result<String>) -> Unit): Call {
        val call = metadataClient(client).newCall(request())
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                callback(Result.failure(error))
            }

            override fun onResponse(call: Call, response: Response) {
                val result = response.use {
                    runCatching {
                        check(it.isSuccessful) {
                            "Não foi possível consultar o modelo do servidor: HTTP ${it.code}."
                        }
                        parseId(it.body?.string().orEmpty())
                    }
                }
                callback(result)
            }
        })
        return call
    }

    /** Mesma consulta de [fetch], bloqueante, para quem já está fora da UI
     *  (envio de arquivos e fatias ao vivo). Falha = null, nunca exceção: um
     *  `/health` indisponível não pode derrubar uma transcrição. */
    fun fetchBlocking(client: OkHttpClient): String? =
        runCatching {
            metadataClient(client).newCall(request()).execute().use { response ->
                check(response.isSuccessful) {
                    "Não foi possível consultar o modelo do servidor: HTTP ${response.code}."
                }
                parseId(response.body?.string().orEmpty())
            }
        }.getOrNull()

    private fun request(): Request = Request.Builder()
        .url(ServiceEndpoints.GRANITE_STT_REST.trimEnd('/') + "/health")
        .header("Accept", "application/json")
        .build()

    private fun metadataClient(client: OkHttpClient): OkHttpClient = client.newBuilder()
        .callTimeout(3, TimeUnit.SECONDS)
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    private const val NEMOTRON_MARKER = "nemotron"
}

/** Motores possíveis do servidor STT local. */
enum class LocalServerEngine {
    GRANITE,
    NEMOTRON
}
