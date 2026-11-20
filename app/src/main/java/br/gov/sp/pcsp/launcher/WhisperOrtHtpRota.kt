package br.gov.sp.pcsp.launcher

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.os.Build
import androidx.annotation.VisibleForTesting
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * R100 §2.4 — ROTA HTP do PRODUTO: encoder EPContext W8A16 no QNN HTP com os
 * decoders INT8 do produto em CPU.
 *
 * É a rota provada na R97 sem sandbox, sem reflexão e sem job de laboratório:
 * a Activity, a rota de arquivo e o cache não sabem que ela existe — a fábrica
 * do cache pergunta, recebe `null`/recusa e segue em CPU.
 *
 * Contratos:
 * - `iniciar` é idempotente e chamado no corpo de `WhisperOrtRoute.executar`;
 * - a admissão é a [WhisperOrtHtpAdmissao] (SoC, pacote verificado, histórico);
 * - carregar ORT/QNN e abrir a sessão acontece UMA vez; qualquer falha desliga o
 *   HTP **no processo** e é contada para o desligamento entre processos;
 * - o log leva só rota, SoC, versão do pacote, tempos e motivo — nunca texto,
 *   áudio ou caminho de arquivo do usuário.
 */
object WhisperOrtHtpRota {

    private const val TAG = "WhisperOrtHtp"
    private const val PREFS = "whisper_ort_htp"
    private const val CHAVE_FALHAS = "falhas"
    private const val CHAVE_VERSAO = "versao"
    private const val ENTRADA = "input_features"
    private const val HTP_MODE = "burst"

    /** Recusa tipada da fábrica: o produtor troca para CPU e registra o motivo. */
    class Recusa(val motivo: String) : IllegalStateException("htp-recusa: $motivo")

    @Volatile private var app: Context? = null
    @Volatile private var iniciado = false
    @Volatile private var desligadoNoProcesso = false
    @Volatile private var motivoIndisponivel: String? = "nao_avaliado"
    @Volatile private var manifesto: WhisperOrtNpuPacote.Manifesto? = null
    @Volatile private var dirPacote: File? = null
    @Volatile private var ortCarregado = false
    @Volatile private var qnnCarregado = false
    @Volatile private var ultimoLoadMs = 0L

    /** `true` quando o pacote já foi conferido por SHA neste processo. */
    @Volatile private var pacoteVerificado = false

    /** Duração da conferência rápida feita no início deste processo. */
    @Volatile private var conferenciaMs = 0L

    /** Detalhe da conferência rápida (motivo da recusa/reverificação). */
    @Volatile private var detalheConferencia: String? = null

    /** Contou falha neste processo (o limite é por processo, não por job). */
    @Volatile private var contouFalhaNesteProcesso = false

    /**
     * Idempotente: chamado a cada pedido, guarda o contexto e faz a
     * **conferência rápida** do pacote da NPU. Não baixa nada (R101 §T03): o
     * download é ação explícita do usuário na tela.
     */
    fun iniciar(context: Context) {
        val anterior = app
        app = context.applicationContext
        if (!iniciado) {
            synchronized(this) {
                if (!iniciado) {
                    iniciado = true
                    val ctx = app ?: return
                    conferirPacote(ctx)
                }
            }
        }
        if (anterior == null) {
            android.util.Log.i(TAG, "rota HTP inicializada: " + descricaoSoC())
        }
    }

