package br.gov.sp.pcsp.launcher

import java.security.MessageDigest

/**
 * R13/F1 — TELEMETRIA ADITIVA do replay F1.
 *
 * Lacunas objetivas do log de janela do device (`f1-replay-20261019`): o log
 * não gravava (a) a lista de SEGMENTOS com tempos que o JNI devolveu à janela,
 * (b) o sha256 do PCM entregue, (c) o `word_times` (para não confundir "não
 * gravado" com "não usado") nem (d) identidade do reparo (`region_id`,
 * `attempt_id`). Sem isso o replay não consegue rodar o ramo `segmentos:k` nem
 * casar pedido/resposta de reparo.
 *
 * ## Contrato (por que é ADITIVO)
 * - Estas funções só FORMATAM campo de log. Não leem estado do pipeline, não
 *   decidem, não ordenam eventos, não tocam em áudio, texto publicado nem em
 *   qualquer API existente. Quem chama concatena o resultado ao payload que já
 *   existia, e SÓ no flavor de laboratório (`WhisperLabSeams.isLab()`).
 * - Dado ausente = campo OMITIDO (nenhuma chave nula inventada); o corpo do
 *   evento continua JSON válido, com ou sem o campo.
 * - `voiced_duration` NÃO está aqui de propósito: o pipeline não carrega o
 *   número de amostras de fala do intervalo VAD até o `deliver`
 *   (`PlannedWindow`/`Chunk` guardam só pcm/faixa única), e contar fala exigiria
 *   mudar o laço de decisão do segmentador. Inventar o valor a partir do
 *   comprimento do intervalo seria chamar de "fala" o que é só geometria.
 *
 * Consumidor: `scripts/` do replay — os campos entram no JSONL do lab, que é
 * artefato privado do flavor.
 */
object LiveTelemetry {

    /** sha256 (hex minúsculo) do PCM s16le ENTREGUE (contexto + único). */
    fun pcmSha256(pcm: ByteArray?): String? {
        if (pcm == null || pcm.isEmpty()) return null
        val digest = MessageDigest.getInstance("SHA-256").digest(pcm)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            if (v < 16) sb.append('0')
            sb.append(Integer.toHexString(v))
        }
        return sb.toString()
    }

    /** `"pcm_sha256":"…"`; "" (omitido) quando não há PCM. */
    fun pcmField(pcm: ByteArray?): String {
        val sha = pcmSha256(pcm) ?: return ""
        return "\"pcm_sha256\":\"$sha\""
    }

    /** Escape JSON mínimo — mesma regra de `jsonEscape` da Activity (o texto
     * vira UMA linha: quebra de linha não pode partir o registro do log). */
    private fun esc(v: String): String =
        v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ")

    /** `"segments":[{"text":…,"t0Ms":…,"t1Ms":…},…]` — exatamente a lista
     * passada a `LiveTextCommitter.onWindow(segments = …)`. "" quando vazia
     * (chave omitida: "não houve segmento" ≠ "não foi registrado"). */
    fun segmentsField(segments: List<LiveSegmentTime>?): String {
        if (segments.isNullOrEmpty()) return ""
        val sb = StringBuilder("\"segments\":[")
        for ((i, s) in segments.withIndex()) {
            if (i > 0) sb.append(',')
            sb.append("{\"text\":\"").append(esc(s.text))
                .append("\",\"t0Ms\":").append(s.t0Ms)
                .append(",\"t1Ms\":").append(s.t1Ms).append('}')
        }
        return sb.append(']').toString()
    }

    /** `"word_times":null` quando o app NÃO passou tempos por palavra (hoje é
     * sempre null: `onWindow` é chamado sem `wordTimes`); array quando houver.
     * Gravar o null explícito elimina a dúvida "não usado" × "não gravado". */
    fun wordTimesField(wordTimes: List<LiveTextCommitter.WordTime>?): String {
        if (wordTimes == null) return "\"word_times\":null"
        val sb = StringBuilder("\"word_times\":[")
        for ((i, w) in wordTimes.withIndex()) {
            if (i > 0) sb.append(',')
            sb.append("{\"word\":\"").append(esc(w.word))
                .append("\",\"t0Ms\":").append(w.t0Ms)
                .append(",\"t1Ms\":").append(w.t1Ms).append('}')
        }
        return sb.append(']').toString()
    }

    /** Identidade do pedido/resposta de reparo. `region_id` é o MESMO id que o
     * committer usa (`RepairRequest.regionId`); `attempt_id` é alocado pelo
     * [AttemptIds] do job (monotônico), não é entrada de decisão. */
    fun repairFields(regionId: Long, attemptId: Long): String =
        "\"region_id\":$regionId,\"attempt_id\":$attemptId"

    /** Junta os campos não vazios; nenhum campo = string vazia (o chamador não
     * acrescenta vírgula). */
    fun join(vararg fields: String): String =
        fields.filter { it.isNotEmpty() }.joinToString(",")

    /**
     * Sequência de `attempt_id` POR JOB (reset no início do job, incremento a
     * cada pedido de reparo). Só identifica a tentativa no log: não limita
     * tentativas, não muda a política de reparo e não é lida pelo committer.
     */
    class AttemptIds {
        private var last = 0L

        @Synchronized
        fun next(): Long = ++last

        @Synchronized
        fun reset() {
            last = 0L
        }
    }
}
