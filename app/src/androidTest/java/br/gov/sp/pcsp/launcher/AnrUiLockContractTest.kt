package br.gov.sp.pcsp.launcher

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * VACINA/F2 — contrato de NAO-BLOQUEIO dos getters de UI do HyMt2Native.
 *
 * Cenario real do ANR corrigido: enquanto load/geracao seguram o lock interno
 * (g_mutex), a thread principal chama threadCount()/lastError()/etc. Estes
 * getters devem responder SEM esperar pelo lock.
 *
 * ROBUSTEZ (F2):
 *  - PRONTIDAO por SINAL REAL: espera `sigTestHoldState()==1` (setado APOS a
 *    aquisicao do lock). O sleep cego nao garantia o lock adquirido e podia
 *    medir "sem lock" (falso positivo) — aqui a prontidao FALHA se nao medir.
 *  - Cleanup: hold com limite (<=5s na lib); join com assert do worker.
 *  - A regressao conhecida (getters voltando a g_mutex) bloqueia ~2s e
 *    QUEBRA o assert de <500ms.
 *
 * Os hooks existem SOMENTE na variante de teste da lib (SIG_ENABLE_TEST_HOOKS).
 * Com a lib de produto, o teste e PULADO explicitamente (nunca "verde" falso).
 *
 * Rodar no device (com a lib de teste instalada):
 *   ./gradlew :app:connectedDebugAndroidTest   (ou `am instrument` manual)
 */
@RunWith(AndroidJUnit4::class)
class AnrUiLockContractTest {

    @Before
    fun exigeVarianteDeTeste() {
        val disponivel = try {
            HyMt2Native.sigTestHoldState()
            true
        } catch (e: UnsatisfiedLinkError) {
            false
        }
        assumeTrue(
            "lib sem hooks de teste (variante de produto) — rode com a lib de teste " +
                "(build -DSIG_ENABLE_TEST_HOOKS=ON)",
            disponivel
        )
    }

    @Test
    fun uiGettersNaoBloqueiamComLockPreso() {
        // worker segura o lock principal por 2000ms (simula load/geracao)
        val worker = Thread { HyMt2Native.sigTestHoldGmutex(2000) }
        worker.start()

        // PRONTIDAO: espera o SINAL REAL pos-aquisicao (nao sleep cego).
        // Timeout de prontidao = teste INVALIDO (falha, nunca mede sem lock).
        var segurando = false
        val deadline = System.nanoTime() + 3_000_000_000L
        while (System.nanoTime() < deadline) {
            if (HyMt2Native.sigTestHoldState() == 1) {
                segurando = true
                break
            }
            Thread.sleep(5)
        }
        assertTrue(
            "prontidao falhou: o lock de inferencia nao foi adquirido em 3s — " +
                "o teste NAO pode medir os getters (evita falso positivo)",
            segurando
        )

        val t0 = System.nanoTime()
        val threads = HyMt2Native.threadCount()
        val err = HyMt2Native.lastError()
        val desc = HyMt2Native.backendDescription()
        val stats = HyMt2Native.lastStats()
        val ms = (System.nanoTime() - t0) / 1_000_000

        worker.join(6000)
        assertFalse("worker ainda preso apos o hold (cleanup falhou)", worker.isAlive)

        assertTrue(
            "getters de UI bloquearam ${ms}ms com o lock preso (contrato: non-block; " +
                "limite 500ms). threads=$threads err=${err.length}ch " +
                "desc=${desc.length}ch stats=${stats.length}ch",
            ms < 500
        )
    }

    @Test
    fun holdEhObservavelDuranteTodaAJanela() {
        // Trabalhador "atrasado"/curto: o estado DEVE ser observado como 1
        // durante o hold — garante que o sinal e' real (e nao um hook inerte,
        // que deixaria o teste principal medir sem lock silenciosamente).
        val worker = Thread { HyMt2Native.sigTestHoldGmutex(600) }
        worker.start()

        var viuSegurando = false
        val deadline = System.nanoTime() + 2_000_000_000L
        while (System.nanoTime() < deadline) {
            if (HyMt2Native.sigTestHoldState() == 1) {
                viuSegurando = true
                break
            }
            Thread.sleep(1)
        }
        worker.join(4000)
        assertFalse("cleanup falhou no hold curto", worker.isAlive)
        assertTrue("estado de hold nunca observado (hook inerte?)", viuSegurando)
        // apos o hold, o estado volta a 0
        assertTrue("estado nao voltou a 0 apos o hold", HyMt2Native.sigTestHoldState() == 0)
    }
}

