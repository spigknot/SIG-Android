package br.gov.sp.pcsp.launcher

/** Posse única do download no processo, independente da Activity e com publicação cancelável. */
object WhisperOrtDownloadOwner {
    /** Tentativa privada; cancelamento e commit têm um único ponto de decisão. */
    class Attempt internal constructor(val id: String) {
        @Volatile private var cancelled = false
        @Synchronized fun cancel() { cancelled = true }
        fun isCancelled(): Boolean = cancelled
        @Synchronized fun <T> publish(action: () -> T): T? = if (cancelled) null else action()
    }
    private var current: Attempt? = null
    @Synchronized fun begin(): Attempt? {
        if (current != null) return null
        return Attempt(java.util.UUID.randomUUID().toString()).also { current = it }
    }
    @Synchronized fun finish(attempt: Attempt) {
        if (current === attempt) current = null
    }
}
