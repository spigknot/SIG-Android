package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R102 §T01 — vacina da oferta única e da entrada fixa.
 *
 * A recusa é guardada na porta [WhisperNpuOferta.Armazenamento]; cada "abertura
 * da tela" cria uma nova instância da política sobre o MESMO armazenamento, que é
 * o que acontece com as SharedPreferences quando a Activity é recriada.
 */
class WhisperNpuOfertaTest {

    /** Memória compartilhada por várias "aberturas da tela". */
    private class Memoria : WhisperNpuOferta.Armazenamento {
        var recusado = false
        override fun recusado(): Boolean = recusado
        override fun registrarRecusa() { recusado = true }
    }

    private fun novaTela(memoria: Memoria) = object {
        val deveOferecer = WhisperNpuOferta.deveOferecerAutomaticamente(memoria, false)
        val entradaVisivel = WhisperNpuOferta.entradaFixaVisivel(memoria, false)
    }

    @Test
    fun `pergunta uma unica vez e a recusa sobrevive a nova abertura da tela`() {
        val memoria = Memoria()
        assertTrue("na primeira abertura deve oferecer", novaTela(memoria).deveOferecer)

        // O usuário tocou "Agora não": a Activity grava na porta de persistência.
        memoria.registrarRecusa()

        val reaberta = novaTela(memoria)
        assertFalse("depois de recusar NAO pode perguntar de novo", reaberta.deveOferecer)
        // Reabrir quantas vezes forem não reativa a oferta automática.
        repeat(5) { assertFalse(novaTela(memoria).deveOferecer) }
    }

    @Test
    fun `a entrada fixa abre o dialogo mesmo depois da recusa`() {
        val memoria = Memoria()
        memoria.registrarRecusa()
        assertTrue("a entrada tem de continuar visível", novaTela(memoria).entradaVisivel)
        assertEquals(
            WhisperNpuOferta.Acao.ABRIR_ENTRADA_FIXA,
            WhisperNpuOferta.acaoEntradaFixa(pacotesProntos = false),
        )
    }

    @Test
    fun `a entrada some quando os dois pacotes estao prontos`() {
        val memoria = Memoria()
        assertFalse(
            "com os dois pacotes prontos não há oferta nem entrada",
            WhisperNpuOferta.deveOferecerAutomaticamente(memoria, true),
        )
        assertFalse(WhisperNpuOferta.entradaFixaVisivel(memoria, true))
        assertEquals(
            WhisperNpuOferta.Acao.NAO_OFERECER,
            WhisperNpuOferta.acaoEntradaFixa(pacotesProntos = true),
        )
    }

    @Test
    fun `sem recusa e com pacotes prontos nada e oferecido`() {
        assertEquals(
            WhisperNpuOferta.Acao.OFERECER,
            WhisperNpuOferta.acaoAutomatica(recusado = false, pacotesProntos = false),
        )
        assertEquals(
            WhisperNpuOferta.Acao.NAO_OFERECER,
            WhisperNpuOferta.acaoAutomatica(recusado = false, pacotesProntos = true),
        )
    }
}
