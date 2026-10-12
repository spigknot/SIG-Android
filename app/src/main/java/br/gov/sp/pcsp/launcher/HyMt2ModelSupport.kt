package br.gov.sp.pcsp.launcher

/** Política de backend por modelo da ferramenta Texto (regra pura, sem UI).
 *
 * O modelo 1.25bit usa tensores do tipo STQ1_0, cujo kernel existe SÓ para CPU
 * neste build: na GPU o OpenCL cai para CPU com cópias (mais lento) e o Vulkan
 * pode devolver saída alucinada (visto em campo em 25/09/2026). Os modelos Q4_0
 * e Q4_K_M usam tipos que os dois backends de GPU suportam de verdade — são os
 * únicos que oferecem CPU/OpenCL/Vulkan.
 */
object HyMt2ModelSupport {

    /** Marcador do arquivo do modelo 1.25bit (tensores STQ, sem kernel de GPU). */
    private const val STQ_MODEL_MARKER = "1.25Bit"

    /** `true` quando o modelo pode rodar acelerado em GPU (OpenCL/Vulkan). */
    fun supportsGpu(fileName: String): Boolean = !fileName.contains(STQ_MODEL_MARKER)

    /** `true` quando o modelo pode rodar no backend NPU (Hexagon/HTP).
     *
     * Regra atual coincide com a de GPU (o único caso sem kernel acelerado é o
     * STQ do 1.25bit; a matriz de suporte de 06/10 confirma Q4_0/Q4_K/Q6_K/Q8_0
     * etc. no ggml-hexagon). Separada para as duas noções poderem divergir sem
     * tocar nos chamadores.
     */
    fun supportsNpu(fileName: String): Boolean = !fileName.contains(STQ_MODEL_MARKER)
}
