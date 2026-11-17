package br.gov.sp.pcsp.launcher

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * R102 §T01 — harness de aceite do build de PRODUTO (sem flavor lab).
 *
 * A `WhisperActivity` fica em primeiro plano e a medição chama a rota de produto
 * [WhisperOrtRoute] e o produtor [WhisperOrtTranscriptionProducer] indiretamente,
 * pelo mesmo despacho da tela. Cada pedido produz um
 * [WhisperNpuAceiteReceipt.Dados] com rota pedida/efetiva, versões, motivo de
 * fallback, `open_ms`, `total_ms`, SoC e os três instantes do cronômetro.
 *
 * ⚠️ Texto transcrito NUNCA vai para logcat: `Log` recebe só [resumo]. O JSON com
 * texto é escrito no arquivo apontado por `-e r102_saida` (padrão:
 * `filesDir/r102/resultado.jsonl`), que é o destino do EV.
 *
 * Uso (no PJA110, com o APK de produto e o androidTest instalados):
 *   adb shell am instrument -w -e r102_saida /sdcard/Android/data/<pkg>/files/r102/resultado.jsonl \
 *       -e r102_audio /sdcard/.../clipe.wav \
 *       br.gov.sp.pcsp.launcher.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class WhisperNpuR102AceiteTest {

    private val ctx: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val args
        get() = InstrumentationRegistry.getInstrumentation().arguments

    private val dispositivo: UiDevice
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    private val saida: File
        get() = File(args.getString("r102_saida")
            ?: File(ctx.filesDir, "r102/resultado.jsonl").absolutePath)

    private lateinit var cancelador: WhisperNpuAceiteReceipt.Cancelador

    @Before
    fun preparar() {
        // Cada ensaio começa sem a recusa persistida da oferta.
        ctx.getSharedPreferences("whisper", Context.MODE_PRIVATE)
            .edit().remove(WhisperNpuOferta.PREF_CHAVE).apply()
        saida.parentFile?.mkdirs()
        cancelador = WhisperNpuAceiteReceipt.canceladorPadrao()
    }

    @After
    fun fechar() {
        cancelador.fechar()
    }

    private fun gravar(dados: WhisperNpuAceiteReceipt.Dados) {
        saida.appendText(dados.json() + "\n")
        Log.i("R102-ACEITE", dados.resumo())
    }

    private fun soc(): String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        WhisperOrtHtpAdmissao.normalizarSoc(Build.SOC_MODEL)
    } else "desconhecido"

    private fun admitido(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val soc = WhisperOrtHtpAdmissao.socDe(Build.SOC_MODEL) ?: return false
        return soc.habilitado && WhisperOrtHtpAdmissao.fabricanteAdmitido(Build.SOC_MANUFACTURER)
    }

    /** C1 — a oferta automática aparece uma vez; recusada, não volta. */
    @Test
    fun c1_oferta_unica_e_entrada_fixa_reabre_o_dialogo() {
        assumeTrue("a célula de oferta exige o SoC admitido", admitido())
        ActivityScenario.launch(WhisperActivity::class.java).use { cenario ->
            val dialogo = dispositivo.wait(Until.findObject(By.textContains("Aceleração NPU")), 15_000L)
            assertNotNull("a oferta automática deve aparecer", dialogo)

            val agoraNao = dispositivo.wait(Until.findObject(By.text("Agora não")), 5_000L)
            assertNotNull("botão de recusa ausente", agoraNao)
            agoraNao!!.click()
            // A recusa é persistida: fechar e reabrir a tela não pode perguntar.
            cenario.close()

            ActivityScenario.launch(WhisperActivity::class.java).use { reaberta ->
                reaberta.onActivity { atividade ->
                    assertTrue(
                        "recusa persistida não pode reaparecer",
                        WhisperNpuOferta.Preferencias(atividade).recusado(),
                    )
                }
                assertTrue(
                    "a oferta automática NÃO pode voltar depois da recusa",
                    dispositivo.wait(Until.findObject(By.text("Agora não")), 4_000L) == null,
                )
                val entrada = dispositivo.wait(
                    Until.findObject(By.text("Ativar aceleração NPU")), 5_000L,
                )
                assertNotNull("a entrada fixa deve continuar na tela", entrada)
                entrada!!.click()
                assertNotNull(
                    "a entrada fixa tem de abrir o MESMO diálogo",
                    dispositivo.wait(Until.findObject(By.text("Agora não")), 5_000L),
                )
            }
        }
        gravar(
            WhisperNpuAceiteReceipt.Dados(
                celula = "C1", rotaPedida = "-", rotaEfetiva = "-",
                versaoNpu = WhisperOrtHtpRota.versaoPacote(),
                versaoQairt = QairtDependencyManager.PACKAGE_VERSION,
                motivoFallback = null, openMs = 0L, totalMs = 0L, soc = soc(),
            )
        )
    }

    /** C1/C2 — download pela ação da tela; mede o tempo até o pacote ficar pronto. */
    @Test
    fun c1c2_download_pela_acao_da_tela() {
        assumeTrue("o download exige o SoC admitido", admitido())
        val inicio = System.nanoTime() / 1_000_000L
        ActivityScenario.launch(WhisperActivity::class.java).use { cenario ->
            val oferta = dispositivo.wait(Until.findObject(By.textContains("Aceleração NPU")), 15_000L)
            assertNotNull("o diálogo de download deve aparecer uma vez", oferta)
            val baixar = dispositivo.wait(Until.findObject(By.text("Baixar")), 5_000L)
            assertNotNull("botão Baixar ausente", baixar)
            val tocado = System.nanoTime() / 1_000_000L
            baixar!!.click()
            val limite = tocado + 20 * 60_000L
            var pronto = false
            while (System.currentTimeMillis() < limite) {
                cenario.onActivity { atividade ->
                    pronto = atividade.findViewById<android.widget.TextView>(R.id.button_ativar_npu)
                        ?.text?.toString() == "NPU ativa"
                }
                if (pronto) break
                Thread.sleep(1_000L)
            }
            val fim = System.nanoTime() / 1_000_000L
            gravar(
                WhisperNpuAceiteReceipt.Dados(
                    celula = if (QairtDependencyManager.isInstalled(ctx)) "C2" else "C1",
                    rotaPedida = "download", rotaEfetiva = if (pronto) "pacote verificado" else null,
                    versaoNpu = WhisperOrtHtpRota.versaoPacote(),
                    versaoQairt = QairtDependencyManager.PACKAGE_VERSION,
                    motivoFallback = if (pronto) null else "download_nao_concluiu",
                    openMs = tocado - inicio, totalMs = fim - tocado, soc = soc(),
                )
            )
            assertTrue("o download da tela precisa terminar com pacote verificado", pronto)
        }
    }

    /** C4/C7 — pedido pela rota de produto, com relógio de cancelamento separado. */
    @Test
    fun c4c7_rota_efetiva_e_cancelamento_aos_3s() {
        val caminho = args.getString("r102_audio") ?: run {
            assumeTrue("C4/C7 exigem -e r102_audio", false)
            return
        }
        val artefato = File(caminho)
        assumeTrue("áudio inexistente: use -e r102_audio", artefato.isFile)
        val uri = android.net.Uri.fromFile(artefato)
        val rotaPedida = WhisperOrtHtpRota.rotaDoPedido()

        ActivityScenario.launch(WhisperActivity::class.java).use { cenario ->
            cenario.onActivity { atividade ->
                val terminal = CountDownLatch(1)
                val inicio = System.nanoTime() / 1_000_000L
                val programado = WhisperNpuAceiteReceipt.Timers(inicio + 3_000L)
                val real = java.util.concurrent.atomic.AtomicLong(0L)
                val fim = java.util.concurrent.atomic.AtomicLong(0L)
                var job: WhisperOrtAdmission.Job? = null
                var resultado: WhisperOrtRoute.Result? = null

                // O timer corre na PRÓPRIA thread; a inferência não a bloqueia.
                cancelador.programar(inicio, 3_000L) { _, agora ->
                    real.set(agora)
                    WhisperOrtRoute.cancelar(job)
                }
                job = WhisperOrtRoute.transcribe(atividade, uri, onResult = { r ->
                    resultado = r
                    fim.set(System.nanoTime() / 1_000_000L)
                    val efetivo = when (r) {
                        is WhisperOrtRoute.Result.Done -> r.hardwareLabel
                        is WhisperOrtRoute.Result.Cancelled -> r.hardwareLabel
                        else -> null
                    }
                    val open = (resultado as? WhisperOrtRoute.Result.Done)?.openMillis ?: 0L
                    gravar(
                        WhisperNpuAceiteReceipt.Dados(
                            celula = "C7",
                            rotaPedida = rotaPedida,
                            rotaEfetiva = efetivo,
                            versaoNpu = WhisperOrtHtpRota.versaoPacote(),
                            versaoQairt = QairtDependencyManager.PACKAGE_VERSION,
                            motivoFallback = WhisperOrtHtpRota.motivoRota(),
                            openMs = open,
                            totalMs = fim.get() - inicio,
                            soc = soc(),
                            timers = programado.copy(
                                realMs = real.get().takeIf { it > 0L },
                                terminalMs = fim.get(),
                            ),
                            texto = when (r) {
                                is WhisperOrtRoute.Result.Done -> r.text
                                is WhisperOrtRoute.Result.Cancelled -> r.text
                                else -> null
                            },
                        )
                    )
                    terminal.countDown()
                })
                assertTrue("terminal não chegou em 120 s", terminal.await(120, TimeUnit.SECONDS))
            }
        }
    }

    /** C12 — fluidez: gfxinfo e checagem de ANR durante a interação. */
    @Test
    fun c12_fluidez_sem_anr() {
        val pkg = ctx.packageName
        val gfx = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("dumpsys gfxinfo $pkg framestats")
        val anr = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("dumpsys activity processes | grep -i 'ANR'")
        // O bruto de gfxinfo vai para o EV pelo coletor de logcat; aqui
        // só a classificação binária entra no recibo.
        val comAnr = anr.use { it.bufferedReader().readText() }.contains("ProcessRecord")
        gravar(
            WhisperNpuAceiteReceipt.Dados(
                celula = "C12", rotaPedida = "ui", rotaEfetiva = "ui",
                versaoNpu = WhisperOrtHtpRota.versaoPacote(),
                versaoQairt = QairtDependencyManager.PACKAGE_VERSION,
                motivoFallback = if (comAnr) "ANR" else null,
                openMs = 0L, totalMs = 0L, soc = soc(),
            )
        )
        gfx.close()
        assertTrue("C12 reprova com ANR", !comAnr)
    }
}
