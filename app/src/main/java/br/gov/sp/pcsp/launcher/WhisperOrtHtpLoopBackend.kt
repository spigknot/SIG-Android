package br.gov.sp.pcsp.launcher

/**
 * R115 §T02 — ROTA HTP DE PONTA A PONTA do produto (desenho **Q′** da R114).
 *
 * O que muda em relação à rota da R100 (encoder HTP + decoders CPU):
 * 1. o **encoder** EPContext entrega os bytes int16 direto ao **projetor**, sem
 *    passar por float — é a otimização que a R114 mediu em 20,6 ms de projetor
 *    e eliminou os 23,7 ms da volta float → códigos;
 * 2. o **projetor** devolve as 8 K/V cruzadas da janela;
 * 3. o **decoder O3** usa o cache fixo de 199 posições (máscara de 200) e recebe
 *    o prompt **token a token**;
 * 4. a realimentação é **direta**: as saídas `k/v_cache_self_i_out` de um passo
 *    entram como `k/v_cache_self_i_in` do passo seguinte, e o resultado anterior
 *    só fecha depois do `run` ([WhisperOrtFeedbackDireto]);
 * 5. os logits são desquantizados pelo contrato ([WhisperOrtHtpContrato]) e
 *    quem escolhe o token continua sendo a `choose` do produto.
 *
 * **Volta para a CPU (T02.2/T02.5).** Quando a próxima posição não cabe mais no
 * cache self — ou quando um passo HTP falha no meio da janela — a janela é
 * refeita INTEIRA no decoder CPU INT8 do produto e o parcial da HTP é
 * descartado. Os decoders CPU são preguiçosos: só carregam na primeira volta,
 * então a rota HTP pura não paga os ~1,2 GB deles (R114: PSS ~270 MB).
 *
 * Falha de encoder/projetor/abertura do decoder **não** vira volta para a CPU
 * aqui: sai como [WhisperOrtHtpFalha] e o dono do pedido troca a rota e
 * transcreve o mesmo pedido na CPU, como na R100.
 */
internal interface WhisperOrtHtpProjetorSessao : AutoCloseable {
    /** Recebe os códigos int16 da janela e devolve as 8 K/V cruzadas. */
    fun rodar(codigos: ByteArray): Map<String, ByteArray>
}

/** Decoder HTP de um passo; o estado da janela (máscara, caches) mora na sessão. */
internal interface WhisperOrtHtpDecoderSessao : AutoCloseable {
    /** Abre a janela: K/V cruzadas do projetor, caches self zerados, máscara vazia. */
    fun abrir(cruzadas: Map<String, ByteArray>)

    /** Um passo na posição [posicao] (0-based); devolve os logits uint16 crus. */
    fun passo(token: Int, posicao: Int): ByteArray

    /** `direto`/`copia` — diagnóstico da realimentação. */
    val modoRealimentacao: String get() = WhisperOrtFeedbackDireto.DIRETO

    /** Primeira recusa da realimentação direta, se houve. */
    val erroRealimentacao: String? get() = null
}

