package br.gov.sp.pcsp.launcher

import java.io.File

/** Validação e ativação de arquivos baixados, preservando o destino em caso de falha. */
object DownloadFileStorage {
    /** Arquivo completo; tamanho desconhecido exige ao menos conteúdo não vazio. */
    fun isComplete(file: File, expectedBytes: Long = 0L): Boolean =
        file.isFile && file.length() > 0L && (expectedBytes <= 0L || file.length() == expectedBytes)

    /** Só publica o temporário depois de conferir o tamanho esperado. */
    fun activate(partial: File, destination: File, expectedBytes: Long = 0L) {
        check(isComplete(partial, expectedBytes)) {
            "Download incompleto: ${destination.name}. " +
                if (expectedBytes > 0L) "Esperados $expectedBytes bytes, recebidos ${partial.length()}."
                else "O arquivo está vazio."
        }
        val previous = File(destination.parentFile, "${destination.name}.previous-${System.nanoTime()}")
        val hadPrevious = destination.exists()
        if (hadPrevious) check(destination.renameTo(previous)) {
            "Não foi possível preservar o arquivo anterior: ${destination.name}."
        }
        try {
            if (!partial.renameTo(destination)) {
                partial.copyTo(destination, overwrite = false)
                partial.delete()
            }
            previous.delete()
        } catch (error: Exception) {
            destination.delete()
            if (hadPrevious && !previous.renameTo(destination)) {
                previous.copyTo(destination, overwrite = true)
                previous.delete()
            }
            throw error
        }
    }
}
