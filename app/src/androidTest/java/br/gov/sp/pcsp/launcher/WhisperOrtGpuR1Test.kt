package br.gov.sp.pcsp.launcher

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.FloatBuffer

/**
 * ORT-GPU-R1 §3 — CANÁRIO GPU mínimo e falsificável.
 *
 * Grafo conhecido (MatMul + Add + Relu, FP32, 1x64), entrada e referência
 * determinísticas (assets `canary_gpu.onnx` / `canary_gpu_ref.json`, gerados no
 * host com numpy seed 20261101). A sessão é aberta com o QNN EP apontando
 * explicitamente para `libQnnGpu.so` — NÃO se aceita CPU de referência nem
 * `getAvailableProviders()` como prova.
 *
 * A prova de atribuição vem do PROFILING do ORT: os nós precisam aparecer com
 * provider QNN. Saída precisa ser finita e igual à referência dentro da
 * tolerância medida/declarada.
 */
@RunWith(AndroidJUnit4::class)
class WhisperOrtGpuR1Test {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val assets get() = InstrumentationRegistry.getInstrumentation().context.assets

    private fun dir(): File = File(ctx.filesDir, "gpu-r1").apply { mkdirs() }

    private fun copiarAsset(nome: String, destino: File) {
        assets.open(nome).use { ins -> destino.outputStream().use { out -> ins.copyTo(out) } }
    }

