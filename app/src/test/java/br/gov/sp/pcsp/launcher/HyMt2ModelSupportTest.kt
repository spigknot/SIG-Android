package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contratos da política de backend por modelo da ferramenta Texto.
 *
 * O ponto é travar a regra que nasceu de um bug real (25/09/2026): o 1.25bit
 * (tensores STQ1_0) não pode ser oferecido em GPU — o Vulkan devolveu saída
 * alucinada e o OpenCL só cai para CPU com cópias. Q4_0/Q4_K_M são os modelos
 * com suporte real de GPU nos dois backends.
 */
class HyMt2ModelSupportTest {

    @Test
    fun modeloStq_naoSuportaGpu() {
        assertFalse(HyMt2ModelSupport.supportsGpu("Hy-MT2-1.8B-1.25Bit.gguf"))
    }

    @Test
    fun modelosQ4_suportamGpu() {
        assertTrue(HyMt2ModelSupport.supportsGpu("Hy-MT2-1.8B-Q4_0.gguf"))
        assertTrue(HyMt2ModelSupport.supportsGpu("Hy-MT2-1.8B-Q4_K_M.gguf"))
    }
}
