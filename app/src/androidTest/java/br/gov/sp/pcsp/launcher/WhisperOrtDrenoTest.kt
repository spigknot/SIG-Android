package br.gov.sp.pcsp.launcher

import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Adendo 15 §1 — dreno físico e identidade na fonte final (main).
 *
 * A main conserva a posse no worker e aguarda o fim físico; este teste prova
 * pela FACHADA da rota, com motor FFmpeg e backend ORT controlados:
 * - cancelamento pedido DEPOIS do diagnóstico chega à SESSÃO dona;
 * - término cancelado preserva CANCELLED (nunca vira ERROR/SUCCESS);
 * - sucesso tardio prossegue com o arquivo estabilizado;
 * - cancelar(id) só vale para o job admitido (janela de admissão).
 *
 * Sem `Thread.sleep` de adivinhação: travas e sondagem com prazo.
 */
@RunWith(AndroidJUnit4::class)
class WhisperOrtDrenoTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val ts0 = WhisperOrtSegments.timestampBegin(WhisperOrtPrompt.NO_TIMESTAMPS)
    private fun ts(seg: Double): Int = ts0 + Math.round(seg / 0.02).toInt()

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

    /** Motor FFmpeg controlado: nunca usa o FFmpeg global; registra o alvo. */
    private class MotorControlado(
        private val concluirNoCancel: Boolean = true,
        private val completarOk: (() -> Pair<Boolean, ByteArray?>)? = null,
    ) : WhisperOrtMediaConverter.Motor {
        class Viva(override val id: Long = 4242L) : WhisperOrtMediaConverter.Sessao
        var destino: File? = null
        var aoTerminar: ((WhisperOrtMediaConverter.Final) -> Unit)? = null
        val canceladas = mutableListOf<Long>()
        val doisCancelamentos = CountDownLatch(2)
        val iniciadas = AtomicInteger(0)
        override fun iniciar(
            argumentos: Array<String>,
            aoTerminar: (WhisperOrtMediaConverter.Final) -> Unit,
        ): WhisperOrtMediaConverter.Sessao {
            iniciadas.incrementAndGet()
            destino = argumentos.lastOrNull()?.let { File(it) }
            this.aoTerminar = aoTerminar
            return Viva()
        }
        override fun cancelar(sessao: WhisperOrtMediaConverter.Sessao) {
            synchronized(canceladas) { canceladas.add(sessao.id) }
            doisCancelamentos.countDown()
            if (concluirNoCancel) {
                aoTerminar?.invoke(WhisperOrtMediaConverter.Final(false, true, "cancelado-teste"))
            }
        }
        /** Completa de fora (término tardio observado pelo dreno). */
        fun terminarTarde(ok: Boolean, cargaWav: ByteArray? = null) {
            if (ok && cargaWav != null) destino?.writeBytes(cargaWav)
            aoTerminar?.invoke(WhisperOrtMediaConverter.Final(ok, false, "tardio-teste"))
        }
    }

    @After
    fun limparSeams() {
        WhisperOrtRoute.cacheParaTeste = null
        WhisperOrtRoute.motorConversaoParaTeste = null
        WhisperOrtRoute.timeoutConversaoMsParaTeste = null
    }

    private fun mp3Falso(): File {
        // Conteúdo inválido como áudio (>44 B para passar do guarda de tamanho
        // e cair na CONVERSÃO, não em "arquivo pequeno demais"), com motor
        // controlado — o FFmpeg real nunca é chamado.
        val f = File(ctx.filesDir, "dreno-falso.mp3")
        if (!f.isFile || f.length() < 1024) {
            f.writeBytes(ByteArray(1024) { (it * 7).toByte() })
        }
        return f
    }

    private fun terminais(): Map<String, JSONObject> {
        val pasta = File(ctx.filesDir, "ort-route/terminais")
        val arquivos = pasta.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return emptyMap()
        return arquivos.mapNotNull { f ->
            runCatching { f.nameWithoutExtension to JSONObject(f.readText(Charsets.UTF_8)) }.getOrNull()
        }.toMap()
    }

    private fun aguardarTerminal(
        antes: Set<String>,
        limiteMs: Long,
        aceita: (JSONObject) -> Boolean,
    ): JSONObject? {
        val limite = System.currentTimeMillis() + limiteMs
        while (System.currentTimeMillis() < limite) {
            val novo = terminais().filterKeys { it !in antes }.values.firstOrNull { aceita(it) }
            if (novo != null) return novo
            Thread.sleep(1_000)
        }
        return null
    }

    @Test
    fun cancelTardioNoDreno_chegaASessao_sucessorLiberado() {
        val d01 = File(ctx.filesDir, "d01.wav")
        assumeTrue("WAV de controle ausente", d01.isFile)
        WhisperOrtRoute.setEnabled(ctx, true)
        // Motor que ignora o cancel (sem callback): força o diagnóstico SessaoAtiva.
        val motor = MotorControlado(concluirNoCancel = false)
        WhisperOrtRoute.motorConversaoParaTeste = motor
        WhisperOrtRoute.timeoutConversaoMsParaTeste = 2_000L
        WhisperOrtRoute.cacheParaTeste = WhisperOrtSessionCache(
            factory = { _, _, _, _ -> BackendRoteirizado(roteiroFechado()) },
            open = { },
        )
        val antes = terminais().keys.toSet()
        val noDreno = CountDownLatch(1)

        ActivityScenario.launch(WhisperActivity::class.java).use { cenario ->
            val job1 = arrayOfNulls<WhisperOrtAdmission.Job>(1)
            cenario.onActivity { activity ->
                job1[0] = (activity as WhisperActivity).let {
                    WhisperOrtRoute.transcribe(
                        activity = activity,
                        uri = Uri.fromFile(mp3Falso()),
                        onProgresso = { if (it.startsWith("aguardando término da conversão")) noDreno.countDown() },
                        onResult = { },
                    )
                }
            }
            assertNotNull("primeiro job deveria ser admitido", job1[0])
            assertTrue("o worker deve alcançar o dreno", noDreno.await(30, TimeUnit.SECONDS))
            assertEquals(1, motor.iniciadas.get())
            // A primeira chamada pertence ao timeout, não ao pedido do usuário.
            assertEquals(listOf(4242L), synchronized(motor.canceladas) { motor.canceladas.toList() })
            // Cancelamento TARDIO: tem que chegar à sessão dona.
            assertTrue(WhisperOrtRoute.cancelar(job1[0]!!))
            assertTrue("cancel do usuário deve gerar nova chamada ao motor", motor.doisCancelamentos.await(10, TimeUnit.SECONDS))
            assertEquals("timeout e pedido tardio atingem a mesma sessão", listOf(4242L, 4242L), synchronized(motor.canceladas) { motor.canceladas.toList() })
            // O término físico cancelado encerra: CANCELLED preservado.
            motor.terminarTarde(ok = false)
            val t1 = aguardarTerminal(antes, 60_000) { true }
            assertNotNull("terminal do job cancelado", t1)
            assertEquals("CANCELLED", t1!!.optString("outcome"))
            // Sucessor liberado: WAV direto + backend roteirizado => SUCCESS.
            val antes2 = terminais().keys.toSet()
            cenario.onActivity { activity ->
                val job2 = WhisperOrtRoute.transcribe(
                    activity = activity as WhisperActivity,
                    uri = Uri.fromFile(d01),
                    onResult = { },
                )
                assertNotNull("sucessor deveria ser admitido", job2)
            }
            val t2 = aguardarTerminal(antes2, 180_000) {
                it.optString("outcome") == "SUCCESS" && it.optInt("bytes") > 0
            }
            assertNotNull("sucessor deveria terminar SUCCESS", t2)
        }
    }

    @Test
    fun sucessoTardio_prossegueComArquivoEstavel() {
        val d01 = File(ctx.filesDir, "d01.wav")
        assumeTrue("WAV de controle ausente", d01.isFile)
        WhisperOrtRoute.setEnabled(ctx, true)
        val carga = d01.readBytes()
        val motor = MotorControlado(concluirNoCancel = false)
        WhisperOrtRoute.motorConversaoParaTeste = motor
        WhisperOrtRoute.timeoutConversaoMsParaTeste = 2_000L
        WhisperOrtRoute.cacheParaTeste = WhisperOrtSessionCache(
            factory = { _, _, _, _ -> BackendRoteirizado(roteiroFechado()) },
            open = { },
        )
        val antes = terminais().keys.toSet()
        val noDreno = CountDownLatch(1)

        ActivityScenario.launch(WhisperActivity::class.java).use { cenario ->
            cenario.onActivity { activity ->
                WhisperOrtRoute.transcribe(
                    activity = activity as WhisperActivity,
                    uri = Uri.fromFile(mp3Falso()),
                    onProgresso = { if (it.startsWith("aguardando término da conversão")) noDreno.countDown() },
                    onResult = { },
                )
            }
            assertTrue("o worker deve alcançar o dreno", noDreno.await(30, TimeUnit.SECONDS))
            // Sucesso tardio com arquivo estabilizado: prossegue (não descarta).
            motor.terminarTarde(ok = true, cargaWav = carga)
            val t = aguardarTerminal(antes, 180_000) { it.optString("outcome") == "SUCCESS" }
            assertNotNull("sucesso tardio deveria prosseguir até SUCCESS", t)
            assertTrue(t!!.optInt("bytes") > 0)
        }
    }

    @Test
    fun cancelarNaJanelaDeAdmissao_roteadoComIdentidade() {
        val d01 = File(ctx.filesDir, "d01.wav")
        assumeTrue("WAV de controle ausente", d01.isFile)
        WhisperOrtRoute.setEnabled(ctx, true)
        val trava = CountDownLatch(1)
        val backend = BackendRoteirizado(roteiroFechado(), trava)
        WhisperOrtRoute.cacheParaTeste = WhisperOrtSessionCache(
            factory = { _, _, _, _ -> backend },
            open = { },
        )
        val antes = terminais().keys.toSet()

        ActivityScenario.launch(WhisperActivity::class.java).use { cenario ->
            val job = arrayOfNulls<WhisperOrtAdmission.Job>(1)
            cenario.onActivity { activity ->
                // transcribe devolve o job com jobAtual já publicado (antes do worker).
                job[0] = WhisperOrtRoute.transcribe(
                    activity = activity as WhisperActivity,
                    uri = Uri.fromFile(d01),
                    onResult = { },
                )
            }
            assertNotNull("job deveria ser admitido", job[0])
            // Cancel imediato roteia pelo dono publicado na janela de admissão.
            assertTrue("cancel do job admitido", WhisperOrtRoute.cancelar(job[0]!!))
            val outro = WhisperOrtAdmission.Job("job-alheio", 0, 0, "ORT", "CPU", "", 0)
            assertFalse("cancel de outro job não pode valer", WhisperOrtRoute.cancelar(outro))
            trava.countDown()
            val t = aguardarTerminal(antes, 180_000) { it.optString("outcome") == "CANCELLED" }
            assertNotNull("deveria terminar CANCELLED", t)
        }
    }

    /**
     * Adendo 16 §1 — observador quebrado na fachada: `onProgresso` que lança em
     * TODAS as etapas (inclusive "aguardando término da conversão…", com a
     * sessão física ativa) não pode desviar para encerramento prematuro nem
     * mascarar o término real. O terminal tardio continua CANCELLED preservado.
     */
    @Test
    fun observadorQuebradoDuranteDreno_naoAbortaTerminoFisico() {
        val d01 = File(ctx.filesDir, "d01.wav")
        assumeTrue("WAV de controle ausente", d01.isFile)
        WhisperOrtRoute.setEnabled(ctx, true)
        val motor = MotorControlado(concluirNoCancel = false)
        WhisperOrtRoute.motorConversaoParaTeste = motor
        WhisperOrtRoute.timeoutConversaoMsParaTeste = 2_000L
        WhisperOrtRoute.cacheParaTeste = WhisperOrtSessionCache(
            factory = { _, _, _, _ -> BackendRoteirizado(roteiroFechado()) },
            open = { },
        )
        val antes = terminais().keys.toSet()
        val observadorQuebrado: (String) -> Unit = { throw RuntimeException("ui quebrada") }

        ActivityScenario.launch(WhisperActivity::class.java).use { cenario ->
            val job1 = arrayOfNulls<WhisperOrtAdmission.Job>(1)
            cenario.onActivity { activity ->
                job1[0] = (activity as WhisperActivity).let {
                    WhisperOrtRoute.transcribe(
                        activity = activity,
                        uri = Uri.fromFile(mp3Falso()),
                        onProgresso = observadorQuebrado,
                        onResult = { },
                    )
                }
            }
            assertNotNull("primeiro job deveria ser admitido", job1[0])
            // Dá tempo ao worker de alcançar o dreno com o observador quebrado.
            Thread.sleep(8_000)
            // Cancelamento tardio chega à sessão dona mesmo com UI quebrada.
            assertTrue(WhisperOrtRoute.cancelar(job1[0]!!))
            motor.terminarTarde(ok = false)
            val t1 = aguardarTerminal(antes, 60_000) { true }
            assertNotNull("terminal do job cancelado", t1)
            assertEquals("CANCELLED", t1!!.optString("outcome"))
        }
    }
}
