package br.gov.sp.pcsp.launcher

import android.app.Activity
import android.net.Uri
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * R105/T02 — A/B da rota CPU do PRODUTO entre o build de `df489c7` (WIP sem a
 * integração da NPU) e o build desta rodada.
 *
 * **Contrato de compatibilidade (a razão de existir um harness próprio):** este
 * arquivo usa SOMENTE APIs presentes nos DOIS builds — `WhisperOrtRoute.transcribe`,
 * `WhisperOrtPackageFlow.estado`, `NativeDependencyManager.activateIfInstalled` e
 * `WhisperActivity`. Nada de `WhisperOrtHtpRota`, pacote da NPU ou recibo da
 * rodada: assim o MESMO harness roda nos dois APKs, pelo MESMO caminho de entrada
 * do produto (Activity → `WhisperOrtRoute.transcribe` → preparo → compute).
 *
 * Saída: uma linha JSON por clipe, com `id`, `texto`, `rota` e tempos. O texto vai
 * SÓ para o arquivo (`-e r105_saida`), nunca para o logcat — a linha de log leva
 * apenas o id.
 *
 * A rota é forçada a CPU pelo host (wrapper do pacote da NPU fora do lugar no
 * build HEAD); se algum pedido voltar em HTP, a linha registra a rota efetiva e o
 * A/B é inválido — nada é convertido por conta própria.
 */
@RunWith(AndroidJUnit4::class)
class WhisperCpuAbR105Test {

    private companion object {
        const val TAG = "R105-AB"
        const val SCHEMA = "sig-whisper-cpu-ab/v1"
    }

    private val ctx
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val args
        get() = InstrumentationRegistry.getArguments()

    private val saida: File
        get() = File(args.getString("r105_saida") ?: File(ctx.filesDir, "r105/ab.jsonl").absolutePath)

    private fun linha(json: JSONObject) {
        saida.parentFile?.mkdirs()
        saida.appendText(json.toString() + "\n")
    }

    /** Lista de áudios: `-e r105_lista <arquivo>` com um caminho por linha. */
    private fun audios(): List<File> {
        val lista = args.getString("r105_lista")?.let { File(it) } ?: return emptyList()
        if (!lista.isFile) return emptyList()
        return lista.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { File(it) }
            .filter { it.isFile }
    }

    private fun nativoPronto(): Boolean =
        runCatching { NativeDependencyManager.activateIfInstalled(ctx) }.getOrDefault(false)

    private fun modeloPronto(): Boolean =
        runCatching { WhisperOrtPackageFlow.estado(ctx) }.getOrNull() is WhisperOrtPackageFlow.Estado.Pronto

    @Test
    fun ab_cpu() {
        val fontes = audios()
        val nativo = nativoPronto()
        val modelo = modeloPronto()
        val ok = nativo && modelo && fontes.isNotEmpty()
        linha(
            JSONObject()
                .put("schema", SCHEMA)
                .put("id", "AB-PRE")
                .put("celula", "AB")
                .put("rota", "-")
                .put("outcome", if (ok) "PRE_OK" else "PRE_CONDICAO")
                .put("nativo", nativo)
                .put("modelo", modelo)
                .put("clipes", fontes.size)
                .put("pid", android.os.Process.myPid()),
        )
        assertTrue("PRE_CONDICAO: nativo=$nativo modelo=$modelo clipes=${fontes.size}", ok)

        val limite = (args.getString("r105_limite") ?: "0").toInt()
        val alvo = if (limite > 0) minOf(limite, fontes.size) else fontes.size
        Log.i(TAG, "ab inicio clipes=$alvo pid=${android.os.Process.myPid()}")

        ActivityScenario.launch(WhisperActivity::class.java).use { cenario ->
            val ref = AtomicReference<Activity>()
            // Não bloqueia a thread principal: o await longo roda na instrumentação.
            cenario.onActivity { ref.set(it) }
            val atividade = ref.get() ?: error("Activity nao disponivel")
            for (i in 0 until alvo) {
                val fonte = fontes[i]
                val id = fonte.nameWithoutExtension
                val latch = CountDownLatch(1)
                val inicio = System.nanoTime() / 1_000_000L
                val json = JSONObject()
                    .put("schema", SCHEMA)
                    .put("id", id)
                    .put("celula", "AB")
                WhisperOrtRoute.transcribe(
                    atividade,
                    Uri.fromFile(fonte),
                    onResult = { r ->
                        val total = System.nanoTime() / 1_000_000L - inicio
                        when (r) {
                            is WhisperOrtRoute.Result.Done -> json
                                .put("rota", r.hardwareLabel ?: "?")
                                .put("outcome", r.outcome)
                                .put("open_ms", r.openMillis)
                                .put("audio_ms", r.audioMillis ?: -1L)
                                .put("motivo", "")
                                .put("texto", r.text)
                            is WhisperOrtRoute.Result.Cancelled -> json
                                .put("rota", r.hardwareLabel ?: "?")
                                .put("outcome", "CANCELLED")
                                .put("open_ms", 0L)
                                .put("motivo", "parcial=${r.partial}")
                                .put("texto", r.text)
                            is WhisperOrtRoute.Result.Refused -> json
                                .put("rota", "CPU")
                                .put("outcome", "REFUSED")
                                .put("open_ms", 0L)
                                .put("motivo", r.reason)
                                .put("texto", "")
                            is WhisperOrtRoute.Result.Failed -> json
                                .put("rota", "CPU")
                                .put("outcome", "FAILED")
                                .put("open_ms", 0L)
                                .put("motivo", r.reason)
                                .put("texto", "")
                        }
                        json.put("total_ms", total)
                        linha(json)
                        Log.i(TAG, "ab id=$id total_ms=$total rota=${json.optString("rota")}")
                        latch.countDown()
                    },
                )
                assertTrue("terminal nao chegou em 180 s: $id", latch.await(180, TimeUnit.SECONDS))
            }
        }
        Log.i(TAG, "ab fim clipes=$alvo pid=${android.os.Process.myPid()}")
    }
}
