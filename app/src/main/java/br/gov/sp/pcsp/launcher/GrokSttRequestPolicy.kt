package br.gov.sp.pcsp.launcher

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.max
import kotlin.random.Random

/** Política pura do Grok STT em Ferramentas → Transcrição.
 * Pacing compartilhado de 8 RPS, cooldown de 429, cinco tentativas, backoff
 * com jitter e Retry-After. HTTP, UI e credenciais pertencem à Activity.
 * Referências: docs.x.ai/developers/rate-limits e guia oficial Speech to Text.
 */
object GrokSttRequestPolicy {
    const val REQUESTS_PER_SECOND = 8
    const val MAX_ATTEMPTS = 5
    const val HELP_TEXT = "O Grok STT permite até 10 requisições por segundo (10 RPS) no tier considerado pelo SIG.\n\n" +
        "Para manter uma margem, o SIG inicia até 8 requisições por segundo, inclusive reenvios. " +
        "Por isso, pode haver um atraso no envio de muitos arquivos curtos.\n\n" +
        "Se a xAI limitar a taxa ou a capacidade (por exemplo, por outras instâncias usando a mesma cota), " +
        "o SIG aguarda e tenta novamente, até 5 tentativas. O botão Cancelar também interrompe essa espera."

    val sharedPacer = Pacer()

    fun showWarning(transcriptionMode: Boolean, isGrok: Boolean) = transcriptionMode && isGrok

    /** O lock cobre apenas o envio dos headers, nunca o corpo/resposta HTTP. */
    class Pacer(
        private val now: () -> Long = { System.nanoTime() / 1_000_000 },
        private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    ) {
        private val lock = ReentrantLock()
        private var nextStart = Long.MIN_VALUE
        private var blockedUntil = Long.MIN_VALUE

        fun begin(checkCancelled: () -> Unit) {
            while (true) {
                checkCancelled()
                if (!lock.tryLock(100, TimeUnit.MILLISECONDS)) continue
                var keepLock = false
                val delay: Long
                try {
                    checkCancelled()
                    val deadline = max(nextStart, blockedUntil)
                    delay = if (deadline == Long.MIN_VALUE) 0 else deadline - now()
                    if (delay <= 0) {
                        keepLock = true
                        return
                    }
                } finally {
                    if (!keepLock) lock.unlock()
                }
                sleep(minOf(delay, 100))
            }
        }

        fun end() {
            if (!lock.isHeldByCurrentThread) return
            try {
                nextStart = now() + 1000 / REQUESTS_PER_SECOND
            } finally {
                lock.unlock()
            }
        }

        fun defer(milliseconds: Long) {
            lock.lock()
            try {
                blockedUntil = max(blockedUntil, now() + milliseconds)
            } finally {
                lock.unlock()
            }
        }
    }

    data class Reply<T>(val status: Int, val value: T, val retryAfter: String? = null)

    class Metrics(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
        private val starts = ArrayDeque<Long>()
        private var peak = 0
        private var responses = 0
        private var successes = 0
        private var rateLimited = 0

        @Synchronized fun started() {
            val current = now()
            starts.addLast(current)
            while (starts.isNotEmpty() && current - starts.first >= 1000) starts.removeFirst()
            peak = max(peak, starts.size)
        }

        @Synchronized fun response(status: Int) {
            responses++
            if (status in 200..299) successes++
            if (status == 429) rateLimited++
        }

        @Synchronized fun summary(): String {
            val successRate = if (responses == 0) 0 else successes * 100 / responses
            val limitedRate = if (responses == 0) 0 else rateLimited * 100 / responses
            return "Grok STT: $responses respostas HTTP; sucesso $successes ($successRate%); " +
                "429 $rateLimited ($limitedRate%); pico de início $peak RPS (limite do SIG: $REQUESTS_PER_SECOND)."
        }
    }

    fun retryDelayMillis(
        attempt: Int,
        retryAfter: String?,
        wallClockMillis: Long = System.currentTimeMillis(),
        jitterMillis: Long = Random.nextLong(0, 501),
    ): Long {
        val seconds = retryAfter?.trim()?.toDoubleOrNull()
        val headerDelay = if (seconds != null && seconds.isFinite() && seconds >= 0) {
            (seconds * 1000).toLong()
        } else {
            val date = try {
                SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("GMT")
                    isLenient = false
                }.parse(retryAfter.orEmpty())?.time
            } catch (_: java.text.ParseException) { null }
            date?.minus(wallClockMillis)?.coerceAtLeast(0) ?: 0
        }
        return max((1000L shl attempt) + jitterMillis, headerDelay)
    }

    fun <T> execute(
        checkCancelled: () -> Unit,
        request: () -> Reply<T>,
        metrics: Metrics,
        pacer: Pacer = sharedPacer,
        onRetry: (String) -> Unit = {},
        onResponse: (String) -> Unit = {},
        sleep: (Long) -> Unit = { Thread.sleep(it) },
        jitterMillis: () -> Long = { Random.nextLong(0, 501) },
    ): Reply<T> {
        repeat(MAX_ATTEMPTS) { attempt ->
            checkCancelled()
            val reply = request()
            metrics.response(reply.status)
            onResponse(metrics.summary())
            if (reply.status !in setOf(429, 500, 502, 503, 504)) return reply
            val delay = retryDelayMillis(attempt, reply.retryAfter, jitterMillis = jitterMillis())
            if (reply.status == 429) pacer.defer(delay)
            if (attempt == MAX_ATTEMPTS - 1) return reply
            onRetry("Grok STT: HTTP ${reply.status}; nova tentativa ${attempt + 2}/$MAX_ATTEMPTS em " +
                String.format(Locale.US, "%.1fs.", delay / 1000.0))
            var remaining = delay
            while (remaining > 0) {
                checkCancelled()
                val slice = minOf(remaining, 100)
                sleep(slice)
                remaining -= slice
            }
        }
        error("Grok STT: limite de tentativas esgotado.")
    }
}
