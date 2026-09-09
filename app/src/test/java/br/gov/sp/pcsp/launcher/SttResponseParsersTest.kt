package br.gov.sp.pcsp.launcher

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contratos de parsing das respostas STT extraídos de RemoteSttActivity.
 * Se um destes testes quebrar, o comportamento do app mudou — não "ajuste"
 * a expectativa sem confirmar que a mudança era intencional.
 */
class SttResponseParsersTest {

    // ------------------------------------------------------------ Granite

    @Test
    fun parseResponseItems_objetoComText_usaTextENomeAusente() {
        val parsed = SttResponseParsers.parseResponseItems("""{"text":"olá mundo"}""")
        assertEquals(1, parsed.size)
        assertEquals("olá mundo", parsed[0].text)
        assertEquals(null, parsed[0].name)
    }

    @Test
    fun parseResponseItems_arrayDeObjetos_usaFilenameComoNome() {
        val parsed = SttResponseParsers.parseResponseItems(
            """[{"filename":"a.wav","text":"um"},{"filename":"b.wav","text":"dois"}]"""
        )
        assertEquals(listOf("a.wav", "b.wav"), parsed.map { it.name })
        assertEquals(listOf("um", "dois"), parsed.map { it.text })
    }

    @Test
    fun parseResponseItems_resultadosAninhados_saoDesembrulhados() {
        val parsed = SttResponseParsers.parseResponseItems(
            """{"results":[{"file":"x.wav","transcription":"texto x"}]}"""
        )
        assertEquals(1, parsed.size)
        assertEquals("x.wav", parsed[0].name)
        assertEquals("texto x", parsed[0].text)
    }

    @Test
    fun parseResponseItems_segments_concatenaTextoEMantemTimestamps() {
        val parsed = SttResponseParsers.parseResponseItems(
            """{"segments":[{"text":"bom dia","start":0.0,"end":1.5},{"text":"a todos","start":1.5,"end":3.0}]}"""
        )
        assertEquals(1, parsed.size)
        assertEquals("bom diaa todos", parsed[0].text)
        assertEquals(
            "[00:00:00.000 -> 00:00:01.500] bom dia\n[00:00:01.500 -> 00:00:03.000] a todos",
            parsed[0].timestampedText
        )
    }

    @Test
    fun parseResponseItems_textoPuro_eDevolvidoSemNome() {
        val parsed = SttResponseParsers.parseResponseItems("  transcrição crua  ")
        assertEquals(1, parsed.size)
        assertEquals("transcrição crua", parsed[0].text)
    }

    @Test
    fun parseResponseItems_vazioDevolveListaVazia() {
        assertTrue(SttResponseParsers.parseResponseItems("   ").isEmpty())
    }

    @Test
    fun parseResponseItems_jsonInvalido_caiNoFallbackTextoPuro() {
        val parsed = SttResponseParsers.parseResponseItems("{\"text\":")
        assertEquals(1, parsed.size)
        assertEquals("{\"text\":", parsed[0].text)
    }

    @Test
    fun matchTranscriptions_casaPeloNomeDoArquivo() {
        val parsed = listOf(
            SttResponseParsers.ParsedText("a.wav", "texto A"),
            SttResponseParsers.ParsedText("b.wav", "texto B"),
        )
        val uploads = listOf(
            SttResponseParsers.UploadKey(1, "b", "b.wav"),
            SttResponseParsers.UploadKey(0, "a", "a.wav"),
        )
        val matched = SttResponseParsers.matchTranscriptions(parsed, uploads)
        assertEquals(listOf(0, 1), matched.map { it.index })
        assertEquals(listOf("a", "b"), matched.map { it.itemName })
        assertEquals(listOf("texto A", "texto B"), matched.map { it.text })
    }

    @Test
    fun matchTranscriptions_casaPeloSufixoDoCaminho() {
        val parsed = listOf(SttResponseParsers.ParsedText("/tmp/upload/a.wav", "texto A"))
        val uploads = listOf(SttResponseParsers.UploadKey(0, "a", "a.wav"))
        val matched = SttResponseParsers.matchTranscriptions(parsed, uploads)
        assertEquals("texto A", matched.single().text)
    }

    @Test
    fun matchTranscriptions_semNome_casaPorPosicao() {
        val parsed = listOf(
            SttResponseParsers.ParsedText(null, "primeiro"),
            SttResponseParsers.ParsedText(null, "segundo"),
        )
        val uploads = listOf(
            SttResponseParsers.UploadKey(0, "a", "a.wav"),
            SttResponseParsers.UploadKey(1, "b", "b.wav"),
        )
        val matched = SttResponseParsers.matchTranscriptions(parsed, uploads)
        assertEquals(listOf("primeiro", "segundo"), matched.map { it.text })
    }

