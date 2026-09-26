package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Vacina dos defeitos de tamanho nas telas de download (25/09/2026).
 *
 * Trava três coisas que o usuário reclamou:
 *  1. a base decimal (1 KB = 1000 B) em TODO o app — o mesmo arquivo aparecia
 *     como "3.3 GB" e "3112 MB" em linhas diferentes do mesmo diálogo;
 *  2. a unidade GB existe (o `formatBytes` do Granite só tinha B/KB/MB e o
 *     pacote de 4,93 GB aparecia como "4700.13 MB");
 *  3. o detalhamento por arquivo no estilo do log do SigUpdater (SIG Windows).
 *
 * ⚠️ Os literais usam PONTO decimal: [num] normaliza a vírgula que a locale
 * pt-BR do aparelho produz, senão o teste passa no PC e falha no celular.
 */
class DownloadSizeFormatTest {

    /** Normaliza o separador decimal: a formatação usa a locale do sistema (pt-BR = vírgula). */
    private fun num(s: String) = s.replace(',', '.')

    @Test
    fun `legivel usa base decimal em todas as unidades`() {
        // 1 KB = 1000 B, 1 MB = 1000 KB, 1 GB = 1000 MB — não 1024.
        assertEquals("1.0 KB", num(DownloadSizeFormat.legivel(1_000L)))
        assertEquals("1.0 MB", num(DownloadSizeFormat.legivel(1_000_000L)))
        assertEquals("1.0 GB", num(DownloadSizeFormat.legivel(1_000_000_000L)))
    }

    @Test
    fun `legivel mostra bytes inteiros abaixo de 1 KB`() {
        assertEquals("0 B", DownloadSizeFormat.legivel(0L))
        assertEquals("885 B", DownloadSizeFormat.legivel(885L))
        assertEquals("999 B", DownloadSizeFormat.legivel(999L))
    }

    @Test
    fun `legivel nunca mostra zero para arquivo pequeno`() {
        // Regressão do VAD Silero (885.098 B): com "%.0f MB" a tela diria
        // "0 MB" e o usuário acharia que não há download.
        assertEquals("885.1 KB", num(DownloadSizeFormat.legivel(885_098L)))
    }

    @Test
    fun `legivel cobre os tamanhos reais dos pacotes do SIG`() {
        // Valores conferidos contra o R2 em 25/09/2026 (HEAD nos artefatos).
        assertEquals("71.0 MB", num(DownloadSizeFormat.legivel(70_964_325L)))
        assertEquals("78.6 MB", num(DownloadSizeFormat.legivel(78_561_574L)))
        assertEquals("55.7 MB", num(DownloadSizeFormat.legivel(55_682_231L)))
        // NAR fp16: 4.928.446.340 B = 4,93 GB — antes virava "4700.13 MB".
        assertEquals("4.9 GB", num(DownloadSizeFormat.legivel(4_928_446_340L)))
        // NAR int4: 2.506.635.166 B = 2,51 GB.
        assertEquals("2.5 GB", num(DownloadSizeFormat.legivel(2_506_635_166L)))
        // Granite 5.0 Turbo: 3.995.414.962 B = 4,0 GB.
        assertEquals("4.0 GB", num(DownloadSizeFormat.legivel(3_995_414_962L)))
        // Whisper v3Turbo: 1.624.555.275 B = 1,62 GB — o diálogo não dizia nada.
        assertEquals("1.6 GB", num(DownloadSizeFormat.legivel(1_624_555_275L)))
    }

    @Test
    fun `legivel trata negativo como zero`() {
        assertEquals("0 B", DownloadSizeFormat.legivel(-5L))
    }

    @Test
    fun `plano lista cada arquivo e o total`() {
        val plano = DownloadSizeFormat.Plano(
            rotulo = "NAR",
            arquivos = listOf(
                DownloadSizeFormat.Arquivo("encoder-pesos.data", 1_085_993_664L),
                DownloadSizeFormat.Arquivo("llm-fp16.onnx.data", 3_263_500_288L),
                DownloadSizeFormat.Arquivo("vocab.json", 1_612_704L),
            ),
        )
        val linhas = num(DownloadSizeFormat.plano(plano)).lines()
        // Cabeçalho com a contagem e o total (1,086 GB + 3,264 GB + 1,6 MB = 4,4 GB).
        assertTrue("cabecalho: ${linhas[0]}", linhas[0].contains("3 arquivo(s)"))
        assertTrue("total no cabecalho: ${linhas[0]}", linhas[0].contains("total 4.4 GB"))
        // Cada arquivo aparece com o próprio tamanho.
        assertTrue(linhas.any { it.contains("encoder-pesos.data") && it.contains("1.1 GB") })
        assertTrue(linhas.any { it.contains("llm-fp16.onnx.data") && it.contains("3.3 GB") })
        assertTrue(linhas.any { it.contains("vocab.json") && it.contains("1.6 MB") })
    }

    @Test
    fun `plano sem detalhe cai no total fallback`() {
        val plano = DownloadSizeFormat.Plano("Componentes nativos", emptyList(), 70_964_325L)
        assertEquals(70_964_325L, plano.totalBytes)
        val texto = num(DownloadSizeFormat.plano(plano))
        assertTrue(texto, texto.contains("arquivo único"))
        assertTrue(texto, texto.contains("71.0 MB"))
    }

