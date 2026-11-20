package br.gov.sp.pcsp.launcher

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * R117 §T01 — regressão da C6 da R116, em JVM, sem Android.
 *
 * A C6 reprovou porque `pacoteVerificado` gravava o diretório do pacote pela
 * versão IMPLÍCITA do código (`dirDaVersao(app)`, sempre o v3). Com o v2 ativo e
 * o v3 ausente, a sessão procurava o wrapper no diretório errado, recusava com
 * "wrapper ausente" e a primeira transcrição caía para a CPU.
 *
 * Aqui a anotação é exercitada pelo MESMO seam que o produto usa
 * (`pacoteVerificadoEm`, com a raiz do pacote), sobre diretórios temporários:
 * - v2 verificado com o v3 ausente → diretório do v2, com os 2 arquivos do v2;
 * - v3 verificado → diretório do v3;
 * - troca no meio do processo → o diretório só passa a ser o do v3 DEPOIS da
 *   verificação; o v3 em disco, ainda não verificado, não muda a rota.
 */
class WhisperOrtHtpRotaC6R117Test {

    @get:Rule
    val temp = TemporaryFolder()

    private fun manifesto(recurso: String): WhisperOrtNpuPacote.Manifesto {
        val texto = javaClass.getResourceAsStream(recurso)!!.use {
            it.readBytes().toString(Charsets.UTF_8)
        }
        return WhisperOrtNpuPacote.lerManifesto(texto)
            ?: error("manifesto do fixture ilegível: $recurso")
    }

    private fun manifestoV2(): WhisperOrtNpuPacote.Manifesto = manifesto("/npu/manifesto-publicado-v2.json")

    private fun manifestoV3(): WhisperOrtNpuPacote.Manifesto = manifesto("/npu/manifesto-publicado-v3.json")

    private fun raiz(): File = File(temp.newFolder(), "whisper-npu").also { it.mkdirs() }

    /** Monta o diretório da versão com os arquivos exigidos (conteúdo vazio: aqui só a rota importa). */
    private fun montar(raiz: File, m: WhisperOrtNpuPacote.Manifesto): File {
        val dir = File(raiz, m.versao)
        dir.mkdirs()
        for (item in m.itens) File(dir, item.nome).writeBytes(ByteArray(0))
        File(dir, WhisperOrtNpuPacote.NOME_MANIFESTO).writeText("", Charsets.UTF_8)
        return dir
    }

    @After
    fun limparEstadoDoSingleton() {
        WhisperOrtHtpRota.reiniciarEstadoParaTeste()
    }

    @Test
    fun `c6 v2 verificado com v3 ausente abre o diretorio do v2`() {
        WhisperOrtHtpRota.reiniciarEstadoParaTeste()
        val raiz = raiz()
        val m2 = manifestoV2()
        val dir2 = montar(raiz, m2)
        assertFalse(
            "a C6 exige o v3 ausente",
            File(raiz, WhisperOrtNpuPacote.VERSAO).exists(),
        )

        WhisperOrtHtpRota.pacoteVerificadoEm(raiz, m2)

        val dir = WhisperOrtHtpRota.dirPacoteAtual()
        assertEquals("o diretório do pacote tem de ser o do v2", dir2, dir)
        assertEquals(WhisperOrtNpuPacote.VERSAO_ANTERIOR, dir?.name)
        assertTrue(WhisperOrtHtpRota.pacoteVerificadoNesteProcesso())
        assertEquals("a raiz tem de resolver o v2 como versão ativa", dir2, WhisperOrtNpuPacote.dirAtivo(raiz))
        for (nome in WhisperOrtNpuPacote.ARQUIVOS_ANTERIOR) {
            assertTrue("o diretório do v2 não tem $nome", File(dir, nome).isFile)
        }
        assertTrue(
            "o wrapper do encoder tem de estar no diretório aberto",
            File(dir, WhisperOrtNpuPacote.WRAPPER).isFile,
        )
        assertEquals(2, WhisperOrtNpuPacote.ARQUIVOS_ANTERIOR.size)
    }

    @Test
    fun `v3 verificado abre o diretorio do v3`() {
        WhisperOrtHtpRota.reiniciarEstadoParaTeste()
        val raiz = raiz()
        val m3 = manifestoV3()
        val dir3 = montar(raiz, m3)

        WhisperOrtHtpRota.pacoteVerificadoEm(raiz, m3)

        assertEquals(dir3, WhisperOrtHtpRota.dirPacoteAtual())
        assertEquals(WhisperOrtNpuPacote.VERSAO, WhisperOrtHtpRota.dirPacoteAtual()?.name)
        assertTrue(m3.pontaAPonta)
        for (nome in WhisperOrtNpuPacote.ARQUIVOS) {
            assertTrue("o diretório do v3 não tem $nome", File(dir3, nome).isFile)
        }
    }

    @Test
    fun `troca v2 para v3 no meio do processo so vale depois da verificacao`() {
        WhisperOrtHtpRota.reiniciarEstadoParaTeste()
        val raiz = raiz()
        val m2 = manifestoV2()
        val dir2 = montar(raiz, m2)
        val m3 = manifestoV3()
        val dir3 = montar(raiz, m3)

        WhisperOrtHtpRota.pacoteVerificadoEm(raiz, m2)
        assertEquals("antes da verificação do v3 a rota é a do v2", dir2, WhisperOrtHtpRota.dirPacoteAtual())
        assertEquals(dir3, WhisperOrtNpuPacote.dirAtivo(raiz))
        assertEquals("v3 em disco não verificado não pode mudar a rota", dir2, WhisperOrtHtpRota.dirPacoteAtual())

        WhisperOrtHtpRota.pacoteVerificadoEm(raiz, m3)

        assertEquals("depois da verificação do v3 a rota é a do v3", dir3, WhisperOrtHtpRota.dirPacoteAtual())
        assertEquals(WhisperOrtNpuPacote.VERSAO, WhisperOrtHtpRota.dirPacoteAtual()?.name)
    }
}
