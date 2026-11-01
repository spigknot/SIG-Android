package br.gov.sp.pcsp.launcher

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * ADENDO 26 §1 — CADEIA INTEGRADA no flavor de laboratório, com fonte PCM
 * INJETADA (sem microfone ambiente) e identidades capturadas ANTES de cada
 * ação: job/attempt/entrada/geração lógica/owner do produtor/token de reserva.
 *
 * Cenários (cada um com recibo JSON em `ad26-evidence`):
 *  1. LIVE injetado (`d01.pcm` cru 16 kHz s16le): começa, drena e FECHA sem
 *     AudioRecord; terminal do ledger e identidade reais.
 *  2. CPP arquivo→gravação SEM ORT: gravação com PCM injetado, WAV escrito e
 *     transcrito pelo fluxo do produto.
 *  3. ORT→CPP arquivo→gravação→LIVE→arquivo: cadeia completa no MESMO run.
 *  4. Evento atrasado de A (terminal/cancel) contra B VIVO: B preservado.
 *
 * Só roda na variante `whisperlab` (a fonte injetada é seam de lab); na
 * variante normal cada método fica PENDENTE via `assumeTrue`.
 */
@RunWith(AndroidJUnit4::class)
class WhisperCadeiaIntegradaAd26Test {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val ehLab: Boolean = runCatching {
        Class.forName("br.gov.sp.pcsp.launcher.WhisperLabDrive")
        true
    }.getOrDefault(false)

    private lateinit var labLog: File

    // ------------------------------------------------------------- seams lab
    private fun setDrive(nome: String, valor: Any?) {
        val c = Class.forName("br.gov.sp.pcsp.launcher.WhisperLabDrive")
        val campo = c.getDeclaredField(nome).apply { isAccessible = true }
        val inst = runCatching {
            c.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
        }.getOrNull()
        // Propriedade de `object` sem @JvmField é campo de INSTANCE.
        if (inst != null && !java.lang.reflect.Modifier.isStatic(campo.modifiers)) {
            campo.set(inst, valor)
        } else {
            campo.set(null, valor)
        }
    }

    @Before
    fun preparar() {
        assumeTrue("variante de lab ausente (produção): cenário PENDENTE", ehLab)
        labLog = File(ctx.filesDir, "ad26-lab-${System.currentTimeMillis()}.jsonl")
        val c = Class.forName("br.gov.sp.pcsp.launcher.WhisperLabDrive")
        // `object` Kotlin: reset() é método de INSTANCE (não estático).
        val inst = c.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
        c.getDeclaredMethod("reset").apply { isAccessible = true }.invoke(inst)
        setDrive("active", true)
        setDrive("runId", "ad26")
        setDrive("scenario", "cadeia-integrada")
        setDrive("logFile", labLog)
    }

    @After
    fun restaurar() {
        if (!ehLab) return
        runCatching { WhisperOrtRoute.cacheParaTeste = null }
        runCatching { WhisperOrtRoute.setEnabled(ctx, true) }
        // ADENDO 26 §1 — RESTAURAR os seams ao fim (nao herdar fonte, speed,
        // janela/contexto, roteiro de clique nem atraso para a proxima celula).
        setDrive("active", false)
        runCatching {
            val c = Class.forName("br.gov.sp.pcsp.launcher.WhisperLabDrive")
            val inst = c.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
            c.getDeclaredMethod("reset").apply { isAccessible = true }.invoke(inst)
        }
    }

    /** Leitura de campo do drive (INSTANCE quando nao-estatico). */
    private fun getDrive(nome: String): Any? {
        val c = Class.forName("br.gov.sp.pcsp.launcher.WhisperLabDrive")
        val campo = c.getDeclaredField(nome).apply { isAccessible = true }
        val inst = runCatching {
            c.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
        }.getOrNull()
        return if (inst != null && !java.lang.reflect.Modifier.isStatic(campo.modifiers)) {
            campo.get(inst)
        } else {
            campo.get(null)
        }
    }

