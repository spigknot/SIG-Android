package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** W10: o status estruturado do nativo decide o resultado; o prefixo de
 * controle do texto é só fallback de biblioteca antiga. */
class WhisperResultParserTest {

    private fun parse(returned: String?, status: String?) = WhisperResultParser.parse(returned, status)

    // ---------------- status ok ----------------

    @Test
    fun `ok com texto comum vira Success`() {
        val result = parse("  Bom dia, colegas.  ", "ok")
        assertEquals(WhisperTranscriptResult.Success("Bom dia, colegas."), result)
    }

    @Test
    fun `ok com texto iniciando com Erro é conteúdo real e vira Success`() {
        // colisão de prefixo: o áudio falou "Erro: conexão perdida"
        val result = parse("Erro: conexão perdida com o servidor", "ok")
        assertEquals(WhisperTranscriptResult.Success("Erro: conexão perdida com o servidor"), result)
    }

    @Test
    fun `ok com texto iniciando com Cancelado é conteúdo real e vira Success`() {
        val result = parse("Cancelado: o voo foi remarcado", "ok")
        assertEquals(WhisperTranscriptResult.Success("Cancelado: o voo foi remarcado"), result)
    }

    @Test
    fun `ok com texto vazio vira Success vazio`() {
        assertEquals(WhisperTranscriptResult.Success(""), parse("", "ok"))
        assertEquals(WhisperTranscriptResult.Success(""), parse("   ", "ok"))
    }

    @Test
    fun `status ok é insensível a maiúsculas e espaços`() {
        assertEquals(WhisperTranscriptResult.Success("oi"), parse("oi", "  OK "))
    }

    // ---------------- no_speech ----------------

    @Test
    fun `no_speech com texto vazio vira NoSpeech`() {
        assertEquals(WhisperTranscriptResult.NoSpeech, parse("", "no_speech"))
    }

    @Test
    fun `no_speech vence texto residual vindo do nativo`() {
        assertEquals(WhisperTranscriptResult.NoSpeech, parse("   ", "no_speech"))
        assertEquals(WhisperTranscriptResult.NoSpeech, parse("whisper: blank", "no_speech"))
    }

    // ---------------- cancelled ----------------

    @Test
    fun `cancelled com prefixo remove o prefixo da mensagem`() {
        val result = parse("Cancelado:  pelo usuário ", "cancelled")
        assertEquals(WhisperTranscriptResult.Cancelled("pelo usuário"), result)
    }

    @Test
    fun `cancelled com texto sem prefixo usa o texto como mensagem`() {
        assertEquals(WhisperTranscriptResult.Cancelled("interrompido"), parse("interrompido", "cancelled"))
    }

    @Test
    fun `cancelled com texto vazio usa mensagem padrão`() {
        val result = parse("", "cancelled")
        assertEquals(WhisperTranscriptResult.Cancelled(WhisperResultParser.DEFAULT_CANCEL_MESSAGE), result)
    }

    @Test
    fun `cancelled staleness do controller vira Cancelled`() {
        // cenário W02: transcribe devolveu texto de sessão substituída
        val result = parse(WhisperSessionController.STALE_RESULT_TEXT, WhisperSessionController.STALE_STATUS)
        assertTrue(result is WhisperTranscriptResult.Cancelled)
        assertEquals("sessão substituída", (result as WhisperTranscriptResult.Cancelled).message)
    }

    // ---------------- error ----------------

    @Test
    fun `error com prefixo remove o prefixo da mensagem`() {
        val result = parse("Erro: GGML inválido", "error")
        assertEquals(WhisperTranscriptResult.Error("GGML inválido"), result)
    }

    @Test
    fun `error sem prefixo usa o texto como mensagem`() {
        assertEquals(WhisperTranscriptResult.Error("modelo corrompido"), parse("modelo corrompido", "error"))
    }

    @Test
    fun `error com texto vazio usa mensagem padrão`() {
        val result = parse("", "error")
        assertEquals(WhisperTranscriptResult.Error(WhisperResultParser.DEFAULT_ERROR_MESSAGE), result)
    }

    // ---------------- fallback legado (sem status) ----------------

    @Test
    fun `status desconhecido cai no fallback por prefixo`() {
        assertTrue(parse("Cancelado: por você", "qualquercoisa") is WhisperTranscriptResult.Cancelled)
        assertTrue(parse("Erro: disco cheio", "qualquercoisa") is WhisperTranscriptResult.Error)
        assertTrue(parse("texto normal", "qualquercoisa") is WhisperTranscriptResult.Success)
    }

    @Test
    fun `status ausente ou nulo cai no fallback por prefixo`() {
        assertTrue(parse("Cancelado: por você", "") is WhisperTranscriptResult.Cancelled)
        assertTrue(parse("Cancelado: por você", "   ") is WhisperTranscriptResult.Cancelled)
        assertTrue(parse("Cancelado: por você", null as String?) is WhisperTranscriptResult.Cancelled)
        assertEquals(WhisperTranscriptResult.Success("texto normal"), parse("texto normal", null as String?))
    }

    @Test
    fun `fallback legado tira prefixo das mensagens`() {
        val cancelled = parse("  Cancelado:  pelo usuário  ", "lib-antiga")
        assertEquals(WhisperTranscriptResult.Cancelled("pelo usuário"), cancelled)
        val error = parse("  Erro:  GGML inválido  ", "lib-antiga")
        assertEquals(WhisperTranscriptResult.Error("GGML inválido"), error)
    }

    @Test
    fun `fallback legado com texto vazio vira Success vazio`() {
        // compat: o fluxo antigo tratava retorno vazio como transcrição vazia, não como falha
        assertEquals(WhisperTranscriptResult.Success(""), parse("", "lib-antiga"))
        assertEquals(WhisperTranscriptResult.Success(""), parse(null as String?, "lib-antiga"))
    }

    @Test
    fun `texto normal com prefixo parcial não é confundido com controle`() {
        // "Erro" sem os dois-pontos não é prefixo de controle
        assertEquals(WhisperTranscriptResult.Success("Erro no relatório anterior"), parse("Erro no relatório anterior", "ok"))
        assertTrue(parse("Erro no relatório anterior", "lib-antiga") is WhisperTranscriptResult.Success)
    }

    @Test
    fun `parseLegacyByPrefix cobre os quatro caminhos`() {
        assertTrue(WhisperResultParser.parseLegacyByPrefix("oi") is WhisperTranscriptResult.Success)
        assertTrue(WhisperResultParser.parseLegacyByPrefix("Erro: x") is WhisperTranscriptResult.Error)
        assertTrue(WhisperResultParser.parseLegacyByPrefix("Cancelado: x") is WhisperTranscriptResult.Cancelled)
        // legado: vazio não é falha — o fluxo antigo tratava retorno vazio como "transcrição vazia"
        assertEquals(WhisperTranscriptResult.Success(""), WhisperResultParser.parseLegacyByPrefix(""))
    }
}
