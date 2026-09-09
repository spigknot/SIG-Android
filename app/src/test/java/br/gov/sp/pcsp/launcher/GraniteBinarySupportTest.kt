package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class GraniteBinarySupportTest {

    @get:Rule
    val temp = TemporaryFolder()

    // ---------- byteLevelCharToByte ----------

    @Test
    fun byteLevelCharToByte_cobreTodosOs256Bytes() {
        val map = GraniteBinarySupport.byteLevelCharToByte()
        val valores = map.filter { it >= 0 }.sorted()
        assertEquals((0..255).toList(), valores)
    }

    @Test
    fun byteLevelCharToByte_imprimiveisMantemOCodigo() {
        val map = GraniteBinarySupport.byteLevelCharToByte()
        assertEquals(0x21, map[0x21])
        assertEquals(0x7e, map[0x7e])
        assertEquals(0xa1, map[0xa1])
        assertEquals(0xff, map[0xff])
    }

    @Test
    fun byteLevelCharToByte_naoImprimiveisVaoParaCodepoints256mais() {
        val map = GraniteBinarySupport.byteLevelCharToByte()
        // map e codepoint -> byte: 0x00 e o primeiro nao imprimivel em ordem
        // crescente, logo ocupa o codepoint 256.
        assertEquals(0x00, map[256])
        // espaco (0x20) e o 33o nao imprimivel (indice 32) -> codepoint 288
        assertEquals(0x20, map[288])
        assertEquals(-1, map[0x20])
    }

    // ---------- describeError ----------

    @Test
    fun describeError_encadeiaMensagensDaCausa() {
        val e = IllegalStateException(
            "topo",
            RuntimeException("meio", IOException("fundo"))
        )
        val texto = GraniteBinarySupport.describeError(e)
        assertTrue(texto.startsWith("topo -> meio -> fundo"))
        assertTrue(texto.contains("IllegalStateException"))
    }

    @Test
    fun describeError_semMensagem_usaNomeDaClasse() {
        val texto = GraniteBinarySupport.describeError(RuntimeException())
        assertTrue(texto.startsWith("RuntimeException"))
    }

    @Test
    fun describeError_mensagemRepetidaNaoDuplica() {
        val e = RuntimeException("igual", RuntimeException("igual"))
        val texto = GraniteBinarySupport.describeError(e)
        // a cadeia de mensagens deduplica; o stack trace (anexado depois) pode
        // repetir a mensagem, por isso a checagem e na primeira linha
        assertEquals("igual", texto.lineSequence().first())
    }

    // ---------- readFloatBinary ----------

    @Test
    fun readFloatBinary_leFloatsLittleEndian() {
        val f = File(temp.root, "f.bin")
        val valores = floatArrayOf(0f, 1.5f, -2.25f, 3.0f)
        ByteArrayOutputStream().use { bos ->
            for (v in valores) {
                val bits = java.lang.Float.floatToIntBits(v)
                bos.write(bits and 0xff)
                bos.write((bits shr 8) and 0xff)
                bos.write((bits shr 16) and 0xff)
                bos.write((bits shr 24) and 0xff)
            }
            f.writeBytes(bos.toByteArray())
        }
        assertArrayEquals(valores, GraniteBinarySupport.readFloatBinary(f), 0f)
    }

    @Test
    fun readFloatBinary_ignoraRestoQueNaoFormaFloat() {
        val f = File(temp.root, "f2.bin")
        f.writeBytes(byteArrayOf(0, 0, 0, 0, 0x7f, 0x01))
        assertEquals(1, GraniteBinarySupport.readFloatBinary(f).size)
    }

    // ---------- readWav16kMono ----------

    private fun wav16kMono(samples: ShortArray, sampleRate: Int = 16000, channels: Int = 1): ByteArray {
        val dataSize = samples.size * 2
        val bos = ByteArrayOutputStream()
        fun ascii(s: String) = bos.write(s.toByteArray())
        fun le32(v: Int) {
            bos.write(v and 0xff); bos.write((v shr 8) and 0xff)
            bos.write((v shr 16) and 0xff); bos.write((v shr 24) and 0xff)
        }
        fun le16(v: Int) {
            bos.write(v and 0xff); bos.write((v shr 8) and 0xff)
        }
        ascii("RIFF"); le32(36 + dataSize); ascii("WAVE")
        ascii("fmt "); le32(16); le16(1); le16(channels)
        le32(sampleRate); le32(sampleRate * channels * 2); le16(channels * 2); le16(16)
        ascii("data"); le32(dataSize)
        for (s in samples) {
            bos.write(s.toInt() and 0xff); bos.write((s.toInt() shr 8) and 0xff)
        }
        return bos.toByteArray()
    }

    @Test
    fun readWav16kMono_leAmostrasNormalizadas() {
        val f = File(temp.root, "a.wav")
        f.writeBytes(wav16kMono(shortArrayOf(0, 32767, -32768)))
        val out = GraniteBinarySupport.readWav16kMono(f, "Teste")
        assertArrayEquals(floatArrayOf(0f, 32767 / 32768f, -1f), out!!, 1e-6f)
    }

    @Test
    fun readWav16kMono_taxdiferente_devolveNull() {
        val f = File(temp.root, "b.wav")
        f.writeBytes(wav16kMono(shortArrayOf(1, 2), sampleRate = 8000))
        assertNull(GraniteBinarySupport.readWav16kMono(f, "Teste"))
    }

    @Test
    fun readWav16kMono_estereo_devolveNull() {
        val f = File(temp.root, "c.wav")
        f.writeBytes(wav16kMono(shortArrayOf(1, 2, 3, 4), channels = 2))
        assertNull(GraniteBinarySupport.readWav16kMono(f, "Teste"))
    }

    @Test
    fun readWav16kMono_naoRiff_devolveNull() {
        val f = File(temp.root, "d.wav")
        f.writeBytes("NOPE".toByteArray() + ByteArray(60))
        assertNull(GraniteBinarySupport.readWav16kMono(f, "Teste"))
    }
}
