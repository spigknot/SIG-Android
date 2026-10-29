package br.gov.sp.pcsp.launcher

/** Ponte JNI do Whisper (carregar modelo, transcrever, cancelar).
 *
 * So declara/aciona o nativo; selecao de modelo, UI e relatorio ficam em
 * WhisperActivity. Nenhum segredo passa por aqui.
 */

object WhisperNative {
    init {
        // R2/B3: seam de carga. Produção (WhisperLibLoader em main) delega ao
        // NativeDependencyManager; a variante de laboratório substitui o
        // arquivo via source set do flavor e carrega libs candidatas do
        // sandbox do lab. O comportamento default é idêntico.
        WhisperLibLoader.load("sig_whisper")
    }

    interface Callback {
        fun onSegment(text: String, startMs: Long, endMs: Long)
        fun onProgress(progress: Int)
        fun onNativeLog(line: String)
    }

    external fun loadModel(modelPath: String, backendKind: Int, flashAttention: Boolean): Boolean
    external fun transcribe(
        wavPath: String,
        language: String,
        beamSize: Int,
        bestOf: Int,
        wordTimestamps: Boolean,
        vadFilter: Boolean,
        vadModelPath: String,
        callback: Callback
    ): String
    external fun cancelTranscription()
    /** W09: fronteira de sessão — limpa pedido de cancelamento pendente para que
     * um cancelamento de uma sessão anterior não vaze para esta. Deve ser
     * chamado no início de cada execução (transcrição em lote/gravação/live).
     * Retorna o identificador monotônico da sessão (diagnóstico). */
    external fun beginSession(): Long
    /** R8/A2: marcador `faJobBegin` no log nativo (INÍCIO de job) — delimita o
     * estado FA do job ATUAL no log de diagnóstico: linhas SIG_FA_STATUS/
     * SIG_FA_EXEC ANTERIORES ao marcador deixam de valer (job sem transcribe
     * NÃO herda a execução do anterior). Lib antiga sem o símbolo lança
     * UnsatisfiedLinkError — o chamador trata como best-effort. */
    external fun faJobBegin(route: String): Boolean

    /** W10: status estruturado da última transcrição: "ok", "no_speech",
     * "cancelled" ou "error". Prefira este status a interpretar prefixos de
     * controle ("Erro:"/"Cancelado:") no texto retornado por transcribe. */
    external fun lastTranscribeStatus(): String

    /**
     * R14/C2 — rota FA efetiva (contadores do backend): r6/global/recusas/CPU.
     * `fa_status=unavailable` quando o backend não expõe o símbolo.
     */
    /**
     * R15/C1 — modo R6 pedido pelo ENSAIO ("auto"/"force"/"off"/"").
     *
     * Precisa ser chamado ANTES da primeira carga de modelo do processo: o
     * modo e' lido uma unica vez, na inicializacao do device Vulkan.
     * Producao nunca chama -> variavel ausente -> AUTO.
     */
    external fun setFaR6ModeEnv(mode: String)

    /** R16/D3 — ativa observador de beam + gravação TF (só tem efeito no DIAG). */
    external fun setBeamObsEnv(prefix: String)

    /**
     * R15/D2 — contexto de encoder derivado das AMOSTRAS da janela.
     *
     * O nativo calcula posicoes = ceil(amostras/320) + margem 12,5% e RECUSA
     * (mantendo o default) quando a janela excede a cobertura do modelo.
     * Consumido one-shot no proximo transcribe; producao nunca chama.
     */
    /** R24/REQ — fotografia por tentativa (0=OK, 1=dup, 2=conflito, 3=cheio, 4=inválido). */
    external fun publishAttemptOpts(owner: Long, attempt: Long, windowSamples: Long,
                                    submitMb: Int, k: Int, serial: Int): Int
    external fun clearAttemptOpts(owner: Long, attempt: Long): Int
    external fun setAudioCtxWindow(nSamples: Long): Long

    external fun faRouteStatus(): String

    /** R19/A4 — backend EFETIVO do contexto (pos-fallback); -1 = desconhecido. */
    external fun effectiveBackendKind(): Int

    /** R19/A4 — FA realmente presente no grafo carregado. */
    external fun effectiveFlashGraph(): Boolean

    /** R14/B4 — MEDIÇÃO do trabalho em voo (submits/nós/bytes do último grafo). */
    external fun b4InflightState(): String

    /** R17/D2 — submissões do backend OpenCL (o b4 cobre só o Vulkan). */
    external fun clSubmitState(): String

    /** R14/B4 — define uma variável de ambiente do processo (DIAG). */
    external fun setDiagEnv(name: String, value: String): Int

