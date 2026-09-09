package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

class LittleEndianIoTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun leShort_leDoisBytesLittleEndian() {
        val f = temp.newFile("s.bin")
        f.writeBytes(byteArrayOf(0x34, 0x12))
        RandomAccessFile(f, "r").use { raf ->
            assertEquals(0x1234, LittleEndianIo.leShort(raf))
        }
    }

    @Test
    fun leInt_leQuatroBytesLittleEndian() {
        val f = temp.newFile("i.bin")
        f.writeBytes(byteArrayOf(0x78, 0x56, 0x34, 0x12))
        RandomAccessFile(f, "r").use { raf ->
            assertEquals(0x12345678, LittleEndianIo.leInt(raf))
        }
    }

    @Test
    fun leInt_byteAltoNegativo_naoViraNegativo() {
        val f = temp.newFile("neg.bin")
        f.writeBytes(byteArrayOf(0x00, 0x00, 0x00, 0x80.toByte()))
        RandomAccessFile(f, "r").use { raf ->
            assertEquals(Int.MIN_VALUE, LittleEndianIo.leInt(raf))
        }
    }

    @Test
    fun leShort_arquivoCurto_lancaEof() {
        val f = temp.newFile("curto.bin")
        f.writeBytes(byteArrayOf(0x01))
        var lancou = false
        RandomAccessFile(f, "r").use { raf ->
            try {
                LittleEndianIo.leShort(raf)
            } catch (e: EOFException) {
                lancou = true
            }
        }
        assertEquals(true, lancou)
    }

    @Test
    fun writeIntLe_gravaBytesLittleEndian() {
        val f = File(temp.root, "out.bin")
        FileOutputStream(f).use { out -> LittleEndianIo.writeIntLe(out, 0x12345678) }
        assertEquals(listOf<Byte>(0x78, 0x56, 0x34, 0x12), f.readBytes().toList())
    }

    @Test
    fun writeShortLe_gravaBytesLittleEndian() {
        val f = File(temp.root, "out2.bin")
        FileOutputStream(f).use { out -> LittleEndianIo.writeShortLe(out, 0x1234) }
        assertEquals(listOf<Byte>(0x34, 0x12), f.readBytes().toList())
    }

    @Test
    fun roundtrip_escreveELeDeVolta() {
        val f = File(temp.root, "rt.bin")
        FileOutputStream(f).use { out ->
            LittleEndianIo.writeShortLe(out, 1)
            LittleEndianIo.writeIntLe(out, 16000)
            LittleEndianIo.writeIntLe(out, -1)
        }
        RandomAccessFile(f, "r").use { raf ->
            assertEquals(1, LittleEndianIo.leShort(raf))
            assertEquals(16000, LittleEndianIo.leInt(raf))
            assertEquals(-1, LittleEndianIo.leInt(raf))
        }
    }
}
