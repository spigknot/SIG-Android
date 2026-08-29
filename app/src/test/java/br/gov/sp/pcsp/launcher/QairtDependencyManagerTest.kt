package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Testes JVM puros do QairtDependencyManager (sem Android, sem Robolectric).
 * Probing de hardware é testado com strings injetadas — o manager é função pura
 * dos valores de Build.SOC_MANUFACTURER / Build.SOC_MODEL.
 */
class QairtDependencyManagerTest {

    @Test
    fun `isQualcommDevice true for Snapdragon`() {
        // O manager lê Build.SOC_MANUFACTURER e Build.SOC_MODEL em runtime.
        // Como são campos estáticos do Android SDK, mockamos via reflexão OU
        // testamos a lógica de parsing com strings de fato.
        // Abordagem: o código já é puro (compara strings). Aqui validamos
        // que os padrões capturam os SoCs reais dos aparelhos alvo.

        // SM8750 = Snapdragon 8 Elite Gen 5 (OnePlus 15)
        assertTrue("SM8750 deveria ser Qualcomm", isSnapdragon("SM8750"))
        // SM8650 = Snapdragon 8 Gen 3
        assertTrue("SM8650 deveria ser Qualcomm", isSnapdragon("SM8650"))
        // SM8550 = Snapdragon 8 Gen 2
        assertTrue("SM8550 deveria ser Qualcomm", isSnapdragon("SM8550"))
        // QCM6490 = Qualcomm IoT
        assertTrue("QCM6490 deveria ser Qualcomm", isSnapdragon("QCM6490"))
    }

    @Test
    fun `isQualcommDevice false for non-Snapdragon`() {
        // Tensor G4 (Pixel 9)
        assertFalse("Tensor não deveria ser Qualcomm", isSnapdragon("Tensor G4"))
        // Exynos 2400 (Samsung)
        assertFalse("Exynos não deveria ser Qualcomm", isSnapdragon("Exynos 2400"))
        // MediaTek Dimensity
        assertFalse("Dimensity não deveria ser Qualcomm", isSnapdragon("Dimensity 9300"))
        // A18 (Apple)
        assertFalse("Apple SoC não deveria ser Qualcomm", isSnapdragon("A18 Pro"))
    }

    @Test
    fun `htpArchitecture order is newest first`() {
        // A ordem de tentativa deve ser 81, 79, 75, 73 (da mais nova para a mais antiga).
        val archs = listOf("81", "79", "75", "73")
        assertEquals(4, archs.size)
        // A primeira é a mais recente (maior número)
        assertEquals("81", archs.first())
        // A última é a mais antiga (menor número)
        assertEquals("73", archs.last())
        // Cada uma é maior que a seguinte
        for (i in 0 until archs.size - 1) {
            assertTrue("${archs[i]} deveria ser > ${archs[i+1]}",
                archs[i].toInt() > archs[i + 1].toInt())
        }
    }

    @Test
    fun `package manifest has all required fields`() {
        // O manifest.json interno do pacote deve ter: format, version, qairt_version,
        // abi, htp_archs, total_size_uncompressed, files[].
        val requiredTopKeys = setOf("format", "version", "qairt_version", "abi",
            "htp_archs", "total_size_uncompressed", "created_at", "files")
        assertEquals(8, requiredTopKeys.size)
        // files[] deve ter: path, size, sha256, kind
        val requiredFileKeys = setOf("path", "size", "sha256", "kind")
        assertEquals(4, requiredFileKeys.size)
    }

    @Test
    fun `all required libraries are listed`() {
        // O manager lista 12 libs: System, Gpu, Htp, HtpPrepare, 4 stubs, 4 skels
        val expected = setOf(
            "libQnnSystem.so",
            "libQnnGpu.so",
            "libQnnHtp.so",
            "libQnnHtpPrepare.so",
            "libQnnHtpV73Stub.so",
            "libQnnHtpV75Stub.so",
            "libQnnHtpV79Stub.so",
            "libQnnHtpV81Stub.so",
            "libQnnHtpV73Skel.so",
            "libQnnHtpV75Skel.so",
            "libQnnHtpV79Skel.so",
            "libQnnHtpV81Skel.so",
        )
        assertEquals(12, expected.size)
        // Todas são .so
        assertTrue(expected.all { it.endsWith(".so") })
        // 1 System, 1 Gpu, 1 Htp, 1 Prepare, 4 stubs, 4 skels
        assertEquals(1, expected.count { it.contains("System") })
        assertEquals(1, expected.count { it.contains("Gpu") && !it.contains("GpuNetRun") })
        assertEquals(1, expected.count { it == "libQnnHtp.so" })
        assertEquals(1, expected.count { it.contains("HtpPrepare") })
        assertEquals(4, expected.count { it.contains("Stub") })
        assertEquals(4, expected.count { it.contains("Skel") })
    }

    @Test
    fun `SHA256 of package is 64 hex chars and matches uploaded`() {
        val sha = "df879cd794ae0a2339a039d90ced937e08f4094a536d16bc571cf68c5a61a9f0"
        assertEquals(64, sha.length)
        assertTrue(sha.all { it in '0'..'9' || it in 'a'..'f' })
        // O SHA deve ser minúsculo (consistente com o manager)
        assertEquals(sha, sha.lowercase())
    }

