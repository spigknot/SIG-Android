package br.gov.sp.pcsp.launcher

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Adendo 15 §1-§2 — LOADER REAL pelo fluxo comum (sem preload manual).
 *
 * O laboratório carregava `.so` de `files/` com `System.load` manual — isso NÃO
 * certifica o produto. Aqui o pacote nativo v14 é instalado/ativado pelo fluxo
 * comum ([NativeDependencyManager]) no sandbox autorizado, e só então:
 * 1. JNI/paths/runtime reais são verificados antes de abrir o `OrtEnvironment`;
 * 2. um MP3 é transcrito pela rota SEM preload do runner (prova também a
 *    conversão FFmpeg pelo loader normal).
 *
 * Não altera o app publicado do usuário: roda na variante de laboratório
 * (applicationId próprio). Não anuncia compatibilidade a partir de skip: ou
 * prova de verdade, ou falha com o motivo.
 */
@RunWith(AndroidJUnit4::class)
class WhisperOrtLoaderRealTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun fluxoNormal_instalaV14_ativaOrtETranscreveSemPreload() {
        // 1) Instala pelo fluxo comum (download R2 + SHA + extração), se preciso.
        if (!NativeDependencyManager.isInstalled(ctx)) {
            val falha = AtomicReference<Throwable?>()
            val pronto = CountDownLatch(1)
            Thread({
                val r = runCatching { NativeDependencyManager.install(ctx) {} }
                falha.set(r.exceptionOrNull())
                pronto.countDown()
            }, "loader-install").apply { isDaemon = true }.start()
            assertTrue(
                "instalação do pacote nativo excedeu 12 min (rede do aparelho?)",
                pronto.await(12, TimeUnit.MINUTES),
            )
            assertTrue("install falhou: ${falha.get()}", falha.get() == null)
        }
        assertTrue("pacote deveria estar instalado", NativeDependencyManager.isInstalled(ctx))

        // 2) Ativa e verifica JNI/paths reais antes de abrir o OrtEnvironment.
        assertTrue("activateIfInstalled recusou", NativeDependencyManager.activateIfInstalled(ctx))
        // Carga pelo FLUXO COMUM (ativa o v14 + caminhos absolutos em ordem): era
        // exatamente o `dlopen failed` do §63 com `loadLibrary` puro.
        val carga = NativeDependencyManager.loadOrtRuntime(ctx)
        assertTrue("loadOrtRuntime falhou: ${carga.exceptionOrNull()}", carga.isSuccess)
        // Abrir o ambiente prova o runtime nativo real (lançaria
        // UnsatisfiedLinkError sem as duas `.so` resolvidas); a inferência na
        // etapa 3 prova o CPU EP em execução.
        val ambiente = ai.onnxruntime.OrtEnvironment.getEnvironment()
        try {
            assertTrue("ambiente nulo após carga real", ambiente != null)
        } finally {
            ambiente.close()
        }

        // 3) MP3 pela rota, SEM preload manual do runner: transcreve (prova o ORT)
        // e converte (prova o FFmpeg) pelo loader normal.
        val audio = File(ctx.filesDir, "d01.mp3")
        assertTrue("MP3 de controle ausente em files/ (push antes de rodar)", audio.isFile)
        val antes = terminais().keys.toSet()
        val pronto = CountDownLatch(1)
        WhisperOrtRoute.transcribe(
            activityOf(), Uri.fromFile(audio),
            onResult = { pronto.countDown() },
        )
        assertTrue("o job não terminou", pronto.await(240, TimeUnit.SECONDS))
        val novos = terminais().filterKeys { it !in antes }
        val ok = novos.values.any { it.optString("outcome") == "SUCCESS" && it.optInt("bytes") > 0 }
        assertTrue("nenhum SUCCESS com texto após o loader real", ok)
    }

    private fun activityOf(): android.app.Activity {
        // A rota precisa de uma Activity (contexto + main looper); usa a real.
        val regra = androidx.test.core.app.ActivityScenario.launch(WhisperActivity::class.java)
        val ref = AtomicReference<android.app.Activity>()
        regra.onActivity { ref.set(it) }
        regra.close()
        return ref.get()
    }

    private fun terminais(): Map<String, JSONObject> {
        val pasta = File(ctx.filesDir, "ort-route/terminais")
        val arquivos = pasta.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return emptyMap()
        return arquivos.mapNotNull { f ->
            runCatching { f.nameWithoutExtension to JSONObject(f.readText(Charsets.UTF_8)) }.getOrNull()
        }.toMap()
    }
}
