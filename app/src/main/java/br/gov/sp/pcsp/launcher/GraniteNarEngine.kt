package br.gov.sp.pcsp.launcher

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Granite Speech 4.1 2B NAR — engine ONNX FP16 (pipeline NLE / non-autoregressive).
 *
 * Arquitetura diferente do TurboCTC (GraniteEngine.kt): 3 sub-grafos encadeados
 * (encoder conformer CTC + Q-Former projector + LLM editor bidirecional), com
 * editing de hipótese CTC via slots de inserção. Multilíngue (EN/ES/FR/DE/PT).
 *
 * Partes puras (front-end, CTC collapse, interleave, tokenizer byte-level,
 * conversão fp16) são testáveis na JVM; só a sessão ORT é do dispositivo.
 *
 * Contrato real dos grafos: ver docs/granite-nar-design.md.
 */

/** Conversão IEEE 754 half (fp16) <-> float32, portável (sem android.util.Half). */
object HalfFloat {
    fun toFloat(h: Short): Float {
        val s = (h.toInt() ushr 15) and 0x1
        var e = (h.toInt() ushr 10) and 0x1F
        var m = h.toInt() and 0x3FF
        val bits: Int
        when {
            e == 0 -> {
                if (m == 0) {
                    bits = s shl 31
                } else {
                    // subnormal
                    var mm = m
                    var ee = 127 - 15 + 1
                    while ((mm and 0x400) == 0) { mm = mm shl 1; ee-- }
                    mm = mm and 0x3FF
                    bits = (s shl 31) or (ee shl 23) or (mm shl 13)
                }
            }
            e == 0x1F -> {
                bits = (s shl 31) or 0x7F800000 or (m shl 13) // inf/nan
            }
            else -> {
                e += (127 - 15)
                bits = (s shl 31) or (e shl 23) or (m shl 13)
            }
        }
        return Float.fromBits(bits)
    }
}

/** Front-end NAR: log-mel 80 + stack 2x -> 160 dim. SEM deltas, SEM AGC. */
class GraniteNarFrontend(
    private val melFilters: FloatArray, // [80 * 257] mel-major (transposta de torchaudio mel_scale.fb)
    private val window: FloatArray,     // [512] = Hann(400) com pad 56 zeros em cada lado
) {
    companion object {
        const val N_FFT = 512
        const val HOP = 160
        const val N_MELS = 80
        const val N_FREQS = 257
        const val STACK = 2
        const val INPUT_DIM = 160
        const val FLOOR_DB = 8.0f
        const val T_FIXED = 2000  // shape estático do encoder exportado
    }

    private val fft = Radix2Fft(N_FFT)
    private val eps = 1e-10f

    /** Frames de saída (160-dim) para nSamples de áudio: T // (2*hop). */
    fun outFrames(nSamples: Int): Int = nSamples / (2 * HOP)

    /** Mel frames a manter (truncamento do torchaudio): 2*(T // (2*hop)). */
    fun melFrameCount(nSamples: Int): Int = 2 * (nSamples / (2 * HOP))

    /** Retorna [frames, 160] row-major, SEM padding até T_FIXED. */
    fun compute(wav: FloatArray): GraniteFeatures {
        val n = wav.size
        val l = melFrameCount(n)
        if (l == 0) return GraniteFeatures(FloatArray(0), 0, INPUT_DIM)

        // torch.stft(center=True, pad_mode='reflect'): pad 256 em cada lado.
        val half = N_FFT shr 1
        val x = FloatArray(n + N_FFT)
        System.arraycopy(wav, 0, x, half, n)
        for (i in 0 until half) {
            x[half - 1 - i] = wav[min(i + 1, n - 1)]
            x[half + n + i] = wav[max(n - 2 - i, 0)]
        }

        // Mel spectrogram, mel-major ([80][l]).
        val mel = FloatArray(N_MELS * l)
        val re = FloatArray(N_FFT)
        val im = FloatArray(N_FFT)
        val power = FloatArray(N_FREQS)
        for (t in 0 until l) {
            val off = t * HOP
            for (i in 0 until N_FFT) { re[i] = x[off + i] * window[i]; im[i] = 0f }
            fft.run(re, im)
            for (k in 0 until N_FREQS) power[k] = re[k] * re[k] + im[k] * im[k]
            for (m in 0 until N_MELS) {
                val fb = m * N_FREQS
                var acc = 0f
                for (k in 0 until N_FREQS) acc += melFilters[fb + k] * power[k]
                mel[m * l + t] = acc
            }
        }

        // log10(clamp(1e-10)) -> max(logmel, mx-8)/4 + 1.
        var mx = Float.NEGATIVE_INFINITY
        for (i in mel.indices) {
            val v = log10(max(mel[i], eps))
            mel[i] = v
            if (v > mx) mx = v
        }
        val fl = mx - FLOOR_DB
        for (i in mel.indices) mel[i] = (max(mel[i], fl) / 4f + 1f)

        // Stack 2x: (80, l) -> (l/2, 160).
        val outF = l / STACK
        val data = FloatArray(outF * INPUT_DIM)
        for (j in 0 until outF) {
            for (k in 0 until STACK) {
                val t = j * STACK + k
                val base = j * INPUT_DIM + k * N_MELS
                for (c in 0 until N_MELS) data[base + c] = mel[c * l + t]
            }
        }
        return GraniteFeatures(data, outF, INPUT_DIM)
    }
}

/** CTC collapse greedy (argmax -> unique_consecutive -> remove blank). */
object GraniteNarCtc {
    const val BLANK = 100257

    /**
     * argmax + unique_consecutive + remove blank, sobre logits [frames, vocab]
     * row-major, lendo DIRETO de um FloatBuffer (output do ORT) — evita
     * materializar os ~200 MB (frames × vocab × 4) no heap Java, que estourava
     * com OutOfMemoryError (growth limit 256 MB) no NAR (fix 29/08).
     */
    fun collapseLogits(
        logits: java.nio.FloatBuffer,
        frames: Int,
        vocab: Int,
        frameOffset: Int = 0,
    ): IntArray {
        require(frames >= 0) { "frames negativo: $frames" }
        require(vocab > 0) { "vocab inválido: $vocab" }
        require(frameOffset >= 0) { "frameOffset negativo: $frameOffset" }
        require((frameOffset.toLong() + frames) * vocab <= logits.limit().toLong()) {
            "logits insuficientes: limit=${logits.limit()}, offset=$frameOffset, frames=$frames, vocab=$vocab"
        }
        val out = ArrayList<Int>(frames)
        var prev = -1
        for (t in 0 until frames) {
            val row = (frameOffset + t) * vocab
            var best = 0
            var bestVal = logits.get(row)
            for (v in 1 until vocab) {
                val valv = logits.get(row + v)
                if (valv > bestVal) { bestVal = valv; best = v }
            }
            if (best != prev && best != BLANK) out.add(best)
            prev = best
        }
        return out.toIntArray()
    }

