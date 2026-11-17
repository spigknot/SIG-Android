package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Adendo 11 §2 — recibo e resultado ligados à PRÓPRIA tentativa.
 *
 * A contraprova do especialista: publicar A, publicar B e confirmar o recibo de A
 * acabava confirmando B (a Activity consultava um `ultimoRecibo` global) e A ficava
 * pendente para sempre. Aqui os casos usam a fachada do sink com raiz explícita.
 */
class WhisperOrtTerminalSinkTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun terminal(
        job: String,
        generation: Long = 1L,
        outcome: String = "SUCCESS",
        texto: String = "texto de $job",
        partial: Boolean = false,
    ): WhisperOrtAdmission.Terminal {
        val bytes = texto.toByteArray(Charsets.UTF_8)
        return WhisperOrtAdmission.Terminal(
            jobId = job,
            attempt = 1L,
            generation = generation,
            engine = "ORT",
            provider = "CPU",
            inputSha256 = "entrada",
            outcome = outcome,
            text = texto,
            textBytes = bytes.size,
            textSha256 = "sha-do-texto",
            totalMillis = 10L,
            partial = partial,
            error = null,
        )
    }

    @Test
    fun `confirmar o recibo de A nao confirma B`() {
        val raiz = temp.root
        val a = WhisperOrtTerminalSink.publicar(raiz, terminal("jobA"))!!
        val b = WhisperOrtTerminalSink.publicar(raiz, terminal("jobB"))!!
        assertEquals(2, WhisperOrtTerminalSink.pendentes(raiz).size)

        assertTrue(WhisperOrtTerminalSink.confirmar(raiz, a))

        val restantes = WhisperOrtTerminalSink.pendentes(raiz)
        assertEquals("só B pode continuar pendente", 1, restantes.size)
        assertEquals("jobB", restantes.single().jobId)
        assertFalse("o recibo de A não pode ter confirmado B", a.sha256 == b.sha256)
    }

    @Test
    fun `publicar o mesmo terminal duas vezes e idempotente`() {
        val raiz = temp.root
        val primeiro = WhisperOrtTerminalSink.publicar(raiz, terminal("jobA"))!!
        val segundo = WhisperOrtTerminalSink.publicar(raiz, terminal("jobA"))!!
        assertEquals("mesmo conteúdo devolve o mesmo SHA", primeiro.sha256, segundo.sha256)
        assertEquals(1, WhisperOrtTerminalSink.pendentes(raiz).size)
    }

    @Test
    fun `payload conflitante para o mesmo job e recusado e preserva o confirmado`() {
        val raiz = temp.root
        val original = WhisperOrtTerminalSink.publicar(raiz, terminal("jobA", texto = "primeiro"))!!
        val conflito = WhisperOrtTerminalSink.publicar(raiz, terminal("jobA", texto = "outro texto"))
        assertNull("payload diferente no mesmo job/geração deve ser recusado", conflito)
        assertTrue(WhisperOrtTerminalSink.confirmar(raiz, original))
        assertEquals(0, WhisperOrtTerminalSink.pendentes(raiz).size)
    }

    @Test
    fun `ack vazio nao retira o terminal da fila`() {
        val raiz = temp.root
        val recibo = WhisperOrtTerminalSink.publicar(raiz, terminal("jobA"))!!
        // ACK vazio (conteúdo em branco) não pode valer como entrega.
        File(recibo.caminho).parentFile?.let { pasta ->
            File(pasta, "jobA-1.ack").writeText("", Charsets.UTF_8)
        }
        assertEquals(1, WhisperOrtTerminalSink.pendentes(raiz).size)
    }

    @Test
    fun `ack de conteudo antigo mantem pendente`() {
        val raiz = temp.root
        val recibo = WhisperOrtTerminalSink.publicar(raiz, terminal("jobA"))!!
        File(File(recibo.caminho).parentFile, "jobA-1.ack").writeText("sha-que-nao-e-o-atual", Charsets.UTF_8)
        assertEquals("ACK de outro payload não confirma este", 1, WhisperOrtTerminalSink.pendentes(raiz).size)
    }

    @Test
    fun `terminal sem texto tambem e entregavel`() {
        val raiz = temp.root
        val recibo = WhisperOrtTerminalSink.publicar(
            raiz,
            terminal("jobMudo", outcome = "NO_SPEECH", texto = ""),
        )
        assertNotNull("NO_SPEECH precisa de recibo: é entrega válida", recibo)
        assertEquals(1, WhisperOrtTerminalSink.pendentes(raiz).size)
        assertTrue(WhisperOrtTerminalSink.confirmar(raiz, recibo!!))
        assertEquals(0, WhisperOrtTerminalSink.pendentes(raiz).size)
    }

    @Test
    fun `publicacao em raiz invalida devolve nulo em vez de fingir entrega`() {
        val arquivoNoLugarDaPasta = temp.newFile("nao-e-diretorio")
        val recibo = WhisperOrtTerminalSink.publicar(arquivoNoLugarDaPasta, terminal("jobA"))
        assertNull("sem diretório não há entrega", recibo)
    }

    /**
     * R13/§4 — os metadados de resumo viajam no recibo do job que produziu o
     * texto (duração de áudio e hardware efetivos), para a reentrega exibir o
     * MESMO cartão sem reler a seleção atual.
     */
    @Test
    fun reciboGravaMetadadosDeResumo() {
        val base = temp.newFolder("sink-meta")
        val t = terminal("job-meta").copy(audioMillis = 17_000L, hardware = "CPU")
        val recibo = WhisperOrtTerminalSink.publicar(base, t)
        assertNotNull("terminal não foi publicado", recibo)
        val json = org.json.JSONObject(File(recibo!!.caminho).readText(Charsets.UTF_8))
        assertEquals("duração de áudio não persistida", 17_000L, json.optLong("audio_ms", -1L))
        assertEquals("hardware não persistido", "CPU", json.optString("hardware", ""))
        assertEquals("modelo não persistido", "", json.optString("model", ""))
    }

    /**
     * R13/§4 — recibo ANTIGO (sem as chaves novas) continua legível: a ausência
     * vira "sem dado", nunca zero inventado.
     */
    @Test
    fun reciboAntigoSemMetadadosLeComoAusente() {
        val base = temp.newFolder("sink-antigo")
        val t = terminal("job-antigo")
        val recibo = WhisperOrtTerminalSink.publicar(base, t)
        assertNotNull(recibo)
        // Simula o arquivo gravado por uma versão anterior: remove as chaves novas.
        val arquivo = File(recibo!!.caminho)
        val json = org.json.JSONObject(arquivo.readText(Charsets.UTF_8))
        json.remove("audio_ms")
        json.remove("hardware")
        arquivo.writeText(json.toString(), Charsets.UTF_8)

        val relido = org.json.JSONObject(arquivo.readText(Charsets.UTF_8))
        assertEquals("audio ausente deve virar -1", -1L, relido.optLong("audio_ms", -1L))
        assertEquals("hardware ausente deve virar vazio", "", relido.optString("hardware", ""))
        assertEquals("texto do recibo antigo preservado", t.text, relido.optString("texto", ""))
    }
}
