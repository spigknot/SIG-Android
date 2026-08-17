package br.gov.sp.pcsp.launcher

import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Timeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

object TranscriptAssistantClient {

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    fun requestHistoryAndNames(
        client: OkHttpClient,
        serverConfig: ModelServerStore.Config,
        partsServerConfig: ModelServerStore.Config = serverConfig,
        transcript: String,
        historySystemPrompt: String,
        historyUserPrompt: String,
        partsSystemPrompt: String,
        partsUserPrompt: String,
        extractionMethod: PartsExtractionSettings.Method,
        nameDatabase: Set<String>,
        onHistory: (Result<String>) -> Unit,
        onNames: (Result<List<String>>, Long) -> Unit
    ): List<Call> {
        val historyCall = newCall(client, serverConfig, historySystemPrompt, historyUserPrompt)

        if (extractionMethod == PartsExtractionSettings.Method.AI) {
            val namesStartedAt = System.nanoTime()
            val namesCall = newCall(client, partsServerConfig, partsSystemPrompt, partsUserPrompt)
            historyCall.enqueue(
                resultCallback(
                    parser = { body ->
                        extractOutputText(body).trim().ifBlank {
                            throw IllegalStateException("O servidor devolveu um histórico vazio.")
                        }
                    },
                    callback = onHistory
                )
            )
            namesCall.enqueue(
                resultCallback(
                    parser = { body ->
                        parseNames(extractOutputText(body)).ifEmpty {
                            throw IllegalStateException("O servidor não devolveu nomes reconhecíveis.")
                        }
                    },
                    callback = { result ->
                        onNames(result, elapsedMillis(namesStartedAt))
                    }
                )
            )
            return listOf(historyCall, namesCall)
        }

        historyCall.enqueue(
            resultCallback(
                parser = { body ->
                    extractOutputText(body).trim().ifBlank {
                        throw IllegalStateException("O servidor devolveu um histórico vazio.")
                    }
                },
                callback = { result ->
                    onHistory(result)
                    result.fold(
                        onSuccess = { history ->
                            val startedAt = System.nanoTime()
                            val names = when (extractionMethod) {
                                PartsExtractionSettings.Method.UPPERCASE -> extractUppercaseNames(history)
                                PartsExtractionSettings.Method.NAME_DATABASE ->
                                    extractNamesFromDatabase(history, nameDatabase)
                                PartsExtractionSettings.Method.AI -> emptyList()
                            }
                            val elapsedMs = elapsedMillis(startedAt)
                            onNames(Result.success(names), elapsedMs)
                        },
                        onFailure = { error ->
                            onNames(Result.failure(error), 0L)
                        }
                    )
                }
            )
        )
        return listOf(historyCall)
    }

    fun requestNames(
        client: OkHttpClient,
        serverConfig: ModelServerStore.Config,
        material: String,
        partsSystemPrompt: String,
        partsUserPrompt: String,
        extractionMethod: PartsExtractionSettings.Method,
        nameDatabase: Set<String>,
        callback: (Result<List<String>>, Long) -> Unit
    ): Call? {
        val startedAt = System.nanoTime()
        if (extractionMethod != PartsExtractionSettings.Method.AI) {
            val names = when (extractionMethod) {
                PartsExtractionSettings.Method.UPPERCASE -> extractUppercaseNames(material)
                PartsExtractionSettings.Method.NAME_DATABASE -> extractNamesFromDatabase(material, nameDatabase)
                PartsExtractionSettings.Method.AI -> emptyList()
            }
            callback(Result.success(names), elapsedMillis(startedAt))
            return null
        }
        val call = newCall(client, serverConfig, partsSystemPrompt, partsUserPrompt)
        call.enqueue(
            resultCallback(
                parser = { body ->
                    parseNames(extractOutputText(body)).ifEmpty {
                        throw IllegalStateException("O servidor não devolveu nomes reconhecíveis.")
                    }
                },
                callback = { result -> callback(result, elapsedMillis(startedAt)) }
            )
        )
        return call
    }

    private fun elapsedMillis(startedAt: Long): Long {
        return ((System.nanoTime() - startedAt) / 1_000_000L).coerceAtLeast(0L)
    }

    private fun extractUppercaseNames(text: String): List<String> {
        val names = mutableListOf<String>()
        UPPERCASE_NAME_SEQUENCE.findAll(text).forEach { match ->
            val candidate = match.value
                .trim()
                .replace(Regex("""\s+"""), " ")
            if (candidate.length >= 2 && candidate !in IGNORED_UPPERCASE_WORDS) {
                names += candidate
            }
        }
        return names.distinctBy { it.uppercase() }
    }

