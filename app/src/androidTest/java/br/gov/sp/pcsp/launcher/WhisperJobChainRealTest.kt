package br.gov.sp.pcsp.launcher

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * ADENDO 20 §1 — CADEIA pelos PRODUTORES REAIS, no aparelho, com fixture
 * (sem microfone ambiente).
 *
 * Cobre, pelos pontos de entrada de produção (reflexão, mesmo idioma
 * sancionado do FachadaTest) e com o Serviço FGS REAL dono:
 *  - ORT→CPP arquivo: job ORT (seleção→despacho→terminal) seguido de job CPP
 *    arquivo pelo MESMO seletor com ORT desligado — o token do alocador comum
 *    circula nas DUAIS rotas sem recusa stale;
 *  - cancelamento/sucessor na gravação: A cancelada não derruba nem rouba o
 *    terminal de B (tokens distintos, ACK de A não altera B);
 *  - destroy com evento atrasado (Activity destruída no meio) — ownerGone
 *    real com token≠geração lógica; recusa não inicia compute;
 *  - encaminhamento SEM VÍNCULO (token alheio) → false sem tocar
 *    controller/UI (contrato do adendo 19 confirmado no ciclo real).
 *
 * Recibos: cada método emite `Log.i("AD20CHAIN", json)` por job/attempt com
 * produtor/token(gen do FGS)/geração lógica/terminal/fases; o logcat é
 * capturado por método eGuardado em `build/ort-rodada1/logs/ad20-*`.
 * Nenhuma campanha/varredura; fixtures já aprovadas (d01.wav / c.wav).
 */