    /** Overload de conveniência para FloatArray (usado em testes/outros fluxos). */
    fun collapseLogits(logits: FloatArray, frames: Int, vocab: Int): IntArray =
        collapseLogits(java.nio.FloatBuffer.wrap(logits), frames, vocab)
}

/** Intercalação de slots de inserção: [blank, tok0, blank, tok1, ...]. */
object GraniteNarInterleave {
    /** len = max(2n+1, minEditSequenceLength); posições ímpares recebem os tokens. */
    fun buildSlots(ctcTokens: IntArray, blank: Int, minEditSequenceLength: Int = 8): IntArray {
        val n = ctcTokens.size
        val total = max(2 * n + 1, minEditSequenceLength)
        val slots = IntArray(total) { blank }
        for (i in 0 until n) slots[2 * i + 1] = ctcTokens[i]
        return slots
    }
}

/**
 * Seleção do bucket do encoder/projector pelo tamanho do áudio.
 *
 * O encoder/projector exportado tem forma ESTÁTICA (`[1, T, 160]`), então o áudio é
 * preenchido com zeros até `T`. Esse padding **não é neutro**: a máscara de atenção do
 * grafo é calculada estaticamente (todas as posições válidas), então os zeros entram na
 * atenção. Medido em 10/09 (8 amostras com T_raw ≤ 198, mesmo encoder em dois buckets,
 * mesma cadeia downstream):
 *
 * | | bucket 200 | bucket 2000 (T_FIXED) |
 * |---|---|---|
 * | texto idêntico | — | 6/8 |
 * | CER médio vs FLEURS | 0,0435 | 0,0476 |
 * | pareado | — | **1 piora · 7 empata · 0 melhora** |
 * | tempo/amostra | 76 s | 745 s (**9,7×**) |
 *
 * A piora foi perda de palavra: `inland water can be…` → `inlandways can be…`.
 * Nenhuma amostra melhorou com padding — o erro é unidirecional.
 *
 * Regra: usar o **MENOR bucket que ainda caiba** (`T >= realFrames`). Se nenhum couber,
 * devolver o maior e deixar o chamador tratar (áudio longo).
 */
object GraniteNarBuckets {
    /** Buckets exportados, em ordem crescente. O app escolhe o menor que caiba. */
    val TODOS = intArrayOf(200, 400, 800, 1200, 1600, 2000)

    /** Menor bucket disponível que caiba em [realFrames]; senão o maior disponível. */
    fun escolhe(realFrames: Int, disponiveis: IntArray = TODOS): Int {
        val cabe = disponiveis.filter { it >= realFrames }
        return cabe.minOrNull() ?: disponiveis.max()
    }

    /** Nome do arquivo do encoder para um bucket (vazio se o bucket não é exportado). */
    fun encoderFile(t: Int): String = "granite-4.1-nar-encoder-t%04d-fp16.onnx".format(t)

    /** Nome do arquivo do projector para um bucket. */
    fun projectorFile(t: Int): String = "granite-4.1-nar-projector-t%04d-fp16.onnx".format(t)

    /**
     * Arquivos do pacote ANTIGO (bucket único, pesos embutidos) — ~1,34 GB que viram lixo
     * depois da troca. Só podem ser removidos quando o pacote novo está COMPLETO.
     */
    val LEGADOS = listOf(
        "granite-4.1-nar-encoder-fp16.onnx",
        "granite-4.1-nar-projector-fp16.onnx",
        "granite-4.1-nar-projector-fp16.onnx.data",
    )
}

/**
 * Variantes do LLM editor — o trade-off que o usuário escolhe.
 *
 * Mesmo modelo, mesma arquitetura; muda só a precisão dos pesos do LLM (`MatMulNBits`
 * weight-only, RTN, bloco 128 — ver `tools/granite/nar/`). É o análogo do
 * tiny/small/medium/turbo do Whisper: tamanho, velocidade e qualidade andam juntos.
 *
 * Os números vêm de medição no laboratório (contrato REAL do app: 2 entradas, `S`
 * dinâmico, sem padding; 82 amostras FLEURS em 5 idiomas; ORT CPU):
 *
 * | variante | `.data` | tempo/amostra | CER vs float (global) | pt_br |
 * |---|---|---|---|---|
 * | float     | 3.263 MB | 15,76 s | referência | referência |
 * | 8-bit     | 1.657 MB | (medindo) | (medindo) | (medindo) |
 * | 4-bit     |   841 MB |  3,64 s (4,3×) | +0,0015 | **+0,0054** |
 *
 * ⚠️ Os tempos são de **CPU no PC**; no aparelho os valores absolutos mudam. Por isso a UI
 * mostra o **fator** (4,3×), não segundos.
 *
 * ⚠️ O CER de pt_br do 4-bit **excede o critério do plano** (+0,005). A escolha por padrão é
 * a de maior qualidade; quem quiser velocidade assume a troca conscientemente.
 */