    private fun extractNamesFromDatabase(text: String, nameDatabase: Set<String>): List<String> {
        if (nameDatabase.isEmpty()) return emptyList()
        val names = mutableListOf<String>()
        UPPERCASE_NAME_SEQUENCE.findAll(text).forEach { match ->
            val words = UPPERCASE_WORD.findAll(match.value).map { it.value }.toList()
            val candidate = words
                .filterNot { NameDatabaseStore.normalize(it) in NAME_CONNECTORS }
                .takeIf {
                    it.isNotEmpty() && it.all { word ->
                        NameDatabaseStore.matchingKeys(word).any(nameDatabase::contains)
                    }
                }
                ?.joinToString(" ")
            if (!candidate.isNullOrBlank()) names += candidate
        }
        return names.distinctBy(NameDatabaseStore::normalize)
    }

    fun requestStatement(
        client: OkHttpClient,
        serverConfig: ModelServerStore.Config,
        material: String,
        statementSystemPrompt: String,
        statementUserPrompt: String,
        callback: (Result<String>) -> Unit
    ): Call {
        val call = newCall(client, serverConfig, statementSystemPrompt, statementUserPrompt)
        call.enqueue(
            resultCallback(
                parser = { body ->
                    extractOutputText(body).trim().ifBlank {
                        throw IllegalStateException("O servidor devolveu uma oitiva vazia.")
                    }
                },
                callback = callback
            )
        )
        return call
    }

