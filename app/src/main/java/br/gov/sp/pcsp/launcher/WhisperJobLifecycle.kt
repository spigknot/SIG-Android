package br.gov.sp.pcsp.launcher

import android.content.pm.ServiceInfo

/**
 * R11/D4 — CONTRATO DE VIDA DO JOB: lógica PURA (sem Android), testável na JVM.
 *
 * O defeito que esta fase corrige: o job de transcrição rodava numa `Thread`
 * comum de uma Activity sem nenhum lifecycle de processo; com a tela apagada o
 * processo entra em estado cacheado/`/background` e o congelador (app freezer)
 * do Android 13 (HyperOS/ColorOS no PJA110) para TODAS as threads — inclusive a
 * do worker nativo. Resultado: "Carregando…" congelado para sempre e sessão
 * possivelmente perdida.
 *
 * A correção de produto é executar o job com um **foreground service tipado**
 * (execução iniciada pelo usuário, observável) + wake lock com teto. As decisões
 * que NÃO dependem do framework ficam aqui, para poderem ser provadas por teste:
 *
 *  * [fgsType] — tipo de FGS por rota. Fontes oficiais conferidas (versão de
 *    2026-10-01, `background-work/services/fgs/service-types`):
 *      - "Local file processing" e "Import or export operations" ⇒ `dataSync`
 *        (`FOREGROUND_SERVICE_DATA_SYNC`; pré-requisitos de runtime: NENHUM);
 *      - captura de microfone ⇒ `microphone` (`FOREGROUND_SERVICE_MICROPHONE`;
 *        pré-requisito de runtime: `RECORD_AUDIO` concedido).
 *    `mediaProcessing` foi considerado e descartado: é para "time-consuming
 *    operations on media assets" com teto de 6 h/24 h e sem cobrir a rota em
 *    que o produto só consome um arquivo local já existente.
 *  * [isOwner] — posse por job: geração que não é dona não libera nem cancela.
 *  * [clampLockTimeout] — o wake lock tem TETO por construção (nunca permanente).
 */
object WhisperJobLifecycle {
    /** Rota de arquivo (lote/gravação já convertida): processamento local. */
    const val KIND_FILE = "file"

    /** Rota ao vivo: captura de microfone. */
    const val KIND_LIVE = "live"

    fun initialStatus(kind: String): String =
        if (kind == KIND_LIVE) "Preparando transcrição ao vivo…" else "Preparando transcrição…"

    /** Tipo de FGS declarado no manifesto por rota (ver doc oficial acima). */
    fun fgsType(kind: String): Int =
        if (kind == KIND_LIVE) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC

    /** Teto maximo permitido para FGS dataSync (Android 15 impoe 6h/24h). */
    const val MAX_DATA_SYNC_TIMEOUT_MS = 6 * 60 * 60 * 1000L

    /** Retorna se o timeout respeita as restricoes da plataforma para o tipo de FGS. */
    fun isValidTimeout(kind: String, timeoutMs: Long): Boolean =
        timeoutMs > 0L && (kind != KIND_FILE || timeoutMs <= MAX_DATA_SYNC_TIMEOUT_MS)

    /** Dono por job: só a geração DONA corrente libera/cancela (sem callback
     * stale e sem duas sessões ao retornar — um `finish` atrasado de um job
     * antigo nunca derruba o lock da sessão nova). */
    fun isOwner(owner: Long, requesting: Long): Boolean = owner > 0L && owner == requesting

    // ------------------------------------------------------- R13/D3 FGS START

    /** Resultado da subida do foreground (decisão pura, testável). */
    enum class FgsStart { TYPED, DEGRADED_FILE, FAILED }

