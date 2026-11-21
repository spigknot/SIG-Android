package br.gov.sp.pcsp.launcher

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * Rota padrão ORT/CPU para arquivos, com janelas temporais e resultado persistente.
 * Gravação e ao vivo continuam no whisper.cpp. Admissão, reserva, cancelamento
 * e temporários permanecem com o job até o término físico do trabalho.
 */
object WhisperOrtRoute {

    /**
     * Chave da preferência INVERTIDA: "usar whisper.cpp".
     *
     * Decisão do usuário (29/10): ORT/CPU passa a ser o motor PADRÃO da rota de
     * arquivo, e a caixa "usar whisper.cpp" nas Opções avançadas desliga o ORT.
     * O default do app deixa de ser CPP apenas onde esta rota atende; gravação e
     * ao vivo seguem no whisper.cpp.
     */
    const val PREF_CPP = "whisper_engine_cpp"

    /** Chave antiga, mantida só para migração de preferência. */
    const val PREF_KEY = "whisper_engine_ort"

    private const val TAG = "WhisperOrtRoute"

    /** Teto de alocação do PCM: 20 minutos (≈76 MB em FloatArray). */
    private const val MAX_AMOSTRAS = 20 * 60 * WhisperOrtPcm.SAMPLE_RATE

    /** Teto em BYTES do arquivo de entrada: PCM16 20 min + folga de cabeçalho. */
    private const val MAX_BYTES_AUDIO = MAX_AMOSTRAS * 2L + 1_048_576L

    /** Limite desta rota: uma janela de 30 s. */
    const val MAX_SECONDS = 30.0

    /**
     * Cache de sessões do processo (mesmo do laboratório).
     *
     * R100 §2.4 — a fábrica pergunta a ROTA: `htp` abre encoder EPContext W8A16 +
     * decoders CPU (e lança [WhisperOrtHtpRota.Recusa] quando não pode); `cpu`
     * abre a sessão de sempre. O produtor transforma a recusa em fallback do
     * MESMO pedido, não em erro para o usuário.
     */
    private val cache = WhisperOrtSessionCache(
        maxEntries = 1,
        abrirPorRota = { rota, encoder, decoder, past, threads ->
            if (WhisperOrtHtpAdmissao.isHtp(rota)) {
                WhisperOrtHtpRota.abrir(encoder, decoder, past, threads, rota)
            } else {
                WhisperOrtSession(encoder, decoder, past, threads)
            }
        },
    )

    /** Admissão single-flight e terminal do job (M4). */
    private val admission = WhisperOrtAdmission()

    /** Cache efetivo: produção usa o interno; teste injeta backend controlado. */
    @Volatile
    var cacheParaTeste: WhisperOrtSessionCache? = null

    /**
     * Motor FFmpeg e prazo de conversão injetáveis (testes instrumentados).
     * Produção usa o [WhisperOrtMediaConverter.MotorFfmpeg] real com o prazo
     * de 60 s; o dreno continua idêntico — só o relógio/músculo mudam.
     */
    @Volatile
    var motorConversaoParaTeste: WhisperOrtMediaConverter.Motor? = null

    @Volatile
    var timeoutConversaoMsParaTeste: Long? = null

    private val cacheEfetivo: WhisperOrtSessionCache
        get() = cacheParaTeste ?: cache

    /**
     * `true` quando [motivoRecusa] de recusa pode oferecer a troca para whisper.cpp.
     *
     * Delega à porta pura (verificável em JVM).
     */
    fun isFallbackElegivel(motivoRecusa: String): Boolean =
        WhisperOrtPorta.isFallbackElegivel(motivoRecusa)

    /**
     * Threads do compute (1–8) — seletor COMPARTILHADO ORT + whisper.cpp.
     *
     * Pedido do usuário: controle na ferramenta com default 4 (confirmado pela
     * varredura 1–8 no PJA110, §66) e máximo 8. A rota lê via [threadsEfetivos]
     * a cada despacho ORT; o caminho CPP aplica o mesmo valor por job via
     * seam one-shot (sem virar config global). A chave é neutra (`whisper_threads`);
     * a legada (`whisper_ort_threads`) é migrada uma vez e depois ignorada.
     */
    const val THREADS_MIN = 1
    const val THREADS_MAX = 8
    private const val PREF_THREADS = "whisper_threads"
    private const val PREF_THREADS_LEGADO = "whisper_ort_threads"

    fun threadsEfetivos(context: Context): Int {
        val prefs = context.getSharedPreferences("whisper", Context.MODE_PRIVATE)
        val nova: Int? = if (prefs.contains(PREF_THREADS)) prefs.getInt(PREF_THREADS, 0) else null
        val legada: Int? = if (prefs.contains(PREF_THREADS_LEGADO)) {
            prefs.getInt(PREF_THREADS_LEGADO, WhisperOrtSession.DEFAULT_THREADS)
        } else null
        val efetivas = WhisperOrtSelecao.resolverThreads(
            nova, legada, WhisperOrtSession.DEFAULT_THREADS,
            WhisperOrtRoute.THREADS_MIN, WhisperOrtRoute.THREADS_MAX,
        )
        if (nova == null && legada != null) {
            // Migração única: preserva o ajuste de quem já usava a chave antiga.
            prefs.edit().putInt(PREF_THREADS, efetivas).apply()
        }
        return efetivas
    }

