package br.gov.sp.pcsp.launcher

/**
 * Segmentação e avanço (seek) por ORIGEM TEMPORAL — porte do `_retrieve_segment`
 * do Whisper (ORT1-M2, adendo8 §5).
 *
 * O que mudou em relação ao que existia: nada é decidido por semelhança de
 * palavras. A referência define "fim de segmento" como um PAR de timestamps
 * consecutivos; só os segmentos fechados são publicados, o resto é cauda
 * incompleta (pendente) e o seek avança até a última fronteira FECHADA. Assim a
 * palavra repetida em outra ocorrência não corre risco de ser apagada por
 * igualdade lexical — o critério é a fronteira temporal.
 *
 * Regras portadas (com o mesmo significado da referência):
 * - `singleTimestampEnding`: os DOIS últimos tokens são [texto, timestamp] — o
 *   modelo terminou com um único timestamp, ou seja, consumiu a janela inteira;
 * - fronteiras = índices i+1 onde `tokens[i]` e `tokens[i+1]` são ambos
 *   timestamps (par que fecha segmento);
 * - com fronteiras: os segmentos são as fatias entre fronteiras; o seek vai para
 *   a última fronteira fechada quando NÃO houve fim simples, e para o fim da
 *   janela quando houve;
 * - sem fronteiras: a decodificação inteira é um segmento e o seek consome a
 *   janela.
 *
 * Seam puro: recebe tokens, devolve dados. Nenhum tensor, nenhum Android.
 */
object WhisperOrtSegments {

    /** Passo entre tokens de timestamp, em segundos (0,02 s). */
    const val TIMESTAMP_STEP_SECONDS = 0.02

    /** Primeiro id de timestamp para um dado `no_timestamps_token_id`. */
    fun timestampBegin(noTimestampsTokenId: Int): Int = noTimestampsTokenId + 1

    /** `true` quando [id] é token de timestamp para este vocabulário. */
    fun isTimestamp(id: Int, noTimestampsTokenId: Int, vocabSize: Int): Boolean =
        id >= timestampBegin(noTimestampsTokenId) && id < vocabSize

    /** Segundos do token de timestamp [id]. */
    fun seconds(id: Int, noTimestampsTokenId: Int): Double =
        (id - timestampBegin(noTimestampsTokenId)) * TIMESTAMP_STEP_SECONDS

    /** Segmento FECHADO, com tempo absoluto no áudio completo. */
    data class Segment(val startSeconds: Double, val endSeconds: Double, val text: String)

    /**
     * Resultado de uma janela.
     *
     * [pendingText] é o texto DEPOIS da última fronteira fechada: fala cortada no
     * meio do áudio. Não é publicado nem descartado — fica pendente para a
     * continuação, e o seek garante que a próxima janela recomece ali.
     * [pendingFromSeconds] é a ORIGEM temporal da cauda (fim do último segmento
     * fechado, ou o começo da janela): a reconciliação exige que a janela seguinte
     * cubra essa origem — texto igual sem cobertura não confirma nada.
     */
    data class Retrieval(
        val segments: List<Segment>,
        val pendingText: String,
        val seekSeconds: Double,
        val singleTimestampEnding: Boolean,
        val closedBoundaries: Int,
        val pendingFromSeconds: Double = 0.0,
    )

