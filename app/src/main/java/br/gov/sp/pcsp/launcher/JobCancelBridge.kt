package br.gov.sp.pcsp.launcher

import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * R15/B3 — PONTE DE CANCELAMENTO INDEPENDENTE DA ACTIVITY.
 *
 * Defeito da R14: o forwarder de cancelamento era um campo da `WhisperActivity`
 * e era DESREGISTRADO no `onDestroy`. Se a dona morresse com o worker ainda
 * drenando a GPU, o serviço ficava sem ninguém para aceitar o cancelamento —
 * `forwardCancel` devolvia falso e o job era dado como terminal ENQUANTO o
 * nativo ainda trabalhava, soltando o wake lock no meio do dreno.
 *
 * A sessão (`WhisperSessions.shared`) é de PROCESSO e sobrevive à Activity, mas
 * o forwarder não: ele capturava a Activity (UI + `cancelRequested`) e por isso
 * tinha de ser descartado junto com ela.
 *
 * Esta ponte separa as duas coisas:
 *  - o forwarder é UM só, estável e de processo, registrado uma única vez;
 *  - ele pede o cancelamento à SESSÃO (que é quem de fato conhece o job);
 *  - a UI é avisada por `WeakReference` — se não houver Activity viva, o
 *    cancelamento continua valendo e nada vaza.
 *
 * Assim o desregistro deixa de ser necessário e o worker nunca fica órfão.
 */
object JobCancelBridge {

    /** Quem recebe o aviso de UI (a Activity viva). Abstrato para testar sem Android. */
    fun interface UiSink {
        fun onJobCancelForwarded(generation: Long)
    }

    private val activityRef = AtomicReference<WeakReference<UiSink>?>(null)
    private val registrado = AtomicBoolean(false)

    /** Identidade estável usada no registro (nunca recriada). */
    val forwarder: (Long) -> Boolean = { generation ->
        // A sessão é de processo: o ACK continua válido mesmo sem Activity.
        val aceito = WhisperSessions.shared.cancel(generation)
        activityRef.get()?.get()?.onJobCancelForwarded(generation)
        aceito
    }

    /** A Activity viva passa a receber o aviso de UI (registro é idempotente). */
    fun attach(activity: UiSink) {
        activityRef.set(WeakReference(activity))
        if (registrado.compareAndSet(false, true)) {
            WhisperJobService.registerCancelForwarder(forwarder)
        }
    }

    /**
     * Solta a UI — mas NÃO desregistra o forwarder.
     *
     * É exatamente esta a correção: o registro é de processo e precisa
     * sobreviver à dona para o cancelamento continuar aceito enquanto o worker
     * existir. Só a referência de UI (fraca) é limpa, e só se ainda for a mesma
     * Activity (destroy atrasado de A não apaga a referência de B).
     */
    fun detach(activity: UiSink) {
        val atual = activityRef.get()?.get()
        if (atual == null || atual === activity) {
            activityRef.compareAndSet(activityRef.get(), null)
        }
    }

    /** Só para teste: volta ao estado inicial. */
    fun resetForTest() {
        activityRef.set(null)
        if (registrado.compareAndSet(true, false)) {
            WhisperJobService.unregisterCancelForwarder(forwarder)
        }
    }
}
