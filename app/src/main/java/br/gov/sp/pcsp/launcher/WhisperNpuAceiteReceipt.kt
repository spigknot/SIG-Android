package br.gov.sp.pcsp.launcher

import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * R102 §T01 — recibo e relógio do harness de aceite do build de produto.
 *
 * O recibo é a única saída que pode conter texto transcrito, e ele é escrito no
 * arquivo de resultado do EV — nunca no logcat. [dados] não inclui texto. O
 * timer de cancelamento corre numa thread própria do `ScheduledExecutorService`:
 * a inferência pode estar ocupada na thread do worker que o disparo continua
 * pontual. Os três instantes (programado, real e terminal) são gravados para a
 * prova de "≤ 1 s depois do instante programado" da C7.
 */
object WhisperNpuAceiteReceipt {

    const val SCHEMA = "sig-whisper-npu-aceite/v1"

    /** Instante programado, instante REAL do disparo e instante terminal do job. */
    data class Timers(
        val programadoMs: Long,
        val realMs: Long? = null,
        val terminalMs: Long? = null,
    ) {
        /** Atraso do disparo em relação ao instante programado (nunca negativo). */
        val atrasoMs: Long?
            get() = realMs?.let { (it - programadoMs).coerceAtLeast(0L) }

        /** Tempo do sinal real até o terminal (a métrica da C7). */
        val posSinalMs: Long?
            get() = terminalMs?.let { t -> realMs?.let { t - it } }
    }

    /** Um pedido medido; [texto] vai SOMENTE para o JSON do EV. */
    data class Dados(
        val celula: String,
        val rotaPedida: String,
        val rotaEfetiva: String?,
        val versaoNpu: String,
        val versaoQairt: String,
        val motivoFallback: String?,
        val openMs: Long,
        val totalMs: Long,
        val soc: String,
        val timers: Timers = Timers(0L),
        val texto: String? = null,
    ) {
        /** Serialização completa, inclusive texto: destino = arquivo do EV. */
        fun json(): String = JSONObject()
            .put("schema", SCHEMA)
            .put("celula", celula)
            .put("rota_pedida", rotaPedida)
            .put("rota_efetiva", rotaEfetiva ?: JSONObject.NULL)
            .put("versao_npu", versaoNpu)
            .put("versao_qairt", versaoQairt)
            .put("motivo_fallback", motivoFallback ?: JSONObject.NULL)
            .put("open_ms", openMs)
            .put("total_ms", totalMs)
            .put("soc", soc)
            .put("programado_ms", timers.programadoMs)
            .put("real_ms", timers.realMs ?: JSONObject.NULL)
            .put("terminal_ms", timers.terminalMs ?: JSONObject.NULL)
            .put("atraso_ms", timers.atrasoMs ?: JSONObject.NULL)
            .put("pos_sinal_ms", timers.posSinalMs ?: JSONObject.NULL)
            .put("texto", texto ?: JSONObject.NULL)
            .toString()

        /** Resumo SEM texto, seguro para logcat e terminal. */
        fun resumo(): String =
            "celula=$celula rota_pedida=$rotaPedida rota_efetiva=$rotaEfetiva " +
                "versao_npu=$versaoNpu versao_qairt=$versaoQairt " +
                "motivo=$motivoFallback open_ms=$openMs total_ms=$totalMs soc=$soc"
    }

    /** Relógio monotônico; injetável para a prova JVM do timer. */
    fun relogioPadrao(): () -> Long = { System.nanoTime() / 1_000_000L }

    /**
     * Disparo do cancelamento numa thread que a inferência não bloqueia.
     * `inicioMs + atrasoMs` é o instante PROGRAMADO; o callback recebe esse
     * valor e o instante REAL lido no disparo.
     */
    class Cancelador(
        private val relogio: () -> Long,
        private val executor: ScheduledExecutorService,
    ) {
        fun programar(inicioMs: Long, atrasoMs: Long, aoDisparar: (programadoMs: Long, realMs: Long) -> Unit) {
            val programado = inicioMs + atrasoMs
            executor.schedule({
                aoDisparar(programado, relogio())
            }, atrasoMs, TimeUnit.MILLISECONDS)
        }

        fun fechar() {
            executor.shutdownNow()
        }
    }

    fun canceladorPadrao(): Cancelador = Cancelador(
        relogioPadrao(),
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "r102-cancel-timer").apply { isDaemon = true }
        },
    )
}
