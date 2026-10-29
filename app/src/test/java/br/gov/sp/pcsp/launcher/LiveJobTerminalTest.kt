package br.gov.sp.pcsp.launcher

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * R8/A1 — TERMINAL UNIVERSAL DO JOB (cancel-open e afins).
 *
 * Contrato exigido (o que o analisador cobra nos logs do device):
 *  - EXATAMENTE um `live_terminal` por job iniciado, MESMO quando o pipeline
 *    nunca existiu (cancel/erro durante a carga);
 *  - identidade IMMUTABLE: um job sucessor não altera o carimbo do terminal do
 *    job anterior;
 *  - contadores NUNCA inventados para pipeline inexistente nem tomados do
 *    sucessor (zero quando o pipeline não existiu; do retrato quando existiu);
 *  - cancel continua cancel, erro continua erro (o primeiro desfecho vence);
 *  - payload JSON VÁLIDO (razões com quotes/newlines não quebram o evento).
 */
class LiveJobTerminalTest {

    private fun identity(job: Long, gen: Long = 7L) =
        LiveJobIdentity(runId = "p1t9", activityInstanceId = 3, jobId = job, generation = gen)

    private fun summary(
        cancelled: Boolean = false,
        okSamples: Long = 9_000,
        unique: Long = 32_000,
        contextual: Long = 8_000,
        failed: Long = 0,
    ) = LiveTerminalSummary(
        cancelled = cancelled,
        durationMs = 1_000,
        capturedSamples = 64_000,
        processedSamples = 64_000,
        okSamples = okSamples,
        silenceSamples = 0,
        failedSamples = failed,
        cancelledSamples = 0,
        lostSamples = 0,
        lateLostSamples = 0,
        oddBytesDropped = 0,
        sourceFailure = null,
        storageFailure = null,
        sourceStalled = false,
        readerReason = "eof",
        uniqueSamples = unique,
        contextualSamples = contextual,
        successChunks = 2,
        noSpeechChunks = 0,
        failedChunks = 0,
        cancelledChunks = 0,
        digitalZeroSkippedChunks = 0,
        lastError = null,
        effective = "fixed 4+4s",
        listenerErrors = 0,
        identity = "act3.job1.gen7",
    )

    private class Sink {
        val events = Collections.synchronizedList(ArrayList<Triple<String, LiveJobIdentity, String>>())

        fun emit(name: String, identity: LiveJobIdentity, fields: String) {
            events.add(Triple(name, identity, fields))
        }

        fun only(): JSONObject {
            assertEquals("exatamente UM evento emitido", 1, events.size)
            assertEquals("live_terminal", events[0].first)
            return JSONObject("{" + events[0].third + "}")
        }
    }

    // ------------------------------------------------------------------ A1.1

    @Test
    fun `cancel antes da carga - um terminal com contadores zero e pipeline=false`() {
        val sink = Sink()
        val emitter = LiveJobTerminalEmitter(identity(1), sink::emit)

        val t = emitter.emit(
            outcome = LiveJobOutcome.CANCELLED,
            reason = "cancel durante a carga: destroy da Activity",
            phase = LiveJobPhase.LOAD,
            durationMs = 320,
            decodeWindows = 0,
            decodeWallMs = 0L,
            summary = null,
        )

        assertNotNull(t)
        val j = sink.only()
        assertEquals("cancelado", j.getString("verdict"))
        assertTrue(j.getBoolean("cancelled"))
        assertFalse("pipeline nunca existiu", j.getBoolean("pipeline"))
        assertEquals("carga", j.getString("phase"))
        assertEquals(0, j.getInt("decode_windows"))
        assertEquals(0L, j.getLong("decode_wall_ms"))
        assertEquals(0L, j.getLong("unique_samples"))
        assertEquals(0L, j.getLong("contextual_samples"))
        assertEquals(0L, j.getLong("ok_samples"))
        assertEquals("act3.job1.gen7", j.getString("identity"))
        assertEquals("cancelled", j.getString("outcome"))
        assertTrue("emit marcado", emitter.emitted)
    }

    @Test
    fun `emit e idempotente - cancel continua cancel mesmo com erro posterior`() {
        val sink = Sink()
        val emitter = LiveJobTerminalEmitter(identity(1), sink::emit)

        assertNotNull(
            emitter.emit(LiveJobOutcome.CANCELLED, "cancel antes da carga", LiveJobPhase.LOAD, 100, 0, 0L, null)
        )
        // Segunda emissão (ex.: teardown/erro tardio) NÃO escreve nada.
        assertNull(
            emitter.emit(LiveJobOutcome.ERROR, "erro tardio", LiveJobPhase.TEARDOWN, 200, 0, 0L, null)
        )
        val j = sink.only()
        assertEquals("cancelado", j.getString("verdict"))
        assertTrue(j.getBoolean("cancelled"))
    }

