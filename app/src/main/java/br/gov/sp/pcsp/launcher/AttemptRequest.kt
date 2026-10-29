package br.gov.sp.pcsp.launcher

/**
 * R25/REQ — PEDIDO DA TENTATIVA, construído UMA vez e enviado NA chamada.
 *
 * Por que existe: a R24 publicava uma fotografia numa tabela lateral e o nativo
 * a consumia com `take(owner, 0)` — wildcard de attempt, sem identidade — além
 * de mandar a política por `setenv` do processo. Este objeto é a única fonte:
 * o mesmo valor alimenta o Intent/atestação do lab e a chamada
 * [WhisperNative.transcribeOwnedReq]; nada é relido depois.
 *
 * Contrato do fio (`toWire`): pares `chave=valor` separados por `;`, validados
 * de novo no nativo (`sig_attempt_request.h`). `null` significa DEFAULT
 * explícito — e DEFAULT limpa o domínio do job, não herda valor anterior.
 *
 * Produção nunca constrói isto: `WhisperLabSeams.attemptRequest` devolve null
 * fora do flavor de bancada.
 */
data class AttemptRequest(
    val token: String,
    val owner: Long,
    val gen: Long,
    val job: Long,
    val attempt: Long,
    val ctxMode: CtxMode = CtxMode.DEFAULT,
    val ctxLiteral: Int = 0,
    val winMode: WinMode = WinMode.DEFAULT,
    val winSamples: Long = 0L,
    val submitMb: Int? = null,
    val serial: Int? = null,
    val k: Int? = null,
    val capture: Capture? = null,
    val producer: String = "",
    val config: String = ""
) {
    enum class CtxMode { DEFAULT, LITERAL }

    enum class WinMode { DEFAULT, WINDOW }

    /** Configuração de captura da tentativa (modo/kinds/destino/seleção). */
    data class Capture(
        val kinds: String,
        val dir: String,
        val tag: String,
        val first: Int = 0,
        val step: Int = 0,
        val max: Int = 0,
        val steps: Int = 1
    )

    /**
     * Recusa LOCAL tipada (null = pode enviar). Espelha as regras do nativo para
     * o erro aparecer antes da JNI; o nativo valida de novo e recusa sozinho.
     */
    /**
     * R27/REQ — a rota LEGADA só é aceitável quando o pedido é equivalente aos
     * defaults de produção. Qualquer override experimental (submit/K/serial/
     * captura/ctx/win explícitos) exige a entrada nova: numa lib sem ela a
     * resposta correta é UNSUPPORTED, não inferir na configuração antiga.
     */
    fun isProdDefault(): Boolean =
        ctxMode == CtxMode.DEFAULT && ctxLiteral == 0 &&
            winMode == WinMode.DEFAULT && winSamples == 0L &&
            submitMb == null && serial == null && k == null && capture == null

    fun validate(): String? {
        if (token.isBlank()) return "token vazio"
        if (owner <= 0L || gen <= 0L || job <= 0L || attempt <= 0L) return "identidade invalida"
        if (ctxMode == CtxMode.LITERAL && (ctxLiteral <= 0 || ctxLiteral > 1500)) {
            return "ctx literal fora de [1,1500]"
        }
        if (winMode == WinMode.WINDOW && winSamples <= 0L) return "janela sem amostras"
        submitMb?.let { if (it < 0 || it > 4096) return "submit fora de [0,4096]" }
        serial?.let { if (it != 0 && it != 1) return "serial fora de {0,1}" }
        k?.let { if (it < 0 || it > 64) return "k fora de [0,64]" }
        capture?.let { c ->
            if (c.kinds.isBlank()) return "captura sem kinds"
            if (c.dir.isBlank() || c.tag.isBlank()) return "captura sem dir/tag"
            if (c.first < 0 || c.step < 0 || c.max < 0 || c.steps < 1) return "selecao de captura invalida"
        }
        for ((nome, valor) in listOf(
            "token" to token, "producer" to producer, "config" to config,
            "cap_dir" to (capture?.dir ?: ""), "cap_tag" to (capture?.tag ?: ""),
            "cap_kinds" to (capture?.kinds ?: "")
        )) {
            if (valor.contains(';') || valor.contains('=')) return "$nome com separador do fio"
        }
        return null
    }

    /** Serializa para o fio aceito por `sig_attempt_request.h` (v=1). */
    fun toWire(): String {
        val sb = StringBuilder("v=1")
        sb.append(";token=").append(token)
        sb.append(";owner=").append(owner)
        sb.append(";gen=").append(gen)
        sb.append(";job=").append(job)
        sb.append(";attempt=").append(attempt)
        sb.append(";ctx=").append(if (ctxMode == CtxMode.LITERAL) "literal" else "default")
        if (ctxMode == CtxMode.LITERAL) sb.append(";ctx_value=").append(ctxLiteral)
        sb.append(";win=").append(if (winMode == WinMode.WINDOW) "window" else "default")
        if (winMode == WinMode.WINDOW) sb.append(";win_samples=").append(winSamples)
        sb.append(";submit=").append(submitMb?.toString() ?: "default")
        sb.append(";serial=").append(serial?.toString() ?: "default")
        sb.append(";k=").append(k?.toString() ?: "default")
        if (capture == null) {
            sb.append(";cap=off")
        } else {
            sb.append(";cap=on")
            sb.append(";cap_kinds=").append(capture.kinds)
            sb.append(";cap_dir=").append(capture.dir)
            sb.append(";cap_tag=").append(capture.tag)
            sb.append(";cap_first=").append(capture.first)
            sb.append(";cap_step=").append(capture.step)
            sb.append(";cap_max=").append(capture.max)
            sb.append(";cap_steps=").append(capture.steps)
        }
        if (producer.isNotEmpty()) sb.append(";producer=").append(producer)
        if (config.isNotEmpty()) sb.append(";config=").append(config)
        return sb.toString()
    }
}
