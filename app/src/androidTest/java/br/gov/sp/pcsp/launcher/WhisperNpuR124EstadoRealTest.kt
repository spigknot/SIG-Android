package br.gov.sp.pcsp.launcher

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * R124/T03 — o ESTADO REAL do usuário, pelo método de despacho da própria tela.
 *
 * A célula NÃO monta o hardware: chama [WhisperActivity.despacharOrtParaTeste],
 * que resolve o modelo efetivo e o hardware por ele (via `hardwareDoJob`) e
 * chama `WhisperOrtRoute.transcribe`. É a rota que o usuário usa — o que a R123
 * não provou.
 *
 * Quando a tela oferece o turbo (pacote instalado e inativo), a célula ACEITA a
 * oferta pela INTERFACE e espera o job que a tela redespacha com o override
 * consentido.
 *
 * O clipe vem do asset da instrumentação: NÃO passa pelo seletor de arquivos.
 *
 * A `WhisperActivity` fica em PRIMEIRO PLANO durante toda a célula
 * (`ActivityScenario` aberto): o ColorOS congela processo em segundo plano
 * (`do_freezer_trap`) e a inferência pararia no meio.
 *
 * Privacidade: o recibo leva outcome, rota, hardware, threads, modelo, hashes e
 * tempos — NUNCA o texto transcrito.
 *
 * Args: `r124_esperado` (`NPU` | `NPU→CPU` | `CPU`), `r124_saida` (jsonl único
 * por célula), `r124_timeout_s` (default 900).
 */
@RunWith(AndroidJUnit4::class)
class WhisperNpuR124EstadoRealTest {

    private companion object {
        const val TAG = "R124-ESTADO-REAL"
        const val SCHEMA = "sig-whisper-npu-r124-estado-real/v1"
        const val FIXTURE = "probe.wav"
        const val ESPERA_UI_MS = 5_000L
        const val ESPERA_ACEITE_MS = 180_000L
    }

    private val ctx: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val args
        get() = InstrumentationRegistry.getArguments()

    private val saida: File
        get() = File(args.getString("r124_saida") ?: File(ctx.filesDir, "r124/estado-real.jsonl").absolutePath)

    private fun linha(json: JSONObject) {
        saida.parentFile?.mkdirs()
        saida.appendText(json.toString() + "\n", Charsets.UTF_8)
    }

    private fun device() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    /** Valor do seletor pelo `resource-id` (o `contentDescription` fixo saiu no R124/T02). */
    private fun rotuloDoSeletor(recurso: String): String? =
        device().wait(Until.findObject(By.res(ctx.packageName, recurso)), ESPERA_UI_MS)?.text?.trim()

    /** Copia o clipe do asset da instrumentação para o app (sem seletor de arquivos). */
    private fun copiarClipe(): File {
        val destino = File(ctx.cacheDir, "r124-$FIXTURE")
        InstrumentationRegistry.getInstrumentation().context.assets.open(FIXTURE).use { fonte ->
            destino.outputStream().use { fonte.copyTo(it) }
        }
        return destino
    }

    /**
     * Aceita a oferta do turbo pela INTERFACE.
     *
     * No tema do app o botão positivo sai em CAIXA ALTA ("USAR … INSTALADO" ou
     * "ATIVAR … INSTALADO"): procura-se pelo texto em caixa alta. Devolve o
     * texto clicado, ou `null` quando não há oferta na tela.
     */
    private fun aceitarOfertaDoTurbo(): String? {
        for (marca in listOf("USAR ", "ATIVAR ", "BAIXAR ")) {
            val no = device().findObject(By.textContains(marca))
            if (no != null && no.text.orEmpty().contains("instalado", ignoreCase = true)) {
                val texto = no.text.orEmpty()
                no.click()
                device().waitForIdle()
                return texto
            }
        }
        return null
    }