    // ------------------------------------------------------------- helpers
    private fun campo(activity: android.app.Activity, nome: String): Any? =
        WhisperActivity::class.java.getDeclaredField(nome).apply { isAccessible = true }.get(activity)

    private fun invocar(activity: android.app.Activity, metodo: String): Any? =
        WhisperActivity::class.java.getDeclaredMethod(metodo).apply { isAccessible = true }
            .invoke(activity)

    private fun injetarArquivo(activity: android.app.Activity, arquivo: File, request: Int) {
        val dados = Intent().setData(Uri.fromFile(arquivo))
        val m = android.app.Activity::class.java.getDeclaredMethod(
            "onActivityResult",
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Intent::class.java,
        )
        m.isAccessible = true
        m.invoke(activity, request, android.app.Activity.RESULT_OK, dados)
    }

    private fun aplicarModelo(activity: android.app.Activity, label: String): Boolean {
        val modelos = WhisperActivity::class.java.getDeclaredMethod("officialModels")
            .apply { isAccessible = true }.invoke(activity) as List<*>
        val modelo = modelos.firstOrNull {
            val f = it!!.javaClass.getDeclaredField("label").apply { isAccessible = true }
            f.get(it) == label
        } ?: return false
        WhisperActivity::class.java.getDeclaredMethod("applyModelSelection", modelo.javaClass)
            .apply { isAccessible = true }.invoke(activity, modelo)
        return true
    }

    private fun fixture(nome: String): File {
        val original = File(ctx.filesDir, nome)
        assumeTrue("fixture $nome ausente no lab", original.isFile && original.length() > 4_000L)
        // Cópia por cenário: a via de arquivo/gravação CONSOME a entrada.
        val copia = File(ctx.cacheDir, "ad26-$nome")
        original.copyTo(copia, overwrite = true)
        return copia
    }

    private fun aguardarFgs(tetoMs: Long): Boolean {
        val fimDono = System.currentTimeMillis() + 20_000L
        var viu = false
        while (System.currentTimeMillis() < fimDono) {
            if (WhisperJobService.isRunning()) { viu = true; break }
            Thread.sleep(100)
        }
        if (!viu) return false
        val fim = System.currentTimeMillis() + tetoMs
        while (System.currentTimeMillis() < fim) {
            if (!WhisperJobService.isRunning()) return true
            Thread.sleep(500)
        }
        return !WhisperJobService.isRunning()
    }

    private fun recibo(rotulo: String, dados: JSONObject) {
        dados.put("rotulo", rotulo)
        dados.put("lab", ehLab)
        dados.put("lab_log", labLog.absolutePath)
        android.util.Log.i("AD26CADEIA", dados.toString())
        val dir = ctx.getExternalFilesDir("ad26-evidence")
        dir?.mkdirs()
        dir?.let { d ->
            File(d, "$rotulo-${System.currentTimeMillis()}.json")
                .writeText(dados.toString(), Charsets.UTF_8)
        }
    }

    private fun linhasLab(evento: String): List<JSONObject> {
        if (!labLog.isFile) return emptyList()
        return labLog.readLines(Charsets.UTF_8).mapNotNull { linha ->
            runCatching { JSONObject(linha) }.getOrNull()?.takeIf { it.optString("event") == evento }
        }
    }

