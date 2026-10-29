package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R4/C1 (contrato) + R7/A2 (portão SÓ-ZERO-DIGITAL) — caminho ao vivo:
 * o portão NÃO decide fala por RMS/pico (fala fraca -30 dB atravessa); só
 * descarta PCM VÁLIDO integralmente zero; contadores por desfecho e retrato
 * terminal (que a UI não pode sobrescrever com "finalizada" genérica).
 */
class LiveJobSupportTest {

    // ---------------------------------------------------------------- gate

    private fun zeros(samples: Int) = ByteArray(samples * 2)

    private fun weakSpeechLike(samples: Int): ByteArray {
        // Proxy sintético do fixture n06_fala_baixa_30db (rms≈72,4 / pico=590):
        // fala fraca de -30 dB que NÃO pode ser decidida como silêncio.
        val out = ByteArray(samples * 2)
        for (i in 0 until samples) {
            val s = if (i % 100 == 0) 590 else 30
            out[i * 2] = (s and 0xFF).toByte()
            out[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return out
    }

    @Test
    fun `portao R7 - so PCM valido todo zero fecha - rms pico ficam de diagnostico`() {
        val digital = LiveEnergyGate.measure(zeros(16_000))
        assertEquals(0.0, digital.rms, 1e-9)
        assertEquals(0, digital.peak)
        assertTrue(digital.validPcm)
        assertTrue("zero absoluto é silêncio", LiveEnergyGate.isDigitalSilence(digital))

        // R7/A2: rms=10 / pico=10 (abaixo dos DOIS limiares antigos) NÃO é mais
        // descartável — é PCM não nulo e vai seguir para o modelo/VAD. Os
        // valores continuam medidos, só como diagnóstico.
        val almost = LiveEnergyGate.measure(ByteArray(32_000).also { for (i in it.indices step 2) { it[i] = 10; it[i + 1] = 0 } })
        assertEquals(10.0, almost.rms, 1e-6)
        assertEquals(10, almost.peak)
        assertFalse("pcm não nulo abaixo dos limiares antigos NÃO vira silêncio", LiveEnergyGate.isDigitalSilence(almost))
    }

    @Test
    fun `portao - fala fraca -30dB do fixture n06 atravessa - RMS nao decide fala`() {
        val weak = LiveEnergyGate.measure(weakSpeechLike(16_000))
        assertTrue("proxy do n06 tem rms >> limiar histórico (${weak.rms})", weak.rms > LiveEnergyGate.LEGACY_RMS_SILENCE_THRESHOLD)
        assertEquals(590, weak.peak)
        assertFalse("fala fraca NÃO pode virar silêncio de energia", LiveEnergyGate.isDigitalSilence(weak))
    }

    @Test
    fun `portao - transiente com rms baixo segue para o modelo`() {
        // rms baixo, mas com um transiente no meio: onset/offset tem de passar
        // (o modelo decide) — nenhum limiar de pico decide por ele.
        val out = ByteArray(16_000 * 2)
        for (i in 0 until 16_000) {
            val s = if (i == 8_000) 1_500 else 4
            out[i * 2] = (s and 0xFF).toByte()
            out[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
        val e = LiveEnergyGate.measure(out)
        assertTrue("rms baixo (${e.rms})", e.rms < LiveEnergyGate.LEGACY_RMS_SILENCE_THRESHOLD)
        assertEquals(1_500, e.peak)
        assertFalse("transiente com rms baixo NÃO é silêncio", LiveEnergyGate.isDigitalSilence(e))
    }

    // --------------------------------------------------------------- tally

    @Test
    fun `tally - desfechos separados e zero-digital contado pelo motivo`() {
        val t = LiveChunkTally()
        t.count(LiveChunkResult.Success("texto"))
        t.count(LiveChunkResult.NoSpeech("zero-digital (64000 amostras todas 0; pcm valido=true)"))
        t.count(LiveChunkResult.NoSpeech("no_speech do modelo"))
        t.count(LiveChunkResult.Failed("status error"))
        t.count(LiveChunkResult.Cancelled("transcrição cancelada"))
        assertEquals(1, t.successChunks())
        assertEquals(2, t.noSpeechChunks())
        assertEquals(1, t.digitalZeroSkippedChunks())
        assertEquals(1, t.failedChunks())
        assertEquals(1, t.cancelledChunks())
    }

    // ----------------------------------------------------- retrato terminal

    private fun counters(
        captured: Long,
        processed: Long,
        ok: Long,
        silence: Long,
        failed: Long,
        cancelled: Long = 0,
        lost: Long = 0,
        lateLost: Long = 0,
        odd: Long = 0,
        sourceFailure: String? = null,
        storageFailure: String? = null,
        stalled: Boolean = false,
        workerError: String? = null,
        listenerErrors: Int = 0,
    ) = LivePcmCounters(
        producedSamples = captured + lost,
        capturedSamples = captured,
        enqueuedSamples = captured,
        processedSamples = processed,
        okSamples = ok,
        silenceSamples = silence,
        failedSamples = failed,
        cancelledSamples = cancelled,
        lostSamples = lost,
        lateLostSamples = lateLost,
        oddBytesDropped = odd,
        listenerErrors = listenerErrors,
        expectedSamples = captured + lost,
        spoolBytesPeak = 0,
        indexHighWatermark = 0,
        sourceEof = sourceFailure == null && !stalled,
        sourceStalled = stalled,
        storageFailure = storageFailure,
        sourceFailure = sourceFailure,
        workerError = workerError,
    )

    private fun summaryOf(
        cancelled: Boolean,
        c: LivePcmCounters,
        readerReason: String = "eof",
        tally: LiveChunkTally = LiveChunkTally(),
        lastError: String? = null,
    ) = LiveTerminalSummary.of(
        cancelled = cancelled,
        durationMs = 12_345L,
        counters = c,
        readerReason = readerReason,
        tally = tally,
        lastError = lastError,
        effective = "decode: beam=2 bestOf=2 timestamps=false vad=true",
    )

    @Test
    fun `terminal - sucesso sem perdas`() {
        val s = summaryOf(false, counters(captured = 64_000, processed = 64_000, ok = 64_000, silence = 0, failed = 0))
        assertEquals("sucesso", s.verdict)
        assertFalse(s.interrupted)
        assertFalse(s.hasLoss)
        assertTrue(s.statusLine().contains("beam=2"))
    }

    @Test
    fun `terminal - fonte falha com ZERO perdidas ainda e parcial e interrompido`() {
        val s = summaryOf(
            false,
            counters(
                captured = 6_400, processed = 6_400, ok = 6_400, silence = 0, failed = 0,
                sourceFailure = "exceção na fonte: s11-fonte-falha-injetada", 
            ),
            readerReason = "source-error",
        )
        assertEquals("parcial", s.verdict)
        assertTrue(s.interrupted)
        assertTrue(s.statusLine().contains("FONTE FALHOU"))
        assertTrue("nada de 'finalizada' genérica", !s.statusLine().contains("finalizada"))
    }

    @Test
    fun `terminal - byte impar descartado e perda explicita - parcial visivel`() {
        val s = summaryOf(false, counters(captured = 1_000, processed = 1_000, ok = 1_000, silence = 0, failed = 0, odd = 1))
        assertEquals("parcial", s.verdict)
        assertTrue(s.hasLoss)
        assertTrue(s.statusLine().contains("byteImparDescartado=1"))
    }

    @Test
    fun `terminal - cancelado tem precedencia e motivo de teardown`() {
        val s = summaryOf(true, counters(captured = 5_000, processed = 2_000, ok = 1_000, silence = 0, failed = 0, cancelled = 3_000))
        assertEquals("cancelado", s.verdict)
        assertTrue(s.statusLine().contains("teardown"))
    }

    @Test
    fun `terminal - falha de chunk sem amostras ok vira parcial com motivo`() {
        val c = counters(captured = 4_000, processed = 4_000, ok = 0, silence = 0, failed = 4_000, workerError = "worker falhou no chunk 1")
        // A Activity monta o retrato com lastError = counters.workerError.
        val s = summaryOf(false, c, lastError = c.workerError)
        assertEquals("parcial", s.verdict)
        assertTrue(s.statusLine().contains("ultimo erro: worker falhou no chunk 1"))
    }

    @Test
    fun `terminal - storage cheio e falha explicita`() {
        val s = summaryOf(false, counters(captured = 3_200, processed = 3_200, ok = 3_200, silence = 0, failed = 0, storageFailure = "spool cheio"))
        assertEquals("parcial", s.verdict)
        assertTrue(s.statusLine().contains("ARMAZENAMENTO FALHOU: spool cheio"))
    }

    @Test
    fun `terminal - listener com excecao vira parcial explicito`() {
        val c = counters(captured = 64_000, processed = 64_000, ok = 64_000, silence = 0, failed = 0, listenerErrors = 1)
        val s = summaryOf(false, c)
        assertEquals("parcial", s.verdict)
        assertTrue(s.statusLine().contains("listenerErrors=1"))
    }

    @Test
    fun `terminal - sem fala nenhuma`() {
        val s = summaryOf(false, counters(captured = 32_000, processed = 32_000, ok = 0, silence = 32_000, failed = 0))
        assertEquals("sem-fala", s.verdict)
    }
}
