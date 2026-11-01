package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Adendo 9 §3 — interleavings do cache de sessões.
 *
 * O cache não tinha teste próprio: os defeitos apontados (chave solta, fatos
 * globais, release antigo) eram verificados só por leitura de código e por
 * ensaios no aparelho. Aqui o backend é roteirizado e o relógio é injetado, então
 * cada caso isola UMA regra de posse.
 *
 * Não há ONNX: `factory` devolve um backend falso e `open` só conta.
 */
class WhisperOrtSessionCacheTest {

    private var aberturas = 0
    private var relogioNs = 0L

    private fun backendFalso() = WhisperOrtFakeBackend(listOf(listOf(65, WhisperOrtPrompt.END_OF_TEXT)))

    private fun cache() = WhisperOrtSessionCache(
        factory = { _, _, _, _ ->
            aberturas++
            relogioNs += 5_000_000 // 5 ms por abertura
            backendFalso()
        },
        open = { },
        clock = { relogioNs },
    )

    private fun chave(versao: String = "v1", threads: Int = 4) = WhisperOrtSessionCache.Key(
        engine = "ORT",
        provider = "CPU",
        runtime = "onnxruntime",
        model = "whisper-large-v3-turbo",
        revision = versao,
        configHash = "enc,dec,past,vocab,cfg",
        threads = threads,
        packageVersion = versao,
    )

    private fun arquivo(nome: String): File = File.createTempFile("ort-cache-", "-$nome").apply { deleteOnExit() }

    private fun acquire(c: WhisperOrtSessionCache, versao: String = "v1", threads: Int = 4) =
        c.acquire(chave(versao, threads), versao, arquivo("enc"), arquivo("dec"), arquivo("past"))

    @Test
    fun `abertura conta como miss e o segundo pedido e hit`() {
        val c = cache()
        val primeira = acquire(c)!!
        assertFalse("primeira aquisição não pode ser hit", primeira.hit)
        assertTrue("miss deve registrar tempo de abertura", primeira.openMillis > 0L)
        assertEquals("abriu uma vez", 1, aberturas)

        val segunda = acquire(c)!!
        assertTrue("segunda aquisição deve reaproveitar a sessão", segunda.hit)
        assertEquals("hit não reabre", 0L, segunda.openMillis)
        assertEquals("não pode abrir de novo", 1, aberturas)
        assertEquals(1, c.stats().hits)
    }

    @Test
    fun `release antigo nao libera a tentativa nova`() {
        val c = cache()
        val antiga = acquire(c)!!
        assertTrue("release da própria tentativa libera", c.release(antiga))

        val nova = acquire(c)!!
        assertFalse("release repetido/antigo não pode liberar outra posse", c.release(antiga))
        assertEquals("a nova tentativa continua em uso", 1, c.stats().inUse)

        assertTrue(c.release(nova))
        assertEquals(0, c.stats().inUse)
    }

    @Test
    fun `troca de pacote na mesma chave e recusada`() {
        val c = cache()
        assertNotNull(acquire(c, versao = "v1"))
        // Mesma Key, arquivos de outra versão: não pode reaproveitar a sessão.
        assertNull(
            "versão divergente do pacote tem de recusar",
            c.acquire(chave("v1"), "v2", arquivo("enc"), arquivo("dec"), arquivo("past")),
        )
    }

    @Test
    fun `invalidate recusa durante compute e aceita depois do dreno`() {
        val c = cache()
        val lease = acquire(c)!!

        assertTrue(c.tryBeginCompute(lease))
        assertFalse("não pode fechar sessão em execução", c.invalidate(lease.key))
        // Release com compute ativo também não solta a posse física.
        assertFalse("release durante compute deve ser recusado", c.release(lease))

        c.endCompute(lease)
        assertTrue("release após o compute deve liberar", c.release(lease))
        assertTrue("sem uso, invalidate pode fechar", c.invalidate(lease.key))
        assertEquals(0, c.stats().live)
    }

    @Test
    fun `threads diferentes nao compartilham sessao`() {
        val c = cache()
        assertNotNull(acquire(c, threads = 4))
        assertNotNull(acquire(c, threads = 8))
        assertEquals("configurações distintas abrem sessões distintas", 2, aberturas)
        assertEquals(2, c.stats().live)
    }

    @Test
    fun `alternancia de threads abre conta e fecha ociosas sem tocar em uso`() {
        // Política hot (maxEntries=1, a de produção): cada troca fecha a ociosa.
        var aberturas = 0
        val criados = mutableListOf<WhisperOrtFakeBackend>()
        val quente = WhisperOrtSessionCache(
            factory = { _, _, _, _ -> aberturas++; WhisperOrtFakeBackend(emptyList()).also { criados.add(it) } },
            open = { },
            maxEntries = 1,
        )
        fun usar(t: Int) {
            val lease = quente.acquire(chave(threads = t), "v1", arquivo("e"), arquivo("d"), arquivo("p"))!!
            assertTrue(quente.release(lease))
        }
        usar(1); usar(2); usar(3)
        assertEquals("três aberturas", 3, aberturas)
        assertEquals("só a quente fica viva", 1, quente.stats().live)
        assertEquals("duas ociosas fecharam", 2, criados.count { it.closed })
        // Reuso: a quente dá hit sem reabrir.
        val deNovo = quente.acquire(chave(threads = 3), "v1", arquivo("e"), arquivo("d"), arquivo("p"))!!
        assertTrue(deNovo.hit)
        assertEquals(3, aberturas)
        assertTrue(quente.release(deNovo))
        // Em uso não é expulso: segura o lease e força a troca.
        val emUso = quente.acquire(chave(threads = 3), "v1", arquivo("e"), arquivo("d"), arquivo("p"))!!
        assertTrue(quente.tryBeginCompute(emUso))
        val outro = quente.acquire(chave(threads = 4), "v1", arquivo("e"), arquivo("d"), arquivo("p"))
        assertNull("com a única sessão em compute, a troca é recusada", outro)
        assertFalse("backend em uso não fechou", criados.last().closed)
        quente.endCompute(emUso)
        assertTrue(quente.release(emUso))
        quente.closeAll()
    }
}