    /**
     * R13/D3 — microphone NUNCA degrada para dataSync: o tipo `microphone`
     * carrega o requisito de captura (`RECORD_AUDIO`); cair para `dataSync`
     * contornaria esse requisito. Só a rota de arquivo admite degradação
     * (processamento local). Falha total = FAILED honesto (o serviço deve
     * registrar e encerrar-se; o job segue no processo DESPROTEGIDO, como
     * antes do D4 — nunca "como se estivesse protegido").
     */
    fun startOutcome(kind: String, typedOk: Boolean, degradedOk: Boolean): FgsStart = when {
        typedOk -> FgsStart.TYPED
        kind == KIND_FILE && degradedOk -> FgsStart.DEGRADED_FILE
        else -> FgsStart.FAILED
    }

    // ---------------------------------------------------- R13/D3 WORKER LEASE

    /**
     * R13/D3 — concessão do wake lock com relógio injetável (pura, testável).
     *
     * Política do worker: o lock tem prazo (`acquire(timeout)`); renovação
     * SOMENTE com progresso observado (status novo) e no máximo
     * [MAX_RENEWALS] vezes. Expirado sem terminal = encerrar/cancelar, nunca
     * renovar em silêncio e nunca operar desprotegido sem registro.
     * `nowElapsedMs` usa a mesma base de `SystemClock.elapsedRealtime()`.
     */
    data class WorkerLease(
        val acquiredElapsedMs: Long,
        val timeoutMs: Long,
        val renewals: Int = 0,
    ) {
        companion object {
            /** Aviso prévio de expiração. */
            const val RENEW_WARN_MS = 5 * 60 * 1000L

            /** Renovações com progresso antes do encerramento obrigatório. */
            const val MAX_RENEWALS = 2
        }

        fun remainingMs(nowElapsedMs: Long): Long =
            (acquiredElapsedMs + timeoutMs) - nowElapsedMs

        fun expired(nowElapsedMs: Long): Boolean = remainingMs(nowElapsedMs) <= 0L

        fun expiringSoon(nowElapsedMs: Long): Boolean =
            !expired(nowElapsedMs) && remainingMs(nowElapsedMs) <= RENEW_WARN_MS

        /**
         * R14/B1 — a renovação só é devida PERTO do prazo (>= 75 % do timeout
         * já consumido). Sem isto, dois `update` de status no início do job
         * gastavam as duas renovações e o job ficava sem orçamento quando o
         * prazo realmente chegava (defeito reproduzido na revisão da R13).
         */
        fun renewalDue(nowElapsedMs: Long): Boolean {
            if (expired(nowElapsedMs)) return false
            val elapsed = nowElapsedMs - acquiredElapsedMs
            return elapsed >= (timeoutMs * 3L) / 4L
        }

        /**
         * Renova a concessão a partir de `nowElapsedMs`. Exige progresso
         * observado E que a renovação esteja devida ([renewalDue]); concede no
         * máximo [MAX_RENEWALS] e nunca renova concessão já expirada
         * (expirada ⇒ encerrar/cancelar). Devolve o novo lease ou `null`.
         */
        fun renew(nowElapsedMs: Long, progressObserved: Boolean): WorkerLease? {
            if (!progressObserved) return null
            if (renewals >= MAX_RENEWALS) return null
            if (expired(nowElapsedMs)) return null
            if (!renewalDue(nowElapsedMs)) return null
            return copy(acquiredElapsedMs = nowElapsedMs, renewals = renewals + 1)
        }
    }

    /** R16/B3 — o que fazer quando o prazo/orçamento acaba. */
    enum class JobEndAction { RELEASE, QUARANTINE }

    /**
     * R16/B3 — só o TÉRMINO DEMONSTRADO do worker autoriza liberar a proteção.
     *
     * Repros do especialista: OWNER_TIMEOUT e DRAIN_BUDGET chamavam stopJob com
     * `worker_ack=false`, liberando lock e declarando terminal enquanto a
     * computação podia continuar. Aqui a decisão é explícita e testável.
     */
    fun endAction(workerFinished: Boolean): JobEndAction =
        if (workerFinished) JobEndAction.RELEASE else JobEndAction.QUARANTINE

