package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADENDO 26 §2 — CONTRATO ASSÍNCRONO da apresentação exercitando o MESMO seam
 * que a `WhisperActivity` usa no post real ([WhisperApresentacaoObservador]).
 *
 * Relógio e fila são controlados pelo teste:
 *  - `agora` é o relógio monotônico injetado (nada de `Thread.sleep`);
 *  - "fila" é a ordem em que [WhisperApresentacaoObservador.apresentar] é
 *    chamado: emitir sem apresentar é post ENFILEIRADO, não apresentado.
 *
 * Os testes falham se o fluxo deixar de registrar a apresentação, aceitar
 * identidade de outra tentativa ou contar um post pendente como exibido.
 */
class WhisperApresentacaoContratoTest {

    private var agora = 0L

    private fun chave(job: Long = 7L, attempt: Long = 1L): TranscriptionJobKey =
        TranscriptionJobKey(
            runId = "run-teste",
            actId = 42,
            jobId = job,
            owner = 900L,
            inputId = "c.wav",
            attemptId = attempt,
        )

    private fun observador(key: TranscriptionJobKey = chave()) =
        WhisperApresentacaoObservador(key) { agora }

    @Test
    fun postEnfileiradoNaoContaComoApresentado() {
        val obs = observador()
        agora = 1_000
        obs.inicioCompute()
        agora = 5_000
        assertTrue(obs.emitido(1))
        assertTrue(obs.emitido(2))

        var escritas = 0
        agora = 5_100
        assertTrue(obs.apresentar(1, obs.identidade) { escritas++ })

        val r = obs.recibo()
        assertEquals("último ESPERADO é o último emitido", 2L, r.ultimoEsperado)
        assertEquals("só o 1 foi para a tela", 1L, r.ultimoApresentado)
        assertEquals(1, r.apresentados)
        assertEquals(1, r.pendentes)
        assertEquals(WhisperApresentacaoObservador.ESTADO_PENDENTE, r.estado)
        assertFalse("recibo não pode fechar com post enfileirado", r.fechado)
        assertEquals(1, escritas)
    }

    @Test
    fun postAtrasadoAposRetornoMedeIntervaloNoMesmoRelogio() {
        val obs = observador()
        agora = 1_000
        obs.inicioCompute()
        agora = 90_000
        obs.progresso99()
        obs.retorno()
        obs.emitido(1)

        // O post do último segmento só executa DEPOIS do retorno do compute.
        agora = 90_321
        assertTrue(obs.apresentar(1, obs.identidade) { })

        val r = obs.recibo()
        assertEquals("origem declarada = início do compute", "compute_inicio", r.origem)
        assertEquals(1_000L, r.origemEm)
        assertEquals(89_000L, r.marcasMs["retorno"])
        assertEquals(89_321L, r.apresentacaoMs)
        assertEquals("retorno→apresentação no MESMO domínio", 321L, r.retornoAteApresentacaoMs)
        assertEquals(WhisperApresentacaoObservador.ESTADO_APRESENTADO, r.estado)
    }

    @Test
    fun destroyRecusaSemApresentarTexto() {
        val obs = observador()
        obs.inicioCompute()
        agora = 10
        obs.emitido(1)
        agora = 20
        assertTrue(obs.recusar(1, WhisperApresentacaoObservador.MotivoRecusa.DESTROY))

        val r = obs.recibo()
        assertEquals(0L, r.ultimoApresentado)
        assertEquals(0, r.apresentados)
        assertEquals(
            "${WhisperApresentacaoObservador.ESTADO_RECUSADO}:DESTROY",
            r.estado,
        )
        assertEquals(WhisperApresentacaoObservador.MotivoRecusa.DESTROY, r.ultimoSegmento?.motivo)
    }

    @Test
    fun sucessorNaoApresentaTextoDeOutraTentativa() {
        val a = observador(chave(job = 7, attempt = 1))
        val b = observador(chave(job = 8, attempt = 2))
        a.inicioCompute()
        a.emitido(1)

        var escritasNoSucessor = 0
        // Post de A executando quando a corrente já é B: a escrita NÃO ocorre.
        assertFalse(a.apresentar(1, b.identidade) { escritasNoSucessor++ })
        assertEquals(0, escritasNoSucessor)
        assertEquals(0L, a.recibo().ultimoApresentado)
        assertEquals(
            WhisperApresentacaoObservador.MotivoRecusa.IDENTIDADE_DIVERGENTE,
            a.recibo().ultimoSegmento?.motivo,
        )
        // A tentativa B permanece intocada pelo evento de A.
        assertEquals(0L, b.recibo().ultimoEsperado)
        assertEquals(WhisperApresentacaoObservador.ESTADO_SEM_SEGMENTOS, b.recibo().estado)
    }

