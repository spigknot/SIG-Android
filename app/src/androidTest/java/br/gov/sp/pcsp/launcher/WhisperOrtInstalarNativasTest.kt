package br.gov.sp.pcsp.launcher

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Adendo 16 — instala o pacote de dependências nativas do produto
 * ([NativeDependencyManager], v14 arm64 ~50 MB do R2) quando ausente.
 *
 * Sem ele, a rota ORT real recusa com BUSY ("Dependências do SIG não
 * instaladas") e o whisper.cpp não carrega: nenhuma inferência real no
 * launcher é possível. É setup de aparelho (download publicado + SHA),
 * não mudança de código; idempotente ([isInstalled] curto-circuita).
 */
@RunWith(AndroidJUnit4::class)
class WhisperOrtInstalarNativasTest {

    @Test
    fun instalarNativasSeAusente() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        if (NativeDependencyManager.isInstalled(ctx)) {
            Log.i("INSTALAR-NATIVAS-16", "ja instalado; nada a fazer")
            return
        }
        val inicio = System.currentTimeMillis()
        val r = NativeDependencyManager.install(ctx) { p ->
            Log.i(
                "INSTALAR-NATIVAS-16",
                "progresso ${p.stage} ${p.downloaded}/${p.total} baixando=${p.baixando}",
            )
        }
        Log.i(
            "INSTALAR-NATIVAS-16",
            "install ok=${r.isSuccess} em_ms=${System.currentTimeMillis() - inicio} " +
                "erro=${r.exceptionOrNull()?.message}",
        )
        assertTrue("falha ao instalar nativas: ${r.exceptionOrNull()?.message}", r.isSuccess)
        assertTrue(NativeDependencyManager.isInstalled(ctx))
        // ORT runtime carrega por caminho absoluto (ordem de dependência).
        val rt = NativeDependencyManager.loadOrtRuntime(ctx) { Log.i("INSTALAR-NATIVAS-16", it) }
        assertTrue("ORT runtime deveria carregar: ${rt.exceptionOrNull()?.message}", rt.isSuccess)
    }
}
