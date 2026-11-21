package br.gov.sp.pcsp.launcher

import android.content.Context
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
        const val ESPERA_UI_MS = 5_000L
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
     * Tela acesa durante a célula, SEM pedir permissão do sistema.
     *
     * `FLAG_KEEP_SCREEN_ON` na janela que a célula abre é escopado à janela e
     * não exige `WAKE_LOCK` — ao contrário do `newWakeLock`, que disparou o
     * diálogo "permitir que o app seja executado em segundo plano" do sistema
     * (o diálogo virou a janela ativa e a árvore de acessibilidade passou a ser
     * a do `com.android.permissioncontroller`, não a da tela do produto).
     */
    private fun manterTelaAcesa(): PowerManager.WakeLock? = null

    /** Liga o `FLAG_KEEP_SCREEN_ON` na janela da Activity da célula. */
    private fun manterTelaDaCelulaAcesa(cenario: ActivityScenario<WhisperActivity>) {
        cenario.onActivity { activity ->
            activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun device() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    /**
     * Valor de um seletor da tela do produto, lido pelo `resource-id`.
     *
     * R124/T02 — o `contentDescription` fixo saiu do layout: ele fazia o leitor
     * de tela deixar de anunciar o VALOR do seletor. A célula passa a ler o nó
     * por `resource-id`, que os dumps da UiAutomator já trazem
     * (`br.gov.sp.pcsp.launcher:id/button_*`).
     */
    private fun rotuloDoSeletor(recurso: String): String? {
        val seletor = By.res(InstrumentationRegistry.getInstrumentation().targetContext.packageName, recurso)
        val no = device().wait(Until.findObject(seletor), ESPERA_UI_MS) ?: return null
        return no.text?.trim()
    }

    /** Nó de um seletor pelo `resource-id`, para os diagnósticos. */
    private fun existeSeletor(recurso: String): Boolean =
        device().findObject(By.res(ctx.packageName, recurso)) != null

    /** Diagnóstico: a janela ativa vista pelo UiDevice, antes de ler o seletor. */
    private fun diagnosticoDaJanela(): JSONObject {
        val janela = JSONObject().put("pacote", "").put("classe", "").put("filhos", -1)
            .put("tem_seletor", false).put("erro", "")
        return try {
            val raiz = device().findObject(By.pkg(ctx.packageName))
            janela.put("tem_seletor", existeSeletor("button_backend"))
            janela.put("pacote", if (raiz == null) "" else ctx.packageName)
            janela.put("filhos", if (raiz == null) -1 else 1)
        } catch (t: Throwable) {
            janela.put("erro", "${t.javaClass.simpleName}: ${t.message}")
        }
    }
    /** Valor do seletor "Hardware" — o hardware EFETIVO que a tela decidiu. */
    private fun rotuloHardwareNaTela(): String? = rotuloDoSeletor("button_backend")

    @Test
    fun fumacaPelaEscolhaDaTela() {
        val lock = manterTelaAcesa()
        val clipe = args.getString("r123_clipe")?.let { File(it) }
        val timeoutS = (args.getString("r123_timeout_s") ?: "600").toLong()
        val pkg = ctx.packageName
        // `activityIntents` instala um RESOLVEDOR PADRAO cuja origem EXPLICITA e
        // este pacote. Sem ela o Android 13 adota o modo de compatibilidade que
        // pede POST_NOTIFICATIONS ao abrir a tela, e o dialogo do sistema vira a
        // janela ativa (arvore = permissioncontroller, seletor nao achado).
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("cmd package set-home-activity $pkg/home")
        // R124/T02 — o gancho `seletoresRelerParaTeste` foi descartado: era
        // propriedade de instância que nascia `false` no `onCreate` e nunca podia
        // valer. A célula não depende dele: abre a tela e lê o rótulo pelo
        // `resource-id`, como qualquer usuário que reabre o app.
        val cenario = ActivityScenario.launch(WhisperActivity::class.java)
        var cenario2: ActivityScenario<WhisperActivity>? = null
        manterTelaDaCelulaAcesa(cenario)
        try {
            requireNotNull(clipe)
            val ligado = WhisperOrtRoute.isEnabled(ctx)
            val pacote = (WhisperOrtNpuPacote.estado(ctx) as? WhisperOrtNpuPacote.Estado.Pronto)
                ?.manifesto?.versao ?: "nenhum"
            // Diagnóstico da janela ativa (antes de ler o seletor), do MODELO
            // pedido pela tela lido da LINHA de pedidos ORT
            // (`ort-route/terminais.jsonl`, arquivo de estado do app) e da
            // decisão da regra. Sem esse cruzamento, "hardware null" não diz se
            // a janela não está ativa ou se o nó não está na árvore.
            val linhaOrt = runCatching {
                File(ctx.filesDir, "ort-route/terminais.jsonl").readLines()
                    .mapNotNull { texto -> runCatching { JSONObject(texto) }.getOrNull() }
                    .lastOrNull { it.optString("model").isNotEmpty() }
            }.getOrNull()
            // O "antes" do pacote v3: a conferência RÁPIDA pode pedir verificação
            // completa (o marcador é do formato anterior). A célula dispara a MESMA
            // verificação do produto — leitura de disco, sem apagar nem baixar
            // nada — e só depois decide a regra com a versão verificada.
            val estadoVerificacao = runCatching {
                if (WhisperOrtNpuPacote.precisaReverificar(WhisperOrtNpuPacote.raiz(ctx))) {
                    WhisperOrtNpuPacote.verificarCompleto(ctx).javaClass.simpleName
                } else "sem-reverificacao"
            }.getOrElse { "erro:${it.javaClass.simpleName}" }
            val janela = diagnosticoDaJanela()
            janela.put("linha_ort_modelo", linhaOrt?.optString("model") ?: "")
            janela.put("verificacao_completa", estadoVerificacao)
            val dirAtivoAgora = WhisperOrtNpuPacote.dirAtivo(WhisperOrtNpuPacote.raiz(ctx))
            val manifestoAgora = runCatching {
                WhisperOrtNpuPacote.lerManifesto(File(dirAtivoAgora, "manifest.json").readText())
            }.getOrNull()
            val marcadorAgora = runCatching {
                WhisperOrtNpuPacote.lerMarcador(File(dirAtivoAgora, ".installed").readText())
            }.getOrNull()
            janela.put(
                "marcador",
                JSONObject()
                    .put("dir", dirAtivoAgora.name)
                    .put("manifesto_lido", manifestoAgora != null)
                    .put("marcador_lido", marcadorAgora != null)
                    .put("itens_marcador", marcadorAgora?.itens?.size ?: -1)
                    .put("itens_manifesto", manifestoAgora?.itens?.size ?: -1)
                    .put("assinatura_confere", manifestoAgora != null && marcadorAgora != null &&
                        marcadorAgora.assinatura == WhisperOrtNpuPacote.textoMarcadorEsperado(manifestoAgora))
                    .put("versao_manifesto", manifestoAgora?.versao ?: "")
                    .put("ponta_a_ponta_manifesto", manifestoAgora?.pontaAPonta ?: false),
            )
            linha(JSONObject().put("schema", SCHEMA).put("tipo", "janela").put("arvore", janela)
                .put("pid", android.os.Process.myPid()))
            val arquivosEsperados = listOf(
                "sig_whisper_enc_w8a16.bin", "sig_whisper_enc_w8a16_i16io.onnx",
                "sig_r112_cross_kv_projector.bin", "sig_r112_cross_kv_projector.onnx",
                "decoder_o3.bin", "sig_whisper_dec_w8a16.onnx",
            )
            janela.put(
                "presenca",
                JSONObject()
                    .put("arquivos_ok", arquivosEsperados.all { File(dirAtivoAgora, it).isFile })
                    .put("manifesto_ok", File(dirAtivoAgora, "manifest.json").isFile)
                    .put("marcador_ok", File(dirAtivoAgora, ".installed").isFile)
                    .put("manifesto_bytes", File(dirAtivoAgora, "manifest.json").length())
                    .put("marcador_bytes", File(dirAtivoAgora, ".installed").length()),
            )
            janela.put(
                "leitura",
                JSONObject()
                    .put("raiz", WhisperOrtNpuPacote.raiz(ctx).absolutePath)
                    .put("raiz_existe", WhisperOrtNpuPacote.raiz(ctx).isDirectory)
                    .put("dir_ativo", dirAtivoAgora.absolutePath)
                    .put("dir_ativo_existe", dirAtivoAgora.isDirectory)
                    .put("arquivo_manifesto", File(dirAtivoAgora, "manifest.json").absolutePath)
                    .put("arquivo_manifesto_existe", File(dirAtivoAgora, "manifest.json").isFile)
                    .put("arquivo_manifesto_bytes", File(dirAtivoAgora, "manifest.json").length())
                    .put("listagem", dirAtivoAgora.list()?.size ?: -1),
            )
            val prefsWhisper = ctx.getSharedPreferences("whisper", Context.MODE_PRIVATE)
            val escolhaSalva = WhisperSelectors.Hardware.fromPreference(prefsWhisper.getString("ort_hardware", null))
            val admitido = ligado &&
                WhisperOrtHtpAdmissao.socDe(android.os.Build.SOC_MODEL)?.habilitado == true &&
                WhisperOrtHtpAdmissao.fabricanteAdmitido(android.os.Build.SOC_MANUFACTURER)
            val turboDoPedido = linhaOrt?.optString("model") == WhisperOrtModelCatalog.MODEL
            val versaoPacote = if (QairtDependencyManager.isInstalled(ctx)) {
                val check = runCatching { WhisperOrtNpuPacote.conferenciaRapida(ctx) }.getOrNull()
                if (check is WhisperOrtNpuPacote.Conferencia.Ok) {
                    if (check.manifesto.pontaAPonta) 3 else 2
                } else 0
            } else 0
            // O hardware NAO e parametro: sai do MESMO `WhisperSelectors.effective`
            // que a tela usa, com o estado da tela (escolha salva, SoC admitido,
            // turbo do ULTIMO pedido que a tela fez e versao do pacote).
            val hardwareDaRegra = WhisperSelectors.effective(
                escolhaSalva, admitido, turboDoPedido, versaoPacote,
            )
            // Reabertura da tela: a selecao de modelo e de sessao (o `onCreate`
            // grava o turbo só quando ele esta completo em disco), entao relê o
            // rotulo depois de uma Activity NOVA, como um usuario que reabre.
            cenario.close()
            cenario2 = ActivityScenario.launch(WhisperActivity::class.java)
            manterTelaDaCelulaAcesa(cenario2)
            val hardwareTela = rotuloHardwareNaTela()
            linha(
                JSONObject().put("schema", SCHEMA).put("tipo", "decisao")
                    .put("escolha_salva", escolhaSalva?.name ?: "")
                    .put("soc_admitido", admitido)
                    .put("turbo_do_pedido", turboDoPedido)
                    .put("pacote_versao", versaoPacote)
                    .put("hardware_da_regra", hardwareDaRegra.name)
                    .put("rotulo_da_tela", hardwareTela ?: "")
                    .put("pid", android.os.Process.myPid()),
            )
            assertEquals("rótulo da tela x regra", hardwareDaRegra.label, hardwareTela)
            val hardware = hardwareDaRegra
            val pcm = WhisperOrtRoute.pcmDoArquivo(clipe)
            val hashEsperado = WhisperOrtPcm.sha256(WhisperOrtPcm.toInt16Le(pcm))
            val threads = WhisperOrtRoute.threadsEfetivos(ctx)
            val pronto = CountDownLatch(1)
            var job: WhisperOrtAdmission.Job? = null
            var resultado: WhisperOrtRoute.Result? = null
            cenario2.onActivity { activity ->
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
            runCatching { if (cenario2 != null) cenario2.close() else cenario.close() }
            runCatching { lock?.release() }
        }
    }
}
