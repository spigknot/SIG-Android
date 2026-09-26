package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Vacina das tabelas de tamanho dos gerenciadores de download (25/09/2026).
 *
 * Trava três coisas:
 *
 *  1. **ZIP é arquivo único.** O app baixa UM `.zip`; a tela tem de mostrar o
 *     ZIP, não as libs de dentro (seria mentira sobre o tráfego). O conteúdo
 *     interno continua conferido aqui, para o pacote republicado não passar.
 *  2. **A soma do conteúdo bate com o ZIP publicado** (dentro de 1%): se uma
 *     lib nova entrar no pacote sem ninguém atualizar a tabela, a porta
 *     de aceitação acusa.
 *  3. **Todo item tem nome e tamanho positivo** — a lista do diálogo não pode
 *     mostrar linha vazia nem "0 B".
 */
class DownloadPlanTablesTest {

    @Test
    fun `o plano nativo e um item so o proprio ZIP`() {
        for (abi in listOf("arm64-v8a", "x86_64")) {
            val plan = NativeDependencyManager.downloadPlan(abi)
            assertEquals("$abi deveria mostrar 1 item (o ZIP)", 1, plan.arquivos.size)
            val item = plan.arquivos.single()
            assertTrue("$abi: nome deveria ser o .zip", item.nome.endsWith(".zip"))
            assertEquals(
                "$abi: o item precisa ser o tamanho real do ZIP",
                NativeDependencyManager.downloadBytesOf(abi),
                item.bytes,
            )
            assertEquals("$abi: total do plano = total do ZIP", item.bytes, plan.totalBytes)
        }
    }

    @Test
    fun `o conteudo interno do nativo tem 18 itens por ABI`() {
        for (abi in listOf("arm64-v8a", "x86_64")) {
            val conteudo = NativeDependencyManager.conteudoInstalado(abi)
            assertEquals("$abi com contagem diferente", 18, conteudo.size)
            assertTrue(
                "$abi: o pacote tem de conter a lib do llama",
                conteudo.any { it.nome == "libsig_llama.so" },
            )
            assertTrue(
                "$abi: o pacote tem de conter o modelo do VAD",
                conteudo.any { it.nome.startsWith("ggml-silero") },
            )
        }
    }

    @Test
    fun `a soma do conteudo do nativo fecha com o ZIP publicado`() {
        for (abi in listOf("arm64-v8a", "x86_64")) {
            val conteudo = NativeDependencyManager.conteudoInstalado(abi).sumOf { it.bytes }
            val zip = NativeDependencyManager.downloadBytesOf(abi)
            val dif = kotlin.math.abs(conteudo - zip)
            assertTrue(
                "$abi: conteudo=$conteudo zip=$zip dif=$dif",
                dif * 100 < zip,
            )
        }
    }

    @Test
    fun `o plano do QAIRT e um item so o proprio ZIP`() {
        val plan = QairtDependencyManager.downloadPlan()
        assertEquals(1, plan.arquivos.size)
        val item = plan.arquivos.single()
        assertTrue("nome deveria ser o .zip", item.nome.endsWith(".zip"))
        assertEquals(QairtDependencyManager.downloadSize(), item.bytes)
    }

    @Test
    fun `todo item tem nome e tamanho positivo`() {
        val planos = listOf(
            NativeDependencyManager.downloadPlan("arm64-v8a"),
            QairtDependencyManager.downloadPlan(),
            GraniteEngine.downloadPlan(),
        )
        for (plan in planos) {
            assertTrue("${plan.rotulo} sem itens", plan.arquivos.isNotEmpty())
            for (item in plan.arquivos) {
                assertTrue("${plan.rotulo}: '${item.nome}' sem nome", item.nome.isNotBlank())
                assertTrue("${plan.rotulo}: '${item.nome}' com ${item.bytes} bytes", item.bytes > 0L)
            }
        }
    }
}
