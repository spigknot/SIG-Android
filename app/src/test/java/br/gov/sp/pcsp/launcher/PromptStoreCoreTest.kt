package br.gov.sp.pcsp.launcher

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Núcleo dos prompts: seed do padrão, migração do legado, proteção do
 *  `Padrão`, validação do download do R2 e importação de `.txt`. */
class PromptStoreCoreTest {

    private val temp: File = Files.createTempDirectory("sig-prompts").toFile()

    private val assets = mapOf(
        "historico_system.txt" to "SISTEMA DO HISTORICO",
        "historico_user.txt" to "TRANSCRICAO: $HISTORY_TRANSCRIPT_MARKER",
        "oitiva_system.txt" to "SISTEMA DA OITIVA",
        "oitiva_user.txt" to "HISTORICO: $STATEMENT_HISTORY_MARKER",
        "partes_system.txt" to "PARTES SISTEMA",
    )

    private fun core(): PromptStoreCore = PromptStoreCore(temp) { assets[it] }

    private fun validFiles(
        historySystem: String = "SISTEMA DO HISTORICO",
        historyUser: String = "TRANSCRICAO: $HISTORY_TRANSCRIPT_MARKER",
        statementSystem: String = "SISTEMA DA OITIVA",
        statementUser: String = "HISTORICO: $STATEMENT_HISTORY_MARKER",
    ): Map<String, String> = mapOf(
        "historico_system.txt" to historySystem,
        "historico_user.txt" to historyUser,
        "oitiva_system.txt" to statementSystem,
        "oitiva_user.txt" to statementUser,
    )

    @After
    fun cleanup() {
        temp.deleteRecursively()
    }

    @Test
    fun seedCriaOArrayDePadraoELeAtivo() {
        val store = core()
        store.ensureLayout()

        for (slot in PromptSlot.values()) {
            assertTrue(File(File(temp, "padrao"), slot.file).isFile)
            assertEquals("padrao", store.activeId(slot))
            assertEquals(assets.getValue(slot.file), store.readActive(slot))
        }
        assertTrue(File(temp, "LEIA-ME.txt").isFile)
        assertTrue(File(temp, "LEIA-ME.txt").readText().contains("padrao/"))
        assertFalse(File(temp, "historico_system.txt").exists())
    }

    @Test
    fun listaComecaComOPadraoEmCadaSlot() {
        val store = core()
        store.ensureLayout()

        val entries = store.entries()
        assertEquals(PromptSlot.values().size * 1, entries.size)
        for (slot in PromptSlot.values()) {
            val slotEntries = entries.filter { it.slot == slot }
            assertEquals(PROMPT_DEFAULT_ID, slotEntries.first().id)
            assertTrue(slotEntries.first().isDefault)
            assertTrue(slotEntries.first().active)
            assertEquals("Padrão", slotEntries.first().optionLabel)
        }
    }

    @Test
    fun rotuloDaOpcaoEhPadraOuDezCaracteresComReticencias() {
        val store = core()
        store.ensureLayout()

        assertNull(store.saveCustom(PromptSlot.HISTORY_SYSTEM, "esse_e_um_prompt_longo", "X", overwrite = false))
        assertNull(store.saveCustom(PromptSlot.STATEMENT_SYSTEM, "abcdefghij", "Y", overwrite = false))
        assertNull(store.saveCustom(PromptSlot.STATEMENT_USER, "curto", "Z: $STATEMENT_HISTORY_MARKER", overwrite = false))

        val entries = store.entries()
        // Nome maior que 10: primeiros 10 caracteres + "...", sem extensão.
        assertEquals("esse_e_um_...", entries.first { it.id == "esse_e_um_prompt_longo" }.optionLabel)
        // Exatamente 10: nome inteiro, sem reticências.
        assertEquals("abcdefghij", entries.first { it.id == "abcdefghij" }.optionLabel)
        // Menos de 10: nome inteiro.
        assertEquals("curto", entries.first { it.id == "curto" }.optionLabel)
        // O Padrão nunca aparece com o nome do arquivo.
        assertTrue(entries.filter { it.isDefault }.all { it.optionLabel == "Padrão" })
    }

