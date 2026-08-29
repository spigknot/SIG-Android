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
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Granite Speech 5.0 TurboCTC — pipeline puro (sem Android).
 *
 * Porta fiel do Space oficial da IBM `granite-speech-streaming-webgpu`
 * (front-end `CtcFrontend`, `agc`, `CtcDecoder`, `ctcCollapse`), que foi
 * verificado pela IBM "bit-for-bit" contra o Python.
 *
 * Apenas decodificação; o encoder ONNX é executado pela [GraniteEngine].
 * Todas as classes aqui são puras (sem Android) e testáveis na JVM.
 */

/** Configuração do front-end (frontend_config.json do Space da IBM). */
data class GraniteFrontendConfig(
    val sampleRate: Int = 16000,
    val nFft: Int = 512,
    val winLength: Int = 400,
    val hopLength: Int = 160,
    val nMels: Int = 80,
    val stackFactor: Int = 2,
    val deltas: Boolean = true,
    val deltaWinLength: Int = 3,
    val logmelFloorDb: Double = 8.0,
    val numSpecialTokens: Int = 1,
    val inputDim: Int = 320,
    val blankId: Int = 0,
    val padMultiple: Int = 512,
    val subsampleFactor: Int = 4,
) {
    companion object {
        /** Carrega do JSON baixado junto com o modelo. */
        fun fromJson(json: String): GraniteFrontendConfig {
            // Parse minimalista (o arquivo é pequeno e fixo).
            fun num(key: String): Int {
                val m = Regex("\"$key\"\\s*:\\s*(-?\\d+)").find(json) ?: error("frontend_config sem $key")
                return m.groupValues[1].toInt()
            }
            fun flt(key: String): Double {
                val m = Regex("\"$key\"\\s*:\\s*(-?[\\d.]+)").find(json) ?: error("frontend_config sem $key")
                return m.groupValues[1].toDouble()
            }
            fun bool(key: String): Boolean {
                val m = Regex("\"$key\"\\s*:\\s*(true|false)").find(json) ?: error("frontend_config sem $key")
                return m.groupValues[1] == "true"
            }
            return GraniteFrontendConfig(
                sampleRate = num("sample_rate"),
                nFft = num("n_fft"),
                winLength = num("win_length"),
                hopLength = num("hop_length"),
                nMels = num("n_mels"),
                stackFactor = num("stack_factor"),
                deltas = bool("deltas"),
                deltaWinLength = num("delta_win_length"),
                logmelFloorDb = flt("logmel_floor_db"),
                numSpecialTokens = num("num_special_tokens"),
                inputDim = num("input_dim"),
                blankId = num("blank_id"),
                padMultiple = num("pad_multiple"),
                subsampleFactor = num("subsample_factor"),
            )
        }
    }
}

/** AGC — port do sidecar da IBM. Não cosmético: o front-end normaliza pelo pico global. */
object GraniteAgc {
    fun apply(x: FloatArray, sampleRate: Int = 16000, target: Double = 0.12, winMs: Double = 150.0, maxGain: Double = 20.0): FloatArray {
        if (x.isEmpty()) return x
        val n = x.size
        val win = max(1, ((sampleRate * winMs) / 1000.0).toInt())

        val sq = FloatArray(n) { x[it] * x[it] }
        val ms = movingAvg(sq, win)

        val g = FloatArray(n)
        for (i in 0 until n) {
            val env = sqrt(ms[i] + 1e-9)
            g[i] = min(max(target / max(env, 1e-4), 0.0), maxGain).toFloat()
        }
        val gs = movingAvg(g, win)

        val y = FloatArray(n)
        var peak = 0f
        for (i in 0 until n) {
            y[i] = x[i] * gs[i]
            val a = kotlin.math.abs(y[i])
            if (a > peak) peak = a
        }
        if (peak > 0.97f) {
            val k = 0.97f / peak
            for (i in 0 until n) y[i] *= k
        }
        return y
    }

    private fun movingAvg(x: FloatArray, win: Int): FloatArray {
        val n = x.size
        val out = FloatArray(n)
        var sum = 0.0
        for (i in 0 until n) {
            sum += x[i]
            if (i >= win) sum -= x[i - win]
            out[i] = (sum / min(i + 1, win)).toFloat()
        }
        return out
    }
}

/**
 * Front-end: STFT (radix-2 FFT iterativa) + mel + log10/floor + deltas + stacking.
 * Porta fiel do `CtcFrontend.compute` do Space da IBM.
 */
