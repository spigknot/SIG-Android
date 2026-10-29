package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * R18/A2 — o teste que teria pegado o "Q5" que era BASE na R17.
 */
class ModelIdentityGateTest {

    private val baseKey =
        "/data/user/0/br.gov.sp.pcsp.whisperlab/files/whisper_models/ggml-base.bin|0|false|" +
            "60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe:147951465"

    private val q5Key =
        "/storage/emulated/0/Android/data/br.gov.sp.pcsp.whisperlab/files/whisper_models/" +
            "ggml-large-v3-turbo-q5_0.bin|0|false|" +
            "394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2:574041195"

    private val f16Key =
        "/x/ggml-large-v3-turbo.bin|0|false|" +
            "1fc70f774d38eb169993ac391eea357ef47c88757ef72ee5943879b7e8e2bc69:1624555275"

    @Test
    fun `parse le a chave real do model_loaded`() {
        val c = ModelIdentityGate.parseLoaded(baseKey)!!
        assertEquals("ggml-base.bin", c.nome)
        assertEquals(147_951_465L, c.bytes)
        assertEquals(ModelIdentityGate.BASE.sha256, c.sha256)
    }

    @Test
    fun `chave ilegivel vira null e nao identidade vazia`() {
        assertNull(ModelIdentityGate.parseLoaded(null))
        assertNull(ModelIdentityGate.parseLoaded(""))
        assertNull(ModelIdentityGate.parseLoaded("sem-pipes"))
        assertNull(ModelIdentityGate.parseLoaded("/a|0|false|nao-e-sha:123"))
        assertNull(ModelIdentityGate.parseLoaded("/a|0|false|" + "0".repeat(64) + ":abc"))
    }

    @Test
    fun `o caso da R17 - pediu Q5 e carregou BASE - e MISMATCH`() {
        val r = ModelIdentityGate.confrontar(
            ModelIdentityGate.esperado("q5Turbo"),
            ModelIdentityGate.parseLoaded(baseKey)
        )
        assertEquals(ModelIdentityGate.Veredito.MISMATCH, r.veredito)
        assertTrueContains(r.motivo, "147951465")
    }

    @Test
    fun `Q5 correto passa`() {
        val r = ModelIdentityGate.confrontar(
            ModelIdentityGate.esperado("q5Turbo"),
            ModelIdentityGate.parseLoaded(q5Key)
        )
        assertEquals(ModelIdentityGate.Veredito.PASS, r.veredito)
    }

    @Test
    fun `F16 correto passa e BASE no controle tambem`() {
        assertEquals(
            ModelIdentityGate.Veredito.PASS,
            ModelIdentityGate.confrontar(
                ModelIdentityGate.esperado("v3Turbo"),
                ModelIdentityGate.parseLoaded(f16Key)
            ).veredito
        )
        assertEquals(
            ModelIdentityGate.Veredito.PASS,
            ModelIdentityGate.confrontar(
                ModelIdentityGate.esperado("base"),
                ModelIdentityGate.parseLoaded(baseKey)
            ).veredito
        )
    }

    @Test
    fun `nome certo com bytes errados reprova`() {
        val adulterado = baseKey.replace(":147951465", ":147951466")
        val r = ModelIdentityGate.confrontar(
            ModelIdentityGate.esperado("base"),
            ModelIdentityGate.parseLoaded(adulterado)
        )
        assertEquals(ModelIdentityGate.Veredito.MISMATCH, r.veredito)
    }

    @Test
    fun `sha errado com bytes certos reprova`() {
        val adulterado = baseKey.replace("60ed5bc3", "70ed5bc3")
        val r = ModelIdentityGate.confrontar(
            ModelIdentityGate.esperado("base"),
            ModelIdentityGate.parseLoaded(adulterado)
        )
        assertEquals(ModelIdentityGate.Veredito.MISMATCH, r.veredito)
    }

    @Test
    fun `loaded ausente e UNKNOWN - nunca PASS`() {
        val r = ModelIdentityGate.confrontar(ModelIdentityGate.esperado("base"), null)
        assertEquals(ModelIdentityGate.Veredito.UNKNOWN, r.veredito)
    }

    @Test
    fun `rotulo desconhecido e UNKNOWN`() {
        val r = ModelIdentityGate.confrontar(ModelIdentityGate.esperado("qualquer"), null)
        assertEquals(ModelIdentityGate.Veredito.UNKNOWN, r.veredito)
        assertNull(ModelIdentityGate.esperado(""))
    }

    @Test
    fun `selecao exige callback corrente E sha esperado`() {
        val esperado = ModelIdentityGate.TURBO_Q5
        // publicado com sha certo mas callback stale -> recusada
        assertEquals(
            "stale_ou_recusada",
            ModelIdentityGate.selecao(false, esperado.sha256, esperado).motivo
        )
        // callback corrente mas arquivo é outro modelo -> recusada
        assertEquals(
            "sha_divergente_Turbo Q5_0",
            ModelIdentityGate.selecao(true, ModelIdentityGate.BASE.sha256, esperado).motivo
        )
        // ok
        assertEquals(
            "selecionado_Turbo Q5_0",
            ModelIdentityGate.selecao(true, esperado.sha256, esperado).motivo
        )
    }

    private fun assertTrueContains(s: String, sub: String) {
        org.junit.Assert.assertTrue("esperava '$sub' em '$s'", s.contains(sub))
    }
}