    @Test
    fun matchTranscriptions_uploadUnico_recebeOPrimeiroRestante() {
        val parsed = listOf(SttResponseParsers.ParsedText(null, "única"))
        val uploads = listOf(SttResponseParsers.UploadKey(3, "x", "x.wav"))
        val matched = SttResponseParsers.matchTranscriptions(parsed, uploads)
        assertEquals(3, matched.single().index)
        assertEquals("única", matched.single().text)
    }

    @Test
    fun matchTranscriptions_semItemCorrespondente_sobraVaiParaOUltimoPorPosicao() {
        val parsed = listOf(SttResponseParsers.ParsedText("outro.wav", "texto"))
        val uploads = listOf(
            SttResponseParsers.UploadKey(0, "a", "a.wav"),
            SttResponseParsers.UploadKey(1, "b", "b.wav"),
        )
        val matched = SttResponseParsers.matchTranscriptions(parsed, uploads)
        assertEquals(2, matched.size)
        // Fallback posicional: quando resta exatamente 1 item e falta 1 upload,
        // o item é atribuído ao upload daquela posição (o último).
        assertEquals(listOf("", "texto"), matched.map { it.text })
    }

    @Test
    fun matchTranscriptions_removeEspacosDoTexto() {
        val parsed = listOf(SttResponseParsers.ParsedText("a.wav", "  espaçado  "))
        val uploads = listOf(SttResponseParsers.UploadKey(0, "a", "a.wav"))
        assertEquals("espaçado", SttResponseParsers.matchTranscriptions(parsed, uploads).single().text)
    }

    @Test
    fun formatTimestamp_formataComHorasMinutosSegundosEMillis() {
        assertEquals("00:00:00.000", SttResponseParsers.formatTimestamp(0L))
        assertEquals("01:01:01.123", SttResponseParsers.formatTimestamp(3_661_123L))
        assertEquals("00:00:00.000", SttResponseParsers.formatTimestamp(-50L))
    }

    // ------------------------------------------------------------ Alibaba

    @Test
    fun formatAlibabaRestResponse_prefereOutputText() {
        val payload = JSONObject("""{"output":{"text":"  bom dia  ","choices":[]}}""")
        assertEquals("bom dia", SttResponseParsers.formatAlibabaRestResponse(payload))
    }

    @Test
    fun formatAlibabaRestResponse_usaChoicesQuandoSemText() {
        val payload = JSONObject("""{"output":{"choices":[{"message":{"content":"via choices"}}]}}""")
        assertEquals("via choices", SttResponseParsers.formatAlibabaRestResponse(payload))
    }

    @Test
    fun formatAlibabaRestResponse_contentEmArray_hojeDevolveOJsonCru() {
        // Comportamento ATUAL (suspeito de bug, preservado de propósito nesta
        // refatoração): optString em um array devolve a representação JSON.
        // Não "corrija" sem antes confirmar o contrato real do DashScope.
        val payload = JSONObject(
            """{"output":{"choices":[{"message":{"content":[{"text":"parte 1"},{"text":"parte 2"}]}}]}}"""
        )
        assertEquals(
            """[{"text":"parte 1"},{"text":"parte 2"}]""",
            SttResponseParsers.formatAlibabaRestResponse(payload)
        )
    }

    @Test
    fun formatAlibabaRestResponse_semOutputDevolveVazio() {
        assertEquals("", SttResponseParsers.formatAlibabaRestResponse(JSONObject("{}")))
    }

    @Test
    fun alibabaWsErrorMessage_mapeiaAuthThrottlingEGenerico() {
        val auth = JSONObject("""{"header":{"error_code":"InvalidApiKey"}}""")
        assertEquals(SttRequestBuilders.ALIBABA_AUTH_ERROR, SttResponseParsers.alibabaWsErrorMessage(auth))

        val throttling = JSONObject("""{"header":{"error_code":"Throttling","error_message":"slow down"}}""")
        assertEquals(SttRequestBuilders.ALIBABA_RATE_LIMIT_ERROR, SttResponseParsers.alibabaWsErrorMessage(throttling))

        val generic = JSONObject("""{"header":{"error_code":"BadRequest","error_message":"campo inválido"}}""")
        assertEquals("campo inválido", SttResponseParsers.alibabaWsErrorMessage(generic))

        val semMensagem = JSONObject("""{"header":{"error_code":"X"}}""")
        assertEquals("erro X do Alibaba", SttResponseParsers.alibabaWsErrorMessage(semMensagem))
    }

    // --------------------------------------------------------- Diarização

    @Test
    fun stripDiarizationLabels_removeRotulosEMantemLinhas() {
        val entrada = "Interlocutor 1: bom dia\nInterlocutor 2:  boa tarde  "
        assertEquals("bom dia\nboa tarde", SttResponseParsers.stripDiarizationLabels(entrada))
    }

    @Test
    fun formatGrokDiarizedTranscript_desligado_devolveFallback() {
        val payload = JSONObject("""{"words":[{"text":"oi","speaker":0}]}""")
        assertEquals(
            "fallback",
            SttResponseParsers.formatGrokDiarizedTranscript(payload, "fallback", diarizationEnabled = false)
        )
    }

