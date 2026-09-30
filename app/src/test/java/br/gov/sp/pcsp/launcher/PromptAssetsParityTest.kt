package br.gov.sp.pcsp.launcher

import java.io.File
import org.junit.AssumptionViolatedException
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Vacina de paridade: os quatro prompts embutidos no APK são, byte a byte, os
 * de `D:\Projetos\SIG Windows\prompts` — a pasta é a fonte da verdade do
 * conteúdo padrão (o bucket R2 é o canal de distribuição dela).
 *
 * O teste pula (assumption) quando a pasta do SIG Windows não existe na
 * máquina (ex.: CI); quando existe, qualquer divergência reprova.
 */
class PromptAssetsParityTest {

    private val promptFiles = listOf(
        "historico_system.txt",
        "historico_user.txt",
        "oitiva_system.txt",
        "oitiva_user.txt",
    )

    private fun firstDir(vararg paths: String): File? = paths.map { File(it) }.firstOrNull { it.isDirectory }

    private fun windowsPromptsDir(): File? = firstDir(
        "D:/Projetos/SIG Windows/prompts",
        "../SIG Windows/prompts",
        "../../SIG Windows/prompts",
    )

    private fun assetsDir(): File? = firstDir(
        "src/main/assets/prompts",
        "app/src/main/assets/prompts",
        "../app/src/main/assets/prompts",
    )

    @Test
    fun assetsDoApkBatemComAPastaDoSigWindows() {
        val windows = windowsPromptsDir()
            ?: throw AssumptionViolatedException("pasta do SIG Windows ausente nesta máquina — paridade não verificável")
        val assets = assetsDir()
            ?: throw AssumptionViolatedException("assets/prompts do APK não encontrados — paridade não verificável")

        for (name in promptFiles) {
            val expected = File(windows, name)
            val actual = File(assets, name)
            assertTrue("$name não existe em ${windows.absolutePath}", expected.isFile)
            assertTrue("$name não existe em ${assets.absolutePath}", actual.isFile)
            assertTrue(
                "$name diverge: o APK não está com o conteúdo de ${windows.absolutePath} " +
                    "(rode o sync dos prompts/assets)",
                expected.readBytes().contentEquals(actual.readBytes()),
            )
        }
    }
}
