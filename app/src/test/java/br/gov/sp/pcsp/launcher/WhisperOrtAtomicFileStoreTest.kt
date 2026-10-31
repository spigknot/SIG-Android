package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Commit atômico: falha/interrupção deixam A ou B completo, nunca nada. */
class WhisperOrtAtomicFileStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val store = WhisperOrtAtomicFileStore()

    @Test
    fun `grava e le conteudo`() {
        val alvo = File(temp.root, "active")
        assertTrue(store.write(alvo, "v1\n".toByteArray()))
        assertEquals("v1", store.read(alvo)?.toString(Charsets.UTF_8)?.trim())
        // Sem sobras.
        assertFalse(File(temp.root, "active.tmp").exists())
        assertFalse(File(temp.root, "active.bak").exists())
    }

    @Test
    fun `segunda gravacao substitui e nao deixa resto`() {
        val alvo = File(temp.root, "active")
        assertTrue(store.write(alvo, "v1".toByteArray()))
        assertTrue(store.write(alvo, "v2".toByteArray()))
        assertEquals("v2", store.read(alvo)?.toString(Charsets.UTF_8))
        assertFalse(File(temp.root, "active.bak").exists())
    }

    @Test
    fun `temporario completo recupera alvo ausente`() {
        val alvo = File(temp.root, "active")
        // Simula interrupção: tmp escrito, alvo inexistente.
        File(temp.root, "active.tmp").writeText("v9")
        assertTrue(store.recover(alvo))
        assertTrue(alvo.isFile)
        assertEquals("v9", alvo.readText())
    }

    @Test
    fun `temporario abandonado com alvo valido e descartado`() {
        val alvo = File(temp.root, "active")
        assertTrue(store.write(alvo, "v1".toByteArray()))
        File(temp.root, "active.tmp").writeText("lixo")
        assertTrue(store.recover(alvo))
        assertEquals("v1", alvo.readText())
        assertFalse(File(temp.root, "active.tmp").exists())
    }

    @Test
    fun `anterior preservado assume quando o alvo desaparece`() {
        val alvo = File(temp.root, "active")
        File(temp.root, "active.bak").writeText("anterior")
        assertTrue(store.recover(alvo))
        assertEquals("anterior", alvo.readText())
    }

    @Test
    fun `falha de gravacao preserva o conteudo anterior`() {
        val alvo = File(temp.root, "active")
        assertTrue(store.write(alvo, "v1".toByteArray()))
        // Bloqueia o temporário com um DIRETÓRIO não vazio: a escrita falha.
        val tmp = File(temp.root, "active.tmp")
        tmp.mkdirs()
        File(tmp, "x").writeText("x")
        assertFalse(store.write(alvo, "v2".toByteArray()))
        assertEquals("v1", alvo.readText())
    }

    @Test
    fun `sem nada recuperavel devolve nulo`() {
        assertNull(store.read(File(temp.root, "nao-existe")))
        assertFalse(store.recover(File(temp.root, "nao-existe")))
    }

    @Test
    fun `alvo invalido com bak valido promove o bak`() {
        val alvo = File(temp.root, "active")
        val bak = File(temp.root, "active.bak")
        alvo.writeText("corrompido", Charsets.UTF_8)
        bak.writeText("estado-confirmado", Charsets.UTF_8)
        val ok = store.recover(alvo) { it.isNotEmpty() && String(it, Charsets.UTF_8).startsWith("estado") }
        assertTrue("recover deveria confirmar o estado do bak", ok)
        assertEquals("estado-confirmado", alvo.readText(Charsets.UTF_8))
        assertFalse("bak nao pode sobreviver depois de promovido", bak.exists())
    }

    @Test
    fun `tmp truncado nao vence bak valido`() {
        val alvo = File(temp.root, "active")
        val bak = File(temp.root, "active.bak")
        val tmp = File(temp.root, "active.tmp")
        bak.writeText("estado-confirmado", Charsets.UTF_8)
        tmp.writeText("e", Charsets.UTF_8)
        val ok = store.recover(alvo) { String(it, Charsets.UTF_8).startsWith("estado") }
        assertTrue(ok)
        assertEquals("estado-confirmado", alvo.readText(Charsets.UTF_8))
        assertFalse(tmp.exists())
    }

    @Test
    fun `tudo invalido devolve falso e preserva os arquivos`() {
        val alvo = File(temp.root, "active")
        alvo.writeText("x", Charsets.UTF_8)
        val ok = store.recover(alvo) { String(it, Charsets.UTF_8).startsWith("estado") }
        assertFalse("nao existe estado confirmado", ok)
        assertTrue("o alvo nao pode ser apagado sem substituto", alvo.exists())
    }
}