    // ------------------------------------------------------------------ 1
    @Test
    fun liveInjetadoFechaSemMicrofone() {
        // d01sil = d01.pcm + 3 s de silencio final (prefixo de fala BYTE A BYTE
        // identico). Sem silencio, o ultimo trecho cai inteiro no CONTEXTO da
        // janela e nao tem audio proprio para reparo (regiao nao confirmada).
        val pcm = fixture("d01sil.pcm")
        setDrive("livePcmPath", pcm.absolutePath)
        setDrive("livePcmSpeed", 1.0)
        val seqBase = TranscriptionJobResults.ledger.latest()?.publishedSeq ?: 0L
        var geracao = 0L
        var dono = false

        ActivityScenario.launch(WhisperActivity::class.java).use { cenario ->
            cenario.onActivity { activity ->
                assumeTrue("modelo base ausente no lab", aplicarModelo(activity, "base"))
                val antes = JSONObject()
                    .put("auto_roteiro_desarmado", (getDrive("clickLiveStartMs") as? Long ?: 0L) < 0L)
                    .put("entrada", pcm.name)
                    .put("entrada_sha256", sha256(pcm))
                    .put("pcm_bytes", pcm.length())
                    .put("live_speed", 1.0)
                    .put("published_seq_antes", seqBase)
                recibo("live_antes", antes)
                invocar(activity, "startLiveMicTranscription")
                geracao = campo(activity, "currentSessionGeneration") as Long
                // Deixa a FONTE terminar (EOF) — é o desfecho do fluxo ao vivo
                // com o áudio INTEIRO; a parada pelo botão é registrada como
                // CANCELLED parcial no relatório, não substitui esta leg.
            }
            dono = aguardarFgs(600_000)
        }
        val resultado = TranscriptionJobResults.ledger.latestAfter(seqBase)
        val fontes = linhasLab("injected_live_source")
        val stats = linhasLab("pcm_source_stats")
        recibo("live_depois", JSONObject()
            .put("geracao_logica", geracao)
            .put("fgs_encerrou", dono)
            .put("terminal", resultado?.terminal?.toString())
            .put("job_id", resultado?.key?.jobId)
            .put("owner", resultado?.key?.owner)
            .put("attempt", resultado?.key?.attemptId)
            .put("published_seq", resultado?.publishedSeq)
            .put("chars", resultado?.text?.length)
            .put("fonte_injetada_sha256", fontes.firstOrNull()?.optString("sha256"))
            .put("fonte_esperada_sha256", sha256(pcm))
            .put("pcm_stats", stats.firstOrNull()?.toString())
        )
        assertTrue("FGS do live não encerrou (fonte injetada não finalizou)", dono)
        assertNotNull("ledger não publicou o terminal do live", resultado)
        // Desfecho REAL observado (SUCCESS/CANCELLED/ERROR): a política do live
        // decide por integralidade do texto; registrar o valor real (e o motivo
        // em `live_terminal_tipado`) é a prova — nunca "pendente de janela".
        val tipado = linhasLab("live_terminal_tipado").lastOrNull()
        recibo("live_terminal", JSONObject()
            .put("terminal", resultado!!.terminal.toString())
            .put("motivo", tipado?.optString("motivo"))
            .put("integral", tipado?.optBoolean("integral"))
            .put("regioes_nao_confirmadas", tipado?.optInt("regioes_promovidas_nao_confirmadas"))
            .put("identidade", resultado.key.shortForm))
        // ADENDO 27 §1 — o live fecha SUCCESS (texto íntegro): a região da
        // fronteira não fica mais retida sem áudio próprio.
        assertEquals(
            "terminal do live (esperado SUCCESS com texto integral)",
            "SUCCESS",
            resultado!!.terminal.toString(),
        )
        assertEquals(
            "texto do live precisa ser integral",
            true,
            tipado?.optBoolean("integral"),
        )
        assertEquals(
            "identidade do job do live incoerente com a geração lógica",
            geracao,
            resultado.key.owner,
        )
        assertTrue("fonte injetada não registrada no lab", fontes.isNotEmpty())
        assertEquals(
            "PCM injetado diferente do fixture (identidade da fonte)",
            sha256(pcm),
            fontes.first().optString("sha256"),
        )
        assertTrue("pcm_source_stats ausente (fonte não foi consumida até o fim)", stats.isNotEmpty())
    }

