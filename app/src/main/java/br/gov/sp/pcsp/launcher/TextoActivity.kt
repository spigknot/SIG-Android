package br.gov.sp.pcsp.launcher

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.Date
import java.util.Locale
import java.text.SimpleDateFormat

/** Ferramenta Texto: tradução de texto com o modelo Hy-MT2 (Tencent).
 *
 * Inferência no aparelho via `HyMt2Native` (libsig_llama.so do pacote nativo).
 * Modelo é escolhido num menu (1.25bit / Q4_0 / Q4_K_M) e baixado sob demanda,
 * como no Whisper; o backend segue o menu final de seis rotas (Smart (OpenCL),
 * Smart (Vulkan), CPU, GPU (OpenCL), GPU (Vulkan), NPU) — cada rota habilita
 * conforme `HyMt2BackendPolicy` (modelo/runtime) e mostra o motivo quando não.
 * O rodapé mostra um log numerado de cada passo para acompanhamento.
 */
class TextoActivity : AppCompatActivity() {

    private lateinit var inputText: EditText
    private lateinit var outputText: EditText
    private lateinit var buttonTranslate: TextView
    private lateinit var buttonModel: TextView
    private lateinit var buttonBackend: TextView
    private lateinit var buttonLanguage: TextView
    private lateinit var buttonThreads: TextView
    private lateinit var status: TextView
    private lateinit var terminalText: TextView
    private lateinit var terminalScroll: ScrollView

    private var selectedModel: HyMt2Model? = null
    private var selectedBackend = HyMt2BackendPolicy.Route.CPU
    private var selectedTarget: HyMt2Translator.TargetLanguage = HyMt2Translator.TARGET_LANGUAGES.first()
    private var loadedModelFile: File? = null
    private var loadedBackend: HyMt2BackendPolicy.Route? = null
    private var threadsLabel: String = "Auto"
    private var translating = false
    private var logCounter = 0

    /** Modelos oficiais Hy-MT2 (GGUF). O R2 (Cloudflare) e' a fonte PRIMARIA
     *  (mais rapido e sem rate limit); o HuggingFace entra como alternativa
     *  (fallback) quando o espelho existir. O Q4_0 (produzido a partir do
     *  Q8_0) so existe no R2; o 1.25bit so existe no HuggingFace. */
    private data class HyMt2Model(
        val label: String,
        val fileName: String,
        val file: File,
        /** Fonte primaria (R2). */
        val downloadUrl: String,
        /** Tamanho real publicado (medido no R2/HuggingFace em 25/09/2026). */
        val bytes: Long,
        /** Espelho oficial (HuggingFace) usado se a fonte primaria falhar. */
        val downloadUrlAlternativa: String? = null
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        keepContentInsideSystemBars()
        setContentView(R.layout.activity_texto)

        inputText = findViewById(R.id.input_text)
        outputText = findViewById(R.id.output_text)
        buttonTranslate = findViewById(R.id.button_translate)
        buttonModel = findViewById(R.id.button_model)
        buttonBackend = findViewById(R.id.button_backend)
        buttonLanguage = findViewById(R.id.button_language)
        buttonThreads = findViewById(R.id.button_threads)
        status = findViewById(R.id.status)
        terminalText = findViewById(R.id.terminal_text)
        terminalScroll = findViewById(R.id.terminal_scroll)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        buttonTranslate.setOnClickListener { translate() }
        buttonModel.setOnClickListener { showModelMenu() }
        buttonBackend.setOnClickListener { showBackendMenu() }
        buttonLanguage.setOnClickListener { showLanguageMenu() }
        buttonThreads.setOnClickListener { showThreadsMenu() }
        findViewById<TextView>(R.id.button_clear_translation).setOnClickListener {
            inputText.setText("")
            outputText.setText("")
            status.text = ""
        }
        findViewById<ImageButton>(R.id.button_copy_translation).setOnClickListener { copyTranslation() }
        findViewById<ImageButton>(R.id.button_paste_translation).setOnClickListener { pasteInput() }
        findViewById<ImageButton>(R.id.button_share_translation).setOnClickListener { shareTranslation() }
        findViewById<TextView>(R.id.button_save_translation).setOnClickListener { saveTranslation() }

        // Acoes do texto de entrada (mesmo conjunto da traducao).
        findViewById<TextView>(R.id.button_clear_input).setOnClickListener {
            inputText.setText("")
            status.text = "Texto original limpo."
            appendLog("Texto original limpo.")
        }
        findViewById<ImageButton>(R.id.button_share_input).setOnClickListener { shareInput() }
        findViewById<ImageButton>(R.id.button_copy_input).setOnClickListener { copyInput() }
        findViewById<ImageButton>(R.id.button_paste_input).setOnClickListener { pasteInput() }

        val models = officialModels()
        selectedModel = models.first()
        buttonModel.text = selectedModel!!.label
        selectedTarget = savedTargetLanguage()
        buttonLanguage.text = selectedTarget.label
        selectedBackend = savedBackend()
        buttonBackend.text = selectedBackend.shortLabel
        threadsLabel = threadsLabelSaved()
        buttonThreads.text = threadsLabel
        syncBackendWithModel()
        appendLog("Ferramenta Texto pronta.")
        appendLog(
            "Modelo: ${selectedModel!!.label} | Backend: ${selectedBackend.shortLabel} | " +
                "Idioma alvo: ${selectedTarget.label}"
        )
        if (!selectedModel!!.file.exists()) {
            appendLog("Modelo ainda não baixado — toque em Traduzir para baixar.")
        }
        carregarTextoCompartilhado(intent)
    }