class GraniteFrontend(
    val config: GraniteFrontendConfig,
    private val melFilters: FloatArray, // [nMels * nFreqs], mel-major
    private val window: FloatArray,     // nFft, já centralizado pelo exporter
) {
    private val nFreqs = config.nFft / 2 + 1
    private val fft = Radix2Fft(config.nFft)
    private val eps = 1e-10

    /** Frames de saída (antes do pad_multiple) para nSamples de áudio. */
    fun frameCount(nSamples: Int): Int {
        val melFrames = floor(nSamples.toDouble() / config.hopLength).toInt()
        return config.stackFactor * ((melFrames + config.stackFactor - 1) / config.stackFactor)
    }

    /** Retorna `{ data, frames, dim }` — data [frames, dim] row-major. */
    fun compute(wav: FloatArray): GraniteFeatures {
        val hop = config.hopLength
        val nFft = config.nFft
        val nMels = config.nMels
        val stack = config.stackFactor
        val nFrames = frameCount(wav.size)
        if (nFrames == 0) return GraniteFeatures(FloatArray(0), 0, config.inputDim)

        // Right-pad o waveform para completar o último grupo de stack.
        val need = (nFrames - 1) * hop + 1
        val x: FloatArray = if (wav.size < need) {
            FloatArray(need).also { System.arraycopy(wav, 0, it, 0, wav.size) }
        } else wav

        // torch.stft(center=True, pad_mode='reflect')
        val half = nFft shr 1
        val padded = FloatArray(x.size + nFft)
        System.arraycopy(x, 0, padded, half, x.size)
        for (i in 0 until half) {
            padded[half - 1 - i] = x[min(i + 1, x.size - 1)]
            padded[half + x.size + i] = x[max(x.size - 2 - i, 0)]
        }

        // Mel spectrogram, mel-major ([nMels][nFrames]).
        val mel = FloatArray(nMels * nFrames)
        val re = FloatArray(nFft)
        val im = FloatArray(nFft)
        val power = FloatArray(nFreqs)
        for (t in 0 until nFrames) {
            val off = t * hop
            for (i in 0 until nFft) {
                re[i] = padded[off + i] * window[i]
                im[i] = 0f
            }
            fft.run(re, im)
            for (k in 0 until nFreqs) power[k] = re[k] * re[k] + im[k] * im[k]
            for (m in 0 until nMels) {
                val fb = m * nFreqs
                var acc = 0f
                for (k in 0 until nFreqs) acc += melFilters[fb + k] * power[k]
                mel[m * nFrames + t] = acc
            }
        }

        // log10 com floor relativo ao pico global, depois /4 + 1.
        var mx = Double.NEGATIVE_INFINITY
        for (i in 0 until mel.size) {
            val v = log10(max(mel[i].toDouble(), eps))
            mel[i] = v.toFloat()
            if (v > mx) mx = v
        }
        val floor = mx - config.logmelFloorDb
        for (i in 0 until mel.size) mel[i] = (max(mel[i].toDouble(), floor) / 4.0 + 1.0).toFloat()

        // Deltas: (x[t+1] - x[t-1]) / 2 com borda replicate.
        val nCh = if (config.deltas) nMels * 2 else nMels
        val chans = FloatArray(nCh * nFrames)
        System.arraycopy(mel, 0, chans, 0, mel.size)
        if (config.deltas) {
            for (m in 0 until nMels) {
                val src = m * nFrames
                val dst = (nMels + m) * nFrames
                for (t in 0 until nFrames) {
                    val prev = mel[src + max(t - 1, 0)]
                    val next = mel[src + min(t + 1, nFrames - 1)]
                    chans[dst + t] = (next - prev) / 2f
                }
            }
        }

        // Stack: (nCh, nFrames) -> (nFrames/stack, stack*nCh).
        val outFrames = nFrames / stack
        val dim = stack * nCh
        val data = FloatArray(outFrames * dim)
        for (j in 0 until outFrames) {
            for (k in 0 until stack) {
                val t = j * stack + k
                val base = j * dim + k * nCh
                for (c in 0 until nCh) data[base + c] = chans[c * nFrames + t]
            }
        }
        return GraniteFeatures(data, outFrames, dim)
    }
}

data class GraniteFeatures(val data: FloatArray, val frames: Int, val dim: Int)

/**
 * Construção da attention_mask do CTC (função PURA, testável na JVM).
 *
 * Convenção HuggingFace/transformers (conferida no fonte do modelo
 * `modeling_granite_speech5.py`): 1 = frame REAL (atendido), 0 = padding
 * (mascarado com -inf no bloco de atenção). A máscara é int64 (o grafo
 * espera `attention_mask` [1, frames] int64).
 */
object GraniteMask {
    fun build(windowFrames: Int, windowLen: Int): LongArray {
        require(windowLen in 0..windowFrames) { "windowLen $windowLen fora de [0, $windowFrames]" }
        return LongArray(windowFrames) { if (it < windowLen) 1L else 0L }
    }
}

