package br.gov.sp.pcsp.launcher

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/** Rebaseia headers/CRC FLAC (RFC 9639), preservando os subframes comprimidos. */
object SmartInsertFlac {
    private val crc8 = table(8,7)
    private val crc16 = table(16,0x8005)
    private fun table(bits: Int, polynomial: Int): IntArray = IntArray(256) { byte ->
        var value = byte shl (bits-8)
        repeat(8) { value = ((value shl 1) xor if (value and (1 shl (bits-1)) != 0) polynomial else 0) and ((1 shl bits)-1) }
        value
    }
    fun crc(data: ByteArray, bits: Int): Int {
        val table = if (bits==8) crc8 else crc16
        var value = 0
        for (byte in data) value = ((value shl 8) xor table[(value ushr (bits-8)) xor (byte.toInt() and 255)]) and ((1 shl bits)-1)
        return value
    }
    private fun apply(matrix: IntArray, input: Int): Int {
        var value = input; var result = 0; var bit = 0
        while (value != 0) { if (value and 1 != 0) result = result xor matrix[bit]; value = value ushr 1; bit++ }
        return result
    }
    private val shifts = Array(25) { IntArray(16) }.also { matrices ->
        for (i in 0..15) {
            var value = 1 shl i
            repeat(8) { value = ((value shl 1) xor if (value and 0x8000 != 0) 0x8005 else 0) and 65535 }
            matrices[0][i] = value
        }
        for (power in 1 until matrices.size) for (i in 0..15) matrices[power][i] = apply(matrices[power-1],matrices[power-1][i])
    }
    private val byteShifts = shifts.map { matrix -> Pair(IntArray(256) { apply(matrix,it) },IntArray(256) { apply(matrix,it shl 8) }) }
    fun shiftCrc(input: Int, bytes: Int): Int {
        require(bytes >= 0 && bytes < 1 shl shifts.size) { "Tamanho de frame FLAC inválido." }
        var value = input; var length = bytes; var power = 0
        while (length > 0) {
            if (length and 1 != 0) { val (low,high) = byteShifts[power]; value = low[value and 255] xor high[value ushr 8] }
            length = length ushr 1; power++
        }
        return value
    }
    fun number(input: Long): ByteArray {
        require(input >= 0 && input < 1L shl 36) { "Número de amostra FLAC inválido." }
        if (input < 128) return byteArrayOf(input.toByte())
        val limits = intArrayOf(11,16,21,26,31,36)
        val n = limits.indexOfFirst { input < 1L shl it } + 2
        var value = input
        val result = ByteArray(n)
        for (i in n-1 downTo 1) { result[i] = (0x80 or (value and 63).toInt()).toByte(); value = value ushr 6 }
        result[0] = (((0xff shl (8-n)) and 255) or value.toInt()).toByte()
        return result
    }
    data class Header(val numberEnd: Int, val end: Int, val samples: Int)
    fun frameHeader(frame: ByteArray): Header {
        fun byte(at: Int) = frame[at].toInt() and 255
        require(frame.size >= 8 && byte(0)==255 && byte(1) and 254 == 248) { "Sincronização FLAC inválida." }
        val first = byte(4)
        val n = if (first < 128) 1 else (2..7).firstOrNull { i ->
            val mask = (255 shl (8-i)) and 255
            first and mask == mask && (i==7 || first and (1 shl (7-i)) == 0)
        } ?: 0
        require(first != 255 && n > 0 && frame.size >= 4+n+3 && (5 until 4+n).all { byte(it) and 192 == 128 }) { "Número de frame FLAC inválido." }
        var extra = 4+n
        val samples = when (val block=byte(2) ushr 4) {
            1 -> 192
            in 2..5 -> 576 shl (block-2)
            6 -> (byte(extra)+1).also { extra++ }
            7 -> ((byte(extra) shl 8)+byte(extra+1)+1).also { extra+=2 }
            in 8..15 -> 256 shl (block-8)
            else -> error("Tamanho de bloco FLAC reservado.")
        }
        val rate = byte(2) and 15
        extra += when (rate) { 12 -> 1; 13,14 -> 2; else -> 0 }
        require(rate != 15 && frame.size >= extra+3 && crc(frame.copyOfRange(0,extra+1),8)==0) { "Header ou CRC-8 FLAC inválido." }
        return Header(4+n,extra+1,samples)
    }
    fun rebase(frame: ByteArray, sample: Long): Pair<ByteArray,Int> {
        val old = frameHeader(frame)
        val prefix = frame.copyOfRange(0,4).also { it[1] = (it[1].toInt() or 1).toByte() } + number(sample) + frame.copyOfRange(old.numberEnd,old.end-1)
        val header = prefix + byteArrayOf(crc(prefix,8).toByte())
        val delta = crc(frame.copyOfRange(0,old.end),16) xor crc(header,16)
        val original = ((frame[frame.size-2].toInt() and 255) shl 8) or (frame.last().toInt() and 255)
        val checksum = original xor shiftCrc(delta,frame.size-old.end-2)
        return Pair(header + frame.copyOfRange(old.end,frame.size-2) + byteArrayOf((checksum ushr 8).toByte(),checksum.toByte()),old.samples)
    }
    fun metadata(path: File): List<Pair<Int,ByteArray>> {
        val blocks = mutableListOf<Pair<Int,ByteArray>>()
        RandomAccessFile(path,"r").use { source ->
            val magic = ByteArray(4).also { source.readFully(it) }
            require(String(magic,Charsets.US_ASCII)=="fLaC") { "O arquivo não é FLAC nativo." }
            do {
                val header = source.readInt()
                val count = header and 0xffffff
                require(count.toLong() <= source.length()-source.filePointer) { "Metadados FLAC incompletos." }
                val payload = ByteArray(count).also { source.readFully(it) }
                blocks += Pair((header ushr 24) and 127,payload)
            } while (header ushr 31 == 0)
        }
        require(blocks.firstOrNull()?.let { it.first==0 && it.second.size==34 } == true) { "STREAMINFO FLAC inválido." }
        return blocks
    }
    fun streamValue(bytes: ByteArray): Long = ByteBuffer.wrap(bytes,10,8).long
    private fun three(value: Int) = byteArrayOf((value ushr 16).toByte(),(value ushr 8).toByte(),value.toByte())
    fun assemble(parts: List<Pair<File,List<SmartInsertPlanner.Packet>>>, output: File, main: File, total: Long, cancelled: () -> Unit) {
        require(total > 0 && total < 1L shl 36) { "Contagem de amostras FLAC inválida." }
        val blocks = metadata(main)
        val stream = blocks[0].second.copyOf()
        val signature = streamValue(stream) ushr 36
        ByteBuffer.wrap(stream,10,8).putLong((signature shl 36) or total)
        stream.fill(0,18,34)
        val retained = listOf(Pair(0,stream)) + blocks.drop(1).filter { it.first in setOf(4,6) }
        var sample = 0L; var minCount = Int.MAX_VALUE; var maxCount = 0; var minSize = Int.MAX_VALUE; var maxSize = 0
        RandomAccessFile(output,"rw").use { destination ->
            destination.setLength(0); destination.writeBytes("fLaC")
            retained.forEachIndexed { i,(kind,data) ->
                destination.writeByte(kind or if (i==retained.lastIndex) 128 else 0)
                destination.write(three(data.size)); destination.write(data)
            }
            for ((path,packets) in parts) {
                require(streamValue(metadata(path)[0].second) ushr 36 == signature) { "As peças FLAC possuem formatos diferentes." }
                RandomAccessFile(path,"r").use { source ->
                    for (packet in packets) {
                        cancelled()
                        require(packet.offset >= 0 && packet.size > 0 && packet.size.toLong() <= source.length()-packet.offset) { "Frame FLAC incompleto." }
                        source.seek(packet.offset)
                        val original = ByteArray(packet.size).also { source.readFully(it) }
                        val (frame,count) = rebase(original,sample)
                        sample += count
                        require(count >= 16 || sample == total) { "Bloco FLAC interno curto demais." }
                        destination.write(frame)
                        minCount=minOf(minCount,count); maxCount=maxOf(maxCount,count)
                        minSize=minOf(minSize,frame.size); maxSize=maxOf(maxSize,frame.size)
                    }
                }
            }
            require(sample == total) { "A montagem FLAC mudou a contagem de amostras." }
            ByteBuffer.wrap(stream,0,4).putShort(maxOf(16,minCount).toShort()).putShort(maxOf(16,maxCount).toShort())
            three(minSize).copyInto(stream,4); three(maxSize).copyInto(stream,7)
            destination.seek(8); destination.write(stream)
        }
    }
}