    /**
     * R15/B2 — a renovação é PERMITIDA?
     *
     * Regra única consultada por TODOS os caminhos do serviço:
     *  - job em cancelamento/dreno NUNCA renova (o prazo já foi decidido);
     *  - exige progresso OBSERVADO (não texto de UI repetido, não supressão);
     *  - respeita a janela de 75 % e o orçamento de [WorkerLease.MAX_RENEWALS];
     *  - nunca renova concessão já expirada.
     *
     * Pura de propósito: a célula física b23-destroy mostrou o caminho de status
     * passando `progressObserved = true` fixo — a política tem de ser a mesma
     * que os testes cobrem.
     */
    fun shouldRenew(
        cancelling: Boolean,
        lease: WorkerLease,
        nowElapsedMs: Long,
        progressObserved: Boolean,
    ): Boolean {
        if (cancelling) return false
        return lease.renew(nowElapsedMs, progressObserved) != null
    }

    /**
     * R15/B1 — margem ANTES do prazo físico em que o monitor decide.
     *
     * Precisa ser pequena o suficiente para o instante de decisão ainda cair
     * dentro da janela de renovação (>= 75 % do prazo consumido) — por isso o
     * teto de [timeoutMs]/20 — e grande o suficiente para caber o pedido de
     * cancelamento antes de o framework soltar o lock.
     */
    fun leaseCheckMarginMs(timeoutMs: Long): Long =
        minOf(5_000L, maxOf(500L, timeoutMs / 20L))

    /** Teto do wake lock: pedido inválido/ausente vira o teto máximo; o lock
     * nunca fica permanente (release explícito + teto do próprio framework). */
    fun clampLockTimeout(requestedMs: Long, minMs: Long, maxMs: Long): Long = when {
        requestedMs <= 0L -> maxMs
        requestedMs < minMs -> minMs
        requestedMs > maxMs -> maxMs
        else -> requestedMs
    }

    /** Texto do `contentText` da notificação (nunca vazio). */
    fun notificationText(status: String): String = status.trim().ifEmpty { "Transcrevendo…" }

    fun notificationTitle(kind: String): String =
        if (kind == KIND_LIVE) "Transcrição ao vivo em andamento" else "Transcrição em andamento"

    // ------------------------------------------------- R55/B01 ADMISSÃO ÚNICA

    /**
     * R55/B01 — PORTA DE ADMISSÃO do job, a MESMA para os dois lados reais.
     *
     * A reserva é decidida em [WorkerReservation]; a LEITURA da decisão ficou
     * aqui para que Activity (`startJob` pré-Serviço e worker) e Serviço
     * (`onStartCommand`→`beginJob`) não possam divergir: antes da R55 o
     * `beginJob` tratava `REFUSED_OTHER_OWNER` e a Activity tratava tudo o mais
     * no `else` — uma decisão nova (geração encerrada) atravessava o `else` e o
     * job começava a computar SEM reserva.
     */
    data class Admission(val start: Boolean, val reason: String)

    fun admission(decision: WorkerReservation.Decision): Admission = when (decision) {
        WorkerReservation.Decision.RESERVED ->
            Admission(true, "reservado")
        WorkerReservation.Decision.ALREADY_HELD ->
            Admission(true, "mesma_geracao_ja_dona")
        WorkerReservation.Decision.REFUSED_OTHER_OWNER ->
            Admission(false, "outra_geracao_detem_a_reserva")
        WorkerReservation.Decision.REFUSED_STALE_GENERATION ->
            Admission(false, "geracao_ja_encerrada")
    }

    // --------------------------------------------------- R55/B01/B03 TERMINAL

    /**
     * R55/B01 — o que um terminal PROVA. Só um ack provado libera; nome de
     * evento/status de UI nunca vira ack (R17/B1: "ACK não inventado").
     */
    enum class TerminalKind {
        /** O worker declarou o próprio fim (SUCCESS/CANCELLED/ERROR). */
        WORKER_ACK,

        /** O dono declara o fim DEPOIS de observar o worker encerrado. */
        OWNER_DECLARED,

