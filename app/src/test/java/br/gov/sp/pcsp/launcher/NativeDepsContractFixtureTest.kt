package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Testes de CONTRATO dos pacotes nativos — fixture MINIMAL determinística.
 *
 * Roda em checkout limpo, SEM rede e SEM os ZIPs reais de 50 MB (que ficam
 * fora do Git). Exercita a MESMA lógica de conferência usada pelo teste
 * official (NativeDepsOfficialFixturesTest, que valida os ZIPs reais v10 no
 * momento da release): aqui os valores são sintéticos e estáveis.
 *
 * A fixture `native-deps/contract-min.zip` (≈1 KB, versionada) é gerada por
 * `scripts/gen-native-deps-fixture.py` — conteúdo fixo, timestamps fixos,
 * SHA-256 estável. O teste confere os tamanhos por nome; se o gerador mudar,
 * os valores esperados AQUI precisam mudar juntos (é o ponto: fixture
 * determinística revisada, não derivada de artefato de 50 MB).
 *
 * O que NÃO mora aqui: os VALORES REAIS de produção (tabela conteudoPorAbi,
 * sha256 declarado, ZIPs reais). Isso é o official — de propósito separado,
 * para o unit rodar em checkout limpo sem enfraquecer o gate de release.
 */
class NativeDepsContractFixtureTest {

    private val fixture = "contract-min.zip"

    /**
     * Valores esperados da fixture — batem com ENTRIES do gerador
     * `scripts/gen-native-deps-fixture.py`. Tamanhos DESCOMPRIMIDOS.
     */
    private val esperado = mapOf(
        "manifest.json" to 34L,
        "libalpha.so" to 1000L,
        "libbeta.so" to 2000L,
        "tiny.bin" to 512L,
    )

    private fun bytesDaFixture(): ByteArray {
        val b = NativeDepsTestSupport.fixtureDoClasspath(fixture)
        assertTrue("fixture ausente: native-deps/$fixture (rode scripts/gen-native-deps-fixture.py)", b != null)
        return b!!
    }

    @Test
    fun `a fixture minimal existe e tem a estrutura esperada`() {
        val entradas = NativeDepsTestSupport.lerZip(bytesDaFixture())
        assertEquals("a fixture tem de ter ${esperado.size} entradas", esperado.size, entradas.size)
        for ((nome, tamanho) in esperado) {
            val e = entradas.firstOrNull { it.nome.substringAfterLast('/') == nome }
            assertTrue("$nome ausente na fixture", e != null)
            assertEquals("$nome: tamanho descomprimido divergente", tamanho, e!!.tamanho)
        }
        // A fixture tem de manter o prefixo lib/ (estrutura do pacote real).
        assertTrue(
            "a fixture precisa preservar a estrutura lib/",
            entradas.any { it.nome.startsWith("lib/") },
        )
    }

    @Test
    fun `a conferencia ACEITA a fixture minimal (positivo)`() {
        val divergencias = NativeDepsTestSupport.conferir(
            NativeDepsTestSupport.lerZip(bytesDaFixture()), esperado,
        )
        assertTrue("conferência positiva falhou: $divergencias", divergencias.isEmpty())
    }

    @Test
    fun `a conferencia REJEITA entry faltante`() {
        // Reempacota a fixture SEM a primeira entry não-diretório.
        val semUma = ByteArrayOutputStream()
        var pulei = false
        java.util.zip.ZipInputStream(bytesDaFixture().inputStream()).use { zis ->
            ZipOutputStream(semUma).use { zos ->
                while (true) {
                    val e = zis.nextEntry ?: break
                    val dados = zis.readBytes()
                    zis.closeEntry()
                    if (!e.isDirectory && !pulei) { pulei = true; continue }
                    if (!e.isDirectory) {
                        zos.putNextEntry(ZipEntry(e.name)); zos.write(dados); zos.closeEntry()
                    }
                }
            }
        }
        assertTrue("o reempacotamento tinha de pular uma entrada", pulei)
        val divergencias = NativeDepsTestSupport.conferir(
            NativeDepsTestSupport.lerZip(semUma.toByteArray()), esperado,
        )
        assertTrue(
            "a conferência ACEITOU entry faltante — não verifica nada: $divergencias",
            divergencias.any { it.contains("AUSENTE") },
        )
    }

    @Test
    fun `a conferencia REJEITA entry extra`() {
        val comExtra = ByteArrayOutputStream()
        java.util.zip.ZipInputStream(bytesDaFixture().inputStream()).use { zis ->
            ZipOutputStream(comExtra).use { zos ->
                while (true) {
                    val e = zis.nextEntry ?: break
                    val dados = zis.readBytes()
                    zis.closeEntry()
                    if (!e.isDirectory) {
                        zos.putNextEntry(ZipEntry(e.name)); zos.write(dados); zos.closeEntry()
                    }
                }
                zos.putNextEntry(ZipEntry("lib/intruso.so")); zos.write("x".toByteArray()); zos.closeEntry()
            }
        }
        val divergencias = NativeDepsTestSupport.conferir(
            NativeDepsTestSupport.lerZip(comExtra.toByteArray()), esperado,
        )
        assertTrue(
            "a conferência ACEITOU entry extra — não verifica nada: $divergencias",
            divergencias.any { it.contains("NAO foi declarado") },
        )
    }

    @Test
    fun `a conferencia REJEITA tamanho divergente (adulterado)`() {
        val adulterado = NativeDepsTestSupport.adulterarUmByte(bytesDaFixture(), indice = 0)
        val divergencias = NativeDepsTestSupport.conferir(
            NativeDepsTestSupport.lerZip(adulterado), esperado,
        )
        assertTrue(
            "a conferência ACEITOU tamanho divergente — não verifica nada: $divergencias",
            divergencias.any { it.contains("tamanho diverge") },
        )
    }

    @Test
    fun `o sha256 detecta corrupcao de 1 byte`() {
        val original = bytesDaFixture()
        val adulterado = NativeDepsTestSupport.adulterarUmByte(original, indice = 0)
        assertNotEquals(
            "sha256 igual após corrupção — o mecanismo de verificação de download não detectaria",
            NativeDepsTestSupport.sha256(original),
            NativeDepsTestSupport.sha256(adulterado),
        )
    }
}
