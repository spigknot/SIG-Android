package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * R6/D2 (histórico) + R7/A2 — prova POR INTERVALO do portão de energia.
 *
 * Contrato R7: o atalho sem-modelo é autorizado APENAS para PCM VÁLIDO
 * integralmente ZERO. Todo o resto — fala fraca (-30 dB), música/tom/ruído,
 * um único sample não nulo, -32768, onset curto cercado de silêncio, fragmento
 * de bytes ímpares — PASSA para o modelo/VAD. Os valores 20/320 dos limiares
 * antigos ficam só como referência histórica nos testes de regressão
 * (LiveEnergyGateZeroOnlyTest).
 */
class LiveEnergyGateIntervalTest {

    private fun pcmOf(samples: IntArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            out[i * 2] = (samples[i] and 0xFF).toByte()
            out[i * 2 + 1] = ((samples[i] shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun zeros(samples: Int): IntArray = IntArray(samples)

    /** Tom senoidal puro com pico [amp]. */
    private fun sine(samples: Int, amp: Int, freq: Double = 440.0): IntArray =
        IntArray(samples) { (amp * sin(2 * PI * freq * it / 16000.0)).roundToInt() }

    /** Ruído determinístico tipo fala com rms e pico alvo aproximados. */
    private fun speechLike(samples: Int, rmsTarget: Double, peakTarget: Int): IntArray {
        val rnd = java.util.Random(42)
        val white = IntArray(samples) { rnd.nextInt(2 * peakTarget + 1) - peakTarget }
        val rms = sqrt(white.sumOf { it.toDouble() * it } / samples)
        val gain = if (rms > 0) rmsTarget / rms else 1.0
        return IntArray(samples) {
            (white[it] * gain).roundToInt().coerceIn(-peakTarget, peakTarget)
        }
    }

    private fun gate(pcm: ByteArray) = LiveEnergyGate.measure(pcm)

    @Test
    fun `zero digital e o UNICO silencio decidivel sem modelo`() {
        val g = gate(pcmOf(zeros(16000)))
        assertEquals(0.0, g.rms, 1e-9)
        assertEquals(0, g.peak)
        assertTrue(g.validPcm)
        assertTrue(LiveEnergyGate.isDigitalSilence(g))
    }

    @Test
    fun `fala fraca menos 30 dB NAO e descartada`() {
        // n06 medido no device: rms~72, pico~590. Aqui: fala sintética -30 dB.
        val pcm = pcmOf(speechLike(16000, rmsTarget = 72.4, peakTarget = 590))
        val g = gate(pcm)
        assertTrue("rms medido = ${g.rms}", g.rms >= 60)
        assertFalse("fala fraca jamais vira silêncio", LiveEnergyGate.isDigitalSilence(g))
    }

    @Test
    fun `musica tom e ruido NAO sao descartados`() {
        assertFalse(LiveEnergyGate.isDigitalSilence(gate(pcmOf(sine(16000, amp = 300)))))
        val noisy = IntArray(16000) { i -> sine(16000, 2000)[i] + speechLike(16000, 40.0, 120)[i] }
        assertFalse(LiveEnergyGate.isDigitalSilence(gate(pcmOf(noisy.map { it.coerceIn(-32000, 32000) }.toIntArray()))))
    }

    @Test
    fun `R7 - qualquer amostra nao nula passa - nenhum limiar decide por rms pico`() {
        // 1) amostra 1 UMA vez: abaixo dos DOIS limiares antigos → passa ainda assim
        val single = zeros(16000)
        single[8000] = 1
        val g1 = gate(pcmOf(single))
        assertEquals(1, g1.peak)
        assertFalse("uma amostra não nula jamais é silêncio digital", LiveEnergyGate.isDigitalSilence(g1))
        // 2) valor -32768 (0x8000): passa e o pico é medido 32768 (sem overflow)
        val neg = zeros(16000)
        neg[8000] = -32768
        val g2 = gate(pcmOf(neg))
        assertEquals(32768, g2.peak)
        assertFalse("-32768 passa", LiveEnergyGate.isDigitalSilence(g2))
        // 3) valor 319 com rms ~0 (o caso antigo que era descartado): passa
        val spike2 = zeros(16000)
        spike2[8000] = 319
        val g3 = gate(pcmOf(spike2))
        assertEquals(319, g3.peak)
        assertFalse("319 abaixo do pico antigo (320) NÃO é mais descartável", LiveEnergyGate.isDigitalSilence(g3))
    }

    @Test
    fun `intervalos compostos - so o trecho 100 por cento digital e descartado`() {
        // [zeros 1s][música 1s][zeros 1s][fala fraca 1s] medido como UM chunk
        // de 4 s (como o pipeline mede): não é silêncio.
        val mixed = zeros(16000) + sine(16000, 300) + zeros(16000) + speechLike(16000, 72.0, 590)
        assertFalse(LiveEnergyGate.isDigitalSilence(gate(pcmOf(mixed))))
        // o chunk final 100% de silêncio digital (típico de cauda) é descartado
        assertTrue(LiveEnergyGate.isDigitalSilence(gate(pcmOf(zeros(32000)))))
    }
}
