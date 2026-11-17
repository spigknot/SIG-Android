package br.gov.sp.pcsp.launcher

/**
 * R16/§5 — seam PURO da política de estágio do ORT (etapa `GPU-3`).
 *
 * Regra de negócio sem I/O: diz **se** uma tentativa pode rodar em `CPU_INTEGRAL`
 * (default de produção) ou em `HIBRIDO_QNN_GPU_ENC_CPU_DEC` (encoder QNN GPU em
 * segmentos + decoders INT8 em CPU), e **como** cair para CPU quando o híbrido
 * não está qualificado — sempre com consentimento explícito, nunca em silêncio.
 *
 * Nada aqui abre sessão, lê arquivo ou toca QNN: quem chama entrega os fatos
 * (modelo, threads, [QualificacaoOrtGpu], [AmbienteOrt], segmentos presentes) e
 * recebe uma decisão imutável. É o que permite testar a política em JVM, sem
 * aparelho.
 */
object WhisperOrtStagePolicy {

    /** Modos de execução do motor ORT. */
    enum class ModoExecucaoOrt {
        /** Produção: encoder + decoders INT8 na CPU. */
        CPU_INTEGRAL,

        /** Laboratório/candidato: encoder nos 6 segmentos QNN GPU, decoders na CPU. */
        HIBRIDO_QNN_GPU_ENC_CPU_DEC,
    }

    /**
     * Identidade do pacote segmentado: só vale para o modelo/SoC/runtime que foi
     * de fato qualificado. `sha256Segmentos` mapeia nome do segmento → hash.
     */
    data class QualificacaoOrtGpu(
        val modeloId: String,
        val socAlvo: String,
        val runtimeVersao: String,
        val qnnPluginVersao: String,
        val qnnSdkVersao: String,
        val segmentosEsperados: Int,
        val sha256Segmentos: Map<String, String>,
    )

    /** Fatos do ambiente no momento da decisão (sem I/O neste seam). */
    data class AmbienteOrt(
        val socAtual: String,
        val runtimeDesacopladoDisponivel: Boolean,
        val pluginQnnDisponivel: Boolean,
        val backendGpuDisponivel: Boolean,
    )

    /** Decisão do estágio: ou executa num modo, ou recusa com motivo. */
    sealed class DecisaoEstagioOrt {
        data class Permitido(
            val modoEfetivo: ModoExecucaoOrt,
            val rotuloHardware: String,
            val chaveCacheSessao: String,
        ) : DecisaoEstagioOrt()

        data class Recusado(
            val motivoCodigo: String,
            val mensagemUsuario: String,
            val permiteFallbackConsentidoCpu: Boolean,
        ) : DecisaoEstagioOrt()
    }

    /** Modelos aceitos em CPU (todos os suportados pela produção). */
    val MODELOS_CPU = setOf("tiny", "base", "small", "v3Turbo")

    /** Único modelo qualificado para o híbrido nesta etapa. */
    const val MODELO_GPU_QUALIFICADO = "v3Turbo"

    /** Partição usada quando o chamador não especifica outra. */
    const val PARTICAO_PADRAO = "todas"

    /** Rótulo honesto do híbrido: GPU no encoder, CPU no decoder. */
    const val ROTULO_HIBRIDO = "GPU (QNN enc) + CPU (dec)"

    /** Rótulo da CPU com o número de threads efetivo. */
    fun rotuloCpu(threads: Int): String = "CPU ($threads threads)"

