package br.gov.sp.pcsp.launcher

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicLong

/**
 * R16/C1 — CAPTURA REAL DO COMMITTER (append-only, versionada).
 *
 * Grava o que o produtor REAL entregou, na FRONTEIRA da chamada, antes de
 * qualquer mutação de estado. Desligada no NORMAL: só liga com caminho
 * explícito (laboratório/DIAG).
 *
 * Schema v1 — uma linha JSON por evento:
 *   {"v":1,"seq":N,"ev":"window_in","gen":G,"idx":I,"t0":..,"t1":..,
 *    "ctx":..,"texto":"..","wordTimes":[...]|null,"segments":[...]|null,
 *    "pcmBytes":N,"pcmSha256":".."}
 *   {"v":1,"seq":N,"ev":"window_out","gen":G,"idx":I,"win":..,"added":..,
 *    "provisional":..,"held":..,"alignment":".."}
 *   {"v":1,"seq":N,"ev":"repair","gen":G,"idx":I,"t1":..,"t2":..,"ok":bool,
 *    "motivo":".."}
 *   {"v":1,"seq":N,"ev":"pcm_balance","lido":..,"unico":..,"ctx":..,
 *    "enfileirado":..,"consumido":..,"reparado":..,"pendente":..,"descartado":..,"motivo":".."}
 *   {"v":1,"seq":N,"ev":"final","textoComprometido":"..","integral":bool, ...}
 */
object CommitCapture {

    @Volatile private var out: OutputStreamWriter? = null
    @Volatile private var caminho: String? = null
    private val seq = AtomicLong(0L)
    /** R17/C1 — identidade da sessão de captura (vai em TODA linha). */
    @Volatile private var captureId: String = ""
    /** R17/C1 — uma falha de escrita INVALIDA a captura (nunca "completa"). */
    @Volatile private var valida: Boolean = true
    @Volatile private var escritas: Long = 0L

    val ativa: Boolean get() = out != null
    fun caminhoAtual(): String? = caminho
    fun idAtual(): String = captureId
    fun validaAgora(): Boolean = valida
    fun escritasAgora(): Long = escritas

    @Synchronized
    fun ligar(path: String): Boolean {
        if (out != null) return true
        val f = File(path)
        f.parentFile?.mkdirs()
        out = OutputStreamWriter(FileOutputStream(f, true), StandardCharsets.UTF_8)
        caminho = path
        seq.set(0L)
        valida = true
        escritas = 0L
        captureId = "cap-" + System.currentTimeMillis().toString(36) + "-" +
            (1000 + (Math.random() * 9000).toInt())
        write(mapOf("ev" to "capture_start", "schema" to 2,
                    "capture_id" to captureId))
        return true
    }

    /**
     * R17/C1 — fecha a sessão de forma EXPLÍCITA.
     *
     * Grava `final` (snapshot terminal) e `capture_end` com as contagens. É o
     * que permite ao leitor distinguir "captura encerrada" de "arquivo que
     * acabou no meio de uma linha" — o truncamento limpo que a R16 aprovava.
     */
    @Synchronized
    fun fechar(motivo: String, terminal: String = "", texto: String = "") {
        if (out == null) return
        write(mapOf(
            "ev" to "final",
            "capture_id" to captureId,
            "motivo" to motivo,
            "terminal" to terminal,
            "textoComprometido" to texto,
            "escritas" to escritas
        ))
        write(mapOf(
            "ev" to "capture_end",
            "capture_id" to captureId,
            "motivo" to motivo,
            "seq_final" to seq.get(),
            "escritas" to escritas,
            "valida" to valida
        ))
        runCatching { out?.flush() }
        runCatching { out?.close() }
        out = null
        caminho = null
    }

    @Synchronized
    fun desligar() {
        fechar("desligar")
    }

    /** Liga por ambiente (laboratório/DIAG). NORMAL nunca define a variável. */
    fun ligarPorAmbiente(): Boolean {
        if (ativa) return true
        val p = System.getenv("SIG_WHISPER_CAPTURE_PATH") ?: return false
        if (p.isBlank()) return false
        return runCatching { ligar(p) }.getOrDefault(false)
    }

    @Synchronized
    fun write(campos: Map<String, Any?>) {
        val w = out ?: return
        val o = JSONObject()
        o.put("v", 2)
        o.put("seq", seq.incrementAndGet())
        // R17/C1 — a identidade da sessão vai em TODA linha: permite recusar
        // arquivo com sessões misturadas/sobrepostas.
        if (captureId.isNotEmpty() && !campos.containsKey("capture_id")) {
            o.put("capture_id", captureId)
        }
        for ((k, v) in campos) o.put(k, if (v == null) JSONObject.NULL else v)
        val ok = runCatching {
            w.write(o.toString())
            w.write("\n")
            w.flush()
        }.isSuccess
        if (ok) {
            escritas += 1
        } else {
            // R17/C1 — falha de escrita INVALIDA a captura em vez de ser
            // engolida como se estivesse completa.
            valida = false
        }
    }

    /** SHA-256 dos bytes do PCM da janela (identidade do que foi consumido). */
    fun sha256(b: ByteArray): String {
        val d = java.security.MessageDigest.getInstance("SHA-256")
        d.update(b)
        return d.digest().joinToString("") { "%02x".format(it) }
    }

    /** Serializa wordTimes preservando null como AUSENTE (não lista vazia). */
    fun wordTimesJson(ts: List<LiveTextCommitter.WordTime>?): Any? {
        if (ts == null) return null
        val a = JSONArray()
        for (t in ts) a.put(JSONObject().put("t0Ms", t.t0Ms).put("t1Ms", t.t1Ms).put("word", t.word))
        return a
    }

    fun segmentsJson(ss: List<LiveSegmentTime>?): Any? {
        if (ss == null) return null
        val a = JSONArray()
        for (s in ss) a.put(JSONObject().put("t0Ms", s.t0Ms).put("t1Ms", s.t1Ms).put("text", s.text))
        return a
    }
}
