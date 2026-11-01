package br.gov.sp.pcsp.launcher

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * ADENDO 21 §1 — Tiny e Base MULTILINGUES: download pelo FLUXO DO PRODUTO
 * (+ staging/activate), transcrição REAL de áudio curto aprovado, terminal
 * com modelo pedido=instalado=carregado. CPU + threads do seletor
 * compartilhado. Sem campanha de qualidade; sem pacote de outro modelo
 * tornando o teste falso-verde (o terminal `model` é conferido POR CÉLULA).
 *
 * Focais de retorno a small/turbo ficam nos unit (cache/fila); aqui o
 * produto encontra o pacote instalado e troca de modelo com o seletor.
 */
@RunWith(AndroidJUnit4::class)
class WhisperOrtTinyBaseDeviceTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun terminais(): Map<String, JSONObject> {
        val pasta = File(ctx.filesDir, "ort-route/terminais")
        val arquivos = pasta.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return emptyMap()
        return arquivos.mapNotNull { f ->
            runCatching { f.nameWithoutExtension to JSONObject(f.readText(Charsets.UTF_8)) }.getOrNull()
        }.toMap()
    }

    private fun aguardarTerminal(antes: Set<String>, timeoutMs: Long, modelo: String): JSONObject? {
        val fim = System.currentTimeMillis() + timeoutMs
        var ultimoStatus = "-"
        while (System.currentTimeMillis() < fim) {
            val novos = terminais().filterKeys { it !in antes }
            val alvo = novos.values.firstOrNull {
                it.optString("model") == modelo && !it.optBoolean("partial", true)
            }
            if (alvo != null) return alvo
            val mensagem = novos.values.firstOrNull { it.has("error") }
            if (mensagem != null) ultimoStatus = mensagem.optString("error")
            Thread.sleep(5_000)
        }
        RuntimeException("timeout aguardando $modelo; ultimo=$ultimoStatus").printStackTrace()
        return null
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

    /** Download + activate do pacote via fluxo do produto (reflection). */
    private fun baixarModelo(activity: Activity, uri: Uri, pedido: String, efetivo: String) {
        val m = WhisperActivity::class.java.getDeclaredMethod(
            "baixarModeloOrt", Uri::class.java, String::class.java, String::class.java,
        )
        m.isAccessible = true
        m.invoke(activity, uri, pedido, efetivo)
    }

    /**
     * Transcreve com o modelo pedido via fluxo de despacho do produto
     * (seletor→[modelo aplicado]→despacharOrt). O download, quando preciso,
     * lem o pacote para o staging e ativa.
     */
    @Test
    fun tinyDownloadETranscreve() = verificarModelos(
        listOf("tiny" to WhisperOrtModelCatalog.MODEL_TINY),
    )

    @Test
    fun baseDownloadETranscreve() = verificarModelos(
        listOf("base" to WhisperOrtModelCatalog.MODEL_BASE),
    )

    private fun verificarModelos(modelos: List<Pair<String, String>>) {
        // ADENDO 22 §1.4 — áudio com fala confirmada (30,016 s / 480256
        // amostras); d01.wav devolveu NO_SPEECH no tiny (não é prova de
        // transcrição real).
        val audio = File(ctx.filesDir, "a17-cwav-30016.wav")
        assumeTrue("fixture a17 (c.wav 30,016 s) ausente", audio.isFile && audio.length() > 900_000L)
        // ADENDO 21 §1 — rota ORT HABILITADA antes do download via produto
        // (SmallRealTest: sem isso a atividade pode recusar a ORT).
        WhisperOrtRoute.setEnabled(ctx, true)
        assumirRede()
        for ((pedido, efetivo) in modelos) {
            val antes = terminais().keys.toSet()
            ActivityScenario.launch(WhisperActivity::class.java).use { cenario ->
                cenario.onActivity { activity ->
                    injetarRetornoSeletor(activity, 6101, audio)
                    // ADENDO 22 §1 — diagnóstico por fase: log ANTES e DEPOIS do
                    // worker do download (a causa observada exige nome antes de
                    // atribuir a rede/diálogo/modelo).
                    android.util.Log.i("AD21TB", "fase=pre-baixar pedido=$pedido efetivo=$efetivo uri=${Uri.fromFile(audio)}")
                    baixarModelo(activity, Uri.fromFile(audio), pedido, efetivo)
                    android.util.Log.i("AD21TB", "fase=pos-invoke baixo registrado")
                    // ADENDO 22 §1 — o worker do download se comunica pelo
                    // diálogo; o TestRunner preciso esperar o worker COMEÇAR:
                    // o status do DownloadOwner é observável pelo campo.
                    val dl = WhisperActivity::class.java.getDeclaredField("ortDownload")
                        .apply { isAccessible = true }.get(activity)
                    android.util.Log.i("AD21TB", "fase=dl-owner-set dl!=null=${dl != null}")
                    if (dl == null) fail("baixarModeloOrt não criou o owner do download (worker não iniciou)")
                }
                // ADENDO 21 §1 — teto de 1800 s para 2 downloads (74+130 MB)
                // + instalação + transcrição REAL de cada modelo.
                val t = aguardarTerminal(antes, 1_800_000, efetivo)
                assertTrue("terminal de $efetivo não fechou (sem SUCCESS)", t != null)
                val terminal = t!!
                assertEquals("modelo no terminal", efetivo, terminal.optString("model"))
                assertEquals("provider", "CPU", terminal.optString("provider"))
                assertTrue("texto vazio no terminal de $efetivo", terminal.optInt("bytes", 0) > 0)
                assertTrue("outcome não suportado: ${terminal.optString("outcome")}",
                           terminal.optString("outcome") in listOf("SUCCESS"))
                org.junit.Assert.assertFalse(
                    "terminal PARTIAL não é prova",
                    terminal.optBoolean("partial", false),
                )
                android.util.Log.i(
                    "AD21", "terminal $efetivo :: ${terminal.toString().take(400)}"
                )
            }
        }
    }

    /** Assume rede HF alcançável pelo aparelho (download em andamento). */
    private fun assumirRede() {
        val cm = ctx.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
            as android.net.ConnectivityManager
        val redeAtiva = cm.activeNetwork != null && cm.activeNetworkInfo?.isConnected == true
        assumeTrue("sem rede no aparelho para o download", redeAtiva)
    }
}
