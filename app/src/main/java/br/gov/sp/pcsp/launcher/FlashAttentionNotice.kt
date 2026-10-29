package br.gov.sp.pcsp.launcher

/**
 * D3/rodada 2 + R7/fase D (D4) — capacidade OBSERVÁVEL de Flash Attention.
 *
 * Contrato R7/D: o veredito vem de ESTADO ESTRUTURADO emitido pelo motor
 * (linhas `SIG_FA_STATUS` da carga e `SIG_FA_EXEC` do job, key=value, geradas
 * por contadores REAIS nos pontos de decisão do backend). Heurística de texto
 * NÃO prova execução: sem a linha estruturada o veredito é "não comprovado"
 * (nunca "aplicado"), e a mensagem ao usuário é curta — o detalhe (família,
 * execução GPU/CPU/mista, motivo, perfil experimental) fica em [details].
 *
 * Distingue explicitamente:
 *  - pedido do usuário x grafo FA aplicado (fallback de carga);
 *  - família/variante escolhida;
 *  - GPU_EXECUTED x CPU_EXECUTED x CPU_FALLBACK x MIXED x NOT_RUN (contadores);
 *  - R9/A2: CPU_FALLBACK só quando a GPU foi PEDIDA — CPU pedido + FA CPU é
 *    CPU_EXECUTED (classificação por contexto; backend solicitado x efetivo);
 *  - perfil experimental sem vantagem validada (fa_r6 compacto);
 *  - motivo de falha/refazimento com FA off.
 *
 * Nada é habilitado por aqui: este seam só EXPLICA o que o motor registrou.
 */
object FlashAttentionNotice {

    const val KIND_CPU = 0
    const val KIND_VULKAN = 1
    const val KIND_OPENCL = 2

    enum class Exec { NOT_RUN, GPU_EXECUTED, CPU_EXECUTED, CPU_FALLBACK, MIXED, UNKNOWN, REFUSED_NO_EXEC }

    /** Estado estruturado lido das linhas SIG_FA_STATUS/SIG_FA_EXEC. */
    data class Status(
        val requested: Boolean,
        val backend: String,
        val graphFa: Boolean,
        val family: String,
        val preflight: String,
        val exec: Exec,
        val execFaGpu: Long,
        val execFaCpu: Long,
        val smGpuWg64: Long,
        val smCpuRefused: Long,
        val smRoute: String,
        val profile: String,
        val reason: String,
        val evidence: String,
        val fromExecLine: Boolean,
        // R8/A2 — por JOB (delta do MESMO job, linha SIG_FA_EXEC) e placement:
        val variant: String = "n/a",
        val placementRefusals: Long = 0,
        val jobGpuR6: Long = 0,
        val jobGpuGeneric: Long = 0,
        val jobCpu: Long = 0,
        val jobRefusals: Long = 0,
        val cpuExecSource: String = "indisponivel",
        // R9/A2 — backend SOLICITADO x EFETIVO e execução classificada com contexto:
        val backendReq: String = "n/a",
        val backendEff: String = "n/a",
        // R16/D2 — família EFETIVA (da execução: contadores r6/generic do job),
        // distinta da `family` (decisão de construção do grafo). Quando o
        // grafo genérico executa 100% dispatches R6, family=vulkan_generic mas
        // execFamily=vulkan_fa_r6_exec — reconciliação, não contradição.
        val execFamily: String = "n/a",
    ) {
        val experimental: Boolean get() = profile == "experimental_no_validated_advantage"
        val jobGpuTotal: Long get() = jobGpuR6 + jobGpuGeneric
    }

    sealed class Verdict {
        /** Usuário não pediu FA: nada a explicar. */
        object NotRequested : Verdict()

        /** FA pedido, aplicado no grafo e EXECUTADO com prova estruturada. */
        data class Applied(val status: Status, val message: String) : Verdict()

        /** FA aplicado, mas a execução foi mista (GPU + fallback CPU). */
        data class Mixed(val status: Status, val message: String) : Verdict()

        /** FA pedido e NÃO aplicado (ou não executado onde o usuário esperava). */
        data class NotApplied(val reason: String, val message: String, val status: Status?) : Verdict()

        /** Sem estado estruturado: estado NÃO comprovado (nunca "aplicado"). */
        data class Unproven(val detail: String, val message: String) : Verdict()
    }

    // ------------------------------------------------------------------ API

    fun verdict(requested: Boolean, backendKind: Int, loadLog: String): Verdict {
        if (!requested) return Verdict.NotRequested
        val status = parseStatus(loadLog, backendKind, requested)
            ?: return Verdict.Unproven(
                detail = "sem linha SIG_FA_STATUS/SIG_FA_EXEC no log do motor",
                message = "Flash Attention: solicitado — estado de execução não comprovado pelo motor (ver diagnóstico)."
            )
        return verdict(status)
    }