/** FFT radix-2 iterativa (Cooley-Tukey, DIT) — port do JS da IBM. */
class Radix2Fft(private val size: Int) {
    private val rev = IntArray(size)
    private val cos = FloatArray(size / 2)
    private val sin = FloatArray(size / 2)

    init {
        var levels = 0
        var s = size
        while (s > 1) { s = s shr 1; levels++ }
        require(1 shl levels == size) { "FFT size $size não é potência de 2" }
        for (i in 0 until size) {
            var r = 0
            for (b in 0 until levels) r = r or (((i shr b) and 1) shl (levels - 1 - b))
            rev[i] = r
        }
        for (i in 0 until size / 2) {
            cos[i] = cos(-2.0 * PI * i / size).toFloat()
            sin[i] = sin(-2.0 * PI * i / size).toFloat()
        }
    }

    /** Transforma re/im in place (ambos com length = size). */
    fun run(re: FloatArray, im: FloatArray) {
        val n = size
        for (i in 0 until n) {
            val j = rev[i]
            if (j > i) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val half = len shr 1
            val step = n / len
            for (i in 0 until n step len) {
                for (j in 0 until half) {
                    val k = j * step
                    val c = cos[k]
                    val s = sin[k]
                    val reT = re[i + j + half] * c - im[i + j + half] * s
                    val imT = re[i + j + half] * s + im[i + j + half] * c
                    re[i + j + half] = re[i + j] - reT
                    im[i + j + half] = im[i + j] - imT
                    re[i + j] += reT
                    im[i + j] += imT
                }
            }
            len = len shl 1
        }
    }
}

/** Greedy CTC collapse + decode ByteLevel GPT-2. Port do `CtcDecoder`/`ctcCollapse`. */
class GraniteDecoder(
    private val pieces: List<String>,
    private val numSpecialTokens: Int = 1,
) {
    private val encoded: Array<ByteArray>
    private val charToByte: IntArray

    init {
        charToByte = byteLevelCharToByte()
        encoded = Array(pieces.size) { i ->
            val piece = pieces[i]
            val bytes = ByteArray(piece.length)
            var n = 0
            for (ch in piece) {
                val b = charToByte.getOrNull(ch.code) ?: -1
                if (b >= 0) bytes[n++] = b.toByte()
            }
            bytes.copyOf(n)
        }
    }

    /** Decodifica ids de conteúdo (>= numSpecialTokens) para texto UTF-8. */
    fun decode(ids: IntArray): String {
        var total = 0
        for (id in ids) {
            val e = encoded.getOrNull(id - numSpecialTokens) ?: continue
            total += e.size
        }
        val buf = ByteArray(total)
        var at = 0
        for (id in ids) {
            val e = encoded.getOrNull(id - numSpecialTokens) ?: continue
            System.arraycopy(e, 0, buf, at, e.size)
            at += e.size
        }
        return String(buf, Charsets.UTF_8)
    }

    /** Greedy CTC collapse: drop repetidos, depois blank, depois ids < numSpecialTokens. */
    fun collapse(ids: IntArray, blankId: Int = 0): IntArray {
        val out = mutableListOf<Int>()
        var prev = -1
        for (id in ids) {
            if (id != prev && id != blankId && id >= numSpecialTokens) out.add(id)
            prev = id
        }
        return out.toIntArray()
    }

    companion object {
        /** Tabela GPT-2 byte <-> codepoint (invertida: codepoint -> byte). Port fiel do JS. */
        private fun byteLevelCharToByte(): IntArray {
            val bytes = mutableListOf<Int>()
            for (b in 0x21..0x7e) bytes.add(b)
            for (b in 0xa1..0xac) bytes.add(b)
            for (b in 0xae..0xff) bytes.add(b)
            val codepoints = bytes.toMutableList()
            val printable = bytes.toHashSet()
            var next = 0
            for (b in 0 until 256) {
                if (b !in printable) {
                    bytes.add(b)
                    codepoints.add(256 + next)
                    next++
                }
            }
            // map[codepoint] = byte (codepoint até 256+next-1)
            val map = IntArray(256 + next) { -1 }
            for (i in bytes.indices) map[codepoints[i]] = bytes[i]
            return map
        }
    }
}

// ============================================================================
// Engine (parte Android): sessão ONNX Runtime + download do pacote.
// ============================================================================

