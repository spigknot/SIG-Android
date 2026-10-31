package br.gov.sp.pcsp.launcher

import android.content.Context
import java.io.File

/**
 * ESTADO do pacote ORT para a ferramenta — o que a UI precisa saber antes de
 * prometer transcrição.
 *
 * Não faz download: o pacote vem de staging/importação (o mesmo `install` atômico
 * do instalador). O que este fluxo resolve é a **honestidade da interface**: a
 * ferramenta consegue dizer "pacote ausente por este motivo" em vez de falhar no
 * meio do job, e o motivo é o mesmo que a rota usaria.
 */
object WhisperOrtPackageFlow {

    /** Situação do pacote, com o motivo quando não está pronto. */
    sealed class Estado {
        data class Pronto(val versionKey: String) : Estado()
        data class Ausente(val motivo: String) : Estado()
        data class Invalido(val motivo: String) : Estado()
    }

    /** Raiz onde o instalador grava as versões imutáveis. */
    fun raiz(context: Context): File = File(context.filesDir, "ort-model")

    /**
     * Consulta o estado SEM abrir sessão nem baixar nada.
     *
     * Reusa a mesma porta da rota (`WhisperOrtPackageInstaller.open`), então o que
     * a UI mostra é o que a transcrição vai encontrar — não uma checagem paralela
     * que poderia divergir.
     */
    fun estado(context: Context): Estado {
        val root = raiz(context)
        val ativo = File(root, "active")
        if (!ativo.isFile || ativo.length() == 0L) {
            return Estado.Ausente("pacote ORT nao instalado em ${root.path}")
        }
        return when (val aberto = WhisperOrtPackageInstaller.open(root, WhisperOrtModelCatalog.identity())) {
            is WhisperOrtPackageInstaller.Outcome.Ready ->
                Estado.Pronto(aberto.validated.versionKey)
            is WhisperOrtPackageInstaller.Outcome.Refused ->
                Estado.Invalido("${aberto.reason}: ${aberto.detail}")
        }
    }

    /** Mensagem pronta para a tela, sem jargão de código. */
    fun explicar(estado: Estado): String = when (estado) {
        is Estado.Pronto -> "Pacote ORT pronto (versao ${estado.versionKey.take(8)})."
        is Estado.Ausente ->
            "O motor ORT precisa do pacote de modelo instalado. Nada foi transcrito. " +
                "Detalhe: ${estado.motivo}"
        is Estado.Invalido ->
            "O pacote ORT instalado nao passou na validacao, entao a transcricao por ORT " +
                "foi recusada em vez de arriscar resultado errado. Detalhe: ${estado.motivo}"
    }
}
