package br.gov.sp.pcsp.launcher

/**
 * R17/D2 — PERFIL POR SUBMISSÃO/FENCE (peça pura, host-testável).
 *
 * A R16 mediu o cancelamento só pelo tempo de parede: o gatilho era o progresso
 * do ARQUIVO, não o do encoder, e não se sabia quantas submissões estavam em voo
 * quando o cancel foi pedido — nem qual era o quantum (nós por submissão) do
 * job. Sem isso, "cancelou em 321 ms" não diz se o cancel esperou um lote de 400
 * nós ou se pegou a fila vazia.
 *
 * O diag nativo (`WhisperNative.b4InflightState()`) já publica os contadores:
 *
 *   state=… armed=… attached=… witness=… owner=… job=… attempt=…
 *   graph=… batch=… dispatch_count=… submit_seq=… fence_generation=…
 *   b4_submits=… b4_nodes=… b4_nodes_total=… b4_mulmat_bytes=…
 *   b4_max_nodes_per_submit=… b4_lote_max_ms=… b4_t_first_ms=… b4_t_last_ms=…
 *   b4_pending=NA b4_fences=NA
 *
 * Esta peça lê essa string (sem tocar no nativo), calcula o DELTA entre a
 * captura de antes e a de depois do cancel, e julga a coorte com as metas
 * declaradas — recusando PASS quando a amostra é menor que o alvo.
 */
object SubmissionFenceProfile {

    /** Alvos declarados (não são ajustáveis para "fechar" um resultado). */
    const val ALVO_AMOSTRAS = 20
    const val P95_ALVO_MS = 3_000L
    const val MAX_ALVO_MS = 5_000L

    data class Estado(
        val state: String,
        val owner: String,
        val submits: Long,          // b4_submits
        val nodes: Long,            // b4_nodes
        val nodesTotal: Long,       // b4_nodes_total
        val dispatchCount: Long,    // dispatch_count
        val submitSeq: Long,        // submit_seq
        val fenceGeneration: Long,  // fence_generation
        val maxNodesPerSubmit: Long,
        val loteMaxMs: Long,
        val quantumLast: Long,      // b4_quantum_last (dispatches/submissão do último grafo)
        val quantumAvg: Long,       // b4_quantum_avg (média do processo)
        val bruto: String,
    ) {
        /** R17/D2 — quantum = dispatches por submissão REAL (por grafo).
         *  Antes disto o campo vinha de nodes/submits (grafo ÷ cumulativo),
         *  que são grandezas diferentes e davam 0. */
        val quantumMedio: Long get() = if (quantumAvg > 0) quantumAvg else quantumLast
    }

    private fun campo(tokens: Map<String, String>, k: String): Long =
        tokens[k]?.toLongOrNull() ?: -1L

    /**
     * @return null quando a string é vazia/indisponível — ausência de diag NÃO
     *         é estado zerado, e tratá-la como zero fabricaria evidência.
     */
    fun parse(s: String?): Estado? {
        if (s == null) return null
        val t = s.trim()
        if (t.isEmpty() || t.contains("unavailable")) return null
        val tokens = HashMap<String, String>()
        for (par in t.split(' ')) {
            val i = par.indexOf('=')
            if (i <= 0) continue
            tokens[par.substring(0, i)] = par.substring(i + 1)
        }
        return Estado(
            state = tokens["state"] ?: "",
            owner = tokens["owner"] ?: "",
            submits = campo(tokens, "b4_submits"),
            nodes = campo(tokens, "b4_nodes"),
            nodesTotal = campo(tokens, "b4_nodes_total"),
            dispatchCount = campo(tokens, "dispatch_count"),
            submitSeq = campo(tokens, "submit_seq"),
            fenceGeneration = campo(tokens, "fence_generation"),
            maxNodesPerSubmit = campo(tokens, "b4_max_nodes_per_submit"),
            loteMaxMs = campo(tokens, "b4_lote_max_ms"),
            quantumLast = campo(tokens, "b4_quantum_last"),
            quantumAvg = campo(tokens, "b4_quantum_avg"),
            bruto = t,
        )
    }

    /**
     * Delta entre duas capturas do MESMO job. Valores negativos (contador que
     * reiniciou porque o job trocou) viram 0 e o chamador deve descartar o par —
     * por isso o delta expõe [Delta.valido].
     */
    data class Delta2(val submits: Long, val fenceGeracoes: Long, val dispatches: Long,
                      val nodes: Long, val quantumMedio: Long, val valido: Boolean)

    fun delta(antes: Estado, depois: Estado): Delta2 {
        val dSub = depois.submits - antes.submits
        val dFen = depois.fenceGeneration - antes.fenceGeneration
        val dDis = depois.dispatchCount - antes.dispatchCount
        val dNos = depois.nodesTotal - antes.nodesTotal
        val valido = antes.owner == depois.owner && dSub >= 0 && dFen >= 0 && dDis >= 0 && dNos >= 0
        return Delta2(
            submits = if (dSub > 0) dSub else 0,
            fenceGeracoes = if (dFen > 0) dFen else 0,
            dispatches = if (dDis > 0) dDis else 0,
            nodes = if (dNos > 0) dNos else 0,
            quantumMedio = if (dSub > 0) (if (dNos > 0) dNos else 0) / dSub else 0,
            valido = valido,
        )
    }

    enum class Veredito { PASS, INCOMPLETE, FAIL }

    /**
     * Julga a coorte. Regra: amostra insuficiente é INCOMPLETE (nunca PASS por
     * omissão) e qualquer max acima do teto é FAIL, mesmo com p95 bom.
     */
    fun veredito(
        amostras: Int,
        p95Ms: Long,
        maxMs: Long,
        alvo: Int = ALVO_AMOSTRAS,
        p95AlvoMs: Long = P95_ALVO_MS,
        maxAlvoMs: Long = MAX_ALVO_MS,
    ): Veredito = when {
        amostras < alvo -> Veredito.INCOMPLETE
        maxMs > maxAlvoMs -> Veredito.FAIL
        p95Ms > p95AlvoMs -> Veredito.FAIL
        else -> Veredito.PASS
    }
}
