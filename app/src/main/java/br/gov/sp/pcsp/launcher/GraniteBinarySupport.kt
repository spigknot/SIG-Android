package br.gov.sp.pcsp.launcher

import android.util.Log
import java.io.File
import java.io.RandomAccessFile

/**
 * Apoio binario dos motores Granite (GraniteEngine e GraniteNarEngine):
 * tabela de bytes do vocabulario, descricao de erros encadeados e leitura dos
 * arquivos de mel/janela/WAV usados na inferencia.
 *
 * Os dois motores mantinham copias identicas destas funcoes; qualquer mudanca
 * aqui vale para os dois.
 */
internal object GraniteBinarySupport {

    /**
     * Tabela de conversao caractere->byte do vocabulario byte-level: imprimiveis
     * ASCII/ISO-8859-1 mantem o codigo; os demais bytes recebem codepoints
     * 256+ (na ordem crescente de byte).
     */
    fun byteLevelCharToByte(): IntArray {
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

    /** Encadeia mensagens de causa (sem repetir) e anexa o stack trace. */
    fun describeError(e: Throwable): String {
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

    /** Le um arquivo de floats little-endian (mel filters / STFT window). */
    fun readFloatBinary(file: File): FloatArray {
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

    /**
     * Le um WAV PCM 16 kHz mono 16 bits como floats normalizados.
     * Devolve null quando o formato nao bate (ou em qualquer falha de leitura,
     * registrada em log com [logTag]).
     */
    fun readWav16kMono(file: File, logTag: String): FloatArray? {
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
                    val size = LittleEndianIo.leInt(raf)
                    when (String(id)) {
                        "fmt " -> {
                            raf.skipBytes(2) // audioFormat
                            channels = LittleEndianIo.leShort(raf)
                            sampleRate = LittleEndianIo.leInt(raf)
                            raf.skipBytes(6)
                            bits = LittleEndianIo.leShort(raf)
                            raf.skipBytes((size - 16).coerceAtLeast(0))
                        }
                        "data" -> {
                            dataSize = size.toLong()
                            dataOffset = raf.filePointer
                        }
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
            Log.e(logTag, "readWav16kMono failed", e)
            null
        }
    }
}
