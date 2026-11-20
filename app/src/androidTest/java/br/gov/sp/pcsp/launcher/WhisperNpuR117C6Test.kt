package br.gov.sp.pcsp.launcher

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * R117 §T02 — célula C6 no build CORRIGIDO: v2 presente e verificado, v3 AUSENTE,
 * falha do v3 simulada ANTES da verificação.
 *
 * A C6 da R116 reprovou porque a sessão abria o diretório do v3 com o v2 ativo
 * (pacoteVerificado usava dirDaVersao(app)); a transcrição saía, mas em CPU.
 * O critério desta rodada é explícito: rota efetiva HTP W8A16 com o v2
 * (encoder HTP + decoder CPU), o v2 não é removido e continua servindo.
 *
 * O preparo do v2 é o MESMO método da R116 (modo instalar_v2 do harness R116, a
 * partir do ZIP publicado com as funções do produto), feito numa sessão anterior;
 * aqui só se confere o estado, se simula a falha e se mede a rota.
 *
 * Args: r117_saida (jsonl), r117_audio (curto).
 */
@RunWith(AndroidJUnit4::class)
class WhisperNpuR117C6Test {

    private companion object {
        const val TAG = "R117-C6"
        const val SCHEMA = "sig-whisper-npu-r117-c6/v1"
    }

    private val ctx: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val args get() = InstrumentationRegistry.getArguments()

    private val saida: File
        get() = File(args.getString("r117_saida") ?: File(ctx.filesDir, "r117/c6.jsonl").absolutePath)

    private fun linha(json: JSONObject) {
        saida.parentFile?.mkdirs()
        saida.appendText(json.toString() + "\n")
        Log.i(TAG, "etapa=" + json.optString("etapa") + " rota=" + json.optString("rota") +
            " outcome=" + json.optString("outcome"))
    }

    private fun inventario(dir: File): String = if (!dir.isDirectory) {
        "ausente"
    } else {
        dir.listFiles().orEmpty().sortedBy { it.name }
            .joinToString(";") { it.name + ":" + it.length() + ":" + it.lastModified() }
    }

    private fun audio(): File =
        File(args.getString("r117_audio") ?: "/data/local/tmp/r113/corpus/pt_br_1623.wav")

    private fun asActivity(cenario: ActivityScenario<WhisperActivity>): Activity {
        val ref = AtomicReference<Activity>()
        cenario.onActivity { ref.set(it) }
        return ref.get() ?: error("Activity nao disponivel")
    }

    private fun transcrever(atividade: Activity, artefato: File, id: String): JSONObject {
        val latch = CountDownLatch(1)
        val json = JSONObject()
        val t0 = System.nanoTime()
        WhisperOrtRoute.transcribe(
            atividade, Uri.fromFile(artefato),
            onResult = { r ->
                when (r) {
                    is WhisperOrtRoute.Result.Done -> json
                        .put("rota", r.hardwareLabel ?: "?")
                        .put("outcome", r.outcome)
                        .put("texto", r.text)
                        .put("open_ms", r.openMillis)
                    is WhisperOrtRoute.Result.Cancelled -> json
                        .put("rota", r.hardwareLabel ?: "?")
                        .put("outcome", "CANCELLED")
                        .put("texto", r.text)
                    is WhisperOrtRoute.Result.Refused -> json
                        .put("rota", "CPU").put("outcome", "REFUSED").put("texto", "")
                        .put("motivo", r.reason)
                    is WhisperOrtRoute.Result.Failed -> json
                        .put("rota", "CPU").put("outcome", "FAILED").put("texto", "")
                        .put("motivo", r.reason)
                }
                latch.countDown()
            },
        )
        assertTrue("terminal nao chegou em 240 s: $id", latch.await(240, TimeUnit.SECONDS))
        return json.put("id", id).put("total_ms", (System.nanoTime() - t0) / 1_000_000)
    }

