package br.gov.sp.pcsp.launcher

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * R11/D4 — FOREGROUND SERVICE DO JOB ("tela apagada e vida do job").
 *
 * PROBLEMA (antes): o job de transcrição (arquivo ou ao vivo) rodava numa
 * `Thread` solta da Activity, sem serviço nem wake lock. Com a tela apagada o
 * processo vira candidato a `cached`/`/background` e é CONGELADO (app freezer
 * do Android 13 / HyperOS-ColorOS no PJA110): o worker nativo para no meio, o
 * `status` fica em "Carregando…" indefinidamente e, se o processo for morto, a
 * sessão/texto se perde. "Não tratar isso como erro de bancada — é defeito de
 * contrato de produto."
 *
 * DEPOIS: enquanto há job, um foreground service TIPADO mantém o processo
 * perceptível e o CPU ativo:
 *
 *  * **iniciado por ação do usuário** (clique em transcrever/ao vivo), no estado
 *    permitido (Activity em foreground) — `startForegroundService` a partir do
 *    handler do botão; falha ⇒ degradação registrada, o job segue no processo;
 *  * **notificação clara** com status vivo (`UPDATE`) e ação **Cancelar**;
 *  * **dono por job** (`EXTRA_GENERATION`): cancel/stop de geração que não é a
 *    dona é IGNORADO (sem callback stale, sem derrubar a sessão nova);
 *  * **wake lock com TETO** (`PARTIAL_WAKE_LOCK` + `acquire(timeout)`) e
 *    release em TODO terminal (sucesso/cancel/erro/timeout/onDestroy do dono) —
 *    **não existe lock permanente** por construção;
 *  * `START_NOT_STICKY` — **sem startup automático**; `stopSelf()` no terminal.
 *
 * Não usa áudio falso nem playback para "manter o telefone acordado"; o
 * microfone só entra quando o produto já autorizou (`RECORD_AUDIO`).
 */
