package br.gov.sp.pcsp.launcher

import org.junit.Assert.*
import org.junit.Test

class FfmpegPreviewSeekQueueTest {
    @Test fun arrasteMantemSomenteDestinoFinal() {
        val queue = FfmpegPreviewSeekQueue()
        repeat(1000) { queue.request(it.toLong(), it.toLong()); assertNull(queue.take(it.toLong())) }
        assertNull(queue.take(1118L))
        assertEquals(999L, queue.take(1119L))
        assertNull(queue.take(5000L))
        queue.complete()
        assertFalse(queue.isSeeking)
    }

    @Test fun buscaEmAndamentoNaoRecebeOutraAteConcluir() {
        val queue = FfmpegPreviewSeekQueue()
        queue.request(100L, 0L, immediate = true)
        assertEquals(100L, queue.take(0L))
        queue.request(200L, 1L)
        queue.request(300L, 2L, immediate = true)
        assertNull(queue.take(1000L))
        assertNull(queue.delayMs(1000L))
        queue.complete()
        assertEquals(300L, queue.take(1000L))
    }

    @Test fun pedidoImediatoSubstituiArrastePendente() {
        val queue = FfmpegPreviewSeekQueue()
        queue.request(900L, 0L)
        queue.request(250L, 10L, immediate = true)
        assertEquals(250L, queue.take(10L))
        queue.complete()
        assertNull(queue.take(1000L))
    }

    @Test fun cancelamentoPreservaBuscaAtivaEDescarteRemoveTudo() {
        val queue = FfmpegPreviewSeekQueue()
        queue.request(10L, 0L, immediate = true)
        queue.take(0L)
        queue.request(20L, 1L)
        queue.cancelPending()
        assertTrue(queue.isSeeking)
        queue.complete()
        assertFalse(queue.isSeeking)
        queue.request(30L, 2L)
        queue.reset()
        assertNull(queue.take(1000L))
        assertFalse(queue.isSeeking)
    }
}
