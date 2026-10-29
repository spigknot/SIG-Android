package br.gov.sp.pcsp.launcher

/**
 * R11/fase E (E1) — PERFIL OpenCL: recusa de combinação medida como insegura.
 *
 * Fato medido (R9 → R11, PJA110 / Adreno 740 / OpenCL 3.0 E031.41.03.36):
 * o backend OpenCL enfileira o grafo do encoder em escala large/turbo
 * (encoder 1500/PAD 1536, ~1.063 nós) e o driver NÃO devolve controle —
 * `adreno_drawctxt_wait`, sessão pendurada e compositor congelado. A CARGA do
 * mesmo modelo é estável: o gatilho é o COMPUTE. Por isso a recusa tem de
 * acontecer ANTES do dispatch.
 *
 * Contrato: a DECISÃO e a recusa são do motor nativo (JNI), que carrega o
 * contexto no backend SEGURO e emite a linha ESTRUTURADA `SIG_OCL_GUARD`
 * (key=value, `evidence=structured_v2`) com `backend_req`/`backend_eff`/
 * `reason`. Este objeto só RELATA (terminal/relatório) e mantém, em Kotlin, um
 * ESPELHO TESTÁVEL do critério — para que a fronteira (quais shapes são
 * seguros) seja verificável em JVM, sem device.
 *
 * Nada é habilitado/desabilitado por aqui: nenhuma heurística de texto decide
 * backend. Sem a linha estruturada, o relato é "não recusado" (nunca inventa
 * recusa).
 */
object OpenClProfileNotice {

    const val GUARD_PREFIX = "SIG_OCL_GUARD"

    /** Limites do critério nativo (mesmos valores de `sig_ocl_guard_reason`). */
    const val UNSAFE_AUDIO_STATE = 1280
    const val UNSAFE_AUDIO_LAYER = 32

    /** Marcador de família/geração medida: só a Adreno A7X foi caracterizada. */
    const val ADRENO_A7X_MARKER = "Adreno(TM) 7"
    const val ADRENO_KERNELS_MARKER = "kernels optimized for Adreno"

    data class Report(
        val requested: String,
        val effective: String,
        val audioState: Int,
        val audioLayer: Int,
        val ftype: Int,
        val device: String,
        val reason: String,
        val flashAttn: Int,
    )

    /**
     * ESPELHO do critério nativo (JVM, sem device): a combinação é recusada
     * quando o backend pedido é OpenCL, o device é da família Adreno A7X (fato
     * lido do log do motor) E a arquitetura do modelo carregado é a classe
     * large/turbo. tiny/base/small/medium permanecem no OpenCL.
     */
    fun isUnsafeProfile(openclRequested: Boolean, adrenoA7X: Boolean, audioState: Int, audioLayer: Int): Boolean =
        openclRequested && adrenoA7X && audioState >= UNSAFE_AUDIO_STATE && audioLayer >= UNSAFE_AUDIO_LAYER

    /** Fatos do device a partir do log de carga (mesmas linhas que o JNI usa). */
    fun adrenoA7XIn(loadLog: String): Boolean =
        loadLog.contains(ADRENO_KERNELS_MARKER) && loadLog.contains(ADRENO_A7X_MARKER)

    /** Lê a última linha `SIG_OCL_GUARD` do log (a mais recente vence). */
    fun parse(loadLog: String): Report? {
        val line = loadLog.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("$GUARD_PREFIX ") }
            .lastOrNull() ?: return null
        val fields = parseKeyValues(line)
        return Report(
            requested = fields["backend_req"] ?: "opencl",
            effective = fields["backend_eff"] ?: "?",
            audioState = fields["arch_audio_state"]?.toIntOrNull() ?: 0,
            audioLayer = fields["arch_audio_layer"]?.toIntOrNull() ?: 0,
            ftype = fields["ftype"]?.toIntOrNull() ?: -1,
            device = fields["device"] ?: "?",
            reason = fields["reason"] ?: "",
            flashAttn = fields["flash_attn"]?.toIntOrNull() ?: 0,
        )
    }

    /**
     * Mensagem curta ao usuário, no MESMO formato pedido/efetivo/motivo. `null`
     * quando não houve recusa nesta carga (nada a reportar).
     */
    fun message(backendKind: Int, loadLog: String): String? {
        val report = parse(loadLog) ?: return null
        val requested = if (backendKind == 2) "OpenCL" else report.requested
        val effective = when (report.effective) {
            "vulkan" -> "GPU Vulkan"
            "cpu" -> "CPU"
            "opencl" -> "OpenCL"
            else -> report.effective
        }
        return "Perfil OpenCL recusado nesta combinação (device ${report.device}): " +
            "pedido=$requested, efetivo=$effective, modelo=${report.audioState}/${report.audioLayer} " +
            "(classe large/turbo), FA=${if (report.flashAttn == 1) "on" else "off"}. " +
            "Motivo: ${report.reason}"
    }

    private fun parseKeyValues(line: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val tokens = Regex("([A-Za-z_][A-Za-z0-9_]*)=(\"[^\"]*\"|[^\\s]+)").findAll(line)
        for (token in tokens) {
            val key = token.groupValues[1]
            val value = token.groupValues[2].removeSurrounding("\"")
            out[key] = value
        }
        return out
    }
}
