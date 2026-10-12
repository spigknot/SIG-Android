package br.gov.sp.pcsp.launcher

/** Capabilities e política das rotas de backend da ferramenta Texto (Hy-MT2).
 *
 * Regra pura (sem UI/Context): define as SEIS rotas do menu final — Smart
 * (OpenCL), Smart (Vulkan), CPU, GPU (OpenCL), GPU (Vulkan), NPU —, os ids
 * persistidos (os quatro históricos nunca são renumerados; Smart ganha dois ids
 * novos) e a disponibilidade de cada rota para um modelo/runtime, sempre com
 * motivo visível quando indisponível (nunca habilitar uma rota que degradaria
 * em silêncio para CPU/NPU inteira).
 *
 * Semântica (não negociável): Smart (X) = prefill no NPU/HTP → geração na GPU X,
 * com handoff de estado/KV e sem replay integral do prompt; NPU = prefill e
 * geração predominantemente no HTP. Não existe Smart genérico/automático.
 */
object HyMt2BackendPolicy {

    /** Rota de backend da ferramenta Texto; a ordem dos valores é a ordem do menu final. */
    enum class Route(
        /** Valor persistido em prefs — nunca renumerar os quatro históricos (CPU/OPENCL/VULKAN/NPU). */
        val id: String,
        /** Rótulo completo (menu/ajuda). */
        val label: String,
        /** Rótulo curto (botão da tela). */
        val shortLabel: String,
        /** backendKind correspondente no JNI (contrato Java/JNI; kinds 3/4/5 entram com os modos no llama_jni). */
        val nativeKind: Int,
    ) {
        SMART_OPENCL("SMART_OPENCL", "Smart (OpenCL)", "Smart (OC)", 4),
        SMART_VULKAN("SMART_VULKAN", "Smart (Vulkan)", "Smart (VK)", 5),
        CPU("CPU", "CPU", "CPU", 0),
        GPU_OPENCL("OPENCL", "GPU (OpenCL)", "OpenCL", 1),
        GPU_VULKAN("VULKAN", "GPU (Vulkan)", "Vulkan", 2),
        NPU("NPU", "NPU", "NPU", 3);

        companion object {
            /** Rota pelo id persistido; ausente/desconhecido (versão antiga ou futura) volta para CPU. */
            fun fromId(id: String?): Route = values().firstOrNull { it.id == id } ?: CPU
        }
    }

    /** Recursos do runtime (o chamador detecta; o seam não toca Android). */
    data class RuntimeSupport(
        /** Backend Hexagon/HTP utilizável (pacote com runtime + aparelho compatível). */
        val npu: Boolean,
        /** Device de GPU OpenCL presente. */
        val gpuOpenCl: Boolean,
        /** Device de GPU Vulkan presente. */
        val gpuVulkan: Boolean,
    )

    /** Disponibilidade de uma rota; `reason` explica a negativa (menu/log). */
    data class Availability(val route: Route, val available: Boolean, val reason: String? = null)

    /** Motivo único dos modelos 1.25bit (tensores STQ sem kernel acelerado). */
    const val STQ_REASON = "modelo 1.25bit sem kernel acelerado"

    /** Disponibilidade das seis rotas, na ordem do menu. */
    fun availability(modelFileName: String, support: RuntimeSupport): List<Availability> =
        Route.values().map { availability(it, modelFileName, support) }

    /** Disponibilidade de uma rota para o modelo e o runtime informados.
     *
     * Regras: CPU sempre; GPU (X) pede kernel de GPU no modelo + device X; NPU
     * pede kernel acelerado no modelo + runtime NPU; Smart (X) pede as DUAS
     * pontas (NPU + device X) com o mesmo modelo.
     */
    fun availability(route: Route, modelFileName: String, support: RuntimeSupport): Availability {
        val gpuKernel = HyMt2ModelSupport.supportsGpu(modelFileName)
        val npuKernel = HyMt2ModelSupport.supportsNpu(modelFileName)
        return when (route) {
            Route.CPU -> Availability(route, true)

            Route.GPU_OPENCL -> gpuRoute(route, gpuKernel, support.gpuOpenCl, "GPU OpenCL")
            Route.GPU_VULKAN -> gpuRoute(route, gpuKernel, support.gpuVulkan, "GPU Vulkan")

            Route.NPU -> when {
                !npuKernel -> denied(route, STQ_REASON)
                !support.npu -> denied(route, "aparelho sem NPU (HTP) compatível")
                else -> Availability(route, true)
            }

            Route.SMART_OPENCL, Route.SMART_VULKAN -> {
                val device = if (route == Route.SMART_OPENCL) support.gpuOpenCl else support.gpuVulkan
                val deviceName = if (route == Route.SMART_OPENCL) "GPU OpenCL" else "GPU Vulkan"
                when {
                    !npuKernel || !gpuKernel -> denied(route, STQ_REASON)
                    !support.npu && !device -> denied(route, "requer NPU (HTP) + $deviceName")
                    !support.npu -> denied(route, "requer NPU (HTP)")
                    !device -> denied(route, "requer $deviceName")
                    else -> Availability(route, true)
                }
            }
        }
    }

    private fun gpuRoute(route: Route, gpuKernel: Boolean, device: Boolean, deviceName: String): Availability =
        when {
            !gpuKernel -> denied(route, STQ_REASON)
            !device -> denied(route, "aparelho sem $deviceName")
            else -> Availability(route, true)
        }

    private fun denied(route: Route, reason: String) = Availability(route, false, reason)
}