    /**
     * R105/T01 — CONFERÊNCIA RÁPIDA no início do processo, no padrão do QAIRT e
     * das dependências nativas: marcador da versão + existência + tamanho + data
     * de cada arquivo. Sem hash de 646 MiB, ela cabe antes do primeiro pedido e é
     * o que permite ADMITIR a HTP já na primeira transcrição do processo.
     *
     * Os três desfechos ([WhisperOrtNpuPacote.Conferencia]):
     * - conformidade: admitido na hora; marcador do formato anterior também é
     *   admitido e a verificação completa é agendada só para regravar a data;
     * - reverificação: a rota fica em CPU até a verificação completa (SHA) passar;
     * - recusa (tamanho/data divergentes): a rota fica em CPU — o pacote em disco
     *   não é o pacote conferido e não há o que reverificar.
     */
    private fun conferirPacote(ctx: Context) {
        val inicio = System.nanoTime()
        val resultado = runCatching { WhisperOrtNpuPacote.conferenciaRapida(ctx) }.getOrNull()
        conferenciaMs = (System.nanoTime() - inicio) / 1_000_000
        when (resultado) {
            is WhisperOrtNpuPacote.Conferencia.Ok -> {
                detalheConferencia = if (resultado.legado) "marcador_legado" else "ok"
                pacoteVerificado(resultado.manifesto)
                android.util.Log.i(
                    TAG,
                    "conferencia rapida ok legado=${resultado.legado} ms=$conferenciaMs " +
                        "versao=${resultado.manifesto.versao} arquivos=${resultado.manifesto.itens.size}",
                )
                if (resultado.legado) {
                    // Marcador do formato anterior: já foi admitido este processo
                    // e a verificação completa só grava a impressão de disco.
                    runCatching { WhisperOrtNpuPacote.verificarEmSegundoPlano(ctx) }
                }
            }
            is WhisperOrtNpuPacote.Conferencia.Reverificar -> {
                detalheConferencia = "reverificar:" + resultado.motivo
                pacoteIndisponivel("verificacao:" + resultado.motivo)
                runCatching { WhisperOrtNpuPacote.verificarEmSegundoPlano(ctx) }
                    .onFailure { android.util.Log.w(TAG, "verificacao do pacote falhou: ${it.message}") }
            }
            is WhisperOrtNpuPacote.Conferencia.Recusar -> {
                detalheConferencia = "recusa:" + resultado.motivo
                pacoteIndisponivel("conferencia:" + resultado.motivo)
                // R106/T02 — a recusa continua mandando ESTE pedido para a CPU,
                // mas a verificação completa por SHA é agendada sozinha (uma vez
                // por processo). Se o SHA conferir, o marcador é regravado com a
                // impressão de disco atual e a HTP volta no pedido seguinte, sem
                // ação do usuário e sem download.
                //
                // Exceção deliberada: pacote JÁ marcado como CORROMPIDO não é
                // reverificado sozinho — a verificação completa já rodou e falhou,
                // nada é apagado, e a saída é a ação explícita do usuário na
                // entrada fixa ("Verificar novamente" ou "Reinstalar"). Sem isso,
                // cada abertura da tela varreria 646 MiB e readmitiria sozinha.
                if (!resultado.motivo.startsWith("corrompido:")) {
                    runCatching { WhisperOrtNpuPacote.verificarEmSegundoPlano(ctx) }
                        .onFailure { android.util.Log.w(TAG, "revalidacao do pacote falhou: ${it.message}") }
                }
            }
            null -> {
                detalheConferencia = "erro:conferencia"
                pacoteIndisponivel("verificacao:conferencia_falhou")
                runCatching { WhisperOrtNpuPacote.verificarEmSegundoPlano(ctx) }
            }
        }
    }

    /** Tempo da conferência rápida deste processo (vai para o recibo da C14). */
    fun conferenciaMs(): Long = conferenciaMs

    /** Chamado pelo [WhisperOrtNpuPacote] quando a verificação completa passou. */
    fun pacoteVerificado(manifesto: WhisperOrtNpuPacote.Manifesto) {
        this.manifesto = manifesto
        this.dirPacote = app?.let { WhisperOrtNpuPacote.dirDaVersao(it) }
        pacoteVerificado = true
        motivoIndisponivel = null
        android.util.Log.i(
            TAG,
            "pacote verificado versao=${manifesto.versao} soc=${manifesto.soc} " +
                "arquivos=${manifesto.itens.size}",
        )
    }

    /** Chamado quando o pacote não está disponível; a rota segue em CPU. */
    fun pacoteIndisponivel(motivo: String) {
        pacoteVerificado = false
        motivoIndisponivel = motivo
        android.util.Log.i(TAG, "pacote indisponivel: $motivo")
    }

