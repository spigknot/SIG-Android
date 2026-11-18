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

    // ------------------------------------------------------------------ R106

    /**
     * R106/T02 — a entrada fixa distingue pacote ausente, recusado pela
     * conferência e corrompido: só o corrompido pede REINSTALAÇÃO; os dois
     * primeiros aceitam "Verificar novamente" (SHA, sem download).
     */
    @Test
    fun `R106 entrada fixa distingue recusado de corrompido`() {
        val ausente = WhisperNpuOferta.Situacao.AUSENTE
        val recusado = WhisperNpuOferta.Situacao.RECUSADO
        val corrompido = WhisperNpuOferta.Situacao.CORROMPIDO
        val pronto = WhisperNpuOferta.Situacao.PRONTO

        assertEquals("Ativar aceleração NPU", WhisperNpuOferta.rotuloEntradaFixa(ausente))
        assertEquals("Ativar aceleração NPU", WhisperNpuOferta.rotuloEntradaFixa(recusado))
        assertEquals("Reinstalar aceleração NPU", WhisperNpuOferta.rotuloEntradaFixa(corrompido))
        assertEquals("NPU ativa", WhisperNpuOferta.rotuloEntradaFixa(pronto))

        assertFalse(WhisperNpuOferta.ofereceVerificarNovamente(ausente))
        assertFalse(WhisperNpuOferta.ofereceVerificarNovamente(pronto))
        assertTrue(WhisperNpuOferta.ofereceVerificarNovamente(recusado))
        assertTrue(WhisperNpuOferta.ofereceVerificarNovamente(corrompido))

        // Com o QAIRT faltando não se anuncia "NPU ativa" (ainda há o que baixar).
        assertEquals(
            "Ativar aceleração NPU",
            WhisperNpuOferta.rotuloEntradaFixa(pronto, qairtInstalado = false),
        )
    }

    @Test
    fun `R106 oferta automatica so quando o pacote ainda nao esta em disco`() {
        val memoria = Memoria()
        assertTrue(
            WhisperNpuOferta.deveOferecerAutomaticamente(memoria, WhisperNpuOferta.Situacao.AUSENTE),
        )
        assertFalse(
            WhisperNpuOferta.deveOferecerAutomaticamente(memoria, WhisperNpuOferta.Situacao.RECUSADO),
        )
        assertFalse(
            WhisperNpuOferta.deveOferecerAutomaticamente(memoria, WhisperNpuOferta.Situacao.CORROMPIDO),
        )
        memoria.registrarRecusa()
        assertFalse(
            WhisperNpuOferta.deveOferecerAutomaticamente(memoria, WhisperNpuOferta.Situacao.AUSENTE),
        )
    }
}