    // ------------------------------------------------------------------ 1b
    @Test
    fun liveInjetadoContextoCurtoFecha() {
        // Config de LAB declarada: janela FIXA 4 s com contexto 0,5 s
        // (`liveContextMs>=0` troca a estratégia no seam). Objetivo: dar áudio
        // PRÓPRIO à última região para que ela possa ser confirmada/reparada,
        // isolando a política de integralidade do desenho VAD padrão.
        val pcm = fixture("d01sil.pcm")
        setDrive("livePcmPath", pcm.absolutePath)
        setDrive("livePcmSpeed", 1.0)
        setDrive("liveContextMs", 500L)
        val seqBase = TranscriptionJobResults.ledger.latest()?.publishedSeq ?: 0L
        var geracao = 0L
        var dono = false
        ActivityScenario.launch(WhisperActivity::class.java).use { cenario ->
            cenario.onActivity { activity ->
                assumeTrue("modelo base ausente no lab", aplicarModelo(activity, "base"))
                invocar(activity, "startLiveMicTranscription")
                geracao = campo(activity, "currentSessionGeneration") as Long
            }
            dono = aguardarFgs(600_000)
        }
        val resultado = TranscriptionJobResults.ledger.latestAfter(seqBase)
        val tipado = linhasLab("live_terminal_tipado").lastOrNull()
        recibo("live_contexto_curto", JSONObject()
            .put("config", "fixed unique=4000ms contexto=500ms (liveContextMs)")
            .put("geracao_logica", geracao)
            .put("fgs_encerrou", dono)
            .put("terminal", resultado?.terminal?.toString())
            .put("motivo", tipado?.optString("motivo"))
            .put("chars", resultado?.text?.length)
            .put("job_id", resultado?.key?.jobId)
            .put("owner", resultado?.key?.owner))
        assertTrue("FGS do live (contexto curto) não encerrou", dono)
        assertNotNull("ledger sem terminal do live (contexto curto)", resultado)
        assertEquals("geração lógica incoerente", geracao, resultado!!.key.owner)
    }

