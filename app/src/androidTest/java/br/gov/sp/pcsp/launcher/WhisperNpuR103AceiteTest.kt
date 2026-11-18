package br.gov.sp.pcsp.launcher

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.regex.Pattern

/**
 * R103/T03 + R104/T01 — harness de aceite das 13 células (C1–C13) no build de produto.
 *
 * Regras de privacidade:
 *  - o texto transcrito vai SOMENTE para o jsonl apontado por `-e r103_saida`;
 *  - o logcat recebe apenas [WhisperNpuAceiteReceipt.Dados.resumo], sem texto;
 *  - a preferência `whisper_engine_cpp` NÃO é alterada por este harness;
 *  - a única preferência tocada é `whisper_npu_oferta_recusada`, e só na C1/C2.
 *
 * R104/T01 (correções do ensaio):
 *  - C1 guarda o valor da recusa, zera SÓ essa chave, testa e registra o estado
 *    final; com o pacote instalado a oferta não aparece e a entrada fixa diz
 *    "NPU ativa";
 *  - C2 registra o diagnóstico do download (plano, duração, QAIRT intocado);
 *  - toda célula começa pela pré-condição (nativos + modelo). Sem ela, a célula
 *    sai com motivo PRE_CONDICAO — não como falha da rota.
 *
 * Args: r103_saida, r103_audio, r103_lista, r103_limite, r103_sinal_ms,
 * r103_duracao_ms, r103_celula, r103_rota.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class WhisperNpuR103AceiteTest {

    private val ctx: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val args
        get() = InstrumentationRegistry.getArguments()

    private val prefs
        get() = ctx.getSharedPreferences("whisper", Context.MODE_PRIVATE)

    private val dispositivo: UiDevice
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    private val saida: File
        get() = File(args.getString("r103_saida")
            ?: File(ctx.filesDir, "r103/resultado.jsonl").absolutePath)

    private val limite: Int get() = (args.getString("r103_limite") ?: "1").toInt()
    private val sinalMs: Long get() = (args.getString("r103_sinal_ms") ?: "3000").toLong()
    private val duracaoMs: Long get() = (args.getString("r103_duracao_ms") ?: "60000").toLong()

    private lateinit var cancelador: WhisperNpuAceiteReceipt.Cancelador

    @Before
    fun preparar() {
        saida.parentFile?.mkdirs()
        cancelador = WhisperNpuAceiteReceipt.canceladorPadrao()
    }

    @After
    fun fechar() {
        cancelador.fechar()
    }

    private fun gravar(d: WhisperNpuAceiteReceipt.Dados) {
        saida.appendText(d.json() + "\n")
        Log.i("R103-ACEITE", d.resumo())
    }

    private fun soc(): String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        WhisperOrtHtpAdmissao.normalizarSoc(Build.SOC_MODEL)
    } else "desconhecido"

    private fun admitido(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val s = WhisperOrtHtpAdmissao.socDe(Build.SOC_MODEL) ?: return false
        return s.habilitado && WhisperOrtHtpAdmissao.fabricanteAdmitido(Build.SOC_MANUFACTURER)
    }

    private fun versoes() = Pair(WhisperOrtHtpRota.versaoPacote(), QairtDependencyManager.PACKAGE_VERSION)

    /** Áudios da corrida: -e r103_lista (um por linha) ou -e r103_audio. */
    private fun audios(): List<File> {
        val lista = args.getString("r103_lista")?.let { File(it) }
        if (lista != null && lista.isFile) {
            return lista.readLines().map { it.trim() }.filter { it.isNotEmpty() }.map { File(it) }
        }
        val unico = args.getString("r103_audio")?.let { File(it) }
        return listOfNotNull(unico).filter { it.isFile }
    }

    private fun modeloPronto(): Boolean =
        runCatching { WhisperOrtPackageFlow.estado(ctx) }.getOrNull() is WhisperOrtPackageFlow.Estado.Pronto

    private fun nativoPronto(): Boolean =
        runCatching { NativeDependencyManager.activateIfInstalled(ctx) }.getOrDefault(false)

    private fun pacoteNpuPronto(): Boolean =
        runCatching { WhisperOrtNpuPacote.estado(ctx) }.getOrNull() is WhisperOrtNpuPacote.Estado.Pronto

    /**
     * Pré-condição de TODA célula (R104/T01): dependências nativas e modelo.
     * Sem elas a célula sai com `PRE_CONDICAO` — não é falha da rota.
     */
    private fun preCondicao(celula: String, exigeAudio: Boolean = true) {
        val t0 = System.nanoTime() / 1_000_000L
        val nativo = nativoPronto()
        val modelo = modeloPronto()
        val gasto = System.nanoTime() / 1_000_000L - t0
        val (vn, vq) = versoes()
        val temAudio = !exigeAudio || audios().isNotEmpty()
        val ok = nativo && modelo && temAudio
        gravar(WhisperNpuAceiteReceipt.Dados(
            celula = "$celula-PRE",
            rotaPedida = "pre",
            rotaEfetiva = null,
            versaoNpu = vn,
            versaoQairt = vq,
            motivoFallback = "nativo=$nativo modelo=$modelo audio=$temAudio verificacao_ms=$gasto",
            openMs = 0L,
            totalMs = gasto,
            soc = soc(),
            timers = WhisperNpuAceiteReceipt.Timers(0L),
        ))
        assumeTrue(
            "PRE_CONDICAO: nativo=$nativo modelo=$modelo audio=$temAudio",
            ok,
        )
    }

    /** Executa UM pedido pela rota de produto e devolve o receipt. */
    private fun transcrever(
        atividade: android.app.Activity,
        artefato: File,
        celula: String,
        rotaPedida: String,
        sinalMsParam: Long? = null,
        id: String? = null,
    ): WhisperNpuAceiteReceipt.Dados {
        val latch = CountDownLatch(1)
        val inicio = System.nanoTime() / 1_000_000L
        val real = AtomicLong(0L)
        val terminal = AtomicLong(0L)
        val rec = arrayOf<WhisperNpuAceiteReceipt.Dados?>(null)
        val timers = WhisperNpuAceiteReceipt.Timers(inicio + (sinalMsParam ?: 0L))
        var job = null as WhisperOrtAdmission.Job?
        if (sinalMsParam != null) {
            cancelador.programar(inicio, sinalMsParam) { _, agora ->
                real.set(agora)
                WhisperOrtRoute.cancelar(job)
            }
        }
        job = WhisperOrtRoute.transcribe(
            atividade,
            Uri.fromFile(artefato),
            onResult = { r ->
                terminal.set(System.nanoTime() / 1_000_000L)
                val efetivo = when (r) {
                    is WhisperOrtRoute.Result.Done -> r.hardwareLabel
                    is WhisperOrtRoute.Result.Cancelled -> r.hardwareLabel
                    else -> null
                }
                val (vn, vq) = versoes()
                rec[0] = WhisperNpuAceiteReceipt.Dados(
                    celula = celula,
                    rotaPedida = rotaPedida,
                    rotaEfetiva = efetivo,
                    versaoNpu = vn,
                    versaoQairt = vq,
                    motivoFallback = buildString {
                        when (r) {
                            is WhisperOrtRoute.Result.Refused -> append("recusado:${r.reason}")
                            is WhisperOrtRoute.Result.Failed -> append("falhou:${r.reason}")
                            else -> append(WhisperOrtHtpRota.motivoRota())
                        }
                        if (!id.isNullOrEmpty()) append(" id=$id")
                    },
                    openMs = (r as? WhisperOrtRoute.Result.Done)?.openMillis ?: 0L,
                    totalMs = terminal.get() - inicio,
                    soc = soc(),
                    timers = timers.copy(
                        realMs = real.get().takeIf { it > 0L },
                        terminalMs = terminal.get(),
                    ),
                    texto = when (r) {
                        is WhisperOrtRoute.Result.Done -> r.text
                        is WhisperOrtRoute.Result.Cancelled -> r.text
                        else -> null
                    },
                )
                latch.countDown()
            },
        )
        assertTrue("terminal não chegou em 180 s", latch.await(180, TimeUnit.SECONDS))
        return rec[0] ?: error("receipt não produzido")
    }

    /**
     * Recebe a Activity sem bloquear a thread principal: `onActivity` apenas
     * captura a referência; o trabalho (com awaits longos) roda na thread de
     * instrumentação. Bloquear o main thread geraria ANR e invalidaria a C12.
     */
    private fun comAtividade(block: (android.app.Activity) -> Unit) {
        ActivityScenario.launch(WhisperActivity::class.java).use { cenario ->
            val ref = java.util.concurrent.atomic.AtomicReference<android.app.Activity>()
            cenario.onActivity { ref.set(it) }
            block(ref.get() ?: error("Activity nao disponivel"))
        }
    }

    // ---------------------------------------------------------------- C1

    /**
     * C1 — oferta única. Com o pacote da NPU ausente, o diálogo aparece sozinho
     * e oferece SÓ o NPU v2 (o QAIRT já está instalado); a recusa é persistida e
     * não reaparece; a entrada fixa continua na tela. Com o pacote instalado
     * (ensaio já baixou), a oferta não aparece e a entrada fixa diz "NPU ativa".
     */
    @Test
    fun a_c1_oferta_unica() {
        assumeTrue("C1 exige SoC admitido", admitido())
        preCondicao("C1", exigeAudio = false)
        val chave = WhisperNpuOferta.PREF_CHAVE
        val tinha = prefs.contains(chave)
        val valor = prefs.getBoolean(chave, false)
        val npu = pacoteNpuPronto()
        val detalhe: String
        if (!npu) {
            prefs.edit().remove(chave).commit()
            ActivityScenario.launch(WhisperActivity::class.java).use { cenario ->
                val dialogo = dispositivo.wait(Until.findObject(By.textContains("Aceleração NPU")), 20_000L)
                assertNotNull("oferta única deve aparecer", dialogo)
                val texto = dialogo!!.text ?: ""
                assertFalse("o diálogo não pode oferecer QAIRT: $texto", texto.contains("QAIRT", true))
                val agoraNao = dispositivo.wait(Until.findObject(By.text("Agora não")), 5_000L)
                assertNotNull("botão de recusa ausente", agoraNao)
                agoraNao!!.click()
                Thread.sleep(500L)
                assertTrue("a recusa tem de persistir", prefs.getBoolean(chave, false))
                cenario.close()
                ActivityScenario.launch(WhisperActivity::class.java).use {
                    assertNull(
                        "recusa persistida não pode reaparecer",
                        dispositivo.wait(Until.findObject(By.text("Agora não")), 4_000L),
                    )
                    assertNotNull(
                        "entrada fixa continua na tela",
                        dispositivo.wait(Until.findObject(By.text("Ativar aceleração NPU")), 5_000L),
                    )
                }
            }
            detalhe = "oferta=so_npu_v2 recusa=persistida entrada_fixa=visivel"
        } else {
            ActivityScenario.launch(WhisperActivity::class.java).use { cenario ->
                assertNull(
                    "com pacote Pronto a oferta não aparece",
                    dispositivo.wait(Until.findObject(By.text("Agora não")), 5_000L),
                )
                cenario.onActivity { a ->
                    assertEquals(
                        "NPU ativa",
                        a.findViewById<TextView>(R.id.button_ativar_npu)?.text?.toString(),
                    )
                }
            }
            detalhe = "pacote=Pronto oferta=ausente entrada_fixa=NPU_ativa"
        }
        val (vn, vq) = versoes()
        gravar(WhisperNpuAceiteReceipt.Dados(
            celula = "C1", rotaPedida = "-", rotaEfetiva = "-", versaoNpu = vn, versaoQairt = vq,
            motivoFallback = "$detalhe inicial=presente:$tinha/$valor " +
                "final=presente:${prefs.contains(chave)}/${prefs.getBoolean(chave, false)}",
            openMs = 0L, totalMs = 0L, soc = soc(),
        ))
        // Devolve a chave ao estado encontrado (a recusa é do teste, não do usuário).
        prefs.edit().apply { if (tinha) putBoolean(chave, valor) else remove(chave) }.commit()
    }

    // ---------------------------------------------------------------- C2

    /** Impressão digital barata do QAIRT: detecta qualquer alteração sem rehash. */
    private fun digitalQairt(): String {
        val raiz = File(ctx.noBackupFilesDir, "qairt")
        if (!raiz.exists()) return "ausente"
        var n = 0
        var bytes = 0L
        var mtime = 0L
        raiz.walkTopDown().filter { it.isFile }.forEach {
            n++
            bytes += it.length()
            mtime += it.lastModified()
        }
        return "arquivos=$n bytes=$bytes mtime_soma=$mtime"
    }

    /**
     * C2 — não baixa o QAIRT de novo. O plano com o QAIRT instalado tem só o
     * pacote da NPU (1 arquivo), e o QAIRT fica intocado. Quando o pacote da NPU
     * ainda não está pronto (ensaio), baixa pelo diálogo real e registra a
     * duração; quando já está pronto, a oferta não aparece.
     */
    @Test
    fun b_c2_nao_baixa_qairt() {
        assumeTrue("C2 exige SoC admitido", admitido())
        preCondicao("C2", exigeAudio = false)
        assertTrue("pré-condição: QAIRT já instalado", QairtDependencyManager.isInstalled(ctx))
        val antes = digitalQairt()
        val plano = WhisperOrtNpuPacote.planoDeDownload(true)
        assertTrue(
            "o plano não pode oferecer QAIRT: ${plano.arquivos.map { it.nome }}",
            plano.arquivos.none { it.nome.contains("qairt", true) },
        )
        val npu = pacoteNpuPronto()
        var pronto = npu
        var duracao = 0L
        if (!npu) {
            prefs.edit().remove(WhisperNpuOferta.PREF_CHAVE).commit()
            val inicio = System.nanoTime() / 1_000_000L
            ActivityScenario.launch(WhisperActivity::class.java).use {
                val oferta = dispositivo.wait(Until.findObject(By.textContains("Aceleração NPU")), 20_000L)
                assertNotNull("diálogo de oferta ausente", oferta)
                assertEquals("plano com QAIRT instalado tem só o NPU v2", 1, plano.arquivos.size)
                val baixar = dispositivo.wait(Until.findObject(By.text(Pattern.compile("(?i)baixar"))), 8_000L)
                assertNotNull("botão Baixar ausente", baixar)
                baixar!!.click()
                val limiteMs = System.currentTimeMillis() + 30 * 60_000L
                while (System.currentTimeMillis() < limiteMs) {
                    pronto = pacoteNpuPronto()
                    if (pronto) break
                    Thread.sleep(1_000L)
                }
                duracao = System.nanoTime() / 1_000_000L - inicio
            }
        } else {
            ActivityScenario.launch(WhisperActivity::class.java).use {
                assertNull(
                    "com pacote Pronto não há oferta",
                    dispositivo.wait(Until.findObject(By.text("Agora não")), 5_000L),
                )
            }
        }
        val depois = digitalQairt()
        assertEquals("o QAIRT não pode ser tocado pela C2", antes, depois)
        val (vn, vq) = versoes()
        gravar(WhisperNpuAceiteReceipt.Dados(
            celula = "C2-DETALHE", rotaPedida = "download", rotaEfetiva = if (pronto) "pacote verificado" else null,
            versaoNpu = vn, versaoQairt = vq,
            motivoFallback = "arquivos_plano=${plano.arquivos.size} bytes_plano=${plano.totalBytes} " +
                "npu_pronto_antes=$npu download_ms=$duracao qairt=$antes",
            openMs = 0L, totalMs = duracao, soc = soc(),
        ))
        gravar(WhisperNpuAceiteReceipt.Dados(
            celula = "C2", rotaPedida = "download", rotaEfetiva = if (pronto) "pacote verificado" else null,
            versaoNpu = vn, versaoQairt = vq,
            motivoFallback = if (pronto) null else "download_nao_concluiu:${duracao}ms",
            openMs = 0L, totalMs = duracao, soc = soc(),
        ))
        assertTrue("download do NPU v2 precisa terminar Pronto", pronto)
    }

    // ---------------------------------------------------------------- C3

    /**
     * C3 — QAIRT único. Duas ordens no MESMO processo; a contagem de cargas do
     * QNN e o `ADSP_LIBRARY_PATH` único são lidos do logcat pelo host. A metade
     * Granite (coexistência) exige o modelo do Granite, que a ordem proíbe
     * baixar: fica registrada como NÃO EXECUTADA.
     */
    @Test
    fun c_c3_qairt_unico() {
        preCondicao("C3")
        val fontes = audios()
        val respostas = mutableListOf<WhisperNpuAceiteReceipt.Dados>()
        comAtividade { atividade ->
            for (artefato in fontes.take(2)) {
                respostas.add(transcrever(atividade, artefato, "C3", "htp", id = artefato.nameWithoutExtension))
            }
        }
        respostas.forEach { gravar(it) }
        val (vn, vq) = versoes()
        gravar(WhisperNpuAceiteReceipt.Dados(
            celula = "C3-MARCO", rotaPedida = "htp", rotaEfetiva = respostas.firstOrNull()?.rotaEfetiva,
            versaoNpu = vn, versaoQairt = vq,
            motivoFallback = "ordens_mesmo_processo=${respostas.size} granite=NAO_EXECUTADA_modelo_ausente",
            openMs = 0L, totalMs = 0L, soc = soc(),
        ))
        assertTrue("C3 nao produziu nenhuma ordem", respostas.isNotEmpty())
    }

    // ---------------------------------------------------------------- C4/C5/C6/C11

    /** Workhorse: N pedidos no MESMO processo (C4 = 8 por processo; C5/C6 = 202). */
    @Test
    fun d_workhorse() {
        val celula = args.getString("r103_celula") ?: "C4"
        assertTrue("célula de workhorse inválida: $celula", celula in setOf("C4", "C5", "C6", "C11"))
        preCondicao(celula)
        val fontes = audios()
        val rota = args.getString("r103_rota") ?: WhisperOrtHtpRota.rotaDoPedido()
        val ate = if (celula == "C11") System.currentTimeMillis() + duracaoMs else Long.MAX_VALUE
        comAtividade { atividade ->
            var n = 0
            while (n < limite && System.currentTimeMillis() < ate) {
                val fonte = fontes[n % fontes.size]
                gravar(transcrever(atividade, fonte, celula, rota, id = fonte.nameWithoutExtension))
                n++
            }
            assertTrue("nenhum pedido executado", n > 0)
        }
    }

    /** C10 — áudio longo: uma passada; o RTF sai da duração real do arquivo. */
    @Test
    fun k_c10_audio_longo() {
        preCondicao("C10")
        val fonte = audios().first()
        val duracaoAudio = duracaoAudioMs(fonte)
        val rota = args.getString("r103_rota") ?: WhisperOrtHtpRota.rotaDoPedido()
        var dados: WhisperNpuAceiteReceipt.Dados? = null
        comAtividade { atividade -> dados = transcrever(atividade, fonte, "C10", rota, id = fonte.nameWithoutExtension) }
        val d = dados!!
        val rtf = if (duracaoAudio > 0L) d.totalMs.toDouble() / duracaoAudio else -1.0
        val (vn, vq) = versoes()
        gravar(WhisperNpuAceiteReceipt.Dados(
            celula = "C10-RTF", rotaPedida = rota, rotaEfetiva = d.rotaEfetiva,
            versaoNpu = vn, versaoQairt = vq,
            motivoFallback = "audio_ms=$duracaoAudio total_ms=${d.totalMs} rtf=$rtf id=${fonte.nameWithoutExtension}",
            openMs = d.openMs, totalMs = d.totalMs, soc = soc(),
        ))
        gravar(d)
        assertTrue("RTF precisa ser finito e < 1", rtf in 0.0..1.0)
    }

    private fun duracaoAudioMs(arquivo: File): Long = runCatching {
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(arquivo.absolutePath)
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } finally {
            mmr.release()
        }
    }.getOrDefault(0L)

    // ---------------------------------------------------------------- C7

    /** C7 — cancelamento da transcrição HTP com sinal aos 3 s. */
    @Test
    fun g_c7_cancelamento() {
        preCondicao("C7")
        var dados: WhisperNpuAceiteReceipt.Dados? = null
        comAtividade { atividade ->
            dados = transcrever(atividade, audios().first(), "C7", "htp", sinalMsParam = sinalMs)
        }
        gravar(dados!!)
        val desdeProgramado = dados!!.timers.terminalMs?.let { it - dados!!.timers.programadoMs }
        assertTrue(
            "terminal tem de chegar em até 1 s após o instante programado (obtido $desdeProgramado ms)",
            desdeProgramado != null && desdeProgramado <= 1_000L,
        )
    }

    // ---------------------------------------------------------------- C8/C9

    /** C8 — fallback com pacote não verificado (host renomeia o wrapper). */
    @Test
    fun i_c8_fallback() {
        preCondicao("C8")
        var dados: WhisperNpuAceiteReceipt.Dados? = null
        comAtividade { atividade -> dados = transcrever(atividade, audios().first(), "C8", "cpu") }
        gravar(dados!!)
        assertEquals("a rota tem de cair para CPU", "CPU", dados!!.rotaEfetiva)
        assertNotNull("o motivo do fallback tem de estar registrado", dados!!.motivoFallback)
    }

    /** C9 — fallback sem QAIRT. Exige o QAIRT ausente: sem autorização, sai PRE_CONDICAO. */
    @Test
    fun j_c9_sem_qairt() {
        preCondicao("C9")
        assumeTrue(
            "C9 exige QAIRT ausente (remoção é decisão do usuário)",
            !QairtDependencyManager.isInstalled(ctx),
        )
        var dados: WhisperNpuAceiteReceipt.Dados? = null
        comAtividade { atividade -> dados = transcrever(atividade, audios().first(), "C9", "cpu") }
        gravar(dados!!)
        assertEquals("a rota tem de cair para CPU", "CPU", dados!!.rotaEfetiva)
    }

    // ---------------------------------------------------------------- C12/C13

    /** C12 — fluidez: detector de ANR por linha do processo, sem falso positivo. */
    @Test
    fun m_c12_fluidez() {
        val pkg = ctx.packageName
        val fluxo = android.os.ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand("dumpsys activity processes"),
        )
        val texto = BufferedReader(InputStreamReader(fluxo, "UTF-8")).readText()
        fluxo.close()
        val regex = Regex("(?i)(anr|notresponding|not responding)")
        val linhasSuspeitas = texto.lines().filter { regex.containsMatchIn(it) && it.contains(pkg) }
        val (vn, vq) = versoes()
        gravar(WhisperNpuAceiteReceipt.Dados(
            celula = "C12", rotaPedida = "ui", rotaEfetiva = "ui", versaoNpu = vn, versaoQairt = vq,
            motivoFallback = if (linhasSuspeitas.isEmpty()) null else "ANR:${linhasSuspeitas.size}",
            openMs = 0L, totalMs = 0L, soc = soc(),
        ))
        assertTrue("C12 reprova com ANR", linhasSuspeitas.isEmpty())
    }

    /** C13 — marco do monitor de congelamento (amostragem é do host). */
    @Test
    fun n_c13_monitor() {
        val (vn, vq) = versoes()
        gravar(WhisperNpuAceiteReceipt.Dados(
            celula = "C13", rotaPedida = "monitor", rotaEfetiva = "monitor",
            versaoNpu = vn, versaoQairt = vq, motivoFallback = "pid=${android.os.Process.myPid()}",
            openMs = 0L, totalMs = 0L, soc = soc(),
        ))
    }
}
