package br.gov.sp.pcsp.launcher

/**
 * R55/B04 — IDENTIDADE DO ESCRITOR do snapshot de estado (`state.json`).
 *
 * Defeito de laboratório observado na R54: o app rodava no PID 26390 e o
 * `state.json` em disco era de OUTRA instância (`pid:26849`, `active:false`,
 * `last_event:drive_finish`). O avaliador classificou como
 * `UNKNOWN_PID_DIVERGENTE` e o bloqueio só saiu com **force-stop** — um efeito
 * colateral no aparelho para um arquivo que estava simplesmente VELHO (o
 * escritor anterior já tinha morrido).
 *
 * A identidade do escritor é `(boot_id, pid, start_ticks)`: o `pid` sozinho não
 * identifica processo nenhum, porque o Android RECICLA números. `start_ticks` é
 * o campo 22 de `/proc/<pid>/stat` (mesma base do kernel). Com os três, o
 * consumidor distingue "arquivo de um processo morto" (STALE_WRITER) de "outra
 * instância realmente viva" (OTHER_LIVE_WRITER) sem adivinhar e sem matar nada.
 *
 * Classe PURA (sem Android, sem I/O): o produtor passa o texto de
 * `/proc/self/stat` e a existência do PID anterior; o teste cobre o caso R54.
 */
object LabSnapshotIdentity {

    /** Identidade de um escritor de snapshot. */
    data class Writer(
        val bootId: String,
        val pid: Int,
        val startTicks: Long,
        val seq: Long = 0L,
        val active: Boolean = false,
        val lastEvent: String = "",
    ) {
        /** Mesmo PROCESSO (mesmo pid E mesmo start_ticks). */
        fun sameProcess(other: Writer): Boolean =
            pid == other.pid && startTicks == other.startTicks && startTicks > 0L
    }

    /** Veredito sobre o snapshot encontrado em disco. */
    enum class Verdict {
        /** Não havia snapshot: nada a reconciliar. */
        NO_SNAPSHOT,

        /** O snapshot é deste mesmo processo. */
        SAME_WRITER,

        /** Escritor MORTO (ou pid reciclado): o arquivo é velho, não é instância viva. */
        STALE_WRITER,

        /** Boot diferente: o arquivo é de antes do reboot — escritor morto por definição. */
        PREVIOUS_BOOT,

        /** Outra instância VIVA escreveu: não se sobrescreve nem se mata nada. */
        OTHER_LIVE_WRITER,

        /** Sem `boot_id` confiável dos dois lados: não se conclui nada. */
        UNKNOWN_BOOT,
    }

    /**
     * Classifica o snapshot anterior contra o escritor atual.
     *
     * @param previousPidAlive o PID anterior existe AGORA (`/proc/<pid>`)?
     *   Só é consultado quando o boot é o mesmo.
     */
    fun classify(current: Writer, previous: Writer?, previousPidAlive: Boolean): Verdict {
        if (previous == null) return Verdict.NO_SNAPSHOT
        if (current.bootId.isBlank() || previous.bootId.isBlank()) return Verdict.UNKNOWN_BOOT
        if (current.bootId != previous.bootId) return Verdict.PREVIOUS_BOOT
        if (previous.sameProcess(current)) return Verdict.SAME_WRITER
        // Mesmo número de pid com start_ticks diferente = número RECICLADO.
        if (previous.pid == current.pid) return Verdict.STALE_WRITER
        return if (previousPidAlive) Verdict.OTHER_LIVE_WRITER else Verdict.STALE_WRITER
    }

    /**
     * O veredito BLOQUEIA o preflight? Só outra instância comprovadamente viva.
     *
     * Sem isto, arquivo velho (STALE_WRITER) ou de outro boot (PREVIOUS_BOOT)
     * viravam bloqueio — e o caminho de saída era force-stop, não reconciliação.
     */
    fun blocksPreflight(verdict: Verdict): Boolean = verdict == Verdict.OTHER_LIVE_WRITER

    /**
     * O processo atual pode publicar por cima preservando a evidência? Sempre
     * pode — ele É o escritor corrente; a evidência do anterior viaja nos campos
     * `handover_from_*`. O que muda é o veredito publicado.
     */
    fun supersedes(verdict: Verdict): Boolean = verdict != Verdict.OTHER_LIVE_WRITER

    /**
     * Campo 22 (`starttime`, em ticks desde o boot) de `/proc/<pid>/stat`.
     *
     * O nome do processo (campo 2) vem entre parênteses e pode conter espaço e
     * `)`: por isso o parse começa no ÚLTIMO `)`, nunca num split ingênuo.
     * Devolve `null` quando a linha não tem os campos esperados.
     */
    fun parseStartTicks(statLine: String): Long? {
        val close = statLine.lastIndexOf(')')
        if (close < 0 || close + 2 >= statLine.length) return null
        val rest = statLine.substring(close + 1).trim()
        if (rest.isEmpty()) return null
        val fields = rest.split(' ')
        // fields[0] = campo 3 (state). starttime (campo 22) = índice 19.
        val idx = 19
        if (fields.size <= idx) return null
        return fields[idx].toLongOrNull()?.takeIf { it > 0L }
    }
}
