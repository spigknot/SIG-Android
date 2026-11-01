package br.gov.sp.pcsp.launcher

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Adendo 17 §1 — small de ponta a ponta no aparelho (pesos reais).
 *
 * Pré-condição: pacote small pode estar ausente (a T1 baixa ~390 MB pelo
 * fluxo do produto). Turbo real já instalado é reutilizado, nunca baixado
 * de novo. Resultados via terminal JSON (campo `model`).
 */
@RunWith(AndroidJUnit4::class)
class WhisperOrtSmallRealTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val small = WhisperOrtModelCatalog.MODEL_SMALL

    // Adendo 17 §5 — finally nos testes: a preferência ORT é restaurada ao
    // valor anterior mesmo com falha; nenhum job fica abandonado (cada teste
    // aguarda o próprio terminal antes de sair).
    private var enabledAntes = true

    @Before
    fun preservarPreferenciaOrt() {
        enabledAntes = WhisperOrtRoute.isEnabled(ctx)
    }

    @After
    fun restaurarPreferenciaOrt() {
        WhisperOrtRoute.setEnabled(ctx, enabledAntes)
    }

    private fun terminais(): Map<String, JSONObject> {
        val pasta = File(ctx.filesDir, "ort-route/terminais")
        val arquivos = pasta.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return emptyMap()
        return arquivos.mapNotNull { f ->
            runCatching { f.nameWithoutExtension to JSONObject(f.readText(Charsets.UTF_8)) }.getOrNull()
        }.toMap()
    }

    private fun aguardar(antes: Set<String>, timeoutMs: Long, pred: (JSONObject) -> Boolean): JSONObject? {
        val limite = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < limite) {
            val novo = terminais().filterKeys { it !in antes }.values.firstOrNull(pred)
            if (novo != null) return novo
            Thread.sleep(5_000)
        }
        return null
    }

    private fun transcrever(entrada: File, modelo: String, timeoutMs: Long): JSONObject {
        val antes = terminais().keys.toSet()
        var job: WhisperOrtAdmission.Job? = null
        ActivityScenario.launch(WhisperActivity::class.java).use { cenario ->
            cenario.onActivity { activity ->
                job = WhisperOrtRoute.transcribe(
                    activity = activity as WhisperActivity,
                    uri = Uri.fromFile(entrada),
                    onResult = { },
                    modelo = modelo,
                )
            }
            assertNotNull("job deveria ser admitido ($modelo)", job)
            val t = aguardar(antes, timeoutMs) { it.optString("outcome") == "SUCCESS" }
            assertNotNull("deveria terminar SUCCESS ($modelo)", t)
            assertEquals(modelo, t!!.optString("model"))
            assertTrue("texto vazio ($modelo)", t.optInt("bytes", 0) > 0)
            return t
        }
    }

    private fun injetarRetornoSeletor(activity: Activity, request: Int, arquivo: File) {
        val dados = Intent().setData(Uri.fromFile(arquivo))
        val metodo = Activity::class.java.getDeclaredMethod(
            "onActivityResult",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Intent::class.java,
        )
        metodo.isAccessible = true
        metodo.invoke(activity, request, Activity.RESULT_OK, dados)
    }

    @Test
    fun smallBaixaETranscreveComCongelamento() {
        val d01 = File(ctx.filesDir, "d01.wav")
        assumeTrue("WAV de controle ausente", d01.isFile)
        WhisperOrtRoute.setEnabled(ctx, true)
        ActivityScenario.launch(WhisperActivity::class.java).use { cenario ->
            // Baixa pelo fluxo do produto com pedido congelado, TROCA a seleção
            // no meio do download e prova que o redispatch usa o efetivo (small).
            val antes = terminais().keys.toSet()
            cenario.onActivity { activity ->
                val tela = activity as WhisperActivity
                // O redispatch pós-download exige uriEscolhida == uri (fluxo do
                // produto: picker antes do download). Sem isso, nada redespacha.
                injetarRetornoSeletor(tela, 6101, d01)
                val m = WhisperActivity::class.java.getDeclaredMethod(
                    "baixarModeloOrt", Uri::class.java,
                    String::class.java, String::class.java,
                ).apply { isAccessible = true }
                m.invoke(tela, Uri.fromFile(d01), "small", small)
            }
            // Muda a seleção DURANTE o download: o pedido em voo é small.
            cenario.onActivity { activity ->
                val tela = activity as WhisperActivity
                WhisperActivity::class.java.getDeclaredField("selectedModel")
                    .apply { isAccessible = true }.set(tela, null)
            }
            // Observa a entrega (temporário adendo 17): status/job/download a
            // cada rodada, para distinguir download parado de redispatch parado.
            var t: JSONObject? = null
            val limite = System.currentTimeMillis() + 900_000
            while (System.currentTimeMillis() < limite && t == null) {
                Thread.sleep(10_000)
                cenario.onActivity { activity ->
                    val tela = activity as WhisperActivity
                    val st = (campoPrivado(tela, "status") as android.widget.TextView).text.toString()
                    val temJob = campoPrivado(tela, "jobOrtNaTela") != null
                    val temDl = campoPrivado(tela, "ortDownload") != null
                    Log.i("SMALL-REAL-17", "t1 status=[$st] job=$temJob dl=$temDl")
                }
                t = aguardarUma(antes)
            }
            assertNotNull("small deveria terminar SUCCESS com model=small", t)
            Log.i("SMALL-REAL-17", "small bytes=${t!!.optInt("bytes")} millis=${t.optLong("millis")}")
        }
    }

    private fun campoPrivado(activity: android.app.Activity, nome: String): Any? {
        val campo = WhisperActivity::class.java.getDeclaredField(nome)
        campo.isAccessible = true
        return campo.get(activity)
    }

    private fun aguardarUma(antes: Set<String>): JSONObject? {
        return terminais().filterKeys { it !in antes }.values.firstOrNull {
            it.optString("outcome") == "SUCCESS" && it.optString("model") == small
        }
    }

    @Test
    fun smallDespachoIsoladoTranscreve() {
        val d01 = File(ctx.filesDir, "d01.wav")
        assumeTrue("WAV de controle ausente", d01.isFile)
        // Auto-suficiente: reativa o small instalado (a classe compartilha o
        // aparelho e outro teste pode ter deixado o turbo ativo).
        val root = File(ctx.filesDir, "ort-model")
        val idSmall = WhisperOrtModelCatalog.identity(small)
        assumeTrue(
            "pacote small ausente",
            WhisperOrtPackageInstaller.instalado(root, idSmall, WhisperOrtModelCatalog.conjuntoPara(small)),
        )
        val atv = WhisperOrtPackageInstaller.ativar(
            root, idSmall, WhisperOrtModelCatalog.conjuntoPara(small),
        )
        assumeTrue("reativação do small recusada: $atv", atv is WhisperOrtPackageInstaller.Outcome.Ready)
        val antes = terminais().keys.toSet()
        ActivityScenario.launch(WhisperActivity::class.java).use { cenario ->
            cenario.onActivity { activity ->
                val tela = activity as WhisperActivity
                injetarRetornoSeletor(tela, 6101, d01)
                val m = WhisperActivity::class.java.getDeclaredMethod(
                    "despacharOrt", android.net.Uri::class.java, Intent::class.java,
                    String::class.java, String::class.java,
                ).apply { isAccessible = true }
                m.invoke(tela, android.net.Uri.fromFile(d01), null, small, null)
            }
            var t: JSONObject? = null
            val limite = System.currentTimeMillis() + 420_000
            while (System.currentTimeMillis() < limite && t == null) {
                Thread.sleep(10_000)
                cenario.onActivity { activity ->
                    val tela = activity as WhisperActivity
                    val st = (campoPrivado(tela, "status") as android.widget.TextView).text.toString()
                    val temJob = campoPrivado(tela, "jobOrtNaTela") != null
                    Log.i("SMALL-REAL-17", "isolado status=[$st] job=$temJob")
                }
                t = aguardarUma(antes)
            }
            assertNotNull("despacho isolado small deveria terminar SUCCESS", t)
            assertEquals(small, t!!.optString("model"))
            Log.i("SMALL-REAL-17", "isolado bytes=${t.optInt("bytes")}")
        }
    }

    @Test
    fun trocaTurboSmallCacheQuente() {
        val d01 = File(ctx.filesDir, "d01.wav")
        assumeTrue("WAV de controle ausente", d01.isFile)
        val root = File(ctx.filesDir, "ort-model")
        val idTurbo = WhisperOrtModelCatalog.identity(WhisperOrtModelCatalog.MODEL)
        val idSmall = WhisperOrtModelCatalog.identity(small)
        assumeTrue(
            "pacote small ausente (rode smallBaixaETranscreveComCongelamento antes)",
            WhisperOrtPackageInstaller.instalado(root, idSmall, WhisperOrtModelCatalog.conjuntoPara(small)),
        )
        assumeTrue(
            "pacote turbo ausente",
            WhisperOrtPackageInstaller.instalado(root, idTurbo, WhisperOrtModelCatalog.conjuntoPara(WhisperOrtModelCatalog.MODEL)),
        )
        WhisperOrtRoute.setEnabled(ctx, true)
        // Auto-suficiente e independente de ordem: reativa cada versão
        // (sem download) antes da sua perna — a classe compartilha o
        // aparelho e só uma versão fica ativa por vez.
        val atvTurbo = WhisperOrtPackageInstaller.ativar(
            root, idTurbo, WhisperOrtModelCatalog.conjuntoPara(WhisperOrtModelCatalog.MODEL),
        )
        assumeTrue("reativação do turbo recusada: $atvTurbo", atvTurbo is WhisperOrtPackageInstaller.Outcome.Ready)
        val tTurbo = transcrever(d01, WhisperOrtModelCatalog.MODEL, 600_000)
        val atvSmall = WhisperOrtPackageInstaller.ativar(
            root, idSmall, WhisperOrtModelCatalog.conjuntoPara(small),
        )
        assumeTrue("reativação do small recusada: $atvSmall", atvSmall is WhisperOrtPackageInstaller.Outcome.Ready)
        val tSmall = transcrever(d01, small, 600_000)
        assertEquals(WhisperOrtModelCatalog.MODEL, tTurbo.optString("model"))
        assertEquals(small, tSmall.optString("model"))
        Log.i(
            "SMALL-REAL-17",
            "turbo millis=${tTurbo.optLong("millis")} small millis=${tSmall.optLong("millis")}",
        )
    }

    @Test
    fun overrideTurboParaEntradaSmallViajaComAEntrada() {
        val d01 = File(ctx.filesDir, "d01.wav")
        assumeTrue("WAV de controle ausente", d01.isFile)
        val root = File(ctx.filesDir, "ort-model")
        val idTurbo = WhisperOrtModelCatalog.identity(WhisperOrtModelCatalog.MODEL)
        assumeTrue(
            "pacote turbo ausente",
            WhisperOrtPackageInstaller.instalado(root, idTurbo, WhisperOrtModelCatalog.conjuntoPara(WhisperOrtModelCatalog.MODEL)),
        )
        // O consentido (turbo) precisa estar ATIVO: a classe compartilha o
        // aparelho e o teste anterior pode ter deixado o small ativo.
        val atv = WhisperOrtPackageInstaller.ativar(
            root, idTurbo, WhisperOrtModelCatalog.conjuntoPara(WhisperOrtModelCatalog.MODEL),
        )
        assumeTrue("reativação do turbo recusada: $atv", atv is WhisperOrtPackageInstaller.Outcome.Ready)
        WhisperOrtRoute.setEnabled(ctx, true)
        val antes = terminais().keys.toSet()
        ActivityScenario.launch(WhisperActivity::class.java).use { cenario ->
            cenario.onActivity { activity ->
                val tela = activity as WhisperActivity
                // Consentimento congelado da entrada: pedido small, efetivo turbo.
                val metodo = WhisperActivity::class.java.getDeclaredMethod(
                    "despacharOrt", Uri::class.java, Intent::class.java,
                    String::class.java, String::class.java,
                ).apply { isAccessible = true }
                metodo.invoke(tela, Uri.fromFile(d01), null, "small", WhisperOrtModelCatalog.MODEL)
            }
            val t = aguardar(antes, 600_000) { it.optString("outcome") == "SUCCESS" }
            assertNotNull("override deveria terminar SUCCESS", t)
            assertEquals(
                "consentimento turbo-para-small deve transcrever turbo",
                WhisperOrtModelCatalog.MODEL, t!!.optString("model"),
            )
        }
    }
}
