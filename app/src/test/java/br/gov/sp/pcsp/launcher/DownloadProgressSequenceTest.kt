package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Vacinas do log que acumula e da etapa por arquivo (25/09/2026).
 *
 * O pedido que estes testes travam: a tela do download mostra a sequência dos
 * arquivos, no estilo do log do SigUpdater (SIG Windows), e no fim fica aberta
 * para o usuário rolar e ler antes de continuar.
 */
class DownloadProgressSequenceTest {

    /** Normaliza o separador decimal: a formatação usa a locale do sistema. */
    private fun num(s: String) = s.replace(',', '.')

    @Test
    fun `a linha por arquivo tem o formato do SigUpdater`() {
        val texto = DownloadSizeFormat.arquivoAtual(
            indice = 7, total = 25, nome = "llm-fp16.onnx.data", percent = 12,
        )
        assertEquals("Baixando arquivo 7/25: llm-fp16.onnx.data (12%)", texto)
    }

    @Test
    fun `a linha sem percentual nao inventa zero por cento`() {
        // Rede sem Content-Length: escrever "(0%)" seria pior que omitir, porque
        // o arquivo já estaria no meio.
        val texto = DownloadSizeFormat.arquivoAtualSemPercentual(
            indice = 3, total = 25, nome = "vocab.json",
        )
        assertEquals("Baixando arquivo 3/25: vocab.json", texto)
        assertFalse("não pode mostrar percentual inventado", texto.contains("%"))
    }

    @Test
    fun `o log guarda a ordem dos arquivos concluidos`() {
        val log = DownloadSizeFormat.Log()
        log.inicio("25 arquivo(s) · total 4,93 GB")
        log.concluido("encoder-pesos.data", 1_085_993_664L)
        log.concluido("projector-pesos.data", 159_535_104L)
        log.fim(4_928_446_340L)

        val linhas = log.linhas()
        // A ordem é a do download, e cada arquivo tem o SEU tamanho.
        assertTrue(num(linhas[1]).contains("encoder-pesos.data") && num(linhas[1]).contains("1.1 GB"))
        assertTrue(num(linhas[2]).contains("projector-pesos.data") && num(linhas[2]).contains("159.5 MB"))
        assertEquals("Download concluído · 4,9 GB no total", linhas.last())
    }

    @Test
    fun `o log nao repete a mesma etapa consecutiva`() {
        // O callback de progresso dispara dezenas de vezes por segundo; sem o
        // filtro o log viraria milhares de linhas idênticas.
        val log = DownloadSizeFormat.Log()
        repeat(50) { log.etapa("Baixando: 42% (2,0 GB de 4,9 GB)") }
        log.etapa("Baixando: 43% (2,1 GB de 4,9 GB)")
        assertEquals(2, log.linhas().size)
    }

    @Test
    fun `o NAR lista todos os arquivos do pacote mesmo sem manifesto`() {
        // Regressão do fallback: com o manifesto fora do ar, a lista ficava com
        // 11 arquivos e o total não fechava com o download real.
        //
        // São 21 e não 25: o manifesto publicado tem 25 entradas porque inclui o
        // par das DUAS variantes do LLM, e o app baixa só o par da escolhida
        // (9 comuns + 12 dos 6 buckets).
        val arquivos = GraniteNarEngine.nomesDoPacote()
        assertEquals("o pacote da variante tem 21 arquivos", 21, arquivos.size)

        val semTamanho = arquivos.filter { GraniteNarEngine.tamanhoConhecido(it) <= 0L }
        assertEquals(
            "todo arquivo do NAR precisa de tamanho conhecido, faltam: $semTamanho",
            0, semTamanho.size,
        )
    }

    @Test
    fun `os buckets do NAR entram no tamanho conhecido`() {
        // Os 12 arquivos de bucket (t0200..t2000) são os que sumiam sem manifesto.
        for (t in listOf(200, 400, 800, 1200, 1600, 2000)) {
            val encoder = "granite-4.1-nar-encoder-t%04d-fp16.onnx".format(t)
            val projector = "granite-4.1-nar-projector-t%04d-fp16.onnx".format(t)
            assertTrue("sem tamanho: $encoder", GraniteNarEngine.tamanhoConhecido(encoder) > 0L)
            assertTrue("sem tamanho: $projector", GraniteNarEngine.tamanhoConhecido(projector) > 0L)
        }
    }
}