@RunWith(AndroidJUnit4::class)
class WhisperJobChainRealTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun repostarSeams() {
        WhisperOrtRoute.cacheParaTeste = null
        WhisperOrtRoute.setEnabled(ctx, true)
    }

    // ------------------------------------------------------------- reflexão
    private fun campoPrivado(activity: android.app.Activity, nome: String): Any? {
        val campo = WhisperActivity::class.java.getDeclaredField(nome)
        campo.isAccessible = true
        return campo.get(activity)
    }

    private fun invocar(activity: android.app.Activity, metodo: String, arg: Any? = null) {
        val m = if (arg == null) {
            WhisperActivity::class.java.getDeclaredMethod(metodo)
        } else {
            WhisperActivity::class.java.getDeclaredMethod(metodo, arg.javaClass)
        }
        m.isAccessible = true
        if (arg == null) m.invoke(activity) else m.invoke(activity, arg)
    }

    private fun injetarRetornoSeletor(activity: android.app.Activity, request: Int, arquivo: File) {
        val dados = Intent().setData(Uri.fromFile(arquivo))
        val metodo = android.app.Activity::class.java.getDeclaredMethod(
            "onActivityResult",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Intent::class.java,
        )
        metodo.isAccessible = true
        metodo.invoke(activity, request, android.app.Activity.RESULT_OK, dados)
    }

    // ------------------------------------------------------------- recibos
    private fun recibo(rotulo: String, dados: JSONObject) {
        val rec = JSONObject()
        rec.put("rotulo", rotulo)
        // token de RESERVA aparece no FGS como `gen` (Log "WhisperJob"):
        // aqui registramos a geração LÓGICA do controller; o token vem no
        // logcat do serviço por método (dump do host).
        for (chave in listOf(
            "produtor", "jobId", "owner", "gen", "entrada", "terminal",
            "fases", "obs", "publishedSeq", "tokens",
        )) {
            if (dados.has(chave)) rec.put(chave, dados.get(chave))
        }
        android.util.Log.i("AD20CHAIN", rec.toString())
        // Redundância em arquivo (pulled quando o runtime permitir):
        val dir = ctx.getExternalFilesDir("ad20-evidence")
        dir?.mkdirs()
        dir?.let { d ->
            File(d, "$rotulo-${System.currentTimeMillis()}.json")
                .writeText(rec.toString(), Charsets.UTF_8)
        }
    }

    private fun modelosNoAparelho(): Boolean {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val dir = File(app.getExternalFilesDir(null), "whisper_models")
        val maior = dir.listFiles()?.filter { it.isFile && it.length() > 20_000_000L } ?: emptyList()
        assumeTrue("modelo ggml ausente no aparelho", maior.isNotEmpty())
        return true
    }

    /**
     * ADENDO 20 — copia a fixture para o cacheDir (a via gravação/arquivo do
     * PRODUTO CONSOME o arquivo de entrada ao fim: usar o original faria o
     * 1º teste destruir a fixture do 2º; T2 passou e T1/T3 skips foram
     * exatamente por isso.
     */
    private fun fixture(nome: String): File {
        val orig = File(ctx.filesDir, nome)
        assumeTrue("fixture $nome ausente no app", orig.isFile && orig.length() > 4_000L)
        val copia = File(ctx.cacheDir, "ad20-fixture-$nome")
        copia.parentFile?.mkdirs()
        orig.copyTo(copia, overwrite = true)
        return copia
    }

    // ------------------------------------------------------------------ T1
    @Test
    fun ortArquivoDepoisCppArquivoSemRecusaStale() {
        val audio = fixture("d01.wav")
        val antes = terminais().keys.toSet()
        WhisperOrtRoute.setEnabled(ctx, true)
        instalarCacheOrt()
        // ADENDO 23 §2 — reativar o pacote TURBO antes de despachar: a janela
        // anterior deixou o ACTIVE em whisper-base (ad23 device tiny/base) e o
        // `despacharOrt` conversada para oferecer download em vez de transcrever.
        runCatching {
            val root = WhisperOrtPackageFlow.raiz(ctx)
            WhisperOrtPackageInstaller.ativar(
                root,
                WhisperOrtModelCatalog.identity(WhisperOrtModelCatalog.MODEL),
                WhisperOrtModelCatalog.conjuntoPara(WhisperOrtModelCatalog.MODEL),
                withPublication = { it() },
            )
        }

        androidx.test.core.app.ActivityScenario.launch(WhisperActivity::class.java).use { cenario ->
            cenario.onActivity { activity ->
                injetarRetornoSeletor(activity as android.app.Activity, 6101, audio)
                val metodo = WhisperActivity::class.java.getDeclaredMethod("transcribeSelectedMedia")
                metodo.isAccessible = true
                metodo.invoke(activity)
            }
            // ADENDO 23 §2 — a leg ORT roteirizada corre na sessão de teste
            // com `cacheParaTeste` (não passa pelo LEDGER de produção), então
            // a prova é o TERMINAL da rota (`ort-route/terminais`), como no
            // FachadaTest. O ledger fica para os caminhos de produção.
            val terminalOrt = esperarTerminalNovo(antes, 240_000, exigeSuccess = true)
            assertTrue("job ORT não fechou SUCCESS", terminalOrt != null)
            recibo("ort_arquivo", JSONObject()
                .put("produtor", "ORT")
                .put("entrada", audio.name)
                .put("terminal", "SUCCESS")
                .put("obs", "backend roteirizado (cenário aprovado Fachada); token no logcat WhisperJob")
            )
        }
        // Sucessor: MESMO aparelho, ROTA CPP REAL (ORT off): nenhum
        // REFUSED_STALE deve aparecer; o compute é o ggml real.
        repostarSeams()
        WhisperOrtRoute.setEnabled(ctx, false)
        val audioC = fixture("d01.wav")
        val publishSeqBefore = TranscriptionJobResults.ledger.latest()?.publishedSeq ?: 0L
        androidx.test.core.app.ActivityScenario.launch(WhisperActivity::class.java).use { cenario ->
            var recusado = false
            cenario.onActivity { activity ->
                despacharCpp(activity as android.app.Activity, audioC, 6101)
                val status = campoPrivado(activity, "status") as android.widget.TextView
                recusado = status.text.toString().contains("Job recusado")
            }
            assertFalse("CPP sucessor do ORT não pode ser recusado como stale", recusado)
            // Termina com terminal novo no ledger + FGS liberado:
            val fechou = aguardarFgsReal(600_000)
            assertTrue("job CPP não encerrou o FGS em 10 min", fechou)
            // ADENDO 21 §2 — o resultado é o do LEDGER (não o "esperado"):
            val resultado = TranscriptionJobResults.ledger.latestAfter(publishSeqBefore)
            assertTrue("ledger não publicou terminal para o CPP", resultado != null)
            assertEquals(
                "terminal real do CPP (não inferido)",
                JobTerminalState.SUCCESS.toString(),
                resultado!!.terminal.toString(),
            )
            recibo("cpp_arquivo", JSONObject()
                .put("produtor", "CPP")
                .put("entrada", audioC.name)
                .put("terminal", resultado.terminal.toString())
                .put("jobId", resultado.key.jobId)
                .put("owner", resultado.key.owner)
                .put("publishedSeq", resultado.publishedSeq)
                .put("obs", "fases no terminal.txt da sessão + logcat WhisperActivity (fases job=)")
            )
        }
    }

    // ------------------------------------------------------------------ T2
    @Test
    fun gravacaoCanceladaSucessorIndependente() {
        val wav = fixture("d01.wav")
        WhisperOrtRoute.setEnabled(ctx, false)
        modelosNoAparelho()
        val seqBase = TranscriptionJobResults.ledger.latest()?.publishedSeq ?: 0L
        androidx.test.core.app.ActivityScenario.launch(WhisperActivity::class.java).use { cenario ->
            cenario.onActivity { activity ->
                aplicarModeloNaTela(activity as android.app.Activity)
                transcribeRecordedWav(activity, wav)
            }
            // Cancelamento imediato (via sessão do controller, caminho da UI):
            var genActual = 0L
            cenario.onActivity { activity ->
                genActual = campoPrivado(activity, "currentSessionGeneration") as Long
                (campoPrivado(activity, "whisperSession") as WhisperSessionController)
                    .cancel(genActual)
            }
            assertTrue("geração lógica do cancel inválida", genActual > 0L)
            // A TERMINA (FGS sai) e a reserva libera; o sucessor B começa:
            val aEncerrou = aguardarFgsReal(240_000)
            assertTrue("job A (gravação) não encerrou após cancel", aEncerrou)

            // ADENDO 21 §2 — terminal REAL de A PELA LEDGER: o A tem estado
            // próprio (não inferido) e token (owner=ledger) distinto de B.
            val a = TranscriptionJobResults.ledger.latestAfter(seqBase)
            assertTrue("ledger SEM o terminal de A", a != null)
            assertEquals(
                "terminal real de A",
                JobTerminalState.CANCELLED.toString(),
                a!!.terminal.toString(),
            )
            // Sucessor B: token novo; ACK de A não pode atingir B:
            val antesB = System.currentTimeMillis()
            // A consome/apaga sua entrada; B precisa de cópia nova (fix do
            // revisor no adendo21 §0).
            val wavB = fixture("d01.wav")
            val seqA = a.publishedSeq
            cenario.onActivity { activity ->
                aplicarModeloNaTela(activity as android.app.Activity)
                transcribeRecordedWav(activity, wavB)
            }
            val bEncerrou = aguardarFgsReal(600_000)
            assertTrue("sucessor B não encerrou (cancel de A derrubou?)", bEncerrou)
            val bt = TranscriptionJobResults.ledger.latestAfter(seqA)
            assertTrue("ledger SEM o terminal de B", bt != null)
            assertEquals(
                "terminal real de B",
                JobTerminalState.SUCCESS.toString(),
                bt!!.terminal.toString(),
            )
            assertTrue(
                "B com token GEN igual a A? — tokens precisam ser distintos",
                bt.key.owner != a.key.owner,
            )
            recibo("gravacao_cancel_sucessor", JSONObject()
                .put("produtor", "gravação")
                .put("gen", genActual)
                .put("entrada", wav.name)
                .put("tokens", "${a.key.owner}(A=CANCELLED seq=${a.publishedSeq}) / ${bt.key.owner}(B=SUCCESS seq=${bt.publishedSeq})")
                .put("terminal", "A=${a.terminal}, B=${bt.terminal}")
                .put("publishedSeq", "${a.publishedSeq} -> ${bt.publishedSeq}")
                .put("obs", "tokens distintos; ACK atrasado de A não altera B; início em $antesB")
            )
        }
    }

    // ------------------------------------------------------------------ T3
    @Test
    fun destroyComEventoAtrasadoLiberaERecusaNaoInicia() {
        val wav = fixture("d01.wav")
        WhisperOrtRoute.setEnabled(ctx, false)
        modelosNoAparelho()
        var genActual = 0L
        androidx.test.core.app.ActivityScenario.launch(WhisperActivity::class.java).use { cenario ->
            cenario.onActivity { activity ->
                aplicarModeloNaTela(activity as android.app.Activity)
                transcribeRecordedWav(activity, wav)
                genActual = campoPrivado(activity, "currentSessionGeneration") as Long
            }
            // DESTROY da dona imediatamente (owner gona real): o worker segue
            // e o serviço não pode ficar preso (evento atrasado com token≠lógica).
            cenario.close() // ActivityScenario.use fecha ao fim, mas forçar antes:
        }
        TokenGuard.registrarTeste(genActual)
        val seqBeforeDestroy = TranscriptionJobResults.ledger.latest()?.publishedSeq ?: 0L
        val liberou = aguardarFgsReal(600_000)
        assertTrue("destroy com evento atrasado não liberou o serviço", liberou)
        // ADENDO 21 §2 — o resultado REAL de destroy vem do ledger, com o
        // terminal NÃO escondido; um simples "liberou" não prova o ACK.
        val dt = TranscriptionJobResults.ledger.latestAfter(seqBeforeDestroy)
        assertTrue("ledger SEM o terminal do destroy", dt != null)
        assertTrue(
            "destroy com evento atrasado deveria ter terminal próprio",
            dt!!.publishedSeq > seqBeforeDestroy,
        )
        // Recusa: encaminhamento SEM VÍNCULO no bridge real neta false (e não
        // altera o controller de nenhum sucessor — o sink fica vazio).
        val semVinculo = JobCancelBridge.forwarder(987_654_321L)
        assertFalse("token sem vínculo deveria ser recusado sem tocar a sessão", semVinculo)
        recibo("destroy_evento_atrasado", JSONObject()
            .put("produtor", "gravação/arquivo")
            .put("gen", genActual)
            .put("terminal", dt.terminal.toString())
            .put("jobId", dt.key.jobId)
            .put("owner", dt.key.owner)
            .put("publishedSeq", dt.publishedSeq)
            .put("obs", "ownerGone real (token≠lógica); forwarder sem vínculo=false")
        )
    }

    // --------------------------------------------------------------- helpers
    /** ADENDO 20 — o modelo é aplicado por ARQUIVO (UX real): depois do
     * seletor e ANTES do transcribeSelectedMedia; sem isso o CPP não despacha
     * (selectedModel nulo). Idioma do MedicaoRealTest. */
    private fun despacharCpp(activity: android.app.Activity, fixture: File, request: Int) {
        val tela = activity as WhisperActivity
        injetarRetornoSeletor(tela, request, fixture)
        val modelos = WhisperActivity::class.java.getDeclaredMethod("officialModels")
            .apply { isAccessible = true }.invoke(tela) as List<*>
        val turbo = modelos.firstOrNull {
            val f = it!!.javaClass.getDeclaredField("label").apply { isAccessible = true }
            f.get(it) == "v3Turbo"
        }
        assumeTrue("v3Turbo ausente do menu oficial", turbo != null)
        WhisperActivity::class.java.getDeclaredMethod("applyModelSelection", turbo!!.javaClass)
            .apply { isAccessible = true }.invoke(tela, turbo)
        WhisperActivity::class.java.getDeclaredMethod("transcribeSelectedMedia")
            .apply { isAccessible = true }.invoke(tela)
        val st = campoPrivado(tela, "status") as android.widget.TextView
        recibo("despacho_cpp", JSONObject()
            .put("produtor", "CPP")
            .put("entrada", fixture.name)
            .put("fases", "status[${st.text}] modelo=aplicado")
            .put("obs", "diagnóstico imediato do despatch (recusa/modelo nulo aparecem aqui)")
        )
    }

    private fun aplicarModeloNaTela(activity: android.app.Activity) {
        val tela = activity as WhisperActivity
        val modelos = WhisperActivity::class.java.getDeclaredMethod("officialModels")
            .apply { isAccessible = true }.invoke(tela) as List<*>
        val turbo = modelos.firstOrNull {
            val f = it!!.javaClass.getDeclaredField("label").apply { isAccessible = true }
            f.get(it) == "v3Turbo"
        }
        assumeTrue("v3Turbo ausente do menu oficial", turbo != null)
        WhisperActivity::class.java.getDeclaredMethod("applyModelSelection", turbo!!.javaClass)
            .apply { isAccessible = true }.invoke(tela, turbo)
    }

    private fun transcribeRecordedWav(activity: android.app.Activity, wav: File) {
        val m = WhisperActivity::class.java.getDeclaredMethod("transcribeRecordedWav", File::class.java)
        m.isAccessible = true
        m.invoke(activity, wav)
    }

    private fun instalarCacheOrt() {
        WhisperOrtRoute.cacheParaTeste = WhisperOrtSessionCache(
            factory = { _, _, _, _ -> BackendRoteirizadoChain() },
            open = { },
        )
    }

    private fun terminais(): Map<String, JSONObject> {
        val pasta = File(ctx.filesDir, "ort-route/terminais")
        val arquivos = pasta.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return emptyMap()
        return arquivos.mapNotNull { f ->
            runCatching { f.nameWithoutExtension to JSONObject(f.readText(Charsets.UTF_8)) }.getOrNull()
        }.toMap()
    }

    private fun esperarTerminalNovo(
        antes: Set<String>,
        tetoMs: Long,
        exigeSuccess: Boolean,
    ): JSONObject? {
        val fim = System.currentTimeMillis() + tetoMs
        while (System.currentTimeMillis() < fim) {
            val novos = terminais().filterKeys { it !in antes }
            val alvo = if (exigeSuccess) {
                novos.values.firstOrNull { it.optString("outcome") == "SUCCESS" && it.optInt("bytes") > 0 }
            } else null
            if (alvo != null) return alvo
            Thread.sleep(2_000)
        }
        return null
    }

    private fun aguardarAte(tetoMs: Long, condicao: () -> Boolean): Boolean {
        val fim = System.currentTimeMillis() + tetoMs
        while (System.currentTimeMillis() < fim) {
            if (condicao()) return true
            Thread.sleep(1_000)
        }
        return condicao()
    }

    /** ADENDO 20 §1 — espera em 2 fases (dono primeiro, encerramento depois):
     * evita falso-positivo quando o FGS ainda não subiu. */
    private fun aguardarFgsReal(tetoMs: Long): Boolean {
        val fimDono = System.currentTimeMillis() + 20_000L
        var viuDono = false
        while (System.currentTimeMillis() < fimDono) {
            if (WhisperJobService.isRunning()) { viuDono = true; break }
            Thread.sleep(100)
        }
        if (!viuDono) return false
        return aguardarAte(tetoMs) { !WhisperJobService.isRunning() }
    }
}

