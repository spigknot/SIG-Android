package br.gov.sp.pcsp.launcher

/**
 * Admissão e terminal do job na rota ORT (ORT1-M4, adendo8 §3.4/§3.5/§3.6).
 *
 * A rota já executava; faltava a **posse do job**: quem foi admitido, com qual
 * identidade, e o que ficou registrado no fim. Regras aqui, puras e testáveis:
 *
 * - **uma execução por vez** (single-flight): enquanto um job está em voo, nova
 *   admissão é RECUSADA com motivo — nunca dois computes sobre a mesma sessão;
 * - identidade **congelada na admissão**: job, tentativa, geração, engine,
 *   provider e hash da entrada viajam juntos até o terminal;
 * - **cancelamento** fecha o job com terminal `CANCELLED` preservando o que já
 *   havia; não apaga a identidade;
 * - **terminal é único e imutável**: `complete`/`cancel` só valem uma vez; um
 *   callback atrasado de job já encerrado é reconhecido e descartado, não
 *   reabre a admissão nem sobrescreve o registro;
 * - a geração é monotônica por processo: um terminal antigo nunca "vence" um
 *   job novo na comparação por identidade.
 *
 * Seam puro: sem Android, sem I/O de rede. O registro em disco é feito pelo
 * chamador com o [Terminal] devolvido.
 */
class WhisperOrtAdmission(private val clock: () -> Long = { System.nanoTime() }) {

    /**
     * Fotografia congelada na admissão.
     *
     * Classe (não data class) DE PROPÓSITO: [inputSha256] e [entradaVinculada] só
     * mudam via [vincularEntrada] — fora da admissão são somente leitura, para
     * nenhum consumidor reescrever a identidade. Igualdade por jobId+generation.
     */
    class Job(
        val jobId: String,
        val attempt: Long,
        val generation: Long,
        val engine: String,
        val provider: String,
        inputSha256: String,
        val admittedAtMillis: Long,
        entradaVinculada: Boolean = false,
    ) {
        /**
         * Hash da entrada. Nasce com a identidade da SELEÇÃO e é substituído pelo
         * hash medido do áudio quando o preparo termina — um único vínculo, na
         * transição para entrada preparada, sem SHA fictício no terminal.
         */
        var inputSha256: String = inputSha256
            private set

        /** `true` quando o hash MEDIDO já foi vinculado: não se reescreve. */
        var entradaVinculada: Boolean = entradaVinculada
            private set

        internal fun definirEntrada(sha256Medido: String) {
            inputSha256 = sha256Medido
            entradaVinculada = true
        }

        fun copy(
            jobId: String = this.jobId,
            attempt: Long = this.attempt,
            generation: Long = this.generation,
            engine: String = this.engine,
            provider: String = this.provider,
            inputSha256: String = this.inputSha256,
            admittedAtMillis: Long = this.admittedAtMillis,
        ): Job = Job(jobId, attempt, generation, engine, provider, inputSha256, admittedAtMillis, entradaVinculada)

        override fun equals(other: Any?): Boolean =
            other is Job && other.jobId == jobId && other.generation == generation

        override fun hashCode(): Int = 31 * jobId.hashCode() + generation.hashCode()

        override fun toString(): String =
            "Job(jobId=$jobId, attempt=$attempt, generation=$generation, engine=$engine, provider=$provider)"
    }

    /** Terminal do job. Imutável e único por admissão. */
    data class Terminal(
        val jobId: String,
        val attempt: Long,
        val generation: Long,
        val engine: String,
        val provider: String,
        val inputSha256: String,
        val outcome: String,
        /** Texto completo produzido: o resultado precisa ser recuperável (M4 §3). */
        val text: String,
        val textBytes: Int,
        val textSha256: String,
        val totalMillis: Long,
        val partial: Boolean,
        val error: String?,
    )

    /** Resultado da tentativa de admissão. */
    sealed class Admitted {
        data class Ok(val job: Job) : Admitted()
        data class Refused(val reason: String) : Admitted()
    }

    private val lock = java.util.concurrent.locks.ReentrantLock(true)
    private var emVoo: Job? = null
    private var geracao = 0L
    private val terminais = HashMap<String, Terminal>()
    private val cancelados = HashSet<String>()

    /** `true` quando há job em execução. */
    fun isBusy(): Boolean {
        lock.lock()
        try {
            return emVoo != null
        } finally {
            lock.unlock()
        }
    }

    /** Geração corrente (para diagnóstico). */
    fun generation(): Long {
        lock.lock()
        try {
            return geracao
        } finally {
            lock.unlock()
        }
    }

