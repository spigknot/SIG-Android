package br.gov.sp.pcsp.launcher

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Utilitarios compartilhados pelos tres fluxos de transcricao
 * (RemoteSttActivity, GraniteActivity e WhisperActivity).
 *
 * Tudo aqui e logica pura de formatacao/arquivo: nao toca em UI, rede nem
 * Android. Se um destes helpers precisar de contexto de tela, ele NAO deve
 * entrar neste arquivo.
 */
object TranscriptionReport {

    /** Linha do relatorio HTML: nome do arquivo e texto transcrito. */
    data class Row(val fileName: String, val text: String)

    fun escapeHtml(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }

    /** Relatorio HTML com uma linha por arquivo transcrito. */
    fun buildHtml(rows: List<Row>): String {
        val rowsHtml = rows.joinToString("\n") { row ->
            "<tr><td>${escapeHtml(row.fileName)}</td><td>${escapeHtml(row.text).replace("\n", "<br>")}</td></tr>"
        }
        return """
            <!doctype html>
            <html lang="pt-BR">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>Transcrições</title>
              <style>
                body { font-family: sans-serif; margin: 24px; color: #111; }
                table { border-collapse: collapse; width: 100%; }
                th, td { border: 1px solid #bbb; padding: 8px; vertical-align: top; }
                th { background: #eee; text-align: left; }
              </style>
            </head>
            <body>
              <h1>Transcrições</h1>
              <table>
                <thead><tr><th>Arquivo</th><th>Transcrição</th></tr></thead>
                <tbody>
                $rowsHtml
                </tbody>
              </table>
            </body>
            </html>
        """.trimIndent()
    }

    /** Linha de log com carimbo HH:mm:ss, sincronizada no builder. */
    fun appendLog(builder: StringBuilder, line: String) {
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        synchronized(builder) { builder.append("[$stamp] ").append(line).append('\n') }
    }

    /** Cabecalho de um arquivo no painel de transcricao ao vivo. */
    fun appendTranscriptionHeader(builder: StringBuilder, fileName: String) {
        synchronized(builder) {
            if (builder.isNotEmpty() && !builder.endsWith("\n")) builder.append('\n')
            builder.append(fileName).append("\n\n")
        }
    }

    /** Separador entre arquivos no painel de transcricao ao vivo. */
    fun appendTranscriptionSeparator(builder: StringBuilder) {
        synchronized(builder) {
            if (!builder.endsWith("\n")) builder.append('\n')
            builder.append("-------------------------------\n")
        }
    }

    /** Nome-base seguro para arquivo de saida (sem extensao, sem caracteres invalidos). */
    fun safeBaseName(name: String): String {
        return name.substringBeforeLast('.', name).ifBlank { "transcricao" }
            .replace(Regex("""[\\/:*?"<>|]"""), "_")
    }

    /** Primeiro nome de arquivo livre em [outputDir], sufixando _2, _3, ... */
    fun uniqueFile(outputDir: File, outputName: String): File {
        val base = outputName.substringBeforeLast('.', outputName)
        val extension = outputName.substringAfterLast('.', "")
        var candidate = File(outputDir, outputName)
        var suffix = 2
        while (candidate.exists()) {
            candidate = File(outputDir, "${base}_$suffix.$extension")
            suffix++
        }
        return candidate
    }

    /** Tamanho legivel em b/kb/mb/gb (uma casa decimal, ponto). */
    fun formatMediaSize(bytes: Long): String {
        val units = arrayOf("b", "kb", "mb", "gb")
        var value = bytes.coerceAtLeast(0L).toDouble()
        var unit = 0
        while (value >= 1024.0 && unit < units.lastIndex) {
            value /= 1024.0
            unit++
        }
        return String.format(Locale.US, "%.1f %s", value, units[unit])
    }

    /** Bitrate em kbps: "9.5k" abaixo de 10, "42k" acima. */
    fun formatKbps(value: Double): String {
        return if (value < 10.0) {
            String.format(Locale.US, "%.1fk", value)
        } else {
            "${value.toInt()}k"
        }
    }

    /** Extrai a duracao de um log do FFmpeg ("Duration: HH:MM:SS.mmm"). */
    fun parseDurationSeconds(logs: String): Double? {
        val match = Regex("""Duration:\s*(\d+):(\d+):(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE).find(logs)
            ?: return null
        val hours = match.groupValues.getOrNull(1)?.toDoubleOrNull() ?: return null
        val minutes = match.groupValues.getOrNull(2)?.toDoubleOrNull() ?: return null
        val seconds = match.groupValues.getOrNull(3)?.toDoubleOrNull() ?: return null
        return hours * 3600.0 + minutes * 60.0 + seconds
    }
}
