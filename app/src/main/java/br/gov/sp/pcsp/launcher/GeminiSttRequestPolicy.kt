package br.gov.sp.pcsp.launcher

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.random.Random

/** Cota pura do Gemini Transcribe REST: 8/min, teto local 100/dia, cooldown e
 * dados opcionais da resposta HTTP. Persistência e HTTP são injetados.
 * A contagem local por chave não representa o saldo global do projeto Google.
 */
class GeminiSttRequestPolicy(
    private val state: State = State(),
    private val save: (State) -> Unit = {},
    private val now: () -> Long = { System.currentTimeMillis() },
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) {
    data class State(
        var day: String = "", var used: Int = 0, var dailyBlocked: Boolean = false,
        var next: Long = 0, var blocked: Long = 0, var responses: Int = 0,
        var errors: Int = 0, var limited: Int = 0, var observed: Long = 0,
        var status: Int = 0, var starts: List<Long> = emptyList(),
        var headers: Map<String, String> = emptyMap(),
    )

    data class Reply<T>(
        val status: Int, val value: T, val headers: Map<String, String> = emptyMap(),
        val dailyExhausted: Boolean = false, val retryInfoMillis: Long = 0,
    )

    private val lock = ReentrantLock()

    private fun resetDay() {
        val day = pacificDay(now())
        if (state.day != day) {
            state.day = day; state.used = 0; state.dailyBlocked = false
            state.responses = 0; state.errors = 0; state.limited = 0
        }
    }

    /** Mantém o lock somente até o fim dos headers. Corpo e respostas são paralelos. */
    fun begin(checkCancelled: () -> Unit) {
        while (true) {
            checkCancelled()
            if (!lock.tryLock(100, TimeUnit.MILLISECONDS)) continue
            try {
                checkCancelled()
                resetDay()
                check(!state.dailyBlocked && state.used < DAILY_LIMIT) {
                    "Gemini: limite diário de 100 requisições atingido. Retome após a meia-noite do Pacífico."
                }
                val delay = maxOf(state.next, state.blocked) - now()
                if (delay <= 0) {
                    state.used++
                    state.starts = state.starts.filter { now() - it < 60_000 } + now()
                    state.next = now() + PACE_MILLIS
                    save(state)
                    return
                }
                lock.unlock()
                sleep(minOf(delay, 100))
            } catch (error: Throwable) {
                if (lock.isHeldByCurrentThread) lock.unlock()
                throw error
            }
        }
    }

    fun end() {
        if (!lock.isHeldByCurrentThread) return
        try { state.next = maxOf(state.next, now() + PACE_MILLIS); save(state) }
        finally { lock.unlock() }
    }

    fun checkAvailable() = lock.withLock {
        resetDay()
        check(!state.dailyBlocked && state.used < DAILY_LIMIT) {
            "Gemini: limite diário de 100 requisições atingido. Retome após a meia-noite do Pacífico."
        }
    }

    fun <T> observe(reply: Reply<T>) = lock.withLock {
        resetDay()
        state.responses++
        if (reply.status !in 200..299) state.errors++
        if (reply.status == 429) state.limited++
        state.status = reply.status
        state.observed = now()
        state.dailyBlocked = state.dailyBlocked || reply.dailyExhausted
        state.headers = reply.headers.mapKeys { it.key.lowercase() }
            .filterKeys { it in QUOTA_HEADERS }.mapValues { it.value.take(120) }
        save(state)
    }

    private fun defer(delay: Long) = lock.withLock {
        state.blocked = maxOf(state.blocked, now() + delay)
        save(state)
    }

    fun <T> execute(
        checkCancelled: () -> Unit, request: () -> Reply<T>,
        onRetry: (String) -> Unit = {}, jitter: () -> Long = { Random.nextLong(0, 501) },
    ): Reply<T> {
        repeat(5) { attempt ->
            checkCancelled()
            val reply = request()
            observe(reply)
            if (reply.dailyExhausted || reply.status !in setOf(429, 500, 502, 503, 504)) return reply
            val headers = reply.headers.mapKeys { it.key.lowercase() }
            val delay = maxOf(reply.retryInfoMillis, GrokSttRequestPolicy.retryDelayMillis(
                attempt, headers["retry-after"], now(), jitter(),
            ))
            if (reply.status == 429) defer(delay)
            if (attempt == 4) return reply
            onRetry("Gemini STT: HTTP ${reply.status}; nova tentativa ${attempt + 2}/5 após ${delay / 1000.0}s e o pacing de 8/min.")
            var remaining = delay
            while (remaining > 0) {
                checkCancelled()
                val slice = minOf(remaining, 100)
                sleep(slice); remaining -= slice
            }
        }
        error("Gemini: limite de tentativas esgotado.")
    }

    fun statistics(): String = lock.withLock {
        resetDay()
        val minute = state.starts.count { now() - it < 60_000 }
        val wait = (maxOf(state.next, state.blocked) - now()).coerceAtLeast(0) / 1000.0
        buildString {
            append("Uso local: $minute/10 no último minuto; ${state.used}/100 hoje (Pacífico).\n")
            append("Saldo diário local: ${(DAILY_LIMIT - state.used).coerceAtLeast(0)}.\n")
            if (state.dailyBlocked || state.used >= DAILY_LIMIT) append("Novos envios bloqueados até a meia-noite do Pacífico.\n")
            else append("Próxima permissão local em ${wait}s.\n")
            append("Respostas HTTP hoje: ${state.responses}; erros: ${state.errors}; 429: ${state.limited}.\n")
            if (state.dailyBlocked) append("A API informou esgotamento da cota diária; novos envios bloqueados.\n")
            if (state.headers.isEmpty()) append("Saldo global de requisições/tokens: não informado pela API.")
            else {
                append("Dados da última resposta da API (janela não especificada):\n")
                append(state.headers.entries.joinToString("\n") { "${HEADER_LABELS[it.key]}: ${it.value}" })
            }
            if (state.observed > 0) {
                append("\nÚltima resposta: " + SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault()).format(Date(state.observed)))
            }
        }
    }

    companion object {
        const val DAILY_LIMIT = 100
        const val PACE_MILLIS = 7_500L
        val QUOTA_HEADERS = setOf("x-ratelimit-remaining-requests", "x-ratelimit-limit-requests",
            "x-ratelimit-remaining-tokens", "x-ratelimit-reset")
        private val HEADER_LABELS = mapOf("x-ratelimit-remaining-requests" to "Requisições restantes",
            "x-ratelimit-limit-requests" to "Limite da janela", "x-ratelimit-remaining-tokens" to "Tokens restantes",
            "x-ratelimit-reset" to "Reinício informado")
        const val HELP_TEXT = "Gemini 3.5 Transcribe: limite considerado de 10 requisições por minuto e 100 por dia. " +
            "O SIG inicia até 8 por minuto, inclusive retentativas. Por isso, vários arquivos podem demorar.\n\n" +
            "A cota diária reinicia à meia-noite do horário do Pacífico. Ao esgotá-la, o SIG interrompe novos envios. " +
            "Cancelar interrompe a espera.\n\n" +
            "A cota Google é compartilhada pelo projeto. A contagem local não inclui outros aparelhos, aplicativos " +
            "ou chaves do mesmo projeto. O saldo global só aparece quando informado pela API.\n\n"
        fun pacificDay(millis: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("America/Los_Angeles") }.format(Date(millis))
        fun showWarning(transcriptionMode: Boolean, isGemini: Boolean) = transcriptionMode && isGemini
    }
}
