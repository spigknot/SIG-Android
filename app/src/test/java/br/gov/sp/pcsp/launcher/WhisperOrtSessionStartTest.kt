package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R100 §5.1 — regra do `start` que NÃO reaproveita o hidden de outra janela.
 *
 * A criação do `OnnxTensor` exige o runtime nativo (indisponível em teste JVM),
 * então o que se prova aqui é a REGRA que `WhisperOrtSession.start` aplica
 * incondicionalmente: fecha o tensor anterior e devolve um NOVO. O teste falharia
 * se a implementação voltasse ao `hiddenTensor ?: criar(...)`.
 */
class WhisperOrtSessionStartTest {

    private class TensorFake : AutoCloseable {
        var fechado = 0
        override fun close() {
            fechado += 1
        }
    }

    @Test
    fun `segunda janela fecha a primeira e cria outro tensor`() {
        val primeira = TensorFake()
        val segunda = TensorFake()
        var criados = 0

        val janela1 = trocarHidden<TensorFake>(null) { criados += 1; primeira }
        val janela2 = trocarHidden(janela1) { criados += 1; segunda }

        assertEquals(2, criados)
        assertNotSame("a segunda janela não pode reusar o tensor da primeira", janela1, janela2)
        assertEquals("o tensor da janela anterior tem de ser fechado", 1, primeira.fechado)
        assertEquals("o tensor novo não pode ser fechado antes do run", 0, segunda.fechado)
    }

    @Test
    fun `tres janelas fecham exatamente as anteriores`() {
        val tensores = List(3) { TensorFake() }
        var atual: TensorFake? = null
        for (t in tensores) atual = trocarHidden(atual) { t }

        assertEquals(1, tensores[0].fechado)
        assertEquals(1, tensores[1].fechado)
        assertEquals(0, tensores[2].fechado)
        assertTrue(atual === tensores[2])
    }

    @Test
    fun `falha ao fechar o anterior nao impede a janela nova`() {
        val quebrado = object : AutoCloseable {
            override fun close() = throw IllegalStateException("close falhou")
        }
        val novo = TensorFake()
        val resultado = trocarHidden(quebrado) { novo }
        assertTrue(resultado === novo)
    }
}
