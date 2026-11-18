package br.gov.sp.pcsp.launcher

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
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

    private fun manifesto(vararg itens: Pair<String, ByteArray>, versao: String = "2"): String {
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
            // R103/T02: o manifesto publicado do v2 grava "i16".
            .put("dtype", "i16")
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
    fun `manifesto sem uma das duas entradas e recusado`() {
        val itens = conteudo().dropLast(1)
        assertNull(WhisperOrtNpuPacote.lerManifesto(manifesto(*itens.toTypedArray())))
    }

    @Test
    fun `manifesto de outra versao e recusado`() {
        val itens = conteudo()
        assertNull(WhisperOrtNpuPacote.lerManifesto(manifesto(*itens.toTypedArray(), versao = "3")))
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
        File(dir, WhisperOrtNpuPacote.WRAPPER).delete()
        val lido = WhisperOrtNpuPacote.lerManifesto(manifesto(*itens.toTypedArray()))!!
        val motivo = WhisperOrtNpuPacote.conferirArquivos(lido, dir)
        assertEquals("ausente:${WhisperOrtNpuPacote.WRAPPER}", motivo)
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

    // ---- R101 §T02: pacote v2 = contexto + wrapper; QNN vem do QAIRT ----

    @Test
    fun `pacote v2 nao carrega biblioteca QNN`() {
        assertEquals(
            listOf(WhisperOrtNpuPacote.CONTEXTO, WhisperOrtNpuPacote.WRAPPER),
            WhisperOrtNpuPacote.ARQUIVOS,
        )
        assertTrue(
            "nenhuma .so entra no pacote da NPU",
            WhisperOrtNpuPacote.ARQUIVOS.none { it.endsWith(".so") },
        )
    }

    @Test
    fun `plano de download com QAIRT instalado so tem o pacote da NPU`() {
        val plano = WhisperOrtNpuPacote.planoDeDownload(qairtInstalado = true)
        assertEquals(1, plano.arquivos.size)
        assertEquals(WhisperOrtNpuPacote.BYTES_ZIP, plano.totalBytes)
        assertEquals(WhisperOrtNpuPacote.BYTES_ZIP, WhisperOrtNpuPacote.bytesParaBaixar(true, false))
    }

    @Test
    fun `plano de download sem QAIRT soma os dois pacotes`() {
        val plano = WhisperOrtNpuPacote.planoDeDownload(qairtInstalado = false)
        assertEquals(2, plano.arquivos.size)
        assertEquals(
            WhisperOrtNpuPacote.BYTES_ZIP + QairtDependencyManager.downloadSize(),
            plano.totalBytes,
        )
        assertEquals(plano.totalBytes, WhisperOrtNpuPacote.bytesParaBaixar(false, false))
        assertEquals(0L, WhisperOrtNpuPacote.bytesParaBaixar(true, true))
    }

    @Test
    fun `espaco necessario cobre ZIP e extracao dos dois pacotes`() {
        val soNpu = WhisperOrtNpuPacote.espacoNecessario(qairtInstalado = true, npuInstalado = false)
        assertTrue(soNpu >= WhisperOrtNpuPacote.BYTES_ZIP + WhisperOrtNpuPacote.BYTES_CONTEUDO)
        val osDois = WhisperOrtNpuPacote.espacoNecessario(qairtInstalado = false, npuInstalado = false)
        assertTrue(osDois > soNpu)
        assertTrue(osDois >= soNpu + QairtDependencyManager.downloadSize())
        assertEquals(0L, WhisperOrtNpuPacote.espacoNecessario(qairtInstalado = true, npuInstalado = true))
    }

    @Test
    fun `backend QNN da rota aponta para o QAIRT e nao para o pacote da NPU`() {
        val qairt = File(temp.root, "qairt/v1")
        val pacoteNpu = File(temp.root, "whisper-npu/2")
        val backend = WhisperOrtHtpRota.backendHtp(qairt)
        assertEquals(File(qairt, "lib/libQnnHtp.so").absolutePath, backend.absolutePath)
        assertTrue(!backend.absolutePath.startsWith(pacoteNpu.absolutePath))
    }

    // ---- R103/T02: contrato do manifesto PUBLICADO e instalação de ponta a ponta ----

    private fun manifestoPublicadoBytes(): ByteArray =
        javaClass.getResourceAsStream("/npu/manifesto-publicado-v2.json")!!.use { it.readBytes() }

    @Test
    fun `bytes do manifesto publicado sao aceitos e dtype i16 e traduzido`() {
        val bytes = manifestoPublicadoBytes()
        assertEquals(
            "9011fde9817f5ba90e82cad0ea0c999fcd87523282e3e285c1decddff61b277d",
            sha(bytes),
        )
        val lido = WhisperOrtNpuPacote.lerManifesto(bytes.toString(Charsets.UTF_8))
        assertNotNull("manifesto publicado do v2 tem de ser aceito", lido)
        assertEquals("2", lido!!.versao)
        assertEquals(WhisperOrtHtpAdmissao.SOC_SM8550, lido.soc)
        assertEquals("73", lido.htpArch)
        assertEquals("i16", JSONObject(bytes.toString(Charsets.UTF_8)).optString("dtype"))
        assertEquals(
            "o valor publicado i16 vira o dtype canonico da conversao",
            WhisperOrtHtpConversao.DTYPE_I16,
            lido.dtype,
        )
        assertEquals(2, lido.itens.size)
    }

    @Test
    fun `manifesto publicado alterado em qualquer campo e recusado`() {
        val original = JSONObject(manifestoPublicadoBytes().toString(Charsets.UTF_8))
        val mutacoes = listOf(
            "schema" to { o: JSONObject -> o.put("schema", "outro/v1") },
            "versao" to { o: JSONObject -> o.put("versao", "3") },
            "soc" to { o: JSONObject -> o.put("soc", "SM8750") },
            "htp_arch" to { o: JSONObject -> o.put("htp_arch", "75") },
            "dtype" to { o: JSONObject -> o.put("dtype", WhisperOrtHtpConversao.DTYPE_I16) },
            "arquivos.nome" to { o: JSONObject -> o.getJSONArray("arquivos").getJSONObject(0).put("nome", "outro.bin") },
            "arquivos.bytes" to { o: JSONObject -> o.getJSONArray("arquivos").getJSONObject(0).put("bytes", 0L) },
            "arquivos.sha256" to { o: JSONObject -> o.getJSONArray("arquivos").getJSONObject(0).put("sha256", "g".repeat(64)) },
            "arquivos.extra" to { o: JSONObject -> o.getJSONArray("arquivos").put(JSONObject().put("nome", "extra.bin").put("bytes", 1L).put("sha256", "a".repeat(64))) },
            "arquivos.removido" to { o: JSONObject -> o.getJSONArray("arquivos").remove(1) },
        )
        for ((campo, mutar) in mutacoes) {
            val alterado = JSONObject(original.toString())
            mutar(alterado)
            assertNull("alterar $campo tem de recusar", WhisperOrtNpuPacote.lerManifesto(alterado.toString()))
        }
    }

    @Test
    fun `instalacao ponta a ponta do ZIP publicado termina Pronto e limpa o download`() {
        val zip = File(System.getProperty("r103.zip", "D:/tmp/r101/download/sig-whisper-w8a16-sm8550-v2.zip"))
        Assume.assumeTrue("ZIP publicado ausente no host — teste pulado explicitamente", zip.isFile)
        assertEquals(
            "tem de ser o ZIP publicado pinado",
            WhisperOrtNpuPacote.SHA256_ZIP,
            WhisperOrtNpuPacote.sha256(zip),
        )
        val cache = temp.newFolder("cache-e2e")
        val raiz = temp.newFolder("raiz-e2e")
        val baixado = File(cache, "whisper-npu-v${WhisperOrtNpuPacote.VERSAO}.zip.download")
        val avisos = mutableListOf<String>()
        val resultado = WhisperOrtNpuPacote.instalarEm(
            cacheDir = cache,
            raiz = raiz,
            urlPacote = zip.toURI().toString(),
            aoMarcarVerificado = { avisos.add(it.dtype) },
        )
        assertTrue("instalar de ponta a ponta falhou: ${resultado.exceptionOrNull()}", resultado.isSuccess)
        assertTrue(WhisperOrtNpuPacote.estadoEm(File(raiz, WhisperOrtNpuPacote.VERSAO)) is WhisperOrtNpuPacote.Estado.Pronto)
        assertEquals(listOf(WhisperOrtHtpConversao.DTYPE_I16), avisos)
        assertTrue(".zip.download tem de ser apagado após sucesso", !baixado.exists())
    }

    @Test
    fun `zip de download e apagado tambem depois de falha de SHA`() {
        val falso = File(temp.root, "falso.zip")
        falso.writeBytes(ByteArray(1024) { 7 })
        val cache = temp.newFolder("cache-falha")
        val raiz = temp.newFolder("raiz-falha")
        val baixado = File(cache, "whisper-npu-v${WhisperOrtNpuPacote.VERSAO}.zip.download")
        val resultado = WhisperOrtNpuPacote.instalarEm(
            cacheDir = cache,
            raiz = raiz,
            urlPacote = falso.toURI().toString(),
        )
        assertTrue("ZIP divergente tem de falhar", resultado.isFailure)
        assertTrue(".zip.download tem de ser apagado após falha", !baixado.exists())
    }
}
