package br.gov.sp.pcsp.launcher

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Montagem WAV/RF64 por bytes PCM em limites de amostras, sem encoder no principal. */
object SmartInsertWave {
    data class Data(val format: ByteArray, val offset: Long, val size: Long, val align: Int,
        val floating: Boolean, val signature: List<Int>, val metadata: ByteArray)

    private fun u16(bytes: ByteArray, at: Int) = ByteBuffer.wrap(bytes, at, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 65535
    private fun u32(bytes: ByteArray, at: Int) = ByteBuffer.wrap(bytes, at, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
    private fun u64(bytes: ByteArray, at: Int) = ByteBuffer.wrap(bytes, at, 8).order(ByteOrder.LITTLE_ENDIAN).long
    private fun little(value: Long, size: Int) = ByteArray(size) { (value ushr (it*8)).toByte() }
    private fun read(file: RandomAccessFile, length: Int) = ByteArray(length).also { file.readFully(it) }
    private fun chunk(kind: String, data: ByteArray) = kind.toByteArray(Charsets.US_ASCII) + little(data.size.toLong(),4) + data + ByteArray(data.size and 1)

    fun inspect(path: File): Data {
        var format: ByteArray? = null
        var dataOffset = -1L
        var dataSize = 0L
        var data64: Long? = null
        val metadata = ByteArrayOutputStream()
        RandomAccessFile(path,"r").use { file ->
            val header = read(file,12)
            require(String(header,0,4,Charsets.US_ASCII) in setOf("RIFF","RF64") && String(header,8,4,Charsets.US_ASCII)=="WAVE") { "WAV/RF64 não suportado." }
            while (file.filePointer+8 <= file.length()) {
                val entry = read(file,8)
                val kind = String(entry,0,4,Charsets.US_ASCII)
                var size = u32(entry,4)
                if (size == 0xffffffffL) {
                    require(kind == "data" && data64 != null) { "Tamanho RF64 ausente." }
                    size = checkNotNull(data64)
                }
                val at = file.filePointer
                require(size >= 0 && size <= file.length()-at) { "Chunk WAV truncado." }
                when (kind) {
                    "ds64" -> {
                        require(size >= 28) { "ds64 incompleto." }
                        data64 = u64(read(file,28),8)
                    }
                    "fmt " -> {
                        require(size in 16..65536) { "Formato WAV inválido." }
                        format = read(file,size.toInt())
                    }
                    "data" -> {
                        require(dataOffset < 0) { "WAV com múltiplos chunks de áudio exige compatibilização." }
                        dataOffset = at; dataSize = size
                    }
                    "LIST" -> if (size <= 1 shl 20) {
                        val info = read(file,size.toInt())
                        if (info.size >= 4 && String(info,0,4,Charsets.US_ASCII)=="INFO") metadata.write(chunk(kind,info))
                    }
                }
                file.seek(at+size+(size and 1))
            }
        }
        val fmt = requireNotNull(format) { "WAV sem formato." }
        require(dataOffset >= 0) { "WAV sem dados." }
        var tag = u16(fmt,0)
        val channels = u16(fmt,2); val rate = u32(fmt,4).toInt()
        val align = u16(fmt,12); val bits = u16(fmt,14)
        var valid = bits; var mask = 0
        if (tag == 65534) {
            val guid = byteArrayOf(0,0,0,0,16,0,128.toByte(),0,0,170.toByte(),0,56,155.toByte(),113)
            require(fmt.size >= 40 && fmt.copyOfRange(26,40).contentEquals(guid)) { "Subformato WAV não suportado." }
            valid = u16(fmt,18); mask = u32(fmt,20).toInt(); tag = u16(fmt,24)
        }
        require(tag in setOf(1,3) && channels > 0 && rate > 0 && bits in 1..64 && align > 0 &&
            (tag != 3 || bits in setOf(32,64)) && align == channels*((bits+7)/8) && u32(fmt,8) == rate.toLong()*align && dataSize % align == 0L) { "Formato PCM inválido." }
        if (channels <= 2) mask = 0
        return Data(fmt,dataOffset,dataSize,align,tag==3,listOf(tag,channels,rate,align,bits,valid,mask),metadata.toByteArray())
    }

    fun assemble(main: File, inserted: File, output: File, insertion: Long, total: Long, cancelled: () -> Unit) {
        val first = inspect(main); val middle = inspect(inserted)
        require(first.signature == middle.signature) { "Os trechos WAV possuem formatos PCM diferentes." }
        require(insertion >= 0 && insertion <= first.size/first.align && total == (first.size+middle.size)/first.align) { "A contagem de amostras WAV mudou." }
        val dataSize = first.size+middle.size
        var extra = chunk("fmt ",first.format)+first.metadata
        if (first.floating) extra += chunk("fact",little(minOf(total,0xffffffffL),4))
        val riffSize = 4+extra.size+8+dataSize+(dataSize and 1)
        val rf64 = riffSize > 0xffffffffL
        output.outputStream().buffered().use { destination ->
            destination.write((if (rf64) "RF64" else "RIFF").toByteArray(Charsets.US_ASCII))
            destination.write(little(if (rf64) 0xffffffffL else riffSize,4)); destination.write("WAVE".toByteArray(Charsets.US_ASCII))
            if (rf64) destination.write(chunk("ds64",little(riffSize+36,8)+little(dataSize,8)+little(total,8)+little(0,4)))
            destination.write(extra); destination.write("data".toByteArray(Charsets.US_ASCII)); destination.write(little(if (rf64) 0xffffffffL else dataSize,4))
            val buffer = ByteArray(1 shl 20)
            for ((path,start,count) in listOf(Triple(main,first.offset,insertion*first.align),
                Triple(inserted,middle.offset,middle.size), Triple(main,first.offset+insertion*first.align,first.size-insertion*first.align))) {
                RandomAccessFile(path,"r").use { source ->
                    source.seek(start)
                    var remaining = count
                    while (remaining > 0) {
                        cancelled()
                        val length = source.read(buffer,0,minOf(buffer.size.toLong(),remaining).toInt())
                        require(length > 0) { "Trecho PCM incompleto." }
                        destination.write(buffer,0,length); remaining -= length
                    }
                }
            }
            if (dataSize and 1L != 0L) destination.write(0)
        }
    }
}