    /**
     * Se a Activity foi aberta por compartilhamento de texto (ACTION_SEND),
     * coloca o conteúdo no campo de entrada. Também aceita o extra `extra_texto`
     * (usado pelo driver de benchmark para injetar o texto sem digitar).
     */
    private fun carregarTextoCompartilhado(intent: Intent?) {
        val texto = intent?.getStringExtra(Intent.EXTRA_TEXT)
            ?: intent?.getStringExtra("extra_texto")
        if (!texto.isNullOrBlank()) {
            inputText.setText(texto)
            appendLog("Texto recebido (${texto.length} caracteres).")
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        carregarTextoCompartilhado(intent)
    }

    override fun onDestroy() {
        Thread { HyMt2Native.releaseModel() }.start()
        super.onDestroy()
    }

    private fun modelsDir(): File {
        return getExternalFilesDir("hymt2_models")
            ?: File(filesDir, "hymt2_models")
    }

    /** Modelos oficiais Hy-MT2 1.8B, do MENOS ao MAIS bits (qualidade crescente).
     *
     * Medido no OnePlus 15 (CPU): 1.25bit=11 tok/s, Q4_0=12, Q4_K_M=14 — o modelo
     * MAIOR em bytes é o MAIS rápido. Ou seja, aqui "menos bytes" NÃO significa
     * "mais velocidade": o padrão de acesso do STQ (1 bit) perde para modelos
     * maiores e mais bem agrupados. Por isso o menu traz a curva inteira:
     * o benchmark escolhe o ponto ideal (qualidade × velocidade).
     *
     * Q8_0 é o topo prático: acima dele só existe F16/BF16 não quantizado
     * (~3,7 GB), inviável para celular.
     *
     * Só entram no menu os que EXISTEM de verdade: o repo oficial do HF publica
     * apenas Q4_K_M, Q6_K e Q8_0 (Q4_K_S, Q5_0 e Q5_K_M dão 404 — asurei).
     * Fontes: R2 = PRIMARIO (mais rápido, sem rate limit; espelho feito pelo
     * workflow .github/workflows/mirror-models-to-r2.yml), HuggingFace =
     * alternativa/fallback. O Q4_0 (produzido do Q8_0) e o 1.25bit (repo
     * separado no HF) têm fonte única e são marcados como tal. */
    /** Só para o EXPERIMENTO 1 do diagnóstico Vulkan (docs/vulkan-hymt2-plano-experimentos.txt).
     *
     * O modelo F16 (não quantizado) é uma ferramenta de DIAGNÓSTICO: separa
     * "defeito no caminho de quantização" de "problema geral de matmul no
     * Vulkan". Não faz sentido como produto — 3,59 GB contra 1,08 GB do Q4_K_M.
     *
     * Fica atrás de um arquivo-sinal (hymt2_f16.habilitar) para não poluir o
     * menu de quem usa o app de verdade. Apague o arquivo para sumir com ele.
     * Não é publicável: o HF não publica este GGUF, ele foi convertido do
     * modelo base BF16 (tencent/Hy-MT2-1.8B). */
    private fun diagnosticoF16(): HyMt2Model? {
        val dir = modelsDir()
        val arquivo = File(dir, "Hy-MT2-1.8B-f16.gguf")
        if (!arquivo.exists()) return null
        val habilitador = File(dir, "hymt2_f16.habilitar")
        if (!habilitador.exists()) return null
        return HyMt2Model(
            "F16 (diagnóstico)",
            arquivo.name,
            arquivo,
            "", // sem URL: não é baixável, só local
            arquivo.length()
        )
    }

    private fun officialModels(): List<HyMt2Model> {
        val dir = modelsDir().apply { mkdirs() }
        val r2 = "https://pub-6476622beda24c82875cb84f11f660ea.r2.dev/models/hymt2"
        val hf = "https://huggingface.co/tencent/Hy-MT2-1.8B-GGUF/resolve/main"
        fun r2Model(label: String, file: String, bytes: Long) =
            HyMt2Model(label, file, File(dir, file), "$r2/$file", bytes)
        /** Espelhado: primario no R2, alternativa no HuggingFace (mesmo nome
         *  de arquivo nos dois). E' o padrao dos modelos oficiais do Hy-MT2. */
        fun espelhado(label: String, file: String, bytes: Long) =
            HyMt2Model(label, file, File(dir, file), "$r2/$file", bytes,
                downloadUrlAlternativa = "$hf/$file")
        // ⚠️ A ORDEM IMPORTA: o primeiro da lista é o modelo inicial do app.
        // MEDIDO (OnePlus 15, 112 linhas, 6 threads, CPU — ver
        // docs/relatorio-benchmark-qualidade-texto-hymt2.md):
        //   Q4_K_M = 10,0 tok/s, 13/13 siglas e 9/9 nomes preservados, 1,08 GB
        //   Q8_0   = 10,5 tok/s (NÃO é mais lento), melhor divergência (2,6%)
        //   Q6_K   = 8,1 tok/s (o único que perde velocidade)
        //   Q4_0   = 10,1 tok/s, 12/13 siglas
        //   1.25bit= TRUNCA a saída (728 vs 2.458 tokens) e perde 38% dos números
        //            → só serve para frase curta, por isso fica no fim.
        return listOf(
            espelhado("Q4_K_M", "Hy-MT2-1.8B-Q4_K_M.gguf", 1_133_080_448L),
            espelhado("Q8_0", "Hy-MT2-1.8B-Q8_0.gguf", 1_908_528_192L),
            r2Model("Q4_0", "Hy-MT2-1.8B-Q4_0.gguf", 1_076_850_528L),
            espelhado("Q6_K", "Hy-MT2-1.8B-Q6_K.gguf", 1_474_785_120L),
            HyMt2Model(
                "1.25bit",
                "Hy-MT2-1.8B-1.25Bit.gguf",
                File(dir, "Hy-MT2-1.8B-1.25Bit.gguf"),
                "$r2/Hy-MT2-1.8B-1.25Bit.gguf",
                461_860_800L,
                downloadUrlAlternativa =
                    "https://huggingface.co/tencent/Hy-MT2-1.8B-1.25Bit-GGUF/resolve/main/Hy-MT2-1.8B-1.25Bit.gguf"
            )
        ) + listOfNotNull(diagnosticoF16())
    }

    private fun showModelMenu() {
        val models = officialModels()
        PopupMenu(this, buttonModel).apply {
            models.forEachIndexed { index, model ->
                val display = if (model.file.exists()) "✓ ${model.label}" else model.label
                menu.add(0, index + 1, 0, display)
            }
            setOnMenuItemClickListener { item ->
                val model = models.getOrNull(item.itemId - 1) ?: return@setOnMenuItemClickListener true
                if (model.file.exists()) {
                    selectModel(model)
                } else {
                    confirmModelDownload(model)
                }
                true
            }
            show()
        }
    }

    private fun selectModel(model: HyMt2Model) {
        selectedModel = model
        buttonModel.text = model.label
        appendLog("Modelo selecionado: ${model.label}")
        syncBackendWithModel()
    }

    private fun confirmModelDownload(model: HyMt2Model) {
        // O diálogo não dizia tamanho nenhum — e o Q8_0 são 1,9 GB.
        DownloadPlanDialog.confirmar(
            activity = this,
            plano = DownloadSizeFormat.Plano(
                rotulo = "Modelo Hy-MT2 ${model.label}",
                arquivos = listOf(
                    DownloadSizeFormat.Arquivo(model.fileName, model.bytes, jaBaixado = model.file.exists())
                ),
            ),
            negativo = "Cancelar",
            positivo = "Baixar",
            prefacio = "Ainda não temos o modelo ${model.label} no aparelho.",
            sufixo = "Baixar agora?",
        ) {
            downloadModelWithProgress(model) { selectModel(model) }
        }
    }

    private fun downloadModelWithProgress(model: HyMt2Model, onSuccess: () -> Unit) {
        // Lista que acumula (item 1 do plano): o usuário vê o ✓ no fim e rola
        // para cima para ler tudo antes de continuar.
        val plano = DownloadSizeFormat.Plano(
            rotulo = "Modelo Hy-MT2 ${model.label}",
            arquivos = listOf(
                DownloadSizeFormat.Arquivo(model.fileName, model.bytes, jaBaixado = model.file.exists())
            ),
        )
        val progresso = DownloadPlanDialog.progresso(this, plano, titulo = "Baixando modelo ${model.label}")
        appendLog("Baixando modelo ${model.label}...")
        Thread {
            try {
                model.file.parentFile?.mkdirs()
                val temp = File(model.file.parentFile, "${model.fileName}.download")

                // ⚠️ HuggingFace responde 302 para o CDN. Sem seguir o redirect, o
                // corpo da resposta de 15 bytes ("Entry not found") era gravado
                // como se fosse o modelo — daí "Preparando download" longo e barra
                // que não anda. Aqui resolvemos o redirect e, se a resposta não for
                // 200, falamos na hora com o motivo real.
                // Fonte primaria = R2 (Cloudflare); alternativa = HuggingFace.
                // A alternativa e' usada quando o R2 ainda nao tem o arquivo
                // espelhado ou esta indisponivel — o download nunca regride.
                val fontes = listOfNotNull(model.downloadUrl, model.downloadUrlAlternativa)
                var ultimaFalha: Throwable? = null
                var conexaoEscolhida: java.net.HttpURLConnection? = null
                for (fonte in fontes) {
                    try {
                        var url = URL(fonte)
                        var tentativas = 0
                        var http: java.net.HttpURLConnection? = null
                        while (tentativas < 5) {
                            val c = url.openConnection() as java.net.HttpURLConnection
                            c.connectTimeout = 20000
                            c.readTimeout = 120000
                            c.instanceFollowRedirects = false
                            val code = c.responseCode
                            if (code == java.net.HttpURLConnection.HTTP_MOVED_PERM ||
                                code == java.net.HttpURLConnection.HTTP_MOVED_TEMP ||
                                code == java.net.HttpURLConnection.HTTP_SEE_OTHER ||
                                code == 307 || code == 308
                            ) {
                                val destino = c.getHeaderField("Location")
                                if (destino.isNullOrBlank()) {
                                    c.disconnect()
                                    throw IllegalStateException(
                                        "O servidor mandou redirecionar sem destino (HTTP $code)."
                                    )
                                }
                                url = URL(URL(fonte), destino)
                                c.disconnect()
                                tentativas++
                                continue
                            }
                            http = c
                            break
                        }
                        if (http == null) {
                            throw IllegalStateException("Não foi possível resolver o endereço do modelo.")
                        }
                        if (http.responseCode != 200) {
                            val code = http.responseCode
                            val motivo = runCatching { http.errorStream?.bufferedReader()?.readText() }
                                .getOrNull()?.trim()?.take(120).orEmpty()
                            http.disconnect()
                            throw IllegalStateException(
                                when (code) {
                                    404 -> "Este modelo não existe no repositório (HTTP 404)."
                                    403 -> "Sem permissão para baixar este modelo (HTTP 403)."
                                    else -> "O servidor respondeu HTTP $code. $motivo"
                                }
                            )
                        }
                        if (fonte != model.downloadUrl) {
                            Log.i(TAG, "Fonte primaria indisponivel; usando alternativa: $fonte")
                        }
                        conexaoEscolhida = http
                        break
                    } catch (e: Throwable) {
                        ultimaFalha = e
                        Log.w(TAG, "Fonte indisponivel ($fonte): ${e.message ?: "falha"}")
                    }
                }
                // HttpURLConnection não implementa Closeable: disconnect() no finally.
                val conn: java.net.HttpURLConnection =
                    conexaoEscolhida ?: throw (ultimaFalha ?: IllegalStateException("Download falhou."))
                try {
                    val code = conn.responseCode
                    if (code != 200) {
                        throw IllegalStateException("O servidor respondeu HTTP $code.")
                    }
                    val total = conn.contentLengthLong
                    conn.getInputStream().use { input ->
                        FileOutputStream(temp).use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var copied = 0L
                            var lastUi = 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                copied += read
                                val now = SystemClock.elapsedRealtime()
                                if (now - lastUi > 300L) {
                                    lastUi = now
                                    val percent = if (total > 0L) (copied * 100L / total).coerceIn(0L, 100L) else -1L
                                    runOnUiThread {
                                        progresso.atualizar(
                                            copied, total, percent.toInt(),
                                            if (percent >= 100L) plano.arquivos.map { it.nome }.toSet() else emptySet(),
                                        )
                                    }
                                }
                            }
                        }
                    }
                } finally {
                    conn.disconnect()
                }
                if (model.file.exists()) model.file.delete()
                if (!temp.renameTo(model.file)) {
                    temp.copyTo(model.file, overwrite = true)
                    temp.delete()
                }
                runOnUiThread {
                    progresso.atualizar(model.file.length(), model.file.length(), 100, plano.arquivos.map { it.nome }.toSet())
                    progresso.concluirContinuando(model.file.length())
                    appendLog("Modelo ${model.label} baixado (${DownloadSizeFormat.legivel(model.file.length())}).")
                    onSuccess()
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Download failed", e)
                runOnUiThread {
                    progresso.fechar()
                    appendLog("ERRO ao baixar ${model.label}: ${e.message ?: "falha inesperada"}")
                    status.text = "Erro ao baixar ${model.label}: ${e.message ?: "falha inesperada"}"
                }
            }
        }.start()
    }

