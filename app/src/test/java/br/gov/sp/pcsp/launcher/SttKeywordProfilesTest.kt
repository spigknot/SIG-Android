package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Perfis de keywords: nome, criação/remoção/renomeação, seleção ativa e a
 *  migração do formato antigo (lista única). */
class SttKeywordProfilesTest {

    private val lista1 = KeywordProfile("Lista 1", listOf("placa", "abordagem"))
    private val lista2 = KeywordProfile("Operação X", listOf("Furtura"))

    @Test
    fun label_mostraONomeDoPerfilOuODesligado() {
        assertEquals("Keywords: Lista 1", SttKeywordProfiles.label("Lista 1"))
        assertEquals("Keywords: Não", SttKeywordProfiles.label(null))
        assertEquals("Keywords: Não", SttKeywordProfiles.label("   "))
    }

    @Test
    fun encodeDecode_preservaOrdemNomeETermos() {
        val perfis = listOf(lista1, lista2)

        val volta = SttKeywordProfiles.decode(SttKeywordProfiles.encode(perfis))

        assertEquals(perfis, volta)
        assertEquals(emptyList<KeywordProfile>(), SttKeywordProfiles.decode(""))
        assertEquals(emptyList<KeywordProfile>(), SttKeywordProfiles.decode("não é json"))
        assertEquals(emptyList<KeywordProfile>(), SttKeywordProfiles.decode("[{\"x\":1}]"))
    }

    @Test
    fun decode_descartaNomesRepetidosEMantemOLimite() {
        val repetidos = """[{"name":"A","keywords":["x"]},{"name":"a","keywords":["y"]}]"""
        assertEquals(1, SttKeywordProfiles.decode(repetidos).size)

        val muitos = (1..SttKeywordProfiles.MAX_PROFILES + 5)
            .joinToString(",", "[", "]") { """{"name":"p$it","keywords":["t$it"]}""" }
        assertEquals(SttKeywordProfiles.MAX_PROFILES, SttKeywordProfiles.decode(muitos).size)
    }

    @Test
    fun nameError_recusaVazioLongoRepetidoEDesligado() {
        val existentes = listOf(lista1)
        assertEquals("Digite o nome do perfil.", SttKeywordProfiles.nameError("  ", existentes))
        assertEquals(
            "Use no máximo ${SttKeywordProfiles.MAX_NAME_LENGTH} caracteres.",
            SttKeywordProfiles.nameError("x".repeat(SttKeywordProfiles.MAX_NAME_LENGTH + 1), existentes),
        )
        assertEquals("\"lista 1\" já existe.", SttKeywordProfiles.nameError("lista 1", existentes))
        assertTrue(SttKeywordProfiles.nameError("Não", existentes)!!.contains("desligado"))
        assertNull(SttKeywordProfiles.nameError("Operação Y", existentes))
        // Renomear mantendo o PRÓPRIO nome é permitido (só muda maiúsculas).
        assertNull(SttKeywordProfiles.nameError("LISTA 1", existentes, renaming = "Lista 1"))
    }

    @Test
    fun nextDefaultName_evitaColisao() {
        assertEquals("Lista 1", SttKeywordProfiles.nextDefaultName(emptyList()))
        assertEquals("Lista 2", SttKeywordProfiles.nextDefaultName(listOf(lista1)))
        assertEquals(
            "Lista 3",
            SttKeywordProfiles.nextDefaultName(listOf(lista1, KeywordProfile("lista 2", emptyList()))),
        )
    }

    @Test
    fun withProfile_criaOuAtualizaPreservandoAOrdem() {
        val criado = SttKeywordProfiles.withProfile(listOf(lista1), "Operação X", listOf(" Furtura ", "Furtura"))
        assertEquals(2, criado.size)
        assertEquals("Furtura", criado[1].keywords.single())

        val atualizado = SttKeywordProfiles.withProfile(criado, "lista 1", listOf("placa"))
        assertEquals(2, atualizado.size)
        assertEquals("Lista 1", atualizado[0].name)          // mantém a grafia original
        assertEquals(listOf("placa"), atualizado[0].keywords)
    }

    @Test
    fun withoutProfile_eRenamed_mexemSoNoPerfilCerto() {
        val perfis = listOf(lista1, lista2)
        assertEquals(listOf(lista1), SttKeywordProfiles.withoutProfile(perfis, "operação x"))
        assertEquals(perfis, SttKeywordProfiles.withoutProfile(perfis, null))
        assertEquals(perfis, SttKeywordProfiles.withoutProfile(perfis, "  "))

        val renomeado = SttKeywordProfiles.renamed(perfis, "Lista 1", "Operação Z")
        assertEquals("Operação Z", renomeado[0].name)
        assertEquals(listOf("placa", "abordagem"), renomeado[0].keywords)
        assertEquals(lista2, renomeado[1])
    }

    @Test
    fun resolveSelection_ignoraCaixaEMDevolveNullQuandoOPerfilSumiu() {
        val perfis = listOf(lista1, lista2)
        assertEquals("Lista 1", SttKeywordProfiles.resolveSelection(perfis, "lista 1"))
        assertNull(SttKeywordProfiles.resolveSelection(perfis, "apagado"))
        assertNull(SttKeywordProfiles.resolveSelection(perfis, ""))
        assertNull(SttKeywordProfiles.resolveSelection(perfis, null))
    }

    @Test
    fun keywordsOf_devolveOsTermosDoPerfilAtivo() {
        val perfis = listOf(lista1, lista2)
        assertEquals(listOf("placa", "abordagem"), SttKeywordProfiles.keywordsOf(perfis, "Lista 1"))
        assertEquals(listOf("Furtura"), SttKeywordProfiles.keywordsOf(perfis, "Operação X"))
        assertEquals(emptyList<String>(), SttKeywordProfiles.keywordsOf(perfis, null))
        assertEquals(emptyList<String>(), SttKeywordProfiles.keywordsOf(perfis, "sumiu"))
    }

    @Test
    fun migratedFromSingleList_viraUmPerfil() {
        assertEquals(
            listOf(KeywordProfile("Lista 1", listOf("placa", "abordagem"))),
            SttKeywordProfiles.migratedFromSingleList(listOf(" placa ", "abordagem", "PLACA")),
        )
        assertEquals(emptyList<KeywordProfile>(), SttKeywordProfiles.migratedFromSingleList(emptyList()))
    }
}