    /**
     * O agrupamento em "outros N" foi REMOVIDO de propósito: o pedido é o
     * usuário ler TODOS os arquivos, e a lista cabe porque o diálogo a exibe
     * com altura limitada + rolagem. Agrupar esconderia justamente o que se
     * pediu para ver.
     */
    @Test
    fun `plano nunca agrupa em outros N arquivo`() {
        val arquivos = (1..25).map { DownloadSizeFormat.Arquivo("f$it.bin", 1_000_000L * it) }
        val texto = DownloadSizeFormat.plano(DownloadSizeFormat.Plano("NAR", arquivos))
        assertFalse("não pode agrupar", texto.contains("outros"))
        for (i in 1..25) {
            assertTrue("faltou f$i", texto.contains("f$i.bin"))
        }
        // E o total não pode depender de como a lista foi apresentada.
        assertEquals(arquivos.sumOf { it.bytes }, DownloadSizeFormat.Plano("NAR", arquivos).totalBytes)
    }

    @Test
    fun `o log do download acumula linhas sem repetir a mesma consecutiva`() {
        val log = DownloadSizeFormat.Log()
        log.inicio("2 arquivo(s) · total 4,3 GB")
        log.etapa("Baixando: 50% (2,1 GB de 4,3 GB)")
        // O callback de rede repete a MESMA linha dezenas de vezes por segundo.
        log.etapa("Baixando: 50% (2,1 GB de 4,3 GB)")
        log.etapa("Baixando: 100% (4,3 GB de 4,3 GB)")
        log.concluido("llm-fp16.onnx.data", 3_263_500_288L)
        log.fim(4_349_493_952L)

        val linhas = log.linhas()
        assertEquals(5, linhas.size)
        assertEquals("2 arquivo(s) · total 4,3 GB", linhas.first())
        assertEquals("Download concluído · 4,3 GB no total", linhas.last())
        assertTrue(linhas[2].contains("100%"))
    }

    @Test
    fun `o log distingue o total desconhecido`() {
        val log = DownloadSizeFormat.Log()
        log.inicio("1 arquivo")
        log.fim(0L)
        assertEquals("Download concluído", log.linhas().last())
    }

    @Test
    fun `pendente separa o que ja esta no aparelho`() {
        val plano = DownloadSizeFormat.Plano(
            "NAR",
            listOf(
                DownloadSizeFormat.Arquivo("pesos.data", 1_000L),
                DownloadSizeFormat.Arquivo("llm.onnx.data", 3_000L, jaBaixado = true),
            ),
        )
        assertEquals(4_000L, plano.totalBytes)
        assertEquals(1_000L, plano.pendenteBytes)
        assertTrue(!plano.completo)
        val linhas = num(DownloadSizeFormat.plano(plano)).lines()
        assertTrue("faltam no cabecalho: ${linhas[0]}", linhas[0].contains("faltam 1.0 KB"))
        // O já baixado sai marcado.
        assertTrue(linhas.any { it.trimStart().startsWith("✓") && it.contains("llm.onnx.data") })
    }

    @Test
    fun `completo quando todos os arquivos ja estao no aparelho`() {
        val plano = DownloadSizeFormat.Plano(
            "x",
            listOf(
                DownloadSizeFormat.Arquivo("a", 10L, jaBaixado = true),
                DownloadSizeFormat.Arquivo("b", 20L, jaBaixado = true),
            ),
        )
        assertTrue(plano.completo)
        assertEquals(0L, plano.pendenteBytes)
    }

    @Test
    fun `resumoLinha cabe em uma linha e diz o total`() {
        val texto = num(
            DownloadSizeFormat.resumoLinha(
                DownloadSizeFormat.Plano("Componentes", listOf(DownloadSizeFormat.Arquivo("a", 2_000L)))
            )
        )
        assertEquals("Componentes: 1 arquivo(s) · total 2.0 KB", texto)
    }

    @Test
    fun `progresso mostra baixado de total no estilo do SigUpdater`() {
        val texto = num(DownloadSizeFormat.progresso(45_200_000L, 67_700_000L, 68))
        assertEquals("Baixando: 68% (45.2 MB de 67.7 MB)", texto)
    }

    @Test
    fun `progresso sem total conhecido nao inventa numero`() {
        val texto = num(DownloadSizeFormat.progresso(1_000L, 0L, -1))
        assertEquals("Baixando: -1% (1.0 KB)", texto)
    }

    @Test
    fun `arquivoAtual mostra indice de total e nome como no Windows`() {
        val texto = DownloadSizeFormat.arquivoAtual(7, 18, "lib/libsig_llama.so", 68)
        assertEquals("Baixando arquivo 7/18: lib/libsig_llama.so (68%)", texto)
    }

    @Test
    fun `nome longo e truncado no meio para nao estourar a tela`() {
        val plano = DownloadSizeFormat.Plano(
            "x",
            listOf(DownloadSizeFormat.Arquivo("a".repeat(80), 1L)),
        )
        val linha = DownloadSizeFormat.plano(plano).lines()[1]
        // O padEnd sozinho não impede a linha de ser larga demais: precisa truncar.
        assertTrue("linha muito longa (${linha.length}): $linha", linha.length < 60)
        assertTrue("sem reticencias: $linha", linha.contains("…"))
    }

    @Test
    fun `nome ja cabendo nao e alterado`() {
        val plano = DownloadSizeFormat.Plano("x", listOf(DownloadSizeFormat.Arquivo("vocab.json", 1L)))
        assertTrue(DownloadSizeFormat.plano(plano).lines()[1].contains("vocab.json"))
    }
}
