package br.gov.sp.pcsp.launcher

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Regressões de posse física, limite do cache e cancelamento do download na integração. */
class WhisperOrtReleaseSafetyTest {
    private fun key(threads: Int) = WhisperOrtSessionCache.Key(
        "ORT", "CPU", "test", "turbo", "rev", "config", threads, "version",
    )

    @Test fun trocaDeThreadsFechaIdleERecusaExpulsarLeaseAtivo() {
        val backends = mutableListOf<WhisperOrtFakeBackend>()
        val cache = WhisperOrtSessionCache(
            factory = { _, _, _, _ -> WhisperOrtFakeBackend(emptyList()).also { backends += it } },
            open = {}, maxEntries = 1,
        )
        val file = File("unused")
        fun acquire(t: Int) = cache.acquire(key(t), "version", file, file, file)
        val first = acquire(1)!!
        assertTrue(cache.tryBeginCompute(first))
        assertNull(acquire(2))
        assertFalse(backends.single().closed)
        cache.endCompute(first)
        assertTrue(cache.release(first))
        for (threads in 2..8) {
            val lease = acquire(threads)!!
            assertEquals(1, cache.stats().live)
            assertTrue(cache.release(lease))
        }
        assertEquals(8, backends.size)
        assertEquals(7, backends.count { it.closed })
        val last = acquire(8)!!
        assertTrue(cache.tryBeginCompute(last))
        cache.endCompute(last)
        cache.release(last)
        cache.closeAll()
    }

    @Test fun cancelamentoDoDownloadImpedeAtivacaoENaoLiberaOwnerAntesDaLimpeza() {
        val first = WhisperOrtDownloadOwner.begin()!!
        try {
            first.cancel()
            assertNull(WhisperOrtDownloadOwner.begin())
            var published = false
            assertNull(first.publish { published = true })
            assertFalse(published)
        } finally { WhisperOrtDownloadOwner.finish(first) }
        val second = WhisperOrtDownloadOwner.begin()!!
        try {
            assertNotEquals(first.id, second.id)
            WhisperOrtDownloadOwner.finish(first)
            assertNull(WhisperOrtDownloadOwner.begin())
            assertEquals("committed", second.publish { "committed" })
        } finally { WhisperOrtDownloadOwner.finish(second) }
    }

    @Test fun interrupcaoDaEsperaNaoLiberaAdmissaoAntesDoCallbackFisico() {
        val admission = WhisperOrtAdmission()
        val job = (admission.admit("A", 1, "ORT", "CPU", "a".repeat(64)) as WhisperOrtAdmission.Admitted.Ok).job
        val waiting = CountDownLatch(1)
        val callback = CountDownLatch(1)
        val done = AtomicBoolean(false)
        val motorNulo = object : WhisperOrtMediaConverter.Motor {
            override fun iniciar(argumentos: Array<String>, aoTerminar: (WhisperOrtMediaConverter.Final) -> Unit) =
                throw UnsupportedOperationException()
            override fun cancelar(sessao: WhisperOrtMediaConverter.Sessao) = Unit
        }
        val sessaoNula = object : WhisperOrtMediaConverter.Sessao {
            override val id: Long = 7L
        }
        val active = WhisperOrtMediaConverter.Resultado.SessaoAtiva("timeout", 7, File("unused"), sessaoNula, motorNulo) {
            waiting.countDown()
            callback.await()
            WhisperOrtMediaConverter.Final(false, true, "cancelled")
        }
        val worker = Thread {
            WhisperOrtMediaConverter.aguardarTermino(active)
            admission.complete(job, "CANCELLED", "", 0, false)
            done.set(true)
        }.apply { isDaemon = true; start() }
        try {
            assertTrue(waiting.await(2, TimeUnit.SECONDS))
            worker.interrupt()
            assertTrue(admission.requestCancel(job))
            assertTrue(admission.isBusy())
            assertFalse(done.get())
            assertTrue(admission.admit("B", 1, "ORT", "CPU", "b".repeat(64)) is WhisperOrtAdmission.Admitted.Refused)
        } finally {
            callback.countDown()
            worker.join(2000)
        }
        assertTrue(done.get())
        assertFalse(admission.isBusy())
        assertEquals("CANCELLED", admission.terminalOf(job)?.outcome)
    }