class WhisperJobService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var foregroundShown = false
    // R55/B01 — lido na thread do worker (roteamento do terminal) e escrito na
    // main (beginJob/stopJob): volátil para o roteamento não decidir por valor
    // obsoleto.
    @Volatile private var owner = 0L
    private var kind = WhisperJobLifecycle.KIND_FILE
    private var lastStatus = ""
    private var lockTimeoutMs = DEFAULT_LOCK_TIMEOUT_MS
    /** R13/D3: concessão vigente do wake lock (prazo + renovações). */
    private var lease: WhisperJobLifecycle.WorkerLease? = null
    /**
     * R15/B1 — houve progresso OBSERVADO desde a última renovação?
     *
     * O `WorkerLease.renew` exige progresso: sem esta marca a renovação era
     * sempre negada e o lock morria no prazo mesmo com o job avançando. Só é
     * ligada por status NOVO (não por re-publicação do mesmo texto).
     */
    @Volatile private var progressSinceLease = false
    /** R15/B2 — quantos drenos limitados ja foram concedidos neste job. */
    private var drainRounds = 0
    /**
     * R16/B3 — o WORKER terminou? (distinto de "cancel foi aceito").
     * Só isto autoriza liberar a proteção e declarar fim confirmado.
     */
    @Volatile private var workerFinished = false
    /**
     * R17/B1 — algum worker chegou a INICIAR trabalho nesta geração?
     *
     * Distingue "nenhum worker iniciou" (substituir o dono é correto) de
     * "worker iniciou e perdeu canal" (substituir seria liberar computação viva).
     * Ligado quando o worker publica status ou quando o canal de cancelamento
     * é registrado; zerado em `beginJob`.
     */
    @Volatile private var workerStarted = false
    /** R17/B1 — contenção ativa: worker sem término confirmado. Bloqueia admissão. */
    @Volatile private var stuck = false
    /** R16/B3 — rodadas de quarentena (proteção sem término demonstrado). */
    private var quarantineRounds = 0
    /**
     * R15/B2 — o job ja entrou em cancelamento/dreno?
     *
     * Depois do pedido de cancel NENHUMA renovacao e legitima: renovar
     * esticaria o dreno e mascararia o prazo de um job que ja devia estar
     * terminando (defeito visto na celula b23-destroy).
     */
    @Volatile private var cancelling = false
    /** R13/D3: resultado honesto da subida do foreground. */
    private var fgsOutcome: WhisperJobLifecycle.FgsStart? = null
    @Volatile private var lastNotifyMs = 0L
    /** R14/B1 — monitor INDEPENDENTE do prazo da concessão. Expirar deixou de
     *  ser só log: no vencimento o serviço pede cancelamento e observa o
     *  reconhecimento do worker (a proteção só cai no terminal). */
    private val monitorHandler = android.os.Handler(android.os.Looper.getMainLooper())
    /**
     * R15/B2 — o timer captura a GERACAO agendada.
     *
     * `onLeaseDeadline` da R14 lia o `owner` ATUAL no instante do disparo: um
     * timer da dona antiga podia agir sobre a nova (mesmo vicio do
     * `onOwnerGoneCheck`). Agora o disparo compara a geracao capturada no
     * agendamento com a geracao viva ANTES de qualquer efeito.
     */
    private var leaseTimerGen = 0L
    private val leaseMonitor = Runnable { onLeaseTimer() }

    private fun onLeaseTimer() {
        val vivo = owner
        if (vivo <= 0L) return
        if (vivo != leaseTimerGen) {
            Log.w(TAG, "lease_timer_dono_trocado agendado=$leaseTimerGen atual=$vivo")
            labEvent("lease_timer_dono_trocado",
                     "agendado" to leaseTimerGen, "atual" to vivo)
            return
        }
        onLeasePreDeadline()
    }
    /** R14/B2 — instante do destroy da dona (prazo de término seguro). */
    private var ownerGoneAt = 0L
    /**
     * R15/B2 — TOKEN DE AGENDAMENTO do "owner gone".
     *
     * Regressão da R14: `onOwnerGoneCheck` lia o `owner` ATUAL no instante do
     * callback — se um job novo tivesse começado, o timer do job VELHO
     * finalizava o dono NOVO (`stopJob(2, OWNER_GONE_TIMEOUT)`).
     *
     * Agora cada agendamento carrega o owner para o qual foi feito; o callback
     * só age se o owner ATUAL ainda for o MESMO, e o token invalida o
     * agendamento quando o job termina ou é substituído.
     */
    private var ownerGoneToken = 0L
    private var ownerGoneScheduledFor = 0L
    private val ownerGoneCheck = Runnable { onOwnerGoneCheck() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        ensureChannel()
    }

    override fun onDestroy() {
        // R13/D3: onDestroy SOLICITA o cancel ao worker (o service não enxerga
        // fence GPU — a espera efetiva é a granularidade de D1, pendente).
        // Só depois libera a proteção; nenhum destroy deixa lock para trás.
        val curOwner = owner
        if (curOwner > 0L) {
            // R14/B2: encaminha para os tokens VIVOS (não zera o dos outros).
            forwardCancel(curOwner)
        }
        releaseWakeLock("onDestroy")
        foregroundShown = false
        owner = 0L
        lease = null
        if (instance === this) instance = null
        super.onDestroy()
    }

    /**
     * R13/D3 — `onTimeout(startId, fgsType)` existe a partir do **API 35
     * (Android 15)** — NÃO é prova genérica de timeout em API 34. Só faz
     * chamadas rápidas (encaminhar cancel + terminal local + `stopSelf`
     * no `stopJob`): nunca bloqueia a main thread esperando compute GPU
     * (o cancel limitado é D1, pendente). Referência oficial exige parar o
     * serviço no curto prazo de tolerância.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "job_fgs_timeout startId=$startId fgsType=$fgsType owner=$owner")
        val curOwner = owner
        if (curOwner > 0L) {
            forwardCancel(curOwner)
            stopJob(curOwner, "TIMEOUT_FGS")
        }
        super.onTimeout(startId, fgsType)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> beginJob(intent)
            // R18/C1 — o dono avisa o início REAL do trabalho, antes do nativo.
            ACTION_WORKER_START -> markWorkerStarted(intent.getLongExtra(EXTRA_GENERATION, 0L))
            ACTION_UPDATE -> updateFromIntent(intent)
            ACTION_CANCEL -> cancelFromIntent(intent)
            // R17/B1 — STOP da notificação é PEDIDO de cancelamento, não
            // terminal. Antes ele chamava stopJob direto e liberava proteção
            // com o worker possivelmente vivo; agora encaminha o cancel e a
            // liberação só acontece no ack do worker.
            ACTION_STOP -> cancelFromIntent(intent)
            else -> Log.w(TAG, "onStartCommand sem action reconhecida; ignorando")
        }
        return START_NOT_STICKY
    }

    // ------------------------------------------------------------------ START
    private fun beginJob(intent: Intent) {
        // R21/C2 — ADMISSÃO ATÔMICA. Uma única operação decide e o resultado
        // dela comanda; não existe mais owner() → decide → adopt com o retorno
        // ignorado (defeito apontado pela revisão da R20).
        val generation = intent.getLongExtra(EXTRA_GENERATION, -1L)
        if (generation <= 0L) {
            // Validação ANTES de qualquer mutação de estado/reserva.
            Log.w(TAG, "job_start_recusado motivo=geracao_ausente")
            stopSelf()
            return
        }
        // R55/B01 — PORTA ÚNICA de admissão (mesma do `startJob` pré-Serviço e
        // do worker da Activity). A decisão da reserva não é reinterpretada
        // aqui: `admission` traduz TODAS as recusas, inclusive a geração que já
        // terminou (antes ela caía no `else` e o serviço assumia um dono morto).
        val reserva = WorkerReservationStore.reserve(generation)
        val porta = WhisperJobLifecycle.admission(reserva)
        if (!porta.start) {
            Log.w(TAG, "job_start_recusado gen=$generation motivo=${porta.reason} " +
                "reserva_dono=${WorkerReservationStore.owner()}")
            labEvent("job_admissao", "gen_novo" to generation, "admitir" to false,
                "motivo" to porta.reason, "reserva_dono" to WorkerReservationStore.owner())
            // R55/B01 — o serviço subiu por startForegroundService e NÃO vai
            // servir ninguém: parar é obrigatório (sem startForeground, o
            // framework mata o processo em ~5 s). Só quando ESTA instância não
            // tem dono; com dono, a recusa é do pedido e o job atual continua.
            if (owner == 0L) stopSelf()
            return
        }
        if (owner != 0L && owner != generation) {
            // R17/B1 — ADMISSÃO: um job novo não pode substituir o dono de um
            // worker que já iniciou e não terminou. Só substitui quando não há
            // worker iniciado (falha antes do trabalho) ou quando o anterior
            // deu ack de término.
            val admissao = JobProtection.admitNew(
                ownerAtivo = true,
                workerStarted = workerStarted || stuck,
                workerFinished = workerFinished
            )
            labEvent(
                "job_admissao",
                "gen_atual" to owner,
                "gen_novo" to generation,
                "admitir" to admissao.admitNew,
                "release" to admissao.release,
                "motivo" to admissao.reason
            )
            if (!admissao.admitNew) {
                Log.w(TAG, "job_start_recusado gen=$generation motivo=${admissao.reason}")
                // R55/B01 — se ESTA chamada criou a reserva e o serviço não vai
                // servir a geração, devolve na hora. Sem isto o dono recém
                // reservado ficaria preso bloqueando a próxima (tombstone).
                if (reserva == WorkerReservation.Decision.RESERVED) {
                    WorkerReservationStore.release(generation, "ADMISSAO_RECUSADA")
                }
                return
            }
            if (admissao.release) releaseWakeLock("job substituído: ${admissao.reason}")
        }
        owner = generation
        workerFinished = false
        workerStarted = false
        stuck = false
        kind = intent.getStringExtra(EXTRA_KIND) ?: WhisperJobLifecycle.KIND_FILE
        lastStatus = intent.getStringExtra(EXTRA_STATUS) ?: WhisperJobLifecycle.initialStatus(kind)
        lockTimeoutMs = WhisperJobLifecycle.clampLockTimeout(
            intent.getLongExtra(EXTRA_TIMEOUT_MS, DEFAULT_LOCK_TIMEOUT_MS),
            MIN_LOCK_TIMEOUT_MS, MAX_LOCK_TIMEOUT_MS
        )
        // R13/D3: subida com resultado honesto. FAILED (microphone sem
        // degradação admitida, ou ambos indisponíveis) registra, encerra o
        // serviço e NÃO adquire lock: o job segue no processo DESPROTEGIDO
        // (estado pré-D4), nunca "como se estivesse protegido".
        fgsOutcome = startForegroundOutcome()
        if (fgsOutcome == WhisperJobLifecycle.FgsStart.FAILED) {
            Log.e(TAG, "job_fgs_falhou gen=$owner kind=$kind (sem proteção; job segue no processo)")
            labEvent("job_fgs_failed", "gen" to owner, "kind" to kind)
            owner = 0L
            stopSelf()
            return
        }
        acquireWakeLock(lockTimeoutMs)
        Log.i(
            TAG,
            "job_start gen=$owner kind=$kind fgs_type=${WhisperJobLifecycle.fgsType(kind)} " +
                "lock_timeout_ms=$lockTimeoutMs fgs_shown=$foregroundShown"
        )
        // R11/D4 — marco no JSONL do lab (no APK normal é no-op sem arquivo): é o
        // artefato que sobrevive a quedas de transporte com a tela apagada.
        labEvent("job_fgs_start", "gen" to owner, "kind" to kind,
            "fgs_type" to WhisperJobLifecycle.fgsType(kind),
            "lock_timeout_ms" to lockTimeoutMs, "fgs_shown" to foregroundShown)
    }

    /**
     * R13/D3 — subida tipada com resultado honesto (ver
     * [WhisperJobLifecycle.startOutcome]): microphone NUNCA degrada para
     * dataSync. API 34+: tipo não declarado/permitido naquele estado pode
     * lançar ForegroundServiceTypeNotAllowedException.
     */
    private fun startForegroundOutcome(): WhisperJobLifecycle.FgsStart {
        val type = WhisperJobLifecycle.fgsType(kind)
        var typedOk = false
        try {
            startForegroundCompat(type)
            foregroundShown = true
            typedOk = true
        } catch (t: Throwable) {
            Log.w(TAG, "startForeground tipo=$type falhou ($t)")
        }
        var degradedOk = false
        // Degradação admitida SÓ na rota de arquivo (processamento local).
        if (!typedOk && kind == WhisperJobLifecycle.KIND_FILE) {
            try {
                startForegroundCompat(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                foregroundShown = true
                degradedOk = true
                Log.i(TAG, "job_fgs_degradado tipo=${ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC}")
            } catch (t: Throwable) {
                Log.e(TAG, "startForeground indisponível: $t")
            }
        }
        return WhisperJobLifecycle.startOutcome(kind, typedOk, degradedOk)
    }

    /**
     * R11/G-fix — formatação compatível com `minSdk 24`.
     *
     * A sobrecarga tipada `Service.startForeground(int, Notification, int)` só
     * existe a partir do **API 29**: chamá-la direto quebra o lint (NewApi) e,
     * em API < 29, lançaria `NoSuchMethodError` com o runtime antigo. O tipo de
     * FGS é intrinsicamente um conceito de API ≥ 29.
     *
     * Em **API ≥ 29 (bancada: Android 13 / API 33)** o caminho é EXATAMENTE o
     * anterior — tipo tipado `dataSync`/`microphone`; em API < 29 usa-se a forma
     * de dois argumentos, onde o tipo é irrelevante por construção. Logo a
     * mudança é **inerte em API ≥ 29** e o contrato provado no D4 permanece.
     */
    private fun startForegroundCompat(type: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, buildNotification(lastStatus), type)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification(lastStatus))
        }
    }

    // ----------------------------------------------------------------- UPDATE
    private fun updateFromIntent(intent: Intent) {
        val generation = intent.getLongExtra(EXTRA_GENERATION, 0L)
        if (!WhisperJobLifecycle.isOwner(owner, generation)) {
            Log.i(TAG, "job_update_ignorado gen=$generation dono=$owner")
            return
        }
        val status = intent.getStringExtra(EXTRA_STATUS) ?: return
        if (status == lastStatus) return
        if (lastStatus != status && !WhisperLabSeams.leaseProgressSuppressed()) {
            progressSinceLease = true
        }
        lastStatus = status
        notifyStatus(force = false)
    }

    private fun notifyStatus(force: Boolean) {
        if (!foregroundShown) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (!force && now - lastNotifyMs < NOTIFY_MIN_INTERVAL_MS) return
        lastNotifyMs = now
        // R11/G-fix — POST_NOTIFICATIONS é runtime permission desde o API 33:
        // checagem EXPLÍCITA (sem @Suppress) para satisfazer o MissingPermission.
        // Abaixo do API 33 a permissão não existe e a publicação é sempre
        // permitida — a checagem fica no caminho 33+ (bancada Android 13/API 33).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "notificação não atualizada: POST_NOTIFICATIONS não concedida")
            return
        }
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(lastStatus))
        } catch (t: Throwable) {
            Log.w(TAG, "notificação não atualizada: $t")
        }
    }

    // ----------------------------------------------------------------- CANCEL
    private fun cancelFromIntent(intent: Intent) {
        val generation = intent.getLongExtra(EXTRA_GENERATION, 0L)
        if (!WhisperJobLifecycle.isOwner(owner, generation)) {
            Log.i(TAG, "job_cancel_ignorado gen=$generation dono=$owner (callback stale)")
            return
        }
        lastStatus = "Cancelando transcrição…"
        notifyStatus(force = true)
        Log.i(TAG, "job_cancel_requested gen=$owner")
        // R14/B2: token por Activity — o destroy de uma não derruba a outra.
        if (!forwardCancel(generation)) {
            // R17/B1 — distinguir os dois casos: sem worker iniciado, liberar é
            // correto; com worker iniciado e canal perdido, é CONTENÇÃO.
            // R22/C2 — reserva ativa conta como trabalho pedido.
            val reservado = WorkerReservationStore.heldBy(generation) || WorkerReservationStore.owner() > 0L
            val d = JobProtection.noForwarder(workerStarted, reservado)
            labEvent("job_cancel_sem_forwarder", "gen" to generation,
                     "worker_started" to workerStarted, "reservado" to reservado,
                     "release" to d.release, "razao" to d.reason)
            if (d.release) {
                stopJob(generation, "CANCEL_SEM_WORKER")
            } else {
                Log.w(TAG, "job_cancel sem forwarder vivo com worker iniciado: contencao")
                enterStuck(generation, d.reason)
            }
            return
        }
    }

    /**
     * R14/B2 — a Activity dona foi destruída. NÃO é terminal: pede o cancel,
     * mantém FGS/wake lock até o worker reconhecer e dá um prazo curto para o
     * término seguro antes de encerrar (nunca deixa FGS órfão).
     */
    fun onOwnerGone(generation: Long) {
        if (!WhisperJobLifecycle.isOwner(owner, generation)) {
            Log.i(TAG, "job_owner_gone_ignorado gen=$generation dono=$owner")
            return
        }
        ownerGoneAt = android.os.SystemClock.elapsedRealtime()
        // R15/B2 — token novo: um agendamento anterior (de outro owner) deixa
        // de valer, e este remember PARA QUE owner.
        ownerGoneToken += 1
        ownerGoneScheduledFor = generation
        labEvent(
            "job_owner_gone", "gen" to generation, "kind" to kind,
            "lock_held" to (wakeLock?.isHeld == true), "token" to ownerGoneToken
        )
        lastStatus = "Encerrando transcrição…"
        notifyStatus(force = true)
        // Pede o cancelamento (worker/binding pode já ter sido invalidado).
        forwardCancel(generation)
        monitorHandler.removeCallbacks(ownerGoneCheck)
        monitorHandler.postDelayed(ownerGoneCheck, OWNER_GONE_GRACE_MS)
        Log.i(TAG, "job_owner_gone gen=$generation aguardando término (${OWNER_GONE_GRACE_MS} ms)")
    }

    /**
     * R15/B2 — Rechecagem do término após o destroy da dona.
     *
     * age APENAS sobre o owner para o qual foi AGENDADO (`ownerGoneScheduledFor`),
     * e só se o owner ATUAL ainda for esse. Se um job novo começou, este timer
     * é um evento do job VELHO: ele NÃO pode finalizar o dono novo.
     */
    private fun onOwnerGoneCheck() {
        val agendado = ownerGoneScheduledFor
        val atual = owner
        if (agendado <= 0L) return
        if (atual != agendado) {
            // O owner mudou (job novo): este timer pertence ao job VELHO e
            // não pode tocar no dono novo. Só o cancelamento do job antigo
            // foi pedido (no onOwnerGone) — nada a encerrar aqui.
            Log.i(TAG, "job_owner_gone_stale agendado=$agendado atual=$atual — timer antigo ignorado")
            labEvent("job_owner_gone_stale", "gen" to agendado, "atual" to atual)
            return
        }
        val elapsed = android.os.SystemClock.elapsedRealtime() - ownerGoneAt
        if (elapsed < OWNER_GONE_GRACE_MS) {
            monitorHandler.postDelayed(ownerGoneCheck, OWNER_GONE_GRACE_MS - elapsed)
            return
        }
        when (WhisperJobLifecycle.endAction(workerFinished)) {
            WhisperJobLifecycle.JobEndAction.RELEASE -> {
                Log.w(TAG, "job_owner_gone_sem_terminal gen=$agendado — worker JA terminou")
                labEvent("job_owner_gone_timeout", "gen" to agendado, "kind" to kind,
                         "worker_ack" to true)
                stopJob(agendado, "OWNER_GONE_TIMEOUT")
            }
            WhisperJobLifecycle.JobEndAction.QUARANTINE -> {
                Log.w(TAG, "job_owner_gone_sem_ack gen=$agendado — QUARENTENA")
                labEvent("job_owner_gone_sem_ack", "gen" to agendado, "kind" to kind,
                         "worker_ack" to false)
                enterQuarantine(agendado, "owner_gone")
            }
        }
    }

    /** Terminal do job: só o dono libera (sucesso/cancel/erro/timeout/destroy). */
    fun stopJob(generation: Long, terminal: String, fromWorker: Boolean = false): Boolean {
        if (!WhisperJobLifecycle.isOwner(owner, generation)) {
            Log.i(TAG, "job_stop_ignorado gen=$generation terminal=$terminal dono=$owner")
            // R55/B01 — `false` = esta instância NÃO responde por esta geração;
            // o chamador (finishJob) aplica o terminal no store, como no caso
            // em que o Serviço nunca existiu. Antes o terminal era descartado.
            return false
        }
        if (fromWorker) {
            // R16/B3 — ACK de término real vindo do worker.
            workerFinished = true
            labEvent("worker_finished", "gen" to generation, "terminal" to terminal)
        }
        // R17/B1 — SÓ término provado libera. Timer, timeout, STOP da
        // notificação e ausência de canal NÃO liberam: entram em contenção.
        if (!JobProtection.canRelease(
                fromWorker, workerFinished, workerStarted,
                reserved = WorkerReservationStore.heldBy(generation))) {
            enterStuck(generation, "terminal_sem_ack:$terminal")
            return true
        }
        // R21/C2 — liberação da reserva SÓ no terminal comprovado, e só pelo dono.
        // R55/B02 — o terminal fica REGISTRADO (lastTerminalGen/lastTerminal):
        // estado, não linha de log.
        WorkerReservationStore.release(generation, terminal)
        Log.i(TAG, "job_terminal gen=$owner kind=$kind terminal=$terminal")
        labEvent("job_fgs_terminal", "gen" to owner, "kind" to kind, "terminal" to terminal,
            "lock_held" to (wakeLock?.isHeld == true))
        releaseWakeLock("terminal=$terminal")
        owner = 0L
        // R15/B2 — o terminal INVALIDA o agendamento do "owner gone": um timer
        // pendente de um job que já terminou não pode agir depois.
        ownerGoneToken += 1
        ownerGoneScheduledFor = 0L
        monitorHandler.removeCallbacks(ownerGoneCheck)
        if (foregroundShown) {
            try {
                stopForeground(Service.STOP_FOREGROUND_REMOVE)
            } catch (t: Throwable) {
                Log.w(TAG, "stopForeground falhou: $t")
            }
            foregroundShown = false
        }
        try {
            NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
        } catch (_: Throwable) {
        }
        stopSelf()
        return true
    }

    // -------------------------------------------------------------- RECURSOS
    private fun acquireWakeLock(timeoutMs: Long) {
        val held = wakeLock
        if (held != null && held.isHeld) return
        try {
            val power = getSystemService(Context.POWER_SERVICE) as PowerManager
            val lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            lock.setReferenceCounted(false)
            // acquire(timeout): teto duro do framework além do release explícito.
            lock.acquire(timeoutMs)
            wakeLock = lock
            lease = WhisperJobLifecycle.WorkerLease(
                android.os.SystemClock.elapsedRealtime(), timeoutMs
            )
            drainRounds = 0   // R15/B2 — job novo, orcamento de dreno novo
            cancelling = false
            workerFinished = false
            quarantineRounds = 0
            Log.i(TAG, "wake_lock_acquired tag=$WAKE_LOCK_TAG timeout_ms=$timeoutMs")
            scheduleLeaseMonitor("acquire")
        } catch (t: Throwable) {
            Log.w(TAG, "wake_lock_indisponivel: $t")
        }
    }

    // ------------------------------------------------- R15/B1 MONITOR DO PRAZO
    /**
     * R15/B1 — agenda a verificação ANTES do prazo físico.
     *
     * Defeito da R14: o monitor era agendado para `prazo + 250 ms`, ou seja
     * DEPOIS de o framework já ter soltado o wake lock (`acquire(timeout)`).
     * Nesse intervalo o job seguia rodando DESPROTEGIDO — com a tela apagada o
     * CPU pode dormir e a transcrição trava sem registro. Aqui a verificação
     * acontece `margem` antes do vencimento, com o lock ainda fisicamente em
     * mãos, e é nesse instante que se decide renovar ou cancelar.
     */
    private fun scheduleLeaseMonitor(reason: String) {
        monitorHandler.removeCallbacks(leaseMonitor)
        val cur = lease ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        val deadline = cur.acquiredElapsedMs + cur.timeoutMs
        // margem pequena o bastante para não comer o orçamento de 75 % da
        // renovação, e grande o bastante para caber o pedido de cancelamento.
        val margin = WhisperJobLifecycle.leaseCheckMarginMs(cur.timeoutMs)
        val wait = (deadline - margin) - now
        if (wait <= 0L) {
            onLeasePreDeadline()
            return
        }
        leaseTimerGen = owner
        monitorHandler.postDelayed(leaseMonitor, wait)
        Log.i(
            TAG,
            "job_lease_monitor reason=$reason wait_ms=$wait margin_ms=$margin " +
                "renewals=${cur.renewals} gen=$leaseTimerGen"
        )
    }

    /**
     * R15/B1 — ponto de decisão ANTES do prazo físico.
     *
     * 1. Se a renovação é devida E houve progresso, renova a concessão e
     *    RE-ADQUIRE o lock físico com o novo prazo (o framework não solta no
     *    meio). 2. Caso contrário, pede o cancelamento AINDA segurando o lock e
     *    toma um lock de DRENO limitado, para o worker terminar de drenar a GPU
     *    protegido. Em nenhum caminho o job fica sem proteção.
     */
    private fun onLeasePreDeadline() {
        val cur = lease ?: return
        val gen = owner
        if (gen <= 0L) return
        val now = android.os.SystemClock.elapsedRealtime()

        if (cur.expired(now)) {
            // Atraso de agendamento: já venceu — não há o que renovar, só
            // cancelar e cobrir o dreno.
            requestCancelAndDrain(cur, gen, now, "expirado")
            return
        }

        if (!cancelling && cur.renewalDue(now)) {
            val novo = cur.renew(now, progressObserved = progressSinceLease)
            if (novo != null) {
                // R16/B3 — TRANSACIONAL
                if (!reacquirePhysicalLock(novo.timeoutMs)) {
                    labEvent("job_lease_renew_falhou", "gen" to gen,
                             "renewals" to cur.renewals, "via" to "monitor")
                    Log.w(TAG, "job_lease_renew_falhou(monitor) gen=$gen — mantendo prazo anterior")
                    requestCancelAndDrain(cur, gen, now, "renew_falhou")
                    return
                }
                lease = novo
                progressSinceLease = false
                labEvent(
                    "job_lease_renewed", "gen" to gen, "kind" to kind,
                    "renewals" to novo.renewals, "timeout_ms" to novo.timeoutMs,
                    "lock_held" to (wakeLock?.isHeld == true)
                )
                Log.i(
                    TAG,
                    "job_lease_renovado gen=$gen renewals=${novo.renewals} " +
                        "timeout_ms=${novo.timeoutMs}"
                )
                scheduleLeaseMonitor("renewed")
                return
            }
            // Sem progresso ou orçamento esgotado: cai para o cancelamento.
            Log.w(
                TAG,
                "job_lease_sem_renovacao gen=$gen renewals=${cur.renewals} " +
                    "progresso=$progressSinceLease"
            )
        }

        requestCancelAndDrain(cur, gen, now, "pre-prazo")
    }

    /**
     * R15/B1 — pede cancelamento com o lock ainda em mãos e cobre o dreno.
     *
     * O `labEvent` registra `lock_held` no INSTANTE do pedido: é a prova de que
     * o cancelamento não foi emitido desprotegido (exigência da ordem).
     */
    private fun requestCancelAndDrain(
        cur: WhisperJobLifecycle.WorkerLease,
        gen: Long,
        now: Long,
        motivo: String,
    ) {
        // R15/B2 — o dreno e LIMITADO: sem orcamento, cada rechecagem renovava
        // o lock de dreno para sempre e o job nunca chegava a terminal (lock
        // infinito disfarcado). Esgotado o orcamento, terminal HONESTO.
        if (drainRounds >= MAX_DRAIN_ROUNDS) {
            Log.w(TAG, "job_lease_drain_esgotado gen=$gen rounds=$drainRounds " +
                       "worker_ack=$workerFinished")
            labEvent("job_lease_drain_esgotado", "gen" to gen, "rounds" to drainRounds,
                     "lock_held" to (wakeLock?.isHeld == true),
                     "worker_ack" to workerFinished)
            when (WhisperJobLifecycle.endAction(workerFinished)) {
                WhisperJobLifecycle.JobEndAction.RELEASE -> stopJob(gen, "TIMEOUT_LEASE")
                WhisperJobLifecycle.JobEndAction.QUARANTINE ->
                    enterQuarantine(gen, "drain_esgotado")
            }
            return
        }
        drainRounds++
        cancelling = true
        labEvent("job_lease_drain_round", "gen" to gen, "round" to drainRounds,
                 "max" to MAX_DRAIN_ROUNDS)
        val heldAtRequest = wakeLock?.isHeld == true
        Log.w(
            TAG,
            "job_lease_deadline gen=$gen motivo=$motivo renewals=${cur.renewals} " +
                "lock_held=$heldAtRequest restante_ms=${cur.remainingMs(now)}"
        )
        labEvent(
            "job_lease_deadline", "gen" to gen, "kind" to kind,
            "motivo" to motivo, "renewals" to cur.renewals,
            "lock_held" to heldAtRequest, "restante_ms" to cur.remainingMs(now)
        )
        lastStatus = "Tempo limite da proteção atingido — cancelando…"
        notifyStatus(force = true)
        val aceito = forwardCancel(gen)
        if (aceito) {
            Log.i(TAG, "cancel_ack gen=$gen forwarders=${cancelForwarderCount}")
        } else {
            Log.w(TAG, "cancel_sem_ack gen=$gen forwarders=${cancelForwarderCount} — terminal local")
        }
        labEvent(
            "cancel_forward_result", "gen" to gen, "aceito" to aceito,
            "forwarders" to cancelForwarderCount,
            "lock_held" to (wakeLock?.isHeld == true)
        )
        if (aceito) {
            // Dreno protegido: lock novo e LIMITADO (o worker pode estar
            // terminando um submit de GPU; sem lock o CPU dorme e o dreno travava).
            reacquirePhysicalLock(LEASE_DRAIN_MS)
            labEvent("job_lease_drain_lock", "gen" to gen, "drain_ms" to LEASE_DRAIN_MS)
            monitorHandler.postDelayed(leaseMonitor, LEASE_RECHECK_MS)
        } else {
            Log.w(TAG, "job_lease_sem_forwarder gen=$gen — terminal local")
            stopJob(gen, "TIMEOUT_LEASE")
        }
    }

    /**
     * R15/B1 — re-adquire o lock físico com prazo novo, sem soltar o anterior.
     * `setReferenceCounted(false)` + `release()` antes evita empilhar locks.
     */
    private fun reacquirePhysicalLock(timeoutMs: Long): Boolean {
        val anterior = wakeLock
        try {
            val power = getSystemService(Context.POWER_SERVICE) as PowerManager
            val lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            lock.setReferenceCounted(false)
            // R15/B2 — ADQUIRE PRIMEIRO: se a aquisicao falhar, o lock anterior
            // permanece valido. A versao anterior soltava antes de adquirir e,
            // numa falha, o job seguia sem protecao nenhuma.
            lock.acquire(timeoutMs)
            wakeLock = lock
            // o prazo fisico volta a valer a partir de agora
            lease = lease?.copy(acquiredElapsedMs = android.os.SystemClock.elapsedRealtime(),
                                timeoutMs = timeoutMs)
            anterior?.let { runCatching { if (it.isHeld) it.release() } }
            return true
        } catch (t: Throwable) {
            // R16/B3 — FALHA: o lock anterior segue valido e a lease NAO pode
            // ter sido publicada. Devolve false para o chamador desfazer.
            Log.w(TAG, "wake_lock_renovacao_indisponivel: $t (mantendo o anterior)")
            labEvent("wake_lock_renew_fail", "error" to t.toString(),
                     "anterior_held" to (anterior?.isHeld == true))
            return false
        }
    }

    /**
     * R16/B3 — QUARENTENA: proteção curta e observável enquanto o worker não
     * dá ack. Não declara fim: bloqueia novo trabalho e registra. Esgotadas as
     * rodadas, encerra com terminal HONESTO `TIMEOUT_LEASE_UNCONFIRMED`.
     */
    private fun enterQuarantine(gen: Long, motivo: String) {
        if (quarantineRounds >= MAX_QUARANTINE_ROUNDS) {
            Log.w(TAG, "job_quarantine_esgotada gen=$gen rounds=$quarantineRounds")
            // R17/B1 — esgotar quarentena NÃO autoriza liberar: sem ack do
            // worker o estado é STUCK (lock e owner mantidos, admissão
            // bloqueada). O terminal só sai quando o worker confirma.
            val d = JobProtection.quarantineExhausted(workerFinished, workerStarted)
            labEvent("job_quarantine_esgotada", "gen" to gen,
                     "rounds" to quarantineRounds, "motivo" to motivo,
                     "release" to d.release, "estado" to d.state.name,
                     "razao" to d.reason)
            if (d.release) {
                stopJob(gen, "TIMEOUT_LEASE_UNCONFIRMED")
            } else {
                enterStuck(gen, d.reason)
            }
            return
        }
        quarantineRounds += 1
        reacquirePhysicalLock(LEASE_DRAIN_MS)
        labEvent("job_quarantine", "gen" to gen, "round" to quarantineRounds,
                 "max" to MAX_QUARANTINE_ROUNDS, "motivo" to motivo)
        monitorHandler.postDelayed(leaseMonitor, LEASE_DRAIN_MS / 2)
    }

    /**
     * R17/B1 — CONTENÇÃO: o worker não confirmou término e não há como provar
     * que parou. Mantém lock, owner e notificação; bloqueia admissão de job
     * novo; registra o estado. Não inventa terminal para destravar a bancada.
     *
     * O teto físico do wake lock (`lockTimeoutMs`, adquirido com `acquire(timeout)`)
     * continua sendo o limite duro do framework — e é registrado, não escondido.
     */
    private fun enterStuck(gen: Long, motivo: String) {
        stuck = true
        val held = wakeLock?.isHeld == true
        labEvent(
            "job_stuck",
            "gen" to gen,
            "motivo" to motivo,
            "lock_held" to held,
            "owner" to owner,
            "admissao_bloqueada" to true
        )
        Log.w(TAG, "job_stuck gen=$gen motivo=$motivo (lock e owner mantidos)")
        if (foregroundShown) {
            lastStatus = "Contenção: worker sem término confirmado"
            notifyStatus(force = true)
        }
    }

    private fun cancelLeaseMonitor(reason: String) {
        monitorHandler.removeCallbacks(leaseMonitor)
        leaseTimerGen = 0L
        Log.i(TAG, "job_lease_monitor_cancel reason=$reason")
    }

    private fun releaseWakeLock(reason: String) {
        cancelLeaseMonitor(reason)
        val lock = wakeLock ?: run {
            lease = null
            return
        }
        wakeLock = null
        lease = null
        try {
            if (lock.isHeld) lock.release()
        } catch (t: Throwable) {
            Log.w(TAG, "wake_lock_release falhou ($reason): $t")
        }
        Log.i(TAG, "wake_lock_released reason=$reason held=${lock.isHeld}")
    }

    // ---------------------------------------------------------- NOTIFICAÇÃO
    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID, "Transcrição em andamento", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Progresso e cancelamento do job de transcrição do Whisper"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(status: String): Notification {
        val openIntent = Intent(this, WhisperActivity::class.java).apply {
            // Reanexa à MESMA instância quando ela ainda está no topo (nada de
            // segunda Activity/segunda sessão ao voltar pela notificação).
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val openFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(this, NOTIFICATION_ID, openIntent, openFlags)

        val cancelIntent = Intent(this, WhisperJobService::class.java).apply {
            action = ACTION_CANCEL
            putExtra(EXTRA_GENERATION, owner)
        }
        val cancel = PendingIntent.getService(
            this, NOTIFICATION_ID + 1, cancelIntent, openFlags
        )

        val icon = if (kind == WhisperJobLifecycle.KIND_LIVE) {
            R.drawable.ic_mic_outline
        } else {
            R.drawable.ic_ffmpeg_select_file
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(icon)
            .setContentTitle(WhisperJobLifecycle.notificationTitle(kind))
            .setContentText(WhisperJobLifecycle.notificationText(status))
            .setStyle(NotificationCompat.BigTextStyle().bigText(WhisperJobLifecycle.notificationText(status)))
            .setContentIntent(open)
            .addAction(R.drawable.ic_ffmpeg_cancel_red, "Cancelar", cancel)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /**
     * R56/B02 — resultado TIPADO da admissão. O booleano antigo misturava duas
     * coisas incompatíveis: "admissão recusada" (a geração NÃO é dona, o job
     * não pode rodar) e "FGS não subiu" (o job segue no processo). Sem essa
     * distinção o chamador prosseguia transcrevendo sem dono e o terminal era
     * recusado depois (`job_terminal_recusado`), deixando a reserva em
     * quarentena.
     */
    enum class StartOutcome { STARTED, ADMISSAO_RECUSADA, SEM_FGS }

    companion object {
        const val TAG = "WhisperJob"

        const val ACTION_START = "br.gov.sp.pcsp.launcher.action.WHISPER_JOB_START"
        // R18/C1 — INÍCIO REAL do worker: enviado pelo dono IMEDIATAMENTE antes
        // do trabalho nativo. Não é derivado de status: status é observação de
        // UI/publicação, não prova de que o worker existe.
        const val ACTION_WORKER_START = "br.gov.sp.pcsp.launcher.action.WHISPER_WORKER_START"
        const val ACTION_UPDATE = "br.gov.sp.pcsp.launcher.action.WHISPER_JOB_UPDATE"
        const val ACTION_CANCEL = "br.gov.sp.pcsp.launcher.action.WHISPER_JOB_CANCEL"
        const val ACTION_STOP = "br.gov.sp.pcsp.launcher.action.WHISPER_JOB_STOP"

        /**
         * R18/C1 — o dono avisa o INÍCIO REAL do worker, imediatamente antes do
         * trabalho nativo. Sem isto o serviço só saberia do worker pelo status,
         * que é observação de publicação — e recusaria/liberaria no momento
         * errado.
         */
        fun markWorkerStarted(ctx: android.content.Context, generation: Long) {
            val i = Intent(ctx, WhisperJobService::class.java).apply {
                action = ACTION_WORKER_START
                putExtra(EXTRA_GENERATION, generation)
            }
            runCatching { ctx.startService(i) }
                .onFailure { Log.w(TAG, "worker_start_envio_falhou: $it") }
        }

        const val EXTRA_GENERATION = "generation"
        const val EXTRA_KIND = "kind"
        const val EXTRA_STATUS = "status"
        const val EXTRA_TERMINAL = "terminal"
        const val EXTRA_TIMEOUT_MS = "timeoutMs"

        const val NOTIFICATION_ID = 0x5715
        const val CHANNEL_ID = "whisper_job"

        /** Tag do wake lock — é o que `dumpsys power` mostra por job. */
        const val WAKE_LOCK_TAG = "WhisperJob:job"

        const val DEFAULT_LOCK_TIMEOUT_MS = 45 * 60 * 1000L
        const val MIN_LOCK_TIMEOUT_MS = 60 * 1000L
        const val MAX_LOCK_TIMEOUT_MS = 6 * 60 * 60 * 1000L
        const val NOTIFY_MIN_INTERVAL_MS = 500L

        /** R14/B1 — intervalo de rechecagem após o vencimento da concessão. */
        /**
         * R15/B1 — rechecagem do dreno. Precisa ser MENOR que [LEASE_DRAIN_MS]:
         * com 30 s de rechecagem e 20 s de lock, o lock venceria 10 s antes de
         * alguem olhar — janela desprotegida silenciosa.
         */
        const val LEASE_RECHECK_MS = 10_000L
        /** R15/B1 — lock LIMITADO que cobre o dreno do worker apos o cancelamento. */
        const val LEASE_DRAIN_MS = 20_000L
        /** R15/B2 — orcamento de drenos (2 x 20 s): depois disso, terminal honesto. */
        const val MAX_DRAIN_ROUNDS = 2
        /** R16/B3 — rodadas de quarentena antes do terminal NAO confirmado. */
        const val MAX_QUARANTINE_ROUNDS = 2

        /** R14/B2 — prazo de término seguro após o destroy da Activity dona. */
        const val OWNER_GONE_GRACE_MS = 20_000L

        /** Instância viva (mesmo processo). `null` = nenhum job com FGS rodando. */
        @Volatile private var instance: WhisperJobService? = null

        /** Ponte do "Cancelar" da notificação para a sessão da Activity. Sem ela o
         * botão só registra o pedido; com ela o cancel chega ao job (dono atual).
         * R14/B2: registro por TOKEN — duas Activities convivem e o destroy de
         * uma NÃO invalida o callback da outra. */
        private val cancelForwarders =
            java.util.concurrent.CopyOnWriteArraySet<(Long) -> Boolean>()

        fun registerCancelForwarder(f: (Long) -> Boolean) {
            cancelForwarders.add(f)
        }

        /** Remove só o próprio token (idempotente). */
        fun unregisterCancelForwarder(f: (Long) -> Boolean) {
            cancelForwarders.remove(f)
        }

        /** Encaminha para TODOS os tokens vivos; cada sessão ignora geração alheia. */
        /**
         * R15/B2 — pede o cancelamento e devolve se ALGUÉM ACEITOU.
         *
         * Defeito da R14: `runCatching { f(owner) }.isSuccess` contava como
         * aceite o simples fato de a lambda NÃO lançar exceção — mas o
         * forwarder era `(Long) -> Unit` e podia ser um no-op (sessão já
         * terminou, geração desconhecida, Activity destruída). O serviço então
         * ficava esperando um terminal que nunca vinha, segurando o dreno.
         * Agora o forwarder devolve ACK explícito e "sem exceção" não basta.
         */
        fun forwardCancel(owner: Long): Boolean {
            var aceito = false
            for (f in cancelForwarders) {
                val ok = runCatching { f(owner) }.getOrDefault(false)
                if (ok) aceito = true
            }
            // R15/B2 — a política aqui é PURA (sem Log): o chamador registra o
            // resultado no ledger (`cancel_forward_result`) e nos logs. Manter
            // o companion sem dependência de android.util.Log também deixa a
            // regra testável na JVM.
            lastForwardAck = aceito
            return aceito
        }

        /** R14/B2 — chamada pela Activity no destroy (a instância viva trata). */
        fun ownerGone(generation: Long) {
            instance?.onOwnerGone(generation)
        }

        /** Liga/desliga o espelho de teste (bancada: habilita o FGS pelo lab). */
        /** R15/B2 — resultado do último encaminhamento de cancelamento. */
        @Volatile var lastForwardAck: Boolean? = null

        /** R15/B2 — quantos forwarders estavam registrados no último pedido. */
        val cancelForwarderCount: Int get() = cancelForwarders.size

        @Volatile var forceEnabled = true

        fun isRunning(): Boolean = instance?.foregroundShown == true

        fun owner(): Long = instance?.owner ?: 0L

        /**
         * R56/B02 — regra ÚNICA consumida pelos três pontos de admissão da
         * atividade: recusa de admissão não deixa prosseguir (a geração não é
         * dona); falta de FGS deixa (o job segue no processo, como antes).
         */
        fun permiteProsseguir(outcome: StartOutcome): Boolean =
            outcome != StartOutcome.ADMISSAO_RECUSADA

        /**
         * Inicia o job observável. DEVE ser chamado a partir da ação do usuário
         * (estado permitido). Falha ⇒ `false` com degradação registrada (o job
         * segue no processo, como antes do D4 — sem inventar sucesso).
         */
        fun startJob(
            context: Context,
            generation: Long,
            kind: String,
            status: String = WhisperJobLifecycle.initialStatus(kind),
            timeoutMs: Long = DEFAULT_LOCK_TIMEOUT_MS
        ): Boolean = startJobOutcome(context, generation, kind, status, timeoutMs) !=
            StartOutcome.ADMISSAO_RECUSADA

        fun startJobOutcome(
            context: Context,
            generation: Long,
            kind: String,
            status: String = WhisperJobLifecycle.initialStatus(kind),
            timeoutMs: Long = DEFAULT_LOCK_TIMEOUT_MS
        ): StartOutcome {
            if (!forceEnabled) return StartOutcome.SEM_FGS
            if (generation <= 0L) {
                Log.w(TAG, "startJob recusado: geração inválida ($generation)")
                return StartOutcome.ADMISSAO_RECUSADA
            }
            // R55/B01 — RESERVA PRÉ-SERVIÇO: a reserva passa a existir antes de
            // o Serviço ser criado (startForegroundService é assíncrono). Antes
            // dela, a rota ao vivo não reservava em lugar nenhum e a rota de
            // arquivo só reservava já dentro do worker. Recusa ⇒ NÃO sobe FGS.
            val porta = WhisperJobLifecycle.admission(WorkerReservationStore.reserve(generation))
            if (!porta.start) {
                Log.w(TAG, "startJob recusado gen=$generation motivo=${porta.reason} " +
                    "reserva_dono=${WorkerReservationStore.owner()}")
                WhisperLabSeams.event(
                    "job_admissao",
                    WhisperJobLifecycle.terminalEventJson(
                        generation, "ADMISSAO_RECUSADA",
                        WorkerReservationStore.owner(), false, "pre_servico:" + porta.reason
                    )
                )
                return StartOutcome.ADMISSAO_RECUSADA
            }
            val intent = Intent(context, WhisperJobService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_GENERATION, generation)
                putExtra(EXTRA_KIND, kind)
                putExtra(EXTRA_STATUS, status)
                putExtra(EXTRA_TIMEOUT_MS, timeoutMs)
            }
            return try {
                ContextCompat.startForegroundService(context, intent)
                StartOutcome.STARTED
            } catch (t: Throwable) {
                Log.w(TAG, "job_fgs_nao_iniciado gen=$generation: $t (job segue no processo)")
                StartOutcome.SEM_FGS
            }
        }

        /** Espelha o status vivo do job na notificação (dono conferido). */
        fun updateStatus(context: Context, generation: Long, status: String) {
            if (!forceEnabled) return
            val svc = instance ?: return
            svc.applyStatus(generation, status)
        }

        /** Terminal do job: libera o lock e a notificação (dono conferido).
         * Chamado no sucesso/cancel/erro/timeout/onDestroy do dono. */
        fun finishJob(generation: Long, terminal: String) {
            val kind = WhisperJobLifecycle.terminalKind(terminal)
            val svc = instance
            // R55/B01 — o Serviço só responde pelo que ELE possui. Antes,
            // `instance != null` bastava: com `owner == 0` (FGS falhou, job já
            // substituído ou onDestroy em curso) o terminal era DESCARTADO pelo
            // isOwner do stopJob e a reserva ficava presa para sempre.
            if (svc != null) {
                // R16/B3 — caminho do TÉRMINO REAL: único que autoriza liberar
                // proteção (o worker ackou). Nome desconhecido não vira ack.
                val tratou = svc.stopJob(
                    generation, terminal,
                    fromWorker = kind != WhisperJobLifecycle.TerminalKind.UNKNOWN
                )
                if (tratou) return
            }
            // R44/E02 + R55/B01 — serviço nunca existiu OU existe sem ser o dono
            // desta geração (destroy/cancel/FGS falho antes do onCreate). A
            // decisão é a MESMA do caminho com Serviço; a liberação continua
            // sendo só do dono (release confere a geração).
            val resultado = WhisperJobLifecycle.applyTerminalWithoutService(
                generation, terminal,
                { gen -> WorkerReservationStore.heldBy(gen) },
                { gen, term -> WorkerReservationStore.release(gen, term) },
            )
            Log.i(TAG, "job_terminal_sem_servico gen=$generation terminal=$terminal " +
                "kind=${resultado.kind.name} liberou=${resultado.released} " +
                "registrou=${resultado.recorded} reservado=${WorkerReservationStore.heldBy(generation)}")
            // R55/B03 — ESTADO sem UI e sem Serviço pelo MESMO sink dos eventos
            // do serviço (no APK normal é no-op); o log acima não substitui o
            // estado. Terminal não provado publica `job_terminal_recusado`,
            // nunca `job_fgs_terminal`.
            emitTerminal(resultado.payloadJson, event = resultado.event)
        }

        /** R55/B03 — sink Service-free (no APK normal, no-op). */
        private fun emitTerminal(payloadJson: String, event: String = "job_fgs_terminal") {
            WhisperLabSeams.event(event, payloadJson)
        }

    }

    /**
     * R18/C1 — INÍCIO REAL do worker (ação explícita do dono).
     * Idempotente por geração; geração que não é a dona é ignorada e registrada.
     * Só esta função marca `workerStarted`.
     */
    private fun markWorkerStarted(generation: Long) {
        if (!WhisperJobLifecycle.isOwner(owner, generation)) {
            Log.i(TAG, "worker_start_ignorado gen=$generation dono=$owner (stale)")
            labEvent("worker_start_ignorado", "gen" to generation, "dono" to owner)
            return
        }
        if (workerStarted) return
        workerStarted = true
        // R18/C2 — física, não lógica: isHeld é o estado REAL do lock.
        labEvent(
            "worker_start_real",
            "gen" to generation,
            "lock_held" to (wakeLock?.isHeld == true),
            "stuck" to stuck
        )
    }

    private fun applyStatus(generation: Long, status: String) {
        if (!WhisperJobLifecycle.isOwner(owner, generation)) return
        // R18/C1 — `workerStarted` NÃO é mais marcado aqui. Status é observação
        // de publicação; a prova de worker vivo vem de ACTION_WORKER_START,
        // enviada pelo dono imediatamente antes do trabalho nativo.
        if (status == lastStatus) return
        lastStatus = status
        // R15/B1 — status NOVO = progresso observado (o early-return acima
        // garante que repetição do mesmo texto não conta como progresso).
        // O ensaio pode SUPRIMIR a marca (injeção de falha) para provar o braço
        // de cancelamento; produção nunca suprime.
        if (!WhisperLabSeams.leaseProgressSuppressed()) progressSinceLease = true
        // R13/D3: status novo = ponto de renovação limitada da concessão
        // (ver WorkerLease; máx 2, nunca após expirada).
        val cur = lease
        if (cur != null && cancelling) {
            // R15/B2 — status durante cancelamento/dreno NAO renova: o prazo do
            // job ja foi decidido; renovar aqui só prolongaria a espera.
            labEvent("job_lease_renew_ignorada", "gen" to owner, "motivo" to "cancelling")
        } else if (cur != null) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (cur.expired(now)) {
                Log.e(TAG, "job_lease_expirado gen=$owner (monitor aciona o cancelamento)")
                labEvent("job_lease_expired", "gen" to owner, "kind" to kind)
                // R15/B1: defesa em camadas — o caminho de status também pede
                // o cancelamento, cobrindo o dreno com lock limitado.
                requestCancelAndDrain(cur, owner, now, "status-expirado")
            } else {
                // R15/B2 — mesma politica pura usada no monitor: sem
                // progresso real (ou cancelando) nao renova.
                val podeRenovar = WhisperJobLifecycle.shouldRenew(
                    cancelling, cur, now, progressSinceLease
                )
                if (!podeRenovar && cur.renewalDue(now)) {
                    labEvent("job_lease_renew_negada", "gen" to owner,
                             "progresso" to progressSinceLease)
                }
                cur.renew(now, progressObserved = progressSinceLease)?.let { renewed ->
                    if (renewed.acquiredElapsedMs != cur.acquiredElapsedMs) {
                        // R16/B3 — TRANSACIONAL: adquire primeiro, publica depois.
                        if (reacquirePhysicalLock(renewed.timeoutMs)) {
                            lease = renewed
                        } else {
                            // mantem `cur` (prazo REAL anterior) e registra
                            labEvent("job_lease_renew_falhou",
                                     "gen" to owner, "renewals" to cur.renewals)
                            Log.w(TAG, "job_lease_renew_falhou gen=$owner — mantendo prazo anterior")
                        }
                        labEvent(
                            "job_lease_renewed", "gen" to owner, "kind" to kind,
                            "renewals" to renewed.renewals, "via" to "status"
                        )
                        scheduleLeaseMonitor("status-renew")
                    }
                }
            }
        }
        notifyStatus(force = false)
    }


    /** R11/D4 — marco no JSONL do LAB (em produção `isLab()` é `false` e nada é
     * construído: custo de produção = uma checagem booleana). É o artefato que
     * sobrevive à queda do transporte com a tela apagada. */
    private fun labEvent(name: String, vararg kv: Pair<String, Any?>) {
        if (!WhisperLabSeams.isLab()) return
        val sb = StringBuilder("{")
        kv.forEachIndexed { index, (k, v) ->
            if (index > 0) sb.append(',')
            sb.append('"').append(k).append("\":")
            when (v) {
                null -> sb.append("null")
                is Number, is Boolean -> sb.append(v.toString())
                else -> sb.append('"').append(v.toString().replace("\"", "\\\"")).append('"')
            }
        }
        sb.append('}')
        WhisperLabSeams.event(name, sb.toString())
    }
}
