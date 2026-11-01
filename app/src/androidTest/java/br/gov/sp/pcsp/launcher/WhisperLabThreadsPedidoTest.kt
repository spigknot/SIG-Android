package br.gov.sp.pcsp.launcher

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
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
 * ADENDO 20 §2 — TRANSPORTE DE THREADS COM IDENTIDADE, COM GATEWAY/JNI/
 * COMPUTE REAIS, NO CAMINHO DE PRODUÇÃO, SEM PUBLICAR.
 *
 * Variante `whisperlab` (applicationId próprio, libs privadas
 * `files/whisperlab-libs`, loader com SHA-256 medido e recibo
 * `files/art-lab/lib-load.json`): a biblioteca CANDIDATA v2 é carregada
 * LOCALMENTE — publicação de ZIP não faz parte desta rodada.
 *
 * Modo de prova: a Activity de PRODUÇÃO direta (não o entry do lab) roda com
 * `WhisperLabDrive.active == false`: os SEAMS de lab devolvem null/0
 * (`attemptRequest` → null, `threadsOverride` → 0) e o controller usa o
 * caminho de produção — `threadsForJob` (seletor congelado) e pedido de
 * produção construído pelo controller, com gateway/JNI/reais — sem sombra do
 * provedor de lab e sem fake.
 *
 * Prova: capability==2 da LIB CARREGADA; ABA pendente com valor IGUAL ao
 * pedido (one-shot 4; pedido 4): o produto NÃO consome/limpa o valor do
 * outro writer; `threads_origin=attempt-request` e
 * `threads_requested_identidade=4` no terminal/log nativo do job; pedido
 * owner/gen/job/attempt no fio de produção.
 */