    private class MotorControlado : WhisperOrtMediaConverter.Motor {
        data class Viva(override val id: Long = 77L) : WhisperOrtMediaConverter.Sessao
        val canceladas = mutableListOf<Long>()
        val cancelRecebido = CountDownLatch(1)
        override fun iniciar(argumentos: Array<String>, aoTerminar: (WhisperOrtMediaConverter.Final) -> Unit): WhisperOrtMediaConverter.Sessao =
            throw UnsupportedOperationException("o teste controla uma sessão já ativa")
        override fun cancelar(sessao: WhisperOrtMediaConverter.Sessao) {
            synchronized(canceladas) { canceladas.add(sessao.id) }
            cancelRecebido.countDown()
        }
    }

    @Test fun drenoEncaminhaCancelTardioASessaoDona() {
        val motor = MotorControlado()
        val esperando = CountDownLatch(1)
        val terminoFisico = CountDownLatch(1)
        val ativa = WhisperOrtMediaConverter.Resultado.SessaoAtiva(
            "prazo", 77L, File("unused"), MotorControlado.Viva(), motor,
        ) { prazo ->
            esperando.countDown()
            if (terminoFisico.await(prazo, TimeUnit.MILLISECONDS)) {
                WhisperOrtMediaConverter.Final(false, true, "cancelled")
            } else null
        }
        val pedidoCancel = AtomicBoolean(false)
        val fim = java.util.concurrent.atomic.AtomicReference<WhisperOrtMediaConverter.Final?>()
        val t = Thread({
            fim.set(WhisperOrtMediaConverter.drenar(ativa, isCancelada = pedidoCancel::get))
        }, "dreno-test").apply { isDaemon = true; start() }
        try {
            assertTrue("o dreno precisa estar esperando", esperando.await(5, TimeUnit.SECONDS))
            assertTrue("nenhum cancel anterior pode satisfazer a prova", motor.canceladas.isEmpty())
            pedidoCancel.set(true)
            assertTrue("novo cancel não chegou ao motor", motor.cancelRecebido.await(5, TimeUnit.SECONDS))
            assertEquals(listOf(77L), synchronized(motor.canceladas) { motor.canceladas.toList() })
            assertTrue("cancel não é término físico", t.isAlive)
            assertNull(fim.get())
        } finally {
            terminoFisico.countDown()
            t.join(5_000)
        }
        assertFalse("dreno deve terminar após o callback", t.isAlive)
        val f = fim.get()
        assertNotNull("dreno deveria terminar no callback tardio", f)
        assertTrue("cancelamento tardio preserva o estado", f!!.cancelada)
    }

    @Test fun falhaDaEsperaMantemPosseAteCallback() {
        val motor = MotorControlado()
        val chamadas = java.util.concurrent.atomic.AtomicInteger(0)
        val esperandoAposFalha = CountDownLatch(1)
        val terminoFisico = CountDownLatch(1)
        val admission = WhisperOrtAdmission()
        val job = (admission.admit("A", 1, "ORT", "CPU", "a".repeat(64)) as WhisperOrtAdmission.Admitted.Ok).job
        val ativa = WhisperOrtMediaConverter.Resultado.SessaoAtiva(
            "prazo", 77L, File("unused"),
            object : WhisperOrtMediaConverter.Sessao { override val id = 77L },
            motor,
        ) { prazo ->
            // Primeira espera falha (interrupção simulada); o dreno NÃO desiste —
            // mantém a posse até o callback tardio concluir.
            if (chamadas.incrementAndGet() == 1) throw InterruptedException("falha simulada da espera")
            esperandoAposFalha.countDown()
            if (terminoFisico.await(prazo, TimeUnit.MILLISECONDS)) {
                WhisperOrtMediaConverter.Final(ok = true, cancelada = false, logsCauda = "")
            } else null
        }
        val fim = java.util.concurrent.atomic.AtomicReference<WhisperOrtMediaConverter.Final?>()
        val interrupcaoPreservada = AtomicBoolean(false)
        val drenando = Thread {
            fim.set(WhisperOrtMediaConverter.drenar(ativa, isCancelada = { false }))
            interrupcaoPreservada.set(Thread.currentThread().isInterrupted)
            admission.complete(job, "SUCCESS", "", 0, false)
        }.apply { isDaemon = true; start() }
        try {
            assertTrue("a espera precisa continuar após a falha", esperandoAposFalha.await(5, TimeUnit.SECONDS))
            assertTrue("falha da espera não pode encerrar", drenando.isAlive)
            assertTrue(admission.isBusy())
            assertTrue(admission.admit("B", 1, "ORT", "CPU", "b".repeat(64)) is WhisperOrtAdmission.Admitted.Refused)
        } finally {
            terminoFisico.countDown()
            drenando.join(5_000)
        }
        assertFalse("worker do dreno deveria ter terminado", drenando.isAlive)
        assertNotNull(fim.get())
        assertTrue(fim.get()!!.ok)
        assertFalse(admission.isBusy())
        assertTrue(chamadas.get() >= 2)
        assertTrue("a interrupção deve ser restaurada após o fim físico", interrupcaoPreservada.get())
    }

