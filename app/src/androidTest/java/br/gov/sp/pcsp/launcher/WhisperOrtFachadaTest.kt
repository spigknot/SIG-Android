package br.gov.sp.pcsp.launcher

import android.app.Activity
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Adendo 15 §1-§2 — fachada da ferramenta REAL (WhisperActivity, variante de lab).
 *
 * Usa `onActivityResult` para o retorno do seletor e cliques reais nos botões;
 * dirigir o seletor do sistema com UiAutomator é complementar e não bloqueia
 * estas provas. O backend de sessão é controlado ([cacheParaTeste]) — o que se
 * prova é seleção→despacho→resultado→fallback→reentrega, não a numerologia ORT.
 */
@RunWith(AndroidJUnit4::class)
class WhisperOrtFachadaTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val ts0 = WhisperOrtSegments.timestampBegin(WhisperOrtPrompt.NO_TIMESTAMPS)
    private fun ts(seg: Double): Int = ts0 + Math.round(seg / 0.02).toInt()

    /** Backend roteirizado (argmax do roteiro), com trava opcional no `encode`. */
    private inner class BackendRoteirizado(
        private val roteiros: List<List<Int>>,
        private val travaEncode: CountDownLatch? = null,
    ) : WhisperOrtBackend {
        val chamadasEncode = AtomicInteger(0)
        private var janela = -1
        private var indice = 0
        override fun encode(logMel: FloatArray): FloatArray {
            chamadasEncode.incrementAndGet()
            travaEncode?.await(120, TimeUnit.SECONDS)
            return FloatArray(1_500 * 1_280)
        }
        override fun start(hidden: FloatArray, prompt: IntArray): FloatArray {
            janela++
            indice = 0
            return logits(indice)
        }
        override fun step(token: Int): FloatArray {
            indice++
            return logits(indice)
        }
        override fun close() = Unit
        private fun logits(posicao: Int): FloatArray {
            val v = FloatArray(51_866) { -1f }
            val roteiro = roteiros.getOrNull(janela) ?: emptyList()
            v[roteiro.getOrNull(posicao) ?: WhisperOrtPrompt.END_OF_TEXT] = 5f
            return v
        }
    }

    private fun roteiroFechado() = listOf(
        listOf(ts0, 65, ts(2.0), ts(2.0), 66, ts(4.0), ts(4.0)),
    )

    private fun instalarCache(backend: BackendRoteirizado) {
        WhisperOrtRoute.cacheParaTeste = WhisperOrtSessionCache(
            factory = { _, _, _, _ -> backend },
            open = { },
        )
    }

    @After
    fun limparCache() {
        WhisperOrtRoute.cacheParaTeste = null
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

    private fun campoPrivado(activity: Activity, nome: String): Any? {
        val campo = WhisperActivity::class.java.getDeclaredField(nome)
        campo.isAccessible = true
        return campo.get(activity)
    }

    private fun transcreverSelecionado(activity: Activity) {
        val metodo = WhisperActivity::class.java.getDeclaredMethod("transcribeSelectedMedia")
        metodo.isAccessible = true
        metodo.invoke(activity)
    }

    private fun despachar(activity: Activity, uri: Uri, dados: Intent?, modeloPedido: String? = null) {
        // 4º parâmetro (override consentido) default: a chamada de produção sem
        // consentimento passa null — o gate resolve o pedido.
        val metodo = WhisperActivity::class.java.getDeclaredMethod(
            "despacharOrt", Uri::class.java, Intent::class.java, String::class.java, String::class.java,
        )
        metodo.isAccessible = true
        metodo.invoke(activity, uri, dados, modeloPedido, null)
    }

    private fun terminais(): Map<String, JSONObject> {
        val pasta = File(ctx.filesDir, "ort-route/terminais")
        val arquivos = pasta.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return emptyMap()
        return arquivos.mapNotNull { f ->
            runCatching { f.nameWithoutExtension to JSONObject(f.readText(Charsets.UTF_8)) }.getOrNull()
        }.toMap()
    }

    @Test
    fun selecaoBsobreIntentA_despachaB() {
        val audioB = File(ctx.filesDir, "d01.wav")
        assumeTrue("WAV de controle ausente", audioB.isFile)
        WhisperOrtRoute.setEnabled(ctx, true)
        instalarCache(BackendRoteirizado(roteiroFechado()))
        val antes = terminais().keys.toSet()

        // A precedência B-sobre-A vive no seletor puro (WhisperOrtSelecaoTest, JVM);
        // aqui o picker normal devolve B e o despacho usa a seleção da tela.
        ActivityScenario.launch(WhisperActivity::class.java).use { cenario ->
            cenario.onActivity { activity ->
                // Picker normal devolve B: a seleção da tela vence o intent A.
                injetarRetornoSeletor(activity as Activity, 6101, audioB)
                val escolhida = campoPrivado(activity, "uriEscolhida") as Uri?
                assertEquals(Uri.fromFile(audioB).toString(), escolhida.toString())
                transcreverSelecionado(activity)
            }
            // O despacho ORT registra motor + entrada efetiva.
            var despacho = ""
            val limite = System.currentTimeMillis() + 30_000
            while (System.currentTimeMillis() < limite) {
                cenario.onActivity { activity ->
                    despacho = (activity as WhisperActivity).ultimoDespacho
                }
                if (despacho == "ORT:${Uri.fromFile(audioB)}") break
                Thread.sleep(500)
            }
            assertEquals("o despacho deveria ser ORT com o arquivo B", "ORT:${Uri.fromFile(audioB)}", despacho)
            // E o job termina com texto (caminho real até o terminal).
            var sucesso = false
            val limite2 = System.currentTimeMillis() + 180_000
            while (System.currentTimeMillis() < limite2) {
                val novos = terminais().filterKeys { it !in antes }
                val ok = novos.values.any { it.optString("outcome") == "SUCCESS" && it.optInt("bytes") > 0 }
                if (ok) {
                    sucesso = true
                    break
                }
                Thread.sleep(2_000)
            }
            assertTrue("o job de B deveria terminar SUCCESS com texto", sucesso)
        }
    }

    @Test
    fun recusaDoSegundoNaoApagaODono() {
        val audioB = File(ctx.filesDir, "d01.wav")
        assumeTrue("WAV de controle ausente", audioB.isFile)
        WhisperOrtRoute.setEnabled(ctx, true)
        val trava = CountDownLatch(1)
        val backend = BackendRoteirizado(roteiroFechado(), trava)
        instalarCache(backend)
        val antes = terminais().keys.toSet()

        ActivityScenario.launch(WhisperActivity::class.java).use { cenario ->
            cenario.onActivity { activity ->
                injetarRetornoSeletor(activity as Activity, 6101, audioB)
                transcreverSelecionado(activity)
            }
            // Espera o compute do primeiro começar (encode observado).
            val limiteEncode = System.currentTimeMillis() + 120_000
            while (backend.chamadasEncode.get() == 0 && System.currentTimeMillis() < limiteEncode) {
                Thread.sleep(500)
            }
            assertTrue("o compute não começou", backend.chamadasEncode.get() > 0)
            // Segundo pedido com recurso ativo: recusado na porta, sem terminal e
            // sem apagar a identidade do que está rodando.
            cenario.onActivity { activity ->
                transcreverSelecionado(activity as Activity)
            }
            Thread.sleep(3_000)
            var despacho = ""
            cenario.onActivity { activity ->
                despacho = (activity as WhisperActivity).ultimoDespacho
            }
            assertEquals("o despacho continua sendo o do job em voo", "ORT:${Uri.fromFile(audioB)}", despacho)
            // Libera: o primeiro termina SUCCESS; o segundo não deixou rastro.
            trava.countDown()
            var sucesso = false
            val limite = System.currentTimeMillis() + 180_000
            while (System.currentTimeMillis() < limite) {
                val novos = terminais().filterKeys { it !in antes }
                if (novos.values.any { it.optString("outcome") == "SUCCESS" }) {
                    sucesso = true
                    break
                }
                Thread.sleep(2_000)
            }
            assertTrue("o job deveria terminar SUCCESS", sucesso)
            assertEquals(
                "recusa não publica terminal",
                1, terminais().filterKeys { it !in antes }.size,
            )
        }
    }

    @Test
    fun fallback_aceitarVaiParaCppERecusarMantem() {
        val bogus = File(ctx.filesDir, "nao-existe-fallback.wav")
        ActivityScenario.launch(WhisperActivity::class.java).use { cenario ->
            cenario.onActivity { activity ->
                val tela = activity as WhisperActivity
                // Entrada inválida -> recusa por capacidade ("midia:") -> diálogo.
                val dados = Intent().setData(Uri.fromFile(bogus))
                despachar(activity, Uri.fromFile(bogus), dados)
            }
            // Espera o diálogo de fallback.
            var dialogo: android.app.AlertDialog? = null
            val limite = System.currentTimeMillis() + 60_000
            while (System.currentTimeMillis() < limite) {
                cenario.onActivity { activity ->
                    dialogo = (activity as WhisperActivity).dialogoFallbackEmExibicao
                }
                if (dialogo != null) break
                Thread.sleep(500)
            }
            assertTrue("o diálogo de fallback deveria aparecer", dialogo != null)
            // Aceitar: pedido CPP desta entrada, preferência geral preservada.
            val prefAntes = WhisperOrtRoute.isEnabled(ctx)
            cenario.onActivity { activity ->
                dialogo!!.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            }
            Thread.sleep(2_000)
            var despacho = ""
            var prefDepois = false
            cenario.onActivity { activity ->
                val tela = activity as WhisperActivity
                despacho = tela.ultimoDespacho
                prefDepois = WhisperOrtRoute.isEnabled(ctx)
            }
            assertTrue("aceitar deveria despachar CPP (veio '$despacho')", despacho.startsWith("CPP:"))
            assertEquals("a preferência geral não pode mudar", prefAntes, prefDepois)
            // Recusar: nada muda.
            cenario.onActivity { activity ->
                val dados = Intent().setData(Uri.fromFile(bogus))
                despachar(activity as Activity, Uri.fromFile(bogus), dados)
            }
            val despachoAntesDaRecusa = despacho
            var dialogo2: android.app.AlertDialog? = null
            val limite2 = System.currentTimeMillis() + 60_000
            while (System.currentTimeMillis() < limite2) {
                cenario.onActivity { activity ->
                    dialogo2 = (activity as WhisperActivity).dialogoFallbackEmExibicao
                }
                if (dialogo2 != null) break
                Thread.sleep(500)
            }
            assertTrue("o diálogo deveria reaparecer", dialogo2 != null)
            cenario.onActivity {
                dialogo2!!.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
            }
            Thread.sleep(1_000)
            cenario.onActivity { activity ->
                despacho = (activity as WhisperActivity).ultimoDespacho
            }
            // O segundo despacho (sem override, ORT ligado) vai para ORT; o ponto
            // é que RECUSAR o diálogo não pode rotear para CPP.
            assertEquals("ORT:${Uri.fromFile(bogus)}", despacho)
        }
    }

    @Test
    fun destroyEmVoo_comBackendControlado_pendenteERecupera() {
        val audio = File(ctx.filesDir, "d01.wav")
        assumeTrue("WAV de controle ausente", audio.isFile)
        assumeTrue(
            "pacote ORT ausente",
            WhisperOrtPackageFlow.estado(ctx) is WhisperOrtPackageFlow.Estado.Pronto,
        )
        WhisperOrtRoute.setEnabled(ctx, true)
        val trava = CountDownLatch(1)
        val backend = BackendRoteirizado(roteiroFechado(), trava)
        instalarCache(backend)
        val antes = terminais().keys.toSet()

        // Dispacha com a tela viva e destrói no meio do compute.
        val cenario = ActivityScenario.launch(WhisperActivity::class.java)
        cenario.onActivity { activity ->
            val tela = activity as WhisperActivity
            // Adendo 17 §1 — seleção EXPLÍCITA do v3Turbo: o default do
            // produto é o primeiro GGML completo ("tiny") e pedido
            // desconhecido não abre job ORT (gate); sem isto o teste
            // depende do estado de modelos do aparelho.
            val modelos = WhisperActivity::class.java.getDeclaredMethod("officialModels")
                .apply { isAccessible = true }.invoke(tela) as List<*>
            val turbo = modelos.firstOrNull { m ->
                m!!.javaClass.getDeclaredField("label").apply { isAccessible = true }.get(m) == "v3Turbo"
            }
            assertTrue("v3Turbo deveria existir no seletor", turbo != null)
            WhisperActivity::class.java.getDeclaredField("selectedModel")
                .apply { isAccessible = true }.set(tela, turbo)
            injetarRetornoSeletor(activity as Activity, 6107, audio)
        }
        val limiteEncode = System.currentTimeMillis() + 120_000
        while (backend.chamadasEncode.get() == 0 && System.currentTimeMillis() < limiteEncode) {
            Thread.sleep(500)
        }
        assertTrue("o compute não começou", backend.chamadasEncode.get() > 0)
        cenario.close()
        // Libera o backend: o job termina SEM tela viva.
        trava.countDown()
        var novo: Pair<String, JSONObject>? = null
        val limiteTermino = System.currentTimeMillis() + 180_000
        while (System.currentTimeMillis() < limiteTermino) {
            novo = terminais().filterKeys { it !in antes }.entries.firstOrNull()?.toPair()
            if (novo != null) break
            Thread.sleep(2_000)
        }
        assertTrue("nenhum terminal do job em voo", novo != null)
        val (nome, json) = novo!!
        assertEquals("SUCCESS", json.optString("outcome"))
        assertTrue("texto vazio", json.optInt("bytes") > 0)
        // Sem confirmação de tela morta: segue pendente.
        assertFalse(
            "tela destruída não pode confirmar entrega",
            File(ctx.filesDir, "ort-route/terminais/$nome.ack").isFile,
        )
        // Recria: o resultado aparece e SÓ ENTÃO é confirmado.
        ActivityScenario.launch(WhisperActivity::class.java).use { c2 ->
            c2.onActivity { activity ->
                val texto = activity.findViewById<android.widget.TextView>(
                    br.gov.sp.pcsp.launcher.R.id.live_transcript_text,
                )?.text?.toString().orEmpty()
                assertTrue("resultado não reentregue (veio '$texto')", texto.isNotBlank())
            }
        }
        assertTrue(
            "após exibir, a entrega deveria ser confirmada",
            File(ctx.filesDir, "ort-route/terminais/$nome.ack").isFile,
        )
    }

    /**
     * Adendo 17 §1 — pedido `small` com ORT ligado e pacote small ausente:
     * abre o diálogo de download do SMALL (nunca o turbo silenciosamente).
     * Exercita o callback real do picker ORT (6107) com a seleção small na
     * tela. Nada é baixado nem transcrito sem clique: nenhum job, nenhum
     * download, nenhum terminal.
     */
    @Test
    fun pedidoSmallSemPacote_abreDownloadDoSmall() {
        val audio = File(ctx.filesDir, "d01.wav")
        assumeTrue("WAV de controle ausente", audio.isFile)
        WhisperOrtRoute.setEnabled(ctx, true)
        instalarCache(BackendRoteirizado(roteiroFechado()))
        val antes = terminais().keys.toSet()

        ActivityScenario.launch(WhisperActivity::class.java).use { cenario ->
            cenario.onActivity { activity ->
                val tela = activity as WhisperActivity
                val modelos = WhisperActivity::class.java.getDeclaredMethod("officialModels")
                    .apply { isAccessible = true }.invoke(tela) as List<*>
                val small = modelos.firstOrNull { modelo ->
                    modelo!!.javaClass.getDeclaredField("label").apply { isAccessible = true }.get(modelo) == "small"
                }
                assertTrue("small deve existir no seletor", small != null)
                WhisperActivity::class.java.getDeclaredField("selectedModel")
                    .apply { isAccessible = true }.set(tela, small)
                val request = WhisperActivity::class.java.getDeclaredField("REQUEST_PICK_MEDIA_ORT")
                    .apply { isAccessible = true }.getInt(null)
                // Exercita o callback real do picker ORT: transmite a seleção
                // small; o gate resolve para o pacote small (não turbo).
                injetarRetornoSeletor(tela, request, audio)
            }
            cenario.onActivity { activity ->
                val tela = activity as WhisperActivity
                assertEquals(
                    "small sem pacote não pode admitir job ORT",
                    null, campoPrivado(tela, "jobOrtNaTela"),
                )
                assertEquals(
                    "small sem clique não pode iniciar download (de nenhum modelo)",
                    null, campoPrivado(tela, "ortDownload"),
                )
                assertEquals(
                    "nenhum despacho efetivo com pedido pendente de download",
                    "NENHUM", tela.ultimoDespacho,
                )
            }
        }
        assertEquals(
            "pedido pendente de download não publica terminal",
            0, terminais().filterKeys { it !in antes }.size,
        )
    }

    /**
     * Adendo 16 §2 — pedido `v3Turbo` segue o fluxo normal até SUCCESS:
     * seleção turbo → pacote/inferência turbo, sem regressão do caminho.
     */
    @Test
    fun pedidoTurboSegueFluxoNormal() {
        val audio = File(ctx.filesDir, "d01.wav")
        assumeTrue("WAV de controle ausente", audio.isFile)
        assumeTrue(
            "pacote ORT ausente",
            WhisperOrtPackageFlow.estado(ctx) is WhisperOrtPackageFlow.Estado.Pronto,
        )
        WhisperOrtRoute.setEnabled(ctx, true)
        instalarCache(BackendRoteirizado(roteiroFechado()))
        val antes = terminais().keys.toSet()

        ActivityScenario.launch(WhisperActivity::class.java).use { cenario ->
            cenario.onActivity { activity ->
                despachar(
                    activity as Activity,
                    Uri.fromFile(audio),
                    Intent().setData(Uri.fromFile(audio)),
                    "v3Turbo",
                )
            }
            var sucesso = false
            var threadsTerminal = -1
            val limite = System.currentTimeMillis() + 180_000
            while (System.currentTimeMillis() < limite) {
                val novos = terminais().filterKeys { it !in antes }
                val ok = novos.values.firstOrNull { it.optString("outcome") == "SUCCESS" && it.optInt("bytes") > 0 }
                if (ok != null) {
                    sucesso = true
                    threadsTerminal = ok.optInt("threads", -1)
                    break
                }
                Thread.sleep(2_000)
            }
            assertTrue("pedido turbo deveria terminar SUCCESS com texto", sucesso)
            // Adendo 16 §3 — o terminal carrega as pedidas do seletor compartilhado.
            assertEquals(
                "terminal deveria registrar as threads pedidas",
                WhisperOrtRoute.threadsEfetivos(ctx), threadsTerminal,
            )
        }
    }
}