    private fun showBackendMenu() {
        val model = selectedModel ?: return
        val rotas = HyMt2BackendPolicy.availability(model.fileName, runtimeSupport())
        PopupMenu(this, buttonBackend).apply {
            // As SEIS rotas aparecem sempre; a indisponível fica desabilitada com o
            // motivo no próprio item (nada de esconder o que existe).
            rotas.forEachIndexed { index, disponibilidade ->
                val titulo = if (disponibilidade.available) disponibilidade.route.label
                else "${disponibilidade.route.label} — ${disponibilidade.reason}"
                menu.add(0, index + 1, 0, titulo).isEnabled = disponibilidade.available
            }
            setOnMenuItemClickListener { item ->
                val disponibilidade = rotas.getOrNull(item.itemId - 1)
                    ?: return@setOnMenuItemClickListener true
                if (disponibilidade.route != selectedBackend) {
                    selectedBackend = disponibilidade.route
                    buttonBackend.text = disponibilidade.route.shortLabel
                    // O modelo precisa recarregar na rota nova na próxima tradução.
                    loadedModelFile = null
                    loadedBackend = null
                    getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit().putString(PREF_BACKEND, disponibilidade.route.id).apply()
                    appendLog("Backend selecionado: ${disponibilidade.route.shortLabel} (modelo será recarregado)")
                    ajudaDaRota(disponibilidade.route)
                }
                true
            }
            show()
        }
    }