    @Test
    fun migracao_deArquivoIgualAoAsset_viraPadrao() {
        File(temp, "historico_system.txt").writeText(assets.getValue("historico_system.txt"), Charsets.UTF_8)

        val store = core()
        store.ensureLayout()

        assertFalse(File(temp, "historico_system.txt").exists())
        assertEquals(PROMPT_DEFAULT_ID, store.activeId(PromptSlot.HISTORY_SYSTEM))
        assertEquals(assets.getValue("historico_system.txt"), store.readActive(PromptSlot.HISTORY_SYSTEM))
        assertTrue(store.entries().none { it.slot == PromptSlot.HISTORY_SYSTEM && !it.isDefault })
    }

    @Test
    fun migracao_deArquivoEditado_viraCustomizadoEContinuaEmUso() {
        File(temp, "oitiva_system.txt").writeText("EDITEI ANTES DA TELA", Charsets.UTF_8)

        val store = core()
        store.ensureLayout()

        assertFalse(File(temp, "oitiva_system.txt").exists())
        assertEquals("oitiva_system", store.activeId(PromptSlot.STATEMENT_SYSTEM))
        assertEquals("EDITEI ANTES DA TELA", store.readActive(PromptSlot.STATEMENT_SYSTEM))
        assertTrue(File(File(File(temp, "custom"), "oitiva_system"), "oitiva_system.txt").isFile)
        assertTrue(store.entries().any { it.slot == PromptSlot.STATEMENT_SYSTEM && it.id == "oitiva_system" && it.active })
    }

    @Test
    fun migracao_comIdJaOcupadoNaoApagaOCustomizadoExistente() {
        val customDir = File(File(temp, "custom"), "historico_system").apply { mkdirs() }
        File(customDir, "historico_system.txt").writeText("MEU CUSTO", Charsets.UTF_8)
        File(temp, "historico_system.txt").writeText("LEGADO EDITADO", Charsets.UTF_8)

        val store = core()
        store.ensureLayout()

        assertEquals("MEU CUSTO", store.read(PromptSlot.HISTORY_SYSTEM, "historico_system"))
        assertEquals("LEGADO EDITADO", store.read(PromptSlot.HISTORY_SYSTEM, "historico_system_2"))
        assertEquals("historico_system_2", store.activeId(PromptSlot.HISTORY_SYSTEM))
    }

    @Test
    fun padraoNaoPodeSerSobrescritoPelaTela() {
        val store = core()
        store.ensureLayout()

        val erro = store.saveCustom(PromptSlot.HISTORY_SYSTEM, PROMPT_DEFAULT_ID, "NOVO TEXTO", overwrite = true)

        assertNotNull(erro)
        assertTrue(erro!!.contains("Padrão"))
        assertEquals(assets.getValue("historico_system.txt"), store.readPadrao(PromptSlot.HISTORY_SYSTEM))
    }

    @Test
    fun temporaryDefaultEditsCannotWriteOrDeleteAnySlotEvenWithOverwrite() {
        val store = core()
        store.ensureLayout()
        for (slot in PromptSlot.values()) {
            val file = File(File(temp, "padrao"), slot.file)
            val before = file.readBytes()
            for (overwrite in listOf(false, true)) {
                for (id in listOf(PROMPT_DEFAULT_ID, "PADRAO")) {
                    assertNotNull(store.saveCustom(slot, id, "TEMP ${assets.getValue(slot.file)}", overwrite))
                    assertTrue(before.contentEquals(file.readBytes()))
                }
            }
            assertNotNull(store.deleteCustom(slot, PROMPT_DEFAULT_ID))
            assertTrue(before.contentEquals(file.readBytes()))
            assertEquals(assets.getValue(slot.file), core().readActive(slot))
            assertEquals(1, store.entries().count { it.slot == slot })
        }
    }

