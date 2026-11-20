package br.gov.sp.pcsp.launcher

import android.content.Context
import android.os.Debug
import android.os.PowerManager
import androidx.test.core.app.ActivityScenario
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * R115/T04 — FUMAÇA no aparelho com o BUILD DE PRODUTO e o pacote v3.
 *
 * Três modos, no MESMO pipeline do produto
 * (`WhisperOrtWav.ler` → [WhisperOrtFrontend.logMel] → [WhisperOrtHtpRota.abrir]
 * → [WhisperOrtTranscriber] → [WhisperOrtGenerator]):
 *
 * - `qualidade`: os 28 clipes da R113, comparados com os tokens do braço Q′ da
 *   R114 (esperado 28/28 idênticos);
 * - `latencia`: os clipes 2–8 em DUAS sessões, com a janela limpa como na R114
 *   (frontend + encoder + prompt + passos), mediana esperada perto de 830 ms;
 * - `volta`: limite ARTIFICIAL de passos HTP ([WhisperOrtHtpRota.limiteHtpParaTeste])
 *   para forçar a volta da janela inteira para o CPU e provar que os decoders
 *   CPU só carregam ali.
 *
 * Privacidade: o texto/ids vão SÓ para o arquivo apontado por `-e r115_saida`;
 * o logcat leva uma linha por clipe com id, rota e contagem — nunca texto.
 *
 * Args: `r115_modo`, `r115_lista`, `r115_saida`, `r115_referencia` (jsonl Q′ da
 * R114), `r115_limite`, `r115_aquecimentos`.
 */
@RunWith(AndroidJUnit4::class)
class WhisperNpuR115FumacaTest {

    private companion object {
        const val TAG = "R115-FUMACA"
        const val SCHEMA = "sig-whisper-npu-r115-fumaca/v1"
        const val DIR_MODELO = "ort-model"
    }

    private val ctx: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val args
        get() = InstrumentationRegistry.getArguments()

    private val saida: File
        get() = File(args.getString("r115_saida") ?: File(ctx.filesDir, "r115/fumaca.jsonl").absolutePath)

    private fun linha(json: JSONObject) {
        saida.parentFile?.mkdirs()
        saida.appendText(json.toString() + "\n")
    }

    private fun pares(): List<Pair<String, File>> {
        val lista = args.getString("r115_lista")?.let { File(it) } ?: return emptyList()
        if (!lista.isFile) return emptyList()
        return lista.readLines().map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull { linha ->
            val partes = linha.split("\t")
            val id = partes[0].trim()
            val caminho = partes.getOrNull(1)?.trim().orEmpty().ifEmpty { linha }
            val arquivo = File(caminho)
            if (id.isEmpty() || !arquivo.isFile) null else id to arquivo
        }
    }

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

    /** Backend instrumentado: acumula ns por etapa e contadores da rota. */
    private class BackendMedido(private val real: WhisperOrtBackend) : WhisperOrtBackend {
        val encoderNs = AtomicLong()
        val startNs = AtomicLong()
        val stepNs = AtomicLong()
        val stepChamadas = AtomicLong()
        val frontendNs = AtomicLong()
        val encodes = AtomicLong()

        override fun encode(logMel: FloatArray): FloatArray {
            val t0 = System.nanoTime()
            val saida = real.encode(logMel)
            encoderNs.addAndGet(System.nanoTime() - t0)
            encodes.incrementAndGet()
            return saida
        }

        override fun start(hidden: FloatArray, prompt: IntArray): FloatArray {
            val t0 = System.nanoTime()
            val saida = real.start(hidden, prompt)
            startNs.addAndGet(System.nanoTime() - t0)
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

    private fun pssKb(): Long = runCatching { Debug.getPss().toLong() }.getOrDefault(-1L)

    private fun zerarPicoRss() {
        runCatching { File("/proc/self/clear_refs").writeText("5\n") }
    }

    private fun rssPicoKb(): Long = runCatching {
        File("/proc/self/status").readLines()
            .firstOrNull { it.startsWith("VmHWM:") }
            ?.split(Regex("\\s+"))?.getOrNull(1)?.toLong() ?: -1L
    }.getOrDefault(-1L)

    /** Tokens do braço Q′ da R114, por id (referência da qualidade). */
    private fun referenciaQp(): Map<String, String> {
        val caminho = args.getString("r115_referencia")?.let { File(it) } ?: return emptyMap()
        if (!caminho.isFile) return emptyMap()
        val mapa = HashMap<String, String>()
        for (texto in caminho.readLines()) {
            if (texto.isBlank()) continue
            runCatching {
                val o = JSONObject(texto)
                if (o.optString("braco") == "QP" && o.optString("tipo") == "identidade") {
                    val id = o.optString("id")
                    val tokens = o.optString("tokens")
                    if (id.isNotEmpty() && tokens.isNotEmpty()) mapa[id] = tokens.trim(',')
                }
            }
        }
        return mapa
    }

    /**
     * Tela acesa durante a célula: sem isso o Android CONGELA o processo em
     * segundo plano (`do_freezer_trap`) e a inferência para no meio da lista.
     * É um wake lock do PRÓPRIO app de teste, não configuração do sistema.
     */
    private fun manterTelaAcesa(): PowerManager.WakeLock? = runCatching {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "SIG:R115Fumaca",
        ).apply { acquire(90 * 60_000L) }
    }.getOrNull()