    @Test
    fun eventoObsoletoDeADepoisDoFechamentoNaoEntraEmB() {
        val a = observador(chave(job = 7, attempt = 1))
        val b = observador(chave(job = 8, attempt = 2))
        a.inicioCompute()
        a.emitido(1)
        a.fecharEmissao()
        // Emissão nova depois do fechamento é recusada (não cria segmento).
        assertFalse(a.emitido(2))

        // B vivo recebe sua própria apresentação normalmente.
        b.inicioCompute()
        b.emitido(1)
        assertTrue(b.apresentar(1, b.identidade) { })

        // Evento atrasado de A chega em B: recusado e sem escrita.
        var escritas = 0
        assertFalse(b.apresentar(1, a.identidade) { escritas++ })
        assertEquals(0, escritas)
        assertEquals(1L, b.recibo().ultimoApresentado)
        assertEquals(WhisperApresentacaoObservador.ESTADO_APRESENTADO, b.recibo().estado)
        assertEquals(1, b.recibo().apresentados)
    }

    @Test
    fun apresentacaoSemEmissaoPreviaEhRecusada() {
        val obs = observador()
        obs.inicioCompute()
        var escritas = 0
        agora = 50
        assertFalse(obs.apresentar(9, obs.identidade) { escritas++ })
        assertEquals("post de sequência nunca emitida não escreve", 0, escritas)
        assertEquals(
            WhisperApresentacaoObservador.MotivoRecusa.FORA_DE_ORDEM,
            obs.recibo().ultimoSegmento?.motivo,
        )
    }

    @Test
    fun apresentacaoRepetidaNaoContaDuasVezes() {
        val obs = observador()
        obs.inicioCompute()
        obs.emitido(1)
        obs.emitido(2)
        agora = 10
        assertTrue(obs.apresentar(1, obs.identidade) { })
        agora = 20
        assertTrue(obs.apresentar(2, obs.identidade) { })
        val marco = obs.recibo().apresentacaoMs
        agora = 99
        // Re-execução do MESMO post não re-marca nem duplica contagem.
        assertFalse(obs.apresentar(2, obs.identidade) { })
        val r = obs.recibo()
        assertEquals(2, r.apresentados)
        assertEquals(2L, r.ultimoApresentado)
        assertEquals("instante da apresentação não regride", marco, r.apresentacaoMs)
    }

    @Test
    fun reciboFinalSoFechaComComputePersistenciaEFinalizacao() {
        val obs = observador()
        agora = 1_000
        obs.inicioCompute()
        obs.emitido(1)
        agora = 2_000
        assertTrue(obs.apresentar(1, obs.identidade) { })
        // Desfecho do segmento ok, mas o job ainda não persistiu/finalizou.
        assertTrue(obs.recibo().fechado)
        assertFalse(
            "recibo final antes da persistência seria fotografia precoce",
            obs.recibo().podeFechar,
        )

        obs.retorno()
        obs.parse()
        obs.persistencia()
        obs.finalizacao()
        // ADENDO 27 §2 — finalização sozinha NÃO fecha: falta o FATO terminal.
        val semTerminal = obs.recibo()
        assertFalse("não pode fechar sem o fato terminal", semTerminal.podeFechar)
        obs.terminal(liberacaoConfirmada = true)
        val r = obs.recibo()
        assertTrue(r.podeFechar)
        assertEquals(1_000L, r.marcasMs["persistencia"])
        assertTrue(obs.reservarPublicacaoFinal())
        assertFalse("linha final é publicada UMA vez", obs.reservarPublicacaoFinal())
        val linha = obs.linha("final")
        assertTrue(linha.startsWith("apresentacao rotulo=final job=7 gen=900 att=1"))
        assertTrue(linha.contains("estado=APRESENTADO"))
        assertTrue(linha.contains("retorno_ate_apresentacao_ms="))
    }