    @Test
    fun salvarComoCriaCustomizadoEPermiteTrocarDePrompt() {
        val store = core()
        store.ensureLayout()

        assertNull(store.saveCustom(PromptSlot.HISTORY_USER, "meu historico", "T: $HISTORY_TRANSCRIPT_MARKER", overwrite = false))
        assertNotNull(store.saveCustom(PromptSlot.HISTORY_USER, "meu historico", "T: $HISTORY_TRANSCRIPT_MARKER", overwrite = false))
        assertEquals("meu_historico_2", store.suggestId(PromptSlot.HISTORY_USER, "meu historico"))

        assertNull(store.setActive(PromptSlot.HISTORY_USER, "meu_historico"))
        assertEquals("meu_historico", store.activeId(PromptSlot.HISTORY_USER))
        assertEquals("T: $HISTORY_TRANSCRIPT_MARKER", store.readActive(PromptSlot.HISTORY_USER))
        assertNotNull(store.setActive(PromptSlot.HISTORY_USER, "nao_existe"))
    }

    @Test
    fun downloadReprovaMarcadorAusenteEVazioSemGravarNada() {
        val store = core()
        store.ensureLayout()

        val semMarcador = store.applyDefaults(validFiles(historyUser = "sem marcador nenhum"))
        assertNotNull(semMarcador)
        assertTrue(semMarcador!!.contains(HISTORY_TRANSCRIPT_MARKER))

        val vazio = store.applyDefaults(validFiles(statementSystem = "   "))
        assertNotNull(vazio)

        val incompleto = store.applyDefaults(validFiles().filterKeys { it != "oitiva_system.txt" })
        assertNotNull(incompleto)

        // Nada mudou no padrão.
        assertEquals(assets.getValue("historico_user.txt"), store.readPadrao(PromptSlot.HISTORY_USER))
        assertEquals(assets.getValue("oitiva_system.txt"), store.readPadrao(PromptSlot.STATEMENT_SYSTEM))
    }

    @Test
    fun downloadTrocaPadraoEPreservaOsCustomizados() {
        val store = core()
        store.ensureLayout()
        assertNull(store.saveCustom(PromptSlot.STATEMENT_USER, "minha oitiva", "X: $STATEMENT_HISTORY_MARKER", overwrite = false))
        assertNull(store.setActive(PromptSlot.STATEMENT_USER, "minha_oitiva"))

        val novo = validFiles(statementUser = "NOVO: $STATEMENT_HISTORY_MARKER")
        assertNull(store.applyDefaults(novo))

        assertEquals("NOVO: $STATEMENT_HISTORY_MARKER", store.readPadrao(PromptSlot.STATEMENT_USER))
        // Continua usando o customizado, então a troca do padrão não muda a requisição.
        assertEquals("X: $STATEMENT_HISTORY_MARKER", store.readActive(PromptSlot.STATEMENT_USER))

        assertNull(store.setActive(PromptSlot.STATEMENT_USER, PROMPT_DEFAULT_ID))
        assertEquals("NOVO: $STATEMENT_HISTORY_MARKER", store.readActive(PromptSlot.STATEMENT_USER))
    }

    @Test
    fun downloadReprovaTamanhoExcessivoEUtf8Invalido() {
        val store = core()
        store.ensureLayout()

        val gigante = "A".repeat(MAX_PROMPT_BYTES + 1)
        assertNotNull(store.validate(PromptSlot.HISTORY_SYSTEM, gigante))

        val invalido = byteArrayOf(0xC3.toByte(), 0x28)
        assertNull(store.decodeStrict(invalido))
        assertEquals("texto válido", store.decodeStrict("texto válido".toByteArray()))
    }

