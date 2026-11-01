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
        /** [generation] é a GERAÇÃO LÓGICA da sessão (traduzida do token do
         * serviço); [aceito] = o cancelamento foi aceito pelo controller.
         * Aviso SEM ACK (token alheio/ORT/escapado) não chega à UI. */
        fun onJobCancelForwarded(generation: Long, aceito: Boolean)
    }

    private val activityRef = AtomicReference<WeakReference<UiSink>?>(null)
    private val registrado = AtomicBoolean(false)

    /**
     * Fonte de verdade do cancelamento: o singleton do processo. MUTÁVEL
     * apenas para TESTE (injetar fake de recusa/ACK); `resetForTest` restaura
     * o default — produção nunca substitui este sink.
     */
    @Volatile
    var sessionSink: (Long) -> Boolean = { gen -> WhisperSessions.shared.cancel(gen) }

    /** Identidade estável usada no registro (nunca recriada). */
    val forwarder: (Long) -> Boolean = encaminhar@{ generation ->
        // Adendo 18 §2 — o serviço é dono do TOKEN do alocador comum; a sessão
        // do controller é indexada pela GERAÇÃO LÓGICA. Resolve o par pela
        // ponte (token⇄lógico) antes de pedir o cancel: o cancelamento alcança
        // a sessão nativa certa mesmo quando o token difere da geração. Token
        // sem PAR não autoriza inferir uma geração lógica pelo mesmo número.
        // A rota ORT tem forwarder próprio; tokens alheios/expirados são no-op.
        val alvo = ReservationJobIdentity.logicoDe(generation) ?: return@encaminhar false
        // A sessão é de processo: o ACK continua válido mesmo sem Activity.
        val aceito = sessionSink(alvo)
        // ADENDO 19 §1.2 — a UI é avisada com a IDENTIDADE DA SESSÃO (lógica,
        // nunca o token do serviço) e APENAS quando o cancelamento foi ACEITO;
        // encaminhamento alheio/atrasado/sem ACK (rota ORT, token de A
        // expirado) NÃO alcança o UiSink — a flag/status do job corrente
        // (Activity) nunca é movida destes avisos.
        if (aceito) activityRef.get()?.get()?.onJobCancelForwarded(alvo, aceito)
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
        sessionSink = { gen -> WhisperSessions.shared.cancel(gen) }
        if (registrado.compareAndSet(true, false)) {
            WhisperJobService.unregisterCancelForwarder(forwarder)
        }
    }
}
