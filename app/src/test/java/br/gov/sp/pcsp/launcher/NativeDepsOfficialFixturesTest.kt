package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

/**
 * Validação OFFICIAL: tabela de produção x ZIPs REAIS v10 (≈50 MB por ABI).
 *
 * Os ZIPs reais ficam em `app/src/test/resources/native-deps/` e NÃO são
 * versionados (são os artefatos de release). Por isso este teste é PULADO
 * (Assume) quando eles não estão presentes — checkout/CI limpo. Ele roda
 * localmente e no momento da release, quando os ZIPs estão em disco.
 *
 * A regra conferida é a MESMA do teste de contrato
 * (NativeDepsContractFixtureTest), via NativeDepsTestSupport.conferir:
 * nada declarado pode faltar, nada existente pode ficar de fora, e os
 * tamanhos têm de bater. Aqui os valores são os REAIS de produção.
 */
class NativeDepsOfficialFixturesTest {

    private val fixturesPorAbi = listOf(
        "arm64-v8a" to "sig-android-dependencies-v10-arm64-v8a.zip",
        "x86_64" to "sig-android-dependencies-v10-x86_64.zip",
    )

    private fun fixtureBytes(nome: String): ByteArray? =
        NativeDepsTestSupport.fixtureDoClasspath(nome)

    private fun fixturePresente(): Boolean =
        fixturesPorAbi.any { fixtureBytes(it.second) != null }

    private fun exigir(abi: String, nome: String): ByteArray {
        val b = fixtureBytes(nome)
        assertNotNull("$abi: fixture ausente — native-deps/$nome", b)
        return b!!
    }

    @Test
    fun `a tabela de conteudo bate com o ZIP real, arquivo a arquivo`() {
        Assume.assumeTrue("ZIPs reais v10 ausentes (checkout limpo) — teste official pulado", fixturePresente())
        for ((abi, fixture) in fixturesPorAbi) {
            val entradas = NativeDepsTestSupport.lerZip(exigir(abi, fixture))
            assertTrue("$abi: o ZIP fixtureado tem de ter conteúdo", entradas.isNotEmpty())
            val declarado = NativeDependencyManager.conteudoInstalado(abi)
                .associate { it.nome to it.bytes }
            val divergencias = NativeDepsTestSupport.conferir(entradas, declarado)
            assertTrue("$abi: divergências tabela x ZIP real: $divergencias", divergencias.isEmpty())
        }
    }

    @Test
    fun `a soma do conteudo declarado e a soma das entradas do ZIP real`() {
        Assume.assumeTrue("ZIPs reais v10 ausentes (checkout limpo) — teste official pulado", fixturePresente())
        for ((abi, fixture) in fixturesPorAbi) {
            val doZip = NativeDepsTestSupport.lerZip(exigir(abi, fixture)).sumOf { it.tamanho }
            val declarado = NativeDependencyManager.conteudoInstalado(abi).sumOf { it.bytes }
            assertEquals("$abi: soma declarada $declarado != soma do ZIP $doZip", doZip, declarado)
        }
    }

    /**
     * A fixture PRECISA ser o ZIP que o código de produção baixa.
     * Sem isto, o teste arquivo-a-arquivo passaria mesmo que a fixture fosse
     * de uma versão antiga. O SHA declarado em produção é a âncora — e o
     * tamanho declarado (downloadBytesOf) confere de novo.
     */
    @Test
    fun `as fixtures sao os ZIPs que o codigo de producao declara (sha256)`() {
        Assume.assumeTrue("ZIPs reais v10 ausentes (checkout limpo) — teste official pulado", fixturePresente())
        for ((abi, fixture) in fixturesPorAbi) {
            val bytes = exigir(abi, fixture)
            val sha = NativeDepsTestSupport.sha256(bytes)
            val declarado = NativeDependencyManager.sha256DeclaradoDe(abi)
            assertNotNull("$abi: producao nao declara sha256", declarado)
            assertEquals("$abi: a fixture NAO e' o ZIP publicado (sha $sha)", declarado, sha)
            assertEquals(
                "$abi: tamanho da fixture diverge de downloadBytes",
                NativeDependencyManager.downloadBytesOf(abi),
                bytes.size.toLong(),
            )
        }
    }

    /**
     * CASO NEGATIVO REAL (parecer 29/09 §8.2), agora sobre o ZIP real:
     * reempacota com UMA ENTRADA UM BYTE MAIOR e confere que a MESMA regra
     * de conferência detecta. Se passasse, o teste arquivo-a-arquivo não
     * estaria verificando nada.
     */
    @Test
    fun `a conferencia arquivo-a-arquivo REJEITA um tamanho divergente no ZIP real`() {
        Assume.assumeTrue("ZIPs reais v10 ausentes (checkout limpo) — teste official pulado", fixturePresente())
        val (abi, fixture) = fixturesPorAbi.first()
        val bytesReais = exigir(abi, fixture)
        val original = NativeDepsTestSupport.lerZip(bytesReais)
        assertTrue("$abi: o ZIP tem de ter mais de uma entrada", original.size > 1)

        val adulterado = NativeDepsTestSupport.adulterarUmByte(bytesReais, indice = 0)
        val declarado = NativeDependencyManager.conteudoInstalado(abi)
            .associate { it.nome to it.bytes }
        val divergencias = NativeDepsTestSupport.conferir(
            NativeDepsTestSupport.lerZip(adulterado), declarado,
        )
        assertTrue(
            "$abi: a conferência ACEITOU tamanho divergente — o teste arquivo-a-arquivo não verifica nada: $divergencias",
            divergencias.any { it.contains("tamanho diverge") },
        )
    }
}
