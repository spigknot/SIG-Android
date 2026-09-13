package br.gov.sp.pcsp.launcher

/** Modos de corte da ferramenta Cortar (ordem exibida; SmartCut é o padrão).
 *
 * Port de `CUT_MODE_*` do SIG Windows. O modo só descreve a INTENÇÃO; a decisão
 * final (com motivo) é do despacho em [videoPlan] e das regras de execução —
 * ex.: seleção de área não pode copiar streams e força o Reencode Completo.
 *
 * Não toca Android: regra pura, testável sem aparelho. */
object FfmpegCutModes {

    const val SMART = "SmartCut"
    const val REENCODE = "Reencode Completo"
    const val COPY = "Sem Reencode"

    val MODES = listOf(SMART, REENCODE, COPY)
    val DEFAULT = SMART

    const val HELP = "SmartCut: corte preciso e rápido, mas EXPERIMENTAL — copia os trechos que já " +
        "começam em keyframe e reencoda apenas as bordas até os tempos exatos.\n\n" +
        "Reencode Completo: reencoda todo o trecho — lento e preciso.\n\n" +
        "Sem Reencode: copia os streams sem reencodar — rápido e menos preciso, porque início e fim " +
        "escorregam até o keyframe/pacote disponível."

    /** Margem mínima para valer a pena reencodar a borda e para considerar que
     * há miolo copiável entre dois keyframes (mesmo valor do Windows). */
    const val SMARTCUT_MIN_EDGE = 0.05

    /** Plano do despacho: modo efetivo + motivo quando ele mudou. */
    data class Plan(val mode: String, val reason: String? = null)

    /** Modo "Sem Reencode": cópia pura dos streams. */
    fun isCopy(mode: String): Boolean = mode.startsWith(COPY)

    /** Ferramentas/modos que reencodam vídeo usam o seletor de encoder; o modo
     * de cópia não reencoda nada. */
    fun usesVideoEncoder(mode: String): Boolean = !isCopy(mode)

    /** Modo efetivo do corte de VÍDEO, aplicando as regras que forçam reencode.
     *
     * A seleção de área (crop) recorta pixels, o que cópia de streams não faz —
     * e o miolo copiado do SmartCut também não pode ser recortado. Nos dois
     * casos o corte vira Reencode Completo, com o motivo para o log. */
    fun videoPlan(mode: String, hasCrop: Boolean): Plan = when {
        hasCrop && isCopy(mode) ->
            Plan(REENCODE, "A seleção de área exige reencodar: usando o Reencode Completo.")
        hasCrop && !mode.startsWith(REENCODE) ->
            Plan(REENCODE, "A seleção de área exige reencodar todo o trecho: usando o Reencode Completo.")
        else -> Plan(mode)
    }

    /** Motivo para o SmartCut não poder copiar o miolo (o corte cai no Reencode
     * Completo); `null` quando o caminho rápido está disponível. */
    fun smartCutFallbackReason(
        codecFamily: String?,
        hasInternalKeyframes: Boolean?,
        edgeEncoderAvailable: Boolean
    ): String? = when {
        codecFamily !in setOf("h264", "hevc") ->
            "Codec ${codecFamily ?: "desconhecido"} não permite cópia híbrida."
        !edgeEncoderAvailable ->
            "Nenhum encoder deste aparelho produz o mesmo codec do arquivo (o miolo é copiado)."
        hasInternalKeyframes == false ->
            "Não há keyframes internos suficientes para copiar o trecho central sem perdas."
        else -> null
    }
}
