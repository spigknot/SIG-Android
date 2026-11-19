package br.gov.sp.pcsp.launcher

import android.content.Context
import android.os.Debug
import android.os.PowerManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * R109/T03 — latência por etapa no modo do PRODUTO (timestamps + prompt de 3
 * tokens), rota HTP, com o pipeline real:
 *
 * `WhisperOrtWav.ler` → [WhisperOrtFrontend.logMel] → [WhisperOrtHtpRota.abrir]
 * (encoder EPContext W8A16 + decoders CPU) → [WhisperOrtTranscriber] →
 * [WhisperOrtGenerator].
 *
 * **Contrato de compatibilidade (R105):** este arquivo usa SOMENTE APIs
 * presentes em `9e75bf6` e no HEAD da `npu/r109-frontend-busca` — assim o MESMO
 * APK de teste roda contra os dois APKs de produto. Nada de membro novo da R109
 * aqui (o `suprimidoBase` e o `pelaPolitica` são internos do JVM test).
 *
 * Por clipe, em NANOSSEGUNDOS (a conversão fica para o relatório):
 * - `frontend_ns`  = `transcript.featuresMillis` (relógio do próprio produto);
 * - `encoder_ns`, `start_ns`, `step_soma_ns` e `step_chamadas` pelo backend
 *   instrumentado ([BackendMedido]);
 * - `residuo_ns` = parede − (frontend + encoder + start + soma dos step);
 * - `tokens`, `tokens_ts` e `janelas`;
 * - `pss_antes_kb`/`pss_depois_kb` (uma leitura de cada lado), `rss_pico_kb`
 *   (`VmHWM` zerado por clipe), `t_antes_c`/`t_depois_c` e o
 *   `thermal_status` do Android.
 *
 * O texto NÃO vai para o logcat: a linha de log leva só o id. O texto vai para o
 * arquivo de saída (`-e r109_saida`), porque a linha de base da R110 precisa
 * conferir o modo do produto nos mesmos clipes.
 *
 * Uso:
 * `am instrument -w -e class br.gov.sp.pcsp.launcher.WhisperR109LatenciaTest \
 *   -e r109_lista <arquivo> -e r109_saida <arquivo> -e r109_braco A|B \
 *   -e r109_aquecimentos 1 br.gov.sp.pcsp.launcher.test/androidx.test.runner.AndroidJUnitRunner`
 */
@RunWith(AndroidJUnit4::class)
class WhisperR109LatenciaTest {

    private companion object {
        const val TAG = "R109-LAT"
        const val SCHEMA = "sig-whisper-r109-latencia/v1"
        const val DIR_MODELO = "ort-model"
    }

    private val ctx: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val args
        get() = InstrumentationRegistry.getArguments()

    private val saida: File
        get() = File(args.getString("r109_saida") ?: File(ctx.filesDir, "r109/latencia.jsonl").absolutePath)

    private fun linha(json: JSONObject) {
        saida.parentFile?.mkdirs()
        saida.appendText(json.toString() + "\n")
    }

    /** Lista de áudios: `-e r109_lista <arquivo>` com um caminho por linha. */
    private fun audios(): List<File> {
        val lista = args.getString("r109_lista")?.let { File(it) } ?: return emptyList()
        if (!lista.isFile) return emptyList()
        return lista.readLines().map { it.trim() }.filter { it.isNotEmpty() }.map { File(it) }
            .filter { it.isFile }
    }

    /** Pasta da versão ativa do pacote ORT (`ort-model/active` → `versions/<chave>`). */
    private fun dirPacote(): File? {
        val raiz = File(ctx.filesDir, DIR_MODELO)
        val ativo = File(raiz, "active")
        if (!ativo.isFile) return null
        val chave = ativo.readText(Charsets.UTF_8).trim()
        if (chave.isEmpty()) return null
        val dir = File(File(raiz, "versions"), chave)
        return if (dir.isDirectory) dir else null
    }