    /**
     * R106/T02 — a verificação completa passou para um pacote que a conferência
     * rápida havia RECUSADO (data/tamanho/marcador). O marcador já foi regravado
     * com a impressão de disco atual; aqui o detalhe da conferência passa a
     * refletir a revalidação, para o recibo não carregar a recusa antiga, e a HTP
     * é admitida já no pedido seguinte do processo.
     */
    fun pacoteRevalidado(manifesto: WhisperOrtNpuPacote.Manifesto, detalhe: String) {
        detalheConferencia = detalhe
        pacoteVerificado(manifesto)
        android.util.Log.i(TAG, "pacote revalidado ($detalhe) versao=${manifesto.versao}")
    }

    fun versaoPacote(): String = manifesto?.versao ?: WhisperOrtNpuPacote.VERSAO

    fun desligadoNoProcesso(): Boolean = desligadoNoProcesso

    /**
     * Rota efetiva do pedido AGORA. Barata: não hasheia nada (a verificação pesada
     * roda uma vez, em segundo plano) e não abre sessão.
     */
    fun rotaDoPedido(): String {
        val ctx = app ?: return WhisperOrtHtpAdmissao.ROTA_CPU
        val presente = runCatching {
            WhisperOrtNpuPacote.estado(ctx) is WhisperOrtNpuPacote.Estado.Pronto
        }.getOrDefault(false)
        val decisao = WhisperOrtHtpAdmissao.decidir(
            socModelo = socModelo(),
            fabricante = socFabricante(),
            qairtPresente = QairtDependencyManager.isInstalled(ctx),
            pacotePresente = presente,
            pacoteVerificado = pacoteVerificado,
            desligadoNoProcesso = desligadoNoProcesso,
            memoriaFalhas = memoriaDeFalhas(ctx),
            versaoPacote = versaoPacote(),
        )
        motivoIndisponivel = decisao.detalhe + " motivo=" + decisao.motivo.name +
            (detalheConferencia?.let { " conferencia=$it" } ?: "")
        return decisao.rota
    }

    /** Motivo estável da última avaliação (vai para log, não para a tela). */
    fun motivoRota(): String = motivoIndisponivel ?: "admitido"

