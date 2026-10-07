package br.gov.sp.pcsp.launcher

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

class SmartInsertWaveTest {
    private fun fixture(root: File, name: String, data: ByteArray, bits: Int = 16): File {
        val file=File(root,name)
        val bytes=ByteBuffer.allocate(44+data.size+(data.size and 1)).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray());putInt(capacity()-8);put("WAVEfmt ".toByteArray());putInt(16)
            putShort(1);putShort(1);putInt(48000);putInt(48000*bits/8);putShort((bits/8).toShort());putShort(bits.toShort())
            put("data".toByteArray());putInt(data.size);put(data)
        }.array()
        file.writeBytes(bytes)
        return file
    }
    private fun payload(file: File): ByteArray {
        val bytes=file.readBytes();val input=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var offset=12
        while(offset+8<=bytes.size) {
            val length=input.getInt(offset+4)
            if(String(bytes,offset,4)=="data")return bytes.copyOfRange(offset+8,offset+8+length)
            offset+=8+length+(length and 1)
        }
        error("data missing")
    }
    @Test fun copiesExactSamplesAtBeginningMiddleAndEnd() {
        val root=Files.createTempDirectory("insert-wav").toFile()
        try {
            val source=ByteBuffer.allocate(14).order(ByteOrder.LITTLE_ENDIAN).apply {
                listOf(-32768,-1234,-1,0,1,1234,32767).forEach { putShort(it.toShort()) }
            }.array()
            val inserted=byteArrayOf(2,3,4,5,6,7)
            val main=fixture(root,"main.wav",source);val middle=fixture(root,"middle.wav",inserted)
            for(cut in listOf(0,1,4,7)) {
                val output=File(root,"out-$cut.wav")
                SmartInsertWave.assemble(main,middle,output,cut.toLong(),10) { }
                assertEquals(20,payload(output).size)
                assertArrayEquals(source.copyOfRange(0,cut*2)+inserted+source.copyOfRange(cut*2,source.size),payload(output))
            }
        } finally { root.deleteRecursively() }
    }
    @Test fun handlesOddUnsignedPcmLengthAndCancellation() {
        val root=Files.createTempDirectory("insert-wav").toFile()
        try {
            val main=fixture(root,"main.wav",byteArrayOf(0,128.toByte(),255.toByte()),8)
            val middle=fixture(root,"middle.wav",byteArrayOf(45,93),8)
            val output=File(root,"out.wav")
            SmartInsertWave.assemble(main,middle,output,1,5) { }
            assertArrayEquals(byteArrayOf(0,45,93,128.toByte(),255.toByte()),payload(output))
            assertThrows(InterruptedException::class.java) { SmartInsertWave.assemble(main,middle,File(root,"cancel.wav"),1,5) { throw InterruptedException() } }
        } finally { root.deleteRecursively() }
    }
    @Test fun acceptsRf64ExtensibleAndRejectsTruncatedData() {
        val root=Files.createTempDirectory("insert-rf64").toFile()
        try {
            val fmt=ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN).apply {
                putShort(65534.toShort());putShort(2);putInt(48000);putInt(288000);putShort(6);putShort(24)
                putShort(22);putShort(24);putInt(3);put(byteArrayOf(1,0,0,0,0,0,16,0,128.toByte(),0,0,170.toByte(),0,56,155.toByte(),113))
            }.array()
            val bytes=ByteBuffer.allocate(134).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RF64".toByteArray());putInt(-1);put("WAVEds64".toByteArray());putInt(28)
                putLong(126);putLong(30);putLong(5);putInt(0)
                put("fmt ".toByteArray());putInt(40);put(fmt);put("data".toByteArray());putInt(-1);put(ByteArray(30) { it.toByte() })
            }.array()
            val main=File(root,"main.wav").also { it.writeBytes(bytes) }
            val parsed=SmartInsertWave.inspect(main)
            assertEquals(listOf(1,2,48000,6,24,24,0),parsed.signature);assertEquals(30L,parsed.size)
            main.writeBytes(bytes.copyOf(bytes.size-1))
            assertThrows(IllegalArgumentException::class.java) { SmartInsertWave.inspect(main) }
        } finally { root.deleteRecursively() }
    }
}
