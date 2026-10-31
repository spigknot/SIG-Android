package br.gov.sp.pcsp.launcher

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Implementação ONNX Runtime/CPU do [WhisperOrtBackend] (ORT1-D01/D03/E01/F01).
 *
 * Usa TRÊS sessões — encoder, decoder de init e decoder com past — em vez do
 * grafo "merged" com `If`. A troca foi decidida por medição:
 *
 * 1. o merged devolve `present.N.encoder.*` **degenerado** no ramo
 *    `use_cache_branch=true` (forma `(0,20,1,64)` em vez de `(1,20,1500,64)`);
 *    realimentá-lo fazia o 2º token virar EOS (0,16 contra 0,59 do token certo);
 * 2. depois de ~3 janelas completas na MESMA sessão o merged falha no Reshape do
 *    ramo `If` (`Input shape:{20,0,64}, requested {1,20,64,64}`), o que
 *    quebraria qualquer áudio acima de ~90 s;
 * 3. o separado rodou 6 janelas seguidas na mesma sessão com texto idêntico e
 *    igual ao do aparelho (`separate-decoders-stress.json`).
 *
 * Ganho estrutural: o grafo de init NÃO tem entradas de past e o `with_past`
 * devolve só o KV do decoder — o KV do encoder atravessa a janela sem ser
 * recalculado, e **nenhum tensor de comprimento zero é criado**, que era a raiz
 * das duas falhas acima.
 *
 * Assinaturas conferidas nos grafos reais, não presumidas:
 * - encoder: `input_features` float32 `[1,128,3000]` -> `last_hidden_state` `[1,1500,1280]`
 * - init: `input_ids` int64 `[1,seq]` + `encoder_hidden_states` -> `logits` + 16 `present.*`
 * - with_past: `input_ids` int64 `[1,1]` + 16 `past_key_values.*` -> `logits` + 8 `present.*`
 *
 * Posse de memória: o `Result` do init vive a janela inteira (é dono do KV do
 * encoder); o do with_past vive até o passo seguinte terminar. Nenhum é fechado
 * com tensores em uso (F01). Os logits são COPIADOS para `FloatArray`, porque o
 * buffer pertence ao `Result`.
 *
 * Environment: singleton compartilhado com o Granite, NUNCA fechado (F07).
 */