    /**
     * Lógica pura do seletor compartilhado (sem Android, testável em JVM).
     *
     * Precedência: chave nova > chave legada > default; tudo coagido a 1–8.
     */
    fun resolverThreads(nova: Int?, legada: Int?, padrao: Int): Int =
        (nova ?: legada ?: padrao).coerceIn(THREADS_MIN, THREADS_MAX)

    fun setThreads(context: Context, threads: Int) {
        context.getSharedPreferences("whisper", Context.MODE_PRIVATE)
            .edit().putInt(PREF_THREADS, threads.coerceIn(THREADS_MIN, THREADS_MAX)).apply()
    }

    /** Job em execução, para o pedido de cancelamento externo. */
    @Volatile
    private var jobAtual: WhisperOrtAdmission.Job? = null

    /** Recibo da última publicação — a entrega NÃO é presumida sem ele. */
    @Volatile
    private var ultimoRecibo: WhisperOrtTerminalSink.Recibo? = null

    /** Recibo do terminal mais recente (`null` = não entregue). */
    fun ultimoRecibo(): WhisperOrtTerminalSink.Recibo? = ultimoRecibo

    /** Terminais publicados sem confirmação de entrega (reentrega após destroy). */
    fun pendentes(app: Context): List<WhisperOrtTerminalSink.Recibo> =
        WhisperOrtTerminalSink.pendentes(app)

    /** Confirma a entrega de [recibo] (só após o resultado ser exibido/salvo). */
    fun confirmarEntrega(app: Context, recibo: WhisperOrtTerminalSink.Recibo): Boolean =
        WhisperOrtTerminalSink.confirmar(app, recibo)