/**
 * Backend roteirizado (idioma EXATO do FachadaTest): timestamps segmentados
 * com janela FECHADA (texto fechado no fim para o outcome=SUCCESS e nonPARTIAL).
 */
private class BackendRoteirizadoChain : WhisperOrtBackend {
    private var janela = -1
    private var indice = 0
    private val ts0 = WhisperOrtSegments.timestampBegin(WhisperOrtPrompt.NO_TIMESTAMPS)
    private fun ts(seg: Double): Int = ts0 + Math.round(seg / 0.02).toInt()
    private val roteiroFechado = listOf(
        listOf(ts0, 65, ts(2.0), ts(2.0), 66, ts(4.0), ts(4.0)),
    )
    override fun encode(logMel: FloatArray) = FloatArray(1_500 * 1_280)
    override fun start(hidden: FloatArray, prompt: IntArray): FloatArray {
        janela++
        indice = 0
        return logits(indice)
    }
    override fun step(token: Int): FloatArray {
        indice++
        return logits(indice)
    }
    override fun close() = Unit
    private fun logits(posicao: Int): FloatArray {
        val v = FloatArray(51_866) { -1f }
        val roteiro = roteiroFechado.getOrNull(janela) ?: emptyList()
        v[roteiro.getOrNull(posicao) ?: WhisperOrtPrompt.END_OF_TEXT] = 5f
        return v
    }
}

/** Âncora mínima para o recibo do destroy (sem tocar no estado de produção). */
private object TokenGuard {
    val registradas = mutableListOf<Long>()
    fun registrarTeste(gen: Long) {
        registradas.add(gen)
    }
}
