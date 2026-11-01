package br.gov.sp.pcsp.launcher

import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Adendo 15 §1 — JOB EM VOO durante o DESTROY da tela (fonte final).
 *
 * O contrato: o compute não pertence à Activity. Se a tela morre no meio, o job
 * termina e o resultado fica PENDENTE no sink (sem confirmação), para a próxima
 * Activity recuperar. Um callback numa tela morta não pode confirmar entrega.
 *
 * Na main roda contra a WhisperActivity com backend controlado (sem nativas).
 */
@RunWith(AndroidJUnit4::class)
class WhisperOrtDestroyEmVooTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val ts0 = WhisperOrtSegments.timestampBegin(WhisperOrtPrompt.NO_TIMESTAMPS)
    private fun ts(seg: Double): Int = ts0 + Math.round(seg / 0.02).toInt()

    private inner class BackendTexto : WhisperOrtBackend {
        private var janela = -1
        private var indice = 0
        private val roteiro = listOf(ts0, 65, ts(2.0), ts(2.0), 66, ts(4.0), ts(4.0))
        override fun encode(logMel: FloatArray) = FloatArray(1_500 * 1_280)
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
            v[roteiro.getOrNull(posicao) ?: WhisperOrtPrompt.END_OF_TEXT] = 5f
            return v
        }
    }

    @After
    fun limparCache() {
        WhisperOrtRoute.cacheParaTeste = null
    }

    @Test
    fun resultadoSobreviveAoTelaDestruida() {
        val audio = File(ctx.filesDir, "d01.wav")
        assumeTrue("WAV de controle ausente", audio.isFile)
        WhisperOrtRoute.setEnabled(ctx, true)
        WhisperOrtRoute.cacheParaTeste = WhisperOrtSessionCache(
            factory = { _, _, _, _ -> BackendTexto() },
            open = { },
        )

        val terminou = CountDownLatch(1)
        val antes = WhisperOrtTerminalSink.pendentes(ctx).map { it.jobId }.toSet()

        ActivityScenario.launch(WhisperActivity::class.java).use { cenario ->
            cenario.onActivity { activity ->
                WhisperOrtRoute.transcribe(
                    activity, Uri.fromFile(audio),
                    onResult = { terminou.countDown() },
                )
            }
            // Destrói a tela IMEDIATAMENTE: o compute continua na rota.
            cenario.close()
        }

        // O resultado deve ser publicado mesmo sem tela viva.
        val publicou = terminou.await(180, TimeUnit.SECONDS)
        assertTrue("o job não terminou após o destroy", publicou)

        val novos = WhisperOrtTerminalSink.pendentes(ctx).filter { it.jobId !in antes }
        assertTrue(
            "nenhum terminal novo pendente: a entrega foi confirmada por uma tela morta " +
                "ou o resultado não foi persistido",
            novos.isNotEmpty(),
        )
    }
}
