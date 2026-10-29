package br.gov.sp.pcsp.launcher

/**
 * R19/C1 — RESERVA do worker, registrada ANTES do primeiro passo bloqueante.
 *
 * Problema medido: `worker_start_real` era enviado por `startService`
 * (assíncrono) e só chegava DEPOIS do carregamento nativo — em `c1-ok3`,
 * `model_loaded` precedia a marca em 151 ms. Nessa janela, a política do
 * serviço lia `!workerStarted` como "pode liberar/substituir".
 *
 * Contrato:
 *  1. `reserve(gen)` é chamado pelo dono ANTES de qualquer trabalho caro;
 *     devolve `false` se outra geração já detém a reserva (admissão recusada).
 *  2. Quem recebe `false` NÃO inicia compute.
 *  3. `adopt(gen)` é o serviço reconhecendo a mesma geração (não cria reserva
 *     nova para outro dono).
 *  4. `release(gen)` só libera se `gen` for o dono — ACK atrasado de outra
 *     geração não derruba a reserva corrente.
 *  5. R55/B02 — a geração que JÁ TERMINOU não volta: `reserve` de uma geração
 *     com terminal provado devolve `REFUSED_STALE_GENERATION`. A memória é um
 *     escalar só (última geração + terminal), nunca um conjunto que cresce, e
 *     não existe `clear` global de produção.
 *
 * A classe é pura (sem Android): o teste exercita todas as intercalações no
 * host. O armazenamento real é um singleton thread-safe em [WorkerReservationStore].
 */
object WorkerReservation {

    /** Resultado de uma tentativa de reserva. */
    enum class Decision {
        /** Reserva criada para esta geração. */
        RESERVED,
        /** A mesma geração já era dona (idempotente). */
        ALREADY_HELD,
        /** Outra geração detém a reserva — quem chamou NÃO deve computar. */
        REFUSED_OTHER_OWNER,
        /**
         * R55/B02 — geração que JÁ TERMINOU (ou anterior a ela). Recusa sem
         * tocar no estado: quem chama não computa. Antes desta decisão o
         * `reserve` de uma geração encerrada voltava `RESERVED`, reocupava a
         * reserva e o dono morto bloqueava todas as gerações seguintes.
         */
        REFUSED_STALE_GENERATION,
    }

    /** Estado puro, testável. */
    class State {
        var owner: Long = -1L
            private set

        /**
         * R55/B02 — última geração com terminal PROVADO (escalar ÚNICO).
         *
         * É a identidade DELIMITADA da reserva: guarda só o último número, não
         * um cemitério de gerações, e só avança pelo [release] do próprio dono.
         * Uma reserva atrasada dessa geração — ou de qualquer anterior — é
         * recusada; gerações futuras nunca são bloqueadas.
         */
        var lastTerminalGen: Long = -1L
            private set

        /** Terminal que encerrou [lastTerminalGen] — ESTADO, não linha de log. */
        var lastTerminal: String = ""
            private set

        fun held(): Boolean = owner >= 0L
        fun heldBy(gen: Long): Boolean = owner >= 0L && owner == gen

        fun reserve(gen: Long): Decision {
            // R21/C2 — geração ZERO ou negativa não ocupa reserva (a R20
            // aceitava 0 e a reserva ficava "presa" num dono inexistente).
            if (gen <= 0L) return Decision.REFUSED_OTHER_OWNER
            if (owner == gen) return Decision.ALREADY_HELD
            // R55/B02 — geração com terminal provado NÃO volta a ser dona: o
            // worker dela já não existe (ACTION_START atrasado, retry, etc.).
            if (gen <= lastTerminalGen) return Decision.REFUSED_STALE_GENERATION
            if (owner >= 0L) return Decision.REFUSED_OTHER_OWNER
            owner = gen
            return Decision.RESERVED
        }

        /** Serviço reconhece a mesma geração; nunca troca de dono. */
        fun adopt(gen: Long): Boolean {
            if (gen <= 0L) return false
            if (owner == gen) return true
            if (gen <= lastTerminalGen) return false
            if (owner < 0L) {
                owner = gen
                return true
            }
            return false
        }

        /** Só o dono libera. ACK de outra geração é ignorado. */
        fun release(gen: Long, terminal: String = ""): Boolean {
            if (owner < 0L) return false
            if (owner != gen) return false
            owner = -1L
            lastTerminalGen = gen
            lastTerminal = terminal
            return true
        }

        /** Fim forçado (processo/serviço encerrando) — não é liberação normal. */
        fun clear(): Long {
            val anterior = owner
            owner = -1L
            lastTerminalGen = -1L
            lastTerminal = ""
            return anterior
        }
    }
}

/**
 * R19/C1 — armazenamento da reserva, compartilhado entre Activity e Service
 * (mesmo processo). Thread-safe: a reserva é feita na thread do job e lida na
 * thread do serviço.
 */
object WorkerReservationStore {
    private val state = WorkerReservation.State()
    private val lock = Any()

    fun reserve(gen: Long): WorkerReservation.Decision = synchronized(lock) { state.reserve(gen) }
    fun adopt(gen: Long): Boolean = synchronized(lock) { state.adopt(gen) }

    /** R55/B02 — o terminal fica REGISTRADO junto da liberação (estado, não log). */
    fun release(gen: Long, terminal: String = ""): Boolean =
        synchronized(lock) { state.release(gen, terminal) }

    fun clear(): Long = synchronized(lock) { state.clear() }
    fun heldBy(gen: Long): Boolean = synchronized(lock) { state.heldBy(gen) }
    fun owner(): Long = synchronized(lock) { state.owner }
    fun lastTerminalGen(): Long = synchronized(lock) { state.lastTerminalGen }
    fun lastTerminal(): String = synchronized(lock) { state.lastTerminal }
}