    @Test
    fun `erro continua erro - carga recusada nao vira cancelado`() {
        val sink = Sink()
        val emitter = LiveJobTerminalEmitter(identity(2), sink::emit)

        assertNotNull(
            emitter.emit(
                LiveJobOutcome.ERROR,
                "carga recusada: não consegui carregar o modelo com Vulkan",
                LiveJobPhase.LOAD, 900, 0, 0L, null,
            )
        )
        assertNull(emitter.emit(LiveJobOutcome.CANCELLED, "cancel tardio", LiveJobPhase.TEARDOWN, 950, 0, 0L, null))
        val j = sink.only()
        assertEquals("erro", j.getString("verdict"))
        assertFalse(j.getBoolean("cancelled"))
        assertFalse(j.getBoolean("pipeline"))
        assertTrue(j.getString("reason").startsWith("carga recusada"))
    }

    // ------------------------------------------------------------------ A1.2

    @Test
    fun `sucesso com pipeline - contadores REAIS do job e classificacao do retrato`() {
        val sink = Sink()
        val emitter = LiveJobTerminalEmitter(identity(4), sink::emit)
        val s = summary(cancelled = false, okSamples = 12_000, unique = 48_000, contextual = 16_000)

        val t = emitter.emit(
            outcome = LiveJobTerminalEmitter.classify(s),
            reason = s.statusLine(),
            phase = LiveJobPhase.TEARDOWN,
            durationMs = 5_000,
            decodeWindows = 3,
            decodeWallMs = 1_234,
            summary = s,
        )

        assertEquals(LiveJobOutcome.SUCCESS, t!!.outcome)
        val j = sink.only()
        assertEquals("sucesso", j.getString("verdict"))
        assertFalse(j.getBoolean("cancelled"))
        assertTrue(j.getBoolean("pipeline"))
        assertEquals(3, j.getInt("decode_windows"))
        assertEquals(1_234L, j.getLong("decode_wall_ms"))
        assertEquals(48_000L, j.getLong("unique_samples"))
        assertEquals(16_000L, j.getLong("contextual_samples"))
        assertEquals(12_000L, j.getLong("ok_samples"))
    }

    @Test
    fun `cancel durante a captura - retrato marcado cancelado permanece cancelado`() {
        val sink = Sink()
        val emitter = LiveJobTerminalEmitter(identity(5), sink::emit)
        val s = summary(cancelled = true, okSamples = 4_800, unique = 32_000, contextual = 0)

        val t = emitter.emit(
            LiveJobTerminalEmitter.classify(s), "cancel/stop do job", LiveJobPhase.TEARDOWN,
            2_222, 1, 700, s,
        )

        assertEquals(LiveJobOutcome.CANCELLED, t!!.outcome)
        val j = sink.only()
        assertEquals("cancelado", j.getString("verdict"))
        assertTrue(j.getBoolean("cancelled"))
        assertTrue(j.getBoolean("pipeline"))
        assertEquals(1, j.getInt("decode_windows"))
        assertEquals(32_000L, j.getLong("unique_samples"))
    }

    // ------------------------------------------------------------------ A1.3

    @Test
    fun `excecao antes do pipeline - terminal presente exatamente uma vez`() {
        val sink = Sink()
        val emitter = LiveJobTerminalEmitter(identity(6), sink::emit)

        val t = emitter.emit(
            LiveJobOutcome.ERROR,
            "exceção no pipeline: IllegalStateException: AudioRecord falhou",
            LiveJobPhase.CAPTURE, 42, 0, 0L, null,
        )
        assertNotNull(t)
        assertEquals(1, sink.events.size)
        val j = sink.only()
        assertEquals("erro", j.getString("verdict"))
        assertEquals("captura", j.getString("phase"))
        assertEquals(0L, j.getLong("lost_samples"))
    }

