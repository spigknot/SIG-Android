package br.gov.sp.pcsp.launcher

/**
 * ADENDO 26 §2 — seam PURO do contrato ASSÍNCRONO de apresentação.
 *
 * O problema que este arquivo resolve: o worker termina o compute e escreve o
 * recibo ANTES de o `runOnUiThread` do último segmento executar. Uma leitura
 * global tardia (ou uma fotografia precoce do worker) não sabe dizer qual era o
 * último segmento ESPERADO nem se ele chegou à tela — foi o sintoma do 99%
 * (`apresentado` = -1 no worker).
 *
 * A identidade é IMUTÁVEL por job/attempt ([TranscriptionJobKey]: run/act/job/
 * owner/entrada/attempt). Cada segmento tem uma sequência própria. O fluxo
 * registra:
 *
 *  - EMISSÃO — o post foi AGENDADO (ainda pode não executar);
 *  - APRESENTAÇÃO — o post EXECUTOU e atualizou a view (a escrita acontece
 *    DENTRO de [apresentar], depois da conferência de identidade);
 *  - RECUSA — destroy, troca de geração ou identidade divergente; o texto NÃO
 *    é escrito no sucessor.
 *
 * O recibo final responde: qual o último segmento esperado, se ele foi
 * apresentado, e em que instante — em RELAÇÃO À MESMA ORIGEM do compute
 * ([Fase.COMPUTE_INICIO]). Não há espera/bloqueio de UI nem de worker: quem lê
 * o recibo decide se aguarda o post pendente.
 *
 * Puro: sem Android, sem I/O, sem estado global. O relógio é injetado
 * (produção usa `SystemClock.elapsedRealtime`; teste usa relógio controlado).
 */
