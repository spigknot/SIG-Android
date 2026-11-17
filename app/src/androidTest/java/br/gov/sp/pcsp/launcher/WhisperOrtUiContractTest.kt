package br.gov.sp.pcsp.launcher

import android.content.Context
import android.view.View
import android.widget.CheckBox
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

/**
 * Adendo 9 §7 — prova de UI da rota ORT na ferramenta REAL, por teste instrumentado.
 *
 * Por que instrumentado: a `WhisperActivity` não é exportada (o shell não a inicia)
 * e o APK da worktree não instala por cima do release do aparelho
 * (INSTALL_FAILED_VERSION_DOWNGRADE). O teste roda no mesmo processo do app e
 * exercita os controles por clique, sem depender de `adb` nem de versionCode.
 *
 * O que é verificado:
 * 1. a tela da ferramenta mostra `checkbox_usar_cpp`, `help_usar_cpp` e
 *    `button_transcribe_ort` — os três controles do M4;
 * 2. os controles estão VISÍVEIS (não basta existirem escondidos no layout);
 * 3. marcar a caixa desliga o ORT e desmarcar religa, pela preferência real
 *    (`WhisperOrtRoute.isEnabled`), que é o que o despacho consulta.
 *
 * Rodar (variante de lab, applicationId próprio):
 *   ./gradlew :app:assembleWhisperlabDebug :app:assembleWhisperlabDebugAndroidTest -PwhisperLab=true
 *   adb shell am instrument -w -e class br.gov.sp.pcsp.launcher.WhisperOrtUiContractTest \
 *       br.gov.sp.pcsp.whisperlab.test/androidx.test.runner.AndroidJUnitRunner
 */
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

    /**
     * R11 §2/§4 — contrato NOVO da tela: um único "Transcrever", seletor de
     * arquivos identificado, opções avançadas acessíveis desde a abertura e as
     * duas caixas de log fora da tela (ids preservados para diagnóstico).
     */
    @Test
    fun ferramentaTemUmUnicoTranscreverOpcoesELogsForaDaTela() {
        ActivityScenario.launch(WhisperActivity::class.java).use { cenario ->
            cenario.onActivity { activity ->
                val recursos = activity.resources
                fun id(nome: String) = recursos.getIdentifier(nome, "id", activity.packageName)

                val caixa = activity.findViewById<CheckBox>(R.id.checkbox_usar_cpp)
                val ajuda = activity.findViewById<View>(R.id.help_usar_cpp)
                val transcrever = activity.findViewById<View>(R.id.button_transcribe)
                val seletor = activity.findViewById<View>(R.id.button_select_media)
                val avancadas = activity.findViewById<View>(R.id.advanced_toggle)
                val logTexto = activity.findViewById<View>(R.id.log_text)
                val terminal = activity.findViewById<View>(R.id.terminal_text)

                assertNotNull("checkbox 'usar whisper.cpp' ausente", caixa)
                assertNotNull("ajuda '!' ausente", ajuda)
                assertNotNull("botão Transcrever ausente", transcrever)
                assertNotNull("seletor de arquivos ausente", seletor)
                assertNotNull("opções avançadas ausentes", avancadas)

                // §2 — o segundo botão verde sumiu do layout (nem id existe).
                assertEquals("segundo botão 'Transcrever' ainda presente", 0, id("button_transcribe_ort"))

                // §1 — avançadas acessíveis desde a abertura, sem arquivo.
                assertEquals("opções avançadas escondidas na abertura",
                    View.VISIBLE, avancadas!!.visibility)

                // §4 — as duas caixas de log não aparecem mais.
                assertEquals("cabeçalho de log ainda visível", View.GONE,
                    activity.findViewById<View>(R.id.log_toggle).visibility)
                assertEquals("caixa de log ainda visível", View.GONE, logTexto!!.visibility)
                assertEquals("terminal (2ª caixa) ainda visível", View.GONE, terminal!!.visibility)

                // Controles do motor ORT continuam visíveis na rota.
                assertEquals("checkbox existe mas nao esta visivel", View.VISIBLE, caixa!!.visibility)
                assertEquals("ajuda '!' nao esta visivel", View.VISIBLE, ajuda!!.visibility)
            }
        }
    }

    @Test
    fun marcarCaixaDesligaOrtEDesmarcarReliga() {
        // Estado de partida explícito: ORT ligado (default do produto).
        WhisperOrtRoute.setEnabled(ctx, true)
        ActivityScenario.launch(WhisperActivity::class.java).use { cenario ->
            cenario.onActivity { activity ->
                val caixa = activity.findViewById<CheckBox>(R.id.checkbox_usar_cpp)!!
                assertFalse("com ORT ligado a caixa deve comecar desmarcada", caixa.isChecked)

                caixa.performClick()
                assertTrue("marcar a caixa deveria marcar", caixa.isChecked)
                assertFalse("marcar 'usar whisper.cpp' deve desligar o ORT", WhisperOrtRoute.isEnabled(activity))

                caixa.performClick()
                assertFalse("desmarcar deveria desmarcar", caixa.isChecked)
                assertTrue("desmarcar deve religar o ORT", WhisperOrtRoute.isEnabled(activity))
            }
        }
    }

    @Test
    fun ajudaAbreExplicacaoSemQuebrar() {
        ActivityScenario.launch(WhisperActivity::class.java).use { cenario ->
            cenario.onActivity { activity ->
                val ajuda = activity.findViewById<View>(R.id.help_usar_cpp)!!
                // O "!" abre um AlertDialog explicando a opção. Se o listener
                // quebrar, o processo do teste cai — o clique é a prova de que o
                // caminho existe e executa.
                ajuda.performClick()
                activity.runOnUiThread { }
            }
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
