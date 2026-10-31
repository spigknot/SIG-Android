package br.gov.sp.pcsp.launcher

/**
 * PORTA de admissão da rota ORT — a decisão que acontece ANTES de enfileirar.
 *
 * Existe separada da rota por dois motivos:
 * 1. **contrato verificável**: é pura (sem Android, sem sessão), então o teste
 *    consegue provar que uma recusa NÃO entrega trabalho ao executor;
 * 2. **ordem explícita**: decidir → (só se admitido) enfileirar. Antes a admissão
 *    rodava dentro do worker, e um clique concorrente enfileirava um `Runnable`
 *    que só depois era recusado.
 *
 * Não guarda estado: a admissão é injetada, o que mantém o single-flight no
 * `WhisperOrtAdmission` (dono único do job em voo).
 */
object WhisperOrtPorta {

    /**
     * `true` quando [motivoRecusa] pode oferecer a troca para whisper.cpp.
     *
     * SOMENTE recusa por capacidade (pacote/mídia): BUSY, dreno, cancelamento e
     * erro interno NÃO oferecem troca de motor enquanto o recurso estiver ativo.
     * Pura para ser verificável em JVM.
     */
    fun isFallbackElegivel(motivoRecusa: String): Boolean =
        motivoRecusa.startsWith("pacote:") || motivoRecusa.startsWith("midia:")

    /**
     * Admite a execução de [identidade] e, SOMENTE quando admitido, chama
     * [enfileirar]. Devolve a decisão para o chamador reportar a recusa.
     */
    fun despachar(
        identidade: String,
        admissao: WhisperOrtAdmission,
        enfileirar: (WhisperOrtAdmission.Job) -> Unit,
    ): WhisperOrtAdmission.Admitted {
        val hashSelecao = WhisperOrtPcm.sha256(identidade.toByteArray(Charsets.UTF_8))
        val porta = admissao.admit(
            jobId = "ort-route-" + System.nanoTime(),
            attempt = 1L,
            engine = "ORT",
            provider = "CPU",
            inputSha256 = hashSelecao,
        )
        if (porta is WhisperOrtAdmission.Admitted.Ok) {
            try {
                enfileirar(porta.job)
            } catch (falha: Throwable) {
                // A fila nao recebeu o trabalho; nenhum worker vira liberar o job.
                admissao.abandon(porta.job)
                throw falha
            }
        }
        return porta
    }
}
