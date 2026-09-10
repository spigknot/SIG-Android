package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Texto da ajuda das keywords: precisa DIZER ao usuário quanto cada modelo
 *  aproveita da lista (e que a ordem importa). */
class SttKeywordsHelpTest {

    private val cemTermos = (1..100).map { "t".repeat(48) + "%02d".format(it % 100) }

    @Test
    fun text_sempreExplicaPropositoOrdemEAvisoDeInsercao() {
        val texto = SttKeywordsHelp.text(provider = "deepgram", isLive = false, keywords = listOf("placa"))

        assertTrue(texto.contains(SttKeywordsHelp.PURPOSE))
        assertTrue(texto.contains("A ORDEM IMPORTA"))
        assertTrue(texto.contains(SttKeywordsHelp.INSERTION_WARNING))
        assertTrue(texto.contains("NÃO foi dito no áudio"))
    }

    @Test
    fun text_semProvedorListaTodosOsModelos() {
        val texto = SttKeywordsHelp.text(provider = null, isLive = false)

        listOf("Deepgram", "Grok (xAI)", "ElevenLabs", "AssemblyAI", "Muse Voice", "Alibaba").forEach {
            assertTrue("faltou $it", texto.contains(it))
        }
        // Os dois modos do ElevenLabs e do AssemblyAI aparecem (REST x ao vivo).
        assertTrue(texto.contains("ElevenLabs (ao vivo)"))
        assertTrue(texto.contains("AssemblyAI (arquivo)"))
    }

    @Test
    fun text_comProvedorMostraSoOsLimitesDaqueleModo() {
        val vivo = SttKeywordsHelp.text(provider = "elevenlabs", isLive = true, keywords = emptyList())
        val arquivo = SttKeywordsHelp.text(provider = "elevenlabs", isLive = false, keywords = emptyList())

        assertTrue(vivo.contains("primeiros 20 caracteres"))
        assertTrue(vivo.contains("até 50 termos"))
        assertTrue(arquivo.contains("até 1000 termos"))
        assertTrue(!arquivo.contains("primeiros 20 caracteres"))
    }

    @Test
    fun text_contaQuantosTermosOModeloRecebe() {
        val texto = SttKeywordsHelp.text(
            provider = "deepgram",
            isLive = false,
            keywords = cemTermos,
        )

        // 100 termos de 50 caracteres = 13 tokens cada: o Deepgram recebe 38.
        assertTrue(texto.contains("recebe 38 dos 100 termos cadastrados"))
        assertTrue(texto.contains("os outros ficam de fora"))
    }

    @Test
    fun text_quandoTudoEhAproveitadoDizIsso() {
        val texto = SttKeywordsHelp.text(
            provider = "metamuse",
            isLive = false,
            keywords = listOf("placa", "abordagem"),
        )

        assertTrue(texto.contains("recebe todos os 2 termos cadastrados"))
    }

    @Test
    fun providerLimit_avisaQueOAlibabaNaoAplicaNoModoArquivo() {
        val arquivo = SttKeywordsHelp.providerLimit("alibaba", isLive = false).orEmpty()
        val vivo = SttKeywordsHelp.providerLimit("alibaba", isLive = true).orEmpty()

        assertTrue(arquivo.contains("aplicadas com peso normal"))
        assertTrue(!arquivo.contains("NÃO aplica"))
        // Ao vivo o reforço é máximo (peso 50) nos primeiros termos — foi o que
        // corrigiu "Taguaí" no teste real.
        assertTrue(vivo.contains("reforço máximo"))
        assertTrue(vivo.contains("50 termos"))
    }

    @Test
    fun providerLimit_eNuloParaOsNaoSuportados() {
        assertNull(SttKeywordsHelp.providerLimit("servidor", isLive = false))
        assertNull(SttKeywordsHelp.providerLimit("", isLive = true))
    }

    @Test
    fun labels_usamONomeQueOUsuarioVe() {
        assertEquals("Deepgram Nova 3", SttKeywordsHelp.providerLabel("deepgram"))
        assertEquals("Muse Voice (Meta)", SttKeywordsHelp.providerLabel("metamuse"))
    }

    @Test
    fun text_semKeywordsNaoMencionaContagem() {
        val texto = SttKeywordsHelp.text(provider = "deepgram", isLive = false, keywords = emptyList())

        assertTrue(!texto.contains("termos cadastrados"))
        assertTrue(texto.contains("Deepgram"))
    }
}
