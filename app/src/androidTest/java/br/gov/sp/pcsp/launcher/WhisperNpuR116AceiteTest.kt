package br.gov.sp.pcsp.launcher

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.net.Uri
import android.os.Debug
import android.os.PowerManager
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.regex.Pattern

/**
 * R116 — harness de aceite completo no build de produto (T02/T03/T04).
 *
 * Modos por `-e r116_modo`:
 * - `c2_download_fixa`: C2 nas entregas fixa (download v3, rota HTP ponta a ponta).
 * - `c3_cancel_download`: C3 (cancelar no meio: nada ativa, nenhum resto parcial).
 * - `c6_falha`: C6 (falha antes de verificar o v3, simulada com `cancelado` imediato,
 *   com o v2 presente: v2 continua servindo e não é removido).
 * - `t03_qualidade`: 202 clipes, braço H ou R (`-e r116_braco`); texto SÓ no recibo.
 * - `t03_latencia`: clipes 2–8 (lista de latência), 1 processo por braço/sessão,
 *   janela limpa (WAV fora), TTFT e decomposição; SEM texto.
 * - `t04_s1`: 3 passadas × 202 na rota H, um processo; PSS/RSS/temp/total; SEM texto.
 * - `t04_frio`: 1 clipe em processo novo (carga fria).
 * - `t04_temp`: laço contínuo por `-e r116_minutos` (defeito 30) na rota H; SEM texto.
 * - `t04_cancel`: cancelar no meio de áudio longo (≤1 s) e liberar a NPU.
 * - `t04_background`: áudio longo com o app em segundo plano (FGS), sem congelamento.
 *
 * O braço R é a rota híbrida do próprio produto (encoder HTP do v3 + decoders CPU
 * INT8), composta com as classes do produto (`WhisperOrtHtpBackend` + sessão EPContext
 * do `WhisperOrtHtpSessoesOrt` + `WhisperOrtSession.openDecodersOnly`) — sem mudar
 * `app/src/main`.
 *
 * Args: r116_modo, r116_saida, r116_braco (h|r), r116_lista (id<TAB>path),
 * r116_audio (curto padrão), r116_audio_longa, r116_sessao (rótulo do processo).
 */
@RunWith(AndroidJUnit4::class)
class WhisperNpuR116AceiteTest {

    private companion object {
        const val TAG = "R116-ACEITE"
        const val SCHEMA = "sig-whisper-npu-r116-aceite/v1"
        const val DIR_MODELO = "ort-model"
        const val ENTRADA = "input_features"
    }

    private val ctx: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val args get() = InstrumentationRegistry.getArguments()

    private val saida: File get() = File(args.getString("r116_saida") ?: File(ctx.filesDir, "r116/resultado.jsonl").absolutePath)

    private fun linha(json: JSONObject) {
        saida.parentFile?.mkdirs()
        saida.appendText(json.toString() + "\n")
        Log.i(TAG, "etapa=${json.optString("etapa")} id=${json.optString("id")} rota=${json.optString("rota")} outcome=${json.optString("outcome")}")
    }

    private fun pares(): List<Pair<String, File>> {
        val lista = args.getString("r116_lista")?.let { File(it) } ?: return emptyList()
        if (!lista.isFile) return emptyList()
        return lista.readLines().map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull { l ->
            val partes = l.split(Regex("\\t|\\s{2,}"), limit = 2).map { it.trim() }
            if (partes.size < 2) return@mapNotNull null
            val arquivo = File(partes[1])
            if (arquivo.isFile) partes[0] to arquivo else null
        }
    }

    private fun audioCurto(): File = File(args.getString("r116_audio") ?: pares().firstOrNull()?.second?.absolutePath ?: "")

    private fun audioLonga(): File = File(args.getString("r116_audio_longa") ?: "/data/local/tmp/r116/long.wav")

