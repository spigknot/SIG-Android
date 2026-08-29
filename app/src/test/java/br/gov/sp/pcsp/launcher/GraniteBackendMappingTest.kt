package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testes JVM puros do mapeamento GraniteExecutionBackend → QNN EP.
 *
 * Verificam que:
 * - CPU não tem qnnBackend nem accelerated.
 * - GPU_QNN e NPU_QNN_HTP são acelerados e têm qnnBackend correto.
 * - Nenhum backend acelerado referencia NNAPI (NNAPI foi removido).
 * - Os labels são consistentes e legíveis.
 */
class GraniteBackendMappingTest {

    @Test
    fun `CPU backend is not accelerated`() {
        val cpu = GraniteExecutionBackend.CPU
        assertEquals("CPU", cpu.shortLabel)
        assertEquals("CPU", cpu.reportLabel)
        assertFalse("CPU não deveria ser accelerated", cpu.accelerated)
    }

    @Test
    fun `GPU QNN backend maps to gpu`() {
        val gpu = GraniteExecutionBackend.GPU_QNN
        assertEquals("GPU", gpu.shortLabel)
        assertTrue("GPU deveria ser accelerated", gpu.accelerated)
        assertEquals("gpu", gpu.qnnBackend)
        assertFalse("GPU não deveria usar NNAPI", gpu.reportLabel.contains("NNAPI"))
    }

    @Test
    fun `NPU QNN HTP backend maps to htp`() {
        val npu = GraniteExecutionBackend.NPU_QNN_HTP
        assertEquals("NPU", npu.shortLabel)
        assertTrue("NPU deveria ser accelerated", npu.accelerated)
        assertEquals("htp", npu.qnnBackend)
        assertFalse("NPU não deveria usar NNAPI", npu.reportLabel.contains("NNAPI"))
    }

    @Test
    fun `no backend references NNAPI`() {
        for (backend in GraniteExecutionBackend.entries) {
            assertFalse("Backend ${backend.label} referencia NNAPI no label",
                backend.label.contains("NNAPI"))
            assertFalse("Backend ${backend.label} referencia NNAPI no reportLabel",
                backend.reportLabel.contains("NNAPI"))
        }
    }

    @Test
    fun `accelerated entries list excludes CPU`() {
        val accelerated = GraniteExecutionBackend.acceleratedEntries
        assertEquals(2, accelerated.size)
        assertTrue("GPU_QNN deveria estar nos acelerados",
            accelerated.contains(GraniteExecutionBackend.GPU_QNN))
        assertTrue("NPU_QNN_HTP deveria estar nos acelerados",
            accelerated.contains(GraniteExecutionBackend.NPU_QNN_HTP))
        assertFalse("CPU não deveria estar nos acelerados",
            accelerated.contains(GraniteExecutionBackend.CPU))
    }

    @Test
    fun `labels are human readable`() {
        assertEquals("CPU", GraniteExecutionBackend.CPU.label)
        assertEquals("GPU (Adreno)", GraniteExecutionBackend.GPU_QNN.label)
        assertEquals("NPU (Hexagon)", GraniteExecutionBackend.NPU_QNN_HTP.label)
    }

    @Test
    fun `report labels reflect real QNN EPs`() {
        assertEquals("GPU (QNN)", GraniteExecutionBackend.GPU_QNN.reportLabel)
        assertEquals("NPU (QNN HTP)", GraniteExecutionBackend.NPU_QNN_HTP.reportLabel)
    }

    @Test
    fun `qnnBackend is present for accelerated entries`() {
        for (be in GraniteExecutionBackend.acceleratedEntries) {
            assertNotNull("Backend acelerado ${be.label} deveria ter qnnBackend", be.qnnBackend)
        }
    }

    @Test
    fun `qnnBackend is valid value`() {
        assertEquals("gpu", GraniteExecutionBackend.GPU_QNN.qnnBackend)
        assertEquals("htp", GraniteExecutionBackend.NPU_QNN_HTP.qnnBackend)
        assertTrue(
            "qnnBackend deveria ser 'gpu' ou 'htp'",
            GraniteExecutionBackend.acceleratedEntries.all {
                it.qnnBackend in setOf("gpu", "htp")
            }
        )
    }
}