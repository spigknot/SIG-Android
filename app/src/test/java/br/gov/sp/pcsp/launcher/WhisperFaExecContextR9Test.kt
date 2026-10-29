package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R9/A2 — classificação de execução da FA POR CONTEXTO do backend solicitado.
 *
 * Falha R8 coberta aqui: `fa_job_exec_label` retornava CPU_FALLBACK para todo
 * delta CPU>0, inclusive quando o usuário PEDIU CPU — a mensagem curta chegava
 * a dizer "sem aceleração por GPU" para quem escolheu CPU+FA. A regra nova:
 *  - CPU solicitado + FA CPU computada  => CPU_EXECUTED (execução normal);
 *  - GPU solicitado + op CPU            => CPU_FALLBACK;
 *  - GPU e CPU no mesmo job             => MIXED;
 *  - nada computado                     => NOT_RUN / REFUSED_NO_EXEC / UNKNOWN.
 * Tudo a partir de dados estruturados (backend solicitado + rótulo + contadores
 * POR JOB); NUNCA de supports_op nem de heurística de texto.
 */
class WhisperFaExecContextR9Test {

    private val statusCpu =
        "SIG_FA_STATUS requested=1 backend=cpu graph_fa=1 family=cpu_f32acc preflight=skipped exec=NOT_RUN " +
            "exec_fa_gpu=0 exec_fa_cpu=0 sm_gpu_wg64=0 sm_cpu_refused=0 sm_route=\"indisponivel\" " +
            "profile=standard reason=\"sem recusa registrada\" evidence=structured_v1"

    /** Linha NOVA (R9/A2): backend_req/backend_eff explícitos. */
    private val execCpuNew =
        "SIG_FA_EXEC requested=1 backend=cpu backend_req=cpu backend_eff=cpu graph_fa=1 family=cpu_f32acc " +
            "preflight=n/a exec=CPU_EXECUTED job_gpu_r6=0 job_gpu_generic=0 job_cpu=83 " +
            "cpu_exec_source=ggml-cpu job_refusals=0 owner=4 totals_gpu_r6=0 totals_gpu_generic=0 " +
            "totals_cpu=83 totals_refusals=0 profile=standard reason=\"sem recusa registrada\" evidence=structured_v2"

    /** Linha no formato da lib R8 (sem backend_req/eff; rótulo antigo errado). */
    private val execCpuLegacyFallback =
        "SIG_FA_EXEC requested=1 backend=cpu graph_fa=1 family=cpu_f32acc preflight=n/a exec=CPU_FALLBACK " +
            "exec_fa_gpu=0 exec_fa_cpu=83 sm_gpu_wg64=0 sm_cpu_refused=0 sm_route=\"indisponivel\" " +
            "profile=standard reason=\"sem recusa registrada\" evidence=structured_v1"

    private val execGpuFallback =
        "SIG_FA_EXEC requested=1 backend=vulkan backend_req=vulkan backend_eff=cpu graph_fa=1 " +
            "family=vulkan_fa_r6_compact variant=compacta_r6 exec=CPU_FALLBACK job_gpu_r6=0 " +
            "job_gpu_generic=0 job_cpu=12 cpu_exec_source=ggml-cpu job_refusals=1 owner=7 " +
            "profile=experimental_no_validated_advantage reason=\"modo/pipelines indisponiveis\" evidence=structured_v2"

    private val execMixed =
        "SIG_FA_EXEC requested=1 backend=vulkan backend_req=vulkan backend_eff=vulkan+cpu graph_fa=1 " +
            "family=vulkan_fa_r6_compact exec=MIXED job_gpu_r6=4 job_gpu_generic=0 job_cpu=2 " +
            "cpu_exec_source=ggml-cpu job_refusals=0 owner=8 profile=experimental_no_validated_advantage " +
            "reason=\"ok\" evidence=structured_v2"

    // ------------------------------------------------------ R16/D2

    @Test
    fun `R16 D2 exec_family r6 puro reconcilia family generica`() {
        val exec =
            "SIG_FA_EXEC requested=1 backend=vulkan backend_req=vulkan backend_eff=vulkan graph_fa=1 " +
                "family=vulkan_generic variant=generica exec_family=vulkan_fa_r6_exec exec=GPU_EXECUTED " +
                "job_gpu_r6=2072 job_gpu_generic=0 job_cpu=0 cpu_exec_source=ggml-cpu job_refusals=0 owner=1 " +
                "profile=standard reason=\"ok\" evidence=structured_v2"
        val status = FlashAttentionNotice.parseStatus(exec, FlashAttentionNotice.KIND_VULKAN, true)!!
        // family de construção preservada (mensagem do produto não muda)...
        assertEquals("vulkan_generic", status.family)
        // ...mas a efetiva da execução é R6 (reconciliação, não contradição).
        assertEquals("vulkan_fa_r6_exec", status.execFamily)
        assertEquals(2072L, status.jobGpuR6)
        assertEquals(0L, status.jobGpuGeneric)
    }

