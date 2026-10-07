package br.gov.sp.pcsp.launcher

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * R6/VACINA (ANR): contrato de NAO-BLOQUEIO dos getters de UI do HyMt2Native.
 *
 * Cenario real do ANR corrigido: enquanto load/geracao seguram o lock interno
 * (g_mutex), a thread principal chama threadCount()/lastError()/... Estes
 * getters devem responder SEM esperar pelo lock. Se alguem regredir e voltar
 * a usar g_mutex nos getters (mutacao conhecida do patch), este teste fica
 * bloqueado ~2s e FALHA — a regressao nao passa.
 *
 * Rodar no device: ./gradlew :app:connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class AnrUiLockContractTest {

    @Test
    fun uiGettersNaoBloqueiamComLockPreso() {
        // worker segura o lock principal por 2000ms (simula load/geracao longos)
        val worker = Thread { HyMt2Native.sigTestHoldGmutex(2000) }
        worker.start()
        Thread.sleep(200) // garante que o lock ja esta preso

        val t0 = System.nanoTime()
        val threads = HyMt2Native.threadCount()
        val err = HyMt2Native.lastError()
        val desc = HyMt2Native.backendDescription()
        val stats = HyMt2Native.lastStats()
        val ms = (System.nanoTime() - t0) / 1_000_000

        worker.join(5000)

        assertTrue(
            "getters de UI bloquearam ${ms}ms com o lock preso (contrato: non-block; " +
                "limite 500ms). threads=$threads err=${err.length}ch desc=${desc.length}ch stats=${stats.length}ch",
            ms < 500
        )
    }
}
