package br.gov.sp.pcsp.launcher

/** Preferências e disponibilidade dos seletores de motor e hardware do Whisper. */
internal object WhisperSelectors {
    enum class Hardware(val label: String, val route: String) {
        CPU("CPU", "cpu"),
        HYBRID("NPU→CPU", "htp-cpu"),
        NPU("NPU", "htp");

        companion object {
            fun fromPreference(value: String?) = entries.firstOrNull { it.name == value }
        }
    }

    enum class Choice(val reason: String? = null) {
        READY,
        DOWNLOAD,
        UNSUPPORTED_SOC("não disponível neste aparelho"),
        UNSUPPORTED_MODEL("só no large-v3-turbo"),
    }

    fun useOrt(cpp: Boolean?, legacyOrt: Boolean?): Boolean = cpp?.not() ?: legacyOrt ?: true

    fun flashAttention(useOrt: Boolean, saved: Boolean): Boolean = !useOrt && saved

    fun choice(
        hardware: Hardware,
        admitted: Boolean,
        turbo: Boolean,
        verifiedVersion: Int,
    ): Choice = when {
        hardware == Hardware.CPU -> Choice.READY
        !admitted -> Choice.UNSUPPORTED_SOC
        !turbo -> Choice.UNSUPPORTED_MODEL
        verifiedVersion >= if (hardware == Hardware.NPU) 3 else 2 -> Choice.READY
        else -> Choice.DOWNLOAD
    }

    /**
     * Hardware EFETIVO da tela.
     *
     * R123/T01 — sem escolha salva, o padrão é a melhor rota PRONTA, na ordem
     * `NPU` (v3) → `NPU→CPU` (v2 ou v3) → `CPU`. Antes o padrão era `NPU` e o
     * pacote v2 caía em `CPU`: a 81 usa o encoder na HTP com o v2, e a 82
     * tiraria a NPU de quem ainda está no v2, sem aviso (revisão R122 §3).
     *
     * Com escolha salva, a escolha vale se estiver pronta; senão, `CPU` — como
     * antes, para não surpreender quem escolheu.
     */
    fun effective(
        saved: Hardware?,
        admitted: Boolean,
        turbo: Boolean,
        verifiedVersion: Int,
    ): Hardware {
        if (saved != null) {
            return if (choice(saved, admitted, turbo, verifiedVersion) == Choice.READY) saved
            else Hardware.CPU
        }
        return PADRAO_SEM_ESCOLHA.firstOrNull {
            choice(it, admitted, turbo, verifiedVersion) == Choice.READY
        } ?: Hardware.CPU
    }

    /**
     * Hardware EFETIVO de um JOB, pelo modelo que o job vai rodar.
     *
     * R124/T02 — a tela decidia o hardware com o modelo do BOTÃO
     * (`selectedModel`) enquanto o job rodava o modelo EFETIVO
     * (`modeloEfetivoConsentido ?: modeloParaPedido(...)`). Com `tiny` no botão
     * e o turbo aceito pela oferta, a 81 rodava na NPU e a candidata caía na
     * CPU: regressão que bloqueava a 82 (revisão R123 §3).
     *
     * A regra é [effective] com o "turbo" vindo do modelo efetivo, e vale
     * tanto para o DESPACHO quanto para o rótulo e o menu, que precisam mostrar
     * a mesma decisão que o despacho usaria.
     */
    fun effectiveParaModelo(
        saved: Hardware?,
        admitted: Boolean,
        modeloEfetivo: String?,
        verifiedVersion: Int,
    ): Hardware = effective(saved, admitted, modeloEfetivo == MODELO_TURBO, verifiedVersion)

    /**
     * Modelo efetivo que o DESPACHO usaria para [rotuloDoBotao], sem oferta
     * consentida.
     *
     * R124/T02 — é `modeloParaPedido(botão)` (`resolverDoPedido`) ou, quando
     * ele é nulo (pedido sem pacote ORT, que só roda pela oferta consentida do
     * turbo), o próprio [MODELO_TURBO].
     *
     * Existe porque o rótulo e o menu da tela têm de mostrar o hardware que o
     * despacho usaria, e o modelo EFETIVO não está no rótulo do botão.
     */
    fun modeloEfetivoDoBotao(
        rotuloDoBotao: String?,
        resolverDoPedido: (String?) -> String?,
    ): String? = resolverDoPedido(rotuloDoBotao) ?: MODELO_TURBO

    /** Ordem do padrão sem escolha salva: a melhor rota pronta primeiro. */
    private val PADRAO_SEM_ESCOLHA = listOf(Hardware.NPU, Hardware.HYBRID, Hardware.CPU)

    /**
     * Rótulo do turbo no catálogo ORT. O `WhisperSelectors` é puro (sem
     * Android), por isso repete a constante do catálogo, e o teste garante que
     * as duas não divergem.
     */
    const val MODELO_TURBO = "whisper-large-v3-turbo"
}