    // R2/B1 — ownership por sessão (API v2). A lib antiga não exporta estes
    // símbolos; o controller detecta a capacidade UMA vez via
    // [ownershipApiVersion] e cai no modo legado se faltar (UnsatisfiedLinkError).
    /** Capacidade de ownership por sessão; 2 = contrato owned (R2), 3 = R3
     * (cancels por dono, recusa pós-espera, getters de contexto/ativo). */
    external fun ownershipApiVersion(): Int
    /** R3/B2: dono do contexto carregado (0 = nenhum/legado). */
    external fun contextOwner(): Long
    /** R3/B2: dono da inferência EXECUTANDO agora (0 = nenhuma; waiter não conta). */
    external fun activeTranscribeOwner(): Long
    /** Nova sessão com id monotônico (fonte única do dono). Não apaga
     * cancelamento com dono nem pedido de inferência em curso. */
    external fun beginSessionOwned(): Long
    /** Carrega/substitui o modelo registrando [owner] como dono do contexto. */
    external fun loadModelOwned(modelPath: String, backendKind: Int, flashAttention: Boolean, owner: Long): Boolean
    /** R3/B2: transfere a posse de um contexto JÁ carregado (mesma chave real
     * caminho+backend+FA) para [owner], sob g_mutex, sem recarga. */
    external fun assumeContextOwned(modelPath: String, backendKind: Int, flashAttention: Boolean, owner: Long): Boolean
    /** Libera o modelo SÓ se o dono do contexto ainda for [owner]; caso
     * contrário devolve false sem tocar no contexto. */
    external fun releaseModelOwned(owner: Long): Boolean
    /** Transcreve com dono: barreiras de cancelamento e status são vinculados a
     * [owner] (texto e status do mesmo job). */
    external fun transcribeOwned(
        wavPath: String,
        language: String,
        beamSize: Int,
        bestOf: Int,
        wordTimestamps: Boolean,
        vadFilter: Boolean,
        vadModelPath: String,
        owner: Long,
        callback: Callback
    ): String
    /**
     * R25/REQ — entrada ADITIVA: a fotografia da tentativa viaja NESTA chamada
     * ([request] no formato de [AttemptRequest.toWire]). Recusa tipada quando o
     * pedido é inválido/não suportado; não cai no caminho legado em silêncio.
     */
    external fun transcribeOwnedReq(
        wavPath: String,
        language: String,
        beamSize: Int,
        bestOf: Int,
        wordTimestamps: Boolean,
        vadFilter: Boolean,
        vadModelPath: String,
        owner: Long,
        callback: Callback,
        request: String
    ): String
    external fun cancelTranscriptionOwned(owner: Long)
    /** Status da última transcrição do dono [owner]; "" se não houver. */
    external fun lastTranscribeStatusOwned(owner: Long): String
    /** R3/B2: getter seguro do dono do contexto — libs antigas (sem o símbolo)
     * devolvem -1 (desconhecido ⇒ o cache owned recusa o fast-path). */
    fun contextOwnerSafe(): Long = try {
        contextOwner()
    } catch (_: Throwable) {
        -1L
    }

    /** R3/B2: transferência de posse com símbolo opcional (lib sem R3 → false). */
    fun assumeContextOwnedSafe(modelPath: String, backendKind: Int, flashAttention: Boolean, owner: Long): Boolean = try {
        assumeContextOwned(modelPath, backendKind, flashAttention, owner)
    } catch (_: Throwable) {
        false
    }

    /** R3/B2: getter seguro do executor ativo — -1 quando a lib não expõe. */
    fun activeTranscribeOwnerSafe(): Long = try {
        activeTranscribeOwner()
    } catch (_: Throwable) {
        -1L
    }

    /** R11/D2 — seam de bancada da POLÍTICA DE THREADS (one-shot). Valor 0
     * desliga. O produto nunca chama isto com valor > 0: [WhisperLabSeams]
     * devolve 0 no APK normal. */
    external fun setThreadsOverride(threads: Int): Int

    /**
     * R14/D2-D3 — contexto de encoder EXPERIMENTAL (bancada). 0 = desliga;
     * >0 vale para o PRÓXIMO job (one-shot). O produto nunca chama.
     */
    external fun setAudioCtxOverride(audioCtx: Int): Int

    /** R18/E1 — pedido de audio_ctx VINCULADO ao dono: só o job `owner` o consome. */
    external fun setAudioCtxOverrideFor(owner: Long, audioCtx: Int): Int

    /** Diagnóstico do seam: -1 = inativo. */
    external fun audioCtxOverridePeek(): Int

    /** R20/C1 — descarta o pedido do dono (cancelamento/erro antes do consumo). */
    external fun cancelAudioCtxRequest(owner: Long): Boolean
    /** R11/D2 — leitura do seam (diagnóstico de bancada; 0 = inativo). */
    external fun threadsOverridePeek(): Int

    external fun filterVad(
        inputWavPath: String,
        outputWavPath: String,
        sileroModelPath: String,
        mode: Int,
        aggressiveness: Int
    ): String
    external fun systemInfo(): String
    external fun buildInfo(): String
    external fun backendInfo(): String
    external fun lastError(): String
    external fun lastLoadLog(): String
    /** R22/B3 — configura o dump DENTRO do processo; 0 = build sem hook (NORMAL). */
    external fun setInflightK(k: Int)
    external fun setSerialBatch(on: Int)
    external fun sigDumpConfigure(kinds: String?, dir: String?, tag: String?, steps: Int): Int
    external fun sigDumpConfigureNodes(first: Int, step: Int, maxNodes: Int): Int
    external fun releaseModel()
}