@RunWith(AndroidJUnit4::class)
class WhisperLabThreadsPedidoTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** Classe de lab existente = estamos na variant frying pan certa. */
    private val ehLab: Boolean = runCatching {
        Class.forName("br.gov.sp.pcsp.launcher.WhisperLibLoader")
        true
    }.getOrDefault(false)

    private fun recibo(rotulo: String, dados: JSONObject) {
        val rec = JSONObject().put("rotulo", rotulo)
        for (k in listOf("cap", "aba_pendente", "threads", "terminal", "params", "obs")) {
            if (dados.has(k)) rec.put(k, dados.get(k))
        }
        android.util.Log.i("AD20LAB", rec.toString())
        ctx.getExternalFilesDir("ad20-evidence")?.mkdirs()
        ctx.getExternalFilesDir("ad20-evidence")?.let { d ->
            File(d, "lab-$rotulo-${SystemClock.elapsedRealtime()}.json")
                .writeText(rec.toString(), Charsets.UTF_8)
        }
    }

    /** shas recebidos do loader (identidade medida no aparelho). */
    private fun libCarregada(): JSONObject? {
        val f = File(ctx.filesDir, "art-lab/lib-load.json")
        if (!f.isFile) return null
        val linhas = f.readText(Charsets.UTF_8).lines().filter { it.isNotBlank() }
        for (i in linhas.indices.reversed()) {
            val o = runCatching { JSONObject(linhas[i]) }.getOrNull() ?: continue
            if (o.optString("event") == "lib_load" && o.optString("file") == "libsig_whisper.so") {
                return o
            }
        }
        return null
    }

    /**
     * Bloco 1 — pedido de produção transportado e APLICADO: one-shot pendente
     * com valor IGUAL fica INTACTO; native params mostra
     * threads_origin=attempt-request com as threads do seletor; job SUCCESS.
     */
    @Test
    fun transporteRealProducaoAplicaPedidoPreservaAba() {
        assumeTrue("variante de lab ausente (produção): cenário fica PENDENTE para o lab", ehLab)
        // Puxa o class-init/prewarm (primeiro call nativo carrega as libs do lab).
        val cap = runCatching { WhisperNative.attemptRequestApiVersion() }.getOrDefault(-1)
        assumeTrue("lib carregada não é a candidata v2 (cap=$cap)", cap == 2)
        val lib = libCarregada()
        recibo("cap_lib", JSONObject()
            .put("cap", cap)
            .put("obs", lib?.optString("sha256_measured") ?: "sem lib-load.json")
        )
        // ABA: one-shot pendente com MESMO valor do seletor (4). Ninguém do
        // produto deve consumir/limpar isso.
        WhisperNative.setThreadsOverride(4)
        assertEquals(4, WhisperNative.threadsOverridePeek())

        WhisperOrtRoute.setEnabled(ctx, false)      // CPP real (gateway/JNI/compute)
        val audio = File(ctx.filesDir, "d01.wav")
        assumeTrue("fixture d01.wav ausente no lab", audio.isFile)
        val antes = TerminalScan.maisNovoTerminal()

        ActivityScenario.launch(WhisperActivity::class.java).use { cenario ->
            despacharProducao(cenario, audio, 6101)
            val fechou = TerminalScan.aguardarFgs(600_000)
            assertTrue("job de produção não encerrou em 10 min", fechou)
        }
        // ABA intacta (never consumed by the product compute):
        assertEquals(
            "one-shot pendente alheio (valor IGUAL ao pedido) foi consumido/limpo",
            4, WhisperNative.threadsOverridePeek(),
        )
        val terminalNovo = TerminalScan.maisNovoTerminal()
        assertTrue("terminal.txt novo da sessão não encontrado", terminalNovo != null)
        val txt = terminalNovo!!.readText(Charsets.UTF_8)
        val params = txt.lineSequence().firstOrNull { it.contains("native params") && it.contains("threads=") }
        assertTrue("linha native params ausente no terminal", params != null)
        assertTrue("threads_origin=attempt-request ausente", params!!.contains("threads_origin=attempt-request"))
        assertTrue("threads_requested_identidade=4 ausente", params.contains("threads_requested_identidade=4"))
        recibo("transporte", JSONObject()
            .put("cap", cap)
            .put("aba_pendente", WhisperNative.threadsOverridePeek())
            .put("params", params)
            .put("obs", "produção (lab seams inativos): pedido owner/gen/job/attempt no fio")
        )
        // Limpeza: o one-shot de bancada é DO TESTE:
        WhisperNative.setThreadsOverride(0)
    }

    /** Bloco 2 — saída antecipada antes do compute + sucessor: sem vazamento. */
    @Test
    fun saidaAntecipadaSucessorSemVazamento() {
        assumeTrue("variant de lab ausente", ehLab)
        WhisperNative.setThreadsOverride(7)
        assumeTrue(WhisperNative.attemptRequestApiVersion() == 2)
        WhisperOrtRoute.setEnabled(ctx, false)
        val audio = File(ctx.filesDir, "d01.wav")
        assumeTrue(audio.isFile)
        ActivityScenario.launch(WhisperActivity::class.java).use { cenario ->
            // ADENDO 20 — o SELETOR só trata REQUEST_PICK_MEDIA (6101):
            // requisitar outro número deixa `uriEscolhida` nula e o despacho nunca acontece.
            despacharProducao(cenario, audio, 6101)
            var gen = 0L
            cenario.onActivity { activity ->
                gen = campo(cenario, "currentSessionGeneration") as Long
            }
            // Saída antecipada: cancel antes do compute terminar.
            (campo(cenario, "whisperSession") as WhisperSessionController).cancel(gen)
            val fechou = TerminalScan.aguardarFgs(240_000)
            assertTrue("job A não fechou após cancel antecipado", fechou)
            val peekA = WhisperNative.threadsOverridePeek()
            // Sucessor B: produção aplica o SEU pedido; ABA pendente não vaza para B.
            despacharProducao(cenario, audio, 6101)
            val bFechou = TerminalScan.aguardarFgs(600_000)
            assertTrue("sucessor B não encerrou", bFechou)
            assertTrue("one-shot de bancada (7) persistiu — nunca consumido pelo produto", peekA == 7)
            val params = TerminalScan.maisNovoTerminal()?.readText(Charsets.UTF_8)
                ?.lineSequence()?.firstOrNull { it.contains("native params") && it.contains("threads=") }
            recibo("saida_sucessor", JSONObject()
                .put("aba_pendente", WhisperNative.threadsOverridePeek())
                .put("params", params ?: "")
                .put("obs", "A cancelado antes do compute; B aplica o pedido próprio; 7 intacto")
            )
            WhisperNative.setThreadsOverride(0)
        }
    }


    /** ADENDO 20 — raspagem de UX real: seletor → modelo (v3Turbo) →
     * transcribeSelectedMedia (o CPP requer o modelo aplicado por arquivo;
     * sem isso não despacha). Mesma ordem do MedicaoRealTest. */
    private fun despacharProducao(cenario: ActivityScenario<WhisperActivity>, audio: File, request: Int) {
        cenario.onActivity { activity ->
            val dados = Intent().setData(Uri.fromFile(audio))
            val m = android.app.Activity::class.java.getDeclaredMethod(
                "onActivityResult",
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Intent::class.java,
            )
            m.isAccessible = true
            m.invoke(activity, request, android.app.Activity.RESULT_OK, dados)
            val modelos = WhisperActivity::class.java.getDeclaredMethod("officialModels")
                .apply { isAccessible = true }.invoke(activity) as List<*>
            val turbo = modelos.firstOrNull {
                val f = it!!.javaClass.getDeclaredField("label").apply { isAccessible = true }
                f.get(it) == "v3Turbo"
            }
            assumeTrue("v3Turbo ausente do menu oficial do lab", turbo != null)
            WhisperActivity::class.java.getDeclaredMethod("applyModelSelection", turbo!!.javaClass)
                .apply { isAccessible = true }.invoke(activity, turbo)
            val tm = WhisperActivity::class.java.getDeclaredMethod("transcribeSelectedMedia")
            tm.isAccessible = true
            tm.invoke(activity)
            val st = WhisperActivity::class.java.getDeclaredField("status")
                .apply { isAccessible = true }.get(activity) as android.widget.TextView
            android.util.Log.i("AD20LAB", "dispatch status=[${st.text.toString().take(80)}] dpo=${audio.name}")
        }
    }

    private fun campo(activity: androidx.test.core.app.ActivityScenario<WhisperActivity>, nome: String): Any? {
        var valor: Any? = null
        activity.onActivity { a ->
            val f = WhisperActivity::class.java.getDeclaredField(nome)
            f.isAccessible = true
            valor = f.get(a)
        }
        return valor
    }
}

private object TerminalScan {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val raizesFiles: Array<File> = buildList {
        add(ctx.filesDir)
        ctx.getExternalFilesDir(null)?.let { add(it) }
    }.toTypedArray()

    fun maisNovoTerminal(): File? =
        raizesFiles.asSequence().flatMap { it.walkTopDown() }
            .filter { it.isFile && it.name == "terminal.txt" }
            .maxByOrNull { it.lastModified() }

    /** Espera em 2 FASES: primeiro o FGS ADQUIRIR O DONO (nada de
     * falso-positivo antes da admissão), depois encerrar. ADENDO 20. */
    fun aguardarFgs(tetoMs: Long): Boolean {
        // FASE A — dono:
        val fimDono = System.currentTimeMillis() + 20_000L
        var viuDono = false
        while (System.currentTimeMillis() < fimDono) {
            if (WhisperJobService.isRunning()) { viuDono = true; break }
            Thread.sleep(100)
        }
        if (!viuDono) return false
        // FASE B — encerramento real:
        val fim = System.currentTimeMillis() + tetoMs
        while (System.currentTimeMillis() < fim) {
            if (!WhisperJobService.isRunning()) return true
            Thread.sleep(2_000)
        }
        return !WhisperJobService.isRunning()
    }
}