    // ------------------------------------------------------------------ 2
    @Test
    fun cppArquivoDepoisGravacaoInjetadaSemOrt() {
        WhisperOrtRoute.setEnabled(ctx, false)
        val audio = fixture("d01.wav")
        val seqBase = TranscriptionJobResults.ledger.latest()?.publishedSeq ?: 0L
        var jobCpp: JSONObject? = null

        ActivityScenario.launch(WhisperActivity::class.java).use { cenario ->
            cenario.onActivity { activity ->
                assumeTrue("modelo base ausente no lab", aplicarModelo(activity, "base"))
                injetarArquivo(activity, audio, 6101)
                // A injeção atualiza hasMedia e limpa a seleção: reaplicar o
                // modelo DEPOIS do arquivo (mesma ordem do despacho do ad20).
                assumeTrue("modelo base ausente no lab", aplicarModelo(activity, "base"))
                invocar(activity, "transcribeSelectedMedia")
                recibo("cpp_arquivo_antes", JSONObject()
                    .put("entrada", audio.name)
                    .put("sha256", sha256(audio))
                    .put("published_seq_antes", seqBase))
            }
            val fechou = aguardarFgs(600_000)
            val r = TranscriptionJobResults.ledger.latestAfter(seqBase)
            jobCpp = JSONObject()
                .put("terminal", r?.terminal?.toString())
                .put("job_id", r?.key?.jobId)
                .put("owner", r?.key?.owner)
                .put("attempt", r?.key?.attemptId)
                .put("chars", r?.text?.length)
                .put("fgs_encerrou", fechou)
            recibo("cpp_arquivo_depois", jobCpp)
            assertTrue("job CPP de arquivo não fechou o FGS", fechou)
            assertNotNull("ledger sem terminal do CPP arquivo", r)
            assertEquals("terminal do CPP arquivo", "SUCCESS", r!!.terminal.toString())
        }

        // Gravação com PCM INJETADO (sem ORT): o fluxo do produto escreve o WAV
        // e transcreve automaticamente no stop.
        val pcmGrav = fixture("d01.pcm")
        setDrive("recordPcmPath", pcmGrav.absolutePath)
        setDrive("recordPcmSpeed", 4.0)
        val seqGrav = TranscriptionJobResults.ledger.latest()?.publishedSeq ?: 0L
        var geracaoGrav = 0L
        ActivityScenario.launch(WhisperActivity::class.java).use { cenario ->
            cenario.onActivity { activity ->
                assumeTrue("modelo base ausente no lab", aplicarModelo(activity, "base"))
                invocar(activity, "startWhiteRecording")
                geracaoGrav = campo(activity, "currentSessionGeneration") as Long
                recibo("gravacao_antes", JSONObject()
                    .put("pcm", pcmGrav.name)
                    .put("sha256", sha256(pcmGrav))
                    .put("speed", 4.0)
                    .put("published_seq_antes", seqGrav))
            }
            // Consumo da fonte (17,15 s / 4x ≈ 4,3 s) na THREAD DO TESTE.
            Thread.sleep(8_000)
            cenario.onActivity { activity -> invocar(activity, "stopWhiteRecording") }
            val fechou = aguardarFgs(600_000)
            val r = TranscriptionJobResults.ledger.latestAfter(seqGrav)
            recibo("gravacao_depois", JSONObject()
                .put("geracao_logica", geracaoGrav)
                .put("terminal", r?.terminal?.toString())
                .put("job_id", r?.key?.jobId)
                .put("owner", r?.key?.owner)
                .put("attempt", r?.key?.attemptId)
                .put("chars", r?.text?.length)
                .put("fgs_encerrou", fechou)
                .put("cpp_job_anterior", jobCpp?.opt("job_id"))
                .put("cpp_owner_anterior", jobCpp?.opt("owner")))
            assertTrue("gravação injetada não fechou o FGS", fechou)
            assertNotNull("ledger sem terminal da gravação", r)
            assertEquals("terminal da gravação", "SUCCESS", r!!.terminal.toString())
            assertFalse(
                "gravação reusou a identidade do job CPP anterior",
                r.key.jobId == jobCpp?.optLong("job_id") && r.key.owner == jobCpp?.optLong("owner"),
            )
        }
    }

