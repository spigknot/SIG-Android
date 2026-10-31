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
}
