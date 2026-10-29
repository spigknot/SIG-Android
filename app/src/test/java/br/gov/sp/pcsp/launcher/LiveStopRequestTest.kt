package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

/**
 * R13/F2-fix — o STOP do usuário no caminho AO VIVO é um PEDIDO DE CANCELAMENTO
 * do job em voo.
 *
 * Achado F2 (piloto live, 2026-10-19): clique real em parar com janela em voo
 * deu pedido→terminal de 29.044 ms. A causa não é o nativo: no instante do
 * clique NENHUM pedido foi emitido — o stop só parava de capturar e esperava a
 * janela terminar; o primeiro (e único) cancel saiu do `onDestroy`, 29 s depois,
 * e abortou a janela SEGUINTE em 947 ms.
 *
 * Contrato exigido destas unidades (o que a célula física cobra):
 *  - o stop encaminha UM cancelamento por-dono para a geração do job ao vivo;
 *  - o áudio ainda enfileirado é descartado (senão o próximo chunk vira um
 *    decode novo de ~82 s, e o pedido já foi consumido);
 *  - um segundo stop não reencaminha (idempotente);
 *  - geração desconhecida (<= 0) NUNCA vira pedido: sem owner não há alvo, e um
 *    pedido anônimo derrubaria outro job;
 *  - falha do cancelamento nativo não deixa o pendente sendo decodificado.
 */
class LiveStopRequestTest {

    private class Recorder {
        val native = Collections.synchronizedList(mutableListOf<Long>())
        val pipeline = Collections.synchronizedList(mutableListOf<String>())
        val forwarded = Collections.synchronizedList(mutableListOf<Long>())
        var throwOnNative = false
    }

    private fun request(r: Recorder) = LiveStopRequest(
        cancelNative = { gen ->
            r.native.add(gen)
            if (r.throwOnNative) throw IllegalStateException("gateway indisponível")
        },
        cancelPipeline = { r.pipeline.add("cancel") },
        onForwarded = { gen -> r.forwarded.add(gen) },
    )

    @Test
    fun `stop encaminha um cancelamento por dono e descarta o pendente`() {
        val r = Recorder()
        val stop = request(r)

        assertTrue("o stop precisa encaminhar o pedido", stop.request(7L))
        assertEquals("um pedido por-dono, para a geração do job ao vivo", listOf(7L), r.native)
        assertEquals("o áudio enfileirado não pode virar decode novo", listOf("cancel"), r.pipeline)
        assertEquals("a marca do pedido é única", listOf(7L), r.forwarded)
    }

    @Test
    fun `segundo stop nao reencaminha o pedido`() {
        val r = Recorder()
        val stop = request(r)

        assertTrue(stop.request(7L))
        assertFalse("stop repetido (clique duplo / notificação) é no-op", stop.request(7L))
        assertEquals(listOf(7L), r.native)
        assertEquals(listOf("cancel"), r.pipeline)
    }

    @Test
    fun `geracao desconhecida nao vira pedido anonimo`() {
        val r = Recorder()
        val stop = request(r)

        assertFalse(stop.request(0L))
        assertFalse(stop.request(-3L))
        assertTrue("nenhum owner inventado", r.native.isEmpty())
        assertTrue("nada a descartar sem job", r.pipeline.isEmpty())
        assertTrue(r.forwarded.isEmpty())
    }

    @Test
    fun `falha do cancelamento nativo ainda descarta o pendente`() {
        val r = Recorder()
        r.throwOnNative = true
        val stop = request(r)

        assertTrue("o pedido foi tentado uma vez e não é re-tentado", stop.request(7L))
        assertEquals(listOf(7L), r.native)
        assertEquals("sem o descarte, o próximo chunk vira decode novo", listOf("cancel"), r.pipeline)
    }
}
