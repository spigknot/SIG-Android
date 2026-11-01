package br.gov.sp.pcsp.launcher

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Adendo 16 §3/§4/§5 — medição REAL no aparelho (modelos verdadeiros, sem backend
 * controlado): ORT d01 × {1,4} threads, ORT c.wav (cauda 30,016 s) e CPP turbo
 * d01 × {1,4} + c.wav (fases do 99%).
 *
 * Cada célula registra via Logcat (`MEDICAO-ORT-16`): motor, entrada, threads
 * pedidas/efetivas, ms totais e cauda do texto. Nada é commitado: o aparelho é
 * o instrumento e o log é a evidência. Entradas pessoais (c.wav) nunca entram
 * no repo; o teste pula (`assumeTrue`) sem elas.
 */
@RunWith(AndroidJUnit4::class)
class WhisperOrtMedicaoRealTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun terminais(): Map<String, JSONObject> {
        val pasta = File(ctx.filesDir, "ort-route/terminais")
        val arquivos = pasta.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return emptyMap()
        return arquivos.mapNotNull { f ->
            runCatching { f.nameWithoutExtension to JSONObject(f.readText(Charsets.UTF_8)) }.getOrNull()
        }.toMap()
    }

    private fun aguardarOrt(antes: Set<String>, timeoutMs: Long, jobId: String? = null): JSONObject? {
        // Adendo 17 §4 — casa o terminal com o job devolvido (nunca qualquer
        // SUCCESS novo). Poll curto: a fase é medida no produto, não no poll.
        val limite = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < limite) {
            val novo = terminais().filterKeys { it !in antes }.values
                .firstOrNull {
                    it.optString("outcome") == "SUCCESS" &&
                        (jobId == null || it.optString("job") == jobId)
                }
            if (novo != null) return novo
            Thread.sleep(2_000)
        }
        return null
    }

    private fun campoPrivado(activity: Activity, nome: String): Any? {
        val campo = WhisperActivity::class.java.getDeclaredField(nome)
        campo.isAccessible = true
        return campo.get(activity)
    }

    private fun raizesSessao(): List<File> {
        val ext = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        val appRoot = File(ext, "Whisper")
        val pubRoot = File(android.os.Environment.getExternalStorageDirectory(), "SIG/Whisper")
        return listOf(appRoot, pubRoot).filter { it.isDirectory }
    }

    /**
     * Terminal da sessão deste job com as fases §4 (prova de que o compute
     * correu de verdade com primeiro/último 99 medidos por arquivo).
     *
     * Correlacao EXATA (adendo 17 §4): geracao da sessao/job + threads
     * pedidas + nome do arquivo + fases. Nunca aceita sessao anterior como
     * prova (tempo sozinho nao basta: sessoes antigas com o mesmo arquivo
     * e threads passariam no filtro temporal).
     */
    private fun terminalComFases(
        inicioMs: Long,
        threads: Int,
        entradaNome: String,
        geracao: Long? = null,
    ): File? =
        raizesSessao().asSequence()
            .flatMap { r ->
                runCatching {
                    r.walkTopDown().maxDepth(3).filter { it.isFile && it.name == "terminal.txt" }.toList()
                }.getOrDefault(emptyList())
            }
            .filter { it.lastModified() >= inicioMs - 5_000 }
            .firstOrNull { t ->
                runCatching {
                    val texto = t.readText(Charsets.UTF_8)
                    texto.contains("threads_pedidas=$threads") &&
                        texto.contains(entradaNome) &&
                        texto.contains("p99_primeiro_ms=") &&
                        texto.contains("retorno_ate_parse_ms=") &&
                        (geracao == null || texto.contains("fases job=$geracao "))
                }.getOrDefault(false)
            }

    private fun injetarRetornoSeletor(activity: Activity, request: Int, arquivo: File) {
        val dados = Intent().setData(Uri.fromFile(arquivo))
        val metodo = Activity::class.java.getDeclaredMethod(
            "onActivityResult",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Intent::class.java,
        )
        metodo.isAccessible = true
        metodo.invoke(activity, request, Activity.RESULT_OK, dados)
    }

    private fun medirOrt(entrada: File, threads: Int, timeoutMs: Long, modelo: String = WhisperOrtModelCatalog.MODEL): JSONObject {
        WhisperOrtRoute.setThreads(ctx, threads)
        WhisperOrtRoute.cacheParaTeste = null
        val antes = terminais().keys.toSet()
        var jobId: String? = null
        ActivityScenario.launch(WhisperActivity::class.java).use { cenario ->
            cenario.onActivity { activity ->
                val job = WhisperOrtRoute.transcribe(
                    activity = activity as WhisperActivity,
                    uri = Uri.fromFile(entrada),
                    onResult = { },
                    // A rota direta não lê o seletor: o pedido viaja explícito
                    // (despacharOrt faz isso no produto). Sem este argumento a
                    // matriz 1×4 media 4×4.
                    threads = threads,
                    modelo = modelo,
                )
                jobId = job?.jobId
            }
            assertNotNull("job deveria ser admitido (${entrada.name}, t=$threads)", jobId)
            val t = aguardarOrt(antes, timeoutMs, jobId)
            assertNotNull("ORT deveria terminar SUCCESS (${entrada.name}, t=$threads)", t)
            assertEquals(modelo, t!!.optString("model"))
            return t
        }
    }

    @Test
    fun ortReal_d01_threads1e4() {
        val d01 = File(ctx.filesDir, "d01.wav")
        assumeTrue("WAV de controle ausente", d01.isFile)
        assumeTrue("pacote ORT ausente", WhisperOrtPackageFlow.estado(ctx) is WhisperOrtPackageFlow.Estado.Pronto)
        WhisperOrtRoute.setEnabled(ctx, true)
        try {
            for (threads in listOf(1, 4)) {
                val t = medirOrt(d01, threads, 600_000)
                val texto = t.optString("texto", "")
                Log.i(
                    "MEDICAO-ORT-16",
                    "motor=ORT entrada=d01.wav threads_pedidas=$threads " +
                        "threads_terminal=${t.optInt("threads", -1)} millis=${t.optLong("millis", -1)} " +
                        "bytes=${t.optInt("bytes", -1)} cauda=${texto.takeLast(120)}",
                )
                assertEquals("terminal deveria registrar as pedidas", threads, t.optInt("threads", -1))
                assertTrue("texto vazio", texto.isNotBlank())
                // Guarda contra falso-positivo: recusa BUSY sai em ms; compute
                // real de 17 s nunca termina em menos de 1 s.
                assertTrue(
                    "millis deveria refletir compute real (veio ${t.optLong("millis", -1)})",
                    t.optLong("millis", -1) >= 1_000,
                )
            }
        } finally {
            WhisperOrtRoute.setThreads(ctx, 4)
        }
    }

    @Test
    fun ortReal_cwav_cauda() {
        val c = File(ctx.filesDir, "a17-cwav-30016.wav")
        assumeTrue("a17-cwav ausente no aparelho", c.isFile)
        assumeTrue("pacote ORT ausente", WhisperOrtPackageFlow.estado(ctx) is WhisperOrtPackageFlow.Estado.Pronto)
        WhisperOrtRoute.setEnabled(ctx, true)
        val t = medirOrt(c, 4, 900_000)
        val texto = t.optString("texto", "")
        Log.i(
            "MEDICAO-ORT-16",
            "motor=ORT entrada=a17-cwav-30016.wav threads_pedidas=4 threads_terminal=${t.optInt("threads", -1)} " +
                "millis=${t.optLong("millis", -1)} bytes=${t.optInt("bytes", -1)} " +
                "texto_cauda=${texto.takeLast(300)}",
        )
        assertTrue("a17-cwav deveria produzir texto", texto.isNotBlank())
    }

    @Test
    fun cppReal_turbo_d01_cwav_fases() {
        val turbo = File(ctx.getExternalFilesDir("whisper_models"), "ggml-large-v3-turbo.bin")
        assumeTrue("GGML turbo ausente no aparelho", turbo.isFile && turbo.length() > 1_000_000_000L)
        val d01 = File(ctx.filesDir, "d01.wav")
        assumeTrue("WAV de controle ausente", d01.isFile)
        val c = File(ctx.filesDir, "a17-cwav-30016.wav")
        // Rota CPP: ORT desligado (nada de fallback/OR T nesta célula).
        WhisperOrtRoute.setEnabled(ctx, false)
        try {
            ActivityScenario.launch(WhisperActivity::class.java).use { cenario ->
                // Modelo turbo resolvido uma vez (menu real do produto); a
                // SELEÇÃO acontece por arquivo, DEPOIS do seletor (o retorno
                // do picker reseta o modelo — ordem do UX real).
                val pedidoTurbo = arrayOfNulls<Any>(1)
                cenario.onActivity { activity ->
                    val tela = activity as WhisperActivity
                    val modelos = WhisperActivity::class.java.getDeclaredMethod("officialModels")
                        .apply { isAccessible = true }.invoke(tela) as List<*>
                    pedidoTurbo[0] = modelos.firstOrNull {
                        val f = it!!.javaClass.getDeclaredField("label").apply { isAccessible = true }
                        f.get(it) == "v3Turbo"
                    }
                    assertNotNull("turbo deveria estar no menu oficial", pedidoTurbo[0])
                }
                val aplicarModelo = WhisperActivity::class.java.getDeclaredMethod(
                    "applyModelSelection", pedidoTurbo[0]!!.javaClass,
                ).apply { isAccessible = true }
                val transcrever = WhisperActivity::class.java.getDeclaredMethod("transcribeSelectedMedia")
                    .apply { isAccessible = true }
                for ((entrada, threads) in listOf(d01 to 1, d01 to 4, c to 4)) {
                    assumeTrue("${entrada.name} ausente", entrada.isFile)
                    WhisperOrtRoute.setThreads(ctx, threads)
                    // Despacha na UI; a espera roda na thread do teste (nunca
                    // dormir minutos dentro do onActivity — ANR). Ordem do UX:
                    // arquivo (seletor 6101) → modelo → transcrever.
                    cenario.onActivity { activity ->
                        val tela = activity as WhisperActivity
                        injetarRetornoSeletor(tela, 6101, entrada)
                        aplicarModelo.invoke(tela, pedidoTurbo[0])
                        transcrever.invoke(tela)
                        // Diagnóstico imediato: revela o retorno precoce
                        // (modelo nulo, reserva recusada, itens vazios…).
                        val st = (campoPrivado(tela, "status") as android.widget.TextView).text.toString()
                        val nItems = campoPrivado(tela, "selectedItems").let { (it as List<*>).size }
                        val temModelo = campoPrivado(tela, "selectedModel") != null
                        Log.i(
                            "MEDICAO-ORT-16",
                            "pos_invoke entrada=${entrada.name} status=[$st] items=$nItems temModelo=$temModelo",
                        )
                    }
                    val inicio = System.currentTimeMillis()
                    // Geração desta tentativa (adendo 17 §4): a correlação do
                    // terminal usa a identidade exata, nunca só o tempo.
                    var geracaoCaso = -1L
                    cenario.onActivity { a ->
                        geracaoCaso = campoPrivado(a, "currentSessionGeneration") as Long
                    }
                    // Aguarda o fim (isTranscribing volta a false na entrega).
                    var fim = false
                    val limite = System.currentTimeMillis() + 1_200_000
                    while (System.currentTimeMillis() < limite) {
                        Thread.sleep(10_000)
                        var ocupada = true
                        cenario.onActivity { a ->
                            ocupada = campoPrivado(a, "isTranscribing") as Boolean
                        }
                        if (!ocupada) {
                            fim = true
                            break
                        }
                    }
                    val decorrido = System.currentTimeMillis() - inicio
                    Log.i(
                        "MEDICAO-ORT-16",
                        "motor=CPP entrada=${entrada.name} threads_pedidas=$threads " +
                            "concluiu=$fim wall_ms=$decorrido",
                    )
                    assertTrue("CPP deveria concluir ${entrada.name} t=$threads", fim)
                    // Guarda contra falso-positivo: a via normal do lote não
                    // carimba ultimoDespacho; a prova é a sessão com as fases
                    // §4 (compute real + primeiro/último 99 + retorno→parse).
                    assertTrue(
                        "wall deveria refletir compute real (veio ${decorrido}ms)",
                        decorrido >= 10_000,
                    )
                    val terminal = terminalComFases(inicio, threads, entrada.name, geracaoCaso)
                    assertNotNull(
                        "sessão deveria registrar fases por arquivo (${entrada.name} t=$threads)",
                        terminal,
                    )
                    val fases = terminal!!.readText(Charsets.UTF_8).lineSequence()
                        .filter { it.contains("fases job=") }
                        .joinToString(" | ")
                    Log.i("MEDICAO-ORT-17", "fases ${entrada.name} t=$threads :: $fases")
                }
            }
        } finally {
            WhisperOrtRoute.setEnabled(ctx, true)
            WhisperOrtRoute.setThreads(ctx, 4)
        }
    }
}