    @Test
    fun `R16 D2 exec_family ausente em linha antiga vira n-a`() {
        val status = FlashAttentionNotice.parseStatus(execMixed, FlashAttentionNotice.KIND_VULKAN, true)!!
        assertEquals("n/a", status.execFamily)
    }

    // ------------------------------------------------------ CPU solicitado

    @Test
    fun `CPU+FA com rotulo novo vira CPU_EXECUTED e nao nega aceleracao`() {
        val log = statusCpu + "\n" + execCpuNew + "\n"
        val verdict = FlashAttentionNotice.verdict(true, FlashAttentionNotice.KIND_CPU, log)
        assertTrue(verdict is FlashAttentionNotice.Verdict.Applied)
        val message = (verdict as FlashAttentionNotice.Verdict.Applied).message
        assertTrue(message.contains("aplicado (CPU)"))
        assertTrue(!message.contains("sem aceleração"))
        assertTrue(!message.contains("não executado na GPU"))
        val status = FlashAttentionNotice.parseStatus(log, FlashAttentionNotice.KIND_CPU, true)!!
        assertEquals(FlashAttentionNotice.Exec.CPU_EXECUTED, status.exec)
        assertEquals("cpu", status.backendReq)
        assertEquals("cpu", status.backendEff)
        val summary = FlashAttentionNotice.executionSummary(true, FlashAttentionNotice.KIND_CPU, log)!!
        assertTrue(summary.contains("exec=CPU_EXECUTED"))
        assertTrue(summary.contains("backend_req=cpu"))
        assertTrue(summary.contains("backend_eff=cpu"))
    }

    @Test
    fun `CPU+FA com rotulo ANTIGO CPU_FALLBACK e reclassificado por contexto`() {
        // Regressão da falha R8: a lib antiga rotulava execução CPU como
        // fallback e a mensagem dizia "sem aceleração por GPU" para CPU+FA.
        val log = statusCpu + "\n" + execCpuLegacyFallback + "\n"
        val status = FlashAttentionNotice.parseStatus(log, FlashAttentionNotice.KIND_CPU, true)!!
        assertEquals(FlashAttentionNotice.Exec.CPU_EXECUTED, status.exec)
        assertEquals("cpu", status.backendReq)
        val verdict = FlashAttentionNotice.verdict(true, FlashAttentionNotice.KIND_CPU, log)
        assertTrue(verdict is FlashAttentionNotice.Verdict.Applied)
        val message = (verdict as FlashAttentionNotice.Verdict.Applied).message
        assertTrue(!message.contains("sem aceleração"))
        assertTrue(!message.contains("não executado na GPU"))
        assertTrue(message.contains("aplicado (CPU)"))
    }

    @Test
    fun `normalizacao direta - contexto manda, nao o rotulo`() {
        assertEquals(
            FlashAttentionNotice.Exec.CPU_EXECUTED,
            FlashAttentionNotice.normalizeExecForRequested(FlashAttentionNotice.Exec.CPU_FALLBACK, "cpu")
        )
        assertEquals(
            FlashAttentionNotice.Exec.CPU_FALLBACK,
            FlashAttentionNotice.normalizeExecForRequested(FlashAttentionNotice.Exec.CPU_FALLBACK, "vulkan")
        )
        assertEquals(
            FlashAttentionNotice.Exec.GPU_EXECUTED,
            FlashAttentionNotice.normalizeExecForRequested(FlashAttentionNotice.Exec.GPU_EXECUTED, "cpu")
        )
    }

    // ------------------------------------------------------- GPU solicitada

    @Test
    fun `GPU+FA com op no CPU permanece CPU_FALLBACK e explica`() {
        val log = statusCpu.replace("backend=cpu", "backend=vulkan") + "\n" + execGpuFallback + "\n"
        val status = FlashAttentionNotice.parseStatus(log, FlashAttentionNotice.KIND_VULKAN, true)!!
        assertEquals(FlashAttentionNotice.Exec.CPU_FALLBACK, status.exec)
        assertEquals("vulkan", status.backendReq)
        assertEquals("cpu", status.backendEff)
        val verdict = FlashAttentionNotice.verdict(true, FlashAttentionNotice.KIND_VULKAN, log)
        assertTrue(verdict is FlashAttentionNotice.Verdict.NotApplied)
        val message = (verdict as FlashAttentionNotice.Verdict.NotApplied).message
        assertTrue(message.contains("fallback para CPU"))
        assertTrue(!message.contains("aplicado"))
    }

