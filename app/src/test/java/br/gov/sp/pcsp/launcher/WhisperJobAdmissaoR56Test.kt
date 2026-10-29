package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R56/B02 — regressão focal da admissão recusada.
 *
 * Defeito demonstrado nos brutos congelados da auditoria
 * (`checkpoint-rodada56-revisao2-2026-10-28/adb/r56b02-*.txt`): `job_admissao`
 * saía com `terminal=ADMISSAO_RECUSADA` e o fluxo CONTINUAVA
 * (`live_job_start`, `live_terminal`, `fulltext_export` com `chars=0`), porque
 * os três chamadores de `startJob` ignoravam o retorno booleano. O terminal era
 * recusado depois (`job_terminal_recusado`, `owner` = geração) e a reserva
 * entrava em `job_quarantine`.
 *
 * Este teste fixa a regra: recusa de admissão NÃO deixa prosseguir; queda do
 * FGS deixa (o job segue no processo, comportamento anterior).
 */
class WhisperJobAdmissaoR56Test {

    @Test
    fun `admissao recusada nao permite prosseguir`() {
        assertFalse(
            WhisperJobService.permiteProsseguir(
                WhisperJobService.StartOutcome.ADMISSAO_RECUSADA
            )
        )
    }

    @Test
    fun `fgs ausente permite prosseguir no processo`() {
        assertTrue(
            WhisperJobService.permiteProsseguir(
                WhisperJobService.StartOutcome.SEM_FGS
            )
        )
    }

    @Test
    fun `admissao aceita permite prosseguir`() {
        assertTrue(
            WhisperJobService.permiteProsseguir(
                WhisperJobService.StartOutcome.STARTED
            )
        )
    }

    @Test
    fun `estados sao exatamente tres e distintos`() {
        val estados = WhisperJobService.StartOutcome.values().map { it.name }.toSet()
        assertTrue(estados == setOf("STARTED", "ADMISSAO_RECUSADA", "SEM_FGS"))
    }
}