    @Test
    fun marcadoresDoContratoBatemComOsDasRequisicoes() {
        // Vacina: se o marcador mudar num dos lados, a requisição passa a
        // enviar o marcador cru sem ninguém perceber.
        assertTrue(store_markersHisto())
        val store = core()
        assertNull(store.validate(PromptSlot.HISTORY_USER, "ok $HISTORY_TRANSCRIPT_MARKER"))
        assertNull(store.validate(PromptSlot.STATEMENT_USER, "ok $STATEMENT_HISTORY_MARKER"))
        assertNull(store.validate(PromptSlot.STATEMENT_USER, "ok $STATEMENT_HISTORY_LEGACY_MARKER"))
        assertNotNull(store.validate(PromptSlot.STATEMENT_USER, "ok sem marcador"))
        assertEquals(listOf("historico_system.txt", "historico_user.txt", "oitiva_system.txt", "oitiva_user.txt"), R2_PROMPT_KEYS)
        assertEquals(PromptSlot.values().map { it.file }, R2_PROMPT_KEYS)
    }

    private fun store_markersHisto(): Boolean =
        HISTORY_TRANSCRIPT_MARKER == "{{conteudo_caixa_transcricao}}" &&
            STATEMENT_HISTORY_MARKER == "{{{conteudo_caixa_historico}}}"

    @Test
    fun atualizacaoSoSinalizaComoNovoOQueDiferirDoPadrao() {
        val store = core()
        store.ensureLayout()

        // Mesmo conteúdo do que já está no aparelho: nada a gravar.
        assertEquals(emptyList<String>(), store.changedDefaults(validFiles()))
        // Um arquivo só do R2 já muda o resultado (é o "se houverem" do botão).
        assertEquals(
            listOf("historico_system.txt"),
            store.changedDefaults(validFiles(historySystem = "OUTRO CONTEUDO")),
        )
        // Prompt ausente também conta como mudança, para não deixar buraco.
        assertEquals(
            listOf("oitiva_user.txt"),
            store.changedDefaults(validFiles().filterKeys { it != "oitiva_user.txt" }),
        )
    }

    @Test
    fun inferenciaDeSlotPorNomePorMarcadorEhNulaQuandoNaoDa() {
        val store = core()
        assertEquals(PromptSlot.HISTORY_SYSTEM, store.inferSlot("meu_historico_system.txt", "texto qualquer"))
        assertEquals(PromptSlot.STATEMENT_SYSTEM, store.inferSlot("Oitiva Sistema.txt", "texto qualquer"))
        assertEquals(PromptSlot.HISTORY_USER, store.inferSlot("qualquer.txt", "usa $HISTORY_TRANSCRIPT_MARKER"))
        assertEquals(PromptSlot.STATEMENT_USER, store.inferSlot("qualquer.txt", "usa $STATEMENT_HISTORY_MARKER"))
        assertEquals(PromptSlot.HISTORY_USER, store.inferSlot("historico_usuario.txt", "texto qualquer"))
        assertNull(store.inferSlot("notas_da_reuniao.txt", "texto qualquer"))
    }

    @Test
    fun importarGravaNoSlotIndicadoDeixaEmUsoESanitizaOId() {
        val store = core()
        store.ensureLayout()

        val texto = "NOVO CONTEUDO: $STATEMENT_HISTORY_MARKER"
        assertNull(store.importPrompt(PromptSlot.STATEMENT_USER, "Minha Oitiva.txt", texto))

        assertEquals("minha_oitiva", store.activeId(PromptSlot.STATEMENT_USER))
        assertEquals(texto, store.readActive(PromptSlot.STATEMENT_USER))
        assertTrue(File(File(File(temp, "custom"), "oitiva_user"), "minha_oitiva.txt").isFile)

        // Id que tenta sair da pasta é neutralizado.
        assertEquals("passwd", store.sanitizeId("../etc/passwd"))
        assertEquals("", store.sanitizeId("   "))
        assertEquals("prompt", store.suggestId(PromptSlot.HISTORY_SYSTEM, "   "))
    }

