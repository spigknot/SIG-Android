package br.gov.sp.pcsp.launcher

/**
 * Backend de inferência ROTEIRIZADO para os testes (só test sourceset).
 *
 * Não simula ONNX Runtime nem finge inferência: devolve logits construídos a
 * partir de uma lista de tokens desejada, para exercitar o laço de geração, o
 * corte de janelas e o terminal. A prova de fidelidade numérica é outra
 * (`WhisperOrtFrontendTest` + referência do host), nunca esta.
 */
class WhisperOrtFakeBackend(
    private val scripts: List<List<Int>>,
    private val vocabSize: Int = 51_866,
    /** Token de controle com logit altíssimo, para provar a supressão. */
    private val controlSpike: Int? = null,
    /** Quantas chamadas de `encode` falham antes de passar (0 = nunca). */
    private var failEncodeTimes: Int = 0,
) : WhisperOrtBackend {

    /** Quantas vezes `encode` foi chamado (uma por janela). */
    var encodeCalls: Int = 0
        private set

    /** `true` depois de [close]. */
    var closed: Boolean = false
        private set

    /** Quantas vezes [reopen] foi chamado (recuperação). */
    var reopenCalls: Int = 0
        private set

    private var window = -1
    private var index = 0

    override fun encode(logMel: FloatArray): FloatArray {
        require(logMel.size == WhisperOrtFrontend.N_MELS * WhisperOrtFrontend.N_FRAMES) {
            "log-mel inesperado: ${logMel.size}"
        }
        encodeCalls++
        if (encodeCalls <= failEncodeTimes) throw WhisperOrtRuntimeFault("ORT_FAIL", "falha simulada do encoder")
        return FloatArray(1_500 * 1_280)
    }

    override fun start(hidden: FloatArray, prompt: IntArray): FloatArray {
        require(hidden.size == 1_500 * 1_280) { "hidden inesperado: ${hidden.size}" }
        require(prompt.size >= 3) { "prefixo curto: ${prompt.size}" }
        window++
        index = 0
        return logitsFor(index)
    }

    override fun step(token: Int): FloatArray {
        index++
        return logitsFor(index)
    }

    override fun close() {
        closed = true
    }

    /** Simula reabertura bem-sucedida das sessões. */
    override fun reopen(): Boolean {
        reopenCalls++
        return true
    }

    private fun logitsFor(position: Int): FloatArray {
        val logits = FloatArray(vocabSize) { -1f }
        val script = scripts.getOrNull(window) ?: emptyList()
        val target = script.getOrNull(position) ?: WhisperOrtPrompt.END_OF_TEXT
        logits[target] = 5f
        controlSpike?.let { logits[it] = 9f }
        return logits
    }
}
