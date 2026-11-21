package br.gov.sp.pcsp.launcher

import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Contrato instrumentado dos seletores e da entrega de resultado na tela Whisper. */
@RunWith(AndroidJUnit4::class)
class WhisperOrtUiContractTest {

    private val ctx: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private var preferenciaOriginal = true

    @Before
    fun guardarPreferencia() {
        preferenciaOriginal = WhisperOrtRoute.isEnabled(ctx)
    }

    @After
    fun restaurarPreferencia() {
        // O teste não pode deixar o app com preferência trocada.
        WhisperOrtRoute.setEnabled(ctx, preferenciaOriginal)
    }

    @Test
    fun telaMantemUmTranscreverEControlesDeCadaMotor() {
        WhisperOrtRoute.setEnabled(ctx, true)
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        ActivityScenario.launch(WhisperActivity::class.java).use { scenario ->
            fun checkScreen(ort: Boolean) {
                scenario.onActivity { activity ->
                    assertEquals(ort, WhisperOrtRoute.isEnabled(activity))
                    assertEquals(if (ort) "Onnx RT" else "Whisper.cpp",
                        activity.findViewById<TextView>(R.id.button_engine).text.toString())
                    val decoding = activity.findViewById<View>(R.id.cpp_decoding_controls)
                    assertEquals(if (ort) View.GONE else View.VISIBLE, decoding.visibility)
                    for (id in listOf(R.id.button_transcribe, R.id.button_select_media,
                        R.id.button_threads, R.id.button_whisper_vad)) {
                        assertNotNull(activity.findViewById<View>(id))
                    }
                    for (name in listOf("button_transcribe_ort", "advanced_toggle", "advanced_panel")) {
                        assertEquals(0, activity.resources.getIdentifier(name, "id", activity.packageName))
                    }
                    for (id in listOf(R.id.log_toggle, R.id.log_text, R.id.terminal_text)) {
                        assertEquals(View.GONE, activity.findViewById<View>(id).visibility)
                    }
                }
            }
            fun selectEngine(label: String) {
                scenario.onActivity { it.findViewById<View>(R.id.button_engine).performClick() }
                val option = device.wait(Until.findObject(By.text(label)), 5_000L)
                assertNotNull(option)
                option!!.click()
            }
            checkScreen(ort = true)
            selectEngine("Whisper.cpp")
            checkScreen(ort = false)
            selectEngine("Onnx RT")
            checkScreen(ort = true)
        }
    }

    @Test
    fun activityRecriadaReentregaResultadoPendente() {
        // Simula o que o adendo 10/11 pedem: o job terminou, o resultado foi
        // publicado, e a tela morreu ANTES de confirmar a entrega.
        val marca = "job-reentrega-" + System.currentTimeMillis()
        val terminal = WhisperOrtAdmission.Terminal(
            jobId = marca,
            attempt = 1L,
            generation = 1L,
            engine = "ORT",
            provider = "CPU",
            inputSha256 = "entrada",
            outcome = "SUCCESS",
            text = "texto reentregue apos destroy",
            textBytes = 28,
            textSha256 = "sha",
            totalMillis = 1_500L,
            partial = false,
            error = null,
            model = "whisper-large-v3-turbo",
            audioMillis = 3_000L,
            hardware = "CPU (6 threads)",
        )
        val recibo = WhisperOrtTerminalSink.publicar(ctx, terminal)
        assertNotNull("publicação precisa devolver recibo", recibo)

        ActivityScenario.launch(WhisperActivity::class.java).use { cenario ->
            // R14 — a reentrega publica o cartão num POST da main: espera o estado
            // observável (visibilidade) antes de ler, sem dormir dentro da main.
            val limite = System.currentTimeMillis() + 15_000L
            while (System.currentTimeMillis() < limite) {
                var visivel = false
                cenario.onActivity { a ->
                    visivel = a.findViewById<View>(R.id.resumo_text)?.visibility == View.VISIBLE
                }
                if (visivel) break
                Thread.sleep(200)
            }
            cenario.onActivity { activity ->
                val texto = activity.findViewById<android.widget.TextView>(R.id.live_transcript_text)
                    ?.text?.toString().orEmpty()
                val container = activity.findViewById<View>(R.id.live_transcript_container)
                val resumo = activity.findViewById<android.widget.TextView>(R.id.resumo_text)
                val resumoTexto = resumo?.text?.toString().orEmpty()
                assertTrue("resultado pendente nao foi exibido: '$texto'", texto.contains("reentregue"))
                assertEquals("container precisa ficar visivel", View.VISIBLE, container?.visibility)
                assertEquals("resumo precisa ficar visivel na reentrega", View.VISIBLE, resumo?.visibility)
                assertTrue("resumo reentregue sem modelo: '$resumoTexto'", resumoTexto.contains("whisper-large-v3-turbo"))
                assertTrue("resumo reentregue sem hardware: '$resumoTexto'", resumoTexto.contains("CPU (6 threads)"))
                // R14 — o formato do cartão mostrado pelo produto é "N" quando
                // concluídos == total (spec de WhisperResumoTest: 3, 1 (Streaming),
                // "2 de 3" só quando difere). A reentrega segue a MESMA regra.
                assertTrue(
                    "resumo reentregue sem contagem: '$resumoTexto'",
                    resumoTexto.contains("Áudios transcritos: 1") ||
                        resumoTexto.contains("1 de 1"),
                )
                assertTrue("resumo reentregue sem eficiencia: '$resumoTexto'", resumoTexto.contains("2,000x"))
                assertTrue("resumo reentregue sem status Concluído: '$resumoTexto'", resumoTexto.contains("Concluído"))
            }
        }
        // Depois de exibir, a entrega tem de estar confirmada (fora da fila).
        val aindaPendente = WhisperOrtTerminalSink.pendentes(ctx).any { it.jobId == marca }
        assertFalse("recibo confirmado não pode continuar pendente", aindaPendente)
    }
}