    private fun sha256(file: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { ins ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun recibo(rotulo: String, dados: JSONObject) {
        dados.put("rotulo", rotulo)
        android.util.Log.i("ORTGPUR1", dados.toString())
        val d = ctx.getExternalFilesDir("gpu-evidence") ?: return
        d.mkdirs()
        File(d, "$rotulo-${System.currentTimeMillis()}.json")
            .writeText(dados.toString(), Charsets.UTF_8)
    }

    @Test
    fun canarioGpuMatMul() {
        assumeTrue("aparelho não Qualcomm", QairtDependencyManager.isQualcommDevice())
        // ORT do lab vive no pacote nativo (não no APK): carregar pelo fluxo do
        // produto ANTES de qualquer referência a OrtEnvironment.
        val ativou = NativeDependencyManager.activateIfInstalled(ctx)
        val cargaOrt = NativeDependencyManager.loadOrtRuntime(ctx)
        val inventario = JSONObject()
            .put("soc", android.os.Build.SOC_MODEL)
            .put("soc_manufacturer", android.os.Build.SOC_MANUFACTURER)
            .put("device", android.os.Build.DEVICE)
            .put("hardware", android.os.Build.HARDWARE)
            .put("sdk", android.os.Build.VERSION.SDK_INT)
            .put("abi", android.os.Build.SUPPORTED_ABIS.joinToString(","))
            .put("ort_version", OrtEnvironment.getEnvironment().version)
            .put("providers", OrtEnvironment.getAvailableProviders().joinToString(","))
            .put("qairt_instalado_antes", QairtDependencyManager.isInstalled(ctx))
            .put("native_deps_ativado", ativou)
            .put("ort_load_ok", cargaOrt.isSuccess)
            .put("ort_load_erro", cargaOrt.exceptionOrNull()?.message ?: "")
        recibo("inventario", inventario)
        assumeTrue("dependências nativas não ativadas", ativou)
        assumeTrue("ORT não carregou: ${cargaOrt.exceptionOrNull()?.message}", cargaOrt.isSuccess)

        if (!QairtDependencyManager.isInstalled(ctx)) {
            val t0 = System.currentTimeMillis()
            val r = QairtDependencyManager.install(ctx) { }
            val detalhe = r.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" }
            recibo("qairt_install", JSONObject()
                .put("ok", r.isSuccess).put("ms", System.currentTimeMillis() - t0)
                .put("erro", detalhe ?: ""))
            assumeTrue("QAIRT não instalado: $detalhe", r.isSuccess)
        }
        val libs = File(QairtDependencyManager.packageDir(ctx), "lib")
        val qnnGpu = File(libs, "libQnnGpu.so")
        val qnnSystem = File(libs, "libQnnSystem.so")
        recibo("qairt_libs", JSONObject()
            .put("dir", libs.absolutePath)
            .put("qnn_gpu_bytes", qnnGpu.length())
            .put("qnn_gpu_sha256", if (qnnGpu.isFile) sha256(qnnGpu) else "")
            .put("qnn_system_bytes", qnnSystem.length())
            .put("qnn_system_sha256", if (qnnSystem.isFile) sha256(qnnSystem) else ""))
        assumeTrue("libQnnGpu.so ausente após install", qnnGpu.isFile)

        QairtDependencyManager.loadQnnNatives(ctx, "gpu")

        val modelo = File(dir(), "canary_gpu.onnx")
        copiarAsset("canary_gpu.onnx", modelo)
        val ref = JSONObject(assets.open("canary_gpu_ref.json").bufferedReader().readText())
        val entrada = FloatArray(64) { ref.getJSONArray("input").getDouble(it).toFloat() }
        val esperado = FloatArray(64) { ref.getJSONArray("expected").getDouble(it).toFloat() }

        val env = OrtEnvironment.getEnvironment()
        val perfil = File(dir(), "canary_profile_${System.currentTimeMillis()}.json")
        val opts = OrtSession.SessionOptions()
        opts.setIntraOpNumThreads(4)
        opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        val qnn = HashMap<String, String>()
        qnn["backend_path"] = "libQnnGpu.so"
        opts.addQnn(qnn)
        opts.enableProfiling(perfil.absolutePath)

        var saida: FloatArray? = null
        var erro: String? = null
        var cargaMs = 0L
        var runMs = 0L
        val tCarga = System.currentTimeMillis()
        try {
            env.createSession(modelo.absolutePath, opts).use { sessao ->
                cargaMs = System.currentTimeMillis() - tCarga
                val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(entrada), longArrayOf(1, 64))
                tensor.use {
                    val tRun = System.currentTimeMillis()
                    sessao.run(mapOf("input" to it)).use { res ->
                        runMs = System.currentTimeMillis() - tRun
                        val out = res[0] as OnnxTensor
                        val fb = out.floatBuffer
                        val arr = FloatArray(fb.remaining())
                        fb.get(arr)
                        saida = arr
                    }
                }
                // Segunda execução (aquecida) para custo comparável.
                val t2 = System.currentTimeMillis()
                OnnxTensor.createTensor(env, FloatBuffer.wrap(entrada), longArrayOf(1, 64)).use { t ->
                    sessao.run(mapOf("input" to t)).close()
                }
                runMs = System.currentTimeMillis() - t2
            }
        } catch (t: Throwable) {
            erro = "${t.javaClass.simpleName}: ${t.message}"
        } finally {
            runCatching { opts.disableProfiling() }
            runCatching { opts.close() }
        }

        var maxAbs = -1.0
        var rmse = -1.0
        var finito = false
        if (saida != null) {
            val arr = saida!!
            finito = arr.all { it.isFinite() }
            var soma = 0.0
            for (i in arr.indices) {
                val d = Math.abs(arr[i] - esperado[i]).toDouble()
                if (d > maxAbs) maxAbs = d
                soma += d * d
            }
            rmse = Math.sqrt(soma / arr.size)
        }
        // O ORT acrescenta um sufixo de data/hora ao caminho pedido.
        val perfilReal = perfil.parentFile?.listFiles()
            ?.filter { it.name.startsWith(perfil.name) }
            ?.maxByOrNull { it.lastModified() }
        val provedorNoPerfil = runCatching {
            if (perfilReal == null) return@runCatching "sem arquivo"
            val arr = org.json.JSONArray(perfilReal.readText(Charsets.UTF_8))
            var qnn = 0
            var cpu = 0
            for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i) ?: continue
                if (e.optString("cat") != "Node") continue
                val prov = e.optJSONObject("args")?.optString("provider").orEmpty()
                if (prov.startsWith("QNN")) qnn++ else if (prov.startsWith("CPU")) cpu++
            }
            "qnn=$qnn cpu=$cpu"
        }.getOrDefault("erro ao ler")
        // PROVA de backend: a lib carregada no processo (não só o nome do EP).
        val maps = runCatching { File("/proc/self/maps").readText() }.getOrDefault("")
        val gpuNoProcesso = maps.contains("libQnnGpu.so")
        val htpNoProcesso = maps.contains("libQnnHtp.so")