    fun verdict(status: Status): Verdict {
        if (!status.requested) return Verdict.NotRequested

        if (!status.graphFa) {
            val reason = status.reason.ifBlank { "motivo registrado no diagnóstico" }
            return Verdict.NotApplied(
                reason = reason,
                message = "Flash Attention: não aplicado ($reason) — transcrição seguiu com FA off.",
                status = status
            )
        }

        return when (status.exec) {
            Exec.GPU_EXECUTED -> Verdict.Applied(
                status = status,
                message = "Flash Attention: aplicado e executado na GPU (" + gpuLabel(status) + ")." +
                    (if (status.experimental) " Perfil experimental — vantagem não validada." else "") +
                    jobExecSuffix(status)
            )
            Exec.CPU_EXECUTED -> Verdict.Applied(
                status = status,
                message = "Flash Attention: aplicado (CPU)." + jobExecSuffix(status)
            )
            Exec.CPU_FALLBACK -> Verdict.NotApplied(
                reason = "executado no CPU (fallback) — " + status.reason.ifBlank { "FA sem rota na GPU" },
                message = "Flash Attention: não executado na GPU (fallback para CPU" +
                    (if (status.reason.isNotBlank()) ": ${status.reason}" else "") + ") — sem aceleração por GPU.",
                status = status
            )
            Exec.MIXED -> Verdict.Mixed(
                status = status,
                message = "Flash Attention: parcial (parte na GPU, parte no CPU) — ver diagnóstico."
            )
            Exec.NOT_RUN -> Verdict.Unproven(
                detail = "grafo com FA, execução não exercitada nesta carga",
                message = "Flash Attention: aplicado no grafo (" + status.family +
                    "); execução por backend ainda não exercitada nesta carga (ver diagnóstico)."
            )
            Exec.REFUSED_NO_EXEC -> Verdict.NotApplied(
                reason = "recusado neste job sem execução observada — " +
                    status.reason.ifBlank { "FA sem rota na GPU" },
                message = "Flash Attention: recusado neste job (sem execução no GPU nem no CPU observada" +
                    (if (status.reason.isNotBlank()) ": ${status.reason}" else "") + ") — ver diagnóstico.",
                status = status
            )
            Exec.UNKNOWN -> Verdict.Unproven(
                detail = "contadores de execução indisponíveis",
                message = "Flash Attention: aplicado no grafo, execução por backend não comprovada (ver diagnóstico)."
            )
        }
    }

    /** R8/A2: sufixo com os contadores POR JOB — atualiza a mensagem com o
     * estado do MESMO job (linha SIG_FA_EXEC), distinguindo-a da CARGA. */
    private fun jobExecSuffix(status: Status): String =
        if (!status.fromExecLine) ""
        else " Execução do job: gpu_r6=${status.jobGpuR6} gpu_generico=${status.jobGpuGeneric}" +
            " cpu=${status.jobCpu} (fonte=${status.cpuExecSource}) recusas=${status.jobRefusals}."

    /** Texto curto para status/terminal; null quando não há o que explicar. */
    fun message(requested: Boolean, backendKind: Int, loadLog: String): String? =
        when (val v = verdict(requested, backendKind, loadLog)) {
            is Verdict.NotRequested -> null
            is Verdict.Applied -> v.message
            is Verdict.Mixed -> v.message
            is Verdict.NotApplied -> v.message
            is Verdict.Unproven -> v.message
        }

    /** Detalhes (diagnóstico): uma linha estável por campo relevante. */
    fun details(requested: Boolean, backendKind: Int, loadLog: String): String? {
        if (!requested) return null
        val status = parseStatus(loadLog, backendKind, true) ?: return null
        return buildString {
            append("fa requested=1 backend=").append(status.backend)
            append(" graph_fa=").append(if (status.graphFa) 1 else 0)
            append(" family=").append(status.family)
            append(" variant=").append(status.variant)
            append(" preflight=").append(status.preflight)
            append(" exec=").append(status.exec.name)
            append(" fa_gpu=").append(status.execFaGpu)
            append(" fa_cpu=").append(status.execFaCpu)
            append(" cpu_fonte=").append(status.cpuExecSource)
            append(" backend_req=").append(status.backendReq)
            append(" backend_eff=").append(status.backendEff)
            append(" job_gpu_r6=").append(status.jobGpuR6)
            append(" job_gpu_generico=").append(status.jobGpuGeneric)
            append(" job_cpu=").append(status.jobCpu)
            append(" job_recusas=").append(status.jobRefusals)
            append(" placement_recusas=").append(status.placementRefusals)
            append(" sm_gpu_wg64=").append(status.smGpuWg64)
            append(" sm_cpu_refused=").append(status.smCpuRefused)
            append(" sm_route=").append(status.smRoute)
            append(" profile=").append(status.profile)
            append(" evidence=").append(status.evidence)
            append(" reason=").append(status.reason)
        }
    }

