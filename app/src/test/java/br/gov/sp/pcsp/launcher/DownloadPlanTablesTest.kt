package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Vacina das tabelas de tamanho dos gerenciadores de download.
 * (25/09/2026; REESCRITA em 29/09/2026 — parecer seção 4;
 *  SEPARADA em 05/10/2026 em três testes, para checkout limpo.)
 *
 * ⚠️ HISTÓRICO DAS INVARIANTES ERRADAS. Não as repita:
 *
 *   v1: `abs(soma - zip) * 100 < zip`   (soma descomprimida vs ZIP comprimido)
 *       FALHAVA com o v9: o v8 era STORED (razão 1,00), o v9 é DEFLATE
 *       (razão 2,74). "ZIP ≈ soma" era acidente do build v8, não propriedade.
 *
 *   v2 (minha, 28/09): `conteudo >= zip` e `conteudo <= 4*zip`
 *       AMBAS FALSAS, verificadas com java.util.zip:
 *         • 1 byte em ZIP_STORED  → payload 1, ZIP 101      ⇒ 1 >= 101 falso
 *         • 10.000 "A" em DEFLATE → payload 10.000, ZIP 128 ⇒ 10.000 <= 512 falso
 *       O ZIP tem estrutura (cabeçalho local, diretório central, EOCD) e a
 *       razão de compressão depende DOS DADOS. Não existe intervalo universal.
 *
 *   v3 (29/09): valida ARTEFATOS, não aritmética de razão.
 *
 *   v4 (05/10, checkout limpo): os testes deste arquivo NÃO dependem de
 *       nenhuma fixture. A conferência com os ZIPs REAIS (≈50 MB, fora do
 *       Git) mora em `NativeDepsOfficialFixturesTest` — pulado (Assume)
 *       quando os ZIPs não estão em disco; a lógica de conferência é
 *       exercitada em `NativeDepsContractFixtureTest` com a fixture MINIMAL
 *       determinística (`contract-min.zip`, ≈1 KB, versionada, gerada por
 *       `scripts/gen-native-deps-fixture.py`). Este arquivo cobre o plano,
 *       contagens, e a leitura de ZIP nos extremos (STORED/DEFLATE).
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

    /** Comprova que a leitura de ZIP e as somas funcionam em casos extremos. */
    @Test
    fun `a leitura de ZIP funciona em STORED minimo e em DEFLATE compressivel`() {
        val stored = ByteArrayOutputStream().also { bo ->
            ZipOutputStream(bo).use { z ->
                z.setLevel(0)
                z.putNextEntry(ZipEntry("a.txt")); z.write("x".toByteArray()); z.closeEntry()
            }
        }.toByteArray()
        val e1 = NativeDepsTestSupport.lerZip(stored)
        assertEquals(1, e1.size)
        assertEquals(1L, e1[0].tamanho)
        assertTrue("ZIP STORED tem de ser MAIOR que o payload", stored.size > e1[0].tamanho)

        val deflated = ByteArrayOutputStream().also { bo ->
            ZipOutputStream(bo).use { z ->
                z.setLevel(9)
                z.putNextEntry(ZipEntry("b.txt"))
                z.write("A".repeat(10_000).toByteArray()); z.closeEntry()
            }
        }.toByteArray()
        val e2 = NativeDepsTestSupport.lerZip(deflated)
        assertEquals(1, e2.size)
        assertEquals(10_000L, e2[0].tamanho)
        assertTrue("DEFLATE de dado repetido tem de ser MENOR que o payload",
            deflated.size < e2[0].tamanho)
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
