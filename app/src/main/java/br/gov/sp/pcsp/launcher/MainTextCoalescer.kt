package br.gov.sp.pcsp.launcher

/**
 * R9/B2 — publicador COALESCIDO de texto na main.
 *
 * Motivo medido (rodada 9, fase B host — `analysis/b1-stall-audit.md`):
 * na bateria R8-H o ÚNICO snapshot de `main_stall` em que a main não está no
 * renderer é `android.graphics.Paint.nGetCharArrayBounds` (423 ms) — custo de
 * MEDIDA/LAYOUT de um bloco de texto grande submetido pela própria app. O
 * terminal (`updateTerminalText`) e o log global (`updateGlobalLogText`)
 * publicam `setText(<histórico inteiro>)` a cada linha anexada: layout O(n) por
 * linha e O(n²) por operação, com até 1000–1200 linhas no buffer.
 *
 * Este publicador NÃO altera o conteúdo nem descarta linha alguma: ele só
 * limita a FREQUÊNCIA com que o texto completo é entregue à View
 * (`minIntervalMs`) e evita republicar texto idêntico ao último publicado.
 * O último texto sempre vence (last-wins) e [flush] publica imediatamente o
 * pendente — estados terminais e teardown não perdem o conteúdo final.
 *
 * Nada aqui toca o conteúdo do texto: continua sendo a main quem escreve na
 * View (nenhuma View/setText vai para worker).
 */
class MainTextCoalescer(
    private val minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS,
    /** Relógio monotônico injetável (testes JVM). */
    private val now: () -> Long = { System.currentTimeMillis() },
    /** Agenda uma publicação futura (na main, via View.postDelayed). */
    private val postDelayed: (delayMs: Long, action: Runnable) -> Unit = { _, _ -> },
    /** Escrita efetiva na View (sempre na main). */
    private val publish: (String) -> Unit,
) {
    companion object {
        /**
         * Intervalo mínimo entre duas publicações completas. Pequeno o
         * suficiente para não atrasar a percepção do usuário nem o esperado
         * terminal do lab, e grande o suficiente para colapsar as rajadas de
         * linhas que ocorrem no fim de janela (commit/status).
         */
        const val DEFAULT_MIN_INTERVAL_MS = 250L
    }

    private var pending: (() -> String)? = null
    private var lastPublished: String? = null
    private var lastPublishAt: Long? = null
    private var armed = false

    /** Nº de publicações efetivas (métrica de teste/observabilidade). */
    var publishCount: Int = 0
        private set

    /** Última publicação efetiva (idêntica a `null` enquanto nada saiu). */
    val lastText: String? get() = lastPublished

    val hasPending: Boolean get() = pending != null

    private val timer = Runnable { onTimer() }

    /** Pede a publicação de [text]; chamadas em rajada colapsam em uma só. */
    fun request(text: String) {
        request { text }
    }

    /**
     * Pede a publicação do texto produzido por [provider] NA HORA de publicar
     * (evita reconstruir/copiar o histórico inteiro a cada chamada).
     */
    fun request(provider: () -> String) {
        pending = provider
        if (armed) return
        val at = lastPublishAt
        val wait = if (at == null) 0L else (minIntervalMs - (now() - at)).coerceAtLeast(0L)
        if (wait <= 0L) {
            emitNow()
        } else {
            armed = true
            postDelayed(wait, timer)
        }
    }

    /** Publica já o pendente (fim de job/teardown). Não perde o último texto. */
    fun flush() {
        armed = false
        emitNow()
    }

    private fun onTimer() {
        armed = false
        emitNow()
    }

    private fun emitNow() {
        val provider = pending ?: return
        pending = null
        val text = provider()
        if (text == lastPublished) return
        lastPublished = text
        lastPublishAt = now()
        publishCount++
        publish(text)
    }
}
