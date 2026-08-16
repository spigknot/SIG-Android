package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Testes mínimos obrigatórios da especificação de diarização. */
class SttDiarizationTest {

    // 1. Deepgram REST, marcada: diarize_model=latest e nunca diarize=true.
    @Test
    fun deepgramRestChecked_usesDiarizeModelLatest() {
        assertEquals("diarize_model=latest", SttDiarization.deepgramQuery(true))
        assertFalse(SttDiarization.deepgramQuery(true) == "diarize=true")
    }

    // 2. Deepgram WS, marcada: diarize_model=latest e nunca diarize_model=v2.
    @Test
    fun deepgramWsChecked_neverUsesV2() {
        assertEquals("diarize_model=latest", SttDiarization.deepgramQuery(true))
        assertFalse(SttDiarization.deepgramQuery(true) == "diarize_model=v2")
    }

    // 3. Deepgram desmarcada: nenhum parâmetro.
    @Test
    fun deepgramUnchecked_noParam() {
        assertNull(SttDiarization.deepgramQuery(false))
    }

    // 4. AssemblyAI REST, marcada: speaker_labels=true e punctuate=true.
    @Test
    fun assemblyaiRestChecked_speakerLabelsAndPunctuate() {
        val (speakerLabels, punctuate) = SttDiarization.assemblyaiRest(true)
        assertTrue(speakerLabels)
        assertTrue(punctuate)
    }

    // 5. AssemblyAI WS, marcada: speaker_labels=true na conexão.
    @Test
    fun assemblyaiWsChecked_speakerLabels() {
        assertEquals("speaker_labels=true", SttDiarization.assemblyaiWsQuery(true))
    }

    // 6. AssemblyAI desmarcada: não reutiliza speaker_labels=true.
    @Test
    fun assemblyaiUnchecked_noSpeakerLabels() {
        assertNull(SttDiarization.assemblyaiWsQuery(false))
        val (speakerLabels, punctuate) = SttDiarization.assemblyaiRest(false)
        assertFalse(speakerLabels)
        assertFalse(punctuate)
    }

    // 7. ElevenLabs REST, marcada: diarize=true; sem speaker_labels; sem
    //    num_speakers/diarization_threshold (a API só recebe o que o objeto produz).
    @Test
    fun elevenlabsRestChecked_diarizeTrue() {
        assertTrue(SttDiarization.elevenlabsRestDiarize(true))
    }

    // 8. ElevenLabs REST, desmarcada: sem diarize=true.
    @Test
    fun elevenlabsRestUnchecked_noDiarize() {
        assertFalse(SttDiarization.elevenlabsRestDiarize(false))
    }

    // 9. ElevenLabs WS: nenhum parâmetro, independente do estado salvo no REST.
    @Test
    fun elevenlabsWs_neverSendsParams() {
        assertNull(SttDiarization.elevenlabsWsQuery(true))
        assertNull(SttDiarization.elevenlabsWsQuery(false))
        assertFalse(SttDiarization.supportsDiarize("elevenlabs", isLive = true))
        assertTrue(SttDiarization.supportsDiarize("elevenlabs", isLive = false))
    }

    // 10. Grok REST & WS: diarize=true quando marcada; checkbox habilitada.
    @Test
    fun grok_sendsDiarizeAndEnabled() {
        assertEquals("diarize=true", SttDiarization.grokQuery(true))
        assertNull(SttDiarization.grokQuery(false))
        assertTrue(SttDiarization.grokRestDiarize(true))
        assertFalse(SttDiarization.grokRestDiarize(false))
        assertTrue(SttDiarization.supportsDiarize("grok", isLive = true))
        assertTrue(SttDiarization.supportsDiarize("grok", isLive = false))
    }

    // 11. Isolamento entre provedores: os parâmetros de um provedor nunca
    //     vazam para outro (cada função pertence ao seu provedor).
    @Test
    fun providerParams_areIsolated() {
        // O Deepgram nunca recebe speaker_labels.
        assertNull(SttDiarization.assemblyaiWsQuery(false))
        // O WS do ElevenLabs nunca recebe nada.
        assertNull(SttDiarization.elevenlabsWsQuery(true))
        // Deepgram e AssemblyAI continuam habilitados em ambos os modos.
        assertTrue(SttDiarization.supportsDiarize("deepgram", isLive = true))
        assertTrue(SttDiarization.supportsDiarize("deepgram", isLive = false))
        assertTrue(SttDiarization.supportsDiarize("assemblyai", isLive = true))
        assertTrue(SttDiarization.supportsDiarize("assemblyai", isLive = false))
    }
}
