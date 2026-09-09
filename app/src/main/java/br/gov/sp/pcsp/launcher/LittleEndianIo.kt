package br.gov.sp.pcsp.launcher

import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * Leitura/escrita little-endian usada em cabecalhos WAV e nos pacotes dos
 * motores Granite.
 *
 * `leShort`/`leInt` leem com `readFully` (lancam EOF se o arquivo acabar) —
 * e o contrato usado pelos motores. Nao confundir com os leitores tolerantes
 * a EOF de WhisperActivity, que tem semantica diferente de proposito.
 */
internal object LittleEndianIo {

    fun leShort(raf: RandomAccessFile): Int {
        val b = ByteArray(2)
        raf.readFully(b)
        return (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8)
    }

    fun leInt(raf: RandomAccessFile): Int {
        val b = ByteArray(4)
        raf.readFully(b)
        return (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8) or
            ((b[2].toInt() and 0xFF) shl 16) or ((b[3].toInt() and 0xFF) shl 24)
    }

    fun writeIntLe(output: FileOutputStream, value: Int) {
        output.write(
            byteArrayOf(
                (value and 0xff).toByte(),
                ((value shr 8) and 0xff).toByte(),
                ((value shr 16) and 0xff).toByte(),
                ((value shr 24) and 0xff).toByte()
            )
        )
    }

    fun writeShortLe(output: FileOutputStream, value: Int) {
        output.write(
            byteArrayOf(
                (value and 0xff).toByte(),
                ((value shr 8) and 0xff).toByte()
            )
        )
    }
}