        val rec = JSONObject()
            .put("modelo", "canary_gpu.onnx").put("modelo_sha256", sha256(modelo))
            .put("load_ms", cargaMs).put("run_ms_ultima", runMs)
            .put("erro", erro ?: "")
            .put("finito", finito).put("max_abs_diff", maxAbs).put("rmse", rmse)
            .put("perfil", perfilReal?.absolutePath ?: perfil.absolutePath)
            .put("perfil_providers", provedorNoPerfil)
            .put("libqnn_gpu_no_processo", gpuNoProcesso)
            .put("libqnn_htp_no_processo", htpNoProcesso)
        recibo("canario_gpu", rec)
        // Copia o perfil para o diretório de evidência (texto pequeno).
        runCatching {
            val d = ctx.getExternalFilesDir("gpu-evidence") ?: return@runCatching
            perfilReal?.copyTo(File(d, "canary_profile.json"), overwrite = true)
        }

        assertEquals("canário falhou: ${erro ?: ""}", "", erro ?: "")
        assertTrue("saída não finita", finito)
        // FP32 no canário: tolerância 1e-4 cobre reassociação do GPU; o valor
        // real medido vai no recibo (não se declara fp16 sem prova).
        assertTrue("max_abs_diff=$maxAbs acima de 1e-4", maxAbs in 0.0..1e-4)
        assertTrue("QNN não atribuiu nenhum nó (perfil: $provedorNoPerfil)", provedorNoPerfil.contains("qnn=") && !provedorNoPerfil.startsWith("qnn=0"))
        assertTrue("CPU também executou nós no canário (perfil: $provedorNoPerfil)", provedorNoPerfil.contains("cpu=0"))
        assertTrue("libQnnGpu.so não está no processo", gpuNoProcesso)
    }

    // ================================================================ encoder
    private fun tipoDe(v: ai.onnxruntime.ValueInfo?): String =
        (v as? ai.onnxruntime.TensorInfo)?.type?.name ?: "?"

    private fun shapeDe(v: ai.onnxruntime.ValueInfo?): String =
        (v as? ai.onnxruntime.TensorInfo)?.shape?.joinToString(",") ?: "?"

    private fun melDeD01(): FloatArray? {
        val wav = File(ctx.filesDir, "d01.wav")
        if (!wav.isFile) return null
        val leitura = WhisperOrtWav.ler(wav.readBytes())
        val pcm = (leitura as? WhisperOrtWav.Leitura.Pcm)?.amostras ?: return null
        val frontend = WhisperOrtFrontend(WhisperOrtMelFilters.slaney)
        val mel = frontend.logMel(pcm)
        // Guarda a ENTRADA exata usada (referência do host roda sobre ela).
        File(dir(), "mel_d01_f32.bin").writeBytes(floatParaBytes(mel))
        return mel
    }

    private fun floatParaBytes(arr: FloatArray): ByteArray {
        val bb = java.nio.ByteBuffer.allocate(arr.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        arr.forEach { bb.putFloat(it) }
        return bb.array()
    }

    private fun bytesParaFloat(bytes: ByteArray): FloatArray {
        val bb = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val out = FloatArray(bytes.size / 4)
        for (i in out.indices) out[i] = bb.float
        return out
    }

    private fun lerSaida(t: OnnxTensor, tipo: ai.onnxruntime.OnnxJavaType): FloatArray {
        return when (tipo) {
            ai.onnxruntime.OnnxJavaType.FLOAT -> {
                val fb = t.floatBuffer
                val out = FloatArray(fb.remaining())
                fb.get(out)
                out
            }
            ai.onnxruntime.OnnxJavaType.FLOAT16 -> {
                // ORT Java entrega fp16 como FloatBuffer (conversão na ponte).
                val fb = t.floatBuffer
                val out = FloatArray(fb.remaining())
                fb.get(out)
                out
            }
            else -> throw IllegalStateException("tipo de saída não suportado: $tipo")
        }
    }

    private fun metricas(a: FloatArray, b: FloatArray): Triple<Double, Double, Double> {
        var maxAbs = 0.0
        var soma = 0.0
        var somaRef = 0.0
        for (i in a.indices) {
            val d = Math.abs(a[i] - b[i]).toDouble()
            if (d > maxAbs) maxAbs = d
            soma += d * d
            somaRef += a[i].toDouble() * a[i].toDouble()
        }
        val rmse = Math.sqrt(soma / a.size)
        val rel = Math.sqrt(soma / maxOf(somaRef, 1e-12))
        return Triple(maxAbs, rmse, rel)
    }

    @Test
    fun encoderFp16Gpu() {
        assumeTrue("aparelho não Qualcomm", QairtDependencyManager.isQualcommDevice())
        assumeTrue("deps nativas", NativeDependencyManager.activateIfInstalled(ctx))
        assumeTrue("ORT", NativeDependencyManager.loadOrtRuntime(ctx).isSuccess)
        assumeTrue("QAIRT", QairtDependencyManager.isInstalled(ctx))
        QairtDependencyManager.loadQnnNatives(ctx, "gpu")

        val pasta = ctx.getExternalFilesDir("gpu-r1") ?: error("sem external files dir")
        val modelo = File(pasta, "encoder_fp16.onnx")
        assumeTrue("encoder fp16 ausente em ${modelo.absolutePath}", modelo.isFile)
        assumeTrue("encoder fp16 com tamanho inesperado: ${modelo.length()}", modelo.length() == 1_274_342_603L)

        val mel = melDeD01() ?: error("d01.wav ausente/sem PCM")
        val env = OrtEnvironment.getEnvironment()

        fun abrir(gpu: Boolean): OrtSession {
            val opts = OrtSession.SessionOptions()
            opts.setIntraOpNumThreads(4)
            opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            if (gpu) {
                val qnn = HashMap<String, String>()
                qnn["backend_path"] = "libQnnGpu.so"
                opts.addQnn(qnn)
            } else {
                opts.addCPU(true)
            }
            return env.createSession(modelo.absolutePath, opts)
        }

        fun rodar(sess: OrtSession, entrada: OnnxTensor, nome: String, reps: Int): Pair<FloatArray, Long> {
            var saida = FloatArray(0)
            var ms = 0L
            for (i in 0 until reps) {
                val t0 = System.currentTimeMillis()
                sess.run(mapOf(nome to entrada)).use { res ->
                    val t = res[0] as OnnxTensor
                    val info = sess.outputInfo.values.first()
                    saida = lerSaida(t, (info as ai.onnxruntime.TensorInfo).type)
                }
                ms = System.currentTimeMillis() - t0
            }
            return saida to ms
        }

        val rec = JSONObject().put("modelo_bytes", modelo.length())
        // -------- CPU (mesma candidata fp16) --------
        var cpuOut: FloatArray? = null
        var cpuErro: String? = null
        var cpuLoad = 0L
        var cpuMs = 0L
        try {
            val t0 = System.currentTimeMillis()
            abrir(gpu = false).use { sess ->
                cpuLoad = System.currentTimeMillis() - t0
                val nome = sess.inputNames.first()
                rec.put("input_nome", nome)
                rec.put("input_tipo", tipoDe(sess.inputInfo[nome]?.info))
                rec.put("input_shape", shapeDe(sess.inputInfo[nome]?.info))
                rec.put("output_tipo_cpu", tipoDe(sess.outputInfo.values.first().info))
                OnnxTensor.createTensor(env, FloatBuffer.wrap(mel), longArrayOf(1, 128, 3000)).use { t ->
                    val (o, ms) = rodar(sess, t, nome, 1)
                    cpuOut = o
                    cpuMs = ms
                }
            }
        } catch (t: Throwable) {
            cpuErro = "${t.javaClass.simpleName}: ${t.message}"
        }
        rec.put("cpu_load_ms", cpuLoad).put("cpu_run_ms", cpuMs).put("cpu_erro", cpuErro ?: "")
        rec.put("cpu_out_bytes", cpuOut?.size ?: 0)
        cpuOut?.let { File(dir(), "encoder_cpu_out_f32.bin").writeBytes(floatParaBytes(it)) }

        // -------- GPU (QNN Adreno) --------
        val perfil = File(dir(), "encoder_profile_${System.currentTimeMillis()}.json")
        var gpuOut: FloatArray? = null
        var gpuErro: String? = null
        var gpuLoad = 0L
        var gpuMs = 0L
        var gpuMs3 = ArrayList<Long>()
        try {
            val opts = OrtSession.SessionOptions()
            opts.setIntraOpNumThreads(4)
            opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            val qnn = HashMap<String, String>()
            qnn["backend_path"] = "libQnnGpu.so"
            opts.addQnn(qnn)
            opts.enableProfiling(perfil.absolutePath)
            val t0 = System.currentTimeMillis()
            env.createSession(modelo.absolutePath, opts).use { sess ->
                gpuLoad = System.currentTimeMillis() - t0
                val nome = sess.inputNames.first()
                rec.put("gpu_input_tipo", tipoDe(sess.inputInfo[nome]?.info))
                rec.put("gpu_input_shape", shapeDe(sess.inputInfo[nome]?.info))
                rec.put("gpu_output_tipo", tipoDe(sess.outputInfo.values.first().info))
                OnnxTensor.createTensor(env, FloatBuffer.wrap(mel), longArrayOf(1, 128, 3000)).use { t ->
                    // aquecimento
                    sess.run(mapOf(nome to t)).close()
                    for (i in 0 until 3) {
                        val ti = System.currentTimeMillis()
                        sess.run(mapOf(nome to t)).use { res ->
                            val tt = res[0] as OnnxTensor
                            gpuOut = lerSaida(tt, (sess.outputInfo.values.first().info as ai.onnxruntime.TensorInfo).type)
                        }
                        gpuMs3.add(System.currentTimeMillis() - ti)
                    }
                }
            }
            gpuMs = gpuMs3.lastOrNull() ?: 0L
            runCatching { opts.disableProfiling() }
            runCatching { opts.close() }
        } catch (t: Throwable) {
            gpuErro = "${t.javaClass.simpleName}: ${t.message}"
        }
        gpuOut?.let { File(dir(), "encoder_gpu_out_f32.bin").writeBytes(floatParaBytes(it)) }

        val perfilReal = perfil.parentFile?.listFiles()
            ?.filter { it.name.startsWith(perfil.name) }?.maxByOrNull { it.lastModified() }
        val provedores = runCatching {
            if (perfilReal == null) return@runCatching "sem arquivo"
            val arr = org.json.JSONArray(perfilReal.readText(Charsets.UTF_8))
            var qnn = 0
            var cpu = 0
            for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i) ?: continue
                if (e.optString("cat") != "Node") continue
                val p = e.optJSONObject("args")?.optString("provider").orEmpty()
                if (p.startsWith("QNN")) qnn++ else if (p.startsWith("CPU")) cpu++
            }
            "qnn=$qnn cpu=$cpu"
        }.getOrDefault("erro")
        val maps = runCatching { File("/proc/self/maps").readText() }.getOrDefault("")

        rec.put("gpu_load_ms", gpuLoad)
        rec.put("gpu_run_ms", gpuMs)
        rec.put("gpu_runs_ms", gpuMs3.joinToString(","))
        rec.put("gpu_erro", gpuErro ?: "")
        rec.put("gpu_out_bytes", gpuOut?.size ?: 0)
        rec.put("gpu_perfil_providers", provedores)
        rec.put("libqnn_gpu_no_processo", maps.contains("libQnnGpu.so"))
        if (gpuOut != null) {
            val finito = gpuOut!!.all { it.isFinite() }
            rec.put("gpu_finito", finito)
            rec.put("gpu_shape_ok", gpuOut!!.size == 1500 * 1280)
            if (cpuOut != null && cpuOut!!.size == gpuOut!!.size) {
                val (mx, rmse, rel) = metricas(cpuOut!!, gpuOut!!)
                rec.put("cpu_vs_gpu_max_abs", mx).put("cpu_vs_gpu_rmse", rmse).put("cpu_vs_gpu_rel", rel)
            }
        }
        recibo("encoder_fp16", rec)

        assertEquals("GPU falhou: ${gpuErro ?: ""}", "", gpuErro ?: "")
        assertTrue("saída do encoder não finita", gpuOut != null && gpuOut!!.all { it.isFinite() })
        assertEquals("shape da saída do encoder", 1500 * 1280, gpuOut!!.size)
        assertTrue("libQnnGpu.so não está no processo", maps.contains("libQnnGpu.so"))
        assertTrue("QNN não executou nós: $provedores", provedores.startsWith("qnn=") && !provedores.startsWith("qnn=0"))
    }
}
