package br.gov.sp.pcsp.launcher

import android.content.Context
import android.net.Uri
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.regex.Pattern

/**
 * ORDEM ESPECIAL 2 (seletores da tela Whisper) — aceite no aparelho.
 *
 * T01: roda as 6 combinações motor × hardware pela ROTA DE PRODUÇÃO com o motor
 * e o hardware passados COMO PARÂMETRO (nunca pela preferência do usuário):
 * - Onnx RT: `WhisperOrtRoute.transcribe(..., hardware =, threads =, vad =)` —
 *   a mesma função que a tela chama, com o `hardware` explícito;
 * - Whisper.cpp: `WhisperSessions.shared` (o MESMO controller da tela) com
 *   `loadModel(..., backendKind =)` e `threadsForJob` — kind 0/2/1.
 *
 * O clipe de fixture é copiado para o armazenamento privado do app antes de
 * qualquer célula. Cada célula grava UM recibo JSONL com outcome, rota efetiva,
 * tempo e threads — sem texto transcrito (só o comprimento).
 *
 * T02.2: injeta "pacote da NPU ausente" por diretório temporário (hook
 * `WhisperOrtNpuPacote.raizParaTeste`) e prova, pela interface, que escolher NPU
 * abre o diálogo de download e que "Agora não" volta para CPU. Nada é baixado e
 * o pacote real do aparelho não é tocado.
 *
 * `ui`: só abre a tela real e espera o arquivo-sentinela do host (para os dumps
 * de uiautomator de T02.1/T02.3), sem dirigir nada por conta própria.
 */
@RunWith(AndroidJUnit4::class)
class WhisperEspecialSeletores2Test {

    private val ctx: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val args get() = InstrumentationRegistry.getArguments()

    private fun raiz(): File = File(ctx.filesDir, "especial-seletores2").apply { mkdirs() }

    private fun recibos(): File = File(raiz(), "recibos.jsonl")