    /**
     * Porte de `_retrieve_segment`.
     *
     * [windowOffsetSeconds] é o começo da janela no áudio; [windowDurationSeconds]
     * é o intervalo ÚTIL (sem padding). Devolve segmentos fechados em tempo
     * absoluto, a cauda pendente e onde a próxima janela deve começar.
     */
    fun retrieve(
        tokens: List<Int>,
        noTimestampsTokenId: Int,
        vocabSize: Int,
        windowOffsetSeconds: Double,
        windowDurationSeconds: Double,
        decode: (IntArray) -> String,
    ): Retrieval {
        if (tokens.isEmpty()) {
            return Retrieval(emptyList(), "", windowOffsetSeconds, false, 0, windowOffsetSeconds)
        }
        val ehTs = BooleanArray(tokens.size) { isTimestamp(tokens[it], noTimestampsTokenId, vocabSize) }
        // Fim simples: [texto, timestamp] nos dois últimos.
        val singleEnding = tokens.size >= 2 && !ehTs[tokens.size - 2] && ehTs[tokens.size - 1]
        // Fronteiras: par de timestamps consecutivos -> índice i+1.
        val fronteiras = ArrayList<Int>(4)
        for (i in 0 until tokens.size - 1) {
            if (ehTs[i] && ehTs[i + 1]) fronteiras.add(i + 1)
        }
        fun texto(de: Int, ate: Int): String =
            if (ate <= de) "" else decode(tokens.subList(de, ate).toIntArray()).trim()
        fun segundosDe(id: Int): Double = windowOffsetSeconds + seconds(id, noTimestampsTokenId)
        // Nenhum tempo publicado sai do intervalo ÚTIL da janela: limitar só o
        // seek deixava `endSeconds` consumir áudio inexistente.
        val limiteUtil = windowOffsetSeconds + windowDurationSeconds
        fun prender(instante: Double): Double = instante.coerceIn(windowOffsetSeconds, limiteUtil)

        if (fronteiras.isEmpty()) {
            // Sem fronteira: a janela inteira é um segmento; consome o áudio.
            val ultimoTs = tokens.indices.lastOrNull { ehTs[it] }
            val fim = if (ultimoTs != null && tokens[ultimoTs] > timestampBegin(noTimestampsTokenId)) {
                prender(segundosDe(tokens[ultimoTs]))
            } else {
                limiteUtil
            }
            val texto = decode(tokens.toIntArray()).trim()
            val segmentos = if (texto.isEmpty()) emptyList() else listOf(Segment(windowOffsetSeconds, fim, texto))
            return Retrieval(segmentos, "", limiteUtil, singleEnding, 0, limiteUtil)
        }

        val fatias = ArrayList<Int>(fronteiras)
        if (singleEnding) {
            fatias.add(tokens.size)
        } else {
            fatias[fatias.size - 1] = fatias.last() + 1
        }
        val segmentos = ArrayList<Segment>(fatias.size)
        var inicioFatia = 0
        for ((indice, fatia) in fatias.withIndex()) {
            if (fatia <= inicioFatia) continue
            val ultima = indice == fatias.size - 1
            val primeiro = tokens[inicioFatia]
            // A referencia usa -1 (ultimo token da fatia) para TODA fatia nao-final
            // e tambem para a ultima quando o fim e simples; so a ultima fatia de
            // uma sequencia sem fim simples termina no penultimo (o par fecha).
            val posFinal = when {
                !ultima || singleEnding -> tokens[fatia - 1]
                else -> tokens[fatia - 2]
            }
            val texto = texto(inicioFatia, fatia)
            if (texto.isNotEmpty()) {
                segmentos.add(Segment(prender(segundosDe(primeiro)), prender(segundosDe(posFinal)), texto))
            }
            inicioFatia = fatia
        }
        // Cauda depois da última fronteira: fala cortada no meio.
        val cauda = if (singleEnding) "" else texto(fatias.last(), tokens.size)
        // O seek nunca passa do áudio ÚTIL da janela: antes, uma entrada de 1 s
        // (padded até 30 s) admitia seek de 20-30 s, consumindo áudio inexistente
        // e produzindo SUCCESS indevido.
        val seekBruto = if (singleEnding) {
            limiteUtil
        } else {
            // Seek pela última fronteira FECHADA (par de timestamps), não pela
            // abertura seguinte: a referência usa last_slice-2. Com ts0,a,ts1,ts2,b
            // a versão anterior dava seek 2 s onde a referência dá 1 s.
            val fechada = fatias.last() - 2
            segundosDe(tokens[if (fechada >= 0) fechada else fatias.last() - 1])
        }
        val seek = seekBruto.coerceIn(windowOffsetSeconds, limiteUtil)
        // Origem temporal da cauda: fim do último segmento fechado (ou o começo
        // da janela, sem fechamento). A janela seguinte precisa COBRIR essa origem.
        val origemPendente = if (singleEnding) {
            limiteUtil
        } else {
            segmentos.lastOrNull()?.endSeconds ?: windowOffsetSeconds
        }
        return Retrieval(segmentos, cauda, seek, singleEnding, fronteiras.size, origemPendente)
    }

    /**
     * Junta segmentos de várias janelas por TEMPO ABSOLUTO.
     *
     * Sem alinhamento lexical: um segmento entra se o FIM for depois do já
     * comprometido. Como o seek só avança por fronteira fechada, a sobreposição
     * de áudio é a mesma região já publicada — e o segmento seguinte começa nela,
     * então o corte por fim resolve sem apagar palavra nenhuma.
     */
    fun join(
        committedText: String,
        committedEndSeconds: Double,
        segments: List<Segment>,
        minimumAdvanceSeconds: Double = 0.05,
    ): Pair<String, Double> {
        var texto = committedText
        var fim = committedEndSeconds
        for (segmento in segments) {
            if (segmento.text.isBlank()) continue
            if (segmento.endSeconds <= fim + minimumAdvanceSeconds) continue
            texto = if (texto.isBlank()) segmento.text else "$texto ${segmento.text}"
            fim = maxOf(fim, segmento.endSeconds)
        }
        return texto to fim
    }
}
