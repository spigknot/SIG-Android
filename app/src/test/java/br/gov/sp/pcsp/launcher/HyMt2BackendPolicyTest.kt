package br.gov.sp.pcsp.launcher

import br.gov.sp.pcsp.launcher.HyMt2BackendPolicy.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contratos do capabilities/política de rotas da ferramenta Texto.
 *
 * Trava a meta final (menu de seis entradas, nesta ordem), os ids persistidos
 * (os quatro históricos não são renumerados; Smart entra com dois ids novos e
 * migração segura) e a regra de habilitação: Smart (X) só aparece disponível
 * com as DUAS pontas (NPU + GPU X) e modelo compatível; qualquer negativa traz
 * motivo visível — nunca rota degradando em silêncio para CPU/NPU inteira.
 */
class HyMt2BackendPolicyTest {

    private fun tudoSuportado() = HyMt2BackendPolicy.RuntimeSupport(npu = true, gpuOpenCl = true, gpuVulkan = true)
    private fun semNpu() = HyMt2BackendPolicy.RuntimeSupport(npu = false, gpuOpenCl = true, gpuVulkan = true)

    private val modeloQ4 = "Hy-MT2-1.8B-Q4_K_M.gguf"
    private val modeloQ40 = "Hy-MT2-1.8B-Q4_0.gguf"
    private val modeloStq = "Hy-MT2-1.8B-1.25Bit.gguf"

    @Test
    fun menuTemAsSeisRotasNaOrdemDaMetaFinal() {
        assertEquals(
            listOf("SMART_OPENCL", "SMART_VULKAN", "CPU", "OPENCL", "VULKAN", "NPU"),
            Route.values().map { it.id }
        )
    }

    @Test
    fun idsPersistidosDosQuatroHistoricosNaoMudam() {
        assertEquals("CPU", Route.CPU.id)
        assertEquals("OPENCL", Route.GPU_OPENCL.id)
        assertEquals("VULKAN", Route.GPU_VULKAN.id)
        assertEquals("NPU", Route.NPU.id)
        assertEquals(Route.CPU, Route.fromId("CPU"))
        assertEquals(Route.GPU_OPENCL, Route.fromId("OPENCL"))
        assertEquals(Route.GPU_VULKAN, Route.fromId("VULKAN"))
        assertEquals(Route.NPU, Route.fromId("NPU"))
    }

    @Test
    fun idsDosDoisSmartSaoNovosEDistintos() {
        assertEquals(Route.SMART_OPENCL, Route.fromId("SMART_OPENCL"))
        assertEquals(Route.SMART_VULKAN, Route.fromId("SMART_VULKAN"))
        assertEquals(6, Route.values().map { it.id }.toSet().size)
    }

    @Test
    fun idDesconhecidoOuAusenteVoltaParaCpu() {
        assertEquals(Route.CPU, Route.fromId("SMART_GENERICO"))
        assertEquals(Route.CPU, Route.fromId(null))
        assertEquals(Route.CPU, Route.fromId(""))
    }

    @Test
    fun modeloStq_somenteCpuDisponivel() {
        val todas = HyMt2BackendPolicy.availability(modeloStq, tudoSuportado())
        assertTrue(todas.first { it.route == Route.CPU }.available)
        todas.filter { it.route != Route.CPU }.forEach { av ->
            assertFalse("rota ${av.route} deveria estar indisponível no 1.25bit", av.available)
            assertNotNull(av.reason)
            assertTrue(av.reason!!.contains("1.25bit"))
        }
    }

    @Test
    fun runtimeCompleto_modeloQ4_todasDisponiveis() {
        val todas = HyMt2BackendPolicy.availability(modeloQ4, tudoSuportado())
        assertEquals(6, todas.size)
        todas.forEach { av ->
            assertTrue("rota ${av.route} deveria estar disponível", av.available)
            assertNull(av.reason)
        }
    }

    @Test
    fun semNpu_smartENpuIndisponiveisComMotivo() {
        val todas = HyMt2BackendPolicy.availability(modeloQ40, semNpu())
        val smartOc = todas.first { it.route == Route.SMART_OPENCL }
        val smartVk = todas.first { it.route == Route.SMART_VULKAN }
        val npu = todas.first { it.route == Route.NPU }
        assertFalse(smartOc.available)
        assertFalse(smartVk.available)
        assertFalse(npu.available)
        assertTrue(smartOc.reason!!.contains("NPU"))
        assertTrue(smartVk.reason!!.contains("NPU"))
        assertTrue(npu.reason!!.contains("NPU"))
        assertTrue(todas.first { it.route == Route.GPU_OPENCL }.available)
        assertTrue(todas.first { it.route == Route.GPU_VULKAN }.available)
    }

    @Test
    fun semVulkan_smartVulkanIndisponivel_masSmartOpenclOk() {
        val support = HyMt2BackendPolicy.RuntimeSupport(npu = true, gpuOpenCl = true, gpuVulkan = false)
        val todas = HyMt2BackendPolicy.availability(modeloQ4, support)
        val smartVk = todas.first { it.route == Route.SMART_VULKAN }
        assertFalse(smartVk.available)
        assertTrue(smartVk.reason!!.contains("Vulkan"))
        assertTrue(todas.first { it.route == Route.SMART_OPENCL }.available)
        assertFalse(todas.first { it.route == Route.GPU_VULKAN }.available)
        assertTrue(todas.first { it.route == Route.GPU_OPENCL }.available)
    }

    @Test
    fun semDeviceGpu_smartEGpuPurasIndisponiveis_npuOk() {
        val support = HyMt2BackendPolicy.RuntimeSupport(npu = true, gpuOpenCl = false, gpuVulkan = false)
        val todas = HyMt2BackendPolicy.availability(modeloQ4, support)
        assertFalse(todas.first { it.route == Route.SMART_OPENCL }.available)
        assertFalse(todas.first { it.route == Route.SMART_VULKAN }.available)
        assertFalse(todas.first { it.route == Route.GPU_OPENCL }.available)
        assertFalse(todas.first { it.route == Route.GPU_VULKAN }.available)
        assertTrue(todas.first { it.route == Route.NPU }.available)
        assertTrue(todas.first { it.route == Route.CPU }.available)
    }

    @Test
    fun availabilitySegueOrdemDoMenu() {
        val ordem = HyMt2BackendPolicy.availability(modeloQ4, tudoSuportado()).map { it.route }
        assertEquals(Route.values().toList(), ordem)
    }

    @Test
    fun rotulosDoSmartBatemComAProposta() {
        assertEquals("Smart (OpenCL)", Route.SMART_OPENCL.label)
        assertEquals("Smart (Vulkan)", Route.SMART_VULKAN.label)
        assertEquals(6, Route.values().map { it.shortLabel }.toSet().size)
    }

    @Test
    fun nativeKindsFixamOContratoJavaJni() {
        assertEquals(0, Route.CPU.nativeKind)
        assertEquals(1, Route.GPU_OPENCL.nativeKind)
        assertEquals(2, Route.GPU_VULKAN.nativeKind)
        assertEquals(3, Route.NPU.nativeKind)
        assertEquals(4, Route.SMART_OPENCL.nativeKind)
        assertEquals(5, Route.SMART_VULKAN.nativeKind)
    }
}
