package br.gov.sp.pcsp.launcher

import android.content.Context
import android.os.PowerManager
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * R123/T02 — FUMAÇA pela ESCOLHA DA TELA, com o hardware EFETIVO lido da tela.
 *
 * A célula NÃO recebe o hardware por parâmetro: ela abre a `WhisperActivity`,
 * lê o rótulo do seletor "Hardware" (o mesmo `WhisperSelectors.effective` que a
 * tela usa) e despacha o clipe pela rota de produto
 * ([WhisperOrtRoute.transcribe]) com esse hardware. Assim a prova cobre a
 * decisão da tela, não uma constante do teste.
 *
 * A `WhisperActivity` fica em PRIMEIRO PLANO durante toda a célula
 * (`ActivityScenario` aberto): o ColorOS congela processo em segundo plano
 * (`do_freezer_trap`) e a inferência pararia no meio.
 *
 * Privacidade: o recibo leva outcome, rota, hardware, threads, contagens e
 * hashes — NUNCA o texto transcrito. O texto só é usado para contar bytes.
 *
 * Args: `r123_clipe` (WAV 16 kHz mono), `r123_saida` (jsonl do recibo),
 * `r123_timeout_s` (default 600).
 */
@RunWith(AndroidJUnit4::class)
class WhisperSeletoresR123FumacaTest {

    private companion object {
        const val TAG = "R123-FUMACA"
        const val SCHEMA = "sig-whisper-seletores-r123-fumaca/v1"
        const val ROTULO_HARDWARE = "Hardware"
    }

    private val ctx: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val args
        get() = InstrumentationRegistry.getArguments()

    private val saida: File
        get() = File(args.getString("r123_saida") ?: File(ctx.filesDir, "r123/fumaca.jsonl").absolutePath)

    private fun linha(json: JSONObject) {
        saida.parentFile?.mkdirs()
        saida.appendText(json.toString() + "\n", Charsets.UTF_8)
    }