    @Test
    fun formatGrokDiarizedTranscript_agrupaPorFalante() {
        val payload = JSONObject(
            """{"words":[
                {"text":"bom","speaker":0},{"text":"dia","speaker":0},
                {"text":"tudo","speaker":1},{"text":"bem","speaker":1}
            ]}"""
        )
        assertEquals(
            "Interlocutor 1: bom dia\nInterlocutor 2: tudo bem",
            SttResponseParsers.formatGrokDiarizedTranscript(payload, "fallback", diarizationEnabled = true)
        )
    }

    @Test
    fun formatGrokDiarizedTranscript_semWords_devolveFallback() {
        assertEquals(
            "fallback",
            SttResponseParsers.formatGrokDiarizedTranscript(JSONObject("{}"), "fallback", diarizationEnabled = true)
        )
    }

    @Test
    fun prefixMetamuseSpeaker_respeitaEstadoEDiarizacao() {
        val numeros = linkedMapOf<String, Int>()

        assertEquals(
            "texto cru",
            SttResponseParsers.prefixMetamuseSpeaker("texto cru", diarizationEnabled = false, currentSpeaker = "A", speakerNumbers = numeros)
        )
        assertEquals(
            "texto cru",
            SttResponseParsers.prefixMetamuseSpeaker("texto cru", diarizationEnabled = true, currentSpeaker = null, speakerNumbers = numeros)
        )
        assertEquals(
            "Interlocutor 1: texto cru",
            SttResponseParsers.prefixMetamuseSpeaker("texto cru", diarizationEnabled = true, currentSpeaker = "A", speakerNumbers = numeros)
        )
        assertEquals(1, numeros["A"])
        assertEquals(
            "Interlocutor 1: já prefixado",
            SttResponseParsers.prefixMetamuseSpeaker("Interlocutor 1: já prefixado", diarizationEnabled = true, currentSpeaker = "A", speakerNumbers = numeros)
        )
    }

    @Test
    fun formatMetamuseDiarizedTurns_numeraFalantesPorOrdemDeAparicao() {
        val turns = JSONArray(
            """[
                {"speaker":"B","transcript":"primeiro"},
                {"speaker":"A","transcript":"segundo"},
                {"speaker":"B","transcript":"terceiro"},
                {"transcript":"sem falante"}
            ]"""
        )
        assertEquals(
            "Interlocutor 1: primeiro\nInterlocutor 2: segundo\nInterlocutor 1: terceiro\nsem falante",
            SttResponseParsers.formatMetamuseDiarizedTurns(turns)
        )
    }

    @Test
    fun formatMetamuseDiarizedTurns_ignoraTurnosVazios() {
        val turns = JSONArray("""[{"speaker":"A","transcript":"  "},{"speaker":"A","transcript":"ok"}]""")
        assertEquals("Interlocutor 1: ok", SttResponseParsers.formatMetamuseDiarizedTurns(turns))
    }

    // ----------------------------------------------------------------- SSE

    @Test
    fun extractTextDelta_interpretaEnvelopesSSE() {
        assertEquals("olá", SttResponseParsers.extractTextDelta("data: olá"))
        assertEquals("", SttResponseParsers.extractTextDelta("data: [DONE]"))
        assertEquals("", SttResponseParsers.extractTextDelta("event: transcript.partial"))
        assertEquals("", SttResponseParsers.extractTextDelta("   "))
        assertEquals("linha crua", SttResponseParsers.extractTextDelta("linha crua"))
    }

    @Test
    fun extractTextDelta_leJSONComTextDeltaChoicesESegments() {
        assertEquals("a", SttResponseParsers.extractTextDelta("""{"text":"a"}"""))
        assertEquals("b", SttResponseParsers.extractTextDelta("""{"delta":"b"}"""))
        assertEquals(
            "c",
            SttResponseParsers.extractTextDelta("""{"choices":[{"delta":{"content":"c"}}]}""")
        )
        assertEquals(
            "d",
            SttResponseParsers.extractTextDelta("""{"segments":[{"text":"d"}]}""")
        )
        assertEquals("", SttResponseParsers.extractTextDelta("{invalido"))
    }

    @Test
    fun isServerEnvelopeLine_reconheceLinhasDeControle() {
        assertTrue(SttResponseParsers.isServerEnvelopeLine("data: x"))
        assertTrue(SttResponseParsers.isServerEnvelopeLine("event: y"))
        assertTrue(SttResponseParsers.isServerEnvelopeLine("id: 1"))
        assertTrue(SttResponseParsers.isServerEnvelopeLine("retry: 100"))
        assertTrue(SttResponseParsers.isServerEnvelopeLine("[DONE]"))
        assertFalse(SttResponseParsers.isServerEnvelopeLine("texto normal"))
    }
}
