package br.gov.sp.pcsp.launcher

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/**
 * R115 §T03 — pacote v3: contrato do manifesto PUBLICADO, admissão v3/v2/nenhum,
 * remoção ATÔMICA do v2 só depois do v3 verificado e oferta de atualização
 * uma vez só.
 */
class WhisperOrtNpuPacoteV3Test {

    @get:Rule
    val temp = TemporaryFolder()

    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun manifestoPublicadoV3(): ByteArray =
        javaClass.getResourceAsStream("/npu/manifesto-publicado-v3.json")!!.use { it.readBytes() }

    private fun manifestoPublicadoV2(): ByteArray =
        javaClass.getResourceAsStream("/npu/manifesto-publicado-v2.json")!!.use { it.readBytes() }

    // ---------------------------------------------------------------- contrato

    @Test
    fun `manifesto v3 publicado e aceito com o contrato completo`() {
        val bytes = manifestoPublicadoV3()
        assertEquals("5b8b61eb297a10c157ef464bba7aacd344427b7f36d9f48c4a173c0d9f20bb52", sha(bytes))
        val lido = WhisperOrtNpuPacote.lerManifesto(bytes.toString(Charsets.UTF_8))
        assertNotNull("manifesto publicado do v3 tem de ser aceito", lido)
        assertEquals("3", lido!!.versao)
        assertTrue(lido.pontaAPonta)
        assertEquals(6, lido.itens.size)
        assertEquals(WhisperOrtNpuPacote.ARQUIVOS.toSet(), lido.itens.map { it.nome }.toSet())
        val contrato = lido.contrato
        assertNotNull(contrato)
        assertTrue("contrato v3 tem de conferir", WhisperOrtNpuPacote.contratoConfere(contrato))
        // Números pinados: logits, máscara, entrada do projetor e as 8 K/V.
        val logits = contrato!!.getJSONObject("logits")
        assertEquals(WhisperOrtHtpContrato.ESCALA_LOGIT, logits.getDouble("escala"), 0.0)
        assertEquals(WhisperOrtHtpContrato.OFFSET_LOGIT, logits.getInt("offset"))
        val mascara = contrato.getJSONObject("mascara")
        assertEquals(WhisperOrtHtpContrato.MASCARA_VALIDO, mascara.getInt("valido"))
        assertEquals("direita", mascara.getString("alinhamento"))
        val entrada = contrato.getJSONObject("projetor_entrada")
        assertEquals("sig_hidden_after_layer_norm", entrada.getString("nome"))
        assertEquals(WhisperOrtHtpContrato.ESCALA_OCULTO, entrada.getDouble("escala"), 0.0)
        assertEquals(WhisperOrtHtpContrato.OFFSET_QNN_OCULTO, entrada.getInt("offset"))
        val kv = contrato.getJSONObject("kv_cruzadas")
        assertEquals(8, kv.getInt("n"))
        assertEquals(-128, kv.getJSONObject("offsets").getInt("projetor"))
        assertEquals(128, kv.getJSONObject("offsets").getInt("decoder"))
        val escalas = kv.getJSONObject("escalas")
        for (i in 0 until 4) {
            assertTrue(escalas.getDouble("k_cache_cross_$i") > 0.0)
            assertTrue(escalas.getDouble("v_cache_cross_$i") > 0.0)
        }
        // Os seis arquivos publicados, com os SHAs dos pinos.
        assertEquals(
            "f44623b39d2e0d81d772a3a723777edf19163afa0887e2de12eed436a04bdfe1",
            lido.item(WhisperOrtNpuPacote.DECODER_CONTEXTO)!!.sha256,
        )
        assertEquals(
            "0994c2a9969f525616ec0586c4e2dcb0a996544db0c51701bbb9e5eafcb598bf",
            lido.item(WhisperOrtNpuPacote.CONTEXTO)!!.sha256,
        )
        assertEquals(677_531_160L, lido.item(WhisperOrtNpuPacote.CONTEXTO)!!.bytes)
    }

    @Test
    fun `manifesto v2 publicado continua aceito e nao e ponta a ponta`() {
        val lido = WhisperOrtNpuPacote.lerManifesto(manifestoPublicadoV2().toString(Charsets.UTF_8))
        assertNotNull(lido)
        assertEquals("2", lido!!.versao)
        assertFalse(lido.pontaAPonta)
        assertEquals(2, lido.itens.size)
        assertNull(lido.contrato)
        assertEquals(WhisperOrtNpuPacote.ARQUIVOS_ANTERIOR.toSet(), lido.itens.map { it.nome }.toSet())
    }

    @Test
    fun `manifesto v3 sem contrato e recusado`() {
        val raiz = JSONObject(manifestoPublicadoV3().toString(Charsets.UTF_8))
        raiz.remove("contrato")
        assertNull(WhisperOrtNpuPacote.lerManifesto(raiz.toString()))
    }

