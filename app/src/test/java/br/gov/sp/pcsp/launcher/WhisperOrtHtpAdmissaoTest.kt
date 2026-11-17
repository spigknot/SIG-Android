package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R100 §5.1 — admissão da rota HTP por SoC, sem aparelho.
 *
 * Cobre o que a ordem exige: SM8550 admitido, SM8850 presente e DESLIGADO,
 * qualquer outro em CPU, fabricante diferente, pacote ausente/não verificado,
 * desligamento no processo e as três falhas consecutivas entre processos.
 */
class WhisperOrtHtpAdmissaoTest {

    private fun decidir(
        soc: String? = "SM8550",
        fabricante: String? = "QTI",
        qairtPresente: Boolean = true,
        pacotePresente: Boolean = true,
        pacoteVerificado: Boolean = true,
        desligadoNoProcesso: Boolean = false,
        memoria: WhisperOrtHtpAdmissao.MemoriaFalhas = WhisperOrtHtpAdmissao.MemoriaFalhas(),
        versao: String = WhisperOrtNpuPacote.VERSAO,
    ) = WhisperOrtHtpAdmissao.decidir(
        socModelo = soc,
        fabricante = fabricante,
        qairtPresente = qairtPresente,
        pacotePresente = pacotePresente,
        pacoteVerificado = pacoteVerificado,
        desligadoNoProcesso = desligadoNoProcesso,
        memoriaFalhas = memoria,
        versaoPacote = versao,
    )

    @Test
    fun `SM8550 com pacote verificado usa HTP`() {
        val d = decidir()
        assertTrue(d.usarHtp)
        assertEquals(WhisperOrtHtpAdmissao.Motivo.ADMITIDO, d.motivo)
        assertEquals("73", WhisperOrtHtpAdmissao.socDe("SM8550")?.htpArch)
    }

    @Test
    fun `SM8550 com sufixo de variante continua admitido`() {
        assertEquals("SM8550", WhisperOrtHtpAdmissao.normalizarSoc("sm8550-ab"))
        assertTrue(decidir(soc = "SM8550-AB").usarHtp)
    }

    @Test
    fun `SM8850 esta na tabela e desligado ate a M4`() {
        val d = decidir(soc = "SM8850")
        assertFalse(d.usarHtp)
        assertEquals(WhisperOrtHtpAdmissao.Motivo.SOC_DESLIGADO, d.motivo)
        // A entrada existe (não é "desconhecido") e já tem arch, mas não liga.
        assertEquals("81", WhisperOrtHtpAdmissao.socDe("SM8850")?.htpArch)
        assertFalse(WhisperOrtHtpAdmissao.socDe("SM8850")!!.habilitado)
    }

    @Test
    fun `SoC fora da tabela usa CPU`() {
        val d = decidir(soc = "SM7450")
        assertFalse(d.usarHtp)
        assertEquals(WhisperOrtHtpAdmissao.Motivo.SOC_DESCONHECIDO, d.motivo)
    }

    @Test
    fun `fabricante diferente de QTI usa CPU`() {
        val d = decidir(fabricante = "MediaTek")
        assertFalse(d.usarHtp)
        assertEquals(WhisperOrtHtpAdmissao.Motivo.FABRICANTE_DIFERENTE, d.motivo)
    }

    @Test
    fun `QAIRT ausente usa CPU mesmo com o pacote pronto`() {
        val d = decidir(qairtPresente = false)
        assertFalse(d.usarHtp)
        assertEquals(WhisperOrtHtpAdmissao.Motivo.QAIRT_AUSENTE, d.motivo)
    }

    @Test
    fun `QAIRT vem antes do pacote no diagnostico`() {
        // Os dois ausentes: o motivo reportado é o PRIMEIRO que impede.
        val d = decidir(qairtPresente = false, pacotePresente = false)
        assertEquals(WhisperOrtHtpAdmissao.Motivo.QAIRT_AUSENTE, d.motivo)
    }

    @Test
    fun `pacote ausente usa CPU`() {
        val d = decidir(pacotePresente = false)
        assertFalse(d.usarHtp)
        assertEquals(WhisperOrtHtpAdmissao.Motivo.PACOTE_AUSENTE, d.motivo)
    }

    @Test
    fun `pacote presente sem SHA conferido usa CPU`() {
        val d = decidir(pacoteVerificado = false)
        assertFalse(d.usarHtp)
        assertEquals(WhisperOrtHtpAdmissao.Motivo.PACOTE_NAO_VERIFICADO, d.motivo)
    }

    @Test
    fun `desligamento no processo vale ate o fim do processo`() {
        val d = decidir(desligadoNoProcesso = true)
        assertFalse(d.usarHtp)
        assertEquals(WhisperOrtHtpAdmissao.Motivo.DESLIGADO_NO_PROCESSO, d.motivo)
    }

    @Test
    fun `duas falhas nao desligam e tres desligam ate a proxima versao`() {
        val duas = WhisperOrtHtpAdmissao.MemoriaFalhas(falhas = 2, versao = WhisperOrtNpuPacote.VERSAO)
        assertTrue(decidir(memoria = duas).usarHtp)

        val tres = WhisperOrtHtpAdmissao.MemoriaFalhas(falhas = 3, versao = WhisperOrtNpuPacote.VERSAO)
        val d = decidir(memoria = tres)
        assertFalse(d.usarHtp)
        assertEquals(WhisperOrtHtpAdmissao.Motivo.FALHAS_CONSECUTIVAS, d.motivo)

        // Versão nova do pacote libera de novo, mesmo com o contador antigo.
        val antiga = WhisperOrtHtpAdmissao.MemoriaFalhas(falhas = 3, versao = "1")
        assertTrue(decidir(memoria = antiga, versao = WhisperOrtNpuPacote.VERSAO).usarHtp)
    }

    @Test
    fun `memoria de versao diferente nao bloqueia`() {
        val memoria = WhisperOrtHtpAdmissao.MemoriaFalhas(falhas = 9, versao = "0")
        assertFalse(WhisperOrtHtpAdmissao.bloqueadoPorFalhas(memoria, "1"))
        assertTrue(WhisperOrtHtpAdmissao.bloqueadoPorFalhas(memoria.copy(versao = "1"), "1"))
    }
}
