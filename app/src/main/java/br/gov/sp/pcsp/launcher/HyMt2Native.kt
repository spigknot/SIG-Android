package br.gov.sp.pcsp.launcher

/** Ponte JNI do Hy-MT2 (carregar modelo, traduzir, liberar).
 *
 * So declara/aciona o nativo (libsig_llama.so); seleção de modelo, backend,
 * download, UI e log ficam em TextoActivity. Nenhum segredo passa por aqui.
 */

object HyMt2Native {
    init {
        NativeDependencyManager.loadLibrary(SigApplication.appInstance, "sig_llama")
    }

    /** Carrega o modelo GGUF e cria o contexto de inferência.
     *
     * @param backendKind 0 = CPU, 1 = GPU OpenCL, 2 = GPU Vulkan (3 = NPU, não
     *  implementado). GPU exige o device correspondente no aparelho; sem ele a
     *  carga falha com diagnóstico em `lastError()`.
     * @param nThreads 0 deixa o llama.cpp escolher.
     * @param nCtx tamanho do contexto (0 = 8192). */
    external fun loadModel(modelPath: String, backendKind: Int, nThreads: Int, nCtx: Int): Boolean

    /** Gera a tradução para o prompt já montado (com template de chat). */
    external fun generate(
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        topK: Int,
        repeatPenalty: Float
    ): String?

    external fun releaseModel()
    external fun lastError(): String

    /** Backend realmente usado no último carregamento (ex.: "GPU Vulkan (Vulkan / Vulkan0)"). */
    external fun backendDescription(): String

    /** Linhas de memória do último carregamento (tamanhos de buffer, VRAM do device). */
    external fun loadSummary(): String

    /** Estatísticas da última geração (ex.: "9 tokens em 1.2 s (7.4 tokens/s)"). */
    external fun lastStats(): String
    external fun systemInfo(): String
}
