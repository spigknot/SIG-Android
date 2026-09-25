package br.gov.sp.pcsp.launcher

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Vacina do bug "Pacote incompleto." (24/09): pacotes gerados no Windows
 * (.NET ZipFile.CreateFromDirectory) gravam '\' (0x5c) como separador nas
 * entradas ZIP. O ZipInputStream entrega o nome cru; sem normalização a
 * pasta lib/ nunca existe no staging e o check de requiredLibraries falha.
 */
class NativeDependencyManagerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `normalizeZipEntryName converte barra invertida em separador`() {
        assertEquals(
            "lib/libsig_llama.so",
            NativeDependencyManager.normalizeZipEntryName("lib\\libsig_llama.so")
        )
        assertEquals(
            "lib/libsig_llama.so",
            NativeDependencyManager.normalizeZipEntryName("lib/libsig_llama.so")
        )
        assertEquals("a/b/c.so", NativeDependencyManager.normalizeZipEntryName("a\\b\\c.so"))
        assertEquals("manifest.json", NativeDependencyManager.normalizeZipEntryName("manifest.json"))
    }

    @Test
    fun `extractSecurely extrai pacote gerado no Windows com barra invertida`() {
        val zip = tmp.newFile("pacote-windows.zip")
        ZipOutputStream(FileOutputStream(zip)).use { out ->
            for (name in listOf("manifest.json", "lib\\libA.so", "lib\\libB.so", "models\\modelo.bin")) {
                out.putNextEntry(ZipEntry(name))
                out.write(byteArrayOf(1, 2, 3))
                out.closeEntry()
            }
        }
        val dest = File(tmp.root, "staging")
        NativeDependencyManager.extractSecurely(zip, dest)
        assertTrue(File(dest, "lib/libA.so").isFile)
        assertTrue(File(dest, "lib/libB.so").isFile)
        assertTrue(File(dest, "models/modelo.bin").isFile)
        assertTrue(File(dest, "manifest.json").isFile)
    }

    @Test
    fun `extractSecurely rejeita entrada com zip-slip`() {
        val zip = tmp.newFile("malicioso.zip")
        ZipOutputStream(FileOutputStream(zip)).use { out ->
            out.putNextEntry(ZipEntry("../fuga.so"))
            out.write(byteArrayOf(1))
            out.closeEntry()
        }
        val dest = File(tmp.root, "staging")
        val erro = runCatching {
            NativeDependencyManager.extractSecurely(zip, dest)
        }.exceptionOrNull()
        assertTrue(erro is IllegalStateException)
        assertEquals("Entrada ZIP inválida.", erro?.message)
    }
}