    @Test
    fun excluirSoParaCustomizadosEPadraoVoltaAUso() {
        val store = core()
        store.ensureLayout()

        // Padrão é intocável — mesma regra do botão Salvar.
        assertNotNull(store.deleteCustom(PromptSlot.HISTORY_SYSTEM, PROMPT_DEFAULT_ID))
        assertTrue(File(File(temp, "padrao"), "historico_system.txt").isFile)

        assertNull(store.saveCustom(PromptSlot.HISTORY_SYSTEM, "temporario", "TEMP", overwrite = false))
        assertNull(store.setActive(PromptSlot.HISTORY_SYSTEM, "temporario"))
        assertTrue(store.entries().any { it.id == "temporario" && it.active })

        assertNull(store.deleteCustom(PromptSlot.HISTORY_SYSTEM, "temporario"))

        assertFalse(File(File(File(temp, "custom"), "historico_system"), "temporario.txt").exists())
        assertTrue(store.entries().none { it.id == "temporario" })
        // O que estava em uso cai de volta para o Padrão.
        assertEquals(PROMPT_DEFAULT_ID, store.activeId(PromptSlot.HISTORY_SYSTEM))
        assertEquals(assets.getValue("historico_system.txt"), store.readActive(PromptSlot.HISTORY_SYSTEM))
        // Excluir de novo (já não existe) é erro, não sucesso silencioso.
        assertNotNull(store.deleteCustom(PromptSlot.HISTORY_SYSTEM, "temporario"))
    }

    @Test
    fun novoPromptParteDoPadraoMesmoComCustomAtivoEPersisteCadaSecao() {
        val store = core()
        store.ensureLayout()
        for (slot in PromptSlot.values()) {
            val text = assets.getValue(slot.file)
            assertNull(store.saveCustom(slot, "anterior", "CUSTOM $text", overwrite = false))
            assertNull(store.setActive(slot, "anterior"))
            // The '+' editor reads the default explicitly, never readActive().
            val initial = store.read(slot, PROMPT_DEFAULT_ID)
            assertEquals(text, initial)
            assertFalse(initial == store.readActive(slot))
            assertNull(store.saveCustom(slot, "novo", initial, overwrite = false))
            assertNull(store.setActive(slot, "novo"))
        }
        val reopened = core()
        reopened.ensureLayout()
        for (slot in PromptSlot.values()) {
            assertEquals("novo", reopened.activeId(slot))
            assertEquals(assets.getValue(slot.file), reopened.readActive(slot))
            assertEquals(PROMPT_DEFAULT_ID, reopened.entries().first { it.slot == slot }.id)
        }
        assertNull(reopened.deleteCustom(PromptSlot.HISTORY_SYSTEM, "novo"))
        assertEquals(PROMPT_DEFAULT_ID, reopened.activeId(PromptSlot.HISTORY_SYSTEM))
        assertEquals("novo", reopened.activeId(PromptSlot.HISTORY_USER))
        assertEquals("novo", reopened.activeId(PromptSlot.STATEMENT_SYSTEM))
        assertEquals("novo", reopened.activeId(PromptSlot.STATEMENT_USER))
    }

    @Test
    fun customizadoApagadoCaiParaOPadrao() {
        val store = core()
        store.ensureLayout()
        assertNull(store.saveCustom(PromptSlot.HISTORY_SYSTEM, "temporario", "TEMP", overwrite = false))
        assertNull(store.setActive(PromptSlot.HISTORY_SYSTEM, "temporario"))

        File(File(File(temp, "custom"), "historico_system"), "temporario.txt").delete()

        assertEquals(assets.getValue("historico_system.txt"), store.readActive(PromptSlot.HISTORY_SYSTEM))
        store.ensureLayout()
        assertEquals(PROMPT_DEFAULT_ID, store.activeId(PromptSlot.HISTORY_SYSTEM))
    }
}
