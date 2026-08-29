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

    /** argmax + unique_consecutive + remove blank, sobre logits [frames, vocab] row-major. */
    fun collapseLogits(logits: FloatArray, frames: Int, vocab: Int): IntArray {
        val out = ArrayList<Int>(frames)
        var prev = -1
        for (t in 0 until frames) {
            var best = 0
            var bestVal = logits[t * vocab]
            for (v in 1 until vocab) {
                val valv = logits[t * vocab + v]
                if (valv > bestVal) { bestVal = valv; best = v }
            }
            if (best != prev && best != BLANK) out.add(best)
            prev = best
        }
        return out.toIntArray()
    }
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

    private const val PACKAGE_BASE_URL = "https://pub-6476622beda24c82875cb84f11f660ea.r2.dev/models/granite/4.1-nar"

    private const val ENCODER_FILE = "granite-4.1-nar-encoder-fp16.onnx"
    private const val PROJECTOR_FILE = "granite-4.1-nar-projector-fp16.onnx"
    private const val PROJECTOR_DATA = "granite-4.1-nar-projector-fp16.onnx.data"
    private const val LLM_FILE = "granite-4.1-nar-llm-fp16.onnx"
    private const val LLM_DATA = "granite-4.1-nar-llm-fp16.onnx.data"
    private const val MEL_FILE = "nar_mel_filters.bin"
    private const val WINDOW_FILE = "nar_stft_window.bin"
    private const val VOCAB_FILE = "vocab.json"
    private const val EMBED_FILE = "nar_embed_tokens.bin"
    private const val CONFIG_FILE = "preprocessor_config.json"

    const val VOCAB_SIZE = 100352
    const val HIDDEN = 2048
    const val BLANK = 100257
    const val T_FIXED = 2000
    const val EMBEDDING_MULTIPLIER = 12.0f

    @Volatile private var encoderSession: OrtSession? = null
    @Volatile private var projectorSession: OrtSession? = null
    @Volatile private var llmSession: OrtSession? = null
    @Volatile private var frontend: GraniteNarFrontend? = null
    @Volatile private var vocab: List<String>? = null
    @Volatile private var embed: ByteArray? = null
    @Volatile private var lastErrorMessage: String = ""
    @Volatile private var onnxNativesLoaded: Boolean = false

    fun lastError(): String = lastErrorMessage

    fun packageDir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "granite_nar_models")

    private fun packageFiles(): List<Pair<String, String>> = listOf(
        ENCODER_FILE to "$PACKAGE_BASE_URL/$ENCODER_FILE",
        PROJECTOR_FILE to "$PACKAGE_BASE_URL/$PROJECTOR_FILE",
        PROJECTOR_DATA to "$PACKAGE_BASE_URL/$PROJECTOR_DATA",
        LLM_FILE to "$PACKAGE_BASE_URL/$LLM_FILE",
        LLM_DATA to "$PACKAGE_BASE_URL/$LLM_DATA",
        MEL_FILE to "$PACKAGE_BASE_URL/$MEL_FILE",
        WINDOW_FILE to "$PACKAGE_BASE_URL/$WINDOW_FILE",
        VOCAB_FILE to "$PACKAGE_BASE_URL/$VOCAB_FILE",
        EMBED_FILE to "$PACKAGE_BASE_URL/$EMBED_FILE",
        CONFIG_FILE to "$PACKAGE_BASE_URL/$CONFIG_FILE",
    )

    /**
     * Tamanho total do download do pacote (para o diálogo).
     *
     * Consulta os tamanhos reais no R2 via HEAD (fonte de verdade) e soma apenas
     * os arquivos que ainda faltam baixar — o mesmo cálculo usado em
     * [downloadPackage]. A soma fixa abaixo é apenas fallback quando a rede falha.
     */
    fun packageDownloadBytes(context: Context? = null): Long {
        val dir = context?.let { packageDir(it) }
        val missing = dir?.let { d ->
            packageFiles().filter { (name, _) ->
                val f = File(d, name)
                !(f.exists() && f.length() > 0L)
            }
        } ?: packageFiles()
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
        return if (remote > 0L) remote else FALLBACK_PACKAGE_BYTES
    }

    /** Soma dos arquivos publicados (fallback quando os HEAD requests falham). */
    internal const val FALLBACK_PACKAGE_BYTES =
        1_086_629_439L + 159_568_555L + 159_535_104L + 2_149_128L + 3_263_500_288L +
            82_240L + 2_048L + 1_612_704L + 411_041_792L + 289L

    fun packageComplete(context: Context): Boolean {
        val dir = packageDir(context)
        return packageFiles().all { (name, _) ->
            val f = File(dir, name)
            f.exists() && f.length() > 0L
        }
    }

    fun downloadPackage(context: Context, onProgress: (percent: Int, mb: Long) -> Unit) {
        val dir = packageDir(context).apply { mkdirs() }
        dir.listFiles()?.forEach { if (it.name.endsWith(".download")) it.delete() }
        val files = packageFiles()
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
        if (totalBytes <= 0L) totalBytes = FALLBACK_PACKAGE_BYTES
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

    fun load(
        context: Context,
        onLog: (String) -> Unit = {},
        onFallbackPrompt: (String) -> Boolean = { true },
    ): Boolean {
        return try {
            if (!NativeDependencyManager.activateIfInstalled(context)) {
                lastErrorMessage = "Componentes nativos do SIG não instalados. Baixe-os na abertura do app e tente novamente."
                return false
            }
            loadOnnxRuntimeNatives(onLog)
            release()

            val dir = packageDir(context)
            for (f in listOf(ENCODER_FILE, PROJECTOR_FILE, PROJECTOR_DATA, LLM_FILE, LLM_DATA, MEL_FILE, WINDOW_FILE, VOCAB_FILE, EMBED_FILE)) {
                if (!File(dir, f).exists()) {
                    lastErrorMessage = "arquivo do modelo ausente: $f"
                    return false
                }
            }

            frontend = GraniteNarFrontend(
                readFloatBinary(File(dir, MEL_FILE)),
                readFloatBinary(File(dir, WINDOW_FILE)),
            )
            vocab = parseVocabJson(File(dir, VOCAB_FILE).readText())
            embed = File(dir, EMBED_FILE).readBytes()

            val env = OrtEnvironment.getEnvironment()
            val cpuOptions = OrtSession.SessionOptions()
            cpuOptions.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT)

            encoderSession = env.createSession(File(dir, ENCODER_FILE).absolutePath, cpuOptions)
            onLog("ONNX encoder criado")
            projectorSession = env.createSession(File(dir, PROJECTOR_FILE).absolutePath, cpuOptions)
            onLog("ONNX projector criado")
            llmSession = env.createSession(File(dir, LLM_FILE).absolutePath, cpuOptions)
            onLog("ONNX llm editor criado")
            true
        } catch (e: Throwable) {
            lastErrorMessage = describeError(e)
            Log.e(TAG, "load failed", e)
            false
        }
    }

    /** Embedding lookup: embed_tokens[token] = [HIDDEN] float32, do nar_embed_tokens.bin (fp16). */
    private fun embedToken(token: Int): FloatArray {
        val buf = embed ?: throw IllegalStateException("embedding não carregado")
        val out = FloatArray(HIDDEN)
        val base = token * HIDDEN * 2
        for (i in 0 until HIDDEN) {
            val h = ((buf[base + 2 * i].toInt() and 0xFF) or ((buf[base + 2 * i + 1].toInt() and 0xFF) shl 8)).toShort()
            out[i] = HalfFloat.toFloat(h)
        }
        return out
    }

    /** Transcreve um WAV 16 kHz mono (single shot até T_FIXED frames ~37,5s). */
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
        val enc = encoderSession ?: throw IllegalStateException("encoder não carregado")
        val proj = projectorSession ?: throw IllegalStateException("projector não carregado")
        val llm = llmSession ?: throw IllegalStateException("llm não carregado")
        val fe = frontend ?: throw IllegalStateException("front-end não carregado")
        val pieces = vocab ?: throw IllegalStateException("vocab não carregado")

        val wav = readWav16kMono(wavFile) ?: throw IllegalStateException("WAV inválido")
        val features = fe.compute(wav)
        if (features.frames == 0) return ""
        val realFrames = min(features.frames, T_FIXED)
        if (features.frames > T_FIXED) {
            throw IllegalStateException("áudio muito longo para o Granite 4.1 NAR nesta versão (máx ~${T_FIXED * 160 / 16000}s)")
        }

        // Pad até T_FIXED e roda o encoder.
        val input = FloatArray(T_FIXED * GraniteNarFrontend.INPUT_DIM)
        for (r in 0 until realFrames) {
            System.arraycopy(features.data, r * features.dim, input, r * features.dim, features.dim)
        }
        val env = OrtEnvironment.getEnvironment()
        val inputTensor = OnnxTensor.createTensor(env, java.nio.FloatBuffer.wrap(input), longArrayOf(1L, T_FIXED.toLong(), GraniteNarFrontend.INPUT_DIM.toLong()))
        val encOut = enc.run(mapOf("input_features" to inputTensor), setOf("encoder_bpe_logits", "multilayer_features"))
        inputTensor.close()

        val bpeLogits = (encOut["encoder_bpe_logits"] as OnnxTensor).floatBuffer
        val multilayer = (encOut["multilayer_features"] as OnnxTensor).floatBuffer
        val bpeLogitsArr = FloatArray(bpeLogits.remaining()).also { bpeLogits.get(it) }
        val multilayerArr = FloatArray(multilayer.remaining()).also { multilayer.get(it) }
        (encOut["encoder_bpe_logits"] as OnnxTensor).close()
        (encOut["multilayer_features"] as OnnxTensor).close()
        onProgress(25)

        // CTC collapse no encoder (valid frames = ceil(realFrames/4)).
        val validBpe = (realFrames + 3) / 4
        val ctcTokens = GraniteNarCtc.collapseLogits(bpeLogitsArr, validBpe, VOCAB_SIZE)
        onProgress(40)

        // Projector -> audio_embeds [402, 2048]; válidos = realFrames//5; /12 (scale).
        val projTensor = OnnxTensor.createTensor(env, java.nio.FloatBuffer.wrap(multilayerArr), longArrayOf(1L, T_FIXED.toLong(), 4096L))
        val projOut = proj.run(mapOf("multilayer_features" to projTensor), setOf("audio_embeds"))
        projTensor.close()
        val audioEmbeds = (projOut["audio_embeds"] as OnnxTensor).floatBuffer
        val audioEmbedsArr = FloatArray(audioEmbeds.remaining()).also { audioEmbeds.get(it) }
        (projOut["audio_embeds"] as OnnxTensor).close()
        onProgress(55)

        val validAudio = realFrames / 5
        // Interleave + embedding lookup.
        val slots = GraniteNarInterleave.buildSlots(ctcTokens, BLANK)
        val S = validAudio + slots.size
        val inputsEmbeds = FloatArray(S * HIDDEN)
        // audio_embeds (validAudio vetores de 2048), divididos por 12.
        for (i in 0 until validAudio) {
            val base = i * HIDDEN
            for (d in 0 until HIDDEN) inputsEmbeds[base + d] = audioEmbedsArr[base + d] / EMBEDDING_MULTIPLIER
        }
        // text embeds (slots) no offset validAudio.
        for (t in slots.indices) {
            val emb = embedToken(slots[t])
            val base = (validAudio + t) * HIDDEN
            System.arraycopy(emb, 0, inputsEmbeds, base, HIDDEN)
        }
        onProgress(70)

        // LLM editor.
        val posIds = LongArray(S) { it.toLong() }
        val embedsTensor = OnnxTensor.createTensor(env, java.nio.FloatBuffer.wrap(inputsEmbeds), longArrayOf(1L, S.toLong(), HIDDEN.toLong()))
        val posTensor = OnnxTensor.createTensor(env, java.nio.LongBuffer.wrap(posIds), longArrayOf(1L, S.toLong()))
        val llmOut = llm.run(mapOf("inputs_embeds" to embedsTensor, "position_ids" to posTensor), setOf("logits"))
        embedsTensor.close()
        posTensor.close()
        val logits = (llmOut["logits"] as OnnxTensor).floatBuffer
        val logitsArr = FloatArray(logits.remaining()).also { logits.get(it) }
        (llmOut["logits"] as OnnxTensor).close()
        onProgress(90)

        // Fatia do texto + collapse + decode.
        val textStart = validAudio
        val textFrames = S - validAudio
        val textLogits = FloatArray(textFrames * VOCAB_SIZE)
        System.arraycopy(logitsArr, textStart * VOCAB_SIZE, textLogits, 0, textFrames * VOCAB_SIZE)
        val pred = GraniteNarCtc.collapseLogits(textLogits, textFrames, VOCAB_SIZE)

        val decoded = decodeByteLevel(pred, pieces)
        onProgress(100)
        return decoded.trim()
    }

    /** Decodifica tokens BPE byte-level (id = byte-stand-in) para UTF-8. */
    private fun decodeByteLevel(ids: IntArray, pieces: List<String>): String {
        val cm = byteLevelCharToByte()
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
        val map = IntArray(256 + next) { -1 }
        for (i in bytes.indices) map[codepoints[i]] = bytes[i]
        return map
    }

    fun release() {
        try { encoderSession?.close() } catch (_: Throwable) {}
        try { projectorSession?.close() } catch (_: Throwable) {}
        try { llmSession?.close() } catch (_: Throwable) {}
        encoderSession = null
        projectorSession = null
        llmSession = null
        frontend = null
        vocab = null
        embed = null
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
                            raf.skipBytes(2)
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
