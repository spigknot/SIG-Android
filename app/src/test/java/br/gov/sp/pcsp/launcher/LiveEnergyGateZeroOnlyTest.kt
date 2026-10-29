package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * R7/A2 — REGRESSÕES do portão SÓ-ZERO-DIGITAL (reprova o estado da R6).
 *
 * O contrato antigo (R6: rms<20 E pico<320 ⇒ descarta) descartava PCM NÃO
 * nulo. Aqui provamos, caso a caso e por INTERVALO, que:
 *  - amostra não nula abaixo dos DOIS limiares antigos atravessa;
 *  - fala fraca local atravessa;
 *  - onset curto cercado de silêncio atravessa;
 *  - -32768 e valores extremos atravessam (sem overflow no pico);
 *  - zero digital puro continua sendo o ÚNICO caso pulado;
 *  - fragmento inválido (bytes ímpares / vazio) NÃO vira silêncio;
 *  - TODO chunk com qualquer byte não nulo chega ao worker (modelo/VAD) no
 *    pipeline REAL — e os únicos chunks pulados são os 100% zero.
 */
class LiveEnergyGateZeroOnlyTest {

    private fun pcmOf(samples: IntArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            out[i * 2] = (samples[i] and 0xFF).toByte()
            out[i * 2 + 1] = ((samples[i] shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun put(out: ByteArray, sampleIndex: Int, value: Int) {
        out[sampleIndex * 2] = (value and 0xFF).toByte()
        out[sampleIndex * 2 + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun tmpDir(): File =
        File(System.getProperty("java.io.tmpdir"), "gate-zero-r7-" + System.nanoTime()).apply { mkdirs() }

    // ------------------------------------------------------------- regressões

    @Test
    fun `regressao R6 - amostra nao zero abaixo dos DOIS limiares antigos nao vira silencio`() {
        // 1 amostra = 1 num chunk de 16000 amostras: rms≈0.0079, pico=1.
        // No contrato R6 (rms<20 E pico<320) isto era DESCARTADO — aqui tem de passar.
        val pcm = ByteArray(32_000)
        put(pcm, 8_000, 1)
        val e = LiveEnergyGate.measure(pcm)
        assertTrue("rms abaixo do limiar antigo (${e.rms})", e.rms < LiveEnergyGate.LEGACY_RMS_SILENCE_THRESHOLD)
        assertTrue("pico abaixo do limiar antigo (${e.peak})", e.peak < LiveEnergyGate.LEGACY_PEAK_RESCUE_THRESHOLD)
        assertFalse("amostra não nula NÃO pode virar silêncio", LiveEnergyGate.isDigitalSilence(e))
    }

    @Test
    fun `regressao - fala fraca local (pico 25) atravessa`() {
        val pcm = ByteArray(32_000)
        for (i in 0 until 16_000) put(pcm, i, if (i % 50 == 0) 25 else 3)
        val e = LiveEnergyGate.measure(pcm)
        assertTrue("pico bem abaixo do limiar antigo", e.peak < LiveEnergyGate.LEGACY_PEAK_RESCUE_THRESHOLD)
        assertFalse(e.digitalZero)
        assertFalse(LiveEnergyGate.isDigitalSilence(e))
    }

    @Test
    fun `regressao - onset curto cercado de silencio atravessa`() {
        val pcm = ByteArray(32_000) // 1 s de zeros
        // onset de 12 amostras (0,75 ms), amplitude fraca 20..53: NÃO pode sumir.
        for (i in 0 until 12) put(pcm, 4_000 + i, 20 + i * 3)
        val e = LiveEnergyGate.measure(pcm)
        assertFalse("onset curto jamais some no silêncio", LiveEnergyGate.isDigitalSilence(e))
        // e o chunk 100% zero continua fechando o portão
        assertTrue(LiveEnergyGate.isDigitalSilence(LiveEnergyGate.measure(ByteArray(32_000))))
    }

    @Test
    fun `regressao - -32768 e valores extremos atravessam sem overflow no pico`() {
        val neg = ByteArray(32_000)
        put(neg, 12_345, -32768)
        val eNeg = LiveEnergyGate.measure(neg)
        assertEquals(32_768, eNeg.peak)
        assertFalse("-32768 passa", LiveEnergyGate.isDigitalSilence(eNeg))

        val maxPos = ByteArray(32_000)
        put(maxPos, 7, 32_767)
        val eMax = LiveEnergyGate.measure(maxPos)
        assertEquals(32_767, eMax.peak)
        assertFalse("32767 passa", LiveEnergyGate.isDigitalSilence(eMax))
    }

    @Test
    fun `regressao - fragmento invalido (impar ou vazio) NAO vira silencio`() {
        // 2001 bytes = 1000 amostras zero + 1 byte: fragmento inválido (ímpar).
        val odd = ByteArray(2_001)
        val eOdd = LiveEnergyGate.measure(odd)
        assertFalse("bytes ímpares não são PCM válido", eOdd.validPcm)
        assertFalse("entrada inválida NÃO pode ser descartada como silêncio", LiveEnergyGate.isDigitalSilence(eOdd))

        val empty = ByteArray(0)
        val eEmpty = LiveEnergyGate.measure(empty)
        assertFalse(eEmpty.validPcm)
        assertFalse("vazio não é silêncio digital decidível", LiveEnergyGate.isDigitalSilence(eEmpty))
    }

    @Test
    fun `prova intervalar - amostra nao nula em QUALQUER posicao do chunk faz o chunk seguir`() {
        // Varre TODAS as posições de um chunk de 1000 amostras: em nenhuma o
        // chunk pode ser classificado como silêncio digital.
        val n = 1_000
        for (pos in 0 until n) {
            val pcm = ByteArray(n * 2)
            put(pcm, pos, if (pos % 2 == 0) 1 else -2)
            assertFalse("posição $pos não pode ser pulada", LiveEnergyGate.isDigitalSilence(LiveEnergyGate.measure(pcm)))
        }
    }

    // ------------------------------------------- prova no pipeline REAL

    @Test
    fun `pipeline - todo chunk com byte nao nulo chega ao worker, so zero-digital e pulado`() {
        val chunkSamples = 1_000
        val chunkBytes = chunkSamples * 2
        // 8 chunks: [zeros][fala fraca][zeros][fala+extremo][zeros][rajao 319][zeros][tom no fim]
        val pcm = pcmOf(IntArray(chunkSamples) { 0 }) +                       // 0: todo zero → pulado
            pcmOf(IntArray(chunkSamples) { i -> if (i % 7 == 0) 24 else 5 }) + // 1: fala fraca → modelo
            pcmOf(IntArray(chunkSamples) { 0 }) +                              // 2: todo zero → pulado
            pcmOf(IntArray(chunkSamples) { i -> if (i == 500) -32768 else 0 }) + // 3: 1 amostra extrema → modelo
            pcmOf(IntArray(chunkSamples) { 0 }) +                              // 4: todo zero → pulado
            pcmOf(IntArray(chunkSamples) { i -> if (i == 999) 319 else 0 }) +  // 5: 319 (limiar antigo) → modelo
            pcmOf(IntArray(chunkSamples) { 0 }) +                              // 6: todo zero → pulado
            pcmOf(IntArray(chunkSamples) { i -> if (i % 2 == 0) 9 else -9 })   // 7: não nulo → modelo

        val delivered = CopyOnWriteArrayList<Int>()
        val skipped = CopyOnWriteArrayList<Int>()
        val worker = object : LivePcmPipeline.Worker<String?> {
            override fun process(chunk: LivePcmPipeline.Chunk): String? {
                // MESMA decisão do worker de produção (Activity): só pula quando
                // o portão só-zero-digital fecha; o resto segue p/ o modelo.
                val energy = LiveEnergyGate.measure(chunk.pcm)
                if (LiveEnergyGate.isDigitalSilence(energy)) {
                    skipped.add(chunk.index)
                    return null
                }
                delivered.add(chunk.index)
                return "modelo:${chunk.index}"
            }

            override fun isSilence(result: String?): Boolean = result == null
        }

        val dir = tmpDir()
        val pipeline = LivePcmPipeline(chunkSamples = chunkSamples, worker = worker, spoolDir = dir)
        pipeline.start()
        // feed aceita no máximo `chunkBytes` por chamada (contrato do pipeline):
        // alimentamos chunk a chunk, como o leitor real faz.
        var acceptedTotal = 0
        for (i in 0 until 8) {
            acceptedTotal += pipeline.feed(pcm.copyOfRange(i * chunkBytes, (i + 1) * chunkBytes), chunkBytes)
        }
        assertEquals("todo o PCM foi aceito (nada descartado no feed)", pcm.size, acceptedTotal)
        pipeline.requestStop()
        assertTrue(pipeline.awaitFinished(10_000))

        val counters = pipeline.counters()
        assertTrue("contabilidade balanceada", counters.isBalanced())
        assertEquals("nada perdido no spool", 0, counters.lostSamples)
        assertEquals("4 chunks foram ao modelo", listOf(1, 3, 5, 7), delivered.toList())
        assertEquals("4 chunks 100% zero foram pulados", listOf(0, 2, 4, 6), skipped.toList())
        // Todo chunk com QUALQUER byte não nulo: verificação independente por bytes.
        for (i in 0 until 8) {
            val bytes = pcm.copyOfRange(i * chunkBytes, (i + 1) * chunkBytes)
            val anyNonZero = bytes.any { it != 0.toByte() }
            if (anyNonZero) {
                assertTrue("chunk $i tem byte não nulo e TEM de chegar ao worker", delivered.contains(i))
                assertFalse("chunk $i não pode ser pulado", skipped.contains(i))
            } else {
                assertTrue("chunk $i é 100% zero e é o único caso pulável", skipped.contains(i))
            }
        }
        dir.deleteRecursively()
    }
}