    @Test
    fun fumaca() {
        val lock = manterTelaAcesa()
        // Tela do produto em primeiro plano durante a célula: o ColorOS congela
        // processo em segundo plano (`do_freezer_trap`) mesmo com a tela acesa, e
        // a inferência pararia no meio da lista. A Activity fica só aberta.
        val cenario = ActivityScenario.launch(WhisperActivity::class.java)
        try {
            fumacaInterna()
        } finally {
            runCatching { cenario.close() }
            runCatching { lock?.release() }
        }
    }

    private fun fumacaInterna() {
        val modo = args.getString("r115_modo") ?: "qualidade"
        val limite = args.getString("r115_limite")?.toIntOrNull()
        val aquecimentos = (args.getString("r115_aquecimentos") ?: "0").toInt()
        val fontes = pares()
        val dir = dirPacote()
        val modelo = dir?.let { modeloDoPacote(it) }
        val nativo = NativeDependencyManager.activateIfInstalled(ctx)
        val ligado = WhisperOrtRoute.isEnabled(ctx)
        WhisperOrtHtpRota.iniciar(ctx)
        WhisperOrtHtpRota.limiteHtpParaTeste = limite
        val rotaPedida = WhisperOrtHtpRota.rotaDoPedido()
        val estado = WhisperOrtNpuPacote.estado(ctx)
        val pacote = (estado as? WhisperOrtNpuPacote.Estado.Pronto)?.manifesto?.versao ?: "nenhum"
        val ok = nativo && dir != null && modelo != null && fontes.isNotEmpty() && ligado
        linha(
            JSONObject()
                .put("schema", SCHEMA)
                .put("tipo", "pre")
                .put("modo", modo)
                .put("outcome", if (ok) "PRE_OK" else "PRE_CONDICAO")
                .put("nativo", nativo)
                .put("modelo", modelo ?: "")
                .put("clipes", fontes.size)
                .put("motor_ort_ligado", ligado)
                .put("rota_pedida", rotaPedida)
                .put("pacote_versao", pacote)
                .put("limite_teste", limite ?: -1)
                .put("pid", android.os.Process.myPid()),
        )
        Log.i(TAG, "processo pid=${android.os.Process.myPid()} modo=$modo pacote=$pacote rota=$rotaPedida")
        assertTrue("PRE_CONDICAO: nativo=$nativo pacote=$dir modelo=$modelo ligado=$ligado clipes=${fontes.size}", ok)
        requireNotNull(dir)
        requireNotNull(modelo)
        assertTrue("PRE_CONDICAO: rota pedida=$rotaPedida", rotaPedida == WhisperOrtHtpAdmissao.ROTA_HTP)
        assertTrue(
            "PRE_CONDICAO: v3 verificado (rota de ponta a ponta); achado=$pacote",
            pacote == WhisperOrtNpuPacote.VERSAO,
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
        val frontend = WhisperOrtFrontend(WhisperOrtModelCatalog.filtrosMelOuDefault(modelo))
        val backend = WhisperOrtHtpRota.abrir(
            File(dir, "encoder_model_int8.onnx"),
            File(dir, "decoder_model_int8.onnx"),
            File(dir, "decoder_with_past_model_int8.onnx"),
            WhisperOrtSession.DEFAULT_THREADS,
        )
        val loop = backend as? WhisperOrtHtpLoopBackend

        try {
            if (aquecimentos > 0 && fontes.isNotEmpty()) {
                val quente = (WhisperOrtWav.ler(fontes[0].second.readBytes()) as? WhisperOrtWav.Leitura.Pcm)
                if (quente != null) {
                    repeat(aquecimentos) {
                        runCatching {
                            WhisperOrtTranscriber(tokenizer, policy, frontend)
                                .transcribe(BackendMedido(backend), quente.amostras)
                        }
                    }
                    Log.i(TAG, "aquecimento=$aquecimentos pid=${android.os.Process.myPid()}")
                }
            }

            val referencia = referenciaQp()
            var identicos = 0
            var comparados = 0
            var voltasTotal = 0
            var passosCpuTotal = 0
            var cpuCarregadoAlgum = false
            var falhaFeedbackAlguma = ""
            for ((indice, par) in fontes.withIndex()) {
                val (id, arquivo) = par
                val leitura = WhisperOrtWav.ler(arquivo.readBytes())
                val pcm = (leitura as? WhisperOrtWav.Leitura.Pcm)?.amostras
                if (pcm == null) {
                    linha(JSONObject().put("schema", SCHEMA).put("tipo", "clipe").put("id", id)
                        .put("outcome", "PCM_NAO_WAV").put("detalhe", leitura.toString()))
                    continue
                }
                val medido = BackendMedido(backend)
                val transcriber = WhisperOrtTranscriber(tokenizer, policy, frontend)
                val voltasAntes = loop?.voltasCpu ?: -1
                val cpuAntes = loop?.decodersCpuCarregados() ?: false
                zerarPicoRss()
                val pssAntes = pssKb()
                val inicio = System.nanoTime()
                val transcript = transcriber.transcribe(medido, pcm)
                val paredeNs = System.nanoTime() - inicio
                val pssDepois = pssKb()
                val rssPico = rssPicoKb()
                val tokens = transcript.windows.sumOf { it.generation.tokens.size }
                val tokensTexto = transcript.windows
                    .flatMap { janela -> janela.generation.tokens }
                    .joinToString(",")
                val janelaNs = medido.encoderNs.get() + medido.startNs.get() + medido.stepNs.get() +
                    transcript.featuresMillis * 1_000_000L
                voltasTotal = loop?.voltasCpu ?: 0
                passosCpuTotal = loop?.passosCpu ?: 0
                if (loop?.decodersCpuCarregados() == true) cpuCarregadoAlgum = true
                if ((loop?.erroRealimentacao() ?: "").isNotEmpty()) {
                    falhaFeedbackAlguma = loop!!.erroRealimentacao()!!
                }
                val qp = referencia[id]
                val identico = qp != null && qp == tokensTexto
                if (qp != null) {
                    comparados += 1
                    if (identico) identicos += 1
                }
                linha(
                    JSONObject()
                        .put("schema", SCHEMA)
                        .put("tipo", "clipe")
                        .put("modo", modo)
                        .put("indice", indice + 1)
                        .put("id", id)
                        .put("outcome", transcript.outcome.name)
                        .put("stop", transcript.stop.name)
                        .put("rota", medido.rota)
                        .put("janelas", transcript.windows.size)
                        .put("parede_ns", paredeNs)
                        .put("janela_ns", janelaNs)
                        .put("frontend_ns", transcript.featuresMillis * 1_000_000L)
                        .put("encoder_ns", medido.encoderNs.get())
                        .put("start_ns", medido.startNs.get())
                        .put("step_soma_ns", medido.stepNs.get())
                        .put("step_chamadas", medido.stepChamadas.get())
                        .put("tokens", tokens)
                        .put("tokens_txt", tokensTexto)
                        .put("audio_ms", transcript.audioMillis)
                        .put("pss_antes_kb", pssAntes)
                        .put("pss_depois_kb", pssDepois)
                        .put("rss_pico_kb", rssPico)
                        .put("voltas_cpu", (loop?.voltasCpu ?: -1) - voltasAntes)
                        .put("passos_cpu", loop?.passosCpu ?: -1)
                        .put("passos_htp", loop?.passosHtp ?: -1)
                        .put("decoders_cpu_carregados", loop?.decodersCpuCarregados() ?: false)
                        .put("cpu_carregado_antes", cpuAntes)
                        .put("modo_feedback", loop?.modoRealimentacao() ?: "")
                        .put("referencia_qp", qp != null)
                        .put("identico_qp", identico)
                        .put("texto", transcript.text),
                )
                Log.i(
                    TAG,
                    "clipe id=$id modo=$modo rota=${medido.rota} janela_ms=${janelaNs / 1_000_000} " +
                        "tokens=$tokens voltas=${loop?.voltasCpu ?: -1} pid=${android.os.Process.myPid()}",
                )
            }
            if (modo == "volta") {
                linha(
                    JSONObject().put("schema", SCHEMA).put("tipo", "resumo").put("modo", modo)
                        .put("clipes", fontes.size).put("voltas_cpu", voltasTotal)
                        .put("passos_cpu", passosCpuTotal)
                        .put("cpu_carregado", cpuCarregadoAlgum)
                        .put("erro_feedback", falhaFeedbackAlguma)
                        .put("pid", android.os.Process.myPid()),
                )
                assertTrue("o limite artificial tem de forçar a volta para a CPU", voltasTotal > 0)
                assertTrue("os decoders CPU só carregam na volta", cpuCarregadoAlgum)
                assertTrue("a janela refeita na CPU tem de rodar passos", passosCpuTotal > 0)
            }
            if (modo == "qualidade") {
                linha(
                    JSONObject().put("schema", SCHEMA).put("tipo", "resumo").put("modo", modo)
                        .put("clipes", fontes.size).put("comparados", comparados)
                        .put("identicos", identicos)
                        .put("pid", android.os.Process.myPid()),
                )
                assertEquals("28/28 idênticos ao braço Q′ (comparados=$comparados)", comparados, identicos)
                assertTrue("esperado 28 clipes na lista", fontes.size == 28)
            }
        } finally {
            runCatching { backend.close() }
            WhisperOrtHtpRota.limiteHtpParaTeste = null
        }
        Log.i(TAG, "fim modo=$modo clipes=${fontes.size} pid=${android.os.Process.myPid()}")
    }
}
