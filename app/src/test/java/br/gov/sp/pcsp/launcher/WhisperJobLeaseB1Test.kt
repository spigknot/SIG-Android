package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R15/B1 — MONITOR DO PRAZO FÍSICO DO WAKE LOCK.
 *
 * Defeito da R14: o monitor era agendado para `prazo + 250 ms`. Como o lock é
 * adquirido com `acquire(timeout)`, o framework o solta EXATAMENTE no prazo —
 * então a decisão acontecia com o job já DESPROTEGIDO. Estes testes fixam as
 * três propriedades que a correção precisa manter:
 *
 *  1. o instante de decisão cai ANTES do prazo e DENTRO da janela de renovação
 *     (>= 75 % consumido) — senão `renew` seria sempre negado;
 *  2. a renovação exige progresso e respeita o orçamento de [MAX_RENEWALS];
 *  3. quando não há renovação, o cancelamento é pedido com o lock ainda em
 *     mãos (o serviço registra `lock_held` no evento) e o dreno é coberto por
 *     um lock LIMITADO.
 */
class WhisperJobLeaseB1Test {

    private val timeout = 10 * 60 * 1000L   // 10 min

    // ---------------------------------------------------------- 1) A MARGEM

    @Test
    fun `margem deixa a decisao antes do prazo e dentro da janela de renovacao`() {
        val casos = listOf(30_000L, 60_000L, 5 * 60_000L, 10 * 60_000L, 30 * 60_000L)
        for (t in casos) {
            val margin = WhisperJobLifecycle.leaseCheckMarginMs(t)
            val decisao = t - margin
            assertTrue("margem positiva para t=$t", margin > 0L)
            assertTrue("decisão ANTES do prazo (t=$t)", decisao < t)
            // o instante de decisão ainda precisa ser "devido" para renovar
            val lease = WhisperJobLifecycle.WorkerLease(acquiredElapsedMs = 0L, timeoutMs = t)
            assertTrue(
                "decisão dentro da janela de 75% (t=$t, decisão=$decisao)",
                lease.renewalDue(decisao)
            )
            assertFalse("decisão não pode ser depois do prazo (t=$t)", lease.expired(decisao))
        }
    }

    @Test
    fun `margem nunca passa de um quarto do prazo`() {
        // garantia estrutural: se passasse de 25 %, `renewalDue` seria falso
        for (t in listOf(30_000L, 60_000L, 120_000L, 600_000L)) {
            val margin = WhisperJobLifecycle.leaseCheckMarginMs(t)
            assertTrue("margem=$margin <= t/4 para t=$t", margin <= t / 4)
        }
    }

    // ------------------------------------------------------ 2) A RENOVAÇÃO

    @Test
    fun `renova no ponto pre-prazo quando ha progresso`() {
        val lease = WhisperJobLifecycle.WorkerLease(0L, timeout)
        val decisao = timeout - WhisperJobLifecycle.leaseCheckMarginMs(timeout)
        val novo = lease.renew(decisao, progressObserved = true)
        assertNotNull("renovação devida com progresso", novo)
        assertEquals(1, novo!!.renewals)
        assertEquals("o prazo físico recomeça", decisao, novo.acquiredElapsedMs)
        assertFalse("novo prazo não está expirado", novo.expired(decisao))
    }

    @Test
    fun `nao renova sem progresso observado`() {
        val lease = WhisperJobLifecycle.WorkerLease(0L, timeout)
        val decisao = timeout - WhisperJobLifecycle.leaseCheckMarginMs(timeout)
        assertNull("sem progresso não há renovação", lease.renew(decisao, progressObserved = false))
    }

    @Test
    fun `nao renova concessao ja expirada`() {
        val lease = WhisperJobLifecycle.WorkerLease(0L, timeout)
        assertNull(lease.renew(timeout, progressObserved = true))
        assertNull(lease.renew(timeout + 1L, progressObserved = true))
    }

    @Test
    fun `orcamento de renovacoes e finito mesmo com progresso continuo`() {
        var lease = WhisperJobLifecycle.WorkerLease(0L, timeout)
        var now = 0L
        var renovacoes = 0
        // simula um job que reporta progresso sem parar: o lock NÃO pode viver
        // para sempre
        repeat(10) {
            now = lease.acquiredElapsedMs + lease.timeoutMs -
                WhisperJobLifecycle.leaseCheckMarginMs(lease.timeoutMs)
            val novo = lease.renew(now, progressObserved = true) ?: return@repeat
            lease = novo
            renovacoes++
        }
        assertEquals(
            "teto de renovação respeitado",
            WhisperJobLifecycle.WorkerLease.MAX_RENEWALS, renovacoes
        )
        assertNull(
            "esgotado o orçamento, a próxima renovação é negada",
            lease.renew(lease.acquiredElapsedMs + lease.timeoutMs, progressObserved = true)
        )
    }

    // ------------------------------------------------- 3) CAMINHO DE CANCELAMENTO

    @Test
    fun `sem renovacao o prazo vence e o cancelamento e obrigatorio`() {
        val lease = WhisperJobLifecycle.WorkerLease(0L, timeout)
        // job sem progresso: nenhuma renovação é concedida em nenhum instante
        for (now in listOf(timeout / 2, (timeout * 3) / 4, timeout - 1L)) {
            assertNull(
                "sem progresso não renova em now=$now",
                lease.renew(now, progressObserved = false)
            )
        }
        assertTrue("vence no prazo", lease.expired(timeout))
        assertFalse("não é 'devido' depois de expirado", lease.renewalDue(timeout))
    }