object GraniteNarLlm {
    /**
     * Uma variante do LLM.
     *
     * @param id identificador estável (usado na persistência — não renomear)
     * @param bytes tamanho do `.data` (o `.onnx` tem ~2,2 MB e é desprezível)
     * @param fatorVelocidade quanto mais rápido que o float (1,0 = float)
     * @param cerDelta diferença de CER contra o float, medido no corpus de 82 amostras
     * @param cerDeltaPtBr idem, restrito a português (idioma de uso)
     */
    data class Variante(
        val id: String,
        val rotulo: String,
        val resumo: String,
        val bytes: Long,
        val fatorVelocidade: Double,
        val cerDelta: Double,
        val cerDeltaPtBr: Double,
    ) {
        /** Nome do grafo no device (a variante carrega o próprio nome). */
        val onnx: String get() = "granite-4.1-nar-llm-$id.onnx"

        /** Nome do `.data` no device; é o `external_data.location` gravado no artefato. */
        val data: String get() = "$onnx.data"

        /**
         * Tamanho formatado para a UI (ex.: "3,3 GB" em pt-BR, "3.3 GB" em en-US).
         *
         * Usa a locale do SISTEMA de propósito: o usuário brasileiro espera vírgula decimal.
         * Fixar `Locale.US` deixaria "3.3 GB" — tecnicamente legível, mas estranho para ele.
         */
        fun tamanhoLegivel(): String =
            if (bytes >= 1_000_000_000L) "%.1f GB".format(bytes / 1_000_000_000.0)
            // Arredonda: `%d` truncava (433,7 MB aparecia como "433 MB").
            else "%d MB".format(kotlin.math.round(bytes / 1_000_000.0).toLong())

        /** Ganho de velocidade para a UI (ex.: "4,3× mais rápido"); vazio no float. */
        fun ganhoLegivel(): String =
            if (fatorVelocidade <= 1.05) "" else "%.1f× mais rápido".format(fatorVelocidade)

        /**
         * Efeito na qualidade para a UI. Devolve "" quando indistinguível do float,
         * e um aviso quando excede o critério do plano (+0,005) no idioma de uso.
         */
        fun qualidadeLegivel(): String = when {
            cerDelta <= 0.0005 && cerDeltaPtBr <= 0.0005 -> "mesma qualidade"
            cerDeltaPtBr > 0.005 -> "qualidade levemente menor (medido em pt-BR)"
            else -> "qualidade praticamente igual"
        }

        /** true quando o CER em pt-BR excede o critério de aceitação do plano. */
        fun excedeCriterioPtBr(): Boolean = cerDeltaPtBr > 0.005
    }

    val FLOAT = Variante(
        id = "fp16",
        rotulo = "Máxima qualidade",
        resumo = "Modelo completo, sem quantização",
        bytes = 3_263_500_288L,
        fatorVelocidade = 1.0,
        cerDelta = 0.0,
        cerDeltaPtBr = 0.0,
    )

    val OITO_BITS = Variante(
        id = "int8b-blk128",
        rotulo = "Equilibrado",
        resumo = "Pesos de 8 bits — metade do tamanho",
        bytes = 1_657_409_536L,
        // Medido em 11/09 (82 amostras, contrato do app): 15,30 s -> 6,29 s por amostra.
        fatorVelocidade = 2.4,
        // Delta de CER contra o float: -0,0002 global (levemente MELHOR) e +0,0012 em pt-BR.
        cerDelta = -0.0002,
        cerDeltaPtBr = 0.0012,
    )

    val QUATRO_BITS = Variante(
        id = "int4b-blk128",
        rotulo = "Mais leve e rápido",
        resumo = "Pesos de 4 bits — menor e ~4× mais rápido",
        bytes = 841_617_408L,
        fatorVelocidade = 4.3,
        cerDelta = 0.0015,
        cerDeltaPtBr = 0.0054,
    )

    /**
     * 2 bits por peso — **NÃO ENTRA NA UI**: medido e reprovado (11/09).
     *
     * O artefato é tecnicamente válido (`MatMulNBits={2: 281}`, carrega no ORT, contrato
     * correto) e é 3,0× mais rápido — mas a transcrição é destruída:
     *
     * | | float | 2-bit |
     * |---|---|---|
     * | texto idêntico | — | **0/82** |
     * | CER médio | 0,0323 | **0,7946** (24,6×) |
     * | amostras com CER > 0,30 | 0 | **82/82** |
     *
     * Isso não é "qualidade menor": é lixo. Fica aqui registrado para que ninguém tente de
     * novo sem saber — e para que a faixa pare no 4-bit, que ao menos preserva o conteúdo.
     */
    @Suppress("unused")
    val DOIS_BITS_REPROVADO = Variante(
        id = "int2b-blk128",
        rotulo = "Mínimo (reprovado)",
        resumo = "Pesos de 2 bits — não usar (texto destruído)",
        bytes = 433_721_344L,
        fatorVelocidade = 3.0,
        cerDelta = 0.7623,
        cerDeltaPtBr = 0.7850,
    )

    /**
     * Variantes oferecidas ao usuário, da maior qualidade para a menor.
     *
     * A faixa termina no 4-bit: o 2-bit foi medido e reprovado (ver [DOIS_BITS_REPROVADO]).
     */
    val TODAS = listOf(FLOAT, OITO_BITS, QUATRO_BITS)

    /** Variante com maior qualidade — usada quando nada foi escolhido. */
    val PADRAO = FLOAT

    fun porId(id: String?): Variante = TODAS.firstOrNull { it.id == id } ?: PADRAO
}

// ============================================================================
// Engine (parte Android): 3 sessões ONNX Runtime + download do pacote.
// ============================================================================

/**
 * Engine do Granite 4.1 NAR. Fluxo:
 * front-end (160-dim) -> encoder -> CTC collapse BPE -> projector ->
 * embedding lookup + interleave -> LLM editor -> collapse -> decode byte-level.
 */
object GraniteNarEngine {
    private const val TAG = "GraniteNarEngine"

    // Pacote v2: pesos compartilhados entre buckets + 3 variantes do LLM (o app baixa só a
    // escolhida pelo usuário). O pacote v1 (`.../models/granite/4.1-nar`) continua publicado
    // e intacto — a troca é reversível apontando de volta para ele.
    private const val PACKAGE_BASE_URL = "https://pub-6476622beda24c82875cb84f11f660ea.r2.dev/models/granite/4.1-nar/v2"

    // Pesos compartilhados por TODOS os buckets: o exporter renumera nomes de tensor entre
    // shapes (`onnx::Conv_3197` -> `onnx::Conv_3199`), mas o CONTEUDO e identico —
    // medido: 472/472 initializers iguais entre t0200 e t2000. Com um `.data` unico, cada
    // bucket extra custa ~719 KB (o grafo) em vez de ~1,04 GB (pesos embutidos).
    private const val ENCODER_PESOS = "encoder-pesos.data"
    private const val PROJECTOR_PESOS = "projector-pesos.data"
    // O par do LLM (.onnx + .data) vem de GraniteNarLlm.Variante — escolhido pelo usuário.
    private const val MEL_FILE = "nar_mel_filters.bin"
    private const val WINDOW_FILE = "nar_stft_window.bin"
    private const val VOCAB_FILE = "vocab.json"
    private const val EMBED_FILE = "nar_embed_tokens.bin"
    private const val CONFIG_FILE = "preprocessor_config.json"

