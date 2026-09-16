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
 *  - [serverWindowMs]: 1ª requisição -> última resposta lida (rede + servidor);
 *  - [serverProcessingMs]: o que o SERVIDOR informa ter processado
 *    (`total_processing_time_seconds` / `processing_time_seconds` na resposta).
 *    É o único número honesto para "tempo no servidor"; a diferença
 *    [networkMs] é upload + espera + download.
 *
 * Puro (sem Android, UI, rede ou estado) para ser verificável por testes
 * unitários — TranscriptionRunTimingsTest.
 */
data class TranscriptionRunTimings(
    val totalMs: Long,
    val firstRequestMs: Long?,
    val preparationMs: Long,
    val preparedFiles: Int,
    val serverWindowMs: Long,
    val serverProcessingMs: Long?,
    val serverProcessingFiles: Int,
    val serverAudioMs: Long?
) {
    /** Rede dentro da janela: upload + espera + resposta (nunca negativa). */
    val networkMs: Long? = serverProcessingMs?.let { (serverWindowMs - it).coerceAtLeast(0L) }

    fun generalEfficiency(audioSeconds: Double): Double = ratio(audioSeconds, totalMs)

    fun windowEfficiency(audioSeconds: Double): Double = ratio(audioSeconds, serverWindowMs)

    /** Eficiência real do servidor; null quando ele não informou o tempo. */
    fun serverEfficiency(audioSeconds: Double): Double? =
        serverProcessingMs?.let { ratio(audioSeconds, it) }

    /** Linhas da conta aberta (tempos + eficiências) para o relatório. */
    fun reportLines(audioSeconds: Double): List<String> {
        val lines = mutableListOf<String>()
        lines += "Tempo total (clique -> fim): ${seconds(totalMs)}s"

        val preparation = buildString {
            append("- preparação local (copiar/converter/VAD): ${seconds(preparationMs)}s")
            if (preparedFiles > 0) append(" somando $preparedFiles arquivo(s)")
            firstRequestMs?.let { append("; 1º arquivo pronto em ${seconds(it)}s") }
        }
        lines += preparation

        lines += "- janela de envio + servidor (1º envio -> última resposta): ${seconds(serverWindowMs)}s"
        val processing = serverProcessingMs
        if (processing == null) {
            lines += "- processamento no servidor: não informado pela resposta"
        } else {
            lines += "- processamento no servidor (reportado por ele): ${seconds(processing)}s" +
                if (serverProcessingFiles > 0) " ($serverProcessingFiles arquivo(s))" else ""
            networkMs?.let { lines += "- rede dentro da janela (upload + espera + resposta): ${seconds(it)}s" }
        }

        lines += "Eficiência geral (clique -> fim): ${efficiency(generalEfficiency(audioSeconds))}"
        serverEfficiency(audioSeconds)?.let { lines += "Eficiência do servidor (processamento real): ${efficiency(it)}" }
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
    private var serverProcessingMs = 0L
    private var serverProcessingFiles = 0
    private var serverAudioMs = 0L
    private var serverAudioFiles = 0

    @Synchronized
    fun recordPreparation(elapsedMs: Long) {
        preparationMs += elapsedMs.coerceAtLeast(0L)
        preparedFiles++
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
            serverWindowMs = serverWindowMs,
            serverProcessingMs = serverProcessingMs.takeIf { serverProcessingFiles > 0 },
            serverProcessingFiles = serverProcessingFiles,
            serverAudioMs = serverAudioMs.takeIf { serverAudioFiles > 0 }
        )
}