    @Test
    fun `GPU+FA MIXED permanece parcial com backend_eff composto`() {
        val log = statusCpu.replace("backend=cpu", "backend=vulkan") + "\n" + execMixed + "\n"
        val status = FlashAttentionNotice.parseStatus(log, FlashAttentionNotice.KIND_VULKAN, true)!!
        assertEquals(FlashAttentionNotice.Exec.MIXED, status.exec)
        assertEquals("vulkan+cpu", status.backendEff)
        val verdict = FlashAttentionNotice.verdict(true, FlashAttentionNotice.KIND_VULKAN, log)
        assertTrue(verdict is FlashAttentionNotice.Verdict.Mixed)
    }

    // --------------------------------------------- nenhuma computação

    @Test
    fun `sem computo - NOT_RUN REFUSED e UNKNOWN nao viram fallback nem GPU`() {
        val notRun = "SIG_FA_EXEC requested=1 backend=vulkan backend_req=vulkan backend_eff=none graph_fa=1 " +
            "family=vulkan_fa_r6_compact exec=NOT_RUN job_gpu_r6=0 job_gpu_generic=0 job_cpu=0 " +
            "cpu_exec_source=ggml-cpu job_refusals=0 evidence=structured_v2"
        val refused = "SIG_FA_EXEC requested=1 backend=vulkan backend_req=vulkan backend_eff=none graph_fa=1 " +
            "family=vulkan_fa_r6_compact exec=REFUSED_NO_EXEC job_refusals=3 reason=\"modo/pipelines indisponiveis\" evidence=structured_v2"
        val unknown = "SIG_FA_EXEC requested=1 backend=vulkan graph_fa=1 family=vulkan_fa_r6_compact exec=UNKNOWN evidence=structured_v2"
        assertTrue(
            FlashAttentionNotice.verdict(true, FlashAttentionNotice.KIND_VULKAN, notRun + "\n")
                is FlashAttentionNotice.Verdict.Unproven
        )
        val refusedVerdict = FlashAttentionNotice.verdict(true, FlashAttentionNotice.KIND_VULKAN, refused + "\n")
        assertTrue(refusedVerdict is FlashAttentionNotice.Verdict.NotApplied)
        assertTrue((refusedVerdict as FlashAttentionNotice.Verdict.NotApplied).message.contains("recusado neste job"))
        assertTrue(
            FlashAttentionNotice.verdict(true, FlashAttentionNotice.KIND_VULKAN, unknown + "\n")
                is FlashAttentionNotice.Verdict.Unproven
        )
        // CPU pedido sem NENHUMA prova não vira "aplicado"
        assertTrue(
            FlashAttentionNotice.verdict(true, FlashAttentionNotice.KIND_CPU, unknown + "\n")
                is FlashAttentionNotice.Verdict.Unproven
        )
    }

    // ------------------------------------- escopo de job (sem herança)

    @Test
    fun `marcador faJobBegin - job CPU novo nao herda GPU antigo`() {
        val gpuOld = "SIG_FA_EXEC requested=1 backend=vulkan graph_fa=1 family=vulkan_fa_r6_compact " +
            "exec=GPU_EXECUTED job_gpu_r6=17 job_cpu=0 cpu_exec_source=ggml-cpu job_refusals=0 " +
            "profile=experimental_no_validated_advantage reason=\"ok\" evidence=structured_v2"
        val log = gpuOld + "\nfaJobBegin route=file\n" + statusCpu + "\n" + execCpuNew + "\n"
        val summary = FlashAttentionNotice.executionSummary(true, FlashAttentionNotice.KIND_CPU, log)!!
        assertTrue(summary.contains("exec=CPU_EXECUTED"))
        assertTrue(summary.contains("cpu=83"))
        assertTrue(summary.contains("backend_eff=cpu"))
        assertTrue(!summary.contains("gpu_r6=17"))
    }

    @Test
    fun `detalhes trazem backend_req e backend_eff`() {
        val log = statusCpu + "\n" + execCpuNew + "\n"
        val details = FlashAttentionNotice.details(true, FlashAttentionNotice.KIND_CPU, log)!!
        assertTrue(details.contains("backend_req=cpu"))
        assertTrue(details.contains("backend_eff=cpu"))
        // lib antiga: campos derivados, nunca "n/a"
        val old = FlashAttentionNotice.details(true, FlashAttentionNotice.KIND_CPU, statusCpu + "\n" + execCpuLegacyFallback + "\n")!!
        assertTrue(old.contains("backend_req=cpu"))
        assertTrue(old.contains("backend_eff=cpu"))
    }
}