    /**
     * Fábrica da rota HTP. Devolve o backend pronto (encoder HTP + decoders CPU
     * abertos) ou lança [Recusa] — nunca devolve um backend pela metade.
     */
    fun abrir(encoder: File, decoder: File, past: File, threads: Int): WhisperOrtBackend {
        val ctx = app ?: throw Recusa("sem contexto")
        val decisao = try {
            val presente = WhisperOrtNpuPacote.estado(ctx) is WhisperOrtNpuPacote.Estado.Pronto
            WhisperOrtHtpAdmissao.decidir(
                socModelo = socModelo(),
                fabricante = socFabricante(),
                qairtPresente = QairtDependencyManager.isInstalled(ctx),
                pacotePresente = presente,
                pacoteVerificado = pacoteVerificado,
                desligadoNoProcesso = desligadoNoProcesso,
                memoriaFalhas = memoriaDeFalhas(ctx),
                versaoPacote = versaoPacote(),
            )
        } catch (falha: Throwable) {
            throw Recusa("admissao: ${falha.javaClass.simpleName}: ${falha.message}")
        }
        if (!decisao.usarHtp) throw Recusa(decisao.motivo.name + ":" + decisao.detalhe)

        val pacote = manifesto ?: runCatching {
            val estado = WhisperOrtNpuPacote.estado(ctx)
            (estado as? WhisperOrtNpuPacote.Estado.Pronto)?.manifesto
        }.getOrNull() ?: throw Recusa("pacote sem manifesto")
        val archEsperada = WhisperOrtHtpAdmissao.socDe(socModelo())?.htpArch
        if (archEsperada == null || pacote.htpArch != archEsperada) {
            throw Recusa("arch do pacote ${pacote.htpArch} != SoC ${archEsperada ?: "?"}")
        }

        val dir = dirPacote ?: WhisperOrtNpuPacote.dirAtivo(WhisperOrtNpuPacote.raiz(ctx))
        val inicio = System.nanoTime()
        val qairt = QairtDependencyManager.packageDir(ctx)

        // R115 §T02 — pacote v3 verificado: rota HTP de PONTA A PONTA (Q′).
        if (pacote.pontaAPonta) {
            val backend = try {
                carregarOrt(ctx)
                carregarQnnQairt(ctx, pacote.htpArch)
                abrirPontaAPonta(dir, qairt, pacote.htpArch, encoder, decoder, past, threads)
            } catch (falha: Throwable) {
                registrarFalha("abertura v3: ${falha.javaClass.simpleName}: ${falha.message}")
                throw Recusa("abertura v3: ${falha.message ?: falha.javaClass.simpleName}")
            }
            ultimoLoadMs = (System.nanoTime() - inicio) / 1_000_000
            android.util.Log.i(
                TAG,
                "rota=htp-ponta-a-ponta soc=${WhisperOrtHtpAdmissao.normalizarSoc(socModelo())} " +
                    "pacote=${pacote.versao} arch=${pacote.htpArch} load_ms=$ultimoLoadMs",
            )
            return backend
        }

        // Pacote v2 (só o encoder): rota antiga — encoder HTP + decoders CPU.
        val sessaoHtp = try {
            carregarOrt(ctx)
            // R101 §T02 — um único QAIRT no processo: as libs vêm daqui, e não
            // do pacote da NPU (que agora só tem contexto e wrapper).
            carregarQnnQairt(ctx, pacote.htpArch)
            abrirSessaoHtp(
                File(dir, WhisperOrtNpuPacote.WRAPPER),
                qairt,
                pacote.htpArch,
            )
        } catch (falha: Throwable) {
            registrarFalha("abertura: ${falha.javaClass.simpleName}: ${falha.message}")
            throw Recusa("abertura: ${falha.message ?: falha.javaClass.simpleName}")
        }
        val decoders = WhisperOrtSession(encoder, decoder, past, threads)
        try {
            decoders.openDecodersOnly(
                melBins = WhisperOrtFrontend.N_MELS,
                frames = 1500,
                dim = 1280,
            )
        } catch (falha: Throwable) {
            runCatching { decoders.close() }
            runCatching { sessaoHtp.close() }
            registrarFalha("decoders: ${falha.javaClass.simpleName}: ${falha.message}")
            throw Recusa("decoders: ${falha.message}")
        }
        ultimoLoadMs = (System.nanoTime() - inicio) / 1_000_000
        android.util.Log.i(
            TAG,
            "rota=htp soc=${WhisperOrtHtpAdmissao.normalizarSoc(socModelo())} " +
                "pacote=${pacote.versao} arch=${pacote.htpArch} load_ms=$ultimoLoadMs",
        )
        return WhisperOrtHtpBackend(sessaoHtp, decoders, pacote.dtype)
    }

    /**
     * R115 — limite ARTIFICIAL de passos HTP por janela, só para o teste da volta
     * forçada para a CPU (T04.5). `null` (produção) usa o contrato: 199 posições.
     * Não é alcançável pela UI nem por preferência; quem escreve é o
     * instrumentation, no mesmo processo.
     */
    @VisibleForTesting
    internal var limiteHtpParaTeste: Int? = null

