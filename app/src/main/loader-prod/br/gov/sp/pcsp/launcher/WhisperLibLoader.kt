package br.gov.sp.pcsp.launcher

/** Seam de carga das bibliotecas nativas do Whisper (R2/B3).
 *
 * Esta é a implementação de PRODUÇÃO: delega exatamente ao
 * [NativeDependencyManager] (comportamento inalterado — mesma verificação de
 * pacote instalado, mesma ordem c++_shared → omp → sig_whisper).
 *
 * A variante de laboratório (flavor `whisperlab`, somente debug) substitui
 * ESTE arquivo pelo source set `app/src/whisperlab/java/...` e carrega
 * bibliotecas candidatas de um diretório privado do sandbox do lab, sem tocar
 * em nenhum caminho de produção. O nome do objeto e do pacote são idênticos de
 * propósito: é a sobreposição de source set que troca a implementação.
 */
object WhisperLibLoader {
    fun load(name: String) {
        NativeDependencyManager.loadLibrary(SigApplication.appInstance, name)
    }
}