    private fun linha(json: JSONObject) {
        synchronized(this) {
            recibos().appendText(json.toString() + "\n", Charsets.UTF_8)
        }
        Log.i(TAG, json.toString())
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { entrada ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val lidos = entrada.read(buffer)
                if (lidos <= 0) break
                md.update(buffer, 0, lidos)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun manterTelaAcesa(): PowerManager.WakeLock? = runCatching {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "SIG:EspecialSeletores2",
        ).apply { acquire(90 * 60_000L) }
    }.getOrNull()

    /** Copia UM clipe de fixture para o armazenamento privado do app. */
    private fun clipe(): File {
        val destino = File(raiz(), "clipe.wav")
        if (!destino.isFile || destino.length() <= 0L) {
            val origem = File(ctx.filesDir, "d01.wav")
            assumeTrue("fixture d01.wav ausente no armazenamento privado", origem.isFile)
            origem.copyTo(destino, overwrite = true)
        }
        return destino
    }

    private fun turbinaCpp(): File = File(ctx.getExternalFilesDir("whisper_models"), "ggml-large-v3-turbo.bin").also {
        assumeTrue("modelo cpp turbo ausente", it.isFile)
    }

    private fun pequenoCpp(): File = File(ctx.getExternalFilesDir("whisper_models"), "ggml-small.bin").also {
        assumeTrue("modelo cpp small ausente", it.isFile)
    }

    private fun versaoPacoteNpu(): String = runCatching {
        val estado = WhisperOrtNpuPacote.estado(ctx)
        when (estado) {
            is WhisperOrtNpuPacote.Estado.Pronto -> "pronto:${estado.manifesto.versao}"
            is WhisperOrtNpuPacote.Estado.Ausente -> "ausente:${estado.motivo}"
            is WhisperOrtNpuPacote.Estado.Invalido -> "invalido:${estado.motivo}"
        }
    }.getOrDefault("erro:${'$'}{runCatching { WhisperOrtNpuPacote.estado(ctx) }.exceptionOrNull()}")

    private fun idSelecionado(id: String): Boolean {
        val filtro = args.getString("celulas") ?: return true
        return filtro.split(",").map { it.trim() }.any { it.equals(id, ignoreCase = true) }
    }

    // ------------------------------------------------------------------ ORT

    private fun interpretarOrt(resultado: WhisperOrtRoute.Result): JSONObject = when (resultado) {
        is WhisperOrtRoute.Result.Done -> JSONObject()
            .put("outcome", resultado.outcome)
            .put("rota_efetiva", resultado.hardwareLabel ?: "?")
            .put("ms", resultado.millis)
            .put("open_ms", resultado.openMillis)
            .put("cache_hit", resultado.cacheHit)
            .put("chars", resultado.text.length)
        is WhisperOrtRoute.Result.Cancelled -> JSONObject()
            .put("outcome", "CANCELLED")
            .put("rota_efetiva", resultado.hardwareLabel ?: "?")
            .put("ms", resultado.millis)
            .put("chars", resultado.text.length)
        is WhisperOrtRoute.Result.Refused -> JSONObject()
            .put("outcome", "REFUSED")
            .put("motivo", resultado.reason)
        is WhisperOrtRoute.Result.Failed -> JSONObject()
            .put("outcome", "FAILED")
            .put("motivo", resultado.reason)
    }

    private fun celulaOrt(
        cenario: ActivityScenario<WhisperActivity>,
        id: String,
        hardware: WhisperSelectors.Hardware,
        threads: Int,
        vad: LocalVad.Selection,
    ) {
        val clipe = clipe()
        val latch = CountDownLatch(1)
        val caixa = AtomicReference<JSONObject?>(null)
        val despacho = AtomicReference<String>("nao-despercou")
        val t0 = SystemClock.elapsedRealtime()
        cenario.onActivity { activity ->
            try {
                val job = WhisperOrtRoute.transcribe(
                    activity,
                    Uri.fromFile(clipe),
                    onResult = { resultado ->
                        caixa.set(interpretarOrt(resultado))
                        latch.countDown()
                    },
                    threads = threads,
                    hardware = hardware,
                    vad = vad,
                )
                despacho.set(if (job == null) "nulo" else "despachado")
                if (job == null) latch.countDown()
            } catch (t: Throwable) {
                caixa.set(JSONObject().put("outcome", "EXCECAO").put("motivo", t.toString()))
                despacho.set("excecao")
                latch.countDown()
            }
        }
        val fechou = latch.await(360L, TimeUnit.SECONDS)
        val ms = SystemClock.elapsedRealtime() - t0
        val base = JSONObject()
            .put("schema", SCHEMA)
            .put("celula", id)
            .put("motor", "ort")
            .put("hardware_pedido", hardware.label)
            .put("rota_parametro", hardware.route)
            .put("threads_pedidas", threads)
            .put("vad", vad.label)
            .put("despacho", despacho.get())
            .put("fechou", fechou)
            .put("ms_parede", ms)
            .put("clipe", clipe.name)
            .put("clipe_sha256", sha256(clipe))
            .put("clipe_bytes", clipe.length())
            .put("pid", android.os.Process.myPid())
        val desfecho = caixa.get()
        if (desfecho == null) {
            base.put("outcome", if (fechou) "SEM_RESULTADO" else "TIMEOUT")
        } else {
            for (chave in desfecho.keys()) base.put(chave, desfecho.get(chave))
        }
        linha(base)
    }

    private fun esperarAdmissaoHtp(maxMs: Long = 40_000L) {
        val fim = System.currentTimeMillis() + maxMs
        while (System.currentTimeMillis() < fim) {
            val rota = runCatching { WhisperOrtHtpRota.rotaDoPedido() }.getOrDefault("cpu")
            if (WhisperOrtHtpAdmissao.isHtp(rota)) return
            Thread.sleep(250)
        }
        Log.w(TAG, "admissao HTP nao convergiu em ${maxMs}ms; motivo=${WhisperOrtHtpRota.motivoRota()}")
    }

    // ------------------------------------------------------- whisper.cpp

    private fun celulaCpp(id: String, kind: Int, threads: Int, modelo: File, variante: String) {
        val clipe = clipe()
        val controle = WhisperSessions.shared
        val anterior = controle.threadsForJob
        val t0 = SystemClock.elapsedRealtime()
        var geracao = -1L
        var carregou = false
        var status = ""
        var chars = 0
        var erro = ""
        var backendEfetivo = -1
        var params = ""
        val paramsRef = AtomicReference("")
        try {
            controle.threadsForJob = { threads }
            geracao = controle.beginSession()
            carregou = controle.loadModel(geracao, modelo.absolutePath, kind, false)
            if (!carregou) {
                erro = runCatching { WhisperNative.lastError() }.getOrDefault("")
            } else {
                val callback = object : WhisperNative.Callback {
                    override fun onSegment(text: String, startMs: Long, endMs: Long) = Unit
                    override fun onProgress(progress: Int) = Unit
                    override fun onNativeLog(line: String) {
                        if (line.startsWith("native params") || line.startsWith("SIGThreads")) {
                            paramsRef.set(line)
                        }
                    }
                }
                val (texto, situacao) = controle.transcribeAndStatus(
                    geracao, clipe.absolutePath, "pt", 5, 5, false, false, "", callback,
                )
                status = situacao
                chars = texto.length
                backendEfetivo = runCatching { WhisperNative.effectiveBackendKind() }.getOrDefault(-1)
                params = paramsRef.get()
            }
        } catch (t: Throwable) {
            erro = t.toString()
        } finally {
            if (geracao >= 0L) runCatching { controle.releaseAsync(geracao) }
            controle.threadsForJob = anterior
            Thread.sleep(1_000L)
        }
        val ms = SystemClock.elapsedRealtime() - t0
        linha(JSONObject()
            .put("schema", SCHEMA)
            .put("celula", id)
            .put("motor", "cpp")
            .put("backend_pedido", kind)
            .put("modelo", modelo.name)
            .put("variante", variante)
            .put("threads_pedidas", threads)
            .put("vad", "VAD: Nenhum")
            .put("load_ok", carregou)
            .put("status", status)
            .put("backend_efetivo", backendEfetivo)
            .put("chars", chars)
            .put("ms_parede", ms)
            .put("erro", erro)
            .put("native_params", params)
            .put("clipe", clipe.name)
            .put("clipe_sha256", sha256(clipe))
            .put("clipe_bytes", clipe.length())
            .put("pid", android.os.Process.myPid()))
    }

    /** Combinação recusada pela guarda de carga: registra e roda a variante aceita.
     *  A recusa da guarda OpenCL (Adreno A7X + large/turbo) NÃO falha a carga: ela
     *  cai para a CPU, logo o critério é o backend EFETIVO divergir do pedido. */
    private fun celulaCppComGuarda(id: String, kind: Int, threads: Int) {
        val turbo = turbinaCpp()
        celulaCpp(id, kind, threads, turbo, "turbo")
        val recebido = ultimoRecibo(id)
        val efetivo = recebido?.optInt("backend_efetivo", -1) ?: -1
        val falhou = recebido == null ||
            recebido.optBoolean("load_ok", false).not() ||
            recebido.optString("status") != "ok" ||
            efetivo != kind
        if (falhou) {
            val motivo = recebido?.optString("erro").orEmpty()
            Log.w(TAG, "celula $id: turbo nao rodou no backend pedido (efetivo=$efetivo): $motivo")
            celulaCpp("$id-small", kind, threads, pequenoCpp(), "small")
        }
    }

    private fun ultimoRecibo(id: String): JSONObject? = runCatching {
        if (!recibos().isFile) return null
        recibos().readLines(Charsets.UTF_8).lastOrNull { it.contains("\"celula\":\"$id\"") }
            ?.let { JSONObject(it) }
    }.getOrNull()

    // -------------------------------------------------------------- testes

    @Test
    fun aceite() {
        assumeTrue("fluxo de aceite precisa do clipe", File(ctx.filesDir, "d01.wav").isFile)
        val wakelock = manterTelaAcesa()
        try {
            // Dá tempo ao coletor de logcat do host (--pid) de anexar antes da 1ª rota.
            Thread.sleep(5_000L)
            val clipe = clipe()
            linha(JSONObject()
                .put("schema", SCHEMA)
                .put("celula", "ABERTURA")
                .put("clipe", clipe.name)
                .put("clipe_sha256", sha256(clipe))
                .put("clipe_bytes", clipe.length())
                .put("pacote_npu", versaoPacoteNpu())
                .put("rota_pre", runCatching { WhisperOrtHtpRota.rotaDoPedido() }.getOrDefault("?"))
                .put("pid", android.os.Process.myPid()))

            ActivityScenario.launch(WhisperActivity::class.java).use { cenario ->
                // A tela chama `iniciar` dentro do primeiro job; aqui ele é
                // chamado antes para a ADMISSAO ser medida no estado real.
                runCatching { WhisperOrtHtpRota.iniciar(ctx) }
                esperarAdmissaoHtp()
                linha(JSONObject()
                    .put("schema", SCHEMA)
                    .put("celula", "ADMISSAO")
                    .put("rota", runCatching { WhisperOrtHtpRota.rotaDoPedido() }.getOrDefault("?"))
                    .put("motivo", WhisperOrtHtpRota.motivoRota())
                    .put("conferencia_ms", WhisperOrtHtpRota.conferenciaMs()))

                if (idSelecionado("A1")) celulaOrt(cenario, "A1", WhisperSelectors.Hardware.CPU, 4, LocalVad.Selection())
                if (idSelecionado("A2")) celulaOrt(cenario, "A2", WhisperSelectors.Hardware.HYBRID, 4, LocalVad.Selection())
                if (idSelecionado("A3")) celulaOrt(cenario, "A3", WhisperSelectors.Hardware.NPU, 4, LocalVad.Selection())
                if (idSelecionado("B1")) {
                    celulaOrt(
                        cenario, "B1", WhisperSelectors.Hardware.HYBRID, 4,
                        LocalVad.Selection(LocalVad.Mode.SILERO_CPU, 2),
                    )
                }
                if (idSelecionado("B2")) {
                    celulaOrt(
                        cenario, "B2", WhisperSelectors.Hardware.CPU, 4,
                        LocalVad.Selection(LocalVad.Mode.WEBRTC, 1),
                    )
                }
                if (idSelecionado("C1")) celulaOrt(cenario, "C1", WhisperSelectors.Hardware.HYBRID, 2, LocalVad.Selection())
            }

            if (idSelecionado("A4")) celulaCpp("A4", 0, 4, turbinaCpp(), "turbo")
            if (idSelecionado("A5")) celulaCppComGuarda("A5", 2, 4)
            if (idSelecionado("A6")) celulaCppComGuarda("A6", 1, 4)
            if (idSelecionado("C2")) celulaCpp("C2", 0, 2, turbinaCpp(), "turbo")

            linha(JSONObject().put("schema", SCHEMA).put("celula", "FIM").put("pid", android.os.Process.myPid()))
        } finally {
            runCatching { wakelock?.release() }
        }
    }

    /**
     * T02.2 — pacote AUSENTE injetado: escolher NPU abre o diálogo de download;
     * "Agora não" volta o seletor para CPU. Nada é baixado.
     */
    @Test
    fun dialogoNpuAusente() {
        val temporario = File(ctx.cacheDir, "npu-ausente-teste").apply {
            deleteRecursively()
            mkdirs()
        }
        val prefs = ctx.getSharedPreferences("whisper", Context.MODE_PRIVATE)
        val antes = prefs.getString("ort_hardware", null)
        WhisperOrtNpuPacote.raizParaTeste = temporario
        val dispositivo = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        var abriuDialogo = false
        var botaoAgoraNao = false
        var voltouParaCpu = false
        try {
            assumeTrue("SoC não admitido para a NPU", WhisperOrtHtpAdmissao.socDe(android.os.Build.SOC_MODEL)?.habilitado == true)
            prefs.edit().putString("ort_hardware", "CPU").commit()
            ActivityScenario.launch(WhisperActivity::class.java).use { cenario ->
                cenario.onActivity { activity ->
                    aplicarTurbo(activity)
                    activity.findViewById<View>(R.id.button_backend)?.performClick()
                }
                val itemNpu = dispositivo.wait(Until.findObject(By.text(NPU_EXATO)), 8_000L)
                if (itemNpu == null) {
                    dispositivo.dumpWindowHierarchy(File(raiz(), "T02-menu-sem-npu.xml"))
                    linha(JSONObject().put("schema", SCHEMA).put("celula", "T02.2")
                        .put("outcome", "FAIL").put("motivo", "item NPU ausente no menu de hardware"))
                    return@use
                }
                itemNpu.click()
                val agoraNao = dispositivo.wait(Until.findObject(By.text(AGORA_NAO)), 12_000L)
                abriuDialogo = agoraNao != null
                dispositivo.dumpWindowHierarchy(
                    File(raiz(), if (abriuDialogo) "T02-dialogo-npu.xml" else "T02-sem-dialogo.xml"),
                )
                if (agoraNao != null) {
                    agoraNao.click()
                    botaoAgoraNao = true
                    Thread.sleep(1_500L)
                    voltouParaCpu = prefs.getString("ort_hardware", null) == "CPU"
                    cenario.onActivity { activity ->
                        val rotulo = activity.findViewById<android.widget.TextView>(R.id.button_backend)?.text?.toString()
                        Log.i(TAG, "T02.2 hardware apos recusa: $rotulo")
                    }
                }
            }
            linha(JSONObject()
                .put("schema", SCHEMA)
                .put("celula", "T02.2")
                .put("outcome", if (abriuDialogo && botaoAgoraNao && voltouParaCpu) "PASS" else "FAIL")
                .put("estado_injetado", "ausente (diretorio temporario)")
                .put("abriu_dialogo", abriuDialogo)
                .put("clicou_agora_nao", botaoAgoraNao)
                .put("voltou_para_cpu", voltouParaCpu)
                .put("pref_ort_hardware_depois", prefs.getString("ort_hardware", null))
                .put("pacote_real_intacto", versaoPacoteNpu())
                .put("pid", android.os.Process.myPid()))
        } finally {
            WhisperOrtNpuPacote.raizParaTeste = null
            prefs.edit().putString("ort_hardware", antes ?: "CPU").commit()
        }
    }

    /** Só abre a tela real e espera a sentinela do host (T02.1). */
    @Test
    fun ui() {
        val limiteMs = (args.getString("ui_ms")?.toLongOrNull() ?: 240_000L).coerceIn(30_000L, 1_800_000L)
        val sentinela = File(raiz(), "ui.done")
        runCatching { sentinela.delete() }
        val wakelock = manterTelaAcesa()
        try {
            ActivityScenario.launch(WhisperActivity::class.java).use {
                val fim = System.currentTimeMillis() + limiteMs
                while (System.currentTimeMillis() < fim) {
                    if (sentinela.isFile) break
                    Thread.sleep(500L)
                }
            }
        } finally {
            runCatching { wakelock?.release() }
        }
    }

    private fun aplicarTurbo(activity: android.app.Activity) {
        val modelos = WhisperActivity::class.java
            .getDeclaredMethod("officialModels")
            .apply { isAccessible = true }
            .invoke(activity) as List<*>
        val turbo = modelos.filterNotNull().firstOrNull { modelo ->
            runCatching {
                modelo.javaClass.declaredFields.first { it.name == "label" }
                    .apply { isAccessible = true }.get(modelo) == "v3Turbo"
            }.getOrDefault(false)
        } ?: return
        WhisperActivity::class.java
            .getDeclaredMethod("applyModelSelection", turbo.javaClass)
            .apply { isAccessible = true }
            .invoke(activity, turbo)
    }

    private companion object {
        const val TAG = "ESPECIAL2"
        const val SCHEMA = "sig-whisper-especial-seletores2-aceite/v1"

        val NPU_EXATO: Pattern = Pattern.compile("(?i)^NPU$")
        val AGORA_NAO: Pattern = Pattern.compile("(?i)^AGORA N[ÃA]O$")
    }
}