    @Test
    fun `manifesto v3 com cache diferente de 200 e recusado`() {
        val raiz = JSONObject(manifestoPublicadoV3().toString(Charsets.UTF_8))
        raiz.put("cache_posicoes", 448)
        assertNull(WhisperOrtNpuPacote.lerManifesto(raiz.toString()))
    }

    @Test
    fun `manifesto v3 com escala de logit trocada e recusado`() {
        val raiz = JSONObject(manifestoPublicadoV3().toString(Charsets.UTF_8))
        raiz.getJSONObject("contrato").getJSONObject("logits").put("escala", 0.001)
        assertNull(WhisperOrtNpuPacote.lerManifesto(raiz.toString()))
    }

    @Test
    fun `manifesto v3 sem uma das K-V cruzadas e recusado`() {
        val raiz = JSONObject(manifestoPublicadoV3().toString(Charsets.UTF_8))
        val escalas = raiz.getJSONObject("contrato").getJSONObject("kv_cruzadas").getJSONObject("escalas")
        escalas.remove("v_cache_cross_3")
        assertNull(WhisperOrtNpuPacote.lerManifesto(raiz.toString()))
    }

    @Test
    fun `manifesto v3 sem o decoder e recusado`() {
        val raiz = JSONObject(manifestoPublicadoV3().toString(Charsets.UTF_8))
        val arquivos = raiz.getJSONArray("arquivos")
        for (i in arquivos.length() - 1 downTo 0) {
            if (arquivos.getJSONObject(i).getString("nome") == WhisperOrtNpuPacote.DECODER_CONTEXTO) {
                arquivos.remove(i)
            }
        }
        assertNull(WhisperOrtNpuPacote.lerManifesto(raiz.toString()))
    }

    // ---------------------------------------------------------------- admissão

    @Test
    fun `admissao escolhe a rota pela versao verificada`() {
        assertEquals(
            WhisperOrtHtpAdmissao.RotaNpu.PONTA_A_PONTA,
            WhisperOrtHtpAdmissao.rotaDaVersao("3"),
        )
        assertEquals(
            WhisperOrtHtpAdmissao.RotaNpu.ENCODER_HTP,
            WhisperOrtHtpAdmissao.rotaDaVersao("2"),
        )
        assertEquals(WhisperOrtHtpAdmissao.RotaNpu.CPU, WhisperOrtHtpAdmissao.rotaDaVersao(null))
        assertEquals(WhisperOrtHtpAdmissao.RotaNpu.CPU, WhisperOrtHtpAdmissao.rotaDaVersao(""))
        assertEquals(WhisperOrtHtpAdmissao.RotaNpu.CPU, WhisperOrtHtpAdmissao.rotaDaVersao("9"))
    }

    // ------------------------------------------------- remoção atômica do v2

    @Test
    fun `v2 e removido depois do v3 e nada sobra no caminho oficial`() {
        val raiz = temp.newFolder("raiz-remocao")
        val v2 = File(raiz, WhisperOrtNpuPacote.VERSAO_ANTERIOR)
        v2.mkdirs()
        File(v2, WhisperOrtNpuPacote.CONTEXTO).writeBytes(ByteArray(16))
        assertTrue(WhisperOrtNpuPacote.removerVersaoAnterior(raiz))
        assertFalse("o caminho do v2 tem de sumir de uma vez", v2.exists())
        assertTrue(raiz.listFiles().orEmpty().none { it.name.startsWith(".remover-") })
    }

    @Test
    fun `falha no rename nao apaga nada do v2`() {
        val raiz = temp.newFolder("raiz-rename")
        val v2 = File(raiz, WhisperOrtNpuPacote.VERSAO_ANTERIOR)
        v2.mkdirs()
        File(v2, WhisperOrtNpuPacote.CONTEXTO).writeBytes(ByteArray(16))
        val removeu = WhisperOrtNpuPacote.removerVersaoAnterior(
            raiz,
            renomear = { _, _ -> false },
            apagar = { error("não pode apagar sem rename") },
        )
        assertFalse(removeu)
        assertTrue("o v2 continua inteiro", File(v2, WhisperOrtNpuPacote.CONTEXTO).isFile)
    }

    @Test
    fun `falha no meio do apagamento nao deixa o v2 meio visivel`() {
        val raiz = temp.newFolder("raiz-apagar")
        val v2 = File(raiz, WhisperOrtNpuPacote.VERSAO_ANTERIOR)
        v2.mkdirs()
        File(v2, WhisperOrtNpuPacote.CONTEXTO).writeBytes(ByteArray(16))
        val removeu = WhisperOrtNpuPacote.removerVersaoAnterior(
            raiz,
            // Falha no MEIO do apagamento: o túmulo fica pela metade.
            apagar = { tumulo -> File(tumulo, "resto").writeText("lixo"); false },
        )
        // O diretório oficial do v2 já não existe: a visão do app é atômica mesmo
        // com o apagamento do túmulo falhando.
        assertTrue(removeu)
        assertFalse(v2.exists())
        assertTrue(raiz.listFiles().orEmpty().any { it.name.startsWith(".remover-") })
    }

