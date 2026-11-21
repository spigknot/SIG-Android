package br.gov.sp.pcsp.launcher

import android.app.Activity
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * R106/T04 — C15a e C15b no aparelho, com o build da branch `npu/r106-ajustes`.
 *
 * C15a (`c15a_revalidacao_automatica`): o host altera SÓ a data do contexto. O
 * teste exige o 1º pedido em CPU com `conferencia=recusa:data`, espera a
 * verificação completa agendada sozinha e prova o 2º pedido em HTP no MESMO
 * processo — sem ação do usuário e sem download.
 *
 * C15b (`c15b_corrompido`): o host troca o contexto por uma cópia com 1 byte
 * alterado, mesmo tamanho. O teste exige CPU, a marcação de corrompido, os
 * arquivos do pacote INTACTOS (nada apagado) e nenhum diálogo de erro.
 *
 * C15b (`c15b_volta_htp`): roda depois de o host devolver o contexto original,
 * com o marcador intacto e o sinal de corrompido presente. Abre a tela, exige a
 * entrada fixa "Reinstalar aceleração NPU", toca nela e usa "Verificar novamente"
 * — a volta à HTP tem de acontecer pela AÇÃO DA TELA, sem download.
 *
 * Saída: uma linha JSON por etapa em `-e r106_saida` (texto só no arquivo).
 */
@RunWith(AndroidJUnit4::class)
class WhisperNpuRevalidacaoR106Test {

    private companion object {
        const val TAG = "R106-T04"
        const val SCHEMA = "sig-whisper-npu-revalidacao/v1"
        const val ESPERA_VERIFICACAO_MS = 240_000L

        // Os botões do AlertDialog são renderizados em CAIXA ALTA pelo tema:
        // a busca precisa ser insensível a maiúsculas (o teste da R106 provou).
        private val VERIFICAR_NOVAMENTE: java.util.regex.Pattern =
            java.util.regex.Pattern.compile("(?i).*verificar novamente.*")
        private val REINSTALAR_NPU: java.util.regex.Pattern =
            java.util.regex.Pattern.compile("(?i).*reinstalar aceleração npu.*")
        private val BAIXAR: java.util.regex.Pattern =
            java.util.regex.Pattern.compile("(?i)^baixar$")
    }

    private val ctx
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val args
        get() = InstrumentationRegistry.getArguments()

    private val saida: File
        get() = File(args.getString("r106_saida") ?: File(ctx.filesDir, "r106/revalidacao.jsonl").absolutePath)

    private val audio: File
        get() = File(args.getString("r106_audio") ?: "/data/local/tmp/r104/corpus/pt_br_1623.wav")

    private fun linha(json: JSONObject) {
        saida.parentFile?.mkdirs()
        saida.appendText(json.toString() + "\n")
        Log.i(TAG, "etapa=${json.optString("etapa")} rota=${json.optString("rota")} id=${json.optString("id")}")
    }

    private fun situacao(): WhisperNpuOferta.Situacao = when {
        WhisperOrtNpuPacote.corrompido(ctx) != null -> WhisperNpuOferta.Situacao.CORROMPIDO
        runCatching { WhisperOrtNpuPacote.estado(ctx) }.getOrNull() is WhisperOrtNpuPacote.Estado.Pronto ->
            WhisperNpuOferta.Situacao.PRONTO
        runCatching { WhisperOrtNpuPacote.estado(ctx) }.getOrNull() is WhisperOrtNpuPacote.Estado.Ausente ->
            WhisperNpuOferta.Situacao.AUSENTE
        else -> WhisperNpuOferta.Situacao.RECUSADO
    }

    /** Lista do diretório do pacote: nome, bytes e mtime — prova de "nada apagado". */
    private fun inventario(): String {
        val dir = WhisperOrtNpuPacote.dirDaVersao(ctx, WhisperOrtNpuPacote.VERSAO)
        return dir.listFiles().orEmpty().sortedBy { it.name }
            .joinToString(";") { "${it.name}:${it.length()}:${it.lastModified()}" }
    }

