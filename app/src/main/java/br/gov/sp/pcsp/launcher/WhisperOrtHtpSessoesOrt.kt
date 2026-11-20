package br.gov.sp.pcsp.launcher

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * R115 §T02.1 — as três sessões EPContext da rota HTP de ponta a ponta em ORT.
 *
 * As opções são as MESMAS do encoder da R100 (QNN EP estrito, `burst`,
 * `htp_arch` da tabela de SoC, sem fallback do EP para CPU, sem
 * `offload_graph_io_quantization`), agora aplicadas também ao projetor e ao
 * decoder. Manter as opções em UM lugar é o que garante que os três grafos
 * rodem no mesmo backend e na mesma configuração.
 *
 * O decoder implementa a realimentação direta pelo [WhisperOrtFeedbackDireto]:
 * os tensores de saída `k/v_cache_self_i_out` do passo anterior entram como
 * `k/v_cache_self_i_in` do passo seguinte, sem cópia, e o `Result` anterior só
 * é fechado depois do `run`. Se a API recusar, o erro é registrado e o passo
 * refaz em modo cópia — nunca em silêncio.
 */
internal object WhisperOrtHtpSessoesOrt {

    /** Modo de performance do HTP (o mesmo da rota da R100). */
    const val HTP_MODE = "burst"

    /** Cria a sessão EPContext no QNN HTP estrito. */
    fun abrir(env: OrtEnvironment, wrapper: File, dirQairt: File, htpArch: String): OrtSession {
        check(wrapper.isFile) { "wrapper ausente: ${wrapper.name}" }
        val opts = OrtSession.SessionOptions()
        opts.addQnn(
            linkedMapOf(
                "backend_path" to File(File(dirQairt, "lib"), "libQnnHtp.so").absolutePath,
                "htp_arch" to htpArch,
                "device_id" to "0",
                "offload_graph_io_quantization" to "0",
                "enable_htp_fp16_precision" to "0",
                "htp_performance_mode" to HTP_MODE,
            ),
        )
        opts.addConfigEntry("session.disable_cpu_ep_fallback", "1")
        opts.addConfigEntry("session.log_severity_level", "3")
        opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        return env.createSession(wrapper.absolutePath, opts)
    }

    fun projetor(env: OrtEnvironment, sessao: OrtSession): WhisperOrtHtpProjetorSessao =
        WhisperOrtProjetorOrt(env, sessao)

    fun decoder(env: OrtEnvironment, sessao: OrtSession): WhisperOrtHtpDecoderSessao =
        WhisperOrtDecoderOrt(env, sessao)
}

