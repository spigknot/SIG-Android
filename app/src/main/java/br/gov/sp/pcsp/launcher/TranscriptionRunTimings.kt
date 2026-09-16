package br.gov.sp.pcsp.launcher

import java.util.Locale

/**
 * Tempos de uma rodada de transcrição por arquivos, em milissegundos.
 *
 * O relatório antigo tinha um número só ("Tempo no servidor") que media a
 * JANELA de rede — do início da 1ª requisição até a última resposta lida, com
 * o upload dos arquivos dentro. Este tipo separa o que é o quê:
 *
 *  - [totalMs]: do clique no botão verde até o fim (preparação + envio +
 *    processamento + resposta);
 *  - [firstRequestMs]: do clique até a 1ª requisição sair (a preparação do
 *    primeiro arquivo — os demais são preparados em paralelo, dentro da janela);
 *  - [preparationMs]: soma da preparação (copiar/converter/VAD) dos arquivos;
 *  - [uploadMs]: MEDIDO do início da 1ª requisição até o último byte do último
 *    arquivo ser entregue ao socket (é o custo de rede do envio, e não uma
 *    subtração);
 *  - [serverWindowMs]: 1ª requisição -> última resposta lida (upload + espera +
 *    resposta + processamento);
 *  - [serverProcessingMs]: o que o SERVIDOR informa ter levado com os arquivos
 *    (`total_processing_time_seconds` / `processing_time_seconds`). É a medida
 *    dele — pode ultrapassar a janela do cliente quando há fila/paralelismo no
 *    servidor (medido: outro cliente com um lote de 200 arquivos dividindo a
 *    mesma GPU), por isso NÃO é subtraída da janela para "estimar" rede.
 *
 * Puro (sem Android, UI, rede ou estado) para ser verificável por testes
 * unitários — TranscriptionRunTimingsTest.
 */
data class TranscriptionRunTimings(
    val totalMs: Long,
    val firstRequestMs: Long?,
    val preparationMs: Long,
    val preparedFiles: Int,
    val uploadMs: Long?,
    val serverWindowMs: Long,
    val serverProcessingMs: Long?,
    val serverProcessingFiles: Int,
    val serverAudioMs: Long?
) {
    fun generalEfficiency(audioSeconds: Double): Double = ratio(audioSeconds, totalMs)

    fun windowEfficiency(audioSeconds: Double): Double = ratio(audioSeconds, serverWindowMs)

    /** Eficiência pelo tempo que o servidor reportou; null quando ele não informou. */
    fun serverEfficiency(audioSeconds: Double): Double? =
        serverProcessingMs?.let { ratio(audioSeconds, it) }

    /** Linhas da conta aberta (tempos + eficiências) para o relatório. */
    fun reportLines(audioSeconds: Double): List<String> {
        val lines = mutableListOf<String>()
        lines += "Tempo total (clique -> fim): ${seconds(totalMs)}s"

        lines += buildString {
            append("- preparação local (copiar/converter/VAD): ${seconds(preparationMs)}s")
            if (preparedFiles > 0) append(" somando $preparedFiles arquivo(s)")
            firstRequestMs?.let { append("; 1º arquivo pronto em ${seconds(it)}s") }
        }
        uploadMs?.let { lines += "- envio pela rede (upload dos arquivos): ${seconds(it)}s" }
        lines += "- janela de envio + servidor (1º envio -> última resposta): ${seconds(serverWindowMs)}s"
        val processing = serverProcessingMs
        if (processing == null) {
            lines += "- processamento no servidor: não informado pela resposta"
        } else {
            lines += "- processamento no servidor (reportado por ele): ${seconds(processing)}s" +
                if (serverProcessingFiles > 0) " ($serverProcessingFiles arquivo(s))" else ""
        }

        lines += "Eficiência geral (clique -> fim): ${efficiency(generalEfficiency(audioSeconds))}"
        serverEfficiency(audioSeconds)?.let {
            lines += "Eficiência no servidor (tempo reportado por ele): ${efficiency(it)}"
        }
        lines += "Eficiência da janela de envio+servidor: ${efficiency(windowEfficiency(audioSeconds))}"
        return lines
    }

    private fun ratio(audioSeconds: Double, elapsedMs: Long): Double =
        audioSeconds / (elapsedMs / 1000.0).coerceAtLeast(0.001)

    private fun seconds(milliseconds: Long): String =
        String.format(Locale.US, "%.1f", milliseconds / 1000.0)

    private fun efficiency(value: Double): String = String.format(Locale.US, "%.2fx", value)
}

/**
 * Acumulador thread-safe dos tempos medidos na rodada: a preparação e o envio
 * rodam em pools paralelos (uma thread por arquivo), então os registros não
 * podem se perder nem se sobrescrever.
 */
class MutableTranscriptionRunTimings {
    private var preparationMs = 0L
    private var preparedFiles = 0
    private var firstRequestAt = 0L
    private var lastUploadWrittenAt = 0L
    private var serverProcessingMs = 0L
    private var serverProcessingFiles = 0
    private var serverAudioMs = 0L
    private var serverAudioFiles = 0

    @Synchronized
    fun recordPreparation(elapsedMs: Long) {
        preparationMs += elapsedMs.coerceAtLeast(0L)
        preparedFiles++
    }

    /** Início da 1ª requisição (só a primeira conta para a janela de envio). */
    @Synchronized
    fun recordRequestStarted(at: Long) {
        if (firstRequestAt == 0L || at < firstRequestAt) firstRequestAt = at
    }

    /** Último byte do último arquivo entregue ao socket (fim do upload). */
    @Synchronized
    fun recordUploadWritten(at: Long) {
        if (at > lastUploadWrittenAt) lastUploadWrittenAt = at
    }

    @Synchronized
    fun recordServerTiming(timing: SttResponseParsers.ServerTiming) {
        serverProcessingMs += (timing.processingSeconds * 1000.0).toLong().coerceAtLeast(0L)
        serverProcessingFiles++
        if (timing.audioSeconds > 0.0) {
            serverAudioMs += (timing.audioSeconds * 1000.0).toLong()
            serverAudioFiles++
        }
    }

    @Synchronized
    fun snapshot(totalMs: Long, firstRequestMs: Long?, serverWindowMs: Long): TranscriptionRunTimings =
        TranscriptionRunTimings(
            totalMs = totalMs,
            firstRequestMs = firstRequestMs,
            preparationMs = preparationMs,
            preparedFiles = preparedFiles,
            uploadMs = if (firstRequestAt > 0L && lastUploadWrittenAt >= firstRequestAt) {
                lastUploadWrittenAt - firstRequestAt
            } else {
                null
            },
            serverWindowMs = serverWindowMs,
            serverProcessingMs = serverProcessingMs.takeIf { serverProcessingFiles > 0 },
            serverProcessingFiles = serverProcessingFiles,
            serverAudioMs = serverAudioMs.takeIf { serverAudioFiles > 0 }
        )
}