    @Test
    fun `identidade IMMUTABLE - sucessor nao altera o carimbo do job anterior`() {
        val sinkA = Sink()
        val sinkB = Sink()
        val job1 = LiveJobTerminalEmitter(identity(1), sinkA::emit)
        val job2 = LiveJobTerminalEmitter(identity(2, gen = 8L), sinkB::emit)

        // Job 2 assume PRIMEIRO; a saída TARDIA do job 1 chega depois.
        job2.emit(LiveJobOutcome.CANCELLED, "job 2 cancelado", LiveJobPhase.LOAD, 10, 0, 0L, null)
        val t1 = job1.emit(LiveJobOutcome.CANCELLED, "job 1 cancelado", LiveJobPhase.LOAD, 20, 0, 0L, null)

        assertEquals(1L, t1!!.identity.jobId)
        assertEquals("act3.job1.gen7", sinkA.only().getString("identity"))
        assertEquals("act3.job2.gen8", sinkB.only().getString("identity"))
        // Os ids do carimbo do evento também são os do PRÓPRIO job.
        assertEquals(1L, sinkA.events[0].second.jobId)
        assertEquals(2L, sinkB.events[0].second.jobId)
    }

    @Test
    fun `exatamente um terminal por job sob concorrencia`() {
        val sink = Sink()
        val emitter = LiveJobTerminalEmitter(identity(9), sink::emit)
        val threads = 16
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val winners = AtomicInteger(0)
        val started = AtomicInteger(0)
        val errors = java.util.Collections.synchronizedList(ArrayList<Throwable>())

        repeat(threads) { i ->
            Thread {
                try {
                    started.incrementAndGet()
                    start.await()
                    val outcome = if (i % 2 == 0) LiveJobOutcome.CANCELLED else LiveJobOutcome.ERROR
                    if (emitter.emit(outcome, "corrida $i", LiveJobPhase.LOAD, i.toLong(), 0, 0L, null) != null) {
                        winners.incrementAndGet()
                    }
                } catch (t: Throwable) {
                    errors.add(t)
                } finally {
                    done.countDown()
                }
            }.start()
        }
        start.countDown()
        assertTrue("threads iniciaram ($started)", done.await(30, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue("sem exceções nas threads: ${errors.firstOrNull()}", errors.isEmpty())

        assertEquals("somente uma emissão venceu", 1, winners.get())
        assertEquals("somente um evento escrito", 1, sink.events.size)
    }

    @Test
    fun `terminal sem pipeline nao inventa contadores nem mesmo se passados`() {
        val sink = Sink()
        val emitter = LiveJobTerminalEmitter(identity(10), sink::emit)

        emitter.emit(LiveJobOutcome.ERROR, "erro", LiveJobPhase.CAPTURE, 5, 7, 999L, null)
        val j = sink.only()
        assertEquals("decode_windows forçado a 0 sem pipeline", 0, j.getInt("decode_windows"))
        assertEquals("decode_wall_ms forçado a 0 sem pipeline", 0L, j.getLong("decode_wall_ms"))
    }

    @Test
    fun `payload e JSON valido mesmo com quotes newline e umlaut na razao`() {
        val sink = Sink()
        val emitter = LiveJobTerminalEmitter(identity(11), sink::emit)
        val nasty = "falha \"não\" esperada\ncom quebra de linha — amostras=123"

        emitter.emit(LiveJobOutcome.ERROR, nasty, LiveJobPhase.DECODE, 77, 0, 0L, null,
            extraFields = "\"idx_peak\":5,\"first_partial_ms\":-1")
        val j = sink.only()
        // O JSON é válido e a razão preserva o conteúdo (sem newline cru).
        assertTrue(j.getString("reason").contains("não"))
        assertFalse(j.getString("reason").contains("\n"))
        assertEquals(5, j.getInt("idx_peak"))
        assertEquals(-1L, j.getLong("first_partial_ms"))
    }

    @Test
    fun `terminal encerra com identidade do produtor mesmo sem sucessor`() {
        val sink = Sink()
        val emitter = LiveJobTerminalEmitter(identity(12, gen = -3L), sink::emit)
        val t = emitter.emit(LiveJobOutcome.NO_SPEECH, "sem fala", LiveJobPhase.TEARDOWN, 400, 0, 0L, null)
        assertEquals("sem-fala", t!!.verdict)
        assertEquals(-3L, sink.events[0].second.generation)
    }

    @Test
    fun `listener recebe o terminal uma unica vez`() {
        val sink = Sink()
        val count = AtomicInteger(0)
        val emitter = LiveJobTerminalEmitter(identity(13), sink::emit) { count.incrementAndGet() }
        emitter.emit(LiveJobOutcome.CANCELLED, "a", LiveJobPhase.LOAD, 1, 0, 0L, null)
        emitter.emit(LiveJobOutcome.ERROR, "b", LiveJobPhase.LOAD, 2, 0, 0L, null)
        assertEquals(1, count.get())
    }
}