    @Test fun callbackFisicoEncerraMesmoQuandoCancelarContinuaFalhando() {
        val cancelTentado = CountDownLatch(1)
        val terminoFisico = CountDownLatch(1)
        val drenoTerminou = CountDownLatch(1)
        val permitirCancel = AtomicBoolean(false)
        val sessao = object : WhisperOrtMediaConverter.Sessao { override val id = 88L }
        val motor = object : WhisperOrtMediaConverter.Motor {
            override fun iniciar(argumentos: Array<String>, aoTerminar: (WhisperOrtMediaConverter.Final) -> Unit): WhisperOrtMediaConverter.Sessao =
                throw UnsupportedOperationException("sessão já ativa")
            override fun cancelar(sessao: WhisperOrtMediaConverter.Sessao) {
                cancelTentado.countDown()
                if (!permitirCancel.get()) throw IllegalStateException("cancel indisponível")
            }
        }
        val ativa = WhisperOrtMediaConverter.Resultado.SessaoAtiva(
            "prazo", sessao.id, File("unused"), sessao, motor,
        ) { prazo ->
            if (terminoFisico.await(prazo, TimeUnit.MILLISECONDS)) {
                WhisperOrtMediaConverter.Final(true, false, "concluiu sem cancel")
            } else null
        }
        val resultado = java.util.concurrent.atomic.AtomicReference<WhisperOrtMediaConverter.Final?>()
        val worker = Thread {
            resultado.set(WhisperOrtMediaConverter.drenar(ativa, isCancelada = { true }))
            drenoTerminou.countDown()
        }.apply { isDaemon = true; start() }
        try {
            assertTrue(cancelTentado.await(2, TimeUnit.SECONDS))
            assertTrue("cancel falho não equivale a término", worker.isAlive)
            terminoFisico.countDown()
            assertTrue("callback deve ser observado mesmo se cancelar lança sempre", drenoTerminou.await(2, TimeUnit.SECONDS))
            assertTrue(resultado.get()!!.ok)
        } finally {
            permitirCancel.set(true)
            terminoFisico.countDown()
            worker.join(3_000)
        }
        assertFalse("teste não pode abandonar worker ativo", worker.isAlive)
    }

    // Adendo 16 §1 — posse física nas exceções (conversor + dreno + observador).

    private class MotorConversor(
        val aoIniciar: CountDownLatch = CountDownLatch(1),
        var falharCancelar: Boolean = false,
    ) : WhisperOrtMediaConverter.Motor {
        data class Viva(override val id: Long = 4242L) : WhisperOrtMediaConverter.Sessao
        var aoTerminar: ((WhisperOrtMediaConverter.Final) -> Unit)? = null
        val canceladas = mutableListOf<Long>()
        override fun iniciar(
            argumentos: Array<String>,
            aoTerminar: (WhisperOrtMediaConverter.Final) -> Unit,
        ): WhisperOrtMediaConverter.Sessao {
            this.aoTerminar = aoTerminar
            aoIniciar.countDown()
            return Viva()
        }
        override fun cancelar(sessao: WhisperOrtMediaConverter.Sessao) {
            if (falharCancelar) throw RuntimeException("cancel simulado")
            synchronized(canceladas) { canceladas.add(sessao.id) }
        }
    }

