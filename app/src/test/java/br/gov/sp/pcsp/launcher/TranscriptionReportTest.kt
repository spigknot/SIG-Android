package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TranscriptionReportTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun escapeHtml_trocaOsCincoCaracteres() {
        assertEquals("a &amp; b &lt;c&gt; &quot;d&quot; &#39;e&#39;", TranscriptionReport.escapeHtml("a & b <c> \"d\" 'e'"))
    }

    @Test
    fun buildHtml_escapaCamposEConverteQuebraDeLinha() {
        val html = TranscriptionReport.buildHtml(
            listOf(TranscriptionReport.Row("a&b.txt", "linha1\nlinha2"))
        )
        assertTrue(html.contains("<td>a&amp;b.txt</td>"))
        assertTrue(html.contains("<td>linha1<br>linha2</td>"))
        assertTrue(html.startsWith("<!doctype html>"))
    }

    @Test
    fun buildHtml_listaVazia_geraTabelaSemLinhas() {
        val html = TranscriptionReport.buildHtml(emptyList())
        assertTrue(html.contains("<tbody>"))
        assertFalse(html.contains("<tr><td>"))
    }

    @Test
    fun appendLog_comecaComCarimboDeHora() {
        val builder = StringBuilder()
        TranscriptionReport.appendLog(builder, "mensagem")
        assertTrue(Regex("""^\[\d{2}:\d{2}:\d{2}\] mensagem\n$""").matches(builder.toString()))
    }

    @Test
    fun appendTranscriptionHeader_emBuilderVazio_naoAdicionaQuebraAntes() {
        val builder = StringBuilder()
        TranscriptionReport.appendTranscriptionHeader(builder, "audio.mp3")
        assertEquals("audio.mp3\n\n", builder.toString())
    }

    @Test
    fun appendTranscriptionHeader_semQuebraFinal_insereQuebra() {
        val builder = StringBuilder("texto")
        TranscriptionReport.appendTranscriptionHeader(builder, "audio.mp3")
        assertEquals("texto\naudio.mp3\n\n", builder.toString())
    }

    @Test
    fun appendTranscriptionHeader_comQuebraFinal_naoDuplica() {
        val builder = StringBuilder("texto\n")
        TranscriptionReport.appendTranscriptionHeader(builder, "audio.mp3")
        assertEquals("texto\naudio.mp3\n\n", builder.toString())
    }

    @Test
    fun appendTranscriptionSeparator_garanteQuebraAntesDoSeparador() {
        val builder = StringBuilder("texto")
        TranscriptionReport.appendTranscriptionSeparator(builder)
        assertEquals("texto\n-------------------------------\n", builder.toString())
    }

    @Test
    fun safeBaseName_removeExtensaoEInvalida() {
        assertEquals("meu_audio", TranscriptionReport.safeBaseName("meu/audio.mp3"))
        assertEquals("a_b_c", TranscriptionReport.safeBaseName("a:b*c.wav"))
        assertEquals("transcricao", TranscriptionReport.safeBaseName(""))
    }

    @Test
    fun uniqueFile_sufixaQuandoArquivoExiste() {
        val dir = temp.newFolder("saida")
        val primeiro = TranscriptionReport.uniqueFile(dir, "texto.txt")
        assertEquals("texto.txt", primeiro.name)
        primeiro.writeText("x")
        val segundo = TranscriptionReport.uniqueFile(dir, "texto.txt")
        assertEquals("texto_2.txt", segundo.name)
        segundo.writeText("x")
        val terceiro = TranscriptionReport.uniqueFile(dir, "texto.txt")
        assertEquals("texto_3.txt", terceiro.name)
    }

    @Test
    fun formatMediaSize_usaUnidadesBinarias() {
        assertEquals("0.0 b", TranscriptionReport.formatMediaSize(0L))
        assertEquals("1023.0 b", TranscriptionReport.formatMediaSize(1023L))
        assertEquals("1.0 kb", TranscriptionReport.formatMediaSize(1024L))
        assertEquals("1.5 kb", TranscriptionReport.formatMediaSize(1536L))
        assertEquals("1.0 mb", TranscriptionReport.formatMediaSize(1024L * 1024L))
        assertEquals("1.0 gb", TranscriptionReport.formatMediaSize(1024L * 1024L * 1024L))
    }

    @Test
    fun formatKbps_abaixoDeDezUsaDecimal() {
        assertEquals("9.5k", TranscriptionReport.formatKbps(9.5))
        assertEquals("10k", TranscriptionReport.formatKbps(10.0))
        assertEquals("42k", TranscriptionReport.formatKbps(42.7))
    }

    @Test
    fun parseDurationSeconds_leOHHMMSSDoLog() {
        assertEquals(62.5, TranscriptionReport.parseDurationSeconds("... Duration: 00:01:02.50, start ...")!!, 0.0001)
        assertEquals(3723.0, TranscriptionReport.parseDurationSeconds("duration: 1:02:03")!!, 0.0001)
        assertEquals(null, TranscriptionReport.parseDurationSeconds("sem duracao aqui"))
    }
}