class WhisperOrtSession(
    private val encoderModel: File,
    private val decoderModel: File,
    private val decoderWithPastModel: File,
    private val intraOpThreads: Int = DEFAULT_THREADS,
    private val environment: OrtEnvironment = OrtEnvironment.getEnvironment(),
) : WhisperOrtBackend {

    private var encoderSession: OrtSession? = null
    private var initSession: OrtSession? = null
    private var pastSession: OrtSession? = null
    private val options = ArrayList<OrtSession.SessionOptions>(3)

    private var hiddenTensor: OnnxTensor? = null
    private var initResult: OrtSession.Result? = null
    private var stepResult: OrtSession.Result? = null

    private var layerCount = 0
    private var vocabSize = 0

    /** Tempo de criação das três sessões (H01), separado de encode/decode. */
    var loadMillis: Long = 0
        private set

    /** Tempo acumulado dentro do `run` do encoder (H01). */
    var encoderMillis: Long = 0
        private set

    /** Tempo acumulado dentro do `run` dos decoders (H01). */
    var decodeMillis: Long = 0
        private set

    /** Passos de decoder executados desde [open]. */
    var decodeSteps: Int = 0
        private set

    /** Janelas iniciadas desde [open] (prova de estabilidade multi-janela). */
    var windows: Int = 0
        private set

    /**
     * Abre as três sessões em CPU.
     *
     * Provider é CPU por construção: nenhum `addQnn`/`addNnapi` é chamado nesta
     * rodada. Arquivo ausente falha ANTES de criar qualquer sessão.
     */
    fun open() {
        require(encoderModel.isFile) { "encoder ausente: ${encoderModel.path}" }
        require(decoderModel.isFile) { "decoder init ausente: ${decoderModel.path}" }
        require(decoderWithPastModel.isFile) { "decoder with_past ausente: ${decoderWithPastModel.path}" }
        check(encoderSession == null) { "sessão já aberta" }
        val startedAt = System.nanoTime()
        try {
            val encoder = newOptions()
            val init = newOptions()
            val past = newOptions()
            encoderSession = environment.createSession(encoderModel.path, encoder)
            initSession = environment.createSession(decoderModel.path, init)
            pastSession = environment.createSession(decoderWithPastModel.path, past)
            layerCount = detectLayerCount(initSession!!)
            vocabSize = detectVocabSize(initSession!!)
            loadMillis = (System.nanoTime() - startedAt) / 1_000_000
        } catch (failure: Throwable) {
            // Sessão parcial não fica viva: fecha o que abriu e propaga.
            runCatching { encoderSession?.close() }
            runCatching { initSession?.close() }
            runCatching { pastSession?.close() }
            options.forEach { runCatching { it.close() } }
            options.clear()
            encoderSession = null
            initSession = null
            pastSession = null
            throw failure
        }
    }

    /**
     * Executa um `run` classificando o erro REAL do ONNX Runtime.
     *
     * Antes, `runCatching` transformava QUALQUER Throwable em
     * [WhisperOrtRuntimeFault] — inclusive argumento inválido, shape e OOM — e o
     * retry reabria sessão para todos. Agora só [OrtException.OrtErrorCode.ORT_FAIL]
     * (a falha de execução observada no Reshape) é recuperável; o resto vira
     * [WhisperOrtInputFault] e é definitivo.
     */
    private inline fun <T> guarded(stage: String, block: () -> T): T = try {
        block()
    } catch (failure: OrtException) {
        when (failure.code) {
            OrtException.OrtErrorCode.ORT_FAIL ->
                throw WhisperOrtRuntimeFault(failure.code.name, "$stage: ${failure.message}", failure)
            else ->
                throw WhisperOrtInputFault("$stage: ${failure.code} ${failure.message}")
        }
    }

    private fun newOptions(): OrtSession.SessionOptions {
        val created = OrtSession.SessionOptions()
        created.setIntraOpNumThreads(intraOpThreads)
        created.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        // Grafo linear (encoder) ou sem ramificação (decoders separados):
        // threads inter-op só somariam overhead.
        created.setInterOpNumThreads(1)
        options.add(created)
        return created
    }

    /** `true` quando as três sessões estão abertas. */
    val isOpen: Boolean get() = encoderSession != null && initSession != null && pastSession != null

    /** Camadas de decoder detectadas no grafo (deve ser 4). */
    val layers: Int get() = layerCount

    /** Vocabulário detectado na saída `logits` (deve ser 51866). */
    val vocabulary: Int get() = vocabSize

    override fun encode(logMel: FloatArray): FloatArray {
        val session = encoderSession ?: error("sessão do encoder não aberta")
        require(logMel.size == WhisperOrtFrontend.N_MELS * WhisperOrtFrontend.N_FRAMES) {
            "log-mel com ${logMel.size} valores; esperado ${WhisperOrtFrontend.N_MELS * WhisperOrtFrontend.N_FRAMES}"
        }
        val startedAt = System.nanoTime()
        hiddenTensor?.close()
        hiddenTensor = null
        val input = OnnxTensor.createTensor(
            environment,
            FloatBuffer.wrap(logMel),
            longArrayOf(1L, WhisperOrtFrontend.N_MELS.toLong(), WhisperOrtFrontend.N_FRAMES.toLong()),
        )
        try {
            val result = guarded("encoder") { session.run(mapOf(ENCODER_INPUT to input)) }
            try {
                val output = result.get(0) as OnnxTensor
                val buffer = output.floatBuffer
                val out = FloatArray(buffer.remaining())
                buffer.get(out)
                hiddenTensor = OnnxTensor.createTensor(
                    environment,
                    FloatBuffer.wrap(out),
                    longArrayOf(1L, HIDDEN_FRAMES.toLong(), HIDDEN_DIM.toLong()),
                )
                encoderMillis += (System.nanoTime() - startedAt) / 1_000_000
                return out
            } finally {
                result.close()
            }
        } finally {
            input.close()
        }
    }

    override fun start(hidden: FloatArray, prompt: IntArray): FloatArray {
        val session = initSession ?: error("sessão de init não aberta")
        val hiddenState = hiddenTensor ?: OnnxTensor.createTensor(
            environment,
            FloatBuffer.wrap(hidden),
            longArrayOf(1L, HIDDEN_FRAMES.toLong(), HIDDEN_DIM.toLong()),
        ).also { hiddenTensor = it }
        // Nova janela: os Results anteriores não são mais necessários. Fechar
        // AQUI é seguro porque o run que os usava já terminou (F01).
        stepResult?.close()
        stepResult = null
        initResult?.close()
        initResult = null
        val ids = OnnxTensor.createTensor(
            environment,
            LongBuffer.wrap(LongArray(prompt.size) { prompt[it].toLong() }),
            longArrayOf(1L, prompt.size.toLong()),
        )
        val startedAt = System.nanoTime()
        val result = try {
            guarded("decoder init") { session.run(mapOf(INIT_IDS to ids, INIT_HIDDEN to hiddenState)) }
        } finally {
            ids.close()
        }
        decodeMillis += (System.nanoTime() - startedAt) / 1_000_000
        decodeSteps += 1
        windows += 1
        initResult = result
        return readLogits(result, prompt.size - 1, prompt.size)
    }

    override fun step(token: Int): FloatArray {
        val session = pastSession ?: error("sessão with_past não aberta")
        val base = initResult ?: error("step chamado antes de start (KV ausente)")
        val ids = OnnxTensor.createTensor(
            environment,
            LongBuffer.wrap(longArrayOf(token.toLong())),
            longArrayOf(1L, 1L),
        )
        val previousStep = stepResult
        val feeds = HashMap<String, OnnxTensor>(layerCount * KINDS.size + 1)
        feeds[PAST_IDS] = ids
        for (layer in 0 until layerCount) {
            for (kind in KINDS) {
                // decoder KV: do último passo; encoder KV: do init (constante).
                val source = if (kind.startsWith("decoder") && previousStep != null) previousStep else base
                feeds["past_key_values.$layer.$kind"] = source.get("present.$layer.$kind").orElseThrow {
                    IllegalStateException("decoder sem present.$layer.$kind")
                } as OnnxTensor
            }
        }
        val startedAt = System.nanoTime()
        val result = try {
            guarded("decoder with_past") { session.run(feeds) }
        } finally {
            ids.close()
        }
        decodeMillis += (System.nanoTime() - startedAt) / 1_000_000
        decodeSteps += 1
        // `previousStep` era a fonte das entradas acima: só sai depois do run.
        previousStep?.close()
        stepResult = result
        return readLogits(result, 0, 1)
    }

    override fun close() {
        runCatching { stepResult?.close() }
        stepResult = null
        runCatching { initResult?.close() }
        initResult = null
        hiddenTensor?.let { runCatching { it.close() } }
        hiddenTensor = null
        runCatching { encoderSession?.close() }
        runCatching { initSession?.close() }
        runCatching { pastSession?.close() }
        options.forEach { runCatching { it.close() } }
        options.clear()
        encoderSession = null
        initSession = null
        pastSession = null
        // OrtEnvironment é singleton compartilhado com o Granite: NUNCA fechar.
    }

    /**
     * Fecha e reabre as três sessões (recuperação após falha do runtime).
     *
     * Devolve `false` se a reabertura também falhar — o chamador então reporta
     * ERROR, nunca um sucesso parcial silencioso.
     */
    override fun reopen(): Boolean = try {
        close()
        open()
        true
    } catch (failure: Throwable) {
        close()
        false
    }

    /**
     * Extrai a linha de logits da posição [position] e **copia** os valores.
     *
     * Copiar é obrigatório: o buffer pertence ao `Result`, que será fechado no
     * passo seguinte. O consumidor guarda o `FloatArray`, nunca o tensor.
     */
    private fun readLogits(result: OrtSession.Result, position: Int, sequence: Int): FloatArray {
        val tensor = result.get(0) as OnnxTensor
        val buffer = tensor.floatBuffer
        val vocab = if (sequence > 0) buffer.remaining() / sequence else vocabSize
        val base = position * vocab
        val out = FloatArray(vocab)
        for (i in 0 until vocab) out[i] = buffer.get(base + i)
        return out
    }

    private fun detectLayerCount(session: OrtSession): Int {
        var max = -1
        for (name in session.outputNames) {
            val match = LAYER_PATTERN.find(name) ?: continue
            val layer = match.groupValues[1].toInt()
            if (layer > max) max = layer
        }
        check(max >= 0) { "decoder sem present.N.*: esquema inesperado" }
        return max + 1
    }

    private fun detectVocabSize(session: OrtSession): Int {
        val info = session.outputInfo[LOGITS_OUTPUT]?.info as? TensorInfo
            ?: error("decoder sem saída '$LOGITS_OUTPUT'")
        val last = info.shape.lastOrNull() ?: 0L
        check(last > 0) { "decoder com vocabulário simbólico ($last)" }
        return last.toInt()
    }

    companion object {
        /** Threads intra-op default; ajuste só com medida (H03). */
        const val DEFAULT_THREADS = 4

        private const val ENCODER_INPUT = "input_features"
        private const val INIT_IDS = "input_ids"
        private const val INIT_HIDDEN = "encoder_hidden_states"
        private const val PAST_IDS = "input_ids"
        private const val LOGITS_OUTPUT = "logits"

        private const val HIDDEN_FRAMES = 1_500
        private const val HIDDEN_DIM = 1_280

        private val KINDS = arrayOf("decoder.key", "decoder.value", "encoder.key", "encoder.value")
        private val LAYER_PATTERN = Regex("""present\.(\d+)\.""")
    }
}
