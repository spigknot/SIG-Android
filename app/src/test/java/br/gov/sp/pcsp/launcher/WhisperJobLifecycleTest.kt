package br.gov.sp.pcsp.launcher

import android.content.pm.ServiceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R11/D4 — contrato de vida do job (lógica pura). Estes testes travam as
 * decisões que o device não consegue provar sozinho: tipo de FGS por rota,
 * posse por job (anti-callback-stale) e teto do wake lock (nunca permanente).
 */
class WhisperJobLifecycleTest {

    @Test
    fun `rota de arquivo usa dataSync (processamento local de arquivo)`() {
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            WhisperJobLifecycle.fgsType(WhisperJobLifecycle.KIND_FILE)
        )
    }

    @Test
    fun `rota ao vivo usa microphone`() {
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            WhisperJobLifecycle.fgsType(WhisperJobLifecycle.KIND_LIVE)
        )
    }

    @Test
    fun `rota desconhecida cai no tipo de arquivo (nunca microphone por engano)`() {
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            WhisperJobLifecycle.fgsType("outra-coisa")
        )
    }

    @Test
    fun `dono por job - so a geracao dona libera cancelar ou terminal`() {
        assertTrue(WhisperJobLifecycle.isOwner(7L, 7L))
        assertFalse(WhisperJobLifecycle.isOwner(7L, 8L))
        assertFalse(WhisperJobLifecycle.isOwner(7L, 0L))
        assertFalse("sem dono nunca casa", WhisperJobLifecycle.isOwner(0L, 0L))
        assertFalse("geracao invalida nunca casa", WhisperJobLifecycle.isOwner(-1L, -1L))
    }

    @Test
    fun `teto do wake lock - pedido invalido vira o teto e nunca passa do maximo`() {
        val min = 60_000L
        val max = 3_600_000L
        assertEquals(max, WhisperJobLifecycle.clampLockTimeout(0L, min, max))
        assertEquals(max, WhisperJobLifecycle.clampLockTimeout(-5L, min, max))
        assertEquals(min, WhisperJobLifecycle.clampLockTimeout(1L, min, max))
        assertEquals(max, WhisperJobLifecycle.clampLockTimeout(max + 1L, min, max))
        assertEquals(120_000L, WhisperJobLifecycle.clampLockTimeout(120_000L, min, max))
    }

    @Test
    fun `texto da notificacao nunca fica vazio`() {
        assertEquals("Transcrevendo…", WhisperJobLifecycle.notificationText("   "))
        assertEquals("Transcrevendo…", WhisperJobLifecycle.notificationText(""))
        assertEquals("Carregando modelo...", WhisperJobLifecycle.notificationText("  Carregando modelo...  "))
    }

    @Test
    fun `validacao de timeout por tipo de rota`() {
        assertTrue(WhisperJobLifecycle.isValidTimeout(WhisperJobLifecycle.KIND_FILE, 3600_000L))
        assertTrue(WhisperJobLifecycle.isValidTimeout(WhisperJobLifecycle.KIND_FILE, WhisperJobLifecycle.MAX_DATA_SYNC_TIMEOUT_MS))
        assertFalse(WhisperJobLifecycle.isValidTimeout(WhisperJobLifecycle.KIND_FILE, WhisperJobLifecycle.MAX_DATA_SYNC_TIMEOUT_MS + 1L))
        assertFalse(WhisperJobLifecycle.isValidTimeout(WhisperJobLifecycle.KIND_FILE, 0L))
        assertFalse(WhisperJobLifecycle.isValidTimeout(WhisperJobLifecycle.KIND_FILE, -100L))
        assertTrue(WhisperJobLifecycle.isValidTimeout(WhisperJobLifecycle.KIND_LIVE, 24 * 3600_000L))
    }

    @Test
    fun `titulo e status inicial distinguem as rotas`() {
        assertEquals("Transcrição em andamento", WhisperJobLifecycle.notificationTitle(WhisperJobLifecycle.KIND_FILE))
        assertEquals(
            "Transcrição ao vivo em andamento",
            WhisperJobLifecycle.notificationTitle(WhisperJobLifecycle.KIND_LIVE)
        )
        assertEquals("Preparando transcrição…", WhisperJobLifecycle.initialStatus(WhisperJobLifecycle.KIND_FILE))
        assertEquals(
            "Preparando transcrição ao vivo…",
            WhisperJobLifecycle.initialStatus(WhisperJobLifecycle.KIND_LIVE)
        )
    }

    // ------------------------------------------------- R13/D3 FGS START

    @Test
    fun `subida tipada ok vence em qualquer rota`() {
        assertEquals(
            WhisperJobLifecycle.FgsStart.TYPED,
            WhisperJobLifecycle.startOutcome(WhisperJobLifecycle.KIND_FILE, typedOk = true, degradedOk = false)
        )
        assertEquals(
            WhisperJobLifecycle.FgsStart.TYPED,
            WhisperJobLifecycle.startOutcome(WhisperJobLifecycle.KIND_LIVE, typedOk = true, degradedOk = false)
        )
    }

    @Test
    fun `arquivo admite degradacao para dataSync`() {
        assertEquals(
            WhisperJobLifecycle.FgsStart.DEGRADED_FILE,
            WhisperJobLifecycle.startOutcome(WhisperJobLifecycle.KIND_FILE, typedOk = false, degradedOk = true)
        )
    }

    @Test
    fun `microphone nunca degrada para dataSync`() {
        assertEquals(
            WhisperJobLifecycle.FgsStart.FAILED,
            WhisperJobLifecycle.startOutcome(WhisperJobLifecycle.KIND_LIVE, typedOk = false, degradedOk = true)
        )
    }

    @Test
    fun `falha total em ambas as rotas`() {
        assertEquals(
            WhisperJobLifecycle.FgsStart.FAILED,
            WhisperJobLifecycle.startOutcome(WhisperJobLifecycle.KIND_FILE, typedOk = false, degradedOk = false)
        )
        assertEquals(
            WhisperJobLifecycle.FgsStart.FAILED,
            WhisperJobLifecycle.startOutcome(WhisperJobLifecycle.KIND_LIVE, typedOk = false, degradedOk = false)
        )
    }

    // ------------------------------------------------- R13/D3 WORKER LEASE

    @Test
    fun `lease restante e expiracao com relogio injetado`() {
        val lease = WhisperJobLifecycle.WorkerLease(acquiredElapsedMs = 1_000L, timeoutMs = 600_000L)
        assertEquals(600_000L, lease.remainingMs(1_000L))
        assertEquals(1_000L, lease.remainingMs(600_000L))
        assertFalse(lease.expired(600_999L))
        assertTrue(lease.expired(601_000L))
        assertFalse(lease.expiringSoon(1_000L))
        assertTrue(lease.expiringSoon(600_000L))
    }

    @Test
    fun `renovacao exige progresso e respeita o teto`() {
        val lease = WhisperJobLifecycle.WorkerLease(acquiredElapsedMs = 0L, timeoutMs = 600_000L)
        assertEquals(null, lease.renew(nowElapsedMs = 500_000L, progressObserved = false))
        // R14/B1: renovar só PERTO do prazo (>= 75 %). 500 s de 600 s = devido.
        val r1 = lease.renew(nowElapsedMs = 500_000L, progressObserved = true)
        assertTrue(r1 != null && r1!!.renewals == 1 && r1.acquiredElapsedMs == 500_000L)
        val r2 = r1!!.renew(nowElapsedMs = 1_000_000L, progressObserved = true)
        assertTrue(r2 != null && r2!!.renewals == 2)
        assertEquals(null, r2!!.renew(nowElapsedMs = 1_500_000L, progressObserved = true))
    }

    @Test
    fun `R14 dois updates no inicio NAO gastam o orcamento de renovacao`() {
        // Defeito da R13: dois update de status em 2 s consumiam as 2 renovações.
        val lease = WhisperJobLifecycle.WorkerLease(acquiredElapsedMs = 0L, timeoutMs = 600_000L)
        assertEquals(null, lease.renew(nowElapsedMs = 1_000L, progressObserved = true))
        assertEquals(null, lease.renew(nowElapsedMs = 2_000L, progressObserved = true))
        assertEquals("orçamento intacto", 0, lease.renewals)
        // Perto do prazo, a renovação é concedida.
        assertNotNull(lease.renew(nowElapsedMs = 450_000L, progressObserved = true))
    }

    @Test
    fun `R14 renewalDue marca a janela de renovacao`() {
        val lease = WhisperJobLifecycle.WorkerLease(acquiredElapsedMs = 0L, timeoutMs = 600_000L)
        assertFalse(lease.renewalDue(0L))
        assertFalse(lease.renewalDue(449_999L))
        assertTrue(lease.renewalDue(450_000L))
        assertTrue(lease.renewalDue(599_999L))
        assertFalse("expirado não é 'devido'", lease.renewalDue(600_000L))
    }

    @Test
    fun `lease expirado nao renova - encerrar`() {
        val lease = WhisperJobLifecycle.WorkerLease(acquiredElapsedMs = 0L, timeoutMs = 600_000L)
        assertEquals(null, lease.renew(nowElapsedMs = 600_001L, progressObserved = true))
    }
}