    /**
     * Pede o cancelamento do job em execução.
     *
     * Não interrompe nada à força: marca o pedido e o laço o observa entre
     * tokens; o terminal sai CANCELLED com o texto já produzido.
     */
    fun cancelar(dono: WhisperOrtAdmission.Job? = jobAtual): Boolean {
        val job = dono ?: return false
        if (job != jobAtual) return false
        return admission.requestCancel(job)
    }

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "whisper-ort-route").apply { isDaemon = true }
    }

    private val main = Handler(Looper.getMainLooper())

    /** Resultado tipado da rota. */
    sealed class Result {
        /**
         * Recibo da entrega DESTA tentativa.
         *
         * Precisa viajar no resultado: com um campo global, confirmar o resultado
         * A confirmava o recibo mais recente (B) e deixava A pendente para sempre.
         * `null` significa que a publicação FALHOU — não é "não aplicável".
         */
        var recibo: WhisperOrtTerminalSink.Recibo? = null
            internal set

        data class Done(
            val text: String,
            val outcome: String,
            val millis: Long,
            val cacheHit: Boolean,
            /** Custo de abertura DESTA tentativa (0 no hit). */
            val openMillis: Long = 0L,
            /** R12/§3 — áudio efetivamente processado (nulo = não medido). */
            val audioMillis: Long? = null,
            /** Modelo EFETIVO do pacote que rodou (nulo em recibo antigo). */
            val modelLabel: String? = null,
            /** Hardware efetivo da rota ORT (CPU por construção). */
            val hardwareLabel: String? = "CPU",
        ) : Result()

        /**
         * Cancelado pelo usuário.
         *
         * [text] é o que já havia sido produzido; [partial] indica que o texto não
         * cobre o áudio. Cancelamento NÃO é falha de compatibilidade: nunca deve
         * virar ERROR nem oferecer fallback de motor.
         */
        data class Cancelled(
            val text: String,
            val partial: Boolean,
            val millis: Long,
            /** R12/§3 — áudio processado até o cancelamento (nulo = não medido). */
            val audioMillis: Long? = null,
            val modelLabel: String? = null,
            val hardwareLabel: String? = "CPU",
        ) : Result()

        data class Refused(val reason: String) : Result()
        data class Failed(val reason: String) : Result()
    }

    /**
     * `true` quando a rota ORT deve atender o arquivo.
     *
     * Default: LIGADA. Só desliga se o usuário marcar "usar whisper.cpp".
     * A preferência antiga (`whisper_engine_ort`) é respeitada apenas quando
     * existir explicitamente, para não surpreender quem já a tinha desligado.
     */
    fun isEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences("whisper", Context.MODE_PRIVATE)
        val cpp = if (prefs.contains(PREF_CPP)) prefs.getBoolean(PREF_CPP, false) else null
        val legacyOrt = if (cpp == null && prefs.contains(PREF_KEY)) prefs.getBoolean(PREF_KEY, true) else null
        return WhisperSelectors.useOrt(cpp, legacyOrt)
    }

    /** Liga/desliga a rota. [enabled] `false` grava "usar whisper.cpp". */
    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences("whisper", Context.MODE_PRIVATE)
            .edit().putBoolean(PREF_CPP, !enabled).apply()
    }

    /**
     * Resolve o arquivo da Activity.
     *
     * Usa o `intent` (arquivo compartilhado/aberto). O seletor interno da tela não
     * é tocado nesta entrega: quando não há URI no intent, a rota recusa com
     * motivo em vez de adivinhar.
     */
    /**
     * `true` quando existe pacote ORT ativo no disco.
     *
     * Checagem LEVE (ponteiro `active` presente e não vazio): a validação
     * completa acontece quando a rota abre o pacote. Serve para a UI habilitar a
     * ferramenta sem exigir o modelo GGML do whisper.cpp.
     */
    fun temPacote(context: Context): Boolean {
        val ativo = File(File(context.filesDir, "ort-model"), "active")
        return ativo.isFile && ativo.length() > 0L
    }

    fun resolveUri(activity: Activity): Uri? =
        activity.intent?.data ?: activity.intent?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri

    /**
     * Executa a rota para [uri].
     *
     * A ADMISSÃO acontece ANTES de enfileirar: um clique concorrente é recusado na
     * porta e não entra na fila do executor (a fila serial não substitui a
     * reserva). [onProgresso] leva o estado do preparo para a tela.
     */
    internal fun transcribe(
        activity: Activity,
        uri: Uri,
        onProgresso: (String) -> Unit = { },
        onResult: (Result) -> Unit,
        threads: Int = WhisperOrtSession.DEFAULT_THREADS,
        modelo: String = WhisperOrtModelCatalog.MODEL,
        hardware: WhisperSelectors.Hardware? = null,
        vad: LocalVad.Selection = LocalVad.Selection(),
    ): WhisperOrtAdmission.Job? {
        val app = activity.applicationContext
        val porta = despachar(uri.toString()) { job ->
            jobAtual = job
            worker.execute {
                val resultado = runCatching { executar(app, uri, job, onProgresso, threads, modelo, hardware, vad) }
                    .getOrElse { Result.Failed("${it.javaClass.simpleName}: ${it.message}") }
                main.post { onResult(resultado) }
            }
        }
        if (porta is WhisperOrtAdmission.Admitted.Refused) {
            main.post { onResult(Result.Refused(porta.reason)) }
            return null
        }
        // Devolve a identidade do job para o observador conferir que o resultado
        // que chega é deste pedido (e não de um callback antigo).
        return (porta as? WhisperOrtAdmission.Admitted.Ok)?.job
    }

    /**
     * PORTA de admissão: decide e, SÓ quando admite, entrega o job a [enfileirar].
     *
     * É o ponto que garante "recusa antes de enfileirar": a recusa acontece na
     * thread chamadora e o trabalho nunca entra na fila do executor. Injetável para
     * que o contrato seja verificável sem UI.
     */
    internal fun despachar(
        identidade: String,
        admissao: WhisperOrtAdmission = admission,
        enfileirar: (WhisperOrtAdmission.Job) -> Unit,
    ): WhisperOrtAdmission.Admitted {
        return WhisperOrtPorta.despachar(identidade, admissao, enfileirar)
    }

    /**
     * R100 §2.4 — rótulo HONESTO do hardware efetivo. O recibo não pode dizer
     * "CPU" quando o encoder rodou no HTP, nem "HTP" quando houve fallback.
     */
    private fun rotuloHardware(rota: String): String =
        when (rota) {
            WhisperOrtHtpAdmissao.ROTA_HTP -> "HTP W8A16"
            WhisperOrtHtpAdmissao.ROTA_HTP_CPU -> "NPU→CPU"
            else -> "CPU"
        }

    /**
     * R100 §2.2 — o encoder W8A16 é do **turbo** (128 bins mel, 1.500 frames).
     * Pedido de outro modelo usa CPU mesmo com o pacote presente: alimentar o
     * wrapper com log-mel de 80 bins seria erro de forma, não ganho de NPU.
     */
    private fun rotaParaModelo(modelo: String, hardware: WhisperSelectors.Hardware?): String {
        if (modelo != WhisperOrtModelCatalog.MODEL || hardware == WhisperSelectors.Hardware.CPU) {
            return WhisperOrtHtpAdmissao.ROTA_CPU
        }
        val admitida = WhisperOrtHtpRota.rotaDoPedido()
        return if (WhisperOrtHtpAdmissao.isHtp(admitida)) hardware?.route ?: admitida else admitida
    }

    /** Corpo síncrono (roda na thread de trabalho). */
    private fun executar(
        app: Context,
        uri: Uri,
        job: WhisperOrtAdmission.Job,
        onProgresso: (String) -> Unit,
        threads: Int = WhisperOrtSession.DEFAULT_THREADS,
        modelo: String = WhisperOrtModelCatalog.MODEL,
        hardware: WhisperSelectors.Hardware? = null,
        vad: LocalVad.Selection = LocalVad.Selection(),
    ): Result {
        val inicio = SystemClock.elapsedRealtime()
        jobAtual = job
        // R100 §2.4 — idempotente: guarda o Context e agenda o pacote NPU em
        // segundo plano. Nunca bloqueia: sem pacote verificado, a rota é CPU.
        WhisperOrtHtpRota.iniciar(app)

        // §2.1 — RESERVA/FGS/ENCAMINHAMENTO ANTES de copiar, converter, ler e hashear.
        // O preparo consome CPU e disco: se outro compute (ORT ou CPP) já tem a
        // reserva, este pedido tem de morrer aqui, não depois de preparar tudo.
        // Adendo 18 §2 — token do alocador COMUM (antes era elapsedRealtime
        // puro: relógio não é único no mesmo milissegundo e o domínio devia
        // ser comum a todos os produtores). Mesmo valor de domínio do
        // relógio, mas única/monotônica por chamada.
        val geracaoFgs = WorkerReservation.Tokens.alocar(SystemClock.elapsedRealtime())
        val inicioFgs = WhisperJobService.startJobOutcome(
            app, geracaoFgs, WhisperJobLifecycle.KIND_FILE, "Preparando (ORT/CPU)…",
        )
        if (inicioFgs == WhisperJobService.StartOutcome.ADMISSAO_RECUSADA) {
            admission.abandon(job)
            jobAtual = null
            return Result.Refused("reserva de job recusada: outro compute em andamento")
        }
        val encaminhadorCancelamento: (Long) -> Boolean = { geracao ->
            if (geracao == geracaoFgs) admission.requestCancel(job) else false
        }
        WhisperJobService.registerCancelForwarder(encaminhadorCancelamento)
        // O preparo (cópia/conversão/leitura) é TRABALHO ATIVO para o serviço,
        // antes do compute ORT: registra o início real aqui (idempotente).
        WhisperJobService.markWorkerStarted(app, geracaoFgs)

        // Temporários cujo DONO é este job; o encerramento limpa.
        var copiaTemp: File? = null
        var convertidoTemp: File? = null
        var vadTemp: File? = null

        // Encerramento apenas após o retorno físico do preparo/compute.
        // Durante dreno a mesma thread mantém admissão, arquivos e forwarder.
        fun encerrar(resultado: Result): Result {
            val outcome = when (resultado) {
                is Result.Done -> resultado.outcome
                is Result.Cancelled -> "CANCELLED"
                is Result.Refused -> "BUSY"
                is Result.Failed -> "ERROR"
            }
            val texto = when (resultado) {
                is Result.Done -> resultado.text
                is Result.Cancelled -> resultado.text
                else -> ""
            }
            val parcial = when (resultado) {
                is Result.Done -> resultado.outcome == "PARTIAL"
                is Result.Cancelled -> resultado.partial
                else -> false
            }
            val terminal = admission.complete(
                job = job,
                outcome = outcome,
                text = texto,
                totalMillis = SystemClock.elapsedRealtime() - inicio,
                partial = parcial,
                // Recusa também viaja no terminal: "BUSY" sem motivo não permite
                // distinguir porta/serviço/pacote/sessão no diagnóstico.
                error = (resultado as? Result.Failed)?.reason
                    ?: (resultado as? Result.Refused)?.reason,
                // Seletor compartilhado (§3): ORT aplica direto (pedidas ==
                // efetivas); recusa antes do despacho registra 0.
                threads = threads,
                // Modelo efetivo do compute (adendo 17 §1): prova small→small.
                model = modelo,
                // R13/§4 — duração/hardware do resumo viajam no terminal para o
                // recibo; cancelado NÃO herda o PCM inteiro como áudio transcrito.
                audioMillis = (resultado as? Result.Done)
                    ?.takeIf { it.outcome == "SUCCESS" }?.audioMillis,
                hardware = when (resultado) {
                    is Result.Done -> resultado.hardwareLabel
                    is Result.Cancelled -> resultado.hardwareLabel
                    else -> null
                },
            )
            val recibo = if (terminal != null) gravarTerminal(app, terminal) else null
            resultado.recibo = recibo
            if (terminal == null) {
                // Job admitido que não conseguiu registrar terminal: é falha de
                // PERSISTÊNCIA (diferente de recusa antes da admissão), e precisa
                // ficar observável.
                Log.e("WhisperOrtRoute", "terminal NAO registrado job=${job.jobId} outcome=$outcome")
            }
            runCatching { copiaTemp?.delete() }
            runCatching { convertidoTemp?.delete() }
            runCatching { vadTemp?.delete() }
            WhisperJobService.unregisterCancelForwarder(encaminhadorCancelamento)
            WhisperJobService.finishJob(geracaoFgs, terminal?.outcome ?: outcome)
            jobAtual = null
            return resultado
        }

        // Modelo desconhecido recusa antes de qualquer preparo (sem fallback):
        // nunca cai em compute de outro pacote por engano.
        try {
            WhisperOrtModelCatalog.conjuntoPara(modelo)
        } catch (e: IllegalArgumentException) {
            return encerrar(Result.Refused("modelo sem suporte ORT validado: $modelo"))
        }

        try {

        // 1) pacote validado (marcador medido na instalação), NA IDENTIDADE DO
        // MODELO PEDIDO (adendo 17 §1): turbo e small têm pacotes, sessões e
        // chaves de cache distintos; nunca se misturam.
        val root = File(app.filesDir, "ort-model")
        val identidade = WhisperOrtModelCatalog.identity(modelo)
        val pacote = when (val aberto = WhisperOrtPackageInstaller.open(
            root, identidade, WhisperOrtModelCatalog.conjuntoPara(modelo),
        )) {
            is WhisperOrtPackageInstaller.Outcome.Refused ->
                return encerrar(Result.Refused("pacote: ${aberto.reason}: ${aberto.detail}"))
            is WhisperOrtPackageInstaller.Outcome.Ready -> aberto.validated
        }

        // 2) áudio: cópia CANCELÁVEL e com ORÇAMENTO antes das alocações (§2.3).
        // Falha de observador nunca decide o desfecho (adendo 16 §1).
        runCatching { onProgresso("preparando áudio…") }
        val pcm = try {
            val arquivo = copiarParaTemporario(app, uri, job, onProgresso)
            copiaTemp = arquivo
            runCatching { onProgresso("lendo áudio…") }
            var ready = arquivo
            val unfiltered = try {
                lerWav16kMono(arquivo)
            } catch (precisaConversao: WavPrecisaConversao) {
                val convertido = WhisperOrtMediaConverter.wavDeSaida(arquivo)
                val desfecho = WhisperOrtMediaConverter.converterParaWav16kMono(
                    origem = arquivo,
                    destino = convertido,
                    isCancelada = { admission.isCancelRequested(job) },
                    onProgresso = onProgresso,
                    motor = motorConversaoParaTeste ?: WhisperOrtMediaConverter.MotorFfmpeg,
                    timeoutMs = timeoutConversaoMsParaTeste ?: 60_000L,
                )
                when (desfecho) {
                    is WhisperOrtMediaConverter.Resultado.Cancelada -> throw CancelamentoNoPreparo()
                    is WhisperOrtMediaConverter.Resultado.SessaoAtiva -> {
                        // Mantém admissão, job, forwarder, entrada e FGS até o
                        // callback físico. Timeout/cancelamento não liberam posse.
                        // O dreno encaminha cancelamentos TARDIOS à sessão dona
                        // (nunca global); pedido não equivale a recurso encerrado.
                        convertidoTemp = desfecho.destino
                        // Falha de observador NÃO é prova de término do conversor:
                        // nunca pode desviar para o encerramento prematuro.
                        runCatching { onProgresso("aguardando término da conversão…") }
                        val fimFisico = WhisperOrtMediaConverter.drenar(
                            desfecho,
                            isCancelada = { admission.isCancelRequested(job) },
                            onProgresso = onProgresso,
                        )
                        if (fimFisico.cancelada || admission.isCancelRequested(job)) {
                            throw CancelamentoNoPreparo()
                        }
                        if (!fimFisico.ok) {
                            return encerrar(Result.Failed("conversao: ${desfecho.motivo}"))
                        }
                        // Sucesso tardio: o arquivo estabilizou (término observado),
                        // então o pipeline prossegue e o terminal sai normal.
                    }
                    is WhisperOrtMediaConverter.Resultado.Falhou ->
                        throw IllegalStateException(desfecho.motivo, precisaConversao)
                    is WhisperOrtMediaConverter.Resultado.Converteu -> {
                        convertidoTemp = desfecho.arquivo
                        // Teto do arquivo CONVERTIDO: um MP3 pequeno pode virar um
                        // WAV enorme; nunca transcrever além do limite para caber.
                        if (desfecho.arquivo.length() > MAX_BYTES_AUDIO) {
                            runCatching { desfecho.arquivo.delete() }
                            convertidoTemp = null
                            throw WavCorrompido(
                                "convertido com ${desfecho.arquivo.length() / (1024 * 1024)} MB " +
                                    "acima do limite desta rota",
                            )
                        }
                    }
                }
                ready = convertido
                lerWav16kMono(ready)
            }
            if (vad.mode == LocalVad.Mode.NONE) {
                unfiltered
            } else {
                if (admission.isCancelRequested(job)) throw CancelamentoNoPreparo()
                runCatching { onProgresso("filtrando voz com VAD…") }
                val filtered = File.createTempFile("whisper_vad_", ".wav", app.cacheDir)
                vadTemp = filtered
                LocalVad.filter(app, ready, filtered, vad) {
                    if (admission.isCancelRequested(job)) throw CancelamentoNoPreparo()
                }
                lerWav16kMono(filtered)
            }
        } catch (cancelado: CancelamentoNoPreparo) {
            return encerrar(Result.Cancelled("", partial = false, millis = SystemClock.elapsedRealtime() - inicio))
        } catch (failure: Throwable) {
            return encerrar(Result.Refused("midia: ${failure.message}"))
        }

        if (admission.isCancelRequested(job)) {
            return encerrar(Result.Cancelled("", false, SystemClock.elapsedRealtime() - inicio))
        }
        // Defesa em profundidade: o teto já foi aplicado no tamanho do arquivo; aqui
        // confere o PCM resultante (nunca truncar para caber).
        if (pcm.size > MAX_AMOSTRAS) {
            val minutos = pcm.size.toDouble() / WhisperOrtPcm.SAMPLE_RATE / 60.0
            return encerrar(
                Result.Refused(
                    "midia: audio de %.1f min excede o limite de %d min desta rota"
                        .format(minutos, MAX_AMOSTRAS / WhisperOrtPcm.SAMPLE_RATE / 60),
                ),
            )
        }
        val segundos = pcm.size.toDouble() / WhisperOrtPcm.SAMPLE_RATE
        if (segundos <= 0.0) {
            return encerrar(Result.Refused("midia: audio vazio"))
        }

        // §2.2 — vínculo ÚNICO do hash medido, na transição para entrada pronta.
        // Se o vínculo for recusado (job não é mais o dono, hash inválido ou já
        // vinculado), a rota NÃO segue: seguir publicaria identidade errada.
        // Adendo 17 §3 — quantidade e hash do PCM que REALMENTE entrou no
        // modelo (só log: correlaciona arquivo→PCM→janelas sem tocar o contrato).
        val hashMedido = WhisperOrtPcm.sha256(WhisperOrtPcm.toInt16Le(pcm))
        android.util.Log.i(TAG, "ort-pcm amostras=${pcm.size} sha256=$hashMedido")
        if (!admission.vincularEntrada(job, hashMedido)) {
            return encerrar(Result.Failed("entrada ja vinculada ou job nao e mais o dono"))
        }

        if (WhisperOrtPcm.isDigitalSilence(pcm)) {
            return encerrar(Result.Done("", "NO_SPEECH", SystemClock.elapsedRealtime() - inicio, false))
        }

        // 3) NATIVAS pelo fluxo comum (v14): ORT por caminho absoluto em ordem de
        // dependência. Sem isso, o `open` da sessão cai em `UnsatisfiedLinkError`
        // (DT_NEEDED não resolve no namespace do APK).
        // Com backend injetado (prova controlada) não há ORT de verdade: pula.
        // (Sem pré-carga manual do FFmpeg aqui: o próprio FFmpegKit carrega o que
        // precisa, e a ferramenta diagnóstica do manager proíbe esse preload no
        // fluxo normal. Se a conversão falhar, a rota recusa com o motivo.)
        if (cacheParaTeste == null) {
            val nativas = NativeDependencyManager.loadOrtRuntime(app)
            if (nativas.isFailure) {
                return encerrar(Result.Refused("nativo: ${nativas.exceptionOrNull()?.message}"))
            }
        }

        // 4) COMPUTE — todo desfecho aqui termina no MESMO `encerrar`.
        val resultado: Result = try {
            val config = WhisperOrtGenerationConfig.parse(pacote.generationConfig.readText(Charsets.UTF_8))
            val tokenizer = run {
                val vocab = JSONObject(pacote.vocab.readText(Charsets.UTF_8))
                val mapa = HashMap<String, Int>(vocab.length())
                for (chave in vocab.keys()) mapa[chave] = vocab.getInt(chave)
                // Adendo 17 §1 — especiais por modelo (51865 small, 51866
                // turbo): o vocab.json só traz o texto; com o teto do turbo
                // no small, os logits (51865) nunca batiam no tokenizer.
                WhisperOrtTokenizer.fromVocab(mapa, WhisperOrtModelCatalog.especiaisPara(modelo))
            }
            // INÍCIO REAL do trabalho nativo (§2.1: a FGS já está tomada desde antes
            // do preparo; aqui só se registra o começo do compute).
            WhisperJobService.markWorkerStarted(app, geracaoFgs)
            val producer = WhisperOrtTranscriptionProducer(
                cacheEfetivo,
                tokenizer,
                runtimeVersion = ORT_RUNTIME,
                aoFalharHtp = { motivo -> WhisperOrtHtpRota.registrarFalha(motivo) },
                aoSucessoHtp = { WhisperOrtHtpRota.registrarSucesso() },
            )
            val rotaPedida = rotaParaModelo(modelo, hardware)
            android.util.Log.i(
                TAG,
                "ort-rota pedida=$rotaPedida motivo=${WhisperOrtHtpRota.motivoRota()} " +
                    "pacote=${WhisperOrtHtpRota.versaoPacote()}",
            )
            val pedido = WhisperOrtTranscriptionProducer.Request(
                jobId = job.jobId,
                attempt = job.attempt,
                pcm = pcm,
                // ADENDO 23 §1 — o ID de TAREFA vem da FAMÍLIA do pacote
                // (task_to_id medido nos metadados congelados); só Turbo
                // mantém o default 50360. Prompt divergente recusa antes do compute.
                policy = WhisperOrtGenerationPolicy(
                    prompt = intArrayOf(
                        WhisperOrtPrompt.START_OF_TRANSCRIPT,
                        WhisperOrtPrompt.LANGUAGE_PT,
                        WhisperOrtModelCatalog.taskIdsPara(modelo).transcribe,
                    ),
                    config = config,
                    timestampMode = true,
                    // ADENDO 23 §1 — IDs por família (tiny/base 50359/50363).
                    transcribeTokenId = WhisperOrtModelCatalog.taskIdsPara(modelo).transcribe,
                    translateTokenId = WhisperOrtModelCatalog.taskIdsPara(modelo).translate,
                    noTimestampsTokenId = WhisperOrtModelCatalog.taskIdsPara(modelo).noTimestamps,
                ),
                threads = threads,
                isCancelled = { admission.isCancelRequested(job) },
                rota = rotaPedida,
            )
            when (val desfecho = producer.transcribe(pacote, pedido)) {
                is WhisperOrtTranscriptionProducer.Outcome.Done -> {
                    val t = desfecho.transcript
                    val fim = SystemClock.elapsedRealtime() - inicio
                    val rotulo = rotuloHardware(desfecho.rotaEfetiva)
                    android.util.Log.i(
                        TAG,
                        "ort-rota efetiva=${desfecho.rotaEfetiva} total_ms=$fim " +
                            "open_ms=${desfecho.sessionOpenedMillis}",
                    )
                    if (t.outcome == WhisperOrtOutcome.CANCELLED) {
                        Result.Cancelled(t.text, partial = true, millis = fim,
                            audioMillis = t.audioMillis, modelLabel = pacote.identity.model,
                            hardwareLabel = rotulo)
                    } else if (t.outcome == WhisperOrtOutcome.ERROR) {
                        // Adendo 17 §1 — erro do transcriber viajava como Done
                        // (texto vazio + outcome ERROR) e o motivo se perdia
                        // (terminal com error=null). Falha carrega o motivo.
                        Result.Failed(t.error ?: "transcriber devolveu ERROR sem detalhe")
                    } else {
                        Result.Done(
                            t.text, t.outcome.name, fim, desfecho.cacheHit,
                            openMillis = desfecho.sessionOpenedMillis,
                            audioMillis = t.audioMillis,
                            modelLabel = pacote.identity.model,
                            hardwareLabel = rotulo,
                        )
                    }
                }
                is WhisperOrtTranscriptionProducer.Outcome.Busy ->
                    Result.Refused("sessao ocupada: " + desfecho.reason)
                is WhisperOrtTranscriptionProducer.Outcome.Cancelled ->
                    Result.Cancelled("", partial = false, millis = SystemClock.elapsedRealtime() - inicio)
                is WhisperOrtTranscriptionProducer.Outcome.Failed ->
                    Result.Failed(desfecho.error)
            }
        } catch (failure: Throwable) {
            Result.Failed(failure.javaClass.simpleName + ": " + failure.message)
        }
        return encerrar(resultado)
        } catch (falhaInesperada: Throwable) {
            // Qualquer exceção fora dos caminhos previstos ainda termina com
            // terminal e posse liberada — nunca com job preso e sem diagnóstico.
            return runCatching {
                encerrar(
                    Result.Failed(
                        "interno: ${falhaInesperada.javaClass.simpleName}: ${falhaInesperada.message}",
                    ),
                )
            }.getOrElse {
                admission.abandon(job)
                jobAtual = null
                Result.Failed(
                    "interno: ${falhaInesperada.javaClass.simpleName}: ${falhaInesperada.message}",
                )
            }
        }
    }

    /**
     * Acrescenta o terminal ao registro do processo (sobrevive à Activity).
     *
     * O contrato do M4 pede resultado RECUPERÁVEL: por isso o texto completo e o
     * desfecho vão no registro, não apenas hash/tamanho. Falha de append deixa de
     * ser engolida — fica no log com o job, e o chamador sabe que não houve
     * recibo. Nunca se fabrica ACK em cima de uma escrita que falhou.
     */
    private fun gravarTerminal(
        app: Context,
        terminal: WhisperOrtAdmission.Terminal,
    ): WhisperOrtTerminalSink.Recibo? {
        // O sink grava um arquivo POR JOB (atômico) e devolve recibo; o JSONL fica
        // como índice. Sem recibo, a entrega NÃO aconteceu e isso fica observável.
        val recibo = WhisperOrtTerminalSink.publicar(app, terminal)
        ultimoRecibo = recibo
        if (recibo == null) {
            Log.e(
                "WhisperOrtRoute",
                "terminal NAO entregue job=${terminal.jobId} outcome=${terminal.outcome}",
            )
        }
        return recibo
    }

    /** Copia o conteúdo da URI para arquivo privado (SAF), com nome único. */
    /**
     * Copia a URI para arquivo privado do job, com ORÇAMENTO e CANCELAMENTO.
     *
     * §2.3: o teto é aplicado DURANTE a cópia, não depois — copiar 2 GB e só então
     * recusar já teria alocado o disco e o tempo. Cancela entre blocos e apaga o
     * parcial ao abortar; nada de arquivo meio escrito virando entrada.
     */
    private fun copiarParaTemporario(
        app: Context,
        uri: Uri,
        job: WhisperOrtAdmission.Job,
        onProgresso: (String) -> Unit,
    ): File {
        // Temporário PRIVADO por job (nome com jobId): dois jobs não compartilham
        // arquivo parcial e a limpeza é do dono.
        val destino = File(app.cacheDir, "ort-route-${job.jobId}-${job.attempt}.wav")
        destino.delete()
        val entrada = app.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("nao foi possivel abrir a URI")
        var total = 0L
        var proximoAviso = 4L * 1024 * 1024
        try {
            entrada.use { origem ->
                destino.outputStream().use { saida ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (admission.isCancelRequested(job)) throw CancelamentoNoPreparo()
                        val lidos = origem.read(buffer)
                        if (lidos <= 0) break
                        total += lidos
                        if (total > MAX_BYTES_AUDIO) {
                            throw IllegalStateException(
                                "arquivo acima do limite desta rota (${MAX_BYTES_AUDIO / (1024 * 1024)} MB)",
                            )
                        }
                        saida.write(buffer, 0, lidos)
                        if (total >= proximoAviso) {
                            runCatching { onProgresso("copiando ${total / (1024 * 1024)} MB…") }
                            proximoAviso += 4L * 1024 * 1024
                        }
                    }
                }
            }
        } catch (falha: Throwable) {
            destino.delete()
            throw falha
        }
        return destino
    }

    /** Formato diferente do exigido: vale tentar converter (MP3, estéreo, 44,1 kHz). */
    /** Cancelamento pedido durante o preparo (antes de qualquer compute). */
    private class CancelamentoNoPreparo : RuntimeException("cancelado durante o preparo")

    private class WavPrecisaConversao(motivo: String) : IllegalArgumentException(motivo)

    /** Arquivo inválido: converter só esconderia a causa. Nunca vira texto. */
    private class WavCorrompido(motivo: String) : IllegalArgumentException(motivo)

    /**
     * Lê WAV PCM16 16 kHz mono.
     *
     * Separa o que é FORMATO (converte) do que é CORRUPÇÃO (erro terminal). Antes,
     * qualquer exceção caía na conversão: um arquivo truncado era "consertado"
     * pelo FFmpeg e transcrito pela metade como se fosse o inteiro.
     */
    private fun lerWav16kMono(arquivo: File): FloatArray {
        // §2.3: teto ANTES de qualquer alocação grande (readBytes + ByteArray + Float).
        if (arquivo.length() > MAX_BYTES_AUDIO) {
            throw WavCorrompido(
                "arquivo de ${arquivo.length() / (1024 * 1024)} MB acima do limite desta rota",
            )
        }
        // Adendo 17 §3 — caminhamento de chunks no seam puro (testável em JVM
        // com RIFF real, inclusive LIST); aqui só o mapeamento para o erro
        // tipado da rota.
        return when (val lido = WhisperOrtWav.ler(arquivo.readBytes())) {
            is WhisperOrtWav.Leitura.Pcm -> lido.amostras
            is WhisperOrtWav.Leitura.PrecisaConversao -> throw WavPrecisaConversao(lido.motivo)
            is WhisperOrtWav.Leitura.Corrompido -> throw WavCorrompido(lido.motivo)
        }
    }

    /** Aviso curto na main (a Activity só observa). */
    fun avisar(activity: Activity, mensagem: String) {
        Log.i(TAG, mensagem)
        main.post { Toast.makeText(activity, mensagem, Toast.LENGTH_LONG).show() }
    }

    /** Runtime declarado (mesmo AAR do projeto). */
    private const val ORT_RUNTIME = "onnxruntime-android-qnn:1.29.0"
}