    /**
     * Decide o estágio. [segmentosPresentes] são os nomes realmente disponíveis
     * (não os esperados) — a política não confia na intenção, só no que existe.
     */
    fun decidir(
        modoPedido: ModoExecucaoOrt,
        modeloId: String,
        threadsCpu: Int,
        qualificacao: QualificacaoOrtGpu?,
        ambiente: AmbienteOrt,
        segmentosPresentes: Set<String>,
        segmentosVazios: Set<String> = emptySet(),
    ): DecisaoEstagioOrt {
        if (modoPedido == ModoExecucaoOrt.CPU_INTEGRAL) {
            if (modeloId !in MODELOS_CPU) {
                return DecisaoEstagioOrt.Recusado(
                    motivoCodigo = "MODELO_NAO_SUPORTADO",
                    mensagemUsuario = "Modelo não suportado pela rota CPU: $modeloId",
                    permiteFallbackConsentidoCpu = false,
                )
            }
            return DecisaoEstagioOrt.Permitido(
                modoEfetivo = ModoExecucaoOrt.CPU_INTEGRAL,
                rotuloHardware = rotuloCpu(threadsCpu),
                chaveCacheSessao = chaveCache(
                    ModoExecucaoOrt.CPU_INTEGRAL, modeloId, threadsCpu,
                    qualificacao, hashCombinado = "",
                ),
            )
        }

        // Híbrido: cada pré-condição tem código próprio, para a tela poder explicar.
        if (modeloId != MODELO_GPU_QUALIFICADO) {
            return DecisaoEstagioOrt.Recusado(
                motivoCodigo = "MODELO_NAO_QUALIFICADO_GPU",
                mensagemUsuario = "O modo GPU só está qualificado para $MODELO_GPU_QUALIFICADO.",
                permiteFallbackConsentidoCpu = true,
            )
        }
        if (qualificacao == null) {
            return DecisaoEstagioOrt.Recusado(
                motivoCodigo = "PACOTE_SEGMENTADO_INCOMPLETO",
                mensagemUsuario = "Pacote segmentado do encoder não está instalado.",
                permiteFallbackConsentidoCpu = true,
            )
        }
        val esperados = qualificacao.sha256Segmentos.keys
        // R21/§3.1 — pacote com contagem declarada inválida não é "incompleto":
        // é um manifesto inconsistente, e isso precisa de código próprio.
        if (qualificacao.segmentosEsperados <= 0 ||
            qualificacao.segmentosEsperados != esperados.size
        ) {
            return DecisaoEstagioOrt.Recusado(
                motivoCodigo = "PACOTE_SEGMENTADO_TAMANHO_INVALIDO",
                mensagemUsuario = "Manifesto de segmentos inconsistente: " +
                    "declara ${qualificacao.segmentosEsperados} e lista ${esperados.size}.",
                permiteFallbackConsentidoCpu = true,
            )
        }
        val vazios = segmentosPresentes.intersect(segmentosVazios)
        if (vazios.isNotEmpty()) {
            return DecisaoEstagioOrt.Recusado(
                motivoCodigo = "SEGMENTO_VAZIO",
                mensagemUsuario = "Segmento com 0 bytes: ${vazios.sorted()}",
                permiteFallbackConsentidoCpu = true,
            )
        }
        val faltando = esperados - segmentosPresentes
        if (faltando.isNotEmpty() || esperados.size != qualificacao.segmentosEsperados) {
            return DecisaoEstagioOrt.Recusado(
                motivoCodigo = "PACOTE_SEGMENTADO_INCOMPLETO",
                mensagemUsuario = "Faltam segmentos do encoder: ${faltando.sorted()}",
                permiteFallbackConsentidoCpu = true,
            )
        }
        if (!ambiente.runtimeDesacopladoDisponivel ||
            !ambiente.pluginQnnDisponivel ||
            !ambiente.backendGpuDisponivel ||
            ambiente.socAtual != qualificacao.socAlvo
        ) {
            return DecisaoEstagioOrt.Recusado(
                motivoCodigo = "RUNTIME_QNN_INDISPONIVEL",
                mensagemUsuario = "Runtime QNN (plugin/SDK/backend) indisponível neste aparelho.",
                permiteFallbackConsentidoCpu = true,
            )
        }
        return DecisaoEstagioOrt.Permitido(
            modoEfetivo = ModoExecucaoOrt.HIBRIDO_QNN_GPU_ENC_CPU_DEC,
            rotuloHardware = ROTULO_HIBRIDO,
            chaveCacheSessao = chaveCache(
                ModoExecucaoOrt.HIBRIDO_QNN_GPU_ENC_CPU_DEC, modeloId, threadsCpu,
                qualificacao, hashCombinado = hashCombinado(qualificacao.sha256Segmentos),
            ),
        )
    }

    /**
     * Fallback honesto: recusa **nunca** vira CPU silenciosamente.
     *
     * Só devolve `Permitido(CPU_INTEGRAL, …)` quando
     * [consentimentoExplicitoUsuario] é `true`; sem consentimento, a recusa é
     * preservada (mesmo código e mesma mensagem).
     */
    fun resolverFallback(
        decisao: DecisaoEstagioOrt,
        consentimentoExplicitoUsuario: Boolean,
        modeloId: String = MODELO_GPU_QUALIFICADO,
        threadsCpu: Int = 6,
    ): DecisaoEstagioOrt {
        if (decisao is DecisaoEstagioOrt.Permitido) return decisao
        val recusa = decisao as DecisaoEstagioOrt.Recusado
        if (!consentimentoExplicitoUsuario || !recusa.permiteFallbackConsentidoCpu) return recusa
        return DecisaoEstagioOrt.Permitido(
            modoEfetivo = ModoExecucaoOrt.CPU_INTEGRAL,
            rotuloHardware = rotuloCpu(threadsCpu),
            chaveCacheSessao = chaveCache(
                ModoExecucaoOrt.CPU_INTEGRAL, modeloId, threadsCpu, null, "",
            ),
        )
    }

    /** Hash combinado e determinístico dos segmentos (ordem por nome). */
    fun hashCombinado(sha256Segmentos: Map<String, String>): String {
        val base = sha256Segmentos.toSortedMap().entries.joinToString("|") { "${it.key}=${it.value}" }
        var hash = 0xcbf29ce484222325uL
        for (b in base.toByteArray(Charsets.UTF_8)) {
            hash = hash xor b.toULong()
            hash *= 0x100000001b3uL
        }
        return hash.toString(16).padStart(16, '0')
    }

    /**
     * Chave de cache de sessão: inclui modo, modelo, threads, versões do runtime
     * e o hash dos segmentos. Assim um cache de CPU nunca serve ao híbrido (e
     * vice-versa), e trocar qualquer peça invalida a chave.
     */
    fun chaveCache(
        modo: ModoExecucaoOrt,
        modeloId: String,
        threadsCpu: Int,
        qualificacao: QualificacaoOrtGpu?,
        hashCombinado: String,
        particaoGpu: String = PARTICAO_PADRAO,
    ): String {
        val runtime = qualificacao?.runtimeVersao ?: "-"
        val plugin = qualificacao?.qnnPluginVersao ?: "-"
        val sdk = qualificacao?.qnnSdkVersao ?: "-"
        return "ort:${modo.name}:$modeloId:thr$threadsCpu:rt$runtime:ep$plugin:sdk$sdk:" +
            "seg$hashCombinado:part$particaoGpu"
    }
}