    @Test
    fun despachaPeloMetodoDaTela() {
        val esperado = requireNotNull(args.getString("r124_esperado")) {
            "r124_esperado é obrigatório (NPU | NPU→CPU | CPU)"
        }
        val timeoutS = (args.getString("r124_timeout_s") ?: "900").toLong()
        val clipe = copiarClipe()
        val pkg = ctx.packageName
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("cmd package set-home-activity $pkg/home")

        val cenario = ActivityScenario.launch(WhisperActivity::class.java)
        try {
            cenario.onActivity { activity ->
                activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            // ESTADO DA TELA pelo resource-id: é o que o usuário vê.
            val modeloNaTela = rotuloDoSeletor("button_model")
            val hardwareNaTela = rotuloDoSeletor("button_backend")
            linha(
                JSONObject().put("schema", SCHEMA).put("tipo", "tela")
                    .put("motor", rotuloDoSeletor("button_engine") ?: "")
                    .put("modelo", modeloNaTela ?: "")
                    .put("hardware", hardwareNaTela ?: "")
                    .put("threads", rotuloDoSeletor("button_threads") ?: "")
                    .put("vad", rotuloDoSeletor("button_whisper_vad") ?: "")
                    .put("esperado", esperado)
                    .put("pid", android.os.Process.myPid()),
            )

            val pronto = CountDownLatch(1)
            var job: WhisperOrtAdmission.Job? = null
            var resultado: WhisperOrtRoute.Result? = null
            // O QUE O USUÁRIO FAZ NA INTERFACE: com `tiny` no botão, a oferta é
            // "USAR WHISPER-TINY INSTALADO" (CPU). O caso da NPU é o turbo aceito
            // pela oferta do modelo SEM pacote ORT — a célula recebe esse modelo
            // efetivo e o passa como override consentido, que é o mesmo caminho
            // do aceite real (R124/T02 §modeloEfetivoConsentido).
            val efetivoConsentido = args.getString("r124_efetivo_consentido")?.takeIf { it.isNotBlank() }
            cenario.onActivity { activity ->
                job = if (efetivoConsentido == null) {
                    activity.despacharOrtParaTeste(Uri.fromFile(clipe), modeloNaTela, null) { r ->
                        resultado = r
                        pronto.countDown()
                    }
                } else {
                    activity.despacharOrtParaTeste(
                        Uri.fromFile(clipe),
                        modeloNaTela,
                        efetivoConsentido,
                    ) { r ->
                        resultado = r
                        pronto.countDown()
                    }
                }
            }
            linha(
                JSONObject().put("schema", SCHEMA).put("tipo", "despacho")
                    .put("modelo_pedido", modeloNaTela ?: "")
                    .put("modelo_efetivo_consentido", efetivoConsentido ?: "(nenhum)")
                    .put("pid", android.os.Process.myPid()),
            )

            if (job?.jobId == null) {
                // A tela parou no fluxo de pacote: aceita a oferta do turbo pela
                // interface e espera o job que a tela redespacha.
                val aceite = aceitarOfertaDoTurbo()
                linha(
                    JSONObject().put("schema", SCHEMA).put("tipo", "oferta")
                        .put("botao_clicado", aceite ?: "(nenhuma oferta na tela)")
                        .put("pid", android.os.Process.myPid()),
                )
                if (aceite != null) {
                    val limite = System.currentTimeMillis() + ESPERA_ACEITE_MS
                    while (System.currentTimeMillis() < limite && job == null) {
                        Thread.sleep(500L)
                        cenario.onActivity { activity -> job = activity.jobOrtDaTelaParaTeste() }
                    }
                }
            }
            val jobId = job?.jobId
            if (jobId == null) {
                val status = device().findObject(By.res(pkg, "status"))?.text?.trim().orEmpty()
                linha(
                    JSONObject().put("schema", SCHEMA).put("tipo", "sem_job")
                        .put("status_da_tela", status)
                        .put("pid", android.os.Process.myPid()),
                )
                throw AssertionError("a tela não despachou: $status")
            }
            // A partir do aceite o job é o da tela; o latch pode não ter sido
            // ligado nele, então a espera é pelo terminal do PRÓPRIO job.
            var terminal = job?.let { WhisperOrtRoute.terminalDe(it) }
            val limite = System.currentTimeMillis() + timeoutS * 1_000L
            while (terminal == null && System.currentTimeMillis() < limite) {
                Thread.sleep(1_000L)
                terminal = job?.let { WhisperOrtRoute.terminalDe(it) }
            }
            val terminou = terminal != null
            val outcome = terminal?.outcome ?: (resultado?.let { it::class.simpleName } ?: "SEM_TERMINAL")
            linha(
                JSONObject().put("schema", SCHEMA).put("tipo", "recibo")
                    .put("outcome", outcome)
                    .put("terminou", terminou)
                    .put("job", jobId)
                    .put("modelo_tela", modeloNaTela ?: "")
                    .put("hardware_tela", hardwareNaTela ?: "")
                    .put("hardware_terminal", terminal?.hardware ?: "")
                    .put("modelo_terminal", terminal?.model ?: "")
                    .put("threads", terminal?.threads ?: -1)
                    .put("input_sha256", terminal?.inputSha256 ?: "")
                    .put("texto_bytes", terminal?.textBytes ?: -1)
                    .put("millis", terminal?.totalMillis ?: -1L)
                    .put("audio_ms", terminal?.audioMillis ?: -1L)
                    .put("erro", terminal?.error ?: "")
                    .put("pid", android.os.Process.myPid()),
            )
            android.util.Log.i(
                TAG,
                "recibo pid=${android.os.Process.myPid()} job=$jobId outcome=$outcome " +
                    "modelo_tela=${modeloNaTela.orEmpty()} hardware_tela=${hardwareNaTela.orEmpty()} " +
                    "hardware_terminal=${terminal?.hardware.orEmpty()} esperado=$esperado",
            )
            assertTrue("a célula tem de terminar dentro de ${timeoutS}s", terminou)
            assertEquals("outcome do terminal", "SUCCESS", outcome)
            assertEquals("hardware do terminal", esperado, terminal?.hardware)
        } finally {
            runCatching { cenario.close() }
        }
    }
}