    private fun dirPacoteModelo(): File? {
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

    private class BackendMedido(private val real: WhisperOrtBackend) : WhisperOrtBackend {
        val encoderNs = AtomicLong()
        val startNs = AtomicLong()
        val stepNs = AtomicLong()
        val stepChamadas = AtomicLong()

        override fun encode(logMel: FloatArray): FloatArray {
            val t0 = System.nanoTime()
            val saida = real.encode(logMel)
            encoderNs.addAndGet(System.nanoTime() - t0)
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

    private fun rssKb(): Long = runCatching {
        File("/proc/self/status").readLines()
            .firstOrNull { it.startsWith("VmRSS:") }
            ?.split(Regex("\\s+"))?.getOrNull(1)?.toLong() ?: -1L
    }.getOrDefault(-1L)

    private fun zerarPicoRss() {
        runCatching { File("/proc/self/clear_refs").writeText("5\n") }
    }

    private fun rssPicoKb(): Long = runCatching {
        File("/proc/self/status").readLines()
            .firstOrNull { it.startsWith("VmHWM:") }
            ?.split(Regex("\\s+"))?.getOrNull(1)?.toLong() ?: -1L
    }.getOrDefault(-1L)

    private fun manterTelaAcesa(): PowerManager.WakeLock? = runCatching {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "SIG:R116Aceite",
        ).apply { acquire(120 * 60_000L) }
    }.getOrNull()

    private fun inventario(dir: File): String =
        if (!dir.isDirectory) "" else dir.listFiles().orEmpty().sortedBy { it.name }
            .joinToString(";") { "${it.name}:${it.length()}:${it.lastModified()}" }

    /** Arquivos sob o cacheDir (o zip do pacote fica em `<cacheDir>/main/task_manager/`). */
    private fun cacheDirArvore(raiz: File): List<File> =
        if (!raiz.isDirectory) emptyList()
        else raiz.walkTopDown().filter { it.isFile }.toList()

    private fun textoExato(rotulo: String) = By.text(Pattern.compile("(?i)^" + Pattern.quote(rotulo) + "$"))

    private fun textoContem(rotulo: String) = By.text(Pattern.compile("(?i).*" + Pattern.quote(rotulo) + ".*"))

    private fun prefs() = ctx.getSharedPreferences("whisper", Context.MODE_PRIVATE)

    // ------------------------------------------------------------------ transcrição (rota do produto)

    private fun transcreverProduto(atividade: android.app.Activity, artefato: File, id: String, timeoutS: Long = 240L): JSONObject {
        val latch = CountDownLatch(1)
        val json = JSONObject()
        val t0 = System.nanoTime()
        WhisperOrtRoute.transcribe(
            atividade, Uri.fromFile(artefato),
            onResult = { r ->
                when (r) {
                    is WhisperOrtRoute.Result.Done -> json
                        .put("rota", r.hardwareLabel ?: "?")
                        .put("outcome", r.outcome)
                        .put("texto", r.text)
                        .put("open_ms", r.openMillis)
                    is WhisperOrtRoute.Result.Cancelled -> json
                        .put("rota", r.hardwareLabel ?: "?")
                        .put("outcome", "CANCELLED")
                        .put("texto", r.text)
                    is WhisperOrtRoute.Result.Refused -> json
                        .put("rota", "CPU").put("outcome", "REFUSED").put("texto", "")
                        .put("motivo", r.reason)
                    is WhisperOrtRoute.Result.Failed -> json
                        .put("rota", "CPU").put("outcome", "FAILED").put("texto", "")
                        .put("motivo", r.reason)
                }
                latch.countDown()
            },
        )
        assertTrue("terminal não chegou em ${timeoutS}s: $id", latch.await(timeoutS, TimeUnit.SECONDS))
        json.put("id", id).put("total_ms", (System.nanoTime() - t0) / 1_000_000)
        return json
    }

    // ------------------------------------------------------------------ backends H/R

    private fun abrirHibrido(dir: File, encoder: File, decoder: File, past: File, threads: Int): WhisperOrtBackend {
        val dirNpu = WhisperOrtNpuPacote.dirAtivo(WhisperOrtNpuPacote.raiz(ctx))
        val estado = WhisperOrtNpuPacote.estado(ctx)
        val pacote = (estado as? WhisperOrtNpuPacote.Estado.Pronto)?.manifesto
            ?: error("pacote não pronto para o braço R")
        NativeDependencyManager.loadOrtRuntime(ctx).getOrThrow()
        check(QairtDependencyManager.isInstalled(ctx)) { "QAIRT ausente" }
        QairtDependencyManager.loadQnnNatives(ctx, "htp", pacote.htpArch)
        val dirQairt = QairtDependencyManager.packageDir(ctx)
        val env = OrtEnvironment.getEnvironment(OrtLoggingLevel.ORT_LOGGING_LEVEL_WARNING, TAG)
        val ortSessao = WhisperOrtHtpSessoesOrt.abrir(env, File(dirNpu, WhisperOrtNpuPacote.WRAPPER), dirQairt, pacote.htpArch)
        val sessaoHtp = object : WhisperOrtHtpSessao {
            override fun rodar(entrada: ByteArray): ByteArray {
                val info = ortSessao.inputInfo[ENTRADA]?.info as? TensorInfo
                    ?: throw IllegalStateException("metadata de entrada ausente")
                val tipo = when (info.onnxType) {
                    TensorInfo.OnnxTensorType.ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT16 -> OnnxJavaType.FLOAT16
                    TensorInfo.OnnxTensorType.ONNX_TENSOR_ELEMENT_DATA_TYPE_INT16,
                    TensorInfo.OnnxTensorType.ONNX_TENSOR_ELEMENT_DATA_TYPE_UINT16,
                    -> OnnxJavaType.INT16
                    else -> throw IllegalStateException("dtype de entrada inesperado: ${info.onnxType}")
                }
                val buffer = ByteBuffer.allocateDirect(entrada.size).order(ByteOrder.LITTLE_ENDIAN)
                buffer.put(entrada)
                buffer.rewind()
                OnnxTensor.createTensor(env, buffer, info.shape, tipo).use { tensor ->
                    ortSessao.run(mapOf(ENTRADA to tensor)).use { resultado ->
                        val saidaOrt = resultado.get(0) as OnnxTensor
                        val bytes = saidaOrt.byteBuffer
                        val copia = ByteArray(bytes.remaining())
                        bytes.get(copia)
                        return copia
                    }
                }
            }

            override fun close() {
                runCatching { ortSessao.close() }
            }
        }
        val decoders = WhisperOrtSession(encoder, decoder, past, threads)
        try {
            decoders.openDecodersOnly(
                melBins = WhisperOrtFrontend.N_MELS,
                frames = 1500,
                dim = 1280,
            )
        } catch (falha: Throwable) {
            runCatching { decoders.close() }
            runCatching { sessaoHtp.close() }
            throw falha
        }
        return WhisperOrtHtpBackend(sessaoHtp, decoders, pacote.dtype)
    }

    private data class CtxMedicao(
        val backend: WhisperOrtBackend,
        val transcriber: WhisperOrtTranscriber,
        val loop: WhisperOrtHtpLoopBackend?,
    )

    private fun prepararMedicao(modo: String): CtxMedicao {
        val braco = args.getString("r116_braco") ?: "h"
        val dir = dirPacoteModelo() ?: error("pacote do modelo ORT ausente")
        val modelo = modeloDoPacote(dir) ?: error("modelo do pacote ausente")
        val config = WhisperOrtGenerationConfig.parse(
            File(dir, "generation_config.json").readText(Charsets.UTF_8),
        )
        val tokenizer = tokenizerDe(dir, modelo)
        val policy = politicaDe(modelo, config)
        val frontend = WhisperOrtFrontend(WhisperOrtModelCatalog.filtrosMelOuDefault(modelo))
        val enc = File(dir, "encoder_model_int8.onnx")
        val dec = File(dir, "decoder_model_int8.onnx")
        val past = File(dir, "decoder_with_past_model_int8.onnx")
        val backend: WhisperOrtBackend = if (braco == "r") {
            abrirHibrido(dir, enc, dec, past, WhisperOrtSession.DEFAULT_THREADS)
        } else {
            WhisperOrtHtpRota.abrir(enc, dec, past, WhisperOrtSession.DEFAULT_THREADS)
        }
        return CtxMedicao(backend, WhisperOrtTranscriber(tokenizer, policy, frontend),
            backend as? WhisperOrtHtpLoopBackend)
    }

    private fun temperatura(): JSONObject {
        var bateriaC = -1
        var zonasLegiveis = 0
        var zonasIlegiveis = 0
        runCatching {
            val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
            bateriaC = bm.getIntProperty(4) / 10 // BATTERY_PROPERTY_TEMPERATURE (API 17)
        }
        for (z in 0..31) {
            runCatching {
                File("/sys/class/thermal/thermal_zone$z/temp").readText()
                zonasLegiveis++
            }.onFailure { zonasIlegiveis++ }
        }
        return JSONObject()
            .put("bateria_c", bateriaC)
            .put("bateria_fonte", "BatteryManager/BATTERY_PROPERTY_TEMPERATURE em C")
            .put("zonas_legiveis", zonasLegiveis)
            .put("zonas_ilegiveis", zonasIlegiveis)
    }

    private fun pre(): JSONObject {
        // O motor do Whisper tem de estar em ORT para as rotas desta rodada; o
        // valor encontrado (cpp=true) fica registrado e volta na T05.
        val cppAntes = prefs().getBoolean("whisper_engine_cpp", false)
        prefs().edit().putBoolean("whisper_engine_cpp", false).commit()
        WhisperOrtHtpRota.iniciar(ctx)
        NativeDependencyManager.activateIfInstalled(ctx)
        val dirNpu = WhisperOrtNpuPacote.dirAtivo(WhisperOrtNpuPacote.raiz(ctx))
        val dirOrt = dirPacoteModelo()
        val modelo = dirOrt?.let { modeloDoPacote(it) }
        val ligado = WhisperOrtRoute.isEnabled(ctx)
        val rotaPedida = WhisperOrtHtpRota.rotaDoPedido()
        val ok = dirNpu != null && modelo != null && ligado
        val linhaPre = JSONObject()
            .put("schema", SCHEMA)
            .put("tipo", "pre")
            .put("etapa", args.getString("r116_modo"))
            .put("outcome", if (ok) "PRE_OK" else "PRE_CONDICAO")
            .put("braco", args.getString("r116_braco") ?: "-")
            .put("rota_pedida", rotaPedida)
            .put("motor_ort_ligado", ligado)
            .put("cpp_antes", cppAntes)
            .put("dir_ativo", dirNpu?.name ?: "")
            .put("dir_ort", dirOrt?.name ?: "")
            .put("modelo", modelo ?: "")
            .put("pid", android.os.Process.myPid())
            .put("sessao", args.getString("r116_sessao") ?: "")
        linha(linhaPre)
        Log.i(TAG, "processo pid=${android.os.Process.myPid()} modo=${args.getString("r116_modo")} braco=${args.getString("r116_braco") ?: "-"}")
        assertTrue("PRE_CONDICAO dir=$dirNpu modelo=$modelo ligado=$ligado", ok)
        return linhaPre
    }

    // ------------------------------------------------------------------ motorista

    @Test
    fun rodar() {
        val modo = args.getString("r116_modo") ?: return
        val lock = manterTelaAcesa()
        try {
            when (modo) {
                "c1_oferta" -> c1()
                "c2_download_fixa" -> c2()
                "c3_cancel_download" -> c3()
                "c6_falha" -> c6()
                "instalar_v2" -> instalarV2()
                "c7_final" -> c7Final()
                "t03_qualidade" -> t03Qualidade()
                "t03_latencia" -> t03Latencia()
                "t04_s1" -> t04S1()
                "t04_frio" -> t04Frio()
                "t04_temp" -> t04Temp()
                "t04_cancel" -> t04Cancel()
                "t04_background" -> t04Background()
                "restaurar" -> restaurar()
                else -> throw IllegalStateException("modo desconhecido: $modo")
            }
        } finally {
            runCatching { lock?.release() }
        }
    }

    // ------------------------------------------------------------------ C1 / C2 / C3

    /**
     * C1 — sem pacote: a oferta do v3 aparece UMA vez; recusar persiste e nada
     * baixa; a entrada fixa continua oferecendo. Os rótulos são buscados por
     * trecho ASCII ("AGORA", "AIXAR") porque `(?i)` sem `u` não dobra `ã`/`Ã`.
     */
    private fun c1() {
        val dispositivo = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val chave = WhisperNpuOferta.PREF_CHAVE
        val tinha = prefs().contains(chave)
        val valor = prefs().getBoolean(chave, false)
        val estadoAntes = WhisperOrtNpuPacote.estado(ctx)
        assertTrue(
            "C1 exige pacote ausente, achado=${estadoAntes.javaClass.simpleName}",
            estadoAntes is WhisperOrtNpuPacote.Estado.Ausente,
        )
        val tinhaCpp = prefs().contains("whisper_engine_cpp")
        val cppAntes = prefs().getBoolean("whisper_engine_cpp", false)
        try {
            prefs().edit()
                .putBoolean("whisper_engine_cpp", false)
                .remove(WhisperNpuOferta.PREF_CHAVE)
                .remove(WhisperNpuOferta.PREF_CHAVE_ATUALIZACAO)
                .commit()
            var dialogoApareceu = false
            var recusou = false
            var reapareceuSemRecusa = false
            var entradaFixaVisivel = false
            var rotuloFixa = ""
            ActivityScenario.launch(WhisperActivity::class.java).use { cenario ->
                asActivity(cenario)
                selecionarNpuParaAceite(dispositivo, cenario)
                val baixar = dispositivo.wait(Until.findObject(By.textContains("AIXAR")), 20_000L)
                dialogoApareceu = baixar != null
                assertNotNull("a oferta do v3 tem de aparecer uma vez", baixar)
                val plano990 = dispositivo.findObject(By.textContains("990"))
                val agora = dispositivo.wait(Until.findObject(By.textContains("AGORA")), 10_000L)
                assertNotNull("o botão da recusa tem de estar no diálogo", agora)
                agora!!.click()
                Thread.sleep(500L)
                recusou = prefs().getString("ort_hardware", null) == "CPU"
                assertTrue("a recusa tem de persistir", recusou)
                linha(JSONObject()
                    .put("schema", SCHEMA).put("etapa", "C1-DIALOGO")
                    .put("outcome", if (dialogoApareceu && recusou) "OFERTA_RECUSADA_OK" else "OFERTA_RECUSADA_FALHOU")
                    .put("plano_990mb", plano990 != null)
                    .put("pid", android.os.Process.myPid()))
                cenario.close()
            }
            ActivityScenario.launch(WhisperActivity::class.java).use { cenario ->
                asActivity(cenario)
                Thread.sleep(3_000L)
                reapareceuSemRecusa = dispositivo.hasObject(By.textContains("AIXAR"))
                val entrada = dispositivo.findObject(By.res(ctx.packageName, "button_backend"))
                entradaFixaVisivel = entrada != null
                rotuloFixa = entrada?.text ?: ""
            }
            linha(JSONObject()
                .put("schema", SCHEMA).put("etapa", "C1-FIXA")
                .put("outcome", if (!reapareceuSemRecusa && entradaFixaVisivel) "FIXA_OK" else "FIXA_FALHOU")
                .put("reapareceu", reapareceuSemRecusa)
                .put("rotulo_fixa", rotuloFixa)
                .put("pid", android.os.Process.myPid()))
            val estadoDepois = WhisperOrtNpuPacote.estado(ctx)
            assertTrue("a oferta não pode reaparecer após a recusa", !reapareceuSemRecusa)
            assertTrue("a entrada fixa tem de continuar na tela", entradaFixaVisivel)
            assertEquals("o seletor retorna à CPU", "CPU", rotuloFixa)
            assertTrue("recusar não pode baixar nada", estadoDepois is WhisperOrtNpuPacote.Estado.Ausente)
        } finally {
            prefs().edit().apply {
                if (tinha) putBoolean(chave, valor) else remove(chave)
                if (tinhaCpp) putBoolean("whisper_engine_cpp", cppAntes) else remove("whisper_engine_cpp")
            }.commit()
        }
        assertEquals("o motor tem de voltar ao valor do início", cppAntes,
            prefs().getBoolean("whisper_engine_cpp", false))
    }

    private fun abrirDialogoDownload(dispositivo: UiDevice, cenario: ActivityScenario<WhisperActivity>, rotuloFixa: String) {
        // Recusa registrada: a automática não aparece; a entrada fixa é o caminho.
        // Motor ORT na rodada (valor original `cpp=true` gravado no estado inicial).
        prefs().edit()
            .putBoolean("whisper_engine_cpp", false)
            .putBoolean(WhisperNpuOferta.PREF_CHAVE, true)
            .putBoolean(WhisperNpuOferta.PREF_CHAVE_ATUALIZACAO, true)
            .commit()
        selecionarNpuParaAceite(dispositivo, cenario)
        val baixar = dispositivo.wait(Until.findObject(By.textContains("AIXAR")), 20_000L)
        assertNotNull("o diálogo tem de oferecer Baixar", baixar)
    }

    private fun c2() {
        val dispositivo = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val raiz = WhisperOrtNpuPacote.raiz(ctx)
        val antes = inventario(raiz)
        val estadoAntes = WhisperOrtNpuPacote.estado(ctx)
        assertTrue(
            "C2 exige pacote ausente, achado=${estadoAntes.javaClass.simpleName}",
            estadoAntes is WhisperOrtNpuPacote.Estado.Ausente,
        )
        ActivityScenario.launch(WhisperActivity::class.java).use { cenario ->
            abrirDialogoDownload(dispositivo, cenario, "Ativar aceleração NPU")
            val t0 = System.nanoTime()
            dispositivo.findObject(By.textContains("AIXAR"))?.click()
            // Espera a verificação v3 terminar (download ~987 MB).
            val fim = System.currentTimeMillis() + 25 * 60_000L
            var pronto = false
            var estado: WhisperOrtNpuPacote.Estado = WhisperOrtNpuPacote.Estado.Ausente("aguardar")
            while (System.currentTimeMillis() < fim && !pronto) {
                Thread.sleep(3_000L)
                estado = WhisperOrtNpuPacote.estado(ctx)
                pronto = estado is WhisperOrtNpuPacote.Estado.Pronto
            }
            val ms = (System.nanoTime() - t0) / 1_000_000
            linha(JSONObject()
                .put("schema", SCHEMA).put("etapa", "C2-DOWNLOAD")
                .put("outcome", if (pronto) "DOWNLOAD_OK" else "DOWNLOAD_FALHOU")
                .put("ms", ms)
                .put("estado", estado.javaClass.simpleName)
                .put("versao", (estado as? WhisperOrtNpuPacote.Estado.Pronto)?.manifesto?.versao ?: "")
                .put("inventario_antes", antes)
                .put("pid", android.os.Process.myPid()))
            assertTrue("C2: o download não concluiu em 25 min", pronto)
            assertEquals("3", (estado as WhisperOrtNpuPacote.Estado.Pronto).manifesto.versao)
            val t = transcreverProduto(asActivity(cenario), audioCurto(), audioCurto().nameWithoutExtension, 120L)
            linha(t.put("schema", SCHEMA).put("etapa", "C2-ROTA"))
            assertEquals("a rota tem de ser HTP ponta a ponta", "HTP W8A16", t.optString("rota"))
            assertTrue("texto vazio na rota HTP", !t.optString("texto").isNullOrBlank())
        }
    }

    private fun asActivity(cenario: ActivityScenario<WhisperActivity>): android.app.Activity {
        val ref = java.util.concurrent.atomic.AtomicReference<android.app.Activity>()
        cenario.onActivity { ref.set(it) }
        return ref.get() ?: error("Activity não disponível")
    }

    private fun c3() {
        val dispositivo = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val raiz = WhisperOrtNpuPacote.raiz(ctx)
        val staging = File(raiz, ".staging")
        fun zipsEmCache(): List<File> = cacheDirArvore(ctx.cacheDir).filter {
            it.name.contains("zip", true) || it.name.endsWith(".download")
        }
        val antes = inventario(raiz)
        val estadoAntes = WhisperOrtNpuPacote.estado(ctx)
        assertTrue(
            "C3 exige pacote ausente, achado=${estadoAntes.javaClass.simpleName}",
            estadoAntes is WhisperOrtNpuPacote.Estado.Ausente,
        )
        ActivityScenario.launch(WhisperActivity::class.java).use { cenario ->
            abrirDialogoDownload(dispositivo, cenario, "Ativar aceleração NPU")
            dispositivo.findObject(By.textContains("AIXAR"))?.click()
            // Espera o download começar (zip em cache ou .staging) de verdade.
            fun zipComecado(): Pair<File?, String> {
                val candidato = zipsEmCache().firstOrNull { it.length() > 10_000_000L }
                return candidato to (candidato?.name ?: "")
            }
            var started = false
            var nomeZip = ""
            val fimStart = System.currentTimeMillis() + 120_000L
            while (System.currentTimeMillis() < fimStart && !started) {
                val (arq, nome) = zipComecado()
                if (arq != null || staging.isDirectory) {
                    started = true
                    nomeZip = nome
                } else {
                    Thread.sleep(1_000L)
                }
            }
            assertTrue("C3: o download não começou em 120 s", started)
            val cancelar = dispositivo.wait(Until.findObject(By.textContains("CANCELAR")), 15_000L)
            assertNotNull("o diálogo de progresso tem de oferecer Cancelar", cancelar)
            val tCancel = System.nanoTime()
            cancelar!!.click()
            // Espera o terminal do cancelamento: staging/zip somem, nada ativa.
            val fim = System.currentTimeMillis() + 180_000L
            while (System.currentTimeMillis() < fim && (staging.exists() || zipsEmCache().isNotEmpty())) {
                Thread.sleep(1_000L)
            }
            val ms = (System.nanoTime() - tCancel) / 1_000_000
            val depois = inventario(raiz)
            val estadoDepois = WhisperOrtNpuPacote.estado(ctx)
            val zipsRestantes = zipsEmCache().map { it.name }
            val dirV3 = File(raiz, "3")
            val arquivosV3 = if (dirV3.isDirectory) dirV3.listFiles()?.size ?: 0 else 0
            linha(JSONObject()
                .put("schema", SCHEMA).put("etapa", "C3-CANCEL")
                .put("outcome", if (estadoDepois is WhisperOrtNpuPacote.Estado.Ausente) "CANCEL_OK" else "CANCEL_FALHOU")
                .put("ms_ate_terminal", ms)
                .put("estado", estadoDepois.javaClass.simpleName)
                .put("staging_existe", staging.exists())
                .put("zip_comecado", nomeZip)
                .put("zips_restantes", zipsRestantes.joinToString(","))
                .put("arquivos_v3", arquivosV3)
                .put("inventario_antes", antes)
                .put("inventario_depois", depois)
                .put("pid", android.os.Process.myPid()))
            assertTrue("C3: o pacote não pode ativar depois do cancelamento", estadoDepois is WhisperOrtNpuPacote.Estado.Ausente)
            assertEquals("C3: nenhum resto parcial (.staging)", false, staging.exists())
            assertEquals("C3: nenhum resto parcial (zip em cache)", emptyList<File>(), zipsEmCache())
            assertEquals("C3: nenhum arquivo do pacote v3 pode restar", 0, arquivosV3)
        }
    }

    // ------------------------------------------------------------------ C6

    /**
     * C6 (preparo) — instala o v2 a partir do ZIP publicado, usando APENAS funções
     * do produto (`lerManifesto`, `conferirArquivos`, `montarMarcador`,
     * `gravarMarcador`): o marcador sai com a assinatura do próprio pacote e a
     * conferência rápida do app o reconhece como Pronto. Nada é inventado.
     */
    private fun instalarV2() {
        val zip = File(args.getString("r116_zip_v2") ?: "/data/local/tmp/r116-v2.zip")
        assertTrue("ZIP do v2 ausente: $zip", zip.isFile)
        val raiz = WhisperOrtNpuPacote.raiz(ctx)
        val destino = File(raiz, WhisperOrtNpuPacote.VERSAO_ANTERIOR)
        val staging = File(raiz, ".staging-v2-teste")
        staging.deleteRecursively()
        staging.mkdirs()
        val t0 = System.nanoTime()
        java.util.zip.ZipInputStream(java.io.BufferedInputStream(zip.inputStream())).use { entrada ->
            while (true) {
                val item = entrada.nextEntry ?: break
                val nome = item.name.replace('\\', '/')
                if (nome.endsWith("/")) continue
                val alvo = File(staging, nome)
                alvo.parentFile?.mkdirs()
                alvo.outputStream().use { saida -> entrada.copyTo(saida) }
            }
        }
        val manifesto = WhisperOrtNpuPacote.lerManifesto(File(staging, WhisperOrtNpuPacote.NOME_MANIFESTO).readText())
        assertNotNull("manifesto do v2 ilegível", manifesto)
        val motivo = WhisperOrtNpuPacote.conferirArquivos(manifesto!!, staging)
        assertNull("pacote v2 incompleto: $motivo", motivo)
        destino.deleteRecursively()
        val ativado = staging.renameTo(destino) || runCatching {
            staging.copyRecursively(destino, overwrite = true); staging.deleteRecursively(); destino.exists()
        }.getOrDefault(false)
        assertTrue("não ativou o v2", ativado)
        assertTrue("marcador do v2 não gravado",
            WhisperOrtNpuPacote.gravarMarcador(destino, WhisperOrtNpuPacote.montarMarcador(manifesto, destino)))
        val estado = WhisperOrtNpuPacote.estadoEm(destino)
        linha(JSONObject()
            .put("schema", SCHEMA).put("etapa", "INSTALAR-V2")
            .put("outcome", if (estado is WhisperOrtNpuPacote.Estado.Pronto) "V2_PRONTO" else "V2_FALHOU")
            .put("estado", estado.javaClass.simpleName)
            .put("versao", manifesto!!.versao)
            .put("arquivos", manifesto!!.itens.size)
            .put("bytes", manifesto!!.bytesDescompactados())
            .put("ms", (System.nanoTime() - t0) / 1_000_000)
            .put("pid", android.os.Process.myPid()))
        assertTrue("o v2 tem de ficar Pronto", estado is WhisperOrtNpuPacote.Estado.Pronto)
        assertEquals("2", manifesto.versao)
    }

    /**
     * C7 — estado final: v2 ausente, v3 Pronto e verificado (o marcador é regravado
     * pela verificação completa do produto), preferências restauradas.
     */
    private fun c7Final() {
        val raiz = WhisperOrtNpuPacote.raiz(ctx)
        val dirV3 = File(raiz, WhisperOrtNpuPacote.VERSAO)
        val dirV2 = File(raiz, WhisperOrtNpuPacote.VERSAO_ANTERIOR)
        // C7 também é a restauração das preferências ao valor encontrado na T02
        // (`whisper_engine_cpp=true` no estado inicial da rodada).
        prefs().edit().putBoolean("whisper_engine_cpp", true).commit()
        val completo = WhisperOrtNpuPacote.verificarCompleto(ctx)
        val estado = WhisperOrtNpuPacote.estado(ctx)
        val cpp = prefs().getBoolean("whisper_engine_cpp", false)
        val rota = runCatching { WhisperOrtHtpRota.iniciar(ctx); WhisperOrtHtpRota.rotaDoPedido() }.getOrDefault("?")
        linha(JSONObject()
            .put("schema", SCHEMA).put("etapa", "C7-FINAL")
            .put("outcome", if (estado is WhisperOrtNpuPacote.Estado.Pronto && !dirV2.exists()) "FINAL_OK" else "FINAL_FALHOU")
            .put("verificacao_completa", completo.javaClass.simpleName)
            .put("estado", estado.javaClass.simpleName)
            .put("versao", (estado as? WhisperOrtNpuPacote.Estado.Pronto)?.manifesto?.versao ?: "")
            .put("v2_existe", dirV2.exists())
            .put("v3_arquivos", dirV3.listFiles()?.size ?: 0)
            .put("v3_bytes", dirV3.listFiles()?.sumOf { it.length() } ?: 0L)
            .put("corrompido", WhisperOrtNpuPacote.corrompido(ctx) ?: "")
            .put("reverificar", WhisperOrtNpuPacote.precisaReverificar(raiz))
            .put("whisper_engine_cpp", cpp)
            .put("rota", rota)
            .put("inventario_v3", inventario(dirV3))
            .put("pid", android.os.Process.myPid()))
        assertTrue("estado final tem de ser v3 Pronto", estado is WhisperOrtNpuPacote.Estado.Pronto)
        assertEquals("3", (estado as WhisperOrtNpuPacote.Estado.Pronto).manifesto.versao)
        assertTrue("o v2 tem de estar ausente no estado final", !dirV2.exists())
        assertNull("o sinal de corrompido tem de estar limpo", WhisperOrtNpuPacote.corrompido(ctx))
        assertEquals("o motor tem de estar restaurado (cpp=true)", true, cpp)
        assertEquals("a rota final tem de ser HTP", "htp", rota)
    }

    private fun c6() {
        val raiz = WhisperOrtNpuPacote.raiz(ctx)
        val dirV2 = WhisperOrtNpuPacote.dirDaVersao(ctx, WhisperOrtNpuPacote.VERSAO_ANTERIOR)
        val invAntes = inventario(dirV2)
        val estadoV2Antes = WhisperOrtNpuPacote.estado(ctx)
        assertTrue(
            "C6 exige o v2 presente e pronto, achado=${estadoV2Antes.javaClass.simpleName}",
            estadoV2Antes is WhisperOrtNpuPacote.Estado.Pronto &&
                (estadoV2Antes as WhisperOrtNpuPacote.Estado.Pronto).manifesto.versao == WhisperOrtNpuPacote.VERSAO_ANTERIOR,
        )
        pre()
        val resultado = WhisperOrtNpuPacote.instalar(ctx, cancelado = { true })
        val invDepois = inventario(dirV2)
        val estadoV2Depois = WhisperOrtNpuPacote.estado(ctx)
        val estadoPos = WhisperOrtNpuPacote.estado(ctx)
        linha(JSONObject()
            .put("schema", SCHEMA).put("etapa", "C6-FALHA")
            .put("outcome", if (resultado.isFailure) "FALHA_SIMULADA" else "SEM_FALHA")
            .put("erro", resultado.exceptionOrNull()?.javaClass?.simpleName ?: "")
            .put("v2_antes", invAntes)
            .put("v2_depois", invDepois)
            .put("estado_v2", estadoV2Depois?.javaClass?.simpleName ?: "null")
            .put("estado_pos", estadoPos.javaClass.simpleName)
            .put("dirAtivo_v2", WhisperOrtNpuPacote.dirAtivo(raiz).name)
            .put("pid", android.os.Process.myPid()))
        assertTrue("a instalação tem de falhar com o cancelamento imediato", resultado.isFailure)
        assertEquals("o v2 não pode mudar com a falha", invAntes, invDepois)
        assertTrue("o v2 continua pronto", estadoV2Depois is WhisperOrtNpuPacote.Estado.Pronto)
        assertTrue("o dir ativo continua o v2", WhisperOrtNpuPacote.dirAtivo(raiz).name == WhisperOrtNpuPacote.VERSAO_ANTERIOR)
        ActivityScenario.launch(WhisperActivity::class.java).use { cenario ->
            val t = transcreverProduto(asActivity(cenario), audioCurto(), audioCurto().nameWithoutExtension, 120L)
            val rotaEfetiva = t.optString("rota")
            linha(t.put("schema", SCHEMA).put("etapa", "C6-ROTA")
                .put("rota_esperada", "HTP W8A16")
                .put("serviu", !t.optString("texto").isNullOrBlank()))
            // Critério da ordem: o v2 continua servindo (produz transcrição) e não é
            // removido. A ROTA é registrada à parte: em 19/11/2026 ela caiu em CPU
            // por `pacoteVerificado` apontar `dirPacote` para o diretório do v3
            // (`dirDaVersao`) mesmo com o v2 ativo → "abertura: wrapper ausente".
            // A classificação final trata a degradação; aqui não se inventa HTP.
            assertTrue("o v2 tem de continuar servindo (transcrição não vazia)", !t.optString("texto").isNullOrBlank())
            assertEquals("o v2 não pode ser removido", true,
                WhisperOrtNpuPacote.dirDaVersao(ctx, WhisperOrtNpuPacote.VERSAO_ANTERIOR).isDirectory)
            linha(JSONObject().put("schema", SCHEMA).put("etapa", "C6-ROTA-EFETIVA")
                .put("rota", rotaEfetiva).put("pid", android.os.Process.myPid()))
        }
    }

    // ------------------------------------------------------------------ T03 qualidade / latência

    private fun medirClipes(
        medicao: CtxMedicao,
        fontes: List<Pair<String, File>>,
        comTexto: Boolean,
        etiqueta: String,
        maxMs: Long,
        s1: Boolean,
    ) {
        val loop = medicao.loop
        val startGlobal = System.nanoTime()
        var indice = 0
        var passaram = 0
        while (indice < fontes.size) {
            if (maxMs > 0 && (System.nanoTime() - startGlobal) / 1_000_000 > maxMs) break
            val (id, arquivo) = fontes[indice]
            val leitura = WhisperOrtWav.ler(arquivo.readBytes())
            val pcm = (leitura as? WhisperOrtWav.Leitura.Pcm)?.amostras
            if (pcm == null) {
                linha(JSONObject().put("schema", SCHEMA).put("tipo", "clipe").put("etapa", etiqueta)
                    .put("id", id).put("outcome", "PCM_NAO_WAV").put("pid", android.os.Process.myPid()))
                indice++
                continue
            }
            val medido = BackendMedido(medicao.backend)
            val voltasAntes = loop?.voltasCpu ?: -1
            val cpuAntes = loop?.decodersCpuCarregados() ?: false
            zerarPicoRss()
            val pssAntes = pssKb()
            val inicio = System.nanoTime()
            val transcript = medicao.transcriber.transcribe(medido, pcm)
            val paredeNs = System.nanoTime() - inicio
            val pssDepois = pssKb()
            val rss = rssKb()
            val rssPico = rssPicoKb()
            val tokens = transcript.windows.sumOf { it.generation.tokens.size }
            val tokensTexto = transcript.windows
                .flatMap { janela -> janela.generation.tokens }
                .joinToString(",")
            val janelaNs = medido.encoderNs.get() + medido.startNs.get() + medido.stepNs.get() +
                transcript.featuresMillis * 1_000_000L
            val ttft = transcript.featuresMillis + (transcript.windows.firstOrNull()?.generation?.firstTokenMillis ?: -1L)
            val ok = transcript.outcome == WhisperOrtOutcome.SUCCESS || transcript.outcome == WhisperOrtOutcome.NO_SPEECH
            if (ok) passaram++
            val json = JSONObject()
                .put("schema", SCHEMA)
                .put("tipo", "clipe")
                .put("etapa", etiqueta)
                .put("indice", indice + 1)
                .put("id", id)
                .put("braco", args.getString("r116_braco") ?: "-")
                .put("sessao", args.getString("r116_sessao") ?: "")
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
                .put("residuo_ns", (janelaNs - transcript.featuresMillis * 1_000_000L - medido.encoderNs.get() - medido.startNs.get() - medido.stepNs.get()))
                .put("ttft_ms", ttft)
                .put("tokens", tokens)
                .put("pss_antes_kb", pssAntes)
                .put("pss_depois_kb", pssDepois)
                .put("rss_kb", rss)
                .put("rss_pico_kb", rssPico)
                .put("voltas_cpu", (loop?.voltasCpu ?: -1) - voltasAntes)
                .put("passos_cpu", loop?.passosCpu ?: -1)
                .put("passos_htp", loop?.passosHtp ?: -1)
                .put("cpu_carregado_antes", cpuAntes)
                .put("pid", android.os.Process.myPid())
            if (comTexto) {
                json.put("tokens_txt", tokensTexto).put("texto", transcript.text)
            }
            if (s1) {
                json.put("temperatura", temperatura())
            }
            linha(json)
            Log.i(TAG, "clipe id=$id etapa=$etiqueta rota=${medido.rota} janela_ms=${janelaNs / 1_000_000} tokens=$tokens pid=${android.os.Process.myPid()}")
            indice++
        }
        linha(JSONObject().put("schema", SCHEMA).put("tipo", "resumo").put("etapa", etiqueta)
            .put("clipes", fontes.size).put("executados", indice).put("passaram", passaram)
            .put("pid", android.os.Process.myPid()))
    }

    private fun t03Qualidade() {
        pre()
        val fontes = pares()
        assumeTrue("lista vazia", fontes.isNotEmpty())
        val medicao = prepararMedicao("qualidade")
        ActivityScenario.launch(WhisperActivity::class.java).use { cenario ->
            asActivity(cenario)
            medirClipes(medicao, fontes, comTexto = true, etiqueta = "QUALIDADE", maxMs = 0L, s1 = false)
        }
        runCatching { medicao.backend.close() }
    }

    private fun t03Latencia() {
        pre()
        val fontes = pares()
        assumeTrue("lista de latência vazia", fontes.isNotEmpty())
        val medicao = prepararMedicao("latencia")
        ActivityScenario.launch(WhisperActivity::class.java).use { cenario ->
            asActivity(cenario)
            // Aquecimento: primeiro clipe fora das medidas.
            val quente = fontes.first()
            val leituraQ = (WhisperOrtWav.ler(quente.second.readBytes()) as? WhisperOrtWav.Leitura.Pcm)?.amostras
            if (leituraQ != null) {
                runCatching { medicao.transcriber.transcribe(medicao.backend, leituraQ) }
            }
            medirClipes(medicao, fontes, comTexto = false, etiqueta = "LATENCIA", maxMs = 0L, s1 = false)
        }
        runCatching { medicao.backend.close() }
    }

    // ------------------------------------------------------------------ T04

    private fun t04S1() {
        pre()
        val fontes = pares()
        assumeTrue("lista vazia", fontes.isNotEmpty())
        val medicao = prepararMedicao("s1")
        val passadas = (args.getString("r116_passes") ?: "3").toInt()
        ActivityScenario.launch(WhisperActivity::class.java).use { cenario ->
            asActivity(cenario)
            // 3 passadas executadas como blocos: cada passada repõe a lista.
            // A implementação chama medirClipes com outra etiqueta; o pid é um só.
            for (p in 1..passadas) {
                medirClipes(medicao, fontes, comTexto = false, etiqueta = "S1-P$p", maxMs = 0L, s1 = true)
            }
        }
        runCatching { medicao.backend.close() }
    }

    private fun t04Frio() {
        val t0 = System.nanoTime()
        pre()
        val medicao = prepararMedicao("frio")
        val aberturaMs = (System.nanoTime() - t0) / 1_000_000
        val arquivo = audioCurto()
        assertTrue("áudio ausente: $arquivo", arquivo.isFile)
        val leitura = WhisperOrtWav.ler(arquivo.readBytes())
        val pcm = (leitura as? WhisperOrtWav.Leitura.Pcm)?.amostras
        assertNotNull("PCM não é WAV", pcm)
        val medido = BackendMedido(medicao.backend)
        val inicio = System.nanoTime()
        val transcript = medicao.transcriber.transcribe(medido, pcm!!)
        val cargaFriaMs = (System.nanoTime() - t0) / 1_000_000
        val janelaNs = medido.encoderNs.get() + medido.startNs.get() + medido.stepNs.get() +
            transcript.featuresMillis * 1_000_000L
        linha(JSONObject()
            .put("schema", SCHEMA).put("etapa", "FRIO")
            .put("outcome", transcript.outcome.name)
            .put("braco", args.getString("r116_braco") ?: "-")
            .put("abertura_ms", aberturaMs)
            .put("carga_fria_ms", cargaFriaMs)
            .put("inicio_ms", (inicio - t0) / 1_000_000)
            .put("janela_ns", janelaNs)
            .put("parede_ns", System.nanoTime() - inicio)
            .put("tokens", transcript.windows.sumOf { it.generation.tokens.size })
            .put("pid", android.os.Process.myPid()))
        runCatching { medicao.backend.close() }
    }

    private fun t04Temp() {
        pre()
        val fontes = pares()
        assumeTrue("lista vazia", fontes.isNotEmpty())
        val minutos = (args.getString("r116_minutos") ?: "30").toLong()
        val medicao = prepararMedicao("temp")
        ActivityScenario.launch(WhisperActivity::class.java).use { cenario ->
            asActivity(cenario)
            // Transcrição CONTÍNUA: repete o corpus até fechar a janela pedida.
            val limiteMs = minutos * 60_000L
            val inicio = System.nanoTime()
            var passada = 0
            while ((System.nanoTime() - inicio) / 1_000_000 < limiteMs) {
                passada++
                medirClipes(
                    medicao, fontes, comTexto = false, etiqueta = "TEMP-P$passada",
                    maxMs = limiteMs - (System.nanoTime() - inicio) / 1_000_000,
                    s1 = true,
                )
            }
            linha(JSONObject().put("schema", SCHEMA).put("tipo", "resumo")
                .put("etapa", "TEMP").put("passadas", passada)
                .put("minutos_pedidos", minutos)
                .put("minutos_reais", (System.nanoTime() - inicio) / 60_000_000_000L)
                .put("pid", android.os.Process.myPid()))
        }
        runCatching { medicao.backend.close() }
    }

    private fun t04Cancel() {
        pre()
        val longa = audioLonga()
        assertTrue("áudio longo ausente: $longa", longa.isFile)
        val curta = audioCurto()
        assertTrue("áudio curto ausente: $curta", curta.isFile)
        val cancelador = WhisperNpuAceiteReceipt.canceladorPadrao()
        val recibos = mutableListOf<WhisperNpuAceiteReceipt.Dados>()
        ActivityScenario.launch(WhisperActivity::class.java).use { cenario ->
            val atividade = asActivity(cenario)
            val inicio = System.nanoTime() / 1_000_000L
            val real = AtomicLong(0L)
            val terminal = AtomicLong(0L)
            val latch = CountDownLatch(1)
            var job: WhisperOrtAdmission.Job? = null
            val timers = WhisperNpuAceiteReceipt.Timers(inicio + 4_000L)
            cancelador.programar(inicio, 4_000L) { _, agora ->
                real.set(agora)
                WhisperOrtRoute.cancelar(job)
            }
            job = WhisperOrtRoute.transcribe(atividade, Uri.fromFile(longa), onResult = { r ->
                terminal.set(System.nanoTime() / 1_000_000L)
                recibos.add(
                    WhisperNpuAceiteReceipt.Dados(
                        celula = "T04-CANCEL", rotaPedida = "htp", rotaEfetiva = (r as? WhisperOrtRoute.Result.Cancelled)?.hardwareLabel ?: (r as? WhisperOrtRoute.Result.Done)?.hardwareLabel,
                        versaoNpu = WhisperOrtHtpRota.versaoPacote(), versaoQairt = QairtDependencyManager.PACKAGE_VERSION,
                        motivoFallback = (r as? WhisperOrtRoute.Result.Cancelled)?.let { "CANCELLED parcial=${it.partial}" } ?: "",
                        openMs = 0L, totalMs = terminal.get() - inicio, soc = "",
                        timers = timers.copy(realMs = real.get().takeIf { it > 0L }, terminalMs = terminal.get()),
                        texto = null, id = longa.nameWithoutExtension,
                        conferenciaMs = WhisperOrtHtpRota.conferenciaMs(),
                    ),
                )
                latch.countDown()
            })
            assertTrue("o terminal não chegou em 600 s", latch.await(600, TimeUnit.SECONDS))
            val d = recibos.first()
            linha(JSONObject()
                .put("schema", SCHEMA).put("etapa", "T04-CANCEL")
                .put("outcome", "CANCEL_EXECUTADO")
                .put("rota", d.rotaEfetiva ?: "")
                .put("pos_sinal_ms", d.timers.posSinalMs ?: -1L)
                .put("total_ms", d.totalMs)
                .put("pid", android.os.Process.myPid()))
            assertTrue("o cancelamento tem de concluir em ≤ 1 s após o sinal (obtido ${d.timers.posSinalMs})", (d.timers.posSinalMs ?: Long.MAX_VALUE) <= 1_000L)
            // NPU livre: a próxima transcrição curto vai à HTP.
            val t = transcreverProduto(atividade, curta, curta.nameWithoutExtension, 120L)
            linha(t.put("schema", SCHEMA).put("etapa", "T04-POS-CANCEL"))
            assertEquals("a NPU tem de estar livre para a próxima transcrição", "HTP W8A16", t.optString("rota"))
        }
        cancelador.fechar()
    }

    private fun restaurar() {
        // T05: devolve as preferências ao estado encontrado na T02 — motor em
        // whisper.cpp (`whisper_engine_cpp=true`) e SEM as chaves de recusa que o
        // harness criou para abrir o diálogo.
        prefs().edit()
            .putBoolean("whisper_engine_cpp", true)
            .remove(WhisperNpuOferta.PREF_CHAVE)
            .remove(WhisperNpuOferta.PREF_CHAVE_ATUALIZACAO)
            .commit()
        val cpp = prefs().getBoolean("whisper_engine_cpp", false)
        val recusa = prefs().contains(WhisperNpuOferta.PREF_CHAVE)
        val recusaAtualizacao = prefs().contains(WhisperNpuOferta.PREF_CHAVE_ATUALIZACAO)
        linha(JSONObject()
            .put("schema", SCHEMA).put("etapa", "RESTAURAR")
            .put("outcome", if (cpp && !recusa && !recusaAtualizacao) "RESTAURADO_OK" else "RESTAURAR_FALHOU")
            .put("whisper_engine_cpp", cpp)
            .put("recusa_presente", recusa)
            .put("recusa_atualizacao_presente", recusaAtualizacao)
            .put("pid", android.os.Process.myPid()))
        assertTrue("o motor tem de ser restaurado para cpp=true", cpp)
        assertTrue("as chaves de recusa criadas pelo harness não podem sobrar", !recusa && !recusaAtualizacao)
    }

    private fun t04Background() {
        pre()
        val longa = audioLonga()
        assertTrue("áudio longo ausente: $longa", longa.isFile)
        ActivityScenario.launch(WhisperActivity::class.java).use { cenario ->
            val atividade = asActivity(cenario)
            val latch = CountDownLatch(1)
            val resultado = arrayOf<WhisperOrtRoute.Result?>(null)
            val t0 = System.nanoTime()
            WhisperOrtRoute.transcribe(atividade, Uri.fromFile(longa), onResult = { r ->
                resultado[0] = r
                latch.countDown()
            })
            Thread.sleep(2_000L)
            // App em segundo plano: a Activity sai do foreground; o FGS segue.
            cenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            val travou = !latch.await(10 * 60, TimeUnit.SECONDS)
            val ms = (System.nanoTime() - t0) / 1_000_000
            var wchan = ""
            if (travou) {
                runCatching {
                    val pid = android.os.Process.myPid()
                    val taskDir = File("/proc/$pid/task")
                    wchan = taskDir.listFiles()?.joinToString(";") { t ->
                        val comm = runCatching { File(t, "comm").readText().trim() }.getOrDefault("?")
                        val wc = runCatching { File(t, "wchan").readText().trim() }.getOrDefault("?")
                        "${t.name}:$comm:$wc"
                    } ?: ""
                }
            }
            val r = resultado[0]
            linha(JSONObject()
                .put("schema", SCHEMA).put("etapa", "T04-BACKGROUND")
                .put("outcome", if (!travou && r is WhisperOrtRoute.Result.Done) "BACKGROUND_OK" else "BACKGROUND_FALHOU")
                .put("travou", travou)
                .put("resultado", r?.javaClass?.simpleName ?: "null")
                .put("total_ms", ms)
                .put("wchan", wchan)
                .put("pid", android.os.Process.myPid()))
            assertTrue("a transcrição em segundo plano travou", !travou)
            assertTrue("a transcrição em segundo plano não concluiu", r is WhisperOrtRoute.Result.Done)
        }
    }
}
