package br.gov.sp.pcsp.launcher

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R13/F1 — TELEMETRIA ADITIVA do replay (teste FOCAL).
 *
 * Contrato provado aqui:
 * 1. campo PRESENTE e correto quando o dado existe (segments, sha256 do PCM,
 *    word_times, region_id/attempt_id no reparo);
 * 2. campo OMITIDO quando o dado NÃO existe — e o corpo do evento continua
 *    JSON VÁLIDO (nada quebra: nenhuma vírgula pendurada, nenhuma chave nula);
 * 3. nada aqui decide, ordena ou publica: são funções PURAS de formatação.
 */
class LiveTelemetryF1Test {

    private val header = "\"run_id\":\"r1\",\"act_id\":\"a1\""

    private fun body(fields: String): String =
        if (fields.isBlank()) "{$header}" else "{$header,$fields}"

    // ---------------------------------------------------------------- segments

    @Test
    fun `segments presentes quando existem - texto e tempos exatos`() {
        val segs = listOf(
            LiveSegmentTime("a mostra 1.", 0, 620),
            LiveSegmentTime("a mostra 2.", 620, 1240),
        )
        val field = LiveTelemetry.segmentsField(segs)
        val obj = JSONObject(body(field))
        val arr = obj.getJSONArray("segments")
        assertEquals(2, arr.length())
        assertEquals("a mostra 1.", arr.getJSONObject(0).getString("text"))
        assertEquals(0L, arr.getJSONObject(0).getLong("t0Ms"))
        assertEquals(620L, arr.getJSONObject(0).getLong("t1Ms"))
        assertEquals(620L, arr.getJSONObject(1).getLong("t0Ms"))
        assertEquals(1240L, arr.getJSONObject(1).getLong("t1Ms"))
    }

    @Test
    fun `segments com aspas barra e quebra de linha continuam JSON valido`() {
        val segs = listOf(LiveSegmentTime("ele disse \"oi\"\\fim\nlinha2", 5, 9))
        val obj = JSONObject(body(LiveTelemetry.segmentsField(segs)))
        assertEquals(
            "ele disse \"oi\"\\fim linha2",
            obj.getJSONArray("segments").getJSONObject(0).getString("text"),
        )
    }

    @Test
    fun `sem segments a chave e OMITIDA e o corpo continua valido`() {
        for (absent in listOf(null, emptyList<LiveSegmentTime>())) {
            val field = LiveTelemetry.segmentsField(absent)
            assertEquals("campo vazio = chave omitida", "", field)
            val obj = JSONObject(body(field))
            assertFalse(obj.has("segments"))
            assertEquals("r1", obj.getString("run_id"))
        }
    }

    // ---------------------------------------------------------------- pcm sha256

    @Test
    fun `pcm_sha256 presente quando ha PCM - vetor conhecido`() {
        // sha256("abc") = ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad
        val field = LiveTelemetry.pcmField("abc".toByteArray(Charsets.US_ASCII))
        assertEquals(
            "\"pcm_sha256\":\"ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad\"",
            field,
        )
        val obj = JSONObject(body(field))
        assertEquals(64, obj.getString("pcm_sha256").length)
    }

    @Test
    fun `pcm_sha256 ausente sem PCM - null ou vazio nao quebram`() {
        assertNull(LiveTelemetry.pcmSha256(null))
        assertNull(LiveTelemetry.pcmSha256(ByteArray(0)))
        for (absent in listOf(null, ByteArray(0))) {
            assertEquals("", LiveTelemetry.pcmField(absent))
            val obj = JSONObject(body(LiveTelemetry.pcmField(absent)))
            assertFalse(obj.has("pcm_sha256"))
        }
    }

    // ---------------------------------------------------------------- word_times

    @Test
    fun `word_times explicito quando o app nao passa tempos por palavra`() {
        val obj = JSONObject(body(LiveTelemetry.wordTimesField(null)))
        assertTrue("chave presente, valor null", obj.has("word_times"))
        assertTrue(obj.isNull("word_times"))
    }

    @Test
    fun `word_times com dados vira array de texto e tempos`() {
        val wt = listOf(
            LiveTextCommitter.WordTime("alfa", 0, 100),
            LiveTextCommitter.WordTime("beta", 100, 250),
        )
        val arr = JSONObject(body(LiveTelemetry.wordTimesField(wt))).getJSONArray("word_times")
        assertEquals(2, arr.length())
        assertEquals("beta", arr.getJSONObject(1).getString("word"))
        assertEquals(250L, arr.getJSONObject(1).getLong("t1Ms"))
    }

    // ---------------------------------------------------------------- reparo

    @Test
    fun `reparo leva region_id e attempt_id explicitos`() {
        val obj = JSONObject(body(LiveTelemetry.repairFields(7L, 3L)))
        assertEquals(7L, obj.getLong("region_id"))
        assertEquals(3L, obj.getLong("attempt_id"))
    }

    @Test
    fun `attempt ids sequenciais por job e zero no inicio`() {
        val ids = LiveTelemetry.AttemptIds()
        assertEquals(1L, ids.next())
        assertEquals(2L, ids.next())
        ids.reset()
        assertEquals("reset por job", 1L, ids.next())
    }

    // ---------------------------------------------------------------- corpo completo

    @Test
    fun `corpo do live_commit com todos os campos e JSON valido`() {
        val fields = LiveTelemetry.join(
            "\"added\":0,\"provisional\":91,\"committed_words\":0,\"alignment\":\"segmentos:1(evidencia)\"",
            LiveTelemetry.segmentsField(listOf(LiveSegmentTime("Semana.", 0, 14_680))),
            LiveTelemetry.wordTimesField(null),
            LiveTelemetry.pcmField(ByteArray(8) { it.toByte() }),
        )
        val obj = JSONObject(body(fields))
        assertEquals("segmentos:1(evidencia)", obj.getString("alignment"))
        assertEquals(1, obj.getJSONArray("segments").length())
        assertTrue(obj.isNull("word_times"))
        assertEquals(64, obj.getString("pcm_sha256").length)
    }

    @Test
    fun `corpo sem nenhum dado aditivo nao ganha chaves nem virgula`() {
        val fields = LiveTelemetry.join(
            "\"added\":0,\"alignment\":\"anexo(esp=0)\"",
            LiveTelemetry.segmentsField(null),
            LiveTelemetry.pcmField(null),
        )
        assertEquals("\"added\":0,\"alignment\":\"anexo(esp=0)\"", fields)
        val obj = JSONObject(body(fields))
        assertFalse(obj.has("segments"))
        assertFalse(obj.has("pcm_sha256"))
    }
}
