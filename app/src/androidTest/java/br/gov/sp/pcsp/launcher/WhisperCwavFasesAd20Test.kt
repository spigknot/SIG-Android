package br.gov.sp.pcsp.launcher

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * ADENDO 20 §3 — MEDIÇÃO CONTROLADA do relato de sel-demora em 99%
 * (c.wav/turbo, CPP real; monotônico, POR IDENTIDADE de tentativa).
 *
 * Entrada: a17-cwav-30016.wav (30,016 s; 480256 amostras preservadas). Registra
 * para o job/attempt/entrada real: produtor, geração lógica (token no FGS),
 * threadRepository? — não: threads via terminal; 1º/último 99 por FasesArquivo
 * (relógio monotônico), último segmento EMITIDO/apresentado (linha
 * `fases job=` do Activity), retorno nativo (compute_ms), retorno→parse,
 * parse→writes (persistência), terminal do FGS e liberação da reserva
 * (logcat WhisperJob por método).cuidados: um único método executa um único
 * job; dois métodos por entrada nunca sobrepõem IDs.
 *
 * O diagnóstico deve responder: qual etapa gerou a espera relatada depois do
 * último texto (não usar polling; não confundir posição no áudio (último
 * segmento_fim_audio_ms) com o instante EXIBIDO; não prometer aceleração).
 */
@RunWith(AndroidJUnit4::class)
class WhisperCwavFasesAd20Test {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** ADENDO 23 §3 — Activity capturada no lançamento (leitura do volatile). */
    private var activityUsada: WhisperActivity? = null