    /**
     * Tela acesa durante a célula. É um wake lock do PRÓPRIO app de teste, não
     * configuração do sistema.
     */
    private fun manterTelaAcesa(): PowerManager.WakeLock? = runCatching {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "SIG:R123Fumaca",
        ).apply { acquire(60 * 60_000L) }
    }.getOrNull()

    /**
     * Rótulo do seletor de [descricao] na tela aberta.
     *
     * Os quatro seletores (`button_engine`, `button_backend`, `button_threads`,
     * `button_whisper_vad`) carregam `contentDescription` = Motor/Hardware/
     * Threads/VAD; o `text` do nó é o VALOR efetivo que a tela está exibindo.
     * A leitura é pela árvore, sem tocar na Activity.
     */
    private fun rotuloDoSeletor(descricao: String): String? {
        val instrumentacao = InstrumentationRegistry.getInstrumentation()
        val raiz = instrumentacao.uiAutomation.rootInActiveWindow ?: return null
        try {
            val pilha = ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
            pilha.add(raiz)
            while (pilha.isNotEmpty()) {
                val no = pilha.removeFirst()
                if (no.contentDescription?.toString()?.trim() == descricao) {
                    return no.text?.toString()?.trim()
                }
                for (i in 0 until no.childCount) no.getChild(i)?.let { pilha.add(it) }
            }
        } finally {
            runCatching { raiz.recycle() }
        }
        return null
    }

    /** Valor do seletor "Hardware" — o hardware EFETIVO que a tela decidiu. */
    private fun rotuloHardwareNaTela(): String? = rotuloDoSeletor(ROTULO_HARDWARE)

    @Test
    fun fumacaPelaEscolhaDaTela() {
        val lock = manterTelaAcesa()
        val clipe = args.getString("r123_clipe")?.let { File(it) }
        val timeoutS = (args.getString("r123_timeout_s") ?: "600").toLong()
        val cenario = ActivityScenario.launch(WhisperActivity::class.java)
        try {
            assertTrue("PRE_CONDICAO: clipe ausente", clipe != null && clipe.isFile)
            requireNotNull(clipe)
            val ligado = WhisperOrtRoute.isEnabled(ctx)
            val pacote = (WhisperOrtNpuPacote.estado(ctx) as? WhisperOrtNpuPacote.Estado.Pronto)
                ?.manifesto?.versao ?: "nenhum"
            val hardwareTela = rotuloHardwareNaTela()
            val hardware = WhisperSelectors.Hardware.entries.firstOrNull { it.label == hardwareTela }
            linha(
                JSONObject()
                    .put("schema", SCHEMA)
                    .put("tipo", "pre")
                    .put("motor", rotuloDoSeletor("Motor") ?: "")
                    .put("motor_ort_ligado", ligado)
                    .put("pacote_versao", pacote)
                    .put("hardware_tela", hardwareTela ?: "")
                    .put("hardware_efetivo", hardware?.name ?: "")
                    .put("threads_tela", rotuloDoSeletor("Threads") ?: "")
                    .put("vad_tela", rotuloDoSeletor("VAD") ?: "")
                    .put("pid", android.os.Process.myPid()),
            )
            Log.i(
                TAG,
                "pre pid=${android.os.Process.myPid()} motor_ort=$ligado pacote=$pacote " +
                    "hardware_tela=${hardwareTela ?: "?"}",
            )
            assertTrue("PRE_CONDICAO: motor ORT ligado", ligado)
            assertTrue("PRE_CONDICAO: hardware lido da tela ($hardwareTela)", hardware != null)
            requireNotNull(hardware)

            val pcm = WhisperOrtRoute.pcmDoArquivo(clipe)
            val hashEsperado = WhisperOrtPcm.sha256(WhisperOrtPcm.toInt16Le(pcm))
            val threads = WhisperOrtRoute.threadsEfetivos(ctx)
            val pronto = CountDownLatch(1)
            var job: WhisperOrtAdmission.Job? = null
            var resultado: WhisperOrtRoute.Result? = null
            cenario.onActivity { activity ->
                job = WhisperOrtRoute.transcribe(
                    activity,
                    android.net.Uri.fromFile(clipe),
                    threads = threads,
                    hardware = hardware,
                    vad = LocalVad.Selection(),
                    onResult = { r ->
                        resultado = r
                        pronto.countDown()
                    },
                )
            }
            val jobId = job?.jobId
            assertTrue("PRE_CONDICAO: job despachado", jobId != null)
            val terminou = pronto.await(timeoutS, TimeUnit.SECONDS)
            val terminal = job?.let { WhisperOrtRoute.terminalDe(it) }
            val r = resultado
            val outcome = terminal?.outcome ?: (r?.let { it::class.simpleName } ?: "SEM_TERMINAL")
            linha(
                JSONObject()
                    .put("schema", SCHEMA)
                    .put("tipo", "recibo")
                    .put("outcome", outcome)
                    .put("terminou", terminou)
                    .put("job", jobId ?: "")
                    .put("hardware_tela", hardwareTela ?: "")
                    .put("hardware_efetivo", hardware.name)
                    .put("hardware_terminal", terminal?.hardware ?: "")
                    .put("threads", terminal?.threads ?: threads)
                    .put("modelo", terminal?.model ?: "")
                    .put("input_sha256", terminal?.inputSha256 ?: "")
                    .put("input_esperado", hashEsperado)
                    .put("input_confere", terminal?.inputSha256 == hashEsperado)
                    .put("texto_bytes", terminal?.textBytes ?: -1)
                    .put("texto_sha256", terminal?.textSha256 ?: "")
                    .put("millis", terminal?.totalMillis ?: -1L)
                    .put("audio_ms", terminal?.audioMillis ?: -1L)
                    .put("erro", terminal?.error ?: "")
                    .put("pid", android.os.Process.myPid()),
            )
            Log.i(
                TAG,
                "recibo pid=${android.os.Process.myPid()} outcome=$outcome hardware=${hardware.name} " +
                    "threads=${terminal?.threads ?: threads} input_confere=${terminal?.inputSha256 == hashEsperado}",
            )
            assertTrue("a célula tem de terminar dentro de ${timeoutS}s", terminou)
            assertEquals("outcome do terminal", "SUCCESS", outcome)
            assertEquals("hardware efetivo do terminal", hardware.name, terminal?.hardware)
            assertTrue("o terminal tem de amarrar o áudio desta célula", terminal?.inputSha256 == hashEsperado)
        } finally {
            runCatching { cenario.close() }
            runCatching { lock?.release() }
        }
    }
}