    private fun modeloDoPacote(dir: File): String? = runCatching {
        JSONObject(File(dir, "pacote-validado.json").readText(Charsets.UTF_8))
            .getJSONObject("identity").getString("model")
    }.getOrNull()

    private fun tokenizerDe(dir: File, modelo: String): WhisperOrtTokenizer {
        val vocab = JSONObject(File(dir, "vocab.json").readText(Charsets.UTF_8))
        val mapa = HashMap<String, Int>(vocab.length())
        for (chave in vocab.keys()) mapa[chave] = vocab.getInt(chave)
        return WhisperOrtTokenizer.fromVocab(mapa, WhisperOrtModelCatalog.especiaisPara(modelo))
    }

    /** Política do PRODUTO: prompt de 3 tokens e modo temporal (`WhisperOrtRoute`). */
    private fun politicaDe(modelo: String, config: WhisperOrtGenerationConfig): WhisperOrtGenerationPolicy {
        val ids = WhisperOrtModelCatalog.taskIdsPara(modelo)
        return WhisperOrtGenerationPolicy(
            prompt = intArrayOf(
                WhisperOrtPrompt.START_OF_TRANSCRIPT,
                WhisperOrtPrompt.LANGUAGE_PT,
                ids.transcribe,
            ),
            config = config,
            timestampMode = true,
            transcribeTokenId = ids.transcribe,
            translateTokenId = ids.translate,
            noTimestampsTokenId = ids.noTimestamps,
        )
    }

    /** Temperatura do SoC: maior zona legível de `/sys/class/thermal`. */
    private fun temperaturaC(): Double {
        var maior = Double.NaN
        val raiz = File("/sys/class/thermal")
        val zonas = raiz.listFiles() ?: return maior
        for (zona in zonas) {
            if (!zona.name.startsWith("thermal_zone")) continue
            val bruto = runCatching { File(zona, "temp").readText().trim().toLong() }.getOrNull() ?: continue
            val celsius = if (bruto > 2_000) bruto / 1000.0 else bruto.toDouble()
            if (celsius.isFinite() && (maior.isNaN() || celsius > maior)) maior = celsius
        }
        return maior
    }

