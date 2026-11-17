package br.gov.sp.pcsp.launcher

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/**
 * R100 §5.1 — manifesto do pacote NPU.
 *
 * Prova o que a ordem exige antes de qualquer rota HTP: SHA divergente e pacote
 * incompleto levam à CPU (recusa), e um manifesto fora do contrato (schema,
 * versão, SoC, arch, dtype ou arquivo faltando) não é aceito "quase pronto".
 */
class WhisperOrtNpuPacoteTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun manifesto(vararg itens: Pair<String, ByteArray>, versao: String = "1"): String {
        val arquivos = JSONArray()
        for ((nome, bytes) in itens) {
            arquivos.put(
                JSONObject()
                    .put("nome", nome)
                    .put("bytes", bytes.size)
                    .put("sha256", sha(bytes)),
            )
        }
        return JSONObject()
            .put("schema", "sig-whisper-npu-pacote/v1")
            .put("versao", versao)
            .put("soc", WhisperOrtHtpAdmissao.SOC_SM8550)
            .put("htp_arch", "73")
            .put("dtype", WhisperOrtHtpConversao.DTYPE_I16)
            .put("arquivos", arquivos)
            .toString()
    }

    private fun conteudo(): List<Pair<String, ByteArray>> = WhisperOrtNpuPacote.ARQUIVOS.mapIndexed { i, nome ->
        nome to ByteArray(64 + i) { (i + 1).toByte() }
    }

    private fun escrever(dir: File, itens: List<Pair<String, ByteArray>>) {
        for ((nome, bytes) in itens) File(dir, nome).writeBytes(bytes)
    }

    @Test
    fun `manifesto completo e valido`() {
        val itens = conteudo()
        val lido = WhisperOrtNpuPacote.lerManifesto(manifesto(*itens.toTypedArray()))
        assertNotNull(lido)
        assertEquals(WhisperOrtNpuPacote.ARQUIVOS.size, lido!!.itens.size)
        assertEquals("73", lido.htpArch)
        assertEquals(WhisperOrtHtpConversao.DTYPE_I16, lido.dtype)
    }

    @Test
    fun `manifesto sem uma das sete entradas e recusado`() {
        val itens = conteudo().dropLast(1)
        assertNull(WhisperOrtNpuPacote.lerManifesto(manifesto(*itens.toTypedArray())))
    }

    @Test
    fun `manifesto de outra versao e recusado`() {
        val itens = conteudo()
        assertNull(WhisperOrtNpuPacote.lerManifesto(manifesto(*itens.toTypedArray(), versao = "2")))
    }

    @Test
    fun `pacote completo passa`() {
        val itens = conteudo()
        val dir = temp.newFolder("completo")
        escrever(dir, itens)
        val lido = WhisperOrtNpuPacote.lerManifesto(manifesto(*itens.toTypedArray()))!!
        assertNull(WhisperOrtNpuPacote.conferirArquivos(lido, dir))
    }

    @Test
    fun `SHA divergente leva a CPU`() {
        val itens = conteudo()
        val dir = temp.newFolder("sha")
        escrever(dir, itens)
        // Conteúdo trocado DEPOIS de assinar: o tamanho pode até coincidir.
        File(dir, WhisperOrtNpuPacote.CONTEXTO).writeBytes(ByteArray(itens[0].second.size) { 0x7F })
        val lido = WhisperOrtNpuPacote.lerManifesto(manifesto(*itens.toTypedArray()))!!
        val motivo = WhisperOrtNpuPacote.conferirArquivos(lido, dir)
        assertNotNull("SHA divergente tem de recusar", motivo)
        assertTrue(motivo!!.startsWith("sha:"))
    }

    @Test
    fun `pacote incompleto leva a CPU`() {
        val itens = conteudo()
        val dir = temp.newFolder("incompleto")
        escrever(dir, itens)
        File(dir, "libQnnHtpV73Skel.so").delete()
        val lido = WhisperOrtNpuPacote.lerManifesto(manifesto(*itens.toTypedArray()))!!
        val motivo = WhisperOrtNpuPacote.conferirArquivos(lido, dir)
        assertEquals("ausente:libQnnHtpV73Skel.so", motivo)
    }

    @Test
    fun `tamanho diferente leva a CPU antes de hashear`() {
        val itens = conteudo()
        val dir = temp.newFolder("tamanho")
        escrever(dir, itens)
        File(dir, WhisperOrtNpuPacote.WRAPPER).writeBytes(ByteArray(itens[1].second.size + 1))
        val lido = WhisperOrtNpuPacote.lerManifesto(manifesto(*itens.toTypedArray()))!!
        var hasheou = false
        val motivo = WhisperOrtNpuPacote.conferirArquivos(lido, dir) { hasheou = true; "" }
        assertTrue(motivo!!.startsWith("tamanho:"))
        assertTrue("tamanho é conferido antes do SHA", !hasheou)
    }

    @Test
    fun `marcador amarra versao e conteudo do manifesto`() {
        val itens = conteudo()
        val m1 = WhisperOrtNpuPacote.lerManifesto(manifesto(*itens.toTypedArray()))!!
        val outro = itens.toMutableList()
        outro[0] = outro[0].first to ByteArray(64) { 9 }
        val m2 = WhisperOrtNpuPacote.lerManifesto(manifesto(*outro.toTypedArray()))!!
        assertTrue(WhisperOrtNpuPacote.textoMarcadorEsperado(m1) !=
            WhisperOrtNpuPacote.textoMarcadorEsperado(m2))
    }
}