    @Test
    fun cwavTurboFasesComIdentidade() {
        val entry = File(ctx.filesDir, "a17-cwav-30016.wav")
        assumeTrue("fixture a17 (c.wav 30,016 s) ausente", entry.isFile && entry.length() > 900_000L)
        activityUsada = null
        assumeTrue("devia GTML turbo no aparelho", modelosTurbo())
        val antes = System.currentTimeMillis()
        val inicioTesteElapsed = android.os.SystemClock.elapsedRealtime()
        var recusado = false

        WhisperOrtRoute.setEnabled(ctx, false)
        ActivityScenario.launch(WhisperActivity::class.java).use { cenario ->
            cenario.onActivity { activity ->
                activityUsada = activity
                // fx no adendo: seletor→model turbo→transcribe (despacho CPP).
                val dados = Intent().setData(Uri.fromFile(entry))
                val m = android.app.Activity::class.java.getDeclaredMethod(
                    "onActivityResult",
                    Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Intent::class.java,
                )
                m.isAccessible = true
                m.invoke(activity, 6101, android.app.Activity.RESULT_OK, dados)
                val modelos = WhisperActivity::class.java.getDeclaredMethod("officialModels")
                    .apply { isAccessible = true }.invoke(activity) as List<*>
                val turbo = modelos.firstOrNull {
                    val f = it!!.javaClass.getDeclaredField("label").apply { isAccessible = true }
                    f.get(it) == "v3Turbo"
                }
                assumeTrue("v3Turbo ausente", turbo != null)
                WhisperActivity::class.java.getDeclaredMethod("applyModelSelection", turbo!!.javaClass)
                    .apply { isAccessible = true }.invoke(activity, turbo)
                val tm = WhisperActivity::class.java.getDeclaredMethod("transcribeSelectedMedia")
                tm.isAccessible = true
                tm.invoke(activity)
                val st = WhisperActivity::class.java.getDeclaredField("status")
                    .apply { isAccessible = true }.get(activity) as android.widget.TextView
                recusado = st.text.toString().contains("Job recusado")
            }
            assertFalse("admissão recusada na janela", recusado)

            // Esp polling por geração (identidade do job):
            var gen = 0L
            cenario.onActivity { activity ->
                val f = WhisperActivity::class.java.getDeclaredField("currentSessionGeneration")
                    .apply { isAccessible = true }.get(activity) as Long
                gen = f
            }
            assertTrue("geração inválida", gen > 0L)

            // FASE A — dono (evita falso-positivo):
            val fimDono = System.currentTimeMillis() + 20_000L
            var viuDono = false
            while (System.currentTimeMillis() < fimDono) {
                if (WhisperJobService.isRunning()) { viuDono = true; break }
                Thread.sleep(100)
            }
            assumeTrue("FGS nesta janela não subiu", viuDono)
            // FASE B — encerramento real (teto truncado):
            val fim = System.currentTimeMillis() + 1_200_000L
            while (System.currentTimeMillis() < fim) {
                if (!WhisperJobService.isRunning()) break
                Thread.sleep(2_000)
            }
        }

        // Recibo: terminal.txt da sessão com as fases do §4 por identidade
        var terminal: File? = null
        val raizes = buildList { add(ctx.filesDir); ctx.getExternalFilesDir(null)?.let { add(it) } }
        terminal = raizes.asSequence().flatMap { it.walkTopDown() }
            .filter { it.isFile && it.name == "terminal.txt" && it.lastModified() >= antes }
            .maxByOrNull { it.lastModified() }
        assertNotNull("terminal.txt da sessão não encontrado (a busca não achou o do job)", terminal)
        val txt = terminal!!.readText(Charsets.UTF_8)
        val fases = txt.lineSequence().filter { it.contains("fases job=") }.toMutableList()
        val m2 = txt.lineSequence().filter { it.contains("retorno_ate_parse_ms") }
            .toMutableList()
        val params = txt.lineSequence()
            .filter { it.contains("native params") && it.contains("threads=") }
            .toMutableList()
        val model_linhas = txt.lineSequence()
            .filter { it.contains("whisper_full_parallel returned") }
            .toMutableList()
        assertTrue("fases ausentes no terminal: nenhuma linha `fases job=`", fases.isNotEmpty())
        assertTrue("m2 ausente (retorno→parse/parse→writes)", m2.isNotEmpty())
        val recibo = JSONObject()
            .put("rotulo", "cwav_turbo_cpp_fases")
            .put("entrada", entry.name)
            .put("job_startHostMs", antes)
            .put("fases", JSONArray(fases))
            .put("m2", JSONArray(m2))
            .put("returned_line", if (model_linhas.isNotEmpty()) model_linhas.last() else "")
        val paramsCho = params.lastOrNull() ?: ""
        if (paramsCho.isNotBlank()) recibo.put("native_params", paramsCho)
        // ADENDO 20 §3 — terminal e LIBERAÇÃO da reserva por token, do
        // Store real (estado, não log): lastTerminalGen = o TOKEN que
        // liberou; owner = -1 = reserva livre; lastTerminal = tipo do ack.
        // ADENDO 21 §3 — a fronteira de APRESENTAÇÃO vem no mesmíssimo
        // relógio do terminal (último_segmento_apresentado_ms).
        val apresentado = fases.toString().split(" ").firstOrNull { it.startsWith("ultimo_segmento_apresentado_ms=") }
        recibo.put("apresentado", apresentado ?: "ausente")
        recibo.put("lastTerminalGen", WorkerReservationStore.lastTerminalGen())
        recibo.put("lastTerminal", WorkerReservationStore.lastTerminal())
        recibo.put("reservaLivre", WorkerReservationStore.owner() == -1L)
        val apresentadoReal = activityUsada?.ultimoApresentadoParaTeste() ?: 0L
        recibo.put("apresentado_real_em_elapsed_ms", apresentadoReal)
        recibo.put("apresentado_durante_ms_total", apresentadoReal - inicioTesteElapsed)
        recibo.put("inicio_teste_em_elapsed_ms", inicioTesteElapsed)

        // ADENDO 26 §2 — RECIBO ASSÍNCRONO da tentativa real (seam de produção
        // ligado ao post executado): último ESPERADO × apresentado e todas as
        // fases na MESMA origem (início do compute), sem somar absolutos com
        // relativos. Escolha por IDENTIDADE (entrada cwav), nunca por "último".
        var reciboApr: WhisperApresentacaoObservador.Recibo? = null
        val limiteApr = System.currentTimeMillis() + 20_000L
        while (System.currentTimeMillis() < limiteApr) {
            reciboApr = activityUsada?.recibosApresentacaoParaTeste()
                ?.filter { it.identidade.inputId.contains("cwav", ignoreCase = true) }
                ?.maxByOrNull { it.identidade.attemptId }
            if (reciboApr?.podeFechar == true) break
            Thread.sleep(100)
        }
        if (reciboApr != null) {
            recibo.put("apresentacao_identidade", reciboApr!!.identidade.shortForm)
            recibo.put("apresentacao_estado", reciboApr!!.estado)
            recibo.put("apresentacao_ultimo_esperado", reciboApr!!.ultimoEsperado)
            recibo.put("apresentacao_ultimo_apresentado", reciboApr!!.ultimoApresentado)
            recibo.put("apresentacao_apresentados", reciboApr!!.apresentados)
            recibo.put("apresentacao_pendentes", reciboApr!!.pendentes)
            recibo.put("apresentacao_recusados", reciboApr!!.recusados)
            recibo.put("apresentacao_origem", reciboApr!!.origem)
            recibo.put("apresentacao_ms", reciboApr!!.apresentacaoMs)
            recibo.put("retorno_ate_apresentacao_ms", reciboApr!!.retornoAteApresentacaoMs)
            recibo.put("marcas_ms", JSONObject(reciboApr!!.marcasMs))
            recibo.put("liberacao_pendente", reciboApr!!.liberacaoPendente)
        } else {
            recibo.put("apresentacao_estado", "AUSENTE")
        }
        // ADENDO 25 §3 — duração COMPARÁVEL no METESMO relógio (elapsed):
        // apresentado − inicio do teste (inclui preparo/abertura/compute);
        // NÃO é apresentado após o retorno — o retorno relatório os em uma
        // duração própria (retornoMs é relativo ao início do compute).
        android.util.Log.i("AD20CFASES", recibo.toString())
        // persistência redundante:
        ctx.getExternalFilesDir("ad20-evidence")?.mkdirs()
        ctx.getExternalFilesDir("ad20-evidence")?.let { d ->
            File(d, "cwav-fases-${System.currentTimeMillis()}.json").writeText(recibo.toString(), Charsets.UTF_8)
        }
        // Espera=/job atribuído apenas pelo delta identificado; assert apenas
        // que compute + parse os números completos existem:
        assertTrue("fases devem conter ultimo_segmento_ms", fases.toString().contains("ultimo_segmento_ms"))
        // ADENDO 22 §3 — exigir VALOR VÁLIDO (não -1), lido DO post executado
        // (campo volátil da Activity, atualizado pela UI thread): a Activity
        // pode ter sido destruída antes da leitura; o valor no recibo então
        // é o observado na linha do terminal.
        val apresentadoLinha = fases.toString().split("\n").firstOrNull {
            it.contains("ultimo_segmento_apresentado_ms=")
        }
        val apresentadoMs = apresentadoLinha
            ?.substringAfter("ultimo_segmento_apresentado_ms=")
            ?.substringBefore(" ")?.toLongOrNull()
        // ADENDO 23 §3 — o valor REAL do post executado vem do volatile da
        // Activity (ultimoApresentadoGlobal) — a linha do worker pode -1.
        assertTrue(
            "apresentação deve ter ocorrido nesta execução (tem $apresentadoReal; início $inicioTesteElapsed; linha worker: $apresentadoLinha)",
            apresentadoReal > 0L && apresentadoReal >= inicioTesteElapsed,
        )
        assertTrue("m2 deve ter retorno_ate_parse_ms e parse_ate_writes_ms", m2.toString().contains("parse_ate_writes_ms"))
        // ADENDO 26 §2 — o fluxo REAL precisa ter registrado a apresentação:
        // teste falha se o post deixar de registrar ou se o recibo for de outra
        // tentativa (identidade é conferida na escolha acima).
        assertNotNull("recibo assíncrono ausente: o fluxo não registrou a apresentação", reciboApr)
        assertTrue(
            "nenhum segmento emitido nesta tentativa (estado=${reciboApr!!.estado})",
            reciboApr!!.ultimoEsperado >= 1L,
        )
        if (reciboApr!!.estado == WhisperApresentacaoObservador.ESTADO_APRESENTADO) {
            assertEquals(
                "último ESPERADO precisa ter sido apresentado",
                reciboApr!!.ultimoEsperado,
                reciboApr!!.ultimoApresentado,
            )
            assertTrue(
                "retorno→apresentação precisa existir no MESMO domínio",
                reciboApr!!.retornoAteApresentacaoMs >= 0L,
            )
        } else {
            // Recusa legítima (destroy/geração) fica REGISTRADA com motivo —
            // nunca vira "pendente de janela".
            assertTrue(
                "estado inesperado sem motivo: ${reciboApr!!.estado}",
                reciboApr!!.estado.startsWith(WhisperApresentacaoObservador.ESTADO_RECUSADO) ||
                    reciboApr!!.estado == WhisperApresentacaoObservador.ESTADO_PENDENTE,
            )
        }
    }

    private fun modelosTurbo(): Boolean {
        val dir = ctx.getExternalFilesDir("whisper_models") ?: return false
        return dir.listFiles()?.any {
            it.isFile && it.name.startsWith("ggml-large-v3-turbo") && it.length() > 1_000_000_000L
        } == true
    }
}