    private fun <T> resultCallback(
        parser: (String) -> T,
        callback: (Result<T>) -> Unit
    ): Callback {
        return object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        val body = response.body?.string().orEmpty()
                        if (!response.isSuccessful) {
                            throw IllegalStateException(
                                "Servidor respondeu HTTP ${response.code}: ${body.take(400)}"
                            )
                        }
                        callback(Result.success(parser(body)))
                    } catch (e: Throwable) {
                        callback(Result.failure(e))
                    }
                }
            }
        }
    }

    private fun buildPayload(
        client: OkHttpClient,
        serverConfig: ModelServerStore.Config,
        systemPrompt: String,
        transcript: String
    ): String {
        val payload = JSONObject(serverConfig.parameters.toString())
        if (serverConfig.url.contains("/api/generate", ignoreCase = true)) {
            payload.put("system", systemPrompt)
            payload.put("prompt", transcript)
            if (!payload.has("stream")) payload.put("stream", false)
        } else if (serverConfig.provider == "deepseek" || serverConfig.isDeepseekApi) {
            payload.put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", systemPrompt))
                .put(JSONObject().put("role", "user").put("content", transcript)))
        } else if (serverConfig.provider == "servidor") {
            payload.put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", systemPrompt))
                .put(JSONObject().put("role", "user").put("content", transcript)))
            payload.put("max_tokens", countInputTokens(client, serverConfig, systemPrompt, transcript))
        } else {
            payload.put(
                "input",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("role", "system")
                            .put("content", systemPrompt)
                    )
                    .put(
                        JSONObject()
                            .put("role", "user")
                            .put("content", transcript)
                    )
            )
        }
        return payload.toString()
    }

    private fun buildRequest(
        client: OkHttpClient,
        serverConfig: ModelServerStore.Config,
        systemPrompt: String,
        material: String,
        url: String = serverConfig.url
    ): Request {
        val builder = Request.Builder()
            .url(url)
            .post(buildPayload(client, serverConfig, systemPrompt, material).toRequestBody(jsonMediaType))
        if (serverConfig.isGrokApi) {
            val key = GrokApiSettings.xaiApiKey()
            require(GrokApiSettings.isPlausibleXaiKey(key)) { "A chave API da xAI salva nas configurações é inválida." }
            builder.header("Authorization", "Bearer $key")
        } else if (serverConfig.isDeepseekApi) {
            val key = GrokApiSettings.deepseekApiKey()
            require(GrokApiSettings.isPlausibleDeepseekKey(key)) { "A chave API do Deepseek salva nas configurações é inválida." }
            builder.header("Authorization", "Bearer $key")
        }
        return builder.build()
    }

    private fun newCall(
        client: OkHttpClient,
        serverConfig: ModelServerStore.Config,
        systemPrompt: String,
        userPrompt: String
    ): Call {
        val primary = buildRequest(client, serverConfig, systemPrompt, userPrompt)
        val fallbackUrl = serverConfig.fallbackUrl.trim()
        if (fallbackUrl.isBlank()) return client.newCall(primary)
        return newFallbackCall(client, primary, buildRequest(client, serverConfig, systemPrompt, userPrompt, fallbackUrl))
    }

    /** max_tokens = tokens do input com a margem do template do chat.
     *
     * O servidor é um llama.cpp: /tokenize conta o texto CRU via
     * {"content": <texto>} -> {"tokens": [...]}; o template do chat (turnos
     * system/user) adiciona ~50% em textos curtos, então aplicamos a margem
     * 1.5 (no teste real bateu com o usage.prompt_tokens). Sem o /tokenize,
     * estimativa de 4 caracteres por token, também com a margem. */
    private fun countInputTokens(
        client: OkHttpClient,
        serverConfig: ModelServerStore.Config,
        systemPrompt: String,
        transcript: String
    ): Int {
        val text = "$systemPrompt\n$transcript"
        val bases = listOf(serverConfig.url, serverConfig.fallbackUrl)
            .filter { it.isNotBlank() }
            .map { it.substringBefore("/v1/chat/completions").trimEnd('/') + "/tokenize" }
            .distinct()
        val tokenizeClient = client.newBuilder()
            .callTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        for (tokenizeUrl in bases) {
            try {
                val body = JSONObject()
                    .put("content", text)
                    .toString()
                    .toRequestBody(jsonMediaType)
                val request = Request.Builder().url(tokenizeUrl).post(body).build()
                tokenizeClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val root = JSONObject(response.body?.string().orEmpty())
                        var count = root.optInt("count", -1)
                        if (count <= 0) count = root.optJSONArray("tokens")?.length() ?: 0
                        if (count > 0) return maxOf(1, Math.round(count * 1.5).toInt())
                    }
                }
            } catch (_: IOException) {
            } catch (_: Exception) {
            }
        }
        return maxOf(1, Math.round((text.length / 4.0) * 1.5).toInt())
    }

    internal fun newFallbackCall(client: OkHttpClient, primary: Request, fallback: Request): Call =
        FallbackCall(client, primary, fallback)

    private class FallbackCall(
        private val client: OkHttpClient,
        private val primaryRequest: Request,
        private val fallbackRequest: Request
    ) : Call {
        private val executed = AtomicBoolean(false)
        private val canceled = AtomicBoolean(false)
        @Volatile private var currentCall: Call? = null

        override fun request(): Request = primaryRequest

        override fun execute(): Response {
            check(executed.compareAndSet(false, true)) { "Already Executed" }
            if (canceled.get()) throw IOException("Canceled")
            val primary = client.newCall(primaryRequest).also { currentCall = it }
            try {
                val response = primary.execute()
                if (response.isSuccessful || canceled.get()) return response
                response.close()
            } catch (error: IOException) {
                if (canceled.get()) throw error
            }
            val fallback = client.newCall(fallbackRequest).also { currentCall = it }
            if (canceled.get()) {
                fallback.cancel()
                throw IOException("Canceled")
            }
            return fallback.execute()
        }

        override fun enqueue(responseCallback: Callback) {
            check(executed.compareAndSet(false, true)) { "Already Executed" }
            if (canceled.get()) {
                responseCallback.onFailure(this, IOException("Canceled"))
                return
            }
            val primary = client.newCall(primaryRequest).also { currentCall = it }
            primary.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (canceled.get()) responseCallback.onFailure(this@FallbackCall, e)
                    else enqueueFallback(responseCallback)
                }

                override fun onResponse(call: Call, response: Response) {
                    if (response.isSuccessful || canceled.get()) {
                        responseCallback.onResponse(this@FallbackCall, response)
                    } else {
                        response.close()
                        enqueueFallback(responseCallback)
                    }
                }
            })
        }

        private fun enqueueFallback(responseCallback: Callback) {
            if (canceled.get()) {
                responseCallback.onFailure(this, IOException("Canceled"))
                return
            }
            val fallback = client.newCall(fallbackRequest).also { currentCall = it }
            if (canceled.get()) {
                fallback.cancel()
                responseCallback.onFailure(this, IOException("Canceled"))
                return
            }
            fallback.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    responseCallback.onFailure(this@FallbackCall, e)
                }

                override fun onResponse(call: Call, response: Response) {
                    responseCallback.onResponse(this@FallbackCall, response)
                }
            })
        }

        override fun cancel() {
            canceled.set(true)
            currentCall?.cancel()
        }

        override fun isExecuted(): Boolean = executed.get()
        override fun isCanceled(): Boolean = canceled.get()
        override fun timeout(): Timeout = currentCall?.timeout() ?: Timeout.NONE
        override fun clone(): Call = FallbackCall(client, primaryRequest, fallbackRequest)
    }

    private fun extractOutputText(body: String): String {
        val root = JSONObject(body)
        root.stringValue("response")?.let { return it }
        root.stringValue("output_text")?.let { return it }
        root.stringValue("text")?.let { return it }

        root.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            ?.stringValue("content")?.let { return it }

        val output = root.optJSONArray("output")
            ?: throw IllegalStateException("A resposta não contém output.")

        for (index in output.length() - 1 downTo 0) {
            val item = output.optJSONObject(index) ?: continue
            if (item.optString("type") != "message" && item.optString("role") != "assistant") continue
            extractContentText(item.optJSONArray("content"))?.let { return it }
        }

        for (index in 0 until output.length()) {
            extractContentText(output.optJSONObject(index)?.optJSONArray("content"))?.let { return it }
        }
        throw IllegalStateException("A resposta não contém output/content/text.")
    }

    private fun extractContentText(content: JSONArray?): String? {
        if (content == null) return null
        for (index in 0 until content.length()) {
            val item = content.optJSONObject(index) ?: continue
            val type = item.optString("type")
            if (type.isNotBlank() && type != "output_text" && type != "text") continue
            item.stringValue("text")?.let { return it }
        }
        return null
    }

    private fun JSONObject.stringValue(key: String): String? {
        val value = opt(key)
        return (value as? String)?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun parseNames(rawText: String): List<String> {
        val clean = rawText.trim()
            .removePrefix("```json")
            .removePrefix("```JSON")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val names = mutableListOf<String>()
        val jsonCandidate = extractJsonCandidate(clean)
        if (jsonCandidate != null) {
            runCatching {
                if (jsonCandidate.startsWith("[")) {
                    collectNames(JSONArray(jsonCandidate), names)
                } else {
                    collectNames(JSONObject(jsonCandidate), names)
                }
            }
        }
        if (names.isEmpty()) {
            QUOTED_VALUE.findAll(clean).forEach { match ->
                addName(names, match.groupValues[1])
            }
        }
        if (names.isEmpty()) {
            clean.split(',', '\n', ';').forEach { addName(names, it) }
        }
        return names.distinctBy { it.uppercase() }
    }

    private fun extractJsonCandidate(text: String): String? {
        val arrayStart = text.indexOf('[')
        val arrayEnd = text.lastIndexOf(']')
        if (arrayStart >= 0 && arrayEnd > arrayStart) {
            return text.substring(arrayStart, arrayEnd + 1)
        }
        val objectStart = text.indexOf('{')
        val objectEnd = text.lastIndexOf('}')
        if (objectStart >= 0 && objectEnd > objectStart) {
            return text.substring(objectStart, objectEnd + 1)
        }
        return null
    }

    private fun collectNames(value: Any?, names: MutableList<String>) {
        when (value) {
            is JSONArray -> {
                for (index in 0 until value.length()) collectNames(value.opt(index), names)
            }
            is JSONObject -> {
                val keys = value.keys()
                while (keys.hasNext()) collectNames(value.opt(keys.next()), names)
            }
            is String -> addName(names, value)
        }
    }

    private fun addName(names: MutableList<String>, value: String) {
        val clean = value.trim().trim('"', '\'', '[', ']', '{', '}')
        if (clean.isNotBlank() && clean.length <= 80) names += clean.uppercase()
    }

    internal const val DEFAULT_PARTS_PROMPT =
        "Extraia apenas os primeiros nomes dos envolvidos nesse história e mê devolva no formato json. Quero que todas as letras do nomes sejam maiúsculas. Não faça nada além disso."

    private val QUOTED_VALUE = Regex(""""([^"\\]+)"""")
    private val UPPERCASE_NAME_SEQUENCE = Regex(
        """(?<![\p{L}\p{N}])[\p{Lu}][\p{Lu}\p{M}'’-]+(?:\s+[\p{Lu}][\p{Lu}\p{M}'’-]+)*(?![\p{L}\p{N}])"""
    )
    private val UPPERCASE_WORD = Regex("""[\p{Lu}][\p{Lu}\p{M}'’-]+""")
    private val NAME_CONNECTORS = setOf("DA", "DE", "DO", "DAS", "DOS", "E")
    private val IGNORED_UPPERCASE_WORDS = setOf(
        "BO",
        "CPF",
        "RG",
        "IMEI",
        "SP",
        "WhatsApp".uppercase()
    )

    internal val DEFAULT_HISTORY_PROMPT = """
        Você trabalha em uma Delegacia de Polícia, e você vai ouvir a transcrição do áudio gravado de uma entrevista que foi feita do(s) declarante(s) pelo(s) policial(ais), e depois você redigir o histórico que será usado no Boletim de Ocorrência para formalizar os relatos. As vezes serão mais de duas pessoas na conversa, você vai ter que ouvir e entender a história e depois fazer o histórico baseado no que entendeu. Vou te dar exemplos de históricos para que você saiba exatamente como deve escrever, o quão formal o texto deve soar e coisa do tipo. Deixe no mesmo nível de formalidade dos exemplos que te darei, e seja tão direto qual os exemplos, mas sem deixar de enviar informações que você pegou, é claro. Os nomes próprios sempre deverão ser escritos com as letras todas maiúsculas, e não use o nome completo, apenas o primeiro nome, ou dois nomes caso seja nome composto. Pode usar um sobrenome apenas se tiver outra pessoa com o mesmo primeiro nome envolvida. teremos duas pessoas com nomes iguais no Boletim de Ocorrência. Não use introduções e nem coloque conclusões. Seja objetivo e já comece seu texto com "Comparece" e termine com "Sem mais.". Estamos lidando com textos que contém provas que não podem ser perdidas, portanto preciso que você transcreva exatamente xingamentos e outros elementos que podem ser pesados. Evite redundâncias feias. Vou colocar abaixo, entre aspas, o primeiro exemplo para que você aprenda como deve ser feito um histórico: "Comparece BIANCA, declarando que manteve um relacionamento conjugal com WELLINGTON por aproximadamente dois anos, sendo um ano de namoro e um de casamento, possuindo um filho juntos, o infante JOÃO MIGUEL, atualmente com 3 (três) meses de idade, e que o casal encontra-se separado de fato há cerca de 45 dias. Relata a declarante que o relacionamento sempre foi conturbado, marcado por instabilidade emocional e episódios de violência psicológica por parte do autor. O averiguado frequentemente a ofendia com xingamentos de baixo calão (tais como "escrota", "louca", "retardada" e "vagabunda"), além de submetê-la a manipulações, isolamento (tratamento de silêncio) e humilhações, afirmando que a declarante era culpada pelas agressões e que seus próprios familiares não a suportavam. A vítima destaca o perfil manipulador do autor, que, perante terceiros, demonstrava comportamento afetuoso, mas, na intimidade, tornava-se agressivo. Relata que, em datas anteriores, o autor tentou tomar seu aparelho celular à força em duas ocasiões. Em um destes episódios, ao tentar gravar as ofensas proferidas por WELLINGTON, este arrebatou o telefone de suas mãos de forma violenta, vindo a causar um corte na boca da vítima, tendo o autor dissimulado a situação ao alegar que ela havia caído sozinha. Nesta ocasião, que ocorreu na segunda metade de 2025, BIANCA estava grávida e foi atendida na Santa Casa de Taguaí, pois, além do ferimento na boca e da pressão elevada, teve sangramento gestacional, tendo sua gravidez sido considerada pelo médico, a partir desse momento, gravidez de risco. Em outra ocasião, após uma discussão, BIANCA foi até o consultório de seu médico, pois tinha uma consulta marcada, mas não queria que WELLINGTON estivesse junto dela, pois a presença dele fazia com que ela passasse mal. WELLINGTON, então, não permitiu que BIANCA recebesse atendimento médico e também não permitiu que a vítima fosse atendida no posto de saúde, logo em seguida. Informa a declarante que, em novembro do ano pretérito, ocorreu um episódio de violência patrimonial. Durante uma discussão em que o autor tentava retirar pertences da residência (uma televisão recebida como presente de casamento), a declarante tentou intervir utilizando seu veículo. O autor, então, adentrou no carro e quebrou o câmbio do automóvel, apenas não causando mais danos porque a declarante conseguiu abrir o portão e evadir-se do local. Acrescenta que, devido ao comportamento persecutório do autor — que chegou a rondar sua residência de madrugada e pular o muro —, sentiu-se atemorizada e viu-se obrigada a abandonar seu lar, passando a residir na casa de sua genitora. Há cerca de 15 dias, a vítima bloqueou o autor em todas as redes sociais e aplicativos de mensagens; todavia, o averiguado passou a importuná-la insistentemente através de e-mails, utilizando o filho do casal como subterfúgio e insinuando falsamente a prática de alienação parental, fato que tem agravado severamente o quadro de saúde mental da vítima, a qual realiza acompanhamento psicológico desde o período gestacional. Por fim, a declarante informa que decidiu registrar a presente ocorrência, pois necessita de paz para resguardar sua segurança psicológica e a de seu filho recém-nascido. Manifesta expresso interesse na concessão de Medidas Protetivas de Urgência, temendo por sua integridade física e psicológica, ressaltando o fato agravante de que o autor possui posse de arma de fogo (acreditando ser legalizada), o que eleva substancialmente o seu fundado temor. Sem mais."
    """.trimIndent()

    internal val DEFAULT_STATEMENT_TEMPLATE = """
        Você trabalha em uma Delegacia de Polícia, e você vai digitar a oitiva de uma pessoa baseado no material que te fornecerei. O material pode o ser a transcrição da gravação de uma entrevista que foi feita do(s) declarante(s) pelo(s) policial(ais) e a parte que está sendo ouvida, ou o histórico do Boletim de Ocorrência, sendo que você perceberá quando é a transcrição de entrevista por existe falas de duas pessoas no texto. Se for a transcrição de entrevista, você deverá entender os fatos e depois redigirá a oitiva de acordo com o modelo de oitiva que te fornecerei aqui, sendo rígido, usando letras maiúsculas exclusivamente nos nomes de pessoas, que terão todas as letras maiúsculas e você usará apenas o primeiro nome, sempre, a não ser que haja duas pessoas com nome igual, aí você usaria um sobrenome para diferencia-las. No caso de ser um histórico de Boletim de Ocorrência, você só terá que reescrever o texto na forma de oitiva. {{INSTRUCAO_PESSOA}} Se você entender que ele(a) é vítima ou autor(a), você irá se referir a ele(a) como "declarante", e se ele for testemunha, ou seja, não tiver interesse direto no caso, você irá se referir a ele como "depoente". Seu oitiva começerá automaticamente com a frase "que aceita ser intimado/notificado pelo telefone/WhatsApp fornecido", e não usará introduções, não fará análise do mérito do caso, e não colocará conclusões, apenas escreverá a oitiva dele(a). Deixe no mesmo nível de formalidade dos exemplos que te darei. Seja tão direto qual o exemplo que te darei e use o mesmo estilo de escrita. Estamos lidando com textos que contém provas que não podem ser perdidas, portanto preciso que você transcreva exatamente xingamentos e outros elementos que podem ser pesados. Não tire informações do exemplo que te darei, pois ele se refere a outro caso que não tem relação alguma com o caso atual, apenas use ele para aprender a redigir a oitiva. Evite redundâncias feias. Repara que cada informação fornecida é uma sentença que começa com "que" e termina com ponto-e-vírgula (";"). Vou colocar abaixo, entre aspas, o primeiro exemplo para que você aprenda como deve ser escrita uma oitiva: "aceita ser intimada/notificada pelo telefone/Whatsapp fornecido; que conhece KAROLYNA há cerca de 4 anos; que KAROLYNA era uma de suas melhores amigas; que certo dia, a depoente estava na residência de KAROLYNA, quando KAROLYNA confidenciou a ela que JOÃO havia, alguns dias atrás, publicado um vídeo íntimo de KAROLYNA nos stories de um perfil de Facebook antigo da vítima, perfil este que a vítima tinha perdido o acesso; que este vídeo fora gravado sem a autorização de KAROLYNA; que se recorda de ter aconselhado KAROLYNA a guardar as imagens, pois elas eram provas; que se recorda que KAROLYNA tinha muito medo de JOÃO; que se recorda que, pouco depois da noite entre os dias 31/12/2022 e 01/01/2023, ou seja, pouco depois da virada do ano, noite esta em que estavam na praça próxima a prefeitura do município de Taguaí, KAROLYNA disse que, naquela noite, JOÃO estava mandando mensagens com ameaças para RITA; que se recorda que, depois que RITA encerrara o relacionamento com JOÃO, a depoente encontrou RITA lotérica, e foi informada de que KAROLYNA evitava sair de casa por ter medo de JOÃO.""
    """.trimIndent()
}