    @Test
    fun `o dreno e limitado e menor que o prazo da concessao`() {
        val dreno = WhisperJobService.LEASE_DRAIN_MS
        assertTrue("dreno positivo", dreno > 0L)
        assertTrue(
            "dreno ($dreno) não pode ser maior que o prazo típico ($timeout)",
            dreno < timeout
        )
        assertEquals(
            "dreno não é renovação de concessão (não consome orçamento)",
            0, WhisperJobLifecycle.WorkerLease(0L, dreno).renewals
        )
    }

    // --------------------------------- 3b) ORÇAMENTO DE DRENO (R15/B2)

    @Test
    fun `a rechecagem acontece antes de o lock de dreno vencer`() {
        assertTrue(
            "rechecagem (${WhisperJobService.LEASE_RECHECK_MS}) tem de ser menor " +
                "que o lock de dreno (${WhisperJobService.LEASE_DRAIN_MS}), senão há " +
                "janela desprotegida silenciosa",
            WhisperJobService.LEASE_RECHECK_MS < WhisperJobService.LEASE_DRAIN_MS
        )
    }

    @Test
    fun `o dreno tem orcamento finito`() {
        val totalDreno = WhisperJobService.LEASE_DRAIN_MS * WhisperJobService.MAX_DRAIN_ROUNDS
        assertTrue("orçamento de dreno positivo", WhisperJobService.MAX_DRAIN_ROUNDS >= 1)
        assertTrue(
            "proteção extra de dreno ($totalDreno ms) tem de ser finita e curta",
            totalDreno in 1..(5 * 60_000L)
        )
        // 3 rodadas já estouram o orçamento: o serviço declara terminal honesto
        assertTrue(
            "após o orçamento, não há novo dreno",
            WhisperJobService.MAX_DRAIN_ROUNDS < 3
        )
    }

    // --------------------------------- 3c) POLÍTICA ÚNICA (R15/B2)

    @Test
    fun `job em cancelamento NAO renova mesmo com progresso e na janela`() {
        // Defeito visto na célula física b23-destroy: um status que chegava
        // DEPOIS do pedido de cancelamento renovava a concessão (via=status),
        // esticando o dreno de um job que já devia estar terminando.
        val lease = WhisperJobLifecycle.WorkerLease(0L, timeout)
        val janela = (timeout * 3L) / 4L
        assertTrue(
            "sem cancelamento, renovaria",
            WhisperJobLifecycle.shouldRenew(false, lease, janela, progressObserved = true)
        )
        assertFalse(
            "cancelando, NÃO renova",
            WhisperJobLifecycle.shouldRenew(true, lease, janela, progressObserved = true)
        )
    }

    @Test
    fun `sem progresso nao renova pela politica unica`() {
        val lease = WhisperJobLifecycle.WorkerLease(0L, timeout)
        val janela = (timeout * 3L) / 4L
        assertFalse(
            WhisperJobLifecycle.shouldRenew(false, lease, janela, progressObserved = false)
        )
        assertTrue(
            WhisperJobLifecycle.shouldRenew(false, lease, janela, progressObserved = true)
        )
    }

    @Test
    fun `a politica unica respeita janela orcamento e vencimento`() {
        val lease = WhisperJobLifecycle.WorkerLease(0L, timeout)
        // fora da janela (cedo demais)
        assertFalse(
            "cedo demais",
            WhisperJobLifecycle.shouldRenew(false, lease, 1000L, progressObserved = true)
        )
        // expirada
        assertFalse(
            "expirada",
            WhisperJobLifecycle.shouldRenew(false, lease, timeout, progressObserved = true)
        )
        // orçamento esgotado
        var esgotado = lease.copy(renewals = WhisperJobLifecycle.WorkerLease.MAX_RENEWALS)
        assertFalse(
            "orçamento esgotado",
            WhisperJobLifecycle.shouldRenew(
                false, esgotado, (timeout * 3L) / 4L, progressObserved = true
            )
        )
        // sanidade: dentro da janela, com progresso e orçamento
        assertTrue(
            WhisperJobLifecycle.shouldRenew(
                false, lease, (timeout * 3L) / 4L, progressObserved = true
            )
        )
    }

    // ---------------------------------- 4) REGRESSÃO EXPLÍCITA DA R14

    @Test
    fun `regressao R14 - decidir depois do prazo deixa o job desprotegido`() {
        // O bug: postDelayed(prazo + 250). No instante prazo+250 o lock físico
        // JÁ foi solto pelo framework, e `expired` é verdadeiro: nenhuma
        // renovação é possível, só cancelar. O teste documenta o motivo de a
        // margem ter de ser SUBTRAÍDA do prazo.
        val lease = WhisperJobLifecycle.WorkerLease(0L, timeout)
        val atrasado = timeout + 250L
        assertTrue("no agendamento da R14 a concessão já venceu", lease.expired(atrasado))
        assertNull(
            "e não há renovação possível nesse instante",
            lease.renew(atrasado, progressObserved = true)
        )
        // com a correção, a decisão é tomada com folga:
        val correto = timeout - WhisperJobLifecycle.leaseCheckMarginMs(timeout)
        assertFalse("com margem, ainda protegido", lease.expired(correto))
        assertNotNull("e renovável", lease.renew(correto, progressObserved = true))
    }
}