/** Backend de execução do ONNX Runtime. */
enum class GraniteExecutionBackend(
    val label: String,
    val shortLabel: String = label,
    val reportLabel: String = label,
    val qnnBackend: String? = null,
    val accelerated: Boolean = false,
) {
    CPU("CPU", "CPU", "CPU"),
    GPU_QNN("GPU (Adreno)", "GPU", "GPU (QNN)", qnnBackend = "gpu", accelerated = true),
    NPU_QNN_HTP("NPU (Hexagon)", "NPU", "NPU (QNN HTP)", qnnBackend = "htp", accelerated = true);

    companion object {
        /** Backends acelerados (exigem pacote QAIRT + aparelho Qualcomm). */
        val acceleratedEntries: List<GraniteExecutionBackend> = entries.filter { it.accelerated }
    }
}

/**
 * Engine do Granite Speech 5.0 TurboCTC no Android.
 *
 * Fluxo: baixa o pacote (modelo ONNX F32 + front-end + vocab + punctuator) do R2,
 * carrega a sessão ONNX Runtime (CPU ou NNAPI GPU/NPU), e transcreve WAVs
 * 16 kHz mono: AGC -> front-end -> sessão -> CTC collapse -> decode -> pontuação.
 */
object GraniteEngine {
    private const val TAG = "GraniteEngine"

    // URLs do pacote no Cloudflare R2 (bucket sig-android, subpasta models/granite/5.0-turbo/).
    private const val PACKAGE_BASE_URL = "https://pub-6476622beda24c82875cb84f11f660ea.r2.dev/models/granite/5.0-turbo"
    /** Modelo F32 (CPU e acelerado via QNN — o QNN EP converte FP32→FP16 internamente). */
    private const val MODEL_F32_FILE_NAME = "granite-5.0-turboctc-f32-ext.onnx"
    private const val MODEL_F32_DATA_FILE_NAME = "granite-5.0-turboctc-f32-ext.onnx.data"
    private const val MODEL_FP16_FILE_NAME = "granite-5.0-turboctc-fp16-ext.onnx"
    private const val MODEL_FP16_DATA_FILE_NAME = "granite-5.0-turboctc-fp16-ext.onnx.data"
    private const val FRONTEND_FILE_NAME = "frontend_config.json"
    private const val MEL_FILTERS_FILE_NAME = "mel_filters.bin"
    private const val STFT_WINDOW_FILE_NAME = "stft_window.bin"
    private const val VOCAB_FILE_NAME = "vocab.json"
    private const val PCS_VOCAB_FILE_NAME = "pcs_vocab.json"
    private const val PUNCT_FILE_NAME = "punct_cap_seg_en.onnx"

    @Volatile private var session: OrtSession? = null
    @Volatile private var frontend: GraniteFrontend? = null
    @Volatile private var decoder: GraniteDecoder? = null
    @Volatile private var lastErrorMessage: String = ""
    @Volatile private var onnxNativesLoaded: Boolean = false

    fun lastError(): String = lastErrorMessage

    /** Captura a causa raiz completa (mensagem + stack trace) de uma exceção. */
    private fun describeError(e: Throwable): String {
        val sb = StringBuilder()
        var cause: Throwable = e
        val seen = mutableSetOf<String>()
        while (true) {
            val msg = cause.message ?: cause::class.java.simpleName
            if (seen.add(msg)) {
                if (sb.isNotEmpty()) sb.append(" -> ")
                sb.append(msg)
            }
            val c = cause.cause ?: break
            cause = c
            if (cause === e) break
        }
        sb.append("\n")
        sb.append(e.stackTraceToString())
        return sb.toString()
    }

    /** Tamanho total do download do pacote (para o diálogo). */
    fun packageDownloadBytes(): Long = 2_100_000_000L

