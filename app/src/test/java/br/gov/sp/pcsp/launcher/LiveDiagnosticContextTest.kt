package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveDiagnosticContextTest {

    @Test
    fun `correlacao preserva provider e estados em ordem`() {
        val context = LiveDiagnosticContext("live-test-123", "Deepgram")

        context.recordState("connecting")
        context.recordState("CONNECTED")
        context.recordState("reconnecting")
        context.recordState("reconnected")

        assertEquals(
            listOf("CONNECTING", "CONNECTED", "RECONNECTING", "RECONNECTED"),
            context.stateSequence(),
        )
        val correlation = context.correlation()
        assertEquals("live-test-123", correlation["live_run_id"])
        assertEquals("Deepgram", correlation["provider"])
        assertEquals(
            "CONNECTING>CONNECTED>RECONNECTING>RECONNECTED",
            correlation["state_sequence"],
        )
        assertEquals("true", correlation["recovery_observed"])
        assertEquals("NONE", correlation["terminal_outcome"])
        assertTrue(context.correlationText().contains("state_sequence=CONNECTING>CONNECTED>RECONNECTING>RECONNECTED"))
    }

    @Test
    fun `estado vazio nao entra na sequencia nem na correlacao`() {
        val context = LiveDiagnosticContext("live-test-456", "Grok")

        context.recordState(" ")

        assertTrue(context.stateSequence().isEmpty())
        assertTrue(!context.correlationText().contains("null"))
    }

    @Test
    fun `finalizacao e falha terminal ficam explicitas`() {
        val completed = LiveDiagnosticContext("live-test-789", "Grok")
        completed.recordState("CONNECTING")
        completed.recordState("CONNECTED")
        completed.recordState("FINALIZING")
        completed.recordState("DONE")

        assertEquals("DONE", completed.terminalOutcome())
        assertEquals("DONE", completed.correlation()["terminal_outcome"])
        assertTrue(completed.correlationText().contains("recovery_observed=false"))

        val failed = LiveDiagnosticContext("live-test-999", "Deepgram")
        failed.recordState("CONNECTING")
        failed.recordState("RECONNECTING")
        failed.recordState("AUDIO_LOST")

        assertEquals("AUDIO_LOST", failed.terminalOutcome())
        assertEquals("AUDIO_LOST", failed.correlation()["terminal_outcome"])
        assertTrue(failed.correlationText().contains("terminal_outcome=AUDIO_LOST"))
    }
}