    /**
     * R115 §T02.1 — abre as três sessões EPContext do pacote v3 e monta o
     * backend de ponta a ponta. Os decoders CPU do produto entram como provider
     * PREGUIÇOSO: só carregam na primeira volta para a CPU.
     */
    private fun abrirPontaAPonta(
        dir: File,
        dirQairt: File,
        htpArch: String,
        encoder: File,
        decoder: File,
        past: File,
        threads: Int,
    ): WhisperOrtBackend {
        val env = OrtEnvironment.getEnvironment(OrtLoggingLevel.ORT_LOGGING_LEVEL_WARNING, TAG)
        val sessaoEncoder = abrirSessaoHtp(File(dir, WhisperOrtNpuPacote.WRAPPER), dirQairt, htpArch)
        val sessaoProjetor = try {
            WhisperOrtHtpSessoesOrt.abrir(
                env, File(dir, WhisperOrtNpuPacote.PROJETOR_WRAPPER), dirQairt, htpArch,
            )
        } catch (falha: Throwable) {
            runCatching { sessaoEncoder.close() }
            throw falha
        }
        val sessaoDecoder = try {
            WhisperOrtHtpSessoesOrt.abrir(
                env, File(dir, WhisperOrtNpuPacote.DECODER_WRAPPER), dirQairt, htpArch,
            )
        } catch (falha: Throwable) {
            runCatching { sessaoProjetor.close() }
            runCatching { sessaoEncoder.close() }
            throw falha
        }
        return WhisperOrtHtpLoopBackend(
            encoder = sessaoEncoder,
            projetor = WhisperOrtHtpSessoesOrt.projetor(env, sessaoProjetor),
            decoder = WhisperOrtHtpSessoesOrt.decoder(env, sessaoDecoder),
            limiteHtp = limiteHtpParaTeste ?: WhisperOrtHtpContrato.POSICOES_CACHE_SELF,
            decodersCpu = {
                WhisperOrtSession(encoder, decoder, past, threads).also { cpu ->
                    cpu.openDecodersOnly(
                        melBins = WhisperOrtFrontend.N_MELS,
                        frames = 1500,
                        dim = 1280,
                    )
                }
            },
        )
    }

    /**
     * Falha de rota: desliga no processo e conta UMA vez por processo. Três
     * processos seguidos sem sucesso desligam o HTP até a próxima versão.
     *
     * R105/T01 — a falha (inclusive a de ABERTURA da sessão HTP) marca o pacote
     * para verificação COMPLETA: o próximo processo confere o SHA de cada arquivo
     * antes de autorizar a rota, e o fallback para CPU continua valendo aqui.
     */
    fun registrarFalha(motivo: String) {
        desligadoNoProcesso = true
        motivoIndisponivel = "falha:" + motivo
        val ctx = app ?: return
        runCatching { WhisperOrtNpuPacote.marcarParaReverificacao(WhisperOrtNpuPacote.raiz(ctx)) }
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val falhas = prefs.getInt(CHAVE_FALHAS, 0)
        val versao = prefs.getString(CHAVE_VERSAO, "").orEmpty()
        var novas = falhas
        if (!contouFalhaNesteProcesso) {
            contouFalhaNesteProcesso = true
            novas = if (versao == versaoPacote()) falhas + 1 else 1
            prefs.edit().putInt(CHAVE_FALHAS, novas).putString(CHAVE_VERSAO, versaoPacote()).apply()
        }
        android.util.Log.w(
            TAG,
            "rota=htp DESLIGADA motivo=$motivo falhas=$novas versao=${versaoPacote()} " +
                "limite=${WhisperOrtHtpAdmissao.FALHAS_PARA_DESLIGAR}",
        )
    }