    @Test
    fun c6_v2_ativo_com_v3_ausente_serve_pela_htp() {
        val prefs = ctx.getSharedPreferences("whisper", Context.MODE_PRIVATE)
        val cppAntes = prefs.getBoolean("whisper_engine_cpp", false)
        prefs.edit().putBoolean("whisper_engine_cpp", false).commit()
        try {
            val raiz = WhisperOrtNpuPacote.raiz(ctx)
            val dirV3 = File(raiz, WhisperOrtNpuPacote.VERSAO)
            val dirV2 = WhisperOrtNpuPacote.dirDaVersao(ctx, WhisperOrtNpuPacote.VERSAO_ANTERIOR)
            val v2Antes = inventario(dirV2)
            val v3Antes = inventario(dirV3)
            val estado = WhisperOrtNpuPacote.estado(ctx)
            val versao = (estado as? WhisperOrtNpuPacote.Estado.Pronto)?.manifesto?.versao ?: "nenhum"
            linha(
                JSONObject().put("schema", SCHEMA).put("etapa", "C6-PRE")
                    .put("estado", estado.javaClass.simpleName).put("versao", versao)
                    .put("v2_existe", dirV2.isDirectory).put("v3_existe", dirV3.exists())
                    .put("v2_inventario", v2Antes).put("v3_inventario", v3Antes)
                    .put("pid", android.os.Process.myPid()),
            )
            assertFalse("a C6 exige o v3 AUSENTE", dirV3.exists())
            assertTrue("o v2 tem de estar instalado", dirV2.isDirectory)
            assertEquals("o pacote ativo tem de ser o v2", WhisperOrtNpuPacote.VERSAO_ANTERIOR, versao)

            WhisperOrtHtpRota.iniciar(ctx)
            val rotaAntes = WhisperOrtHtpRota.rotaDoPedido()
            val motivoAntes = WhisperOrtHtpRota.motivoRota()
            // Falha simulada do v3 ANTES da verificação (mesmo método da R116:
            // cancelamento imediato do instalador).
            val resultado = WhisperOrtNpuPacote.instalar(ctx, cancelado = { true })
            val v2DepoisFalha = inventario(dirV2)
            linha(
                JSONObject().put("schema", SCHEMA).put("etapa", "C6-FALHA")
                    .put("outcome", if (resultado.isFailure) "FALHA_SIMULADA" else "SEM_FALHA")
                    .put("erro", resultado.exceptionOrNull()?.javaClass?.simpleName ?: "")
                    .put("rota_antes", rotaAntes).put("motivo_antes", motivoAntes)
                    .put("v2_depois", v2DepoisFalha).put("v3_existe", dirV3.exists())
                    .put("pid", android.os.Process.myPid()),
            )
            assertTrue("a falha do v3 tem de acontecer", resultado.isFailure)
            assertFalse("o v3 nao pode aparecer com a falha", dirV3.exists())
            assertEquals("o v2 nao pode mudar com a falha", v2Antes, v2DepoisFalha)
            assertEquals("a rota do pedido tem de ser a HTP", WhisperOrtHtpAdmissao.ROTA_HTP, rotaAntes)

            ActivityScenario.launch(WhisperActivity::class.java).use { cenario ->
                val t = transcrever(asActivity(cenario), audio(), "C6-1")
                linha(
                    t.put("schema", SCHEMA).put("etapa", "C6-ROTA")
                        .put("rota_esperada", "HTP W8A16")
                        .put("versao_pacote", WhisperOrtHtpRota.versaoPacote())
                        .put("motivo_rota", WhisperOrtHtpRota.motivoRota())
                        .put("v2_inventario", inventario(dirV2))
                        .put("v3_existe", dirV3.exists())
                        .put("pid", android.os.Process.myPid()),
                )
                assertEquals("com o v2 ativo a rota efetiva tem de ser a HTP", "HTP W8A16", t.optString("rota"))
                assertFalse("a transcricao nao pode sair vazia", t.optString("texto").isNullOrBlank())
                assertEquals("o v2 nao pode ser removido", v2Antes, inventario(dirV2))
            }
            linha(
                JSONObject().put("schema", SCHEMA).put("etapa", "C6-POS")
                    .put("outcome", "V2_SERVE_HTP")
                    .put("v2_inventario", inventario(dirV2))
                    .put("v3_existe", dirV3.exists())
                    .put("rota", WhisperOrtHtpRota.rotaDoPedido())
                    .put("pid", android.os.Process.myPid()),
            )
        } finally {
            prefs.edit().putBoolean("whisper_engine_cpp", cppAntes).commit()
        }
    }
}
