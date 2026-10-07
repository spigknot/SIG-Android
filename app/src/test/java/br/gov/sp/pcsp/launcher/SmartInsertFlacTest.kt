package br.gov.sp.pcsp.launcher

import org.junit.Assert.*
import org.junit.Test
import java.util.Random

class SmartInsertFlacTest {
    @Test fun tableCrcMatchesIndependentBitwiseImplementation() {
        val bytes=ByteArray(8192).also { Random(829).nextBytes(it) }
        for((bits,polynomial) in listOf(8 to 7,16 to 0x8005)) {
            var value=0
            for(byte in bytes) {
                value=value xor ((byte.toInt() and 255) shl (bits-8))
                repeat(8) { value=((value shl 1) xor if(value and (1 shl (bits-1))!=0)polynomial else 0) and ((1 shl bits)-1) }
            }
            assertEquals(value,SmartInsertFlac.crc(bytes,bits))
        }
    }
    @Test fun rebasedFramesRetainPayloadAndPassFullCrc() {
        val body=ByteArray(8192).also { Random(70).nextBytes(it) }
        for(oldNumber in listOf(0L,127L,128L,65536L)) {
            val prefix=byteArrayOf(255.toByte(),248.toByte(),105,8)+SmartInsertFlac.number(oldNumber)+byteArrayOf(127)
            val original=prefix+byteArrayOf(SmartInsertFlac.crc(prefix,8).toByte())+body
            val checksum=SmartInsertFlac.crc(original,16)
            val frame=original+byteArrayOf((checksum ushr 8).toByte(),checksum.toByte())
            for(sample in listOf(0L,128L,65536L,1L shl 35)) {
                val (rebased,count)=SmartInsertFlac.rebase(frame,sample)
                val header=SmartInsertFlac.frameHeader(rebased)
                assertEquals(128,count);assertEquals(0,SmartInsertFlac.crc(rebased,16))
                assertArrayEquals(body,rebased.copyOfRange(header.end,rebased.size-2))
            }
        }
    }
    @Test fun shiftCrcMatchesAppendingZeroBytes() {
        val prefix="original header".toByteArray()
        for(length in listOf(0,1,127,8192))assertEquals(SmartInsertFlac.crc(prefix+ByteArray(length),16),
            SmartInsertFlac.shiftCrc(SmartInsertFlac.crc(prefix,16),length))
    }
    @Test fun sampleNumbersAndBrokenFramesAreRejected() {
        for(value in listOf(-1L,1L shl 36))assertThrows(IllegalArgumentException::class.java) { SmartInsertFlac.number(value) }
        for(bytes in listOf(byteArrayOf(),"not a frame".toByteArray(),byteArrayOf(-1,-8,105,8,-1,1,0,0)))
            assertThrows(IllegalArgumentException::class.java) { SmartInsertFlac.frameHeader(bytes) }
    }
}
