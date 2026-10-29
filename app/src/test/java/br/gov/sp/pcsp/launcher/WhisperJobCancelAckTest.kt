package br.gov.sp.pcsp.launcher

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R15/B2 — ACEITE DO CANCELAMENTO (ACK).
 *
 * Defeito da R14: `forwardCancel` fazia
 *
 *     val ok = runCatching { f(owner) }.isSuccess
 *
 * e tratava "não lançou exceção" como "o dono aceitou". Como o forwarder era
 * `(Long) -> Unit`, um no-op — sessão já terminada, geração desconhecida,
 * Activity destruída — devolvia sucesso. O serviço então NÃO encerrava
 * localmente: ficava esperando um terminal que nunca vinha, segurando o wake
 * lock de dreno até o teto.
 *
 * A correção exige ACK EXPLÍCITO: o forwarder devolve `Boolean` e só `true`
 * conta. Estes testes fixam que:
 *  - no-op que devolve `false` NÃO é aceite;
 *  - exceção NÃO é aceite;
 *  - basta UM forwarder aceitar para o pedido valer;
 *  - sem nenhum forwarder registrado o resultado é `false` (e o serviço
 *    encerra localmente).
 */
class WhisperJobCancelAckTest {

    private val registrados = mutableListOf<(Long) -> Boolean>()

    private fun registrar(f: (Long) -> Boolean): (Long) -> Boolean {
        WhisperJobService.registerCancelForwarder(f)
        registrados.add(f)
        return f
    }

    @After
    fun limpar() {
        for (f in registrados) WhisperJobService.unregisterCancelForwarder(f)
        registrados.clear()
    }

    @Test
    fun `forwarder que recusa nao conta como aceite`() {
        registrar { false }
        assertFalse(
            "no-op (recusa explícita) não pode ser tratado como aceite",
            WhisperJobService.forwardCancel(7L)
        )
    }

    @Test
    fun `excecao no forwarder nao conta como aceite`() {
        registrar { throw IllegalStateException("sessão morta") }
        assertFalse(
            "exceção não é aceite (na R14 era, por causa do isSuccess)",
            WhisperJobService.forwardCancel(7L)
        )
    }

    @Test
    fun `basta um aceite entre varios forwarders`() {
        registrar { false }
        registrar { true }
        registrar { throw RuntimeException("boom") }
        assertTrue("um aceite já vale", WhisperJobService.forwardCancel(7L))
    }

    @Test
    fun `sem forwarder registrado o resultado e falso`() {
        assertFalse(
            "sem quem trate, o serviço tem de encerrar localmente",
            WhisperJobService.forwardCancel(11L)
        )
    }

    @Test
    fun `o aceite e por GERACAO`() {
        registrar { gen -> gen == 42L }
        assertTrue("a geração dona aceita", WhisperJobService.forwardCancel(42L))
        assertFalse("outra geração não é aceita", WhisperJobService.forwardCancel(43L))
    }

    @Test
    fun `regressao R14 - no-op sem excecao nao e aceite`() {
        // reproduz o formato antigo: uma lambda que não faz nada e não lança.
        // Antes: isSuccess == true  →  serviço esperava para sempre.
        // Agora: o contrato exige Boolean e `false` significa "não aceitei".
        val noOp: (Long) -> Boolean = { _ -> false }
        registrar(noOp)
        assertFalse(WhisperJobService.forwardCancel(1L))
        // e o último ACK observável fica registrado para a evidência
        assertFalse(WhisperJobService.lastForwardAck ?: true)
    }
}