    /** Sucesso completo na rota HTP zera a sequência de falhas. */
    fun registrarSucesso() {
        contouFalhaNesteProcesso = false
        val ctx = app ?: return
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getInt(CHAVE_FALHAS, 0) != 0 || prefs.getString(CHAVE_VERSAO, "").orEmpty().isNotEmpty()) {
            prefs.edit().putInt(CHAVE_FALHAS, 0).putString(CHAVE_VERSAO, versaoPacote()).apply()
        }
    }

    private fun memoriaDeFalhas(ctx: Context): WhisperOrtHtpAdmissao.MemoriaFalhas {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return WhisperOrtHtpAdmissao.MemoriaFalhas(
            falhas = prefs.getInt(CHAVE_FALHAS, 0),
            versao = prefs.getString(CHAVE_VERSAO, "").orEmpty(),
        )
    }

    private fun socModelo(): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else null

    private fun socFabricante(): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MANUFACTURER else null

    private fun descricaoSoC(): String =
        "soc=${WhisperOrtHtpAdmissao.normalizarSoc(socModelo())} fabricante=${socFabricante().orEmpty()}"

    private fun carregarOrt(ctx: Context) {
        if (ortCarregado) return
        synchronized(this) {
            if (ortCarregado) return
            NativeDependencyManager.loadOrtRuntime(ctx, onLog = { }).getOrThrow()
            ortCarregado = true
        }
    }

    /**
     * R101 §T02 — carrega o QNN pelo pacote QAIRT ÚNICO, com a mesma porta do
     * Granite.
     *
     * [QairtDependencyManager.loadQnnNatives] já faz o que a rota fazia à mão:
     * registra o diretório no classloader, carrega `cdsprpc` do vendor, aponta
     * `ADSP_LIBRARY_PATH` para o diretório do QAIRT e pula o que **já está
     * carregado** no processo. Assim Granite (NPU) e Whisper (NPU) no mesmo
     * processo compartilham uma instância do runtime e um único caminho de skels.
     */
    private fun carregarQnnQairt(ctx: Context, htpArch: String) {
        if (qnnCarregado) return
        synchronized(this) {
            if (qnnCarregado) return
            check(QairtDependencyManager.isInstalled(ctx)) { "QAIRT ausente" }
            QairtDependencyManager.loadQnnNatives(ctx, "htp", htpArch)
            qnnCarregado = true
        }
    }

    /**
     * Caminho do backend QNN dentro do pacote QAIRT.
     *
     * Função pequena de propósito: é a regra "a rota do Whisper aponta para o
     * QAIRT, nunca para o pacote da NPU" provada em JVM, sem Android.
     */
    internal fun backendHtp(dirQairt: File): File = File(File(dirQairt, "lib"), "libQnnHtp.so")

    /** Abre a sessão EPContext no QNN HTP estrito (sem fallback para CPU do EP). */
    private fun abrirSessaoHtp(
        wrapper: File,
        dirQairt: File,
        htpArch: String,
    ): WhisperOrtHtpSessao {
        check(wrapper.isFile) { "wrapper ausente" }
        val env = OrtEnvironment.getEnvironment(OrtLoggingLevel.ORT_LOGGING_LEVEL_WARNING, TAG)
        val opts = OrtSession.SessionOptions()
        opts.addQnn(
            linkedMapOf(
                "backend_path" to backendHtp(dirQairt).absolutePath,
                "htp_arch" to htpArch,
                "device_id" to "0",
                "offload_graph_io_quantization" to "0",
                "enable_htp_fp16_precision" to "0",
                "htp_performance_mode" to HTP_MODE,
            ),
        )
        opts.addConfigEntry("session.disable_cpu_ep_fallback", "1")
        opts.addConfigEntry("session.log_severity_level", "3")
        opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        val sessao = env.createSession(wrapper.absolutePath, opts)
        return object : WhisperOrtHtpSessao {
            override fun rodar(entrada: ByteArray): ByteArray {
                val info = sessao.inputInfo[ENTRADA]?.info as? TensorInfo
                    ?: throw IllegalStateException("metadata de entrada ausente")
                val tipo = when (info.onnxType) {
                    TensorInfo.OnnxTensorType.ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT16 ->
                        OnnxJavaType.FLOAT16
                    TensorInfo.OnnxTensorType.ONNX_TENSOR_ELEMENT_DATA_TYPE_INT16,
                    TensorInfo.OnnxTensorType.ONNX_TENSOR_ELEMENT_DATA_TYPE_UINT16,
                    -> OnnxJavaType.INT16
                    else -> throw IllegalStateException("dtype de entrada inesperado: ${info.onnxType}")
                }
                val buffer = ByteBuffer.allocateDirect(entrada.size).order(ByteOrder.LITTLE_ENDIAN)
                buffer.put(entrada)
                buffer.rewind()
                OnnxTensor.createTensor(env, buffer, info.shape, tipo).use { tensor ->
                    sessao.run(mapOf(ENTRADA to tensor)).use { resultado ->
                        val saida = resultado.get(0) as OnnxTensor
                        val bytes = saida.byteBuffer
                        val copia = ByteArray(bytes.remaining())
                        bytes.get(copia)
                        return copia
                    }
                }
            }

            override fun close() {
                runCatching { sessao.close() }
                runCatching { opts.close() }
            }
        }
    }
}
