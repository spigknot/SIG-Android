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
        // R105/T01 — TODA linha carrega id: o do clipe quando há clipe, senão o
        // da célula. Sem isso, mapear 202 textos exige casar string no motivo.
        val linha = if (d.id.isNullOrEmpty()) d.copy(id = d.celula) else d
        saida.appendText(linha.json() + "\n")
        Log.i("R103-ACEITE", linha.resumo())
    }

    private fun soc(): String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        WhisperOrtHtpAdmissao.normalizarSoc(Build.SOC_MODEL)
    } else "desconhecido"

    /** Botão de AlertDialog Material: o rótulo é exibido em CAIXA ALTA. */
    private fun textoExato(rotulo: String) = By.text(Pattern.compile("(?i)^" + Pattern.quote(rotulo) + "$"))

    /** Trecho de texto sem depender de caixa (título do diálogo e botões). */
    private fun textoContem(rotulo: String) = By.text(Pattern.compile("(?i).*" + Pattern.quote(rotulo) + ".*"))

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
     * Espera a verificação do pacote da NPU terminar. Sem isso, um pedido feito
     * com o pacote em reverificação cai em CPU e a célula mediria a rota errada.
     *
     * R105/T01 — com a conferência rápida, o caso NORMAL não espera nada (a rota
     * já está em HTP). A espera só existe para o marcador em formato anterior e
     * para a reverificação completa depois de falha de sessão.
     */
    private fun aguardarVerificacao(limiteMs: Long = 20_000L): Long {
        val t0 = System.nanoTime() / 1_000_000L
        WhisperOrtHtpRota.iniciar(ctx)
        val fim = System.currentTimeMillis() + limiteMs
        while (System.currentTimeMillis() < fim) {
            if (WhisperOrtHtpRota.rotaDoPedido() == WhisperOrtHtpAdmissao.ROTA_HTP) break
            val motivo = WhisperOrtHtpRota.motivoRota()
            // Só a reverificação vale espera; pacote ausente/recusado não muda.
            if (!motivo.contains("verificacao")) break
            Thread.sleep(50L)
        }
        return System.nanoTime() / 1_000_000L - t0
    }

    /**
     * Pré-condição de TODA célula (R104/T01): dependências nativas e modelo.
     * Sem elas a célula sai com `PRE_CONDICAO` — não é falha da rota.
     */
    private fun preCondicao(celula: String, exigeAudio: Boolean = true) {
        // R105/T01 — a conferência rápida do pacote acontece no início do pedido,
        // como em produção (`WhisperOrtRoute.executar`). Feita aqui, o PRE registra
        // o custo real que decide a rota do PRIMEIRO pedido do processo.
        WhisperOrtHtpRota.iniciar(ctx)
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
            motivoFallback = "nativo=$nativo modelo=$modelo audio=$temAudio verificacao_ms=$gasto " +
                "rota=${WhisperOrtHtpRota.rotaDoPedido()} motivo_rota=${WhisperOrtHtpRota.motivoRota()}",
            openMs = 0L,
            totalMs = gasto,
            soc = soc(),
            timers = WhisperNpuAceiteReceipt.Timers(0L),
            id = celula,
            conferenciaMs = WhisperOrtHtpRota.conferenciaMs(),
        ))
        assumeTrue(
            "PRE_CONDICAO: nativo=$nativo modelo=$modelo audio=$temAudio",
            ok,
        )
        val espera = aguardarVerificacao()
        if (espera > 0L) Log.i("R103-ACEITE", "espera da verificacao: ${espera}ms")
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
                        when (r) {
                            is WhisperOrtRoute.Result.Done -> append(" outcome=${r.outcome}")
                            is WhisperOrtRoute.Result.Cancelled ->
                                append(" outcome=CANCELLED parcial=${r.partial}")
                            else -> {}
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
                    id = id,
                    conferenciaMs = WhisperOrtHtpRota.conferenciaMs(),
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
                val dialogo = dispositivo.wait(Until.findObject(textoContem("Aceleração NPU")), 20_000L)
                assertNotNull("oferta única deve aparecer", dialogo)
                val texto = dialogo!!.text ?: ""
                assertFalse("o diálogo não pode oferecer QAIRT: $texto", texto.contains("QAIRT", true))
                val agoraNao = dispositivo.wait(Until.findObject(textoExato("Agora não")), 5_000L)
                assertNotNull("botão de recusa ausente", agoraNao)
                agoraNao!!.click()
                Thread.sleep(500L)
                assertTrue("a recusa tem de persistir", prefs.getBoolean(chave, false))
                cenario.close()
                ActivityScenario.launch(WhisperActivity::class.java).use {
                    assertNull(
                        "recusa persistida não pode reaparecer",
                        dispositivo.wait(Until.findObject(textoExato("Agora não")), 4_000L),
                    )
                    assertNotNull(
                        "entrada fixa continua na tela",
                        dispositivo.wait(Until.findObject(textoExato("Ativar aceleração NPU")), 5_000L),
                    )
                }
            }
            detalhe = "oferta=so_npu_v2 recusa=persistida entrada_fixa=visivel"
        } else {
            ActivityScenario.launch(WhisperActivity::class.java).use { cenario ->
                assertNull(
                    "com pacote Pronto a oferta não aparece",
                    dispositivo.wait(Until.findObject(textoExato("Agora não")), 5_000L),
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
                val oferta = dispositivo.wait(Until.findObject(textoContem("Aceleração NPU")), 20_000L)
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
                    dispositivo.wait(Until.findObject(textoExato("Agora não")), 5_000L),
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
        val temGranite = runCatching { GraniteEngine.modelFile(ctx).isFile }.getOrDefault(false)
        var graniteBackend: String? = null
        var graniteTextoLen = -1
        if (temGranite) {
            // Ordem (a): Granite na NPU primeiro, Whisper depois, no MESMO processo.
            val carregou = runCatching {
                GraniteEngine.load(ctx, GraniteExecutionBackend.NPU_QNN_HTP)
            }.getOrDefault(false)
            graniteBackend = runCatching { GraniteEngine.loadedBackend()?.reportLabel }.getOrNull()
            if (carregou) {
                graniteTextoLen = runCatching {
                    GraniteEngine.transcribeFile(fontes.first()).length
                }.getOrDefault(-1)
            }
            val (vg, vqg) = versoes()
            gravar(WhisperNpuAceiteReceipt.Dados(
                celula = "C3-GRANITE", rotaPedida = "npu", rotaEfetiva = graniteBackend,
                versaoNpu = vg, versaoQairt = vqg,
                motivoFallback = "load=$carregou backend=$graniteBackend texto_len=$graniteTextoLen erro=${GraniteEngine.lastError()}",
                openMs = 0L, totalMs = 0L, soc = soc(),
            ))
        }
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
            motivoFallback = "ordens_mesmo_processo=${respostas.size} granite=" +
                if (temGranite) "executado:$graniteBackend texto_len=$graniteTextoLen" else "NAO_EXECUTADA_modelo_ausente",
            openMs = 0L, totalMs = 0L, soc = soc(),
        ))
        assertTrue("C3 nao produziu nenhuma ordem", respostas.isNotEmpty())
    }

    /**
     * R105/T03 — Granite NPU SOZINHO, em processo novo, sem tocar na rota do
     * Whisper (nenhum `WhisperOrtHtpRota.iniciar`): o que se mede é só a frente
     * Granite. Limite de 20 min; no estouro o processo é encerrado pelo host.
     *
     * Registra tempo, memória (VmRSS antes/depois) e as bibliotecas QNN carregadas
     * (inclusive os stubs V73/V81) lidas de `/proc/self/maps`.
     */
    @Test
    fun q_c3_granite_sozinho() {
        val limiteMs = (args.getString("r105_limite_ms") ?: "1200000").toLong()
        val nativo = nativoPronto()
        val qairt = runCatching { QairtDependencyManager.isInstalled(ctx) }.getOrDefault(false)
        val modelo = runCatching { GraniteEngine.modelFile(ctx).isFile }.getOrDefault(false)
        val (vn, vq) = versoes()
        val rssAntes = rssKb()
        val libsAntes = libsQnnCarregadas()
        gravar(WhisperNpuAceiteReceipt.Dados(
            celula = "C3G-PRE", rotaPedida = "pre", rotaEfetiva = null,
            versaoNpu = vn, versaoQairt = vq,
            motivoFallback = "nativo=$nativo qairt=$qairt modelo_granite=$modelo " +
                "rss_kb=$rssAntes libs_antes=$libsAntes",
            openMs = 0L, totalMs = 0L, soc = soc(), id = "C3G-PRE",
        ))
        assumeTrue("PRE_CONDICAO: nativo=$nativo qairt=$qairt modelo=$modelo", nativo && qairt && modelo)

        val carregou = java.util.concurrent.atomic.AtomicBoolean(false)
        val erro = java.util.concurrent.atomic.AtomicReference("")
        val inicio = System.nanoTime() / 1_000_000L
        val thread = Thread {
            runCatching { GraniteEngine.load(ctx, GraniteExecutionBackend.NPU_QNN_HTP) }
                .onSuccess { carregou.set(it) }
                .onFailure { erro.set("${it.javaClass.simpleName}: ${it.message}") }
        }.apply { isDaemon = true; name = "r105-granite-sozinho" }
        thread.start()
        thread.join(limiteMs)
        val estourou = thread.isAlive
        val gasto = System.nanoTime() / 1_000_000L - inicio
        val rssDepois = rssKb()
        val libsDepois = libsQnnCarregadas()
        val backend = runCatching { GraniteEngine.loadedBackend()?.reportLabel }.getOrNull()
        gravar(WhisperNpuAceiteReceipt.Dados(
            celula = "C3-GRANITE-SOZINHO", rotaPedida = "npu", rotaEfetiva = backend,
            versaoNpu = vn, versaoQairt = vq,
            motivoFallback = "load=${carregou.get()} timeout=$estourou limite_ms=$limiteMs " +
                "load_ms=$gasto rss_antes_kb=$rssAntes rss_depois_kb=$rssDepois " +
                "libs_antes=$libsAntes libs_depois=$libsDepois erro=${erro.get()}",
            openMs = 0L, totalMs = gasto, soc = soc(), id = "C3-GRANITE-SOZINHO",
        ))
    }

    /**
     * R105/T03 — as DUAS ordens no MESMO processo, com transcrição real:
     * `granite-w` (Granite NPU e depois Whisper) e `w-granite` (Whisper e depois
     * Granite). O critério substituto da R102 usa `qairt-w` / `w-qairt` (carga do
     * QAIRT antes do Whisper e o inverso).
     *
     * A prova de "uma única carga de cada biblioteca e um único
     * `ADSP_LIBRARY_PATH`" é do host, sobre o logcat filtrado por PID.
     */
    @Test
    fun r_c3_ordens() {
        val ordem = args.getString("r105_ordem") ?: "granite-w"
        preCondicao("C3-" + ordem.uppercase())
        val fontes = audios()
        val (vn, vq) = versoes()
        val rota = WhisperOrtHtpRota.rotaDoPedido()

        fun granite(passo: String) {
            val modelo = runCatching { GraniteEngine.modelFile(ctx).isFile }.getOrDefault(false)
            assumeTrue("modelo do Granite ausente", modelo)
            val rssAntes = rssKb()
            val inicio = System.nanoTime() / 1_000_000L
            val carregou = runCatching { GraniteEngine.load(ctx, GraniteExecutionBackend.NPU_QNN_HTP) }.getOrDefault(false)
            val textoLen = if (carregou && fontes.isNotEmpty()) {
                runCatching { GraniteEngine.transcribeFile(fontes.first()).length }.getOrDefault(-1)
            } else -1
            val gasto = System.nanoTime() / 1_000_000L - inicio
            gravar(WhisperNpuAceiteReceipt.Dados(
                celula = "C3-$passo-GRANITE", rotaPedida = "npu",
                rotaEfetiva = runCatching { GraniteEngine.loadedBackend()?.reportLabel }.getOrNull(),
                versaoNpu = vn, versaoQairt = vq,
                motivoFallback = "passo=$passo load=$carregou texto_len=$textoLen " +
                    "rss_kb=$rssAntes libs=${libsQnnCarregadas()} erro=${GraniteEngine.lastError()}",
                openMs = 0L, totalMs = gasto, soc = soc(), id = "C3-$passo-GRANITE",
            ))
        }

        fun qairt(passo: String) {
            val inicio = System.nanoTime() / 1_000_000L
            val falha = runCatching { QairtDependencyManager.loadQnnNatives(ctx, "htp", "73") }
            val gasto = System.nanoTime() / 1_000_000L - inicio
            gravar(WhisperNpuAceiteReceipt.Dados(
                celula = "C3-$passo-QAIRT", rotaPedida = "npu", rotaEfetiva = "qairt",
                versaoNpu = vn, versaoQairt = vq,
                motivoFallback = "passo=$passo ok=${falha.isSuccess} libs=${libsQnnCarregadas()} " +
                    "erro=${falha.exceptionOrNull()?.message}",
                openMs = 0L, totalMs = gasto, soc = soc(), id = "C3-$passo-QAIRT",
            ))
            falha.getOrThrow()
        }

        fun whisper() {
            val respostas = mutableListOf<WhisperNpuAceiteReceipt.Dados>()
            comAtividade { atividade ->
                for (artefato in fontes.take(2)) {
                    respostas.add(transcrever(atividade, artefato, "C3-ORDEM", rota, id = artefato.nameWithoutExtension))
                }
            }
            respostas.forEach { gravar(it) }
            assertEquals("ordem Whisper não pode cair em CPU", "HTP W8A16", respostas.firstOrNull()?.rotaEfetiva)
        }

        when (ordem) {
            "granite-w" -> { granite("1"); whisper() }
            "w-granite" -> { whisper(); granite("2") }
            "qairt-w" -> { qairt("1"); whisper() }
            "w-qairt" -> { whisper(); qairt("2") }
            else -> throw AssertionError("ordem inválida: $ordem")
        }
    }

    /** VmRSS do próprio processo, em KiB (−1 quando indisponível). */
    private fun rssKb(): Long = runCatching {
        File("/proc/self/status").readLines()
            .first { it.startsWith("VmRSS:") }
            .trim().split(Regex("\\s+"))[1].toLong()
    }.getOrDefault(-1L)

    /** Bibliotecas QNN/FastRPC mapeadas no processo, por nome de arquivo. */
    private fun libsQnnCarregadas(): String = runCatching {
        File("/proc/self/maps").readLines()
            .map { it.substringAfterLast(' ').trim() }
            .filter { it.contains("libQnn") || it.contains("libcdsprpc") || it.contains("libadsprpc") }
            .map { it.substringAfterLast('/') }
            .distinct().sorted().joinToString(",")
    }.getOrDefault("indisponivel")

    /** Baixa o pacote do Granite 5.0 pelo fluxo de produto ([GraniteEngine.downloadPackage]). */
    @Test
    fun o_granite_baixar() {
        preCondicao("GRANITE-BAIXAR")
        val dir = GraniteEngine.packageDir(ctx)
        val plano = GraniteEngine.downloadPlan(dir)
        val faltam = plano.pendenteBytes
        val t0 = System.nanoTime() / 1_000_000L
        if (faltam > 0L) {
            GraniteEngine.downloadPackage(ctx, onProgress = { pct, mb ->
                if (pct % 10 == 0) Log.i("R103-ACEITE", "granite download pct=$pct mb=$mb")
            })
        }
        val gasto = System.nanoTime() / 1_000_000L - t0
        val modelo = GraniteEngine.modelFile(ctx)
        val (vn, vq) = versoes()
        gravar(WhisperNpuAceiteReceipt.Dados(
            celula = "GRANITE-DOWNLOAD", rotaPedida = "download", rotaEfetiva = if (modelo.isFile) "pacote presente" else null,
            versaoNpu = vn, versaoQairt = vq,
            motivoFallback = "faltavam=$faltam bytes=${modelo.length()} download_ms=$gasto dir=${dir.name}",
            openMs = 0L, totalMs = gasto, soc = soc(),
        ))
        assertTrue("pacote do Granite precisa terminar presente", modelo.isFile)
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
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        var maxTermico = -1
        var somaTermico = 0L
        var amostrasTermico = 0L
        comAtividade { atividade ->
            var n = 0
            while (n < limite && System.currentTimeMillis() < ate) {
                val fonte = fontes[n % fontes.size]
                gravar(transcrever(atividade, fonte, celula, rota, id = fonte.nameWithoutExtension))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val st = pm.currentThermalStatus
                    if (st > maxTermico) maxTermico = st
                    somaTermico += st
                    amostrasTermico++
                }
                n++
            }
            assertTrue("nenhum pedido executado", n > 0)
            if (celula == "C11") {
                val (vt, vqt) = versoes()
                gravar(WhisperNpuAceiteReceipt.Dados(
                    celula = "C11-TERMICA", rotaPedida = rota, rotaEfetiva = rota,
                    versaoNpu = vt, versaoQairt = vqt,
                    motivoFallback = "status_max=$maxTermico status_medio=" +
                        (if (amostrasTermico > 0) somaTermico.toDouble() / amostrasTermico else -1.0) +
                        " amostras=$amostrasTermico pedidos=$n",
                    openMs = 0L, totalMs = 0L, soc = soc(),
                ))
            }
            if (celula == "C5" || celula == "C6") {
                val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                val info = am.getProcessMemoryInfo(intArrayOf(android.os.Process.myPid())).firstOrNull()
                val (vp, vqp) = versoes()
                gravar(WhisperNpuAceiteReceipt.Dados(
                    celula = "$celula-PSS", rotaPedida = rota, rotaEfetiva = rota,
                    versaoNpu = vp, versaoQairt = vqp,
                    motivoFallback = "pss_kib=${info?.totalPss ?: 0} pedidos=$n",
                    openMs = 0L, totalMs = 0L, soc = soc(),
                ))
            }
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

    private fun duracaoAudioMs(arquivo: File): Long {
        val porMetadata = runCatching {
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(arquivo.absolutePath)
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } finally {
                mmr.release()
            }
        }.getOrDefault(0L)
        if (porMetadata > 0L) return porMetadata
        // Fallback para WAV PCM: o MediaMetadataRetriever pode não abrir
        // /data/local/tmp no processo do app.
        return runCatching {
            val b = arquivo.readBytes()
            if (b.size < 44 || String(b, 0, 4) != "RIFF") {
                0L
            } else {
                var off = 12
                var taxa = 0L
                var canais = 0L
                var bits = 0L
                var dados = 0L
                while (off + 8 <= b.size) {
                    val id = String(b, off, 4)
                    val tam = (b[off + 4].toLong() and 0xFF) or
                        ((b[off + 5].toLong() and 0xFF) shl 8) or
                        ((b[off + 6].toLong() and 0xFF) shl 16) or
                        ((b[off + 7].toLong() and 0xFF) shl 24)
                    when (id) {
                        "fmt " -> {
                            canais = (b[off + 10].toLong() and 0xFF) or ((b[off + 11].toLong() and 0xFF) shl 8)
                            taxa = (b[off + 12].toLong() and 0xFF) or
                                ((b[off + 13].toLong() and 0xFF) shl 8) or
                                ((b[off + 14].toLong() and 0xFF) shl 16) or
                                ((b[off + 15].toLong() and 0xFF) shl 24)
                            bits = (b[off + 22].toLong() and 0xFF) or ((b[off + 23].toLong() and 0xFF) shl 8)
                        }
                        "data" -> dados = tam.toInt().toLong() and 0xFFFFFFFFL
                    }
                    off += 8 + tam.toInt() + (tam.toInt() and 1)
                }
                if (dados <= 0L) dados = (b.size - 44).toLong()
                val porSegundo = taxa * canais * (bits / 8L)
                if (porSegundo > 0L) dados * 1000L / porSegundo else 0L
            }
        }.getOrDefault(0L)
    }

    // ---------------------------------------------------------------- C7

    /** C7 — cancelamento da transcrição HTP com sinal aos 3 s. */
    @Test
    fun g_c7_cancelamento() {
        preCondicao("C7")
        val fonte = audios().first()
        var dados: WhisperNpuAceiteReceipt.Dados? = null
        comAtividade { atividade ->
            dados = transcrever(atividade, fonte, "C7", "htp", sinalMsParam = sinalMs, id = fonte.nameWithoutExtension)
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
        val fonte = audios().first()
        var dados: WhisperNpuAceiteReceipt.Dados? = null
        comAtividade { atividade -> dados = transcrever(atividade, fonte, "C8", "cpu", id = fonte.nameWithoutExtension) }
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
        val fonte = audios().first()
        var dados: WhisperNpuAceiteReceipt.Dados? = null
        comAtividade { atividade -> dados = transcrever(atividade, fonte, "C9", "cpu", id = fonte.nameWithoutExtension) }
        gravar(dados!!)
        assertEquals("a rota tem de cair para CPU", "CPU", dados!!.rotaEfetiva)
    }

    // ---------------------------------------------------------------- C14

    /**
     * R105/T01 — C14: o PRIMEIRO pedido depois do início do processo tem de ser
     * admitido na HTP quando a conferência rápida passa.
     *
     * A prova de 5/5 é do host: este método roda UMA vez por processo, com o
     * processo encerrado entre as repetições. O recibo leva a rota efetiva e o
     * tempo da conferência rápida (`conferencia_ms`) de cada repetição.
     */
    @Test
    fun p_c14_primeiro_pedido() {
        preCondicao("C14")
        val fonte = audios().first()
        var dados: WhisperNpuAceiteReceipt.Dados? = null
        comAtividade { atividade -> dados = transcrever(atividade, fonte, "C14", "htp", id = fonte.nameWithoutExtension) }
        val d = dados!!
        gravar(d)
        assertEquals("o 1º pedido do processo tem de ir à HTP", "HTP W8A16", d.rotaEfetiva)
        assertTrue("texto vazio no 1º pedido", !d.texto.isNullOrBlank())
    }

    /**
     * R105/T04 — C14, metade de CORRUPÇÃO. O host troca o contexto por uma cópia
     * com 1 byte alterado (mesmo tamanho, data nova) e devolve o original depois.
     *
     * Esperado: a conferência rápida RECUSA (tamanho/data) e o pedido cai para a
     * CPU — ou a sessão falha e o fallback leva à CPU — sem diálogo de erro.
     */
    @Test
    fun s_c14_corrupcao() {
        WhisperOrtHtpRota.iniciar(ctx)
        val rota = WhisperOrtHtpRota.rotaDoPedido()
        val motivo = WhisperOrtHtpRota.motivoRota()
        val fonte = audios().first()
        var dados: WhisperNpuAceiteReceipt.Dados? = null
        var dialogo: Boolean? = null
        comAtividade { atividade ->
            dados = transcrever(atividade, fonte, "C14-CORRUPCAO", "htp", id = fonte.nameWithoutExtension)
            dialogo = (atividade as? WhisperActivity)?.dialogoFallbackEmExibicao != null
        }
        val d = dados!!
        gravar(d)
        gravar(WhisperNpuAceiteReceipt.Dados(
            celula = "C14-CORRUPCAO-CONF", rotaPedida = "htp", rotaEfetiva = rota,
            versaoNpu = WhisperOrtHtpRota.versaoPacote(),
            versaoQairt = QairtDependencyManager.PACKAGE_VERSION,
            motivoFallback = "rota=$rota motivo=$motivo dialogo_fallback=$dialogo",
            openMs = 0L, totalMs = 0L, soc = soc(), id = "C14-CORRUPCAO-CONF",
            conferenciaMs = WhisperOrtHtpRota.conferenciaMs(),
        ))
        assertEquals("pacote adulterado tem de cair para CPU", "CPU", d.rotaEfetiva)
        assertTrue("a transcrição tem de concluir pelo fallback", !d.texto.isNullOrBlank())
        assertTrue("não pode haver diálogo de erro/fallback", dialogo != true)
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
