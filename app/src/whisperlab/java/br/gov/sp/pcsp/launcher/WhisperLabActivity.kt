package br.gov.sp.pcsp.launcher

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.TextView
import org.json.JSONObject
import br.gov.sp.pcsp.whisperlab.LabUtf8Probe
import java.io.File
import java.security.MessageDigest
import java.util.Properties
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * VARIANTE DE LABORATÓRIO (flavor `whisperlab`, applicationId próprio,
 * somente debug). Executa cenários de sessão/ownership/UTF-8/callbacks em ART
 * com a biblioteca candidata carregada do sandbox privado do lab.
 *
 * Uso: adb shell am start -n br.gov.sp.pcsp.whisperlab/.WhisperLabActivity \
 *        --es scenario s1 --es run r1
 *
 * Todo evento vira uma linha JSONL em filesDir/art-lab/cenarios.jsonl (writer
 * dedicado, nunca na main thread), espelhada no logcat (tag WhisperLab).
 * Heartbeat: Runnable de 100 ms na main thread medindo atraso máximo
 * (`hb`/`hb_final`) — evidência de bloqueio em beginSession/cancel/release.
 */
class WhisperLabActivity : Activity() {

    companion object {
        /** R15/A2 — cache de hash de modelo (arquivo, bytes, mtime) → sha256. */
        private val modelShaCache = HashMap<String, String>()
        const val TAG = "WhisperLab"
        private const val HB_MS = 100L
    }

