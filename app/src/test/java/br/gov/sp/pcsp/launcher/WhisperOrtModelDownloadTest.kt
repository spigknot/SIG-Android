package br.gov.sp.pcsp.launcher

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.HttpURLConnection
import java.net.URL

/**
 * Adendo 13 §2 — entrega gerenciada do modelo com servidor controlado.
 *
 * Prova por cenários pequenos (bytes, não os 1,5 GB reais): sucesso íntegro,
 * cancelamento no meio, HTTP incompleto e SHA errado. Nenhum peso é baixado de
 * verdade; a ativação continua sendo o `install` atômico existente.
 */
class WhisperOrtModelDownloadTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun sha(b: ByteArray) = java.security.MessageDigest.getInstance("SHA-256")
        .digest(b).joinToString("") { "%02x".format(it) }

    private fun entrada(nome: String, corpo: ByteArray, tamanhoDeclarado: Long = corpo.size.toLong()) =
        WhisperOrtModelCatalog.Entry(nome, tamanhoDeclarado, sha(corpo), WhisperOrtModelPackage.Role.CONFIG)

    private fun servir(corpos: List<ByteArray>): MockWebServer {
        val servidor = MockWebServer()
        for (corpo in corpos) {
            servidor.enqueue(MockResponse().setBody(String(corpo, Charsets.ISO_8859_1)))
        }
        servidor.start()
        return servidor
    }

    @Test
    fun `urls seguem a origem congelada`() {
        assertEquals(
            "https://huggingface.co/onnx-community/whisper-large-v3-turbo/resolve/" +
                "${WhisperOrtModelDownload.REVISION}/onnx/encoder_model_int8.onnx",
            WhisperOrtModelDownload.urlDe("encoder_model_int8.onnx"),
        )
        assertEquals(
            "https://huggingface.co/onnx-community/whisper-large-v3-turbo/resolve/" +
                "${WhisperOrtModelDownload.REVISION}/vocab.json",
            WhisperOrtModelDownload.urlDe("vocab.json"),
        )
    }

    @Test
    fun `corpo sem tamanho declarado nao pode exceder o catalogo durante a gravacao`() {
        val corpo = ByteArray(16) { 65 }
        val esperada = entrada("a.bin", corpo.copyOf(4))
        val staging = java.io.File(temp.root, "staging-limite")
        var maiorParcial = 0L
        var desconectou = false
        val r = WhisperOrtModelDownload.baixar(
            listOf(esperada), staging,
            onProgresso = {
                maiorParcial = maxOf(maiorParcial, java.io.File(staging, "a.bin.part").length())
            },
            abrirConexao = { endereco ->
                object : HttpURLConnection(URL(endereco)) {
                    override fun connect() = Unit
                    override fun disconnect() { desconectou = true }
                    override fun usingProxy() = false
                    override fun getResponseCode() = HTTP_OK
                    override fun getContentLengthLong() = -1L
                    override fun getInputStream() = corpo.inputStream()
                }
            },
        )
        assertTrue(r is WhisperOrtModelDownload.Resultado.Falhou)
        assertTrue("o parcial ultrapassou o orcamento", maiorParcial <= esperada.sizeBytes)
        assertTrue((r as WhisperOrtModelDownload.Resultado.Falhou).motivo.contains("excede"))
        assertTrue("conexao deve fechar tambem na falha de tamanho", desconectou)
        assertFalse(staging.exists())
    }

    @Test
    fun `falha incompleta informa bytes recebidos antes da limpeza`() {
        val corpo = "ABCD".toByteArray()
        val servidor = servir(listOf(corpo))
        try {
            val r = WhisperOrtModelDownload.baixar(
                listOf(entrada("a.bin", corpo, tamanhoDeclarado = 8L)),
                java.io.File(temp.root, "staging-incompleto"),
                urlDe = { servidor.url("/$it").toString() },
            ) as WhisperOrtModelDownload.Resultado.Falhou
            assertTrue("contagem real perdida: ${r.motivo}", r.motivo.contains("4 B de 8 B"))
        } finally {
            servidor.close()
        }
    }

    @Test
    fun `sucesso integro publica staging pronto`() {
        val a = ByteArray(10_000) { 7 }
        val b = ByteArray(5_000) { 9 }
        val servidor = servir(listOf(a, b))
        try {
            val entradas = listOf(entrada("a.bin", a), entrada("b.bin", b))
            var chamadas = 0
            val r = WhisperOrtModelDownload.baixar(
                entradas,
                temp.newFolder("stg"),
                urlDe = { servidor.url("/$it").toString() },
                onProgresso = { chamadas++ },
                abrirConexao = { URL(it).openConnection() as HttpURLConnection },
            )
            assertTrue("deveria ser Pronto, veio $r", r is WhisperOrtModelDownload.Resultado.Pronto)
            val staging = (r as WhisperOrtModelDownload.Resultado.Pronto).staging
            assertTrue(staging.resolve("a.bin").isFile)
            assertTrue(staging.resolve("b.bin").isFile)
            assertTrue("progresso deveria ser reportado", chamadas > 0)
        } finally {
            servidor.close()
        }
    }

    @Test
    fun `cancelamento no meio apaga parciais`() {
        val primeiro = ByteArray(1_000) { 3 }
        val segundo = ByteArray(2_000) { 4 }
        val servidor = servir(listOf(primeiro, segundo))
        try {
            val staging = temp.newFolder("stg-cancel")
            // Cancela quando o PRIMEIRO arquivo conclui (progresso final dele):
            // determinístico, sem depender de temporização — o cancelamento chega
            // no meio da entrega, antes do segundo arquivo começar.
            var primeiroPronto = false
            val r = WhisperOrtModelDownload.baixar(
                listOf(entrada("a.bin", primeiro), entrada("g.bin", segundo)),
                staging,
                urlDe = { servidor.url("/$it").toString() },
                isCancelada = { primeiroPronto },
                onProgresso = { p ->
                    if (p.arquivoAtual == "a.bin" && p.baixadosArquivo == p.totalArquivo) {
                        primeiroPronto = true
                    }
                },
                abrirConexao = { URL(it).openConnection() as HttpURLConnection },
            )
            assertTrue("deveria ser Cancelado, veio $r", r is WhisperOrtModelDownload.Resultado.Cancelado)
            assertFalse(
                "parciais não podem ficar (restou ${staging.listFiles()?.map { "${it.name}:${it.length()}" }})",
                staging.exists(),
            )
        } finally {
            servidor.close()
        }
    }

    @Test
    fun `http incompleto e recusado sem publicar`() {
        val corpo = ByteArray(1_000) { 5 }
        val servidor = servir(listOf(corpo))
        try {
            // Declara 5.000 B mas o servidor entrega 1.000 B.
            val r = WhisperOrtModelDownload.baixar(
                listOf(entrada("c.bin", corpo, tamanhoDeclarado = 5_000L)),
                temp.newFolder("stg-curto"),
                urlDe = { servidor.url("/$it").toString() },
                abrirConexao = { URL(it).openConnection() as HttpURLConnection },
            )
            assertTrue("deveria ser Falhou, veio $r", r is WhisperOrtModelDownload.Resultado.Falhou)
            assertTrue(
                "motivo deve dizer incompleto (veio ${(r as WhisperOrtModelDownload.Resultado.Falhou).motivo})",
                r.motivo.contains("incompleto"),
            )
        } finally {
            servidor.close()
        }
    }

    @Test
    fun `sha errado e recusado sem publicar`() {
        val corpo = ByteArray(2_000) { 6 }
        val servidor = servir(listOf(corpo))
        try {
            val errada = WhisperOrtModelCatalog.Entry(
                "d.bin", corpo.size.toLong(), sha(ByteArray(2_000) { 8 }),
                WhisperOrtModelPackage.Role.CONFIG,
            )
            val staging = temp.newFolder("stg-sha")
            val r = WhisperOrtModelDownload.baixar(
                listOf(errada),
                staging,
                urlDe = { servidor.url("/$it").toString() },
                abrirConexao = { URL(it).openConnection() as HttpURLConnection },
            )
            assertTrue("deveria ser Falhou, veio $r", r is WhisperOrtModelDownload.Resultado.Falhou)
            assertFalse("nada pode ser publicado", staging.exists())
        } finally {
            servidor.close()
        }
    }

    @Test
    fun `http 404 vira falha tipada`() {
        val servidor = MockWebServer()
        servidor.enqueue(MockResponse().setResponseCode(404))
        servidor.start()
        try {
            val r = WhisperOrtModelDownload.baixar(
                listOf(entrada("x.bin", ByteArray(10) { 1 })),
                temp.newFolder("stg-404"),
                urlDe = { servidor.url("/$it").toString() },
                abrirConexao = { URL(it).openConnection() as HttpURLConnection },
            )
            assertTrue("deveria ser Falhou, veio $r", r is WhisperOrtModelDownload.Resultado.Falhou)
            assertTrue(
                (r as WhisperOrtModelDownload.Resultado.Falhou).motivo.contains("404"),
            )
        } finally {
            servidor.close()
        }
    }
}