    @Test
    fun linhaDoReciboNaoTemCampoVazio() {
        val obs = observador()
        obs.inicioCompute()
        obs.emitido(1)
        obs.apresentar(1, obs.identidade) { }
        val linha = obs.linha("fase1")
        assertNotNull(linha)
        assertTrue(linha.contains("ultimo_esperado=1"))
        assertTrue(linha.contains("entrada=c.wav"))
        assertTrue(linha.contains("origem=compute_inicio"))
    }

    @Test
    fun reciboNuncaDevolveOutroJob() {
        val a = observador(chave(job = 7, attempt = 1))
        a.inicioCompute()
        a.emitido(1)
        a.apresentar(1, a.identidade) { }
        val r = a.recibo()
        assertEquals(7L, r.identidade.jobId)
        assertEquals(1L, r.identidade.attemptId)
        assertNull(observador(chave(job = 99, attempt = 9)).recibo().ultimoSegmento)
    }

    /**
     * ADENDO 27 §2 — DUAS ORDENS de fechamento, as duas completas:
     *  (a) finalização ANTES do terminal (UI first);
     *  (b) terminal ANTES da finalização (worker first).
     * Em ambas o recibo só fecha com os DOIS fatos, e a linha final traz
     * terminal e liberação preenchidos.
     */
    @Test
    fun ordemFinalizacaoAntesDoTerminalFechaCompleto() {
        val obs = observador()
        obs.inicioCompute()
        obs.emitido(1)
        obs.apresentar(1, obs.identidade) { }
        obs.persistencia()
        obs.finalizacao()
        assertFalse("sem terminal ainda não fecha", obs.recibo().podeFechar)
        obs.terminal(liberacaoConfirmada = true)
        val r = obs.recibo()
        assertTrue(r.podeFechar)
        assertTrue(r.marcasMs.containsKey("terminal"))
        assertTrue(r.marcasMs.containsKey("liberacao"))
        assertEquals(false, r.liberacaoPendente)
        assertFalse("finalização presente não é 'ausente'", obs.linha("final").contains("finalizacao_ausente=true"))
    }

    @Test
    fun ordemTerminalAntesDaFinalizacaoFechaCompleto() {
        val obs = observador()
        obs.inicioCompute()
        obs.emitido(1)
        obs.apresentar(1, obs.identidade) { }
        obs.persistencia()
        obs.terminal(liberacaoConfirmada = true)
        assertFalse("sem finalização/ausência ainda não fecha", obs.recibo().podeFechar)
        obs.finalizacao()
        val r = obs.recibo()
        assertTrue(r.podeFechar)
        assertTrue(r.marcasMs.containsKey("terminal"))
        assertTrue(r.marcasMs.containsKey("liberacao"))
        assertTrue(obs.reservarPublicacaoFinal())
        assertFalse(obs.reservarPublicacaoFinal())
    }

    @Test
    fun destroyFechaComFinalizacaoAusente() {
        val obs = observador()
        obs.inicioCompute()
        obs.emitido(1)
        assertTrue(obs.recusar(1, WhisperApresentacaoObservador.MotivoRecusa.DESTROY))
        obs.persistencia()
        // A UI morreu: o fato é a AUSÊNCIA tipada, não uma espera.
        obs.finalizacaoAusente()
        assertFalse("falta o terminal", obs.recibo().podeFechar)
        obs.terminal(liberacaoConfirmada = false)
        val r = obs.recibo()
        assertTrue(r.podeFechar)
        assertTrue(r.liberacaoPendente)
        val linha = obs.linha("final")
        assertTrue(linha.contains("finalizacao_ausente=true"))
        assertTrue(linha.contains("estado=RECUSADO:DESTROY"))
    }

    @Test
    fun semFinalizacaoNemAusenciaNuncaFecha() {
        val obs = observador()
        obs.inicioCompute()
        obs.emitido(1)
        obs.apresentar(1, obs.identidade) { }
        obs.persistencia()
        obs.terminal(liberacaoConfirmada = true)
        assertFalse(
            "sem finalização nem ausência o recibo fica incompleto (nunca fecha só com o terminal)",
            obs.recibo().podeFechar,
        )
    }
}