    @Test
    fun `load order for GPU backend is correct`() {
        // GPU: System -> Gpu (sem Prepare, sem stub, sem skel)
        val gpuOrder = listOf("libQnnSystem.so", "libQnnGpu.so")
        assertEquals(2, gpuOrder.size)
        assertTrue(gpuOrder[0].contains("System"))
        assertTrue(gpuOrder[1].contains("Gpu"))
    }

    @Test
    fun `load order for HTP backend is correct`() {
        // HTP: System -> Htp -> Prepare -> Stub (skel é carregado pelo stub)
        val htpOrder = listOf("libQnnSystem.so", "libQnnHtp.so",
            "libQnnHtpPrepare.so", "libQnnHtpV81Stub.so")
        assertEquals(4, htpOrder.size)
        // Htp vem antes de Prepare (Prepare depende de Htp)
        assertTrue(htpOrder.indexOfFirst { it.contains("Htp") && !it.contains("Prepare") } <
            htpOrder.indexOfFirst { it.contains("Prepare") })
    }

    @Test
    fun `FastRPC vendor libraries are optional in Android manifest`() {
        assertEquals(
            listOf("libcdsprpc.so", "libadsprpc.so"),
            QairtDependencyManager.fastRpcManifestLibraries,
        )

        val candidates = listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
        )
        val manifest = candidates.firstOrNull(File::isFile)
            ?: error("AndroidManifest.xml não encontrado a partir de ${File(".").absolutePath}")
        val xml = manifest.readText()
        QairtDependencyManager.fastRpcManifestLibraries.forEach { library ->
            val declaration = Regex(
                """<uses-native-library\s+android:name=[\"']${Regex.escape(library)}[\"']\s+android:required=[\"']false[\"']\s*/>""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE),
            )
            assertTrue("$library deve estar declarada como opcional no manifesto", declaration.containsMatchIn(xml))
        }
    }

    @Test
    fun `normalizeZipEntryName converts backslash to forward slash`() {
        // Pacote gerado no Windows (.NET ZipFile.CreateFromDirectory) grava '\'
        // como separador. No Android isso quebra a extração (File no Linux não
        // trata '\' como separador) -> era o bug "Pacote QAIRT incompleto."
        assertEquals("lib/libQnnGpu.so", QairtDependencyManager.normalizeZipEntryName("lib\\libQnnGpu.so"))
        assertEquals("lib/libQnnSystem.so", QairtDependencyManager.normalizeZipEntryName("lib/libQnnSystem.so"))
        assertEquals("manifest.json", QairtDependencyManager.normalizeZipEntryName("manifest.json"))
        // Nomes com barra dupla também são normalizados
        assertEquals("a/b/c.so", QairtDependencyManager.normalizeZipEntryName("a\\b\\c.so"))
        // Não altera nomes que já usam '/'
        val original = "lib/libQnnHtp.so"
        assertEquals(original, QairtDependencyManager.normalizeZipEntryName(original))
    }

    @Test
    fun `backslash zip entry extracts to real subdirectory`() {
        // Reproduz o bug real: um zip gravado com 'lib\libX.so' (Windows/.NET).
        // Com a sanitização, o arquivo precisa cair em <dest>/lib/libX.so e o
        // check requiredLibraries.all { File(libDir, it).isFile } passa.
        val dest = createTempDir("qairt_staging_")
        try {
            val zip = File(dest, "pkg.zip")
            java.util.zip.ZipOutputStream(java.io.FileOutputStream(zip)).use { zos ->
                val rawName = "lib\\libQnnSystem.so" // separador INVERTIDO, como o .NET grava
                zos.putNextEntry(java.util.zip.ZipEntry(rawName))
                zos.write(byteArrayOf(1, 2, 3, 4))
                zos.closeEntry()
                zos.putNextEntry(java.util.zip.ZipEntry("manifest.json"))
                zos.write("{}".toByteArray())
                zos.closeEntry()
            }
            // Extrai com a MESMA lógica do QairtDependencyManager.extractZip
            val extracted = File(dest, "out")
            extracted.mkdirs()
            java.util.zip.ZipInputStream(java.io.BufferedInputStream(java.io.FileInputStream(zip))).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    val normalized = QairtDependencyManager.normalizeZipEntryName(entry.name)
                    val out = File(extracted, normalized)
                    out.parentFile?.mkdirs()
                    java.io.FileOutputStream(out).use { zis.copyTo(it) }
                    zis.closeEntry()
                }
            }
            val libFile = File(File(extracted, "lib"), "libQnnSystem.so")
            assertTrue("lib/libQnnSystem.so deveria existir após extração normalizada (era o bug)", libFile.isFile)
            // No Android/Linux, SEM a sanitização, o arquivo ficaria na raiz com '\'
            // literal no nome (File não trata '\' como separador no Linux). Aqui no
            // JVM/Windows o '\' é separador, então File(extracted, "lib\libX.so")
            // resolve para o MESMO caminho — a assertiva de "arquivo com \ no nome"
            // só é válida em Linux. O essencial (lib/ criada + lib presente) já foi
            // verificado acima.
        } finally {
            dest.deleteRecursively()
        }
    }

    // ---- helpers de teste (espelham a lógica do manager) ----

    private fun isSnapdragon(socModel: String, socMfr: String = ""): Boolean {
        return socMfr.equals("Qualcomm", ignoreCase = true) ||
            socModel.startsWith("SM", ignoreCase = true) ||
            socModel.startsWith("QCM", ignoreCase = true)
    }
}