        /** String desconhecida: não é ack e não pode liberar nem encerrar nada. */
        UNKNOWN,
    }

    fun terminalKind(terminal: String): TerminalKind = when (terminal) {
        "SUCCESS", "CANCELLED", "ERROR" -> TerminalKind.WORKER_ACK
        "LIVE_TERMINAL" -> TerminalKind.OWNER_DECLARED
        else -> TerminalKind.UNKNOWN
    }

    /**
     * R55/B01 — terminal SEM Serviço instanciado.
     *
     * É a mesma regra do caminho com Serviço — `JobProtection.canRelease` com
     * `fromWorker = true` — aplicada à evidência que existe aqui: o dono só
     * chama `finishJob` quando a execução dele terminou, e a liberação continua
     * sendo SÓ do dono (`WorkerReservationStore.release` confere a geração).
     * O que NÃO se faz é liberar por nome de evento desconhecido.
     */
    fun releasesReservedOnProvenTerminal(kind: TerminalKind, heldByGeneration: Boolean): Boolean =
        kind != TerminalKind.UNKNOWN && heldByGeneration

    /**
     * R55/B01/B03 — resultado de um terminal aplicado SEM UI e SEM Serviço.
     *
     * @property kind o que o terminal provou.
     * @property released a reserva desta geração foi liberada agora.
     * @property recorded o terminal virou ESTADO (e não só uma linha de log).
     * @property event nome do evento a publicar — `job_terminal_recusado` para
     *   terminal não provado, para o consumidor NUNCA ler um ACK inventado.
     * @property payloadJson linha JSON correlacionada (gen/terminal/dono/origem).
     */
    data class TerminalResult(
        val kind: TerminalKind,
        val released: Boolean,
        val recorded: Boolean,
        val event: String,
        val payloadJson: String,
    )

    /**
     * R55/B01/B03 — TERMINAL DO PRODUTOR sem UI/Serviço.
     *
     * Função ÚNICA chamada pelo `finishJob` quando não há Serviço dono da
     * geração. Antes dela, o caminho sem Serviço só escrevia log: não havia
     * estado, e um terminal de geração alheia era indistinguível de ACK.
     *
     * Idempotente por construção: `release` só devolve `true` para o dono atual;
     * repetir o mesmo terminal devolve `released = false`/`recorded = false` e
     * NÃO mexe no estado do sucessor.
     */
    fun applyTerminalWithoutService(
        generation: Long,
        terminal: String,
        heldBy: (Long) -> Boolean,
        release: (Long, String) -> Boolean,
    ): TerminalResult {
        val kind = terminalKind(terminal)
        if (!releasesReservedOnProvenTerminal(kind, heldBy(generation))) {
            return TerminalResult(
                kind = kind,
                released = false,
                recorded = false,
                event = "job_terminal_recusado",
                payloadJson = terminalEventJson(
                    generation, terminal, generation, false, "sem_servico_recusado"
                ),
            )
        }
        val released = release(generation, terminal)
        return TerminalResult(
            kind = kind,
            released = released,
            recorded = released,
            event = "job_fgs_terminal",
            payloadJson = terminalEventJson(
                generation, terminal, generation, released, "sem_servico"
            ),
        )
    }

    /**
     * R55/B03 — linha JSON do terminal correlacionado, para o produtor de
     * estado sem UI/Serviço. Campos explícitos (geração, terminal, dono,
     * liberou, origem): o consumidor não infere identidade por nome de evento.
     */
    fun terminalEventJson(
        generation: Long,
        terminal: String,
        owner: Long,
        released: Boolean,
        origem: String,
    ): String {
        fun esc(v: String): String =
            v.replace("\\", "\\\\").replace("\"", "\\\"")
        return "{\"gen\":$generation,\"terminal\":\"${esc(terminal)}\"," +
            "\"owner\":$owner,\"released\":$released,\"origem\":\"${esc(origem)}\"}"
    }
}
