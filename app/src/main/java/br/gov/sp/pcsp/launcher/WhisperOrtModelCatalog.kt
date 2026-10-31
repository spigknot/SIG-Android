package br.gov.sp.pcsp.launcher

/**
 * Catálogo RESTRITO do export Whisper congelado (ORT1-B03/M3, adendo2 §3).
 *
 * A revisão mostrou que uma varredura de nome/8 MB não prova nada: um grafo com
 * `location` além do trecho varrido passava sem os pesos. A resposta não é um
 * parser ONNX universal no telefone — é **catálogo**: o app conhece os arquivos
 * exatos do export (nome, tamanho e SHA-256) e recusa qualquer coisa fora dele.
 *
 * Consequência aceita e declarada: um export novo exige atualizar este arquivo
 * e o manifesto; importar grafo desconhecido é recusado em vez de "validado".
 *
 * Origem dos valores: revisão `360ebcde2559d60bb474678be3c1de9ef347d01a`,
 * medida em staging e conferida contra o manifesto do HF.
 */
object WhisperOrtModelCatalog {

    /** Modelo e revisão que este catálogo descreve. */
    const val MODEL = "whisper-large-v3-turbo"
    const val REVISION = "360ebcde2559d60bb474678be3c1de9ef347d01a"
    const val DTYPE = "int8"
    const val OPSET = 14

    /** Licença do modelo base (`openai/whisper-large-v3-turbo`) e do export. */
    const val LICENSE = "MIT"
    const val LICENSE_SOURCE = "openai/whisper-large-v3-turbo (cardData.license=mit); " +
        "o repo onnx-community só converte os mesmos pesos"

    /** Arquivo esperado do conjunto ativo. */
    data class Entry(
        val name: String,
        val sizeBytes: Long,
        val sha256: String,
        val role: WhisperOrtModelPackage.Role,
    )

    /** Conjunto ATIVO (3 sessões: encoder + init + with_past) mais metadados. */
    val active: List<Entry> = listOf(
        Entry(
            "encoder_model_int8.onnx", 644_822_094L,
            "e44c0d5cfcc6ad283011602a738fa28dfa1ad7f7540c9503205479072a9cc1ef",
            WhisperOrtModelPackage.Role.ENCODER,
        ),
        Entry(
            "decoder_model_int8.onnx", 439_684_851L,
            "b54c67a80cf31304d75b8d525393e8758c23f848027d4fa916f26077e9f4bde2",
            WhisperOrtModelPackage.Role.DECODER,
        ),
        Entry(
            "decoder_with_past_model_int8.onnx", 426_540_585L,
            "6058a3d09f5ef15d413de7b8e64c5582e690c539c3fd909212af83e88dc03d87",
            WhisperOrtModelPackage.Role.DECODER_WITH_PAST,
        ),
        Entry(
            "vocab.json", 1_036_558L,
            "e2aa043ef015641d363d8288e7c241c85e36a5c761fb303598e0710233344387",
            WhisperOrtModelPackage.Role.TOKENIZER,
        ),
        Entry(
            "generation_config.json", 3_897L,
            "16f95291d2f47c944d3c2b19390bba7965666555c1ea2a0bdc850d1fab45612f",
            WhisperOrtModelPackage.Role.CONFIG,
        ),
    )

    /** `true` quando [name] pertence ao conjunto ativo. */
    fun knows(name: String): Boolean = active.any { it.name == name }

    /**
     * Confere o manifesto contra o catálogo.
     *
     * Devolve `null` quando todos os arquivos declarados são conhecidos e o
     * conjunto ativo está completo, ou a razão textual da recusa. Tamanho e SHA
     * declarados divergentes do catálogo são recusados — importar "quase igual"
     * não é importar o export aprovado.
     */
    fun validate(entries: List<WhisperOrtModelPackage.FileEntry>): String? {
        for (entry in entries) {
            if (entry.role == WhisperOrtModelPackage.Role.EXTERNAL_DATA) continue
            val known = active.firstOrNull { it.name == entry.name }
                ?: return "arquivo fora do catálogo: ${entry.name}"
            if (entry.role != known.role) {
                return "${entry.name}: papel ${entry.role} != ${known.role} do catálogo"
            }
            if (entry.sizeBytes != known.sizeBytes) {
                return "${entry.name}: ${entry.sizeBytes} B != ${known.sizeBytes} B do catálogo"
            }
            if (!entry.sha256.equals(known.sha256, ignoreCase = true)) {
                return "${entry.name}: SHA divergente do catálogo"
            }
        }
        val faltando = active.filter { known -> entries.none { it.name == known.name } }
        if (faltando.isNotEmpty()) {
            return "catálogo incompleto: falta ${faltando.joinToString { it.name }}"
        }
        return null
    }

    /** Identidade do pacote derivada do catálogo. */
    fun identity(): WhisperOrtModelPackage.Identity = WhisperOrtModelPackage.Identity(
        model = MODEL,
        revision = REVISION,
        dtype = DTYPE,
        provider = "CPU",
        opset = OPSET,
    )
}