    @Test
    fun `sem v2 a remocao e no-op`() {
        val raiz = temp.newFolder("raiz-vazio")
        assertTrue(WhisperOrtNpuPacote.removerVersaoAnterior(raiz))
    }

    // ---------------------------------------------------------------- oferta

    private class Memoria : WhisperNpuOferta.Armazenamento {
        var recusa = false
        var recusaAtualizacao = false
        override fun recusado(): Boolean = recusa
        override fun registrarRecusa() {
            recusa = true
        }
        override fun atualizacaoRecusada(): Boolean = recusaAtualizacao
        override fun registrarRecusaAtualizacao() {
            recusaAtualizacao = true
        }
    }

    @Test
    fun `oferta de atualizacao aparece uma vez com o v2 instalado`() {
        val memoria = Memoria()
        assertTrue(WhisperNpuOferta.deveOferecerAtualizacao(memoria, WhisperNpuOferta.Instalado.V2))
        memoria.registrarRecusaAtualizacao()
        assertFalse("depois de 'agora não' não pergunta de novo",
            WhisperNpuOferta.deveOferecerAtualizacao(memoria, WhisperNpuOferta.Instalado.V2))
        assertFalse(WhisperNpuOferta.deveOferecerAtualizacao(Memoria(), WhisperNpuOferta.Instalado.V3))
        assertFalse(WhisperNpuOferta.deveOferecerAtualizacao(Memoria(), WhisperNpuOferta.Instalado.NENHUM))
    }

    @Test
    fun `a recusa da atualizacao nao bloqueia a oferta inicial`() {
        val memoria = Memoria()
        memoria.registrarRecusaAtualizacao()
        assertTrue(WhisperNpuOferta.deveOferecerAutomaticamente(memoria, WhisperNpuOferta.Situacao.AUSENTE))
    }

    @Test
    fun `entrada fixa diz Atualizar com o v2 e NPU ativa com o v3`() {
        assertEquals(
            WhisperNpuOferta.ROTULO_ATUALIZACAO,
            WhisperNpuOferta.rotuloEntradaFixa(
                WhisperNpuOferta.Situacao.PRONTO, qairtInstalado = true,
                instalado = WhisperNpuOferta.Instalado.V2,
            ),
        )
        assertEquals(
            "NPU ativa",
            WhisperNpuOferta.rotuloEntradaFixa(
                WhisperNpuOferta.Situacao.PRONTO, qairtInstalado = true,
                instalado = WhisperNpuOferta.Instalado.V3,
            ),
        )
        assertEquals(
            "Ativar aceleração NPU",
            WhisperNpuOferta.rotuloEntradaFixa(
                WhisperNpuOferta.Situacao.AUSENTE, qairtInstalado = true,
                instalado = WhisperNpuOferta.Instalado.NENHUM,
            ),
        )
    }

    @Test
    fun `plano da atualizacao aponta o v3 e diz o tamanho aproximado`() {
        val plano = WhisperOrtNpuPacote.planoDeDownload(qairtInstalado = true, atualizacao = true)
        assertEquals("Atualizar aceleração NPU (~990 MB)", plano.rotulo)
        assertEquals(1, plano.arquivos.size)
        assertEquals(WhisperOrtNpuPacote.BYTES_ZIP, plano.totalBytes)
        assertTrue(plano.arquivos[0].nome.endsWith("-v3.zip"))
    }

    @Test
    fun `dir ativo prefere o v3 e cai para o v2`() {
        val raiz = temp.newFolder("raiz-ativo")
        val v3 = File(raiz, WhisperOrtNpuPacote.VERSAO).apply { mkdirs() }
        val v2 = File(raiz, WhisperOrtNpuPacote.VERSAO_ANTERIOR).apply { mkdirs() }
        File(v3, WhisperOrtNpuPacote.NOME_MANIFESTO).writeText("{}")
        File(v2, WhisperOrtNpuPacote.NOME_MANIFESTO).writeText("{}")
        assertEquals(v3, WhisperOrtNpuPacote.dirAtivo(raiz))
        File(v3, WhisperOrtNpuPacote.NOME_MANIFESTO).delete()
        assertEquals("sem manifesto o v3 não conta", v2, WhisperOrtNpuPacote.dirAtivo(raiz))
        File(v2, WhisperOrtNpuPacote.NOME_MANIFESTO).delete()
        assertEquals(v3, WhisperOrtNpuPacote.dirAtivo(raiz))
    }
}