    const val VOCAB_SIZE = 100352
    const val HIDDEN = 2048
    const val BLANK = 100257

    /** Bucket padrão (maior): usado no load e como teto de duração. */
    const val T_FIXED = 2000
    const val EMBEDDING_MULTIPLIER = 12.0f

    @Volatile private var encoderSession: OrtSession? = null

    /** Variante do LLM carregada em [llmSession] (ou null se nenhuma). */
    @Volatile private var llmVariante: GraniteNarLlm.Variante? = null

    /** Bucket da sessão de encoder aberta em [encoderSession] (-1 = nenhuma). */
    @Volatile private var encoderBucket: Int = -1
    @Volatile private var projectorSession: OrtSession? = null
    @Volatile private var projectorBucket: Int = -1
    @Volatile private var llmSession: OrtSession? = null
    @Volatile private var frontend: GraniteNarFrontend? = null
    @Volatile private var vocab: List<String>? = null
    @Volatile private var embed: java.nio.ByteBuffer? = null
    @Volatile private var lastErrorMessage: String = ""
    @Volatile private var onnxNativesLoaded: Boolean = false
    @Volatile private var lastLoadedBackend: GraniteExecutionBackend? = null

    // Parâmetros do load que o transcribe precisa para abrir buckets sob demanda.
    @Volatile private var sessDir: File? = null
    @Volatile private var sessBackend: GraniteExecutionBackend = GraniteExecutionBackend.CPU
    @Volatile private var sessRequireFullAcceleration: Boolean = true

    /** Variante do LLM escolhida pelo usuário, lida na carga. */
    @Volatile private var sessVariante: GraniteNarLlm.Variante = GraniteNarLlm.PADRAO

    fun lastError(): String = lastErrorMessage
    fun loadedBackend(): GraniteExecutionBackend? = lastLoadedBackend