internal class WhisperOrtHtpLoopBackend(
    private val encoder: WhisperOrtHtpSessao,
    private val projetor: WhisperOrtHtpProjetorSessao,
    private val decoder: WhisperOrtHtpDecoderSessao,
    private val decodersCpu: () -> WhisperOrtBackend,
    /** Limite artificial de passos HTP por janela (teste da volta para a CPU). */
    private val limiteHtp: Int = WhisperOrtHtpContrato.POSICOES_CACHE_SELF,
    private val relogio: () -> Long = { System.nanoTime() },
) : WhisperOrtBackend {

    override val rota: String get() = WhisperOrtHtpAdmissao.ROTA_HTP

    /** O encoder do pacote é o turbo de 128 bins mel; não depende dos decoders. */
    override val melBinsDetectados: Int get() = WhisperOrtFrontend.N_MELS

    private val decodersCpuLazy = lazy(decodersCpu)

    private var codigos: ByteArray? = null
    private var hiddenJanela: FloatArray? = null
    private var promptJanela: IntArray? = null
    private val tokensGerados = ArrayList<Int>(256)
    private var posicao = 0
    private var modoCpu = false

    var janelas: Int = 0
        private set
    var projecoes: Int = 0
        private set
    var tokensPrompt: Int = 0
        private set
    var passosHtp: Int = 0
        private set
    var voltasCpu: Int = 0
        private set
    var passosCpu: Int = 0
        private set
    var encodes: Int = 0
        private set
    var nsEncoder: Long = 0L
        private set
    var nsProjetor: Long = 0L
        private set
    var nsPassos: Long = 0L
        private set
    var nsLogits: Long = 0L
        private set

    /** Último motivo de volta para a CPU (`limite` ou `falha:...`). */
    var motivoVoltaCpu: String? = null
        private set

    fun decodersCpuCarregados(): Boolean = decodersCpuLazy.isInitialized()

    /** `direto`/`copia` — diagnóstico da realimentação do decoder HTP. */
    fun modoRealimentacao(): String = decoder.modoRealimentacao

    /** Primeira recusa da realimentação direta, se houve. */
    fun erroRealimentacao(): String? = decoder.erroRealimentacao

    /** Bytes int16 que alimentaram o projetor da última janela. */
    fun codigosDaJanela(): ByteArray? = codigos

    fun metricas(): String = buildString {
        append("janelas=$janelas projecoes=$projecoes tokensPrompt=$tokensPrompt ")
        append("passosHtp=$passosHtp voltasCpu=$voltasCpu passosCpu=$passosCpu ")
        append("modoFeedback=${decoder.modoRealimentacao} ")
        append("nsEncoder=$nsEncoder nsProjetor=$nsProjetor nsPassos=$nsPassos nsLogits=$nsLogits")
    }

    override fun encode(logMel: FloatArray): FloatArray {
        val inicio = relogio()
        val entrada = WhisperOrtHtpConversao.paraDtype(logMel, WhisperOrtHtpConversao.DTYPE_I16)
        val saida = try {
            encoder.rodar(entrada)
        } catch (failure: Throwable) {
            throw WhisperOrtHtpFalha("encoder: ${failure.javaClass.simpleName}: ${failure.message}", failure)
        }
        // T02.1: os códigos que o projetor consome são ESTES bytes, sem float.
        codigos = saida
        val hidden = try {
            WhisperOrtHtpConversao.paraFloat(saida, WhisperOrtHtpConversao.DTYPE_I16)
        } catch (failure: Throwable) {
            throw WhisperOrtHtpFalha("saida do encoder: ${failure.javaClass.simpleName}: ${failure.message}", failure)
        }
        encodes += 1
        nsEncoder += relogio() - inicio
        return hidden
    }

    override fun start(hidden: FloatArray, prompt: IntArray): FloatArray {
        janelas += 1
        hiddenJanela = hidden
        promptJanela = prompt.copyOf()
        tokensGerados.clear()
        modoCpu = false
        motivoVoltaCpu = null
        posicao = 0

        val bytes = codigos ?: WhisperOrtHtpContrato.ocultoParaBytes(hidden)
        val inicio = relogio()
        val cruzadas = try {
            projetor.rodar(bytes)
        } catch (failure: Throwable) {
            throw WhisperOrtHtpFalha("projetor: ${failure.javaClass.simpleName}: ${failure.message}", failure)
        }
        projecoes += 1
        nsProjetor += relogio() - inicio
        check(cruzadas.size == 8) { "projetor devolveu ${cruzadas.size} tensores (esperado 8)" }
        try {
            decoder.abrir(cruzadas)
        } catch (failure: Throwable) {
            throw WhisperOrtHtpFalha(
                "decoder: abertura: ${failure.javaClass.simpleName}: ${failure.message}", failure,
            )
        }
        var logits = FloatArray(0)
        for (token in prompt) logits = passoHtp(token, prompt = true)
        return logits
    }

    override fun step(token: Int): FloatArray {
        if (modoCpu) {
            tokensGerados.add(token)
            passosCpu += 1
            return decodersCpuLazy.value.step(token)
        }
        if (!WhisperOrtHtpContrato.cabeNaJanela(posicao) || posicao >= limiteHtp) {
            motivoVoltaCpu = "limite:$posicao"
            return voltarParaCpu(token)
        }
        // T02.5: falha da HTP no meio da janela não perde o pedido — a janela é
        // refeita inteira na CPU, com o parcial da HTP descartado.
        val logits = try {
            passoHtp(token, prompt = false)
        } catch (falha: WhisperOrtHtpFalha) {
            motivoVoltaCpu = "falha:$posicao:${falha.message}"
            return voltarParaCpu(token)
        }
        tokensGerados.add(token)
        return logits
    }

    /** Um passo HTP; qualquer falha sai tipada e quem decide a volta é o chamador. */
    private fun passoHtp(token: Int, prompt: Boolean): FloatArray {
        val inicio = relogio()
        val bytes = try {
            decoder.passo(token, posicao)
        } catch (failure: Throwable) {
            throw WhisperOrtHtpFalha(
                "decoder: passo $posicao: ${failure.javaClass.simpleName}: ${failure.message}", failure,
            )
        }
        nsPassos += relogio() - inicio
        posicao += 1
        if (prompt) tokensPrompt += 1 else passosHtp += 1
        val inicioLogits = relogio()
        val logits = try {
            WhisperOrtHtpContrato.logitsParaFloat(bytes)
        } catch (failure: Throwable) {
            throw WhisperOrtHtpFalha("logits: ${failure.javaClass.simpleName}: ${failure.message}", failure)
        }
        nsLogits += relogio() - inicioLogits
        if (logits.size != VOCABULARIO) {
            throw WhisperOrtHtpFalha("logits com ${logits.size} valores (esperado $VOCABULARIO)")
        }
        return logits
    }

    /**
     * Volta para a CPU: refaz a janela inteira no decoder INT8 do produto e
     * descarta o parcial da HTP. É a ÚNICA porta que carrega os decoders CPU.
     *
     * [token] ainda NÃO está em [tokensGerados]: a janela é replayada do prompt
     * até o último token já produzido e este entra como o passo seguinte.
     */
    private fun voltarParaCpu(token: Int): FloatArray {
        modoCpu = true
        voltasCpu += 1
        val hidden = requireNotNull(hiddenJanela) { "janela sem hidden" }
        val prompt = requireNotNull(promptJanela) { "janela sem prompt" }
        val cpu = decodersCpuLazy.value
        cpu.start(hidden, prompt)
        for (anterior in tokensGerados) cpu.step(anterior)
        tokensGerados.add(token)
        passosCpu += 1
        return cpu.step(token)
    }

    override fun close() {
        runCatching { decoder.close() }
        runCatching { projetor.close() }
        runCatching { encoder.close() }
        if (decodersCpuLazy.isInitialized()) runCatching { decodersCpuLazy.value.close() }
    }

    companion object {
        /** Vocabulário do turbo (logits `[1, 51866, 1, 1]`). */
        const val VOCABULARIO = 51866
    }
}
