package br.gov.sp.pcsp.launcher

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtEpDevice
import ai.onnxruntime.OrtSession
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.FloatBuffer

/**
 * R16/§1.3 e §2.2 — backend HÍBRIDO do laboratório: **encoder QNN GPU em 6
 * segmentos** + **decoders INT8 em CPU**.
 *
 * Por que assim: o contrato do motor é [WhisperOrtBackend] (`encode`/`start`/
 * `step`/`close`/`reopen`). O decode já está aceito na fase CPU, então o híbrido
 * **não reimplementa decode**: delega `start`/`step`/`close`/`reopen` à sessão CPU
 * e sobrescreve **apenas** `encode`, encadeando os segmentos no QNN GPU.
 *
 * Três correções obrigatórias em relação à R15 (que travava no 4º segmento):
 * 1. **Plugin QNN EP desacoplado** — `registerExecutionProviderLibrary` +
 *    `addExecutionProvider(listOf(device), …)`, **nunca** `addQnn(...)`: o runtime
 *    precisa ser o conjunto R3–R10 (`libonnxruntime_providers_qnn.so`).
 * 2. **Ciclo de vida estrito por segmento** — extrai o `FloatArray` e fecha, nesta
 *    ordem, `Result` → tensor → sessão → `SessionOptions` →
 *    `unregisterExecutionProviderLibrary`, descarregando `libQnnGpu.so` e
 *    liberando os buffers OpenCL/KGSL antes do próximo segmento.
 * 3. **Telemetria de memória** — [MetricasSegmento] registra `VmRSS` antes/depois
 *    de cada segmento, além de `loadMs`, `runMs` e `closeMs`.
 *
 * Cadeia de tensores (nomes publicados pela R10):
 * `input_features [1,128,3000]` -> `/Add_2_output_0` -> `/layers.0/Add_1_output_0`
 * -> `/layers.7/Add_1_output_0` -> `/layers.15/…` -> `/layers.23/…` ->
 * `last_hidden_state [1,1500,1280]`.
 *
 * Cancelamento: [cancelado] é consultado **antes de cada segmento**; `close()` não
 * corre com `session.run` em voo (o `encode` é síncrono).
 */