    // ------------------------------------------------------------------ 3
    @Test
    fun cadeiaOrtCppGravacaoLiveArquivo() {
        val audio = fixture("d01.wav")
        val pcm = fixture("d01sil.pcm")
        val passos = org.json.JSONArray()

        // (1) ORT arquivo — backend roteirizado (controle aprovado da Fachada).
        WhisperOrtRoute.setEnabled(ctx, true)
        WhisperOrtRoute.cacheParaTeste = WhisperOrtSessionCache(
            factory = { _, _, _, _ -> BackendRoteirizadoAd26() },
            open = { },
        )
        runCatching {
            val root = WhisperOrtPackageFlow.raiz(ctx)
            WhisperOrtPackageInstaller.ativar(
                root,
                WhisperOrtModelCatalog.identity(WhisperOrtModelCatalog.MODEL),
                WhisperOrtModelCatalog.conjuntoPara(WhisperOrtModelCatalog.MODEL),
                withPublication = { it() },
            )
        }
        val terminaisAntes = File(ctx.filesDir, "ort-route/terminais")
            .listFiles { f -> f.isFile && f.name.endsWith(".json") }?.map { it.name }?.toSet() ?: emptySet()
        ActivityScenario.launch(WhisperActivity::class.java).use { cenario ->
            cenario.onActivity { activity ->
                injetarArquivo(activity, audio, 6101)
                invocar(activity, "transcribeSelectedMedia")
            }
            val fim = System.currentTimeMillis() + 240_000L
            var terminal: JSONObject? = null
            while (System.currentTimeMillis() < fim) {
                terminal = File(ctx.filesDir, "ort-route/terminais")
                    .listFiles { f -> f.isFile && f.name.endsWith(".json") }?.asSequence()
                    ?.filter { it.name !in terminaisAntes }
                    ?.mapNotNull { runCatching { JSONObject(it.readText()) }.getOrNull() }
                    ?.firstOrNull { it.optString("outcome") == "SUCCESS" }
                if (terminal != null) break
                Thread.sleep(1_000)
            }
            passos.put(JSONObject().put("passo", "ort_arquivo")
                .put("terminal", terminal?.optString("outcome"))
                .put("entrada", audio.name)
                .put("sha256", sha256(audio)))
            assertNotNull("leg ORT não fechou SUCCESS", terminal)
        }

        // (2) CPP arquivo — rota real, ORT desligado.
        WhisperOrtRoute.cacheParaTeste = null
        WhisperOrtRoute.setEnabled(ctx, false)
        val seqCpp = TranscriptionJobResults.ledger.latest()?.publishedSeq ?: 0L
        ActivityScenario.launch(WhisperActivity::class.java).use { cenario ->
            cenario.onActivity { activity ->
                assumeTrue("modelo base ausente no lab", aplicarModelo(activity, "base"))
                injetarArquivo(activity, audio, 6101)
                // A injeção atualiza hasMedia e limpa a seleção: reaplicar o
                // modelo DEPOIS do arquivo (mesma ordem do despacho do ad20).
                assumeTrue("modelo base ausente no lab", aplicarModelo(activity, "base"))
                invocar(activity, "transcribeSelectedMedia")
            }
            val fechou = aguardarFgs(600_000)
            val r = TranscriptionJobResults.ledger.latestAfter(seqCpp)
            passos.put(JSONObject().put("passo", "cpp_arquivo")
                .put("terminal", r?.terminal?.toString())
                .put("job_id", r?.key?.jobId)
                .put("owner", r?.key?.owner)
                .put("fgs_encerrou", fechou))
            assertTrue("CPP arquivo não fechou o FGS", fechou)
            assertEquals("terminal do CPP arquivo", "SUCCESS", r!!.terminal.toString())
        }

        // (3) GRAVAÇÃO com PCM injetado.
        setDrive("recordPcmPath", pcm.absolutePath)
        setDrive("recordPcmSpeed", 4.0)
        val seqGrav = TranscriptionJobResults.ledger.latest()?.publishedSeq ?: 0L
        ActivityScenario.launch(WhisperActivity::class.java).use { cenario ->
            cenario.onActivity { activity ->
                assumeTrue("modelo base ausente no lab", aplicarModelo(activity, "base"))
                invocar(activity, "startWhiteRecording")
            }
            // Consumo do PCM na THREAD DO TESTE (nunca na main/UI).
            Thread.sleep(8_000)
            cenario.onActivity { activity -> invocar(activity, "stopWhiteRecording") }
            val fechou = aguardarFgs(600_000)
            val r = TranscriptionJobResults.ledger.latestAfter(seqGrav)
            passos.put(JSONObject().put("passo", "gravacao_injetada")
                .put("terminal", r?.terminal?.toString())
                .put("job_id", r?.key?.jobId)
                .put("owner", r?.key?.owner)
                .put("fgs_encerrou", fechou))
            assertTrue("gravação não fechou o FGS", fechou)
            assertEquals("terminal da gravação", "SUCCESS", r!!.terminal.toString())
        }

        // (4) LIVE com PCM injetado (mesma fonte, sem microfone).
        setDrive("recordPcmPath", null)
        setDrive("livePcmPath", pcm.absolutePath)
        setDrive("livePcmSpeed", 1.0)
        val seqLive = TranscriptionJobResults.ledger.latest()?.publishedSeq ?: 0L
        ActivityScenario.launch(WhisperActivity::class.java).use { cenario ->
            cenario.onActivity { activity ->
                assumeTrue("modelo base ausente no lab", aplicarModelo(activity, "base"))
                invocar(activity, "startLiveMicTranscription")
            }
            val fechou = aguardarFgs(600_000)
            val r = TranscriptionJobResults.ledger.latestAfter(seqLive)
            passos.put(JSONObject().put("passo", "live_injetado")
                .put("terminal", r?.terminal?.toString())
                .put("job_id", r?.key?.jobId)
                .put("owner", r?.key?.owner)
                .put("chars", r?.text?.length)
                .put("fgs_encerrou", fechou))
            assertTrue("live não fechou o FGS", fechou)
            assertNotNull("ledger sem terminal do live", r)
            assertEquals("live da cadeia precisa SUCCESS", "SUCCESS", r!!.terminal.toString())
        }

        // (5) ARQUIVO de volta (retorno ao arquivo depois do live).
        setDrive("livePcmPath", null)
        val audioFinal = fixture("d01.wav")
        val seqFinal = TranscriptionJobResults.ledger.latest()?.publishedSeq ?: 0L
        ActivityScenario.launch(WhisperActivity::class.java).use { cenario ->
            cenario.onActivity { activity ->
                assumeTrue("modelo base ausente no lab", aplicarModelo(activity, "base"))
                injetarArquivo(activity, audioFinal, 6101)
                assumeTrue("modelo base ausente no lab", aplicarModelo(activity, "base"))
                invocar(activity, "transcribeSelectedMedia")
            }
            val fechou = aguardarFgs(600_000)
            val r = TranscriptionJobResults.ledger.latestAfter(seqFinal)
            passos.put(JSONObject().put("passo", "arquivo_final")
                .put("terminal", r?.terminal?.toString())
                .put("job_id", r?.key?.jobId)
                .put("owner", r?.key?.owner)
                .put("fgs_encerrou", fechou))
            assertTrue("arquivo final não fechou o FGS", fechou)
            assertEquals("terminal do arquivo final", "SUCCESS", r!!.terminal.toString())
        }

        val jobs = (0 until passos.length()).map { passos.getJSONObject(it) }
        recibo("cadeia_completa", JSONObject()
            .put("passos", passos)
            .put("jobs_distintos", jobs.map { "${it.opt("job_id")}/${it.opt("owner")}" }.distinct().size)
            .put("ordem", jobs.joinToString(">") { it.optString("passo") })
        )
        assertEquals("a cadeia deveria ter 5 jobs com identidade própria", 5, jobs.size)
        // ADENDO 27 §1 — TODAS as etapas com sucesso funcional (sem ERROR).
        val naoSucesso = jobs.filter { it.optString("terminal") != "SUCCESS" }
        assertTrue("etapas sem SUCCESS na cadeia: $naoSucesso", naoSucesso.isEmpty())
    }