/** Projetor: 1 entrada int16 (hidden) → 8 saídas uint8 (K/V cruzadas). */
internal class WhisperOrtProjetorOrt(
    private val env: OrtEnvironment,
    private val sessao: OrtSession,
) : WhisperOrtHtpProjetorSessao {

    private val nomeEntrada: String by lazy {
        sessao.inputNames.firstOrNull() ?: error("projetor sem entrada")
    }
    private val infoEntrada: TensorInfo by lazy {
        sessao.inputInfo[nomeEntrada]?.info as? TensorInfo
            ?: error("projetor sem TensorInfo de $nomeEntrada")
    }

    init {
        check(sessao.outputNames.size == 8) { "projetor com ${sessao.outputNames.size} saídas" }
    }

    override fun rodar(codigos: ByteArray): Map<String, ByteArray> {
        check(codigos.size.toLong() == infoEntrada.shape.fold(1L) { a, d -> a * d } * 2L) {
            "hidden com ${codigos.size} bytes; esperado ${infoEntrada.shape.toList()}"
        }
        val buffer = ByteBuffer.allocateDirect(codigos.size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(codigos)
        buffer.rewind()
        OnnxTensor.createTensor(env, buffer, infoEntrada.shape, OnnxJavaType.INT16).use { tensor ->
            sessao.run(mapOf(nomeEntrada to tensor)).use { resultado ->
                val saida = LinkedHashMap<String, ByteArray>(8)
                for ((nome, valor) in resultado) {
                    val bytes = (valor as OnnxTensor).byteBuffer.duplicate()
                    bytes.rewind()
                    val copia = ByteArray(bytes.remaining())
                    bytes.get(copia)
                    saida[nome] = copia
                }
                return saida
            }
        }
    }

    override fun close() {
        runCatching { sessao.close() }
    }
}

/** Uma entrada do decoder: buffer direto, forma e tipo fixos do wrapper. */
private class EntradaDecoder(
    val nome: String,
    val buffer: ByteBuffer,
    val forma: LongArray,
    val tipo: OnnxJavaType,
)

/**
 * Decoder O3: 19 entradas (ids, posição, máscara, 8 K/V cruzadas e 8 caches
 * self) e 9 saídas (8 caches self + logits uint16).
 */
internal class WhisperOrtDecoderOrt(
    private val env: OrtEnvironment,
    private val sessao: OrtSession,
) : WhisperOrtHtpDecoderSessao {

    private val entradas = LinkedHashMap<String, EntradaDecoder>()
    /** Entradas que não são cache self: ids, posição, máscara e K/V cruzadas. */
    private val fixos = LinkedHashMap<String, OnnxTensor>()
    private val caches = LinkedHashMap<String, OnnxTensor>()
    private val zeros = LinkedHashMap<String, OnnxTensor>()
    private var paresCache: List<Pair<String, String>> = emptyList()

    private val feedback = WhisperOrtFeedbackDireto<OrtSession.Result, OnnxTensor>(
        saidasParaEntrada = { resultado ->
            val mapa = HashMap<String, OnnxTensor>(paresCache.size)
            for ((saida, entrada) in paresCache) {
                mapa[entrada] = resultado.get(saida).orElseThrow {
                    IllegalStateException("passo anterior sem a saída $saida")
                } as OnnxTensor
            }
            mapa
        },
        fechar = { resultado -> runCatching { resultado.close() } },
    )

    override val modoRealimentacao: String get() = feedback.modo
    override val erroRealimentacao: String? get() = feedback.erro

    init {
        for (nome in sessao.inputNames) {
            val info = sessao.inputInfo[nome]?.info as? TensorInfo
                ?: error("decoder sem TensorInfo de $nome")
            val tipo = when (nome) {
                "input_ids", "position_ids" -> OnnxJavaType.INT32
                "attention_mask" -> OnnxJavaType.INT16 // uint16 bit-compatível (wrapper i16)
                else -> OnnxJavaType.UINT8 // UFIXED_POINT_8 (int8 simétrico)
            }
            val elementos = info.shape.fold(1L) { a, d -> a * d }
            val bytesPor = when (tipo) {
                OnnxJavaType.INT32 -> 4
                OnnxJavaType.INT16 -> 2
                else -> 1
            }
            val buffer = ByteBuffer.allocateDirect((elementos * bytesPor).toInt())
                .order(ByteOrder.LITTLE_ENDIAN)
            entradas[nome] = EntradaDecoder(nome, buffer, info.shape, tipo)
        }
        check(entradas["input_ids"]?.buffer?.capacity() == 4) { "input_ids não é [1,1]" }
        check(entradas["position_ids"]?.buffer?.capacity() == 4) { "position_ids não é [1]" }
        check(entradas["attention_mask"]?.buffer?.capacity() ==
            WhisperOrtHtpContrato.TAMANHO_MASCARA * 2) { "máscara não é [1,1,1,200]" }

        paresCache = sessao.outputNames
            .filter { (it.startsWith("k_cache_self_") || it.startsWith("v_cache_self_")) && it.endsWith("_out") }
            .map { it to it.removeSuffix("_out") + "_in" }
            .filter { it.second in entradas }
        check(paresCache.size == 8) { "caches self: esperados 8, achados ${paresCache.size}" }
        check("logits" in sessao.outputNames) { "decoder sem saída logits" }

        for (e in entradas.values) {
            if (paresCache.any { it.second == e.nome }) continue
            fixos[e.nome] = OnnxTensor.createTensor(env, e.buffer, e.forma, e.tipo)
        }
        for ((_, entrada) in paresCache) {
            val e = entradas[entrada] ?: error("cache self sem entrada $entrada")
            caches[entrada] = OnnxTensor.createTensor(env, e.buffer, e.forma, e.tipo)
            val zerado = ByteBuffer.allocateDirect(e.buffer.capacity()).order(ByteOrder.LITTLE_ENDIAN)
            zeros[entrada] = OnnxTensor.createTensor(env, zerado, e.forma, e.tipo)
        }
    }

    override fun abrir(cruzadas: Map<String, ByteArray>) {
        feedback.limpar()
        check(cruzadas.size == 8) { "projetor devolveu ${cruzadas.size} K/V cruzadas" }
        for ((nome, valor) in cruzadas) {
            val entrada = entradas[nome] ?: error("decoder não tem entrada $nome")
            check(valor.size == entrada.buffer.capacity()) {
                "$nome com ${valor.size} bytes; esperado ${entrada.buffer.capacity()}"
            }
            val destino = entrada.buffer
            destino.clear()
            destino.put(valor)
            destino.rewind()
        }
        val mascara = entradas.getValue("attention_mask").buffer
        mascara.clear()
        for (i in 0 until WhisperOrtHtpContrato.TAMANHO_MASCARA) mascara.putShort(i * 2, 0)
        mascara.rewind()
        posicaoAberta = 0
    }

    private var posicaoAberta = 0

    override fun passo(token: Int, posicao: Int): ByteArray {
        check(posicao == posicaoAberta) { "passo fora de ordem: $posicao != $posicaoAberta" }
        val ids = entradas.getValue("input_ids").buffer
        ids.clear()
        ids.putInt(0, token)
        ids.rewind()
        val pos = entradas.getValue("position_ids").buffer
        pos.clear()
        pos.putInt(0, posicao)
        pos.rewind()
        val mascara = entradas.getValue("attention_mask").buffer
        mascara.putShort(
            (WhisperOrtHtpContrato.TAMANHO_MASCARA - 1 - posicao) * 2,
            WhisperOrtHtpContrato.MASCARA_VALIDO.toShort(),
        )

        val (saidas, origem) = feedback.entradas()
        val feeds = montarFeeds(saidas)
        val resultado = try {
            sessao.run(feeds)
        } catch (falha: Throwable) {
            if (feedback.modo != WhisperOrtFeedbackDireto.DIRETO) throw falha
            // A API recusou a realimentação direta: registra e refaz copiando.
            feedback.recusar("${falha.javaClass.simpleName}: ${falha.message}")
            sessao.run(montarFeeds(feedback.entradas().first))
        }
        feedback.concluir(resultado, origem)
        posicaoAberta = posicao + 1
        val logits = resultado.get("logits").orElseThrow {
            IllegalStateException("decoder sem saída logits no resultado")
        } as OnnxTensor
        val bytes = logits.byteBuffer.duplicate()
        bytes.rewind()
        val copia = ByteArray(bytes.remaining())
        bytes.get(copia)
        return copia
    }

    /**
     * Feeds do passo. Modo direto usa os tensores do resultado anterior como
     * entrada; modo cópia copia os bytes para os buffers e usa os tensores deles.
     */
    private fun montarFeeds(saidas: Map<String, OnnxTensor>): Map<String, OnnxTensor> {
        val feeds = HashMap<String, OnnxTensor>(fixos.size + 8)
        feeds.putAll(fixos)
        if (saidas.isEmpty()) {
            feeds.putAll(zeros)
            return feeds
        }
        if (feedback.modo == WhisperOrtFeedbackDireto.DIRETO) {
            feeds.putAll(saidas)
        } else {
            for ((entrada, tensor) in saidas) {
                val destino = entradas.getValue(entrada).buffer
                val bytes = tensor.byteBuffer.duplicate()
                bytes.rewind()
                destino.clear()
                destino.put(bytes)
                destino.rewind()
            }
            feeds.putAll(caches)
        }
        return feeds
    }

    override fun close() {
        feedback.limpar()
        zeros.values.forEach { runCatching { it.close() } }
        caches.values.forEach { runCatching { it.close() } }
        fixos.values.forEach { runCatching { it.close() } }
        runCatching { sessao.close() }
    }
}