class WhisperApresentacaoObservador(
    val identidade: TranscriptionJobKey,
    private val relogio: () -> Long,
) {

    /** Fases do job — todas relativas à MESMA origem declarada no recibo. */
    enum class Fase {
        COMPUTE_INICIO,
        PRIMEIRO_99,
        ULTIMO_99,
        RETORNO,
        PARSE,
        PERSISTENCIA,
        FINALIZACAO,
        FINALIZACAO_AUSENTE,
        TERMINAL,
        LIBERACAO,
    }

    /** Por que um segmento emitido NÃO foi apresentado. */
    enum class MotivoRecusa {
        /** A tela morreu entre o agendamento e a execução do post. */
        DESTROY,

        /** Outra geração lógica virou corrente antes da execução do post. */
        GERACAO_TROCADA,

        /** O post executou com identidade de OUTRO job/attempt. */
        IDENTIDADE_DIVERGENTE,

        /** Sequência nunca emitida (evento obsoleto/duplicado). */
        FORA_DE_ORDEM,
    }

    /**
     * Estado de UM segmento desta tentativa.
     *
     * `emitidoEm` é o agendamento; `apresentadoEm` só é preenchido quando o post
     * executou e a view foi atualizada; `recusadoEm` guarda a recusa.
     */
    data class Segmento(
        val sequencia: Long,
        val emitidoEm: Long,
        val apresentadoEm: Long = -1L,
        val recusadoEm: Long = -1L,
        val motivo: MotivoRecusa? = null,
    ) {
        val apresentado: Boolean get() = apresentadoEm >= 0L
        val recusado: Boolean get() = recusadoEm >= 0L
    }

    /**
     * Fotografia IMUTÁVEL do recibo de UMA tentativa.
     *
     * [origem] nomeia o domínio dos instantes: `compute_inicio`. Todas as
     * marcas em [marcasMs] são DURAÇÕES desde essa origem (-1 = não ocorreu).
     * [apresentacaoMs] e [retornoAteApresentacaoMs] só existem quando os dois
     * instantes estão no mesmo relógio monotônico.
     */
    data class Recibo(
        val identidade: TranscriptionJobKey,
        val origem: String,
        val origemEm: Long,
        val marcasMs: Map<String, Long>,
        val ultimoEsperado: Long,
        val ultimoEmitido: Long,
        val ultimoApresentado: Long,
        val apresentados: Int,
        val recusados: Int,
        val pendentes: Int,
        val estado: String,
        val ultimoSegmento: Segmento?,
        val apresentacaoMs: Long,
        val retornoAteApresentacaoMs: Long,
        val liberacaoPendente: Boolean,
    ) {
        /** `true` quando o último segmento esperado já teve desfecho observado. */
        val fechado: Boolean
            get() = estado == ESTADO_APRESENTADO || estado.startsWith(ESTADO_RECUSADO) ||
                estado == ESTADO_SEM_SEGMENTOS

        /**
         * `true` quando o recibo final pode ser publicado: desfecho do último
         * segmento observado E compute/persistência/finalização já marcados.
         * Antes disso a fotografia é PARCIAL e não substitui o fechamento.
         */
        val podeFechar: Boolean
            get() = fechado &&
                marcasMs.containsKey("persistencia") &&
                marcasMs.containsKey("terminal") &&
                (marcasMs.containsKey("finalizacao") || marcasMs.containsKey("finalizacao_ausente"))
    }

    private val lock = Any()
    private val segmentos = LinkedHashMap<Long, Segmento>()
    private val marcas = LinkedHashMap<Fase, Long>()
    private var ultimoEmitido = 0L
    private var fechadoParaEmissao = false
    private var liberacaoPendente = false
    private var reciboFinalPublicado = false

    /**
     * Registra a EMISSÃO de um segmento (post agendado). `false` = sequência
     * fora de ordem/duplicada; emissão nunca regride.
     */
    fun emitido(sequencia: Long): Boolean = synchronized(lock) {
        if (fechadoParaEmissao) return@synchronized false
        if (sequencia <= ultimoEmitido) return@synchronized false
        val agora = relogio()
        segmentos[sequencia] = Segmento(sequencia = sequencia, emitidoEm = agora)
        ultimoEmitido = sequencia
        true
    }

    /**
     * APRESENTA o segmento: confere a identidade da tentativa, executa [escrever]
     * (a atualização da view) e SÓ ENTÃO marca o instante.
     *
     * Identidade divergente NÃO escreve nada — é o que impede o sucessor de
     * exibir texto de outra tentativa. `false` = não apresentado; o motivo fica
     * registrado no segmento.
     */
    fun apresentar(
        sequencia: Long,
        identidadeNaExecucao: TranscriptionJobKey,
        escrever: () -> Unit,
    ): Boolean = synchronized(lock) {
        if (identidadeNaExecucao != identidade) {
            registrarRecusa(sequencia, MotivoRecusa.IDENTIDADE_DIVERGENTE)
            return@synchronized false
        }
        val atual = segmentos[sequencia]
        if (atual == null || atual.recusado) {
            registrarRecusa(sequencia, MotivoRecusa.FORA_DE_ORDEM)
            return@synchronized false
        }
        if (atual.apresentado) {
            // Repetição do mesmo post não reescreve nem re-marca.
            return@synchronized false
        }
        escrever()
        segmentos[sequencia] = atual.copy(apresentadoEm = relogio())
        true
    }

    /** Registra a RECUSA de um segmento (destroy/geração trocada). */
    fun recusar(sequencia: Long, motivo: MotivoRecusa): Boolean = synchronized(lock) {
        registrarRecusa(sequencia, motivo)
    }

    private fun registrarRecusa(sequencia: Long, motivo: MotivoRecusa): Boolean {
        val atual = segmentos[sequencia]
        if (atual == null) {
            // Sequência nunca emitida: registra para o recibo denunciar o
            // evento obsoleto em vez de escondê-lo.
            segmentos[sequencia] = Segmento(
                sequencia = sequencia, emitidoEm = 0L,
                recusadoEm = relogio(), motivo = motivo,
            )
            return false
        }
        if (atual.recusado || atual.apresentado) return false
        segmentos[sequencia] = atual.copy(recusadoEm = relogio(), motivo = motivo)
        return true
    }

    /** Origem declarada do recibo: início do compute desta tentativa. */
    fun inicioCompute(instante: Long = relogio()): Unit = synchronized(lock) {
        if (!marcas.containsKey(Fase.COMPUTE_INICIO)) marcas[Fase.COMPUTE_INICIO] = instante
    }

    /** Primeiro e último evento de 99% (o último sempre avança). */
    fun progresso99(instante: Long = relogio()): Unit = synchronized(lock) {
        if (!marcas.containsKey(Fase.PRIMEIRO_99)) marcas[Fase.PRIMEIRO_99] = instante
        marcas[Fase.ULTIMO_99] = instante
    }

    fun retorno(instante: Long = relogio()): Unit = marcar(Fase.RETORNO, instante)

    fun parse(instante: Long = relogio()): Unit = marcar(Fase.PARSE, instante)

    fun persistencia(instante: Long = relogio()): Unit = marcar(Fase.PERSISTENCIA, instante)

    fun finalizacao(instante: Long = relogio()): Unit = marcar(Fase.FINALIZACAO, instante)

    /**
     * ADENDO 27 §2 — a UI NÃO vai finalizar (destroy/geração trocada). É um
     * FATO tipado, não uma espera: permite fechar o recibo quando o terminal
     * chegar, sem depender de um post que nunca vai executar.
     */
    fun finalizacaoAusente(instante: Long = relogio()): Unit = marcar(Fase.FINALIZACAO_AUSENTE, instante)

    /**
     * Terminal do job. A liberação é registrada SEPARADAMENTE porque só o dono
     * da reserva pode prová-la: [liberacaoConfirmada] = a reserva não está mais
     * presa a este job.
     */
    fun terminal(instante: Long = relogio(), liberacaoConfirmada: Boolean) = synchronized(lock) {
        marcar(Fase.TERMINAL, instante)
        if (liberacaoConfirmada) {
            marcar(Fase.LIBERACAO, instante)
            liberacaoPendente = false
        } else {
            liberacaoPendente = true
        }
    }

    private fun marcar(fase: Fase, instante: Long): Unit = synchronized(lock) {
        if (fase == Fase.COMPUTE_INICIO) {
            if (!marcas.containsKey(fase)) marcas[fase] = instante
        } else {
            marcas[fase] = instante
        }
    }

    /** Encerra a EMISSÃO (o compute/persistência terminaram). Posts já emitidos continuam válidos. */
    fun fecharEmissao() = synchronized(lock) {
        fechadoParaEmissao = true
    }

    /**
     * Reserva a publicação do recibo FINAL (idempotente). Só quem receber
     * `true` publica — evita duas linhas finais para a mesma tentativa.
     */
    fun reservarPublicacaoFinal(): Boolean = synchronized(lock) {
        if (reciboFinalPublicado) return@synchronized false
        reciboFinalPublicado = true
        true
    }

    /** Snapshot do recibo desta tentativa. Nunca lança e nunca devolve outro job. */
    fun recibo(): Recibo = synchronized(lock) {
        val origemEm = marcas[Fase.COMPUTE_INICIO] ?: 0L
        val apresentados = segmentos.values.count { it.apresentado }
        val recusados = segmentos.values.count { it.recusado }
        val ultimo = segmentos[ultimoEmitido] ?: segmentos.values.maxByOrNull { it.sequencia }
        val pendentes = segmentos.values.count { !it.apresentado && !it.recusado }
        val ultimoApresentado = segmentos.values.filter { it.apresentado }
            .maxByOrNull { it.sequencia }?.sequencia ?: 0L
        val estado = when {
            ultimoEmitido == 0L -> ESTADO_SEM_SEGMENTOS
            ultimo == null -> ESTADO_PENDENTE
            ultimo.apresentado -> ESTADO_APRESENTADO
            ultimo.recusado -> "$ESTADO_RECUSADO:${ultimo.motivo?.name ?: "?"}"
            else -> ESTADO_PENDENTE
        }
        val marcasMs = LinkedHashMap<String, Long>()
        for (fase in Fase.values()) {
            val valor = marcas[fase] ?: continue
            marcasMs[fase.name.lowercase()] = if (origemEm > 0L && valor > 0L) valor - origemEm else -1L
        }
        val apresentadoEm = segmentos.values.filter { it.apresentado }
            .maxByOrNull { it.sequencia }?.apresentadoEm ?: -1L
        val retornoEm = marcas[Fase.RETORNO] ?: -1L
        Recibo(
            identidade = identidade,
            origem = "compute_inicio",
            origemEm = origemEm,
            marcasMs = marcasMs,
            ultimoEsperado = ultimoEmitido,
            ultimoEmitido = ultimo?.sequencia ?: 0L,
            ultimoApresentado = ultimoApresentado,
            apresentados = apresentados,
            recusados = recusados,
            pendentes = pendentes,
            estado = estado,
            ultimoSegmento = ultimo,
            apresentacaoMs = if (origemEm > 0L && apresentadoEm >= 0L) apresentadoEm - origemEm else -1L,
            retornoAteApresentacaoMs = if (retornoEm > 0L && apresentadoEm >= 0L) apresentadoEm - retornoEm else -1L,
            liberacaoPendente = liberacaoPendente,
        )
    }

    /**
     * Linha de recibo para o terminal do PRODUTO (uma linha, campos estáveis).
     * [rotulo] distingue a fotografia do worker (`fase1`) do fechamento real
     * publicado quando o último post executa (`final`).
     */
    fun linha(rotulo: String): String {
        val r = recibo()
        fun m(nome: String): Long = r.marcasMs[nome] ?: -1L
        val entrada = r.identidade.inputId.replace(Regex("\\s+"), "_")
        return "apresentacao rotulo=$rotulo job=${r.identidade.jobId} gen=${r.identidade.owner} " +
            "att=${r.identidade.attemptId} entrada=$entrada origem=${r.origem} " +
            "ultimo_esperado=${r.ultimoEsperado} ultimo_emitido=${r.ultimoEmitido} " +
            "ultimo_apresentado=${r.ultimoApresentado} apresentados=${r.apresentados} " +
            "recusados=${r.recusados} pendentes=${r.pendentes} estado=${r.estado} " +
            "apresentacao_ms=${r.apresentacaoMs} retorno_ate_apresentacao_ms=${r.retornoAteApresentacaoMs} " +
            "compute_inicio_ms=${m("compute_inicio")} p99_primeiro_ms=${m("primeiro_99")} " +
            "p99_ultimo_ms=${m("ultimo_99")} retorno_ms=${m("retorno")} parse_ms=${m("parse")} " +
            "persistencia_ms=${m("persistencia")} finalizacao_ms=${m("finalizacao")} " +
            "terminal_ms=${m("terminal")} liberacao_ms=${m("liberacao")} " +
            "finalizacao_ausente=${r.marcasMs.containsKey("finalizacao_ausente")} " +
            "liberacao_pendente=${r.liberacaoPendente}"
    }

    companion object {
        const val ESTADO_SEM_SEGMENTOS = "SEM_SEGMENTOS"
        const val ESTADO_APRESENTADO = "APRESENTADO"
        const val ESTADO_PENDENTE = "PENDENTE"
        const val ESTADO_RECUSADO = "RECUSADO"
    }
}
