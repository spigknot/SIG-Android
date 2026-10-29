package br.gov.sp.pcsp.launcher

import android.os.Process
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * R7/A1 — IDENTIDADE DE EVENTOS DO CAMINHO AO VIVO, gerada NO PRODUTOR.
 *
 * Por que existe: na R6 os JSONL do laboratório misturavam eventos de jobs
 * diferentes num MESMO arquivo/run (duas passadas de soak, reuso de Activity,
 * reabertura após destroy) sem identidade suficiente para separá-los — a
 * análise somava janelas de jobs distintos (18 janelas × terminal de 9) e
 * cruzava "1ª parcial" de um job com "1ª fala" de outro. A correlação NUNCA
 * pode ser deduzida do nome do arquivo nem inventada depois: quem EMITE o
 * evento carimba a identidade.
 *
 * Campos carimbados em TODO evento do produtor (ver [LiveEventStamp.header]):
 *  - run_id: id do RUN DO PROCESSO (pid + instante monotônico da 1ª identidade)
 *    — um processo novo gera run_id novo mesmo que o roteiro reusa o nome;
 *  - act_id: instância da Activity (contador do processo; destroy+recriação
 *    no MESMO processo produz act_id diferente);
 *  - job_id: job ao vivo dentro da instância (incrementa a cada job — stop e
 *    recomeço na MESMA Activity NÃO reutilizam id);
 *  - gen: geração de sessão (WhisperSession) — validade/dono do contexto;
 *  - seq: ordem de emissão (monotônica por instância; dois eventos distintos
 *    nunca compartilham a mesma tupla run_id+act_id+seq);
 *  - mono: SystemClock.elapsedRealtime() no instante da emissão;
 *  - window_index: quando o evento pertence a uma janela (portão/commit/…).
 *
 * O APK NORMAL não escreve arquivo nenhum: o objeto de produção do seam
 * ([WhisperLabSeams] em src/main/loader-prod) é inerte; o flavor de
 * laboratório apenas REGISTRA o que o produtor já carimbou. Nenhum hook de
 * lab é necessário no APK de produção.
 */
object LiveEventIdentity {

    private val activityCounter = AtomicInteger(0)

    /** Id do RUN DO PROCESSO: pid + instante monotônico da primeira consulta. */
    val runId: String = "p" + Process.myPid() + "t" + SystemClock.elapsedRealtime()

    /** Nova identidade de instância de Activity (uma por onCreate). */
    fun nextActivityInstanceId(): Int = activityCounter.incrementAndGet()

    /** Forma curta p/ UI/terminal: act<A>.job<J>.gen<G>. */
    fun shortForm(activityInstanceId: Int, jobId: Long, generation: Long): String =
        "act$activityInstanceId.job$jobId.gen$generation"
}

/**
 * Carimbo de eventos de UMA instância de Activity (thread-safe: eventos são
 * emitidos da main, do worker e do leitor). O [header] devolve os campos JSON
 * de identidade, sem chaves externas, para prefixar qualquer payload.
 */
class LiveEventStamp(private val activityInstanceId: Int) {

    private val seq = AtomicLong(0)

    @Volatile private var jobId = 0L

    @Volatile private var jobGeneration = -1L

    /** Abre um job ao vivo desta instância; devolve o job_id recém-assumido. */
    fun beginJob(generation: Long): Long {
        jobId += 1
        jobGeneration = generation
        return jobId
    }

    /** Último job_id assumido (0 = nenhum job abriu nesta instância ainda). */
    fun currentJobId(): Long = jobId

    /** Geração do último job aberto (-1 = nenhum). */
    fun currentJobGeneration(): Long = jobGeneration

    /**
     * Header JSON de identidade: run_id/act_id/job_id/gen/seq/mono (+
     * window_index quando [windowIndex] >= 0). NÃO inclui chaves externas.
     */
    fun header(generation: Long, windowIndex: Int = -1): String {
        val sb = StringBuilder(112)
        sb.append("\"run_id\":\"").append(LiveEventIdentity.runId).append('"')
        sb.append(",\"act_id\":").append(activityInstanceId)
        sb.append(",\"job_id\":").append(jobId)
        sb.append(",\"gen\":").append(generation)
        sb.append(",\"seq\":").append(seq.incrementAndGet())
        sb.append(",\"mono\":").append(SystemClock.elapsedRealtime())
        if (windowIndex >= 0) sb.append(",\"window_index\":").append(windowIndex)
        return sb.toString()
    }

    /** R8/A1: identidade IMUTÁVEL de um job — capturada UMA vez no beginJob e
     * usada em TODA saída assíncrona daquele job (um sucessor que abre job novo
     * NÃO muda os carimbos de quem já começou). */
    fun identitySnapshot(generation: Long): LiveJobIdentity =
        LiveJobIdentity(
            runId = LiveEventIdentity.runId,
            activityInstanceId = activityInstanceId,
            jobId = jobId,
            generation = generation,
        )

    /** Header para a identidade IMUTÁVEL [identity] (seq/mono seguem do carimbo;
     * os campos de identidade vêm do snapshot, nunca do estado corrente). */
    fun headerFor(identity: LiveJobIdentity, windowIndex: Int = -1): String {
        val sb = StringBuilder(112)
        sb.append("\"run_id\":\"").append(identity.runId).append('"')
        sb.append(",\"act_id\":").append(identity.activityInstanceId)
        sb.append(",\"job_id\":").append(identity.jobId)
        sb.append(",\"gen\":").append(identity.generation)
        sb.append(",\"seq\":").append(seq.incrementAndGet())
        sb.append(",\"mono\":").append(SystemClock.elapsedRealtime())
        if (windowIndex >= 0) sb.append(",\"window_index\":").append(windowIndex)
        return sb.toString()
    }

    /** Forma curta p/ UI/terminal (idem [LiveEventIdentity.shortForm]). */
    fun shortForm(generation: Long): String =
        LiveEventIdentity.shortForm(activityInstanceId, jobId, generation)
}
