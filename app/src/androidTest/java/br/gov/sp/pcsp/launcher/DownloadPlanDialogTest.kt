package br.gov.sp.pcsp.launcher

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Exercita a tela real com eventos controlados, sem rede nem arquivos de modelos. */
@RunWith(AndroidJUnit4::class)
class DownloadPlanDialogTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun arquivoAtualConcluidosEContinuarCorrespondemAosEventos() {
        val activity = openActivity()
        lateinit var progresso: DownloadPlanDialog.Progresso
        var criado = false
        try {
            val plano = DownloadSizeFormat.Plano("Modelos de teste", listOf(
                DownloadSizeFormat.Arquivo("encoder.data", 20, jaBaixado = true),
                DownloadSizeFormat.Arquivo("llm.onnx", 100),
                DownloadSizeFormat.Arquivo("vocab.json", 5),
            ))
            instrumentation.runOnMainSync { progresso = DownloadPlanDialog.progresso(activity, plano); criado = true }
            await { continuar()?.isEnabled == false }
            val sequencia = DownloadSizeFormat.Sequencia()
            fun emitir(etapa: DownloadSizeFormat.Etapa) {
                val estado = sequencia.atualizar(etapa)
                instrumentation.runOnMainSync {
                    progresso.atualizarArquivo(etapa, estado)
                }
            }
            val etapa = DownloadSizeFormat.Etapa(1, 2, "llm.onnx", 100, 100, 105)
            emitir(etapa)
            await { textos().contains("↓ llm.onnx") }
            assertFalse(textos().contains("✓ llm.onnx"))
            assertFalse(textos().contains("✓ vocab.json"))
            screenshot(activity, "transferindo")
            emitir(etapa.copy(estado = DownloadSizeFormat.EstadoArquivo.VALIDANDO))
            await { textos().contains("Validando arquivo 1/2: llm.onnx") }
            assertFalse(textos().contains("✓ llm.onnx"))
            emitir(etapa.copy(estado = DownloadSizeFormat.EstadoArquivo.CONCLUIDO))
            emitir(etapa.copy(indice = 2, nome = "vocab.json", percentArquivo = 20, downloaded = 101))
            await { textos().contains("↓ vocab.json") && textos().contains("✓ llm.onnx") }
            assertTrue(textos().contains("Total: 96%"))
            assertFalse(textos().contains("✓ vocab.json"))
            screenshot(activity, "proximo-arquivo")
            emitir(etapa.copy(indice = 2, nome = "vocab.json", downloaded = 105, estado = DownloadSizeFormat.EstadoArquivo.CONCLUIDO))
            instrumentation.runOnMainSync { progresso.concluirContinuando(105) }
            await { continuar()?.isEnabled == true && textos().contains("Download concluído.") }
            assertFalse(textos().contains("faltam 105 B"))
            assertTrue(textos().contains("faltam 0 B"))
            assertTrue(textos().contains("✓ vocab.json"))
            screenshot(activity, "concluido")
            assertTrue(continuar()!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            await { !textos().contains("Modelos de teste") }
        } finally {
            instrumentation.runOnMainSync { if (criado) progresso.fechar(); activity.finish() }
        }
    }

    @Test fun pacoteMostraValidacaoEInstalacaoSemAnunciarConclusaoAntecipada() {
        val activity = openActivity()
        lateinit var progresso: DownloadPlanDialog.Progresso
        var criado = false
        try {
            val plano = DownloadSizeFormat.Plano("Pacote de teste", listOf(DownloadSizeFormat.Arquivo("pacote.zip", 100)))
            instrumentation.runOnMainSync {
                progresso = DownloadPlanDialog.progresso(activity, plano)
                criado = true
                progresso.atualizarPacote(100, 100, "Validando pacote", false)
            }
            await { textos().contains("Validando pacote...") }
            assertFalse(textos().contains("✓ pacote.zip"))
            assertFalse(continuar()!!.isEnabled)
            instrumentation.runOnMainSync { progresso.atualizarPacote(100, 100, "Instalando componentes", false) }
            await { textos().contains("Instalando componentes...") }
            assertFalse(textos().contains("Baixando: 100%"))
            screenshot(activity, "instalando-pacote")
            instrumentation.runOnMainSync { progresso.concluirContinuando(100) }
            await { continuar()?.isEnabled == true && textos().contains("✓ pacote.zip") }
        } finally {
            instrumentation.runOnMainSync { if (criado) progresso.fechar(); activity.finish() }
        }
    }

    private fun openActivity(): Activity = instrumentation.startActivitySync(
        Intent(instrumentation.targetContext, ModelSettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )

    private fun nodes(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> =
        if (node == null) emptyList() else listOf(node) + (0 until node.childCount).flatMap { nodes(node.getChild(it)) }

    private fun textos(): String = nodes(instrumentation.uiAutomation.rootInActiveWindow).mapNotNull { it.text }.joinToString("\n")
    private fun continuar(): AccessibilityNodeInfo? = nodes(instrumentation.uiAutomation.rootInActiveWindow)
        .firstOrNull { it.text?.toString().equals("Continuar", ignoreCase = true) }

    private fun await(condition: () -> Boolean) {
        val limite = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < limite) {
            instrumentation.waitForIdleSync()
            if (condition()) return
            Thread.sleep(50)
        }
        fail("Estado da tela não observado: ${textos()}")
    }

    private fun screenshot(activity: Activity, nome: String) {
        val dir = File(activity.getExternalFilesDir(null), "download-ui-tests").apply { mkdirs() }
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: error("Screenshot indisponível")
        File(dir, "$nome.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
