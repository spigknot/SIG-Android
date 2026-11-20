package br.gov.sp.pcsp.launcher

/**
 * R115 §T02 — CONTRATO numérico da rota HTP de ponta a ponta (desenho Q′ da
 * R114), em regra PURA.
 *
 * Os números são os pinados nas rodadas que os mediram:
 * - entrada do projetor = saída do encoder do produto: escala
 *   [WhisperOrtHtpConversao.ESCALA_SAIDA_I16] e offset
 *   [WhisperOrtHtpConversao.DESLOCAMENTO_SAIDA_I16] (R112/T02 e R113);
 * - logits uint16: escala 0,0007861469688191425 e offset 23109 (R111/T01,
 *   o mesmo contrato usado pelos braços Q/Q′ da R113/R114);
 * - máscara uint16: 65535 vale 0,0 e 0 vale −100,0, alinhada à direita, com a
 *   coluna `199 − posição` marcada a cada passo (app oficial da Qualcomm);
 * - cache self de 199 posições (0..198) dentro de uma máscara de 200: a janela
 *   faz no máximo 199 passos HTP, como no braço Q′ qualificado. O passo de
 *   posição 199 exigiria um 200º slot que o tensor de cache não tem — é
 *   exatamente aí que a janela volta inteira para o decoder CPU INT8.
 *
 * Nada aqui toca Android nem ORT: o teste JVM prova cada constante e a regra da
 * volta para a CPU antes de qualquer lançamento no aparelho.
 */
object WhisperOrtHtpContrato {

    /** Escala do hidden (saída do encoder = entrada do projetor). */
    const val ESCALA_OCULTO = WhisperOrtHtpConversao.ESCALA_SAIDA_I16

    /** Offset uint16 do hidden (constante ARITMÉTICA: `codigo = round(x/escala) + offset`). */
    const val OFFSET_OCULTO = WhisperOrtHtpConversao.DESLOCAMENTO_SAIDA_I16

    /**
     * O MESMO offset com o sinal do encoding QNN (`-31745`), que é como ele
     * aparece no manifesto publicado, na assinatura do contexto e nos encodings.
     * Confundir os dois sinais desloca o hidden inteiro — por isso os dois nomes
     * existem e o teste confere os dois.
     */
    const val OFFSET_QNN_OCULTO = -OFFSET_OCULTO

    /** Escala dos logits uint16 do decoder. */
    const val ESCALA_LOGIT = 0.0007861469688191425

    /** Offset dos logits uint16 do decoder. */
    const val OFFSET_LOGIT = 23109

    /** Largura da máscara de atenção (posições). */
    const val TAMANHO_MASCARA = 200

    /** Posições do cache self do decoder (0..198). */
    const val POSICOES_CACHE_SELF = 199

    /** Valor que marca a posição como válida na máscara (0,0). */
    const val MASCARA_VALIDO = 65535

    /** Valor que mantém a posição mascarada (−100,0). */
    const val MASCARA_VAZIO = 0

    /** Códigos de uma janela: 1500 frames × 1280 dimensões. */
    const val CODIGOS_DA_JANELA = 1500 * 1280

    /** Código uint16 do hidden → float, pelo contrato do produto. */
    fun codigoParaOculto(codigo: Int): Float =
        ((codigo - OFFSET_OCULTO) * ESCALA_OCULTO).toFloat()

    /** float do hidden → código uint16 da entrada do projetor. */
    fun codigoOculto(valor: Float): Int {
        val q = Math.rint(valor.toDouble() / ESCALA_OCULTO) + OFFSET_OCULTO
        return q.coerceIn(0.0, 65535.0).toInt()
    }

    /** hidden achatado → bytes little-endian uint16 (entrada do projetor). */
    fun ocultoParaBytes(hidden: FloatArray): ByteArray {
        val out = ByteArray(hidden.size * 2)
        for (i in hidden.indices) {
            val codigo = codigoOculto(hidden[i])
            out[2 * i] = (codigo and 0xFF).toByte()
            out[2 * i + 1] = ((codigo ushr 8) and 0xFF).toByte()
        }
        return out
    }

    /** Código uint16 → float, pelo contrato dos encodings do decoder. */
    fun codigoParaLogit(codigo: Int): Float =
        ((codigo - OFFSET_LOGIT) * ESCALA_LOGIT).toFloat()

    /** Código uint16 little-endian na posição [i] de [bytes]. */
    fun codigoEm(bytes: ByteArray, i: Int): Int =
        (bytes[2 * i].toInt() and 0xFF) or ((bytes[2 * i + 1].toInt() and 0xFF) shl 8)

    /** bytes little-endian dos logits uint16 → float. */
    fun logitsParaFloat(bytes: ByteArray): FloatArray {
        require(bytes.size % 2 == 0) { "logits com tamanho ímpar: ${bytes.size}" }
        return FloatArray(bytes.size / 2) { i -> codigoParaLogit(codigoEm(bytes, i)) }
    }

    /** Máscara zerada: todas as posições valem −100,0. */
    fun novaMascara(): ByteArray = ByteArray(TAMANHO_MASCARA * 2)

    /**
     * Um passo da máscara do app oficial: com [posicao] tokens já consumidos, a
     * coluna `199 − posicao` passa a valer 0,0. As anteriores continuam válidas —
     * o buffer é persistente durante a janela.
     */
    fun marcarMascara(mascara: ByteArray, posicao: Int) {
        require(mascara.size == TAMANHO_MASCARA * 2) { "máscara com ${mascara.size} bytes" }
        require(posicao in 0 until TAMANHO_MASCARA) { "posição $posicao fora da máscara" }
        val i = (TAMANHO_MASCARA - 1 - posicao) * 2
        mascara[i] = (MASCARA_VALIDO and 0xFF).toByte()
        mascara[i + 1] = ((MASCARA_VALIDO ushr 8) and 0xFF).toByte()
    }

    /**
     * A próxima posição ainda cabe na janela HTP?
     *
     * [posicao] é quantos tokens a janela já consumiu no cache self. Com
     * `199` o próximo passo gravaria a 200ª posição em um tensor de 199 —
     * a janela inteira volta para a CPU, como na R114.
     */
    fun cabeNaJanela(posicao: Int): Boolean = posicao < POSICOES_CACHE_SELF

    /** Números de bytes divergentes entre dois blocos (prova de identidade). */
    fun divergencias(a: ByteArray, b: ByteArray): Int {
        if (a.size != b.size) return maxOf(a.size, b.size)
        var n = 0
        for (i in a.indices) if (a[i] != b[i]) n++
        return n
    }
}
