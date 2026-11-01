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

        /**
         * Pedido fora do catálogo ORT desta rodada (adendo 16 §2).
         *
         * Proteção transitória: `small` (e `tiny`/`base`) não têm export ONNX
         * validado. Informa a limitação e oferece escolha consentida, em vez
         * de iniciar o download do turbo como se fosse a variante pedida.
         */
        data class ModeloNaoSuportado(val pedido: String, val detalhe: String) : Estado()
    }

    /** Raiz onde o instalador grava as versões imutáveis. */
    fun raiz(context: Context): File = File(context.filesDir, "ort-model")

    /**
     * Adendo 17 §1 — `true` quando [modelo] tem versão íntegra em disco,
     * mesmo sem ser a ativa.
     *
     * Trocar de modelo com o pacote válido não baixa de novo: confere o
     * marcador (identidade + arquivos) da versão do modelo, sem rehash.
     * A revalidação completa (fingerprint do conteúdo) acontece no
     * [WhisperOrtPackageInstaller.ativar].
     */
    fun temVersaoInstalada(context: Context, modelo: String): Boolean {
        val identidade = runCatching { WhisperOrtModelCatalog.identity(modelo) }.getOrNull() ?: return false
        val descritor = runCatching { WhisperOrtModelCatalog.conjuntoPara(modelo) }.getOrNull() ?: return false
        return runCatching { WhisperOrtPackageInstaller.instalado(raiz(context), identidade, descritor) }.getOrDefault(false)
    }

    /**
     * Consulta o estado SEM abrir sessão nem baixar nada.
     *
     * Reusa a mesma porta da rota (`WhisperOrtPackageInstaller.open`), com a
     * identidade DO MODELO PEDIDO (adendo 17 §1): o que a UI mostra é o que a
     * transcrição vai encontrar — não uma checagem paralela que poderia
     * divergir. Turbo é o default (compatibilidade).
     */
    fun estado(context: Context, modelo: String = WhisperOrtModelCatalog.MODEL): Estado {
        val root = raiz(context)
        val ativo = File(root, "active")
        if (!ativo.isFile || ativo.length() == 0L) {
            return Estado.Ausente("pacote ORT nao instalado em ${root.path}")
        }
        return when (val aberto = WhisperOrtPackageInstaller.open(
            root,
            WhisperOrtModelCatalog.identity(modelo),
            WhisperOrtModelCatalog.conjuntoPara(modelo),
        )) {
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
        is Estado.ModeloNaoSuportado ->
            "O ORT desta rodada só tem pacote validado para ${WhisperOrtModelCatalog.MODEL} " +
                "(${WhisperOrtModelCatalog.tamanhoPacoteBytes() / 1_048_576L} MB). " +
                "“${estado.pedido}” não tem export ORT validado. Detalhe: ${estado.detalhe}"
    }
}