    /** Alinha o backend com o modelo escolhido.
     *
     * Se a rota selecionada não vale para o modelo (ex.: 1.25bit sem kernel
     * acelerado), volta para CPU e avisa com o motivo — o menu mostra o mesmo
     * motivo em cada rota indisponível (nada de degradar em silêncio). */
    private fun syncBackendWithModel() {
        val model = selectedModel ?: return
        if (selectedBackend == HyMt2BackendPolicy.Route.CPU) return
        val disponibilidade =
            HyMt2BackendPolicy.availability(selectedBackend, model.fileName, runtimeSupport())
        if (disponibilidade.available) return
        val motivo = disponibilidade.reason ?: "indisponível"
        selectedBackend = HyMt2BackendPolicy.Route.CPU
        buttonBackend.text = selectedBackend.shortLabel
        loadedModelFile = null
        loadedBackend = null
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(PREF_BACKEND, selectedBackend.id).apply()
        appendLog("${disponibilidade.route.label} indisponível ($motivo) — backend voltou para CPU.")
    }

    /** Backend persistido (id estável); id desconhecido/ausente volta para CPU. */
    private fun savedBackend(): HyMt2BackendPolicy.Route {
        val id = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_BACKEND, null)
        return HyMt2BackendPolicy.Route.fromId(id)
    }

    /** Rota efetiva para um modelo: a selecionada quando disponível; senão CPU.
     *
     * A diferença nunca é silenciosa: [translate] registra o motivo no log. */
    private fun effectiveBackend(model: HyMt2Model): HyMt2BackendPolicy.Route {
        val disponibilidade =
            HyMt2BackendPolicy.availability(selectedBackend, model.fileName, runtimeSupport())
        return if (disponibilidade.available) selectedBackend else HyMt2BackendPolicy.Route.CPU
    }

    /** Recursos de runtime para a política de rotas.
     *
     * NPU (R68/E4): `hasNpu()` = device HTP0 presente no runtime (backend Hexagon
     * embutido na lib do pacote). Antes do pacote com o motor, retorna false e as
     * rotas aceleradas aparecem com motivo — nunca em silêncio.
     * GPU: os devices não são pré-checados aqui (o load reporta o diagnóstico
     * real na primeira tradução, como hoje). */
    private fun runtimeSupport(): HyMt2BackendPolicy.RuntimeSupport {
        val npuPresente = runCatching { HyMt2Native.hasNpu() }.getOrDefault(false)
        return HyMt2BackendPolicy.RuntimeSupport(
            npu = npuPresente,
            gpuOpenCl = true,
            gpuVulkan = true,
        )
    }

    /** Ajuda curta da rota, no padrão aprovado ("NPU processa o prompt; GPU gera a resposta"). */
    private fun ajudaDaRota(rota: HyMt2BackendPolicy.Route) {
        when (rota) {
            HyMt2BackendPolicy.Route.SMART_OPENCL ->
                appendLog("Smart (OpenCL): a NPU processa o prompt; a GPU OpenCL gera a resposta.")
            HyMt2BackendPolicy.Route.SMART_VULKAN ->
                appendLog("Smart (Vulkan): a NPU processa o prompt; a GPU Vulkan gera a resposta.")
            HyMt2BackendPolicy.Route.NPU ->
                appendLog("NPU: prompt e resposta no HTP; operações não suportadas ficam visíveis no log.")
            else -> Unit
        }
    }

    private fun showLanguageMenu() {
        PopupMenu(this, buttonLanguage).apply {
            HyMt2Translator.TARGET_LANGUAGES.forEach { language -> menu.add(language.label) }
            setOnMenuItemClickListener { item ->
                val language = HyMt2Translator.TARGET_LANGUAGES.firstOrNull {
                    it.label == item.title.toString()
                } ?: return@setOnMenuItemClickListener true
                selectTargetLanguage(language)
                true
            }
            show()
        }
    }

    private fun selectTargetLanguage(language: HyMt2Translator.TargetLanguage) {
        selectedTarget = language
        buttonLanguage.text = language.label
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(PREF_TARGET_LANGUAGE, language.label).apply()
        appendLog("Idioma alvo: ${language.label} (${language.promptName})")
    }

    /** Idioma-alvo persistido; rótulo desconhecido volta para o padrão (Português). */
    private fun savedTargetLanguage(): HyMt2Translator.TargetLanguage {
        val label = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_TARGET_LANGUAGE, null)
        return HyMt2Translator.TARGET_LANGUAGES.firstOrNull { it.label == label }
            ?: HyMt2Translator.TARGET_LANGUAGES.first()
    }

    /** Rótulo salvo das threads (para mostrar no botão ao reabrir a tela). */
    private fun threadsLabelSaved(): String {
        val pref = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_THREADS, "auto")
        if (pref == null || pref == "auto") {
            return "Auto ${autoThreads()}"
        }
        return "$pref thr"
    }

    /** Chamadas nativas informativas: nunca podem derrubar a tela.
     *
     * Todas existem só a partir do pacote nativo correspondente (v8/v9). Com um
     * pacote mais antigo, a chamada lança UnsatisfiedLinkError e o app fecha no
     * meio da tradução — foi o que aconteceu na 20260925_002. */
    private fun nativoSeguro(chamada: () -> String): String = try {
        chamada()
    } catch (e: Throwable) {
        ""
    }

    private fun resumoDeMemoria(): String = nativoSeguro { HyMt2Native.loadSummary() }

    private fun statsDeDesempenho(): String = nativoSeguro { HyMt2Native.lastStats() }

    /** Threads que o contexto realmente criou (informativo, nunca pode quebrar a tela).
     *
     * ⚠️ `threadCount()` é nativa e só existe a partir do pacote nativo v9. Se o
     * aparelho ainda tiver um pacote antigo, a chamada lança UnsatisfiedLinkError
     * e matava o app no meio da tradução — daí o runCatching. */
    private fun threadsEmUso(): String = try {
        HyMt2Native.threadCount()
    } catch (e: Throwable) {
        inferenceThreads().let { if (it == 0) "auto" else it.toString() }
    }

    /** Quantas threads de CPU usar na inferência.
     *
     * MEDIDO no OnePlus 15 (8 núcleos) com 5 quantizações diferentes (1.25bit,
     * Q4_0, Q4_K_M, Q6_K, Q8_0) — a curva é **idêntica em todos os modelos**, o
     * que prova que o gargalo é thread, não quantização nem banda de memória:
     *
     *     1 thread  ~ 4 tok/s      4 threads  ~ 14-20 tok/s
     *     2 threads ~ 7,5 tok/s    6 threads  ~ 18-24 tok/s  ← pico
     *     3 threads ~ 11 tok/s     8 threads  ~ 16-17 tok/s  (pior: cache)
     *     12/16 threads ~ 0,1-0,2 tok/s  (colapso por over-subscription)
     *
     * Portanto: 1→6 escala quase linearmente, 8 piora (contenção de cache — usar
     * todos os núcleos faz o escalonador migrar as threads o tempo todo) e 12+
     * é desastre. Como o app é para qualquer Android, o padrão não pode ser 6
     * fixo: num aparelho de 4 núcleos isso seria metade do potencial.
     *
     * Heurística escolhida: **max(n-2, min(4, n))** —
     *   8 núcleos  -> 6 threads (o pico medido)
     *   6 núcleos  -> 4 threads
     *   4 núcleos  -> 4 threads (o piso de 4 protege o entry-level)
     *   2 núcleos  -> 2 threads
     * Devolve 0 quando o usuário deixa em "Automático"? Não: devolvemos o valor
     * calculado, porque 0 (deixar o llama.cpp escolher) foi o que travou em ~4.
     *
     * O menu de 1..16 continua disponível para medir. */
    private fun inferenceThreads(): Int {
        val pref = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_THREADS, "auto")
        if (pref == null || pref == "auto") return autoThreads()
        return pref.toIntOrNull()?.coerceIn(1, 16) ?: autoThreads()
    }

    /** n-2, com piso de 4 e teto de n (ver KDoc de [inferenceThreads]). */
    private fun autoThreads(): Int {
        val n = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        return maxOf(n - 2, minOf(4, n))
    }

    private fun showThreadsMenu() {
        val n = Runtime.getRuntime().availableProcessors()
        // "Auto" mostra o valor que a heuristica n-2 escolhe; 1..16 fica
        // disponivel para medir (over-subscription as vezes ajuda, as vezes e
        // desastre — ver o KDoc de inferenceThreads).
        val opcoes = listOf("auto" to "Auto (n-2 = ${autoThreads()} de $n)") +
            (1..16).map { it.toString() to "$it threads" }
        PopupMenu(this, buttonThreads).apply {
            opcoes.forEachIndexed { index, opcao ->
                menu.add(0, index + 1, 0, opcao.second)
            }
            setOnMenuItemClickListener { item ->
                val opcao = opcoes.getOrNull(item.itemId - 1) ?: return@setOnMenuItemClickListener true
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putString(PREF_THREADS, opcao.first).apply()
                threadsLabel = opcao.second
                buttonThreads.text = threadsLabel
                // o modelo recarrega com o novo n_threads
                loadedModelFile = null
                loadedBackend = null
                appendLog("Threads: ${opcao.second} (o modelo será recarregado)")
                true
            }
            show()
        }
    }

    private fun translate() {
        if (translating) return
        val source = inputText.text.toString().trim()
        if (source.isBlank()) {
            status.text = "Digite o texto para traduzir."
            return
        }
        val model = selectedModel ?: return
        translating = true
        buttonTranslate.isEnabled = false
        status.text = "Traduzindo..."
        val startedAt = SystemClock.elapsedRealtime()
        val backendVez = effectiveBackend(model)
        appendLog("Traduzindo com ${model.label} (${backendVez.shortLabel}) para ${selectedTarget.label}...")
        if (backendVez != selectedBackend) {
            val motivo = HyMt2BackendPolicy
                .availability(selectedBackend, model.fileName, runtimeSupport()).reason
                ?: "indisponível para este modelo"
            appendLog("${selectedBackend.label} indisponível ($motivo) — executando em CPU.")
        }

        Thread {
            try {
                if (!model.file.exists()) {
                    runOnUiThread {
                        translating = false
                        buttonTranslate.isEnabled = true
                        status.text = "Modelo ausente."
                        appendLog("Modelo ${model.label} não está no aparelho.")
                        confirmModelDownload(model)
                    }
                    return@Thread
                }
                // Recarrega o modelo quando o arquivo OU o backend efetivo mudam.
                if (loadedModelFile != model.file || loadedBackend != backendVez) {
                    runOnUiThread {
                        appendLog("Carregando modelo ${model.label} (${backendVez.shortLabel})...")
                        status.text = "Carregando modelo..."
                    }
                    val loadStart = SystemClock.elapsedRealtime()
                    val ok = HyMt2Native.loadModel(
                        model.file.absolutePath,
                        backendVez.nativeKind,
                        inferenceThreads(),
                        8192
                    )
                    if (!ok) {
                        val error = HyMt2Native.lastError()
                        runOnUiThread {
                            translating = false
                            buttonTranslate.isEnabled = true
                            // Tempo de carga tambem no caminho de ERRO: o log
                            // mostra "apos X.Xs" sempre que uma carga e' tentada.
                            appendLog(
                                "ERRO ao carregar o modelo apos " +
                                    "%.1fs".format(
                                        Locale.US,
                                        (SystemClock.elapsedRealtime() - loadStart) / 1000.0
                                    ) + ": $error"
                            )
                            status.text = "Erro ao carregar o modelo (ver log)."
                        }
                        return@Thread
                    }
                    loadedModelFile = model.file
                    loadedBackend = backendVez
                    val backendInUse = nativoSeguro { HyMt2Native.backendDescription() }
                        .ifBlank { backendVez.label }
                    runOnUiThread {
                        appendLog(
                            "Modelo carregado em " +
                                "%.1fs.".format(Locale.US, (SystemClock.elapsedRealtime() - loadStart) / 1000.0)
                        )
                        appendLog("Backend em uso: $backendInUse")
                        appendLog("Threads: ${threadsEmUso()}")
                        // UX FIX (rodada ANR/UI): fim da fase de carga - antes o rotulo
                        // "Carregando modelo..." permanecia ate o fim da traducao.
                        status.text = "Traduzindo..."
                        val resumo = resumoDeMemoria()
                        if (resumo.isNotBlank()) {
                            appendLog(
                                "Memória: " +
                                    resumo.lines().filter { it.isNotBlank() }.joinToString(" | ")
                            )
                        }
                    }
                }

                val prompt = HyMt2Translator.wrapWithChatTemplate(
                    HyMt2Translator.buildUserPrompt(source, selectedTarget.promptName)
                )
                val translation = HyMt2Native.generate(
                    prompt,
                    HyMt2Translator.MAX_TOKENS,
                    HyMt2Translator.TEMPERATURE,
                    HyMt2Translator.TOP_P,
                    HyMt2Translator.TOP_K,
                    HyMt2Translator.REPEAT_PENALTY
                )
                if (translation == null) {
                    val error = HyMt2Native.lastError()
                    runOnUiThread {
                        translating = false
                        buttonTranslate.isEnabled = true
                        appendLog("ERRO na geração: $error")
                        status.text = "Erro na tradução."
                    }
                    return@Thread
                }
                runOnUiThread {
                    translating = false
                    buttonTranslate.isEnabled = true
                    outputText.setText(translation)
                    val seconds = (SystemClock.elapsedRealtime() - startedAt) / 1000.0
                    appendLog(
                        "Tradução pronta em %.1fs.".format(Locale.US, seconds)
                    )
                    val stats = statsDeDesempenho()
                    if (stats.isNotBlank()) {
                        appendLog("Desempenho: $stats")
                    }
                    status.text = "Tradução pronta."
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Translation failed", e)
                runOnUiThread {
                    translating = false
                    buttonTranslate.isEnabled = true
                    appendLog("ERRO inesperado: ${e.message ?: e.javaClass.simpleName}")
                    status.text = "Erro na tradução."
                }
            }
        }.start()
    }

    /**
     * Compartilha um texto por Intent (o mesmo caminho do botão Compartilhar
     * das ferramentas FFmpeg). Usado tanto para o texto original quanto para
     * a tradução.
     */
    private fun shareText(texto: String, rotulo: String): Boolean {
        if (texto.isBlank()) {
            status.text = "Não há $rotulo para compartilhar."
            return false
        }
        return try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, texto)
            }
            startActivity(Intent.createChooser(intent, "Compartilhar $rotulo"))
            appendLog("$rotulo enviado para compartilhar.")
            true
        } catch (e: Exception) {
            status.text = "Erro ao compartilhar: ${e.message}"
            appendLog("Falha ao compartilhar $rotulo: ${e.message}")
            false
        }
    }

    /**
     * Copia um texto para a área de transferência.
     */
    private fun copyText(texto: String, rotulo: String): Boolean {
        if (texto.isBlank()) {
            status.text = "Não há $rotulo para copiar."
            return false
        }
        return try {
            val clipboard = getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText(rotulo, texto))
            Toast.makeText(this, "$rotulo copiado", Toast.LENGTH_SHORT).show()
            appendLog("$rotulo copiado para a área de transferência.")
            true
        } catch (e: Exception) {
            status.text = "Erro ao copiar: ${e.message}"
            appendLog("Falha ao copiar $rotulo: ${e.message}")
            false
        }
    }

    /** Compartilha a tradução. */
    private fun shareTranslation() =
        shareText(outputText.text.toString(), "tradução")

    /** Compartilha o texto original digitado. */
    private fun shareInput() =
        shareText(inputText.text?.toString().orEmpty(), "texto original")

    /** Copia a tradução. */
    private fun copyTranslation() =
        copyText(outputText.text.toString(), "tradução")

    /** Copia o texto original digitado. */
    private fun copyInput() =
        copyText(inputText.text?.toString().orEmpty(), "texto original")

    /**
     * Cola o conteúdo da área de transferência no campo de entrada.
     * Útil para reusar um texto sem digitar de novo (e para o driver de
     * benchmark, que precisa inserir o mesmo texto em várias execuções).
     */
    private fun pasteInput() {
        try {
            val clipboard = getSystemService(ClipboardManager::class.java)
            val texto = clipboard.primaryClip?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)?.coerceToText(this)?.toString()
            if (texto.isNullOrBlank()) {
                status.text = "Área de transferência vazia."
                appendLog("Colar: a área de transferência está vazia.")
                return
            }
            val atual = inputText.text?.toString().orEmpty()
            val inicio = inputText.selectionStart.coerceAtLeast(0)
            val fim = inputText.selectionEnd.coerceAtLeast(0)
            if (fim > inicio) {
                // ha selecao: substitui, como um colar normal
                inputText.text?.replace(inicio, fim, texto)
            } else {
                inputText.setText(atual + texto)
            }
            status.text = "Texto colado (${texto.length} caracteres)."
            appendLog("Colado da área de transferência: ${texto.length} caracteres.")
        } catch (e: Exception) {
            status.text = "Erro ao colar: ${e.message}"
            appendLog("Falha ao colar: ${e.message}")
        }
    }

    /**
     * Grava a tradução atual em arquivo para inspeção/comparação posterior.
     *
     * Fica em `files/textos/` (diretório externo da app, acessível por adb
     * sem root) e o nome carrega modelo + backend + horário, para permitir
     * comparar várias execuções do mesmo texto.
     */
    private fun saveTranslation() {
        val text = outputText.text.toString()
        if (text.isBlank()) {
            status.text = "Não há tradução para salvar."
            return
        }
        try {
            val dir = getExternalFilesDir("textos")?.apply { mkdirs() }
                ?: throw IllegalStateException("sem acesso ao armazenamento externo")
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val safeModel = (selectedModel?.label ?: "modelo").replace(Regex("[^A-Za-z0-9._-]"), "_")
            val safeBackend = selectedBackend.shortLabel.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val file = File(dir, "trad_${safeModel}_${safeBackend}_$stamp.txt")
            file.writeText(text, Charsets.UTF_8)
            status.text = "Tradução salva em ${file.name}"
            appendLog("Tradução salva: ${file.absolutePath} (${text.length} caracteres)")
            Toast.makeText(this, "Tradução salva", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            status.text = "Erro ao salvar: ${e.message}"
            appendLog("Falha ao salvar tradução: ${e.message}")
        }
    }

    /** Acrescenta uma linha numerada ao log do rodapé (padrão de logs do usuário). */
    private fun appendLog(line: String) {
        logCounter++
        terminalText.append(String.format(Locale.US, "%2d. %s\n", logCounter, line))
        terminalScroll.post { terminalScroll.fullScroll(View.FOCUS_DOWN) }
    }

    companion object {
        private const val TAG = "TextoActivity"
        private const val PREFS_NAME = "texto_settings"
        private const val PREF_TARGET_LANGUAGE = "target_language"
        private const val PREF_BACKEND = "backend"
        private const val PREF_THREADS = "threads"
    }
}