    /** R15/B1 — prazo curto do wake lock pedido por este ensaio (0 = padrão). */
    private var labDestroyAfterMs = -1L
    private var labLeaseMs = 0L
    /** R15/B1 — suprime progresso (prova do braço de cancelamento). */
    private var labLeaseIgnoreProgress = false
    private lateinit var rootDir: File
    private lateinit var outDir: File
    private lateinit var corpusDir: File
    private lateinit var modelDir: File
    private lateinit var jsonlFile: File
    private lateinit var stateFile: File

    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "lab-scenario") }
    // R56/F01 — lifetime EXPLÍCITO do writer. A auditoria mediu 28 threads
    // `lab-log-writer` e 91 `lab-scenario` vivas no PID 951: `startWriter`
    // abria um loop infinito por Activity e `onDestroy` não encerrava nem o
    // writer nem o executor local. `isDaemon` não encerra thread em processo
    // Android vivo.
    @Volatile private var writerAtivo = false
    private var writerThread: Thread? = null
    private val longOps: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "lab-longop") }
    private val inference: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "lab-inference") }

    private val logQueue = LinkedBlockingQueue<String>()

    // Controller COMPARTILHADO no processo do lab (espelha WhisperSessions.shared
    // de produção): a geração de sessão é global, então uma nova instância da
    // Activity invalida posts/callbacks da anterior — exatamente o contrato
    // sob teste em s2/s3/s5/s6.
    private val ctrl: WhisperSessionController get() = WhisperLab.controller

    private var scenario = ""
    private var runId = ""
    private var phase = 1
    private var mode = ""
    private var backend = "cpu"
    private var backend2 = ""
    private var modelOverride: String? = null
    private var model2Override: String? = null
    private var killMs = 0
    private var thrower = false

    @Volatile
    private var lastGen = 0L

    private var hbLast = 0L
    private var hbMax = 0L
    private var hbTicks = 0
    private var hbFlush = 0L

    private val hbRunnable = object : Runnable {
        override fun run() {
            val now = SystemClock.uptimeMillis()
            if (hbLast != 0L) {
                hbTicks++
                val late = (now - hbLast - HB_MS).coerceAtLeast(0L)
                if (late > hbMax) hbMax = late
            }
            hbLast = now
            if (now - hbFlush >= 1000L) {
                hbFlush = now
                labLog("hb", "max_ms" to hbMax, "ticks" to hbTicks)
            }
            mainHandler.postDelayed(this, HB_MS)
        }
    }

    // ------------------------------------------------------------------ setup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // R14/B4 — LIGA o DIAG de trabalho em voo (a lib é carregada depois; o
        // getenv é lido uma única vez, na inicialização estática do módulo).
        runCatching {
            WhisperNative.setDiagEnv("SIG_WHISPER_VK_INFLIGHT_DIAG", "1")
            WhisperNative.setDiagEnv("SIG_WHISPER_CANCEL_TRACE", "1")
            // R19/B2 — janela em voo (MB) configuravel por celula.
            intent?.let { i ->
                // R25/GPU-K1 — liga o recurso de conclusao por unidade
                // (timeline). Desligado por padrao: a admissao usa a fence.
                // R25/ERR-NUM — grava tokens/passos da tentativa (TF record).
                i.getStringExtra("tfRecord")?.takeIf { it.isNotBlank() }?.let { pref ->
                    WhisperNative.setDiagEnv("SIG_WHISPER_TF_RECORD", pref)
                }
                // R26/GPU — intervencao de diagnostico: sync real encoder->cross.
                // R26/GPU — dreno a cada N submits (diagnostico H3).
                if (i.hasExtra("drainN") && i.getIntExtra("drainN", 0) > 0) {
                    WhisperNative.setDiagEnv("SIG_WHISPER_DRAIN_N", i.getIntExtra("drainN", 0).toString())
                }
                // R26/GPU — dreno apos cada submit (diagnostico H3).
                if (i.hasExtra("witness") && i.getBooleanExtra("witness", false)) {
                    WhisperNative.setDiagEnv("SIG_VK_WITNESS", "1")
                }
                if (i.hasExtra("drainEach") && i.getBooleanExtra("drainEach", false)) {
                    WhisperNative.setDiagEnv("SIG_WHISPER_DRAIN_EACH", "1")
                }
                if (i.hasExtra("syncEncCross") && i.getBooleanExtra("syncEncCross", false)) {
                    WhisperNative.setDiagEnv("SIG_WHISPER_SYNC_ENC_CROSS", "1")
                }
                if (i.hasExtra("ledgerTl") && i.getBooleanExtra("ledgerTl", false)) {
                    WhisperNative.setDiagEnv("SIG_VK_LEDGER_TL", "1")
                }
                // R30/D1 — PERFIL POR OPERADOR (laboratorio): liga o logger do
                // backend ANTES da inicializacao do modulo Vulkan. Coorte propria
                // (PROFILE), nunca usada no benchmark limpo.
                if (i.hasExtra("perfLogger") && i.getBooleanExtra("perfLogger", false)) {
                    WhisperNative.setDiagEnv("GGML_VK_PERF_LOGGER", "1")
                }
                // R35/B3 — recorte de softmax (laboratorio): head 0 + N linhas de
                // query com todas as chaves. Default OFF; so existe no DIAG.
                // R40 — fixa o passo do dump de logits (mesmo passo nas duas corridas).
                if (i.hasExtra("dumpStepOnly")) {
                    WhisperNative.setDiagEnv("SIG_DUMP_STEP_ONLY", i.getIntExtra("dumpStepOnly", -1).toString())
                }
                if (i.hasExtra("softmaxSlice") && i.getBooleanExtra("softmaxSlice", false)) {
                    WhisperNative.setDiagEnv("SIG_DUMP_SOFTMAX_SLICE", "1")
                    WhisperNative.setDiagEnv("SIG_DUMP_SOFTMAX_MAX",
                        (if (i.hasExtra("softmaxMax")) i.getIntExtra("softmaxMax", 2) else 2).toString())
                    WhisperNative.setDiagEnv("SIG_DUMP_SOFTMAX_ROWS",
                        (if (i.hasExtra("softmaxRows")) i.getIntExtra("softmaxRows", 4) else 4).toString())
                }
                if (i.hasExtra("submitMb")) {
                    val mb = i.getIntExtra("submitMb", 0)
                    // R25/REQ — adaptador LEGADO de entrada (antes da fotografia);
                    // a decisão da tentativa vem do pedido, espelhado no config().
                    WhisperNative.setDiagEnv("SIG_VK_SUBMIT_MB", mb.toString())
                }
                // R20/B2 — limite de trabalho EM VOO (backpressure).
                if (i.hasExtra("inflightLimit")) {
                    WhisperNative.setDiagEnv("SIG_VK_INFLIGHT_LIMIT",
                        i.getIntExtra("inflightLimit", 0).toString())
                }
                // R20/B2 — controle serial: espera o lote já submetido.
                if (i.hasExtra("serialBatch") && i.getBooleanExtra("serialBatch", false)) {
                    WhisperNative.setDiagEnv("SIG_VK_SERIAL_BATCH", "1")
                }
                // R21/B2 — limite K de submissões em voo.
                if (i.hasExtra("inflightK")) {
                    WhisperNative.setDiagEnv("SIG_VK_INFLIGHT_K",
                        i.getIntExtra("inflightK", 0).toString())
                }
            }
            // R18/B1 — o rastro sobrevive à rotação do logcat.
            runCatching {
                WhisperNative.setDiagEnv(
                    "SIG_CANCEL_TRACE_PATH",
                    File(filesDir, "art-lab/cancel-trace.txt").absolutePath
                )
            }
        }
        rootDir = File(filesDir, "art-lab")
        outDir = File(rootDir, "out")
        corpusDir = File(filesDir, "whisperlab-corpus")
        modelDir = File(filesDir, "whisperlab-model")
        jsonlFile = File(rootDir, "cenarios.jsonl")
        stateFile = File(rootDir, "state.txt")
        rootDir.mkdirs(); outDir.mkdirs()
        // R14/E4 — observa o estado da TELA com clock MONOTÔNICO (prova de
        // leitura/ENQUEUE DEPOIS do OFF, não só "houve um OFF").
        runCatching { WhisperLabDrive.startScreenWatcher(this) }

        startWriter()

        // R16/B4 — extra `askNotif`: pede POST_NOTIFICATIONS de verdade para
        // exercitar o PendingIntent real da notificação (o runner clica
        // "Permitir" no diálogo do sistema).
        if (intent?.getBooleanExtra("askNotif", false) == true &&
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            androidx.core.app.ActivityCompat.checkSelfPermission(
                this, android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            androidx.core.app.ActivityCompat.requestPermissions(
                this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 6108)
            labLog("asknotif_pedido", "ok" to true)
        }

        scenario = intent?.getStringExtra("scenario") ?: "s1"
        runId = intent?.getStringExtra("run") ?: "r0"
        phase = intent?.getIntExtra("phase", 1) ?: 1
        mode = intent?.getStringExtra("mode") ?: ""
        backend = intent?.getStringExtra("backend") ?: "cpu"
        backend2 = intent?.getStringExtra("backend2") ?: ""
        // R15/C1 — modo R6 do ensaio (cold start obrigatorio: o modo e' lido
        // na primeira inicializacao do device Vulkan).
        intent?.getStringExtra("faR6")?.takeIf { it.isNotEmpty() }?.let { modo ->
            WhisperNative.setFaR6ModeEnv(modo)
            labLog("fa_r6_mode_pedido", "modo" to modo)
        }
        // R15/D2 — janela em AMOSTRAS para derivar o audio_ctx (0 = nao usar).
        intent?.getLongExtra("audioCtxWindow", 0L)?.takeIf { it > 0L }?.let { amostras ->
            val ctx = WhisperNative.setAudioCtxWindow(amostras)
            // R25/REQ — a fotografia leva a MESMA janela (leitura única no seam).
            WhisperLabDrive.audioCtxWindowToSet = amostras
            labLog("audio_ctx_janela_pedido", "amostras" to amostras, "retorno" to ctx)
        }
        labDestroyAfterMs = intent?.getLongExtra("destroyAfterMs", -1L) ?: -1L
        // R16/C1 — captura do committer (lab). Caminho absoluto no device.
        intent?.getStringExtra("capturePath")?.takeIf { it.isNotBlank() }?.let { p ->
            val ok = CommitCapture.ligar(p)
            labLog("capture_ligada", "path" to p, "ok" to ok)
        }
        labLeaseMs = intent?.getLongExtra("labLeaseMs", 0L) ?: 0L
        labLeaseIgnoreProgress =
            intent?.getBooleanExtra("labLeaseIgnoreProgress", false) ?: false
        modelOverride = intent?.getStringExtra("model")
        model2Override = intent?.getStringExtra("model2")
        killMs = intent?.getIntExtra("killMs", 0) ?: 0
        thrower = intent?.getBooleanExtra("thrower", false) ?: false

        labLog(
            "activity_create",
            "phase" to phase, "mode" to mode, "backend" to backend,
            "pid" to android.os.Process.myPid(), "destroyed" to isDestroyed
        )
        mainHandler.postDelayed(hbRunnable, HB_MS)
        worker.execute { runScenario(scenario) }
    }

    override fun onDestroy() {
        super.onDestroy()
        labLog("activity_destroy", "gen" to lastGen)
        if (lastGen != 0L) {
            ctrl.releaseAsync(lastGen)
            labLog("destroy_release_async", "gen" to lastGen)
        }
        labLog("hb_final", "max_ms" to hbMax, "ticks" to hbTicks)
        mainHandler.removeCallbacks(hbRunnable)
        // R56/F01 (nota5 §2) — lifetime em ORDEM: primeiro impedir novas tarefas
        // e ESPERAR os produtores em voo (que ainda precisam publicar terminal/
        // ACK), só então sinalizar o writer e drenar. Antes o writer era
        // sinalizado primeiro e podia sair antes do evento tardio. `shutdown()`
        // é gracioso: NÃO interrompe inferência em voo.
        runCatching { worker.shutdown() }
        runCatching { longOps.shutdown() }
        runCatching { inference.shutdown() }
        val w = writerThread
        writerThread = null
        val produtores: List<Pair<String, () -> Boolean>> = listOf(
            "worker" to { worker.isTerminated },
            "longOps" to { longOps.isTerminated },
            "inference" to { inference.isTerminated },
        )
        Thread({
            // R56/F01-F02 (nota5 §2, adendo13) — a REGRA vive no seam
            // LoggerTeardownCoordinator: writer só fecha depois do dreno (ou da
            // perda DECLARADA), pendência sempre observável.
            val r = LoggerTeardownCoordinator(
                produtores = produtores,
                publicar = { texto ->
                    val nome = texto.substringBefore(':')
                    val why = texto.substringAfter(':', "")
                    runCatching {
                        logQueue.offer(
                            org.json.JSONObject()
                                .put("ts", android.os.SystemClock.elapsedRealtime())
                                .put("event", nome)
                                .put("why", why)
                                .toString()
                        )
                    }
                },
                fecharWriter = { writerAtivo = false },
                writerVivo = { w?.isAlive == true },
            ).executar()

            // `join` devolve Unit: quem prova término é `isAlive`.
            if (w != null) {
                w.join(2500)
                if (w.isAlive) {
                    Log.w(TAG, "teardown: writer AINDA VIVO após join — pendência")
                } else {
                    Log.i(TAG, "teardown: writer encerrado (drenado=${r.drenado}, " +
                        "marco=${r.marcoDiagnostico}, espera=${r.esperaMs}ms)")
                }
            } else {
                Log.i(TAG, "teardown: sem writer para fechar")
            }
        }, "lab-writer-close").start()
    }

    private fun startWriter() {
        if (writerThread?.isAlive == true) return
        writerAtivo = true
        writerThread = Thread({
            while (writerAtivo) {
                val line = try {
                    logQueue.poll(1, TimeUnit.SECONDS)
                } catch (t: InterruptedException) {
                    break
                }
                if (line != null) {
                    try {
                        jsonlFile.appendText(line + "\n", Charsets.UTF_8)
                    } catch (t: Throwable) {
                        Log.e(TAG, "writer falhou: $t")
                    }
                }
            }
            // dreno final: nada do que já foi logado pode ser perdido
            while (true) {
                val resto = logQueue.poll() ?: break
                runCatching { jsonlFile.appendText(resto + "\n", Charsets.UTF_8) }
            }
        }, "lab-log-writer").apply { isDaemon = true; start() }
    }

    // --------------------------------------------------------------- utilidades

    private fun labLog(event: String, vararg kv: Pair<String, Any?>) {
        val obj = JSONObject()
        obj.put("ts", SystemClock.elapsedRealtime())
        obj.put("run", runId)
        obj.put("scenario", scenario)
        obj.put("event", event)
        for ((k, v) in kv) obj.put(k, v ?: JSONObject.NULL)
        val line = obj.toString()
        Log.i(TAG, line.take(3500))
        logQueue.offer(line)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun hex(bytes: ByteArray, limit: Int = 96): String =
        bytes.take(limit).joinToString(" ") { "%02x".format(it) }

    private fun stateSet(key: String, value: String) {
        val props = Properties()
        if (stateFile.isFile) {
            try {
                stateFile.inputStream().use { props.load(it) }
            } catch (_: Throwable) {
            }
        }
        props.setProperty(key, value)
        try {
            stateFile.outputStream().use { props.store(it, "whisper-lab state") }
        } catch (t: Throwable) {
            labLog("state_write_error", "error" to t.toString())
        }
    }

    private fun stateGet(key: String): String? {
        if (!stateFile.isFile) return null
        val props = Properties()
        return try {
            stateFile.inputStream().use { props.load(it) }
            props.getProperty(key)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * R15/A2 — o modelo pedido pelo ensaio.
     *
     * A R14 mediu o `audio_ctx` com o **BASE**: `modelPath()` ignorava o modelo
     * pedido e apontava SEMPRE para `ggml-base.bin` (e o `c3EnsureModel` copiava
     * só o BASE). Agora o extra `modelFile`/`faModel` define o arquivo, com o
     * BASE continuando disponível como controle.
     */
    private fun modelPath(): String {
        modelOverride?.let { return it }
        val pedido = intent?.getStringExtra("modelFile")
            ?: intent?.getStringExtra("faModel")?.let { rotulo ->
                when (rotulo) {
                    "v3Turbo", "turbo" -> "ggml-large-v3-turbo.bin"
                    "q5Turbo", "turbo_q5" -> "ggml-large-v3-turbo-q5_0.bin"
                    "base" -> "ggml-base.bin"
                    else -> null
                }
            }
            ?: "ggml-base.bin"
        val emModelDir = File(modelDir, pedido)
        if (emModelDir.isFile) return emModelDir.absolutePath
        // o modelo pode estar só no diretório externo (copiado pelo c3EnsureModel)
        val externo = getExternalFilesDir("whisper_models")?.let { File(it, pedido) }
        if (externo != null && externo.isFile) return externo.absolutePath
        return emModelDir.absolutePath
    }
    private fun model2Path(): String = model2Override ?: modelPath()
    private fun wav(name: String): String = File(corpusDir, name).absolutePath
    private fun lang(): String = intent?.getStringExtra("lang") ?: "pt"
    private fun beam(): Int = intent?.getIntExtra("beam", 1) ?: 1

    private fun kindOf(name: String): Int = when (name.lowercase()) {
        "vulkan", "gpu" -> 1
        "opencl" -> 2
        else -> 0
    }

    private fun mustFile(path: String, label: String): Boolean {
        val ok = File(path).isFile
        if (!ok) labLog("missing_file", "label" to label, "path" to path)
        return ok
    }

    private fun finishSoon(ms: Long = 1200L) {
        mainHandler.postDelayed({
            labLog("finish_now")
            try {
                finish()
            } catch (t: Throwable) {
                labLog("finish_error", "error" to t.toString())
            }
        }, ms)
    }

    private fun done(verdict: String, vararg kv: Pair<String, Any?>) {
        labLog("scenario_done", "verdict" to verdict, *kv)
        finishSoon()
    }

    // ---------------------------------------------------------------- s13 (R4/C3)
    // Ponte SÓ no lab: abre a WhisperActivity REAL por Intent explícito do
    // MESMO pacote (exported=false continua no manifest de produção) e a
    // dirige pelos widgets reais. Config/injeções via WhisperLabDrive; eventos
    // em filesDir/art-lab/c3-<run>.jsonl (publicados pela Activity real).

    private fun c3LogFile(): File = File(rootDir, "c3-$runId.jsonl")

    private fun c3LaunchReal(): Boolean {
        return try {
            val intent = Intent().setClassName(this, "br.gov.sp.pcsp.launcher.WhisperActivity")
            // R15/A2 — o modelo pedido viaja NO INTENT: o produto escolhe o
            // modelo no onCreate, antes de qualquer portão do lab. Sem isto, a
            // célula mede o BASE (o que aconteceu na R14).
            // R15/A2 — extra DISTINTO ("labModelLabel"): o extra "model" já é
            // usado pelo lab com outro sentido (caminho do modelo local) e não
            // pode ser reaproveitado.
            intent.putExtra("labModelLabel", requestedModelLabel())
            intent.putExtra("labModelFile", modelFileName())
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            labLog("c3_launched_real", "class" to "br.gov.sp.pcsp.launcher.WhisperActivity")
            true
        } catch (t: Throwable) {
            labLog("c3_launch_failed", "error" to t.toString())
            false
        }
    }

    private fun c3WaitFinal(expectedInst: Int, timeoutMs: Long, label: String): Boolean {
        val file = c3LogFile()
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (file.isFile) {
                val text = runCatching { file.readText(Charsets.UTF_8) }.getOrDefault("")
                // Pareamento POR LINHA: o ui_final precisa ser DA instância
                // esperada (dois contains soltos davam falso-positivo: ui_final
                // de inst1 + ui_periodic de inst2).
                val seen = text.lineSequence().any { line ->
                    line.contains("\"event\":\"ui_final\"") && line.contains("\"inst\":$expectedInst,")
                }
                if (seen) {
                    labLog("c3_final_seen", "inst" to expectedInst, "label" to label)
                    return true
                }
            }
            Thread.sleep(500)
        }
        labLog("c3_final_timeout", "inst" to expectedInst, "label" to label, "timeout_ms" to timeoutMs)
        return false
    }

    /**
     * R15/A2 — SHA-256 do modelo, por STREAM e com cache.
     *
     * `readBytes()` de 1,6 GiB estoura a memória e a main thread; o hash tem de
     * ser feito em background, por blocos, e ser cacheado por
     * (arquivo, bytes, mtime) para não reaquecer o aparelho a cada célula.
     */
    private fun modelSha256Cached(f: File): String {
        val key = "${f.absolutePath}:${f.length()}:${f.lastModified()}"
        modelShaCache[key]?.let { return it }
        val h = java.security.MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                h.update(buf, 0, n)
            }
        }
        val hex = h.digest().joinToString("") { "%02x".format(it) }
        modelShaCache[key] = hex
        return hex
    }

    /** R15/A2 — rótulo do modelo pedido (o que o menu do produto mostra). */
    private fun requestedModelLabel(): String? = when (modelFileName()) {
        "ggml-large-v3-turbo.bin" -> "v3Turbo"
        "ggml-large-v3-turbo-q5_0.bin" -> "q5Turbo"
        "ggml-base.bin" -> null      // BASE é o default do produto
        else -> null
    }

    /** R15/A2 — nome do arquivo de modelo pedido pelo ensaio. */
    private fun modelFileName(): String =
        intent?.getStringExtra("modelFile")
            ?: intent?.getStringExtra("faModel")?.let { rotulo ->
                when (rotulo) {
                    "v3Turbo", "turbo" -> "ggml-large-v3-turbo.bin"
                    "q5Turbo", "turbo_q5" -> "ggml-large-v3-turbo-q5_0.bin"
                    else -> null
                }
            }
            ?: "ggml-base.bin"

    /**
     * R15/A2 — espera o botão de modelo mostrar o rótulo pedido, clicando no
     * MENU REAL quando necessário (o MESMO clique do roteiro r8fa).
     */
    private fun c3AwaitModelLabel(timeoutMs: Long): Boolean {
        val want = WhisperLabDrive.modelLabelToSet ?: return true
        val t0 = SystemClock.elapsedRealtime()
        var tried = false
        while (SystemClock.elapsedRealtime() - t0 < timeoutMs) {
            val cur = findViewById<TextView>(R.id.button_model)?.text?.toString() ?: ""
            if (cur == want) {
                labLog("model_gate_ok", "label" to want,
                       "elapsed_ms" to (SystemClock.elapsedRealtime() - t0))
                return true
            }
            if (!tried) {
                runCatching { WhisperLabDrive.clickDialogItemByText(want, "model_gate_item", bySuffix = true) }
                tried = true
            }
            Thread.sleep(500)
        }
        labLog("model_gate_timeout", "want" to want,
               "cur" to (findViewById<TextView>(R.id.button_model)?.text?.toString() ?: "?"))
        return false
    }

    /**
     * R15/A2 — IDENTIDADE DO MODELO antes da inferência.
     *
     * A R14 mediu o `audio_ctx` com o modelo **BASE** porque `c3EnsureModel` copiada
     * e usava SEMPRE `ggml-base.bin`, ignorando o modelo pedido. Aqui o modelo é
     * EXPLICITO (extra `modelFile` ou o rótulo `faModel`), e a identidade (nome,
     * bytes e SHA-256) é registrada ANTES da inferência — a célula não pode
     * aceitar "BASE como se fosse Turbo".
     */
    private fun c3EnsureModel(): Boolean {
        val pedido = intent?.getStringExtra("modelFile")
            ?: intent?.getStringExtra("faModel")?.let { rotulo ->
                // rótulos conhecidos -> arquivo correspondente
                when (rotulo) {
                    "v3Turbo", "turbo" -> "ggml-large-v3-turbo.bin"
                    "q5Turbo", "turbo_q5" -> "ggml-large-v3-turbo-q5_0.bin"
                    "base" -> "ggml-base.bin"
                    else -> null
                }
            }
            ?: "ggml-base.bin"
        // R15/E1 — o modelo pode estar no diretorio EXTERNO (import SAF/E1):
        // procurar em modelDir e, se nao houver, no externo, em vez de falhar
        // com "prep_missing" mesmo com o arquivo presente no aparelho.
        val src = File(modelDir, pedido).takeIf { it.isFile }
            ?: getExternalFilesDir("whisper_models")?.let { File(it, pedido) }?.takeIf { it.isFile }
            ?: run {
                labLog("c3_missing_model", "path" to File(modelDir, pedido).absolutePath,
                       "externo" to (getExternalFilesDir("whisper_models")?.absolutePath ?: "?"),
                       "pedido" to pedido)
                return false
            }
        // R15/A2 — identidade do modelo: bytes + SHA-256. O hash de 1,6 GiB
        // NÃO pode ser feito na main thread (congela a UI e o `readBytes()` ainda
        // carrega o arquivo inteiro em memória): é feito em background, por
        // STREAM, e o resultado é cacheado por (arquivo, bytes, mtime).
        val sha = modelSha256Cached(src)
        labLog(
            "model_identidade", "arquivo" to pedido, "bytes" to src.length(),
            "sha256" to sha, "path" to src.absolutePath
        )
        val targets = ArrayList<File>()
        getExternalFilesDir("whisper_models")?.let { targets.add(File(it, pedido)) }
        targets.add(File(filesDir, "whisper_models/$pedido"))
        for (dst in targets) {
            if (!dst.isFile || dst.length() != src.length()) {
                dst.parentFile?.mkdirs()
                runCatching { src.copyTo(dst, overwrite = true) }
                    .onFailure { labLog("c3_model_copy_fail", "dst" to dst.absolutePath, "error" to it.toString()) }
                labLog("c3_model_copied", "dst" to dst.absolutePath, "bytes" to dst.length())
            }
        }
        val ok = targets.all { it.isFile }
        if (!ok) {
            labLog("c3_model_prep_fail", "pedido" to pedido)
            return false
        }
        // o produto tem de SELECIONAR este modelo (não o default)
        WhisperLabDrive.modelLabelToSet = when (pedido) {
            "ggml-large-v3-turbo.bin" -> "v3Turbo"
            "ggml-large-v3-turbo-q5_0.bin" -> "q5Turbo"
            else -> "base"
        }
        return true
    }

    private fun c3EnsureVad(): Boolean {
        val src = File(modelDir, "silero/ggml-silero-v6.2.0.bin")
        if (!src.isFile) {
            labLog("c3_missing_vad", "path" to src.absolutePath)
            return false
        }
        val targets = ArrayList<File>()
        runCatching { targets.add(NativeDependencyManager.sileroModelFile(this)) }
        getExternalFilesDir("whisper_models")?.let { targets.add(File(File(it, "silero"), "ggml-silero-v6.2.0.bin")) }
        targets.add(File(filesDir, "whisper_models/silero/ggml-silero-v6.2.0.bin"))
        for (dst in targets) {
            if (!dst.isFile || dst.length() != src.length()) {
                dst.parentFile?.mkdirs()
                runCatching { src.copyTo(dst, overwrite = true) }
            }
        }
        labLog("c3_vad_ready", "bytes" to src.length(), "targets" to targets.joinToString(",") { it.absolutePath })
        return true
    }

    private fun c3PrepPcm(name: String): File {
        val f = File(rootDir, "pcm/$name")
        labLog("c3_pcm", "name" to name, "path" to f.absolutePath, "exists" to f.isFile, "bytes" to (if (f.isFile) f.length() else -1))
        return f
    }

    private fun scenarioS13(modeArg: String) {
        if (!c3EnsureModel() || !c3EnsureVad()) {
            done("FAIL", "reason" to "c3_prep_missing")
            return
        }
        val startInst: () -> Int = { WhisperLabDrive.currentInstance() + 1 }
        var pass = true
        var detail = ""

        fun configBase(pcmName: String) {
            WhisperLabDrive.reset()
            WhisperLabDrive.active = true
            WhisperLabDrive.runId = runId
            WhisperLabDrive.logFile = c3LogFile()
            WhisperLabDrive.livePcmPath = c3PrepPcm(pcmName).absolutePath
            // R6/D2: estratégia de janela (extras; -1 = default do produto).
            WhisperLabDrive.liveHopMs = intent?.getLongExtra("hopMs", -1L) ?: -1L
            WhisperLabDrive.liveContextMs = intent?.getLongExtra("ctxMs", -1L) ?: -1L
            // R7/E3: estratégia do caminho ao vivo ("fixed"/"vad"; "" = default).
            WhisperLabDrive.liveStrategyKind = intent?.getStringExtra("strategy") ?: ""
            WhisperLabDrive.livePadMs = intent?.getLongExtra("padMs", -1L) ?: -1L
            WhisperLabDrive.liveMaxSegmentMs = intent?.getLongExtra("maxMs", -1L) ?: -1L
            WhisperLabDrive.liveSilenceCutMs = intent?.getLongExtra("silMs", -1L) ?: -1L
            // R8/D (fecho): espera máxima pelo publish do terminal antes do
            // export do fulltext (corrida export×terminal-publish).
            WhisperLabDrive.terminalFinalWaitMs = intent?.getLongExtra("termWaitMs", 15_000L) ?: 15_000L
            WhisperLabDrive.scenario = "s13-$modeArg"
        }

        when (modeArg) {
            "live-out" -> {
                configBase("c09_12frases.pcm")
                WhisperLabDrive.livePcmSpeed = 1.0
                WhisperLabDrive.beamToSet = 3; WhisperLabDrive.bestOfToSet = 3; WhisperLabDrive.vadToSet = true
                WhisperLabDrive.clickLiveStartMs = 1500
                WhisperLabDrive.stopAfterOuvindoMs = 34_000
                WhisperLabDrive.finishOnStatusPrefix = "Ao vivo "
                // R16/E1 — teto do ensaio parametrizavel: audio longo com F16
                // passava de 240 s e o lab CANCELAVA o job (terminal CANCELLED),
                // mascarando o resultado como "export vazio".
                WhisperLabDrive.hardFinishMs =
                    intent?.getLongExtra("hardFinishMs", 240_000L) ?: 240_000L
                WhisperLabDrive.dumpEveryMs = 2000
                val inst = startInst()
                if (c3LaunchReal()) pass = c3WaitFinal(inst, 240_000, "live-out")
                detail = "inst=$inst"
            }
            "live-burst" -> {
                configBase("c09_12frases.pcm")
                WhisperLabDrive.livePcmSpeed = 0.0
                WhisperLabDrive.beamToSet = 1; WhisperLabDrive.bestOfToSet = 1; WhisperLabDrive.vadToSet = true
                WhisperLabDrive.clickLiveStartMs = 1500
                WhisperLabDrive.stopAfterOuvindoMs = 2500
                WhisperLabDrive.finishOnStatusPrefix = "Ao vivo "
                WhisperLabDrive.hardFinishMs = 180_000
                WhisperLabDrive.dumpEveryMs = 2000
                val inst = startInst()
                if (c3LaunchReal()) pass = c3WaitFinal(inst, 180_000, "live-burst")
                detail = "inst=$inst"
            }
            "live-slow" -> {
                configBase("c09_12frases.pcm")
                WhisperLabDrive.livePcmSpeed = 0.0
                WhisperLabDrive.chunkDelayMs = 3000
                WhisperLabDrive.beamToSet = 1; WhisperLabDrive.bestOfToSet = 1; WhisperLabDrive.vadToSet = true
                WhisperLabDrive.clickLiveStartMs = 1500
                WhisperLabDrive.stopAfterOuvindoMs = 6_000
                WhisperLabDrive.finishOnStatusPrefix = "Ao vivo "
                WhisperLabDrive.hardFinishMs = 240_000
                WhisperLabDrive.dumpEveryMs = 2000
                val inst = startInst()
                if (c3LaunchReal()) pass = c3WaitFinal(inst, 240_000, "live-slow")
                detail = "inst=$inst"
            }
            "live-vad" -> {
                configBase("vad_mix.pcm")
                WhisperLabDrive.livePcmSpeed = 0.0
                WhisperLabDrive.beamToSet = 1; WhisperLabDrive.bestOfToSet = 1; WhisperLabDrive.vadToSet = true
                WhisperLabDrive.clickLiveStartMs = 1500
                WhisperLabDrive.stopAfterOuvindoMs = 3_000
                WhisperLabDrive.finishOnStatusPrefix = "Ao vivo "
                WhisperLabDrive.hardFinishMs = 240_000
                WhisperLabDrive.dumpEveryMs = 2000
                val inst = startInst()
                if (c3LaunchReal()) pass = c3WaitFinal(inst, 240_000, "live-vad")
                detail = "inst=$inst"
            }
            "record" -> {
                configBase("c09_12frases.pcm")
                WhisperLabDrive.livePcmPath = null // gravação usa a fonte de gravação
                WhisperLabDrive.recordPcmPath = c3PrepPcm("c01.pcm").absolutePath
                WhisperLabDrive.recordPcmSpeed = 1.0
                WhisperLabDrive.beamToSet = 1; WhisperLabDrive.bestOfToSet = 1
                WhisperLabDrive.vadToSet = true
                WhisperLabDrive.clickRecordStartMs = 1500
                WhisperLabDrive.clickRecordStopMs = 1500 + 16_000
                WhisperLabDrive.finishOnStatusContains = "concluída"
                WhisperLabDrive.hardFinishMs = 300_000
                WhisperLabDrive.dumpEveryMs = 3000
                val inst = startInst()
                if (c3LaunchReal()) pass = c3WaitFinal(inst, 300_000, "record")
                detail = "inst=$inst"
            }
            "live-d2" -> {
                // R6/D2: job ao vivo com RITMO REAL (speed=1.0) ou acelerado
                // (extra speed=2.0/8.0), PCM/stop/janela por extras.
                val pcmName = intent?.getStringExtra("pcm") ?: "c09_12frases.pcm"
                configBase(pcmName)
                WhisperLabDrive.livePcmSpeed = intent?.getDoubleExtra("speed", 1.0) ?: 1.0
                // R8/B: injeção de TEXTO de um chunk específico (lab) — força uma
                // região retida p/ exercitar a re-decodição RESTRITA no device.
                WhisperLabDrive.injectChunkIndex = intent?.getIntExtra("injectChunk", -1) ?: -1
                WhisperLabDrive.injectChunkText = intent?.getStringExtra("injectText") ?: ""
                WhisperLabDrive.injectChunkStatus = intent?.getStringExtra("injectStatus") ?: "ok"
                // R8/D: candidatas da 1ª parcial + pausa de scheduler injetada.
                WhisperLabDrive.liveFirstSegmentMs = intent?.getLongExtra("firstMs", -1L) ?: -1L
                WhisperLabDrive.liveFirstSilenceCutMs = intent?.getLongExtra("firstSilMs", -1L) ?: -1L
                WhisperLabDrive.liveIncrementalPartial = intent?.getBooleanExtra("incPartial", false) ?: false
                WhisperLabDrive.livePauseAtMs = intent?.getLongExtra("pauseAtMs", -1L) ?: -1L
                WhisperLabDrive.livePauseForMs = intent?.getLongExtra("pauseForMs", 0L) ?: 0L
                // R8/D: 2º job na MESMA Activity (frio→quente) — a latência warm
                // sai do 2º job com o modelo já carregado (cache do produto).
                WhisperLabDrive.secondJobStartAfterFirstVerdictMs = intent?.getLongExtra("secondJobMs", -1L) ?: -1L
                WhisperLabDrive.secondJobStopAfterStartMs = intent?.getLongExtra("secondStopMs", -1L) ?: -1L
                WhisperLabDrive.beamToSet = 1; WhisperLabDrive.bestOfToSet = 1; WhisperLabDrive.vadToSet = false
                WhisperLabDrive.clickLiveStartMs = 1500
                WhisperLabDrive.stopAfterOuvindoMs = intent?.getLongExtra("stopMs", 60_000L) ?: 60_000L
                WhisperLabDrive.finishOnStatusPrefix = "Ao vivo "
                // R7/E3: teto absoluto ajustável (prova sustentada de 30+ min).
                WhisperLabDrive.hardFinishMs = intent?.getLongExtra("hardMs", 600_000L) ?: 600_000L
                WhisperLabDrive.dumpEveryMs = 2000
                labLog(
                    "s13_live_d2", "pcm" to pcmName, "speed" to WhisperLabDrive.livePcmSpeed,
                    "stop_ms" to WhisperLabDrive.stopAfterOuvindoMs,
                    "hop_ms" to WhisperLabDrive.liveHopMs, "ctx_ms" to WhisperLabDrive.liveContextMs,
                    "strategy" to WhisperLabDrive.liveStrategyKind,
                    "pad_ms" to WhisperLabDrive.livePadMs, "max_ms" to WhisperLabDrive.liveMaxSegmentMs,
                    "sil_ms" to WhisperLabDrive.liveSilenceCutMs,
                    "first_ms" to WhisperLabDrive.liveFirstSegmentMs,
                    "first_sil_ms" to WhisperLabDrive.liveFirstSilenceCutMs,
                    "inc_partial" to WhisperLabDrive.liveIncrementalPartial,
                    "pause_at_ms" to WhisperLabDrive.livePauseAtMs,
                    "pause_for_ms" to WhisperLabDrive.livePauseForMs
                )
                val inst = startInst()
                // R7/E3: tempo de espera do FIM do job ajustável (prova de 30+ min).
                val waitMs = intent?.getLongExtra("waitMs", 660_000L) ?: 660_000L
                if (c3LaunchReal()) pass = c3WaitFinal(inst, waitMs, "live-d2")
                detail = "inst=$inst pcm=$pcmName"
            }
            "native-error" -> {
                configBase("c09_12frases.pcm")
                WhisperLabDrive.livePcmSpeed = 0.0
                WhisperLabDrive.injectChunkIndex = 1
                WhisperLabDrive.injectChunkStatus = "error"
                WhisperLabDrive.injectChunkText = "Erro: lab-injecao-nativa"
                WhisperLabDrive.beamToSet = 1; WhisperLabDrive.bestOfToSet = 1; WhisperLabDrive.vadToSet = true
                WhisperLabDrive.clickLiveStartMs = 1500
                WhisperLabDrive.stopAfterOuvindoMs = 2500
                WhisperLabDrive.finishOnStatusPrefix = "Ao vivo "
                WhisperLabDrive.hardFinishMs = 180_000
                WhisperLabDrive.dumpEveryMs = 2000
                val inst = startInst()
                if (c3LaunchReal()) pass = c3WaitFinal(inst, 180_000, "native-error")
                detail = "inst=$inst"
            }
            "sourcefail", "sourcefail-next" -> {
                configBase("c09_12frases.pcm")
                WhisperLabDrive.livePcmSpeed = 0.0
                WhisperLabDrive.livePcmFailAfterReads = 4   // cauda parcial de 6.400 amostras
                WhisperLabDrive.beamToSet = 1; WhisperLabDrive.bestOfToSet = 1; WhisperLabDrive.vadToSet = true
                WhisperLabDrive.clickLiveStartMs = 1500
                WhisperLabDrive.stopAfterOuvindoMs = 2500
                WhisperLabDrive.finishOnStatusPrefix = "Ao vivo "
                WhisperLabDrive.hardFinishMs = 180_000
                WhisperLabDrive.dumpEveryMs = 2000
                val instA = startInst()
                var okA = false
                if (c3LaunchReal()) okA = c3WaitFinal(instA, 180_000, "sourcefail-A")
                var okB = true
                var instB = -1
                if (modeArg == "sourcefail-next") {
                    // Segunda instância: job SEGUINTE saudável (mesmo modelo/chave).
                    WhisperLabDrive.reset()
                    WhisperLabDrive.active = true
                    WhisperLabDrive.runId = runId
                    WhisperLabDrive.logFile = c3LogFile()
                    WhisperLabDrive.scenario = "s13-$modeArg-B"
                    WhisperLabDrive.livePcmPath = c3PrepPcm("c01.pcm").absolutePath
                    WhisperLabDrive.livePcmSpeed = 0.0
                    WhisperLabDrive.beamToSet = 1; WhisperLabDrive.bestOfToSet = 1; WhisperLabDrive.vadToSet = true
                    WhisperLabDrive.clickLiveStartMs = 1500
                    WhisperLabDrive.stopAfterOuvindoMs = 2500
                    WhisperLabDrive.finishOnStatusPrefix = "Ao vivo "
                    WhisperLabDrive.hardFinishMs = 180_000
                    WhisperLabDrive.dumpEveryMs = 2000
                    instB = startInst()
                    if (c3LaunchReal()) okB = c3WaitFinal(instB, 180_000, "sourcefail-B")
                }
                pass = okA && okB
                detail = "instA=$instA instB=$instB"
            }
            "spool" -> {
                configBase("c09_12frases.pcm")
                WhisperLabDrive.livePcmSpeed = 0.0
                WhisperLabDrive.spoolLimitBytes = 64_000L
                WhisperLabDrive.beamToSet = 1; WhisperLabDrive.bestOfToSet = 1; WhisperLabDrive.vadToSet = true
                WhisperLabDrive.clickLiveStartMs = 1500
                WhisperLabDrive.stopAfterOuvindoMs = 2500
                WhisperLabDrive.finishOnStatusPrefix = "Ao vivo "
                WhisperLabDrive.hardFinishMs = 180_000
                WhisperLabDrive.dumpEveryMs = 2000
                val inst = startInst()
                if (c3LaunchReal()) pass = c3WaitFinal(inst, 180_000, "spool")
                detail = "inst=$inst"
            }
            "listener" -> {
                configBase("c09_12frases.pcm")
                WhisperLabDrive.livePcmSpeed = 0.0
                WhisperLabDrive.listenerFaultChunk = 1
                WhisperLabDrive.beamToSet = 1; WhisperLabDrive.bestOfToSet = 1; WhisperLabDrive.vadToSet = true
                WhisperLabDrive.clickLiveStartMs = 1500
                WhisperLabDrive.stopAfterOuvindoMs = 2500
                WhisperLabDrive.finishOnStatusPrefix = "Ao vivo "
                WhisperLabDrive.hardFinishMs = 180_000
                WhisperLabDrive.dumpEveryMs = 2000
                val inst = startInst()
                if (c3LaunchReal()) pass = c3WaitFinal(inst, 180_000, "listener")
                detail = "inst=$inst"
            }
            "reuse" -> {
                configBase("c01.pcm")
                WhisperLabDrive.livePcmSpeed = 0.0
                WhisperLabDrive.beamToSet = 1; WhisperLabDrive.bestOfToSet = 1; WhisperLabDrive.vadToSet = true
                WhisperLabDrive.clickLiveStartMs = 1500
                WhisperLabDrive.stopAfterOuvindoMs = 2500
                WhisperLabDrive.secondJobStartAfterFirstVerdictMs = 2000
                WhisperLabDrive.finishOnStatusPrefix = "Ao vivo "
                WhisperLabDrive.hardFinishMs = 300_000
                WhisperLabDrive.dumpEveryMs = 2000
                val inst = startInst()
                if (c3LaunchReal()) pass = c3WaitFinal(inst, 300_000, "reuse")
                detail = "inst=$inst"
            }
            "cancel-open" -> {
                // A) sai DURANTE o load; B) reabre e transcreve (job seguinte).
                configBase("c09_12frases.pcm")
                WhisperLabDrive.livePcmSpeed = 0.0
                WhisperLabDrive.modelLoadDelayMs = 9000
                WhisperLabDrive.beamToSet = 1; WhisperLabDrive.bestOfToSet = 1; WhisperLabDrive.vadToSet = true
                WhisperLabDrive.clickLiveStartMs = 800
                WhisperLabDrive.forceFinishMs = 2500
                WhisperLabDrive.hardFinishMs = 60_000
                WhisperLabDrive.dumpEveryMs = 1000
                val instA = startInst()
                var okA = false
                if (c3LaunchReal()) okA = c3WaitFinal(instA, 60_000, "cancel-open-A")
                // B) normal
                WhisperLabDrive.reset()
                WhisperLabDrive.active = true
                WhisperLabDrive.runId = runId
                WhisperLabDrive.logFile = c3LogFile()
                WhisperLabDrive.scenario = "s13-cancel-open-B"
                WhisperLabDrive.livePcmPath = c3PrepPcm("c09_12frases.pcm").absolutePath
                WhisperLabDrive.livePcmSpeed = 0.0
                WhisperLabDrive.beamToSet = 1; WhisperLabDrive.bestOfToSet = 1; WhisperLabDrive.vadToSet = true
                WhisperLabDrive.clickLiveStartMs = 1500
                WhisperLabDrive.stopAfterOuvindoMs = 2500
                WhisperLabDrive.finishOnStatusPrefix = "Ao vivo "
                WhisperLabDrive.hardFinishMs = 180_000
                WhisperLabDrive.dumpEveryMs = 2000
                val instB = startInst()
                val okB = if (c3LaunchReal()) c3WaitFinal(instB, 180_000, "cancel-open-B") else false
                pass = okA && okB
                detail = "instA=$instA instB=$instB"
            }
            "destroy-infer" -> {
                // R5/A3: A) sai DURANTE a inferência de um chunk (worker lento);
                // B) reabre e transcreve. Critérios no JSONL: cancel chega a A
                // (live_terminal de A cancelled/term.), B intacto, sem post de A.
                configBase("c09_12frases.pcm")
                WhisperLabDrive.livePcmSpeed = 0.0
                WhisperLabDrive.chunkDelayMs = 3000
                WhisperLabDrive.beamToSet = 1; WhisperLabDrive.bestOfToSet = 1; WhisperLabDrive.vadToSet = true
                WhisperLabDrive.clickLiveStartMs = 800
                WhisperLabDrive.forceFinishMs = 10_500
                WhisperLabDrive.hardFinishMs = 60_000
                WhisperLabDrive.dumpEveryMs = 1000
                val instA = startInst()
                var okA = false
                if (c3LaunchReal()) okA = c3WaitFinal(instA, 60_000, "destroy-infer-A")
                // B) normal
                WhisperLabDrive.reset()
                WhisperLabDrive.active = true
                WhisperLabDrive.runId = runId
                WhisperLabDrive.logFile = c3LogFile()
                WhisperLabDrive.scenario = "s13-destroy-infer-B"
                WhisperLabDrive.livePcmPath = c3PrepPcm("c09_12frases.pcm").absolutePath
                WhisperLabDrive.livePcmSpeed = 0.0
                WhisperLabDrive.beamToSet = 1; WhisperLabDrive.bestOfToSet = 1; WhisperLabDrive.vadToSet = true
                WhisperLabDrive.clickLiveStartMs = 1500
                WhisperLabDrive.stopAfterOuvindoMs = 2500
                WhisperLabDrive.finishOnStatusPrefix = "Ao vivo "
                WhisperLabDrive.hardFinishMs = 180_000
                WhisperLabDrive.dumpEveryMs = 2000
                val instB = startInst()
                val okB = if (c3LaunchReal()) c3WaitFinal(instB, 180_000, "destroy-infer-B") else false
                pass = okA && okB
                detail = "instA=$instA instB=$instB"
            }
            "cycles" -> {
                val n = intent?.getIntExtra("n", 5) ?: 5
                val seen = ArrayList<String>()
                for (i in 1..n) {
                    configBase("c01.pcm")
                    WhisperLabDrive.livePcmSpeed = 0.0
                    WhisperLabDrive.beamToSet = 1; WhisperLabDrive.bestOfToSet = 1; WhisperLabDrive.vadToSet = true
                    WhisperLabDrive.clickLiveStartMs = 1200
                    WhisperLabDrive.stopAfterOuvindoMs = 2500
                    WhisperLabDrive.finishOnStatusPrefix = "Ao vivo "
                    WhisperLabDrive.hardFinishMs = 180_000
                    WhisperLabDrive.dumpEveryMs = 1500
                    val inst = startInst()
                    val ok = if (c3LaunchReal()) c3WaitFinal(inst, 180_000, "cycles-$i") else false
                    labLog("c3_cycle", "iter" to i, "inst" to inst, "ok" to ok)
                    seen.add("$i:$inst:$ok")
                    if (!ok) { pass = false; break }
                    Thread.sleep(1500)
                }
                detail = "cycles=${seen.joinToString(" ")}"
            }
            else -> {
                labLog("c3_mode_unknown", "mode" to modeArg)
                pass = false
                detail = "mode=$modeArg"
            }
        }

        WhisperLabDrive.log("c3_scenario_done", "mode" to modeArg, "pass" to pass, "detail" to detail)
        done(if (pass) "PASS" else "FAIL", "mode" to modeArg, "detail" to detail)
    }

    // ---------------------------------------------------------------- s14 (R6/D3)
    // Ponte SÓ no lab: rota de ARQUIVO da WhisperActivity REAL — Uri do
    // provider de fixture (exported=false) entregue À MESMA callback do picker;
    // transcrever real; 2º job (cache); inválido; cancel; destroy/reabrir.
    // Eventos no MESMO c3-<run>.jsonl da Activity real.

    private fun c3FileHas(substr: String): Boolean {
        val f = c3LogFile()
        return f.isFile && runCatching { f.readText(Charsets.UTF_8) }.getOrDefault("").contains(substr)
    }

    /** R6/D3: copia a sessão de relatório (transcricoes.txt/html, log, terminal)
     *  do diretório externo do app para files/art-lab/out (puxável por run-as). */
    private fun copyLatestSession(label: String) {
        try {
            val ext = getExternalFilesDir(null) ?: return
            val root = File(ext, "Whisper")
            val newest = root.listFiles()?.filter { it.isDirectory }?.maxByOrNull { it.lastModified() } ?: return
            val dest = File(outDir, "session-$label-${runId}")
            dest.mkdirs()
            newest.listFiles()?.forEach { f ->
                if (f.isFile) runCatching { f.copyTo(File(dest, f.name), overwrite = true) }
            }
            labLog("s14_session_copy", "label" to label, "src" to newest.absolutePath, "dst" to dest.absolutePath,
                   "arquivos" to (dest.listFiles()?.joinToString(",") { it.name } ?: ""))
        } catch (t: Throwable) {
            labLog("s14_session_copy_fail", "label" to label, "error" to t.toString())
        }
    }

    /**
     * R14/D2-D3 — reaplica os extras de bancada DEPOIS de um `reset()` do Drive
     * (o reset apaga os one-shots). Chamado por todo cenário que reseta.
     */
    private fun labReapBenchExtras() {
        if (intent?.hasExtra("audioCtx") == true) {
            WhisperLabDrive.audioCtxToSet =
                intent.getIntExtra("audioCtx", 0).coerceIn(0, 1500)
            labLog("prep_audio_ctx", "value" to WhisperLabDrive.audioCtxToSet,
                   "scope" to "one-shot (proximo job)")
        }
        if (intent?.hasExtra("threads") == true) {
            WhisperLabDrive.threadsToSet =
                intent.getIntExtra("threads", 0).coerceIn(0, 8)
            labLog("prep_threads", "value" to WhisperLabDrive.threadsToSet,
                   "scope" to "one-shot (proximo job)")
        }
    }

    /**
     * R14/E1 — importação de MODELO pelo fluxo REAL do produto:
     * 1. a fixture de modelo é copiada para `fixtures/` (provider do lab);
     * 2. o lab entrega o Uri à MESMA callback de `REQUEST_PICK_MODEL`;
     * 3. o produto importa (hash + cópia atômica + sidecar de identidade);
     * 4. a transcrição roda com o modelo importado e o resultado sai do PAYLOAD.
     */
    private fun scenarioS15(modeArg: String) {
        if (!c3EnsureModel()) { done("FAIL", "reason" to "c3_prep_missing"); return }
        val modelFixture = intent?.getStringExtra("modelFixture") ?: "ggml-large-v3-turbo-q5_0.bin"
        val srcModel = File(rootDir, "fixtures/$modelFixture")
        if (!srcModel.isFile) {
            // o modelo pode estar em whisperlab-model/ (empurrado pelo host)
            val alt = File(modelDir, modelFixture)
            if (alt.isFile) srcModel.copyTo(File(rootDir, "fixtures/$modelFixture"), overwrite = true)
        }
        if (!File(rootDir, "fixtures/$modelFixture").isFile) {
            done("FAIL", "reason" to "model_fixture_missing", "fixture" to modelFixture)
            return
        }
        WhisperLabDrive.reset()
        WhisperLabDrive.active = true
        WhisperLabDrive.runId = runId
        WhisperLabDrive.logFile = c3LogFile()
        WhisperLabDrive.scenario = "s15-$modeArg"
        WhisperLabDrive.requirePayloadExport = true
        // R14/E1: a importação é o que este cenário exercita.
        WhisperLabDrive.modelImportFixtureName = modelFixture
        WhisperLabDrive.modelImportClickAfterMs = 1200L
        val audio = intent?.getStringExtra("fixture") ?: "d01_aprovado.wav"
        WhisperLabDrive.mediaFixtureName = audio
        WhisperLabDrive.fileTranscribeClickMs = 4000L
        WhisperLabDrive.finishOnStatusContains = "Transcrição concluída"
        WhisperLabDrive.hardFinishMs = 300_000
        labLog("s15_start", "model_fixture" to modelFixture, "audio" to audio)
        // R18/A2 — GATE DE IDENTIDADE: o cenário declara QUAL modelo espera e
        // a transcrição só começa depois de a seleção corrente ter esse SHA.
        val esperado = ModelIdentityGate.esperadoPorArquivo(modelFixture)
        WhisperLabDrive.waitForImportSha = esperado?.sha256
        WhisperLabDrive.importSelectBudgetMs =
            intent?.getLongExtra("importBudgetMs", 180_000L) ?: 180_000L
        val budget = SessionBudget.resolve(
            intent?.getLongExtra("hardFinishMs", -1L) ?: -1L,
            300_000L
        )
        WhisperLabDrive.hardFinishMs = budget.ms
        labLog("prep_budget", "cenario" to "s15", "ms" to budget.ms, "source" to budget.source)
        val inst = WhisperLabDrive.currentInstance() + 1
        c3LaunchReal()
        var waited = 0
        while (waited < budget.ms) {
            Thread.sleep(1000)
            waited += 1000
            if (c3FileHas("job_fgs_terminal") || c3FileHas("import_gate_timeout")) break
        }
        // R18/A2 — confronta a identidade do modelo que o JOB carregou com a
        // esperada. Divergência é FAIL, nunca fallback silencioso.
        val carregado = lerModelLoaded()
        val conf = ModelIdentityGate.confrontar(esperado, carregado)
        val selecionado = c3FileHas("model_import_selected")
        val concluiu = c3FileHas("Transcrição concluída") || c3FileHas("\"terminal\":\"SUCCESS\"")
        val ok = conf.veredito == ModelIdentityGate.Veredito.PASS && selecionado && concluiu
        val detail = "inst=$inst modelo=${carregado?.nome ?: "?"} bytes=${carregado?.bytes ?: -1} " +
            "ident=${conf.veredito}:${conf.motivo} selecionado=$selecionado concluiu=$concluiu"
        done(if (ok) "PASS" else "FAIL", "detail" to detail, "identidade" to conf.veredito.name)
    }

    /** R18/A2 — lê do próprio JSONL a chave do `model_loaded` do job. */
    private fun lerModelLoaded(): ModelIdentityGate.Carregado? {
        val f = c3LogFile()
        if (!f.isFile) return null
        return runCatching {
            f.useLines { seq ->
                val linha = seq.firstOrNull { it.contains("\"event\":\"model_loaded\"") }
                    ?: return@useLines null
                ModelIdentityGate.parseLoaded(org.json.JSONObject(linha).optString("key"))
            }
        }.getOrNull()
    }

    private fun scenarioS14(modeArg: String) {
        if (!c3EnsureModel() || !c3EnsureVad()) {
            done("FAIL", "reason" to "c3_prep_missing")
            return
        }
        val fixture = intent?.getStringExtra("fixture") ?: "d01_aprovado.wav"
        val src = File(rootDir, "fixtures/$fixture")
        labLog("s14_fixture", "name" to fixture, "exists" to src.isFile, "bytes" to (if (src.isFile) src.length() else -1))
        if (!src.isFile) {
            done("FAIL", "reason" to "fixture_missing", "fixture" to fixture)
            return
        }
        // nome servido = cópia controlada (provider valida e serve SÓ de fixtures/)
        val served = "fx-s14-$runId-${modeArg}.audio"
        val dst = File(rootDir, "fixtures/$served")
        if (!dst.isFile || dst.length() != src.length()) {
            runCatching { src.copyTo(dst, overwrite = true) }
        }
        if (!dst.isFile) {
            done("FAIL", "reason" to "fixture_copy_failed")
            return
        }

        val startInst: () -> Int = { WhisperLabDrive.currentInstance() + 1 }
        var pass = true
        var detail = ""

        fun config() {
            WhisperLabDrive.reset()
            // R19/A4 — backend/FA FORCADOS pela celula (sentinela Vulkan).
            // Sem isto o cenario caia no default CPU e media a fase errada.
            WhisperLabDrive.forcedBackendKind = intent?.let { i ->
                if (i.hasExtra("backend")) i.getIntExtra("backend", -1).takeIf { it in 0..2 } else null
            }
            WhisperLabDrive.forcedFlashAttention = intent?.let { i ->
                if (i.hasExtra("fa")) i.getBooleanExtra("fa", false) else null
            }
            // R21/Q1 — seleção por ARQUIVO (o Turbo Q5 não está no catálogo do
            // menu). Precisa estar DENTRO do config(), que é o caminho do s14.
            WhisperLabDrive.modelFileToSet = intent?.getStringExtra("modelFile")
            // R22/A1 — produtor/configuracao verificaveis da celula.
            // R22/B1 — POLITICA de submissao configurada no processo (o getenv
            // do adb shell nao alcanca um app ja iniciado pelo zygote).
            runCatching {
                if (intent?.hasExtra("vkK") == true) {
                    val kv = intent.getIntExtra("vkK", 0)
                    WhisperNative.setInflightK(kv)
                    WhisperLabDrive.kToSet = kv            // R25/REQ — mesma fonte
                }
                if (intent?.hasExtra("vkSerial") == true) {
                    val sv = intent.getIntExtra("vkSerial", 0)
                    WhisperNative.setSerialBatch(sv)
                    WhisperLabDrive.serialToSet = sv       // R25/REQ — mesma fonte
                }
            }
            // R22/B3 — DUMP configurado DENTRO do processo, antes de qualquer
            // chamada de captura (kind_enabled/ensure_dir sao cacheados).
            runCatching {
                val kinds = intent?.getStringExtra("dumpKinds")
                if (!kinds.isNullOrBlank()) {
                    val tag = intent?.getStringExtra("dumpTag") ?: "run"
                    val dir = java.io.File(filesDir, "art-lab/dump-$tag")
                    dir.mkdirs()
                    val steps = intent?.getIntExtra("dumpSteps", 1) ?: 1
                    val first = intent?.getIntExtra("dumpNodesFirst", 24) ?: 24
                    val step = intent?.getIntExtra("dumpNodesStep", 0) ?: 0
                    val maxNodes = intent?.getIntExtra("dumpNodesMax", 24) ?: 24
                    // R25/REQ — PROVA DISCRIMINANTE: `dumpRequestOnly=1` NÃO
                    // configura o dump por aqui; a captura só pode existir se o
                    // pedido da tentativa (na chamada) a tiver configurado.
                    val soPeloPedido = intent?.getBooleanExtra("dumpRequestOnly", false) ?: false
                    val applied = if (soPeloPedido) -1 else WhisperNative.sigDumpConfigure(
                        kinds, dir.absolutePath, tag, steps)
                    val nodes = if (soPeloPedido) -1 else WhisperNative.sigDumpConfigureNodes(first, step, maxNodes)
                    // R25/REQ — a fotografia leva a captura que o config aplicou.
                    WhisperLabDrive.dumpKindsToSet = kinds
                    WhisperLabDrive.dumpDirToSet = dir.absolutePath
                    WhisperLabDrive.dumpTagToSet = tag
                    WhisperLabDrive.dumpFirstToSet = first
                    WhisperLabDrive.dumpStepToSet = step
                    WhisperLabDrive.dumpMaxToSet = maxNodes
                    WhisperLabDrive.dumpStepsToSet = steps
                    WhisperLabDrive.log("dump_configure", "kinds" to kinds,
                        "dir" to dir.absolutePath, "tag" to tag,
                        "applied" to applied, "nodes_cfg" to nodes,
                        "via_request_only" to soPeloPedido)
                }
            }
            WhisperLabDrive.producerId = intent?.getStringExtra("producer") ?: ""
            WhisperLabDrive.configId = intent?.getStringExtra("config") ?: ""
            // R25/REQ — espelhos DEPOIS do reset(): o valor que a fotografia
            // leva é o mesmo do Intent, não um resíduo apagado por reset().
            // (Antes o submitMb era gravado no onCreate e o reset do config()
            // o zerava — a fotografia saía com submit=default:0.)
            if (intent?.hasExtra("submitMb") == true) {
                WhisperLabDrive.submitMbToSet = intent.getIntExtra("submitMb", 0)
            }
            if (intent?.hasExtra("audioCtxWindow") == true) {
                WhisperLabDrive.audioCtxWindowToSet = intent.getLongExtra("audioCtxWindow", 0L)
            }
            // R14/A2: ensaio de RESULTADO exige payload do job
            // não é aceito como resultado (falta de payload = erro diagnóstico).
            WhisperLabDrive.requirePayloadExport = true
            // R15/A2 — o s14 também passa pelo PORTÃO DE MODELO (menu real), como
            // o roteiro r8fa: sem isso o produto escolhe o primeiro modelo que
            // existe (o BASE) e a célula mediria o modelo errado.
            // R21/Q1 — quando a seleção é por ARQUIVO (seam), o gate de MENU não
            // roda: pedir "q5Turbo" num catálogo que não o tem fazia o menu cair
            // no BASE e desfazer a seleção correta.
            WhisperLabDrive.modelLabelToSet = if (WhisperLabDrive.modelFileToSet != null) {
                null
            } else when (modelFileName()) {
                "ggml-large-v3-turbo.bin" -> "v3Turbo"
                "ggml-large-v3-turbo-q5_0.bin" -> "q5Turbo"
                else -> "base"
            }
            // R16/A2 — o modelo PEDIDO entra no gate de identidade.
            // R21/Q1 — quando a seleção é por ARQUIVO, o esperado tem de ser o
            // ARQUIVO do extra: `modelPath()` lê a seleção CORRENTE, que no
            // config ainda é o default (BASE) — era essa a divergência.
            WhisperLabDrive.expectedModelPath = WhisperLabDrive.modelFileToSet
                ?.let { java.io.File(getExternalFilesDir(null), "whisper_models/$it").absolutePath }
                ?: modelPath()
            // R16/B4 — destroy programado (ms). -1 = nao destruir.
            WhisperLabDrive.destroyAfterMs = labDestroyAfterMs
            // R15/B1 — o prazo curto entra DEPOIS do reset (que o limpa).
            WhisperLabDrive.leaseTimeoutMs = labLeaseMs
            WhisperLabDrive.leaseIgnoreProgress = labLeaseIgnoreProgress
            WhisperLabDrive.active = true
            WhisperLabDrive.runId = runId
            WhisperLabDrive.logFile = c3LogFile()
            WhisperLabDrive.scenario = "s14-$modeArg"
            WhisperLabDrive.mediaFixtureName = served
            WhisperLabDrive.dumpEveryMs = 1000
            WhisperLabDrive.beamToSet = 1
            WhisperLabDrive.bestOfToSet = 1
            WhisperLabDrive.vadToSet = false
            // R14/D2-D3 — o reset() acima APAGA os extras de bancada; re-arma
            // DEPOIS dele (mesmo padrão de beam/bestOf/vad).
            labReapBenchExtras()
        }

        when (modeArg) {
            "ok" -> {
                config()
                WhisperLabDrive.fileTranscribeClickMs = 2500
                WhisperLabDrive.finishOnStatusContains = "Transcrição concluída"
                // R17/A2 — UM orçamento para os DOIS consumidores. Antes o
                // watcher do Drive ficava com 240 s fixos e matava o job longo
                // (drive_finish why=hard_cap em ~240,3 s) enquanto a espera
                // externa já aceitava o extra.
                val budget = SessionBudget.resolve(
                    intent?.getLongExtra("hardFinishMs", -1L) ?: -1L,
                    240_000L
                )
                WhisperLabDrive.hardFinishMs = budget.ms
                labLog(
                    "prep_budget",
                    "cenario" to "s14-ok",
                    "ms" to budget.ms,
                    "source" to budget.source
                )
                // R15/A2 — o portão de modelo roda na Activity REAL (o botão
                // `button_model` pertence ao produto) — ver
                // `WhisperLabDrive.applyModelToRealActivity`, ligado no install.
                val inst = startInst()
                if (c3LaunchReal()) pass = c3WaitFinal(
                    inst,
                    budget.ms,
                    "file-ok"
                )
                val uriOk = c3FileHas("injected_media_uri")
                val clicked = c3FileHas("click_transcribe")
                val done = c3FileHas("Transcrição concluída")
                copyLatestSession("ok")
                // R15/C1 — ESTADO ESTRUTURADO do FA/R6 ao fim da célula: é a
                // evidência de que o MODO pedido (auto/force/off) produziu a
                // rota esperada. Lido do nativo (sem parse de log).
                runCatching {
                    val st = WhisperNative.faRouteStatus()
                    labLog("fa_route_status_final", "status" to st)
                }.onFailure {
                    labLog("fa_route_status_falhou", "error" to it.toString())
                }
                detail = "inst=$inst uri=$uriOk click=$clicked concluiu=$done"
                if (!uriOk || !clicked || !done) pass = false
            }
            "second" -> {
                config()
                WhisperLabDrive.fileSecondJob = true
                WhisperLabDrive.fileTranscribeClickMs = 2500
                WhisperLabDrive.secondJobStartAfterFirstVerdictMs = 3000
                WhisperLabDrive.secondJobStopAfterStartMs = -1
                WhisperLabDrive.finishOnStatusContains = "Transcrição concluída"
                WhisperLabDrive.hardFinishMs = 300_000
                val inst = startInst()
                if (c3LaunchReal()) pass = c3WaitFinal(inst, 300_000, "file-second")
                copyLatestSession("second")
                detail = "inst=$inst"
            }
            "invalid" -> {
                config()
                WhisperLabDrive.fileTranscribeClickMs = 4000
                WhisperLabDrive.hardFinishMs = 60_000
                val inst = startInst()
                if (c3LaunchReal()) pass = c3WaitFinal(inst, 90_000, "file-invalid")
                detail = "inst=$inst tentouTranscrever=${c3FileHas("click_transcribe")}"
            }
            "cancel" -> {
                config()
                WhisperLabDrive.fileTranscribeClickMs = 2500
                // R19/B1 — atraso configuravel: o cancelamento tem de cair no
                // ENCODER, nao durante a carga (a R18 cancelou antes do
                // model_loaded em 18/20 celulas e mediu a fase errada).
                WhisperLabDrive.fileCancelAfterTranscribeMs =
                    intent?.getLongExtra("cancelAfterMs", 2500L) ?: 2500L
                WhisperLabDrive.finishOnStatusContains = "cancelada"
                WhisperLabDrive.hardFinishMs = 120_000
                val inst = startInst()
                if (c3LaunchReal()) pass = c3WaitFinal(inst, 150_000, "file-cancel")
                copyLatestSession("cancel")
                detail = "inst=$inst cancelou=${c3FileHas("cancelada")} toggle=${c3FileHas("click_transcribe_cancel")}"
                if (!c3FileHas("click_transcribe_cancel")) pass = false
            }
            "reopen" -> {
                // A) job de arquivo DESTRUÍDO no meio (forceFinish)
                config()
                WhisperLabDrive.fileTranscribeClickMs = 2500
                WhisperLabDrive.forceFinishMs = 7000
                WhisperLabDrive.hardFinishMs = 60_000
                val instA = startInst()
                var okA = false
                if (c3LaunchReal()) okA = c3WaitFinal(instA, 60_000, "file-reopen-A")
                // B) reabre e conclui o MESMO fixture (Uri reinjetada, novo job)
                config()
                WhisperLabDrive.fileTranscribeClickMs = 2500
                WhisperLabDrive.finishOnStatusContains = "Transcrição concluída"
                WhisperLabDrive.hardFinishMs = 240_000
                val instB = startInst()
                val okB = if (c3LaunchReal()) c3WaitFinal(instB, 240_000, "file-reopen-B") else false
                copyLatestSession("reopen")
                pass = okA && okB && c3FileHas("Transcrição concluída")
                detail = "instA=$instA instB=$instB"
            }
            else -> {
                labLog("s14_mode_unknown", "mode" to modeArg)
                pass = false
                detail = "mode=$modeArg"
            }
        }

        WhisperLabDrive.log("s14_scenario_done", "mode" to modeArg, "pass" to pass, "detail" to detail)
        done(if (pass) "PASS" else "FAIL", "mode" to modeArg, "detail" to detail)
    }

    /** Callback de lab com contadores e modos de falha injetáveis. */
    private inner class LabCallback(
        private val tag: String,
        private val throwOnEvent: Boolean = false,
        private val uiPostGen: Long = -1L,
    ) : WhisperNative.Callback {
        val segments = AtomicInteger(0)
        val logs = AtomicInteger(0)
        val throws = AtomicInteger(0)
        val uiDelivered = AtomicInteger(0)
        val uiSuppressed = AtomicInteger(0)

        override fun onSegment(text: String, startMs: Long, endMs: Long) {
            segments.incrementAndGet()
            labLog("cb_segment", "tag" to tag, "text" to text, "t0" to startMs, "t1" to endMs, "len" to text.length)
            if (uiPostGen > 0L) {
                mainHandler.post {
                    val ran = ctrl.runIfCurrent(uiPostGen) {
                        uiDelivered.incrementAndGet()
                        labLog("cb_ui_effect", "tag" to tag)
                    }
                    if (!ran) {
                        uiSuppressed.incrementAndGet()
                        labLog("cb_ui_suppressed", "tag" to tag, "gen" to uiPostGen, "destroyed" to isDestroyed)
                    }
                }
            }
            if (throwOnEvent) {
                throws.incrementAndGet()
                throw IllegalStateException("lab-callback-thrower($tag)")
            }
        }

        override fun onProgress(progress: Int) {
            if (progress % 20 == 0) labLog("cb_progress", "tag" to tag, "progress" to progress)
        }

        override fun onNativeLog(line: String) {
            logs.incrementAndGet()
            // R2/fase C: linhas de evidencia do fix MUTF-8 tambem sao
            // registradas (contagem de substituicoes U+FFFD do sanitize).
            if (line.contains("loading model from") || line.contains("native result") ||
                line.contains("whisper_full") || line.contains("model load") ||
                line.contains("MUTF-8 sanitize")
            ) {
                labLog("cb_native_log", "tag" to tag, "line" to line.take(240))
            }
            if (throwOnEvent) {
                throws.incrementAndGet()
                throw IllegalStateException("lab-callback-thrower-log($tag)")
            }
        }
    }

    private fun nativeLogExtract(): String {
        val log = runCatching { WhisperNative.lastLoadLog() }.getOrDefault("")
        return log.lineSequence().firstOrNull { it.contains("loading model from") }?.take(300)
            ?: "n/a"
    }

    // ---------------------------------------------------------------- cenários

    private fun runScenario(name: String) {
        // R11/D4 — braço de controle do MESMO binário: `--es jobFgs off` desliga
        // o foreground service do job (volta ao comportamento anterior, sem dono
        // de lifecycle). Default/ausente = ligado (comportamento de produção).
        if (intent?.hasExtra("jobFgs") == true) {
            WhisperLabDrive.jobFgsEnabled = intent.getStringExtra("jobFgs") != "off"
            labLog("prep_job_fgs", "enabled" to WhisperLabDrive.jobFgsEnabled)
        }
        try {
            when (name) {
                "s1" -> scenarioS1()
                "s2" -> scenarioS2(phase)
                "s3" -> scenarioS3(phase)
                "s4" -> scenarioS4(mode.ifEmpty { "beforeLoad" })
                "s5" -> scenarioS5(phase)
                "s6" -> scenarioS6(mode.ifEmpty { "thrower" })
                "s7" -> scenarioS7()
                "s8" -> scenarioS8()
                "s8b" -> scenarioS8b()
                "s9" -> scenarioS9()
                "s10" -> scenarioS10()
                "s11" -> scenarioS11(mode.ifEmpty { "realtime" })
                "s12" -> scenarioS12(mode.ifEmpty { "pending2" })
                "s13" -> scenarioS13(mode.ifEmpty { "live-out" })
                "s14" -> scenarioS14(mode.ifEmpty { "ok" })
                // R14/E1 — importação de MODELO pelo fluxo SAF real + transcrição.
                "s15" -> scenarioS15(mode.ifEmpty { "import" })
                "s15" -> scenarioS15()
                "r8fa" -> scenarioR8Fa()
                else -> labLog("scenario_unknown", "name" to name)
            }
        } catch (t: Throwable) {
            labLog("scenario_exception", "name" to name, "throwable" to t.toString())
            done("FAIL", "reason" to "exception", "throwable" to t.toString())
        }
    }

    /**
     * R8/A2+A3 — BATERIA de UI REAL no MESMO processo: jobs sequenciais pelos
     * CONTROLES REAIS (painel avançado: expandir/FA/backend; botão de iniciar).
     * Cada job registra `fa_job_state` (SIG_FA_STATUS/SIG_FA_EXEC do MESMO job)
     * + exporta o TEXTO COMPLETO do MESMO job (fulltext.jsonl). O roteiro não
     * usa sleep fixo para cancelar: cancela no ESTÁGIO OBSERVADO (progresso do
     * callback / calling_full) — passos default = 7 cenários do prompt A2.
     */
    private fun scenarioR8Fa() {
        // R16/B4 — destroy nao depende de config(): aplica aqui.
        WhisperLabDrive.destroyAfterMs = labDestroyAfterMs
        val plan = intent?.getStringExtra("faSeq")
            ?: "vulkan_on,silencio,cpu_on,vulkan_off,opencl_on,cancel_progress,recovery"
        val speechName = intent?.getStringExtra("faSpeech") ?: "c09_12frases.pcm"
        val speech = c3PrepPcm(speechName)
        if (!c3EnsureModel()) { done("FAIL", "reason" to "missing_model"); return }
        if (!mustFile(speech.absolutePath, "faSpeech")) { done("FAIL", "reason" to "missing_speech_pcm"); return }
        val fixture = intent?.getStringExtra("media") ?: "n07_novo.wav"
        WhisperLabDrive.reset()
        WhisperLabDrive.active = true
        WhisperLabDrive.runId = runId
        WhisperLabDrive.logFile = c3LogFile()
        WhisperLabDrive.scenario = "r8fa"
        WhisperLabDrive.mediaFixtureName = fixture
        // R14/D2-D3 — reaplica os one-shots depois do reset do Drive.
        labReapBenchExtras()
        // R9/G3 — seleção do MODELO pelo MENU REAL (ex.: "v3Turbo") antes do 1o passo.
        WhisperLabDrive.modelLabelToSet = intent?.getStringExtra("faModel")
        // R21/Q1 — seleção por arquivo (Turbo Q5 não está no catálogo do menu).
        WhisperLabDrive.modelFileToSet = intent?.getStringExtra("modelFile")
        // R19/A4 — celula pode FORCAR backend/FA (sentinela Vulkan). Sem isto o
        // cenario caia no default CPU e a coorte media a fase errada.
        WhisperLabDrive.forcedBackendKind = intent?.let { i ->
            if (i.hasExtra("backend")) i.getIntExtra("backend", -1).takeIf { it in 0..2 } else null
        }
        WhisperLabDrive.forcedFlashAttention = intent?.let { i ->
            if (i.hasExtra("fa")) i.getBooleanExtra("fa", false) else null
        }
        // R10/H2-APP (aceitação pela ROTA DO APP): controle OPCIONAL de
        // amostragem/VAD por execução, pelos MESMOS widgets que o produto lê
        // (input_beam_size / input_best_of / checkbox_vad). AUSENTE => nada muda
        // (defaults do produto: beam 5 / best_of 5 / VAD ligado). Presente =>
        // permite rodar a Activity com os MESMOS parâmetros da aceitação de núcleo
        // (beam 1 / best_of 1 / VAD off), para que app × core sejam comparáveis.
        if (intent?.hasExtra("beam") == true) {
            WhisperLabDrive.beamToSet = intent.getIntExtra("beam", 1).coerceIn(1, 16)
        }
        if (intent?.hasExtra("bestOf") == true) {
            WhisperLabDrive.bestOfToSet = intent.getIntExtra("bestOf", 1).coerceIn(1, 16)
        }
        if (intent?.hasExtra("vad") == true) {
            WhisperLabDrive.vadToSet = intent.getBooleanExtra("vad", false)
        }
        // R11/D2 — SEAM DE THREADS (bancada, one-shot): extra OPCIONAL `threads`
        // (1..8). AUSENTE => nada muda: a política do nativo decide (4→8 em CPU +
        // classe de arquitetura medida). Presente => o PRÓXIMO job roda com esse
        // número e o nativo rotula `lab_override`. É o que permite o par
        // controlado (t4 forçado × política) no MESMO caminho/lib/params.
        // R14/D2-D3 — contexto de encoder EXPERIMENTAL (bancada): 0 = default.
        if (intent?.hasExtra("audioCtx") == true) {
            val ac = intent.getIntExtra("audioCtx", 0).coerceIn(0, 1500)
            WhisperLabDrive.audioCtxToSet = ac
            labLog("prep_audio_ctx", "value" to ac, "scope" to "one-shot (proximo job)")
        }
        if (intent?.hasExtra("threads") == true) {
            val tv = intent.getIntExtra("threads", 0).coerceIn(1, 8)
            WhisperLabDrive.threadsToSet = tv
            labLog("prep_threads", "value" to tv, "scope" to "one-shot (proximo job)")
        }
        // R16/D3 — K0 focal: prefixo da gravação TF + observador de beam
        // (só tem efeito com a lib DIAG; no NORMAL é inerte).
        if (intent?.hasExtra("beamObs") == true) {
            val pref = intent.getStringExtra("beamObs") ?: ""
            if (pref.isNotBlank()) {
                runCatching { WhisperNative.setBeamObsEnv(pref) }
                labLog("prep_beamobs", "prefix" to pref)
            }
        }
        // R9/B1 (matriz focal): controle de instrumentação por execução.
        // dumpMs=0 → sem ui_periodic (o hb final continua em drive_finish);
        // stallLite=true → main_stall sem o thread-dump pesado.
        val dumpMs = intent?.getLongExtra("dumpMs", -1L) ?: -1L
        WhisperLabDrive.dumpEveryMs = if (dumpMs >= 0L) dumpMs else 1500L
        WhisperLabDrive.stallLite = intent?.getBooleanExtra("stallLite", false) ?: false
        WhisperLabDrive.faSeqPlan = plan
        WhisperLabDrive.faSeqSpeechPcm = speech.absolutePath
        WhisperLabDrive.faSeqZerosMs = intent?.getLongExtra("faZerosMs", 3000L) ?: 3000L
        WhisperLabDrive.faSeqStepCapMs = intent?.getLongExtra("faCapMs", 150_000L) ?: 150_000L
        WhisperLabDrive.livePcmSpeed = intent?.getDoubleExtra("speed", 30.0) ?: 30.0
        WhisperLabDrive.livePcmReadBytes = 3200
        WhisperLabDrive.hardFinishMs = -1L
        // R16/B4/C2 — por ULTIMO: nada acima pode resetar o Drive depois disto.
        WhisperLabDrive.destroyAfterMs = labDestroyAfterMs
        WhisperLabDrive.stopAfterOuvindoMs =
            intent?.getLongExtra("stopAfterOuvindoMs", -1L) ?: -1L
        val inst = WhisperLabDrive.currentInstance() + 1
        val okLaunch = c3LaunchReal()
        val okRun = if (okLaunch) c3WaitFinal(inst, 30L * 60_000L, "r8fa") else false
        // Contagem de evidência (a análise FINA fica no script da fase A).
        var jobs = 0
        var doneSeen = false
        val file = c3LogFile()
        if (file.isFile) {
            val text = runCatching { file.readText(Charsets.UTF_8) }.getOrDefault("")
            for (line in text.lineSequence()) {
                if (line.contains("\"event\":\"fa_job_state\"")) jobs++
                if (line.contains("\"event\":\"fa_seq_done\"")) doneSeen = true
            }
        }
        labLog("r8fa_result", "inst" to inst, "plan" to plan, "jobs" to jobs, "done" to doneSeen)
        done(if (okRun && doneSeen && jobs >= 5) "PASS" else "FAIL",
            "inst" to inst, "plan" to plan, "jobs" to jobs, "done" to doneSeen)
    }

    /** s1 — transcrição completa de arquivo curto C01. */
    private fun scenarioS1() {
        val model = modelPath()
        val wavPath = wav("c01_speech_short.wav")
        if (!mustFile(model, "model") || !mustFile(wavPath, "c01")) {
            done("FAIL", "reason" to "missing_files")
            return
        }
        val api = runCatching { WhisperNative.ownershipApiVersion() }.getOrDefault(-1)
        val gen = ctrl.beginSession()
        lastGen = gen
        labLog("s1_session", "gen" to gen, "ownership_api" to api)
        val ok = ctrl.loadModel(gen, model, kindOf(backend), false)
        labLog("s1_load", "ok" to ok, "backend" to backend, "load_log" to nativeLogExtract())
        if (!ok) {
            done("FAIL", "reason" to "load_failed", "lastError" to runCatching { WhisperNative.lastError() }.getOrDefault(""))
            return
        }
        val cb = LabCallback("s1")
        val t0 = SystemClock.elapsedRealtime()
        val (text, status) = ctrl.transcribeAndStatus(gen, wavPath, lang(), beam(), 1, false, false, "", cb)
        val dt = SystemClock.elapsedRealtime() - t0
        val bytes = text.toByteArray(Charsets.UTF_8)
        File(outDir, "s1-text.utf8").writeBytes(bytes)
        val parsed = WhisperResultParser.parse(text, status)
        labLog(
            "s1_transcribe", "status" to status, "ms" to dt, "len_utf16" to text.length,
            "bytes_utf8" to bytes.size, "sha256" to sha256(bytes), "parsed" to parsed.javaClass.simpleName,
            "segments" to cb.segments.get(), "text" to text.take(200)
        )
        labLog(
            "s1_utf8", "has_revisao" to text.contains("revisão"), "has_relatorios" to text.contains("relatórios"),
            "non_ascii_chars" to text.count { it.code > 127 }
        )
        val pass = parsed is WhisperTranscriptResult.Success && text.isNotBlank()
        done(if (pass) "PASS" else "FAIL", "status" to status, "ms" to dt, "len" to text.length)
    }

    /** s2 — fechar/reabrir a Activity durante o LOAD (fases 1 e 2). */
    private fun scenarioS2(phase: Int) {
        val model = modelPath()
        val wavPath = wav("c01_speech_short.wav")
        if (phase == 1) {
            val gen1 = ctrl.beginSession()
            lastGen = gen1
            stateSet("s2_gen1", gen1.toString())
            labLog("s2p1_session", "gen1" to gen1)
            longOps.execute {
                labLog("s2p1_load_start", "gen1" to gen1)
                val ok = ctrl.loadModel(gen1, model, 0, false)
                labLog("s2p1_load_done", "ok" to ok, "gen1" to gen1)
            }
            val kill = if (killMs > 0) killMs.toLong() else 700L
            mainHandler.postDelayed({
                labLog("s2p1_finish_now", "killMs" to kill)
                finish()
            }, kill)
        } else {
            val gen1 = stateGet("s2_gen1")?.toLongOrNull() ?: -1L
            val gen2 = ctrl.beginSession()
            lastGen = gen2
            labLog("s2p2_session", "gen1" to gen1, "gen2" to gen2)
            val ok = ctrl.loadModel(gen2, model, 0, false)
            labLog("s2p2_load", "ok" to ok)
            val staleRc = try {
                WhisperNative.releaseModelOwned(gen1)
            } catch (t: Throwable) {
                labLog("s2p2_stale_exception", "t" to t.toString())
                true
            }
            labLog("s2p2_stale_release_native", "gen1" to gen1, "rc_freed" to staleRc)
            ctrl.releaseAsync(gen1)
            Thread.sleep(400)
            val (text, status) = ctrl.transcribeAndStatus(gen2, wavPath, lang(), beam(), 1, false, false, "", LabCallback("s2p2"))
            labLog("s2p2_transcribe", "status" to status, "len" to text.length, "sha" to sha256(text.toByteArray(Charsets.UTF_8)))
            val pass = ok && !staleRc && status == "ok" && text.isNotBlank()
            done(if (pass) "PASS" else "FAIL", "stale_freed" to staleRc, "status" to status, "load" to ok)
        }
    }

    /** s3 — fechar/reabrir a Activity durante a INFERÊNCIA (fases 1 e 2). */
    private fun scenarioS3(phase: Int) {
        val model = modelPath()
        val wavPath = wav("c01_speech_short.wav")
        if (phase == 1) {
            val gen1 = ctrl.beginSession()
            lastGen = gen1
            stateSet("s3_gen1", gen1.toString())
            labLog("s3p1_session", "gen1" to gen1)
            longOps.execute {
                val ok = ctrl.loadModel(gen1, model, 0, false)
                labLog("s3p1_load_done", "ok" to ok)
                if (ok) {
                    inference.execute {
                        labLog("s3p1_transcribe_start", "gen1" to gen1)
                        val kill = if (killMs > 0) killMs.toLong() else 1200L
                        mainHandler.postDelayed({
                            labLog("s3p1_finish_now", "killMs" to kill)
                            finish()
                        }, kill)
                        val cb = LabCallback("s3p1", uiPostGen = gen1)
                        val (text, status) = ctrl.transcribeAndStatus(gen1, wavPath, lang(), beam(), 1, false, false, "", cb)
                        labLog(
                            "s3p1_transcribe_done", "status" to status, "len" to text.length,
                            "segments" to cb.segments.get(), "ui_delivered" to cb.uiDelivered.get(),
                            "ui_suppressed" to cb.uiSuppressed.get()
                        )
                    }
                }
            }
        } else {
            val gen1 = stateGet("s3_gen1")?.toLongOrNull() ?: -1L
            val gen2 = ctrl.beginSession()
            lastGen = gen2
            labLog("s3p2_session", "gen1" to gen1, "gen2" to gen2)
            val ok = ctrl.loadModel(gen2, model, 0, false)
            labLog("s3p2_load", "ok" to ok)
            val staleRc = try {
                WhisperNative.releaseModelOwned(gen1)
            } catch (t: Throwable) {
                true
            }
            labLog("s3p2_stale_release_native", "gen1" to gen1, "rc_freed" to staleRc)
            ctrl.releaseAsync(gen1)
            Thread.sleep(300)
            val (text, status) = ctrl.transcribeAndStatus(gen2, wavPath, lang(), beam(), 1, false, false, "", LabCallback("s3p2"))
            labLog("s3p2_transcribe", "status" to status, "len" to text.length)
            val pass = ok && !staleRc && status == "ok"
            done(if (pass) "PASS" else "FAIL", "stale_freed" to staleRc, "status" to status)
        }
    }

    /** s4 — cancelamento antes do load, durante o load (espera por lock) e durante a inferência. */
    private fun scenarioS4(modeArg: String) {
        val model = modelPath()
        val wavPath = wav("c01_speech_short.wav")
        when (modeArg) {
            "beforeLoad" -> {
                val gen = ctrl.beginSession()
                lastGen = gen
                ctrl.cancel(gen)
                labLog("s4_cancel_issued", "mode" to "beforeLoad", "gen" to gen)
                val t0 = SystemClock.elapsedRealtime()
                val (text, status) = ctrl.transcribeAndStatus(gen, wavPath, lang(), beam(), 1, false, false, "", LabCallback("s4bl"))
                val dt = SystemClock.elapsedRealtime() - t0
                labLog("s4_cancel_result", "mode" to "beforeLoad", "status" to status, "ms" to dt, "text" to text.take(140))
                val ok = ctrl.loadModel(gen, model, 0, false)
                val (text2, status2) = ctrl.transcribeAndStatus(gen, wavPath, lang(), beam(), 1, false, false, "", LabCallback("s4bl-rec"))
                labLog("s4_recovery", "load" to ok, "status" to status2, "len" to text2.length)
                val pass = status == "cancelled" && ok && status2 == "ok" && text2.isNotBlank()
                done(if (pass) "PASS" else "FAIL", "cancel_status" to status, "recovery_status" to status2, "ms" to dt)
            }
            "duringLoad" -> {
                val gen = ctrl.beginSession()
                lastGen = gen
                longOps.execute {
                    val ok = ctrl.loadModel(gen, model, 0, false)
                    labLog("s4dl_load_done", "ok" to ok)
                }
                Thread.sleep(250)
                val doneLatch = CountDownLatch(1)
                val statusRef = java.util.concurrent.atomic.AtomicReference("")
                val textRef = java.util.concurrent.atomic.AtomicReference("")
                inference.execute {
                    labLog("s4dl_transcribe_start", "gen" to gen)
                    val (text, status) = ctrl.transcribeAndStatus(gen, wavPath, lang(), beam(), 1, false, false, "", LabCallback("s4dl"))
                    textRef.set(text)
                    statusRef.set(status)
                    doneLatch.countDown()
                }
                Thread.sleep(300)
                val c0 = SystemClock.elapsedRealtime()
                ctrl.cancel(gen)
                val c1 = SystemClock.elapsedRealtime()
                labLog("s4dl_cancel_issued", "gen" to gen, "cancel_call_ms" to (c1 - c0))
                val returned = doneLatch.await(180, TimeUnit.SECONDS)
                val c2 = SystemClock.elapsedRealtime()
                labLog(
                    "s4_cancel_result", "mode" to "duringLoad", "returned" to returned,
                    "cancel_to_return_ms" to (c2 - c1), "status" to statusRef.get(), "text" to textRef.get().take(140)
                )
                val pass = returned && statusRef.get() == "cancelled"
                done(if (pass) "PASS" else "FAIL", "mode" to "duringLoad", "status" to statusRef.get(), "cancel_to_return_ms" to (c2 - c1))
            }
            "duringInference" -> {
                val gen = ctrl.beginSession()
                lastGen = gen
                val ok = ctrl.loadModel(gen, model, 0, false)
                labLog("s4di_load", "ok" to ok)
                val doneLatch = CountDownLatch(1)
                val statusRef = java.util.concurrent.atomic.AtomicReference("")
                val textRef = java.util.concurrent.atomic.AtomicReference("")
                inference.execute {
                    labLog("s4di_transcribe_start", "gen" to gen)
                    val (text, status) = ctrl.transcribeAndStatus(gen, wavPath, lang(), beam(), 1, false, false, "", LabCallback("s4di"))
                    textRef.set(text)
                    statusRef.set(status)
                    doneLatch.countDown()
                }
                Thread.sleep(1500)
                val c0 = SystemClock.elapsedRealtime()
                ctrl.cancel(gen)
                val c1 = SystemClock.elapsedRealtime()
                val returned = doneLatch.await(180, TimeUnit.SECONDS)
                val c2 = SystemClock.elapsedRealtime()
                labLog(
                    "s4_cancel_result", "mode" to "duringInference", "returned" to returned,
                    "cancel_call_ms" to (c1 - c0), "cancel_to_return_ms" to (c2 - c1),
                    "status" to statusRef.get(), "text" to textRef.get().take(140)
                )
                val pass = returned && statusRef.get() == "cancelled"
                done(if (pass) "PASS" else "FAIL", "mode" to "duringInference", "status" to statusRef.get(), "cancel_to_return_ms" to (c2 - c1))
            }
            else -> done("FAIL", "reason" to "modo_desconhecido", "mode" to modeArg)
        }
    }

    /** s5 — troca de modelo/backend no meio + recriação da Activity. */
    private fun scenarioS5(phase: Int) {
        if (phase == 1) {
            val gen1 = ctrl.beginSession()
            lastGen = gen1
            stateSet("s5_gen1", gen1.toString())
            stateSet("s5_model_a", modelPath())
            stateSet("s5_backend_a", backend)
            labLog("s5p1_session", "gen1" to gen1, "model_a" to modelPath(), "backend_a" to backend)
            val ok = ctrl.loadModel(gen1, modelPath(), kindOf(backend), false)
            labLog("s5p1_load", "ok" to ok, "load_log" to nativeLogExtract())
            done(if (ok) "PASS" else "FAIL", "load_a" to ok)
        } else {
            val gen1 = stateGet("s5_gen1")?.toLongOrNull() ?: -1L
            val modelA = stateGet("s5_model_a") ?: "?"
            val backendB = backend2.ifEmpty { backend }
            val modelB = model2Path()
            val gen2 = ctrl.beginSession()
            lastGen = gen2
            labLog("s5p2_session", "gen1" to gen1, "gen2" to gen2, "model_a" to modelA, "model_b" to modelB, "backend_b" to backendB)
            val ok = ctrl.loadModel(gen2, modelB, kindOf(backendB), false)
            labLog("s5p2_load_b", "ok" to ok, "load_log" to nativeLogExtract())
            val (staleText, staleStatus) = ctrl.transcribeAndStatus(gen1, wav("c01_speech_short.wav"), lang(), beam(), 1, false, false, "", LabCallback("s5stale"))
            val parsedStale = WhisperResultParser.parse(staleText, staleStatus)
            labLog(
                "s5_stale_transcribe", "text" to staleText, "status" to staleStatus,
                "parsed" to parsedStale.javaClass.simpleName
            )
            val staleRc = try {
                WhisperNative.releaseModelOwned(gen1)
            } catch (t: Throwable) {
                true
            }
            labLog("s5_stale_release_native", "gen1" to gen1, "rc_freed" to staleRc)
            ctrl.releaseAsync(gen1)
            Thread.sleep(300)
            val (text, status) = ctrl.transcribeAndStatus(gen2, wav("c01_speech_short.wav"), lang(), beam(), 1, false, false, "", LabCallback("s5p2"))
            val loadLogB = nativeLogExtract()
            labLog("s5p2_transcribe", "status" to status, "len" to text.length, "sha" to sha256(text.toByteArray(Charsets.UTF_8)), "load_log_b" to loadLogB)
            val modelsDiffer = modelA != modelB || backend2.isNotEmpty() && backend2 != backend
            val pass = ok && !staleRc && staleStatus == WhisperSessionController.STALE_STATUS &&
                status == "ok" && parsedStale is WhisperTranscriptResult.Cancelled
            done(
                if (pass) "PASS" else "FAIL",
                "load_b" to ok, "stale_freed" to staleRc, "stale_status" to staleStatus,
                "status_b" to status, "models_differ" to modelsDiffer, "backend_b" to backendB
            )
        }
    }

    /** s6 — callback que lança exceção + callbacks enfileirados com Activity destruída. */
    private fun scenarioS6(modeArg: String) {
        val model = modelPath()
        val wavPath = wav("c01_speech_short.wav")
        when (modeArg) {
            "thrower" -> {
                val gen = ctrl.beginSession()
                lastGen = gen
                val ok = ctrl.loadModel(gen, model, 0, false)
                labLog("s6_load", "ok" to ok)
                val cb = LabCallback("s6throw", throwOnEvent = true)
                val (text, status) = ctrl.transcribeAndStatus(gen, wavPath, lang(), beam(), 1, false, false, "", cb)
                labLog(
                    "s6_thrower_result", "status" to status, "len" to text.length,
                    "segments" to cb.segments.get(), "logs" to cb.logs.get(), "throws" to cb.throws.get()
                )
                val pass = status == "ok" && text.isNotBlank() && cb.throws.get() > 0
                done(if (pass) "PASS" else "FAIL", "throws" to cb.throws.get(), "segments" to cb.segments.get(), "logs" to cb.logs.get())
            }
            "queue" -> {
                val gen = ctrl.beginSession()
                lastGen = gen
                labLog("s6q_session", "gen" to gen)
                val probe = Object()
                mainHandler.postDelayed({
                    val ran = ctrl.runIfCurrent(gen) {
                        labLog("s6q_ui_effect_delivered", "gen" to gen, "destroyed" to isDestroyed, "probe" to probe.hashCode())
                    }
                    labLog("s6q_queued_run", "gen" to gen, "ran" to ran, "destroyed" to isDestroyed, "probe" to probe.hashCode())
                }, 2200)
                val probe2 = Object()
                mainHandler.postDelayed({
                    val ran = ctrl.runIfCurrent(gen) {
                        labLog("s6q_ui_effect_control", "gen" to gen, "probe" to probe2.hashCode())
                    }
                    labLog("s6q_control_run", "gen" to gen, "ran" to ran, "probe" to probe2.hashCode())
                }, 250)
                mainHandler.postDelayed({
                    labLog("s6q_finish_now", "gen" to gen)
                    finish()
                }, 500)
                // A fase 2 (nova instância) roda beginSession — é o que torna o
                // runnable enfileirado obsoleto (runIfCurrent=false).
                labLog("s6q_phase1_ready", "gen" to gen)
            }
            "newSession" -> {
                // Fase 2 de s6/queue: uma nova instância da Activity abre uma
                // sessão nova (gera a invalidação dos posts enfileirados da
                // fase 1) e observa por tempo suficiente o runnable obsoleto.
                val gen = ctrl.beginSession()
                lastGen = gen
                labLog("s6q_p2_begin", "gen" to gen)
                Thread.sleep(3200)
                labLog("s6q_p2_observe_done", "gen" to gen)
                done("PASS", "gen" to gen)
            }
            else -> done("FAIL", "reason" to "modo_desconhecido", "mode" to modeArg)
        }
    }

    /** s7 — texto iniciado por 'Erro:'/'Cancelado:' (áudio real C06 + seam do parser). */
    private fun scenarioS7() {
        val pairs = listOf(
            Triple("Cancelado: isto é fala real do áudio", "ok", "Success"),
            Triple("texto normal sem prefixo", "cancelled", "Cancelled"),
            Triple("Erro: isto também pode ser fala real", "ok", "Success"),
            Triple("qualquer coisa", "no_speech", "NoSpeech"),
            Triple("Erro: falha nativa de verdade", "error", "Error"),
            Triple("Erro: legado sem status", null, "Error"),
            Triple("Cancelado: legado sem status", null, "Cancelled"),
        )
        for ((text, status, expected) in pairs) {
            val got = WhisperResultParser.parse(text, status).javaClass.simpleName
            labLog("s7_seam", "status" to (status ?: "<null>"), "expected" to expected, "got" to got, "ok" to (got == expected), "text" to text.take(60))
        }
        val model = modelPath()
        val wavPath = wav("c06_prefix_collision.wav")
        var audioOk = false
        if (mustFile(wavPath, "c06") && mustFile(model, "model")) {
            val gen = ctrl.beginSession()
            lastGen = gen
            val ok = ctrl.loadModel(gen, model, 0, false)
            val (text, status) = ctrl.transcribeAndStatus(gen, wavPath, lang(), beam(), 1, false, false, "", LabCallback("s7c06"))
            val parsed = WhisperResultParser.parse(text, status)
            File(outDir, "s7-c06-text.utf8").writeBytes(text.toByteArray(Charsets.UTF_8))
            labLog(
                "s7_c06_audio", "status" to status, "len" to text.length,
                "starts_erro" to text.trimStart().startsWith("Erro:"),
                "starts_cancelado" to text.trimStart().startsWith("Cancelado:"),
                "parsed" to parsed.javaClass.simpleName, "text" to text.take(240)
            )
            audioOk = status == "ok" && parsed is WhisperTranscriptResult.Success
            labLog("s7_c06_load", "ok" to ok)
        }
        val genStale = ctrl.beginSession()
        lastGen = genStale
        val (staleText, staleStatus) = ctrl.transcribeAndStatus(genStale - 1L, wav("c01_speech_short.wav"), lang(), beam(), 1, false, false, "", LabCallback("s7stale"))
        labLog(
            "s7_stale_pair", "text" to staleText, "status" to staleStatus,
            "parsed" to WhisperResultParser.parse(staleText, staleStatus).javaClass.simpleName,
            "expect" to WhisperSessionController.STALE_RESULT_TEXT
        )
        val pass = audioOk && staleText == WhisperSessionController.STALE_RESULT_TEXT
        done(if (pass) "PASS" else "FAIL", "audio_ok" to audioOk, "stale_text" to staleText)
    }

    /**
     * s15 (R6/fase E, E2) — TRAVESSIA JNI REAL de UTF-8 com a sonda do
     * laboratório (SÓ build DIAG da lib):
     *   (a) bytes CRUS -> sanitizador de produção -> jstring (ART valida);
     *   (b) medidas Java: length (UTF-16), code points, U+FFFD (contagem e
     *       posição), SHA-256/hex do UTF-8 de ida;
     *   (c) ida/volta: GetStringUTFChars (MUTF-8 do ART: CESU-8 para fora do
     *       BMP; C0 80 para U+0000) comparado com o esperado.
     * Casos: C3+B3 válido, B3 órfão, prefixo incompleto terminal, emoji 4 bytes,
     * NUL cru, NUL MUTF-8 (C0 80), par CESU-8 direto e FRONTEIRA de segmento
     * (fragmento que completa na concatenação: preservado no texto COMPLETO;
     * fragmento sozinho vira U+FFFD — contrato por string completa).
     */
    private fun scenarioS15() {
        class Case(
            val name: String,
            val rawHex: String,
            val expReplacements: Int,
            val expLenUtf16: Int,
            val expCodePoints: Int,
            val expFffdIndex: Int,
            val expBackMutf8Hex: String,
        )
        fun unhex(s: String): ByteArray {
            val out = ByteArray(s.length / 2)
            for (i in out.indices) out[i] = ((Character.digit(s[2 * i], 16) shl 4) or Character.digit(s[2 * i + 1], 16)).toByte()
            return out
        }
        val cases = listOf(
            Case("c3b3_completo", "61c3b362", 0, 3, 3, -1, "61c3b362"),
            Case("b3_orfao", "61b362", 1, 3, 3, 1, "61efbfbd62"),
            Case("c3_prefixo_incompleto_terminal", "61c3", 1, 2, 2, 1, "61efbfbd"),
            // IDA/VOLTA: no ART do PJA110 (Android 13) o GetStringUTFChars emite
            // UTF-8 de 4 bytes para fora do BMP (NÃO CESU-8; medido no f2 com o
            // valor antigo) e C0 80 para U+0000. O sanitizador aceita as DUAS
            // formas; a expectativa da volta registra a medição REAL do device.
            Case("emoji_4bytes", "61f09f988062", 0, 4, 3, -1, "61f09f988062"),
            Case("nul_cru", "610062", 1, 3, 3, 1, "61efbfbd62"),
            Case("nul_mutf8_c080", "61c08062", 0, 3, 3, -1, "61c08062"),
            Case("cesu8_par_direto", "61eda0bdedb88062", 0, 4, 3, -1, "61f09f988062"),
            // fronteira: fragmento de SEGMENTO que completa na concatenação
            Case("fronteira_segA_incompleto", "61f09f", 1, 2, 2, 1, "61efbfbd"),
            Case("fronteira_segB_continuacoes", "988062", 2, 3, 3, 0, "efbfbdefbfbd62"),
            Case("fronteira_completo", "61f09f988062", 0, 4, 3, -1, "61f09f988062"),
        )

        // (0) a lib do sandbox e' carregada quando WhisperNative e' TOCADO (init);
        // a sonda esta em OUTRA classe, entao forca a carga antes de resolve-la.
        runCatching { WhisperNative.ownershipApiVersion() }

        // (0b) a sonda existe? (lib DIAG no sandbox). Sem ela => SKIP declarado.
        try {
            LabUtf8Probe.sanitize(unhex("61"))
        } catch (t: UnsatisfiedLinkError) {
            labLog("s15_probe_ausente", "erro" to t.toString())
            done("SKIP", "reason" to "lab_probe_ausente_lib_diag_necessaria")
            return
        }

        var allOk = true
        for (c in cases) {
            val raw = unhex(c.rawHex)
            val text = try {
                LabUtf8Probe.sanitize(raw)
            } catch (t: Throwable) {
                labLog("s15_case_exception", "name" to c.name, "throwable" to t.toString())
                allOk = false
                continue
            }
            val utf8 = text.toByteArray(Charsets.UTF_8)
            val fffdCount = text.count { it == '\uFFFD' }
            val fffdIndex = text.indexOf('\uFFFD')
            val codePoints = text.codePointCount(0, text.length)
            val backJson = runCatching { JSONObject(LabUtf8Probe.mutf8Back(text)) }.getOrNull()
            val backHex = backJson?.optString("hex") ?: "?"
            val backLen = backJson?.optInt("mutf8_len", -1) ?: -1
            // forma REAL da volta (o contrato aceita ambas; a saúde é o conteudo)
            val backForm = when {
                backHex.contains("eda0bd") || backHex.contains("edb880") -> "cesu8"
                backHex.contains("f09f9880") -> "utf8-4b"
                else -> "utf8"
            }
            val ok = fffdCount == c.expReplacements && text.length == c.expLenUtf16 &&
                codePoints == c.expCodePoints && fffdIndex == c.expFffdIndex &&
                backHex == c.expBackMutf8Hex
            if (!ok) allOk = false
            labLog(
                "s15_case",
                "name" to c.name,
                "raw_hex" to c.rawHex,
                "exp_replacements" to c.expReplacements,
                "fffd_count" to fffdCount,
                "fffd_index" to fffdIndex,
                "len_utf16" to text.length,
                "code_points" to codePoints,
                "utf8_bytes" to utf8.size,
                "utf8_sha256" to sha256(utf8),
                "utf8_hex" to hex(utf8, 40),
                "back_mutf8_len" to backLen,
                "back_mutf8_hex" to backHex,
                "back_form" to backForm,
                "exp_back_mutf8_hex" to c.expBackMutf8Hex,
                "nul_present" to text.contains('\u0000'),
                "emoji_present" to text.contains("\uD83D\uDE00"),
                "ok" to ok
            )
        }
        done(if (allOk) "PASS" else "FAIL", "cases" to cases.size, "all_ok" to allOk)
    }

    /** s8 — silêncio (no_speech), arquivo inválido (erro explícito) e recuperação. */
    private fun scenarioS8() {
        val model = modelPath()
        if (!mustFile(model, "model")) {
            done("FAIL", "reason" to "missing_model")
            return
        }
        val gen = ctrl.beginSession()
        lastGen = gen
        val ok = ctrl.loadModel(gen, model, 0, false)
        labLog("s8_load", "ok" to ok)
        val silence = wav("c05_silence.wav")
        var silenceStatus = ""
        if (mustFile(silence, "c05a")) {
            val (text, status) = ctrl.transcribeAndStatus(gen, silence, lang(), beam(), 1, false, false, "", LabCallback("s8sil"))
            silenceStatus = status
            labLog("s8_silence", "status" to status, "len" to text.trim().length, "text" to text.take(80))
        }
        var invalidStatus = ""
        var invalidText = ""
        val invalid = wav("c08_invalid_riff.wav")
        if (mustFile(invalid, "c08")) {
            val (text, status) = ctrl.transcribeAndStatus(gen, invalid, lang(), beam(), 1, false, false, "", LabCallback("s8inv"))
            invalidStatus = status
            invalidText = text
            labLog("s8_invalid", "status" to status, "len" to text.length, "text" to text.take(160), "starts_erro" to text.trimStart().startsWith("Erro:"))
        }
        val (goodText, goodStatus) = ctrl.transcribeAndStatus(gen, wav("c01_speech_short.wav"), lang(), beam(), 1, false, false, "", LabCallback("s8rec"))
        labLog("s8_recovery", "status" to goodStatus, "len" to goodText.length)
        val pass = ok && silenceStatus == "no_speech" && invalidStatus == "error" &&
            invalidText.trimStart().startsWith("Erro:") && goodStatus == "ok" && goodText.isNotBlank()
        done(
            if (pass) "PASS" else "FAIL",
            "silence_status" to silenceStatus, "invalid_status" to invalidStatus, "recovery_status" to goodStatus
        )
    }

    /** s9 — UTF-8 real (C01 com acentos), emoji/NUL no seam Kotlin e no JNI (MUTF-8). */
    private fun scenarioS9() {
        val model = modelPath()
        val wavPath = wav("c01_speech_short.wav")
        var c01Ok = false
        if (mustFile(model, "model") && mustFile(wavPath, "c01")) {
            val gen = ctrl.beginSession()
            lastGen = gen
            val ok = ctrl.loadModel(gen, model, 0, false)
            val (text, status) = ctrl.transcribeAndStatus(gen, wavPath, lang(), beam(), 1, false, false, "", LabCallback("s9c01"))
            val bytes = text.toByteArray(Charsets.UTF_8)
            File(outDir, "s9-text.utf8").writeBytes(bytes)
            val decoded = File(outDir, "s9-text.utf8").readBytes().toString(Charsets.UTF_8)
            var c3a9 = 0
            for (i in 0 until bytes.size - 1) if (bytes[i] == 0xC3.toByte() && bytes[i + 1] == 0xA9.toByte()) c3a9++
            labLog(
                "s9_c01_bytes", "status" to status, "len_utf16" to text.length, "bytes_utf8" to bytes.size,
                "sha256" to sha256(bytes), "has_revisao" to decoded.contains("revisão"),
                "has_relatorios" to decoded.contains("relatórios"), "seq_c3a9" to c3a9,
                "non_ascii_bytes" to bytes.count { (it.toInt() and 0x80) != 0 }
            )
            c01Ok = status == "ok" && decoded.contains("revisão") && decoded.contains("relatórios")
        }
        // (b) seam Kotlin: emoji + NUL atravessando String/arquivo/JSON
        val weird = "çãõ 😀 fim\u0000depois"
        val weirdFile = File(outDir, "s9-weird.utf8")
        weirdFile.writeText(weird, Charsets.UTF_8)
        val back = weirdFile.readText(Charsets.UTF_8)
        labLog(
            "s9_kotlin_roundtrip", "equal" to (back == weird), "utf16_len" to weird.length,
            "utf8_bytes" to weird.toByteArray(Charsets.UTF_8).size,
            "has_emoji" to back.contains("\uD83D\uDE00"), "has_nul" to back.contains('\u0000'),
            "hex" to hex(weird.toByteArray(Charsets.UTF_8)),
            "json_escaped" to JSONObject().put("v", weird).toString()
        )
        // (c) JNI real: caminho com emoji + NUL atravessa GetStringUTFChars/NewStringUTF
        //     via lastLoadLog (o log nativo ecoa 'loading model from <path>').
        val emojiPath = File(modelDir, "modelo-\uD83D\uDE00-nulo\u0000fim.bin").absolutePath
        val gen2 = ctrl.beginSession()
        lastGen = gen2
        val loaded = try {
            WhisperNative.loadModelOwned(emojiPath, 0, false, gen2)
        } catch (t: Throwable) {
            labLog("s9_jni_exception", "t" to t.toString())
            true
        }
        val nativeLog = runCatching { WhisperNative.lastLoadLog() }.getOrDefault("")
        labLog(
            "s9_jni_roundtrip", "load_ok" to loaded,
            "log_has_emoji" to nativeLog.contains("\uD83D\uDE00"),
            "log_has_nul" to nativeLog.contains('\u0000'),
            "log_len" to nativeLog.length,
            "log_head" to nativeLog.lineSequence().firstOrNull()?.take(220)
        )
        // (d) recuperação: recarrega o modelo real e transcreve de novo
        val okReload = ctrl.loadModel(gen2, model, 0, false)
        val (text2, status2) = ctrl.transcribeAndStatus(gen2, wavPath, lang(), beam(), 1, false, false, "", LabCallback("s9rec"))
        labLog("s9_recovery", "load" to okReload, "status" to status2, "len" to text2.length)
        val pass = c01Ok && loaded == false && okReload && status2 == "ok" && text2.isNotBlank()
        done(if (pass) "PASS" else "FAIL", "c01_ok" to c01Ok, "jni_load_ok" to loaded, "recovery_status" to status2)
    }

    /** s8b — transcreve o WAV indicado por --es wav (variantes de silêncio/ruído). */
    private fun scenarioS8b() {
        val model = modelPath()
        val name = intent?.getStringExtra("wav") ?: return
        val wavPath = wav(name)
        if (!mustFile(model, "model") || !mustFile(wavPath, name)) {
            done("FAIL", "reason" to "missing_files", "wav" to name)
            return
        }
        val gen = ctrl.beginSession()
        lastGen = gen
        val ok = ctrl.loadModel(gen, model, 0, false)
        labLog("s8b_load", "ok" to ok, "wav" to name)
        val (text, status) = ctrl.transcribeAndStatus(gen, wavPath, lang(), beam(), 1, false, false, "", LabCallback("s8b"))
        labLog(
            "s8b_transcribe", "wav" to name, "status" to status, "len" to text.trim().length,
            "segments" to text.count { it == '\n' } , "text" to text.take(200)
        )
        done("INFO", "wav" to name, "status" to status, "len" to text.trim().length)
    }

    /** s10 — CheckJNI (somente se disponível por app; senão SKIP justificado). */
    private fun scenarioS10() {
        val checkJni = System.getProperty("dalvik.vm.checkjni") ?: "unset"
        val extended = System.getProperty("dalvik.vm.jniopts") ?: "unset"
        labLog("s10_props", "dalvik.vm.checkjni" to checkJni, "dalvik.vm.jniopts" to extended)
        labLog(
            "s10_verdict",
            "note" to "CheckJNI por app: instrumentacao com -e checkjni (AndroidJUnitRunner) ou setprop global debug.checkjni/dalvik.vm.checkjni (root/userdebug). Evidencia de bloqueio no lado host."
        )
        done("SKIP_JUSTIFICADO", "reason" to "checkjni_nao_habilitavel_por_app_sem_mudanca_global")
    }

    /** s11 (R3/C2) — live REAL na MESMA rota da WhisperActivity: PCM injetado ->
     * LiveTranscriptionCoordinator (o coordenador de produção) -> transcribe
     * real -> spool + contadores; PCM reconstruído é conferido por hash contra
     * a fonte (independente). Modos: realtime, burst, stop, thrower, sourcefail,
     * cancel, nextjob. */
    private fun scenarioS11(modeArg: String) {
        val model = modelPath()
        val pcmName = intent?.getStringExtra("pcm") ?: "c09_12frases.pcm"
        val pcmFile = File(rootDir, "pcm/$pcmName")
        if (!mustFile(model, "model") || !mustFile(pcmFile.absolutePath, "pcm_$pcmName")) {
            done("FAIL", "reason" to "missing_files", "pcm" to pcmName)
            return
        }
        val probe = FilePcmSource(pcmFile, speed = 0.0)
        val sourceSha = probe.sha256()
        val sourceBytes = probe.allBytes()
        val sourceSamples = probe.totalSamples()

        // R8/D — modo "pace": mede SÓ a cadência da FONTE (sem modelo/decode):
        // speed real/acelerada, EOF e pausa de scheduler injetada, com o MESMO
        // canal de evidência (pcm_source_read/stats) — prova do deadline.
        if (modeArg == "pace") {
            // Evidência no MESMO arquivo c3-<run>.jsonl dos jobs ao vivo.
            WhisperLabDrive.active = true
            WhisperLabDrive.runId = runId
            WhisperLabDrive.logFile = c3LogFile()
            WhisperLabDrive.scenario = "s11-pace"
            val speed = intent?.getDoubleExtra("speed", 1.0) ?: 1.0
            val pauseAt = intent?.getLongExtra("pauseAtMs", -1L) ?: -1L
            val pauseFor = intent?.getLongExtra("pauseForMs", 0L) ?: 0L
            val src = FilePcmSource(
                pcmFile, speed = speed, pauseAtMs = pauseAt, pauseForMs = pauseFor,
                onRead = { e ->
                    WhisperLabDrive.log(
                        "pcm_source_read", "i" to e.index, "samples" to e.samples,
                        "delivered" to e.deliveredSamples,
                        "target_ms" to String.format(java.util.Locale.US, "%.3f", e.targetMs),
                        "return_ms" to String.format(java.util.Locale.US, "%.3f", e.returnMs),
                        "late_ms" to String.format(java.util.Locale.US, "%.3f", e.lateMs),
                        "jitter_ms" to String.format(java.util.Locale.US, "%.3f", e.jitterMs),
                        "sleep_ms" to String.format(java.util.Locale.US, "%.3f", e.sleepMs)
                    )
                },
                onStats = { s ->
                    WhisperLabDrive.log(
                        "pcm_source_stats", "reads" to s.reads, "samples" to s.samples,
                        "audio_ms" to String.format(java.util.Locale.US, "%.1f", s.audioMs),
                        "wall_ms" to String.format(java.util.Locale.US, "%.1f", s.wallMs),
                        "ratio" to String.format(java.util.Locale.US, "%.4f", s.ratio),
                        "jitter_p50" to String.format(java.util.Locale.US, "%.3f", s.jitterAbsP50Ms),
                        "jitter_p95" to String.format(java.util.Locale.US, "%.3f", s.jitterAbsP95Ms),
                        "jitter_max" to String.format(java.util.Locale.US, "%.3f", s.jitterAbsMaxMs),
                        "late_sum_ms" to String.format(java.util.Locale.US, "%.1f", s.lateSumMs),
                        "late_max_ms" to String.format(java.util.Locale.US, "%.1f", s.lateMaxMs),
                        "catchups" to s.catchUps, "reanchors" to s.reAnchors,
                        "reanchor_ms" to String.format(java.util.Locale.US, "%.1f", s.reAnchorMs),
                        "pauses" to s.pauses,
                        "pause_ms" to String.format(java.util.Locale.US, "%.1f", s.pauseInjectedMs),
                        "eof_ms" to String.format(java.util.Locale.US, "%.1f", s.eofReturnMs),
                        "eof_ahead_ms" to String.format(java.util.Locale.US, "%.1f", s.eofAheadOfPlanMs)
                    )
                }
            )
            val buf = ByteArray(16_384)
            var totalBytes = 0L
            var reads = 0
            val t0 = SystemClock.elapsedRealtime()
            while (true) {
                val r = src.read(buf)
                if (r < 0) break
                totalBytes += r
                reads++
            }
            val wallMs = SystemClock.elapsedRealtime() - t0
            src.close()
            val audioMs = totalBytes / 2 * 1000.0 / 16000.0
            WhisperLabDrive.log(
                "s11_pace", "samples" to totalBytes / 2, "reads" to reads, "wall_ms" to wallMs,
                "audio_ms" to String.format(java.util.Locale.US, "%.1f", audioMs),
                "speed" to speed,
                "ratio" to String.format(java.util.Locale.US, "%.5f", wallMs / audioMs)
            )
            done(
                "PASS", "mode" to "pace", "samples" to totalBytes / 2, "reads" to reads,
                "wall_ms" to wallMs, "audio_ms" to String.format(java.util.Locale.US, "%.1f", audioMs)
            )
            return
        }

        val gen = ctrl.beginSession()
        lastGen = gen
        val loaded = ctrl.loadModel(gen, model, 0, false)
        labLog(
            "s11_load", "gen" to gen, "ok" to loaded, "mode" to modeArg, "pcm" to pcmName,
            "pcm_samples" to sourceSamples, "pcm_sha256" to sourceSha
        )
        if (!loaded) {
            done("FAIL", "reason" to "load_failed")
            return
        }

        val md = MessageDigest.getInstance("SHA-256")
        val hashBytes = AtomicLong(0L)
        val chunks = AtomicInteger(0)
        val delivered = AtomicInteger(0)
        val suppressed = AtomicInteger(0)
        val terminalEvents = AtomicInteger(0)
        val pendingEvents = AtomicInteger(0)
        val startMs = SystemClock.elapsedRealtime()
        val genRef = gen

        val coordinator = LiveTranscriptionCoordinator<String?>(
            spoolDir = File(rootDir, "s11-spool"),
            chunkSamples = 32000,   // 2 s por chunk
            processChunk = { chunk ->
                md.update(chunk.pcm)
                hashBytes.addAndGet(chunk.pcm.size.toLong())
                chunks.incrementAndGet()
                val wav = File(outDir, "s11-chunk-${chunk.index}.wav")
                writePcmWav(wav, chunk.pcm)
                val (text, status) = ctrl.transcribeAndStatus(
                    genRef, wav.absolutePath, lang(), beam(), 1, false, false, "", LabCallback("s11c${chunk.index}")
                )
                labLog(
                    "s11_chunk", "index" to chunk.index, "samples" to chunk.sampleCount,
                    "status" to status, "text_len" to text.trim().length,
                    "elapsed_ms" to (SystemClock.elapsedRealtime() - startMs)
                )
                wav.delete()
                if (status == "ok" && text.isNotBlank()) text else null
            },
            silenceCheck = { it.isNullOrBlank() },
            deliver = { _, result, ok ->
                if (modeArg == "thrower") throw IllegalStateException("s11-deliver-thrower")
                if (ok && !result.isNullOrBlank()) {
                    mainHandler.post {
                        val ran = ctrl.runIfCurrent(genRef) {
                            delivered.incrementAndGet()
                            labLog("s11_delivered", "n" to delivered.get(), "len" to result.length)
                        }
                        if (!ran) suppressed.incrementAndGet()
                    }
                }
            },
            terminal = { cancelled ->
                terminalEvents.incrementAndGet()
                labLog("s11_terminal", "cancelled" to cancelled)
            },
        )
        coordinator.start()

        if (modeArg == "cancel") {
            val reader = Thread { coordinator.runReader(FilePcmSource(pcmFile, speed = 0.0), { true }) }
            reader.start()
            Thread.sleep(900)
            coordinator.cancel()
            reader.join(30_000)
        } else {
            val speed = if (modeArg == "realtime") 1.0 else 0.0
            val source: LivePcmSource =
                if (modeArg == "sourcefail") FailingPcmSource(pcmFile, failAfterReads = 4)
                else FilePcmSource(pcmFile, speed = speed)
            val outcome = coordinator.runReader(source, { true })
            labLog(
                "s11_readout", "reason" to outcome.reason, "samples_read" to outcome.samplesRead,
                "odd_bytes" to outcome.oddBytesDropped
            )
            if (modeArg == "stop") {
                // worker ainda em inferência real: timeout CURTO injetado => pendente VISÍVEL
                coordinator.stopAndAwait(stopTimeoutMs = 300L, onPending = { elapsed ->
                    pendingEvents.incrementAndGet()
                    labLog("s11_stop_pending", "elapsed_ms" to elapsed)
                })
            }
        }
        coordinator.stopAndAwait(stopTimeoutMs = 60_000L)

        val counters = coordinator.counters()
        val reconstructed = md.digest().joinToString("") { "%02x".format(it) }
        val hashMatches = reconstructed == sourceSha
        val profBytes = minOf(hashBytes.get(), sourceBytes.size.toLong()).toInt()
        val prefixShaOk = reconstructed == sha256(sourceBytes.copyOf(profBytes))
        labLog(
            "s11_counters",
            "produced" to counters.producedSamples, "captured" to counters.capturedSamples,
            "processed" to counters.processedSamples, "ok" to counters.okSamples,
            "silence" to counters.silenceSamples, "failed" to counters.failedSamples,
            "cancelled" to counters.cancelledSamples, "lost" to counters.lostSamples,
            "late_lost" to counters.lateLostSamples, "odd_bytes" to counters.oddBytesDropped,
            "listener_errors" to counters.listenerErrors, "pending_samples" to counters.pendingSamples(),
            "balanced" to counters.isBalanced(), "consistent" to counters.isConsistent(),
            "summary" to counters.summary()
        )
        labLog(
            "s11_hash", "source_sha256" to sourceSha, "reconstructed_sha256" to reconstructed,
            "match" to hashMatches, "match_prefix" to prefixShaOk,
            "hash_bytes" to hashBytes.get(), "source_bytes" to sourceBytes.size,
            "chunks" to chunks.get(), "delivered" to delivered.get(), "suppressed" to suppressed.get(),
            "terminal_events" to terminalEvents.get(), "pending_events" to pendingEvents.get(),
            "worker_alive" to coordinator.pipeline.workerAlive(),
            "elapsed_ms" to (SystemClock.elapsedRealtime() - startMs)
        )

        var nextJobOk = true
        if (modeArg == "nextjob") {
            val gen2 = ctrl.beginSession()
            val assumed = ctrl.assumeContext(gen2, model, 0, false)
            labLog(
                "s11_nextjob_assume", "gen" to gen, "gen2" to gen2, "assumed" to assumed,
                "ctx_owner" to WhisperNative.contextOwnerSafe()
            )
            val (text2, status2) = ctrl.transcribeAndStatus(
                gen2, wav("c01_speech_short.wav"), lang(), beam(), 1, false, false, "", LabCallback("s11nj")
            )
            labLog("s11_nextjob_result", "status" to status2, "len" to text2.trim().length)
            nextJobOk = assumed && status2 == "ok" && text2.isNotBlank()
        }

        val workerGone = !coordinator.pipeline.workerAlive()
        val baseOk = counters.isBalanced() && terminalEvents.get() == 1 && workerGone
        val pass = when (modeArg) {
            "realtime", "burst" -> baseOk && hashMatches && counters.lostSamples == 0L && counters.cancelledSamples == 0L
            "stop" -> baseOk && hashMatches && pendingEvents.get() >= 1
            "thrower" -> baseOk && counters.listenerErrors > 0
            "sourcefail" -> baseOk && counters.sourceFailure != null && prefixShaOk
            "cancel" -> baseOk && counters.cancelledSamples > 0L && suppressed.get() + delivered.get() >= 0
            "nextjob" -> baseOk && hashMatches && counters.lostSamples == 0L && nextJobOk
            else -> baseOk && hashMatches
        }
        done(
            if (pass) "PASS" else "FAIL", "mode" to modeArg, "balanced" to counters.isBalanced(),
            "hash_match" to hashMatches, "prefix_ok" to prefixShaOk, "next_job_ok" to nextJobOk
        )
    }

    /** s12 (R3/B1) — invariantes de ownership NA lib real do sandbox. Modos:
     * pending2 (dois cancelamentos pendentes), refusal (contexto de B + transcribe
     * de A => recusa nativa), activeOwner (waiter não se declara ativo). */
    private fun scenarioS12(modeArg: String) {
        val model = modelPath()
        val wavPath = wav("c01_speech_short.wav")
        if (!mustFile(model, "model") || !mustFile(wavPath, "c01")) {
            done("FAIL", "reason" to "missing_files")
            return
        }
        when (modeArg) {
            "pending2" -> {
                val genA = ctrl.beginSession()
                lastGen = genA
                val loaded = ctrl.loadModel(genA, model, 0, false)
                labLog("s12_load", "gen" to genA, "ok" to loaded, "reason" to nativeLogExtract())
                val statusRef = java.util.concurrent.atomic.AtomicReference("")
                val textRef = java.util.concurrent.atomic.AtomicReference("")
                val t = Thread {
                    val (tx, st) = ctrl.transcribeAndStatus(genA, wavPath, lang(), beam(), 1, false, false, "", LabCallback("s12A"))
                    textRef.set(tx); statusRef.set(st)
                }
                t.start()
                Thread.sleep(400)
                ctrl.cancel(genA)
                val genB = ctrl.beginSession()
                ctrl.cancel(genB)
                t.join(180_000)
                val loadedB = ctrl.loadModel(genB, model, 0, false)
                val (_, statusB) = ctrl.transcribeAndStatus(genB, wavPath, lang(), beam(), 1, false, false, "", LabCallback("s12B"))
                val pass = statusRef.get() == "cancelled" && statusB == "cancelled" && loadedB
                labLog(
                    "s12_pending2", "genA" to genA, "genB" to genB, "statusA" to statusRef.get(),
                    "statusB" to statusB, "textA" to textRef.get().take(80), "loadedB" to loadedB
                )
                done(if (pass) "PASS" else "FAIL", "mode" to modeArg, "statusA" to statusRef.get(), "statusB" to statusB)
            }
            "refusal" -> {
                val genA = ctrl.beginSession()
                lastGen = genA
                ctrl.loadModel(genA, model, 0, false)
                val genB = ctrl.beginSession()
                val okB = ctrl.loadModel(genB, model, 0, false)
                val direct = WhisperNative.transcribeOwned(wavPath, lang(), beam(), 1, false, false, "", genA, LabCallback("s12ref"))
                val refused = direct.startsWith("Erro: recusa nativa")
                labLog(
                    "s12_refusal", "genA" to genA, "genB" to genB, "loadedB" to okB,
                    "direct_prefix" to direct.take(90), "refused" to refused,
                    "ctx_owner" to WhisperNative.contextOwnerSafe()
                )
                done(if (refused) "PASS" else "FAIL", "mode" to modeArg, "refused" to refused)
            }
            "activeOwner" -> {
                val genA = ctrl.beginSession()
                lastGen = genA
                ctrl.loadModel(genA, model, 0, false)
                val doneA = CountDownLatch(1)
                val tA = Thread {
                    ctrl.transcribeAndStatus(genA, wavPath, lang(), beam(), 1, false, false, "", LabCallback("s12act"))
                    doneA.countDown()
                }
                tA.start()
                Thread.sleep(150)
                val genB = ctrl.beginSession()
                val resultB = java.util.concurrent.atomic.AtomicReference("")
                val tB = Thread {
                    resultB.set(
                        runCatching {
                            WhisperNative.transcribeOwned(wavPath, lang(), beam(), 1, false, false, "", genB, LabCallback("s12wait"))
                        }.getOrElse { "EXC:" + it }
                    )
                }
                tB.start()
                val seen = LinkedHashSet<Long>()
                val t0 = SystemClock.elapsedRealtime()
                while (SystemClock.elapsedRealtime() - t0 < 8000) {
                    seen.add(WhisperNative.activeTranscribeOwnerSafe())
                    if (doneA.count == 0L) break
                    Thread.sleep(25)
                }
                tA.join(30_000)
                tB.join(30_000)
                val sawExecutor = seen.contains(genA)
                val sawWaiter = seen.contains(genB)
                labLog(
                    "s12_active", "genA" to genA, "genB" to genB, "seen" to seen.joinToString(","),
                    "saw_executor" to sawExecutor, "saw_waiter" to sawWaiter,
                    "resultB_prefix" to resultB.get().take(80)
                )
                val pass = sawExecutor && !sawWaiter
                done(if (pass) "PASS" else "FAIL", "mode" to modeArg, "saw_executor" to sawExecutor, "saw_waiter" to sawWaiter)
            }
            else -> done("FAIL", "reason" to "unknown_mode", "mode" to modeArg)
        }
    }
}

/** Controller compartilhado do PROCESSO de laboratório. Espelha
 * [WhisperSessions.shared] de produção: a geração de sessão é única por
 * processo, então uma nova instância da Activity (nova sessão) invalida posts
 * e callbacks da instância anterior — o contrato de ownership sob teste. */
object WhisperLab {
    val controller: WhisperSessionController = WhisperSessionController(
        gateway = WhisperNativeGateway,
        logError = { message, t -> android.util.Log.e("WhisperLab", message, t) }
    )
}
