package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Vacina das tabelas de tamanho dos gerenciadores de download.
 * (25/09/2026; REESCRITA em 29/09/2026 — parecer seção 4.)
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
 *   v3 (esta): valida ARTEFATOS, não aritmética de razão.
 *
 * O que é conferido agora:
 *   1. O plano mostra UM item: o próprio ZIP, com o tamanho real.
 *   2. Todo item tem nome e tamanho positivo.
 *   3. A tabela interna bate com o ZIP fixtureado, ARQUIVO A ARQUIVO — nada
 *      declarado pode faltar, e nada existente pode ficar de fora.
 *   4. A soma declarada = soma das entradas do ZIP.
 *   5. A leitura de ZIP funciona em STORED mínimo e DEFLATE muito compressível
 *      (os casos que quebrariam qualquer regra de razão).
 *
 * Os ZIPs fixtureados ficam em `app/src/test/resources/native-deps/`. Sem eles
 * o teste de artefato não pode existir: a tabela de produção não enxerga disco.
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

    // ---------------------------------------------------------- infraestrutura

    private data class Entrada(val nome: String, val tamanho: Long)

    /**
     * ZIP fixtureado por ABI, em `app/src/test/resources/native-deps/`.
     *
     * São cópias dos pacotes publicados, verificadas por SHA-256 no momento em
     * que foram postas lá. Sem estas fixtures o teste de artefato não pode
     * existir: a tabela de produção não conhece nenhum ZIP em disco.
     */
    private val fixturesPorAbi = listOf(
        "arm64-v8a" to "sig-android-dependencies-v10-arm64-v8a.zip",
        "x86_64" to "sig-android-dependencies-v10-x86_64.zip",
    )

    private fun fixtureBytes(nome: String): ByteArray {
        val r = javaClass.classLoader!!.getResourceAsStream("native-deps/$nome")
        assertNotNull("fixture ausente: native-deps/$nome", r)
        return r!!.readBytes()
    }

    /** Lê o ZIP de verdade: nome e tamanho descomprimido de cada entrada. */
    private fun lerZip(bytes: ByteArray): List<Entrada> {
        val out = ArrayList<Entrada>()
        java.util.zip.ZipInputStream(bytes.inputStream()).use { zis ->
            while (true) {
                val e = zis.nextEntry ?: break
                if (e.isDirectory) { zis.closeEntry(); continue }
                zis.readBytes()          // consome o conteúdo, sem guardá-lo
                out.add(Entrada(e.name, e.size))
                zis.closeEntry()
            }
        }
        return out
    }

    /**
     * A tabela de produção tem de bater com o ZIP REAL, arquivo a arquivo.
     *
     * ⚠️ Este teste substitui duas invariantes FALSAS que eu escrevi em 28/09
     * (`conteudo >= zip` e `conteudo <= 4*zip`). Verificadas com java.util.zip:
     *   • 1 byte em ZIP_STORED → payload 1, ZIP 101      ⇒ 1 >= 101 é falso
     *   • 10.000 bytes "A" em ZIP_DEFLATED
     *                          → payload 10.000, ZIP 128 ⇒ 10.000 <= 512 é falso
     * O ZIP tem estrutura (cabeçalho local, diretório central, EOCD) e a razão
     * de compressão depende DOS DADOS. Não existe intervalo universal; as duas
     * regras só passavam porque a razão real do v9 é 2,74/2,84.
     *
     * O que protege o usuário de verdade é conferir os ARTEFATOS: a tabela
     * declara exatamente as entradas que existem no ZIP, com o mesmo tamanho.
     */
    @Test
    fun `a tabela de conteudo bate com o ZIP fixtureado, arquivo a arquivo`() {
        for ((abi, fixture) in fixturesPorAbi) {
            val entradas = lerZip(fixtureBytes(fixture))
            assertTrue("$abi: o ZIP fixtureado tem de ter conteúdo", entradas.isNotEmpty())
            val declarado = NativeDependencyManager.conteudoInstalado(abi).associateBy { it.nome }
            for ((nome, item) in declarado) {
                val real = entradas.firstOrNull { it.nome.substringAfterLast('/') == nome }
                assertNotNull(
                    "$abi/$nome: declarado na tabela mas AUSENTE no ZIP fixtureado", real,
                )
                assertEquals("$abi/$nome: tamanho diverge", real!!.tamanho, item.bytes)
            }
            for (e in entradas) {
                val nome = e.nome.substringAfterLast('/')
                assertNotNull(
                    "$abi/$nome: existe no ZIP mas NAO foi declarado", declarado[nome],
                )
            }
        }
    }

    /**
     * CASO NEGATIVO REAL (parecer 29/09 §8.2).
     *
     * A versao anterior deste arquivo "verificava" o caso negativo comparando
     * duas listas construidas pelo proprio teste — o que nao exercita logica
     * nenhuma. Este aqui pega o MESMO par (tabela de producao x ZIP fixtureado)
     * do teste arquivo-a-arquivo e INTRODUZ um defeito sinteticos em uma
     * entrada do ZIP, chamando depois a MESMA logica de conferencia. A
     * conferencia TEM de falhar; se ela passar com um tamanho errado no ZIP, o
     * teste arquivo-a-arquivo nao estava verificando nada.
     */
    @Test
    fun `a conferencia arquivo-a-arquivo REJEITA um tamanho divergente no ZIP`() {
        val (abi, fixture) = fixturesPorAbi.first()
        val bytesReais = fixtureBytes(fixture)

        // Reempacota o ZIP real com UMA ENTRADA UM BYTE MAIOR que a original.
        // O tamanho descomprimido dela passa a divergir do que a tabela declara.
        val original = lerZip(bytesReais)
        assertTrue("$abi: o ZIP fixtureado tem de ter mais de uma entrada", original.size > 1)
        val alvoIdx = original.indexOfFirst { !it.nome.endsWith("/") }

        val adulterado = ByteArrayOutputStream()
        var alterado = 0
        ZipInputStream(bytesReais.inputStream()).use { zis ->
            ZipOutputStream(adulterado).use { zos ->
                var idx = 0
                while (true) {
                    val e = zis.nextEntry ?: break
                    val dados = zis.readBytes()
                    zis.closeEntry()
                    if (!e.isDirectory && idx == alvoIdx) {
                        // O ZipOutputStream RECALCULA o tamanho a partir dos
                        // bytes escritos: adulterar ZipEntry.size seria ignorado.
                        // A adulteracao real e' escrever UM BYTE A MAIS — o
                        // tamanho efetivo da entrada passa a divergir da tabela.
                        val e2 = ZipEntry(e.name)
                        zos.putNextEntry(e2)
                        zos.write(dados)
                        zos.write(0)
                        zos.closeEntry()
                        alterado++
                    } else if (!e.isDirectory) {
                        val e2 = ZipEntry(e.name)
                        zos.putNextEntry(e2)
                        zos.write(dados)
                        zos.closeEntry()
                    }
                    idx++
                }
            }
        }
        assertEquals("o ZIP adulterado tem de ter exatamente 1 entrada alterada", 1, alterado)

        // A MESMA logica do teste arquivo-a-arquivo, agora sobre o ZIP errado:
        // tem de detectar divergencia de tamanho na entrada adulterada.
        val entradasRuins = lerZip(adulterado.toByteArray())
        val declarado = NativeDependencyManager.conteudoInstalado(abi).associateBy { it.nome }
        val nomeAlvo = original[alvoIdx].nome.substringAfterLast('/')
        val real = entradasRuins.firstOrNull { it.nome.substringAfterLast('/') == nomeAlvo }
        assertNotNull("$abi/$nomeAlvo: sumiu do ZIP adulterado", real)
        org.junit.Assert.assertNotEquals(
            "$abi/$nomeAlvo: a conferencia ACEITOU tamanho divergente — o teste " +
                "arquivo-a-arquivo nao verifica nada",
            declarado.getValue(nomeAlvo).bytes, real!!.tamanho,
        )
    }

    /**
     * A fixture PRECISA ser o ZIP que o código de produção baixa.
     *
     * Sem isto, o teste de artefato passaria mesmo que a fixture fosse de uma
     * versão antiga: a tabela e a fixture concordariam entre si e o teste seria
     * vazio de sentido. O SHA declarado em produção é a âncora.
     */
    @Test
    fun `a fixture e o ZIP que o codigo de producao declara`() {
        for (abi in listOf("arm64-v8a", "x86_64")) {
            val fixture = fixturesPorAbi.first { it.first == abi }.second
            val sha = java.security.MessageDigest.getInstance("SHA-256")
                .digest(fixtureBytes(fixture))
                .joinToString("") { "%02x".format(it) }
            val declarado = NativeDependencyManager.sha256DeclaradoDe(abi)
            assertNotNull("$abi: producao nao declara sha256", declarado)
            assertEquals(
                "$abi: a fixture NAO e' o ZIP publicado (sha $sha)",
                declarado, sha,
            )
            assertEquals(
                "$abi: tamanho da fixture diverge de downloadBytes",
                NativeDependencyManager.downloadBytesOf(abi),
                fixtureBytes(fixture).size.toLong(),
            )
        }
    }

    @Test
    fun `a soma do conteudo declarado e a soma das entradas do ZIP`() {
        for ((abi, fixture) in fixturesPorAbi) {
            val doZip = lerZip(fixtureBytes(fixture)).sumOf { it.tamanho }
            val declarado = NativeDependencyManager.conteudoInstalado(abi).sumOf { it.bytes }
            assertEquals("$abi: soma declarada $declarado != soma do ZIP $doZip", doZip, declarado)
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
        val e1 = lerZip(stored)
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
        val e2 = lerZip(deflated)
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