    private fun thermalStatus(): Int = runCatching {
        (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus
    }.getOrDefault(-1)

    /**
     * Backend instrumentado: mede o encoder, o `start` e CADA `step` em
     * nanossegundos, sem tocar no backend real.
     */
    private class BackendMedido(private val real: WhisperOrtBackend) : WhisperOrtBackend {
        val encoderNs = AtomicLong()
        val startNs = AtomicLong()
        val stepNs = AtomicLong()
        val stepChamadas = AtomicLong()
        val encoderChamadas = AtomicLong()
        val startChamadas = AtomicLong()

        override fun encode(logMel: FloatArray): FloatArray {
            val t0 = System.nanoTime()
            val saida = real.encode(logMel)
            encoderNs.addAndGet(System.nanoTime() - t0)
            encoderChamadas.incrementAndGet()
            return saida
        }

        override fun start(hidden: FloatArray, prompt: IntArray): FloatArray {
            val t0 = System.nanoTime()
            val saida = real.start(hidden, prompt)
            startNs.addAndGet(System.nanoTime() - t0)
            startChamadas.incrementAndGet()
            return saida
        }

        override fun step(token: Int): FloatArray {
            val t0 = System.nanoTime()
            val saida = real.step(token)
            stepNs.addAndGet(System.nanoTime() - t0)
            stepChamadas.incrementAndGet()
            return saida
        }

        override fun close() = real.close()

        override fun reopen(): Boolean = real.reopen()

        override val rota: String get() = real.rota

        override val melBinsDetectados: Int get() = real.melBinsDetectados
    }

    /**
     * Medida de memória NÃO INVASIVA.
     *
     * Antes havia uma thread chamando `Debug.getPss()` a cada 50 ms durante a
     * inferência. `getPss()` percorre `/proc/self/smaps_rollup` e toma o
     * `mmap_sem`; o `fastrpc`/QNN mapeia memória compartilhada para o DSP pelo
     * mesmo caminho, e o processo morria ou travava depois de 3–4 clipes (a rota
     * do produto, sem sampler, durou 138). Aqui:
     *
     * - `pss` é lido UMA vez antes e UMA depois de cada clipe (barato, fora da
     *   janela de inferência);
     * - `rss_pico_kb` vem de `VmHWM` (high-water mark do kernel), zerado antes de
     *   cada clipe por `/proc/self/clear_refs` — é o PICO de verdade, sem
     *   amostragem e sem lock.
     */
    private fun pssKb(): Long = runCatching { Debug.getPss().toLong() }.getOrDefault(-1L)

    private fun zerarPicoRss() {
        runCatching { File("/proc/self/clear_refs").writeText("5\n") }
    }

    private fun rssPicoKb(): Long = runCatching {
        File("/proc/self/status").readLines()
            .firstOrNull { it.startsWith("VmHWM:") }
            ?.split(Regex("\\s+"))?.getOrNull(1)?.toLong() ?: -1L
    }.getOrDefault(-1L)

    @Test
    fun latencia() {
        val braco = args.getString("r109_braco") ?: "?"
        val aquecimentos = (args.getString("r109_aquecimentos") ?: "1").toInt()
        val fontes = audios()
        val dir = dirPacote()
        val modelo = dir?.let { modeloDoPacote(it) }
        val nativo = NativeDependencyManager.activateIfInstalled(ctx)
        val ligado = WhisperOrtRoute.isEnabled(ctx)
        WhisperOrtHtpRota.iniciar(ctx)
        val rotaPedida = WhisperOrtHtpRota.rotaDoPedido()
        val ok = nativo && dir != null && modelo != null && fontes.isNotEmpty() && ligado
        linha(
            JSONObject()
                .put("schema", SCHEMA)
                .put("id", "PRE")
                .put("braco", braco)
                .put("outcome", if (ok) "PRE_OK" else "PRE_CONDICAO")
                .put("nativo", nativo)
                .put("pacote", dir?.absolutePath ?: "")
                .put("modelo", modelo ?: "")
                .put("clipes", fontes.size)
                .put("motor_ort_ligado", ligado)
                .put("rota_pedida", rotaPedida)
                .put("pid", android.os.Process.myPid()),
        )
        assertTrue(
            "PRE_CONDICAO: nativo=$nativo pacote=$dir modelo=$modelo ligado=$ligado clipes=${fontes.size}",
            ok,
        )
        requireNotNull(dir)
        requireNotNull(modelo)
        assertTrue(
            "PRE_CONDICAO: rota pedida=$rotaPedida (esperado htp)",
            rotaPedida == WhisperOrtHtpAdmissao.ROTA_HTP,
        )

        assertTrue(
            "PRE_CONDICAO: carregar o runtime ORT",
            NativeDependencyManager.loadOrtRuntime(ctx).isSuccess,
        )

        val config = WhisperOrtGenerationConfig.parse(
            File(dir, "generation_config.json").readText(Charsets.UTF_8),
        )
        val tokenizer = tokenizerDe(dir, modelo)
        val policy = politicaDe(modelo, config)
        val primeiroTs = WhisperOrtSegments.timestampBegin(config.noTimestampsTokenId)
        val frontend = WhisperOrtFrontend(WhisperOrtModelCatalog.filtrosMelOuDefault(modelo))
        val backend = WhisperOrtHtpRota.abrir(
            File(dir, "encoder_model_int8.onnx"),
            File(dir, "decoder_model_int8.onnx"),
            File(dir, "decoder_with_past_model_int8.onnx"),
            WhisperOrtSession.DEFAULT_THREADS,
        )

        try {
            // Aquecimento: mesma janela, descartada (a 1ª inferência do HTP é mais lenta).
            if (aquecimentos > 0 && fontes.isNotEmpty()) {
                val quente = (WhisperOrtWav.ler(fontes[0].readBytes()) as? WhisperOrtWav.Leitura.Pcm)
                if (quente != null) {
                    repeat(aquecimentos) {
                        val medido = BackendMedido(backend)
                        val transcriber = WhisperOrtTranscriber(tokenizer, policy, frontend)
                        runCatching { transcriber.transcribe(medido, quente.amostras) }
                    }
                    Log.i(TAG, "aquecimento=$aquecimentos pid=${android.os.Process.myPid()}")
                }
            }

            for (fonte in fontes) {
                val leitura = WhisperOrtWav.ler(fonte.readBytes())
                val pcm = (leitura as? WhisperOrtWav.Leitura.Pcm)?.amostras
                if (pcm == null) {
                    linha(
                        JSONObject().put("schema", SCHEMA).put("id", fonte.nameWithoutExtension)
                            .put("braco", braco).put("outcome", "PCM_NAO_WAV").put("detalhe", leitura.toString()),
                    )
                    continue
                }
                val id = fonte.nameWithoutExtension
                val medido = BackendMedido(backend)
                val transcriber = WhisperOrtTranscriber(tokenizer, policy, frontend)
                zerarPicoRss()
                val pssAntes = pssKb()
                val tAntes = temperaturaC()
                val statusAntes = thermalStatus()
                val inicio = System.nanoTime()
                val transcript = transcriber.transcribe(medido, pcm)
                val paredeNs = System.nanoTime() - inicio
                val pssDepois = pssKb()
                val rssPico = rssPicoKb()
                val tDepois = temperaturaC()
                val statusDepois = thermalStatus()

                val frontendNs = transcript.featuresMillis * 1_000_000L
                val encoderNs = medido.encoderNs.get()
                val startNs = medido.startNs.get()
                val stepNs = medido.stepNs.get()
                val residuoNs = paredeNs - (frontendNs + encoderNs + startNs + stepNs)
                val tokens = transcript.windows.sumOf { it.generation.tokens.size }
                val tokensTs = transcript.windows.sumOf { janela ->
                    janela.generation.tokens.count { it >= primeiroTs }
                }
                val json = JSONObject()
                    .put("schema", SCHEMA)
                    .put("id", id)
                    .put("braco", braco)
                    .put("outcome", transcript.outcome.name)
                    .put("stop", transcript.stop.name)
                    .put("rota", medido.rota)
                    .put("janelas", transcript.windows.size)
                    .put("parede_ns", paredeNs)
                    .put("transcriber_ns", transcript.totalMillis * 1_000_000L)
                    .put("frontend_ns", frontendNs)
                    .put("encoder_ns", encoderNs)
                    .put("start_ns", startNs)
                    .put("step_soma_ns", stepNs)
                    .put("residuo_ns", residuoNs)
                    .put("step_chamadas", medido.stepChamadas.get())
                    .put("encoder_chamadas", medido.encoderChamadas.get())
                    .put("start_chamadas", medido.startChamadas.get())
                    .put("tokens", tokens)
                    .put("tokens_ts", tokensTs)
                    .put("audio_ms", transcript.audioMillis)
                    .put("pss_antes_kb", pssAntes)
                    .put("pss_depois_kb", pssDepois)
                    .put("rss_pico_kb", rssPico)
                    .put("t_antes_c", if (tAntes.isNaN()) JSONObject.NULL else tAntes)
                    .put("t_depois_c", if (tDepois.isNaN()) JSONObject.NULL else tDepois)
                    .put("thermal_antes", statusAntes)
                    .put("thermal_depois", statusDepois)
                    .put("texto", transcript.text)
                linha(json)
                // Logcat: só o id e os tempos — nunca o texto.
                Log.i(
                    TAG,
                    "clipe id=$id braco=$braco parede_ms=${paredeNs / 1_000_000} " +
                        "tokens=$tokens janelas=${transcript.windows.size} rota=${medido.rota}",
                )
            }
        } finally {
            runCatching { backend.close() }
        }
        Log.i(TAG, "fim braco=$braco clipes=${fontes.size} pid=${android.os.Process.myPid()}")
    }
}
