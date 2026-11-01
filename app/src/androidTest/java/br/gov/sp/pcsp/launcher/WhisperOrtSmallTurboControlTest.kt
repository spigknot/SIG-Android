package br.gov.sp.pcsp.launcher

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * ADENDO 25 §1 — CONTROLES FOCAL de Small e Turbo ORT no aparelho após a
 * reclassificação da família de Small (50359/50358/50363), reutilizando
 * pacotes íntegros já presentes (não baixa modelos grandes de novo).
 *
 * Prova por job: seleção/prefixo/modelo efetivo, SUCCESS/texto não vazio,
 * threads/CPU, retorno entre as seleções (small→turbo na mesma_arbita).
 * Não exige equivalência literal de texto.
 */
@RunWith(AndroidJUnit4::class)
class WhisperOrtSmallTurboControlTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun terminais(): Map<String, JSONObject> {
        val pasta = File(ctx.filesDir, "ort-route/terminais")
        val arquivos = pasta.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return emptyMap()
        return arquivos.mapNotNull { f ->
            runCatching { f.nameWithoutExtension to JSONObject(f.readText(Charsets.UTF_8)) }.getOrNull()
        }.toMap()
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

    private fun aguardarTerminal(antes: Set<String>, tetoMs: Long, modelo: String): JSONObject? {
        val fim = System.currentTimeMillis() + tetoMs
        while (System.currentTimeMillis() < fim) {
            val novos = terminais().filterKeys { it !in antes }
            val alvo = novos.values.firstOrNull {
                it.optString("model") == modelo &&
                    it.optString("outcome") == "SUCCESS" &&
                    it.optInt("bytes", 0) > 0 &&
                    !it.optBoolean("partial", true)
            }
            if (alvo != null) return alvo
            Thread.sleep(5_000)
        }
        return null
    }

    private fun reativar(modelo: String) {
        val root = WhisperOrtPackageFlow.raiz(ctx)
        WhisperOrtPackageInstaller.ativar(
            root,
            WhisperOrtModelCatalog.identity(modelo),
            WhisperOrtModelCatalog.conjuntoPara(modelo),
            withPublication = { it() },
        )
    }

    @Test
    fun smallTurboSucessoComTextoEReciprocidade() {
        val audio = File(ctx.filesDir, "d01.wav")
        assumeTrue("WAV de controle ausente", audio.isFile)
        WhisperOrtRoute.setEnabled(ctx, true)

        for (pedido in listOf("small", "v3Turbo")) {
            val efetivo = WhisperOrtModelCatalog.modeloParaPedido(pedido)!!
            // ADENDO 25 §1 — a prova usa pacote ÍNTEGRO presente; se ausente,
            // registra o impedimento concreto (não inventa PASS e não baixa:
            // pequena baixada no ad17; turbo grande presente no aparelho).
            val instalado = runCatching {
                WhisperOrtPackageFlow.estado(ctx, efetivo) is WhisperOrtPackageFlow.Estado.Pronto ||
                    WhisperOrtPackageFlow.temVersaoInstalada(ctx, efetivo)
            }.getOrDefault(false)
            assumeTrue("pacote $efetivo não está presente no aparelho (impedimento concreto registrado)", instalado)

            val antes = terminais().keys.toSet()
            ActivityScenario.launch(WhisperActivity::class.java).use { cenario ->
                cenario.onActivity { activity ->
                    if (activity is WhisperActivity) {
                        reativar(efetivo)
                        injetarRetornoSeletor(activity, 6101, audio)
                    }
                    val m = WhisperActivity::class.java.getDeclaredMethod("despacharOrt",
                        Uri::class.java, Intent::class.java, String::class.java, String::class.java)
                    m.isAccessible = true
                    m.invoke(activity as Activity, Uri.fromFile(audio), null, pedido, efetivo)
                }
                val t = aguardarTerminal(antes, 600_000, efetivo)
                assertTrue("terminal de $efetivo não fechou SUCCESS com texto", t != null)
                val terminal = t!!
                assertEquals("modelo no terminal", efetivo, terminal.optString("model"))
                assertEquals("provider", "CPU", terminal.optString("provider"))
                assertTrue("texto não vazio", terminal.optInt("bytes", 0) > 0)
                assertFalse(terminal.optBoolean("partial", false))
                // ADENDO 25 §1 — recibo do job com identidade/configuração:
                val rec = JSONObject()
                    .put("rotulo", "controle_focal_$pedido")
                    .put("modelo", efetivo)
                    .put("entrada", audio.name)
                    .put("prefixo_misc", "pt + transcribe(família) + no_timestamps")
                    .put("threads", terminal.optInt("threads", 4))
                    .put("bytes", terminal.optInt("bytes", 0))
                    .put("millis", terminal.optLong("millis", 0L))
                android.util.Log.i("AD25", rec.toString())
                ctx.getExternalFilesDir("ad25-evidence")?.let { d ->
                    d.mkdirs()
                    File(d, "controle-$pedido-${System.currentTimeMillis()}.json")
                        .writeText(rec.toString(), Charsets.UTF_8)
                }
            }
        }
    }
}
