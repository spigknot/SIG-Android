package br.gov.sp.pcsp.launcher

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DownloadFileStorageTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun `arquivo vazio ou truncado nao e considerado instalado`() {
        val file = folder.newFile("model.bin")
        assertFalse(DownloadFileStorage.isComplete(file))
        file.writeText("abc")
        assertFalse(DownloadFileStorage.isComplete(file, 4))
        assertTrue(DownloadFileStorage.isComplete(file, 3))
        assertTrue(DownloadFileStorage.isComplete(file))
    }

    @Test fun `download incompleto preserva modelo anterior`() {
        val destination = folder.newFile("model.bin").apply { writeText("anterior") }
        val partial = folder.newFile("model.bin.download").apply { writeText("novo") }
        assertThrows(IllegalStateException::class.java) {
            DownloadFileStorage.activate(partial, destination, 10)
        }
        assertEquals("anterior", destination.readText())
        assertEquals("novo", partial.readText())
    }

    @Test fun `arquivo validado substitui somente seu proprio destino`() {
        val other = folder.newFile("outra-qualidade.bin").apply { writeText("mantido") }
        val destination = folder.newFile("model.bin").apply { writeText("anterior") }
        val partial = folder.newFile("model.bin.download").apply { writeText("novo") }
        DownloadFileStorage.activate(partial, destination, 4)
        assertEquals("novo", destination.readText())
        assertEquals("mantido", other.readText())
        assertFalse(partial.exists())
        assertEquals(setOf("model.bin", "outra-qualidade.bin"), folder.root.listFiles()!!.map { it.name }.toSet())
    }

    @Test fun `falha ao publicar nao apaga arquivo anterior`() {
        val previous = folder.newFile("model.bin").apply { writeText("anterior") }
        val missing = File(folder.root, "inexistente.download")
        assertThrows(IllegalStateException::class.java) {
            DownloadFileStorage.activate(missing, previous)
        }
        assertEquals("anterior", previous.readText())
    }
}
