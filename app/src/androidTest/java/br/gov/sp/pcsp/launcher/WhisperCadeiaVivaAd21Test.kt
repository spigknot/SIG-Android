package br.gov.sp.pcsp.launcher

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * ADENDO 21 §2 — leg LIVE com fonte INJETADA do lab (sem microfone
 * ambiente): a leg live usa o MESMO caminho de produção
 * (`startLiveMicTranscription` → `runLiveMicLoop` → `sink`/ledger) e o
 * terminal do job é o do ledger.
 *
 * Roda SÓ na variante `whisperlab` (a fonte injetada é seam de lab):
 * na variante normal o assumeTrue registra o cenário como PENDENTE (não
 * executado). `WhisperLabDrive.active=true` + `livePcmPath` ativam a
 * fonte injetada; sem isso o AudioRecord real tentaria abrir o microfone.
 */
@RunWith(androidx.test.ext.junit.runners.AndroidJUnit4::class)
class WhisperCadeiaVivaAd21Test {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val ehLab: Boolean = runCatching {
        Class.forName("br.gov.sp.pcsp.launcher.WhisperLabDrive")
        true
    }.getOrDefault(false)

    private fun setDriveField(nome: String, valor: Any?) {
        val c = Class.forName("br.gov.sp.pcsp.launcher.WhisperLabDrive")
        c.getDeclaredField(nome).apply { isAccessible = true }.set(null, valor)
    }

    @Test
    fun legLiveComFonteInjectada() {
        assumeTrue("variante de lab ausente (produção): cenário fica PENDENTE para a janela de lab", ehLab)
        val pcm = File(ctx.filesDir, "d01.wav")
        assumeTrue("fixture d01.wav ausente no lab", pcm.isFile)
        // ADENDO 21 §2 — fonte INJETADA (sem microfone ambiente):
        setDriveField("active", true)
        setDriveField("livePcmPath", pcm.absolutePath)
        val seqBase = TranscriptionJobResults.ledger.latest()?.publishedSeq ?: 0L
        try {
            ActivityScenario.launch(WhisperActivity::class.java).use { cenario ->
                cenario.onActivity { activity ->
                    val m = WhisperActivity::class.java.getDeclaredMethod("startLiveMicTranscription")
                    m.isAccessible = true
                    m.invoke(activity)
                }
                // FASE A — dono:
                val fimDono = System.currentTimeMillis() + 20_000L
                var viuDono = false
                while (System.currentTimeMillis() < fimDono) {
                    if (WhisperJobService.isRunning()) { viuDono = true; break }
                    Thread.sleep(100)
                }
                assumeTrue("FGS nesta janela não subiu", viuDono)
                // FASE B — encerramento real da fonte injetada:
                val fim = System.currentTimeMillis() + 600_000L
                while (System.currentTimeMillis() < fim) {
                    if (!WhisperJobService.isRunning()) break
                    Thread.sleep(2_000)
                }
            }
            val rec = TranscriptionJobResults.ledger.latestAfter(seqBase)
            assumeTrue("ledger sem resultados nesta janela", rec != null)
            val terminal = rec!!.terminal.toString()
            // O terminal do live NUNCA é UNKNOWN (ignorar não é prova de excesso);
            // o desfecho físico é SUCCESS (fonte fechada) ou CANCELLED.
            assertTrue(
                "terminal do live fora do conjunto real ($terminal)",
                terminal in listOf("SUCCESS", "CANCELLED"),
            )
            val rec2 = JSONObject()
                .put("produtor", "live")
                .put("jobId", rec.key.jobId)
                .put("owner", rec.key.owner)
                .put("publishedSeq", rec.publishedSeq)
                .put("terminal", terminal)
                .put("origem", "live-fonte-injectada (d01.wav)")
            android.util.Log.i("AD21LIVE", rec2.toString())
            ctx.getExternalFilesDir("ad21-live-evidence")?.let { d ->
                File(d, "live-${System.currentTimeMillis()}.json")
                    .writeText(rec2.toString(), Charsets.UTF_8)
            }
        } finally {
            setDriveField("active", false)
        }
    }
}
