package br.gov.sp.pcsp.launcher

/**
 * R17/B1 — política PURA de proteção do worker (quem pode liberar, quem pode entrar).
 *
 * O defeito da R16, medido pelo especialista: ao esgotar a quarentena, o serviço
 * chamava `stopJob(gen, "TIMEOUT_LEASE_UNCONFIRMED")`, e `stopJob` liberava o
 * wake lock, zerava o owner, removia a notificação e parava o serviço **com o
 * worker possivelmente ainda vivo**. O rótulo mudou; o invariante físico não.
 * O mesmo valia para ausência de forwarder.
 *
 * Regras que esta porta codifica (e que os testes travam):
 *
 *  1. **Só o término PROVADO libera.** `fromWorker` (ack do worker) ou
 *     `workerFinished` já registrado. Timer, timeout, STOP da notificação e
 *     ausência de canal NÃO liberam.
 *  2. **Quarentena esgotada sem ack vira STUCK**, não terminal: mantém lock e
 *     owner, bloqueia admissão, registra o estado — e espera o ack ou o teto
 *     físico do wake lock. Não se inventa terminal para destravar a bancada.
 *  3. **Admissão de job novo respeita worker vivo.** Com owner ativo e worker
 *     já iniciado, um `beginJob` novo é RECUSADO (não substitui o dono). Sem
 *     worker iniciado (falha antes de qualquer trabalho), substituir é correto.
 */
object JobProtection {

    /** Estado observável da proteção. */
    enum class State { IDLE, RUNNING, CANCELLING, QUARANTINED, STUCK, RELEASED }

    /** Eventos que mudam o estado. */
    enum class Event {
        START, CANCEL_REQUESTED, CANCEL_ACCEPTED, WORKER_FINISHED,
        QUARANTINE_EXHAUSTED, OWNER_GONE, STOP_REQUESTED
    }

    /**
     * Decisão de um passo.
     *
     * @property state estado resultante.
     * @property release pode soltar lock/owner/notificação?
     * @property admitNew pode aceitar um job novo neste estado?
     * @property reason motivo legível (vai para o log do laboratório).
     */
    data class Decision(
        val state: State,
        val release: Boolean,
        val admitNew: Boolean,
        val reason: String,
    )

    /**
     * Um terminal só libera proteção quando o worker deu ack — ou quando
     * nenhum worker chegou a iniciar (não há computação a proteger).
     */
    fun canRelease(fromWorker: Boolean, workerFinished: Boolean,
                   workerStarted: Boolean = true, reserved: Boolean = false): Boolean =
        // R21/C2 — RESERVA conta como trabalho protegido: mesmo sem
        // worker_started (startService é assíncrono), NÃO se libera o dono.
        fromWorker || workerFinished || (!workerStarted && !reserved)

    // R55/B01 — o gate `releaseWithoutService` foi SUBSTITUÍDO pela regra única
    // `WhisperJobLifecycle.releasesReservedOnProvenTerminal`, consultada pelo
    // `finishJob` (sem Serviço) e pelo `stopJob` (com Serviço). Manter as duas
    // permitia que o mesmo terminal liberasse num caminho e não no outro.

    /**
     * Admissão de um job novo.
     *
     * @param ownerAtivo existe dono atual (`owner != 0`).
     * @param workerStarted algum worker chegou a iniciar trabalho nesta geração.
     * @param workerFinished o worker desta geração já deu ack de término.
     */
    fun admitNew(ownerAtivo: Boolean, workerStarted: Boolean,
                 workerFinished: Boolean): Decision {
        if (!ownerAtivo) {
            return Decision(State.IDLE, release = false, admitNew = true,
                reason = "sem dono ativo")
        }
        if (workerFinished) {
            // Término provado: o dono pode ser substituído (release do anterior
            // é feito pelo caminho de terminal).
            return Decision(State.IDLE, release = true, admitNew = true,
                reason = "worker anterior terminou")
        }
        if (!workerStarted) {
            // Nenhum worker iniciou: não há computação a proteger.
            return Decision(State.IDLE, release = true, admitNew = true,
                reason = "nenhum worker chegou a iniciar")
        }
        return Decision(State.RUNNING, release = false, admitNew = false,
            reason = "worker em voo: admissão recusada (dono nao substituido)")
    }

    /**
     * Quarentena esgotada (owner-gone ou dreno) sem ack do worker.
     *
     * Nunca devolve `release=true` sem término provado: vira [State.STUCK].
     */
    fun quarantineExhausted(workerFinished: Boolean,
                            workerStarted: Boolean = true): Decision =
        if (workerFinished || !workerStarted) {
            Decision(State.RELEASED, release = true, admitNew = true,
                reason = if (workerFinished) "quarentena esgotada com ack do worker"
                         else "quarentena esgotada sem worker iniciado")
        } else {
            Decision(State.STUCK, release = false, admitNew = false,
                reason = "quarentena esgotada SEM ack: contencao (lock e owner mantidos)")
        }

    /**
     * Ausência de canal de cancelamento.
     *
     * R22/C2 — a RESERVA também conta: `startService` é assíncrono, então
     * `workerStarted == false` com reserva ativa significa "trabalho pedido,
     * ainda não confirmado", NÃO "nada em curso". Antes desta correção a
     * ausência de forwarder liberava o dono nessa janela (defeito provado por
     * C2FronteirasR22Test."timeout no init nao libera a reserva").
     */
    fun noForwarder(workerStarted: Boolean, reserved: Boolean = false): Decision =
        if (!workerStarted && !reserved) {
            Decision(State.RELEASED, release = true, admitNew = true,
                reason = "sem forwarder e nenhum worker iniciado")
        } else {
            Decision(State.STUCK, release = false, admitNew = false,
                reason = if (reserved) "sem forwarder com reserva ativa: contencao"
                         else "sem forwarder com worker iniciado: contencao")
        }

    /** Passo genérico de estado, para diagnóstico consistente. */
    fun step(state: State, event: Event, workerFinished: Boolean): Decision {
        return when (event) {
            Event.WORKER_FINISHED ->
                Decision(State.RELEASED, true, true, "ack do worker")
            Event.CANCEL_REQUESTED ->
                Decision(State.CANCELLING, false, false, "cancel pedido")
            Event.CANCEL_ACCEPTED ->
                Decision(State.CANCELLING, false, false, "cancel aceito (nao e termino)")
            Event.QUARANTINE_EXHAUSTED -> quarantineExhausted(workerFinished)
            Event.OWNER_GONE ->
                Decision(State.QUARANTINED, false, false, "dono ausente: quarentena")
            Event.STOP_REQUESTED ->
                Decision(State.CANCELLING, false, false,
                    "STOP da notificacao: encaminhar cancel, nao liberar")
            Event.START ->
                Decision(State.RUNNING, false, false, "job iniciado")
        }
    }
}
