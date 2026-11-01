package br.gov.sp.pcsp.launcher

import java.io.File

/**
 * ADENDO 27 §2 — REGISTRO dos recibos de apresentação por TENTATIVA.
 *
 * O problema que este seam resolve: o destino do recibo final precisa estar
 * PRESO à identidade da tentativa (job/attempt/entrada) e não a um campo
 * mutável da Activity (`tempSessionDir`). Um post/terminal atrasado de A não
 * pode escrever no terminal de B nem depender de o campo global ainda apontar
 * para A.
 *
 * Aqui moram, por identidade imutável ([TranscriptionJobKey]):
 *  - o OBSERVADOR da tentativa (identidade + segmentos + fases + recibo);
 *  - o DESTINO persistente daquela tentativa (arquivo terminal da sessão);
 *  - a RESERVA de publicação única do recibo final.
 *
 * Puro no sentido de seam: sem Android, sem UI, sem I/O próprio — quem escreve
 * é o chamador, no arquivo que ESTE registro devolve para a identidade exata.
 * Cache limitado ([limite]), como o ledger.
 */
class WhisperApresentacaoRegistro(private val limite: Int = 64) {

    private val lock = Any()
    private val observadores = LinkedHashMap<String, WhisperApresentacaoObservador>()
    private val destinos = HashMap<String, File>()

    /** Chave estável da tentativa (mesma forma usada nos logs). */
    fun chave(key: TranscriptionJobKey): String =
        "${key.jobId}/${key.owner}/${key.attemptId}/${key.inputId}"

    /** Registra (ou reaproveita) o observador daquela identidade. */
    fun registrar(key: TranscriptionJobKey, relogio: () -> Long): WhisperApresentacaoObservador =
        synchronized(lock) {
            val k = chave(key)
            observadores[k] ?: WhisperApresentacaoObservador(key, relogio).also {
                observadores[k] = it
                while (observadores.size > limite) {
                    val maisAntigo = observadores.keys.firstOrNull() ?: break
                    observadores.remove(maisAntigo)
                    destinos.remove(maisAntigo)
                }
            }
        }

    /** Prende o destino persistente à identidade (nunca a um campo global). */
    fun vincularDestino(key: TranscriptionJobKey, destino: File) = synchronized(lock) {
        destinos[chave(key)] = destino
    }

    /** Destino DESTA tentativa; `null` = não persistível (nunca o de outra). */
    fun destino(key: TranscriptionJobKey): File? = synchronized(lock) { destinos[chave(key)] }

    /** Recibo da tentativa EXATA (job+attempt). Nunca o "último global". */
    fun recibo(job: Long, attempt: Long): WhisperApresentacaoObservador.Recibo? = synchronized(lock) {
        observadores.values.firstOrNull {
            it.identidade.jobId == job && it.identidade.attemptId == attempt
        }?.recibo()
    }

    /** Fotografia de todos os recibos observados (cada um com sua identidade). */
    fun recibos(): List<WhisperApresentacaoObservador.Recibo> = synchronized(lock) {
        observadores.values.map { it.recibo() }
    }

    /** Observadores vivos (para o flush de destroy). */
    fun observadoresVivos(): List<WhisperApresentacaoObservador> = synchronized(lock) {
        observadores.values.toList()
    }

    /**
     * Reserva a publicação do recibo final DAQUELA identidade. `true` = este
     * chamador publica; `false` = ainda não pode fechar (falta fato terminal/
     * finalização) ou já foi publicado. Nunca reserva para outra tentativa.
     */
    fun reservarFinal(key: TranscriptionJobKey): Boolean = synchronized(lock) {
        val obs = observadores[chave(key)] ?: return@synchronized false
        if (!obs.recibo().podeFechar) return@synchronized false
        obs.reservarPublicacaoFinal()
    }
}