class WhisperOrtHybridBackend(
    private val env: OrtEnvironment,
    private val cpu: WhisperOrtBackend,
    private val segmentDir: File,
    private val config: Configuracao,
    private val cancelado: () -> Boolean = { false },
    private val segmentos: List<String> = SEGMENTOS_4,
) : WhisperOrtBackend {

    /** Caminhos e identidade do runtime QNN desacoplado (R3–R10). */
    data class Configuracao(
        val pluginQnnPath: File,
        val backendGpuPath: File,
        val epRegistrationName: String = "qnn",
        val threadsCpu: Int = 6,
        val profilingDir: File? = null,
        /**
         * R18/§1.2 — partição mista: `null` = todos os segmentos no QNN GPU;
         * um conjunto = só esses vão para a GPU e os demais abrem sessão no EP
         * CPU padrão do ORT. Serve para caber no teto de ~1,81 GB de pesos GPU.
         */
        val segmentosGpu: Set<String>? = null,
    )

    /** Telemetria de um segmento, para o recibo de métricas. */
    data class MetricasSegmento(
        val nome: String,
        val loadMs: Long,
        val runMs: Long,
        val closeMs: Long,
        val vmRssAntesKb: Long,
        val vmRssDepoisKb: Long,
        val qnnDevice: String,
        /** R17/§1.2 — `libQnnGpu.so` saiu de `/proc/self/maps` após o unregister? */
        val qnnGpuDescarregado: Boolean,
    ) {
        fun json(): JSONObject = JSONObject()
            .put("segmento", nome)
            .put("load_ms", loadMs)
            .put("run_ms", runMs)
            .put("close_ms", closeMs)
            .put("vm_rss_antes_kb", vmRssAntesKb)
            .put("vm_rss_depois_kb", vmRssDepoisKb)
            .put("qnn_device", qnnDevice)
            .put("qnn_gpu_descarregado", qnnGpuDescarregado)
    }

    private val metricas = ArrayList<MetricasSegmento>()
    private var libsCarregadas = false

    /**
     * R16 — observador de eventos do ciclo de vida (`antes_open`, `depois_open`,
     * `antes_run`, `depois_run`, `antes_close`, `depois_close`). Existe para o
     * ensaio conseguir dizer em QUAL chamada o 4º segmento trava — a métrica
     * sozinha só aparece depois do `run`.
     */
    @Volatile var observador: ((evento: String, nome: String) -> Unit)? = null

    /** Nome do hardware efetivo: GPU no encoder, CPU no decoder. */
    val hardwareLabel: String get() = "GPU (QNN enc) + CPU (dec)"

    override val melBinsDetectados: Int get() = cpu.melBinsDetectados

    /** Métricas acumuladas, na ordem de execução. */
    fun metricasAcumuladas(): List<MetricasSegmento> = metricas.toList()

    fun metricasJson(): JSONArray = JSONArray(metricas.map { it.json() })

    fun limparMetricas() = metricas.clear()

    /**
     * Carrega as 5 bibliotecas do runtime desacoplado e registra o plugin do QNN
     * EP. Idempotente: chamadas seguintes reaproveitam o que já está carregado.
     */
    fun prepararRuntime(): List<OrtEpDevice> {
        if (!libsCarregadas) {
            val dir = config.pluginQnnPath.parentFile
            // R17/§1.2 — só as libs JNI/System vão por System.load. As do plugin e
            // do backend (libonnxruntime_providers_qnn.so, libQnnGpu.so) ficam por
            // conta de registerExecutionProviderLibrary/backend_path, senão o
            // dlopen refcount nunca chega a zero e o dlclose não descarrega.
            for (nome in LIBS_JNI_ORT) {
                val lib = File(dir, nome)
                require(lib.isFile) { "lib do runtime desacoplado ausente: ${lib.absolutePath}" }
                runCatching { System.load(lib.absolutePath) }
            }
            libsCarregadas = true
        }
        runCatching {
            env.registerExecutionProviderLibrary(
                config.epRegistrationName,
                config.pluginQnnPath.absolutePath,
            )
        }
        return env.epDevices.filter { d ->
            d.epName.lowercase().let { it.contains("qnn") || it.contains("qualcomm") }
        }
    }

    /**
     * Executa UM segmento com ciclo de vida estrito (R16/§2.2) e devolve a saída
     * achatada. Lança [WhisperOrtHybridCancelado] antes de abrir qualquer recurso
     * quando o cancelamento já foi pedido.
     */
    fun executarSegmentoUnico(nome: String, entradaPlana: FloatArray, forma: LongArray): FloatArray {
        if (cancelado()) throw WhisperOrtHybridCancelado("cancelado antes de $nome")
        val rssAntes = VmRss.lerKb()
        val usarGpu = config.segmentosGpu == null || nome in config.segmentosGpu
        val devices = if (usarGpu) prepararRuntime() else emptyList()
        if (usarGpu) {
            require(devices.isNotEmpty()) { "QNN EP não expôs device (plugin ausente?)" }
        }

        val arquivo = File(segmentDir, nome)
        require(arquivo.isFile) { "segmento ausente: ${arquivo.absolutePath}" }

        var sessao: OrtSession? = null
        var tensor: OnnxTensor? = null
        var resultado: OrtSession.Result? = null
        var opcoes: OrtSession.SessionOptions? = null
        var loadMs = 0L
        var runMs = 0L
        val tLoad = System.nanoTime()
        try {
            opcoes = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(config.threadsCpu)
                setInterOpNumThreads(1)
                if (usarGpu) {
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT)
                    addExecutionProvider(
                        listOf(devices.first()),
                        buildMap {
                            put("backend_path", config.backendGpuPath.absolutePath)
                            config.profilingDir?.let { dir ->
                                put("profiling_level", "detailed")
                                put("profiling_file_path", File(dir, "$nome.qnn_profile.json").absolutePath)
                            }
                        },
                    )
                } else {
                    // R20/§1.1 — segmento no EP CPU com ALL_OPT (fusão/vetorização
                    // do com.microsoft.Gelu) MAS sem arena e sem memory pattern:
                    // o BFCArena aloca um pool de ~1 GB e NÃO devolve ao SO no
                    // `close()`, o que na R19 manteve VmRSS em 1 240 MB e impediu
                    // o 4º segmento de abrir na GPU.
                    setIntraOpNumThreads(6)
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    setCPUArenaAllocator(false)
                    setMemoryPatternOptimization(false)
                    addConfigEntry("session.use_env_allocators", "0")
                }
            }
            observador?.invoke("antes_open", nome)
            sessao = env.createSession(arquivo.absolutePath, opcoes)
            observador?.invoke("depois_open", nome)
            loadMs = (System.nanoTime() - tLoad) / 1_000_000

            tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(entradaPlana), forma)
            observador?.invoke("antes_run", nome)
            val tRun = System.nanoTime()
            resultado = sessao.run(mapOf(sessao.inputNames.first() to tensor))
            observador?.invoke("depois_run", nome)
            runMs = (System.nanoTime() - tRun) / 1_000_000

            val out = resultado.get(0) as OnnxTensor
            val buffer = out.floatBuffer
            val saida = FloatArray(buffer.remaining()).also { buffer.get(it) }
            if (saida.any { !it.isFinite() }) {
                throw WhisperOrtHybridFalha("$nome produziu NaN/Inf")
            }
            return saida
        } catch (t: Throwable) {
            if (t is WhisperOrtHybridCancelado || t is WhisperOrtHybridFalha) throw t
            throw WhisperOrtHybridFalha("$nome falhou: ${t.message}", t)
        } finally {
            observador?.invoke("antes_close", nome)
            runCatching { resultado?.close() }
            runCatching { tensor?.close() }
            runCatching { sessao?.close() }
            runCatching { opcoes?.close() }
            // R20/§1.1 — libera referências JNI do segmento CPU antes do próximo
            // `createSession` (sem arena, o free direto devolve as páginas ao SO).
            if (!usarGpu) runCatching { System.gc() }
            // R17/§1.2 — mede o descarregamento REAL do backend GPU (só se houve GPU).
            val tinhaGpu = usarGpu && mapasContem(QNN_GPU_LIB)
            if (usarGpu) {
                // R16/§2.2 — descarrega libQnnGpu.so e libera buffers OpenCL/KGSL.
                runCatching { env.unregisterExecutionProviderLibrary(config.epRegistrationName) }
            }
            val gpuDescarregado = tinhaGpu && !mapasContem(QNN_GPU_LIB)
            observador?.invoke("depois_close", nome)
            metricas.add(
                MetricasSegmento(
                    nome = nome,
                    loadMs = loadMs,
                    runMs = runMs,
                    closeMs = (System.nanoTime() - tLoad) / 1_000_000 - loadMs - runMs,
                    vmRssAntesKb = rssAntes,
                    vmRssDepoisKb = VmRss.lerKb(),
                    qnnDevice = if (usarGpu) (devices.firstOrNull()?.epName ?: "qnn") else "cpu",
                    qnnGpuDescarregado = gpuDescarregado,
                )
            )
        }
    }

    /**
     * Cadeia completa: abre/executa/fecha um segmento por vez, reutilizando o
     * plugin registrado. O estado entre segmentos é o `FloatArray` — nunca um
     * tensor de sessão anterior.
     */
    fun encodePorSegmento(logMel: FloatArray): FloatArray {
        val esperado = melBinsDetectados * WhisperOrtFrontend.N_FRAMES
        require(logMel.size == esperado) {
            "log-mel com ${logMel.size} valores; esperado $esperado (mel=${melBinsDetectados})"
        }
        var atual = logMel
        var forma = longArrayOf(1L, melBinsDetectados.toLong(), WhisperOrtFrontend.N_FRAMES.toLong())
        for (nome in segmentos) {
            atual = executarSegmentoUnico(nome, atual, forma)
            forma = longArrayOf(1L, FORMATO_SAIDA_FRAMES, FORMATO_SAIDA_DIM)
        }
        return atual
    }

    override fun encode(logMel: FloatArray): FloatArray = encodePorSegmento(logMel)

    /**
     * R18/§1.2 — os decoders CPU são abertos **aqui**, depois de todos os segmentos
     * do encoder terem rodado e fechado. Antes, `cpu.open()` era chamado pelo teste
     * antes do encode e carregava ~2 GB (incluindo o encoder INT8, que o híbrido
     * nunca usa), consumindo o orçamento de memória antes do 1º segmento GPU.
     */
    override fun start(hidden: FloatArray, prompt: IntArray): FloatArray {
        val sessao = cpu
        if (sessao is WhisperOrtSession && !sessao.isDecodersOpen) {
            sessao.openDecodersOnly(
                melBins = melBinsDetectados,
                frames = hidden.size / (FORMATO_SAIDA_DIM.toInt()),
                dim = FORMATO_SAIDA_DIM.toInt(),
            )
        }
        return cpu.start(hidden, prompt)
    }

    override fun step(token: Int): FloatArray = cpu.step(token)

    override fun close() {
        runCatching { env.unregisterExecutionProviderLibrary(config.epRegistrationName) }
        metricas.clear()
        cpu.close()
    }

    override fun reopen(): Boolean {
        runCatching { close() }
        return runCatching { prepararRuntime().isNotEmpty() }.getOrDefault(false)
    }

    /** `true` quando [nome] aparece em `/proc/self/maps` (lib carregada). */
    private fun mapasContem(nome: String): Boolean = runCatching {
        File("/proc/self/maps").readLines().any { it.contains(nome) }
    }.getOrDefault(false)

    /** Leitura de `VmRSS`/`VmSize`/`VmPeak` de `/proc/self/status`. */
    object VmRss {
        fun lerKb(): Long = runCatching {
            File("/proc/self/status").readLines()
                .firstOrNull { it.startsWith("VmRSS:") }
                ?.trim()?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull() ?: -1L
        }.getOrDefault(-1L)

        fun lerTudo(): Map<String, Long> = runCatching {
            File("/proc/self/status").readLines()
                .filter {
                    it.startsWith("VmRSS:") || it.startsWith("VmSize:") || it.startsWith("VmPeak:")
                }
                .mapNotNull { linha ->
                    val p = linha.trim().split(Regex("\\s+"))
                    val v = p.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
                    p[0].removeSuffix(":") to v
                }.toMap()
        }.getOrDefault(emptyMap())
    }

    companion object {
        /**
         * R17/§1.2 — cadeia de **4 grafos distintos** (frente+camada0+grupo0_7
         * fundidos), abaixo do limite medido de 5 grafos por processo.
         */
        val SEGMENTOS_4 = listOf(
            "r17_frente_a_7.onnx",
            "r10_grupo8_15.onnx",
            "r10_grupo16_23.onnx",
            "r10_grupo24_31.onnx",
        )

        /** R20/§1.2 — partição A: 3 primeiros na GPU, `grupo24_31` na CPU. */
        val SEGMENTOS_4_TRES_GPU_A = setOf(
            "r17_frente_a_7.onnx",
            "r10_grupo8_15.onnx",
            "r10_grupo16_23.onnx",
        )

        /** R20/§1.2 — partição B: GPU -> GPU -> CPU (sem arena) -> GPU. */
        val SEGMENTOS_4_TRES_GPU_B = setOf(
            "r17_frente_a_7.onnx",
            "r10_grupo8_15.onnx",
            "r10_grupo24_31.onnx",
        )

        /**
         * R20/§1.2 — partição C: CPU (sem arena, antes de qualquer alocação QNN)
         * -> GPU -> GPU -> GPU. Os três blocos puros 8..15, 16..23 e 24..31 ficam
         * todos na QNN GPU.
         */
        val SEGMENTOS_4_TRES_GPU_C = setOf(
            "r10_grupo8_15.onnx",
            "r10_grupo16_23.onnx",
            "r10_grupo24_31.onnx",
        )

        /** Nomes dos segmentos da R10 (6 grafos; usada para comparação/histórico). */
        val SEGMENTOS = listOf(
            "r10_frente.onnx",
            "r10_camada0.onnx",
            "r10_grupo0_7.onnx",
            "r10_grupo8_15.onnx",
            "r10_grupo16_23.onnx",
            "r10_grupo24_31.onnx",
        )

        /**
         * R21/§1.1 — Partição 6A sobre os 6 segmentos originais da R10 (sem o grafo
         * fundido `r17_frente_a_7`, que distorce na QNN GPU — R20 §1.2.2):
         * - CPU (sem arena): `r10_frente` (29 MB), `r10_camada0` (79 MB) e
         *   `r10_grupo16_23` (630 MB);
         * - QNN GPU: 3 grafos pesados = 1 811 MB de pesos.
         */
        val SEGMENTOS_6_TRES_GPU_PESADOS = setOf(
            "r10_grupo0_7.onnx",
            "r10_grupo8_15.onnx",
            "r10_grupo24_31.onnx",
        )

        /**
         * R21/§1.1 — Partição 6B: cinco grafos distintos na QNN GPU. É a variante
         * arriscada (a R16 travou no 5º grafo); usada só se a 6A falhar.
         */
        val SEGMENTOS_6_QUATRO_GPU_E_FRENTE = setOf(
            "r10_frente.onnx",
            "r10_camada0.onnx",
            "r10_grupo0_7.onnx",
            "r10_grupo8_15.onnx",
            "r10_grupo24_31.onnx",
        )

        /**
         * R21/Bloco 2 — os mesmos 6 estágios, com os 4 pesados em FP16 compensado
         * (`.data` pela metade, saída bit-idêntica no host). Reduz a residência da
         * GPU de 1 811 MB para ~1 375 MB.
         */
        val SEGMENTOS_FP16 = listOf(
            "r10_frente.onnx",
            "r10_camada0.onnx",
            "r21_r10_grupo0_7_fp16.onnx",
            "r21_r10_grupo8_15_fp16.onnx",
            "r21_r10_grupo16_23_fp16.onnx",
            "r21_r10_grupo24_31_fp16.onnx",
        )

        /** R21/Bloco 2 — partição 6A sobre os segmentos FP16 (3 grafos na GPU). */
        val SEGMENTOS_6_FP16_TRES_GPU = setOf(
            "r21_r10_grupo0_7_fp16.onnx",
            "r21_r10_grupo8_15_fp16.onnx",
            "r21_r10_grupo24_31_fp16.onnx",
        )

        /**
         * R17/§1.2 — bibliotecas carregadas por `System.load`: apenas JNI/runtime.
         * O plugin (`libonnxruntime_providers_qnn.so`) e o backend
         * (`libQnnGpu.so`) NÃO entram aqui: quem os abre e fecha é o ORT
         * (`registerExecutionProviderLibrary` / `backend_path` +
         * `unregisterExecutionProviderLibrary`).
         */
        /** Nome do backend GPU do QNN (usado na telemetria de descarregamento). */
        const val QNN_GPU_LIB = "libQnnGpu.so"

        val LIBS_JNI_ORT = listOf(
            "libonnxruntime.so",
            "libonnxruntime4j_jni.so",
            "libQnnSystem.so",
        )

        /** @deprecated use [LIBS_JNI_ORT]; mantido para os testes da R16. */
        val LIBS_DESACOPLADAS = LIBS_JNI_ORT

        private const val FORMATO_SAIDA_FRAMES = 1500L
        private const val FORMATO_SAIDA_DIM = 1280L
    }
}

/** Cancelamento observado dentro da cadeia híbrida (mapeado para `Cancelled`). */
class WhisperOrtHybridCancelado(mensagem: String) : RuntimeException(mensagem)

/** Falha tipada de segmento (mapeada para `Failed`, nunca fallback silencioso). */
class WhisperOrtHybridFalha(mensagem: String, causa: Throwable? = null) : RuntimeException(mensagem, causa)