    fun packageDir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "granite_models")

    fun modelFile(context: Context): File = File(packageDir(context), MODEL_F32_FILE_NAME)

    /** Nome do arquivo do modelo ONNX F32 (CPU). Usado pela Activity para conferência. */
        fun modelFileName(): String = MODEL_F32_FILE_NAME

        fun modelDataFile(context: Context): File = File(packageDir(context), MODEL_F32_DATA_FILE_NAME)

        /** Modelo FP16 para backends acelerados (GPU/NPU via QNN). */
        fun modelFileFp16(context: Context): File = File(packageDir(context), MODEL_FP16_FILE_NAME)
        fun modelDataFileFp16(context: Context): File = File(packageDir(context), MODEL_FP16_DATA_FILE_NAME)

        /** Seleciona o arquivo .onnx conforme o backend. */
        fun modelFileForBackend(context: Context, backend: GraniteExecutionBackend): File =
            if (backend.accelerated) modelFileFp16(context) else modelFile(context)

        fun isDownloaded(context: Context): Boolean = packageComplete(context)

        /** True quando todos os arquivos do pacote existem e não estão vazios. */
        fun packageComplete(context: Context): Boolean {
            val dir = packageDir(context)
            return packageFiles().all { (name, _) ->
                val f = File(dir, name)
                f.exists() && f.length() > 0L
            }
        }

        private fun packageFiles(): List<Pair<String, String>> = listOf(
            MODEL_F32_FILE_NAME to "$PACKAGE_BASE_URL/$MODEL_F32_FILE_NAME",
            MODEL_F32_DATA_FILE_NAME to "$PACKAGE_BASE_URL/$MODEL_F32_DATA_FILE_NAME",
            MODEL_FP16_FILE_NAME to "$PACKAGE_BASE_URL/$MODEL_FP16_FILE_NAME",
            MODEL_FP16_DATA_FILE_NAME to "$PACKAGE_BASE_URL/$MODEL_FP16_DATA_FILE_NAME",
            FRONTEND_FILE_NAME to "$PACKAGE_BASE_URL/$FRONTEND_FILE_NAME",
            MEL_FILTERS_FILE_NAME to "$PACKAGE_BASE_URL/$MEL_FILTERS_FILE_NAME",
            STFT_WINDOW_FILE_NAME to "$PACKAGE_BASE_URL/$STFT_WINDOW_FILE_NAME",
            VOCAB_FILE_NAME to "$PACKAGE_BASE_URL/$VOCAB_FILE_NAME",
            PCS_VOCAB_FILE_NAME to "$PACKAGE_BASE_URL/$PCS_VOCAB_FILE_NAME",
            PUNCT_FILE_NAME to "$PACKAGE_BASE_URL/$PUNCT_FILE_NAME",
        )

    /** Baixa o pacote completo do R2 (modelo + external data + front-end + vocab + punctuator). */
    fun downloadPackage(context: Context, onProgress: (percent: Int, mb: Long) -> Unit) {
        val dir = packageDir(context).apply { mkdirs() }
        // Limpa downloads residuais de tentativas anteriores.
        dir.listFiles()?.forEach { if (it.name.endsWith(".download")) it.delete() }
        val files = packageFiles()
        var totalBytes = 0L
        var copiedBytes = 0L
        // Soma apenas o que falta baixar.
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
        if (totalBytes <= 0L) totalBytes = packageDownloadBytes()
        // Baixa cada arquivo que falta.
        for ((name, url) in missing) {
            val dest = File(dir, name)
            val temp = File(dir, "$name.download")
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 120000
            }
            val total = connection.contentLengthLong.coerceAtLeast(0L)
            connection.inputStream.use { input ->
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var copied = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        copiedBytes += read
                        if (total > 0L) {
                            val percent = ((copiedBytes * 100L) / totalBytes.coerceAtLeast(1L)).coerceIn(0L, 100L).toInt()
                            onProgress(percent, copiedBytes / 1048576L)
                        } else {
                            onProgress(-1, copiedBytes / 1048576L)
                        }
                    }
                }
            }
            if (dest.exists()) dest.delete()
            if (!temp.renameTo(dest)) {
                temp.copyTo(dest, overwrite = true)
                temp.delete()
            }
        }
    }

    /**
        * Carrega a sessão ONNX (modelo + front-end + decoder) para o backend escolhido.
        *
        * CPU: modelo F32, ONNX Runtime CPU EP.
        * GPU_QNN: modelo FP16, QNN GPU EP (Adreno, via libQnnGpu.so).
        * NPU_QNN_HTP: modelo FP16, QNN HTP EP (Hexagon, via libQnnHtp.so + skel).
        *
        * Quando o backend é acelerado e a sessão QNN falha, NÃO cai para CPU
        * automaticamente: chama [onFallbackPrompt] com o motivo e só usa CPU se o
        * callback devolver true (o usuário aceitou). Se devolver false, o load
        * falha com mensagem clara.
        */
    fun load(
        context: Context,
        backend: GraniteExecutionBackend,
        onLog: (String) -> Unit = {},
        onFallbackPrompt: (String) -> Boolean = { true },
    ): Boolean {
        return try {
            // As libs nativas do ONNX Runtime vêm do pacote R2 (baixado na 1ª
            // execução), não do APK.
            if (!NativeDependencyManager.activateIfInstalled(context)) {
                lastErrorMessage = "Componentes nativos do SIG não instalados. Baixe-os na abertura do app e tente novamente."
                return false
            }
            loadOnnxRuntimeNatives(onLog)
            release()
            val dir = packageDir(context)

            // Modelo correto: F32 para CPU; FP16 para GPU/NPU (exportado do PyTorch
            // com cast nas bordas — I/O fp32, pesos fp16; QNN GPU usa FP16 nativo).
            val modelPath = modelFileForBackend(context, backend)
            if (!modelPath.exists()) {
                lastErrorMessage = "modelo não encontrado: ${modelPath.absolutePath}"
                return false
            }

            val config = GraniteFrontendConfig.fromJson(File(dir, FRONTEND_FILE_NAME).readText())
            val melFilters = readFloatBinary(File(dir, MEL_FILTERS_FILE_NAME))
            val window = readFloatBinary(File(dir, STFT_WINDOW_FILE_NAME))
            frontend = GraniteFrontend(config, melFilters, window)

            val vocabJson = File(dir, VOCAB_FILE_NAME).readText()
            val pieces = parseVocabJson(vocabJson)
            decoder = GraniteDecoder(pieces, config.numSpecialTokens)

            val env = OrtEnvironment.getEnvironment()
            if (backend == GraniteExecutionBackend.CPU) {
                val cpuOptions = OrtSession.SessionOptions()
                cpuOptions.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT)
                session = env.createSession(modelPath.absolutePath, cpuOptions)
                onLog("ONNX session criada (CPU)")
                return true
            }

            // Backend acelerado (GPU_QNN ou NPU_QNN_HTP): carrega libs QNN e
            // configura o QNN Execution Provider do ONNX Runtime.
            val qnnBackend = requireNotNull(backend.qnnBackend) { "Backend não acelerado." }
            if (!QairtDependencyManager.isInstalled(context)) {
                lastErrorMessage = "Componentes QAIRT/QNN não instalados. Selecione GPU ou NPU para baixá-los."
                return false
            }

            // Carrega libs QNN na ordem correta ANTES de tocar na sessão.
            val htpArch = if (qnnBackend == "htp") {
                val arch = QairtDependencyManager.htpArchitecture()
                if (arch == null) {
                    lastErrorMessage = "Arquitetura HTP não detectada neste aparelho."
                    return false
                }
                onLog("HTP arch detectada: v$arch")
                arch
            } else null

            try {
                QairtDependencyManager.loadQnnNatives(context, qnnBackend, htpArch)
                onLog("libs QNN carregadas: backend=$qnnBackend ${if (htpArch != null) "arch=v$htpArch" else ""}")
            } catch (e: Throwable) {
                val reason = e.message ?: "falha ao carregar libs QNN"
                onLog("QNN load falhou: $reason")
                val accepted = onFallbackPrompt(reason)
                if (!accepted) {
                    lastErrorMessage = "O acelerador ${backend.label} não conseguiu carregar as libs QNN e o fallback para CPU foi recusado: $reason"
                    return false
                }
                // Fallback para CPU (modelo F32).
                val cpuOptions = OrtSession.SessionOptions()
                cpuOptions.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT)
                session = env.createSession(modelFile(context).absolutePath, cpuOptions)
                onLog("ONNX session criada (CPU, fallback QNN)")
                return true
            }

            val qnnOptions = OrtSession.SessionOptions()
            qnnOptions.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT)
            val qnnConfig = mutableMapOf<String, String>()
            qnnConfig["backend_path"] = if (qnnBackend == "gpu") "libQnnGpu.so" else "libQnnHtp.so"
            // offload_graph_io_quantization: delega quantização/dequant de I/O ao CPU EP
            // (default '1'; relevante para modelos QDQ; para FP16 é ignorado).
            qnnConfig["offload_graph_io_quantization"] = "1"
            if (qnnBackend == "htp") {
                // enable_htp_fp16_precision: faz o HTP inferir FP32 com precisão FP16 (default '1').
                qnnConfig["enable_htp_fp16_precision"] = "1"
            }
            try {
                qnnOptions.addQnn(qnnConfig)
                onLog("QNN EP configurado: ${qnnConfig}")
            } catch (e: Throwable) {
                onLog("QNN EP indisponível: ${e.message}")
                val accepted = onFallbackPrompt(e.message ?: "QNN indisponível")
                if (!accepted) {
                    lastErrorMessage = "O acelerador ${backend.label} não tem suporte a QNN e o fallback para CPU foi recusado: ${e.message}"
                    return false
                }
                val cpuOptions = OrtSession.SessionOptions()
                cpuOptions.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT)
                session = env.createSession(modelFile(context).absolutePath, cpuOptions)
                onLog("ONNX session criada (CPU, fallback QNN indisponível)")
                return true
            }

            try {
                session = env.createSession(modelPath.absolutePath, qnnOptions)
                onLog("ONNX session criada (${backend.reportLabel})")
                return true
            } catch (e: Throwable) {
                val reason = e.message ?: "falha desconhecida"
                onLog("Sessão ${backend.label} falhou: $reason")
                // Pergunta ao usuário se quer cair para CPU.
                val accepted = onFallbackPrompt(reason)
                if (!accepted) {
                    lastErrorMessage = "O acelerador ${backend.label} não conseguiu carregar o modelo e o fallback para CPU foi recusado: $reason"
                    return false
                }
            }

            // Fallback para CPU (aceito pelo usuário ou QNN indisponível).
            val cpuOptions = OrtSession.SessionOptions()
            cpuOptions.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT)
            session = env.createSession(modelFile(context).absolutePath, cpuOptions)
            onLog("ONNX session criada (CPU, fallback)")
            true
    } catch (e: Throwable) {
        lastErrorMessage = describeError(e)
        Log.e(TAG, "load failed", e)
        false
    }
    }

    /** Transcreve um WAV 16 kHz mono (processa em janelas fixas de 512 frames / 10,24s). */
    fun transcribeFile(wavFile: File, onProgress: (Int) -> Unit = {}): String {
        try {
            return transcribeFileInner(wavFile, onProgress)
        } catch (e: Throwable) {
            lastErrorMessage = describeError(e)
            Log.e(TAG, "transcribeFile failed", e)
            throw e
        }
    }

    private fun transcribeFileInner(wavFile: File, onProgress: (Int) -> Unit): String {
        val sess = session ?: throw IllegalStateException("modelo não carregado")
        val fe = frontend ?: throw IllegalStateException("front-end não carregado")
        val dec = decoder ?: throw IllegalStateException("decoder não carregado")
        val wav = readWav16kMono(wavFile) ?: throw IllegalStateException("WAV inválido")

        val agc = GraniteAgc.apply(wav)
        val features = fe.compute(agc)
        if (features.frames == 0) return ""

        // Janelas fixas de pad_multiple (512) frames — o grafo é estático [1,512,320].
        val multiple = fe.config.padMultiple
        val windowFrames = multiple
        val allIds = mutableListOf<Int>()
        var windowStart = 0
        while (windowStart < features.frames) {
            val windowEnd = min(windowStart + windowFrames, features.frames)
            val windowLen = windowEnd - windowStart
            val input = FloatArray(windowFrames * features.dim)
            for (r in 0 until windowLen) {
                val src = (windowStart + r) * features.dim
                System.arraycopy(features.data, src, input, r * features.dim, features.dim)
            }
            // mask: 1 = frame real, 0 = padding (convenção HF/transformers).
            // O grafo espera attention_mask [1, frames] int64.
            val mask = GraniteMask.build(windowFrames, windowLen)

            val inputTensor = OnnxTensor.createTensor(env(), java.nio.FloatBuffer.wrap(input), longArrayOf(1L, windowFrames.toLong(), features.dim.toLong()))
            val maskTensor = OnnxTensor.createTensor(env(), java.nio.LongBuffer.wrap(mask), longArrayOf(1L, windowFrames.toLong()))
            val outputs = sess.run(
                mapOf("input_features" to inputTensor, "attention_mask" to maskTensor),
                setOf("logits")
            )
            val logits = outputs[0] as OnnxTensor
            val logitsData = logits.floatBuffer
            val outFrames = windowFrames / fe.config.subsampleFactor  // 128
            val vocabSize = 16384
            // Trim desta janela para os frames reais (windowLen/4).
            val realOut = windowLen / fe.config.subsampleFactor
            for (t in 0 until realOut) {
                var best = 0
                var bestVal = logitsData.get(t * vocabSize)
                for (v in 1 until vocabSize) {
                    val value = logitsData.get(t * vocabSize + v)
                    if (value > bestVal) { bestVal = value; best = v }
                }
                allIds.add(best)
            }
            logits.close()
            inputTensor.close()
            maskTensor.close()
            windowStart = windowEnd
            onProgress((windowStart * 100L / features.frames).coerceIn(0L, 100L).toInt())
        }

        val collapsed = dec.collapse(allIds.toIntArray())
        return dec.decode(collapsed).trim()
    }

    fun release() {
        try { session?.close() } catch (_: Throwable) {}
        session = null
        frontend = null
        decoder = null
    }

    private fun env(): OrtEnvironment = OrtEnvironment.getEnvironment()

    /** Carrega as libs nativas do ONNX Runtime do pacote R2 (sig.native.library.dir).
     *  Deve ser chamado ANTES de qualquer referência a OrtEnvironment. */
    private fun loadOnnxRuntimeNatives(onLog: (String) -> Unit) {
        if (onnxNativesLoaded) return
        val libDir = System.getProperty("sig.native.library.dir")
            ?: throw IllegalStateException("diretório de libs nativas não configurado")
        val dir = File(libDir)
        Log.i(TAG, "loadOnnxRuntimeNatives: dir=$libDir")
        // Ordem de dependência: libonnxruntime.so é dependência da libonnxruntime4j_jni.so.
        for (name in listOf("libonnxruntime.so", "libonnxruntime4j_jni.so")) {
            val lib = File(dir, name)
            Log.i(TAG, "loadOnnxRuntimeNatives: tentando $name exists=${lib.isFile} size=${lib.length()}")
            check(lib.isFile) { "lib nativa ausente: ${lib.absolutePath}" }
            try {
                System.load(lib.absolutePath)
                Log.i(TAG, "loadOnnxRuntimeNatives: OK $name")
                onLog("nativo carregado: $name")
            } catch (e: Throwable) {
                Log.e(TAG, "loadOnnxRuntimeNatives: ERRO $name -> ${e.javaClass.simpleName}: ${e.message}", e)
                // Se já estiver carregada (UnsatisfiedLinkError "already loaded"), ignora.
                if (e.message?.contains("already loaded", ignoreCase = true) != true) throw e
            }
        }
        // As libs já foram carregadas via System.load acima. O problema restante é
        // que o OnnxRuntime.init() (disparado por OrtEnvironment.getEnvironment)
        // ainda chama System.loadLibrary("onnxruntime4j_jni") e falha no namespace
        // do APK. O registro no classloader (registerNativeLibraryDir) é feito em
        // NativeDependencyManager.activateIfInstalled — se ainda falhar, a saída é
        // uma ponte JNI própria (libsig_onnx.so) no lugar do onnxruntime-java.
        onnxNativesLoaded = true
        Log.i(TAG, "loadOnnxRuntimeNatives: concluído")
    }

    private fun readFloatBinary(file: File): FloatArray {
        val bytes = file.readBytes()
        val floats = FloatArray(bytes.size / 4)
        var i = 0
        var j = 0
        while (i + 3 < bytes.size) {
            val bits = (bytes[i].toLong() and 0xFF) or
                ((bytes[i + 1].toLong() and 0xFF) shl 8) or
                ((bytes[i + 2].toLong() and 0xFF) shl 16) or
                ((bytes[i + 3].toLong() and 0xFF) shl 24)
            floats[j++] = Float.fromBits(bits.toInt())
            i += 4
        }
        return floats.copyOf(j)
    }

    /** Lê um WAV 16 kHz mono PCM s16le (o que o FFmpeg gera) e retorna FloatArray [-1,1]. */
    private fun readWav16kMono(file: File): FloatArray? {
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val riff = ByteArray(4)
                raf.readFully(riff)
                if (String(riff) != "RIFF") return null
                raf.skipBytes(4)
                raf.readFully(riff)
                if (String(riff) != "WAVE") return null
                var sampleRate = 0
                var channels = 0
                var bits = 0
                var dataSize = 0L
                var dataOffset = -1L
                while (raf.filePointer < raf.length()) {
                    val id = ByteArray(4)
                    raf.readFully(id)
                    val size = leInt(raf)
                    when (String(id)) {
                        "fmt " -> {
                            raf.skipBytes(2) // audioFormat
                            channels = leShort(raf)
                            sampleRate = leInt(raf)
                            raf.skipBytes(6)
                            bits = leShort(raf)
                            raf.skipBytes((size - 16).coerceAtLeast(0))
                        }
                        "data" -> { dataSize = size.toLong(); dataOffset = raf.filePointer }
                        else -> raf.skipBytes(size.coerceAtLeast(0))
                    }
                    if (dataOffset >= 0) break
                }
                if (dataOffset < 0 || sampleRate != 16000 || channels != 1) return null
                raf.seek(dataOffset)
                val sampleCount = (dataSize / (bits / 8)).toInt()
                val out = FloatArray(sampleCount)
                val buf = ByteArray(sampleCount * 2)
                raf.readFully(buf)
                var i = 0
                var j = 0
                while (j + 1 < buf.size) {
                    val s = ((buf[j].toInt() and 0xFF) or ((buf[j + 1].toInt() and 0xFF) shl 8)).toShort()
                    out[i++] = s / 32768f
                    j += 2
                }
                out
            }
        } catch (e: Throwable) {
            Log.e(TAG, "readWav16kMono failed", e)
            null
        }
    }

    private fun leShort(raf: RandomAccessFile): Int {
        val b = ByteArray(2)
        raf.readFully(b)
        return (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8)
    }

    private fun leInt(raf: RandomAccessFile): Int {
        val b = ByteArray(4)
        raf.readFully(b)
        return (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8) or
            ((b[2].toInt() and 0xFF) shl 16) or ((b[3].toInt() and 0xFF) shl 24)
    }

    /** Parseia o vocab.json (array de strings) do Space da IBM. */
    private fun parseVocabJson(json: String): List<String> {
        // O vocab.json é um array JSON de strings; parseia sem org.json no JVM
        // (o teste roda sem Android). Fallback: regex.
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
        // Objeto { "piece": id } (pcs_vocab) — não usado aqui.
        return emptyList()
    }
}