    // ------------------------------------------------------------------ 4
    @Test
    fun eventoAtrasadoDeAContraBVivo() {
        WhisperOrtRoute.setEnabled(ctx, false)
        val audio = fixture("d01.wav")
        val seqA = TranscriptionJobResults.ledger.latest()?.publishedSeq ?: 0L
        var genA = 0L
        var ownerA = 0L
        ActivityScenario.launch(WhisperActivity::class.java).use { cenario ->
            cenario.onActivity { activity ->
                assumeTrue("modelo base ausente no lab", aplicarModelo(activity, "base"))
                injetarArquivo(activity, audio, 6101)
                // A injeção atualiza hasMedia e limpa a seleção: reaplicar o
                // modelo DEPOIS do arquivo (mesma ordem do despacho do ad20).
                assumeTrue("modelo base ausente no lab", aplicarModelo(activity, "base"))
                invocar(activity, "transcribeSelectedMedia")
                genA = campo(activity, "currentSessionGeneration") as Long
            }
            assertTrue("job A não fechou", aguardarFgs(600_000))
            val a = TranscriptionJobResults.ledger.latestAfter(seqA)
            assertNotNull("ledger sem A", a)
            assertEquals("terminal de A", "SUCCESS", a!!.terminal.toString())
            ownerA = a.key.owner
        }

        // B VIVO começa depois de A terminar: eventos atrasados de A não podem
        // mover o terminal de B.
        val pcm = fixture("d01sil.pcm")
        setDrive("livePcmPath", pcm.absolutePath)
        setDrive("livePcmSpeed", 1.0)
        val seqB = TranscriptionJobResults.ledger.latest()?.publishedSeq ?: 0L
        var bViuTerminalAtrasado = false
        ActivityScenario.launch(WhisperActivity::class.java).use { cenario ->
            cenario.onActivity { activity ->
                assumeTrue("modelo base ausente no lab", aplicarModelo(activity, "base"))
                invocar(activity, "startLiveMicTranscription")
            }
            // Enquanto B está vivo: terminal/cancel ATRASADOS de A.
            Thread.sleep(1_500)
            WhisperJobService.finishJob(ownerA, "CANCELLED")
            val encaminhado = JobCancelBridge.forwarder(ownerA)
            // B ainda deve estar rodando (ou já ter fechado por EOF — o que vale
            // é o TERMINAL de B, verificado abaixo).
            bViuTerminalAtrasado = WhisperJobService.isRunning()
            assertTrue("aguardando B fechar", aguardarFgs(600_000))
            val b = TranscriptionJobResults.ledger.latestAfter(seqB)
            recibo("atrasado_A_contra_B", JSONObject()
                .put("gen_a", genA)
                .put("owner_a", ownerA)
                .put("terminal_a", "SUCCESS")
                .put("forwarder_aceitou_token_expirado", encaminhado)
                .put("b_rodando_apos_evento", bViuTerminalAtrasado)
                .put("b_terminal", b?.terminal?.toString())
                .put("b_job_id", b?.key?.jobId)
                .put("b_owner", b?.key?.owner)
                .put("b_chars", b?.text?.length))
            assertNotNull("ledger sem B", b)
            // B preservado: identidade própria e desfecho NÃO-CANCELLED. Se o
            // terminal atrasado de A (CANCELLED) tivesse vazado, B seria
            // CANCELLED — o desfecho de B é o do PRÓPRIO live (política de
            // integralidade pode dar ERROR; aqui isso não vem de A).
            assertTrue(
                "terminal de B fora do observável do próprio live: ${b!!.terminal}",
                b.terminal.toString() in listOf("SUCCESS", "ERROR"),
            )
            assertFalse(
                "terminal atrasado de A vazou para B (CANCELLED)",
                b.terminal.toString() == "CANCELLED",
            )
            assertEquals("B precisa manter o próprio job", false, b.key.jobId == 0L)
            assertTrue("B sem identidade de owner", b.key.owner > 0L)
            assertFalse("token expirado de A não pode ser aceito", encaminhado)
        }
    }

    private fun sha256(file: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { ins ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

/**
 * Backend roteirizado (mesmo idioma aprovado da Fachada/ad20): timestamps
 * fechados no fim para `outcome=SUCCESS` e texto não vazio.
 */
private class BackendRoteirizadoAd26 : WhisperOrtBackend {
    private var janela = -1
    private var indice = 0
    private val ts0 = WhisperOrtSegments.timestampBegin(WhisperOrtPrompt.NO_TIMESTAMPS)
    private fun ts(seg: Double): Int = ts0 + Math.round(seg / 0.02).toInt()
    private val roteiroFechado = listOf(listOf(ts0, 65, ts(2.0), ts(2.0), 66, ts(4.0), ts(4.0)))

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
