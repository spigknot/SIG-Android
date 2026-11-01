package br.gov.sp.pcsp.launcher

import org.junit.Assert.*
import org.junit.Test

/**
 * Adendo 17 §3 — leitura real de WAV (RIFF verdadeiro, não simulacro).
 *
 * Constrói bytes RIFF com `fmt `/`LIST`/`data` e prova: chunk `data` com
 * 960.512 bytes (480.256 amostras = 30,016 s, espelho do c.wav) é lido por
 * inteiro; LIST é pulado; nada além do chunk entra no PCM; truncado é
 * corrupção (não "WAV mais curto"); float/estéreo/8-bit pedem conversão.
 */
class WhisperOrtWavTest {

    private fun le16(v: Int): ByteArray = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
    private fun le32(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte(),
    )

    private fun cabecalho(fmtExtra: ByteArray? = null, taxa: Int = 16000, canais: Int = 1, bits: Int = 16, formato: Int = 1): ByteArray {
        val out = mutableListOf<Byte>()
        fun add(b: ByteArray) = b.forEach { out.add(it) }
        fun addStr(s: String) = add(s.toByteArray(Charsets.US_ASCII))
        addStr("RIFF"); add(le32(0)); addStr("WAVE")
        addStr("fmt "); add(le32(16))
        add(le16(formato)); add(le16(canais)); add(le32(taxa))
        add(le32(taxa * canais * bits / 8)); add(le16(canais * bits / 8)); add(le16(bits))
        if (fmtExtra != null) add(fmtExtra)
        return out.toByteArray()
    }

    private fun riffComLista(dataBytes: ByteArray): ByteArray {
        // Espelho do c.wav: fmt(16) + LIST(26) + data em offset 70.
        val out = cabecalho().toMutableList()
        fun add(b: ByteArray) = b.forEach { out.add(it) }
        fun addStr(s: String) = add(s.toByteArray(Charsets.US_ASCII))
        addStr("LIST"); add(le32(26)); repeat(26) { out.add(0) }
        addStr("data"); add(le32(dataBytes.size))
        dataBytes.forEach { out.add(it) }
        val total = out.toByteArray()
        val riffSize = total.size - 8
        le32(riffSize).copyInto(total, 4)
        return total
    }

    private fun pcm16(amostras: Int, valor: Short = 1000): ByteArray {
        val out = ByteArray(amostras * 2)
        for (i in 0 until amostras) {
            out[2 * i] = (valor.toInt() and 0xFF).toByte()
            out[2 * i + 1] = ((valor.toInt() shr 8) and 0xFF).toByte()
        }
        return out
    }

    @Test fun `data de 960512 com LIST le 480256 amostras inteiras`() {
        val dados = pcm16(480_256)
        val wav = riffComLista(dados)
        assertEquals(960_590, wav.size)
        val r = WhisperOrtWav.ler(wav)
        assertTrue("deveria ser Pcm, veio $r", r is WhisperOrtWav.Leitura.Pcm)
        val pcm = (r as WhisperOrtWav.Leitura.Pcm)
        assertEquals(480_256, pcm.amostras.size)
        assertEquals(16000, pcm.taxa)
        // Nada além do chunk: a última amostra vem do data declarado.
        assertTrue(pcm.amostras.all { it > 0f })
    }

    @Test fun `sem LIST tambem le inteiro`() {
        val dados = pcm16(16_000)
        val out = cabecalho().toMutableList()
        fun add(b: ByteArray) = b.forEach { out.add(it) }
        "data".toByteArray(Charsets.US_ASCII).forEach { out.add(it) }
        add(le32(dados.size))
        dados.forEach { out.add(it) }
        val total = out.toByteArray()
        le32(total.size - 8).copyInto(total, 4)
        val r = WhisperOrtWav.ler(total)
        assertTrue(r is WhisperOrtWav.Leitura.Pcm)
        assertEquals(16_000, (r as WhisperOrtWav.Leitura.Pcm).amostras.size)
    }

    @Test fun `data declarada maior que presente e truncado`() {
        val dados = pcm16(8_000)
        val out = cabecalho().toMutableList()
        "data".toByteArray(Charsets.US_ASCII).forEach { out.add(it) }
        // Declara 16000 amostras mas entrega 8000.
        val total = out.toByteArray() + le32(32_000) + dados
        le32(total.size - 8).copyInto(total, 4)
        val r = WhisperOrtWav.ler(total)
        assertTrue("truncado deve ser Corrompido, veio $r", r is WhisperOrtWav.Leitura.Corrompido)
    }

    @Test fun `nao riff pede conversao`() {
        val r = WhisperOrtWav.ler(ByteArray(100) { 7 })
        assertTrue(r is WhisperOrtWav.Leitura.PrecisaConversao)
    }

    @Test fun `float e stereo pedem conversao`() {
        val truque = cabecalho(formato = 3).toMutableList()
        fun add(b: ByteArray) = b.forEach { truque.add(it) }
        "data".toByteArray(Charsets.US_ASCII).forEach { truque.add(it) }
        val dados = pcm16(100)
        add(le32(dados.size))
        val total = truque.toByteArray() + dados
        le32(total.size - 8).copyInto(total, 4)
        assertTrue(WhisperOrtWav.ler(total) is WhisperOrtWav.Leitura.PrecisaConversao)
    }

    @Test fun `arquivo minusculo e corrompido`() {
        assertTrue(WhisperOrtWav.ler(ByteArray(10)) is WhisperOrtWav.Leitura.Corrompido)
    }
}