    private fun arquivoTemporario(prefixo: String, bytes: ByteArray): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "ort16-$prefixo-${System.nanoTime()}")
        dir.mkdirs()
        val f = File(dir, "$prefixo.bin")
        f.writeBytes(bytes)
        return f
    }

    @Test fun interrupcaoDuranteEsperaDoConversorDevolveSessaoAtiva() {
        val motor = MotorConversor()
        val origem = arquivoTemporario("orig", ByteArray(2048) { it.toByte() })
        val destino = File(origem.parentFile, "saida.wav")
        val saida = java.util.concurrent.atomic.AtomicReference<WhisperOrtMediaConverter.Resultado?>()
        val worker = Thread {
            saida.set(
                WhisperOrtMediaConverter.converterParaWav16kMono(
                    origem, destino,
                    motor = motor,
                    timeoutMs = 30_000L,
                ),
            )
        }.apply { isDaemon = true; start() }
        try {
            assertTrue("motor deveria iniciar", motor.aoIniciar.await(5, TimeUnit.SECONDS))
            // Ainda sem callback: interrompe a espera no meio da posse.
            Thread.sleep(300)
            worker.interrupt()
            worker.join(5_000)
            assertFalse("conversão interrompida não pode travar", worker.isAlive)
            val r = saida.get()
            assertTrue(
                "interrupção da espera deve preservar a posse (SessaoAtiva), sem liberar",
                r is WhisperOrtMediaConverter.Resultado.SessaoAtiva,
            )
            val ativa = r as WhisperOrtMediaConverter.Resultado.SessaoAtiva
            assertEquals(4242L, ativa.sessaoId)
            // O término físico continua observável: callback tardio drena.
            val pedidoCancel = java.util.concurrent.atomic.AtomicBoolean(false)
            val fim = java.util.concurrent.atomic.AtomicReference<WhisperOrtMediaConverter.Final?>()
            val drenando = Thread {
                fim.set(WhisperOrtMediaConverter.drenar(ativa, isCancelada = pedidoCancel::get))
            }.apply { isDaemon = true; start() }
            motor.aoTerminar?.invoke(WhisperOrtMediaConverter.Final(false, true, "tardio"))
            drenando.join(5_000)
            assertFalse(drenando.isAlive)
            assertNotNull(fim.get())
            assertTrue(fim.get()!!.cancelada)
        } finally {
            if (worker.isAlive) { worker.interrupt(); worker.join(2_000) }
            Thread.interrupted()
        }
    }

    @Test fun falhaDoCancelarNoConversorPreservaSessaoAtiva() {
        val motor = MotorConversor().apply { falharCancelar = true }
        val origem = arquivoTemporario("orig2", ByteArray(2048) { it.toByte() })
        val destino = File(origem.parentFile, "saida2.wav")
        // Timeout curto força o caminho de cancel: mesmo lançando, a posse volta.
        val r = WhisperOrtMediaConverter.converterParaWav16kMono(
            origem, destino,
            motor = motor,
            timeoutMs = 500L,
        )
        assertTrue(
            "cancel que lança não pode virar Falhou nem liberar posse",
            r is WhisperOrtMediaConverter.Resultado.SessaoAtiva,
        )
        // Sessão segue drenável até o callback real.
        val ativa = r as WhisperOrtMediaConverter.Resultado.SessaoAtiva
        motor.falharCancelar = false
        val fim = java.util.concurrent.atomic.AtomicReference<WhisperOrtMediaConverter.Final?>()
        val drenando = Thread {
            fim.set(WhisperOrtMediaConverter.drenar(ativa))
        }.apply { isDaemon = true; start() }
        try {
            motor.aoTerminar?.invoke(WhisperOrtMediaConverter.Final(false, true, "tardio"))
            drenando.join(5_000)
            assertFalse(drenando.isAlive)
            assertNotNull(fim.get())
        } finally {
            if (drenando.isAlive) { drenando.interrupt(); drenando.join(2_000) }
        }
    }

    @Test fun falhaDoObservadorNaoAbortaConversaoNemDreno() {
        val motor = MotorConversor()
        val origem = arquivoTemporario("orig3", ByteArray(2048) { it.toByte() })
        val destino = File(origem.parentFile, "saida3.wav")
        val observadorQuebrado: (String) -> Unit = { throw RuntimeException("ui quebrada") }
        val saida = java.util.concurrent.atomic.AtomicReference<WhisperOrtMediaConverter.Resultado?>()
        val worker = Thread {
            saida.set(
                WhisperOrtMediaConverter.converterParaWav16kMono(
                    origem, destino,
                    onProgresso = observadorQuebrado,
                    motor = motor,
                    timeoutMs = 2_000L,
                ),
            )
        }.apply { isDaemon = true; start() }
        try {
            assertTrue(motor.aoIniciar.await(5, TimeUnit.SECONDS))
            // Observador quebrado no início não impede o diagnóstico de timeout.
            worker.join(5_000)
            assertFalse(worker.isAlive)
            assertTrue(
                "falha de observador não pode virar Falhou prematuro",
                saida.get() is WhisperOrtMediaConverter.Resultado.SessaoAtiva,
            )
            val ativa = saida.get() as WhisperOrtMediaConverter.Resultado.SessaoAtiva
            // Dreno com observador quebrado também chega ao término físico.
            val fim = java.util.concurrent.atomic.AtomicReference<WhisperOrtMediaConverter.Final?>()
            val drenando = Thread {
                fim.set(
                    WhisperOrtMediaConverter.drenar(
                        ativa,
                        isCancelada = { false },
                        onProgresso = observadorQuebrado,
                    ),
                )
            }.apply { isDaemon = true; start() }
            motor.aoTerminar?.invoke(WhisperOrtMediaConverter.Final(false, true, "tardio"))
            drenando.join(5_000)
            assertFalse(drenando.isAlive)
            assertNotNull(fim.get())
            assertTrue(fim.get()!!.cancelada)
        } finally {
            if (worker.isAlive) { worker.interrupt(); worker.join(2_000) }
            Thread.interrupted()
        }
    }

    @Test fun drenoSoMarcaCancelEnviadoQuandoChegaAoRecurso() {
        val motor = MotorConversor().apply { falharCancelar = true }
        val terminoFisico = CountDownLatch(1)
        val ativa = WhisperOrtMediaConverter.Resultado.SessaoAtiva(
            "teste", 4242L, File("unused"),
            MotorConversor.Viva(), motor,
        ) { prazo ->
            if (terminoFisico.await(prazo, TimeUnit.MILLISECONDS)) {
                WhisperOrtMediaConverter.Final(false, true, "cancelled")
            } else null
        }
        val pedidoCancel = java.util.concurrent.atomic.AtomicBoolean(true)
        val fim = java.util.concurrent.atomic.AtomicReference<WhisperOrtMediaConverter.Final?>()
        val drenando = Thread {
            fim.set(WhisperOrtMediaConverter.drenar(ativa, isCancelada = pedidoCancel::get))
        }.apply { isDaemon = true; start() }
        try {
            // Primeiro cancel lança: dreno NÃO pode presumir entrega — tenta de novo.
            Thread.sleep(700)
            assertTrue("dreno com cancel não entregue não pode terminar", drenando.isAlive)
            assertNull(fim.get())
            // Segunda tentativa entrega: só então o cancel chega ao motor.
            motor.falharCancelar = false
            motor.aoTerminar?.let { cb ->
                // O dreno vai chamar cancelar de novo; o callback vem de fora.
            }
            // Aguarda a nova tentativa alcançar o motor via polling do estado.
            val limite = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < limite && motor.canceladas.isEmpty()) {
                Thread.sleep(50)
            }
            assertTrue("cancel reenviado deveria chegar ao motor", motor.canceladas.isNotEmpty())
            terminoFisico.countDown()
            // Entrega o término físico pela sessão dona.
            val limite2 = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < limite2 && drenando.isAlive) {
                // O dreno espera no esperarFim; libera via contagem.
                Thread.sleep(50)
            }
            // Força o callback caso o esperarFim ainda esteja preso no latch.
            drenando.join(5_000)
        } finally {
            terminoFisico.countDown()
            drenando.join(5_000)
        }
        assertFalse(drenando.isAlive)
        assertNotNull("dreno deveria terminar no callback", fim.get())
        assertTrue(fim.get()!!.cancelada)
    }
}