    fun packageDir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "granite_nar_models")

    /**
     * Pesos e dados independentes de bucket (baixados uma vez), mais o par do LLM da
     * variante escolhida pelo usuário.
     */
    private fun arquivosComuns(variante: GraniteNarLlm.Variante = GraniteNarLlm.PADRAO): List<String> =
        listOf(
            ENCODER_PESOS, PROJECTOR_PESOS, variante.onnx, variante.data,
            MEL_FILE, WINDOW_FILE, VOCAB_FILE, EMBED_FILE, CONFIG_FILE,
        )

    /** Grafos de encoder e projector por bucket (pequenos: ~35 KB a ~719 KB cada). */
    private fun arquivosDosBuckets(): List<String> = GraniteNarBuckets.TODOS.flatMap {
        listOf(GraniteNarBuckets.encoderFile(it), GraniteNarBuckets.projectorFile(it))
    }

    private fun packageFiles(variante: GraniteNarLlm.Variante = GraniteNarLlm.PADRAO):
        List<Pair<String, String>> =
        (arquivosComuns(variante) + arquivosDosBuckets()).map { it to "$PACKAGE_BASE_URL/$it" }

    /**
     * Tamanho total do download do pacote (para o diálogo).
     *
     * Consulta os tamanhos reais no R2 via HEAD (fonte de verdade) e soma apenas
     * os arquivos que ainda faltam baixar — o mesmo cálculo usado em
     * [downloadPackage]. A soma fixa abaixo é apenas fallback quando a rede falha.
     */
    fun packageDownloadBytes(context: Context? = null): Long {
        val variante = context?.let { GraniteNarLlmSettings.selected(it) } ?: GraniteNarLlm.PADRAO
        val dir = context?.let { packageDir(it) }
        val todos = packageFiles(variante)
        val missing = dir?.let { d ->
            todos.filter { (name, _) ->
                val f = File(d, name)
                !(f.exists() && f.length() > 0L)
            }
        } ?: todos
        val remote = missing.sumOf { (_, url) ->
            runCatching {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                    requestMethod = "HEAD"
                }
                val len = conn.contentLengthLong.coerceAtLeast(0L)
                conn.disconnect()
                len
            }.getOrDefault(0L)
        }
        return if (remote > 0L) remote else fallbackPackageBytes(variante)
    }

    /**
     * Soma dos arquivos publicados (fallback quando os HEAD requests falham).
     *
     * Depende da VARIANTE escolhida: o par do LLM varia de 434 MB a 3,26 GB. O resto do
     * pacote é fixo — os pesos do encoder/projector são compartilhados por todos os buckets
     * (por isso 6 buckets custam só ~4,3 MB em grafos, não 6 GB).
     */
    private fun fallbackPackageBytes(variante: GraniteNarLlm.Variante): Long =
        1_085_993_664L +        // encoder-pesos.data   (compartilhado pelos 6 buckets)
            159_535_104L +      // projector-pesos.data (idem)
            variante.bytes +    // .data do LLM da variante escolhida
            2_300_000L +        // .onnx do LLM (varia ~40 KB entre variantes)
            82_240L + 2_048L + 1_612_704L + 411_041_792L + 289L +
            4_313_190L +        // 6 grafos de encoder (t0200..t2000)
            209_833L            // 6 grafos de projector

    fun packageComplete(context: Context): Boolean {
        val variante = GraniteNarLlmSettings.selected(context)
        val dir = packageDir(context)
        return packageFiles(variante).all { (name, _) ->
            val f = File(dir, name)
            f.exists() && f.length() > 0L
        }
    }

    /**
     * Busca o manifesto publicado (`<BASE>/manifest.json`) e devolve nome→sha256.
     *
     * Mapa vazio quando indisponível: um pacote legítimo antigo pode não ter hashes, e a
     * ausência não pode impedir o uso. Hashes presentes são sempre verificados.
     */
    private fun buscarManifest(): Map<String, String> = runCatching {
        val conn = (URL("$PACKAGE_BASE_URL/manifest.json").openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 20000
            setRequestProperty("User-Agent", "Mozilla/5.0")
        }
        val texto = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        GraniteNarManifest.parse(texto)
    }.getOrDefault(emptyMap())

    fun downloadPackage(context: Context, onProgress: (percent: Int, mb: Long) -> Unit) {
        val variante = GraniteNarLlmSettings.selected(context)
        val dir = packageDir(context).apply { mkdirs() }
        dir.listFiles()?.forEach { if (it.name.endsWith(".download")) it.delete() }
        val files = packageFiles(variante)
        // Fase 7 do plano: "SHA-256 antes da ativação". Sem isso um download truncado ou
        // corrompido seria ativado em silêncio, e o sintoma apareceria depois como
        // "o modelo não transcreve", sem pista da causa.
        val hashes = buscarManifest()
        var totalBytes = 0L
        var copiedBytes = 0L
        val missing = files.filter { (name, _) ->
            val f = File(dir, name)
            !(f.exists() && f.length() > 0L)
        }
        totalBytes = missing.sumOf { (_, url) ->
            runCatching {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                    requestMethod = "HEAD"
                }
                val len = conn.contentLengthLong.coerceAtLeast(0L)
                conn.disconnect()
                len
            }.getOrDefault(0L)
        }
        if (totalBytes <= 0L) totalBytes = fallbackPackageBytes(variante)
        for ((name, url) in missing) {
            val dest = File(dir, name)
            val temp = File(dir, "$name.download")
            // Download RETOMAVEL: se sobrou um `.download` de uma tentativa anterior, pede
            // apenas o restante (`Range: bytes=N-`). Com 4,6 GiB e rede instavel, reiniciar
            // do zero a cada queda inviabiliza a instalacao.
            var jaTemos = if (temp.isFile) temp.length() else 0L
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 120000
                if (jaTemos > 0L) setRequestProperty("Range", "bytes=$jaTemos-")
            }
            val codigo = connection.responseCode
            if (codigo == HttpURLConnection.HTTP_OK && jaTemos > 0L) {
                // O servidor ignorou o Range e vai mandar tudo: recomeca para nao concatenar.
                jaTemos = 0L
                temp.delete()
            }
            val restante = connection.contentLengthLong.coerceAtLeast(0L)
            connection.inputStream.use { input ->
                FileOutputStream(temp, jaTemos > 0L).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var copied = jaTemos
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        copiedBytes += read
                        if (restante > 0L) {
                            val percent = ((copiedBytes * 100L) / totalBytes.coerceAtLeast(1L)).coerceIn(0L, 100L).toInt()
                            onProgress(percent, copiedBytes / 1048576L)
                        } else {
                            onProgress(-1, copiedBytes / 1048576L)
                        }
                    }
                }
            }
            connection.disconnect()
            // Verifica ANTES de tornar o arquivo definitivo: o `.download` só vira oficial se
            // conferir. Falha = apaga e aborta com mensagem clara (em vez de ativar em
            // silêncio um modelo que não transcreveria).
            val esperado = hashes[name]
            if (!GraniteNarManifest.confere(temp, esperado)) {
                temp.delete()
                throw IllegalStateException(
                    "Download corrompido: $name não confere com o SHA-256 publicado. " +
                        "Verifique a conexão e tente novamente."
                )
            }
            if (dest.exists()) dest.delete()
            if (!temp.renameTo(dest)) {
                temp.copyTo(dest, overwrite = true)
                temp.delete()
            }
        }
        // Depois de o pacote novo estar completo, os arquivos do pacote antigo viram lixo
        // (~1,34 GB). `limparPacoteLegado` confere `packageComplete` antes de apagar —
        // se algo faltou no download, não toca em nada.
        limparPacoteLegado(context)
    }

    /**
     * Remove os arquivos do pacote antigo **somente** quando o pacote novo está completo.
     *
     * Devolve quantos bytes foram liberados. É chamada depois de um download bem-sucedido;
     * se o pacote novo estiver incompleto, não toca em nada (o usuário não pode ficar sem
     * modelo nenhum por causa de uma limpeza).
     */
    fun limparPacoteLegado(context: Context): Long {
        val dir = packageDir(context)
        if (!packageComplete(context)) {
            return 0L
        }
        // Variantes do LLM que NÃO estão em uso viram lixo ao trocar (0,4 a 3 GB cada).
        val ativa = GraniteNarLlmSettings.selected(context)
        val obsoletos = GraniteNarBuckets.LEGADOS +
            GraniteNarLlm.TODAS.filter { it.id != ativa.id }.flatMap { listOf(it.onnx, it.data) }
        var liberados = 0L
        for (nome in obsoletos) {
            val f = File(dir, nome)
            if (f.isFile) {
                val tamanho = f.length()
                if (f.delete()) liberados += tamanho
            }
        }
        return liberados
    }

    /** Carrega as libs nativas do ONNX Runtime (mesma ponte do GraniteEngine). */
    private fun loadOnnxRuntimeNatives(onLog: (String) -> Unit) {
        if (onnxNativesLoaded) return
        val libDir = System.getProperty("sig.native.library.dir")
            ?: throw IllegalStateException("diretório de libs nativas não configurado")
        val dir = File(libDir)
        for (name in listOf("libonnxruntime.so", "libonnxruntime4j_jni.so")) {
            val lib = File(dir, name)
            check(lib.isFile) { "lib nativa ausente: ${lib.absolutePath}" }
            try {
                System.load(lib.absolutePath)
                onLog("nativo carregado: $name")
            } catch (e: Throwable) {
                if (e.message?.contains("already loaded", ignoreCase = true) != true) throw e
            }
        }
        onnxNativesLoaded = true
    }

    private fun createSessionOptions(
        backend: GraniteExecutionBackend,
        requireFullAcceleration: Boolean,
    ): OrtSession.SessionOptions {
        val options = OrtSession.SessionOptions()
        options.setOptimizationLevel(
            if (backend == GraniteExecutionBackend.CPU) {
                OrtSession.SessionOptions.OptLevel.BASIC_OPT
            } else {
                // O QNN EP faz a própria conversão/partição; transformações ORT
                // agressivas podem criar padrões que o backend não reconhece.
                OrtSession.SessionOptions.OptLevel.NO_OPT
            }
        )
        if (!backend.accelerated) return options

        val qnnBackend = requireNotNull(backend.qnnBackend)
        val qnnConfig = mutableMapOf(
            "backend_path" to if (qnnBackend == "gpu") "libQnnGpu.so" else "libQnnHtp.so",
            "offload_graph_io_quantization" to "1",
        )
        if (qnnBackend == "htp") {
            qnnConfig["enable_htp_fp16_precision"] = "1"
            // O NAR abre três grafos por execução. Mode 1 privilegia preparação
            // mais curta; burst reduz latência durante a inferência interativa.
            qnnConfig["htp_graph_finalization_optimization_mode"] = "1"
            qnnConfig["htp_performance_mode"] = "burst"
        }
        options.addQnn(qnnConfig)
        // Sem isto, uma sessão rotulada NPU/GPU pode executar silenciosamente
        // operadores (ou o grafo inteiro) no CPU EP, invalidando a comparação.
        if (requireFullAcceleration) {
            options.addConfigEntry("session.disable_cpu_ep_fallback", "1")
        }
        return options
    }

    private fun prepareAcceleratedBackend(
        context: Context,
        backend: GraniteExecutionBackend,
        onLog: (String) -> Unit,
    ) {
        val qnnBackend = requireNotNull(backend.qnnBackend)
        check(QairtDependencyManager.isInstalled(context)) {
            "Componentes QAIRT/QNN não instalados. Selecione GPU ou NPU para baixá-los."
        }
        val htpArch = if (qnnBackend == "htp") {
            checkNotNull(QairtDependencyManager.htpArchitecture(context)) {
                "Arquitetura HTP não detectada neste aparelho."
            }.also { onLog("HTP arch detectada: v$it") }
        } else null
        QairtDependencyManager.loadQnnNatives(context, qnnBackend, htpArch)
        onLog("libs QNN carregadas: backend=$qnnBackend ${if (htpArch != null) "arch=v$htpArch" else ""}")
    }

    /** Cria uma sessão ORT a partir de um arquivo do pacote, com o log do padrão. */
    private fun criarSessao(
        dir: File,
        fileName: String,
        rotulo: String,
        backend: GraniteExecutionBackend,
        requireFullAcceleration: Boolean,
        onLog: (String) -> Unit,
    ): OrtSession {
        val env = OrtEnvironment.getEnvironment()
        val startedAt = android.os.SystemClock.elapsedRealtime()
        val created = createSessionOptions(backend, requireFullAcceleration).use { options ->
            env.createSession(File(dir, fileName).absolutePath, options)
        }
        val elapsed = android.os.SystemClock.elapsedRealtime() - startedAt
        onLog("ONNX $rotulo criado (${backend.reportLabel}) em ${elapsed}ms")
        return created
    }

    /**
     * Sessão do encoder para [bucket], reutilizando a que já está aberta quando o bucket
     * coincide.
     *
     * ⚠️ Trocar de bucket **fecha** a sessão anterior. Cada encoder ocupa ~1 GB de memória
     * nativa; manter os 6 vivos estouraria o app. É a mesma classe de problema que travou o
     * laboratório no PC (4 sessões de encoder simultâneas -> 13 GB de RSS e a máquina
     * parou de progredir sem erro).
     */
    private fun encoderPara(
        dir: File,
        bucket: Int,
        backend: GraniteExecutionBackend,
        requireFullAcceleration: Boolean,
        onLog: (String) -> Unit,
    ): OrtSession {
        encoderSession?.let { if (encoderBucket == bucket) return it }
        encoderSession?.close()
        encoderSession = null
        encoderBucket = -1
        val nova = criarSessao(dir, GraniteNarBuckets.encoderFile(bucket), "encoder t$bucket",
            backend, requireFullAcceleration, onLog)
        encoderSession = nova
        encoderBucket = bucket
        return nova
    }

    /** Sessão do projector para [bucket]; mesma política de troca do encoder. */
    private fun projectorPara(
        dir: File,
        bucket: Int,
        backend: GraniteExecutionBackend,
        requireFullAcceleration: Boolean,
        onLog: (String) -> Unit,
    ): OrtSession {
        projectorSession?.let { if (projectorBucket == bucket) return it }
        projectorSession?.close()
        projectorSession = null
        projectorBucket = -1
        val nova = criarSessao(dir, GraniteNarBuckets.projectorFile(bucket), "projector t$bucket",
            backend, requireFullAcceleration, onLog)
        projectorSession = nova
        projectorBucket = bucket
        return nova
    }

    /**
     * Sessão do LLM para [variante], reutilizando a aberta quando coincide.
     *
     * Trocar de variante **fecha** a anterior: as três ocupam de 800 MB a 3 GB de memória
     * nativa cada — manter duas vivas é exatamente a classe de problema que travou o
     * laboratório (sessões simultâneas estourando a RAM, sem erro visível).
     */
    private fun llmPara(
        dir: File,
        variante: GraniteNarLlm.Variante,
        backend: GraniteExecutionBackend,
        requireFullAcceleration: Boolean,
        onLog: (String) -> Unit,
    ): OrtSession {
        llmSession?.let { if (llmVariante?.id == variante.id) return it }
        llmSession?.close()
        llmSession = null
        llmVariante = null
        val nova = criarSessao(dir, variante.onnx, "llm editor (${variante.rotulo})",
            backend, requireFullAcceleration, onLog)
        llmSession = nova
        llmVariante = variante
        return nova
    }

    private fun createSessions(
        dir: File,
        backend: GraniteExecutionBackend,
        requireFullAcceleration: Boolean,
        onLog: (String) -> Unit,
    ) {
        // Abre o bucket PADRÃO (T_FIXED) na carga: valida que encoder e projector abrem e
        // mantém o comportamento anterior. Buckets menores entram sob demanda no transcribe.
        encoderPara(dir, T_FIXED, backend, requireFullAcceleration, onLog)
        projectorPara(dir, T_FIXED, backend, requireFullAcceleration, onLog)
        llmPara(dir, sessVariante, backend, requireFullAcceleration, onLog)
    }

    fun load(
        context: Context,
        backend: GraniteExecutionBackend = GraniteExecutionBackend.CPU,
        requireFullAcceleration: Boolean = true,
        onLog: (String) -> Unit = {},
        onFallbackPrompt: (String) -> Boolean = { true },
    ): Boolean {
        return try {
            lastLoadedBackend = null
            if (!NativeDependencyManager.activateIfInstalled(context)) {
                lastErrorMessage = "Componentes nativos do SIG não instalados. Baixe-os na abertura do app e tente novamente."
                return false
            }
            loadOnnxRuntimeNatives(onLog)
            release()

            val dir = packageDir(context)
            // Obrigatórios: os dados comuns + o bucket PADRÃO. Buckets menores são
            // opcionais — o app escolhe o menor que estiver instalado e caiba no áudio.
            val obrigatorios = arquivosComuns(GraniteNarLlmSettings.selected(context)) +
                listOf(
                    GraniteNarBuckets.encoderFile(T_FIXED),
                    GraniteNarBuckets.projectorFile(T_FIXED),
                )
            for (f in obrigatorios) {
                if (!File(dir, f).exists()) {
                    lastErrorMessage = "arquivo do modelo ausente: $f"
                    return false
                }
            }

            frontend = GraniteNarFrontend(
                GraniteBinarySupport.readFloatBinary(File(dir, MEL_FILE)),
                GraniteBinarySupport.readFloatBinary(File(dir, WINDOW_FILE)),
            )
            vocab = parseVocabJson(File(dir, VOCAB_FILE).readText())
            // nar_embed_tokens.bin tem ~411 MB (tabela de embeddings fp16). readBytes()
            // carregava tudo no heap Java (growth limit 256 MB) -> OutOfMemoryError.
            // mmap (MappedByteBuffer) usa memória nativa/arquivo mapeado, fora do
            // heap, com páginas carregadas sob demanda (fix do OOM do NAR, 29/08).
            java.io.RandomAccessFile(File(dir, EMBED_FILE), "r").use { raf ->
                embed = raf.channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, raf.length())
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            }

            if (backend.accelerated) {
                try {
                    prepareAcceleratedBackend(context, backend, onLog)
                    // Guarda os parâmetros: o transcribe abre buckets SOB DEMANDA.
                    sessDir = dir
                    sessBackend = backend
                    sessRequireFullAcceleration = requireFullAcceleration
                    sessVariante = GraniteNarLlmSettings.selected(context)
                    createSessions(dir, backend, requireFullAcceleration, onLog)
                    lastLoadedBackend = backend
                    return true
                } catch (acceleratedError: Throwable) {
                    closeSessions()
                    val reason = GraniteBinarySupport.describeError(acceleratedError)
                    onLog("Sessões ${backend.reportLabel} rejeitadas: $reason")
                    if (!onFallbackPrompt(reason)) {
                        lastErrorMessage =
                            "O acelerador ${backend.label} não executa integralmente o Granite 4.1 NAR " +
                                "e o fallback para CPU foi recusado: $reason"
                        return false
                    }
                    onLog("Fallback explícito para CPU aceito")
                }
            }

            sessDir = dir
            sessBackend = GraniteExecutionBackend.CPU
            sessRequireFullAcceleration = false
            sessVariante = GraniteNarLlmSettings.selected(context)
            createSessions(
                dir,
                GraniteExecutionBackend.CPU,
                requireFullAcceleration = false,
                onLog = onLog,
            )
            lastLoadedBackend = GraniteExecutionBackend.CPU
            true
        } catch (e: Throwable) {
            closeSessions()
            lastLoadedBackend = null
            lastErrorMessage = GraniteBinarySupport.describeError(e)
            Log.e(TAG, "load failed", e)
            false
        }
    }

    /** Escreve embed_tokens[token] diretamente no buffer final, sem FloatArray temporário. */
    private fun copyEmbedding(token: Int, destination: FloatArray, destinationOffset: Int) {
        val buf = embed ?: throw IllegalStateException("embedding não carregado")
        val base = token * HIDDEN * 2L
        for (i in 0 until HIDDEN) {
            // MappedByteBuffer LITTLE_ENDIAN: getShort(pos) lê os 2 bytes fp16.
            val h = buf.getShort((base + 2L * i).toInt())
            destination[destinationOffset + i] = HalfFloat.toFloat(h)
        }
    }

    /**
     * Transcreve um WAV 16 kHz mono (single shot).
     *
     * O bucket do encoder/projector é escolhido pelo tamanho do áudio (o menor que caiba —
     * ver [GraniteNarBuckets]); o teto de duração continua sendo [T_FIXED] frames.
     */
    fun transcribeFile(
        wavFile: File,
        onProgress: (Int) -> Unit = {},
        onLog: (String) -> Unit = {},
    ): String {
        try {
            return transcribeFileInner(wavFile, onProgress, onLog)
        } catch (e: Throwable) {
            lastErrorMessage = GraniteBinarySupport.describeError(e)
            Log.e(TAG, "transcribeFile failed", e)
            throw e
        }
    }

    private fun transcribeFileInner(
        wavFile: File,
        onProgress: (Int) -> Unit,
        onLog: (String) -> Unit,
    ): String {
        val fe = frontend ?: throw IllegalStateException("front-end não carregado")
        val pieces = vocab ?: throw IllegalStateException("vocab não carregado")
        val dir = sessDir ?: throw IllegalStateException("pacote não carregado")

        val totalStartedAt = android.os.SystemClock.elapsedRealtime()
        var stageStartedAt = totalStartedAt
        fun markStage(name: String) {
            val now = android.os.SystemClock.elapsedRealtime()
            onLog("NAR etapa $name: ${now - stageStartedAt}ms")
            stageStartedAt = now
        }

        val wav = GraniteBinarySupport.readWav16kMono(wavFile, TAG) ?: throw IllegalStateException("WAV inválido")
        val features = fe.compute(wav)
        markStage("frontend")
        if (features.frames == 0) return ""
        onLog("NAR entrada: samples=${wav.size} frames=${features.frames}")
        if (features.frames > T_FIXED) {
            throw IllegalStateException("áudio muito longo para o Granite 4.1 NAR nesta versão (máx ~${T_FIXED * 160 / 16000}s)")
        }
        val realFrames = features.frames

        // Bucket = MENOR que caiba. O padding até um T maior NÃO é neutro: a máscara de
        // atenção do grafo é estática, então os zeros entram na atenção. Medido em 10/09:
        // 1 piora / 7 empata / 0 melhora, com perda de palavra, e 9,7x mais lento.
        val bucket = GraniteNarBuckets.escolhe(realFrames)
        val enc = encoderPara(dir, bucket, sessBackend, sessRequireFullAcceleration, onLog)
        val proj = projectorPara(dir, bucket, sessBackend, sessRequireFullAcceleration, onLog)
        // A variante pode ter sido trocada nas configurações: `llmPara` fecha a anterior.
        val llm = llmPara(dir, sessVariante, sessBackend, sessRequireFullAcceleration, onLog)
        onLog("NAR bucket: frames=$realFrames -> T=$bucket")

        // Pad até o BUCKET escolhido (não mais até T_FIXED fixo).
        val input = FloatArray(bucket * GraniteNarFrontend.INPUT_DIM)
        for (r in 0 until realFrames) {
            System.arraycopy(features.data, r * features.dim, input, r * features.dim, features.dim)
        }
        val env = OrtEnvironment.getEnvironment()
        val inputTensor = OnnxTensor.createTensor(env, java.nio.FloatBuffer.wrap(input), longArrayOf(1L, bucket.toLong(), GraniteNarFrontend.INPUT_DIM.toLong()))
        val encOut = enc.run(mapOf("input_features" to inputTensor), setOf("encoder_bpe_logits", "multilayer_features"))
        inputTensor.close()
        markStage("encoder")

        val bpeLogits = (encOut["encoder_bpe_logits"].get() as OnnxTensor).floatBuffer
        val multilayer = (encOut["multilayer_features"].get() as OnnxTensor).floatBuffer
        // NÃO copiar bpeLogits para FloatArray (~200 MB) — o collapse lê direto do
        // FloatBuffer (evita OOM no heap Java de 256 MB, fix 29/08).
        // ⚠️ O collapse PRECISA rodar ANTES do close() do tensor (o buffer fica inválido).
        val multilayerArr = FloatArray(multilayer.remaining()).also { multilayer.get(it) }
        onProgress(25)

        // CTC collapse no encoder (valid frames = ceil(realFrames/4)).
        val validBpe = (realFrames + 3) / 4
        val ctcTokens = GraniteNarCtc.collapseLogits(bpeLogits, validBpe, VOCAB_SIZE)
        encOut.close()
        markStage("ctc encoder + cópia projector")
        onProgress(40)

        // Projector -> audio_embeds [402, 2048]; válidos = realFrames//5; /12 (scale).
        val projTensor = OnnxTensor.createTensor(env, java.nio.FloatBuffer.wrap(multilayerArr), longArrayOf(1L, bucket.toLong(), 4096L))
        val projOut = proj.run(mapOf("multilayer_features" to projTensor), setOf("audio_embeds"))
        projTensor.close()
        val audioEmbeds = (projOut["audio_embeds"].get() as OnnxTensor).floatBuffer
        markStage("projector")
        onProgress(55)

        val validAudio = realFrames / 5
        // Interleave + embedding lookup.
        val slots = GraniteNarInterleave.buildSlots(ctcTokens, BLANK)
        val S = validAudio + slots.size
        onLog(
            "NAR sequência: ctc_tokens=${ctcTokens.size} valid_audio=$validAudio " +
                "slots=${slots.size} llm_tokens=$S",
        )
        val inputsEmbeds = FloatArray(S * HIDDEN)
        // audio_embeds (validAudio vetores de 2048), divididos por 12.
        for (i in 0 until validAudio) {
            val base = i * HIDDEN
            for (d in 0 until HIDDEN) inputsEmbeds[base + d] = audioEmbeds.get(base + d) / EMBEDDING_MULTIPLIER
        }
        projOut.close()
        // text embeds (slots) no offset validAudio.
        for (t in slots.indices) {
            val base = (validAudio + t) * HIDDEN
            copyEmbedding(slots[t], inputsEmbeds, base)
        }
        markStage("montagem de embeddings")
        onProgress(70)

        // LLM editor.
        val posIds = LongArray(S) { it.toLong() }
        val embedsTensor = OnnxTensor.createTensor(env, java.nio.FloatBuffer.wrap(inputsEmbeds), longArrayOf(1L, S.toLong(), HIDDEN.toLong()))
        val posTensor = OnnxTensor.createTensor(env, java.nio.LongBuffer.wrap(posIds), longArrayOf(1L, S.toLong()))
        val llmOut = llm.run(mapOf("inputs_embeds" to embedsTensor, "position_ids" to posTensor), setOf("logits"))
        embedsTensor.close()
        posTensor.close()
        val logits = (llmOut["logits"].get() as OnnxTensor).floatBuffer
        markStage("llm editor")
        onProgress(90)

        // Collapse direto da fatia textual do tensor do ORT: evita copiar todos
        // os logits e depois criar uma segunda FloatArray para o mesmo conteúdo.
        val textStart = validAudio
        val textFrames = S - validAudio
        val pred = GraniteNarCtc.collapseLogits(logits, textFrames, VOCAB_SIZE, frameOffset = textStart)
        llmOut.close()

        val decoded = decodeByteLevel(pred, pieces)
        markStage("ctc final + decode")
        onLog("NAR inferência total: ${android.os.SystemClock.elapsedRealtime() - totalStartedAt}ms")
        onProgress(100)
        return decoded.trim()
    }

    /** Decodifica tokens BPE byte-level (id = byte-stand-in) para UTF-8. */
    private fun decodeByteLevel(ids: IntArray, pieces: List<String>): String {
        val cm = GraniteBinarySupport.byteLevelCharToByte()
        val out = java.io.ByteArrayOutputStream()
        for (id in ids) {
            if (id < 0 || id >= pieces.size) continue
            val piece = pieces[id]
            for (ch in piece) {
                val b = cm.getOrNull(ch.code) ?: -1
                if (b >= 0) out.write(b)
            }
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    private fun closeSessions() {
        try { encoderSession?.close() } catch (_: Throwable) {}
        try { projectorSession?.close() } catch (_: Throwable) {}
        try { llmSession?.close() } catch (_: Throwable) {}
        encoderSession = null
        encoderBucket = -1
        projectorSession = null
        projectorBucket = -1
        llmSession = null
        llmVariante = null
    }

    fun release() {
        closeSessions()
        frontend = null
        vocab = null
        embed = null
        lastLoadedBackend = null
    }

    /** Parseia vocab.json (array de strings, ids 0..N-1) ou objeto {piece: id}. */
    private fun parseVocabJson(json: String): List<String> {
        val trimmed = json.trim()
        if (trimmed.startsWith("[")) {
            val pieces = mutableListOf<String>()
            val regex = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"")
            for (match in regex.findAll(trimmed)) {
                val raw = match.groupValues[1]
                pieces.add(raw.replace("\\\"", "\"").replace("\\\\", "\\"))
            }
            return pieces
        }
        // Objeto { "piece": id }.
        val map = sortedMapOf<Int, String>()
        val regex = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"\\s*:\\s*(\\d+)")
        for (match in regex.findAll(trimmed)) {
            val piece = match.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\")
            map[match.groupValues[2].toInt()] = piece
        }
        return map.values.toList()
    }
}