    /**
     * Admite um job.
     *
     * [isCancelled] já verdadeiro é recusa própria — não abre sessão nem consome
     * geração. Entrada inválida também recusa antes de qualquer compute.
     */
    /**
     * Vincula o hash MEDIDO do áudio ao job admitido.
     *
     * Só vale para o job em voo (mesma geração): um preparo antigo não reescreve a
     * identidade de outra tentativa.
     */
    fun vincularEntrada(job: Job, sha256Medido: String): Boolean {
        lock.lock()
        try {
            val atual = emVoo ?: return false
            if (atual.jobId != job.jobId || atual.generation != job.generation) return false
            if (!sha256Medido.matches(Regex("^[0-9a-f]{64}$"))) return false
            // §2.2 — vínculo ÚNICO: a identidade da entrada não pode ser reescrita
            // pelo consumidor depois de medida (antes, duas vinculações passavam).
            if (atual.entradaVinculada) return false
            atual.definirEntrada(sha256Medido)
            return true
        } finally {
            lock.unlock()
        }
    }

    fun admit(
        jobId: String,
        attempt: Long,
        engine: String,
        provider: String,
        inputSha256: String,
        isCancelled: Boolean = false,
    ): Admitted {
        lock.lock()
        try {
            if (isCancelled) return Admitted.Refused("cancelado antes da admissão")
            if (jobId.isBlank()) return Admitted.Refused("jobId vazio")
            if (!inputSha256.matches(Regex("^[0-9a-f]{64}$"))) {
                return Admitted.Refused("hash de entrada inválido")
            }
            if (emVoo != null) {
                return Admitted.Refused("job ${emVoo!!.jobId} em execução")
            }
            geracao++
            val job = Job(jobId, attempt, geracao, engine, provider, inputSha256, clock() / 1_000_000)
            emVoo = job
            return Admitted.Ok(job)
        } finally {
            lock.unlock()
        }
    }

    /**
     * Pede o cancelamento de [job].
     *
     * Devolve `false` quando o job já não é o corrente — um pedido atrasado não
     * marca a próxima tentativa. O cancelamento é OBSERVADO pelo laço entre
     * tokens: nada é interrompido à força e a sessão não é fechada em execução.
     */
    fun requestCancel(job: Job): Boolean {
        lock.lock()
        try {
            val atual = emVoo ?: return false
            if (atual.jobId != job.jobId || atual.generation != job.generation) return false
            cancelados.add(chave(job))
            return true
        } finally {
            lock.unlock()
        }
    }

    /** `true` quando o cancelamento de [job] foi pedido. */
    fun isCancelRequested(job: Job): Boolean {
        lock.lock()
        try {
            return chave(job) in cancelados
        } finally {
            lock.unlock()
        }
    }

    /**
     * Encerra o job com terminal.
     *
     * Recusa terminal repetido (retorna `null`) e terminal de job que não é o
     * que está em voo — callback atrasado não sobrescreve o registro corrente.
     */
    fun complete(
        job: Job,
        outcome: String,
        text: String,
        totalMillis: Long,
        partial: Boolean,
        error: String? = null,
    ): Terminal? {
        lock.lock()
        try {
            val atual = emVoo ?: return null
            if (atual.jobId != job.jobId || atual.generation != job.generation) return null
            val bytes = text.toByteArray(Charsets.UTF_8)
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            val terminal = Terminal(
                jobId = job.jobId,
                attempt = job.attempt,
                generation = job.generation,
                engine = job.engine,
                provider = job.provider,
                inputSha256 = job.inputSha256,
                outcome = outcome,
                text = text,
                textBytes = bytes.size,
                textSha256 = digest.joinToString("") { "%02x".format(it) },
                totalMillis = totalMillis,
                // Cancelado é PARCIAL por definição: existe texto produzido que não
                // cobre o áudio inteiro.
                partial = partial || outcome == "CANCELLED",
                error = error,
            )
            terminais[chave(job)] = terminal
            cancelados.remove(chave(job))
            emVoo = null
            return terminal
        } finally {
            lock.unlock()
        }
    }

    /** Terminal registrado de [job], ou `null`. */
    fun terminalOf(job: Job): Terminal? {
        lock.lock()
        try {
            return terminais[chave(job)]
        } finally {
            lock.unlock()
        }
    }

    /** Todos os terminais registrados (ordem de conclusão). */
    fun terminals(): List<Terminal> {
        lock.lock()
        try {
            return terminais.values.toList()
        } finally {
            lock.unlock()
        }
    }

    /**
     * Libera um job em voo sem terminal (falha catastrófica do chamador).
     *
     * Só o próprio job libera; um terceiro não derruba a admissão.
     */
    fun abandon(job: Job): Boolean {
        lock.lock()
        try {
            val atual = emVoo ?: return false
            if (atual.jobId != job.jobId || atual.generation != job.generation) return false
            emVoo = null
            return true
        } finally {
            lock.unlock()
        }
    }

    private fun chave(job: Job): String = "${job.jobId}#${job.generation}"
}