    // -------------------------------------------------------------- parser

    const val STATUS_PREFIX = "SIG_FA_STATUS"
    const val EXEC_PREFIX = "SIG_FA_EXEC"

    private fun backendName(kind: Int): String = when (kind) {
        KIND_CPU -> "cpu"
        KIND_VULKAN -> "vulkan"
        KIND_OPENCL -> "opencl"
        else -> "backend$kind"
    }

    private fun gpuLabel(status: Status): String = when (status.backend) {
        "vulkan" -> "GPU Vulkan"
        "opencl" -> "OpenCL"
        else -> status.backend
    }

    /** Extrai uma linha key=value pelo prefixo; a ÚLTIMA ganha (EXEC atualiza). */
    private fun findLine(loadLog: String, prefix: String): String? =
        scopeToCurrentJob(loadLog).lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("$prefix ") }
            .lastOrNull()

    /** R8/A2 — marcador de INÍCIO DE JOB escrito pelo JNI (`faJobBegin`). */
    const val JOB_BEGIN_PREFIX = "faJobBegin"

    /**
     * R8/A2: recorta o log para as linhas APÓS o último marcador `faJobBegin`
     * (o estado do JOB ATUAL). Sem marcador (logs antigos) mantém o log
     * inteiro — compatível com a evidência histórica. Com marcador e nada
     * depois, o recorte fica VAZIO de propósito: um job que não carregou nem
     * transcreveu (contexto reutilizado, silêncio, cancelado antes da
     * inferência) NÃO herda a execução do job anterior.
     */
    fun scopeToCurrentJob(loadLog: String): String {
        val lines = loadLog.lineSequence().toList()
        val lastMarker = lines.indexOfLast { it.trim().startsWith(JOB_BEGIN_PREFIX) }
        return if (lastMarker < 0) loadLog else lines.drop(lastMarker + 1).joinToString("\n")
    }

    private fun kv(line: String, key: String): String? {
        val token = "$key="
        var idx = line.indexOf(token)
        while (idx >= 0) {
            val boundary = idx == 0 || line[idx - 1] == ' '
            if (boundary) {
                val start = idx + token.length
                if (start < line.length && line[start] == '"') {
                    val end = line.indexOf('"', start + 1)
                    if (end > start) return line.substring(start + 1, end)
                }
                val end = line.indexOf(' ', start)
                return if (end < 0) line.substring(start) else line.substring(start, end)
            }
            idx = line.indexOf(token, idx + token.length)
        }
        return null
    }

    private fun longOf(line: String?, key: String): Long =
        line?.let { kv(it, key)?.toLongOrNull() } ?: 0L

    private fun execOf(raw: String?): Exec = when (raw) {
        "GPU_EXECUTED" -> Exec.GPU_EXECUTED
        "CPU_EXECUTED" -> Exec.CPU_EXECUTED
        "CPU_FALLBACK" -> Exec.CPU_FALLBACK
        "MIXED" -> Exec.MIXED
        "NOT_RUN" -> Exec.NOT_RUN
        "NOT_REQUESTED" -> Exec.NOT_RUN
        "REFUSED_NO_EXEC" -> Exec.REFUSED_NO_EXEC
        else -> Exec.UNKNOWN
    }

    /**
     * R9/A2 — classificação POR CONTEXTO do backend SOLICITADO.
     *
     * "CPU_FALLBACK" só faz sentido quando se PEDIU GPU: se o usuário escolheu
     * CPU (KIND_CPU), a op de FA computada no CPU é execução NORMAL — CPU_EXECUTED.
     * A lib antiga rotulava tudo como fallback e a mensagem chegava a dizer
     * "sem aceleração por GPU" para quem pediu CPU+FA. A correção usa SOMENTE
     * dados estruturados (backend solicitado + rótulo de execução); NÃO infere
     * execução por supports_op.
     */
    fun normalizeExecForRequested(exec: Exec, backendReq: String): Exec =
        if (exec == Exec.CPU_FALLBACK && backendReq == "cpu") Exec.CPU_EXECUTED else exec

    /** R9/A2 — efetivo derivado quando a lib antiga não emite backend_eff. */
    private fun backendEffOf(exec: Exec, backend: String): String = when (exec) {
        Exec.GPU_EXECUTED -> backend
        Exec.CPU_EXECUTED, Exec.CPU_FALLBACK -> "cpu"
        Exec.MIXED -> "$backend+cpu"
        else -> "none"
    }

    /** R8/A2: resumo CURTO da execução do MESMO job (linha SIG_FA_EXEC do log
     * atualizado); null sem prova estruturada de execução. Usado pelo caminho
     * ao vivo/arquivo para ATUALIZAR o aviso depois do job. */
    fun executionSummary(requested: Boolean, backendKind: Int, loadLog: String): String? {
        if (!requested) return null
        val status = parseStatus(loadLog, backendKind, true) ?: return null
        if (!status.fromExecLine) return null
        return buildString {
            append("FA execução job: exec=").append(status.exec.name)
            append(" gpu_r6=").append(status.jobGpuR6)
            append(" gpu_generico=").append(status.jobGpuGeneric)
            append(" cpu=").append(status.jobCpu)
            append(" cpu_fonte=").append(status.cpuExecSource)
            append(" recusas=").append(status.jobRefusals)
            append(" backend=").append(status.backend)
            append(" backend_req=").append(status.backendReq)
            append(" backend_eff=").append(status.backendEff)
            append(" familia=").append(status.family)
            append(" variante=").append(status.variant)
        }
    }

    /**
     * Lê o estado estruturado do log de carga. `SIG_FA_EXEC` (pós-job) tem
     * precedência sobre `SIG_FA_STATUS` (carga) para os campos de execução.
     * Sem nenhuma das duas linhas, devolve null — o veredito NÃO é inventado.
     */
    fun parseStatus(loadLog: String, backendKind: Int, requested: Boolean): Status? {
        val statusLine = findLine(loadLog, STATUS_PREFIX)
        val execLine = findLine(loadLog, EXEC_PREFIX)
        if (statusLine == null && execLine == null) return null

        val base = statusLine ?: execLine!!
        val execFrom = execLine ?: base
        val backend = kv(base, "backend") ?: backendName(backendKind)
        val graphFa = (kv(base, "graph_fa") ?: "0") == "1"
        val family = kv(base, "family") ?: "unknown"
        val preflight = kv(base, "preflight") ?: "n/a"
        val reason = kv(execFrom, "reason") ?: kv(base, "reason") ?: ""
        val profile = kv(base, "profile") ?: "standard"
        val evidence = kv(base, "evidence") ?: "indisponivel"
        val smRoute = kv(execFrom, "sm_route") ?: kv(base, "sm_route") ?: "indisponivel"
        // Sem a linha de execução pós-job não há prova de backend: no máximo
        // NOT_RUN (nunca GPU_EXECUTED inferido da carga).
        // R9/A2 — a classificação usa o CONTEXTO do backend solicitado:
        // backend_req da linha (lib nova) ou o backend= (que é o SOLICITADO).
        val backendReq = kv(execFrom, "backend_req") ?: kv(base, "backend_req") ?: backend
        val execRaw = if (execLine == null) Exec.NOT_RUN else execOf(kv(execLine, "exec"))
        val exec = normalizeExecForRequested(execRaw, backendReq)
        val backendEff = kv(execFrom, "backend_eff") ?: backendEffOf(exec, backend)
        return Status(
            requested = (kv(base, "requested") ?: (if (requested) "1" else "0")) == "1",
            backend = backend,
            graphFa = graphFa,
            family = family,
            preflight = preflight,
            exec = exec,
            execFaGpu = longOf(execFrom, "exec_fa_gpu"),
            execFaCpu = longOf(execFrom, "exec_fa_cpu"),
            smGpuWg64 = longOf(execFrom, "sm_gpu_wg64"),
            smCpuRefused = longOf(execFrom, "sm_cpu_refused"),
            smRoute = smRoute,
            profile = profile,
            reason = reason,
            evidence = evidence,
            fromExecLine = execLine != null,
            variant = kv(execFrom, "variant") ?: kv(base, "variant") ?: "n/a",
            placementRefusals = longOf(execFrom, "placement_refusals"),
            jobGpuR6 = longOf(execLine, "job_gpu_r6"),
            jobGpuGeneric = longOf(execLine, "job_gpu_generic"),
            jobCpu = longOf(execLine, "job_cpu"),
            jobRefusals = longOf(execLine, "job_refusals"),
            cpuExecSource = kv(execFrom, "cpu_exec_source") ?: "indisponivel",
            backendReq = backendReq,
            backendEff = backendEff,
            execFamily = kv(execFrom, "exec_family") ?: "n/a",
        )
    }
}