    private fun transcrever(cenario: ActivityScenario<WhisperActivity>, id: String): JSONObject {
        val ref = AtomicReference<Activity>()
        cenario.onActivity { ref.set(it) }
        val atividade = ref.get() ?: error("Activity nao disponivel")
        val latch = CountDownLatch(1)
        val json = JSONObject()
        WhisperOrtRoute.transcribe(
            atividade,
            Uri.fromFile(audio),
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
        return json.put("id", id)
    }

    // ------------------------------------------------------------------ C15a

    @Test
    fun c15a_revalidacao_automatica() {
        assertTrue("audio ausente: $audio", audio.isFile)
        WhisperOrtHtpRota.iniciar(ctx)
        val conferencia = runCatching { WhisperOrtNpuPacote.conferenciaRapida(ctx) }.getOrNull()
        val rota1 = WhisperOrtHtpRota.rotaDoPedido()
        val motivo1 = WhisperOrtHtpRota.motivoRota()
        linha(
            JSONObject().put("schema", SCHEMA).put("etapa", "C15A-PRE")
                .put("conferencia", conferencia?.javaClass?.simpleName ?: "null")
                .put("detalhe", when (conferencia) {
                    is WhisperOrtNpuPacote.Conferencia.Recusar -> "recusa:" + conferencia.motivo
                    is WhisperOrtNpuPacote.Conferencia.Reverificar -> "reverificar:" + conferencia.motivo
                    is WhisperOrtNpuPacote.Conferencia.Ok -> "ok"
                    null -> "erro"
                })
                .put("rota", rota1).put("motivo", motivo1)
                .put("pid", android.os.Process.myPid()),
        )
        assertEquals("1º pedido tem de ser CPU (recusa pela data)", "cpu", rota1)
        assertTrue("motivo da recusa não menciona data: $motivo1", motivo1.contains("data"))

        ActivityScenario.launch(WhisperActivity::class.java).use { cenario ->
            val rotaAntes1 = WhisperOrtHtpRota.rotaDoPedido()
            val t1 = transcrever(cenario, "C15A-1")
            linha(t1.put("schema", SCHEMA).put("etapa", "C15A-1").put("rota_antes", rotaAntes1))
            // A verificação completa roda em segundo plano e, medida no logcat,
            // termina em menos de 1 s. Se ela vencer a corrida com a abertura da
            // tela, a 1ª transcrição já é o "pedido seguinte" — e isso fica
            // registrado em `rota_antes`, sem inventar CPU que não houve.
            if (rotaAntes1 == WhisperOrtHtpAdmissao.ROTA_CPU) {
                assertEquals(
                    "com a rota ainda em CPU, a 1ª transcrição tem de cair na CPU",
                    "CPU",
                    t1.optString("rota"),
                )
            }

            val inicio = SystemClock.elapsedRealtime()
            var rota2 = WhisperOrtHtpRota.rotaDoPedido()
            while (rota2 != WhisperOrtHtpAdmissao.ROTA_HTP &&
                SystemClock.elapsedRealtime() - inicio < ESPERA_VERIFICACAO_MS
            ) {
                Thread.sleep(500)
                rota2 = WhisperOrtHtpRota.rotaDoPedido()
            }
            val esperou = SystemClock.elapsedRealtime() - inicio
            val motivo2 = WhisperOrtHtpRota.motivoRota()
            linha(
                JSONObject().put("schema", SCHEMA).put("etapa", "C15A-REVALIDADO")
                    .put("rota", rota2).put("motivo", motivo2)
                    .put("espera_ms", esperou)
                    .put("corrompido", WhisperOrtNpuPacote.corrompido(ctx) ?: "")
                    .put("pid", android.os.Process.myPid()),
            )
            assertEquals("o pedido seguinte tem de ir à HTP sem ação do usuário", "htp", rota2)
            assertEquals("não pode haver marca de corrompido", null, WhisperOrtNpuPacote.corrompido(ctx))

            val t2 = transcrever(cenario, "C15A-2")
            linha(t2.put("schema", SCHEMA).put("etapa", "C15A-2"))
            assertEquals("2ª transcrição tem de ir à HTP", "HTP W8A16", t2.optString("rota"))
        }
    }

    // ------------------------------------------------------------------ C15b

    @Test
    fun c15b_corrompido() {
        assertTrue("audio ausente: $audio", audio.isFile)
        val antes = inventario()
        WhisperOrtHtpRota.iniciar(ctx)
        val rota1 = WhisperOrtHtpRota.rotaDoPedido()
        val motivo1 = WhisperOrtHtpRota.motivoRota()
        linha(
            JSONObject().put("schema", SCHEMA).put("etapa", "C15B-PRE")
                .put("rota", rota1).put("motivo", motivo1)
                .put("inventario", antes)
                .put("pid", android.os.Process.myPid()),
        )
        assertEquals("pedido com pacote adulterado tem de ser CPU", "cpu", rota1)

        var dialogo = false
        ActivityScenario.launch(WhisperActivity::class.java).use { cenario ->
            val t1 = transcrever(cenario, "C15B-1")
            linha(t1.put("schema", SCHEMA).put("etapa", "C15B-1"))
            assertEquals("transcrição tem de cair na CPU", "CPU", t1.optString("rota"))
            cenario.onActivity { a ->
                dialogo = (a as? WhisperActivity)?.dialogoFallbackEmExibicao != null
            }
        }

        val inicio = SystemClock.elapsedRealtime()
        var corrompido: String? = null
        while (corrompido == null && SystemClock.elapsedRealtime() - inicio < ESPERA_VERIFICACAO_MS) {
            Thread.sleep(500)
            corrompido = WhisperOrtNpuPacote.corrompido(ctx)
        }
        val depois = inventario()
        val situacao = situacao()
        linha(
            JSONObject().put("schema", SCHEMA).put("etapa", "C15B-CORROMPIDO")
                .put("corrompido", corrompido ?: "")
                .put("espera_ms", SystemClock.elapsedRealtime() - inicio)
                .put("situacao", situacao.name)
                .put("rotulo", WhisperNpuOferta.rotuloEntradaFixa(situacao))
                .put("verificar_novamente", WhisperNpuOferta.ofereceVerificarNovamente(situacao))
                .put("inventario_antes", antes)
                .put("inventario_depois", depois)
                .put("dialogo_erro", dialogo)
                .put("rota_reavaliada", WhisperOrtHtpRota.rotaDoPedido())
                .put("pid", android.os.Process.myPid()),
        )
        assertNotNull("a verificação completa tem de marcar o pacote como corrompido", corrompido)
        assertEquals("nada pode ser apagado do pacote", antes, depois)
        assertEquals("a entrada fixa tem de dizer reinstalar", "Reinstalar aceleração NPU",
            WhisperNpuOferta.rotuloEntradaFixa(situacao))
        assertTrue("a entrada fixa tem de oferecer Verificar novamente",
            WhisperNpuOferta.ofereceVerificarNovamente(situacao))
        assertEquals("pacote marcado como corrompido não pode ir à HTP", "cpu", WhisperOrtHtpRota.rotaDoPedido())
        assertTrue("não pode haver diálogo de erro", !dialogo)
    }

    @Test
    fun c15b_volta_htp() {
        assertTrue("audio ausente: $audio", audio.isFile)
        assertNotNull("C15b exige o pacote marcado como corrompido", WhisperOrtNpuPacote.corrompido(ctx))
        WhisperOrtHtpRota.iniciar(ctx)
        val rotaAntes = WhisperOrtHtpRota.rotaDoPedido()
        assertEquals("com a marca de corrompido a rota é CPU até o usuário agir", "cpu", rotaAntes)

        val dispositivo = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        ActivityScenario.launch(WhisperActivity::class.java).use { cenario ->
            selecionarNpuParaAceite(dispositivo, cenario)

            val verificar = dispositivo.wait(
                androidx.test.uiautomator.Until.findObject(By.text(VERIFICAR_NOVAMENTE)),
                15_000L,
            )
            val temReinstalar = dispositivo.hasObject(By.text(REINSTALAR_NPU))
            val temBaixar = dispositivo.hasObject(By.text(BAIXAR))
            val botoes = dispositivo.findObjects(By.clazz("android.widget.Button"))
                .mapNotNull { it.text }.take(8)
            linha(
                JSONObject().put("schema", SCHEMA).put("etapa", "C15B-DIALOGO")
                    .put("verificar_novamente", verificar != null)
                    .put("reinstalar", temReinstalar)
                    .put("baixar", temBaixar)
                    .put("botoes", botoes.joinToString("|")),
            )
            assertNotNull("o diálogo tem de oferecer Verificar novamente", verificar)
            assertTrue("o diálogo tem de oferecer Reinstalar aceleração NPU", temReinstalar)
            assertTrue("o diálogo NÃO pode oferecer download direto", !temBaixar)
            verificar!!.click()

            val inicio = SystemClock.elapsedRealtime()
            var rota: String = WhisperOrtHtpRota.rotaDoPedido()
            while (rota != WhisperOrtHtpAdmissao.ROTA_HTP &&
                SystemClock.elapsedRealtime() - inicio < ESPERA_VERIFICACAO_MS
            ) {
                Thread.sleep(500)
                rota = WhisperOrtHtpRota.rotaDoPedido()
            }
            linha(
                JSONObject().put("schema", SCHEMA).put("etapa", "C15B-VOLTA")
                    .put("rota", rota)
                    .put("espera_ms", SystemClock.elapsedRealtime() - inicio)
                    .put("corrompido", WhisperOrtNpuPacote.corrompido(ctx) ?: "")
                    .put("motivo", WhisperOrtHtpRota.motivoRota()),
            )
            assertEquals("Verificar novamente tem de readmitir a HTP", "htp", rota)
            assertEquals("a marca de corrompido tem de sumir", null, WhisperOrtNpuPacote.corrompido(ctx))

            val t = transcrever(cenario, "C15B-2")
            linha(t.put("schema", SCHEMA).put("etapa", "C15B-2"))
            assertEquals("depois de Verificar novamente a rota tem de ser HTP", "HTP W8A16", t.optString("rota"))
        }
        // Posição final da entrada fixa depois da readmissão.
        val situacao = situacao()
        linha(
            JSONObject().put("schema", SCHEMA).put("etapa", "C15B-POS")
                .put("situacao", situacao.name)
                .put("rotulo", WhisperNpuOferta.rotuloEntradaFixa(situacao)),
        )
        assertEquals("NPU ativa", WhisperNpuOferta.rotuloEntradaFixa(situacao))
    }
}
