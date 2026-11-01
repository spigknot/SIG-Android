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

    /**
     * Adendo 17 §1 — variante small (escopo: small + turbo).
     *
     * Export `onnx-community/whisper-small`, revisão congelada, INT8, mesma
     * topologia de 3 sessões do turbo. Arquitetura DIFERENTE (d_model 768,
     * 12 camadas): nada daqui é copiado do turbo — cada conjunto carrega a
     * própria identidade, arquivos e medidas.
     */
    const val MODEL_SMALL = "whisper-small"
    const val REVISION_SMALL = "36050c46d777d46dc4b5f43f6d90574fc38f8732"
    const val DTYPE_SMALL = "int8"
    const val OPSET_SMALL = 14

    /**
     * ADENDO 21 §1 — variantes Tiny e Base MULTILINGUES (com português).
     *
     * Repos `onnx-community/whisper-tiny` e `onnx-community/whisper-base`,
     * revisões CONGELADAS (nada é copiado do small/turbo: cada conjunto é
     * MEDIDO na fonte — sizes + lfs.oid sha256 + downloads verificados dos
     * metadados; recibos em build/ort-rodada1/tree-tiny(base).json e
     * measure-tiny(base) (downloads e hashes dos jsons).
     *
     * MEDIDO na fonte (config.json de cada revisão): tiny d_model=384,
     * encoder/decoder=4/4, vocab_size=51865, mel=80, <|pt|>=50267;
     * base d_model=512, 6/6, vocab_size=51865, mel=80. Tabelas mel da
     * família 80 (slaney80) e tokenizer da FAMÍLIA MULTILÍNGUE 51865
     * (vocab.json idêntico ao small — medido nos três repositórios,
     * sha 50d6a919…; NÃO confundir com o turbo 51866).
     */
    const val MODEL_TINY = "whisper-tiny"
    const val REVISION_TINY = "ff4177021cc41f7db950912b73ea4fdf7d01d8e7"
    const val DTYPE_TINY = "int8"
    const val OPSET_TINY = 14
    const val MODEL_BASE = "whisper-base"
    const val REVISION_BASE = "1846881b6b3a3024392c1eea3ad983695bc23925"
    const val DTYPE_BASE = "int8"
    const val OPSET_BASE = 14

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

    /**
     * Adendo 17 §1 — conjunto small (medido no HF + staging local).
     *
     * Tamanhos/SHA-256 medidos da revisão congelada; `vocab.json` e
     * `generation_config.json` baixados e conferidos (1.036.584 B e 3.893 B).
     * Arquitetura 768/12 (não 1280/32): a sessão detecta do grafo, nunca do
     * turbo.
     */
    val activeSmall: List<Entry> = listOf(
        Entry(
            "encoder_model_int8.onnx", 92_326_127L,
            "2601c9eb2d345c5916d4576d36f663a7c96589740fb2273828c48c3fc2c7db75",
            WhisperOrtModelPackage.Role.ENCODER,
        ),
        Entry(
            "decoder_model_int8.onnx", 155_989_346L,
            "0486afa8eb464bf2210d07aca0cb3f5d91d3f2f01cbe47f9f1e6ff9122abaaf6",
            WhisperOrtModelPackage.Role.DECODER,
        ),
        Entry(
            "decoder_with_past_model_int8.onnx", 141_652_708L,
            "f1eb4ac7fe2b3aeed93a8de8f478af45b8fcef7302491ffcf9c84f33f71b3e7b",
            WhisperOrtModelPackage.Role.DECODER_WITH_PAST,
        ),
        Entry(
            "vocab.json", 1_036_584L,
            "50d6a919f0a0601d56a04eb583c780d18553aa388254ba3158eb6a00f13e2c1a",
            WhisperOrtModelPackage.Role.TOKENIZER,
        ),
        Entry(
            "generation_config.json", 3_893L,
            "f538b28220c6a6d6f1af1458d4141cacb4ef4963df3de98a19490440c412ddf0",
            WhisperOrtModelPackage.Role.CONFIG,
        ),
    )

    /**
     * ADENDO 21 §1 — conjunto Tiny (medido no HF, revisão congelada):
     * encoder INT8 10.124.977 B, decoder 30.461.071 B, with_past
     * 29.220.476 B; vocab 1.036.584 B (idêntico ao small — tokenizer da
     * família); generation_config 3.772 B.
     */
    val activeTiny: List<Entry> = listOf(
        Entry(
            "encoder_model_int8.onnx", 10_124_977L,
            "03ff3c99ce804f79a42afd6212c9492eb75e55625926de66f8fc192e9567d336",
            WhisperOrtModelPackage.Role.ENCODER,
        ),
        Entry(
            "decoder_model_int8.onnx", 30_461_071L,
            "2a160a2a5ccf901f83aa4d4ceec580a269bd2cec582871e0816e98c610594f12",
            WhisperOrtModelPackage.Role.DECODER,
        ),
        Entry(
            "decoder_with_past_model_int8.onnx", 29_220_476L,
            "9fe615bbbf4e871eac150e6ab3732b22515ae347d6f73fb009775c6ee04c5858",
            WhisperOrtModelPackage.Role.DECODER_WITH_PAST,
        ),
        Entry(
            "vocab.json", 1_036_584L,
            "50d6a919f0a0601d56a04eb583c780d18553aa388254ba3158eb6a00f13e2c1a",
            WhisperOrtModelPackage.Role.TOKENIZER,
        ),
        Entry(
            "generation_config.json", 3_772L,
            "f5c67e5a4f7102f8cb4d058bc95da276bbc19eeec997267c3bb0f25ef68facd1",
            WhisperOrtModelPackage.Role.CONFIG,
        ),
    )

    /**
     * ADENDO 21 §1 — conjunto Base (medido no HF, revisão congelada):
     * encoder INT8 23.201.297 B, decoder 53.310.178 B, with_past
     * 50.075.136 B; vocab 1.036.584 B (idêntico ao small/tiny);
     * generation_config 3.832 B.
     */
    val activeBase: List<Entry> = listOf(
        Entry(
            "encoder_model_int8.onnx", 23_201_297L,
            "ca6177401f86a2c6b4dc5f7fc02fbca680678906bd0c22f6d89f0b80f124253f",
            WhisperOrtModelPackage.Role.ENCODER,
        ),
        Entry(
            "decoder_model_int8.onnx", 53_310_178L,
            "c859636f4463dd5a9ea752546c6c7badeb48ab3e22b340cafef08a15552fcb2e",
            WhisperOrtModelPackage.Role.DECODER,
        ),
        Entry(
            "decoder_with_past_model_int8.onnx", 50_075_136L,
            "dd0d9ff1c066992aa8db6ff1944b5fd5660e95ecbbc86e0da158300c23e52232",
            WhisperOrtModelPackage.Role.DECODER_WITH_PAST,
        ),
        Entry(
            "vocab.json", 1_036_584L,
            "50d6a919f0a0601d56a04eb583c780d18553aa388254ba3158eb6a00f13e2c1a",
            WhisperOrtModelPackage.Role.TOKENIZER,
        ),
        Entry(
            "generation_config.json", 3_832L,
            "61070cf8de25b1e9256e8e102ded49d8d24a8369ed36ef84fdf21549e68125a0",
            WhisperOrtModelPackage.Role.CONFIG,
        ),
    )

    /** `true` quando [name] pertence ao conjunto ativo. */
    fun knows(name: String): Boolean = active.any { it.name == name }

    /**
     * ADENDO 23 §1 — IDs de TAREFA por FAMÍLIA de export.
     *
     * Medidos nos metadados primários baixados da revisão congelada
     * (`task_to_id` do generation_config.json): tiny/base/small usam
     * transcribe=50359/translate=50358/noTimestamps=50363; turbo
     * mantém 50360/50359/50364.
     * NÃO trocar a constante global: outra família exige recusa explícita.
     */
    data class TaskIds(val transcribe: Int, val translate: Int, val noTimestamps: Int)

    fun taskIdsPara(modelo: String): TaskIds = when (modelo) {
        MODEL_TINY, MODEL_BASE, MODEL_SMALL -> TaskIds(50_359, 50_358, 50_363)
        MODEL -> TaskIds(50_360, 50_359, 50_364)
        else -> throw IllegalArgumentException("modelo ORT desconhecido: $modelo")
    }

    /**
     * Adendo 17 §1 — especificação de modelo capturada na admissão/download.
     *
     * Converte o pedido da tela (rótulo GGML: "small"/"v3Turbo", ou nulo quando
     * a rota não recebeu seleção) no modelo ORT efetivo. `null` = legado turbo
     * (compatibilidade com chamadas sem pedido). Rótulo desconhecido devolve
     * `null`: recusa explícita, nunca turbo silencioso.
     */
    fun modeloParaPedido(pedido: String?): String? = when {
        pedido.isNullOrBlank() -> MODEL
        pedido == MODEL || pedido == "v3Turbo" -> MODEL
        pedido == MODEL_SMALL || pedido == "small" -> MODEL_SMALL
        // Adendo 21 §1 — Tiny e Base MULTILINGUES passam a ter export
        // real; os rótulos da tela continuam GGML ("tiny"/"base").
        pedido == MODEL_TINY || pedido == "tiny" -> MODEL_TINY
        pedido == MODEL_BASE || pedido == "base" -> MODEL_BASE
        else -> null
    }

    /** `true` quando [pedido] tem export ORT validado nesta rodada. */
    fun suportado(pedido: String?): Boolean = modeloParaPedido(pedido) != null

    /** Conjunto de arquivos do [modelo] (turbo default). */
    fun conjuntoPara(modelo: String): List<Entry> = when (modelo) {
        MODEL_SMALL -> activeSmall
        MODEL_TINY -> activeTiny
        MODEL_BASE -> activeBase
        MODEL -> active
        else -> throw IllegalArgumentException("modelo ORT desconhecido: $modelo")
    }

    /**
     * Canais mel do extrator do [modelo] (adendo 17 §1): 80 no small
     * (extrator padrão), 128 no turbo (large-v3). É o que escolhe a tabela
     * de filtros do frontend — com 128 fixo o `ConvInteger` do small
     * falhava (`C: 128, kernel channels: 80`).
     */
    fun melBinsPara(modelo: String): Int = when (modelo) {
        // Tiny/Base compartilham os 80 bins da família multilíngue
        // (medido: num_mel_bins=80 em ambas as configs congeladas).
        MODEL_SMALL, MODEL_TINY, MODEL_BASE -> WhisperOrtMelFilters.N_MELS_SMALL
        MODEL -> WhisperOrtMelFilters.N_MELS
        else -> throw IllegalArgumentException("modelo ORT desconhecido: $modelo")
    }

    /** Tabela de filtros mel do [modelo] (80 small, 128 turbo). */
    fun filtrosMelPara(modelo: String): Array<FloatArray> = when (melBinsPara(modelo)) {
        WhisperOrtMelFilters.N_MELS_SMALL -> WhisperOrtMelFilters.slaney80
        else -> WhisperOrtMelFilters.slaney
    }

    /**
     * Variante tolerante para bancada: modelo desconhecido (fakes de teste)
     * cai na tabela 128 em vez de lançar. Produção usa [filtrosMelPara],
     * que recusa o desconhecido.
     */
    fun filtrosMelOuDefault(modelo: String): Array<FloatArray> =
        runCatching { filtrosMelPara(modelo) }.getOrDefault(WhisperOrtMelFilters.slaney)

    /** Tamanho total do vocabulário do [modelo] (adendo 17 §1). */
    fun tamanhoVocabPara(modelo: String): Int = when (modelo) {
        // Medido nos logits do decoder (`detectVocabSize`): o small tem UM
        // especial a menos que o turbo (51865 vs 51866). O `vocab.json` dos
        // dois só traz o texto (50258 chaves); os especiais são
        // convencionais e o tamanho total tem de acompanhar o export.
        // Adendo 21 §1 — Tiny/Base são da MESMA família multilíngue:
        // config.json medido traz vocab_size=51865 em ambos, e o vocab.json
        // é o MESMO arquivo do small (sha idêntico). O runtime continua
        // detectando do grafo (nunca presupuesto).
        MODEL_SMALL, MODEL_TINY, MODEL_BASE -> WhisperOrtTokenizer.VOCAB_SIZE_SMALL
        MODEL -> WhisperOrtTokenizer.VOCAB_SIZE
        else -> throw IllegalArgumentException("modelo ORT desconhecido: $modelo")
    }

    /** Faixa de ids especiais do [modelo] (50257 até tamanho-1). */
    fun especiaisPara(modelo: String): Set<Int> =
        (WhisperOrtTokenizer.FIRST_SPECIAL_ID until tamanhoVocabPara(modelo)).toSet()

    /** Tamanho real do pacote [modelo] (turbo default), para exibir antes de baixar. */
    fun tamanhoPacoteBytes(modelo: String = MODEL): Long = conjuntoPara(modelo).sumOf { it.sizeBytes }

    /**
     * Confere o manifesto contra o catálogo do [modelo].
     *
     * Devolve `null` quando todos os arquivos declarados são conhecidos e o
     * conjunto está completo, ou a razão textual da recusa. Tamanho e SHA
     * declarados divergentes do catálogo são recusados — importar "quase igual"
     * não é importar o export aprovado. NUNCA mistura descritores: manifesto
     * small contra catálogo turbo (e vice-versa) é recusado arquivo a arquivo.
     */
    fun validate(
        entries: List<WhisperOrtModelPackage.FileEntry>,
        descriptor: List<Entry> = active,
    ): String? {
        for (entry in entries) {
            if (entry.role == WhisperOrtModelPackage.Role.EXTERNAL_DATA) continue
            val known = descriptor.firstOrNull { it.name == entry.name }
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
        val faltando = descriptor.filter { known -> entries.none { it.name == known.name } }
        if (faltando.isNotEmpty()) {
            return "catálogo incompleto: falta ${faltando.joinToString { it.name }}"
        }
        return null
    }

    /** Identidade do pacote derivada do catálogo (turbo default). */
    fun identity(modelo: String = MODEL): WhisperOrtModelPackage.Identity = when (modelo) {
        MODEL_SMALL -> WhisperOrtModelPackage.Identity(
            model = MODEL_SMALL,
            revision = REVISION_SMALL,
            dtype = DTYPE_SMALL,
            provider = "CPU",
            opset = OPSET_SMALL,
        )
        MODEL_TINY -> WhisperOrtModelPackage.Identity(
            model = MODEL_TINY,
            revision = REVISION_TINY,
            dtype = DTYPE_TINY,
            provider = "CPU",
            opset = OPSET_TINY,
        )
        MODEL_BASE -> WhisperOrtModelPackage.Identity(
            model = MODEL_BASE,
            revision = REVISION_BASE,
            dtype = DTYPE_BASE,
            provider = "CPU",
            opset = OPSET_BASE,
        )
        MODEL -> WhisperOrtModelPackage.Identity(
            model = MODEL,
            revision = REVISION,
            dtype = DTYPE,
            provider = "CPU",
            opset = OPSET,
        )
        else -> throw IllegalArgumentException("modelo ORT desconhecido: $modelo")
    }
}
